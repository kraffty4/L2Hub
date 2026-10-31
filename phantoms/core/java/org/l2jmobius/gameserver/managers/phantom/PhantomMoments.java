package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.phantom.PhantomRelations.Kind;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: случайности людей (docs/LIVE_SERVER.md, вторая волна, п. 5; docs/PHANTOMS.md, раздел 4.77). Всё за ключами и выключено, новых персонажей нет. Случай одного вида на весь мир — не чаще
 * {@code HunterMomentGapMinutes}.
 * <ul>
 * <li>подарок новичку ({@code HunterGifts}): охотник много старше живого новичка подбегает и зовёт его в обмен штатным запросом ({@code SendTradeRequest}), кладёт адену и шоты. Отказ от обменов, игнор
 * и отказ в окне уважает, вещей игрока не берёт: положил что-то в окно — охотник закрывает его и деньги оставляет себе;</li>
 * <li>разборка из-за дропа ({@code HunterLootFights}): заметная вещь досталась охотнику в пати — сосед предъявляет, тот огрызается, иногда сосед уходит из пати и копит обиду. С живым в пати — только слова;
 * поднял живой — редкая лёгкая реплика без последствий;</li>
 * <li>«кинул в трейде» ({@code HunterScams}): только между охотниками в городе, настоящая адена и настоящая обида, жертва кричит в торговый чат. Живого не кидают никогда.</li>
 * </ul>
 */
final class PhantomMoments
{
	private static final Logger LOGGER = Logger.getLogger(PhantomMoments.class.getName());

	private static final long CHECK = 60000;
	private static final long STEP = 1000;

	/** Живой новичок — до такого уровня. */
	private static final int GIFT_LEVEL = 25;
	/** Дарит тот, кто на столько старше. */
	private static final int GIFT_ELDER = 20;
	private static final int GIFT_RANGE = 900;
	private static final int GIFT_CHANCE = 25;
	/** Одному живому — не чаще. */
	private static final long GIFT_PLAYER_GAP = 21600000;
	/** Кошелёк меньше — не дарит. */
	private static final long GIFT_PURSE = 20000;
	/** Дарит не больше такой части кошелька (1/N). */
	private static final int GIFT_PART = 20;
	private static final int GIFT_MIN = 1000;
	private static final int GIFT_LOW = 5000;
	private static final int GIFT_HIGH = 30000;
	/** С этого расстояния зовёт в обмен (штатный предел — 150). */
	private static final int TRADE_NEAR = 100;
	private static final int TRADE_RANGE = 150;
	private static final long RUN_MAX = 30000;
	private static final long ASK_MAX = 30000;
	private static final long WINDOW_MAX = 60000;
	private static final long PUT_DELAY = 2000;
	/** Soulshot: No Grade и D Grade. */
	private static final int SHOT_NG = 1835;
	private static final int SHOT_D = 1463;
	private static final int SHOT_D_LEVEL = 20;
	private static final long SHOTS_SPARE = 200;
	private static final int SHOTS_PART = 5;
	private static final long SAY_GAP = 600000;

	private static final int FIGHT_CHANCE = 30;
	private static final int LEAVE_CHANCE = 35;
	private static final int JOKE_CHANCE = 15;
	private static final int FIGHT_RANGE = 1500;
	/** Заметная вещь: цена от level² × столько, но не меньше {@value #WORTH_MIN}. */
	private static final int WORTH_FACTOR = 30;
	private static final long WORTH_MIN = 5000;

	/** Кидок — не чаще на весь мир, каким бы ни был {@code HunterMomentGapMinutes}. */
	private static final long SCAM_GAP = 21600000;
	private static final long SCAM_PURSE = 100000;
	private static final int SCAM_PART = 20;
	private static final long SCAM_MIN = 10000;
	private static final long SCAM_MAX = 500000;
	private static final int SCAM_RANGE = 1200;

	private static final String[][] FIGHT_SAY =
	{
		{ "э, это мой дроп был, я его бил", "this drop was mine, I was hitting it" },
		{ "ты чё, с таким дропом и молчишь?", "got that drop and not a word?" },
		{ "мне эта вещь нужна была, вообще-то", "I actually needed that item" }
	};
	private static final String[][] FIGHT_ANSWER =
	{
		{ "кто успел, того и тапки", "first come, first served" },
		{ "рандом так решил, не ко мне вопрос", "the dice decided, take it up with them" },
		{ "ну упало и упало, чего ты", "it dropped, what do you want" }
	};
	private static final String[][] LEAVE_SAY =
	{
		{ "всё, я пошёл. дальше сами", "that's it, I'm out. you're on your own" },
		{ "с такими я не качаюсь", "I'm not leveling with people like this" }
	};
	private static final String[][] SCAM_SHOUT =
	{
		{ "%s кинул меня на %s адены! не торгуйте с ним", "%s scammed me out of %s adena! don't trade with them" },
		{ "осторожно, %s: взял %s адены и не отдал вещь", "watch out for %s: took %s adena and kept the item" }
	};

	private enum Stage
	{
		RUN,
		ASK,
		WINDOW
	}

	private static class Gift
	{
		final Player hunter;
		final Player player;
		Stage stage = Stage.RUN;
		long since;
		long nextMove;
		boolean put;
		long paid;
		volatile ScheduledFuture<?> task;

		Gift(Player hunter, Player player, long now)
		{
			this.hunter = hunter;
			this.player = player;
			since = now;
		}
	}

	/** objectId охотника → подарок: с выбора до закрытия окна. */
	private final Map<Integer, Gift> _gifts = new ConcurrentHashMap<>();
	/** Живой → новый подарок не раньше. */
	private final Map<Integer, Long> _playerNext = new ConcurrentHashMap<>();
	private long _nextCheck;
	private long _giftNext;
	private long _scamNext;
	private volatile long _fightNext;
	private boolean _listening;

	/** Самопроверка: кто стоит и не думает, порог цены, всегда ругаться, всегда уходить. */
	private volatile int _testHold;
	private volatile int _testHold2;
	private volatile long _testWorth;
	private volatile int _fights;

	private PhantomMoments()
	{
	}

	private static String pick(String ru, String en)
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE) ? en : ru;
	}

	private static String pick(String[][] lines)
	{
		final String[] line = lines[Rnd.get(lines.length)];
		return pick(line[0], line[1]);
	}

	private static long gap()
	{
		return PhantomHuntersConfig.HUNTER_MOMENT_GAP_MINUTES * 60000L;
	}

	/**
	 * Из хода охотников ({@link PhantomHunterManager}).
	 * @param hunters охотники в мире
	 * @param now текущее время
	 */
	void control(List<Player> hunters, long now)
	{
		if (PhantomHuntersConfig.HUNTER_LOOT_FIGHTS)
		{
			listen();
		}
		if (now < _nextCheck)
		{
			return;
		}
		_nextCheck = now + CHECK;
		if (PhantomHuntersConfig.HUNTER_GIFTS && (now >= _giftNext))
		{
			lookForGift(now);
		}
		if (PhantomHuntersConfig.HUNTER_SCAMS && (now >= _scamNext))
		{
			lookForScam(hunters, now);
		}
	}

	private synchronized void listen()
	{
		if (!_listening)
		{
			_listening = true;
			PhantomCompat.addItemAddListener(this, this::onItemAdd);
		}
	}

	/**
	 * @param hunter охотник
	 * @return охотник занят подарком и стоит или бежит сам: {@code think} его не трогает
	 */
	boolean holds(Player hunter)
	{
		final int id = hunter.getObjectId();
		return _gifts.containsKey(id) || (id == _testHold) || (id == _testHold2);
	}

	private static boolean busy(Player hunter)
	{
		return PhantomEvents.getInstance().isBusy(hunter) || PhantomOlympiad.getInstance().isBusy(hunter) || (PhantomRaid.getInstance().raidOf(hunter) != null) || hunter.isInDuel();
	}

	/**
	 * Условия {@code TradeRequest} со стороны каждого: чего Mobius не пропустил бы, то здесь и не начинается.
	 */
	private static boolean free(Player player)
	{
		return !player.isDead() && !player.isInCombat() && !player.isAttackingNow() && !player.isCastingNow() && !player.isTeleporting() && !player.isInStoreMode() && !player.isInOlympiadMode() && !player.isJailed() && !player.isProcessingRequest() && !player.isProcessingTransaction() && !player.isInDuel() && (PhantomCompat.karma(player) <= 0);
	}

	// ---------- Подарок новичку ----------

	private boolean canReceive(Player player, long now)
	{
		return PhantomNear.getInstance().isLive(player) && (player.getLevel() <= GIFT_LEVEL) && (now >= _playerNext.getOrDefault(player.getObjectId(), 0L)) && free(player) && !player.getTradeRefusal();
	}

	private boolean canGive(Player hunter, Player player)
	{
		return PhantomRelations.isHunter(hunter) && !PhantomNear.getInstance().isLive(hunter) && (hunter.getLevel() >= (player.getLevel() + GIFT_ELDER)) && !holds(hunter) && !PhantomTrade.getInstance().holds(hunter) && free(hunter) && !busy(hunter) && (((long) hunter.getAdena()) >= GIFT_PURSE) && !PhantomRelations.getInstance().isEnemy(hunter, player) && !BlockList.isBlocked(player, hunter);
	}

	private void lookForGift(long now)
	{
		for (Player player : World.getPlayers())
		{
			if (!canReceive(player, now))
			{
				continue;
			}
			final List<Player> donors = new ArrayList<>();
			for (Player other : World.getVisibleObjectsInRange(player, Player.class, GIFT_RANGE))
			{
				if (canGive(other, player))
				{
					donors.add(other);
				}
			}
			if (!donors.isEmpty() && (Rnd.get(100) < GIFT_CHANCE))
			{
				begin(donors.get(Rnd.get(donors.size())), player, now);
				return;
			}
		}
	}

	private Gift begin(Player hunter, Player player, long now)
	{
		_giftNext = now + gap();
		_playerNext.put(player.getObjectId(), now + GIFT_PLAYER_GAP);
		final Gift gift = new Gift(hunter, player, now);
		_gifts.put(hunter.getObjectId(), gift);
		if (hunter.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") runs to give a gift to " + player.getName() + " (" + player.getLevel() + ").");
		gift.task = ThreadPool.scheduleAtFixedRate(() ->
		{
			try
			{
				step(gift);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": gift of " + hunter.getName() + " failed.", e);
				drop(gift);
			}
		}, STEP, STEP);
		return gift;
	}

	private void step(Gift gift)
	{
		final Player hunter = gift.hunter;
		final Player player = gift.player;
		final long now = System.currentTimeMillis();
		if ((World.getPlayer(hunter.getObjectId()) == null) || !player.isOnline() || hunter.isDead() || player.isDead() || hunter.isInCombat() || player.isInCombat() || PhantomTrade.getInstance().holds(hunter))
		{
			drop(gift);
			return;
		}

		switch (gift.stage)
		{
			case RUN:
			{
				run(gift, now);
				break;
			}
			case ASK:
			{
				final TradeList own = hunter.getActiveTradeList();
				if ((own != null) && (own.getPartner() == player))
				{
					gift.stage = Stage.WINDOW;
					gift.since = now;
				}
				else if (!hunter.isProcessingRequest() || ((now - gift.since) > ASK_MAX))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " did not take the trade of " + hunter.getName() + ".");
					drop(gift);
				}
				break;
			}
			case WINDOW:
			{
				window(gift, now);
				break;
			}
		}
	}

	private void run(Gift gift, long now)
	{
		final Player hunter = gift.hunter;
		final Player player = gift.player;
		if (hunter.calculateDistance3D(player) > TRADE_NEAR)
		{
			if ((now - gift.since) > RUN_MAX)
			{
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " could not reach " + player.getName() + ".");
				drop(gift);
			}
			else if (now >= gift.nextMove)
			{
				gift.nextMove = now + 3000;
				PhantomHunterManager.runTo(hunter, new Location(player.getX(), player.getY(), player.getZ()));
			}
			return;
		}

		if (hunter.isMoving())
		{
			hunter.stopMove(null);
		}
		hunter.getAI().setIntentionIdle();
		// Живой мог включить отказ от обменов, занести охотника в игнор или открыть лавку, пока тот бежал.
		if (!free(player) || player.getTradeRefusal() || !free(hunter) || BlockList.isBlocked(player, hunter) || (hunter.calculateDistance3D(player) > TRADE_RANGE))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " cannot be asked to trade by " + hunter.getName() + " now.");
			drop(gift);
			return;
		}
		PhantomChat.getInstance().hunterEvent(hunter, player, "GIFT", "рядом новичок " + player.getName() + " " + player.getLevel() + "-го уровня, ты сам был таким и решил подкинуть ему денег на шоты. Шепни ему коротко, что открываешь обмен", pick("эй, новичок, держи на шоты, кидаю обмен", "hey, newbie, some money for shots, opening a trade"), SAY_GAP, true);
		PhantomCompat.sendTradeRequest(hunter, player);
		gift.stage = Stage.ASK;
		gift.since = now;
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " asks " + player.getName() + " to trade.");
	}

	private void window(Gift gift, long now)
	{
		final Player hunter = gift.hunter;
		final Player player = gift.player;
		final TradeList own = hunter.getActiveTradeList();
		final TradeList theirs = player.getActiveTradeList();
		if ((own == null) || (theirs == null) || (own.getPartner() != player) || (theirs.getPartner() != hunter))
		{
			// Игрок закрыл окно: Mobius уже всё убрал.
			if (own != null)
			{
				hunter.cancelActiveTrade();
			}
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " closed the trade window of " + hunter.getName() + ".");
			end(gift);
			return;
		}
		if (!theirs.getItems().isEmpty())
		{
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " put something in the gift window of " + hunter.getName() + ": closes it, takes nothing.");
			PhantomChat.getInstance().hunterEvent(hunter, player, "GIFT_BACK", player.getName() + ", которому ты хотел подарить денег, сам положил что-то в окно обмена. Ты ничего от него не хочешь и закрыл окно. Скажи ему коротко", pick("не, не надо мне ничего, оставь себе", "no, keep your stuff, I don't need anything"), SAY_GAP, true);
			hunter.cancelActiveTrade();
			end(gift);
			return;
		}
		if ((now - gift.since) > WINDOW_MAX)
		{
			hunter.cancelActiveTrade();
			end(gift);
			return;
		}
		if (!gift.put)
		{
			if ((now - gift.since) >= PUT_DELAY)
			{
				put(gift, own);
			}
			return;
		}
		if (theirs.isConfirmed())
		{
			final long before = player.getAdena();
			if (!own.confirm())
			{
				hunter.cancelActiveTrade();
				end(gift);
				return;
			}
			end(gift);
			if (player.getAdena() <= before)
			{
				LOGGER.info(getClass().getSimpleName() + ": gift of " + hunter.getName() + " to " + player.getName() + " did not go through.");
				return;
			}
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " gave " + gift.paid + " adena to " + player.getName() + ".");
			PhantomRelations.getInstance().record(hunter, player, Kind.GIFT_GIVEN);
			PhantomChat.getInstance().hunterEvent(hunter, player, "GIFT_DONE", "ты подарил новичку " + player.getName() + " " + gift.paid + " адены, он принял. Скажи ему коротко, что не за что", pick("да не за что, удачи)", "you're welcome, good luck)"), SAY_GAP, true);
		}
	}

	private void put(Gift gift, TradeList own)
	{
		final Player hunter = gift.hunter;
		final Item adena = hunter.getInventory().getAdenaInstance();
		final long purse = adena == null ? 0 : adena.getCount();
		final long pay = Math.min(purse / GIFT_PART, Rnd.get(GIFT_LOW, GIFT_HIGH));
		final TradeItem item = (adena == null) || (pay < GIFT_MIN) ? null : PhantomCompat.addTradeItem(own, adena.getObjectId(), pay, 0);
		if (item == null)
		{
			hunter.cancelActiveTrade();
			end(gift);
			return;
		}
		gift.put = true;
		gift.paid = pay;
		PhantomCompat.tradeOtherAdd(gift.player, item);

		final Item shots = hunter.getInventory().getItemByItemId(gift.player.getLevel() < SHOT_D_LEVEL ? SHOT_NG : SHOT_D);
		if ((shots != null) && (shots.getCount() >= SHOTS_SPARE))
		{
			final TradeItem spare = PhantomCompat.addTradeItem(own, shots.getObjectId(), shots.getCount() / SHOTS_PART, 0);
			if (spare != null)
			{
				PhantomCompat.tradeOtherAdd(gift.player, spare);
			}
		}
	}

	/**
	 * Сорвалось на любом шаге: запрос и окно снять. Не добежал — живой ничего не заметил, его можно одарить снова.
	 */
	private void drop(Gift gift)
	{
		final Player hunter = gift.hunter;
		if (gift.stage == Stage.RUN)
		{
			_playerNext.remove(gift.player.getObjectId());
		}
		if (gift.stage == Stage.ASK)
		{
			hunter.onTransactionResponse();
			if (gift.player.getActiveRequester() == hunter)
			{
				gift.player.setActiveRequester(null);
			}
		}
		if (hunter.getActiveTradeList() != null)
		{
			hunter.cancelActiveTrade();
		}
		end(gift);
	}

	private void end(Gift gift)
	{
		_gifts.remove(gift.hunter.getObjectId(), gift);
		if (gift.task != null)
		{
			gift.task.cancel(false);
		}
	}

	// ---------- Разборка из-за дропа ----------

	private long worth(int level)
	{
		return _testWorth > 0 ? _testWorth : Math.max(WORTH_MIN, (long) level * level * WORTH_FACTOR);
	}

	private void onItemAdd(Player player, Item item)
	{
		// Событие не говорит, откуда вещь: в мирной зоне это покупка, склад или обмен, а не дроп.
		if (!PhantomHuntersConfig.HUNTER_LOOT_FIGHTS || (item == null) || !player.isInParty() || player.isInsideZone(ZoneId.PEACE) || (item.getId() == Inventory.ADENA_ID) || item.isQuestItem() || (item.getTemplate().getReferencePrice() < worth(player.getLevel())))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (now < _fightNext)
		{
			return;
		}

		final boolean test = _testWorth > 0;
		final List<Player> hunters = new ArrayList<>();
		boolean living = false;
		for (Player member : player.getParty().getMembers())
		{
			if (member == player)
			{
				continue;
			}
			if (PhantomNear.getInstance().isLive(member))
			{
				living = true;
			}
			else if (PhantomRelations.isHunter(member) && !member.isDead() && (player.calculateDistance2D(member) <= FIGHT_RANGE))
			{
				hunters.add(member);
			}
		}
		if (hunters.isEmpty())
		{
			return;
		}

		final String what = item.getName();
		if (PhantomNear.getInstance().isLive(player))
		{
			if (test || (Rnd.get(100) < JOKE_CHANCE))
			{
				_fightNext = now + gap();
				final Player hunter = hunters.get(Rnd.get(hunters.size()));
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " jokes about " + what + " of " + player.getName() + ".");
				PhantomChat.getInstance().hunterEvent(hunter, player, "LOOT_JOKE", "твоему соседу по пати " + player.getName() + " досталась вещь " + what + ". Подколи его коротко и по-доброму, вслух", pick("ого, повезло тебе) с тебя баф", "wow, lucky you) you owe me a buff"), SAY_GAP);
			}
			return;
		}
		if (!PhantomRelations.isHunter(player) || !PhantomHunterManager.getInstance().isHunting(player))
		{
			return;
		}

		final Player complainer = hunters.get(Rnd.get(hunters.size()));
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(complainer.getName());
		final int chance = template == null ? FIGHT_CHANCE : template.isReckless() ? (FIGHT_CHANCE * 3) / 2 : template.isCautious() ? FIGHT_CHANCE / 2 : FIGHT_CHANCE;
		if (!test && (Rnd.get(100) >= chance))
		{
			return;
		}
		_fightNext = now + gap();
		_fights++;
		fight(player, complainer, what, !living && (test || (Rnd.get(100) < LEAVE_CHANCE)));
	}

	private void fight(Player looter, Player complainer, String what, boolean leaves)
	{
		LOGGER.info(getClass().getSimpleName() + ": " + complainer.getName() + " fights with " + looter.getName() + " over " + what + (leaves ? " and leaves the party." : "."));
		PhantomRelations.getInstance().record(complainer, looter, Kind.LOOT_FIGHT);
		final PhantomChat chat = PhantomChat.getInstance();
		chat.hunterEvent(complainer, looter, "LOOT_FIGHT", "ты качаешься в пати с " + looter.getName() + ", и ему досталась вещь " + what + ", которая была нужна тебе. Предъяви ему коротко, вслух", pick(FIGHT_SAY), SAY_GAP);
		ThreadPool.schedule(() ->
		{
			chat.hunterEvent(looter, complainer, "LOOT_ANSWER", complainer.getName() + " из твоей пати предъявляет тебе за вещь " + what + ", которая досталась тебе. Огрызнись коротко, вслух", pick(FIGHT_ANSWER), SAY_GAP);
			if (leaves)
			{
				ThreadPool.schedule(() -> leave(complainer, looter, what), Rnd.get(3000, 6000));
			}
		}, Rnd.get(3000, 6000));
	}

	private void leave(Player complainer, Player looter, String what)
	{
		if (!complainer.isInParty() || (complainer.getParty() != looter.getParty()))
		{
			return;
		}
		PhantomChat.getInstance().hunterEvent(complainer, looter, "LOOT_LEAVE", "ты поругался с " + looter.getName() + " из-за вещи " + what + " и уходишь из пати. Скажи коротко, вслух", pick(LEAVE_SAY), SAY_GAP);
		// Ведущий просто выходит, пати распустит PhantomHunterParty.check; остальных отпускает их напарник.
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(complainer.getName());
		if (!PhantomHunterParty.getInstance().isLeader(complainer) && (template != null))
		{
			PhantomCompanionManager.getInstance().release(template, true);
		}
		if (complainer.isInParty())
		{
			complainer.leaveParty();
		}
		PhantomNews.post("drop_fight", String.format(pick("%s ушёл из пати, поругавшись с %s из-за %s", "%s left the party after a fight with %s over %s"), complainer.getName(), looter.getName(), what), complainer.getName(), complainer);
	}

	// ---------- «Кинул в трейде» ----------

	private boolean canDeal(Player hunter)
	{
		return PhantomRelations.isHunter(hunter) && !PhantomNear.getInstance().isLive(hunter) && !holds(hunter) && !PhantomTrade.getInstance().holds(hunter) && free(hunter) && !busy(hunter);
	}

	private void lookForScam(Collection<Player> hunters, long now)
	{
		// Кричать некому — кидать незачем: торговый чат слышат только живые.
		boolean heard = false;
		for (Player player : World.getPlayers())
		{
			if (PhantomNear.getInstance().isLive(player))
			{
				heard = true;
				break;
			}
		}
		if (!heard)
		{
			return;
		}

		final List<Player> town = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (hunter.isInsideZone(ZoneId.PEACE) && canDeal(hunter))
			{
				town.add(hunter);
			}
		}
		Collections.shuffle(town);
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Player victim : town)
		{
			if (((long) victim.getAdena()) < SCAM_PURSE)
			{
				continue;
			}
			for (Player scammer : town)
			{
				if ((scammer != victim) && (victim.calculateDistance2D(scammer) <= SCAM_RANGE) && !PhantomHunterClans.clanmates(victim, scammer) && !relations.isFriend(victim, scammer) && !relations.isFriend(scammer, victim))
				{
					scam(victim, scammer, now);
					return;
				}
			}
		}
	}

	private boolean scam(Player victim, Player scammer, long now)
	{
		final long amount = Math.max(SCAM_MIN, Math.min(SCAM_MAX, ((long) victim.getAdena()) / SCAM_PART));
		if (!PhantomCompat.reduceAdena(victim, ItemProcessType.TRANSFER, amount, null, false))
		{
			return false;
		}
		PhantomCompat.addAdena(scammer, ItemProcessType.TRANSFER, amount, null, false);
		_scamNext = now + Math.max(SCAM_GAP, gap());
		LOGGER.info(getClass().getSimpleName() + ": " + scammer.getName() + " scams " + victim.getName() + " out of " + amount + " adena.");
		PhantomRelations.getInstance().record(victim, scammer, Kind.SCAMMED);
		PhantomNews.post("scam", String.format(pick("%s кинул %s в обмене на %d адены", "%s scammed %s out of %d adena in a trade"), scammer.getName(), victim.getName(), amount), victim.getName(), victim);
		ThreadPool.schedule(() -> PhantomChat.getInstance().hunterShout(victim, String.format(pick(SCAM_SHOUT), scammer.getName(), amount)), Rnd.get(5000, 15000));
		return true;
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка MOMENTS ({@code HuntersSelfTest}, только на копии базы): живого играет охотник, ответы окна обмена — за него. Ключи выключены — тишина; подарок принят, отклонён, в окно положили вещь;
	 * отказ от обменов; разборка с уходом и с живым в пати; кидок между охотниками, живого в кандидатах нет. Ключи возвращаются.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		ThreadPool.execute(() ->
		{
			final String tag = getClass().getSimpleName() + ": SelfTest MOMENTS: ";
			final boolean gifts = PhantomHuntersConfig.HUNTER_GIFTS;
			final boolean fights = PhantomHuntersConfig.HUNTER_LOOT_FIGHTS;
			final boolean scams = PhantomHuntersConfig.HUNTER_SCAMS;
			try
			{
				runTest(tag, hunters);
				LOGGER.info(tag + "done.");
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, tag + "failed.", e);
			}
			finally
			{
				PhantomHuntersConfig.HUNTER_GIFTS = gifts;
				PhantomHuntersConfig.HUNTER_LOOT_FIGHTS = fights;
				PhantomHuntersConfig.HUNTER_SCAMS = scams;
				_testHold = 0;
				_testHold2 = 0;
				_testWorth = 0;
				PhantomNear.getInstance().testPlayer(null);
			}
		});
	}

	private static boolean await(BooleanSupplier done, long millis) throws InterruptedException
	{
		final long until = System.currentTimeMillis() + millis;
		while (!done.getAsBoolean())
		{
			if (System.currentTimeMillis() > until)
			{
				return false;
			}
			Thread.sleep(250);
		}
		return true;
	}

	private void runTest(String tag, List<Player> hunters) throws InterruptedException
	{
		Player live = null;
		Player donor = null;
		for (Player hunter : hunters)
		{
			// В пати его водит лидер, а не ход охотника: «живым» и дарителем такого не взять.
			if (hunter.isDead() || hunter.isInParty())
			{
				continue;
			}
			if ((live == null) || (hunter.getLevel() < live.getLevel()))
			{
				live = hunter;
			}
			if (!busy(hunter) && ((donor == null) || (hunter.getLevel() > donor.getLevel())))
			{
				donor = hunter;
			}
		}
		if ((live == null) || (donor == null) || (live == donor))
		{
			LOGGER.warning(tag + "needs two hunters in the world.");
			return;
		}
		if (donor.getLevel() < (live.getLevel() + GIFT_ELDER))
		{
			LOGGER.info(tag + "levels " + live.getLevel() + " and " + donor.getLevel() + " are not " + GIFT_ELDER + " apart: the level filter says no, the rest is checked anyway.");
		}
		final Player player = live;
		final Player elder = donor;
		_testHold = player.getObjectId();
		_testHold2 = elder.getObjectId();
		for (Player one : new Player[]
		{
			player,
			elder
		})
		{
			if (one.isAutoPlaying())
			{
				AutoPlayTaskManager.getInstance().stopAutoPlay(one);
			}
			one.getAI().setIntentionIdle();
		}
		// Обоих — в Гиран: на споте их бьют, а на окно обмена нужны двое свободных.
		elder.teleToLocation(new Location(83400, 147943, -3404), false);
		player.teleToLocation(new Location(83650, 147943, -3404), false);
		Thread.sleep(3000);
		await(() -> free(player) && free(elder), 30000);
		PhantomNear.getInstance().testPlayer(player);
		if (((long) elder.getAdena()) < (GIFT_PURSE * 2))
		{
			PhantomCompat.addAdena(elder, ItemProcessType.FEE, GIFT_PURSE * 10, null, false);
		}

		// Ключи выключены.
		PhantomHuntersConfig.HUNTER_GIFTS = false;
		PhantomHuntersConfig.HUNTER_LOOT_FIGHTS = false;
		PhantomHuntersConfig.HUNTER_SCAMS = false;
		_nextCheck = 0;
		_giftNext = 0;
		_scamNext = 0;
		control(hunters, System.currentTimeMillis());
		LOGGER.info(tag + "keys off: gifts " + _gifts.size() + ", listener " + _listening + ".");

		PhantomHuntersConfig.HUNTER_GIFTS = true;
		player.getAI().setIntentionIdle();
		_testHold2 = 0;
		LOGGER.info(tag + "candidates: receives " + canReceive(player, System.currentTimeMillis()) + " (level " + player.getLevel() + "), gives " + canGive(elder, player) + " (level " + elder.getLevel() + ", adena " + elder.getAdena() + "), distance " + (int) elder.calculateDistance3D(player) + ".");
		_testHold2 = elder.getObjectId();

		// Принял.
		final long playerBefore = player.getAdena();
		final long elderBefore = elder.getAdena();
		final Gift first = begin(elder, player, System.currentTimeMillis());
		boolean asked = await(() -> player.getActiveRequester() == elder, RUN_MAX + 3000);
		LOGGER.info(tag + "accept: request reached the player " + asked + ", distance " + (int) elder.calculateDistance3D(player) + ".");
		if (!asked)
		{
			end(first);
			player.teleToLocation(new Location(elder.getX(), elder.getY(), elder.getZ()), false);
			Thread.sleep(3000);
			begin(elder, player, System.currentTimeMillis());
			asked = await(() -> player.getActiveRequester() == elder, 10000);
			LOGGER.info(tag + "accept: second try next to him, request reached the player " + asked + ".");
			if (!asked)
			{
				return;
			}
		}
		answer(player, elder, true);
		final boolean put = await(() -> (elder.getActiveTradeList() != null) && !elder.getActiveTradeList().getItems().isEmpty(), 8000);
		LOGGER.info(tag + "accept: hunter put " + (put ? listed(elder.getActiveTradeList()) : "nothing") + ".");
		if (put)
		{
			player.getActiveTradeList().confirm();
		}
		await(() -> !_gifts.containsKey(elder.getObjectId()), 8000);
		LOGGER.info(tag + "accept: player adena " + playerBefore + " -> " + player.getAdena() + ", hunter adena " + elderBefore + " -> " + elder.getAdena() + ", windows open " + (player.getActiveTradeList() != null) + ", relation " + PhantomRelations.getInstance().isFriend(elder, player) + "/" + PhantomRelations.getInstance().isEnemy(elder, player) + ".");
		LOGGER.info(tag + "cooldown: receives again " + canReceive(player, System.currentTimeMillis()) + ".");

		// Отклонил.
		_playerNext.clear();
		long adena = player.getAdena();
		begin(elder, player, System.currentTimeMillis());
		await(() -> player.getActiveRequester() == elder, 10000);
		answer(player, elder, false);
		final boolean refused = await(() -> !_gifts.containsKey(elder.getObjectId()), 8000);
		LOGGER.info(tag + "refuse: gift ended " + refused + ", adena moved " + (player.getAdena() - adena) + ", window " + (player.getActiveTradeList() != null) + ".");

		// Положил вещь в окно: охотник ничего не берёт.
		_playerNext.clear();
		adena = player.getAdena();
		final long elderAdena = elder.getAdena();
		begin(elder, player, System.currentTimeMillis());
		await(() -> player.getActiveRequester() == elder, 10000);
		answer(player, elder, true);
		await(() -> elder.getActiveTradeList() != null, 5000);
		final Item own = player.getInventory().getAdenaInstance();
		final TradeList window = player.getActiveTradeList();
		final TradeItem added = (own == null) || (window == null) ? null : PhantomCompat.addTradeItem(window, own.getObjectId(), 1, 0);
		final boolean closed = await(() -> !_gifts.containsKey(elder.getObjectId()), 8000);
		LOGGER.info(tag + "player puts adena in the window (" + (added != null) + "): gift ended " + closed + ", player adena moved " + (player.getAdena() - adena) + ", hunter adena moved " + (elder.getAdena() - elderAdena) + ", window " + (player.getActiveTradeList() != null) + ".");

		// Отказ от обменов.
		_playerNext.clear();
		final boolean refusal = player.getTradeRefusal();
		player.setTradeRefusal(true);
		LOGGER.info(tag + "trade refusal on: receives " + canReceive(player, System.currentTimeMillis()) + ".");
		player.setTradeRefusal(refusal);

		lootTest(tag, hunters, player, elder);
		scamTest(tag, hunters, player, elder);
	}

	private static void answer(Player player, Player partner, boolean yes)
	{
		// То же, что AnswerTradeRequest: клиента у этого «живого» нет.
		if (yes)
		{
			player.startTrade(partner);
		}
		player.setActiveRequester(null);
		partner.onTransactionResponse();
	}

	private static String listed(TradeList list)
	{
		final StringBuilder sb = new StringBuilder();
		for (TradeItem item : list.getItems())
		{
			sb.append(sb.length() > 0 ? ", " : "").append(item.getCount()).append(" x ").append(item.getItem().getName());
		}
		return sb.toString();
	}

	private void lootTest(String tag, List<Player> hunters, Player player, Player elder) throws InterruptedException
	{
		PhantomHuntersConfig.HUNTER_LOOT_FIGHTS = true;
		final boolean parties = PhantomHuntersConfig.HUNTER_PARTIES;
		PhantomHuntersConfig.HUNTER_PARTIES = true;
		listen();
		_testWorth = 1;
		try
		{
			// Охотники только вошли и идут на споты: ждём, пока хоть две пары встанут рядом.
			final List<Player> free = new ArrayList<>();
			final List<Player[]> pairs = new ArrayList<>();
			await(() ->
			{
				free.clear();
				pairs.clear();
				for (Player hunter : hunters)
				{
					if ((hunter != player) && (hunter != elder) && !hunter.isDead() && !hunter.isInParty() && !busy(hunter) && PhantomHunterManager.getInstance().isHunting(hunter))
					{
						free.add(hunter);
					}
				}
				final long at = System.currentTimeMillis();
				final List<Player> taken = new ArrayList<>();
				for (Player first : free)
				{
					for (Player second : free)
					{
						if ((first != second) && !taken.contains(first) && !taken.contains(second) && (Math.abs(first.getLevel() - second.getLevel()) <= 5) && (first.calculateDistance2D(second) <= FIGHT_RANGE) && (PhantomHunterParty.getInstance().stateRefusal(first, second, at) == null))
						{
							pairs.add(new Player[]
							{
								first,
								second
							});
							taken.add(first);
							taken.add(second);
							break;
						}
					}
				}
				return pairs.size() >= 2;
			}, 180000);
			final long now = System.currentTimeMillis();
			LOGGER.info(tag + "loot: hunting and free " + free.size() + ", pairs next to each other " + pairs.size() + ".");
			// Сами рядом не встали: охотников мало, они только вошли. Разборке важна пати, а не спот, поэтому сводим телепортом.
			if (pairs.size() < 2)
			{
				pairs.clear();
				for (int i = 0; (i + 1) < free.size(); i += 2)
				{
					final Player first = free.get(i);
					final Player second = free.get(i + 1);
					if (PhantomHunterParty.getInstance().stateRefusal(first, second, now) == null)
					{
						second.teleToLocation(new Location(first.getX(), first.getY(), first.getZ()), true);
						pairs.add(new Player[]
						{
							first,
							second
						});
					}
				}
				Thread.sleep(3000);
				LOGGER.info(tag + "loot: pairs brought together " + pairs.size() + ".");
			}
			if (pairs.isEmpty())
			{
				LOGGER.warning(tag + "loot: no free hunters on spots, not checked.");
				return;
			}

			// Двое охотников: тот, кто пожаловался, уходит. Вещь получает тот, кто стоял на споте: сведённый телепортом на каче ещё не числится, и событие его вещи не считает.
			if (!lootLeave(tag, "member complains", pairs.get(0)[0], pairs.get(0)[1], pairs.get(0)[0]))
			{
				return;
			}
			if (pairs.size() > 2)
			{
				final Player[] last = pairs.get(pairs.size() - 1);
				lootLeave(tag, "leader complains", last[1], last[0], last[0]);
			}

			// С живым в пати: только слова.
			if (pairs.size() > 1)
			{
				final Player c = pairs.get(1)[0];
				final Player d = pairs.get(1)[1];
				final String again = PhantomHunterParty.getInstance().ask(c, d, true, System.currentTimeMillis());
				if ((again == null) && c.isInParty())
				{
					final Player third = free.stream().filter(p -> (p != c) && (p != d) && !p.isInParty() && (PhantomHunterParty.getInstance().stateRefusal(c, p, System.currentTimeMillis()) == null)).findFirst().orElse(null);
					if (third != null)
					{
						PhantomHunterParty.getInstance().ask(c, third, true, System.currentTimeMillis());
					}
					final boolean three = (third != null) && third.isInParty() && (third.getParty() == c.getParty());
					if (three)
					{
						PhantomNear.getInstance().testPlayer(third);
						await(() -> c.calculateDistance2D(d) <= FIGHT_RANGE, 15000);
						_fightNext = 0;
						final int count = _fights;
						c.addItem(ItemProcessType.REWARD, 1, 1, null, false);
						await(() -> _fights > count, 6000);
						Thread.sleep(15000);
						LOGGER.info(tag + "loot with a living player in the party: fights " + (_fights - count) + ", party members left " + c.getParty().getMemberCount() + " of 3.");
					}

					// Поднял сам живой: без последствий.
					_fightNext = 0;
					final int jokes = _fights;
					PhantomNear.getInstance().testPlayer(d);
					d.addItem(ItemProcessType.REWARD, 1, 1, null, false);
					Thread.sleep(3000);
					LOGGER.info(tag + "loot by the living player: fights " + (_fights - jokes) + ", in party " + d.isInParty() + ".");
					PhantomHunterParty.getInstance().check(c, false, System.currentTimeMillis());
				}
				else
				{
					LOGGER.warning(tag + "loot with a living player: second party not formed: " + again + ".");
				}
			}
		}
		finally
		{
			PhantomNear.getInstance().testPlayer(player);
			PhantomHuntersConfig.HUNTER_LOOT_FIGHTS = false;
			PhantomHuntersConfig.HUNTER_PARTIES = parties;
			_testWorth = 0;
		}
	}

	private boolean lootLeave(String tag, String what, Player leader, Player member, Player looter) throws InterruptedException
	{
		final PhantomHunterParty party = PhantomHunterParty.getInstance();
		final String refused = party.ask(leader, member, true, System.currentTimeMillis());
		LOGGER.info(tag + "loot, " + what + ": party " + leader.getName() + " + " + member.getName() + ": " + (refused == null ? "together" : refused) + ", " + looter.getName() + " gets the item, hunting " + PhantomHunterManager.getInstance().isHunting(looter) + ".");
		if (refused != null)
		{
			return false;
		}
		final Player complainer = looter == leader ? member : leader;
		// Напарник ещё летит к лидеру: пока его нет рядом, предъявить некому.
		await(() -> looter.calculateDistance2D(complainer) <= FIGHT_RANGE, 15000);
		_fightNext = 0;
		final int before = _fights;
		looter.addItem(ItemProcessType.REWARD, 1, 1, null, false);
		final boolean heard = await(() -> _fights > before, 6000);
		final boolean left = await(() -> !complainer.isInParty(), 30000);
		LOGGER.info(tag + "loot, " + what + ": listener heard the item " + heard + ", " + complainer.getName() + " left the party " + left + ", grudge " + PhantomRelations.getInstance().grudge(complainer, looter) + ".");
		party.check(leader, false, System.currentTimeMillis());
		return true;
	}

	private void scamTest(String tag, List<Player> hunters, Player player, Player elder) throws InterruptedException
	{
		PhantomHuntersConfig.HUNTER_SCAMS = true;
		LOGGER.info(tag + "scam: the living player is a candidate " + canDeal(player) + ".");
		final PhantomRelations relations = PhantomRelations.getInstance();
		Player victim = null;
		Player scammer = null;
		for (Player one : hunters)
		{
			if ((one == player) || (one == elder) || !canDeal(one))
			{
				continue;
			}
			if (victim == null)
			{
				victim = one;
			}
			else if (!PhantomHunterClans.clanmates(victim, one) && !relations.isFriend(victim, one) && !relations.isFriend(one, victim))
			{
				scammer = one;
				break;
			}
		}
		if (scammer == null)
		{
			LOGGER.warning(tag + "scam: no free pair of hunters, not checked.");
			return;
		}
		if (((long) victim.getAdena()) < SCAM_PURSE)
		{
			PhantomCompat.addAdena(victim, ItemProcessType.FEE, SCAM_PURSE * 4, null, false);
		}
		final long victimBefore = victim.getAdena();
		final long scammerBefore = scammer.getAdena();
		final boolean done = scam(victim, scammer, System.currentTimeMillis());
		LOGGER.info(tag + "scam: done " + done + ", " + victim.getName() + " " + victimBefore + " -> " + victim.getAdena() + ", " + scammer.getName() + " " + scammerBefore + " -> " + scammer.getAdena() + ", grudge " + relations.grudge(victim, scammer) + ", enemy " + relations.isEnemy(victim, scammer) + ".");
		Thread.sleep(16000);

		// Обычный поиск: мировой кулдаун не даёт второго кидка; с нулевым — кто в городе.
		_nextCheck = 0;
		control(hunters, System.currentTimeMillis());
		final long gapLeft = _scamNext - System.currentTimeMillis();
		LOGGER.info(tag + "scam: next not before " + (gapLeft / 60000) + " min.");

		// Сам поиск идёт только среди тех, кто в городе: на тестовом мире свежих охотников там нет, двоих приводим в Гиран.
		final List<Player> brought = new ArrayList<>();
		for (Player one : hunters)
		{
			if ((one != player) && (one != elder) && (one != victim) && (one != scammer) && canDeal(one) && (brought.isEmpty() || (!PhantomHunterClans.clanmates(brought.get(0), one) && !relations.isFriend(brought.get(0), one) && !relations.isFriend(one, brought.get(0)))))
			{
				one.teleToLocation(new Location(83400, 147943, -3404), true);
				if (((long) one.getAdena()) < SCAM_PURSE)
				{
					PhantomCompat.addAdena(one, ItemProcessType.FEE, SCAM_PURSE * 4, null, false);
				}
				brought.add(one);
				if (brought.size() == 2)
				{
					break;
				}
			}
		}
		Thread.sleep(3000);
		long town = 0;
		for (Player one : hunters)
		{
			if (one.isInsideZone(ZoneId.PEACE) && canDeal(one))
			{
				town++;
			}
		}
		_scamNext = 0;
		lookForScam(hunters, System.currentTimeMillis());
		LOGGER.info(tag + "scam: free hunters in town " + town + ", the search found a pair " + (_scamNext != 0) + ".");
		PhantomHuntersConfig.HUNTER_SCAMS = false;
	}

	static PhantomMoments getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomMoments INSTANCE = new PhantomMoments();
	}
}
