package org.l2jmobius.gameserver.managers.world;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.NpcHtmlMessage;

/**
 * L2Hub: поручения дня (docs/WORLD.md, раздел 1; {@code WorldDaily} в L2HubWorld.ini). У каждого персонажа три задания на день по его уровню:
 * <ul>
 * <li>«Охота» — всегда: убить {@code WorldDailyHuntKills} мобов не слабее своего уровня − {@value #HUNT_LEVEL_GAP};</li>
 * <li>два из трёх, на день наугад (у персонажа свои): «Место дня» — {@code WorldDailySpotKills} мобов на споте охотников по уровню (из окна — телепорт туда),
 * «Чемпион» — убить чемпиона (если чемпионы включены), «Вместе с охотником» — {@code WorldDailyHunterPartyKills} мобов в пати с охотником-фантомом.</li>
 * </ul>
 * В пати засчитываются убийства всей пати рядом с персонажем (как делится опыт). Задание выполнено — сразу награда: {@code WorldDailyAdena} × уровень² адены
 * и {@code WorldDailyExpPercent} % опыта до следующего уровня; все три — бонус. Новый день — в {@code WorldDailyResetTime}.
 * Окно — {@code .daily} ({@link WorldDailyCommand}). Задания и прогресс — переменная персонажа {@value #VARIABLE}.
 * Подключается без врезок в Mobius: слушатели глобальных событий и регистрация команды — в {@link #start}, его зовёт {@code PhantomManager.onServerStart}.
 */
public final class WorldDaily
{
	private static final Logger LOGGER = Logger.getLogger(WorldDaily.class.getName());

	/** Переменная персонажа: день, бонус и задания. */
	private static final String VARIABLE = "L2HubDaily";
	/** Член пати дальше этого от убитого — не в счёт (как делится опыт пати). */
	private static final int PARTY_RANGE = 1500;
	/** «Охота» и «Вместе с охотником»: моб не слабее своего уровня минус это. */
	private static final int HUNT_LEVEL_GAP = 5;
	/** «Место дня»: убит не дальше радиуса спота × это от точки входа. */
	private static final double SPOT_RANGE = 1.5;
	/** «Место дня» с этого уровня — не в стартовых зонах рас. */
	private static final int LEAVE_HOME_LEVEL = 20;
	private static final Set<String> HOME_REGIONS = Set.of("TalkingIsland", "ElvenTerritory", "DarkElfTerritory", "OrcTerritory", "DwarvenTerritory", "Kamael");
	/** Бонус за три задания: свиток заточки брони по грейду — D, C, B, A, S (у S80 и S84 — S). */
	private static final int[] ARMOR_SCROLLS =
	{
		956,
		952,
		948,
		730,
		960
	};
	/** Сообщать прогресс на этих процентах. */
	private static final int[] PROGRESS_STEPS =
	{
		25,
		50,
		75
	};

	/** Вид задания. */
	public enum Kind
	{
		HUNT("Охота"),
		SPOT("Место дня"),
		CHAMPION("Чемпион"),
		HUNTER_PARTY("Вместе с охотником");

		final String title;

		Kind(String title)
		{
			this.title = title;
		}
	}

	/** «Место дня» не задано. */
	static final int NO_SPOT = Integer.MIN_VALUE;

	/** Задание дня. */
	static final class Task
	{
		final Kind kind;
		final int need;
		int done;
		boolean rewarded;
		/**
		 * «Место дня»: точка входа спота ({@link HuntSpot#entry}) — по ней спот находится и после пересборки списка спотов (номер в списке после неё
		 * указывал бы на другой спот; docs/PHANTOMS.md, 4.47); {@link #NO_SPOT} — не спот.
		 */
		int spotX = NO_SPOT;
		int spotY = NO_SPOT;
		/** Найденный спот и список, в котором он найден: список пересобрали — искать заново. */
		HuntSpot cached;
		List<HuntSpot> cachedIn;

		Task(Kind kind, int need)
		{
			this.kind = kind;
			this.need = need;
		}
	}

	/** Задания персонажа на день. */
	static final class Day
	{
		final String date;
		final List<Task> tasks = new ArrayList<>();
		boolean bonus;

		Day(String date)
		{
			this.date = date;
		}

		int rewarded()
		{
			int count = 0;
			for (Task task : tasks)
			{
				count += task.rewarded ? 1 : 0;
			}
			return count;
		}
	}

	private final Map<Integer, Day> _days = new ConcurrentHashMap<>();
	private boolean _started;

	private WorldDaily()
	{
	}

	/**
	 * Читает L2HubWorld.ini; включено — слушатели убийств и входа в игру. Команда {@code .daily} есть всегда: у нового персонажа она на панели (макрос), а выключенные поручения она так и называет.
	 */
	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		L2HubWorldConfig.load();
		// Кланы малого мира — не поручения: и при выключенных поручениях.
		SmallWorldClans.apply();
		// Без команды текст макроса «.daily» ушёл бы в общий чат.
		VoicedCommandHandler.getInstance().registerHandler(new WorldDailyCommand());
		if (!L2HubWorldConfig.WORLD_DAILY)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}

		PhantomCompat.addAttackableKillListener(this, this::onKill);
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		LOGGER.info(getClass().getSimpleName() + ": daily tasks on (.daily), new day at " + String.format("%02d:%02d", L2HubWorldConfig.WORLD_DAILY_RESET_HOUR, L2HubWorldConfig.WORLD_DAILY_RESET_MINUTE) + ".");
	}

	public boolean isEnabled()
	{
		return _started && L2HubWorldConfig.WORLD_DAILY;
	}

	// ---------- События ----------

	private void onKill(Player killer, Attackable target)
	{
		final Attackable mob = target;
		if ((killer == null) || (mob == null))
		{
			return;
		}

		final Party party = killer.getParty();
		final List<Player> members = party == null ? List.of(killer) : party.getMembers();
		boolean hunter = false;
		if (party != null)
		{
			for (Player member : members)
			{
				if (PhantomHunterManager.getInstance().isHunter(member))
				{
					hunter = true;
					break;
				}
			}
		}
		for (Player member : members)
		{
			if ((member == null) || !member.isOnline() || PhantomManager.getInstance().isPhantom(member) || ((member != killer) && (member.calculateDistance3D(mob) > PARTY_RANGE)))
			{
				continue;
			}
			count(member, mob, hunter);
		}
	}

	private void onLogin(Player player)
	{
		if ((player == null) || PhantomManager.getInstance().isPhantom(player))
		{
			return;
		}
		// После приветствий сервера, чтобы не утонуло в них.
		ThreadPool.schedule(() ->
		{
			if (!player.isOnline())
			{
				return;
			}
			final Day day = day(player);
			final int done = day.rewarded();
			player.sendMessage(done >= day.tasks.size() ? "Поручения дня выполнены. Новые — в " + resetTime() + "." : "Поручения дня: выполнено " + done + " из " + day.tasks.size() + " — команда .daily.");
		}, 8000);
	}

	/**
	 * Засчитать убийство персонажу.
	 * @param hunter в пати убившего есть охотник-фантом
	 */
	private void count(Player player, Attackable mob, boolean hunter)
	{
		final Day day = day(player);
		synchronized (day)
		{
			boolean changed = false;
			for (Task task : day.tasks)
			{
				if (task.rewarded || !counts(task, player, mob, hunter))
				{
					continue;
				}

				task.done++;
				changed = true;
				if (task.done >= task.need)
				{
					complete(player, day, task);
					continue;
				}
				for (int step : PROGRESS_STEPS)
				{
					if ((task.need >= 8) && (task.done == (((task.need * step) + 99) / 100)))
					{
						player.sendMessage("Поручение «" + task.kind.title + "»: " + task.done + " из " + task.need + ".");
					}
				}
			}
			if (changed)
			{
				player.getVariables().set(VARIABLE, encode(day));
			}
		}
	}

	private static boolean counts(Task task, Player player, Attackable mob, boolean hunter)
	{
		switch (task.kind)
		{
			case HUNT:
			{
				return mob.getLevel() >= (player.getLevel() - HUNT_LEVEL_GAP);
			}
			case SPOT:
			{
				final HuntSpot spot = spot(task);
				return (spot != null) && (Math.hypot(mob.getX() - spot.entry.getX(), mob.getY() - spot.entry.getY()) <= (spot.radius * SPOT_RANGE)) && (Math.abs(mob.getZ() - spot.entry.getZ()) < 1000);
			}
			case CHAMPION:
			{
				return mob.isChampion();
			}
			case HUNTER_PARTY:
			{
				return hunter && (mob.getLevel() >= (player.getLevel() - HUNT_LEVEL_GAP));
			}
		}
		return false;
	}

	/**
	 * Задание выполнено: адена и опыт; все задания дня — бонус.
	 */
	private void complete(Player player, Day day, Task task)
	{
		task.rewarded = true;
		final int level = player.getLevel();
		final long adena = (long) L2HubWorldConfig.WORLD_DAILY_ADENA * level * level;
		final long exp = expPercent(level, L2HubWorldConfig.WORLD_DAILY_EXP_PERCENT);
		if (adena > 0)
		{
			PhantomCompat.addAdena(player, ItemProcessType.REWARD, adena, player, true);
		}
		if (exp > 0)
		{
			player.addExpAndSp(exp, 0);
		}
		player.sendPacket(new ExShowScreenMessage("Поручение выполнено: " + task.kind.title, 5000));
		player.sendMessage("Поручение «" + task.kind.title + "» выполнено: +" + adena + " адены" + (exp > 0 ? ", +" + exp + " опыта" : "") + ". Осталось: " + (day.tasks.size() - day.rewarded()) + ".");
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + level + ") done " + task.kind + " (" + task.need + "): +" + adena + " adena, +" + exp + " exp.");

		if ((day.rewarded() >= day.tasks.size()) && !day.bonus && L2HubWorldConfig.WORLD_DAILY_BONUS)
		{
			day.bonus = true;
			final int grade = Math.min(ARMOR_SCROLLS.length, PhantomCompat.expertiseLevel(player));
			if ((grade > 0) && (level >= LEAVE_HOME_LEVEL))
			{
				final int scroll = ARMOR_SCROLLS[grade - 1];
				PhantomCompat.addItem(player, ItemProcessType.REWARD, scroll, 1, player);
				player.sendMessage("Все поручения дня выполнены! Бонус: " + ItemData.getInstance().getTemplate(scroll).getName() + ".");
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " did all daily tasks: bonus " + ItemData.getInstance().getTemplate(scroll).getName() + ".");
			}
			else if (adena > 0)
			{
				PhantomCompat.addAdena(player, ItemProcessType.REWARD, adena, player, true);
				player.sendMessage("Все поручения дня выполнены! Бонус: +" + adena + " адены.");
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " did all daily tasks: bonus " + adena + " adena.");
			}
			player.sendPacket(new ExShowScreenMessage("Все поручения дня выполнены!", 6000));
		}
		player.getVariables().set(VARIABLE, encode(day));
		player.getVariables().storeMe();
	}

	/**
	 * @return столько процентов опыта от текущего уровня до следующего
	 */
	static long expPercent(int level, int percent)
	{
		final ExperienceData data = ExperienceData.getInstance();
		if ((percent <= 0) || ((level + 1) >= data.getMaxLevel()))
		{
			return 0;
		}
		return ((data.getExpForLevel(level + 1) - data.getExpForLevel(level)) * percent) / 100;
	}

	// ---------- День ----------

	/**
	 * Задания персонажа на сегодня: из памяти, из переменной персонажа или новые.
	 */
	Day day(Player player)
	{
		final String today = today();
		Day day = _days.get(player.getObjectId());
		if ((day == null) || !day.date.equals(today))
		{
			day = decode(player.getVariables().getString(VARIABLE, ""));
			if ((day == null) || !day.date.equals(today))
			{
				day = generate(player, today);
				player.getVariables().set(VARIABLE, encode(day));
				LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") gets daily tasks: " + describe(day) + ".");
			}
			_days.put(player.getObjectId(), day);
		}
		return day;
	}

	/**
	 * Новые задания: «Охота» и два других наугад — у персонажа на день свои (зерно — персонаж и дата).
	 */
	private static Day generate(Player player, String date)
	{
		final Random rnd = new Random(Objects.hash(player.getObjectId(), date));
		final Day day = new Day(date);
		day.tasks.add(new Task(Kind.HUNT, L2HubWorldConfig.WORLD_DAILY_HUNT_KILLS));

		final List<Task> pool = new ArrayList<>();
		final HuntSpot spot = pickSpot(player, rnd);
		if (spot != null)
		{
			final Task task = new Task(Kind.SPOT, L2HubWorldConfig.WORLD_DAILY_SPOT_KILLS);
			task.spotX = spot.entry.getX();
			task.spotY = spot.entry.getY();
			pool.add(task);
		}
		if (ChampionMonstersConfig.CHAMPION_ENABLE && (player.getLevel() >= (ChampionMonstersConfig.CHAMP_MIN_LEVEL - 3)) && (player.getLevel() <= (ChampionMonstersConfig.CHAMP_MAX_LEVEL + 3)))
		{
			pool.add(new Task(Kind.CHAMPION, L2HubWorldConfig.WORLD_DAILY_CHAMPIONS));
		}
		if (PhantomsConfig.PHANTOMS_ENABLED && !PhantomHunterManager.getInstance().views().isEmpty())
		{
			pool.add(new Task(Kind.HUNTER_PARTY, L2HubWorldConfig.WORLD_DAILY_HUNTER_PARTY_KILLS));
		}
		Collections.shuffle(pool, rnd);
		day.tasks.addAll(pool.subList(0, Math.min(2, pool.size())));
		return day;
	}

	/**
	 * «Место дня»: спот охотников с мобами на уровень-четыре ниже и до уровня выше своего; с {@value #LEAVE_HOME_LEVEL}-го — не в стартовых зонах.
	 * @return спот или {@code null}
	 */
	private static HuntSpot pickSpot(Player player, Random rnd)
	{
		final int level = player.getLevel();
		final List<HuntSpot> spots = HuntSpotData.getInstance().getSpots();
		final List<Integer> fitting = new ArrayList<>();
		for (int gap = 0; (gap <= 4) && fitting.isEmpty(); gap += 2)
		{
			for (int i = 0; i < spots.size(); i++)
			{
				final HuntSpot spot = spots.get(i);
				if ((spot.level >= (level - 4 - gap)) && (spot.level <= (level + 1)) && (spot.max <= (level + 3 + gap)) && ((level < LEAVE_HOME_LEVEL) || !HOME_REGIONS.contains(spot.region)))
				{
					fitting.add(i);
				}
			}
		}
		return fitting.isEmpty() ? null : spots.get(fitting.get(rnd.nextInt(fitting.size())));
	}

	/**
	 * @return спот задания «Место дня» (по точке входа) или {@code null} — не спот или такого спота больше нет
	 */
	static HuntSpot spot(Task task)
	{
		if (task.spotX == NO_SPOT)
		{
			return null;
		}
		final List<HuntSpot> spots = HuntSpotData.getInstance().getSpots();
		if (task.cachedIn != spots)
		{
			task.cachedIn = spots;
			task.cached = null;
			for (HuntSpot spot : spots)
			{
				if ((spot.entry.getX() == task.spotX) && (spot.entry.getY() == task.spotY))
				{
					task.cached = spot;
					break;
				}
			}
		}
		return task.cached;
	}

	/**
	 * @return сегодняшний день поручений: до {@code WorldDailyResetTime} — ещё вчерашний
	 */
	static String today()
	{
		return LocalDateTime.now().minusHours(L2HubWorldConfig.WORLD_DAILY_RESET_HOUR).minusMinutes(L2HubWorldConfig.WORLD_DAILY_RESET_MINUTE).toLocalDate().toString();
	}

	static String resetTime()
	{
		return String.format("%02d:%02d", L2HubWorldConfig.WORLD_DAILY_RESET_HOUR, L2HubWorldConfig.WORLD_DAILY_RESET_MINUTE);
	}

	// ---------- Хранение: «2026-10-21|B0|HUNT:100:37:0:-1;SPOT:50:0:0:81234,-150012» (у «Места дня» — точка входа спота; прежние записи — номер в списке) ----------

	private static String encode(Day day)
	{
		final StringBuilder sb = new StringBuilder(day.date).append("|B").append(day.bonus ? 1 : 0).append('|');
		for (int i = 0; i < day.tasks.size(); i++)
		{
			final Task task = day.tasks.get(i);
			sb.append(i > 0 ? ";" : "").append(task.kind.name()).append(':').append(task.need).append(':').append(task.done).append(':').append(task.rewarded ? 1 : 0).append(':').append(task.spotX == NO_SPOT ? "-1" : task.spotX + "," + task.spotY);
		}
		return sb.toString();
	}

	private static Day decode(String value)
	{
		try
		{
			final String[] parts = value.split("\\|");
			if ((parts.length < 3) || (LocalDate.parse(parts[0]) == null))
			{
				return null;
			}
			final Day day = new Day(parts[0]);
			day.bonus = parts[1].equals("B1");
			for (String raw : parts[2].split(";"))
			{
				final String[] f = raw.split(":");
				final Task task = new Task(Kind.valueOf(f[0]), Integer.parseInt(f[1]));
				task.done = Integer.parseInt(f[2]);
				task.rewarded = f[3].equals("1");
				if (f[4].contains(","))
				{
					final String[] xy = f[4].split(",");
					task.spotX = Integer.parseInt(xy[0]);
					task.spotY = Integer.parseInt(xy[1]);
				}
				else
				{
					// Прежняя запись — номер в списке спотов (как было до 2026-10-23): в точку входа, если такой номер есть.
					final int index = Integer.parseInt(f[4]);
					final List<HuntSpot> spots = HuntSpotData.getInstance().getSpots();
					if ((index >= 0) && (index < spots.size()))
					{
						task.spotX = spots.get(index).entry.getX();
						task.spotY = spots.get(index).entry.getY();
					}
				}
				day.tasks.add(task);
			}
			return day.tasks.isEmpty() ? null : day;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static String describe(Day day)
	{
		final List<String> parts = new ArrayList<>();
		for (Task task : day.tasks)
		{
			final HuntSpot spot = spot(task);
			parts.add(task.kind + " " + task.done + "/" + task.need + (spot != null ? " " + spot : ""));
		}
		return String.join(", ", parts);
	}

	// ---------- Окно ----------

	/**
	 * Окно поручений ({@code .daily}).
	 */
	void show(Player player)
	{
		final Day day = day(player);
		final int level = player.getLevel();
		final long adena = (long) L2HubWorldConfig.WORLD_DAILY_ADENA * level * level;
		final long exp = expPercent(level, L2HubWorldConfig.WORLD_DAILY_EXP_PERCENT);
		final StringBuilder sb = new StringBuilder("<html><title>Поручения дня</title><body><center><font color=\"LEVEL\">Поручения дня</font></center><br>");
		sb.append("<font color=\"B09878\">Выполнено ").append(day.rewarded()).append(" из ").append(day.tasks.size()).append(". Новые — в ").append(resetTime()).append(".</font><br><br>");
		sb.append("<table width=280>");
		synchronized (day)
		{
			for (Task task : day.tasks)
			{
				final String color = task.rewarded ? "808080" : "FFFFFF";
				sb.append("<tr><td width=200><font color=\"").append(task.rewarded ? "6E8B3D" : "LEVEL").append("\">").append(task.rewarded ? "(готово) " : "").append(task.kind.title).append("</font></td>");
				sb.append("<td width=80 align=right><font color=\"").append(color).append("\">").append(Math.min(task.done, task.need)).append(" / ").append(task.need).append("</font></td></tr>");
				sb.append("<tr><td colspan=2><font color=\"").append(task.rewarded ? "808080" : "A0A0A0").append("\">").append(text(task, player)).append("</font></td></tr>");
				final HuntSpot spot = spot(task);
				if ((spot != null) && !task.rewarded)
				{
					final int fee = level > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE : 0;
					sb.append("<tr><td colspan=2 align=center><button value=\"Туда").append(fee > 0 ? " (" + fee + ")" : "").append("\" action=\"bypass voice .daily go\" width=120 height=21 back=\"").append(PhantomCompat.BUTTON_BACK).append("\" fore=\"").append(PhantomCompat.BUTTON_FORE).append("\"></td></tr>");
				}
				sb.append("<tr><td colspan=2 height=8></td></tr>");
			}
		}
		sb.append("</table><br>");
		sb.append("<font color=\"B09878\">За каждое: ").append(adena).append(" адены").append(exp > 0 ? " и " + L2HubWorldConfig.WORLD_DAILY_EXP_PERCENT + "% уровня (" + exp + " опыта)" : "").append(".");
		if (L2HubWorldConfig.WORLD_DAILY_BONUS)
		{
			final int grade = Math.min(ARMOR_SCROLLS.length, PhantomCompat.expertiseLevel(player));
			sb.append(" За все три — ").append((grade > 0) && (level >= LEAVE_HOME_LEVEL) ? ItemData.getInstance().getTemplate(ARMOR_SCROLLS[grade - 1]).getName() : "ещё столько же адены").append(day.bonus ? " (получено)" : "").append('.');
		}
		sb.append(" В пати засчитывается всё, что убила пати рядом с тобой.</font></body></html>");
		player.sendPacket(new NpcHtmlMessage(sb.toString()));
	}

	private static String text(Task task, Player player)
	{
		switch (task.kind)
		{
			case HUNT:
			{
				return "Убей мобов не слабее " + Math.max(1, player.getLevel() - HUNT_LEVEL_GAP) + "-го уровня.";
			}
			case SPOT:
			{
				final HuntSpot spot = spot(task);
				return spot == null ? "Спот пропал — задание засчитается любым мобом." : "Поохоться здесь: " + spot.name + " (" + spot.region + ", мобы " + spot.min + "–" + spot.max + ").";
			}
			case CHAMPION:
			{
				return "Убей чемпиона — синего или красного (" + ChampionMonstersConfig.CHAMP_MIN_LEVEL + "–" + ChampionMonstersConfig.CHAMP_MAX_LEVEL + " ур.). Окно моба по Shift+клику покажет.";
			}
			case HUNTER_PARTY:
			{
				return "Качайся в пати с охотником: крикни «лфм» в общий чат рядом с ними или пригласи сам (.hunters — кто где).";
			}
		}
		return "";
	}

	/**
	 * Телепорт на «Место дня» (проверки доски — в {@link WorldDailyCommand}): выше {@code MaxFreeTeleportLevel} — за {@code WorldDailyTeleportFee}.
	 * @return ошибка или {@code null}
	 */
	String teleport(Player player)
	{
		final Day day = day(player);
		for (Task task : day.tasks)
		{
			final HuntSpot spot = spot(task);
			if ((spot == null) || task.rewarded)
			{
				continue;
			}
			final int fee = player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE : 0;
			if ((fee > 0) && !PhantomCompat.reduceAdena(player, ItemProcessType.FEE, fee, player, true))
			{
				return "Не хватает адены на телепорт: " + fee + ".";
			}
			final double angle = Math.random() * 2 * Math.PI;
			player.teleToLocation(new Location(spot.entry.getX() + (int) (Math.cos(angle) * 80), spot.entry.getY() + (int) (Math.sin(angle) * 80), spot.entry.getZ()), true);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " teleports to the daily spot " + spot + ".");
			return null;
		}
		return "Места дня нет или оно уже выполнено.";
	}

	public static WorldDaily getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final WorldDaily INSTANCE = new WorldDaily();
	}
}
