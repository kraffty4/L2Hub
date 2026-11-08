package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.config.custom.L2HubX10Config;

/** Читает настоящий файл настроек после записи через интерфейс, без запуска мира и БД. */
public final class X10ConfigCheck
{
	public static void main(String[] args)
	{
		L2HubX10Config.load();
		if (args.length > 0 && args[0].equals("custom"))
		{
			if (L2HubX10Config.FARM_EVENT.coins() != 125 || L2HubX10Config.DEFENCE_EVENT.coins() != 150 || L2HubX10Config.HUNT_EVENT.coins() != 175
				|| L2HubX10Config.FARM_EVENT.intervalMinutes() != 181 || L2HubX10Config.HUNT_EVENT.minutes() != 12
				|| L2HubX10Config.FARM_ROOM != 8 || L2HubX10Config.FARM_MOBS != 24 || L2HubX10Config.DEFENCE_WAVES != 8
				|| L2HubX10Config.DEFENCE_HIT_PERCENT != 0.3 || L2HubX10Config.HUNT_HP != 6.5 || L2HubX10Config.HUNT_GROUP_BONUS != 30
				|| L2HubX10Config.HUNT_KILL_BONUS != 4 || L2HubX10Config.HUNT_BONUS_CAP != 20 || L2HubX10Config.DROP_CHANCE != 125
				|| L2HubX10Config.DAILY_COINS != 25 || L2HubX10Config.WEEKLY_COINS != 75 || L2HubX10Config.ACTIVITY != 65)
			{ throw new AssertionError("GUI values did not reach the server config reader"); }
		}
		else if (L2HubX10Config.FARM_EVENT.intervalMinutes() != 120 || L2HubX10Config.START_GAP != 60 || L2HubX10Config.FARM_EVENT.coins() != 100
			|| L2HubX10Config.DEFENCE_EVENT.coins() != 100 || L2HubX10Config.HUNT_EVENT.coins() != 100 || L2HubX10Config.HUNT_HP != 5
			|| L2HubX10Config.FARM_EVENT.minutes() != 7 || L2HubX10Config.DEFENCE_EVENT.minutes() != 7 || L2HubX10Config.HUNT_EVENT.minutes() != 7
			|| L2HubX10Config.REGISTRATION != 10 || L2HubX10Config.DAILY_MIN_LEVEL != 40 || L2HubX10Config.WEEKLY_MIN_LEVEL != 50)
		{ throw new AssertionError("Default settings changed"); }
		System.out.println("X10 server config reader: " + (args.length > 0 ? args[0] : "defaults") + " passed");
	}
}
