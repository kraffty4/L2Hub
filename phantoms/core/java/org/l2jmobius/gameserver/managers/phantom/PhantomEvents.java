package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Team;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * L2Hub: охотники на PvP-ивентах Mobius — TvT, Deathmatch, CtF (custom/events; docs/PHANTOMS.md, раздел 4.33; {@code HunterPvpEvents} в PhantomHunters.ini).<br>
 * Ивент запускает расписание (у Interlude — c4-interlude, {@code L2HubEvents.ini}: {@code PvpEvents}, крон, {@code PvpMinLevel}) или GM; без охотников одиночке
 * не набрать участников (от 4), и Mobius отменяет ивент сам. Здесь:
 * <ul>
 * <li>запись — пока в мире стоит менеджер ивента (70010 TvT, 70011 Deathmatch, 70012 CtF), охотники на каче (не в пати) записываются тем же путём, что живой игрок
 * у менеджера ({@code notifyEvent("Participate")}, проверки скрипта — уровень, карма, сумка); не больше {@code HunterPvpEventMax}, шанс — {@code HunterPvpEventChance}
 * × вспыльчивость (драчуны идут охотнее). Записанный не уходит в город и в лавку — ждёт телепорта на арену;</li>
 * <li>бой на арене ({@code isOnEvent}): ближайший противник — чужая команда (в Deathmatch команд нет — любой участник), враги по отношениям первыми, если не намного
 * дальше; бой напарника ({@link PhantomCompanionManager#fight}: скиллы, маг отмахивается); хилер сначала лечит своих (пати команды, её собирает ивент). Убитый лежит:
 * ивент сам поднимает у своих через 10 с — в город охотник не уходит;</li>
 * <li>ивент кончился ({@code isOnEvent} снялся) — назад на кач, в логе итог: сколько убил и сколько раз лёг.</li>
 * </ul>
 * Каждое убийство на арене, где убит охотник, — строка лога (счёт Mobius объявляет только в игре, а {@code pvpkills} на ивенте не растут).
 * CtF ({@link #flag}): у команды один бегун — бежит к штабу противника и берёт флаг разговором со штабом, как живой игрок, несёт к своему штабу
 * и сдаёт; остальные дерутся, и первым — с тем, кто несёт их флаг.
 */
public final class PhantomEvents
{
	private static final Logger LOGGER = Logger.getLogger(PhantomEvents.class.getName());

	/** Менеджер ивента → имя скрипта (custom/events). */
	private static final Map<Integer, String> MANAGERS = Map.of(70010, "TvT", 70011, "Deathmatch", 70012, "CtF");
	/** Противник дальше этого — бежать к нему, ближе — бить (атака AI работает в пределах видимости). */
	private static final int ENGAGE_RANGE = 1200;
	/** Враг по отношениям годится, если не дальше ближайшего противника больше чем на столько. */
	private static final int GRUDGE_EXTRA = 600;
	/**
	 * CtF: штабы команд — НПЦ с флагом на арене, у ворот своей базы, за краем её мирной зоны; места — как в {@code CtF.java} у всех пяти хроник.
	 * Издалека штаб не найти: видимость Mobius — соседние регионы по 2048, а от базы до чужого штаба около 3000.
	 */
	private static final int BLUE_HQ = 82016;
	private static final int RED_HQ = 82017;
	private static final Location BLUE_HQ_LOC = new Location(148487, 46703, -3414);
	private static final Location RED_HQ_LOC = new Location(150452, 46714, -3414);
	/** CtF: флаг в руке носильщика — 13531 синий и 13534 красный, у Essence — 93331 и 93332. */
	private static final Set<Integer> CTF_FLAGS = Set.of(13531, 13534, 93331, 93332);
	/** Штаб ближе этого — говорить с ним: скрипт CtF берёт и сдаёт флаг с 70. */
	private static final int HQ_TALK_RANGE = 60;

	/** Что делает охотник на этом тике ({@link #drive}). */
	public enum Drive
	{
		/** Не на ивенте — правила кача (записанный только не уходит в город). */
		NONE,
		/** На арене — правила кача не трогать. */
		FIGHTS,
		/** Ивент кончился — назад на кач. */
		DONE
	}

	/** Охотник на ивенте: какой ивент, его счёт и слушатель смерти, пока он участник. */
	private static class Score
	{
		final String event;
		final long since;
		ConsumerEventListener listener;
		int kills;
		int deaths;

		Score(String event, long since)
		{
			this.event = event;
			this.since = since;
		}
	}

	/** Кто из охотников сейчас на арене или записан (objectId → счёт) — для возврата и лога. */
	private final Map<Integer, Score> _participants = new ConcurrentHashMap<>();
	private long _lastScan;
	/** Строка лога о том, что охотник делает на арене, — не чаще раза в {@value #NOTE_GAP} мс на охотника. */
	private static final long NOTE_GAP = 30000;
	private final Map<Integer, Long> _lastNote = new ConcurrentHashMap<>();
	private Npc _manager;
	/** CtF: бегун за флагом у команды (команда → objectId охотника) — один, пока он на ивенте. */
	private final Map<Team, Integer> _runners = new ConcurrentHashMap<>();

	private PhantomEvents()
	{
	}

	/**
	 * Раз в 10 с из {@code PhantomHunterManager.control}: открыта регистрация — записать охотников.
	 * @param hunting охотники на каче, не в пати, живы
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PVP_EVENTS)
		{
			return;
		}
		final Npc manager = manager(now);
		final String name = manager == null ? null : MANAGERS.get(manager.getId());
		final Quest script = name == null ? null : ScriptManager.getInstance().getScript(name);
		if (script == null)
		{
			return;
		}

		int registered = 0;
		for (Player player : World.getPlayers())
		{
			if (player.isRegisteredOnEvent() && PhantomManager.getInstance().isPhantom(player))
			{
				registered++;
			}
		}
		final List<Player> candidates = new ArrayList<>(hunting);
		Collections.shuffle(candidates);
		for (Player hunter : candidates)
		{
			if (registered >= PhantomHuntersConfig.HUNTER_PVP_EVENT_MAX)
			{
				break;
			}
			if (hunter.isRegisteredOnEvent() || hunter.isOnEvent() || (PhantomRaid.getInstance().raidOf(hunter) != null) || PhantomOlympiad.getInstance().isBusy(hunter))
			{
				continue;
			}
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			final double chance = PhantomHuntersConfig.HUNTER_PVP_EVENT_CHANCE * (template != null ? template.getTemper() : 1);
			if (Rnd.get(100.0) >= chance)
			{
				continue;
			}
			script.notifyEvent("Participate", manager, hunter);
			if (hunter.isRegisteredOnEvent())
			{
				registered++;
				final Score score = new Score(name, now);
				score.listener = new ConsumerEventListener(hunter, EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> onDeath(event), this);
				hunter.addListener(score.listener);
				_participants.put(hunter.getObjectId(), score);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") registers for " + name + " (" + registered + " hunters).");
				if ((template != null) && (Rnd.get(100) < 30))
				{
					PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "записался на " + name + ", кто ещё?" : name + " — го, кто не трус)");
				}
			}
		}
	}

	/**
	 * Менеджер ивента в мире (регистрация или бафферы на арене): раз в 10 с по всему миру, найденный держится, пока стоит.
	 */
	private Npc manager(long now)
	{
		if ((_manager != null) && _manager.isSpawned() && !_manager.isDecayed())
		{
			return _manager;
		}
		_manager = null;
		if ((now - _lastScan) < 10000)
		{
			return null;
		}
		_lastScan = now;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Npc npc) && MANAGERS.containsKey(npc.getId()) && (npc.getInstanceId() == 0))
			{
				_manager = npc;
				break;
			}
		}
		return _manager;
	}

	/**
	 * Для «Мой мир» ({@code activity} в L2HubHunters.json): охотник записан на PvP-ивент или на арене.
	 * @return дописал {@code ,"activity":{"kind":"pvp",…}}
	 */
	boolean activity(StringBuilder sb, Player player)
	{
		final Score score = _participants.get(player.getObjectId());
		if ((score == null) || !isBusy(player))
		{
			return false;
		}
		sb.append(",\"activity\":{\"kind\":\"pvp\",");
		FakePlayerBrain.field(sb, "title", score.event).append(',');
		FakePlayerBrain.field(sb, "stage", player.isOnEvent() ? "ARENA" : "REGISTERED").append(",\"since\":").append(score.since);
		sb.append(",\"kills\":").append(score.kills).append(",\"deaths\":").append(score.deaths).append('}');
		return true;
	}

	/**
	 * @param player охотник
	 * @return записан на ивент или на арене — в город и в лавку не уходить, стратег не командует
	 */
	public boolean isBusy(Player player)
	{
		return player.isRegisteredOnEvent() || player.isOnEvent() || PhantomSiege.getInstance().inBattle(player);
	}

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}, до смерти и фаз): на арене — бой ивента; ивент кончился — {@link Drive#DONE}.
	 * @param player охотник
	 * @param template его шаблон
	 * @param now текущее время
	 * @return что с ним делать
	 */
	Drive drive(Player player, PhantomTemplate template, long now)
	{
		if (!player.isOnEvent())
		{
			final Score score = player.isRegisteredOnEvent() ? null : _participants.remove(player.getObjectId());
			if (score != null)
			{
				player.removeListener(score.listener);
				_runners.values().remove(player.getObjectId());
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " is back from " + score.event + " (kills " + score.kills + ", deaths " + score.deaths + ").");
				return Drive.DONE;
			}
			return Drive.NONE;
		}

		// Убитого ивент поднимает сам; обездвиженный — ждёт открытия ворот.
		if (player.isDead() || player.isTeleporting() || player.isImmobilized() || player.isCastingNow())
		{
			note(player, "waits on the arena (" + (player.isDead() ? "dead" : player.isTeleporting() ? "teleporting" : player.isImmobilized() ? "immobilized" : "casting") + ")", now);
			return Drive.FIGHTS;
		}

		// Сидящему движение не дойдёт: при намерении REST setIntentionMoveTo отбрасывается (прогон CtF 2026-10-30: носильщик просидел весь бой).
		if (player.isSitting())
		{
			player.standUp();
			note(player, "stands up on the arena", now);
			return Drive.FIGHTS;
		}

		final Score score = _participants.get(player.getObjectId());
		final boolean ctf = (score != null) && "CtF".equals(score.event);
		if (ctf && flag(player, template, now))
		{
			return Drive.FIGHTS;
		}

		if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && PhantomSupport.act(player, player, template.getRole(), now))
		{
			return Drive.FIGHTS;
		}

		// CtF: кто несёт флаг своей команды — первый противник, где бы он ни был на арене: убитый носильщик возвращает флаг.
		final Player carrier = ctf ? enemyCarrier(player) : null;
		final Player enemy = carrier != null ? carrier : enemy(player);
		if (enemy == null)
		{
			note(player, "has no enemy on the arena", now);
			return Drive.FIGHTS;
		}
		// TvT и CtF: базы команд по краям арены — мирные зоны, оттуда и туда не бьют (прогон Epilogue 2026-10-22: обе команды стояли у себя
		// весь бой). Все противники у себя или сам ещё на базе — бежать к середине между собой и ним: туда выходят и они.
		// Далеко — бежать к нему: атака AI цели вне видимости (команды на ~4100 друг от друга) сразу сбрасывается — прогон Epilogue 2026-10-22,
		// «attacks Arhimag (4110)» три минуты на месте.
		final int distance = (int) player.calculateDistance2D(enemy);
		final boolean peace = enemy.isInsideZone(ZoneId.PEACE) || player.isInsideZone(ZoneId.PEACE);
		if (peace || (distance > ENGAGE_RANGE))
		{
			if (!player.isMoving())
			{
				player.setRunning();
				player.getAI().setIntentionMoveTo(peace ? new Location((player.getX() + enemy.getX()) / 2, (player.getY() + enemy.getY()) / 2, player.getZ()) : new Location(enemy.getX(), enemy.getY(), enemy.getZ()));
			}
			note(player, "runs to " + (peace ? "the middle of the arena" : enemy.getName()) + " (" + enemy.getName() + " " + distance + " away" + (enemy.isInsideZone(ZoneId.PEACE) ? ", at his base" : "") + ")", now);
			return Drive.FIGHTS;
		}
		note(player, "attacks " + enemy.getName() + " (" + (int) player.calculateDistance2D(enemy) + (enemy == carrier ? ", carries our flag" : "") + ")", now);
		PhantomCompanionManager.fight(player, enemy);
		return Drive.FIGHTS;
	}

	/** Что охотник делает на арене — в лог, не чаще раза в {@value #NOTE_GAP} мс: без клиента иначе не видно, почему он стоит. */
	private void note(Player player, String what, long now)
	{
		final Long last = _lastNote.get(player.getObjectId());
		if ((last == null) || ((now - last) >= NOTE_GAP))
		{
			_lastNote.put(player.getObjectId(), now);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}

	/**
	 * CtF: флаг. Несёт чужой флаг — к своему штабу и сдать разговором со штабом ({@code notifyFirstTalk} скрипта, как клик живого игрока);
	 * наш флаг у противника (свой штаб исчез, сдать некуда) — ждать у места штаба, пока свои не убьют носильщика. Флага у команды ни у кого нет —
	 * бегун команды (один охотник не хилер, первый, кто спросил; держится, пока на ивенте) бежит к штабу противника и берёт флаг. Остальные — в бой.
	 * @return охотник на этом тике занят флагом
	 */
	private boolean flag(Player player, PhantomTemplate template, long now)
	{
		final Team team = player.getTeam();
		final Quest script = ScriptManager.getInstance().getScript("CtF");
		if (((team != Team.BLUE) && (team != Team.RED)) || (script == null))
		{
			return false;
		}
		final boolean blue = team == Team.BLUE;
		final String enemyFlag = blue ? "Red" : "Blue";
		if (carriesFlag(player))
		{
			final Location home = blue ? BLUE_HQ_LOC : RED_HQ_LOC;
			final int distance = (int) player.calculateDistance2D(home);
			if (carrier(player, false) != null)
			{
				if (distance > HQ_TALK_RANGE)
				{
					runTo(player, home);
				}
				note(player, "waits with the " + enemyFlag + " flag at home: ours is taken", now);
				return true;
			}
			final Npc headquarters = distance <= HQ_TALK_RANGE ? headquarters(player, blue ? BLUE_HQ : RED_HQ) : null;
			if (headquarters != null)
			{
				script.notifyFirstTalk(headquarters, player);
				if (!carriesFlag(player))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " captures the " + enemyFlag + " flag on CtF.");
					return true;
				}
			}
			runTo(player, home);
			note(player, "carries the " + enemyFlag + " flag home (" + distance + " away)", now);
			return true;
		}

		if ((carrier(player, true) != null) || ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER)))
		{
			return false;
		}
		final Integer runnerId = _runners.get(team);
		final Player runner = runnerId == null ? null : World.getPlayer(runnerId);
		if ((runner != null) && (runner != player) && runner.isOnEvent() && (runner.getTeam() == team) && _participants.containsKey(runnerId))
		{
			return false;
		}
		if (runner != player)
		{
			_runners.put(team, player.getObjectId());
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " is the flag runner of " + team + " on CtF.");
		}
		final Location target = blue ? RED_HQ_LOC : BLUE_HQ_LOC;
		final int distance = (int) player.calculateDistance2D(target);
		final Npc headquarters = distance <= HQ_TALK_RANGE ? headquarters(player, blue ? RED_HQ : BLUE_HQ) : null;
		if (headquarters != null)
		{
			script.notifyFirstTalk(headquarters, player);
			if (carriesFlag(player))
			{
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " takes the " + enemyFlag + " flag on CtF.");
				return true;
			}
			note(player, "can't take the " + enemyFlag + " flag", now);
			return false;
		}
		runTo(player, target);
		note(player, "runs for the " + enemyFlag + " flag (" + distance + " away)", now);
		return true;
	}

	/** Бежать к месту, если уже не бежит туда (перебивает атаку и прочие намерения). */
	private static void runTo(Player player, Location location)
	{
		if (!player.isMoving() || (player.getAI().getIntention() != Intention.MOVE_TO))
		{
			player.setRunning();
			player.getAI().setIntentionMoveTo(location);
		}
	}

	/** CtF: штаб с флагом рядом (в его инстансе). */
	private static Npc headquarters(Player player, int npcId)
	{
		for (Npc npc : World.getVisibleObjectsInRange(player, Npc.class, HQ_TALK_RANGE * 3))
		{
			if ((npc.getId() == npcId) && !npc.isDecayed())
			{
				return npc;
			}
		}
		return null;
	}

	/** CtF: в руке флаг. */
	private static boolean carriesFlag(Player player)
	{
		final Item hand = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		return (hand != null) && CTF_FLAGS.contains(hand.getId());
	}

	/**
	 * CtF: кто несёт флаг — участник ивента того же инстанса из своей команды ({@code own}) или чужой.
	 * @return носильщик или null
	 */
	private static Player carrier(Player player, boolean own)
	{
		for (Player other : World.getPlayers())
		{
			if (other.isOnEvent() && (other.getInstanceId() == player.getInstanceId()) && (other.getTeam() != Team.NONE) && ((other.getTeam() == player.getTeam()) == own) && carriesFlag(other))
			{
				return other;
			}
		}
		return null;
	}

	/** CtF: противник с нашим флагом, живой и вне мирной зоны — его бить первым. */
	private static Player enemyCarrier(Player player)
	{
		final Player carrier = carrier(player, false);
		return (carrier != null) && !carrier.isDead() && !carrier.isInsideZone(ZoneId.PEACE) ? carrier : null;
	}

	/**
	 * Охотник-участник погиб: на арене — в счёт ему и убийце (если убийца — тоже охотник на ивенте) и строка лога.
	 */
	private synchronized void onDeath(OnCreatureDeath event)
	{
		final Creature target = event.getTarget();
		if (!(target instanceof Player victim) || !victim.isOnEvent())
		{
			return;
		}
		final Score score = _participants.get(victim.getObjectId());
		if (score == null)
		{
			return;
		}
		score.deaths++;
		final Player killer = event.getAttacker() == null ? null : event.getAttacker().asPlayer();
		final Score killerScore = killer == null ? null : _participants.get(killer.getObjectId());
		if (killerScore != null)
		{
			killerScore.kills++;
		}
		LOGGER.info(getClass().getSimpleName() + ": " + (killer != null ? killer.getName() : "?") + " kills " + victim.getName() + " on " + score.event + ".");
	}

	/**
	 * Противник на арене: живой участник того же инстанса из чужой команды (без команд — любой); враг по отношениям — первым,
	 * если он не намного дальше ближайшего. Кто вне мирной зоны (вышел с базы) — первым: того можно бить; все у себя на базах — ближайший.
	 */
	private static Player enemy(Player player)
	{
		final Player open = enemy(player, true);
		return open != null ? open : enemy(player, false);
	}

	private static Player enemy(Player player, boolean outsidePeace)
	{
		Player nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		Player grudge = null;
		double grudgeDistance = Double.MAX_VALUE;
		// Все участники, а не видимые рядом: в TvT и CtF команды встают в разных концах арены — дальше видимости (прогон H5 2026-10-22: 4089, ни одного боя за 20 минут).
		for (Player other : World.getPlayers())
		{
			if ((other == player) || !other.isOnEvent() || other.isDead() || (other.getInstanceId() != player.getInstanceId()) || ((player.getTeam() != Team.NONE) && (other.getTeam() == player.getTeam())) || (outsidePeace && other.isInsideZone(ZoneId.PEACE)))
			{
				continue;
			}
			final double distance = player.calculateDistance2D(other);
			if (distance < nearestDistance)
			{
				nearest = other;
				nearestDistance = distance;
			}
			if ((distance < grudgeDistance) && PhantomRelations.getInstance().isEnemy(player, other))
			{
				grudge = other;
				grudgeDistance = distance;
			}
		}
		return (grudge != null) && (grudgeDistance <= (nearestDistance + GRUDGE_EXTRA)) ? grudge : nearest;
	}

	public static PhantomEvents getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomEvents INSTANCE = new PhantomEvents();
	}
}
