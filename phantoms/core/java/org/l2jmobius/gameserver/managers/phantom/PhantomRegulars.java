package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.ToIntFunction;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;

/**
 * L2Hub: завсегдатаи и толпа рядом (docs/PHANTOMS.md, раздел 4.63; docs/LIVE_SERVER.md, п. 9). Ключ {@code HunterRegulars}. Споты выбираются так, чтобы люди встречались и
 * встречались снова: в диапазоне уровней есть людные места (несколько, чтобы все не сбились в одно), у охотника свои споты, у живого свои завсегдатаи из ровни. Всё считается
 * из имён хешем: файла состояния нет, перезапуск не тасует, тот же охотник на следующий день придёт туда же. Людные места меняются раз в неделю.
 */
final class PhantomRegulars
{
	/** Людным зовётся место, где на всех спотах вместе столько мобов: компании должно хватать. */
	private static final int MIN_PLACE_MOBS = 30;
	/** Столько своих спотов у охотника в его диапазоне уровней. */
	private static final int FAVOURITES = 2;
	/** Такая доля ровни (%) — завсегдатаи данного живого. */
	private static final int LIVING_SHARE = 20;
	/** Такая доля пар охотников (%) знакома между собой, кроме соклановцев. */
	private static final int PAIR_SHARE = 15;
	/** На таком расстоянии от спота живой считается стоящим на нём, кроме радиуса самого спота. */
	private static final int LIVING_MARGIN = 500;
	private static final long WEEK = 7 * 86400000L;

	/** Самопроверка: охотник, который играет живого. */
	private static volatile Player _testLiving;

	private PhantomRegulars()
	{
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_REGULARS;
	}

	static void testLiving(Player player)
	{
		_testLiving = player;
	}

	/**
	 * @param fitting споты охотнику по уровню
	 * @param crowd сколько охотников на споте и рядом, без него самого
	 * @return куда идти; {@code null} — места нет, обычный выбор
	 */
	static HuntSpot choose(Player player, List<HuntSpot> fitting, ToIntFunction<HuntSpot> crowd)
	{
		final String name = player.getName();
		HuntSpot chosen = nearLiving(player, fitting, crowd);
		if ((chosen == null) && (Long.remainderUnsigned(mix(name, "crowd"), 100) < PhantomHuntersConfig.HUNTER_REGULARS_SHARE))
		{
			chosen = crowded(name, fitting, crowd);
		}
		return chosen != null ? chosen : favourite(name, fitting, crowd);
	}

	/**
	 * @return спот рядом с живым, чей завсегдатай этот охотник; спот должен быть ему по уровню, предел {@code HunterRegularsNearLiving}
	 */
	private static HuntSpot nearLiving(Player player, List<HuntSpot> fitting, ToIntFunction<HuntSpot> crowd)
	{
		if (PhantomHuntersConfig.HUNTER_REGULARS_NEAR_LIVING <= 0)
		{
			return null;
		}
		final Player test = _testLiving;
		final List<Player> living = new ArrayList<>();
		if (test != null)
		{
			living.add(test);
		}
		else
		{
			for (Player other : World.getPlayers())
			{
				if (!PhantomManager.getInstance().isPhantom(other) && (other.getClient() != null) && !other.getClient().isDetached())
				{
					living.add(other);
				}
			}
		}
		for (Player other : living)
		{
			if ((other == player) || !regularOf(player.getName(), other.getName()))
			{
				continue;
			}
			for (HuntSpot spot : fitting)
			{
				if ((Math.hypot(spot.entry.getX() - other.getX(), spot.entry.getY() - other.getY()) < (spot.radius + LIVING_MARGIN)) && (crowd.applyAsInt(spot) < PhantomHuntersConfig.HUNTER_REGULARS_NEAR_LIVING))
				{
					return spot;
				}
			}
		}
		return null;
	}

	/**
	 * @return завсегдатай живого: один и тот же набор ровни, а не каждый раз новые
	 */
	static boolean regularOf(String name, String living)
	{
		return Long.remainderUnsigned(mix(name, "living:" + living.toLowerCase(Locale.ROOT)), 100) < LIVING_SHARE;
	}

	private static HuntSpot crowded(String name, List<HuntSpot> fitting, ToIntFunction<HuntSpot> crowd)
	{
		final List<String> places = crowdedPlaces(fitting, System.currentTimeMillis() / WEEK);
		HuntSpot best = null;
		for (HuntSpot spot : fitting)
		{
			if (places.contains(spot.name) && (crowd.applyAsInt(spot) < PhantomHuntersConfig.HUNTER_REGULARS_CROWD) && ((best == null) || (Long.compareUnsigned(mix(name, key(spot)), mix(name, key(best))) < 0)))
			{
				best = spot;
			}
		}
		return best;
	}

	/**
	 * Взвешенный хеш: чем больше на месте мобов, тем чаще оно людное. Диапазоны уровней перекрываются, и у соседних уровней людные места во многом те же.
	 * @return людные места среди этих спотов на этой неделе
	 */
	private static List<String> crowdedPlaces(List<HuntSpot> fitting, long week)
	{
		final Map<String, Integer> mobs = new HashMap<>();
		for (HuntSpot spot : fitting)
		{
			mobs.merge(spot.name, spot.mobs, Integer::sum);
		}
		final List<String> places = new ArrayList<>();
		for (Map.Entry<String, Integer> place : mobs.entrySet())
		{
			if (place.getValue() >= MIN_PLACE_MOBS)
			{
				places.add(place.getKey());
			}
		}
		places.sort(Comparator.comparingDouble(place -> -Math.log(((mix(place, "week:" + week) >>> 11) + 0.5) / (1L << 53)) / mobs.get(place)));
		return places.subList(0, Math.min(places.size(), PhantomHuntersConfig.HUNTER_REGULARS_PLACES));
	}

	private static HuntSpot favourite(String name, List<HuntSpot> fitting, ToIntFunction<HuntSpot> crowd)
	{
		final List<HuntSpot> ranked = new ArrayList<>(fitting);
		ranked.sort((a, b) -> Long.compareUnsigned(mix(name, key(a)), mix(name, key(b))));
		for (HuntSpot spot : ranked.subList(0, Math.min(ranked.size(), FAVOURITES)))
		{
			if (crowd.applyAsInt(spot) < PhantomHuntersConfig.HUNTERS_PER_SPOT)
			{
				return spot;
			}
		}
		return null;
	}

	/**
	 * @return охотники знакомы: соклановцы или постоянная пара
	 */
	static boolean knows(Player a, Player b)
	{
		if ((a.getClan() != null) && (a.getClan() == b.getClan()))
		{
			return true;
		}
		final boolean ordered = a.getName().compareToIgnoreCase(b.getName()) < 0;
		return Long.remainderUnsigned(mix(ordered ? a.getName() : b.getName(), "pair:" + (ordered ? b.getName() : a.getName()).toLowerCase(Locale.ROOT)), 100) < PAIR_SHARE;
	}

	/**
	 * У {@code name} в таблице одного спота много, поэтому спот различается ещё и точкой входа.
	 */
	private static String key(HuntSpot spot)
	{
		return spot.name + "@" + spot.entry.getX() + "," + spot.entry.getY();
	}

	private static long mix(String name, String salt)
	{
		long x = (name.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B97F4A7C15L) + (salt.hashCode() * 0xC2B2AE3D27D4EB4FL);
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return x ^ (x >>> 31);
	}
}
