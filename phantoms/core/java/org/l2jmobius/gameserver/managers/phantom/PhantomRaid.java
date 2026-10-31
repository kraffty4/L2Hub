package org.l2jmobius.gameserver.managers.phantom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.SuperChampion;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import org.l2jmobius.gameserver.util.StatSet;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: рейды охотников на РБ (docs/PHANTOMS.md, раздел 4.32; {@code HunterRaids} в PhantomHunters.ini).<br>
 * Живые РБ — {@link PhantomCompat#raidBosses} (у хроник разные менеджеры спавна). Раз в {@code HunterRaidInterval} минут менеджер ищет лидера — охотника на каче — и живого рейд-босса ниже его уровня ({@code HunterRaidBossLevel}, −8…−4: РБ рассчитаны
 * на полную пати живых, пятеро охотников своего уровня ложатся за минуту), но не ниже, чем на {@value #CURSE_GAP} (дальше — проклятие РБ), зовёт к нему в пати
 * охотников близкого уровня (всего {@code HunterRaidSize}, с хилером, если есть свободный), лидер кричит о сборе в торговый чат, живым игрокам — сообщение,
 * и все телепортируются к точке сбора в {@value #RALLY_DISTANCE} от босса. Через {@code HunterRaidGather} минут — бой: лидер бьёт РБ сам
 * (бой напарника — скиллы, маг отмахивается), остальные — «бей его цель» ({@link PhantomCompanionManager.Mode#ASSIST}), хилер лечит; удары охотников по боссу —
 * × {@code HunterRaidDamage} ({@link #onBossDamaged}): без этого пятеро охотников РБ не берут.
 * Живой игрок вступает в пати рейда и телепортируется к сбору командой {@code .raid} ({@link PhantomRaidCommand}).
 * Конец — босс убит (лут подбирается {@value #LOOT_TIME} мс), лидер лежит дольше {@value #LEADER_DEAD_GRACE} мс или легли все, бой дольше {@value #FIGHT_TIME} мс:
 * итог — в торговый чат, охотники — назад на кач.
 * Охотник-лидер на это время уходит из правил кача: его ведёт {@link #drive} из {@code PhantomHunterManager.think}.<br>
 * Эпики (раздел 4.43, {@code HunterEpicRaids}; ослабление в малом мире и измор — {@link PhantomEpics}):
 * <ul>
 * <li>только с живым игроком: рейд собирается, когда в мире есть живой игрок под эпика; лидер зовёт криком, живым — сообщение и {@code .raid},
 * ждут {@code HunterEpicWait} минут (раз в {@value #EPIC_CALL_GAP} мс зовут снова). Пришёл живой к сбору — бой через {@value #EPIC_READY} мс,
 * не пришёл никто — расходятся: без живого эпика не бьют;</li>
 * <li>без множителя урона (эпика ослабляет малый мир для всех), охотников бережёт только {@code HunterEpicArmor};</li>
 * <li>провал — только когда легли все: лёг лидер — ведёт следующий ({@link #handOver}); хилеры держат танка и лидера ({@link #keepAlive});</li>
 * <li>рецепты: кто из свиты бьёт рейд рядом с лидером — первым (Королева — гвардия, Ядро — рыцари и призраки зала, Орфен — Raikel Leos, Закен — нежить корабля),
 * лекарей свиты не гонять; Королева — один охотник бьёт личинку, и няньки лечат её, а не Королеву ({@link #companionTarget}); Орфен уходит в гнездо на половине HP,
 * Закен телепортируется по кораблю — рейд идёт следом ({@link #reRally}); раскиданных телепортом охотников лидер стягивает к себе;</li>
 * <li>провал — эпик измотан ({@code HunterEpicExhaust}, {@link PhantomEpics#exhaust}), следующий заход — через {@value #EPIC_AFTER_FAIL} мс.</li>
 * </ul>
 * Охота на Танатоса (раздел 4.45, {@link #hunt}): его видели — охотники с {@value #HUNT_LEVEL}-го собирают на него рейд той же машиной, сбор — {@value #HUNT_GATHER} мс,
 * рейд идёт за ним, пока он не ушёл дальше {@value #HUNT_RERALLIES} раз; живой вступает через {@code .raid}.<br>
 * Сбор, провал и убийство эпика — в «Летопись мира» ({@link PhantomNews}), убийство летопись пишет сама.
 */
public final class PhantomRaid
{
	private static final Logger LOGGER = Logger.getLogger(PhantomRaid.class.getName());

	/** Точка сбора — столько от босса, чтобы не агрить его. */
	private static final int RALLY_DISTANCE = 1300;
	/** Босс дальше этого от точки сбора (ушёл, телепортировался) — бой идёт к нему, но не дальше. */
	private static final int BOSS_LEASH = 6000;
	/** Лидер лежит дольше этого (не подняли) — провал; подняли раньше — бой идёт дальше. */
	private static final long LEADER_DEAD_GRACE = 60000;
	/** Участник — не дальше стольких уровней от босса и от лидера (у приёма в пати — 5). */
	private static final int MEMBER_LEVEL_GAP = 5;
	/** Живой игрок вступает с уровнем не ниже босса минус это. */
	private static final int PLAYER_LEVEL_GAP = 10;
	/** Участник — не ниже босса плюс столько (прогон 7: пати 20–24 на босса 20 легла, 25–29 на 24 — сбила половину HP). */
	private static final int MEMBER_OVER_BOSS = 2;
	/** Кто выше босса или его миньона больше чем на столько уровней, того проклятие РБ обращает в камень на 2 минуты: в рейд таких не берут ({@link #curseLevel}). */
	private static final int CURSE_GAP = 8;
	private static final long FIGHT_TIME = 900000;
	private static final long LOOT_TIME = 25000;
	/** Нет лидера и босса под него — следующая попытка через столько. */
	private static final long RETRY = 300000;
	/** Ход боя в лог — раз в столько. */
	private static final long FIGHT_REPORT = 30000;
	/** Не дальше этого от лидера живому игроку засчитано участие (как опыт пати). */
	private static final int PARTICIPATION_RANGE = 1500;

	private static final String[] GATHER_SHOUTS =
	{
		"собираем рейд на %s (%d ур), сбор у %s, через %d мин начинаем — кто с нами?",
		"го на РБ %s %d лвл! сбор у %s, стартуем через %d мин",
		"рейд на %s (%d), %s, через %d мин бьём, нужны люди",
		"кто на %s %d ур? сбор у %s, %d мин на подход"
	};
	/** Рейд по решению стратега охотника — не раньше чем через столько после конца прошлого рейда. */
	private static final long ORDER_GAP = 1200000;
	/** РБ на сборе бьёт кто-то другой дольше этого — не мешать, разойтись. */
	private static final long BUSY_GRACE = 60000;
	/** Вышел из рейда или караула: смерть в пределах стольких мс — ещё от них, сдвиг мобов ей не положен (раздел 4.56). */
	private static final long LEFT_GRACE = 30000;
	/** Очередь за РБ (раздел 4.65): второй рейд приходит не раньше стольких мс от начала сбора; отчаянные (%) при {@code HunterRaidTakeBusy} не ждут очереди; на сколько мс отодвигается бой, пока ждут. */
	private static final long RIVAL_DELAY = 60000;
	private static final int RIVAL_DESPERATE = 40;
	private static final long RIVAL_WAIT = 15000;
	/** Самопроверка очереди: сбор, предел круга, на сколько отложены обычные рейды. */
	private static final long TEST_GATHER = 30000;
	private static final long TEST_ROUND = 900000;
	private static final long TEST_POSTPONE = 7200000;
	private static final int TEST_RETRIES = 6;
	private static final long TEST_RETRY_DELAY = 20000;
	private static final String[] RIVAL_CLAIM_SHOUTS =
	{
		"на %s уже собираются? мы тоже идём, мы следующие",
		"вижу сбор на %s, встаём за вами",
		"%s один, нас тоже пати, кто первый того и рб"
	};
	private static final String[] FIRST_CLAIM_SHOUTS =
	{
		"%s наш, мы раньше собрались",
		"э, %s занят, стойте в очереди",
		"куда на %s? мы первые, ждите"
	};
	private static final String[] RIVAL_TURN_SHOUTS =
	{
		"слились? ну теперь наша очередь на %s",
		"%s свободен, заходим",
		"наш черёд, идём на %s"
	};
	private static final String[] RIVAL_DESPERATE_SHOUTS =
	{
		"не будем мы ждать, бьём %s",
		"%s общий, кто больше набьёт того и лут",
		"очередь отменяется, идём на %s"
	};
	private static final String[] RIVAL_LOST_SHOUTS =
	{
		"ну и забирайте %s, расходимся",
		"увели %s, ладно, следующий наш",
		"%s ушёл не к нам, расходимся"
	};

	/** План рейда: кто ведёт, с кем, на кого, где сбор. */
	private record Plan(Player leader, List<Player> team, Npc boss, Location rally)
	{
	}

	/** Суперчемпион (патч 0020): мини-РБ из обычного моба — свои крики сбора. */
	private static final String[] SUPER_GATHER_SHOUTS =
	{
		"легендарный чемпион %s (%d ур) у %s! собираемся, через %d мин бьём",
		"суперчемп %s %d лвл, сбор у %s, %d мин на подход — кто с нами?",
		"на %s (%d) идём пати, он у %s, через %d мин начинаем"
	};
	/** Суперчемпион: сбор короче, чем на РБ, — его может убить кто-то ещё, а после смерти на его месте встаёт обычный моб. */
	private static final long SUPER_GATHER = 120000;
	/** Суперчемпион: как часто искать (первый раз — через столько после старта: охотникам надо разойтись по спотам). */
	private static final long SUPER_RETRY = 120000;
	private static final long SUPER_FIRST = 300000;
	/** Миньоны эпика, которые лечат его и встают через секунды (няньки Королевы — 10 с, Риба Ирен Орфена): их не гонять, бить босса. */
	private static final Set<Integer> EPIC_HEALERS = Set.of(PhantomEpics.NURSE, 29018);
	/**
	 * Моб (свита эпика) ближе этого к лидеру и бьёт кого-то из рейда — бить его первым; не убит за {@value #EPIC_MINION_TIME} мс — бросить и бить босса
	 * (прогон H5 2026-10-22: лидер три минуты держал целью Raikel Leos, до которого не мог дойти, а Орфен рядом бил его — пати била миньона, Орфен остался на 100 %).
	 */
	private static final int EPIC_MINION_RANGE = 400;
	private static final long EPIC_MINION_TIME = 30000;
	/** Эпик: как часто искать (есть ли живой игрок под эпика); первый раз — через столько после старта. */
	private static final long EPIC_RETRY = 300000;
	private static final long EPIC_FIRST = 600000;
	/** Эпик: живой не пришёл — следующий сбор не раньше чем через столько; рейд не удался — через столько (эпик измотан, можно добить). */
	private static final long EPIC_NO_SHOW = 2700000;
	private static final long EPIC_AFTER_FAIL = 600000;
	/** Эпик: на сборе звать живых снова — раз в столько; живой у сбора — бой через столько. */
	private static final long EPIC_CALL_GAP = 180000;
	private static final long EPIC_READY = 60000;
	/** Эпик и Танатос: охотник рейда дальше этого от лидера (раскидал телепорт Закена, отстал) — телепорт к лидеру. */
	private static final int REGROUP_RANGE = 2500;
	/** Эпик ушёл от точки сбора (Орфен — в гнездо, Закен — по кораблю): рейд идёт следом не больше стольких раз. */
	private static final int EPIC_RERALLIES = 6;
	private static final String[] EPIC_GATHER_SHOUTS =
	{
		"собираем рейд на эпика %s (%d ур)! сбор у %s, ждём живых %d мин — без вас не пойдём",
		"го на %s %d лвл, эпик! сбор у %s, %d мин ждём — кто с нами? (.raid)",
		"эпик %s (%d) у %s, идём большой пати, %d мин на подход — нужен хоть один живой"
	};
	private static final String[] EPIC_CALL_SHOUTS =
	{
		"ещё ждём на %s у %s! кто с нами — .raid",
		"на %s пати собрана, у %s, ждём живого, .raid",
		"%s: стоим у %s, без живых не пойдём — .raid"
	};
	/** Охота на Танатоса: с этого уровня; сбор столько; рейд идёт за ним не больше стольких раз; пати — от стольких охотников. */
	static final int HUNT_LEVEL = 70;
	private static final long HUNT_GATHER = 60000;
	private static final int HUNT_RERALLIES = 3;
	private static final int HUNT_MIN = 3;
	private static final long HUNT_TIME = 600000;
	private static final String[] WIN_SHOUTS =
	{
		"%s лёг! спасибо %s, красавцы",
		"завалили %s, gg %s",
		"%s готов, всем спасибо: %s"
	};
	private static final String[] FAIL_SHOUTS =
	{
		"рейд на %s слился(( в другой раз",
		"%s нас разобрал, расходимся",
		"не вывезли %s, позже ещё попробуем"
	};

	/** Караул у респа (раздел 4.81): выходят не позже, чем за столько минут до респа; шанс (%) выйти на минутной проверке; как часто проверять; следующий караул не раньше; сколько ждать после срока; от появления босса до боя. */
	private static final int WATCH_LEAD_MIN = 3;
	private static final int WATCH_CHANCE = 30;
	private static final long WATCH_CHECK = 60000;
	private static final long WATCH_GAP = 1800000;
	private static final long WATCH_LATE = 180000;
	private static final long WATCH_READY = 30000;
	private static final String[] WATCH_SHOUTS =
	{
		"караулим %s (%d ур) у %s, скоро встанет, кто с нами?",
		"кто на %s %d лвл? мы уже стоим у %s, ждём реса",
		"%s (%d) вот-вот реснется, стоим у %s, подходите",
		"сторожим респ %s %d ур у %s, он наш"
	};
	private static final String[] WATCH_UP_SHOUTS =
	{
		"%s встал, бьём!",
		"вот он, %s! го",
		"дождались, %s реснулся, начинаем"
	};
	private static final String[] WATCH_GONE_SHOUTS =
	{
		"%s так и не встал, расходимся",
		"надоело ждать %s, пошли качаться",
		"с %s сегодня не судьба, разбегаемся"
	};

	/** Что делает лидер на этом тике ({@link #drive}). */
	public enum Drive
	{
		/** Не в рейде — правила кача. */
		NONE,
		/** Ведёт рейд — правила кача не трогать. */
		LEADS,
		/** Рейд кончился — назад на кач. */
		DONE
	}

	private enum Stage
	{
		GATHER,
		FIGHT,
		LOOT
	}

	private static final class Raid
	{
		/** Лидер: лёг на эпике — ведёт следующий ({@link #handOver}). */
		volatile int leaderId;
		final Creature boss;
		final String bossName;
		final int bossLevel;
		volatile Location rally;
		volatile String place;
		final long started;
		/** Цель — суперчемпион (патч 0020), а не РБ: без множителей урона и брони, конец — и когда он перестал быть суперчемпионом. */
		final boolean superChampion;
		/** Цель — эпик (раздел 4.43): только с живым игроком, своя броня, провал — когда легли все. */
		final boolean epic;
		/** Цель — Танатос (раздел 4.45): охота, рейд идёт за ним. */
		final boolean thanatos;
		/** Эпик: миньон, которого бьёт лидер, и с какого времени; брошенные (не убиты вовремя). */
		int minionId;
		long minionSince;
		final Set<Integer> skipMinions = new LinkedHashSet<>();
		/** Королева: охотник, который бьёт личинку ({@link #dutyOf}). */
		volatile int dutyId;
		/** Эпик и Танатос: сколько раз рейд шёл за ним на новое место. */
		int rerallies;
		/** Эпик: когда звали живых в последний раз; живой уже пришёл к сбору. */
		long lastCall;
		boolean playersArrived;
		final Set<Integer> members = new LinkedHashSet<>();
		/** Живые игроки, вступившие через .raid. */
		final Set<Integer> players = new LinkedHashSet<>();
		volatile Stage stage = Stage.GATHER;
		volatile long stageUntil;
		volatile String result;
		/** Лидер лежит с этого времени; 0 — жив. */
		volatile long leaderDeadSince;
		/** Босса на сборе бьёт кто-то другой с этого времени; 0 — никто. */
		long busySince;
		/** Очередь за РБ (раздел 4.65): это второй рейд; он не ждёт очереди; лидер уже крикнул, что идёт бить. */
		boolean rival;
		boolean desperate;
		boolean rivalSaid;
		/** Первому рейду второй уже подбирали. */
		boolean rivalTried;
		/** Сколько рейд нанёс боссу сам, без добавки {@code HunterRaidDamage}: кто больше, у того босс. */
		volatile double damageDone;
		long lastReport;
		/** На боссе на время боя: удары охотников рейда добираются до {@code HunterRaidDamage}. */
		ConsumerEventListener damage;
		/** На охотниках рейда на время боя: удар босса ослаблен в {@code HunterRaidArmor} раз. */
		final java.util.Map<Integer, ConsumerEventListener> armor = new java.util.concurrent.ConcurrentHashMap<>();

		Raid(Player leader, Creature boss, Location rally, String place, long now, boolean thanatos)
		{
			leaderId = leader.getObjectId();
			this.boss = boss;
			bossName = boss.getName();
			bossLevel = boss.getLevel();
			this.rally = rally;
			this.place = place;
			started = now;
			this.thanatos = thanatos;
			superChampion = !thanatos && SuperChampion.isSuper(boss);
			epic = boss instanceof GrandBoss;
			lastCall = now;
			stageUntil = now + (thanatos ? HUNT_GATHER : epic ? (PhantomHuntersConfig.HUNTER_EPIC_WAIT * 60000L) : superChampion ? SUPER_GATHER : (PhantomHuntersConfig.HUNTER_RAID_GATHER * 60000L));
		}
	}

	/** Когда встанет РБ. */
	private record Respawn(Spawn spawn, long at)
	{
	}

	/** Караул у респа (раздел 4.81): пати уже стоит у места, босса ещё нет. Встал — обычный рейд ({@link #fromWatch}). */
	private static final class Watch
	{
		final int leaderId;
		final int bossId;
		final String bossName;
		final int bossLevel;
		final Location rally;
		final String place;
		final long respawnAt;
		final Set<Integer> members = new LinkedHashSet<>();
		/** Караул кончился без боя: лидер кричит это и уводит пати ({@link #drive}). */
		volatile String endText;

		Watch(Player leader, Spawn spawn, Location rally, String place, long respawnAt)
		{
			leaderId = leader.getObjectId();
			bossId = spawn.getId();
			bossName = spawn.getTemplate().getName();
			bossLevel = spawn.getTemplate().getLevel();
			this.rally = rally;
			this.place = place;
			this.respawnAt = respawnAt;
		}
	}

	private volatile Watch _watch;
	private long _nextWatch;
	/** Время респов из базы на момент старта сервера ({@link #respawns}). */
	private Map<Integer, Long> _respawnsAtStart;
	private volatile Raid _raid;
	/** Очередь за РБ (раздел 4.65): второй рейд на того же босса; первый кончился — становится {@link #_raid}. */
	private volatile Raid _rival;
	private volatile int _testRivals;
	private volatile boolean[] _testRivalKeys;
	private volatile long _testRivalStarted;
	private volatile long _testRivalNext;
	private volatile int _testRivalRetries;
	private volatile long _nextRaid;
	private long _nextSuper;
	private long _nextEpic;
	/** Кого можно позвать — с последнего {@link #control} (для рейда по заказу стратега и охоты на Танатоса). */
	private volatile List<Player> _candidates = List.of();
	private volatile long _lastRaidEnd;
	/** Когда кто вышел из рейда или караула, см. {@link #justLeft}. */
	private final Map<Integer, Long> _leftAt = new ConcurrentHashMap<>();
	private boolean _commandRegistered;
	/** Самопроверка EPIC ({@code HuntersSelfTest}): ближайший рейд на эпика — без живого, его играет охотник рейда. */
	private volatile boolean _selfTestEpic;

	private PhantomRaid()
	{
	}

	// ---------- Сбор ----------

	/**
	 * Раз в 10 с из {@code PhantomHunterManager.control}: пора — собрать рейд из охотников на каче.
	 * @param hunting охотники, которых можно позвать: на каче (не в дороге), в городе без дел и в лавке; не в пати, живы, не в драке с игроком.
	 * Лидер — только с кача.
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!_commandRegistered)
		{
			_commandRegistered = true;
			VoicedCommandHandler.getInstance().registerHandler(new PhantomRaidCommand());
		}
		if (!PhantomHuntersConfig.HUNTER_RAIDS)
		{
			return;
		}
		_candidates = new ArrayList<>(hunting);
		if (_nextRaid == 0)
		{
			_nextRaid = now + (PhantomHuntersConfig.HUNTER_RAID_FIRST * 60000L);
			_nextSuper = now + SUPER_FIRST;
			_nextEpic = now + EPIC_FIRST;
			LOGGER.info(getClass().getSimpleName() + ": first raid in " + PhantomHuntersConfig.HUNTER_RAID_FIRST + " min.");
		}
		if (_raid != null)
		{
			rivals(hunting, now);
			return;
		}
		// Караул у респа (раздел 4.81): пока пати стоит у босса, которого ещё нет, новых рейдов не собирают.
		if (_watch != null)
		{
			return;
		}
		if (PhantomHuntersConfig.HUNTER_RAID_WATCH && (now >= _nextWatch))
		{
			_nextWatch = now + WATCH_CHECK;
			_watch = startWatch(hunting, now);
			if (_watch != null)
			{
				_nextWatch = now + WATCH_GAP;
				return;
			}
		}
		// Суперчемпион (патч 0020): жив и подходит под охотников — рейд на него сразу, без общего интервала рейдов.
		if (PhantomHuntersConfig.HUNTER_SUPER_CHAMPIONS && (now >= _nextSuper))
		{
			_nextSuper = now + SUPER_RETRY;
			final Raid raid = assemble(hunting, now, SuperChampion.alive());
			if (raid != null)
			{
				_raid = raid;
				return;
			}
		}
		// Эпик (раздел 4.43): жив, без входа через NPC, под охотников и под живого игрока в мире — рейд на него, без общего интервала рейдов.
		if (PhantomHuntersConfig.HUNTER_EPIC_RAIDS && (now >= _nextEpic))
		{
			_nextEpic = now + EPIC_RETRY;
			final Raid raid = assemble(hunting, now, epics());
			if (raid != null)
			{
				_raid = raid;
				return;
			}
		}
		if (now < _nextRaid)
		{
			return;
		}

		final Raid raid = assemble(hunting, now, PhantomCompat.raidBosses());
		if (raid == null)
		{
			_nextRaid = now + RETRY;
			return;
		}
		_raid = raid;
		_nextRaid = now + interval();
	}

	/**
	 * @return живые эпики ({@link PhantomEpics#EPICS}), под которых в мире есть живой игрок ({@link #livePlayerFor}): без живого эпика не бьют
	 */
	private static List<Npc> epics()
	{
		final List<Npc> epics = new ArrayList<>();
		for (Npc boss : PhantomEpics.alive())
		{
			if (getInstance()._selfTestEpic || livePlayerFor(boss.getLevel()))
			{
				epics.add(boss);
			}
		}
		return epics;
	}

	/**
	 * @return в мире есть живой игрок (с клиентом), которого возьмут в рейд на босса этого уровня ({@link #join})
	 */
	private static boolean livePlayerFor(int bossLevel)
	{
		for (Player player : World.getPlayers())
		{
			if (live(player) && (player.getLevel() >= (bossLevel - PLAYER_LEVEL_GAP)) && (player.getLevel() <= (bossLevel + CURSE_GAP)))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean live(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached();
	}

	private static long interval()
	{
		final int[] minutes = PhantomHuntersConfig.HUNTER_RAID_INTERVAL;
		final int lo = minutes.length > 0 ? minutes[0] : 60;
		final int hi = minutes.length > 1 ? Math.max(lo, minutes[1]) : lo;
		return Rnd.get(lo, hi) * 60000L;
	}

	private Raid assemble(List<Player> hunting, long now, Iterable<? extends Npc> candidates)
	{
		final Plan plan = plan(hunting, candidates, null);
		return plan == null ? null : start(plan.leader(), plan.team(), plan.boss(), plan.rally(), now, false);
	}

	/**
	 * Для стратега охотника: может ли он сейчас повести рейд на РБ — рейда нет, с прошлого прошло {@value #ORDER_GAP} мс, под него есть босс (никем не занятый) и команда с хилером.
	 * @return «босс (ур.) у места» — пометка для модели; {@code null} — нельзя
	 */
	String offer(Player leader, long now)
	{
		final Plan plan = orderPlan(leader, now);
		return plan == null ? null : plan.boss().getName() + " (" + plan.boss().getLevel() + " ур.) у " + place(plan.boss());
	}

	/**
	 * Стратег охотника решил собрать рейд (раздел 4.32): тот же сбор, что по расписанию, но лидер — он и не ждём общего интервала.
	 * @return рейд начался
	 */
	synchronized boolean request(Player leader, long now)
	{
		final Plan plan = orderPlan(leader, now);
		if (plan == null)
		{
			return false;
		}
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " decided to lead a raid (strategy).");
		_raid = start(plan.leader(), plan.team(), plan.boss(), plan.rally(), now, false);
		_nextRaid = Math.max(_nextRaid, now + interval());
		return true;
	}

	private Plan orderPlan(Player leader, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_RAIDS || (_raid != null) || (_watch != null) || (_nextRaid == 0) || ((now - _lastRaidEnd) < ORDER_GAP))
		{
			return null;
		}
		final List<Player> hunting = new ArrayList<>(_candidates);
		if (!hunting.contains(leader))
		{
			hunting.add(leader);
		}
		return plan(hunting, PhantomCompat.raidBosses(), leader);
	}

	/**
	 * Лидер — охотник, под которого есть живой босс и хватает соседей по уровню; из таких — наугад. Участники — ближе всех по уровню, хилер — первым.
	 * Босс, которого уже кто-то бьёт (в бою), — не цель: не мешать чужой пати и игроку.
	 * @param candidates боссы: РБ или суперчемпионы
	 * @param only лидер — только он ({@code null} — любой)
	 */
	private Plan plan(List<Player> hunting, Iterable<? extends Npc> candidates, Player only)
	{
		final List<Npc> bosses = new ArrayList<>();
		for (Npc boss : candidates)
		{
			if ((boss != null) && boss.isSpawned() && !boss.isAlikeDead() && (!boss.isInCombat() || (PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY && !(boss instanceof GrandBoss))) && (boss.getInstanceId() == 0) && !boss.isInsideZone(ZoneId.PEACE) && !boss.isInsideZone(ZoneId.SIEGE))
			{
				bosses.add(boss);
			}
		}
		if (bosses.isEmpty() || (hunting.size() < Math.min(PhantomHuntersConfig.HUNTER_RAID_MIN, PhantomHuntersConfig.HUNTER_EPIC_MIN)))
		{
			return null;
		}

		final List<Player> leaders = new ArrayList<>(hunting);
		Collections.shuffle(leaders);
		for (Player leader : leaders)
		{
			if ((only != null) && (leader != only))
			{
				continue;
			}
			// Лидер — с кача (не из города и не из лавки).
			if (!PhantomHunterManager.getInstance().isHunting(leader))
			{
				continue;
			}
			// Лидер держит агро босса — не хилер: хилер нужен живым, чтобы поднимать (прогон 6: лидером была хилер, легла — поднять некому).
			if (isHealer(leader))
			{
				continue;
			}
			final List<Npc> fitting = new ArrayList<>();
			for (Npc boss : bosses)
			{
				if ((boss.getLevel() >= (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MIN)) && (boss.getLevel() <= (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MAX)) && (leader.getLevel() <= curseLevel(boss)))
				{
					fitting.add(boss);
				}
			}
			if (fitting.isEmpty())
			{
				continue;
			}
			// Из подходящих — один из двух самых «тонких» по HP: у РБ одного уровня HP различается вдвое (прогон 7: Discarded Guardian 20 — 4% за три минуты).
			fitting.sort(Comparator.comparingDouble(Npc::getMaxHp));
			final Npc boss = fitting.get(Rnd.get(Math.min(2, fitting.size())));
			// Эпик — пати больше (раздел 4.43).
			final boolean epic = boss instanceof GrandBoss;
			final int minSize = epic ? PhantomHuntersConfig.HUNTER_EPIC_MIN : PhantomHuntersConfig.HUNTER_RAID_MIN;
			final int maxSize = epic ? PhantomHuntersConfig.HUNTER_EPIC_MAX : PhantomHuntersConfig.HUNTER_RAID_MAX;

			final List<Player> members = members(leader, hunting, boss.getLevel(), curseLevel(boss));
			// Без хилера босс кладёт пати — нет свободного хилера, нет и рейда.
			if (((members.size() + 1) < minSize) || ((_testRivals == 0) && members.stream().noneMatch(PhantomRaid::isHealer)))
			{
				continue;
			}
			final List<Player> team = members.subList(0, Math.min(members.size(), maxSize - 1));

			final Location rally = rally(boss);
			if (rally == null)
			{
				continue;
			}
			return new Plan(leader, new ArrayList<>(team), boss, rally);
		}
		return null;
	}

	/**
	 * @return кого лидер может взять на босса этого уровня: хилер — первым, дальше — соклановцы лидера (раздел 4.50), дальше — ближе по уровню к боссу
	 */
	private static List<Player> members(Player leader, List<Player> hunting, int bossLevel, int curse)
	{
		final List<Player> members = new ArrayList<>();
		for (Player other : hunting)
		{
			if ((other != leader) && (Math.abs(other.getLevel() - leader.getLevel()) <= MEMBER_LEVEL_GAP) && (other.getLevel() >= (bossLevel + MEMBER_OVER_BOSS)) && (other.getLevel() <= curse) && !PhantomRelations.getInstance().isEnemy(leader, other) && !PhantomRelations.getInstance().isEnemy(other, leader))
			{
				members.add(other);
			}
		}
		members.sort(Comparator.comparingInt((Player p) -> isHealer(p) ? 0 : 1).thenComparingInt(p -> PhantomHunterClans.clanmates(leader, p) ? 0 : 1).thenComparingInt(p -> Math.abs(p.getLevel() - bossLevel)));
		return members;
	}

	/**
	 * Миньон РБ для проклятия тоже рейд, а уровнем обычно ниже босса: Masha 29-го на боссе 21-го окаменела об охрану 20-го и простояла весь бой. Каст рядом с миньоном в бою проклинает так же, поэтому порог — от самого низкого.
	 * Эпиков не касается: их миньоны держат свои механики ({@link PhantomEpics}).
	 * @return выше этого уровня в рейд на этого босса не идут
	 */
	private static int curseLevel(Npc boss)
	{
		int lowest = boss.getLevel();
		if (!(boss instanceof GrandBoss) && (boss instanceof Monster) && ((Monster) boss).hasMinions())
		{
			for (Monster minion : ((Monster) boss).getMinionList().getSpawnedMinions())
			{
				if (minion != null)
				{
					lowest = Math.min(lowest, minion.getLevel());
				}
			}
		}
		return lowest + CURSE_GAP;
	}

	private static boolean isHealer(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && (template.getRole() == PhantomTemplate.Role.HEALER);
	}

	/**
	 * Точка сбора: в {@value #RALLY_DISTANCE} от босса, откуда к нему ведёт прямая по геодате; восемь сторон по очереди.
	 */
	private static Location rally(Creature boss)
	{
		return rally(boss.getX(), boss.getY(), boss.getZ());
	}

	private static Location rally(int bossX, int bossY, int bossZ)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		for (int i = 0; i < 8; i++)
		{
			final double angle = start + ((i * Math.PI) / 4);
			final int x = bossX + (int) (Math.cos(angle) * RALLY_DISTANCE);
			final int y = bossY + (int) (Math.sin(angle) * RALLY_DISTANCE);
			final int z = geo.getHeight(x, y, bossZ);
			if ((Math.abs(z - bossZ) < 400) && PhantomCompat.canMoveToTarget(geo, x, y, z, bossX, bossY, bossZ, 0))
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}

	/**
	 * Начать: участники — в пати лидера, «бей его цель»; бафы; лидер (участники — за ним, напарники догоняют сами) — к точке сбора; крик о сборе.
	 */
	private Raid start(Player leader, List<Player> team, Creature boss, Location rally, long now, boolean thanatos)
	{
		final Raid raid = new Raid(leader, boss, rally, place(boss), now, thanatos);
		final String what = raid.thanatos ? "" : raid.epic ? "эпика " : raid.superChampion ? "легендарного чемпиона " : "";
		formParty(leader, team, rally, raid.members);
		final List<String> names = names(raid);
		final long gather = (raid.stageUntil - now) / 60000;
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " (" + leader.getLevel() + ") leads a raid on " + (raid.thanatos ? "the hunt for " : raid.epic ? "epic " : raid.superChampion ? "super champion " : "") + raid.bossName + " (" + raid.bossLevel + ") at " + raid.place + ": " + String.join(", ", names) + "; fight in " + ((raid.stageUntil - now) / 1000) + " s.");

		if (raid.thanatos)
		{
			shout(leader, Rnd.nextBoolean() ? "собираем охоту на Танатоса! он у " + raid.place + ", все 70+ — ко мне, .raid" : "Танатос у " + raid.place + " — идём его валить, кто 70+ — .raid, через минуту выходим");
			PhantomNews.post("thanatos", "Охотники " + leader.getName() + " (" + (raid.members.size() + 1) + ") собирают охоту на Танатоса", leader.getName(), leader);
		}
		else
		{
			final String[] shouts = raid.epic ? EPIC_GATHER_SHOUTS : raid.superChampion ? SUPER_GATHER_SHOUTS : GATHER_SHOUTS;
			shout(leader, String.format(shouts[Rnd.get(shouts.length)], raid.bossName, raid.bossLevel, raid.place, gather));
		}
		// Самопроверка EPIC: живого играет охотник рейда (не хилер) — он и в пати, и в списке живых.
		if (raid.epic && _selfTestEpic)
		{
			_selfTestEpic = false;
			for (int id : raid.members)
			{
				final Player member = World.getPlayer(id);
				if ((member != null) && !isHealer(member))
				{
					raid.players.add(id);
					LOGGER.info(getClass().getSimpleName() + ": SelfTest: " + member.getName() + " plays the live player in the raid on " + raid.bossName + ".");
					break;
				}
			}
		}
		if (raid.epic)
		{
			PhantomNews.post("epic_raid", "Охотники " + leader.getName() + " (" + (raid.members.size() + 1) + ") собирают рейд на эпика " + raid.bossName + " (" + raid.bossLevel + " ур.) у " + raid.place + " и ждут живых", leader.getName(), leader);
		}
		callPlayers(raid, what);
		return raid;
	}

	/**
	 * Лидер — к точке сбора, участники — к нему в пати, бафы.
	 * @param into кто вступил
	 */
	private static void formParty(Player leader, List<Player> team, Location rally, Set<Integer> into)
	{
		PhantomHunterManager.getInstance().raidStarts(leader);
		PhantomHunterManager.payTeleportFee(leader);
		leader.teleToLocation(rally, true);
		for (Player member : team)
		{
			PhantomHunterManager.getInstance().prepareForRaid(member);
			PhantomCompanionManager.getInstance().onInvited(leader, member, PartyDistributionType.RANDOM);
			if (member.isInParty() && (member.getParty() == leader.getParty()))
			{
				into.add(member.getObjectId());
				PhantomHunterManager.raidBuff(member);
			}
		}
	}

	/**
	 * Живым игрокам, которых возьмут в рейд ({@link #join}), — сообщение и {@code .raid}.
	 */
	private static void callPlayers(Raid raid, String what)
	{
		for (Player player : World.getPlayers())
		{
			if (!PhantomManager.getInstance().isPhantom(player) && (player.getLevel() >= (raid.bossLevel - PLAYER_LEVEL_GAP)) && (raid.thanatos || (player.getLevel() <= (raid.bossLevel + CURSE_GAP))) && !raid.players.contains(player.getObjectId()))
			{
				player.sendMessage(raid.thanatos ? tr("Охотники собирают охоту на Танатоса — .raid: вступить и телепорт к сбору.") : tr("Охотники собирают рейд на ") + what + raid.bossName + " (" + raid.bossLevel + tr(" ур.) — .raid: вступить и телепорт к сбору.") + (raid.epic ? tr(" Без живого игрока эпика они не бьют.") : ""));
			}
		}
	}

	/**
	 * Где сбор, словами: ближайший спот охотников (у них в имени ориентир) или зона карты.
	 */
	private static String place(Creature boss)
	{
		return place(boss.getX(), boss.getY(), boss.getName());
	}

	private static String place(int bossX, int bossY, String bossName)
	{
		HuntSpot nearest = null;
		double best = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			final double distance = Math.hypot(spot.entry.getX() - bossX, spot.entry.getY() - bossY);
			if (distance < best)
			{
				best = distance;
				nearest = spot;
			}
		}
		return nearest != null ? nearest.name : bossName;
	}

	// ---------- Охота на Танатоса ----------

	/**
	 * Танатоса видели ({@link PhantomThanatos}): охотники с {@value #HUNT_LEVEL}-го (не меньше {@value #HUNT_MIN}, хилер — если есть свободный) собирают на него рейд.
	 * Лидер — самый сильный не хилер с кача; сбор — {@value #HUNT_GATHER} мс в {@value #RALLY_DISTANCE} от него.
	 * @return охота началась
	 */
	synchronized boolean hunt(Player thanatos, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_RAIDS || (_raid != null) || (_watch != null) || thanatos.isDead())
		{
			return false;
		}
		final List<Player> strong = new ArrayList<>();
		for (Player player : _candidates)
		{
			if ((player.getLevel() >= HUNT_LEVEL) && !player.isDead() && !player.isInParty())
			{
				strong.add(player);
			}
		}
		if (strong.size() < HUNT_MIN)
		{
			return false;
		}
		strong.sort(Comparator.comparingInt((Player p) -> -p.getLevel()));
		Player leader = null;
		for (Player player : strong)
		{
			if (!isHealer(player) && PhantomHunterManager.getInstance().isHunting(player))
			{
				leader = player;
				break;
			}
		}
		final Location rally = rally(thanatos);
		if ((leader == null) || (rally == null))
		{
			return false;
		}
		strong.remove(leader);
		strong.sort(Comparator.comparingInt((Player p) -> isHealer(p) ? 0 : 1).thenComparingInt(p -> -p.getLevel()));
		_raid = start(leader, new ArrayList<>(strong.subList(0, Math.min(strong.size(), 8))), thanatos, rally, now, true);
		return true;
	}

	/**
	 * Самопроверка EPIC ({@code HuntersSelfTest}, для разработки): ближайший рейд на эпика собирается без живого игрока — его играет охотник рейда; проверка — сразу.
	 */
	synchronized void selfTestEpic()
	{
		_selfTestEpic = true;
		_nextEpic = 0;
		if (_nextRaid == 0)
		{
			_nextRaid = System.currentTimeMillis() + (PhantomHuntersConfig.HUNTER_RAID_FIRST * 60000L);
			_nextSuper = System.currentTimeMillis() + SUPER_FIRST;
		}
		LOGGER.info(getClass().getSimpleName() + ": SelfTest EPIC: the next epic raid needs no live player.");
	}

	/**
	 * @return сколько в охоте на Танатоса (охотники и живые) или 0 — охоты нет
	 */
	int huntSize()
	{
		final Raid raid = _raid;
		return (raid != null) && raid.thanatos ? raid.members.size() + 1 + raid.players.size() : 0;
	}

	/**
	 * @return идёт охота на Танатоса, и он в ней — охотник или живой, вступивший через .raid
	 */
	boolean hunting(Player player)
	{
		final Raid raid = _raid;
		return (raid != null) && raid.thanatos && inRaid(raid, player.getObjectId());
	}

	// ---------- Бой ----------

	/**
	 * Тик лидера (из {@code PhantomHunterManager.think}, фаза HUNT): ведёт рейд — сбор, бой, лут; кончился — {@link Drive#DONE}.
	 * @param player охотник
	 * @param now текущее время
	 * @return что с ним делать на этом тике
	 */
	Drive drive(Player player, long now)
	{
		final Watch watch = _watch;
		if ((watch != null) && (watch.leaderId == player.getObjectId()))
		{
			synchronized (this)
			{
				if (_watch == watch)
				{
					if (watch.endText != null)
					{
						endWatch(watch, player);
						return Drive.DONE;
					}
					waitAtRally(player, watch.rally);
					return Drive.LEADS;
				}
			}
		}

		final Raid raid = ledBy(player);
		if (raid == null)
		{
			return Drive.NONE;
		}

		synchronized (this)
		{
			if (raid.result != null)
			{
				finish(raid, player);
				return Drive.DONE;
			}

			switch (raid.stage)
			{
				case GATHER:
				{
					if (raid.rival && rivalGather(raid, player, now))
					{
						return Drive.DONE;
					}
					// РБ бьёт кто-то другой (игрок, чужая пати) — не мешать: дольше BUSY_GRACE бой не стих — рейд распускается. Не касается очереди за первым рейдом и ключа HunterRaidTakeBusy.
					if (!(raid.rival && (_raid != raid)) && !(PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY && !raid.epic) && !raid.superChampion && !raid.thanatos && raid.boss.isInCombat() && !fightsRaid(raid))
					{
						if (raid.busySince == 0)
						{
							raid.busySince = now;
						}
						else if ((now - raid.busySince) > BUSY_GRACE)
						{
							fail(raid, player, "someone else is fighting the boss");
							finish(raid, player);
							return Drive.DONE;
						}
					}
					else
					{
						raid.busySince = 0;
					}
					// Суперчемпиона, пока собирались, убил кто-то ещё (охотники на его споте, игрок) — на респавне встал обычный моб; не победа, рейд не состоялся.
					if (raid.superChampion && (raid.boss.isAlikeDead() || !raid.boss.isSpawned() || !SuperChampion.isSuper(raid.boss)))
					{
						fail(raid, player, "the super champion was killed by someone else");
						finish(raid, player);
						return Drive.DONE;
					}
					// Танатоса, пока собирались, убили или он ушёл из мира.
					if (raid.thanatos && (raid.boss.isDead() || !raid.boss.isSpawned()))
					{
						dismiss(raid, player, "Thanatos is gone", "Танатос ушёл, расходимся");
						return Drive.DONE;
					}
					// Эпик: живой пришёл к сбору — бой через минуту; не зовут давно — позвать снова.
					if (raid.epic)
					{
						if (!raid.playersArrived && playersAtRally(raid))
						{
							raid.playersArrived = true;
							raid.stageUntil = Math.min(raid.stageUntil, now + EPIC_READY);
							PhantomCompanionManager.partySay(player, Rnd.nextBoolean() ? "о, живые подошли! минута — и начинаем" : "все в сборе, бафаемся, через минуту го");
						}
						else if (!raid.playersArrived && ((now - raid.lastCall) >= EPIC_CALL_GAP) && (now < raid.stageUntil))
						{
							raid.lastCall = now;
							shout(player, String.format(EPIC_CALL_SHOUTS[Rnd.get(EPIC_CALL_SHOUTS.length)], raid.bossName, raid.place));
							callPlayers(raid, tr("эпика "));
						}
					}
					if (now < raid.stageUntil)
					{
						waitAtRally(player, raid.rally);
						return Drive.LEADS;
					}
					// Эпик без живого игрока у сбора не бьют: охотники не забирают эпиков себе (решение автора 2026-10-23).
					if (raid.epic && !playersAtRally(raid))
					{
						dismiss(raid, player, "no live player came", Rnd.nextBoolean() ? "никто не пришёл на " + raid.bossName + ", расходимся" : "без живых " + raid.bossName + " не потянем, в другой раз");
						_nextEpic = Math.max(_nextEpic, now + EPIC_NO_SHOW);
						return Drive.DONE;
					}
					raid.stage = Stage.FIGHT;
					raid.stageUntil = now + (raid.thanatos ? HUNT_TIME : FIGHT_TIME);
					// Множители — для РБ (их HP и защита не по силам пати своего уровня); суперчемпион — обычный моб x20 HP, бьют как есть;
					// эпика ослабляет малый мир для всех (PhantomEpics), охотников бережёт только броня; Танатос — без множителей.
					if (!raid.superChampion && !raid.epic && !raid.thanatos && ((PhantomHuntersConfig.HUNTER_RAID_DAMAGE > 1) || PhantomHuntersConfig.HUNTER_RAID_RIVALS))
					{
						raid.damage = new ConsumerEventListener(raid.boss, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onBossDamaged(raid, event), this);
						raid.boss.addListener(raid.damage);
					}
					if (!raid.superChampion && !raid.thanatos && (armorOf(raid) > 1))
					{
						armor(raid, player);
						for (int id : raid.members)
						{
							final Player member = World.getPlayer(id);
							if (member != null)
							{
								armor(raid, member);
							}
						}
					}
					if (player.isSitting())
					{
						player.standUp();
					}
					PhantomCompanionManager.partySay(player, raid.thanatos ? (Rnd.nextBoolean() ? "за мной, валим жнеца!" : "все на Танатоса!") : Rnd.nextBoolean() ? "го, бьём " + raid.bossName + "!" : "погнали!");
					// Голос Танатоса (раздел 4.45): охота вступила в бой — раз на охоту.
					if (raid.thanatos)
					{
						PhantomThanatos.getInstance().onHunt(player, raid.members.size() + 1 + raid.players.size());
					}
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " starts the fight with " + raid.bossName + " (" + (raid.members.size() + 1) + " hunters, " + raid.players.size() + " players).");
					return Drive.LEADS;
				}
				case FIGHT:
				{
					// Суперчемпион мёртв и уже встал обычным мобом (респавн) — тоже конец.
					if (raid.boss.isAlikeDead() || !raid.boss.isSpawned() || (raid.superChampion && !SuperChampion.isSuper(raid.boss)))
					{
						if (raid.thanatos && !raid.boss.isAlikeDead())
						{
							dismiss(raid, player, "Thanatos left the world", "Танатос исчез... расходимся");
							return Drive.DONE;
						}
						if (lostTheRace(raid, player))
						{
							return Drive.DONE;
						}
						raid.stage = Stage.LOOT;
						raid.stageUntil = now + LOOT_TIME;
						LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + "'s raid killed " + raid.bossName + " in " + ((now - raid.started) / 1000) + " s; " + participants(raid) + ".");
						// Кланы охотников (раздел 4.41): босс убит, а не исчез — репутация клану живого игрока рейда и кланам охотников рейда.
						if (raid.boss.isDead() || (raid.superChampion && !SuperChampion.isSuper(raid.boss)))
						{
							final List<Integer> hunters = new ArrayList<>(raid.members);
							hunters.add(raid.leaderId);
							PhantomClans.getInstance().raidKilled(raid.boss, raid.bossName, raid.bossLevel, raid.epic, raid.superChampion, hunters, new ArrayList<>(raid.players));
							PhantomGestures.getInstance().onRaidKilled(hunters);
						}
						final List<String> names = names(raid);
						shout(player, String.format(WIN_SHOUTS[Rnd.get(WIN_SHOUTS.length)], raid.bossName, names.size() > 1 ? String.join(", ", names.subList(1, names.size())) : "всем"));
						return Drive.LEADS;
					}
					if (now >= raid.stageUntil)
					{
						fail(raid, player, "time");
						finish(raid, player);
						return Drive.DONE;
					}
					if ((now - raid.lastReport) >= FIGHT_REPORT)
					{
						raid.lastReport = now;
						final WorldObject target = player.getTarget();
						LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " fights " + raid.bossName + ": boss " + bossHp(raid) + "% HP, leader " + (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp())) + "% HP, alive " + alive(raid) + ", target " + (target == null ? "-" : target.getName() + " at " + (int) player.calculateDistance2D(target)) + ", boss at " + (int) player.calculateDistance2D(raid.boss) + (raid.epic ? ", minions " + minions(raid) : "") + (raid.dutyId != 0 ? ", larva duty " + nameOf(raid.dutyId) : "") + ", " + state(player) + ".");
					}
					if (player.isSitting())
					{
						player.standUp();
						return Drive.LEADS;
					}
					// Эпик ушёл (Орфен — в гнездо на половине HP, Закен — по кораблю), Танатос убежал — рейд идёт следом.
					final int leash = raid.thanatos ? REGROUP_RANGE * 2 : BOSS_LEASH;
					if (raid.boss.calculateDistance2D(raid.rally) > leash)
					{
						if ((raid.epic || raid.thanatos) && (raid.rerallies < (raid.thanatos ? HUNT_RERALLIES : EPIC_RERALLIES)) && reRally(raid, player))
						{
							return Drive.LEADS;
						}
						fail(raid, player, "the boss is gone");
						finish(raid, player);
						return Drive.DONE;
					}
					if (player.calculateDistance2D(raid.rally) > BOSS_LEASH)
					{
						fail(raid, player, "the leader is away");
						finish(raid, player);
						return Drive.DONE;
					}
					if (raid.epic || raid.thanatos)
					{
						regroup(raid, player);
					}
					if (raid.epic)
					{
						assignDuty(raid, player);
					}
					if (!player.isCastingNow())
					{
						PhantomCompanionManager.fight(player, raid.epic ? epicTarget(raid, player, now) : raid.boss);
					}
					return Drive.LEADS;
				}
				case LOOT:
				{
					if (now < raid.stageUntil)
					{
						PhantomCompanionManager.pickUpNear(player);
						return Drive.LEADS;
					}
					raid.result = "win";
					finish(raid, player);
					return Drive.DONE;
				}
			}
			return Drive.LEADS;
		}
	}

	/**
	 * @return что делает лидер, для отчёта боя
	 */
	private static String state(Player player)
	{
		return "cast " + player.isCastingNow() + ", attack " + player.isAttackingNow() + ", move " + player.isMoving() + ", blocked " + player.isMovementDisabled() + ", intent " + player.getAI().getIntention();
	}

	/**
	 * @return кто-то из живых игроков рейда жив и у точки сбора (или у лидера)
	 */
	private static boolean playersAtRally(Raid raid)
	{
		final Player leader = World.getPlayer(raid.leaderId);
		for (int id : raid.players)
		{
			final Player player = World.getPlayer(id);
			if ((player != null) && !player.isDead() && ((player.calculateDistance2D(raid.rally) <= PARTICIPATION_RANGE) || ((leader != null) && (player.calculateDistance2D(leader) <= PARTICIPATION_RANGE))))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Эпик ушёл от точки сбора (Орфен — в гнездо, Закен телепортировался), Танатос убежал: новая точка у него; охотники рейда — туда телепортом,
	 * живым — сообщение ({@code .raid go} — к новому месту).
	 * @return пошли следом
	 */
	private boolean reRally(Raid raid, Player leader)
	{
		final Location rally = rally(raid.boss);
		if (rally == null)
		{
			return false;
		}
		raid.rerallies++;
		raid.rally = rally;
		raid.place = place(raid.boss);
		raid.minionId = 0;
		LOGGER.info(getClass().getSimpleName() + ": " + raid.bossName + " moved; " + leader.getName() + "'s raid follows to " + raid.place + " (" + raid.rerallies + ").");
		leader.teleToLocation(rally, true);
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if ((member != null) && !member.isDead())
			{
				member.teleToLocation(near(rally), true);
			}
		}
		for (int id : raid.players)
		{
			final Player player = World.getPlayer(id);
			if (player != null)
			{
				player.sendMessage(raid.bossName + tr(" ушёл к ") + PhantomText.spotName(raid.place) + tr(" — рейд идёт следом: .raid go."));
			}
		}
		PhantomCompanionManager.partySay(leader, raid.thanatos ? "он сбежал, за ним!" : raid.bossName + " ушёл, за ним!");
		return true;
	}

	/**
	 * Охотник рейда дальше {@value #REGROUP_RANGE} от лидера (Закен телепортом раскидал по кораблю, Орфен утащил к себе, отстал) — телепорт к лидеру.
	 * Кто держит личинку Королевы, тот стоит у неё — его не трогать.
	 */
	private static void regroup(Raid raid, Player leader)
	{
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if ((member != null) && (id != raid.dutyId) && !member.isDead() && !member.isTeleporting() && (member.calculateDistance2D(leader) > REGROUP_RANGE))
			{
				member.teleToLocation(near(leader.getLocation()), true);
			}
		}
	}

	private static Location near(Location at)
	{
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		return new Location(at.getX() + (int) (Math.cos(angle) * 120), at.getY() + (int) (Math.sin(angle) * 120), at.getZ());
	}

	/**
	 * Рецепт Королевы: один охотник рейда (не лидер, не хилер; стрелок или маг — первыми) бьёт личинку у входа в гнездо — няньки лечат её, а не Королеву.
	 */
	private static void assignDuty(Raid raid, Player leader)
	{
		if (raid.boss.getId() != PhantomEpics.QUEEN_ANT)
		{
			return;
		}
		final Player current = raid.dutyId == 0 ? null : World.getPlayer(raid.dutyId);
		if ((current != null) && !current.isDead())
		{
			return;
		}
		Player best = null;
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if ((member == null) || member.isDead() || isHealer(member))
			{
				continue;
			}
			if ((best == null) || (PhantomHunterOutfit.isCaster(member) && !PhantomHunterOutfit.isCaster(best)))
			{
				best = member;
			}
		}
		if ((best != null) && (best.getObjectId() != raid.dutyId))
		{
			raid.dutyId = best.getObjectId();
			PhantomCompanionManager.partySay(best, "держу личинку, чтобы няньки королеву не лечили");
			LOGGER.info(PhantomRaid.class.getSimpleName() + ": " + best.getName() + " keeps the Queen Ant larva busy.");
		}
	}

	/**
	 * Для {@link PhantomCompanionManager}: у охотника рейда своя цель в бою — не цель лидера, которую он берёт только среди мобов.
	 * @return личинка Королевы для того, кто её держит; Танатос на охоте (он игрок); иначе {@code null}
	 */
	Creature companionTarget(Player member)
	{
		final Raid raid = memberOf(member);
		if ((raid == null) || (raid.stage != Stage.FIGHT) || !raid.members.contains(member.getObjectId()))
		{
			return null;
		}
		if (raid.dutyId == member.getObjectId())
		{
			return PhantomEpics.larva(raid.boss, member);
		}
		return raid.thanatos && !raid.boss.isDead() && (member.calculateDistance2D(raid.boss) <= REGROUP_RANGE) ? raid.boss : null;
	}

	/**
	 * @return где это, словами — ближайший спот охотников
	 */
	static String placeOf(Creature creature)
	{
		return place(creature);
	}

	/**
	 * Для хилера ({@link PhantomSupport}): кого держать первым — в бою с эпиком и с Танатосом это танк (кого бьёт босс, если он из рейда) и лидер.
	 * @return пусто — не в таком рейде
	 */
	List<Player> keepAlive(Player healer)
	{
		final Raid raid = memberOf(healer);
		if ((raid == null) || (raid.stage != Stage.FIGHT) || !(raid.epic || raid.thanatos) || !raid.members.contains(healer.getObjectId()))
		{
			return List.of();
		}
		final List<Player> vips = new ArrayList<>(2);
		final Creature tank = PhantomCompat.attackTarget(raid.boss);
		if ((tank instanceof Player) && ((tank.getObjectId() == raid.leaderId) || raid.members.contains(tank.getObjectId()) || raid.players.contains(tank.getObjectId())))
		{
			vips.add((Player) tank);
		}
		final Player leader = World.getPlayer(raid.leaderId);
		if ((leader != null) && !vips.contains(leader))
		{
			vips.add(leader);
		}
		return vips;
	}

	/**
	 * Цель лидера в бою с эпиком: ближайший моб рядом (свита, зал Ядра, нежить корабля), который бьёт кого-то из рейда, — первым (не дольше {@value #EPIC_MINION_TIME} мс на одного),
	 * иначе сам эпик; лекарей свиты ({@link #EPIC_HEALERS}) и личинку не гонять.
	 */
	private static Creature epicTarget(Raid raid, Player leader, long now)
	{
		Monster best = null;
		double bestDistance = EPIC_MINION_RANGE;
		for (Monster mob : World.getVisibleObjectsInRange(leader, Monster.class, EPIC_MINION_RANGE))
		{
			if ((mob == raid.boss) || mob.isAlikeDead() || EPIC_HEALERS.contains(mob.getId()) || (mob.getId() == PhantomEpics.LARVA) || raid.skipMinions.contains(mob.getObjectId()))
			{
				continue;
			}
			final WorldObject victim = PhantomHunterOutfit.victimOf(mob);
			if ((victim == null) || !inRaid(raid, victim.getObjectId()))
			{
				continue;
			}
			final double distance = leader.calculateDistance2D(mob);
			if (distance < bestDistance)
			{
				best = mob;
				bestDistance = distance;
			}
		}
		if (best == null)
		{
			raid.minionId = 0;
			return raid.boss;
		}
		if (best.getObjectId() != raid.minionId)
		{
			raid.minionId = best.getObjectId();
			raid.minionSince = now;
		}
		else if ((now - raid.minionSince) > EPIC_MINION_TIME)
		{
			raid.skipMinions.add(best.getObjectId());
			raid.minionId = 0;
			return raid.boss;
		}
		return best;
	}

	private static boolean inRaid(Raid raid, int objectId)
	{
		return (objectId == raid.leaderId) || raid.members.contains(objectId) || raid.players.contains(objectId);
	}

	/**
	 * @return босс (эпик) бьёт кого-то из рейда — не «чужой бой»: Ядро на сборе сцепилось с охотниками у точки сбора (прогон H5 2026-10-22)
	 */
	private static boolean fightsRaid(Raid raid)
	{
		if (!raid.epic)
		{
			return false;
		}
		final Creature target = PhantomCompat.attackTarget(raid.boss);
		return (target != null) && inRaid(raid, target.getObjectId());
	}

	/**
	 * @return живых миньонов эпика — для отчёта боя
	 */
	private static int minions(Raid raid)
	{
		if (!(raid.boss instanceof Monster) || !((Monster) raid.boss).hasMinions())
		{
			return 0;
		}
		int alive = 0;
		for (Monster minion : ((Monster) raid.boss).getMinionList().getSpawnedMinions())
		{
			if ((minion != null) && !minion.isAlikeDead())
			{
				alive++;
			}
		}
		return alive;
	}

	/**
	 * @return моб, который бьёт лидера (не босс), или {@code null}
	 */
	private static Monster attacker(Player player)
	{
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, 900))
		{
			if (!monster.isAlikeDead() && !monster.isRaid() && (PhantomHunterOutfit.victimOf(monster) == player))
			{
				return monster;
			}
		}
		return null;
	}

	/**
	 * Раз в 10 с: лидер погиб или пропал. РБ — ждать реса {@value #LEADER_DEAD_GRACE} мс, потом провал; эпик и Танатос — ведёт следующий живой охотник
	 * ({@link #handOver}), провал — когда легли все (охотники и живые игроки рейда).
	 */
	synchronized void check(long now)
	{
		check(_raid, now);
		check(_rival, now);
		checkWatch(now);
	}

	/**
	 * Лидер ждёт у точки сбора: бьют — отбиться, отошёл — вернуться, стоит — сесть отдохнуть.
	 */
	private static void waitAtRally(Player player, Location rally)
	{
		final Monster attacker = attacker(player);
		if (attacker != null)
		{
			PhantomCompanionManager.fight(player, attacker);
		}
		else if (player.calculateDistance2D(rally) > 300)
		{
			if (!player.isMoving())
			{
				player.setRunning();
				player.getAI().setIntentionMoveTo(rally);
			}
		}
		else if (!player.isSitting() && !player.isMoving() && !player.isInCombat())
		{
			player.sitDown();
		}
	}

	private void check(Raid raid, long now)
	{
		if (raid == null)
		{
			return;
		}
		final Player leader = World.getPlayer(raid.leaderId);
		if ((leader == null) || !leader.isOnline())
		{
			LOGGER.info(getClass().getSimpleName() + ": raid on " + raid.bossName + " is over: the leader left the world.");
			release(raid, null);
			forget(raid);
			return;
		}
		if (raid.result != null)
		{
			return;
		}
		if (!leader.isDead())
		{
			raid.leaderDeadSince = 0;
			return;
		}
		if (raid.leaderDeadSince == 0)
		{
			raid.leaderDeadSince = now;
		}
		final Player next = nextLeader(raid, leader);
		if ((raid.epic || raid.thanatos) && (raid.stage == Stage.FIGHT))
		{
			if (next != null)
			{
				handOver(raid, leader, next);
				return;
			}
			// Охотников на ногах нет: живой игрок ещё бьётся — ждём, пока поднимут; легли все или подождали — провал.
			if (!livePlayersFighting(raid) || ((now - raid.leaderDeadSince) > LEADER_DEAD_GRACE))
			{
				fail(raid, leader, livePlayersFighting(raid) ? "all hunters died" : "everyone died");
				finish(raid, leader);
			}
			return;
		}
		// РБ: лидер лёг — ждём, что поднимут (хилер в пати ресает); не подняли за минуту или легли все — провал.
		if ((next == null) || ((now - raid.leaderDeadSince) > LEADER_DEAD_GRACE))
		{
			fail(raid, leader, next != null ? "the leader died" : "everyone died");
			finish(raid, leader);
		}
	}

	/**
	 * @return живой охотник рейда в пати лидера, который поведёт вместо него: не хилер (хилер нужен на ресах), старше уровнем; нет таких — хилер; нет никого — {@code null}
	 */
	private static Player nextLeader(Raid raid, Player leader)
	{
		Player best = null;
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if ((member == null) || member.isDead() || !member.isInParty() || (member.getParty() != leader.getParty()))
			{
				continue;
			}
			if ((best == null) || (isHealer(best) && !isHealer(member)) || ((isHealer(best) == isHealer(member)) && (member.getLevel() > best.getLevel())))
			{
				best = member;
			}
		}
		return best;
	}

	/**
	 * @return живой игрок рейда жив и у боя
	 */
	private static boolean livePlayersFighting(Raid raid)
	{
		for (int id : raid.players)
		{
			final Player player = World.getPlayer(id);
			if ((player != null) && !player.isDead() && (player.calculateDistance2D(raid.boss) <= (PARTICIPATION_RANGE * 2)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Эпик, Танатос (раздел 4.43): лидер лёг — ведёт следующий. Новый лидер выходит из напарников и ведёт рейд сам ({@link #drive}), его напарники — теперь
	 * напарники нового лидера, сам старый — тоже (лежит, ждёт реса, как участник), пати — новому лидеру.
	 */
	private void handOver(Raid raid, Player oldLeader, Player next)
	{
		raid.members.remove(next.getObjectId());
		raid.members.add(oldLeader.getObjectId());
		raid.leaderId = next.getObjectId();
		raid.leaderDeadSince = 0;
		raid.minionId = 0;
		if (raid.dutyId == next.getObjectId())
		{
			raid.dutyId = 0;
		}
		PhantomCompanionManager.getInstance().handOver(oldLeader, next);
		PhantomHunterManager.getInstance().takeRaidLead(next);
		if (next.isInParty() && (next.getParty().getLeader() != next))
		{
			next.getParty().changePartyLeader(next.getName());
		}
		PhantomCompanionManager.partySay(next, Rnd.nextBoolean() ? oldLeader.getName() + " лёг, веду я! не расходимся" : "лидер упал — слушаем меня, бьём дальше");
		LOGGER.info(getClass().getSimpleName() + ": " + oldLeader.getName() + " is down, " + next.getName() + " leads the raid on " + raid.bossName + " (boss " + bossHp(raid) + "% HP, alive " + alive(raid) + ").");
	}

	private void fail(Raid raid, Player leader, String why)
	{
		raid.result = "fail";
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + "'s raid on " + raid.bossName + " failed (" + why + ") after " + ((System.currentTimeMillis() - raid.started) / 1000) + " s, the boss has " + bossHp(raid) + "% HP; " + participants(raid) + ".");
		shout(leader, String.format(FAIL_SHOUTS[Rnd.get(FAIL_SHOUTS.length)], raid.bossName));
		if (raid.epic)
		{
			// Измор: не регенит HUNTER_EPIC_EXHAUST минут — следующий заход скоро и с того же HP.
			final boolean fought = raid.stage != Stage.GATHER;
			if (fought)
			{
				PhantomEpics.getInstance().exhaust(raid.boss, PhantomHuntersConfig.HUNTER_EPIC_EXHAUST);
				_nextEpic = System.currentTimeMillis() + EPIC_AFTER_FAIL;
			}
			PhantomNews.post("epic_raid", "Рейд охотников " + leader.getName() + " на эпика " + raid.bossName + " не удался: у босса " + bossHp(raid) + "% HP" + (fought && (PhantomHuntersConfig.HUNTER_EPIC_EXHAUST > 0) ? ", он измотан и " + PhantomHuntersConfig.HUNTER_EPIC_EXHAUST + " мин не восстановится" : ""), leader.getName(), leader);
		}
		else if (raid.thanatos)
		{
			PhantomNews.post("thanatos", "Охота на Танатоса не удалась" + (raid.boss.isDead() ? "" : ": он ушёл живым"), leader.getName(), leader);
		}
	}

	/**
	 * Рейд не начался (живой не пришёл, цель ушла): расходятся без провала — крик, без летописи и измора.
	 */
	private void dismiss(Raid raid, Player leader, String why, String text)
	{
		raid.result = "dismissed";
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + "'s raid on " + raid.bossName + " is dismissed (" + why + ").");
		shout(leader, text);
		finish(raid, leader);
	}

	/**
	 * @return во сколько раз удар босса по охотнику рейда слабее: эпик — {@code HunterEpicArmor}, РБ — {@code HunterRaidArmor}
	 */
	private static float armorOf(Raid raid)
	{
		return raid.epic ? PhantomHuntersConfig.HUNTER_EPIC_ARMOR : PhantomHuntersConfig.HUNTER_RAID_ARMOR;
	}

	/**
	 * Рейд кончен: охотники — из пати назад на кач (их ведёт менеджер охотников), живые игроки остаются сами по себе.
	 */
	private void finish(Raid raid, Player leader)
	{
		if ((_raid != raid) && (_rival != raid))
		{
			return;
		}
		release(raid, leader);
		forget(raid);
		_lastRaidEnd = System.currentTimeMillis();
		LOGGER.info(getClass().getSimpleName() + ": raid on " + raid.bossName + " is over (" + raid.result + ").");
	}

	/**
	 * Удар охотника из рейда по боссу — ещё урона до {@code HunterRaidDamage} от нанесённого (РБ рассчитаны на полную пати живых:
	 * пятеро охотников его почти не царапают, а регенерация съедает и это). Удары живых игроков — как есть.
	 */
	private static void onBossDamaged(Raid raid, OnCreatureDamageReceived event)
	{
		final Creature attacker = event.getAttacker();
		if ((attacker == null) || !attacker.isPlayer() || event.isDamageOverTime() || (event.getDamage() <= 0) || raid.boss.isAlikeDead())
		{
			return;
		}
		final int id = attacker.getObjectId();
		if ((id != raid.leaderId) && !raid.members.contains(id))
		{
			return;
		}
		raid.damageDone += event.getDamage();
		if (PhantomHuntersConfig.HUNTER_RAID_DAMAGE > 1)
		{
			raid.boss.reduceCurrentHp(event.getDamage() * (PhantomHuntersConfig.HUNTER_RAID_DAMAGE - 1), attacker, null);
		}
	}

	/**
	 * Охотнику рейда — слушатель: удар босса (и его миньонов, у эпика — любого моба у его логова) возвращается до 1/{@code HunterRaidArmor} — босс положил бы пятерых за минуту.
	 */
	private void armor(Raid raid, Player hunter)
	{
		final ConsumerEventListener listener = new ConsumerEventListener(hunter, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onHunterDamaged(raid, hunter, event), this);
		hunter.addListener(listener);
		raid.armor.put(hunter.getObjectId(), listener);
	}

	private static void onHunterDamaged(Raid raid, Player hunter, OnCreatureDamageReceived event)
	{
		final Creature attacker = event.getAttacker();
		if ((attacker == null) || hunter.isDead() || (event.getDamage() <= 0))
		{
			return;
		}
		final boolean bossSide = (attacker == raid.boss) || (attacker.isMonster() && ((attacker.asMonster().getLeader() == raid.boss) || (raid.epic && (attacker.calculateDistance2D(raid.boss) < 3000))));
		if (!bossSide)
		{
			return;
		}
		final double restore = event.getDamage() * (1 - (1 / armorOf(raid)));
		hunter.setCurrentHp(Math.min(hunter.getMaxHp(), hunter.getCurrentHp() + restore));
	}

	private static void release(Raid raid, Player leader)
	{
		if (raid.damage != null)
		{
			raid.boss.removeListener(raid.damage);
			raid.damage = null;
		}
		for (java.util.Map.Entry<Integer, ConsumerEventListener> entry : raid.armor.entrySet())
		{
			final Player hunter = World.getPlayer(entry.getKey());
			if (hunter != null)
			{
				hunter.removeListener(entry.getValue());
			}
		}
		raid.armor.clear();
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			final PhantomTemplate template = member == null ? null : PhantomData.getInstance().getTemplate(member.getName());
			if (template != null)
			{
				PhantomCompanionManager.getInstance().release(template, true);
			}
		}
		if ((leader != null) && leader.isInParty())
		{
			leader.leaveParty();
		}
	}

	/**
	 * @return сколько охотников рейда живы и в пати лидера, «живых/всего»
	 */
	private static String alive(Raid raid)
	{
		final Player leader = World.getPlayer(raid.leaderId);
		int alive = (leader != null) && !leader.isDead() ? 1 : 0;
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			alive += (member != null) && !member.isDead() && (leader != null) && member.isInParty() && (member.getParty() == leader.getParty()) ? 1 : 0;
		}
		return alive + "/" + (raid.members.size() + 1);
	}

	private static int bossHp(Raid raid)
	{
		return raid.boss.isAlikeDead() ? 0 : (int) ((raid.boss.getCurrentHp() * 100) / Math.max(1, raid.boss.getMaxHp()));
	}

	private static String nameOf(int objectId)
	{
		final Player player = World.getPlayer(objectId);
		return player != null ? player.getName() : "?";
	}

	private static void shout(Player leader, String text)
	{
		PhantomChat.getInstance().hunterShout(leader, text);
	}

	// ---------- Живой игрок ----------

	/**
	 * Вступить в пати рейда ({@code .raid join}): пока идёт сбор или бой, с уровнем не ниже босса − {@value #PLAYER_LEVEL_GAP}, не в чужой пати.
	 * @return ошибка или {@code null}
	 */
	synchronized String join(Player player)
	{
		final Raid raid = _raid;
		final Player leader = raid == null ? null : World.getPlayer(raid.leaderId);
		if ((raid == null) || (leader == null) || (raid.result != null) || (raid.stage == Stage.LOOT))
		{
			return tr("Рейда сейчас нет.");
		}
		if (player.getLevel() < (raid.bossLevel - PLAYER_LEVEL_GAP))
		{
			return tr("Рано: на ") + raid.bossName + tr(" берут с ") + (raid.bossLevel - PLAYER_LEVEL_GAP) + tr("-го уровня.");
		}
		if (!raid.thanatos && (player.getLevel() > (raid.bossLevel + CURSE_GAP)))
		{
			return tr("Ты выше ") + raid.bossName + tr(" больше чем на ") + CURSE_GAP + tr(" уровней — РБ положит на тебя проклятие. Рейд — до ") + (raid.bossLevel + CURSE_GAP) + tr("-го уровня.");
		}
		if (player.isInParty())
		{
			return player.getParty() == leader.getParty() ? tr("Ты уже в пати рейда.") : tr("Сначала выйди из своей пати.");
		}
		if (player.isInOlympiadMode())
		{
			return tr("Не с олимпиады.");
		}
		if (!leader.isInParty())
		{
			PhantomCompat.newParty(leader, PartyDistributionType.RANDOM);
		}
		if (leader.getParty().getMemberCount() >= 9)
		{
			return tr("Пати рейда полная.");
		}
		player.joinParty(leader.getParty());
		raid.players.add(player.getObjectId());
		PhantomCompanionManager.partySay(leader, Rnd.nextBoolean() ? player.getName() + ", велкам, держись за хилом" : "о, " + player.getName() + " с нами, норм");
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") joins the raid on " + raid.bossName + ".");
		return null;
	}

	/**
	 * Телепорт к точке сбора ({@code .raid go}): выше {@code MaxFreeTeleportLevel} — за плату, как у охотников.
	 * @return ошибка или {@code null}
	 */
	String teleport(Player player)
	{
		final Raid raid = _raid;
		if ((raid == null) || (raid.result != null))
		{
			return tr("Рейда сейчас нет.");
		}
		final int fee = player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0;
		if ((fee > 0) && !PhantomCompat.reduceAdena(player, ItemProcessType.FEE, fee, player, true))
		{
			return tr("Не хватает адены на телепорт: ") + fee + ".";
		}
		player.teleToLocation(near(raid.rally), true);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " teleports to the raid on " + raid.bossName + ".");
		return null;
	}

	/**
	 * Окно {@code .raid}.
	 */
	void show(Player player)
	{
		player.sendPacket(new NpcHtmlMessage(html(player)));
	}

	/**
	 * @param player кто смотрит
	 * @return окно рейда целиком
	 */
	String html(Player player)
	{
		final Raid raid = _raid;
		final StringBuilder sb = new StringBuilder(tr("<html><title>Рейд охотников</title><body><center><font color=\"LEVEL\">Рейд охотников</font></center><br>"));
		final Player leader = raid == null ? null : World.getPlayer(raid.leaderId);
		if ((raid == null) || (leader == null) || (raid.result != null))
		{
			final long next = Math.max(0, (_nextRaid - System.currentTimeMillis()) / 60000);
			sb.append(tr("Сейчас рейда нет. Охотники собираются на РБ своего уровня примерно раз в ")).append(intervalText()).append(PhantomHuntersConfig.HUNTER_RAIDS ? (_nextRaid > 0 ? tr(" — следующий не раньше чем через ") + next + tr(" мин.") : ".") : tr(" (рейды выключены: HunterRaids)."));
			sb.append(tr("<br><br><font color=\"808080\">Когда соберутся — в торговом чате будет крик лидера, а тебе — сообщение. На эпиков охотники без живого игрока не ходят: зовут и ждут.</font></body></html>"));
			return sb.toString();
		}

		final long left = Math.max(0, (raid.stageUntil - System.currentTimeMillis()) / 1000);
		sb.append("<font color=\"LEVEL\">").append(raid.thanatos ? tr("Охота на Танатоса") : raid.bossName).append("</font> (").append(raid.bossLevel).append(tr(" ур.)<br1>"));
		sb.append(tr("Сбор: ")).append(PhantomText.spotName(raid.place)).append("<br1>");
		sb.append(raid.stage == Stage.GATHER ? tr("Бой — через ") + (left / 60) + tr(" мин ") + (left % 60) + tr(" с.") + (raid.epic && !raid.playersArrived ? tr(" Без живого игрока у сбора эпика не бьют.") : "") : raid.stage == Stage.FIGHT ? tr("Идёт бой.") : tr("Босс убит, собирают лут.")).append("<br><br>");
		sb.append(tr("<font color=\"B09878\">Пати:</font><br1>"));
		final Player leaderPlayer = leader;
		sb.append(leaderPlayer.getName()).append(" (").append(leaderPlayer.getLevel()).append(tr(", лидер")).append(leaderPlayer.isDead() ? tr(", лежит") : "").append(")<br1>");
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				sb.append(member.getName()).append(" (").append(member.getLevel()).append(isHealer(member) ? tr(", хилер") : "").append(member.isDead() ? tr(", лежит") : "").append(")<br1>");
			}
		}
		for (int id : raid.players)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				sb.append("<font color=\"LEVEL\">").append(member.getName()).append("</font> (").append(member.getLevel()).append(")<br1>");
			}
		}
		sb.append("<br><center>");
		if (raid.stage != Stage.LOOT)
		{
			sb.append(tr("<button value=\"Вступить\" action=\"bypass voice .raid join\" width=120 height=21 back=\"")).append(PhantomCompat.BUTTON_BACK).append("\" fore=\"").append(PhantomCompat.BUTTON_FORE).append("\"><br>");
			final int fee = player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0;
			sb.append(tr("<button value=\"К сбору")).append(fee > 0 ? " (" + fee + ")" : "").append("\" action=\"bypass voice .raid go\" width=120 height=21 back=\"").append(PhantomCompat.BUTTON_BACK).append("\" fore=\"").append(PhantomCompat.BUTTON_FORE).append("\">");
		}
		sb.append(tr("</center><br><font color=\"808080\">Лут — по очереди (случайно) на всю пати. Телепорт — из мирной зоны.</font></body></html>"));
		return sb.toString();
	}

	private static String intervalText()
	{
		final int[] minutes = PhantomHuntersConfig.HUNTER_RAID_INTERVAL;
		final int lo = minutes.length > 0 ? minutes[0] : 60;
		final int hi = minutes.length > 1 ? Math.max(lo, minutes[1]) : lo;
		return lo == hi ? lo + tr(" мин") : lo + "–" + hi + tr(" мин");
	}

	// ---------- Для других ----------

	/**
	 * Для «Мой мир» ({@code activity} в L2HubHunters.json): охотник в рейде — лидер или участник. На охоте на Танатоса места нет: где он — секрет (раздел 4.45).
	 * @return дописал {@code ,"activity":{"kind":"raid",…}}
	 */
	boolean activity(StringBuilder sb, Player player)
	{
		final Raid led = ledBy(player);
		final Raid raid = led != null ? led : memberOf(player);
		if ((raid == null) || ((raid.leaderId != player.getObjectId()) && !raid.members.contains(player.getObjectId())))
		{
			return false;
		}
		sb.append(",\"activity\":{\"kind\":\"raid\",");
		FakePlayerBrain.field(sb, "title", raid.thanatos ? "Танатос" : raid.bossName).append(',');
		FakePlayerBrain.field(sb, "stage", raid.stage.name()).append(',');
		FakePlayerBrain.field(sb, "place", raid.thanatos ? "" : raid.place).append(",\"since\":").append(raid.started).append(",\"bossLevel\":").append(raid.bossLevel);
		sb.append(",\"leader\":").append(raid.leaderId == player.getObjectId()).append(",\"superChampion\":").append(raid.superChampion).append(",\"epic\":").append(raid.epic).append(",\"thanatos\":").append(raid.thanatos).append('}');
		return true;
	}

	/**
	 * @param player охотник
	 * @param now сейчас
	 * @return он только что вышел из рейда или караула: смерть сейчас — ещё оттуда, спот тут ни при чём (раздел 4.56)
	 */
	public boolean justLeft(Player player, long now)
	{
		final Long at = _leftAt.get(player.getObjectId());
		return (at != null) && ((now - at) < LEFT_GRACE);
	}

	/**
	 * Лидер, легший последним, сам закрывает рейд, а менеджер охотников увидит его смерть только следующим тиком: уже без рейда.
	 */
	private void rememberLeft(int leaderId, Set<Integer> members)
	{
		final long now = System.currentTimeMillis();
		_leftAt.put(leaderId, now);
		for (int id : members)
		{
			_leftAt.put(id, now);
		}
	}

	/**
	 * @param player охотник
	 * @return он лидер рейда — «босс (ур.)» для мозгов и окна охотников; иначе {@code null}
	 */
	public String raidOf(Player player)
	{
		final Raid raid = ledBy(player);
		final Watch watch = _watch;
		if ((raid == null) && (watch != null) && (watch.leaderId == player.getObjectId()))
		{
			return watch.bossName + " (" + watch.bossLevel + ")";
		}
		return raid != null ? raid.bossName + " (" + raid.bossLevel + ")" : null;
	}

	// ---------- Караул у респа (раздел 4.81) ----------

	/**
	 * @return РБ, которые ещё не встали: убитого за эту сессию менеджер помнит сам, а респ, поставленный при старте сервера, держит только в таймере — его время из базы
	 */
	private List<Respawn> respawns(long now)
	{
		if (_respawnsAtStart == null)
		{
			_respawnsAtStart = loadRespawns();
		}
		final Map<Integer, StatSet> stored = PhantomCompat.raidStoredInfo();
		final List<Respawn> respawns = new ArrayList<>();
		for (Spawn spawn : PhantomCompat.raidSpawns().values())
		{
			final StatSet info = stored.get(spawn.getId());
			final long at = info != null ? info.getLong("respawnTime", 0) : _respawnsAtStart.getOrDefault(spawn.getId(), 0L);
			if ((at > now) && spawn.getTemplate().isType("RaidBoss"))
			{
				respawns.add(new Respawn(spawn, at));
			}
		}
		return respawns;
	}

	private Map<Integer, Long> loadRespawns()
	{
		final Map<Integer, Long> respawns = new HashMap<>();
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(PhantomCompat.RAID_RESPAWNS_SQL);
			ResultSet rs = ps.executeQuery())
		{
			while (rs.next())
			{
				respawns.put(rs.getInt(1), rs.getLong(2));
			}
		}
		catch (SQLException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": could not read raid respawns: " + e);
		}
		return respawns;
	}

	/**
	 * Скоро встанет РБ: лидер и команда — по тем же правилам, что у рейда ({@link #plan}), но уровень босса — из шаблона, миньонов ещё нет (порог проклятия проверится, когда он встанет).
	 * Выходят не в одну минуту и не на каждый респ: {@value #WATCH_CHANCE} % на проверке.
	 * @return караул или {@code null}
	 */
	private Watch startWatch(List<Player> hunting, long now)
	{
		if (hunting.size() < PhantomHuntersConfig.HUNTER_RAID_MIN)
		{
			return null;
		}
		final List<Respawn> soon = new ArrayList<>();
		for (Respawn respawn : respawns(now))
		{
			final long left = respawn.at() - now;
			if ((left >= (WATCH_LEAD_MIN * 60000L)) && (left <= (PhantomHuntersConfig.HUNTER_RAID_WATCH_MINUTES * 60000L)) && (Rnd.get(100) < WATCH_CHANCE))
			{
				soon.add(respawn);
			}
		}
		if (soon.isEmpty())
		{
			return null;
		}

		final List<Player> leaders = new ArrayList<>(hunting);
		Collections.shuffle(leaders);
		for (Player leader : leaders)
		{
			if (!PhantomHunterManager.getInstance().isHunting(leader) || isHealer(leader))
			{
				continue;
			}
			for (Respawn respawn : soon)
			{
				final Spawn spawn = respawn.spawn();
				final int level = spawn.getTemplate().getLevel();
				if ((level < (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MIN)) || (level > (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MAX)) || (leader.getLevel() > (level + CURSE_GAP)))
				{
					continue;
				}
				final List<Player> members = members(leader, hunting, level, level + CURSE_GAP);
				if (((members.size() + 1) < PhantomHuntersConfig.HUNTER_RAID_MIN) || members.stream().noneMatch(PhantomRaid::isHealer))
				{
					continue;
				}
				final Location rally = rally(spawn.getX(), spawn.getY(), spawn.getZ());
				if (rally == null)
				{
					continue;
				}
				final Watch watch = new Watch(leader, spawn, rally, place(spawn.getX(), spawn.getY(), spawn.getTemplate().getName()), respawn.at());
				formParty(leader, new ArrayList<>(members.subList(0, Math.min(members.size(), PhantomHuntersConfig.HUNTER_RAID_MAX - 1))), rally, watch.members);
				LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " (" + leader.getLevel() + ") watches the respawn of " + watch.bossName + " (" + watch.bossLevel + ") at " + watch.place + " with " + watch.members.size() + " hunters, " + ((respawn.at() - now) / 1000) + " s left.");
				shout(leader, String.format(WATCH_SHOUTS[Rnd.get(WATCH_SHOUTS.length)], watch.bossName, watch.bossLevel, watch.place));
				PhantomNews.post("raid_killed", "Охотники " + leader.getName() + " (" + (watch.members.size() + 1) + ") караулят респ РБ " + watch.bossName + " (" + watch.bossLevel + " ур.) у " + watch.place, leader.getName(), leader);
				return watch;
			}
		}
		return null;
	}

	/**
	 * Раз в 10 с: лидер пропал или лёг — караула нет; босс встал — рейд; не встал за {@value #WATCH_LATE} мс после срока — расходятся.
	 */
	private void checkWatch(long now)
	{
		final Watch watch = _watch;
		if (watch == null)
		{
			return;
		}
		final Player leader = World.getPlayer(watch.leaderId);
		if ((leader == null) || !leader.isOnline() || leader.isDead())
		{
			endWatch(watch, leader);
			return;
		}
		if (watch.endText != null)
		{
			return;
		}
		for (Npc boss : PhantomCompat.raidBosses())
		{
			if ((boss != null) && (boss.getId() == watch.bossId) && boss.isSpawned() && !boss.isAlikeDead())
			{
				// Порог проклятия теперь виден по миньонам; босс встал там, где рейды не ходят, — как у плана.
				if ((boss.getInstanceId() != 0) || boss.isInsideZone(ZoneId.PEACE) || boss.isInsideZone(ZoneId.SIEGE) || (leader.getLevel() > curseLevel(boss)))
				{
					watch.endText = String.format(WATCH_GONE_SHOUTS[Rnd.get(WATCH_GONE_SHOUTS.length)], watch.bossName);
				}
				else
				{
					_watch = null;
					_raid = fromWatch(watch, leader, boss, now);
				}
				return;
			}
		}
		if (now > (watch.respawnAt + WATCH_LATE))
		{
			watch.endText = String.format(WATCH_GONE_SHOUTS[Rnd.get(WATCH_GONE_SHOUTS.length)], watch.bossName);
		}
	}

	/**
	 * Босс встал: караул становится обычным рейдом на этапе сбора, бой через {@value #WATCH_READY} мс. Кто выше порога проклятия, тот уходит: его миньонов до спавна не было видно.
	 */
	private Raid fromWatch(Watch watch, Player leader, Npc boss, long now)
	{
		final Raid raid = new Raid(leader, boss, watch.rally, watch.place, now, false);
		raid.stageUntil = now + WATCH_READY;
		final int curse = curseLevel(boss);
		for (int id : watch.members)
		{
			final Player member = World.getPlayer(id);
			if ((member == null) || !member.isInParty() || (member.getParty() != leader.getParty()))
			{
				continue;
			}
			if (member.getLevel() <= curse)
			{
				raid.members.add(id);
				PhantomHunterManager.raidBuff(member);
			}
			else
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(member.getName());
				if (template != null)
				{
					PhantomCompanionManager.getInstance().release(template, true);
				}
			}
		}
		PhantomHunterManager.raidBuff(leader);
		_nextRaid = Math.max(_nextRaid, now + interval());
		LOGGER.info(getClass().getSimpleName() + ": " + boss.getName() + " is up, " + leader.getName() + "'s watch becomes a raid: " + String.join(", ", names(raid)) + "; fight in " + (WATCH_READY / 1000) + " s.");
		shout(leader, String.format(WATCH_UP_SHOUTS[Rnd.get(WATCH_UP_SHOUTS.length)], raid.bossName));
		callPlayers(raid, "");
		return raid;
	}

	/**
	 * Караул кончился без боя: охотники — из пати назад на кач; лидера уводит менеджер охотников ({@link Drive#DONE}).
	 */
	private void endWatch(Watch watch, Player leader)
	{
		_watch = null;
		rememberLeft(watch.leaderId, watch.members);
		for (int id : watch.members)
		{
			final Player member = World.getPlayer(id);
			final PhantomTemplate template = member == null ? null : PhantomData.getInstance().getTemplate(member.getName());
			if (template != null)
			{
				PhantomCompanionManager.getInstance().release(template, true);
			}
		}
		if ((leader != null) && leader.isInParty())
		{
			leader.leaveParty();
		}
		if ((leader != null) && (watch.endText != null))
		{
			shout(leader, watch.endText);
		}
		_lastRaidEnd = System.currentTimeMillis();
		LOGGER.info(getClass().getSimpleName() + ": the watch on " + watch.bossName + " is over without a fight.");
	}

	// ---------- Очередь за РБ (раздел 4.65) ----------

	/**
	 * Пока идёт сбор: на того же босса приходит второй рейд, лидеры перекрикиваются, в летопись. Не на эпика, суперчемпиона и Танатоса; один раз на рейд, третьего нет.
	 */
	private void rivals(List<Player> hunting, long now)
	{
		final Raid first = _raid;
		final boolean test = _testRivals != 0;
		if (!PhantomHuntersConfig.HUNTER_RAID_RIVALS || (first == null) || (_rival != null) || first.rival || first.rivalTried || first.epic || first.thanatos || first.superChampion || (first.stage != Stage.GATHER) || (first.result != null) || !(first.boss instanceof Npc) || (!test && ((now - first.started) < RIVAL_DELAY)))
		{
			return;
		}
		first.rivalTried = true;
		if (!test && (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_RAID_RIVAL_CHANCE))
		{
			return;
		}
		final List<Player> free = new ArrayList<>(hunting);
		free.removeIf(player -> inRaid(first, player.getObjectId()));
		final Plan plan = plan(free, List.of((Npc) first.boss), null);
		if (plan == null)
		{
			LOGGER.info(getClass().getSimpleName() + ": no team for a second raid on " + first.bossName + ".");
			return;
		}
		final Raid rival = start(plan.leader(), plan.team(), plan.boss(), plan.rally(), now, false);
		rival.rival = true;
		rival.desperate = PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY && (test || (Rnd.get(100) < RIVAL_DESPERATE));
		if (test)
		{
			rival.stageUntil = now + TEST_GATHER;
		}
		_rival = rival;
		LOGGER.info(getClass().getSimpleName() + ": " + plan.leader().getName() + " brings a second raid to " + first.bossName + (rival.desperate ? ", will not wait." : ", waits in the queue."));
		PhantomNews.post("raid_killed", "Охотники " + plan.leader().getName() + " и " + nameOf(first.leaderId) + " не поделили РБ " + first.bossName + " (" + first.bossLevel + " ур.) у " + first.place, plan.leader().getName(), plan.leader());
		shout(plan.leader(), String.format(RIVAL_CLAIM_SHOUTS[Rnd.get(RIVAL_CLAIM_SHOUTS.length)], first.bossName));
		ThreadPool.schedule(() ->
		{
			final Player leader = World.getPlayer(first.leaderId);
			if ((leader != null) && (_raid == first) && (first.result == null))
			{
				shout(leader, String.format(FIRST_CLAIM_SHOUTS[Rnd.get(FIRST_CLAIM_SHOUTS.length)], first.bossName));
			}
		}, Rnd.get(8000, 15000));
	}

	/**
	 * Второй рейд на сборе. Босса убили — расходятся; первый ещё бьётся — ждут, кроме отчаянных; первый кончился — их очередь, а занятого не трогают, пока действует это правило.
	 * @return {@code true} — рейд кончился
	 */
	private boolean rivalGather(Raid raid, Player leader, long now)
	{
		if (raid.boss.isAlikeDead() || !raid.boss.isSpawned())
		{
			dismiss(raid, leader, "the first raid took the boss", String.format(RIVAL_LOST_SHOUTS[Rnd.get(RIVAL_LOST_SHOUTS.length)], raid.bossName));
			return true;
		}
		final Raid first = _raid;
		if (first != raid)
		{
			if (!raid.desperate)
			{
				raid.stageUntil = Math.max(raid.stageUntil, now + RIVAL_WAIT);
			}
			else if (!raid.rivalSaid && (now >= raid.stageUntil))
			{
				raid.rivalSaid = true;
				shout(leader, String.format(RIVAL_DESPERATE_SHOUTS[Rnd.get(RIVAL_DESPERATE_SHOUTS.length)], raid.bossName));
			}
			return false;
		}
		if (!raid.rivalSaid)
		{
			raid.rivalSaid = true;
			shout(leader, String.format(RIVAL_TURN_SHOUTS[Rnd.get(RIVAL_TURN_SHOUTS.length)], raid.bossName));
		}
		if (!PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY && raid.boss.isInCombat())
		{
			raid.stageUntil = Math.max(raid.stageUntil, now + RIVAL_WAIT);
		}
		return false;
	}

	/**
	 * Рейд кончился: следующий в очереди становится главным.
	 */
	private void forget(Raid raid)
	{
		rememberLeft(raid.leaderId, raid.members);
		if (_raid == raid)
		{
			_raid = _rival;
			_rival = null;
			if (_raid != null)
			{
				LOGGER.info(getClass().getSimpleName() + ": the queue moves: " + nameOf(_raid.leaderId) + "'s raid is next on " + _raid.bossName + ".");
			}
		}
		else if (_rival == raid)
		{
			_rival = null;
		}
	}

	/**
	 * @return рейд, который ведёт этот охотник
	 */
	private Raid ledBy(Player player)
	{
		final Raid first = _raid;
		if ((first != null) && (first.leaderId == player.getObjectId()))
		{
			return first;
		}
		final Raid second = _rival;
		return (second != null) && (second.leaderId == player.getObjectId()) ? second : null;
	}

	/**
	 * @return рейд, где этот охотник участник; нет его ни в одном — первый или {@code null}
	 */
	private Raid memberOf(Player player)
	{
		final Raid second = _rival;
		return (second != null) && second.members.contains(player.getObjectId()) ? second : _raid;
	}

	/**
	 * Босс убит, а бились двумя рейдами: кто набил меньше, тот его упустил. Обида лидера, летопись, крик; рейд кончается без победы.
	 * @return этот рейд проиграл
	 */
	private boolean lostTheRace(Raid raid, Player leader)
	{
		final Raid other = raid == _raid ? _rival : _raid;
		if ((other == null) || (other.stage == Stage.GATHER) || (other.damageDone <= raid.damageDone) || !raid.boss.isDead())
		{
			return false;
		}
		final Player winner = World.getPlayer(other.leaderId);
		if (winner != null)
		{
			PhantomRelations.getInstance().record(leader, winner, PhantomRelations.Kind.CONTESTED);
			PhantomNews.post("raid_killed", "Охотники " + winner.getName() + " отбили РБ " + raid.bossName + " (" + raid.bossLevel + " ур.) у охотников " + leader.getName(), winner.getName(), winner);
		}
		dismiss(raid, leader, "lost the race for the boss: " + (int) raid.damageDone + " damage against " + (int) other.damageDone, String.format(RIVAL_LOST_SHOUTS[Rnd.get(RIVAL_LOST_SHOUTS.length)], raid.bossName));
		return true;
	}

	/**
	 * Самопроверка RAIDRIVAL (из {@code PhantomHunterManager}, только на копии базы): круг первый — второй рейд ждёт очереди; круг второй — {@code HunterRaidTakeBusy}, он бьёт вместе с первым.
	 * Сбор сокращён, хилер не обязателен: на копии базы их на двоих может не хватить. Обычные рейды на это время отложены. Ключи возвращаются.
	 */
	synchronized void selfTestRivals()
	{
		final long now = System.currentTimeMillis();
		_testRivals = 1;
		_testRivalKeys = new boolean[]
		{
			PhantomHuntersConfig.HUNTER_RAIDS,
			PhantomHuntersConfig.HUNTER_RAID_RIVALS,
			PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY
		};
		PhantomHuntersConfig.HUNTER_RAIDS = true;
		PhantomHuntersConfig.HUNTER_RAID_RIVALS = true;
		PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY = false;
		_nextRaid = now + TEST_POSTPONE;
		_nextSuper = now + TEST_POSTPONE;
		_nextEpic = now + TEST_POSTPONE;
		LOGGER.info(getClass().getSimpleName() + ": SelfTest RAIDRIVAL: starts, " + _candidates.size() + " candidates.");
		ThreadPool.schedule(this::testRivalsStep, 15000);
	}

	private synchronized void testRivalsStep()
	{
		final String tag = getClass().getSimpleName() + ": SelfTest RAIDRIVAL: ";
		final long now = System.currentTimeMillis();
		final Raid main = _raid;
		final Raid rival = _rival;
		if ((main == null) && (_testRivalStarted != 0))
		{
			_testRivalStarted = 0;
			LOGGER.info(tag + "round " + _testRivals + " is over.");
			if (_testRivals == 2)
			{
				PhantomHuntersConfig.HUNTER_RAIDS = _testRivalKeys[0];
				PhantomHuntersConfig.HUNTER_RAID_RIVALS = _testRivalKeys[1];
				PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY = _testRivalKeys[2];
				_testRivals = 0;
				LOGGER.info(tag + "done.");
				return;
			}
			_testRivals = 2;
			_testRivalRetries = 0;
			PhantomHuntersConfig.HUNTER_RAID_TAKE_BUSY = true;
		}
		if ((main == null) && (now >= _testRivalNext))
		{
			_testRivalNext = now + 30000;
			final Raid raid = assemble(_candidates, now, PhantomCompat.raidBosses());
			if (raid == null)
			{
				LOGGER.info(tag + "round " + _testRivals + ": no raid can be assembled now, " + _candidates.size() + " candidates; tries again.");
			}
			else
			{
				_raid = raid;
				_testRivalStarted = now;
				raid.stageUntil = now + TEST_GATHER;
				LOGGER.info(tag + "round " + _testRivals + ": the first raid on " + raid.bossName + " (" + raid.bossLevel + "), leader " + nameOf(raid.leaderId) + ", " + raid.members.size() + " members.");
			}
		}
		else if ((main != null) && (rival == null) && !main.rival && !main.rivalTried)
		{
			rivals(_candidates, now);
			LOGGER.info(tag + (_rival != null ? "the second raid: leader " + nameOf(_rival.leaderId) + ", " + _rival.members.size() + " members, desperate " + _rival.desperate + "." : "no team for a second raid."));
		}
		else if (main != null)
		{
			LOGGER.info(tag + "first " + main.stage + " " + (int) main.damageDone + " damage, " + (rival != null ? "second " + rival.stage + " " + (int) rival.damageDone + " damage" : "no second raid") + ", boss " + bossHp(main) + "% HP.");
		}
		// Круг без второго рейда ничего не проверяет: другой босс, другие охотники.
		final Player mainLeader = main == null ? null : World.getPlayer(main.leaderId);
		if ((mainLeader != null) && (_raid == main) && (_rival == null) && main.rivalTried && !main.rival && (main.stage == Stage.GATHER) && (main.result == null) && (_testRivalRetries < TEST_RETRIES))
		{
			_testRivalRetries++;
			_testRivalStarted = 0;
			_testRivalNext = now + TEST_RETRY_DELAY;
			LOGGER.info(tag + "no team for a second raid on " + main.bossName + ", another boss (" + _testRivalRetries + ").");
			dismiss(main, mainLeader, "self-test: no team for a second raid", "расходимся");
		}
		if ((_testRivalStarted != 0) && ((now - _testRivalStarted) > TEST_ROUND))
		{
			LOGGER.warning(tag + "the round takes too long, the raids are dismissed.");
			for (Raid raid : new Raid[]
			{
				rival,
				main
			})
			{
				final Player leader = raid == null ? null : World.getPlayer(raid.leaderId);
				if (leader != null)
				{
					dismiss(raid, leader, "self-test timeout", "расходимся");
				}
			}
		}
		ThreadPool.schedule(this::testRivalsStep, 15000);
	}

	private static List<String> names(Raid raid)
	{
		final List<String> names = new ArrayList<>();
		final Player leader = World.getPlayer(raid.leaderId);
		names.add(leader != null ? leader.getName() : "?");
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				names.add(member.getName());
			}
		}
		for (int id : raid.players)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				names.add(member.getName());
			}
		}
		return names;
	}

	/**
	 * Для лога: кто в рейде и кто из живых был рядом.
	 */
	private static String participants(Raid raid)
	{
		final Player leader = World.getPlayer(raid.leaderId);
		final List<String> players = new ArrayList<>();
		for (int id : raid.players)
		{
			final Player player = World.getPlayer(id);
			if ((player != null) && (leader != null) && (player.calculateDistance2D(leader) <= PARTICIPATION_RANGE))
			{
				players.add(player.getName());
			}
		}
		return "hunters " + String.join(", ", names(raid).subList(0, Math.min(names(raid).size(), raid.members.size() + 1))) + (players.isEmpty() ? "" : ", players " + String.join(", ", players));
	}

	public static PhantomRaid getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomRaid INSTANCE = new PhantomRaid();
	}
}
