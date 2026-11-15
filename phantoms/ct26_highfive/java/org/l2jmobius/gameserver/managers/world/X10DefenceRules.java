package org.l2jmobius.gameserver.managers.world;

/** Правила забега у ворот. Номер волны начинается с единицы. */
final class X10DefenceRules
{
	static int level(int partyLevel, int wave, int offset, int step)
	{
		return Math.clamp(partyLevel - offset + Math.max(0, wave - 1) * step, 1, 85);
	}

	static boolean boss(int wave) { return wave > 0 && wave % 5 == 0; }

	static long coins(int wave, int first, int growth, boolean doubled)
	{
		return ((long) first + (long) Math.max(0, wave - 1) * growth) * (doubled ? 2 : 1);
	}

	static double hp(int wave, int growthPercent, double modifier, double bossMultiplier)
	{
		return (1 + Math.max(0, wave - 1) * growthPercent / 100.0) * modifier * bossMultiplier;
	}

	private X10DefenceRules() { }
}
