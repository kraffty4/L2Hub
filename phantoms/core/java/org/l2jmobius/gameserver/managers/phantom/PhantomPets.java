package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Pet;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/**
 * L2Hub: петы у части охотников (ключ {@code HunterPets}). Волк с 15-го, хетчлинг с 35-го, страйдер с 55-го; вид (ветер, звезда, сумерки) стабилен по имени.
 * Ошейник или флейта покупается один раз за адену охотника, корм — тоже (пачкой в городе). Пет голоден — хозяин тратит корм. Страйдер ходит рядом, как другие петы:
 * верхом охотник не ездит, посадка снимает оружие. Мёртвого пета охотник поднимает и отзывает. Призыватели пета не заводят: слот занят слугой.
 */
final class PhantomPets
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPets.class.getName());

	/** Wolf Collar (волк). */
	private static final int WOLF_COLLAR = 2375;
	/** Цена Wolf Collar: в датапаке High Five её нет (price), покупка без неё не прошла бы. */
	private static final long WOLF_COLLAR_PRICE = 15000;
	/** Dragonflute of Wind / Star / Twilight (хетчлинги). */
	private static final int[] HATCHLING =
	{
		3500,
		3501,
		3502
	};
	/** Dragon Bugle of Wind / Star / Twilight (страйдеры). */
	private static final int[] STRIDER =
	{
		4422,
		4423,
		4424
	};
	/** Food for Wolves. */
	private static final int WOLF_FOOD = 2515;
	/** Food for Hatchling. */
	private static final int HATCHLING_FOOD = 4038;
	/** Food for Strider. */
	private static final int STRIDER_FOOD = 5168;
	/** Тик пета — не чаще раза в столько на охотника. */
	private static final long TICK_GAP = 10000;
	/** Пачка корма, которую покупают в городе. */
	private static final long FOOD_PACK = 10;
	/** Сытость ниже этой доли — голоден, хозяин кормит. */
	private static final double HUNGRY = 0.5;
	/** Строка лога — не чаще раза в столько на охотника. */
	private static final long NOTE_GAP = 60000;

	/** objectId охотника → время прошлого тика пета. */
	private static final Map<Integer, Long> LAST = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> LAST_NOTE = new ConcurrentHashMap<>();
	/** Что было в руках до посадки на страйдера: objectId оружия и щита (0 — пусто). */
	private static final Map<Integer, int[]> HANDS = new ConcurrentHashMap<>();

	private PhantomPets()
	{
	}

	/**
	 * Тик охотника: купить ошейник и корм, призвать, кормить, на страйдере в городе — верхом.
	 * @param player охотник
	 * @param inTown в городе (Phase.TOWN)
	 * @param now текущее время
	 */
	static void tick(Player player, boolean inTown, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PETS)
		{
			return;
		}
		final Long last = LAST.get(player.getObjectId());
		if ((last != null) && ((now - last) < TICK_GAP))
		{
			return;
		}
		if (player.isDead() || player.isSitting() || player.isInStoreMode() || player.isTeleporting())
		{
			return;
		}
		final Summon current = pet(player);
		if (!wantsPet(player))
		{
			// Пет остался с уровней до призыва (призыватель выучил слугу) или доля петов урезана: отозвать, иначе слот занят и слуга не встанет.
			if (current != null)
			{
				LAST.put(player.getObjectId(), now);
				putAway(player, current);
				note(player, "puts the pet away: no pet for this hunter", now);
			}
			return;
		}
		final int collarId = collarFor(player);
		if (collarId == 0)
		{
			return;
		}
		LAST.put(player.getObjectId(), now);

		// Мёртвый пет: Mobius держит труп до распада, и другого призвать нельзя. Охотник поднимает его и отзывает, следующий тик позовёт снова.
		if ((current != null) && current.isDead())
		{
			putAway(player, current);
			note(player, "picks up the dead pet", now);
			return;
		}

		// Переход с меньшего пета: старый ошейник ещё в сумке, а нового нет — отозвать старого пета, новый купим дальше.
		final int prevId = smallerCollar(collarId);
		if ((prevId != 0) && (player.getInventory().getItemByItemId(collarId) == null) && (player.getInventory().getItemByItemId(prevId) != null))
		{
			final Summon old = pet(player);
			if (old != null)
			{
				unsummon(player, old);
				note(player, "puts away the old pet", now);
				return;
			}
			if (player.isMounted())
			{
				dismount(player); // старый страйдер под седлом — слезть, отозвать на следующем тике
				return;
			}
		}

		// Ошейника нет: покупаем один раз, только в городе.
		if (player.getInventory().getItemByItemId(collarId) == null)
		{
			if (!inTown || !buy(player, collarId, 1))
			{
				return;
			}
		}

		// Корм: в городе запас ниже трёх штук — взять пачку.
		final int foodId = foodFor(collarId);
		if (inTown && (countOf(player, foodId) < 3))
		{
			buy(player, foodId, FOOD_PACK);
		}

		// Верхом охотник не ездит: посадка снимает оружие, а городская закупка видит пустые руки и покупает новое (самопроверка L2HUBKITS). Сидит верхом с прежней сборки — слезть.
		if (player.isMounted())
		{
			dismount(player);
			return;
		}

		// Пета нет и другого призыва нет — позвать своим ходом (обработчик предмета).
		final Summon pet = pet(player);
		if (pet == null)
		{
			if (PhantomCompat.servitor(player) == null)
			{
				summon(player, player.getInventory().getItemByItemId(collarId));
			}
			return;
		}

		// Пет есть: голоден — хозяин кормит.
		if ((fed(pet) < HUNGRY) && (countOf(player, foodId) > 0))
		{
			feed(player, pet, foodId);
		}
	}

	/**
	 * @return ошейник, флейта, горн или корм пета: в хлам и в лавку не идут, иначе охотник продавал бы их в каждом городе и покупал снова
	 */
	static boolean isPetItem(int itemId)
	{
		return (itemId == WOLF_COLLAR) || (itemId == WOLF_FOOD) || (itemId == HATCHLING_FOOD) || (itemId == STRIDER_FOOD) || isQuestCollar(itemId);
	}

	/**
	 * Отозвать пета; мёртвого сначала поднять: Mobius не отзывает труп.
	 */
	private static void putAway(Player player, Summon pet)
	{
		if (pet.isDead())
		{
			pet.doRevive();
		}
		unsummon(player, pet);
	}

	/**
	 * Слезть со страйдера перед лавкой: торговец верхом сидеть не может. Вне ключа и не верхом — ничего.
	 */
	static void leaveSaddle(Player player)
	{
		if (PhantomHuntersConfig.HUNTER_PETS && player.isMounted())
		{
			dismount(player);
		}
	}

	/**
	 * @return завёл бы этот охотник пета: не призыватель (слуга занимает слот) и попал в долю {@code HunterPetsShare}, выбор стабилен по имени
	 */
	static boolean wantsPet(Player player)
	{
		if (PhantomSummoner.summonSkill(player) != null)
		{
			return false;
		}
		return (Math.abs((player.getName().toLowerCase() + "#pet").hashCode()) % 100) < PhantomHuntersConfig.HUNTER_PETS_SHARE;
	}

	/**
	 * @return id предмета призыва по уровню: 0 — рано, волк, хетчлинг или страйдер; вид стабилен по имени
	 */
	static int collarFor(Player player)
	{
		final int level = player.getLevel();
		if (level < 15)
		{
			return 0;
		}
		if (level < 35)
		{
			return WOLF_COLLAR;
		}
		final int kind = Math.abs(player.getName().toLowerCase().hashCode()) % 3;
		return level < 55 ? HATCHLING[kind] : STRIDER[kind];
	}

	/**
	 * @return ошейник пета на ступень меньше (волк при 35+, хетчлинг при 55+), 0 — у волка
	 */
	private static int smallerCollar(int collarId)
	{
		if (collarId == WOLF_COLLAR)
		{
			return 0;
		}
		for (int i = 0; i < HATCHLING.length; i++)
		{
			if (HATCHLING[i] == collarId)
			{
				return WOLF_COLLAR;
			}
			if (STRIDER[i] == collarId)
			{
				return HATCHLING[i];
			}
		}
		return 0;
	}

	private static boolean isQuestCollar(int itemId)
	{
		for (int id : HATCHLING)
		{
			if (id == itemId)
			{
				return true;
			}
		}
		return isStrider(itemId);
	}

	private static boolean isStrider(int collarId)
	{
		for (int id : STRIDER)
		{
			if (id == collarId)
			{
				return true;
			}
		}
		return false;
	}

	private static int foodFor(int collarId)
	{
		return collarId == WOLF_COLLAR ? WOLF_FOOD : isStrider(collarId) ? STRIDER_FOOD : HATCHLING_FOOD;
	}

	private static long countOf(Player player, int itemId)
	{
		final Item item = player.getInventory().getItemByItemId(itemId);
		return item == null ? 0 : item.getCount();
	}

	/**
	 * Купить предмет за адену охотника: сначала списать адену, потом выдать.
	 * @return купил
	 */
	private static boolean buy(Player player, int itemId, long count)
	{
		long price = marketPrice(itemId);
		// У Wolf Collar в датапаке High Five цены нет: своя, как у пачки банок.
		if ((price <= 0) && (itemId == WOLF_COLLAR))
		{
			price = WOLF_COLLAR_PRICE;
		}
		if (price <= 0)
		{
			return false;
		}
		// Флейта и горн — квестовые: дороже и реже. Корм — по обычной цене.
		if (isQuestCollar(itemId))
		{
			price *= 3;
		}
		final long total = price * count;
		if (PhantomHunterOutfit.freeAdena(player) < total)
		{
			return false;
		}
		if (!PhantomCompat.reduceAdena(player, ItemProcessType.BUY, total, player, false))
		{
			return false;
		}
		PhantomMarketMemory.deal(itemId, count, price, true); // цена за штуку, не за пачку
		PhantomCompat.addItem(player, ItemProcessType.BUY, itemId, count, player);
		note(player, "buys pet gear (" + itemId + " x" + count + ")", System.currentTimeMillis());
		return true;
	}

	private static long marketPrice(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item == null ? 0 : Math.round(item.getReferencePrice() * PhantomMarketMemory.factor(itemId));
	}

	private static Summon pet(Player player)
	{
		return PhantomCompat.pet(player);
	}

	private static void summon(Player player, Item collar)
	{
		// Как обработчик ошейника: не в бою и не во время каста — иначе призыв сорвётся.
		if ((collar == null) || player.isInCombat() || player.isCastingNow() || player.isAllSkillsDisabled())
		{
			return;
		}
		if (PhantomCompat.summonPet(player, collar))
		{
			note(player, "summons a pet", System.currentTimeMillis());
		}
	}

	private static void unsummon(Player player, Summon pet)
	{
		pet.unSummon(player);
	}

	private static double fed(Summon pet)
	{
		try
		{
			return ((Pet) pet).getCurrentFed() / (double) ((Pet) pet).getMaxFed();
		}
		catch (Exception e)
		{
			return 1.0;
		}
	}

	/**
	 * Накормить: сначала забрать корм у хозяина, не забрался — не кормить.
	 */
	private static void feed(Player player, Summon pet, int foodId)
	{
		final Item food = player.getInventory().getItemByItemId(foodId);
		if ((food == null) || (player.getInventory().destroyItem(ItemProcessType.NONE, food.getObjectId(), 1, player, null) == null))
		{
			return;
		}
		fedToMax((Pet) pet);
	}

	private static void fedToMax(Pet pet)
	{
		pet.setCurrentFed(pet.getMaxFed());
	}

	/**
	 * Слезть со страйдера и взять в руки то, что сняла посадка (посадка была у прежней сборки комплекта).
	 */
	private static void dismount(Player player)
	{
		if (!player.dismount())
		{
			return;
		}
		final int[] hands = HANDS.remove(player.getObjectId());
		if (hands == null)
		{
			return;
		}
		for (int objectId : hands)
		{
			final Item item = objectId == 0 ? null : player.getInventory().getItemByObjectId(objectId);
			if ((item != null) && !item.isEquipped())
			{
				player.getInventory().equipItem(item);
			}
		}
		player.broadcastUserInfo();
	}

	private static void note(Player player, String what, long now)
	{
		final Long last = LAST_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= NOTE_GAP))
		{
			LAST_NOTE.put(player.getObjectId(), now);
			LOGGER.info(PhantomPets.class.getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}
}