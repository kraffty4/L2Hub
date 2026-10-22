package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * L2Hub: живой мир для одного игрока (config/Custom/L2HubWorld.ini, docs/WORLD.md). Читает сам модуль при старте
 * ({@code WorldDaily.start}), без записи в {@code ConfigLoader}: врезок в файлы Mobius не нужно.
 */
public class L2HubWorldConfig
{
	// File
	public static final String L2HUB_WORLD_CONFIG_FILE = "./config/Custom/L2HubWorld.ini";

	// Constants
	public static boolean WORLD_DAILY;
	public static int WORLD_DAILY_RESET_HOUR;
	public static int WORLD_DAILY_RESET_MINUTE;
	public static int WORLD_DAILY_HUNT_KILLS;
	public static int WORLD_DAILY_SPOT_KILLS;
	public static int WORLD_DAILY_HUNTER_PARTY_KILLS;
	public static int WORLD_DAILY_CHAMPIONS;
	public static int WORLD_DAILY_ADENA;
	public static int WORLD_DAILY_EXP_PERCENT;
	public static boolean WORLD_DAILY_BONUS;
	public static int WORLD_DAILY_TELEPORT_FEE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(L2HUB_WORLD_CONFIG_FILE);
		WORLD_DAILY = config.getBoolean("WorldDaily", true);
		final String[] reset = config.getString("WorldDailyResetTime", "06:30").strip().split(":");
		WORLD_DAILY_RESET_HOUR = parse(reset, 0, 6, 23);
		WORLD_DAILY_RESET_MINUTE = parse(reset, 1, 30, 59);
		WORLD_DAILY_HUNT_KILLS = Math.max(1, config.getInt("WorldDailyHuntKills", 100));
		WORLD_DAILY_SPOT_KILLS = Math.max(1, config.getInt("WorldDailySpotKills", 50));
		WORLD_DAILY_HUNTER_PARTY_KILLS = Math.max(1, config.getInt("WorldDailyHunterPartyKills", 40));
		WORLD_DAILY_CHAMPIONS = Math.max(1, config.getInt("WorldDailyChampions", 1));
		WORLD_DAILY_ADENA = Math.max(0, config.getInt("WorldDailyAdena", 30));
		WORLD_DAILY_EXP_PERCENT = Math.max(0, Math.min(100, config.getInt("WorldDailyExpPercent", 2)));
		WORLD_DAILY_BONUS = config.getBoolean("WorldDailyBonus", true);
		WORLD_DAILY_TELEPORT_FEE = Math.max(0, config.getInt("WorldDailyTeleportFee", 15000));
	}

	private static int parse(String[] parts, int index, int fallback, int max)
	{
		try
		{
			return Math.max(0, Math.min(max, Integer.parseInt(parts[index].strip())));
		}
		catch (Exception e)
		{
			return fallback;
		}
	}
}
