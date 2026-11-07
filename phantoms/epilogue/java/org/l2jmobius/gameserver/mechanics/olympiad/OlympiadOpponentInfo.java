package org.l2jmobius.gameserver.mechanics.olympiad;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledFuture;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.ClassListData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/** Epilogue-compatible notice, not an independent HTML panel.
 * The stock client resets every screen-message position on a new screen message.
 * Keep chat history and request one compact native notice after the countdown.
 */
public final class OlympiadOpponentInfo {
 public static final int SCREEN_MILLISECONDS=30000;
 public static final long AFTER_START_MILLISECONDS=2000;
 private static final class Notice {
  final Set<Player> chat=Collections.newSetFromMap(new IdentityHashMap<>());
  boolean scheduled;
  ScheduledFuture<?> task;
 }
 private static final Map<OlympiadGame,Notice> SHOWN = new WeakHashMap<>();
 private OlympiadOpponentInfo() {}
 public static String className(PlayerClass playerClass) {
  if (playerClass == null) return "Неизвестная профессия";
  var info = ClassListData.getInstance().getClass(playerClass);
  String name = info == null ? playerClass.name().replace('_', ' ') : info.getClassName();
  return switch (playerClass.getId()) {
   case 40, 110 -> name + " (СХ)";
   case 46, 113 -> name + " (Дестро)";
   default -> name;
  };
 }
 private static boolean viewer(Player player) {
  return player != null && player.getClient() != null && !player.inObserverMode() && !player.isDead();
 }
 private static void showOnce(OlympiadGame game, Player player, Player opponent) {
  // Appearing completes the native teleport; do not consume the notice during the loading screen.
  if (!viewer(player) || player.isTeleporting()) return;
  synchronized (SHOWN) {
   var viewers = SHOWN.computeIfAbsent(game, key -> new Notice()).chat;
   if (viewers.contains(player)) return;
   String profession = className(opponent.getPlayerClass());
   player.sendPacket(new SystemMessage("Противник: " + opponent.getName() + " — " + profession));
   viewers.add(player);
  }
 }
 public static void announce(OlympiadGame game) {
  if (game == null || game._aborted || !game._gamestarted || game._playerOneDisconnected || game._playerTwoDisconnected) return;
  Player one = game._playerOne, two = game._playerTwo;
  if (one == null || two == null || !one.isInOlympiadMode() || !two.isInOlympiadMode()
    || one.getOlympiadGameId() != game._stadiumID || two.getOlympiadGameId() != game._stadiumID
    || one.getOlympiadSide() == two.getOlympiadSide() || (!viewer(one) && !viewer(two))) return;
  showOnce(game, one, two);
  showOnce(game, two, one);
 }
 private static boolean active(OlympiadGame game) {
  return game!=null&&!game._aborted&&game._gamestarted&&!game._playerOneDisconnected&&!game._playerTwoDisconnected
   &&game._playerOne!=null&&game._playerTwo!=null&&game._playerOne.isInOlympiadMode()&&game._playerTwo.isInOlympiadMode()
   &&game._playerOne.getOlympiadGameId()==game._stadiumID&&game._playerTwo.getOlympiadGameId()==game._stadiumID
   &&game._playerOne.getOlympiadSide()>0&&game._playerTwo.getOlympiadSide()>0
   &&game._playerOne.getOlympiadSide()!=game._playerTwo.getOlympiadSide();
 }
 private static String line(String value) {
  // Epilogue uses # as its two-line separator and strips the backtick character.
  return value==null?"":value.replace('#',' ').replace('`',' ').replace('\n',' ').replace('\r',' ').replace('\t',' ').strip();
 }
 public static String screenText(String name,String profession) {
  return "Соперник: "+line(name)+"#"+line(profession);
 }
 private static void screen(Player player,Player opponent) {
  if(!viewer(player)||player.isTeleporting())return;
  player.sendPacket(new ExShowScreenMessage(screenText(opponent.getName(),className(opponent.getPlayerClass())),
    ExShowScreenMessage.MIDDLE_RIGHT,SCREEN_MILLISECONDS,1,false,false));
 }
 public static void afterBattleStart(OlympiadGame game) {
  if(!active(game)||!game._playerOne.isOlympiadStart()||!game._playerTwo.isOlympiadStart()
   ||(!viewer(game._playerOne)&&!viewer(game._playerTwo)))return;
  announce(game);
  if(LocalOlympiadOptions.opponentHudEnabled())return;
  synchronized(SHOWN) {
   Notice notice=SHOWN.computeIfAbsent(game,key->new Notice());
   if(notice.scheduled)return;
   notice.scheduled=true;
   // Avoid the immediate start/countdown overlay. This cannot reserve a client screen channel.
   notice.task=ThreadPool.schedule(()->{
    synchronized(SHOWN) {
     if(SHOWN.get(game)!=notice||!active(game)||!game._playerOne.isOlympiadStart()||!game._playerTwo.isOlympiadStart())return;
     screen(game._playerOne,game._playerTwo);
     screen(game._playerTwo,game._playerOne);
    }
   },AFTER_START_MILLISECONDS);
  }
 }
 /** Native opponent HP/CP header, scoped to the two participants. No actor rename or extra window. */
 public static String hudName(Player viewer,Player subject) {
  return hudName(viewer,subject,LocalOlympiadOptions.opponentHudEnabled());
 }
 static String hudName(Player viewer,Player subject,boolean enabled) {
  if(subject==null)return "";
  String name=subject.getName();
  if(!enabled||viewer==null||viewer==subject||viewer.inObserverMode()
    ||!viewer.isInOlympiadMode()||!subject.isInOlympiadMode()
    ||viewer.getOlympiadGameId()<0||viewer.getOlympiadGameId()!=subject.getOlympiadGameId()
    ||viewer.getOlympiadSide()<1||viewer.getOlympiadSide()>2
    ||subject.getOlympiadSide()<1||subject.getOlympiadSide()>2
    ||viewer.getOlympiadSide()==subject.getOlympiadSide())return name;
  String tag=switch(subject.getPlayerClass().getId()) {
   case 40,110 -> "СХ";
   case 46,113 -> "Титан";
   case 16,97 -> "Биш";
   case 27,103 -> "СС";
   case 12,94 -> "Сорк";
   default -> {var info=ClassListData.getInstance().getClass(subject.getPlayerClass());yield info==null?subject.getPlayerClass().name().replace('_',' '):info.getClassName();}
  };
  int budget=Math.max(2,31-name.length()-3);
  if(tag.length()>budget){StringBuilder shortName=new StringBuilder();for(String word:tag.split("[ _]+"))if(!word.isEmpty())shortName.append(word.charAt(0));tag=shortName.length()>1?shortName.toString():tag.substring(0,Math.min(budget,tag.length()));}
  return name+" ["+tag+"]";
 }
 public static void clear(OlympiadGame game) {
  synchronized(SHOWN) {
   Notice notice=SHOWN.remove(game);
   if(notice!=null&&notice.task!=null)notice.task.cancel(false);
  }
  // An empty screen/HTML close would reset somebody else's notice or window. Never send one.
 }
}
