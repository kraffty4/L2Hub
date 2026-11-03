package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;

/**
 * L2Hub: охотники в окне Party Matching (Alt+P), ключ {@code HunterPartyMatching} в PhantomHunters.ini.<br>
 * До 6 свободных охотников с кача ждут пати в списке ожидающих; лидер пати охотников на каче (не рейд, не поход в Rift или Камалоку)
 * держит комнату вроде «кач 40-45, нужен хил» — живой зашёл, его сразу берут в пати с приветствием;
 * на комнату живого через 20–90 секунд заходит 1–2 свободных охотника его уровня (не дальше пяти от владельца),
 * не позвали в пати за 10 минут или ушли по делам — выходят из комнаты.
 * Всё, что касается Party Matching Mobius, — только в обёртках внизу файла.
 */
final class PhantomPartyMatching
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPartyMatching.class.getName());

	/** Тик не чаще раза в 15 секунд. */
	private static final long TICK = 15000;
	/** Максимум охотников в списке ожидающих. */
	private static final int MAX_WAITING = 6;
	/** Максимум охотников на одну комнату живого. */
	private static final int MAX_JOIN_LIVE = 2;
	/** Минимальный уровень охотника в списке ожидающих. */
	private static final int MIN_LEVEL = 10;
	/** Минимальный уровень лидера для комнаты. */
	private static final int MIN_ROOM_LEVEL = 10;
	/** Максимум людей в комнате охотника. */
	private static final int ROOM_MEMBERS = 12;
	/** Память об ответах на комнаты живого: 10 минут. */
	private static final long JOIN_COOLDOWN = 10 * 60000L;

	/** Названия комнат охотников: %d-%d — уровень лидера −3…+3. */
	private static final String[] ROOM_TITLES =
	{
		"кач %d-%d, нужен хил",
		"пати %d-%d, го к нам",
		"кач %d-%d, бафер есть"
	};

	/** Приветствия живому в пати охотников: %s — имя. */
	private static final String[] WELCOME =
	{
		"%s, велкам",
		"о, живой) %s, держись рядом",
		"%s с нами, погнали"
	};

	/** Охотники в списке ожидающих (objectId). */
	private final Set<Integer> _waiting = ConcurrentHashMap.newKeySet();
	/** Комнаты охотников: objectId лидера → id комнаты. */
	private final Map<Integer, Integer> _rooms = new ConcurrentHashMap<>();
	/** Кто когда последний раз заходил в комнату живого (objectId → время). */
	private final Map<Integer, Long> _joinedLive = new ConcurrentHashMap<>();
	/** Комната живого → когда в неё можно зайти первому охотнику (задержка 20–90 с). */
	private final Map<Integer, Long> _liveRoomReadyAt = new ConcurrentHashMap<>();
	/** Охотники в комнатах живых: objectId → комната и когда зашёл. */
	private final Map<Integer, Seat> _liveSeats = new ConcurrentHashMap<>();

	/** Место охотника в комнате живого. */
	private record Seat(int roomId, long at)
	{
	}

	/** Время последнего тика. */
	private long _lastAt;

	/**
	 * Раз в 15 секунд: список ожидающих, комнаты охотников, приём живых, отклик на комнаты живых.
	 * @param hunting свободные охотники на каче и в городе
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if ((now - _lastAt) < TICK)
		{
			return;
		}
		_lastAt = now;

		if (!PhantomCompat.partyMatchingExists())
		{
			return;
		}
		if (!PhantomHuntersConfig.HUNTER_PARTY_MATCHING)
		{
			// Выключили конфиг — за собой чистим список и комнаты.
			for (int objectId : _waiting)
			{
				final Player player = World.getPlayer(objectId);
				if (player != null)
				{
					removeWaiting(player);
				}
			}
			_waiting.clear();
			for (int roomId : _rooms.values())
			{
				closeRoom(roomId);
			}
			_rooms.clear();
			_liveRoomReadyAt.clear();
			for (Map.Entry<Integer, Seat> entry : _liveSeats.entrySet())
			{
				final Player hunter = World.getPlayer(entry.getKey());
				if (hunter != null)
				{
					leaveRoom(hunter, entry.getValue().roomId());
				}
			}
			_liveSeats.clear();
			return;
		}

		controlWaiting(hunting);
		controlHunterRooms();
		controlLiveInHunterRooms();
		controlLiveSeats(hunting, now);
		controlLiveRooms(hunting, now);
		forget(now);
	}

	/**
	 * Охотник свободен для окна поиска пати: на каче, без пати, не в лавке, не ведёт рейд или поход,
	 * не на ивенте, не в инстансе и Rift, не сидит в комнате живого.
	 * @param player охотник
	 * @param hunting свободные охотники менеджера
	 */
	private boolean free(Player player, List<Player> hunting)
	{
		return hunting.contains(player) && PhantomHunterManager.getInstance().isHunting(player) && !player.isInParty() && !player.isInStoreMode() && (PhantomRaid.getInstance().raidOf(player) == null) && !PhantomEvents.getInstance().isBusy(player) && (player.getInstanceId() == 0) && !PhantomCompat.inRift(player) && !_liveSeats.containsKey(Integer.valueOf(player.getObjectId()));
	}

	/**
	 * Список ожидающих: убрать в пати или выпавших, добрать случайными свободными.
	 * @param hunting свободные охотники
	 */
	private void controlWaiting(List<Player> hunting)
	{
		for (int objectId : _waiting)
		{
			final Player player = World.getPlayer(objectId);
			if ((player == null) || !free(player, hunting))
			{
				_waiting.remove(objectId);
				if (player != null)
				{
					removeWaiting(player);
				}
			}
		}
		if (_waiting.size() >= MAX_WAITING)
		{
			return;
		}
		final List<Player> free = new ArrayList<>();
		for (Player player : hunting)
		{
			if (free(player, hunting) && (player.getLevel() >= MIN_LEVEL) && !_waiting.contains(player.getObjectId()))
			{
				free.add(player);
			}
		}
		while (!free.isEmpty() && (_waiting.size() < MAX_WAITING))
		{
			final Player player = free.remove(Rnd.get(free.size()));
			_waiting.add(player.getObjectId());
			addWaiting(player);
		}
	}

	/**
	 * Пати охотника может держать комнату: он лидер пати на каче (не рейд, не поход в Rift или Камалоку, не инстанс), людей меньше 6.
	 * Пати с живыми тоже держит: зашедший живой не закрывает комнату остальным.
	 * @param leader лидер пати
	 */
	private static boolean roomLeader(Player leader)
	{
		return PhantomManager.getInstance().isPhantom(leader) && leader.isInParty() && leader.getParty().isLeader(leader) && (leader.getParty().getMemberCount() < 6) && PhantomHunterManager.getInstance().isHunting(leader) && (PhantomRaid.getInstance().raidOf(leader) == null) && !PhantomRift.getInstance().inRun(leader) && !PhantomKamaloka.getInstance().inRun(leader) && (leader.getInstanceId() == 0) && !PhantomCompat.inRift(leader);
	}

	/**
	 * Комнаты охотников: лидер пати охотников на каче (людей меньше 6) держит комнату.
	 */
	private void controlHunterRooms()
	{
		// Сначала закрыть то, что стало не по правилам.
		for (Map.Entry<Integer, Integer> entry : _rooms.entrySet())
		{
			final Player leader = World.getPlayer(entry.getKey());
			if ((leader == null) || !roomLeader(leader))
			{
				closeRoom(entry.getValue());
				_rooms.remove(entry.getKey());
			}
		}
		// Потом открыть недостающие: пати целиком из охотников, чтобы не открывать комнату чужой пати с живым лидером.
		for (Player leader : World.getPlayers())
		{
			if (!roomLeader(leader) || !allPhantoms(leader.getParty()) || (leader.getLevel() < MIN_ROOM_LEVEL) || _rooms.containsKey(leader.getObjectId()))
			{
				continue;
			}
			final int roomId = openRoom(leader, roomTitle(leader.getLevel()));
			if (roomId > 0)
			{
				_rooms.put(leader.getObjectId(), roomId);
			}
		}
	}

	/**
	 * Живой зашёл в комнату охотника — сразу в пати лидера с приветствием.
	 */
	private void controlLiveInHunterRooms()
	{
		for (Map.Entry<Integer, Integer> entry : _rooms.entrySet())
		{
			final Player leader = World.getPlayer(entry.getKey());
			if ((leader == null) || !roomLeader(leader))
			{
				continue;
			}
			for (Player member : roomMembers(entry.getValue()))
			{
				if ((member == null) || PhantomManager.getInstance().isPhantom(member) || !leader.isInParty() || leader.getParty().containsPlayer(member))
				{
					continue;
				}
				if (leader.getParty().getMemberCount() >= 9)
				{
					break; // пати полная
				}
				if (!member.isInParty())
				{
					joinParty(member, leader.getParty());
					PhantomCompanionManager.partySay(leader, String.format(WELCOME[Rnd.get(WELCOME.length)], member.getName()));
					LOGGER.info(getClass().getSimpleName() + ": " + member.getName() + " joins hunter party of " + leader.getName() + ".");
				}
				// В чужой пати — ничего не делаем.
			}
		}
	}

	/**
	 * Отклик на комнаты живых: через 20–90 секунд заходит 1–2 охотника подходящего уровня.
	 * @param hunting свободные охотники
	 * @param now текущее время
	 */
	private void controlLiveRooms(List<Player> hunting, long now)
	{
		for (int roomId : allRooms())
		{
			final Player owner = roomOwner(roomId);
			if ((owner == null) || PhantomManager.getInstance().isPhantom(owner) || _rooms.containsValue(roomId))
			{
				continue;
			}
			Long readyAt = _liveRoomReadyAt.get(roomId);
			if (readyAt == null)
			{
				readyAt = Long.valueOf(now + Rnd.get(20000, 90000));
				_liveRoomReadyAt.put(roomId, readyAt);
				continue;
			}
			if (now < readyAt.longValue())
			{
				continue;
			}
			int huntersInRoom = 0;
			for (Player member : roomMembers(roomId))
			{
				if ((member != null) && PhantomManager.getInstance().isPhantom(member))
				{
					huntersInRoom++;
				}
			}
			if (huntersInRoom >= MAX_JOIN_LIVE)
			{
				continue;
			}
			final Player hunter = pickHunter(hunting, owner, roomMinLevel(roomId), roomMaxLevel(roomId), now);
			if (hunter != null)
			{
				joinRoom(hunter, roomId);
				_joinedLive.put(hunter.getObjectId(), Long.valueOf(now));
				_liveSeats.put(Integer.valueOf(hunter.getObjectId()), new Seat(roomId, now));
			}
		}
	}

	/**
	 * Охотники в комнатах живых: комната закрылась, живой так и не позвал в пати за {@value #JOIN_COOLDOWN} мс,
	 * охотник ушёл в другую пати или дела — выходит из комнаты. Позвал — остаётся, пока он в пати владельца.
	 * @param hunting свободные охотники
	 * @param now текущее время
	 */
	private void controlLiveSeats(List<Player> hunting, long now)
	{
		for (Map.Entry<Integer, Seat> entry : _liveSeats.entrySet())
		{
			final Player hunter = World.getPlayer(entry.getKey());
			final Seat seat = entry.getValue();
			if (hunter == null)
			{
				_liveSeats.remove(entry.getKey());
				continue;
			}
			final Player owner = roomOwner(seat.roomId());
			final boolean withOwner = (owner != null) && hunter.isInParty() && owner.isInParty() && (hunter.getParty() == owner.getParty());
			if (withOwner)
			{
				continue;
			}
			final boolean waiting = (owner != null) && !hunter.isInParty() && hunting.contains(hunter) && ((now - seat.at()) < JOIN_COOLDOWN);
			if (!waiting)
			{
				leaveRoom(hunter, seat.roomId());
				_liveSeats.remove(entry.getKey());
			}
		}
	}

	/**
	 * Забыть устаревшее: комнаты, которых нет, и старые записи об откликах.
	 * @param now текущее время
	 */
	private void forget(long now)
	{
		_liveRoomReadyAt.keySet().removeIf(roomId -> roomOwner(roomId) == null);
		_joinedLive.values().removeIf(when -> (now - when.longValue()) > JOIN_COOLDOWN);
	}

	/**
	 * Свободный охотник под комнату живого: свободен ({@link #free}), уровень в границах комнаты и не дальше пяти от владельца
	 * (дальше инвайт охотник не примет), недавно не отвечал.
	 * @param hunting свободные охотники
	 * @param owner владелец комнаты
	 * @param minLevel минимальный уровень комнаты
	 * @param maxLevel максимальный уровень комнаты
	 * @param now текущее время
	 * @return охотник или null
	 */
	private Player pickHunter(List<Player> hunting, Player owner, int minLevel, int maxLevel, long now)
	{
		final List<Player> candidates = new ArrayList<>();
		for (Player hunter : hunting)
		{
			if (!free(hunter, hunting) || (hunter.getLevel() < minLevel) || (hunter.getLevel() > maxLevel) || (Math.abs(hunter.getLevel() - owner.getLevel()) > 5))
			{
				continue;
			}
			final Long joinedAt = _joinedLive.get(hunter.getObjectId());
			if ((joinedAt != null) && ((now - joinedAt.longValue()) < JOIN_COOLDOWN))
			{
				continue;
			}
			candidates.add(hunter);
		}
		return candidates.isEmpty() ? null : candidates.get(Rnd.get(candidates.size()));
	}

	/**
	 * @param party пати
	 * @return все члены пати — фантомы
	 */
	private boolean allPhantoms(Party party)
	{
		for (Player member : party.getMembers())
		{
			if (!PhantomManager.getInstance().isPhantom(member))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Название комнаты: уровень лидера −3…+3, не длиннее 29 символов.
	 * @param level уровень лидера
	 * @return название комнаты
	 */
	private String roomTitle(int level)
	{
		String title = String.format(ROOM_TITLES[Rnd.get(ROOM_TITLES.length)], level - 3, level + 3);
		if (title.length() > 29)
		{
			title = title.substring(0, 29);
		}
		return title;
	}

	// ---------- Обёртки над Party Matching API Mobius ----------

	/**
	 * Добавить охотника в список ожидающих.
	 * @param player охотник
	 */
	private static void addWaiting(Player player)
	{
		try
		{
			PhantomCompat.partyMatchWait(player, true);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot add " + player.getName() + " to waiting list.");
		}
	}

	/**
	 * Убрать охотника из списка ожидающих.
	 * @param player охотник
	 */
	private static void removeWaiting(Player player)
	{
		try
		{
			PhantomCompat.partyMatchWait(player, false);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot remove " + player.getName() + " from waiting list.");
		}
	}

	/**
	 * Открыть комнату: случайный лут, уровни лидер ±3, мест 12.
	 * @param owner лидер пати охотников
	 * @param title название комнаты
	 * @return id комнаты или 0
	 */
	private static int openRoom(Player owner, String title)
	{
		try
		{
			return PhantomCompat.partyRoomOpen(owner, title, Math.max(1, owner.getLevel() - 3), owner.getLevel() + 3, ROOM_MEMBERS);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot open room for " + owner.getName() + ".");
			return 0;
		}
	}

	/**
	 * Закрыть комнату.
	 * @param roomId id комнаты
	 */
	private static void closeRoom(int roomId)
	{
		try
		{
			PhantomCompat.partyRoomClose(roomId);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot close room " + roomId + ".");
		}
	}

	/**
	 * @param roomId id комнаты
	 * @return участники комнаты
	 */
	private static List<Player> roomMembers(int roomId)
	{
		try
		{
			return PhantomCompat.partyRoomMembers(roomId);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot list room " + roomId + ".");
		}
		return List.of();
	}

	/**
	 * @return id всех комнат
	 */
	private static Collection<Integer> allRooms()
	{
		try
		{
			return PhantomCompat.partyRooms();
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot list rooms.");
			return List.of();
		}
	}

	/**
	 * @param roomId id комнаты
	 * @return владелец комнаты
	 */
	private static Player roomOwner(int roomId)
	{
		try
		{
			return PhantomCompat.partyRoomOwner(roomId);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": cannot get owner of room " + roomId + ".");
			return null;
		}
	}

	/**
	 * @param roomId id комнаты
	 * @return минимальный уровень комнаты
	 */
	private static int roomMinLevel(int roomId)
	{
		try
		{
			return PhantomCompat.partyRoomLevels(roomId)[0];
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/**
	 * @param roomId id комнаты
	 * @return максимальный уровень комнаты
	 */
	private static int roomMaxLevel(int roomId)
	{
		try
		{
			return PhantomCompat.partyRoomLevels(roomId)[1];
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/**
	 * Охотник входит в комнату живого (и разослать обновление списка, если Mobius сам не делает).
	 * @param hunter охотник
	 * @param roomId id комнаты
	 */
	private static void joinRoom(Player hunter, int roomId)
	{
		try
		{
			PhantomCompat.partyRoomJoin(hunter, roomId);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": " + hunter.getName() + " cannot join room " + roomId + ".");
		}
	}

	/**
	 * Охотник выходит из комнаты живого.
	 * @param hunter охотник
	 * @param roomId id комнаты
	 */
	private static void leaveRoom(Player hunter, int roomId)
	{
		try
		{
			PhantomCompat.partyRoomLeave(hunter, roomId);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": " + hunter.getName() + " cannot leave room " + roomId + ".");
		}
	}

	/**
	 * Живой без пати вступает в пати охотников.
	 * @param player живой игрок
	 * @param party пати охотников
	 */
	private static void joinParty(Player player, Party party)
	{
		try
		{
			player.joinParty(party);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomPartyMatching.class.getSimpleName() + ": " + player.getName() + " cannot join party.");
		}
	}

	public static PhantomPartyMatching getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomPartyMatching INSTANCE = new PhantomPartyMatching();
	}
}
