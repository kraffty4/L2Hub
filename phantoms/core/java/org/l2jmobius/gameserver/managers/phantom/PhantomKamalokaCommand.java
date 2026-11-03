package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: поход охотников в Камалоку для живого игрока ({@link PhantomKamaloka}).
 * <ul>
 * <li>.kama (и .kamaloka) — статус похода: зал, лидер, сколько в пати, до входа</li>
 * <li>.kama join — вступить в пати похода</li>
 * <li>.kama go — телепорт к лидеру пати, с теми же запретами, что у доски: карма, бой, олимпиада, мирная зона</li>
 * </ul>
 */
public class PhantomKamalokaCommand implements IVoicedCommandHandler
{
	private static final String[] VOICED_COMMANDS =
	{
		"kama",
		"kamaloka"
	};

	@Override
	public boolean onCommand(String command, Player player, String params)
	{
		final String arg = params == null ? "" : params.strip().toLowerCase();
		if (arg.equals("join"))
		{
			final String error = PhantomKamaloka.getInstance().join(player);
			if (error != null)
			{
				player.sendMessage(error);
			}
		}
		else if (arg.equals("go") && canTeleport(player))
		{
			final String error = PhantomKamaloka.getInstance().teleport(player);
			if (error == null)
			{
				return true;
			}
			player.sendMessage(error);
		}
		PhantomKamaloka.getInstance().show(player);
		return true;
	}

	private static boolean canTeleport(Player player)
	{
		if (CommunityBoardConfig.COMMUNITYBOARD_KARMA_DISABLED && (PhantomCompat.karma(player) > 0))
		{
			player.sendMessage(tr("С кармой телепорт закрыт."));
			return false;
		}

		// Бой, олимпиада, осада — всегда: иначе телепорт становится бесплатным побегом из драки (мирная зона — по настройке доски).
		if ((player.isCastingNow() || player.isInCombat() || player.isInDuel() || player.isInOlympiadMode() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || player.isAlikeDead() || player.isOnEvent() || player.isInStoreMode()))
		{
			player.sendMessage(tr("Сейчас нельзя: ты в бою, мёртв или на событии."));
			return false;
		}

		if (CommunityBoardConfig.COMMUNITYBOARD_PEACE_ONLY && !player.isInsideZone(ZoneId.PEACE))
		{
			player.sendMessage(tr("Телепорт к пати Камалоки — только из мирной зоны."));
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