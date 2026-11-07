package org.l2jmobius.gameserver.managers.phantom;
/** Human clan invitations override ordinary autonomous activity; they do not override another human or a running match. */
public final class ClanPartyInvitePolicy {
 public enum Reason {
  ACCEPT(null),MISSING("Не могу сейчас ответить на приглашение."),DEAD("Я погиб, сначала нужно воскреснуть."),OFFLINE("Меня сейчас нет в мире."),HUMAN_PARTY("Я уже в группе другого игрока."),ALREADY_YOURS("Я уже в твоей пати."),EVENT("Я участвую в ивенте."),EVENT_REGISTERED("Я записан на ивент."),OLYMPIAD("Я занят Олимпиадой."),DUEL("Я в дуэли."),RAID("Я в рейде; нужно сначала закончить участие."),TELEPORT("Я телепортируюсь, позови через несколько секунд."),LEVEL("Для обычного приглашения разница уровней больше пяти."),ENEMY("Я не хочу идти в пати после нашей ссоры."),PVP("Я сейчас дерусь с другим персонажем."),OTHER_RECRUITER("Я уже собирался в другую группу."),BOT_PARTY("Я сейчас в другой пати."),TOWN("Я сейчас занимаюсь делами в городе."),STORE("Я торгую."),PHASE("Сейчас не могу присоединиться.");
  private final String message;Reason(String text){message=text;}public String message(){return message;}
 }
 public record State(boolean priority,boolean alive,boolean online,boolean humanParty,boolean alreadyYours,boolean onEvent,boolean registeredEvent,boolean olympiad,boolean duel,boolean raid,boolean teleport,boolean inParty,String phase,int gap,boolean enemy,boolean pvp,boolean recruiterMatch){}
 private ClanPartyInvitePolicy(){}
 public static Reason decide(State s){
  if(!s.online())return Reason.OFFLINE;if(!s.alive())return Reason.DEAD;if(s.alreadyYours())return Reason.ALREADY_YOURS;if(s.humanParty())return Reason.HUMAN_PARTY;
  if(s.onEvent())return Reason.EVENT;if(s.registeredEvent())return Reason.EVENT_REGISTERED;if(s.olympiad())return Reason.OLYMPIAD;if(s.duel())return Reason.DUEL;if(s.raid())return Reason.RAID;if(s.teleport())return Reason.TELEPORT;
  if(s.priority())return switch(s.phase()){case "HUNT","TOWN","STORE","PARTY","RECRUIT"->Reason.ACCEPT;default->Reason.PHASE;};
  if(s.inParty())return Reason.BOT_PARTY;
  if(s.phase().equals("RECRUIT"))return s.recruiterMatch()?Reason.ACCEPT:Reason.OTHER_RECRUITER;
  if(s.gap()>5)return Reason.LEVEL;if(s.enemy())return Reason.ENEMY;if(s.pvp())return Reason.PVP;
  return switch(s.phase()){case "HUNT"->Reason.ACCEPT;case "TOWN"->Reason.TOWN;case "STORE"->Reason.STORE;default->Reason.PHASE;};
 }
}
