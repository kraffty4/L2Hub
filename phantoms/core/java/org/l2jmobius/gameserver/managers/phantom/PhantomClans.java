package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;

/**
 * L2Hub: охотник в клане живого игрока (docs/PHANTOMS.md, раздел 4.41, части 2 и 3; {@code HunterClanJoin} и {@code HunterClanReputation} в PhantomHunters.ini).
 * <ul>
 * <li>вступление — приглашение из окна клана, как живому (врезка в {@code RequestJoinPledge}: окна «вступить в клан?» у фантома нет, отвечает сервер):
 * друг вступает, враг и незнакомый отказывают со словами; охотников в клане — не больше {@code HunterClanJoinMax} (0 — без предела);</li>
 * <li>в клане — качается сам, виден в списке клана в сети (врезка в {@code ClanMember.isOnline}), отвечает в клан-чате ({@link PhantomChat#onClanChat});</li>
 * <li>уход — стал врагом живого соклановца ({@link #onGrudge}): слова в клан-чат и выход, штраф на вступление — только ему;</li>
 * <li>охотник из клана охотников (раздел 4.50) — уходит к живому другу, только если благодарен ему сверх своей верности ({@link #beforeInvite});
 * одиночка в кланы не идёт;</li>
 * <li>репутация — рейд охотников убил РБ, эпика или суперчемпиона ({@link #raidKilled}): клану живого игрока из рейда — сполна, клану охотников рейда без его
 * живых — {@code HunterClanReputationAway} %; клану охотников с 5-го уровня — столько же и без живых в игре (раздел 4.50).</li>
 * </ul>
 */
public final class PhantomClans
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClans.class.getName());

	/** Пригласивший дальше — ответ шёпотом, ближе — вслух рядом. */
	private static final int SAY_RANGE = 1000;
	/** Повтор реплики о клане тому же игроку — не раньше. */
	private static final long SAY_GAP = 30000;
	/** Живой игрок рейда получает репутацию, если при убийстве был не дальше от босса. */
	private static final int RAID_RANGE = 3000;
	/** Репутация растёт с уровнем босса: на этом — «от» из {@code HunterClanRaidReputation}, на {@value #LEVEL_TO} и выше — «до». */
	private static final int LEVEL_FROM = 20;
	private static final int LEVEL_TO = 80;
	/** Самопроверка CLAN: имя тестового клана; занято — с номером. Такой клан — не клан охотников ({@link PhantomHunterClans#isHunterClan}). */
	static final String TEST_CLAN = "L2HubTest";
	/** Самопроверка CLAN: враг уходит из клана через столько после вступления — время ответить в клан-чате. */
	private static final long TEST_LEAVE_DELAY = 30000;

	/** Самопроверка CLAN: охотник, который играет живого игрока. */
	private volatile int _testPlayerId;

	private record Answer(boolean accept, String why, String what, String fallback)
	{
	}

	private PhantomClans()
	{
	}

	// ---------- Вступление ----------

	/**
	 * Живой игрок позвал фантома в клан (врезка в {@code RequestJoinPledge}; условия Mobius — права, лимит членов, штраф после ухода — уже проверены).
	 * @param requestor кто позвал
	 * @param phantom фантом
	 * @param pledgeType подразделение клана
	 */
	public synchronized void onInvited(Player requestor, Player phantom, int pledgeType)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		final Clan clan = requestor.getClan();
		if ((template == null) || (clan == null))
		{
			PhantomCompat.sendWrongInviteTarget(requestor);
			return;
		}
		// Напарники из .buddy и секретный персонаж в кланы не вступают.
		if (!template.isHunter() || PhantomManager.getInstance().isSecret(phantom))
		{
			requestor.sendMessage(phantom.getName() + " в кланы не вступает.");
			return;
		}
		if (!PhantomHuntersConfig.HUNTER_CLAN_JOIN)
		{
			requestor.sendMessage(phantom.getName() + " в кланы не вступает: охотники в клане выключены (HunterClanJoin в PhantomHunters.ini).");
			return;
		}
		final int hunters = hunters(clan);
		if ((PhantomHuntersConfig.HUNTER_CLAN_JOIN_MAX > 0) && (hunters >= PhantomHuntersConfig.HUNTER_CLAN_JOIN_MAX))
		{
			requestor.sendMessage("В клане уже охотников: " + hunters + " — больше не берут (HunterClanJoinMax в PhantomHunters.ini).");
			return;
		}

		final Answer answer = decide(phantom, requestor, clan);
		LOGGER.info(getClass().getSimpleName() + ": " + requestor.getName() + " invites " + phantom.getName() + " (" + phantom.getLevel() + ") to clan " + clan.getName() + " — " + (answer.accept ? "joined" : "declined") + " (" + answer.why + ").");
		if (!answer.accept)
		{
			PhantomChat.getInstance().hunterEvent(phantom, requestor, "CLAN", answer.what, answer.fallback, SAY_GAP, phantom.calculateDistance2D(requestor) > SAY_RANGE);
			requestor.sendMessage(phantom.getName() + " отказал" + (template.isFemale() ? "а" : "") + " вступить в клан.");
			return;
		}

		PhantomCompat.joinClan(requestor, phantom, pledgeType);
		ThreadPool.execute(phantom::storeMe);
		// Приветствие — в клан-чат: его видят все соклановцы в сети.
		PhantomChat.getInstance().clanEvent(phantom, requestor, clan, "CLAN_JOIN", answer.what, answer.fallback, SAY_GAP);
	}

	/**
	 * Живой игрок зовёт охотника, который уже в клане охотников (врезка в {@code RequestJoinPledge} до условий Mobius, раздел 4.50). Глава своего клана
	 * и верный своим отказывают со словами; друг, благодарный сверх верности ({@link PhantomHunterClans#switchGratitude}: от 3 до 9), уходит из своего клана
	 * без штрафа — дальше решает {@link #onInvited}, как свободный.
	 * @param requestor кто позвал
	 * @param phantom фантом
	 * @return {@code true} — отказал сам, приглашение дальше не идёт; {@code false} — дальше по-обычному (условия Mobius и {@link #onInvited})
	 */
	public synchronized boolean beforeInvite(Player requestor, Player phantom)
	{
		final Clan own = phantom.getClan();
		final Clan clan = requestor.getClan();
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if ((own == null) || (clan == null) || (own == clan) || (template == null) || !template.isHunter() || !PhantomHuntersConfig.HUNTER_CLAN_JOIN || !PhantomHunterClans.isHunterClan(own))
		{
			return false;
		}

		final PhantomRelations relations = PhantomRelations.getInstance();
		final double gratitude = relations.gratitude(phantom, requestor);
		final double need = PhantomHunterClans.getInstance().switchGratitude(phantom);
		final String who = PhantomHunterManager.describe(requestor) + " зовёт тебя в свой клан «" + clan.getName() + "», а ты в клане охотников «" + own.getName() + "»";
		final Answer answer;
		if (phantom.isClanLeader())
		{
			answer = new Answer(false, "leader", who + ", и ты его глава — откажи: своих не бросишь, а кто хочет — пусть сам идёт к вам", "я глава " + own.getName() + ", своих не брошу) хочешь — давай к нам");
		}
		else if (!relations.isFriend(phantom, requestor) || (gratitude < need))
		{
			answer = new Answer(false, String.format(Locale.ROOT, "loyal: gratitude %.1f of %.1f", gratitude, need), who + " — откажи: ты своим верен" + (relations.isFriend(phantom, requestor) ? ", хоть он тебе и друг" : ""), "не, я в " + own.getName() + ", своих не бросаю");
		}
		else if (clan.getSubPledgeMembersCount(0) >= clan.getMaxNrOfMembers(0))
		{
			answer = new Answer(false, "full", who + "; ты бы и ушёл к нему, но у него в клане нет мест — скажи об этом", "я б пошёл, но у вас мест нет)");
		}
		else
		{
			PhantomCompat.leaveClan(phantom);
			// Уходит к другу — штраф на вступление не нужен.
			phantom.setClanJoinExpiryTime(0);
			ThreadPool.execute(phantom::storeMe);
			LOGGER.info(getClass().getSimpleName() + ": " + phantom.getName() + " leaves hunter clan " + own.getName() + " for " + requestor.getName() + "'s clan " + clan.getName() + String.format(Locale.ROOT, " (gratitude %.1f of %.1f).", gratitude, need));
			PhantomNews.post("clan", phantom.getName() + " ушёл" + (template.isFemale() ? "а" : "") + " из клана охотников «" + own.getName() + "» в клан «" + clan.getName() + "» к " + requestor.getName(), phantom.getName(), phantom);
			return false;
		}
		LOGGER.info(getClass().getSimpleName() + ": " + requestor.getName() + " invites " + phantom.getName() + " of hunter clan " + own.getName() + " — stays (" + answer.why + ").");
		PhantomChat.getInstance().hunterEvent(phantom, requestor, "CLAN", answer.what, answer.fallback, SAY_GAP, phantom.calculateDistance2D(requestor) > SAY_RANGE);
		requestor.sendMessage(phantom.getName() + " отказал" + (template.isFemale() ? "а" : "") + " уйти из своего клана «" + own.getName() + "».");
		return true;
	}

	private static Answer decide(Player hunter, Player player, Clan clan)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		final String who = PhantomHunterManager.describe(player) + " зовёт тебя в свой клан «" + clan.getName() + "»";
		if (relations.isEnemy(hunter, player))
		{
			return new Answer(false, "enemy", who + "; он твой враг — ты резко отказываешь: с ним в одном клане не будешь", "в твой клан? после всего? не смеши");
		}
		// Одиночка (раздел 4.50) в кланы не идёт — и к друзьям.
		if (PhantomHunterClans.getInstance().isLoner(hunter))
		{
			return new Answer(false, "loner", who + "; ты одиночка и ни в какие кланы не вступаешь" + (relations.isFriend(hunter, player) ? " — даже к друзьям, без обид" : "") + " — откажи в своей манере", "не, я соло. кланы не моё");
		}
		if (relations.isFriend(hunter, player))
		{
			return new Answer(true, "friend", who + "; он твой друг — ты соглашаешься и уже вступил: поздоровайся с кланом, скажи, что качаться будешь сам, а на рейды пусть зовут", "всем привет) качаюсь сам, но на рб зовите — приду");
		}
		return new Answer(false, "stranger", who + "; ты его почти не знаешь — вежливо отказываешь: в клан идёшь только к тем, с кем уже качался и кто тебя выручал в бою", "не, к незнакомым в клан не иду) покачаемся вместе — тогда поговорим");
	}

	/**
	 * @param clan клан
	 * @return сколько в нём охотников — в мире и вне его
	 */
	static int hunters(Clan clan)
	{
		int count = 0;
		for (ClanMember member : clan.getMembers())
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(member.getName());
			if ((template != null) && template.isHunter())
			{
				count++;
			}
		}
		return count;
	}

	// ---------- Уход ----------

	/**
	 * Записана обида ({@link PhantomRelations#record}): охотник стал врагом живого соклановца — говорит об этом в клан-чат и уходит из клана.
	 * @param hunter охотник
	 * @param player кто его обидел
	 */
	public void onGrudge(Player hunter, Player player)
	{
		final Clan clan = hunter.getClan();
		if ((clan == null) || (player.getClan() != clan) || !living(player) || hunter.isClanLeader() || !PhantomRelations.getInstance().isEnemy(hunter, player))
		{
			return;
		}
		synchronized (this)
		{
			if (hunter.getClan() != clan)
			{
				return;
			}
			PhantomCompat.leaveClan(hunter);
		}
		ThreadPool.execute(hunter::storeMe);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " leaves clan " + clan.getName() + ": " + player.getName() + " is an enemy now.");
		PhantomChat.getInstance().clanEvent(hunter, player, clan, "CLAN_LEAVE", PhantomHunterManager.describe(player) + " из твоего клана «" + clan.getName() + "» стал твоим врагом, и ты уходишь из клана — скажи соклановцам коротко, почему", "всё, я из клана. с " + player.getName() + " в одном клане не буду", SAY_GAP);
	}

	// ---------- Репутация ----------

	/**
	 * Рейд охотников убил босса ({@link PhantomRaid}): клану каждого живого игрока из рейда, кто был рядом с боссом, — репутация сполна;
	 * клану охотников рейда без его живых в рейде — {@code HunterClanReputationAway} %, если кто-то из живых этого клана в игре. Клану — раз за босса.
	 * @param boss босс; {@code null} — самопроверка, без расстояния
	 * @param bossName имя босса — для лога и сообщения
	 * @param bossLevel уровень босса
	 * @param epic эпик
	 * @param superChampion суперчемпион
	 * @param hunters охотники рейда вместе с лидером
	 * @param players живые игроки рейда (.raid)
	 */
	public void raidKilled(Creature boss, String bossName, int bossLevel, boolean epic, boolean superChampion, Collection<Integer> hunters, Collection<Integer> players)
	{
		if (!PhantomHuntersConfig.HUNTER_CLAN_REPUTATION)
		{
			return;
		}

		final String what = (epic ? "эпик " : superChampion ? "легендарный чемпион " : "") + bossName + " (" + bossLevel + " ур.)";
		final int full = points(bossLevel, epic, superChampion);
		final Map<Clan, List<String>> withPlayers = new LinkedHashMap<>();
		for (int id : players)
		{
			final Player player = World.getPlayer(id);
			if (!living(player) || (player.getClan() == null) || ((boss != null) && (player.calculateDistance2D(boss) > RAID_RANGE)))
			{
				continue;
			}
			withPlayers.computeIfAbsent(player.getClan(), clan -> new ArrayList<>()).add(player.getName());
		}
		for (Map.Entry<Clan, List<String>> entry : withPlayers.entrySet())
		{
			award(entry.getKey(), full, what, "Клану +" + full + " репутации: рейд с " + String.join(", ", entry.getValue()) + " убил " + what + ".", "raid with " + String.join(", ", entry.getValue()));
		}

		final int away = (full * PhantomHuntersConfig.HUNTER_CLAN_REPUTATION_AWAY) / 100;
		if (away <= 0)
		{
			return;
		}
		final Map<Clan, List<String>> byHunters = new LinkedHashMap<>();
		for (int id : hunters)
		{
			final Player hunter = World.getPlayer(id);
			final Clan clan = hunter == null ? null : hunter.getClan();
			// Клан охотников с 5-го уровня (раздел 4.50) копит репутацию и без живых в игре.
			if ((clan != null) && !withPlayers.containsKey(clan) && (livingOnline(clan) || (PhantomHunterClans.isHunterClan(clan) && (clan.getLevel() >= 5))))
			{
				byHunters.computeIfAbsent(clan, c -> new ArrayList<>()).add(hunter.getName());
			}
		}
		for (Map.Entry<Clan, List<String>> entry : byHunters.entrySet())
		{
			award(entry.getKey(), away, what, "Клану +" + away + " репутации: охотники клана " + String.join(", ", entry.getValue()) + " убили " + what + " в рейде.", "clan hunters " + String.join(", ", entry.getValue()));
		}
	}

	/**
	 * @return репутация за босса: от и до {@code HunterClanRaidReputation} по уровню, эпику — вдвое больше, суперчемпиону — втрое меньше
	 */
	static int points(int bossLevel, boolean epic, boolean superChampion)
	{
		final int min = PhantomHuntersConfig.HUNTER_CLAN_RAID_REPUTATION_MIN;
		final int max = PhantomHuntersConfig.HUNTER_CLAN_RAID_REPUTATION_MAX;
		final double part = Math.max(0, Math.min(1, (bossLevel - LEVEL_FROM) / (double) (LEVEL_TO - LEVEL_FROM)));
		int points = (int) Math.round(min + ((max - min) * part));
		if (epic)
		{
			points *= 2;
		}
		if (superChampion)
		{
			points = Math.max(1, points / 3);
		}
		return points;
	}

	private void award(Clan clan, int points, String what, String message, String log)
	{
		clan.addReputationScore(points);
		// Mobius пишет репутацию в базу только при остановке сервера — упадёт процесс, начисленное пропадёт.
		ThreadPool.execute(clan::updateClanInDB);
		LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " +" + points + " reputation (now " + clan.getReputationScore() + ") for " + what + ", " + log + ".");
		for (ClanMember member : clan.getMembers())
		{
			final Player player = member.getPlayer();
			if (living(player))
			{
				player.sendMessage(message);
			}
		}
	}

	/**
	 * @return живой игрок в игре (не фантом и не офлайн-торговец) или охотник, который играет его в самопроверке
	 */
	boolean living(Player player)
	{
		if ((player == null) || !player.isOnline())
		{
			return false;
		}
		if (player.getObjectId() == _testPlayerId)
		{
			return true;
		}
		return !PhantomManager.getInstance().isPhantom(player) && !player.isInOfflineMode();
	}

	boolean livingOnline(Clan clan)
	{
		for (ClanMember member : clan.getMembers())
		{
			if (living(member.getPlayer()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Самопроверка CLANWARPLAYER (раздел 4.50, этап 4): этот охотник играет живого игрока, как в CLAN.
	 * @param player охотник или {@code null} — никто
	 */
	void testPlayer(Player player)
	{
		_testPlayerId = player != null ? player.getObjectId() : 0;
	}

	/**
	 * @return это охотник самопроверки, который играет живого игрока ({@link PhantomChat#onClanChat} пускает его как живого)
	 */
	boolean isTestPlayer(Player player)
	{
		return (player != null) && (player.getObjectId() == _testPlayerId);
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка CLAN ({@code HuntersSelfTest}, только на копии базы: клан и штрафы ложатся в базу): старший охотник без клана создаёт клан и играет живого игрока;
	 * второй — незнакомым отказывает, другом (+3 благодарности, «реснул») вступает и отвечает в клан-чате; репутация за рейд с «игроком» и без него;
	 * через {@value #TEST_LEAVE_DELAY} мс «игрок» дважды убивает его — враг уходит из клана; клан распускается.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() == null) && !hunter.isDead())
			{
				free.add(hunter);
			}
		}
		free.sort(Comparator.comparingInt((Player p) -> -p.getLevel()));
		if ((free.size() < 2) || (free.get(0).getLevel() < 10))
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest CLAN: needs two hunters without a clan in the world, the elder of level 10+.");
			return;
		}
		final Player leader = free.get(0);
		final Player member = free.get(1);
		String name = TEST_CLAN;
		for (int i = 2; ClanTable.getInstance().getClanByName(name) != null; i++)
		{
			name = TEST_CLAN + i;
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, name);
		if (clan == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest CLAN: " + leader.getName() + " could not create clan " + name + ".");
			return;
		}
		_testPlayerId = leader.getObjectId();
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: " + leader.getName() + " (" + leader.getLevel() + ") plays the living player, created clan " + name + "; " + member.getName() + " (" + member.getLevel() + ") is invited.");

		// Как в RequestJoinPledge: сначала условия Mobius.
		if (!clan.checkClanJoinCondition(leader, member, 0))
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest CLAN: Mobius does not let " + member.getName() + " join (checkClanJoinCondition).");
			dismissTest(clan);
			return;
		}
		final PhantomRelations relations = PhantomRelations.getInstance();
		onInvited(leader, member, 0);
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: first invite — " + (member.getClan() == clan ? "joined" : "declined") + " (friend " + relations.isFriend(member, leader) + ", enemy " + relations.isEnemy(member, leader) + ").");
		if (member.getClan() == null)
		{
			relations.record(member, leader, PhantomRelations.Kind.REVIVED);
			onInvited(leader, member, 0);
			LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: invite as a friend — " + (member.getClan() == clan ? "joined" : "declined") + " (friend " + relations.isFriend(member, leader) + ").");
		}
		if (member.getClan() != clan)
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest CLAN: " + member.getName() + " did not join, the clan is dismissed.");
			dismissTest(clan);
			return;
		}
		final ClanMember entry = clan.getClanMember(member.getObjectId());
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: " + clan.getName() + " has " + clan.getMembersCount() + " members, hunters " + hunters(clan) + "; " + member.getName() + " in the clan list " + ((entry != null) && entry.isOnline() ? "online" : "OFFLINE") + ".");
		PhantomChat.getInstance().onClanChat(leader, member.getName() + ", привет! как кач?");

		final int before = clan.getReputationScore();
		raidKilled(null, "SelfTest boss", 60, false, false, List.of(member.getObjectId()), List.of(leader.getObjectId()));
		final int withPlayer = clan.getReputationScore() - before;
		raidKilled(null, "SelfTest boss", 60, false, false, List.of(member.getObjectId()), List.of());
		final int byHunters = clan.getReputationScore() - before - withPlayer;
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: reputation for a level 60 boss — with the player +" + withPlayer + " (expected " + points(60, false, false) + "), hunters only +" + byHunters + " (expected " + ((points(60, false, false) * PhantomHuntersConfig.HUNTER_CLAN_REPUTATION_AWAY) / 100) + "); epic " + points(60, true, false) + ", super champion " + points(60, false, true) + ".");

		ThreadPool.schedule(() ->
		{
			relations.record(member, leader, PhantomRelations.Kind.KILLED);
			relations.record(member, leader, PhantomRelations.Kind.KILLED);
			LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: after two kills (enemy " + relations.isEnemy(member, leader) + ") " + member.getName() + (member.getClan() == null ? " left the clan" : " is STILL in the clan") + ", join penalty " + (member.getClanJoinExpiryTime() > System.currentTimeMillis() ? "set" : "NOT set") + ".");
			dismissTest(clan);
		}, TEST_LEAVE_DELAY);
	}

	private void dismissTest(Clan clan)
	{
		ClanTable.getInstance().destroyClan(clan.getId());
		_testPlayerId = 0;
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLAN: clan " + clan.getName() + " dismissed.");
	}

	public static PhantomClans getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClans INSTANCE = new PhantomClans();
	}
}
