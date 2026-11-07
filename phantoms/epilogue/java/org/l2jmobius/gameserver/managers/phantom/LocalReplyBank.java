package org.l2jmobius.gameserver.managers.phantom;
import java.nio.file.*;import java.util.*;import java.util.concurrent.ConcurrentHashMap;import java.util.logging.*;import java.util.regex.*;import javax.xml.parsers.DocumentBuilderFactory;import org.w3c.dom.*;
import org.l2jmobius.commons.util.ConfigReader;import org.l2jmobius.gameserver.managers.FakePlayerBrain;import org.l2jmobius.gameserver.data.xml.MapRegionData;import org.l2jmobius.gameserver.entity.World;import org.l2jmobius.gameserver.entity.actor.instance.Monster;import org.l2jmobius.gameserver.entity.actor.*;import org.l2jmobius.gameserver.entity.zone.ZoneId;import org.l2jmobius.gameserver.network.enums.ChatType;import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.actor.instance.RaidBoss;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
/** Context-aware fallbacks; all bank records remain available as data and dialogue scenes. */
public final class LocalReplyBank {
 static final Logger LOG=Logger.getLogger(LocalReplyBank.class.getName());static final Pattern SLOT=Pattern.compile("\\{\\{(item|class|level)\\}\\}");
 record Entry(String id,String context,String situation,String channel,String text,String dialogue,int turn,String role){}
 record Query(String context,String situation){}
 static final class Snapshot {
  final List<Entry> entries;final Map<String,List<Entry>> contexts=new LinkedHashMap<>();final Map<String,List<Entry>> exact=new HashMap<>();final Map<String,List<Entry>> dialogues=new HashMap<>();
  Snapshot(List<Entry> rows){entries=List.copyOf(rows);for(Entry x:rows){contexts.computeIfAbsent(x.context(),k->new ArrayList<>()).add(x);exact.computeIfAbsent(x.context()+"/"+x.situation(),k->new ArrayList<>()).add(x);if(!x.dialogue().isBlank())dialogues.computeIfAbsent(x.dialogue(),k->new ArrayList<>()).add(x);}dialogues.values().forEach(xs->xs.sort(Comparator.comparingInt(Entry::turn)));}
 }
 static Snapshot read(Path path)throws Exception {var f=DocumentBuilderFactory.newInstance();f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);f.setFeature("http://xml.org/sax/features/external-general-entities",false);f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);f.setXIncludeAware(false);f.setExpandEntityReferences(false);var d=f.newDocumentBuilder().parse(path.toFile());if(!d.getDocumentElement().getTagName().equals("replyBank"))throw new IllegalArgumentException("Unsupported reply pool");List<Entry> rows=new ArrayList<>();Set<String> ids=new HashSet<>();NodeList list=d.getElementsByTagName("line");for(int i=0;i<list.getLength();i++){Element e=(Element)list.item(i);String id=e.getAttribute("id"),text=e.getTextContent();if(id.isBlank()||!ids.add(id)||text.isBlank()||text.chars().anyMatch(c->c<32))throw new IllegalArgumentException("Invalid reply record: "+id);rows.add(new Entry(id,e.getAttribute("context"),e.getAttribute("situation"),e.getAttribute("channel"),text,e.getAttribute("dialogue_id"),e.hasAttribute("turn_index")?Integer.parseInt(e.getAttribute("turn_index")):0,e.getAttribute("speaker_role")));}if(rows.isEmpty())throw new IllegalArgumentException("Empty reply pool");return new Snapshot(rows);}
 static final class Settings {static final ConfigReader C=new ConfigReader("config/Custom/ChatBank.ini");static final boolean ENABLED=C.getBoolean("ReplyBankEnabled",true);static final Path FILE=Path.of(C.getString("ReplyBankFile","data/LocalReplyBank.xml"));}
 static volatile Snapshot current=new Snapshot(List.of());static long checkAfter,modified=-1;static final Random RANDOM=new Random();static final Map<Integer,String> last=new ConcurrentHashMap<>();static final Map<Integer,Long> nextAmbient=new ConcurrentHashMap<>();
 record RecentPrompt(String id,String text,long expiresAt){
  RecentPrompt(String id,long expiresAt){this(id,CITY_STARTERS.get(id),expiresAt);}
 }
 static final Map<Integer,RecentPrompt> recentPrompts=new ConcurrentHashMap<>();
 static final Map<Integer,String> lastFallback=new ConcurrentHashMap<>();
 record DirectedKey(long pair,String channel){}
 static final Map<DirectedKey,RecentPrompt> directedReplies=new ConcurrentHashMap<>();
 static final class ReplyTurns {
  private final Map<Long,Object> pending=new ConcurrentHashMap<>();
  Object begin(long conversation){Object token=new Object();pending.put(conversation,token);return token;}
  boolean claim(long conversation,Object token){return pending.remove(conversation,token);}
  java.util.function.Consumer<String> success(long conversation,Object token,java.util.function.Consumer<String> deliver){return value->{if(claim(conversation,token))deliver.accept(value);};}
  Runnable failure(long conversation,Object token,Runnable deliver){return ()->{if(claim(conversation,token))deliver.run();};}
 }
 private static final ReplyTurns townTurns=new ReplyTurns();
 private static long townKey(int actor,int listener){return ((long)actor<<32)|(listener&0xffffffffL);}
 public static Object beginTownReply(int actor,int listener){return townTurns.begin(townKey(actor,listener));}
 public static boolean claimTownReply(int actor,int listener,Object token){return townTurns.claim(townKey(actor,listener),token);}
 static long conversationKey(Player actor,Player listener){return ((long)actor.getObjectId()<<32)|(listener.getObjectId()&0xffffffffL);}
 static synchronized Snapshot bank(){long now=System.currentTimeMillis();if(now<checkAfter)return current;checkAfter=now+10000;try{long m=Files.getLastModifiedTime(Settings.FILE).toMillis();if(m!=modified){Snapshot next=read(Settings.FILE);current=next;modified=m;LOG.info("LocalReplyBank: loaded ALL "+next.entries.size()+" records across "+next.contexts.size()+" contexts.");}}catch(Exception e){LOG.warning("LocalReplyBank: keeping previous pool: "+e.getMessage());}return current;}
 static String render(String text,Map<String,String> vars){Matcher m=SLOT.matcher(text);StringBuffer b=new StringBuffer();while(m.find()){String value=vars.get(m.group(1));if(value==null||value.isBlank())return null;m.appendReplacement(b,Matcher.quoteReplacement(value));}m.appendTail(b);return b.toString();}
 static String pick(Snapshot s,String context,String situation,String channel,Map<String,String> vars,String previous,Random random){List<Entry> pool=s.exact.getOrDefault(context+"/"+situation,List.of());List<Entry> matching=pool.stream().filter(x->(channel==null||x.channel().equals(channel))&&x.dialogue().isBlank()).toList();pool=matching;if(pool.isEmpty())return null;int offset=random.nextInt(pool.size());for(int i=0;i<pool.size();i++){Entry e=pool.get((offset+i)%pool.size());if(e.id().equals(previous))continue;String text=render(e.text(),vars);if(text!=null)return e.id()+"\n"+text;}return null;}
 static Map<String,String> facts(Player actor,Player other){Map<String,String> vars=new HashMap<>();vars.put("level",Integer.toString(actor.getLevel()));vars.put("class",actor.getPlayerClass().name());try{for(var item:actor.getSellList().getItems())if(item.getCount()>0&&item.getItem()!=null){vars.put("item",item.getItem().getName());vars.put("itemId",Integer.toString(item.getItem().getId()));break;}}catch(RuntimeException ignored){}return vars;}
 static String take(Player actor,Player other,Query q,String channel){if(q==null||!Settings.ENABLED)return null;String value=pick(bank(),q.context(),q.situation(),channel,facts(actor,other),last.get(actor.getObjectId()),RANDOM);if(value==null)return null;int at=value.indexOf('\n');last.put(actor.getObjectId(),value.substring(0,at));return value.substring(at+1);}
 static Query classify(String kind,String message){String k=(kind==null?"":kind).toUpperCase(Locale.ROOT),s=(message==null?"":message).toLowerCase(Locale.ROOT);
  if(k.equals("DIED")||k.equals("OWNER_DIED")||k.equals("KILLED"))return new Query("journey","death");if(k.equals("REVIVED"))return new Query("journey","return");if(k.equals("REST"))return new Query("support","low_mana");if(k.equals("JOINED")||k.contains("PARTY_JOIN"))return new Query("party_find","invite_pending");if(k.equals("OWNER_LEVEL_UP"))return new Query("hunt","exp");if(k.equals("OWNER_LOW_HP"))return new Query("support","support_intent");if(k.equals("WON"))return new Query("pvp","pvp_after");if(k.contains("CLAN"))return new Query("clan",k.contains("RECRUIT")?"recruitment":"clan_chat");if(k.contains("ALLY")||s.contains("альянс"))return new Query("alliance",s.contains("вар")?"future_diplomacy":"future_cooperation");
  if(k.contains("DUEL")||s.contains("дуэл"))return new Query("pvp","duel_request");if(k.contains("OLY")||s.contains("олимп"))return new Query("events",k.contains("RESULT")||s.contains("побед")?"oly_result":"oly_queue");if(k.contains("TVT")||s.contains("tvt")||s.contains("твт"))return new Query("events","tvt_signup");if(k.contains("EVENT")||s.contains("ивент"))return new Query("events","event_schedule");if(k.startsWith("LOOT")||s.contains("дроп")||s.contains("добыч"))return new Query("hunt","loot");if(k.contains("RAID")||s.contains("рейд")||s.contains("босс"))return new Query("raid",s.contains("бь")?"boss_engage":"rally");if(k.contains("TRADE")||s.contains("куплю")||s.contains("продам")||s.contains("цен"))return new Query("trade",s.contains("куплю")?"buy_request":"price_talk");if(Pattern.compile("(?U)\\b(?:рес(?:ни|ните|нул|нуть|нуться|нитесь|ай|айте)?|воскрес\\p{L}*|подним(?:и|ите|ешь|ете))\\b").matcher(s).find())return new Query("support","res_request");if(s.contains("баф"))return new Query("support","buff_request");if(s.contains("ман")||s.contains("мп"))return new Query("support","low_mana");if(s.contains("хил")||s.contains("леч"))return new Query("support","heal_request");if(k.contains("ATTACK")||k.equals("GRUDGE")||k.equals("REVENGE"))return new Query("pvp","war_contact");if(s.contains("клан"))return new Query("clan",s.contains("набор")?"recruitment":"clan_chat");if(s.contains("пати")||s.contains("пачк"))return new Query("party_find","looking_for_group");if(s.contains("бьём")||s.contains("таргет")||s.contains("бей"))return new Query("party_fight","focus_target");if(s.contains("кач")||s.contains("моб"))return new Query("hunt","target_choice");if(s.contains("город")||s.contains("иду")||s.contains("летим"))return new Query("journey","travel");return new Query("city",s.contains("привет")?"greeting":"social");}
 // Events are allowed to use bank messages only if the event and live state agree.
 static final Map<String,String> CONFIRMED_DEATH=Map.of("journey-0001","лёг блин, кто-нибудь рядом живой?");
 static final Map<String,String> CONFIRMED_DEATH_RES=Map.of(
  "support-0005","ресайте когда безопасно будет, я тут подожду",
  "support-0014","реснуть меня сможете, если там безопасно?",
  "support-0019","ресните когда чисто будет, я подожду",
  "support-0092","как чисто будет, ресните",
  "support-0154","если чисто, подойдите ресните меня",
  "support-0174","как безопасно будет, ресните меня",
  "support-0284","если чисто, ресните меня",
  "support-0831","если можешь, подними меня");
 // The companion observer only sees a dead-to-alive transition; it cannot
 // distinguish a resurrection by a player from returning to town.
 static final Map<String,String> CONFIRMED_RETURN=Map.ofEntries(
  Map.entry("journey-0116","я встал, где это вообще?"),
  Map.entry("journey-0127","сек, я встал, гляну где"),
  Map.entry("journey-0169","сек, я встал, где я?"),
  Map.entry("journey-0650","я встал, щас гляну где"),
  Map.entry("journey-0817","бля, надо было раньше отойти"));
 static final Map<String,String> CONFIRMED_JOIN=Map.of("party_find-0766","принял, куда идём?");
 static final Map<String,String> CONFIRMED_LFP=Map.ofEntries(
  Map.entry("party_find-0001","кто на кач собирает? я бы с вами"),
  Map.entry("party_find-0003","возьмите в пати, соло надоело уже"),
  Map.entry("party_find-0010","кто на фарм собирает пачку?"),
  Map.entry("party_find-0014","кто пати на кач собирает?"),
  Map.entry("party_find-0030","кто пати на фарм собирает?"),
  Map.entry("party_find-0050","кто на фарм идёт? я бы с вами"),
  Map.entry("party_find-0060","кто пачку на кач собирает?"),
  Map.entry("party_find-0070","ищу пати на кач, есть место?"),
  Map.entry("party_find-0080","лф пати на кач"),
  Map.entry("party_find-0084","лф пати, кто собирает?"),
  Map.entry("party_find-0086","возьмите в пати, одному надоело"),
  Map.entry("party_find-0100","кто на кач собирается?"),
  Map.entry("party_find-0134","кто собирает пати на фарм?"),
  Map.entry("party_find-0135","есть место в пати на кач?"),
  Map.entry("party_find-0140","лф пачку на кач"),
  Map.entry("party_find-0164","кто на кач собирает? я свободен"),
  Map.entry("party_find-0170","есть пати? одному надоело"),
  Map.entry("party_find-0190","возьмите в пати, соло надоело"),
  Map.entry("party_find-0195","есть пати на фарм? я свободен"),
  Map.entry("party_find-0196","кто на фарм собирается? возьмите"));
 public static String afterLfpSignal(Player actor,String role){
  if(actor==null||role==null||role.isBlank()||actor.isDead()||actor.isInParty()||actor.isInStoreMode()||!Settings.ENABLED)return null;
  Entry e=reviewedPick(bank(),"party_find","looking_for_group","shout",CONFIRMED_LFP,last.get(actor.getObjectId()),RANDOM);
  if(e==null)return null;last.put(actor.getObjectId(),e.id());return formatLfp(e.text(),role,actor.getLevel(),RANDOM);
 }
 static String formatLfp(String text,String role,int level,Random random){
  String who=role+" "+level;
  return switch(random.nextInt(3)){
   case 0 -> who+", "+text;
   case 1 -> text+" ("+who+")";
   default -> text+" — "+who;
  };
 }
 static final Map<String,String> CONFIRMED_JOIN_QUESTIONS=Map.of(
  "party_find-0765","хил у нас уже есть?",
  "party_find-0767","кого ещё добираем?",
  "party_find-0769","кто ведёт и куда идём?",
  "party_find-0771","кого нам ещё в пачку надо?",
  "party_find-0772","на часок идём или дольше?",
  "party_find-0773","фулл уже или кого-то ждём?");
 static final Map<String,String> CONFIRMED_CLAN_JOIN=Map.of("clan-0823","всем привет, что сегодня делаем");
 static final Map<String,String> CONFIRMED_CLAN_JOIN_CHAT=Map.of("clan-0116","всем привет, кто сегодня в поле?");
 static final Map<String,String> CONFIRMED_TVT_REGISTRATION=Map.of("events-new-0011","на твт кто сегодня идёт?");
 static final Map<String,String> CONFIRMED_OLY_REGISTRATION=Map.of(
  "events-0015","кто на оли собирается сегодня?",
  "events-0017","кто ещё на оли?",
  "events-0042","кто ещё на оли стоит?",
  "events-0067","на оли долго очередь?",
  "events-0154","кто сейчас стоит на оли?");
 static final Map<String,String> CONFIRMED_PVP_WIN=Map.ofEntries(
  Map.entry("pvp-0094","спс за файт, хорошо подрались"),
  Map.entry("pvp-0233","спс, хорошо подрались)"),
  Map.entry("pvp-0294","спс за бой)"),
  Map.entry("pvp-0311","спс за бой, нормально вышло"),
  Map.entry("pvp-0346","спс, весело подрались)"),
  Map.entry("pvp-0368","спс, заруба хорошая была)"),
  Map.entry("pvp-0421","спс за бой, хорошая заруба была"),
  Map.entry("pvp-0615","спс за бой, интересно было"),
  Map.entry("pvp-0635","спс за бой, хорошо вышло"),
  Map.entry("pvp-0754","хороший бой, спс"));
 static final Map<String,String> CONFIRMED_REST=Map.ofEntries(
  Map.entry("journey-0027","мп посижу, подождите немного"),
  Map.entry("journey-0085","подождите, я мп посижу"),
  Map.entry("journey-0155","подождите минутку, посижу"),
  Map.entry("journey-0215","я пока ману посижу"),
  Map.entry("journey-0268","я сел восстановиться, пока без меня"),
  Map.entry("journey-0315","я пока отдыхаю, напишите если двинетесь"),
  Map.entry("journey-0368","подожду пока мп восстановится"),
  Map.entry("journey-0409","я мп сижу, не начинайте"),
  Map.entry("journey-0451","подождите, мп посижу"),
  Map.entry("journey-0475","я пока отдыхаю, не теряйте"),
  Map.entry("journey-0539","я сел отдохнуть, вы пока не уходите"),
  Map.entry("journey-0661","немного посижу, пока мп не восстановится"),
  Map.entry("journey-0693","я ману сижу, не начинайте пока"));
 static final Map<String,String> CONFIRMED_SUPPORT_REST=Map.ofEntries(
  Map.entry("support-0122","сижу мп, пока не пулите"),
  Map.entry("support-0186","я мп посижу и пойдём"),
  Map.entry("support-0197","я сижу, не тяните пока"),
  Map.entry("support-0333","мп посижу"),
  Map.entry("support-0348","мп мало, посижу немного"),
  Map.entry("support-0428","мп мало, посижу минуту"),
  Map.entry("support-0443","я ману сижу, не тяните"),
  Map.entry("support-0513","посижу мп, вы там аккуратно"),
  Map.entry("support-0555","погодите, мп посижу"),
  Map.entry("support-0585","я ману сижу, не тяните пока"),
  Map.entry("support-0644","погодите, я мп сижу"),
  Map.entry("support-0764","мп посижу немного"));
 static String eventLane(String kind,boolean sameParty,boolean dead,boolean sitting,double mpRatio,String channel){
  if(!sameParty||!"party".equals(channel)||kind==null)return null;
  return switch(kind){
   case "DIED" -> dead ? "death" : null;
   case "REVIVED" -> !dead ? "revived" : null;
   case "JOINED" -> !dead ? "joined" : null;
   case "REST" -> !dead&&sitting&&mpRatio<0.25 ? "rest" : null;
   default -> null;
  };
 }
 static boolean confirmedClanJoin(String kind,String channel,boolean sameClan,boolean alive){return "CLAN_JOIN".equals(kind)&&"clan".equals(channel)&&sameClan&&alive;}
 static boolean confirmedPvpWin(String kind,String channel,boolean actorAlive,boolean opponentDead,boolean sameInstance,double distance){return "WON".equals(kind)&&"general".equals(channel)&&actorAlive&&opponentDead&&sameInstance&&distance<=1250;}
 static boolean welcomeAllowed(boolean listedFriend,boolean positiveRelation,boolean enemy){return !enemy&&listedFriend;}
 public static boolean loginWhispersEnabled(){return false;}
 static final List<String> FRIEND_WELCOME=List.of("о, привет)","здоров, как ты?","привет! как дела?","о, здарова)","привет, как оно?","о, привет, как жизнь?","здорово)","привет-привет)","о, привет, ты как?","ку, как дела?");
 static String welcomeLine(Random random){return FRIEND_WELCOME.get(random.nextInt(FRIEND_WELCOME.size()));}
 static boolean confirmedTvTRegistration(String event,boolean registered,boolean onEvent){return "TvT".equals(event)&&registered&&!onEvent;}
 static boolean confirmedOlympiadRegistration(boolean registered,boolean inMatch){return registered&&!inMatch;}
 static String registeredOlympiad(Player actor){
  if(actor==null||!Settings.ENABLED||!confirmedOlympiadRegistration(PhantomCompat.olympiadRegistered(actor),actor.isInOlympiadMode()))return null;
  Entry e=reviewedPick(bank(),"events","oly_queue","general",CONFIRMED_OLY_REGISTRATION,last.get(actor.getObjectId()),RANDOM);
  if(e==null)return null;last.put(actor.getObjectId(),e.id());return e.text();
 }
 static String registeredTvT(Player actor,String event){
  if(actor==null||!Settings.ENABLED||!confirmedTvTRegistration(event,actor.isRegisteredOnEvent(),actor.isOnEvent()))return null;
  Entry e=reviewedPick(bank(),"events","tvt_signup","general",CONFIRMED_TVT_REGISTRATION,last.get(actor.getObjectId()),RANDOM);
  if(e==null)return null;last.put(actor.getObjectId(),e.id());return e.text();
 }
 public static String event(Player actor,Player other,String kind,String what,String channel){
  if(actor==null||other==null||!Settings.ENABLED)return null;
  // A login greeting is supplied by PhantomWelcome, never by the unrelated city pool.
  if("WELCOME".equals(kind))return null;
  // Unsupported events use their own contextual fallback; do not inspect
  // unrelated actor state or pick a line from the general bank for them.
  if(kind==null||!Set.of("CLAN_JOIN","WON","DIED","REVIVED","JOINED","REST").contains(kind))return null;
  var clan=actor.getClan();if(confirmedClanJoin(kind,channel,clan!=null&&clan==other.getClan()&&actor.getClan()==clan,!actor.isDead())){
   Entry e=reviewedClanJoinPick(bank(),channel,last.get(actor.getObjectId()),RANDOM);
   if(e==null)return null;last.put(actor.getObjectId(),e.id());return e.text();
  }
  if("WON".equals(kind)&&confirmedPvpWin(kind,channel,!actor.isDead(),other.isDead(),actor.getInstanceId()==other.getInstanceId(),actor.calculateDistance2D(other))){
   Entry e=reviewedPick(bank(),"pvp","pvp_after",channel,CONFIRMED_PVP_WIN,last.get(actor.getObjectId()),RANDOM);
   if(e==null)return null;last.put(actor.getObjectId(),e.id());return e.text();
  }
  Party eventParty=actor.getParty();boolean together=eventParty!=null&&eventParty==other.getParty();
  double mpRatio=actor.getMaxMp()>0?actor.getCurrentMp()/actor.getMaxMp():1.0;
  String lane=eventLane(kind,together,actor.isDead(),actor.isSitting(),mpRatio,channel);
  if(lane==null)return null;
  String context,situation;Map<String,String> approved;
  switch(lane){
   case "death" -> {context="journey";situation="death";approved=CONFIRMED_DEATH;}
   case "revived" -> {context="journey";situation="death";approved=CONFIRMED_RETURN;}
   case "joined" -> {context="party_find";situation="invite_pending";approved=CONFIRMED_JOIN;}
   case "rest" -> {context="journey";situation="rest";approved=CONFIRMED_REST;}
   default -> {return null;}
  }
  Entry e=lane.equals("death")?reviewedDeathPick(bank(),channel,last.get(actor.getObjectId()),RANDOM):lane.equals("joined")?reviewedJoinPick(bank(),channel,last.get(actor.getObjectId()),RANDOM):lane.equals("rest")?reviewedRestPick(bank(),channel,last.get(actor.getObjectId()),RANDOM):reviewedPick(bank(),context,situation,channel,approved,last.get(actor.getObjectId()),RANDOM);
  if(e==null)return null;last.put(actor.getObjectId(),e.id());return e.text();
 }
 static String replyToPrompt(String promptId,String message){
  if(promptId==null||message==null)return null;
  String normalized=message.toLowerCase(Locale.ROOT).strip().replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
  if("city-0112".equals(promptId)){
   if(normalized.matches("(?:(?:я|обычно|чаще|играю) )?(?:без|без музыки|без звука|без музыки играю|выключаю|выключил|не слушаю|выключена)"))return "понял, я иногда тоже выключаю";
   if(normalized.matches("(?:(?:я|обычно|чаще) )?(?:с музыкой|слушаю|включаю|играю с музыкой)"))return "а какую обычно слушаешь?";
   if(normalized.matches("(?:да|ага|угу)"))return "с музыкой или без?";
  }
  if("city-manual-20261101-b01-0039".equals(promptId)&&normalized.matches("(?:я|я качался|норм|нормально|да|да норм|неплохо)"))return "понял, спасибо";
  if("city-manual-20261101-b04-0068".equals(promptId)&&normalized.matches("(?:(?:еще )?(?:час|полчаса|немного|минут[аы]? \\d+)|до \\d{1,2}(?: часов)?)"))return "ага, я тут пока побуду";
  if("city-manual-20261101-b02-0024".equals(promptId)&&normalized.matches("(?:(?:на |с )?\\d{1,3}|на двадцатом|двадцатый|двадцатом|двадцать)"))return "а, понял, спасибо";
  if("city-manual-20261101-b10-0041".equals(promptId)&&normalized.matches("(?:я|да|ага|буду|я буду|нет|не буду)"))return "ага, понял";
  if("city-manual-20261101-b16-0057".equals(promptId)&&normalized.matches("(?:я|я играл|я играла|да я|ага я)"))return "о, давно тогда";
  return null;
 }
 static String replyToRecent(RecentPrompt recent,long now,String message){
  return recent!=null&&recent.expiresAt()>=now?replyToPrompt(recent.id(),message):null;
 }
 public static String recentPromptText(Player actor,long now){
  if(actor==null)return null;RecentPrompt recent=recentPrompts.get(actor.getObjectId());
  return recent!=null&&recent.expiresAt()>=now?recent.text():null;
 }
 public static String previousMessage(Player actor,Player listener,long now,String channel){
  if(actor==null)return null;
  String actual="overheard".equals(channel)?"say":channel;
  RecentPrompt ambient="say".equals(actual)?recentPrompts.get(actor.getObjectId()):null;
  RecentPrompt direct=listener==null?null:directedReplies.get(new DirectedKey(conversationKey(actor,listener),actual));
  if(ambient!=null&&ambient.expiresAt()<now)ambient=null;
  if(direct!=null&&direct.expiresAt()<now)direct=null;
  if(direct==null)return ambient==null?null:ambient.text();
  return ambient!=null&&ambient.expiresAt()>direct.expiresAt()?ambient.text():direct.text();
 }
 public static void rememberDirectedReply(Player actor,Player listener,String text,long now){
  rememberDirectedReply(actor,listener,text,now,"whisper");
 }
 public static void rememberDirectedReply(Player actor,Player listener,String text,long now,String channel){
  if(actor==null||listener==null||text==null||text.isBlank())return;
  if(directedReplies.size()>4096)directedReplies.entrySet().removeIf(e->e.getValue().expiresAt()<now);
  String actual="overheard".equals(channel)?"say":channel;
  directedReplies.put(new DirectedKey(conversationKey(actor,listener),actual),new RecentPrompt("direct",text,now+120000L));
 }
 public static boolean matchesRecentPrompt(Player actor,long now,String message){
  return actor!=null&&replyToRecent(recentPrompts.get(actor.getObjectId()),now,withoutAddress(actor,message))!=null;
 }
 static String withoutAddress(Player actor,String message){
  return actor==null?message:withoutName(actor.getName(),message);
 }
 public static String withoutName(String actorName,String message){
  if(actorName==null||actorName.isBlank()||message==null)return message;
  String name=Pattern.quote(actorName);
  return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}_])"+name+"(?![\\p{L}\\p{N}_])").matcher(message).replaceAll(" ").strip();
 }
 public static boolean localOnlyTownChat(){return true;}
 public static String localTownResponse(int actorId,String actorName,String message){
  String text=withoutName(actorName,message);if(text==null||text.isBlank())return null;
  String previous=lastFallback.get(actorId);String answer=HumanChatFallback.choose(text,previous,RANDOM);
  if(answer==null&&Pattern.compile("(?iu)[а-яё]").matcher(text).find())answer=HumanChatFallback.unknown(previous,RANDOM);
  if(answer!=null)lastFallback.put(actorId,answer);return answer;
 }
 public static Player uniqueMatchingPrompt(Collection<Player> candidates,long now,String message){
  Player found=null;
  for(Player candidate:candidates){
   if(!matchesRecentPrompt(candidate,now,message))continue;
   if(found!=null&&found!=candidate)return null;
   found=candidate;
  }
  return found;
 }
 // A short answer without a name can address the most recent nearby question,
 // but never an older question after someone else has already spoken.
 public static Player uniqueRecentQuestion(Collection<Player> candidates,long now,String message){
  if(message==null)return null;
  String text=message.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
  if(!text.matches("(?:да|нет|ага|угу|неа|не знаю|наверное|вроде да|вроде нет|бывает)"))return null;
  Player found=null;RecentPrompt newest=null;boolean tied=false;
  for(Player p:candidates){
   if(p==null)continue;RecentPrompt r=recentPrompts.get(p.getObjectId());
   if(r==null||r.expiresAt()<now||now-(r.expiresAt()-120000L)>25000L)continue;
   if(newest==null||r.expiresAt()>newest.expiresAt()){found=p;newest=r;tied=false;}
   else if(r.expiresAt()==newest.expiresAt()&&p!=found)tied=true;
  }
  return !tied&&newest!=null&&newest.text()!=null&&newest.text().contains("?")?found:null;
 }
 static void rememberSceneLine(Player actor,String lineId,String text,long now){
  if(actor==null||lineId==null||text==null||text.isBlank())return;
  recentPrompts.put(actor.getObjectId(),new RecentPrompt(lineId,text,now+120000L));
 }
 static boolean asksActorLevel(String message){
  if(message==null)return false;
  String text=message.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
  return text.matches("(?:какой (?:у тебя )?(?:лвл|уровень|левел)|сколько у тебя (?:лвл|уровень|левел)|ты какого (?:лвла|уровня|левела))");
 }
 static boolean asksSalePrice(String message){
  if(message==null)return false;
  String text=message.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
  return text.matches("(?:сколько стоит|почем|какая цена|цена)");
 }
 static String singleSalePrice(List<Long> prices){
  if(prices.size()!=1||prices.get(0)==null||prices.get(0)<=0)return null;
  return "по "+prices.get(0)+" аден за штуку, в лавке";
 }
 static String privateSaleAnswer(Player actor,String channel,String message){
  if(actor==null||!"whisper".equals(channel)||!asksSalePrice(message)||!individualSale(actor.getPrivateStoreType()))return null;
  List<Long> prices=new ArrayList<>();
  try{for(var item:actor.getSellList().getItems())if(item.getCount()>0&&item.getItem()!=null)prices.add(item.getPrice());}catch(RuntimeException unavailable){return null;}
  return individualSale(actor.getPrivateStoreType())?singleSalePrice(prices):null;
 }
 public static String response(Player actor,Player other,String channel,String message,boolean hasOrder){
  if(hasOrder||message==null)return null;
  String lastSpoken=previousMessage(actor,other,System.currentTimeMillis(),channel);
  message=withoutAddress(actor,message);
  if(actor!=null&&"whisper".equals(channel)&&asksActorLevel(message))return "я "+actor.getLevel()+" лвл";
  String sale=privateSaleAnswer(actor,channel,message);if(sale!=null)return sale;
  if(actor!=null&&("say".equals(channel)||"overheard".equals(channel))){
   RecentPrompt recent=recentPrompts.remove(actor.getObjectId());
   String reply=replyToRecent(recent,System.currentTimeMillis(),message);if(reply!=null)return reply;
  }
  String previous=actor==null?null:lastFallback.get(actor.getObjectId());
  String answer=HumanChatFallback.choose(message,lastSpoken,previous,actor==null?new Random(0):RANDOM);
  if(answer!=null&&actor!=null)lastFallback.put(actor.getObjectId(),answer);
  return answer;
 }
 // The bundled brain consumes "message", not arbitrary extra JSON fields.
 // For a short reply to a recently verified starter, keep its question in the
 // message delivered to the model; long/new topics remain unchanged.
 static boolean looksLikeFollowup(String previous,String message){
  if(previous==null||message==null)return false;
  String prior=previous.toLowerCase(Locale.ROOT).replace('ё','е');
  String text=message.toLowerCase(Locale.ROOT).replace('ё','е').replaceAll("[^\\p{L}\\p{N}]+"," ").strip();
  if(text.isEmpty()||text.length()>80||text.split("\\s+").length>5)return false;
  if(text.matches("(?:да|нет|ага|угу|неа|понял|поняла|понятно|не знаю|вроде да|вроде нет|точно|спасибо|бывает)"))return true;
  if(prior.matches("(?s).*(музык|звук).*")&&text.matches("(?s).*(музык|звук|без|слуш|выключ|включ).*"))return true;
  if(prior.matches("(?s).*(погод|дожд|ветер|снег).*" )&&text.matches("(?s).*(дожд|ветер|солнц|снег|холод|жарк|льет).*"))return true;
  Set<String> stop=Set.of("когда","сейчас","потом","только","очень","будет","можно","может","вообще","просто","после","тоже","было","есть");
  Set<String> words=new HashSet<>();
  for(String word:prior.split("[^\\p{L}\\p{N}]+"))if(word.length()>=5&&!stop.contains(word))words.add(word);
  for(String word:text.split("\\s+"))if(words.contains(word))return true;
  return false;
 }
 public static String contextualMessage(String message,String previous){
  if(!looksLikeFollowup(previous,message))return message;
  return "(Твоя предыдущая реплика: «"+previous+"». Мой ответ:) "+message;
 }
 public static String withPreviousMessage(String request,String previous){
  if(previous==null)return request;
  if(request==null||!request.endsWith("}"))throw new IllegalArgumentException("Invalid request object");
  StringBuilder body=new StringBuilder(request);body.deleteCharAt(body.length()-1);if(body.length()>1)body.append(',');
  return FakePlayerBrain.field(body,"previousNpcMessage",previous).append('}').toString();
 }
 public static String unknownFallback(String channel){
  return channel!=null && Set.of("whisper","say","party","clan").contains(channel) ? "не понял, повтори?" : null;
 }
 public static String unknownFallback(Player actor,String channel){
  if(unknownFallback(channel)==null)return null;
  if(actor==null)return unknownFallback(channel);
  String answer=HumanChatFallback.unknown(lastFallback.get(actor.getObjectId()),RANDOM);
  if(answer!=null)lastFallback.put(actor.getObjectId(),answer);
  return answer;
 }
 public static String shout(Player actor,String text){return text;}
 // Only explicitly reviewed utterances may be used without an ongoing conversation.
 // The expected text must still match, preventing a later edit from bypassing review.
 static final Map<String,String> LOW_MP=Map.ofEntries(
  Map.entry("support-0002","мп мало, дайте посидеть"),
  Map.entry("support-0007","мп мало, большую пачку пока не тяните"),
  Map.entry("support-0028","мп на нуле, шикарно блять"),
  Map.entry("support-0033","мп мало, большую пачку сейчас не тяните"),
  Map.entry("support-0064","сядем на пару минут? маны вообще нет"),
  Map.entry("support-0082","без мп новый бой не начинайте"),
  Map.entry("support-0094","дайте посидеть, мп нет"),
  Map.entry("support-0106","мп почти нет, можно посидеть минуту?"),
  Map.entry("support-0138","мп почти нет, следующий пул могу не вытянуть"),
  Map.entry("support-0217","погодите, мп нет совсем"),
  Map.entry("support-0209","у меня мп почти нет"),
  Map.entry("support-0228","мп почти нет, не тяните пока"),
  Map.entry("support-0328","мп почти нет, не тяните"),
  Map.entry("support-0337","мп мало, подождите"),
  Map.entry("support-0458","мп почти нет"),
  Map.entry("support-0508","у меня мп мало осталось"),
  Map.entry("support-0531","мп на долгий бой не хватит"),
  Map.entry("support-0544","не тяните, мп нет"),
  Map.entry("support-0668","с такой мп бой лучше не начинать"),
  Map.entry("support-0744","ой, всё, мп нет"),
  Map.entry("support-0788","маны мало, дальше не потяну"),
  Map.entry("support-new-0026","погоди с пулом, мп почти нет"),
  Map.entry("support-0804","мп ушла, подождите немного"));
 static final Map<String,String> LOW_HP=Map.ofEntries(
  Map.entry("support-0004","хил, можешь меня подлечить?"),
  Map.entry("support-0069","хил, я красный уже!"),
  Map.entry("support-0517","хил есть? я красный уже"),
  Map.entry("support-0541","хил, подхвати меня!"),
  Map.entry("support-0589","хил, подлечи!"),
  Map.entry("support-0600","хил будет? я красный уже"),
  Map.entry("support-0733","хил, я красный!"),
  Map.entry("support-0769","хил, помоги, хп мало!"),
  Map.entry("support-0818","хил кинь как сможешь, я просел"),
  Map.entry("support-0828","мне бы хил, а то красный уже"));
 // These lines are meaningful only when nearby party members are fighting
 // different, still-living monsters. They do not assume a specific monster.
 static final Map<String,String> SPLIT_TARGET=Map.ofEntries(
  Map.entry("party_fight-0095","одну цель бейте, не всех подряд"),
  Map.entry("party_fight-0244","не бейте всех сразу, по одному"),
  Map.entry("party_fight-0506","строго без тильта, бьём одного"),
  Map.entry("party_fight-0509","одного бейте"),
  Map.entry("party_fight-0595","кого бьём-то?"));
 static final Map<String,String> SPLIT_HUNT=Map.ofEntries(
  Map.entry("hunt-0023","одного добейте сначала"),
  Map.entry("hunt-0319","кого сейчас бьём?"),
  Map.entry("hunt-0445","кого бьём? тут их несколько"),
  Map.entry("hunt-0508","одного бейте, не распыляйтесь"),
  Map.entry("hunt-0819","так кого сейчас бьём? я запутался"));
 static final Map<String,String> FIGHT_LOOT=Map.ofEntries(
  Map.entry("hunt-0003","сначала моба добейте, дроп потом"),
  Map.entry("hunt-0131","дроп после мобов глянем"),
  Map.entry("hunt-0044","чё там упало? я не увидел"),
  Map.entry("hunt-0286","сначала добьём, потом лут"),
  Map.entry("hunt-0816","дроп потом разберём"));
 static final Map<String,String> RAID_READY=Map.ofEntries(
  Map.entry("raid-0005","как все будут готовы и бафнуты, скажите"),
  Map.entry("raid-0056","все готовы? лидер, когда начинаем?"),
  Map.entry("raid-0189","все бафнулись?"),
  Map.entry("raid-0226","все на месте и по ролям готовы?"),
  Map.entry("raid-0235","мы ещё кого-то ждём?"),
  Map.entry("raid-0323","вы готовы? кого ещё ждём?"),
  Map.entry("raid-0439","все готовы?"),
  Map.entry("raid-0511","кто ещё не готов?"),
  Map.entry("raid-0523","вы уже бафнулись?"),
  Map.entry("raid-0547","все готовы или кого-то ждём?"),
  Map.entry("raid-0607","у всех нормально? никого не лагает?"),
  Map.entry("raid-0667","начинаем или ещё кого ждём?"),
  Map.entry("raid-0691","все готовы? отпишитесь"),
  Map.entry("raid-0811","начинаем или ещё бафаетесь?"),
  Map.entry("raid-0715","кого-то ждём ещё?"));
 static final Map<String,String> RAID_LOOT_RULES=Map.of(
  "raid-0099","как дроп делить будем? давайте заранее",
  "raid-0228","давайте до боя решим очередь и что делать со спорным дропом",
  "raid-0557","давайте заранее решим, кому лут");
 static Entry reviewedRaidPick(Snapshot s,String channel,String previous,Random random){
  if(!"party".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("raid/boss_engage",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(RAID_READY.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("raid/loot_rules",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(RAID_LOOT_RULES.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 // Text retained for matching a player response to the corresponding scene line.
 static final Map<String,String> CITY_STARTERS=Map.of(
  "city-0112","а вы с музыкой играете или лучше без неё?"
 );
 static final Map<String,String> ACTIVE_SALE=Map.ofEntries(
  Map.entry("trade-0010","продаю {{item}}, кому нужно — пишите"),
  Map.entry("trade-0106","продам {{item}}, пишите если нужно"),
  Map.entry("trade-0203","продам {{item}}, пишите в пм"),
  Map.entry("trade-0229","продаю {{item}}, пишите в пм"),
  Map.entry("trade-0245","продам {{item}}, кому нужно — пм"),
  Map.entry("trade-0257","продам {{item}}, кому надо?"),
  Map.entry("trade-0279","продам {{item}}, кому надо — пишите"),
  Map.entry("trade-0312","продам {{item}}, кому нужно?"),
  Map.entry("trade-0437","продам {{item}}, кому надо — пм"),
  Map.entry("trade-0452","{{item}} продаю, кому надо — пм"),
  Map.entry("trade-0469","продам {{item}}, пм"),
  Map.entry("trade-0564","продаю {{item}}, пм"),
  Map.entry("trade-0599","{{item}} продаю, пишите в пм"),
  Map.entry("trade-0634","{{item}} на продажу, пишите"),
  Map.entry("trade-0768","продаю {{item}}, кому нужно?")
 );
 record MaterialSale(int itemId,String text){}
 static final Map<String,MaterialSale> ACTIVE_MATERIAL_SALE=Map.ofEntries(
  Map.entry("trade-0006",new MaterialSale(1872,"продам кости, кому нужны?")),
  Map.entry("trade-0016",new MaterialSale(1869,"продам железную руду, пишите")),
  Map.entry("trade-0026",new MaterialSale(1871,"продам древесный уголь, кому надо?")),
  Map.entry("trade-0057",new MaterialSale(1870,"продам уголь, пишите")),
  Map.entry("trade-0206",new MaterialSale(1882,"продам кожу, пишите если нужна")),
  Map.entry("trade-0269",new MaterialSale(1872,"продаю кости, пишите")),
  Map.entry("trade-0328",new MaterialSale(1869,"продаю железную руду, кому нужна?")),
  Map.entry("trade-0332",new MaterialSale(1880,"сталь продаю, пишите")),
  Map.entry("trade-0479",new MaterialSale(1865,"продам лак, пм")),
  Map.entry("trade-0548",new MaterialSale(1872,"продаю кости, пишите кому надо")),
  Map.entry("trade-0606",new MaterialSale(1872,"кости продам, кому надо?")),
  Map.entry("trade-0660",new MaterialSale(1870,"продам уголь, кому надо — пишите")),
  Map.entry("trade-0751",new MaterialSale(1871,"продам древесный уголь, кому надо?")));
 static Entry reviewedSalePick(Snapshot s,String itemId,String previous,Random random){
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("trade/sell_offer",List.of())){
   if(!e.dialogue().isBlank()||!"trade".equals(e.channel())||e.id().equals(previous))continue;
   MaterialSale material=ACTIVE_MATERIAL_SALE.get(e.id());
   if(Objects.equals(ACTIVE_SALE.get(e.id()),e.text())||(material!=null&&Integer.toString(material.itemId()).equals(itemId)&&material.text().equals(e.text())))choices.add(e);
  }
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedDeathPick(Snapshot s,String channel,String previous,Random random){
  if(!"party".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("journey/death",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_DEATH.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("support/res_request",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_DEATH_RES.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedRestPick(Snapshot s,String channel,String previous,Random random){
  if(!"party".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("journey/rest",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_REST.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("support/low_mana",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_SUPPORT_REST.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedJoinPick(Snapshot s,String channel,String previous,Random random){
  if(!"party".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("party_find/invite_pending",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_JOIN.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("party_find/role_request",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_JOIN_QUESTIONS.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedClanJoinPick(Snapshot s,String channel,String previous,Random random){
  if(!"clan".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("clan/social",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_CLAN_JOIN.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("clan/clan_chat",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(CONFIRMED_CLAN_JOIN_CHAT.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedPick(Snapshot s,String context,String situation,String channel,Map<String,String> approved,String previous,Random random){
  List<Entry> choices=s.exact.getOrDefault(context+"/"+situation,List.of()).stream().filter(x->x.dialogue().isBlank()&&channel.equals(x.channel())&&Objects.equals(approved.get(x.id()),x.text())&&!x.id().equals(previous)).toList();
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static Entry reviewedSplitPick(Snapshot s,String channel,String previous,Random random){
  if(!"party".equals(channel))return null;
  List<Entry> choices=new ArrayList<>();
  for(Entry e:s.exact.getOrDefault("party_fight/focus_target",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(SPLIT_TARGET.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  for(Entry e:s.exact.getOrDefault("hunt/target_choice",List.of()))
   if(e.dialogue().isBlank()&&channel.equals(e.channel())&&Objects.equals(SPLIT_HUNT.get(e.id()),e.text())&&!e.id().equals(previous))choices.add(e);
  return choices.isEmpty()?null:choices.get(random.nextInt(choices.size()));
 }
 static String ambientLane(boolean humanParty, double hpRatio, double mpRatio, boolean activeStore, boolean peace, boolean combat){
  if(humanParty&&hpRatio<=0.20)return "low-hp";
  if(humanParty&&mpRatio<=0.05)return "low-mp";
  if(activeStore)return "sale";
  return null;
 }
 static boolean individualSale(PrivateStoreType type){return type==PrivateStoreType.SELL;}
 // The same direct and group heal skills used by PhantomSupport.
 static final int[] PARTY_HEAL_SKILLS={1401,1217,1011,1218,1015,1402,1219,1027,1271};
 static boolean hasNearbyHealer(Player actor,Party party){
  if(actor==null||party==null||actor.getParty()!=party)return false;
  for(Player member:party.getMembers()){
   if(member==null||member==actor||member.getParty()!=party||!member.isOnline()||member.isDead()||member.getInstanceId()!=actor.getInstanceId()||actor.calculateDistance3D(member)>1500)continue;
   for(int id:PARTY_HEAL_SKILLS)if(member.getKnownSkill(id)!=null)return actor.getParty()==party;
  }
  return false;
 }
 static boolean splitTargetCandidate(boolean online,boolean combat,boolean sameInstance,double distance,Object first,Object other,boolean otherAlive){
  return online&&combat&&sameInstance&&distance<=1200&&first!=null&&other!=null&&first!=other&&otherAlive;
 }
 static boolean splitTargets(Player actor){
  Party party=actor.getParty();if(party==null||!actor.isInCombat()||!(actor.getTarget() instanceof Monster first)||first.isAlikeDead())return false;
  boolean found=party.getMembers().stream().anyMatch(member->{
   if(member==null||member==actor||member.getParty()!=party||!(member.getTarget() instanceof Monster other))return false;
   return splitTargetCandidate(member.isOnline(),member.isInCombat(),member.getInstanceId()==actor.getInstanceId(),member.calculateDistance2D(actor),first,other,!other.isAlikeDead());
  });
  return found&&actor.getParty()==party;
 }
 static final Map<Integer,Long> nextLootScan=new ConcurrentHashMap<>();
 static final Map<Party,Long> nextRaidQuestion=new WeakHashMap<>();
 static boolean preparingForRaid(Player actor){
  if(actor==null||!actor.isOnline()||actor.isDead()||actor.isInCombat())return false;Party party=actor.getParty();if(party==null)return false;
  if(!(actor.getTarget() instanceof Monster boss)||(!(boss instanceof RaidBoss)&&!(boss instanceof GrandBoss))||!boss.isSpawned()||boss.isAlikeDead()||boss.getInstanceId()!=actor.getInstanceId()||actor.calculateDistance3D(boss)>1000)return false;
  boolean nearby=false;
  for(Player member:party.getMembers()){
   if(member==null||member.getParty()!=party||!member.isOnline()||member.isDead())continue;
   if(member.isInCombat())return false;
   if(member!=actor&&member.getInstanceId()==actor.getInstanceId()&&actor.calculateDistance2D(member)<=1200)nearby=true;
  }
  return nearby&&actor.getParty()==party;
 }
 static boolean claimRaidQuestion(Party party,long now){
  if(party==null)return false;
  synchronized(nextRaidQuestion){if(now<nextRaidQuestion.getOrDefault(party,0L))return false;nextRaidQuestion.put(party,now+600000L);return true;}
 }
 static boolean fightingWithPartyLoot(Player actor,long now){
  Party party=actor.getParty();if(party==null||!actor.isInCombat()||!(actor.getTarget() instanceof Monster target)||target.isAlikeDead()||now<nextLootScan.getOrDefault(actor.getObjectId(),0L))return false;
  nextLootScan.put(actor.getObjectId(),now+15000L);
  return World.getFirstVisibleObject(actor,Item.class,item->{
   if(!item.isSpawned()||actor.calculateDistance3D(item)>600)return false;
   var owner=item.getDropProtection().getOwner();
   return owner!=null&&owner.getParty()==party;
  })!=null&&actor.getParty()==party;
 }
 public static void ambient(Player p,PhantomTemplate t,long now){tryAmbient(p,t,now);}
 static boolean tryAmbient(Player p,PhantomTemplate t,long now){
  if(!Settings.ENABLED||p==null||t==null||p.isDead()||p.isTeleporting()||p.isCastingNow()||p.isOnEvent()||p.isInOlympiadMode()||now<nextAmbient.getOrDefault(p.getObjectId(),0L))return false;
  if(!PhantomBanter.audience(p))return false;
  String context,situation,channel,topic;Map<String,String> approved,vars=Map.of();
  Party party=p.getParty();boolean humanParty=party!=null&&party.getMembers().stream().anyMatch(x->x!=null&&x.getParty()==party&&x.isOnline()&&!x.isInOfflineMode()&&!PhantomManager.getInstance().isPhantom(x));
  if(humanParty)return false; // Do not initiate ambient lines in a human player's party.
  double hpRatio=p.getMaxHp()>0?p.getCurrentHp()/p.getMaxHp():1.0;
  double mpRatio=p.getMaxMp()>0?p.getCurrentMp()/p.getMaxMp():1.0;
  String lane=ambientLane(humanParty,hpRatio,mpRatio,individualSale(p.getPrivateStoreType()),p.isInsideZone(ZoneId.PEACE),p.isInCombat());
  if("low-hp".equals(lane)&&!hasNearbyHealer(p,party))lane=null;
  if(lane==null&&humanParty&&preparingForRaid(p))lane="raid-ready";
  if(lane==null&&humanParty&&splitTargets(p))lane="split-target";
  if(lane==null&&humanParty&&fightingWithPartyLoot(p,now))lane="fight-loot";
  if(lane==null)return false;
  switch(lane){
   case "low-hp" -> {context="support";situation="heal_request";channel="party";topic="verified:low-hp";approved=LOW_HP;}
   case "low-mp" -> {context="support";situation="low_mana";channel="party";topic="verified:low-mp";approved=LOW_MP;}
   case "sale" -> {if(LocalCityDialogues.hasActiveTradeScene(MapRegionData.getInstance().getMapRegionLocId(p)))return false;context="trade";situation="sell_offer";channel="trade";topic="verified:sale";approved=ACTIVE_SALE;vars=facts(p,null);if(!vars.containsKey("item"))return false;}
   case "split-target" -> {context="party_fight";situation="focus_target";channel="party";topic="verified:split-target";approved=SPLIT_TARGET;}
   case "fight-loot" -> {context="hunt";situation="loot";channel="party";topic="verified:fight-loot";approved=FIGHT_LOOT;}
   case "raid-ready" -> {context="raid";situation="boss_engage";channel="party";topic="verified:raid-ready";approved=RAID_READY;}
   default -> {return false;}
  }
  if(!PhantomQuiet.getInstance().allowed(p,topic,now))return false;
  Entry chosen=lane.equals("split-target")?reviewedSplitPick(bank(),channel,last.get(p.getObjectId()),RANDOM):lane.equals("sale")?reviewedSalePick(bank(),vars.get("itemId"),last.get(p.getObjectId()),RANDOM):lane.equals("raid-ready")?reviewedRaidPick(bank(),channel,last.get(p.getObjectId()),RANDOM):reviewedPick(bank(),context,situation,channel,approved,last.get(p.getObjectId()),RANDOM);
  if(chosen==null)return false;String text=render(chosen.text(),vars);if(text==null)return false;
  if(channel.equals("party")&&(party==null||p.getParty()!=party))return false;
  if(lane.equals("raid-ready")&&!claimRaidQuestion(party,now))return false;
  if(channel.equals("party")){LocalPartyDialogues.npcSpoke(p);LocalCityDialogues.partySpoke(p);party.broadcastCreatureSay(new CreatureSay(p,ChatType.PARTY,p.getName(),text),p);}
  else if(channel.equals("trade")){LocalCityDialogues.tradeSpoke(p,false);int region=MapRegionData.getInstance().getMapRegionLocId(p);for(Player listener:World.getPlayers())if(listener.getInstanceId()==p.getInstanceId()&&MapRegionData.getInstance().getMapRegionLocId(listener)==region&&!BlockList.isBlocked(listener,p))listener.sendPacket(new CreatureSay(p,ChatType.TRADE,p.getName(),text));}
  else PhantomChat.getInstance().hunterSay(p,text,topic);
  recentPrompts.remove(p.getObjectId());
  last.put(p.getObjectId(),chosen.id());nextAmbient.put(p.getObjectId(),now+1800000L);PhantomQuiet.getInstance().said(p,topic,now);return true;
 }

 private LocalReplyBank(){}
}
