package org.l2jmobius.gameserver.managers.phantom;

import java.util.HashSet;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.OfflineTradeConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.OfflineTraderTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;

/**
 * L2Hub: лавка от нужды и рынок между охотниками (патч 0035, docs/PHANTOMS.md, раздел 4.18).
 * <ul>
 * <li>Лавка от нужды: охотник садится торговать, только когда ему не хватает адены на цель — профу или вещь, на которую копит ({@link PhantomHunterOutfit#shortfall}),
 * или когда сумка почти полна. Иначе ценный дроп носит с собой. Чем больше не хватает, тем дешевле ставит ({@link #desperation}).</li>
 * <li>Рынок: охотник в городе, у которого есть свободная адена, смотрит лавки на площади Гирана — охотников и живых игроков — и, если там есть вещь лучше надетой
 * не дороже магазина, идёт и покупает её настоящей покупкой в лавке ({@link TradeList#privateStoreBuy}), как живой игрок.</li>
 * <li>Скупка (docs/PHANTOMS.md, раздел 4.29; {@code HunterBuyStores}): охотник, который копит на вещь и накопил больше половины цены, садится
 * в лавку скупки «Куплю …» ({@link #buyOrder}); охотники, у которых такая вещь лежит в сумке, — и в скупку живого игрока тоже — приходят и продают ({@link #findSale}).</li>
 * </ul>
 */
final class PhantomMarket
{
	private static final Logger LOGGER = Logger.getLogger(PhantomMarket.class.getName());

	/** Площадь Гирана у гейткипера: тут стоят лавки (как в {@code PhantomHunterManager}). */
	static final Location GIRAN_SQUARE = new Location(82698, 148638, -3470);
	/** Лавки дальше этого от площади — не рынок. */
	private static final int MARKET_RANGE = 2500;
	/** На рынке — не дороже цены магазина × это. */
	private static final double MAX_PRICE_FACTOR = 1.0;
	/** Скидка от нужды: не хватает всего — цена × (1 − это). */
	private static final double DESPERATION = 0.3;
	/** Вещь уже не продалась раз — ещё × это. */
	private static final double UNSOLD_DISCOUNT = 0.85;

	/** Лавка скупки: не меньше столькой доли цены магазина — дешевле никто не продаст. */
	private static final double MIN_BUY_FACTOR = 0.5;

	/**
	 * Сделка с чужой лавкой: купить в лавке продажи или продать в лавку скупки (патч рынка 4.29).
	 * @param storeId хозяин лавки (objectId)
	 * @param objectId вещь: в лавке продажи — его, в скупку — своя
	 * @param itemId её шаблон
	 * @param count сколько
	 * @param price цена за штуку в лавке
	 * @param name название — для лога
	 * @param sale {@code true} — продать в его скупку, {@code false} — купить у него
	 */
	record Offer(int storeId, int objectId, int itemId, long count, long price, String name, boolean sale)
	{
	}

	/**
	 * Заказ своей лавки скупки: вещь, на которую копит, по цене со скидкой.
	 * @param itemId вещь
	 * @param price сколько даёт
	 * @param shopPrice цена в магазине — для лога
	 * @param name название
	 */
	record Order(int itemId, long price, long shopPrice, String name)
	{
	}

	private PhantomMarket()
	{
	}

	// ---------- Лавка от нужды ----------

	/**
	 * @param player охотник
	 * @param template его шаблон
	 * @return есть зачем садиться в лавку: не хватает адены на цель или сумка почти полна ({@link PhantomHunterOutfit#bagFull}) — продать, даже если ни на что не копит
	 */
	static boolean needsStore(Player player, PhantomTemplate template)
	{
		return (PhantomHunterOutfit.shortfall(player, template) > 0) || PhantomHunterOutfit.bagFull(player);
	}

	/**
	 * Цена от нужды: × (1 − {@value #DESPERATION} × доля нехватки до цели); вещь уже не продалась — ещё × {@value #UNSOLD_DISCOUNT}.
	 * @param player охотник
	 * @param template его шаблон
	 * @param unsoldBefore в лавке есть то, что уже не продалось
	 * @return множитель к цене лавки
	 */
	static double desperation(Player player, PhantomTemplate template, boolean unsoldBefore)
	{
		final long shortfall = PhantomHunterOutfit.shortfall(player, template);
		final double goal = shortfall + Math.max(0, player.getAdena());
		final double need = goal > 0 ? Math.min(1, shortfall / goal) : 0;
		return (1 - (DESPERATION * need)) * (unsoldBefore ? UNSOLD_DISCOUNT : 1);
	}

	// ---------- Рынок ----------

	/**
	 * Лучшее предложение для охотника в лавках Гирана: вещь лучше надетой ({@link PhantomHunterOutfit#isUpgrade}), не дороже магазина, по карману со свободной адены;
	 * из таких — самая сильная (дороже по цене магазина).
	 * @param buyer охотник
	 * @return предложение или {@code null}
	 */
	static Offer findOffer(Player buyer)
	{
		final long free = PhantomHunterOutfit.freeAdena(buyer);
		if (free <= 0)
		{
			return null;
		}

		Offer best = null;
		long bestValue = 0;
		for (Player seller : World.getPlayers())
		{
			if ((seller == buyer) || (seller.getPrivateStoreType() != PrivateStoreType.SELL) || (seller.calculateDistance2D(GIRAN_SQUARE) > MARKET_RANGE))
			{
				continue;
			}

			for (TradeItem trade : seller.getSellList().getItems())
			{
				final Item item = seller.getInventory().getItemByObjectId(trade.getObjectId());
				if ((item == null) || (trade.getPrice() > free) || (trade.getPrice() > (item.getTemplate().getReferencePrice() * MAX_PRICE_FACTOR)) || !PhantomHunterOutfit.isUpgrade(buyer, item))
				{
					continue;
				}

				final long value = item.getTemplate().getReferencePrice();
				if (value > bestValue)
				{
					best = new Offer(seller.getObjectId(), trade.getObjectId(), item.getId(), 1, trade.getPrice(), item.getName(), false);
					bestValue = value;
				}
			}
		}
		return best;
	}

	/**
	 * Лучшая продажа в лавки скупки на площади Гирана (охотников и живых игроков; {@code HunterBuyStores}): у охотника в сумке лежит то, что там скупают,
	 * — то, что он и так отдал бы ({@link PhantomHunterOutfit#junk}), — и дают больше, чем NPC (полцены магазина). Из таких — где выручка больше.
	 * @param seller охотник
	 * @return продажа или {@code null}
	 */
	static Offer findSale(Player seller)
	{
		if (!PhantomHuntersConfig.HUNTER_BUY_STORES)
		{
			return null;
		}

		Offer best = null;
		long bestValue = 0;
		for (Player buyer : World.getPlayers())
		{
			if ((buyer == seller) || (buyer.getPrivateStoreType() != PrivateStoreType.BUY) || (buyer.calculateDistance2D(GIRAN_SQUARE) > MARKET_RANGE))
			{
				continue;
			}

			for (TradeItem wanted : buyer.getBuyList().getItems())
			{
				final long price = wanted.getPrice();
				if ((price <= 0) || (price < (wanted.getItem().getReferencePrice() / 2)))
				{
					continue;
				}
				for (Item item : seller.getInventory().getAllItemsByItemId(wanted.getItem().getId()))
				{
					if (!PhantomHunterOutfit.junk(seller, item) || !item.isTradeable())
					{
						continue;
					}
					final long count = Math.min(Math.min(item.getCount(), wanted.getCount()), buyer.getAdena() / price);
					if ((count > 0) && ((count * price) > bestValue))
					{
						best = new Offer(buyer.getObjectId(), item.getObjectId(), item.getId(), count, price, item.getName(), true);
						bestValue = count * price;
					}
				}
			}
		}
		return best;
	}

	/**
	 * @param offer предложение
	 * @return хозяин лавки, если она ещё открыта (продажи — для покупки, скупки — для продажи), или {@code null}
	 */
	static Player seller(Offer offer)
	{
		final Player store = World.getPlayer(offer.storeId());
		return (store != null) && (store.getPrivateStoreType() == (offer.sale() ? PrivateStoreType.BUY : PrivateStoreType.SELL)) ? store : null;
	}

	/**
	 * Продать в лавку скупки, как живой игрок (то же, что {@code RequestPrivateStoreSell}): {@link TradeList#privateStoreSell}, заполненная скупка закрывается.
	 * @param seller охотник
	 * @param offer продажа из {@link #findSale}
	 * @return продал
	 */
	static boolean sell(Player seller, Offer offer)
	{
		final Player buyer = seller(offer);
		if (buyer == null)
		{
			return false;
		}

		final TradeList list = buyer.getBuyList();
		final long before = seller.getAdena();
		if (!list.privateStoreSell(seller, new RequestTrade[] { PhantomCompat.requestTrade(offer.objectId(), offer.itemId(), offer.count(), offer.price()) }))
		{
			return false;
		}

		if (OfflineTradeConfig.OFFLINE_TRADE_ENABLE && OfflineTradeConfig.STORE_OFFLINE_TRADE_IN_REALTIME && ((buyer.getClient() == null) || buyer.getClient().isDetached()))
		{
			OfflineTraderTable.getInstance().onTransaction(buyer, list.getItemCount() == 0, false);
		}
		if (list.getItemCount() == 0)
		{
			buyer.setPrivateStoreType(PrivateStoreType.NONE);
			buyer.broadcastUserInfo();
		}
		LOGGER.info(PhantomMarket.class.getSimpleName() + ": " + seller.getName() + " sold " + offer.name() + (offer.count() > 1 ? " x" + offer.count() : "") + " to " + buyer.getName() + " for " + (seller.getAdena() - before) + "; " + seller.getAdena() + " adena.");
		return true;
	}

	/**
	 * Заказ лавки скупки ({@code HunterBuyStores}): вещь, на которую копит ({@link PhantomHunterOutfit#want}), — цена магазина × характер
	 * ({@link #buyFactor}), но не больше того, что можно на неё потратить; дешевле {@value #MIN_BUY_FACTOR} цены магазина — не заказ (никто не продаст).
	 * @param player охотник
	 * @param template его шаблон
	 * @return заказ или {@code null}: не копит на вещь или накопил слишком мало
	 */
	static Order buyOrder(Player player, PhantomTemplate template)
	{
		if (!PhantomHuntersConfig.HUNTER_BUY_STORES)
		{
			return null;
		}
		final PhantomHunterOutfit.Want want = PhantomHunterOutfit.want(player, template);
		final ItemTemplate item = want == null ? null : ItemData.getInstance().getTemplate(want.itemId());
		if ((item == null) || !item.isTradeable())
		{
			return null;
		}
		final long price = Math.min(Math.round(want.price() * buyFactor(template)), Math.min(want.price() - want.missing(), player.getAdena()));
		return price < (want.price() * MIN_BUY_FACTOR) ? null : new Order(want.itemId(), price, want.price(), item.getName());
	}

	/**
	 * Характер в скупке: 0,55–0,85 цены магазина, у каждого своя (по имени).
	 */
	private static double buyFactor(PhantomTemplate template)
	{
		return 0.55 + ((Math.abs((template.getName() + "buy").toLowerCase().hashCode()) % 31) / 100.0);
	}

	/**
	 * Купить в лавке, как живой игрок (то же, что {@code RequestPrivateStoreBuy}): {@link TradeList#privateStoreBuy}, опустевшая лавка закрывается; купленное — надеть.
	 * @param buyer охотник
	 * @param offer предложение
	 * @return купил
	 */
	static boolean buy(Player buyer, Offer offer)
	{
		final Player seller = seller(offer);
		if (seller == null)
		{
			return false;
		}

		final TradeList list = seller.getSellList();
		final Set<RequestTrade> request = new HashSet<>();
		request.add(PhantomCompat.requestTrade(offer.objectId(), offer.itemId(), 1, offer.price()));
		if (list.privateStoreBuy(buyer, request) != 0)
		{
			return false;
		}

		if (OfflineTradeConfig.OFFLINE_TRADE_ENABLE && OfflineTradeConfig.STORE_OFFLINE_TRADE_IN_REALTIME && ((seller.getClient() == null) || seller.getClient().isDetached()))
		{
			OfflineTraderTable.getInstance().onTransaction(seller, list.getItemCount() == 0, false);
		}
		if (list.getItemCount() == 0)
		{
			seller.setPrivateStoreType(PrivateStoreType.NONE);
			seller.broadcastUserInfo();
		}

		PhantomHunterOutfit.equipBetterDrops(buyer);
		LOGGER.info(PhantomMarket.class.getSimpleName() + ": " + buyer.getName() + " bought " + offer.name() + " from " + seller.getName() + " for " + offer.price() + "; " + buyer.getAdena() + " adena.");
		return true;
	}
}
