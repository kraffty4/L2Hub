package org.l2jmobius.gameserver.managers.phantom;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Personal helpers receive the same kind of kit as their existing equipment/shots. No owner adena is charged. */
public final class PersonalCompanions {
 public static final int HP=1539,MP=728,QUICK_HP=1540;
 private static final Map<Integer,Long> NEXT_STOCK=new ConcurrentHashMap<>();
 private static final class Settings {
  static final ConfigReader C=new ConfigReader("config/Custom/PhantomParty.ini");
  static final int POTIONS=Math.max(0,Math.min(100,C.getInt("PersonalCompanionPotionStock",10)));
  static final int ORE=Math.max(0,Math.min(1000,C.getInt("PersonalCompanionSpiritOreStock",100)));
  static final int WATER=Math.max(0,Math.min(100,C.getInt("PersonalCompanionHolyWaterStock",10)));
 }
 private static final java.util.Set<Integer> PREPARING=java.util.concurrent.ConcurrentHashMap.newKeySet();
 public static void afterRevive(Player p){
  if(p==null||p.isDead()||!owned(p))return;
  p.abortAttack();p.abortCast();if(p.hasAI())p.getAI().setIntentionActive();p.setTarget(null);
  PREPARING.add(p.getObjectId());PartyBuffs.request(p);CompanionSelfSkills.clear(p);
 }
 /** Hold personal fighters until their actual preset effects are active; no command required. */
 public static boolean readyForAction(Player p,long now){
  if(!owned(p))return true;
  if(p.isDead()||p.isTeleporting()||p.isOnEvent()||p.isInOlympiadMode()||p.isInDuel())return false;
  if(PartyBuffs.ready(p)){if(PREPARING.remove(p.getObjectId()))p.getEffectList().updateEffectIcons(true);return true;}
  if(!PREPARING.contains(p.getObjectId())&&p.isInCombat())return true; // Preserve combat restrictions for ordinary dispels/expiry.
  if(PREPARING.contains(p.getObjectId())){p.abortAttack();if(p.isCastingNow())p.abortCast();if(p.hasAI())p.getAI().setIntentionActive();p.setTarget(null);}
  PartyBuffs.request(p);PartyBuffs.prepare(p,now);
  if(!PartyBuffs.ready(p))return false;
  PREPARING.remove(p.getObjectId());p.getEffectList().updateEffectIcons(true);return true;
 }
 private PersonalCompanions(){}
 static boolean personal(PhantomTemplate template){return template!=null&&!template.isHunter()&&!template.isReaper();}
 public static boolean owned(Player p){
  if(p==null||!PhantomManager.getInstance().isPhantom(p))return false;
  PhantomTemplate t=PhantomData.getInstance().getTemplate(p.getName());
  return personal(t)&&PhantomCompanionManager.getInstance().isCompanion(t);
 }
 static boolean allowedItem(int id){return id>0&&id!=QUICK_HP&&id!=57&&id!=4037&&id!=5575;}
 static Map<Integer,Long> stockFor(Collection<Skill> known,Collection<Integer> selected,Skill summon,int shot,int shotCount){
  Map<Integer,Skill> skills=new HashMap<>();for(Skill skill:known)skills.put(skill.getId(),skill);
  Map<Integer,Long> result=new LinkedHashMap<>();result.put(HP,(long)Settings.POTIONS);result.put(MP,(long)Settings.POTIONS);
  if(shot>0&&allowedItem(shot))result.put(shot,(long)Math.max(0,shotCount));
  for(int id:new int[]{1401,1402,1323})if(skills.containsKey(id))result.merge(3031,(long)Settings.ORE,Math::max);
  if(skills.containsKey(1409))result.merge(8874,(long)Settings.WATER,Math::max);
  List<Skill> costs=new ArrayList<>();for(int id:selected){Skill skill=skills.get(id);if(skill!=null)costs.add(skill);}
  if(summon!=null)costs.add(summon);
  for(Skill skill:costs)if(allowedItem(skill.getItemConsumeId())&&skill.getItemConsumeCount()>0)result.merge(skill.getItemConsumeId(),Math.min(2000L,(long)skill.getItemConsumeCount()*40),Math::max);
  result.entrySet().removeIf(e->e.getValue()<=0||!allowedItem(e.getKey()));return result;
 }
 static void configureCombat(Player p,PhantomTemplate t){
  var use=p.getAutoUseSettings();use.getAutoBuffs().clear();use.getAutoSupplyItems().remove(QUICK_HP);use.setAutoPotionItem(HP);p.getAutoPlaySettings().setAutoPotionPercent(50);
  if(t.getRole()==PhantomTemplate.Role.HEALER||t.getRole()==PhantomTemplate.Role.BUFFER){use.getAutoSkills().clear();use.getAutoActions().clear();use.resetSkillOrder();}
  else PhantomHunterOutfit.setupCompanionCombat(p);
 }
 /** Called on native build/refresh, after class learning and equipment. Hunters/reaper are untouched. */
 public static void configured(Player p,PhantomTemplate t){
  if(!personal(t))return;PREPARING.add(p.getObjectId());PartyBuffs.request(p);configureCombat(p,t);refill(p,t);NEXT_STOCK.remove(p.getObjectId());CompanionSelfSkills.clear(p);
 }
 static void refill(Player p,PhantomTemplate t){
  var gear=t.getGear(p.getLevel());int shot=gear==null?0:gear.shots;
  for(var e:stockFor(p.getAllSkills(),p.getAutoUseSettings().getAutoSkills(),PhantomSummoner.summonSkill(p),shot,PhantomsConfig.PHANTOM_SHOT_COUNT).entrySet()){
   long have=p.getInventory().getInventoryItemCount(e.getKey(),-1),missing=e.getValue()-have;
   if(missing>0)p.getInventory().addItem(ItemProcessType.REWARD,e.getKey(),missing,p,null);
  }
  p.getAutoUseSettings().getAutoSupplyItems().remove(QUICK_HP);p.getAutoUseSettings().setAutoPotionItem(HP);
  if(shot>0){p.addAutoSoulShot(shot);PhantomCompat.rechargeShots(p);}
 }
 public static void tick(Player p,long now){
  if(!owned(p)||p.isDead()||p.isTeleporting()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel())return;
  PhantomTemplate t=PhantomData.getInstance().getTemplate(p.getName());
  if(now>=NEXT_STOCK.getOrDefault(p.getObjectId(),0L)||(Settings.ORE>=5&&p.getKnownSkill(1323)!=null&&p.getInventory().getInventoryItemCount(3031,-1)<5)){
   NEXT_STOCK.put(p.getObjectId(),now+60000);refill(p,t);
  }
  p.getAutoUseSettings().getAutoSupplyItems().remove(QUICK_HP);p.getAutoUseSettings().setAutoPotionItem(HP);
  SupportSupplies.useMana(p);
 }
 public static String prepareNow(Player owner,PhantomTemplate t){
  var companions=PhantomCompanionManager.getInstance();if(!personal(t)||!companions.isOwner(owner,t)||!companions.isPartiedWith(owner,t))return "Это не твой призванный помощник в пати.";
  Player p=PhantomManager.getInstance().getPhantom(t);if(p==null||p.isDead()||p.isInCombat()||p.isCastingNow()||p.isTeleporting()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel())return "Подготовиться можно вне боя, каста и телепорта.";
  PartyBuffs.request(p);PartyBuffs.prepare(p,System.currentTimeMillis());CompanionSelfSkills.clear(p);return null;
 }
}
