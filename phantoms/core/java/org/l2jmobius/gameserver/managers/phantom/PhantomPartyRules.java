package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.util.LocationUtil;

/**
 * L2Hub: доля фантомов в пати. По умолчанию опыт, SP, лут и адена достаются только живым игрокам (PhantomParty.ini). Охотники — как живые: качаются и копят на шмот сами.
 */
public final class PhantomPartyRules
{
	private PhantomPartyRules()
	{
	}

	/**
	 * @param member участник пати
	 * @return может ли он получить предмет или долю адены
	 */
	public static boolean takesLoot(Player member)
	{
		return PhantomPartyConfig.PHANTOM_PARTY_LOOT_SHARE || !isCompanion(member);
	}

	/**
	 * @param members кому положен опыт за моба
	 * @return те же участники без фантомов (если фантомам доля не положена)
	 */
	public static List<Player> expMembers(List<Player> members)
	{
		if (PhantomPartyConfig.PHANTOM_PARTY_EXP_SHARE)
		{
			return members;
		}

		final List<Player> result = new ArrayList<>(members.size());
		for (Player member : members)
		{
			if (!isCompanion(member))
			{
				result.add(member);
			}
		}
		return result;
	}

	/**
	 * @return напарник-фантом (не охотник): ему доля не положена
	 */
	private static boolean isCompanion(Player member)
	{
		if (!PhantomManager.getInstance().isPhantom(member))
		{
			return false;
		}

		final PhantomTemplate template = PhantomData.getInstance().getTemplate(member.getName());
		return (template == null) || !template.isHunter();
	}

	/**
	 * Лут, который достался бы фантому (например, при «Нашедшему» он сам подобрал или убил), уходит живому участнику рядом.
	 * @param party пати
	 * @param looter кому выпало по правилам Mobius
	 * @param target откуда лут (для проверки дистанции)
	 * @return кому отдать
	 */
	public static Player looter(Party party, Player looter, Creature target)
	{
		if (takesLoot(looter))
		{
			return looter;
		}

		for (Player member : party.getMembers())
		{
			if (takesLoot(member) && LocationUtil.checkIfInRange(PlayerConfig.ALT_PARTY_RANGE, target, member, true))
			{
				return member;
			}
		}
		return looter;
	}
}
