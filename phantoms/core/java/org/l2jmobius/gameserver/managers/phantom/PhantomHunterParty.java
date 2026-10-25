package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompanionManager.Mode;

/**
 * L2Hub: пати охотников между собой по отношениям (патч 0036, docs/PHANTOMS.md, раздел 4.19).<br>
 * Решение автора: друзья собираются, остальные — бросок кости, враги — никогда.<br>
 * Охотник на каче раз в {@value #LOOK_GAP} мс смотрит, нет ли рядом (в {@value #RANGE}) свободного охотника близкого уровня (±{@value #LEVEL_GAP}).
 * Враг хоть с одной стороны — никогда; друг (благодарность от 3 и больше обиды) с любой стороны — шанс {@link PhantomHuntersConfig#HUNTER_PARTY_CHANCE_FRIEND};
 * остальные (незнакомые и «сложные») — {@link PhantomHuntersConfig#HUNTER_PARTY_CHANCE} × общительность. Бросок на пару — не чаще раза в {@value #PAIR_GAP} мс.<br>
 * Выпало — тот, кто смотрел, становится лидером и зовёт второго тем же механизмом, что живой игрок ({@link PhantomCompanionManager#onInvited}):
 * лидер качается дальше на своём споте родным AutoPlay, второй ходит с ним напарником — дамагер «кач тут» вокруг лидера, хилер и баффер «бей его цель» и лечат пати.
 * Пати до {@link PhantomHuntersConfig#HUNTER_PARTY_MAX} охотников; живёт {@link PhantomHuntersConfig#HUNTER_PARTY_MINUTES} минут, распускается раньше, если лидер ушёл с кача
 * (город, лавка, смерть, «лфм» живого игрока) или остался один. Отпущенные идут качаться ногами на ближайший спот, как после пати с живым.
 * «Качались вместе» засчитывается и между охотниками — так незнакомцы со временем становятся друзьями.
 */
public class PhantomHunterParty
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHunterParty.class.getName());

	private static final long LOOK_GAP = 30000;
	private static final int RANGE = 1500;
	private static final int LEVEL_GAP = 5;
	/** Одна пара охотников бросает кость не чаще. */
	private static final long PAIR_GAP = 1200000;
	/** После пати охотник столько не собирает новую и не идёт в чужую. */
	private static final long REST_AFTER = 1200000;

	private static class Group
	{
		final long until;
		final Set<Integer> members = ConcurrentHashMap.newKeySet();

		Group(long until)
		{
			this.until = until;
		}
	}

	/** Лидер (objectId) → его пати охотников. */
	private final Map<Integer, Group> _groups = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextLook = new ConcurrentHashMap<>();
	/** Охотник → не в пати до (отдых после пати). */
	private final Map<Integer, Long> _restUntil = new ConcurrentHashMap<>();
	/** Пара → не бросать до. */
	private final Map<Long, Long> _pairNext = new ConcurrentHashMap<>();

	protected PhantomHunterParty()
	{
	}

	/**
	 * Каждый тик охотника (из начала {@link PhantomHunterManager#think}): лидер ушёл с кача, время вышло, остался один — распустить.
	 * @param leader охотник
	 * @param hunting он сейчас на каче (фаза HUNT)
	 * @param now текущее время
	 */
	public void check(Player leader, boolean hunting, long now)
	{
		final Group group = _groups.get(leader.getObjectId());
		if (group == null)
		{
			return;
		}

		// Кто вышел сам (умер и не дождался реса, позвал живой игрок) — из списка.
		group.members.removeIf(id ->
		{
			final Player member = World.getPlayer(id);
			return (member == null) || !member.isInParty() || (member.getParty() != leader.getParty());
		});

		final String reason = !PhantomHuntersConfig.HUNTER_PARTIES ? "пати охотников выключены" : leader.isDead() ? "лидер погиб" : !hunting ? "лидер ушёл с кача" : !leader.isInParty() || group.members.isEmpty() ? "остался один" : now >= group.until ? "время вышло" : null;
		if (reason != null)
		{
			disband(leader, group, reason, now);
		}
	}

	/**
	 * Охотник на каче (фаза HUNT, из {@link PhantomHunterManager}): собрать пати или позвать ещё одного.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	public void tick(Player hunter, PhantomTemplate template, long now)
	{
		final int id = hunter.getObjectId();
		if (!PhantomHuntersConfig.HUNTER_PARTIES || (now < _nextLook.getOrDefault(id, 0L)))
		{
			return;
		}
		_nextLook.put(id, now + LOOK_GAP + Rnd.get(10000));

		final Group group = _groups.get(id);
		if (hunter.isDead() || (now < _restUntil.getOrDefault(id, 0L)) || ((group == null) && hunter.isInParty()) || ((group != null) && ((group.members.size() + 1) >= PhantomHuntersConfig.HUNTER_PARTY_MAX)))
		{
			return;
		}
		if (PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED)
		{
			return;
		}

		// Соклановцы — первыми (раздел 4.50).
		final List<Player> around = new ArrayList<>(World.getVisibleObjectsInRange(hunter, Player.class, RANGE));
		around.sort(Comparator.comparingInt(other -> PhantomHunterClans.clanmates(hunter, other) ? 0 : 1));
		for (Player other : around)
		{
			if (!PhantomRelations.isHunter(other) || other.isDead() || other.isInParty() || (Math.abs(other.getLevel() - hunter.getLevel()) > LEVEL_GAP) || (now < _restUntil.getOrDefault(other.getObjectId(), 0L)) || !PhantomHunterManager.getInstance().isHunting(other))
			{
				continue;
			}

			// Враг хоть с одной стороны — никогда.
			final PhantomRelations relations = PhantomRelations.getInstance();
			if (relations.isEnemy(hunter, other) || relations.isEnemy(other, hunter))
			{
				continue;
			}

			final long pair = pair(hunter, other);
			if (now < _pairNext.getOrDefault(pair, 0L))
			{
				continue;
			}
			_pairNext.put(pair, now + PAIR_GAP);

			final boolean friend = relations.isFriend(hunter, other) || relations.isFriend(other, hunter) || PhantomHunterClans.clanmates(hunter, other);
			final double chance = friend ? PhantomHuntersConfig.HUNTER_PARTY_CHANCE_FRIEND : Math.min(100, PhantomHuntersConfig.HUNTER_PARTY_CHANCE * template.getSocial());
			final boolean yes = Rnd.get(100.0) < chance;
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " -> " + other.getName() + " (" + (friend ? "друг" : "бросок") + ", шанс " + String.format(Locale.ROOT, "%.0f", chance) + "%) — " + (yes ? "зовёт в пати." : "нет."));
			if (yes)
			{
				invite(hunter, other, group, now);
			}
			return;
		}
	}

	/**
	 * @param player охотник
	 * @return он ведёт пати охотников
	 */
	public boolean isLeader(Player player)
	{
		return _groups.containsKey(player.getObjectId());
	}

	/**
	 * @param leader лидер пати охотников
	 * @return в его пати есть место ({@link PhantomHuntersConfig#HUNTER_PARTY_MAX})
	 */
	public boolean hasRoom(Player leader)
	{
		final Group group = _groups.get(leader.getObjectId());
		return (group != null) && ((group.members.size() + 1) < PhantomHuntersConfig.HUNTER_PARTY_MAX);
	}

	/**
	 * Стратег позвал охотника по имени ({@code [[PARTY имя]]}, протокол v1): свободного — к себе, лидера пати охотников — просится к нему. Решает позванный по тем же правилам,
	 * что и бросок на каче ({@link #tick}): враг хоть с одной стороны — никогда, устал или только что из пати — нет, друг — {@link PhantomHuntersConfig#HUNTER_PARTY_CHANCE_FRIEND},
	 * остальные — {@link PhantomHuntersConfig#HUNTER_PARTY_CHANCE} × его общительность; одна пара — не чаще раза в {@value #PAIR_GAP} мс. Кто рядом и есть ли место, проверил менеджер.
	 * @param asker кто зовёт
	 * @param other кого
	 * @param force самопроверка MIND: согласиться без броска
	 * @param now текущее время
	 * @return {@code null} — в одной пати; иначе причина отказа по-русски
	 */
	public String ask(Player asker, Player other, boolean force, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PARTIES)
		{
			return "пати охотников выключены";
		}
		final PhantomTemplate otherTemplate = PhantomData.getInstance().getTemplate(other.getName());
		if (otherTemplate == null)
		{
			return "нет такого рядом";
		}

		final String state = stateRefusal(asker, other, now);
		if (state != null)
		{
			return state;
		}
		final PhantomRelations relations = PhantomRelations.getInstance();
		final long pair = pair(asker, other);
		if (!force && (now < _pairNext.getOrDefault(pair, 0L)))
		{
			return "отказал: уже звал его недавно";
		}
		_pairNext.put(pair, now + PAIR_GAP);

		final boolean friend = relations.isFriend(asker, other) || relations.isFriend(other, asker) || PhantomHunterClans.clanmates(asker, other);
		final double chance = friend ? PhantomHuntersConfig.HUNTER_PARTY_CHANCE_FRIEND : Math.min(100, PhantomHuntersConfig.HUNTER_PARTY_CHANCE * otherTemplate.getSocial());
		final boolean yes = force || (Rnd.get(100.0) < chance);
		final Group theirs = _groups.get(other.getObjectId());
		LOGGER.info(getClass().getSimpleName() + ": " + asker.getName() + " -> " + other.getName() + " по имени (" + (force ? "самопроверка" : friend ? "друг" : "бросок") + ", шанс " + String.format(Locale.ROOT, "%.0f", chance) + "%) — " + (yes ? (theirs != null ? "просится к нему." : "зовёт к себе.") : "отказ."));
		if (!yes)
		{
			return "отказал";
		}

		if (theirs != null)
		{
			invite(other, asker, theirs, now);
		}
		else
		{
			invite(asker, other, _groups.get(asker.getObjectId()), now);
		}
		return asker.isInParty() && (asker.getParty() == other.getParty()) ? null : "отказал";
	}

	/**
	 * Отказ {@link #ask} ещё до броска, по состоянию — его не обходит и самопроверка: враги, только что из пати, устал.
	 * @param asker кто зовёт
	 * @param other кого зовут
	 * @param now текущее время
	 * @return причина по-русски или {@code null} — решит бросок
	 */
	public String stateRefusal(Player asker, Player other, long now)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		if (relations.isEnemy(asker, other) || relations.isEnemy(other, asker))
		{
			return "отказал: вы враги";
		}
		if (now < _restUntil.getOrDefault(other.getObjectId(), 0L))
		{
			return "отказал: только что из пати";
		}
		if (PhantomHunterManager.getInstance().mood(other) == PhantomMood.TIRED)
		{
			return "отказал: устал";
		}
		return null;
	}

	private void invite(Player leader, Player member, Group existing, long now)
	{
		final PhantomTemplate memberTemplate = PhantomData.getInstance().getTemplate(member.getName());
		if (memberTemplate == null)
		{
			return;
		}

		PhantomCompanionManager.getInstance().onInvited(leader, member, null);
		if (!member.isInParty() || (member.getParty() != leader.getParty()))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + member.getName() + " не вступил в пати " + leader.getName() + ".");
			return;
		}

		// Хилер лечит; чистый баффер бафает, только если в городе нет баффера (с ним бафы на максимуме и так — качается рядом, раздел 4.36).
		final boolean support = (memberTemplate.getRole() == PhantomTemplate.Role.HEALER) || ((memberTemplate.getRole() == PhantomTemplate.Role.BUFFER) && !PhantomSupport.townBuffer());
		PhantomCompanionManager.getInstance().setMode(leader, memberTemplate, support ? Mode.ASSIST : Mode.HUNT);

		Group group = existing;
		if (group == null)
		{
			final int[] minutes = PhantomHuntersConfig.HUNTER_PARTY_MINUTES;
			final int lo = minutes.length > 0 ? minutes[0] : 20;
			final int hi = minutes.length > 1 ? Math.max(lo, minutes[1]) : lo;
			group = new Group(now + (Rnd.get(lo, hi) * 60000L));
			_groups.put(leader.getObjectId(), group);
			PhantomHunterManager.getInstance().ledParty(leader);
		}
		group.members.add(member.getObjectId());
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " собрал пати: " + names(leader, group) + " (" + (!support ? "качается рядом" : memberTemplate.getRole() == PhantomTemplate.Role.HEALER ? "хилит" : "бафает") + ").");

		// Вслух (патч 0037): лидер зовёт словами, позванный отвечает — если рядом есть кому слышать.
		final PhantomTemplate leaderTemplate = PhantomData.getInstance().getTemplate(leader.getName());
		if ((leaderTemplate != null) && PhantomBanter.audience(leader))
		{
			PhantomChat.getInstance().banter(leader, member, leaderTemplate, "ты позвал " + PhantomHunterManager.describe(member) + " — такого же охотника, как ты, — к себе в пати качаться вместе на этом споте, и он вступил. Скажи ему это вслух, по-игровому");
		}
	}

	private void disband(Player leader, Group group, String reason, long now)
	{
		_groups.remove(leader.getObjectId());
		_restUntil.put(leader.getObjectId(), now + REST_AFTER);

		// Расстались по-хорошему (время вышло) — лидер прощается вслух, если рядом есть кому слышать (патч 0037).
		final Player first = group.members.isEmpty() ? null : World.getPlayer(group.members.iterator().next());
		if ((first != null) && "время вышло".equals(reason) && !leader.isDead() && PhantomBanter.audience(leader))
		{
			PhantomChat.getInstance().hunterEvent(leader, first, "PARTY_END", "вы с " + first.getName() + " покачались в пати, и пора расходиться — каждый дальше сам. Скажи ему пару слов на прощание", "ладно, бывай, хорошо покачались)", 600000);
		}

		final List<String> names = new ArrayList<>();
		for (int id : group.members)
		{
			final Player member = World.getPlayer(id);
			final PhantomTemplate template = member != null ? PhantomData.getInstance().getTemplate(member.getName()) : null;
			if (template != null)
			{
				PhantomCompanionManager.getInstance().release(template, true);
				names.add(member.getName());
			}
			_restUntil.put(id, now + REST_AFTER);
		}
		if (leader.isInParty())
		{
			leader.leaveParty();
		}
		LOGGER.info(getClass().getSimpleName() + ": пати " + leader.getName() + (names.isEmpty() ? "" : " с " + String.join(", ", names)) + " распущена: " + reason + ".");
	}

	private static String names(Player leader, Group group)
	{
		final List<String> names = new ArrayList<>();
		names.add(leader.getName());
		for (int id : group.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				names.add(member.getName());
			}
		}
		return String.join(", ", names);
	}

	private static long pair(Player a, Player b)
	{
		final long low = Math.min(a.getObjectId(), b.getObjectId());
		final long high = Math.max(a.getObjectId(), b.getObjectId());
		return (low << 32) | (high & 0xffffffffL);
	}

	public static PhantomHunterParty getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomHunterParty INSTANCE = new PhantomHunterParty();
	}
}
