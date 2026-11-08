package org.l2jmobius.gameserver.managers.phantom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;

/**
 * L2Hub: самопроверка {@code HuntersSelfTest = HUNTERSMAX} (docs/PHANTOMS.md, раздел 4.120, только на копии базы: создаёт и распускает клан). Предел {@code HuntersMax} ставится прямо в
 * конфиг: лишние выходят, строки базы лишних не меняются, клан с главой сверх предела переживает месяц простоя, предел снимается, и все входят теми же. Правку файла окном и старт с
 * пределом проверяет внешний прогон. Итог — строка {@code SelfTest HUNTERSMAX: N of M}, ошибки сервера смотрит прогон в {@code log/error0.log}.
 */
final class PhantomHuntersMaxTest
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHuntersMaxTest.class.getName());
	private static final String TAG = "PhantomHuntersMaxTest: SelfTest HUNTERSMAX: ";

	private static final int POLL = 10000;
	/** Сколько ждать выхода лишних: 3 минуты терпения, а рейд и ивент, если идут, не бросают. */
	private static final long LEAVE_WAIT = 600000;
	/** Сколько на вход одного вернувшегося, сверх запаса: по одному за тик. */
	private static final long ENTER_WAIT_EACH = 4000;
	private static final long ENTER_WAIT_BASE = 300000;
	private static final long MONTH = 30 * 86400000L;
	private static final long LONELY_LATER = 73 * 3600000L;
	private static final int MIN_LIMIT = 20;
	private static final String CLAN_NAME = "HMaxTest";

	/** Что о персонаже знает база: всё, что может испортить простой. */
	private static final String[] SNAPSHOTS =
	{
		"SELECT charId, level, exp, sp, clanid, classid, x, y, z, lastAccess, title FROM characters WHERE charId=?",
		"SELECT charId, friendId FROM character_friends WHERE charId=? OR friendId=? ORDER BY charId, friendId",
		"SELECT var, val FROM character_variables WHERE charId=? ORDER BY var",
		"SELECT item_id, count, enchant_level, loc FROM items WHERE owner_id=? ORDER BY object_id",
		"SELECT COUNT(*) FROM character_skills WHERE charId=?",
	};

	private final Supplier<List<Player>> _online;
	/** Таблица без запаса «следующего поколения» (раздел 4.155): тот в мир без жизненного цикла не входит. */
	private final List<PhantomTemplate> _table = PhantomData.getInstance().getHunters().stream().filter(template -> !template.isReserve()).toList();
	/** Лишние: имя, objectId, клан и уровень до выхода. */
	private final Map<String, int[]> _spares = new HashMap<>();
	/** Клан с лишними: кто глава и кто из лишних в нём. */
	private final Map<Integer, Integer> _heads = new HashMap<>();
	private final Map<Integer, Set<Integer>> _spareMembers = new HashMap<>();
	private Map<String, String> _dbBefore;
	private Clan _lonely;
	private int _limit;
	private int _total;
	private int _failed;
	private long _started;

	private PhantomHuntersMaxTest(Supplier<List<Player>> online)
	{
		_online = online;
	}

	/**
	 * @param online охотники в мире, и лежащие мёртвыми
	 */
	static void start(Supplier<List<Player>> online)
	{
		final PhantomHuntersMaxTest test = new PhantomHuntersMaxTest(online);
		ThreadPool.execute(test::begin);
	}

	private void check(boolean ok, String what)
	{
		_total++;
		_failed += ok ? 0 : 1;
		LOGGER.info(TAG + (ok ? "ok: " : "FAIL: ") + what);
	}

	/**
	 * Предел выбирается по живым кланам: как можно больше кланов, где глава за пределом, а кто-то из членов в мире, — такому клану и грозил бы переворот.
	 */
	private int pickLimit(Map<String, Integer> order)
	{
		int best = Math.max(MIN_LIMIT, _table.size() / 2);
		int bestCount = -1;
		for (int limit = MIN_LIMIT; limit <= (_table.size() / 2); limit++)
		{
			int count = 0;
			for (Clan clan : ClanTable.getInstance().getClans())
			{
				final Integer head = order.get(clan.getLeaderName());
				if ((head == null) || (head < limit))
				{
					continue;
				}
				for (ClanMember member : clan.getMembers())
				{
					final Integer position = order.get(member.getName());
					if ((position != null) && (position < limit))
					{
						count++;
						break;
					}
				}
			}
			if (count > bestCount)
			{
				bestCount = count;
				best = limit;
			}
		}
		LOGGER.info(TAG + "limit " + best + ": clans with the head beyond it and a member in the world " + Math.max(0, bestCount) + ".");
		return best;
	}

	private void begin()
	{
		try
		{
			final Map<String, Integer> order = new HashMap<>();
			for (int i = 0; i < _table.size(); i++)
			{
				order.put(_table.get(i).getName(), i);
			}
			_limit = pickLimit(order);
			final List<Player> world = _online.get();
			LOGGER.info(TAG + "in the world " + world.size() + " of " + _table.size() + ".");
			check(world.size() == _table.size(), "everyone is in the world before the limit (" + world.size() + " of " + _table.size() + ")");

			// Клан из одного главы за пределом: без правила его распустили бы через 72 часа.
			synchronized (PhantomHunterClans.getInstance())
			{
				for (Player player : world)
				{
					if ((order.get(player.getName()) >= _limit) && !player.isDead() && (player.getClan() == null) && (player.getLevel() >= 10) && (player.getClanCreateExpiryTime() <= System.currentTimeMillis()))
					{
						_lonely = ClanTable.getInstance().createClan(player, CLAN_NAME);
						if (_lonely != null)
						{
							LOGGER.info(TAG + player.getName() + " (" + player.getLevel() + ") created the lonely clan " + CLAN_NAME + ".");
							break;
						}
					}
				}
			}

			int inParties = 0;
			for (Player player : world)
			{
				if (order.get(player.getName()) < _limit)
				{
					continue;
				}
				inParties += player.isInParty() ? 1 : 0;
				final Clan clan = player.getClan();
				_spares.put(player.getName(), new int[]
				{
					player.getObjectId(),
					clan == null ? 0 : clan.getId(),
					player.getLevel()
				});
				if (clan != null)
				{
					_heads.put(clan.getId(), clan.getLeaderId());
					_spareMembers.computeIfAbsent(clan.getId(), id -> new HashSet<>()).add(player.getObjectId());
				}
			}

			_started = System.currentTimeMillis();
			PhantomHuntersConfig.HUNTERS_MAX = _limit;
			LOGGER.info(TAG + "HuntersMax = " + _limit + ", " + _spares.size() + " hunters must leave, " + inParties + " of them in parties.");
			ThreadPool.schedule(this::waitLeave, POLL);
		}
		catch (Exception e)
		{
			finish(e);
		}
	}

	private List<String> sparesInWorld()
	{
		final List<String> names = new ArrayList<>();
		for (Player player : _online.get())
		{
			if (_spares.containsKey(player.getName()))
			{
				names.add(player.getName());
			}
		}
		return names;
	}

	private void waitLeave()
	{
		try
		{
			final List<String> left = sparesInWorld();
			final long waited = System.currentTimeMillis() - _started;
			if (left.isEmpty() || (waited >= LEAVE_WAIT))
			{
				LOGGER.info(TAG + "after " + (waited / 1000) + " s spare hunters in the world " + left.size() + (left.isEmpty() ? "" : ": " + left) + ", all hunters in the world " + _online.get().size() + ".");
				check(left.isEmpty(), "all spare hunters left in " + (waited / 1000) + " s");
				ThreadPool.schedule(this::hold, 30000);
				return;
			}
			if ((waited % 60000) < POLL)
			{
				LOGGER.info(TAG + "after " + (waited / 1000) + " s spare hunters in the world " + left.size() + ".");
			}
			ThreadPool.schedule(this::waitLeave, POLL);
		}
		catch (Exception e)
		{
			finish(e);
		}
	}

	private Map<String, String> database() throws Exception
	{
		final Map<String, String> rows = new TreeMap<>();
		try (Connection con = DatabaseFactory.getConnection())
		{
			for (int[] spare : _spares.values())
			{
				for (String sql : SNAPSHOTS)
				{
					try (PreparedStatement ps = con.prepareStatement(sql))
					{
						for (int i = 1; i <= ps.getParameterMetaData().getParameterCount(); i++)
						{
							ps.setInt(i, spare[0]);
						}
						try (ResultSet rs = ps.executeQuery())
						{
							final ResultSetMetaData meta = rs.getMetaData();
							final StringBuilder sb = new StringBuilder();
							while (rs.next())
							{
								for (int i = 1; i <= meta.getColumnCount(); i++)
								{
									sb.append(rs.getString(i)).append('|');
								}
								sb.append('\n');
							}
							rows.put(spare[0] + " " + sql.substring(0, Math.min(40, sql.length())), sb.toString());
						}
					}
				}
			}
		}
		return rows;
	}

	/**
	 * Предел стоит, никто лишний не вернулся; месяц простоя для кланов с главой за пределом; строки базы те же.
	 */
	private void hold()
	{
		try
		{
			check(sparesInWorld().isEmpty(), "30 s later nobody spare came back");
			check(_online.get().size() <= _limit, "in the world " + _online.get().size() + ", the limit " + _limit);
			_dbBefore = database();
			LOGGER.info(TAG + "database rows of the spare hunters saved: " + _dbBefore.size() + ".");

			final long now = System.currentTimeMillis();
			final PhantomHunterManager manager = PhantomHunterManager.getInstance();
			if (_lonely != null)
			{
				final PhantomHunterClans clans = PhantomHunterClans.getInstance();
				synchronized (clans)
				{
					final boolean first = clans.dissolveLonely(_lonely, now);
					final boolean second = clans.dissolveLonely(_lonely, now + LONELY_LATER);
					check(!first && !second && (ClanTable.getInstance().getClan(_lonely.getId()) != null), "the lonely clan of a spare head is not dissolved after 73 h");
				}
			}
			else
			{
				LOGGER.warning(TAG + "no hunter could found the lonely clan, that check is skipped.");
			}

			int threatened = 0;
			int heads = 0;
			for (Clan clan : ClanTable.getInstance().getClans())
			{
				if ((clan == _lonely) || !manager.isSpare(clan.getLeaderName()) || !PhantomHunterClans.isHunterClan(clan))
				{
					continue;
				}
				heads++;
				final int leaderId = clan.getLeaderId();
				final int score = PhantomClanPower.getInstance().dramaTest(clan, now + MONTH);
				threatened += score > 0 ? 1 : 0;
				check((score != -2) && (clan.getLeaderId() == leaderId) && (ClanTable.getInstance().getClan(clan.getId()) != null), "clan " + clan.getName() + " of " + clan.getLeaderName() + ": no drama in a month of absence, unrest without the limit " + score);
			}
			LOGGER.info(TAG + "clans with a spare head " + heads + ", where a coup would have been possible without the limit " + threatened + ".");

			final Map<String, String> after = database();
			final List<String> changed = new ArrayList<>();
			for (Map.Entry<String, String> entry : _dbBefore.entrySet())
			{
				if (!entry.getValue().equals(after.get(entry.getKey())))
				{
					changed.add(entry.getKey());
				}
			}
			check(changed.isEmpty(), "database rows of the spare hunters did not change while they were away" + (changed.isEmpty() ? "" : ": " + changed.subList(0, Math.min(8, changed.size()))));

			_started = System.currentTimeMillis();
			PhantomHuntersConfig.HUNTERS_MAX = 0;
			LOGGER.info(TAG + "HuntersMax = 0, everyone comes back.");
			ThreadPool.schedule(this::waitReturn, POLL);
		}
		catch (Exception e)
		{
			finish(e);
		}
	}

	private void waitReturn()
	{
		try
		{
			final int inWorld = _online.get().size();
			final long waited = System.currentTimeMillis() - _started;
			final long limit = ENTER_WAIT_BASE + (ENTER_WAIT_EACH * _spares.size());
			if ((inWorld < _table.size()) && (waited < limit))
			{
				ThreadPool.schedule(this::waitReturn, POLL);
				return;
			}

			check(inWorld == _table.size(), "everyone is back in " + (waited / 1000) + " s (" + inWorld + " of " + _table.size() + ")");
			int same = 0;
			final List<String> wrong = new ArrayList<>();
			for (Map.Entry<String, int[]> entry : _spares.entrySet())
			{
				final Player player = World.getPlayer(entry.getKey());
				final int[] was = entry.getValue();
				final Clan clan = player == null ? null : player.getClan();
				final ClanMember member = clan == null ? null : clan.getClanMember(player.getObjectId());
				// Был без клана — мог вступить уже после возврата, это жизнь мира.
				final boolean clanOk = (was[1] == 0) || ((clan != null) && (clan.getId() == was[1]) && (member != null) && (member.getPlayer() == player));
				if ((player != null) && (player.getObjectId() == was[0]) && clanOk && (player.getLevel() >= was[2]))
				{
					same++;
				}
				else
				{
					wrong.add(entry.getKey());
				}
			}
			check(wrong.isEmpty(), "back the same: character, clan and level, " + same + " of " + _spares.size() + (wrong.isEmpty() ? "" : ", wrong: " + wrong.subList(0, Math.min(8, wrong.size()))));

			int headsOk = 0;
			for (Map.Entry<Integer, Integer> entry : _heads.entrySet())
			{
				final Clan clan = ClanTable.getInstance().getClan(entry.getKey());
				final Set<Integer> members = _spareMembers.get(entry.getKey());
				boolean ok = (clan != null) && (clan.getLeaderId() == entry.getValue());
				if (ok)
				{
					for (int objectId : members)
					{
						ok &= clan.getClanMember(objectId) != null;
					}
				}
				headsOk += ok ? 1 : 0;
			}
			check(headsOk == _heads.size(), "clans of the spare hunters are in place with the same heads and members, " + headsOk + " of " + _heads.size());

			if (_lonely != null)
			{
				final Player head = World.getPlayer(_lonely.getLeaderName());
				check((head != null) && head.isClanLeader() && (head.getClan() == _lonely), "the lonely clan has its head back");
			}
			finish(null);
		}
		catch (Exception e)
		{
			finish(e);
		}
	}

	private void finish(Exception problem)
	{
		if (problem != null)
		{
			_total++;
			_failed++;
			LOGGER.log(Level.WARNING, TAG + "FAIL: the test stopped.", problem);
		}
		if (_lonely != null)
		{
			synchronized (PhantomHunterClans.getInstance())
			{
				ClanTable.getInstance().destroyClan(_lonely.getId());
			}
			LOGGER.info(TAG + "the lonely clan is dismissed.");
		}
		PhantomHuntersConfig.load();
		LOGGER.info(TAG + (_total - _failed) + " of " + _total + ", HuntersMax back to " + PhantomHuntersConfig.HUNTERS_MAX + ".");
		LOGGER.info(TAG + "done.");
	}
}
