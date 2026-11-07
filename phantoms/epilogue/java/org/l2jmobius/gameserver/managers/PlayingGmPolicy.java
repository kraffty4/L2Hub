package org.l2jmobius.gameserver.managers;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * Игровые привилегии отделены от роли администратора. Player.isGM() и проверка
 * доступа к явным командам администратора намеренно остаются штатными.
 */
public final class PlayingGmPolicy
{
	public static final String MODE_VARIABLE = "L2HubPlayingGm";
	private static final String CONFIG_FILE = "./config/Custom/PlayingGm.ini";

	private PlayingGmPolicy()
	{
	}

	/** Читается один раз при первом обращении; параметры сервера применяются после перезапуска. */
	private static final class Settings
	{
		private static final ConfigReader CONFIG = new ConfigReader(CONFIG_FILE);
		private static final boolean ENABLED = CONFIG.getBoolean("PlayingGmEnabled", true);
		private static final boolean DEFAULT_MODE = CONFIG.getBoolean("PlayingGmDefaultMode", false);
	}

	public static boolean isEnabled()
	{
		return Settings.ENABLED;
	}

	public static boolean isPlaying(Player player)
	{
		return (player != null) && Settings.ENABLED && player.isGM()
			&& player.getVariables().getBoolean(MODE_VARIABLE, Settings.DEFAULT_MODE);
	}

	/** Замена только неявного actor.isGM() в игровых правилах, не в защите админских команд. */
	public static boolean hasGameplayBypass(Creature actor)
	{
		return (actor != null) && actor.isGM() && (!actor.isPlayer() || !isPlaying(actor.asPlayer()));
	}

	/** Сохраняет прежний ACL для остальных персонажей и отключает привилегию только играющему GM. */
	public static boolean allowsPrivilege(Player player, boolean nativePermission)
	{
		return nativePermission && !isPlaying(player);
	}

	/** Запреты staff-ACL не переносятся в обычную игру; прочие уровни доступа не меняются. */
	public static boolean canTakeAggro(Player player)
	{
		return isPlaying(player) || player.getAccessLevel().canTakeAggro();
	}

	public static boolean canGainExp(Player player)
	{
		return isPlaying(player) || player.getAccessLevel().canGainExp();
	}

	public static boolean canTrade(Player player)
	{
		return isPlaying(player) || player.getAccessLevel().allowTransaction();
	}

	public static boolean canGiveDamage(Creature actor)
	{
		return !actor.isPlayer() || isPlaying(actor.asPlayer()) || actor.getAccessLevel().canGiveDamage();
	}
}
