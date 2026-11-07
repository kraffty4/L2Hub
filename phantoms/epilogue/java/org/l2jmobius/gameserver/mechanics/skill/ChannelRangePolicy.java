package org.l2jmobius.gameserver.mechanics.skill;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.util.LocationUtil;
/** Неуказанная effectRange у GROUND-канала ограничивается кругом на земле, а не отрицательной дальностью от мага. */
public final class ChannelRangePolicy {
 private ChannelRangePolicy() {}
 public static boolean inRange(Skill skill,Creature caster,Creature target) {
  if(skill==null||caster==null||target==null)return false;
  if(skill.getEffectRange()==-1&&skill.getTargetType()==TargetType.GROUND&&caster.isPlayer()) {
   var centre=caster.asPlayer().getCurrentSkillWorldPosition();
   return centre!=null&&caster.getInstanceId()==target.getInstanceId()&&target.isInsideRadius2D(centre,skill.getAffectRange());
  }
  return LocationUtil.checkIfInRange(skill.getEffectRange(),caster,target,true);
 }
}
