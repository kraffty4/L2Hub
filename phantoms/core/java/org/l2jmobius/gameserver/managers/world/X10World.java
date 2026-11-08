package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Точки подключения x10. High Five подставляет свою реализацию поверх этого файла. */
public final class X10World
{
	static void start()
	{
	}

	public static boolean enabled()
	{
		return false;
	}

	public static boolean contractsEnabled() { return false; }

	public static String board(Player player, String[] args)
	{
		return null;
	}

	public static void showContracts(Player player, boolean weekly, String params)
	{
	}

	public static boolean inPveEvent(Player player)
	{
		return false;
	}

	public static Creature pveTarget(Player player)
	{
		return null;
	}

	private X10World()
	{
	}
}
