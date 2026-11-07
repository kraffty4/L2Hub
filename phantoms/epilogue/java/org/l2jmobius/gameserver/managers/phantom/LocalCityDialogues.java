package org.l2jmobius.gameserver.managers.phantom;

import java.nio.file.*;import java.util.*;import java.util.logging.*;import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;import org.l2jmobius.commons.util.ConfigReader;import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.World;import org.l2jmobius.gameserver.entity.WorldObject;import org.l2jmobius.gameserver.entity.actor.Player;import org.l2jmobius.gameserver.entity.actor.Npc;import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
import org.l2jmobius.gameserver.config.custom.FactionSystemConfig;

/** Offline city conversations. Text can be replaced atomically without replacing code or restarting. */
public final class LocalCityDialogues {
 static final Logger LOG=Logger.getLogger(LocalCityDialogues.class.getName());static final String TOPIC="city-bank:";static final java.util.regex.Pattern YESTERDAY=java.util.regex.Pattern.compile("(?iu)(?<!\\p{L})вчера(?!\\p{L})");
 static final java.util.regex.Pattern ACQUAINTANCE=java.util.regex.Pattern.compile("(?iu)(?<!\\p{L})(?:паш[аыуе]|ван[яюеи]|л[её]х[ауы]|сан[яюеи]|саш[аыуе]|дим[аыуе]|сер[её]г[аыуе]|сер(?:ый|ого|ому))(?!\\p{L})|(?iu)куда он пропал");
 record Line(String id,String role,String text){}record Scene(String id,String topic,List<Line> lines,List<String> roles){}
 public record SceneContinuation(Player speaker,String text){}
 record ReviewedTurn(String questionId,String question,String answerId,String answer,String reply){}
 // A line written for another NPC is not automatically appropriate for a player.
 // Each approved triple is tied to the exact text; edits require renewed review.
 static final Map<String,ReviewedTurn> REVIEWED_PLAYER_TURNS=Map.ofEntries(
  Map.entry("global-work-008-t4",new ReviewedTurn("global-work-008-t2","это кому?","global-work-008-t3","ой бля, в пм хотел","бывает)")),
  Map.entry("global-work-089-t3",new ReviewedTurn("global-work-089-t1","я уже здоровался?","global-work-089-t2","со мной да)","блин совсем запутался")),
  Map.entry("global-work-258-t3",new ReviewedTurn("global-work-258-t1","вы на праздники куда-нибудь едете?","global-work-258-t2","я к родителям на пару дней","о, хорошо тебе съездить")),
  Map.entry("city-life-revision-0031-t4",new ReviewedTurn("city-life-revision-0031-t2","перегрелся?","city-life-revision-0031-t3","хз, я просто вкладок сорок открыл","ну ты сам ответил")),
  Map.entry("city-life-revision-0096-t3",new ReviewedTurn("city-life-revision-0096-t1","ты на том сервере давно играл?","city-life-revision-0096-t2","года три вроде","а почему ушёл?")),
  Map.entry("city-life-revision-0150-t4",new ReviewedTurn("city-life-revision-0150-t2","ну сколько выбираешь?","city-life-revision-0150-t3","третий день","ладно, может и правда долго")),
  Map.entry("city-life-revision-0308-t3",new ReviewedTurn("city-life-revision-0308-t1","ты сегодня чего такой тихий?","city-life-revision-0308-t2","да работал весь день, голова не варит","понятно, отдыхай")),
  Map.entry("city-life-revision-0334-t4",new ReviewedTurn("city-life-revision-0334-t2","написал ему?","city-life-revision-0334-t3","да, оказалось он меня не вспомнил","ну это бывает")),
  Map.entry("city-life-revision-0356-t5",new ReviewedTurn("city-life-revision-0356-t3","купил?","city-life-revision-0356-t4","не, там цена уже другая была","ну ахуенно")),
  Map.entry("city-additive-20261101-0025-t4",new ReviewedTurn("city-additive-20261101-0025-t2","всё донёс?","city-additive-20261101-0025-t3","мандарины по подъезду собирал","хорошо хоть не яйца")),
  Map.entry("city-additive-20261101-0032-t4",new ReviewedTurn("city-additive-20261101-0032-t2","какая вода?","city-additive-20261101-0032-t3","макароны варю","а то я подумал потоп")),
  Map.entry("city-additive-20261101-0035-t4",new ReviewedTurn("city-additive-20261101-0035-t2","простыл?","city-additive-20261101-0035-t3","вчера на концерте орал","а, тогда заслуженно")),
  Map.entry("city-additive-20261101-0039-t3",new ReviewedTurn("city-additive-20261101-0039-t1","ну как суп?","city-additive-20261101-0039-t2","соль забыл","бывает")),
  Map.entry("global-work-316-t3",new ReviewedTurn("global-work-316-t1","кто кроме л2 во что играет?","global-work-316-t2","я в доту иногда захожу","я тоже, но редко уже")),
  Map.entry("global-work-016-t3",new ReviewedTurn("global-work-016-t1","а ник у тебя откуда?","global-work-016-t2","да со школы ещё","я думал из игры какой-то")),
  Map.entry("city-life-revision-0329-t3",new ReviewedTurn("city-life-revision-0329-t1","кто сегодня ночью спать собирается?","city-life-revision-0329-t2","я собирался ещё час назад","а я вообще не заметил который час")),
  Map.entry("city-life-revision-0077-t4",new ReviewedTurn("city-life-revision-0077-t2","что туда уронил, всю солонку?","city-life-revision-0077-t3","примерно, крышка соскочила","блять, вот это обидно")),
  Map.entry("city-life-revision-0011-t3",new ReviewedTurn("city-life-revision-0011-t1","ребят, кто смотрел тот сериал про космос?","city-life-revision-0011-t2","их там миллион, какой именно","блин ну там ещё корабль был"))
 );
 // Only manually checked equivalents; no fuzzy match on partial words.
 static final Map<String,Set<String>> REVIEWED_ANSWER_ALIASES=Map.ofEntries(
  Map.entry("global-work-089-t3",Set.of("да","ага","да со мной","со мной да")),
  Map.entry("global-work-258-t3",Set.of("к родителям","к родителям на пару дней","я к родителям","к своим на пару дней")),
  Map.entry("global-work-316-t3",Set.of("в доту","в доту иногда","играю в доту","иногда в доту")),
  Map.entry("city-life-revision-0096-t3",Set.of("три года","года три","года 3","3 года","лет три")),
  Map.entry("city-life-revision-0150-t4",Set.of("три дня","уже три дня","3 день","третий день уже")),
  Map.entry("city-life-revision-0356-t5",Set.of("цена поменялась","нет цена другая","цена уже другая","там цена другая")),
  Map.entry("city-additive-20261101-0032-t4",Set.of("варю макароны","готовлю макароны","макароны готовлю")),
  Map.entry("city-additive-20261101-0035-t4",Set.of("на концерте орал","на концерте вчера орал","я вчера на концерте орал")),
  Map.entry("city-additive-20261101-0039-t3",Set.of("забыл соль","соли не добавил","забыл посолить","без соли"))
 );
 record Settings(boolean enabled,Path file,int minGap,int maxGap,int speakerGap,int perHour,int minDelay,int maxDelay){
  static Settings load(){var c=new ConfigReader("config/Custom/ChatBank.ini");int min=Math.max(30,c.getInt("CityMinimumGapSeconds",90));return new Settings(c.getBoolean("CityDialoguesEnabled",true),Path.of(c.getString("CityDialogueFile","data/LocalCityDialogues.xml")),min,Math.max(min,c.getInt("CityMaximumGapSeconds",180)),Math.max(30,c.getInt("CitySpeakerGapSeconds",120)),Math.max(8,c.getInt("CityLinesPerHour",120)),Math.max(1000,c.getInt("CityMinimumReplyMilliseconds",3000)),Math.max(3000,c.getInt("CityMaximumReplyMilliseconds",8000)));}
 }
 // Keep the configured limits while giving longer messages a little more typing time.
 static int replyDelay(Settings settings,String text,Random random){
  int min=settings.minDelay(),max=Math.max(min,settings.maxDelay());
  int length=text.codePointCount(0,text.length());
  int floor=min+(int)(((long)(max-min)*Math.min(length,100))/150);
  return floor+random.nextInt(max-floor+1);
 }
 interface Gateway {long now();boolean safe(Player p);boolean human(Player p);boolean audience(Player p);boolean quiet(Player p,String topic,long now);long region(Player p);boolean near(Player a,Player b,int range);List<Player> nearby(Player p);void say(Player p,String line,String topic);void schedule(Runnable r,long delay);default String traceChannel(){return "";}default String traceChannel(Player actor){return traceChannel();}default boolean acceptsScene(Scene scene){return true;}default boolean preferLongScenes(){return false;}default long sceneReuseMillis(){return 1800000L;}default int minimumBurstCapacity(){return 8;}default long broadcastOrigin(Player p){return region(p);}default List<Player> audibleGroup(Player first,List<Player> candidates){return candidates;}default boolean sharedAudience(Collection<Player> actors){return !actors.isEmpty()&&audience(actors.iterator().next());}default boolean audienceOverlap(Collection<Player> actors,WorldObject other){return other instanceof Player p&&actors.stream().anyMatch(a->near(a,p,1250));}default boolean tradeAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin,boolean humanSender){return audienceOverlap(actors,speaker);}default boolean shoutAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin){return audienceOverlap(actors,speaker);}default boolean partyAudienceOverlap(Collection<Player> actors,Player speaker){return audienceOverlap(actors,speaker);}default long humanQuietMillis(){return 0;}default boolean rememberContext(){return true;}default boolean pauseAfterHumanMessage(){return false;}default boolean rotateInitiator(){return false;}}
 static void traceScene(String channel,long region,String scene,int turn,int total,Player actor,String text){
  if(channel.isEmpty()||!Boolean.getBoolean("l2.chat.traceScenes"))return;
  try{LOG.info("CHAT_SCENE channel="+channel+" region="+region+" scene="+scene+" turn="+turn+"/"+total+" speaker="+actor.getName()+" text="+text);}catch(RuntimeException ignored){/* Diagnostics must never interrupt the conversation. */}
 }
 static long broadcastOrigin(Player p){return ((long)p.getInstanceId()<<32)|(MapRegionData.getInstance().getMapRegionLocId(p)&0xffffffffL);}
 static boolean receivesBroadcast(Player viewer,Player speaker,boolean global,long origin,boolean respectFaction,boolean reciprocalBlock){
  if(viewer==null||speaker==null||BlockList.isBlocked(viewer,speaker)||(reciprocalBlock&&BlockList.isBlocked(speaker,viewer)))return false;
  if(!global&&(viewer.getInstanceId()!=(int)(origin>>>32)||MapRegionData.getInstance().getMapRegionLocId(viewer)!=(int)origin))return false;
  return !respectFaction||!FactionSystemConfig.FACTION_SYSTEM_ENABLED||!FactionSystemConfig.FACTION_SPECIFIC_CHAT||(speaker.isGood()&&viewer.isGood())||(speaker.isEvil()&&viewer.isEvil());
 }
 static final class RuntimeGateway implements Gateway {
  public long now(){return System.currentTimeMillis();}
  public boolean human(Player p){return p!=null&&!PhantomManager.getInstance().isPhantom(p)&&p.isOnline()&&!p.isInOfflineMode()&&!p.isInvisible();}
  public boolean safe(Player p){if(p==null||!p.isOnline()||p.isDead()||p.isTeleporting()||p.isInCombat()||p.isCastingNow()||p.isInOlympiadMode()||p.isOnEvent()||p.isRegisteredOnEvent()||p.isInDuel()||p.getPvpFlag()!=0||!p.isInsideZone(ZoneId.PEACE))return false;var t=PhantomData.getInstance().getTemplate(p.getName());if(t==null)return false;boolean companion=PhantomCompanionManager.getInstance().isCompanion(t);boolean humanParty=p.getParty()!=null&&p.getParty().getMembers().stream().anyMatch(x->x!=null&&x.getParty()==p.getParty()&&human(x));return eligibleActor(t.isHunter(),companion,humanParty,companion&&companionCityEnabled(p));}
  boolean heardBy(Player actor,Player viewer){return actor.getInstanceId()==viewer.getInstanceId()&&actor.calculateDistance3D(viewer)<=1250;}
  public boolean audience(Player p){return World.getFirstVisibleObject(p,Player.class,x->human(x)&&heardBy(p,x))!=null;}
  public boolean quiet(Player p,String topic,long now){return PhantomQuiet.getInstance().allowed(p,topic,now);}
  public long region(Player p){return LocalCityDialogues.broadcastOrigin(p);}
  public boolean near(Player a,Player b,int range){return a.getInstanceId()==b.getInstanceId()&&a.calculateDistance2D(b)<=range;}
  public List<Player> nearby(Player p){return new ArrayList<>(World.getVisibleObjectsInRange(p,Player.class,600));}
  public List<Player> audibleGroup(Player first,List<Player> candidates){
   List<Player> best=List.of();
   for(Player viewer:World.getVisibleObjectsInRange(first,Player.class,1250))if(human(viewer)){
    List<Player> heard=candidates.stream().filter(actor->heardBy(actor,viewer)).toList();
    if(heard.contains(first)&&heard.size()>best.size())best=heard;
   }
   return best;
  }
  public boolean sharedAudience(Collection<Player> actors){
   if(actors.isEmpty())return false;Player first=actors.iterator().next();
   return World.getFirstVisibleObject(first,Player.class,viewer->human(viewer)&&actors.stream().allMatch(actor->heardBy(actor,viewer)))!=null;
  }
  public boolean audienceOverlap(Collection<Player> actors,WorldObject other){
   if(other==null)return false;
   for(Player actor:actors)if(World.getFirstVisibleObject(actor,Player.class,viewer->human(viewer)&&heardBy(actor,viewer)&&viewer.getInstanceId()==other.getInstanceId()&&viewer.calculateDistance3D(other)<=1250)!=null)return true;
   return false;
  }
  public boolean tradeAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin,boolean humanSender){
   return speaker!=null&&broadcastAudienceOverlap(actors,speaker,global,false,humanSender,origin);
  }
  public boolean shoutAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin){
   return broadcastAudienceOverlap(actors,speaker,global,!global,true,origin);
  }
  boolean broadcastAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,boolean reciprocalBlock,boolean respectFaction,long origin){
   if(speaker==null)return false;
   for(Player actor:actors)if(World.getFirstVisibleObject(actor,Player.class,viewer->human(viewer)&&heardBy(actor,viewer)&&receivesBroadcast(viewer,speaker,global,origin,respectFaction,reciprocalBlock))!=null)return true;
   return false;
  }
  public boolean partyAudienceOverlap(Collection<Player> actors,Player speaker){
   if(speaker==null||speaker.getParty()==null)return false;var party=speaker.getParty();
   for(Player actor:actors)if(World.getFirstVisibleObject(actor,Player.class,viewer->human(viewer)&&viewer.getParty()==party&&party.getMembers().contains(viewer)&&heardBy(actor,viewer))!=null)return speaker.getParty()==party;
   return false;
  }
  public boolean rotateInitiator(){return true;}
  public String traceChannel(){return "world";}
  public long humanQuietMillis(){return 15000L;}
  public void say(Player p,String line,String topic){PhantomChat.getInstance().hunterSay(p,line,topic);}
  public void schedule(Runnable r,long delay){ThreadPool.schedule(r,delay);}
 }
 static boolean eligibleActor(boolean hunter,boolean companion,boolean humanParty,boolean cityMode){return hunter&&!companion&&!humanParty;}
 static final class Bank {
  final Path path;volatile List<Scene> scenes=List.of();long nextCheck,lastModified=-1;Bank(Path path){this.path=path;}
  synchronized List<Scene> get(long now){if(now<nextCheck)return scenes;nextCheck=now+10000;try{long changed=Files.getLastModifiedTime(path).toMillis();if(changed==lastModified)return scenes;List<Scene> loaded=read(path);scenes=loaded;lastModified=changed;LOG.info("LocalCityDialogues: loaded "+loaded.size()+" complete city conversations.");}catch(Exception e){if(lastModified!=-2){LOG.warning("LocalCityDialogues: keeping previous bank: "+e.getMessage());lastModified=-2;}}return scenes;}
  static List<Scene> read(Path path)throws Exception{
   var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);
   Document d=f.newDocumentBuilder().parse(path.toFile());if(!d.getDocumentElement().getTagName().equals("dialogues")||!d.getDocumentElement().getAttribute("format").equals("1"))throw new IllegalArgumentException("Unsupported dialogue format");
   List<Scene> result=new ArrayList<>();Set<String> ids=new HashSet<>(),lineIds=new HashSet<>();NodeList list=d.getElementsByTagName("scene");
   for(int i=0;i<list.getLength();i++){Element e=(Element)list.item(i);String id=e.getAttribute("id"),topic=e.getAttribute("topic");if(id.isBlank()||!ids.add(id))throw new IllegalArgumentException("Repeated or missing scene ID");if(e.getAttribute("requires_verified_context").equals("true"))continue;List<Line> lines=new ArrayList<>();LinkedHashSet<String> roles=new LinkedHashSet<>();NodeList children=e.getElementsByTagName("line");
    for(int n=0;n<children.getLength();n++){Element x=(Element)children.item(n);String lid=x.getAttribute("id"),role=x.getAttribute("role"),text=x.getTextContent().strip();if(lid.isBlank()||!lineIds.add(lid)||role.isBlank()||text.isBlank()||text.codePointCount(0,text.length())>180||text.chars().anyMatch(c->c<32)||text.contains("[[")||text.contains("{")||text.contains("}"))throw new IllegalArgumentException("Invalid line in "+id);lines.add(new Line(lid,role,text));roles.add(role);}
    if(lines.size()<2||lines.size()>12||roles.size()<2||roles.size()>4)throw new IllegalArgumentException("Invalid scene size: "+id);result.add(new Scene(id,topic,List.copyOf(lines),List.copyOf(roles)));
   }if(result.isEmpty())throw new IllegalArgumentException("Empty dialogue bank");return List.copyOf(result);
  }
 }
 static final class Session {final long region;final Scene scene;final Map<String,Player> roles;final Player anchor;int index;Session(long r,Scene s,Map<String,Player> p,Player a){region=r;scene=s;roles=p;anchor=a;}}
 record SceneKey(long region,String id){}
 record HumanSpeech(Player speaker,long expiresAt){}
 record ShoutSpeech(Player speaker,boolean global,long origin,long expiresAt){}
 record TradeSpeech(Player speaker,boolean global,long origin,boolean humanSender,long expiresAt){}
 static final class Controller {
  final Gateway g;final Settings settings;final Bank bank;final Random random;final Map<Long,Session> active=new HashMap<>();final Map<Integer,Session> busy=new HashMap<>();final Map<Integer,Long> nextLook=new HashMap<>(),speakerNext=new HashMap<>();final Map<Long,Long> areaNext=new HashMap<>();final Map<SceneKey,Long> sceneNext=new HashMap<>();final Map<Long,Deque<Long>> budget=new HashMap<>();final Map<Long,Deque<String>> recentTopics=new HashMap<>();final Map<Long,Deque<Boolean>> recentYesterday=new HashMap<>(),recentAcquaintances=new HashMap<>();final Deque<HumanSpeech> recentHumanSpeech=new ArrayDeque<>();final Deque<ShoutSpeech> recentShouts=new ArrayDeque<>();final Deque<TradeSpeech> recentTrades=new ArrayDeque<>();final Map<Integer,Long> lastHumanMessage=new HashMap<>();
  Controller(Gateway g,Settings s,Bank b,Random r){this.g=g;settings=s;bank=b;random=r;}
  synchronized boolean tick(Player first,long now){
   if(!settings.enabled())return false;if(busy.containsKey(first.getObjectId()))return true;if(now<nextLook.getOrDefault(first.getObjectId(),0L))return false;nextLook.put(first.getObjectId(),now+10000);
   recentHumanSpeech.removeIf(speech->speech.expiresAt()<=now);
   recentShouts.removeIf(speech->speech.expiresAt()<=now);
   long shoutResume=recentShouts.stream().filter(speech->g.shoutAudienceOverlap(List.of(first),speech.speaker(),speech.global(),speech.origin())).mapToLong(ShoutSpeech::expiresAt).min().orElse(Long.MAX_VALUE);
   if(shoutResume!=Long.MAX_VALUE){nextLook.put(first.getObjectId(),Math.min(now+10000L,shoutResume));return false;}
   recentTrades.removeIf(speech->speech.expiresAt()<=now);
   long tradeResume=recentTrades.stream().filter(speech->g.tradeAudienceOverlap(List.of(first),speech.speaker(),speech.global(),speech.origin(),speech.humanSender())).mapToLong(TradeSpeech::expiresAt).min().orElse(Long.MAX_VALUE);
   if(tradeResume!=Long.MAX_VALUE){nextLook.put(first.getObjectId(),Math.min(now+10000L,tradeResume));return false;}
   if(!g.safe(first)||!g.audience(first)||now<speakerNext.getOrDefault(first.getObjectId(),0L)||recentHumanSpeech.stream().anyMatch(speech->g.near(first,speech.speaker(),1250)))return false;long region=g.region(first);if(active.containsKey(region)||now<areaNext.getOrDefault(region,0L))return false;
   List<Scene> all=bank.get(now);if(all.isEmpty())return false;List<Player> actors=new ArrayList<>();actors.add(first);for(Player p:g.nearby(first))if(p!=first&&g.safe(p)&&!busy.containsKey(p.getObjectId())&&now>=speakerNext.getOrDefault(p.getObjectId(),0L)&&g.region(p)==region&&g.near(first,p,600))actors.add(p);if(actors.size()<2)return false;
   if(sceneNext.size()>4096)sceneNext.entrySet().removeIf(e->e.getValue()<=now);
   Deque<Long> times=budget.computeIfAbsent(region,k->new ArrayDeque<>());while(!times.isEmpty()&&now-times.peekFirst()>=3600000)times.removeFirst();
   // Spread the hourly budget over rolling five-minute windows. Without this,
   // many nearby speakers can consume the entire allowance early in the hour.
   int recentCount=(int)times.stream().filter(t->now-t<300000L).count();
   int windowCap=Math.max(g.minimumBurstCapacity(),(settings.perHour()+11)/12);
   int remaining=Math.min(settings.perHour()-times.size(),windowCap-recentCount);Map<String,List<Player>> eligibleByTopic=new HashMap<>();
   java.util.function.Predicate<Scene> workable=s->{
    if(!g.acceptsScene(s)||now<sceneNext.getOrDefault(new SceneKey(region,s.id()),0L)||s.lines().size()>remaining)return false;
    List<Player> eligible=eligibleByTopic.computeIfAbsent(s.topic(),key->g.audibleGroup(first,actors.stream().filter(p->g.quiet(p,TOPIC+key,now)).toList()));
    return eligible.contains(first)&&eligible.size()>=s.roles().size();
   };
   Deque<String> recent=recentTopics.computeIfAbsent(region,k->new ArrayDeque<>());Deque<Boolean> past=recentYesterday.computeIfAbsent(region,k->new ArrayDeque<>());
   Deque<Boolean> acquaintances=recentAcquaintances.computeIfAbsent(region,k->new ArrayDeque<>());
   List<Scene> pool=all.stream().filter(workable).toList();if(pool.isEmpty())return false;
   if(past.contains(Boolean.TRUE)){List<Scene> withoutYesterday=pool.stream().filter(s->!mentionsYesterday(s)).toList();if(!withoutYesterday.isEmpty())pool=withoutYesterday;}
   if(acquaintances.contains(Boolean.TRUE)){List<Scene> withoutAcquaintance=pool.stream().filter(s->!mentionsAcquaintance(s)).toList();if(!withoutAcquaintance.isEmpty())pool=withoutAcquaintance;}
   List<Scene> choices=pool.stream().filter(s->!recent.contains(s.topic())).toList();
   if(choices.isEmpty())choices=pool.stream().filter(s->!s.topic().equals(recent.peekLast())).toList();
   if(choices.isEmpty())choices=pool;
   if(g.preferLongScenes()){
    int roll=random.nextInt(100);int threshold=roll<60?8:roll<90?6:0;
    if(threshold>0){List<Scene> longer=choices.stream().filter(s->s.lines().size()>=threshold).toList();
     if(longer.isEmpty()&&threshold==8)longer=choices.stream().filter(s->s.lines().size()>=6).toList();
     if(!longer.isEmpty())choices=longer;}
   }
   Scene scene=choices.get(random.nextInt(choices.size()));List<Player> eligible=new ArrayList<>(eligibleByTopic.get(scene.topic()));
   eligible.remove(first);Collections.shuffle(eligible,random);eligible.add(0,first);List<Player> selected=new ArrayList<>(eligible.subList(0,scene.roles().size()));
   if(g.rotateInitiator())Collections.shuffle(selected,random);
   Map<String,Player> bindings=new LinkedHashMap<>();for(int i=0;i<scene.roles().size();i++)bindings.put(scene.roles().get(i),selected.get(i));
   Session session=new Session(region,scene,Map.copyOf(bindings),first);active.put(region,session);for(Player p:bindings.values())busy.put(p.getObjectId(),session);areaNext.put(region,now+(settings.minGap()+random.nextInt(settings.maxGap()-settings.minGap()+1))*1000L);sceneNext.put(new SceneKey(region,scene.id()),now+g.sceneReuseMillis());recent.addLast(scene.topic());if(recent.size()>3)recent.removeFirst();past.addLast(mentionsYesterday(scene));if(past.size()>3)past.removeFirst();acquaintances.addLast(mentionsAcquaintance(scene));if(acquaintances.size()>3)acquaintances.removeFirst();advance(session);return true;
  }
  static boolean mentionsYesterday(Scene scene){return scene.lines().stream().anyMatch(line->YESTERDAY.matcher(line.text()).find());}
  static boolean mentionsAcquaintance(Scene scene){return scene.lines().stream().anyMatch(line->ACQUAINTANCE.matcher(line.text()).find());}
  synchronized void advance(Session s){if(active.get(s.region)!=s)return;Player anchor=s.anchor;if(!g.safe(anchor)||g.region(anchor)!=s.region||s.roles.values().stream().anyMatch(p->!g.safe(p)||!g.near(anchor,p,600))||!g.audience(anchor)||!g.sharedAudience(s.roles.values())){finish(s);return;}
   long now=g.now();recentTrades.removeIf(speech->speech.expiresAt()<=now);recentShouts.removeIf(speech->speech.expiresAt()<=now);recentHumanSpeech.removeIf(speech->speech.expiresAt()<=now);
   long tradeResume=recentTrades.stream().filter(speech->g.tradeAudienceOverlap(s.roles.values(),speech.speaker(),speech.global(),speech.origin(),speech.humanSender())).mapToLong(TradeSpeech::expiresAt).max().orElse(now);
   long shoutResume=recentShouts.stream().filter(speech->g.shoutAudienceOverlap(s.roles.values(),speech.speaker(),speech.global(),speech.origin())).mapToLong(ShoutSpeech::expiresAt).max().orElse(now);
   long humanResume=recentHumanSpeech.stream().filter(speech->s.roles.values().stream().anyMatch(actor->g.near(actor,speech.speaker(),1250))).mapToLong(HumanSpeech::expiresAt).max().orElse(now);
   long resume=Math.max(humanResume,Math.max(tradeResume,shoutResume));
   if(resume>now){g.schedule(()->advance(s),resume-now);return;}
   Line line=s.scene.lines().get(s.index);Player actor=s.roles.get(line.role());try{g.say(actor,line.text(),TOPIC+s.scene.topic());traceScene(g.traceChannel(actor),s.region,s.scene.id(),s.index+1,s.scene.lines().size(),actor,line.text());if(g.rememberContext())LocalReplyBank.rememberSceneLine(actor,line.id(),line.text(),g.now());budget.computeIfAbsent(s.region,k->new ArrayDeque<>()).addLast(g.now());speakerNext.put(actor.getObjectId(),g.now()+settings.speakerGap()*1000L);}catch(RuntimeException e){LOG.log(Level.WARNING,"City conversation stopped",e);finish(s);return;}
   if(++s.index>=s.scene.lines().size()){finish(s);return;}long delay=replyDelay(settings,s.scene.lines().get(s.index).text(),random);g.schedule(()->advance(s),delay);
  }
  void finish(Session s){active.remove(s.region,s);for(Player p:s.roles.values())busy.remove(p.getObjectId(),s);}
  synchronized void humanSpoke(Player human){humanSpoke(human,null);}
  synchronized SceneContinuation humanSpoke(Player human,String message){
   if(!g.human(human))return null;SceneContinuation result=null;int matches=0,interrupted=0;
   boolean directed=message==null||g.pauseAfterHumanMessage()||active.values().stream().filter(s->s.roles.values().stream().anyMatch(p->g.near(p,human,1250))).anyMatch(s->addressesParticipant(s,message)||mightAnswerQuestion(s,message)||continuation(s,message)!=null);
   if(g.humanQuietMillis()>0){long now=g.now();lastHumanMessage.entrySet().removeIf(e->now-e.getValue()>60000L);Long previous=lastHumanMessage.put(human.getObjectId(),now);long pause=directed?(previous!=null&&now-previous<30000L?Math.max(g.humanQuietMillis(),30000L):g.humanQuietMillis()):5000L;long expires=Math.max(now+pause,recentHumanSpeech.stream().filter(speech->speech.speaker()==human).mapToLong(HumanSpeech::expiresAt).max().orElse(now));recentHumanSpeech.removeIf(speech->speech.expiresAt()<=now||speech.speaker()==human);recentHumanSpeech.addLast(new HumanSpeech(human,expires));while(recentHumanSpeech.size()>256)recentHumanSpeech.removeFirst();}
   for(Session s:new ArrayList<>(active.values()))if(s.roles.values().stream().anyMatch(p->g.near(p,human,1250))){
    SceneContinuation candidate=continuation(s,message);
    if(message!=null&&!g.pauseAfterHumanMessage()&&candidate==null&&!addressesParticipant(s,message)&&!mightAnswerQuestion(s,message))continue;
    interrupted++;
    if(candidate!=null){result=candidate;matches++;}
    finish(s);long now=g.now();
    if(g.pauseAfterHumanMessage())areaNext.merge(s.region,now+30000L,Math::max);
    else{long resume=Math.min(areaNext.getOrDefault(s.region,now+30000),now+30000);areaNext.put(s.region,Math.max(now+15000,resume));}
   }
   if(g.pauseAfterHumanMessage())areaNext.merge(g.region(human),g.now()+30000L,Math::max);
   return matches==1&&interrupted==1?result:null;
  }
  static SceneContinuation continuation(Session s,String message){
   if(message==null||s.index<1||s.index+1>=s.scene.lines().size())return null;
   Line question=s.scene.lines().get(s.index-1),expected=s.scene.lines().get(s.index),reply=s.scene.lines().get(s.index+1);
   Player speaker=s.roles.get(question.role());
   if(speaker==null||!question.text().contains("?")||expected.role().equals(question.role())||!reply.role().equals(question.role()))return null;
   ReviewedTurn approved=REVIEWED_PLAYER_TURNS.get(reply.id());
   if(approved==null||!approved.questionId().equals(question.id())||!approved.question().equals(question.text())||!approved.answerId().equals(expected.id())||!approved.answer().equals(expected.text())||!approved.reply().equals(reply.text()))return null;
   String actual=normalize(LocalReplyBank.withoutAddress(speaker,message)),target=normalize(expected.text());
   boolean same=target.equals(actual)||REVIEWED_ANSWER_ALIASES.getOrDefault(reply.id(),Set.of()).contains(actual);
   return !target.isEmpty()&&same?new SceneContinuation(speaker,reply.text()):null;
  }
  static String normalize(String text){return text==null?"":text.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();}
  static boolean addressesParticipant(Session s,String message){
   if(message==null)return false;String full=normalize(message);
   return s.roles.values().stream().anyMatch(p->!full.equals(normalize(LocalReplyBank.withoutAddress(p,message))));
  }
  static boolean mightAnswerQuestion(Session s,String message){
   if(message==null||s.index<1||!s.scene.lines().get(s.index-1).text().contains("?"))return false;
   return !normalize(message).isEmpty();
  }
  synchronized void npcSpoke(Player p,String topic){
   if(p==null)return;
   boolean scripted=topic!=null&&topic.startsWith(TOPIC);
   Session source=busy.get(p.getObjectId());
   for(Session s:new ArrayList<>(active.values())){
    if(scripted&&s==source)continue;
    if(g.audienceOverlap(s.roles.values(),p)){
     finish(s);
     areaNext.merge(s.region,g.now()+15000L,Math::max);
    }
   }
  }
  synchronized void otherNpcSpoke(Npc npc){
   if(npc==null)return;
   for(Session s:new ArrayList<>(active.values()))if(g.audienceOverlap(s.roles.values(),npc)){finish(s);areaNext.merge(s.region,g.now()+15000L,Math::max);}
  }
  synchronized void tradeSpoke(Player speaker,boolean global){
   if(speaker==null)return;
   long now=g.now(),origin=global?0:g.broadcastOrigin(speaker);boolean humanSender=g.human(speaker);
   recentTrades.removeIf(speech->speech.expiresAt()<=now||(speech.speaker()==speaker&&speech.global()==global&&(global||speech.origin()==origin)));
   recentTrades.addLast(new TradeSpeech(speaker,global,origin,humanSender,now+5000L));while(recentTrades.size()>256)recentTrades.removeFirst();
  }
  synchronized boolean hasActiveTradeScene(int region){
   return active.values().stream().anyMatch(s->region==-1||(int)s.region==region);
  }
  synchronized void shoutSpoke(Player speaker,boolean global){
   if(speaker==null||!g.human(speaker))return;
   long now=g.now(),origin=global?0:g.broadcastOrigin(speaker);recentShouts.removeIf(speech->speech.expiresAt()<=now||(speech.speaker()==speaker&&speech.global()==global&&(global||speech.origin()==origin)));recentShouts.addLast(new ShoutSpeech(speaker,global,origin,now+5000L));while(recentShouts.size()>256)recentShouts.removeFirst();
  }
  synchronized void partySpoke(Player speaker){
   if(speaker==null)return;
   for(Session s:new ArrayList<>(active.values()))if(g.partyAudienceOverlap(s.roles.values(),speaker)){finish(s);areaNext.merge(s.region,g.now()+15000L,Math::max);}
  }
  synchronized void regionSpoke(long region){Session s=active.get(region);if(s!=null)finish(s);areaNext.merge(region,g.now()+15000L,Math::max);}
  synchronized void groupSpoke(Player p){regionSpoke(g.region(p));}
 }
 private static final class Holder {static final Settings SETTINGS=Settings.load();static final Controller C=new Controller(new RuntimeGateway(),SETTINGS,new Bank(SETTINGS.file()),new Random());}
 static boolean cohesiveCityAudience(List<Player> speakers,List<Player> listeners){
  if(speakers.size()<2||listeners.isEmpty())return false;
  for(Player a:speakers)for(Player b:speakers)if(a.getInstanceId()!=b.getInstanceId()||a.calculateDistance2D(b)>600)return false;
  return listeners.stream().anyMatch(viewer->speakers.stream().allMatch(actor->actor.getInstanceId()==viewer.getInstanceId()&&actor.calculateDistance3D(viewer)<=1250));
 }
 static boolean readyCityGroup(List<Player> speakers,List<Player> listeners,java.util.function.Predicate<Player> busy){
  return speakers.stream().filter(busy.negate()).limit(2).count()==2&&cohesiveCityAudience(speakers,listeners);
 }
 static boolean hasIndependentPartner(Player companion,List<Player> listeners,Collection<Player> nearby,java.util.function.Predicate<Player> eligible){
  return nearby.stream().anyMatch(other->other!=null&&other!=companion&&eligible.test(other)&&cohesiveCityAudience(List.of(companion,other),listeners));
 }
 static boolean selectCompanionCityMode(Player p,List<Player> speakers,List<Player> listeners,java.util.function.Predicate<Player> busy,java.util.function.Supplier<Collection<Player>> nearby,java.util.function.Predicate<Player> independent){
  if(!speakers.contains(p))return false;
  if(readyCityGroup(speakers,listeners,busy))return true;
  return speakers.size()==1&&!busy.test(p)&&hasIndependentPartner(p,listeners,nearby.get(),independent);
 }
 public static boolean companionCityEnabled(Player p){
  if(p==null||!p.isInsideZone(ZoneId.PEACE)||p.getParty()==null||!Holder.SETTINGS.enabled()||Holder.C.bank.get(System.currentTimeMillis()).isEmpty())return false;
  var party=p.getParty();List<Player> speakers=new ArrayList<>(),listeners=new ArrayList<>();
  for(Player member:party.getMembers()){
   if(member==null||member.getParty()!=party||!member.isOnline())continue;
   if(!PhantomManager.getInstance().isPhantom(member)){if(!member.isInOfflineMode()&&!member.isInvisible())listeners.add(member);continue;}
   if(member.isDead()||member.isTeleporting())continue;
   var template=PhantomData.getInstance().getTemplate(member.getName());
   if(template==null||!PhantomCompanionManager.getInstance().isCompanion(template)||!member.isInsideZone(ZoneId.PEACE)||member.isInCombat()||member.isInOlympiadMode()||member.isOnEvent()||member.isRegisteredOnEvent()||member.isInDuel()||member.getPvpFlag()!=0)return false;
   speakers.add(member);
  }
  if(p.getParty()!=party)return false;
  var gateway=new RuntimeGateway();long region=gateway.region(p);
  return selectCompanionCityMode(p,speakers,listeners,Player::isCastingNow,()->World.getVisibleObjectsInRange(p,Player.class,600),other->{
   var t=PhantomData.getInstance().getTemplate(other.getName());
   return t!=null&&t.isHunter()&&PhantomManager.getInstance().isPhantom(other)&&!PhantomCompanionManager.getInstance().isCompanion(t)&&gateway.region(other)==region&&gateway.safe(other);
  })&&p.getParty()==party;
 }
 public static boolean tick(Player p,PhantomTemplate t,long now){if(t==null||(!t.isHunter()&&!PhantomCompanionManager.getInstance().isCompanion(t)))return false;if(Holder.C.tick(p,now))return true;return LocalReplyBank.tryAmbient(p,t,now);}
 public static void humanSpoke(Player p){Holder.C.humanSpoke(p);}
 public static SceneContinuation humanSpoke(Player p,String text){return Holder.C.humanSpoke(p,text);}
 public static void npcSpoke(Player p,String topic){Holder.C.npcSpoke(p,topic);}
 public static void tradeSpoke(Player p,boolean global){Holder.C.tradeSpoke(p,global);LocalPartyDialogues.tradeSpoke(p,global);LocalClanDialogues.tradeSpoke(p,global);PhantomChatFlow.getInstance().noteTrade(p,global);}
 public static boolean hasActiveTradeScene(int region){return Holder.C.hasActiveTradeScene(region);}
 public static void shoutSpoke(Player p,boolean global){Holder.C.shoutSpoke(p,global);LocalPartyDialogues.shoutSpoke(p,global);LocalClanDialogues.shoutSpoke(p,global);}
 public static void partySpoke(Player p){Holder.C.partySpoke(p);}
 public static void otherNpcSpoke(Npc p){Holder.C.otherNpcSpoke(p);}
 private LocalCityDialogues(){}
}
