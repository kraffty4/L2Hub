// L2Hub: копия кода сопровождающего Эпилога (phantoms/epilogue, итог после patches/epilogue/0512) для High Five, docs/PHANTOMS.md, раздел 4.149.
// В общее ядро не ложится: у Classic и Essence другой API эффектов. Правки сопровождающего переносить сюда вручную.
package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.mechanics.effects.AbstractEffect;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.EffectScope;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/** Learned self cures; matching follows native dispel metadata, execution remains native. */
public final class OlympiadSelfCure
{
    private static final String SLOT = "handlers.skill.effects.DispelBySlot";
    private static final String CATEGORY = "handlers.skill.effects.DispelByCategory";
    private static final Set<String> COMPANION_EFFECTS = Set.of(
        "handlers.skill.effects.Buff", "handlers.skill.effects.Heal",
        "handlers.skill.effects.HealPercent", "handlers.skill.effects.HealOverTime");
    private record Access(Field slots, Field category, Field rate, Field max) {}
    private static final ClassValue<Optional<Access>> ACCESS = new ClassValue<>()
    {
        @Override protected Optional<Access> computeValue(Class<?> type)
        {
            try
            {
                if (type.getName().equals(SLOT)) return Optional.of(new Access(field(type, "_dispelAbnormals"), null, null, null));
                if (type.getName().equals(CATEGORY)) return Optional.of(new Access(null, field(type, "_slot"), field(type, "_rate"), field(type, "_max")));
            }
            catch (ReflectiveOperationException | RuntimeException ignored) { }
            return Optional.empty();
        }
    };

    private OlympiadSelfCure() {}

    private static Field field(Class<?> type, String name) throws ReflectiveOperationException
    {
        Field field = type.getDeclaredField(name);
        if (!field.trySetAccessible()) throw new IllegalAccessException(name);
        return field;
    }

    private static Map<?, ?> slots(AbstractEffect effect, Access access) throws IllegalAccessException
    {
        Object value = access.slots().get(effect);
        return value instanceof Map<?, ?> map ? map : Map.of();
    }

    private static boolean cureEffect(AbstractEffect effect)
    {
        Optional<Access> found = ACCESS.get(effect.getClass());
        if (found.isEmpty()) return false;
        Access access = found.get();
        try
        {
            if (access.slots() != null)
            {
                Map<?, ?> values = slots(effect, access);
                return !values.isEmpty() && !values.containsKey(AbnormalType.TRANSFORM)
                    && values.entrySet().stream().allMatch(e -> e.getKey() instanceof AbnormalType && e.getValue() instanceof Short);
            }
            // Native debuff category uses canBeDispeled and a chance roll; never accept "buff" cancel.
            return "debuff".equals(access.category().get(effect)) && access.rate().getInt(effect) >= 0 && access.max().getInt(effect) >= 0;
        }
        catch (IllegalAccessException | RuntimeException ignored) { return false; }
    }

    static boolean candidate(Skill skill)
    {
        if (skill == null || !skill.isActive() || skill.isPassive() || skill.isToggle() || skill.isDebuff()
            || skill.hasNegativeEffect() || skill.isGMSkill() || skill.isHeroSkill() || skill.isSuicideAttack() || skill.isBlockedInOlympiad()) return false;
        TargetType target = skill.getTargetType();
        if (target != TargetType.SELF && target != TargetType.ONE && target != TargetType.PARTY) return false;
        List<AbstractEffect> effects = skill.getEffects(EffectScope.GENERAL);
        if (effects == null || effects.isEmpty() || effects.stream().noneMatch(OlympiadSelfCure::cureEffect)) return false;
        // Offensive dispels/vortex finishers are not cleanses, even if they remove a slot.
        if (effects.stream().anyMatch(e -> !cureEffect(e) && !COMPANION_EFFECTS.contains(e.getClass().getName()))) return false;
        Collection<AbstractEffect> self = skill.getEffects(EffectScope.SELF);
        return self == null || self.stream().allMatch(e -> COMPANION_EFFECTS.contains(e.getClass().getName()));
    }

    static boolean worthwhile(Player player, Skill skill)
    {
        if (!candidate(skill) || player.isDead() || player.isRaid()) return false;
        if (skill.getTargetType() == TargetType.PARTY)
        {
            if (player.isInParty()) return false;
            final var targets = skill.getTargetList(player, false);
            if (targets == null || !targets.contains(player) || targets.stream().anyMatch(t -> t != player && (!(t instanceof Summon summon) || summon.getOwner() != player))) return false;
        }
        for (AbstractEffect effect : skill.getEffects(EffectScope.GENERAL))
        {
            if (!cureEffect(effect) || !effect.testConditions(player, player, skill)) continue;
            Access access = ACCESS.get(effect.getClass()).orElseThrow();
            try
            {
                if (access.slots() != null)
                {
                    for (var entry : slots(effect, access).entrySet())
                    {
                        AbnormalType type = (AbnormalType) entry.getKey();
                        int cap = ((Short) entry.getValue()).intValue();
                        var info = player.getEffectList().getBuffInfoByAbnormalType(type);
                        if (info != null && info.getSkill().isDebuff() && (cap < 0 || cap >= info.getSkill().getAbnormalLevel())) return true;
                    }
                }
                else if (player.getEffectList().getDebuffs().stream().anyMatch(info -> info.getSkill().isDebuff() && info.getSkill().canBeDispeled())) return true;
            }
            catch (IllegalAccessException | RuntimeException ignored) { }
        }
        return false;
    }
}
