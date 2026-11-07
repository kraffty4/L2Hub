import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Проверяет реальные class-файлы0504 без загрузки игровых классов. */
public final class PlayingGmVisionIntegrationCheck {
    static int checks;
    static Path root;
    static String prefix="org/l2jmobius/gameserver/";
    static void ok(boolean value,String name){checks++;if(!value)throw new AssertionError(name);}
    static ClassModel model(String name)throws Exception{return ClassFile.of().parse(Files.readAllBytes(root.resolve(prefix+name+".class")));}
    static MethodModel method(String owner,String name)throws Exception{return model(owner).methods().stream().filter(m->m.methodName().equalsString(name)).findFirst().orElseThrow();}
    static List<String> calls(MethodModel method){
        List<String> out=new ArrayList<>();
        for(var e:method.code().orElseThrow())if(e instanceof InvokeInstruction i)out.add(i.owner().asInternalName()+"."+i.name().stringValue()+i.type().stringValue());
        return out;
    }
    static long count(MethodModel method,String needle){return calls(method).stream().filter(c->c.contains(needle)).count();}
    static int index(List<String> values,String needle){for(int i=0;i<values.size();i++)if(values.get(i).contains(needle))return i;throw new AssertionError(needle);}
    static List<String> simple(MethodModel method){
        List<String> result=new ArrayList<>();
        for(var e:method.code().orElseThrow()) {
            if(e instanceof InvokeInstruction i)result.add(i.opcode()+":"+i.owner().asInternalName()+":"+i.name().stringValue()+i.type().stringValue());
            else if(e instanceof FieldInstruction i)result.add(i.opcode()+":"+i.owner().asInternalName()+":"+i.name().stringValue()+i.type().stringValue());
            else if(e instanceof LoadInstruction i)result.add(i.opcode()+":"+i.slot());
            else if(e instanceof Instruction i)result.add(i.opcode().name());
        }
        return result;
    }
    static void subject(String owner,String field)throws Exception {
        var method=method(owner,"getVisibilityObject");
        ok(method.code().orElseThrow().elementStream().anyMatch(e->e instanceof FieldInstruction f&&f.name().equalsString(field)),owner+" true subject bound");
    }
    public static void main(String[]args)throws Exception {
        root=Path.of(args[0]);
        var visibility=method("entity/WorldObject","isVisibleFor");
        ok(count(visibility,"PlayingGmPolicy.hasGameplayBypass")==1,"central virtual visibility policy");
        ok(count(visibility,".isGM")==0,"no raw GM visibility bypass remains");
        var writer=model("network/serverpackets/ServerPacket").methods().stream().filter(m->m.methodName().equalsString("writeData")
                &&m.methodType().stringValue().contains("GameClient;")).findFirst().orElseThrow();
        List<String> writes=calls(writer);
        ok(index(writes,"PlayingGmVision.canReceive")<index(writes,"WritablePacket.writeData"),"guard before shared cache");
        ok(index(writes,"PlayingGmVision.needsFreshInfo")<index(writes,"WritablePacket.writeData"),"current-mode decoration before cache");
        ok(model("network/serverpackets/ServerPacket").methods().stream().anyMatch(m->m.methodName().equalsString("writeData")&&(m.flags().flagsMask()&0x40)!=0),"generic writer bridge emitted");
        subject("network/serverpackets/CharInfo","_visibilityObject");
        subject("network/serverpackets/AbstractNpcInfo$NpcInfo","_npc");
        subject("network/serverpackets/AbstractNpcInfo$TrapInfo","_trap");
        subject("network/serverpackets/AbstractNpcInfo$SummonInfo","_summon");
        subject("network/serverpackets/FakePlayerInfo","_npc");
        for(var ctor:model("network/serverpackets/CharInfo").methods())if(ctor.methodName().equalsString("<init>"))
            ok(ctor.code().orElseThrow().elementStream().filter(e->e instanceof FieldInstruction f&&f.opcode()==Opcode.PUTFIELD&&f.name().equalsString("_visibilityObject")).count()==1,
               "Player/Decoy constructors retain actual subject");
        var info=method("network/serverpackets/UserInfo","writeImpl");
        ok(count(info,".isGM()Z")==1,"only explicit UserInfo builder-level GM call retained");
        ok(count(info,"PlayingGmPolicy.hasGameplayBypass")==2,"UserInfo GM title/aura guarded");
        ok(count(info,".isHero()Z")==1,"native real hero retained");
        var charInfo=method("network/serverpackets/CharInfo","writeImpl");
        ok(count(charInfo,"PlayingGmPolicy.allowsPrivilege")==1,"queued GM decoration checked at serialization");
        ok(count(charInfo,"PlayingGmPolicy.hasGameplayBypass")==1,"external GM aura guarded");
        ok(count(charInfo,".isHero()Z")==1,"external real hero retained");
        ok(count(method("network/serverpackets/AbstractNpcInfo$SummonInfo","writeImpl"),"PlayingGmPolicy.allowsPrivilege")==1,"pet decoration checked at serialization");
        ok(count(method("entity/actor/Player","sendInfo"),"PlayingGmVision.canReceive")==1,"direct Player info guard");
        ok(count(method("entity/actor/Summon","sendInfo"),"PlayingGmVision.canReceive")==1,"direct foreign Summon info guard");
        int receiver=-1;
        for(var m:model("entity/actor/Player").methods())if(m.methodName().stringValue().startsWith("lambda$broadcastCharInfo$")&&m.methodType().stringValue().contains("CharInfo;")){
            Instruction previous=null;
            for(var e:m.code().orElseThrow())if(e instanceof Instruction i){
                if(i instanceof InvokeInstruction call&&call.name().equalsString("isGM"))receiver=previous instanceof LoadInstruction l?l.slot():-2;
                previous=i;
            }
        }
        ok(receiver==2,"module44 GM-viewer receiver fix preserved in source");
        var refresh=model("managers/PlayingGmVision").methods().stream().filter(m->m.methodName().equalsString("refresh")
                &&m.methodType().stringValue().equals("(Lorg/l2jmobius/gameserver/entity/actor/Player;)V")).findFirst().orElseThrow();
        ok(count(refresh,"entity/World.getVisibleObjects")==1,"public refresh uses native same-instance region selection");
        try(JarFile jar=new JarFile(args[1])) {
            var nativePlayer=ClassFile.of().parse(jar.getInputStream(jar.getJarEntry(prefix+"entity/actor/Player.class")).readAllBytes());
            var old=nativePlayer.methods().stream().filter(m->m.methodName().equalsString("isGM")).findFirst().orElseThrow();
            ok(simple(old).equals(simple(method("entity/actor/Player","isGM"))),"global Player.isGM bytecode semantics unchanged");
        }
        System.out.println("PLAYING_GM_VISION_INTEGRATION_OK checks="+checks+"; parse-only, no game class initialization");
    }
}
