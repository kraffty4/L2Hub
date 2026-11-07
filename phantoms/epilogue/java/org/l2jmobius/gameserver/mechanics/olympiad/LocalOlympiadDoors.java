package org.l2jmobius.gameserver.mechanics.olympiad;

import java.util.logging.Logger;
import org.l2jmobius.gameserver.data.xml.DoorData;
import org.l2jmobius.gameserver.entity.actor.instance.Door;

/** Each legacy arena owns exactly two gates. Never changes another stadium. */
public final class LocalOlympiadDoors {
 private static final Logger LOG = Logger.getLogger(LocalOlympiadDoors.class.getName());
 private LocalOlympiadDoors() {}
 public static boolean prepare(OlympiadGame game) { return change(game, false); }
 public static boolean openAtCountdown(OlympiadGame game) {
  return eligible(game) && change(game, true);
 }
 public static org.l2jmobius.gameserver.entity.Location centre(int stadium){if(stadium<0||stadium>=OlympiadManager.STADIUMS.length)return null;int[] c=OlympiadManager.STADIUMS[stadium].getCoordinates();return new org.l2jmobius.gameserver.entity.Location(c[0],c[1],c[2]);}
 /** Cross the owner's gate before combat, rather than forcing every class to the arena centre. */
 public static org.l2jmobius.gameserver.entity.Location exitPoint(org.l2jmobius.gameserver.entity.actor.Player player) {
  if(!mayLeaveSpawn(player)||player.isDead()||player.isTeleporting())return null;
  int[] c=OlympiadManager.STADIUMS[player.getOlympiadGameId()].getCoordinates();
  int[] ids=OlympiadManager.STADIUMS[player.getOlympiadGameId()].getDoorID();
  Door gate=DoorData.getInstance().getDoor(ids[player.getOlympiadSide()==1?1:0]);
  double dx=c[0]-gate.getX(),dy=c[1]-gate.getY(),length=Math.hypot(dx,dy);
  if(length<1)return null;
  return new org.l2jmobius.gameserver.entity.Location(gate.getX()+(int)Math.round(dx*128/length),gate.getY()+(int)Math.round(dy*128/length),c[2]);
 }
 public static boolean mayLeaveSpawn(org.l2jmobius.gameserver.entity.actor.Player player) {
  if(player==null||!player.isInOlympiadMode()||player.isOlympiadStart()||player.inObserverMode())return false;
  int id=player.getOlympiadGameId();if(id<0||id>=OlympiadManager.STADIUMS.length||player.getOlympiadSide()<1||player.getOlympiadSide()>2)return false;
  int[] doors=OlympiadManager.STADIUMS[id].getDoorID();var one=DoorData.getInstance().getDoor(doors[0]);var two=DoorData.getInstance().getDoor(doors[1]);
  return one!=null&&two!=null&&one.isOpen()&&two.isOpen();
 }
 public static boolean start(OlympiadGame game) {
  if (!eligible(game)) return false;
  if (game._stadiumID < 0 || game._stadiumID >= OlympiadManager.STADIUMS.length) { game._aborted = true; return false; }
  int[] ids = OlympiadManager.STADIUMS[game._stadiumID].getDoorID();
  Door one = DoorData.getInstance().getDoor(ids[0]), two = DoorData.getInstance().getDoor(ids[1]);
  if (one == null || two == null || !one.isOpen() || !two.isOpen()) {
   game._aborted = true;
   LOG.warning("Olympiad gates: arena " + game._stadiumID + ": gates were not opened at the 10-second countdown; match aborted");
   return false;
  }
  return true;
 }
 private static boolean eligible(OlympiadGame game) {
  if (game == null || game._aborted) return false;
  if (!game._gamestarted || game._playerOne == null || game._playerTwo == null || game._playerOneDisconnected || game._playerTwoDisconnected) {
   game._aborted = true;
   return false;
  }
  return true;
 }
 public static void finish(OlympiadGame game) { change(game, false); }
 private static boolean change(OlympiadGame game, boolean open) {
  if (game == null) return false;
  if (game._stadiumID < 0 || game._stadiumID >= OlympiadManager.STADIUMS.length) {
   game._aborted = true;
   LOG.warning("Olympiad gates: invalid stadium " + game._stadiumID);
   return false;
  }
  int[] ids = OlympiadManager.STADIUMS[game._stadiumID].getDoorID();
  Door one = DoorData.getInstance().getDoor(ids[0]);
  Door two = DoorData.getInstance().getDoor(ids[1]);
  if (one == null || two == null) {
   game._aborted = true;
   LOG.warning("Olympiad gates: missing door(s) " + ids[0] + "/" + ids[1] + "; match aborted");
   return false;
  }
  try {
   if (open) { one.openMe(); two.openMe(); }
   else { one.closeMe(); two.closeMe(); }
   if (one.isOpen() != open || two.isOpen() != open) throw new IllegalStateException("gate state did not change");
   return true;
  } catch (RuntimeException error) {
   game._aborted = true;
   if (open) {
    try { one.closeMe(); } catch (RuntimeException ignored) { }
    try { two.closeMe(); } catch (RuntimeException ignored) { }
   }
   LOG.warning("Olympiad gates: arena " + game._stadiumID + ": " + error + "; match aborted");
   return false;
  }
 }
}
