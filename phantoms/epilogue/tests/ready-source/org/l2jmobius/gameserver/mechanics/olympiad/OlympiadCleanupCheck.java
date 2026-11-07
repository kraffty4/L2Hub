/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.mechanics.olympiad;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import sun.misc.Unsafe;
import static java.lang.constant.ConstantDescs.*;

import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate;
import org.l2jmobius.gameserver.entity.spawns.Spawn;


public final class OlympiadCleanupCheck
{
    private static Unsafe memory;
    private static Class<?> npcType,spawnType,templateType;
    private static int checks;
    private static int taskMode;
    private static final List<String> EVENTS=new ArrayList<>();
    private static final Map<Npc,Actor> ACTORS=new IdentityHashMap<>();
    private static final Map<Spawn,Slot> SLOTS=new IdentityHashMap<>();
    private static final Map<NpcTemplate,Integer> TEMPLATES=new IdentityHashMap<>();
    private static final class Actor {int id=36402,deletes;boolean spawned=true,decayed,fail;Spawn owner;}
    private static final class Slot {Npc last;NpcTemplate template;boolean respawn=true;int stops;}

    public static int npcId(Npc n){return ACTORS.get(n).id;}
    public static Spawn owner(Npc n){return ACTORS.get(n).owner;}
    public static boolean spawned(Npc n){return ACTORS.get(n).spawned;}
    public static boolean decayed(Npc n){return ACTORS.get(n).decayed;}
    public static Npc last(Spawn s){return SLOTS.get(s).last;}
    public static NpcTemplate template(Spawn s){return SLOTS.get(s).template;}
    public static int templateId(NpcTemplate t){return TEMPLATES.get(t);}
    public static void stop(Spawn s){Slot slot=SLOTS.get(s);slot.respawn=false;slot.stops++;EVENTS.add("stop");}
    public static boolean delete(Npc n)
    {
        Actor a=ACTORS.get(n);if(a.fail)throw new IllegalStateException("injected one-side delete failure");
        ok(!SLOTS.get(a.owner).respawn,"stopRespawn before native delete/onDecay boundary");
        a.deletes++;a.spawned=false;a.decayed=true;
        if(SLOTS.get(a.owner).last==n)SLOTS.get(a.owner).last=null;
        EVENTS.add("delete");return true;
    }
    public static void boundary(String method){EVENTS.add(method);}
    public static boolean taskEntry(){if(taskMode==1)throw new IllegalArgumentException("injected task cancellation");return false;}
    private static void ok(boolean result,String why){checks++;if(!result)throw new AssertionError(why);}

    private static void fixtures()throws Exception
    {
        var field=Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);memory=(Unsafe)field.get(null);
        var host=ClassDesc.of(OlympiadCleanupCheck.class.getName());
        var npc=ClassDesc.of(Npc.class.getName());var spawn=ClassDesc.of(Spawn.class.getName());var template=ClassDesc.of(NpcTemplate.class.getName());
        npcType=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.mechanics.olympiad.CleanupFixtureNpc"),b->{
            b.withFlags(0x31);b.withSuperclass(npc);
            b.withMethodBody("getId",MethodTypeDesc.of(CD_int),1,c->c.aload(0).invokestatic(host,"npcId",MethodTypeDesc.of(CD_int,npc)).ireturn());
            b.withMethodBody("getSpawn",MethodTypeDesc.of(spawn),1,c->c.aload(0).invokestatic(host,"owner",MethodTypeDesc.of(spawn,npc)).areturn());
            b.withMethodBody("isSpawned",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"spawned",MethodTypeDesc.of(CD_boolean,npc)).ireturn());
            b.withMethodBody("isDecayed",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"decayed",MethodTypeDesc.of(CD_boolean,npc)).ireturn());
            b.withMethodBody("deleteMe",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"delete",MethodTypeDesc.of(CD_boolean,npc)).ireturn());
        }));
        spawnType=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.mechanics.olympiad.CleanupFixtureSpawn"),b->{
            b.withFlags(0x31);b.withSuperclass(spawn);
            b.withMethodBody("getTemplate",MethodTypeDesc.of(template),1,c->c.aload(0).invokestatic(host,"template",MethodTypeDesc.of(template,spawn)).areturn());
            b.withMethodBody("getLastSpawn",MethodTypeDesc.of(npc),1,c->c.aload(0).invokestatic(host,"last",MethodTypeDesc.of(npc,spawn)).areturn());
            b.withMethodBody("stopRespawn",MethodTypeDesc.of(CD_void),1,c->c.aload(0).invokestatic(host,"stop",MethodTypeDesc.of(CD_void,spawn)).return_());
        }));
        templateType=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.mechanics.olympiad.CleanupFixtureTemplate"),b->{
            b.withFlags(0x31);b.withSuperclass(template);
            b.withMethodBody("getId",MethodTypeDesc.of(CD_int),1,c->c.aload(0).invokestatic(host,"templateId",MethodTypeDesc.of(CD_int,template)).ireturn());
        }));
    }
    private static Spawn spawn()throws Exception
    {
        Spawn s=(Spawn)memory.allocateInstance(spawnType);Slot slot=new Slot();SLOTS.put(s,slot);
        slot.template=(NpcTemplate)memory.allocateInstance(templateType);TEMPLATES.put(slot.template,36402);
        Npc n=(Npc)memory.allocateInstance(npcType);Actor actor=new Actor();actor.owner=s;ACTORS.put(n,actor);slot.last=n;
        return s;
    }
    private static OlympiadGame game(Spawn one,Spawn two)throws Exception
    {
        OlympiadGame g=(OlympiadGame)memory.allocateInstance(OlympiadGame.class);g._spawnOne=one;g._spawnTwo=two;return g;
    }
    private static Npc initial(Spawn spawn){return SLOTS.get(spawn).last;}
    private static void ownership()throws Exception
    {
        EpilogueOlympiadBufferCleanup.clear(null);EpilogueOlympiadBufferCleanup.clear(game(null,null));
        ok(true,"null/empty games");
        Spawn a=spawn(),b=spawn(),old=spawn();Npc na=initial(a),nb=initial(b),historic=initial(old);
        OlympiadGame g=game(a,b);g._aborted=true;g._playerOneDisconnected=true;
        g._playerOne=(Player)memory.allocateInstance(Player.class);g._playerTwo=(Player)memory.allocateInstance(Player.class);
        g._playerOne.setOlympiadBuffCount(5);g._playerTwo.setOlympiadBuffCount(3);
        EpilogueOlympiadBufferCleanup.clear(g);
        ok(ACTORS.get(na).deletes==1&&ACTORS.get(nb).deletes==1,"both own NPCs cleaned on aborted/disconnected game");
        ok(g._playerOne.getOlympiadBuffCount()==5&&g._playerTwo.getOlympiadBuffCount()==3,"real quotas unchanged");
        ok(ACTORS.get(historic).spawned&&SLOTS.get(old).respawn,"historical unreferenced spawn untouched");
        EpilogueOlympiadBufferCleanup.clear(g);
        ok(ACTORS.get(na).deletes==1&&ACTORS.get(nb).deletes==1,"idempotent after delete removes lastSpawn");
        for(String reason:List.of("wrong-template","wrong-npc","wrong-owner","null-template","already-decayed","empty"))
        {
            a=spawn();b=spawn();na=initial(a);nb=initial(b);
            switch(reason){
                case "wrong-template"->TEMPLATES.put(SLOTS.get(a).template,36403);
                case "wrong-npc"->ACTORS.get(na).id=31688;
                case "wrong-owner"->ACTORS.get(na).owner=b;
                case "null-template"->SLOTS.get(a).template=null;
                case "already-decayed"->{ACTORS.get(na).spawned=false;ACTORS.get(na).decayed=true;}
                case "empty"->SLOTS.get(a).last=null;
            }
            EpilogueOlympiadBufferCleanup.clear(game(a,null));
            ok(ACTORS.get(na).deletes==0,"excluded NPC: "+reason);
            ok(ACTORS.get(nb).deletes==0&&SLOTS.get(b).respawn,"neighbor untouched: "+reason);
            if(reason.startsWith("wrong")||reason.equals("null-template"))ok(SLOTS.get(a).respawn,"unverified spawn not stopped: "+reason);
            else ok(!SLOTS.get(a).respawn,"owned empty/decayed spawn cannot respawn: "+reason);
        }
        a=spawn();na=initial(a);EpilogueOlympiadBufferCleanup.clear(game(a,a));
        ok(ACTORS.get(na).deletes==1&&SLOTS.get(a).stops==1,"duplicate game slot processed once");
        a=spawn();b=spawn();na=initial(a);nb=initial(b);ACTORS.get(na).fail=true;g=game(a,b);
        EpilogueOlympiadBufferCleanup.clear(g);
        ok(ACTORS.get(nb).deletes==1&&ACTORS.get(na).spawned,"one-side failure does not skip other side");
        ACTORS.get(na).fail=false;EpilogueOlympiadBufferCleanup.clear(g);
        ok(ACTORS.get(na).deletes==1&&ACTORS.get(nb).deletes==1,"retry leaves already deleted side alone");
    }
    private static void parallelGames()throws Exception
    {
        List<OlympiadGame> games=new ArrayList<>();List<Npc> npcs=new ArrayList<>();
        for(int i=0;i<40;i++){Spawn one=spawn(),two=spawn();games.add(game(one,two));npcs.add(initial(one));npcs.add(initial(two));}
        for(int i=0;i<40;i++)
        {
            
            OlympiadGame._gameIsStarted=(i%2)==0;
            EpilogueOlympiadBufferCleanup.clear(games.get(i));
            for(int n=0;n<80;n++)ok(ACTORS.get(npcs.get(n)).deletes==(n/2<=i?1:0),"isolated game ownership "+i+"/"+n);
        }
        OlympiadGame._gameIsStarted=false;
    }
    public static void main(String[] args)throws Exception
    {
        fixtures();ownership();parallelGames();
        System.out.println("OLYMPIAD_BUFFER_CLEANUP_OK checks="+checks+"; exact ownership, unchanged quotas, forty games, native205 ownership-only fixture; lifecycle checked by SourceIntegrationCheck; no world/SQL/live JVM");
    }
}
