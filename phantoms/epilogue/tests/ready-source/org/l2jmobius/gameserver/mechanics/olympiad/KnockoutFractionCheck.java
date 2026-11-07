/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.mechanics.olympiad;

import java.lang.classfile.ClassFile;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.Map;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.status.PlayerStatus;
import org.l2jmobius.gameserver.config.PlayerConfig;
import static java.lang.constant.ConstantDescs.*;


public final class KnockoutFractionCheck {
    static final Map<Player, State> STATES=new IdentityHashMap<>();
    static final class State { boolean dead,pending; PlayerStatus status; }
    public static final class NoRegenStatus extends PlayerStatus {
        NoRegenStatus(Player player) { super(player); }
        @Override public synchronized void startHpMpRegeneration() {}
        @Override public synchronized void stopHpMpRegeneration() {}
    }
    public static boolean dead(Player player) { return STATES.get(player).dead; }
    public static void dead(Player player, boolean value) { STATES.get(player).dead=value; }
    public static void pending(Player player, boolean value) { STATES.get(player).pending=value; }
    public static double hp(Player player) { return STATES.get(player).status.getCurrentHp(); }
    public static PlayerStatus status(Player player) { return STATES.get(player).status; }

    public static void main(String[] args)throws Exception {
        PlayerConfig.DISABLE_TUTORIAL=true; 
        Field field=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");field.setAccessible(true);
        sun.misc.Unsafe unsafe=(sun.misc.Unsafe)field.get(null);
        ClassDesc base=ClassDesc.of(Player.class.getName()),host=ClassDesc.of(KnockoutFractionCheck.class.getName());
        Class<?> fixture=MethodHandles.lookup().defineClass(ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.mechanics.olympiad.KOFractionFixturePlayer"),builder->{
            builder.withFlags(0x31);builder.withSuperclass(base);
            builder.withMethodBody("isDead",MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).invokestatic(host,"dead",MethodTypeDesc.of(CD_boolean,base)).ireturn());
            builder.withMethodBody("setDead",MethodTypeDesc.of(CD_void,CD_boolean),1,c->c.aload(0).iload(1).invokestatic(host,"dead",MethodTypeDesc.of(CD_void,base,CD_boolean)).return_());
            builder.withMethodBody("setIsPendingRevive",MethodTypeDesc.of(CD_void,CD_boolean),1,c->c.aload(0).iload(1).invokestatic(host,"pending",MethodTypeDesc.of(CD_void,base,CD_boolean)).return_());
            builder.withMethodBody("getCurrentHp",MethodTypeDesc.of(CD_double),1,c->c.aload(0).invokestatic(host,"hp",MethodTypeDesc.of(CD_double,base)).dreturn());
            builder.withMethodBody("getStatus",MethodTypeDesc.of(ClassDesc.of(PlayerStatus.class.getName())),1,c->c.aload(0).invokestatic(host,"status",MethodTypeDesc.of(ClassDesc.of(PlayerStatus.class.getName()),base)).areturn());
            builder.withMethodBody("getMaxHp",MethodTypeDesc.of(CD_int),1,c->c.bipush(100).ireturn());
            builder.withMethodBody("getOlympiadGameId",MethodTypeDesc.of(CD_int),1,c->c.bipush(7).ireturn());
            builder.withMethodBody("isInOlympiadMode",MethodTypeDesc.of(CD_boolean),1,c->c.iconst_1().ireturn());
            for(String method:new String[]{"isInvul","isCrafting","isInStoreMode","isSitting","hasSummon"})
                builder.withMethodBody(method,MethodTypeDesc.of(CD_boolean),1,c->c.iconst_0().ireturn());
            for(String method:new String[]{"abortAttack","abortCast","broadcastStatusUpdate","updateUserInfo"})
                builder.withMethodBody(method,MethodTypeDesc.of(CD_void),1,c->c.return_());
            builder.withMethodBody("stopEffectsOnDamage",MethodTypeDesc.of(CD_void,CD_boolean),1,c->c.return_());
        }));
        Player first=(Player)unsafe.allocateInstance(fixture),second=(Player)unsafe.allocateInstance(fixture);
        for(Player player:new Player[]{first,second}) {
            State state=new State();STATES.put(player,state);state.status=new NoRegenStatus(player);state.status.setCurrentHp(1,false);
        }
        OlympiadGame game=(OlympiadGame)unsafe.allocateInstance(OlympiadGame.class);
        game._playerOne=first;game._playerTwo=second;
        if(game.haveWinner())throw new AssertionError("healthy match unexpectedly ended");
        
        STATES.get(second).status.reduceHp(0.75,null,false,true,false,true);
        if(!second.isDead() || !STATES.get(second).pending || second.getCurrentHp()!=0.25)throw new AssertionError("native KO fixture was not reproduced");
        boolean finished=game.haveWinner();
        String mode=args.length==0?"baseline":args[0];
        if(mode.equals("baseline") && finished)throw new AssertionError("baseline no longer contains reported mismatch");
        if(mode.equals("fixed") && !finished)throw new AssertionError("fixed referee still ignores native KO");
        System.out.println("KNOCKOUT_FRACTION "+mode+" native_damage=0.75 remaining_hp="+second.getCurrentHp()+" dead="+second.isDead()+" pending_revive="+STATES.get(second).pending+" haveWinner="+finished+"; no world/SQL/client");
        int checks=0;
        for(double remaining:new double[]{0,0.0001,0.01,0.1,0.25,0.499999,0.5,0.75,1}) {
            for(Player victim:new Player[]{first,second}) {
                for(Player player:new Player[]{first,second}) {
                    State state=STATES.get(player);state.dead=false;state.pending=false;state.status.setCurrentHp(1,false);
                }
                STATES.get(victim).status.reduceHp(1-remaining,null,false,true,false,true);
                if(Math.abs(victim.getCurrentHp()-remaining)>1e-8)throw new AssertionError("native residual HP changed");
                if(victim.isDead()!=(remaining<0.5))throw new AssertionError("native KO threshold changed");
                boolean wanted=mode.equals("fixed")?remaining<0.5:remaining==0;
                if(game.haveWinner()!=wanted)throw new AssertionError("referee boundary "+remaining+" mode="+mode);
                checks+=3;
            }
        }
        for(Player player:new Player[]{first,second})STATES.get(player).dead=false;
        game._aborted=true;if(!game.haveWinner())throw new AssertionError("abort behavior changed");checks++;
        game._aborted=false;game._playerOneDisconnected=true;if(!game.haveWinner())throw new AssertionError("disconnect behavior changed");checks++;
        game._playerOneDisconnected=false;game._playerOne=null;if(!game.haveWinner())throw new AssertionError("missing-player behavior changed");checks++;
        System.out.println("KNOCKOUT_BOUNDARIES "+mode+" checks="+checks+"; real native damage/status, both sides and0.5boundary");
    }
}
