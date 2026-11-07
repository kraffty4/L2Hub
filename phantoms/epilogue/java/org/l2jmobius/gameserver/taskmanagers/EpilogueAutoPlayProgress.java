package org.l2jmobius.gameserver.taskmanagers;

import java.lang.reflect.Field;

import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Контроль уже выбранного моба; часы и список исключений принадлежат AutoPlay. */
public final class EpilogueAutoPlayProgress
{
    private static final long UNKNOWN_ATTACK = Long.MIN_VALUE;
    private static final Field ATTACK_END = attackField();

    private EpilogueAutoPlayProgress() { }

    private static Field attackField()
    {
        try
        {
            Field field = Creature.class.getDeclaredField("_attackEndTime");
            return field.getType() == long.class && field.trySetAccessible() ? field : null;
        }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }

    static long attackStamp(Creature actor)
    {
        if (ATTACK_END != null)
        {
            try { return ATTACK_END.getLong(actor); }
            catch (IllegalAccessException | RuntimeException ignored) { }
        }
        return UNKNOWN_ATTACK;
    }

    /** Состояние живёт в прежнем Detour, без второго таймера или глобальной карты. */
    public static final class State
    {
        private boolean tracking;
        private int targetId, instance, castStamp;
        private long since, attackStamp;
        private double hp;

        public void clear() { tracking = false; }

        boolean observe(int target, int world, double health, long attack, int cast,
            boolean attackStarted, boolean castStarted, boolean busy, long now, long timeout)
        {
            if (timeout <= 0 || !Double.isFinite(health)) { clear(); return false; }
            if (!tracking || targetId != target || instance != world || now < since)
            {
                tracking = true; targetId = target; instance = world; since = now;
                hp = health; attackStamp = attack; castStamp = cast;
                return false;
            }
            // Дедлайн начала атаки сохраняется и после короткой анимации между проходами.
            if (busy || health < hp || (attackStarted && attack != attackStamp) || (castStarted && cast != castStamp)) since = now;
            hp = health; attackStamp = attack; castStamp = cast;
            return now - since >= timeout;
        }
    }

    private static boolean hasAttackChoice(Player player)
    {
        if (player.getAutoUseSettings().getAutoActions().contains(2)) return true;
        if ((player.hasServitor() || player.hasPet()) && player.getSummon() != null
            && !player.getSummon().isAlikeDead() && !player.getSummon().isDisabled()) return true;
        if (!AutoPlayConfig.ENABLE_AUTO_SKILL) return false;
        for (Integer id : player.getAutoUseSettings().getAutoSkills())
        {
            Skill skill = player.getKnownSkill(id);
            if (skill == null || player.isSkillDisabled(skill)
                || player.getCurrentMp() < skill.getMpInitialConsume() + skill.getMpConsume()
                || player.getCurrentHp() <= skill.getHpConsume()
                || player.getCharges() < skill.getChargeConsumeCount()) continue;
            if (skill.getItemConsumeCount() > 0 && player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount()) continue;
            return true;
        }
        return false;
    }

    public static boolean stalled(State state, Player player, Creature target, long now, long timeout)
    {
        if (timeout <= 0 || !target.isMonster() || target.isAlikeDead() || !player.hasAI()
            || player.isAlikeDead() || player.isDisabled() || player.isRooted()
            || player.isSitting() || player.isTeleporting() || !hasAttackChoice(player))
        {
            state.clear(); return false;
        }
        long attack = attackStamp(player);
        boolean autoAttack = player.getAutoUseSettings().getAutoActions().contains(2);
        // Незнакомая версия Native не должна превращать пропущенную короткую атаку в застревание.
        if (autoAttack && attack == UNKNOWN_ATTACK) { state.clear(); return false; }
        Creature attacked = player.getAI().getAttackTarget();
        boolean attackStarted = attacked != null && attacked.getObjectId() == target.getObjectId();
        Skill cast = player.getLastSkillCast();
        boolean castStarted = cast != null && cast.hasNegativeEffect();
        return state.observe(target.getObjectId(), player.getInstanceId(), target.getCurrentHp(), attack,
            player.getCastInterruptTime(), attackStarted, castStarted,
            player.isAttackingNow() || player.isRangeAttackingNow() || player.isCastingNow(), now, timeout);
    }
}
