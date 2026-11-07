package org.l2jmobius.gameserver.managers.phantom;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Selectable personal profiles; multiple classes can form a normal nine-member party. */
public final class PersonalBattleCatalog {
 public record Profile(String key,String family,String label,int classId){public String name(){return PersonalCompanionNames.name(key);}}
 static final List<Profile> PROFILES=List.of(new Profile("gladiator","warrior","Гладиатор",88),new Profile("destroyer","warrior","Дестро",113),new Profile("tyrant","warrior","Тирант",114),new Profile("warlord","warrior","Варлорд",89),new Profile("spellhowler","mage","СХ",110),new Profile("spellsinger","mage","СС",103),new Profile("sorcerer","mage","Сорсерер",94),new Profile("necromancer","mage","Некромант",95),new Profile("healer","mage","Биш",97));
 private static final Map<Integer,String> VIEWS=new ConcurrentHashMap<>();
 private static final Map<Integer,Integer> PAGES=new ConcurrentHashMap<>();
 static final int PAGE_SIZE=2;
 private PersonalBattleCatalog(){}
 static Profile profile(PhantomTemplate t){return t==null?null:PROFILES.stream().filter(x->x.key().equals(PersonalCompanionPool.baseKey(t))).findFirst().orElse(null);}
 static boolean visible(Player viewer,PhantomTemplate t){return (!PersonalCompanionPool.dynamic(t)&&(profile(t)==null||t.getRole()==PhantomTemplate.Role.HEALER||t.getRole()==PhantomTemplate.Role.BUFFER))||PersonalCompanionPool.ownerId(t)==viewer.getObjectId()||PhantomCompanionManager.getInstance().isOwner(viewer,t)||PersonalCompanionClans.sameClan(viewer,t);}
 static String caption(PhantomTemplate t){Profile p=profile(t);return p==null?null:(t.getRole()==PhantomTemplate.Role.HEALER?"хилер — ":p.family().equals("mage")?"маг — ":"воин — ")+p.label();}
 static String view(Player p){return VIEWS.get(p.getObjectId());}
 static String open(Player p,String family){if(!Set.of("warrior","mage").contains(family))return "Нет такой группы классов.";VIEWS.put(p.getObjectId(),family);return null;}
 static void back(Player p){VIEWS.remove(p.getObjectId());}
 static String button(String label,String bypass,String action,String key,int width){return "<button value=\""+label+"\" action=\""+bypass+" "+action+" "+key+"\" width="+width+" height=21 back=\""+PhantomCompat.BUTTON_BACK+"\" fore=\""+PhantomCompat.BUTTON_FORE+"\">";}
 static String chooser(Player p,String bypass,String family){
  PhantomData.getInstance();
  StringBuilder h=new StringBuilder("<font color=LEVEL>Выбери класс "+(family.equals("mage")?"мага":"воина")+"</font><br><br>");
  for(Profile x:PROFILES)if(x.family().equals(family))h.append(button(x.label(),bypass,"add",x.key(),180)).append("<br>");
  return h.append("<font color=808080>Уровень и экипировка — под твой уровень.<br>Каждое нажатие добавляет ещё одного помощника.<br>Можно 8 одного класса; всего в пати 9 мест.<br>Расходники входят в комплект. Quick HP нет.</font><br><br>").append(button("Назад",bypass,"back","menu",110)).toString();
 }
 static String menu(String bypass){return "<font color=LEVEL>Боевые помощники</font><br>"+button("Выбрать воина",bypass,"classes","warrior",180)+"<br>"+button("Выбрать мага",bypass,"classes","mage",180)+"<br><br>";}
 static String setPage(Player p,String key){
  try { int page=Integer.parseInt(key);if(page<0||page>100)return "Нет такой страницы.";PAGES.put(p.getObjectId(),page);back(p);return null; }
  catch(RuntimeException e){return "Нет такой страницы.";}
 }
 static int page(Player p,int count){int last=Math.max(0,(count-1)/PAGE_SIZE);int page=Math.min(PAGES.getOrDefault(p.getObjectId(),0),last);PAGES.put(p.getObjectId(),page);return page;}
 static String pages(Player p,String bypass,int count){
  int current=page(p,count),total=Math.max(1,(count+PAGE_SIZE-1)/PAGE_SIZE);
  if(total==1)return "";
  StringBuilder h=new StringBuilder("<table width=280><tr>");
  h.append("<td>").append(current>0?button("Назад",bypass,"page",Integer.toString(current-1),85):"").append("</td>");
  h.append("<td>").append(current+1).append(" / ").append(total).append("</td><td>");
  if(current+1<total)h.append(button("Дальше",bypass,"page",Integer.toString(current+1),85));
  return h.append("</td></tr></table><br>").toString();
 }
 static String add(Player owner,String key){
  var companions=PhantomCompanionManager.getInstance();
  synchronized(companions){
   if(owner.isInParty()){
    if(!owner.getParty().isLeader(owner))return "Призвать помощника в пати может её лидер.";
    if(owner.getParty().getMemberCount()>=9)return "В пати нет места для помощника.";
   }
   if(PROFILES.stream().noneMatch(p->p.key().equals(key)))return "Нет такого класса помощника.";
   if(!org.l2jmobius.gameserver.config.custom.PhantomsConfig.PHANTOMS_ENABLED)return "Напарники выключены.";
   if(owner.isDead()||owner.isInOlympiadMode()||owner.isJailed()||owner.getInstanceId()!=0)return "Сейчас позвать помощника нельзя.";
   try{return summon(owner,PersonalCompanionPool.acquire(owner,key));}
   catch(java.io.IOException|IllegalArgumentException e){java.util.logging.Logger.getLogger(PersonalBattleCatalog.class.getName()).log(java.util.logging.Level.WARNING,"Personal summon failed",e);return "Не удалось призвать помощника; причина в журнале сервера.";}
  }
 }
 static String summon(Player owner,PhantomTemplate t){
  String error=PersonalCompanionClans.access(owner,t);if(error!=null)return error;
  var companions=PhantomCompanionManager.getInstance();
  if(PersonalCompanions.personal(t)&&owner.isInParty()&&!companions.isPartiedWith(owner,t)){
   if(!owner.getParty().isLeader(owner))return "Призвать помощника в пати может её лидер.";
   if(owner.getParty().getMemberCount()>=9)return "В пати нет места для помощника.";
  }
  error=companions.summon(owner,t);if(error!=null)return error;
  if(!companions.isPartiedWith(owner,t)){error=companions.invite(owner,t);if(error!=null)return error;}
  String saved=PersonalSavedParty.afterSummon(owner,t);if(saved!=null)return saved;back(owner);var roster=PhantomData.getInstance().getCompanions().stream().filter(x->visible(owner,x)).toList();int index=roster.indexOf(t);if(index>=0)PAGES.put(owner.getObjectId(),index/PAGE_SIZE);return null;
 }
}
