package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.entity.actor.Player;

import org.l2jmobius.gameserver.mechanics.skill.*;

import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;



/** Learned self skills use native conditions, costs and cooldowns; mutually exclusive stances are selected once. */

public final class CompanionSelfSkills {

 private static final Map<Integer,Long> NEXT=new ConcurrentHashMap<>();

 private static final Map<Integer,Set<Integer>> TOGGLES=new ConcurrentHashMap<>();

 private static final Set<String> EXCLUDE=Set.of("Transformation","Teleport","TeleportToPlayer","Escape","InstantJump","Fishing","DispelAll","DispelByCategory","DispelBySlot","CallPc","SummonPc","SilentMove");

 private CompanionSelfSkills(){}

 static void clear(Player p){NEXT.remove(p.getObjectId());}

 static boolean recoveryOnly(Skill s){
  if(s==null||s.getTargetType()!=TargetType.SELF)return false;
  if(s.getAbnormalType()==AbnormalType.FORCE_MEDITATION)return true;
  return s.hasEffectType(org.l2jmobius.gameserver.mechanics.effects.EffectType.PARALYZE)
    &&s.getEffects(EffectScope.GENERAL).stream().anyMatch(e->e.getClass().getSimpleName().equals("ManaHealOverTime"));
 }

 static boolean candidate(Skill s,boolean combat){

  if(s==null||s.isPassive()||s.isDebuff()||s.hasNegativeEffect()||s.isGMSkill()||s.isHeroSkill()||s.getTargetType()!=TargetType.SELF||(!s.isContinuous()&&!s.isToggle()))return false;

  // Invocation/meditation is a recovery action: self-paralysis must not be a combat buff.
  if(combat&&recoveryOnly(s))return false;

  if(!s.isToggle()&&s.getAbnormalTime()<20)return false;

  if(!combat&&(s.isToggle()||s.getAbnormalTime()<90||s.getReuseDelay()>180000))return false;

  return s.getEffects(EffectScope.GENERAL).stream().noneMatch(e->e.getEffectType()==org.l2jmobius.gameserver.mechanics.effects.EffectType.RELAXING||e.getEffectType()==org.l2jmobius.gameserver.mechanics.effects.EffectType.FAKE_DEATH||EXCLUDE.contains(e.getClass().getSimpleName())||e.getClass().getSimpleName().contains("Damage"));

 }

 private static int score(Skill s){return (s.getId()==109?5000:1000)+Math.min(500,s.getAbnormalLevel()*10)+Math.min(100,s.getLevel());}

 static List<Skill> select(Collection<Skill> known,boolean combat){

  List<Skill> sorted=known.stream().filter(s->candidate(s,combat)).sorted(Comparator.comparingInt(CompanionSelfSkills::score).reversed().thenComparingInt(Skill::getId)).toList();

  Set<String> groups=new HashSet<>();List<Skill> out=new ArrayList<>();

  for(Skill s:sorted){String key=s.getAbnormalType()==AbnormalType.NONE?"id:"+s.getId():s.getAbnormalType().name();if(groups.add(key))out.add(s);}return out;

 }

 static boolean covered(Player p,Skill skill){

  BuffInfo current=skill.getAbnormalType()==AbnormalType.NONE?p.getEffectList().getBuffInfoBySkillId(skill.getId()):PhantomCompat.buffByAbnormal(p,skill.getAbnormalType());

  return current!=null&&current.isInUse()&&current.getSkill().getAbnormalLevel()>=skill.getAbnormalLevel()&&(skill.isToggle()||current.getTime()>Math.min(60,Math.max(5,skill.getAbnormalTime()/3)));

 }

 public static boolean tick(Player p,boolean combat,long now){

  if(!PersonalCompanions.owned(p)||p.isDead()||p.isTeleporting()||p.isCastingNow()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel()||p.isSitting()||now<NEXT.getOrDefault(p.getObjectId(),0L))return false;

  Set<Integer> toggles=TOGGLES.computeIfAbsent(p.getObjectId(),id->ConcurrentHashMap.newKeySet());

  if(!combat)for(int id:List.copyOf(toggles)){

   Skill s=p.getKnownSkill(id);if(s==null||p.getEffectList().getBuffInfoBySkillId(id)==null){toggles.remove(id);continue;}

   NEXT.put(p.getObjectId(),now+2000);if(PhantomCompat.useMagic(p,s,false,false)){toggles.remove(id);return true;}

  }

  for(Skill s:select(p.getAllSkills(),combat)){

   if(covered(p,s)||p.isSkillDisabled(s)||!SupportSupplies.hasReagents(p,s)||p.getCurrentMp()<s.getMpInitialConsume()+s.getMpConsume()+p.getMaxMp()*0.25||p.getCurrentHp()<=s.getHpConsume()||!s.checkCondition(p,p,false))continue;

   p.setTarget(p);p.stopMove(null);NEXT.put(p.getObjectId(),now+2000);

   if(PhantomCompat.useMagic(p,s,false,false)){if(s.isToggle())toggles.add(s.getId());return true;}

  }

  NEXT.put(p.getObjectId(),now+2000);return false;

 }

}

