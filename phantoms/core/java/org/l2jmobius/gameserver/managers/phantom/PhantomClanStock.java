package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.BodyPart;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;

/**
 * L2Hub: склад клана для новичков (docs/PHANTOMS.md, 4.107, ключ {@code HunterClanStock}; решение автора: взял из кланового склада — обязан вернуть, своё продавай). Склад настоящий: вещи лежат на {@code Clan.getWarehouse()} и переходят штатной передачей с тем же objectId.
 * <ul>
 * <li>кто вырос из вещи, в городе сдаёт её на склад, если таких там меньше {@value #COPIES}; иначе продаёт как обычно ({@link #settle});</li>
 * <li>кому своя вещь не по карману, берёт со склада вещь своего места и вида ({@link #borrow}); взятую помнит по objectId в переменной персонажа;</li>
 * <li>взятое не сдаётся NPC, не идёт в лавку, в обмен, твинку и под заточку ({@link #isLoan});</li>
 * <li>снял, потому что вырос или купил своё, или ушёл из клана — возвращает ({@link #settle}). Клан распался — вещь его.</li>
 * </ul>
 * Запрет — правило в логике охотника, штатный склад L2 его не знает. Только кланы охотников и только охотники: живого игрока в таком клане правило не касается, у живого ничего не забирается.
 */
final class PhantomClanStock
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanStock.class.getName());

	/** Что взято: «objectId:клан;…». */
	static final String VARIABLE = "L2HubClanLoans";
	/** Одинаковых вещей на складе — не больше; сверх этого старший продаёт как обычно. */
	private static final int COPIES = 2;
	/** Склад клана у Mobius — с 1-го уровня (WarehouseInstance). */
	private static final int MIN_CLAN_LEVEL = 1;

	/** Охотник → objectId вещи → клан, у которого она взята. */
	private static final Map<Integer, Map<Integer, Integer>> LOANS = new ConcurrentHashMap<>();

	private PhantomClanStock()
	{
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_CLAN_STOCK;
	}

	/**
	 * @return вещь выдана со склада клана: не его, не продаётся, не меняется, не точится
	 */
	static boolean isLoan(Player player, Item item)
	{
		return enabled() && (item != null) && loans(player).containsKey(item.getObjectId());
	}

	private static Map<Integer, Integer> loans(Player player)
	{
		return LOANS.computeIfAbsent(player.getObjectId(), id ->
		{
			final Map<Integer, Integer> result = new ConcurrentHashMap<>();
			final String saved = player.getVariables().getString(VARIABLE, "");
			for (String pair : saved.split(";"))
			{
				final int colon = pair.indexOf(':');
				if (colon > 0)
				{
					result.put(Integer.parseInt(pair.substring(0, colon)), Integer.parseInt(pair.substring(colon + 1)));
				}
			}
			return result;
		});
	}

	private static void save(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Integer, Integer> entry : loans(player).entrySet())
		{
			sb.append(sb.length() > 0 ? ";" : "").append(entry.getKey()).append(':').append(entry.getValue());
		}
		if (sb.length() > 0)
		{
			player.getVariables().set(VARIABLE, sb.toString());
		}
		else
		{
			player.getVariables().remove(VARIABLE);
		}
		player.getVariables().storeMe();
	}

	/**
	 * @return клан охотников со складом, и сам он охотник
	 */
	private static boolean hasStock(Player player)
	{
		final Clan clan = player.getClan();
		return PhantomHunterClans.isHunterClan(clan) && (clan.getLevel() >= MIN_CLAN_LEVEL) && PhantomHunterClans.isHunter(player);
	}

	/**
	 * Городские дела, до сдачи хлама и после покупок: вернуть снятое и взятое у прежнего клана, сдать переросшее.
	 * @param player охотник
	 * @param template его описание
	 * @param done что сделал — для лога
	 */
	static void settle(Player player, PhantomTemplate template, List<String> done)
	{
		if (!enabled())
		{
			return;
		}
		try
		{
			giveBack(player, template, done);
			donate(player, done);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomClanStock.class.getSimpleName() + ": " + player.getName() + ": " + e.getMessage(), e);
		}
	}

	private static void giveBack(Player player, PhantomTemplate template, List<String> done)
	{
		final Map<Integer, Integer> loans = loans(player);
		boolean changed = false;
		for (Map.Entry<Integer, Integer> entry : new ArrayList<>(loans.entrySet()))
		{
			final Item item = player.getInventory().getItemByObjectId(entry.getKey());
			final Clan origin = ClanTable.getInstance().getClan(entry.getValue());
			if ((item == null) || (origin == null))
			{
				// Вещи нет (смерть с кармой забрала её штатно) или клан распался: вещь его, правило снято.
				loans.remove(entry.getKey());
				changed = true;
				LOGGER.info(PhantomClanStock.class.getSimpleName() + ": " + player.getName() + " forgets the loan " + entry.getKey() + " of clan " + entry.getValue() + ": " + (item == null ? "no such item any more" : item.getName() + " is his now, the clan is gone") + ".");
				continue;
			}
			final boolean left = player.getClanId() != origin.getId();
			if ((!left && item.isEquipped()) || !origin.getWarehouse().validateCapacity(1))
			{
				continue;
			}
			final String name = item.getName();
			if (item.isEquipped())
			{
				player.getInventory().unEquipItemInSlot(item.getLocationSlot());
			}
			if (player.getInventory().transferItem(ItemProcessType.TRANSFER, item.getObjectId(), 1, origin.getWarehouse(), player, null) != null)
			{
				loans.remove(entry.getKey());
				changed = true;
				done.add("вернул на склад клана: " + name);
				LOGGER.info(PhantomClanStock.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") returned " + name + " to the stock of clan " + origin.getName() + (left ? " (left the clan)" : "") + ".");
			}
		}
		if (changed)
		{
			save(player);
			if (player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND) == null)
			{
				PhantomHunterOutfit.update(player, template);
			}
			PhantomCompat.refreshExpertisePenalty(player);
			player.broadcastUserInfo();
		}
	}

	/**
	 * Вырос из вещи: на склад, если таких там не хватает. Вещь своего грейда не сдаёт — её можно продать дорого; лавка открыта — не трогает: товар уже выставлен.
	 */
	private static void donate(Player player, List<String> done)
	{
		if (!hasStock(player) || (player.getPrivateStoreType() != PrivateStoreType.NONE))
		{
			return;
		}
		final Clan clan = player.getClan();
		final ItemContainer stock = clan.getWarehouse();
		final int expertise = PhantomCompat.expertiseLevel(player);
		for (Item item : new ArrayList<>(player.getInventory().getItems()))
		{
			// Одно место на складе остаётся: для адены в казну клана, когда кончится стопка.
			if (!gear(item) || !PhantomHunterOutfit.junk(player, item) || (item.getTemplate().getCrystalType().getLevel() >= expertise) || (stock.getAllItemsByItemId(item.getId()).size() >= COPIES) || !stock.validateCapacity(2))
			{
				continue;
			}
			final String name = item.getName();
			if (player.getInventory().transferItem(ItemProcessType.TRANSFER, item.getObjectId(), 1, stock, player, null) != null)
			{
				done.add("сдал на склад клана: " + name);
				LOGGER.info(PhantomClanStock.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") gave " + name + " to the stock of clan " + clan.getName() + ".");
			}
		}
	}

	/**
	 * Снаряжение для склада: оружие или доспех тех мест, что охотник покупает ({@code PhantomHunterOutfit.SLOTS}; бижутерию он не берёт, она лежала бы без дела), с грейдом, передаётся, не геройское, не временное.
	 */
	private static boolean gear(Item item)
	{
		if (!item.isEquipable() || !(item.isWeapon() || item.isArmor()) || (item.getTemplate().getCrystalType().getLevel() <= 0) || !item.isTradeable() || item.isHeroItem() || (item.getTemplate().getTime() > 0))
		{
			return false;
		}
		final int place = BodyPart.getPaperdollIndex(item.getTemplate().getBodyPart());
		for (int slot : PhantomHunterOutfit.SLOTS)
		{
			if (slot == place)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Городские дела, перед покупками: на чём своё не по карману, взять со склада, надеть и запомнить.
	 * @param player охотник
	 * @param template его описание
	 * @param done что сделал — для лога
	 */
	static void borrow(Player player, PhantomTemplate template, List<String> done)
	{
		if (!enabled() || !hasStock(player))
		{
			return;
		}
		try
		{
			take(player, template, done);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomClanStock.class.getSimpleName() + ": " + player.getName() + ": " + e.getMessage(), e);
		}
	}

	private static void take(Player player, PhantomTemplate template, List<String> done)
	{
		final Clan clan = player.getClan();
		final ItemContainer stock = clan.getWarehouse();
		boolean wore = false;
		for (int slot : PhantomHunterOutfit.SLOTS)
		{
			if (((slot == Inventory.PAPERDOLL_LEGS) && (PhantomHunterOutfit.wornBodyPart(player, Inventory.PAPERDOLL_CHEST) == BodyPart.FULL_ARMOR)) || ((slot == Inventory.PAPERDOLL_LHAND) && (PhantomHunterOutfit.wornBodyPart(player, Inventory.PAPERDOLL_RHAND) == BodyPart.LR_HAND)))
			{
				continue;
			}
			Item best = null;
			for (Item item : stock.getItems())
			{
				if (fits(player, template, item, slot) && ((best == null) || (item.getTemplate().getCrystalType().getLevel() > best.getTemplate().getCrystalType().getLevel()) || ((item.getTemplate().getCrystalType().getLevel() == best.getTemplate().getCrystalType().getLevel()) && (item.getTemplate().getReferencePrice() > best.getTemplate().getReferencePrice()))))
				{
					best = item;
				}
			}
			if (best == null)
			{
				continue;
			}
			final String name = best.getName();
			final Item taken = stock.transferItem(ItemProcessType.TRANSFER, best.getObjectId(), 1, player.getInventory(), player, null);
			if (taken == null)
			{
				continue;
			}
			loans(player).put(taken.getObjectId(), clan.getId());
			save(player);
			PhantomHunterOutfit.wear(player, taken);
			wore = true;
			done.add("взял со склада клана: " + name);
			LOGGER.info(PhantomClanStock.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") borrowed " + name + " from the stock of clan " + clan.getName() + "; adena " + player.getAdena() + ".");
		}
		if (wore)
		{
			PhantomCompat.refreshExpertisePenalty(player);
			player.broadcastUserInfo();
		}
	}

	/**
	 * Вещь со склада в этот слот: по экспертизе, лучше надетого, того же места и вида, что его набор, и своя такая ему не по карману.
	 */
	private static boolean fits(Player player, PhantomTemplate template, Item item, int slot)
	{
		if (!gear(item) || (BodyPart.getPaperdollIndex(item.getTemplate().getBodyPart()) != slot) || (player.getAdena() >= item.getTemplate().getReferencePrice()))
		{
			return false;
		}
		final int expertise = PhantomCompat.expertiseLevel(player);
		final int grade = item.getTemplate().getCrystalType().getLevel();
		if ((grade > expertise) || (grade <= PhantomHunterOutfit.wornGrade(player.getInventory().getPaperdollItem(slot))))
		{
			return false;
		}
		for (PhantomTemplate.Gear gear : template.getAllGear())
		{
			final int wantedId = PhantomHunterOutfit.itemFor(gear, slot);
			final ItemTemplate wanted = wantedId > 0 ? ItemData.getInstance().getTemplate(wantedId) : null;
			if ((wanted != null) && (wanted.getBodyPart() == item.getTemplate().getBodyPart()) && (wanted.getItemType() == item.getTemplate().getItemType()) && (!item.isWeapon() || (wanted.isMagicWeapon() == item.getTemplate().isMagicWeapon())))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Самопроверка CLANSTOCK ({@code HuntersSelfTest}, только на копии базы: вещи выдаются и снимаются на месте). Клан охотников, где есть старший и младший по экспертизе: старший сдаёт, младший берёт и носит;
	 * взятое не продаётся никуда и не точится, своё продаётся; вырос — вернул; ушёл из клана — вернул; рестарт не забывает; клан живого не трогает.
	 * @param hunters охотники в мире
	 */
	static void selfTest(List<Player> hunters)
	{
		final String tag = PhantomClanStock.class.getSimpleName() + ": SelfTest CLANSTOCK: ";
		try
		{
			Player elder = null;
			Player novice = null;
			for (Clan clan : ClanTable.getInstance().getClans())
			{
				if (!PhantomHunterClans.isHunterClan(clan) || (clan.getLevel() < MIN_CLAN_LEVEL))
				{
					continue;
				}
				Player top = null;
				Player low = null;
				for (Player hunter : hunters)
				{
					if ((hunter.getClan() != clan) || !PhantomHunterClans.isHunter(hunter) || hunter.isClanLeader() || (PhantomCompat.expertiseLevel(hunter) < 1) || (hunter.getPrivateStoreType() != PrivateStoreType.NONE))
					{
						continue;
					}
					top = (top == null) || (PhantomCompat.expertiseLevel(hunter) > PhantomCompat.expertiseLevel(top)) ? hunter : top;
					low = (low == null) || (PhantomCompat.expertiseLevel(hunter) < PhantomCompat.expertiseLevel(low)) ? hunter : low;
				}
				if ((top != null) && (PhantomCompat.expertiseLevel(top) > PhantomCompat.expertiseLevel(low)))
				{
					elder = top;
					novice = low;
					break;
				}
			}
			if (elder == null)
			{
				LOGGER.warning(tag + "needs a hunter clan with two members of different expertise.");
				return;
			}
			final Clan clan = novice.getClan();
			final PhantomTemplate noviceTemplate = PhantomData.getInstance().getTemplate(novice.getName());
			final PhantomTemplate elderTemplate = PhantomData.getInstance().getTemplate(elder.getName());
			final int expertise = PhantomCompat.expertiseLevel(novice);
			final int weaponId = setWeapon(noviceTemplate, expertise);
			if (weaponId == 0)
			{
				LOGGER.warning(tag + "no weapon of grade " + expertise + " in the set of " + novice.getName() + ".");
				return;
			}
			final ItemTemplate weapon = ItemData.getInstance().getTemplate(weaponId);
			final ItemContainer stock = clan.getWarehouse();
			LOGGER.info(tag + "clan " + clan.getName() + " (level " + clan.getLevel() + "), elder " + elder.getName() + " (" + elder.getLevel() + "), novice " + novice.getName() + " (" + novice.getLevel() + "), item " + weapon.getName() + ", price " + weapon.getReferencePrice() + ".");

			// Старший вырос из вещи: сдаёт, две копии — предел, третью продаёт как обычно.
			final List<String> log = new ArrayList<>();
			final long stockBefore = stock.getAllItemsByItemId(weaponId).size();
			final Item given = elder.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, elder, null);
			final int givenId = given.getObjectId();
			donate(elder, log);
			check(tag, "1. старший сдал на склад", stock.getItemByObjectId(givenId) != null, log.toString());
			final Item second = elder.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, elder, null);
			final Item third = elder.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, elder, null);
			log.clear();
			donate(elder, log);
			check(tag, "2. копий на складе не больше " + COPIES + ", лишняя у него и продаётся", (stock.getAllItemsByItemId(weaponId).size() == Math.max(COPIES, stockBefore)) && ((elder.getInventory().getItemByObjectId(third.getObjectId()) != null) || (elder.getInventory().getItemByObjectId(second.getObjectId()) != null)) && PhantomHunterOutfit.junk(elder, elder.getInventory().getItemByObjectId(third.getObjectId()) != null ? third : second), "на складе " + stock.getAllItemsByItemId(weaponId).size());

			// Младший без денег берёт и носит; с деньгами — покупает сам.
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, weapon.getReferencePrice());
			log.clear();
			borrow(novice, noviceTemplate, log);
			final boolean richSkips = novice.getInventory().getPaperdollItemId(Inventory.PAPERDOLL_RHAND) != weaponId;
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, 0);
			log.clear();
			borrow(novice, noviceTemplate, log);
			final Item loan = novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
			check(tag, "3. при деньгах не берёт, без денег взял и надел", richSkips && (loan != null) && (loan.getId() == weaponId) && isLoan(novice, loan), log + ", в руке " + (loan == null ? "ничего" : loan.getName()));
			if ((loan == null) || (loan.getId() != weaponId))
			{
				LOGGER.info(tag + "done, stop: no loan.");
				return;
			}
			check(tag, "4. запомнил в переменной персонажа", novice.getVariables().getString(VARIABLE, "").contains(loan.getObjectId() + ":" + clan.getId()), novice.getVariables().getString(VARIABLE, ""));

			// Рестарт не забывает: кэш — заново из переменной.
			LOANS.remove(novice.getObjectId());
			check(tag, "5. после перезагрузки памяти помнит", isLoan(novice, loan), "");

			// Заточка не трогает надетую выданную вещь.
			final boolean enchant = PhantomEnchant.next(novice, 99) != loan;
			check(tag, "6. заточка мимо выданной", enchant, "");

			// Снял: хлам, лавка, рынок, обмен, твинк — всё мимо. Своё такое же продаётся.
			novice.getInventory().unEquipItemInSlot(loan.getLocationSlot());
			final boolean notJunk = !PhantomHunterOutfit.junk(novice, loan);
			final boolean notStore = !PhantomHunterOutfit.storeGoods(novice).contains(loan);
			final boolean notOffer = PhantomHunterOutfit.sellOffers(novice).stream().noneMatch(offer -> offer.itemId() == weaponId);
			final boolean notTrade = !PhantomNear.tradeGoods(novice).contains(loan);
			final StringBuilder sold = new StringBuilder();
			final String refused = PhantomHunterOutfit.sellNamed(novice, loan.getName(), false, sold);
			final long adena = novice.getAdena();
			final long price = PhantomHunterOutfit.testSell(novice, loan);
			check(tag, "7. не хлам, не лавка, не SELL, не TRADE, не NPC", notJunk && notStore && notOffer && notTrade && (refused != null) && (price == 0) && (novice.getAdena() == adena) && (novice.getInventory().getItemByObjectId(loan.getObjectId()) != null), "хлам " + !notJunk + ", лавка " + !notStore + ", SELL " + !notOffer + ", TRADE " + !notTrade + ", отказ «" + refused + "», выручка " + price);
			final Item own = novice.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, novice, null);
			check(tag, "8. своё такое же продаётся", PhantomHunterOutfit.junk(novice, own) && (PhantomHunterOutfit.testSell(novice, own) > 0), "");

			// Снятая выданная возвращается.
			log.clear();
			settle(novice, noviceTemplate, log);
			check(tag, "9. снял — вернул на склад, в переменной её нет", (stock.getItemByObjectId(loan.getObjectId()) != null) && !isLoan(novice, loan) && !novice.getVariables().getString(VARIABLE, "").contains(loan.getObjectId() + ":"), log.toString());

			// Купил своё лучше: прошлая выданная слетает через retire и возвращается; своё остаётся.
			log.clear();
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, 0);
			borrow(novice, noviceTemplate, log);
			final Item again = novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
			final Item better = novice.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, novice, null);
			better.setEnchantLevel(1);
			PhantomHunterOutfit.wear(novice, better);
			final boolean kept = (again != null) && (novice.getInventory().getItemByObjectId(again.getObjectId()) != null) && !again.isEquipped();
			log.clear();
			settle(novice, noviceTemplate, log);
			check(tag, "10. надел своё: выданная не продана и вернулась", kept && (again != null) && (stock.getItemByObjectId(again.getObjectId()) != null), log.toString());

			// Ушёл из клана — вернул, хоть и надета.
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, 0);
			log.clear();
			borrow(novice, noviceTemplate, log);
			final Item worn = novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
			final boolean wearing = (worn != null) && isLoan(novice, worn);
			final Player leader = clan.getLeader().getPlayer();
			PhantomCompat.leaveClan(novice);
			novice.setClanJoinExpiryTime(0);
			log.clear();
			settle(novice, noviceTemplate, log);
			check(tag, "11. ушёл из клана: надетая снята и вернулась на склад клана", wearing && (stock.getItemByObjectId(worn.getObjectId()) != null) && (novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND) != worn), log.toString());
			if (leader != null)
			{
				PhantomHunterClans.getInstance().join(clan, leader, novice, true);
			}

			// Клан распался: вещь его, правило снято. На настоящем клане не проверить: подставляем чужой id.
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, 0);
			borrow(novice, noviceTemplate, log);
			final Item orphan = novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
			if ((orphan != null) && isLoan(novice, orphan))
			{
				loans(novice).put(orphan.getObjectId(), Integer.MAX_VALUE);
				log.clear();
				giveBack(novice, noviceTemplate, log);
				check(tag, "12. клана больше нет: вещь осталась ему, правило снято", (novice.getInventory().getItemByObjectId(orphan.getObjectId()) != null) && !isLoan(novice, orphan), "");
			}

			// Клан живого: ни сдачи, ни взятия. Ключ выключен — правила нет.
			Player inLiving = null;
			for (Player hunter : hunters)
			{
				if ((hunter.getClan() != null) && !PhantomHunterClans.isHunterClan(hunter.getClan()))
				{
					inLiving = hunter;
					break;
				}
			}
			if (inLiving != null)
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(inLiving.getName());
				final ItemContainer livingStock = inLiving.getClan().getWarehouse();
				final int before = livingStock.getSize();
				log.clear();
				donate(inLiving, log);
				borrow(inLiving, template, log);
				check(tag, "13. клан живого: склад не тронут", (livingStock.getSize() == before) && log.isEmpty(), inLiving.getName() + " в клане " + inLiving.getClan().getName());
			}
			else
			{
				LOGGER.info(tag + "13. SKIP: нет охотника в клане живого в мире.");
			}
			PhantomHuntersConfig.HUNTER_CLAN_STOCK = false;
			final boolean off = !isLoan(novice, novice.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND));
			log.clear();
			borrow(novice, noviceTemplate, log);
			PhantomHuntersConfig.HUNTER_CLAN_STOCK = true;
			check(tag, "14. ключ выключен: правила и склада нет", off && log.isEmpty(), "");

			// Полный заход в город: настоящие дела берут со склада, старший в том же заходе сдаёт.
			PhantomHunterOutfit.testStrip(novice, noviceTemplate, 0);
			final List<String> chores = PhantomHunterOutfit.townChores(novice, noviceTemplate, new java.util.HashMap<>());
			check(tag, "15. обычный заход в город берёт со склада", chores.stream().anyMatch(line -> line.startsWith("взял со склада")), chores.toString());
			final Item stray = elder.getInventory().addItem(ItemProcessType.REWARD, weaponId, 1, elder, null);
			final List<String> elderChores = PhantomHunterOutfit.townChores(elder, elderTemplate, new java.util.HashMap<>());
			LOGGER.info(tag + "elder in town: " + elderChores + ", item " + (stock.getItemByObjectId(stray.getObjectId()) != null ? "in the stock" : elder.getInventory().getItemByObjectId(stray.getObjectId()) != null ? "in the bag" : "sold") + ".");

			final StringBuilder items = new StringBuilder();
			for (Item item : stock.getItems())
			{
				items.append(item.getName()).append(item.getCount() > 1 ? " x" + item.getCount() : "").append(", ");
			}
			LOGGER.info(tag + "stock of " + clan.getName() + ": " + items + "total " + stock.getSize() + ".");
			ThreadPool.execute(novice::storeMe);
			ThreadPool.execute(elder::storeMe);
			LOGGER.info(tag + "done.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
	}

	/**
	 * @return оружие набора этого грейда или 0
	 */
	private static int setWeapon(PhantomTemplate template, int expertise)
	{
		for (PhantomTemplate.Gear gear : template.getAllGear())
		{
			final ItemTemplate item = gear.rhand > 0 ? ItemData.getInstance().getTemplate(gear.rhand) : null;
			if ((item != null) && (item.getCrystalType().getLevel() == expertise))
			{
				return gear.rhand;
			}
		}
		return 0;
	}

	private static void check(String tag, String step, boolean ok, String detail)
	{
		LOGGER.info(tag + step + ": " + (ok ? "OK" : "FAIL") + (detail.isEmpty() ? "" : " (" + detail + ")") + ".");
	}
}
