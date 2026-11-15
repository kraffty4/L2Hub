package org.l2jmobius.gameserver.managers.world;

/** Границы уровней, роста волн и наград без сервера. */
public final class X10DefenceRulesCheck
{
	private static int checks;
	private static void check(boolean ok) { checks++; if (!ok) { throw new AssertionError("check " + checks); } }
	public static void main(String[] args)
	{
		for (int level = 1; level <= 85; level++)
		{
			check(X10DefenceRules.level(level, 1, 10, 3) == Math.max(1, level - 10));
			int previous = 0;
			for (int wave = 1; wave <= 30; wave++)
			{
				final int current = X10DefenceRules.level(level, wave, 10, 3);
				check(current >= previous && current >= 1 && current <= 85);
				previous = current;
			}
		}
		long total = 0;
		for (int wave = 1; wave <= 30; wave++)
		{
			check(X10DefenceRules.boss(wave) == (wave % 5 == 0));
			check(X10DefenceRules.coins(wave, 5, 5, true) == 2 * X10DefenceRules.coins(wave, 5, 5, false));
			check(X10DefenceRules.hp(wave + 1, 20, 1, 1) > X10DefenceRules.hp(wave, 20, 1, 1));
			if (wave <= 10) { total += X10DefenceRules.coins(wave, 5, 5, false); }
		}
		check(total == 275);
		check(X10DefenceRules.hp(5, 20, 2, 5) == 18);
		check(X10DefenceRules.coins(30, 100000, 100000, true) == 6000000L);
		check(X10DefenceRules.coins(1, 0, 0, true) == 0);
		System.out.println("X10 defence rules: " + checks + " checks passed");
	}
}
