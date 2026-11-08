package org.l2jmobius.gameserver.managers.world;

import java.util.EnumMap;
import java.util.Map;
import org.l2jmobius.gameserver.managers.world.X10EventRules.Kind;

/** Сохранение отсчёта, выключение форматов и очередь при несовместимых периодах. */
public final class X10EventScheduleCheck
{
	private static int checks;
	private static void check(boolean result) { if (!result) { throw new AssertionError("Schedule check " + checks); } checks++; }
	private static Map<Kind, X10EventSchedule.Spec> defaults()
	{
		return new EnumMap<>(Map.of(Kind.FARM, new X10EventSchedule.Spec(true, 120, 60, 10), Kind.DEFENCE, new X10EventSchedule.Spec(true, 240, 120, 10), Kind.HUNT, new X10EventSchedule.Spec(true, 240, 240, 10)));
	}
	public static void main(String[] args)
	{
		var specs = defaults();
		var plan = new X10EventSchedule(specs, 60, 5, 0, "");
		final Kind[] sequence = {Kind.FARM, Kind.DEFENCE, Kind.FARM, Kind.HUNT, Kind.FARM, Kind.DEFENCE, Kind.FARM, Kind.HUNT};
		long now = 0;
		for (int i = 0; i < sequence.length; i++)
		{
			var next = plan.next(now);
			check(next.kind() == sequence[i]);
			check(next.seconds() == (i + 1) * 3600L);
			check(plan.nextFor(next.kind(), now) == next.seconds());
			now = next.seconds();
			plan.start(next.kind(), now, false);
			plan.finish(now + 600);
		}
		plan = new X10EventSchedule(defaults(), 60, 5, 0, "");
		plan.start(Kind.FARM, 3600, false);
		String saved = plan.encode();
		plan = new X10EventSchedule(defaults(), 60, 5, 4000, saved);
		check(plan.next(4000).kind() == Kind.DEFENCE && plan.next(4000).seconds() == 7200);
		check(plan.nextFor(Kind.FARM, 4000) == 10800);
		// Отключённый формат исключается из очереди; остальные сохранённые сроки остаются.
		specs.put(Kind.DEFENCE, new X10EventSchedule.Spec(false, 240, 120, 10));
		plan = new X10EventSchedule(specs, 60, 5, 4000, saved);
		check(plan.next(4000).kind() == Kind.FARM && plan.next(4000).seconds() == 10800);
		check(plan.nextFor(Kind.DEFENCE, 4000) == -1);
		for (Kind k : Kind.values()) { specs.put(k, new X10EventSchedule.Spec(false, 5, 1, 1)); }
		plan = new X10EventSchedule(specs, 1, 5, 0, saved);
		check(plan.next(0) == null);
		// Слишком частые интервалы: порядок справедлив, бои и запись не перекрываются.
		for (Kind k : Kind.values()) { specs.put(k, new X10EventSchedule.Spec(true, 5, 1, 10)); }
		plan = new X10EventSchedule(specs, 1, 5, 0, "");
		now = 0;
		for (int i = 0; i < 30; i++)
		{
			var next = plan.next(now);
			check(next.kind() == Kind.values()[i % 3]);
			check(next.seconds() >= now + (i == 0 ? 300 : 900));
			now = next.seconds();
			plan.start(next.kind(), now, false);
		}
		check(plan.nextFor(Kind.HUNT, now) >= now + 900);
		// Перезапуск перед просроченным стартом даёт полный новый период записи.
		plan = new X10EventSchedule(specs, 1, 5, now + 1000, plan.encode());
		check(plan.next(now + 1000).seconds() >= now + 1300);
		// Смена длительности/наград не сбрасывает отдельный таймер; смена периода сбрасывает.
		specs = defaults();
		specs.put(Kind.DEFENCE, new X10EventSchedule.Spec(false, 240, 120, 10));
		specs.put(Kind.HUNT, new X10EventSchedule.Spec(false, 240, 240, 10));
		specs.put(Kind.FARM, new X10EventSchedule.Spec(true, 120, 60, 20));
		plan = new X10EventSchedule(specs, 60, 5, 4000, saved);
		check(plan.nextFor(Kind.FARM, 4000) == 10800);
		specs.put(Kind.FARM, new X10EventSchedule.Spec(true, 180, 60, 20));
		plan = new X10EventSchedule(specs, 60, 5, 4000, saved);
		check(plan.nextFor(Kind.FARM, 4000) == 7600);
		// Тестовый старт не заменяет запланированный срок, но соблюдает отдых после боя.
		plan = new X10EventSchedule(defaults(), 60, 5, 0, "");
		var test = plan.afterTest(Kind.HUNT, 30);
		check(plan.next(0).seconds() == 3600);
		check(test.next(30).seconds() == 3630);
		check(new X10EventSchedule(defaults(), 60, 5, 0, "broken|state").next(0).seconds() == 3600);
		check(new X10EventSchedule(defaults(), 60, 5, 0, "1|-1|7000|true,120,60,invalid|true,240,120,7200|true,240,240,14400").next(0).seconds() == 3600);
		System.out.println("X10 event schedule: " + checks + " checks passed");
	}
}
