package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;

/**
 * Тату пешкам по роли класса (A16, из Эпилога): боец, танк, маг, поддержка — свои три тату ({@code BotClassKits.ini}). Со второй профы и 40-го уровня.
 * Пешка платит сама — краска по цене покупки ({@link HunterShopPrices}) и плата за нанесение, как игрок у Symbol Maker; напарнику владельца — даром.
 * Нанесённые не трогает: только пустые слоты, адена живого — никогда. Работа с тату — через {@link PhantomCompat}: у Essence другой API.
 * У Эпилога свой класс с тем же именем ({@code phantoms/epilogue/java}): там ещё замена оружия напарников на SA-версии.
 */
final class PhantomClassKits
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClassKits.class.getName());

	private static final class Settings
	{
		static final ConfigReader C = new ConfigReader("config/Custom/BotClassKits.ini");
		static final boolean ENABLED = C.getBoolean("BotClassDyesEnabled", false);
		static final int[] FIGHTER = C.getIntArray("BotFighterDyes", ",", "169,174,109");
		static final int[] TANK = C.getIntArray("BotTankDyes", ",", "171,173,111");
		static final int[] MAGE = C.getIntArray("BotMageDyes", ",", "175,180,171");
		static final int[] SUPPORT = C.getIntArray("BotSupportDyes", ",", "180,171,173");
	}

	private PhantomClassKits()
	{
	}

	/**
	 * @return тату для класса: по роли, только существующие и разрешённые классу, не больше трёх
	 */
	static int[] dyePlan(Player player)
	{
		final PlayerClass cls = player.getPlayerClass();
		if ((cls == null) || (cls.level() < 2))
		{
			return new int[0];
		}
		final int[] plan = switch (cls.getId())
		{
			// Танки: Paladin, Dark Avenger, Temple Knight, Shillien Knight и их третьи профы.
			case 5, 6, 20, 33, 90, 91, 99, 106 -> Settings.TANK;
			// Поддержка: хилеры, баферы и призыватели.
			case 14, 16, 17, 28, 30, 41, 43, 51, 52, 96, 97, 98, 104, 105, 111, 112, 115, 116 -> Settings.SUPPORT;
			default -> cls.isMage() ? Settings.MAGE : Settings.FIGHTER;
		};
		return Arrays.stream(plan).distinct().filter(id -> PhantomCompat.hennaAllowed(player, id)).limit(3).toArray();
	}

	/**
	 * @return краска на нанесение по цене покупки плюс плата; -1 — краску не купить
	 */
	static long dyeCost(int dyeId)
	{
		final long[] wear = PhantomCompat.hennaWear(dyeId);
		if (wear == null)
		{
			return -1;
		}
		final long price = HunterShopPrices.buyPrice((int) wear[0]);
		if ((price <= 0) || (wear[1] <= 0))
		{
			return -1;
		}
		return (wear[1] * price) + Math.max(0, wear[2]);
	}

	/**
	 * Нанести тату в пустые слоты.
	 * @param personal напарник владельца — даром
	 * @param budget сколько адены можно потратить (пешке); напарнику — не важно
	 * @return что нанёс — для лога
	 */
	static List<String> applyDyes(Player player, boolean personal, long budget)
	{
		final List<String> applied = new ArrayList<>();
		if (!Settings.ENABLED || (player.getLevel() < 40) || player.isDead() || player.isInCombat() || player.isInOlympiadMode() || player.isOnEvent() || player.isInDuel())
		{
			return applied;
		}
		synchronized (player)
		{
			for (int id : dyePlan(player))
			{
				if (player.getHennaEmptySlots() <= 0)
				{
					break;
				}
				if (PhantomCompat.hasHenna(player, id))
				{
					continue;
				}
				final long cost = personal ? 0 : dyeCost(id);
				if ((cost < 0) || (!personal && ((cost > budget) || (cost > player.getAdena()))))
				{
					break;
				}
				if (!personal && !PhantomCompat.reduceAdena(player, ItemProcessType.BUY, cost, player, false))
				{
					break;
				}
				if (PhantomCompat.addHenna(player, id))
				{
					budget -= cost;
					final long[] wear = PhantomCompat.hennaWear(id);
					final ItemTemplate dye = wear == null ? null : ItemData.getInstance().getTemplate((int) wear[0]);
					applied.add("нанёс тату: " + (dye == null ? id : dye.getName()));
				}
				else
				{
					if (!personal)
					{
						PhantomCompat.addAdena(player, ItemProcessType.REFUND, cost, player, false);
					}
					break;
				}
			}
		}
		return applied;
	}

	/**
	 * Самопроверка KITS ({@code HuntersSelfTest}, только на копии базы): цены покупки по GM-шопу ({@link HunterShopPrices#selfTest}) и тату —
	 * пешка платит ровно краску по цене покупки и плату, мимо бюджета не наносит, напарнику даром. Ключ цен на время включается;
	 * нанесённые тату у проверенных пешек остаются, адена возвращается к прежней.
	 * @param hunters охотники в мире
	 */
	static void selfTest(List<Player> hunters)
	{
		final String tag = PhantomClassKits.class.getSimpleName() + ": SelfTest KITS: ";
		final List<String> failed = new ArrayList<>();
		final boolean shopWas = PhantomHuntersConfig.HUNTER_SHOP_PRICES;
		int checks = 0;
		final List<Player> touched = new ArrayList<>();
		final List<Long> adenaWas = new ArrayList<>();
		try
		{
			PhantomHuntersConfig.HUNTER_SHOP_PRICES = true;
			checks += HunterShopPrices.selfTest(tag, failed);

			checks++;
			if (!Settings.ENABLED)
			{
				LOGGER.warning(tag + "BotClassDyesEnabled = False: dyes are not checked.");
				failed.add("dyes enabled");
				return;
			}
			// Пешки, которым можно нанести хотя бы одно тату: со второй профы и 40-го, есть пустой слот и недостающее тату по плану.
			final List<Player> free = new ArrayList<>();
			for (Player hunter : hunters)
			{
				if ((hunter.getLevel() >= 40) && !hunter.isDead() && !hunter.isInCombat() && !hunter.isInOlympiadMode() && !hunter.isOnEvent() && !hunter.isInDuel() && (hunter.getHennaEmptySlots() > 0) && (missing(hunter) > 0))
				{
					free.add(hunter);
				}
			}
			LOGGER.info(tag + free.size() + " hunters may get a dye.");
			if (free.size() < 2)
			{
				failed.add("two hunters for dyes");
				return;
			}

			// Платно: потрачено ровно столько, сколько стоят нанесённые.
			checks++;
			final Player paid = free.get(0);
			touched.add(paid);
			adenaWas.add((long) paid.getAdena());
			final long gift = 100_000_000L;
			PhantomCompat.addAdena(paid, ItemProcessType.REWARD, gift, paid, false);
			final int slots = paid.getHennaEmptySlots();
			final List<Integer> before = applied(paid);
			final long adena = paid.getAdena();
			final List<String> done = applyDyes(paid, false, Long.MAX_VALUE / 4);
			long expected = 0;
			for (int id : applied(paid))
			{
				if (!before.contains(id))
				{
					expected += dyeCost(id);
				}
			}
			final long spent = adena - paid.getAdena();
			final boolean paidOk = !done.isEmpty() && (done.size() == (slots - paid.getHennaEmptySlots())) && (spent == expected) && (spent > 0);
			LOGGER.info(tag + paid.getName() + " (" + paid.getPlayerClass().name() + ", " + paid.getLevel() + "): " + done + ", spent " + spent + ", expected " + expected + " — " + (paidOk ? "ok" : "WRONG") + ".");
			if (!paidOk)
			{
				failed.add("paid");
			}

			// Мимо бюджета: краска дороже — не наносит и не берёт адену.
			checks++;
			final Player poor = free.get(1);
			touched.add(poor);
			adenaWas.add((long) poor.getAdena());
			final long cost = firstCost(poor);
			PhantomCompat.addAdena(poor, ItemProcessType.REWARD, (cost * 2) + 1, poor, false);
			final long poorAdena = poor.getAdena();
			final List<String> none = applyDyes(poor, false, cost - 1);
			final boolean budgetOk = none.isEmpty() && (poor.getAdena() == poorAdena) && (cost > 0);
			LOGGER.info(tag + poor.getName() + ": the first dye costs " + cost + ", budget " + (cost - 1) + ": " + none + ", adena kept " + (poor.getAdena() == poorAdena) + " — " + (budgetOk ? "ok" : "WRONG") + ".");
			if (!budgetOk)
			{
				failed.add("budget");
			}

			// Напарнику — даром: тату нанесены, адена та же.
			checks++;
			final long freeAdena = poor.getAdena();
			final List<String> personal = applyDyes(poor, true, 0);
			final boolean personalOk = !personal.isEmpty() && (poor.getAdena() == freeAdena);
			LOGGER.info(tag + poor.getName() + " as an owner's companion: " + personal + ", adena kept " + (poor.getAdena() == freeAdena) + " — " + (personalOk ? "ok" : "WRONG") + ".");
			if (!personalOk)
			{
				failed.add("personal");
			}

			// Нанесённые не трогает: второй проход ничего не наносит.
			checks++;
			final List<String> again = applyDyes(paid, false, Long.MAX_VALUE / 4);
			final boolean againOk = again.isEmpty();
			LOGGER.info(tag + paid.getName() + " second pass: " + again + " — " + (againOk ? "ok" : "WRONG") + ".");
			if (!againOk)
			{
				failed.add("second pass");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
			failed.add("exception");
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_SHOP_PRICES = shopWas;
			for (int i = 0; i < touched.size(); i++)
			{
				final Player hunter = touched.get(i);
				final long extra = hunter.getAdena() - adenaWas.get(i);
				if (extra > 0)
				{
					PhantomCompat.reduceAdena(hunter, ItemProcessType.DESTROY, extra, hunter, false);
				}
			}
			LOGGER.info(tag + (checks - failed.size()) + "/" + checks + " passed" + (failed.isEmpty() ? "." : "; failed: " + failed + "."));
		}
	}

	/**
	 * @return id нанесённых тату из плана класса
	 */
	private static List<Integer> applied(Player player)
	{
		final List<Integer> ids = new ArrayList<>();
		for (int id : dyePlan(player))
		{
			if (PhantomCompat.hasHenna(player, id))
			{
				ids.add(id);
			}
		}
		return ids;
	}

	/**
	 * @return сколько тату из плана ещё не нанесено и с ценой
	 */
	private static int missing(Player player)
	{
		int count = 0;
		for (int id : dyePlan(player))
		{
			if (!PhantomCompat.hasHenna(player, id) && (dyeCost(id) > 0))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * @return цена первого ненанесённого тату из плана; 0 — нет такого
	 */
	private static long firstCost(Player player)
	{
		for (int id : dyePlan(player))
		{
			if (!PhantomCompat.hasHenna(player, id))
			{
				return Math.max(0, dyeCost(id));
			}
		}
		return 0;
	}

	/**
	 * Напарнику владельца ({@code .buddy}) — тату даром, при каждом вызове: пустые слоты заполнятся, когда дорастёт. Пешкам — нет, они платят сами в городе.
	 */
	static void personal(Player player, PhantomTemplate template)
	{
		if (template.isHunter() || template.isReaper())
		{
			return;
		}
		applyDyes(player, true, 0);
	}
}
