package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: кланы охотников для живого игрока ({@link PhantomHunterClans}, раздел 4.50).
 * <ul>
 * <li>.clans — кланы охотников: уровень, члены, сколько в игре, глава, репутация, кто набирает</li>
 * <li>.clans reset — распустить все кланы охотников (только GM)</li>
 * <li>.clans top [страница] — все кланы сервера по силе ({@link PhantomClanPower#topPage})</li>
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
				player.sendMessage(tr("Распустить кланы охотников может только GM."));
				return true;
			}
			player.sendMessage(tr("Распущено кланов охотников: ") + PhantomHunterClans.getInstance().reset() + ".");
			return true;
		}
		if (arg.equals("top") || arg.startsWith("top "))
		{
			if (!PhantomHuntersConfig.HUNTER_CLAN_TOP)
			{
				player.sendMessage(tr("Топ кланов выключен в настройках сервера (HunterClanTop в PhantomHunters.ini)."));
				return true;
			}
			int page = 0;
			try
			{
				page = arg.length() > 4 ? Integer.parseInt(arg.substring(4).strip()) : 0;
			}
			catch (NumberFormatException e)
			{
				page = 0;
			}
			player.sendPacket(new NpcHtmlMessage(PhantomClanPower.getInstance().topPage(player, page)));
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
