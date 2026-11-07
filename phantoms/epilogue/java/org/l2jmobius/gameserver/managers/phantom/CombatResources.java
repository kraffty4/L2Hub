package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Real Focus Sonic/Focused Force casts; no artificial free charges. */
public final class CombatResources {
 private static final Map<Integer,Long> NEXT=new ConcurrentHashMap<>();
 private static final class Settings {static final boolean ENABLED=new ConfigReader("config/Custom/HunterSupport.ini").getBoolean("AutoChargeSkills",true);}
 private CombatResources(){}
 static int needed(Collection<Skill> attacks){return Math.min(8,attacks.stream().mapToInt(Skill::getChargeConsumeCount).max().orElse(0));}
 static boolean charge(Player p,long now){
  if(!Settings.ENABLED||p.isDead()||p.isSitting()||p.isCastingNow()||p.isTeleporting()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel()||p.isInStoreMode()||p.isInsideZone(ZoneId.PEACE)||p.isControlBlocked()||p.getCurrentHpPercent()<50)return false;
  List<Skill> selected=new ArrayList<>();for(int id:p.getAutoUseSettings().getAutoSkills()){Skill s=p.getKnownSkill(id);if(s!=null)selected.add(s);}
  int desired=needed(selected);if(desired<=p.getCharges())return false;
  Skill focus=p.getKnownSkill(8);if(focus==null)focus=p.getKnownSkill(50);
  if(focus==null||p.isSkillDisabled(focus)||p.getCurrentMp()<focus.getMpConsume()+focus.getMpInitialConsume()||!SupportSupplies.hasReagents(p,focus))return false;
  long next=now+1000;Long prior=NEXT.putIfAbsent(p.getObjectId(),next);if(prior!=null&&(now<prior||!NEXT.replace(p.getObjectId(),prior,next)))return false;
  return PhantomCompat.useMagic(p,focus,false,false);
 }
 public static boolean prepareCharge(Player p){
  if(p==null||!PhantomManager.getInstance().isPhantom(p))return false;
  PhantomTemplate t=PhantomData.getInstance().getTemplate(p.getName());
  return t!=null&&(t.isHunter()||PersonalCompanions.owned(p))&&!t.isReaper()&&charge(p,System.currentTimeMillis());
 }
}
