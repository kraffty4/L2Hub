package org.l2jmobius.gameserver.managers.phantom;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: /trade с охотником (docs/PHANTOMS.md, раздел 4.70; {@code HunterTrade} в PhantomHunters.ini).
 * <ul>
 * <li>запрос — штатный {@code TradeRequest}, врезка в его конце: окна у фантома нет, отвечает сервер через пару секунд. Отказ — в бою, на ивенте, в рейде, в лавке,
 * врагу; незнакомому — шанс {@value #STRANGER_CHANCE} %;</li>
 * <li>окно — настоящее ({@code Player.startTrade}); охотник стоит на месте ({@link #holds}, из {@code PhantomHunterManager.think}), окно опрашивается раз в
 * {@value #STEP} мс: пакеты {@code AddTradeItem} и {@code TradeDone} не тронуты;</li>
 * <li>цена — игрок {@value #OFFER_DELAY} мс ничего не докладывает: охотник кладёт адену за всё сразу, {@link PhantomMarket#buyFactor} × цена магазина, но не больше
 * {@value #PURSE} % кошелька. Вторую адену в окно положить нельзя ({@code TradeList.addItem} откажет), поэтому доложил после цены — охотник закрывает окно, пусть кладёт заново. Цена выше, чем у
 * NPC (половина цены магазина), и ниже магазинной: перепродать ему купленное в магазине нельзя;</li>
 * <li>подтверждение — игрок нажал ОК: цены ещё нет — охотник кладёт её, затем подтверждает, сделку ведёт Mobius ({@code TradeList.confirm}). Адена от игрока без вещей — подарок,
 * {@link PhantomRelations.Kind#GIFTED};</li>
 * <li>конец — игрок закрыл окно или отошёл, охотника бьют, {@value #IDLE} мс без дела — охотник закрывает и возвращается к своему.</li>
 * </ul>
 * Вещей охотник не продаёт: у него нет понятия, что живому нужно.
 */
public final class PhantomTrade
{
	private static final Logger LOGGER = Logger.getLogger(PhantomTrade.class.getName());

	/** Через столько отвечает на запрос, мс. */
	private static final int ANSWER_MIN = 1500;
	private static final int ANSWER_MAX = 4000;
	/** Шанс, что незнакомый согласится, %. */
	private static final int STRANGER_CHANCE = 80;
	/** Шаг опроса окна. */
	private static final long STEP = 1000;
	/** Сколько ждать, пока игрок докладывает вещи, прежде чем назвать цену. */
	private static final long OFFER_DELAY = 4000;
	/** Окно без движения столько — закрыть. */
	private static final long IDLE = 90000;
	/** На вещи живого — не больше такой доли кошелька, %: остальное на телепорт и банки. */
	private static final int PURSE = 80;
	/** Платит меньше такой доли от своей цены — считает, что денег нет. */
	private static final double MIN_PAY = 0.1;
	/** Обмен возможен в этом радиусе (как в {@code TradeRequest}). */
	private static final int RANGE = 150;
	/** Реплика об обмене тому же игроку — не чаще. */
	private static final long SAY_GAP = 5000;

	private static class Deal
	{
		final Player player;
		final Player hunter;
		final PhantomTemplate template;
		volatile ScheduledFuture<?> task;
		long lastChange = System.currentTimeMillis();
		/** Что игрок положил при прошлом шаге: сколько строк и сколько всего. */
		int seenLines;
		long seenCount;
		/** Адена охотника уже в окне. */
		long paid;
		boolean priced;

		Deal(Player player, Player hunter, PhantomTemplate template)
		{
			this.player = player;
			this.hunter = hunter;
			this.template = template;
		}
	}

	/** objectId охотника → обмен: с запроса до закрытия окна. */
	private final Map<Integer, Deal> _deals = new ConcurrentHashMap<>();

	private PhantomTrade()
	{
	}

	/**
	 * Из {@code TradeRequest}: запрос уже отправлен штатно ({@code onTransactionRequest}, «вы предложили обмен»), ответа ждём здесь.
	 * @param player кто зовёт
	 * @param phantom кого зовут
	 */
	public void onRequest(Player player, Player phantom)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		final String no = refusal(player, phantom, template);
		if (no == null)
		{
			_deals.put(phantom.getObjectId(), new Deal(player, phantom, template));
			stand(phantom);
		}
		ThreadPool.schedule(() -> answer(player, phantom, no), Rnd.get(ANSWER_MIN, ANSWER_MAX));
	}

	/**
	 * @return почему отказ: что сказать мозгам, пустая строка — молча; {@code null} — согласен
	 */
	private static String refusal(Player player, Player phantom, PhantomTemplate template)
	{
		if (!PhantomHuntersConfig.HUNTER_TRADE || (template == null) || !template.isHunter())
		{
			return "";
		}
		final String who = "Игрок " + player.getName() + " зовёт тебя в обмен, ";
		if (PhantomRelations.getInstance().isEnemy(phantom, player))
		{
			return who + "а ты на него в обиде и отказал. Ответь ему коротко.";
		}
		if (phantom.isDead())
		{
			return "";
		}
		if (phantom.isInCombat() || phantom.isAttackingNow() || phantom.isCastingNow())
		{
			return who + "а ты сейчас в бою и отказал. Ответь ему коротко.";
		}
		if (PhantomEvents.getInstance().isBusy(phantom) || PhantomOlympiad.getInstance().isBusy(phantom) || (PhantomRaid.getInstance().raidOf(phantom) != null) || phantom.isInDuel())
		{
			return who + "а ты занят — ивент, рейд или бой — и отказал. Ответь ему коротко.";
		}
		final boolean known = PhantomRelations.getInstance().isFriend(phantom, player) || phantom.getFriendList().contains(player.getObjectId()) || (phantom.isInParty() && (phantom.getParty() == player.getParty())) || ((phantom.getClan() != null) && (phantom.getClan() == player.getClan()));
		if (!known && (Rnd.get(100) >= STRANGER_CHANCE))
		{
			return who + "а ты его не знаешь и отказал. Ответь ему коротко.";
		}
		return null;
	}

	private void answer(Player player, Player phantom, String no)
	{
		phantom.setActiveRequester(null);
		player.onTransactionResponse();
		if (no != null)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + phantom.getName() + " refuses a trade with " + player.getName() + ".");
			PhantomCompat.tradeDenied(player, phantom);
			if (!no.isEmpty())
			{
				PhantomChat.getInstance().hunterEvent(phantom, player, "TRADE", no, null, SAY_GAP, true);
			}
			return;
		}

		final Deal deal = _deals.get(phantom.getObjectId());
		if ((deal == null) || (deal.player != player) || (World.getPlayer(phantom.getObjectId()) == null) || !player.isOnline() || (player.calculateDistance3D(phantom) > RANGE) || player.isProcessingTransaction() || phantom.isProcessingTransaction())
		{
			_deals.remove(phantom.getObjectId());
			if (player.isOnline())
			{
				PhantomCompat.tradeDenied(player, phantom);
			}
			return;
		}

		player.startTrade(phantom);
		LOGGER.info(getClass().getSimpleName() + ": " + phantom.getName() + " opens a trade with " + player.getName() + ".");
		deal.task = ThreadPool.scheduleAtFixedRate(() ->
		{
			try
			{
				step(deal);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": trade of " + phantom.getName() + " failed.", e);
				phantom.cancelActiveTrade();
				close(deal);
			}
		}, STEP, STEP);
	}

	/**
	 * Охотник стоит: автобой остановлен, идти некуда; из {@code PhantomHunterManager.think}.
	 * @return охотник в обмене (окно открыто или запрос ждёт ответа)
	 */
	boolean holds(Player hunter)
	{
		return _deals.containsKey(hunter.getObjectId());
	}

	private static void stand(Player hunter)
	{
		if (hunter.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
		}
		if (hunter.isMoving())
		{
			hunter.stopMove(null);
		}
		hunter.getAI().setIntentionIdle();
	}

	private void step(Deal deal)
	{
		final Player player = deal.player;
		final Player hunter = deal.hunter;
		final TradeList own = hunter.getActiveTradeList();
		final TradeList theirs = player.getActiveTradeList();
		if ((own == null) || (theirs == null) || (own.getPartner() != player) || (theirs.getPartner() != hunter))
		{
			// Игрок закрыл окно: Mobius уже всё убрал.
			if (own != null)
			{
				hunter.cancelActiveTrade();
			}
			close(deal);
			return;
		}

		final long now = System.currentTimeMillis();
		if (hunter.isDead() || !player.isOnline() || hunter.isInCombat() || (player.calculateDistance3D(hunter) > (RANGE * 2)))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " drops the trade with " + player.getName() + ".");
			hunter.cancelActiveTrade();
			close(deal);
			return;
		}

		final Collection<TradeItem> items = theirs.getItems();
		long count = 0;
		for (TradeItem item : items)
		{
			count += item.getCount();
		}
		if ((items.size() != deal.seenLines) || (count != deal.seenCount))
		{
			deal.seenLines = items.size();
			deal.seenCount = count;
			deal.lastChange = now;
			if (deal.priced)
			{
				// Вторую адену положить нельзя: пусть кладёт заново и сразу всё.
				say(deal, "Игрок " + player.getName() + " докладывает вещи в обмен уже после твоей цены. Ты закрыл окно: пусть откроет заново и положит всё сразу. Скажи ему коротко.");
				hunter.cancelActiveTrade();
				close(deal);
				return;
			}
		}

		if (theirs.isConfirmed())
		{
			confirm(deal, own, items);
			return;
		}
		if (!items.isEmpty() && !deal.priced && ((now - deal.lastChange) >= OFFER_DELAY))
		{
			price(deal, own, items);
			return;
		}
		if ((now - deal.lastChange) > IDLE)
		{
			say(deal, "Игрок " + player.getName() + " открыл с тобой обмен и завис, ты устал ждать и закрыл окно. Скажи ему коротко.");
			hunter.cancelActiveTrade();
			close(deal);
		}
	}

	/**
	 * Назвать цену: положить адену в окно один раз, за всё сразу.
	 */
	private void price(Deal deal, TradeList own, Collection<TradeItem> items)
	{
		deal.priced = true;
		final Player hunter = deal.hunter;
		final long worth = worth(deal, items);
		if (worth <= 0)
		{
			// Только адена или вещи без цены — подарок: платить не за что.
			return;
		}
		final Item adena = hunter.getInventory().getAdenaInstance();
		final long purse = adena == null ? 0 : Math.min(adena.getCount(), (((long) hunter.getAdena()) * PURSE) / 100);
		final long pay = Math.min(worth, purse);
		final String what = names(items);
		if ((adena == null) || (pay < Math.max(1, worth * MIN_PAY)))
		{
			say(deal, "Игрок " + deal.player.getName() + " положил в окно обмена: " + what + ". Ты бы взял за " + worth + " адены, но денег у тебя столько нет. Скажи ему коротко.");
			return;
		}
		final TradeItem item = PhantomCompat.addTradeItem(own, adena.getObjectId(), pay, 0);
		if (item == null)
		{
			return;
		}
		deal.paid = pay;
		PhantomCompat.tradeOtherAdd(deal.player, item);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " offers " + pay + " adena to " + deal.player.getName() + " for " + what + " (worth " + worth + ").");
		say(deal, "Игрок " + deal.player.getName() + " положил в окно обмена: " + what + ". Ты положил за это " + pay + " адены. Скажи ему это коротко.");
	}

	/**
	 * Игрок нажал ОК: цены ещё нет — назвать, затем подтвердить. Сделка — у Mobius.
	 */
	private void confirm(Deal deal, TradeList own, Collection<TradeItem> items)
	{
		final Player player = deal.player;
		final Player hunter = deal.hunter;
		if (items.isEmpty())
		{
			say(deal, "Игрок " + player.getName() + " открыл с тобой обмен и нажал ОК, ничего не положив. Ты закрыл окно. Скажи ему коротко.");
			hunter.cancelActiveTrade();
			close(deal);
			return;
		}
		if (!deal.priced)
		{
			price(deal, own, items);
		}

		final String what = names(items);
		final boolean gift = deal.paid <= 0;
		final TradeItem first = items.iterator().next();
		final int objectId = first.getObjectId();
		final long before = countOf(player, objectId);
		if (!own.confirm())
		{
			hunter.cancelActiveTrade();
			close(deal);
			return;
		}
		close(deal);
		if (countOf(player, objectId) == before)
		{
			LOGGER.info(getClass().getSimpleName() + ": trade of " + hunter.getName() + " with " + player.getName() + " did not go through.");
			return;
		}

		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " traded with " + player.getName() + ": " + what + (gift ? " as a gift." : " for " + deal.paid + " adena."));
		if (gift)
		{
			PhantomRelations.getInstance().record(hunter, player, PhantomRelations.Kind.GIFTED);
			say(deal, "Игрок " + player.getName() + " подарил тебе через обмен: " + what + ". Поблагодари его коротко.");
		}
		else
		{
			PhantomFavors.getInstance().onSold(hunter, player);
			say(deal, "Обмен с игроком " + player.getName() + " прошёл: ты купил " + what + " за " + deal.paid + " адены. Скажи ему коротко.");
		}
	}

	private static long countOf(Player player, int objectId)
	{
		final Item item = player.getInventory().getItemByObjectId(objectId);
		return item == null ? 0 : item.getCount();
	}

	/**
	 * Сколько охотник дал бы за вещи: адена игрока не считается, она сама деньги.
	 */
	private static long worth(Deal deal, Collection<TradeItem> items)
	{
		final double factor = PhantomMarket.buyFactor(deal.template);
		long worth = 0;
		for (TradeItem item : items)
		{
			if (item.getItem().getId() != Inventory.ADENA_ID)
			{
				worth += Math.round(item.getItem().getReferencePrice() * factor) * item.getCount();
			}
		}
		return worth;
	}

	private static String names(Collection<TradeItem> items)
	{
		final StringBuilder sb = new StringBuilder();
		for (TradeItem item : items)
		{
			if (sb.length() > 0)
			{
				sb.append(", ");
			}
			sb.append(item.getItem().getName());
			if (item.getCount() > 1)
			{
				sb.append(" (").append(item.getCount()).append(" шт.)");
			}
		}
		return sb.toString();
	}

	private static void say(Deal deal, String what)
	{
		PhantomChat.getInstance().hunterEvent(deal.hunter, deal.player, "TRADE", what, null, SAY_GAP, true);
	}

	private void close(Deal deal)
	{
		_deals.remove(deal.hunter.getObjectId(), deal);
		if (deal.task != null)
		{
			deal.task.cancel(false);
		}
	}

	public static PhantomTrade getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomTrade INSTANCE = new PhantomTrade();
	}
}
