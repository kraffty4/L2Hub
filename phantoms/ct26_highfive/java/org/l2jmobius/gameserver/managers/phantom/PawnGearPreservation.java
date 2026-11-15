package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/** Keep actual invested instances. Does not copy upgrades, create items or alter payments. */
public final class PawnGearPreservation
{
    private PawnGearPreservation() {}
    private record Kept(Item item, boolean worn, long count) {}

    /** Positive factory enchant needs provenance; never guess paid versus factory gear. */
    static void requirePlainTemplate(ItemTemplate template)
    {
        if (template != null && template.isEquipable() && template.getDefaultEnchantLevel() != 0)
            throw new IllegalStateException("Nonzero factory enchant requires kit provenance before destructive rebuild");
    }

    static void requirePlainKit(PhantomTemplate.Gear gear)
    {
        if (gear == null) return;
        for (int itemId : gear.items())
            if (itemId > 0) requirePlainTemplate(ItemData.getInstance().getTemplate(itemId));
    }

    static boolean invested(Item item)
    {
        if (item == null || !item.isEquipable()) return false;
        if (item.getEnchantLevel() > 0 || item.isAugmented()) return true;
        if (item.getElementals() != null)
            for (var element : item.getElementals()) if (element != null && element.getValue() > 0) return true;
        return false;
    }

    private static void validate(Player player, Inventory inventory, Item item)
    {
        if (item.getObjectId() <= 0 || item.getOwnerId() != player.getObjectId() || item.getCount() <= 0
            || inventory.getItemByObjectId(item.getObjectId()) != item)
            throw new IllegalStateException("Inconsistent inventory item before outfit rebuild");
    }

    static Saved reset(Player player)
    {
        Inventory inventory = player.getInventory();
        List<Item> before = new ArrayList<>(inventory.getItems());
        Set<Integer> ids = new HashSet<>();
        for (Item item : before)
        {
            validate(player, inventory, item);
            requirePlainTemplate(item.getTemplate());
            if (!ids.add(item.getObjectId())) throw new IllegalStateException("Duplicate inventory objectId before outfit rebuild");
        }
        List<Kept> kept = new ArrayList<>();
        Set<Item> retained = Collections.newSetFromMap(new IdentityHashMap<>());
        // Unequip before level/class/skills are rebuilt, so old augmentation skills do not remain active.
        for (Item item : before) if (invested(item)) keep(player, inventory, item, kept, retained);
        for (Item item : before)
        {
            if (retained.contains(item)) continue;
            // Recheck immediately before native destruction; an observed late upgrade is retained too.
            if (invested(item)) { keep(player, inventory, item, kept, retained); continue; }
            if (inventory.destroyItem(ItemProcessType.DESTROY, item, player, null) == null)
                throw new IllegalStateException("Native inventory destruction rejected; no replacement kit granted");
        }
        for (Item item : inventory.getItems())
            if (!retained.contains(item)) throw new IllegalStateException("Inventory changed during outfit rebuild");
        Saved result = new Saved(player, List.copyOf(kept));
        result.verify(inventory);
        return result;
    }

    private static void keep(Player player, Inventory inventory, Item item, List<Kept> kept, Set<Item> retained)
    {
        validate(player, inventory, item);
        boolean worn = item.isEquipped();
        if (worn)
        {
            int slot = item.getLocationSlot();
            if (inventory.getPaperdollItem(slot) != item) throw new IllegalStateException("Paperdoll identity mismatch before outfit rebuild");
            inventory.unEquipItemInSlot(slot);
            if (item.isEquipped()) throw new IllegalStateException("Invested item could not be unequipped");
        }
        retained.add(item);kept.add(new Kept(item, worn, item.getCount()));
    }

    static final class Saved
    {
        private final Player owner;
        private final List<Kept> kept;
        private final Set<Item> used = Collections.newSetFromMap(new IdentityHashMap<>());
        private Saved(Player owner, List<Kept> kept) { this.owner = owner; this.kept = kept; }
        boolean isEmpty() { return kept.isEmpty(); }
        void verify(Inventory inventory)
        {
            for (Kept entry : kept)
            {
                validate(owner, inventory, entry.item());
                if (entry.item().getCount() != entry.count()) throw new IllegalStateException("Invested item count changed during outfit rebuild");
            }
        }
        Item takeMatching(Inventory inventory, int itemId)
        {
            verify(inventory);
            for (Kept entry : kept)
                if (entry.worn() && entry.item().getId() == itemId && used.add(entry.item())) return entry.item();
            return null;
        }
    }
}
