package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.sql.CrestTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.Crest;
import org.l2jmobius.gameserver.entity.clan.enums.CrestType;

/**
 * L2Hub: у каждого клана охотников своя эмблема (docs/PHANTOMS.md, раздел 4.61; по вкладу пользователя). Набор — {@code data/ClanCrests/*.dds}, его пишет
 * {@code tools/clan_crests.py} по хронике; нет набора — ничего не происходит. Эмблему игрока и уже выданную не трогает, новая не повторяет занятую ни у кого на сервере.
 * Кончились свободные — остальные кланы ждут, выданные остаются.
 */
public final class PhantomClanCrests
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanCrests.class.getName());

	private static final Path POOL = Path.of("data/ClanCrests");
	private static final long CHECK_GAP = 60000;
	private static final int MAGIC = 0x20534444;
	private static final int DXT1 = 0x31545844;

	private record Asset(String name, String hash, byte[] data)
	{
	}

	private final List<Asset> _pool = load();
	private long _nextCheck;
	private boolean _exhausted;

	private PhantomClanCrests()
	{
		LOGGER.info(getClass().getSimpleName() + ": loaded " + _pool.size() + " unique crests.");
	}

	synchronized void control(long now)
	{
		if (!PhantomHuntersConfig.HUNTER_CLAN_CRESTS || !PhantomHuntersConfig.HUNTER_CLANS || (now < _nextCheck))
		{
			return;
		}
		_nextCheck = now + CHECK_GAP;
		give();
	}

	/**
	 * @return сколько кланов получили эмблему
	 */
	private int give()
	{
		if (_pool.isEmpty())
		{
			return 0;
		}
		final Set<String> used = new HashSet<>();
		final List<Clan> pending = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (clan.getCrestId() != 0)
			{
				final Crest crest = CrestTable.getInstance().getCrest(clan.getCrestId());
				if (crest != null)
				{
					used.add(hash(crest.getData()));
				}
			}
			else if ((clan.getLevel() >= PhantomHuntersConfig.HUNTER_CLAN_CREST_LEVEL) && PhantomHunterClans.isHunterClan(clan))
			{
				pending.add(clan);
			}
		}
		pending.sort(Comparator.comparingInt(Clan::getId));
		int given = 0;
		for (Clan clan : pending)
		{
			final Asset asset = choose(used, clan.getId());
			if (asset == null)
			{
				if (!_exhausted)
				{
					LOGGER.warning(getClass().getSimpleName() + ": no unused crests remain, " + (pending.size() - given) + " clans wait; given crests stay.");
					_exhausted = true;
				}
				break;
			}
			_exhausted = false;
			final Crest crest = CrestTable.getInstance().createCrest(asset.data(), CrestType.PLEDGE);
			if (crest == null)
			{
				continue;
			}
			clan.changeClanCrest(crest.getId());
			used.add(asset.hash());
			given++;
			LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " received " + asset.name() + ".");
		}
		return given;
	}

	private Asset choose(Set<String> used, int clanId)
	{
		final int start = Math.floorMod(clanId * 0x9E3779B9, _pool.size());
		for (int i = 0; i < _pool.size(); i++)
		{
			final Asset asset = _pool.get((start + i) % _pool.size());
			if (!used.contains(asset.hash()))
			{
				return asset;
			}
		}
		return null;
	}

	private static List<Asset> load()
	{
		if (!Files.isDirectory(POOL))
		{
			return List.of();
		}
		final List<Asset> result = new ArrayList<>();
		final Set<String> hashes = new HashSet<>();
		try (var files = Files.list(POOL))
		{
			for (Path path : files.filter(file -> Files.isRegularFile(file) && file.getFileName().toString().endsWith(".dds")).sorted().toList())
			{
				final byte[] data = Files.readAllBytes(path);
				if (!valid(data))
				{
					LOGGER.warning("PhantomClanCrests: skipped incompatible " + path.getFileName() + ".");
					continue;
				}
				final String hash = hash(data);
				if (hashes.add(hash))
				{
					result.add(new Asset(path.getFileName().toString(), hash, data));
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "PhantomClanCrests: could not load the crests.", e);
		}
		return List.copyOf(result);
	}

	/**
	 * Заголовок DDS DXT1, размер данных сходится с шириной и высотой. Предел по байтам у хроник разный, его держит генератор.
	 */
	private static boolean valid(byte[] data)
	{
		if (data.length <= 128)
		{
			return false;
		}
		final ByteBuffer header = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		final int height = header.getInt(12);
		final int width = header.getInt(16);
		return (header.getInt(0) == MAGIC) && (header.getInt(4) == 124) && (header.getInt(76) == 32) && (header.getInt(84) == DXT1) && (width > 0) && (height > 0) && ((width % 4) == 0) && ((height % 4) == 0) && ((data.length - 128) == ((width / 4) * (height / 4) * 8));
	}

	private static String hash(byte[] data)
	{
		try
		{
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
		}
		catch (NoSuchAlgorithmException e)
		{
			throw new IllegalStateException(e);
		}
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка CLANCREST ({@code HuntersSelfTest}, только на копии базы): два клана охотников ниже и на нужном уровне, выдача, разные эмблемы, эмблема в таблице
	 * и уцелела при второй выдаче; кланы распускаются.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		final String tag = getClass().getSimpleName() + ": SelfTest CLANCREST: ";
		LOGGER.info(tag + "pool " + _pool.size() + (_pool.isEmpty() ? "" : ", first " + _pool.get(0).name() + " of " + _pool.get(0).data().length + " bytes") + ".");
		final long started = System.currentTimeMillis();
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (PhantomHunterClans.canFound(hunter, started))
			{
				free.add(hunter);
			}
		}
		if (free.size() < 2)
		{
			LOGGER.warning(tag + "needs two hunters who may found a clan, found " + free.size() + ".");
			return;
		}
		final boolean keyWas = PhantomHuntersConfig.HUNTER_CLAN_CRESTS;
		Clan first = null;
		Clan second = null;
		try
		{
			first = PhantomHunterClans.getInstance().found(free.get(0), PhantomHunterClans.fallbackName(), "self-test");
			second = PhantomHunterClans.getInstance().found(free.get(1), PhantomHunterClans.fallbackName(), "self-test");
			if ((first == null) || (second == null))
			{
				LOGGER.warning(tag + "could not found two clans, stop.");
				return;
			}
			final int level = PhantomHuntersConfig.HUNTER_CLAN_CREST_LEVEL;
			first.changeLevel(Math.max(0, level - 1));
			second.changeLevel(level);
			PhantomHuntersConfig.HUNTER_CLAN_CRESTS = true;
			final int given = give();
			LOGGER.info(tag + "levels " + first.getLevel() + " and " + second.getLevel() + " with " + level + " required: given " + given + ", low clan crest " + first.getCrestId() + ", high clan crest " + second.getCrestId() + ".");
			first.changeLevel(level);
			give();
			final Crest a = CrestTable.getInstance().getCrest(first.getCrestId());
			final Crest b = CrestTable.getInstance().getCrest(second.getCrestId());
			LOGGER.info(tag + "after the level: both have one " + ((a != null) && (b != null)) + ", different " + ((a != null) && (b != null) && !hash(a.getData()).equals(hash(b.getData()))) + ", " + (a == null ? 0 : a.getData().length) + " bytes.");
			final int kept = second.getCrestId();
			final int again = give();
			LOGGER.info(tag + "second pass: given " + again + ", crest kept " + (kept == second.getCrestId()) + ".");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_CLAN_CRESTS = keyWas;
			for (Clan clan : new Clan[]
			{
				first,
				second
			})
			{
				if (clan != null)
				{
					clan.changeClanCrest(0);
					ClanTable.getInstance().destroyClan(clan.getId());
				}
			}
			LOGGER.info(tag + "clans dismissed, done.");
		}
	}

	static PhantomClanCrests getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClanCrests INSTANCE = new PhantomClanCrests();
	}
}
