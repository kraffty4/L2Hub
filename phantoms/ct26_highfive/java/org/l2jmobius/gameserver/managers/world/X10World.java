package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/** Общие сервисы High Five при любых рейтах; x10 задаёт начальные настройки отдельных функций. */
public final class X10World
{
	private static final Logger LOGGER = Logger.getLogger(X10World.class.getName());
	private static final String CLOCK = "L2HubX10ActiveSeconds";
	private static volatile boolean enabled;
	private static long savedSeconds;
	private static long startedNanos;
	private static long lastSave;
	private static volatile boolean hasPlayers;

	static synchronized void start()
	{
		L2HubX10Config.load();
		// Общие сервисы доступны при любых рейтах; события, задания и фарм имеют свои флаги.
		if (enabled || !X10Economy.start()) { return; }
		savedSeconds = GlobalVariablesManager.getInstance().getLong(CLOCK, 0);
		startedNanos = System.nanoTime();
		enabled = true;
		try
		{
			X10FarmEvent.start();
		}
		catch (RuntimeException e)
		{
			enabled = false;
			LOGGER.log(Level.SEVERE, "X10 startup failed; keeping the original daily system", e);
			return;
		}
		PhantomCompat.addAttackableKillListener(X10World.class, X10World::kill);
		X10FarmSpots.start();
		X10PlayerTools.start();
		VoicedCommandHandler.getInstance().registerHandler(new IVoicedCommandHandler()
		{
			@Override
			public String[] getCommandList()
			{
				return new String[] {"events"};
			}

			@Override
			public boolean onCommand(String command, Player player, String params)
			{
				show(player, params == null ? "" : params);
				return true;
			}
		});
		ThreadPool.scheduleAtFixedRate(X10World::tick, 10000, 10000);
		Runtime.getRuntime().addShutdownHook(new Thread(X10World::saveClock, "L2Hub-x10-clock"));
		LOGGER.info("X10 world enabled: wallet ready; battle events=" + L2HubX10Config.EVENTS_ENABLED + ", contracts=" + L2HubX10Config.CONTRACTS_ENABLED + ", ordinary coin drop=" + L2HubX10Config.COIN_DROP_ENABLED);
	}

	public static boolean enabled()
	{
		return enabled;
	}

	public static boolean contractsEnabled() { return enabled && L2HubX10Config.CONTRACTS_ENABLED; }

	/** Окно обмена магазина коинов/стиля: покупку проводит кошелёк, а не списание предметов-валют. */
	public static boolean isShopList(int listId)
	{
		return enabled && X10Economy.isShopList(listId);
	}

	public static void shopBuy(Player player, int itemId, long amount)
	{
		player.sendMessage(X10Economy.buy(player, itemId, amount));
	}

	/** Коинов за одну «медаль» награды PvP-боя: 7-минутный бой даёт столько же, сколько ивент x10 (4 × 25 = 100). Самих медалей при x10 нет. */
	public static final int MEDAL_COINS = 25;

	/** Награда PvP-ивента (TvT, DM, CtF) при x10: коины сразу в кошелёк. */
	public static void pvpReward(Player player, int medals)
	{
		if (player == null || medals <= 0) { return; }
		final long coins = (long) medals * MEDAL_COINS;
		if (X10Economy.credit(player, coins, 0, "pvp:" + player.getObjectId() + ":" + UUID.randomUUID()) == 1)
		{
			player.sendMessage(text("Награда за PvP-бой: +", "PvP fight reward: +") + coins + text(" коинов.", " Coins."));
		}
	}

	/** Открыть окно обмена раздела магазина за коины/жетоны (с доски Alt+B). */
	public static void openShop(Player player, String category)
	{
		if (enabled) { X10Economy.open(player, category); }
	}

	public static boolean isAutoPlayToggle(int itemId)
	{
		return enabled && itemId == X10PlayerTools.AUTOPLAY_ITEM;
	}

	public static void autoPlay(Player player, boolean on)
	{
		X10PlayerTools.autoPlay(player, on);
	}

	/** Клиент после ShortcutInit гасит свечение ярлыков; вернуть свечение автобоя, если бой идёт. */
	public static void refreshAutoPlayGlow(Player player)
	{
		if (enabled && player != null) { X10PlayerTools.refreshGlow(player); }
	}

	/** Магазин стиля: аура героя только для вида. */
	public static boolean heroAura(Player player)
	{
		return enabled && X10Style.heroAura(player);
	}

	/** Магазин стиля: видимая заточка оружия (свечение), реальная заточка и статы не меняются. */
	public static int enchantEffect(Player player)
	{
		return enabled ? X10Style.enchantEffect(player) : player.getEnchantEffect();
	}

	static long activeSeconds()
	{
		return savedSeconds + Math.max(0, (System.nanoTime() - startedNanos) / 1000000000L);
	}

	static void saveClock()
	{
		if (enabled)
		{
			GlobalVariablesManager.getInstance().set(CLOCK, activeSeconds());
			GlobalVariablesManager.getInstance().storeMe();
		}
	}

	private static void tick()
	{
		try
		{
			hasPlayers = World.getPlayers().stream().anyMatch(X10World::livePlayer);
			for (Player player : World.getPlayers())
			{
				if (player.isOnline())
				{
					X10FarmEvent.recover(player);
				}
				if (player.isOnline() && !PhantomManager.getInstance().isPhantom(player))
				{
					X10Contracts.notifyUnlock(player);
					X10Economy.syncInventory(player);
					X10Contracts.visit(player);
					if (System.currentTimeMillis() - lastSave > 60000)
					{
						X10Contracts.refreshDailyCount(player);
						player.getVariables().storeMe();
					}
				}
			}
			if (System.currentTimeMillis() - lastSave > 60000)
			{
				saveClock();
				lastSave = System.currentTimeMillis();
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "X10 world tick failed", e);
		}
	}

	private static List<Player> nearby(Player player)
	{
		final List<Player> result = new ArrayList<>();
		for (Player member : player.isInParty() ? player.getParty().getMembers() : List.of(player))
		{
			if (member.isOnline() && !member.isAlikeDead() && (member.getInstanceId() == player.getInstanceId()) && (member.calculateDistance3D(player) <= 1500))
			{
				result.add(member);
			}
		}
		return result;
	}

	private static void kill(Player killer, Attackable mob)
	{
		if (!enabled || (killer == null) || (mob == null) || !mob.isMonster() || mob.getVariables().getBoolean("L2HubX10Event", false))
		{
			return;
		}
		final List<Player> party = nearby(killer);
		for (Player member : party)
		{
			if ((member.getInstanceId() == mob.getInstanceId()) && (member.calculateDistance3D(mob) <= 1500))
			{
				X10Contracts.kill(member, mob, party.size() > 1);
			}
		}
		if (!L2HubX10Config.COIN_DROP_ENABLED || (!hasPlayers && !livePlayer(killer)) || mob.isRaid() || mob.getTemplate().getExp() <= 0 || (ThreadLocalRandom.current().nextInt(10000) >= L2HubX10Config.DROP_CHANCE))
		{
			return;
		}
		party.removeIf(player -> (mob.getLevel() < player.getLevel() - 5) || (player.calculateDistance3D(mob) > 1500));
		if (!party.isEmpty())
		{
			final Player recipient = party.get(ThreadLocalRandom.current().nextInt(party.size()));
			if (X10Economy.credit(recipient, 1, 0, "loot:" + UUID.randomUUID()) == 1)
			{
				recipient.sendMessage(text("Найдена монета ивентов. Кошелёк и описание — Alt+B → Ивенты.", "You found an Event Coin. Wallet and details: Alt+B → Events."));
			}
		}
	}

	private static boolean livePlayer(Player player)
	{
		return player.isOnline() && !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode();
	}

	/** Врезка H5 передаёт точный прирост стека; покупки и склад поручения не продвигают. */
	public static void itemObtained(Player player, ItemProcessType process, int itemId, long count)
	{
		if (player != null && itemId == 57 && count > 0 && (process == ItemProcessType.LOOT || process == ItemProcessType.SWEEP)) { X10PlayerTools.earnedAdena(player, count); }
		// Коины с земли (например, награда чемпиона): рюкзак лишь отражает кошелёк, поэтому поднятое сразу зачисляем в кошелёк.
		if (enabled && player != null && itemId == X10Economy.COIN_ITEM && count > 0 && (process == ItemProcessType.LOOT || process == ItemProcessType.PICKUP) && !PhantomManager.getInstance().isPhantom(player))
		{
			X10Economy.change(player.getObjectId(), count, 0, "pickup:" + UUID.randomUUID(), false);
			X10PlayerTools.earned(player, count, 0);
		}
		if (!contractsEnabled() || (player == null))
		{
			return;
		}
		try
		{
			if (process == ItemProcessType.CRAFT)
			{
				X10Contracts.obtained(player, process, itemId, count);
			}
			else if ((process == ItemProcessType.LOOT) || (process == ItemProcessType.SWEEP))
			{
				for (Player member : nearby(player))
				{
					X10Contracts.obtained(member, process, itemId, count);
				}
			}
		}
		catch (Exception e)
		{
			// Дополнение не должно сорвать штатное получение предмета или крафт.
			LOGGER.log(Level.WARNING, "X10 item progress failed", e);
		}
	}

	public static boolean inPveEvent(Player player)
	{
		return enabled && X10FarmEvent.contains(player);
	}

	public static org.l2jmobius.gameserver.entity.actor.Creature pveTarget(Player player)
	{
		return X10FarmEvent.target(player);
	}

	public static void showContracts(Player player, boolean weekly, String params)
	{
		if (!contractsEnabled()) { player.sendMessage(text("Поручения выключены в настройках Хаба.", "Contracts are disabled in Hub settings.")); return; }
		org.l2jmobius.gameserver.handler.CommunityBoardHandler.getInstance().handleParseCommand("_bbshubevents " + (weekly ? "weekly" : "daily") + (params == null || params.isBlank() ? "" : " " + params), player);
	}

	/** null оставляет существующему обработчику TvT, DM и CtF его команды. */
	public static String board(Player player, String[] args)
	{
		if (!enabled)
		{
			if (args.length > 1 && args[1].equals("spots")) { return text("Фарм-споты доступны при включённых дополнениях High Five x10.", "Farming areas require the High Five x10 additions to be enabled."); }
			return null;
		}
		final String action = args.length > 1 ? args[1] : "";
		if (SetHolder.LEGACY.contains(action))
		{
			return null;
		}
		return fragment(player, args.length > 1 ? java.util.Arrays.copyOfRange(args, 1, args.length) : new String[0], "bypass _bbshubevents", 520);
	}

	public static String farmSpots(Player player, String[] args)
	{
		return "<table width=520 cellpadding=0 cellspacing=0><tr><td align=left>" + X10FarmSpots.html(player, args.length > 0 && args[0].startsWith("_bbs") ? java.util.Arrays.copyOfRange(args, 1, args.length) : args, "bypass _bbshubfarm", 520) + "</td></tr></table>";
	}

	private static void show(Player player, String params)
	{
		org.l2jmobius.gameserver.handler.CommunityBoardHandler.getInstance().handleParseCommand("_bbshubevents" + (params == null || params.isBlank() ? "" : " " + params), player);
	}

	private static String fragment(Player player, String[] args, String prefix, int width)
	{
		final String action = args.length > 0 ? args[0] : "";
		final StringBuilder out = new StringBuilder("<table width=").append(width).append(" cellpadding=0 cellspacing=0><tr><td align=left>").append(navigation(prefix)).append("<br>");
		if (action.equals("daily") || action.equals("weekly"))
		{
			// L2Hub: таблицу фрагмента закрыть и здесь — открытая ломала разметку доски, клиент дорисовывал пустые части ShowBoard как «nullnull».
			if (!contractsEnabled()) { return out.append(text("Поручения выключены в настройках Хаба.", "Contracts are disabled in Hub settings.")).append("</td></tr></table>").toString(); }
			final boolean weekly = action.equals("weekly");
			final String message = X10Contracts.action(player, weekly, args, 1);
			if (!message.isEmpty())
			{
				out.append("<font color=\"LEVEL\">").append(X10Text.escape(message)).append("</font><br>");
			}
			out.append(X10Contracts.html(player, weekly, page(args), prefix + " " + action, width));
		}
		else if (action.equals("spots"))
		{
			out.append(X10FarmSpots.html(player, args, prefix, width));
		}
		else if (action.equals("coins"))
		{
			out.append(X10Economy.balances(player)).append("<br><br>").append(X10Economy.help());
		}
		else if (action.equals("farm") || action.equals("defence") || action.equals("hunt"))
		{
			final X10EventRules.Kind requested = action.equals("defence") ? X10EventRules.Kind.DEFENCE : action.equals("hunt") ? X10EventRules.Kind.HUNT : X10EventRules.Kind.FARM;
			if (args.length > 1)
			{
				out.append("<font color=\"LEVEL\">").append(X10Text.escape(X10FarmEvent.action(player, args[1], requested))).append("</font><br>");
			}
			out.append(X10FarmEvent.html(player, prefix, requested));
		}
		else if (action.equals("style"))
		{
			out.append(X10Style.page(player, args, prefix));
		}
		else if (action.equals("shop") || action.equals("buy"))
		{
			if (action.equals("shop") && args.length > 2 && args[1].equals("open"))
			{
				X10Economy.open(player, args[2]);
			}
			out.append(X10Economy.shop(player, prefix));
		}
		else
		{
			out.append("<font color=\"LEVEL\">").append(text("Ивенты", "Events")).append("</font><br1>")
				.append(X10Economy.balances(player)).append("<br><br>")
				.append(text("Здесь — события, поручения и магазин заработанных в игре монет.", "Events, contracts and a shop for currency earned in game.")).append("<br><br>")
				.append(text("Часы мира: ", "Active world time: ")).append(activeSeconds() / 3600).append(text(" ч. Прогресс сохраняется после перезапуска.", " hours. Progress survives server restarts.")).append("<br><br>")
				.append(X10FarmEvent.html(player, prefix)).append("<br><br>")
				.append("<font color=\"AFAFAF\">").append(text("Отдельные бои игроков против игроков:", "Separate player-versus-player formats:")).append("</font><br1>")
				.append(X10Text.button(text("PvP-арены · TvT / DM / CtF", "PvP arenas · TvT / DM / CtF"), "bypass _bbshubevents legacy", 260));
		}
		return out.append("</td></tr></table>").toString();
	}

	private static String navigation(String prefix)
	{
		return X10Text.tabs(new String[] {text("Ивенты", "Events"), text("Ежедневные", "Daily"), text("Недельные", "Weekly"), text("Валюты", "Currency"), text("Магазин", "Shop")},
			new String[] {prefix, prefix + " daily", prefix + " weekly", prefix + " coins", prefix + " shop"}, 520);
	}

	static String teleportError(Player player)
	{
		if (player.isAlikeDead() || player.isInCombat() || player.isCastingNow() || player.isInDuel() || player.isInOlympiadMode() || PhantomCompat.olympiadRegistered(player) || player.isOnEvent() || player.isRegisteredOnEvent() || player.isInStoreMode() || player.isFishing() || player.isInInstance() || player.isInSiege() || player.isInsideZone(ZoneId.SIEGE) || player.isInsideZone(ZoneId.PVP) || (player.getPvpFlag() > 0) || (PhantomCompat.karma(player) > 0) || player.isCursedWeaponEquipped() || player.isJailed())
		{
			return text("Телепорт сейчас закрыт: закончи бой, событие или другое занятие.", "Teleport is unavailable: finish your combat, event or other activity first.");
		}
		return "";
	}

	static String teleport(Player player, Location location, boolean fee)
	{
		final String blocked = teleportError(player);
		if (!blocked.isEmpty())
		{
			return blocked;
		}
		final int price = fee && (player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL) ? L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE : 0;
		if ((price > 0) && !PhantomCompat.reduceAdena(player, ItemProcessType.FEE, price, player, true))
		{
			return text("На телепорт нужно адены: ", "Teleport requires adena: ") + price;
		}
		player.teleToLocation(location, true);
		return "";
	}

	private static String[] words(String params)
	{
		return params == null || params.isBlank() ? new String[0] : params.strip().split("\\s+");
	}

	private static int number(String[] args, int index)
	{
		try
		{
			return Integer.parseInt(args[index]);
		}
		catch (RuntimeException e)
		{
			return 0;
		}
	}

	private static int page(String[] args)
	{
		return args.length > 1 ? Math.max(0, number(args, args.length - 1)) : 0;
	}

	private static final class SetHolder
	{
		static final java.util.Set<String> LEGACY = java.util.Set.of("legacy", "join", "cancel", "medals");
	}

	private X10World()
	{
	}
}
