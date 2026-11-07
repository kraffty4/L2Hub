package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.entity.actor.Player;

/** Право личного помощника на автоматический1323 принадлежит его текущему владельцу. */
public final class PhantomPersonalNoblesse
{
	private PhantomPersonalNoblesse()
	{
	}

	public static boolean allows(Player actor, int skillId)
	{
		if (skillId != 1323)
		{
			return true;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(actor.getName());
		if (template == null)
		{
			return !PhantomManager.getInstance().isPhantom(actor);
		}
		if (template.isHunter() || template.isReaper())
		{
			return true;
		}
		return inheritedAllowed(actor, template, PhantomCompanionManager.getInstance());
	}

	static boolean inheritedAllowed(Player actor, PhantomTemplate template, PhantomCompanionManager companions)
	{
		if (template.isHunter() || template.isReaper())
		{
			return true;
		}
		if (!actor.isInParty())
		{
			return false;
		}
		for (Player member : actor.getParty().getMembers())
		{
			if ((member != actor) && companions.isOwner(member, template))
			{
				return member.isNoble();
			}
		}
		return false;
	}
}
