package org.l2jmobius.gameserver.mechanics.olympiad;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.spawns.Spawn;

/** Remove only the two buffer spawns owned by this game, never nearby historical NPCs. */
public final class LocalOlympiadBufferCleanup
{
    private static final Logger LOG = Logger.getLogger(LocalOlympiadBufferCleanup.class.getName());
    private static final int BUFFER_ID = 36402;

    private LocalOlympiadBufferCleanup() {}

    /** Idempotent on start/end/abort; does not depend on the shared _gameIsStarted flag. */
    public static void clear(OlympiadGame game)
    {
        if (game == null)
        {
            return;
        }
        final Spawn one = game._spawnOne;
        final Spawn two = game._spawnTwo;
        clearSpawn(game, one);
        if (two != one)
        {
            clearSpawn(game, two);
        }
    }

    private static void clearSpawn(OlympiadGame game, Spawn expected)
    {
        if (expected == null)
        {
            return;
        }
        try
        {
            // Both directions of ownership must agree. Never stop/delete an unrelated spawn.
            if ((expected.getTemplate() == null) || (expected.getTemplate().getId() != BUFFER_ID))
            {
                return;
            }
            final Npc npc = expected.getLastSpawn();
            if ((npc != null) && ((npc.getId() != BUFFER_ID) || (npc.getSpawn() != expected)))
            {
                return;
            }
            // Before deleteMe/onDecay: decreaseCount must not schedule another buffer.
            expected.stopRespawn();
            if ((npc != null) && (expected.getLastSpawn() == npc) && (npc.getSpawn() == expected)
                && (npc.isSpawned() || !npc.isDecayed()))
            {
                npc.deleteMe();
            }
        }
        catch (RuntimeException error)
        {
            // A failed side must not skip the other side or the existing match cleanup.
            LOG.log(Level.WARNING, "Olympiad buffer cleanup failed for game " + game._stadiumID, error);
        }
    }
}
