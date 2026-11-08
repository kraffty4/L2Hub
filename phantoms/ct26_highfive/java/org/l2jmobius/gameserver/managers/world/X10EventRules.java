package org.l2jmobius.gameserver.managers.world;

/** Чистые правила расписания, волн и наград: их можно проверить без мира и базы. */
final class X10EventRules
{
	enum Kind { FARM, DEFENCE, HUNT }

	static int invasionLevel(long seconds)
	{
		return seconds < 10800 ? 40 : seconds < 86400 ? 50 : seconds < 172800 ? 60 : seconds < 259200 ? 70 : seconds < 345600 ? 80 : 85;
	}

	static int waveSize(int wave, int participants, int first, int growth, int cap)
	{
		return Math.min(cap, Math.max(first, participants / 3) + Math.max(0, wave) * growth);
	}

	static int defenceCoins(int full, double health, long elapsed, long duration, int minimumPercent)
	{
		final int quality = (int) Math.floor(full * Math.max(minimumPercent / 100.0, Math.clamp(health, 0, 1)));
		return X10EventProgress.coins(quality, elapsed, duration);
	}

	static int huntCoins(int full, long elapsed, long duration, int bonus)
	{
		return elapsed <= 0 ? 0 : X10EventProgress.coins(full, elapsed, duration) + Math.max(0, bonus);
	}

	static final class QuarryBonus
	{
		private final java.util.Set<Integer> victims = new java.util.HashSet<>();
		void record(int characterId, boolean active)
		{
			if (characterId > 0 && active) { victims.add(characterId); }
		}
		int coins(int perKill, int cap) { return (int) Math.min(Math.max(0, cap), (long) victims.size() * Math.max(0, perKill)); }
	}

	private X10EventRules() { }
}
