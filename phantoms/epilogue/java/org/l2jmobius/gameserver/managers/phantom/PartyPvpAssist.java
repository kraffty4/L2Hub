package org.l2jmobius.gameserver.managers.phantom;
import java.util.*;import java.util.function.BiPredicate;
import org.l2jmobius.gameserver.entity.WorldObject;import org.l2jmobius.gameserver.entity.actor.*;import org.l2jmobius.gameserver.entity.zone.ZoneId;import org.l2jmobius.gameserver.geoengine.GeoEngine;
/** Reactive party assistance; never starts fights simply because a war target is nearby. */
public final class PartyPvpAssist {
 private PartyPvpAssist(){}
 static Creature choose(Player helper,Player owner,boolean assist,boolean protect,int assistRange,int defendRange,int leash){return choose(helper,owner,assist,protect,assistRange,defendRange,leash,(a,b)->GeoEngine.getInstance().canSeeTarget(a,b));}
 static boolean legal(Player helper,Player owner,Creature target,BiPredicate<Creature,Creature> sight){
  if(target==null||!target.isPlayable()||target==helper||target==owner||target.isAlikeDead()||target.isInvul())return false;
  Player enemy=target instanceof Player p?p:target instanceof Summon s?s.getOwner():null;if(enemy==null||enemy==owner||enemy==helper||enemy.isInOlympiadMode()||enemy.isInDuel()||enemy.isOnEvent())return false;
  if((owner.isInParty()&&owner.getParty().containsPlayer(enemy))||(helper.isInParty()&&helper.getParty().containsPlayer(enemy)))return false;
  if((owner.getClanId()!=0&&owner.getClanId()==enemy.getClanId())||(owner.getAllyId()!=0&&owner.getAllyId()==enemy.getAllyId()))return false;
  if(helper.getInstanceId()!=owner.getInstanceId()||target.getInstanceId()!=helper.getInstanceId())return false;
  if(helper.isInsideZone(ZoneId.PEACE)||owner.isInsideZone(ZoneId.PEACE)||target.isInsideZone(ZoneId.PEACE))return false;
  return target.isAutoAttackable(helper)&&target.isAutoAttackable(owner)&&sight.test(helper,target);
 }
 static Creature choose(Player helper,Player owner,boolean assist,boolean protect,int assistRange,int defendRange,int leash,BiPredicate<Creature,Creature> sight){
  if(assist&&!owner.isDead()&&owner.isInCombat()&&owner.getTarget() instanceof Creature target&&owner.calculateDistance2D(target)<=assistRange&&legal(helper,owner,target,sight))return target;
  Set<Creature> attackers=new LinkedHashSet<>(helper.getAttackByList());if(protect)attackers.addAll(owner.getAttackByList());Creature nearest=null;double best=Double.MAX_VALUE;
  for(Creature enemy:attackers){
   if(!enemy.isInCombat()||!legal(helper,owner,enemy,sight)||helper.calculateDistance2D(enemy)>defendRange||owner.calculateDistance2D(enemy)>leash)continue;
   WorldObject victim=enemy.getTarget();if(victim!=helper&&(!protect||!(victim==owner||(victim instanceof Player p&&owner.isInParty()&&owner.getParty().containsPlayer(p)))))continue;
   double distance=helper.calculateDistance2D(enemy);if(distance<best){best=distance;nearest=enemy;}
  }
  return nearest;
 }
}
