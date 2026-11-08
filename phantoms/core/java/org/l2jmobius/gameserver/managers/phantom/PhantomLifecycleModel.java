package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Жизненный цикл охотников (раздел 4.151, вклад пользователя, решение автора 2026-11-07): чистые правила без сервера и БД, их проверяет
 * {@link PhantomLifecycleModelTest}. Тип игрока и личный потолок уровня считаются из имени, как расписание суток (та же формула, что
 * {@code PhantomDayCycle.mix}), поэтому перезапуск ничего не тасует. Время жизни идёт в сыгранных днях — днях, когда охотник был в мире: выключенный на неделю
 * сервер не копит уходов, а офлайн-прогресса нет.
 */
final class PhantomLifecycleModel
{
	/** С какого уровня охотник, уже живший до включения ключа, — старожил: насовсем не уходит. */
	static final int OLD_LEVEL = 76;
	/** Полосы уровней для снимка населения и самопроверки: верхний уровень каждой. */
	static final int[] BAND_TOP = { 20, 40, 60, 75, 99 };
	/** Версия записи. */
	private static final String VERSION = "1";

	private static final int SALT_KIND = 31;
	private static final int SALT_TARGET = 32;
	private static final int SALT_REST = 33;
	private static final int SALT_QUIT = 34;
	private static final int SALT_ORDER = 35;
	private static final int SALT_QUOTA = 36;
	private static final int SALT_RETYPE = 37;

	/**
	 * Тип игрока: доля среди новых, потолок уровня (от и до), сколько сыгранных дней живёт на потолке до ухода насовсем, через сколько сыгранных дней бросает, даже не
	 * дойдя (0 — не бросает).
	 */
	enum Kind
	{
		/** Залётный новичок: пара вечеров, низкий потолок. */
		TOURIST("новичок", 27, 5, 25, 5, 14, 10, 20),
		/** Казуал: вечера и выходные, до середины. */
		CASUAL("казуал", 25, 18, 48, 10, 25, 30, 60),
		/** Обычный: основа сервера. */
		REGULAR("обычный", 21, 38, 72, 10, 30, 0, 0),
		/** Клановый: долго, держится за своих. */
		SOCIAL("клановый", 14, 45, 75, 15, 40, 0, 0),
		/** Торговец: спойл, крафт, лавка; уровень ему не главное. */
		ARTISAN("торговец", 8, 30, 70, 20, 50, 0, 0),
		/** Старожил: потолок наверху, месяцы игры. */
		VETERAN("старожил", 5, 76, 99, 15, 40, 0, 0);

		final String title;
		final int weight;
		final int targetMin;
		final int targetMax;
		final int restMin;
		final int restMax;
		final int quitMin;
		final int quitMax;

		Kind(String title, int weight, int targetMin, int targetMax, int restMin, int restMax, int quitMin, int quitMax)
		{
			this.title = title;
			this.weight = weight;
			this.targetMin = targetMin;
			this.targetMax = targetMax;
			this.restMin = restMin;
			this.restMax = restMax;
			this.quitMin = quitMin;
			this.quitMax = quitMax;
		}
	}

	/**
	 * Биография охотника.
	 * @param kind тип игрока
	 * @param target личный потолок уровня: дошёл — дальше не качается
	 * @param born день первой записи
	 * @param played сколько дней был в мире
	 * @param last последний такой день
	 * @param doneAt на каком сыгранном дне дошёл до потолка; -1 — не дошёл
	 * @param gone день ухода насовсем; -1 — играет
	 * @param newcomer пришёл новичком на освободившееся место
	 * @param oldTimer старожил, живший до включения ключа: насовсем не уходит
	 */
	record Life(Kind kind, int target, long born, int played, long last, int doneAt, long gone, boolean newcomer, boolean oldTimer)
	{
		boolean active()
		{
			return gone < 0;
		}
	}

	/**
	 * Решение директора на день.
	 * @param retire кто уходит насовсем
	 * @param admit кто из таблицы сверх {@code HuntersMax} приходит новичком
	 * @param quota предел дня
	 */
	record Plan(List<String> retire, List<String> admit, int quota)
	{
	}

	private PhantomLifecycleModel()
	{
	}

	/**
	 * Та же формула, что {@code PhantomDayCycle.mix}: модель обходится без сервера.
	 */
	static int mix(String name, long day, int salt, int bound)
	{
		long x = (name.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B97F4A7C15L) + (day * 0xC2B2AE3D27D4EB4FL) + (salt * 0x165667B19E3779F9L);
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return (int) Long.remainderUnsigned(x ^ (x >>> 31), bound);
	}

	private static int between(String name, int salt, int min, int max)
	{
		return max <= min ? min : min + mix(name, 0, salt, (max - min) + 1);
	}

	/**
	 * @return тип по имени среди тех, чей потолок не ниже {@code level}
	 */
	private static Kind pick(String name, int salt, int level, int maxLevel)
	{
		int total = 0;
		for (Kind kind : Kind.values())
		{
			total += Math.min(kind.targetMax, maxLevel) >= level ? kind.weight : 0;
		}
		if (total <= 0)
		{
			return Kind.VETERAN;
		}
		int roll = mix(name, 0, salt, total);
		for (Kind kind : Kind.values())
		{
			if (Math.min(kind.targetMax, maxLevel) < level)
			{
				continue;
			}
			roll -= kind.weight;
			if (roll < 0)
			{
				return kind;
			}
		}
		return Kind.VETERAN;
	}

	static Kind kind(String name, int maxLevel)
	{
		return pick(name, SALT_KIND, 1, maxLevel);
	}

	static int target(String name, Kind kind, int maxLevel)
	{
		return Math.min(maxLevel, between(name, SALT_TARGET, Math.min(kind.targetMin, maxLevel), Math.min(kind.targetMax, maxLevel)));
	}

	static int rest(String name, Kind kind)
	{
		return between(name, SALT_REST, kind.restMin, kind.restMax);
	}

	static int quit(String name, Kind kind)
	{
		return kind.quitMax <= 0 ? 0 : between(name, SALT_QUIT, kind.quitMin, kind.quitMax);
	}

	/**
	 * Первая запись. Новичок — с типом по имени. Кто уже жил до ключа, получает тип с учётом нынешнего уровня (тип, чей потолок ниже, ему не достаётся), с
	 * {@value #OLD_LEVEL}-го он старожил и насовсем не уходит; уже стоящий на потолке считается дошедшим с первого дня.
	 */
	static Life create(String name, int level, long day, int maxLevel, boolean newcomer)
	{
		if (newcomer || (level <= 1))
		{
			final Kind kind = kind(name, maxLevel);
			return new Life(kind, target(name, kind, maxLevel), day, 0, Long.MIN_VALUE, -1, -1, newcomer, false);
		}

		final boolean old = level >= Math.min(OLD_LEVEL, maxLevel);
		Kind kind = old ? Kind.VETERAN : kind(name, maxLevel);
		if (Math.min(kind.targetMax, maxLevel) < level)
		{
			kind = pick(name, SALT_RETYPE, level, maxLevel);
		}
		final int target = Math.max(level, target(name, kind, maxLevel));
		return new Life(kind, target, day, 0, Long.MIN_VALUE, level >= target ? 0 : -1, -1, false, old);
	}

	/**
	 * Охотник в мире в день {@code day}: +1 сыгранный день (один раз за день), дошёл до потолка — отметка.
	 */
	static Life observe(Life life, int level, long day)
	{
		final int played = life.last() == day ? life.played() : life.played() + 1;
		final int doneAt = (life.doneAt() < 0) && (level >= life.target()) ? played : life.doneAt();
		return new Life(life.kind(), life.target(), life.born(), played, day, doneAt, life.gone(), life.newcomer(), life.oldTimer());
	}

	/**
	 * @return дошёл до личного потолка: дальше не качается
	 */
	static boolean capped(Life life, int level)
	{
		return level >= life.target();
	}

	/**
	 * @return пора уходить насовсем: отжил своё на потолке или бросил, не дойдя; старожил не уходит
	 */
	static boolean due(Life life, String name)
	{
		if (!life.active() || life.oldTimer())
		{
			return false;
		}
		if ((life.doneAt() >= 0) && ((life.played() - life.doneAt()) >= rest(name, life.kind())))
		{
			return true;
		}
		final int quit = quit(name, life.kind());
		return (quit > 0) && (life.played() >= quit);
	}

	static Life retire(Life life, long day)
	{
		return new Life(life.kind(), life.target(), life.born(), life.played(), life.last(), life.doneAt(), day, life.newcomer(), life.oldTimer());
	}

	/**
	 * @return предел новичков и уходов за день: {@code perDay} ± 1, не больше {@code max}
	 */
	static int quota(long day, int perDay, int max)
	{
		if ((perDay <= 0) || (max <= 0))
		{
			return 0;
		}
		return Math.max(0, Math.min(max, (perDay - 1) + mix("quota", day, SALT_QUOTA, 3)));
	}

	/**
	 * Директор населения на день. Новички приходят только на уже свободные места, не больше предела дня; уходов тоже не больше предела, и только пока в таблице есть
	 * кому прийти на место: мир не пустеет, а свободных мест разом не больше предела дня. Кто уходит первым из готовых — по имени и дню, без очереди по таблице.
	 * @param seats мест в мире ({@code HuntersMax})
	 * @param active сколько из них занято
	 * @param due готовы уйти
	 * @param spares ждут места, в порядке таблицы
	 */
	static Plan direct(long day, int seats, int active, List<String> due, List<String> spares, int perDay, int max)
	{
		final int quota = quota(day, perDay, max);
		final int free = Math.max(0, seats - active);
		final int admit = Math.min(Math.min(free, quota), spares.size());
		final int stillFree = free - admit;
		final int retire = Math.max(0, Math.min(Math.min(due.size(), quota - stillFree), spares.size() - admit - stillFree));
		final List<String> order = new ArrayList<>(due);
		order.sort(Comparator.comparingInt((String name) -> mix(name, day, SALT_ORDER, 1000000)).thenComparing(Comparator.naturalOrder()));
		return new Plan(List.copyOf(order.subList(0, retire)), List.copyOf(spares.subList(0, admit)), quota);
	}

	static int band(int level)
	{
		for (int b = 0; b < BAND_TOP.length; b++)
		{
			if (level <= BAND_TOP[b])
			{
				return b;
			}
		}
		return BAND_TOP.length - 1;
	}

	static String bandName(int b)
	{
		return b == (BAND_TOP.length - 1) ? (BAND_TOP[b - 1] + 1) + "+" : (b == 0 ? 1 : BAND_TOP[b - 1] + 1) + "–" + BAND_TOP[b];
	}

	/**
	 * @return запись для хранения: {@code 1;ТИП;потолок;рождён;сыграно;последний;дошёл;ушёл;флаги}
	 */
	static String encode(Life life)
	{
		return VERSION + ";" + life.kind().name() + ";" + life.target() + ";" + life.born() + ";" + life.played() + ";" + (life.last() == Long.MIN_VALUE ? "-" : Long.toString(life.last())) + ";" + life.doneAt() + ";" + life.gone() + ";" + (life.newcomer() ? "n" : "") + (life.oldTimer() ? "o" : "");
	}

	/**
	 * @return запись или {@code null}, если она битая или другой версии (охотник не трогается, запись создаётся заново)
	 */
	static Life decode(String text)
	{
		if ((text == null) || text.isEmpty())
		{
			return null;
		}
		final String[] part = text.split(";", -1);
		if ((part.length != 9) || !VERSION.equals(part[0]))
		{
			return null;
		}
		try
		{
			final Kind kind = Kind.valueOf(part[1]);
			final int target = Integer.parseInt(part[2]);
			final int played = Integer.parseInt(part[4]);
			final int doneAt = Integer.parseInt(part[6]);
			if ((target < 1) || (played < 0) || (doneAt < -1) || (doneAt > played) || !part[8].matches("n?o?"))
			{
				return null;
			}
			return new Life(kind, target, Long.parseLong(part[3]), played, "-".equals(part[5]) ? Long.MIN_VALUE : Long.parseLong(part[5]), doneAt, Long.parseLong(part[7]), part[8].contains("n"), part[8].contains("o"));
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}
}
