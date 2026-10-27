package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;

/**
 * L2Hub: окно поручений дня ({@link WorldDaily}).
 * <ul>
 * <li>.daily — задания на сегодня, прогресс и награды</li>
 * <li>.daily go — телепорт на «Место дня» (выше MaxFreeTeleportLevel — за адену), с теми же запретами, что у доски ({@code CommunityBoard.ini}): карма, бой, олимпиада, мирная зона</li>
 * </ul>
 * Регистрирует {@link WorldDaily#start} — без врезки в {@code MasterHandler}.
 */
public class WorldDailyCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"daily"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (!WorldDaily.getInstance().isEnabled())
		{
			player.sendMessage("Поручения дня выключены (WorldDaily в L2HubWorld.ini).");
			return false;
		}

		if ((params != null) && params.strip().equalsIgnoreCase("go") && canTeleport(player))
		{
			final String error = WorldDaily.getInstance().teleport(player);
			if (error == null)
			{
				return true;
			}
			player.sendMessage(error);
		}
		WorldDaily.getInstance().show(player);
		return true;
	}

	private static boolean canTeleport(Player player)
	{
		if (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (PhantomCompat.karma(player) > 0))
		{
			player.sendMessage("С кармой телепорт закрыт.");
			return false;
		}

		if (CommunityBoardConfig.COMMUNITYBOARD_COMBAT_DISABLED && (player.isCastingNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isAlikeDead() || player.isOnEvent() || player.isInStoreMode()))
		{
			player.sendMessage("Сейчас нельзя: ты в бою, мёртв или на событии.");
			return false;
		}

		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			player.sendMessage("Телепорт на место дня — только из мирной зоны.");
			return false;
		}
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
