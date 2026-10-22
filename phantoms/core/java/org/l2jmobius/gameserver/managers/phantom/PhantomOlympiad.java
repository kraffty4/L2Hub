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
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.olympiad.Olympiad;

/**
 * L2Hub: охотники на олимпиаде (docs/PHANTOMS.md, раздел 4.39; {@code HunterOlympiad} в PhantomHunters.ini) — «стать героем» в одиночку, когда соперники — охотники.
 * <ul>
 * <li>дворянство — охотник с {@code HunterOlympiadLevel} (76: к нему у охотника уже третья профа) становится дворянином, как после квеста;</li>
 * <li>запись — в часы соревнований ({@code Olympiad.inCompPeriod}) раз в минуту дворяне с кача записываются на матч без класса, тем же путём, что игрок у менеджера
 * ({@link PhantomCompat#olympiadRegister}), шанс — {@code HunterOlympiadChance} × вспыльчивость, не больше {@code HunterOlympiadMax};</li>
 * <li>бой — на стадионе (матч ведёт Mobius: телепорт, отсчёт, итог, очки, герои периода): соперник по матчу дальше {@value #ENGAGE_RANGE} — бежать к нему,
 * ближе — бой напарника, хилер — сначала себя.</li>
 * </ul>
 * Записанный ходит в город, как обычно (матча можно ждать долго), но в рейды и PvP-ивенты его не берут. Настройки малого мира (матч от двоих, герои раз в неделю) —
 * патч {@code 0025-olympiad-small-world} c4-interlude.
 */
public final class PhantomOlympiad
{
	private static final Logger LOGGER = Logger.getLogger(PhantomOlympiad.class.getName());

	/** Запись и дворянство — не чаще раза в столько. */
	private static final long CONTROL_GAP = 60000;
	/** Соперник дальше — бежать к нему: атака AI цели вне видимости сбрасывается (как на PvP-ивентах, раздел 4.33). */
	private static final int ENGAGE_RANGE = 1200;
	/** Строка лога о бое — не чаще раза в столько на охотника. */
	private static final long NOTE_GAP = 30000;

	private long _lastControl;
	private final Map<Integer, Long> _lastNote = new ConcurrentHashMap<>();

	private PhantomOlympiad()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control}: дворянство и запись.
	 * @param hunting охотники на каче, не в пати, живы
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_OLYMPIAD || ((now - _lastControl) < CONTROL_GAP))
		{
			return;
		}
		_lastControl = now;

		for (Player hunter : hunting)
		{
			if (!hunter.isNoble() && (hunter.getLevel() >= PhantomHuntersConfig.HUNTER_OLYMPIAD_LEVEL))
			{
				hunter.setNoble(true);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") became a noble.");
				if (Rnd.get(100) < 50)
				{
					PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "ну всё, я теперь дворянин, на оли пойду)" : "дворянство взял! кто на олимпиаду?");
				}
			}
		}
		if (!Olympiad.getInstance().inCompPeriod())
		{
			return;
		}

		int registered = 0;
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) && (PhantomCompat.olympiadRegistered(player) || player.isInOlympiadMode()))
			{
				registered++;
			}
		}
		final List<Player> candidates = new ArrayList<>(hunting);
		Collections.shuffle(candidates);
		for (Player hunter : candidates)
		{
			if (registered >= PhantomHuntersConfig.HUNTER_OLYMPIAD_MAX)
			{
				break;
			}
			if (!hunter.isNoble() || hunter.isInOlympiadMode() || PhantomCompat.olympiadRegistered(hunter) || PhantomEvents.getInstance().isBusy(hunter) || (PhantomRaid.getInstance().raidOf(hunter) != null))
			{
				continue;
			}
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			if (Rnd.get(100.0) >= (PhantomHuntersConfig.HUNTER_OLYMPIAD_CHANCE * (template != null ? template.getTemper() : 1)))
			{
				continue;
			}
			if (PhantomCompat.olympiadRegister(hunter))
			{
				registered++;
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") registers for the Olympiad (" + registered + " hunters).");
			}
		}
	}

	/**
	 * @return записан на матч или на стадионе — в рейд и на PvP-ивент не брать
	 */
	public boolean isBusy(Player player)
	{
		return player.isInOlympiadMode() || PhantomCompat.olympiadRegistered(player);
	}

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}, до смерти и фаз): на стадионе — бой матча.
	 * @return {@link PhantomEvents.Drive#FIGHTS} — на стадионе, правила кача не трогать; иначе {@link PhantomEvents.Drive#NONE}
	 */
	PhantomEvents.Drive drive(Player player, PhantomTemplate template, long now)
	{
		if (!player.isInOlympiadMode())
		{
			return PhantomEvents.Drive.NONE;
		}
		// Отсчёт до боя, телепорт, каст, лежит — ждёт; матч и итог ведёт Mobius.
		if (!player.isOlympiadStart() || player.isDead() || player.isTeleporting() || player.isCastingNow())
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && PhantomSupport.act(player, player, template.getRole(), now))
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		final Player opponent = opponent(player);
		if (opponent == null)
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		final int distance = (int) player.calculateDistance2D(opponent);
		if (distance > ENGAGE_RANGE)
		{
			if (!player.isMoving())
			{
				player.setRunning();
				player.getAI().setIntentionMoveTo(new Location(opponent.getX(), opponent.getY(), opponent.getZ()));
			}
			note(player, "runs to " + opponent.getName() + " on the Olympiad stadium (" + distance + ")", now);
			return PhantomEvents.Drive.FIGHTS;
		}
		note(player, "fights " + opponent.getName() + " on the Olympiad stadium (HP " + (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp())) + "% vs " + (int) ((opponent.getCurrentHp() * 100) / Math.max(1, opponent.getMaxHp())) + "%)", now);
		PhantomCompanionManager.fight(player, opponent);
		return PhantomEvents.Drive.FIGHTS;
	}

	/**
	 * Соперник по матчу: тот же матч ({@code getOlympiadGameId}), другая сторона.
	 */
	private static Player opponent(Player player)
	{
		for (Player other : World.getPlayers())
		{
			if ((other != player) && other.isInOlympiadMode() && (other.getOlympiadGameId() == player.getOlympiadGameId()) && (other.getOlympiadSide() != player.getOlympiadSide()) && !other.isDead())
			{
				return other;
			}
		}
		return null;
	}

	private void note(Player player, String what, long now)
	{
		final Long last = _lastNote.get(player.getObjectId());
		if ((last == null) || ((now - last) >= NOTE_GAP))
		{
			_lastNote.put(player.getObjectId(), now);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}

	public static PhantomOlympiad getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomOlympiad INSTANCE = new PhantomOlympiad();
	}
}
