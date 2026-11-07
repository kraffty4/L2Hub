package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.buylist.BuyListHolder;

/** Dedicated sell/refund window, with no purchasable products or dependency on the legacy 423 shop. */
public final class CommunityLootSell
{
	public static final int LIST_ID = 50049997;
	private static final BuyListHolder LIST = new BuyListHolder(LIST_ID);

	private CommunityLootSell()
	{
	}

	public static boolean isList(int id)
	{
		return id == LIST_ID;
	}

	public static BuyListHolder getList()
	{
		return LIST;
	}

	/** Check again when processing sell/refund packets; opening a board is not a transaction permit. */
	public static boolean canUse(Player player)
	{
		if ((player == null) || !player.isOnline() || !CommunityBoardConfig.CUSTOM_CB_ENABLED
			|| !CommunityBoardConfig.COMMUNITYBOARD_ENABLE_MULTISELLS)
		{
			return false;
		}
		if (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (player.getKarma() > 0))
		{
			return false;
		}
		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		return !CommunityBoardConfig.COMMUNITYBOARD_COMBAT_DISABLED || !(player.isCastingNow() || player.isInCombat()
			|| player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE)
			|| player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isAlikeDead()
			|| player.isOnEvent() || player.isInStoreMode());
	}
}

