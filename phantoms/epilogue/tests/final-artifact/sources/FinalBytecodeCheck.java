import java.lang.classfile.*;
import java.lang.classfile.instruction.InvokeInstruction;
import java.lang.reflect.*;
import java.util.*;

/** Читает только ресурсы загруженных final-JAR классов; не компилирует helper implementations. */
public final class FinalBytecodeCheck {
 static int checks;
 record Call(int index,String owner,String name){}
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static ClassModel model(String name)throws Exception{
  Class<?> c=Class.forName(name,false,ClassLoader.getSystemClassLoader());
  try(var in=c.getResourceAsStream("/"+name.replace('.','/')+".class")){if(in==null)throw new AssertionError("Missing final resource "+name);return ClassFile.of().parse(in.readAllBytes());}
 }
 static List<Call> calls(MethodModel method){
  List<Call> out=new ArrayList<>();int i=0;
  if(method.code().isPresent())for(CodeElement e:method.code().get()){if(e instanceof InvokeInstruction call)out.add(new Call(i,call.owner().asInternalName(),call.name().stringValue()));i++;}
  return out;
 }
 static void contains(String type,String method,String owner,String name)throws Exception{
  check(model(type).methods().stream().filter(m->method==null||m.methodName().equalsString(method)).flatMap(m->calls(m).stream()).anyMatch(c->c.owner.equals(owner)&&c.name.equals(name)),type+"."+method+" must call "+owner+"."+name);
 }
 static void lambdaCalls(String type,String entry,String owner,List<String> required)throws Exception{
  ClassModel cm=model(type);String thisOwner=type.replace('.','/');
  Set<String> referenced=new HashSet<>();
  for(var m:cm.methods())if(m.methodName().equalsString(entry)&&m.code().isPresent())
   for(CodeElement e:m.code().get())if(e instanceof java.lang.classfile.instruction.InvokeDynamicInstruction dynamic)
    for(var arg:dynamic.bootstrapArgs())if(arg instanceof java.lang.constant.DirectMethodHandleDesc handle
      &&handle.owner().descriptorString().equals("L"+thisOwner+";")&&handle.methodName().startsWith("lambda$"+entry+"$"))
     referenced.add(handle.methodName());
  List<MethodModel> guards=cm.methods().stream().filter(m->referenced.contains(m.methodName().stringValue()))
    .filter(m->required.stream().allMatch(name->calls(m).stream().anyMatch(c->c.owner.equals(owner)&&c.name.equals(name)))).toList();
  check(guards.size()==1,type+"."+entry+" must invoke a lambda containing all guards "+required+"; referenced="+referenced);
 }
 static void before(String type,String method,String firstOwner,String first,String laterOwner,String later)throws Exception{
  boolean found=false;
  for(var m:model(type).methods())if(method==null||m.methodName().equalsString(method)){
   List<Call> calls=calls(m);int a=calls.stream().filter(c->c.owner.equals(firstOwner)&&c.name.equals(first)).mapToInt(Call::index).min().orElse(-1);
   int b=calls.stream().filter(c->c.owner.equals(laterOwner)&&c.name.equals(later)).mapToInt(Call::index).min().orElse(-1);
   if(a>=0&&b>=0){check(a<b,type+"."+m.methodName()+" guard must precede side effect "+later);found=true;}
  }
  check(found,type+" guard and side effect not in same actual method");
 }
 public static void main(String[]args)throws Exception{
  String game="org.l2jmobius.gameserver.",base="org/l2jmobius/gameserver/",policy=base+"managers/GmShopEnchantPolicy",noble=base+"managers/phantom/PhantomPersonalNoblesse",binding=base+"managers/phantom/LocalDialogueBindings";
  before(game+"network.clientpackets.MultiSellChoose",null,policy,"canExchange",base+"entity/actor/Player","destroyItem");
  before(game+"network.clientpackets.RequestBuyItem",null,policy,"canBuy",base+"entity/actor/Player","reduceAdena");
  for(String parser:List.of("MultisellData","BuyListData"))contains(game+"data.xml."+parser,"parseDocument",policy,"filterLegacyDocument");
  contains(game+"network.serverpackets.BuyList","writeImpl",policy,"productsForViewer");
  contains(game+"network.serverpackets.ExBuySellList","<init>",policy,"productsForViewer");
  for(String service:List.of("PartyNoblesse","PhantomPartyNoblesse")){
   contains(game+"managers.phantom."+service,"eligible",noble,"allows");
   contains(game+"managers.phantom."+service,"synchronize",noble,"allows");
   before(game+"managers.phantom."+service,"synchronize",noble,"allows",base+"entity/actor/Player","setNoble");
  }
  contains(game+"managers.phantom.PhantomSupport","selfBuffSet",noble,"allows");
  contains(game+"managers.phantom.PartyBuffs","preset",noble,"allows");
  before(game+"managers.phantom.PartyBuffs","refresh",noble,"allows",base+"mechanics/skill/Skill","applyEffects");
  String controller=game+"managers.phantom.LocalCityDialogues$Controller";
  contains(controller,"tick",binding,"bind");
  lambdaCalls(controller,"tick",binding,List.of("sceneAllowed","canBind"));
  for(String m:List.of("sceneAllowed","compatible"))contains(game+"managers.phantom.LocalCityDialogues$Controller","advance",binding,m);
  contains(game+"managers.phantom.LocalReplyBank$Snapshot",null,binding,"lineAllowed");
  contains(game+"managers.phantom.LocalHeroDialogues",null,binding,"openerAllowed");
  contains(game+"managers.phantom.LocalHeroDialogues$HeroGateway","safe",base+"entity/actor/Player","isHero");
  Class<?> chat=Class.forName(game+"managers.phantom.PhantomChat");Field f=chat.getDeclaredField("CHAT_FALLBACK");f.setAccessible(true);
  check(Arrays.asList((String[])f.get(null)).equals(List.of("а?","в смысле?","ты про что?","погоди, это о чём?")),"four actual final fallback literals");
  Field order=chat.getDeclaredField("ORDER_FALLBACK");order.setAccessible(true);Map<?,?> map=(Map<?,?>)order.get(null);Map<String,Object> names=new HashMap<>();map.forEach((k,v)->names.put(((Enum<?>)k).name(),v));
  check(Objects.equals(names.get("ASSIST"),"ок")&&Objects.equals(names.get("HUNT"),"го на кач")&&Objects.equals(names.get("LEAD"),"го за мной"),"three actual final order literals");
  System.out.println("PASS FinalBytecodeCheck checks="+checks+" actual final resource bindings/prepayment guards/literals; no production recompilation");
 }
}
