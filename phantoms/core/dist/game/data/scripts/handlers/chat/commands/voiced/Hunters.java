package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterUi;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: охотники-фантомы для игроков — то же окно, что «Охотники» на доске Alt+B High Five ({@link PhantomHunterUi#board}),
 * но по команде: в Interlude нашей доски нет. Таблица широкая, поэтому показывается в окне доски.
 * <ul>
 * <li>.hunters [near|all|store] [страница] — кто из охотников где: на твоём уровне, все, в лавках</li>
 * <li>.hunters go &lt;имя&gt; [вкладка] [страница] — телепорт на спот охотника или к его лавке (с 40-го уровня — за адену)</li>
 * </ul>
 * Телепорт — с теми же запретами, что у доски ({@code CommunityBoard.ini}): карма, бой, олимпиада, осада, мирная зона.
 */
public class Hunters implements IVoicedCommandHandler
{
	private static final String BYPASS = "bypass voice .hunters";
	private static final String[] VOICED_COMMANDS =
	{
		"hunters"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			player.sendMessage(tr("Фантомы выключены (EnablePhantoms в Phantoms.ini)."));
			return false;
		}

		final String[] args = (params == null) || params.isBlank() ? new String[0] : params.strip().split("\\s+");
		int i = 0;
		if ((args.length > 1) && args[0].equalsIgnoreCase("go"))
		{
			if (canTeleport(player))
			{
				final String error = PhantomHunterUi.teleport(player, args[1]);
				if (error == null)
				{
					return true; // улетел — окно закрыто
				}
				player.sendMessage(error);
			}
			i = 2;
		}

		final String content = PhantomHunterUi.board(player, args.length > i ? args[i] : null, args.length > (i + 1) ? parseInt(args[i + 1]) : 0, BYPASS);
		CommunityBoardHandler.separateAndSend(tr("<html><body><br><center><font color=\"LEVEL\">Охотники</font><br><br>") + content + "</center></body></html>", player);
		return true;
	}

	private static boolean canTeleport(Player player)
	{
		if (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (PhantomCompat.karma(player) > 0))
		{
			player.sendMessage(tr("С кармой телепорт к охотникам закрыт."));
			return false;
		}

		if (CommunityBoardConfig.COMMUNITYBOARD_COMBAT_DISABLED && (player.isCastingNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isAlikeDead() || player.isOnEvent() || player.isInStoreMode()))
		{
			player.sendMessage(tr("Сейчас нельзя: ты в бою, мёртв или на событии."));
			return false;
		}

		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			player.sendMessage(tr("Телепорт к охотникам — только из мирной зоны."));
			return false;
		}
		return true;
	}

	private static int parseInt(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
