package org.l2jmobius.gameserver.managers.phantom;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillUseHolder;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/** Coordinates support resurrection casts without changing native reuse or costs. */
public final class SupportResurrection
{
    // Weak keys and weak caster references do not retain dismissed companions.
    private static final Map<Player, Claim> CLAIMS = new WeakHashMap<>();

    private record Claim(WeakReference<Player> caster, SkillUseHolder cast, int instanceId)
    {
        boolean active(Player corpse)
        {
            final Player player = caster.get();
            return corpse.isDead() && !corpse.isReviveRequested()
                && (player != null) && !player.isDead() && player.isOnline()
                && (corpse.getInstanceId() == instanceId) && (player.getInstanceId() == instanceId)
                && player.isCastingNow() && (player.getCurrentSkill() == cast);
        }
    }

    private SupportResurrection()
    {
    }

    private static void prune()
    {
        CLAIMS.entrySet().removeIf(entry -> !entry.getValue().active(entry.getKey()));
    }

    /** Used during corpse selection so the other healer can choose another casualty. */
    public static boolean inProgress(Player corpse)
    {
        if (corpse == null)
        {
            return false;
        }
        synchronized (CLAIMS)
        {
            prune();
            return CLAIMS.containsKey(corpse);
        }
    }

    /** Native useMagic starts casting synchronously; serialize its check-and-start. */
    public static boolean useMagic(Player player, Skill skill, Creature target)
    {
        if ((skill.getTargetType() != TargetType.PC_BODY) || !skill.hasEffectType(EffectType.RESURRECTION)
            || !(target instanceof Player corpse))
        {
            return PhantomCompat.useMagic(player, skill, false, false);
        }
        synchronized (CLAIMS)
        {
            prune();
            if (!corpse.isDead() || corpse.isReviveRequested() || CLAIMS.containsKey(corpse))
            {
                return false;
            }
            final boolean started = PhantomCompat.useMagic(player, skill, false, false);
            final SkillUseHolder cast = player.getCurrentSkill();
            if (started && player.isCastingNow() && (cast != null) && (cast.getSkillId() == skill.getId()))
            {
                CLAIMS.put(corpse, new Claim(new WeakReference<>(player), cast, corpse.getInstanceId()));
            }
            return started;
        }
    }
}
