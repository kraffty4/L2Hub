package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * L2Hub: рейд охотников для живого игрока ({@link PhantomRaid}, раздел 4.32).
 * <ul>
 * <li>.raid — кто кого собирается бить, где сбор, сколько до боя, кто в пати</li>
 * <li>.raid join — вступить в пати рейда</li>
 * <li>.raid go — телепорт к точке сбора (выше MaxFreeTeleportLevel — за адену), с теми же запретами, что у доски: карма, бой, олимпиада, мирная зона</li>
 * </ul>
 * Регистрирует {@link PhantomRaid#control} при первом вызове — без врезки в {@code MasterHandler}.
 */
public class PhantomRaidCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"raid"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		final String arg = params == null ? "" : params.strip().toLowerCase();
		if (arg.equals("join"))
		{
			final String error = PhantomRaid.getInstance().join(player);
			if (error != null)
			{
				player.sendMessage(error);
			}
		}
		else if (arg.equals("go") && canTeleport(player))
		{
			final String error = PhantomRaid.getInstance().teleport(player);
			if (error == null)
			{
				return true;
			}
			player.sendMessage(error);
		}
		PhantomRaid.getInstance().show(player);
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
			player.sendMessage("Телепорт к рейду — только из мирной зоны.");
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
