package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Race;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.instance.Teleporter;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Choice;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Decision;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Facts;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgBuy;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgSell;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/**
 * L2Hub: охотники-фантомы (этап 5) — фермеры из 0006 как настоящие игроки без клиента. Начинают с 1 уровня в стартовой деревне своей расы и качаются сами на настоящем опыте.<br>
 * Бой — родной AutoPlay и AutoUse Mobius ({@link PhantomHunterOutfit} выбирает скиллы); здесь — «макро»-слой, тик 2 с на каждого:
 * <ul>
 * <li>спот по уровню из {@link HuntSpotData} (до 20-го — в родной зоне расы, не больше {@link PhantomHuntersConfig#HUNTERS_PER_SPOT} на спот), телепорт туда, бафы как у городского баффера;</li>
 * <li>поводок к споту, прогулка, если мобов рядом нет, отдых сидя по HP и MP;</li>
 * <li>смерть: через {@link PhantomHuntersConfig#HUNTER_RESPAWN_DELAY} — в город (как «в деревню» у игрока), потом снова на спот;</li>
 * <li>новый уровень: профессия на 20, 40 и 76, новые скиллы; спот перерос — следующий;</li>
 * <li>экономика x1 (раздел 4.2 docs/PHANTOMS.md): кончились сосы или стрелы, полон инвентарь, есть ценный дроп или просто давно не был — в город: хлам NPC, покупки за свою адену; ценное — в лавку в Гиране;</li>
 * <li>пати по крику: «лфм», «нужен хил» в общем, торговом чате и в крике — свободный охотник своей роли и уровня просится в пати и ходит там как напарник;</li>
 * <li>мозги-стратег ({@link PhantomHunterStrategy}): раз в несколько минут охотник на споте спрашивает модель, что делать дальше, и выполняет ответ через те же фазы; нет ответа — правила выше;</li>
 * <li>живой игрок подошёл — охотник иногда заговаривает с ним первым ({@link PhantomInitiative});</li>
 * <li>режим (выключены, всегда, только при живых игроках) и сброс прогресса — из PhantomHunters.ini, который сервер перечитывает на ходу.</li>
 * </ul>
 */
public class PhantomHunterManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHunterManager.class.getName());

	private static final long TICK = 2000;
	private static final long CONTROL_INTERVAL = 10000;
	private static final long STORE_INTERVAL = 300000;
	/** Не дрался и не шёл столько — мобов рядом нет: прогуляться по споту. */
	private static final long IDLE_ROAM = 45000;
	/** Столько прогулок подряд без боя — спот пустой: сменить. */
	private static final int ROAMS_BEFORE_SWITCH = 3;
	private static final int DANGER_RANGE = 900;
	/** Спот ближе этого — к нему ногами (поводок, возврат на кач из пати и города), дальше — телепорт. */
	private static final int WALK_RANGE = 6000;
	/** Шёл к споту ногами столько и не дошёл (застрял в геодате) — телепорт; на дальнюю дорогу — больше, по расстоянию ({@link #walkTime}). */
	private static final long WALK_BACK_TIMEOUT = 90000;
	/** В дороге раз в столько проверяется, приблизился ли охотник; приблизился хотя бы на {@link #WALK_PROGRESS_MIN} — время в пути продлевается (патч 0044). */
	private static final long WALK_PROGRESS_CHECK = 30000;
	private static final int WALK_PROGRESS_MIN = 300;
	/** Скорость для расчёта времени в пути ногами: бег новичка — около 115–130, с запасом. */
	private static final int WALK_SPEED = 90;
	/** Отпустили из пати — спот рядом годится, даже если мобы до стольких уровней выше своего: игрок привёл туда, бафы ещё висят. */
	private static final int NEARBY_LEVEL_UP = 5;
	/** Вошёл в мир или перерос спот — ближайший спот, где мобы не выше стольких уровней (без бафов пати — осторожнее). */
	private static final int SPAWN_LEVEL_UP = 3;
	/** Вне города спот не дальше этого — ногами, а не телепортом (патч 0030). */
	private static final int WALK_TRAVEL = 12000;
	/**
	 * Дальше этого Mobius ведёт игрока по прямой без поиска пути (Creature.moveToLocation: «ткнул мышкой далеко — беги»), и первая скала или стена
	 * останавливает охотника. Поэтому к далёкой точке — отрезками не длиннее этого: на них путь по геодате ищется (патч 0040). Буфер поиска пути у Mobius —
	 * 64 + 2·Δ клеток по 16, не больше 500: на 2500 он почти полон, и промежуточная точка в скале оставляла без пути; 1500 — с запасом (патч 0045).
	 */
	private static final int MOVE_STEP = 1500;
	/** Ближе этого к точке маршрута ({@link PhantomPath}) — дошёл до неё, бежит к следующей. */
	private static final int ROUTE_REACH = 150;
	/** Встал, не дойдя до точки маршрута, — маршрут заново от места, не больше стольких раз за дорогу; дальше — обход поворотами. */
	private static final int ROUTE_REPLANS = 3;
	/** Маршрута нет — отрезки с поворотом, по очереди (патч 0045): обход скалы или стены, как жук. */
	private static final int[] WALK_TURNS =
	{
		0,
		40,
		-40,
		80,
		-80,
		120,
		-120
	};
	/** До этого уровня охотник качается в родной зоне расы, с него — уходит в мир: стартовые зоны не берёт, пока есть другие (патч 0039). */
	private static final int HOME_LEVEL = 15;
	/** Охотники на спотах ближе этого друг к другу — в одном месте: {@code HuntersPerSpot} считается на место, а не на спот (патч 0039). */
	private static final int CROWD_RANGE = 2500;
	/** Вошёл в мир или перерос спот — ногами на один из стольких ближайших свободных, а не всегда на самый ближний (патч 0039). */
	private static final int NEAREST_CHOICE = 3;
	/** Из города: гейткипер не дальше этого; дальше или нет — телепорт с места. */
	private static final int GATEKEEPER_RANGE = 5000;
	/** Ближе этого к гейткиперу — «поговорил» с ним. */
	private static final int GATEKEEPER_REACH = 150;
	/** Бежал к гейткиперу столько и не добежал — телепорт с места. */
	private static final long GATEKEEPER_TIMEOUT = 90000;
	private static final String RESET_VARIABLE = "L2HubHuntersResetId";
	private static final String SELF_TEST_VARIABLE = "L2HubHuntersSelfTestId";
	/** Состояние охотников для лаунчера (от папки game). */
	private static final String STATUS_FILE = "./log/L2HubHunters.json";
	/** Самопроверка UI: окна //hunters и Alt+B, как их увидел бы первый охотник в мире. */
	private static final String UI_TEST_FILE = "./log/L2HubHuntersUi.html";
	/** Обиды охотника до патча 0031: кто и сколько раз его убивал (переменная персонажа). Теперь только читается — перенос в {@link PhantomRelations}. */
	static final String GRUDGE_VARIABLE = "L2HubGrudges";
	/** Часы игры охотника (секунды, переменная персонажа): по ним остывают отношения (патч 0032). */
	private static final String PLAYED_VARIABLE = "L2HubPlayed";
	/** Качались вместе: за каждые столько мс в пати с живым игроком — благодарность, не больше {@link #TOGETHER_MAX} за пати. */
	private static final long TOGETHER_STEP = 1800000;
	private static final int TOGETHER_MAX = 3;
	/** Вместе — если живой игрок из пати не дальше этого (как делится опыт пати). */
	private static final int TOGETHER_RANGE = 1500;
	/** Живой игрок не дальше этого от трупа — охотник ждёт реса дольше. */
	private static final int REVIVE_RANGE = 1500;
	/** Квесты-симуляция: уровень последнего сданного квеста (переменная персонажа). */
	private static final String QUEST_VARIABLE = "L2HubHunterQuest";

	// Экономика.
	/** Между походами в город не меньше этого; по сосам и ценному дропу — не чаще {@link #TOWN_SOFT_COOLDOWN}. */
	private static final long TOWN_COOLDOWN = 60000;
	private static final long TOWN_SOFT_COOLDOWN = 600000;
	/** С прошлой лавки прошло столько — ценный дроп снова повод идти в город. */
	private static final long STORE_COOLDOWN = 1800000;
	/** Площадь Гирана у гейткипера: тут охотники ставят лавки. */
	private static final Location GIRAN_SQUARE = new Location(82698, 148638, -3470);
	private static final int STORE_SPACING = 80;
	private static final int STORE_TRIES = 6;
	/**
	 * Место лавки — на уровне площади: не выше и не ниже её больше чем на столько, вокруг ровно (соседи в {@value #STORE_FLAT_RADIUS} — в пределах
	 * {@value #STORE_FLAT_Z}), от центра площади — прямой путь. Иначе охотник садился на постамент статуи (баг с живого H5 2026-10-23: z −3243 при площади −3464).
	 */
	private static final int STORE_Z_TOLERANCE = 50;
	private static final int STORE_FLAT_RADIUS = 48;
	private static final int STORE_FLAT_Z = 16;
	/** Рынок: не дошёл до продавца за столько — назад на кач. */
	private static final long SHOP_TIMEOUT = 120000;
	/** Ближе этого к продавцу — покупает. */
	private static final int SHOP_REACH = 120;
	/** Продавец ближе этого — к нему пешком; дальше — телепорт на площадь Гирана. */
	private static final int MARKET_WALK = 4000;
	/** Убитый в пати ждёт реса столько, потом уходит из пати в город. */
	private static final long PARTY_DEATH_WAIT = 120000;
	/** Сколько ждёт инвайта, позванный криком. */
	private static final long RECRUIT_WAIT = 180000;
	private static final long LFM_COOLDOWN = 60000;
	private static final int LFM_LEVEL_GAP = 5;

	// Крик «ищу людей»: «лфм», «lfm», «нужен хил», «ищем танка», «го в пати» … и роль, если названа. Короткие слова (ее, дд, тк) — только целиком.
	private static final String END = "(?![\\p{L}])";
	private static final String START = "(^|[^\\p{L}])";
	private static final Pattern LFM = Pattern.compile(START + "(лфм|lfm|lf\\d|нужен|нужна|нужны|нужно|ищу|ищем|need|го в пати|кто в пати|в пати к|возьму|берём|берем)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern WANT_HEALER = Pattern.compile(START + "(хил|heal|биш|bish|епископ|шилен|саппорт|support|(ee|ее|se|бп|bp)" + END + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern WANT_TANK = Pattern.compile(START + "(танк|tank|паладин|(тк|tk|pal|da|дав)" + END + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern WANT_DAMAGE = Pattern.compile(START + "(дамаг|damage|нюкер|nuker|лучник|archer|даггер|дагер|dagger|(дд|dd|dps|маг|мага|магов|mage|лук)" + END + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern WANT_PARTY = Pattern.compile(START + "(пати|party|pt|людей|народ|кто|кого)" + END, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	// Мозги-стратег.
	/** Крик «ищу пати» (роль, уровень). */
	private static final String[] LFP_SHOUTS =
	{
		"лфп %s %d, возьмите в пати",
		"ищу пати, %s %d ур",
		"кто на кач? %s %d, кидайте инвайт",
		"lfp %s %d",
		"го в пати кто-нибудь, %s %d"
	};
	/** Один охотник кричит «лфп» не чаще, все вместе — не чаще: торговый чат не для спама. */
	private static final long LFP_COOLDOWN = 1200000;
	private static final long LFP_GLOBAL_COOLDOWN = 120000;
	/** Столько после крика охотник «ищет пати» (так и говорит в чате). */
	private static final long LFP_WINDOW = 300000;
	/** Самопроверка STRATEGY: столько стратег думает и без живых игроков. */
	private static final long STRATEGY_TEST_DURATION = 600000;
	/** Ответа мозгов нет столько — запрос считается потерянным. */
	private static final long THINK_TIMEOUT = 60000;
	private static final long STRATEGY_LOG_INTERVAL = 60000;
	/** Настроение (патч 0026): после нападения живого игрока или смерти охотник столько настороже. */
	private static final long GUARDED_TIME = 600000;

	/** Заготовки названий лавок (до 29 знаков). */
	private static final String[] STORE_TITLES =
	{
		"Дроп с охоты, недорого",
		"Продам с кача",
		"Шмот и ресы, налетай",
		"Распродажа лута",
		"Дёшево, торг уместен",
		"Всё с фарма, по чесноку",
		"Продаю лишнее",
		"Лут по сходной цене"
	};

	/** Родная зона расы: до {@link #HOME_LEVEL} уровня охотник качается там, если есть подходящий спот. */
	private static final Map<Race, String> HOME_REGIONS = new EnumMap<>(Race.class);
	static
	{
		HOME_REGIONS.put(Race.HUMAN, "TalkingIsland");
		HOME_REGIONS.put(Race.ELF, "ElvenTerritory");
		HOME_REGIONS.put(Race.DARK_ELF, "DarkElfTerritory");
		HOME_REGIONS.put(Race.ORC, "OrcTerritory");
		HOME_REGIONS.put(Race.DWARF, "DwarvenTerritory");
		PhantomCompat.addHomeRegions(HOME_REGIONS);
	}

	private enum Phase
	{
		/** На споте: бой AutoPlay, отдых, поводок. */
		HUNT,
		/** В городе: дела (хлам, покупки), потом обратно. */
		TOWN,
		/** В Гиране на своей лавке. */
		STORE,
		/** Позвали криком: бежит к позвавшему и ждёт инвайта. */
		RECRUIT,
		/** В пати: ведёт {@link PhantomCompanionManager}, как напарника. */
		PARTY
	}

	private static class Hunter
	{
		final PhantomTemplate template;
		HuntSpot spot;
		int level = -1;
		long deadSince;
		boolean needBuff;
		long lastStore;
		long lastActive;
		int roams;
		long retryAt;
		/** Идёт на спот ногами до этого времени; не дошёл — другой спот рядом или телепорт. 0 — не идёт. */
		long walkBackUntil;
		/** Сколько раз подряд не дошёл до спота ногами (застрял в геодате). */
		int walkFails;
		/** Обход в дороге (патч 0045): номер поворота из {@link #WALK_TURNS} и расстояние до спота (в городе — до гейткипера, патч 0046) на прошлом тике; на маршруте — до его точки. */
		int walkTurn;
		double walkLastDistance;
		/** Маршрут дороги ногами ({@link PhantomPath}), точка, к которой бежит, и сколько раз маршрут строился заново. {@code null} — без маршрута, поворотами. */
		List<Location> walkRoute;
		int walkRouteIndex;
		int walkReplans;
		/** Последняя проверка дороги и расстояние до спота тогда (патч 0044). */
		long walkProgressAt;
		double walkProgressDistance;
		/** Из города на далёкий спот: бежит к гейткиперу, у него — телепорт на этот спот. {@code null} — не бежит. */
		HuntSpot gatekeeperSpot;
		Location gatekeeperAt;
		long gatekeeperUntil;
		// Квесты-симуляция: какой взял (уровень, 0 — никакого), когда сделает, сделал — несёт сдавать.
		int questLevel;
		long questUntil;
		boolean questReady;
		// Доход с охоты для лаунчера: прирост адены, пока он на споте, с выхода в мир.
		long earned;
		long lastAdena = -1;
		long earnedSince;
		/** Живой игрок, который на него напал (ПвП): охотник бьёт его, пока тот рядом. */
		volatile int enemyId;
		volatile long enemySeen;
		/** Махач (патч 0037): драку начал он сам — бьёт только того, кто ответил и флагнулся; когда начал. */
		volatile boolean brawlAggressor;
		volatile long brawlSince;
		/** Кровь за кровь (раздел 4.44, {@link PhantomPk}): напал на обидчика и добивает его — не отходит, как в махаче. */
		volatile boolean revenge;
		/** Ответил «сорян» на предъяву за моба (патч 0037) — уйти на другой спот на ближайшем тике. */
		volatile boolean yieldRequested;
		// Экономика и пати.
		volatile Phase phase = Phase.HUNT;
		long phaseUntil;
		boolean choresDone;
		long lastTown;
		long lastStoreSession;
		boolean storeOpen;
		int storeTries;
		/** Лавка скупки (раздел 4.29): что скупает; 0 — лавка продажи. */
		int buyItemId;
		/** Сезонное событие (раздел 4.34): к какому NPC события идёт (objectId), до какого времени; когда был у NPC событий последний раз. */
		int eventNpcId;
		long eventUntil;
		long lastEventVisit;
		/** Для «Мой мир» (activity в L2HubHunters.json): событие, NPC, что сделал; стоит у NPC до этого времени. */
		String eventTitle;
		String eventNpcName;
		String eventResult;
		long eventStandUntil;
		/** Сколько раз вещь (objectId) не продалась в лавке: после двух — NPC. */
		final Map<Integer, Integer> unsold = new ConcurrentHashMap<>();
		volatile int recruiterId;
		volatile long recruitArriveAt;
		boolean recruitArrived;
		// Мозги-стратег.
		long nextThink;
		volatile long thinkingSince;
		/** Ответ модели ждёт выполнения на тике (ответ приходит из потока HTTP). */
		volatile Choice choice;
		volatile Choice lastChoice;
		/** Куда вели бы смены спота — посчитано при вопросе, модель видит названия. */
		HuntSpot upSpot;
		HuntSpot downSpot;
		long spotSince;
		long spotExp;
		long afkUntil;
		long lfpUntil;
		long lastLfp;
		/** Стратег решил сесть в лавку: в городе — в лавку, даже если с прошлой не прошло 30 минут. */
		boolean wantStore;
		/** Рынок (патч 0035): за чем идёт в лавку на площади Гирана и до какого времени; {@code null} — не идёт. */
		PhantomMarket.Offer shop;
		long shopUntil;
		final Deque<Long> deaths = new ArrayDeque<>();
		/** Последняя смерть — для настроения: его читают и потоки чата, а {@link #deaths} — только тик. */
		volatile long lastDeath;
		// Отношения (патч 0032): часы игры, мс; «качались вместе» — сколько мс с каждым живым из пати и сколько раз засчитано.
		volatile long played;
		volatile boolean playedLoaded;
		long playedTick;
		final Map<Integer, Long> together = new ConcurrentHashMap<>();
		final Map<Integer, Integer> togetherAwards = new ConcurrentHashMap<>();
		long togetherTick;

		Hunter(PhantomTemplate template)
		{
			this.template = template;
		}
	}

	private final Map<String, Hunter> _hunters = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lfmCooldowns = new ConcurrentHashMap<>();
	private volatile boolean _wanted;
	/** Живых игроков в игре (раз в 10 с). */
	private volatile int _realPlayers;
	private volatile long _strategyTestUntil;
	private int _strategyTestCount;
	private volatile long _lastLfp;
	private volatile long _lastStrategyLog;
	private volatile long _lastStrategyFailLog;
	private long _lastControl;
	private long _configModified;
	private long _lastRealPlayer;
	private ScheduledFuture<?> _task;

	protected PhantomHunterManager()
	{
	}

	/**
	 * Вызывается из {@link PhantomManager#onServerStart()}.
	 */
	public synchronized void start()
	{
		final File file = new File(PhantomHuntersConfig.PHANTOM_HUNTERS_CONFIG_FILE);
		_configModified = file.lastModified();
		for (PhantomTemplate template : PhantomData.getInstance().getHunters())
		{
			_hunters.put(template.getName().toLowerCase(), new Hunter(template));
		}

		if (_hunters.isEmpty())
		{
			LOGGER.info(getClass().getSimpleName() + ": No hunters in L2HubPhantoms.xml.");
			return;
		}

		HuntSpotData.getInstance();
		PhantomRelations.getInstance().start();
		LOGGER.info(getClass().getSimpleName() + ": " + _hunters.size() + " hunters, mode " + PhantomHuntersConfig.HUNTERS_MODE + ".");
		if ((_task == null) || _task.isDone())
		{
			_task = ThreadPool.scheduleAtFixedRate(this::tick, 5000, TICK);
		}
	}

	/**
	 * @param template охотник
	 * @return чем он сейчас занят — для мозгов, от его лица
	 */
	public String stateForBrain(PhantomTemplate template)
	{
		final Hunter hunter = _hunters.get(template.getName().toLowerCase());
		final Player player = PhantomManager.getInstance().getPhantom(template);
		if ((hunter == null) || (player == null))
		{
			return "сейчас не в игре";
		}

		if (player.isDead())
		{
			if (System.currentTimeMillis() < PhantomRelations.getInstance().reviveCastUntil(player))
			{
				return "тебя убили, лежишь — и тебя как раз ресают";
			}
			return hunter.phase != Phase.PARTY && (PhantomHuntersConfig.HUNTER_REVIVE_WAIT > 0) && reviverNear(player) ? "тебя убили, лежишь и ждёшь реса — рядом живой игрок, может реснёт" : "тебя только что убили, лежишь и скоро вернёшься в город";
		}

		final String wallet = "; в кармане " + money(player.getAdena()) + " адены";
		switch (hunter.phase)
		{
			case TOWN:
			{
				final long afk = hunter.afkUntil - System.currentTimeMillis();
				if (afk > 0)
				{
					return "отдыхаешь в городе " + MapRegionData.getInstance().getClosestTownName(player) + ", сидишь афк ещё минут " + Math.max(1, afk / 60000) + ", потом снова на кач" + wallet;
				}
				return "зашёл в город " + MapRegionData.getInstance().getClosestTownName(player) + ": сдать хлам, купить сосы и шмот, потом обратно на кач" + wallet;
			}
			case STORE:
			{
				if (hunter.buyItemId > 0)
				{
					final TradeList list = player.getBuyList();
					final TradeItem wanted = list.getItemCount() > 0 ? list.getItems().iterator().next() : null;
					return "сидишь в Гиране в лавке скупки: копишь на " + (wanted != null ? wanted.getItem().getName() + " и даёшь за неё " + money(wanted.getPrice()) + " — дешевле, чем в магазине" : "шмот") + wallet;
				}
				return "сидишь на своей лавке в Гиране, продаёшь дроп с охоты" + wallet;
			}
			case RECRUIT:
			{
				final Player recruiter = World.getPlayer(hunter.recruiterId);
				return "идёшь в пати к " + (recruiter != null ? recruiter.getName() : "тому, кто звал в чате") + ", ждёшь инвайта" + wallet;
			}
			default:
			{
				final String raid = PhantomRaid.getInstance().raidOf(player);
				if (raid != null)
				{
					return "ведёшь рейд охотников на РБ " + raid + ": собрал пати, ждёте у босса остальных или уже бьёте его" + wallet;
				}
				final String where = hunter.spot != null ? "качаешься на споте " + hunter.spot.name + " (мобы " + hunter.spot.min + "-" + hunter.spot.max + " ур.)" : "только вышел в игру и выбираешь, где качаться";
				final long now = System.currentTimeMillis();
				final String lfp = now < hunter.lfpUntil ? "; ищешь пати — только что кричал в чат, ждёшь инвайта" : "; соло, ни с кем не ходишь";
				final Choice last = hunter.lastChoice;
				final String thought = (last != null) && !last.thought.isEmpty() && ((now - last.at) < 900000) ? "; недавно думал: «" + last.thought + "»" : "";
				return where + (player.isSitting() ? ", сейчас сидишь отдыхаешь" : "") + lfp + wallet + thought;
			}
		}
	}

	/**
	 * Настроение охотника (патч 0026) — из состояния, без модели: для «заговорить первым», «встрять» и мозгов.
	 * @param player фантом
	 * @return настроение; {@code null} — не охотник или настроение выключено ({@link PhantomHuntersConfig#HUNTER_MOOD})
	 */
	public PhantomMood mood(Player player)
	{
		final Hunter hunter = player != null ? _hunters.get(player.getName().toLowerCase()) : null;
		return hunter != null ? mood(hunter, player, System.currentTimeMillis()) : null;
	}

	private static PhantomMood mood(Hunter hunter, Player player, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_MOOD || player.isDead())
		{
			return null;
		}

		final Phase phase = hunter.phase;
		if ((player.getCurrentHp() < (player.getMaxHp() * 0.35)) || (player.getCurrentMp() < (player.getMaxMp() * 0.2)) || ((phase == Phase.HUNT) && player.isSitting()))
		{
			return PhantomMood.TIRED;
		}
		if ((hunter.enemyId != 0) || ((now - hunter.enemySeen) < GUARDED_TIME) || ((now - hunter.lastDeath) < GUARDED_TIME))
		{
			return PhantomMood.GUARDED;
		}
		final boolean afk = (phase == Phase.TOWN) && (hunter.afkUntil > now);
		if ((phase == Phase.RECRUIT) || (phase == Phase.PARTY) || ((phase == Phase.TOWN) && !afk) || (hunter.walkBackUntil > now))
		{
			return PhantomMood.FOCUSED;
		}
		if (afk || ((phase == Phase.HUNT) && (hunter.roams > 0)))
		{
			return PhantomMood.RESTLESS;
		}
		return hunter.template.getSocial() >= 1.5 ? PhantomMood.SOCIABLE : PhantomMood.CALM;
	}

	static String money(long adena)
	{
		if (adena >= 1000000)
		{
			return String.format(Locale.ROOT, "%.1fкк", adena / 1000000.0);
		}
		return adena >= 1000 ? (adena / 1000) + "к" : String.valueOf(adena);
	}

	// ---------- Тик ----------

	private void tick()
	{
		final long now = System.currentTimeMillis();
		if ((now - _lastControl) >= CONTROL_INTERVAL)
		{
			_lastControl = now;
			try
			{
				control(now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": control failed.", e);
			}
		}

		boolean spawnedOne = false;
		for (Hunter hunter : _hunters.values())
		{
			try
			{
				final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
				if (player == null)
				{
					// Входят по одному за тик, чтобы не грузить сервер разом.
					if (_wanted && !spawnedOne && (now >= hunter.retryAt))
					{
						spawnedOne = true;
						spawn(hunter, now);
					}
					continue;
				}

				if (!_wanted)
				{
					if (hunter.phase == Phase.PARTY)
					{
						PhantomCompanionManager.getInstance().release(hunter.template, false);
					}
					closeStore(hunter, player);
					PhantomManager.getInstance().despawnHunter(hunter.template);
					continue;
				}

				think(hunter, player, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + hunter.template.getName() + " tick failed.", e);
			}
		}
	}

	/**
	 * Раз в 10 с: перечитать PhantomHunters.ini, если он изменился, выполнить сброс, решить, нужны ли охотники в мире.
	 */
	private void control(long now)
	{
		final File file = new File(PhantomHuntersConfig.PHANTOM_HUNTERS_CONFIG_FILE);
		if (file.lastModified() != _configModified)
		{
			_configModified = file.lastModified();
			PhantomHuntersConfig.load();
			LOGGER.info(getClass().getSimpleName() + ": PhantomHunters.ini reloaded, mode " + PhantomHuntersConfig.HUNTERS_MODE + ".");
		}

		reset();
		selfTest();
		writeStatus(now);

		int realPlayers = 0;
		for (Player player : World.getPlayers())
		{
			if (!PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached())
			{
				realPlayers++;
			}
		}
		_realPlayers = realPlayers;
		final boolean realPlayer = realPlayers > 0;
		if (realPlayer)
		{
			_lastRealPlayer = now;
		}

		// Эпики малого мира (раздел 4.43): ослабить живых эпиков — и при выключенных охотниках, это свойство мира.
		PhantomEpics.getInstance().control();
		// Рейды охотников на РБ (раздел 4.32): лидер погиб — провал; пора — собрать новый из тех, кто на каче.
		PhantomRaid.getInstance().check(now);
		if (_wanted)
		{
			final List<Player> hunting = new ArrayList<>();
			for (Hunter hunter : _hunters.values())
			{
				final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
				// На каче (не в дороге) — кто угодно; в городе и в лавке — тоже: позовут в рейд — свернётся и пойдёт, как живой (лидера PhantomRaid берёт только с кача).
				final boolean onSpot = (hunter.phase == Phase.HUNT) && (hunter.walkBackUntil == 0) && (hunter.gatekeeperSpot == null) && (hunter.spot != null);
				final boolean idle = ((hunter.phase == Phase.TOWN) && (hunter.shop == null) && (hunter.afkUntil <= now)) || (hunter.phase == Phase.STORE);
				if ((player != null) && (onSpot || idle) && !player.isDead() && !player.isInParty() && (hunter.enemyId == 0) && !player.isTeleporting() && !PhantomEvents.getInstance().isBusy(player) && !PhantomOlympiad.getInstance().isBusy(player) && !PhantomDuel.getInstance().isBusy(player))
				{
					hunting.add(player);
				}
			}
			PhantomRaid.getInstance().control(hunting, now);
			// PvP-ивенты (раздел 4.33): открыта регистрация — записываются охотники с кача.
			final List<Player> onSpots = new ArrayList<>();
			for (Player player : hunting)
			{
				if (isHunting(player) && !player.isInParty())
				{
					onSpots.add(player);
				}
			}
			PhantomEvents.getInstance().control(onSpots, now);
			// Олимпиада (раздел 4.39): дворянство с 76-го, запись в часы соревнований.
			PhantomOlympiad.getInstance().control(onSpots, now);
			// Дуэли (раздел 4.40): охотник вызывает соседа по споту.
			PhantomDuel.getInstance().control(onSpots, now);
			// Семь печатей (раздел 4.42): смена периода — в летопись.
			PhantomSevenSigns.getInstance().control(now);
			// ПК (раздел 4.44): ПК вокруг — приоритетная цель; кровь за кровь.
			PhantomPk.getInstance().control(onSpots, now);
			// Танатос (раздел 4.45): часть охотников-фантомов — охотники в мире, и он тоже.
			PhantomThanatos.getInstance().control(now);
			// «Разум 2.0»: кто из охотников рядом с живым игроком.
			final List<Player> online = new ArrayList<>();
			for (Hunter hunter : _hunters.values())
			{
				final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
				if (player != null)
				{
					online.add(player);
				}
			}
			PhantomFocus.getInstance().update(online, now);
		}
		else
		{
			PhantomThanatos.getInstance().disable();
		}

		switch (PhantomHuntersConfig.HUNTERS_MODE)
		{
			case "OFF":
			{
				_wanted = false;
				break;
			}
			case "WITH_PLAYERS":
			{
				_wanted = realPlayer || ((_lastRealPlayer > 0) && ((now - _lastRealPlayer) < (PhantomHuntersConfig.HUNTERS_IDLE_TIMEOUT * 1000L)));
				break;
			}
			default:
			{
				_wanted = true;
				break;
			}
		}
	}

	/**
	 * Состояние охотников для лаунчера L2Hub ({@value #STATUS_FILE}, раз в 10 с): уровень, фаза, адена, где качается, лавка с товаром, с кем в пати,
	 * занятие сверх фазы ({@code activity}: рейд, PvP-ивент, сезонное событие — только пока идёт).
	 */
	private void writeStatus(long now)
	{
		final StringBuilder sb = new StringBuilder(8192);
		sb.append("{\"time\":").append(now).append(',');
		FakePlayerBrain.field(sb, "mode", PhantomHuntersConfig.HUNTERS_MODE).append(',');
		sb.append("\"wanted\":").append(_wanted).append(",\"hunters\":[");
		boolean first = true;
		for (Hunter hunter : _hunters.values())
		{
			final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
			sb.append(first ? "" : ",").append('{');
			first = false;
			FakePlayerBrain.field(sb, "name", hunter.template.getName()).append(',');
			sb.append("\"online\":").append(player != null);
			if (player != null)
			{
				final String phase = player.isDead() ? "DEAD" : hunter.phase.name();
				sb.append(",\"level\":").append(player.getLevel()).append(",\"classId\":").append(player.getPlayerClass().getId()).append(",\"adena\":").append(player.getAdena());
				// Координаты для карты мира в лаунчере.
				sb.append(",\"x\":").append(player.getX()).append(",\"y\":").append(player.getY()).append(",\"z\":").append(player.getZ()).append(",\"heading\":").append(player.getHeading()).append(',');
				FakePlayerBrain.field(sb, "phase", phase).append(',');
				FakePlayerBrain.field(sb, "spot", hunter.spot != null ? hunter.spot.name : "").append(',');
				FakePlayerBrain.field(sb, "town", MapRegionData.getInstance().getClosestTownName(player)).append(',');
				FakePlayerBrain.field(sb, "party", player.isInParty() ? player.getParty().getLeader().getName() : "");
				if (player.isInParty())
				{
					// Состав пати для «Мой мир» (патч 0042): лидер первым; имена игроков — латиница и цифры, экранировать нечего.
					sb.append(",\"partyMembers\":[\"").append(player.getParty().getLeader().getName()).append('"');
					for (Player member : player.getParty().getMembers())
					{
						if (member != player.getParty().getLeader())
						{
							sb.append(",\"").append(member.getName()).append('"');
						}
					}
					sb.append(']');
				}
				final PhantomMood mood = mood(hunter, player, now);
				if (mood != null)
				{
					sb.append(',');
					FakePlayerBrain.field(sb, "mood", mood.getKey());
				}
				sb.append(",\"relations\":");
				PhantomRelations.getInstance().top(sb, player, 5);
				final Choice last = hunter.lastChoice;
				if (last != null)
				{
					sb.append(",\"strategy\":{\"at\":").append(last.at).append(',');
					FakePlayerBrain.field(sb, "decision", last.toString()).append(',');
					FakePlayerBrain.field(sb, "thought", last.thought).append('}');
				}
				sb.append(",\"nextThink\":").append(hunter.nextThink);
				// «Разум 2.0»: охотник рядом с живым игроком — окно покажет, кто думает расширенно.
				final String focus = PhantomFocus.getInstance().of(player);
				if (focus != null)
				{
					sb.append(',');
					FakePlayerBrain.field(sb, "focus", focus);
				}
				// Занятие сверх фазы — PvP-ивент, рейд на РБ, сезонное событие; только пока идёт (разделы 4.32–4.34).
				if (!PhantomEvents.getInstance().activity(sb, player) && !PhantomRaid.getInstance().activity(sb, player))
				{
					seasonalActivity(sb, hunter, now);
				}
				sb.append(",\"earnedPerHour\":").append(hunter.earnedSince > 0 ? (hunter.earned * 3600000L) / Math.max(600000L, now - hunter.earnedSince) : 0);
				if (hunter.questLevel > 0)
				{
					sb.append(",\"quest\":{\"level\":").append(hunter.questLevel).append(",\"until\":").append(hunter.questUntil).append(",\"ready\":").append(hunter.questReady).append('}');
				}
				final long due = PhantomHunterOutfit.professionDue(player);
				if (due > 0)
				{
					sb.append(",\"professionDue\":").append(due);
				}
				if (hunter.storeOpen && ((player.getPrivateStoreType() == PrivateStoreType.SELL) || (player.getPrivateStoreType() == PrivateStoreType.BUY)))
				{
					// kind: sell — лавка продажи, buy — скупка (раздел 4.29); items — товары или что скупает.
					final boolean buy = player.getPrivateStoreType() == PrivateStoreType.BUY;
					final TradeList list = buy ? player.getBuyList() : player.getSellList();
					sb.append(",\"store\":{\"kind\":\"").append(buy ? "buy" : "sell").append("\",\"until\":").append(hunter.phaseUntil).append(',');
					FakePlayerBrain.field(sb, "title", list.getTitle()).append(",\"items\":[");
					boolean firstItem = true;
					for (TradeItem trade : list.getItems())
					{
						sb.append(firstItem ? "" : ",").append('{');
						firstItem = false;
						FakePlayerBrain.field(sb, "name", trade.getItem().getName()).append(',');
						sb.append("\"count\":").append(trade.getCount()).append(",\"price\":").append(trade.getPrice()).append('}');
					}
					sb.append("]}");
				}
			}
			sb.append('}');
		}
		// Танатос (раздел 4.45) — последняя строка, только отметка и уровень.
		PhantomThanatos.getInstance().status(sb, first);
		sb.append(']');

		// Для карты мира в лаунчере: живые игроки онлайн (не фантомы) и напарники из .buddy с владельцем.
		sb.append(",\"players\":[");
		first = true;
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) || !player.isOnline())
			{
				continue;
			}
			sb.append(first ? "" : ",").append('{');
			first = false;
			FakePlayerBrain.field(sb, "name", player.getName()).append(',');
			sb.append("\"level\":").append(player.getLevel()).append(",\"classId\":").append(player.getPlayerClass().getId());
			sb.append(",\"x\":").append(player.getX()).append(",\"y\":").append(player.getY()).append(",\"z\":").append(player.getZ()).append('}');
		}
		sb.append("],\"companions\":");
		PhantomCompanionManager.getInstance().mapStatus(sb);
		sb.append('}');

		try
		{
			final Path file = Path.of(STATUS_FILE);
			final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, sb, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.FINE, getClass().getSimpleName() + ": could not write " + STATUS_FILE + ".", e);
		}
	}

	/**
	 * Сброс прогресса: когда HuntersResetId отличается от выполненного в прошлый раз — удалить названных (или ALL) охотников из базы.
	 */
	private void reset()
	{
		final int done = GlobalVariablesManager.getInstance().getInt(RESET_VARIABLE, 0);
		if (PhantomHuntersConfig.HUNTERS_RESET_ID == done)
		{
			return;
		}

		GlobalVariablesManager.getInstance().set(RESET_VARIABLE, PhantomHuntersConfig.HUNTERS_RESET_ID);
		GlobalVariablesManager.getInstance().storeMe();
		final String names = PhantomHuntersConfig.HUNTERS_RESET;
		if (names.isEmpty())
		{
			return;
		}

		final boolean all = names.equalsIgnoreCase("ALL");
		final List<String> list = new ArrayList<>();
		for (String name : names.split(","))
		{
			if (!name.isBlank())
			{
				list.add(name.strip().toLowerCase());
			}
		}

		int count = 0;
		for (Hunter hunter : _hunters.values())
		{
			if (all || list.contains(hunter.template.getName().toLowerCase()))
			{
				if (hunter.phase == Phase.PARTY)
				{
					PhantomCompanionManager.getInstance().release(hunter.template, false);
				}
				final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
				if (player != null)
				{
					closeStore(hunter, player);
				}
				PhantomManager.getInstance().resetHunter(hunter.template);
				hunter.spot = null;
				hunter.level = -1;
				hunter.deadSince = 0;
				hunter.phase = Phase.HUNT;
				hunter.unsold.clear();
				hunter.choice = null;
				hunter.lastChoice = null;
				hunter.deaths.clear();
				count++;
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": Reset " + count + " hunters (" + names + ").");
	}

	private void spawn(Hunter hunter, long now)
	{
		final Player player = PhantomManager.getInstance().spawnHunter(hunter.template);
		if (player == null)
		{
			hunter.retryAt = now + 300000;
			return;
		}

		hunter.level = player.getLevel();
		hunter.spot = null;
		hunter.deadSince = 0;
		hunter.needBuff = true;
		hunter.enemyId = 0;
		hunter.phase = Phase.HUNT;
		hunter.storeOpen = false;
		// ПвП: кто ударил и кто убил — события приходят от сервера (асинхронно).
		player.addListener(new ConsumerEventListener(player, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamaged(hunter, player, event.getAttacker()), this));
		player.addListener(new ConsumerEventListener(player, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onKilled(hunter, player, event.getAttacker()), this));
		hunter.lastStore = now;
		hunter.lastActive = now;
		hunter.lastTown = now;
		hunter.roams = 0;
		// Стратег: первое решение — в пределах обычного промежутка, у каждого своё время, чтобы не спрашивали все разом.
		hunter.nextThink = now + Rnd.get(60000L, Math.max(60000L, PhantomHunterStrategy.nextDelay()));
		hunter.thinkingSince = 0;
		hunter.choice = null;
		hunter.afkUntil = 0;
		hunter.lfpUntil = 0;
		hunter.wantStore = false;
		hunter.questLevel = 0;
		hunter.questReady = false;
		hunter.earned = 0;
		hunter.lastAdena = player.getAdena();
		hunter.earnedSince = now;
	}

	private void think(Hunter hunter, Player player, long now)
	{
		played(hunter, player, now);
		// PvP-ивент (раздел 4.33): на арене охотника ведёт бой ивента, убитого поднимает сам ивент — до обработки смерти; кончился — назад на кач.
		final PhantomEvents.Drive event = PhantomEvents.getInstance().drive(player, hunter.template, now);
		if (event == PhantomEvents.Drive.FIGHTS)
		{
			hunter.lastActive = now;
			return;
		}
		// Олимпиада (раздел 4.39): на стадионе — бой матча, матч ведёт Mobius.
		if (PhantomOlympiad.getInstance().drive(player, hunter.template, now) == PhantomEvents.Drive.FIGHTS)
		{
			hunter.lastActive = now;
			return;
		}
		// Дуэль (раздел 4.40): вызов принят — ждёт готовности и дерётся по правилам дуэли Mobius.
		if (PhantomDuel.getInstance().drive(player, hunter.template, now))
		{
			hunter.lastActive = now;
			return;
		}
		if ((event == PhantomEvents.Drive.DONE) && !player.isDead())
		{
			backToHunt(hunter, player);
			return;
		}
		// Пати охотников (патч 0036): лидер ушёл с кача, погиб, время вышло — распустить.
		PhantomHunterParty.getInstance().check(player, hunter.phase == Phase.HUNT, now);
		if (player.isDead())
		{
			died(hunter, player, now);
			return;
		}
		hunter.deadSince = 0;

		if (player.isTeleporting())
		{
			return;
		}

		if (player.getLevel() != hunter.level)
		{
			leveled(hunter, player);
		}

		if ((now - hunter.lastStore) > STORE_INTERVAL)
		{
			hunter.lastStore = now;
			player.getVariables().set(PLAYED_VARIABLE, hunter.played / 1000);
			player.storeMe();
		}

		// Доход с охоты: прирост адены, пока он на споте (город, лавка и сдача квеста — не в счёт).
		final long adena = player.getAdena();
		if ((hunter.phase == Phase.HUNT) && (hunter.lastAdena >= 0) && (adena > hunter.lastAdena))
		{
			hunter.earned += adena - hunter.lastAdena;
		}
		hunter.lastAdena = adena;

		// Решение стратега пришло, а охотник уже занят другим (город по правилам, пати, лавка) — забыть.
		final Choice pending = hunter.choice;
		if ((pending != null) && (hunter.phase != Phase.HUNT))
		{
			hunter.choice = null;
			LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " drops " + pending + ", busy (" + hunter.phase + ").");
		}

		// Заговорить первым с живым игроком рядом (патч 0025): на каче, в городе и в лавке.
		if ((hunter.phase == Phase.HUNT) || (hunter.phase == Phase.TOWN) || (hunter.phase == Phase.STORE))
		{
			PhantomInitiative.getInstance().look(player, hunter.template, hunter.enemyId != 0, _realPlayers > 0, now);
			// Перепалки и приветствия с другими охотниками при живом зрителе (патч 0033).
			PhantomBanter.getInstance().tick(player, hunter.template, now);
		}

		// Ведёт рейд на РБ (раздел 4.32): правила кача не трогают; рейд кончился — назад на свой спот.
		if (hunter.phase == Phase.HUNT)
		{
			final PhantomRaid.Drive drive = PhantomRaid.getInstance().drive(player, now);
			if (drive == PhantomRaid.Drive.LEADS)
			{
				hunter.lastActive = now;
				return;
			}
			if (drive == PhantomRaid.Drive.DONE)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " is back from the raid.");
				backToHunt(hunter, player);
				return;
			}
			// Призыватель (раздел 4.37): на споте держит слугу, слуга бьёт его цель.
			if ((hunter.walkBackUntil == 0) && (hunter.gatekeeperSpot == null) && (hunter.spot != null) && PhantomSummoner.tick(player, now))
			{
				hunter.lastActive = now;
				return;
			}
			// Тактика по классу (раздел 4.38): танк перетягивает агро с товарищей, лучник и маг держат дистанцию.
			if ((hunter.walkBackUntil == 0) && (hunter.gatekeeperSpot == null) && (hunter.spot != null) && PhantomTactics.tick(player, hunter.template, now))
			{
				hunter.lastActive = now;
				return;
			}
		}

		switch (hunter.phase)
		{
			case PARTY:
			{
				// Распустили, выгнали, лидер ушёл — назад на кач.
				if (!PhantomCompanionManager.getInstance().isCompanion(hunter.template))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " left the party, back to hunting.");
					backToHuntNearby(hunter, player);
					return;
				}
				// Тактика по классу и в чужой пати (раздел 4.38): танк держит агро, лучник и маг — дистанцию.
				if (PhantomTactics.tick(player, hunter.template, now))
				{
					hunter.lastActive = now;
					return;
				}
				together(hunter, player, now);
				return;
			}
			case RECRUIT:
			{
				recruit(hunter, player, now);
				return;
			}
			case TOWN:
			{
				town(hunter, player, now);
				return;
			}
			case STORE:
			{
				store(hunter, player, now);
				return;
			}
			default:
			{
				break;
			}
		}

		// Из города бежит к гейткиперу (патч 0027): у него — телепорт на спот.
		if (hunter.gatekeeperSpot != null)
		{
			gatekeeper(hunter, player, now);
			return;
		}

		if ((hunter.spot == null) || outgrown(hunter.spot, player.getLevel()))
		{
			// Вошёл в мир или перерос спот (патч 0030): ближайший подходящий — ногами; рядом нет — выбрать и телепорт, как раньше.
			final HuntSpot near = nearestFreeSpot(player);
			if (near != null)
			{
				walkTo(hunter, player, near);
				return;
			}
			goToSpot(hunter, player, null);
			return;
		}

		if (hunter.needBuff)
		{
			hunter.needBuff = false;
			buff(player);
		}

		// ПвП: пока обидчик рядом — драка с ним, без отдыха и поводка.
		if ((hunter.enemyId != 0) && fight(hunter, player, now))
		{
			hunter.lastActive = now;
			return;
		}
		noticeGrudges(player);

		// Соседи-охотники (патч 0033): «отжал моба»; хилер хилит и ресает соседа — пока помогает, остальное ждёт.
		if (PhantomNeighbors.getInstance().tick(player, hunter.template, now))
		{
			hunter.lastActive = now;
			return;
		}

		// «Сорян» на предъяву за моба (патч 0037): уйти на другой спот.
		if (hunter.yieldRequested)
		{
			yieldNow(hunter, player);
			return;
		}

		// Пати охотников по отношениям (патч 0036): собрать или позвать ещё одного; лидер тоже копит «качались вместе».
		PhantomHunterParty.getInstance().tick(player, hunter.template, now);
		if (PhantomHunterParty.getInstance().isLeader(player))
		{
			together(hunter, player, now);
		}

		// Квест сделан — в город сдавать.
		if (quest(hunter, player, now))
		{
			return;
		}

		// Мозги-стратег: решение модели выполняется тут, когда можно; нет решения — правила, как раньше.
		if (strategy(hunter, player, now))
		{
			return;
		}

		if (!player.isInCombat() && !player.isAttackingNow() && !player.isCastingNow() && needsTown(hunter, player, now))
		{
			goToTown(hunter, player, now);
			return;
		}

		if (rest(player))
		{
			hunter.lastActive = now;
			// Сел отдохнуть в дороге — время в пути стоит: отдых не застревание (Pupsik на Interlude 2026-10-20 «застрял» в 2158 от спота).
			if (hunter.walkBackUntil != 0)
			{
				hunter.walkBackUntil += TICK;
				hunter.walkProgressAt = now;
			}
			return;
		}

		// В дороге на спот — бежит, а не охотится по пути (патч 0043): AutoPlay цеплял мобов вокруг и гонялся за ними, и охотник,
		// вошедший в мир посреди чужого поля, не уходил с места. Бьёт, только если бьют его.
		if ((hunter.walkBackUntil > now) && !inDanger(player))
		{
			if (player.isAutoPlaying())
			{
				stop(player);
			}
			leash(hunter, player, now);
			return;
		}

		if (!player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(player);
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		// Маг под ударами вплотную или без маны — отмахивается оружием (патч 0028).
		PhantomHunterOutfit.syncMelee(player);

		if (player.isInCombat() || player.isAttackingNow() || player.isCastingNow() || player.isMoving())
		{
			hunter.lastActive = now;
			hunter.roams = 0;
		}

		leash(hunter, player, now);
	}

	// ---------- Смерть ----------

	private void died(Hunter hunter, Player player, long now)
	{
		if (hunter.deadSince == 0)
		{
			hunter.deadSince = now;
			hunter.deaths.addLast(now);
			hunter.lastDeath = now;
			hunter.choice = null;
			endFight(hunter, player);
			if (hunter.phase != Phase.PARTY)
			{
				stop(player);
			}
			return;
		}

		// Кто-то предложил рес — соглашаемся; «реснул» засчитывается тому, кто кастовал (патч 0032).
		if (player.isReviveRequested())
		{
			player.reviveAnswer(1);
			hunter.deadSince = 0;
			PhantomRelations.getInstance().revived(player);
			return;
		}

		// На него кастуют рес — ждать конца каста, а не улетать в город посреди него (патч 0032).
		if (now < PhantomRelations.getInstance().reviveCastUntil(player))
		{
			return;
		}

		// В пати ждёт реса дольше; рядом живой игрок, не враг, — тоже: вдруг реснёт (патч 0032). Не дождался — в город.
		// Лидер рейда (раздел 4.32) ждёт реса, как в пати: в рейде есть хилер, улетит в город — рейд провален.
		long wait = (hunter.phase == Phase.PARTY) || (PhantomRaid.getInstance().raidOf(player) != null) ? PARTY_DEATH_WAIT : (PhantomHuntersConfig.HUNTER_RESPAWN_DELAY * 1000L);
		if ((hunter.phase != Phase.PARTY) && (PhantomHuntersConfig.HUNTER_REVIVE_WAIT > 0) && reviverNear(player))
		{
			wait = Math.max(wait, PhantomHuntersConfig.HUNTER_REVIVE_WAIT * 1000L);
		}
		if ((now - hunter.deadSince) < wait)
		{
			return;
		}
		PhantomRelations.getInstance().clearRevive(player);

		if (hunter.phase == Phase.PARTY)
		{
			PhantomCompanionManager.getInstance().release(hunter.template, true);
		}

		// Как «в деревню» у игрока (RequestRestartPoint): телепорт в ближайший город с ресом по прилёту, там — дела.
		hunter.deadSince = 0;
		hunter.spot = null;
		hunter.needBuff = true;
		final Location town = MapRegionData.getInstance().getTeleToLocation(player, TeleportWhereType.TOWN);
		PhantomCompat.setInstanceId(player, 0);
		player.setIsPendingRevive(true);
		player.teleToLocation(town, true);
		enterTown(hunter, now);
	}

	// ---------- Уровень и спот ----------

	private void leveled(Hunter hunter, Player player)
	{
		final boolean up = (hunter.level > 0) && (player.getLevel() > hunter.level);
		hunter.level = player.getLevel();
		PhantomHunterOutfit.update(player, hunter.template);
		player.storeMe();
		if (up)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " reached level " + player.getLevel() + " (" + player.getPlayerClass().name() + ", " + player.getAdena() + " adena).");
		}
	}

	/**
	 * Спот перерос (мобы слишком слабые) или после смерти с потерей опыта стал опасен.
	 */
	private static boolean outgrown(HuntSpot spot, int level)
	{
		return (level > (spot.max + 3)) || (level < (spot.min - 4));
	}

	/**
	 * Новый спот по уровню: мобы на уровень-три ниже и до своего; до {@link #HOME_LEVEL} — в родной зоне расы, если там есть, потом — в мире вне стартовых зон;
	 * не больше HuntersPerSpot охотников на место.
	 */
	private void goToSpot(Hunter hunter, Player player, HuntSpot avoid)
	{
		final HuntSpot spot = pickSpot(player, avoid);
		if (spot == null)
		{
			return;
		}
		moveToSpot(hunter, player, spot);
	}

	/**
	 * Телепорт на этот спот (у гейткипера, с платой с 40-го уровня); в городе — сначала бегом к гейткиперу.
	 */
	private static void moveToSpot(Hunter hunter, Player player, HuntSpot spot)
	{
		hunter.spot = spot;
		hunter.roams = 0;
		hunter.lastActive = System.currentTimeMillis();
		hunter.spotSince = hunter.lastActive;
		hunter.spotExp = player.getExp();
		stop(player);
		if (player.isSitting())
		{
			player.standUp();
		}
		LOGGER.fine(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") goes to " + spot + ".");
		travel(hunter, player, spot);
	}

	/**
	 * Телепорт на спот (патч 0027): в городе — как живой, бегом к ближайшему гейткиперу и телепорт у него ({@link #gatekeeper}); вне города или гейткипера рядом нет — с места.
	 */
	private static void travel(Hunter hunter, Player player, HuntSpot spot)
	{
		final boolean town = PhantomCompat.isInTown(player);
		// Вне города и спот не дальше WALK_TRAVEL — ногами (патч 0030): смена спота стратегом, новый спот после входа в мир.
		final int distance = (int) player.calculateDistance2D(spot.entry);
		if (!town && (distance <= WALK_TRAVEL))
		{
			hunter.walkBackUntil = System.currentTimeMillis() + walkTime(distance);
			if (startWalk(hunter, player, spot))
			{
				LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " walks to " + spot.name + " (мобы " + spot.min + "-" + spot.max + ", " + distance + ", маршрут " + hunter.walkRoute.size() + ").");
				return;
			}
			// Ногами туда не пройти (другой ярус, пещера, остров) — телепорт, а не бег до таймаута.
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " has no way on foot to " + spot.name + " (" + distance + "), teleports.");
		}

		final Npc gatekeeper = town ? nearestGatekeeper(player) : null;
		if (gatekeeper == null)
		{
			payTeleport(player);
			player.teleToLocation(spot.entry, true);
			return;
		}

		// Точка перед гейткипером со стороны охотника: в самого NPC не упираться.
		final double angle = Math.atan2(player.getY() - gatekeeper.getY(), player.getX() - gatekeeper.getX());
		final int x = gatekeeper.getX() + (int) (Math.cos(angle) * 60);
		final int y = gatekeeper.getY() + (int) (Math.sin(angle) * 60);
		hunter.gatekeeperSpot = spot;
		hunter.gatekeeperAt = new Location(x, y, GeoEngine.getInstance().getHeight(x, y, gatekeeper.getZ()));
		hunter.gatekeeperUntil = System.currentTimeMillis() + GATEKEEPER_TIMEOUT;
		hunter.walkTurn = 0;
		hunter.walkLastDistance = player.calculateDistance2D(hunter.gatekeeperAt);
		player.setRunning();
		// По маршруту (раздел 4.30); маршрута нет — отрезками с обходом поворотами (патч 0046).
		if (!runRoute(player, hunter.gatekeeperAt))
		{
			runTo(player, hunter.gatekeeperAt);
		}
		LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " runs to gatekeeper " + gatekeeper.getName() + " (" + (int) player.calculateDistance2D(gatekeeper) + ") for " + spot.name + ".");
	}

	/**
	 * Бежит к гейткиперу: добежал — телепорт на спот у него; застрял дольше {@link #GATEKEEPER_TIMEOUT} — телепорт с места.
	 * Встал и не приблизился с прошлой команды — следующий отрезок с поворотом из {@link #WALK_TURNS}, как в дороге на спот (патч 0046): в Глудио прямую от точек
	 * респа к Bella режет препятствие около (-13750, 122940), путь по геодате Mobius оттуда не находит, и охотник стоял у стены до таймаута.
	 */
	private static void gatekeeper(Hunter hunter, Player player, long now)
	{
		final HuntSpot spot = hunter.gatekeeperSpot;
		final double distance = player.calculateDistance2D(hunter.gatekeeperAt);
		if ((distance > GATEKEEPER_REACH) && (now < hunter.gatekeeperUntil))
		{
			if (!player.isMoving())
			{
				player.setRunning();
				if (runRoute(player, hunter.gatekeeperAt))
				{
					return;
				}
				if (distance < (hunter.walkLastDistance - 50))
				{
					hunter.walkTurn = 0;
				}
				else
				{
					hunter.walkTurn++;
				}
				hunter.walkLastDistance = distance;
				runTo(player, hunter.gatekeeperAt, WALK_TURNS[hunter.walkTurn % WALK_TURNS.length]);
			}
			return;
		}

		hunter.gatekeeperSpot = null;
		hunter.gatekeeperAt = null;
		if (distance > GATEKEEPER_REACH)
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " did not reach the gatekeeper (" + (int) distance + "), teleports.");
		}
		stop(player);
		payTeleport(player);
		player.teleToLocation(spot.entry, true);
	}

	/**
	 * Ближайший гейткипер (NPC-телепортер) в {@link #GATEKEEPER_RANGE}. Путь по прямой не проверяется (патч 0028): в городе прямую режут дома,
	 * а бег идёт с поиском пути; застрял — через {@link #GATEKEEPER_TIMEOUT} телепорт с места.
	 */
	private static Npc nearestGatekeeper(Player player)
	{
		Npc nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Teleporter npc : World.getVisibleObjectsInRange(player, Teleporter.class, GATEKEEPER_RANGE))
		{
			final double distance = player.calculateDistance2D(npc);
			if ((distance < nearestDistance) && npc.isSpawned())
			{
				nearest = npc;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Лидер рейда (раздел 4.32): AutoPlay — стоп, бафы, как перед качем; дорогу и поход забыть.
	 */
	void raidStarts(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter != null)
		{
			hunter.walkBackUntil = 0;
			hunter.walkRoute = null;
			hunter.gatekeeperSpot = null;
			hunter.choice = null;
		}
		stop(player);
		AutoUseTaskManager.getInstance().startAutoUseTask(player);
		if (player.isSitting())
		{
			player.standUp();
		}
		raidBuff(player);
	}

	/** Рейд (раздел 4.32): к набору баффера — шаманские чанты и дары, воину и магу свои (уровни — из схем-баффера, мод 0004). */
	private static final int[] RAID_FIGHTER_BUFFS =
	{
		1363, // Chant of Victory
		1251, // Chant of Fury
		1253, // Chant of Rage
		1007, // Chant of Battle
		1009, // Chant of Shielding
		1310, // Chant of Vampiric
		1308, // Chant of Predator
		1309, // Chant of Eagle
		4700, // Gift of Queen
		4699, // Blessing of Queen
		1388, // Greater Might
		1389, // Greater Shield
		915, // Dance of Berserker
	};
	private static final int[] RAID_MAGE_BUFFS =
	{
		1413, // Magnus' Chant
		1009, // Chant of Shielding
		1006, // Chant of Fire
		1002, // Flame Chant
		4703, // Gift of Seraphim
		4702, // Blessing of Seraphim
		1389, // Greater Shield
		915, // Dance of Berserker
	};

	/**
	 * Бафы участнику рейда: набор баффера ({@link #buff}) и рейдовые — чанты шамана и дары (замечание автора 2026-10-21: «все баффы: с танцами песнями шаманскими?»).
	 */
	static void raidBuff(Player player)
	{
		buff(player);
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		for (int skillId : player.isMageClass() ? RAID_MAGE_BUFFS : RAID_FIGHTER_BUFFS)
		{
			final BuffSkillHolder holder = table.getAvailableBuff(skillId);
			final Skill skill = holder == null ? null : SkillData.getInstance().getSkill(skillId, holder.getLevel());
			if (skill != null)
			{
				skill.applyEffects(player, player);
			}
		}
	}

	/**
	 * Участник рейда из города или лавки: свернуть лавку, забыть дела в городе и дорогу — дальше он в пати лидера (приём в пати ждёт фазу кача).
	 */
	void prepareForRaid(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if ((hunter == null) || (hunter.phase == Phase.PARTY))
		{
			return;
		}
		closeStore(hunter, player);
		hunter.phase = Phase.HUNT;
		hunter.shop = null;
		hunter.afkUntil = 0;
		hunter.walkBackUntil = 0;
		hunter.gatekeeperSpot = null;
		hunter.choice = null;
	}

	/**
	 * Плата за телепорт, как у гейткипера ({@link #payTeleport}) — лидеру рейда к точке сбора.
	 */
	static void payTeleportFee(Player player)
	{
		payTeleport(player);
	}

	/**
	 * Телепорт у гейткипера: до 40-го — бесплатно, как у новичков; дальше — {@link PhantomHuntersConfig#HUNTER_TELEPORT_FEE} (сколько есть).
	 */
	private static void payTeleport(Player player)
	{
		if (player.getLevel() >= 40)
		{
			final long fee = Math.min(player.getAdena(), PhantomHuntersConfig.HUNTER_TELEPORT_FEE);
			if (fee > 0)
			{
				PhantomCompat.reduceAdena(player, ItemProcessType.FEE, fee, player, false);
			}
		}
	}

	private HuntSpot pickSpot(Player player, HuntSpot avoid)
	{
		final int level = player.getLevel();
		// До HOME_LEVEL — сначала родная зона расы, даже с допуском по уровню пошире (у гномов, например, нет спотов 1–7 уровня), потом весь мир.
		if (level < HOME_LEVEL)
		{
			final HuntSpot home = pickSpot(player, avoid, HOME_REGIONS.get(player.getRace()), 4, false);
			if (home != null)
			{
				return home;
			}
			return pickSpot(player, avoid, null, 6, false);
		}
		// Дальше — мир (патч 0039): свободное место вне стартовых зон, мобы не выше трёх уровней своего; нет такого — как раньше, со стартовыми зонами.
		final HuntSpot world = pickSpot(player, avoid, null, 3, true);
		return world != null ? world : pickSpot(player, avoid, null, 6, false);
	}

	/**
	 * @param region только эта зона или {@code null} — любая
	 * @param maxTolerance насколько расширять разброс уровня мобов
	 * @param skipHomes не брать стартовые зоны рас ({@link #HOME_REGIONS}) и занятые места: {@code null}, если свободного вне стартовых зон нет
	 */
	private HuntSpot pickSpot(Player player, HuntSpot avoid, String region, int maxTolerance, boolean skipHomes)
	{
		final int level = player.getLevel();
		for (int tolerance = 0; tolerance <= maxTolerance; tolerance++)
		{
			final List<HuntSpot> fitting = new ArrayList<>();
			for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
			{
				// Перерос — не брать: иначе каждый тик телепорт на тот же спот (у тёмных эльфов дома споты только до 11-го).
				if ((spot != avoid) && !outgrown(spot, level) && ((region == null) || spot.region.equals(region)) && (!skipHomes || !HOME_REGIONS.containsValue(spot.region)) && (spot.level >= (level - 3 - tolerance)) && (spot.level <= (level + tolerance)))
				{
					fitting.add(spot);
				}
			}
			if (fitting.isEmpty())
			{
				continue;
			}

			// Свободные споты — в первую очередь.
			final List<HuntSpot> free = new ArrayList<>();
			for (HuntSpot spot : fitting)
			{
				if (crowd(spot, player) < PhantomHuntersConfig.HUNTERS_PER_SPOT)
				{
					free.add(spot);
				}
			}
			if (free.isEmpty() && skipHomes)
			{
				continue;
			}
			final List<HuntSpot> choice = free.isEmpty() ? fitting : free;
			return choice.get(Rnd.get(choice.size()));
		}
		return null;
	}

	/**
	 * Сколько охотников качается в этом месте: на этом споте или на соседнем ближе {@link #CROWD_RANGE} (патч 0039 — до него считались только на том же споте,
	 * и соседние споты одного поля заполнялись по два охотника каждый).
	 */
	private int crowd(HuntSpot spot, Player self)
	{
		final Hunter me = _hunters.get(self.getName());
		int count = 0;
		for (Hunter other : _hunters.values())
		{
			final HuntSpot taken = other.spot;
			if (other == me)
			{
				continue;
			}
			if ((taken == spot) || ((taken != null) && (Math.hypot(taken.entry.getX() - spot.entry.getX(), taken.entry.getY() - spot.entry.getY()) < CROWD_RANGE)))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Бафы как у городского баффера схем: набор воину или магу (FIGHTER_GROUP, MAGE_GROUP из SchemeBufferSkills.xml).
	 */
	private static void buff(Player player)
	{
		final String group = player.isMageClass() ? "MAGE_GROUP" : "FIGHTER_GROUP";
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		for (int skillId : table.getSkillsIdsByType(group))
		{
			final BuffSkillHolder holder = table.getAvailableBuff(group, skillId);
			final Skill skill = holder == null ? null : SkillData.getInstance().getSkill(skillId, holder.getLevel());
			if (skill != null)
			{
				skill.applyEffects(player, player);
			}
		}
	}

	// ---------- Город ----------

	/**
	 * Повод идти в город: стрелы кончились; сосов меньше 20 % (и есть на что купить); инвентарь заполнен на 70 %; ценный дроп, а с прошлой лавки прошло 30 минут; или просто давно не был.
	 */
	private static boolean needsTown(Hunter hunter, Player player, long now)
	{
		// Записан на PvP-ивент — ждёт телепорта на арену на каче (раздел 4.33).
		if (PhantomEvents.getInstance().isBusy(player))
		{
			return false;
		}
		final long since = now - hunter.lastTown;
		if (since < TOWN_COOLDOWN)
		{
			return false;
		}

		if (PhantomHunterOutfit.outOfAmmo(player) || (player.getInventory().getSize() >= (player.getInventoryLimit() * 0.7)))
		{
			return true;
		}

		if (since >= (PhantomHuntersConfig.HUNTER_TOWN_INTERVAL * 60000L))
		{
			return true;
		}

		if (since < TOWN_SOFT_COOLDOWN)
		{
			return false;
		}

		if ((PhantomHunterOutfit.suppliesLeft(player) < 0.2) && PhantomHunterOutfit.canRestock(player))
		{
			return true;
		}

		// Ценный дроп — повод идти в город, только если нужна адена (лавка от нужды, патч 0035).
		return ((now - hunter.lastStoreSession) >= STORE_COOLDOWN) && !PhantomHunterOutfit.storeGoods(player).isEmpty() && PhantomMarket.needsStore(player, hunter.template);
	}

	private static void goToTown(Hunter hunter, Player player, long now)
	{
		stop(player);
		AutoUseTaskManager.getInstance().stopAutoUseTask(player);
		if (player.isSitting())
		{
			player.standUp();
		}

		// Как свиток возврата: в ближайший город.
		player.teleToLocation(MapRegionData.getInstance().getTeleToLocation(player, TeleportWhereType.TOWN), true);
		enterTown(hunter, now);
	}

	// ---------- Квесты-симуляция ----------

	/**
	 * Квесты-симуляция (HunterQuests) вместо квестов, на которых живые игроки на x1 живут до 20-го: на уровне из HunterQuestLevels
	 * охотник берёт квест, качается ещё HunterQuestMinutes (как за квестовыми мобами), потом идёт в город сдавать — награда в {@link #turnInQuest}.
	 * @return пошёл сдавать
	 */
	private boolean quest(Hunter hunter, Player player, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_QUESTS)
		{
			return false;
		}

		if (hunter.questLevel == 0)
		{
			final int next = nextQuest(player);
			if (next > 0)
			{
				hunter.questLevel = next;
				hunter.questReady = false;
				hunter.questUntil = now + (Rnd.get(PhantomHuntersConfig.HUNTER_QUEST_MIN_MINUTES, PhantomHuntersConfig.HUNTER_QUEST_MAX_MINUTES) * 60000L);
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") takes a quest (level " + next + ").");
			}
			return false;
		}

		if (!hunter.questReady && (now >= hunter.questUntil))
		{
			hunter.questReady = true;
		}
		if (hunter.questReady && !player.isInCombat() && !player.isAttackingNow() && !player.isCastingNow() && !PhantomEvents.getInstance().isBusy(player))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " finished the quest (level " + hunter.questLevel + "), goes to town.");
			goToTown(hunter, player, now);
			return true;
		}
		return false;
	}

	/**
	 * @return квест, который можно взять: самый высокий из HunterQuestLevels не выше своего уровня и выше уже сданного (низкие пропускаются, как у игроков); 0 — нет
	 */
	private static int nextQuest(Player player)
	{
		final int done = player.getVariables().getInt(QUEST_VARIABLE, 0);
		int best = 0;
		for (int level : PhantomHuntersConfig.HUNTER_QUEST_LEVELS)
		{
			if ((level > done) && (level <= player.getLevel()))
			{
				best = level;
			}
		}
		return best;
	}

	/**
	 * Сдать квест в городе: HunterQuestReward × уровень квеста² адены.
	 */
	private void turnInQuest(Hunter hunter, Player player)
	{
		final long reward = (long) PhantomHuntersConfig.HUNTER_QUEST_REWARD * hunter.questLevel * hunter.questLevel;
		if (reward > 0)
		{
			PhantomCompat.addAdena(player, ItemProcessType.QUEST, reward, player, false);
		}
		player.getVariables().set(QUEST_VARIABLE, hunter.questLevel);
		player.getVariables().storeMe();
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " turned in the quest (level " + hunter.questLevel + "): +" + reward + " adena.");
		hunter.questLevel = 0;
		hunter.questReady = false;
		hunter.questUntil = 0;
	}

	private static void enterTown(Hunter hunter, long now)
	{
		hunter.phase = Phase.TOWN;
		hunter.choresDone = false;
		hunter.phaseUntil = now + Rnd.get(20000, 45000);
		hunter.lastTown = now;
		hunter.recruitArrived = false;
		hunter.shop = null;
		hunter.eventNpcId = 0;
		hunter.eventStandUntil = 0;
	}

	/**
	 * В городе: пройтись (как до торговца), сделать дела, постоять; с ценным дропом — в лавку, иначе назад на кач.
	 */
	private void town(Hunter hunter, Player player, long now)
	{
		// Рынок (патч 0035): идёт к лавке в Гиране за покупкой.
		if (hunter.shop != null)
		{
			shop(hunter, player, now);
			return;
		}
		// Сезонное событие (раздел 4.34): идёт к NPC события.
		if (hunter.eventNpcId != 0)
		{
			eventVisit(hunter, player, now);
			return;
		}

		if (!hunter.choresDone)
		{
			if (!player.isMoving() && (Rnd.get(100) < 25))
			{
				wander(player, 150, 450);
			}

			if (now < hunter.phaseUntil)
			{
				return;
			}

			hunter.choresDone = true;
			if (hunter.questReady)
			{
				turnInQuest(hunter, player);
			}
			final List<String> done = PhantomHunterOutfit.townChores(player, hunter.template, hunter.unsold);
			player.storeMe();
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") in " + MapRegionData.getInstance().getClosestTownName(player) + ": " + (done.isEmpty() ? "nothing to do" : String.join(", ", done)) + "; " + player.getAdena() + " adena.");
			final boolean afk = hunter.afkUntil > now;
			// Сезонное событие (раздел 4.34): рядом NPC идущего события — подойти, сдать, что умеет.
			if (!afk && goToEvent(hunter, player, now))
			{
				return;
			}
			// Рынок (патч 0035): в лавках Гирана есть вещь лучше надетой, не дороже магазина и по карману — сходить купить.
			if (!afk && goShopping(hunter, player, now))
			{
				return;
			}
			if (!afk && wantsStore(hunter, player, now))
			{
				hunter.wantStore = false;
				goToStore(hunter, player);
				return;
			}
			hunter.wantStore = false;
			// Стратег велел отдохнуть — сидит афк до назначенного, потом обратно.
			hunter.phaseUntil = Math.max(now + Rnd.get(5000, 15000), hunter.afkUntil);
			if (afk && !player.isSitting())
			{
				player.sitDown();
			}
			return;
		}

		if (now >= hunter.phaseUntil)
		{
			if (hunter.afkUntil > 0)
			{
				hunter.afkUntil = 0;
				LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " is back from AFK.");
			}
			backToHunt(hunter, player);
		}
	}

	/**
	 * Из города и лавки — обратно: на свой спот, если он ещё по уровню, иначе — выбрать новый.
	 * Спот рядом — бежит ногами, как живой (дальше дорогу ведёт {@link #leash}); далеко — телепорт за адену, в городе — у гейткипера ({@link #travel}).
	 */
	private void backToHunt(Hunter hunter, Player player)
	{
		final long now = System.currentTimeMillis();
		hunter.phase = Phase.HUNT;
		hunter.needBuff = true;
		hunter.recruiterId = 0;
		hunter.lastActive = now;
		hunter.roams = 0;
		hunter.walkBackUntil = 0;
		hunter.walkFails = 0;
		closeStore(hunter, player);
		if ((hunter.spot != null) && !outgrown(hunter.spot, player.getLevel()))
		{
			stop(player);
			if (player.isSitting())
			{
				player.standUp();
			}
			final int distance = (int) player.calculateDistance2D(hunter.spot.entry);
			if (distance <= WALK_RANGE)
			{
				hunter.walkBackUntil = now + WALK_BACK_TIMEOUT;
				if (startWalk(hunter, player, hunter.spot))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " walks back to " + hunter.spot.name + " (" + distance + ", маршрут " + hunter.walkRoute.size() + ").");
					return;
				}
			}
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " teleports back to " + hunter.spot.name + " (" + distance + ").");
			travel(hunter, player, hunter.spot);
			return;
		}
		hunter.spot = null;
	}

	/**
	 * Отпустили из пати или не дождался инвайта (патч 0027): ближайший спот по силам — ногами, без телепорта, как живой игрок, которого отпустили посреди локации.
	 * Мобы до {@link #NEARBY_LEVEL_UP} уровней выше своего годятся: игрок мог привести на спот посильнее, бафы ещё висят. Новых бафов не берёт — пати-бафы лучше.
	 */
	private void backToHuntNearby(Hunter hunter, Player player)
	{
		final long now = System.currentTimeMillis();
		hunter.phase = Phase.HUNT;
		hunter.recruiterId = 0;
		hunter.lastActive = now;
		hunter.roams = 0;
		hunter.walkBackUntil = 0;
		hunter.walkFails = 0;
		hunter.gatekeeperSpot = null;
		closeStore(hunter, player);
		final HuntSpot spot = nearestSpot(player, null);
		if (spot == null)
		{
			// Ничего по силам вокруг — выберет спот как обычно.
			hunter.spot = null;
			return;
		}
		walkTo(hunter, player, spot);
	}

	/**
	 * Ближайший к охотнику спот, на котором он не перерос мобов, а мобы не сильнее {@link #NEARBY_LEVEL_UP} уровней.
	 * @param avoid этот не брать или {@code null}
	 */
	private static HuntSpot nearestSpot(Player player, HuntSpot avoid)
	{
		final int level = player.getLevel();
		HuntSpot nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot == avoid) || outgrown(spot, level) || (spot.level > (level + NEARBY_LEVEL_UP)) || (spot.level < (level - 9)))
			{
				continue;
			}

			final double distance = player.calculateDistance2D(spot.entry);
			if (distance < nearestDistance)
			{
				nearest = spot;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Вошёл в мир или перерос спот (патч 0030): ближайший спот по силам — мобы не выше {@link #SPAWN_LEVEL_UP} уровней, — свободный ({@code HuntersPerSpot}) первым.
	 * Патч 0039: из свободных — один из {@link #NEAREST_CHOICE} ближайших наугад, чтобы вошедшие в одном месте не шли на один спот; с {@link #HOME_LEVEL} уровня
	 * стартовые зоны рас (своя и чужие) не годятся — рядом с городом в них ничего нет, и охотник уходит в мир через {@link #pickSpot}.
	 * @return спот не дальше {@link #WALK_TRAVEL} или {@code null} — рядом подходящего нет
	 */
	private HuntSpot nearestFreeSpot(Player player)
	{
		final int level = player.getLevel();
		final boolean leftHome = level >= HOME_LEVEL;
		final List<HuntSpot> free = new ArrayList<>();
		HuntSpot any = null;
		double anyDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if (outgrown(spot, level) || (spot.level > (level + SPAWN_LEVEL_UP)) || (spot.level < (level - 9)) || (leftHome && HOME_REGIONS.containsValue(spot.region)))
			{
				continue;
			}

			final double distance = player.calculateDistance2D(spot.entry);
			if (distance > WALK_TRAVEL)
			{
				continue;
			}
			if (distance < anyDistance)
			{
				any = spot;
				anyDistance = distance;
			}
			if (crowd(spot, player) < PhantomHuntersConfig.HUNTERS_PER_SPOT)
			{
				free.add(spot);
			}
		}
		if (free.isEmpty())
		{
			return any;
		}
		free.sort(Comparator.comparingDouble(spot -> player.calculateDistance2D(spot.entry)));
		return free.get(Rnd.get(Math.min(NEAREST_CHOICE, free.size())));
	}

	/**
	 * Новый спот — ногами (без телепорта); уже на нём — просто качается дальше. Время в пути — по расстоянию.
	 */
	private static void walkTo(Hunter hunter, Player player, HuntSpot spot)
	{
		final long now = System.currentTimeMillis();
		final boolean same = hunter.spot == spot;
		hunter.spot = spot;
		if (!same)
		{
			hunter.spotSince = now;
			hunter.spotExp = player.getExp();
		}
		stop(player);
		if (player.isSitting())
		{
			player.standUp();
		}

		final int distance = (int) player.calculateDistance2D(spot.entry);
		if (distance <= (spot.radius * 1.3))
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " stays at " + spot.name + " (мобы " + spot.min + "-" + spot.max + ").");
			return;
		}

		// Дальше WALK_TRAVEL ногами не ходят: ближайший по силам бывает за картой (отпустили из пати в городе — Tihon 2026-10-20 получил поход
		// на 25736; другой спот после «застрял») — тогда как дальний: в городе к гейткиперу, вне — телепорт.
		if (distance > WALK_TRAVEL)
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " goes to " + spot.name + " (мобы " + spot.min + "-" + spot.max + ", " + distance + "), too far on foot.");
			travel(hunter, player, spot);
			return;
		}

		hunter.walkBackUntil = now + walkTime(distance);
		if (startWalk(hunter, player, spot))
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " walks to " + spot.name + " (мобы " + spot.min + "-" + spot.max + ", " + distance + ", маршрут " + hunter.walkRoute.size() + ").");
			return;
		}

		// Ногами туда не пройти (другой ярус, пещера, остров) — сразу другой спот рядом, второй раз подряд — телепорт: как после «застрял»,
		// только без бега поворотами до таймаута.
		final HuntSpot other = hunter.walkFails++ == 0 ? nearestSpot(player, spot) : null;
		if (other != null)
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " has no way on foot to " + spot.name + " (" + distance + "), tries " + other.name + ".");
			walkTo(hunter, player, other);
			return;
		}
		hunter.walkFails = 0;
		LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " has no way on foot to " + spot.name + " (" + distance + "), teleports.");
		stop(player);
		player.teleToLocation(spot.entry, true);
	}

	/**
	 * Начать дорогу ногами на спот (время в пути — {@code walkBackUntil} — ставит вызывающий): маршрут по геодате ({@link PhantomPath}) до радиуса спота
	 * и бег к его первой точке; маршрут длиннее прямой — время в пути по его длине.
	 * @return маршрут есть и дорога начата; нет — ногами туда не пройти, {@code walkBackUntil} сброшен, решает вызывающий
	 */
	private static boolean startWalk(Hunter hunter, Player player, HuntSpot spot)
	{
		hunter.walkTurn = 0;
		hunter.walkReplans = 0;
		hunter.walkRoute = PhantomPath.find(player.getX(), player.getY(), player.getZ(), spot.entry.getX(), spot.entry.getY(), spot.entry.getZ(), spot.radius, player.getInstanceId());
		hunter.walkRouteIndex = 0;
		if (hunter.walkRoute == null)
		{
			hunter.walkBackUntil = 0;
			return false;
		}
		player.setRunning();
		followRoute(hunter, player);
		return true;
	}

	/**
	 * Маршрут построен заново или начат: время в пути — не меньше, чем по его длине, и бег к первой точке.
	 */
	private static void followRoute(Hunter hunter, Player player)
	{
		double length = 0;
		Location from = player.getLocation();
		for (Location point : hunter.walkRoute)
		{
			length += Math.hypot(point.getX() - from.getX(), point.getY() - from.getY());
			from = point;
		}
		hunter.walkBackUntil = Math.max(hunter.walkBackUntil, System.currentTimeMillis() + walkTime((int) length));
		final Location first = hunter.walkRoute.get(0);
		hunter.walkLastDistance = player.calculateDistance2D(first);
		player.getAI().setIntentionMoveTo(first);
	}

	/**
	 * Шаг дороги ногами, раз в тик (из {@link #leash}). По маршруту: дошёл до точки — к следующей; встал, не дойдя (драка, геодата не пустила):
	 * приблизился к точке с прошлой команды — к ней же снова, нет — маршрут заново от места, не больше {@link #ROUTE_REPLANS} раз; заново не нашёлся —
	 * дорога кончена, на следующем тике {@link #leash} — другой спот рядом или телепорт. Перестраивал уже {@link #ROUTE_REPLANS} раз — дальше
	 * отрезками с обходом поворотами (патч 0045). Обходной отрезок добегает до конца и только потом сравнивается с расстоянием на момент команды:
	 * раньше его ставили заново каждый тик, и охотник под углом 120° убегал от цели до таймаута.
	 */
	private static void walkStep(Hunter hunter, Player player, HuntSpot spot, double distance)
	{
		final Location target = spot.entry;
		if (hunter.walkRoute != null)
		{
			final Location point = hunter.walkRoute.get(hunter.walkRouteIndex);
			final double toPoint = player.calculateDistance2D(point);
			if (toPoint <= ROUTE_REACH)
			{
				if (++hunter.walkRouteIndex < hunter.walkRoute.size())
				{
					final Location next = hunter.walkRoute.get(hunter.walkRouteIndex);
					hunter.walkLastDistance = player.calculateDistance2D(next);
					player.getAI().setIntentionMoveTo(next);
					return;
				}
				// Маршрут кончился, а до входа на спот ещё далеко (спот сдвинулся или мерили не от того места) — дальше прямо.
				hunter.walkRoute = null;
				hunter.walkTurn = 0;
				hunter.walkLastDistance = distance;
				runTo(player, target);
				return;
			}
			if (player.isMoving())
			{
				return;
			}
			if (toPoint < (hunter.walkLastDistance - 50))
			{
				hunter.walkLastDistance = toPoint;
				player.getAI().setIntentionMoveTo(point);
				return;
			}
			if (hunter.walkReplans < ROUTE_REPLANS)
			{
				hunter.walkReplans++;
				hunter.walkRoute = PhantomPath.find(player.getX(), player.getY(), player.getZ(), target.getX(), target.getY(), target.getZ(), spot.radius, player.getInstanceId());
				hunter.walkRouteIndex = 0;
				if (hunter.walkRoute != null)
				{
					followRoute(hunter, player);
					return;
				}
				// Отсюда ногами не пройти — время в пути вышло: следующий тик решит, как после «застрял» (проверку прогресса не ждать, она бы продлила).
				hunter.walkBackUntil = 1;
				hunter.walkProgressAt = System.currentTimeMillis();
				return;
			}
			hunter.walkRoute = null;
			hunter.walkTurn = 0;
			hunter.walkLastDistance = distance;
		}

		final int turn = WALK_TURNS[hunter.walkTurn % WALK_TURNS.length];
		if ((turn != 0) && player.isMoving())
		{
			return;
		}
		if (turn == 0)
		{
			if (distance < (hunter.walkLastDistance - 50))
			{
				hunter.walkLastDistance = distance;
			}
			else if (!player.isMoving())
			{
				hunter.walkTurn++;
				hunter.walkLastDistance = distance;
			}
		}
		else
		{
			hunter.walkTurn = distance < (hunter.walkLastDistance - 50) ? 0 : hunter.walkTurn + 1;
			hunter.walkLastDistance = distance;
		}
		runTo(player, target, WALK_TURNS[hunter.walkTurn % WALK_TURNS.length]);
	}

	/**
	 * Маршрут короткого забега ({@link #runRoute}): к гейткиперу, к продавцу, «паровоз» к споту и к владельцу.
	 */
	private static final class Route
	{
		final Location target;
		List<Location> points;
		int index;
		int replans;
		double lastDistance = Double.MAX_VALUE;

		Route(Location target, List<Location> points)
		{
			this.target = target;
			this.points = points;
		}
	}

	/** Маршруты коротких забегов по objectId бегущего; цель сменилась — маршрут строится заново. */
	private static final Map<Integer, Route> ROUTES = new ConcurrentHashMap<>();

	/**
	 * Бежать к точке по маршруту {@link PhantomPath} (раздел 4.30) — для забегов без своего учёта дороги: к гейткиперу, к продавцу, «паровоз» к споту
	 * и к владельцу. Звать, когда бегущий встал. Цель сменилась (дальше {@link #ROUTE_REACH} от прежней) — маршрут заново; ближе {@link #MOVE_STEP} — прямо,
	 * обход дома Mobius найдёт сам. Встал у точки маршрута и не приблизился к ней с прошлой команды (бой, геодата) — маршрут заново от места,
	 * не больше {@link #ROUTE_REPLANS} раз.
	 * @return {@code false} — маршрута нет: вызывающий бежит по-старому, отрезками ({@link #runTo}), и так до смены цели
	 */
	static boolean runRoute(Player player, Location target)
	{
		final double distance = player.calculateDistance2D(target);
		Route route = ROUTES.get(player.getObjectId());
		if ((route == null) || (Math.hypot(route.target.getX() - target.getX(), route.target.getY() - target.getY()) > ROUTE_REACH))
		{
			// Близко — маршрут из одной точки: прямо, но встал без прогресса — перестройка, как у дальнего.
			final List<Location> points = distance <= MOVE_STEP ? new ArrayList<>(List.of(target)) : PhantomPath.find(player.getX(), player.getY(), player.getZ(), target.getX(), target.getY(), target.getZ(), 0, player.getInstanceId());
			route = new Route(target, points);
			ROUTES.put(player.getObjectId(), route);
		}
		if (route.points == null)
		{
			return false;
		}

		while ((route.index < route.points.size()) && (player.calculateDistance2D(route.points.get(route.index)) <= ROUTE_REACH))
		{
			route.index++;
			route.lastDistance = Double.MAX_VALUE;
		}
		if (route.index >= route.points.size())
		{
			ROUTES.remove(player.getObjectId());
			player.getAI().setIntentionMoveTo(target);
			return true;
		}

		final Location point = route.points.get(route.index);
		final double toPoint = player.calculateDistance2D(point);
		if (toPoint >= (route.lastDistance - 50))
		{
			// Встал и не приблизился — маршрут заново от места (драка увела в сторону, геодата не пустила).
			if (route.replans >= ROUTE_REPLANS)
			{
				route.points = null;
				return false;
			}
			final int replans = route.replans + 1;
			route = new Route(target, PhantomPath.find(player.getX(), player.getY(), player.getZ(), target.getX(), target.getY(), target.getZ(), 0, player.getInstanceId()));
			route.replans = replans;
			ROUTES.put(player.getObjectId(), route);
			if ((route.points == null) || route.points.isEmpty())
			{
				route.points = null;
				return false;
			}
			player.getAI().setIntentionMoveTo(route.points.get(0));
			route.lastDistance = player.calculateDistance2D(route.points.get(0));
			return true;
		}
		route.lastDistance = toPoint;
		player.getAI().setIntentionMoveTo(point);
		return true;
	}

	/**
	 * Бежать к точке, как живой игрок: ближе {@link #MOVE_STEP} — прямо (путь по геодате Mobius найдёт сам), дальше — к промежуточной точке на {@link #MOVE_STEP}
	 * в её сторону. Дошёл до промежуточной и встал — следующий тик даёт следующую (патч 0040).
	 */
	static void runTo(Player player, Location target)
	{
		runTo(player, target, 0);
	}

	/**
	 * @param turn поворот отрезка от направления на цель, в градусах: обход препятствия (патч 0045)
	 */
	private static void runTo(Player player, Location target, int turn)
	{
		final double distance = player.calculateDistance2D(target);
		if ((distance <= MOVE_STEP) && (turn == 0))
		{
			player.getAI().setIntentionMoveTo(target);
			return;
		}

		final double angle = Math.atan2(target.getY() - player.getY(), target.getX() - player.getX()) + Math.toRadians(turn);
		final double step = Math.min(MOVE_STEP, distance);
		final int x = player.getX() + (int) (Math.cos(angle) * step);
		final int y = player.getY() + (int) (Math.sin(angle) * step);
		player.getAI().setIntentionMoveTo(new Location(x, y, GeoEngine.getInstance().getHeight(x, y, player.getZ())));
	}

	/**
	 * @return сколько ждать, пока дойдёт ногами: по расстоянию с запасом, не меньше {@link #WALK_BACK_TIMEOUT}
	 */
	private static long walkTime(int distance)
	{
		return Math.max(WALK_BACK_TIMEOUT, ((distance * 1000L) / WALK_SPEED) + 30000);
	}

	private static void wander(Player player, int min, int max)
	{
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int r = Rnd.get(min, max);
		final int x = player.getX() + (int) (Math.cos(angle) * r);
		final int y = player.getY() + (int) (Math.sin(angle) * r);
		final int z = GeoEngine.getInstance().getHeight(x, y, player.getZ());
		if (PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), player.getX(), player.getY(), player.getZ(), x, y, z, player.getInstanceId()))
		{
			player.getAI().setIntentionMoveTo(new Location(x, y, z));
		}
	}

	// ---------- Рынок (патч 0035) ----------

	/**
	 * После дел в городе: в лавках на площади Гирана (охотники и живые игроки) есть вещь лучше надетой, не дороже магазина и по карману — туда:
	 * не в Гиране — телепорт на площадь (плата — как у гейткипера), дальше пешком к продавцу ({@link #shop}).
	 * @return пошёл за покупкой
	 */
	private static boolean goShopping(Hunter hunter, Player player, long now)
	{
		// Купить лучше надетого; нечего — продать из сумки в чью-нибудь скупку (раздел 4.29).
		PhantomMarket.Offer offer = PhantomMarket.findOffer(player);
		if (offer == null)
		{
			offer = PhantomMarket.findSale(player);
		}
		final Player seller = offer == null ? null : PhantomMarket.seller(offer);
		if (seller == null)
		{
			return false;
		}
		// Продажа дешевле двух плат за телепорт в Гиран и обратно — не стоит дороги.
		if (offer.sale() && (player.calculateDistance2D(seller) > MARKET_WALK) && (player.getLevel() >= 40) && ((offer.count() * offer.price()) < (2L * PhantomHuntersConfig.HUNTER_TELEPORT_FEE)))
		{
			return false;
		}

		hunter.shop = offer;
		hunter.shopUntil = now + SHOP_TIMEOUT;
		if (offer.sale())
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " goes to sell " + offer.name() + (offer.count() > 1 ? " x" + offer.count() : "") + " to " + seller.getName() + " for " + offer.price() + (offer.count() > 1 ? " each" : "") + " (" + player.getAdena() + " adena).");
		}
		else
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " goes to buy " + offer.name() + " from " + seller.getName() + " for " + offer.price() + " (" + player.getAdena() + " adena).");
		}
		stop(player);
		if (player.isSitting())
		{
			player.standUp();
		}
		if (player.calculateDistance2D(seller) > (MARKET_WALK))
		{
			payTeleport(player);
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int x = PhantomMarket.GIRAN_SQUARE.getX() + (int) (Math.cos(angle) * 150);
			final int y = PhantomMarket.GIRAN_SQUARE.getY() + (int) (Math.sin(angle) * 150);
			player.teleToLocation(new Location(x, y, GeoEngine.getInstance().getHeight(x, y, PhantomMarket.GIRAN_SQUARE.getZ())), true);
		}
		return true;
	}

	/**
	 * Идёт к продавцу (или к скупщику, раздел 4.29); рядом — покупает в его лавке (продаёт в скупку), постоит и обратно на кач. Лавка свернулась
	 * или не дошёл за {@link #SHOP_TIMEOUT} — сразу обратно.
	 */
	private void shop(Hunter hunter, Player player, long now)
	{
		final Player seller = PhantomMarket.seller(hunter.shop);
		if ((seller == null) || (now > hunter.shopUntil))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " did not " + (hunter.shop.sale() ? "sell " : "buy ") + hunter.shop.name() + (seller == null ? ": the store is gone." : ": did not reach the " + (hunter.shop.sale() ? "buyer." : "seller.")));
			hunter.shop = null;
			backToHunt(hunter, player);
			return;
		}

		if (player.calculateDistance2D(seller) > SHOP_REACH)
		{
			if (!player.isMoving())
			{
				player.setRunning();
				final Location at = new Location(seller.getX(), seller.getY(), seller.getZ());
				if (!runRoute(player, at))
				{
					runTo(player, at);
				}
			}
			return;
		}

		if (hunter.shop.sale())
		{
			PhantomMarket.sell(player, hunter.shop);
		}
		else
		{
			PhantomMarket.buy(player, hunter.shop);
		}
		hunter.shop = null;
		hunter.choresDone = true;
		hunter.phaseUntil = now + Rnd.get(3000, 8000); // постоять у лавки, потом — town() вернёт на кач
	}

	// ---------- Сезонные события (раздел 4.34) ----------

	/** К NPC события — не чаще; дальше этого в городе не ходит; не дошёл за столько — бросил. */
	private static final long EVENT_VISIT_GAP = 1800000;
	private static final int EVENT_RANGE = 3000;
	private static final long EVENT_VISIT_TIMEOUT = 90000;

	/**
	 * В городе после дел: рядом стоит NPC идущего сезонного события ({@link PhantomSeasonal}) — сходить к нему.
	 * @return пошёл
	 */
	private static boolean goToEvent(Hunter hunter, Player player, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_SEASONAL_EVENTS || ((now - hunter.lastEventVisit) < EVENT_VISIT_GAP))
		{
			return false;
		}
		final Npc npc = PhantomSeasonal.nearestEventNpc(player, EVENT_RANGE);
		if (npc == null)
		{
			return false;
		}
		hunter.eventNpcId = npc.getObjectId();
		hunter.eventUntil = now + EVENT_VISIT_TIMEOUT;
		hunter.lastEventVisit = now;
		hunter.eventTitle = PhantomSeasonal.titleOf(npc);
		hunter.eventNpcName = npc.getName();
		hunter.eventResult = null;
		hunter.eventStandUntil = 0;
		if (player.isSitting())
		{
			player.standUp();
		}
		LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " goes to " + npc.getName() + " (" + PhantomSeasonal.eventOf(npc) + ", " + (int) player.calculateDistance2D(npc) + ").");
		return true;
	}

	/**
	 * Идёт к NPC события; рядом — делает, что умеет ({@link PhantomSeasonal#act}), стоит немного и обратно на кач (как после рынка).
	 */
	private void eventVisit(Hunter hunter, Player player, long now)
	{
		final WorldObject object = World.findObject(hunter.eventNpcId);
		final Npc npc = object instanceof Npc ? (Npc) object : null;
		if ((npc == null) || !npc.isSpawned() || (now > hunter.eventUntil))
		{
			hunter.eventNpcId = 0;
			hunter.eventStandUntil = 0;
			hunter.choresDone = true;
			hunter.phaseUntil = now + Rnd.get(3000, 8000);
			return;
		}
		if (player.calculateDistance2D(npc) > SHOP_REACH)
		{
			if (!player.isMoving())
			{
				player.setRunning();
				final Location at = new Location(npc.getX(), npc.getY(), npc.getZ());
				if (!runRoute(player, at))
				{
					runTo(player, at);
				}
			}
			return;
		}

		stop(player);
		final String done = PhantomSeasonal.act(player, npc);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " at " + npc.getName() + " (" + PhantomSeasonal.eventOf(npc) + "): " + (done != null ? done : "stands around") + ".");
		hunter.eventNpcId = 0;
		hunter.eventResult = done;
		hunter.choresDone = true;
		hunter.phaseUntil = now + Rnd.get(8000, 20000); // постоять у NPC события, потом — town() вернёт на кач
		hunter.eventStandUntil = hunter.phaseUntil;
	}

	/**
	 * Для «Мой мир» ({@code activity} в {@value #STATUS_FILE}): идёт к NPC сезонного события или стоит у него.
	 * @return дописал {@code ,"activity":{…}}
	 */
	private static boolean seasonalActivity(StringBuilder sb, Hunter hunter, long now)
	{
		final boolean going = hunter.eventNpcId != 0;
		if (!going && (now >= hunter.eventStandUntil))
		{
			return false;
		}
		sb.append(",\"activity\":{\"kind\":\"seasonal\",");
		FakePlayerBrain.field(sb, "title", hunter.eventTitle).append(',');
		FakePlayerBrain.field(sb, "stage", going ? "GOING" : "AT_NPC").append(',');
		FakePlayerBrain.field(sb, "npc", hunter.eventNpcName).append(",\"since\":").append(hunter.lastEventVisit);
		if (!going && (hunter.eventResult != null))
		{
			sb.append(',');
			FakePlayerBrain.field(sb, "result", hunter.eventResult);
		}
		sb.append('}');
		return true;
	}

	// ---------- Лавка ----------

	private boolean wantsStore(Hunter hunter, Player player, long now)
	{
		// Продавать нечего — может, скупка (раздел 4.29): копит на вещь и накопил на неё больше половины.
		final boolean goods = !PhantomHunterOutfit.storeGoods(player).isEmpty();
		if ((!hunter.wantStore && ((now - hunter.lastStoreSession) < STORE_COOLDOWN)) || (!goods && (PhantomMarket.buyOrder(player, hunter.template) == null)))
		{
			return false;
		}
		// Лавка от нужды (патч 0035): садится, только когда не хватает адены на цель или сумка почти полна; стратег велел — садится и так.
		if (goods && !hunter.wantStore && !PhantomMarket.needsStore(player, hunter.template))
		{
			return false;
		}

		int stores = 0;
		for (Hunter other : _hunters.values())
		{
			if (other.phase == Phase.STORE)
			{
				stores++;
			}
		}
		return stores < PhantomHuntersConfig.HUNTER_MAX_STORES;
	}

	/**
	 * На площадь Гирана: точка в кольце вокруг гейткипера на уровне площади ({@link #storePoint}); проверку зоны и соседей делает {@link #store} по прилёту.
	 */
	private static void goToStore(Hunter hunter, Player player)
	{
		hunter.phase = Phase.STORE;
		hunter.storeOpen = false;
		hunter.storeTries++;
		stop(player);
		if (player.isSitting())
		{
			player.standUp();
		}
		player.teleToLocation(storePoint(), false);
	}

	/**
	 * @return точка лавки: в кольце 250–800 вокруг гейткипера, на уровне площади ({@value #STORE_Z_TOLERANCE}), на ровном и с прямым путём от центра площади —
	 *         не постамент статуи и не ступени; не нашлось за 20 попыток — у самого центра площади
	 */
	private static Location storePoint()
	{
		final GeoEngine geo = GeoEngine.getInstance();
		for (int i = 0; i < 20; i++)
		{
			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int r = Rnd.get(250, 800);
			final int x = GIRAN_SQUARE.getX() + (int) (Math.cos(angle) * r);
			final int y = GIRAN_SQUARE.getY() + (int) (Math.sin(angle) * r);
			final int z = geo.getHeight(x, y, GIRAN_SQUARE.getZ());
			if ((Math.abs(z - GIRAN_SQUARE.getZ()) > STORE_Z_TOLERANCE) || !flat(geo, x, y, z) || !PhantomCompat.canMoveToTarget(geo, GIRAN_SQUARE.getX(), GIRAN_SQUARE.getY(), GIRAN_SQUARE.getZ(), x, y, z, 0))
			{
				continue;
			}
			return new Location(x, y, z, Rnd.get(65536));
		}
		final int x = GIRAN_SQUARE.getX() + Rnd.get(-120, 120);
		final int y = GIRAN_SQUARE.getY() + Rnd.get(-120, 120);
		return new Location(x, y, geo.getHeight(x, y, GIRAN_SQUARE.getZ()), Rnd.get(65536));
	}

	/**
	 * @return вокруг точки ровно: высота соседей в {@value #STORE_FLAT_RADIUS} — в пределах {@value #STORE_FLAT_Z} (бордюр, край постамента — нет)
	 */
	private static boolean flat(GeoEngine geo, int x, int y, int z)
	{
		for (int dx = -STORE_FLAT_RADIUS; dx <= STORE_FLAT_RADIUS; dx += STORE_FLAT_RADIUS)
		{
			for (int dy = -STORE_FLAT_RADIUS; dy <= STORE_FLAT_RADIUS; dy += STORE_FLAT_RADIUS)
			{
				if (Math.abs(geo.getHeight(x + dx, y + dy, z) - z) > STORE_FLAT_Z)
				{
					return false;
				}
			}
		}
		return true;
	}

	private void store(Hunter hunter, Player player, long now)
	{
		if (!hunter.storeOpen)
		{
			if (!openStore(hunter, player, now))
			{
				if (hunter.storeTries >= STORE_TRIES)
				{
					hunter.storeTries = 0;
					hunter.lastStoreSession = now;
					backToHunt(hunter, player);
				}
				else
				{
					goToStore(hunter, player);
				}
			}
			return;
		}

		// Время вышло, всё раскупили (в скупку — продали) или кто-то закрыл — свернуться и обратно на кач.
		final boolean buy = hunter.buyItemId > 0;
		if ((now >= hunter.phaseUntil) || (player.getPrivateStoreType() != (buy ? PrivateStoreType.BUY : PrivateStoreType.SELL)) || ((buy ? player.getBuyList() : player.getSellList()).getItemCount() == 0))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " closes the store; " + player.getAdena() + " adena.");
			backToHunt(hunter, player);
		}
	}

	/**
	 * Настоящая личная лавка (как {@code SetPrivateStoreListSell}): ценное по цене магазина × характер, название — заготовка.
	 * @return открыл ли
	 */
	private boolean openStore(Hunter hunter, Player player, long now)
	{
		if (!player.canOpenPrivateStore() || (World.getFirstVisibleObject(player, Player.class, other -> other.isInStoreMode() && (player.calculateDistance2D(other) < STORE_SPACING)) != null))
		{
			return false;
		}

		final List<Item> goods = PhantomHunterOutfit.storeGoods(player);
		if (goods.isEmpty())
		{
			if (openBuyStore(hunter, player, now))
			{
				return true;
			}
			hunter.storeTries = STORE_TRIES;
			return false;
		}

		// Цена — по характеру и от нужды (патч 0035): чем больше не хватает до цели, тем дешевле; не продалось раньше — ещё дешевле.
		final boolean unsoldBefore = goods.stream().anyMatch(item -> hunter.unsold.containsKey(item.getObjectId()));
		final double factor = priceFactor(hunter.template) * PhantomMarket.desperation(player, hunter.template, unsoldBefore);
		final TradeList list = player.getSellList();
		list.clear();
		list.setPackaged(false);
		final List<String> names = new ArrayList<>();
		for (Item item : goods.subList(0, Math.min(goods.size(), player.getPrivateSellStoreLimit())))
		{
			final long price = Math.max(1, Math.round(item.getTemplate().getReferencePrice() * factor));
			PhantomCompat.addTradeItem(list, item.getObjectId(), item.getCount(), price);
			names.add(item.getName() + (item.getCount() > 1 ? " x" + item.getCount() : "") + " " + price);
		}
		if (list.getItemCount() == 0)
		{
			hunter.storeTries = STORE_TRIES;
			return false;
		}

		list.setTitle(STORE_TITLES[Rnd.get(STORE_TITLES.length)]);
		player.sitDown();
		player.setPrivateStoreType(PrivateStoreType.SELL);
		player.broadcastUserInfo();
		player.broadcastPacket(new PrivateStoreMsgSell(player));
		hunter.storeOpen = true;
		hunter.storeTries = 0;
		hunter.lastStoreSession = now;
		hunter.phaseUntil = now + (Rnd.get(PhantomHuntersConfig.HUNTER_STORE_MIN_MINUTES, PhantomHuntersConfig.HUNTER_STORE_MAX_MINUTES) * 60000L);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " opens a store in Giran: " + list.getItemCount() + " items, price x" + String.format(Locale.ROOT, "%.2f", factor) + ": " + String.join(", ", names) + ".");
		return true;
	}

	/**
	 * Лавка скупки (раздел 4.29): «Куплю <вещь>» — то, на что копит, по цене из {@link PhantomMarket#buyOrder}. Адена на заказ у него есть: так проверяет и Mobius.
	 * @return открыл
	 */
	private static boolean openBuyStore(Hunter hunter, Player player, long now)
	{
		final PhantomMarket.Order order = PhantomMarket.buyOrder(player, hunter.template);
		if (order == null)
		{
			return false;
		}

		final TradeList list = player.getBuyList();
		list.clear();
		if (PhantomCompat.addBuyItem(list, order.itemId(), 1, order.price()) == null)
		{
			return false;
		}

		final String title = "Куплю " + order.name();
		list.setTitle(title.length() > 29 ? title.substring(0, 29) : title);
		player.sitDown();
		player.setPrivateStoreType(PrivateStoreType.BUY);
		player.broadcastUserInfo();
		player.broadcastPacket(new PrivateStoreMsgBuy(player));
		hunter.storeOpen = true;
		hunter.buyItemId = order.itemId();
		hunter.storeTries = 0;
		hunter.lastStoreSession = now;
		hunter.phaseUntil = now + (Rnd.get(PhantomHuntersConfig.HUNTER_STORE_MIN_MINUTES, PhantomHuntersConfig.HUNTER_STORE_MAX_MINUTES) * 60000L);
		LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " opens a buy store in Giran: " + order.name() + " for " + order.price() + " (shop " + order.shopPrice() + "); " + player.getAdena() + " adena.");
		return true;
	}

	/**
	 * Свернуть лавку; что не продалось — отметить (после двух лавок уйдёт NPC). Скупка: продали — надеть купленное.
	 */
	private static void closeStore(Hunter hunter, Player player)
	{
		if (!hunter.storeOpen)
		{
			return;
		}

		hunter.storeOpen = false;
		if (hunter.buyItemId > 0)
		{
			final int itemId = hunter.buyItemId;
			hunter.buyItemId = 0;
			final boolean bought = player.getBuyList().getItemCount() == 0;
			player.getBuyList().clear();
			if (player.getPrivateStoreType() != PrivateStoreType.NONE)
			{
				player.setPrivateStoreType(PrivateStoreType.NONE);
				player.broadcastUserInfo();
			}
			if (player.isSitting())
			{
				player.standUp();
			}
			if (bought)
			{
				LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " got " + ItemData.getInstance().getTemplate(itemId).getName() + " in the buy store" + (PhantomHunterOutfit.equipBought(player, itemId) ? " and put it on" : "") + "; " + player.getAdena() + " adena.");
			}
			return;
		}
		for (Item item : PhantomHunterOutfit.storeGoods(player))
		{
			if (player.getSellList().getItems().stream().anyMatch(trade -> trade.getObjectId() == item.getObjectId()))
			{
				hunter.unsold.merge(item.getObjectId(), 1, Integer::sum);
			}
		}
		player.getSellList().clear();
		if (player.getPrivateStoreType() != PrivateStoreType.NONE)
		{
			player.setPrivateStoreType(PrivateStoreType.NONE);
			player.broadcastUserInfo();
		}
		if (player.isSitting())
		{
			player.standUp();
		}
	}

	/**
	 * Характер в цене: 0,8–1,5 от цены магазина, у каждого своя (по имени).
	 */
	private static double priceFactor(PhantomTemplate template)
	{
		return 0.8 + ((Math.abs(template.getName().toLowerCase().hashCode()) % 71) / 100.0);
	}

	// ---------- Пати по крику ----------

	/**
	 * Живой игрок написал в общий, торговый чат или крикнул (из {@code ChatGeneral}, {@code ChatTrade}, {@code ChatShout}): если это «ищу людей», свободные охотники его уровня и нужной роли просятся в пати.
	 * @param shouter кто написал
	 * @param type канал
	 * @param text что написал
	 * @return {@code true}, если кто-то откликнулся
	 */
	public boolean onLfm(Player shouter, ChatType type, String text)
	{
		return !lfm(shouter, type, text, false).isEmpty();
	}

	/**
	 * @param test самопроверка {@code HuntersSelfTest = LFM}: кричит сам охотник
	 * @return кто откликнулся
	 */
	private List<Hunter> lfm(Player shouter, ChatType type, String text, boolean test)
	{
		if (!PhantomHuntersConfig.HUNTER_LFM || !_wanted || (shouter == null) || (text == null) || (!test && PhantomManager.getInstance().isPhantom(shouter)) || !LFM.matcher(text).find())
		{
			return List.of();
		}

		final boolean healer = WANT_HEALER.matcher(text).find();
		final boolean tank = WANT_TANK.matcher(text).find();
		final boolean damage = WANT_DAMAGE.matcher(text).find();
		if (!healer && !tank && !damage && !WANT_PARTY.matcher(text).find() && !text.toLowerCase().contains("лфм") && !text.toLowerCase().contains("lfm"))
		{
			return List.of(); // «нужен совет» — не про пати
		}

		if (shouter.isInParty() && (!shouter.getParty().isLeader(shouter) || (shouter.getParty().getMemberCount() >= 9)))
		{
			return List.of();
		}

		final long now = System.currentTimeMillis();
		final Long until = _lfmCooldowns.get(shouter.getObjectId());
		if ((until != null) && (now < until))
		{
			return List.of();
		}

		final List<Hunter> candidates = new ArrayList<>();
		for (Hunter hunter : _hunters.values())
		{
			final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
			if ((player == null) || (player == shouter) || (hunter.phase != Phase.HUNT) || player.isDead() || (hunter.enemyId != 0) || (Math.abs(player.getLevel() - shouter.getLevel()) > LFM_LEVEL_GAP) || PhantomRelations.getInstance().isEnemy(player, shouter))
			{
				continue;
			}

			final PhantomTemplate.Role role = hunter.template.getRole();
			final boolean fits = (!healer && !tank && !damage) || (healer && (role == PhantomTemplate.Role.HEALER)) || (tank && (role == PhantomTemplate.Role.TANK)) || (damage && (role == PhantomTemplate.Role.DAMAGE));
			if (!fits || ((type == ChatType.GENERAL) && (player.calculateDistance2D(shouter) > 1250)))
			{
				continue;
			}
			candidates.add(hunter);
		}

		if (candidates.isEmpty())
		{
			return List.of();
		}

		_lfmCooldowns.put(shouter.getObjectId(), now + LFM_COOLDOWN);
		Collections.shuffle(candidates);
		// Названы роли — по одному на каждую, не названы — двое любых.
		final int wanted = (healer ? 1 : 0) + (tank ? 1 : 0) + (damage ? 1 : 0);
		final List<Hunter> recruited = new ArrayList<>(candidates.subList(0, Math.min(candidates.size(), wanted > 0 ? wanted : 2)));
		for (Hunter hunter : recruited)
		{
			final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
			hunter.recruiterId = shouter.getObjectId();
			hunter.recruitArrived = false;
			hunter.recruitArriveAt = now + Rnd.get(8000, 25000);
			hunter.phaseUntil = now + RECRUIT_WAIT;
			hunter.phase = Phase.RECRUIT;
			final String who = player.getPlayerClass().name().replace('_', ' ').toLowerCase() + " " + player.getLevel();
			PhantomChat.getInstance().hunterEvent(player, shouter, "LFM", shouter.getName() + " (" + shouter.getLevel() + " ур.) пишет в чат: «" + text + "». Ты свободен и подходишь — напиши ему в личку коротко, что идёшь к нему в пати (ты " + who + ")", "го, я " + who + ", кинь пати", 60000, true);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " answers the LFM of " + shouter.getName() + ".");
		}
		return recruited;
	}

	/**
	 * Самопроверка пати по крику без клиента ({@code HuntersSelfTest = LFM}, один раз на каждое новое {@code HuntersSelfTestId}): охотник «кричит» «лфм нужен дд», через 40 с — инвайт тому, кто откликнулся, через 100 с — отпустить. Каждый шаг — в лог с пометкой SelfTest.
	 */
	private void selfTest()
	{
		final int done = GlobalVariablesManager.getInstance().getInt(SELF_TEST_VARIABLE, 0);
		if (PhantomHuntersConfig.HUNTERS_SELF_TEST_ID == done)
		{
			return;
		}

		// Окнам нужен зритель: ждём, пока в мире появится хоть один охотник.
		final boolean ui = "UI".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST);
		final List<HunterView> views = ui ? views() : List.of();
		if (ui && views.isEmpty())
		{
			return;
		}

		GlobalVariablesManager.getInstance().set(SELF_TEST_VARIABLE, PhantomHuntersConfig.HUNTERS_SELF_TEST_ID);
		GlobalVariablesManager.getInstance().storeMe();
		if ("STRATEGY".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST))
		{
			strategySelfTest();
			return;
		}
		if (ui)
		{
			uiSelfTest(views.get(0).player);
			return;
		}
		// Эпик без клиента (раздел 4.43): живого в рейде играет охотник.
		if ("EPIC".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST))
		{
			PhantomRaid.getInstance().selfTestEpic();
			return;
		}
		// Танатос сразу (раздел 4.45).
		if ("THANATOS".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST))
		{
			PhantomThanatos.getInstance().selfTest();
			return;
		}
		if (!"LFM".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST))
		{
			return;
		}

		// Кричит тот, у кого больше всего охотников-дамагеров рядом по уровню.
		Player shouter = null;
		int best = 0;
		for (Hunter hunter : _hunters.values())
		{
			final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
			if ((player == null) || (hunter.phase != Phase.HUNT) || player.isDead() || player.isInParty())
			{
				continue;
			}

			int peers = 0;
			for (Hunter other : _hunters.values())
			{
				final Player peer = PhantomManager.getInstance().getPhantom(other.template);
				if ((peer != null) && (peer != player) && (other.phase == Phase.HUNT) && (other.template.getRole() == PhantomTemplate.Role.DAMAGE) && (Math.abs(peer.getLevel() - player.getLevel()) <= LFM_LEVEL_GAP))
				{
					peers++;
				}
			}
			if (peers > best)
			{
				best = peers;
				shouter = player;
			}
		}

		if (shouter == null)
		{
			LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: no free hunters to test with.");
			return;
		}

		final Player leader = shouter;
		final List<Hunter> recruited = lfm(leader, ChatType.SHOUT, "лфм нужен дд", true);
		LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: " + leader.getName() + " (" + leader.getLevel() + ") shouts, " + recruited.size() + " answered" + (recruited.isEmpty() ? "." : ": " + recruited.get(0).template.getName() + "."));
		if (recruited.isEmpty())
		{
			return;
		}

		final Hunter hunter = recruited.get(0);
		ThreadPool.schedule(() ->
		{
			final Player member = PhantomManager.getInstance().getPhantom(hunter.template);
			if (member == null)
			{
				LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: " + hunter.template.getName() + " left the world.");
				return;
			}

			LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: " + member.getName() + " is " + hunter.phase + ", " + (int) member.calculateDistance2D(leader) + " from " + leader.getName() + "; inviting.");
			PhantomCompanionManager.getInstance().onInvited(leader, member, null);
			LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: after invite " + member.getName() + " is " + hunter.phase + ", companion " + PhantomCompanionManager.getInstance().isCompanion(hunter.template) + ", party " + (member.isInParty() ? member.getParty().getLeader().getName() + " x" + member.getParty().getMemberCount() : "none") + ", level " + member.getLevel() + ".");
		}, 40000);
		ThreadPool.schedule(() ->
		{
			final Player member = PhantomManager.getInstance().getPhantom(hunter.template);
			if (member == null)
			{
				return;
			}

			LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: after 1 min in party " + member.getName() + " is " + (int) member.calculateDistance2D(leader) + " from " + leader.getName() + ", " + hunter.phase + "; releasing.");
			PhantomCompanionManager.getInstance().release(hunter.template, false);
		}, 100000);
		ThreadPool.schedule(() ->
		{
			final Player member = PhantomManager.getInstance().getPhantom(hunter.template);
			LOGGER.info(getClass().getSimpleName() + ": SelfTest LFM: done, " + hunter.template.getName() + " is " + hunter.phase + (member != null ? ", party " + member.isInParty() + ", spot " + (hunter.spot != null ? hunter.spot.name : "none") : "") + ".");
		}, 115000);
	}

	/**
	 * Позвали криком: через несколько секунд — рядом с позвавшим (как телепорт к нему), ждёт инвайта; не дождался — назад.
	 */
	private void recruit(Hunter hunter, Player player, long now)
	{
		final Player recruiter = World.getPlayer(hunter.recruiterId);
		if ((recruiter == null) || !recruiter.isOnline() || (now >= hunter.phaseUntil) || (recruiter.isInParty() && (recruiter.getParty().getMemberCount() >= 9)))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " got no invite, back to hunting.");
			backToHuntNearby(hunter, player);
			return;
		}

		if (!hunter.recruitArrived)
		{
			if ((now < hunter.recruitArriveAt) || (recruiter.getInstanceId() != 0) || recruiter.isTeleporting())
			{
				return;
			}

			hunter.recruitArrived = true;
			stop(player);
			AutoUseTaskManager.getInstance().stopAutoUseTask(player);
			if (player.isSitting())
			{
				player.standUp();
			}
			if (player.calculateDistance2D(recruiter) > 1500)
			{
				// Рядом, но не в нём: несколько точек вокруг по геодате, ни одна не годится — к нему самому.
				Location near = new Location(recruiter.getX(), recruiter.getY(), recruiter.getZ());
				for (int i = 0; i < 8; i++)
				{
					final double angle = Rnd.nextDouble() * 2 * Math.PI;
					final int r = Rnd.get(120, 250);
					final int x = recruiter.getX() + (int) (Math.cos(angle) * r);
					final int y = recruiter.getY() + (int) (Math.sin(angle) * r);
					final int z = GeoEngine.getInstance().getHeight(x, y, recruiter.getZ());
					if (PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), recruiter.getX(), recruiter.getY(), recruiter.getZ(), x, y, z, recruiter.getInstanceId()))
					{
						near = new Location(x, y, z);
						break;
					}
				}
				player.teleToLocation(near, false);
			}
			return;
		}

		// Позвавший отошёл — подбежать, но остановиться в паре шагов от него, а не в нём.
		final double distance = player.calculateDistance2D(recruiter);
		if ((distance > 400) && !player.isMoving())
		{
			final double k = (distance - 120) / distance;
			final int x = player.getX() + (int) ((recruiter.getX() - player.getX()) * k);
			final int y = player.getY() + (int) ((recruiter.getY() - player.getY()) * k);
			player.setRunning();
			player.getAI().setIntentionMoveTo(new Location(x, y, GeoEngine.getInstance().getHeight(x, y, recruiter.getZ())));
		}
	}

	/**
	 * Инвайт охотнику (из {@link PhantomCompanionManager#onInvited}): позвал криком именно он — да; свободен, уровень близкий, не обидчик — тоже да.
	 * @param template охотник
	 * @param requestor кто приглашает
	 * @return пойдёт ли в пати
	 */
	public boolean acceptsInvite(PhantomTemplate template, Player requestor)
	{
		final Hunter hunter = _hunters.get(template.getName().toLowerCase());
		final Player player = PhantomManager.getInstance().getPhantom(template);
		if ((hunter == null) || (player == null) || player.isDead())
		{
			return false;
		}

		if (hunter.phase == Phase.RECRUIT)
		{
			return hunter.recruiterId == requestor.getObjectId();
		}
		return (hunter.phase == Phase.HUNT) && (hunter.enemyId == 0) && (Math.abs(player.getLevel() - requestor.getLevel()) <= LFM_LEVEL_GAP) && !PhantomRelations.getInstance().isEnemy(player, requestor) && !PhantomDuel.getInstance().isBusy(player);
	}

	/**
	 * Охотник вступил в пати: дальше его ведёт {@link PhantomCompanionManager}, AutoPlay выключен.
	 * AutoUse работает (патч 0027): без AutoPlay он не бьёт скиллами, но пьёт банки; скиллы в бою кастует менеджер напарников.
	 * @param template охотник
	 */
	public void joinedParty(PhantomTemplate template)
	{
		final Hunter hunter = _hunters.get(template.getName().toLowerCase());
		final Player player = PhantomManager.getInstance().getPhantom(template);
		if ((hunter == null) || (player == null))
		{
			return;
		}

		hunter.phase = Phase.PARTY;
		hunter.together.clear();
		hunter.togetherAwards.clear();
		hunter.togetherTick = 0;
		hunter.recruiterId = 0;
		hunter.gatekeeperSpot = null;
		closeStore(hunter, player);
		AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		AutoUseTaskManager.getInstance().startAutoUseTask(player);
		if (player.isSitting())
		{
			player.standUp();
		}
	}

	// ---------- Мозги-стратег ----------

	/**
	 * Мозги-стратег на споте: пришло решение — выполнить (смену спота и город — между боями, как свиток у игрока); пора думать — спросить модель. Ответ приходит асинхронно и выполняется на одном из следующих тиков.
	 * @return {@code true}, если охотник по решению ушёл со спота и правилам на этом тике делать нечего
	 */
	private boolean strategy(Hunter hunter, Player player, long now)
	{
		// Записан на PvP-ивент — стратег не уводит его со спота (раздел 4.33).
		if (PhantomEvents.getInstance().isBusy(player))
		{
			return false;
		}
		final Choice choice = hunter.choice;
		if (choice != null)
		{
			if ((now - choice.at) > PhantomHunterStrategy.CHOICE_TTL)
			{
				hunter.choice = null;
				LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " drops " + choice + ", no chance to do it.");
				return false;
			}

			final boolean anytime = (choice.decision == Decision.HUNT) || (choice.decision == Decision.PARTY);
			if (!anytime && (player.isInCombat() || player.isAttackingNow() || player.isCastingNow()))
			{
				return false;
			}

			hunter.choice = null;
			return apply(hunter, player, choice, now);
		}

		if ((hunter.thinkingSince > 0) && ((now - hunter.thinkingSince) > THINK_TIMEOUT))
		{
			hunter.thinkingSince = 0;
		}
		// «Разум 2.0»: охотник в фокусе при HunterMindExtended думает чаще — подтянуть срок, если он дальше.
		final long focusGap = PhantomFocus.getInstance().strategyGap(player);
		if ((focusGap > 0) && (hunter.thinkingSince == 0) && (hunter.nextThink > (now + focusGap)))
		{
			hunter.nextThink = now + focusGap;
		}
		if ((hunter.thinkingSince == 0) && (now >= hunter.nextThink))
		{
			ask(hunter, player, now);
		}
		return false;
	}

	/**
	 * Может ли стратег думать сейчас: включён, мозги включены, а при {@code HunterStrategyWhen = WITH_PLAYERS} в игре есть живой игрок (или идёт самопроверка STRATEGY).
	 */
	private boolean strategyOn(long now)
	{
		return PhantomHuntersConfig.HUNTER_STRATEGY && PhantomChat.brainEnabled() && ("ALWAYS".equals(PhantomHuntersConfig.HUNTER_STRATEGY_WHEN) || (_realPlayers > 0) || (now < _strategyTestUntil));
	}

	/**
	 * Спросить модель, что делать дальше: состояние охотника и доступные варианты — в brain.py ({@code "kind":"strategy"}). Бюджет исчерпан, стратег выключен, ответа нет — решают правила.
	 */
	private void ask(Hunter hunter, Player player, long now)
	{
		if (!strategyOn(now))
		{
			// Без живых игроков не думает; войдёт игрок — охотники начнут думать вразнобой, а не все разом.
			hunter.nextThink = now + Rnd.get(60000L, Math.max(60000L, PhantomHunterStrategy.nextDelay()));
			return;
		}

		if (!PhantomHunterStrategy.budget(now))
		{
			hunter.nextThink = now + Rnd.get(60000, 180000);
			if ((now - _lastStrategyLog) >= STRATEGY_LOG_INTERVAL)
			{
				_lastStrategyLog = now;
				LOGGER.info(getClass().getSimpleName() + ": Strategy: budget " + PhantomHuntersConfig.HUNTER_STRATEGY_PER_MINUTE + " per minute is spent, rules decide (" + player.getName() + " asks later).");
			}
			return;
		}

		final long focusGap = PhantomFocus.getInstance().strategyGap(player);
		hunter.nextThink = now + (focusGap > 0 ? focusGap : PhantomHunterStrategy.nextDelay());
		hunter.thinkingSince = now;
		final Facts facts = facts(hunter, player, now);
		final Map<Decision, String> options = options(hunter, player, now);
		FakePlayerBrain.getInstance().send(PhantomHunterStrategy.request(player, hunter.template, facts, options), reply ->
		{
			hunter.thinkingSince = 0;
			final Choice choice = PhantomHunterStrategy.parse(reply, options.keySet(), System.currentTimeMillis());
			if (choice == null)
			{
				LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " got garbage from the brain («" + reply + "»), rules decide.");
				return;
			}

			hunter.lastChoice = choice;
			hunter.choice = choice;
			LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " (" + player.getLevel() + ", " + spotLabel(hunter.spot) + ") decides " + choice + ": «" + choice.thought + "»" + (facts.test.isEmpty() ? "." : " [SelfTest " + facts.test + "]."));
		}, () ->
		{
			hunter.thinkingSince = 0;
			final long time = System.currentTimeMillis();
			if (!facts.test.isEmpty() || ((time - _lastStrategyFailLog) >= STRATEGY_LOG_INTERVAL))
			{
				_lastStrategyFailLog = time;
				LOGGER.info(getClass().getSimpleName() + ": Strategy: " + player.getName() + " got no decision (brain failed or answered garbage), rules decide" + (facts.test.isEmpty() ? "." : " [SelfTest " + facts.test + "]."));
			}
		}, PhantomFocus.getInstance().of(player) != null ? PhantomChat.FOCUS_TIMEOUT : 0);
	}

	/**
	 * Что о нём знает менеджер — для запроса стратегу: сколько на споте и сколько там набрал, простой, смерти за час, давно ли был в городе и в лавке.
	 */
	private Facts facts(Hunter hunter, Player player, long now)
	{
		final Facts facts = new Facts();
		facts.spot = hunter.spot;
		facts.spotMinutes = hunter.spotSince > 0 ? (int) ((now - hunter.spotSince) / 60000) : 0;
		facts.spotExpPercent = hunter.spotSince > 0 ? PhantomHunterStrategy.expGainPercent(player, hunter.spotExp) : 0;
		facts.idleMinutes = (int) ((now - hunter.lastActive) / 60000);
		facts.roams = hunter.roams;
		while (!hunter.deaths.isEmpty() && ((now - hunter.deaths.peekFirst()) > 3600000))
		{
			hunter.deaths.pollFirst();
		}
		facts.deathsHour = hunter.deaths.size();
		facts.townMinutes = (int) ((now - hunter.lastTown) / 60000);
		facts.storeMinutes = hunter.lastStoreSession > 0 ? (int) ((now - hunter.lastStoreSession) / 60000) : -1;
		facts.realPlayers = _realPlayers;
		if (now < _strategyTestUntil)
		{
			// Самопроверка: первый ответ — мусор (откат на правила), следующие шесть — случайный вариант без модели (выполнение).
			final int n = _strategyTestCount++;
			facts.test = n == 0 ? "garbage" : n <= 6 ? "random" : "";
		}
		return facts;
	}

	/**
	 * Что стратегу можно выбрать сейчас, с пометкой для модели: смены спота — если есть куда, лавка — если есть что продать и место в Гиране, пати — если крик кто-то услышит и не было крика недавно.
	 */
	private Map<Decision, String> options(Hunter hunter, Player player, long now)
	{
		final Map<Decision, String> options = new LinkedHashMap<>();
		options.put(Decision.HUNT, hunter.spot != null ? spotNote(hunter.spot) : "");
		hunter.upSpot = shiftedSpot(hunter, player, true);
		if (hunter.upSpot != null)
		{
			options.put(Decision.SPOT_UP, spotNote(hunter.upSpot));
		}
		hunter.downSpot = shiftedSpot(hunter, player, false);
		if (hunter.downSpot != null)
		{
			options.put(Decision.SPOT_DOWN, spotNote(hunter.downSpot));
		}
		options.put(Decision.TOWN, MapRegionData.getInstance().getClosestTownName(player));
		final String drop = PhantomHunterStrategy.drop(player);
		if (!drop.isEmpty() && (stores() < PhantomHuntersConfig.HUNTER_MAX_STORES))
		{
			options.put(Decision.STORE, drop);
		}
		if (((_realPlayers > 0) || (now < _strategyTestUntil)) && ((now - hunter.lastLfp) >= LFP_COOLDOWN) && ((now - _lastLfp) >= LFP_GLOBAL_COOLDOWN))
		{
			options.put(Decision.PARTY, player.getLevel() >= 20 ? "крикнуть в торговый чат" : "крикнуть в общий чат рядом (торговый — с 20 ур.)");
		}
		options.put(Decision.AFK, "");
		// Рейд по решению стратега (раздел 4.32): рейда нет, под него есть свободный РБ и команда с хилером.
		final String raid = PhantomRaid.getInstance().offer(player, now);
		if (raid != null)
		{
			options.put(Decision.RAID, raid);
		}
		return options;
	}

	/**
	 * Спот для смены по решению стратега. Выше — мобы сильнее нынешних, но не больше чем на 3 уровня выше своего; ниже — слабее нынешних, но не перерос.
	 * В первую очередь свободные, до {@link #HOME_LEVEL} — в родной зоне, с него — вне стартовых зон (патч 0039), ближе по уровню к нынешнему.
	 * @return спот или {@code null}, если некуда
	 */
	private HuntSpot shiftedSpot(Hunter hunter, Player player, boolean up)
	{
		final HuntSpot current = hunter.spot;
		if (current == null)
		{
			return null;
		}

		final int level = player.getLevel();
		final String home = level < HOME_LEVEL ? HOME_REGIONS.get(player.getRace()) : null;
		final List<HuntSpot> best = new ArrayList<>();
		int bestScore = Integer.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot == current) || outgrown(spot, level) || (up ? ((spot.level <= current.level) || (spot.level > (level + 3))) : ((spot.level >= current.level) || (spot.level < (level - 6)))))
			{
				continue;
			}

			// Меньше — лучше: занятое место, чужая зона до HOME_LEVEL или стартовая зона после, дальше по уровню от нынешнего.
			final boolean offRoute = (home != null) ? !home.equals(spot.region) : HOME_REGIONS.containsValue(spot.region);
			final int score = (crowd(spot, player) >= PhantomHuntersConfig.HUNTERS_PER_SPOT ? 1000 : 0) + (offRoute ? 100 : 0) + Math.abs(spot.level - current.level);
			if (score < bestScore)
			{
				bestScore = score;
				best.clear();
			}
			if (score == bestScore)
			{
				best.add(spot);
			}
		}
		return best.isEmpty() ? null : best.get(Rnd.get(best.size()));
	}

	/**
	 * Выполнить решение стратега через фазы охотника. Не вышло (некуда, нечего продавать) — остаётся на споте, дальше правила.
	 * @return {@code true}, если охотник ушёл со спота
	 */
	private boolean apply(Hunter hunter, Player player, Choice choice, long now)
	{
		final String who = getClass().getSimpleName() + ": Strategy: " + player.getName() + " (" + player.getLevel() + ") " + choice;
		thinkAloud(player, choice);
		switch (choice.decision)
		{
			case SPOT_UP:
			case SPOT_DOWN:
			{
				final HuntSpot target = choice.decision == Decision.SPOT_UP ? hunter.upSpot : hunter.downSpot;
				if ((target == null) || outgrown(target, player.getLevel()))
				{
					LOGGER.info(who + ": no such spot now, keeps hunting.");
					return false;
				}

				LOGGER.info(who + ": " + spotLabel(hunter.spot) + " -> " + spotLabel(target) + ".");
				moveToSpot(hunter, player, target);
				return true;
			}
			case TOWN:
			{
				if ((now - hunter.lastTown) < TOWN_COOLDOWN)
				{
					LOGGER.info(who + ": was in town a minute ago, keeps hunting.");
					return false;
				}

				LOGGER.info(who + ": goes to town.");
				goToTown(hunter, player, now);
				return true;
			}
			case STORE:
			{
				if (PhantomHunterOutfit.storeGoods(player).isEmpty() || (stores() >= PhantomHuntersConfig.HUNTER_MAX_STORES))
				{
					LOGGER.info(who + ": nothing to sell or no place in Giran, keeps hunting.");
					return false;
				}

				hunter.wantStore = true;
				LOGGER.info(who + ": town, then a store in Giran.");
				goToTown(hunter, player, now);
				return true;
			}
			case PARTY:
			{
				final String text = String.format(LFP_SHOUTS[Rnd.get(LFP_SHOUTS.length)], roleWord(hunter.template, player), player.getLevel());
				final String channel = PhantomChat.getInstance().hunterShout(player, text);
				hunter.lastLfp = now;
				hunter.lfpUntil = now + LFP_WINDOW;
				_lastLfp = now;
				LOGGER.info(who + ": shouts to " + channel + " chat «" + text + "», keeps hunting and waits for an invite.");
				return false;
			}
			case RAID:
			{
				if (PhantomRaid.getInstance().request(player, now))
				{
					return true;
				}
				LOGGER.info(who + ": wanted to lead a raid, but it is not possible any more.");
				return false;
			}
			case AFK:
			{
				hunter.afkUntil = now + (choice.minutes * 60000L);
				LOGGER.info(who + ": rests in town for " + choice.minutes + " min.");
				goToTown(hunter, player, now);
				return true;
			}
			default:
			{
				LOGGER.info(who + ": keeps hunting at " + spotLabel(hunter.spot) + ".");
				return false;
			}
		}
	}

	/**
	 * Мысль вслух в общий чат рядом — изредка ({@link PhantomHuntersConfig#HUNTER_STRATEGY_SAY_CHANCE}), только если рядом есть живой игрок и вокруг тихо ({@link PhantomQuiet}).
	 */
	private static void thinkAloud(Player player, Choice choice)
	{
		if (choice.thought.isEmpty() || (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_STRATEGY_SAY_CHANCE))
		{
			return;
		}

		if (World.getFirstVisibleObject(player, Player.class, other -> !PhantomManager.getInstance().isPhantom(other) && (player.calculateDistance2D(other) < 1250)) != null)
		{
			PhantomChat.getInstance().hunterSay(player, choice.thought);
		}
	}

	/**
	 * @return кем он себя называет в крике «лфп»
	 */
	private static String roleWord(PhantomTemplate template, Player player)
	{
		switch (template.getRole())
		{
			case HEALER:
			{
				return "хил";
			}
			case BUFFER:
			{
				return "баффер";
			}
			case TANK:
			{
				return "танк";
			}
			default:
			{
				return PhantomHunterOutfit.ammoId(player) > 0 ? "лук" : player.isMageClass() ? "маг" : "дд";
			}
		}
	}

	private int stores()
	{
		int stores = 0;
		for (Hunter other : _hunters.values())
		{
			if (other.phase == Phase.STORE)
			{
				stores++;
			}
		}
		return stores;
	}

	private static String spotLabel(HuntSpot spot)
	{
		return spot == null ? "no spot" : spot.name + " (" + spot.min + "-" + spot.max + ")";
	}

	private static String spotNote(HuntSpot spot)
	{
		return spot.name + " (мобы " + spot.min + "-" + spot.max + " ур.)";
	}

	/**
	 * Самопроверка стратега без клиента ({@code HuntersSelfTest = STRATEGY}): {@value #STRATEGY_TEST_DURATION} мс стратег думает и без живых игроков, все охотники спрашивают в ближайшие 3 минуты
	 * (в пределах бюджета в минуту). Первый ответ — нарочно мусор (проверка отката на правила), следующие шесть — случайный вариант без модели (проверка выполнения), остальные — модель.
	 */
	private void strategySelfTest()
	{
		final long now = System.currentTimeMillis();
		_strategyTestUntil = now + STRATEGY_TEST_DURATION;
		_strategyTestCount = 0;
		for (Hunter hunter : _hunters.values())
		{
			hunter.nextThink = now + Rnd.get(5000, 180000);
		}
		LOGGER.info(getClass().getSimpleName() + ": SelfTest STRATEGY: " + _hunters.size() + " hunters ask the strategist within 3 minutes, test runs " + (STRATEGY_TEST_DURATION / 60000) + " minutes" + (PhantomHuntersConfig.HUNTER_STRATEGY && PhantomChat.brainEnabled() ? "." : ", but the strategist or the brains are off."));
	}

	// ---------- ПвП ----------

	/**
	 * Охотника ударил живой игрок (или его питомец): он — враг, охотник даёт сдачи. Флаг ставит сам сервер, когда охотник бьёт в ответ.
	 */
	private void onDamaged(Hunter hunter, Player player, Creature attacker)
	{
		// Моб добивает охотника — убьёт его живой игрок, значит «спас» (патч 0032).
		PhantomRelations.getInstance().threat(attacker, player);
		final Player enemy = actingPlayer(attacker);
		// Напал живой игрок или охотник (махач, патч 0037); напарники из .buddy — нет.
		final boolean hunterEnemy = (enemy != null) && PhantomRelations.isHunter(enemy);
		// Танатос (раздел 4.45) — тоже враг: фантом, но не напарник.
		final boolean reaper = PhantomThanatos.is(enemy);
		if ((enemy == null) || (enemy == player) || (PhantomManager.getInstance().isPhantom(enemy) && !hunterEnemy && !reaper) || player.isDead() || (hunter.phase == Phase.PARTY) || (player.isInDuel() && enemy.isInDuel() && (player.getDuelId() == enemy.getDuelId())))
		{
			return;
		}

		// ПК или Танатос напал (раздел 4.44): слабый или трус не отвечает, а убегает — тогда убийство даст ПК карму.
		if (((PhantomCompat.karma(enemy) > 0) || reaper) && (hunter.enemyId != enemy.getObjectId()) && PhantomPk.getInstance().fleeFromAttacker(player, enemy))
		{
			return;
		}

		final boolean fresh = hunter.enemyId != enemy.getObjectId();
		hunter.enemyId = enemy.getObjectId();
		hunter.enemySeen = System.currentTimeMillis();
		if (fresh)
		{
			PhantomRelations.getInstance().record(player, enemy, PhantomRelations.Kind.ATTACKED);
			final int grudge = grudge(player, enemy);
			// Охотник на охотника — вслух, только если есть кому слышать.
			if (!hunterEnemy || PhantomBanter.audience(player))
			{
				PhantomChat.getInstance().hunterEvent(player, enemy, "ATTACKED", "на тебя напал " + describe(enemy) + (grudge > 0 ? ", который уже убивал тебя (" + grudge + " раз)" : "") + ", ты даёшь сдачи", "ты чё творишь?!", 180000);
			}
		}
	}

	/**
	 * «Сорян» (патч 0037): охотник уступает спот тому, у кого отжал моба, — ногами на ближайший другой спот по силам; рядом такого нет — остаётся.
	 * @param player охотник
	 */
	void yieldSpot(Player player)
	{
		// Ответ модели приходит из чужого потока — сам уход делает тик охотника.
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter != null)
		{
			hunter.yieldRequested = true;
		}
	}

	private static void yieldNow(Hunter hunter, Player player)
	{
		hunter.yieldRequested = false;
		if ((hunter.phase != Phase.HUNT) || (hunter.enemyId != 0) || player.isDead())
		{
			return;
		}

		final HuntSpot other = nearestSpot(player, hunter.spot);
		if ((other == null) || (other == hunter.spot))
		{
			LOGGER.info(PhantomHunterManager.class.getSimpleName() + ": " + player.getName() + " has no other spot nearby to yield to.");
			return;
		}
		walkTo(hunter, player, other);
	}

	/**
	 * Махач (патч 0037, {@link PhantomBrawl} решил): угроза вслух и первый удар — как ctrl+клик у игрока: AutoPlay не бьёт того, у кого нет флага.
	 * Дальше {@link #fight}: ответил и флагнулся — ПвП до конца, нет — зачинщик отходит.
	 * @param player зачинщик
	 * @param enemy враг
	 * @param why что случилось — для угрозы
	 */
	void startBrawl(Player player, Player enemy, String why)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter == null)
		{
			return;
		}

		final long now = System.currentTimeMillis();
		hunter.enemyId = enemy.getObjectId();
		hunter.enemySeen = now;
		hunter.brawlAggressor = true;
		hunter.brawlSince = now;
		if (player.isSitting())
		{
			player.standUp();
		}
		AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		player.setTarget(enemy);
		player.getAI().setIntentionAttack(enemy);
		if (PhantomBanter.audience(player))
		{
			PhantomChat.getInstance().hunterEvent(player, enemy, "BRAWL", "ты больше не можешь терпеть " + describe(enemy) + ": " + why + ". Ты на него нападаешь — скажи это ему в лицо", "всё, достал!", 600000);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " attacks " + enemy.getName() + " (" + why + ").");
	}

	/**
	 * Кровь за кровь (раздел 4.44, {@link PhantomPk} решил): нападение на живого обидчика — как махач ({@link #fight}), но не отходит, а добивает;
	 * белого — с кармой, её даёт Mobius.
	 * @param player охотник
	 * @param victim обидчик
	 */
	void startRevenge(Player player, Player victim)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter == null)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		hunter.enemyId = victim.getObjectId();
		hunter.enemySeen = now;
		hunter.brawlAggressor = true;
		hunter.brawlSince = now;
		hunter.revenge = true;
		if (player.isSitting())
		{
			player.standUp();
		}
		AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		player.setTarget(victim);
		player.getAI().setIntentionAttack(victim);
		PhantomChat.getInstance().hunterEvent(player, victim, "REVENGE", "мимо проходит " + describe(victim) + ", который " + (grudge(player, victim) > 0 ? "убивал тебя (" + grudge(player, victim) + " раз)" : "нападал на тебя") + ". Кровь за кровь — ты нападаешь первым, скажи ему это", "кровь за кровь!", 600000);
	}

	/**
	 * Бить ПК (раздел 4.44): драка, как в ответ на нападение ({@link #fight}): AutoPlay по персонажам, цель — ПК (его бьют без кармы).
	 * @return начал (на каче и ни с кем не дерётся)
	 */
	boolean engagePk(Player player, Player pk)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if ((hunter == null) || (hunter.phase != Phase.HUNT) || (hunter.enemyId != 0) || player.isDead())
		{
			return false;
		}
		hunter.enemyId = pk.getObjectId();
		hunter.enemySeen = System.currentTimeMillis();
		hunter.brawlAggressor = false;
		hunter.revenge = false;
		return true;
	}

	/**
	 * Рейд на эпика или охота на Танатоса (разделы 4.43, 4.45): лидер лёг — рейд ведёт этот охотник, он уже не напарник, а ведущий ({@link PhantomRaid#drive}).
	 */
	void takeRaidLead(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter == null)
		{
			return;
		}
		hunter.phase = Phase.HUNT;
		hunter.enemyId = 0;
		raidStarts(player);
	}

	/**
	 * Охотника убил живой игрок: запомнить обидчика (переменная персонажа, переживает рестарт) и высказаться.
	 */
	private void onKilled(Hunter hunter, Player player, Creature killer)
	{
		hunter.enemyId = 0;
		hunter.brawlAggressor = false;
		hunter.revenge = false;
		// ПК (раздел 4.44) получил по заслугам — в летопись.
		final Player pkKiller = actingPlayer(killer);
		if ((pkKiller != null) && (pkKiller != player) && (PhantomCompat.karma(player) > 0))
		{
			PhantomPk.getInstance().onPkKilled(player, pkKiller);
		}
		// PvP-ивент (раздел 4.33): смерть на арене — спорт, не обида; поднимет ивент, в город не уходит.
		if (player.isOnEvent())
		{
			return;
		}
		final Player enemy = actingPlayer(killer);
		// Убил живой игрок или охотник в махаче (патч 0037).
		final boolean hunterEnemy = (enemy != null) && PhantomRelations.isHunter(enemy);
		if ((enemy == null) || (enemy == player) || (PhantomManager.getInstance().isPhantom(enemy) && !hunterEnemy))
		{
			return;
		}

		final int count = addGrudge(player, enemy);
		if (!hunterEnemy || PhantomBanter.audience(player))
		{
			PhantomChat.getInstance().hunterEvent(player, enemy, "KILLED", "тебя убил " + describe(enemy) + (count > 1 ? " — уже " + count + "-й раз" : "") + "; ты лежишь и скоро вернёшься в город", count > 1 ? "опять ты... ну погоди" : "ну ты и гад...", 60000);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " was killed by " + enemy.getName() + " (" + count + ").");
	}

	/**
	 * Драка с обидчиком: AutoPlay переключается на персонажей, цель — он; AutoUse бьёт скиллами. Обидчик ушёл, умер или стал неатакуемым — конец драки.
	 * @return {@code true}, если драка продолжается
	 */
	private boolean fight(Hunter hunter, Player player, long now)
	{
		final Player enemy = World.getPlayer(hunter.enemyId);
		// Охотник гонится за обидчиком, бьёт ПК — пока он рядом (раздел 4.44); за обидчиком — не дольше PhantomPk.CHASE.
		if ((enemy != null) && (hunter.revenge || (PhantomCompat.karma(enemy) > 0)) && (player.calculateDistance2D(enemy) <= 2500))
		{
			hunter.enemySeen = now;
		}
		final boolean gone = (enemy == null) || !enemy.isOnline() || (player.calculateDistance2D(enemy) > 2500) || ((now - hunter.enemySeen) > 60000) || (hunter.revenge && ((now - hunter.brawlSince) > PhantomPk.CHASE));
		final boolean hunterEnemy = (enemy != null) && PhantomRelations.isHunter(enemy);

		// Махач (патч 0037): страх ПК. Зачинщик бьёт только того, кто ответил и флагнулся; не ответил за GRACE или почти мёртв без флага — отойти, белого не добивать.
		final boolean grace = hunter.brawlAggressor && ((now - hunter.brawlSince) < PhantomBrawl.GRACE);
		if (!gone && hunter.brawlAggressor && !hunter.revenge && !enemy.isDead() && (enemy.getPvpFlag() == 0) && (!grace || (enemy.getCurrentHp() < (enemy.getMaxHp() * 0.4))))
		{
			player.abortAttack();
			player.getAI().setIntentionActive();
			if (PhantomBanter.audience(player))
			{
				PhantomChat.getInstance().hunterEvent(player, enemy, "BRAWL_OFF", "ты напал на " + enemy.getName() + ", а он даже не отвечает — бить белого ниже твоего достоинства (и карму брать не хочешь), ты отходишь", "ссыкло, даже не отвечаешь", 600000);
			}
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " backs off " + enemy.getName() + ": no flag.");
			endFight(hunter, player);
			return false;
		}

		if (gone || enemy.isDead() || (!enemy.isAutoAttackable(player) && !grace && !hunter.revenge))
		{
			if ((enemy != null) && enemy.isDead() && !gone && hunter.revenge)
			{
				PhantomPk.getInstance().onRevengeKill(player, enemy);
			}
			else if ((enemy != null) && enemy.isDead() && !gone && (!hunterEnemy || PhantomBanter.audience(player)))
			{
				PhantomChat.getInstance().hunterEvent(player, enemy, "WON", hunter.brawlAggressor ? "ты завалил " + enemy.getName() + " в махаче" : "ты завалил " + enemy.getName() + ", который на тебя напал", "изи)", 180000);
			}
			endFight(hunter, player);
			return false;
		}

		if (player.isSitting())
		{
			player.standUp();
			return true;
		}

		// Пока у врага нет флага, AutoPlay его не бьёт — зачинщик бьёт сам, как ctrl+клик.
		if ((grace || hunter.revenge) && (enemy.getPvpFlag() == 0) && (PhantomCompat.karma(enemy) <= 0))
		{
			if ((player.getTarget() != enemy) || (player.getAI().getIntention() != Intention.ATTACK))
			{
				player.setTarget(enemy);
				player.getAI().setIntentionAttack(enemy);
			}
			return true;
		}

		player.getAutoPlaySettings().setNextTargetMode(2); // персонажи
		if (player.getTarget() != enemy)
		{
			player.setTarget(enemy);
		}
		if (!player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(player);
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		return true;
	}

	private static void endFight(Hunter hunter, Player player)
	{
		hunter.enemyId = 0;
		hunter.brawlAggressor = false;
		hunter.revenge = false;
		player.getAutoPlaySettings().setNextTargetMode(1); // снова мобы
		if ((player.getTarget() instanceof Player))
		{
			player.setTarget(null);
		}
	}

	/**
	 * Враг проходит мимо — охотник его узнаёт (не чаще раза в 15 минут на каждого). Остыла обида или помирились — молчит (патч 0032).
	 */
	private static void noticeGrudges(Player player)
	{
		if (!PhantomRelations.getInstance().hasEnemies(player))
		{
			return;
		}

		for (Player other : World.getVisibleObjectsInRange(player, Player.class, 700))
		{
			if (PhantomManager.getInstance().isPhantom(other) || other.isInvisible() || !PhantomRelations.getInstance().isEnemy(player, other))
			{
				continue;
			}

			final int count = grudge(player, other);
			PhantomChat.getInstance().hunterEvent(player, other, "GRUDGE", "мимо проходит " + describe(other) + ", который " + (count > 0 ? "убивал тебя (" + count + " раз)" : "нападал на тебя") + " — ты его узнал", "о, опять ты...", 900000);
		}
	}

	/**
	 * @return сколько раз этот игрок убивал охотника ({@link PhantomRelations}); враг ли он — {@link PhantomRelations#isEnemy}
	 */
	static int grudge(Player hunter, Player other)
	{
		return PhantomRelations.getInstance().kills(hunter, other);
	}

	private static int addGrudge(Player hunter, Player other)
	{
		return PhantomRelations.getInstance().record(hunter, other, PhantomRelations.Kind.KILLED);
	}

	/**
	 * Рядом с трупом охотника живой игрок, не враг, или охотник-хилер с ресом (патч 0033): может реснуть — охотник лежит дольше ({@link PhantomHuntersConfig#HUNTER_REVIVE_WAIT}).
	 */
	private static boolean reviverNear(Player player)
	{
		return World.getFirstVisibleObject(player, Player.class, other -> !other.isDead() && !other.isInvisible() && (player.calculateDistance2D(other) < REVIVE_RANGE) && !PhantomRelations.getInstance().isEnemy(player, other) && (!PhantomManager.getInstance().isPhantom(other) || PhantomNeighbors.getInstance().canRevive(other, player))) != null;
	}

	/**
	 * Часы игры охотника: копятся на тике, пока он в мире; сохраняются с персонажем ({@value #PLAYED_VARIABLE}).
	 */
	private static void played(Hunter hunter, Player player, long now)
	{
		if (!hunter.playedLoaded)
		{
			hunter.played = player.getVariables().getLong(PLAYED_VARIABLE, 0) * 1000;
			hunter.playedLoaded = true;
		}
		else if ((hunter.playedTick > 0) && ((now - hunter.playedTick) < (TICK * 5)))
		{
			hunter.played += now - hunter.playedTick;
		}
		hunter.playedTick = now;
	}

	/**
	 * @param player охотник
	 * @return сколько часов он провёл в мире — по ним остывают отношения ({@link PhantomRelations})
	 */
	public double playedHours(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter == null)
		{
			return 0;
		}
		if (!hunter.playedLoaded)
		{
			hunter.played = player.getVariables().getLong(PLAYED_VARIABLE, 0) * 1000;
			hunter.playedLoaded = true;
		}
		return hunter.played / 3600000.0;
	}

	/**
	 * @param player охотник
	 * @return он на каче, свободен и не дерётся с игроком — его можно звать в пати охотников (патч 0036)
	 */
	public boolean isHunting(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		// Ждёт дуэли или дерётся на ней (раздел 4.40) — не на каче: в пати, рейд и махач его не звать (прогон H5 2026-10-22: принявших вызов разобрали в пати охотников).
		return (hunter != null) && (hunter.phase == Phase.HUNT) && (hunter.enemyId == 0) && (hunter.spot != null) && !player.isDead() && !PhantomDuel.getInstance().isBusy(player);
	}

	/**
	 * Охотник собрал пати охотников (патч 0036): счёт «качались вместе» — с нуля, как у вступившего ({@link #joinedParty}).
	 * @param player лидер
	 */
	public void ledParty(Player player)
	{
		final Hunter hunter = _hunters.get(player.getName().toLowerCase());
		if (hunter != null)
		{
			hunter.together.clear();
			hunter.togetherAwards.clear();
			hunter.togetherTick = 0;
		}
	}

	/**
	 * Охотник в пати: время рядом с каждым живым или охотником из пати; за каждые {@link #TOGETHER_STEP} мс — «качались вместе» (не больше {@link #TOGETHER_MAX} за пати).
	 */
	private static void together(Hunter hunter, Player player, long now)
	{
		final long delta = hunter.togetherTick > 0 ? Math.min(now - hunter.togetherTick, TICK * 5) : 0;
		hunter.togetherTick = now;
		if ((delta <= 0) || !player.isInParty() || player.isDead())
		{
			return;
		}

		for (Player member : player.getParty().getMembers())
		{
			// С живыми и с охотниками (патч 0036: пати охотников); напарники из .buddy — нет.
			if ((member == player) || (PhantomManager.getInstance().isPhantom(member) && !PhantomRelations.isHunter(member)) || !member.isOnline() || member.isDead() || (player.calculateDistance2D(member) > TOGETHER_RANGE))
			{
				continue;
			}

			final int id = member.getObjectId();
			final long time = hunter.together.merge(id, delta, Long::sum);
			if ((time >= TOGETHER_STEP) && (hunter.togetherAwards.getOrDefault(id, 0) < TOGETHER_MAX))
			{
				hunter.together.put(id, time - TOGETHER_STEP);
				hunter.togetherAwards.merge(id, 1, Integer::sum);
				PhantomRelations.getInstance().record(player, member, PhantomRelations.Kind.HUNTED);
			}
		}
	}

	private static Player actingPlayer(Creature creature)
	{
		if (creature instanceof Player)
		{
			return (Player) creature;
		}
		if (creature instanceof Summon)
		{
			return ((Summon) creature).getOwner();
		}
		return null;
	}

	static String describe(Player other)
	{
		return other.getName() + " (" + other.getLevel() + " ур., " + other.getPlayerClass().name().replace('_', ' ').toLowerCase() + ")";
	}

	// ---------- На споте ----------

	/**
	 * Отдых сидя: HP ниже 45 % или MP ниже 15 % (маг — ниже 30 %: держит ману на бой, раздел 4.38), рядом никто не бьёт — сесть; встать при 95 % HP и 90 % MP или при опасности.
	 * @return {@code true}, если отдыхает
	 */
	private static boolean rest(Player player)
	{
		final double hp = player.getCurrentHp() / Math.max(1, player.getMaxHp());
		final double mp = player.getCurrentMp() / Math.max(1, player.getMaxMp());
		final boolean danger = inDanger(player);
		if (player.isSitting())
		{
			if (danger || ((hp >= 0.95) && (mp >= 0.9)))
			{
				player.standUp();
			}
			return true;
		}

		if (((hp < 0.45) || (mp < (PhantomHunterOutfit.isCaster(player) ? 0.3 : 0.15))) && !danger && !player.isAttackingNow() && !player.isCastingNow())
		{
			player.setTarget(null);
			player.abortAttack();
			player.getAI().setIntentionActive();
			player.sitDown();
			return true;
		}
		return false;
	}

	private static boolean inDanger(Player player)
	{
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, DANGER_RANGE))
		{
			if (monster.isAlikeDead())
			{
				continue;
			}

			// Цель моба в драке AttackableAI держит только в ИИ, getTarget() остаётся пустым или старым (находка параллельной сессии, патч 0034).
			final Creature attackTarget = PhantomCompat.attackTarget(monster);
			final boolean attacking = monster.getAI().getIntention() == Intention.ATTACK;
			final WorldObject victim = (attackTarget != null) && attacking ? attackTarget : monster.getTarget();
			if ((victim == player) && (monster.isInCombat() || attacking))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Поводок: убежал за мобами дальше радиуса спота — назад к центру; совсем далеко — телепорт. Мобов рядом нет — прогулка по споту, а после нескольких пустых прогулок — другой спот.
	 */
	private void leash(Hunter hunter, Player player, long now)
	{
		final HuntSpot spot = hunter.spot;
		final double distance = player.calculateDistance2D(spot.entry);
		// Идёт и приближается — не застрял, даже если обход по геодате и драки по дороге дольше расчёта по прямой (патч 0044).
		if ((hunter.walkBackUntil != 0) && ((now - hunter.walkProgressAt) >= WALK_PROGRESS_CHECK))
		{
			if (distance < (hunter.walkProgressDistance - WALK_PROGRESS_MIN))
			{
				hunter.walkBackUntil = Math.max(hunter.walkBackUntil, now + WALK_PROGRESS_CHECK);
			}
			hunter.walkProgressAt = now;
			hunter.walkProgressDistance = distance;
		}
		if (distance <= (spot.radius * 1.3))
		{
			if (hunter.walkBackUntil != 0)
			{
				hunter.walkBackUntil = 0;
				hunter.walkFails = 0;
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " reached " + spot.name + " on foot.");
			}
		}
		else if ((hunter.walkBackUntil != 0) && (now > hunter.walkBackUntil))
		{
			hunter.walkBackUntil = 0; // шёл и не дошёл — застрял в геодате
			// Сначала — другой спот поблизости, тоже ногами; второй раз подряд не вышло — телепорт, как раньше.
			final HuntSpot other = hunter.walkFails++ == 0 ? nearestSpot(player, spot) : null;
			if (other != null)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " got stuck walking to " + spot.name + " (" + (int) distance + (player.isInCombat() ? ", в бою" : "") + "), tries " + other.name + ".");
				walkTo(hunter, player, other);
				return;
			}
			hunter.walkFails = 0;
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " got stuck walking back to " + spot.name + " (" + (int) distance + (player.isInCombat() ? ", в бою" : "") + "), teleports.");
			stop(player);
			player.teleToLocation(spot.entry, true);
			return;
		}
		// Далеко от спота — телепорт; кроме дороги ногами: её ведёт таймер выше.
		if ((distance > WALK_RANGE) && (hunter.walkBackUntil == 0))
		{
			hunter.walkBackUntil = 0;
			stop(player);
			player.teleToLocation(spot.entry, true);
			return;
		}

		if (player.isAttackingNow() || player.isCastingNow())
		{
			return;
		}

		if (distance > (spot.radius * 1.3))
		{
			final WorldObject target = player.getTarget();
			if ((target == null) || !(target instanceof Monster) || ((Monster) target).isAlikeDead() || (player.calculateDistance2D(target) > 300))
			{
				player.setTarget(null);
				// В дороге ногами — по маршруту или с обходом ({@link #walkStep}); на споте убежал за мобами — назад к центру.
				if (hunter.walkBackUntil != 0)
				{
					walkStep(hunter, player, spot, distance);
				}
				else
				{
					runTo(player, spot.entry);
				}
			}
			return;
		}

		if ((now - hunter.lastActive) > IDLE_ROAM)
		{
			hunter.lastActive = now;
			hunter.roams++;
			if (hunter.roams > ROAMS_BEFORE_SWITCH)
			{
				goToSpot(hunter, player, spot);
				return;
			}

			final double angle = Rnd.nextDouble() * 2 * Math.PI;
			final int r = Rnd.get(200, Math.max(250, spot.radius));
			final int x = spot.entry.getX() + (int) (Math.cos(angle) * r);
			final int y = spot.entry.getY() + (int) (Math.sin(angle) * r);
			final int z = GeoEngine.getInstance().getHeight(x, y, spot.entry.getZ());
			if (PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), player.getX(), player.getY(), player.getZ(), x, y, z, player.getInstanceId()))
			{
				player.getAI().setIntentionMoveTo(new Location(x, y, z));
			}
		}
	}

	private static void stop(Player player)
	{
		AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		player.setTarget(null);
		player.abortAttack();
		player.abortCast();
	}

	/**
	 * Шанс дропа вещей (не адены) с моба, убитого охотником (NpcTemplate): HunterDropChanceMultiplier — чтобы было что ставить в лавку.
	 */
	public static double dropChanceMultiplier(Creature killer)
	{
		final Player player = killer == null ? null : killer.asPlayer();
		return (player != null) && getInstance().isHunter(player) ? PhantomHuntersConfig.HUNTER_DROP_CHANCE_MULTIPLIER : 1;
	}

	public boolean isHunter(Player player)
	{
		return _hunters.containsKey(player.getName().toLowerCase()) && PhantomManager.getInstance().isPhantom(player);
	}

	/**
	 * Самопроверка {@code HuntersSelfTest = UI}: окно GM на вкладках «Все» и «Лавки» и раздел доски на «Мой уровень» и «Все» — в {@link #UI_TEST_FILE}.
	 * Кнопки там не работают; смотреть, что строится, какой длины и как выглядит.
	 */
	private void uiSelfTest(Player viewer)
	{
		final String[][] admin =
		{
			{
				"admin_hunters"
			},
			{
				"admin_hunters",
				"store",
				"name",
				"0"
			}
		};
		final StringBuilder sb = new StringBuilder(65536);
		sb.append("<!doctype html><meta charset=\"utf-8\"><title>L2Hub: окна охотников</title><body style=\"background:#1b1b1b;color:#ddd;font:12px Tahoma\">");
		sb.append("<p>Зритель: ").append(viewer.getName()).append(" (").append(viewer.getLevel()).append(" ур.). Кнопки не работают.</p>");
		for (String[] args : admin)
		{
			final String html = PhantomHunterUi.admin(viewer, args);
			sb.append("<h3>//hunters ").append(String.join(" ", args).substring("admin_hunters".length())).append(" — ").append(html.length()).append(" символов (предел 17200)</h3>");
			sb.append("<div style=\"width:280px;border:1px solid #555;padding:4px\">").append(html).append("</div>");
		}
		for (String tab : new String[]
		{
			"near",
			"all"
		})
		{
			final String html = PhantomHunterUi.board(viewer, tab, 0, "bypass _bbshubhunters");
			sb.append("<h3>Alt+B, вкладка ").append(tab).append(" — ").append(html.length()).append(" символов</h3>");
			sb.append("<div style=\"width:560px;border:1px solid #555;padding:4px\">").append(html).append("</div>");
		}
		sb.append("</body>");

		try
		{
			Files.writeString(Path.of(UI_TEST_FILE), sb, StandardCharsets.UTF_8);
			LOGGER.info(getClass().getSimpleName() + ": SelfTest UI: windows for " + viewer.getName() + " written to " + UI_TEST_FILE + ".");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": SelfTest UI: could not write " + UI_TEST_FILE + ".", e);
		}
	}

	// ---------- Снимок для окон ----------

	/**
	 * Охотник в мире, как его видят окна //hunters и «Охотники» на Alt+B ({@link PhantomHunterUi}).
	 */
	public static final class HunterView
	{
		public final Player player;
		/** HUNT, TOWN, STORE, RECRUIT, PARTY или DEAD. */
		public final String phase;
		/** Спот, на котором качается или куда вернётся; {@code null} — ещё без спота. */
		public final HuntSpot spot;
		public final long earnedPerHour;
		/** Взятый квест (уровень), 0 — никакого. */
		public final int questLevel;
		public final long questUntil;
		public final boolean questReady;
		/** Сколько не хватает на профу, 0 — не копит. */
		public final long professionDue;
		/** Сидит в лавке (торговля открыта). */
		public final boolean storeOpen;
		/** Кто позвал его в пати криком (фаза RECRUIT), иначе пусто. */
		public final String recruiter;

		HunterView(Hunter hunter, Player player, long now)
		{
			this.player = player;
			phase = player.isDead() ? "DEAD" : hunter.phase.name();
			spot = hunter.spot;
			earnedPerHour = hunter.earnedSince > 0 ? (hunter.earned * 3600000L) / Math.max(600000L, now - hunter.earnedSince) : 0;
			questLevel = hunter.questLevel;
			questUntil = hunter.questUntil;
			questReady = hunter.questReady;
			professionDue = PhantomHunterOutfit.professionDue(player);
			storeOpen = hunter.storeOpen && (player.getPrivateStoreType() == PrivateStoreType.SELL);
			final Player recruiterPlayer = hunter.recruiterId != 0 ? World.getPlayer(hunter.recruiterId) : null;
			recruiter = (hunter.phase == Phase.RECRUIT) && (recruiterPlayer != null) ? recruiterPlayer.getName() : "";
		}
	}

	/**
	 * @return охотники, которые сейчас в мире
	 */
	public List<HunterView> views()
	{
		final long now = System.currentTimeMillis();
		final List<HunterView> result = new ArrayList<>(_hunters.size());
		for (Hunter hunter : _hunters.values())
		{
			final Player player = PhantomManager.getInstance().getPhantom(hunter.template);
			if (player != null)
			{
				result.add(new HunterView(hunter, player, now));
			}
		}
		return result;
	}

	/**
	 * @return живых игроков в игре (считается раз в 10 с)
	 */
	public int getRealPlayers()
	{
		return _realPlayers;
	}

	public static PhantomHunterManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomHunterManager INSTANCE = new PhantomHunterManager();
	}
}
