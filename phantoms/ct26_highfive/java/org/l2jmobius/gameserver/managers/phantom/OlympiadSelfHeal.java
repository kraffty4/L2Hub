// L2Hub: копия кода сопровождающего Эпилога (phantoms/epilogue, итог после patches/epilogue/0512) для High Five, docs/PHANTOMS.md, раздел 4.149.
// В общее ядро не ложится: у Classic и Essence другой API эффектов. Правки сопровождающего переносить сюда вручную.
package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.handler.TargetHandler;
import org.l2jmobius.gameserver.mechanics.effects.AbstractEffect;
import org.l2jmobius.gameserver.mechanics.skill.EffectScope;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.mechanics.stats.functions.FuncTemplate;

/** Learned instant self-heals. Casting, costs and reuse remain native. */
public final class OlympiadSelfHeal
{
    private static final String HEAL = "handlers.skill.effects.Heal";
    private static final String PERCENT = "handlers.skill.effects.HealPercent";
    private static final Set<String> PREDICTABLE_COST_EFFECTS = Set.of(PERCENT,
        "handlers.skill.effects.HealOverTime", "handlers.skill.effects.Buff");
    private static final Set<Stat> HP_MODEL_STATS = Set.of(Stat.MAX_HP, Stat.MAX_RECOVERABLE_HP, Stat.STAT_CON);
    private static final Optional<Field> SKILL_FUNCTIONS = skillFunctions();
    private static final ClassValue<Optional<Field>> PERCENT_POWER = new ClassValue<>()
    {
        @Override protected Optional<Field> computeValue(Class<?> type)
        {
            if (!type.getName().equals(PERCENT)) return Optional.empty();
            try
            {
                Field field = type.getDeclaredField("_power");
                return field.getType() == int.class && field.trySetAccessible() ? Optional.of(field) : Optional.empty();
            }
            catch (ReflectiveOperationException | RuntimeException unavailable) { return Optional.empty(); }
        }
    };

    private OlympiadSelfHeal() {}

    private static Optional<Field> skillFunctions()
    {
        try
        {
            Field field = Skill.class.getDeclaredField("_funcTemplates");
            return List.class.isAssignableFrom(field.getType()) && field.trySetAccessible() ? Optional.of(field) : Optional.empty();
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return Optional.empty(); }
    }

    static double percentPower(AbstractEffect effect)
    {
        if (effect == null) return Double.NaN;
        Optional<Field> field = PERCENT_POWER.get(effect.getClass());
        if (field.isEmpty()) return Double.NaN;
        try
        {
            Object value = field.get().get(effect);
            return value instanceof Number number ? number.doubleValue() : Double.NaN;
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return Double.NaN; }
    }

    static boolean candidate(Skill skill)
    {
        if (skill == null || !skill.isActive() || skill.isToggle() || skill.isPassive() || skill.isDebuff() || skill.hasNegativeEffect()
            || skill.isGMSkill() || skill.isHeroSkill() || skill.isSuicideAttack() || skill.isBlockedInOlympiad()
            || (skill.getTargetType() != TargetType.SELF && skill.getTargetType() != TargetType.ONE && skill.getTargetType() != TargetType.PARTY) || skill.getHpConsume() < 0) return false;
        List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
        if (effects == null) return false;
        boolean direct = effects.stream().anyMatch(effect -> effect != null && effect.isInstant()
            && ((skill.getHpConsume() == 0 && effect.getClass().getName().equals(HEAL)) || percentPower(effect) > 0));
        return direct && (skill.getHpConsume() == 0 || stableHpCostEffects(skill));
    }

    private static boolean stableFunctions(Iterable<?> functions)
    {
        if (functions == null) return true;
        for (Object function : functions)
            if (!(function instanceof FuncTemplate template) || template.getStat() == null || HP_MODEL_STATS.contains(template.getStat())) return false;
        return true;
    }

    private static boolean stableHpCostEffects(Skill skill)
    {
        // Inspect all templates, including currently inactive conditional ones.
        // Public getStatFuncs would omit those before the HP cost is paid.
        if (SKILL_FUNCTIONS.isEmpty()) return false;
        try
        {
            Object functions = SKILL_FUNCTIONS.get().get(skill);
            if (functions != null && (!(functions instanceof Iterable<?> list) || !stableFunctions(list))) return false;
        }
        catch (ReflectiveOperationException | RuntimeException unavailable) { return false; }
        for (EffectScope scope : EffectScope.values())
        {
            List<AbstractEffect> effects = skill.getEffects(scope);
            if (effects == null) continue;
            for (AbstractEffect effect : effects)
            {
                if (effect == null || !PREDICTABLE_COST_EFFECTS.contains(effect.getClass().getName()) || !stableFunctions(effect.getFuncTemplates())) return false;
                if (effect.getClass().getName().equals(PERCENT) && !(percentPower(effect) > 0)) return false;
            }
        }
        return true;
    }

    private static boolean soloPartyTargets(Player player, Skill skill)
    {
        if (player.getParty() != null) return false;
        try
        {
            var handler = TargetHandler.getInstance().getHandler(TargetType.PARTY);
            if (handler == null || !handler.getClass().getName().equals("handlers.skill.targets.Party")) return false;
            // Use the native target rule without changing the selected target.
            // A solo caster and its own summon are the only permitted recipients.
            var targets = skill.getTargetList(player, false);
            if (targets == null || targets.isEmpty() || !targets.contains(player)) return false;
            Summon summon = player.getSummon();
            for (var target : targets)
                if (target != player && (summon == null || target != summon || summon.getOwner() != player)) return false;
            return true;
        }
        catch (RuntimeException | LinkageError incompatibleTargetRule) { return false; }
    }

    static boolean worthwhile(Player player, Skill skill)
    {
        if (player == null || !candidate(skill)) return false;
        if (skill.getTargetType() == TargetType.PARTY && !soloPartyTargets(player, skill)) return false;
        double hp = player.getCurrentHp(), max = player.getMaxHp(), cap = player.getMaxRecoverableHp(), cost = skill.getHpConsume();
        if (!Double.isFinite(hp) || !Double.isFinite(max) || !Double.isFinite(cap) || max <= 0 || hp <= cost || cap <= hp) return false;
        List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
        if (cost == 0)
        {
            // Native fixed Heal returns without healing an invulnerable actor;
            // native HealPercent still works in that state.
            return !player.isInvul() || effects.stream().anyMatch(effect -> effect != null && effect.isInstant() && percentPower(effect) > 0);
        }
        // Use only a proven native HealPercent amount. Ignore HoT, fixed heals,
        // and other future benefits when deciding whether paying HP is safe.
        for (AbstractEffect effect : effects)
        {
            double power = percentPower(effect);
            if (!(power > 0) || !effect.isInstant()) continue;
            double afterCost = hp - cost;
            double amount = Math.max(Math.min(max * power / 100d, cap - afterCost), 0d);
            if (Double.isFinite(amount) && amount > cost) return true;
        }
        return false;
    }
}
