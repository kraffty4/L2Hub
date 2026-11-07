package org.l2jmobius.gameserver.managers.phantom;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.actor.Player;

/** One normal AI decision after a successful native start, without waiting for the 2s hunt tick. */
public final class OlympiadStartKick {
    private static final Logger LOG = Logger.getLogger(OlympiadStartKick.class.getName());
    private static final Set<Object> STARTED = Collections.newSetFromMap(new WeakHashMap<>());
    private static final Map<Integer, Object> DRIVE_LOCKS = new ConcurrentHashMap<>();

    private OlympiadStartKick() {}

    static Object lockFor(int playerId) { return DRIVE_LOCKS.computeIfAbsent(playerId, ignored -> new Object()); }

    static boolean queue(Object match, Runnable action, Consumer<Runnable> scheduler) {
        if (match == null) return false;
        synchronized (STARTED) { if (!STARTED.add(match)) return false; }
        try {
            scheduler.accept(() -> { synchronized (STARTED) { if (!STARTED.contains(match)) return; } action.run(); });
            return true;
        } catch (RuntimeException error) {
            clear(match);
            throw error;
        }
    }

    static boolean active(Object match) { synchronized (STARTED) { return STARTED.contains(match); } }
    public static void clear(Object match) {
        synchronized (STARTED) { STARTED.remove(match); }
        OlympiadActionPace.clear(match);
    }

    static boolean eligible(boolean firstInOlympiad, boolean secondInOlympiad, boolean firstStarted, boolean secondStarted,
            boolean firstDead, boolean secondDead, boolean firstTeleport, boolean secondTeleport,
            int wantedGame, int firstGame, int secondGame, int firstSide, int secondSide,
            int firstInstance, int secondInstance) {
        return firstInOlympiad && secondInOlympiad && firstStarted && secondStarted
            && !firstDead && !secondDead && !firstTeleport && !secondTeleport
            && wantedGame >= 0 && firstGame == wantedGame && secondGame == wantedGame
            && firstSide >= 1 && firstSide <= 2 && secondSide == 3-firstSide && firstInstance == secondInstance;
    }

    public static void started(Object match, Player first, Player second, int game, BooleanSupplier stillValid) {
        if (match == null || first == null || second == null) return;
        try {
            queue(match, () -> {
                if (!stillValid.getAsBoolean()) return;
                OlympiadActionPace.register(match,first,second,game,stillValid);
                if (stillValid.getAsBoolean()) wakeSafely(match, first, second, game);
                if (stillValid.getAsBoolean()) wakeSafely(match, second, first, game);
            }, ThreadPool::execute);
        } catch (RuntimeException error) {
            // This optional first decision must never abort the native match lifecycle.
            LOG.log(Level.WARNING, "Could not enqueue first Olympiad AI decision", error);
        }
    }

    private static void wakeSafely(Object match, Player player, Player rival, int game) {
        try { wake(match, player, rival, game); }
        catch (RuntimeException error) { LOG.log(Level.WARNING, "First Olympiad AI decision failed for " + player.getObjectId(), error); }
    }

    private static void wake(Object match, Player player, Player rival, int game) {
        if (!PhantomManager.getInstance().isPhantom(player)) return;
        synchronized (lockFor(player.getObjectId())) {
            synchronized (STARTED) { if (!STARTED.contains(match)) return; }
            if (!player.isSpawned() || !rival.isSpawned()) return;
            if (!eligible(player.isInOlympiadMode(), rival.isInOlympiadMode(), player.isOlympiadStart(), rival.isOlympiadStart(),
                player.isDead(), rival.isDead(), player.isTeleporting(), rival.isTeleporting(), game,
                player.getOlympiadGameId(), rival.getOlympiadGameId(), player.getOlympiadSide(), rival.getOlympiadSide(),
                player.getInstanceId(), rival.getInstanceId())) return;
            PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
            if (template == null || !template.isHunter() || template.isReaper()) return;
            // Native stand/fake-death methods keep their own animation and action checks.
            if (player.isFakeDeath()) player.stopFakeDeath(true);
            if (player.isSitting()) player.standUp();
            PhantomOlympiad.getInstance().drive(player, template, System.currentTimeMillis());
        }
    }
}
