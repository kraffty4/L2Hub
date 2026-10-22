package handlers.chat.commands.admin;

import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterUi;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * L2Hub: окно охотников для GM ({@link PhantomHunterUi}).
 * <ul>
 * <li>//hunters [all|near|store|hunt|town|party] [level|name|near] [страница] — кто где: что делает, адена, доход в час, квест, расстояние</li>
 * <li>//hunters goto &lt;имя&gt; … — телепорт к охотнику, он становится целью; окно остаётся на той же вкладке</li>
 * </ul>
 */
public class AdminHunters implements IAdminCommandHandler
{
	private static final String[] ADMIN_COMMANDS =
	{
		"admin_hunters"
	};

	@Override
	public boolean onCommand(String command, Player activeChar)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			activeChar.sendSysMessage("Phantoms are disabled: EnablePhantoms in config/Custom/Phantoms.ini.");
			return false;
		}

		activeChar.sendPacket(new NpcHtmlMessage(PhantomHunterUi.admin(activeChar, command.strip().split("\\s+"))));
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return ADMIN_COMMANDS;
	}
}
