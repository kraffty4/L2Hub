package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Native MOVE_TO only during the open-gate countdown; never starts combat or teleports. */
public final class OlympiadPositioning {
    private OlympiadPositioning() { }

    static boolean ranged(OlympiadPreparationPlan.Profile profile) {
        return switch (profile) {
            case ARCHER, MAGE, HEALER, BUFFER, SUMMONER -> true;
            default -> false;
        };
    }

    static int preferredRange(boolean ranged, int attackRange) {
        return ranged ? Math.max(150, attackRange - 50) : Math.max(30, Math.min(100, attackRange - 10));
    }

    /** Null means hold this position. This is relative to the opponent, never an arena centre. */
    static Location destination(int x, int y, int z, int otherX, int otherY, int otherZ,
            int range, boolean ranged) {
        double dx = x - (double) otherX, dy = y - (double) otherY;
        double distance = Math.hypot(dx, dy);
        if (distance < 1 || (distance <= range + 25 && (!ranged || distance >= range - 90))) return null;
        int targetX = otherX + (int) Math.round(dx * range / distance);
        int targetY = otherY + (int) Math.round(dy * range / distance);
        // Use the existing native movement/pathfinding, including its height/collision checks.
        return new Location(targetX, targetY, distance > range ? otherZ : z);
    }

    public static void advance(Player player, Player opponent) {
        if (player == null || opponent == null || player == opponent
                || !org.l2jmobius.gameserver.mechanics.olympiad.LocalOlympiadDoors.mayLeaveSpawn(player)
                || !opponent.isInOlympiadMode() || opponent.inObserverMode() || opponent.isDead()
                || player.getOlympiadGameId() != opponent.getOlympiadGameId()
                || player.getInstanceId() != opponent.getInstanceId()
                || opponent.getOlympiadSide() < 1 || opponent.getOlympiadSide() > 2
                || player.getOlympiadSide() == opponent.getOlympiadSide()
                || player.isDead() || player.isTeleporting() || opponent.isTeleporting()
                || player.isCastingNow() || player.isMovementDisabled()) return;

        var profile = OlympiadPreparationPlan.profile(player.getPlayerClass().getId(), player.isMageClass());
        boolean keepDistance = ranged(profile) || (profile == OlympiadPreparationPlan.Profile.HYBRID && player.isMageClass());
        int attackRange = player.getStat().getPhysicalAttackRange();
        if (keepDistance && profile != OlympiadPreparationPlan.Profile.ARCHER) {
            var attacks = AttackSkillPlan.select(player.getAllSkills(), true);
            attackRange = attacks.isEmpty() ? 600 : attacks.getFirst().getCastRange();
            if (attackRange < 200) attackRange = 600;
        }
        Location target = destination(player.getX(), player.getY(), player.getZ(),
                opponent.getX(), opponent.getY(), opponent.getZ(), preferredRange(keepDistance, attackRange), keepDistance);
        if (target == null) {
            if (player.isMoving()) {
                player.getAI().setIntentionActive();
                player.stopMove(null);
            }
            return;
        }
        if (player.isMoving() && Math.hypot(player.getXdestination() - (double) target.getX(),
                player.getYdestination() - (double) target.getY()) <= 75) return;
        player.setRunning();
        player.getAI().setIntentionMoveTo(target);
    }
}
