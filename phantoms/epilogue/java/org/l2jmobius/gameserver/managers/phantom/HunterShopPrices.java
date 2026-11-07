package org.l2jmobius.gameserver.managers.phantom;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.OptionalLong;

import org.l2jmobius.gameserver.config.custom.CommunityBoardConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.mechanics.multisell.ListContainer;

/** Purchase prices come from the actual, already overlaid/filtered player shop catalogs. NPC resale is unchanged. */
public final class HunterShopPrices
{
    private record Catalog(Map<Integer, Long> prices, Set<Integer> listed) { }
    private static volatile Catalog catalog;
    private HunterShopPrices() { }

    /** Called once after native multisell loading/verification, also on administrative reload. */
    public static void loaded(Collection<ListContainer> lists)
    {
        int npc = CommunityBoardConfig.COMMUNITY_SHOP_NPC > 0 ? CommunityBoardConfig.COMMUNITY_SHOP_NPC : 50010;
        Map<Integer, Long> prices = new HashMap<>();
        Set<Integer> listed = new HashSet<>();
        long coinRate = reversibleCoinRate(lists, npc);
        for (ListContainer list : lists)
        {
            if (!list.isNpcAllowed(npc)) continue;
            for (var entry : list.getEntries())
            {
                for (var output : entry.getProducts()) if (output.getItemId() > 0) listed.add(output.getItemId());
                if ((entry.getIngredients().size() != 1) || (entry.getProducts().size() != 1)) continue;
                var input = entry.getIngredients().getFirst();
                var output = entry.getProducts().getFirst();
                if ((input.getItemId() != 57 && (input.getItemId() != 4037 || coinRate <= 0)) || (input.getItemCount() < 0) || (output.getItemCount() <= 0) || (output.getItemId() == 57)) continue;
                long units = output.getItemCount(), total = input.getItemCount();
                // The native Adena balance is long and the Hub allows balances above2b.
                // Charge exactly the shop's reversible CoL value, never the old reference price.
                if (input.getItemId() == 4037)
                {
                    try { total = Math.multiplyExact(total, coinRate); }
                    catch (ArithmeticException e) { continue; }
                }
                long perUnit = total / units + (total % units == 0 ? 0 : 1);
                prices.merge(output.getItemId(), perUnit, Math::min);
            }
        }
        catalog = new Catalog(Map.copyOf(prices), Set.copyOf(listed));
        PhantomHunterOutfit.shopCatalogChanged();
    }

    /** Only the actual two-way CoL/Adena exchange makes a token quote payable in Adena. */
    private static long reversibleCoinRate(Collection<ListContainer> lists, int npc)
    {
        long purchase = 0, redemption = 0;
        for (ListContainer list : lists)
        {
            if (!list.isNpcAllowed(npc)) continue;
            for (var entry : list.getEntries())
            {
                if (entry.getIngredients().size() != 1 || entry.getProducts().size() != 1) continue;
                var in = entry.getIngredients().getFirst();
                var out = entry.getProducts().getFirst();
                if (in.getItemCount() <= 0 || out.getItemCount() <= 0) continue;
                if (in.getItemId() == 57 && out.getItemId() == 4037)
                {
                    if (in.getItemCount() % out.getItemCount() != 0) return 0;
                    long rate = in.getItemCount() / out.getItemCount();
                    if (purchase != 0 && purchase != rate) return 0;
                    purchase = rate;
                }
                else if (in.getItemId() == 4037 && out.getItemId() == 57)
                {
                    if (out.getItemCount() % in.getItemCount() != 0) return 0;
                    long rate = out.getItemCount() / in.getItemCount();
                    if (redemption != 0 && redemption != rate) return 0;
                    redemption = rate;
                }
            }
        }
        return purchase > 0 && purchase == redemption ? purchase : 0;
    }

    private static Catalog current()
    {
        if (catalog == null) MultisellData.getInstance();
        Catalog result = catalog;
        if (result == null) throw new IllegalStateException("Player shop catalogs have not finished loading");
        return result;
    }
    static OptionalLong shopPrice(int id)
    {
        Long price = current().prices().get(id);
        return price == null ? OptionalLong.empty() : OptionalLong.of(price);
    }
    static boolean listed(int id) { return current().listed().contains(id); }
    static Map<Integer, Long> shopGoods() { return current().prices(); }

    /** No privileged old-price purchase for a product offered only for tokens or barter. */
    static long buyPrice(ItemTemplate item)
    {
        if (item == null) return 0;
        Catalog c = current();
        Long price = c.prices().get(item.getId());
        if (price != null) return price;
        return c.listed().contains(item.getId()) ? 0 : Math.max(0, item.getReferencePrice());
    }
    static long buyPrice(int id) { return buyPrice(id <= 0 ? null : ItemData.getInstance().getTemplate(id)); }
}
