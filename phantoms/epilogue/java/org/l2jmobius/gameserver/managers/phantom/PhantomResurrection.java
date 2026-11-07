package org.l2jmobius.gameserver.managers.phantom;
import org.l2jmobius.gameserver.entity.actor.Player;
/** Accept only an existing native resurrection offer to a real phantom corpse. */
public final class PhantomResurrection {
 private PhantomResurrection(){}
 public static void accept(Player player){
  if(player==null||!player.isDead()||player.isRevivingPet()||!player.isReviveRequested()||player.isResurrectionBlocked())return;
  if(PhantomManager.getInstance().isPhantom(player)){player.reviveAnswer(1);PersonalCompanions.afterRevive(player);}
 }
}
