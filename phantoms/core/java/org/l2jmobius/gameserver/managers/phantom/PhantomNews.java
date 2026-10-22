package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: новости модуля фантомов для «Летописи мира» (скрипт {@code custom/L2HubChronicle}, патч 0023): ядро кладёт заметное событие, летопись забирает его
 * при опросе ({@link #drain}). Скрипты компилируются против ядра, а ядро скрипт не видит — поэтому очередь, а не вызов. Не забрали — хранятся последние {@value #MAX}.
 */
public final class PhantomNews
{
	private static final int MAX = 50;
	private static final Deque<Item> ITEMS = new ArrayDeque<>();

	/**
	 * Запись летописи: вид (как у летописи: {@code pvp_result} и т. п.), текст, кто, где (ближайший город), координаты.
	 */
	public record Item(String kind, String text, String who, String where, Integer x, Integer y)
	{
	}

	private PhantomNews()
	{
	}

	/**
	 * @param kind вид записи летописи
	 * @param text текст
	 * @param who кто (или {@code null})
	 * @param at где случилось (или {@code null})
	 */
	static void post(String kind, String text, String who, Player at)
	{
		String where = null;
		if (at != null)
		{
			try
			{
				where = MapRegionData.getInstance().getClosestTownName(at);
			}
			catch (Exception e)
			{
				// Без города — только координаты.
			}
		}
		final Item item = new Item(kind, text, who, where, at == null ? null : at.getX(), at == null ? null : at.getY());
		synchronized (ITEMS)
		{
			ITEMS.addLast(item);
			while (ITEMS.size() > MAX)
			{
				ITEMS.removeFirst();
			}
		}
	}

	/**
	 * @return накопленные новости (очередь пустеет)
	 */
	public static List<Item> drain()
	{
		synchronized (ITEMS)
		{
			final List<Item> items = new ArrayList<>(ITEMS);
			ITEMS.clear();
			return items;
		}
	}
}
