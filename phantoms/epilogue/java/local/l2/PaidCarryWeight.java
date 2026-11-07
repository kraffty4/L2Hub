package local.l2;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;
/** Per-character, persistent, repeatable weight expansion; native weight rules remain active. */
public final class PaidCarryWeight {
 public static final String KEY="L2Local.CarryWeightBonusPercent";
 private static final Logger LOG=Logger.getLogger(PaidCarryWeight.class.getName());
 private static final Map<Integer,Object> LOCKS=new ConcurrentHashMap<>();
 private static final ThreadLocal<Boolean> BASE=new ThreadLocal<>();
 public enum Result {CHANGED,MAXIMUM,NO_FUNDS,SAVE_FAILED}
 interface Account {int bonus();void store(int value);boolean save();boolean debit(long amount);void refund(long amount);}
 private PaidCarryWeight(){}
 static int adjusted(int nativeLoad,int bonus){if(nativeLoad<=0||bonus<=0)return nativeLoad;return (int)Math.min(Integer.MAX_VALUE,(long)nativeLoad*(100L+bonus)/100L);}
 public static long weightPermille(int current,int allowance,int maximum){return maximum>0?(((long)current-allowance)*1000L)/maximum:0;}
 public static int limit(int nativeLoad,Creature actor){
  if(Boolean.TRUE.equals(BASE.get())||!(actor instanceof Player p)||!p.hasVariables())return nativeLoad;
  return adjusted(nativeLoad,Math.max(0,p.getVariables().getInt(KEY,0)));
 }
 static int nativeLoad(Player p){Boolean previous=BASE.get();BASE.set(true);try{return p.getMaxLoad();}finally{if(previous==null)BASE.remove();else BASE.set(previous);}}
 static Result purchase(Account a,int nativeLoad,int increment,long price){
  if(increment<=0||price<0)throw new IllegalArgumentException("Invalid weight purchase");
  int previous=Math.max(0,a.bonus());long next=(long)previous+increment;
  if(next>Integer.MAX_VALUE||nativeLoad<=0||adjusted(nativeLoad,(int)next)<=adjusted(nativeLoad,previous))return Result.MAXIMUM;
  if(price>0&&!a.debit(price))return Result.NO_FUNDS;
  try{a.store((int)next);if(!a.save())throw new IllegalStateException("Carry weight save failed");return Result.CHANGED;}
  catch(RuntimeException error){try{a.store(previous);a.save();}catch(RuntimeException rollback){LOG.warning("Carry weight rollback failed: "+rollback.getClass().getSimpleName());}if(price>0)a.refund(price);return Result.SAVE_FAILED;}
 }
 static Account account(Player p){return new Account(){
  public int bonus(){return Math.max(0,p.getVariables().getInt(KEY,0));}
  public void store(int n){if(n==0)p.getVariables().remove(KEY);else p.getVariables().set(KEY,n);}
  public boolean save(){var v=p.getVariables();return !v.hasChanges()||v.saveNow();}
  public boolean debit(long n){return p.reduceAdena(ItemProcessType.BUY,n,p,true);}
  public void refund(long n){p.addAdena(ItemProcessType.BUY,n,p,true);}
 };}
 public static String board(Player p,String[] args){
  ConfigReader c=new ConfigReader("config/Custom/AppearanceServices.ini");long price=Math.max(0,c.getLong("CarryWeightExpansionPrice",100000000));int increment=Math.max(1,c.getInt("CarryWeightExpansionBonusPercent",2000));boolean enabled=c.getBoolean("CarryWeightExpansionEnabled",true);String note="";
  if(args.length>1){
   if(args.length!=2||!args[1].equalsIgnoreCase("buy"))note="Неверная команда.";
   else if(!enabled)note="Услуга выключена.";
   else if(p.isDead()||p.isInCombat()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel()||p.isInStoreMode()||p.isTeleporting())note="Сейчас покупка недоступна.";
   else synchronized(LOCKS.computeIfAbsent(p.getObjectId(),id->new Object())){
    Result r=purchase(account(p),nativeLoad(p),increment,price);
    note=switch(r){case CHANGED->"Грузоподъёмность увеличена и сохранена.";case MAXIMUM->"Достигнут предел грузоподъёмности клиента. Оплата не списана.";case NO_FUNDS->"Недостаточно адены.";case SAVE_FAILED->"Не удалось сохранить прибавку. Оплата возвращена.";};
    if(r==Result.CHANGED){p.refreshOverloaded();StatusUpdate su=new StatusUpdate(p);su.addAttribute(StatusUpdate.CUR_LOAD,p.getCurrentLoad());su.addAttribute(StatusUpdate.MAX_LOAD,p.getMaxLoad());p.sendPacket(su);p.broadcastUserInfo();}
   }
  }
  int base=nativeLoad(p),bonus=account(p).bonus(),current=p.getMaxLoad();long next=(long)bonus+increment;int target=adjusted(base,(int)Math.min(Integer.MAX_VALUE,next));
  StringBuilder h=new StringBuilder("<br><font color=LEVEL>Грузоподъёмность</font><br><br>");if(!note.isBlank())h.append("<font color=LEVEL>").append(PaidColors.esc(note)).append("</font><br><br>");
  h.append("Текущий предел веса: ").append(PaidColors.money(current)).append(".<br>Оплаченная прибавка: +").append(PaidColors.money(bonus)).append("%.<br>Следующая покупка: ещё +").append(PaidColors.money(increment)).append("% от базового предела.<br>После покупки: ").append(PaidColors.money(target)).append(".<br><br>Можно покупать несколько раз. Прибавки складываются.<br>Сохраняется после выхода и смены подкласса.<br><br>");
  if(enabled&&next<=Integer.MAX_VALUE&&target>current)h.append(PaidColors.button("+"+PaidColors.money(increment)+"% за "+PaidColors.money(price),"_bbshubweight buy",280));else h.append(enabled?"Достигнут предел клиента.":"Услуга выключена.");return h.toString();
 }
}
