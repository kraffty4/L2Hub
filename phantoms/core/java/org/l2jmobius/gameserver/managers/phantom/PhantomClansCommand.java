package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;

/**
 * L2Hub: кланы охотников для живого игрока ({@link PhantomHunterClans}, раздел 4.50).
 * <ul>
 * <li>.clans — кланы охотников: уровень, члены, сколько в игре, глава, репутация, кто набирает</li>
 * <li>.clans reset — распустить все кланы охотников (только GM)</li>
 * </ul>
 * Регистрирует {@link PhantomHunterClans#control} при первом вызове — без врезки в {@code MasterHandler}.
 */
public class PhantomClansCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"clans"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		final String arg = params == null ? "" : params.strip().toLowerCase();
		if (arg.equals("reset"))
		{
			if (!player.isGM())
			{
				player.sendMessage("Распустить кланы охотников может только GM.");
				return true;
			}
			player.sendMessage("Распущено кланов охотников: " + PhantomHunterClans.getInstance().reset() + ".");
			return true;
		}
		for (String line : PhantomHunterClans.getInstance().describe(player))
		{
			player.sendMessage(line);
		}
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
