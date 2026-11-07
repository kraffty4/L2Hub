/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.nio.file.*;
import java.util.*;

public final class SourceIntegrationCheck {
 static int checks;static Path root;
 static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
 static ClassModel model(String name)throws Exception{return ClassFile.of().parse(Files.readAllBytes(root.resolve(name+".class")));}
 static MethodModel method(String owner,String name)throws Exception{return model(owner).methods().stream().filter(m->m.methodName().equalsString(name)).findFirst().orElseThrow();}
 static long calls(MethodModel m,String owner,String name){return m.code().orElseThrow().elementStream().filter(e->e instanceof InvokeInstruction i&&i.owner().asInternalName().equals(owner)&&i.name().equalsString(name)).count();}
 public static void main(String[]args)throws Exception {
  root=Path.of(args[0]);String base="org/l2jmobius/gameserver/",op=base+"mechanics/olympiad/",mp=base+"managers/phantom/";
  var start=method(op+"OlympiadGame","makeCompetitionStart");ok(calls(start,op+"EpilogueOlympiadLifecycle","started")>0,"successful-start callback present");
  ok(calls(method(op+"OlympiadGame","clearPlayers"),op+"EpilogueOlympiadLifecycle","finished")>0,"clear-game callback present");
  ok(calls(method(op+"OlympiadGameTask","run"),op+"EpilogueOlympiadLifecycle","finished")>=2,"finally compiled for normal/exceptional exits");
  ok(calls(method(op+"OlympiadGame","PlayersStatusBack"),op+"EpilogueOlympiadReconnect","remember")==2,"two return tickets");
  ok(calls(method(op+"OlympiadGame","PlayersStatusBack"),op+"EpilogueOlympiadReconnect","refresh")==1,"current World actors rebound");
  var entered=method(base+"entity/actor/Player","onPlayerEnter");ok(calls(entered,op+"EpilogueOlympiadReconnect","entered")>0,"login reconnect callback");
  for(String name:List.of("giveAvailableSkills","giveAvailableAutoGetSkills"))ok(calls(method(base+"entity/actor/Player",name),mp+"SkillLevelChoice","afterAutoLearn")>0,"learning callback "+name);
  ok(calls(entered,mp+"SkillLevelChoice","entered")>0,"login skill preference callback");
  ok(calls(method(mp+"PhantomCompanionManager","paceCasts"),mp+"OlympiadActionPace","tick")==1,"one native200ms integration");
  ok(calls(method(mp+"PhantomCompanionManager","paceCasts"),mp+"OlympiadActionPace","owns")==1,"native cast-only duplicate suppressed");
  ok(calls(method(mp+"PhantomOlympiad","drive"),mp+"OlympiadActionPace","allowDecision")==1,"slow/fast decision clock shared");
  ok(calls(method(mp+"PhantomOlympiad","driveLocked"),mp+"EpilogueOlympiadSkills","tick")==1,"new-only skill adapter wired");
  var cursor=method(op+"Olympiad","getLastNobleOlympiadPoints");ok(calls(cursor,"java/sql/ResultSet","next")==1&&calls(cursor,"java/sql/ResultSet","first")==0,"forward-only cursor");
  for(String c:List.of(op+"OlympiadGame",op+"OlympiadGameTask",mp+"PhantomOlympiad",mp+"EpilogueOlympiadSkills"))
   for(var m:model(c).methods())if(m.code().isPresent())for(var e:m.code().get())if(e instanceof InvokeInstruction i)ok(!i.owner().asInternalName().startsWith("local/l2/"),"no old module-agent API "+c);
  System.out.println("SOURCE_INTEGRATION_OK checks="+checks+"; native class bytes only, no class init/world/SQL/client");
 }
}
