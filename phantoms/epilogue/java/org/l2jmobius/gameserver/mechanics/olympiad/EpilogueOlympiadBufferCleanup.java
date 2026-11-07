/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Код предназначен для L2Hub / L2J Mobius Gracia Epilogue.
 */
/** Убираются только два собственных спавна баффера данного боя. */
package org.l2jmobius.gameserver.mechanics.olympiad;

import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.spawns.Spawn;


public final class EpilogueOlympiadBufferCleanup
{
    private static final Logger LOG = Logger.getLogger(EpilogueOlympiadBufferCleanup.class.getName());
    private static final int BUFFER_ID = 36402;

    private EpilogueOlympiadBufferCleanup() {}

    
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
            
            if ((expected.getTemplate() == null) || (expected.getTemplate().getId() != BUFFER_ID))
            {
                return;
            }
            final Npc npc = expected.getLastSpawn();
            if ((npc != null) && ((npc.getId() != BUFFER_ID) || (npc.getSpawn() != expected)))
            {
                return;
            }
            
            expected.stopRespawn();
            if ((npc != null) && (expected.getLastSpawn() == npc) && (npc.getSpawn() == expected)
                && (npc.isSpawned() || !npc.isDecayed()))
            {
                npc.deleteMe();
            }
        }
        catch (RuntimeException error)
        {
            
            LOG.log(Level.WARNING, "Olympiad buffer cleanup failed for game " + game._stadiumID, error);
        }
    }
}
