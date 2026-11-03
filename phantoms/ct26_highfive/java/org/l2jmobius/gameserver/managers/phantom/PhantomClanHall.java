package org.l2jmobius.gameserver.managers.phantom;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanHallTable;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.itemcontainer.ItemContainer;
import org.l2jmobius.gameserver.entity.residences.AuctionableHall;
import org.l2jmobius.gameserver.entity.residences.ClanHall;
import org.l2jmobius.gameserver.entity.residences.ClanHallAuction;
import org.l2jmobius.gameserver.managers.ClanHallAuctionManager;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: кланхолл клана охотников на High Five (docs/PHANTOMS.md, 4.108, ключ {@code HunterClanHall}; решение автора: клан копит на холл и его прокачку). Механика настоящая, та же, что у живого у Auctioneer и управляющего холлом:
 * <ul>
 * <li>члены клана в городе вносят адену на склад клана, пока не накопят цель ({@link #town}); деньги только из заработка, ничего не появляется из воздуха;</li>
 * <li>глава с 2-го уровня клана ставит у аукциона начальную цену со склада ({@code ClanHallAuction.setBid}); холл достаётся в конце недельного цикла ({@code endAuction});</li>
 * <li>аренду раз в неделю снимает сам Mobius со склада ({@code AuctionableHall.FeeTask}); денег нет — долг, неделя долга — холл отбирается. Отложенная проверка долга у Mobius не срабатывает, поэтому её раз в {@value #DEBT_GAP} мс зовём сами ({@link #debt}, только для кланов охотников);</li>
 * <li>функции — по таблицам управляющего для грейда холла; первый платёж из сумки главы, как у живого, деньги глава берёт со склада ({@link #functions}).</li>
 * </ul>
 * Живого не трогает: ставка — только на аукцион без участников, на чужую ставку охотник не отвечает, живой свободно перебивает охотника; занятый холл не на аукционе. Больше половины холлов охотники не занимают.
 */
final class PhantomClanHall implements PhantomClanHallLogic
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanHall.class.getName());

	/** На сколько недель расходов клан держит запас: и до ставки, и прежде чем брать функцию. */
	private static final int RESERVE_WEEKS = 4;
	/** Меньше не вносят. */
	private static final long MIN_GIFT = 10000;
	/** Склад клана у Mobius — с 1-го уровня, аукцион — со 2-го (Auctioneer). */
	private static final int STOCK_LEVEL = 1;
	private static final int BID_LEVEL = 2;
	private static final long WEEK = 604800000L;
	/** Новая функция — не чаще. */
	private static final long FUNCTION_GAP = 21600000;
	/** Проверка долга — не чаще. */
	private static final long DEBT_GAP = 21600000;
	private static final String HALL_VARIABLE = "L2HubClanHall_";

	/** Функции холла в порядке выбора при равной цене: уровни — как в {@code ClanHallManager}, по грейду холла 0–3. */
	private static final int[] FUNCTIONS =
	{
		ClanHall.FUNC_RESTORE_HP,
		ClanHall.FUNC_RESTORE_MP,
		ClanHall.FUNC_TELEPORT,
		ClanHall.FUNC_RESTORE_EXP,
		ClanHall.FUNC_SUPPORT
	};
	private static final int[][] HP_LEVELS =
	{
		{ 20, 40, 220 },
		{ 40, 100, 160 },
		{ 80, 140, 200, 260 },
		{ 80, 120, 180, 240, 300 }
	};
	private static final int[][] MP_LEVELS =
	{
		{ 5, 10, 25 },
		{ 5, 15, 25 },
		{ 5, 15, 30 },
		{ 5, 15, 30, 40 }
	};
	private static final int[][] EXP_LEVELS =
	{
		{ 5, 10, 25 },
		{ 5, 15, 30 },
		{ 5, 15, 25, 40 },
		{ 15, 25, 35, 50 }
	};
	private static final int[][] SUPPORT_LEVELS =
	{
		{ 1, 2 },
		{ 1, 2, 4 },
		{ 3, 4, 5 },
		{ 3, 5, 7, 8 }
	};
	private static final int[][] TELEPORT_LEVELS =
	{
		{ 1, 2 },
		{ 1, 2 },
		{ 1, 2 },
		{ 1, 2 }
	};

	/**
	 * Следующий шаг функции.
	 * @param added на сколько вырастут расходы клана в неделю
	 */
	private record Upgrade(int type, int level, int fee, long rate, long added)
	{
	}

	private final Map<Integer, Long> _nextFunction = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextDebt = new ConcurrentHashMap<>();
	/** Самопроверка считает казну до монеты: чужие взносы из городских дел на это время закрыты. */
	private volatile boolean _giftsOff;

	PhantomClanHall()
	{
	}

	private static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_CLAN_HALL;
	}

	@Override
	public void life(Clan clan, Player leader, long now)
	{
		if (!enabled() || !ClanHallTable.getInstance().loaded())
		{
			return;
		}
		try
		{
			remember(clan, leader);
			final AuctionableHall hall = ClanHallTable.getInstance().getClanHallByOwner(clan);
			if (hall != null)
			{
				debt(clan, hall, now);
				if (clan.getHideoutId() == hall.getId())
				{
					functions(clan, leader, hall, now);
				}
				return;
			}
			if ((clan.getLevel() >= BID_LEVEL) && (clan.getHideoutId() == 0) && (clan.getAuctionBiddedAt() == 0))
			{
				bid(clan, leader);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Холл появился или пропал: в журнал и в «Летопись» — один раз.
	 */
	private static void remember(Clan clan, Player leader)
	{
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		final String key = HALL_VARIABLE + clan.getId();
		final int known = variables.getInt(key, 0);
		final int current = clan.getHideoutId();
		if (known == current)
		{
			return;
		}
		if (current > 0)
		{
			variables.set(key, current);
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(current);
			final String name = hall == null ? String.valueOf(current) : hall.getName();
			LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + " got the clan hall " + name + " (" + current + "), treasury " + clan.getWarehouse().getAdena() + ".");
			PhantomNews.post("clan", "Клан охотников «" + clan.getName() + "» получил кланхолл " + name, clan.getLeaderName(), leader);
		}
		else
		{
			variables.remove(key);
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(known);
			final String name = hall == null ? String.valueOf(known) : hall.getName();
			LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + " lost the clan hall " + name + " (" + known + "), treasury " + clan.getWarehouse().getAdena() + ".");
			PhantomNews.post("clan", "Клан охотников «" + clan.getName() + "» потерял кланхолл " + name + ": нечем платить", clan.getLeaderName(), leader);
		}
	}

	/**
	 * Долг: настоящая проверка аренды Mobius, она же платит, когда деньги появились, и отбирает холл после недели долга.
	 */
	private void debt(Clan clan, AuctionableHall hall, long now)
	{
		if (hall.getPaid() || (now < _nextDebt.getOrDefault(clan.getId(), 0L)))
		{
			return;
		}
		_nextDebt.put(clan.getId(), now + DEBT_GAP);
		final long before = clan.getWarehouse().getAdena();
		hall.feeNow();
		LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + " owes the rent of " + hall.getName() + " (" + hall.getLease() + "): treasury " + before + " -> " + clan.getWarehouse().getAdena() + ", paid " + hall.getPaid() + ", still the owner " + (clan.getHideoutId() == hall.getId()) + ".");
	}

	/**
	 * Следующая функция, если после неё клану хватает на {@value #RESERVE_WEEKS} недели всех расходов; первый платёж — со склада через сумку главы.
	 */
	private void functions(Clan clan, Player leader, AuctionableHall hall, long now)
	{
		if (!hall.getPaid() || (now < _nextFunction.getOrDefault(clan.getId(), 0L)))
		{
			return;
		}
		final Upgrade next = next(hall);
		final ItemContainer stock = clan.getWarehouse();
		if ((next == null) || (stock.getAdena() < (next.fee + (RESERVE_WEEKS * (burn(hall) + next.added)))))
		{
			return;
		}
		_nextFunction.put(clan.getId(), now + FUNCTION_GAP);
		final Item adena = stock.getItemByItemId(Inventory.ADENA_ID);
		if ((adena == null) || (stock.transferItem(ItemProcessType.TRANSFER, adena.getObjectId(), next.fee, leader.getInventory(), leader, null) == null))
		{
			return;
		}
		if (hall.updateFunctions(leader, next.type, next.level, next.fee, next.rate, hall.getFunction(next.type) == null))
		{
			LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + ", hall " + hall.getName() + ": function " + next.type + " level " + next.level + " for " + next.fee + " per " + (next.rate / 86400000) + " days, treasury " + stock.getAdena() + ", burns " + burn(hall) + " a week.");
		}
		else
		{
			// Плату не приняли: адену — обратно на склад, у главы не остаётся.
			final Item back = leader.getInventory().getAdenaInstance();
			if (back != null)
			{
				leader.getInventory().transferItem(ItemProcessType.TRANSFER, back.getObjectId(), next.fee, stock, leader, null);
			}
		}
	}

	/**
	 * @return следующий шаг, самый дешёвый по недельным расходам; {@code null} — всё на пределе грейда
	 */
	private static Upgrade next(AuctionableHall hall)
	{
		Upgrade best = null;
		for (int type : FUNCTIONS)
		{
			final ClanHall.ClanHallFunction current = hall.getFunction(type);
			for (int level : levels(type)[Math.max(0, Math.min(3, hall.getGrade()))])
			{
				if ((current != null) && (level <= current.getLevel()))
				{
					continue;
				}
				final int fee = fee(type, level);
				final long rate = rate(type);
				final long added = weekly(fee, rate) - (current == null ? 0 : weekly(current.getLease(), current.getRate()));
				if ((best == null) || (added < best.added))
				{
					best = new Upgrade(type, level, fee, rate, added);
				}
				break;
			}
		}
		return best;
	}

	/**
	 * @return расходы холла в неделю: аренда и все функции
	 */
	private static long burn(AuctionableHall hall)
	{
		long total = hall.getLease();
		for (int type : FUNCTIONS)
		{
			final ClanHall.ClanHallFunction function = hall.getFunction(type);
			if (function != null)
			{
				total += weekly(function.getLease(), function.getRate());
			}
		}
		return total;
	}

	private static long weekly(int fee, long rate)
	{
		return Math.round((double) fee * WEEK / Math.max(1, rate));
	}

	private static int[][] levels(int type)
	{
		switch (type)
		{
			case ClanHall.FUNC_RESTORE_HP:
			{
				return HP_LEVELS;
			}
			case ClanHall.FUNC_RESTORE_MP:
			{
				return MP_LEVELS;
			}
			case ClanHall.FUNC_RESTORE_EXP:
			{
				return EXP_LEVELS;
			}
			case ClanHall.FUNC_SUPPORT:
			{
				return SUPPORT_LEVELS;
			}
			default:
			{
				return TELEPORT_LEVELS;
			}
		}
	}

	private static long rate(int type)
	{
		switch (type)
		{
			case ClanHall.FUNC_RESTORE_HP:
			{
				return FeatureConfig.CH_HPREG_FEE_RATIO;
			}
			case ClanHall.FUNC_RESTORE_MP:
			{
				return FeatureConfig.CH_MPREG_FEE_RATIO;
			}
			case ClanHall.FUNC_RESTORE_EXP:
			{
				return FeatureConfig.CH_EXPREG_FEE_RATIO;
			}
			case ClanHall.FUNC_SUPPORT:
			{
				return FeatureConfig.CH_SUPPORT_FEE_RATIO;
			}
			default:
			{
				return FeatureConfig.CH_TELE_FEE_RATIO;
			}
		}
	}

	/**
	 * @return цена шага, как в {@code ClanHallManager}
	 */
	private static int fee(int type, int level)
	{
		switch (type)
		{
			case ClanHall.FUNC_RESTORE_HP:
			{
				switch (level)
				{
					case 20:
						return FeatureConfig.CH_HPREG1_FEE;
					case 40:
						return FeatureConfig.CH_HPREG2_FEE;
					case 80:
						return FeatureConfig.CH_HPREG3_FEE;
					case 100:
						return FeatureConfig.CH_HPREG4_FEE;
					case 120:
						return FeatureConfig.CH_HPREG5_FEE;
					case 140:
						return FeatureConfig.CH_HPREG6_FEE;
					case 160:
						return FeatureConfig.CH_HPREG7_FEE;
					case 180:
						return FeatureConfig.CH_HPREG8_FEE;
					case 200:
						return FeatureConfig.CH_HPREG9_FEE;
					case 220:
						return FeatureConfig.CH_HPREG10_FEE;
					case 240:
						return FeatureConfig.CH_HPREG11_FEE;
					case 260:
						return FeatureConfig.CH_HPREG12_FEE;
					default:
						return FeatureConfig.CH_HPREG13_FEE;
				}
			}
			case ClanHall.FUNC_RESTORE_MP:
			{
				switch (level)
				{
					case 5:
						return FeatureConfig.CH_MPREG1_FEE;
					case 10:
						return FeatureConfig.CH_MPREG2_FEE;
					case 15:
						return FeatureConfig.CH_MPREG3_FEE;
					case 30:
						return FeatureConfig.CH_MPREG4_FEE;
					default:
						return FeatureConfig.CH_MPREG5_FEE;
				}
			}
			case ClanHall.FUNC_RESTORE_EXP:
			{
				switch (level)
				{
					case 5:
						return FeatureConfig.CH_EXPREG1_FEE;
					case 10:
						return FeatureConfig.CH_EXPREG2_FEE;
					case 15:
						return FeatureConfig.CH_EXPREG3_FEE;
					case 25:
						return FeatureConfig.CH_EXPREG4_FEE;
					case 35:
						return FeatureConfig.CH_EXPREG5_FEE;
					case 40:
						return FeatureConfig.CH_EXPREG6_FEE;
					default:
						return FeatureConfig.CH_EXPREG7_FEE;
				}
			}
			case ClanHall.FUNC_SUPPORT:
			{
				switch (level)
				{
					case 1:
						return FeatureConfig.CH_SUPPORT1_FEE;
					case 2:
						return FeatureConfig.CH_SUPPORT2_FEE;
					case 3:
						return FeatureConfig.CH_SUPPORT3_FEE;
					case 4:
						return FeatureConfig.CH_SUPPORT4_FEE;
					case 5:
						return FeatureConfig.CH_SUPPORT5_FEE;
					case 6:
						return FeatureConfig.CH_SUPPORT6_FEE;
					case 7:
						return FeatureConfig.CH_SUPPORT7_FEE;
					default:
						return FeatureConfig.CH_SUPPORT8_FEE;
				}
			}
			default:
			{
				return level == 1 ? FeatureConfig.CH_TELE1_FEE : FeatureConfig.CH_TELE2_FEE;
			}
		}
	}

	/**
	 * Аукционы, на которые клан может поставить: продаёт NPC, холл свободен, ставок нет ни от кого.
	 */
	private static List<ClanHallAuction> open()
	{
		final List<ClanHallAuction> result = new ArrayList<>();
		for (ClanHallAuction auction : ClanHallAuctionManager.getInstance().getAuctions())
		{
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(auction.getItemId());
			if ((auction.getSellerId() == 0) && (auction.getHighestBidderId() == 0) && auction.getBidders().isEmpty() && (hall != null) && (hall.getOwnerId() == 0))
			{
				result.add(auction);
			}
		}
		return result;
	}

	/**
	 * @return охотники заняли или торгуют половину холлов: остальное — живым
	 */
	private static boolean shareTaken()
	{
		int taken = 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (PhantomHunterClans.isHunterClan(clan) && ((clan.getHideoutId() > 0) || (clan.getAuctionBiddedAt() > 0)))
			{
				taken++;
			}
		}
		return (taken * 2) >= ClanHallTable.getInstance().getAllAuctionableClanHalls().size();
	}

	/**
	 * @return куда ставить: лучший грейд, на который хватает казны с запасом аренды, среди равных — по id клана; {@code null} — не на что
	 */
	private static ClanHallAuction choose(Clan clan)
	{
		final long treasury = clan.getWarehouse().getAdena();
		final List<ClanHallAuction> best = new ArrayList<>();
		int bestGrade = -1;
		for (ClanHallAuction auction : open())
		{
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(auction.getItemId());
			if (treasury < (auction.getStartingBid() + (RESERVE_WEEKS * hall.getLease())))
			{
				continue;
			}
			if (hall.getGrade() > bestGrade)
			{
				best.clear();
				bestGrade = hall.getGrade();
			}
			if (hall.getGrade() == bestGrade)
			{
				best.add(auction);
			}
		}
		return best.isEmpty() ? null : best.get(clan.getId() % best.size());
	}

	private static void bid(Clan clan, Player leader)
	{
		if (shareTaken())
		{
			return;
		}
		final ClanHallAuction auction = choose(clan);
		if (auction == null)
		{
			return;
		}
		final long start = auction.getStartingBid();
		final long before = clan.getWarehouse().getAdena();
		auction.setBid(leader, start);
		if (clan.getAuctionBiddedAt() != auction.getId())
		{
			LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + ": the bid on " + auction.getItemName() + " was not taken.");
			return;
		}
		final long days = Math.max(0, (auction.getEndDate() - System.currentTimeMillis()) / 86400000);
		LOGGER.info(PhantomClanHall.class.getSimpleName() + ": clan " + clan.getName() + " bids " + start + " on the clan hall " + auction.getItemName() + " (" + auction.getItemId() + "), treasury " + before + " -> " + clan.getWarehouse().getAdena() + ", ends in " + days + " days.");
		PhantomNews.post("clan", "Клан охотников «" + clan.getName() + "» поставил на кланхолл " + auction.getItemName(), clan.getLeaderName(), leader);
	}

	/**
	 * @return сколько клан хочет иметь в казне: цена самого дешёвого холла с запасом аренды, запас аренды на время торгов, запас расходов и следующая функция — когда холл есть
	 */
	private static long goal(Clan clan)
	{
		final AuctionableHall own = ClanHallTable.getInstance().getClanHallByOwner(clan);
		if (own != null)
		{
			final Upgrade next = next(own);
			return (RESERVE_WEEKS * (burn(own) + (next == null ? 0 : next.added))) + (next == null ? 0 : next.fee);
		}
		if (clan.getAuctionBiddedAt() > 0)
		{
			final ClanHallAuction auction = ClanHallAuctionManager.getInstance().getAuction(clan.getAuctionBiddedAt());
			final AuctionableHall hall = auction == null ? null : ClanHallTable.getInstance().getAuctionableHallById(auction.getItemId());
			return hall == null ? 0 : RESERVE_WEEKS * (long) hall.getLease();
		}
		long goal = 0;
		for (ClanHallAuction auction : open())
		{
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(auction.getItemId());
			final long price = auction.getStartingBid() + (RESERVE_WEEKS * (long) hall.getLease());
			goal = goal == 0 ? price : Math.min(goal, price);
		}
		return goal;
	}

	@Override
	public void town(Player player, PhantomTemplate template, List<String> done)
	{
		if (!enabled() || _giftsOff || !ClanHallTable.getInstance().loaded())
		{
			return;
		}
		final Clan clan = player.getClan();
		if (!PhantomHunterClans.isHunterClan(clan) || (clan.getLevel() < STOCK_LEVEL) || !PhantomHunterClans.isHunter(player) || (PhantomHunterOutfit.want(player, template) != null))
		{
			return;
		}
		try
		{
			final ItemContainer stock = clan.getWarehouse();
			final long missing = goal(clan) - stock.getAdena();
			final long amount = Math.min(missing, (PhantomHunterOutfit.spareAdena(player) * PhantomHuntersConfig.HUNTER_CLAN_HALL_SHARE) / 100);
			final Item adena = player.getInventory().getAdenaInstance();
			if ((amount < MIN_GIFT) || (adena == null) || ((stock.getItemByItemId(Inventory.ADENA_ID) == null) && !stock.validateCapacity(1)))
			{
				return;
			}
			if (player.getInventory().transferItem(ItemProcessType.TRANSFER, adena.getObjectId(), amount, stock, player, null) != null)
			{
				done.add("внёс в казну клана: " + amount);
				LOGGER.info(PhantomClanHall.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") put " + amount + " adena into the treasury of clan " + clan.getName() + ": " + stock.getAdena() + " of " + (stock.getAdena() + (missing - amount)) + ".");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomClanHall.class.getSimpleName() + ": " + player.getName() + ": " + e.getMessage(), e);
		}
	}

	@Override
	public String note(Clan clan)
	{
		if (!enabled())
		{
			return "";
		}
		final AuctionableHall hall = ClanHallTable.getInstance().getClanHallByOwner(clan);
		if (hall != null)
		{
			return tr(", кланхолл ") + hall.getName();
		}
		return clan.getAuctionBiddedAt() > 0 ? tr(", торгуется за кланхолл") : "";
	}

	/**
	 * Самопроверка CLANHALL ({@code HuntersSelfTest}, только на копии базы: деньги членам и складу добавляются на месте, аукцион завершается вручную). Взносы без воздуха; ставка; чужой участник отпугивает; победа и первая аренда; функции; долг и его оплата;
	 * неделя долга — холл отобран и снова на аукционе; ключ выключен — ничего.
	 */
	@Override
	public void selfTest(List<Player> hunters)
	{
		final String tag = PhantomClanHall.class.getSimpleName() + ": SelfTest CLANHALL: ";
		try
		{
			Clan clan = null;
			int bestOnline = 0;
			for (Clan other : ClanTable.getInstance().getClans())
			{
				if (!PhantomHunterClans.isHunterClan(other) || (other.getHideoutId() > 0) || (other.getAuctionBiddedAt() > 0) || (other.getLeader() == null) || (other.getLeader().getPlayer() == null))
				{
					continue;
				}
				int online = 0;
				for (Player hunter : hunters)
				{
					online += hunter.getClan() == other ? 1 : 0;
				}
				if (online > bestOnline)
				{
					bestOnline = online;
					clan = other;
				}
			}
			if ((clan == null) || (bestOnline < 2))
			{
				LOGGER.warning(tag + "needs a hunter clan with two members in the world.");
				return;
			}
			final Player leader = clan.getLeader().getPlayer();
			if (clan.getLevel() < BID_LEVEL)
			{
				clan.changeLevel(BID_LEVEL);
			}
			final ItemContainer stock = clan.getWarehouse();
			final List<Player> members = new ArrayList<>();
			for (Player hunter : hunters)
			{
				if (hunter.getClan() == clan)
				{
					members.add(hunter);
				}
			}
			LOGGER.info(tag + "clan " + clan.getName() + " (level " + clan.getLevel() + "), members in the world " + members.size() + ", treasury " + stock.getAdena() + ", goal " + goal(clan) + ", free auctions " + open().size() + " of " + ClanHallTable.getInstance().getAllAuctionableClanHalls().size() + ".");

			// Ключ выключен: ни взноса, ни ставки.
			PhantomHuntersConfig.HUNTER_CLAN_HALL = false;
			final List<String> offLog = new ArrayList<>();
			final long offBefore = stock.getAdena();
			town(leader, PhantomData.getInstance().getTemplate(leader.getName()), offLog);
			life(clan, leader, System.currentTimeMillis());
			PhantomHuntersConfig.HUNTER_CLAN_HALL = true;
			check(tag, "1. ключ выключен: ничего", offLog.isEmpty() && (stock.getAdena() == offBefore) && (clan.getAuctionBiddedAt() == 0), "");

			// Взносы: деньги переходят, в сумме не прибавляются. Членам — адены на копии с избытком: кто копит на вещь, тот не вносит.
			final long goal = goal(clan);
			for (Player member : members)
			{
				PhantomCompat.addAdena(member, ItemProcessType.REWARD, goal + 1000000000L, null, false);
			}
			long total = stock.getAdena();
			for (Player member : members)
			{
				total += member.getAdena();
			}
			final List<String> gifts = new ArrayList<>();
			for (int round = 0; (round < 10) && (stock.getAdena() < goal); round++)
			{
				for (Player member : members)
				{
					town(member, PhantomData.getInstance().getTemplate(member.getName()), gifts);
				}
			}
			long after = stock.getAdena();
			for (Player member : members)
			{
				after += member.getAdena();
			}
			check(tag, "2. члены клана внесли до цели, адена не появилась из воздуха", (stock.getAdena() >= goal) && (after == total), "взносов " + gifts.size() + ", казна " + stock.getAdena() + ", цель " + goal + ", всего " + total + " -> " + after);
			final List<String> extra = new ArrayList<>();
			town(members.get(0), PhantomData.getInstance().getTemplate(members.get(0).getName()), extra);
			check(tag, "3. цель накоплена: больше не вносят", extra.isEmpty(), extra.toString());
			_giftsOff = true;

			// Чужой участник: на этот аукцион охотник не ставит.
			final ClanHallAuction target = choose(clan);
			if (target == null)
			{
				LOGGER.warning(tag + "needs an auction without bidders.");
				return;
			}
			target.getBidders().put(-1, new ClanHallAuction.Bidder("Living", "LivingClan", target.getStartingBid(), System.currentTimeMillis()));
			final boolean scared = !open().contains(target);
			target.getBidders().remove(-1);
			check(tag, "4. на аукцион с чужой ставкой не ставит", scared && open().contains(target), target.getItemName());

			// Ставка настоящая: адена уходит со склада, запись у аукциона.
			final ClanHallAuction chosen = choose(clan);
			final long before = stock.getAdena();
			final long start = chosen.getStartingBid();
			life(clan, leader, System.currentTimeMillis());
			final boolean bidTaken = (clan.getAuctionBiddedAt() == chosen.getId()) && (chosen.getHighestBidderId() == clan.getId()) && (stock.getAdena() == (before - start));
			check(tag, "5. ставка: " + chosen.getItemName(), bidTaken, "казна " + before + " -> " + stock.getAdena() + ", ставка " + start + ", конец через " + ((chosen.getEndDate() - System.currentTimeMillis()) / 3600000) + " ч");
			life(clan, leader, System.currentTimeMillis());
			check(tag, "6. вторую ставку не делает", stock.getAdena() == (before - start), "");
			check(tag, "7. другие кланы на этот аукцион не ставят", !open().contains(chosen), "");

			// Конец цикла. У живого его ждут до недели; самопроверка зовёт его сразу.
			final AuctionableHall hall = ClanHallTable.getInstance().getAuctionableHallById(chosen.getItemId());
			final long beforeFee = stock.getAdena();
			chosen.endAuction();
			Thread.sleep(3000);
			life(clan, leader, System.currentTimeMillis());
			check(tag, "8. конец аукциона: холл клана, аренда снята", (clan.getHideoutId() == hall.getId()) && (hall.getOwnerId() == clan.getId()) && hall.getPaid() && (stock.getAdena() == (beforeFee - hall.getLease())), hall.getName() + ", грейд " + hall.getGrade() + ", аренда " + hall.getLease() + ", казна " + beforeFee + " -> " + stock.getAdena() + ", оплачено до " + new java.util.Date(hall.getPaidUntil()));
			check(tag, "9. аукциона больше нет", ClanHallAuctionManager.getInstance().getAuction(chosen.getId()) == null, "");
			LOGGER.info(tag + ".clans note: «" + note(clan) + "».");

			// Функции: со склада, через сумку главы; его адена не меняется.
			final long leaderAdena = leader.getAdena();
			stock.addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, 100000000, null, null);
			final long stockFunctions = stock.getAdena();
			final List<String> taken = new ArrayList<>();
			for (int i = 0; i < 8; i++)
			{
				final Upgrade next = next(hall);
				_nextFunction.clear();
				functions(clan, leader, hall, System.currentTimeMillis());
				if (next != null)
				{
					final ClanHall.ClanHallFunction function = hall.getFunction(next.type);
					taken.add(next.type + ":" + next.level + "=" + next.fee + ((function != null) && (function.getLevel() == next.level) ? "" : " НЕТ"));
				}
			}
			Thread.sleep(1000);
			check(tag, "10. функции куплены по таблицам грейда, глава не потратил своего", !taken.isEmpty() && taken.stream().noneMatch(line -> line.endsWith("НЕТ")) && (leader.getAdena() == leaderAdena) && (stock.getAdena() < stockFunctions), taken + ", казна " + stockFunctions + " -> " + stock.getAdena() + ", расходы в неделю " + burn(hall) + ", глава " + leaderAdena + " -> " + leader.getAdena());
			_nextFunction.clear();
			final long reserveBefore = stock.getAdena();
			final Item all = stock.getItemByItemId(Inventory.ADENA_ID);
			if (all != null)
			{
				stock.destroyItemByItemId(ItemProcessType.NONE, Inventory.ADENA_ID, all.getCount(), null, null);
			}
			final Upgrade pending = next(hall);
			final int pendingLevel = pending == null || hall.getFunction(pending.type) == null ? 0 : hall.getFunction(pending.type).getLevel();
			functions(clan, leader, hall, System.currentTimeMillis());
			check(tag, "11. нет запаса: функцию не берёт", (pending == null) || ((hall.getFunction(pending.type) == null ? 0 : hall.getFunction(pending.type).getLevel()) == pendingLevel), "казна была " + reserveBefore);

			// Денег на аренду нет — долг; появились — оплата той же проверкой; неделя долга — холл отобран.
			setRent(hall, System.currentTimeMillis() - 3600000, true);
			hall.feeNow();
			final boolean owes = !hall.getPaid() && (clan.getHideoutId() == hall.getId());
			check(tag, "12. нечем платить: долг, холл пока за кланом", owes, "оплачен " + hall.getPaid() + ", холл " + clan.getHideoutId());
			stock.addItem(ItemProcessType.REWARD, Inventory.ADENA_ID, hall.getLease() * 2L, null, null);
			_nextDebt.clear();
			life(clan, leader, System.currentTimeMillis());
			check(tag, "13. деньги появились: долг оплачен", hall.getPaid() && (hall.getPaidUntil() > System.currentTimeMillis()) && (stock.getAdena() == hall.getLease()), "казна " + stock.getAdena() + ", оплачен " + hall.getPaid());

			final Item rest = stock.getItemByItemId(Inventory.ADENA_ID);
			if (rest != null)
			{
				stock.destroyItemByItemId(ItemProcessType.NONE, Inventory.ADENA_ID, rest.getCount(), null, null);
			}
			setRent(hall, System.currentTimeMillis() - (8 * 86400000L), false);
			_nextDebt.clear();
			life(clan, leader, System.currentTimeMillis());
			life(clan, leader, System.currentTimeMillis());
			final ClanHallAuction reopened = ClanHallAuctionManager.getInstance().getAuction(hall.getId());
			check(tag, "14. неделя долга: холл отобран и снова на аукционе", (clan.getHideoutId() == 0) && (hall.getOwnerId() == 0) && (reopened != null) && (reopened.getHighestBidderId() == 0), "холл " + clan.getHideoutId() + ", хозяин " + hall.getOwnerId() + ", аукцион " + (reopened != null));
			check(tag, "15. функции ушли вместе с холлом", hall.getFunction(ClanHall.FUNC_RESTORE_HP) == null, "");
			LOGGER.info(tag + "done.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
		finally
		{
			_giftsOff = false;
		}
	}

	/**
	 * Самопроверка: подставить срок оплаты, как будто прошло время.
	 */
	private static void setRent(AuctionableHall hall, long paidUntil, boolean paid) throws ReflectiveOperationException
	{
		final Field until = AuctionableHall.class.getDeclaredField("_paidUntil");
		until.setAccessible(true);
		until.setLong(hall, paidUntil);
		final Field flag = AuctionableHall.class.getDeclaredField("_paid");
		flag.setAccessible(true);
		flag.setBoolean(hall, paid);
	}

	private static void check(String tag, String step, boolean ok, String detail)
	{
		LOGGER.info(tag + step + ": " + (ok ? "OK" : "FAIL") + (detail.isEmpty() ? "" : " (" + detail + ")") + ".");
	}
}
