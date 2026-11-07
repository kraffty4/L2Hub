package org.l2jmobius.gameserver.mechanics.olympiad;
import java.nio.charset.StandardCharsets;import java.nio.file.*;import java.util.Properties;import java.util.logging.Logger;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/** Native Hub-owned INI. Missing/invalid values preserve the original40-second result countdown. */
public final class LocalOlympiadOptions {
 private static final Logger LOGGER=Logger.getLogger(LocalOlympiadOptions.class.getName());
 private LocalOlympiadOptions(){}
 static int parseReturnDelay(Properties p){
  String raw=p.getProperty("OlympiadReturnDelaySeconds","40").strip();
  try{int value=Integer.parseInt(raw);if(value<0||value>600)throw new IllegalArgumentException();return value;}
  catch(IllegalArgumentException e){LOGGER.warning("OlympiadLocal.ini: invalid OlympiadReturnDelaySeconds; native40 retained");return 40;}
 }
 static int load(Path file){Properties p=new Properties();if(Files.isRegularFile(file))try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);}catch(Exception e){LOGGER.warning("OlympiadLocal.ini: cannot read; native40 retained");return 40;}return parseReturnDelay(p);}
 public static int returnDelaySeconds(){return Holder.RETURN_DELAY;}
 public static boolean opponentHudEnabled(){return HudHolder.ENABLED;}
 private static final class HudHolder{
  static final boolean ENABLED=loadHud();
  private static boolean loadHud(){Properties p=new Properties();Path file=Path.of("config/Custom/OlympiadLocal.ini");if(Files.isRegularFile(file))try(var in=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(in);}catch(Exception e){return false;}return Boolean.parseBoolean(p.getProperty("OlympiadOpponentHudClass","False").strip());}
 }
 static int countdownStep(int remaining,int step){return Math.min(Math.max(0,remaining),Math.max(1,step));}
 interface Countdown {void show(int remaining);void sleep(int seconds);}
 static void countdown(int seconds,Countdown output){
  int step=10;
  for(int remaining=seconds;remaining>0;){
   output.show(remaining);
   if(remaining==10)step=5;else if(remaining==5)step=1;
   int wait=countdownStep(remaining,step);output.sleep(wait);remaining-=wait;
  }
 }
 public static void awaitReturn(OlympiadGame game){
  countdown(returnDelaySeconds(),new Countdown(){
   public void show(int remaining){game._sm=new SystemMessage(SystemMessageId.YOU_WILL_BE_MOVED_BACK_TO_TOWN_IN_S1_SECOND_S);game._sm.addInt(remaining);game.broadcastMessage(game._sm,false);}
   public void sleep(int seconds){try{Thread.sleep(seconds*1000L);}catch(Exception ignored){/* Native countdown keeps progressing after interruption. */}}
  });
 }
 private static final class Holder{static final int RETURN_DELAY=load(Path.of("config/Custom/OlympiadLocal.ini"));}
}
