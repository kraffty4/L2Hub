package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.DailyMissionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.DailyMissionStatus;
import org.l2jmobius.gameserver.entity.actor.holders.player.DailyMissionDataHolder;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.entity.item.instance.Item;

/**
 * L2Hub, только Essence: фантомы забирают награды заданий дня и меняют талоны на сосы, как живой игрок (docs/ESSENCE.md, «Экономика охотников»).
 * <p>
 * У Essence сосы игроку даёт не адена, а задания дня: «Daily Hunting» — 1000 убийств мобов 20–75-го уровня за день (2000 — с 76-го) —
 * 35–40 Soulshot Ticket, 35–40 HP Potion и Spirit Ore. Талон у Dimensional Merchant (мультиселл 3247801) — 500 Soulshot или 134 Blessed Spiritshot.
 * Убийства фантомов Mobius и так засчитывает в задания (они игроки), но награду выдаёт только окно клиента, а клиента у фантома нет.
 * Раз в {@value #INTERVAL} мс: фантомы от {@value #MIN_LEVEL}-го уровня забирают готовые задания, в награде которых есть талоны, и меняют
 * талоны на сосы своего вида ({@link PhantomCompat#gradelessShot}). Прочие задания не проверяются: у Mobius пустая запись задания — запрос к базе.
 */
public final class PhantomDailyMissions
{
	private static final Logger LOGGER = Logger.getLogger(PhantomDailyMissions.class.getName());
	private static final long INTERVAL = 300000;
	/** Ниже 20-го задания с талонами не копятся: «Daily Hunting» считает мобов от 20-го уровня. */
	private static final int MIN_LEVEL = 20;
	/** Soulshot Ticket - Sealed. */
	private static final int SOULSHOT_TICKET = 90907;
	/** Сколько сосов за талон у Dimensional Merchant: Soulshot и Blessed Spiritshot. */
	private static final int TICKET_SOULSHOTS = 500;
	private static final int TICKET_SPIRITSHOTS = 134;

	private final List<DailyMissionDataHolder> _missions = new ArrayList<>();
	private boolean _started;

	private PhantomDailyMissions()
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
		for (DailyMissionDataHolder mission : DailyMissionData.getInstance().getDailyMissionData())
		{
			for (ItemHolder reward : mission.getRewards())
			{
				if (reward.getId() == SOULSHOT_TICKET)
				{
					_missions.add(mission);
					break;
				}
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": " + _missions.size() + " daily missions with Soulshot Tickets for phantoms.");
		ThreadPool.scheduleAtFixedRate(this::run, INTERVAL, INTERVAL);
	}

	private void run()
	{
		try
		{
			for (Player player : World.getPlayers())
			{
				if (PhantomManager.getInstance().isPhantom(player) && (player.getLevel() >= MIN_LEVEL))
				{
					claim(player);
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": failed.", e);
		}
	}

	/**
	 * Забрать готовые задания с талонами и поменять талоны на сосы.
	 * @param player фантом
	 * @return сколько заданий забрал
	 */
	int claim(Player player)
	{
		int claimed = 0;
		for (DailyMissionDataHolder mission : _missions)
		{
			if (!mission.isDisplayable(player))
			{
				continue;
			}
			final int before = mission.getStatus(player);
			mission.requestReward(player);
			// Набранное, но не отмеченное готовым, Mobius отмечает при первом запросе, а награду даёт при втором — как окно клиента: список, потом «получить».
			if (mission.getStatus(player) == DailyMissionStatus.AVAILABLE.getClientId())
			{
				mission.requestReward(player);
			}
			if ((before != DailyMissionStatus.COMPLETED.getClientId()) && (mission.getStatus(player) == DailyMissionStatus.COMPLETED.getClientId()))
			{
				claimed++;
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") claimed the daily mission " + mission.getId() + ".");
			}
		}

		final Item tickets = player.getInventory().getItemByItemId(SOULSHOT_TICKET);
		if ((tickets != null) && (tickets.getCount() > 0))
		{
			final long count = tickets.getCount();
			final boolean mage = player.isMageClass();
			final int shot = PhantomCompat.gradelessShot(mage);
			final long shots = count * (mage ? TICKET_SPIRITSHOTS : TICKET_SOULSHOTS);
			if (player.destroyItemByItemId(ItemProcessType.FEE, SOULSHOT_TICKET, count, player, false))
			{
				player.addItem(ItemProcessType.REWARD, shot, shots, player, false);
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " exchanged " + count + " Soulshot Tickets for " + shots + (mage ? " Blessed Spiritshots." : " Soulshots."));
			}
		}
		return claimed;
	}

	public static PhantomDailyMissions getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomDailyMissions INSTANCE = new PhantomDailyMissions();
	}
}
