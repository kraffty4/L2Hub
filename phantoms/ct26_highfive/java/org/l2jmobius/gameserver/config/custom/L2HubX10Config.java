package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/** Общие правила ивентов и поручений, рассчитанные для High Five x10. */
public final class L2HubX10Config
{
	public static boolean EVENTS_ENABLED;
	public static boolean CONTRACTS_ENABLED;
	public static boolean COIN_DROP_ENABLED;
	public static boolean ADENA_ALWAYS_DROP;
	public static int DAILY_COINS;
	public static int DAILY_MIN_LEVEL;
	public static int WEEKLY_MIN_LEVEL;
	public static int WEEKLY_COINS;
	public static int DROP_CHANCE;
	public static int EVENT_COINS;
	public static int EVENT_MINUTES;
	public static int COIN_ADENA;
	public static boolean FARM_SPOTS;
	public record EventOptions(boolean enabled, int intervalMinutes, int firstDelayMinutes, int minutes, int coins, int style) { }
	public static EventOptions FARM_EVENT;
	public static EventOptions DEFENCE_EVENT;
	public static EventOptions HUNT_EVENT;
	public static int START_GAP;
	public static int REGISTRATION;
	public static int ACTIVITY;
	public static int PLAYER_RESPAWN;
	public static boolean EVENT_HUNTERS;
	public static int FARM_ROOM;
	public static int FARM_MOBS;
	public static int FARM_RESPAWN;
	public static int FARM_SCORE_SECONDS;
	public static int FARM_RANK_FIRST;
	public static int FARM_RANK_LAST;
	public static boolean FARM_GOLDEN;
	public static int FARM_GOLDEN_MIN;
	public static int FARM_GOLDEN_MAX;
	public static int FARM_GOLDEN_LIFE;
	public static int FARM_GOLDEN_COINS;
	public static double FARM_GOLDEN_SPEED;
	public static boolean FARM_HERBS;
	public static int FARM_HERB_CHANCE;
	public static int FARM_HERB_SECONDS;
	public static int FARM_SPLASH_RADIUS;
	public static int FARM_SPLASH_PERCENT;
	public static int FARM_SPLASH_COOLDOWN;
	public static boolean FARM_BOSS;
	public static int FARM_BOSS_LAST;
	public static double FARM_BOSS_HP;
	public static int FARM_BOSS_COINS;
	public static boolean FARM_ARENA_RANDOM;
	public static int DEFENCE_WAVES;
	public static int DEFENCE_MAX_MOBS;
	public static int DEFENCE_FIRST_WAVE;
	public static int DEFENCE_WAVE_GROWTH;
	public static int DEFENCE_WAVE_CAP;
	public static int DEFENCE_MIN_REWARD;
	public static double DEFENCE_HIT_PERCENT;
	public static int DEFENCE_LEVEL_OFFSET;
	public static int DEFENCE_LEVEL_STEP;
	public static int DEFENCE_HP_GROWTH;
	public static int DEFENCE_PAUSE;
	public static int DEFENCE_WAVE_COINS;
	public static int DEFENCE_COIN_GROWTH;
	public static boolean DEFENCE_MODIFIERS;
	public static int DEFENCE_MODIFIER_CHANCE;
	public static double DEFENCE_ELITE_HP;
	public static double DEFENCE_ELITE_ATTACK;
	public static double DEFENCE_BOSS_HP;
	public static double DEFENCE_SPEED;
	public static boolean DEFENCE_GIRAN;
	public static boolean DEFENCE_ADEN;
	public static boolean DEFENCE_DION;
	public static double HUNT_HP;
	public static int HUNT_GROUP_BONUS;
	public static int HUNT_KILL_BONUS;
	public static int HUNT_BONUS_CAP;
	public static boolean HUNT_RETREAT;
	public static int HUNT_RETREAT_PERCENT;

	public static void load()
	{
		final ConfigReader config = new ConfigReader("./config/Custom/L2HubX10.ini");
		EVENTS_ENABLED = config.getBoolean("EventsEnabled", false);
		CONTRACTS_ENABLED = config.getBoolean("ContractsEnabled", false);
		COIN_DROP_ENABLED = config.getBoolean("CoinDropEnabled", false);
		ADENA_ALWAYS_DROP = config.getBoolean("AdenaAlwaysDrop", false);
		DAILY_COINS = Math.clamp(config.getInt("DailyCoins", 20), 0, 100000);
		DAILY_MIN_LEVEL = Math.clamp(config.getInt("DailyMinLevel", 40), 1, 85);
		WEEKLY_MIN_LEVEL = Math.clamp(config.getInt("WeeklyMinLevel", 50), DAILY_MIN_LEVEL, 85);
		WEEKLY_COINS = Math.clamp(config.getInt("WeeklyCoins", 60), 0, 100000);
		DROP_CHANCE = Math.clamp(config.getInt("CoinDropChance", 200), 0, 10000);
		EVENT_COINS = Math.clamp(config.getInt("EventCoins", 100), 0, 100000);
		EVENT_MINUTES = Math.clamp(config.getInt("EventMinutes", 7), 1, 30);
		COIN_ADENA = Math.max(1, config.getInt("ReferenceAdenaPerCoin", 200000));
		FARM_SPOTS = config.getBoolean("FarmSpotsEnabled", false);
		FARM_EVENT = event(config, "Farm", 120, 60);
		DEFENCE_EVENT = event(config, "Defence", 240, 120);
		HUNT_EVENT = event(config, "Hunt", 240, 240);
		START_GAP = Math.clamp(config.getInt("EventStartGapMinutes", 60), 1, 10080);
		REGISTRATION = Math.clamp(config.getInt("EventRegistrationMinutes", 10), 1, 30);
		ACTIVITY = Math.clamp(config.getInt("EventActivityPercent", 70), 1, 100);
		PLAYER_RESPAWN = Math.clamp(config.getInt("EventPlayerRespawnSeconds", 5), 1, 60);
		EVENT_HUNTERS = config.getBoolean("EventHuntersEnabled", true);
		FARM_ROOM = Math.clamp(config.getInt("FarmRoomSize", 18), 1, 36);
		FARM_MOBS = Math.clamp(config.getInt("FarmMonsterCount", 30), 1, 100);
		FARM_RESPAWN = Math.clamp(config.getInt("FarmRespawnSeconds", 5), 1, 120);
		FARM_SCORE_SECONDS = Math.clamp(config.getInt("FarmScoreSeconds", 15), 10, 60);
		FARM_RANK_FIRST = Math.clamp(config.getInt("FarmRankFirstBonus", 100), 0, 100000);
		FARM_RANK_LAST = Math.clamp(config.getInt("FarmRankLastBonus", 20), 0, 100000);
		FARM_GOLDEN = config.getBoolean("FarmGoldenEnabled", true);
		FARM_GOLDEN_MIN = Math.clamp(config.getInt("FarmGoldenMinSeconds", 30), 5, 300);
		FARM_GOLDEN_MAX = Math.clamp(config.getInt("FarmGoldenMaxSeconds", 60), 5, 300);
		FARM_GOLDEN_LIFE = Math.clamp(config.getInt("FarmGoldenLifetimeSeconds", 30), 5, 120);
		FARM_GOLDEN_COINS = Math.clamp(config.getInt("FarmGoldenCoins", 30), 0, 100000);
		FARM_GOLDEN_SPEED = Math.clamp(config.getDouble("FarmGoldenSpeedMultiplier", 1.8), 1, 4);
		FARM_HERBS = config.getBoolean("FarmHerbsEnabled", true);
		FARM_HERB_CHANCE = Math.clamp(config.getInt("FarmHerbChance", 8), 0, 100);
		FARM_HERB_SECONDS = Math.clamp(config.getInt("FarmHerbSeconds", 25), 5, 60);
		FARM_SPLASH_RADIUS = Math.clamp(config.getInt("FarmSplashRadius", 250), 50, 600);
		FARM_SPLASH_PERCENT = Math.clamp(config.getInt("FarmSplashPercent", 50), 1, 100);
		FARM_SPLASH_COOLDOWN = Math.clamp(config.getInt("FarmSplashCooldownSeconds", 2), 1, 10);
		FARM_BOSS = config.getBoolean("FarmBossEnabled", true);
		FARM_BOSS_LAST = Math.clamp(config.getInt("FarmBossLastSeconds", 60), 10, 120);
		FARM_BOSS_HP = Math.clamp(config.getDouble("FarmBossHpMultiplier", 8.0), 1, 100);
		FARM_BOSS_COINS = Math.clamp(config.getInt("FarmBossCoins", 50), 0, 100000);
		FARM_ARENA_RANDOM = config.getBoolean("FarmArenaRandom", false);
		FARM_GOLDEN_MAX = Math.max(FARM_GOLDEN_MIN, FARM_GOLDEN_MAX);
		FARM_RANK_LAST = Math.min(FARM_RANK_FIRST, FARM_RANK_LAST);
		DEFENCE_WAVES = Math.clamp(config.getInt("DefenceWaveCount", 10), 1, 30);
		DEFENCE_MAX_MOBS = Math.clamp(config.getInt("DefenceMaxMonsters", 80), 1, 150);
		DEFENCE_FIRST_WAVE = Math.clamp(config.getInt("DefenceFirstWaveSize", 3), 1, 100);
		DEFENCE_WAVE_GROWTH = Math.clamp(config.getInt("DefenceWaveGrowth", 2), 0, 30);
		DEFENCE_WAVE_CAP = Math.clamp(config.getInt("DefenceWaveCap", 30), 1, 100);
		DEFENCE_MIN_REWARD = Math.clamp(config.getInt("DefenceMinimumRewardPercent", 20), 0, 100);
		DEFENCE_HIT_PERCENT = Math.clamp(config.getDouble("DefenceHitPercent", 0.25), 0.01, 10);
		DEFENCE_LEVEL_OFFSET = Math.clamp(config.getInt("DefenceLevelOffset", 10), 0, 84);
		DEFENCE_LEVEL_STEP = Math.clamp(config.getInt("DefenceLevelStep", 3), 1, 10);
		DEFENCE_HP_GROWTH = Math.clamp(config.getInt("DefenceHpGrowthPercent", 20), 0, 200);
		DEFENCE_PAUSE = Math.clamp(config.getInt("DefenceWavePauseSeconds", 8), 3, 60);
		DEFENCE_WAVE_COINS = Math.clamp(config.getInt("DefenceWaveCoins", 5), 0, 100000);
		DEFENCE_COIN_GROWTH = Math.clamp(config.getInt("DefenceWaveCoinGrowth", 5), 0, 100000);
		DEFENCE_MODIFIERS = config.getBoolean("DefenceModifiersEnabled", true);
		DEFENCE_MODIFIER_CHANCE = Math.clamp(config.getInt("DefenceModifierChance", 40), 0, 100);
		DEFENCE_ELITE_HP = Math.clamp(config.getDouble("DefenceEliteHpMultiplier", 2.0), 1, 10);
		DEFENCE_ELITE_ATTACK = Math.clamp(config.getDouble("DefenceEliteAttackMultiplier", 1.5), 1, 5);
		DEFENCE_BOSS_HP = Math.clamp(config.getDouble("DefenceBossHpMultiplier", 5.0), 1, 30);
		DEFENCE_SPEED = Math.clamp(config.getDouble("DefenceFastSpeedMultiplier", 1.5), 1, 3);
		DEFENCE_GIRAN = config.getBoolean("DefenceGiranEnabled", true);
		DEFENCE_ADEN = config.getBoolean("DefenceAdenEnabled", true);
		DEFENCE_DION = config.getBoolean("DefenceDionEnabled", true);
		HUNT_HP = Math.clamp(config.getDouble("HuntHpMultiplier", 5), 1, 20);
		HUNT_GROUP_BONUS = Math.clamp(config.getInt("HuntGroupBonus", 25), 0, 10000);
		HUNT_KILL_BONUS = Math.clamp(config.getInt("HuntUniqueKillBonus", 5), 0, 10000);
		HUNT_BONUS_CAP = Math.clamp(config.getInt("HuntTargetBonusCap", 25), 0, 100000);
		HUNT_RETREAT = config.getBoolean("HuntRetreatEnabled", true);
		HUNT_RETREAT_PERCENT = Math.clamp(config.getInt("HuntRetreatHpPercent", 35), 1, 90);
	}

	private static EventOptions event(ConfigReader config, String prefix, int interval, int delay)
	{
		return new EventOptions(config.getBoolean(prefix + "Enabled", true),
			Math.clamp(config.getInt(prefix + "IntervalMinutes", interval), 5, 10080),
			Math.clamp(config.getInt(prefix + "FirstDelayMinutes", delay), 1, 10080),
			Math.clamp(config.getInt(prefix + "Minutes", EVENT_MINUTES), 1, 30),
			Math.clamp(config.getInt(prefix + "Coins", EVENT_COINS), 0, 100000),
			Math.clamp(config.getInt(prefix + "StyleReward", 1), 0, 1000));
	}

	private L2HubX10Config()
	{
	}
}
