package org.l2jmobius.gameserver.managers.phantom;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.sql.DriverManager;
import java.util.*;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import static java.lang.constant.ConstantDescs.*;

/** Final-JAR test: реальные owner/policy/link/sync/cast/refresh; актёры в памяти, без таблиц-замен. */
public final class FinalNobleOwnerCheck {
 static sun.misc.Unsafe u;static Class<?> actorClass;static int next=950000,checks,setNobleCalls;
 static final Map<String,Object> contexts=new HashMap<>();
 static final Map<Player,State> states=new IdentityHashMap<>();
 static final class State{int id;String name;boolean noble,gm;Party party;Set<String> flags=new HashSet<>();}
 public static Object context(String key){Object v=contexts.get(key);if(v==null)throw new AssertionError("Unseeded actor registry "+key);return v;}
 public static int id(Player p){return states.get(p).id;}
 public static String name(Player p){return states.get(p).name;}
 public static Party party(Player p){return states.get(p).party;}
 public static boolean inParty(Player p){return states.get(p).party!=null;}
 public static boolean noble(Player p){return states.get(p).noble;}
 public static boolean gm(Player p){return states.get(p).gm;}
 public static boolean flag(Player p,String key){return states.get(p).flags.contains(key);}
 public static void setNoble(Player p,boolean value){setNobleCalls++;states.get(p).noble=value;}
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static <T>T blank(Class<T> c)throws Exception{return c.cast(u.allocateInstance(c));}
 static void put(Object o,Class<?> c,String field,Object value)throws Exception{Field f=c.getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
 static Player actor(boolean noble)throws Exception{Player p=blank(actorClass.asSubclass(Player.class));State s=new State();s.id=++next;s.name="FinalFixture"+s.id;s.noble=noble;states.put(p,s);return p;}
 static Party party(Player...members)throws Exception{Party p=blank(Party.class);put(p,Party.class,"_members",new ArrayList<>(List.of(members)));for(Player member:members)states.get(member).party=p;return p;}
 static PhantomTemplate template(Player p,boolean hunter,boolean reaper)throws Exception{PhantomTemplate t=blank(PhantomTemplate.class);put(t,PhantomTemplate.class,"_name",name(p));put(t,PhantomTemplate.class,"_hunter",hunter);put(t,PhantomTemplate.class,"_reaper",reaper);return t;}
 public static void main(String[]args)throws Exception{
  try{DriverManager.getConnection("jdbc:final-noble-must-block:");throw new AssertionError("NoWorld missing");}catch(AssertionError e){if(!e.getMessage().startsWith("Blocked world/JDBC"))throw e;checks++;}
  Field uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);u=(sun.misc.Unsafe)uf.get(null);
  ClassDesc h=FinalNobleOwnerCheck.class.describeConstable().orElseThrow(),pd=Player.class.describeConstable().orElseThrow();
  actorClass=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.FinalNobleFixturePlayer"),b->{
   b.withFlags(0x31);b.withSuperclass(pd);
   for(var entry:Map.of("isNoble","noble","isGM","gm","getObjectId","id","getName","name","getParty","party","isInParty","inParty").entrySet()){
    try{Class<?> ret=Player.class.getMethod(entry.getKey()).getReturnType();ClassDesc type=ret.describeConstable().orElseThrow();b.withMethodBody(entry.getKey(),MethodTypeDesc.of(type),1,c->{c.aload(0).invokestatic(h,entry.getValue(),MethodTypeDesc.of(type,pd));if(ret==int.class||ret==boolean.class)c.ireturn();else c.areturn();});}catch(Exception e){throw new RuntimeException(e);}
   }
   for(String m:List.of("isDead","isTeleporting","isInOlympiadMode","isOnEvent","isInDuel"))b.withMethodBody(m,MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).ldc(m).invokestatic(h,"flag",MethodTypeDesc.of(CD_boolean,pd,CD_String)).ireturn());
   b.withMethodBody("setNoble",MethodTypeDesc.of(CD_void,CD_boolean),1,c->c.aload(0).iload(1).invokestatic(h,"setNoble",MethodTypeDesc.of(CD_void,pd,CD_boolean)).return_());
  }));
  PhantomData data=blank(PhantomData.class);Map<String,PhantomTemplate> templates=new HashMap<>();put(data,PhantomData.class,"_templates",templates);contexts.put(PhantomData.class.getName(),data);
  PhantomManager registry=blank(PhantomManager.class);Map<Integer,PhantomTemplate> phantoms=new HashMap<>();put(registry,PhantomManager.class,"_phantoms",phantoms);contexts.put(PhantomManager.class.getName(),registry);
  PhantomCompanionManager manager=blank(PhantomCompanionManager.class);Map<String,Object> companions=new HashMap<>();put(manager,PhantomCompanionManager.class,"_companions",companions);contexts.put(PhantomCompanionManager.class.getName(),manager);
  Class<?> binding=Class.forName(PhantomCompanionManager.class.getName()+"$Companion");
  Player owner=actor(false),bot=actor(true),other=actor(false);party(owner,bot,other);PhantomTemplate personal=template(bot,false,false);templates.put(name(bot).toLowerCase(),personal);phantoms.put(id(bot),personal);
  Object bound=blank(binding);put(bound,binding,"ownerId",id(owner));companions.put(name(bot).toLowerCase(),bound);
  check(!PhantomPersonalNoblesse.allows(bot,1323),"saved bot noble cannot replace owner noble");check(PhantomPersonalNoblesse.allows(bot,1040),"ordinary buffs remain allowed");
  states.get(owner).gm=true;check(!PhantomPersonalNoblesse.allows(bot,1323),"GM alone does not grant personal noblesse");states.get(owner).gm=false;
  Skill noble=blank(Skill.class);put(noble,Skill.class,"_id",1323);
  Method refresh=PartyBuffs.class.getDeclaredMethod("refresh",Player.class,List.class,int.class);refresh.setAccessible(true);
  check(((Integer)refresh.invoke(null,bot,List.of(noble),0))==0,"actual direct refresh rejects1323 before effects/target state");
  for(String name:List.of(PartyNoblesse.class.getName(),PhantomPartyNoblesse.class.getName())){
   Class<?> service=Class.forName(name);Field f=service.getDeclaredField("LINKS");f.setAccessible(true);Map<Integer,Object> links=(Map<Integer,Object>)f.get(null);links.clear();
   Constructor<?> ctor=Class.forName(name+"$Link").getDeclaredConstructor(int.class,boolean.class,long.class);ctor.setAccessible(true);links.put(id(bot),ctor.newInstance(id(owner),true,1000000L));
   Method eligible=service.getDeclaredMethod("eligible",Player.class);eligible.setAccessible(true);check(!(boolean)eligible.invoke(null,bot),"actual eligible rejects nonnoble current owner "+name);
   states.get(owner).noble=true;check((boolean)eligible.invoke(null,bot),"noble current owner authorizes link "+name);
   states.get(bot).noble=false;check(!(boolean)eligible.invoke(null,bot),"caster itself still needs noble "+name);states.get(bot).noble=true;
   for(String flag:List.of("isDead","isTeleporting","isInOlympiadMode","isOnEvent","isInDuel")){states.get(bot).flags.add(flag);check(!(boolean)eligible.invoke(null,bot),"native caster restriction "+flag);states.get(bot).flags.remove(flag);}
   put(bound,binding,"ownerId",id(other));check(!(boolean)eligible.invoke(null,bot),"actual current owner wins over old noble party member "+name);
   Method sync=service.getDeclaredMethod("synchronize",Player.class,Player.class,PhantomTemplate.class,long.class);sync.setAccessible(true);sync.invoke(null,owner,bot,personal,1000001L);check(!links.containsKey(id(bot))&&setNobleCalls==0,"stale noble argument cannot grant or restore "+name);
   put(bound,binding,"ownerId",id(owner));states.get(owner).noble=false;links.put(id(bot),ctor.newInstance(id(owner),true,1000001L));
   Method cast=service.getDeclaredMethod("cast",Player.class,Player.class,long.class);cast.setAccessible(true);check(!(boolean)cast.invoke(null,bot,null,1000002L),"denied before target/effect access "+name);
   sync.invoke(null,owner,bot,personal,1000003L);check(!links.containsKey(id(bot))&&states.get(bot).noble&&setNobleCalls==0,"invalid sync unlinks without stripping saved noble "+name);
   links.clear();
  }
  states.get(owner).noble=true;check(PhantomPersonalNoblesse.allows(bot,1323),"owner gains noble without restart");
  states.get(bot).party=null;check(!PhantomPersonalNoblesse.allows(bot,1323),"dismissed personal actor not authorized");party(owner,bot,other);
  templates.put(name(bot).toLowerCase(),template(bot,true,false));states.get(owner).noble=false;check(PhantomPersonalNoblesse.allows(bot,1323),"world hunter unchanged");
  templates.put(name(bot).toLowerCase(),template(bot,false,true));check(PhantomPersonalNoblesse.allows(bot,1323),"reaper unchanged");
  templates.remove(name(bot).toLowerCase());check(!PhantomPersonalNoblesse.allows(bot,1323)&&PhantomPersonalNoblesse.allows(owner,1323),"unknown phantom closed and ordinary human unchanged");
  System.out.println("PASS FinalNobleOwnerCheck checks="+checks+"; actual final owner/policy/link/sync/cast/direct-refresh; no table/skill singleton replacement, no world/SQL");
 }
}
