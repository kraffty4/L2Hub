package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.RecipeData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.recipe.RecipeList;
import org.l2jmobius.gameserver.network.serverpackets.RecipeShopMsg;

/**
 * L2Hub: мастерские гномов-охотников (ключ {@code HunterCraft}). Охотник-ремесленник (Artisan, Warsmith, Maestro) садится в Гиране штатной лавкой крафта Mobius
 * ({@code PrivateStoreType.MANUFACTURE}): живой игрок выбирает рецепт, приносит свои материалы и платит за работу. Сам крафт, проверка материалов, шанс и MP — штатный код Mobius, здесь только
 * выбор ремесленников, рецепты и открытие мастерской.<br>
 * Упрощение V1: рецепты ремесленник получает бесплатно, по своему уровню Create Item (скилл 172). Мастерскую открывает только ремесленник-«мастер», его выбор стабилен по имени.
 */
final class PhantomCraft
{
	private static final Logger LOGGER = Logger.getLogger(PhantomCraft.class.getName());

	private static final int CREATE_ITEM = 172;
	private static final int[] CRAFTER_CLASSES = { 56, 57, 118 };
	/** Плата за работу — не меньше этой суммы в адене. */
	private static final long MIN_FEE = 100;
	private static final String[] TITLES = { "Крафт, ресы ваши", "Крафчу недорого", "Крафт шмота и шотов", "Craft: your mats", "Мастерская, заходи" };

	/** Кто сейчас сидит мастерской (objectId): их не больше {@code HunterCraftMaxWorkshops}, остальные места на площади — лавкам продажи. */
	private static final Set<Integer> OPEN = ConcurrentHashMap.newKeySet();

	private PhantomCraft()
	{
	}

	/**
	 * Идёт ли охотник в мастерскую вместо обычной лавки. Детерминированно, без случайности: при одинаковом персонаже всегда одинаковый ответ.
	 */
	static boolean wantsWorkshop(Player player)
	{
		if (!PhantomHuntersConfig.HUNTER_CRAFT)
		{
			return false;
		}
		if (!isCrafter(player))
		{
			return false;
		}
		if (createItemLevel(player) < 1)
		{
			return false;
		}
		// Доля ремесленников, работающих мастерской; выбор стабилен по имени.
		return (Math.abs(player.getName().toLowerCase().hashCode()) % 100) < PhantomHuntersConfig.HUNTER_CRAFT_SHARE;
	}

	/**
	 * Пойти мастерской можно сейчас: ремесленник-«мастер», и на площади ещё есть место под мастерскую.
	 */
	static boolean canOpenWorkshop(Player player)
	{
		return wantsWorkshop(player) && roomForWorkshop();
	}

	/**
	 * Открыть мастерскую: записать рецепты в книгу, выставить платы и сесть лавкой крафта.
	 * @return открыл ли
	 */
	static boolean openWorkshop(Player player)
	{
		if (!canOpenWorkshop(player))
		{
			return false;
		}
		final int level = createItemLevel(player);

		// Кандидаты — гномьи рецепты двух верхних ярусов по Create Item: старьё никому не нужно.
		final List<RecipeList> candidates = new ArrayList<>();
		for (RecipeList recipe : dwarvenRecipes())
		{
			final int recipeLevel = recipeLevel(recipe);
			if ((recipeLevel <= level) && (recipeLevel >= (level - 1)))
			{
				candidates.add(recipe);
			}
		}
		// Дорогие товары первыми: на них больше плата за работу.
		candidates.sort((a, b) -> Long.compare(productPrice(b), productPrice(a)));
		final int limit = Math.min(PhantomHuntersConfig.HUNTER_CRAFT_RECIPES, candidates.size());
		if (limit == 0)
		{
			return false;
		}

		clearWorkshop(player);
		for (int i = 0; i < limit; i++)
		{
			final RecipeList recipe = candidates.get(i);
			learn(player, recipe);
			final long fee = Math.max(MIN_FEE, (productPrice(recipe) * PhantomHuntersConfig.HUNTER_CRAFT_FEE_PERCENT) / 100);
			addWorkshopItem(player, recipe, fee);
		}

		String title = TITLES[Rnd.get(TITLES.length)];
		if (title.length() > 29)
		{
			title = title.substring(0, 29);
		}
		setWorkshopTitle(player, title);
		player.sitDown();
		player.setPrivateStoreType(PrivateStoreType.MANUFACTURE);
		player.broadcastUserInfo();
		broadcastWorkshop(player);
		OPEN.add(player.getObjectId());

		LOGGER.info(PhantomCraft.class.getSimpleName() + ": " + player.getName() + " opens the workshop; " + limit + " recipes; Create Item " + level + ".");
		return true;
	}

	/**
	 * @return есть место под ещё одну мастерскую; заодно забывает тех, кто мастерскую уже свернул или вышел
	 */
	private static boolean roomForWorkshop()
	{
		OPEN.removeIf(objectId ->
		{
			final Player crafter = World.getPlayer(objectId);
			return (crafter == null) || (crafter.getPrivateStoreType() != PrivateStoreType.MANUFACTURE);
		});
		return OPEN.size() < PhantomHuntersConfig.HUNTER_CRAFT_MAX_WORKSHOPS;
	}

	/** Ремесленник ли: Artisan, Warsmith или Maestro. */
	private static boolean isCrafter(Player player)
	{
		final int classId = player.getPlayerClass().getId();
		for (final int crafter : CRAFTER_CLASSES)
		{
			if (crafter == classId)
			{
				return true;
			}
		}
		return false;
	}

	private static int createItemLevel(Player player)
	{
		final int level = player.getSkillLevel(CREATE_ITEM);
		return Math.max(level, 0);
	}

	private static Collection<RecipeList> dwarvenRecipes()
	{
		final List<RecipeList> dwarven = new ArrayList<>();
		for (int recipeItemId : RecipeData.getInstance().getAllItemIds())
		{
			final RecipeList recipe = RecipeData.getInstance().getRecipeByItemId(recipeItemId);
			if ((recipe != null) && recipe.isDwarvenRecipe())
			{
				dwarven.add(recipe);
			}
		}
		return dwarven;
	}

	private static int recipeLevel(RecipeList recipe)
	{
		return recipe.getLevel();
	}

	private static long productPrice(RecipeList recipe)
	{
		final ItemTemplate template = ItemData.getInstance().getTemplate(recipe.getItemId());
		return template == null ? 0 : template.getReferencePrice();
	}

	private static void learn(Player player, RecipeList recipe)
	{
		if (!player.hasRecipeList(recipe.getId()))
		{
			player.registerDwarvenRecipeList(recipe, true);
		}
	}

	private static void clearWorkshop(Player player)
	{
		player.getManufactureItems().clear();
	}

	private static void addWorkshopItem(Player player, RecipeList recipe, long fee)
	{
		PhantomCompat.addManufactureItem(player, recipe.getId(), fee);
	}

	private static void setWorkshopTitle(Player player, String title)
	{
		player.setStoreName(title);
	}

	private static void broadcastWorkshop(Player player)
	{
		player.broadcastPacket(new RecipeShopMsg(player));
	}
}
