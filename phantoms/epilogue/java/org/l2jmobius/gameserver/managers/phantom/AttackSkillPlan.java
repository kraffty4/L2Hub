package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Field;
import java.util.*;
import org.l2jmobius.gameserver.mechanics.effects.*;
import org.l2jmobius.gameserver.mechanics.skill.*;

/** Learned HP-damage skills and a repeatable fallback, independent of names/class IDs. */
public final class AttackSkillPlan {
 private static final Field CONDITIONS;
 static {try{CONDITIONS=Skill.class.getDeclaredField("_preCondition");CONDITIONS.setAccessible(true);}catch(ReflectiveOperationException e){throw new ExceptionInInitializerError(e);}}
 private AttackSkillPlan(){}
 private static final ClassValue<Optional<Field>> EFFECT_POWER=new ClassValue<>(){
  protected Optional<Field> computeValue(Class<?> type){try{Field f=type.getDeclaredField("_power");f.setAccessible(true);return Optional.of(f);}catch(NoSuchFieldException e){return Optional.empty();}}
 };
 public static double power(Skill skill){
  double result=skill.getPower();List<AbstractEffect> effects=skill.getEffects(EffectScope.GENERAL);
  if(effects!=null)for(AbstractEffect effect:effects){var field=EFFECT_POWER.get(effect.getClass());if(field.isPresent())try{Object value=field.get().get(effect);if(value instanceof Number n)result=Math.max(result,n.doubleValue());}catch(IllegalAccessException e){throw new IllegalStateException(e);}}
  return result;
 }

 public static boolean hpDamage(Skill skill){
  if(skill.getId()==325)return false; // Noble Strider Siege Assault requires a mount, not ordinary companion combat.
  if(!skill.isActive()||skill.isToggle()||skill.isSuicideAttack()||!PhantomCompat.isAttackSkillTarget(skill))return false;
  List<AbstractEffect> effects=skill.getEffects(EffectScope.GENERAL);if(effects==null)return false;
  for(AbstractEffect effect:effects){
   String name=effect.getClass().getSimpleName();
   if(name.contains("Mana")||name.endsWith("DamageMp"))continue;
   if(Set.of(EffectType.PHYSICAL_ATTACK,EffectType.MAGICAL_ATTACK,EffectType.HP_DRAIN).contains(effect.getEffectType()))return true;
  }
  return false;
 }
 private static boolean plain(Skill skill){
  try{Object conditions=CONDITIONS.get(skill);return conditions==null||((Collection<?>)conditions).isEmpty();}
  catch(IllegalAccessException e){throw new IllegalStateException("Cannot inspect skill conditions",e);}
 }
 public static boolean repeatable(Skill s){
  return hpDamage(s)&&s.getTargetType().name().equals("ONE")&&s.getCastRange()>=400&&s.getReuseDelay()<=3000
   &&s.getItemConsumeCount()<=0&&s.getChargeConsumeCount()<=0&&power(s)>0&&plain(s);
 }
 private static int weaponCondition(Object condition,int mask){
  if(condition==null)return 0;
  Class<?> type=condition.getClass();String name=type.getSimpleName();
  try{
   if(name.equals("ConditionUsingItemType")){
    Field armor=type.getDeclaredField("_armor"),wanted=type.getDeclaredField("_mask");armor.setAccessible(true);wanted.setAccessible(true);
    return armor.getBoolean(condition)?0:((wanted.getInt(condition)&mask)!=0?1:-1);
   }
   if(name.equals("ConditionLogicAnd")){
    for(Field f:type.getDeclaredFields()){f.setAccessible(true);Object value=f.get(condition);
     if(value instanceof Iterable<?> children){for(Object child:children)if(weaponCondition(child,mask)<0)return -1;}
     else if(value instanceof Object[] children){for(Object child:children)if(weaponCondition(child,mask)<0)return -1;}
    }
   }
   return 0; // Other conditions remain native cast-time checks; never assume their result.
  }catch(ReflectiveOperationException error){throw new IllegalStateException("Native weapon condition contract changed",error);}
 }
 public static boolean weaponCompatible(Skill s,int mask){
  try{Object value=CONDITIONS.get(s);if(value instanceof Collection<?> conditions)for(Object c:conditions)if(weaponCondition(c,mask)<0)return false;return true;}
  catch(IllegalAccessException error){throw new IllegalStateException(error);}
 }
 public static List<Skill> select(Collection<Skill> known,boolean caster,int weaponMask){return select(known.stream().filter(s->weaponCompatible(s,weaponMask)).toList(),caster);}
 public static boolean sustained(Skill s){return hpDamage(s)&&s.getTargetType().name().equals("ONE")&&s.getCastRange()>=400&&s.getReuseDelay()<=3000&&s.getChargeConsumeCount()<=0&&power(s)>0&&plain(s);}
 public static List<Skill> select(Collection<Skill> known,boolean caster){
  List<Skill> attacks=known.stream().filter(AttackSkillPlan::hpDamage).sorted(Comparator.<Skill>comparingDouble(AttackSkillPlan::power).reversed().thenComparingInt(Skill::getId)).toList();
  if(!caster)return new ArrayList<>(attacks.subList(0,Math.min(3,attacks.size())));
  Skill primary=attacks.stream().filter(AttackSkillPlan::sustained).max(Comparator.<Skill>comparingDouble(AttackSkillPlan::power).thenComparingLong(s->-s.getReuseDelay()).thenComparingInt(s->-s.getItemConsumeCount()).thenComparingDouble(s->-s.getMpConsume()-s.getMpInitialConsume()).thenComparingInt(s->-s.getId())).orElse(null);
  List<Skill> result=new ArrayList<>();if(primary!=null)result.add(primary);
  for(Skill skill:attacks)if(result.size()<4&&skill!=primary&&skill.getReuseDelay()<=30000)result.add(skill);
  Skill fallback=attacks.stream().filter(AttackSkillPlan::repeatable).max(Comparator.<Skill>comparingDouble(AttackSkillPlan::power).thenComparingLong(s->-s.getReuseDelay()).thenComparingInt(s->-s.getId())).orElse(null);
  if(fallback!=null&&!result.contains(fallback)){if(result.size()<4)result.add(fallback);else result.set(result.size()-1,fallback);}
  if(result.isEmpty())result.addAll(attacks.subList(0,Math.min(4,attacks.size())));
  return result;
 }
}
