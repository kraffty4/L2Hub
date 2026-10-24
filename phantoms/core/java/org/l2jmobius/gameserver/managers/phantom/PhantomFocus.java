package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * L2Hub: «Разум 2.0», шаг 1 — охотники рядом с живым игроком (docs/MIND.md; ключи {@code HunterMindExtended}, {@code HunterFocus*} в PhantomHunters.ini).<br>
 * Раз в 10 с ({@code PhantomHunterManager.control}) у каждого охотника ищется повод быть «в фокусе» у какого-нибудь живого игрока:
 * <ul>
 * <li>{@code party} — в одной пати с ним;</li>
 * <li>{@code talk} — говорил с ним последние {@value #TALK_WINDOW} мс (шёпот, пати, белый чат к нему — {@link #talked});</li>
 * <li>{@code near} — в {@value #NEAR_RANGE} от него и в прямой видимости по геодате.</li>
 * </ul>
 * В фокусе — не больше {@code HunterFocusMax} на всех живых: пати, потом разговор, потом ближние. Выпал из фокуса — остаётся ещё {@code HunterFocusLinger} секунд,
 * если есть место: признак не мигает. Признак уходит в запросы к мозгам ({@code "focus"} у стратега и в разговорах) и в L2HubHunters.json; при
 * {@code HunterMindExtended} стратег у таких думает чаще. Танатос — не охотник: вне фокуса и вне стратега.
 */
public final class PhantomFocus
{
	/** Ближний — в этом радиусе от живого игрока. */
	private static final int NEAR_RANGE = 2500;
	/** Говорил с живым — столько считается «в разговоре». */
	private static final long TALK_WINDOW = 120000;

	private record Candidate(Player hunter, String kind, int priority, double distance)
	{
	}

	/** Пара «охотник — живой игрок» → когда говорили. */
	private final Map<Long, Long> _talks = new ConcurrentHashMap<>();
	/** objectId охотника → признак сейчас. */
	private volatile Map<Integer, String> _focus = Map.of();
	/** objectId охотника → до какого времени держится в фокусе после потери повода. */
	private final Map<Integer, Long> _linger = new ConcurrentHashMap<>();

	private PhantomFocus()
	{
	}

	/**
	 * Пересчитать, кто в фокусе.
	 * @param hunters охотники в мире
	 * @param now текущее время
	 */
	void update(Collection<Player> hunters, long now)
	{
		final List<Player> live = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if (!PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInvisible())
			{
				live.add(player);
			}
		}
		_talks.values().removeIf(time -> (now - time) > TALK_WINDOW);
		final int max = Math.max(0, PhantomHuntersConfig.HUNTER_FOCUS_MAX);
		if (live.isEmpty() || (max == 0))
		{
			_focus = Map.of();
			_linger.clear();
			return;
		}

		final List<Candidate> fresh = new ArrayList<>();
		for (Player hunter : hunters)
		{
			Candidate best = null;
			for (Player player : live)
			{
				final double distance = hunter.calculateDistance2D(player);
				final Candidate candidate;
				if (hunter.isInParty() && (hunter.getParty() == player.getParty()))
				{
					candidate = new Candidate(hunter, "party", 0, distance);
				}
				else if (_talks.containsKey(pair(hunter, player)))
				{
					candidate = new Candidate(hunter, "talk", 1, distance);
				}
				else if ((distance <= NEAR_RANGE) && (hunter.getInstanceId() == player.getInstanceId()) && GeoEngine.getInstance().canSeeTarget(player, hunter))
				{
					candidate = new Candidate(hunter, "near", 2, distance);
				}
				else
				{
					continue;
				}
				if ((best == null) || (candidate.priority() < best.priority()) || ((candidate.priority() == best.priority()) && (candidate.distance() < best.distance())))
				{
					best = candidate;
				}
			}
			if (best != null)
			{
				fresh.add(best);
			}
		}
		fresh.sort(Comparator.comparingInt(Candidate::priority).thenComparingDouble(Candidate::distance));

		final long linger = Math.max(0, PhantomHuntersConfig.HUNTER_FOCUS_LINGER) * 1000L;
		final Map<Integer, String> focus = new LinkedHashMap<>();
		for (Candidate candidate : fresh.subList(0, Math.min(max, fresh.size())))
		{
			focus.put(candidate.hunter().getObjectId(), candidate.kind());
			_linger.put(candidate.hunter().getObjectId(), now + linger);
		}
		// Гистерезис: выпавшие недавно — пока есть место, с прежним признаком.
		final Map<Integer, String> previous = _focus;
		final List<Map.Entry<Integer, Long>> lingering = new ArrayList<>(_linger.entrySet());
		lingering.sort(Map.Entry.<Integer, Long> comparingByValue().reversed());
		for (Map.Entry<Integer, Long> entry : lingering)
		{
			if (focus.size() >= max)
			{
				break;
			}
			if (!focus.containsKey(entry.getKey()) && (entry.getValue() > now) && previous.containsKey(entry.getKey()))
			{
				focus.put(entry.getKey(), previous.get(entry.getKey()));
			}
		}
		_linger.entrySet().removeIf(entry -> (entry.getValue() <= now) && !focus.containsKey(entry.getKey()));
		_focus = focus;
	}

	/**
	 * Охотник ответил живому игроку ({@code PhantomChat.talk}): разговор — повод для фокуса {@value #TALK_WINDOW} мс.
	 */
	void talked(Player hunter, Player player)
	{
		if (PhantomRelations.isHunter(hunter) && !PhantomManager.getInstance().isPhantom(player))
		{
			_talks.put(pair(hunter, player), System.currentTimeMillis());
		}
	}

	/**
	 * @return признак охотника: {@code near}, {@code party}, {@code talk} или {@code null} — не в фокусе
	 */
	String of(Player hunter)
	{
		return hunter == null ? null : _focus.get(hunter.getObjectId());
	}

	/**
	 * @return стратег у охотника в фокусе думает раз во столько мс ({@code HunterMindExtended}, {@code HunterFocusStrategyMinutes}); 0 — по обычному расписанию
	 */
	long strategyGap(Player hunter)
	{
		return PhantomHuntersConfig.HUNTER_MIND_EXTENDED && (of(hunter) != null) ? Math.max(1, PhantomHuntersConfig.HUNTER_FOCUS_STRATEGY_MINUTES) * 60000L : 0;
	}

	private static long pair(Player hunter, Player player)
	{
		return ((long) hunter.getObjectId() << 32) | (player.getObjectId() & 0xffffffffL);
	}

	public static PhantomFocus getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomFocus INSTANCE = new PhantomFocus();
	}
}
