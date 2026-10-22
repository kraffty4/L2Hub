package handlers.chat.commands.voiced;

import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.PhantomUi;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * L2Hub: напарники-фантомы. Окно и кнопки — {@link PhantomUi} (то же окно — вкладка «Напарники» на доске Alt+B).
 * <ul>
 * <li>.buddy — окно: кто есть, где он, кнопки</li>
 * <li>.buddy summon &lt;имя|роль&gt; — позвать к себе, подтянуть до своего уровня и сразу взять в пати</li>
 * <li>.buddy invite &lt;имя|роль&gt; — взять в пати позванного (если сразу не вышло: ты не лидер, пати полная)</li>
 * <li>.buddy dismiss &lt;имя|роль&gt; — отпустить (уходит из мира)</li>
 * <li>.buddy assist|follow|stay|sit|stand|buff|heal|res &lt;имя|роль&gt; — приказ, как словами в чате пати: напарник выполнит и ответит</li>
 * </ul>
 */
public class Buddy implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"buddy"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			player.sendMessage("Напарники выключены (EnablePhantoms в Phantoms.ini).");
			return false;
		}

		if ((params != null) && !params.isBlank())
		{
			final String[] args = params.strip().split("\\s+");
			final String error = PhantomUi.handle(player, args[0], args.length > 1 ? args[1] : null);
			if (error != null)
			{
				player.sendMessage(error);
			}
		}

		player.sendPacket(new NpcHtmlMessage("<html><title>Напарники</title><body><center>" + PhantomUi.render(player, "bypass voice .buddy") + "</center></body></html>"));
		return true;
	}

	@Override
	public String[] getCommandList()
	{
		return VOICED_COMMANDS;
	}
}
