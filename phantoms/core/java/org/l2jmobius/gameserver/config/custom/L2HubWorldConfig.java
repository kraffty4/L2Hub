package org.l2jmobius.gameserver.config.custom;

import java.util.Arrays;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * L2Hub: живой мир для одного игрока (config/Custom/L2HubWorld.ini, docs/WORLD.md). Читает сам модуль при старте
 * ({@code WorldDaily.start}), без записи в {@code ConfigLoader}: врезок в файлы Mobius не нужно.
 */
public class L2HubWorldConfig
{
	// File
	public static final String L2HUB_WORLD_CONFIG_FILE = "./config/Custom/L2HubWorld.ini";
	/** Члены клана на 6, 7, 8, 9, 10 и 11-й уровень по умолчанию (кланы малого мира). */
	private static final String SMALL_WORLD_CLAN_MEMBERS_DEFAULT = "10,15,20,30,40,50";

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
	/** Язык текстов L2Hub в игре: {@code ru} или {@code en} (ключ L2HubLanguage — его пишет лаунчер по языку окна). */
	public static String LANGUAGE;
	/** Кланы малого мира ({@code SmallWorldClans}): меньше членов на 6-й уровень клана и выше ({@code SmallWorldClans.apply}). */
	public static boolean SMALL_WORLD_CLANS;
	/** Сколько членов нужно клану на 6, 7, 8… уровень ({@code SmallWorldClanMembers}). */
	public static int[] SMALL_WORLD_CLAN_MEMBERS;

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
		LANGUAGE = "en".equalsIgnoreCase(config.getString("L2HubLanguage", "ru").strip()) ? "en" : "ru";
		SMALL_WORLD_CLANS = config.getBoolean("SmallWorldClans", true);
		SMALL_WORLD_CLAN_MEMBERS = numbers(config.getString("SmallWorldClanMembers", SMALL_WORLD_CLAN_MEMBERS_DEFAULT), SMALL_WORLD_CLAN_MEMBERS_DEFAULT);
	}

	/** Числа через запятую, каждое от 1; ошибка в строке — {@code fallback}. */
	private static int[] numbers(String value, String fallback)
	{
		try
		{
			return Arrays.stream(value.split(",")).map(String::strip).filter(s -> !s.isEmpty()).mapToInt(s -> Math.max(1, Integer.parseInt(s))).toArray();
		}
		catch (NumberFormatException e)
		{
			return numbers(fallback, fallback);
		}
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
