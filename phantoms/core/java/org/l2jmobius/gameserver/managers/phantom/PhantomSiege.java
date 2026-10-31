package org.l2jmobius.gameserver.managers.phantom;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Artefact;
import org.l2jmobius.gameserver.entity.actor.instance.Defender;
import org.l2jmobius.gameserver.entity.actor.instance.Door;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.SiegeManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.gameserver.mechanics.siege.Siege;
import org.l2jmobius.gameserver.mechanics.siege.SiegeClan;
import org.l2jmobius.gameserver.mechanics.skill.CommonSkill;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: осады замков кланами охотников (docs/PHANTOMS.md, раздел 4.74; docs/LIVE_SERVER.md, п. 7; ключ {@code HunterSieges}). Осада настоящая: расписание, запись, ворота, стражу,
 * печать и смену владельца ведёт Mobius, модуль только записывает кланы охотников и водит их.
 * <ul>
 * <li>Запись: пока регистрация открыта и до осады не больше {@code HunterSiegeRegisterHours}, сильный клан охотников записывается в нападающие обычным вызовом Mobius, так что его правила
 * (уровень клана, свой замок, уже записан) работают как для игрока. На замок — не больше {@code HunterSiegeAttackers} кланов охотников.</li>
 * <li>Бой: охотник из клана на идущей осаде (и из клана игрока, если тот записан) уходит из правил кача. Нападающие идут к внешним воротам, потом к внутренним, потом к артефакту; глава
 * клана-охотника кастует печать. Защитники стоят у артефакта. Противник рядом важнее ворот.</li>
 * <li>Гарнизон: у замка без владельца сотни стражников рассчитаны на сервер в тысячу человек. Если нападают охотники, остаётся {@code HunterSiegeGarrison} % стражи.</li>
 * <li>Убитый поднимается через {@value #RESPAWN} мс у своей точки: как игрок, что пришёл из города.</li>
 * <li>Начало, взятие и итог — в летописи.</li>
 * </ul>
 */
public final class PhantomSiege
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSiege.class.getName());

	private static final long TICK = 30000;
	/** Шанс записаться за тик: кланы не записываются все разом. */
	private static final int REGISTER_CHANCE = 10;
	/** Младше этого уровня на осаду не ходит: качается дальше. */
	private static final int MIN_FIGHTER_LEVEL = 20;
	private static final long RESPAWN = 20000;
	/** Вражеский игрок ближе этого важнее ворот. */
	private static final int ENGAGE_RANGE = 900;
	/** Стражник ближе этого мешает: бить его, иначе идти дальше. */
	private static final int GUARD_RANGE = 300;
	/** От центра ворот наружу до точки сбора. */
	private static final int RALLY_OUT = 500;
	/** К воротам подходит на это расстояние, дальше ведёт атака AI. */
	private static final int GATE_RANGE = 250;
	/** У печати castRange 85, сам каст — 3 минуты. */
	private static final int SEAL_RANGE = 80;
	private static final int ARTEFACT_CROWD = 450;
	/** В таком радиусе от артефакта поредевший гарнизон не стоит: печать идёт 3 минуты, и удар её сбивает. */
	private static final int SEAL_ZONE = 1000;
	/** Дальше этого от точки — телепорт: до замка ногами не идут. */
	private static final int FAR = 6000;
	/** Столько не сдвинулся — короткий телепорт к цели: застрял. */
	private static final long STUCK = 30000;
	/** Столько бьёт цель, а здоровье у неё не падает: её не достать, и столько же она его больше не держит. */
	private static final long FIGHT_STALL = 15000;
	private static final long FIGHT_IGNORE = 30000;
	/** Столько стоит с намерением каста, не кастуя и не идя: цель пропала, а намерение осталось. */
	private static final long CAST_HANG = 5000;
	/** Ниже этого MP хилер не лечит: {@code PhantomSupport} посадил бы его отдыхать, а на осаде он встаёт и садится по кругу. */
	private static final int HEALER_MIN_MP = 30;
	private static final long REPORT = 60000;

	/** Идущие осады по id замка. */
	private final Map<Integer, Battle> _battles = new ConcurrentHashMap<>();
	private final Map<Integer, Fighter> _fighters = new ConcurrentHashMap<>();

	private static final class Battle
	{
		final Castle castle;
		final Siege siege;
		final long started;
		final List<Door> outer = new ArrayList<>();
		final List<Door> inner = new ArrayList<>();
		final Artefact artefact;
		final Location rally;
		int owner;
		long reportAt;

		Battle(Castle castle, Siege siege, Artefact artefact, Location rally, long now)
		{
			this.castle = castle;
			this.siege = siege;
			this.artefact = artefact;
			this.rally = rally;
			started = now;
			owner = castle.getOwnerId();
			reportAt = now + REPORT;
		}
	}

	private static final class Fighter
	{
		long deadSince;
		long movedAt;
		double lastDistance;
		int deaths;
		/** Своё место у артефакта: случайная точка на каждый тик не даёт заметить, что боец застрял. */
		Location post;
		int enemyId;
		double enemyHp;
		long enemySince;
		long ignoreUntil;
		long castHangSince;
	}

	void start()
	{
		ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
	}

	private void tick()
	{
		if (!PhantomHuntersConfig.HUNTER_SIEGES)
		{
			return;
		}
		try
		{
			final long now = System.currentTimeMillis();
			for (Castle castle : CastleManager.getInstance().getCastles())
			{
				final Siege siege = castle.getSiege();
				if (siege == null)
				{
					continue;
				}
				final Battle battle = _battles.get(castle.getResidenceId());
				if (siege.isInProgress())
				{
					watch(battle != null ? battle : begin(castle, siege, now), now);
				}
				else
				{
					if (battle != null)
					{
						finish(battle);
					}
					register(castle, siege, now);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e, e);
		}
	}

	// ---------- Запись ----------

	private void register(Castle castle, Siege siege, long now)
	{
		if (siege.isRegistrationOver() || ((siege.getSiegeDate().getTimeInMillis() - now) > (PhantomHuntersConfig.HUNTER_SIEGE_REGISTER_HOURS * 3600000L)) || (Rnd.get(100) >= REGISTER_CHANCE))
		{
			return;
		}

		int ours = 0;
		for (SiegeClan attacker : siege.getAttackerClans())
		{
			final Clan clan = ClanTable.getInstance().getClan(attacker.getClanId());
			ours += (clan != null) && PhantomHunterClans.isHunterClan(clan) ? 1 : 0;
		}
		if (ours >= PhantomHuntersConfig.HUNTER_SIEGE_ATTACKERS)
		{
			return;
		}

		final List<Clan> clans = new ArrayList<>(ClanTable.getInstance().getClans());
		Collections.shuffle(clans);
		for (Clan clan : clans)
		{
			final Player leader = strongLeader(clan);
			if ((leader == null) || (clan.getCastleId() > 0) || SiegeManager.getInstance().checkIsRegistered(clan, castle.getResidenceId()) || siege.checkIfAlreadyRegisteredForSameDay(clan))
			{
				continue;
			}
			siege.registerAttacker(leader);
			if (siege.getAttackerClan(clan) != null)
			{
				final String when = new SimpleDateFormat("dd.MM HH:mm").format(siege.getSiegeDate().getTime());
				LOGGER.info(getClass().getSimpleName() + ": " + clan.getName() + " (" + clan.getLevel() + ", leader " + leader.getName() + ") registers for the siege of " + castle.getName() + " on " + when + ".");
				PhantomNews.post("siege_register", en() ? "Clan " + clan.getName() + " will attack " + castle.getName() + " Castle on " + when + "." : "Клан " + clan.getName() + " идёт на замок " + castle.getName() + ": осада " + when + ".", leader.getName(), null);
				return;
			}
		}
	}

	/** Глава-охотник сильного клана: осада ему по силам, и есть с кем идти. */
	private static Player strongLeader(Clan clan)
	{
		if (!PhantomHunterClans.isHunterClan(clan) || (clan.getLevel() < SiegeManager.getInstance().getSiegeClanMinLevel()))
		{
			return null;
		}
		final Player leader = clan.getLeader() == null ? null : clan.getLeader().getPlayer();
		if ((leader == null) || !leader.isOnline() || !PhantomManager.getInstance().isPhantom(leader) || (leader.getLevel() < PhantomHuntersConfig.HUNTER_SIEGE_MIN_LEVEL))
		{
			return null;
		}
		int fighters = 0;
		for (Player member : clan.getOnlineMembers(0))
		{
			fighters += member.getLevel() >= MIN_FIGHTER_LEVEL ? 1 : 0;
		}
		return fighters >= PhantomHuntersConfig.HUNTER_SIEGE_MIN_MEMBERS ? leader : null;
	}

	// ---------- Ход осады ----------

	private Battle begin(Castle castle, Siege siege, long now)
	{
		final Artefact artefact = castle.getArtefacts().isEmpty() ? null : castle.getArtefacts().iterator().next();
		final List<Door> outer = new ArrayList<>();
		final List<Door> inner = new ArrayList<>();
		for (Door door : castle.getDoors())
		{
			if (door.isWall())
			{
				continue;
			}
			// Не door.getName(): в CT он от WorldObject и пуст, имя есть только в шаблоне.
			final String name = door.getTemplate().getName().toLowerCase();
			if (name.contains("out"))
			{
				outer.add(door);
			}
			else if (name.contains("inner"))
			{
				inner.add(door);
			}
		}
		// У другой хроники ворота могут зваться иначе: тогда внешние — самые дальние от артефакта.
		if (outer.isEmpty() && (artefact != null))
		{
			final List<Door> doors = new ArrayList<>(castle.getDoors());
			doors.removeIf(Door::isWall);
			doors.sort(Comparator.comparingDouble((Door door) -> -door.calculateDistance2D(artefact)));
			outer.addAll(doors.subList(0, Math.min(2, doors.size())));
		}

		final Battle battle = new Battle(castle, siege, artefact, rally(outer, artefact), now);
		battle.outer.addAll(outer);
		battle.inner.addAll(inner);
		_battles.put(castle.getResidenceId(), battle);

		thinGarrison(castle, siege, artefact);

		final List<String> attackers = names(siege.getAttackerClans());
		final Clan owner = ClanTable.getInstance().getClan(castle.getOwnerId());
		LOGGER.info(getClass().getSimpleName() + ": siege of " + castle.getName() + " is on: attackers " + attackers + ", owner " + (owner == null ? "none" : owner.getName()) + ", gates " + outer.size() + "+" + inner.size() + ", rally " + battle.rally + ".");
		final String ownerName = owner == null ? (en() ? "the guards" : "стража") : owner.getName();
		PhantomNews.post("siege_start", en() ? "The siege of " + castle.getName() + " has begun: " + String.join(", ", attackers) + " against " + ownerName + "." : "Началась осада замка " + castle.getName() + ": " + String.join(", ", attackers) + " против: " + ownerName + ".", null, null);
		return battle;
	}

	/**
	 * В замке без владельца сотни стражников 56–60-го уровня: на сервер в тысячу человек, а не на сотню охотников. Лишних убираем, как только начался бой; наёмников замка с владельцем не трогаем.
	 * Убранных не возрождаем и оставшихся тоже: двор зачищается за конечное время, у печати стражи нет, иначе трёхминутный каст не дойдёт до конца.
	 */
	private void thinGarrison(Castle castle, Siege siege, Artefact artefact)
	{
		if ((castle.getOwnerId() > 0) || (PhantomHuntersConfig.HUNTER_SIEGE_GARRISON >= 100))
		{
			return;
		}
		boolean hunters = false;
		for (SiegeClan attacker : siege.getAttackerClans())
		{
			final Clan clan = ClanTable.getInstance().getClan(attacker.getClanId());
			hunters |= (clan != null) && PhantomHunterClans.isHunterClan(clan);
		}
		if (!hunters)
		{
			return;
		}

		final List<Spawn> guards = new ArrayList<>(PhantomCompat.siegeGuards(castle));
		Collections.shuffle(guards);
		final int keep = (guards.size() * PhantomHuntersConfig.HUNTER_SIEGE_GARRISON) / 100;
		int stay = 0;
		for (Spawn spawn : guards)
		{
			spawn.stopRespawn();
			final Npc guard = spawn.getLastSpawn();
			if (guard == null)
			{
				continue;
			}
			if ((stay < keep) && ((artefact == null) || (guard.calculateDistance2D(artefact) >= SEAL_ZONE)))
			{
				stay++;
			}
			else
			{
				guard.deleteMe();
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": garrison of " + castle.getName() + ": " + stay + " of " + guards.size() + " guards stay.");
	}

	private static List<String> names(Iterable<SiegeClan> clans)
	{
		final List<String> out = new ArrayList<>();
		for (SiegeClan siegeClan : clans)
		{
			final Clan clan = ClanTable.getInstance().getClan(siegeClan.getClanId());
			if (clan != null)
			{
				out.add(clan.getName());
			}
		}
		return out;
	}

	/** Точка сбора — снаружи внешних ворот, на линии от артефакта через ворота. */
	private static Location rally(List<Door> outer, Artefact artefact)
	{
		if (outer.isEmpty())
		{
			return artefact == null ? null : new Location(artefact.getX(), artefact.getY(), artefact.getZ());
		}
		long sumX = 0;
		long sumY = 0;
		for (Door door : outer)
		{
			sumX += door.getX();
			sumY += door.getY();
		}
		final int gateX = (int) (sumX / outer.size());
		final int gateY = (int) (sumY / outer.size());
		final int gateZ = outer.get(0).getZ();
		double dx = 0;
		double dy = 1;
		if (artefact != null)
		{
			final double length = Math.max(1, Math.hypot(gateX - artefact.getX(), gateY - artefact.getY()));
			dx = (gateX - artefact.getX()) / length;
			dy = (gateY - artefact.getY()) / length;
		}
		final int x = gateX + (int) (dx * RALLY_OUT);
		final int y = gateY + (int) (dy * RALLY_OUT);
		return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, gateZ));
	}

	/** Смена владельца — в летопись. С единственным нападающим Mobius кончает осаду тут же, поэтому зовёт и {@link #finish}. */
	private void noteOwner(Battle battle, long now)
	{
		final int owner = battle.castle.getOwnerId();
		if (owner == battle.owner)
		{
			return;
		}
		battle.owner = owner;
		final Clan clan = ClanTable.getInstance().getClan(owner);
		if (clan != null)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + clan.getName() + " takes " + battle.castle.getName() + " after " + ((now - battle.started) / 60000) + " min.");
			PhantomNews.post("siege_taken", en() ? "Clan " + clan.getName() + " took the seal of " + battle.castle.getName() + " Castle!" : "Клан " + clan.getName() + " взял печать замка " + battle.castle.getName() + "!", clan.getName(), null);
		}
	}

	/** Ход боя: взятие — в летопись, остальное — в лог. */
	private void watch(Battle battle, long now)
	{
		noteOwner(battle, now);
		if (now >= battle.reportAt)
		{
			battle.reportAt = now + REPORT;
			LOGGER.info(getClass().getSimpleName() + ": " + battle.castle.getName() + ": " + state(battle) + ".");
		}
	}

	/** Ход боя строкой: для лога и самопроверки. */
	private String state(Battle battle)
	{
		int near = 0;
		int dead = 0;
		for (Player player : World.getPlayers())
		{
			if ((battleOf(player) == battle))
			{
				near += player.calculateDistance2D(battle.rally) <= (FAR / 2) ? 1 : 0;
				dead += player.isDead() ? 1 : 0;
			}
		}
		return "fighters near " + near + ", dead " + dead + ", outer " + doors(battle.outer) + ", inner " + doors(battle.inner) + ", owner " + battle.castle.getOwnerId();
	}

	private static String doors(List<Door> doors)
	{
		final List<String> out = new ArrayList<>();
		for (Door door : doors)
		{
			out.add(door.isDead() ? "open" : ((int) ((door.getCurrentHp() * 100) / door.getMaxHp())) + "%");
		}
		return out.toString();
	}

	private void finish(Battle battle)
	{
		noteOwner(battle, System.currentTimeMillis());
		_battles.remove(battle.castle.getResidenceId());
		final Clan clan = ClanTable.getInstance().getClan(battle.castle.getOwnerId());
		final String name = clan == null ? (en() ? "the guards" : "стражи") : clan.getName();
		LOGGER.info(getClass().getSimpleName() + ": siege of " + battle.castle.getName() + " is over, the castle belongs to " + (clan == null ? "nobody" : clan.getName()) + ".");
		PhantomNews.post("siege_end", en() ? "The siege of " + battle.castle.getName() + " is over: the castle stays with " + name + "." : "Осада замка " + battle.castle.getName() + " кончилась: замок за кланом " + name + ".", clan == null ? null : clan.getName(), null);
	}

	private Battle battleOf(Player player)
	{
		final Clan clan = player.getClan();
		if ((clan == null) || _battles.isEmpty())
		{
			return null;
		}
		for (Battle battle : _battles.values())
		{
			if (battle.siege.isInProgress() && (battle.siege.checkIsAttacker(clan) || battle.siege.checkIsDefender(clan)))
			{
				return battle;
			}
		}
		return null;
	}

	/**
	 * @param player охотник
	 * @return его клан на идущей осаде, и охотник на неё пойдёт: другие правила кача его не трогают
	 */
	boolean inBattle(Player player)
	{
		return PhantomHuntersConfig.HUNTER_SIEGES && (player.getLevel() >= MIN_FIGHTER_LEVEL) && !player.isInStoreMode() && (battleOf(player) != null);
	}

	// ---------- Охотник на осаде ----------

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}).
	 * @param player охотник
	 * @param template его шаблон
	 * @param now текущее время
	 * @return что с ним делать
	 */
	PhantomEvents.Drive drive(Player player, PhantomTemplate template, long now)
	{
		if (!inBattle(player) || player.isOnEvent() || player.isInOlympiadMode() || (PhantomRaid.getInstance().raidOf(player) != null))
		{
			return _fighters.remove(player.getObjectId()) != null ? PhantomEvents.Drive.DONE : PhantomEvents.Drive.NONE;
		}

		final Battle battle = battleOf(player);
		if ((battle == null) || (battle.rally == null))
		{
			return PhantomEvents.Drive.NONE;
		}
		final Fighter fighter = _fighters.computeIfAbsent(player.getObjectId(), id -> new Fighter());
		final boolean attacker = battle.siege.checkIsAttacker(player.getClan());
		final Location home = attacker || (battle.artefact == null) ? battle.rally : post(fighter, battle.artefact);

		if (player.isDead())
		{
			if (fighter.deadSince == 0)
			{
				fighter.deadSince = now;
				fighter.deaths++;
			}
			else if ((now - fighter.deadSince) >= RESPAWN)
			{
				fighter.deadSince = 0;
				player.doRevive();
				player.setCurrentHp(player.getMaxHp());
				player.setCurrentMp(player.getMaxMp());
				player.teleToLocation(home, true);
				enlist(player, battle, attacker);
			}
			return PhantomEvents.Drive.FIGHTS;
		}
		fighter.deadSince = 0;
		if (player.isTeleporting() || player.isImmobilized() || player.isCastingNow())
		{
			fighter.castHangSince = 0;
			return PhantomEvents.Drive.FIGHTS;
		}
		releaseHungCast(player, fighter, now);
		if (player.isSitting())
		{
			player.standUp();
			return PhantomEvents.Drive.FIGHTS;
		}
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		}
		enlist(player, battle, attacker);

		// Издалека — телепорт: до замка ногами не идут.
		if (player.calculateDistance2D(home) > FAR)
		{
			player.getAI().setIntentionActive();
			player.teleToLocation(home, true);
			fighter.movedAt = now;
			return PhantomEvents.Drive.FIGHTS;
		}

		// Ворота открыты: глава клана бежит к печати, стражник его не держит, игрок другой стороны — держит.
		final Door gate = attacker ? gate(player, battle) : null;
		final boolean leader = attacker && (gate == null) && (battle.artefact != null) && player.isClanLeader() && (battle.castle.getOwnerId() != player.getClanId());
		// Глава-хилер уже идёт к печати: это важнее лечения.
		if (!leader && (template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && (player.getCurrentMp() >= ((player.getMaxMp() * HEALER_MIN_MP) / 100)) && PhantomSupport.act(player, player, template.getRole(), now))
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		final Creature enemy = now < fighter.ignoreUntil ? null : enemy(player, battle, attacker, !leader);
		if ((enemy != null) && !stalled(fighter, enemy, now))
		{
			PhantomCompanionManager.fight(player, enemy);
			fighter.movedAt = now;
			return PhantomEvents.Drive.FIGHTS;
		}
		if (!attacker)
		{
			go(player, fighter, home, ARTEFACT_CROWD, now);
			return PhantomEvents.Drive.FIGHTS;
		}

		if (gate != null)
		{
			final Location at = new Location(gate.getX(), gate.getY(), player.getZ());
			if (go(player, fighter, at, GATE_RANGE, now))
			{
				PhantomCompanionManager.fight(player, gate);
				fighter.movedAt = now;
			}
			return PhantomEvents.Drive.FIGHTS;
		}
		if (battle.artefact == null)
		{
			return PhantomEvents.Drive.FIGHTS;
		}

		// Ворота открыты. Глава клана кастует печать, остальные прикрывают.
		final Location at = leader ? new Location(battle.artefact.getX(), battle.artefact.getY(), battle.artefact.getZ()) : post(fighter, battle.artefact);
		if (go(player, fighter, at, leader ? SEAL_RANGE : (ARTEFACT_CROWD / 2), now) && leader)
		{
			seal(player, battle);
		}
		return PhantomEvents.Drive.FIGHTS;
	}

	/** Намерение каста без каста: цель убита или пропала. Его не снимет никто: {@code PhantomSupport} ждёт только свои касты, и боец стоит столбом. */
	private static void releaseHungCast(Player player, Fighter fighter, long now)
	{
		if ((player.getAI().getIntention() != Intention.CAST) || player.isMoving())
		{
			fighter.castHangSince = 0;
		}
		else if (fighter.castHangSince == 0)
		{
			fighter.castHangSince = now;
		}
		else if ((now - fighter.castHangSince) > CAST_HANG)
		{
			fighter.castHangSince = 0;
			player.getAI().setIntentionActive();
		}
	}

	/** Сторона осады у того, кто вошёл в мир уже после начала: Mobius ставит её только тем, кто был в сети в начале. */
	private static void enlist(Player player, Battle battle, boolean attacker)
	{
		if (player.getSiegeState() == 0)
		{
			player.setSiegeState((byte) (attacker ? 1 : 2));
			player.setSiegeSide(battle.castle.getResidenceId());
		}
	}

	private static Location post(Fighter fighter, Artefact artefact)
	{
		if (fighter.post == null)
		{
			fighter.post = around(artefact, ARTEFACT_CROWD);
		}
		return fighter.post;
	}

	private static Location around(Artefact artefact, int range)
	{
		final double angle = Rnd.nextDouble() * 2 * Math.PI;
		final int x = artefact.getX() + (int) (Math.cos(angle) * (range / 2));
		final int y = artefact.getY() + (int) (Math.sin(angle) * (range / 2));
		return new Location(x, y, GeoEngine.getInstance().getHeight(x, y, artefact.getZ()));
	}

	/**
	 * Идёт к точке; застрял — короткий телепорт.
	 * @return уже на месте
	 */
	private static boolean go(Player player, Fighter fighter, Location to, int range, long now)
	{
		final double distance = player.calculateDistance2D(to);
		if (distance <= range)
		{
			fighter.movedAt = now;
			return true;
		}
		if ((fighter.movedAt == 0) || (Math.abs(distance - fighter.lastDistance) > 30))
		{
			fighter.movedAt = now;
			fighter.lastDistance = distance;
		}
		else if ((now - fighter.movedAt) > STUCK)
		{
			fighter.movedAt = now;
			player.getAI().setIntentionActive();
			player.teleToLocation(to, true);
			return false;
		}
		if (!player.isMoving())
		{
			player.setRunning();
			player.getAI().setIntentionMoveTo(to);
		}
		return false;
	}

	/** Бой без урона: цель, которую не достать, иначе держала бы бойца до конца осады. */
	private static boolean stalled(Fighter fighter, Creature enemy, long now)
	{
		if ((fighter.enemyId != enemy.getObjectId()) || (enemy.getCurrentHp() < fighter.enemyHp))
		{
			fighter.enemyId = enemy.getObjectId();
			fighter.enemySince = now;
		}
		fighter.enemyHp = enemy.getCurrentHp();
		if ((now - fighter.enemySince) <= FIGHT_STALL)
		{
			return false;
		}
		fighter.enemyId = 0;
		fighter.ignoreUntil = now + FIGHT_IGNORE;
		return true;
	}

	/** Ближайший враг в прямой видимости (за закрытыми воротами и на стене — не враг): игрок другой стороны, а нападающему ещё и стражник вплотную. */
	private static Creature enemy(Player player, Battle battle, boolean attacker, boolean guards)
	{
		Creature best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Player other : World.getVisibleObjectsInRange(player, Player.class, ENGAGE_RANGE))
		{
			final Clan clan = other.getClan();
			if ((clan == null) || other.isDead() || other.isInvisible() || !(attacker ? battle.siege.checkIsDefender(clan) : battle.siege.checkIsAttacker(clan)) || !GeoEngine.getInstance().canSeeTarget(player, other))
			{
				continue;
			}
			final double distance = player.calculateDistance2D(other);
			if (distance < bestDistance)
			{
				best = other;
				bestDistance = distance;
			}
		}
		if ((best != null) || !attacker || !guards)
		{
			return best;
		}
		for (Defender guard : World.getVisibleObjectsInRange(player, Defender.class, GUARD_RANGE))
		{
			if (guard.isDead() || !guard.isAutoAttackable(player) || !GeoEngine.getInstance().canSeeTarget(player, guard))
			{
				continue;
			}
			final double distance = player.calculateDistance2D(guard);
			if (distance < bestDistance)
			{
				best = guard;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Что ломать: пока целы обе створки внешних ворот — ближняя из них, потом так же внутренние. Одна створка лежит — проход есть. */
	private static Door gate(Player player, Battle battle)
	{
		final Door outer = closed(player, battle.outer);
		return outer != null ? outer : closed(player, battle.inner);
	}

	private static Door closed(Player player, List<Door> doors)
	{
		Door best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Door door : doors)
		{
			if (door.isDead() || door.isOpen())
			{
				return null;
			}
			final double distance = player.calculateDistance2D(door);
			if (distance < bestDistance)
			{
				best = door;
				bestDistance = distance;
			}
		}
		return best;
	}

	private void seal(Player player, Battle battle)
	{
		final Skill skill = CommonSkill.SEAL_OF_RULER.getSkill();
		player.setTarget(battle.artefact);
		if (PhantomCompat.useMagic(player, skill, false, true))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " casts the seal on " + battle.castle.getName() + ".");
		}
		else
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " could not cast the seal on " + battle.castle.getName() + " (distance " + (int) player.calculateDistance2D(battle.artefact) + ", z " + player.getZ() + "/" + battle.artefact.getZ() + ").");
		}
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка SIEGE ({@code HuntersSelfTest}, только на копии базы): дюжина свободных охотников, поднятых до {@value #TEST_LEVEL}-го, основывает клан, записывается на замок без владельца
	 * и начинает осаду сразу. Ворота ослаблены до {@value #TEST_GATE_PERCENT} %, иначе в окно прогона не уложиться. Итог: смена владельца, конец осады, клан распущен.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		ThreadPool.execute(() ->
		{
			try
			{
				runTest(hunters);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": SelfTest SIEGE: failed: " + e, e);
			}
		});
	}

	private static final int TEST_LEVEL = 70;
	private static final int TEST_GATE_PERCENT = 1;
	private static final int TEST_MEMBERS = 12;
	private static final long TEST_WINDOW = 660000;

	private void runTest(List<Player> hunters) throws InterruptedException
	{
		final String tag = getClass().getSimpleName() + ": SelfTest SIEGE: ";
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() == null) && !hunter.isDead() && !hunter.isInStoreMode() && !hunter.isInParty() && !PhantomHunterClans.getInstance().isLoner(hunter))
			{
				free.add(hunter);
			}
		}
		free.sort(Comparator.comparingInt((Player hunter) -> -hunter.getLevel()));
		if (free.size() < 6)
		{
			LOGGER.warning(tag + "needs six free hunters, found " + free.size() + ".");
			return;
		}
		final List<Player> team = free.subList(0, Math.min(TEST_MEMBERS, free.size()));
		for (Player hunter : team)
		{
			if (hunter.getLevel() < TEST_LEVEL)
			{
				hunter.addExpAndSp(ExperienceData.getInstance().getExpForLevel(TEST_LEVEL) - hunter.getExp(), 0);
			}
			// Поднятый уровнем шмот не меняет: надеть лучший по уровню, как у охотника, что дорос сам.
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			if (template != null)
			{
				PhantomTemplate.Gear best = null;
				for (PhantomTemplate.Gear gear : template.getAllGear())
				{
					if ((gear.minLevel <= hunter.getLevel()) && ((best == null) || (gear.minLevel > best.minLevel)))
					{
						best = gear;
					}
				}
				PhantomOutfit.dress(hunter, best);
			}
		}
		Thread.sleep(3000);

		final Player leader = team.get(0);
		String name = "L2HubSiegeTest";
		for (int i = 2; ClanTable.getInstance().getClanByName(name) != null; i++)
		{
			name = "L2HubSiegeTest" + i;
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, name);
		if (clan == null)
		{
			LOGGER.warning(tag + leader.getName() + " could not create clan " + name + ", stop.");
			return;
		}
		clan.changeLevel(5);
		for (Player hunter : team)
		{
			if (hunter != leader)
			{
				PhantomCompat.joinClan(leader, hunter, 0);
			}
		}

		Castle castle = null;
		for (Castle candidate : CastleManager.getInstance().getCastles())
		{
			if ((candidate.getOwnerId() == 0) && !candidate.getSiege().isInProgress() && !candidate.getArtefacts().isEmpty() && ((castle == null) || (candidate.getResidenceId() < castle.getResidenceId())))
			{
				castle = candidate;
			}
		}
		if (castle == null)
		{
			LOGGER.warning(tag + "no free castle, stop.");
			return;
		}
		final boolean enabled = PhantomHuntersConfig.HUNTER_SIEGES;
		PhantomHuntersConfig.HUNTER_SIEGES = true;
		final Siege siege = castle.getSiege();
		LOGGER.info(tag + clan.getName() + " (" + clan.getLevel() + "), " + clan.getMembersCount() + " members, leader " + leader.getName() + " (" + leader.getLevel() + "), castle " + castle.getName() + ", key was " + enabled + ", siege date " + siege.getSiegeDate().getTime() + ".");
		LOGGER.info(tag + "too weak to register: " + (strongLeader(clan) == null) + " (leader " + leader.getLevel() + ", key level " + PhantomHuntersConfig.HUNTER_SIEGE_MIN_LEVEL + ").");
		siege.registerAttacker(leader, true);
		LOGGER.info(tag + "registered " + (siege.getAttackerClan(clan) != null) + ".");
		siege.startSiege();
		LOGGER.info(tag + "started " + siege.isInProgress() + ".");
		final long started = System.currentTimeMillis();
		final Battle battle = begin(castle, siege, started);
		for (Door door : castle.getDoors())
		{
			if (!door.isWall())
			{
				door.setCurrentHp((door.getMaxHp() * TEST_GATE_PERCENT) / 100);
			}
		}

		boolean taken = false;
		int ticks = 0;
		while ((System.currentTimeMillis() - started) < TEST_WINDOW)
		{
			Thread.sleep(15000);
			if ((++ticks % 4) == 0)
			{
				final StringBuilder sb = new StringBuilder();
				for (Player hunter : team)
				{
					sb.append(hunter.isDead() ? "dead" : hunter.getAI().getIntention()).append(' ').append(battle.artefact == null ? -1 : (int) hunter.calculateDistance2D(battle.artefact)).append(' ').append(hunter.getTarget() == null ? "-" : hunter.getTarget().getClass().getSimpleName()).append("; ");
				}
				LOGGER.info(tag + "team: " + sb);
			}
			LOGGER.info(tag + "after " + ((System.currentTimeMillis() - started) / 1000) + " s: " + state(battle) + ", leader " + (leader.isDead() ? "dead" : leader.isCastingNow() ? "casting" : "alive") + " at " + (battle.artefact == null ? -1 : (int) leader.calculateDistance2D(battle.artefact)) + " from the artefact, target " + (leader.getTarget() == null ? "none" : leader.getTarget().getClass().getSimpleName()) + ", " + leader.getAI().getIntention() + ".");
			if (castle.getOwnerId() == clan.getId())
			{
				taken = true;
				break;
			}
		}
		LOGGER.info(tag + (taken ? "TAKEN by " + clan.getName() + " after " + ((System.currentTimeMillis() - started) / 1000) + " s." : "NOT taken in the window."));
		int deaths = 0;
		for (Player hunter : team)
		{
			final Fighter fighter = _fighters.get(hunter.getObjectId());
			deaths += fighter == null ? 0 : fighter.deaths;
		}
		LOGGER.info(tag + "deaths " + deaths + ", defender now " + siege.checkIsDefender(clan) + ", attacker " + siege.checkIsAttacker(clan) + ".");
		Thread.sleep(20000);

		siege.endSiege();
		Thread.sleep(35000);
		LOGGER.info(tag + "ended: in progress " + siege.isInProgress() + ", owner " + castle.getOwnerId() + ", leader in battle " + inBattle(leader) + ", battles left " + _battles.size() + ".");
		if (taken)
		{
			castle.removeOwner(clan);
		}
		ClanTable.getInstance().destroyClan(clan.getId());
		PhantomHuntersConfig.HUNTER_SIEGES = enabled;
		LOGGER.info(tag + "done, clan dissolved.");
	}

	public static PhantomSiege getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomSiege INSTANCE = new PhantomSiege();
	}
}
