package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
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
 * Союз, который возглавляет клан охотников, получает свою эмблему союза из того же набора (мост, ep-alliance-crest-not-assigned; вариант сопровождающего Эпилога,
 * решение автора 2026-11-07): середина эмблемы клана 8×16 (DXT1, 192 байта — предел {@code RequestSetAllyCrest}), не занятая ни у кого на сервере. Выбранную не меняет,
 * недостающие копии у кланов союза возвращает. Свободных в наборе не осталось — эмблема главы, сжатая по ширине до 8×16 ({@link #allyCrest}). Эмблему союза игрока не трогает.
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
	private final List<Asset> _allyPool = alliancePool(_pool, _retired);
	private long _nextCheck;
	private boolean _exhausted;
	private boolean _allyExhausted;

	private PhantomClanCrests()
	{
		LOGGER.info(getClass().getSimpleName() + ": loaded " + _pool.size() + " unique crests, " + _retired.size() + " retired, " + _allyPool.size() + " alliance crests.");
	}

	synchronized void control(long now)
	{
		if (!PhantomHuntersConfig.HUNTER_CLAN_CRESTS || !PhantomHuntersConfig.HUNTER_CLANS || (now < _nextCheck))
		{
			return;
		}
		_nextCheck = now + CHECK_GAP;
		give();
		giveAllies(_allyPool);
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
	 * Союзы во главе с кланом охотников (вариант сопровождающего Эпилога). Эмблема у союза уже есть — остаётся, кланам союза без неё возвращается та же копия.
	 * Нет — своя из набора союзов ({@link #alliancePool}), не занятая ни у кого; набор кончился — эмблема главы, сжатая до 8×16 ({@link #allyCrest}).
	 * Всем кланам союза — {@code changeAllyCrest}, как RequestSetAllyCrest.
	 * @param allyPool набор эмблем союза; самопроверка передаёт пустой, чтобы пройти запасной путь
	 * @return скольким союзам выдана
	 */
	private int giveAllies(List<Asset> allyPool)
	{
		final List<Clan> clans = new ArrayList<>(ClanTable.getInstance().getClans());
		clans.sort(Comparator.comparingInt(Clan::getId));
		final Set<String> used = new HashSet<>();
		for (Clan clan : clans)
		{
			final Crest crest = allianceCrest(clan);
			if (crest != null)
			{
				used.add(hash(crest.getData()));
			}
		}
		int given = 0;
		for (Clan leader : clans)
		{
			if ((leader.getAllyId() == 0) || (leader.getAllyId() != leader.getId()) || !PhantomHunterClans.isHunterClan(leader))
			{
				continue;
			}
			final List<Clan> members = clans.stream().filter(clan -> clan.getAllyId() == leader.getAllyId()).toList();
			Crest existing = allianceCrest(leader);
			if (existing == null)
			{
				for (Clan member : members)
				{
					existing = allianceCrest(member);
					if (existing != null)
					{
						break;
					}
				}
			}
			if (existing != null)
			{
				// Выбранную эмблему не меняем, только возвращаем копии кланам союза, где её нет.
				for (Clan member : members)
				{
					if (member.getAllyCrestId() == 0)
					{
						member.changeAllyCrest(existing.getId(), true);
					}
				}
				continue;
			}
			final Asset asset = choose(allyPool, used, Set.of(), leader.getAllyId());
			final byte[] data;
			if (asset != null)
			{
				_allyExhausted = false;
				data = asset.data();
			}
			else
			{
				if (!_allyExhausted)
				{
					LOGGER.warning(getClass().getSimpleName() + ": no unused alliance crests remain, alliances get the leader's crest compressed to 8x16; given crests stay.");
					_allyExhausted = true;
				}
				final Crest own = CrestTable.getInstance().getCrest(leader.getCrestId());
				data = own == null ? null : allyCrest(own.getData());
				if (data == null)
				{
					continue;
				}
			}
			final Crest crest = CrestTable.getInstance().createCrest(data, CrestType.ALLY);
			if (crest == null)
			{
				continue;
			}
			leader.changeAllyCrest(crest.getId(), false);
			used.add(hash(data));
			given++;
			LOGGER.info(getClass().getSimpleName() + ": alliance " + leader.getAllyName() + " of clan " + leader.getName() + " received " + (asset != null ? asset.name() : "the leader's crest compressed") + ".");
		}
		return given;
	}

	private static Crest allianceCrest(Clan clan)
	{
		final Crest crest = CrestTable.getInstance().getCrest(clan.getAllyCrestId());
		return ((crest != null) && (crest.getType() == CrestType.ALLY)) ? crest : null;
	}

	/**
	 * Набор эмблем союза из набора кланов (вариант сопровождающего Эпилога): середина эмблемы клана по ширине, 8×16, блоки DXT1 как есть, без пересжатия —
	 * два средних блока в каждой из четырёх строк. Эмблема клана не 16 в высоту или уже 8 — пропускается; снятые ({@code retired.txt}) — тоже.
	 * @param clans набор эмблем кланов
	 * @param retired суммы снятых
	 * @return эмблемы союза 192 байта, без повторов
	 */
	static List<Asset> alliancePool(List<Asset> clans, Set<String> retired)
	{
		final List<Asset> result = new ArrayList<>();
		final Set<String> hashes = new HashSet<>();
		for (Asset clan : clans)
		{
			final byte[] source = clan.data();
			if (retired.contains(clan.hash()) || !valid(source))
			{
				continue;
			}
			final ByteBuffer header = ByteBuffer.wrap(source).order(ByteOrder.LITTLE_ENDIAN);
			final int height = header.getInt(12);
			final int width = header.getInt(16);
			if ((height != 16) || (width < 8))
			{
				continue;
			}
			final int blocks = width / 4;
			final int from = (blocks - 2) / 2;
			final byte[] data = Arrays.copyOf(source, 192);
			final ByteBuffer out = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
			out.putInt(16, 8);
			out.putInt(20, 64);
			for (int row = 0; row < 4; row++)
			{
				System.arraycopy(source, 128 + (row * blocks * 8) + (from * 8), data, 128 + (row * 16), 16);
			}
			final String hash = hash(data);
			if (hashes.add(hash))
			{
				result.add(new Asset("ally-" + clan.name(), hash, data));
			}
		}
		return List.copyOf(result);
	}

	/**
	 * Правка при старте (вариант сопровождающего Эпилога): эмблемы кланов старого формата 16×12 (224 байта) переписываются в 16×16 с той же видимой частью,
	 * под новыми номерами, чтобы клиент не просил отвергнутую. Зовёт сервер до входа игроков; правильные эмблемы кланов и союзов не трогает.
	 * @return сколько эмблем поправлено
	 */
	public static int repairOnStartup()
	{
		return getInstance().repairExisting();
	}

	private synchronized int repairExisting()
	{
		int repaired = 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			final Crest old = CrestTable.getInstance().getCrest(clan.getCrestId());
			if (old == null)
			{
				continue;
			}
			final byte[] replacement = correctedClanCrest(old.getData(), old.getType());
			if (replacement == null)
			{
				continue;
			}
			final Crest next = CrestTable.getInstance().createCrest(replacement, CrestType.PLEDGE);
			if (next == null)
			{
				throw new IllegalStateException("Could not repair legacy crest for clan " + clan.getId() + "; stop before accepting players.");
			}
			clan.changeClanCrest(next.getId());
			repaired++;
			LOGGER.info(getClass().getSimpleName() + ": repaired clan " + clan.getName() + " crest " + old.getId() + " -> " + next.getId() + " (16x16 DDS, visible 16x12).");
		}
		LOGGER.info(getClass().getSimpleName() + ": startup repaired " + repaired + " legacy clan crests; valid clan and alliance crests retained.");
		return repaired;
	}

	static byte[] correctedClanCrest(byte[] data, CrestType type)
	{
		if ((type != CrestType.PLEDGE) || (data == null) || (data.length != 224))
		{
			return null;
		}
		final ByteBuffer old = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN);
		if ((old.getInt(0) != MAGIC) || (old.getInt(4) != 124) || (old.getInt(12) != 12) || (old.getInt(16) != 16) || (old.getInt(76) != 32) || (old.getInt(84) != DXT1))
		{
			return null;
		}
		final byte[] corrected = Arrays.copyOf(data, 256);
		final ByteBuffer header = ByteBuffer.wrap(corrected).order(ByteOrder.LITTLE_ENDIAN);
		header.putInt(12, 16);
		header.putInt(20, 128);
		if (!valid(corrected))
		{
			throw new IllegalStateException("Invalid corrected clan crest");
		}
		return corrected;
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
		return choose(_pool, used, _retired, clanId);
	}

	private static Asset choose(List<Asset> assets, Set<String> used, Set<String> retired, int id)
	{
		if (assets.isEmpty())
		{
			return null;
		}
		final int start = Math.floorMod(id * 0x9E3779B9, assets.size());
		for (int i = 0; i < assets.size(); i++)
		{
			final Asset asset = assets.get((start + i) % assets.size());
			if (!used.contains(asset.hash()) && !retired.contains(asset.hash()))
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
			// Союз во главе с первым кланом (ep-alliance-crest-not-assigned, вариант сопровождающего Эпилога): своя эмблема из набора союзов у обоих.
			first.setAllyId(first.getId());
			first.setAllyName("SelfTestAlly");
			second.setAllyId(first.getId());
			second.setAllyName("SelfTestAlly");
			final Set<String> poolHashes = new HashSet<>();
			for (Asset asset : _allyPool)
			{
				poolHashes.add(asset.hash());
			}
			final int allies = giveAllies(_allyPool);
			final byte[] allyData = crestData(first.getAllyCrestId());
			LOGGER.info(tag + "alliance: pool " + _allyPool.size() + ", given " + allies + ", crest " + first.getAllyCrestId() + ", from the alliance pool " + ((allyData != null) && poolHashes.contains(hash(allyData))) + ", the same at the member " + ((first.getAllyCrestId() != 0) && (second.getAllyCrestId() == first.getAllyCrestId())) + ", " + (allyData == null ? 0 : allyData.length) + " bytes, width " + width(allyData) + ", valid " + ((allyData != null) && valid(allyData)) + ", second pass gives " + giveAllies(_allyPool) + ".");
			// Копия у члена союза пропала — возвращается та же, выбранная у главы не меняется.
			final int chosen = first.getAllyCrestId();
			second.changeAllyCrest(0, true);
			final boolean lost = second.getAllyCrestId() == 0;
			final int copies = giveAllies(_allyPool);
			LOGGER.info(tag + "alliance copy: member lost it " + lost + ", pass gives " + copies + ", restored the same " + ((chosen != 0) && (second.getAllyCrestId() == chosen)) + ", leader kept " + (first.getAllyCrestId() == chosen) + ".");
			// Набор кончился — запасной путь: эмблема главы, сжатая до 8×16.
			first.changeAllyCrest(0, false);
			final int spare = giveAllies(List.of());
			final byte[] spareData = crestData(first.getAllyCrestId());
			final byte[] leaderData = crestData(first.getCrestId());
			LOGGER.info(tag + "alliance fallback: given " + spare + ", " + (spareData == null ? 0 : spareData.length) + " bytes, width " + width(spareData) + ", the leader's crest compressed " + ((spareData != null) && (leaderData != null) && Arrays.equals(spareData, allyCrest(leaderData))) + ", the same at the member " + ((first.getAllyCrestId() != 0) && (second.getAllyCrestId() == first.getAllyCrestId())) + ".");
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

	private static byte[] crestData(int crestId)
	{
		final Crest crest = CrestTable.getInstance().getCrest(crestId);
		return crest == null ? null : crest.getData();
	}

	private static int width(byte[] data)
	{
		return (data == null) || (data.length < 128) ? 0 : ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getInt(16);
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
