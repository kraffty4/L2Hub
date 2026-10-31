package org.l2jmobius.gameserver.managers.phantom;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: настоящие сутки (docs/PHANTOMS.md, раздел 4.62; docs/LIVE_SERVER.md, п. 1): вечером мир полон, к утру народу мало. У каждого охотника привычка (работяга, вечерний,
 * студент, сова, задрот) и на каждый день свой час входа, длина сессии и шанс пропустить день: всё считается из имени и даты, поэтому перезапуск сервера расписание не
 * тасует и файла состояния нет. Выключатель {@code DailyRhythm}; {@code DailyRhythmStrength} — доля охотников, живущих по расписанию в данный день, остальные весь день
 * в сети; {@code DailyRhythmShift} — сдвиг часов, чтобы играющий ночью не сидел в пустом мире. По этим же часам живут фейки городов ({@code L2HubTownFakes}).
 */
public final class PhantomDayCycle
{
	private static final Logger LOGGER = Logger.getLogger(PhantomDayCycle.class.getName());

	private static final int DAY = 1440;
	private static final int SALT_HABIT = 1;
	private static final int SALT_SKIP = 2;
	private static final int SALT_START = 3;
	private static final int SALT_LENGTH = 4;
	private static final int SALT_FREE = 5;

	/** Самопроверка: столько мс мир живёт на каждом проверяемом часе. */
	private static final long TEST_STEP = 150000;
	private static final int[] TEST_HOURS =
	{
		21,
		5,
		13
	};

	/**
	 * Привычка: с какой по какую минуту суток входит и сколько минут сидит, в будни и в выходные; шанс пропустить день.
	 */
	private enum Habit
	{
		WORKER(1080, 1230, 180, 330, 660, 900, 360, 720, 20),
		EVENING(990, 1170, 240, 420, 960, 1170, 300, 480, 12),
		STUDENT(840, 990, 240, 480, 720, 840, 480, 720, 15),
		OWL(1260, 1410, 300, 480, 1200, 1410, 360, 540, 15),
		NOLIFE(540, 720, 840, 1080, 540, 720, 900, 1140, 3);

		final int startFrom;
		final int startTo;
		final int lengthFrom;
		final int lengthTo;
		final int restStartFrom;
		final int restStartTo;
		final int restLengthFrom;
		final int restLengthTo;
		final int skip;

		Habit(int startFrom, int startTo, int lengthFrom, int lengthTo, int restStartFrom, int restStartTo, int restLengthFrom, int restLengthTo, int skip)
		{
			this.startFrom = startFrom;
			this.startTo = startTo;
			this.lengthFrom = lengthFrom;
			this.lengthTo = lengthTo;
			this.restStartFrom = restStartFrom;
			this.restStartTo = restStartTo;
			this.restLengthFrom = restLengthFrom;
			this.restLengthTo = restLengthTo;
			this.skip = skip;
		}
	}

	private PhantomDayCycle()
	{
	}

	/**
	 * @return настоящие сутки включены; {@code PhantomHunters.ini} читается при старте и при изменении файла
	 */
	public static boolean enabled()
	{
		return PhantomHuntersConfig.DAILY_RHYTHM;
	}

	private static LocalDateTime clock(long now)
	{
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(now), ZoneId.systemDefault()).plusHours(PhantomHuntersConfig.DAILY_RHYTHM_SHIFT);
	}

	/**
	 * @return минута реальных суток со сдвигом {@code DailyRhythmShift}: распорядок фейков городов
	 */
	public static int minuteOfDay(long now)
	{
		return clock(now).get(ChronoField.MINUTE_OF_DAY);
	}

	/**
	 * @return номер реальных суток со сдвигом {@code DailyRhythmShift}
	 */
	static long day(long now)
	{
		return clock(now).toLocalDate().toEpochDay();
	}

	/**
	 * @return охотник сейчас по расписанию в сети
	 */
	static boolean wantsOnline(String name, long now)
	{
		final LocalDateTime time = clock(now);
		final long day = time.toLocalDate().toEpochDay();
		final int minute = time.get(ChronoField.MINUTE_OF_DAY);
		if (mix(name, day, SALT_FREE, 100) >= PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH)
		{
			return true;
		}
		// Вчерашняя сессия могла перейти через полночь.
		return inSession(name, day, minute) || inSession(name, day - 1, minute + DAY);
	}

	private static Habit habit(String name)
	{
		final int roll = mix(name, 0, SALT_HABIT, 100);
		return roll < 35 ? Habit.WORKER : roll < 60 ? Habit.EVENING : roll < 75 ? Habit.STUDENT : roll < 85 ? Habit.OWL : Habit.NOLIFE;
	}

	private static boolean inSession(String name, long day, int minute)
	{
		final Habit habit = habit(name);
		if (mix(name, day, SALT_SKIP, 100) < habit.skip)
		{
			return false;
		}
		final DayOfWeek weekDay = LocalDate.ofEpochDay(day).getDayOfWeek();
		final boolean rest = (weekDay == DayOfWeek.SATURDAY) || (weekDay == DayOfWeek.SUNDAY);
		final int startFrom = rest ? habit.restStartFrom : habit.startFrom;
		final int lengthFrom = rest ? habit.restLengthFrom : habit.lengthFrom;
		final int start = startFrom + mix(name, day, SALT_START, (rest ? habit.restStartTo : habit.startTo) - startFrom + 1);
		// В пятницу сидят дольше: завтра не на работу.
		final int length = lengthFrom + mix(name, day, SALT_LENGTH, (rest ? habit.restLengthTo : habit.lengthTo) - lengthFrom + 1) + (weekDay == DayOfWeek.FRIDAY ? 90 : 0);
		return (minute >= start) && (minute < (start + length));
	}

	/**
	 * @return число от 0 до bound, неизменное для имени, дня и соли; {@code String.hashCode} и арифметика заданы языком, поэтому перезапуск и другая машина дают то же
	 */
	static int mix(String name, long day, int salt, int bound)
	{
		long x = (name.toLowerCase(Locale.ROOT).hashCode() * 0x9E3779B97F4A7C15L) + (day * 0xC2B2AE3D27D4EB4FL) + (salt * 0x165667B19E3779F9L);
		x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
		x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
		return (int) Long.remainderUnsigned(x ^ (x >>> 31), bound);
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка DAYCYCLE ({@code HuntersSelfTest}, только на копии базы). Без мира: кривая по часам среды и субботы на всех именах и повторяемость. В мире: часы
	 * переставляются сдвигом на вечер, ночь и день, на каждом мир живёт {@value #TEST_STEP} мс, в лог идёт, сколько в мире против расписания.
	 * @param names имена всех охотников
	 * @param online сейчас в мире
	 */
	static void selfTest(List<String> names, java.util.function.Supplier<List<Player>> online)
	{
		final String tag = "PhantomDayCycle: SelfTest DAYCYCLE: ";
		final boolean keyWas = PhantomHuntersConfig.DAILY_RHYTHM;
		final int strengthWas = PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH;
		final int shiftWas = PhantomHuntersConfig.DAILY_RHYTHM_SHIFT;
		PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = 100;
		PhantomHuntersConfig.DAILY_RHYTHM_SHIFT = 0;
		final int[] habits = new int[Habit.values().length];
		for (String name : names)
		{
			habits[habit(name).ordinal()]++;
		}
		LOGGER.info(tag + names.size() + " hunters: workers " + habits[0] + ", evening " + habits[1] + ", students " + habits[2] + ", owls " + habits[3] + ", nolife " + habits[4] + ".");
		for (DayOfWeek weekDay : new DayOfWeek[]
		{
			DayOfWeek.WEDNESDAY,
			DayOfWeek.SATURDAY
		})
		{
			final LocalDateTime midnight = LocalDate.now().with(java.time.temporal.TemporalAdjusters.nextOrSame(weekDay)).atStartOfDay();
			final StringBuilder curve = new StringBuilder();
			boolean same = true;
			for (int hour = 0; hour < 24; hour++)
			{
				final long at = midnight.plusHours(hour).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
				int count = 0;
				for (String name : names)
				{
					final boolean wants = wantsOnline(name, at);
					same &= wants == wantsOnline(name, at);
					count += wants ? 1 : 0;
				}
				curve.append(String.format(Locale.ROOT, "%02d:%d ", hour, count));
			}
			LOGGER.info(tag + weekDay + ": " + curve + (same ? "" : "NOT REPEATABLE"));
		}
		PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = 50;
		final long at = System.currentTimeMillis();
		LOGGER.info(tag + "strength 50 at this hour: " + names.stream().filter(name -> wantsOnline(name, at)).count() + " of " + names.size() + "; strength 0: " + strengthZero(names, at) + ".");
		PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = 100;
		PhantomHuntersConfig.DAILY_RHYTHM = true;
		final List<Integer> hours = new ArrayList<>();
		for (int hour : TEST_HOURS)
		{
			hours.add(hour);
		}
		ThreadPool.schedule(() -> step(tag, names, online, hours, keyWas, strengthWas, shiftWas), 1000);
	}

	private static long strengthZero(List<String> names, long at)
	{
		PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = 0;
		final long count = names.stream().filter(name -> wantsOnline(name, at)).count();
		PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = 100;
		return count;
	}

	private static void step(String tag, List<String> names, java.util.function.Supplier<List<Player>> online, List<Integer> hours, boolean keyWas, int strengthWas, int shiftWas)
	{
		if (hours.isEmpty())
		{
			PhantomHuntersConfig.DAILY_RHYTHM = keyWas;
			PhantomHuntersConfig.DAILY_RHYTHM_STRENGTH = strengthWas;
			PhantomHuntersConfig.DAILY_RHYTHM_SHIFT = shiftWas;
			LOGGER.info(tag + "done.");
			return;
		}
		final int hour = hours.remove(0);
		PhantomHuntersConfig.DAILY_RHYTHM_SHIFT = hour - LocalDateTime.now().getHour();
		final long at = System.currentTimeMillis();
		final long wanted = names.stream().filter(name -> wantsOnline(name, at)).count();
		LOGGER.info(tag + "the clock moved to " + hour + ":00, in the world " + online.get().size() + ", by the schedule " + wanted + ".");
		ThreadPool.schedule(() ->
		{
			final long later = System.currentTimeMillis();
			LOGGER.info(tag + "after " + (TEST_STEP / 1000) + " s at " + hour + ":00: in the world " + online.get().size() + ", by the schedule " + names.stream().filter(name -> wantsOnline(name, later)).count() + ".");
			step(tag, names, online, hours, keyWas, strengthWas, shiftWas);
		}, TEST_STEP);
	}
}
