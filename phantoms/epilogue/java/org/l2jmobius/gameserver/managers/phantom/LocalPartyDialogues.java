package org.l2jmobius.gameserver.managers.phantom;

import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/** Pre-reviewed party conversations, independent of online language models. */
public final class LocalPartyDialogues {
 private static final Pattern PARTY_NAMES=Pattern.compile("(?iuU)(?<!\\w)(?:"
  +"дим(?:а|ы|е|у|ой|ка|ки|ке|ку|кой|он\\w*|ыч\\w*)|"
  +"паш(?:а|и|е|у|ей|ка|ки|ке|ку|кой)|"
  +"саш(?:а|и|е|у|ей|ка|ки|ке|ку|кой)|сан(?:я|и|е|ю|ей|ьк\\w*)|"
  +"л[её](?:х|ш)(?:а|и|е|у|ой|ей|ка|ки|ке|ку|кой)?|"
  +"сер[её]г(?:а|и|е|у|ой|ей)|серг(?:ей|ея|ею|еем)|"
  +"ван(?:я|и|е|ю|ей|ьк\\w*)|вит(?:я|и|е|ю|ей|ьк\\w*)|"
  +"ром(?:а|ы|е|у|ой|ка|ки|ке|ку|кой)|жен(?:я|и|е|ю|ей|ьк\\w*)|"
  +"вов(?:а|ы|е|у|ой|ка|ки|ке|ку|кой)|миш(?:а|и|е|у|ей|ка|ки|ке|ку|кой)|"
  +"тан(?:я|и|е|ю|ей|ьк\\w*)|кат(?:я|и|е|ю|ей|ьк\\w*)|маш(?:а|и|е|у|ей|ка|ки|ке|ку|кой)|"
  +"пет(?:я|и|е|ю|ей|ьк\\w*)|юр(?:а|ы|е|у|ой|ка|ки|ке|ку|кой)|кост(?:я|е|ю|ей|ьк\\w*)|"
  +"макс(?:а|у|ом|е|ик(?:а|у|ом|е)?|им(?:а|у|ом|е)?)?|"
  +"влад(?:а|у|ом|е|ик(?:а|у|ом|е)?|ислав(?:а|у|ом|е)?)?|"
  +"(?:андре|александр|алексей|дмитр|иван|егор|никит|арт[её]м|антон|игор|ил[ьья]|олег|кирилл|никола|коля|оля|павел|папич|бебе)\\w*)(?!\\w)");
 private static final Pattern PARTY_VOICE=Pattern.compile("(?iuU)(?<!\\w)(?:голос\\w*|войс\\w*|дискорд\\w*|discord\\w*|микрофон\\w*|созвон\\w*|тимспик\\w*|(?:в|из|через)\\s+(?:тс|дс)|(?:слышу|слышно|слышишь|слышали)\\s+(?:тебя|вас|его|её|их))(?!\\w)");
 private static final Set<String> ABSTRACT_ROLES=Set.of("A","B","C","D","А","Б","В","Г");
 static boolean partyContentAllowed(LocalCityDialogues.Scene scene){
  if(scene==null)return false;List<Pattern> fixedNames=new ArrayList<>();
  for(String role:scene.roles())if(role!=null&&!role.isBlank()&&!ABSTRACT_ROLES.contains(role))fixedNames.add(Pattern.compile("(?iuU)(?<!\\w)"+Pattern.quote(role)+"(?!\\w)"));
  for(var line:scene.lines()){
   String text=line.text();if(text==null||PARTY_NAMES.matcher(text).find()||PARTY_VOICE.matcher(text).find())return false;
   for(var name:fixedNames)if(name.matcher(text).find())return false;
  }
  return true;
 }
 static final class PartyGateway implements LocalCityDialogues.Gateway {
  private final Map<Party,Long> ids=new WeakHashMap<>();private long nextId=1;
  private final Map<LocalCityDialogues.Scene,Boolean> contentAllowed=Collections.synchronizedMap(new WeakHashMap<>());
  public long now(){return System.currentTimeMillis();}
  public boolean human(Player p){return p!=null&&p.isOnline()&&!p.isInOfflineMode()&&!PhantomManager.getInstance().isPhantom(p);}
  public boolean safe(Player p){return p!=null&&p.isOnline()&&!p.isDead()&&!p.isTeleporting()&&ordinarySceneAllowed(p.isOnEvent(),p.isRegisteredOnEvent(),p.isInOlympiadMode(),p.isInDuel())&&p.isInParty()&&rosterReady(p.getParty(),now())&&PhantomManager.getInstance().isPhantom(p);}
  public boolean audience(Player p){Party party=p.getParty();return party!=null&&party.getMembers().stream().anyMatch(member->member!=null&&member.getParty()==party&&human(member));}
  public boolean quiet(Player p,String topic,long now){return true;}
  public synchronized long region(Player p){return ids.computeIfAbsent(p.getParty(),x->nextId++);}
  public long broadcastOrigin(Player p){return LocalCityDialogues.broadcastOrigin(p);}
  public boolean near(Player a,Player b,int range){return a!=null&&b!=null&&a.getParty()!=null&&a.getParty()==b.getParty();}
  public List<Player> nearby(Player p){return p.getParty()==null?List.of():new ArrayList<>(p.getParty().getMembers());}
  private boolean partyReceives(Collection<Player> actors,Player speaker,boolean global,long origin,boolean respectFaction,boolean reciprocalBlock){
   if(speaker==null||actors.isEmpty())return false;Party party=actors.iterator().next().getParty();if(party==null||actors.stream().anyMatch(actor->actor.getParty()!=party))return false;
   return party.getMembers().stream().anyMatch(member->member!=null&&member.getParty()==party&&human(member)&&LocalCityDialogues.receivesBroadcast(member,speaker,global,origin,respectFaction,reciprocalBlock));
  }
  public boolean tradeAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin,boolean humanSender){return partyReceives(actors,speaker,global,origin,humanSender,false);}
  public boolean shoutAudienceOverlap(Collection<Player> actors,Player speaker,boolean global,long origin){return partyReceives(actors,speaker,global,origin,true,!global);}
  public void say(Player p,String text,String topic){Party party=p.getParty();if(party==null)throw new IllegalStateException("speaker left party");party.broadcastCreatureSay(new CreatureSay(p,ChatType.PARTY,p.getName(),text),p);}
  public void schedule(Runnable r,long delay){ThreadPool.schedule(r,delay);}
  public boolean rememberContext(){return false;}
  public String traceChannel(){return "party";}
  public boolean acceptsScene(LocalCityDialogues.Scene scene){return scene!=null&&scene.lines().size()>=5&&contentAllowed.computeIfAbsent(scene,LocalPartyDialogues::partyContentAllowed);}
  public boolean preferLongScenes(){return true;}
  public long sceneReuseMillis(){return 14400000L;}
  public int minimumBurstCapacity(){return 12;}
  public boolean pauseAfterHumanMessage(){return true;}
  public boolean rotateInitiator(){return true;}
 }
 static boolean ordinarySceneAllowed(boolean onEvent,boolean registeredForEvent,boolean olympiad,boolean duel){return !onEvent&&!registeredForEvent&&!olympiad&&!duel;}
 static LocalCityDialogues.Settings settings(){
  var c=new ConfigReader("config/Custom/ChatBank.ini");int min=Math.max(900,c.getInt("PartyMinimumGapSeconds",900));
  int minDelay=Math.max(6000,c.getInt("PartyMinimumReplyMilliseconds",7000));
  return new LocalCityDialogues.Settings(c.getBoolean("PartyDialoguesEnabled",true),Path.of(c.getString("PartyLongDialogueFile","data/LocalPartyLongDialogues.xml")),min,
   Math.max(min+300,c.getInt("PartyMaximumGapSeconds",1500)),Math.max(120,c.getInt("PartySpeakerGapSeconds",120)),
   Math.min(48,Math.max(8,c.getInt("PartyLinesPerHour",36))),minDelay,
   Math.max(minDelay,c.getInt("PartyMaximumReplyMilliseconds",18000)));
 }
 private static final class Holder {
  static final LocalCityDialogues.Settings SETTINGS=settings();
  static final LocalCityDialogues.Controller C=new LocalCityDialogues.Controller(new PartyGateway(),SETTINGS,new LocalCityDialogues.Bank(SETTINGS.file()),new Random());
 }
 static boolean partyChatterEnabled(){return Holder.SETTINGS.enabled();}
 static boolean automaticPartyEventsEnabled(){return false;}
 record PartyStart(Set<Integer> members,long readyAt){}
 static PartyStart afterRosterChange(PartyStart previous,Set<Integer> members,long now,Random random){
  if(previous==null||!previous.members().equals(members))return new PartyStart(Set.copyOf(members),now+90000L+random.nextInt(90001));
  return previous;
 }
 private static final Map<Party,PartyStart> PARTY_STARTS=new WeakHashMap<>();
 private static final Random PARTY_START_RANDOM=new Random();
 static Set<Integer> partyMembers(Party party){Set<Integer> members=new HashSet<>();for(Player p:party.getMembers())if(p!=null)members.add(p.getObjectId());return members;}
 static boolean rosterReady(Party party,long now){
  if(party==null)return false;Set<Integer> members=partyMembers(party);synchronized(PARTY_STARTS){PartyStart start=PARTY_STARTS.get(party);return start!=null&&start.members().equals(members)&&now>=start.readyAt();}
 }
 public static void tick(Player phantom,Player owner,long now){
  if(phantom==null||owner==null)return;Party party=owner.getParty();if(party==null||party!=phantom.getParty())return;
  Set<Integer> members=partyMembers(party);
  PartyStart start;boolean changed;synchronized(PARTY_STARTS){PartyStart previous=PARTY_STARTS.get(party);start=afterRosterChange(previous,members,now,PARTY_START_RANDOM);changed=previous!=null&&start!=previous;PARTY_STARTS.put(party,start);}
  if(changed&&phantom.getParty()==party)Holder.C.groupSpoke(phantom);
  if(now>=start.readyAt()&&owner.getParty()==party&&phantom.getParty()==party)Holder.C.tick(phantom,now);
 }
 public static void humanSpoke(Player p){if(p!=null&&p.isInParty())Holder.C.humanSpoke(p);}
 public static void tradeSpoke(Player p,boolean global){if(p!=null)Holder.C.tradeSpoke(p,global);}
 public static void shoutSpoke(Player p,boolean global){if(p!=null)Holder.C.shoutSpoke(p,global);}
 public static void npcSpoke(Player p){if(p!=null&&p.isInParty())Holder.C.groupSpoke(p);}
 private LocalPartyDialogues(){}
}
