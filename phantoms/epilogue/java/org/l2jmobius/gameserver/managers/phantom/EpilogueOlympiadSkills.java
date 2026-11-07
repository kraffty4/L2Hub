/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Код предназначен для L2Hub / L2J Mobius Gracia Epilogue.
 */
package org.l2jmobius.gameserver.managers.phantom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
/** Минимальная интеграция новых38–40 поверх Native205, без старой локальной тактики. */
public final class EpilogueOlympiadSkills {
 enum Kind { DEFENCE, HEAL }
 record Choice(Skill skill,Kind kind,int priority) {}
 private static final Map<Integer,Long> NEXT=new ConcurrentHashMap<>();
 private static final Set<String> DEFENCES=Set.of("PD_UP_SPECIAL","MD_UP_SPECIAL","AVOID_UP_SPECIAL","INVINCIBILITY","DEBUFF_IMMUNITY","PERFECT_DEFENCE");
 private EpilogueOlympiadSkills() {}
 public static void clear(int id) { NEXT.remove(id); }
 public static void clear(Player player) { if(player!=null)clear(player.getObjectId()); }
 static Kind kind(Skill s) {
  if(s==null||!s.isActive()||s.isToggle()||s.isPassive()||s.isGMSkill()||s.isHeroSkill()||s.isSuicideAttack()||s.isBlockedInOlympiad())return null;
  if(s.getTargetType()==TargetType.AURA&&!s.isDebuff()&&s.hasEffects(EffectScope.SELF)
   &&(DEFENCES.contains(s.getAbnormalType().name())||s.getEffects(EffectScope.SELF).stream().anyMatch(e->e.getClass().getSimpleName().equals("Invincibility"))))return Kind.DEFENCE;
  return OlympiadSelfHeal.candidate(s)?Kind.HEAL:null;
 }
 static boolean immobilizes(Skill s) { return java.util.stream.Stream.of(s.getEffects(EffectScope.GENERAL),s.getEffects(EffectScope.SELF)).filter(Objects::nonNull).flatMap(Collection::stream).anyMatch(e->e.getClass().getSimpleName().equals("ImmobileBuff")); }
 static boolean covered(Player p,Skill s) {
  BuffInfo b=s.getAbnormalType()==AbnormalType.NONE?p.getEffectList().getBuffInfoBySkillId(s.getId()):PhantomCompat.buffByAbnormal(p,s.getAbnormalType());
  return b!=null&&b.isInUse()&&b.getSkill().getAbnormalLevel()>=s.getAbnormalLevel();
 }
 static boolean sameFight(Player p,Player e) {
  return p!=null&&e!=null&&p!=e&&p.isInOlympiadMode()&&e.isInOlympiadMode()&&p.isOlympiadStart()&&e.isOlympiadStart()
   &&p.getOlympiadGameId()>=0&&p.getOlympiadGameId()==e.getOlympiadGameId()&&p.getOlympiadSide()>=1&&p.getOlympiadSide()<=2
   &&e.getOlympiadSide()==3-p.getOlympiadSide()&&p.getInstanceId()==e.getInstanceId()&&!p.isDead()&&!e.isDead()&&!p.isTeleporting()&&!e.isTeleporting();
 }
 static boolean reagents(Player p,Skill s) { return s.getItemConsumeId()<=0||s.getItemConsumeCount()<=0||p.getInventory().getInventoryItemCount(s.getItemConsumeId(),-1)>=s.getItemConsumeCount(); }
 static List<Choice> choices(Collection<Skill> learned,double hp,double cp) {
  List<Choice> choices=new ArrayList<>();
  for(Skill s:learned) {
   Kind kind=kind(s);if(kind==null||(kind==Kind.DEFENCE&&hp>75&&cp>20)||(kind==Kind.HEAL&&hp>65))continue;
   choices.add(new Choice(s,kind,(kind==Kind.DEFENCE?3000:2500)+Math.min(99,s.getAbnormalLevel())));
  }
  choices.sort(Comparator.comparingInt(Choice::priority).reversed().thenComparingInt(c->c.kind()==Kind.HEAL?c.skill().getReuseDelay():0).thenComparingInt(c->c.skill().getId()));
  return choices;
 }
 static boolean tick(Player p,Player enemy,long now) {
  if(!sameFight(p,enemy)||p.isCastingNow()||p.isSitting()||p.isFakeDeath()||now<NEXT.getOrDefault(p.getObjectId(),0L))return false;
  double hp=100d*p.getCurrentHp()/Math.max(1d,p.getMaxHp()),cp=100d*p.getCurrentCp()/Math.max(1d,p.getMaxCp());
  for(Choice c:choices(p.getAllSkills(),hp,cp)) {
   Skill s=c.skill();
   if(p.isSkillDisabled(s)||p.getCurrentMp()<s.getMpConsume()+s.getMpInitialConsume()||p.getCurrentHp()<=s.getHpConsume()||p.getCharges()<s.getChargeConsumeCount()||!reagents(p,s))continue;
   if(c.kind()==Kind.DEFENCE&&covered(p,s))continue;
   if(c.kind()==Kind.HEAL&&!OlympiadSelfHeal.worthwhile(p,s))continue;
   if(immobilizes(s)&&!PhantomCompanionManager.inReach(p,enemy,p.getPhysicalAttackRange()+40))continue;
   if(!s.checkCondition(p,p,false))continue;
   var previous=p.getTarget();
   try { p.setTarget(p);if(PhantomCompat.useMagic(p,s,true,false)){NEXT.put(p.getObjectId(),now+500);return true;} }
   finally { p.setTarget(previous); }
  }
  NEXT.put(p.getObjectId(),now+500);return false;
 }
}
