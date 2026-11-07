package local.l2;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.network.serverpackets.ExStorageMaxCount;

/** Permanent per-character expansion. Does not double-stack the native fishing skill 1372. */
public final class PaidInventory {
  public static final String KEY="L2Local.InventoryMax";
  public static final int BONUS=48;
  private static final Logger LOG=Logger.getLogger(PaidInventory.class.getName());
  private static final Map<Integer,Object> LOCKS=new ConcurrentHashMap<>();
  public enum Result {CHANGED,ALREADY_OWNED,ALREADY_MAX,NO_FUNDS,SAVE_FAILED}
  interface Account {
    boolean owned();void store(boolean value);boolean save();int fishingLevel();
    boolean debit(long amount);void refund(long amount);
  }
  private PaidInventory(){}
  static int extra(int fishingLevel){return Math.max(0,BONUS-Math.min(BONUS,Math.max(0,fishingLevel)*6));}
  static int adjusted(int nativeLimit,boolean owned,int fishingLevel){return owned?Math.min(65535,nativeLimit+extra(fishingLevel)):nativeLimit;}
  public static int limit(int nativeLimit,Player p){
    // Capacity is also queried while Player is still loading: do not cause an early variables/SQL load.
    if(p==null||!p.hasVariables()||!p.getVariables().getBoolean(KEY,false))return nativeLimit;
    var learned=p.getKnownSkill(1372);
    return adjusted(nativeLimit,true,learned==null?0:learned.getLevel());
  }
  static Result purchase(Account a,long price){
    if(price<0)throw new IllegalArgumentException("Invalid price");
    if(a.owned())return Result.ALREADY_OWNED;
    if(extra(a.fishingLevel())==0)return Result.ALREADY_MAX;
    if(price>0&&!a.debit(price))return Result.NO_FUNDS;
    try{a.store(true);if(!a.save())throw new IllegalStateException("Inventory save failed");return Result.CHANGED;}
    catch(RuntimeException error){
      try{a.store(false);a.save();}catch(RuntimeException rollback){LOG.warning("Inventory rollback failed: "+rollback.getClass().getSimpleName());}
      if(price>0)a.refund(price);return Result.SAVE_FAILED;
    }
  }
  static Account account(Player p){return new Account(){
    public boolean owned(){return p.getVariables().getBoolean(KEY,false);}
    public void store(boolean value){if(value)p.getVariables().set(KEY,true);else p.getVariables().remove(KEY);}
    public boolean save(){var vars=p.getVariables();return !vars.hasChanges()||vars.saveNow();}
    public int fishingLevel(){var skill=p.getKnownSkill(1372);return skill==null?0:skill.getLevel();}
    public boolean debit(long amount){return p.reduceAdena(ItemProcessType.BUY,amount,p,true);}
    public void refund(long amount){p.addAdena(ItemProcessType.BUY,amount,p,true);}
  };}
  public static void refresh(Player p){if(p!=null&&p.hasVariables()&&p.getVariables().getBoolean(KEY,false))p.sendPacket(new ExStorageMaxCount(p));}
  public static String board(Player p,String[] args){
    ConfigReader config=new ConfigReader("config/Custom/AppearanceServices.ini");
    long price=Math.max(0,config.getLong("InventoryExpansionPrice",50000000));
    boolean enabled=config.getBoolean("InventoryExpansionEnabled",true);
    String note="";
    if(args.length>1){
      if(args.length!=2||!args[1].equalsIgnoreCase("buy"))note="Неверная команда.";
      else if(!enabled)note="Покупка расширения выключена.";
      else if(p.isDead()||p.isInCombat()||p.isInOlympiadMode()||p.isOnEvent()||p.isInDuel()||p.isInStoreMode()||p.isTeleporting())note="Сейчас расширение недоступно.";
      else synchronized(LOCKS.computeIfAbsent(p.getObjectId(),id->new Object())){
        Result result=purchase(account(p),price);
        note=switch(result){
          case CHANGED->"Максимальное расширение сохранено.";
          case ALREADY_OWNED->"Расширение уже оплачено. Повторная оплата не нужна.";
          case ALREADY_MAX->"Штатное расширение уже даёт максимальный объём.";
          case NO_FUNDS->"Недостаточно адены.";
          case SAVE_FAILED->"Не удалось сохранить расширение. Оплата возвращена.";
        };
        if(result==Result.CHANGED)try{refresh(p);}catch(RuntimeException error){note="Расширение сохранено. Обновление окна произойдёт после входа в игру.";}
      }
    }
    Account a=account(p);int current=p.getInventoryLimit();int target=a.owned()?current:current+extra(a.fishingLevel());
    StringBuilder h=new StringBuilder("<br><font color=LEVEL>Расширение инвентаря</font><br><br>");
    if(!note.isBlank())h.append("<font color=LEVEL>").append(PaidColors.esc(note)).append("</font><br><br>");
    h.append("Сейчас: ").append(current).append(" слотов.<br>После расширения: ").append(target).append(" слотов.<br><br>");
    h.append("Максимальная прибавка: +48 слотов.<br>Уже изученное Expand Inventory учтено.<br>Доступно на любом уровне. Сохраняется после выхода<br>и смены подкласса.<br><br>");
    if(a.owned()||extra(a.fishingLevel())==0)h.append("<font color=LEVEL>Максимальный объём уже доступен.</font><br>");
    else if(enabled)h.append("Разовая оплата: ").append(PaidColors.money(price)).append(" адены.<br><br>").append(PaidColors.button("Расширить за "+PaidColors.money(price),"_bbshubinventory buy",260));
    else h.append("Покупка расширения выключена.");
    return h.toString();
  }
}
