package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * L2Hub: фантомы в пати — опыт, лут и поведение напарника (config/Custom/PhantomParty.ini).
 */
public class PhantomPartyConfig
{
	// File
	private static final String PHANTOM_PARTY_CONFIG_FILE = "./config/Custom/PhantomParty.ini";

	// Constants
	public static boolean PHANTOM_PARTY_EXP_SHARE;
	public static boolean PHANTOM_PARTY_LOOT_SHARE;
	public static int PHANTOM_CATCH_UP_RANGE;
	public static int PHANTOM_ASSIST_RANGE;
	public static int PHANTOM_DEFEND_RANGE;
	public static int PHANTOM_OWNER_LOGOUT_GRACE;
	public static boolean PHANTOM_BRAIN;
	public static int PHANTOM_EVENT_COOLDOWN;
	public static int PHANTOM_EVENTS_PER_MINUTE;
	public static boolean PHANTOM_GENERAL_CHAT;
	public static int PHANTOM_OVERHEAR_CHANCE;
	public static int PHANTOM_OVERHEAR_COOLDOWN;
	public static int PHANTOM_OVERHEAR_PLAYER_COOLDOWN;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PHANTOM_PARTY_CONFIG_FILE);
		PHANTOM_PARTY_EXP_SHARE = config.getBoolean("PhantomPartyExpShare", false);
		PHANTOM_PARTY_LOOT_SHARE = config.getBoolean("PhantomPartyLootShare", false);
		PHANTOM_CATCH_UP_RANGE = config.getInt("PhantomCatchUpRange", 2000);
		PHANTOM_ASSIST_RANGE = config.getInt("PhantomAssistRange", 1500);
		PHANTOM_DEFEND_RANGE = config.getInt("PhantomDefendRange", 900);
		PHANTOM_OWNER_LOGOUT_GRACE = config.getInt("PhantomOwnerLogoutGrace", 60);
		PHANTOM_BRAIN = config.getBoolean("PhantomBrain", true);
		PHANTOM_EVENT_COOLDOWN = config.getInt("PhantomEventCooldown", 60);
		PHANTOM_EVENTS_PER_MINUTE = config.getInt("PhantomEventsPerMinute", 6);
		PHANTOM_GENERAL_CHAT = config.getBoolean("PhantomGeneralChat", true);
		PHANTOM_OVERHEAR_CHANCE = config.getInt("PhantomOverhearChance", 25);
		PHANTOM_OVERHEAR_COOLDOWN = config.getInt("PhantomOverhearCooldown", 90);
		PHANTOM_OVERHEAR_PLAYER_COOLDOWN = config.getInt("PhantomOverhearPlayerCooldown", 30);
	}
}
