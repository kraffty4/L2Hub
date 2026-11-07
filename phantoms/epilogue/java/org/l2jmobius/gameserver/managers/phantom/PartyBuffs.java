package org.l2jmobius.gameserver.managers.phantom;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.*;

/** Same existing hunter presets while accompanied; no preparation during arena/event/combat. */
public final class PartyBuffs {
  private static final Logger LOG=Logger.getLogger(PartyBuffs.class.getName());
  private static final Map<Integer,Long> CHECKS=new ConcurrentHashMap<>(),SUPPLIES=new ConcurrentHashMap<>();
  private static final class Settings {
    static final ConfigReader C=new ConfigReader("config/Custom/HunterSupport.ini");
    static final boolean ENABLED=C.getBoolean("PreparePartyBuffs",true);
    static final long CHECK=Math.max(1,C.getInt("PartyBuffCheckSeconds",5))*1000L;
    static final long STOCK=Math.max(5,C.getInt("PartySupplyCheckSeconds",60))*1000L;
    static final int REFRESH=Math.max(0,Math.min(120,C.getInt("PartyBuffRefreshSeconds",60)));
  }
  private PartyBuffs(){}
  static boolean allowed(boolean enabled,boolean townBuffer,boolean dead,boolean combat,boolean casting,boolean olympiad,boolean event){
    return enabled&&townBuffer&&!dead&&!combat&&!casting&&!olympiad&&!event;
  }
  static boolean refreshNeeded(int wanted,int active,int seconds,int threshold){
    return active==0||(active==wanted&&seconds<=threshold);
  }
  static BuffInfo active(Player p,Skill skill){
    BuffInfo best=null;
    for(BuffInfo b:p.getEffectList().getEffects())if(b.isInUse()&&(skill.getAbnormalType()==AbnormalType.NONE?b.getSkill().getId()==skill.getId():b.getSkill().getAbnormalType()==skill.getAbnormalType()))
      if(best==null||b.getSkill().getAbnormalLevel()>best.getSkill().getAbnormalLevel())best=b;
    return best;
  }
  static boolean covered(Player p,Skill skill,int threshold){BuffInfo b=active(p,skill);return b!=null&&(b.getSkill().isToggle()||b.getTime()>threshold)&&(b.getSkill().getId()==skill.getId()||b.getSkill().getAbnormalLevel()>=skill.getAbnormalLevel());}
  static List<Skill> preset(Player p){
    String group=p.isMageClass()?"MAGE_GROUP":"FIGHTER_GROUP";var table=SchemeBufferTable.getInstance();List<Skill> result=new ArrayList<>();
    for(int id:table.getSkillsIdsByType(group)){var holder=table.getAvailableBuff(group,id);Skill skill=holder==null?null:SkillData.getInstance().getSkill(id,holder.getLevel());if(skill!=null&&PhantomPersonalNoblesse.allows(p,id))result.add(skill);}return result;
  }
  static boolean ready(Player p){if(!Settings.ENABLED||!PhantomSupport.townBuffer())return true;for(Skill s:preset(p))if(!covered(p,s,0))return false;return true;}
  static int refresh(Player player,List<Skill> skills,int threshold){int applied=0;for(Skill skill:skills)if(PhantomPersonalNoblesse.allows(player,skill.getId())&&!covered(player,skill,threshold)){skill.applyEffects(player,player);applied++;}return applied;}
  public static void request(Player p){CHECKS.remove(p.getObjectId());}
  public static void prepare(Player player,long now){
    if(player==null||!PhantomManager.getInstance().isPhantom(player))return;
    PhantomTemplate template=PhantomData.getInstance().getTemplate(player.getName());
    if(template==null||(!template.isHunter()&&!PersonalCompanions.owned(player))||template.isReaper()||player.isDead()||player.isInCombat()||player.isCastingNow()
      ||player.isTeleporting()||player.isInOlympiadMode()||player.isOnEvent()||player.isInStoreMode())return;
    if(now>=SUPPLIES.getOrDefault(player.getObjectId(),0L)){
      SUPPLIES.put(player.getObjectId(),now+Settings.STOCK);SupportSupplies.restock(player);
    }
    if(!allowed(Settings.ENABLED,PhantomSupport.townBuffer(),false,false,false,false,false)||now<CHECKS.getOrDefault(player.getObjectId(),0L))return;
    CHECKS.put(player.getObjectId(),now+Settings.CHECK);
    List<Skill> skills=preset(player);
    int applied=refresh(player,skills,Settings.REFRESH);
    if(applied>0)LOG.fine("Party preparation: "+player.getName()+" restored "+applied+" preset buffs.");
  }
}
