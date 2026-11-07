import java.lang.instrument.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.security.ProtectionDomain;
/** Только test JVM: in-memory phantom registry и видимые игроки, без создания мира. */
public final class DialogueContextsAgent {
 public static void premain(String args,Instrumentation ins){ins.addTransformer(new ClassFileTransformer(){
  public byte[] transform(ClassLoader loader,String name,Class<?> old,ProtectionDomain domain,byte[] bytes){
   if(name==null||!(name.equals("org/l2jmobius/gameserver/managers/phantom/PhantomManager")||name.equals("org/l2jmobius/gameserver/entity/World")))return null;
   var cf=ClassFile.of();return cf.transformClass(cf.parse(bytes),(b,e)->{
    if(e instanceof MethodModel m&&((name.endsWith("/PhantomManager")&&m.methodName().equalsString("getInstance"))||(name.endsWith("/World")&&m.methodName().equalsString("getPlayers")))){
     b.withMethodBody(m.methodName().stringValue(),m.methodTypeSymbol(),m.flags().flagsMask(),c->c.invokestatic(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.DialogueRuntimeCheck"),name.endsWith("/World")?"players":"registry",MethodTypeDesc.of(m.methodTypeSymbol().returnType())).areturn());
    }else b.with(e);
   });
  }
 });}
}
