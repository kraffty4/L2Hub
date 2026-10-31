package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.ChatBroadcastType;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;

/**
 * L2Hub: директор торгового чата (docs/PHANTOMS.md, раздел 4.71; docs/LIVE_SERVER.md, п. 3). Трейд — звук сервера: пока в регионе есть живой игрок, охотники этого региона
 * пишут туда с умеренной частотой ({@code HunterChatFlowPerHour}). Там, где живых нет, нет и строк: их некому слышать.
 * <ul>
 * <li>Строки — данные, а не реплики: WTS — товар настоящей лавки по её цене; WTB — настоящая лавка скупки или то, чего охотнику правда не хватает и за что он заплатит в /trade
 * ({@link PhantomTrade}); LFP — соло-охотник, инвайт он примет как обычно; РБ — босс сейчас жив и охотнику по уровню.</li>
 * <li>Ветки — только мозги и в их бюджете: другой охотник отвечает на строку, первый иногда отвечает ему. Мозгов нет — веток нет.</li>
 * <li>Живой написал в трейд — изредка отвечает охотник этого региона, дальше разговор идёт с ним. Тоже только мозги.</li>
 * </ul>
 * Не перебивает: после чужого крика в этом регионе и пока живой с кем-то разговаривает — ждёт. Одна и та же вещь или босс — не чаще раза в час.
 */
public final class PhantomChatFlow
{
	private static final Logger LOGGER = Logger.getLogger(PhantomChatFlow.class.getName());

	private static final long TICK = 20000;
	/** Торговый чат открывается с этого уровня ({@code ChatTrade}). */
	private static final int MIN_LEVEL = 20;
	/** После чужого крика в регионе столько тишины. */
	private static final long AFTER_SHOUT = 45000;
	/** Вещь или босс в чате не чаще, мс. */
	private static final long REPEAT_WINDOW = 3600000;
	/** Дешевле этого в чат не кричат. */
	private static final long MIN_PRICE = 1000;
	/** Лавка дальше этого от площади Гирана — не «в Гиране». */
	private static final int GIRAN_RANGE = 2500;
	private static final int STORE_ITEMS = 2;
	/** Обмен берёт от живого не дороже этой доли кошелька ({@link PhantomTrade}): WTB не обещает больше. */
	private static final double PURSE_SHARE = 0.8;
	/** Множитель {@code HunterChatFlowPerHour} по часам суток, в среднем около единицы. */
	private static final double[] HOUR_FACTOR =
	{
		1, 0.7, 0.5, 0.3, 0.2, 0.2, 0.3, 0.4, 0.6, 0.7, 0.8, 0.9, 1, 1, 1, 1, 1.1, 1.3, 1.5, 1.6, 1.7, 1.6, 1.4, 1.2
	};
	/** РБ охотнику по уровню: от его уровня −5 до +10. */
	private static final int BOSS_BELOW = 5;
	private static final int BOSS_ABOVE = 10;
	/** Строка про РБ в регионе не чаще, мс: боссов много, и без лавок они забили бы весь поток. */
	private static final long RAID_GAP = 1200000;
	/** Живому на первую строку отвечают не чаще, мс. */
	private static final long ANSWER_GAP = 120000;
	/** Охотник и живой ещё разговаривают: столько ждём следующую строку живого, мс. */
	private static final long TALK_WINDOW = 150000;
	/** Шанс (%), что первый ответит на ответ второго. */
	private static final int ANSWER_BACK_CHANCE = 40;

	private enum Kind
	{
		WTS(40),
		WTB(25),
		LFP(20),
		RAID(15);

		final int weight;

		Kind(int weight)
		{
			this.weight = weight;
		}
	}

	private static final String[] WTS_RU =
	{
		"WTS %s",
		"wts %s",
		"продам %s",
		"продаю %s",
		"%s, продам",
	};
	private static final String[] WTS_EN =
	{
		"WTS %s",
		"wts %s",
		"selling %s",
		"%s for sale",
	};
	private static final String[] WTB_RU =
	{
		"WTB %s",
		"wtb %s",
		"куплю %s",
		"кто продаст %s?",
		"%s, куплю, кидайте трейд",
	};
	private static final String[] WTB_EN =
	{
		"WTB %s",
		"wtb %s",
		"buying %s",
		"anyone selling %s?",
		"%s, buying, send trade",
	};
	private static final String[] LFP_RU =
	{
		"лфп %s %d, возьмите в пати",
		"ищу пати, %s %d ур",
		"кто на кач? %s %d, кидайте инвайт",
		"lfp %s %d",
		"го в пати кто-нибудь, %s %d",
	};
	private static final String[] LFP_EN =
	{
		"lfp %s %d",
		"LFP %s %d",
		"%s %d looking for party",
		"anyone hunting? %s %d, send invite",
	};
	private static final String[] RAID_RU =
	{
		"кто на %s?",
		"%s жив, кто идёт?",
		"кто-нибудь идёт на %s?",
		"%s кто-нибудь видел?",
	};
	private static final String[] RAID_EN =
	{
		"anyone for %s?",
		"%s is up, who's going?",
		"who wants %s?",
		"anyone seen %s?",
	};
	private static final Map<String, String> ROLE_EN = Map.of("хил", "healer", "баффер", "buffer", "танк", "tank", "лук", "archer", "маг", "mage", "дд", "dd");

	/** Когда следующая строка: по регионам, где есть живые. */
	private final Map<Integer, Long> _next = new ConcurrentHashMap<>();
	/** Что и когда уже кричали: id вещи, у босса — со знаком минус. */
	private final Map<Integer, Long> _said = new ConcurrentHashMap<>();
	/** Когда в регионе кричали про РБ. */
	private final Map<Integer, Long> _lastRaid = new ConcurrentHashMap<>();
	/** С кем из охотников разговаривает живой: objectId живого — охотник и до какого времени. */
	private final Map<Integer, Talk> _talks = new ConcurrentHashMap<>();
	/** Тексты за час: для счёта повторов в сводке. */
	private final Set<String> _texts = ConcurrentHashMap.newKeySet();
	private final Map<Kind, Integer> _count = new EnumMap<>(Kind.class);
	private int _branches;
	private int _answers;
	private int _repeats;
	private long _summaryAt;
	private volatile long _nextAnswer;

	private record Talk(Player hunter, long until)
	{
	}

	private record Line(Kind kind, Player speaker, String text)
	{
	}

	void start()
	{
		_summaryAt = System.currentTimeMillis() + REPEAT_WINDOW;
		ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
	}

	private void tick()
	{
		try
		{
			final int perHour = PhantomHuntersConfig.HUNTER_CHAT_FLOW_PER_HOUR;
			if ((perHour <= 0) || !tradeChatOn())
			{
				return;
			}

			final long now = System.currentTimeMillis();
			final Set<Integer> regions = new HashSet<>();
			boolean talking = false;
			for (Player player : World.getPlayers())
			{
				if (live(player))
				{
					regions.add(region(player));
					talking |= PhantomChat.getInstance().inConversation(player, now);
				}
			}

			_next.keySet().retainAll(regions);
			for (int region : regions)
			{
				final Long at = _next.get(region);
				if (at == null)
				{
					// Живой только что пришёл: первая строка не сразу.
					_next.put(region, now + (gap(perHour, now) / 2));
				}
				else if (now >= at)
				{
					// Живой разговаривает или в регионе только что кричали — не встревать.
					if (talking || ((now - PhantomChat.getInstance().lastShout(region)) < AFTER_SHOUT))
					{
						_next.put(region, now + AFTER_SHOUT);
					}
					else
					{
						_next.put(region, now + gap(perHour, now));
						speak(region, now);
					}
				}
			}
			summary(now);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e, e);
		}
	}

	/** Промежуток между строками: 0,5–1,5 от среднего, чтобы не шло по метроному. */
	private static long gap(int perHour, long now)
	{
		final long mean = Math.round(3600000L / (perHour * hourFactor(now)));
		return (mean / 2) + Rnd.get((int) Math.min(mean, Integer.MAX_VALUE));
	}

	/** Сила потока по часам: вечером трейд течёт, к утру почти стоит. Только с {@code DailyRhythm}: те же часы и тот же сдвиг, что у охотников. */
	private static double hourFactor(long now)
	{
		return PhantomDayCycle.enabled() ? HOUR_FACTOR[PhantomDayCycle.minuteOfDay(now) / 60] : 1;
	}

	private static boolean tradeChatOn()
	{
		final ChatBroadcastType trade = GeneralConfig.DEFAULT_TRADE_CHAT;
		return (trade == ChatBroadcastType.ON) || (trade == ChatBroadcastType.GLOBAL);
	}

	private static boolean live(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached();
	}

	/** Регион торгового чата; при TradeChat = GLOBAL весь мир — один регион. */
	private static int region(Player player)
	{
		return GeneralConfig.DEFAULT_TRADE_CHAT == ChatBroadcastType.GLOBAL ? -1 : MapRegionData.getInstance().getMapRegionLocId(player);
	}

	/** Кто может написать в трейд этого региона. */
	private static List<Player> speakers(int region)
	{
		final List<Player> out = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) && PhantomHunterManager.getInstance().isHunter(player) && !PhantomManager.getInstance().isSecret(player) && (player.getLevel() >= MIN_LEVEL) && !player.isDead() && !player.isOnEvent() && !player.isInOlympiadMode() && ((region == -1) || (region(player) == region)))
			{
				out.add(player);
			}
		}
		Collections.shuffle(out);
		return out;
	}

	/** Одна строка в трейд региона: первый вид по весам, у которого есть настоящие данные. Данных нет — тишина. */
	private void speak(int region, long now)
	{
		final List<Player> hunters = speakers(region);
		if (hunters.isEmpty())
		{
			return;
		}

		for (Kind kind : order())
		{
			final Line line = compose(kind, hunters, region, now);
			if (line != null)
			{
				final String channel = PhantomChat.getInstance().hunterShout(line.speaker(), line.text());
				count(line, channel);
				branch(line, hunters);
				return;
			}
		}
	}

	private static List<Kind> order()
	{
		final List<Kind> left = new ArrayList<>(List.of(Kind.values()));
		final List<Kind> out = new ArrayList<>();
		while (!left.isEmpty())
		{
			int roll = Rnd.get(left.stream().mapToInt(kind -> kind.weight).sum());
			for (Kind kind : left)
			{
				roll -= kind.weight;
				if (roll < 0)
				{
					out.add(kind);
					left.remove(kind);
					break;
				}
			}
		}
		return out;
	}

	private Line compose(Kind kind, List<Player> hunters, int region, long now)
	{
		switch (kind)
		{
			case WTS:
			{
				return store(hunters, PrivateStoreType.SELL, now);
			}
			case WTB:
			{
				final Line store = store(hunters, PrivateStoreType.BUY, now);
				return store != null ? store : want(hunters, now);
			}
			case LFP:
			{
				return lfp(hunters, now);
			}
			default:
			{
				return raid(hunters, region, now);
			}
		}
	}

	/** WTS и WTB настоящей лавки: самые дорогие вещи, которых в чате ещё не было. */
	private Line store(List<Player> hunters, PrivateStoreType type, long now)
	{
		final boolean sell = type == PrivateStoreType.SELL;
		for (Player hunter : hunters)
		{
			if (hunter.getPrivateStoreType() != type)
			{
				continue;
			}

			final TradeList list = sell ? hunter.getSellList() : hunter.getBuyList();
			final List<TradeItem> goods = new ArrayList<>();
			for (TradeItem item : list.getItems())
			{
				if ((item.getPrice() >= MIN_PRICE) && fresh(item.getItem().getId(), now))
				{
					goods.add(item);
				}
			}
			if (goods.isEmpty())
			{
				continue;
			}

			goods.sort(Comparator.comparingLong((TradeItem item) -> item.getPrice()).reversed());
			final List<String> parts = new ArrayList<>();
			for (TradeItem item : goods.subList(0, Math.min(STORE_ITEMS, goods.size())))
			{
				_said.put(item.getItem().getId(), now);
				parts.add((item.getEnchant() > 0 ? "+" + item.getEnchant() + " " : "") + item.getItem().getName() + " " + price(item.getPrice()));
			}

			final boolean en = en();
			final String[] pool = sell ? (en ? WTS_EN : WTS_RU) : (en ? WTB_EN : WTB_RU);
			final String where = (hunter.calculateDistance2D(PhantomMarket.GIRAN_SQUARE) <= GIRAN_RANGE) && Rnd.nextBoolean() ? (en ? ", shop in Giran" : ", лавка в гиране") : "";
			return new Line(sell ? Kind.WTS : Kind.WTB, hunter, String.format(pool[Rnd.get(pool.length)], String.join(", ", parts)) + where);
		}
		return null;
	}

	/** WTB без лавки: чего охотнику не хватает и за сколько он это купит. Живой продаст ему по /trade — поэтому только с {@code HunterTrade}. */
	private Line want(List<Player> hunters, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_TRADE)
		{
			return null;
		}
		for (Player hunter : hunters)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			if ((template == null) || hunter.isInStoreMode())
			{
				continue;
			}

			final PhantomMarket.Order order = PhantomMarket.buyOrder(hunter, template);
			if ((order == null) || (order.price() < MIN_PRICE) || (order.price() > (hunter.getAdena() * PURSE_SHARE)) || !fresh(order.itemId(), now))
			{
				continue;
			}

			_said.put(order.itemId(), now);
			final ItemTemplate item = ItemData.getInstance().getTemplate(order.itemId());
			final String[] pool = en() ? WTB_EN : WTB_RU;
			return new Line(Kind.WTB, hunter, String.format(pool[Rnd.get(pool.length)], item.getName() + " " + price(order.price())));
		}
		return null;
	}

	private Line lfp(List<Player> hunters, long now)
	{
		for (Player hunter : hunters)
		{
			if (hunter.isInParty() || hunter.isInStoreMode())
			{
				continue;
			}

			final String role = PhantomHunterManager.getInstance().lfpShout(hunter, now);
			if (role != null)
			{
				final boolean en = en();
				final String[] pool = en ? LFP_EN : LFP_RU;
				return new Line(Kind.LFP, hunter, String.format(pool[Rnd.get(pool.length)], en ? ROLE_EN.getOrDefault(role, role) : role, hunter.getLevel()));
			}
		}
		return null;
	}

	private Line raid(List<Player> hunters, int region, long now)
	{
		final Long last = _lastRaid.get(region);
		if ((last != null) && ((now - last) < RAID_GAP))
		{
			return null;
		}

		final List<Npc> alive = new ArrayList<>();
		for (Npc boss : PhantomCompat.raidBosses())
		{
			if (!boss.isDead() && boss.isSpawned() && fresh(-boss.getId(), now))
			{
				alive.add(boss);
			}
		}
		Collections.shuffle(alive);

		for (Player hunter : hunters)
		{
			if (hunter.isInStoreMode())
			{
				continue;
			}
			for (Npc boss : alive)
			{
				if ((boss.getLevel() >= (hunter.getLevel() - BOSS_BELOW)) && (boss.getLevel() <= (hunter.getLevel() + BOSS_ABOVE)))
				{
					_said.put(-boss.getId(), now);
					_lastRaid.put(region, now);
					final String[] pool = en() ? RAID_EN : RAID_RU;
					return new Line(Kind.RAID, hunter, String.format(pool[Rnd.get(pool.length)], boss.getName()));
				}
			}
		}
		return null;
	}

	private boolean fresh(int key, long now)
	{
		final Long at = _said.get(key);
		return (at == null) || ((now - at) >= REPEAT_WINDOW);
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	/** Цена как в чате: 500к, 1.2кк; в английском 500k, 1.2kk. */
	static String price(long adena)
	{
		final String k = en() ? "k" : "к";
		if (adena >= 1000000000L)
		{
			return short1(adena / 1.0E9) + k + k + k;
		}
		if (adena >= 1000000L)
		{
			return short1(adena / 1.0E6) + k + k;
		}
		return adena >= 1000L ? short1(adena / 1000.0) + k : Long.toString(adena);
	}

	private static String short1(double value)
	{
		final long tenths = Math.round(value * 10);
		return (tenths % 10) == 0 ? Long.toString(tenths / 10) : (tenths / 10) + "." + (tenths % 10);
	}

	// ---------- Ветки: только мозги ----------

	/** Другой охотник отвечает на строку; первый иногда отвечает ему. */
	private void branch(Line line, List<Player> hunters)
	{
		if ((Rnd.get(100) >= PhantomHuntersConfig.HUNTER_CHAT_FLOW_BRANCH_CHANCE) || !PhantomChat.brainEnabled())
		{
			return;
		}

		final Player first = line.speaker();
		for (Player second : hunters)
		{
			if ((second != first) && !second.isInStoreMode())
			{
				final String what = "в торговом чате " + first.getName() + " написал: «" + line.text() + "». Ответь ему в тот же чат одной короткой репликой, как игрок: спроси, поторгуйся, подколи или посоветуй. Чего у тебя нет и каких цен не знаешь — не обещай";
				ThreadPool.schedule(() -> PhantomChat.getInstance().tradeReply(first, second, "banter", what, reply ->
				{
					synchronized (_count)
					{
						_branches++;
					}
					if (Rnd.get(100) < ANSWER_BACK_CHANCE)
					{
						ThreadPool.schedule(() -> PhantomChat.getInstance().tradeReply(second, first, "say", reply, null), Rnd.get(8000, 25000));
					}
				}), Rnd.get(8000, 25000));
				return;
			}
		}
	}

	/**
	 * Живой написал в торговый чат ({@code ChatTrade}). Изредка отвечает охотник его региона ({@code HunterChatFlowAnswerChance}), дальше разговор идёт с ним; без мозгов — тишина.
	 * @param speaker кто написал
	 * @param text что написал
	 */
	public void onTrade(Player speaker, String text)
	{
		if ((PhantomHuntersConfig.HUNTER_CHAT_FLOW_PER_HOUR <= 0) || !PhantomChat.brainEnabled() || PhantomManager.getInstance().isPhantom(speaker) || PhantomHunterManager.isLfm(text))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final Talk talk = _talks.get(speaker.getObjectId());
		if ((talk != null) && (now < talk.until()) && !talk.hunter().isDead() && talk.hunter().isOnline())
		{
			answer(speaker, talk.hunter(), "say", text, now);
			return;
		}

		if ((now < _nextAnswer) || (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_CHAT_FLOW_ANSWER_CHANCE))
		{
			return;
		}
		for (Player hunter : speakers(region(speaker)))
		{
			if (!hunter.isInStoreMode() && !hunter.isInCombat())
			{
				_nextAnswer = now + ANSWER_GAP;
				final String what = "в торговом чате " + speaker.getName() + " написал: «" + text + "». Ты это видишь и можешь ответить ему в тот же чат одной короткой репликой, как игрок: по делу, с вопросом или с подколом. Чего у тебя нет и каких цен не знаешь — не обещай. Незачем отвечать — молчи";
				answer(speaker, hunter, "banter", what, now);
				return;
			}
		}
	}

	private void answer(Player speaker, Player hunter, String channel, String what, long now)
	{
		// Живой не ждёт ответа мгновенно: человек тоже дочитывает и набирает.
		_talks.put(speaker.getObjectId(), new Talk(hunter, now + TALK_WINDOW));
		ThreadPool.schedule(() -> PhantomChat.getInstance().tradeReply(speaker, hunter, channel, what, reply ->
		{
			_talks.put(speaker.getObjectId(), new Talk(hunter, System.currentTimeMillis() + TALK_WINDOW));
			synchronized (_count)
			{
				_answers++;
			}
		}), Rnd.get(6000, 20000));
	}

	// ---------- Счёт ----------

	private void count(Line line, String channel)
	{
		synchronized (_count)
		{
			_count.merge(line.kind(), 1, Integer::sum);
			if (!_texts.add(line.text()))
			{
				_repeats++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + line.kind() + " " + line.speaker().getName() + " (" + channel + "): «" + line.text() + "»");
	}

	/** Раз в час: сколько строк, повторов и ответов мозгов (docs/LIVE_SERVER.md, п. 3, «Проверка без живого»). */
	private void summary(long now)
	{
		if (now < _summaryAt)
		{
			return;
		}
		_summaryAt = now + REPEAT_WINDOW;
		_said.values().removeIf(at -> (now - at) >= REPEAT_WINDOW);
		_talks.values().removeIf(talk -> now >= talk.until());

		synchronized (_count)
		{
			final int lines = _count.values().stream().mapToInt(Integer::intValue).sum();
			if ((lines > 0) || (_branches > 0) || (_answers > 0))
			{
				LOGGER.info(getClass().getSimpleName() + ": за час строк " + lines + " " + _count + ", повторов " + _repeats + ", веток " + _branches + ", ответов живым " + _answers + ".");
			}
			_count.clear();
			_texts.clear();
			_branches = 0;
			_answers = 0;
			_repeats = 0;
		}
	}

	public static PhantomChatFlow getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomChatFlow INSTANCE = new PhantomChatFlow();
	}
}
