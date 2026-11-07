package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.l2jmobius.commons.util.ConfigReader;
import org.l2jmobius.gameserver.data.xml.HennaData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.item.Henna;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;

/** Native, persistent symbols; personal kits are free, hunters pay their own item price plus drawing fee. */
public final class PhantomClassKits
{
    private static final Set<Long> LEGACY_UPGRADES = Set.of(304942682728L,
        309237649994L,
        317827584591L,
        335007453813L,
        343597389316L,
        382252094072L,
        390842028684L,
        395136998954L,
        403726930667L,
        420906800634L,
        562640720468L,
        571230655061L,
        609885360747L,
        687194772091L,
        691489739390L,
        743029346944L,
        747324315817L,
        820338759849L,
        824633727145L,
        828928694441L,
        833223661737L,
        837518629033L,
        889058237994L,
        893353205290L,
        897648172586L,
        910533072395L,
        919123007952L,
        983547515562L,
        996432417434L,
        1009317320174L,
        1155346208245L,
        1206885814986L,
        1211180782285L,
        1288490193656L,
        1297080128232L,
        6317896892641L,
        16909286244649L,
        17300128268513L,
        18180596568768L,
        21058224657931L,
        21062519625227L,
        21066814592523L,
        22020297326817L,
        22028887263732L,
        22033182228705L,
        22037477196001L,
        27333171878326L,
        27337466845625L,
        27341761812944L,
        27346056780224L,
        27358941682121L,
        27363236649418L,
        27367531616717L,
        28256589846992L,
        32534377274777L,
        33861522169772L,
        33895881907754L,
        33904471840267L,
        33908766807563L,
        39651138085906L,
        39668317955093L,
        39754217301039L,
        39977555600479L,
        40080634815605L,
        40235253638297L,
        40252433507486L,
        40286793245861L,
        40303973115051L,
        40553081218687L,
        40557376185985L,
        40565966120585L,
        40570261087882L,
        40574556055182L,
        40578851022481L,
        40583145989781L,
        40587440957079L,
        44032004728469L,
        47699906800483L,
        48245367642576L,
        48348446862331L,
        57797374916391L,
        57801669883689L,
        57810259818289L,
        57814554785586L,
        57818849752887L,
        57823144720185L,
        57831734654782L,
        57836029622078L,
        57840324589381L,
        57848914523979L,
        57853209491277L,
        60760902350654L);

    private static final class Settings
    {
        static final ConfigReader C = new ConfigReader("config/Custom/BotClassKits.ini");
        static final boolean ENABLED = C.getBoolean("BotClassDyesEnabled", true);
        static final int[] FIGHTER = C.getIntArray("BotFighterDyes", ",", "169,174,109");
        static final int[] TANK = C.getIntArray("BotTankDyes", ",", "171,173,111");
        static final int[] MAGE = C.getIntArray("BotMageDyes", ",", "175,180,171");
        static final int[] SUPPORT = C.getIntArray("BotSupportDyes", ",", "180,171,173");
    }

    private PhantomClassKits() { }

    static int[] dyePlan(PlayerClass cls)
    {
        if ((cls == null) || (cls.level() < 2)) return new int[0];
        int[] plan = switch (cls.getId())
        {
            case 5, 6, 20, 33, 90, 91, 99, 106 -> Settings.TANK;
            case 14, 16, 17, 28, 30, 41, 43, 51, 52, 96, 97, 98, 104, 105, 111, 112, 115, 116 -> Settings.SUPPORT;
            default -> cls.isMage() ? Settings.MAGE : Settings.FIGHTER;
        };
        return Arrays.stream(plan).distinct().filter(id -> {
            Henna henna = HennaData.getInstance().getHenna(id);
            return (henna != null) && henna.isAllowedClass(cls);
        }).limit(3).toArray();
    }

    static long dyeCost(Henna henna)
    {
        ItemTemplate dye = ItemData.getInstance().getTemplate(henna.getDyeItemId());
        if ((dye == null) || (HunterShopPrices.buyPrice(dye) <= 0) || (henna.getWearCount() <= 0)) return -1;
        return ((long) henna.getWearCount() * HunterShopPrices.buyPrice(dye)) + Math.max(0, henna.getWearFee());
    }

    /** Fill free slots only. Existing symbols, subclasses and a human owner's adena are never changed. */
    static List<String> applyDyes(Player player, boolean personal, long budget)
    {
        List<String> applied = new ArrayList<>();
        if (!Settings.ENABLED || (player.getLevel() < 40) || player.isDead() || player.isInCombat()
            || player.isInOlympiadMode() || player.isOnEvent() || player.isInDuel()) return applied;
        synchronized (player)
        {
            for (int id : dyePlan(player.getPlayerClass()))
            {
                if (player.getHennaEmptySlots() <= 0) break;
                if (Arrays.stream(player.getHennaList()).anyMatch(h -> (h != null) && (h.getDyeId() == id))) continue;
                Henna henna = HennaData.getInstance().getHenna(id);
                long cost = personal ? 0 : dyeCost(henna);
                if ((cost < 0) || (!personal && ((cost > budget) || (cost > player.getAdena())))) break;
                if (!personal && !PhantomCompat.reduceAdena(player, ItemProcessType.BUY, cost, player, false)) break;
                if (player.addHenna(henna))
                {
                    budget -= cost;
                    applied.add("нанёс тату: " + ItemData.getInstance().getTemplate(henna.getDyeItemId()).getName());
                }
                else
                {
                    if (!personal) PhantomCompat.addAdena(player, ItemProcessType.REFUND, cost, player, false);
                    break;
                }
            }
        }
        return applied;
    }

    static boolean legacyUpgrade(int current, int wanted)
    {
        return LEGACY_UPGRADES.contains(((long) current << 32) | (wanted & 0xffffffffL));
    }

    /** The old grade-only comparison prevented buying an SA version at the same grade. Keep invested items. */
    static boolean sameGradeUpgrade(Item current, ItemTemplate wanted)
    {
        return (current != null) && (wanted != null) && current.isWeapon() && wanted.isWeapon()
            && (current.getTemplate().getCrystalType() == wanted.getCrystalType())
            && (current.getEnchantLevel() == 0) && !current.isAugmented() && (current.getElementals() == null)
            && legacyUpgrade(current.getId(), wanted.getId());
    }

    /** On recall, existing personal actors also receive the revised weapon, retaining enchant and element. */
    static void personal(Player player, PhantomTemplate template)
    {
        if (!PersonalCompanions.personal(template)) return;
        PhantomTemplate.Gear gear = template.getGear(player.getLevel());
        Item current = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
        if ((gear != null) && (current != null) && !current.isAugmented() && legacyUpgrade(current.getId(), gear.rhand))
        {
            ItemTemplate target = ItemData.getInstance().getTemplate(gear.rhand);
            if ((target != null) && (current.getTemplate().getCrystalType() == target.getCrystalType())
                && (target.getCrystalType().getLevel() <= PhantomCompat.expertiseLevel(player)))
            {
                Item replacement = player.getInventory().addItem(ItemProcessType.REWARD, gear.rhand, 1, player, null);
                if ((replacement != null) && replacement.isEquipable())
                {
                    replacement.setEnchantLevel(current.getEnchantLevel());
                    if (current.getElementals() != null)
                        for (var element : current.getElementals()) replacement.setElementAttr(element.getElement(), element.getValue());
                    player.getInventory().equipItem(replacement);
                    if (player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND) == replacement)
                        player.getInventory().destroyItem(ItemProcessType.DESTROY, current, player, null);
                    else
                        player.getInventory().destroyItem(ItemProcessType.DESTROY, replacement, player, null);
                    // Convert once; transferring enchant/element must not duplicate an invested weapon.
                    PhantomCompat.refreshExpertisePenalty(player);
                    PhantomCompat.rechargeShots(player);
                    player.broadcastUserInfo();
                }
            }
        }
        applyDyes(player, true, 0);
    }
}
