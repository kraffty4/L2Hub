package handlers.chat.commands.admin;

import org.l2jmobius.gameserver.data.xml.AdminData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IAdminCommandHandler;
import org.l2jmobius.gameserver.managers.PlayingGmPolicy;
import org.l2jmobius.gameserver.managers.PlayingGmState;
import org.l2jmobius.gameserver.managers.PlayingGmVision;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/** Режим обычной игры переключается для самого GM; админские права не снимаются. */
public class AdminPlayingGm implements IAdminCommandHandler
{
	private static final String COMMAND = "admin_playinggm";

	@Override
	public boolean onCommand(String command, Player player)
	{
		if ((player == null) || !player.isGM() || !AdminData.getInstance().hasAccess(COMMAND, player.getAccessLevel()))
		{
			return false;
		}
		if (command.equals(COMMAND + " on") || command.equals(COMMAND + " off"))
		{
			final PlayingGmState.ChangeResult result = PlayingGmState.change(player, command.endsWith(" on"));
			switch (result)
			{
				case CHANGED ->
				{
					PlayingGmVision.refresh(player);
					player.sendSysMessage(tr(player, "Режим сохранён для персонажа.", "Character mode saved."));
				}
				case DISABLED -> player.sendSysMessage(tr(player, "Режим выключен в настройках L2Hub.", "Disabled in L2Hub settings."));
				case OBSERVER -> player.sendSysMessage(tr(player, "Сначала выйдите из режима наблюдения.", "Leave observer mode first."));
				case SAVE_FAILED -> player.sendSysMessage(tr(player, "Не удалось сохранить режим. Переключение отменено.", "Could not save mode. Change cancelled."));
				default -> { return false; }
			}
		}
		else if (!command.equals(COMMAND))
		{
			return false;
		}

		final boolean playing = PlayingGmPolicy.isPlaying(player);
		final String status = !PlayingGmPolicy.isEnabled() ? tr(player, "Выключено в L2Hub", "Disabled in L2Hub")
			: playing ? tr(player, "Обычная игра с админкой", "Playing GM") : tr(player, "Полные игровые привилегии GM", "Full GM gameplay privileges");
		final String html = "<html><title>" + tr(player, "Играющий GM", "Playing GM") + "</title><body><center>"
			+ "<font color=LEVEL>" + status + "</font><br><br>"
			+ tr(player, "Команды и меню администратора доступны. В игровом режиме действуют обычные входы, откаты, бой и зрение.",
				"Admin commands and menus remain available. Playing mode uses normal access checks, reuse, combat and vision.")
			+ "<br><br><button value=\"" + tr(player, "Включить обычную игру", "Enable playing mode") + "\" action=\"bypass admin_playinggm on\" width=250 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">"
			+ "<button value=\"" + tr(player, "Вернуть привилегии GM", "Restore GM privileges") + "\" action=\"bypass admin_playinggm off\" width=250 height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"><br>"
			+ tr(player, "Явные тестовые команды (invul, невидимость, GM-бафы) остаются доступны. Повторное включение режима снимет их игровые эффекты.",
				"Explicit test commands (invulnerability, invisibility, GM buffs) remain available. Enable playing mode again to clear their effects.")
			+ "<br><button value=\"" + tr(player, "Назад", "Back") + "\" action=\"bypass admin_admin\" width=120 height=21 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\"></center></body></html>";
		final NpcHtmlMessage packet = new NpcHtmlMessage(0);
		packet.setHtml(html);
		player.sendPacket(packet);
		return true;
	}

	private static String tr(Player player, String ru, String en)
	{
		return "ru".equals(player.getLang()) ? ru : en;
	}

	@Override
	public String[] getCommandList()
	{
		return new String[] { COMMAND };
	}
}
