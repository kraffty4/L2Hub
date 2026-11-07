package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Sanctioned matches must not become unexpected world attacks or lasting grudges. */
public final class CombatEventPolicy {
    private CombatEventPolicy() {}

    static boolean sanctioned(boolean firstOlympiad, boolean secondOlympiad, boolean firstDuel,
            boolean secondDuel, boolean firstEvent, boolean secondEvent) {
        return firstOlympiad || secondOlympiad || firstDuel || secondDuel || firstEvent || secondEvent;
    }

    public static boolean sanctioned(Player first, Player second) {
        return sanctioned(first != null && first.isInOlympiadMode(), second != null && second.isInOlympiadMode(),
            first != null && first.isInDuel(), second != null && second.isInDuel(),
            first != null && first.isOnEvent(), second != null && second.isOnEvent());
    }

    public static String key(int hunterId, int otherId, String kind) {
        // A mass fight can contain many attackers; one hunter should not complain to each of them.
        return "h" + hunterId + ":" + ("ATTACKED".equals(kind) ? "world-attack" : otherId) + ":" + kind;
    }

    public static boolean reserve(Map<String, Long> cooldowns, String key, long now, long cooldown) {
        AtomicBoolean reserved = new AtomicBoolean();
        // PhantomChat owns a ConcurrentHashMap: check-and-reserve must be one atomic operation.
        cooldowns.compute(key, (ignored, until) -> {
            if (until != null && now < until) return until;
            reserved.set(true);
            return now + Math.max(0L, cooldown);
        });
        return reserved.get();
    }
}
