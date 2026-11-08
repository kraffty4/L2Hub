package org.l2jmobius.gameserver.managers.world;

/** Граница полезной активности и награда за короткий бой, без сервера. */
public final class X10EventProgressCheck
{
	private static void check(boolean value, String message)
	{
		if (!value)
		{
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args)
	{
		final X10EventProgress spam = new X10EventProgress();
		for (int n = 0; n < 1000; n++)
		{
			spam.active(1000);
		}
		check(!spam.eligible(600000), "click spam is not sustained participation");
		final X10EventProgress player = new X10EventProgress();
		for (int n = 0; n < 13; n++)
		{
			player.active(n * 30000L);
		}
		check(!player.eligible(600000), "65 percent is insufficient");
		check(player.eligible(600000, 65), "custom 65 percent threshold qualifies");
		check(!player.eligible(600000, 100), "custom full attendance threshold rejects");
		player.active(390000);
		check(player.eligible(600000), "70 percent qualifies");
		check(!new X10EventProgress().eligible(600000), "presence alone gives no reward");
		check(X10EventProgress.coins(100, 240000, 600000) == 40, "four minute fight");
		check(X10EventProgress.coins(100, 600000, 600000) == 100, "full fight");
		check(X10EventProgress.coins(100, 1200000, 600000) == 100, "cannot exceed base reward");
		check(X10EventProgress.coins(100, -1000, 600000) == 0, "negative time gives no reward");
		System.out.println("X10 event participation: 10 checks passed");
	}
}
