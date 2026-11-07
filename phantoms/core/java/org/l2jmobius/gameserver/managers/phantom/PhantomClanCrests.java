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
 * Кончились свободные — остальные кланы ждут, выданные остаются. Исключение — снятый набор: суммы в {@code data/ClanCrests/retired.txt}, такую эмблему могла выдать только прежняя версия
 * генератора, клан охотников получает вместо неё новую.<br>
 * Союз, который возглавляет клан охотников, получает эмблему союза из эмблемы главы, сжатой по ширине до 8×16 (DXT1, 192 байта — предел {@code RequestSetAllyCrest}):
 * без неё у союзов охотников эмблемы не было совсем (мост, ep-alliance-crest-not-assigned). Эмблему союза игрока не трогает.
 */
public final class PhantomClanCrests
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanCrests.class.getName());

	private static final Path POOL = Path.of("data/ClanCrests");
	private static final Path RETIRED = POOL.resolve("retired.txt");
	private static final long CHECK_GAP = 60000;
	private static final int MAGIC = 0x20534444;
	private static final int DXT1 = 0x31545844;

	private record Asset(String name, String hash, byte[] data)
	{
	}

	private final List<Asset> _pool = load();
	private final Set<String> _retired = loadRetired();
	private long _nextCheck;
	private boolean _exhausted;

	private PhantomClanCrests()
	{
		LOGGER.info(getClass().getSimpleName() + ": loaded " + _pool.size() + " unique crests, " + _retired.size() + " retired.");
	}

	synchronized void control(long now)
	{
		if (!PhantomHuntersConfig.HUNTER_CLAN_CRESTS || !PhantomHuntersConfig.HUNTER_CLANS || (now < _nextCheck))
		{
			return;
		}
		_nextCheck = now + CHECK_GAP;
		give();
		giveAlly();
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
					final String hash = hash(crest.getData());
					if (_retired.contains(hash) && PhantomHunterClans.isHunterClan(clan))
					{
						pending.add(clan);
					}
					else
					{
						used.add(hash);
					}
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

	/**
	 * Союзы во главе с кланом охотников без эмблемы: эмблема главы, сжатая до размера эмблемы союза, — всем кланам союза ({@code changeAllyCrest}, как RequestSetAllyCrest).
	 * @return скольким союзам выдана
	 */
	private int giveAlly()
	{
		int given = 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if ((clan.getAllyId() == 0) || (clan.getAllyId() != clan.getId()) || (clan.getAllyCrestId() != 0) || (clan.getCrestId() == 0) || !PhantomHunterClans.isHunterClan(clan))
			{
				continue;
			}
			final Crest own = CrestTable.getInstance().getCrest(clan.getCrestId());
			final byte[] data = own == null ? null : allyCrest(own.getData());
			if (data == null)
			{
				continue;
			}
			final Crest crest = CrestTable.getInstance().createCrest(data, CrestType.ALLY);
			if (crest == null)
			{
				continue;
			}
			clan.changeAllyCrest(crest.getId(), false);
			given++;
			LOGGER.info(getClass().getSimpleName() + ": alliance " + clan.getAllyName() + " of clan " + clan.getName() + " received an alliance crest.");
		}
		return given;
	}

	/**
	 * Эмблема союза из эмблемы клана: DXT1 той же высоты 16, по ширине — до 8 средним по соседним точкам; прозрачная точка остаётся прозрачной, если прозрачна хотя бы половина.
	 * @param clanCrest DDS эмблемы клана
	 * @return DDS 8×16 или {@code null}, если формат не тот
	 */
	static byte[] allyCrest(byte[] clanCrest)
	{
		if (!valid(clanCrest))
		{
			return null;
		}
		final ByteBuffer header = ByteBuffer.wrap(clanCrest).order(ByteOrder.LITTLE_ENDIAN);
		final int height = header.getInt(12);
		final int width = header.getInt(16);
		if ((height != 16) || (width < 8) || ((width % 8) != 0))
		{
			return null;
		}
		final int[] pixels = decode(clanCrest, width, height);
		final int factor = width / 8;
		final int[] small = new int[8 * height];
		for (int y = 0; y < height; y++)
		{
			for (int x = 0; x < 8; x++)
			{
				int r = 0;
				int g = 0;
				int b = 0;
				int solid = 0;
				for (int i = 0; i < factor; i++)
				{
					final int argb = pixels[(y * width) + (x * factor) + i];
					if ((argb >>> 24) != 0)
					{
						r += (argb >> 16) & 0xFF;
						g += (argb >> 8) & 0xFF;
						b += argb & 0xFF;
						solid++;
					}
				}
				small[(y * 8) + x] = (solid * 2) < factor ? 0 : (0xFF000000 | ((r / solid) << 16) | ((g / solid) << 8) | (b / solid));
			}
		}
		final byte[] out = new byte[128 + ((8 / 4) * (height / 4) * 8)];
		System.arraycopy(clanCrest, 0, out, 0, 128);
		final ByteBuffer dds = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN);
		dds.putInt(16, 8);
		dds.putInt(20, out.length - 128);
		encode(small, 8, height, dds);
		return out;
	}

	static int[] decode(byte[] data, int width, int height)
	{
		final ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		final int[] pixels = new int[width * height];
		int offset = 128;
		for (int by = 0; by < (height / 4); by++)
		{
			for (int bx = 0; bx < (width / 4); bx++)
			{
				final int c0 = in.getShort(offset) & 0xFFFF;
				final int c1 = in.getShort(offset + 2) & 0xFFFF;
				final int bits = in.getInt(offset + 4);
				offset += 8;
				final int[] palette = palette(c0, c1);
				for (int i = 0; i < 16; i++)
				{
					pixels[(((by * 4) + (i / 4)) * width) + (bx * 4) + (i % 4)] = palette[(bits >>> (i * 2)) & 3];
				}
			}
		}
		return pixels;
	}

	private static void encode(int[] pixels, int width, int height, ByteBuffer out)
	{
		int offset = 128;
		for (int by = 0; by < (height / 4); by++)
		{
			for (int bx = 0; bx < (width / 4); bx++)
			{
				final int[] block = new int[16];
				boolean transparent = false;
				int low = -1;
				int high = -1;
				for (int i = 0; i < 16; i++)
				{
					final int argb = pixels[(((by * 4) + (i / 4)) * width) + (bx * 4) + (i % 4)];
					block[i] = argb;
					if ((argb >>> 24) == 0)
					{
						transparent = true;
						continue;
					}
					final int c = to565(argb);
					if ((low < 0) || (luma(c) < luma(low)))
					{
						low = c;
					}
					if ((high < 0) || (luma(c) > luma(high)))
					{
						high = c;
					}
				}
				int c0;
				int c1;
				if (low < 0)
				{
					c0 = 0;
					c1 = 0;
				}
				else if (transparent || (low == high))
				{
					// Три цвета и прозрачный: c0 <= c1.
					c0 = Math.min(low, high);
					c1 = Math.max(low, high);
				}
				else
				{
					c0 = Math.max(low, high);
					c1 = Math.min(low, high);
				}
				final int[] palette = palette(c0, c1);
				final int colors = c0 > c1 ? 4 : 3;
				int bits = 0;
				for (int i = 0; i < 16; i++)
				{
					int index = 3;
					if ((block[i] >>> 24) != 0)
					{
						long best = Long.MAX_VALUE;
						for (int k = 0; k < colors; k++)
						{
							final long d = distance(block[i], palette[k]);
							if (d < best)
							{
								best = d;
								index = k;
							}
						}
					}
					bits |= index << (i * 2);
				}
				out.putShort(offset, (short) c0);
				out.putShort(offset + 2, (short) c1);
				out.putInt(offset + 4, bits);
				offset += 8;
			}
		}
	}

	private static int[] palette(int c0, int c1)
	{
		final int a = from565(c0);
		final int b = from565(c1);
		if (c0 > c1)
		{
			return new int[]
			{
				a,
				b,
				mix(a, b, 2, 1),
				mix(a, b, 1, 2)
			};
		}
		return new int[]
		{
			a,
			b,
			mix(a, b, 1, 1),
			0
		};
	}

	private static int mix(int a, int b, int wa, int wb)
	{
		final int sum = wa + wb;
		final int r = ((((a >> 16) & 0xFF) * wa) + (((b >> 16) & 0xFF) * wb)) / sum;
		final int g = ((((a >> 8) & 0xFF) * wa) + (((b >> 8) & 0xFF) * wb)) / sum;
		final int bl = (((a & 0xFF) * wa) + ((b & 0xFF) * wb)) / sum;
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static int from565(int c)
	{
		final int r = (c >> 11) & 0x1F;
		final int g = (c >> 5) & 0x3F;
		final int b = c & 0x1F;
		return 0xFF000000 | (((r << 3) | (r >> 2)) << 16) | (((g << 2) | (g >> 4)) << 8) | ((b << 3) | (b >> 2));
	}

	private static int to565(int argb)
	{
		return ((((argb >> 16) & 0xFF) >> 3) << 11) | ((((argb >> 8) & 0xFF) >> 2) << 5) | ((argb & 0xFF) >> 3);
	}

	private static int luma(int c565)
	{
		final int argb = from565(c565);
		return (((argb >> 16) & 0xFF) * 3) + (((argb >> 8) & 0xFF) * 6) + (argb & 0xFF);
	}

	private static long distance(int a, int b)
	{
		final long dr = ((a >> 16) & 0xFF) - ((b >> 16) & 0xFF);
		final long dg = ((a >> 8) & 0xFF) - ((b >> 8) & 0xFF);
		final long db = (a & 0xFF) - (b & 0xFF);
		return (dr * dr) + (dg * dg) + (db * db);
	}

	private Asset choose(Set<String> used, int clanId)
	{
		final int start = Math.floorMod(clanId * 0x9E3779B9, _pool.size());
		for (int i = 0; i < _pool.size(); i++)
		{
			final Asset asset = _pool.get((start + i) % _pool.size());
			if (!used.contains(asset.hash()) && !_retired.contains(asset.hash()))
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

	private static Set<String> loadRetired()
	{
		final Set<String> result = new HashSet<>();
		if (Files.isRegularFile(RETIRED))
		{
			try
			{
				for (String line : Files.readAllLines(RETIRED))
				{
					if (!line.isBlank())
					{
						result.add(line.strip());
					}
				}
			}
			catch (IOException e)
			{
				LOGGER.log(Level.WARNING, "PhantomClanCrests: could not load the retired crests.", e);
			}
		}
		return result;
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
			final int before = first.getCrestId();
			final String oldHash = hash(CrestTable.getInstance().getCrest(before).getData());
			final boolean added = _retired.add(oldHash);
			final int replaced = give();
			final String newHash = hash(CrestTable.getInstance().getCrest(first.getCrestId()).getData());
			if (added)
			{
				_retired.remove(oldHash);
			}
			LOGGER.info(tag + "retired " + _retired.size() + " on file; retiring one: replaced " + replaced + ", crest changed " + ((before != first.getCrestId()) && !oldHash.equals(newHash)) + ", other crest kept " + (kept == second.getCrestId()) + ", old row gone " + (CrestTable.getInstance().getCrest(before) == null) + ".");
			// Союз во главе с первым кланом (ep-alliance-crest-not-assigned): эмблема союза 8×16 у обоих.
			first.setAllyId(first.getId());
			first.setAllyName("SelfTestAlly");
			second.setAllyId(first.getId());
			second.setAllyName("SelfTestAlly");
			final int allies = giveAlly();
			final Crest ally = CrestTable.getInstance().getCrest(first.getAllyCrestId());
			final byte[] allyData = ally == null ? null : ally.getData();
			final int allyWidth = allyData == null ? 0 : ByteBuffer.wrap(allyData).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
			LOGGER.info(tag + "alliance: given " + allies + ", crest " + first.getAllyCrestId() + ", the same at the member " + ((first.getAllyCrestId() != 0) && (second.getAllyCrestId() == first.getAllyCrestId())) + ", " + (allyData == null ? 0 : allyData.length) + " bytes, width " + allyWidth + ", valid " + ((allyData != null) && valid(allyData)) + ", second pass gives " + giveAlly() + ".");
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
					if (clan.getAllyId() != 0)
					{
						if (clan.getAllyId() == clan.getId())
						{
							clan.changeAllyCrest(0, false);
						}
						clan.setAllyId(0);
						clan.setAllyName(null);
					}
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
