package org.l2jmobius.gameserver.managers.phantom;

import java.util.Set;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.EffectScope;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/**
 * Проверки навыка для боя Олимпиады на High Five ({@link OlympiadCombatSkills}, docs/PHANTOMS.md, раздел 4.149). Дословно из кода сопровождающего Эпилога:
 * {@code CompanionSelfSkills.recoveryOnly}, {@code CompanionSelfSkills.candidate(skill, true)} и {@code SupportSupplies.hasReagents}. Сами те классы — механика
 * Эпилога ({@code phantoms/epilogue}, тянут его расходники и бафы пати), а бою Олимпиады нужны только эти три проверки.
 */
final class OlympiadSkillRules
{
	private static final Set<String> EXCLUDE = Set.of("Transformation", "Teleport", "TeleportToPlayer", "Escape", "InstantJump", "Fishing", "DispelAll", "DispelByCategory", "DispelBySlot", "CallPc", "SummonPc", "SilentMove");

	private OlympiadSkillRules()
	{
	}

	/**
	 * Медитация и прочее восстановление с самопараличом — не боевой навык.
	 */
	static boolean recoveryOnly(Skill s)
	{
		if ((s == null) || (s.getTargetType() != TargetType.SELF))
		{
			return false;
		}
		if (s.getAbnormalType() == AbnormalType.FORCE_MEDITATION)
		{
			return true;
		}
		return s.hasEffectType(EffectType.PARALYZE) && s.getEffects(EffectScope.GENERAL).stream().anyMatch(e -> e.getClass().getSimpleName().equals("ManaHealOverTime"));
	}

	/**
	 * Свой бафф в бою: на себя, длится или тоггл, без вреда, не телепорт и не урон.
	 */
	static boolean combatSelfBuff(Skill s)
	{
		if ((s == null) || s.isPassive() || s.isDebuff() || s.hasNegativeEffect() || s.isGMSkill() || s.isHeroSkill() || (s.getTargetType() != TargetType.SELF) || (!s.isContinuous() && !s.isToggle()))
		{
			return false;
		}
		if (recoveryOnly(s))
		{
			return false;
		}
		if (!s.isToggle() && (s.getAbnormalTime() < 20))
		{
			return false;
		}
		return s.getEffects(EffectScope.GENERAL).stream().noneMatch(e -> (e.getEffectType() == EffectType.RELAXING) || (e.getEffectType() == EffectType.FAKE_DEATH) || EXCLUDE.contains(e.getClass().getSimpleName()) || e.getClass().getSimpleName().contains("Damage"));
	}

	/**
	 * Хватает расходника навыка (Spirit Ore и т. п.).
	 */
	static boolean hasReagents(Player p, Skill s)
	{
		return (s.getItemConsumeId() <= 0) || (s.getItemConsumeCount() <= 0) || (p.getInventory().getInventoryItemCount(s.getItemConsumeId(), -1) >= s.getItemConsumeCount());
	}
}
