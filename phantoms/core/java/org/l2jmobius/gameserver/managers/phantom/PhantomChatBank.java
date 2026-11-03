package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * L2Hub: банк реплик — запасная речь фейков, когда нет мозгов (docs/PHANTOMS.md, раздел 4.122; вклад пользователя, переписан под наш мир).<br>
 * Данные — {@code L2HubChatBank.xml} и {@code L2HubChatBankMat.xml} (ключ {@code HunterChatBankMat}), их собирает {@code tools/chat_bank_build.py}.
 * Английского в банке нет: при {@code L2HubLanguage = en} он молчит целиком.
 * <ul>
 * <li>Мозги первые: ответ и реплика на событие берутся отсюда, только если мозгов нет, они не ответили или кончился бюджет ({@link PhantomChat}).</li>
 * <li>{@code hunterShout} банк не подменяет: новости и объявления остаются собой. В директоре торгового чата ({@link PhantomChatFlow}) меняется только обёртка строки, данные настоящие.</li>
 * <li>Тема ответа — по целым словам ({@link #classify}); темы нет — фейк промолчит, а не скажет случайное.</li>
 * <li>Род и характер: {@link #fits}. Хил и баф обещает только саппорт.</li>
 * <li>Сцены — разговор двух-четырёх охотников: только мирная зона, живой рядом, один разговор на регион, свой бюджет. Живой заговорил или актёр сказал что-то своё — разговор обрывается.
 * Ключ {@code HunterBanter} выключает и их.</li>
 * </ul>
 */
public final class PhantomChatBank
{
	private static final Logger LOGGER = Logger.getLogger(PhantomChatBank.class.getName());

	private static final String BANK_FILE = "data/L2HubChatBank.xml";
	private static final String MAT_FILE = "data/L2HubChatBankMat.xml";
	/** Тема реплики сцены для {@link PhantomQuiet}: со своими сцена не обрывается ({@link #spoke}). */
	static final String SCENE_TOPIC = "bank-scene:";
	private static final String LINE_TOPIC = "bank:";

	/** Охотник оглядывается не чаще, мс. */
	private static final long LOOK_GAP = 10000;
	/** Своя фоновая реплика охотника не чаще, мс. */
	private static final long AMBIENT_GAP = 120000;
	/** Фон в чат пати с живым, клана и альянса не чаще, мс: охотников в клане много, а чат один. */
	private static final long PARTY_GAP = 90000;
	private static final long CLAN_GAP = 300000;
	private static final long ALLY_GAP = 900000;
	private static final int CLAN_CHANCE = 20;
	private static final int ALLY_CHANCE = 5;
	/** Актёры сцены не дальше этого от первого. */
	private static final int ACTOR_RANGE = 600;
	private static final int MIN_AREA_GAP = 90000;
	private static final int MAX_AREA_GAP = 180000;
	/** Актёр снова говорит не раньше, мс. */
	private static final long SPEAKER_GAP = 120000;
	/** Та же сцена не чаще, мс. */
	private static final long SCENE_GAP = 1800000;
	/** Живой заговорил: столько тишины от сцен, мс. */
	private static final long AFTER_HUMAN = 45000;
	/** Строк сцен и фона на регион в час. */
	private static final int LINES_PER_HOUR = 120;
	private static final int MIN_LINE_DELAY = 3000;
	private static final int MAX_LINE_DELAY = 8000;
	private static final int SCENE_TRIES = 40;
	/** Столько последних сказанных строк банка не повторяются. */
	private static final int RECENT = 64;
	/** Мана меньше этого (%) — говорит, что пустой. */
	private static final int LOW_MANA = 30;

	/** Что сказать: ситуация в таблице банка. */
	record Query(String context, String situation)
	{
	}

	private record Line(String context, String situation, String channel, String persona, String gender, String text)
	{
	}

	private record Role(String id, String persona, String gender)
	{
	}

	private record SceneLine(String role, String text)
	{
	}

	private record Scene(String id, List<Role> roles, List<SceneLine> lines)
	{
	}

	private static final class Bank
	{
		final Map<String, List<Line>> lines = new HashMap<>();
		final List<Scene> scenes = new ArrayList<>();
		final boolean mat;
		int count;

		Bank(boolean mat)
		{
			this.mat = mat;
		}
	}

	private static final class Session
	{
		final long region;
		final Scene scene;
		final Map<String, Player> roles;
		final Player anchor;
		int index;

		Session(long region, Scene scene, Map<String, Player> roles, Player anchor)
		{
			this.region = region;
			this.scene = scene;
			this.roles = roles;
			this.anchor = anchor;
		}
	}

	/** Тема ответа: первая подошедшая в порядке списка. */
	private record Topic(Pattern pattern, Query query, boolean support, boolean partyOnly)
	{
	}

	private static final List<Topic> TOPICS = new ArrayList<>();
	static
	{
		topic("\\bдуэл\\w*|\\bduel\\w*", "pvp", "duel_request", false, false);
		topic("\\bолимп\\w*|\\bоли\\b", "events", "oly_queue", false, false);
		topic("\\bтвт\\w*|\\btvt\\w*", "events", "tvt_signup", false, false);
		topic("\\bивент\\w*|\\bевент\\w*|\\bevent\\w*", "events", "event_schedule", false, false);
		topic("\\bрес(а|у|ом|ни|ните|нуть|ай|айте)?\\b|\\bвоскрес\\w*", "support", "support_intent", true, false);
		topic("\\bман[аыуе]\\b|\\bмп\\b|\\bmp\\b", "support", "low_mana", false, false);
		topic("\\bхил\\w*|\\bподлеч\\w*|\\bлеч\\w*|\\bхп\\b", "support", "heal_request", true, false);
		topic("\\bбафф?\\w*|\\bребафф?\\w*", "support", "buff_request", true, false);
		topic("\\bлут\\w*|\\bдроп\\w*|\\bдобыч\\w*", "hunt", "loot", false, false);
		topic("\\bрейд\\w*|\\bрб\\b|\\bбосс\\w*", "raid", "boss_status", false, false);
		topic("\\bальянс\\w*|\\bсоюз\\w*", "alliance", "future_diplomacy", false, false);
		topic("\\bкуплю\\b|\\bпродам\\b|\\bпродаю\\b|\\bцен[аыуе]\\b|\\bсколько стоит\\b|\\bwts\\b|\\bwtb\\b", "trade", "price_talk", false, false);
		topic("\\bпати\\b|\\bпачк\\w*|\\bлф\\b|\\bлфм\\b|\\bлфп\\b|\\blf\\b|\\blfm\\b|\\blfp\\b", "party_find", "role_request", false, false);
		topic("\\bбь[её]м\\b|\\bбей\\b|\\bбейте\\b|\\bтаргет\\w*|\\bассист\\w*", "party_fight", "assist", false, true);
		topic("\\bкач(а\\w*|у|ну\\w*|ем)?\\b|\\bмоб\\w*|\\bспот\\w*|\\bэксп\\w*|\\bфарм\\w*", "hunt", "exp", false, false);
		topic("\\bгород\\w*|\\bтп\\b|\\bтелепорт\\w*|\\bиду\\b|\\bидём\\b|\\bбегу\\b|\\bлетим\\b", "journey", "travel", false, false);
		topic("\\bприв(ет\\w*)?\\b|\\bздаров\\w*|\\bздоров\\w*|\\bхай\\b|\\bку\\b", "city", "greeting", false, false);
	}

	private static void topic(String regex, String context, String situation, boolean support, boolean partyOnly)
	{
		TOPICS.add(new Topic(Pattern.compile(regex, Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), new Query(context, situation), support, partyOnly));
	}

	/** Городской фон: ситуации и их веса. */
	private static final Object[][] CITY =
	{
		{"social", 70},
		{"idle", 10},
		{"smalltalk", 5},
		{"crowd", 5},
		{"shop_visit", 10},
	};

	private volatile Bank _bank;
	private final Deque<Line> _recent = new ArrayDeque<>();
	private final Map<Integer, Long> _nextLook = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextAmbient = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _partyNext = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _clanNext = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _allyNext = new ConcurrentHashMap<>();
	private final Map<Long, Session> _active = new ConcurrentHashMap<>();
	private final Map<Integer, Session> _busy = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _speakerNext = new HashMap<>();
	private final Map<Long, Long> _areaNext = new HashMap<>();
	private final Map<String, Long> _sceneNext = new HashMap<>();
	private final Map<Long, Deque<Long>> _budget = new HashMap<>();
	private volatile boolean _testNoBrain;
	private volatile boolean _testAudience;

	private PhantomChatBank()
	{
	}

	// ---------- Когда и что ----------

	/**
	 * @return банк включён и язык мира русский
	 */
	private static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_CHAT_BANK && !"en".equals(L2HubWorldConfig.LANGUAGE);
	}

	/**
	 * @return запасная речь нужна сама по себе: мозгов нет или они только что не ответили. Ответы и события спрашивать не надо: там отказ мозгов уже случился.
	 */
	public boolean needed()
	{
		return enabled() && (_testNoBrain || !PhantomChat.brainEnabled() || FakePlayerBrain.getInstance().isDown());
	}

	/**
	 * Ответ игроку без мозгов.
	 * @param phantom кто отвечает
	 * @param channel канал {@link PhantomChat}: whisper, say, overheard, clan, party, event
	 * @param message что сказал игрок
	 * @return строка или {@code null} — темы нет, промолчать
	 */
	public String reply(Player phantom, String channel, String message)
	{
		if (!enabled())
		{
			return null;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if ((template == null) || PhantomManager.getInstance().isSecret(phantom))
		{
			return null;
		}
		final Query query = classify(message, channel, PhantomSupport.isSupport(template));
		return query == null ? null : pick(phantom, template, query, bankChannel(channel), false);
	}

	/**
	 * Реплика напарника на событие без мозгов.
	 * @param phantom напарник
	 * @param event событие
	 * @param fallback прежняя заготовка
	 * @return строка банка, а нет её — заготовка
	 */
	public String event(Player phantom, PhantomChat.Event event, String fallback)
	{
		final Query query = eventQuery(event);
		final PhantomTemplate template = enabled() && (query != null) ? PhantomData.getInstance().getTemplate(phantom.getName()) : null;
		final String line = template == null ? null : pick(phantom, template, query, "party", false);
		return line != null ? line : fallback;
	}

	private static Query eventQuery(PhantomChat.Event event)
	{
		switch (event)
		{
			case REST:
			{
				return new Query("support", "low_mana");
			}
			case DIED:
			{
				return new Query("journey", "death");
			}
			case REVIVED:
			{
				return new Query("journey", "return");
			}
			case OWNER_LOW_HP:
			{
				return new Query("support", "support_intent");
			}
			case JOINED:
			{
				return new Query("party_find", "invite_pending");
			}
			default:
			{
				return null;
			}
		}
	}

	/**
	 * Реплика охотника на событие без мозгов. Заготовка точнее банка почти везде («это мой моб!»); банк разбавляет только то, что повторяется чаще всего.
	 * @param hunter охотник
	 * @param kind вид события
	 * @param fallback прежняя заготовка
	 * @return что сказать
	 */
	public String hunterEvent(Player hunter, String kind, String fallback)
	{
		final Query query = "KILLED".equals(kind) ? new Query("journey", "death") : "WON".equals(kind) ? new Query("pvp", "pvp_after") : null;
		if (!enabled() || (query == null) || Rnd.nextBoolean())
		{
			return fallback;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		final String line = template == null ? null : pick(hunter, template, query, "general", false);
		return line != null ? line : fallback;
	}

	/**
	 * Обёртка строки поиска пати для {@link PhantomChatFlow}: только когда нет мозгов, данные настоящие.
	 * @param hunter охотник
	 * @param role его роль словами
	 * @return строка или {@code null} — прежняя таблица
	 */
	String lfp(Player hunter, String role)
	{
		return announce(hunter, new Query("party_find", "looking_for_group"), role, null);
	}

	/**
	 * Обёртка строки лавки для {@link PhantomChatFlow}: только когда нет мозгов, товар и цена настоящие.
	 * @param hunter охотник
	 * @param sell продаёт, а не покупает
	 * @param goods товар с ценой
	 * @return строка или {@code null} — прежняя таблица
	 */
	String store(Player hunter, boolean sell, String goods)
	{
		return announce(hunter, new Query("trade", sell ? "sell_offer" : "buy_request"), null, goods);
	}

	private String announce(Player hunter, Query query, String role, String item)
	{
		if (!needed())
		{
			return null;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if (template == null)
		{
			return null;
		}
		final Map<String, String> slots = new HashMap<>();
		slots.put("class", role);
		slots.put("level", Integer.toString(hunter.getLevel()));
		slots.put("item", item);
		final Line line = choose(template, query, null, slots, true);
		return line == null ? null : render(line.text(), slots);
	}

	/**
	 * Тема сообщения — по целым словам: «интересно» — не «рес», «обманули» — не «мана».
	 * @param message что сказал игрок
	 * @param channel канал {@link PhantomChat}
	 * @param support говорит саппорт: хил и рес обещает только он
	 * @return что ответить или {@code null} — промолчать
	 */
	static Query classify(String message, String channel, boolean support)
	{
		final String text = message == null ? "" : message.toLowerCase(Locale.ROOT);
		for (Topic topic : TOPICS)
		{
			if (topic.pattern().matcher(text).find())
			{
				return (topic.support() && !support) || (topic.partyOnly() && !"party".equals(channel)) ? null : topic.query();
			}
		}
		return "clan".equals(channel) ? new Query("clan", "clan_chat") : null;
	}

	private static String bankChannel(String channel)
	{
		switch (channel)
		{
			case "whisper":
			case "clan":
			case "party":
			{
				return channel;
			}
			case "event":
			{
				return "party";
			}
			default:
			{
				return "general";
			}
		}
	}

	// ---------- Выбор строки ----------

	/**
	 * Кому какая строка: женщине не мужские формы и наоборот; дерзкое — вспыльчивому или рисковому, осторожное — осторожному или мягкому, и так далее.
	 */
	private static boolean fits(String gender, String persona, PhantomTemplate template)
	{
		return ("m".equals(gender) ? !template.isFemale() : !"f".equals(gender) || template.isFemale()) && persona(persona, template);
	}

	private static boolean persona(String persona, PhantomTemplate template)
	{
		switch (persona)
		{
			case "bold":
			{
				return (template.getTemper() >= 1.2f) || template.isReckless();
			}
			case "careful":
			{
				return template.isCautious() || (template.getTemper() <= 0.5f);
			}
			case "calm":
			{
				return template.getTemper() <= 1f;
			}
			case "ironic":
			{
				return template.getTemper() >= 1f;
			}
			case "social":
			case "talkative":
			{
				return template.getSocial() >= 1f;
			}
			default:
			{
				return true;
			}
		}
	}

	private String pick(Player actor, PhantomTemplate template, Query query, String channel, boolean slotted)
	{
		final Map<String, String> slots = new HashMap<>();
		slots.put("class", PhantomHunterManager.roleWord(template, actor));
		slots.put("level", Integer.toString(actor.getLevel()));
		final Line line = choose(template, query, channel, slots, slotted);
		return line == null ? null : render(line.text(), slots);
	}

	/**
	 * @param channel канал: сначала его строки, нет их — той же ситуации; {@code null} — любой
	 * @param slots что подставить: нечего подставить — строка не годится
	 * @param slotted только строки с подстановкой (обёртка объявления)
	 */
	private Line choose(PhantomTemplate template, Query query, String channel, Map<String, String> slots, boolean slotted)
	{
		final List<Line> pool = bank().lines.get(query.context() + "/" + query.situation());
		if (pool == null)
		{
			return null;
		}
		Line other = null;
		final int offset = Rnd.get(pool.size());
		for (int i = 0; i < pool.size(); i++)
		{
			final Line line = pool.get((offset + i) % pool.size());
			if (!fits(line.gender(), line.persona(), template) || (render(line.text(), slots) == null) || (slotted && (!line.text().contains("{{") || line.text().contains("цен"))) || wasRecent(line))
			{
				continue;
			}
			if ((channel == null) || line.channel().equals(channel))
			{
				remember(line);
				return line;
			}
			if (other == null)
			{
				other = line;
			}
		}
		if (other != null)
		{
			remember(other);
		}
		return other;
	}

	private boolean wasRecent(Line line)
	{
		synchronized (_recent)
		{
			return _recent.contains(line);
		}
	}

	private void remember(Line line)
	{
		synchronized (_recent)
		{
			_recent.addLast(line);
			while (_recent.size() > RECENT)
			{
				_recent.pollFirst();
			}
		}
	}

	/**
	 * @return текст с подстановками или {@code null} — подставить нечего
	 */
	private static String render(String text, Map<String, String> slots)
	{
		if (!text.contains("{{"))
		{
			return text;
		}
		String out = text;
		for (Map.Entry<String, String> slot : slots.entrySet())
		{
			final String key = "{{" + slot.getKey() + "}}";
			if (out.contains(key))
			{
				if (slot.getValue() == null)
				{
					return null;
				}
				out = out.replace(key, slot.getValue());
			}
		}
		return out.contains("{{") ? null : out;
	}

	// ---------- Загрузка ----------

	private Bank bank()
	{
		Bank bank = _bank;
		if ((bank == null) || (bank.mat != PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT))
		{
			synchronized (this)
			{
				bank = _bank;
				if ((bank == null) || (bank.mat != PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT))
				{
					bank = new Bank(PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT);
					read(bank, BANK_FILE);
					if (bank.mat)
					{
						read(bank, MAT_FILE);
					}
					_bank = bank;
					LOGGER.info(getClass().getSimpleName() + ": loaded " + bank.count + " lines and " + bank.scenes.size() + " scenes" + (bank.mat ? ", swearing on" : "") + ".");
				}
			}
		}
		return bank;
	}

	private static void read(Bank bank, String path)
	{
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			final Element root = factory.newDocumentBuilder().parse(new File(ServerConfig.DATAPACK_ROOT, path)).getDocumentElement();
			for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
			{
				if (!(node instanceof Element element))
				{
					continue;
				}
				if ("line".equals(element.getTagName()))
				{
					final Line line = new Line(element.getAttribute("c"), element.getAttribute("s"), element.getAttribute("ch"), element.getAttribute("p"), element.getAttribute("g"), element.getTextContent().strip());
					bank.lines.computeIfAbsent(line.context() + "/" + line.situation(), key -> new ArrayList<>()).add(line);
					bank.count++;
				}
				else if ("scene".equals(element.getTagName()))
				{
					final List<Role> roles = new ArrayList<>();
					final List<SceneLine> lines = new ArrayList<>();
					for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling())
					{
						if (child instanceof Element part)
						{
							if ("role".equals(part.getTagName()))
							{
								roles.add(new Role(part.getAttribute("id"), part.getAttribute("p"), part.getAttribute("g")));
							}
							else
							{
								lines.add(new SceneLine(part.getAttribute("role"), part.getTextContent().strip()));
							}
						}
					}
					bank.scenes.add(new Scene(element.getAttribute("id"), roles, lines));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomChatBank.class.getSimpleName() + ": can't read " + path + ".", e);
		}
	}

	// ---------- Тик охотника: сцены и фон ----------

	/**
	 * Тик охотника из {@link PhantomBanter#tick}, после ключа {@code HunterBanter}.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	public void tick(Player hunter, PhantomTemplate template, long now)
	{
		final int id = hunter.getObjectId();
		if (!needed() || _busy.containsKey(id) || (now < _nextLook.getOrDefault(id, 0L)))
		{
			return;
		}
		_nextLook.put(id, now + LOOK_GAP);
		if (hunter.isDead() || hunter.isTeleporting() || hunter.isCastingNow() || PhantomManager.getInstance().isSecret(hunter) || (PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED))
		{
			return;
		}

		final Session session = open(hunter, now);
		if (session != null)
		{
			advance(session);
		}
		else
		{
			ambient(hunter, template, now);
		}
	}

	private boolean audience(Player player)
	{
		return _testAudience || PhantomBanter.audience(player);
	}

	/** Актёр сцены: охотник в мирной зоне, ничем не занят, в пати нет живых. */
	private static boolean safe(Player player)
	{
		if ((player == null) || !player.isOnline() || player.isDead() || player.isTeleporting() || player.isInCombat() || player.isCastingNow() || player.isInOlympiadMode() || player.isOnEvent() || player.isRegisteredOnEvent() || player.isInDuel() || (player.getPvpFlag() != 0) || !player.isInsideZone(ZoneId.PEACE) || PhantomManager.getInstance().isSecret(player))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		if ((template == null) || !template.isHunter() || PhantomCompanionManager.getInstance().isCompanion(template))
		{
			return false;
		}
		return !player.isInParty() || !hasLive(player.getParty().getMembers());
	}

	private static boolean hasLive(List<Player> members)
	{
		for (Player member : members)
		{
			if (!PhantomManager.getInstance().isPhantom(member) || PhantomClans.getInstance().isTestPlayer(member))
			{
				return true;
			}
		}
		return false;
	}

	private static long region(Player player)
	{
		return ((long) player.getInstanceId() << 32) | (MapRegionData.getInstance().getMapRegionLocId(player) & 0xffffffffL);
	}

	/**
	 * @return начатый разговор или {@code null}
	 */
	private synchronized Session open(Player first, long now)
	{
		// Актёры — кто стоит: бегущий мимо уходит из сцены после первой строки.
		if (!safe(first) || first.isMoving() || (now < _speakerNext.getOrDefault(first.getObjectId(), 0L)) || !audience(first))
		{
			return null;
		}
		final long region = region(first);
		if (_active.containsKey(region) || (now < _areaNext.getOrDefault(region, 0L)))
		{
			return null;
		}

		final List<Player> actors = new ArrayList<>();
		actors.add(first);
		for (Player other : World.getVisibleObjectsInRange(first, Player.class, ACTOR_RANGE))
		{
			if ((other != first) && safe(other) && !other.isMoving() && !_busy.containsKey(other.getObjectId()) && (now >= _speakerNext.getOrDefault(other.getObjectId(), 0L)) && (region(other) == region))
			{
				actors.add(other);
			}
		}
		if (actors.size() < 2)
		{
			return null;
		}
		Collections.shuffle(actors);

		final List<Scene> scenes = bank().scenes;
		if (scenes.isEmpty())
		{
			return null;
		}
		for (int i = 0; i < SCENE_TRIES; i++)
		{
			final Scene scene = scenes.get(Rnd.get(scenes.size()));
			if ((scene.roles().size() > actors.size()) || (now < _sceneNext.getOrDefault(scene.id(), 0L)) || !budget(region, scene.lines().size(), now))
			{
				continue;
			}
			final Map<String, Player> roles = new LinkedHashMap<>();
			if (!bind(scene, 0, actors, roles, now))
			{
				continue;
			}

			final Session session = new Session(region, scene, roles, roles.values().iterator().next());
			_active.put(region, session);
			for (Player actor : roles.values())
			{
				_busy.put(actor.getObjectId(), session);
			}
			_areaNext.put(region, now + MIN_AREA_GAP + Rnd.get(MAX_AREA_GAP - MIN_AREA_GAP));
			_sceneNext.put(scene.id(), now + SCENE_GAP);
			return session;
		}
		return null;
	}

	/** Раздать роли по роду и характеру. */
	private static boolean bind(Scene scene, int index, List<Player> actors, Map<String, Player> roles, long now)
	{
		if (index == scene.roles().size())
		{
			return true;
		}
		final Role role = scene.roles().get(index);
		final String topic = SCENE_TOPIC + scene.id();
		for (Player actor : actors)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(actor.getName());
			if ((template != null) && !roles.containsValue(actor) && fits(role.gender(), role.persona(), template) && PhantomQuiet.getInstance().allowed(actor, topic, now))
			{
				roles.put(role.id(), actor);
				if (bind(scene, index + 1, actors, roles, now))
				{
					return true;
				}
				roles.remove(role.id());
			}
		}
		return false;
	}

	private boolean budget(long region, int lines, long now)
	{
		final Deque<Long> times = _budget.computeIfAbsent(region, key -> new ArrayDeque<>());
		while (!times.isEmpty() && ((now - times.peekFirst()) >= 3600000))
		{
			times.pollFirst();
		}
		return (times.size() + lines) <= LINES_PER_HOUR;
	}

	private void advance(Session session)
	{
		final SceneLine line;
		final Player actor;
		final boolean last;
		synchronized (this)
		{
			if (_active.get(session.region) != session)
			{
				return;
			}
			// Выключили ключ или мозги вернулись: разговор кончается на полуслове.
			boolean stop = !PhantomHuntersConfig.HUNTER_BANTER || !needed() || !audience(session.anchor);
			for (Player player : session.roles.values())
			{
				stop |= !safe(player) || (player.calculateDistance2D(session.anchor) > ACTOR_RANGE);
			}
			if (stop)
			{
				finish(session);
				return;
			}

			final long now = System.currentTimeMillis();
			line = session.scene.lines().get(session.index);
			actor = session.roles.get(line.role());
			_budget.computeIfAbsent(session.region, key -> new ArrayDeque<>()).addLast(now);
			_speakerNext.put(actor.getObjectId(), now + SPEAKER_GAP);
			last = ++session.index >= session.scene.lines().size();
			if (last)
			{
				finish(session);
			}
		}

		PhantomChat.getInstance().hunterSay(actor, line.text(), SCENE_TOPIC + session.scene.id());
		if (!last)
		{
			ThreadPool.schedule(() -> advance(session), MIN_LINE_DELAY + Rnd.get(MAX_LINE_DELAY - MIN_LINE_DELAY));
		}
	}

	private void finish(Session session)
	{
		_active.remove(session.region, session);
		for (Player actor : session.roles.values())
		{
			_busy.remove(actor.getObjectId(), session);
		}
	}

	/**
	 * Фантом сказал что-то вслух (из {@link PhantomChat}): своё, не из разговора, — разговор обрывается.
	 * @param speaker кто
	 * @param topic тема реплики
	 */
	void spoke(Player speaker, String topic)
	{
		if (_busy.isEmpty() || ((topic != null) && topic.startsWith(SCENE_TOPIC)))
		{
			return;
		}
		synchronized (this)
		{
			final Session session = _busy.get(speaker.getObjectId());
			if (session != null)
			{
				finish(session);
			}
		}
	}

	/**
	 * Живой заговорил в общем чате: разговоры рядом с ним обрываются и пока не начинаются.
	 * @param human живой игрок
	 */
	void humanSpoke(Player human)
	{
		if (_active.isEmpty())
		{
			return;
		}
		synchronized (this)
		{
			final long now = System.currentTimeMillis();
			for (Session session : new ArrayList<>(_active.values()))
			{
				for (Player actor : session.roles.values())
				{
					if ((actor.getInstanceId() == human.getInstanceId()) && (actor.calculateDistance2D(human) <= PhantomQuiet.RANGE))
					{
						finish(session);
						_areaNext.merge(session.region, now + AFTER_HUMAN, Math::max);
						break;
					}
				}
			}
		}
	}

	private void ambient(Player hunter, PhantomTemplate template, long now)
	{
		final int id = hunter.getObjectId();
		if ((now < _nextAmbient.getOrDefault(id, 0L)) || hunter.isOnEvent() || hunter.isInOlympiadMode() || hunter.isInStoreMode())
		{
			return;
		}
		_nextAmbient.put(id, now + AMBIENT_GAP);

		final Clan clan = hunter.getClan();
		if (clan != null)
		{
			if ((Rnd.get(100) < ALLY_CHANCE) && (clan.getAllyId() != 0) && (now >= _allyNext.getOrDefault(clan.getAllyId(), 0L)) && hasLiveInAlly(clan.getAllyId()))
			{
				final String text = pick(hunter, template, new Query("alliance", "future_cooperation"), "alliance", false);
				if (text != null)
				{
					_allyNext.put(clan.getAllyId(), now + ALLY_GAP);
					clan.broadcastToOnlineAllyMembers(new CreatureSay(hunter, ChatType.ALLIANCE, hunter.getName(), text));
				}
				return;
			}
			if ((Rnd.get(100) < CLAN_CHANCE) && (now >= _clanNext.getOrDefault(clan.getId(), 0L)) && hasLiveInClan(clan))
			{
				final String text = pick(hunter, template, new Query("clan", "clan_chat"), "clan", false);
				if (text != null)
				{
					_clanNext.put(clan.getId(), now + CLAN_GAP);
					clan.broadcastCSToOnlineMembers(new CreatureSay(hunter, ChatType.CLAN, hunter.getName(), text), hunter);
				}
				return;
			}
		}

		// Вслух: только если рядом есть кому слышать. В бою рот занят.
		final Query query = hunter.isInsideZone(ZoneId.PEACE) ? new Query("city", citySituation()) : hunter.isSitting() ? new Query("journey", "rest") : hunter.isInCombat() ? null : new Query("hunt", "exp");
		if ((query == null) || !audience(hunter))
		{
			return;
		}
		final String topic = LINE_TOPIC + query.context();
		if (!PhantomQuiet.getInstance().allowed(hunter, topic, now))
		{
			return;
		}
		final long region = region(hunter);
		synchronized (this)
		{
			if (!budget(region, 1, now))
			{
				return;
			}
			_budget.computeIfAbsent(region, key -> new ArrayDeque<>()).addLast(now);
		}
		final String text = pick(hunter, template, query, "general", false);
		if (text != null)
		{
			PhantomChat.getInstance().hunterSay(hunter, text, topic);
		}
	}

	private static String citySituation()
	{
		int roll = Rnd.get(100);
		for (Object[] row : CITY)
		{
			roll -= (Integer) row[1];
			if (roll < 0)
			{
				return (String) row[0];
			}
		}
		return "social";
	}

	/**
	 * Фон в чат пати с живым (из {@code PhantomCompanionManager}): не чаще {@value #PARTY_GAP} мс на пати.
	 * @param phantom напарник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	void partyTick(Player phantom, PhantomTemplate template, long now)
	{
		if (!needed() || !phantom.isInParty() || phantom.isDead() || phantom.isCastingNow() || PhantomManager.getInstance().isSecret(phantom))
		{
			return;
		}
		final int party = phantom.getParty().getLeaderObjectId();
		if ((now < _partyNext.getOrDefault(party, 0L)) || !hasLive(phantom.getParty().getMembers()))
		{
			return;
		}
		_partyNext.put(party, now + PARTY_GAP);

		final Query query;
		if ((phantom.getCurrentMp() * 100) < (phantom.getMaxMp() * LOW_MANA))
		{
			query = new Query("support", "low_mana");
		}
		else if (phantom.isInCombat())
		{
			query = (phantom.getTarget() instanceof Monster monster) && monster.isRaid() ? new Query("raid", "boss_engage") : new Query("party_fight", Rnd.nextBoolean() ? "assist" : "focus_target");
		}
		else
		{
			query = new Query("hunt", "exp");
		}
		final String text = pick(phantom, template, query, "party", false);
		if (text != null)
		{
			phantom.getParty().broadcastCreatureSay(new CreatureSay(phantom, ChatType.PARTY, phantom.getName(), text), phantom);
		}
	}

	private static boolean hasLiveInClan(Clan clan)
	{
		for (ClanMember member : clan.getMembers())
		{
			final Player player = member.getPlayer();
			if ((player != null) && player.isOnline() && !player.isInOfflineMode() && !PhantomManager.getInstance().isPhantom(player))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasLiveInAlly(int allyId)
	{
		for (Player player : World.getPlayers())
		{
			if ((player.getAllyId() == allyId) && !player.isInOfflineMode() && !PhantomManager.getInstance().isPhantom(player))
			{
				return true;
			}
		}
		return false;
	}

	// ---------- Самопроверка ----------

	/**
	 * {@code HuntersSelfTest = CHATBANK}: ключи и порядок речи, темы, род и характер, подстановки, ворота и обрывы сцен. Идёт в своём потоке: сцены говорят с паузами, мозги отвечают сами.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		ThreadPool.execute(() -> getInstance().runSelfTest(world));
	}

	private void runSelfTest(List<Player> world)
	{
		final String tag = "SelfTest CHATBANK: ";
		final List<String> failed = new ArrayList<>();
		final boolean bankKey = PhantomHuntersConfig.HUNTER_CHAT_BANK;
		final boolean matKey = PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT;
		final boolean banterKey = PhantomHuntersConfig.HUNTER_BANTER;
		final String language = L2HubWorldConfig.LANGUAGE;
		final List<Player> frozen = new ArrayList<>();
		try
		{
			final Player healer = find(world, player -> PhantomSupport.isSupport(template(player)));
			final Player fighter = find(world, player -> !PhantomSupport.isSupport(template(player)));
			final Player man = find(world, player -> !template(player).isFemale());
			final Player woman = find(world, player -> template(player).isFemale());
			if ((healer == null) || (fighter == null) || (man == null) || (woman == null))
			{
				LOGGER.warning(tag + "needs a support, a fighter, a man and a woman among the hunters.");
				return;
			}

			// 1. Ключи и язык.
			PhantomHuntersConfig.HUNTER_CHAT_BANK = false;
			check(tag, failed, "1. ключ выключен: молчит, заготовка на месте", (reply(fighter, "whisper", "привет") == null) && !needed() && "заготовка".equals(event(fighter, PhantomChat.Event.DIED, "заготовка")), "");
			PhantomHuntersConfig.HUNTER_CHAT_BANK = true;
			L2HubWorldConfig.LANGUAGE = "en";
			check(tag, failed, "2. английский: молчит, заготовка на месте", (reply(fighter, "whisper", "привет") == null) && !needed() && "заготовка".equals(event(fighter, PhantomChat.Event.DIED, "заготовка")) && (lfp(fighter, "дд") == null), "");
			L2HubWorldConfig.LANGUAGE = "ru";

			// 2. Данные и мат.
			final Pattern swear = Pattern.compile("(^|\\s)(бля|блять|блядь|пиздец|нахуй|нихуя|сука|заебал\\w*)($|[\\s,.!?)])", Pattern.UNICODE_CHARACTER_CLASS | Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
			PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT = false;
			final Bank clean = bank();
			final long cleanSwears = swears(clean, swear);
			check(tag, failed, "3. банк без мата", (clean.count > 5000) && (clean.scenes.size() > 500) && (cleanSwears == 0), clean.count + " строк, " + clean.scenes.size() + " сцен, мата " + cleanSwears);
			PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT = true;
			final Bank rude = bank();
			check(tag, failed, "4. свой ключ добавляет мат", (rude.count > clean.count) && (rude.scenes.size() > clean.scenes.size()) && (swears(rude, swear) > 0), rude.count + " строк, " + rude.scenes.size() + " сцен, мата " + swears(rude, swear));
			PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT = matKey;

			// 3. Темы по словам.
			final Object[][] topics =
			{
				{"интересно", "whisper", false, null},
				{"обманули", "whisper", false, null},
				{"ресурс нужен", "whisper", false, null},
				{"команда у нас хорошая", "whisper", false, null},
				{"качество так себе", "whisper", false, null},
				{"рес плз", "whisper", true, "support_intent"},
				{"рес плз", "whisper", false, null},
				{"хил есть?", "party", true, "heal_request"},
				{"мана есть?", "whisper", false, "low_mana"},
				{"куплю меч", "say", false, "price_talk"},
				{"пати есть?", "say", false, "role_request"},
				{"кто на твт?", "say", false, "tvt_signup"},
				{"кто на дуэль", "say", false, "duel_request"},
				{"бьём этого", "say", false, null},
				{"бьём этого", "party", false, "assist"},
				{"куда идём качаться", "say", false, "exp"},
				{"привет", "say", false, "greeting"},
				{"просто так", "clan", false, "clan_chat"},
				{"просто так", "say", false, null},
			};
			final List<String> wrong = new ArrayList<>();
			for (Object[] row : topics)
			{
				final Query query = classify((String) row[0], (String) row[1], (Boolean) row[2]);
				final String got = query == null ? null : query.situation();
				if ((got == null) ? (row[3] != null) : !got.equals(row[3]))
				{
					wrong.add(row[0] + " → " + got);
				}
			}
			check(tag, failed, "5. темы по целым словам", wrong.isEmpty(), wrong.toString());
			final String reply = reply(fighter, "whisper", "кто на твт?");
			check(tag, failed, "6. ответ по теме есть", reply != null, "«" + reply + "»");

			// 4. Род и характер.
			int genderWrong = 0;
			int personaWrong = 0;
			final PhantomTemplate manTemplate = template(man);
			final PhantomTemplate womanTemplate = template(woman);
			final Query social = new Query("city", "social");
			for (int i = 0; i < 400; i++)
			{
				final Line forMan = choose(manTemplate, social, null, Map.of(), false);
				final Line forWoman = choose(womanTemplate, social, null, Map.of(), false);
				genderWrong += (forMan != null) && "f".equals(forMan.gender()) ? 1 : 0;
				genderWrong += (forWoman != null) && "m".equals(forWoman.gender()) ? 1 : 0;
				personaWrong += (forMan != null) && !persona(forMan.persona(), manTemplate) ? 1 : 0;
				personaWrong += (forWoman != null) && !persona(forWoman.persona(), womanTemplate) ? 1 : 0;
			}
			check(tag, failed, "7. род и характер строк", (genderWrong == 0) && (personaWrong == 0), "чужой род " + genderWrong + ", чужой характер " + personaWrong);
			final Player calm = find(world, player -> (template(player).getTemper() < 1.2f) && !template(player).isReckless());
			int boldWrong = 0;
			if (calm != null)
			{
				for (int i = 0; i < 400; i++)
				{
					final Line line = choose(template(calm), new Query("pvp", "duel_request"), null, Map.of(), false);
					boldWrong += (line != null) && "bold".equals(line.persona()) ? 1 : 0;
				}
			}
			check(tag, failed, "8. дерзкое спокойному не достаётся", boldWrong == 0, calm == null ? "нет такого" : calm.getName() + ": " + boldWrong);

			// 5. Подстановки.
			_testNoBrain = true;
			final String lfp = lfp(fighter, "дд");
			final String wts = store(fighter, true, "Long Sword 1.5кк");
			final String wtb = store(fighter, false, "Long Sword 1.5кк");
			final String enumName = fighter.getPlayerClass().name();
			check(tag, failed, "9. подстановки по-человечески", (lfp != null) && lfp.contains("дд") && lfp.contains(Integer.toString(fighter.getLevel())) && !lfp.contains(enumName) && !lfp.contains("{{") && (wts != null) && wts.contains("Long Sword") && (wtb != null) && wtb.contains("Long Sword"), "«" + lfp + "», «" + wts + "», «" + wtb + "»");

			// 6. Мозги первые: заглушка на этом же сервере то отвечает, то нет.
			brainOrder(tag, failed, fighter, healer);

			// 7. Сцены.
			scenes(tag, failed, world, frozen);

			LOGGER.info(tag + "done, " + failed.size() + " failed" + (failed.isEmpty() ? "." : ": " + failed));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed", e);
			LOGGER.info(tag + "done, stopped by an exception.");
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_CHAT_BANK = bankKey;
			PhantomHuntersConfig.HUNTER_CHAT_BANK_MAT = matKey;
			PhantomHuntersConfig.HUNTER_BANTER = banterKey;
			L2HubWorldConfig.LANGUAGE = language;
			_testNoBrain = false;
			_testAudience = false;
			for (Player player : frozen)
			{
				PhantomFavors.getInstance().unfreeze(player);
			}
		}
	}

	private void brainOrder(String tag, List<String> failed, Player phantom, Player speaker) throws Exception
	{
		final boolean brain = PhantomPartyConfig.PHANTOM_BRAIN;
		final boolean fake = FakePlayersConfig.FAKE_PLAYER_BRAIN;
		final String url = FakePlayersConfig.FAKE_PLAYER_BRAIN_URL;
		final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
		final java.util.concurrent.atomic.AtomicInteger status = new java.util.concurrent.atomic.AtomicInteger(200);
		final com.sun.net.httpserver.HttpServer stub = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
		stub.createContext("/chat", exchange ->
		{
			if (new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).contains("мана есть?"))
			{
				calls.incrementAndGet();
			}
			final byte[] body = "ответ мозгов".getBytes(java.nio.charset.StandardCharsets.UTF_8);
			exchange.sendResponseHeaders(status.get(), body.length);
			exchange.getResponseBody().write(body);
			exchange.close();
		});
		stub.start();
		try
		{
			PhantomPartyConfig.PHANTOM_BRAIN = true;
			FakePlayersConfig.FAKE_PLAYER_BRAIN = true;
			FakePlayersConfig.FAKE_PLAYER_BRAIN_URL = "http://127.0.0.1:" + stub.getAddress().getPort();
			_testNoBrain = false;

			final PhantomChat chat = PhantomChat.getInstance();
			FakePlayerBrain.getInstance().clearDown();
			final Line[] before = new Line[1];
			synchronized (_recent)
			{
				before[0] = _recent.peekLast();
			}
			chat.onWhisper(speaker, phantom, "мана есть?");
			Thread.sleep(1500);
			Line now;
			synchronized (_recent)
			{
				now = _recent.peekLast();
			}
			check(tag, failed, "10. мозги ответили: банк не трогали", (calls.get() == 1) && (now == before[0]) && !needed(), "запросов " + calls.get());

			status.set(503);
			chat.onWhisper(speaker, phantom, "мана есть?");
			Thread.sleep(1500);
			synchronized (_recent)
			{
				now = _recent.peekLast();
			}
			check(tag, failed, "11. мозги не ответили: банк вместо них и запасная речь нужна", (calls.get() == 2) && (now != before[0]) && needed(), "запросов " + calls.get());

			status.set(200);
			chat.onWhisper(speaker, phantom, "мана есть?");
			Thread.sleep(1500);
			check(tag, failed, "12. мозги вернулись: запасная речь не нужна", (calls.get() == 3) && !needed(), "запросов " + calls.get());
		}
		finally
		{
			stub.stop(0);
			PhantomPartyConfig.PHANTOM_BRAIN = brain;
			FakePlayersConfig.FAKE_PLAYER_BRAIN = fake;
			FakePlayersConfig.FAKE_PLAYER_BRAIN_URL = url;
			FakePlayerBrain.getInstance().clearDown();
		}
	}

	private void scenes(String tag, List<String> failed, List<Player> world, List<Player> frozen) throws Exception
	{
		_testNoBrain = true;
		PhantomHuntersConfig.HUNTER_BANTER = true;

		// Двое в мирной зоне: кто уже там, а нет таких — привести в Гиран.
		final List<Player> actors = new ArrayList<>();
		Player field = null;
		for (Player player : world)
		{
			if (safe(player))
			{
				if (actors.isEmpty() || (actors.get(0).calculateDistance2D(player) <= ACTOR_RANGE))
				{
					actors.add(player);
				}
			}
			else if ((field == null) && !player.isInsideZone(ZoneId.PEACE) && !player.isDead() && !player.isInParty())
			{
				field = player;
			}
		}
		if (actors.size() < 3)
		{
			actors.clear();
			for (Player player : world)
			{
				final PhantomTemplate template = template(player);
				// Того, кто нужен вне мирной зоны (шаг 14), в город не ведём.
				if ((actors.size() < 4) && (player != field) && !player.isInParty() && !player.isDead() && !player.isInStoreMode() && !player.isInsideZone(ZoneId.PEACE) && !PhantomCompanionManager.getInstance().isCompanion(template))
				{
					PhantomFavors.getInstance().freeze(player);
					frozen.add(player);
					player.abortAttack();
					player.abortCast();
					player.teleToLocation(PhantomMarket.GIRAN_SQUARE, true);
					actors.add(player);
				}
			}
		}
		// Из боя приходят с боевой стойкой ещё секунд 15: ждём, пока двое станут свободны.
		final List<Player> ready = new ArrayList<>();
		for (int i = 0; (i < 30) && (ready.size() < 2); i++)
		{
			Thread.sleep(1000);
			ready.clear();
			for (Player player : actors)
			{
				if (safe(player))
				{
					ready.add(player);
				}
			}
		}
		if (ready.size() < 2)
		{
			final StringBuilder sb = new StringBuilder();
			for (Player player : actors)
			{
				sb.append(player.getName()).append(" бой ").append(player.isInCombat()).append(" каст ").append(player.isCastingNow()).append(" мир ").append(player.isInsideZone(ZoneId.PEACE)).append(" телепорт ").append(player.isTeleporting()).append("; ");
			}
			check(tag, failed, "13. сцены: нет двоих в мирной зоне", false, sb.toString());
			return;
		}
		final Player first = ready.get(0);
		LOGGER.info(tag + "actors " + ready + ", in the field " + field + ".");
		// Все рядом стоят: иначе свои дела (бафы, дорога к гейткиперу) уводят актёра из сцены, как и в игре.
		for (Player player : World.getVisibleObjectsInRange(first, Player.class, ACTOR_RANGE))
		{
			if ((template(player) != null) && !frozen.contains(player))
			{
				PhantomFavors.getInstance().freeze(player);
				frozen.add(player);
				player.stopMove(null);
			}
		}
		if (!frozen.contains(first))
		{
			PhantomFavors.getInstance().freeze(first);
			frozen.add(first);
			first.stopMove(null);
		}

		_testAudience = false;
		clearScenes();
		check(tag, failed, "13. без живого сцены нет", open(first, System.currentTimeMillis()) == null, "");

		_testAudience = true;
		if (field != null)
		{
			clearScenes();
			check(tag, failed, "14. вне мирной зоны сцены нет", open(field, System.currentTimeMillis()) == null, field.getName());
		}

		// Обрыв своей репликой актёра.
		clearScenes();
		Session session = open(first, System.currentTimeMillis());
		check(tag, failed, "15. живой рядом, двое в мирной зоне: сцена есть", session != null, session == null ? "" : session.scene.id() + " " + session.roles.values());
		if (session == null)
		{
			return;
		}
		spoke(session.roles.values().iterator().next(), SCENE_TOPIC + session.scene.id());
		check(tag, failed, "16. своя строка сцены её не рвёт", _active.get(session.region) == session, "");
		spoke(session.roles.values().iterator().next(), "другое");
		check(tag, failed, "17. актёр сказал своё: обрыв", !_active.containsKey(session.region) && _busy.isEmpty(), "");

		// Обрыв живым.
		clearScenes();
		session = open(first, System.currentTimeMillis());
		final long region = session == null ? 0 : session.region;
		if (session != null)
		{
			humanSpoke(session.anchor);
			synchronized (this)
			{
				check(tag, failed, "18. живой заговорил: обрыв и тишина", !_active.containsKey(region) && (_areaNext.getOrDefault(region, 0L) > System.currentTimeMillis()), "");
			}
		}

		// Выключатель.
		clearScenes();
		session = open(first, System.currentTimeMillis());
		if (session != null)
		{
			PhantomHuntersConfig.HUNTER_BANTER = false;
			advance(session);
			check(tag, failed, "19. HunterBanter выключен: сцена стоит", !_active.containsKey(session.region), "");
			PhantomHuntersConfig.HUNTER_BANTER = true;
		}

		// Сцена целиком; строки — в журнал самопроверки.
		clearScenes();
		session = open(first, System.currentTimeMillis());
		check(tag, failed, "20. сцена целиком", session != null, session == null ? "" : session.scene.id());
		if (session != null)
		{
			for (SceneLine line : session.scene.lines())
			{
				LOGGER.info(tag + session.roles.get(line.role()).getName() + ": " + line.text());
			}
			final int total = session.scene.lines().size();
			advance(session);
			final long deadline = System.currentTimeMillis() + (total * (MAX_LINE_DELAY + 1000L));
			while (_active.containsKey(session.region) && (System.currentTimeMillis() < deadline))
			{
				Thread.sleep(500);
			}
			check(tag, failed, "21. сцена дошла до конца и освободила актёров", (session.index >= total) && !_active.containsKey(session.region) && !_busy.containsKey(session.anchor.getObjectId()), session.index + " из " + total + (session.index >= total ? "" : ", " + stopReason(session)));
		}

		// Фон.
		clearScenes();
		_nextAmbient.clear();
		synchronized (this)
		{
			_budget.clear();
		}
		PhantomQuiet.getInstance().clear();
		final Line before;
		synchronized (_recent)
		{
			before = _recent.peekLast();
		}
		for (int i = 0; i < 10; i++)
		{
			_nextAmbient.clear();
			ambient(first, template(first), System.currentTimeMillis());
		}
		final Line after;
		synchronized (_recent)
		{
			after = _recent.peekLast();
		}
		check(tag, failed, "22. фон при живом рядом говорит", after != before, after == null ? "" : after.situation() + ": " + after.text());
	}

	/** Самопроверка: почему сцена встала, по состоянию сейчас. */
	private String stopReason(Session session)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("banter ").append(PhantomHuntersConfig.HUNTER_BANTER).append(", нужен ").append(needed()).append(", зритель ").append(audience(session.anchor));
		for (Player actor : session.roles.values())
		{
			sb.append("; ").append(actor.getName()).append(" safe ").append(safe(actor)).append(" бой ").append(actor.isInCombat()).append(" каст ").append(actor.isCastingNow()).append(" мир ").append(actor.isInsideZone(ZoneId.PEACE)).append(" до ").append((int) actor.calculateDistance2D(session.anchor));
		}
		return sb.toString();
	}

	private synchronized void clearScenes()
	{
		for (Session session : new ArrayList<>(_active.values()))
		{
			finish(session);
		}
		_speakerNext.clear();
		_areaNext.clear();
		_sceneNext.clear();
		PhantomQuiet.getInstance().clear();
	}

	private static long swears(Bank bank, Pattern swear)
	{
		long count = 0;
		for (List<Line> pool : bank.lines.values())
		{
			for (Line line : pool)
			{
				count += swear.matcher(line.text()).find() ? 1 : 0;
			}
		}
		for (Scene scene : bank.scenes)
		{
			for (SceneLine line : scene.lines())
			{
				count += swear.matcher(line.text()).find() ? 1 : 0;
			}
		}
		return count;
	}

	private static PhantomTemplate template(Player player)
	{
		return PhantomData.getInstance().getTemplate(player.getName());
	}

	private static Player find(List<Player> world, java.util.function.Predicate<Player> test)
	{
		for (Player player : world)
		{
			if ((template(player) != null) && test.test(player))
			{
				return player;
			}
		}
		return null;
	}

	private static void check(String tag, List<String> failed, String name, boolean ok, String detail)
	{
		LOGGER.info(tag + (ok ? "OK " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
		if (!ok)
		{
			failed.add(name);
		}
	}

	public static PhantomChatBank getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomChatBank INSTANCE = new PhantomChatBank();
	}
}
