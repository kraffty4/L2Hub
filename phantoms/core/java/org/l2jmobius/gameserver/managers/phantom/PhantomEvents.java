package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Team;
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
 * Флаги CtF охотники пока не носят — только дерутся.
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
		return player.isRegisteredOnEvent() || player.isOnEvent();
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

		if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && PhantomSupport.act(player, player, template.getRole(), now))
		{
			return Drive.FIGHTS;
		}

		final Player enemy = enemy(player);
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
		note(player, "attacks " + enemy.getName() + " (" + (int) player.calculateDistance2D(enemy) + ")", now);
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
