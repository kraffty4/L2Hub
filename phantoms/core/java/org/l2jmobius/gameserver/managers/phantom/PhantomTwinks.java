package org.l2jmobius.gameserver.managers.phantom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgSell;

/**
 * L2Hub: гномы-твинки на продаже (docs/PHANTOMS.md, раздел 4.91, ключ {@code HunterTwinks}; решение автора 2026-10-31). Как на старых серверах: охотнику не к спеху, и его ценный дроп продаёт
 * гном-твинк на площади Гирана, а сам он качается. Твинк — настоящий персонаж аккаунта фантомов и настоящая лавка; вещи и выручка переходят настоящими переносами. Охотник в нужде садится в лавку
 * сам, как раньше (раздел 4.18).<br>
 * В мире твинк, только пока у него есть товар или выручка. В {@link PhantomData} его нет: чат, обмен, пати и дуэли его не видят, и на всё это он молчит, как афк за лавкой. Сам не ходит и не дерётся.
 */
public final class PhantomTwinks
{
	private static final Logger LOGGER = Logger.getLogger(PhantomTwinks.class.getName());

	/** Чьи твинки: «хозяин:твинк;хозяин:твинк». Персонажи остаются в базе, пока хозяина не сбросили; окно по ней же не показывает их в «Обществе». */
	private static final String WORLD_VARIABLE = "L2HubTwinks";
	/** Твинка заводит тот, кто уже наигрался на новичке. */
	private static final int MIN_OWNER_LEVEL = 20;
	/** Dwarven Fighter: класс гнома у всех хроник. */
	private static final int DWARF_FIGHTER = 53;
	/** Squire's Shirt и Squire's Pants: стартовая одежда бойца. */
	private static final int[] STARTER_CLOTHES =
	{
		1146,
		1147
	};
	private static final String[] NAME_SUFFIXES =
	{
		"Shop",
		"Sell",
		"Trade",
		"Tw",
		"Alt",
		"Mule"
	};
	private static final int NAME_MAX = 16;
	/** Лавка не продалась за столько мс — цены ниже на {@value #DISCOUNT_STEP}. */
	private static final long DISCOUNT_EVERY = 21_600_000;
	private static final double DISCOUNT_STEP = 0.93;
	/** Ниже такой доли цены магазина твинк не спускается: NPC дал бы половину. */
	private static final double DISCOUNT_FLOOR = 0.6;
	private static final int SALT_NAME = 8201;
	private static final int SALT_LOOK = 8202;
	private static final int POINT_TRIES = 12;
	private static final String HAS_STOCK = "SELECT COUNT(*) FROM items i JOIN characters c ON c.charId = i.owner_id WHERE c.char_name = ? AND i.loc = 'INVENTORY'";
	/** Самопроверка: столько мс на ход {@link #control}, он раз в 10 с. */
	private static final long TEST_TICK = 12000;
	private static final long TEST_ADENA = 1_000_000_000L;
	private static final int TEST_MAX_BUYS = 300;

	/** Хозяин в нижнем регистре → имя твинка. */
	private final Map<String, String> _twinks = new ConcurrentHashMap<>();
	private final Map<String, PhantomTemplate> _templates = new ConcurrentHashMap<>();
	/** Твинк в нижнем регистре → когда сел с этой партией. Рестарт сервера скидку обнуляет. */
	private final Map<String, Long> _since = new ConcurrentHashMap<>();
	/** Твинк в нижнем регистре → на какой ступени скидки стоят цены. */
	private final Map<String, Long> _step = new ConcurrentHashMap<>();
	private boolean _loaded;
	private boolean _restored;

	private PhantomTwinks()
	{
	}

	// ---------- Ход ----------

	/**
	 * Раз в 10 с из {@link PhantomHunterManager}: выручку хозяину, распроданный уходит, свежие вещи и скидка — в лавку. Режим снят — все уходят, вещи остаются в базе.
	 * @param wanted охотники сейчас нужны в мире
	 * @param now время, мс
	 */
	synchronized void control(boolean wanted, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_TWINKS || !wanted)
		{
			_restored = false;
			for (String twink : _twinks.values())
			{
				leave(twink, "the mode is off");
			}
			return;
		}

		load();
		if (!_restored)
		{
			_restored = true;
			for (String twink : _twinks.values())
			{
				if (hasStock(twink))
				{
					spawn(twink);
				}
			}
		}
		for (Map.Entry<String, String> entry : _twinks.entrySet())
		{
			final Player twink = PhantomManager.getInstance().getPhantom(template(entry.getValue()));
			if (twink != null)
			{
				tick(entry.getKey(), twink, now);
			}
		}
	}

	private void tick(String ownerName, Player twink, long now)
	{
		settle(ownerName, twink);
		final List<Item> goods = goods(twink);
		if (goods.isEmpty())
		{
			// Выручка ждёт хозяина: без него твинк не уходит, иначе она пролежит в базе до следующей партии.
			if (twink.getAdena() <= 0)
			{
				leave(twink.getName(), "sold out");
			}
			return;
		}

		final String key = twink.getName().toLowerCase();
		final long step = (now - _since.computeIfAbsent(key, k -> now)) / DISCOUNT_EVERY;
		final int listed = Math.min(goods.size(), twink.getPrivateSellStoreLimit());
		if ((twink.getPrivateStoreType() != PrivateStoreType.SELL) || (twink.getSellList().getItemCount() < listed) || (step != _step.getOrDefault(key, 0L)))
		{
			_step.put(key, step);
			open(twink, ownerName, goods, step);
		}
	}

	private static void settle(String ownerName, Player twink)
	{
		final long adena = twink.getAdena();
		final Player owner = World.getPlayer(ownerName);
		if ((adena > 0) && PhantomManager.getInstance().isPhantom(owner) && PhantomCompat.reduceAdena(twink, ItemProcessType.TRANSFER, adena, null, false))
		{
			PhantomCompat.addAdena(owner, ItemProcessType.TRANSFER, adena, null, false);
			LOGGER.info(PhantomTwinks.class.getSimpleName() + ": " + twink.getName() + " brings " + adena + " adena to " + owner.getName() + ".");
		}
	}

	private static List<Item> goods(Player twink)
	{
		final List<Item> goods = new ArrayList<>();
		for (Item item : twink.getInventory().getItems())
		{
			if (!item.isEquipped() && (item.getId() != Inventory.ADENA_ID) && item.isTradeable())
			{
				goods.add(item);
			}
		}
		return goods;
	}

	/**
	 * Настоящая личная лавка, как у охотника ({@code PhantomHunterManager.openStore}): цена магазина × характер хозяина; спешки нет, не продаётся — дешевеет.
	 */
	private static void open(Player twink, String ownerName, List<Item> goods, long step)
	{
		if (!twink.canOpenPrivateStore())
		{
			return;
		}

		final PhantomTemplate owner = PhantomData.getInstance().getTemplate(ownerName);
		final double character = owner == null ? 1 : PhantomHunterManager.priceFactor(owner);
		final double factor = Math.max(DISCOUNT_FLOOR, character * Math.pow(DISCOUNT_STEP, step));
		final TradeList list = twink.getSellList();
		list.clear();
		list.setPackaged(false);
		final List<String> names = new ArrayList<>();
		for (Item item : goods.subList(0, Math.min(goods.size(), twink.getPrivateSellStoreLimit())))
		{
			final long price = Math.max(1, Math.round(item.getTemplate().getReferencePrice() * factor));
			PhantomCompat.addTradeItem(list, item.getObjectId(), item.getCount(), price);
			names.add(item.getName() + (item.getCount() > 1 ? " x" + item.getCount() : "") + " " + price);
		}
		if (list.getItemCount() == 0)
		{
			return;
		}

		list.setTitle(PhantomHunterManager.STORE_TITLES[Rnd.get(PhantomHunterManager.STORE_TITLES.length)]);
		if (!twink.isSitting())
		{
			twink.sitDown();
		}
		twink.setPrivateStoreType(PrivateStoreType.SELL);
		twink.broadcastUserInfo();
		twink.broadcastPacket(new PrivateStoreMsgSell(twink));
		LOGGER.info(PhantomTwinks.class.getSimpleName() + ": " + twink.getName() + " opens a store: price x" + String.format(Locale.ROOT, "%.2f", factor) + ": " + String.join(", ", names) + ".");
	}

	private void leave(String name, String why)
	{
		final PhantomTemplate template = template(name);
		final Player twink = PhantomManager.getInstance().getPhantom(template);
		if (twink == null)
		{
			return;
		}

		twink.getSellList().clear();
		twink.setPrivateStoreType(PrivateStoreType.NONE);
		_since.remove(name.toLowerCase());
		_step.remove(name.toLowerCase());
		PhantomManager.getInstance().despawnHunter(template);
		LOGGER.info(getClass().getSimpleName() + ": " + name + " leaves the square: " + why + ".");
	}

	// ---------- Передача ----------

	/**
	 * В городе, до дел с NPC: охотнику не к спеху, а ценное лежит — отдать твинку. В нужде ({@link PhantomMarket#needsStore}) он сядет в лавку сам.
	 * @param owner охотник
	 * @param template его описание
	 */
	synchronized void handOff(Player owner, PhantomTemplate template)
	{
		if (!PhantomHuntersConfig.HUNTER_TWINKS || template.isReaper() || (owner.getLevel() < MIN_OWNER_LEVEL) || PhantomMarket.needsStore(owner, template))
		{
			return;
		}
		final List<Item> goods = PhantomHunterOutfit.storeGoods(owner);
		if (!goods.isEmpty())
		{
			give(owner, goods);
		}
	}

	/**
	 * @return сколько вещей перешло; 0 — твинка нет и завести нельзя или у него всё занято
	 */
	private int give(Player owner, List<Item> goods)
	{
		load();
		final Player twink = twinkOf(owner);
		if (twink == null)
		{
			return 0;
		}

		final List<String> moved = new ArrayList<>();
		int free = twink.getPrivateSellStoreLimit() - goods(twink).size();
		for (Item item : goods)
		{
			if (free-- <= 0)
			{
				break;
			}
			final String what = item.getName() + (item.getCount() > 1 ? " x" + item.getCount() : "");
			if (PhantomCompat.transferItem(owner, item.getObjectId(), item.getCount(), twink) != null)
			{
				moved.add(what);
			}
		}
		if (moved.isEmpty())
		{
			return 0;
		}

		final String key = twink.getName().toLowerCase();
		_since.put(key, System.currentTimeMillis());
		_step.put(key, 0L);
		open(twink, owner.getName(), goods(twink), 0);
		LOGGER.info(getClass().getSimpleName() + ": " + owner.getName() + " gives " + twink.getName() + " to sell: " + String.join(", ", moved) + ".");
		return moved.size();
	}

	/**
	 * @return твинк хозяина в мире: есть — ставит на площадь, нет — заводит, если не вышел лимит {@code HunterTwinkCount}; иначе {@code null}
	 */
	private Player twinkOf(Player owner)
	{
		final String key = owner.getName().toLowerCase();
		final String known = _twinks.get(key);
		if ((known == null) && (_twinks.size() >= PhantomHuntersConfig.HUNTER_TWINK_COUNT))
		{
			return null;
		}

		final String name = known != null ? known : freeName(owner.getName());
		if (name == null)
		{
			return null;
		}

		final Player twink = spawn(name);
		if ((twink != null) && (known == null))
		{
			_twinks.put(key, name);
			save();
		}
		return twink;
	}

	private Player spawn(String name)
	{
		final PhantomTemplate template = template(name);
		final Player online = PhantomManager.getInstance().getPhantom(template);
		if (online != null)
		{
			return online;
		}

		final Player twink = PhantomManager.getInstance().spawnTwink(template, point());
		if (twink == null)
		{
			return null;
		}

		twink.setDietMode(true);
		if (twink.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST) == null)
		{
			for (int itemId : STARTER_CLOTHES)
			{
				if (ItemData.getInstance().getTemplate(itemId) != null)
				{
					final Item item = twink.getInventory().addItem(ItemProcessType.REWARD, itemId, 1, twink, null);
					if ((item != null) && item.isEquipable())
					{
						twink.getInventory().equipItem(item);
					}
				}
			}
			twink.broadcastUserInfo();
		}
		return twink;
	}

	/**
	 * @return место в рядах площади, поодаль от других лавок
	 */
	private static Location point()
	{
		Location location = PhantomHunterManager.storePoint();
		for (int i = 0; i < POINT_TRIES; i++)
		{
			final Location at = location;
			if (World.getPlayers().stream().noneMatch(player -> player.isInStoreMode() && (Math.hypot(player.getX() - at.getX(), player.getY() - at.getY()) < PhantomHunterManager.STORE_SPACING)))
			{
				break;
			}
			location = PhantomHunterManager.storePoint();
		}
		return location;
	}

	/**
	 * Охотника сбросили (раздел 4.7): твинк уходит вместе с ним, с товаром и выручкой.
	 * @param ownerName имя охотника
	 */
	synchronized void onReset(String ownerName)
	{
		load();
		final String name = _twinks.remove(ownerName.toLowerCase());
		if (name != null)
		{
			PhantomManager.getInstance().resetHunter(template(name));
			save();
		}
	}

	// ---------- Кто чей ----------

	private void load()
	{
		if (_loaded)
		{
			return;
		}

		_loaded = true;
		for (String pair : GlobalVariablesManager.getInstance().getString(WORLD_VARIABLE, "").split(";"))
		{
			final String[] parts = pair.split(":");
			if (parts.length == 2)
			{
				_twinks.put(parts[0], parts[1]);
			}
		}
	}

	private void save()
	{
		GlobalVariablesManager.getInstance().set(WORLD_VARIABLE, _twinks.entrySet().stream().map(entry -> entry.getKey() + ":" + entry.getValue()).collect(Collectors.joining(";")));
		GlobalVariablesManager.getInstance().storeMe();
	}

	private static String freeName(String owner)
	{
		final int first = PhantomDayCycle.mix(owner, 0, SALT_NAME, NAME_SUFFIXES.length);
		for (int i = 0; i < NAME_SUFFIXES.length; i++)
		{
			final String suffix = NAME_SUFFIXES[(first + i) % NAME_SUFFIXES.length];
			final String name = owner.substring(0, Math.min(owner.length(), NAME_MAX - suffix.length())) + suffix;
			if (!CharInfoTable.getInstance().doesCharNameExist(name) && (PhantomData.getInstance().getTemplate(name) == null))
			{
				return name;
			}
		}
		return null;
	}

	/**
	 * Описание твинка не из L2HubPhantoms.xml: таблицы и генератор не меняются. Внешность — от имени; нужна только при создании персонажа.
	 */
	private PhantomTemplate template(String name)
	{
		return _templates.computeIfAbsent(name.toLowerCase(), key ->
		{
			final boolean female = PhantomDayCycle.mix(name, 0, SALT_LOOK, 2) == 0;
			final byte face = (byte) PhantomDayCycle.mix(name, 1, SALT_LOOK, 3);
			final byte hairStyle = (byte) PhantomDayCycle.mix(name, 2, SALT_LOOK, female ? 7 : 5);
			final byte hairColor = (byte) PhantomDayCycle.mix(name, 3, SALT_LOOK, 4);
			return new PhantomTemplate(name, PhantomTemplate.Role.values()[0], female, face, hairStyle, hairColor, new int[]
			{
				DWARF_FIGHTER
			}, PhantomMarket.GIRAN_SQUARE, List.of(), "", "twink", 0);
		});
	}

	private static boolean hasStock(String name)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(HAS_STOCK))
		{
			ps.setString(1, name);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() && (rs.getInt(1) > 0);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomTwinks.class.getSimpleName() + ": Could not look up the stock of " + name + ".", e);
			return false;
		}
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка TWINKS ({@code HuntersSelfTest}, только на копии базы): ключ снят — вещи у хозяина; в нужде не отдаёт; передача настоящая, лавка на площади; лимит твинков; шёпот и обмен без ответа;
	 * настоящие покупки, выручка хозяину, распроданный уходит и остаётся в базе; вход как после рестарта садится с товаром; снятие ключа уводит. Ключи возвращаются.
	 * @param online охотники в мире
	 */
	void selfTest(List<Player> online)
	{
		ThreadPool.execute(() -> runTest(online));
	}

	private void runTest(List<Player> online)
	{
		final String tag = "SelfTest TWINKS: ";
		final boolean wasOn = PhantomHuntersConfig.HUNTER_TWINKS;
		final int wasCount = PhantomHuntersConfig.HUNTER_TWINK_COUNT;
		try
		{
			final List<Player> free = new ArrayList<>();
			for (Player player : online)
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
				if ((template != null) && !template.isReaper() && !player.isDead() && (player.getPrivateStoreType() == PrivateStoreType.NONE))
				{
					free.add(player);
				}
			}
			final List<Player> grown = free.stream().filter(player -> player.getLevel() >= MIN_OWNER_LEVEL).toList();
			final Player young = free.stream().filter(player -> player.getLevel() < MIN_OWNER_LEVEL).findFirst().orElse(null);
			if (grown.size() < 3)
			{
				LOGGER.info(tag + "needs three hunters from level " + MIN_OWNER_LEVEL + ", has " + grown.size() + ".");
				return;
			}

			final Player owner = grown.get(0);
			final Player other = grown.get(1);
			final Player buyer = grown.get(2);
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(owner.getName());
			PhantomCompat.addAdena(buyer, ItemProcessType.REWARD, TEST_ADENA, null, false);

			// Ключ снят: ничего.
			PhantomHuntersConfig.HUNTER_TWINKS = false;
			giveGoods(owner, template);
			PhantomCompat.addAdena(owner, ItemProcessType.REWARD, TEST_ADENA, null, false);
			final int before = PhantomHunterOutfit.storeGoods(owner).size();
			handOff(owner, template);
			LOGGER.info(tag + "key off: goods at the owner " + before + " -> " + PhantomHunterOutfit.storeGoods(owner).size() + ", twinks " + _twinks.size());

			// В нужде и совсем новичок: не отдают. Нужду даёт нехватка адены, её нет только у того, кому уже всё по карману.
			PhantomHuntersConfig.HUNTER_TWINKS = true;
			PhantomHuntersConfig.HUNTER_TWINK_COUNT = 1;
			final PhantomTemplate otherTemplate = PhantomData.getInstance().getTemplate(other.getName());
			giveGoods(other, otherTemplate);
			final boolean need = PhantomMarket.needsStore(other, otherTemplate);
			final int otherBefore = PhantomHunterOutfit.storeGoods(other).size();
			if (need)
			{
				handOff(other, otherTemplate);
			}
			LOGGER.info(tag + other.getName() + " in need " + need + " (short of " + PhantomHunterOutfit.shortfall(other, otherTemplate) + "): goods " + otherBefore + " -> " + PhantomHunterOutfit.storeGoods(other).size() + ", twinks " + _twinks.size());
			if (young != null)
			{
				final PhantomTemplate youngTemplate = PhantomData.getInstance().getTemplate(young.getName());
				giveGoods(young, youngTemplate);
				final int youngBefore = PhantomHunterOutfit.storeGoods(young).size();
				handOff(young, youngTemplate);
				LOGGER.info(tag + young.getName() + " at level " + young.getLevel() + ": goods " + youngBefore + " -> " + PhantomHunterOutfit.storeGoods(young).size() + ", twinks " + _twinks.size());
			}

			// Не к спеху: передача настоящая.
			final int ownerGoods = PhantomHunterOutfit.storeGoods(owner).size();
			final boolean ownerNeed = PhantomMarket.needsStore(owner, template);
			handOff(owner, template);
			final String name = _twinks.get(owner.getName().toLowerCase());
			final Player twink = name == null ? null : World.getPlayer(name);
			if (twink == null)
			{
				LOGGER.info(tag + "failed: " + owner.getName() + " in need " + ownerNeed + " (short of " + PhantomHunterOutfit.shortfall(owner, template) + ") has goods " + ownerGoods + ", twink " + name);
				return;
			}
			LOGGER.info(tag + owner.getName() + " gave " + (ownerGoods - PhantomHunterOutfit.storeGoods(owner).size()) + " of " + ownerGoods + " to " + name + ": level " + twink.getLevel() + ", race " + twink.getRace() + ", distance to the square " + Math.round(twink.calculateDistance2D(PhantomMarket.GIRAN_SQUARE)) + ", store " + twink.getPrivateStoreType() + " " + twink.getSellList().getItemCount() + " items of " + goods(twink).size() + ", slots " + twink.getPrivateSellStoreLimit() + ", title «" + twink.getSellList().getTitle() + "», phantom " + PhantomManager.getInstance().isPhantom(twink) + ", chest " + (twink.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST) != null));

			// Лимит.
			LOGGER.info(tag + "limit 1: " + other.getName() + " gives " + give(other, PhantomHunterOutfit.storeGoods(other)) + ", twinks " + _twinks.size());
			PhantomHuntersConfig.HUNTER_TWINK_COUNT = wasCount;

			// Молчит: в PhantomData его нет.
			PhantomChat.getInstance().onWhisper(buyer, twink, "почем");
			PhantomTrade.getInstance().onRequest(buyer, twink);
			LOGGER.info(tag + "whisper and trade request: no answer expected, template " + PhantomData.getInstance().getTemplate(name));
			Thread.sleep(TEST_TICK);

			// Покупки, как у живого, до последней вещи.
			final long ownerAdena = owner.getAdena();
			long paid = 0;
			int bought = 0;
			while (twink.isOnline() && (twink.getPrivateStoreType() == PrivateStoreType.SELL) && (twink.getSellList().getItemCount() > 0) && (bought < TEST_MAX_BUYS))
			{
				final TradeItem trade = twink.getSellList().getItems().iterator().next();
				final Item item = twink.getInventory().getItemByObjectId(trade.getObjectId());
				if ((item == null) || !PhantomMarket.buy(buyer, new PhantomMarket.Offer(twink.getObjectId(), trade.getObjectId(), item.getId(), 1, trade.getPrice(), item.getName(), false)))
				{
					LOGGER.info(tag + "failed: " + buyer.getName() + " could not buy " + (item == null ? trade.getObjectId() : item.getName()) + " at " + trade.getPrice());
					break;
				}
				if (bought < 6)
				{
					LOGGER.info(tag + buyer.getName() + " buys " + item.getName() + " at " + trade.getPrice() + ", shop " + item.getTemplate().getReferencePrice() + "; twink adena " + twink.getAdena() + ", left in the store " + twink.getSellList().getItemCount());
				}
				paid += trade.getPrice();
				bought++;
			}
			Thread.sleep(TEST_TICK * 2);
			LOGGER.info(tag + "after " + bought + " buys for " + paid + ": owner got " + (owner.getAdena() - ownerAdena) + ", twink in the world " + (World.getPlayer(name) != null) + ", character in the base " + CharInfoTable.getInstance().doesCharNameExist(name) + ", has stock " + hasStock(name));

			// Вторая партия — тот же твинк; выход и вход как после рестарта.
			giveGoods(owner, template);
			handOff(owner, template);
			final Player second = World.getPlayer(name);
			LOGGER.info(tag + "second batch: twinks " + _twinks.size() + ", in the world " + (second != null) + ", store " + (second == null ? "-" : second.getSellList().getItemCount()));
			PhantomManager.getInstance().despawnHunter(template(name));
			synchronized (this)
			{
				_restored = false;
			}
			LOGGER.info(tag + "out of the world, has stock " + hasStock(name));
			Thread.sleep(TEST_TICK);
			final Player third = World.getPlayer(name);
			LOGGER.info(tag + "as after a restart: in the world " + (third != null) + ", store " + (third == null ? "-" : third.getPrivateStoreType() + " " + third.getSellList().getItemCount()) + ", goods " + (third == null ? "-" : goods(third).size()));

			// Ключ снят: уходит, товар в базе.
			PhantomHuntersConfig.HUNTER_TWINKS = false;
			Thread.sleep(TEST_TICK);
			LOGGER.info(tag + "key off again: in the world " + (World.getPlayer(name) != null) + ", has stock " + hasStock(name));
			LOGGER.info(tag + "done.");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed.", e);
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_TWINKS = wasOn;
			PhantomHuntersConfig.HUNTER_TWINK_COUNT = wasCount;
		}
	}

	/**
	 * Ценное, что на нём не надето: оружие и броня верхнего тира его таблицы.
	 */
	private static void giveGoods(Player player, PhantomTemplate template)
	{
		final List<PhantomTemplate.Gear> all = template.getAllGear();
		if (all.isEmpty())
		{
			return;
		}
		final PhantomTemplate.Gear gear = all.get(all.size() - 1);
		for (int itemId : new int[]
		{
			gear.rhand,
			gear.chest,
			gear.legs
		})
		{
			if (itemId > 0)
			{
				PhantomCompat.addItem(player, ItemProcessType.REWARD, itemId, 1, null);
			}
		}
	}

	public static PhantomTwinks getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomTwinks INSTANCE = new PhantomTwinks();
	}
}
