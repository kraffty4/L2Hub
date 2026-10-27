package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.ChatBroadcastType;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.phantom.PhantomOrders.Order;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * L2Hub: болтовня напарников (этап 4) — шёпот и чат пати, приказы словами, реплики на события.<br>
 * Шёпот напарнику ({@code ChatWhisper}) и чат пати с напарником ({@code ChatParty}) приходят сюда. Сначала {@link PhantomOrders}: понятный приказ своего владельца выполняется сразу, мозги ({@code launcher/l2hub/brain.py}) только отвечают в характере.
 * Остальное — в мозги; тег приказа в их ответе ({@code [[STAY]]}) выполняется, если говорил владелец.<br>
 * В чате пати без имени отвечает тот напарник, с которым игрок уже говорит, а если в пати кроме него только напарники — любой; с именем или прозвищем — названный.<br>
 * Общий (белый) чат рядом ({@code ChatGeneral}): напарники из пати, кто стоит рядом, слышат его так же, как чат пати, и отвечают вслух; охотник рядом — если назвали, а без имени изредка встревает сам.<br>
 * События (вступил в пати, новый уровень владельца, смерть, рес, отдых) — реплика с кулдауном и общим бюджетом в минуту; мозги недоступны — заготовка.<br>
 * Охотник заговаривает первым ({@link PhantomInitiative} решает, здесь — реплика): ответ игрока без имени уходит ему, как в разговоре.<br>
 * Все реплики вслух идут через {@link #sayNear} и записываются в {@link PhantomQuiet}: непрошеные (мысль вслух, обидчик мимо, встрял, заговорил первым) ждут тишины в округе.
 */
public class PhantomChat
{
	private static final Logger LOGGER = Logger.getLogger(PhantomChat.class.getName());

	/** Сообщение без имени в чате пати — продолжение разговора с напарником, который отвечал этому игроку не раньше этого. */
	private static final long CONVERSATION_WINDOW = 120000;
	/** Сколько слышно общий чат — как у игроков. */
	private static final int SAY_RANGE = 1250;
	private static final Pattern TAG = Pattern.compile("\\[\\[([A-Z]+)\\]\\]");
	/** «Разум 2.0», шаг 6: новая цена в лавке [[TRADE]] из ответа мозгов разговора (brain.py дописывает её в конец, ответ может быть одним тегом). */
	private static final Pattern PRICE = Pattern.compile("\\[\\[\\s*PRICE[\\s:]+(\\d{1,12})\\s*\\]\\]");
	/** События охотника, которые он говорит сам, без повода от игрока: ждут тишины в округе ({@link PhantomQuiet}). */
	private static final Set<String> UNSOLICITED_EVENTS = Set.of("GRUDGE");

	public enum Event
	{
		/** Вступил в пати. */
		JOINED(false, 1),
		/** Владелец получил уровень. */
		OWNER_LEVEL_UP(true, 1),
		/** Владелец погиб. */
		OWNER_DIED(true, 1),
		/** У владельца мало HP (говорит хилер). */
		OWNER_LOW_HP(true, 3),
		/** Напарника убили. */
		DIED(false, 1),
		/** Напарника подняли. */
		REVIVED(false, 1),
		/** Сел на MP. */
		REST(false, 2);

		/** Общее для пати владельца: из напарников говорит один. */
		final boolean partyWide;
		/** Кулдаун события — столько {@link PhantomPartyConfig#PHANTOM_EVENT_COOLDOWN}. */
		final int cooldownFactor;

		Event(boolean partyWide, int cooldownFactor)
		{
			this.partyWide = partyWide;
			this.cooldownFactor = cooldownFactor;
		}
	}

	private static final String[] CHAT_FALLBACK =
	{
		"ага",
		"угу",
		"щас, погоди",
		")"
	};

	private static final Map<Order, String> ORDER_FALLBACK = new EnumMap<>(Order.class);
	static
	{
		ORDER_FALLBACK.put(Order.FOLLOW, "иду");
		ORDER_FALLBACK.put(Order.STAY, "стою");
		ORDER_FALLBACK.put(Order.ASSIST, "понял");
		ORDER_FALLBACK.put(Order.BUFF, "бафаю");
		ORDER_FALLBACK.put(Order.HEAL, "хилю");
		ORDER_FALLBACK.put(Order.RES, "ща подниму");
		ORDER_FALLBACK.put(Order.SIT, "сажусь");
		ORDER_FALLBACK.put(Order.STAND, "встаю");
		ORDER_FALLBACK.put(Order.LEAVE, "ну пока, зови если что");
		ORDER_FALLBACK.put(Order.HUNT, "го, мочим всех");
		ORDER_FALLBACK.put(Order.LEAD, "го за мной, щас натаскаем");
	}

	/** Кто кому последним отвечал: (фантом, игрок) → время. */
	private final Map<Long, Long> _lastReply = new ConcurrentHashMap<>();
	/** Событие → не раньше чем. */
	private final Map<String, Long> _eventCooldowns = new ConcurrentHashMap<>();
	/** Запросы к мозгам на события за последнюю минуту. */
	private final Deque<Long> _eventCalls = new ArrayDeque<>();

	protected PhantomChat()
	{
	}

	/**
	 * Шёпот напарнику (из {@code ChatWhisper}): у фантома нет клиента, отвечает этот класс.
	 * @param speaker кто пишет
	 * @param phantom напарник
	 * @param text что написал
	 */
	public void onWhisper(Player speaker, Player phantom, String text)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if (template == null)
		{
			return;
		}
		// Танатос (раздел 4.45, «Голос»): отвечает сам, редко.
		if (template.isReaper())
		{
			PhantomThanatos.getInstance().onWhisper(speaker, phantom, text);
			return;
		}

		// Кланы охотников (раздел 4.50): «возьми в клан» — решает глава, слова — мозгам.
		if (template.isHunter() && PhantomHunterClans.getInstance().onWhisper(speaker, phantom, text))
		{
			return;
		}

		final boolean owner = PhantomCompanionManager.getInstance().isOwner(speaker, template);
		final List<Order> orders = owner ? PhantomOrders.parse(text) : List.of();
		final String note = execute(speaker, template, orders);
		talk(speaker, phantom, template, "whisper", text, orders, note, owner);
	}

	/**
	 * Сообщение в чат пати (из {@code ChatParty}): отвечает и выполняет приказы кто-то из напарников в этой пати.
	 * @param speaker кто пишет (живой игрок)
	 * @param text что написал
	 */
	public void onPartyChat(Player speaker, String text)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED || !speaker.isInParty() || PhantomManager.getInstance().isPhantom(speaker))
		{
			return;
		}

		final Map<PhantomTemplate, Player> phantoms = new LinkedHashMap<>();
		boolean othersHuman = false;
		for (Player member : speaker.getParty().getMembers())
		{
			if (member == speaker)
			{
				continue;
			}

			final PhantomTemplate template = PhantomManager.getInstance().isPhantom(member) ? PhantomData.getInstance().getTemplate(member.getName()) : null;
			if (template != null)
			{
				phantoms.put(template, member);
			}
			else
			{
				othersHuman = true;
			}
		}

		route(speaker, text, phantoms, othersHuman, "party");
	}

	/**
	 * Сообщение в чат клана (из {@code ChatClan}): охотники клана в мире слышат его и отвечают туда же ({@link PhantomClans}, раздел 4.41) — как в пати:
	 * названный, без имени — тот, с кем игрок уже говорит, а если других живых в сети нет — любой.
	 * @param speaker кто пишет (живой игрок)
	 * @param text что написал
	 */
	public void onClanChat(Player speaker, String text)
	{
		final Clan clan = speaker.getClan();
		if (!PhantomsConfig.PHANTOMS_ENABLED || !PhantomHuntersConfig.HUNTER_CLAN_JOIN || (clan == null) || (PhantomManager.getInstance().isPhantom(speaker) && !PhantomClans.getInstance().isTestPlayer(speaker)))
		{
			return;
		}

		final Map<PhantomTemplate, Player> phantoms = new LinkedHashMap<>();
		boolean othersHuman = false;
		for (ClanMember member : clan.getMembers())
		{
			final Player player = member.getPlayer();
			if ((player == null) || (player == speaker) || !player.isOnline())
			{
				continue;
			}

			final PhantomTemplate template = PhantomManager.getInstance().isPhantom(player) ? PhantomData.getInstance().getTemplate(player.getName()) : null;
			if (template != null)
			{
				if (template.isHunter() && !player.isDead())
				{
					phantoms.put(template, player);
				}
			}
			else if (!player.isInOfflineMode())
			{
				othersHuman = true;
			}
		}

		route(speaker, text, phantoms, othersHuman, "clan");
	}

	/**
	 * Сообщение в общий чат рядом (из {@code ChatGeneral}, если к нему не взялись фейки и «лфм»).<br>
	 * Напарники из пати говорящего, кто стоит рядом, слышат его как чат пати: слушаются приказов и отвечают вслух.
	 * Охотник рядом отвечает, если его назвали или он уже говорит с этим игроком; без этого изредка встревает сам — только когда рядом нет других живых игроков.
	 * @param speaker кто пишет (живой игрок)
	 * @param text что написал
	 */
	public void onGeneralChat(Player speaker, String text)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED || !PhantomPartyConfig.PHANTOM_GENERAL_CHAT || PhantomManager.getInstance().isPhantom(speaker))
		{
			return;
		}

		final Map<PhantomTemplate, Player> mates = new LinkedHashMap<>();
		final Map<PhantomTemplate, Player> strangers = new LinkedHashMap<>();
		boolean othersHuman = false;
		for (Player player : World.getVisibleObjectsInRange(speaker, Player.class, SAY_RANGE))
		{
			if (player == speaker)
			{
				continue;
			}

			final PhantomTemplate template = PhantomManager.getInstance().isPhantom(player) ? PhantomData.getInstance().getTemplate(player.getName()) : null;
			if (template == null)
			{
				othersHuman |= player.isOnline() && !player.isInOfflineMode();
			}
			else if (speaker.isInParty() && (player.getParty() == speaker.getParty()))
			{
				mates.put(template, player);
			}
			else
			{
				strangers.put(template, player);
			}
		}

		// Назван только кто-то чужой — отвечает он, а не напарники.
		final List<PhantomTemplate> named = PhantomOrders.addressed(text, strangers.keySet());

		// Без имён и приказов: игрок сейчас говорит с охотником (тот ему ответил или сам заговорил первым), а с напарниками разговора нет — отвечает охотник.
		if (named.isEmpty() && PhantomOrders.addressed(text, mates.keySet()).isEmpty() && PhantomOrders.parse(text).isEmpty() && (recentPartner(speaker, mates) == null))
		{
			final PhantomTemplate partner = recentPartner(speaker, strangers);
			if (partner != null)
			{
				talk(speaker, strangers.get(partner), partner, "say", text, List.of(), null, PhantomCompanionManager.getInstance().isOwner(speaker, partner));
				return;
			}
		}

		if ((named.isEmpty() || !PhantomOrders.addressed(text, mates.keySet()).isEmpty()) && route(speaker, text, mates, othersHuman, "say"))
		{
			return;
		}
		if (strangers.isEmpty())
		{
			return;
		}

		PhantomTemplate replier = named.isEmpty() ? recentPartner(speaker, strangers) : named.get(0);
		if (replier != null)
		{
			talk(speaker, strangers.get(replier), replier, "say", text, List.of(), null, PhantomCompanionManager.getInstance().isOwner(speaker, replier));
			return;
		}

		// Без имени: изредка встревает кто-то свободный, если рядом нет других живых — в чужой разговор охотники не лезут.
		final long now = System.currentTimeMillis();
		final String speakerKey = "say:" + speaker.getObjectId();
		if (othersHuman || (Rnd.get(100) >= PhantomPartyConfig.PHANTOM_OVERHEAR_CHANCE) || !brainEnabled() || !cooled(speakerKey, now))
		{
			return;
		}

		final List<PhantomTemplate> free = new ArrayList<>();
		for (Map.Entry<PhantomTemplate, Player> entry : strangers.entrySet())
		{
			// Усталый и настороженный не встревают; вокруг только что говорили — тоже нет (патч 0026).
			final Player phantom = entry.getValue();
			final PhantomMood mood = PhantomHunterManager.getInstance().mood(phantom);
			if (!phantom.isDead() && !phantom.isInCombat() && cooled("overhear:" + phantom.getObjectId(), now) && ((mood == null) || mood.overhears()) && PhantomQuiet.getInstance().allowed(phantom, PhantomQuiet.OVERHEARD, now))
			{
				free.add(entry.getKey());
			}
		}
		if (free.isEmpty() || !eventBudget(now))
		{
			return;
		}

		replier = free.get(Rnd.get(free.size()));
		final Player phantom = strangers.get(replier);
		_eventCooldowns.put(speakerKey, now + (PhantomPartyConfig.PHANTOM_OVERHEAR_PLAYER_COOLDOWN * 1000L));
		_eventCooldowns.put("overhear:" + phantom.getObjectId(), now + (PhantomPartyConfig.PHANTOM_OVERHEAR_COOLDOWN * 1000L));
		talk(speaker, phantom, replier, "overheard", text, List.of(), null, false);
	}

	/**
	 * Кто из фантомов отвечает и что выполняет. Приказы — только своим напарникам: названным, а без имени — тем, чья это работа (баф — бафферу, хил и рес — хилеру).
	 * Разговор — названному, без имени — тому, с кем игрок уже говорит, а если других живых рядом нет — любому.
	 * @param channel куда отвечать: {@code party} — в чат пати, {@code clan} — в чат клана, {@code say} — вслух рядом
	 * @return {@code true}, если кто-то взялся ответить
	 */
	private boolean route(Player speaker, String text, Map<PhantomTemplate, Player> phantoms, boolean othersHuman, String channel)
	{
		if (phantoms.isEmpty())
		{
			return false;
		}

		final List<PhantomTemplate> addressed = PhantomOrders.addressed(text, phantoms.keySet());

		final List<Order> orders = PhantomOrders.parse(text);
		if (!orders.isEmpty())
		{
			final List<PhantomTemplate> mine = new ArrayList<>();
			for (PhantomTemplate template : addressed.isEmpty() ? phantoms.keySet() : addressed)
			{
				if (PhantomCompanionManager.getInstance().isOwner(speaker, template))
				{
					mine.add(template);
				}
			}

			if (!mine.isEmpty())
			{
				final Map<PhantomTemplate, List<Order>> assigned = new LinkedHashMap<>();
				for (Order order : orders)
				{
					for (PhantomTemplate template : addressed.isEmpty() ? executors(order, mine) : mine)
					{
						assigned.computeIfAbsent(template, t -> new ArrayList<>()).add(order);
					}
				}

				boolean replied = false;
				for (Map.Entry<PhantomTemplate, List<Order>> entry : assigned.entrySet())
				{
					final String note = execute(speaker, entry.getKey(), entry.getValue());
					if (!replied)
					{
						// Отвечает один — первый исполнитель; остальные выполняют молча.
						replied = true;
						talk(speaker, phantoms.get(entry.getKey()), entry.getKey(), channel, text, entry.getValue(), note, true);
					}
				}
				return true;
			}
		}

		PhantomTemplate replier = addressed.isEmpty() ? null : addressed.get(0);
		if (replier == null)
		{
			replier = recentPartner(speaker, phantoms);
		}
		if ((replier == null) && !othersHuman)
		{
			final List<PhantomTemplate> all = new ArrayList<>(phantoms.keySet());
			replier = all.get(Rnd.get(all.size()));
		}
		if (replier == null)
		{
			return false;
		}

		talk(speaker, phantoms.get(replier), replier, channel, text, List.of(), null, PhantomCompanionManager.getInstance().isOwner(speaker, replier));
		return true;
	}

	/**
	 * Приказ кнопкой из окна .buddy: выполняется и озвучивается в чате пати так же, как сказанный словами.
	 * @param owner владелец
	 * @param template напарник
	 * @param order приказ
	 * @param said как он звучал бы словами — для мозгов («бафни»)
	 */
	public void command(Player owner, PhantomTemplate template, Order order, String said)
	{
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if (phantom == null)
		{
			return;
		}

		final List<Order> orders = List.of(order);
		talk(owner, phantom, template, "party", said, orders, execute(owner, template, orders), true);
	}

	/**
	 * Реплика напарника на событие: с кулдауном и бюджетом в минуту; без мозгов — заготовка.
	 * @param phantom напарник
	 * @param owner владелец
	 * @param event событие
	 * @param what что случилось — для мозгов, от лица напарника («у тебя кончается MP, ты садишься отдохнуть»)
	 * @param fallback заготовка, если мозги недоступны; {@code null} — промолчать
	 */
	public void event(Player phantom, Player owner, Event event, String what, String fallback)
	{
		// Пати охотников (патч 0036): лидер — охотник, живых в пати нет — реплику в чат пати никто не услышит, модель не звать.
		if (PhantomManager.getInstance().isPhantom(owner))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final String key = (event.partyWide ? "o" + owner.getObjectId() : "p" + phantom.getObjectId()) + ":" + event;
		final Long until = _eventCooldowns.get(key);
		if ((until != null) && (now < until))
		{
			return;
		}
		_eventCooldowns.put(key, now + (PhantomPartyConfig.PHANTOM_EVENT_COOLDOWN * 1000L * event.cooldownFactor));

		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if ((template == null) || !brainEnabled() || !eventBudget(now))
		{
			if (fallback != null)
			{
				say(phantom, owner, "event", fallback);
			}
			return;
		}

		final boolean isOwner = PhantomCompanionManager.getInstance().isOwner(owner, template);
		FakePlayerBrain.getInstance().send(request(owner, phantom, template, "event", what, null, isOwner), reply ->
		{
			final String text = TAG.matcher(reply).replaceAll("").strip();
			if (!text.isEmpty())
			{
				say(phantom, owner, "event", text);
			}
		}, () ->
		{
			if (fallback != null)
			{
				say(phantom, owner, "event", fallback);
			}
		});
	}

	/**
	 * Реплика охотника в общий чат рядом (ПвП: напали, убили, обидчик повержен, обидчик проходит мимо). Кулдаун — на пару «охотник — игрок» и вид события; без мозгов или сверх бюджета — заготовка.
	 * @param hunter охотник
	 * @param other игрок, о котором речь (он же собеседник для мозгов: событие попадает в память их разговора)
	 * @param kind вид события
	 * @param what что случилось — от лица охотника
	 * @param fallback заготовка или {@code null}
	 * @param cooldown не чаще, мс
	 */
	public void hunterEvent(Player hunter, Player other, String kind, String what, String fallback, long cooldown)
	{
		hunterEvent(hunter, other, kind, what, fallback, cooldown, false);
	}

	/**
	 * @param hunter охотник
	 * @param other игрок, о котором речь
	 * @param kind вид события
	 * @param what что случилось — от лица охотника
	 * @param fallback заготовка или {@code null}
	 * @param cooldown не чаще, мс
	 * @param whisper ответить игроку шёпотом (пати по крику), а не в общий чат рядом
	 */
	public void hunterEvent(Player hunter, Player other, String kind, String what, String fallback, long cooldown, boolean whisper)
	{
		// Обидчик мимо — непрошеная реплика: вокруг только что говорили — подождать.
		event(hunter, other, kind, what, fallback, cooldown, !whisper && UNSOLICITED_EVENTS.contains(kind), text -> sayHunter(hunter, other, kind, text, whisper));
	}

	/**
	 * Охотник говорит о клане в чат клана ({@link PhantomClans}: вступил, уходит). Кулдаун и бюджет — как у {@link #hunterEvent}.
	 * @param hunter охотник
	 * @param other игрок, о котором речь
	 * @param clan клан: уходящий говорит в чат клана, в котором уже не состоит
	 * @param kind вид события
	 * @param what что случилось — от лица охотника
	 * @param fallback заготовка или {@code null}
	 * @param cooldown не чаще, мс
	 */
	public void clanEvent(Player hunter, Player other, Clan clan, String kind, String what, String fallback, long cooldown)
	{
		event(hunter, other, kind, what, fallback, cooldown, false, text -> clan.broadcastCSToOnlineMembers(new CreatureSay(hunter, ChatType.CLAN, hunter.getName(), text), hunter));
	}

	/**
	 * @param unsolicited непрошеная реплика: вокруг только что говорили — промолчать (кулдаун не ставится, повод вернётся на следующем проходе)
	 * @param out куда сказать
	 */
	private void event(Player hunter, Player other, String kind, String what, String fallback, long cooldown, boolean unsolicited, Consumer<String> out)
	{
		final long now = System.currentTimeMillis();
		final String key = "h" + hunter.getObjectId() + ":" + other.getObjectId() + ":" + kind;
		final Long until = _eventCooldowns.get(key);
		if ((until != null) && (now < until))
		{
			return;
		}
		if (unsolicited && !PhantomQuiet.getInstance().allowed(hunter, kind, now))
		{
			return;
		}
		_eventCooldowns.put(key, now + cooldown);

		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if ((template == null) || !brainEnabled() || !eventBudget(now))
		{
			if (fallback != null)
			{
				out.accept(fallback);
			}
			return;
		}

		FakePlayerBrain.getInstance().send(request(other, hunter, template, "event", what, null, false), reply ->
		{
			final String text = TAG.matcher(reply).replaceAll("").strip();
			if (!text.isEmpty())
			{
				out.accept(text);
			}
		}, () ->
		{
			if (fallback != null)
			{
				out.accept(fallback);
			}
		});
	}

	private static void sayHunter(Player hunter, Player other, String kind, String text, boolean whisper)
	{
		if (whisper)
		{
			if (other.isOnline())
			{
				other.sendPacket(new CreatureSay(hunter, ChatType.WHISPER, hunter.getName(), text));
			}
			return;
		}
		sayNear(hunter, text, kind);
	}

	/**
	 * Вслух в общий чат рядом; реплика записывается в тишину в округе ({@link PhantomQuiet}).
	 * @param topic тема или вид реплики
	 */
	private static void sayNear(Player speaker, String text, String topic)
	{
		PhantomQuiet.getInstance().said(speaker, topic, System.currentTimeMillis());
		final CreatureSay say = new CreatureSay(speaker, ChatType.GENERAL, speaker.getName(), text);
		World.forEachVisibleObjectInRange(speaker, Player.class, 1250, player -> player.sendPacket(say));
	}

	/**
	 * Заготовка охотника вслух в общий чат рядом, без мозгов (дуэль охотников, {@link PhantomDuel}).
	 * @param topic тема или вид реплики
	 */
	void hunterSay(Player hunter, String text, String topic)
	{
		sayNear(hunter, text, topic);
	}

	/**
	 * Охотник заговаривает первым ({@link PhantomInitiative}): правила уже разрешили, здесь — реплика модели вслух в общий чат рядом, в общем бюджете событий.
	 * Модель промолчала, мозги недоступны или бюджет исчерпан — тишина, без заготовок: дежурное «привет» хуже молчания.
	 * @param hunter охотник
	 * @param player к кому он обращается
	 * @param template шаблон охотника
	 * @param what что он видит — для мозгов, от его лица
	 * @param onSaid охотник что-то сказал: ждать ответа
	 */
	void initiative(Player hunter, Player player, PhantomTemplate template, String what, Runnable onSaid)
	{
		// Пока мозги отвечали «знакомы ли», рядом кто-то заговорил — промолчать (патч 0026).
		final long now = System.currentTimeMillis();
		if (!PhantomQuiet.getInstance().allowed(hunter, PhantomQuiet.INITIATIVE, now))
		{
			LOGGER.info(PhantomInitiative.class.getSimpleName() + ": " + hunter.getName() + " -> " + player.getName() + ": рядом только что говорили — промолчал.");
			return;
		}
		if (!brainEnabled() || !eventBudget(now))
		{
			return;
		}

		FakePlayerBrain.getInstance().send(request(player, hunter, template, "initiative", what, null, false), reply ->
		{
			final String line = TAG.matcher(reply).replaceAll("").strip();
			if (line.isEmpty() || hunter.isDead())
			{
				return;
			}

			// Ответ игрока без имени уйдёт этому охотнику; сам охотник следом не встревает.
			final long saidAt = System.currentTimeMillis();
			_lastReply.put(pair(hunter, player), saidAt);
			_eventCooldowns.put("overhear:" + hunter.getObjectId(), saidAt + (PhantomPartyConfig.PHANTOM_OVERHEAR_COOLDOWN * 1000L));
			sayNear(hunter, line, PhantomQuiet.INITIATIVE);
			onSaid.run();
		}, () ->
		{
			// Мозги недоступны — охотник молчит и качается дальше.
		});
	}

	/**
	 * Перепалка или привет между охотниками ({@link PhantomBanter} решил, патч 0033): первый говорит вслух, второй через 2–5 с отвечает в своём отношении.
	 * Без заготовок: мозги молчат или недоступны — тишина. Обе реплики — в бюджете событий и в памяти пары у мозгов.
	 * @param first кто начинает
	 * @param second к кому обращается
	 * @param template шаблон первого
	 * @param enemy первый видит во втором врага (иначе — друга)
	 */
	void banter(Player first, Player second, PhantomTemplate template, boolean enemy)
	{
		banter(first, second, template, "рядом " + PhantomHunterManager.describe(second) + " — такой же охотник, как ты, " + (enemy ? "ты на него в обиде" : "ты ему благодарен") + "; вокруг стоят и слышат другие игроки");
	}

	/**
	 * Охотник говорит другому охотнику вслух по ситуации (патч 0037: зовёт в пати, отжали моба), второй через 2–5 с отвечает в своём отношении.
	 * @param first кто говорит
	 * @param second кому
	 * @param template шаблон первого
	 * @param what ситуация — для мозгов, от лица первого
	 */
	void banter(Player first, Player second, PhantomTemplate template, String what)
	{
		dialogue(first, second, template, "banter", what, "say", null);
	}

	/**
	 * Предъява за моба (патч 0037, {@link PhantomNeighbors}): обиженный говорит, отжавший отвечает так, как решил сервер (бросок × вспыльчивость), — «сорян» или «пнх».
	 * Последствия ({@code after}) — после ответа; мозги молчат, недоступны или бюджет кончился — сразу, без слов.
	 * @param victim у кого отжали
	 * @param taker кто отжал
	 * @param template шаблон обиженного
	 * @param what ситуация — для мозгов, от лица обиженного
	 * @param rude отжавший посылает (иначе извиняется и уходит)
	 * @param after последствия: очко отношения, уход на другой спот, махач
	 */
	void warn(Player victim, Player taker, PhantomTemplate template, String what, boolean rude, Runnable after)
	{
		dialogue(victim, taker, template, "banter", what, rude ? "retort_rude" : "retort_sorry", after);
	}

	/**
	 * Две реплики вслух: первый по ситуации, второй через 2–5 с отвечает ему. {@code after} — ровно один раз: после ответа второго или сразу, если разговора не вышло.
	 */
	private void dialogue(Player first, Player second, PhantomTemplate template, String firstChannel, String what, String secondChannel, Runnable after)
	{
		final Runnable done = after == null ? () ->
		{
		} : after;
		if (!brainEnabled() || !eventBudget(System.currentTimeMillis()))
		{
			done.run();
			return;
		}

		FakePlayerBrain.getInstance().send(request(second, first, template, firstChannel, what, null, false), reply ->
		{
			final String line = TAG.matcher(reply).replaceAll("").strip();
			final PhantomTemplate secondTemplate = PhantomData.getInstance().getTemplate(second.getName());
			if (line.isEmpty() || first.isDead() || (secondTemplate == null))
			{
				if (!line.isEmpty() && !first.isDead())
				{
					sayNear(first, line, PhantomBanter.TOPIC);
				}
				done.run();
				return;
			}

			sayNear(first, line, PhantomBanter.TOPIC);
			LOGGER.info(PhantomBanter.class.getSimpleName() + ": " + first.getName() + " -> " + second.getName() + ": «" + line + "»");
			ThreadPool.schedule(() ->
			{
				if (second.isDead() || (second.calculateDistance2D(first) > SAY_RANGE) || !eventBudget(System.currentTimeMillis()))
				{
					done.run();
					return;
				}

				FakePlayerBrain.getInstance().send(request(first, second, secondTemplate, secondChannel, line, null, false), answer ->
				{
					final String text = TAG.matcher(answer).replaceAll("").strip();
					if (!text.isEmpty() && !second.isDead())
					{
						sayNear(second, text, "say");
						LOGGER.info(PhantomBanter.class.getSimpleName() + ": " + second.getName() + " -> " + first.getName() + ": «" + text + "»");
					}
					done.run();
				}, done);
			}, Rnd.get(2000, 5000));
		}, done);
	}

	/**
	 * @param player игрок
	 * @param now текущее время
	 * @return с игроком сейчас разговаривает кто-то из фантомов (отвечал ему за последние {@value #CONVERSATION_WINDOW} мс)
	 */
	boolean inConversation(Player player, long now)
	{
		final long id = player.getObjectId() & 0xffffffffL;
		for (Map.Entry<Long, Long> entry : _lastReply.entrySet())
		{
			if (((entry.getKey() & 0xffffffffL) == id) && ((now - entry.getValue()) <= CONVERSATION_WINDOW))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Мысль охотника вслух (мозги-стратег) — в общий чат рядом, если вокруг тихо ({@link PhantomQuiet}).
	 * @param hunter охотник
	 * @param text что сказал
	 * @return сказал
	 */
	public boolean hunterSay(Player hunter, String text)
	{
		if (!PhantomQuiet.getInstance().allowed(hunter, PhantomQuiet.THOUGHT, System.currentTimeMillis()))
		{
			return false;
		}
		sayNear(hunter, text, PhantomQuiet.THOUGHT);
		return true;
	}

	/**
	 * Охотник ищет пати (мозги-стратег): с 20-го уровня — в торговый чат, как у игроков (свой регион или весь мир — по TradeChat в General.ini), младше — в общий чат рядом.
	 * @param hunter охотник
	 * @param text крик
	 * @return куда ушло — для лога
	 */
	public String hunterShout(Player hunter, String text)
	{
		final ChatBroadcastType trade = GeneralConfig.DEFAULT_TRADE_CHAT;
		if ((hunter.getLevel() < 20) || ((trade != ChatBroadcastType.ON) && (trade != ChatBroadcastType.GLOBAL)))
		{
			sayNear(hunter, text, "lfp");
			return "general";
		}

		final CreatureSay say = new CreatureSay(hunter, ChatType.TRADE, hunter.getName(), text);
		final int region = MapRegionData.getInstance().getMapRegionLocId(hunter);
		for (Player player : World.getPlayers())
		{
			if ((player != hunter) && !BlockList.isBlocked(player, hunter) && ((trade == ChatBroadcastType.GLOBAL) || ((region == MapRegionData.getInstance().getMapRegionLocId(player)) && (player.getInstanceId() == hunter.getInstanceId()))))
			{
				player.sendPacket(say);
			}
		}
		return "trade";
	}

	/**
	 * Тревога охотника (Танатос рядом, раздел 4.45): крик в общий чат на весь регион карты, как «!» у игрока.
	 * @param hunter охотник
	 * @param text крик
	 */
	public void hunterAlarm(Player hunter, String text)
	{
		final CreatureSay say = new CreatureSay(hunter, ChatType.SHOUT, hunter.getName(), text);
		final int region = MapRegionData.getInstance().getMapRegionLocId(hunter);
		for (Player player : World.getPlayers())
		{
			if ((player != hunter) && !BlockList.isBlocked(player, hunter) && (region == MapRegionData.getInstance().getMapRegionLocId(player)) && (player.getInstanceId() == hunter.getInstanceId()))
			{
				player.sendPacket(say);
			}
		}
	}

	// ---------- Разговор ----------

	/**
	 * Выполнить приказы.
	 * @return что сделано — для мозгов («сделано: идёшь за ним следом; нельзя: у тебя нет хила»); {@code null}, если приказов нет
	 */
	private static String execute(Player owner, PhantomTemplate template, List<Order> orders)
	{
		if (orders.isEmpty())
		{
			return null;
		}

		final StringBuilder note = new StringBuilder();
		for (Order order : orders)
		{
			final String result = PhantomCompanionManager.getInstance().order(owner, template, order);
			if (note.length() > 0)
			{
				note.append("; ");
			}
			note.append(result);
			LOGGER.info(PhantomChat.class.getSimpleName() + ": " + owner.getName() + " -> " + template.getName() + ": " + order + " (" + result + ")");
		}
		return note.toString();
	}

	/**
	 * Без имени приказ получает тот, чья это работа: баф — баффер, хил и рес — хилер; такого в пати нет — все.
	 */
	private static List<PhantomTemplate> executors(Order order, List<PhantomTemplate> mine)
	{
		final PhantomTemplate.Role role = order == Order.BUFF ? PhantomTemplate.Role.BUFFER : (order == Order.HEAL) || (order == Order.RES) ? PhantomTemplate.Role.HEALER : null;
		if (role == null)
		{
			return mine;
		}

		final List<PhantomTemplate> result = new ArrayList<>();
		for (PhantomTemplate template : mine)
		{
			if (template.getRole() == role)
			{
				result.add(template);
			}
		}
		return result.isEmpty() ? mine : result;
	}

	private PhantomTemplate recentPartner(Player speaker, Map<PhantomTemplate, Player> phantoms)
	{
		final long now = System.currentTimeMillis();
		PhantomTemplate best = null;
		long bestTime = 0;
		for (Map.Entry<PhantomTemplate, Player> entry : phantoms.entrySet())
		{
			final Long time = _lastReply.get(pair(entry.getValue(), speaker));
			if ((time != null) && ((now - time) <= CONVERSATION_WINDOW) && (time > bestTime))
			{
				best = entry.getKey();
				bestTime = time;
			}
		}
		return best;
	}

	private void talk(Player speaker, Player phantom, PhantomTemplate template, String channel, String text, List<Order> orders, String note, boolean owner)
	{
		if (!"overheard".equals(channel))
		{
			_lastReply.put(pair(phantom, speaker), System.currentTimeMillis());
			if (template.isHunter())
			{
				PhantomInitiative.getInstance().onSpoke(phantom, speaker);
				// «Разум 2.0»: разговор с живым — повод для фокуса.
				PhantomFocus.getInstance().talked(phantom, speaker);
			}
		}
		if (!brainEnabled())
		{
			fallback(speaker, phantom, channel, orders);
			return;
		}

		FakePlayerBrain.getInstance().send(request(speaker, phantom, template, channel, text, note, owner), reply ->
		{
			// Тег приказа из ответа мозгов: только от владельца, только если правила приказ не нашли, LEAVE — только при прощании.
			final Matcher matcher = TAG.matcher(reply);
			final Order tagged = matcher.find() ? PhantomOrders.fromTag(matcher.group(1)) : null;
			if (owner && orders.isEmpty() && (tagged != null) && ((tagged != Order.LEAVE) || PhantomOrders.confirmsLeave(text)))
			{
				execute(speaker, template, List.of(tagged));
			}

			// «Разум 2.0», шаг 6: сторговались — новая цена в лавке [[TRADE]] этому игроку (сервер зажимает её в 50–300 % цены магазина).
			final long price = priceTag(reply);
			if (template.isHunter() && (price > 0))
			{
				PhantomNear.getInstance().price(phantom, speaker, price);
			}
			final String line = TAG.matcher(PRICE.matcher(reply).replaceAll(" ")).replaceAll("").replaceAll("\\s+", " ").strip();
			if (!line.isEmpty())
			{
				_lastReply.put(pair(phantom, speaker), System.currentTimeMillis());
				say(phantom, speaker, channel, line);
			}
		}, () -> fallback(speaker, phantom, channel, orders), PhantomFocus.getInstance().of(phantom) != null ? FOCUS_TIMEOUT : 0);
	}

	/**
	 * @return цена из блока {@code [[PRICE N]]} в ответе мозгов или -1 — блока нет
	 */
	static long priceTag(String reply)
	{
		final Matcher matcher = PRICE.matcher(reply == null ? "" : reply);
		return matcher.find() ? Long.parseLong(matcher.group(1)) : -1;
	}

	private static void fallback(Player speaker, Player phantom, String channel, List<Order> orders)
	{
		if (!orders.isEmpty())
		{
			say(phantom, speaker, channel, ORDER_FALLBACK.get(orders.get(0)));
		}
		else if ("whisper".equals(channel))
		{
			say(phantom, speaker, channel, CHAT_FALLBACK[Rnd.get(CHAT_FALLBACK.length)]);
		}
	}

	/**
	 * Ответ туда, откуда спросили: шёпот — шёпотом, общий чат — вслух рядом, клан — в чат клана, пати и события — в чат пати (не в пати — шёпотом).
	 */
	private static void say(Player phantom, Player listener, String channel, String text)
	{
		if ("say".equals(channel) || "overheard".equals(channel))
		{
			sayNear(phantom, text, channel);
		}
		else if ("clan".equals(channel) && (phantom.getClan() != null))
		{
			phantom.getClan().broadcastCSToOnlineMembers(new CreatureSay(phantom, ChatType.CLAN, phantom.getName(), text), phantom);
		}
		else if (!"whisper".equals(channel) && phantom.isInParty())
		{
			phantom.getParty().broadcastCreatureSay(new CreatureSay(phantom, ChatType.PARTY, phantom.getName(), text), phantom);
		}
		else if ((listener != null) && listener.isOnline())
		{
			listener.sendPacket(new CreatureSay(phantom, ChatType.WHISPER, phantom.getName(), text));
		}
	}

	static boolean brainEnabled()
	{
		return PhantomPartyConfig.PHANTOM_BRAIN && FakePlayersConfig.FAKE_PLAYER_BRAIN;
	}

	private boolean cooled(String key, long now)
	{
		final Long until = _eventCooldowns.get(key);
		return (until == null) || (now >= until);
	}

	private boolean eventBudget(long now)
	{
		synchronized (_eventCalls)
		{
			while (!_eventCalls.isEmpty() && ((now - _eventCalls.peekFirst()) > 60000))
			{
				_eventCalls.pollFirst();
			}

			if (_eventCalls.size() >= PhantomPartyConfig.PHANTOM_EVENTS_PER_MINUTE)
			{
				return false;
			}
			_eventCalls.addLast(now);
			return true;
		}
	}

	// ---------- Запрос к мозгам ----------

	/** «Разум 2.0»: ответ охотнику в фокусе ждать до стольких секунд — мозги думают с размышлением до 45 с. */
	static final int FOCUS_TIMEOUT = 50;

	/**
	 * Тело POST /chat для brain.py: {@code "kind":"phantom"}, кто говорит, состояние напарника и пати.
	 */
	private static String request(Player player, Player phantom, PhantomTemplate template, String channel, String message, String note, boolean owner)
	{
		final StringBuilder sb = new StringBuilder(1536);
		sb.append('{');
		FakePlayerBrain.field(sb, "kind", "phantom").append(',');
		FakePlayerBrain.field(sb, "fpc", phantom.getName()).append(',');
		// «Разум 2.0»: охотник рядом с живым игроком (PhantomFocus).
		final String focus = template.isHunter() ? PhantomFocus.getInstance().of(phantom) : null;
		if (focus != null)
		{
			FakePlayerBrain.field(sb, "focus", focus).append(',');
		}
		sb.append("\"npcId\":").append(phantom.getObjectId()).append(',');
		FakePlayerBrain.field(sb, "channel", channel).append(',');
		FakePlayerBrain.field(sb, "message", message).append(',');
		FakePlayerBrain.field(sb, "order", note != null ? note : "").append(',');
		sb.append("\"player\":{");
		FakePlayerBrain.field(sb, "name", player.getName()).append(',');
		sb.append("\"level\":").append(player.getLevel()).append(',');
		FakePlayerBrain.field(sb, "class", player.getPlayerClass().name()).append(',');
		FakePlayerBrain.field(sb, "race", player.getRace().name()).append(',');
		sb.append("\"female\":").append(player.getAppearance().isFemale()).append(',');
		FakePlayerBrain.field(sb, "clan", player.getClan() != null ? player.getClan().getName() : "").append(',');
		sb.append("\"gm\":").append(player.isGM()).append(',');
		// Собеседник — тоже охотник (перепалки охотников, патч 0033).
		sb.append("\"hunter\":").append(PhantomRelations.isHunter(player)).append(',');
		sb.append("\"owner\":").append(owner).append(',');
		sb.append("\"grudge\":").append(template.isHunter() ? PhantomHunterManager.grudge(phantom, player) : 0).append(',');
		if (template.isHunter())
		{
			// Отношение охотника к игроку (патч 0032): обида, благодарность, причины.
			PhantomRelations.getInstance().json(sb, phantom, player);
		}
		if (template.isHunter())
		{
			// «Разум 2.0», шаг 6: что живой собеседник делает у охотника на глазах — кого бьёт, на том же ли споте.
			PhantomNear.getInstance().doing(sb, phantom, player, PhantomHunterManager.getInstance().huntSpot(phantom));
		}
		sb.append("\"hpPct\":").append(percent(player.getCurrentHp(), player.getMaxHp())).append(',');
		sb.append("\"dead\":").append(player.isDead()).append(',');
		sb.append("\"distance\":").append((int) phantom.calculateDistance2D(player));
		sb.append("},\"phantom\":{");
		// Шаг 6: охотник сидит лавкой [[TRADE]] для собеседника — вещь и цена, мозги торгуются и возвращают [[PRICE N]].
		PhantomNear.getInstance().tradeJson(sb, phantom, player, System.currentTimeMillis());
		FakePlayerBrain.field(sb, "role", template.getRole().name()).append(',');
		sb.append("\"hunter\":").append(template.isHunter()).append(',');
		final PhantomMood mood = template.isHunter() ? PhantomHunterManager.getInstance().mood(phantom) : null;
		FakePlayerBrain.field(sb, "mood", mood != null ? mood.getKey() : "").append(',');
		FakePlayerBrain.field(sb, "state", PhantomCompanionManager.getInstance().stateForBrain(template)).append(',');
		sb.append("\"level\":").append(phantom.getLevel()).append(',');
		FakePlayerBrain.field(sb, "class", phantom.getPlayerClass().name()).append(',');
		sb.append("\"hpPct\":").append(percent(phantom.getCurrentHp(), phantom.getMaxHp())).append(',');
		sb.append("\"mpPct\":").append(percent(phantom.getCurrentMp(), phantom.getMaxMp())).append(',');
		sb.append("\"inCombat\":").append(phantom.isInCombat()).append(',');
		sb.append("\"sitting\":").append(phantom.isSitting()).append(',');
		sb.append("\"dead\":").append(phantom.isDead()).append(',');
		final WorldObject target = phantom.getTarget();
		FakePlayerBrain.field(sb, "target", (target instanceof Creature) && (target != phantom) && !(target instanceof Player) ? target.getName() : "").append(',');
		FakePlayerBrain.field(sb, "town", MapRegionData.getInstance().getClosestTownName(phantom)).append(',');
		// Клан охотника (раздел 4.41): он в клане игрока — свой.
		FakePlayerBrain.field(sb, "clan", phantom.getClan() != null ? phantom.getClan().getName() : "").append(',');
		// Живые кланы (раздел 4.50): глава клана, клан охотников или клан живого игрока.
		sb.append("\"clanLeader\":").append(phantom.isClanLeader()).append(',');
		sb.append("\"hunterClan\":").append(PhantomHunterClans.isHunterClan(phantom.getClan())).append(',');
		final String[] clanWar = PhantomClanWars.getInstance().war(phantom.getClan());
		if (clanWar != null)
		{
			FakePlayerBrain.field(sb, "clanWar", clanWar[0]).append(',');
			FakePlayerBrain.field(sb, "clanWarScore", clanWar[1]).append(',');
			if (!clanWar[2].isEmpty())
			{
				sb.append("\"clanWarPlayer\":true,");
			}
		}
		sb.append("\"party\":[");
		if (phantom.isInParty())
		{
			boolean first = true;
			for (Player member : phantom.getParty().getMembers())
			{
				if (member == phantom)
				{
					continue;
				}

				if (!first)
				{
					sb.append(',');
				}
				first = false;
				sb.append('{');
				FakePlayerBrain.field(sb, "name", member.getName()).append(',');
				sb.append("\"level\":").append(member.getLevel()).append(',');
				FakePlayerBrain.field(sb, "class", member.getPlayerClass().name()).append(',');
				sb.append("\"hpPct\":").append(percent(member.getCurrentHp(), member.getMaxHp())).append(',');
				sb.append("\"dead\":").append(member.isDead()).append(',');
				sb.append("\"phantom\":").append(PhantomManager.getInstance().isPhantom(member));
				sb.append('}');
			}
		}
		sb.append("]},");
		final int time = GameTimeTaskManager.getInstance().getGameTime();
		FakePlayerBrain.field(sb, "gameTime", String.format("%02d:%02d", (time / 60) % 24, time % 60)).append(',');
		sb.append("\"night\":").append(GameTimeTaskManager.getInstance().isNight());
		sb.append('}');
		return sb.toString();
	}

	private static int percent(double current, double max)
	{
		return (int) ((current * 100) / Math.max(1, max));
	}

	private static long pair(Player phantom, Player player)
	{
		return ((long) phantom.getObjectId() << 32) | (player.getObjectId() & 0xffffffffL);
	}

	public static PhantomChat getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomChat INSTANCE = new PhantomChat();
	}
}
