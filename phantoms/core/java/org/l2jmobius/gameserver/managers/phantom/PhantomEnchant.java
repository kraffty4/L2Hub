package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.EnchantItemData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enchant.EnchantResultType;
import org.l2jmobius.gameserver.entity.item.enchant.EnchantScroll;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/**
 * L2Hub: заточка у охотников (docs/LIVE_SERVER.md, п. 14; docs/PHANTOMS.md; ключ {@code HunterEnchant}). В городе охотник со свободной аденой точит надетое настоящими свитками с настоящим шансом
 * сервера (EnchantItemGroups): сначала оружие, потом доспех. Свиток берёт из сумки или покупает на рынке по средней цене. Докуда точит — характер: большинство останавливается на безопасной заточке,
 * жадные идут дальше и ломают вещи. Сломал — кристаллы, как у игрока, новая вещь (если есть на что), крик в торговый чат и строка в летописи. Заточка видна на персонаже.
 */
final class PhantomEnchant
{
	private static final Logger LOGGER = Logger.getLogger(PhantomEnchant.class.getName());

	/** Свитки по грейдам D, C, B, A, S: id у хроник общие. */
	private static final int[] WEAPON_SCROLLS = { 955, 951, 947, 729, 959 };
	private static final int[] ARMOR_SCROLLS = { 956, 952, 948, 730, 960 };
	private static final String GRADES = "DCBAS";
	private static final int[] ARMOR_SLOTS = { Inventory.PAPERDOLL_CHEST, Inventory.PAPERDOLL_LEGS, Inventory.PAPERDOLL_HEAD, Inventory.PAPERDOLL_GLOVES, Inventory.PAPERDOLL_FEET };
	/** Безопасная заточка: столько точит почти каждый. */
	private static final int SAFE = 3;
	/** Попыток за один заход в город. */
	private static final int TRIES = 3;
	/** На свитки — не больше этой части свободной адены за заход. */
	private static final int BUDGET_DIVIDER = 4;
	/** С какой заточки об успехе пишут в летопись и кричат. */
	private static final int NEWS_SUCCESS_LEVEL = 7;
	/** С какой заточки о поломке пишут и кричат: ниже — обычное дело. */
	private static final int NEWS_BREAK_LEVEL = 4;
	/** Bastard Sword: оружие грейда D с этим id у всех хроник. */
	private static final int TEST_WEAPON = 69;
	private static final String[] BROKE_LINES = { "%s +%d в кристаллы, ну как так-то", "%s +%d сломан. всё, я в запой", "%s +%d... с третьей попытки, сука, но сломал", "минус %s +%d, не точите по ночам" };
	private static final String[] BROKE_LINES_EN = { "%s +%d is crystals now, how even", "broke my %s +%d. I'm done", "%s +%d gone, third try too", "lost %s +%d, never enchant at night" };
	private static final String[] DONE_LINES = { "%s +%d! кто ещё сомневался", "ЕСТЬ %s +%d", "%s +%d, можно и в ГК постоять", "%s +%d с первого раза, учитесь" };
	private static final String[] DONE_LINES_EN = { "%s +%d! anyone still doubt me", "YES %s +%d", "%s +%d, time to stand at the GK", "%s +%d first try, learn" };

	/** Чем кончилась попытка. */
	private enum Result
	{
		DONE,
		BROKE,
		KEPT,
		CANNOT
	}

	private PhantomEnchant()
	{
	}

	/**
	 * Заход в город: поточить надетое, если есть на что. Зовётся из {@link PhantomHunterOutfit#townChores}.
	 * @param player охотник
	 * @param template его шаблон
	 * @return записи для журнала дел
	 */
	static List<String> chore(Player player, PhantomTemplate template)
	{
		final List<String> done = new ArrayList<>();
		if (!PhantomHuntersConfig.HUNTER_ENCHANT)
		{
			return done;
		}
		final int temper = temper(template.getName());
		long budget = PhantomHunterOutfit.freeAdena(player) / BUDGET_DIVIDER;
		int tries = TRIES;
		for (Item item = next(player, temper); (item != null) && (tries > 0); item = next(player, temper))
		{
			tries--;
			final int level = item.getEnchantLevel();
			final String name = item.getName();
			final int scrollId = scrollId(item);
			final boolean owned = player.getInventory().getItemByItemId(scrollId) != null;
			final long price = owned ? 0 : marketPrice(scrollId);
			if ((scrollId == 0) || (!owned && ((price <= 0) || (price > budget))))
			{
				break;
			}
			if (!owned)
			{
				if (!PhantomCompat.reduceAdena(player, ItemProcessType.BUY, price, player, false))
				{
					break;
				}
				budget -= price;
				PhantomMarketMemory.deal(scrollId, 1, price, true);
				PhantomCompat.addItem(player, ItemProcessType.BUY, scrollId, 1, player);
			}
			final Result result = attempt(player, player.getInventory().getItemByItemId(scrollId), item);
			if (result == Result.CANNOT)
			{
				break;
			}
			if (result == Result.DONE)
			{
				done.add("заточил " + name + " на +" + (level + 1));
				announce(player, name, level + 1, false);
			}
			else if (result == Result.BROKE)
			{
				done.add("сломал " + name + " +" + level);
				announce(player, name, level, true);
				break; // рук больше не поднимается
			}
		}
		return done;
	}

	/**
	 * Одна настоящая попытка, как {@code RequestEnchantItem} с простым свитком: свиток расходуется, успех — +1, провал — кристаллы.
	 */
	private static Result attempt(Player player, Item scrollItem, Item item)
	{
		final EnchantScroll scroll = scrollItem == null ? null : EnchantItemData.getInstance().getEnchantScroll(scrollItem);
		final EnchantResultType roll = scroll == null ? null : PhantomCompat.enchantRoll(player, scroll, item);
		if (roll == null)
		{
			return Result.CANNOT;
		}
		if (player.getInventory().destroyItem(ItemProcessType.NONE, scrollItem.getObjectId(), 1, player, null) == null)
		{
			return Result.CANNOT;
		}

		final int level = item.getEnchantLevel();
		final String name = item.getName();
		final boolean worn = item.isEquipped();
		final int slot = item.getLocationSlot();
		if (roll == EnchantResultType.SUCCESS)
		{
			// Снять и надеть: так бонусы заточки пересчитаются, а +N увидят все вокруг.
			if (worn)
			{
				player.getInventory().unEquipItemInSlot(slot);
			}
			item.setEnchantLevel(level + 1);
			item.updateDatabase();
			if (worn)
			{
				player.getInventory().equipItem(item);
			}
			player.broadcastUserInfo();
			LOGGER.info(PhantomEnchant.class.getSimpleName() + ": " + player.getName() + " enchanted " + name + " +" + level + " -> +" + (level + 1) + ".");
			return Result.DONE;
		}

		if (scroll.isSafe() || scroll.isBlessed())
		{
			LOGGER.info(PhantomEnchant.class.getSimpleName() + ": " + player.getName() + " failed on " + name + " +" + level + ", kept.");
			return Result.KEPT;
		}
		if (worn)
		{
			player.getInventory().unEquipItemInSlot(slot);
		}
		final int crystalId = item.getTemplate().isCrystallizable() ? item.getTemplate().getCrystalItemId() : 0;
		final int crystals = crystalId == 0 ? 0 : Math.max(0, item.getCrystalCount() - ((item.getTemplate().getCrystalCount() + 1) / 2));
		if (player.getInventory().destroyItem(ItemProcessType.NONE, item, player, null) == null)
		{
			return Result.CANNOT;
		}
		if (crystals > 0)
		{
			player.getInventory().addItem(ItemProcessType.COMPENSATE, crystalId, crystals, player, null);
		}
		player.broadcastUserInfo();
		LOGGER.info(PhantomEnchant.class.getSimpleName() + ": " + player.getName() + " broke " + name + " +" + level + ", crystals " + crystals + ".");
		return Result.BROKE;
	}

	static void announce(Player player, String item, int level, boolean broke)
	{
		if (level < (broke ? (PhantomStories.enabled() ? PhantomStories.BREAK_LEVEL : NEWS_BREAK_LEVEL) : NEWS_SUCCESS_LEVEL))
		{
			return;
		}
		final boolean en = "en".equals(L2HubWorldConfig.LANGUAGE);
		final String ending = player.getAppearance().isFemale() ? "а" : "";
		final String text = broke ? (en ? " broke " + item + " +" : " сломал" + ending + " " + item + " +") : (en ? " enchanted " + item + " to +" : " заточил" + ending + " " + item + " на +");
		PhantomNews.post("enchant", player.getName() + text + level + (broke ? PhantomStories.brokeAgain(player, en) : ""), player.getName(), player);
		final String[] pool = broke ? (en ? BROKE_LINES_EN : BROKE_LINES) : (en ? DONE_LINES_EN : DONE_LINES);
		PhantomChat.getInstance().hunterShout(player, String.format(pool[Rnd.get(pool.length)], item, level));
		if (broke)
		{
			PhantomAbsence.afterBreak(player, item, level);
		}
	}

	/**
	 * @return что точить дальше: оружие, затем доспех, ниже цели; {@code null} — всё на цели
	 */
	private static Item next(Player player, int temper)
	{
		final Item weapon = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		if ((weapon != null) && (scrollId(weapon) != 0) && (weapon.getEnchantLevel() < target(temper, true)))
		{
			return weapon;
		}
		for (int slot : ARMOR_SLOTS)
		{
			final Item armor = player.getInventory().getPaperdollItem(slot);
			if ((armor != null) && (scrollId(armor) != 0) && (armor.getEnchantLevel() < target(temper, false)))
			{
				return armor;
			}
		}
		return null;
	}

	/**
	 * Докуда точит, по характеру: 60 % — безопасная заточка, 30 % — на одну-две выше, 10 % — азартные, оружие до +9, доспех до +6.
	 */
	static int target(int temper, boolean weapon)
	{
		if (temper < 60)
		{
			return SAFE;
		}
		if (temper < 90)
		{
			return SAFE + 1 + (temper % 2);
		}
		return weapon ? 6 + (temper % 4) : 5 + (temper % 2);
	}

	static int temper(String name)
	{
		return Math.abs((name + "enchant").toLowerCase().hashCode()) % 100;
	}

	/**
	 * @return свиток под вещь или 0: у вещи нет грейда или она не оружие и не доспех
	 */
	private static int scrollId(Item item)
	{
		final int grade = GRADES.indexOf(item.getTemplate().getCrystalType().name().charAt(0));
		if (grade < 0)
		{
			return 0;
		}
		return item.isWeapon() ? WEAPON_SCROLLS[grade] : item.isArmor() ? ARMOR_SCROLLS[grade] : 0;
	}

	private static long marketPrice(int scrollId)
	{
		final ItemTemplate scroll = ItemData.getInstance().getTemplate(scrollId);
		return scroll == null ? 0 : Math.round(scroll.getReferencePrice() * PhantomMarketMemory.factor(scrollId));
	}

	/**
	 * Самопроверка на копии базы (TownFakesStoreTest): вещь грейда D точится настоящими свитками, пока не сломается; шаг за шагом сходятся заточка, свитки и кристаллы.
	 * @param player персонаж
	 * @return что не сошлось, пусто — всё сошлось
	 */
	public static List<String> selfTest(Player player)
	{
		final List<String> failed = new ArrayList<>();
		final Item worn = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		final Item item = PhantomCompat.addItem(player, ItemProcessType.REWARD, TEST_WEAPON, 1, player);
		if (item == null)
		{
			failed.add("нет тестовой вещи");
			return failed;
		}
		final int scrollId = scrollId(item);
		if (scrollId == 0)
		{
			failed.add("у тестовой вещи " + item.getName() + " нет свитка, грейд " + item.getTemplate().getCrystalType());
			return failed;
		}
		PhantomCompat.addItem(player, ItemProcessType.REWARD, scrollId, 60, player);
		final long crystalsBefore = countOf(player, item.getTemplate().getCrystalItemId());
		int tries = 0;
		int done = 0;
		int level = item.getEnchantLevel();
		Result result = Result.DONE;
		while ((tries < 60) && (result != Result.BROKE) && (result != Result.CANNOT))
		{
			tries++;
			final long scrolls = countOf(player, scrollId);
			result = attempt(player, player.getInventory().getItemByItemId(scrollId), item);
			if ((result != Result.CANNOT) && (countOf(player, scrollId) != (scrolls - 1)))
			{
				failed.add("свиток не списан на попытке " + tries);
			}
			if (result == Result.DONE)
			{
				done++;
				if (item.getEnchantLevel() != (level + 1))
				{
					failed.add("заточка " + level + " → " + item.getEnchantLevel());
				}
				level = item.getEnchantLevel();
			}
		}
		if (result == Result.CANNOT)
		{
			if (level == 0)
			{
				failed.add("свиток не подошёл к " + item.getName());
			}
		}
		else if (result == Result.BROKE)
		{
			if (player.getInventory().getItemByObjectId(item.getObjectId()) != null)
			{
				failed.add("сломанная вещь осталась");
			}
			if (item.getTemplate().isCrystallizable() && (countOf(player, item.getTemplate().getCrystalItemId()) <= crystalsBefore) && (level > 0))
			{
				failed.add("нет кристаллов за поломку на +" + level);
			}
		}
		if ((worn != null) && !worn.isEquipped())
		{
			player.getInventory().equipItem(worn);
		}
		player.getInventory().destroyItemByItemId(ItemProcessType.NONE, scrollId, (int) countOf(player, scrollId), player, null);
		LOGGER.info(PhantomEnchant.class.getSimpleName() + ": self-test " + item.getName() + ": tries " + tries + ", enchanted " + done + ", last +" + level + ", " + result + ".");
		return failed;
	}

	private static long countOf(Player player, int itemId)
	{
		long total = 0;
		for (Item item : player.getInventory().getAllItemsByItemId(itemId))
		{
			total += item.getCount();
		}
		return total;
	}
}
