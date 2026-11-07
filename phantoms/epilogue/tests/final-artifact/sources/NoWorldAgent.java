/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
import java.lang.instrument.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.security.ProtectionDomain;

public final class NoWorldAgent {
    public static void premain(String args, Instrumentation ins)throws Exception {
        ins.addTransformer(new ClassFileTransformer(){
            public byte[] transform(ClassLoader l,String n,Class<?> previous,ProtectionDomain domain,byte[] bytes){
                if(n==null || !(n.equals("java/sql/DriverManager") || n.endsWith("/DatabaseFactory")
                        || n.equals("org/l2jmobius/gameserver/GameServer") || n.equals("org/l2jmobius/loginserver/LoginServer")))return null;
                var cf=ClassFile.of();
                return cf.transformClass(cf.parse(bytes),(b,e)->{
                    if(e instanceof MethodModel m && ((m.methodName().equalsString("getConnection"))
                            || ((n.endsWith("/GameServer")||n.endsWith("/LoginServer"))
                            && (m.methodName().equalsString("main")||m.methodName().equalsString("<init>"))))) {
                        b.withMethodBody(m.methodName().stringValue(),m.methodTypeSymbol(),m.flags().flagsMask(),c->
                            c.new_(ClassDesc.of("java.lang.AssertionError")).dup().ldc("Blocked world/JDBC access in isolated test")
                             .invokespecial(ClassDesc.of("java.lang.AssertionError"),"<init>",MethodTypeDesc.of(ConstantDescs.CD_void,ConstantDescs.CD_Object)).athrow());
                    } else b.with(e);
                });
            }
        },true);
        ins.retransformClasses(java.sql.DriverManager.class);
    }
}
