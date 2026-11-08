package org.l2jmobius.gameserver.managers.world;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.LinkedHashMap;
import java.util.Map;

/** Состояние поручений x10 без серверных API: лимиты, периоды и сохранение. */
final class X10TaskState
{
	static final int DAILY_LIMIT = 5;
	final String period;
	final Map<String, Task> tasks = new LinkedHashMap<>();

	enum Kind
	{
		HUNT, SPOT, GROUP, CHAMPION, RAID, RESOURCE, CRAFT, EXPLORE, DAILY, EVENT
	}

	static final class Task
	{
		final String id;
		final Kind kind;
		final int goal;
		final int level;
		final int x;
		final int y;
		final int z;
		int done;
		boolean accepted;
		boolean claimed;
		boolean abandoned;
		String visited = "";

		Task(String id, Kind kind, int goal, int level, int x, int y, int z)
		{
			this.id = id;
			this.kind = kind;
			this.goal = Math.max(1, goal);
			this.level = level;
			this.x = x;
			this.y = y;
			this.z = z;
		}

		boolean add(int amount)
		{
			if (!accepted || claimed || (amount <= 0) || (done >= goal))
			{
				return false;
			}
			done = (int) Math.min(goal, (long) done + amount);
			return true;
		}

		boolean visit(String key)
		{
			if ((";" + visited).contains(";" + key + ";"))
			{
				return false;
			}
			if (!add(1))
			{
				return false;
			}
			visited += key + ";";
			return true;
		}
	}

	X10TaskState(String period)
	{
		this.period = period;
	}

	int taken()
	{
		return (int) tasks.values().stream().filter(task -> task.accepted || task.abandoned).count();
	}

	boolean accept(String id)
	{
		final Task task = tasks.get(id);
		if ((task == null) || task.accepted || task.abandoned || (taken() >= DAILY_LIMIT))
		{
			return false;
		}
		task.accepted = true;
		return true;
	}

	boolean abandon(String id)
	{
		final Task task = tasks.get(id);
		if ((task == null) || !task.accepted || task.claimed) { return false; }
		task.accepted = false;
		task.abandoned = true;
		return true;
	}

	static String day(LocalDateTime now, int hour, int minute)
	{
		return now.minusHours(hour).minusMinutes(minute).toLocalDate().toString();
	}

	static String week(String day)
	{
		return LocalDate.parse(day).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toString();
	}

	String encode()
	{
		final StringBuilder out = new StringBuilder("2|").append(period);
		for (Task task : tasks.values())
		{
			out.append('|').append(task.id).append(',').append(task.kind).append(',').append(task.goal).append(',').append(task.done)
				.append(',').append(task.accepted ? 1 : 0).append(',').append(task.claimed ? 1 : 0).append(',').append(task.level)
				.append(',').append(task.x).append(',').append(task.y).append(',').append(task.z).append(',').append(task.visited).append(',').append(task.abandoned ? 1 : 0);
		}
		return out.toString();
	}

	static X10TaskState decode(String value)
	{
		final String[] parts = value.split("\\|", -1);
		if ((parts.length < 2) || !(parts[0].equals("1") || parts[0].equals("2")) || (parts.length > 34))
		{
			throw new IllegalArgumentException("Invalid task state");
		}
		LocalDate.parse(parts[1]);
		final X10TaskState state = new X10TaskState(parts[1]);
		for (int i = 2; i < parts.length; i++)
		{
			final String[] f = parts[i].split(",", -1);
			if ((f.length != (parts[0].equals("2") ? 12 : 11)) || !f[0].matches("[a-z_]+") || (f[10].length() > 256))
			{
				throw new IllegalArgumentException("Invalid task record");
			}
			final int goal = Integer.parseInt(f[2]);
			if ((goal < 1) || (goal > 1000000) || !SetHolder.FLAGS.contains(f[4]) || !SetHolder.FLAGS.contains(f[5]))
			{
				throw new IllegalArgumentException("Invalid task goal or flags");
			}
			final Task task = new Task(f[0], Kind.valueOf(f[1]), goal, Integer.parseInt(f[6]), Integer.parseInt(f[7]), Integer.parseInt(f[8]), Integer.parseInt(f[9]));
			task.done = Integer.parseInt(f[3]);
			task.accepted = f[4].equals("1");
			task.claimed = f[5].equals("1");
			task.visited = f[10];
			task.abandoned = f.length == 12 && f[11].equals("1");
			if (f.length == 12 && (!SetHolder.FLAGS.contains(f[11]) || (task.abandoned && (task.accepted || task.claimed)))) { throw new IllegalArgumentException("Invalid abandoned task"); }
			if ((task.goal > 1000000) || (task.done < 0) || (task.done > task.goal) || (task.level < 1) || (task.level > 200) || (task.claimed && !task.accepted) || (state.tasks.put(task.id, task) != null))
			{
				throw new IllegalArgumentException("Invalid task values");
			}
		}
		return state;
	}

	private static final class SetHolder
	{
		static final java.util.Set<String> FLAGS = java.util.Set.of("0", "1");
	}
}
