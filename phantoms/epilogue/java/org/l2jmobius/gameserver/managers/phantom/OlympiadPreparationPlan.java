package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Field;
import java.util.*;
import org.l2jmobius.gameserver.data.xml.CategoryData;
import org.l2jmobius.gameserver.data.enums.CategoryType;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.skill.*;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillHolder;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import static org.l2jmobius.gameserver.managers.phantom.OlympiadPreparationEngine.*;

/** Plans only learned, native-legal preparation; no world buffer presets or free effects. */
public final class OlympiadPreparationPlan {
  public enum Profile {MAGE,HEALER,SUMMONER,BUFFER,TANK,ARCHER,FIGHTER,HYBRID}
  private static final Set<TargetType> SELF_TARGETS=EnumSet.of(TargetType.SELF,TargetType.ONE,TargetType.PARTY,TargetType.PARTY_CLAN,TargetType.PARTY_MEMBER,TargetType.CLAN_MEMBER);
  private static final Set<TargetType> PET_TARGETS=EnumSet.of(TargetType.PET,TargetType.SERVITOR,TargetType.SUMMON);
  private static final Set<String> EXCLUDED=Set.of("Transformation","Teleport","TeleportToPlayer","Escape","InstantJump","Fishing","DispelAll","DispelByCategory","DispelBySlot","CallPc","SummonPc","SilentMove");
  private OlympiadPreparationPlan(){}
  private static final class NativeBuffs {
    static final SkillHolder[] VALUES=read();
    static SkillHolder[] read(){
      try{Field f=OlympiadManager.class.getDeclaredField("ALLOWED_BUFFS");f.setAccessible(true);return ((SkillHolder[])f.get(null)).clone();}
      catch(ReflectiveOperationException error){throw new IllegalStateException("Olympiad NPC buff contract changed",error);}
    }
  }
  public static Skill npcSkill(int index){return index>=0&&index<NativeBuffs.VALUES.length?NativeBuffs.VALUES[index].getSkill():null;}
  public static int npcChoices(){return NativeBuffs.VALUES.length;}
  public static Profile profile(int classId,boolean mage){
    var categories=CategoryData.getInstance();
    if(classId==128||classId==129||classId==132||classId==133)return Profile.HYBRID;
    if(categories.isInCategory(CategoryType.SUMMONER_CATEGORY,classId))return Profile.SUMMONER;
    if(categories.isInCategory(CategoryType.HEALER_CATEGORY,classId))return Profile.HEALER;
    if(categories.isInCategory(CategoryType.SUBCLASS_ENCHANTER,classId))return Profile.BUFFER;
    if(categories.isInCategory(CategoryType.TANKER_CATEGORY,classId))return Profile.TANK;
    if(categories.isInCategory(CategoryType.ARCHER_CATEGORY,classId))return Profile.ARCHER;
    return mage?Profile.MAGE:Profile.FIGHTER;
  }
  public static List<Integer> npcOrder(Profile p){
    return switch(p){
      case MAGE->List.of(3,2,1,4,9,0,5,7,8,6);
      case HEALER->List.of(3,1,4,2,0,5,7,8,6,9);
      case SUMMONER->List.of(3,2,1,4,0,5,7,8,6,9);
      case BUFFER,HYBRID->List.of(3,1,0,2,5,7,8,4,6,9);
      case TANK->List.of(0,5,1,7,8,6,3,4,2,9);
      case ARCHER->List.of(0,5,7,8,1,6,9,3,4,2);
      default->List.of(0,5,7,8,1,6,9,3,4,2);
    };
  }
  static boolean effect(Skill s,String name){return s.getEffects(EffectScope.GENERAL).stream().anyMatch(e->e.getClass().getSimpleName().equals(name));}
  public static boolean ordinaryBuff(Skill s){
    if(s==null||s.isPassive()||s.isDebuff()||s.hasNegativeEffect()||s.isGMSkill()||s.isHeroSkill()||s.isBlockedInOlympiad()||s.getHpConsume()>0)return false;
    if(!SELF_TARGETS.contains(s.getTargetType())&&!PET_TARGETS.contains(s.getTargetType()))return false;
    if(!s.isContinuous()&&!s.isToggle())return false;
    if(!s.isToggle()&&(s.getAbnormalTime()<30||(s.getAbnormalTime()<90&&s.getReuseDelay()>120000)))return false;
    return s.getEffects(EffectScope.GENERAL).stream().noneMatch(e->e.getEffectType()==org.l2jmobius.gameserver.mechanics.effects.EffectType.RELAXING||e.getEffectType()==org.l2jmobius.gameserver.mechanics.effects.EffectType.FAKE_DEATH||EXCLUDED.contains(e.getClass().getSimpleName())||e.getClass().getSimpleName().contains("Damage"));
  }
  private static int score(Skill s){
    int preference=s.getId()==109?3000:s.getId()==355?2500:s.getId()==357?2400:s.getId()==356?2300:1000;
    return preference+Math.min(500,s.getAbnormalLevel()*10)+Math.min(100,s.getLevel());
  }
  static int cubicId(Skill s){
    for(var e:s.getEffects(EffectScope.GENERAL))if(e.getClass().getSimpleName().equals("SummonCubic")){
      try{Field f=e.getClass().getDeclaredField("_cubicId");f.setAccessible(true);return f.getInt(e);}
      catch(ReflectiveOperationException error){throw new IllegalStateException("SummonCubic contract changed",error);}
    }
    return -1;
  }
  public static List<Action> actions(Player p){
    List<Action> result=new ArrayList<>();
    Skill summon=PhantomSummoner.summonSkill(p);
    if(summon!=null&&!summon.isBlockedInOlympiad())result.add(new Action("summon:"+summon.getId(),summon.getId(),Kind.SUMMON,0,0,summon.getHitTime()));
    List<Skill> learned=new ArrayList<>(p.getAllSkills());
    learned.sort(Comparator.comparingInt(OlympiadPreparationPlan::score).reversed().thenComparingInt(Skill::getId));
    Set<String> groups=new HashSet<>();
    for(Skill s:learned){
      if(s.isPassive()||s.isDebuff()||s.hasNegativeEffect()||s.isGMSkill()||s.isHeroSkill()||s.isBlockedInOlympiad())continue;
      int cubic=cubicId(s);
      if(cubic>=0){result.add(new Action("cubic:"+cubic,s.getId(),Kind.CUBIC,cubic,20000,s.getHitTime()));continue;}
      if(!ordinaryBuff(s))continue;
      boolean pet=PET_TARGETS.contains(s.getTargetType());
      if(pet&&!p.hasServitor())continue;
      var target=pet?p.getSummon():p;
      if(!s.checkCondition(p,target,false))continue;
      String group=(pet?"pet:":"self:")+(s.getAbnormalType()==AbnormalType.NONE?"skill:"+s.getId():s.getAbnormalType().name());
      if(!groups.add(group))continue;
      Kind kind=s.isToggle()?Kind.TOGGLE:pet?Kind.BUFF_PET:Kind.BUFF_SELF;
      long late=s.isToggle()?45000:s.getAbnormalTime()<90?40000:0;
      result.add(new Action(group,s.getId(),kind,0,late,s.getHitTime()));
    }
    int desired=0;
    for(int id:p.getAutoUseSettings().getAutoSkills()){Skill s=p.getKnownSkill(id);if(s!=null)desired=Math.max(desired,s.getChargeConsumeCount());}
    Skill focus=p.getKnownSkill(8);if(focus==null)focus=p.getKnownSkill(50);
    if(focus!=null){desired=desired>0?Math.min(8,focus.getLevel()):0;if(p.getCharges()<desired)result.add(new Action("charge:"+p.getCharges(),focus.getId(),Kind.CHARGE,p.getCharges()+1,0,focus.getHitTime()));}
    Skill souls=p.getKnownSkill(625);
    int soulCap=(int)p.calcStat(org.l2jmobius.gameserver.mechanics.stats.Stat.MAX_SOULS,0,null,null);
    if(souls!=null&&soulCap>0&&p.getChargedSouls()<Math.min(40,soulCap))result.add(new Action("souls:"+p.getChargedSouls(),625,Kind.SOUL,soulCap,0,souls.getHitTime()));
    return result;
  }
}
