import java.lang.classfile.*;
import java.lang.classfile.instruction.*;
import java.lang.constant.*;
import java.lang.invoke.*;
import java.lang.reflect.*;
import java.sql.DriverManager;
import java.util.*;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import static java.lang.constant.ConstantDescs.*;

/** Исполняет настоящий начальный amount-guard из class-файла, останавливаясь до списка/инвентаря. */
public final class MultiSellLimitCheck {
    static int checks,clears,notices,reached;
    public static void clear(){clears++;}
    public static void notice(){notices++;}
    public static void reached(){reached++;}
    static void check(boolean condition,String name){checks++;if(!condition)throw new AssertionError(name);}
    public static void main(String[]args)throws Throwable {
        boolean guarded=false;
        try{DriverManager.getConnection("jdbc:multisell-must-block:");}
        catch(AssertionError expected){guarded=expected.getMessage().contains("Blocked world/JDBC");}
        catch(java.sql.SQLException absent){throw new AssertionError("NoWorldAgent required",absent);}
        if(!guarded)throw new AssertionError("NoWorldAgent required");
        boolean fixed=Boolean.getBoolean("multisell.fixed");
        String owner="org/l2jmobius/gameserver/network/clientpackets/MultiSellChoose";
        byte[] bytes;try(var in=ClassLoader.getSystemResourceAsStream(owner+".class")){bytes=in.readAllBytes();}
        var model=ClassFile.of().parse(bytes);
        var run=model.methods().stream().filter(m->m.methodName().equalsString("runImpl")).findFirst().orElseThrow();
        List<CodeElement> code=run.code().orElseThrow().elementStream().toList();
        int start=-1,end=-1;
        MethodTypeDesc setter=null,sender=null;
        for(int i=0;i<code.size();i++) {
            var element=code.get(i);
            if(start<0&&element instanceof FieldInstruction f&&f.owner().asInternalName().equals(owner)&&f.name().equalsString("_amount"))start=i-1;
            if(element instanceof InvokeInstruction call&&call.name().equalsString("setMultiSell"))setter=call.typeSymbol();
            if(start>=0&&element instanceof InvokeInstruction call&&call.name().equalsString("getMultiSell")){end=i-1;break;}
        }
        check(start>=0&&end>start&&setter!=null,"real amount block located before list access");
        List<CodeElement> block=code.subList(start,end);
        for(var element:block)if(element instanceof InvokeInstruction call&&call.name().equalsString("sendPacket"))sender=call.typeSymbol();
        check((sender!=null)==fixed,"only fixed class reports quantity error");
        check(block.stream().anyMatch(e->e instanceof FieldInstruction f&&f.name().equalsString("MULTISELL_AMOUNT_LIMIT")),"actual Native setting consulted");
        if(fixed)check(block.stream().anyMatch(e->e instanceof FieldInstruction f&&f.name().equalsString("YOU_HAVE_EXCEEDED_THE_QUANTITY_THAT_CAN_BE_INPUTTED")),"Native localized1036 message used");
        ClassDesc host=MultiSellLimitCheck.class.describeConstable().orElseThrow(),pd=Player.class.describeConstable().orElseThrow();
        MethodTypeDesc set=setter,send=sender;
        Class<?> fake=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("MultiSellFixturePlayer"),b->{
            b.withFlags(0x31);b.withSuperclass(pd);
            b.withMethodBody("setMultiSell",set,1,c->c.invokestatic(host,"clear",MethodTypeDesc.of(CD_void)).return_());
            if(send!=null)b.withMethodBody("sendPacket",send,1,c->c.invokestatic(host,"notice",MethodTypeDesc.of(CD_void)).return_());
        }));
        var uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);
        Player player=(Player)((sun.misc.Unsafe)uf.get(null)).allocateInstance(fake);
        Class<?> lifted=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("MultiSellActualAmountGuard"),b->{
            b.withFlags(0x31);
            b.withMethodBody("check",MethodTypeDesc.of(CD_void,pd,CD_long),0x09,c->{
                Map<Label,Label> labels=new IdentityHashMap<>();
                for(int i=0;i<block.size();i++) {
                    var element=block.get(i);
                    if(element instanceof LoadInstruction load&&load.slot()==0&&i+1<block.size()&&block.get(i+1) instanceof FieldInstruction f
                            &&f.owner().asInternalName().equals(owner)&&f.name().equalsString("_amount")){c.lload(1);i++;continue;}
                    if(element instanceof LoadInstruction load&&load.slot()==1){c.aload(0);continue;}
                    if(element instanceof BranchInstruction branch)c.with(BranchInstruction.of(branch.opcode(),labels.computeIfAbsent(branch.target(),l->c.newLabel())));
                    else if(element instanceof LabelTarget target)c.labelBinding(labels.computeIfAbsent(target.label(),l->c.newLabel()));
                    else if(element instanceof Instruction)c.with(element);
                }
                c.invokestatic(host,"reached",MethodTypeDesc.of(CD_void)).return_();
            });
        }));
        MethodHandle invoke=MethodHandles.lookup().findStatic(lifted,"check",MethodType.methodType(void.class,Player.class,long.class));
        for(long limit:List.of(10000L,100000L))for(long amount:new long[]{Long.MIN_VALUE,-1,0,1,9999,10000,10001,50000,55555,100000,100001,Long.MAX_VALUE}) {
            GeneralConfig.MULTISELL_AMOUNT_LIMIT=limit;clears=notices=reached=0;
            invoke.invokeExact(player,amount);
            boolean bad=amount<1||amount>limit;
            check(reached==(bad?0:1),"boundary/list decision "+amount+"/"+limit);
            check(clears==(bad?1:0),"Native list reset retained "+amount+"/"+limit);
            check(notices==(fixed&&amount>limit?1:0),"specific over-limit notice only "+amount+"/"+limit);
        }
        System.out.println("MULTISELL_LIMIT_OK fixed="+fixed+" checks="+checks+"; actual_bytecode_guard=true no_list_money_inventory_world_SQL=true");
    }
}
