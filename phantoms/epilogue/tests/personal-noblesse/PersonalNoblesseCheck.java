package org.l2jmobius.gameserver.managers.phantom;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.sql.DriverManager;
import java.util.*;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import static java.lang.constant.ConstantDescs.*;

/** Реальные policy/eligible/preset; создание singleton заменено, мир и JDBC заблокированы. */
public final class PersonalNoblesseCheck {
    static sun.misc.Unsafe u;static Class<?> actorClass;static int checks,next=970000;static boolean fixed;
    static final Map<String,Object> contexts=new HashMap<>();
    static final Map<Player,Boolean> nobles=new IdentityHashMap<>();
    static final Map<Player,Integer> ids=new IdentityHashMap<>();
    static final Map<Player,String> names=new IdentityHashMap<>();
    static final Map<Player,Party> parties=new IdentityHashMap<>();
    static final Map<Player,PlayerClass> jobs=new IdentityHashMap<>();
    public static Object context(String name){Object o=contexts.get(name);if(o==null)throw new AssertionError("Unseeded singleton "+name);return o;}
    public static boolean noble(Player p){return nobles.getOrDefault(p,false);}
    public static int id(Player p){return ids.get(p);}
    public static String name(Player p){return names.get(p);}
    public static Party party(Player p){return parties.get(p);}
    public static boolean inParty(Player p){return parties.containsKey(p);}
    public static PlayerClass job(Player p){return jobs.get(p);}
    public static boolean mage(Player p){return jobs.get(p)==PlayerClass.STORM_SCREAMER;}
    static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
    static <T>T blank(Class<T> c)throws Exception{return c.cast(u.allocateInstance(c));}
    static void put(Object o,Class<?> c,String field,Object value)throws Exception{Field f=c.getDeclaredField(field);f.setAccessible(true);f.set(o,value);}
    static Player actor(boolean noble,PlayerClass job)throws Exception{Player p=(Player)u.allocateInstance(actorClass);ids.put(p,++next);names.put(p,"Fixture"+next);nobles.put(p,noble);jobs.put(p,job);return p;}
    static Party party(Player...members)throws Exception{Party p=blank(Party.class);put(p,Party.class,"_members",new ArrayList<>(List.of(members)));for(Player member:members)parties.put(member,p);return p;}
    static PhantomTemplate template(String name,boolean hunter,boolean reaper)throws Exception{PhantomTemplate t=blank(PhantomTemplate.class);put(t,PhantomTemplate.class,"_name",name);put(t,PhantomTemplate.class,"_hunter",hunter);put(t,PhantomTemplate.class,"_reaper",reaper);return t;}
    static void fixture()throws Exception{
        Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);u=(sun.misc.Unsafe)f.get(null);
        ClassDesc h=PersonalNoblesseCheck.class.describeConstable().orElseThrow(),p=Player.class.describeConstable().orElseThrow();
        actorClass=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.PersonalNobleFixturePlayer"),b->{
            b.withFlags(0x31);b.withSuperclass(p);
            for(var m:Map.of("isNoble","noble","getObjectId","id","getName","name","getParty","party","isInParty","inParty","getPlayerClass","job","isMageClass","mage").entrySet()){
                try{Class<?> ret=Player.class.getMethod(m.getKey()).getReturnType();ClassDesc type=ret.describeConstable().orElseThrow();
                    b.withMethodBody(m.getKey(),MethodTypeDesc.of(type),1,c->{c.aload(0).invokestatic(h,m.getValue(),MethodTypeDesc.of(type,p));if(ret==boolean.class||ret==int.class)c.ireturn();else c.areturn();});
                }catch(Exception e){throw new RuntimeException(e);}
            }
            for(String m:List.of("isDead","isTeleporting","isInOlympiadMode","isOnEvent","isInDuel"))b.withMethodBody(m,MethodTypeDesc.of(CD_boolean),1,c->c.iconst_0().ireturn());
        }));
    }
    static Skill skill(int id)throws Exception{Skill s=blank(Skill.class);put(s,Skill.class,"_id",id);put(s,Skill.class,"_level",1);Field f=Skill.class.getDeclaredField("_operateType");f.setAccessible(true);f.set(s,Enum.valueOf((Class)f.getType(),"A2"));return s;}
    static boolean contains(List<Skill> set,int id){return set.stream().anyMatch(s->s.getId()==id);}
    public static void main(String[]args)throws Exception{
        fixed=args[0].equals("fixed");
        try{DriverManager.getConnection("jdbc:noble-owner-must-block:");throw new AssertionError("NoWorld missing");}
        catch(AssertionError e){if(!e.getMessage().startsWith("Blocked world/JDBC"))throw e;checks++;}
        fixture();
        PhantomData data=blank(PhantomData.class);Map<String,PhantomTemplate> templates=new HashMap<>();put(data,PhantomData.class,"_templates",templates);contexts.put(PhantomData.class.getName(),data);
        PhantomManager registry=blank(PhantomManager.class);Map<Integer,PhantomTemplate> phantoms=new HashMap<>();put(registry,PhantomManager.class,"_phantoms",phantoms);contexts.put(PhantomManager.class.getName(),registry);
        PhantomCompanionManager manager=blank(PhantomCompanionManager.class);Map<String,Object> companions=new HashMap<>();put(manager,PhantomCompanionManager.class,"_companions",companions);contexts.put(PhantomCompanionManager.class.getName(),manager);
        Class<?> binding=Class.forName(PhantomCompanionManager.class.getName()+"$Companion");
        SchemeBufferTable table=blank(SchemeBufferTable.class);Map<Integer,BuffSkillHolder> available=new HashMap<>();Map<String,List<Integer>> groups=new HashMap<>();Map<String,Map<Integer,BuffSkillHolder>> byGroup=new HashMap<>();
        for(String group:List.of("ROLE_FIGHTER_GROUP","ROLE_DAGGER_GROUP","ROLE_ARCHER_GROUP","ROLE_TANK_GROUP","ROLE_MAGE_GROUP","ROLE_HEALER_GROUP","FIGHTER_GROUP","MAGE_GROUP")){
            Map<Integer,BuffSkillHolder> buffs=new HashMap<>();for(int id:new int[]{1040,1323}){BuffSkillHolder holder=new BuffSkillHolder(id,1,0,group,"Fixture");buffs.put(id,holder);available.put(id,holder);}groups.put(group,List.of(1040,1323));byGroup.put(group,buffs);
        }
        put(table,SchemeBufferTable.class,"_availableBuffs",available);put(table,SchemeBufferTable.class,"_skillIdsByType",groups);put(table,SchemeBufferTable.class,"_availableBuffsByType",byGroup);contexts.put(SchemeBufferTable.class.getName(),table);
        SkillData skills=blank(SkillData.class);Map<Integer,Skill> skillMap=new HashMap<>();for(int id:new int[]{1040,1323})skillMap.put(SkillData.getSkillHashCode(id,1),skill(id));put(skills,SkillData.class,"_skillsByHash",skillMap);put(skills,SkillData.class,"_maxSkillLevels",Map.of());contexts.put(SkillData.class.getName(),skills);
        Player owner=actor(false,PlayerClass.DREADNOUGHT),bot=actor(true,PlayerClass.STORM_SCREAMER),other=actor(true,PlayerClass.DREADNOUGHT);party(owner,bot,other);
        PhantomTemplate personal=template(name(bot),false,false);templates.put(name(bot).toLowerCase(),personal);phantoms.put(id(bot),personal);Object bound=blank(binding);put(bound,binding,"ownerId",id(owner));companions.put(name(bot).toLowerCase(),bound);
        for(PlayerClass job:new PlayerClass[]{PlayerClass.DREADNOUGHT,PlayerClass.WIND_RIDER,PlayerClass.SAGITTARIUS,PlayerClass.PHOENIX_KNIGHT,PlayerClass.STORM_SCREAMER,PlayerClass.CARDINAL}){
            jobs.put(bot,job);List<Skill> actual=PhantomSupport.selfBuffSet(bot);check(contains(actual,1323)==!fixed,"personal preset ownerfalse "+job);check(contains(actual,1040),"unrelated role buff retained");
        }
        check(contains(PartyBuffs.preset(bot),1323)==!fixed,"legacy personal preset filter");
        if(fixed){check(!PhantomPersonalNoblesse.allows(bot,1323),"bot's saved noble cannot replace current owner's right");check(PhantomPersonalNoblesse.allows(bot,1040),"only1323 gated");}
        for(String name:List.of(PartyNoblesse.class.getName(),PhantomPartyNoblesse.class.getName())){
            Class<?> c=Class.forName(name);Field f=c.getDeclaredField("LINKS");f.setAccessible(true);Map<Integer,Object> links=(Map<Integer,Object>)f.get(null);links.clear();
            Constructor<?> ctor=Class.forName(name+"$Link").getDeclaredConstructor(int.class,boolean.class,long.class);ctor.setAccessible(true);links.put(id(bot),ctor.newInstance(id(owner),true,1000000L));
            Method eligible=c.getDeclaredMethod("eligible",Player.class);eligible.setAccessible(true);check((boolean)eligible.invoke(null,bot)==!fixed,"actual eligible stale owner "+name);
            nobles.put(owner,true);check((boolean)eligible.invoke(null,bot),"actual eligible noble owner "+name);
            // Передача в той же пати: прежний дворянин остаётся, но actual current owner уже другой.
            nobles.put(other,false);put(bound,binding,"ownerId",id(other));check((boolean)eligible.invoke(null,bot)==!fixed,"actual current owner wins over old noble party member "+name);
            if(fixed){Method sync=c.getDeclaredMethod("synchronize",Player.class,Player.class,PhantomTemplate.class,long.class);sync.setAccessible(true);sync.invoke(null,owner,bot,personal,1000001L);check(!links.containsKey(id(bot)),"stale noble owner argument cannot grant/restore after transfer "+name);links.put(id(bot),ctor.newInstance(id(owner),true,1000001L));}
            put(bound,binding,"ownerId",id(owner));nobles.put(owner,false);
            if(fixed){
                Method cast=c.getDeclaredMethod("cast",Player.class,Player.class,long.class);cast.setAccessible(true);check(!(boolean)cast.invoke(null,bot,null,1000001L),"denied before target/effect/inventory work "+name);
                Method sync=c.getDeclaredMethod("synchronize",Player.class,Player.class,PhantomTemplate.class,long.class);sync.setAccessible(true);sync.invoke(null,owner,bot,personal,1000002L);check(!links.containsKey(id(bot)),"invalid personal sync removes stale link "+name);check(noble(bot),"historical noble not destructively stripped");
            }
            links.clear();
        }
        if(fixed){
            nobles.put(owner,true);check(contains(PhantomSupport.selfBuffSet(bot),1323),"owner obtains noble: preset restored without restart");
            nobles.put(owner,false);nobles.put(bot,false);check(!contains(PhantomSupport.selfBuffSet(bot),1323),"no noble on either actor: preset blocked");
            parties.remove(bot);check(!PhantomPersonalNoblesse.allows(bot,1323),"dismissal stops personal automatic noble");party(owner,bot,other);
            PhantomTemplate hunter=template(name(bot),true,false);templates.put(name(bot).toLowerCase(),hunter);check(PhantomPersonalNoblesse.allows(bot,1323)&&contains(PhantomSupport.selfBuffSet(bot),1323),"world hunter preset unchanged for nonnoble human party");
            templates.put(name(bot).toLowerCase(),template(name(bot),false,true));check(PhantomPersonalNoblesse.allows(bot,1323),"reaper outside personal scope");
            templates.remove(name(bot).toLowerCase());check(!PhantomPersonalNoblesse.allows(bot,1323),"unknown phantom fail closed");check(PhantomPersonalNoblesse.allows(owner,1323),"ordinary human unaffected");
        }
        System.out.println("PASS PersonalNoblesseCheck "+args[0]+" checks="+checks+" NativeRoleGroups=6; actual owner lookup/eligible/preset; world=false sql=false; effects/payment not executed");
    }
}
