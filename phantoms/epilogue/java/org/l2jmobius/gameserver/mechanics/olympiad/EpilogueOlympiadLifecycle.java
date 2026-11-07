/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 * Код предназначен для L2Hub / L2J Mobius Gracia Epilogue.
 */
package org.l2jmobius.gameserver.mechanics.olympiad;
import org.l2jmobius.gameserver.managers.phantom.OlympiadStartKick;
import org.l2jmobius.gameserver.managers.phantom.EpilogueOlympiadSkills;
/** Только новые врезки36; старые локальные двери, подготовка и экран сюда не переносятся. */
public final class EpilogueOlympiadLifecycle {
 private EpilogueOlympiadLifecycle() {}
 public static void started(OlympiadGame game) {
  EpilogueOlympiadBufferCleanup.clear(game);
  if(game==null||game.isAborted()||game._playerOneDisconnected||game._playerTwoDisconnected)return;
  final var one=game._playerOne;final var two=game._playerTwo;
  OlympiadStartKick.started(game,one,two,game._stadiumID,
    ()->!game.isAborted()&&!game._playerOneDisconnected&&!game._playerTwoDisconnected&&game._playerOne==one&&game._playerTwo==two);
 }
 public static void finished(OlympiadGame game) {
  if(game==null)return;
  EpilogueOlympiadBufferCleanup.clear(game);OlympiadStartKick.clear(game);
  EpilogueOlympiadSkills.clear(game._playerOneID);EpilogueOlympiadSkills.clear(game._playerTwoID);
 }
}
