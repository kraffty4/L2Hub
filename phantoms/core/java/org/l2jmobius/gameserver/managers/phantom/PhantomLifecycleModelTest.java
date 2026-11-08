package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Kind;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Life;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Plan;

/**
 * Автономная проверка жизненного цикла без сервера, БД и JUnit: javac двух исходников, затем java с этим классом. Её же зовёт самопроверка LIFECYCLE на сервере.
 * Главное — симуляция мира на год вперёд: доли по уровням не вырождаются, волн уходов и новичков нет.
 */
final class PhantomLifecycleModelTest
{
	/** Мягкие ориентиры долей полос {@link PhantomLifecycleModel#PhantomLifecycleModel.BAND_TOP} из вклада пользователя (раздел 13), проценты. */
	static final int[] BAND_MIN = { 15, 20, 20, 15, 5 };
	static final int[] BAND_MAX = { 25, 30, 30, 25, 15 };
	/** Допуск к ориентирам, процентных пунктов: они мягкие. */
	static final int TOLERANCE = 6;
	/** Месячная доля полосы не гуляет сильнее, пунктов (мир на 100 мест: один охотник — пункт). */
	static final int DRIFT = 15;
	/** Средняя доля последней трети периода отличается от первой не больше, пунктов: мир не сползает. */
	static final int TREND = 6;

	private PhantomLifecycleModelTest()
	{
	}

	public static void main(String[] args)
	{
		final List<String> report = new ArrayList<>();
		final List<String> failed = check(report);
		report.forEach(System.out::println);
		if (!failed.isEmpty())
		{
			throw new IllegalStateException(String.join("; ", failed));
		}
		System.out.println("PhantomLifecycleModelTest: all checks passed.");
	}

	static List<String> check(List<String> report)
	{
		final List<String> failed = new ArrayList<>();
		final long day = 20400;

		// Тип и потолок — из имени, одинаковые при каждом вызове.
		final Life first = PhantomLifecycleModel.create("Fialka", 1, day, 85, true);
		final Life again = PhantomLifecycleModel.create("Fialka", 1, day + 9, 85, true);
		expect(failed, "тип по имени повторяется", first.kind() == again.kind() && first.target() == again.target());
		expect(failed, "потолок в пределах типа", (first.target() >= first.kind().targetMin) && (first.target() <= Math.min(85, first.kind().targetMax)));
		final Life interlude = PhantomLifecycleModel.create("Ded", 1, day, 80, true);
		expect(failed, "потолок не выше предела хроники", interlude.target() <= 80);

		// Кто жил до ключа: старожил не уходит, тип не ниже уровня.
		final Life old = PhantomLifecycleModel.create("Rys", 80, day, 85, false);
		expect(failed, "с 76-го — старожил", old.oldTimer() && (old.kind() == Kind.VETERAN) && (old.target() >= 80));
		final Life mid = PhantomLifecycleModel.create("Kuzya", 58, day, 85, false);
		expect(failed, "тип по уровню", Math.min(mid.kind().targetMax, 85) >= 58 && mid.target() >= 58 && !mid.oldTimer());
		Life longLife = old;
		for (int i = 0; i < 400; i++)
		{
			longLife = PhantomLifecycleModel.observe(longLife, 80, day + i);
		}
		expect(failed, "старожил не уходит насовсем", !PhantomLifecycleModel.due(longLife, "Rys"));

		// Сыгранные дни: один за день, офлайн не копятся.
		Life life = PhantomLifecycleModel.create("Alenka", 1, day, 85, true);
		life = PhantomLifecycleModel.observe(life, 5, day);
		life = PhantomLifecycleModel.observe(life, 7, day);
		expect(failed, "день считается один раз", life.played() == 1);
		life = PhantomLifecycleModel.observe(life, 9, day + 30);
		expect(failed, "месяц без входа — один сыгранный день", life.played() == 2);
		life = PhantomLifecycleModel.observe(life, life.target(), day + 31);
		expect(failed, "дошёл до потолка", PhantomLifecycleModel.capped(life, life.target()) && (life.doneAt() == 3));
		final int doneAt = life.doneAt();
		life = PhantomLifecycleModel.observe(life, life.target(), day + 32);
		expect(failed, "отметка потолка одна", life.doneAt() == doneAt);
		Life rest = life;
		int played = 0;
		while (!PhantomLifecycleModel.due(rest, "Alenka") && (played < 500))
		{
			rest = PhantomLifecycleModel.observe(rest, rest.target(), day + 40 + played++);
		}
		expect(failed, "уходит после своего отдыха на потолке", played <= PhantomLifecycleModel.rest("Alenka", rest.kind()) + 1);

		// Запись: туда и обратно, битая — null.
		final String text = PhantomLifecycleModel.encode(PhantomLifecycleModel.retire(life, day + 50));
		final Life back = PhantomLifecycleModel.decode(text);
		expect(failed, "запись читается: " + text, (back != null) && back.equals(PhantomLifecycleModel.retire(life, day + 50)));
		expect(failed, "свежая запись читается", PhantomLifecycleModel.decode(PhantomLifecycleModel.encode(first)) != null);
		expect(failed, "битая запись", (PhantomLifecycleModel.decode("1;NOPE;1;2;3;4;5;6;") == null) && (PhantomLifecycleModel.decode("1;CASUAL;x") == null) && (PhantomLifecycleModel.decode("2;CASUAL;30;1;1;1;-1;-1;") == null) && (PhantomLifecycleModel.decode(null) == null));

		// Предел дня и директор.
		boolean quotaOk = true;
		for (long d = day; d < (day + 365); d++)
		{
			final int quota = PhantomLifecycleModel.quota(d, 2, 4);
			quotaOk &= (quota >= 1) && (quota <= 3);
		}
		expect(failed, "предел дня 2 ± 1", quotaOk);
		expect(failed, "нет новичков — нет предела", PhantomLifecycleModel.quota(day, 0, 4) == 0);
		final Plan none = PhantomLifecycleModel.direct(day, 10, 10, List.of("a", "b", "c", "d", "e"), List.of(), 2, 4);
		expect(failed, "без запасных никто не уходит", none.retire().isEmpty() && none.admit().isEmpty());
		final Plan full = PhantomLifecycleModel.direct(day, 10, 4, List.of("a", "b"), List.of("x", "y", "z", "w", "v", "u", "t"), 2, 4);
		expect(failed, "новичков не больше предела, уходов — пока мест разом не больше предела", (full.admit().size() == full.quota()) && full.retire().isEmpty());
		final Plan again2 = PhantomLifecycleModel.direct(day, 10, 4, List.of("b", "a"), List.of("x", "y", "z", "w", "v", "u", "t"), 2, 4);
		expect(failed, "решение директора не зависит от порядка списка", again2.equals(full));

		// Год мира на двух скоростях кача.
		simulate(failed, report, "медленный кач", new double[] { 0.15, 0.4, 0.9, 1.6, 4 });
		simulate(failed, report, "быстрый кач", new double[] { 0.03, 0.08, 0.2, 0.4, 1 });
		return failed;
	}

	/**
	 * Мир на 100 мест, таблица на 1000 имён, год. В начале 100 охотников с уровнями от 1 до 85 поровну, живут до ключа. Охотник в мире 7 дней из 10 и качается
	 * {@code daysPerLevel} сыгранных дней на уровень по полосам, на своём потолке — нет.
	 */
	static void simulate(List<String> failed, List<String> report, String title, double[] daysPerLevel)
	{
		final int seats = 100;
		final int table = 1000;
		final int days = 480;
		final int warmup = 120;
		final long start = 20400;
		final Map<String, Life> lives = new HashMap<>();
		final Map<String, Integer> levels = new HashMap<>();
		final Map<String, Double> progress = new HashMap<>();
		final List<String> world = new ArrayList<>();
		for (int i = 0; i < seats; i++)
		{
			final String name = "Hunter" + i;
			final int level = 1 + PhantomLifecycleModel.mix(name, 0, 99, 85);
			world.add(name);
			levels.put(name, level);
			progress.put(name, 0.0);
			lives.put(name, PhantomLifecycleModel.create(name, level, start, 85, false));
		}
		int next = seats;
		int maxEvents = 0;
		int minActive = seats;
		int retired = 0;
		int admitted = 0;
		final int months = (days - warmup) / 30;
		final double[][] monthly = new double[months][PhantomLifecycleModel.BAND_TOP.length];
		final double[] total = new double[PhantomLifecycleModel.BAND_TOP.length];
		int sampled = 0;
		for (int d = 0; d < days; d++)
		{
			final long today = start + d;
			int active = 0;
			final List<String> due = new ArrayList<>();
			for (String name : world)
			{
				Life life = lives.get(name);
				if (!life.active())
				{
					continue;
				}
				active++;
				if (PhantomLifecycleModel.mix(name, today, 98, 10) >= 7)
				{
					continue;
				}
				life = PhantomLifecycleModel.observe(life, levels.get(name), today);
				int level = levels.get(name);
				if (!PhantomLifecycleModel.capped(life, level))
				{
					double gained = progress.get(name) + (1 / daysPerLevel[PhantomLifecycleModel.band(level)]);
					while ((gained >= 1) && (level < life.target()))
					{
						gained -= 1;
						level++;
						gained = gained * daysPerLevel[PhantomLifecycleModel.band(level - 1)] / daysPerLevel[PhantomLifecycleModel.band(level)];
					}
					progress.put(name, level >= life.target() ? 0 : gained);
					levels.put(name, level);
					life = PhantomLifecycleModel.observe(life, level, today);
				}
				lives.put(name, life);
				if (PhantomLifecycleModel.due(life, name))
				{
					due.add(name);
				}
			}
			final List<String> spares = new ArrayList<>();
			for (int i = next; i < table; i++)
			{
				spares.add("Hunter" + i);
			}
			final Plan plan = PhantomLifecycleModel.direct(today, seats, active, due, spares, 2, 4);
			for (String name : plan.retire())
			{
				lives.put(name, PhantomLifecycleModel.retire(lives.get(name), today));
			}
			for (String name : plan.admit())
			{
				world.add(name);
				levels.put(name, 1);
				progress.put(name, 0.0);
				lives.put(name, PhantomLifecycleModel.create(name, 1, today, 85, true));
				next++;
			}
			retired += plan.retire().size();
			admitted += plan.admit().size();
			maxEvents = Math.max(maxEvents, Math.max(plan.retire().size(), plan.admit().size()));
			active = active - plan.retire().size() + plan.admit().size();
			if (d >= 1)
			{
				minActive = Math.min(minActive, active);
			}
			if (d >= warmup)
			{
				final double[] share = shares(world, lives, levels);
				final int month = Math.min(months - 1, (d - warmup) / 30);
				for (int b = 0; b < PhantomLifecycleModel.BAND_TOP.length; b++)
				{
					monthly[month][b] += share[b] / 30;
					total[b] += share[b];
				}
				sampled++;
			}
		}

		final StringBuilder line = new StringBuilder(title + ": ушли " + retired + ", пришли " + admitted + ", в день не больше " + maxEvents + ", в мире не меньше " + minActive + "; доли");
		for (int b = 0; b < PhantomLifecycleModel.BAND_TOP.length; b++)
		{
			double low = 100;
			double high = 0;
			for (int m = 0; m < months; m++)
			{
				low = Math.min(low, monthly[m][b]);
				high = Math.max(high, monthly[m][b]);
			}
			final double mean = total[b] / sampled;
			final int third = Math.max(1, months / 3);
			double early = 0;
			double late = 0;
			for (int m = 0; m < third; m++)
			{
				early += monthly[m][b] / third;
				late += monthly[months - 1 - m][b] / third;
			}
			expect(failed, title + ": полоса " + PhantomLifecycleModel.bandName(b) + " не сползает, " + Math.round(early) + " → " + Math.round(late), Math.abs(late - early) <= TREND);
			line.append(String.format(" %s: %.0f%% (%.0f–%.0f, %.0f → %.0f)", PhantomLifecycleModel.bandName(b), mean, low, high, early, late));
			expect(failed, title + ": полоса " + PhantomLifecycleModel.bandName(b) + " " + Math.round(mean) + "% у ориентира " + BAND_MIN[b] + "–" + BAND_MAX[b], (mean >= (BAND_MIN[b] - TOLERANCE)) && (mean <= (BAND_MAX[b] + TOLERANCE)));
			expect(failed, title + ": полоса " + PhantomLifecycleModel.bandName(b) + " без волн, месяцы " + Math.round(low) + "–" + Math.round(high), (high - low) <= DRIFT);
		}
		report.add(line.toString());
		expect(failed, title + ": в день не больше предела", maxEvents <= 4);
		expect(failed, title + ": мир не пустеет", minActive >= (seats - 4));
		expect(failed, title + ": смена поколений идёт", (retired >= 100) && (admitted >= 100));
	}

	private static double[] shares(List<String> world, Map<String, Life> lives, Map<String, Integer> levels)
	{
		final double[] share = new double[PhantomLifecycleModel.BAND_TOP.length];
		int count = 0;
		for (String name : world)
		{
			if (lives.get(name).active())
			{
				share[PhantomLifecycleModel.band(levels.get(name))]++;
				count++;
			}
		}
		for (int b = 0; b < share.length; b++)
		{
			share[b] = count == 0 ? 0 : (100 * share[b]) / count;
		}
		return share;
	}

	private static void expect(List<String> failed, String what, boolean ok)
	{
		if (!ok)
		{
			failed.add(what);
		}
	}
}
