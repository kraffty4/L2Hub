import java.lang.instrument.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.security.ProtectionDomain;
import java.util.Set;

/** Только test JVM: заменить создание пяти singleton на заранее заданные объекты в памяти. */
public final class OwnerContextsAgent {
    public static void premain(String args,Instrumentation instrumentation){
        Set<String> targets=Set.of("org/l2jmobius/gameserver/managers/phantom/PhantomData",
            "org/l2jmobius/gameserver/managers/phantom/PhantomManager",
            "org/l2jmobius/gameserver/managers/phantom/PhantomCompanionManager",
            "org/l2jmobius/gameserver/data/SchemeBufferTable","org/l2jmobius/gameserver/data/xml/SkillData");
        instrumentation.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader loader,String name,Class<?> old,ProtectionDomain domain,byte[] bytes){
                if(name==null||!targets.contains(name))return null;
                var cf=ClassFile.of();return cf.transformClass(cf.parse(bytes),(b,e)->{
                    if(e instanceof MethodModel m&&m.methodName().equalsString("getInstance"))
                        b.withMethodBody("getInstance",m.methodTypeSymbol(),m.flags().flagsMask(),c->c.ldc(name.replace('/','.'))
                            .invokestatic(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.PersonalNoblesseCheck"),"context",MethodTypeDesc.of(ConstantDescs.CD_Object,ConstantDescs.CD_String))
                            .checkcast(m.methodTypeSymbol().returnType()).areturn());
                    else b.with(e);
                });
            }
        });
    }
}
