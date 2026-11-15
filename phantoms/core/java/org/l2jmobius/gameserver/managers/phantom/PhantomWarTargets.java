package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.entity.actor.Player;

/** Только охват целей клановой войны; разрешение атаки и лимиты остаются Native. */
public final class PhantomWarTargets
{
    private PhantomWarTargets() { }

    public static boolean playerClanMember(Player player)
    {
        if (PhantomClans.getInstance().living(player)) return true;
        if (player == null || !player.isOnline()
            || !PhantomManager.getInstance().isPhantom(player)
            || PhantomManager.getInstance().isSecret(player)) return false;

        PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
        return template != null && !template.isReaper()
            && PhantomCompanionManager.getInstance().isCompanion(template);
    }
}
