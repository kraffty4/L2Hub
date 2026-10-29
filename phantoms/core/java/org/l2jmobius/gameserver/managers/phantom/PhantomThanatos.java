package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.entity.zone.type.WaterZone;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.IItemHandler;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.script.Quest;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * L2Hub: Танатос — один ПК на сервер (docs/PHANTOMS.md, раздел 4.45; решения автора 2026-10-23). Заменил ПК-охотников из общего пула.
 * <ul>
 * <li>кто: фантом из L2HubPhantoms.xml ({@code kind="reaper"}, пишет tools/fake_players.py) — орк-кастетчик линии Тирана, всегда {@value #LEVEL}-го уровня
 * (третья профа), в лучшем шмоте хроники для {@value #LEVEL}-го с бижутерией и рубахой: оружие +{@value #WEAPON_ENCHANT}, броня и бижа +{@value #ARMOR_ENCHANT},
 * рубаха +{@value #SHIRT_ENCHANT}; все бафы схемы баффера (бафы, танцы, песни, чанты, пророчества, резисты) максимального уровня, кроме магических, щитовых
 * и вредных ({@link #NO_BUFFS}), обновляются раз в {@value #REBUFF} мс; карма {@value #KARMA} (решение автора 2026-10-23: «чтобы он был грозой»);</li>
 * <li>часть охотников-фантомов, отдельного выключателя нет: охотники не нужны (HuntersMode) — нет и его;</li>
 * <li>странствует по спотам охотников {@value #SPOT_MIN}–{@value #SPOT_MAX} уровня (в городах гварды, стартовые локации — ниже) по своему маршруту ({@link #nextSpot}):
 * курс, память спотов, не больше {@value #REGION_STEPS} спотов в одной зоне; по маршруту — только шагом ({@link #travel}), бежит — только за жертвой; где люди,
 * он не знает — жертвы те, кого увидел по пути; бьёт только игроков и охотников, мобов не трогает;
 * мерцает, только когда уходит (решение автора 2026-10-23): убил — телепорт чуть дальше ({@link #blink}), и дальше шагом; ходит только туда, куда дойдёт пешком:
 * спот не в воде, путь по геодате на весь перегон найден при выборе спота ({@link #plan}) и не идёт морем; обошёл свою землю — уходит прыжком после убийства
 * или через {@value #CORNERED} мс, пешком некуда вовсе (остров, башня) — через {@value #TRAPPED} мс; застрял — шаг в сторону, трижды — бросает спот, без телепорта;
 * всё — в лог;</li>
 * <li>бой — свой ({@link #strike}): копит силу Focused Force, тратит её в Hurricane Assault и Force Blaster, без силы — Burning Fist и Soul Breaker, всё — с ctrl,
 * как ПК у игрока; между жертвами набирает силу до {@value #CHARGES_IDLE}; свои бафы Тирана — тотем, Zealot, War Frenzy; пьёт банки, как живой ПК ({@link #drink});
 * бьют несколько — сначала хилера, оглушив его Soul Breaker;</li>
 * <li>живого игрока сам выбирает не чаще раза в {@value #PLAYER_GAP} мс (переменная персонажа); за {@value #FORESHADOW_MIN}–{@value #FORESHADOW_MAX} мс до удара — предвестие
 * системным сообщением. Кто бьёт его сам — тот без защиты: отвечает сразу;</li>
 * <li>смерть от него — без потери опыта (опыт возвращается через секунду); его смерть: карма роняет вещи (штатно), сутки без него ({@value #DEATH_ABSENCE} мс);</li>
 * <li>охотники, что его видят: кричат в общий чат, слабые бегут, сильные (с {@link PhantomRaid#HUNT_LEVEL}-го) собирают на него охоту ({@link PhantomRaid#hunt});</li>
 * <li>убийства и его смерть — в «Летопись мира» ({@link PhantomNews}, вид {@code thanatos}); в «Мой мир» — строка без данных ({@link #status});</li>
 * <li>фейки в ближайшем городе судачат о нём (скрипт {@code L2HubTownFakes} c4-interlude, если есть: {@link #townFakes}) — появился, убил, убит;</li>
 * <li>голос — мозги ({@code "kind":"reaper"}, {@link #voice}): убил живого — шёпот жертве, охотника — в общий чат; его убили, охота вступила в бой, живой напал сам —
 * в общий чат; вслух — только если рядом слышит живой (иначе мозги не звать); выбранный живой ушёл в мирную зону — шёпот; шёпот ему — ответ шёпотом, одному не чаще
 * раза в {@value #WHISPER_GAP} мс.</li>
 * </ul>
 */
public final class PhantomThanatos
{
	private static final Logger LOGGER = Logger.getLogger(PhantomThanatos.class.getName());

	static final int LEVEL = 76;
	private static final int KARMA = 9999;
	private static final int WEAPON_ENCHANT = 16;
	private static final int ARMOR_ENCHANT = 10;
	private static final int SHIRT_ENCHANT = 15;
	/** Сила Тирана: Focused Force копит заряды, в бою добирать до {@value #CHARGES_FIGHT}, между жертвами — до {@value #CHARGES_IDLE}. */
	private static final int FOCUSED_FORCE = 50;
	private static final int CHARGES_FIGHT = 2;
	private static final int CHARGES_IDLE = 7;
	/** Удары по порядку: Hurricane Assault (2 заряда), Force Blaster (1), Burning Fist, Soul Breaker (оглушает); чего нет у хроники — пропускается. */
	private static final int[] ATTACKS =
	{
		284,
		54,
		280,
		281
	};
	/** Свои бафы Тирана: тотем (один на всех, Ogre — сила атаки; нет его — Hawk, Bison, Puma), Zealot, War Frenzy. */
	private static final int[] TOTEMS =
	{
		109,
		425,
		292,
		282
	};
	private static final int[] SELF_BUFFS =
	{
		420,
		424
	};
	/**
	 * Банки, как у живого ПК: Greater CP Potion — CP ниже {@value #CP_DRINK} %; Quick Healing Potion — HP ниже {@value #QUICK_DRINK} %, не чаще раза в {@value #QUICK_GAP} мс;
	 * Greater Healing Potion (лечит со временем) — HP ниже {@value #HP_DRINK} %, раз в {@value #HP_GAP} мс. Запас — {@value #POTION_STACK} каждой при выходе; чего нет у хроники — не пьёт.
	 */
	private static final int CP_POTION = 5592;
	private static final int QUICK_POTION = 1540;
	private static final int HP_POTION = 1539;
	private static final int POTION_STACK = 5000;
	private static final int CP_DRINK = 80;
	private static final int QUICK_DRINK = 50;
	private static final int HP_DRINK = 80;
	private static final long QUICK_GAP = 3000;
	private static final long HP_GAP = 15000;
	/** Soul Breaker — оглушить хилера. */
	private static final int SOUL_BREAKER = 281;
	/** Бафы: обновлять раз в столько. */
	private static final long REBUFF = 600000;
	/**
	 * Не его бафы: магические (Empower, Concentration, Acumen, Wild Magic, Clarity, танцы мистика, концентрации и сирены, Flame Chant, пророчества воды и ветра,
	 * Magnus, дары серафима), щитовые (Bless Shield, Advanced Block) и вредные (Dance of Shadow режет скорость вдвое, Berserker Spirit и Dance of Berserker снимают защиту).
	 */
	private static final java.util.Set<Integer> NO_BUFFS = java.util.Set.of(1059, 1078, 1085, 1303, 1397, 273, 276, 365, 1002, 1355, 1357, 1413, 4702, 4703, 1243, 1304, 366, 1062, 915);
	/** Тело мага ({@link PhantomTemplate.Body}): магические бафы — его; не его — щитовые и вредные. Тело со щитом берёт и щитовые (Bless Shield, Advanced Block). */
	private static final java.util.Set<Integer> MAGE_NO_BUFFS = java.util.Set.of(1243, 1304, 366, 1062, 915);
	private static final java.util.Set<Integer> SHIELD_BUFFS = java.util.Set.of(1243, 1304);
	/**
	 * Тело после смерти (решения автора 2026-10-25, «Новое тело»): 0 — Тиран шаблона, 1… — {@link PhantomTemplate#getBodies()}. Убили — следующее тело
	 * другое, случайное; номер — в глобальной переменной, переживает рестарт.
	 */
	private static final String BODY_VARIABLE = "L2HubThanatosBody";
	/** Удары тела не Тирана: столько самых сильных атакующих скиллов класса. */
	private static final int BODY_ATTACKS = 4;
	/** Первый выход — через столько после старта охотников. */
	private static final long FIRST = 600000;
	/** После смерти — столько без него; труп лежит столько. */
	private static final long DEATH_ABSENCE = 86400000;
	private static final long CORPSE_TIME = 60000;
	private static final String BACK_VARIABLE = "L2HubThanatosBack";
	/** Живой игрок — не чаще раза в столько (если выбирает он). */
	private static final long PLAYER_GAP = 21600000;
	private static final String MARK_VARIABLE = "L2HubThanatosNext";
	private static final long FORESHADOW_MIN = 5000;
	private static final long FORESHADOW_MAX = 10000;
	/** Споты: уровень мобов от и до (выше — сам ляжет от мобов). */
	private static final int SPOT_MIN = 20;
	private static final int SPOT_MAX = 74;
	/** Жертва — в этом радиусе; погоня — не дольше и не дальше. */
	private static final int VICTIM_RANGE = 1500;
	private static final long CHASE = 90000;
	private static final int CHASE_RANGE = 2500;
	/** Маршрут ({@link #nextSpot}): соседний спот — в стольких от него, нет — до стольких; помнит столько последних; на споте без жертв — столько. */
	private static final int NEXT_MIN = 2500;
	private static final int NEXT_MAX = 12000;
	private static final int NEXT_FAR = 25000;
	private static final int MEMORY = 15;
	/** В одной зоне — не больше стольких спотов подряд, дальше — в другую (иначе бродит по острову час). */
	private static final int REGION_STEPS = 6;
	private static final long STAY_MIN = 45000;
	private static final long STAY_MAX = 90000;
	/** Засиделся в зоне, а другой рядом нет — пешком в ближайшую другую, если она не дальше этого. */
	private static final int NEXT_WALK_FAR = 45000;
	/** Выбор соседа: путь пешком ({@link #plan}) ищется у стольких лучших. */
	private static final int PICK_CHECKS = 12;
	/** Вода на пути: точки через столько; вода подряд длиннее этого — море или озеро. */
	private static final int SAMPLE = 200;
	private static final int WET_RUN = 400;
	/**
	 * Путь на весь перегон — {@link PhantomPath} (A* охотников, раздел 4.25) с таким потолком раскрытых узлов: сам Mobius путь дальше 3500 не ищет
	 * (PathFindBuffers — до 500 клеток) и ведёт напрямую сквозь горы и стены. Офлайн по геодате H5 (2026-10-23): выбор спота — медиана 2 мс, 99 % — до 0,25 с.
	 */
	private static final int PATH_NODES = 60000;
	/** Мерцание после убийства: повороты курса, если по курсу земли нет. */
	private static final double[] BLINK_TURNS =
	{
		0,
		Math.PI / 9,
		-Math.PI / 9,
		(2 * Math.PI) / 9,
		(-2 * Math.PI) / 9
	};
	/** Не сдвинулся столько в пути — застрял: шаг в сторону и новый путь; застрял столько раз на перегоне — бросает спот. */
	private static final long STUCK = 15000;
	private static final int STUCK_GIVE_UP = 3;
	/**
	 * Обошёл свою землю (все соседи, куда дойдёт, — в памяти) и никого не забрал столько — уходит прыжком; пешком отсюда некуда вовсе (часть острова, башня) —
	 * через столько. Офлайн по H5 (2026-10-23): такие тупики — Devils Isle, Swamp of Screams, Tower of Insolence, Raging Spartoi.
	 */
	private static final long CORNERED = 1800000;
	private static final long TRAPPED = 300000;
	/** Идти некуда — следующая попытка через столько. */
	private static final long RETRY = 60000;
	/** Телепорт «чуть дальше»: столько от себя к следующему споту. */
	private static final int BLINK_MIN = 1800;
	private static final int BLINK_MAX = 3200;
	/** Самопроверка: выйти не дальше стольких от охотника. */
	private static final int SELF_TEST_NEAR = 500;
	/** Самопроверка THANATOS_DEATH: столько смертей; охотник убивает его через столько после выхода; сутки отсутствия — не короче стольких. */
	private static final int DEATH_TEST_ROUNDS = 3;
	private static final long DEATH_TEST_ALIVE = 30000;
	private static final long DEATH_TEST_ABSENCE_MIN = DEATH_ABSENCE - 600000;
	/** Охотники его видят в этом радиусе; кричат — не чаще; охоту собирают — не чаще. */
	private static final int SEE_RANGE = 1500;
	private static final long SHOUT_GAP = 300000;
	private static final long HUNT_GAP = 900000;
	private static final long SEEN_GAP = 5000;
	/** Кто бил его — отвечать ему столько. */
	private static final long AGGRESSOR_MEMORY = 30000;
	private static final long TICK = 1000;
	/** Голос: на шёпот одному игроку — не чаще; «напал сам» — раз на бой (новый бой — после стольких мс без ударов); слышат — в этом радиусе; ждать мозги — столько секунд. */
	private static final long WHISPER_GAP = 120000;
	private static final long ATTACK_FIGHT = 300000;
	private static final int HEAR_RANGE = 1250;
	private static final int VOICE_TIMEOUT = 15;
	/** Что точить: оружие, броня, бижутерия. */
	private static final int[] SLOTS =
	{
		Inventory.PAPERDOLL_RHAND,
		Inventory.PAPERDOLL_LHAND,
		Inventory.PAPERDOLL_HEAD,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS,
		Inventory.PAPERDOLL_GLOVES,
		Inventory.PAPERDOLL_FEET,
		Inventory.PAPERDOLL_NECK,
		Inventory.PAPERDOLL_REAR,
		Inventory.PAPERDOLL_LEAR,
		Inventory.PAPERDOLL_RFINGER,
		Inventory.PAPERDOLL_LFINGER,
		Inventory.PAPERDOLL_UNDER
	};

	private static final String[] OMENS =
	{
		"По спине пробежал холодок. Кто-то смотрит на тебя.",
		"Тишина вокруг стала слишком густой. Кто-то идёт за тобой.",
		"Ты слышишь тяжёлые шаги. Они не похожи на шаги монстра.",
		"Воздух пахнет железом. Беги."
	};
	private static final String[] ALARMS =
	{
		"ТАНАТОС у %s!!! бегите",
		"жнец у %s, валите оттуда!",
		"Танатос на %s, я сваливаю, кто может — тоже",
		"осторожно, Танатос бегает у %s"
	};

	private volatile boolean _enabled;
	private volatile long _firstAppear;
	private ScheduledFuture<?> _task;
	private boolean _listening;
	/** Куда идёт и когда пришёл (0 — в пути). */
	private HuntSpot _spot;
	private long _arrivedAt;
	/** Маршрут: курс (радианы), последние споты, сколько стоять на этом. */
	private double _heading = Rnd.nextDouble() * 2 * Math.PI;
	private final java.util.ArrayDeque<HuntSpot> _visited = new java.util.ArrayDeque<>();
	private long _stayFor = STAY_MIN;
	/** Сколько спотов подряд в одной зоне (папка спавнов: Oren, Aden…). */
	private int _regionSteps;
	/** Почему следующий спот — прыжком или {@code null}; путь и шаг в нём; застревания — на перегоне и с выхода. */
	private String _jumpWhy;
	private List<Location> _route;
	private int _routeStep;
	/** Пути, найденные при выборе спота ({@code null} — не дойти), и к какому споту выбранный. */
	private final Map<HuntSpot, List<Location>> _plans = new HashMap<>();
	private HuntSpot _plannedFor;
	/** Сколько мс шёл выбор спота с поиском путей — в лог. */
	private long _pickMs;
	private int _legStucks;
	private int _stucks;
	/** Обошёл свою землю: где и с какого времени — следующее убийство уводит его прыжком; идти некуда — следующая попытка не раньше. */
	private HuntSpot _cornered;
	private long _corneredSince;
	private long _corneredFor;
	private long _retryAt;
	private long _movedAt;
	private int _lastX;
	private int _lastY;
	/** Жертва: кто, когда удар (после предвестия), с какого времени гонится. */
	private volatile int _victimId;
	private long _strikeAt;
	private long _chaseSince;
	private long _deadSince;
	private boolean _deathHandled;
	private long _lastSeen;
	private long _buffedAt;
	private long _quickAt;
	private long _hpAt;
	private long _lastShout;
	private long _lastHunt;
	private final Map<Integer, Long> _aggressors = new ConcurrentHashMap<>();
	/** Голос: кому и когда отвечал на шёпот; кто из живых напал сам и когда об этом сказано (раз на бой). */
	private final Map<Integer, Long> _whispered = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _attackSaid = new ConcurrentHashMap<>();
	/**
	 * Самопроверка THANATOS: выходит рядом с охотником на каче ({@link #huntingHunter}); голос без живых — убийство охотника как живого (шёпот), death и hunt — и без
	 * слушателей.
	 */
	private volatile boolean _selfTest;
	/** Сколько раз кастовал какой скилл с выхода — в лог при убийстве и смерти (проверка боя). */
	private static final Map<String, Integer> CASTS = new ConcurrentHashMap<>();
	private ConsumerEventListener _damageListener;
	/** Удары тела не Тирана ({@link #attacks}): в бою — первый готовый, с ctrl. У Тирана — свой бой ({@link #ATTACKS}). */
	private volatile List<Skill> _attacks = List.of();
	/** Самопроверка THANATOS_BODIES идёт ({@link #selfTestBodies}): тик молчит. */
	private volatile boolean _bodyTest;
	/** Когда вышел в мир последний раз. */
	private long _appearedAt;
	/**
	 * Самопроверка THANATOS_DEATH ({@link #selfTestDeath}): сколько смертей ещё устроить, сколько раундов прошло и сколько из них в порядке; он мёртв и ждёт
	 * возвращения — тело до смерти и сколько вещей было при нём.
	 */
	private int _deathTest;
	private int _deathTestRound;
	private int _deathTestOk;
	private boolean _deathTestDied;
	private int _deathTestBody;
	private int _deathTestItems;

	private PhantomThanatos()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control} раз в 10 с: охотники нужны — Танатос в мире (или скоро будет).
	 */
	synchronized void control(long now)
	{
		if (PhantomData.getInstance().getReaper() == null)
		{
			return;
		}
		_enabled = true;
		if (_firstAppear == 0)
		{
			_firstAppear = now + FIRST;
		}
		if (!_listening)
		{
			_listening = true;
			Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onDeath(event), this));
		}
		if ((_task == null) || _task.isDone())
		{
			_task = ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
		}
	}

	/**
	 * Охотники не нужны (HuntersMode OFF или живых нет): уходит из мира на ближайшем тике.
	 */
	void disable()
	{
		_enabled = false;
	}

	/**
	 * Самопроверка THANATOS ({@code HuntersSelfTest}, для разработки): выйти в мир сразу — без ожидания после старта и после смерти — и рядом с охотником на каче.
	 */
	synchronized void selfTest()
	{
		_firstAppear = 1;
		_selfTest = true;
		GlobalVariablesManager.getInstance().set(BACK_VARIABLE, 0L);
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS: he walks the world now, next to a hunter; voice without live players (hunters count as players).");
	}

	/**
	 * Самопроверка THANATOS_BODIES ({@code HuntersSelfTest}, для разработки; раздел 4.45, «Новое тело»): все тела по очереди выходят рядом с охотником на каче
	 * и уходят — сабкласс, класс, уровень, раса, оружие, удары; итог — «done, N of M ok». Дальше — как THANATOS, в теле мага (самом непохожем на Тирана): бой и смерть.
	 */
	synchronized void selfTestBodies()
	{
		_bodyTest = true;
		_selfTest = true;
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_BODIES: every body in turn, next to a hunter.");
		ThreadPool.schedule(this::runBodyTest, 1000);
	}

	/**
	 * Самопроверка THANATOS_DEATH ({@code HuntersSelfTest}, для разработки; раздел 4.45, «Новое тело»): выходит рядом с охотником, как THANATOS, и
	 * {@value #DEATH_TEST_ROUNDS} раза гибнет от руки ближайшего охотника через {@value #DEATH_TEST_ALIVE} мс после выхода. Дальше — штатно: летопись, голос,
	 * карма роняет вещи, следующее тело, сутки отсутствия, минута трупа, уход в базу. Сутки самопроверка обнуляет и выпускает его сразу: тело другое, отсутствие —
	 * около суток, новое тело — как в THANATOS_BODIES. Итог — «done, N of M ok», дальше — как THANATOS.
	 */
	synchronized void selfTestDeath()
	{
		_deathTest = DEATH_TEST_ROUNDS;
		_deathTestRound = 0;
		_deathTestOk = 0;
		_deathTestDied = false;
		selfTest();
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: he dies " + DEATH_TEST_ROUNDS + " times by a hunter's hand and comes back at once in another body.");
	}

	private void runBodyTest()
	{
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		if (template == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest THANATOS_BODIES: no reaper in L2HubPhantoms.xml.");
			_bodyTest = false;
			return;
		}
		final List<PhantomTemplate.Body> bodies = template.getBodies();
		int ok = 0;
		int mage = 0;
		for (int i = 0; i <= bodies.size(); i++)
		{
			final PhantomTemplate.Body body = i == 0 ? null : bodies.get(i - 1);
			if ((body != null) && "mage".equals(body.kit()))
			{
				mage = i;
			}
			final String kit = body == null ? "tyrant" : body.kit();
			String why = "";
			try
			{
				synchronized (this)
				{
					if (PhantomManager.getInstance().getPhantom(template) != null)
					{
						leave(template, "body test");
					}
					GlobalVariablesManager.getInstance().set(BODY_VARIABLE, i);
					appear(template, System.currentTimeMillis());
				}
				final Player me = PhantomManager.getInstance().getPhantom(template);
				if (me == null)
				{
					why = "did not appear";
				}
				else
				{
					why = checkBody(me, template, body);
					LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_BODIES: body " + i + " " + kit + " — " + describe(me) + (why.isEmpty() ? ": ok." : ": FAIL —" + why));
				}
			}
			catch (Exception e)
			{
				why = e.toString();
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": SelfTest THANATOS_BODIES: body " + i + " " + kit + " failed: " + e.getMessage(), e);
			}
			if (why.isEmpty())
			{
				ok++;
			}
		}
		synchronized (this)
		{
			leave(template, "body test done");
			GlobalVariablesManager.getInstance().set(BODY_VARIABLE, mage);
		}
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_BODIES: done, " + ok + " of " + (bodies.size() + 1) + " ok; now as THANATOS in body " + (mage == 0 ? "tyrant" : "mage") + ".");
		_bodyTest = false;
		selfTest();
	}

	/**
	 * Самопроверки тел: он такой, каким его делает тело ({@code null} — Тиран): 76-й, класс, сабкласс, раса орка, оружие, удары, стрелы луку.
	 * @return пусто — в порядке; иначе — что не так
	 */
	private String checkBody(Player me, PhantomTemplate template, PhantomTemplate.Body body)
	{
		String why = "";
		final int wantClass = body == null ? template.getClassId(LEVEL) : body.classes()[body.classes().length - 1];
		final Item weapon = me.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		final int wantWeapon = body == null ? template.getGear(LEVEL).rhand : body.gear().rhand;
		final int ammo = PhantomHunterOutfit.ammoId(me);
		final Item lhand = me.getInventory().getPaperdollItem(Inventory.PAPERDOLL_LHAND);
		if (me.getLevel() != LEVEL)
		{
			why += " level " + me.getLevel() + ";";
		}
		if (me.getPlayerClass().getId() != wantClass)
		{
			why += " class " + me.getPlayerClass().name() + " instead of " + wantClass + ";";
		}
		if (me.isSubClassActive() != (body != null))
		{
			why += " sub class active " + me.isSubClassActive() + ";";
		}
		if (!"ORC".equals(me.getRace().name()))
		{
			why += " race " + me.getRace() + ";";
		}
		if ((weapon == null) || (weapon.getId() != wantWeapon))
		{
			why += " weapon " + (weapon == null ? "none" : weapon.getName()) + " instead of " + wantWeapon + ";";
		}
		if ((body != null) && _attacks.isEmpty())
		{
			why += " no attack skills;";
		}
		if ((ammo > 0) && ((lhand == null) || (lhand.getId() != ammo)))
		{
			why += " no arrows;";
		}
		return why;
	}

	/**
	 * @return для лога самопроверок: уровень, класс, раса, сабкласс, оружие с заточкой, левая рука, удары
	 */
	private String describe(Player me)
	{
		final Item weapon = me.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		final Item lhand = me.getInventory().getPaperdollItem(Inventory.PAPERDOLL_LHAND);
		return me.getLevel() + " " + me.getPlayerClass().name() + ", race " + me.getRace() + ", sub " + me.isSubClassActive() + ", " + (weapon == null ? "no weapon" : weapon.getName() + " +" + weapon.getEnchantLevel()) + (lhand == null ? "" : ", left " + lhand.getName() + (lhand.getCount() > 1 ? " x" + lhand.getCount() : "")) + ", attacks " + _attacks.stream().map(Skill::getName).toList();
	}

	/**
	 * @return вид тела по номеру — для лога
	 */
	private static String kitOf(PhantomTemplate template, int index)
	{
		return (index <= 0) || (index > template.getBodies().size()) ? "tyrant" : template.getBodies().get(index - 1).kit();
	}

	/**
	 * THANATOS_DEATH, раунд: ближайший охотник убивает его — дальше смерть идёт штатно ({@link #onSelfDeath}, уход трупа в {@link #think}).
	 */
	private void deathTestKill(PhantomTemplate template, Player me)
	{
		Player killer = null;
		double nearest = Double.MAX_VALUE;
		for (Player other : World.getPlayers())
		{
			if ((other != me) && !other.isDead() && PhantomManager.getInstance().isPhantom(other) && PhantomRelations.isHunter(other))
			{
				final double distance = me.calculateDistance3D(other);
				if (distance < nearest)
				{
					nearest = distance;
					killer = other;
				}
			}
		}
		_deathTestRound++;
		_deathTest--;
		_deathTestBody = bodyIndex(template);
		_deathTestItems = me.getInventory().getItems().size();
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: round " + _deathTestRound + ": " + (killer == null ? "nobody" : killer.getName() + " (" + killer.getLevel() + ", " + (int) nearest + " away)") + " kills him in body " + kitOf(template, _deathTestBody) + " — " + describe(me) + ", karma " + PhantomCompat.karma(me) + ", pk " + me.getPkKills() + ", " + _deathTestItems + " items.");
		me.doDie(killer);
		if (me.isDead())
		{
			_deathTestDied = true;
			// Карма роняет вещи по правилам Mobius: с шансом и только от PvpConfig.KARMA_PK_LIMIT убийств (pk в строке выше) — ноль вещей не провал.
			LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: round " + _deathTestRound + ": dead; " + (_deathTestItems - me.getInventory().getItems().size()) + " items gone with karma; back variable in " + ((GlobalVariablesManager.getInstance().getLong(BACK_VARIABLE, 0) - System.currentTimeMillis()) / 60000) + " min, next body " + kitOf(template, bodyIndex(template)) + ".");
		}
		else
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: round " + _deathTestRound + ": FAIL — he did not die.");
			deathTestDone();
		}
	}

	/**
	 * THANATOS_DEATH: труп ушёл в базу — проверить, что смерть записала (сутки, другое тело), обнулить сутки и выпустить его в новом теле.
	 */
	private void deathTestReturn(PhantomTemplate template, long now)
	{
		_deathTestDied = false;
		String why = "";
		final long absence = GlobalVariablesManager.getInstance().getLong(BACK_VARIABLE, 0) - now;
		if ((absence < DEATH_TEST_ABSENCE_MIN) || (absence > DEATH_ABSENCE))
		{
			why += " away for " + (absence / 60000) + " min instead of 24 h;";
		}
		final int index = bodyIndex(template);
		if (index == _deathTestBody)
		{
			why += " same body;";
		}
		GlobalVariablesManager.getInstance().set(BACK_VARIABLE, 0L);
		appear(template, now);
		final Player me = PhantomManager.getInstance().getPhantom(template);
		if (me == null)
		{
			why += " did not appear;";
		}
		else
		{
			why += checkBody(me, template, index == 0 ? null : template.getBodies().get(index - 1));
		}
		if (why.isEmpty())
		{
			_deathTestOk++;
		}
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: round " + _deathTestRound + ": " + kitOf(template, _deathTestBody) + " -> " + kitOf(template, index) + ", away " + (absence / 60000) + " min" +(me == null ? "" : " — " + describe(me) + ", " + me.getInventory().getItems().size() + " items (" + _deathTestItems + " before death)") + (why.isEmpty() ? ": ok." : ": FAIL —" + why));
		if (_deathTest == 0)
		{
			deathTestDone();
		}
	}

	private void deathTestDone()
	{
		LOGGER.info(getClass().getSimpleName() + ": SelfTest THANATOS_DEATH: done, " + _deathTestOk + " of " + _deathTestRound + " ok; now as THANATOS.");
		_deathTest = 0;
		_deathTestDied = false;
	}

	/**
	 * @return это он
	 */
	public static boolean is(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		return (player != null) && (template != null) && template.getName().equalsIgnoreCase(player.getName()) && PhantomManager.getInstance().isPhantom(player);
	}

	/**
	 * @return он в мире, жив — или {@code null}
	 */
	Player get()
	{
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		final Player player = template == null ? null : PhantomManager.getInstance().getPhantom(template);
		return (player != null) && !player.isDead() ? player : null;
	}

	private void tick()
	{
		try
		{
			think(System.currentTimeMillis());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": tick failed.", e);
		}
	}

	private synchronized void think(long now)
	{
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		if ((template == null) || _bodyTest)
		{
			return;
		}
		final Player me = PhantomManager.getInstance().getPhantom(template);
		if (!_enabled)
		{
			if (me != null)
			{
				leave(template, "hunters are off");
			}
			return;
		}
		if (me == null)
		{
			if (_deathTestDied)
			{
				deathTestReturn(template, now);
			}
			else if (now >= back())
			{
				appear(template, now);
			}
			return;
		}
		// Поднят из базы при старте сервера (PhantomManager.restore) — как был: после смерти он ниже 76-го и без вещей, что уронила карма
		// (прогон H5 2026-10-23: охота убила такого за 6 секунд). Уйти — и выйти заново полной сборкой, когда придёт его время.
		if (_damageListener == null)
		{
			leave(template, "restored from the database, rebuilt on the next appearance");
			return;
		}
		if (me.isDead())
		{
			if (_deadSince == 0)
			{
				_deadSince = now;
			}
			else if ((now - _deadSince) > CORPSE_TIME)
			{
				leave(template, "dead");
			}
			return;
		}
		if ((_deathTest > 0) && ((now - _appearedAt) >= DEATH_TEST_ALIVE))
		{
			deathTestKill(template, me);
			return;
		}
		if (PhantomCompat.karma(me) < KARMA)
		{
			PhantomCompat.setKarma(me, KARMA);
		}
		if ((now - _buffedAt) >= REBUFF)
		{
			_buffedAt = now;
			buff(me);
		}
		drink(me, now);
		if ((now - _lastSeen) >= SEEN_GAP)
		{
			_lastSeen = now;
			seen(me, now);
		}

		// Погоня.
		Player victim = _victimId == 0 ? null : World.getPlayer(_victimId);
		if ((victim != null) && (victim.isDead() || !victim.isOnline() || victim.isInsideZone(ZoneId.PEACE) || (me.calculateDistance2D(victim) > CHASE_RANGE) || ((now - _chaseSince) > CHASE)))
		{
			if (victim.isDead())
			{
				// Добил: телепорт чуть дальше — и шагом к следующему споту.
				moveOn(me, now, true);
			}
			else
			{
				// Голос: выбранный им живой ушёл в мирную зону — шёпотом.
				if (victim.isInsideZone(ZoneId.PEACE) && !PhantomManager.getInstance().isPhantom(victim) && victim.isOnline())
				{
					final Player escaped = victim;
					voice(me, "escaped", escaped, "", 0, text -> whisper(me, escaped, text));
				}
				LOGGER.info(getClass().getSimpleName() + ": lost " + victim.getName() + ".");
				calm(me);
			}
			_victimId = 0;
			victim = null;
		}
		final Player aggressor = aggressor(me, now);
		if ((aggressor != null) && (aggressor != victim) && ((victim == null) || (now < _strikeAt) || (healer(aggressor) && !healer(victim))))
		{
			startChase(me, aggressor, now, false);
			victim = aggressor;
		}
		if (victim != null)
		{
			chase(me, victim, now);
			return;
		}

		// Путь к споту и спот.
		if (_spot == null)
		{
			if (now < _retryAt)
			{
				// Пешком некуда ({@link #stuck}): стоит и высматривает — убийство уводит его прыжком.
				final Player target = pickVictim(me, now);
				if (target != null)
				{
					startChase(me, target, now, true);
					chase(me, target, now);
				}
				return;
			}
			final HuntSpot next = nextSpot(me, false, now);
			if (next == null)
			{
				_retryAt = now + pause(now);
				return;
			}
			goTo(me, next, now, false);
		}
		if (_arrivedAt == 0)
		{
			if (me.calculateDistance2D(_spot.entry) <= 600)
			{
				_arrivedAt = now;
			}
			else
			{
				travel(me, now);
				return;
			}
		}
		final Player target = pickVictim(me, now);
		if (target != null)
		{
			startChase(me, target, now, true);
			chase(me, target, now);
			return;
		}
		if (!me.isMoving() && charge(me))
		{
			return;
		}
		if ((now - _arrivedAt) > _stayFor)
		{
			moveOn(me, now, false);
		}
		else if (!me.isMoving() && (Rnd.get(10) == 0))
		{
			// Бродит по споту, высматривает.
			final int r = Math.max(200, _spot.radius / 2);
			final Location to = ground(_spot.entry.getX() + Rnd.get(-r, r), _spot.entry.getY() + Rnd.get(-r, r), _spot.entry.getZ());
			if ((to != null) && !wet(to))
			{
				me.setWalking();
				me.getAI().setIntentionMoveTo(to);
			}
		}
	}

	// ---------- Выход и уход ----------

	/**
	 * @return раньше этого времени его нет: первый выход или сутки после смерти
	 */
	private long back()
	{
		return Math.max(_firstAppear, GlobalVariablesManager.getInstance().getLong(BACK_VARIABLE, 0));
	}

	private void appear(PhantomTemplate template, long now)
	{
		// Самопроверка — рядом с охотником на каче: странник находит жертв не сразу (прогон 2026-10-23: 14 спотов за полчаса без встреч).
		final Player near = _selfTest ? huntingHunter() : null;
		final HuntSpot spot = near != null ? nearestSpot(near) : randomSpot(null);
		if (spot == null)
		{
			return;
		}
		final Location base = near != null ? near.getLocation() : spot.entry;
		final int spread = near != null ? SELF_TEST_NEAR : BLINK_MAX;
		final Location at = ground(base.getX() + Rnd.get(-spread, spread), base.getY() + Rnd.get(-spread, spread), base.getZ());
		final Player me = PhantomManager.getInstance().spawn(template, (at != null) && !wet(at) ? at : spot.entry, LEVEL);
		if (me == null)
		{
			return;
		}
		// Тело (раздел 4.45, «Новое тело»): не Тиран — сабкласс тела и его комплект; Тиран, а из базы поднят в прежнем теле — назад в базу и сборка Тирана.
		final int bodyIndex = bodyIndex(template);
		final PhantomTemplate.Body body = bodyIndex == 0 ? null : template.getBodies().get(bodyIndex - 1);
		if (body != null)
		{
			if (!PhantomOutfit.wearBody(me, body, LEVEL))
			{
				LOGGER.warning(getClass().getSimpleName() + ": " + me.getName() + " stays a Tyrant: body " + body.kit() + " did not take.");
			}
		}
		else if (me.isSubClassActive())
		{
			PhantomOutfit.toBase(me);
			PhantomOutfit.build(me, template, LEVEL);
		}
		_attacks = attacks(me);
		if (me.getLevel() != LEVEL)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + me.getName() + " is level " + me.getLevel() + " instead of " + LEVEL + ".");
		}
		// Заточка: снять, заточить, надеть — бонусы заточки считаются при надевании. Стрелы в левой руке — не точить.
		for (int slot : SLOTS)
		{
			final Item item = me.getInventory().getPaperdollItem(slot);
			if ((item != null) && (item.getEnchantLevel() == 0) && (item.isWeapon() || item.isArmor()))
			{
				me.getInventory().unEquipItemInSlot(slot);
				item.setEnchantLevel(slot == Inventory.PAPERDOLL_RHAND ? WEAPON_ENCHANT : slot == Inventory.PAPERDOLL_UNDER ? SHIRT_ENCHANT : ARMOR_ENCHANT);
				me.getInventory().equipItem(item);
			}
		}
		PhantomCompat.setKarma(me, KARMA);
		me.setTitle("");
		for (int potion : new int[]
		{
			CP_POTION,
			QUICK_POTION,
			HP_POTION
		})
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(potion);
			if ((item != null) && item.getName().contains("Potion"))
			{
				PhantomCompat.addItem(me, ItemProcessType.REWARD, potion, POTION_STACK, null);
			}
		}
		buff(me);
		_buffedAt = System.currentTimeMillis();
		me.setCurrentHpMp(me.getMaxHp(), me.getMaxMp());
		me.setCurrentCp(me.getMaxCp());
		me.broadcastUserInfo();
		_damageListener = new ConsumerEventListener(me, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamaged(event), this);
		me.addListener(_damageListener);
		_spot = spot;
		_appearedAt = now;
		_arrivedAt = 0;
		_visited.clear();
		remember(spot);
		_stucks = 0;
		_legStucks = 0;
		_route = null;
		_cornered = null;
		_retryAt = 0;
		_heading = Rnd.nextDouble() * 2 * Math.PI;
		_stayFor = Rnd.get(STAY_MIN, STAY_MAX);
		_victimId = 0;
		_deadSince = 0;
		_deathHandled = false;
		_aggressors.clear();
		CASTS.clear();
		LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + " (" + me.getLevel() + " " + me.getPlayerClass().name() + ", body " + (body == null ? "tyrant" : body.kit()) + ") walks the world, heading to " + spot.name + ".");
		townFakes("thanatos", me, "");
	}

	/**
	 * @return номер тела ({@link #BODY_VARIABLE}): 0 — Тиран; номер вне списка тел хроники — тоже 0
	 */
	private static int bodyIndex(PhantomTemplate template)
	{
		final int index = GlobalVariablesManager.getInstance().getInt(BODY_VARIABLE, 0);
		return (index < 0) || (index > template.getBodies().size()) ? 0 : index;
	}

	/**
	 * Убили — следующее тело другое: случайное из остальных (Тиран тоже в их числе).
	 * @return новое тело — в лог
	 */
	private static String nextBody(PhantomTemplate template)
	{
		final int count = template.getBodies().size() + 1;
		if (count < 2)
		{
			return "tyrant";
		}
		int next = Rnd.get(count - 1);
		if (next >= bodyIndex(template))
		{
			next++;
		}
		GlobalVariablesManager.getInstance().set(BODY_VARIABLE, next);
		return next == 0 ? "tyrant" : template.getBodies().get(next - 1).kit();
	}

	/**
	 * Удары тела не Тирана, как у охотников ({@code PhantomHunterOutfit.setupCombat}): активные атакующие скиллы класса без расхода предметов,
	 * {@value #BODY_ATTACKS} самых сильных; скиллы на заряды — тоже (готов ли — {@link #ready}).
	 */
	private static List<Skill> attacks(Player me)
	{
		final List<Skill> attacks = new ArrayList<>();
		for (Skill skill : me.getAllSkills())
		{
			if (skill.isActive() && !skill.isToggle() && (skill.getItemConsumeCount() == 0) && PhantomCompat.isAttackSkillTarget(skill) && skill.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.MAGICAL_ATTACK, EffectType.HP_DRAIN, EffectType.DEATH_LINK, EffectType.PHYSICAL_ATTACK_HP_LINK))
			{
				attacks.add(skill);
			}
		}
		attacks.sort((a, b) -> Double.compare(PhantomCompat.skillPower(b), PhantomCompat.skillPower(a)));
		return List.copyOf(attacks.subList(0, Math.min(BODY_ATTACKS, attacks.size())));
	}

	private void leave(PhantomTemplate template, String why)
	{
		final Player me = PhantomManager.getInstance().getPhantom(template);
		if ((me != null) && (_damageListener != null))
		{
			me.removeListener(_damageListener);
		}
		// В базу — в ней он поднимается и собирается заново при следующем выходе (тело надевается поверх).
		if (me != null)
		{
			PhantomOutfit.toBase(me);
		}
		_damageListener = null;
		PhantomManager.getInstance().despawn(template);
		_spot = null;
		_victimId = 0;
		_deadSince = 0;
		LOGGER.info(getClass().getSimpleName() + ": " + template.getName() + " left the world (" + why + ").");
	}

	/**
	 * Все бафы схемы баффера хроники (мод 0004: уровни — максимальные) по всем разделам, кроме {@link #NO_BUFFS}; Super Haste там нет и не нужен (решение автора).
	 */
	private static void buff(Player me)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final java.util.Set<Integer> done = new java.util.HashSet<>();
		for (String type : table.getSkillTypes())
		{
			if (type.endsWith("_GROUP"))
			{
				continue;
			}
			for (int skillId : table.getSkillsIdsByType(type))
			{
				if (noBuff(me, skillId) || !done.add(skillId))
				{
					continue;
				}
				final BuffSkillHolder holder = table.getAvailableBuff(type, skillId);
				final Skill skill = holder == null ? null : SkillData.getInstance().getSkill(skillId, holder.getLevel());
				if (skill != null)
				{
					skill.applyEffects(me, me);
				}
			}
		}
		// Свои бафы Тирана: один тотем (общий эффект — сработает последний) и остальные.
		for (int id : TOTEMS)
		{
			final Skill totem = me.getKnownSkill(id);
			if (totem != null)
			{
				totem.applyEffects(me, me);
				break;
			}
		}
		for (int id : SELF_BUFFS)
		{
			final Skill skill = me.getKnownSkill(id);
			if (skill != null)
			{
				skill.applyEffects(me, me);
			}
		}
	}

	/**
	 * @return баф не его: воину — {@link #NO_BUFFS}, телу мага — {@link #MAGE_NO_BUFFS}; телу со щитом щитовые бафы ({@link #SHIELD_BUFFS}) — его
	 */
	private static boolean noBuff(Player me, int skillId)
	{
		final Item lhand = me.getInventory().getPaperdollItem(Inventory.PAPERDOLL_LHAND);
		if ((lhand != null) && lhand.isArmor() && SHIELD_BUFFS.contains(skillId))
		{
			return false;
		}
		return (me.isMageClass() ? MAGE_NO_BUFFS : NO_BUFFS).contains(skillId);
	}

	// ---------- Ходьба ----------

	/**
	 * Маршрут странника (решение автора 2026-10-23: «исследует мир», а не телепорт туда, где люди): у него есть курс; следующий спот — из соседних
	 * ({@value #NEXT_MIN}–{@value #NEXT_MAX} от него, нет таких — до {@value #NEXT_FAR}), не из последних {@value #MEMORY} посещённых, чем дальше по курсу — тем охотнее,
	 * с разбросом. Так он идёт по миру дугами и не кружит на месте. Засиделся в зоне — пешком в ближайшую другую (до {@value #NEXT_WALK_FAR}).
	 * Только туда, куда дойдёт пешком ({@link #walkable}): спот не в воде, по прямой нет моря. Все такие соседи — в памяти (обошёл свою землю — остров):
	 * память с чистого листа, по второму кругу, а уход отсюда — прыжком после убийства или через {@value #CORNERED} мс без жертв («он должен ходить, мерцать когда
	 * съёбывает»). Жертвы — только те, кого он увидел по пути.
	 * @param leaving уходит после убийства
	 * @return следующий спот или {@code null} — идти некуда
	 */
	private HuntSpot nextSpot(Player me, boolean leaving, long now)
	{
		final long started = System.nanoTime();
		_jumpWhy = null;
		_plans.clear();
		_plannedFor = null;
		if ((_cornered != null) && (leaving || ((now - _corneredSince) > _corneredFor)))
		{
			_jumpWhy = leaving ? "leaves his walked-through land after a kill" : "nobody to take on his walked-through land for " + (_corneredFor / 60000) + " min";
			final HuntSpot away = randomSpot(_cornered.region);
			_cornered = null;
			_visited.clear();
			return headTo(me, away);
		}
		HuntSpot best = pick(me, NEXT_MAX);
		if (best == null)
		{
			best = pick(me, NEXT_FAR);
		}
		// Засиделся в зоне, а другой в соседях нет — пешком в ближайшую другую, если дойдёт; нет такой — остаётся в своей.
		// Без перехода в другую зону маршрут ломается: офлайн по спотам H5 (2026-10-23) — 33 спота из 40 подряд в одной зоне.
		if ((best != null) && (_regionSteps >= REGION_STEPS) && (_spot != null) && best.region.equals(_spot.region))
		{
			final HuntSpot other = nearestOtherZone(me, _spot.region);
			if (other != null)
			{
				best = other;
			}
		}
		if (best == null)
		{
			// Все соседи, куда дойдёт, — в памяти: своя земля обойдена. Память — с чистого листа (кроме спота, где стоит), уход — прыжком после убийства.
			// Не дойти и до них — тупик (часть острова, башня): уход прыжком скоро.
			_visited.clear();
			if (_spot != null)
			{
				remember(_spot);
			}
			best = pick(me, NEXT_MAX);
			if (best == null)
			{
				best = pick(me, NEXT_FAR);
			}
			if ((_cornered == null) && (_spot != null))
			{
				_cornered = _spot;
				_corneredSince = now;
				_corneredFor = best != null ? CORNERED : TRAPPED;
				LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + (best != null ? " has walked through his land around " : " can walk nowhere from ") + _spot.name + "; he leaves it after the next kill or in " + (_corneredFor / 60000) + " min.");
			}
		}
		_plannedFor = best;
		_pickMs = (System.nanoTime() - started) / 1000000;
		return headTo(me, best);
	}

	/**
	 * @return сколько ждать следующей попытки, когда идти некуда: до срока ухода прыжком ({@link #nextSpot}) или {@value #RETRY}
	 */
	private long pause(long now)
	{
		return _cornered != null ? Math.max(RETRY, (_corneredSince + _corneredFor + TICK) - now) : RETRY;
	}

	/**
	 * Курс — к выбранному споту с небольшим разбросом: дуги, а не прямые.
	 */
	private HuntSpot headTo(Player me, HuntSpot spot)
	{
		if (spot != null)
		{
			_heading = Math.atan2(spot.entry.getY() - me.getY(), spot.entry.getX() - me.getX()) + ((Rnd.nextDouble() - 0.5) * 0.5);
		}
		return spot;
	}

	/**
	 * @return лучший по курсу сосед ({@value #NEXT_MIN}–{@code max} от него, не из недавних, не в воде), до которого есть путь пешком; проверяются лучшие {@value #PICK_CHECKS}
	 */
	private HuntSpot pick(Player me, int max)
	{
		final List<HuntSpot> spots = new ArrayList<>();
		final Map<HuntSpot, Double> scores = new HashMap<>();
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.level < SPOT_MIN) || (spot.level > SPOT_MAX) || _visited.contains(spot))
			{
				continue;
			}
			final double dx = spot.entry.getX() - me.getX();
			final double dy = spot.entry.getY() - me.getY();
			final double distance = Math.hypot(dx, dy);
			if ((distance < NEXT_MIN) || (distance > max))
			{
				continue;
			}
			// По курсу: косинус угла к курсу (1 — прямо, −1 — назад) и немного случая; засиделся в одной зоне — туда, где другая.
			final boolean sameRegion = (_spot != null) && spot.region.equals(_spot.region);
			scores.put(spot, Math.cos(Math.atan2(dy, dx) - _heading) + (Rnd.nextDouble() * 0.6) + ((_regionSteps >= REGION_STEPS) ? (sameRegion ? -2 : 1) : 0));
			spots.add(spot);
		}
		spots.sort((x, y) -> Double.compare(scores.get(y), scores.get(x)));
		return firstWalkable(me, spots);
	}

	/**
	 * @return ближайший спот {@value #SPOT_MIN}–{@value #SPOT_MAX} уровня в другой зоне, не дальше {@value #NEXT_WALK_FAR}, не из недавних, до которого есть путь пешком
	 */
	private HuntSpot nearestOtherZone(Player me, String region)
	{
		final List<HuntSpot> spots = new ArrayList<>();
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.level >= SPOT_MIN) && (spot.level <= SPOT_MAX) && !spot.region.equals(region) && !_visited.contains(spot) && (me.calculateDistance2D(spot.entry) <= NEXT_WALK_FAR))
			{
				spots.add(spot);
			}
		}
		spots.sort((x, y) -> Double.compare(me.calculateDistance2D(x.entry), me.calculateDistance2D(y.entry)));
		return firstWalkable(me, spots);
	}

	/**
	 * Первый из списка, до кого есть путь пешком ({@link #plan}); пути — в {@link #_plans}: в одном выборе спот проверяется раз.
	 */
	private HuntSpot firstWalkable(Player me, List<HuntSpot> spots)
	{
		for (int i = 0; i < Math.min(spots.size(), PICK_CHECKS); i++)
		{
			final HuntSpot spot = spots.get(i);
			if (!_plans.containsKey(spot))
			{
				_plans.put(spot, !wet(spot.entry) && walkable(me.getX(), me.getY(), me.getZ(), spot.entry) ? plan(me.getX(), me.getY(), me.getZ(), spot.entry, me.getInstanceId()) : null);
			}
			if (_plans.get(spot) != null)
			{
				return spot;
			}
		}
		return null;
	}

	/**
	 * Дешёвый отсев до поиска пути: по прямой к цели — геодата везде, а вода (море, озеро) — не длиннее {@value #WET_RUN} подряд, точки — через {@value #SAMPLE},
	 * высота — по земле. Лог мира автора 2026-10-23: с Devils Isle к Bee Hive он пошёл морем и застрял на берегу.
	 */
	private static boolean walkable(int x, int y, int z, Location to)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double dx = to.getX() - x;
		final double dy = to.getY() - y;
		final double length = Math.hypot(dx, dy);
		int height = z;
		int wetRun = 0;
		for (int step = SAMPLE; step < length; step += SAMPLE)
		{
			final int sx = x + (int) ((dx / length) * step);
			final int sy = y + (int) ((dy / length) * step);
			if (!geo.hasGeo(sx, sy))
			{
				return false;
			}
			height = geo.getHeight(sx, sy, height);
			wetRun = wet(sx, sy, height) ? wetRun + SAMPLE : 0;
			if (wetRun > WET_RUN)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * @return точка в воде: у Mobius море — зона WaterZone до уровня моря, и низина Devils Isle (13 спотов H5) — в ней: там он «плывёт» без геодаты и встаёт
	 */
	private static boolean wet(int x, int y, int z)
	{
		return ZoneManager.getInstance().getZone(x, y, z + 16, WaterZone.class) != null;
	}

	private static boolean wet(Location at)
	{
		return wet(at.getX(), at.getY(), at.getZ());
	}

	/**
	 * @return любой спот {@value #SPOT_MIN}–{@value #SPOT_MAX} уровня не в воде — выход в мир и уход прыжком
	 */
	private static HuntSpot randomSpot(String notRegion)
	{
		final List<HuntSpot> all = new ArrayList<>();
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.level >= SPOT_MIN) && (spot.level <= SPOT_MAX) && !spot.region.equals(notRegion) && !wet(spot.entry))
			{
				all.add(spot);
			}
		}
		return all.isEmpty() ? null : all.get(Rnd.get(all.size()));
	}

	/**
	 * @return случайный охотник на каче (не в городе, не в охоте) — для самопроверки; сильный ({@value PhantomRaid#HUNT_LEVEL}+), если есть: он соберёт охоту
	 */
	private static Player huntingHunter()
	{
		final List<Player> hunters = new ArrayList<>();
		final List<Player> strong = new ArrayList<>();
		for (Player other : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(other) && PhantomRelations.isHunter(other) && fair(other) && !PhantomRaid.getInstance().hunting(other))
			{
				(other.getLevel() >= PhantomRaid.HUNT_LEVEL ? strong : hunters).add(other);
			}
		}
		final List<Player> from = strong.isEmpty() ? hunters : strong;
		return from.isEmpty() ? null : from.get(Rnd.get(from.size()));
	}

	/**
	 * @return спот охотников, ближайший к игроку
	 */
	private static HuntSpot nearestSpot(Player player)
	{
		HuntSpot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			final double distance = player.calculateDistance2D(spot.entry);
			if (distance < bestDistance)
			{
				best = spot;
				bestDistance = distance;
			}
		}
		return best;
	}

	private void remember(HuntSpot spot)
	{
		_visited.addLast(spot);
		while (_visited.size() > MEMORY)
		{
			_visited.removeFirst();
		}
	}

	/**
	 * Шагом к споту (решение автора 2026-10-23: «он должен ходить, мерцать когда съёбывает»): по точкам пути, найденного при выборе спота ({@link #plan}),
	 * а не прямым ходом Mobius сквозь горы; пути нет (шаг в сторону, мерцание) — новый от того места. Корни, стан, сон, каст — стоит не по своей воле, это
	 * не застрял. Не сдвинулся {@value #STUCK} мс или пути нет — {@link #stuck}: шаг в сторону, без телепорта.
	 */
	private void travel(Player me, long now)
	{
		if ((me.getX() != _lastX) || (me.getY() != _lastY))
		{
			_lastX = me.getX();
			_lastY = me.getY();
			_movedAt = now;
		}
		if (me.isMovementDisabled() || me.isCastingNow())
		{
			_movedAt = now;
			return;
		}
		if ((now - _movedAt) > STUCK)
		{
			stuck(me, now, "stood " + (STUCK / 1000) + " s, intention " + me.getAI().getIntention() + (me.isInsideZone(ZoneId.WATER) ? ", in water" : ""));
			return;
		}
		boolean next = false;
		if ((_route == null) || (_routeStep >= _route.size()))
		{
			_route = plan(me.getX(), me.getY(), me.getZ(), _spot.entry, me.getInstanceId());
			_routeStep = 0;
			if (_route == null)
			{
				stuck(me, now, "no way");
				return;
			}
			next = true;
		}
		Location point = _route.get(_routeStep);
		if (me.calculateDistance2D(point) < 100)
		{
			_routeStep++;
			if (_routeStep >= _route.size())
			{
				return;
			}
			point = _route.get(_routeStep);
			next = true;
		}
		// Шагом: пока не видит жертву, жнец идёт (решение автора 2026-10-23); бежит — только за жертвой.
		if (next || !me.isMoving() || (me.getAI().getIntention() != Intention.MOVE_TO))
		{
			me.setWalking();
			me.getAI().setIntentionMoveTo(point);
		}
	}

	/**
	 * Путь пешком на весь перегон ({@link PhantomPath}, до {@value #PATH_NODES} узлов) — по суше: вода подряд не длиннее {@value #WET_RUN} (ручей перейдёт, морем
	 * не пойдёт). Офлайн по спотам H5 (2026-10-23): соседние споты без пути друг к другу — 12 % (части Devils Isle, Forgotten Temple, Cruma), с шагом сетки 32
	 * вместо 96 — столько же: это разрывы геодаты, а не грубость сетки.
	 * @return точки пути (последняя — в 300 от цели) или {@code null} — не дойти
	 */
	private static List<Location> plan(int x, int y, int z, Location target, int instanceId)
	{
		final List<Location> path = PhantomPath.find(x, y, z, target.getX(), target.getY(), target.getZ(), 300, instanceId, PATH_NODES);
		if (path == null)
		{
			return null;
		}
		final GeoEngine geo = GeoEngine.getInstance();
		int fromX = x;
		int fromY = y;
		int height = z;
		int wetRun = 0;
		for (Location point : path)
		{
			final double length = Math.hypot(point.getX() - fromX, point.getY() - fromY);
			for (int step = SAMPLE; step < length; step += SAMPLE)
			{
				final int sx = fromX + (int) (((point.getX() - fromX) * step) / length);
				final int sy = fromY + (int) (((point.getY() - fromY) * step) / length);
				height = geo.getHeight(sx, sy, height);
				wetRun = wet(sx, sy, height) ? wetRun + SAMPLE : 0;
				if (wetRun > WET_RUN)
				{
					return null;
				}
			}
			fromX = point.getX();
			fromY = point.getY();
			height = point.getZ();
		}
		return path;
	}

	/**
	 * Застрял: в лог со счётом и причиной; шаг в сторону ({@link #sidestep}) — и новый путь; застрял {@value #STUCK_GIVE_UP} раза на перегоне — бросает спот и идёт
	 * к другому. К цели не телепортируется никогда (лог мира автора 2026-10-23: аварийное мерцание с Devils Isle легло прямо у Bee Hive).
	 */
	private void stuck(Player me, long now, String why)
	{
		_stucks++;
		_legStucks++;
		_movedAt = now;
		_route = null;
		final String where = getClass().getSimpleName() + ": " + me.getName() + " is stuck on the way to " + _spot.name + " (" + (int) me.calculateDistance2D(_spot.entry) + " left; " + why + ")";
		if (_legStucks >= STUCK_GIVE_UP)
		{
			final HuntSpot next = nextSpot(me, false, now);
			LOGGER.info(where + ", gives it up for " + (next != null ? next.name : "nothing yet") + "; stuck " + _stucks + " times since he appeared.");
			if (next != null)
			{
				goTo(me, next, now, false);
			}
			else
			{
				// Пешком некуда — стоит где стоит: до жертвы (после убийства — прыжок) или до срока ухода прыжком ({@link #nextSpot}).
				_spot = null;
				_retryAt = now + pause(now);
			}
			return;
		}
		final Location side = sidestep(me, _spot.entry);
		if (side != null)
		{
			_route = new ArrayList<>(List.of(side));
			_routeStep = 0;
		}
		LOGGER.info(where + (side != null ? ", steps aside" : ", tries again") + "; stuck " + _stucks + " times since he appeared.");
	}

	/**
	 * @return шаг в сторону: сухая точка в 300–700 от него под углом 60–120° к цели, куда геодата пускает по прямой; нет такой — {@code null}
	 */
	private static Location sidestep(Player me, Location target)
	{
		final double course = Math.atan2(target.getY() - me.getY(), target.getX() - me.getX());
		final int side = Rnd.nextBoolean() ? 1 : -1;
		for (int attempt = 0; attempt < 8; attempt++)
		{
			final double angle = course + ((attempt % 2) == 0 ? side : -side) * Math.toRadians(Rnd.get(60, 120));
			final int r = Rnd.get(300, 700);
			final Location at = ground(me.getX() + (int) (Math.cos(angle) * r), me.getY() + (int) (Math.sin(angle) * r), me.getZ());
			if ((at != null) && !wet(at) && PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), me.getX(), me.getY(), me.getZ(), at.getX(), at.getY(), at.getZ(), me.getInstanceId()))
			{
				return at;
			}
		}
		return null;
	}

	/**
	 * Дальше по маршруту: убил — мерцание сразу («телепорт чуть дальше», и дальше шагом); на споте никого — шагом.
	 */
	private void moveOn(Player me, long now, boolean killed)
	{
		final HuntSpot next = nextSpot(me, killed, now);
		if (next == null)
		{
			// Пешком некуда — стоит на споте дальше: до жертвы или до срока ухода прыжком. Без этого спрашивал маршрут каждый тик.
			_arrivedAt = now;
			_stayFor = pause(now);
			_retryAt = now + _stayFor;
			return;
		}
		goTo(me, next, now, killed);
	}

	private void goTo(Player me, HuntSpot next, long now, boolean blinkNow)
	{
		// Ушёл ногами далеко от обойдённой земли — она его больше не держит.
		if ((_cornered != null) && (_jumpWhy == null) && (Math.hypot(next.entry.getX() - _cornered.entry.getX(), next.entry.getY() - _cornered.entry.getY()) > NEXT_FAR))
		{
			_cornered = null;
		}
		_regionSteps = (_spot != null) && next.region.equals(_spot.region) ? _regionSteps + 1 : 0;
		_spot = next;
		remember(next);
		_arrivedAt = 0;
		_movedAt = now;
		_route = null;
		_legStucks = 0;
		_stayFor = Rnd.get(STAY_MIN, STAY_MAX);
		final int distance = (int) Math.hypot(next.entry.getX() - me.getX(), next.entry.getY() - me.getY());
		final String how;
		if (_jumpWhy != null)
		{
			// Своя земля обойдена ({@link #nextSpot}) — уход прыжком в другие земли, прямо к споту.
			final Location at = ground(next.entry.getX() + Rnd.get(-BLINK_MAX, BLINK_MAX), next.entry.getY() + Rnd.get(-BLINK_MAX, BLINK_MAX), next.entry.getZ());
			calm(me);
			me.teleToLocation((at != null) && !wet(at) ? at : next.entry, false);
			how = "jumps (" + _jumpWhy + ")";
		}
		else
		{
			final List<Location> blinked = blinkNow ? blink(me, next.entry) : null;
			_route = blinked != null ? blinked : next == _plannedFor ? _plans.get(next) : null;
			_routeStep = 0;
			how = blinked != null ? "blinks after a kill" : blinkNow ? "walks (no dry ground to blink to)" : "walks";
		}
		_jumpWhy = null;
		LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + " " + how + " to " + next.name + " (" + distance + (_route != null ? "; path " + pathLength(me, _route) : "") + (next == _plannedFor ? "; picked in " + _pickMs + " ms" : "") + ").");
	}

	/**
	 * Телепорт «чуть дальше» — только когда уходит, после убийства: на {@value #BLINK_MIN}–{@value #BLINK_MAX} к цели, на сухую землю, от которой до цели есть
	 * путь пешком ({@link #plan}; по курсу, потом с поворотом ±20°, ±40°). Нет такой — не мерцает, идёт шагом; к самой цели — никогда.
	 * @return путь от места мерцания или {@code null} — не мерцнул
	 */
	private static List<Location> blink(Player me, Location target)
	{
		final double length = me.calculateDistance2D(target);
		final double course = Math.atan2(target.getY() - me.getY(), target.getX() - me.getX());
		for (double turn : BLINK_TURNS)
		{
			final double step = Math.min(length - 300, Rnd.get(BLINK_MIN, BLINK_MAX));
			if (step < 500)
			{
				return null;
			}
			final Location at = ground(me.getX() + (int) (Math.cos(course + turn) * step), me.getY() + (int) (Math.sin(course + turn) * step), me.getZ());
			final List<Location> path = (at != null) && !wet(at) ? plan(at.getX(), at.getY(), at.getZ(), target, me.getInstanceId()) : null;
			if (path != null)
			{
				calm(me);
				me.teleToLocation(at, false);
				return path;
			}
		}
		return null;
	}

	/**
	 * @return длина пути от него по точкам — в лог
	 */
	private static int pathLength(Player me, List<Location> path)
	{
		double length = 0;
		int x = me.getX();
		int y = me.getY();
		for (Location point : path)
		{
			length += Math.hypot(point.getX() - x, point.getY() - y);
			x = point.getX();
			y = point.getY();
		}
		return (int) length;
	}

	/**
	 * @return точка на земле по геодате или {@code null} (высота уехала — обрыв, пещера)
	 */
	private static Location ground(int x, int y, int z)
	{
		final int gz = GeoEngine.getInstance().getHeight(x, y, z);
		return Math.abs(gz - z) < 600 ? new Location(x, y, gz) : null;
	}

	private static void calm(Player me)
	{
		me.abortAttack();
		me.setTarget(null);
		me.getAI().setIntentionActive();
	}

	// ---------- Охота ----------

	/**
	 * Жертва на споте: охотник или живой игрок (его — если не защищён: сам Танатос выбирает живого не чаще раза в {@value #PLAYER_GAP} мс);
	 * живой — с шансом в половину, если есть и охотники.
	 */
	private static Player pickVictim(Player me, long now)
	{
		final List<Player> hunters = new ArrayList<>();
		final List<Player> players = new ArrayList<>();
		for (Player other : World.getVisibleObjectsInRange(me, Player.class, VICTIM_RANGE))
		{
			if (!fair(other))
			{
				continue;
			}
			if (PhantomManager.getInstance().isPhantom(other))
			{
				if (PhantomRelations.isHunter(other))
				{
					hunters.add(other);
				}
			}
			else if (now >= other.getVariables().getLong(MARK_VARIABLE, 0))
			{
				players.add(other);
			}
		}
		if (!players.isEmpty() && (hunters.isEmpty() || Rnd.nextBoolean()))
		{
			return players.get(Rnd.get(players.size()));
		}
		return hunters.isEmpty() ? null : hunters.get(Rnd.get(hunters.size()));
	}

	/**
	 * @return его можно бить: жив, виден, не в мирной зоне, не на олимпиаде, ивенте или дуэли, не GM
	 */
	private static boolean fair(Player other)
	{
		return !other.isDead() && !other.isInvisible() && !other.isGM() && !other.isInsideZone(ZoneId.PEACE) && !other.isInOlympiadMode() && !other.isOnEvent() && !other.isInDuel() && !is(other);
	}

	/**
	 * @param chosen жертву выбрал он сам (не ответ на удар): живому — предвестие и защита на {@value #PLAYER_GAP} мс
	 */
	private void startChase(Player me, Player victim, long now, boolean chosen)
	{
		_victimId = victim.getObjectId();
		_chaseSince = now;
		_strikeAt = now;
		// Погоня уводит с пути: после неё путь к споту — заново от того места.
		_route = null;
		if (chosen && !PhantomManager.getInstance().isPhantom(victim))
		{
			_strikeAt = now + Rnd.get(FORESHADOW_MIN, FORESHADOW_MAX);
			victim.getVariables().set(MARK_VARIABLE, now + PLAYER_GAP);
			victim.sendMessage(OMENS[Rnd.get(OMENS.length)]);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + " hunts " + victim.getName() + " (" + victim.getLevel() + (PhantomManager.getInstance().isPhantom(victim) ? "" : ", player") + (chosen ? "" : ", fights back") + ").");
	}

	/**
	 * Гонится: до удара (предвестие) — держится поодаль, потом бьёт. Белого — как ctrl+клик (AutoPlay и скиллы белого не берут), с флагом или кармой — боем со скиллами.
	 */
	private void chase(Player me, Player victim, long now)
	{
		if (me.isCastingNow())
		{
			return;
		}
		if (now < _strikeAt)
		{
			if (me.calculateDistance2D(victim) > 700)
			{
				me.setRunning();
				me.getAI().setIntentionMoveTo(victim.getLocation());
			}
			return;
		}
		strike(me, victim);
	}

	/**
	 * Удар Тирана: первый готовый из {@link #ATTACKS} (с ctrl — белого тоже); ничего не готово и силы меньше {@value #CHARGES_FIGHT} — Focused Force; иначе рукой.
	 * Силу он копит до {@value #CHARGES_IDLE} заранее, между жертвами ({@link #charge}).
	 * Сам собран как напарник ({@code PhantomOutfit.build}), AutoUse у него пуст: без этого он бил только рукой (прогон H5 2026-10-23 — охота четверых 70+ брала его за 35 с).
	 */
	private void strike(Player me, Player victim)
	{
		if (me.getTarget() != victim)
		{
			me.setTarget(victim);
		}
		// Тело не Тирана (силы Focused Force у него нет) — свои удары.
		if ((me.getKnownSkill(FOCUSED_FORCE) == null) && !_attacks.isEmpty())
		{
			strikeAsBody(me, victim);
			return;
		}
		// Хилер — сначала оглушить Soul Breaker: пока он стоит, его не лечат и он не лечит.
		final Skill stun = healer(victim) ? me.getKnownSkill(SOUL_BREAKER) : null;
		if ((stun != null) && ready(me, stun, victim))
		{
			me.setRunning();
			if (PhantomCompat.useMagic(me, stun, true, false))
			{
				CASTS.merge(stun.getName(), 1, Integer::sum);
				return;
			}
		}
		// Удары: что готово — с силой или без; под ударами силу не копит, пока есть чем бить (прогон H5 2026-10-23: Focused Force 41 раз на 31 Hurricane Assault).
		for (int id : ATTACKS)
		{
			final Skill skill = me.getKnownSkill(id);
			if ((skill != null) && ready(me, skill, victim))
			{
				me.setRunning();
				if (PhantomCompat.useMagic(me, skill, true, false))
				{
					CASTS.merge(skill.getName(), 1, Integer::sum);
					return;
				}
			}
		}
		// Ничего не готово — добрать силы (рядом с жертвой, между ударами рукой).
		final Skill force = me.getKnownSkill(FOCUSED_FORCE);
		if ((force != null) && (me.getCharges() < CHARGES_FIGHT) && !me.isAttackingNow() && ready(me, force, me) && PhantomCompat.useMagic(me, force, false, false))
		{
			CASTS.merge(force.getName(), 1, Integer::sum);
			return;
		}
		if ((me.getAI().getIntention() != Intention.ATTACK) || (!me.isAttackingNow() && !me.isMoving()))
		{
			me.setRunning();
			me.getAI().setIntentionAttack(victim);
		}
	}

	/**
	 * Удар тела не Тирана: первый готовый из {@link #_attacks} (с ctrl — белого тоже); ничего не готово — оружием (маг — тоже: стоять хуже).
	 */
	private void strikeAsBody(Player me, Player victim)
	{
		for (Skill skill : _attacks)
		{
			if (ready(me, skill, victim))
			{
				me.setRunning();
				if (PhantomCompat.useMagic(me, skill, true, false))
				{
					CASTS.merge(skill.getName(), 1, Integer::sum);
					return;
				}
			}
		}
		if ((me.getAI().getIntention() != Intention.ATTACK) || (!me.isAttackingNow() && !me.isMoving()))
		{
			me.setRunning();
			me.getAI().setIntentionAttack(victim);
		}
	}

	/**
	 * @return скилл можно кастовать сейчас: не на откате, хватает MP и силы, условия скилла выполнены
	 */
	private static boolean ready(Player me, Skill skill, Creature target)
	{
		return !me.isSkillDisabled(skill) && (me.getCurrentMp() >= (skill.getMpInitialConsume() + skill.getMpConsume())) && (skill.getChargeConsumeCount() <= me.getCharges()) && skill.checkCondition(me, target, false);
	}

	/**
	 * Банки, как у живого ПК: CP, быстрая хилка на половине HP, хилка со временем — по порогам.
	 */
	private void drink(Player me, long now)
	{
		if (me.isDead() || me.isInsideZone(ZoneId.PEACE))
		{
			return;
		}
		if (((me.getCurrentCp() * 100) < (me.getMaxCp() * CP_DRINK)) && quaff(me, CP_POTION))
		{
			CASTS.merge("CP Potion", 1, Integer::sum);
		}
		final double hp = (me.getCurrentHp() * 100) / Math.max(1, me.getMaxHp());
		if ((hp < QUICK_DRINK) && ((now - _quickAt) >= QUICK_GAP) && quaff(me, QUICK_POTION))
		{
			_quickAt = now;
			CASTS.merge("Quick Healing Potion", 1, Integer::sum);
		}
		if ((hp < HP_DRINK) && ((now - _hpAt) >= HP_GAP) && quaff(me, HP_POTION))
		{
			_hpAt = now;
			CASTS.merge("Healing Potion", 1, Integer::sum);
		}
	}

	/**
	 * Выпить банку, как игрок (обработчик предмета: эффект и расход).
	 * @return выпил
	 */
	private static boolean quaff(Player me, int itemId)
	{
		final Item item = me.getInventory().getItemByItemId(itemId);
		if (item == null)
		{
			return false;
		}
		final IItemHandler handler = ItemHandler.getInstance().getHandler(item.getEtcItem());
		return (handler != null) && handler.onItemUse(me, item, false);
	}

	/**
	 * @return лекарь: охотник с ролью хилера, живой игрок — по классу (епископ, старейшина, кардинал, святой, клерик, оракул)
	 */
	private static boolean healer(Player player)
	{
		if (player == null)
		{
			return false;
		}
		final PhantomTemplate template = PhantomManager.getInstance().isPhantom(player) ? PhantomData.getInstance().getTemplate(player.getName()) : null;
		if (template != null)
		{
			return template.getRole() == PhantomTemplate.Role.HEALER;
		}
		final String name = player.getPlayerClass().name();
		return name.contains("BISHOP") || name.contains("ELDER") || name.contains("CARDINAL") || name.contains("SAINT") || name.contains("CLERIC") || name.contains("ORACLE");
	}

	/**
	 * Между жертвами — набрать силу до {@value #CHARGES_IDLE}, как Тиран перед боем.
	 * @return кастует
	 */
	private static boolean charge(Player me)
	{
		final Skill force = me.getKnownSkill(FOCUSED_FORCE);
		if ((force == null) || me.isCastingNow() || (me.getCharges() >= CHARGES_IDLE) || !ready(me, force, me))
		{
			return false;
		}
		return PhantomCompat.useMagic(me, force, false, false);
	}

	/**
	 * @return кто бил его недавно и рядом — ему ответ сразу, без защиты
	 */
	private Player aggressor(Player me, long now)
	{
		Player best = null;
		double bestDistance = VICTIM_RANGE;
		boolean bestHealer = false;
		for (Map.Entry<Integer, Long> entry : _aggressors.entrySet())
		{
			if ((now - entry.getValue()) > AGGRESSOR_MEMORY)
			{
				_aggressors.remove(entry.getKey());
				continue;
			}
			final Player other = World.getPlayer(entry.getKey());
			if ((other == null) || !fair(other))
			{
				continue;
			}
			// Хилер — первым: без него группа сыплется (решение автора 2026-10-23).
			final double distance = me.calculateDistance2D(other);
			final boolean isHealer = healer(other);
			if ((distance < VICTIM_RANGE) && ((isHealer && !bestHealer) || ((isHealer == bestHealer) && (distance < bestDistance))))
			{
				best = other;
				bestDistance = distance;
				bestHealer = isHealer;
			}
		}
		return best;
	}

	/**
	 * Удар по нему: от игрока или охотника — тот в списке обидчиков (ответ сразу); от моба — урон гасится: жнец мобов не замечает и от них не умирает
	 * (прогон H5 2026-10-23: агрессивные мобы спота 70+ забили его, пока он бегал).
	 */
	private void onDamaged(OnCreatureDamageReceived event)
	{
		final Player attacker = acting(event.getAttacker());
		if ((attacker != null) && !is(attacker))
		{
			final long now = System.currentTimeMillis();
			_aggressors.put(attacker.getObjectId(), now);
			// Голос: живой напал сам, не в охоте — раз на бой.
			if (!PhantomManager.getInstance().isPhantom(attacker) && !PhantomRaid.getInstance().hunting(attacker))
			{
				final Long since = _attackSaid.get(attacker.getObjectId());
				_attackSaid.put(attacker.getObjectId(), now);
				if ((since == null) || ((now - since) > ATTACK_FIGHT))
				{
					final Player me = get();
					if (me != null)
					{
						voice(me, "attacked", attacker, "", 0, text -> sayNear(me, text));
					}
				}
			}
			return;
		}
		final Creature target = event.getTarget();
		if ((attacker == null) && (event.getAttacker() != null) && event.getAttacker().isMonster() && (target != null) && !target.isDead())
		{
			target.setCurrentHp(Math.min(target.getMaxHp(), target.getCurrentHp() + event.getDamage()));
		}
	}

	/**
	 * Охотники рядом: один кричит в общий чат (не чаще {@value #SHOUT_GAP} мс), слабые бегут; есть сильные — охота ({@link PhantomRaid#hunt}, не чаще {@value #HUNT_GAP} мс).
	 */
	private void seen(Player me, long now)
	{
		Player witness = null;
		for (Player hunter : World.getVisibleObjectsInRange(me, Player.class, SEE_RANGE))
		{
			if (hunter.isDead() || !PhantomRelations.isHunter(hunter) || PhantomRaid.getInstance().hunting(hunter))
			{
				continue;
			}
			if (witness == null)
			{
				witness = hunter;
			}
			if ((hunter.getLevel() < PhantomRaid.HUNT_LEVEL) && (hunter.getObjectId() != _victimId))
			{
				PhantomPk.getInstance().fleeFrom(hunter, me);
			}
		}
		if (witness == null)
		{
			return;
		}
		if ((now - _lastShout) >= SHOUT_GAP)
		{
			_lastShout = now;
			PhantomChat.getInstance().hunterAlarm(witness, String.format(ALARMS[Rnd.get(ALARMS.length)], _spot != null ? _spot.name : PhantomRaid.placeOf(me)));
			LOGGER.info(getClass().getSimpleName() + ": " + witness.getName() + " saw " + me.getName() + ".");
		}
		if (((now - _lastHunt) >= HUNT_GAP) && PhantomRaid.getInstance().hunt(me, now))
		{
			_lastHunt = now;
		}
	}

	// ---------- Смерть ----------

	private void onDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if (!(target instanceof Player))
		{
			return;
		}
		final Player victim = (Player) target;
		final Player killer = acting(event.getAttacker());
		if (is(victim))
		{
			onSelfDeath(victim, killer);
		}
		else if (is(killer))
		{
			onKill(killer, victim);
		}
	}

	/**
	 * Убил: в летопись; опыт жертве — назад (Mobius снимает его после события смерти, поэтому — через секунду).
	 */
	private void onKill(Player me, Player victim)
	{
		final boolean hunter = PhantomManager.getInstance().isPhantom(victim);
		LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + " killed " + victim.getName() + " (" + victim.getLevel() + (hunter ? "" : ", player") + "); casts " + CASTS + ".");
		PhantomNews.post("thanatos", "Танатос забрал " + (hunter ? "охотника " : "игрока ") + victim.getName() + " (" + victim.getLevel() + ")", me.getName(), victim);
		// Голос: живому — шёпотом; об охотнике — вслух, если рядом слышит живой (иначе мозги не звать).
		if (!hunter || _selfTest)
		{
			voice(me, "kill", victim, "", 0, text -> whisper(me, victim, text));
		}
		else if (liveNear(me))
		{
			voice(me, "kill", victim, "", 0, text -> sayNear(me, text));
		}
		townFakes("thanatos_kill", victim, victim.getName());
		ThreadPool.schedule(() -> restoreExp(victim), 1000);
	}

	private static void restoreExp(Player victim)
	{
		final long before = victim.getExpBeforeDeath();
		final long lost = before - victim.getExp();
		if ((before > 0) && (lost > 0))
		{
			victim.getStat().addExp(lost);
			victim.setExpBeforeDeath(0);
			LOGGER.info(PhantomThanatos.class.getSimpleName() + ": " + victim.getName() + " gets back " + lost + " exp lost to him.");
			if (!PhantomManager.getInstance().isPhantom(victim))
			{
				victim.sendMessage("Смерть от Танатоса не забирает опыт.");
			}
		}
	}

	/**
	 * Пал: сутки без него, в летопись. Вещи роняет карма — штатно.
	 */
	private synchronized void onSelfDeath(Player me, Player killer)
	{
		// Событие смерти приходит дважды (на самого и общим слушателем) — считать одну.
		if (_deathHandled)
		{
			return;
		}
		_deathHandled = true;
		final long now = System.currentTimeMillis();
		GlobalVariablesManager.getInstance().set(BACK_VARIABLE, now + DEATH_ABSENCE);
		_deadSince = now;
		_victimId = 0;
		// Вернётся другим (решение автора 2026-10-24: «после убийства танатоса меняется его класс и шмотки»).
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		final String body = template == null ? "?" : nextBody(template);
		final String by = killer == null ? "" : ": его убил " + (PhantomManager.getInstance().isPhantom(killer) ? "охотник " : "игрок ") + killer.getName() + " (" + killer.getLevel() + ")";
		LOGGER.info(getClass().getSimpleName() + ": " + me.getName() + " died" + (killer != null ? " (" + killer.getName() + ")" : "") + "; back in 24 h as body " + body + "; casts " + CASTS + ".");
		PhantomNews.post("thanatos", "Танатос пал" + by + ". Сутки его не будет", killer != null ? killer.getName() : me.getName(), me);
		// Голос: последнее слово вслух; party — сколько было в охоте, без охоты — сколько били его в последние полминуты.
		if ((killer != null) && (liveNear(me) || _selfTest))
		{
			final int party = PhantomRaid.getInstance().huntSize();
			voice(me, "death", killer, "", party > 0 ? party : Math.max(1, _aggressors.size()), text -> sayNear(me, text));
		}
		townFakes("thanatos_dead", me, killer != null ? killer.getName() : "");
	}

	/**
	 * Фейки в городах (c4-interlude, патч 0031-town-fakes, скрипт {@code custom/L2HubTownFakes}): в ближайшем к месту городе, где их слышит живой игрок, 1–2 фейка
	 * откликаются на новость. Без скрипта (EnableFakePlayers = False, нет патча) — ничего; зависимости при компиляции нет.
	 * @param kind {@code thanatos} — появился, {@code thanatos_kill} — убил ({@code text} — жертва), {@code thanatos_dead} — убит ({@code text} — кто)
	 */
	private static void townFakes(String kind, Player at, String text)
	{
		try
		{
			final Quest script = ScriptManager.getInstance().getScript("L2HubTownFakes");
			if (script != null)
			{
				script.notifyEvent("world:" + kind + ":" + at.getX() + "," + at.getY() + ":" + text, null, null);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomThanatos.class.getSimpleName() + ": town fakes did not take " + kind + ".", e);
		}
	}

	// ---------- Голос (раздел 4.45, «Голос»: мозги, kind = reaper) ----------

	/**
	 * Охота вступила в бой ({@link PhantomRaid}, раз на охоту) — вслух.
	 * @param leader лидер охоты
	 * @param party сколько в охоте
	 */
	void onHunt(Player leader, int party)
	{
		final Player me = get();
		if ((me != null) && (liveNear(me) || _selfTest))
		{
			voice(me, "hunt", leader, "", party, text -> sayNear(me, text));
		}
	}

	/**
	 * Шёпот ему ({@code PhantomChat.onWhisper}): ответ шёпотом, одному игроку — не чаще раза в {@value #WHISPER_GAP} мс; остальные — молча, без запроса
	 * (модель отвечает почти всегда, «чаще молчит» держится частотой).
	 */
	void onWhisper(Player speaker, Player me, String text)
	{
		final long now = System.currentTimeMillis();
		if ((now - _whispered.getOrDefault(speaker.getObjectId(), 0L)) < WHISPER_GAP)
		{
			return;
		}
		_whispered.put(speaker.getObjectId(), now);
		voice(me, "whisper", speaker, text, 0, reply -> whisper(me, speaker, reply));
	}

	/**
	 * Запрос мозгам ({@code POST /chat}, {@code "kind":"reaper"}): асинхронно, бой не ждёт; пустой ответ, 502 и 503 — молчит.
	 * @param event kill, death, hunt, attacked, escaped, whisper
	 * @param player собеседник: жертва, убийца, лидер охоты, кто напал, кто ушёл, кто шепнул
	 * @param message текст шёпота (у остальных — пусто)
	 * @param party сколько в охоте (death, hunt)
	 * @param deliver куда отдать реплику
	 */
	private static void voice(Player me, String event, Player player, String message, int party, java.util.function.Consumer<String> deliver)
	{
		if (!PhantomChat.brainEnabled() || (player == null))
		{
			return;
		}
		final StringBuilder sb = new StringBuilder(512);
		sb.append('{');
		FakePlayerBrain.field(sb, "kind", "reaper").append(',');
		FakePlayerBrain.field(sb, "fpc", me.getName()).append(',');
		FakePlayerBrain.field(sb, "event", event).append(',');
		FakePlayerBrain.field(sb, "message", message).append(',');
		sb.append("\"party\":").append(party).append(',');
		FakePlayerBrain.field(sb, "lang", L2HubWorldConfig.LANGUAGE != null ? L2HubWorldConfig.LANGUAGE : "ru").append(',');
		sb.append("\"player\":{");
		FakePlayerBrain.field(sb, "name", player.getName()).append(',');
		sb.append("\"level\":").append(player.getLevel()).append(',');
		FakePlayerBrain.field(sb, "class", player.getPlayerClass().name()).append(',');
		FakePlayerBrain.field(sb, "race", player.getRace().name()).append(',');
		sb.append("\"female\":").append(player.getAppearance().isFemale()).append(',');
		sb.append("\"hunter\":").append(PhantomRelations.isHunter(player));
		sb.append("},\"reaper\":{\"level\":").append(me.getLevel()).append(",\"hpPct\":").append((int) ((me.getCurrentHp() * 100) / Math.max(1, me.getMaxHp()))).append(',');
		// Класс нынешнего тела (раздел 4.45, «Новое тело»): с виду орк-Тиран, а бьёт, например, как Spellsinger — реплики под класс у мозгов.
		FakePlayerBrain.field(sb, "class", me.getPlayerClass().name()).append("}}");
		FakePlayerBrain.getInstance().send(sb.toString(), reply ->
		{
			final String line = reply == null ? "" : reply.strip();
			if (!line.isEmpty())
			{
				LOGGER.info(PhantomThanatos.class.getSimpleName() + ": [" + event + "] " + player.getName() + " — «" + line + "».");
				deliver.accept(line);
			}
		}, () ->
		{
			// Мозги не ответили — молчит.
		}, VOICE_TIMEOUT);
	}

	/**
	 * Шёпотом — одному игроку (если ещё в игре).
	 */
	private static void whisper(Player me, Player to, String text)
	{
		if ((to != null) && to.isOnline())
		{
			to.sendPacket(new CreatureSay(me, ChatType.WHISPER, me.getName(), text));
		}
	}

	/**
	 * Вслух — в общий чат рядом ({@value #HEAR_RANGE}).
	 */
	private static void sayNear(Player me, String text)
	{
		if (!me.isSpawned())
		{
			return;
		}
		final CreatureSay say = new CreatureSay(me, ChatType.GENERAL, me.getName(), text);
		World.forEachVisibleObjectInRange(me, Player.class, HEAR_RANGE, player -> player.sendPacket(say));
	}

	/**
	 * @return рядом ({@value #HEAR_RANGE}) есть живой игрок — есть кому слышать
	 */
	private static boolean liveNear(Player me)
	{
		return World.getFirstVisibleObject(me, Player.class, other -> !PhantomManager.getInstance().isPhantom(other) && (me.calculateDistance2D(other) <= HEAR_RANGE)) != null;
	}

	private static Player acting(Creature creature)
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

	// ---------- Для окна ----------

	/**
	 * «Мой мир» (L2HubHunters.json, раздел 4.45): последняя строка списка охотников — только отметка, что это он, и уровень; ни места, ни занятия.
	 */
	void status(StringBuilder sb, boolean first)
	{
		final PhantomTemplate template = PhantomData.getInstance().getReaper();
		if (template != null)
		{
			sb.append(first ? "" : ",").append("{\"name\":\"").append(template.getName()).append("\",\"secret\":true,\"level\":").append(LEVEL).append('}');
		}
	}

	public static PhantomThanatos getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomThanatos INSTANCE = new PhantomThanatos();
	}
}
