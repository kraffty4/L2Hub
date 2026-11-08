package org.l2jmobius.gameserver.managers.world;

import java.util.BitSet;

/** Награда за полезное участие в разных 30-секундных окнах, а не за число кликов. */
final class X10EventProgress
{
	static final long WINDOW = 30000;
	private final BitSet activity = new BitSet();
	long lastCombat;
	long lastSupport;
	double damage;
	int kills;

	void active(long elapsed)
	{
		activity.set((int) Math.clamp(elapsed / WINDOW, 0, 120));
	}

	boolean eligible(long elapsed)
	{
		return eligible(elapsed, 70);
	}

	boolean eligible(long elapsed, int percent)
	{
		final int windows = (int) Math.max(1, (elapsed + WINDOW - 1) / WINDOW);
		return activity.get(0, windows).cardinality() * 100 >= windows * Math.clamp(percent, 1, 100);
	}

	static int coins(int fullReward, long elapsed, long fullDuration)
	{
		return (int) Math.min(fullReward, Math.max(0, (long) fullReward * elapsed / Math.max(1, fullDuration)));
	}
}
