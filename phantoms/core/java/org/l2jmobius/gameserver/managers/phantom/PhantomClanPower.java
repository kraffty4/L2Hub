package org.l2jmobius.gameserver.managers.phantom;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: кланы как сила (docs/LIVE_SERVER.md, п. 12; docs/PHANTOMS.md, раздел «Кланы как сила»). Войны и эмблемы здесь не трогаем, клан-чата между ботами нет.
 * <ul>
 * <li>глава сам зовёт живого ({@code HunterClanInvitePlayers}): шёпот и штатное окно «вступить в клан?»;</li>
 * <li>титулы по месту в клане ({@code HunterClanTitles}): меняются только пустые и свои;</li>
 * <li>альянсы кланов охотников ({@code HunterClanAlliances}): союз, вступление и разрыв по правилам Mobius;</li>
 * <li>топ всех кланов ({@code HunterClanTop}): {@code .clans top}, кнопка на доске «Охотники»;</li>
 * <li>переворот, раскол и громкий развал ({@code HunterClanDrama}, не чаще раза в {@code HunterClanDramaHours} часов на весь мир).</li>
 * </ul>
 * Весь ход — под монитором {@link PhantomHunterClans}: зовётся из его {@code control}.
 */
final class PhantomClanPower
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanPower.class.getName());

	/** Как часто ищем, кого позвать; топ зовёт не больше одного за проход. */
	private static final long INVITE_GAP = 60000;
	private static final long TITLE_GAP = 600000;
	private static final long ALLY_GAP = 1800000;
	private static final long DRAMA_GAP = 900000;

	/** С какого уровня живого зовут. */
	private static final int INVITE_LEVEL = 10;
	/** Сколько живой должен пробыть в мире, чтобы его позвали: не с порога. */
	private static final long INVITE_SEEN = 900000;
	/** Пауза между приглашениями одному живому, удваивается с каждым (1, 2, 4, 8, 16 ч). */
	private static final long INVITE_PLAYER_GAP = 3600000;
	private static final int INVITE_BACKOFF_MAX = 4;
	/** Тот же клан того же живого — не чаще. */
	private static final long INVITE_PAIR_GAP = 43200000;
	/** Шёпот, потом окно. */
	private static final long INVITE_DELAY = 6000;
	/** Незнакомого зовут, только если он рядом, в ±{@value #INVITE_LEVELS} уровнях, с этим шансом за проход (× общительность главы). */
	private static final int INVITE_RANGE = 2500;
	private static final int INVITE_LEVELS = 10;
	private static final double INVITE_STRANGER_CHANCE = 5;
	/** Друга клана зовут где угодно, с этим шансом за проход. */
	private static final double INVITE_FRIEND_CHANCE = 50;

	/** С каким числом членов появляются замы; сколько их. */
	private static final int DEPUTY_MEMBERS = 5;
	private static final int DEPUTIES = 2;
	/** На столько уровней ниже среднего — рекрут. */
	private static final int ROOKIE_GAP = 10;

	/** С какого уровня клан основывает союз (требование Mobius) и входит в него. */
	private static final int ALLY_FOUND_LEVEL = 5;
	private static final int ALLY_JOIN_LEVEL = 3;
	/** Шанс союза пары за проход: главы или члены дружат, иначе. */
	private static final double ALLY_FRIEND_CHANCE = 30;
	private static final double ALLY_STRANGER_CHANCE = 3;
	/** Один союз на столько кланов охотников. */
	private static final int ALLY_CLANS_PER = 5;

	/** С какого числа членов в клане бывает переворот. */
	private static final int COUP_MEMBERS = 4;
	/** Тот же клан — не чаще. */
	private static final long COUP_GAP = 7 * 86400000L;
	/** Шанс переворота за проход на единицу смуты. */
	private static final double COUP_CHANCE = 1;
	/** Глава пропал на столько — смута. */
	private static final long ABSENT_TIME = 6 * 3600000L;
	/** Претендент сильнее главы на столько уровней — смута. */
	private static final int LEVEL_GAP = 8;
	/** Младше главы на столько уровней в главы идут, только если он враг половины клана. */
	private static final int PRETENDER_LEVELS = 10;
	/** Смута «глава — враг половины клана»: переворот без броска (раздел 4.50, «Власть и распад»). */
	private static final int UNREST_SURE = 100;
	/** После переворота бывший глава уводит друзей с таким шансом. */
	private static final int SPLIT_CHANCE = 50;
	/** Шанс развала за проход, если есть причина. */
	private static final int COLLAPSE_CHANCE = 5;
	/** Клан от этого уровня не разваливается: слишком много в него вложено. */
	private static final int COLLAPSE_MAX_LEVEL = 6;

	private static final int TOP_PAGE = 12;

	private static final String DRAMA_VARIABLE = "L2HubClanDramaAt";
	private static final String COUP_VARIABLE = "L2HubClanCoup_";

	private static final String[] TITLES_LEADER = { "Глава", "Босс", "Основатель", "Капитан", "Батя" };
	private static final String[] TITLES_LEADER_EN = { "Leader", "Boss", "Founder", "Captain", "Warlord" };
	private static final String[] TITLES_DEPUTY = { "Зам", "Правая рука", "Советник", "Офицер" };
	private static final String[] TITLES_DEPUTY_EN = { "Deputy", "Right hand", "Advisor", "Officer" };
	private static final String[] TITLES_HEALER = { "Хил клана", "Наш лекарь", "Полевой медик" };
	private static final String[] TITLES_HEALER_EN = { "Clan healer", "Our medic", "Field medic" };
	private static final String[] TITLES_TANK = { "Щит клана", "Живая стена", "Танк" };
	private static final String[] TITLES_TANK_EN = { "Clan shield", "Living wall", "Tank" };
	private static final String[] TITLES_BUFFER = { "Бафы тут", "Баффер клана" };
	private static final String[] TITLES_BUFFER_EN = { "Buffs here", "Clan buffer" };
	private static final String[] TITLES_ROOKIE = { "Рекрут", "Новенький", "Салага" };
	private static final String[] TITLES_ROOKIE_EN = { "Recruit", "New guy", "Rookie" };
	private static final String[] TITLES_MEMBER = { "Боец", "Ветеран", "Рубака", "Старожил", "Кланер" };
	private static final String[] TITLES_MEMBER_EN = { "Fighter", "Veteran", "Slasher", "Old-timer", "Clanmate" };
	private static final String[][] ALL_TITLES = { TITLES_LEADER, TITLES_LEADER_EN, TITLES_DEPUTY, TITLES_DEPUTY_EN, TITLES_HEALER, TITLES_HEALER_EN, TITLES_TANK, TITLES_TANK_EN, TITLES_BUFFER, TITLES_BUFFER_EN, TITLES_ROOKIE, TITLES_ROOKIE_EN, TITLES_MEMBER, TITLES_MEMBER_EN };
	private static final Set<String> OUR_TITLES = new HashSet<>();
	static
	{
		for (String[] pool : ALL_TITLES)
		{
			Collections.addAll(OUR_TITLES, pool);
		}
	}

	private static final String[] INVITE_FRIEND = { "слушай, у нас в %s место есть, пойдёшь к нам? кину инвайт", "давай к нам в %s, свои люди. инвайт кидаю" };
	private static final String[] INVITE_FRIEND_EN = { "hey, we have room in %s, wanna join? sending invite", "come to %s, good people here. invite incoming" };
	private static final String[] INVITE_STRANGER = { "привет, ты без клана? у нас %s, ищем людей. кину инвайт, глянь", "видел тебя на споте, неплохо бьёшь. иди к нам в %s?" };
	private static final String[] INVITE_STRANGER_EN = { "hi, no clan? we're %s, looking for people. sending invite", "saw you on the spot, not bad. join %s?" };
	private static final String[] COUP_SHOUT = { "%s больше не глава %s. теперь решаю я", "в %2$s смена власти, %1$s свободен. кто недоволен — в пм" };
	private static final String[] COUP_SHOUT_EN = { "%s no longer runs %s. I do now", "new management in %2$s, %1$s is out. complaints go to my pm" };
	private static final String[] SPLIT_SHOUT = { "ухожу из %s. кто со мной — вы знаете, где меня искать", "хватит с меня %s, начинаем заново" };
	private static final String[] SPLIT_SHOUT_EN = { "I'm leaving %s. you know where to find me", "done with %s, starting over" };
	private static final String[] COLLAPSE_SHOUT = { "всё, %s больше нет. спасибо всем, кто был", "%s распущен. разбирайтесь теперь сами" };
	private static final String[] COLLAPSE_SHOUT_EN = { "that's it, %s is no more. thanks to everyone who stayed", "%s is disbanded. you're on your own now" };
	private static final String[] ALLY_SHOUT = { "у нас союз с %s, тронете их — будете иметь дело с нами", "%s теперь с нами в одном альянсе" };
	private static final String[] ALLY_SHOUT_EN = { "we're allied with %s now, touch them and you deal with us", "%s is in our alliance now" };
	private static final String[] ALLY_BREAK_SHOUT = { "мы больше не с %s. союза нет", "с %s покончено, союз разорван" };
	private static final String[] ALLY_BREAK_SHOUT_EN = { "we're done with %s. no more alliance", "alliance with %s is over" };

	/** Почему неспокойно в клане. */
	private record Unrest(int score, String why)
	{
	}

	private long _nextInvite;
	private long _nextTitles;
	private long _nextAlly;
	private long _nextDrama;
	/** Живой → когда впервые замечен в мире. */
	private final Map<Integer, Long> _seen = new HashMap<>();
	/** Живой → {пауза до, сколько раз звали}. */
	private final Map<Integer, long[]> _playerGaps = new HashMap<>();
	/** «клан:живой» → когда звали. */
	private final Map<String, Long> _pairs = new HashMap<>();
	/** Клан → с какого момента главы нет в игре. */
	private final Map<Integer, Long> _absent = new HashMap<>();

	private PhantomClanPower()
	{
	}

	/**
	 * Из {@link PhantomHunterClans#control}, под его монитором, раз в 10 с.
	 * @param hunters охотники в мире, живые
	 * @param now текущее время
	 */
	void control(List<Player> hunters, long now)
	{
		final boolean on = PhantomHuntersConfig.HUNTER_CLANS;
		// Самопроверки кланов идут по своим сценариям: живой ход им не мешает.
		final boolean natural = on && PhantomHuntersConfig.HUNTERS_SELF_TEST.isEmpty();
		if (_nextInvite == 0)
		{
			_nextInvite = now + INVITE_GAP;
			_nextAlly = now + ALLY_GAP;
			_nextDrama = now + DRAMA_GAP;
		}
		try
		{
			if (now >= _nextTitles)
			{
				_nextTitles = now + TITLE_GAP;
				titles(hunters);
			}
			if (now >= _nextInvite)
			{
				_nextInvite = now + INVITE_GAP;
				if (natural && PhantomHuntersConfig.HUNTER_CLAN_INVITE_PLAYERS)
				{
					invite(now);
				}
			}
			if (now >= _nextAlly)
			{
				_nextAlly = now + ALLY_GAP;
				if (natural)
				{
					alliances(now, true);
				}
			}
			if (now >= _nextDrama)
			{
				_nextDrama = now + DRAMA_GAP;
				if (natural && PhantomHuntersConfig.HUNTER_CLAN_DRAMA)
				{
					drama(now);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e.getMessage(), e);
		}
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	private static String pick(String[] ru, String[] english)
	{
		final String[] pool = en() ? english : ru;
		return pool[Rnd.get(pool.length)];
	}

	private static boolean isHunter(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && template.isHunter() && !template.isReaper() && !PhantomManager.getInstance().isSecret(player);
	}

	/**
	 * @return кланы охотников, кроме клана самопроверки
	 */
	private static List<Clan> hunterClans()
	{
		final int test = PhantomHunterClans.getInstance().testClanId();
		final List<Clan> clans = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (PhantomHunterClans.isHunterClan(clan) && (clan.getId() != test))
			{
				clans.add(clan);
			}
		}
		return clans;
	}

	private static Player leaderOnline(Clan clan)
	{
		final ClanMember head = clan.getLeader();
		final Player leader = head == null ? null : head.getPlayer();
		return (leader != null) && leader.isOnline() ? leader : null;
	}

	/**
	 * @return охотники клана в игре
	 */
	private static List<Player> onlineHunters(Clan clan)
	{
		final List<Player> result = new ArrayList<>();
		for (Player player : clan.getOnlineMembers(0))
		{
			if (isHunter(player))
			{
				result.add(player);
			}
		}
		return result;
	}

	/**
	 * @return в клане есть живой игрок, хоть и вне игры
	 */
	private static boolean hasLiving(Clan clan)
	{
		for (ClanMember member : clan.getMembers())
		{
			if (PhantomData.getInstance().getTemplate(member.getName()) == null)
			{
				return true;
			}
		}
		return false;
	}

	// ---------- Приглашение живого ----------

	private void invite(long now)
	{
		final List<Player> living = new ArrayList<>();
		final Set<Integer> present = new HashSet<>();
		for (Player player : World.getPlayers())
		{
			if (player.isOnline() && !PhantomManager.getInstance().isPhantom(player))
			{
				living.add(player);
				present.add(player.getObjectId());
				_seen.putIfAbsent(player.getObjectId(), now);
			}
		}
		_seen.keySet().retainAll(present);
		if (living.isEmpty())
		{
			return;
		}
		_pairs.values().removeIf(at -> (now - at) >= INVITE_PAIR_GAP);
		final List<Clan> clans = hunterClans();
		Collections.shuffle(clans);
		Collections.shuffle(living);
		for (Clan clan : clans)
		{
			final Player leader = leaderOnline(clan);
			if ((leader == null) || leader.isDead() || (clan.getMembersCount() >= PhantomHunterClans.getInstance().appetite(clan)))
			{
				continue;
			}
			for (Player player : living)
			{
				if (refusal(clan, player, now) != null)
				{
					continue;
				}
				final boolean friend = friendOfClan(clan, player);
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(leader.getName());
				final double chance = friend ? INVITE_FRIEND_CHANCE : INVITE_STRANGER_CHANCE * (template == null ? 1 : template.getSocial());
				if ((friend || near(leader, player)) && (Rnd.get(100.0) < chance))
				{
					offer(clan, leader, player, friend, now);
					return;
				}
			}
		}
	}

	private static boolean near(Player leader, Player player)
	{
		return (Math.abs(leader.getLevel() - player.getLevel()) <= INVITE_LEVELS) && leader.isInsideRadius3D(player, INVITE_RANGE);
	}

	private static boolean friendOfClan(Clan clan, Player player)
	{
		for (Player mate : onlineHunters(clan))
		{
			if (PhantomRelations.getInstance().isFriend(mate, player))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return почему этого живого этот клан сейчас не зовёт или {@code null}
	 */
	private String refusal(Clan clan, Player player, long now)
	{
		return free(player, now) ? pause(clan, player, now) : "busy or not free";
	}

	/**
	 * @return какая пауза или вражда держит приглашение, {@code null} — ничего
	 */
	private String pause(Clan clan, Player player, long now)
	{
		final Long seen = _seen.get(player.getObjectId());
		if ((seen == null) || ((now - seen) < INVITE_SEEN))
		{
			return "just came";
		}
		final long[] gap = _playerGaps.get(player.getObjectId());
		if ((gap != null) && (now < gap[0]))
		{
			return "player gap";
		}
		if (_pairs.containsKey(clan.getId() + ":" + player.getObjectId()))
		{
			return "pair gap";
		}
		for (Player mate : onlineHunters(clan))
		{
			if (PhantomRelations.getInstance().isEnemy(mate, player))
			{
				return "enemy of " + mate.getName();
			}
		}
		return null;
	}

	/**
	 * @return без клана, без штрафа, не занят: бой, олимпиада, лавка, ивент и чужой запрос не прерываем
	 */
	private static boolean free(Player player, long now)
	{
		return player.isOnline() && !player.isDead() && (player.getClan() == null) && (player.getClanJoinExpiryTime() <= now) && (player.getLevel() >= INVITE_LEVEL) && !player.isInCombat() && !player.isInOlympiadMode() && !player.isInStoreMode() && !player.isOnEvent() && !player.isProcessingRequest();
	}

	private void offer(Clan clan, Player leader, Player player, boolean friend, long now)
	{
		final long[] gap = _playerGaps.computeIfAbsent(player.getObjectId(), id -> new long[2]);
		gap[0] = now + (INVITE_PLAYER_GAP << Math.min(gap[1], INVITE_BACKOFF_MAX));
		gap[1]++;
		_pairs.put(clan.getId() + ":" + player.getObjectId(), now);
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " invites " + player.getName() + " to clan " + clan.getName() + " (" + (friend ? "friend of the clan" : "stranger nearby") + ").");
		final String what = PhantomHunterManager.describe(player) + " без клана. Ты глава клана «" + clan.getName() + "» (" + clan.getLevel() + " ур., " + clan.getMembersCount() + " чел.) и сам зовёшь его к себе" + (friend ? ", он друг клана" : ", ты видел его рядом") + ". Напиши ему коротко в личку: окно приглашения сейчас придёт";
		final String fallback = String.format(friend ? pick(INVITE_FRIEND, INVITE_FRIEND_EN) : pick(INVITE_STRANGER, INVITE_STRANGER_EN), clan.getName());
		PhantomChat.getInstance().hunterEvent(leader, player, "CLAN_INVITE", what, fallback, INVITE_PAIR_GAP, true);
		ThreadPool.schedule(() -> window(clan, leader, player), INVITE_DELAY);
	}

	/**
	 * Окно через пару секунд после шёпота: за это время живой мог вступить в бой или в другой клан, а глава — потерять клан. Ответ разбирает RequestAnswerJoinPledge Mobius.
	 */
	private void window(Clan clan, Player leader, Player player)
	{
		synchronized (PhantomHunterClans.getInstance())
		{
			final long now = System.currentTimeMillis();
			if (!leader.isOnline() || !leader.isClanLeader() || (leader.getClan() != clan) || !free(player, now) || !clan.checkClanJoinCondition(leader, player, 0))
			{
				LOGGER.info(getClass().getSimpleName() + ": no invite window for " + player.getName() + " from clan " + clan.getName() + ": the moment has passed.");
				return;
			}
			if (!PhantomCompat.askJoinClan(leader, player))
			{
				LOGGER.info(getClass().getSimpleName() + ": no invite window for " + player.getName() + " from clan " + clan.getName() + ": another request is in progress.");
			}
		}
	}

	// ---------- Титулы ----------

	/**
	 * Титул по месту в клане. Чужие титулы не трогаем; свои снимаем у тех, кто вне клана охотников, и когда выключено.
	 */
	private void titles(Collection<Player> hunters)
	{
		for (Player hunter : hunters)
		{
			final String wanted = wantedTitle(hunter);
			final String title = hunter.getTitle() == null ? "" : hunter.getTitle();
			final boolean replaceable = title.isEmpty() || OUR_TITLES.contains(title);
			final String target = wanted == null ? (OUR_TITLES.contains(title) ? "" : title) : (replaceable ? wanted : title);
			if (!target.equals(title))
			{
				hunter.setTitle(target);
				hunter.broadcastTitleInfo();
			}
		}
	}

	private static String wantedTitle(Player hunter)
	{
		final Clan clan = hunter.getClan();
		if (!PhantomHuntersConfig.HUNTER_CLANS || !PhantomHuntersConfig.HUNTER_CLAN_TITLES || !PhantomHunterClans.isHunterClan(clan) || !isHunter(hunter))
		{
			return null;
		}
		final boolean english = en();
		if (hunter.isClanLeader())
		{
			return stable(hunter, english ? TITLES_LEADER_EN : TITLES_LEADER);
		}
		final List<ClanMember> members = new ArrayList<>(clan.getMembers());
		members.removeIf(member -> member.getObjectId() == clan.getLeaderId());
		int total = 0;
		for (ClanMember member : members)
		{
			total += member.getLevel();
		}
		if (members.isEmpty())
		{
			return stable(hunter, english ? TITLES_MEMBER_EN : TITLES_MEMBER);
		}
		if (clan.getMembersCount() >= DEPUTY_MEMBERS)
		{
			members.sort(Comparator.comparingInt(ClanMember::getLevel).reversed().thenComparingInt(ClanMember::getObjectId));
			for (ClanMember member : members.subList(0, Math.min(DEPUTIES, members.size())))
			{
				if (member.getObjectId() == hunter.getObjectId())
				{
					return stable(hunter, english ? TITLES_DEPUTY_EN : TITLES_DEPUTY);
				}
			}
		}
		if ((hunter.getLevel() + ROOKIE_GAP) < (total / members.size()))
		{
			return stable(hunter, english ? TITLES_ROOKIE_EN : TITLES_ROOKIE);
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		switch (template.getRole())
		{
			case HEALER:
			{
				return stable(hunter, english ? TITLES_HEALER_EN : TITLES_HEALER);
			}
			case TANK:
			{
				return stable(hunter, english ? TITLES_TANK_EN : TITLES_TANK);
			}
			case BUFFER:
			{
				return stable(hunter, english ? TITLES_BUFFER_EN : TITLES_BUFFER);
			}
			default:
			{
				return stable(hunter, english ? TITLES_MEMBER_EN : TITLES_MEMBER);
			}
		}
	}

	/**
	 * @return титул из пула, один и тот же у этого охотника: титулы не мигают от прохода к проходу
	 */
	private static String stable(Player hunter, String[] pool)
	{
		return pool[Math.floorMod(hunter.getName().hashCode(), pool.length)];
	}

	// ---------- Альянсы ----------

	/**
	 * Разбор того, что сломалось (глава клана исчез, главы рассорились, война), потом — бросок на новый союз.
	 * @param roll бросать на новый союз
	 */
	private void alliances(long now, boolean roll)
	{
		final List<Clan> clans = hunterClans();
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Clan clan : clans)
		{
			final int allyId = clan.getAllyId();
			if ((allyId == 0) || (allyId == clan.getId()))
			{
				continue;
			}
			final Clan head = ClanTable.getInstance().getClan(allyId);
			if (head == null)
			{
				clearAlly(clan, 0, 0);
				LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " loses its alliance: the head clan is gone.");
				continue;
			}
			if (!PhantomHuntersConfig.HUNTER_CLAN_ALLIANCES)
			{
				continue;
			}
			final Player a = leaderOnline(head);
			final Player b = leaderOnline(clan);
			final boolean war = head.isAtWarWith(clan.getId()) || clan.isAtWarWith(head.getId());
			final boolean enemies = (a != null) && (b != null) && (relations.isEnemy(a, b) || relations.isEnemy(b, a));
			if (war || enemies)
			{
				breakAlly(clan, head, war, now);
			}
		}
		for (Clan clan : clans)
		{
			if ((clan.getAllyId() == clan.getId()) && (ClanTable.getInstance().getClanAllies(clan.getId()).size() <= 1))
			{
				dissolveAlly(clan);
				LOGGER.info(getClass().getSimpleName() + ": alliance of clan " + clan.getName() + " dissolved: nobody left in it.");
			}
		}
		if (roll && PhantomHuntersConfig.HUNTER_CLAN_ALLIANCES)
		{
			rollAlliance(clans, now);
		}
	}

	private void rollAlliance(List<Clan> clans, long now)
	{
		int alliances = 0;
		final List<Clan> present = new ArrayList<>();
		for (Clan clan : clans)
		{
			if (clan.getAllyId() == clan.getId())
			{
				alliances++;
			}
			if ((leaderOnline(clan) != null) && (clan.getLevel() >= ALLY_JOIN_LEVEL))
			{
				present.add(clan);
			}
		}
		Collections.shuffle(present);
		for (Clan founder : present)
		{
			final boolean isHead = founder.getAllyId() == founder.getId();
			if ((founder.getLevel() < ALLY_FOUND_LEVEL) || ((founder.getAllyId() != 0) && !isHead) || (!isHead && (alliances >= ((clans.size() / ALLY_CLANS_PER) + 1))))
			{
				continue;
			}
			for (Clan partner : present)
			{
				if ((partner == founder) || !canAlly(founder, partner, now))
				{
					continue;
				}
				if (Rnd.get(100.0) < (friends(founder, partner) ? ALLY_FRIEND_CHANCE : ALLY_STRANGER_CHANCE))
				{
					formAlly(founder, partner);
					return;
				}
			}
		}
	}

	private static boolean canAlly(Clan founder, Clan partner, long now)
	{
		if ((partner.getAllyId() != 0) || (partner.getAllyPenaltyExpiryTime() > now) || founder.isAtWarWith(partner.getId()) || partner.isAtWarWith(founder.getId()))
		{
			return false;
		}
		if ((founder.getAllyId() == 0) && (founder.getAllyPenaltyExpiryTime() > now))
		{
			return false;
		}
		final Player a = leaderOnline(founder);
		final Player b = leaderOnline(partner);
		return (a != null) && (b != null) && !PhantomRelations.getInstance().isEnemy(a, b) && !PhantomRelations.getInstance().isEnemy(b, a);
	}

	private static boolean friends(Clan first, Clan second)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Player a : onlineHunters(first))
		{
			for (Player b : onlineHunters(second))
			{
				if (relations.isFriend(a, b))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Союз по правилам Mobius: основать через {@code createAlly}, вступление — как RequestAnswerJoinAlly.
	 * @return союз есть и партнёр в нём
	 */
	private boolean formAlly(Clan founder, Clan partner)
	{
		final Player a = leaderOnline(founder);
		final Player b = leaderOnline(partner);
		if ((a == null) || (b == null))
		{
			return false;
		}
		final boolean created = founder.getAllyId() == 0;
		if (created)
		{
			founder.createAlly(a, allyName());
			if (founder.getAllyId() == 0)
			{
				LOGGER.info(getClass().getSimpleName() + ": Mobius does not let clan " + founder.getName() + " create an alliance.");
				return false;
			}
		}
		if (!founder.checkAllyJoinCondition(a, b))
		{
			LOGGER.info(getClass().getSimpleName() + ": Mobius does not let clan " + partner.getName() + " join alliance " + founder.getAllyName() + ".");
			if (created)
			{
				dissolveAlly(founder);
			}
			return false;
		}
		partner.setAllyId(founder.getAllyId());
		partner.setAllyName(founder.getAllyName());
		partner.setAllyPenaltyExpiryTime(0, 0);
		partner.changeAllyCrest(founder.getAllyCrestId(), true);
		partner.updateClanInDB();
		a.broadcastUserInfo();
		b.broadcastUserInfo();
		LOGGER.info(getClass().getSimpleName() + ": clan " + partner.getName() + " joined alliance " + founder.getAllyName() + " of clan " + founder.getName() + ".");
		final String text = created //
			? (en() ? "Clans " + founder.getName() + " and " + partner.getName() + " formed alliance " + founder.getAllyName() : "Кланы «" + founder.getName() + "» и «" + partner.getName() + "» заключили союз «" + founder.getAllyName() + "»") //
			: (en() ? "Clan " + partner.getName() + " joined alliance " + founder.getAllyName() : "Клан «" + partner.getName() + "» вошёл в союз «" + founder.getAllyName() + "»");
		PhantomNews.post("clan", text, a.getName(), a);
		PhantomChat.getInstance().hunterShout(a, String.format(pick(ALLY_SHOUT, ALLY_SHOUT_EN), partner.getName()));
		return true;
	}

	private static String allyName()
	{
		for (int i = 0; i < 20; i++)
		{
			final String name = PhantomHunterClans.fallbackName();
			if (!ClanTable.getInstance().isAllyExists(name))
			{
				return name;
			}
		}
		return "Ally" + Rnd.get(10000, 100000);
	}

	private void breakAlly(Clan clan, Clan head, boolean war, long now)
	{
		final String ally = clan.getAllyName();
		clearAlly(clan, now + (PlayerConfig.ALT_ALLY_JOIN_DAYS_WHEN_LEAVED * 86400000L), Clan.PENALTY_TYPE_CLAN_LEAVED);
		final Player leader = leaderOnline(clan);
		LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " left alliance " + ally + " of clan " + head.getName() + " (" + (war ? "war" : "leaders are enemies") + ").");
		final String text = en() //
			? "Clan " + clan.getName() + " left alliance " + ally + ": " + (war ? "they are at war" : "the leaders fell out") //
			: "Клан «" + clan.getName() + "» вышел из союза «" + ally + "»: " + (war ? "война между своими" : "главы рассорились");
		PhantomNews.post("clan", text, clan.getLeaderName(), leader);
		if (leader != null)
		{
			PhantomChat.getInstance().hunterShout(leader, String.format(pick(ALLY_BREAK_SHOUT, ALLY_BREAK_SHOUT_EN), head.getName()));
		}
	}

	private static void clearAlly(Clan clan, long penalty, int penaltyType)
	{
		clan.setAllyId(0);
		clan.setAllyName(null);
		clan.changeAllyCrest(0, true);
		clan.setAllyPenaltyExpiryTime(penalty, penaltyType);
		clan.updateClanInDB();
		final Player leader = leaderOnline(clan);
		if (leader != null)
		{
			leader.broadcastUserInfo();
		}
	}

	/**
	 * Распустить союз главного клана: глава в игре — штатно, со штрафом Mobius, нет — вручную, штрафа нет.
	 */
	private static void dissolveAlly(Clan head)
	{
		final Player leader = leaderOnline(head);
		if (leader != null)
		{
			head.dissolveAlly(leader);
			if (head.getAllyId() == 0)
			{
				return;
			}
		}
		for (Clan clan : ClanTable.getInstance().getClanAllies(head.getId()))
		{
			clearAlly(clan, 0, 0);
		}
	}

	/**
	 * @return кланы в одном союзе: войны между ними нет
	 */
	static boolean allied(Clan first, Clan second)
	{
		return (first.getAllyId() != 0) && (first.getAllyId() == second.getAllyId());
	}

	/**
	 * @return альянс клана для строки {@code .clans}
	 */
	static String allyNote(Clan clan)
	{
		return (clan.getAllyId() != 0) && (clan.getAllyName() != null) ? tr(", альянс ") + "«" + clan.getAllyName() + "»" : "";
	}

	// ---------- Драма ----------

	private void drama(long now)
	{
		final List<Clan> clans = hunterClans();
		for (Clan clan : clans)
		{
			if (leaderOnline(clan) == null)
			{
				_absent.putIfAbsent(clan.getId(), now);
			}
			else
			{
				_absent.remove(clan.getId());
			}
		}
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		final long last = variables.getLong(DRAMA_VARIABLE, 0);
		if (now < (last + (PhantomHuntersConfig.HUNTER_CLAN_DRAMA_HOURS * 3600000L)))
		{
			return;
		}
		Collections.shuffle(clans);
		for (Clan clan : clans)
		{
			if (tryCoup(clan, now) || tryCollapse(clan))
			{
				variables.set(DRAMA_VARIABLE, now);
				return;
			}
		}
	}

	private boolean tryCoup(Clan clan, long now)
	{
		if ((clan.getMembersCount() < COUP_MEMBERS) || !coupAllowed(clan, now))
		{
			return false;
		}
		final List<Player> online = onlineHunters(clan);
		final Player strong = pretender(clan, online, PRETENDER_LEVELS);
		final Player pretender = strong != null ? strong : pretender(clan, online, Integer.MAX_VALUE);
		if (pretender == null)
		{
			return false;
		}
		final Unrest unrest = unrest(clan, pretender, online, now);
		if ((unrest.score() <= 0) || ((strong == null) && (unrest.score() < UNREST_SURE)))
		{
			return false;
		}
		if ((unrest.score() <= 0) || ((unrest.score() < UNREST_SURE) && (Rnd.get(100.0) >= (COUP_CHANCE * unrest.score()))))
		{
			return false;
		}
		final Player exLeader = leaderOnline(clan);
		coup(clan, pretender, unrest.why(), now);
		if (Rnd.get(100) < SPLIT_CHANCE)
		{
			split(clan, exLeader, pretender, false);
		}
		return true;
	}

	private static boolean coupAllowed(Clan clan, long now)
	{
		return now >= (GlobalVariablesManager.getInstance().getLong(COUP_VARIABLE + clan.getId(), 0) + COUP_GAP);
	}

	/**
	 * @param levels насколько претендент может быть младше главы
	 * @return самый уважаемый охотник клана в игре: благодарность соклановцев минус обида, лидерство — в придачу
	 */
	private static Player pretender(Clan clan, List<Player> online, int levels)
	{
		final ClanMember head = clan.getLeader();
		final PhantomRelations relations = PhantomRelations.getInstance();
		Player best = null;
		double bestScore = Double.NEGATIVE_INFINITY;
		for (Player candidate : online)
		{
			if (candidate.isClanLeader() || candidate.isDead() || ((head.getLevel() - candidate.getLevel()) > levels))
			{
				continue;
			}
			double score = 2 * PhantomHunterClans.getInstance().traits(candidate).leadership();
			for (Player mate : online)
			{
				if (mate != candidate)
				{
					score += relations.gratitude(mate, candidate) - relations.grudge(mate, candidate);
				}
			}
			if (score > bestScore)
			{
				bestScore = score;
				best = candidate;
			}
		}
		return best;
	}

	/**
	 * Смута: глава — враг половины клана — {@value #UNREST_SURE}, без броска. Иначе баллы: глава пропал на {@value #ABSENT_TIME} мс и претендент с ним в ссоре — по 2,
	 * глава слабее на {@value #LEVEL_GAP} уровней и репутация в минусе — по 1.
	 */
	private Unrest unrest(Clan clan, Player pretender, List<Player> online, long now)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		final Player leader = leaderOnline(clan);
		final ClanMember head = clan.getLeader();
		int score = 0;
		final List<String> why = new ArrayList<>();
		if (leader != null)
		{
			int enemies = 0;
			for (Player mate : online)
			{
				if ((mate != leader) && relations.isEnemy(mate, leader))
				{
					enemies++;
				}
			}
			if ((enemies >= 2) && ((enemies * 2) >= (online.size() - 1)))
			{
				return new Unrest(UNREST_SURE, "leader is an enemy of half the clan");
			}
			if (relations.isEnemy(pretender, leader))
			{
				score += 2;
				why.add("enemies");
			}
		}
		else
		{
			final Long since = _absent.get(clan.getId());
			if ((since != null) && ((now - since) >= ABSENT_TIME))
			{
				score += 2;
				why.add("leader is away");
			}
		}
		if ((pretender.getLevel() - head.getLevel()) >= LEVEL_GAP)
		{
			score++;
			why.add("leader is weaker");
		}
		if (clan.getReputationScore() < 0)
		{
			score++;
			why.add("reputation below zero");
		}
		return new Unrest(score, String.join(", ", why));
	}

	/**
	 * Власть переходит штатно ({@code setNewLeader}): клан у живого соклановца увидит окно клана и системное сообщение Mobius. Бывший глава запоминает обиду.
	 */
	private void coup(Clan clan, Player pretender, String why, long now)
	{
		final String old = clan.getLeaderName();
		final Player exLeader = leaderOnline(clan);
		clan.setNewLeader(clan.getClanMember(pretender.getObjectId()));
		GlobalVariablesManager.getInstance().set(COUP_VARIABLE + clan.getId(), now);
		_absent.remove(clan.getId());
		if (exLeader != null)
		{
			PhantomRelations.getInstance().record(exLeader, pretender, PhantomRelations.Kind.INSULTED);
			PhantomRelations.getInstance().record(exLeader, pretender, PhantomRelations.Kind.ATTACKED);
		}
		titles(onlineHunters(clan));
		LOGGER.info(getClass().getSimpleName() + ": coup in clan " + clan.getName() + ": " + pretender.getName() + " replaced " + old + " (" + why + ").");
		final String text = en() //
			? "Coup in clan " + clan.getName() + ": " + pretender.getName() + " overthrew " + old //
			: "Переворот в клане «" + clan.getName() + "»: " + pretender.getName() + " сместил" + (pretender.getAppearance().isFemale() ? "а " : " ") + old;
		PhantomNews.post("clan", text, pretender.getName(), pretender);
		PhantomChat.getInstance().hunterShout(pretender, String.format(pick(COUP_SHOUT, COUP_SHOUT_EN), old, clan.getName()));
	}

	/**
	 * Раскол: бывший глава выходит, основывает клан и уводит друзей — друг ему, не друг новому главе, верность не держит; не больше половины.
	 * @param force без броска верности (самопроверка)
	 * @return новый клан или {@code null}
	 */
	private Clan split(Clan clan, Player exLeader, Player newLeader, boolean force)
	{
		if ((exLeader == null) || !exLeader.isOnline() || (exLeader.getClan() != clan) || exLeader.isClanLeader())
		{
			return null;
		}
		final PhantomHunterClans clans = PhantomHunterClans.getInstance();
		final PhantomRelations relations = PhantomRelations.getInstance();
		final List<Player> followers = new ArrayList<>();
		final int limit = clan.getMembersCount() / 2;
		for (Player mate : onlineHunters(clan))
		{
			if ((mate != exLeader) && (mate != newLeader) && (followers.size() < limit) && relations.isFriend(mate, exLeader) && !relations.isFriend(mate, newLeader) && (force || (Rnd.get(1.0) >= clans.traits(mate).loyalty())))
			{
				followers.add(mate);
			}
		}
		PhantomChat.getInstance().hunterShout(exLeader, String.format(pick(SPLIT_SHOUT, SPLIT_SHOUT_EN), clan.getName()));
		PhantomCompat.leaveClan(exLeader);
		exLeader.setClanJoinExpiryTime(0);
		final Clan created = clans.found(exLeader, null, "split from " + clan.getName());
		if (created == null)
		{
			return null;
		}
		for (Player mate : followers)
		{
			PhantomCompat.leaveClan(mate);
			mate.setClanJoinExpiryTime(0);
			clans.join(created, exLeader, mate, true);
			ThreadPool.execute(mate::storeMe);
		}
		titles(onlineHunters(created));
		LOGGER.info(getClass().getSimpleName() + ": split of clan " + clan.getName() + ": " + exLeader.getName() + " founded " + created.getName() + " with " + followers.size() + " followers.");
		final String text = en() //
			? "Split in clan " + clan.getName() + ": " + exLeader.getName() + " left and took " + followers.size() + " along to found " + created.getName() //
			: "Раскол в клане «" + clan.getName() + "»: " + exLeader.getName() + " уш" + (exLeader.getAppearance().isFemale() ? "ла" : "ёл") + " и основал" + (exLeader.getAppearance().isFemale() ? "а" : "") + " «" + created.getName() + "», за ним ушло " + followers.size();
		PhantomNews.post("clan", text, exLeader.getName(), exLeader);
		return created;
	}

	/**
	 * Клан с живым не разваливается: живого из клана за его спиной не выкидываем.
	 */
	private boolean tryCollapse(Clan clan)
	{
		if ((clan.getLevel() >= COLLAPSE_MAX_LEVEL) || hasLiving(clan))
		{
			return false;
		}
		final String why = collapseReason(clan);
		if ((why == null) || (Rnd.get(100) >= COLLAPSE_CHANCE))
		{
			return false;
		}
		collapse(clan, why);
		return true;
	}

	/**
	 * @return причина развала: пары врагов внутри клана или репутация в минусе; {@code null} — причины нет
	 */
	private static String collapseReason(Clan clan)
	{
		final List<Player> online = onlineHunters(clan);
		final PhantomRelations relations = PhantomRelations.getInstance();
		int pairs = 0;
		for (int i = 0; i < online.size(); i++)
		{
			for (int j = i + 1; j < online.size(); j++)
			{
				if (relations.isEnemy(online.get(i), online.get(j)) || relations.isEnemy(online.get(j), online.get(i)))
				{
					pairs++;
				}
			}
		}
		if (pairs >= Math.max(2, online.size() / 4))
		{
			return en() ? "everyone fell out" : "в нём все перессорились";
		}
		return clan.getReputationScore() < 0 ? (en() ? "reputation hit the bottom" : "репутация ушла в минус") : null;
	}

	private void collapse(Clan clan, String why)
	{
		final Player leader = leaderOnline(clan);
		final String name = clan.getName();
		final String head = clan.getLeaderName();
		if (clan.getAllyId() == clan.getId())
		{
			dissolveAlly(clan);
		}
		else if (clan.getAllyId() != 0)
		{
			clearAlly(clan, 0, 0);
		}
		if (leader != null)
		{
			PhantomChat.getInstance().hunterShout(leader, String.format(pick(COLLAPSE_SHOUT, COLLAPSE_SHOUT_EN), name));
		}
		PhantomNews.post("clan", (en() ? "Clan " + name + " fell apart: " : "Клан «" + name + "» развалился: ") + why, head, leader);
		LOGGER.info(getClass().getSimpleName() + ": clan " + name + " of " + head + " collapsed (" + why + ").");
		ClanTable.getInstance().destroyClan(clan.getId());
	}

	// ---------- Топ ----------

	/**
	 * @return сила клана: уровень важнее всего, потом состав и репутация
	 */
	static int power(Clan clan)
	{
		return (clan.getLevel() * 100) + (clan.getMembersCount() * 5) + (clan.getReputationScore() / 50);
	}

	/**
	 * Окно {@code .clans top}: все кланы, и охотников, и игроков, по силе. Ссылки, а не кнопки: окно одно на все хроники, а у Interlude нет текстур кнопок поздних клиентов.
	 * @param viewer кто смотрит
	 * @param requested страница с 0
	 * @return окно целиком
	 */
	String topPage(Player viewer, int requested)
	{
		final List<Clan> clans = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (!clan.getName().startsWith(PhantomClans.TEST_CLAN))
			{
				clans.add(clan);
			}
		}
		clans.sort(Comparator.comparingInt(PhantomClanPower::power).reversed().thenComparing(Clan::getName, String.CASE_INSENSITIVE_ORDER));
		final int pages = Math.max(1, (clans.size() + TOP_PAGE - 1) / TOP_PAGE);
		final int page = Math.max(0, Math.min(requested, pages - 1));
		final StringBuilder sb = new StringBuilder(4096);
		sb.append("<html><title>").append(tr("Топ кланов")).append("</title><body>");
		if (clans.isEmpty())
		{
			sb.append(tr("Кланов пока нет."));
		}
		for (int i = page * TOP_PAGE; i < Math.min(clans.size(), (page + 1) * TOP_PAGE); i++)
		{
			final Clan clan = clans.get(i);
			final String[] war = PhantomClanWars.getInstance().war(clan);
			sb.append("<font color=\"").append(viewer.getClan() == clan ? "00FF00" : "LEVEL").append("\">").append(i + 1).append(". ").append(clan.getName()).append("</font> — ").append(clan.getLevel()).append(tr(" ур., ")).append(clan.getMembersCount()).append(tr(" чел. (в игре ")).append(clan.getOnlineMembers(0).size()).append(")<br1>");
			sb.append("<font color=\"909090\">").append(tr("глава ")).append(clan.getLeaderName()).append(tr(", репутация ")).append(clan.getReputationScore()).append(allyNote(clan)).append(war != null ? tr(", воюет с ") + "«" + war[0] + "»" : "").append("</font><br>");
		}
		if (pages > 1)
		{
			sb.append("<center>");
			if (page > 0)
			{
				sb.append("<a action=\"bypass voice .clans top ").append(page - 1).append("\">").append(tr("Назад")).append("</a> ");
			}
			sb.append(page + 1).append(" / ").append(pages);
			if ((page + 1) < pages)
			{
				sb.append(" <a action=\"bypass voice .clans top ").append(page + 1).append("\">").append(tr("Дальше")).append("</a>");
			}
			sb.append("</center>");
		}
		return sb.append("</body></html>").toString();
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка в конце CLANLIFE ({@code HuntersSelfTest}, только на копии базы): кланы, союзы и обиды ложатся в базу. Два клана охотников живут по сценарию: титулы, приглашение
	 * и его паузы, союз и его разрыв, переворот, раскол, топ, развал. Итог — строка {@code SelfTest CLANPOWER: N of M}, страница топа — {@code log/L2HubClanTop.html}.
	 */
	void selfTest()
	{
		synchronized (PhantomHunterClans.getInstance())
		{
			final String tag = getClass().getSimpleName() + ": SelfTest CLANPOWER: ";
			final List<String> failed = new ArrayList<>();
			final int[] total = new int[1];
			try
			{
				selfTestRun(tag, total, failed);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
				failed.add("exception " + e);
			}
			LOGGER.info(tag + (total[0] - failed.size()) + " of " + total[0] + (failed.isEmpty() ? "" : "; FAILED: " + String.join("; ", failed)) + ".");
		}
	}

	private static void check(int[] total, List<String> failed, boolean ok, String what)
	{
		total[0]++;
		if (!ok)
		{
			failed.add(what);
		}
	}

	private void selfTestRun(String tag, int[] total, List<String> failed)
	{
		final PhantomHunterClans clans = PhantomHunterClans.getInstance();
		final PhantomRelations relations = PhantomRelations.getInstance();
		final long now = System.currentTimeMillis();
		for (String[] pool : ALL_TITLES)
		{
			for (String title : pool)
			{
				check(total, failed, title.length() <= 16, "title too long: " + title);
			}
		}

		final List<Player> free = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if (isHunter(player) && !player.isDead() && PhantomHunterClans.canFound(player, now))
			{
				free.add(player);
			}
		}
		free.sort(Comparator.comparingInt((Player p) -> -p.getLevel()));
		if (free.size() < 9)
		{
			failed.add("needs nine free hunters, found " + free.size());
			total[0]++;
			return;
		}
		final Player a = free.get(0);
		final Player a1 = free.get(1);
		final Player a2 = free.get(2);
		final Player a3 = free.get(3);
		final Player a4 = free.get(4);
		final Player b = free.get(5);
		final Player b1 = free.get(6);
		final Player other = free.get(7);
		final Player living = free.get(8);

		// Два клана: A — глава и четверо, B — глава и один.
		final Clan clanA = clans.found(a, null, "self-test");
		final Clan clanB = clans.found(b, null, "self-test");
		check(total, failed, (clanA != null) && (clanB != null), "clans founded");
		if ((clanA == null) || (clanB == null))
		{
			return;
		}
		for (Player mate : List.of(a1, a2, a3, a4))
		{
			clans.join(clanA, a, mate, false);
		}
		clans.join(clanB, b, b1, false);
		clanA.changeLevel(5);
		clanB.changeLevel(5);
		check(total, failed, clanA.getMembersCount() == 5, "clan A has " + clanA.getMembersCount());

		// Титулы: глава, двое замов; свой титул игрока не трогаем, снимаем только свои.
		a4.setTitle("Own title");
		other.setTitle(TITLES_MEMBER[0]);
		titles(List.of(a, a1, a2, a3, a4, b, b1, other));
		LOGGER.info(tag + "titles: " + a.getName() + " «" + a.getTitle() + "», " + a1.getName() + " «" + a1.getTitle() + "», " + a2.getName() + " «" + a2.getTitle() + "», " + a3.getName() + " «" + a3.getTitle() + "», " + b1.getName() + " «" + b1.getTitle() + "».");
		final boolean english = en();
		check(total, failed, List.of(english ? TITLES_LEADER_EN : TITLES_LEADER).contains(a.getTitle()), "leader title " + a.getTitle());
		final List<String> deputy = List.of(english ? TITLES_DEPUTY_EN : TITLES_DEPUTY);
		check(total, failed, deputy.contains(a1.getTitle()) || deputy.contains(a2.getTitle()) || deputy.contains(a3.getTitle()), "deputy title");
		check(total, failed, !a3.getTitle().isEmpty() && OUR_TITLES.contains(a3.getTitle()), "member title " + a3.getTitle());
		check(total, failed, "Own title".equals(a4.getTitle()), "own title replaced by " + a4.getTitle());
		check(total, failed, other.getTitle().isEmpty(), "our title stays without a clan: " + other.getTitle());
		a4.setTitle("");

		// Приглашение: другой охотник играет живого.
		final int id = living.getObjectId();
		_seen.put(id, now);
		check(total, failed, "just came".equals(pause(clanA, living, now)), "fresh player: " + pause(clanA, living, now));
		_seen.put(id, now - INVITE_SEEN - 1000);
		check(total, failed, pause(clanA, living, now) == null, "player can be invited: " + pause(clanA, living, now));
		check(total, failed, !free(a1, now), "a clan member is not free");
		relations.record(a, living, PhantomRelations.Kind.REVIVED);
		relations.record(a, living, PhantomRelations.Kind.SAVED);
		check(total, failed, friendOfClan(clanA, living), "friend of the clan");
		for (int i = 0; (i < 8) && !relations.isEnemy(a2, living); i++)
		{
			relations.record(a2, living, PhantomRelations.Kind.KILLED);
		}
		check(total, failed, String.valueOf(pause(clanA, living, now)).startsWith("enemy of"), "enemy of a member: " + pause(clanA, living, now));
		for (int i = 0; (i < 8) && relations.isEnemy(a2, living); i++)
		{
			relations.record(a2, living, PhantomRelations.Kind.REVIVED);
		}
		offer(clanA, a, living, true, now);
		check(total, failed, "player gap".equals(pause(clanB, living, now)), "player gap: " + pause(clanB, living, now));
		_playerGaps.get(id)[0] = 0;
		check(total, failed, "pair gap".equals(pause(clanA, living, now)), "pair gap: " + pause(clanA, living, now));
		_pairs.clear();
		offer(clanA, a, living, true, now);
		check(total, failed, _playerGaps.get(id)[0] == (now + (INVITE_PLAYER_GAP * 2)), "backoff doubles");
		_playerGaps.remove(id);
		_pairs.clear();
		_seen.remove(id);
		final boolean window = PhantomCompat.askJoinClan(a, living);
		check(total, failed, window || a.isProcessingRequest() || living.isProcessingRequest(), "invite window");
		if (window)
		{
			a.getRequest().onRequestResponse();
		}

		// Союз и его разрыв; войны между союзниками нет.
		relations.record(a, b, PhantomRelations.Kind.REVIVED);
		check(total, failed, canAlly(clanA, clanB, now), "clans can ally");
		check(total, failed, formAlly(clanA, clanB), "alliance formed");
		final String allyName = clanA.getAllyName();
		check(total, failed, (clanA.getAllyId() == clanA.getId()) && (clanB.getAllyId() == clanA.getId()) && (allyName != null) && allyName.equals(clanB.getAllyName()), "alliance fields");
		check(total, failed, allied(clanA, clanB), "allied");
		check(total, failed, allyNote(clanB).contains(String.valueOf(allyName)), ".clans note: " + allyNote(clanB));
		LOGGER.info(tag + "alliance " + allyName + ": " + clanA.getName() + " and " + clanB.getName() + ".");
		final String page = topPage(a, 0);
		check(total, failed, page.contains(clanA.getName()) && page.contains(clanB.getName()) && page.contains(String.valueOf(allyName)), "top page lists the clans and the alliance");
		check(total, failed, topPage(a, 999).endsWith("</body></html>") && (page.length() < 8000), "top page size " + page.length());
		try
		{
			Files.write(Path.of("log", "L2HubClanTop.html"), page.getBytes(StandardCharsets.UTF_8));
		}
		catch (Exception e)
		{
			LOGGER.warning(tag + "could not write the page: " + e.getMessage());
		}
		for (int i = 0; (i < 8) && !relations.isEnemy(b, a); i++)
		{
			relations.record(b, a, PhantomRelations.Kind.KILLED);
		}
		check(total, failed, relations.isEnemy(b, a), "leaders are enemies");
		check(total, failed, !canAlly(clanA, clanB, now), "enemies do not ally");
		alliances(now, false);
		check(total, failed, (clanB.getAllyId() == 0) && (clanB.getAllyPenaltyType() == Clan.PENALTY_TYPE_CLAN_LEAVED) && (clanB.getAllyPenaltyExpiryTime() > now), "clan left the alliance with the penalty");
		check(total, failed, clanA.getAllyId() == 0, "lonely alliance dissolved");
		clanB.setAllyPenaltyExpiryTime(0, 0);

		// Смута и переворот.
		_absent.remove(clanA.getId());
		final Unrest calm = unrest(clanA, a1, onlineHunters(clanA), now);
		LOGGER.info(tag + "calm clan: unrest " + calm.score() + " (" + calm.why() + "), pretender " + pretender(clanA, onlineHunters(clanA), Integer.MAX_VALUE) + ".");
		check(total, failed, pretender(clanA, onlineHunters(clanA), Integer.MAX_VALUE) != null, "there is a pretender");
		check(total, failed, calm.score() < UNREST_SURE, "calm clan: " + calm);
		for (Player mate : List.of(a1, a2))
		{
			for (int i = 0; (i < 8) && !relations.isEnemy(mate, a); i++)
			{
				relations.record(mate, a, PhantomRelations.Kind.KILLED);
			}
		}
		final Unrest storm = unrest(clanA, a1, onlineHunters(clanA), now);
		check(total, failed, storm.score() == UNREST_SURE, "leader is an enemy of half the clan: " + storm);
		check(total, failed, coupAllowed(clanA, now), "coup allowed");
		coup(clanA, a1, storm.why(), now);
		check(total, failed, a1.isClanLeader() && !a.isClanLeader() && (clanA.getLeaderId() == a1.getObjectId()) && (a.getClan() == clanA), "power changed hands");
		check(total, failed, !coupAllowed(clanA, now), "second coup waits");
		check(total, failed, relations.grudge(a, a1) > 0, "ex-leader remembers");
		check(total, failed, List.of(english ? TITLES_LEADER_EN : TITLES_LEADER).contains(a1.getTitle()), "new leader title " + a1.getTitle());

		// Раскол: a3 — друг бывшего главы, a2 — не друг.
		relations.record(a3, a, PhantomRelations.Kind.REVIVED);
		relations.record(a3, a, PhantomRelations.Kind.SAVED);
		final Clan created = split(clanA, a, a1, true);
		check(total, failed, created != null, "split founded a clan");
		if (created == null)
		{
			return;
		}
		check(total, failed, (a.getClan() == created) && a.isClanLeader(), "ex-leader leads the new clan");
		check(total, failed, a3.getClan() == created, "friend followed: " + (a3.getClan() == null ? "none" : a3.getClan().getName()));
		check(total, failed, a2.getClan() == clanA, "not a friend stayed");

		// Развал: союз главного клана разбирается, клан исчезает, член без клана.
		created.changeLevel(5);
		check(total, failed, formAlly(created, clanB) && allied(created, clanB), "second alliance");
		final int createdId = created.getId();
		collapse(created, "self-test");
		check(total, failed, ClanTable.getInstance().getClan(createdId) == null, "clan collapsed");
		check(total, failed, a.getClan() == null, "leader has no clan");
		check(total, failed, a3.getClan() == null, "member has no clan");
		check(total, failed, clanB.getAllyId() == 0, "alliance of the collapsed clan is gone");
		clanB.addReputationScore(-100);
		check(total, failed, collapseReason(clanB) != null, "reputation below zero is a reason");
		clanB.addReputationScore(100);
		check(total, failed, !hasLiving(clanB), "hunter clan has no living members");

		// Уборка.
		for (Clan clan : List.of(clanA, clanB))
		{
			ClanTable.getInstance().destroyClan(clan.getId());
		}
		titles(List.of(a, a1, a2, a3, a4, b, b1));
		check(total, failed, a2.getTitle().isEmpty() && a1.getTitle().isEmpty(), "titles gone with the clan");
		for (Player player : List.of(a, a1, a2, a3, a4, b, b1, other, living))
		{
			player.setClanJoinExpiryTime(0);
			player.setClanCreateExpiryTime(0);
			ThreadPool.execute(player::storeMe);
		}
	}

	static PhantomClanPower getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClanPower INSTANCE = new PhantomClanPower();
	}
}
