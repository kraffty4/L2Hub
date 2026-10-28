package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.BodyPart;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/**
 * Мировая биржа Essence с охотниками (docs/PHANTOMS.md, раздел 4.51; docs/PLAN.md, «Дальше для одиночки на Classic и Essence», п. 7). Фантом — игрок, поэтому
 * всё — прямыми вызовами WorldExchangeManager через {@link PhantomCompat}; у других хроник биржи нет — там заглушки. В городе, до сдачи хлама NPC:
 * <ol>
 * <li>забрать L-Coin за проданное и вернуть просроченное;</li>
 * <li>купить за L-Coin вещь лучше надетой ({@link PhantomHunterOutfit#isUpgrade}) — не дороже своей оценки или средней цены других лотов той же вещи;</li>
 * <li>выставить оружие, броню и бижутерию из товаров лавки ({@link PhantomHunterOutfit#storeGoods}) — по средней цене биржи, нет её — по своей оценке,
 * если на плату за лот хватает свободной адены; своих лотов — не больше {@code HunterExchangeLots}.</li>
 * </ol>
 * Своя оценка — цена магазина / {@code HunterExchangeAdenaPerLCoin}.
 */
final class PhantomExchange
{
	private static final Logger LOGGER = Logger.getLogger(PhantomExchange.class.getName());
	/** L-Coin (Essence, Inventory.LCOIN_ID). */
	private static final int LCOIN = 91663;
	/** Разделы биржи Mobius (WorldExchangeItemSubType): оружие, броня, бижутерия. */
	private static final int WEAPON = 0;
	private static final int ARMOR = 1;
	private static final int ACCESSORY = 2;

	/** Состояние лота: выставлен, продан (ждёт, пока продавец заберёт L-Coin), просрочен (ждёт, пока заберёт вещь). */
	enum Status
	{
		REGISTERED,
		SOLD,
		OUT_TIME
	}

	/**
	 * Лот биржи.
	 * @param id номер лота
	 * @param item вещь лота (у проданного — копия для продавца)
	 * @param price цена лота в L-Coin
	 * @param ownerId продавец
	 * @param status состояние
	 */
	record Lot(long id, Item item, long price, int ownerId, Status status)
	{
	}

	private PhantomExchange()
	{
	}

	/**
	 * @return биржа у этой хроники есть и включена, и охотникам она разрешена ({@code HunterExchange})
	 */
	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_EXCHANGE && PhantomCompat.exchangeEnabled();
	}

	/**
	 * @return своя оценка вещи в L-Coin: цена магазина / {@code HunterExchangeAdenaPerLCoin}; 0 — дешевле одного L-Coin
	 */
	static long estimate(ItemTemplate template, long count)
	{
		return (template.getReferencePrice() * count) / Math.max(1, PhantomHuntersConfig.HUNTER_EXCHANGE_ADENA_PER_LCOIN);
	}

	/**
	 * Выставить выгоднее, чем сдать NPC: выручка за вычетом {@code LCoinFee} по своему курсу ({@code HunterExchangeAdenaPerLCoin}) минус плата за лот —
	 * больше, чем дал бы NPC (половина цены магазина). Лот за 1 L-Coin продавцу не даёт ничего: Mobius берёт с выручки не меньше одного L-Coin.
	 * @param price цена лота в L-Coin
	 */
	static boolean worthSelling(ItemTemplate template, long count, long price)
	{
		final long net = PhantomCompat.exchangeNet(price);
		return (net >= 1) && (((net * PhantomHuntersConfig.HUNTER_EXCHANGE_ADENA_PER_LCOIN) - PhantomCompat.exchangeFee(price)) > ((template.getReferencePrice() / 2L) * count));
	}

	/**
	 * @return раздел биржи: оружие, бижутерия или броня (щиты и всё прочее надеваемое)
	 */
	private static int category(Item item)
	{
		if (item.isWeapon())
		{
			return WEAPON;
		}
		return PhantomHunterOutfit.JEWELS.contains(item.getTemplate().getBodyPart()) ? ACCESSORY : ARMOR;
	}

	/**
	 * В городе после квеста и поручений стратега, до сдачи хлама NPC ({@link PhantomHunterOutfit#townChores}): выручка и непроданное, покупка, выставить лишнее.
	 * @param player охотник
	 */
	static void visit(Player player)
	{
		if (!enabled())
		{
			return;
		}
		settle(player);
		buy(player);
		sell(player);
	}

	/**
	 * Свои лоты: проданные — забрать L-Coin, просроченные — забрать вещь (она снова товар лавки или хлам).
	 */
	private static void settle(Player player)
	{
		for (Lot lot : PhantomCompat.exchangeOwnLots(player))
		{
			if (lot.status() == Status.REGISTERED)
			{
				continue;
			}
			final long before = PhantomCompat.lcoin(player);
			if (!PhantomCompat.exchangeSettle(player, lot))
			{
				continue;
			}
			if (lot.status() == Status.SOLD)
			{
				LOGGER.info(PhantomExchange.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") collects " + (PhantomCompat.lcoin(player) - before) + " L-Coin for " + lot.item().getName() + " sold on the world exchange; " + PhantomCompat.lcoin(player) + " L-Coin.");
			}
			else
			{
				LOGGER.info(PhantomExchange.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") takes back " + lot.item().getName() + ": nobody bought it on the world exchange.");
			}
		}
	}

	/**
	 * Самая сильная вещь лучше надетой (дороже по цене магазина), по карману в L-Coin и не дороже честной цены ({@link #fair}); одна за заход.
	 */
	private static void buy(Player player)
	{
		final long lcoin = PhantomCompat.lcoin(player);
		if (lcoin <= 0)
		{
			return;
		}

		final List<Lot> lots = PhantomCompat.exchangeOffers(player);
		Lot best = null;
		for (Lot lot : lots)
		{
			if ((lot.ownerId() == player.getObjectId()) || (lot.price() > lcoin) || (lot.item().getCount() != 1) || !PhantomHunterOutfit.isUpgrade(player, lot.item()) || (lot.price() > fair(lot, lots)))
			{
				continue;
			}
			if ((best == null) || (lot.item().getTemplate().getReferencePrice() > best.item().getTemplate().getReferencePrice()))
			{
				best = lot;
			}
		}
		if ((best == null) || !PhantomCompat.exchangeBuy(player, best.id()))
		{
			return;
		}

		PhantomHunterOutfit.equipBetterDrops(player);
		final Player seller = World.getPlayer(best.ownerId());
		LOGGER.info(PhantomExchange.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") buys " + best.item().getName() + " on the world exchange for " + best.price() + " L-Coin" + (seller != null ? " from " + seller.getName() : "") + "; " + PhantomCompat.lcoin(player) + " L-Coin left.");
	}

	/**
	 * Честная цена лота для покупателя: своя оценка (не меньше одного L-Coin) или средняя цена других лотов той же вещи — что больше. Сам лот в среднюю не идёт:
	 * один дорогой лот не делает свою цену честной.
	 */
	private static long fair(Lot lot, List<Lot> lots)
	{
		long sum = 0;
		int count = 0;
		for (Lot other : lots)
		{
			if ((other != lot) && (other.item().getId() == lot.item().getId()))
			{
				sum += other.price();
				count++;
			}
		}
		return Math.max(Math.max(1, estimate(lot.item().getTemplate(), lot.item().getCount())), count > 0 ? sum / count : 0);
	}

	/**
	 * Экипировка из товаров лавки — на биржу, дорогая первой: цена — средняя по бирже, нет её — своя оценка; NPC дал бы больше ({@link #worthSelling})
	 * или плата за лот не по свободной адене ({@link PhantomHunterOutfit#freeAdena}) — остаётся лавке.
	 */
	private static void sell(Player player)
	{
		int free = PhantomHuntersConfig.HUNTER_EXCHANGE_LOTS;
		for (Lot lot : PhantomCompat.exchangeOwnLots(player))
		{
			if (lot.status() == Status.REGISTERED)
			{
				free--;
			}
		}

		for (Item item : PhantomHunterOutfit.storeGoods(player))
		{
			if (free <= 0)
			{
				return;
			}
			if ((!item.isWeapon() && !item.isArmor()) || item.isStackable() || item.isEquipped())
			{
				continue;
			}
			if (register(player, item, true) != null)
			{
				free--;
			}
		}
	}

	/**
	 * Выставить вещь по цене биржи или своей оценке, если это выгоднее NPC ({@link #worthSelling}) и хватает свободной адены на плату.
	 * @param policy {@code false} — только самопроверке: без сравнения с NPC, лишь бы продавцу пришёл хоть один L-Coin
	 * @return лот или {@code null} — не выставил
	 */
	private static Lot register(Player player, Item item, boolean policy)
	{
		final long average = PhantomCompat.exchangeAverage(item.getId());
		final long price = average > 0 ? average : estimate(item.getTemplate(), item.getCount());
		if ((price < 1) || (PhantomCompat.exchangeNet(price) < 1) || (policy && !worthSelling(item.getTemplate(), item.getCount(), price)))
		{
			return null;
		}
		final long fee = PhantomCompat.exchangeFee(price);
		if (fee > PhantomHunterOutfit.freeAdena(player))
		{
			return null;
		}

		final String name = item.getName();
		final int itemId = item.getId();
		if (!PhantomCompat.exchangeRegister(player, item, category(item), price))
		{
			LOGGER.info(PhantomExchange.class.getSimpleName() + ": " + player.getName() + " could not put " + name + " on the world exchange.");
			return null;
		}
		LOGGER.info(PhantomExchange.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") puts " + name + " on the world exchange for " + price + " L-Coin (" + (average > 0 ? "average price" : "own estimate") + "), fee " + fee + " adena; " + player.getAdena() + " adena left.");
		for (Lot lot : PhantomCompat.exchangeOwnLots(player))
		{
			if ((lot.status() == Status.REGISTERED) && (lot.item().getId() == itemId))
			{
				return lot;
			}
		}
		return null;
	}

	/**
	 * Самопроверка {@code HuntersSelfTest = EXCHANGE} (раздел 4.51), только на копии базы и только где биржа есть: охотник, у которого есть вещь лучше надетой
	 * и которую выгодно выставить ({@link #nextUpgrade}), — покупатель; другой охотник — продавец: ему в сумку кладётся такая вещь и адена на плату за лот, он выставляет её по своей оценке;
	 * покупателю — столько L-Coin, сколько стоит лот, он идёт на биржу по правилам ({@link #visit}) — покупает и надевает; продавец забирает выручку.
	 * Каждый шаг — в лог с пометкой SelfTest EXCHANGE.
	 * @param hunters охотники в мире
	 */
	static void selfTest(List<Player> hunters)
	{
		final String tag = PhantomExchange.class.getSimpleName() + ": SelfTest EXCHANGE: ";
		if (!enabled())
		{
			LOGGER.info(tag + "no world exchange here or HunterExchange = False, nothing to check.");
			return;
		}

		final List<Player> sorted = new ArrayList<>(hunters);
		sorted.sort(Comparator.comparingInt(Player::getLevel).reversed());
		Player buyer = null;
		ItemTemplate wanted = null;
		for (Player hunter : sorted)
		{
			if (hunter.isDead() || hunter.isInStoreMode())
			{
				continue;
			}
			wanted = nextUpgrade(hunter, true);
			if (wanted != null)
			{
				buyer = hunter;
				break;
			}
		}
		// Выгодного апгрейда нет ни у кого (охотники Essence до 76-го — в стартовом шмоте и без адены): механику биржи проверяем на самом дешёвом апгрейде,
		// за который продавцу придёт хоть один L-Coin, без сравнения с NPC.
		final boolean policy = buyer != null;
		if (!policy)
		{
			for (Player hunter : sorted)
			{
				if (!hunter.isDead() && !hunter.isInStoreMode() && ((wanted = nextUpgrade(hunter, false)) != null))
				{
					buyer = hunter;
					break;
				}
			}
		}
		if (buyer == null)
		{
			LOGGER.info(tag + "no hunter has an upgrade that pays the seller at least one L-Coin, nothing to check.");
			return;
		}
		Player seller = null;
		for (Player hunter : sorted)
		{
			if ((hunter != buyer) && !hunter.isDead() && !hunter.isInStoreMode())
			{
				seller = hunter;
				break;
			}
		}
		if (seller == null)
		{
			LOGGER.info(tag + "no second hunter to sell, nothing to check.");
			return;
		}

		int passed = 0;
		final long estimate = estimate(wanted, 1);
		LOGGER.info(tag + "buyer " + buyer.getName() + " (" + buyer.getLevel() + ") wants " + wanted.getName() + " (reference price " + wanted.getReferencePrice() + ", own estimate " + estimate + " L-Coin, exchange average " + PhantomCompat.exchangeAverage(wanted.getId()) + (policy ? "" : "; nobody has an upgrade worth listing over an NPC sale, checks the mechanics only") + "); seller " + seller.getName() + " (" + seller.getLevel() + ").");

		// 1. Продавец выставляет вещь.
		final Item item = seller.getInventory().addItem(ItemProcessType.REWARD, wanted.getId(), 1, seller, null);
		final long fee = PhantomCompat.exchangeFee(Math.max(estimate, PhantomCompat.exchangeAverage(wanted.getId())));
		final long need = fee - PhantomHunterOutfit.freeAdena(seller);
		if (need > 0)
		{
			PhantomCompat.addAdena(seller, ItemProcessType.REWARD, need, seller, false);
		}
		final Lot lot = item == null ? null : register(seller, item, policy);
		if (lot == null)
		{
			LOGGER.info(tag + "1. FAIL: " + seller.getName() + " did not put " + wanted.getName() + " on the exchange (" + seller.getAdena() + " adena, free " + PhantomHunterOutfit.freeAdena(seller) + ", fee " + fee + ").");
			return;
		}
		passed++;
		LOGGER.info(tag + "1. ok: lot " + lot.id() + " — " + wanted.getName() + " for " + lot.price() + " L-Coin; " + seller.getName() + " has " + seller.getAdena() + " adena after the fee.");

		// 2. Покупатель с L-Coin на лот идёт на биржу по правилам.
		final long lcoinBefore = PhantomCompat.lcoin(buyer);
		buyer.getInventory().addItem(ItemProcessType.REWARD, LCOIN, (int) lot.price(), buyer, null);
		visit(buyer);
		Status status = null;
		for (Lot own : PhantomCompat.exchangeOwnLots(seller))
		{
			if (own.id() == lot.id())
			{
				status = own.status();
			}
		}
		if (status == Status.SOLD)
		{
			passed++;
			LOGGER.info(tag + "2. ok: " + buyer.getName() + " bought lot " + lot.id() + "; " + PhantomCompat.lcoin(buyer) + " L-Coin left (had " + lcoinBefore + " before the test).");
		}
		else
		{
			LOGGER.info(tag + "2. FAIL: lot " + lot.id() + " is " + status + " after " + buyer.getName() + " visited the exchange with " + (lcoinBefore + lot.price()) + " L-Coin.");
		}

		// 3. Купленное надето.
		boolean worn = false;
		for (Item own : buyer.getInventory().getItems())
		{
			if ((own.getId() == wanted.getId()) && own.isEquipped())
			{
				worn = true;
			}
		}
		if (worn)
		{
			passed++;
		}
		LOGGER.info(tag + "3. " + (worn ? "ok" : "FAIL") + ": " + buyer.getName() + (worn ? " wears " : " does not wear ") + wanted.getName() + ".");

		// 4. Продавец забирает выручку.
		final long sellerBefore = PhantomCompat.lcoin(seller);
		visit(seller);
		final long got = PhantomCompat.lcoin(seller) - sellerBefore;
		if ((status == Status.SOLD) && (got > 0))
		{
			passed++;
		}
		LOGGER.info(tag + "4. " + (((status == Status.SOLD) && (got > 0)) ? "ok" : "FAIL") + ": " + seller.getName() + " collected " + got + " L-Coin for lot " + lot.id() + " (price " + lot.price() + ", minus LCoinFee).");
		LOGGER.info(tag + "done, " + passed + " of 4 ok.");
	}

	/**
	 * Для самопроверки: самая дешёвая вещь датапака лучше надетой ({@link PhantomHunterOutfit#isUpgrade}), которую продавцу выгодно выставить по своей оценке
	 * ({@link #worthSelling}; {@code worth = false} — лишь бы за неё пришёл хоть один L-Coin), — «следующая» для охотника.
	 * Магазины NPC не годятся: у Essence они почти пустые. Сначала — дешёвый отбор по шаблону (то же место, дороже надетого, не временная и не квестовая),
	 * пробник {@code new Item(0, …)} (без objectId, в мир не ложится) — только для подходящих.
	 * @return шаблон или {@code null} — такой нет
	 */
	private static ItemTemplate nextUpgrade(Player player, boolean worth)
	{
		ItemTemplate best = null;
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || (!item.isWeapon() && !item.isArmor()) || !item.isTradeable() || item.isQuestItem() || (item.getTime() > 0) || (worth ? !worthSelling(item, 1, estimate(item, 1)) : (PhantomCompat.exchangeNet(estimate(item, 1)) < 1)) || ((best != null) && (item.getReferencePrice() >= best.getReferencePrice())))
			{
				continue;
			}
			final int slot = BodyPart.getPaperdollIndex(item.getBodyPart());
			// Без места (BodyPart.NONE у Essence — слот PAPERDOLL_TOTALSLOTS, за краем массива) и парные места — мимо.
			final Item current = (slot < 0) || (slot >= Inventory.PAPERDOLL_TOTALSLOTS) ? null : player.getInventory().getPaperdollItem(slot);
			if ((current == null) || (current.getTemplate().getBodyPart() != item.getBodyPart()) || (item.getReferencePrice() <= current.getTemplate().getReferencePrice()))
			{
				continue;
			}
			if (PhantomHunterOutfit.isUpgrade(player, new Item(0, item)))
			{
				best = item;
			}
		}
		return best;
	}
}
