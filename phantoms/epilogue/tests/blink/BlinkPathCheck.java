package isolated.blink;

import java.io.*;
import java.lang.classfile.*;
import java.lang.constant.*;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import static java.lang.constant.ConstantDescs.*;

import org.l2jmobius.gameserver.ai.CreatureAI;
import org.l2jmobius.gameserver.config.*;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.*;
import org.l2jmobius.gameserver.entity.actor.*;
import org.l2jmobius.gameserver.entity.actor.holders.creature.EffectList;
import org.l2jmobius.gameserver.entity.actor.stat.CreatureStat;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.handler.EffectHandler;
import org.l2jmobius.gameserver.mechanics.effects.*;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.util.StatSet;

/** Actual Native Creature.callSkill -> Skill.activateSkill/applyEffects -> XML handlers.
 * Constructorless actors; deterministic RNG and Geo/AI/network/movement/effect scheduling endpoints.
 * The continuous-effect sink runs admitted effects immediately without scheduling the world lifecycle.
 */
public final class BlinkPathCheck {
    static sun.misc.Unsafe unsafe;
    static Class<?> actorClass;
    static int checks, id = 890000;
    static final Map<Creature, State> states = new IdentityHashMap<>();
    static final class State {
        int moves, stuns, immobilized, damageCalls, flyPackets, shots;
        double damage;
        boolean raid, invul, immune;
        CreatureStat stat;
        EffectList effects;
        CreatureAI ai;
        final List<String> admitted = new ArrayList<>();
    }
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static boolean flag(Creature c, String name) {
        State s = states.get(c);
        return switch (name) {
            case "isRaid" -> s.raid;
            case "isInvul" -> s.invul;
            case "isAutoAttackable" -> true;
            default -> false;
        };
    }
    public static double value(Creature c, String name) {
        return switch (name) {
            case "getLevel" -> 60;
            case "getPAtk", "getMAtk" -> 1000000;
            case "getPDef", "getMDef" -> 100;
            case "getCON" -> 1;
            case "getRandomDamageMultiplier" -> 1;
            case "getAttackElement" -> -1;
            default -> 0;
        };
    }
    public static double calc(Creature c, Stat stat, double initial) {
        return stat == Stat.DEBUFF_IMMUNITY && states.get(c).immune ? 100 : initial;
    }
    public static CreatureStat stat(Creature c) { return states.get(c).stat; }
    public static EffectList effects(Creature c) { return states.get(c).effects; }
    public static CreatureAI ai(Creature c) { return states.get(c).ai; }
    public static void moved(Creature c, int x, int y, int z) {
        states.get(c).moves++;
        try { put(c, WorldObject.class, "_location", new Location(x, y, z)); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }
    public static void damage(Creature c, double amount) {
        State s = states.get(c); s.damageCalls++; s.damage += amount;
    }
    public static void stun(Creature c) { states.get(c).stuns++; }
    public static void immobilize(Creature c, boolean b) { if (b) states.get(c).immobilized++; }
    public static void packet(Creature c, ServerPacket packet) {
        if (packet.getClass().getSimpleName().equals("FlyToLocation")) states.get(c).flyPackets++;
    }
    public static void shot(Creature c) { states.get(c).shots++; }
    static ClassDesc desc(Class<?> c) { return c.describeConstable().orElseThrow(); }
    static void put(Object obj, Class<?> owner, String name, Object value) throws Exception {
        Field f = owner.getDeclaredField(name); f.setAccessible(true);
        long at = unsafe.objectFieldOffset(f);
        if (f.getType() == int.class) unsafe.putInt(obj, at, (Integer)value);
        else unsafe.putObject(obj, at, value);
    }
    static MethodTypeDesc method(Method m) {
        return MethodTypeDesc.of(desc(m.getReturnType()), Arrays.stream(m.getParameterTypes()).map(BlinkPathCheck::desc).toArray(ClassDesc[]::new));
    }
    static Creature actor() throws Exception {
        if (actorClass == null) {
            ClassDesc host = desc(BlinkPathCheck.class), creature = desc(Creature.class);
            byte[] bytes = ClassFile.of().build(ClassDesc.of("isolated.blink.BlinkFixtureCreature"), b -> {
                b.withFlags(0x31); b.withSuperclass(creature);
                Set<String> bools = Set.of("isDead", "isAlikeDead", "isRaid", "isInvul", "isGM", "isMovementDisabled", "hasAI", "isAffectedBySkill", "isInvulAgainst", "isChargedShot", "isBehind", "isInFrontOf", "isAutoAttackable");
                Set<String> nums = Set.of("getLevel", "getPAtk", "getPDef", "getMAtk", "getMDef", "getCON", "getRandomDamageMultiplier", "getAttackElement", "getAttackElementValue", "getDefenseElementValue");
                Set<String> noops = Set.of("abortAttack", "abortCast", "sendInfo", "updateAbnormalEffect", "notifyDamageReceived", "sendDamageMessage");
                for (Method m : Creature.class.getMethods()) {
                    if (Modifier.isFinal(m.getModifiers()) || Modifier.isStatic(m.getModifiers())) continue;
                    String name = m.getName(); MethodTypeDesc type = method(m);
                    if (bools.contains(name) && m.getReturnType() == boolean.class) {
                        b.withMethodBody(name, type, 1, c -> c.aload(0).ldc(name).invokestatic(host, "flag", MethodTypeDesc.of(CD_boolean, creature, CD_String)).ireturn());
                    } else if (nums.contains(name)) {
                        b.withMethodBody(name, type, 1, c -> {
                            c.aload(0).ldc(name).invokestatic(host, "value", MethodTypeDesc.of(CD_double, creature, CD_String));
                            if (m.getReturnType() == double.class) c.dreturn(); else c.d2i().ireturn();
                        });
                    } else if (noops.contains(name) && m.getReturnType() == void.class) {
                        b.withMethodBody(name, type, 1, c -> c.return_());
                    } else if (Modifier.isAbstract(m.getModifiers())) {
                        b.withMethodBody(name, type, 1, c -> {
                            if (m.getReturnType() == void.class) c.return_();
                            else if (m.getReturnType().isPrimitive()) c.iconst_0().ireturn();
                            else c.aconst_null().areturn();
                        });
                    }
                }
                b.withMethodBody("getStat", MethodTypeDesc.of(desc(CreatureStat.class)), 1, c -> c.aload(0).invokestatic(host,"stat",MethodTypeDesc.of(desc(CreatureStat.class),creature)).areturn());
                b.withMethodBody("getEffectList", MethodTypeDesc.of(desc(EffectList.class)), 1, c -> c.aload(0).invokestatic(host,"effects",MethodTypeDesc.of(desc(EffectList.class),creature)).areturn());
                b.withMethodBody("getAI", MethodTypeDesc.of(desc(CreatureAI.class)), 1, c -> c.aload(0).invokestatic(host,"ai",MethodTypeDesc.of(desc(CreatureAI.class),creature)).areturn());
                b.withMethodBody("calcStat", MethodTypeDesc.of(CD_double,desc(Stat.class),CD_double,creature,desc(Skill.class)),1,c->c.aload(0).aload(1).dload(2).invokestatic(host,"calc",MethodTypeDesc.of(CD_double,creature,desc(Stat.class),CD_double)).dreturn());
                b.withMethodBody("calcStat", MethodTypeDesc.of(CD_double,desc(Stat.class),CD_double),1,c->c.aload(0).aload(1).dload(2).invokestatic(host,"calc",MethodTypeDesc.of(CD_double,creature,desc(Stat.class),CD_double)).dreturn());
                b.withMethodBody("getAttackType",MethodTypeDesc.of(desc(WeaponType.class)),1,c->c.getstatic(desc(WeaponType.class),"SWORD",desc(WeaponType.class)).areturn());
                b.withMethodBody("setXYZ",MethodTypeDesc.of(CD_void,CD_int,CD_int,CD_int),1,c->c.aload(0).iload(1).iload(2).iload(3).invokestatic(host,"moved",MethodTypeDesc.of(CD_void,creature,CD_int,CD_int,CD_int)).return_());
                b.withMethodBody("reduceCurrentHp",MethodTypeDesc.of(CD_void,CD_double,creature,desc(Skill.class)),1,c->c.aload(0).dload(1).invokestatic(host,"damage",MethodTypeDesc.of(CD_void,creature,CD_double)).return_());
                b.withMethodBody("startStunning",MethodTypeDesc.of(CD_void),1,c->c.aload(0).invokestatic(host,"stun",MethodTypeDesc.of(CD_void,creature)).return_());
                b.withMethodBody("setIsImmobilized",MethodTypeDesc.of(CD_void,CD_boolean),1,c->c.aload(0).iload(1).invokestatic(host,"immobilize",MethodTypeDesc.of(CD_void,creature,CD_boolean)).return_());
                b.withMethodBody("broadcastPacket",MethodTypeDesc.of(CD_void,desc(ServerPacket.class)),1,c->c.aload(0).aload(1).invokestatic(host,"packet",MethodTypeDesc.of(CD_void,creature,desc(ServerPacket.class))).return_());
                b.withMethodBody("setChargedShot",MethodTypeDesc.of(CD_void,desc(org.l2jmobius.gameserver.entity.item.enums.ShotType.class),CD_boolean),1,c->c.aload(0).invokestatic(host,"shot",MethodTypeDesc.of(CD_void,creature)).return_());
            });
            actorClass = MethodHandles.lookup().defineClass(bytes);
        }
        Creature actor = (Creature)unsafe.allocateInstance(actorClass);
        State s = new State(); states.put(actor,s);
        put(actor,WorldObject.class,"_objectId",id++);
        put(actor,WorldObject.class,"_location",new Location(10000,10000,100));
        put(actor,Creature.class,"_triggerSkills",new HashMap<>());
        s.stat = new CreatureStat(actor);
        s.effects = new EffectList(actor) {
            @Override public void add(BuffInfo info) {
                for (AbstractEffect effect : info.getEffects()) {
                    s.admitted.add(effect.getClass().getSimpleName());
                    effect.onStart(info.getEffector(), info.getEffected(), info.getSkill());
                }
            }
        };
        s.ai = new CreatureAI(actor) {
            @Override public void setIntentionIdle() { }
        };
        return actor;
    }
    static void registerEffects(Path root) throws Exception {
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(p->p.toString().endsWith(".class")&&!p.getFileName().toString().contains("$")).toList()) {
                String name = root.relativize(file).toString().replace('\\','.').replace('/','.').replaceAll("\\.class$", "");
                Class<?> type = Class.forName(name,false,BlinkPathCheck.class.getClassLoader());
                if (AbstractEffect.class.isAssignableFrom(type) && !Modifier.isAbstract(type.getModifiers())) {
                    @SuppressWarnings("unchecked") Class<? extends AbstractEffect> effect=(Class<? extends AbstractEffect>)type;
                    EffectHandler.getInstance().registerHandler(effect);
                }
            }
        }
    }
    static Skill contract(TargetType type, boolean self) {
        StatSet props = new StatSet(); props.set("skill_id",990000+type.ordinal()); props.set("level",1);
        props.set("operateType",SkillOperateType.A1); props.set("targetType",type); props.set("name","target-guard contract");
        Skill s=new Skill(props);
        if(self) s.addEffect(EffectScope.SELF, SkillData.getInstance().getSkill(1448,1).getEffects(EffectScope.SELF).getFirst());
        return s;
    }
    static void nativeCase(Skill skill, int enemies, boolean fixed) throws Exception {
        Creature caster=actor(); List<WorldObject> targets=new ArrayList<>();
        for(int i=0;i<enemies;i++) targets.add(actor());
        List<WorldObject> before=List.copyOf(targets);
        caster.callSkill(skill,targets);
        int moves=(enemies>0||fixed)?1:0;
        State a=states.get(caster);
        check(a.moves==moves,"Blink "+skill.getId()+" enemies="+enemies+" self movement "+a.moves+" expected="+moves);
        check(a.flyPackets==moves,"Blink fly packet exactly once");
        check(a.damageCalls==0&&a.stuns==0,"GENERAL damage/stun never applied to caster");
        check(targets.equals(before),"Target list not changed or populated with caster");
        for(WorldObject target:targets) {
            State t=states.get(target.asCreature());
            check(t.damageCalls==1&&t.damage>0,"Native GENERAL PhysicalSoulDamage once per enemy");
            check(t.stuns==1&&t.admitted.contains("Stun"),"Native Stun admitted once per enemy");
            check(t.moves==0,"No Blink movement applied to enemy");
        }
        System.out.println("CASE native "+skill.getId()+" enemies="+enemies+" selfMoves="+a.moves+" damageTargets="+enemies);
    }
    static void run(boolean fixed, Path effects) throws Exception {
        boolean jdbcBlocked=false;
        try { java.sql.DriverManager.getConnection("jdbc:fixture-must-not-connect"); }
        catch(AssertionError ex) { jdbcBlocked=ex.getMessage().contains("Blocked world/JDBC"); }
        check(jdbcBlocked,"Existing NoWorldAgent actually blocks JDBC entrypoint");
        Field u=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");u.setAccessible(true);unsafe=(sun.misc.Unsafe)u.get(null);
        ServerConfig.DATAPACK_ROOT=new File("."); PlayerConfig.load();
        // Guards keep any accidental server/JDBC startup fatal. No main/server/DB is constructed here.
        registerEffects(effects);
        System.out.println("ACTUAL_CLASSES Creature="+Creature.class.getProtectionDomain().getCodeSource().getLocation()+" Skill="+Skill.class.getProtectionDomain().getCodeSource().getLocation()+" Blink="+Class.forName("handlers.skill.effects.Blink").getProtectionDomain().getCodeSource().getLocation());
        Skill blink=SkillData.getInstance().getSkill(1448,1),warp=SkillData.getInstance().getSkill(628,1),ud=SkillData.getInstance().getSkill(368,1);
        check(blink!=null&&warp!=null&&ud!=null,"Real Native XML skills loaded");
        check(blink.getTargetType()==TargetType.AURA&&blink.hasEffects(EffectScope.SELF),"Native Blink AURA+SELF");
        check(blink.getEffects(EffectScope.GENERAL).size()==2&&blink.getEffects(EffectScope.GENERAL).get(0).getClass().getSimpleName().equals("PhysicalSoulDamage")&&blink.getEffects(EffectScope.GENERAL).get(1).getClass().getSimpleName().equals("Stun"),"Native damage/stun preserved");
        for(int count:new int[]{0,1,3}) nativeCase(blink,count,fixed);
        Creature caster=actor(); caster.callSkill(warp,new ArrayList<>(List.of(caster)));
        check(states.get(caster).moves==1&&caster.getX()==10500,"Native Warp 628 retains one forward move");
        Creature tank=actor(); tank.callSkill(ud,new ArrayList<>());
        check(states.get(tank).admitted.contains("ImmobileBuff")==fixed,"Native Vengeance 368 no-enemy SELF defence");
        for(TargetType type:TargetType.values()) {
            Creature p=actor(); p.callSkill(contract(type,true),new ArrayList<>());
            boolean family=Set.of(TargetType.AURA,TargetType.FRONT_AURA,TargetType.BEHIND_AURA,TargetType.AURA_CORPSE_MOB,TargetType.AURA_FRIENDLY).contains(type);
            check(states.get(p).moves==(fixed&&family?1:0),"Empty target guard "+type);
        }
        for(TargetType type:List.of(TargetType.AURA,TargetType.ONE,TargetType.SELF)) {
            Creature p=actor(); p.callSkill(contract(type,true),null);
            check(states.get(p).moves==0,"Null targets rejected "+type);
            p=actor(); p.callSkill(contract(type,false),new ArrayList<>());
            check(states.get(p).moves==0&&states.get(p).shots==0,"No SELF empty rejected "+type);
        }
        // Native target effect restrictions still apply with nonempty enemy lists.
        Creature p=actor(),raid=actor();states.get(raid).raid=true;
        p.callSkill(blink,new ArrayList<>(List.of(raid)));
        check(states.get(p).moves==1&&states.get(raid).damageCalls==1&&states.get(raid).stuns==0,"Raid stun immunity preserved; Blink still self-only");
        p=actor();Creature immune=actor();states.get(immune).immune=true;
        p.callSkill(blink,new ArrayList<>(List.of(immune)));
        check(states.get(p).moves==1&&states.get(immune).damageCalls==1&&states.get(immune).stuns==0,"Native DEBUFF_IMMUNITY prevents Stun, not SELF Blink");
        p=actor();Creature invul=actor();states.get(invul).invul=true;
        p.callSkill(blink,new ArrayList<>(List.of(invul)));
        check(states.get(p).moves==1&&states.get(invul).damageCalls==0&&states.get(invul).stuns==0,"Native invulnerability rejects GENERAL effects");
        System.out.println("BLINK_PATH_"+(fixed?"FIXED":"BASELINE")+" PASS checks="+checks+" actualCallSkill=true actualActivateSkill=true actualApplyEffects=true nativeXML=true NoWorldJDBCGuard=true");
    }
    public static void main(String[] args) {
        try { run(Boolean.parseBoolean(args[0]),Path.of(args[1])); System.exit(0); }
        catch(Throwable ex){ex.printStackTrace();System.exit(1);}
    }
}
