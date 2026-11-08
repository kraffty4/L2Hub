package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;

/**
 * Цены покупки пешек по каталогам GM-шопа (C13, из Эпилога): пешка платит за вещь столько же, сколько игрок у GM-шопа ({@code HunterShopPrices} в PhantomHunters.ini).
 * Вещь в шопе за адену — его цена за штуку (пачка — делённая на штуки, с округлением вверх); за Coin of Luck — по курсу, только если шоп меняет
 * адену на монету и обратно по одному курсу. Вещь в шопе только за жетоны или обменом — покупки нет (цена 0). Вещи нет в шопе — цена предмета, как раньше.
 * Ключ выключен или GM-шоп выключен на доске ({@code TownGmShop}) — везде цена предмета. Скупка NPC не меняется.
 * <p>
 * Каталог читается из XML мультиселлов один раз, при первой покупке: правки редактора цен окна ложатся в те же файлы и доходят со следующего старта гейма.
 * У Эпилога свой класс с тем же именем ({@code phantoms/epilogue/java}): он берёт каталог из уже загруженного {@code MultisellData}.
 */
public final class HunterShopPrices
{
	private static final Logger LOGGER = Logger.getLogger(HunterShopPrices.class.getName());
	/** NPC GM-шопа — тот же, что у {@link PhantomHunterOutfit} для BUY. */
	private static final int GM_SHOP = 50010;
	private static final int ADENA = 57;
	private static final int COIN_OF_LUCK = 4037;

	private record Catalog(Map<Integer, Long> prices, Set<Integer> listed)
	{
	}

	private static volatile Catalog _catalog;

	private HunterShopPrices()
	{
	}

	private static Catalog current()
	{
		Catalog catalog = _catalog;
		if (catalog == null)
		{
			synchronized (HunterShopPrices.class)
			{
				catalog = _catalog;
				if (catalog == null)
				{
					catalog = PhantomHuntersConfig.HUNTER_SHOP_PRICES && PhantomLootSell.shopOn() ? load() : new Catalog(Map.of(), Set.of());
					_catalog = catalog;
				}
			}
		}
		return catalog;
	}

	/**
	 * @return цена штуки в шопе за адену (или монету по курсу); пусто — шоп её так не продаёт или цены шопа не действуют
	 */
	static OptionalLong shopPrice(int id)
	{
		if (!PhantomHuntersConfig.HUNTER_SHOP_PRICES)
		{
			return OptionalLong.empty();
		}
		final Long price = current().prices().get(id);
		return price == null ? OptionalLong.empty() : OptionalLong.of(price);
	}

	/**
	 * @return вещь есть в шопе чем угодно (за адену, жетоны, обменом); цены шопа не действуют — {@code false}
	 */
	static boolean listed(int id)
	{
		return PhantomHuntersConfig.HUNTER_SHOP_PRICES && current().listed().contains(id);
	}

	/**
	 * @return почём пешка покупает вещь: цена шопа; в шопе только за жетоны или обменом — 0 (покупки нет); нет в шопе или цены шопа не действуют — цена предмета
	 */
	static long buyPrice(ItemTemplate item)
	{
		if (item == null)
		{
			return 0;
		}
		if (PhantomHuntersConfig.HUNTER_SHOP_PRICES)
		{
			final Catalog catalog = current();
			final Long price = catalog.prices().get(item.getId());
			if (price != null)
			{
				return price;
			}
			if (catalog.listed().contains(item.getId()))
			{
				return 0;
			}
		}
		return Math.max(0, item.getReferencePrice());
	}

	static long buyPrice(int id)
	{
		return buyPrice(id <= 0 ? null : ItemData.getInstance().getTemplate(id));
	}

	/**
	 * @return цены шопа действуют, а вещь в нём только за жетоны или обменом: пешка её не покупает. При выключенном ключе — всегда {@code false}, как раньше
	 */
	static boolean notForSale(ItemTemplate item)
	{
		return (item != null) && PhantomHuntersConfig.HUNTER_SHOP_PRICES && (buyPrice(item) <= 0) && current().listed().contains(item.getId());
	}

	/**
	 * Каталог из мультиселлов NPC {@value #GM_SHOP}: XML читается сам, как в {@link PhantomHunterOutfit} — у MultisellData хроник API разный.
	 */
	private static Catalog load()
	{
		final Map<Integer, Long> prices = new HashMap<>();
		final Set<Integer> listed = new HashSet<>();
		final Map<Integer, long[]> coins = new HashMap<>(); // id вещи → {монет, штук} — пересчёт, когда известен курс
		long purchase = 0;
		long redemption = 0;
		boolean coinRateBroken = false;
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		for (String dir : new String[]
		{
			"data/multisell",
			"data/multisell/custom"
		})
		{
			final File[] files = new File(ServerConfig.DATAPACK_ROOT, dir).listFiles((folder, name) -> name.endsWith(".xml"));
			if (files == null)
			{
				continue;
			}
			for (File file : files)
			{
				final int listId = number(file.getName().replace(".xml", ""));
				try
				{
					final Document document = factory.newDocumentBuilder().parse(file);
					if (!forNpc(document))
					{
						continue;
					}

					final NodeList items = document.getElementsByTagName("item");
					for (int i = 0; i < items.getLength(); i++)
					{
						final Element item = (Element) items.item(i);
						// Чего шоп этой хроники не продаёт (на High Five — флаги территорий и варды при GmShopAllowTerritoryFlags = False), не покупает и пешка.
						if (!sells(listId, item))
						{
							continue;
						}
						final NodeList ingredients = item.getElementsByTagName("ingredient");
						final NodeList productions = item.getElementsByTagName("production");
						for (int p = 0; p < productions.getLength(); p++)
						{
							final int product = number(((Element) productions.item(p)).getAttribute("id"));
							if (product > 0)
							{
								listed.add(product);
							}
						}
						if ((ingredients.getLength() != 1) || (productions.getLength() != 1))
						{
							continue;
						}

						final Element ingredient = (Element) ingredients.item(0);
						final Element production = (Element) productions.item(0);
						final int in = number(ingredient.getAttribute("id"));
						final int out = number(production.getAttribute("id"));
						final long total = count(ingredient.getAttribute("count"));
						final long units = count(production.getAttribute("count"));
						if ((total <= 0) || (units <= 0) || (out <= 0))
						{
							continue;
						}
						if ((in == ADENA) && (out == COIN_OF_LUCK))
						{
							final long rate = (total % units) == 0 ? total / units : -1;
							coinRateBroken |= (rate <= 0) || ((purchase != 0) && (purchase != rate));
							purchase = rate;
						}
						else if ((in == COIN_OF_LUCK) && (out == ADENA))
						{
							final long rate = (units % total) == 0 ? units / total : -1;
							coinRateBroken |= (rate <= 0) || ((redemption != 0) && (redemption != rate));
							redemption = rate;
						}
						if (out == ADENA)
						{
							continue;
						}
						if (in == ADENA)
						{
							prices.merge(out, perUnit(total, units), Math::min);
						}
						else if (in == COIN_OF_LUCK)
						{
							coins.merge(out, new long[]
							{
								total,
								units
							}, (a, b) -> (a[0] * b[1]) <= (b[0] * a[1]) ? a : b);
						}
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(HunterShopPrices.class.getSimpleName() + ": " + file.getName() + " не прочитан: " + e.getMessage());
				}
			}
		}

		// Монета — по курсу, только если шоп продаёт и выкупает её по одному курсу: иначе цена в монетах аденой не платится.
		final long coinRate = !coinRateBroken && (purchase > 0) && (purchase == redemption) ? purchase : 0;
		if (coinRate > 0)
		{
			for (Map.Entry<Integer, long[]> entry : coins.entrySet())
			{
				try
				{
					prices.merge(entry.getKey(), perUnit(Math.multiplyExact(entry.getValue()[0], coinRate), entry.getValue()[1]), Math::min);
				}
				catch (ArithmeticException e)
				{
					// цена не помещается в long — такую вещь пешка не покупает
				}
			}
		}
		final long barter = listed.stream().filter(id -> !prices.containsKey(id)).count();
		LOGGER.info(HunterShopPrices.class.getSimpleName() + ": hunters buy at GM shop prices: " + prices.size() + " items for adena" + (coinRate > 0 ? " (Coin of Luck at " + coinRate + ")" : "") + ", " + barter + " only for tokens or barter.");
		return new Catalog(Map.copyOf(prices), Set.copyOf(listed));
	}

	/**
	 * Часть самопроверки KITS ({@link PhantomClassKits#selfTest}): ключ на время включён вызывающим.
	 * @param failed сюда — названия проваленных проверок
	 * @return сколько проверок сделано
	 */
	static int selfTest(String tag, List<String> failed)
	{
		final Catalog catalog = current();
		int checks = 1;
		LOGGER.info(tag + "shop catalog: " + catalog.prices().size() + " items for adena, " + catalog.listed().size() + " listed; GM shop on " + PhantomLootSell.shopOn() + ".");
		if (catalog.prices().isEmpty())
		{
			failed.add("catalog");
			return checks;
		}

		// Вещь за адену, у которой цена шопа не равна цене предмета: пешка платит цену шопа.
		ItemTemplate priced = null;
		ItemTemplate barter = null;
		for (Map.Entry<Integer, Long> entry : catalog.prices().entrySet())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(entry.getKey());
			if (item == null)
			{
				continue;
			}
			if (priced == null)
			{
				priced = item;
			}
			if (item.getReferencePrice() != entry.getValue())
			{
				priced = item;
				break;
			}
		}
		for (int id : catalog.listed())
		{
			final ItemTemplate item = ItemData.getInstance().getTemplate(id);
			if ((item != null) && !catalog.prices().containsKey(id) && (item.getReferencePrice() > 0))
			{
				barter = item;
				break;
			}
		}
		ItemTemplate unlisted = null;
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item != null) && (item.getReferencePrice() > 0) && !catalog.listed().contains(item.getId()))
			{
				unlisted = item;
				break;
			}
		}

		if (priced == null)
		{
			failed.add("catalog items");
			return checks;
		}
		checks++;
		final long shop = catalog.prices().get(priced.getId());
		final boolean pricedOk = (buyPrice(priced) == shop) && (PhantomHunterOutfit.price(priced.getId()) == shop) && !notForSale(priced);
		LOGGER.info(tag + "for adena: " + priced.getName() + " shop " + shop + ", item price " + priced.getReferencePrice() + ", hunter pays " + buyPrice(priced) + ", outfit price " + PhantomHunterOutfit.price(priced.getId()) + " — " + (pricedOk ? "ok" : "WRONG") + ".");
		if (!pricedOk)
		{
			failed.add("for adena");
		}
		if (barter != null)
		{
			checks++;
			final boolean barterOk = (buyPrice(barter) == 0) && notForSale(barter);
			LOGGER.info(tag + "only for tokens or barter: " + barter.getName() + " (item price " + barter.getReferencePrice() + "), hunter pays " + buyPrice(barter) + ", not for sale " + notForSale(barter) + " — " + (barterOk ? "ok" : "WRONG") + ".");
			if (!barterOk)
			{
				failed.add("barter");
			}
		}
		else
		{
			LOGGER.info(tag + "nothing in the shop only for tokens or barter, step skipped.");
		}
		if (unlisted != null)
		{
			checks++;
			final boolean unlistedOk = buyPrice(unlisted) == unlisted.getReferencePrice();
			LOGGER.info(tag + "not in the shop: " + unlisted.getName() + ", item price " + unlisted.getReferencePrice() + ", hunter pays " + buyPrice(unlisted) + " — " + (unlistedOk ? "ok" : "WRONG") + ".");
			if (!unlistedOk)
			{
				failed.add("not in the shop");
			}
		}

		// Ключ выключен — везде цена предмета, как раньше.
		checks++;
		PhantomHuntersConfig.HUNTER_SHOP_PRICES = false;
		final boolean offOk = (buyPrice(priced) == Math.max(0, priced.getReferencePrice())) && ((barter == null) || (!notForSale(barter) && (buyPrice(barter) == barter.getReferencePrice())));
		PhantomHuntersConfig.HUNTER_SHOP_PRICES = true;
		LOGGER.info(tag + "key off: item prices again — " + (offOk ? "ok" : "WRONG") + ".");
		if (!offOk)
		{
			failed.add("key off");
		}
		return checks;
	}

	private static long perUnit(long total, long units)
	{
		return (total / units) + ((total % units) == 0 ? 0 : 1);
	}

	private static boolean sells(int listId, Element item)
	{
		final NodeList productions = item.getElementsByTagName("production");
		for (int p = 0; p < productions.getLength(); p++)
		{
			if (!PhantomCompat.gmShopSells(listId, number(((Element) productions.item(p)).getAttribute("id"))))
			{
				return false;
			}
		}
		return true;
	}

	private static int number(String text)
	{
		try
		{
			return Integer.parseInt(text.strip());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static long count(String text)
	{
		try
		{
			return Long.parseLong(text.strip());
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static boolean forNpc(Document document)
	{
		final NodeList npcs = document.getElementsByTagName("npc");
		for (int i = 0; i < npcs.getLength(); i++)
		{
			if (String.valueOf(GM_SHOP).equals(npcs.item(i).getTextContent().strip()))
			{
				return true;
			}
		}
		return false;
	}
}
