package org.l2jmobius.gameserver.managers.phantom;
import java.lang.reflect.*;
import java.util.*;
import java.util.logging.Logger;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Responds to a declared incoming war; normal AI initiation still uses grudges. */
public final class LocalWarReplies {
  static final Logger LOG=Logger.getLogger(LocalWarReplies.class.getName());
  private static Constructor<?> candidate;
  private static Method start;
  public static boolean shouldReply(boolean botTarget,boolean enabled,boolean eligible,boolean incoming,boolean outgoing,boolean allied,boolean leaderOnline){
    return botTarget&&enabled&&eligible&&incoming&&!outgoing&&!allied&&leaderOnline;
  }
  public static void scan(long now){
    if(!PhantomHuntersConfig.HUNTER_CLANS)return;
    try{
      var clans=new ArrayList<>(ClanTable.getInstance().getClans());
      for(Clan target:clans){
        if(!PhantomHunterClans.isHunterClan(target)||!ClanWarPolicy.eligible(target))continue;
        var head=target.getLeader();Player leader=head==null?null:head.getPlayer();
        for(Clan source:clans){
          if(source==target)continue;
          boolean sourceBot=PhantomHunterClans.isHunterClan(source);
          boolean enabled=sourceBot?PhantomHuntersConfig.HUNTER_CLAN_WARS:PhantomHuntersConfig.HUNTER_CLAN_WARS_PLAYER;
          if(!shouldReply(true,enabled,ClanWarPolicy.eligible(source),source.isAtWarWith(target.getId()),target.isAtWarWith(source.getId()),
              target.getAllyId()!=0&&target.getAllyId()==source.getAllyId(),leader!=null&&leader.isOnline()))continue;
          if(candidate==null){
            Class<?> type=Class.forName(PhantomClanWars.class.getName()+"$Candidate",false,PhantomClanWars.class.getClassLoader());
            candidate=type.getDeclaredConstructor(Clan.class,Clan.class,double.class,double.class);candidate.setAccessible(true);
            start=PhantomClanWars.class.getDeclaredMethod("start",type,Player.class,long.class);start.setAccessible(true);
          }
          Object result=start.invoke(PhantomClanWars.getInstance(),candidate.newInstance(target,source,0d,0d),leader,now);
          if(result!=null)LOG.info("Incoming war accepted: "+target.getName()+" -> "+source.getName());
        }
      }
    }catch(ReflectiveOperationException e){throw new IllegalStateException("War reply integration changed; compatibility review required",e);}
  }
}
