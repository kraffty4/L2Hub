package org.l2jmobius.gameserver.managers.phantom;

import java.util.Collection;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;

/** Personal profiles use native clan membership, including while dismissed. */
public final class PersonalCompanionClans {
 private PersonalCompanionClans() {}
 static Clan binding(PhantomTemplate template, Collection<Clan> clans) {
  if (!PersonalCompanions.personal(template)) return null;
  int id=PersonalCompanionNames.characterId(template.getName());
  for(Clan clan:clans){
   if(id>0&&clan.getClanMember(id)!=null)return clan;
   if(clan.getClanMember(template.getName())!=null)return clan;
   for(String old:PersonalCompanionNames.candidates(template.getName()))if(clan.getClanMember(old)!=null)return clan;
  }
  return null;
 }
 static Clan binding(PhantomTemplate template) {
  return PersonalCompanions.personal(template) ? binding(template, ClanTable.getInstance().getClans()) : null;
 }
 static String access(Player owner, PhantomTemplate template, Clan bound) {
  String personalError=PersonalCompanionPool.permission(owner,template,bound);if(personalError!=null)return personalError;
  if (!PersonalCompanions.personal(template) || bound == null || owner.getClanId() == bound.getId()) return null;
  return template.getName() + " состоит в другом клане. Призвать его могут только его соклановцы.";
 }
 static String access(Player owner, PhantomTemplate template) {
  return access(owner, template, binding(template));
 }
 static boolean sameClan(Player owner, PhantomTemplate template) {
  return owner.getClan() != null && binding(template,java.util.List.of(owner.getClan())) != null;
 }
 static String join(Player owner, PhantomTemplate template) {
  if (!PersonalCompanions.personal(template)) return "Эта кнопка предназначена для личных помощников.";
  if (owner.getClan() == null) return "Сначала вступи в клан.";
  var companions = PhantomCompanionManager.getInstance();
  synchronized (companions) {
   String error = access(owner, template);
   if (error != null) return error;
   if (!companions.isOwner(owner, template)) return "Сначала призови этого помощника.";
   Player helper = PhantomManager.getInstance().getPhantom(template);
   if (helper == null) return "Сначала призови этого помощника.";
   return enroll(owner, helper);
  }
 }
 static String enroll(Player owner, Player helper) {
  Clan clan = owner.getClan();
  if (clan == null) return "Сначала вступи в клан.";
  synchronized (clan) {
   if (owner.getClan() != clan) return "Клан изменился. Открой меню заново.";
   if (helper.getClanId() == clan.getId()) return "Помощник уже в твоём клане.";
   if (helper.getClanId() != 0) return "Помощник уже состоит в другом клане.";
   if (!clan.checkClanJoinCondition(owner, helper, 0)) return "Приём в клан невозможен: проверь право приглашать, свободные места и штрафы клана.";
   PhantomCompat.joinClan(owner, helper, 0);
   helper.storeMe();
   owner.sendMessage(helper.getName() + " принят в клан. Членство сохраняется после отзыва.");
   return null;
  }
 }
}
