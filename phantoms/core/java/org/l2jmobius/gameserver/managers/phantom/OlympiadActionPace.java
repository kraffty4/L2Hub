package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Full arena decisions reuse the existing native200ms pass; there is no second repeating timer. */
public final class OlympiadActionPace {
    private static final Logger LOG = Logger.getLogger(OlympiadActionPace.class.getName());
    private static final Map<Integer, Entry> ACTIVE = new ConcurrentHashMap<>();

    static final class DecisionClock {
        private long next;
        boolean claim(long now, long interval) {
            if (now < next) return false;
            next = now + Math.max(1L, interval);
            return true;
        }
    }
    private static final class Entry {
        final Object match;
        final Player player, rival;
        final PhantomTemplate template;
        final int game, side, instance;
        final BooleanSupplier ready;
        final DecisionClock clock = new DecisionClock();
        Entry(Object match, Player player, Player rival, PhantomTemplate template, int game, BooleanSupplier ready) {
            this.match=match;this.player=player;this.rival=rival;this.template=template;this.game=game;
            side=player.getOlympiadSide();instance=player.getInstanceId();
            this.ready=ready;
        }
    }
    private OlympiadActionPace() {}

    static void register(Object match, Player first, Player second, int game, BooleanSupplier ready) {
        if (!PhantomHuntersConfig.HUNTER_COMBAT_FIXES) return;
        boolean added = add(match, first, second, game, ready) | add(match, second, first, game, ready);
        if (added) PhantomCompanionManager.ensureOlympiadPace();
    }
    private static boolean add(Object match, Player player, Player rival, int game, BooleanSupplier ready) {
        if (!PhantomManager.getInstance().isPhantom(player)) return false;
        PhantomTemplate template=PhantomData.getInstance().getTemplate(player.getName());
        if (template==null || !template.isHunter() || template.isReaper()) return false;
        Entry entry=new Entry(match,player,rival,template,game,ready);
        if (!valid(entry)) return false;
        ACTIVE.put(player.getObjectId(),entry);
        return true;
    }
    private static boolean valid(Entry entry) {
        Player player=entry.player,rival=entry.rival;
        return PhantomHuntersConfig.HUNTER_COMBAT_FIXES && entry.ready.getAsBoolean() && OlympiadStartKick.active(entry.match)
            && player.isSpawned() && rival.isSpawned()
            && player.getOlympiadSide()==entry.side && player.getInstanceId()==entry.instance
            && OlympiadStartKick.eligible(player.isInOlympiadMode(),rival.isInOlympiadMode(),player.isOlympiadStart(),rival.isOlympiadStart(),
                player.isDead(),rival.isDead(),player.isTeleporting(),rival.isTeleporting(),entry.game,
                player.getOlympiadGameId(),rival.getOlympiadGameId(),player.getOlympiadSide(),rival.getOlympiadSide(),player.getInstanceId(),rival.getInstanceId());
    }
    static void clear(Object match) { ACTIVE.entrySet().removeIf(e->e.getValue().match==match); }
    public static boolean owns(Player player) {
        if (player==null) return false;
        Entry entry=ACTIVE.get(player.getObjectId());
        return entry!=null && entry.player==player && valid(entry);
    }
    /** Caller holds the same per-player lock as both arena drivers. */
    static boolean allowDecision(Player player,long now) {
        Entry entry=ACTIVE.get(player.getObjectId());
        return entry==null || entry.player!=player || !valid(entry)
            || entry.clock.claim(now,PhantomCompanionManager.combatPaceInterval());
    }
    public static void tick(long now) {
        for (Entry entry:ACTIVE.values()) {
            Player player=entry.player;
            synchronized(OlympiadStartKick.lockFor(player.getObjectId())) {
                if (!valid(entry)) { ACTIVE.remove(player.getObjectId(),entry);continue; }
                if (player.isCastingNow()) continue;
                try {
                    if (player.isMoving()) {
                        // Keep chase/kite intact unless a real self-defence, heal or cure is accepted.
                        PhantomCompat.olympiadReactive(player,entry.rival,now);
                        continue;
                    }
                    PhantomOlympiad.getInstance().drive(player,entry.template,now);
                }
                catch(RuntimeException error) {
                    ACTIVE.remove(player.getObjectId(),entry);
                    LOG.log(Level.WARNING,"Fast Olympiad decision failed for "+player.getObjectId()+"; returning to normal driver",error);
                }
            }
        }
    }
}
