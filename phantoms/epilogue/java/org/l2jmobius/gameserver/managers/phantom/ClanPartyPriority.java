package org.l2jmobius.gameserver.managers.phantom;
import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.util.logging.Logger;
import org.l2jmobius.gameserver.entity.World;import org.l2jmobius.gameserver.entity.actor.Player;import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
/** Delivery on the hunter's own tick prevents an old town/shop action from running after transfer. */
public final class ClanPartyPriority {
 private static final Logger LOG=Logger.getLogger(ClanPartyPriority.class.getName());
 private static final Map<String,Invite> PENDING=new ConcurrentHashMap<>();private static final ThreadLocal<String> DISPATCH=new ThreadLocal<>();
 record Invite(int ownerId,long until,PartyDistributionType type){}
 private ClanPartyPriority(){}
 private static String key(PhantomTemplate t){return t.getName().toLowerCase(Locale.ROOT);}
 public static boolean priority(Player owner,Player bot){return owner!=null&&bot!=null&&!PhantomManager.getInstance().isPhantom(owner)&&owner.getClanId()!=0&&owner.getClanId()==bot.getClanId();}
 static boolean offer(String name,int owner,long now,PartyDistributionType type){
  Invite next=new Invite(owner,now+20000,type);Invite old=PENDING.putIfAbsent(name,next);
  if(old!=null&&old.until()<now)return PENDING.replace(name,old,next);return old==null||old.ownerId()==owner;
 }
 public static boolean pending(Player bot){return bot!=null&&PENDING.containsKey(bot.getName().toLowerCase(Locale.ROOT));}
 static Invite take(String name){return PENDING.remove(name);}
 static String senderBlock(Player owner){
  if(owner==null||!owner.isOnline())return "Приглашающий вышел из игры.";
  if(owner.isInOlympiadMode()||owner.isOnEvent()||owner.isInDuel())return "Во время матча приглашение недоступно.";
  if(owner.isInParty()&&!owner.getParty().isLeader(owner))return "Приглашать может только лидер пати.";
  if(owner.isInParty()&&owner.getParty().getMemberCount()>=9)return "В пати уже девять участников.";return null;
 }
 public static boolean defer(Player owner,Player bot,PhantomTemplate template,PartyDistributionType type){
  String name=key(template);if(!template.isHunter()||!priority(owner,bot)||name.equals(DISPATCH.get()))return false;
  String block=senderBlock(owner);ClanPartyInvitePolicy.Reason reason=PhantomHunterManager.getInstance().inviteDecision(template,owner);
  if(block==null&&reason!=ClanPartyInvitePolicy.Reason.ACCEPT)block=reason.message();
  if(block!=null){owner.sendMessage(bot.getName()+": "+block);log(bot,owner,reason,"declined before queue");return true;}
  if(!offer(name,owner.getObjectId(),System.currentTimeMillis(),type)){owner.sendMessage(bot.getName()+": уже отвечаю на приглашение соклановца.");return true;}
  log(bot,owner,reason,"queued priority clan invite");return true;
 }
 public static boolean process(PhantomTemplate template,Player bot,long now){
  String name=key(template);Invite invite=take(name);if(invite==null)return false;Player owner=World.getPlayer(invite.ownerId());
  String block=senderBlock(owner);
  if(block==null&&(now>invite.until()||!priority(owner,bot)))block="Приглашение соклановца устарело.";
  if(block==null&&!bot.isInOlympiadMode()&&PhantomCompat.olympiadRegistered(bot)){
   org.l2jmobius.gameserver.mechanics.olympiad.Olympiad.getInstance().unRegisterNoble(bot);
   if(bot.isInOlympiadMode()||PhantomCompat.olympiadRegistered(bot))block="Не удалось отменить запись на Олимпиаду.";
  }
  if(block==null&&bot.isRegisteredOnEvent()&&!bot.isOnEvent()&&!PhantomEvents.getInstance().cancelWaitingForClanInvite(bot))block="Не удалось отменить запись на ивент.";
  ClanPartyInvitePolicy.Reason reason=block==null?PhantomHunterManager.getInstance().inviteDecision(template,owner):ClanPartyInvitePolicy.Reason.MISSING;
  if(block==null&&reason!=ClanPartyInvitePolicy.Reason.ACCEPT)block=reason.message();
  if(block!=null){if(owner!=null&&owner.isOnline())owner.sendMessage(bot.getName()+": "+block);log(bot,owner,reason,"declined on hunter tick");return false;}
  // All checks happen before dropping a store or another bot group. Never dissolve a human party.
  if(bot.isInParty()&&bot.getParty().getMembers().stream().anyMatch(p->!PhantomManager.getInstance().isPhantom(p))){owner.sendMessage(bot.getName()+": я уже в группе другого игрока.");return false;}
  if(!PhantomRaid.getInstance().yieldToClanInvite(bot)){owner.sendMessage(bot.getName()+": в моём рейде есть другой игрок.");return false;}
  PhantomHunterParty.getInstance().yieldToClanInvite(bot,now);
  PhantomCompanionManager.getInstance().release(template,true);
  PhantomHunterManager.getInstance().preparePriorityPartyJoin(template,bot);
  DISPATCH.set(name);
  try{PhantomCompanionManager.getInstance().onInvited(owner,bot,invite.type());}
  finally{DISPATCH.remove();}
  boolean joined=bot.isInParty()&&owner.isInParty()&&bot.getParty()==owner.getParty();log(bot,owner,reason,joined?"priority clan invite joined":"priority clan invite failed");return joined;
 }
 public static void log(Player bot,Player owner,ClanPartyInvitePolicy.Reason reason,String result){LOG.info("Clan party invite: "+(bot==null?"?":bot.getName())+" <- "+(owner==null?"offline":owner.getName())+"; reason="+reason+"; "+result);}
}
