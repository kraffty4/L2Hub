package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.data.xml.SubjugationGacha;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.PlayerPurgeHolder;
import org.l2jmobius.gameserver.entity.item.enums.ItemLocation;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.managers.MailManager;
import org.l2jmobius.gameserver.mechanics.events.EventDispatcher;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.item.OnItemPurgeReward;
import org.l2jmobius.gameserver.network.enums.MailType;
import org.l2jmobius.gameserver.network.holders.MailMessage;

/**
 * L2Hub, только Essence: охотники в Зачистке (Purge) — как живой игрок, без поблажек (docs/ESSENCE.md, «Зачистка с охотниками»).
 * <p>
 * Очки Зачистки Mobius начисляет фантому и так: скрипты {@code ai/others/Subjugation/*Purge.java} считают убийства любого игрока с Sayha's Grace,
 * 1 000 000 очков — ключ зоны. Остальное живой игрок делает в окнах клиента, а клиента у фантома нет. Раз в {@value #INTERVAL} мс:
 * <ul>
 * <li>охотник тратит ключи в гаче зоны — по 20 000 адены за попытку, те же шансы ({@code SubjugationGacha.xml}), что у окна клиента
 * ({@code RequestSubjugationGacha}); тратит только свободную адену, как на покупки ({@link PhantomHunterOutfit#buyBudget}): запас на сосы и телепорт остаётся;</li>
 * <li>фантом забирает из почты письма «Purge reward» (воскресная награда топ-5 зоны, {@code PurgeRankingManager}), как {@code RequestPostAttachment}:
 * только без наложенного платежа и если вещи влезают в сумку.</li>
 * </ul>
 * Выигрыш гачи — обычная добыча: охотник надевает или продаёт её, как всё прочее.
 */
public final class PhantomPurge
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPurge.class.getName());
	private static final long INTERVAL = 300000;
	/** Цена попытки в гаче — как в {@code RequestSubjugationGacha}. */
	private static final long GACHA_PRICE = 20000;

	private boolean _started;

	private PhantomPurge()
	{
	}

	/**
	 * Вызывается при старте сервера, после {@link PhantomManager#onServerStart}.
	 */
	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		LOGGER.info(getClass().getSimpleName() + ": Purge keys and rewards for phantoms, every " + (INTERVAL / 60000) + " min.");
		ThreadPool.scheduleAtFixedRate(this::run, INTERVAL, INTERVAL);
	}

	private void run()
	{
		try
		{
			for (Player player : World.getPlayers())
			{
				if (!PhantomManager.getInstance().isPhantom(player) || player.isDead())
				{
					continue;
				}
				claimRewards(player);
				if (PhantomHunterManager.getInstance().isHunter(player))
				{
					gacha(player);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": failed.", e);
		}
	}

	/**
	 * Ключи зон — в гачу, пока хватает свободной адены. Логика и шансы — {@code RequestSubjugationGacha}.
	 * @param player охотник
	 * @return сколько попыток сделал
	 */
	int gacha(Player player)
	{
		int total = 0;
		for (Entry<Integer, PlayerPurgeHolder> entry : new ArrayList<>(player.getPurgePoints().entrySet()))
		{
			final int category = entry.getKey();
			final PlayerPurgeHolder keys = entry.getValue();
			final Map<Integer, Double> data = SubjugationGacha.getInstance().getSubjugation(category);
			if ((keys == null) || (keys.getKeys() < 1) || (data == null) || data.isEmpty())
			{
				continue;
			}
			final int amount = (int) Math.min(keys.getKeys(), Math.max(0, (PhantomHunterOutfit.buyBudget(player) - 1) / GACHA_PRICE));
			if ((amount < 1) || (player.getInventory().getAdena() <= (GACHA_PRICE * amount)))
			{
				continue;
			}
			if (!player.reduceAdena(ItemProcessType.FEE, GACHA_PRICE * amount, player, false))
			{
				continue;
			}
			// Все ключи потрачены — как у окна клиента: remainingKeys 0, и PlayerPurgeHolder сам вернёт 40. Окно ставит 0 и при остатке ключей,
			// а при remainingKeys 0 getPoints() отдаёт 0 — очки зоны стоят, пока ключи не кончатся. Охотник, которому адены хватило не на все, этого не ловит.
			final int left = keys.getKeys() - amount;
			player.getPurgePoints().put(category, new PlayerPurgeHolder(keys.getPoints(), left, left == 0 ? 0 : keys.getRemainingKeys()));

			final int size = data.size();
			final double[] chances = new double[size];
			final int[] itemIds = new int[size];
			double maxBound = 0;
			int idx = 0;
			for (Entry<Integer, Double> item : data.entrySet())
			{
				chances[idx] = item.getValue();
				itemIds[idx] = item.getKey();
				maxBound += item.getValue();
				idx++;
			}
			final Map<Integer, Integer> rewards = new HashMap<>();
			for (int i = 0; i < amount; i++)
			{
				double rate = 0;
				for (int index = 0; index < size; index++)
				{
					if (Rnd.get(maxBound - rate) < chances[index])
					{
						rewards.merge(itemIds[index], 1, Integer::sum);
						final Item item = player.addItem(ItemProcessType.REWARD, itemIds[index], 1, player, false);
						if (EventDispatcher.getInstance().hasListener(EventType.ON_ITEM_PURGE_REWARD))
						{
							EventDispatcher.getInstance().notifyEventAsync(new OnItemPurgeReward(player, item));
						}
						break;
					}
					rate += chances[index];
				}
			}
			total += amount;
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") spent " + amount + " Purge keys of category " + category + " for " + (GACHA_PRICE * amount) + " adena: " + rewards + ".");
		}
		return total;
	}

	/**
	 * Письма «Purge reward» — в сумку. Проверки — как у {@code RequestPostAttachment}: вещи письма, место и вес в сумке, без наложенного платежа.
	 * @param player фантом
	 * @return сколько писем забрал
	 */
	int claimRewards(Player player)
	{
		if (!GeneralConfig.ALLOW_MAIL || !GeneralConfig.ALLOW_ATTACHMENTS || player.isInCombat() || (player.getActiveTradeList() != null) || player.isInStoreMode())
		{
			return 0;
		}
		int claimed = 0;
		final List<MailMessage> inbox = MailManager.getInstance().getInbox(player.getObjectId());
		for (MailMessage msg : inbox)
		{
			if ((msg.getMailType() != MailType.PURGE_REWARD) || !msg.hasAttachments() || (msg.getReqAdena() > 0))
			{
				continue;
			}
			final ItemContainer attachments = msg.getAttachments();
			if (attachments == null)
			{
				continue;
			}
			long weight = 0;
			int slots = 0;
			boolean valid = true;
			for (Item item : attachments.getItems())
			{
				if ((item.getOwnerId() != msg.getSenderId()) || (item.getItemLocation() != ItemLocation.MAIL) || (item.getLocationSlot() != msg.getId()))
				{
					valid = false;
					break;
				}
				weight += item.getCount() * item.getTemplate().getWeight();
				if (!item.isStackable())
				{
					slots += item.getCount();
				}
				else if (player.getInventory().getItemByItemId(item.getId()) == null)
				{
					slots++;
				}
			}
			if (!valid || !player.getInventory().validateCapacity(slots) || !player.getInventory().validateWeight(weight))
			{
				continue;
			}
			final StringBuilder got = new StringBuilder();
			for (Item item : attachments.getItems())
			{
				final long count = item.getCount();
				final int itemId = item.getId();
				if (attachments.transferItem(ItemProcessType.TRANSFER, item.getObjectId(), count, player.getInventory(), player, null) != null)
				{
					got.append(got.isEmpty() ? "" : ", ").append(itemId).append(" x").append(count);
				}
			}
			msg.removeAttachments();
			msg.markAsRead();
			claimed++;
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") took the Purge reward from the mail: " + got + ".");
		}
		return claimed;
	}

	public static PhantomPurge getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomPurge INSTANCE = new PhantomPurge();
	}
}
