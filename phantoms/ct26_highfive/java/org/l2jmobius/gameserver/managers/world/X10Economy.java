package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;

/** Личный кошелёк: начисление и уникальная причина записываются одной транзакцией. */
final class X10Economy
{
	private static final Logger LOGGER = Logger.getLogger(X10Economy.class.getName());
	private static final Map<Integer, Goods> SHOP = new LinkedHashMap<>();
	private static boolean ready;
	static final int COIN_ITEM = 40001;
	static final int STYLE_ITEM = 40002;

	record Goods(int id, String name, String category, int level, long coins, long style)
	{
	}

	/** Мультиселлы магазина: штатное окно обмена H5, цена — предметы-валюты, списание — через кошелёк. */
	static final int LIST_GEAR = 90004001;
	static final int LIST_SUPPLIES = 90004002;
	static final int LIST_STYLE = 90004003;
	static final int LIST_ENHANCE = 90004004;
	static final int LIST_PRESTIGE = 90004005;
	static final int LIST_AGATHION = 90004006;
	private static final int[] LISTS = {LIST_GEAR, LIST_SUPPLIES, LIST_STYLE, LIST_ENHANCE, LIST_PRESTIGE, LIST_AGATHION};

	static boolean start()
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			createTables(con);
			loadShop();
			registerLists();
			ready = true;
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "X10 economy unavailable; rewards and purchases disabled", e);
			// L2Hub: без кошелька коины 40001 (чемпионы, PvP-ивенты) падают обычным предметом и в кошелёк не идут — сказать прямо.
			LOGGER.warning("X10 economy is OFF: wallet, coin shop and coin rewards do not work until the database and L2HubX10Shop.xml load. "
				+ "Coins (item 40001) from champions and PvP events stay in the bag as a plain item and are NOT credited to the wallet.");
			return false;
		}
	}

	static void createTables(Connection con) throws SQLException
	{
		try (var statement = con.createStatement())
		{
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS l2hub_x10_wallet (char_id INT UNSIGNED NOT NULL PRIMARY KEY, coins BIGINT NOT NULL DEFAULT 0, style BIGINT NOT NULL DEFAULT 0, FOREIGN KEY(char_id) REFERENCES characters(charId) ON DELETE CASCADE) ENGINE=InnoDB");
			statement.executeUpdate("CREATE TABLE IF NOT EXISTS l2hub_x10_ledger (char_id INT UNSIGNED NOT NULL, source VARCHAR(160) NOT NULL, coins BIGINT NOT NULL, style BIGINT NOT NULL, created TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, PRIMARY KEY(char_id,source), FOREIGN KEY(char_id) REFERENCES l2hub_x10_wallet(char_id) ON DELETE CASCADE) ENGINE=InnoDB");
		}
	}

	private static void loadShop() throws Exception
	{
		final Path file = Path.of("./data/L2HubX10Shop.xml");
		final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		final Element root = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement();
		final long reference = Long.parseLong(root.getAttribute("referenceAdenaPerCoin"));
		final var rows = root.getElementsByTagName("item");
		for (int i = 0; i < rows.getLength(); i++)
		{
			final Element row = (Element) rows.item(i);
			final int id = Integer.parseInt(row.getAttribute("id"));
			final long adena = Long.parseLong(row.getAttribute("adena"));
			final long coins = Math.max(1, (adena + L2HubX10Config.COIN_ADENA - 1) / L2HubX10Config.COIN_ADENA);
			if ((ItemData.getInstance().getTemplate(id) == null) || (adena <= 0) || (reference <= 0) || SHOP.containsKey(id))
			{
				throw new IllegalArgumentException("Invalid X10 shop item " + id);
			}
			SHOP.put(id, new Goods(id, row.getAttribute("name"), row.getAttribute("category"), Integer.parseInt(row.getAttribute("level")), coins, 0));
		}
		X10Style.load(Path.of("./data/L2HubX10StyleShop.xml"));
		final var styles = factory.newDocumentBuilder().parse(Path.of("./data/L2HubX10StyleShop.xml").toFile()).getDocumentElement().getElementsByTagName("item");
		for (int i = 0; i < styles.getLength(); i++)
		{
			final Element row = (Element) styles.item(i);
			final int id = Integer.parseInt(row.getAttribute("id"));
			final long cost = Long.parseLong(row.getAttribute("style"));
			if (ItemData.getInstance().getTemplate(id) == null || cost <= 0 || SHOP.containsKey(id)) { throw new IllegalArgumentException("Invalid style shop item " + id); }
			SHOP.put(id, new Goods(id, text(row.getAttribute("ru"), row.getAttribute("en")), "style", 1, 0, cost));
		}
	}

	/** 1 — начислено; 0 — эта причина уже оплачена; -1 — ошибка, можно повторить. */
	static int credit(Player player, long coins, long style, String source)
	{
		if (!ready || (coins < 0) || (style < 0) || (source.length() > 160))
		{
			return -1;
		}
		final int result = change(player.getObjectId(), coins, style, source, false);
		if (result == 1) { syncInventory(player); if (!source.startsWith("refund:")) { X10PlayerTools.earned(player, coins, style); } }
		return result;
	}

	/** Предметы рюкзака отражают сохранённый кошелёк; повторный вход не создаёт вторую награду. */
	static void syncInventory(Player player)
	{
		if (org.l2jmobius.gameserver.managers.phantom.PhantomManager.getInstance().isPhantom(player)) { return; }
		synchronized (player)
		{
			final long[] amounts = balance(player);
			if (amounts == null) { return; }
			boolean changed = false;
			for (int n = 0; n < 2; n++)
			{
				final int id = n == 0 ? COIN_ITEM : STYLE_ITEM;
				final var item = player.getInventory().getItemByItemId(id);
				if (item == null && amounts[n] > 0) { PhantomCompat.addItem(player, ItemProcessType.REWARD, id, amounts[n], player); changed = true; }
				else if (item != null && item.getCount() != amounts[n])
				{
					if (amounts[n] == 0) { player.destroyItemByItemId(ItemProcessType.FEE, id, item.getCount(), player, false); }
					else { item.setCount(amounts[n]); item.updateDatabase(true); }
					changed = true;
				}
			}
			if (changed) { player.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.ItemList(player, false)); }
		}
	}

	static int change(int charId, long coins, long style, String source, boolean debit)
	{
		try (Connection con = DatabaseFactory.getConnection())
		{
			con.setAutoCommit(false);
			try
			{
				// Сначала исключительный замок кошелька; INSERT IGNORE оставлял общий замок
				// и приводил к взаимной блокировке одновременных списаний при UPDATE.
				try (PreparedStatement st = con.prepareStatement("INSERT INTO l2hub_x10_wallet(char_id) VALUES(?) ON DUPLICATE KEY UPDATE char_id=VALUES(char_id)"))
				{
					st.setInt(1, charId);
					st.executeUpdate();
				}
				try (PreparedStatement st = con.prepareStatement("INSERT IGNORE INTO l2hub_x10_ledger(char_id,source,coins,style) VALUES(?,?,?,?)"))
				{
					st.setInt(1, charId);
					st.setString(2, source);
					st.setLong(3, coins);
					st.setLong(4, style);
					if (st.executeUpdate() == 0)
					{
						con.rollback();
						return 0;
					}
				}
				final String sql = "UPDATE l2hub_x10_wallet SET coins=coins+?,style=style+? WHERE char_id=?" + (debit ? " AND coins>=? AND style>=?" : "");
				try (PreparedStatement st = con.prepareStatement(sql))
				{
					st.setLong(1, coins);
					st.setLong(2, style);
					st.setInt(3, charId);
					if (debit)
					{
						st.setLong(4, -coins);
						st.setLong(5, -style);
					}
					if ((st.executeUpdate() == 0) && debit)
					{
						con.rollback();
						return 0;
					}
				}
				con.commit();
				return 1;
			}
			catch (SQLException e)
			{
				con.rollback();
				throw e;
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "X10 wallet transaction failed for " + charId, e);
			return -1;
		}
	}

	static long[] balance(Player player)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement st = con.prepareStatement("SELECT coins,style FROM l2hub_x10_wallet WHERE char_id=?"))
		{
			st.setInt(1, player.getObjectId());
			try (ResultSet rs = st.executeQuery())
			{
				return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : new long[] {0, 0};
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "X10 balance unavailable", e);
			return null;
		}
	}

	static int dailyClaims(Player player, String week)
	{
		return claims(player, week, "daily:");
	}

	static int eventClaims(Player player, String week)
	{
		return claims(player, week, "event:");
	}

	private static int claims(Player player, String week, String prefix)
	{
		return claims(player.getObjectId(), week, prefix);
	}

	static int claims(int charId, String week, String prefix)
	{
		try (Connection con = DatabaseFactory.getConnection(); PreparedStatement st = con.prepareStatement("SELECT COUNT(*) FROM l2hub_x10_ledger WHERE char_id=? AND source>=? AND source<?"))
		{
			st.setInt(1, charId);
			st.setString(2, prefix + week);
			st.setString(3, prefix + java.time.LocalDate.parse(week).plusDays(7));
			try (ResultSet rs = st.executeQuery())
			{
				return rs.next() ? rs.getInt(1) : 0;
			}
		}
		catch (SQLException e)
		{
			LOGGER.log(Level.WARNING, "X10 daily history unavailable", e);
			return -1;
		}
	}

	static String balances(Player player)
	{
		final long[] wallet = balance(player);
		return wallet == null ? text("Валюты временно недоступны.", "Currency temporarily unavailable.") : "<table width=480><tr><td width=24><img src=\"icon.etc_coins_gold_i00\" width=24 height=24></td><td>" + text("Коины: ", "Coins: ") + wallet[0]
			+ "</td><td width=24><img src=\"icon.etc_event_medal_i00\" width=24 height=24></td><td>" + text("Жетоны стиля: ", "Style Tokens: ") + wallet[1] + "</td></tr></table>";
	}

	static String help()
	{
		return "<font color=\"LEVEL\">" + text("Коины", "Coins") + "</font><br1>"
			+ text("Добываются в игре: активное участие в ивентах, ежедневные и еженедельные поручения, небольшой шанс с подходящих обычных мобов. Тратятся здесь на S-снаряжение и расходники. Не покупаются за реальные деньги.", "Earned in game through active event participation, daily and weekly contracts, and a small chance from suitable regular monsters. Spend them here on S-grade equipment and supplies. They cannot be bought for real money.")
			+ "<br1>" + text("С обычного моба не ниже твоего уровня − 5: ", "From regular monsters at least your level minus 5: ") + (L2HubX10Config.DROP_CHANCE / 100.0) + text("% на одну монету. В пати одна попытка на моба, монету получает один подходящий участник.", "% chance of one coin. Parties have one roll per monster; one eligible member receives the coin.")
			+ "<br><font color=\"LEVEL\">" + text("Жетоны стиля", "Style Tokens") + "</font><br1>"
			+ text("За полноценное участие в ивентах. На вкладке стиля — маски и украшения без боевых характеристик.", "Earned through full event participation. The style shop offers masks and accessories without combat stats.")
			+ "<br><font color=\"AFAFAF\">" + text("Обе валюты в рюкзаке: золотой коин и медаль с лентой. Личные, не передаются и не продаются. Магазин имеет отдельные вкладки коинов и стиля. Обмена на адену нет.", "Both currencies appear in your inventory: a gold coin and a ribbon medal. Personal, untradeable and unsellable. The shop has separate coin and style tabs. No exchange for adena.") + "</font>";
	}

	/** Списки для окна обмена строятся из тех же цен, что и раньше; повторная регистрация после перезагрузки мультиселлов безопасна. */
	private static void registerLists()
	{
		for (int listId : LISTS)
		{
			final var list = new org.l2jmobius.gameserver.mechanics.multisell.ListContainer(listId);
			list.allowNpc(-1);
			int entryId = 1;
			for (Goods g : SHOP.values())
			{
				if (listId(g.category) != listId) { continue; }
				final var entry = new org.l2jmobius.gameserver.mechanics.multisell.Entry(entryId++);
				entry.addIngredient(new org.l2jmobius.gameserver.mechanics.multisell.Ingredient(g.style > 0 ? STYLE_ITEM : COIN_ITEM, g.style > 0 ? g.style : g.coins, 0, false, false));
				entry.addProduct(new org.l2jmobius.gameserver.mechanics.multisell.Ingredient(g.id, 1, 0, false, false));
				list.getEntries().add(entry);
			}
			org.l2jmobius.gameserver.data.xml.MultisellData.getInstance().registerList(list);
		}
	}

	private static int listId(String category)
	{
		return switch (category)
		{
			case "style" -> LIST_STYLE;
			case "supplies" -> LIST_SUPPLIES;
			case "enhance" -> LIST_ENHANCE;
			case "prestige" -> LIST_PRESTIGE;
			case "agathion" -> LIST_AGATHION;
			default -> LIST_GEAR;
		};
	}

	static boolean isShopList(int listId)
	{
		return java.util.Arrays.stream(LISTS).anyMatch(id -> id == listId);
	}

	/** Открыть штатное окно обмена нужного раздела. Списки регистрируются заново, если мультиселлы перезагружали. */
	static void open(Player player, String category)
	{
		if (!ready) { player.sendMessage(text("Магазин временно недоступен.", "The shop is temporarily unavailable.")); return; }
		final int id = listId(category);
		if (org.l2jmobius.gameserver.data.xml.MultisellData.getInstance().getList(id) == null) { registerLists(); }
		syncInventory(player);
		org.l2jmobius.gameserver.data.xml.MultisellData.getInstance().separateAndSendFromBoard(id, player);
	}

	/** Вход в магазин: штатные окна обмена вместо списка в Alt+B. */
	static String shop(Player player, String prefix)
	{
		final StringBuilder out = new StringBuilder(balances(player)).append("<br>");
		out.append("<table width=520 cellpadding=0 cellspacing=4>");
		shopRow(out, "icon.weapon_dynasty_ancient_sword_i01", text("S-снаряжение", "S-grade gear"), text("Оружие и броня S с 76-го уровня. Цена — коины.", "S-grade weapons and armour from level 76. Paid in Coins."), prefix + " shop open gear");
		shopRow(out, "icon.etc_scroll_of_enchant_weapon_i05", text("Расходники", "Supplies"), text("Свитки модификации S и руны опыта/SP. Цена — коины.", "S-grade enchant scrolls and XP/SP runes. Paid in Coins."), prefix + " shop open supplies");
		shopRow(out, "icon.etc_scroll_of_enchant_weapon_i04", text("Улучшения", "Upgrades"), text("Благословенные и кристальные свитки D–S, топовые камни жизни, камни и кристаллы атрибута. Сюда же переехали товары арены.", "Blessed and crystal enchant scrolls D–S, top-grade life stones, attribute stones and crystals. Arena goods live here too."), prefix + " shop open enhance");
		shopRow(out, "icon.weapon_dynasty_magic_sword_i01", text("Престиж", "Prestige"), text("Икар (S80), Веспер (S84) и эпическая бижутерия. Дорого, на вырост.", "Icarus (S80), Vesper (S84) and epic jewellery. Expensive, for the long run."), prefix + " shop open prestige");
		shopRow(out, "icon.etc_crystal_ball_gold_i00", text("Агатионы", "Agathions"), text("Спутники-питомцы с небольшими умениями. Носятся в слоте браслета.", "Companion pets with small skills. Worn in the bracelet slot."), prefix + " shop open agathion");
		shopRow(out, "icon.etc_event_medal_i00", text("Украшения стиля", "Style accessories"), text("Маски и украшения без боевых характеристик. Цена — жетоны стиля.", "Masks and accessories without combat stats. Paid in Style Tokens."), prefix + " shop open style");
		out.append("</table><font color=\"AFAFAF\">").append(text("«Открыть» — обычное окно обмена, как в GM-шопе: выбери товар и нажми «Обменять».", "Open shows the regular exchange window, like the GM shop: pick an item and press Exchange.")).append("</font><br><br>");
		out.append(X10Style.services(player, prefix));
		return out.toString();
	}

	private static void shopRow(StringBuilder out, String icon, String title, String about, String bypass)
	{
		out.append("<tr><td width=36 valign=top><img src=\"").append(icon).append("\" width=32 height=32></td><td width=300 valign=top><font color=\"LEVEL\">").append(title).append("</font><br1><font color=\"AFAFAF\">").append(about)
			.append("</font></td><td width=170 align=right valign=top>").append(X10Text.button(text("Открыть", "Open"), bypass, 140)).append("</td></tr><tr><td height=6></td></tr>");
	}

	static String buy(Player player, int itemId)
	{
		return buy(player, itemId, 1);
	}

	static String buy(Player player, int itemId, long amount)
	{
		final Goods goods = SHOP.get(itemId);
		if (!ready || (goods == null) || (amount < 1) || (amount > 10000))
		{
			return text("Покупка недоступна.", "This purchase is not available.");
		}
		if (player.getLevel() < goods.level)
		{
			return text("Этот товар доступен с уровня ", "This item is available from level ") + goods.level + ".";
		}
		final var template = ItemData.getInstance().getTemplate(itemId);
		if (player.isAlikeDead() || player.isInStoreMode() || !player.getInventory().validateCapacity(template.isStackable() ? (player.getInventory().getItemByItemId(itemId) == null ? 1 : 0) : amount) || !player.getInventory().validateWeight(template.getWeight() * amount))
		{
			return text("Сначала освободи место и вес в рюкзаке, закрой лавку и воскресни.", "Free inventory slots and weight, close your store and revive first.");
		}
		final long coins = goods.coins * amount;
		final long style = goods.style * amount;
		final String source = "buy:" + itemId + "x" + amount + ":" + UUID.randomUUID();
		final int result = change(player.getObjectId(), -coins, -style, source, true);
		if (result != 1)
		{
			return result == 0 ? (style > 0 ? text("Не хватает жетонов стиля.", "Not enough Style Tokens.") : text("Не хватает коинов.", "Not enough Coins.")) : text("Покупка временно недоступна.", "Purchases temporarily unavailable.");
		}
		try
		{
			if (template.isStackable())
			{
				final var item = PhantomCompat.addItem(player, ItemProcessType.REWARD, itemId, amount, player);
				if (item == null) { throw new IllegalStateException("Inventory rejected purchase"); }
				item.updateDatabase(true);
			}
			else
			{
				for (long i = 0; i < amount; i++)
				{
					final var item = PhantomCompat.addItem(player, ItemProcessType.REWARD, itemId, 1, player);
					if (item == null) { throw new IllegalStateException("Inventory rejected purchase"); }
					item.updateDatabase(true);
				}
			}
			syncInventory(player);
			return text("Покупка в рюкзаке: ", "Added to your inventory: ") + goods.name + (amount > 1 ? " ×" + amount : "");
		}
		catch (Exception e)
		{
			final int refunded = credit(player, coins, style, "refund:" + source);
			LOGGER.log(Level.SEVERE, "X10 delivery failed: " + source, e);
			if (refunded >= 0)
			{
				return text("Предмет не выдан; выполнен возврат монет. Обнови кошелёк.", "Item delivery failed; coins were refunded. Refresh your wallet.");
			}
			player.getVariables().set("L2HubX10PendingRewards", player.getVariables().getString("L2HubX10PendingRewards", "") + "refund:" + source + "," + coins + "," + style + "|");
			player.getVariables().storeMe();
			return text("Предмет не выдан. Возврат сохранён и будет повторён автоматически.", "Item delivery failed. The refund is saved and will be retried automatically.");
		}
	}

	private X10Economy()
	{
	}
}
