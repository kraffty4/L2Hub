package org.l2jmobius.gameserver.managers.phantom;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import static java.lang.constant.ConstantDescs.*;

/** Реальный Controller/preset/hero-gate/group helpers; actor/доставка/часы — записываемые fixtures. */
public final class DialogueRuntimeCheck {
 static sun.misc.Unsafe u;static Class<?> actorClass;static int checks,next=960000;
 static PhantomManager phantomRegistry;static Map<Integer,Object> phantomActors=new HashMap<>();
 static List<Player> visible=new ArrayList<>();static final Map<Player,State> states=new IdentityHashMap<>();
 static final class State{int id;String name;Set<String> flags=new HashSet<>(Set.of("isOnline"));Party party;Clan clan;BlockList blocked;Set<Integer> blockedIds=new HashSet<>();List<ServerPacket> packets=new ArrayList<>();}
 public static PhantomManager registry(){return phantomRegistry;}
 public static Collection<Player> players(){return visible;}
 public static boolean flag(Player p,String key){return states.get(p).flags.contains(key);}
 public static int id(Player p){return states.get(p).id;}
 public static String name(Player p){return states.get(p).name;}
 public static Party party(Player p){return states.get(p).party;}
 public static Clan clan(Player p){return states.get(p).clan;}
 public static BlockList block(Player p){return states.get(p).blocked;}
 public static void packet(Player p,ServerPacket packet){states.get(p).packets.add(packet);}
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static <T>T blank(Class<T> c)throws Exception{return c.cast(u.allocateInstance(c));}
 static void put(Object o,Class<?> c,String field,Object value)throws Exception{Field f=c.getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
 static void fixture()throws Exception{
  Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);u=(sun.misc.Unsafe)f.get(null);
  ClassDesc host=DialogueRuntimeCheck.class.describeConstable().orElseThrow(),pd=Player.class.describeConstable().orElseThrow();
  actorClass=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.DialogueFixturePlayer"),b->{
   b.withFlags(0x31);b.withSuperclass(pd);
   for(String m:List.of("isOnline","isHero","isDead","isTeleporting","isInOlympiadMode","isOnEvent","isRegisteredOnEvent","isInDuel","isInOfflineMode","isInvisible","isChatBanned","isJailed","isGM","isInStoreMode","getMessageRefusal"))b.withMethodBody(m,MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).ldc(m).invokestatic(host,"flag",MethodTypeDesc.of(CD_boolean,pd,CD_String)).ireturn());
   for(var entry:Map.of("getObjectId","id","getName","name","getParty","party","getClan","clan","getBlockList","block").entrySet()){
    try{Class<?> ret=Player.class.getMethod(entry.getKey()).getReturnType();ClassDesc type=ret.describeConstable().orElseThrow();b.withMethodBody(entry.getKey(),MethodTypeDesc.of(type),1,c->{c.aload(0).invokestatic(host,entry.getValue(),MethodTypeDesc.of(type,pd));if(ret==int.class)c.ireturn();else c.areturn();});}catch(Exception e){throw new RuntimeException(e);}
   }
   b.withMethodBody("sendPacket",MethodTypeDesc.of(CD_void,ServerPacket.class.describeConstable().orElseThrow()),1,c->c.aload(0).aload(1).invokestatic(host,"packet",MethodTypeDesc.of(CD_void,pd,ServerPacket.class.describeConstable().orElseThrow())).return_());
  }));
  phantomRegistry=blank(PhantomManager.class);put(phantomRegistry,PhantomManager.class,"_phantoms",phantomActors);
 }
 static Player actor(String wanted,boolean phantom)throws Exception{
  Player p=blank(actorClass.asSubclass(Player.class));State s=new State();s.id=++next;s.name="Fixture"+s.id;
  while(wanted!=null&&!LocalDialogueBindings.voice(s.id,s.name).equals(wanted)){s.id=++next;s.name="Fixture"+s.id;}
  states.put(p,s);s.blocked=blank(BlockList.class);put(s.blocked,BlockList.class,"_owner",p);put(s.blocked,BlockList.class,"_blockList",s.blockedIds);
  if(phantom)phantomActors.put(s.id,null);return p;
 }
 static final class G implements LocalCityDialogues.Gateway {
  long now=1000000;boolean heard=true,heroMode,partyMode,clanMode;Set<Player> allowed=new HashSet<>(),humans=new HashSet<>();List<Player> actors=new ArrayList<>();List<Player> speakers=new ArrayList<>();List<String> texts=new ArrayList<>();Deque<Runnable> queue=new ArrayDeque<>();Deque<Long> delays=new ArrayDeque<>();Party boundParty;Clan boundClan;
  public long now(){return now;}public boolean safe(Player p){return allowed.contains(p)&&(!heroMode||new LocalHeroDialogues.HeroGateway().safe(p))&&(!partyMode||p.getParty()==boundParty&&boundParty.getMembers().contains(p))&&(!clanMode||LocalClanDialogues.maySend(p,boundClan));}
  public boolean human(Player p){return humans.contains(p);}public boolean audience(Player p){return heard;}public boolean quiet(Player p,String topic,long time){return true;}
  public long region(Player p){return clanMode?LocalClanDialogues.scope(p.getClan()):7;}
  public boolean near(Player a,Player b,int range){return clanMode?LocalClanDialogues.sameGroup(a.getClan(),b.getClan()):!partyMode||a.getParty()==b.getParty();}
  public List<Player> nearby(Player p){return actors;}public void say(Player p,String text,String topic){speakers.add(p);texts.add(text);}public void schedule(Runnable r,long delay){check(delay>=1000,"no synchronous reply loop");queue.add(r);delays.add(delay);}
  public boolean pauseAfterHumanMessage(){return partyMode||clanMode||heroMode;}public boolean rememberContext(){return false;}public String traceChannel(){return "";}
  void step(){now+=delays.remove();queue.remove().run();}
 }
 static LocalCityDialogues.Controller controller(G g,Path path,LocalCityDialogues.Scene scene){var bank=new LocalCityDialogues.Bank(path);bank.scenes=List.of(scene);bank.nextCheck=Long.MAX_VALUE;return new LocalCityDialogues.Controller(g,new LocalCityDialogues.Settings(true,path,30,30,60,120,3000,3000),bank,new Random(3));}
 static G group(Player a,Player b,Player h){G g=new G();g.actors.addAll(List.of(a,b));g.allowed.addAll(g.actors);g.humans.add(h);return g;}
 static Party party(Player...members)throws Exception{Party p=blank(Party.class);put(p,Party.class,"_members",new ArrayList<>(List.of(members)));for(Player m:members)states.get(m).party=p;return p;}
 static Clan clan(int id,int ally,Player...members)throws Exception{Clan c=blank(Clan.class);put(c,Clan.class,"_clanId",id);put(c,Clan.class,"_allyId",ally);Map<Integer,Object> map=new HashMap<>();for(Player p:members){states.get(p).clan=c;map.put(p.getObjectId(),null);}put(c,Clan.class,"_members",map);return c;}
 public static void main(String[]args)throws Exception{
  try{DriverManager.getConnection("jdbc:dialogue-runtime-must-block:");throw new AssertionError("NoWorld missing");}catch(AssertionError e){if(!e.getMessage().startsWith("Blocked world/JDBC"))throw e;checks++;}
  fixture();Path data=Path.of(args[0]);Player male=actor("male",true),female=actor("female",true),human=actor(null,false);
  check(LocalDialogueBindings.voice(male).equals(LocalDialogueBindings.voice(male.getObjectId(),"Renamed")),"speech identity persists across rename/relog and never reads avatar sex");
  Path city=data.resolve("LocalCityDialogues.xml");var scene=LocalCityDialogues.Bank.read(city).stream().filter(s->s.id().equals("global-work-026")).findFirst().orElseThrow();
  check(!LocalDialogueBindings.sceneAllowed(city,scene.id(),List.of("rr20261106-unknown-new-line")),"mismatched data/metadata cannot emit a new authored line");
  check(!LocalDialogueBindings.canBind(city,scene.id(),List.of("A","B","MissingRole"),List.of(male,female,human),male),"unknown role does not silently bypass gender constraints");
  G g=group(male,female,human);var c=controller(g,city,scene);check(c.tick(male,g.now),"real Controller starts compatible whole scene");check(g.speakers.getFirst()==female,"female A binds female voice even when male initiates");
  while(!g.queue.isEmpty())g.step();check(g.texts.equals(scene.lines().stream().map(LocalCityDialogues.Line::text).toList()),"all authored turns preserve exact order/text");check(c.active.isEmpty()&&c.busy.isEmpty(),"whole-scene reservations released");
  Player otherMale=actor("male",true);g=group(male,otherMale,human);c=controller(g,city,scene);check(!c.tick(male,g.now)&&g.texts.isEmpty(),"cannot start female role with all male voices");
  g=group(male,female,human);c=controller(g,city,scene);check(c.tick(male,g.now),"interruption scenario starts");c.humanSpoke(human);g.step();check(g.texts.size()==1&&c.active.isEmpty()&&c.busy.isEmpty(),"human interruption prevents all queued old replies");
  g=group(male,female,human);c=controller(g,city,scene);c.tick(male,g.now);g.allowed.remove(female);g.step();check(g.texts.size()==1&&c.busy.isEmpty(),"loss of eligibility/combat cancels remaining turns");
  g=group(male,female,human);g.heard=false;c=controller(g,city,scene);check(!c.tick(male,g.now),"no human audience means no background scene");
  Path longPath=data.resolve("LocalPartyLongDialogues.xml");var longScene=LocalCityDialogues.Bank.read(longPath).stream().filter(s->s.id().equals("global-work-026")).findFirst().orElseThrow();
  party(male,female,human);g=group(male,female,human);g.partyMode=true;g.boundParty=male.getParty();c=controller(g,longPath,longScene);check(c.tick(male,g.now),"long party scene starts only with real bound roster");states.get(female).party=null;g.step();check(g.texts.size()==1&&c.busy.isEmpty(),"leaving real party cancels queued dialogue");
  party(male,female,human);g=group(male,female,human);g.partyMode=true;g.boundParty=male.getParty();c=controller(g,longPath,longScene);c.tick(male,g.now);c.humanSpoke(human,"стоп");g.step();check(g.texts.size()==1,"party human speech interrupts long scene");
  check(!LocalPartyDialogues.ordinarySceneAllowed(true,false,false,false)&&!LocalPartyDialogues.ordinarySceneAllowed(false,true,false,false)&&!LocalPartyDialogues.ordinarySceneAllowed(false,false,true,false)&&!LocalPartyDialogues.ordinarySceneAllowed(false,false,false,true),"party event/registration/Olympiad/duel exclusions retained");
  var rules=LocalDialogueBindings.rules();int blocked=0;
  for(var entry:rules.scenes().entrySet())if(entry.getKey().startsWith("LocalParty")&&entry.getValue().scope().equals("nonparty")){String[] key=entry.getKey().split("#",2);check(!LocalDialogueBindings.sceneAllowed(data.resolve(key[0]),key[1],List.of("unmodified-old-id")),"nonparty version never leaks into old group copy");blocked++;}
  check(blocked>=14,"nonparty group remnants are suppressed");
  check(LocalDialogueBindings.sceneAllowed(longPath,"global-work-077",List.of("rr20261106-b001-s015-t01")),"separately authored party version of same scene ID remains enabled");
  var bank=LocalReplyBank.read(data.resolve("LocalReplyBank.xml"));var m=bank.voiced(male);var f=bank.voiced(female);
  check(m.entries.stream().noneMatch(e->e.id().equals("rr20261106-b019-e137"))&&f.entries.stream().anyMatch(e->e.id().equals("rr20261106-b019-e137")),"female standalone LFP never assigned to male voice");check(bank.entries.size()==17190&&bank.voiced(male)==m,"original data kept and voice cache reused");
  visible=new ArrayList<>(List.of(male,human));var gateway=new LocalHeroDialogues.HeroGateway();states.get(male).flags.remove("isHero");check(!gateway.safe(male)&&!LocalHeroDialogues.send(male,"test",gateway)&&states.get(human).packets.isEmpty(),"nonhero never emits hero channel");
  states.get(male).flags.add("isHero");check(gateway.safe(male)&&LocalHeroDialogues.send(male,"test",gateway)&&states.get(human).packets.size()==1,"actual hero gate and Native hero packet delivery");
  states.get(human).blockedIds.add(male.getObjectId());LocalHeroDialogues.send(male,"blocked",gateway);check(states.get(human).packets.size()==1,"real BlockList still prevents delivery");states.get(human).blockedIds.clear();
  for(var opener:rules.openers().entrySet()){check(LocalDialogueBindings.openerAllowed(male,opener.getKey())==!opener.getValue().equals("female"),"hero opener male persona");check(LocalDialogueBindings.openerAllowed(female,opener.getKey())==!opener.getValue().equals("male"),"hero opener female persona");}
  states.get(female).flags.add("isHero");Path heroPath=data.resolve("LocalHeroDialogues.xml");var heroScene=LocalCityDialogues.Bank.read(heroPath).stream().filter(s->s.id().equals("hero-social-004")).findFirst().orElseThrow();g=group(male,female,human);g.heroMode=true;c=controller(g,heroPath,heroScene);check(c.tick(male,g.now),"hero scene with current heroes starts");states.get(female).flags.remove("isHero");g.step();check(g.texts.size()==1&&c.busy.isEmpty(),"losing current hero status cancels queued hero scene");
  Clan clanA=clan(11,0,male,female,human),clanB=clan(12,42),clanC=clan(13,42);
  check(LocalClanDialogues.channel(clanA)==ChatType.CLAN&&LocalClanDialogues.channel(clanB)==ChatType.ALLIANCE,"actual clan vs alliance channel selection");check(LocalClanDialogues.sameGroup(clanB,clanC)&&!LocalClanDialogues.sameGroup(clanA,clanB),"actual alliance membership scope");check(LocalClanDialogues.maySend(male,clanA)&&!LocalClanDialogues.maySend(male,clanB),"actual clan membership and actor clan both required");
  Path clanPath=data.resolve("LocalClanDialogues.xml");var clanScene=LocalCityDialogues.Bank.read(clanPath).stream().filter(s->s.roles().size()==2&&LocalDialogueBindings.canBind(clanPath,s.id(),s.roles(),List.of(male,female),male)).findFirst().orElseThrow();g=group(male,female,human);g.clanMode=true;g.boundClan=clanA;c=controller(g,clanPath,clanScene);check(c.tick(male,g.now),"clan scene begins with eligible actual roster");put(clanA,Clan.class,"_allyId",42);g.step();check(g.texts.size()==1&&c.busy.isEmpty(),"change from clan to alliance scope cancels stale queue");
  System.out.println("PASS DialogueRuntimeCheck checks="+checks+"; actual Controller/voice/hero-send/BlockList/clan routing/party interruption; no world/SQL, packets recorded in memory");
 }
}
