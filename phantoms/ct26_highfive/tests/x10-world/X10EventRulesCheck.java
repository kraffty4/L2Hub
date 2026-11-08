package org.l2jmobius.gameserver.managers.world;

/** Границы накопленного времени, ограничение волн и экономика обороны. */
public final class X10EventRulesCheck
{
	private static int checks;
	private static void check(boolean value)
	{
		checks++;
		if (!value) { throw new AssertionError("Event rule check " + checks); }
	}

	public static void main(String[] args)
	{
		check(X10EventRules.invasionLevel(10799) == 40);
		check(X10EventRules.invasionLevel(10800) == 50);
		check(X10EventRules.invasionLevel(86399) == 50);
		check(X10EventRules.invasionLevel(86400) == 60);
		check(X10EventRules.invasionLevel(172800) == 70);
		check(X10EventRules.invasionLevel(259200) == 80);
		check(X10EventRules.invasionLevel(345600) == 85);
		check(X10EventRules.invasionLevel(Long.MAX_VALUE) == 85);
		check(X10EventRules.waveSize(0, 10, 3, 2, 30) < X10EventRules.waveSize(9, 10, 3, 2, 30));
		check(X10EventRules.waveSize(1000, 1000, 3, 2, 30) <= 30);
		check(X10EventRules.defenceCoins(100, 1, 600000, 600000, 20) == 100);
		check(X10EventRules.defenceCoins(100, 0.5, 600000, 600000, 20) == 50);
		check(X10EventRules.defenceCoins(100, 0.2, 600000, 600000, 20) == 20);
		check(X10EventRules.defenceCoins(100, 0.1, 600000, 600000, 20) == 20);
		check(X10EventRules.defenceCoins(100, 0, 60000, 600000, 20) == 2);
		check(X10EventRules.defenceCoins(100, 2, 1200000, 600000, 20) == 100);
		check(X10EventRules.defenceCoins(100, 1, -1, 600000, 20) == 0);
		check(X10EventRules.huntCoins(100, 240000, 600000, 25) == 65);
		check(X10EventRules.huntCoins(100, 600000, 600000, 25) == 125);
		check(X10EventRules.huntCoins(100, 1200000, 600000, 1000) == 1100);
		check(X10EventRules.huntCoins(100, 0, 600000, 25) == 0);
		check(X10EventRules.huntCoins(0, 600000, 600000, 25) == 25);
		final X10EventRules.QuarryBonus bonus = new X10EventRules.QuarryBonus();
		bonus.record(1, false);
		check(bonus.coins(5, 25) == 0);
		bonus.record(1, true);
		check(bonus.coins(5, 25) == 5);
		for (int n = 0; n < 100; n++) { bonus.record(1, true); }
		check(bonus.coins(5, 25) == 5);
		for (int n = 2; n < 20; n++) { bonus.record(n, true); }
		check(bonus.coins(5, 25) == 25);
		check(bonus.coins(7, 10) == 10);
		check(bonus.coins(7, 0) == 0);
		check(bonus.coins(0, 100) == 0);
		check(X10EventRules.defenceCoins(200, 0.1, 600000, 600000, 50) == 100);
		check(X10EventRules.defenceCoins(200, 0, 600000, 600000, 0) == 0);
		check(X10EventRules.waveSize(5, 12, 6, 3, 100) == 21);
		System.out.println("X10 event rules: " + checks + " checks passed");
	}
}
