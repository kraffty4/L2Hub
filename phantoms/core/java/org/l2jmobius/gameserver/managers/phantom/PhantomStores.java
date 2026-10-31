package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

import org.l2jmobius.commons.network.buffer.WriteBuffer;
import org.l2jmobius.commons.network.pool.ResourcePool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.InventoryUpdate;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreListBuy;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreListSell;
import org.l2jmobius.gameserver.network.serverpackets.ServerPacket;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * L2Hub: лавки городских фейков (docs/WORLD.md, «Фейки в городах»). Фейк — NPC, а не игрок: кучи почти не ест. По клику игрок получает настоящее окно личной лавки
 * ({@link PrivateStoreListSell}, {@link PrivateStoreListBuy}), покупка и продажа приходят обычными пакетами клиента, вещи и адена настоящие. Адена уходит с фейком и берётся
 * у него ниоткуда: кошелька у NPC нет.
 * <p>
 * Что продаёт и скупает — список id у каждого фейка (data/L2HubTownFakes.xml, tools/l2hub_town_fakes.py) под его вывеску. Запас конечен и обновляется раз в
 * {@value #RESTOCK_MINUTES} минут: часть непроданного остаётся, остальное заменяется. Цены — от цены магазина: продажа не ниже {@value #SELL_MIN} и скупка не выше
 * {@value #BUY_MAX} от неё. NPC платит половину, поэтому купить у фейка и сдать NPC или купить у одного фейка и сдать другому себе в прибыль нельзя.
 */
public final class PhantomStores
{
	private static final Logger LOGGER = Logger.getLogger(PhantomStores.class.getName());

	private static final int RESTOCK_MINUTES = 180;
	/** Всё раскупили — новый запас через столько. */
	private static final int SOLD_OUT_MINUTES = 30;
	private static final int STOCK_MIN = 4;
	private static final int STOCK_MAX = 12;
	/** Непроданное остаётся с этой вероятностью, %. */
	private static final int KEEP_CHANCE = 60;
	private static final double SELL_MIN = 0.85;
	private static final double BUY_MAX = 0.80;
	/** На сколько наценка характера может поднять цену продажи: 0,85 … 1,25 от магазина. */
	private static final double SELL_SPREAD = 0.40;
	/** Выше этого от цены магазина спрос не поднимет. */
	private static final double SELL_MAX = 2.0;
	private static final double BUY_MIN = 0.55;
	/** Сколько адены в одной позиции: запас считается от цены. */
	private static final long SELL_BUDGET_MIN = 200000;
	private static final long SELL_BUDGET_MAX = 2000000;
	private static final long BUY_BUDGET_MIN = 50000;
	private static final long BUY_BUDGET_MAX = 400000;
	private static final long MAX_COUNT = 5000;
	/** id вещей в окне: выше тех, что выдаёт IdManager в обычной игре, клиент только возвращает их обратно. */
	private static final AtomicInteger NEXT_OBJECT_ID = new AtomicInteger(0x7E000000);

	/** Системные сообщения по имени: у Interlude — S1 вместо C1, у Essence свои имена. */
	private static final SystemMessageId YOU_BOUGHT_ONE = message("YOU_HAVE_PURCHASED_S2_FROM_C1", "YOU_HAVE_PURCHASED_S2_FROM_S1");
	private static final SystemMessageId YOU_BOUGHT_MANY = message("YOU_HAVE_PURCHASED_S3_S2_S_FROM_C1", "YOU_HAVE_PURCHASED_S3_S2_S_FROM_S1");
	private static final SystemMessageId YOU_SOLD_ONE = message("C1_PURCHASED_S2", "S1_PURCHASED_S2");
	private static final SystemMessageId YOU_SOLD_MANY = message("C1_PURCHASED_S3_S2_S", "S1_PURCHASED_S3_S2_S");
	private static final SystemMessageId NO_ADENA = message("YOU_DO_NOT_HAVE_ENOUGH_ADENA", "NOT_ENOUGH_ADENA");
	private static final SystemMessageId TOO_HEAVY = message("YOU_HAVE_EXCEEDED_THE_WEIGHT_LIMIT", "WEIGHT_LIMIT_IS_EXCEEDED");

	/** Заточенный товар: шанс, %, самая большая заточка и наценка за каждый плюс. */
	private static final int ENCHANTED_CHANCE = 25;
	private static final int ENCHANTED_MAX = 6;
	private static final double ENCHANT_PREMIUM = 0.25;
	/** Шанс, %, что за один шаг рынка в лавке кто-то поторговал. */
	private static final int TRADE_CHANCE = 12;
	/** Самопроверка: Bastard Sword, есть у всех пяти хроник. */
	private static final int SPARE_ITEM = 69;

	private static final Map<Integer, Store> STORES = new ConcurrentHashMap<>();

	/**
	 * Сделка фейков между собой.
	 * @param itemId вещь
	 * @param count сколько
	 * @param price цена за штуку
	 * @param demand вещь купили, иначе сдали в скупку
	 */
	record Trade(int itemId, long count, long price, boolean demand)
	{
	}

	private static final class Good
	{
		final int objectId = NEXT_OBJECT_ID.getAndIncrement();
		final int itemId;
		final long price;
		final int enchant;
		long count;

		Good(int itemId, long count, long price, int enchant)
		{
			this.itemId = itemId;
			this.count = count;
			this.price = price;
			this.enchant = enchant;
		}
	}

	private static final class Store
	{
		final String name;
		final boolean buying;
		final int[] pool;
		final double character;
		final List<Good> goods = new ArrayList<>();
		long restockAt;

		Store(String name, boolean buying, int[] pool)
		{
			this.name = name;
			this.buying = buying;
			this.pool = pool;
			character = (Math.abs((name + "store").toLowerCase().hashCode()) % 1000) / 1000.0;
		}

		synchronized void refresh(long now)
		{
			if (now < restockAt)
			{
				return;
			}
			goods.removeIf(g -> Rnd.get(100) >= KEEP_CHANCE);
			final int want = Math.min(pool.length, Rnd.get(STOCK_MIN, STOCK_MAX));
			for (int attempt = 0; (goods.size() < want) && (attempt < (want * 4)); attempt++)
			{
				final int itemId = pool[Rnd.get(pool.length)];
				if (goods.stream().noneMatch(g -> g.itemId == itemId))
				{
					final Good good = create(itemId);
					if (good != null)
					{
						goods.add(good);
					}
				}
			}
			restockAt = now + (RESTOCK_MINUTES * 60000L) + Rnd.get(0, 1800000);
		}

		private Good create(int itemId)
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
			if ((item == null) || (item.getReferencePrice() <= 0) || !item.isTradeable())
			{
				return null;
			}
			final long price = price(item.getReferencePrice(), buying, character, PhantomMarketMemory.factor(itemId));
			if (price <= 0)
			{
				return null;
			}
			if (!item.isStackable())
			{
				final boolean enchantable = !buying && (item.isWeapon() || item.isArmor()) && (Rnd.get(100) < ENCHANTED_CHANCE);
				final int enchant = enchantable ? 1 + Rnd.get(Rnd.get(100) < 15 ? ENCHANTED_MAX : 3) : 0; // чаще +1…+3, редко до +6: выше никто не рискнёт
				return new Good(itemId, 1, enchant == 0 ? price : human((long) (price * (1 + (ENCHANT_PREMIUM * enchant))), true), enchant);
			}
			final long budget = buying ? Rnd.get(BUY_BUDGET_MIN, BUY_BUDGET_MAX) : Rnd.get(SELL_BUDGET_MIN, SELL_BUDGET_MAX);
			return new Good(itemId, Math.max(1, Math.min(MAX_COUNT, budget / price)), price, 0);
		}

		Good byObject(int objectId)
		{
			return goods.stream().filter(g -> g.objectId == objectId).findFirst().orElse(null);
		}

		Good byItem(int itemId)
		{
			return goods.stream().filter(g -> g.itemId == itemId).findFirst().orElse(null);
		}

		void spent(long now)
		{
			goods.removeIf(g -> g.count <= 0);
			if (goods.isEmpty())
			{
				restockAt = Math.min(restockAt, now + (SOLD_OUT_MINUTES * 60000L));
			}
		}
	}

	private PhantomStores()
	{
	}

	/**
	 * @param npcId шаблон фейка
	 * @param name его имя
	 * @param buying лавка скупки, иначе продажи
	 * @param pool что может лежать в лавке
	 */
	public static void register(int npcId, String name, boolean buying, int[] pool)
	{
		if (pool.length > 0)
		{
			STORES.put(npcId, new Store(name, buying, pool));
		}
	}

	/**
	 * @param objectId id из пакета клиента
	 * @return это фейк с лавкой
	 */
	public static boolean isStore(int objectId)
	{
		return npc(objectId) != null;
	}

	/**
	 * Открыть окно лавки: щелчок игрока по фейку.
	 * @param player игрок
	 * @param npc фейк
	 */
	public static void open(Player player, Npc npc)
	{
		final Store store = STORES.get(npc.getId());
		if (store == null)
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return;
		}
		player.sendPacket(window(player, npc, store));
	}

	private static ServerPacket window(Player player, Npc npc, Store store)
	{
		final TradeList list = list(store);
		return store.buying ? new PrivateStoreListBuy(player, npc.getObjectId(), list) : new PrivateStoreListSell(player, npc.getObjectId(), list);
	}

	/** Свежий список для окна: {@link TradeItem} у хроник разный (int и long), свой запас — в {@link Good}. */
	private static TradeList list(Store store)
	{
		final TradeList list = new TradeList(null);
		synchronized (store)
		{
			store.refresh(System.currentTimeMillis());
			for (Good good : store.goods)
			{
				final TradeItem item = PhantomCompat.addBuyItem(list, good.itemId, good.count, good.price);
				if (item != null)
				{
					item.setObjectId(good.objectId);
					item.setEnchant(good.enchant);
				}
			}
		}
		return list;
	}

	/**
	 * Игрок покупает в лавке фейка (пакет {@code RequestPrivateStoreBuy}): те же проверки, что {@link TradeList#privateStoreBuy}.
	 * @param player игрок
	 * @param storeId фейк
	 * @param requests что берёт; цена должна совпасть с окном
	 * @return купил хоть что-то
	 */
	public static boolean buy(Player player, int storeId, Set<RequestTrade> requests)
	{
		final Npc npc = npc(storeId);
		final Store store = npc == null ? null : STORES.get(npc.getId());
		if ((store == null) || store.buying || !canDeal(player, npc))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return false;
		}

		synchronized (store)
		{
			final List<Good> goods = new ArrayList<>();
			final List<Long> counts = new ArrayList<>();
			long total = 0;
			long weight = 0;
			long slots = 0;
			for (RequestTrade request : requests)
			{
				final Good good = store.byObject(request.getObjectId());
				if ((good == null) || (good.price != request.getPrice()) || (good.count <= 0))
				{
					continue;
				}
				final ItemTemplate item = ItemData.getInstance().getTemplate(good.itemId);
				final long count = Math.min(request.getCount(), good.count);
				total += count * good.price;
				weight += count * item.getWeight();
				if (!item.isStackable())
				{
					slots += count;
				}
				else if (player.getInventory().getItemByItemId(good.itemId) == null)
				{
					slots++;
				}
				goods.add(good);
				counts.add(count);
			}
			if (goods.isEmpty())
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return false;
			}
			if (total > player.getAdena())
			{
				player.sendPacket(NO_ADENA);
				return false;
			}
			if (!player.getInventory().validateWeight((int) weight))
			{
				player.sendPacket(TOO_HEAVY);
				return false;
			}
			if (!player.getInventory().validateCapacity((int) slots))
			{
				player.sendPacket(SystemMessageId.YOUR_INVENTORY_IS_FULL);
				return false;
			}
			if (!PhantomCompat.reduceAdena(player, ItemProcessType.BUY, total, npc, false))
			{
				player.sendPacket(NO_ADENA);
				return false;
			}

			final InventoryUpdate update = new InventoryUpdate();
			update.addItem(player.getInventory().getAdenaInstance());
			for (int i = 0; i < goods.size(); i++)
			{
				final Good good = goods.get(i);
				final long count = counts.get(i);
				final Item added = PhantomCompat.addItem(player, ItemProcessType.BUY, good.itemId, count, npc);
				if (added != null)
				{
					if (good.enchant > 0)
					{
						added.setEnchantLevel(good.enchant);
						added.updateDatabase();
					}
					update.addItem(added);
				}
				good.count -= count;
				notice(player, count > 1 ? YOU_BOUGHT_MANY : YOU_BOUGHT_ONE, store.name, good.itemId, count);
				LOGGER.info(PhantomStores.class.getSimpleName() + ": " + player.getName() + " bought " + ItemData.getInstance().getTemplate(good.itemId).getName() + " x" + count + " from " + store.name + " for " + (count * good.price) + ".");
				PhantomMarketMemory.deal(good.itemId, count, good.price, true);
			}
			player.sendInventoryUpdate(update);
			store.spent(System.currentTimeMillis());
			return true;
		}
	}

	/**
	 * Игрок продаёт в скупку фейка (пакет {@code RequestPrivateStoreSell}): те же проверки, что {@link TradeList#privateStoreSell}.
	 * @param player игрок
	 * @param storeId фейк
	 * @param requests что сдаёт; цена должна совпасть с окном, вещь выбирает {@link #sellable}
	 * @return продал хоть что-то
	 */
	public static boolean sell(Player player, int storeId, RequestTrade[] requests)
	{
		final Npc npc = npc(storeId);
		final Store store = npc == null ? null : STORES.get(npc.getId());
		if ((store == null) || !store.buying || !canDeal(player, npc))
		{
			player.sendPacket(ActionFailed.STATIC_PACKET);
			return false;
		}

		synchronized (store)
		{
			final InventoryUpdate update = new InventoryUpdate();
			long total = 0;
			for (RequestTrade request : requests)
			{
				final Good good = store.byItem(request.getItemId());
				if ((good == null) || (good.price != request.getPrice()) || (good.count <= 0))
				{
					continue;
				}
				final long count = Math.min(request.getCount(), good.count);
				final Item item = sellable(player, good.itemId, count);
				if (item == null)
				{
					continue;
				}
				if (player.getInventory().destroyItem(ItemProcessType.SELL, item.getObjectId(), (int) count, player, npc) == null)
				{
					continue;
				}
				if (item.getCount() > 0)
				{
					update.addModifiedItem(item);
				}
				else
				{
					update.addRemovedItem(item);
				}
				good.count -= count;
				total += count * good.price;
				notice(player, count > 1 ? YOU_SOLD_MANY : YOU_SOLD_ONE, store.name, good.itemId, count);
				LOGGER.info(PhantomStores.class.getSimpleName() + ": " + player.getName() + " sold " + item.getName() + " x" + count + " to " + store.name + " for " + (count * good.price) + ".");
				PhantomMarketMemory.deal(good.itemId, count, good.price, false);
			}
			if (total <= 0)
			{
				player.sendPacket(ActionFailed.STATIC_PACKET);
				return false;
			}
			PhantomCompat.addAdena(player, ItemProcessType.SELL, total, npc, false);
			update.addItem(player.getInventory().getAdenaInstance());
			player.sendInventoryUpdate(update);
			store.spent(System.currentTimeMillis());
			return true;
		}
	}

	/**
	 * Шаг рынка: в части лавок что-то продалось или сдано, пока игрока не было. Запас убывает, лавка пополняется по своему сроку.
	 * @param now время шага
	 * @return сделки для {@link PhantomMarketMemory}
	 */
	static List<Trade> trade(long now)
	{
		final List<Trade> trades = new ArrayList<>();
		for (Store store : STORES.values())
		{
			if (Rnd.get(100) >= TRADE_CHANCE)
			{
				continue;
			}
			synchronized (store)
			{
				store.refresh(now);
				if (!store.goods.isEmpty())
				{
					final Good good = store.goods.get(Rnd.get(store.goods.size()));
					final long count = 1 + Rnd.get(Math.max(1L, good.count / 3));
					good.count -= count;
					trades.add(new Trade(good.itemId, count, good.price, !store.buying));
					store.spent(now);
				}
			}
		}
		return trades;
	}

	/**
	 * @return случайный материал, который скупают фейки: для дефицита; 0 — таких нет
	 */
	static int wantedMaterial()
	{
		final List<Integer> wanted = new ArrayList<>();
		for (Store store : STORES.values())
		{
			if (store.buying)
			{
				for (int itemId : store.pool)
				{
					final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
					if ((item != null) && item.isStackable() && !item.getName().startsWith("Scroll"))
					{
						wanted.add(itemId);
					}
				}
			}
		}
		return wanted.isEmpty() ? 0 : wanted.get(Rnd.get(wanted.size()));
	}

	/**
	 * Какую вещь сдать: не надетую и с самой низкой заточкой. Строка окна — одна на id: в ней первая вещь игрока с этим id (может быть и надетая), а у Classic и Essence
	 * клиент присылает вместо вещи номер строки. Без этого выбора продажа запасного меча снимала бы с игрока заточенный.
	 */
	private static Item sellable(Player player, int itemId, long count)
	{
		Item best = null;
		for (Item item : player.getInventory().getAllItemsByItemId(itemId))
		{
			if (!item.isEquipped() && item.isTradeable() && (player.checkItemManipulation(item.getObjectId(), (int) count, "sell") != null) && ((best == null) || (item.getEnchantLevel() < best.getEnchantLevel())))
			{
				best = item;
			}
		}
		return best;
	}

	private static boolean canDeal(Player player, Npc npc)
	{
		return !player.isCursedWeaponEquipped() && player.getAccessLevel().allowTransaction() && player.isInsideRadius3D(npc, Npc.INTERACTION_DISTANCE);
	}

	private static Npc npc(int objectId)
	{
		final WorldObject object = World.findObject(objectId);
		return (object instanceof Npc npc) && STORES.containsKey(npc.getId()) ? npc : null;
	}

	/**
	 * Цена в лавке: от цены магазина и характера хозяина, ±5 % на позицию, память рынка ({@link PhantomMarketMemory}), «круглая», как ставят люди.
	 * @param reference цена магазина
	 * @param buying скупка, иначе продажа
	 * @param character характер хозяина, 0–1
	 * @param market множитель памяти рынка
	 * @return цена за штуку; продажа никогда не ниже {@value #SELL_MIN} и не выше {@value #SELL_MAX} от магазина, скупка никогда не выше {@value #BUY_MAX}
	 */
	private static long price(long reference, boolean buying, double character, double market)
	{
		final double noise = 0.95 + (Rnd.nextDouble() * 0.10);
		if (buying)
		{
			final double factor = (BUY_MIN + (character * (BUY_MAX - BUY_MIN))) * noise * market;
			return human(Math.max(1, (long) (reference * Math.min(BUY_MAX, factor))), false);
		}
		final double factor = (SELL_MIN + (character * SELL_SPREAD)) * noise * market;
		return human((long) Math.ceil(reference * Math.max(SELL_MIN, Math.min(SELL_MAX, factor))), true);
	}

	/** Цена, как у людей: две-три значащие цифры, 12 437 → 12 500. */
	private static long human(long price, boolean up)
	{
		long step = 1;
		while ((price / step) >= 200)
		{
			step *= 10;
		}
		step = step >= 10 ? step / 2 : step;
		final long down = (price / step) * step;
		return up && (down < price) ? down + step : Math.max(1, down);
	}

	private static SystemMessageId message(String name, String other)
	{
		final SystemMessageId id = SystemMessageId.getSystemMessageId(name);
		return id != null ? id : SystemMessageId.getSystemMessageId(other);
	}

	private static void notice(Player player, SystemMessageId message, String store, int itemId, long count)
	{
		final SystemMessage packet = new SystemMessage(message);
		packet.addString(store);
		packet.addItemName(itemId);
		if (count > 1)
		{
			packet.addString(Long.toString(count));
		}
		player.sendPacket(packet);
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка без клиента ({@code TownFakesStoreTest}): окно собирается, покупка и продажа сходятся по адене и вещам, чужая цена и дальний игрок отвергаются,
	 * ни в одной лавке нельзя заработать на разнице с NPC и с другими лавками. Игрока подводит к фейку телепортом: только на копии базы.
	 * @param player игрок без клиента
	 * @param seller фейк с лавкой продажи
	 * @param buyer фейк со скупкой
	 * @return «прошло из всего» и что не сошлось
	 */
	public static String selfTest(Player player, Npc seller, Npc buyer)
	{
		final List<String> failed = new ArrayList<>();
		int checks = 0;

		// Цены всех лавок.
		for (Store store : STORES.values())
		{
			list(store);
			synchronized (store)
			{
				for (Good good : store.goods)
				{
					checks++;
					final long reference = ItemData.getInstance().getTemplate(good.itemId).getReferencePrice();
					if (store.buying ? (good.price > Math.ceil(reference * BUY_MAX)) : (good.price < (reference * SELL_MIN)))
					{
						failed.add("цена " + store.name + " " + good.itemId + ": " + good.price + " при магазине " + reference);
					}
				}
			}
		}

		// Границы цен при любом рынке: перепродать себе в прибыль нельзя.
		for (long reference : new long[] { 1, 7, 137, 4900, 123456, 8800000 })
		{
			for (double market : new double[] { 0.8, 1, 1.4 })
			{
				for (double character : new double[] { 0, 0.5, 1 })
				{
					checks++;
					final long ask = price(reference, false, character, market);
					final long bid = price(reference, true, character, market);
					if ((ask < (reference * SELL_MIN)) || (ask > Math.ceil(reference * SELL_MAX * 1.05)) || (bid > Math.max(1, Math.ceil(reference * BUY_MAX))) || (bid > ask))
					{
						failed.add("границы цен при магазине " + reference + ", рынке " + market + ": продаёт " + ask + ", скупает " + bid);
					}
				}
			}
		}

		// Окна: клиента нет, пакет собирается напрямую.
		final Store sell = STORES.get(seller.getId());
		final Store buy = STORES.get(buyer.getId());
		checks++;
		final String sellWindow = packetError(window(player, seller, sell));
		if (sellWindow != null)
		{
			failed.add("окно лавки: " + sellWindow);
		}
		checks++;
		final String buyWindow = packetError(window(player, buyer, buy));
		if (buyWindow != null)
		{
			failed.add("окно скупки: " + buyWindow);
		}

		// Покупка.
		list(sell);
		final Good good;
		synchronized (sell)
		{
			good = sell.goods.stream().filter(g -> g.count > 0).findFirst().orElse(null);
		}
		if (good == null)
		{
			failed.add("в лавке " + sell.name + " пусто");
		}
		else
		{
			final long count = Math.min(2, good.count);
			PhantomCompat.addAdena(player, ItemProcessType.REWARD, (good.price * count) + 1000, player, false);
			final long adena = player.getAdena();
			final long stock = good.count;
			checks++;
			if (player.isInsideRadius3D(seller, Npc.INTERACTION_DISTANCE) || buy(player, seller.getObjectId(), request(good, count, good.price)))
			{
				failed.add("купил издалека");
			}
			player.setXYZ(seller.getX(), seller.getY(), seller.getZ());
			checks++;
			if (buy(player, seller.getObjectId(), request(good, count, good.price + 1)) || (player.getAdena() != adena))
			{
				failed.add("купил по чужой цене");
			}
			final long before = have(player, good.itemId);
			checks++;
			final int enchantBefore = maxEnchant(player, good.itemId);
			if (!buy(player, seller.getObjectId(), request(good, count, good.price)) || (player.getAdena() != (adena - (count * good.price))) || (have(player, good.itemId) != (before + count)) || (stockOf(sell, good) != (stock - count)))
			{
				failed.add("покупка: адена " + adena + " → " + player.getAdena() + ", вещей " + before + " → " + have(player, good.itemId) + ", запас " + stock + " → " + stockOf(sell, good) + ", цена " + good.price + " x" + count);
			}
			if ((good.enchant > 0) && (maxEnchant(player, good.itemId) < Math.max(enchantBefore, good.enchant)))
			{
				failed.add("заточка покупки: в окне +" + good.enchant + ", у игрока +" + maxEnchant(player, good.itemId));
			}
		}

		// Скупка.
		list(buy);
		final Good wanted;
		synchronized (buy)
		{
			wanted = buy.goods.stream().filter(g -> g.count > 0).findFirst().orElse(null);
		}
		if (wanted == null)
		{
			failed.add("в скупке " + buy.name + " пусто");
		}
		else
		{
			final long count = Math.min(2, wanted.count);
			final Item item = PhantomCompat.addItem(player, ItemProcessType.REWARD, wanted.itemId, count, player);
			checks++;
			final String offered = packetError(window(player, buyer, buy)); // у игрока есть что продать: другой путь сборки окна
			if (offered != null)
			{
				failed.add("окно скупки с вещью: " + offered);
			}
			player.setXYZ(buyer.getX(), buyer.getY(), buyer.getZ());
			final long adena = player.getAdena();
			final long before = have(player, wanted.itemId);
			final long stock = wanted.count;
			checks++;
			if (!sell(player, buyer.getObjectId(), new RequestTrade[] { PhantomCompat.requestTrade(item.getObjectId(), wanted.itemId, count, wanted.price) }) || (player.getAdena() != (adena + (count * wanted.price))) || (have(player, wanted.itemId) != (before - count)) || (stockOf(buy, wanted) != (stock - count)))
			{
				failed.add("скупка: адена " + adena + " → " + player.getAdena() + ", вещей " + before + " → " + have(player, wanted.itemId) + ", спрос " + stock + " → " + stockOf(buy, wanted) + ", цена " + wanted.price + " x" + count);
			}
		}

		// Скупка не снимает надетое и заточенное: сдать надо самую дешёвую вещь из тех, что не на персонаже.
		final Item worn = PhantomCompat.addItem(player, ItemProcessType.REWARD, SPARE_ITEM, 1, player);
		final Item enchanted = PhantomCompat.addItem(player, ItemProcessType.REWARD, SPARE_ITEM, 1, player);
		final Item plain = PhantomCompat.addItem(player, ItemProcessType.REWARD, SPARE_ITEM, 1, player);
		enchanted.setEnchantLevel(5);
		player.getInventory().equipItem(worn);
		checks++;
		final Item first = sellable(player, SPARE_ITEM, 1);
		player.getInventory().destroyItem(ItemProcessType.NONE, plain, player, null);
		final Item second = sellable(player, SPARE_ITEM, 1);
		player.getInventory().destroyItem(ItemProcessType.NONE, enchanted, player, null);
		final Item third = sellable(player, SPARE_ITEM, 1);
		if (!worn.isEquipped() || (first != plain) || (second != enchanted) || (third != null))
		{
			failed.add("выбор вещи для скупки: надета " + worn.isEquipped() + ", первая " + (first == plain) + ", вторая " + (second == enchanted) + ", надетая не продаётся " + (third == null));
		}
		player.getInventory().unEquipItemInSlot(worn.getLocationSlot());
		player.getInventory().destroyItem(ItemProcessType.NONE, worn, player, null);

		// Память рынка: спрос, затоваривание, дефицит, файл; затем шесть шагов рынка, в логе — кривая цен.
		final int marketItem = buy.pool[0];
		if ((marketItem > 0) && PhantomMarketMemory.isOn())
		{
			checks++;
			final List<String> market = PhantomMarketMemory.selfTest(marketItem);
			if (!market.isEmpty())
			{
				failed.add("рынок: " + String.join(", ", market));
			}
			PhantomMarketMemory.shortage(marketItem, System.currentTimeMillis());
			for (int step = 0; step < 6; step++)
			{
				PhantomMarketMemory.tick(System.currentTimeMillis());
			}
			checks++;
			if (PhantomMarketMemory.factor(marketItem) <= 1)
			{
				failed.add("дефицит не поднял цену " + marketItem);
			}
			buy.goods.removeIf(g -> g.itemId == marketItem);
			buy.restockAt = 0;
			list(buy);
		}
		checks++;
		failed.addAll(PhantomEnchant.selfTest(player));
		return (checks - failed.size()) + " of " + checks + (failed.isEmpty() ? "" : "; FAILED: " + String.join("; ", failed));
	}

	/** @return null или что упало при сборке пакета */
	private static String packetError(ServerPacket packet)
	{
		try
		{
			final Method write = ServerPacket.class.getDeclaredMethod("writeImpl", GameClient.class, WriteBuffer.class);
			write.setAccessible(true);
			write.invoke(packet, null, new WriteBuffer(new ResourcePool(), packet.getClass()));
			return null;
		}
		catch (Exception e)
		{
			return String.valueOf(e.getCause() != null ? e.getCause() : e);
		}
	}

	private static Set<RequestTrade> request(Good good, long count, long price)
	{
		final Set<RequestTrade> set = new HashSet<>();
		set.add(PhantomCompat.requestTrade(good.objectId, good.itemId, count, price));
		return set;
	}

	private static long have(Player player, int itemId)
	{
		long total = 0;
		for (Item item : player.getInventory().getAllItemsByItemId(itemId))
		{
			total += item.getCount();
		}
		return total;
	}

	private static int maxEnchant(Player player, int itemId)
	{
		int level = 0;
		for (Item item : player.getInventory().getAllItemsByItemId(itemId))
		{
			level = Math.max(level, item.getEnchantLevel());
		}
		return level;
	}

	private static long stockOf(Store store, Good good)
	{
		synchronized (store)
		{
			return store.goods.contains(good) ? good.count : 0;
		}
	}
}
