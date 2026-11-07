package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/**
 * Сумка охотника (перенос с Эпилога, A23 сверки): когда пора разгружаться, что продавать первым и что не продавать никогда.
 * Сбор лута — штатный автолут Mobius и правила пати.
 * <ul>
 * <li>{@link #pressured} — сумка забита (70 % ячеек или половина веса): в город при первой безопасной паузе;</li>
 * <li>{@link #reliefNeeded} — всё ещё тесно (55 % ячеек или 45 % веса): в аварию продаётся и товар лавки;</li>
 * <li>{@link #saleOrder} — продажа от дешёвого к дорогому; {@link #keep} — вещи из списка Эпилога (Coin of Luck, Ancient Adena и др.), заточенное и с ЛС не продаются;</li>
 * <li>{@link #overflowDrop} — лут сверх ячеек охотник оставляет в сумке до разгрузки вместо выброса на землю; на Эпилоге его зовёт врезка в
 * {@code Player.addItem}, на других хрониках врезки нет, и лишнее падает на землю, как раньше.</li>
 * </ul>
 */
public final class PhantomHunterLoot
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHunterLoot.class.getName());
	private static final Set<Integer> WARNED = ConcurrentHashMap.newKeySet();

	private PhantomHunterLoot()
	{
	}

	public static boolean pressured(int slots, int limit, long load, long maxLoad)
	{
		return ((limit > 0) && (((long) slots * 100) >= ((long) limit * 70))) || ((maxLoad > 0) && (load >= ((maxLoad * 50) / 100)));
	}

	public static boolean reliefNeeded(int slots, int limit, long load, long maxLoad)
	{
		return ((limit > 0) && (((long) slots * 100) > ((long) limit * 55))) || ((maxLoad > 0) && (load > ((maxLoad * 45) / 100)));
	}

	public static boolean pressured(Player player)
	{
		return pressured(player.getInventory().getNonQuestSize(), player.getInventoryLimit(), player.getCurrentLoad(), player.getMaxLoad());
	}

	public static boolean reliefNeeded(Player player)
	{
		return reliefNeeded(player.getInventory().getNonQuestSize(), player.getInventoryLimit(), player.getCurrentLoad(), player.getMaxLoad());
	}

	/**
	 * @param player охотник
	 * @return в пати есть живой игрок: пати не бросать ради города
	 */
	public static boolean hasHumanParty(Player player)
	{
		if (!player.isInParty())
		{
			return false;
		}
		for (Player member : player.getParty().getMembers())
		{
			if (!PhantomManager.getInstance().isPhantom(member))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @param item вещь
	 * @return не продавать: id из списка Эпилога (4037 Coin of Luck, 5575 Ancient Adena и др.), заточенное и с ЛС
	 */
	public static boolean keep(Item item)
	{
		final int id = item.getId();
		return (id == 4037) || (id == 5575) || (id == 5591) || (id == 5592) || (id == 8175) || (id == 8176) || ((id >= 9814) && (id <= 9818)) || (id == 9910) || (id == 9911) || item.isAugmented() || (item.getEnchantLevel() > 0);
	}

	public static long value(Item item)
	{
		final long price = item.getTemplate().getReferencePrice();
		final long count = item.getCount();
		if ((price <= 0) || (count <= 0))
		{
			return 0;
		}
		return count > (Long.MAX_VALUE / price) ? Long.MAX_VALUE : price * count;
	}

	/**
	 * @param player охотник
	 * @return вещи сумки от дешёвых к дорогим
	 */
	public static List<Item> saleOrder(Player player)
	{
		final List<Item> result = new ArrayList<>(player.getInventory().getItems());
		result.sort(Comparator.comparingLong(PhantomHunterLoot::value).thenComparingInt(Item::getObjectId));
		return result;
	}

	private static boolean keepLoot(Player player, ItemProcessType received)
	{
		if (((received != ItemProcessType.LOOT) && (received != ItemProcessType.SWEEP)) || !PhantomManager.getInstance().isPhantom(player))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		if ((template == null) || !template.isHunter() || template.isReaper())
		{
			return false;
		}
		if (WARNED.add(player.getObjectId()))
		{
			LOGGER.info("Hunter inventory overflow retained for unloading: " + player.getName() + "; slots=" + player.getInventory().getNonQuestSize() + "/" + player.getInventoryLimit());
		}
		return true;
	}

	/**
	 * Только из ветки штатного {@code addItem} «сумка полна — выбросить». Лут не удваивается и не перераспределяется.
	 */
	public static boolean overflowDrop(Player player, ItemProcessType process, Item item, WorldObject reference, boolean message, ItemProcessType received)
	{
		return keepLoot(player, received) ? false : player.dropItem(process, item, reference, message);
	}

	public static boolean overflowDrop(Player player, ItemProcessType process, Item item, WorldObject reference, boolean message, boolean protect, ItemProcessType received)
	{
		return keepLoot(player, received) ? false : player.dropItem(process, item, reference, message, protect);
	}
}
