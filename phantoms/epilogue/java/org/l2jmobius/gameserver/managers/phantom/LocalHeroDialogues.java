package org.l2jmobius.gameserver.managers.phantom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/** Offline hero-channel scenes. Only current online heroes can speak. */
public final class LocalHeroDialogues {
 static final class HeroGateway implements LocalCityDialogues.Gateway {
  public long now(){return System.currentTimeMillis();}
  public boolean human(Player p){return p!=null&&p.isOnline()&&!p.isInOfflineMode()&&!PhantomManager.getInstance().isPhantom(p);}
  public boolean safe(Player p){
   if(p==null||!p.isOnline()||!p.isHero()||!PhantomManager.getInstance().isPhantom(p)||p.isDead()||p.isTeleporting()||p.isInOlympiadMode()||p.isOnEvent()||p.isRegisteredOnEvent()||p.isInDuel()||region(p)<0)return false;
   if(GeneralConfig.BAN_CHAT_CHANNELS!=null&&GeneralConfig.BAN_CHAT_CHANNELS.contains(ChatType.HERO_VOICE)&&p.isChatBanned())return false;
   return !GeneralConfig.JAIL_DISABLE_CHAT||!p.isJailed()||p.isGM();
  }
  public boolean audience(Player p){return World.getPlayers().stream().anyMatch(viewer->human(viewer)&&canHear(viewer,p));}
  public boolean quiet(Player p,String topic,long now){return true;}
  public long region(Player p){
   if(!FactionSystemConfig.FACTION_SYSTEM_ENABLED||!FactionSystemConfig.FACTION_SPECIFIC_CHAT)return 0;
   if(p==null)return -1;if(p.isGood())return 1;if(p.isEvil())return 2;return -1;
  }
  public boolean near(Player a,Player b,int range){return a!=null&&b!=null&&region(a)>=0&&region(a)==region(b);}
  public List<Player> nearby(Player p){return new ArrayList<>(World.getPlayers());}
  public List<Player> audibleGroup(Player first,List<Player> actors){
   List<Player> best=List.of();
   for(Player viewer:World.getPlayers())if(human(viewer)){
    List<Player> heard=actors.stream().filter(actor->canHear(viewer,actor)).toList();
    if(heard.contains(first)&&heard.size()>best.size())best=heard;
   }
   return best;
  }
  public boolean sharedAudience(Collection<Player> actors){return World.getPlayers().stream().anyMatch(v->human(v)&&actors.stream().allMatch(a->canHear(v,a)));}
  public boolean acceptsScene(LocalCityDialogues.Scene scene){return scene.lines().size()>=2&&scene.lines().size()<=4;}
  public long sceneReuseMillis(){return 14400000L;}
  public boolean rememberContext(){return false;}
  public boolean pauseAfterHumanMessage(){return true;}
  public boolean rotateInitiator(){return true;}
  public String traceChannel(){return "hero";}
  public void say(Player p,String text,String topic){if(!send(p,text,this))throw new IllegalStateException("hero or audience no longer available");}
  public void schedule(Runnable r,long delay){ThreadPool.schedule(r,delay);}
 }
 static boolean canHear(Player viewer,Player speaker){return LocalCityDialogues.receivesBroadcast(viewer,speaker,true,0L,true,false);}
 static boolean send(Player speaker,String text,HeroGateway gateway){
  if(!gateway.safe(speaker)||!gateway.audience(speaker))return false;
  CreatureSay packet=new CreatureSay(speaker,ChatType.HERO_VOICE,speaker.getName(),text);
  if(!gateway.safe(speaker))return false;
  for(Player viewer:World.getPlayers())if(viewer!=null&&viewer!=speaker&&canHear(viewer,speaker))viewer.sendPacket(packet);
  return true;
 }
 static LocalCityDialogues.Settings settings(){
  var c=new ConfigReader("config/Custom/ChatBank.ini");int min=Math.max(300,c.getInt("HeroMinimumGapSeconds",420));int delay=Math.max(10000,c.getInt("HeroMinimumReplyMilliseconds",12000));
  return new LocalCityDialogues.Settings(c.getBoolean("HeroDialoguesEnabled",true),Path.of(c.getString("HeroDialogueFile","data/LocalHeroDialogues.xml")),min,
   Math.max(min,c.getInt("HeroMaximumGapSeconds",840)),Math.max(120,c.getInt("HeroSpeakerGapSeconds",180)),Math.max(8,c.getInt("HeroLinesPerHour",30)),delay,
   Math.max(delay,c.getInt("HeroMaximumReplyMilliseconds",22000)));
 }
 static final class Openers {
  final Path path;long nextCheck,modified=-1;List<String> lines=List.of();
  Openers(Path path){this.path=path;}
  synchronized List<String> get(long now){
   if(now<nextCheck)return lines;nextCheck=now+10000;
   try{long next=Files.getLastModifiedTime(path).toMillis();if(next==modified)return lines;
    List<String> loaded=Files.readAllLines(path).stream().map(String::strip).filter(s->!s.isEmpty()&&!s.startsWith("#")).toList();
    if(loaded.isEmpty()||loaded.stream().anyMatch(s->s.codePointCount(0,s.length())>180||s.indexOf('{')>=0||s.indexOf('}')>=0||s.contains("[[")))throw new IllegalArgumentException("invalid hero opener file");
    lines=List.copyOf(loaded);modified=next;
   }catch(Exception e){if(modified!=-2){java.util.logging.Logger.getLogger(LocalHeroDialogues.class.getName()).warning("Hero openers unavailable: "+e.getMessage());modified=-2;}}
   return lines;
  }
 }
 private static final class Holder {
  static final LocalCityDialogues.Settings S=settings();static final HeroGateway G=new HeroGateway();
  static final LocalCityDialogues.Controller C=new LocalCityDialogues.Controller(G,S,new LocalCityDialogues.Bank(S.file()),new Random());
  static final Openers O=new Openers(Path.of(new ConfigReader("config/Custom/ChatBank.ini").getString("HeroOpenersFile","data/LocalHeroOpeners.txt")));
  static final int SOLO_MIN=Math.max(900,new ConfigReader("config/Custom/ChatBank.ini").getInt("HeroSoloMinimumGapSeconds",1080));
  static final int SOLO_MAX=Math.max(SOLO_MIN,new ConfigReader("config/Custom/ChatBank.ini").getInt("HeroSoloMaximumGapSeconds",1800));
 }
 private static final Map<Long,Long> NEXT=new ConcurrentHashMap<>();
 private static final Map<String,Long> SOLO_REUSE=new ConcurrentHashMap<>();
 private static final Random RANDOM=new Random();
 static long initialDelay(Random random){return 120000L+random.nextInt(120001);}
 static long nextGap(LocalCityDialogues.Settings s,Random random){return (s.minGap()+random.nextInt(s.maxGap()-s.minGap()+1))*1000L;}
 public static void tick(long now){
  if(!Holder.S.enabled())return;Map<Long,List<Player>> groups=new LinkedHashMap<>();
  for(Player p:World.getPlayers())if(Holder.G.safe(p)&&Holder.G.audience(p))groups.computeIfAbsent(Holder.G.region(p),k->new ArrayList<>()).add(p);
  NEXT.keySet().retainAll(groups.keySet());
  for(var entry:groups.entrySet()){
   long scope=entry.getKey(),ready=NEXT.computeIfAbsent(scope,k->now+initialDelay(RANDOM));if(now<ready)continue;
   List<Player> candidates=entry.getValue();Collections.shuffle(candidates,RANDOM);boolean started=false,solo=false;
   for(Player p:candidates)if(Holder.G.audibleGroup(p,candidates).size()>=2&&Holder.C.tick(p,now)){started=true;break;}
   if(!started){
    List<String> choices=Holder.O.get(now).stream().filter(line->now>=SOLO_REUSE.getOrDefault(line,0L)).toList();
    if(!choices.isEmpty()){
     Player speaker=candidates.get(0);String text=choices.get(RANDOM.nextInt(choices.size()));
     if(send(speaker,text,Holder.G)){SOLO_REUSE.put(text,now+14400000L);LocalCityDialogues.traceScene("hero",scope,"hero-opener",1,1,speaker,text);started=true;solo=true;}
    }
   }
   if(started)NEXT.put(scope,now+(solo?(Holder.SOLO_MIN+RANDOM.nextInt(Holder.SOLO_MAX-Holder.SOLO_MIN+1))*1000L:nextGap(Holder.S,RANDOM)));else NEXT.put(scope,now+60000L);
  }
  if(SOLO_REUSE.size()>4096)SOLO_REUSE.entrySet().removeIf(e->e.getValue()<=now);
 }
 public static void humanSpoke(Player p){
  if(!Holder.G.human(p))return;long scope=Holder.G.region(p);if(scope<0)return;
  Holder.C.humanSpoke(p);NEXT.merge(scope,System.currentTimeMillis()+90000L,Math::max);
 }
 private LocalHeroDialogues(){}
}
