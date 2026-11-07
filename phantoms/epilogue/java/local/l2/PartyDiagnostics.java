package local.l2;

import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomTemplate;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/** Observation only: never casts, moves, stands up, changes target, or consumes items. */
public final class PartyDiagnostics {
  private static final Logger LOG=Logger.getLogger("L2PartyDiagnostics");
  private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>();
  private static final Map<Integer,Emission> EMITTED=new ConcurrentHashMap<>();
  private static volatile Settings configuration;
  private static volatile long lastError;
  static final int[] CARE_SKILLS={1401,1217,1011,1218,1015,1402,1219,1027,1271,1016,1013,1018,1409};
  record Settings(boolean enabled,Set<String> hunters,long interval,long minimumGap,boolean changes){}
  record Emission(long at,String signature){}
  private static final class Frame {
    final Player actor,owner; final String role; final long now;
    final Map<String,Object> before;
    final Map<String,Boolean> branches=new LinkedHashMap<>();
    final Map<Integer,String> candidates=new LinkedHashMap<>();
    final List<Map<String,Object>> casts=new ArrayList<>();
    Frame(Player actor,Player owner,String role,long now){
      this.actor=actor;this.owner=owner;this.role=role;this.now=now;before=snapshot(actor,owner,now);
    }
  }
  static Settings settings(){
    Settings value=configuration;
    if(value==null)synchronized(PartyDiagnostics.class){
      value=configuration;
      if(value==null){
        ConfigReader c=new ConfigReader("config/Custom/PartyDiagnostics.ini");
        Set<String> names=new HashSet<>();
        for(String name:c.getString("Hunters","Igumen").split("[,;]"))
          if(!name.isBlank())names.add(name.trim().toLowerCase(Locale.ROOT));
        configuration=value=new Settings(c.getBoolean("Enabled",false),Set.copyOf(names),
          Math.max(1,Math.min(300,c.getInt("LogEverySeconds",5)))*1000L,
          Math.max(100,Math.min(60000,c.getInt("MinGapMilliseconds",1000))),c.getBoolean("ReportChanges",true));
      }
    }
    return value;
  }
  static boolean selected(Settings s,String hunter,boolean realOwner){
    return s.enabled()&&realOwner&&(s.hunters().isEmpty()||s.hunters().contains(hunter.toLowerCase(Locale.ROOT)));
  }
  static boolean due(Settings s,Emission previous,long now,String signature){
    return previous==null||now-previous.at()>=s.interval()||
      (s.changes()&&now-previous.at()>=s.minimumGap()&&!previous.signature().equals(signature));
  }
  public static void begin(Player actor,Player owner,PhantomTemplate.Role role,long now){
    CURRENT.remove();
    try{
      Settings s=settings();
      if(!s.enabled()||actor==null||owner==null||
        !selected(s,actor.getName(),!PhantomManager.getInstance().isPhantom(owner)))return;
      CURRENT.set(new Frame(actor,owner,String.valueOf(role),now));
    }catch(RuntimeException e){error(e);}
  }
  public static void branch(boolean result,String name){
    Frame f=CURRENT.get();if(f!=null)f.branches.put(name,result);
  }
  static String availability(boolean known,boolean disabled,double mp,double cost){
    return !known?"missing_skill":disabled?"cooldown":mp<cost?"not_enough_mp":"available";
  }
  public static void candidate(Skill result,Player actor,int id){
    Frame f=CURRENT.get();if(f==null||f.actor!=actor)return;
    try{
      Skill known=actor.getKnownSkill(id);
      String why=availability(known!=null,known!=null&&actor.isSkillDisabled(known),actor.getCurrentMp(),
        known==null?0:known.getMpConsume()+known.getMpInitialConsume());
      if(why.equals("available")&&known!=null&&!org.l2jmobius.gameserver.managers.phantom.SupportSupplies.hasReagents(actor,known))why="missing_reagent";
      f.candidates.put(id,result!=null?"available":why.equals("available")?"state_changed_during_check":why);
    }catch(RuntimeException e){error(e);}
  }
  public static void requested(boolean accepted,Player actor,Skill skill,Creature target){
    Frame f=CURRENT.get();if(f==null||f.actor!=actor||f.casts.size()>=24)return;
    try{
      Map<String,Object> row=new LinkedHashMap<>();
      row.put("skill",skill==null?null:skill.getId());row.put("level",skill==null?null:skill.getLevel());
      row.put("target",target==null?null:target.getName());row.put("accepted",accepted);
      if(target!=null){
        row.put("distance",Math.round(actor.calculateDistance2D(target)));
        row.put("line_of_sight",GeoEngine.getInstance().canSeeTarget(actor,target));
      }
      if(skill!=null)row.put("cast_range",skill.getCastRange());
      f.casts.add(row);
    }catch(RuntimeException e){error(e);}
  }
  static String reason(boolean acted,boolean beforeSit,boolean afterSit,boolean waitingCast,
    long castSince,boolean orderedSit,Map<String,Boolean> branches,List<Map<String,Object>> casts,Map<Integer,String> candidates){
    if(!casts.isEmpty())return Boolean.TRUE.equals(casts.getLast().get("accepted"))?"cast_request_accepted":"cast_request_rejected";
    if(Boolean.TRUE.equals(branches.get("rest"))){
      if(beforeSit&&!afterSit)return "stood_up_this_tick";
      if(!beforeSit&&afterSit)return orderedSit?"sat_down_by_order":"started_mp_rest";
      if(afterSit)return orderedSit?"sitting_by_order":"waiting_for_mp_recovery";
    }
    if(waitingCast)return castSince<=0?"waiting_cast_without_support_timer":"waiting_cast";
    if(beforeSit&&!afterSit)return "stood_up_this_tick";
    if(Boolean.TRUE.equals(branches.get("orders")))return "processing_order";
    if(!candidates.isEmpty()&&candidates.values().stream().noneMatch("available"::equals))return "no_usable_skill";
    return acted?"other_support_action":"no_support_action_selected";
  }
  public static boolean end(boolean result){
    Frame f=CURRENT.get();
    try{
      if(f==null)return result;
      Map<String,Object> after=snapshot(f.actor,f.owner,f.now);
      long since=((Number)after.getOrDefault("support_cast_since",0L)).longValue();
      boolean waiting=Boolean.TRUE.equals(after.get("casting"))||"CAST".equals(after.get("intention"));
      String why=reason(result,Boolean.TRUE.equals(f.before.get("sitting")),Boolean.TRUE.equals(after.get("sitting")),
        waiting,since,Boolean.TRUE.equals(after.get("sit_ordered")),f.branches,f.casts,f.candidates);
      String signature=why+"|"+after.get("intention")+"|"+f.branches+"|"+f.candidates;
      Settings s=settings();int id=f.actor.getObjectId();
      if(!due(s,EMITTED.get(id),f.now,signature))return result;
      EMITTED.put(id,new Emission(f.now,signature));
      Map<String,Object> out=new LinkedHashMap<>();
      out.put("time",f.now);out.put("actor",f.actor.getName());out.put("owner",f.owner.getName());
      out.put("role",f.role);out.put("reason",why);out.put("act_returned",result);
      out.put("before",f.before);out.put("after",after);out.put("branches",f.branches);
      out.put("skill_candidates",f.candidates);out.put("cast_requests",f.casts);
      LOG.info("PARTY_DIAG "+json(out));
    }catch(RuntimeException e){error(e);}
    finally{CURRENT.remove();}
    return result;
  }
  private static Map<String,Object> snapshot(Player p,Player owner,long now){
    Map<String,Object> out=new LinkedHashMap<>();
    out.put("level",p.getLevel());out.put("class",p.getPlayerClass().getId());
    out.put("hp",Math.round(p.getCurrentHp()));out.put("hp_max",p.getMaxHp());
    out.put("mp",Math.round(p.getCurrentMp()));out.put("mp_max",p.getMaxMp());
    out.put("sitting",p.isSitting());out.put("casting",p.isCastingNow());
    out.put("intention",String.valueOf(p.getAI().getIntention()));out.put("instance",p.getInstanceId());
    out.put("owner_distance",Math.round(p.calculateDistance2D(owner)));
    out.put("same_party",p.isInParty()&&p.getParty()==owner.getParty());
    out.put("buffs",p.getEffectList().getBuffCount());out.put("dances",p.getEffectList().getDanceCount());
    out.put("spirit_ore_count",p.getInventory().getInventoryItemCount(3031,-1));
    out.put("holy_water_count",p.getInventory().getInventoryItemCount(8874,-1));
    out.put("mp_728_count",p.getInventory().getInventoryItemCount(728,-1));
    out.put("hp_1539_count",p.getInventory().getInventoryItemCount(1539,-1));
    out.put("auto_supplies",new ArrayList<>(p.getAutoUseSettings().getAutoSupplyItems()));
    out.put("auto_hp_item",p.getAutoUseSettings().getAutoPotionItem());
    Map<Integer,String> known=new LinkedHashMap<>();
    for(int id:CARE_SKILLS){Skill s=p.getKnownSkill(id);known.put(id,availability(s!=null,s!=null&&p.isSkillDisabled(s),p.getCurrentMp(),s==null?0:s.getMpConsume()+s.getMpInitialConsume()));}
    out.put("care_skills",known);
    List<Map<String,Object>> members=new ArrayList<>();
    if(p.isInParty())for(Player m:p.getParty().getMembers()){
      Map<String,Object> row=new LinkedHashMap<>();
      row.put("name",m.getName());row.put("hp_percent",pct(m.getCurrentHp(),m.getMaxHp()));
      row.put("mp_percent",pct(m.getCurrentMp(),m.getMaxMp()));row.put("dead",m.isDead());
      row.put("distance",Math.round(p.calculateDistance2D(m)));row.put("same_instance",p.getInstanceId()==m.getInstanceId());
      row.put("in_support_range",p.getInstanceId()==m.getInstanceId()&&p.calculateDistance2D(m)<=900);
      members.add(row);
    }
    out.put("party",members);
    try{
      Class<?> type=Class.forName("org.l2jmobius.gameserver.managers.phantom.PhantomSupport",false,p.getClass().getClassLoader());
      Field states=type.getDeclaredField("STATES");states.setAccessible(true);
      Object state=((Map<?,?>)states.get(null)).get(p.getObjectId());
      if(state!=null){
        for(String name:List.of("castSince","sitOrdered","noRestUntil","healTarget","healUntil","resTarget","resUntil","refreshUntil")){
          Field field=state.getClass().getDeclaredField(name);field.setAccessible(true);Object value=field.get(state);
          out.put(name.equals("castSince")?"support_cast_since":name.equals("sitOrdered")?"sit_ordered":name,value);
        }
        long since=((Number)out.getOrDefault("support_cast_since",0L)).longValue();
        out.put("support_cast_age_ms",since<=0?null:Math.max(0,now-since));
      }
    }catch(ReflectiveOperationException e){out.put("support_state_unavailable",e.getClass().getSimpleName());}
    return out;
  }
  private static long pct(double current,double maximum){return Math.round(current*100/Math.max(1,maximum));}
  private static void error(RuntimeException e){
    long now=System.currentTimeMillis();
    if(now-lastError>60000){lastError=now;LOG.warning("PARTY_DIAG observation unavailable: "+e.getClass().getSimpleName());}
  }
  static String json(Object value){
    if(value==null)return "null";
    if(value instanceof Boolean)return value.toString();
    if(value instanceof Number n)return Double.isFinite(n.doubleValue())?n.toString():"null";
    if(value instanceof Map<?,?> map){StringJoiner out=new StringJoiner(",","{","}");map.forEach((k,v)->out.add(json(String.valueOf(k))+":"+json(v)));return out.toString();}
    if(value instanceof Iterable<?> items){StringJoiner out=new StringJoiner(",","[","]");for(Object item:items)out.add(json(item));return out.toString();}
    StringBuilder out=new StringBuilder("\"");
    for(char c:String.valueOf(value).toCharArray()){
      switch(c){case '\\'->out.append("\\\\");case '"'->out.append("\\\"");case '\n'->out.append("\\n");case '\r'->out.append("\\r");case '\t'->out.append("\\t");default->{if(c<32)out.append(String.format("\\u%04x",(int)c));else out.append(c);}}
    }
    return out.append('"').toString();
  }
}

