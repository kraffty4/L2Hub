package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.config.custom.L2HubX10Config;

/** Нативный parser: отключение боёв не отключает задания/дроп/фарм; legacy Enabled не является зависимостью. */
public final class X10IndependentFeaturesCheck
{
	public static void main(String[] args)
	{
		L2HubX10Config.load();
		if (L2HubX10Config.EVENTS_ENABLED || !L2HubX10Config.CONTRACTS_ENABLED || !L2HubX10Config.COIN_DROP_ENABLED || !L2HubX10Config.FARM_SPOTS)
		{ throw new AssertionError("Independent feature flags were coupled"); }
		if (L2HubX10Config.FARM_EVENT.coins() != 73 || L2HubX10Config.DAILY_COINS != 11)
		{ throw new AssertionError("Custom rate-mode rewards were not loaded"); }
		System.out.println("Independent H5 features: battles off; contracts, coin drop and farm on; custom rewards read");
	}
}
