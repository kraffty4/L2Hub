package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.SuperChampion;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

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
 * Эпики (раздел 4.43, {@code HunterEpicRaids}): из {@link #EPICS} — живые и без входа через NPC; пати {@code HunterEpicSize}, множители {@code HunterEpicDamage}
 * и {@code HunterEpicArmor}; сбор и провал — в «Летопись мира» ({@link PhantomNews}), убийство летопись пишет сама.
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
	/** Кто выше босса больше чем на столько уровней, на того РБ кладёт проклятие (паралич): в рейд таких не берут. */
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
	/**
	 * Эпики без входа через NPC и предмет: Королева муравьёв, Ядро, Орфен, старый Закен (на Devil's Isle; у H5 — инстанс, его нет). Баюма будят тканью у статуи,
	 * к Антарасу и Валакасу пускают через NPC с камнем, Фринтеза и Белет — инстансы под командный канал: не здесь.
	 */
	private static final int[] EPICS =
	{
		29001, // Queen Ant
		29006, // Core
		29014, // Orfen
		29022 // Zaken
	};
	/** Эпик: как часто искать; первый раз — через столько после старта. */
	private static final long EPIC_RETRY = 300000;
	private static final long EPIC_FIRST = 600000;
	private static final String[] EPIC_GATHER_SHOUTS =
	{
		"собираем рейд на эпика %s (%d ур)! сбор у %s, через %d мин бьём — нужны все",
		"го на %s %d лвл, эпик! сбор у %s, %d мин на подход",
		"эпик %s (%d) у %s, идём большой пати, через %d мин начинаем — кто с нами?"
	};
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
		final int leaderId;
		final Npc boss;
		final String bossName;
		final int bossLevel;
		final Location rally;
		final String place;
		final long started;
		/** Цель — суперчемпион (патч 0020), а не РБ: без множителей урона и брони, конец — и когда он перестал быть суперчемпионом. */
		final boolean superChampion;
		/** Цель — эпик (раздел 4.43): свои множители урона и брони. */
		final boolean epic;
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
		long lastReport;
		/** На боссе на время боя: удары охотников рейда добираются до {@code HunterRaidDamage}. */
		ConsumerEventListener damage;
		/** На охотниках рейда на время боя: удар босса ослаблен в {@code HunterRaidArmor} раз. */
		final java.util.Map<Integer, ConsumerEventListener> armor = new java.util.concurrent.ConcurrentHashMap<>();

		Raid(Player leader, Npc boss, Location rally, String place, long now)
		{
			leaderId = leader.getObjectId();
			this.boss = boss;
			bossName = boss.getName();
			bossLevel = boss.getLevel();
			this.rally = rally;
			this.place = place;
			started = now;
			superChampion = SuperChampion.isSuper(boss);
			epic = boss instanceof GrandBoss;
			stageUntil = now + (superChampion ? SUPER_GATHER : (PhantomHuntersConfig.HUNTER_RAID_GATHER * 60000L));
		}
	}

	private volatile Raid _raid;
	private volatile long _nextRaid;
	private long _nextSuper;
	private long _nextEpic;
	/** Кого можно позвать — с последнего {@link #control} (для рейда по заказу стратега). */
	private volatile List<Player> _candidates = List.of();
	private volatile long _lastRaidEnd;
	private boolean _commandRegistered;

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
			return;
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
		// Эпик (раздел 4.43): жив, без входа через NPC и под охотников — рейд на него, без общего интервала рейдов.
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
	 * @return живые эпики из {@link #EPICS} (каких нет у хроники — {@code null} у менеджера, пропускаются)
	 */
	private static List<Npc> epics()
	{
		final List<Npc> epics = new ArrayList<>();
		for (int id : EPICS)
		{
			final GrandBoss boss = GrandBossManager.getInstance().getBoss(id);
			if (boss != null)
			{
				epics.add(boss);
			}
		}
		return epics;
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
		return plan == null ? null : start(plan.leader(), plan.team(), plan.boss(), plan.rally(), now);
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
		_raid = start(plan.leader(), plan.team(), plan.boss(), plan.rally(), now);
		_nextRaid = Math.max(_nextRaid, now + interval());
		return true;
	}

	private Plan orderPlan(Player leader, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_RAIDS || (_raid != null) || (_nextRaid == 0) || ((now - _lastRaidEnd) < ORDER_GAP))
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
			if ((boss != null) && boss.isSpawned() && !boss.isAlikeDead() && !boss.isInCombat() && (boss.getInstanceId() == 0) && !boss.isInsideZone(ZoneId.PEACE) && !boss.isInsideZone(ZoneId.SIEGE))
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
				if ((boss.getLevel() >= (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MIN)) && (boss.getLevel() <= (leader.getLevel() + PhantomHuntersConfig.HUNTER_RAID_BOSS_LEVEL_MAX)) && (leader.getLevel() <= (boss.getLevel() + CURSE_GAP)))
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

			final List<Player> members = new ArrayList<>();
			for (Player other : hunting)
			{
				if ((other != leader) && (Math.abs(other.getLevel() - leader.getLevel()) <= MEMBER_LEVEL_GAP) && (other.getLevel() >= (boss.getLevel() + MEMBER_OVER_BOSS)) && (other.getLevel() <= (boss.getLevel() + CURSE_GAP)) && !PhantomRelations.getInstance().isEnemy(leader, other) && !PhantomRelations.getInstance().isEnemy(other, leader))
				{
					members.add(other);
				}
			}
			// Без хилера босс кладёт пати — нет свободного хилера, нет и рейда.
			if (((members.size() + 1) < minSize) || members.stream().noneMatch(PhantomRaid::isHealer))
			{
				continue;
			}
			// Хилер — первым, дальше — ближе по уровню к боссу.
			members.sort(Comparator.comparingInt((Player p) -> isHealer(p) ? 0 : 1).thenComparingInt(p -> Math.abs(p.getLevel() - boss.getLevel())));
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

	private static boolean isHealer(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && (template.getRole() == PhantomTemplate.Role.HEALER);
	}

	/**
	 * Точка сбора: в {@value #RALLY_DISTANCE} от босса, откуда к нему ведёт прямая по геодате; восемь сторон по очереди.
	 */
	private static Location rally(Npc boss)
	{
		final GeoEngine geo = GeoEngine.getInstance();
		final double start = Rnd.nextDouble() * 2 * Math.PI;
		for (int i = 0; i < 8; i++)
		{
			final double angle = start + ((i * Math.PI) / 4);
			final int x = boss.getX() + (int) (Math.cos(angle) * RALLY_DISTANCE);
			final int y = boss.getY() + (int) (Math.sin(angle) * RALLY_DISTANCE);
			final int z = geo.getHeight(x, y, boss.getZ());
			if ((Math.abs(z - boss.getZ()) < 400) && PhantomCompat.canMoveToTarget(geo, x, y, z, boss.getX(), boss.getY(), boss.getZ(), 0))
			{
				return new Location(x, y, z);
			}
		}
		return null;
	}

	/**
	 * Начать: участники — в пати лидера, «бей его цель»; бафы; лидер (участники — за ним, напарники догоняют сами) — к точке сбора; крик о сборе.
	 */
	private Raid start(Player leader, List<Player> team, Npc boss, Location rally, long now)
	{
		final Raid raid = new Raid(leader, boss, rally, place(boss), now);
		final String what = raid.epic ? "эпика " : raid.superChampion ? "легендарного чемпиона " : "";
		PhantomHunterManager.getInstance().raidStarts(leader);
		PhantomHunterManager.payTeleportFee(leader);
		leader.teleToLocation(rally, true);
		for (Player member : team)
		{
			PhantomHunterManager.getInstance().prepareForRaid(member);
			PhantomCompanionManager.getInstance().onInvited(leader, member, PartyDistributionType.RANDOM);
			if (member.isInParty() && (member.getParty() == leader.getParty()))
			{
				raid.members.add(member.getObjectId());
				PhantomHunterManager.raidBuff(member);
			}
		}
		final List<String> names = names(raid);
		final long gather = (raid.stageUntil - now) / 60000;
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " (" + leader.getLevel() + ") leads a raid on " + (raid.epic ? "epic " : raid.superChampion ? "super champion " : "") + raid.bossName + " (" + raid.bossLevel + ") at " + raid.place + ": " + String.join(", ", names) + "; fight in " + gather + " min.");

		final String[] shouts = raid.epic ? EPIC_GATHER_SHOUTS : raid.superChampion ? SUPER_GATHER_SHOUTS : GATHER_SHOUTS;
		final String text = String.format(shouts[Rnd.get(shouts.length)], raid.bossName, raid.bossLevel, raid.place, gather);
		PhantomChat.getInstance().hunterShout(leader, text);
		if (raid.epic)
		{
			PhantomNews.post("epic_raid", "Охотники " + leader.getName() + " (" + (raid.members.size() + 1) + ") собирают рейд на эпика " + raid.bossName + " (" + raid.bossLevel + " ур.) у " + raid.place, leader.getName(), leader);
		}
		for (Player player : World.getPlayers())
		{
			if (!PhantomManager.getInstance().isPhantom(player) && (player.getLevel() >= (raid.bossLevel - PLAYER_LEVEL_GAP)))
			{
				player.sendMessage("Охотники собирают рейд на " + what + raid.bossName + " (" + raid.bossLevel + " ур.) — .raid: вступить и телепорт к сбору.");
			}
		}
		return raid;
	}

	/**
	 * Где сбор, словами: ближайший спот охотников (у них в имени ориентир) или зона карты.
	 */
	private static String place(Npc boss)
	{
		HuntSpot nearest = null;
		double best = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			final double distance = Math.hypot(spot.entry.getX() - boss.getX(), spot.entry.getY() - boss.getY());
			if (distance < best)
			{
				best = distance;
				nearest = spot;
			}
		}
		return nearest != null ? nearest.name : boss.getName();
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
		final Raid raid = _raid;
		if ((raid == null) || (raid.leaderId != player.getObjectId()))
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
					// РБ бьёт кто-то другой (игрок, чужая пати) — не мешать: дольше BUSY_GRACE бой не стих — рейд распускается.
					if (!raid.superChampion && raid.boss.isInCombat())
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
					if (now < raid.stageUntil)
					{
						// Ждёт у точки сбора: бьют — отбиться, отошёл — вернуться, стоит — сесть отдохнуть.
						final Monster attacker = attacker(player);
						if (attacker != null)
						{
							PhantomCompanionManager.fight(player, attacker);
							return Drive.LEADS;
						}
						if (player.calculateDistance2D(raid.rally) > 300)
						{
							if (!player.isMoving())
							{
								player.setRunning();
								player.getAI().setIntentionMoveTo(raid.rally);
							}
						}
						else if (!player.isSitting() && !player.isMoving() && !player.isInCombat())
						{
							player.sitDown();
						}
						return Drive.LEADS;
					}
					raid.stage = Stage.FIGHT;
					raid.stageUntil = now + FIGHT_TIME;
					// Множители — для РБ (их HP и защита не по силам пати своего уровня); суперчемпион — обычный моб x20 HP, бьют как есть.
					if (!raid.superChampion && (damageOf(raid) > 1))
					{
						raid.damage = new ConsumerEventListener(raid.boss, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onBossDamaged(raid, event), this);
						raid.boss.addListener(raid.damage);
					}
					if (!raid.superChampion && (armorOf(raid) > 1))
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
					PhantomCompanionManager.partySay(player, Rnd.nextBoolean() ? "го, бьём " + raid.bossName + "!" : "погнали!");
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " starts the fight with " + raid.bossName + " (" + (raid.members.size() + 1) + " hunters, " + raid.players.size() + " players).");
					return Drive.LEADS;
				}
				case FIGHT:
				{
					// Суперчемпион мёртв и уже встал обычным мобом (респавн) — тоже конец.
					if (raid.boss.isAlikeDead() || !raid.boss.isSpawned() || (raid.superChampion && !SuperChampion.isSuper(raid.boss)))
					{
						raid.stage = Stage.LOOT;
						raid.stageUntil = now + LOOT_TIME;
						LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + "'s raid killed " + raid.bossName + " in " + ((now - raid.started) / 1000) + " s; " + participants(raid) + ".");
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
						LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " fights " + raid.bossName + ": boss " + bossHp(raid) + "% HP, leader " + (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp())) + "% HP, alive " + alive(raid) + ".");
					}
					if (player.isSitting())
					{
						player.standUp();
						return Drive.LEADS;
					}
					if (raid.boss.calculateDistance2D(raid.rally) > BOSS_LEASH)
					{
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
					if (!player.isCastingNow())
					{
						PhantomCompanionManager.fight(player, raid.boss);
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
	 * Раз в 10 с: лидер погиб или пропал — рейд провален (сам лидер уже не ведёт его на тике).
	 */
	synchronized void check(long now)
	{
		final Raid raid = _raid;
		if (raid == null)
		{
			return;
		}
		final Player leader = World.getPlayer(raid.leaderId);
		if ((leader == null) || !leader.isOnline())
		{
			LOGGER.info(getClass().getSimpleName() + ": raid on " + raid.bossName + " is over: the leader left the world.");
			release(raid, null);
			_raid = null;
			return;
		}
		if (raid.result != null)
		{
			return;
		}
		// Лидер лёг — ждём, что поднимут (хилер в пати ресает); не подняли за минуту или легли все — провал.
		if (!leader.isDead())
		{
			raid.leaderDeadSince = 0;
			return;
		}
		if (raid.leaderDeadSince == 0)
		{
			raid.leaderDeadSince = now;
		}
		boolean anyAlive = false;
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			anyAlive |= (member != null) && !member.isDead() && member.isInParty() && (member.getParty() == leader.getParty());
		}
		if (!anyAlive || ((now - raid.leaderDeadSince) > LEADER_DEAD_GRACE))
		{
			fail(raid, leader, anyAlive ? "the leader died" : "everyone died");
			finish(raid, leader);
		}
	}

	private void fail(Raid raid, Player leader, String why)
	{
		raid.result = "fail";
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + "'s raid on " + raid.bossName + " failed (" + why + ") after " + ((System.currentTimeMillis() - raid.started) / 1000) + " s, the boss has " + bossHp(raid) + "% HP; " + participants(raid) + ".");
		shout(leader, String.format(FAIL_SHOUTS[Rnd.get(FAIL_SHOUTS.length)], raid.bossName));
		if (raid.epic)
		{
			PhantomNews.post("epic_raid", "Рейд охотников " + leader.getName() + " на эпика " + raid.bossName + " не удался: у босса " + bossHp(raid) + "% HP", leader.getName(), leader);
		}
	}

	/**
	 * @return во сколько раз удар охотника рейда по боссу сильнее: эпик — {@code HunterEpicDamage}, РБ — {@code HunterRaidDamage}
	 */
	private static float damageOf(Raid raid)
	{
		return raid.epic ? PhantomHuntersConfig.HUNTER_EPIC_DAMAGE : PhantomHuntersConfig.HUNTER_RAID_DAMAGE;
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
		if (_raid != raid)
		{
			return;
		}
		release(raid, leader);
		_raid = null;
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
		raid.boss.reduceCurrentHp(event.getDamage() * (damageOf(raid) - 1), attacker, null);
	}

	/**
	 * Охотнику рейда — слушатель: удар босса (и его миньонов) возвращается до 1/{@code HunterRaidArmor} — босс положил бы пятерых за минуту.
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
		if ((attacker == null) || hunter.isDead() || (event.getDamage() <= 0) || ((attacker != raid.boss) && !(attacker.isMonster() && attacker.asMonster().getLeader() == raid.boss)))
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
			return "Рейда сейчас нет.";
		}
		if (player.getLevel() < (raid.bossLevel - PLAYER_LEVEL_GAP))
		{
			return "Рано: на " + raid.bossName + " берут с " + (raid.bossLevel - PLAYER_LEVEL_GAP) + "-го уровня.";
		}
		if (player.getLevel() > (raid.bossLevel + CURSE_GAP))
		{
			return "Ты выше " + raid.bossName + " больше чем на " + CURSE_GAP + " уровней — РБ положит на тебя проклятие. Рейд — до " + (raid.bossLevel + CURSE_GAP) + "-го уровня.";
		}
		if (player.isInParty())
		{
			return player.getParty() == leader.getParty() ? "Ты уже в пати рейда." : "Сначала выйди из своей пати.";
		}
		if (player.isInOlympiadMode())
		{
			return "Не с олимпиады.";
		}
		if (!leader.isInParty())
		{
			PhantomCompat.newParty(leader, PartyDistributionType.RANDOM);
		}
		if (leader.getParty().getMemberCount() >= 9)
		{
			return "Пати рейда полная.";
		}
		player.joinParty(leader.getParty());
		raid.players.add(player.getObjectId());
		PhantomCompanionManager.partySay(leader, Rnd.nextBoolean() ? player.getName() + ", велкам, держись за хилом" : "о, " + player.getName() + " с нами, норм");
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") joins the raid on " + raid.bossName + ".");
		return null;
	}

	/**
	 * Телепорт к точке сбора ({@code .raid go}): с 40-го уровня — за плату, как у охотников.
	 * @return ошибка или {@code null}
	 */
	String teleport(Player player)
	{
		final Raid raid = _raid;
		if ((raid == null) || (raid.result != null))
		{
			return "Рейда сейчас нет.";
		}
		final int fee = player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0;
		if ((fee > 0) && !PhantomCompat.reduceAdena(player, ItemProcessType.FEE, fee, player, true))
		{
			return "Не хватает адены на телепорт: " + fee + ".";
		}
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		player.teleToLocation(new Location(raid.rally.getX() + (int) (Math.cos(angle) * 120), raid.rally.getY() + (int) (Math.sin(angle) * 120), raid.rally.getZ()), true);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " teleports to the raid on " + raid.bossName + ".");
		return null;
	}

	/**
	 * Окно {@code .raid}.
	 */
	void show(Player player)
	{
		final Raid raid = _raid;
		final StringBuilder sb = new StringBuilder("<html><title>Рейд охотников</title><body><center><font color=\"LEVEL\">Рейд охотников</font></center><br>");
		final Player leader = raid == null ? null : World.getPlayer(raid.leaderId);
		if ((raid == null) || (leader == null) || (raid.result != null))
		{
			final long next = Math.max(0, (_nextRaid - System.currentTimeMillis()) / 60000);
			sb.append("Сейчас рейда нет. Охотники собираются на РБ своего уровня примерно раз в ").append(intervalText()).append(PhantomHuntersConfig.HUNTER_RAIDS ? (_nextRaid > 0 ? " — следующий не раньше чем через " + next + " мин." : ".") : " (рейды выключены: HunterRaids).");
			sb.append("<br><br><font color=\"808080\">Когда соберутся — в торговом чате будет крик лидера, а тебе — сообщение.</font></body></html>");
			player.sendPacket(new NpcHtmlMessage(sb.toString()));
			return;
		}

		final long left = Math.max(0, (raid.stageUntil - System.currentTimeMillis()) / 1000);
		sb.append("<font color=\"LEVEL\">").append(raid.bossName).append("</font> (").append(raid.bossLevel).append(" ур.)<br1>");
		sb.append("Сбор: ").append(raid.place).append("<br1>");
		sb.append(raid.stage == Stage.GATHER ? "Бой — через " + (left / 60) + " мин " + (left % 60) + " с." : raid.stage == Stage.FIGHT ? "Идёт бой." : "Босс убит, собирают лут.").append("<br><br>");
		sb.append("<font color=\"B09878\">Пати:</font><br1>");
		final Player leaderPlayer = leader;
		sb.append(leaderPlayer.getName()).append(" (").append(leaderPlayer.getLevel()).append(", лидер)<br1>");
		for (int id : raid.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				sb.append(member.getName()).append(" (").append(member.getLevel()).append(isHealer(member) ? ", хилер" : "").append(member.isDead() ? ", лежит" : "").append(")<br1>");
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
			sb.append("<button value=\"Вступить\" action=\"bypass voice .raid join\" width=120 height=21 back=\"").append(PhantomCompat.BUTTON_BACK).append("\" fore=\"").append(PhantomCompat.BUTTON_FORE).append("\"><br>");
			final int fee = player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0;
			sb.append("<button value=\"К сбору").append(fee > 0 ? " (" + fee + ")" : "").append("\" action=\"bypass voice .raid go\" width=120 height=21 back=\"").append(PhantomCompat.BUTTON_BACK).append("\" fore=\"").append(PhantomCompat.BUTTON_FORE).append("\">");
		}
		sb.append("</center><br><font color=\"808080\">Лут — по очереди (случайно) на всю пати. Телепорт — из мирной зоны.</font></body></html>");
		player.sendPacket(new NpcHtmlMessage(sb.toString()));
	}

	private static String intervalText()
	{
		final int[] minutes = PhantomHuntersConfig.HUNTER_RAID_INTERVAL;
		final int lo = minutes.length > 0 ? minutes[0] : 60;
		final int hi = minutes.length > 1 ? Math.max(lo, minutes[1]) : lo;
		return lo == hi ? lo + " мин" : lo + "–" + hi + " мин";
	}

	// ---------- Для других ----------

	/**
	 * @param player охотник
	 * @return он лидер рейда — для мозгов и окна охотников
	 */
	/**
	 * Для «Мой мир» ({@code activity} в L2HubHunters.json): охотник в рейде — лидер или участник.
	 * @return дописал {@code ,"activity":{"kind":"raid",…}}
	 */
	boolean activity(StringBuilder sb, Player player)
	{
		final Raid raid = _raid;
		if ((raid == null) || ((raid.leaderId != player.getObjectId()) && !raid.members.contains(player.getObjectId())))
		{
			return false;
		}
		sb.append(",\"activity\":{\"kind\":\"raid\",");
		FakePlayerBrain.field(sb, "title", raid.bossName).append(',');
		FakePlayerBrain.field(sb, "stage", raid.stage.name()).append(',');
		FakePlayerBrain.field(sb, "place", raid.place).append(",\"since\":").append(raid.started).append(",\"bossLevel\":").append(raid.bossLevel);
		sb.append(",\"leader\":").append(raid.leaderId == player.getObjectId()).append(",\"superChampion\":").append(raid.superChampion).append(",\"epic\":").append(raid.epic).append('}');
		return true;
	}

	public String raidOf(Player player)
	{
		final Raid raid = _raid;
		return (raid != null) && (raid.leaderId == player.getObjectId()) ? raid.bossName + " (" + raid.bossLevel + ")" : null;
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
