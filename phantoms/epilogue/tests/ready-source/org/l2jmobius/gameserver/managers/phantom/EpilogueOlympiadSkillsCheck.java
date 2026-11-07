/* SPDX-License-Identifier: GPL-3.0-or-later */
/** Изолированная проверка исходников; не запускает игровой мир и базу. */
package org.l2jmobius.gameserver.managers.phantom;

import java.io.*;import java.nio.file.*;import java.lang.classfile.*;import java.lang.constant.*;import java.lang.invoke.MethodHandles;import java.lang.reflect.*;import java.util.*;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.xml.*;
import org.l2jmobius.gameserver.entity.*;
import org.l2jmobius.gameserver.entity.actor.*;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.holders.creature.EffectList;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.itemcontainer.PlayerInventory;
import org.l2jmobius.gameserver.handler.EffectHandler;
import org.l2jmobius.gameserver.mechanics.effects.*;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import static java.lang.constant.ConstantDescs.*;


public final class EpilogueOlympiadSkillsCheck
{
    static sun.misc.Unsafe unsafe;static Class<?> fixtureType;static PlayerTemplate template;static int checks,nextId=870000;
    static final Map<Player,State> states=new IdentityHashMap<>();
    static class State {boolean mode=true,start=true,dead,teleport,casting,sitting,fake,mage,stunned,paralyzed,sleeping,muted,rooted,rejectCast,invul,applyPercent;int game=2,side=1,instance=0,charges=10,casts,lastSkill;double hp=700,cp=0,mp=1000,maxHp=1000,cap=1000;WorldObject target,lastTarget;EffectList effects;List<Skill> skills=new ArrayList<>();Set<Integer> disabled=new HashSet<>();}
    static void check(boolean b,String label){checks++;if(!b)throw new AssertionError(label);}
    public static boolean flag(Player p,String key){State s=states.get(p);return switch(key){case"isInOlympiadMode"->s.mode;case"isOlympiadStart"->s.start;case"isDead","isAlikeDead"->s.dead;case"isTeleporting"->s.teleport;case"isCastingNow"->s.casting;case"isSitting"->s.sitting;case"isFakeDeath"->s.fake;case"isMageClass"->s.mage;case"isStunned"->s.stunned;case"isParalyzed"->s.paralyzed;case"isSleeping"->s.sleeping;case"isMuted"->s.muted;case"isRooted"->s.rooted;case"isInvul"->s.invul;default->false;};}
    public static double value(Player p,String key){State s=states.get(p);return switch(key){case"getOlympiadGameId"->s.game;case"getOlympiadSide"->s.side;case"getInstanceId"->s.instance;case"getCharges"->s.charges;case"getLevel"->85;case"getPhysicalAttackRange"->40;case"getCurrentHp"->s.hp;case"getMaxHp"->s.maxHp;case"getMaxRecoverableHp"->s.cap;case"getCurrentCp"->s.cp;case"getCurrentMp"->s.mp;default->1000;};}
    public static Collection<Skill> skills(Player p){return states.get(p).skills;}
    public static EffectList effects(Player p){return states.get(p).effects;}
    public static WorldObject target(Player p){return states.get(p).target;}
    public static void target(Player p,WorldObject t){states.get(p).target=t;}
    public static boolean disabled(Player p,Skill s){return states.get(p).disabled.contains(s.getId());}
    public static int range(Player p,Skill s){return s.getCastRange();}
    public static PlayerTemplate template(){return template;}
    public static boolean cast(Player p,Skill skill,boolean force,boolean dontMove){State s=states.get(p);if(s.rejectCast)return false;s.casts++;s.lastSkill=skill.getId();s.lastTarget=s.target;s.mp-=skill.getMpConsume()+skill.getMpInitialConsume();s.disabled.add(skill.getId());if(s.applyPercent){s.hp-=skill.getHpConsume();for(var effect:skill.getEffects(EffectScope.GENERAL))if(effect.getClass().getName().equals("handlers.skill.effects.HealPercent"))effect.onStart(p,p,skill);}return true;}
    public static void hp(Player p,double hp){states.get(p).hp=hp;}
    static ClassDesc desc(Class<?> c){return c.describeConstable().orElseThrow();}
    static void put(Object o,Class<?> type,String name,Object value)throws Exception{Field f=type.getDeclaredField(name);f.setAccessible(true);long at=unsafe.objectFieldOffset(f);if(f.getType()==int.class)unsafe.putInt(o,at,(Integer)value);else if(f.getType()==boolean.class)unsafe.putBoolean(o,at,(Boolean)value);else unsafe.putObject(o,at,value);}
    static Player player()throws Exception
    {
        if(fixtureType==null)
        {
            ClassDesc host=desc(EpilogueOlympiadSkillsCheck.class),pd=desc(Player.class),sd=desc(Skill.class);
            byte[] bytes=ClassFile.of().build(ClassDesc.of("org.l2jmobius.gameserver.managers.phantom.ArenaCombatFixturePlayer"),b->{
                b.withFlags(0x31);b.withSuperclass(pd);
                for(String name:List.of("isInOlympiadMode","isOlympiadStart","isDead","isAlikeDead","isTeleporting","isCastingNow","isSitting","isFakeDeath","isMageClass","isStunned","isParalyzed","isSleeping","isMuted","isRooted","isGM","isFakePlayer","isMounted","isInvul"))
                    b.withMethodBody(name,MethodTypeDesc.of(CD_boolean),1,c->c.aload(0).loadConstant(name).invokestatic(host,"flag",MethodTypeDesc.of(CD_boolean,pd,CD_String)).ireturn());
                for(String name:List.of("getOlympiadGameId","getOlympiadSide","getInstanceId","getCharges","getLevel","getPhysicalAttackRange","getCurrentHp","getCurrentCp","getCurrentMp","getMaxHp","getMaxCp","getMaxMp","getMaxRecoverableHp"))
                {
                    try {Class<?> ret=Player.class.getMethod(name).getReturnType();b.withMethodBody(name,MethodTypeDesc.of(desc(ret)),1,c->{c.aload(0).loadConstant(name).invokestatic(host,"value",MethodTypeDesc.of(CD_double,pd,CD_String));if(ret==int.class)c.d2i().ireturn();else c.dreturn();});}catch(Exception e){throw new RuntimeException(e);}
                }
                b.withMethodBody("getAllSkills",MethodTypeDesc.of(desc(Collection.class)),1,c->c.aload(0).invokestatic(host,"skills",MethodTypeDesc.of(desc(Collection.class),pd)).areturn());
                b.withMethodBody("getEffectList",MethodTypeDesc.of(desc(EffectList.class)),1,c->c.aload(0).invokestatic(host,"effects",MethodTypeDesc.of(desc(EffectList.class),pd)).areturn());
                b.withMethodBody("getTemplate",MethodTypeDesc.of(desc(PlayerTemplate.class)),1,c->c.invokestatic(host,"template",MethodTypeDesc.of(desc(PlayerTemplate.class))).areturn());
                b.withMethodBody("getTarget",MethodTypeDesc.of(desc(WorldObject.class)),1,c->c.aload(0).invokestatic(host,"target",MethodTypeDesc.of(desc(WorldObject.class),pd)).areturn());
                b.withMethodBody("setCurrentHp",MethodTypeDesc.of(CD_void,CD_double),1,c->c.aload(0).dload(1).invokestatic(host,"hp",MethodTypeDesc.of(CD_void,pd,CD_double)).return_());
                b.withMethodBody("setTarget",MethodTypeDesc.of(CD_void,desc(WorldObject.class)),1,c->c.aload(0).aload(1).invokestatic(host,"target",MethodTypeDesc.of(CD_void,pd,desc(WorldObject.class))).return_());
                b.withMethodBody("isSkillDisabled",MethodTypeDesc.of(CD_boolean,sd),1,c->c.aload(0).aload(1).invokestatic(host,"disabled",MethodTypeDesc.of(CD_boolean,pd,sd)).ireturn());
                b.withMethodBody("getMagicalAttackRange",MethodTypeDesc.of(CD_int,sd),1,c->c.aload(0).aload(1).invokestatic(host,"range",MethodTypeDesc.of(CD_int,pd,sd)).ireturn());
                b.withMethodBody("useMagic",MethodTypeDesc.of(CD_boolean,sd,CD_boolean,CD_boolean),1,c->c.aload(0).aload(1).iload(2).iload(3).invokestatic(host,"cast",MethodTypeDesc.of(CD_boolean,pd,sd,CD_boolean,CD_boolean)).ireturn());
            });fixtureType=MethodHandles.lookup().defineClass(bytes);
        }
        Player p=(Player)unsafe.allocateInstance(fixtureType);State s=new State();states.put(p,s);s.effects=new EffectList(p);put(p,WorldObject.class,"_objectId",nextId++);put(p,WorldObject.class,"_location",new Location(0,0,0));put(p,Player.class,"_inventory",new PlayerInventory(p));return p;
    }
    static Skill skill(int id)throws Exception{return SkillData.getInstance().getSkill(id,1);}

    static Player[] healDuel(Skill skill,double max,double hp)throws Exception {
        Player p=player(),e=player();State s=states.get(p);states.get(e).side=2;s.maxHp=max;s.cap=max;s.hp=hp;s.target=e;s.skills=List.of(skill);return new Player[]{p,e};
    }
    static Skill nativeKnown(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass cls,int id){
        return SkillTreeData.getInstance().getCompleteClassSkillTree(cls).values().stream().filter(l->l.getSkillId()==id&&l.getGetLevel()<=85).map(l->SkillData.getInstance().getSkill(id,l.getSkillLevel())).filter(Objects::nonNull).max(Comparator.comparingInt(Skill::getLevel)).orElseThrow();
    }
    static org.l2jmobius.gameserver.util.StatSet skillProperties(int id,int hp){
        var v=new org.l2jmobius.gameserver.util.StatSet();v.set("skill_id",id);v.set("level",1);v.set("operateType",SkillOperateType.A2);v.set("targetType",org.l2jmobius.gameserver.mechanics.skill.targets.TargetType.ONE);v.set("hpConsume",hp);v.set("name","Fixture contract only");return v;
    }
    static Skill contractPercent(int id,int cost,int power){
        Skill s=new Skill(skillProperties(id,cost));var props=new org.l2jmobius.gameserver.util.StatSet();props.set("name","HealPercent");var params=new org.l2jmobius.gameserver.util.StatSet();params.set("power",power);s.addEffect(EffectScope.GENERAL,AbstractEffect.createEffect(null,null,props,params));return s;
    }
    static Skill cloneTouch(int id)throws Exception{
        Skill source=skill(341),s=new Skill(skillProperties(id,source.getHpConsume()));for(EffectScope scope:EffectScope.values()){var effects=source.getEffects(scope);if(effects!=null)for(var effect:effects)s.addEffect(scope,effect);}return s;
    }
    static AbstractEffect statBuff(org.l2jmobius.gameserver.mechanics.stats.Stat stat){
        var props=new org.l2jmobius.gameserver.util.StatSet();props.set("name","Buff");var e=AbstractEffect.createEffect(null,null,props,new org.l2jmobius.gameserver.util.StatSet());e.attach(new org.l2jmobius.gameserver.mechanics.stats.functions.FuncTemplate(null,null,"Add",-1,stat,1000));return e;
    }
    static final class ContractLoader extends ClassLoader {
        ContractLoader(){super(EpilogueOlympiadSkillsCheck.class.getClassLoader());}
        Class<?> define(byte[] bytes){return defineClass("handlers.skill.effects.HealPercent",bytes,0,bytes.length);}
    }
    static AbstractEffect missingPower()throws Exception{
        byte[] bytes=ClassFile.of().build(ClassDesc.of("handlers.skill.effects.HealPercent"),b->{b.withFlags(0x31);b.withSuperclass(desc(AbstractEffect.class));b.withMethodBody("isInstant",MethodTypeDesc.of(CD_boolean),1,c->c.iconst_1().ireturn());});
        return (AbstractEffect)unsafe.allocateInstance(new ContractLoader().define(bytes));
    }
    static void selfHealChecks(boolean fixed)throws Exception{
        var cls=org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.PHOENIX_KNIGHT;
        for(var entry:List.of(Map.entry(cls,45),Map.entry(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.HELL_KNIGHT,45),Map.entry(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.EVA_TEMPLAR,58),Map.entry(cls,262))){
            Skill heal=nativeKnown(entry.getKey(),entry.getValue());check(!heal.isBlockedInOlympiad(),"Native learned heal legal "+entry);
            check("HEAL".equals(String.valueOf(EpilogueOlympiadSkills.kind(heal)))==fixed,"Learned heal classification "+entry);
            Player[] d=healDuel(heal,10000,5000);State a=states.get(d[0]);check(heal.checkCondition(d[0],d[0],false),"Native self condition "+entry);
            check(EpilogueOlympiadSkills.tick(d[0],d[1],1000)==fixed,"Learned heal cast "+entry);
            check(a.casts==(fixed?1:0)&&(!fixed||a.lastTarget==d[0])&&a.target==d[1],"Heal self target and restored opponent "+entry);
        }
        Skill touch=skill(341);check("HEAL".equals(String.valueOf(EpilogueOlympiadSkills.kind(touch)))==fixed,"Native Touch of Life recognized only after fix");
        Player[] d=healDuel(touch,10000,5000);State a=states.get(d[0]);a.applyPercent=true;
        check(EpilogueOlympiadSkills.tick(d[0],d[1],1000)==fixed,"Touch of Life chosen with positive native net");
        check(a.hp==(fixed?8785:5000),"Real HealPercent plus native cost contract records positive net");
        System.out.println("SELF_HEAL_EXPECTED="+fixed+" touch_kind="+EpilogueOlympiadSkills.kind(touch)+" touch_after="+a.hp+" casts="+a.casts);
        List<Skill> mixed=List.of(nativeKnown(cls,45),nativeKnown(cls,262),nativeKnown(cls,341));
        Player[] mixedDuel=healDuel(touch,10000,5000);State mixedState=states.get(mixedDuel[0]);mixedState.skills=mixed;mixedState.applyPercent=true;
        check(EpilogueOlympiadSkills.tick(mixedDuel[0],mixedDuel[1],1000)==fixed,"Mixed learned Paladin heals request a cast only after fix");
        int first=mixedState.lastSkill;check(first==(fixed?341:0)&&mixedState.hp==(fixed?8785:5000),"Mixed learned heals prefer useful Touch of Life before plain heals");
        mixedState.hp=5000;mixedState.mp=1000;mixedState.disabled.add(341);EpilogueOlympiadSkills.clear(mixedDuel[0]);
        check(EpilogueOlympiadSkills.tick(mixedDuel[0],mixedDuel[1],2000)==fixed,"Mixed learned heals fall back while Touch of Life is disabled");
        check(mixedState.lastSkill==(fixed?45:0)&&mixedState.casts==(fixed?2:0)&&mixedState.target==mixedDuel[1],"Native cooldown selects available Divine Heal and restores opponent");
        System.out.println("MIXED_PALADIN_EXPECTED="+fixed+" learned="+mixed.stream().map(z->z.getId()+":"+z.getLevel()).toList()+" first="+first+" disabled341_fallback="+mixedState.lastSkill+" casts="+mixedState.casts);
        if(!fixed)return;
        check(OlympiadSelfHeal.candidate(touch),"Native341 accepted with safe buff functions");
        check(!OlympiadSelfHeal.candidate(nativeKnown(cls,69)),"HP-cost fixed Sacrifice excluded");
        check(!OlympiadSelfHeal.candidate(skill(70))&&!OlympiadSelfHeal.candidate(skill(289))&&!OlympiadSelfHeal.candidate(skill(46))&&!OlympiadSelfHeal.candidate(skill(1171)),"Drain/corpse/aura remain outside self-heal route");
        Player[] small=healDuel(touch,2000,1300);check(!OlympiadSelfHeal.worthwhile(small[0],touch)&&!EpilogueOlympiadSkills.tick(small[0],small[1],1000),"Negative native net is rejected");
        Player[] low=healDuel(touch,10000,1215);check(!EpilogueOlympiadSkills.tick(low[0],low[1],1000),"Native HP cost cannot consume final HP");
        Player[] cap=healDuel(touch,10000,5000);State cs=states.get(cap[0]);cs.cap=5000;check(!EpilogueOlympiadSkills.tick(cap[0],cap[1],1000),"Zero net at recovery cap rejected");cs.cap=5500;cs.applyPercent=true;EpilogueOlympiadSkills.clear(cap[0]);check(EpilogueOlympiadSkills.tick(cap[0],cap[1],2000)&&cs.hp==5500,"Native recovery cap respected in beneficial case");
        Skill changed=contractPercent(880001,1215,27);Player[] power=healDuel(changed,10000,3000);State ps=states.get(power[0]);ps.applyPercent=true;check(OlympiadSelfHeal.percentPower(changed.getEffects(EffectScope.GENERAL).getFirst())==27,"Read actual live percent power, no50hardcode");check(EpilogueOlympiadSkills.tick(power[0],power[1],1000)&&ps.hp==4485,"Actual native27percent amount with cost");
        Skill tooWeak=contractPercent(880002,1215,10);Player[] weak=healDuel(tooWeak,10000,5000);check(!OlympiadSelfHeal.worthwhile(weak[0],tooWeak),"Positive power alone insufficient to repayHP cost");
        AbstractEffect missing=missingPower();check(Double.isNaN(OlympiadSelfHeal.percentPower(missing)),"Missing native power field fails closed");Skill missingSkill=new Skill(skillProperties(880003,1215));missingSkill.addEffect(EffectScope.GENERAL,missing);check(!OlympiadSelfHeal.candidate(missingSkill),"Missing-power heal not classified");
        Skill zero=contractPercent(880004,1215,0);check(!OlympiadSelfHeal.candidate(zero),"Zero power excluded");
        Skill general=cloneTouch(880005);general.addEffect(EffectScope.GENERAL,statBuff(org.l2jmobius.gameserver.mechanics.stats.Stat.MAX_RECOVERABLE_HP));check(!OlympiadSelfHeal.candidate(general),"General Buff changes recovery cap: reject uncertain cost heal");
        Skill self=cloneTouch(880006);self.addEffect(EffectScope.SELF,statBuff(org.l2jmobius.gameserver.mechanics.stats.Stat.STAT_CON));check(!OlympiadSelfHeal.candidate(self),"SELF Buff changes CON/maxHP: reject uncertain cost heal");
        Skill skillMod=cloneTouch(880007);var inactive=new org.l2jmobius.gameserver.mechanics.conditions.ConditionUsingItemType(org.l2jmobius.gameserver.entity.item.type.ArmorType.HEAVY.mask());check(!inactive.test(d[0],d[0],skillMod),"Modifier condition is inactive before cast");skillMod.attach(new org.l2jmobius.gameserver.mechanics.stats.functions.FuncTemplate(inactive,null,"Add",-1,org.l2jmobius.gameserver.mechanics.stats.Stat.MAX_HP,1000));check(!OlympiadSelfHeal.candidate(skillMod),"Even inactive skill-level maxHP function makes net uncertain");
        Skill shield=cloneTouch(880008);shield.attach(new org.l2jmobius.gameserver.mechanics.conditions.ConditionUsingItemType(org.l2jmobius.gameserver.entity.item.type.ArmorType.SHIELD.mask()),false);Player[] sd=healDuel(shield,10000,5000);check(!EpilogueOlympiadSkills.tick(sd[0],sd[1],1000),"Native condition not bypassed by healing choice");put(sd[0].getInventory(),org.l2jmobius.gameserver.entity.itemcontainer.Inventory.class,"_wearedMask",org.l2jmobius.gameserver.entity.item.type.ArmorType.SHIELD.mask());EpilogueOlympiadSkills.clear(sd[0]);check(EpilogueOlympiadSkills.tick(sd[0],sd[1],2000),"Native condition acceptance still casts");
        var reagentProps=skillProperties(880009,1215);reagentProps.set("itemConsumeId",1785);reagentProps.set("itemConsumeCount",1);Skill reagent=new Skill(reagentProps);for(var effect:touch.getEffects(EffectScope.GENERAL))reagent.addEffect(EffectScope.GENERAL,effect);Player[] rd=healDuel(reagent,10000,5000);check(!EpilogueOlympiadSkills.tick(rd[0],rd[1],1000),"Missing native reagent still prevents heal");
        Skill flat=nativeKnown(cls,45);Player[] mp=healDuel(flat,10000,5000);State ms=states.get(mp[0]);ms.mp=0;check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],1000),"No free MP for heal");ms.mp=1000;EpilogueOlympiadSkills.clear(mp[0]);check(EpilogueOlympiadSkills.tick(mp[0],mp[1],2000),"Heal with native mana budget");check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],2600)&&ms.casts==1,"Native reuse still excludes disabled heal");
        ms.disabled.clear();ms.start=false;check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],3200),"Heal forbidden before match start");ms.start=true;states.get(mp[1]).game=99;check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],3800),"Heal forbidden against other arena match");states.get(mp[1]).game=2;ms.hp=6600;check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],4400),"Heal not spent above65percent");ms.hp=5000;ms.skills=List.of();check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],5000),"Unlearned heal never fetched/granted");
        ms.skills=List.of(flat);ms.invul=true;check(!EpilogueOlympiadSkills.tick(mp[0],mp[1],5600),"Native fixed Heal does nothing under invulnerability: skip");Player[] inv=healDuel(touch,10000,5000);states.get(inv[0]).invul=true;states.get(inv[0]).applyPercent=true;check(EpilogueOlympiadSkills.tick(inv[0],inv[1],1000)&&states.get(inv[0]).hp==8785,"Native percent heal still works while invulnerable");
        Player[] covered=healDuel(touch,10000,5000);var buff=(BuffInfo)unsafe.allocateInstance(BuffInfo.class);put(buff,BuffInfo.class,"_skill",touch);put(buff,BuffInfo.class,"_isInUse",true);states.get(covered[0]).effects.getBuffs().add(buff);var stacked=EffectList.class.getDeclaredField("_stackedEffects");stacked.setAccessible(true);((Map<AbnormalType,BuffInfo>)stacked.get(states.get(covered[0]).effects)).put(touch.getAbnormalType(),buff);check(EpilogueOlympiadSkills.covered(covered[0],touch),"Fixture really has active Touch of Life buff");check(EpilogueOlympiadSkills.tick(covered[0],covered[1],1000),"Existing buff is not an instant-heal prohibition");
        Player[] reject=healDuel(touch,10000,5000);states.get(reject[0]).rejectCast=true;check(!EpilogueOlympiadSkills.tick(reject[0],reject[1],1000)&&states.get(reject[0]).target==reject[1]&&states.get(reject[0]).hp==5000,"Rejected native cast keeps target and spends noHP");
        var ordered=EpilogueOlympiadSkills.choices(List.of(touch,skill(368)),50,0);check(ordered.getFirst().kind()==EpilogueOlympiadSkills.Kind.DEFENCE,"UD defence retains higher priority than healing");
    }


    static void soloPartyHealCheck()throws Exception{
        boolean partyFixed=Boolean.getBoolean("solo.party.fixed");
        Skill eva=nativeKnown(org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.EVA_TEMPLAR,787);
        check(eva.getTargetType()==org.l2jmobius.gameserver.mechanics.skill.targets.TargetType.PARTY&&!eva.isBlockedInOlympiad(),"Native Touch of Eva is PARTY and not Olympiad-blocked");
        check(eva.getHpConsume()==1253&&eva.getItemConsumeCount()==0,"Native Eva HP/item costs retained");
        Player[] d=healDuel(eva,10000,5000);Player p=d[0];State st=states.get(p);
        check(!p.isInParty()&&p.getParty()==null&&!p.hasSummon(),"Actual native Player party/summon are absent");
        var party=new handlers.skill.targets.Party();
        check(party.getTargetList(eva,p,true,d[1]).equals(List.of(p)),"Native PARTY onlyFirst includes solo caster");
        check(party.getTargetList(eva,p,false,d[1]).equals(List.of(p)),"Native PARTY full scope includes solo caster, excludes selected enemy");
        org.l2jmobius.gameserver.handler.TargetHandler.getInstance().registerHandler(party);
        check(eva.getTargetList(p,false).equals(List.of(p))&&eva.getFirstOfTargetList(p)==p,"Actual Skill target handler pipeline returns solo caster");
        check(Arrays.stream(Skill.class.getDeclaredFields()).noneMatch(f->f.getType().getSimpleName().equals("AffectScope"))&&Arrays.stream(Skill.class.getDeclaredMethods()).noneMatch(m->m.getReturnType().getSimpleName().equals("AffectScope")),"This native Skill API has no AffectScope stage");
        check(!eva.checkCondition(p,p,false),"Touch of Eva forbidden without Eva'sWill trigger");
        check(!EpilogueOlympiadSkills.tick(p,d[1],1000),"No automatic party heal without Native Will prerequisite");
        var weak=(BuffInfo)unsafe.allocateInstance(BuffInfo.class);put(weak,BuffInfo.class,"_skill",SkillData.getInstance().getSkill(5563,2));put(weak,BuffInfo.class,"_isInUse",true);st.effects.getBuffs().add(weak);
        check(!eva.checkCondition(p,p,false),"Eva'sWill skilllevel2 is insufficient");
        check(!EpilogueOlympiadSkills.tick(p,d[1],2000),"No automatic party heal with insufficient Native Will level");
        st.effects.getBuffs().clear();var ready=(BuffInfo)unsafe.allocateInstance(BuffInfo.class);put(ready,BuffInfo.class,"_skill",SkillData.getInstance().getSkill(5563,3));put(ready,BuffInfo.class,"_isInUse",true);st.effects.getBuffs().add(ready);
        check(eva.checkCondition(p,p,false),"Native condition accepts level3 Eva'sWill for solo self target");
        check("HEAL".equals(String.valueOf(EpilogueOlympiadSkills.kind(eva)))==partyFixed&&OlympiadSelfHeal.candidate(eva)==partyFixed,"Only the narrow solo-party heal route is added");
        st.applyPercent=true;EpilogueOlympiadSkills.clear(p);
        check(EpilogueOlympiadSkills.tick(p,d[1],3000)==partyFixed&&st.casts==(partyFixed?1:0),"Eligible learned solo Touch of Eva baseline39 versus narrow fix");
        check(st.hp==(partyFixed?8747:5000)&&st.target==d[1],"Native percent HP benefit and restored opponent");
        st.hp=5000;check(!EpilogueOlympiadSkills.tick(p,d[1],4000)&&st.casts==(partyFixed?1:0),"Native reuse still prevents party heal recast");
        var stable=OlympiadSelfHeal.class.getDeclaredMethod("stableHpCostEffects",Skill.class);stable.setAccessible(true);
        check((Boolean)stable.invoke(null,eva),"Current Eva GENERAL/SELF/skill function metadata keeps maxHP/cap stable");
        var percent=eva.getEffects(EffectScope.GENERAL).stream().filter(e->e.getClass().getName().equals("handlers.skill.effects.HealPercent")).findFirst().orElseThrow();
        check(OlympiadSelfHeal.percentPower(percent)==50,"Native live Eva HealPercent power read");
        st.hp=5000-eva.getHpConsume();percent.onStart(p,p,eva);check(st.hp==8747,"Real native percent effect after1253HP cost has positive net at10kHP");
        st.maxHp=2000;st.cap=2000;st.hp=1300-eva.getHpConsume();percent.onStart(p,p,eva);check(st.hp==1047,"SmallHP case has negative net and must remain guarded");
        System.out.println("SOLO_PARTY_EXPECTED="+partyFixed+" targets=soloCaster condition5563lvl2=false condition5563lvl3=true kind="+EpilogueOlympiadSkills.kind(eva)+" casts="+st.casts+" maxHp10000_net=3747 maxHp2000_net=-253");
        extraSoloPartyChecks(partyFixed);

    }


    static void will(Player p,int level)throws Exception {
        State s=states.get(p);s.effects.getBuffs().clear();if(level<=0)return;
        var info=(BuffInfo)unsafe.allocateInstance(BuffInfo.class);put(info,BuffInfo.class,"_skill",SkillData.getInstance().getSkill(5563,level));put(info,BuffInfo.class,"_isInUse",true);s.effects.getBuffs().add(info);
    }
    static Player[] readyEva(Skill eva,int level,double max,double hp)throws Exception {
        Player[] d=healDuel(eva,max,hp);will(d[0],level);return d;
    }
    static org.l2jmobius.gameserver.entity.actor.Summon summon(Player owner)throws Exception {
        var sum=(org.l2jmobius.gameserver.entity.actor.Summon)unsafe.allocateInstance(org.l2jmobius.gameserver.entity.actor.instance.Servitor.class);
        put(sum,org.l2jmobius.gameserver.entity.actor.Summon.class,"_owner",owner);put(sum,Creature.class,"_template",unsafe.allocateInstance(org.l2jmobius.gameserver.entity.actor.templates.NpcTemplate.class));put(sum,WorldObject.class,"_location",new Location(0,0,0));put(sum,WorldObject.class,"_objectId",nextId++);return sum;
    }
    static void extraSoloPartyChecks(boolean fixed)throws Exception {
        Skill eva=skill(787),touch=skill(341);
        Player[] group=readyEva(eva,3,10000,5000);var party=(org.l2jmobius.gameserver.entity.groups.Party)unsafe.allocateInstance(org.l2jmobius.gameserver.entity.groups.Party.class);put(group[0],Player.class,"_party",party);
        check(group[0].getParty()!=null&&!OlympiadSelfHeal.worthwhile(group[0],eva)&&!EpilogueOlympiadSkills.tick(group[0],group[1],1000),"Non-null party blocks automatic party-heal extension before target enumeration");
        Player[] bad=readyEva(eva,3,2000,1300);check(eva.checkCondition(bad[0],bad[0],false)&&!EpilogueOlympiadSkills.tick(bad[0],bad[1],1000),"Correct Will does not override negative HP net");
        Player[] stopped=readyEva(eva,3,10000,5000);states.get(stopped[0]).start=false;check(!EpilogueOlympiadSkills.tick(stopped[0],stopped[1],1000),"Party heal blocked before own fight start");states.get(stopped[0]).start=true;states.get(stopped[1]).game=99;check(!EpilogueOlympiadSkills.tick(stopped[0],stopped[1],2000),"Party heal blocked for another match");
        Player[] own=readyEva(eva,3,10000,5000);var pet=summon(own[0]);put(own[0],Player.class,"_summon",pet);
        check(eva.getTargetList(own[0],false).equals(List.of(own[0],pet)),"Unchanged Native PARTY target list includes own in-range servitor");
        check(OlympiadSelfHeal.worthwhile(own[0],eva)==fixed,"Native own servitor is an allowed extra recipient");
        put(pet,org.l2jmobius.gameserver.entity.actor.Summon.class,"_owner",own[1]);check(!OlympiadSelfHeal.worthwhile(own[0],eva),"Foreign summon recipient fails closed");put(own[0],Player.class,"_summon",null);
        org.l2jmobius.gameserver.handler.TargetHandler.getInstance().registerHandler(new handlers.skill.targets.Party(){@Override public List<WorldObject> getTargetList(Skill s,Creature caster,boolean first,Creature target){return List.of(caster,own[1]);}});
        check(!OlympiadSelfHeal.worthwhile(own[0],eva),"Unknown native handler replacement fails closed");org.l2jmobius.gameserver.handler.TargetHandler.getInstance().registerHandler(new handlers.skill.targets.Party());
        Player[] noMana=readyEva(eva,3,10000,5000);states.get(noMana[0]).mp=0;check(EpilogueOlympiadSkills.tick(noMana[0],noMana[1],1000)==fixed,"Stock Eva costs HP only, zero mana must not block it");
        Skill groupHeal=skill(1219);check(groupHeal.getMpConsume()+groupHeal.getMpInitialConsume()>0,"Native Greater Group Heal has actual MP cost");Player[] mana=healDuel(groupHeal,10000,5000);states.get(mana[0]).mp=0;check(!EpilogueOlympiadSkills.tick(mana[0],mana[1],1000),"Native MP-cost party heal cannot cast without mana");states.get(mana[0]).mp=1000;EpilogueOlympiadSkills.clear(mana[0]);check(EpilogueOlympiadSkills.tick(mana[0],mana[1],2000)==fixed,"MP-cost party heal respects native budget");
        Player[] reject=readyEva(eva,3,10000,5000);states.get(reject[0]).rejectCast=true;check(!EpilogueOlympiadSkills.tick(reject[0],reject[1],1000)&&states.get(reject[0]).target==reject[1]&&states.get(reject[0]).hp==5000,"Rejected party cast restores target and does not spend HP");
        check(eva.getReuseHashCode()!=touch.getReuseHashCode(),"Native reuse keys differ for Touch of Life and Eva");
        Player nativeReuse=(Player)unsafe.allocateInstance(Player.class);put(nativeReuse,Creature.class,"_effectList",new EffectList(nativeReuse));put(nativeReuse,Creature.class,"_disabledSkills",new java.util.concurrent.ConcurrentHashMap<Integer,Long>());put(nativeReuse,Creature.class,"_reuseTimeStampsSkills",new java.util.concurrent.ConcurrentHashMap<Integer,org.l2jmobius.gameserver.entity.actor.holders.creature.TimeStamp>());
        nativeReuse.addTimeStamp(touch,Math.max(1000,touch.getReuseDelay()));check(nativeReuse.isSkillDisabled(touch)&&!nativeReuse.isSkillDisabled(eva),"Actual native Touch of Life timestamp does not disable Touch of Eva");nativeReuse.removeTimeStamp(touch);nativeReuse.addTimeStamp(eva,Math.max(1000,eva.getReuseDelay()));check(nativeReuse.isSkillDisabled(eva)&&!nativeReuse.isSkillDisabled(touch),"Actual native Touch of Eva timestamp does not disable Touch of Life");
        check(nativeReuse.getSkillReuseTimeStamp(eva.getReuseHashCode()).getSharedReuseGroup()==-1,"Native skill timestamp has no shared group");
        System.out.println("NATIVE_HEAL_REUSE keys341="+touch.getReuseHashCode()+" keys787="+eva.getReuseHashCode()+" delay341="+touch.getReuseDelay()+" delay787="+eva.getReuseDelay()+" independent=true");
        var cls=org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass.EVA_TEMPLAR;List<Skill> mixed=List.of(nativeKnown(cls,58),nativeKnown(cls,341),nativeKnown(cls,787));
        Player[] mixedDuel=readyEva(eva,3,10000,5000);State ms=states.get(mixedDuel[0]);ms.skills=mixed;ms.applyPercent=true;boolean shorter=fixed&&Boolean.getBoolean("heal.reuse.order");
        check(EpilogueOlympiadSkills.tick(mixedDuel[0],mixedDuel[1],1000),"Mixed Eva learned heal set finds an available heal");check(ms.lastSkill==(shorter?787:341),"Mixed Eva chooses expected native-reuse tie-break");int first=ms.lastSkill;
        Player[] weakWill=readyEva(eva,2,10000,5000);State ws=states.get(weakWill[0]);ws.skills=mixed;check(EpilogueOlympiadSkills.tick(weakWill[0],weakWill[1],1000)&&ws.lastSkill==341,"Will2 makes mixed Eva fall back to legal Touch of Life");
        Player[] disabled=readyEva(eva,3,10000,5000);State ds=states.get(disabled[0]);ds.skills=mixed;ds.disabled.add(787);check(EpilogueOlympiadSkills.tick(disabled[0],disabled[1],1000)&&ds.lastSkill==341,"Disabled Eva makes mixed set choose Touch of Life");
        Player[] plain=readyEva(eva,3,10000,5000);State fs=states.get(plain[0]);fs.skills=mixed;fs.disabled.add(787);fs.disabled.add(341);check(EpilogueOlympiadSkills.tick(plain[0],plain[1],1000)&&fs.lastSkill==58,"Both long heals disabled: learned Elemental Heal fallback");
        System.out.println("MIXED_EVA_EXPECTED="+fixed+" shorter_reuse="+shorter+" learned="+mixed.stream().map(z->z.getId()+":"+z.getLevel()).toList()+" Will3_first="+first+" Will2_fallback="+ws.lastSkill+" disabled787_fallback="+ds.lastSkill+" disabledBoth_fallback="+fs.lastSkill);
    }

    public static void main(String[] args)throws Exception
    { try {
        var uf=sun.misc.Unsafe.class.getDeclaredField("theUnsafe");uf.setAccessible(true);unsafe=(sun.misc.Unsafe)uf.get(null);template=(PlayerTemplate)unsafe.allocateInstance(PlayerTemplate.class);
        Path effects=Path.of(args[0]);try(var files=Files.walk(effects)){for(Path path:files.filter(p->p.toString().endsWith(".class")&&!p.toString().contains("$")).toList()){String name=effects.relativize(path).toString().replace('\\','.').replaceFirst("\\.class$","");if(!name.startsWith("handlers.skill.effects."))continue;Class<?> type=Class.forName(name,false,ClassLoader.getSystemClassLoader());if(AbstractEffect.class.isAssignableFrom(type)&&!Modifier.isAbstract(type.getModifiers()))EffectHandler.getInstance().registerHandler((Class<? extends AbstractEffect>)type);}}
        PlayerConfig.load();SkillData data=SkillData.getInstance();
        Skill vengeance=skill(368);
        boolean auraFixed=Boolean.getBoolean("aura.defence.expected");
        check(vengeance.getTargetType()==org.l2jmobius.gameserver.mechanics.skill.targets.TargetType.AURA,"Native Vengeance uses AURA");
        check(vengeance.hasEffects(EffectScope.SELF)&&vengeance.getEffects(EffectScope.SELF).stream().anyMatch(e->e.getClass().getSimpleName().equals("ImmobileBuff")),"Native Vengeance defensive immobility is SELF-scoped");
        check(!vengeance.isBlockedInOlympiad()&&!vengeance.isDebuff(),"Vengeance is native-legal on Olympiad");
        check(EpilogueOlympiadSkills.kind(vengeance)==(auraFixed?EpilogueOlympiadSkills.Kind.DEFENCE:null),"Vengeance classification agrees with baseline/fixed expectation");
        check(EpilogueOlympiadSkills.immobilizes(vengeance)==auraFixed,"SELF-scoped immobility recognized only after fix");
        for(int auraAttack:List.of(1171,1231))check(EpilogueOlympiadSkills.kind(skill(auraAttack))!=EpilogueOlympiadSkills.Kind.DEFENCE,"Ordinary aura attack is not a defence: "+auraAttack);
        check(EpilogueOlympiadSkills.kind(skill(395))==null,"Native hero/blocked skill remains excluded");
        Player vp=player(),ve=player();State vs=states.get(vp);states.get(ve).side=2;vs.target=ve;vs.skills=List.of(vengeance);
        check(!vengeance.checkCondition(vp,vp,false),"Native shield condition rejects empty inventory");
        check(!EpilogueOlympiadSkills.tick(vp,ve,1000)&&vs.casts==0,"No Vengeance without required shield");
        put(vp.getInventory(),org.l2jmobius.gameserver.entity.itemcontainer.Inventory.class,"_wearedMask",org.l2jmobius.gameserver.entity.item.type.ArmorType.SHIELD.mask());
        check(vengeance.checkCondition(vp,vp,false),"Native shield condition accepts fixture shield mask");
        EpilogueOlympiadSkills.clear(vp);
        check(EpilogueOlympiadSkills.tick(vp,ve,2000)==auraFixed,"Native368 skipped in37, chosen with generic aura fix");
        check(vs.casts==(auraFixed?1:0)&&(!auraFixed||(vs.lastSkill==368&&vs.lastTarget==vp)),"Vengeance requests own-centred cast through native useMagic");
        check(vs.target==ve,"Enemy target restored after Vengeance request");
        check(!EpilogueOlympiadSkills.tick(vp,ve,2600),"Disabled Vengeance is not recast");
        vs.disabled.clear();EpilogueOlympiadSkills.clear(vp);put(ve,WorldObject.class,"_location",new Location(1000,0,0));
        check(!EpilogueOlympiadSkills.tick(vp,ve,3200),"Immobile Vengeance waits until enemy is near");
        put(ve,WorldObject.class,"_location",new Location(0,0,0));vs.hp=1000;vs.cp=1000;EpilogueOlympiadSkills.clear(vp);
        check(!EpilogueOlympiadSkills.tick(vp,ve,3800),"Vengeance not wasted with full HP/CP");
        vs.hp=700;vs.cp=0;vs.mp=0;EpilogueOlympiadSkills.clear(vp);check(!EpilogueOlympiadSkills.tick(vp,ve,4400),"Vengeance costs native mana");
        vs.mp=1000;vs.start=false;check(!EpilogueOlympiadSkills.tick(vp,ve,5000),"Vengeance cannot cast before battle start");vs.start=true;
        states.get(ve).game=99;check(!EpilogueOlympiadSkills.tick(vp,ve,5600),"Vengeance ignores different match");states.get(ve).game=2;
        vs.skills=List.of();EpilogueOlympiadSkills.clear(vp);check(!EpilogueOlympiadSkills.tick(vp,ve,6200),"Unlearned Vengeance is never granted");
        System.out.println("VENGEANCE_AURA_EXPECTED="+auraFixed+" kind="+EpilogueOlympiadSkills.kind(vengeance)+" self_immobile="+EpilogueOlympiadSkills.immobilizes(vengeance)+" casts="+vs.casts);
        selfHealChecks(Boolean.getBoolean("tank.heals.fixed"));
        soloPartyHealCheck();
        int profiles=0,withDefence=0,withHeal=0;var trees=SkillTreeData.getInstance();List<Map<String,Object>> audit=new ArrayList<>();
        for(PlayerClass cls:PlayerClass.values())
        {
            var tree=trees.getCompleteClassSkillTree(cls);if(tree==null||tree.isEmpty())continue;Map<Integer,Skill> known=new HashMap<>();
            for(SkillLearn learn:tree.values())if(learn.getGetLevel()<=85){Skill s=data.getSkill(learn.getSkillId(),learn.getSkillLevel());if(s!=null&&(!known.containsKey(s.getId())||known.get(s.getId()).getLevel()<s.getLevel()))known.put(s.getId(),s);}
            var choices=EpilogueOlympiadSkills.choices(known.values(),30,0);profiles++;if(choices.stream().anyMatch(c->c.kind()==EpilogueOlympiadSkills.Kind.DEFENCE))withDefence++;if(choices.stream().anyMatch(c->c.kind()==EpilogueOlympiadSkills.Kind.HEAL))withHeal++;
            check(choices.stream().allMatch(c->known.get(c.skill().getId())==c.skill()),"Only learned abilities for "+cls);
            audit.add(Map.of("class",cls.name(),"skills",choices.stream().map(c->c.skill().getId()+"/"+c.skill().getName()+"/"+c.kind()).toList()));
        }
        Files.writeString(Path.of(args[1]),String.join("\n",audit.stream().map(Object::toString).toList())+"\n");
        System.out.println("EPILOGUE_NEW_SKILLS_CHECK_OK checks="+checks+" profiles="+profiles+" aura_defence_profiles="+withDefence+" heal_profiles="+withHeal+"; native skills/conditions/effect contract; casts recorded, not live arena");System.exit(0);
    } catch(Throwable failure) { failure.printStackTrace();System.exit(1); }
    }
}
