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
 * Кроме наставника в пати новичка ({@link PhantomMentors}, раздел 4.142): он не берёт долю опыта и не поднимает уровень пати.
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
		final boolean mentors = PhantomMentors.getInstance().active();
		if (PhantomPartyConfig.PHANTOM_PARTY_EXP_SHARE && !mentors)
		{
			return members;
		}

		final List<Player> result = new ArrayList<>(members.size());
		for (Player member : members)
		{
			if ((PhantomPartyConfig.PHANTOM_PARTY_EXP_SHARE || !isCompanion(member)) && (!mentors || !PhantomMentors.getInstance().isMentor(member)))
			{
				result.add(member);
			}
		}
		return result;
	}

	/**
	 * Уровень пати для опыта за моба (врезка в {@code Attackable}, High Five): наставник 40+ рядом с новичком иначе обнулил бы ему опыт —
	 * штраф за уровень моба и отсечка {@code PartyXpCutoffLevel} считаются от самого старшего в пати.
	 * @param rewarded кто рядом с мобом
	 * @param level уровень пати по правилам Mobius
	 * @return тот же уровень или, если среди них наставник, старший уровень остальных
	 */
	public static int partyLevel(List<Player> rewarded, int level)
	{
		if (!PhantomMentors.getInstance().active())
		{
			return level;
		}
		boolean mentor = false;
		int top = 0;
		for (Player member : rewarded)
		{
			if (PhantomMentors.getInstance().isMentor(member))
			{
				mentor = true;
			}
			else
			{
				top = Math.max(top, member.getLevel());
			}
		}
		return mentor && (top > 0) ? top : level;
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
