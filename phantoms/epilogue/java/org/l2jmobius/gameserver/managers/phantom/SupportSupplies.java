package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Small support fixes. Uses real inventory, money, item handlers and class skills. */
public final class SupportSupplies {
  private static final Logger LOG=Logger.getLogger(SupportSupplies.class.getName());
  private static final Map<Integer,Long> NEXT_MANA_ATTEMPT=new ConcurrentHashMap<>();
  private static final int[] ORE_SKILLS={1401,1402,1323};
  private static final class Settings {
    static final ConfigReader C=new ConfigReader("config/Custom/HunterSupport.ini");
    static final boolean MANA=C.getBoolean("ManaPotionsEnabled",true);
    static final int COUNT=Math.max(0,Math.min(100,C.getInt("ManaPotionCount",10)));
    static final int PERCENT=Math.max(1,Math.min(70,C.getInt("ManaPotionPercent",70)));
    static final int ORE=Math.max(0,Math.min(1000,C.getInt("SpiritOreCount",100)));
    static final int WATER=Math.max(0,Math.min(100,C.getInt("HolyWaterCount",10)));
    static final int OFFENSIVE=Math.max(0,Math.min(200,C.getInt("OffensiveReagentCasts",40)));
    static final boolean CARE=C.getBoolean("HealBeforeRest",true),CURES=C.getBoolean("CureDebuffs",true);
  }
  public static boolean healBeforeRest(){return Settings.CARE;}
  public static boolean hasReagents(Player p,Skill s){
    return s.getItemConsumeId()<=0||s.getItemConsumeCount()<=0||
      p.getInventory().getInventoryItemCount(s.getItemConsumeId(),-1)>=s.getItemConsumeCount();
  }
  static long quantity(long target,long held,long price,long money,long reserve){
    if(target<=held||price<=0||money<=reserve)return 0;
    return Math.max(0,Math.min(target-held,(money-reserve)/price));
  }
  private static boolean hunter(Player p){
    if(p==null||!PhantomManager.getInstance().isPhantom(p))return false;
    var t=PhantomData.getInstance().getTemplate(p.getName());
    return t!=null&&t.isHunter()&&!t.isReaper();
  }
  private static boolean needsOre(Player p){if(PhantomHuntersConfig.HUNTER_OLYMPIAD_PREPARATION&&p.getKnownSkill(625)!=null)return true;for(int id:ORE_SKILLS)if(p.getKnownSkill(id)!=null)return true;return false;}
  public static boolean protect(Player p,int id){
    if(id==728||(id==3031&&needsOre(p))||(id==8874&&p.getKnownSkill(1409)!=null))return true;
    for(int skillId:p.getAutoUseSettings().getAutoSkills()){Skill skill=p.getKnownSkill(skillId);if(skill!=null&&skill.getItemConsumeId()==id&&skill.getItemConsumeCount()>0)return true;}
    return false;
  }
  public static void restock(Player p){
    if(!hunter(p)||p.isDead()||p.isInCombat()||p.isTeleporting()||p.isInOlympiadMode())return;
    try{
      long reserve=Math.max(0,PhantomHunterOutfit.professionDue(p))+Math.max(0,PhantomHuntersConfig.HUNTER_TELEPORT_FEE);
      if(needsOre(p))buy(p,3031,Settings.ORE,reserve);
      if(p.getKnownSkill(1409)!=null)buy(p,8874,Settings.WATER,reserve);
      if(Settings.MANA&&p.getLevel()>=20)buy(p,728,Settings.COUNT,reserve);
      Map<Integer,Long> ingredients=new HashMap<>();
      for(int skillId:p.getAutoUseSettings().getAutoSkills()){
        Skill skill=p.getKnownSkill(skillId);
        if(skill!=null&&skill.getItemConsumeId()>0&&skill.getItemConsumeId()!=57&&skill.getItemConsumeCount()>0)
          ingredients.merge(skill.getItemConsumeId(),Math.min(2000L,(long)Settings.OFFENSIVE*skill.getItemConsumeCount()),Math::max);
      }
      for(var entry:ingredients.entrySet())buy(p,entry.getKey(),entry.getValue(),reserve);
    }catch(RuntimeException e){LOG.warning("Support restock failed for "+p.getName()+": "+e.getClass().getSimpleName());}
  }
  private static void buy(Player p,int id,long target,long reserve){
    var definition=ItemData.getInstance().getTemplate(id);
    if(definition==null)return;
    long unit=HunterShopPrices.buyPrice(definition);if(unit<=0)return;
    synchronized(p.getInventory()){
      long held=p.getInventory().getInventoryItemCount(id,-1);
      long count=quantity(target,held,unit,p.getAdena(),reserve);
      if(count<=0)return;
      long cost=Math.multiplyExact(count,(long)unit);
      if(!p.reduceAdena(ItemProcessType.BUY,cost,p,false))return;
      Item added=p.getInventory().addItem(ItemProcessType.BUY,id,count,p,null);
      if(added==null){p.addAdena(ItemProcessType.BUY,cost,p,false);return;}
      LOG.info("Support supplies: "+p.getName()+" bought "+id+" x"+count+" for "+cost+" adena.");
    }
  }
  public static void useMana(Player p){
    if(!Settings.MANA||(!hunter(p)&&!PersonalCompanions.owned(p))||p.isAlikeDead()||p.isTeleporting()||p.isInOlympiadMode()
      ||p.isInsideZone(ZoneId.PEACE)||p.isInStoreMode()||p.isMounted()||(p.isTransformed()&&p.getTransformation().isRiding())||p.isStunned()||p.isSleeping()||p.isParalyzed()||p.isControlBlocked())return;
    if(p.getCurrentMp()*100/Math.max(1,p.getMaxMp())>Settings.PERCENT)return;
    long now=System.currentTimeMillis();
    int actor=p.getObjectId();long next=now+1000;
    Long previous=NEXT_MANA_ATTEMPT.putIfAbsent(actor,next);
    if(previous!=null&&(now<previous||!NEXT_MANA_ATTEMPT.replace(actor,previous,next)))return;
    Item item=p.getInventory().getItemByItemId(728);
    if(item==null||item.getCount()<1)return;
    try{
      int reuse=item.getReuseDelay();
      if(reuse>0&&p.getItemRemainingReuseTime(item.getObjectId())>0)return;
      var handler=ItemHandler.getInstance().getHandler(item.getEtcItem());
      if(handler!=null&&handler.onItemUse(p,item,false)){
        if(reuse>0)p.addTimeStampItem(item,reuse);
        LOG.fine("Support mana potion used by "+p.getName());
      }
    }catch(RuntimeException e){LOG.warning("Support mana potion failed for "+p.getName()+": "+e.getClass().getSimpleName());}
  }
  public static boolean purifyCures(int skillLevel,String abnormal,int level){
    int cap=skillLevel<=1?3:skillLevel==2?7:9;
    return switch(abnormal){
      case "POISON","BLEEDING" -> level<=cap;
      case "PARALYZE" -> level<=1;
      case "TURN_STONE" -> skillLevel>=2&&level<=2;
      default -> false;
    };
  }
  private static boolean hard(String type){
    return type.contains("PARAL")||type.contains("STONE")||type.contains("STUN")||type.contains("SLEEP")
      ||type.contains("MUTE")||type.contains("SILENCE")||type.contains("ROOT")||type.contains("FEAR");
  }
  public static int curePriority(Player p){
    int result=0;
    for(BuffInfo effect:p.getEffectList().getEffects()){
      Skill s=effect.getSkill();
      if(s.isDebuff()&&s.canBeDispeled())result=Math.max(result,hard(String.valueOf(s.getAbnormalType()))?2:1);
    }
    return result;
  }
  public static Skill cureSkill(Player caster,Player target,boolean hardOnly){
    if(!Settings.CURES||target.isDead())return null;
    Skill purify=PhantomSupport.usable(caster,1018),cleanse=PhantomSupport.usable(caster,1409);
    for(BuffInfo effect:target.getEffectList().getEffects()){
      Skill bad=effect.getSkill();String type=String.valueOf(bad.getAbnormalType());
      if(!bad.isDebuff()||!bad.canBeDispeled()||(hardOnly&&!hard(type)))continue;
      if(purify!=null&&purifyCures(purify.getLevel(),type,bad.getAbnormalLevel()))return purify;
      if(cleanse!=null)return cleanse;
    }
    return null;
  }
}

