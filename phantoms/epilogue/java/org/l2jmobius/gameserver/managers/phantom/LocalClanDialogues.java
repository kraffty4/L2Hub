package org.l2jmobius.gameserver.managers.phantom;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/** Allied clans share one alliance conversation; unallied clans use clan chat. */
public final class LocalClanDialogues {
 static long scope(Clan clan){return clan==null?0L:clan.getAllyId()!=0?((long)clan.getAllyId()<<1)|1L:((long)clan.getId()<<1);}
 static boolean member(Player p){return p!=null&&p.getClan()!=null&&p.getClan().isMember(p.getObjectId());}
 static boolean sameGroup(Clan a,Clan b){return a!=null&&b!=null&&scope(a)==scope(b);}
 static List<Player> peers(Clan clan){
  if(clan==null)return List.of();
  return World.getPlayers().stream().filter(p->member(p)&&sameGroup(clan,p.getClan())).toList();
 }
 static ChatType channel(Clan clan){return clan!=null&&clan.getAllyId()!=0?ChatType.ALLIANCE:ChatType.CLAN;}
 static boolean maySend(Player p,Clan clan){return clan!=null&&p!=null&&p.getClan()==clan&&clan.isMember(p.getObjectId());}
 static boolean broadcast(Player p,Clan clan,String text){
  if(!maySend(p,clan))return false;
  ChatType channel=channel(clan);CreatureSay packet=new CreatureSay(p,channel,p.getName(),text);
  if(!maySend(p,clan)||channel!=channel(clan))return false;
  if(channel==ChatType.ALLIANCE)clan.broadcastToOnlineAllyMembers(packet);
  else clan.broadcastCSToOnlineMembers(packet,p);
  return true;
 }
 static final class ClanGateway implements LocalCityDialogues.Gateway {
  public long now(){return System.currentTimeMillis();}
  public boolean human(Player p){return p!=null&&p.isOnline()&&!p.isInOfflineMode()&&!p.isInvisible()&&!PhantomManager.getInstance().isPhantom(p);}
  public boolean safe(Player p){return member(p)&&p.isOnline()&&!p.isDead()&&!p.isTeleporting()&&!p.isInOlympiadMode()&&!p.isOnEvent()&&!p.isRegisteredOnEvent()&&!p.isInDuel()&&!p.isInStoreMode()&&PhantomManager.getInstance().isPhantom(p);}
  public boolean audience(Player p){return member(p)&&peers(p.getClan()).stream().anyMatch(this::human);}
  public boolean quiet(Player p,String topic,long now){return true;}
  public long region(Player p){return p==null?0:scope(p.getClan());}
  public long broadcastOrigin(Player p){return LocalCityDialogues.broadcastOrigin(p);}
  public boolean near(Player a,Player b,int range){return member(a)&&member(b)&&sameGroup(a.getClan(),b.getClan());}
  public List<Player> nearby(Player p){return member(p)?peers(p.getClan()):List.of();}
  public boolean acceptsScene(LocalCityDialogues.Scene scene){return scene.lines().size()>=2&&scene.lines().size()<=5;}
  public long sceneReuseMillis(){return 14400000L;}
  public boolean rememberContext(){return false;}
  public boolean pauseAfterHumanMessage(){return true;}
  public boolean rotateInitiator(){return true;}
  public String traceChannel(){return "clan";}
  public String traceChannel(Player actor){return channel(actor.getClan())==ChatType.ALLIANCE?"alliance":"clan";}
  private boolean reaches(Collection<Player> actors,Player speaker,boolean global,long origin,boolean faction,boolean reciprocal){
   if(speaker==null||actors.isEmpty())return false;Player first=actors.iterator().next();
   if(!member(first)||actors.stream().anyMatch(actor->!near(first,actor,0)))return false;
   return peers(first.getClan()).stream().anyMatch(viewer->human(viewer)&&LocalCityDialogues.receivesBroadcast(viewer,speaker,global,origin,faction,reciprocal));
  }
  public boolean tradeAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin,boolean humanSender){return reaches(actors,speaker,global,origin,humanSender,false);}
  public boolean shoutAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin){return reaches(actors,speaker,global,origin,true,!global);}
  public void say(Player p,String text,String topic){Clan clan=p.getClan();if(!maySend(p,clan))throw new IllegalStateException("speaker left clan");pauseOtherScenes(clan);if(!broadcast(p,clan,text))throw new IllegalStateException("speaker changed clan or alliance");}
  public void schedule(Runnable r,long delay){ThreadPool.schedule(r,delay);}
 }
 static LocalCityDialogues.Settings settings(){
  var c=new ConfigReader("config/Custom/ChatBank.ini");int min=Math.max(120,c.getInt("ClanMinimumGapSeconds",180));int delay=Math.max(2500,c.getInt("ClanMinimumReplyMilliseconds",5000));
  return new LocalCityDialogues.Settings(c.getBoolean("ClanDialoguesEnabled",true),Path.of(c.getString("ClanDialogueFile","data/LocalClanDialogues.xml")),min,
   Math.max(min,c.getInt("ClanMaximumGapSeconds",420)),Math.max(60,c.getInt("ClanSpeakerGapSeconds",90)),Math.max(8,c.getInt("ClanLinesPerHour",48)),delay,
   Math.max(delay,c.getInt("ClanMaximumReplyMilliseconds",14000)));
 }
 private static final class Holder {
  static final LocalCityDialogues.Settings SETTINGS=settings();
  static final ClanGateway G=new ClanGateway();
  static final LocalCityDialogues.Controller C=new LocalCityDialogues.Controller(G,SETTINGS,new LocalCityDialogues.Bank(SETTINGS.file()),new Random());
 }
 private static final Map<Long,Long> READY=new ConcurrentHashMap<>();
 private static final Random RANDOM=new Random();
 static long initialDelay(Random random){return 60000L+random.nextInt(60001);}
 public static void tick(long now){
  if(!Holder.SETTINGS.enabled())return;Map<Long,Clan> groups=new LinkedHashMap<>();
  for(Player p:World.getPlayers())if(member(p)&&Holder.G.human(p))groups.put(scope(p.getClan()),p.getClan());
  READY.keySet().retainAll(groups.keySet());
  for(var e:groups.entrySet()){
   long ready=READY.computeIfAbsent(e.getKey(),id->now+initialDelay(RANDOM));if(now<ready)continue;
   List<Player> candidates=new ArrayList<>(peers(e.getValue()));Collections.shuffle(candidates,RANDOM);
   for(Player p:candidates)if(Holder.G.safe(p)&&Holder.C.tick(p,now))break;
  }
 }
 public static void humanSpoke(Player p){if(member(p))Holder.C.humanSpoke(p);}
 public static void tradeSpoke(Player p,boolean global){if(p!=null)Holder.C.tradeSpoke(p,global);}
 public static void shoutSpoke(Player p,boolean global){if(p!=null)Holder.C.shoutSpoke(p,global);}
 static void pauseOtherScenes(Clan clan){
  for(Player viewer:peers(clan))if(Holder.G.human(viewer)){LocalPartyDialogues.humanSpoke(viewer);LocalCityDialogues.humanSpoke(viewer);}
 }
 public static void npcSpoke(Clan clan){if(clan!=null){Holder.C.regionSpoke(scope(clan));pauseOtherScenes(clan);}}
 private LocalClanDialogues(){}
}
