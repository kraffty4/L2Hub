package org.l2jmobius.gameserver.managers.world;

import java.time.LocalDateTime;

/** Проверки лимита, сохранения и календарных границ без запуска игрового сервера. */
public final class X10TaskStateCheck
{
	private static int checks;

	private static void check(boolean value, String message)
	{
		checks++;
		if (!value)
		{
			throw new AssertionError(message);
		}
	}

	public static void main(String[] args)
	{
		final X10TaskState day = new X10TaskState("2026-11-07");
		for (String id : new String[] {"hunt", "spot", "group", "craft", "resources", "explore"})
		{
			day.tasks.put(id, new X10TaskState.Task(id, X10TaskState.Kind.HUNT, 100, 76, -1000, 2000, -3000));
		}
		check(!day.accept("missing"), "unknown contract");
		check(!day.tasks.get("hunt").add(10), "no progress before acceptance");
		check(day.accept("hunt"), "first acceptance");
		check(!day.accept("hunt"), "no duplicate acceptance");
		for (String id : new String[] {"spot", "group", "craft", "resources"})
		{
			check(day.accept(id), "five acceptances");
		}
		final var task = day.tasks.get("hunt");
		check(task.add(Integer.MAX_VALUE) && task.done == 100, "progress clamps without overflow");
		task.claimed = true;
		check(!task.add(1), "claimed contract cannot progress");
		check(!day.accept("explore"), "completion does not free a daily slot");
		final X10TaskState restored = X10TaskState.decode(day.encode());
		check(restored.encode().equals(day.encode()), "exact state roundtrip");
		check(!restored.accept("explore"), "daily limit survives relog");
		check(restored.tasks.get("hunt").claimed, "reward marker survives relog");
		check(restored.tasks.get("hunt").x == -1000 && restored.tasks.get("hunt").z == -3000, "negative location preserved");
		check(!restored.abandon("hunt"), "claimed reward cannot be abandoned");
		check(restored.abandon("spot"), "active contract can be abandoned");
		check(!restored.tasks.get("spot").add(1), "abandoned contract cannot progress");
		check(restored.taken() == 5 && !restored.accept("explore"), "abandonment does not reset daily quota");
		check(!restored.accept("spot"), "abandoned contract cannot be farmed repeatedly");
		check(X10TaskState.decode(restored.encode()).tasks.get("spot").abandoned, "abandonment survives relog");
		check(X10TaskState.decode("1|2026-11-07|hunt,HUNT,100,7,1,0,40,0,0,0,").tasks.get("hunt").done == 7, "v1 progress migrates without losing reward state");
		final var explore = day.tasks.get("explore");
		explore.accepted = true;
		check(explore.visit("10_20"), "first visit");
		check(!explore.visit("10_20"), "same destination not counted twice");
		check(explore.visit("100_200"), "different destination");
		check(X10TaskState.decode(day.encode()).tasks.get("explore").visited.equals(explore.visited), "visited destinations persist");
		check(X10TaskState.day(LocalDateTime.of(2026, 11, 9, 6, 29), 6, 30).equals("2026-11-08"), "before daily reset");
		check(X10TaskState.day(LocalDateTime.of(2026, 11, 9, 6, 30), 6, 30).equals("2026-11-09"), "at daily reset");
		check(X10TaskState.week("2026-11-08").equals("2026-11-02"), "Sunday belongs to previous week");
		check(X10TaskState.week("2026-11-09").equals("2026-11-09"), "Monday starts new week");
		check(X10TaskState.week("2027-01-01").equals("2026-12-28"), "week across year boundary");
		try
		{
			X10TaskState.decode("1|2026-11-07|hunt,HUNT,100,-1,1,0,76,0,0,0,");
			throw new AssertionError("invalid progress accepted");
		}
		catch (IllegalArgumentException expected)
		{
			checks++;
		}
		System.out.println("X10 task state: " + checks + " checks passed");
	}
}
