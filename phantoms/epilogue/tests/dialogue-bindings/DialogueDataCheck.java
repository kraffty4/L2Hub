package org.l2jmobius.gameserver.managers.phantom;
import java.nio.file.*;
import java.sql.DriverManager;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Настоящие runtime XML-reader и metadata-reader на неизменённых авторских банках. */
public final class DialogueDataCheck {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 public static void main(String[]args)throws Exception{
  try{DriverManager.getConnection("jdbc:dialogue-data-must-block:");throw new AssertionError("NoWorld missing");}catch(AssertionError e){if(!e.getMessage().startsWith("Blocked world/JDBC"))throw e;checks++;}
  Path data=Path.of(args[0]);var rules=LocalDialogueBindings.read(data.resolve("LocalDialogueBindings.xml"));
  check(rules.loaded()&&rules.scenes().size()==1384&&rules.lines().size()==7245&&rules.openers().size()==12,"full metadata snapshot loaded");
  var replies=LocalReplyBank.read(data.resolve("LocalReplyBank.xml"));check(replies.entries.size()==17190,"all original reply records retained");
  for(var entry:replies.entries)if(entry.id().startsWith("rr20261106-"))check(rules.lines().containsKey("LocalReplyBank.xml#"+entry.id()),"authored standalone/scene line metadata exists");
  Map<String,Integer> counts=new LinkedHashMap<>();
  for(String file:List.of("LocalCityDialogues.xml","LocalPartyDialogues.xml","LocalPartyLongDialogues.xml","LocalClanDialogues.xml","LocalHeroDialogues.xml")){
   var loaded=LocalCityDialogues.Bank.read(data.resolve(file));counts.put(file,loaded.size());
   check(!loaded.isEmpty(),"actual Native source parser accepted "+file);
   for(var scene:loaded){
    var rule=rules.scenes().get(file+"#"+scene.id());
    if(rule!=null&&!rule.voices().isEmpty())check(rule.voices().keySet().equals(new HashSet<>(scene.roles())),"runtime role names match bindings "+file+"#"+scene.id());
    for(var line:scene.lines())if(line.id().startsWith("rr20261106-"))check(rule!=null&&rules.lines().containsKey(file+"#"+line.id()),"authored scene is bound "+line.id());
   }
  }
  check(counts.get("LocalPartyLongDialogues.xml")==51&&counts.get("LocalClanDialogues.xml")==29&&counts.get("LocalHeroDialogues.xml")==18,"long/clan/hero whole banks retained");
  var openers=new LocalHeroDialogues.Openers(data.resolve("LocalHeroOpeners.txt")).get(1000000);check(openers.size()==12&&rules.openers().keySet().equals(new HashSet<>(openers)),"all12 exact hero openers bound");
  check(LocalReplyBank.LOW_MP.get("rr20261106-b018-e208").equals("мп восстановим? маны вообще нет"),"LOW_MP exact ID+text migrated");
  check(LocalReplyBank.LOW_HP.get("rr20261106-b018-e321").equals("хил, хп почти нет!"),"LOW_HP exact ID+text migrated");
  check(LocalReplyBank.RAID_READY.get("rr20261106-b018-e250").equals("все готовы? когда начинаем?"),"RAID_READY exact ID+text migrated");
  check(LocalReplyBank.CONFIRMED_LFP.get("rr20261106-b019-e137").equals("возьмите в пати, одной надоело"),"female LFP exact ID+text migrated");
  check(!LocalReplyBank.CITY_STARTERS.containsKey("city-0112")&&LocalReplyBank.replyToPrompt("city-0112","с музыкой")==null,"old music-question branch retired, not mechanically remapped");
  System.out.println("PASS DialogueDataCheck checks="+checks+" replies="+replies.entries.size()+" runtimeScenes="+counts+" metadata=1384/7245/12; actual source readers; no world/SQL");
 }
}
