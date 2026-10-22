package org.l2jmobius.gameserver.config.custom;

import org.l2jmobius.commons.util.ConfigReader;

/**
 * L2Hub: настройки фантомов — персонажей игроков без клиента (config/Custom/Phantoms.ini).
 */
public class PhantomsConfig
{
	// File
	private static final String PHANTOMS_CONFIG_FILE = "./config/Custom/Phantoms.ini";

	// Constants
	public static boolean PHANTOMS_ENABLED;
	public static String PHANTOM_ACCOUNT;
	public static boolean PHANTOM_BAN_ACCOUNT_LOGIN;
	public static boolean PHANTOM_RESTORE_ON_STARTUP;
	public static int PHANTOM_DEFAULT_LEVEL;
	public static int PHANTOM_SHOT_COUNT;

	public static void load()
	{
		final ConfigReader config = new ConfigReader(PHANTOMS_CONFIG_FILE);
		PHANTOMS_ENABLED = config.getBoolean("EnablePhantoms", false);
		PHANTOM_ACCOUNT = config.getString("PhantomAccount", "l2hub_phantom");
		PHANTOM_BAN_ACCOUNT_LOGIN = config.getBoolean("PhantomBanAccountLogin", true);
		PHANTOM_RESTORE_ON_STARTUP = config.getBoolean("PhantomRestoreOnStartup", true);
		PHANTOM_DEFAULT_LEVEL = config.getInt("PhantomDefaultLevel", 40);
		PHANTOM_SHOT_COUNT = config.getInt("PhantomShotCount", 5000);
	}
}
