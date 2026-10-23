package org.l2jmobius.gameserver.config.custom;

import java.util.Arrays;
import java.util.NavigableMap;
import java.util.TreeMap;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * L2Hub: охотники-фантомы (config/Custom/PhantomHunters.ini). Сервер перечитывает файл на ходу, когда он меняется: режим и сброс прогресса ставит лаунчер L2Hub.
 */
public class PhantomHuntersConfig
{
	// File
	public static final String PHANTOM_HUNTERS_CONFIG_FILE = "./config/Custom/PhantomHunters.ini";

	// Constants
	public static String HUNTERS_MODE;
	public static String HUNTERS_RESET;
	public static int HUNTERS_RESET_ID;
	public static String HUNTERS_SELF_TEST;
	public static int HUNTERS_SELF_TEST_ID;
	public static int HUNTERS_IDLE_TIMEOUT;
	public static int HUNTER_RESPAWN_DELAY;
	public static int HUNTER_SHOT_COUNT;
	public static int HUNTER_SHOT_BUDGET;
	public static int HUNTERS_PER_SPOT;
	public static int HUNTER_AMMO_COUNT;
	public static int HUNTER_POTION_COUNT;
	public static int HUNTER_TELEPORT_FEE;
	public static int HUNTER_TOWN_INTERVAL;
	public static NavigableMap<Integer, Long> HUNTER_STORE_MIN_PRICES;
	public static float HUNTER_DROP_CHANCE_MULTIPLIER;
	public static NavigableMap<Integer, Long> HUNTER_PROFESSION_PRICES;
	public static boolean HUNTER_QUESTS;
	public static int[] HUNTER_QUEST_LEVELS;
	public static int HUNTER_QUEST_MIN_MINUTES;
	public static int HUNTER_QUEST_MAX_MINUTES;
	public static int HUNTER_QUEST_REWARD;
	public static int HUNTER_MAX_STORES;
	public static int HUNTER_STORE_MIN_MINUTES;
	public static int HUNTER_STORE_MAX_MINUTES;
	public static boolean HUNTER_BUY_STORES;
	public static boolean HUNTER_SELL_OLD_GEAR;
	public static boolean HUNTER_RAIDS;
	public static int HUNTER_RAID_FIRST;
	public static int[] HUNTER_RAID_INTERVAL;
	public static int HUNTER_RAID_GATHER;
	public static int HUNTER_RAID_MIN;
	public static int HUNTER_RAID_MAX;
	public static int HUNTER_RAID_BOSS_LEVEL_MIN;
	public static int HUNTER_RAID_BOSS_LEVEL_MAX;
	public static float HUNTER_RAID_DAMAGE;
	public static float HUNTER_RAID_ARMOR;
	public static boolean HUNTER_EPIC_RAIDS;
	public static int HUNTER_EPIC_MIN;
	public static int HUNTER_EPIC_MAX;
	public static float HUNTER_EPIC_DAMAGE;
	public static float HUNTER_EPIC_ARMOR;
	public static boolean HUNTER_PVP_EVENTS;
	public static int HUNTER_PVP_EVENT_MAX;
	public static int HUNTER_PVP_EVENT_CHANCE;
	public static boolean HUNTER_SEASONAL_EVENTS;
	public static boolean HUNTER_SUPER_CHAMPIONS;
	public static boolean HUNTER_OLYMPIAD;
	public static int HUNTER_OLYMPIAD_LEVEL;
	public static int HUNTER_OLYMPIAD_MAX;
	public static int HUNTER_OLYMPIAD_CHANCE;
	public static boolean HUNTER_DUELS;
	public static int HUNTER_DUEL_RIVAL_CHANCE;
	public static boolean HUNTER_SEVEN_SIGNS;
	public static int HUNTER_SEVEN_SIGNS_LEVEL;
	public static int HUNTER_SEVEN_SIGNS_STONE_CHANCE;
	public static boolean HUNTER_LFM;
	public static boolean HUNTER_STRATEGY;
	public static String HUNTER_STRATEGY_WHEN;
	public static int HUNTER_STRATEGY_PER_HOUR;
	public static int HUNTER_STRATEGY_PER_MINUTE;
	public static int HUNTER_STRATEGY_SAY_CHANCE;
	public static boolean HUNTER_INITIATIVE;
	public static int HUNTER_INITIATIVE_RANGE;
	public static int HUNTER_INITIATIVE_CHANCE_KNOWN;
	public static int HUNTER_INITIATIVE_CHANCE_STRANGER;
	public static int HUNTER_INITIATIVE_HUNTER_COOLDOWN;
	public static int HUNTER_INITIATIVE_PAIR_COOLDOWN;
	public static int HUNTER_INITIATIVE_PLAYER_GAP;
	public static int HUNTER_INITIATIVE_PLAYER_PER_HOUR;
	public static int HUNTER_INITIATIVE_ANSWER_WAIT;
	public static int HUNTER_INITIATIVE_RECENT_TALK;
	public static boolean HUNTER_MOOD;
	public static int HUNTER_CHAT_QUIET;
	public static int HUNTER_CHAT_SPEAKER_GAP;
	public static int HUNTER_CHAT_TOPIC_GAP;
	public static boolean HUNTER_RELATIONS;
	public static int HUNTER_RELATION_HALF_LIFE;
	public static int HUNTER_RELATION_ENEMY;
	public static int HUNTER_REVIVE_WAIT;
	public static boolean HUNTER_NEIGHBORS;
	public static boolean HUNTER_BANTER;
	public static int HUNTER_BANTER_CHANCE;
	public static int HUNTER_BANTER_COOLDOWN;
	public static boolean HUNTER_PARTIES;
	public static int HUNTER_PARTY_MAX;
	public static int HUNTER_PARTY_CHANCE;
	public static int HUNTER_PARTY_CHANCE_FRIEND;
	public static int[] HUNTER_PARTY_MINUTES;
	public static boolean HUNTER_BRAWLS;
	public static int HUNTER_BRAWL_GRUDGE;
	public static int HUNTER_BRAWL_CHANCE;
	public static int HUNTER_BRAWL_COOLDOWN;
	public static int HUNTER_BRAWL_LEVEL_GAP;
	public static boolean HUNTER_BRAWL_WITNESS;
	public static int HUNTER_CONTEST_RUDE_CHANCE;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PHANTOM_HUNTERS_CONFIG_FILE);
		HUNTERS_MODE = config.getString("HuntersMode", "ALWAYS").strip().toUpperCase();
		HUNTERS_RESET = config.getString("HuntersReset", "").strip();
		HUNTERS_RESET_ID = config.getInt("HuntersResetId", 0);
		HUNTERS_SELF_TEST = config.getString("HuntersSelfTest", "").strip();
		HUNTERS_SELF_TEST_ID = config.getInt("HuntersSelfTestId", 0);
		HUNTERS_IDLE_TIMEOUT = config.getInt("HuntersIdleTimeout", 300);
		HUNTER_RESPAWN_DELAY = config.getInt("HunterRespawnDelay", 20);
		HUNTER_SHOT_COUNT = config.getInt("HunterShotCount", 2000);
		HUNTER_SHOT_BUDGET = Math.max(0, Math.min(100, config.getInt("HunterShotBudget", 30)));
		HUNTERS_PER_SPOT = config.getInt("HuntersPerSpot", 2);
		HUNTER_AMMO_COUNT = config.getInt("HunterAmmoCount", 3000);
		HUNTER_POTION_COUNT = config.getInt("HunterPotionCount", 30);
		HUNTER_TELEPORT_FEE = config.getInt("HunterTeleportFee", 15000);
		HUNTER_TOWN_INTERVAL = config.getInt("HunterTownInterval", 120);
		HUNTER_STORE_MIN_PRICES = levelMap(config.getString("HunterStoreMinPrices", "1:2000;20:5000;40:20000;52:50000;61:100000;76:300000"));
		HUNTER_DROP_CHANCE_MULTIPLIER = Math.max(0, config.getFloat("HunterDropChanceMultiplier", 2));
		HUNTER_PROFESSION_PRICES = levelMap(config.getString("HunterProfessionPrices", "20:50000;40:1000000;76:10000000"));
		HUNTER_QUESTS = config.getBoolean("HunterQuests", true);
		HUNTER_QUEST_LEVELS = levels(config.getString("HunterQuestLevels", "6,10,14,17,20,23,26,30,34,38,42,46,50,55,60,65,70,75,80"));
		final int[] minutes = levels(config.getString("HunterQuestMinutes", "15,25"));
		HUNTER_QUEST_MIN_MINUTES = minutes.length > 0 ? Math.max(1, minutes[0]) : 15;
		HUNTER_QUEST_MAX_MINUTES = Math.max(HUNTER_QUEST_MIN_MINUTES, minutes.length > 1 ? minutes[1] : HUNTER_QUEST_MIN_MINUTES);
		HUNTER_QUEST_REWARD = Math.max(0, config.getInt("HunterQuestReward", 40));
		HUNTER_MAX_STORES = config.getInt("HunterMaxStores", 4);
		HUNTER_STORE_MIN_MINUTES = config.getInt("HunterStoreMinMinutes", 20);
		HUNTER_STORE_MAX_MINUTES = Math.max(HUNTER_STORE_MIN_MINUTES, config.getInt("HunterStoreMaxMinutes", 40));
		HUNTER_BUY_STORES = config.getBoolean("HunterBuyStores", true);
		HUNTER_SELL_OLD_GEAR = config.getBoolean("HunterSellOldGear", true);
		HUNTER_RAIDS = config.getBoolean("HunterRaids", true);
		HUNTER_RAID_FIRST = Math.max(1, config.getInt("HunterRaidFirst", 30));
		HUNTER_RAID_INTERVAL = levels(config.getString("HunterRaidInterval", "60,120"));
		HUNTER_RAID_GATHER = Math.max(1, config.getInt("HunterRaidGather", 4));
		final int[] raidSize = levels(config.getString("HunterRaidSize", "3,5"));
		HUNTER_RAID_MIN = Math.max(2, raidSize.length > 0 ? raidSize[0] : 3);
		HUNTER_RAID_MAX = Math.max(HUNTER_RAID_MIN, Math.min(9, raidSize.length > 1 ? raidSize[1] : 5));
		final String[] bossLevel = config.getString("HunterRaidBossLevel", "-8,-4").split(",");
		HUNTER_RAID_BOSS_LEVEL_MIN = signed(bossLevel, 0, -8);
		HUNTER_RAID_BOSS_LEVEL_MAX = Math.max(HUNTER_RAID_BOSS_LEVEL_MIN, signed(bossLevel, 1, -4));
		HUNTER_RAID_DAMAGE = Math.max(1, config.getFloat("HunterRaidDamage", 4));
		HUNTER_RAID_ARMOR = Math.max(1, config.getFloat("HunterRaidArmor", 5));
		HUNTER_EPIC_RAIDS = config.getBoolean("HunterEpicRaids", true);
		final int[] epicSize = levels(config.getString("HunterEpicSize", "5,9"));
		HUNTER_EPIC_MIN = Math.max(2, epicSize.length > 0 ? epicSize[0] : 5);
		HUNTER_EPIC_MAX = Math.max(HUNTER_EPIC_MIN, Math.min(9, epicSize.length > 1 ? epicSize[1] : 9));
		HUNTER_EPIC_DAMAGE = Math.max(1, config.getFloat("HunterEpicDamage", 4));
		HUNTER_EPIC_ARMOR = Math.max(1, config.getFloat("HunterEpicArmor", 8));
		HUNTER_PVP_EVENTS = config.getBoolean("HunterPvpEvents", true);
		HUNTER_PVP_EVENT_MAX = Math.max(0, config.getInt("HunterPvpEventMax", 12));
		HUNTER_PVP_EVENT_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterPvpEventChance", 30)));
		HUNTER_SEASONAL_EVENTS = config.getBoolean("HunterSeasonalEvents", true);
		HUNTER_SUPER_CHAMPIONS = config.getBoolean("HunterSuperChampions", true);
		HUNTER_OLYMPIAD = config.getBoolean("HunterOlympiad", true);
		HUNTER_OLYMPIAD_LEVEL = Math.max(1, config.getInt("HunterOlympiadLevel", 76));
		HUNTER_OLYMPIAD_MAX = Math.max(0, config.getInt("HunterOlympiadMax", 10));
		HUNTER_OLYMPIAD_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterOlympiadChance", 30)));
		HUNTER_DUELS = config.getBoolean("HunterDuels", true);
		HUNTER_DUEL_RIVAL_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterDuelRivalChance", 5)));
		HUNTER_SEVEN_SIGNS = config.getBoolean("HunterSevenSigns", true);
		HUNTER_SEVEN_SIGNS_LEVEL = Math.max(1, config.getInt("HunterSevenSignsLevel", 20));
		HUNTER_SEVEN_SIGNS_STONE_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterSevenSignsStoneChance", 5)));
		HUNTER_LFM = config.getBoolean("HunterLfm", true);
		HUNTER_STRATEGY = config.getBoolean("HunterStrategy", true);
		HUNTER_STRATEGY_WHEN = config.getString("HunterStrategyWhen", "WITH_PLAYERS").strip().toUpperCase();
		HUNTER_STRATEGY_PER_HOUR = Math.max(1, config.getInt("HunterStrategyPerHour", 6));
		HUNTER_STRATEGY_PER_MINUTE = config.getInt("HunterStrategyPerMinute", 6);
		HUNTER_STRATEGY_SAY_CHANCE = config.getInt("HunterStrategySayChance", 20);
		HUNTER_INITIATIVE = config.getBoolean("HunterInitiative", true);
		HUNTER_INITIATIVE_RANGE = Math.max(100, config.getInt("HunterInitiativeRange", 600));
		HUNTER_INITIATIVE_CHANCE_KNOWN = Math.max(0, Math.min(100, config.getInt("HunterInitiativeChanceKnown", 30)));
		HUNTER_INITIATIVE_CHANCE_STRANGER = Math.max(0, Math.min(100, config.getInt("HunterInitiativeChanceStranger", 4)));
		HUNTER_INITIATIVE_HUNTER_COOLDOWN = Math.max(0, config.getInt("HunterInitiativeHunterCooldown", 1800));
		HUNTER_INITIATIVE_PAIR_COOLDOWN = Math.max(0, config.getInt("HunterInitiativePairCooldown", 21600));
		HUNTER_INITIATIVE_PLAYER_GAP = Math.max(0, config.getInt("HunterInitiativePlayerGap", 600));
		HUNTER_INITIATIVE_PLAYER_PER_HOUR = Math.max(0, config.getInt("HunterInitiativePlayerPerHour", 3));
		HUNTER_INITIATIVE_ANSWER_WAIT = Math.max(10, config.getInt("HunterInitiativeAnswerWait", 120));
		HUNTER_INITIATIVE_RECENT_TALK = Math.max(0, config.getInt("HunterInitiativeRecentTalk", 1800));
		HUNTER_MOOD = config.getBoolean("HunterMood", true);
		HUNTER_CHAT_QUIET = Math.max(0, config.getInt("HunterChatQuiet", 45));
		HUNTER_CHAT_SPEAKER_GAP = Math.max(0, config.getInt("HunterChatSpeakerGap", 120));
		HUNTER_CHAT_TOPIC_GAP = Math.max(0, config.getInt("HunterChatTopicGap", 300));
		HUNTER_RELATIONS = config.getBoolean("HunterRelations", true);
		HUNTER_RELATION_HALF_LIFE = Math.max(0, config.getInt("HunterRelationHalfLife", 72));
		HUNTER_RELATION_ENEMY = Math.max(1, config.getInt("HunterRelationEnemy", 3));
		HUNTER_REVIVE_WAIT = Math.max(0, config.getInt("HunterReviveWait", 60));
		HUNTER_NEIGHBORS = config.getBoolean("HunterNeighbors", true);
		HUNTER_BANTER = config.getBoolean("HunterBanter", true);
		HUNTER_BANTER_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterBanterChance", 30)));
		HUNTER_BANTER_COOLDOWN = Math.max(0, config.getInt("HunterBanterCooldown", 1800));
		HUNTER_PARTIES = config.getBoolean("HunterParties", true);
		HUNTER_PARTY_MAX = Math.max(2, Math.min(9, config.getInt("HunterPartyMax", 3)));
		HUNTER_PARTY_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterPartyChance", 25)));
		HUNTER_PARTY_CHANCE_FRIEND = Math.max(0, Math.min(100, config.getInt("HunterPartyChanceFriend", 80)));
		HUNTER_PARTY_MINUTES = levels(config.getString("HunterPartyMinutes", "20,60"));
		HUNTER_BRAWLS = config.getBoolean("HunterBrawls", true);
		HUNTER_BRAWL_GRUDGE = Math.max(1, config.getInt("HunterBrawlGrudge", 5));
		HUNTER_BRAWL_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterBrawlChance", 50)));
		HUNTER_BRAWL_COOLDOWN = Math.max(0, config.getInt("HunterBrawlCooldown", 3600));
		HUNTER_BRAWL_LEVEL_GAP = Math.max(0, config.getInt("HunterBrawlLevelGap", 3));
		HUNTER_BRAWL_WITNESS = config.getBoolean("HunterBrawlWitness", true);
		HUNTER_CONTEST_RUDE_CHANCE = Math.max(0, Math.min(100, config.getInt("HunterContestRudeChance", 40)));
	}

	/**
	 * «уровень:значение;уровень:значение» → уровень → значение; кривые пары пропускаются.
	 */
	private static NavigableMap<Integer, Long> levelMap(String value)
	{
		final NavigableMap<Integer, Long> map = new TreeMap<>();
		for (String pair : value.split(";"))
		{
			final String[] parts = pair.strip().split(":");
			if (parts.length == 2)
			{
				try
				{
					map.put(Integer.parseInt(parts[0].strip()), Long.parseLong(parts[1].strip()));
				}
				catch (NumberFormatException e)
				{
					// пропуск
				}
			}
		}
		return map;
	}

	/**
	 * «6,10,14» → по возрастанию; не числа пропускаются.
	 */
	private static int signed(String[] parts, int index, int fallback)
	{
		try
		{
			return Integer.parseInt(parts[index].strip());
		}
		catch (Exception e)
		{
			return fallback;
		}
	}

	private static int[] levels(String value)
	{
		return Arrays.stream(value.split(",")).map(String::strip).filter(s -> s.matches("\\d+")).mapToInt(Integer::parseInt).sorted().toArray();
	}
}
