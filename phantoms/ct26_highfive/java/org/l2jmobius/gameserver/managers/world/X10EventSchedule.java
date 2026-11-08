package org.l2jmobius.gameserver.managers.world;

import java.util.EnumMap;
import java.util.Map;
import org.l2jmobius.gameserver.managers.world.X10EventRules.Kind;

/** Очередь по накопленному времени мира. Старые ожидающие форматы не голодают при совпадении периодов. */
final class X10EventSchedule
{
	record Spec(boolean enabled, int intervalMinutes, int delayMinutes, int durationMinutes) { }
	record Slot(Kind kind, long seconds) { }
	private final Map<Kind, Spec> specs;
	private final EnumMap<Kind, Long> due = new EnumMap<>(Kind.class);
	private final long gap;
	private final long registration;
	private long lastStart = -1;
	private long notBefore;

	X10EventSchedule(Map<Kind, Spec> specs, int gapMinutes, int registrationMinutes, long now, String saved)
	{
		this.specs = Map.copyOf(specs);
		gap = gapMinutes * 60L;
		registration = registrationMinutes * 60L;
		for (Kind kind : Kind.values()) { due.put(kind, now + Math.max(registration, specs.get(kind).delayMinutes * 60L)); }
		try
		{
			final String[] fields = saved.split("\\|");
			if (fields.length == 6 && fields[0].equals("1"))
			{
				final long previousStart = Long.parseLong(fields[1]);
				final long previousNotBefore = Long.parseLong(fields[2]);
				if (previousStart >= -1 && previousStart <= now + 60 && previousNotBefore >= 0 && previousNotBefore <= now + 7200)
				{
					lastStart = previousStart;
					notBefore = previousNotBefore;
					for (Kind kind : Kind.values())
					{
						final String[] row = fields[kind.ordinal() + 3].split(",");
						final Spec spec = specs.get(kind);
						if (row.length == 4 && Boolean.parseBoolean(row[0]) == spec.enabled && Integer.parseInt(row[1]) == spec.intervalMinutes && Integer.parseInt(row[2]) == spec.delayMinutes)
						{
							final long value = Long.parseLong(row[3]);
							if (value >= 0 && value <= now + 1209600) { due.put(kind, value); }
						}
					}
				}
			}
		}
		catch (RuntimeException e)
		{
			// Некорректная сохранённая строка не должна срывать запуск мира.
			lastStart = -1;
			notBefore = 0;
			for (Kind kind : Kind.values()) { due.put(kind, now + Math.max(registration, specs.get(kind).delayMinutes * 60L)); }
		}
		// После рестарта остаётся время на новую запись, даже если старый срок уже наступил.
		notBefore = Math.max(notBefore, now + registration);
	}

	private X10EventSchedule(X10EventSchedule source)
	{
		specs = source.specs;
		due.putAll(source.due);
		gap = source.gap;
		registration = source.registration;
		lastStart = source.lastStart;
		notBefore = source.notBefore;
	}

	Slot next(long now)
	{
		Kind selected = null;
		long earliest = Long.MAX_VALUE;
		for (Kind kind : Kind.values())
		{
			if (specs.get(kind).enabled && due.get(kind) < earliest)
			{
				selected = kind;
				earliest = due.get(kind);
			}
		}
		return selected == null ? null : new Slot(selected, Math.max(Math.max(now, earliest), Math.max(notBefore, lastStart < 0 ? 0 : lastStart + gap)));
	}

	void start(Kind kind, long now, boolean test)
	{
		if (!test) { due.put(kind, now + specs.get(kind).intervalMinutes * 60L); }
		lastStart = now;
		notBefore = now + specs.get(kind).durationMinutes * 60L + registration;
	}

	void finish(long now)
	{
		notBefore = now + registration;
	}

	X10EventSchedule afterTest(Kind kind, long seconds)
	{
		final X10EventSchedule result = new X10EventSchedule(this);
		result.start(kind, seconds, true);
		return result;
	}

	long nextFor(Kind requested, long now)
	{
		if (!specs.get(requested).enabled) { return -1; }
		final X10EventSchedule forecast = new X10EventSchedule(this);
		for (int n = 0; n < 20000; n++)
		{
			final Slot slot = forecast.next(now);
			if (slot == null) { return -1; }
			if (slot.kind == requested) { return slot.seconds; }
			forecast.start(slot.kind, slot.seconds, false);
			now = slot.seconds;
		}
		return -1;
	}

	String encode()
	{
		final StringBuilder result = new StringBuilder("1|").append(lastStart).append('|').append(notBefore);
		for (Kind kind : Kind.values())
		{
			final Spec spec = specs.get(kind);
			result.append('|').append(spec.enabled).append(',').append(spec.intervalMinutes).append(',').append(spec.delayMinutes).append(',').append(due.get(kind));
		}
		return result.toString();
	}
}
