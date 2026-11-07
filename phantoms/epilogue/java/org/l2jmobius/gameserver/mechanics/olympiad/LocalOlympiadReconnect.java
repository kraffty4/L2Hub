package org.l2jmobius.gameserver.mechanics.olympiad;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.ZoneManager;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.zone.type.OlympiadStadiumZone;
import org.l2jmobius.gameserver.network.serverpackets.ExOlympiadMatchEnd;
import org.l2jmobius.gameserver.network.serverpackets.ExOlympiadMode;

/** Reconnect cleanup uses the current World actor, never respawns an obsolete disconnected actor. */
public final class LocalOlympiadReconnect {
    private static final Logger LOG=Logger.getLogger(LocalOlympiadReconnect.class.getName());
    record Ticket(int arena,int x,int y,int z,long match) {}
    private static final Map<Integer,Ticket> PENDING=new ConcurrentHashMap<>();
    private LocalOlympiadReconnect() {}

    static boolean mayRebind(boolean same,boolean online,boolean spawned,boolean observer,boolean otherMatch,int physicalArena,int arena) {
        return online && spawned && !observer && !otherMatch && (same || physicalArena==arena);
    }
    static int physicalArena(Player player) {
        // This Epilogue datapack has no stadiumId parameters: every zone getter defaults to0.
        // Use coordinates, not actor membership: a reconnecting player may not be spawned yet.
        for(var zone:ZoneManager.getInstance().getZones(player.getX(),player.getY(),player.getZ())) {
            if(!(zone instanceof OlympiadStadiumZone)||!zone.isInsideZone(player.getX(),player.getY(),player.getZ()))continue;
            for(int index=0;index<OlympiadManager.STADIUMS.length;index++) {
                int[] centre=OlympiadManager.STADIUMS[index].getCoordinates();
                if(zone.isInsideZone(centre[0],centre[1],centre[2]))return index;
            }
        }
        return -1;
    }
    public static void remember(int id,int arena,int x,int y,int z,long match) {
        if(id<=0 || arena<0)return;
        Ticket next=new Ticket(arena,x,y,z,match);
        PENDING.compute(id,(ignored,previous)->previous!=null&&previous.match()>match?previous:next);
    }
    private static Player current(int id,Player captured,int arena) {
        Player player=World.getPlayer(id);
        if(player==null)return null;
        boolean other=player.isInOlympiadMode()&&player.getOlympiadGameId()!=arena;
        return mayRebind(player==captured,player.isOnline(),player.isSpawned(),player.inObserverMode(),other,physicalArena(player),arena)?player:null;
    }
    public static void refresh(OlympiadGame game) {
        if(game==null||game._players==null||game._players.size()<2)return;
        game._playerOne=current(game._playerOneID,game._playerOne,game._stadiumID);
        game._playerTwo=current(game._playerTwoID,game._playerTwo,game._stadiumID);
        game._players.set(0,game._playerOne);game._players.set(1,game._playerTwo);
    }
    public static void completed(int id,int arena,long match) {
        Ticket ticket=PENDING.get(id);Player player=World.getPlayer(id);
        if(ticket==null||ticket.arena()!=arena||ticket.match()!=match||player==null)return;
        if(physicalArena(player)!=arena)PENDING.remove(id,ticket);
    }
    /** Pending tickets are only for already-finished matches in this server session. */
    public static void entered(Player player) {
        if(player==null||player.getClient()==null||World.getPlayer(player.getObjectId())!=player)return;
        Ticket ticket=PENDING.get(player.getObjectId());
        if(ticket==null||player.inObserverMode())return;
        if(player.isInOlympiadMode()||physicalArena(player)!=ticket.arena()) {
            PENDING.remove(player.getObjectId(),ticket);return;
        }
        try {
            if(player.isDead())player.setDead(false);
            player.setIsPendingRevive(false);
            player.setInOlympiadMode(false);player.setOlympiadStart(false);
            player.setOlympiadSide(-1);player.setOlympiadGameId(-1);
            player.getStatus().startHpMpRegeneration();
            player.setCurrentCp(player.getMaxCp());player.setCurrentHp(player.getMaxHp());player.setCurrentMp(player.getMaxMp());
            player.sendPacket(new ExOlympiadMode(0));player.sendPacket(ExOlympiadMatchEnd.STATIC_PACKET);
            player.teleToLocation(ticket.x(),ticket.y(),ticket.z(),true);
            completed(player.getObjectId(),ticket.arena(),ticket.match());
        } catch(RuntimeException error) {
            LOG.log(Level.WARNING,"Could not recover completed Olympiad return for "+player.getObjectId(),error);
        }
    }
}
