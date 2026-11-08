// L2Hub: копия кода сопровождающего Эпилога (phantoms/epilogue, итог после patches/epilogue/0512) для High Five, docs/PHANTOMS.md, раздел 4.149.
// В общее ядро не ложится: у Classic и Essence другой API эффектов. Правки сопровождающего переносить сюда вручную.
package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/** Выученные защиты и контроль в бою Олимпиады. Все расходы, условия и откаты — нативные. */
public final class OlympiadCombatSkills
{
    enum Kind { DEFENCE, BURST, CONTROL, HP_LINK, HEAL, CURE }
    record Choice(Skill skill, Kind kind, int priority) {}
    private static final Map<Integer,Long> NEXT = new ConcurrentHashMap<>();
    // Accepted actions and unsuccessful idle scans have different deadlines.
    private static final Map<Integer,Long> NEXT_ACTION = new ConcurrentHashMap<>();
    private static final Set<String> DEFENCES = Set.of("PD_UP_SPECIAL", "MD_UP_SPECIAL", "AVOID_UP_SPECIAL", "INVINCIBILITY", "DEBUFF_IMMUNITY", "PERFECT_DEFENCE");
    private static final Set<EffectType> CONTROLS = Set.of(EffectType.STUN, EffectType.PARALYZE, EffectType.MUTE, EffectType.ROOT);
    private OlympiadCombatSkills() {}

    static void clear(Player player) { NEXT.remove(player.getObjectId()); NEXT_ACTION.remove(player.getObjectId()); }

    static Kind kind(Skill skill)
    {
        if ((skill == null) || !skill.isActive() || skill.isToggle() || skill.isPassive() || skill.isGMSkill() || skill.isHeroSkill() || skill.isSuicideAttack() || skill.isBlockedInOlympiad()) return null;
        // Recovery meditation paralyses its caster and is not an arena burst.
        if (OlympiadSkillRules.recoveryOnly(skill)) return null;
        final var effects = skill.getEffects(EffectScope.GENERAL);
        // An aura may provoke enemies while its actual defence applies to the caster.
        // Require real SELF effects, so ordinary aura attacks do not become defences.
        if (skill.getTargetType() == TargetType.AURA && !skill.isDebuff() && skill.hasEffects(EffectScope.SELF)
            && (DEFENCES.contains(skill.getAbnormalType().name()) || skill.getEffects(EffectScope.SELF).stream().anyMatch(e -> e.getClass().getSimpleName().equals("Invincibility")))) return Kind.DEFENCE;
        if (effects == null) return null;
        if ((skill.getTargetType() == TargetType.SELF || skill.getTargetType() == TargetType.ONE) && !skill.isDebuff()
            && (DEFENCES.contains(skill.getAbnormalType().name()) || effects.stream().anyMatch(e -> e.getClass().getSimpleName().equals("Invincibility")))) return Kind.DEFENCE;
        if (effects.stream().anyMatch(e -> e.getClass().getSimpleName().equals("SilentMove") || e.getClass().getSimpleName().equals("Hide"))) return null;
        if (OlympiadSelfHeal.candidate(skill)) return Kind.HEAL;
        if (OlympiadSelfCure.candidate(skill)) return Kind.CURE;
        if (OlympiadSkillRules.combatSelfBuff(skill) && (skill.getAbnormalTime() <= 90 || skill.getReuseDelay() >= 180000)) return Kind.BURST;
        if (skill.getTargetType() != TargetType.ONE) return null;
        if (effects.stream().anyMatch(e -> CONTROLS.contains(e.getEffectType()) || e.getClass().getSimpleName().equals("TargetCancel"))) return Kind.CONTROL;
        if (skill.hasEffectType(EffectType.DEATH_LINK, EffectType.PHYSICAL_ATTACK_HP_LINK)) return Kind.HP_LINK;
        return null;
    }

    static boolean immobilizes(Skill skill)
    {
        return java.util.stream.Stream.of(skill.getEffects(EffectScope.GENERAL), skill.getEffects(EffectScope.SELF))
            .filter(Objects::nonNull).flatMap(Collection::stream).anyMatch(e -> e.getClass().getSimpleName().equals("ImmobileBuff"));
    }

    static boolean covered(Player target, Skill skill)
    {
        BuffInfo buff = skill.getAbnormalType() == AbnormalType.NONE ? target.getEffectList().getBuffInfoBySkillId(skill.getId()) : PhantomCompat.buffByAbnormal(target, skill.getAbnormalType());
        return (buff != null) && buff.isInUse() && (buff.getSkill().getAbnormalLevel() >= skill.getAbnormalLevel());
    }

    static List<Choice> choices(Collection<Skill> learned, double hpPercent, double cpPercent)
    {
        List<Choice> result = new ArrayList<>();
        for (Skill skill : learned)
        {
            Kind kind = kind(skill);
            if (kind == null) continue;
            if ((kind == Kind.DEFENCE) && hpPercent > 75 && cpPercent > 20) continue;
            if ((kind == Kind.HP_LINK) && hpPercent > 45) continue;
            if ((kind == Kind.HEAL) && hpPercent > 65) continue;
            int priority = switch (kind) { case DEFENCE -> 3000; case HEAL -> 2500; case CURE -> 2400; case BURST -> 2200; case CONTROL -> 2000; case HP_LINK -> 1000; };
            if (skill.hasEffectType(EffectType.STUN, EffectType.PARALYZE)) priority += 200;
            else if (skill.hasEffectType(EffectType.MUTE)) priority += 100;
            priority += Math.min(99, skill.getAbnormalLevel());
            result.add(new Choice(skill, kind, priority));
        }
        result.sort(Comparator.comparingInt(Choice::priority).reversed()
            .thenComparingInt(c -> c.kind() == Kind.HEAL ? c.skill().getReuseDelay() : 0)
            .thenComparingInt(c -> c.skill().getId()));
        return result;
    }

    static boolean sameFight(Player player, Player enemy)
    {
        return (enemy != null) && (enemy != player) && player.isInOlympiadMode() && player.isOlympiadStart() && enemy.isInOlympiadMode() && enemy.isOlympiadStart()
            && (player.getOlympiadGameId() >= 0) && (player.getOlympiadGameId() == enemy.getOlympiadGameId())
            && (player.getOlympiadSide() >= 1) && (player.getOlympiadSide() <= 2) && (enemy.getOlympiadSide() == 3 - player.getOlympiadSide())
            && (player.getInstanceId() == enemy.getInstanceId()) && !player.isDead() && !enemy.isDead() && !player.isTeleporting() && !enemy.isTeleporting();
    }

    static boolean tick(Player player, Player enemy, long now)
    {
        return execute(player, enemy, now, false);
    }

    /** During an existing route, only a needed native self-reaction may interrupt it. */
    static boolean reactiveTick(Player player, Player enemy, long now)
    {
        return execute(player, enemy, now, true);
    }

    private static boolean execute(Player player, Player enemy, long now, boolean reactionsOnly)
    {
        final Map<Integer,Long> deadline = reactionsOnly ? NEXT_ACTION : NEXT;
        if (!sameFight(player, enemy) || player.isCastingNow() || player.isSitting() || player.isFakeDeath() || (now < deadline.getOrDefault(player.getObjectId(), 0L))) return false;
        final double hp = 100d * player.getCurrentHp() / Math.max(1d, player.getMaxHp());
        final double cp = 100d * player.getCurrentCp() / Math.max(1d, player.getMaxCp());
        for (Choice choice : choices(player.getAllSkills(), hp, cp))
        {
            if (reactionsOnly && choice.kind() != Kind.DEFENCE && choice.kind() != Kind.HEAL && choice.kind() != Kind.CURE) continue;
            final Skill skill = choice.skill();
            final boolean defence = choice.kind() == Kind.DEFENCE;
            final boolean heal = choice.kind() == Kind.HEAL;
            final boolean cure = choice.kind() == Kind.CURE;
            final boolean self = defence || heal || cure || choice.kind() == Kind.BURST;
            final Player target = self ? player : enemy;
            if (player.isSkillDisabled(skill) || player.getCurrentMp() < skill.getMpConsume() + skill.getMpInitialConsume() || player.getCurrentHp() <= skill.getHpConsume()
                || player.getCharges() < skill.getChargeConsumeCount() || !OlympiadSkillRules.hasReagents(player, skill) || (!heal && !cure && covered(target, skill))) continue;
            if (heal && !OlympiadSelfHeal.worthwhile(player, skill)) continue;
            if (cure && !OlympiadSelfCure.worthwhile(player, skill)) continue;
            if (!self)
            {
                if (enemy.isStunned() || enemy.isParalyzed() || enemy.isSleeping()) continue;
                if (skill.hasEffectType(EffectType.MUTE) && (!enemy.isMageClass() || enemy.isMuted())) continue;
                if (skill.hasEffectType(EffectType.ROOT) && enemy.isRooted()) continue;
                int range = player.getMagicalAttackRange(skill);
                if ((range >= 0) && !PhantomCompanionManager.inReach(player, enemy, range)) continue;
            }
            else
            {
                if (immobilizes(skill) && !PhantomCompanionManager.inReach(player, enemy, player.getPhysicalAttackRange() + 40)) continue;
                if (choice.kind() == Kind.BURST && player.calculateDistance2D(enemy) > 900) continue;
            }
            if (!skill.checkCondition(player, target, false)) continue;
            final var previous = player.getTarget();
            try
            {
                player.setTarget(target);
                if (PhantomCompat.useMagic(player, skill, true, false))
                {
                    NEXT.put(player.getObjectId(), now + 500);
                    NEXT_ACTION.put(player.getObjectId(), now + 500);
                    return true;
                }
            }
            finally { player.setTarget(previous); }
        }
        if (!reactionsOnly) NEXT.put(player.getObjectId(), now + 500);
        return false;
    }
}
