package org.l2jmobius.gameserver.managers;

import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.sql.DriverManager;
import java.util.*;
import org.l2jmobius.commons.network.ConnectionConfig;
import org.l2jmobius.commons.network.buffer.WriteBuffer;
import org.l2jmobius.commons.network.packet.WritablePacket;
import org.l2jmobius.commons.network.pool.ResourcePool;
import org.l2jmobius.gameserver.ai.*;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.*;
import org.l2jmobius.gameserver.entity.actor.*;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.serverpackets.*;
import static java.lang.constant.ConstantDescs.*;

/** Настоящие policy/visibility/packet-cache; сеть, персонажи и получение списка мира — изолированные терминалы. */
public final class PlayingGmVisionCheck {
    static sun.misc.Unsafe unsafe;
    static int checks, sequence=700000;
    static boolean fixed, enabled, defaultMode;
    static Class<?> playerClass, npcClass, summonClass, revealedClass;
    static final ResourcePool pool = new ResourcePool();
    static final Map<Player,Boolean> gm = new IdentityHashMap<>();
    static final Map<Player,Boolean> heroes = new IdentityHashMap<>();
    static final Map<Player,Vars> vars = new IdentityHashMap<>();
    static final Map<Player,WorldObject> targets = new IdentityHashMap<>();
    static final Map<Player,List<ServerPacket>> sent = new IdentityHashMap<>();
    static final Map<WorldObject,Integer> infos = new IdentityHashMap<>();
    static final Map<Player,Integer> aborts = new IdentityHashMap<>();

    public static final class Vars extends PlayerVariables {
        Boolean mode;
        private Vars(){super(0);}
        @Override public boolean getBoolean(String key, boolean fallback){
            if (!key.equals(PlayingGmPolicy.MODE_VARIABLE)) throw new AssertionError("Unexpected variable");
            return mode==null?fallback:mode;
        }
    }
    public static final class ClientModel extends GameClient {
        private ClientModel(){super(null);}
        @Override public ResourcePool getResourcePool(){return pool;}
    }
    public static final class ProbePacket extends ServerPacket {
        final WorldObject subject;
        int writes;
        ProbePacket(WorldObject subject){this.subject=subject;}
        // На baseline это отдельный метод, на fixed — настоящий virtual subject hook.
        protected WorldObject getVisibilityObject(){return subject;}
        @Override protected void writeImpl(GameClient client,WriteBuffer buffer){writes++;buffer.writeByte(0x03);buffer.writeInt(12345);}
    }
    public static final class Ai extends CreatureAI {
        int idle;
        Creature follow;
        Ai(Creature actor){super(actor);}
        void attack(Creature target){setAttackTarget(target);}
        void cast(Creature target){setCastTarget(target);}
        @Override public Creature getFollowTarget(){return follow;}
        @Override public void setIntentionIdle(){idle++;setAttackTarget(null);setCastTarget(null);follow=null;}
    }
    public static boolean gm(Player p){return gm.getOrDefault(p,false);}
    public static boolean hero(Player p){return heroes.getOrDefault(p,false);}
    public static PlayerVariables variables(Player p){return vars.get(p);}
    public static WorldObject target(Player p){return targets.get(p);}
    public static void target(Player p,WorldObject object){targets.put(p,object);}
    public static void packet(Player p,ServerPacket packet){sent.get(p).add(packet);}
    public static void abort(Player p){aborts.merge(p,1,Integer::sum);}
    public static void info(WorldObject object,Player viewer){infos.merge(object,1,Integer::sum);}
    static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    static void put(Object object,Class<?> owner,String name,Object value)throws Exception {
        Field field=owner.getDeclaredField(name);long at=unsafe.objectFieldOffset(field);
        if(field.getType()==boolean.class)unsafe.putBoolean(object,at,(Boolean)value);
        else if(field.getType()==int.class)unsafe.putInt(object,at,(Integer)value);
        else unsafe.putObject(object,at,value);
    }
    static ClassDesc desc(Class<?> type){return type.describeConstable().orElseThrow();}
    static void types()throws Exception {
        if(playerClass!=null)return;
        ClassDesc host=desc(PlayingGmVisionCheck.class),pd=desc(Player.class),wo=desc(WorldObject.class);
        var cf=ClassFile.of();
        playerClass=MethodHandles.lookup().defineClass(cf.build(ClassDesc.of("org.l2jmobius.gameserver.managers.VisionFixturePlayer"),b->{
            b.withFlags(0x31);b.withSuperclass(pd);
            b.withMethodBody("isGM",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"gm",MethodTypeDesc.of(CD_boolean,pd)).ireturn());
            b.withMethodBody("isHero",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"hero",MethodTypeDesc.of(CD_boolean,pd)).ireturn());
            b.withMethodBody("getVariables",MethodTypeDesc.of(desc(PlayerVariables.class)),1,c->c.aload(0).invokestatic(host,"variables",MethodTypeDesc.of(desc(PlayerVariables.class),pd)).areturn());
            b.withMethodBody("getTarget",MethodTypeDesc.of(wo),1,c->c.aload(0).invokestatic(host,"target",MethodTypeDesc.of(wo,pd)).areturn());
            b.withMethodBody("setTarget",MethodTypeDesc.of(CD_void,wo),1,c->c.aload(0).aload(1).invokestatic(host,"target",MethodTypeDesc.of(CD_void,pd,wo)).return_());
            for(String name:List.of("abortAttack","abortCast"))b.withMethodBody(name,MethodTypeDesc.of(CD_void),1,c->c.aload(0).invokestatic(host,"abort",MethodTypeDesc.of(CD_void,pd)).return_());
            b.withMethodBody("sendPacket",MethodTypeDesc.of(CD_void,desc(ServerPacket.class)),1,c->c.aload(0).aload(1).invokestatic(host,"packet",MethodTypeDesc.of(CD_void,pd,desc(ServerPacket.class))).return_());
            b.withMethodBody("sendInfo",MethodTypeDesc.of(CD_void,pd),1,c->c.aload(0).aload(1).invokestatic(host,"info",MethodTypeDesc.of(CD_void,wo,pd)).return_());
        }));
        npcClass=MethodHandles.lookup().defineClass(cf.build(ClassDesc.of("org.l2jmobius.gameserver.managers.VisionFixtureNpc"),b->{
            b.withFlags(0x21);b.withSuperclass(desc(Npc.class));
            b.withMethodBody("hasListener",MethodTypeDesc.of(CD_boolean,desc(EventType.class)),1,c->c.iconst_0().ireturn());
            b.withMethodBody("sendInfo",MethodTypeDesc.of(CD_void,pd),1,c->c.aload(0).aload(1).invokestatic(host,"info",MethodTypeDesc.of(CD_void,wo,pd)).return_());
        }));
        revealedClass=MethodHandles.lookup().defineClass(cf.build(ClassDesc.of("org.l2jmobius.gameserver.managers.VisionFixtureRevealedNpc"),b->{
            b.withFlags(0x31);b.withSuperclass(desc(npcClass));
            b.withMethodBody("isVisibleFor",MethodTypeDesc.of(CD_boolean,pd),1,c->c.iconst_1().ireturn());
        }));
        summonClass=MethodHandles.lookup().defineClass(cf.build(ClassDesc.of("org.l2jmobius.gameserver.managers.VisionFixtureSummon"),b->{
            b.withFlags(0x31);b.withSuperclass(desc(Summon.class));
            b.withMethodBody("sendInfo",MethodTypeDesc.of(CD_void,pd),1,c->c.aload(0).aload(1).invokestatic(host,"info",MethodTypeDesc.of(CD_void,wo,pd)).return_());
        }));
    }
    static Creature object(Class<?> type,boolean invisible)throws Exception {
        Creature value=(Creature)unsafe.allocateInstance(type);
        put(value,WorldObject.class,"_objectId",sequence++);
        put(value,WorldObject.class,"_isInvisible",invisible);
        put(value,Creature.class,"_knownRelations",new HashMap<>());
        return value;
    }
    static Player player(boolean isGm,Boolean mode)throws Exception {
        Player value=(Player)object(playerClass,false);
        Vars v=(Vars)unsafe.allocateInstance(Vars.class);v.mode=mode;
        gm.put(value,isGm);vars.put(value,v);sent.put(value,new ArrayList<>());return value;
    }
    static ClientModel client(Player viewer)throws Exception {
        ClientModel client=(ClientModel)unsafe.allocateInstance(ClientModel.class);
        put(client,GameClient.class,"_player",viewer);return client;
    }
    static int bytes(ProbePacket packet,ClientModel client)throws Exception {
        // Проверяем generic dispatch, включая сгенерированный bridge writeData(Client).
        WriteBuffer buffer=((WritablePacket)packet).writeData(client);
        int size=buffer.limit();buffer.releaseResources();return size;
    }
    static boolean active(Player p){return enabled && gm(p) && (vars.get(p).mode==null?defaultMode:vars.get(p).mode);}
    static long deletes(Player p){return sent.get(p).stream().filter(x->x instanceof DeleteObject).count();}
    static MethodHandle packetPredicate(String type,boolean heroExpression)throws Exception {
        String name="org/l2jmobius/gameserver/network/serverpackets/"+type;
        byte[] bytes;
        try(var in=ClassLoader.getSystemResourceAsStream(name+".class")){bytes=in.readAllBytes();}
        MethodModel method=ClassFile.of().parse(bytes).methods().stream().filter(m->m.methodName().equalsString("writeImpl")).findFirst().orElseThrow();
        List<CodeElement> code=method.code().orElseThrow().elementStream().toList();
        int begin=-1,end=-1;
        for(int i=0;i<code.size();i++)if(code.get(i) instanceof InvokeInstruction call
                &&call.owner().asInternalName().equals("org/l2jmobius/gameserver/entity/actor/Player")
                &&call.name().equalsString(heroExpression?"isHero":"isGM")) {
            int next=i+1;while(next<code.size()&&!(code.get(next) instanceof Instruction))next++;
            if(!heroExpression&&(!(code.get(next) instanceof InvokeInstruction writer)||!writer.name().equalsString("writeInt")))continue;
            begin=i-2;
            for(int j=i+1;j<code.size();j++)if(code.get(j) instanceof InvokeInstruction writer
                    &&writer.owner().asInternalName().equals("org/l2jmobius/commons/network/buffer/WriteBuffer")
                    &&writer.name().equalsString(heroExpression?"writeByte":"writeInt")){end=j;break;}
            break;
        }
        if(begin<0||end<0)throw new AssertionError("Cannot locate actual packet predicate "+type);
        List<CodeElement> expression=code.subList(begin,end);
        ClassDesc generated=ClassDesc.of("org.l2jmobius.gameserver.managers.Vision"+type+(heroExpression?"Hero":"Builder"));
        byte[] lifted=ClassFile.of().build(generated,b->{b.withFlags(0x31);
            b.withMethodBody("evaluate",MethodTypeDesc.of(CD_boolean,desc(Player.class)),0x09,c->{
                Map<Label,Label> labels=new IdentityHashMap<>();
                for(int i=0;i<expression.size();i++) {
                    CodeElement element=expression.get(i);
                    if(element instanceof LoadInstruction load&&load.slot()==0&&i+1<expression.size()
                            &&expression.get(i+1) instanceof FieldInstruction field&&field.owner().asInternalName().equals(name)
                            &&field.name().equalsString("_player")) {c.aload(0);i++;continue;}
                    if(element instanceof BranchInstruction branch)c.with(BranchInstruction.of(branch.opcode(),labels.computeIfAbsent(branch.target(),l->c.newLabel())));
                    else if(element instanceof LabelTarget target)c.labelBinding(labels.computeIfAbsent(target.label(),l->c.newLabel()));
                    else if(element instanceof Instruction)c.with(element);
                }
                c.ireturn();
            });});
        Class<?> cls=MethodHandles.lookup().defineClass(lifted);
        return MethodHandles.lookup().findStatic(cls,"evaluate",MethodType.methodType(boolean.class,Player.class));
    }
    static void heroAndBuilderChecks()throws Throwable {
        // Исполняется выражение из реального скомпилированного пакета, а не его переписанная модель.
        for(String type:List.of("UserInfo","CharInfo")) {
            MethodHandle hero=packetPredicate(type,true);
            for(boolean gmRole:List.of(false,true))for(boolean realHero:List.of(false,true))
                for(boolean mode:List.of(false,true))for(boolean aura:List.of(false,true)) {
                    Player actor=player(gmRole,mode);heroes.put(actor,realHero);GeneralConfig.GM_HERO_AURA=aura;
                    boolean actual=(boolean)hero.invokeExact(actor);
                    check(actual==(realHero||(gmRole&&aura&&!(fixed&&enabled&&mode))),type+" actual hero predicate preserves real hero and masks only playing GM aura");
                }
        }
        MethodHandle builder=packetPredicate("UserInfo",false);
        for(boolean gmRole:List.of(false,true))for(boolean mode:List.of(false,true)) {
            Player actor=player(gmRole,mode);
            check((boolean)builder.invokeExact(actor)==gmRole,"actual UserInfo builder level retains admin role");
        }
    }
    static void run()throws Throwable {
        types();
        Player full=player(true,false), playing=player(true,true), ordinary=player(false,true), defaults=player(true,null);
        check(PlayingGmPolicy.isEnabled()==enabled,"real root enabled config");
        check(PlayingGmPolicy.isPlaying(playing)==enabled,"real persistent mode variable");
        check(PlayingGmPolicy.isPlaying(defaults)==(enabled&&defaultMode),"real root default config");
        check(!PlayingGmPolicy.isPlaying(ordinary),"ordinary actor cannot gain mode through variable");
        Creature hiddenNpc=object(npcClass,true), shownNpc=object(npcClass,false), hiddenPlayer=player(false,false);
        put(hiddenPlayer,WorldObject.class,"_isInvisible",true);
        Creature hiddenPet=object(summonClass,true), revealed=object(revealedClass,true);
        put(hiddenPet,Summon.class,"_owner",full);
        for(Creature subject:List.of(hiddenNpc,hiddenPlayer,hiddenPet)) {
            check(subject.isVisibleFor(full),"full GM retains native invisible permission");
            check(!subject.isVisibleFor(ordinary),"ordinary hidden decision unchanged");
            check(subject.isVisibleFor(playing)==(!fixed||!enabled),"actual native object decision honors mode");
            check(PlayingGmVision.canReceive(playing,subject)==(!fixed||!enabled),"late subject decision follows virtual visibility");
        }
        check(shownNpc.isVisibleFor(playing),"visible NPC stays visible");
        check(hiddenNpc.isVisibleFor(defaults)==(!fixed||!(enabled&&defaultMode)),"default mode controls real visibility");
        check(PlayingGmVision.canReceive(playing,revealed),"lawful virtual NPC reveal retained despite invisible flag");
        check(playing.isGM(),"admin role never changed");
        ClientModel fc=client(full),pc=client(playing),oc=client(ordinary);
        ProbePacket warm=new ProbePacket(hiddenNpc);warm.sendInBroadcast();
        check(bytes(warm,fc)>ConnectionConfig.HEADER_SIZE&&warm.writes==1,"real native cache warmed by full GM");
        check(bytes(warm,pc)==(fixed&&enabled?ConnectionConfig.HEADER_SIZE:7),"playing viewer cannot bypass guard through warmed shared cache");
        check(bytes(warm,fc)>ConnectionConfig.HEADER_SIZE&&warm.writes==1,"suppression does not poison full-GM broadcast cache");
        check(bytes(warm,oc)>ConnectionConfig.HEADER_SIZE,"raw ordinary send path remains native (source sendInfo guard is separate)");
        ProbePacket cold=new ProbePacket(hiddenNpc);cold.sendInBroadcast();
        check(bytes(cold,pc)==(fixed&&enabled?ConnectionConfig.HEADER_SIZE:7),"playing first recipient does not serialize hidden payload");
        check(cold.writes==(fixed&&enabled?0:1),"no hidden writeImpl/cache population");
        check(bytes(cold,fc)>ConnectionConfig.HEADER_SIZE&&cold.writes==1,"later legal viewer still receives model");
        ProbePacket switched=new ProbePacket(hiddenNpc);switched.sendInBroadcast();
        vars.get(playing).mode=false;check(bytes(switched,pc)>2,"before toggle full GM gets model");
        vars.get(playing).mode=true;check(bytes(switched,pc)==(fixed&&enabled?2:7),"queued/cache packet uses current mode at write time");
        vars.get(playing).mode=false;check(bytes(switched,pc)>2,"mode off restores full view");vars.get(playing).mode=true;
        ProbePacket normal=new ProbePacket(shownNpc);check(bytes(normal,pc)>2,"visible payload unaffected");
        check(bytes(new ProbePacket(null),pc)>2,"admin/system/unscoped packets unaffected");
        check(bytes(new ProbePacket(playing),pc)>2,"own information unaffected");
        ProbePacket revealedCache=new ProbePacket(revealed);revealedCache.sendInBroadcast();
        bytes(revealedCache,fc);bytes(revealedCache,pc);
        check(revealedCache.writes==(fixed&&enabled?2:1),"legal revealed NPC does not reuse old GM-view decoration for playing viewer");
        Player changingActor=player(true,false);ProbePacket actorCache=new ProbePacket(changingActor);actorCache.sendInBroadcast();
        bytes(actorCache,fc);vars.get(changingActor).mode=true;bytes(actorCache,fc);
        check(actorCache.writes==(fixed&&enabled?2:1),"actor mode change does not reuse stale GM_HERO_AURA cache");
        targets.put(playing,hiddenNpc);Ai ai=new Ai(playing);ai.attack(hiddenNpc);ai.cast(hiddenNpc);ai.follow=hiddenNpc;
        put(playing,Creature.class,"_ai",ai);
        shownNpc.getKnownRelations().put(playing.getObjectId(),null);shownNpc.getKnownRelations().put(full.getObjectId(),null);
        PlayingGmVision.refresh(playing,List.of(hiddenNpc,shownNpc,hiddenPlayer,hiddenPet,revealed));
        boolean blocked=fixed&&enabled;
        check((targets.get(playing)==null)==blocked,"stale hidden target cleared only in effective mode");
        check(aborts.getOrDefault(playing,0)==(blocked?2:0),"unsafe attack and cast aborted");
        check(ai.idle==(blocked?1:0),"unsafe intention stopped");
        check(deletes(playing)==(blocked?3:0),"known hidden NPC/player/pet deleted from that client only");
        check(infos.containsKey(shownNpc)&&infos.containsKey(revealed),"visible and legally revealed objects resent");
        check(!shownNpc.getKnownRelations().containsKey(playing.getObjectId())&&shownNpc.getKnownRelations().containsKey(full.getObjectId()),"only recipient relation cache invalidated");
        put(hiddenNpc,WorldObject.class,"_isInvisible",false);
        check(hiddenNpc.isVisibleFor(playing),"SHOW_ZAKEN/detection flag reveal works without early reveal mutation");
        int prior=infos.getOrDefault(hiddenNpc,0);PlayingGmVision.refresh(playing,List.of(hiddenNpc));
        check(infos.get(hiddenNpc)==prior+1,"revealed NPC appears on refresh");
        Creature own=object(summonClass,true);put(own,Summon.class,"_owner",playing);
        long before=deletes(playing);PlayingGmVision.refresh(playing,List.of(own));
        check(infos.containsKey(own)&&deletes(playing)==before,"owner pet information retained");
        vars.get(playing).mode=false;prior=infos.getOrDefault(hiddenPlayer,0);PlayingGmVision.refresh(playing,List.of(hiddenPlayer));
        check(infos.get(hiddenPlayer)==prior+1,"off refresh restores already hidden player");
        int ordinaryPackets=sent.get(ordinary).size();PlayingGmVision.refresh(ordinary,List.of(hiddenNpc));
        check(sent.get(ordinary).size()==ordinaryPackets,"refresh not applied to ordinary players");
        heroAndBuilderChecks();
        System.out.println("PLAYING_GM_VISION_OK fixed="+fixed+" enabled="+enabled+" default="+defaultMode+" checks="+checks
                +"; real_root_policy/native_visibility/native_broadcast_cache=true world_selection_actor_IO=fixture no_world_SQL_socket=true");
    }
    public static void main(String[]args)throws Exception {
        try {
            boolean guarded=false;
            try{DriverManager.getConnection("jdbc:playinggm-guard-probe:");}
            catch(AssertionError expected){guarded=expected.getMessage().contains("Blocked world/JDBC");}
            catch(java.sql.SQLException unguarded){throw new AssertionError("NoWorldAgent missing",unguarded);}
            if(!guarded)throw new AssertionError("NoWorldAgent missing");
            Field f=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");f.setAccessible(true);unsafe=(sun.misc.Unsafe)f.get(null);
            fixed=Boolean.getBoolean("vision.fixed");enabled=Boolean.parseBoolean(System.getProperty("vision.enabled","true"));
            defaultMode=Boolean.getBoolean("vision.default");run();System.exit(0);
        }catch(Throwable error){error.printStackTrace();System.exit(1);}
    }
}
