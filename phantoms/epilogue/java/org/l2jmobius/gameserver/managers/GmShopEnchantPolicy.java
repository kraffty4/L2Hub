package org.l2jmobius.gameserver.managers;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.Set;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Node;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.data.xml.EnchantItemData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enchant.EnchantScroll;
import org.l2jmobius.gameserver.entity.item.type.EtcItemType;
import org.l2jmobius.gameserver.mechanics.buylist.BuyListHolder;
import org.l2jmobius.gameserver.mechanics.buylist.Product;
import org.l2jmobius.gameserver.mechanics.multisell.Entry;
import org.l2jmobius.gameserver.mechanics.multisell.ListContainer;
import org.l2jmobius.gameserver.mechanics.multisell.PreparedListContainer;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;

/** Ассортимент GM-шопа: видимость и повторная проверка покупки используют одно правило. */
public final class GmShopEnchantPolicy
{
	public enum Kind
	{
		NOT_SCROLL, NORMAL, BLESSED, CRYSTAL, ENHANCED, ANCIENT, DIVINE, SPECIAL
	}

	private static final Set<EtcItemType> SCROLL_TYPES = EnumSet.of(
		EtcItemType.SCRL_ENCHANT_AM, EtcItemType.SCRL_ENCHANT_WP,
		EtcItemType.BLESS_SCRL_ENCHANT_AM, EtcItemType.BLESS_SCRL_ENCHANT_WP,
		EtcItemType.ANCIENT_CRYSTAL_ENCHANT_AM, EtcItemType.ANCIENT_CRYSTAL_ENCHANT_WP);

	// Прежние20 shop-bans, перенесённые из local-plugin. Одна неизменяемая таблица для Native и адаптера.
	private static final Set<Integer> LEGACY_BANNED = Set.of(
		22000, 22001, 22002, 22003, 22004, 22005, 20515, 20516,
		22014, 22015, 22016, 22017, 22018, 22019, 22020, 22021, 20519, 20520, 20521, 20522);

	private GmShopEnchantPolicy()
	{
	}

	/** Параметры обычного Native INI применяются при следующем запуске сервера. */
	private static final class Settings
	{
		private static final ConfigReader CONFIG = new ConfigReader("./config/Custom/GmShopEnchantScrolls.ini");
		private static final boolean ENABLED = CONFIG.getBoolean("GmShopEnchantPolicyEnabled", true);
		private static final boolean CRYSTAL = CONFIG.getBoolean("GmShopAllowCrystalScrolls", false);
		private static final boolean ENHANCED = CONFIG.getBoolean("GmShopAllowEnhancedScrolls", false);
		private static final boolean ANCIENT = CONFIG.getBoolean("GmShopAllowAncientScrolls", false);
		private static final boolean DIVINE = CONFIG.getBoolean("GmShopAllowDivineScrolls", false);
		private static final boolean SPECIAL = CONFIG.getBoolean("GmShopAllowSpecialScrolls", false);
	}

	/** Классификация по действующему Native template, без нового списка запрещённых ID. */
	public static Kind kind(int itemId)
	{
		if (itemId <= 0)
		{
			return Kind.NOT_SCROLL;
		}
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		if (item == null)
		{
			return Kind.SPECIAL;
		}
		if (!SCROLL_TYPES.contains(item.getItemType()))
		{
			return Kind.NOT_SCROLL;
		}
		return kind(item, EnchantItemData.getInstance().getEnchantScroll(itemId));
	}

	static Kind kind(ItemTemplate item, EnchantScroll scroll)
	{
		if ((item == null) || !SCROLL_TYPES.contains(item.getItemType()))
		{
			return Kind.NOT_SCROLL;
		}
		if ((scroll == null) || !scroll.getItems().isEmpty())
		{
			return Kind.SPECIAL;
		}
		if ((item.getItemType() == EtcItemType.ANCIENT_CRYSTAL_ENCHANT_AM)
			|| (item.getItemType() == EtcItemType.ANCIENT_CRYSTAL_ENCHANT_WP))
		{
			return scroll.getBonusRate() >= 100 ? Kind.DIVINE : Kind.ANCIENT;
		}
		if (scroll.getBonusRate() >= 100)
		{
			return Kind.CRYSTAL;
		}
		if (scroll.getBonusRate() > 0)
		{
			return Kind.ENHANCED;
		}
		if (scroll.getBonusRate() < 0)
		{
			return Kind.SPECIAL;
		}
		return scroll.isBlessed() ? Kind.BLESSED : Kind.NORMAL;
	}

	public static boolean allows(int itemId)
	{
		if (!Settings.ENABLED)
		{
			return true;
		}
		return switch (kind(itemId))
		{
			case NOT_SCROLL, NORMAL, BLESSED -> true;
			case CRYSTAL -> Settings.CRYSTAL;
			case ENHANCED -> Settings.ENHANCED;
			case ANCIENT -> Settings.ANCIENT;
			case DIVINE -> Settings.DIVINE;
			case SPECIAL -> Settings.SPECIAL;
		};
	}

	private static boolean allows(Entry entry)
	{
		// Проверяем только выдаваемые предметы: использовать свиток как ингредиент не запрещаем.
		return entry.getProducts().stream().allMatch(product -> allows(product.getItemId()));
	}

	public static boolean applies(ListContainer list, Npc npc)
	{
		return (list != null) && (list.isNpcAllowed(CommunityBoardConfig.COMMUNITY_SHOP_NPC)
			|| ((list instanceof PreparedListContainer prepared) && prepared.isFromBoard())
			|| ((npc != null) && (npc.getId() == CommunityBoardConfig.COMMUNITY_SHOP_NPC)));
	}

	public static void filter(ListContainer list, Npc npc)
	{
		if (applies(list, npc))
		{
			list.getEntries().removeIf(entry -> !allows(entry));
		}
	}

	/** Даже старый или подделанный запрос не должен выдать скрытый товар. До оплаты. */
	public static boolean canExchange(PreparedListContainer list, Npc npc, Entry entry)
	{
		return !applies(list, npc) || allows(entry);
	}

	private static boolean applies(BuyListHolder list, Player buyer)
	{
		if ((list != null) && list.isNpcAllowed(CommunityBoardConfig.COMMUNITY_SHOP_NPC))
		{
			return true;
		}
		if (buyer == null)
		{
			return false;
		}
		if (buyer.getTarget() instanceof Npc target)
		{
			return target.getId() == CommunityBoardConfig.COMMUNITY_SHOP_NPC;
		}
		final Npc npc = buyer.getLastFolkNPC();
		return (npc != null) && (npc.getId() == CommunityBoardConfig.COMMUNITY_SHOP_NPC);
	}

	public static void filter(BuyListHolder list)
	{
		if (applies(list, null))
		{
			list.getProducts().removeIf(product -> !allows(product.getItemId()));
		}
	}

	public static Collection<Product> productsForViewer(BuyListHolder list, Player buyer)
	{
		if (!applies(list, buyer))
		{
			return list.getProducts();
		}
		final List<Product> result = new ArrayList<>();
		for (Product product : list.getProducts())
		{
			if (allows(product.getItemId()))
			{
				result.add(product);
			}
		}
		return result;
	}

	public static boolean canBuy(BuyListHolder list, Player buyer, int itemId)
	{
		return !applies(list, buyer) || allows(itemId);
	}

	public static void reject(Player player)
	{
		player.sendMessage("ru".equals(player.getLang()) ? "Этот вид свитков отключён в GM-шопе настройками сервера."
			: "This scroll type is disabled in the GM shop by the server settings.");
		player.sendPacket(ActionFailed.STATIC_PACKET);
	}

	public static Set<Integer> legacyBannedIds()
	{
		return LEGACY_BANNED;
	}

	/**
	 * До штатного parse удаляем только постоянные прежние запреты. Настройки заточек
	 * здесь не читаем: иначе одинаковый entryId означал бы разные товары при смене флагов.
	 * В GM-шопе остаются8Fortuna, вне GM — прежние20. Scroll-policy работает ПОСЛЕ parse.
	 */
	public static int filterLegacyDocument(Document document, File logical)
	{
		final String path = logical.toString().replace('\\', '/');
		final boolean multisell = path.contains("data/multisell/");
		if (!multisell && !path.contains("data/buylists/"))
		{
			return 0;
		}
		final boolean gm = isGmShopDocument(document, logical);
		final Set<Node> remove = new LinkedHashSet<>();
		final NodeList products = document.getElementsByTagName(multisell ? "production" : "item");
		for (int i = 0; i < products.getLength(); i++)
		{
			final Element item = (Element) products.item(i);
			final String raw = item.getAttribute("id");
			if (raw.isEmpty())
			{
				continue;
			}
			final int id = Integer.parseInt(raw);
			if (LEGACY_BANNED.contains(id) && (!gm || (kind(id) == Kind.NOT_SCROLL)))
			{
				remove.add(multisell ? item.getParentNode() : item);
			}
		}
		for (Node item : remove)
		{
			item.getParentNode().removeChild(item);
		}
		return remove.size();
	}

	/** Для переноса legacy SalePolicy: не снимать старый запрет иных магазинов и Fortuna-коробок. */
	public static boolean isGmShopDocument(Document document, File logical)
	{
		final String path = logical.toString().replace('\\', '/');
		if (!path.contains("data/multisell/") && !path.contains("data/buylists/"))
		{
			return false;
		}
		final NodeList owners = document.getElementsByTagName("npcs");
		for (int i = 0; i < owners.getLength(); i++)
		{
			final NodeList npcs = ((Element) owners.item(i)).getElementsByTagName("npc");
			for (int j = 0; j < npcs.getLength(); j++)
			{
				if (Integer.toString(CommunityBoardConfig.COMMUNITY_SHOP_NPC).equals(npcs.item(j).getTextContent().trim()))
				{
					return true;
				}
			}
		}
		return false;
	}
}
