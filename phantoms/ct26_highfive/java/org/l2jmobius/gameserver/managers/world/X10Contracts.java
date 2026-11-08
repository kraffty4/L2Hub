package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomText;
import org.l2jmobius.gameserver.managers.world.X10TaskState.Kind;
import org.l2jmobius.gameserver.managers.world.X10TaskState.Task;

/** Выбор пяти ежедневных и пятнадцать недельных поручений с зачётом пати. */
final class X10Contracts
{
	private static final Logger LOGGER = Logger.getLogger(X10Contracts.class.getName());
	private static final String DAY = "L2HubX10Daily";
	private static final String WEEK = "L2HubX10Weekly";
	private static final Set<Integer> RESOURCES = Set.of(1864, 1865, 1866, 1867, 1868, 1869, 1870, 1871, 1872, 1873, 1874, 1875, 1876, 1877, 1878, 1879, 1880, 1881, 1882, 1883, 1884, 1885, 1886, 1887, 1888, 1889, 1890);
	private static final Map<String, Integer> MATERIALS = Map.of("coal", 1870, "bones", 1872, "iron", 1869, "skin", 1867, "thread", 1868, "varnish", 1865);

	private record Definition(String id, Kind kind, int goal, String ru, String en)
	{
	}

	private static final List<Definition> DAILY = List.of(
		new Definition("hunt", Kind.HUNT, 200, "Охота по уровню", "Level-appropriate hunt"),
		new Definition("spot", Kind.SPOT, 120, "Место дня", "Daily destination"),
		new Definition("route", Kind.SPOT, 120, "Другой маршрут", "A different route"),
		new Definition("group", Kind.GROUP, 120, "Вместе сильнее", "Stronger together"),
		new Definition("explore", Kind.EXPLORE, 1, "Разведка местности", "Scout the area"),
		new Definition("resources", Kind.RESOURCE, 100, "Запасы для города", "Town supplies"),
		new Definition("craft", Kind.CRAFT, 50, "Заказ ремесленника", "Artisan's order"),
		new Definition("champion", Kind.CHAMPION, 2, "Опасные противники", "Dangerous opponents"));

	private static final List<Definition> WEEKLY = List.of(
		new Definition("hunt", Kind.HUNT, 2000, "Большая охота", "The great hunt"),
		new Definition("spot", Kind.SPOT, 900, "Очистить окрестности", "Clear the surroundings"),
		new Definition("group", Kind.GROUP, 700, "Слаженная группа", "A coordinated party"),
		new Definition("champion", Kind.CHAMPION, 20, "Гроза чемпионов", "Champion hunter"),
		new Definition("raid", Kind.RAID, 3, "Рейдовая неделя", "Raid week"),
		new Definition("craft", Kind.CRAFT, 500, "Работа для мастеров", "Work for artisans"),
		new Definition("coal", Kind.RESOURCE, 400, "Уголь для кузницы", "Coal for the forge"),
		new Definition("bones", Kind.RESOURCE, 400, "Кости для мастеров", "Bones for artisans"),
		new Definition("iron", Kind.RESOURCE, 400, "Запасы железа", "Iron supplies"),
		new Definition("skin", Kind.RESOURCE, 400, "Кожевенный заказ", "Leatherworker's order"),
		new Definition("thread", Kind.RESOURCE, 400, "Нити для ткачей", "Thread for weavers"),
		new Definition("varnish", Kind.RESOURCE, 400, "Лак для мастерской", "Varnish for the workshop"),
		new Definition("explore", Kind.EXPLORE, 3, "Три новых места", "Three destinations"),
		new Definition("daily", Kind.DAILY, 10, "Постоянный помощник", "Reliable helper"),
		new Definition("event", Kind.EVENT, 5, "Жизнь сервера", "Life of the server"));

	private static boolean eligible(Player player)
	{
		return X10World.contractsEnabled() && (player != null) && !PhantomManager.getInstance().isPhantom(player);
	}

	private static boolean unlocked(Player player, boolean weekly)
	{
		return player.getLevel() >= (weekly ? L2HubX10Config.WEEKLY_MIN_LEVEL : L2HubX10Config.DAILY_MIN_LEVEL);
	}

	static String locked(Player player, boolean weekly)
	{
		return text(weekly ? "Недельные поручения доступны с " : "Ежедневные поручения доступны с ", weekly ? "Weekly contracts unlock at level " : "Daily contracts unlock at level ")
			+ (weekly ? L2HubX10Config.WEEKLY_MIN_LEVEL : L2HubX10Config.DAILY_MIN_LEVEL) + text(" уровня. Уведомим, когда они откроются.", ". You will be notified when they unlock.");
	}

	static void notifyUnlock(Player player)
	{
		if (!eligible(player)) { return; }
		for (boolean weekly : new boolean[] {false, true})
		{
			final String key = weekly ? "L2HubWeeklyUnlocked" : "L2HubDailyUnlocked";
			if (unlocked(player, weekly) && !player.getVariables().getBoolean(key, false))
			{
				player.getVariables().set(key, true);
				final String message = text(weekly ? "Открыты недельные поручения! Alt+B → Ивенты → Недельные." : "Открыты ежедневные поручения! Alt+B → Ивенты → Ежедневные.", weekly ? "Weekly contracts unlocked! Alt+B → Events → Weekly." : "Daily contracts unlocked! Alt+B → Events → Daily.");
				player.sendMessage(message);
				player.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage(message, 7000));
			}
		}
	}

	private static String today()
	{
		return X10TaskState.day(LocalDateTime.now(), L2HubWorldConfig.WORLD_DAILY_RESET_HOUR, L2HubWorldConfig.WORLD_DAILY_RESET_MINUTE);
	}

	private static X10TaskState state(Player player, boolean weekly)
	{
		final String period = weekly ? X10TaskState.week(today()) : today();
		final String raw = player.getVariables().getString(weekly ? WEEK : DAY, "");
		if (!raw.isEmpty())
		{
			try
			{
				final X10TaskState state = X10TaskState.decode(raw);
				if (state.period.equals(period))
				{
					adapt(player, state, weekly);
					return state;
				}
			}
			catch (RuntimeException e)
			{
				LOGGER.warning("Invalid X10 contract state for " + player.getObjectId() + ": " + e.getMessage());
			}
		}
		final X10TaskState state = new X10TaskState(period);
		final List<HuntSpot> spots = spots(player.getLevel());
		int n = Math.floorMod(player.getObjectId() + period.hashCode(), Math.max(1, spots.size()));
		for (Definition definition : weekly ? WEEKLY : DAILY)
		{
			HuntSpot spot = spots.isEmpty() ? null : spots.get(n++ % spots.size());
			if ((definition.kind == Kind.RESOURCE) || (definition.kind == Kind.CRAFT))
			{
				spot = X10ResourceGuide.find(MATERIALS.getOrDefault(definition.id, 1870), player.getLevel());
			}
			Kind kind = definition.kind;
			int goal = definition.goal;
			if ((kind == Kind.CHAMPION) && (!ChampionMonstersConfig.CHAMPION_ENABLE || (player.getLevel() < ChampionMonstersConfig.CHAMP_MIN_LEVEL)))
			{
				kind = Kind.HUNT;
				goal = weekly ? 1000 : 150;
			}
			final Task task = new Task(definition.id, kind, goal, player.getLevel(), spot == null ? 0 : spot.entry.getX(), spot == null ? 0 : spot.entry.getY(), spot == null ? 0 : spot.entry.getZ());
			task.accepted = weekly;
			state.tasks.put(task.id, task);
		}
		if (!weekly)
		{
			// Награды прежних ежедневок этого дня тоже занимают дневные места.
			final String[] legacy = player.getVariables().getString("L2HubDaily", "").split("\\|", -1);
			if ((legacy.length >= 3) && legacy[0].equals(period))
			{
				int count = 0;
				for (String rawTask : legacy[2].split(";"))
				{
					final String[] fields = rawTask.split(":");
					if ((fields.length > 3) && fields[3].equals("1") && (count < 5))
					{
						final Task old = new Task("legacy_" + (char) ('a' + count++), Kind.HUNT, 1, player.getLevel(), 0, 0, 0);
						old.accepted = true;
						old.claimed = true;
						old.done = 1;
						state.tasks.put(old.id, old);
					}
				}
			}
		}
		put(player, state, weekly);
		return state;
	}

	/** Невзятые и сильно устаревшие цели следуют за уровнем; награды/суточный лимит не сбрасываются. */
	private static void adapt(Player player, X10TaskState state, boolean weekly)
	{
		final List<HuntSpot> choices = spots(player.getLevel());
		if (choices.isEmpty()) { return; }
		int n = 0;
		for (Task old : new ArrayList<>(state.tasks.values()))
		{
			if (old.claimed || old.abandoned || old.id.startsWith("legacy_") || (old.level >= (weekly ? L2HubX10Config.WEEKLY_MIN_LEVEL : L2HubX10Config.DAILY_MIN_LEVEL) && Math.abs(old.level - player.getLevel()) < (old.accepted ? 10 : 5))) { continue; }
			HuntSpot spot = choices.get(n++ % choices.size());
			if (old.kind == Kind.RESOURCE || old.kind == Kind.CRAFT) { spot = X10ResourceGuide.find(MATERIALS.getOrDefault(old.id, 1870), player.getLevel()); }
			final Task fresh = new Task(old.id, old.kind, old.goal, player.getLevel(), spot == null ? 0 : spot.entry.getX(), spot == null ? 0 : spot.entry.getY(), spot == null ? 0 : spot.entry.getZ());
			fresh.accepted = old.accepted; fresh.done = old.done;
			if (old.kind == Kind.EXPLORE) { fresh.done = 0; }
			state.tasks.put(fresh.id, fresh);
		}
		put(player, state, weekly);
	}

	private static List<HuntSpot> spots(int level)
	{
		final List<HuntSpot> spots = new ArrayList<>(HuntSpotData.getInstance().getSpots());
		spots.removeIf(spot -> (level >= 20) && Set.of("TalkingIsland", "ElvenTerritory", "DarkElfTerritory", "OrcTerritory", "DwarvenTerritory", "Kamael").contains(spot.region));
		spots.sort(Comparator.comparingInt(spot -> Math.abs(spot.level - level)));
		if (spots.isEmpty())
		{
			return spots;
		}
		final int distance = Math.abs(spots.get(0).level - level);
		final List<HuntSpot> selected = new ArrayList<>();
		for (HuntSpot spot : spots)
		{
			if ((spot.min <= level + 3) && ((Math.abs(spot.level - level) <= distance + 3) || selected.size() < 3)
				&& selected.stream().noneMatch(other -> Math.hypot(spot.entry.getX() - other.entry.getX(), spot.entry.getY() - other.entry.getY()) < 800))
			{
				selected.add(spot);
				if (selected.size() >= 6)
				{
					break;
				}
			}
		}
		return selected;
	}

	private static void put(Player player, X10TaskState state, boolean weekly)
	{
		player.getVariables().set(weekly ? WEEK : DAY, state.encode());
	}

	static void kill(Player player, Attackable mob, boolean group)
	{
		if (!L2HubX10Config.CONTRACTS_ENABLED) { return; }
		if (!eligible(player))
		{
			return;
		}
		synchronized (player)
		{
			for (boolean weekly : new boolean[] {false, true})
			{
				if (!unlocked(player, weekly)) { continue; }
				final X10TaskState state = state(player, weekly);
				for (Task task : state.tasks.values())
				{
					if ((mob.getLevel() < Math.max(task.level, player.getLevel()) - 5) || !mob.isMonster())
					{
						continue;
					}
					final boolean counts = switch (task.kind)
					{
						case HUNT -> !mob.isRaid();
						case SPOT -> !mob.isRaid() && near(task, mob.getX(), mob.getY(), mob.getZ());
						case GROUP -> group && !mob.isRaid();
						case CHAMPION -> mob.isChampion();
						case RAID -> mob.isRaid();
						default -> false;
					};
					if (counts)
					{
						task.add(1);
					}
				}
				finish(player, state, weekly);
			}
		}
	}

	/** Вызывается в точке фактического добавления, а не по общему количеству в стеке. */
	static void obtained(Player player, ItemProcessType process, int itemId, long count)
	{
		if (!L2HubX10Config.CONTRACTS_ENABLED) { return; }
		if (!eligible(player) || (count <= 0) || ((process != ItemProcessType.LOOT) && (process != ItemProcessType.SWEEP) && (process != ItemProcessType.CRAFT)))
		{
			return;
		}
		final boolean craft = process == ItemProcessType.CRAFT;
		if (craft)
		{
			final var item = org.l2jmobius.gameserver.data.xml.ItemData.getInstance().getTemplate(itemId);
			final boolean equipment = (item != null) && (item.isWeapon() || item.isArmor()) && (item.getCrystalType().getLevel() >= Math.max(1, PhantomCompat.expertiseLevel(player) - 1));
			final boolean material = RESOURCES.contains(itemId) && (itemId >= 1878);
			if (!equipment && !material)
			{
				return;
			}
		}
		if (!craft && !RESOURCES.contains(itemId))
		{
			return;
		}
		synchronized (player)
		{
			for (boolean weekly : new boolean[] {false, true})
			{
				if (!unlocked(player, weekly)) { continue; }
				final X10TaskState state = state(player, weekly);
				for (Task task : state.tasks.values())
				{
					if ((task.kind == Kind.CRAFT) && (craft || RESOURCES.contains(itemId)))
					{
						// Одна операция крафта = десять единиц; пачка сосов не равна тысяче крафтов.
						task.add(craft ? 10 : (int) Math.min(count, 1000000));
					}
					else if (!craft && (task.kind == Kind.RESOURCE) && (!MATERIALS.containsKey(task.id) || (MATERIALS.get(task.id) == itemId)))
					{
						task.add((int) Math.min(count, 1000000));
					}
				}
				finish(player, state, weekly);
			}
		}
	}

	static void event(Player player)
	{
		if (!L2HubX10Config.CONTRACTS_ENABLED) { return; }
		if (!eligible(player) || !unlocked(player, true))
		{
			return;
		}
		synchronized (player)
		{
			final X10TaskState state = state(player, true);
			state.tasks.get("event").add(1);
			finish(player, state, true);
		}
	}

	static void visit(Player player)
	{
		if (!L2HubX10Config.CONTRACTS_ENABLED) { return; }
		if (!eligible(player) || player.isAlikeDead() || player.isInInstance())
		{
			return;
		}
		synchronized (player)
		{
			for (boolean weekly : new boolean[] {false, true})
			{
				if (!unlocked(player, weekly)) { continue; }
				final X10TaskState state = state(player, weekly);
				final Task task = state.tasks.get("explore");
				for (HuntSpot spot : spots(task.level))
				{
					if ((weekly || ((spot.entry.getX() == task.x) && (spot.entry.getY() == task.y))) && (Math.hypot(player.getX() - spot.entry.getX(), player.getY() - spot.entry.getY()) < 300) && (Math.abs(player.getZ() - spot.entry.getZ()) < 500))
					{
						task.visit(spot.entry.getX() + "_" + spot.entry.getY());
					}
				}
				finish(player, state, weekly);
			}
		}
	}

	private static void finish(Player player, X10TaskState state, boolean weekly)
	{
		final boolean auto = player.getVariables().getBoolean("L2HubX10AutoClaim", true);
		if (auto)
		{
			for (Task task : state.tasks.values())
			{
				if (task.accepted && !task.claimed && (task.done >= task.goal))
				{
					claim(player, state, task, weekly);
				}
			}
		}
		put(player, state, weekly);
	}

	private static boolean claim(Player player, X10TaskState state, Task task, boolean weekly)
	{
		if (!task.accepted || task.claimed || (task.done < task.goal))
		{
			return false;
		}
		final int coins = weekly ? L2HubX10Config.WEEKLY_COINS : L2HubX10Config.DAILY_COINS;
		final int credited = X10Economy.credit(player, coins, 0, (weekly ? "weekly:" : "daily:") + state.period + ":" + task.id);
		if (credited < 0)
		{
			return false;
		}
		task.claimed = true;
		put(player, state, weekly);
		player.getVariables().storeMe();
		if (credited == 1)
		{
			player.sendMessage(text("Поручение выполнено: ", "Contract complete: ") + title(task.id, weekly) + ". +" + coins + text(" коинов в рюкзак.", " Coins in your inventory."));
		}
		return true;
	}

	static void refreshDailyCount(Player player)
	{
		if (!L2HubX10Config.CONTRACTS_ENABLED) { return; }
		if (!eligible(player) || !unlocked(player, true))
		{
			return;
		}
		synchronized (player)
		{
			final X10TaskState state = state(player, true);
			final int count = X10Economy.dailyClaims(player, state.period);
			if (count >= 0)
			{
				final Task task = state.tasks.get("daily");
				task.add(Math.max(0, count - task.done));
			}
			final int events = X10Economy.eventClaims(player, state.period);
			if (events >= 0)
			{
				final Task task = state.tasks.get("event");
				task.add(Math.max(0, events - task.done));
			}
			finish(player, state, true);
		}
	}

	static String action(Player player, boolean weekly, String[] args, int offset)
	{
		if (!eligible(player))
		{
			return text("Поручения выключены.", "Contracts are disabled.");
		}
		if (!unlocked(player, weekly)) { return locked(player, weekly); }
		synchronized (player)
		{
			final String action = args.length > offset ? args[offset] : "";
			final String id = args.length > offset + 1 ? args[offset + 1] : "";
			final X10TaskState state = state(player, weekly);
			String message = "";
			if (action.equals("take"))
			{
				message = !weekly && state.accept(id) ? text("Поручение взято.", "Contract accepted.") : text("Нельзя взять: уже взято или достигнут лимит пяти на день.", "Cannot accept: already accepted or the daily limit of five has been reached.");
			}
			else if (action.equals("abandon"))
			{
				message = !weekly && state.abandon(id) ? text("Поручение отменено. Взятое место из пяти на сегодня остаётся использованным.", "Contract abandoned. Its place in today's limit of five remains used.") : text("Нельзя отменить это поручение.", "This contract cannot be abandoned.");
			}
			else if (action.equals("claim"))
			{
				final Task task = state.tasks.get(id);
				message = (task != null) && claim(player, state, task, weekly) ? text("Награда в кошельке.", "Reward added to your wallet.") : text("Награда ещё не готова или уже получена.", "The reward is not ready or has already been claimed.");
			}
			else if (action.equals("auto"))
			{
				player.getVariables().set("L2HubX10AutoClaim", !player.getVariables().getBoolean("L2HubX10AutoClaim", true));
			}
			else if (action.equals("follow"))
			{
				if (state.tasks.containsKey(id)) { player.getVariables().set("L2HubTrackedContract", (weekly ? "week:" : "day:") + id); player.getVariables().set("L2HubProgressHud", true); }
			}
			else if (action.equals("go"))
			{
				final Task task = state.tasks.get(id);
				if ((task == null) || !task.accepted || task.claimed || ((task.x == 0) && (task.y == 0)))
				{
					message = text("Сначала возьми подходящее поручение.", "Accept an available contract first.");
				}
				else
				{
					message = X10World.teleportError(player);
					if (message.isEmpty())
					{
						Location location = new Location(task.x, task.y, task.z);
						if ((task.kind == Kind.EXPLORE) && weekly)
						{
							for (HuntSpot spot : spots(task.level))
							{
								if (!(";" + task.visited).contains(";" + spot.entry.getX() + "_" + spot.entry.getY() + ";"))
								{
									location = spot.entry;
									break;
								}
							}
						}
						message = X10World.teleport(player, location, true);
					}
				}
			}
			finish(player, state, weekly);
			put(player, state, weekly);
			player.getVariables().storeMe();
			return message;
		}
	}

	static String html(Player player, boolean weekly, int page, String prefix, int width)
	{
		if (!eligible(player))
		{
			return text("Поручения выключены.", "Contracts are disabled.");
		}
		if (!unlocked(player, weekly)) { return locked(player, weekly); }
		synchronized (player)
		{
			final X10TaskState state = state(player, weekly);
			finish(player, state, weekly);
			final List<Task> tasks = state.tasks.values().stream().filter(task -> !task.id.startsWith("legacy_")).toList();
			final int size = width > 300 ? 4 : 2;
			final int pages = Math.max(1, (tasks.size() + size - 1) / size);
			final int current = Math.clamp(page, 0, pages - 1);
			final boolean auto = player.getVariables().getBoolean("L2HubX10AutoClaim", true);
			final StringBuilder out = new StringBuilder("<font color=\"LEVEL\">").append(text(weekly ? "Еженедельные поручения" : "Ежедневные поручения", weekly ? "Weekly contracts" : "Daily contracts")).append("</font><br1>");
			out.append(weekly ? text("15 активных; новая неделя в понедельник, ", "15 active; new week on Monday, ") : text("Взято сегодня: ", "Accepted today: ") + state.taken() + "/5. " + text("Новый день: ", "New day: ")).append(WorldDaily.resetTime()).append(".<br1>");
			out.append(text("Период: ", "Period: ")).append(state.period).append("<br1>").append(X10Economy.balances(player)).append("<br><table width=").append(width).append(" cellpadding=3>");
			for (Task task : tasks.subList(current * size, Math.min(tasks.size(), (current + 1) * size)))
			{
				out.append("<tr><td><font color=\"LEVEL\">").append(title(task.id, weekly)).append("</font> ").append(task.done).append('/').append(task.goal).append("<br1>")
					.append(text("Уровень задания: ", "Contract level: ")).append(task.level).append("<br1>").append(description(task)).append("<br1>")
					.append(text("Награда: ", "Reward: ")).append(weekly ? L2HubX10Config.WEEKLY_COINS : L2HubX10Config.DAILY_COINS).append(text(" коинов", " Coins")).append("<br1>");
				if (task.abandoned)
				{
					out.append(text("Отменено на сегодня", "Abandoned for today"));
				}
				else if (task.claimed)
				{
					out.append(text("Получено", "Claimed"));
				}
				else if (!task.accepted)
				{
					if (state.taken() < X10TaskState.DAILY_LIMIT)
					{
						out.append(X10Text.button(text("Взять", "Accept"), prefix + " take " + task.id + " " + current, 90));
					}
				}
				else if (task.done >= task.goal)
				{
					out.append(X10Text.button(text("Получить", "Claim"), prefix + " claim " + task.id + " " + current, 100));
				}
				else if ((task.kind != Kind.RAID) && (task.kind != Kind.DAILY) && (task.kind != Kind.EVENT) && ((task.x != 0) || (task.y != 0)))
				{
					final int fee = player.getLevel() > org.l2jmobius.gameserver.config.PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE : 0;
					out.append(X10Text.button(text("Телепорт", "Teleport") + (fee > 0 ? " (" + fee + " A)" : text(" (бесплатно)", " (free)")), prefix + " go " + task.id + " " + current, 200));
				}
				if (task.accepted && !task.claimed)
				{
					out.append("<table width=260><tr><td>").append(X10Text.button(text("Следить", "Track"), prefix + " follow " + task.id + " " + current, 110)).append("</td><td>");
					if (!weekly) { out.append(X10Text.button(text("Отказаться", "Abandon"), prefix + " abandon " + task.id + " " + current, 110)); }
					out.append("</td></tr></table>");
				}
				out.append("<br></td></tr>");
			}
			out.append("</table>").append(X10Text.pager(current, pages, prefix + " page ", width));
			out.append("<br>").append(X10Text.button(text("Автонаграда: ", "Auto-claim: ") + text(auto ? "вкл." : "выкл.", auto ? "on" : "off"), prefix + " auto", 170));
			out.append("<br1>").append(X10Text.button(text("Обновить", "Refresh"), prefix + " page " + current, 170));
			return out.append("<br1><font color=\"AFAFAF\">").append(text("В пати убийства рядом засчитываются всем, включая саппортов. Прогресс после взятия. Покупка, обмен, склад и подбор выброшенных ресурсов сбором не считаются.", "Nearby party kills count for everyone, including support classes. Progress starts after acceptance. Buying, trading, warehouse transfers and picking up discarded resources do not count as gathering.")).append("</font>").toString();
		}
	}

	private static String title(String id, boolean weekly)
	{
		for (Definition definition : weekly ? WEEKLY : DAILY)
		{
			if (definition.id.equals(id))
			{
				return text(definition.ru, definition.en);
			}
		}
		return X10Text.escape(id);
	}

	static String summary(Player player)
	{
		if (!eligible(player) || !unlocked(player, false)) { return ""; }
		synchronized (player)
		{
			final String tracked = player.getVariables().getString("L2HubTrackedContract", "");
			final boolean weekly = tracked.startsWith("week:") && unlocked(player, true);
			final String selected = tracked.contains(":") ? tracked.substring(tracked.indexOf(':') + 1) : "";
			final X10TaskState state = state(player, weekly);
			final StringBuilder out = new StringBuilder();
			final List<Task> active = state.tasks.values().stream().filter(t -> t.accepted && !t.claimed).toList();
			for (Task task : active.stream().sorted(java.util.Comparator.comparingInt(t -> t.id.equals(selected) ? 0 : 1)).limit(2).toList())
			{
				if (!out.isEmpty()) { out.append("\n"); }
				out.append(title(task.id, weekly)).append(": ").append(task.done).append('/').append(task.goal);
			}
			return out.toString();
		}
	}

	private static String description(Task task)
	{
		final String base = switch (task.kind)
		{
			case HUNT -> text("Побеждай обычных мобов своего уровня, соло или с пати.", "Defeat suitable regular monsters, solo or with a party.");
			case SPOT -> text("Побеждай мобов в указанной локации, соло или с пати.", "Defeat monsters at the listed destination, solo or with a party.");
			case GROUP -> text("Убийства ближайшей пати. Подходят люди, охотники и напарники.", "Nearby party kills. Players, hunters and companions all count.");
			case CHAMPION -> text("Побеждай чемпионов своего уровня. Убийства пати тоже засчитываются.", "Defeat suitable champions. Party kills count too.");
			case RAID -> text("Победи рейд-боссов не ниже уровня задания − 5 с ближайшей пати.", "Defeat raid bosses at least contract level minus 5 with a nearby party.");
			case RESOURCE -> text("Добывай ресурсы охотой или спойлом после начала поручения. ", "Obtain materials through hunting or spoil after the contract starts. ") + (MATERIALS.containsKey(task.id) ? org.l2jmobius.gameserver.data.xml.ItemData.getInstance().getTemplate(MATERIALS.get(task.id)).getName() : text("Подходят обычные ресурсы.", "Common materials count."));
			case CRAFT -> text("Изготавливай снаряжение своего ранга (или на ранг ниже) либо обработанные ресурсы: сталь, кокс и подобные. Операция = 10 прогресса. Любой класс может вместо крафта добывать ресурсы охотой/спойлом: 1 ресурс = 1 прогресс. Соски не считаются.", "Craft equipment of your grade (or one grade lower), or processed materials such as Steel and Cokes. One operation gives 10 progress. Any class may instead hunt/spoil materials: one material gives one progress. Shots do not count.");
			case EXPLORE -> text("Посети подходящее место. Для недельного задания нужны три разные точки; кнопка выбирает следующую.", "Visit a suitable destination. The weekly contract requires three different locations; the button selects the next one.");
			case DAILY -> text("Заверши и получи награды десяти ежедневных поручений за эту неделю.", "Complete and claim ten daily contracts during this week.");
			case EVENT -> text("Полноценно участвуй в пяти событиях x10. Простая запись не считается.", "Fully participate in five x10 events. Signing up alone does not count.");
		};
		if ((task.kind == Kind.RAID) || (task.kind == Kind.DAILY) || (task.kind == Kind.EVENT))
		{
			return base;
		}
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.entry.getX() == task.x) && (spot.entry.getY() == task.y))
			{
				return base + "<br1>" + text("Локация: ", "Location: ") + X10Text.escape(PhantomText.spotName(spot.name)) + " (" + spot.min + "–" + spot.max + text(" ур.)", " lvl)");
			}
		}
		return base + "<br1>" + text("Локация недоступна; выбери другое поручение.", "Destination unavailable; choose another contract.");
	}

	private static boolean near(Task task, int x, int y, int z)
	{
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.entry.getX() == task.x) && (spot.entry.getY() == task.y))
			{
				return (Math.hypot(x - task.x, y - task.y) <= spot.radius * 1.5) && (Math.abs(z - task.z) < 800);
			}
		}
		return false;
	}

	private X10Contracts()
	{
	}
}
