package org.l2jmobius.gameserver.mechanics.olympiad;
import org.l2jmobius.gameserver.managers.phantom.PhantomOlympiadPreparation;
/** Package bridge preserves the native package-private OlympiadGame API. */
public final class LocalOlympiadPreparationBridge {
 private LocalOlympiadPreparationBridge(){}
 public static void afterRemovals(OlympiadGame game){
  if(game==null||game.isAborted()||game._playerOne==null||game._playerTwo==null||game._playerOneDisconnected||game._playerTwoDisconnected)return;
  if(!LocalOlympiadDoors.prepare(game))return;
  PhantomOlympiadPreparation.afterRemovals(game._playerOne,game._spawnOne==null?null:game._spawnOne.getLastSpawn(),game._stadiumID);
  PhantomOlympiadPreparation.afterRemovals(game._playerTwo,game._spawnTwo==null?null:game._spawnTwo.getLastSpawn(),game._stadiumID);
  OlympiadOpponentInfo.announce(game);
 }
 public static void beforeCompetition(OlympiadGame game){if(game!=null){OlympiadOpponentInfo.announce(game);PhantomOlympiadPreparation.beforeCompetition(game._playerOne,game._playerTwo);LocalOlympiadDoors.start(game);}}
 public static void competitionStarted(OlympiadGame game){
  LocalOlympiadBufferCleanup.clear(game);
  if(game==null||game.isAborted()||game._playerOneDisconnected||game._playerTwoDisconnected)return;
  final var one=game._playerOne;final var two=game._playerTwo;
  org.l2jmobius.gameserver.managers.phantom.OlympiadStartKick.started(game,one,two,game._stadiumID,
    ()->!game.isAborted()&&!game._playerOneDisconnected&&!game._playerTwoDisconnected&&game._playerOne==one&&game._playerTwo==two);
 }
 public static void clearGame(OlympiadGame game){if(game!=null){LocalOlympiadBufferCleanup.clear(game);org.l2jmobius.gameserver.managers.phantom.OlympiadStartKick.clear(game);OlympiadOpponentInfo.clear(game);PhantomOlympiadPreparation.clearGame(game._playerOne,game._playerTwo);LocalOlympiadDoors.finish(game);}}
}
