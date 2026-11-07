package local.l2;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;

/** Self-service cosmetics. Uses the same persisted variables as explicit admin colors. */
public final class PaidColors {
 private static final Logger LOG=Logger.getLogger(PaidColors.class.getName());
 private static final Map<Integer,Object> LOCKS=new ConcurrentHashMap<>();
 public enum Part {NAME,TITLE}
 public enum Result {CHANGED,UNCHANGED,NO_FUNDS,SAVE_FAILED}
 record Fees(boolean enabled,long name,long title){}
 interface Account {
  int visible(Part part);int stored(Part part);void store(Part part,int value);boolean save();
  boolean debit(long amount);void refund(long amount);void paint(Part part,int value);
 }
 private PaidColors(){}
 static Fees fees(){ConfigReader c=new ConfigReader("config/Custom/AppearanceServices.ini");return new Fees(c.getBoolean("Enabled",true),Math.max(0,c.getLong("NameColorPrice",20000000)),Math.max(0,c.getLong("TitleColorPrice",10000000)));}
 public static int rgb(String value){String s=value==null?"":value.trim();if(s.startsWith("#"))s=s.substring(1);if(!s.matches("[0-9a-fA-F]{6}"))throw new IllegalArgumentException("Введите шесть знаков цвета, например FF0000.");return Integer.parseInt(s,16);}
 public static int bgr(int rgb){if(!PermanentColors.valid(rgb))throw new IllegalArgumentException("Invalid color");return (rgb&0x00ff00)|((rgb&0xff)<<16)|((rgb>>>16)&0xff);}
 static Result purchase(Account account,Part part,int color,long price){
  if(!PermanentColors.valid(color)||price<0)throw new IllegalArgumentException("Invalid purchase");
  int previous=account.stored(part),visible=account.visible(part);
  if(previous==color&&visible==color)return Result.UNCHANGED;
  if(price>0&&!account.debit(price))return Result.NO_FUNDS;
  try{
   account.store(part,color);
   if(!account.save())throw new IllegalStateException("Color save failed");
   account.paint(part,color);return Result.CHANGED;
  }catch(RuntimeException failure){
   try{account.store(part,previous);account.save();account.paint(part,visible);}catch(RuntimeException rollback){LOG.warning("Color rollback failed: "+rollback.getClass().getSimpleName());}
   if(price>0)account.refund(price);
   return Result.SAVE_FAILED;
  }
 }
 static String key(Part part){return part==Part.NAME?PermanentColors.NAME:PermanentColors.TITLE;}
 static Account account(Player player){return new Account(){
  public int visible(Part p){return p==Part.NAME?player.getAppearance().getNameColor():player.getAppearance().getTitleColor();}
  public int stored(Part p){return player.getVariables().getInt(key(p),-1);}
  public void store(Part p,int value){if(PermanentColors.valid(value))player.getVariables().set(key(p),value);else player.getVariables().remove(key(p));}
  public boolean save(){var v=player.getVariables();return !v.hasChanges()||v.saveNow();}
  public boolean debit(long n){return player.reduceAdena(ItemProcessType.BUY,n,player,true);}
  public void refund(long n){player.addAdena(ItemProcessType.BUY,n,player,true);}
  public void paint(Part p,int color){if(p==Part.NAME)player.getAppearance().setNameColor(color);else player.getAppearance().setTitleColor(color);}
 };}
 static String money(long n){return String.format(Locale.ROOT,"%,d",n).replace(',',' ');}
 static String esc(String s){return (s==null?"":s).replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
 static String button(String label,String command,int width){return "<button value=\""+label+"\" action=\"bypass "+command+"\" width="+width+" height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";}
 static String render(String name,String title,Part part,int rgb,Fees fees,String note){
  String which=part==Part.NAME?"name":"title",hex=String.format(Locale.ROOT,"%06X",rgb);long price=part==Part.NAME?fees.name:fees.title;
  StringBuilder h=new StringBuilder("<br>");h.append(button("Цвет ника — "+money(fees.name),"_bbshubcolors name",235)).append(button("Цвет титула — "+money(fees.title),"_bbshubcolors title",235)).append("<br>");
  if(!note.isBlank())h.append("<font color=\"LEVEL\">").append(esc(note)).append("</font><br><br>");
  h.append("<font color=\"808080\">Предпросмотр</font><br><font name=\"hs16\" color=\"").append(hex).append("\">").append(esc(part==Part.NAME?name:(title==null||title.isBlank()?"Титул":title))).append("</font><br><br>");
  String[][] palette={{"Белый","FFFFFF"},{"Красный","FF0000"},{"Золотой","FFD700"},{"Зелёный","00FF00"},{"Голубой","00CCFF"},{"Синий","4169E1"},{"Сиреневый","C8A2C8"},{"Розовый","FF69B4"},{"Оранжевый","FF8C00"},{"Фиолетовый","9932CC"},{"Бирюзовый","40E0D0"},{"Серебряный","C0C0C0"}};
  h.append("<table width=480>");for(int i=0;i<palette.length;i++){if(i%3==0)h.append("<tr>");h.append("<td align=center>").append(button(palette[i][0],"_bbshubcolors preview "+which+" "+palette[i][1],150)).append("</td>");if(i%3==2)h.append("</tr>");}h.append("</table><br>");
  h.append("Свой цвет:<br><edit var=\"rgb\" width=100 length=6><br>").append(button("Посмотреть","_bbshubcolors preview "+which+" $rgb",150)).append("<br>");
  h.append("Стоимость смены: <font color=\"LEVEL\">").append(money(price)).append(" адены</font><br>").append(button("Применить цвет","_bbshubcolors buy "+which+" "+hex,210)).append("<br>Цвет сохраняется после выхода из игры.<br>");return h.toString();
 }
 public static String board(Player player,String[] args){
  Fees fees=fees();if(!fees.enabled)return "<br>Смена цветов выключена.";
  Part part=Part.NAME;int color=0xffffff;String note="";
  try{
   String action=args.length>1?args[1].toLowerCase(Locale.ROOT):"name";
   if(action.equals("name")||action.equals("title")){if(args.length>2)throw new IllegalArgumentException("Неверная команда.");part=action.equals("title")?Part.TITLE:Part.NAME;color=bgr(part==Part.NAME?player.getAppearance().getNameColor():player.getAppearance().getTitleColor());}
   else if(action.equals("preview")||action.equals("buy")){
    if(args.length!=4||(!args[2].equals("name")&&!args[2].equals("title")))throw new IllegalArgumentException("Неверная команда.");
    part=args[2].equals("title")?Part.TITLE:Part.NAME;color=rgb(args[3]);
    if(action.equals("buy")){
     if(player.isDead()||player.isInOlympiadMode()||player.isOnEvent()||player.isInDuel()||player.isInStoreMode())note="Сейчас смена цвета недоступна.";
     else synchronized(LOCKS.computeIfAbsent(player.getObjectId(),id->new Object())){
      Result result=purchase(account(player),part,bgr(color),part==Part.NAME?fees.name:fees.title);
      note=switch(result){case CHANGED->"Новый цвет сохранён.";case UNCHANGED->"Этот цвет уже установлен.";case NO_FUNDS->"Недостаточно адены.";case SAVE_FAILED->"Не удалось сохранить цвет. Оплата возвращена.";};
      if(result==Result.CHANGED)player.broadcastUserInfo();
     }
    }
   }else throw new IllegalArgumentException("Неверная команда.");
  }catch(IllegalArgumentException e){note=e.getMessage();}catch(RuntimeException e){LOG.warning("Color service failed: "+e.getClass().getSimpleName());note="Сервис временно недоступен.";}
  return render(player.getName(),player.getTitle(),part,color,fees,note);
 }
}
