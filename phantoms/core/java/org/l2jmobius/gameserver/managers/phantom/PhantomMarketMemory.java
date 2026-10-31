package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;

/**
 * L2Hub: память рынка (docs/LIVE_SERVER.md, п. 15; docs/WORLD.md, «Фейки в городах»; ключ {@code TownFakesMarketMemory}). Сделки в лавках фейков и между охотниками складываются
 * в среднюю цену и спрос по каждой вещи. Спрос сдвигает цену, которую фейки поставят при следующем пополнении лавки ({@link #factor}), иногда случается дефицит материала,
 * а торговцы обсуждают это в торговом чате ({@link #rumor}). Цены магазинов и рейты сервера не меняются, границы лавок — в {@link PhantomStores}.
 * <p>
 * Раз в {@value #TICK_MINUTES} минут фейки торгуют между собой ({@link PhantomStores#trade}), память пишется в {@code log/L2HubMarket.json} (её читает и лаунчер), а движение цен — в лог.
 */
public final class PhantomMarketMemory
{
	private static final Logger LOGGER = Logger.getLogger(PhantomMarketMemory.class.getName());
	private static final Path FILE = Paths.get("./log/L2HubMarket.json");
	private static final Pattern LINE = Pattern.compile("\"id\":(\\d+),.*\"average\":([\\d.]+),\"last\":(\\d+),\"deals\":(\\d+),\"units\":(\\d+),\"pressure\":(-?[\\d.]+),\"at\":(\\d+),\"shortageUntil\":(\\d+)");

	private static final int TICK_MINUTES = 10;
	/** Вес новой сделки в средней цене. */
	private static final double AVERAGE_WEIGHT = 0.2;
	/** Спрос затухает вдвое за столько часов. */
	private static final double PRESSURE_HALF_LIFE_HOURS = 12;
	/** Партия на столько адены сдвигает спрос на единицу; мелочь — на {@value #MIN_DEAL_WEIGHT}. */
	private static final double BATCH_ADENA = 200000;
	private static final double MIN_DEAL_WEIGHT = 0.05;
	/** Самый большой спрос (и самое большое затоваривание) сдвигает цену на столько. */
	private static final double PRESSURE_SPAN = 0.15;
	/** Дефицит добавляет к цене столько. */
	private static final double SHORTAGE_EXTRA = 0.25;
	/** Шанс дефицита за один тик, %: в среднем раз в 8 часов игры сервера. */
	private static final int SHORTAGE_CHANCE = 2;
	private static final int SHORTAGE_HOURS_MIN = 6;
	private static final int SHORTAGE_HOURS_MAX = 24;
	/** С какого сдвига цены, %, торговцы это обсуждают. */
	private static final int RUMOR_PERCENT = 5;
	private static final int CURVE_ITEMS = 8;

	private static volatile boolean _on;
	private static final Map<Integer, Entry> ITEMS = new ConcurrentHashMap<>();

	/**
	 * О чём говорят на рынке.
	 * @param kind up, down или short
	 * @param item вещь
	 * @param percent сдвиг цены, %
	 * @param price средняя цена по сделкам
	 */
	public record Rumor(String kind, String item, int percent, long price)
	{
	}

	private static final class Entry
	{
		double average;
		long last;
		long deals;
		long units;
		double pressure;
		long at;
		long shortageUntil;

		double pressure(long now)
		{
			return pressure * Math.pow(0.5, Math.max(0, now - at) / (PRESSURE_HALF_LIFE_HOURS * 3600000));
		}

		double factor(long now)
		{
			return 1 + (PRESSURE_SPAN * pressure(now)) + (now < shortageUntil ? SHORTAGE_EXTRA : 0);
		}
	}

	private PhantomMarketMemory()
	{
	}

	/**
	 * Включить память: прочитать файл и запустить торговлю фейков между собой. Без этого {@link #factor} всегда 1, а {@link #deal} ничего не делает.
	 */
	public static void start()
	{
		if (_on)
		{
			return;
		}
		_on = true;
		load();
		final long period = TICK_MINUTES * 60000L;
		ThreadPool.scheduleAtFixedRate(() -> tick(System.currentTimeMillis()), period, period);
		LOGGER.info(PhantomMarketMemory.class.getSimpleName() + ": on, known items " + ITEMS.size() + ".");
	}

	/**
	 * @return память включена
	 */
	static boolean isOn()
	{
		return _on;
	}

	/**
	 * Сделка на рынке.
	 * @param itemId вещь
	 * @param count сколько
	 * @param price цена за штуку
	 * @param demand вещь купили (спрос растёт), иначе сдали в скупку (затоваривание)
	 */
	public static void deal(int itemId, long count, long price, boolean demand)
	{
		if (_on && (count > 0) && (price > 0))
		{
			deal(itemId, count, price, demand, System.currentTimeMillis());
		}
	}

	private static void deal(int itemId, long count, long price, boolean demand, long now)
	{
		final Entry entry = ITEMS.computeIfAbsent(itemId, k -> new Entry());
		synchronized (entry)
		{
			entry.average = entry.deals == 0 ? price : (entry.average * (1 - AVERAGE_WEIGHT)) + (price * AVERAGE_WEIGHT);
			entry.last = price;
			entry.deals++;
			entry.units += count;
			final double weight = Math.max(MIN_DEAL_WEIGHT, Math.min(1, (count * price) / BATCH_ADENA));
			entry.pressure = Math.max(-1, Math.min(1, entry.pressure(now) + (demand ? weight : -weight)));
			entry.at = now;
		}
	}

	/**
	 * @param itemId вещь
	 * @return множитель к цене лавки при пополнении: спрос и дефицит поднимают, затоваривание опускает; выключено — 1
	 */
	public static double factor(int itemId)
	{
		final Entry entry = _on ? ITEMS.get(itemId) : null;
		return entry == null ? 1 : entry.factor(System.currentTimeMillis());
	}

	/**
	 * @return случайная заметная новость рынка или {@code null}, если цены стоят
	 */
	public static Rumor rumor()
	{
		if (!_on)
		{
			return null;
		}
		final long now = System.currentTimeMillis();
		final List<Rumor> rumors = new ArrayList<>();
		for (Map.Entry<Integer, Entry> pair : ITEMS.entrySet())
		{
			final Rumor rumor = rumor(pair.getKey(), pair.getValue(), now);
			if (rumor != null)
			{
				rumors.add(rumor);
			}
		}
		return rumors.isEmpty() ? null : rumors.get(Rnd.get(rumors.size()));
	}

	private static Rumor rumor(int itemId, Entry entry, long now)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		final int percent = (int) Math.round((entry.factor(now) - 1) * 100);
		if ((item == null) || (entry.deals == 0) || (Math.abs(percent) < RUMOR_PERCENT))
		{
			return null;
		}
		return new Rumor(now < entry.shortageUntil ? "short" : percent > 0 ? "up" : "down", item.getName(), Math.abs(percent), Math.round(entry.average));
	}

	/**
	 * Один шаг рынка: фейки торгуют между собой, иногда начинается дефицит, движение цен — в лог, память — в файл.
	 * @param now время шага: самопроверка гоняет его вперёд
	 */
	static void tick(long now)
	{
		try
		{
			for (PhantomStores.Trade trade : PhantomStores.trade(now))
			{
				deal(trade.itemId(), trade.count(), trade.price(), trade.demand(), now);
			}
			if (Rnd.get(100) < SHORTAGE_CHANCE)
			{
				shortage(PhantomStores.wantedMaterial(), now);
			}
			final String curve = curve(now);
			if (!curve.isEmpty())
			{
				LOGGER.info(PhantomMarketMemory.class.getSimpleName() + ": " + curve);
			}
			save(now);
		}
		catch (Exception e)
		{
			LOGGER.warning(PhantomMarketMemory.class.getSimpleName() + ": tick failed: " + e);
		}
	}

	/**
	 * Начать дефицит вещи.
	 * @param itemId вещь; 0 — ничего
	 * @param now время
	 */
	static void shortage(int itemId, long now)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item == null)
		{
			return;
		}
		final Entry entry = ITEMS.computeIfAbsent(itemId, k -> new Entry());
		synchronized (entry)
		{
			entry.shortageUntil = now + (Rnd.get(SHORTAGE_HOURS_MIN, SHORTAGE_HOURS_MAX) * 3600000L);
			if (entry.deals == 0)
			{
				entry.average = item.getReferencePrice();
				entry.last = item.getReferencePrice();
				entry.deals = 1;
				entry.at = now;
			}
		}
		LOGGER.info(PhantomMarketMemory.class.getSimpleName() + ": shortage of " + item.getName() + ".");
	}

	/**
	 * @param now время
	 * @return строка для лога: вещи с самым большим сдвигом цены — имя, средняя цена, сдвиг, сделок
	 */
	static String curve(long now)
	{
		final List<Map.Entry<Integer, Entry>> moved = new ArrayList<>(ITEMS.entrySet());
		moved.sort(Comparator.comparingDouble((Map.Entry<Integer, Entry> pair) -> -Math.abs(pair.getValue().factor(now) - 1)));
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Entry> pair : moved.subList(0, Math.min(CURVE_ITEMS, moved.size())))
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(pair.getKey());
			if (item != null)
			{
				final Entry entry = pair.getValue();
				sb.append(sb.length() == 0 ? "" : ", ").append(item.getName()).append(' ').append(Math.round(entry.average)).append(" (");
				sb.append(String.format("%+d%%", Math.round((entry.factor(now) - 1) * 100))).append(", deals ").append(entry.deals);
				sb.append(now < entry.shortageUntil ? ", shortage)" : ")");
			}
		}
		return sb.toString();
	}

	private static void save(long now) throws IOException
	{
		final StringBuilder sb = new StringBuilder("{\"updated\":").append(now).append(",\"items\":[\n");
		boolean first = true;
		for (Map.Entry<Integer, Entry> pair : ITEMS.entrySet())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(pair.getKey());
			if (item == null)
			{
				continue;
			}
			final Entry e = pair.getValue();
			sb.append(first ? "" : ",\n");
			first = false;
			sb.append("{\"id\":").append(pair.getKey()).append(",\"name\":\"").append(item.getName().replace("\\", "").replace("\"", "'")).append('"');
			sb.append(",\"percent\":").append(Math.round((e.factor(now) - 1) * 100));
			sb.append(",\"average\":").append(String.format(java.util.Locale.ROOT, "%.1f", e.average)).append(",\"last\":").append(e.last);
			sb.append(",\"deals\":").append(e.deals).append(",\"units\":").append(e.units);
			sb.append(",\"pressure\":").append(String.format(java.util.Locale.ROOT, "%.4f", e.pressure)).append(",\"at\":").append(e.at);
			sb.append(",\"shortageUntil\":").append(e.shortageUntil).append('}');
		}
		sb.append("\n]}\n");
		Files.createDirectories(FILE.getParent());
		final Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
		Files.writeString(tmp, sb, StandardCharsets.UTF_8);
		Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
	}

	private static void load()
	{
		if (!Files.isRegularFile(FILE))
		{
			return;
		}
		try
		{
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8))
			{
				final Matcher m = LINE.matcher(line);
				if (m.find())
				{
					final Entry entry = new Entry();
					entry.average = Double.parseDouble(m.group(2));
					entry.last = Long.parseLong(m.group(3));
					entry.deals = Long.parseLong(m.group(4));
					entry.units = Long.parseLong(m.group(5));
					entry.pressure = Double.parseDouble(m.group(6));
					entry.at = Long.parseLong(m.group(7));
					entry.shortageUntil = Long.parseLong(m.group(8));
					ITEMS.put(Integer.parseInt(m.group(1)), entry);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(PhantomMarketMemory.class.getSimpleName() + ": " + FILE + " not read: " + e);
		}
	}

	/**
	 * Самопроверка рынка: спрос поднимает цену, затоваривание опускает, дефицит добавляет сверху, время всё гасит, файл читается обратно.
	 * @param itemId вещь для проверки: её запись в памяти будет заменена
	 * @return что не сошлось, пусто — всё сошлось
	 */
	static List<String> selfTest(int itemId)
	{
		final List<String> failed = new ArrayList<>();
		final long now = System.currentTimeMillis();
		ITEMS.remove(itemId);
		deal(itemId, 1000, 1000, true, now);
		final double up = ITEMS.get(itemId).factor(now);
		if (up <= 1)
		{
			failed.add("спрос не поднял цену: " + up);
		}
		final double later = ITEMS.get(itemId).factor(now + (long) (PRESSURE_HALF_LIFE_HOURS * 3600000));
		if (Math.abs((later - 1) - ((up - 1) / 2)) > 0.001)
		{
			failed.add("спрос не затух вдвое: " + up + " → " + later);
		}
		for (int i = 0; i < 4; i++)
		{
			deal(itemId, 1000, 1000, false, now);
		}
		final double down = ITEMS.get(itemId).factor(now);
		if (down >= 1)
		{
			failed.add("затоваривание не опустило цену: " + down);
		}
		shortage(itemId, now);
		final double shortage = ITEMS.get(itemId).factor(now);
		if (Math.abs(shortage - down - SHORTAGE_EXTRA) > 0.001)
		{
			failed.add("дефицит: " + down + " → " + shortage);
		}
		final Rumor rumor = rumor(itemId, ITEMS.get(itemId), now);
		if ((rumor == null) || !"short".equals(rumor.kind()))
		{
			failed.add("новость о дефиците: " + rumor);
		}
		try
		{
			save(now);
			final Entry before = ITEMS.get(itemId);
			ITEMS.remove(itemId);
			load();
			final Entry after = ITEMS.get(itemId);
			if ((after == null) || (after.deals != before.deals) || (after.units != before.units) || (after.shortageUntil != before.shortageUntil) || (Math.abs(after.pressure - before.pressure) > 0.001))
			{
				failed.add("файл не прочитался обратно");
			}
		}
		catch (IOException e)
		{
			failed.add("файл: " + e);
		}
		ITEMS.remove(itemId);
		return failed;
	}
}
