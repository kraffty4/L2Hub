package org.l2jmobius.gameserver.mechanics.olympiad;

import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import sun.misc.Unsafe;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.*;
import static java.lang.constant.ConstantDescs.*;

/** Нативная отмена и подбор пары; управляемые межпоточные переходы, без SQL и игрового мира. */
public class OlympiadCancelCheck {
 static Unsafe u;static Class<?> playerType;static int checks,nextId=800000;
 static Olympiad service;static OlympiadManager manager;
 static final Map<Player,State> states=new ConcurrentHashMap<>();
 static class State {boolean noble=true;List<Integer> ids=new CopyOnWriteArrayList<>();List<String> messages=new CopyOnWriteArrayList<>();}
 static void ok(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void packet(Player p,ServerPacket packet){
  if(packet instanceof SystemMessage sm){State s=states.get(p);s.ids.add(sm.getId());try{Field f=SystemMessage.class.getDeclaredField("_params");f.setAccessible(true);for(var v:(SystemMessage.SMParam[])f.get(sm))if(v!=null&&v.getValue() instanceof String value)s.messages.add(value);}catch(Exception e){throw new RuntimeException(e);}}
 }
 public static boolean noble(Player p){return states.get(p).noble;}
 public static PlayerClass playerClass(Player p){return PlayerClass.STORM_SCREAMER;}
 static void field(Class<?> c,Object target,String name,Object value)throws Exception{Field f=c.getDeclaredField(name);f.setAccessible(true);if(Modifier.isFinal(f.getModifiers()))u.putObject(target,u.objectFieldOffset(f),value);else f.set(target,value);}
 static void shared(String name,Object value)throws Exception{Field f=Olympiad.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);}
 static Player player()throws Exception{
  Player p=(Player)u.allocateInstance(playerType);states.put(p,new State());Field id=WorldObject.class.getDeclaredField("_objectId");u.putInt(p,u.objectFieldOffset(id),nextId++);
  Field name=WorldObject.class.getDeclaredField("_name");u.putObject(p,u.objectFieldOffset(name),"Fixture");return p;
 }
 static void reset(Map<Integer,OlympiadGame> games)throws Exception{states.clear();shared("_inCompPeriod",true);shared("_nonClassBasedRegisters",new ArrayList<Player>());shared("_classBasedRegisters",new HashMap<Integer,List<Player>>());field(OlympiadManager.class,manager,"_olympiadInstances",games);}
 static List<Player> queue(boolean classed,Player...players)throws Exception{
  List<Player> list=new ArrayList<>(Arrays.asList(players));
  if(classed)Olympiad.getRegisteredClassBased().put(110,list);else shared("_nonClassBasedRegisters",list);return list;
 }
 static OlympiadGame game(Player a,Player b)throws Exception{OlympiadGame g=(OlympiadGame)u.allocateInstance(OlympiadGame.class);field(OlympiadGame.class,g,"_playerOneID",a.getObjectId());field(OlympiadGame.class,g,"_playerTwoID",b.getObjectId());return g;}
 static boolean id(Player p,SystemMessageId id){return states.get(p).ids.contains(id.getId());}
 static boolean assignedMessage(Player p){return states.get(p).messages.stream().anyMatch(s->s.contains("already selected for a game"));}
 static <T> FutureTask<T> start(String name,Callable<T> work){FutureTask<T> f=new FutureTask<>(work);Thread t=new Thread(f,name);t.setDaemon(true);t.start();return f;}
 static void await(CountDownLatch l)throws Exception{if(!l.await(2,TimeUnit.SECONDS))throw new AssertionError("Fixture boundary not reached");}
 static void blocked(FutureTask<?> f,CountDownLatch entered)throws Exception{await(entered);Thread.sleep(40);ok(!f.isDone(),"operation waits for native manager monitor");}
 static class PauseGames extends HashMap<Integer,OlympiadGame>{
  final CountDownLatch checked=new CountDownLatch(1),resume=new CountDownLatch(1);
  @Override public Collection<OlympiadGame> values(){
   if(!Thread.currentThread().getName().equals("cancel"))return super.values();
   Collection<OlympiadGame> seen=new ArrayList<>(super.values());checked.countDown();try{await(resume);}catch(Exception e){throw new RuntimeException(e);}return seen;
  }
 }
 static void pairSelectedFirst(boolean before)throws Exception{
  Map<Integer,OlympiadGame> games=new HashMap<>();reset(games);Player p=player(),other=player();List<Player> list=queue(false,p,other);
  FutureTask<Boolean> cancel;CountDownLatch entered=new CountDownLatch(1);
  synchronized(manager){
   List<Player> chosen=manager.nextOpponents(list);ok(chosen.size()==2&&list.isEmpty(),"native matcher removed both registrations");
   cancel=start("cancel",()->{entered.countDown();return service.unRegisterNoble(p);});await(entered);
   if(before){ok(!cancel.get(1,TimeUnit.SECONDS),"old cancellation rejected");ok(id(p,SystemMessageId.YOU_ARE_NOT_CURRENTLY_REGISTERED_ON_ANY_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"old bug: wrong not-registered during assignment");}
   else blocked(cancel,entered);
   games.put(0,game(chosen.get(0),chosen.get(1)));
  }
  ok(!cancel.get(1,TimeUnit.SECONDS),"assigned match cannot be cancelled through queue");
  if(!before){ok(assignedMessage(p),"selected opponent gets explicit native rejection");ok(!id(p,SystemMessageId.YOU_ARE_NOT_CURRENTLY_REGISTERED_ON_ANY_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"no misleading not-registered response");}
 }
 static void cancellationSelectedFirst(boolean before)throws Exception{
  PauseGames games=new PauseGames();reset(games);Player p=player(),other=player();List<Player> list=queue(true,p,other);
  FutureTask<Boolean> cancel=start("cancel",()->service.unRegisterNoble(p));await(games.checked);
  CountDownLatch entered=new CountDownLatch(1);
  FutureTask<Boolean> pairing=start("pairing",()->{entered.countDown();synchronized(manager){List<Player> chosen=manager.nextOpponents(list);if(chosen.size()!=2)return false;games.put(0,game(chosen.get(0),chosen.get(1)));return true;}});
  boolean matched=false;
  try{if(before){matched=pairing.get(1,TimeUnit.SECONDS);ok(matched,"old matcher selected pair during cancellation");}else blocked(pairing,entered);}
  finally{games.resume.countDown();}
  boolean removed=cancel.get(1,TimeUnit.SECONDS);matched=pairing.get(1,TimeUnit.SECONDS);
  if(before){ok(removed&&matched,"old bug: reports removed even though match assigned");ok(id(p,SystemMessageId.YOU_HAVE_BEEN_REMOVED_FROM_THE_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"old false success delivered");}
  else{ok(removed&&!matched,"successful cancellation prevents later native pairing");ok(!list.contains(p)&&list.contains(other),"only cancelled player removed");ok(id(p,SystemMessageId.YOU_HAVE_BEEN_REMOVED_FROM_THE_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"truthful success response");}
 }
 static void ordinaryCases()throws Exception{
  for(boolean classed:new boolean[]{false,true}){
   reset(new HashMap<>());Player p=player(),other=player();List<Player> list=queue(classed,p,other);
   ok(service.unRegisterNoble(p),"queue cancellation succeeds "+classed);ok(list.equals(List.of(other)),"other registration retained");ok(!service.unRegisterNoble(p),"repeat cancellation not successful");
   ok(id(p,SystemMessageId.YOU_HAVE_BEEN_REMOVED_FROM_THE_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"removed packet present");
   ok(id(p,SystemMessageId.YOU_ARE_NOT_CURRENTLY_REGISTERED_ON_ANY_GRAND_OLYMPIAD_GAMES_WAITING_LIST),"duplicate explained");
   reset(new HashMap<>());p=player();states.get(p).noble=false;queue(classed,p);ok(!service.unRegisterNoble(p),"non-noble rejected");ok(!states.get(p).ids.isEmpty(),"non-noble response recorded");
   reset(new HashMap<>());p=player();queue(classed,p);shared("_inCompPeriod",false);ok(!service.unRegisterNoble(p),"closed competition rejected");ok(id(p,SystemMessageId.THE_GRAND_OLYMPIAD_GAMES_ARE_NOT_CURRENTLY_IN_PROGRESS),"closed competition response");
  }
  reset(new HashMap<>());Player p=player();ok(!service.unRegisterNoble(p),"absent registration handled");
  reset(new HashMap<>());p=player();Player a=player(),b=player();manager.getOlympiadGames().put(0,game(a,b));queue(false,p);ok(service.unRegisterNoble(p),"unrelated match does not block queue cancellation");
  for(boolean one:new boolean[]{false,true}){reset(new HashMap<>());p=player();a=player();manager.getOlympiadGames().put(0,one?game(p,a):game(a,p));ok(!service.unRegisterNoble(p)&&assignedMessage(p),"both assigned sides rejected clearly");}
  reset(new HashMap<>());p=player();manager.getOlympiadGames().put(0,null);queue(false,p);ok(service.unRegisterNoble(p),"null map value ignored");
  reset(null);p=player();queue(false,p);ok(service.unRegisterNoble(p),"missing game map does not throw");
  reset(new HashMap<>());p=player();shared("_classBasedRegisters",null);shared("_nonClassBasedRegisters",null);ok(!service.unRegisterNoble(p),"missing queues do not throw");
 }
 public static void main(String[]args)throws Exception{
  Field f=Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);u=(Unsafe)f.get(null);ClassDesc pd=ClassDesc.of(Player.class.getName()),host=ClassDesc.of(OlympiadCancelCheck.class.getName());
  playerType=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.mechanics.olympiad.CancelFixturePlayer"),b->{b.withFlags(0x31);b.withSuperclass(pd);
   b.withMethodBody("isNoble",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"noble",MethodTypeDesc.of(CD_boolean,pd)).ireturn());
   b.withMethodBody("getPlayerClass",MethodTypeDesc.of(ClassDesc.of(PlayerClass.class.getName())),1,c->c.aload(0).invokestatic(host,"playerClass",MethodTypeDesc.of(ClassDesc.of(PlayerClass.class.getName()),pd)).areturn());
   b.withMethodBody("sendPacket",MethodTypeDesc.of(CD_void,ClassDesc.of(ServerPacket.class.getName())),1,c->c.aload(0).aload(1).invokestatic(host,"packet",MethodTypeDesc.of(CD_void,pd,ClassDesc.of(ServerPacket.class.getName()))).return_());
  }));
  service=(Olympiad)u.allocateInstance(Olympiad.class);manager=OlympiadManager.getInstance();
  ok(Modifier.isSynchronized(OlympiadManager.class.getDeclaredMethod("run").getModifiers()),"actual native matching loop holds manager monitor");
  boolean before=Arrays.asList(args).contains("before");pairSelectedFirst(before);cancellationSelectedFirst(before);
  if(!before){ordinaryCases();for(int i=0;i<5;i++){pairSelectedFirst(false);cancellationSelectedFirst(false);}}
  System.out.println((before?"CANCEL_BEFORE_BUGS_REPRODUCED":"OLYMPIAD_CANCEL_CHECK_OK")+" checks="+checks+"; real native cancellation and opponent selection, both deterministic interleavings; no world/SQL/live player actions");
 }
}
