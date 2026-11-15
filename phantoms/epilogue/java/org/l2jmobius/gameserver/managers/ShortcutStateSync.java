package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.ExAutoSoulShot;
import org.l2jmobius.gameserver.network.serverpackets.ShortcutInit;

/** Полная панель и действительное состояние автозарядки отправляются в одном порядке. */
public final class ShortcutStateSync
{
    private ShortcutStateSync() { }

    public static void send(Player player)
    {
        if (player == null)
        {
            return;
        }

        player.sendPacket(new ShortcutInit(player));
        for (int itemId : player.getAutoSoulShot())
        {
            player.sendPacket(new ExAutoSoulShot(itemId, 1));
        }
    }
}
