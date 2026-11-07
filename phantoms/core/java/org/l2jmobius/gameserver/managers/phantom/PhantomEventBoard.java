package org.l2jmobius.gameserver.managers.phantom;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.script.Event;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * L2Hub: запись на TvT, Deathmatch и CtF с доски Alt+B, {@code _bbshubevents} (docs/PHANTOMS.md, раздел 4.96).<br>
 * Менеджер стоит только в Гиране, а запись идёт 10 минут, у Deathmatch — одну: издалека не добежать. Запись — только запись, на арену переносит сам ивент, поэтому записаться можно и на каче.
 * Проверки остаются за скриптом: {@code onEvent("Participate", null, player)} и {@code onEvent("CancelParticipation", null, player)} напрямую. {@code notifyEvent} открыл бы окно NPC, а у этих двух событий {@code npc} не нужен.<br>
 * Что открыто, говорит сам скрипт ({@code onEvent("L2HubStatus", null, player)}, патч 0116), а не менеджер в мире: у TvT и CtF менеджеры стоят и на арене, у Deathmatch на арене менеджера нет. Менеджер в Гиране остаётся, охотники записываются через него ({@link PhantomEvents}).
 */
public final class PhantomEventBoard
{
	private static final Logger LOGGER = Logger.getLogger(PhantomEventBoard.class.getName());

	private static final String BOARD = "bypass _bbshubevents";
	private static final Path CONFIG = Paths.get("./config/Custom/L2HubEvents.ini");
	private static final String STATUS = "L2HubStatus";
	private static final String[] EVENTS =
	{
		"TvT",
		"Deathmatch",
		"CtF"
	};

	/** Что говорит скрипт ивента: состояние, записано, мест, порог уровня, предел уровня, этот игрок записан, когда закроется запись. */
	private record Status(String state, int count, int max, int minLevel, int maxLevel, boolean registered, long closes)
	{
		boolean open()
		{
			return "PARTICIPATING".equals(state);
		}

		boolean running()
		{
			return "STARTING".equals(state) || "STARTED".equals(state);
		}
	}

	private PhantomEventBoard()
	{
	}

	// ---------- Запись ----------

	/**
	 * _bbshubevents | join &lt;ивент&gt; | cancel &lt;ивент&gt;
	 * @param player игрок
	 * @param args команда доски
	 * @return содержимое страницы
	 */
	public String page(Player player, String[] args)
	{
		final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "";
		String message = "";
		if (action.equals("join") || action.equals("cancel"))
		{
			final String name = args.length > 2 ? eventName(args[2]) : null;
			message = name == null ? tr("Такого ивента нет.") : action.equals("join") ? join(player, name) : cancel(player, name);
		}
		return render(player, message);
	}

	private static String eventName(String value)
	{
		for (String name : EVENTS)
		{
			if (name.equalsIgnoreCase(value))
			{
				return name;
			}
		}
		return null;
	}

	/**
	 * @return что ответить игроку; запись делает только скрипт
	 */
	private static String join(Player player, String name)
	{
		final Status status = status(name, player);
		if (status == null)
		{
			return unavailable();
		}
		if (status.registered())
		{
			return tr("Ты уже записан на этот ивент.");
		}
		// Сюда и Deathmatch в бою: его Participate сам состояние прежде не проверял, вся надежда была на менеджера рядом.
		if (!status.open())
		{
			return closed(status);
		}
		final String refusal = personal(player);
		if (refusal != null)
		{
			return refusal;
		}

		final String result = call(name, "Participate", player);
		final Status after = status(name, player);
		if ((after != null) && after.registered() && player.isRegisteredOnEvent())
		{
			LOGGER.info(PhantomEventBoard.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") signs up for " + name + " from the board, " + after.count() + " of " + after.max() + ".");
			return tr("Ты записан: ") + eventTitle(name) + tr(". На арену перенесёт сам ивент, когда запись закроется.");
		}
		if ("registration-ip.html".equals(result))
		{
			return tr("С твоего адреса на ивент уже записано слишком много персонажей.");
		}
		final String reason = reason(player, after != null ? after : status);
		return reason != null ? reason : tr("Ивент не принял запись, причина — в системном чате.");
	}

	private static String cancel(Player player, String name)
	{
		final Status status = status(name, player);
		if (status == null)
		{
			return unavailable();
		}
		if (player.isOnEvent())
		{
			return tr("Ты уже на арене, отменять поздно.");
		}
		// Запись чужого ивента трогать нельзя: CancelParticipation снимает общий флаг, записан ли игрок именно сюда, он не смотрит.
		if (!status.registered())
		{
			return tr("Ты не записан на этот ивент.");
		}

		call(name, "CancelParticipation", player);
		final Status after = status(name, player);
		if ((after != null) && !after.registered() && !player.isRegisteredOnEvent())
		{
			LOGGER.info(PhantomEventBoard.class.getSimpleName() + ": " + player.getName() + " cancels " + name + " from the board, " + after.count() + " left.");
			return tr("Запись отменена: ") + eventTitle(name) + ".";
		}
		return tr("Отменить запись не вышло, причина — в системном чате.");
	}

	/**
	 * @return ответ скрипта на событие; {@code null} — ничего не ответил или упал
	 */
	static String call(String name, String event, Player player)
	{
		final Quest script = ScriptManager.getInstance().getScript(name);
		if (script == null)
		{
			return null;
		}
		try
		{
			return script.onEvent(event, null, player);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomEventBoard.class.getSimpleName() + ": " + name + " failed on " + event + " for " + player.getName() + ".", e);
			return null;
		}
	}

	/**
	 * @return что говорит скрипт; {@code null} — скрипта нет или он не наш: тогда страница не обещает запись
	 */
	private static Status status(String name, Player player)
	{
		final Quest script = ScriptManager.getInstance().getScript(name);
		if (script == null)
		{
			return null;
		}
		try
		{
			final String[] parts = script.onEvent(STATUS, null, player).split(" ");
			return new Status(parts[0], Integer.parseInt(parts[1]), Integer.parseInt(parts[2]), Integer.parseInt(parts[3]), Integer.parseInt(parts[4]), Boolean.parseBoolean(parts[5]), Long.parseLong(parts[6]));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomEventBoard.class.getSimpleName() + ": no status from " + name + ".", e);
			return null;
		}
	}

	/**
	 * Что доска решает сама: скрипт этого не проверяет, а у менеджера в Гиране такой игрок обычно и не стоит. Остальное решает {@code canRegister} скрипта.
	 * @return почему нельзя или {@code null}
	 */
	private static String personal(Player player)
	{
		if (player.isDead())
		{
			return tr("Мёртвому на ивент не записаться.");
		}
		if (player.isOnEvent())
		{
			return tr("Ты уже на ивенте.");
		}
		if (player.isInOlympiadMode())
		{
			return tr("На олимпиаде на ивент не записаться.");
		}
		if (player.isInStoreMode())
		{
			return tr("Сначала закрой лавку.");
		}
		return player.isJailed() ? tr("Из тюрьмы на ивент не записаться.") : null;
	}

	/**
	 * Понятная причина отказа: скрипт пишет её в чат по-английски, а здесь те же проверки в том же порядке. Перевоплощение и полёт есть не на всех хрониках, за них скажет чат.
	 * @return почему {@code canRegister} откажет или {@code null}
	 */
	private static String reason(Player player, Status status)
	{
		if (player.getLevel() < status.minLevel())
		{
			return tr("Твой уровень ниже порога: нужен ") + status.minLevel() + ".";
		}
		if (player.getLevel() > status.maxLevel())
		{
			return tr("Твой уровень выше предела: можно до ") + status.maxLevel() + ".";
		}
		if (player.isRegisteredOnEvent())
		{
			return tr("Ты уже записан на другой ивент.");
		}
		if (status.count() >= status.max())
		{
			return tr("Мест больше нет: записано ") + status.count() + tr(" из ") + status.max() + ".";
		}
		if (!player.isInventoryUnder80(false))
		{
			return tr("Сумка заполнена больше чем на 80%: освободи место.");
		}
		if (player.getWeightPenalty() != 0)
		{
			return tr("Перегруз: освободи сумку.");
		}
		if (player.isCursedWeaponEquipped() || (PhantomCompat.karma(player) > 0))
		{
			return tr("С кармой или проклятым оружием на ивент не записаться.");
		}
		if (player.isInDuel())
		{
			return tr("Сначала закончи дуэль.");
		}
		if (PhantomCompat.olympiadRegistered(player))
		{
			return tr("Ты записан на олимпиаду.");
		}
		if (player.isInInstance())
		{
			return tr("Из инстанса на ивент не записаться.");
		}
		if (player.isInSiege() || player.isInsideZone(ZoneId.SIEGE))
		{
			return tr("В осаде на ивент не записаться.");
		}
		return player.isFishing() ? tr("Сначала закончи рыбалку.") : null;
	}

	private static String closed(Status status)
	{
		return status.running() ? tr("Запись на этот ивент закрыта: идёт бой. Записаться можно будет на следующий.") : tr("Запись на этот ивент сейчас не открыта.");
	}

	private static String unavailable()
	{
		return tr("Скрипт этого ивента недоступен, подробность — в журнале сервера.");
	}

	// ---------- Страница ----------

	private static String render(Player player, String message)
	{
		final StringBuilder out = new StringBuilder("<br>");
		if (!message.isEmpty())
		{
			out.append("<font color=\"LEVEL\">").append(message).append("</font><br><br>");
		}
		if (player.isOnEvent())
		{
			out.append("<font color=\"LEVEL\">").append(tr("Ты сейчас на ивенте: запись и отмена с доски закрыты.")).append("</font><br><br>");
		}

		final StringBuilder open = new StringBuilder();
		final StringBuilder running = new StringBuilder();
		for (String name : EVENTS)
		{
			final Status status = status(name, player);
			if (status == null)
			{
				running.append(eventTitle(name)).append(": ").append(unavailable()).append("<br1>");
			}
			else if (status.open())
			{
				open.append(row(player, name, status));
			}
			else if (status.running())
			{
				running.append(tr("Идёт бой: ")).append(eventTitle(name)).append(tr(". Запись на него закрыта.")).append("<br1>");
			}
		}

		if (open.length() > 0)
		{
			out.append("<font color=\"LEVEL\">").append(tr("Открыта запись")).append("</font><br><table width=520 cellpadding=3 cellspacing=0>").append(open).append("</table><br>");
		}
		else
		{
			out.append("<font color=\"AFAFAF\">").append(tr("Сейчас запись ни на что не открыта.")).append("</font><br>");
		}
		if (running.length() > 0)
		{
			out.append("<font color=\"AFAFAF\">").append(running).append("</font><br>");
		}
		if (open.length() == 0)
		{
			schedule(out);
		}
		return out.append("<font color=\"909090\">").append(tr("Запись — только запись: на арену перенесёт сам ивент, когда она закроется, даже если ты на каче. У менеджера в Гиране записывает то же самое.")).append("</font>").toString();
	}

	private static String row(Player player, String name, Status status)
	{
		final StringBuilder line = new StringBuilder("<tr><td width=330><font color=\"CDB67F\">").append(eventTitle(name)).append("</font><br1><font color=\"909090\">");
		line.append(tr("Записано: ")).append(status.count()).append(tr(" из ")).append(status.max()).append(tr(", уровень от ")).append(status.minLevel());
		if (status.closes() > 0)
		{
			line.append(tr(", запись до ")).append(new SimpleDateFormat("HH:mm").format(new Date(status.closes())));
		}
		line.append("</font></td><td width=190 align=center>");
		if (status.registered())
		{
			line.append("<font color=\"6E8B3D\">").append(tr("Ты записан")).append("</font><br1>").append(button(tr("Отменить запись"), BOARD + " cancel " + name, 150));
		}
		else
		{
			final String refusal = personal(player);
			final String blocked = refusal != null ? refusal : reason(player, status);
			line.append(blocked != null ? "<font color=\"909090\">" + blocked + "</font>" : button(tr("Записаться"), BOARD + " join " + name, 150));
		}
		return line.append("</td></tr>").toString();
	}

	/** Ближайшие старты по крону L2HubEvents.ini, как окно {@code .world}. */
	private static void schedule(StringBuilder out)
	{
		final Properties config = config();
		if (!"true".equalsIgnoreCase(config.getProperty("PvpEvents", "False").trim()))
		{
			out.append("<font color=\"AFAFAF\">").append(tr("PvP-ивенты выключены (PvpEvents в L2HubEvents.ini).")).append("</font><br><br>");
			return;
		}

		out.append("<font color=\"LEVEL\">").append(tr("Ближайшие старты")).append("</font><br1>");
		for (String name : EVENTS)
		{
			final long next = next(config.getProperty(name, ""));
			out.append(next > 0 ? when(next) + ": " + eventTitle(name) : eventTitle(name) + tr(": не запускается, в расписании пусто (L2HubEvents.ini)")).append("<br1>");
		}
		out.append("<font color=\"909090\">").append(tr("Старт пропускается, если в этот момент идёт другой ивент.")).append("</font><br><br>");
	}

	private static Properties config()
	{
		final Properties properties = new Properties();
		if (Files.isRegularFile(CONFIG))
		{
			try (Reader reader = Files.newBufferedReader(CONFIG, StandardCharsets.UTF_8))
			{
				properties.load(reader);
			}
			catch (Exception e)
			{
				LOGGER.warning(PhantomEventBoard.class.getSimpleName() + ": " + CONFIG + ": " + e.getMessage());
			}
		}
		return properties;
	}

	/**
	 * @param pattern крон «минуты часы день месяц день_недели»
	 * @return ближайший старт, мс от эпохи; 0 — пусто или не крон
	 */
	private static long next(String pattern)
	{
		if ((pattern == null) || pattern.isBlank())
		{
			return 0;
		}
		try
		{
			// Крон стартует ровно в минуту, а задержка у Mobius приходит на доли секунды короче — округлить до минуты.
			final long at = System.currentTimeMillis() + new SchedulingPattern(pattern.trim()).getDelayToNextFromNow();
			return ((at + 30000) / 60000) * 60000;
		}
		catch (Exception e)
		{
			return 0;
		}
	}

	/**
	 * @return «ЧЧ:ММ», не сегодня — «дд.ММ ЧЧ:ММ»
	 */
	private static String when(long at)
	{
		final boolean today = new SimpleDateFormat("yyyyMMdd").format(new Date(at)).equals(new SimpleDateFormat("yyyyMMdd").format(new Date()));
		return new SimpleDateFormat(today ? "HH:mm" : "dd.MM HH:mm").format(new Date(at));
	}

	private static String eventTitle(String name)
	{
		switch (name)
		{
			case "TvT":
			{
				return tr("TvT, команда на команду");
			}
			case "Deathmatch":
			{
				return tr("Deathmatch, каждый за себя");
			}
			default:
			{
				return tr("CtF, захват флага");
			}
		}
	}

	private static String button(String label, String action, int width)
	{
		// Широкие кнопки — своей текстурой: в Interlude обычная не растягивается шире родной ширины.
		final boolean wide = width > 90;
		return "<button value=\"" + label + "\" action=\"" + action + "\" width=" + width + " height=25 back=\"" + (wide ? PhantomCompat.WIDE_BUTTON_BACK : PhantomCompat.BUTTON_BACK) + "\" fore=\"" + (wide ? PhantomCompat.WIDE_BUTTON_FORE : PhantomCompat.BUTTON_FORE) + "\">";
	}

	/**
	 * @return заголовок страницы
	 */
	public static String title()
	{
		return tr("Ивенты");
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка {@code HuntersSelfTest = EVENTBOARD} (раздел 4.96, только на копии базы): живых играют охотники. Запись закрыта — понятный ответ; запись и отмена с доски, вторая запись и вторая отмена — отказ;
	 * другая запись не снимается; мёртвому и на ивенте — отказ; уровень и места; охотники по-прежнему записываются через менеджера; в бою Deathmatch запись не проходит. Ивенты останавливает сама, в чат уйдёт «Event was canceled».
	 * @param online охотники в мире
	 */
	void selfTest(List<Player> online)
	{
		ThreadPool.execute(() -> runSelfTest(online));
	}

	private void runSelfTest(List<Player> online)
	{
		final String tag = "SelfTest EVENTBOARD: ";
		final List<String> failed = new ArrayList<>();
		for (String name : EVENTS)
		{
			final Status status = status(name, online.isEmpty() ? null : online.get(0));
			if (status == null)
			{
				LOGGER.info(tag + "FAILED: no status from " + name + ".");
				return;
			}
			if (!"INACTIVE".equals(status.state()))
			{
				LOGGER.info(tag + name + " is " + status.state() + ", run it again when no event is on.");
				return;
			}
		}

		final Event tvt = ScriptManager.getInstance().getScript("TvT") instanceof Event event ? event : null;
		final Event deathmatch = ScriptManager.getInstance().getScript("Deathmatch") instanceof Event event ? event : null;
		final List<Player> free = new ArrayList<>();
		Player lowest = null;
		for (Player player : online)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
			if ((template != null) && template.isHunter() && !template.isReaper() && !player.isDead() && !player.isInParty() && !player.isInInstance() && !player.isOnEvent() && !player.isRegisteredOnEvent() && (personal(player) == null))
			{
				free.add(player);
				lowest = (lowest == null) || (player.getLevel() < lowest.getLevel()) ? player : lowest;
			}
		}
		free.sort((a, b) -> Integer.compare(b.getLevel(), a.getLevel()));
		if ((tvt == null) || (deathmatch == null) || (free.size() < 8))
		{
			LOGGER.info(tag + "needs TvT, Deathmatch and eight free hunters, has " + free.size() + ".");
			return;
		}

		final boolean wasHunters = PhantomHuntersConfig.HUNTER_PVP_EVENTS;
		final Player first = free.get(0);
		final Player second = free.get(1);
		final Player third = free.get(2);
		try
		{
			// Охотникам пока нельзя: их менеджер записал бы проверяемых прямо посреди проверки.
			PhantomHuntersConfig.HUNTER_PVP_EVENTS = false;

			expect(failed, "закрыто, запись", join(first, "TvT"), tr("Запись на этот ивент сейчас не открыта."));
			expect(failed, "закрыто, флаг", first.isRegisteredOnEvent(), false);
			final String quiet = render(first, "");
			if (!quiet.contains(tr("Сейчас запись ни на что не открыта.")) || quiet.contains(" join "))
			{
				failed.add("закрыто, страница: " + quiet);
			}
			LOGGER.info(tag + "closed page, " + quiet.length() + " chars.");

			expect(failed, "старт TvT", tvt.eventStart(null), true);
			Status status = status("TvT", first);
			expect(failed, "открыто", (status != null) && status.open(), true);
			final int empty = status != null ? status.count() : -1;
			final String opened = render(first, "");
			if (!opened.contains(BOARD + " join TvT"))
			{
				failed.add("открыто, нет кнопки: " + opened);
			}
			LOGGER.info(tag + "open page, " + opened.length() + " chars, " + status + ".");

			// Запись и вторая запись.
			final String joined = join(first, "TvT");
			status = status("TvT", first);
			expect(failed, "запись, флаг", first.isRegisteredOnEvent(), true);
			expect(failed, "запись, скрипт", (status != null) && status.registered(), true);
			expect(failed, "запись, счёт", status != null ? status.count() : -1, empty + 1);
			if (!joined.startsWith(tr("Ты записан: ")))
			{
				failed.add("запись, ответ: " + joined);
			}
			if (!render(first, "").contains(BOARD + " cancel TvT"))
			{
				failed.add("запись, нет отмены");
			}
			expect(failed, "вторая запись", join(first, "TvT"), tr("Ты уже записан на этот ивент."));
			status = status("TvT", first);
			expect(failed, "вторая запись, счёт", status != null ? status.count() : -1, empty + 1);

			// Отмена чужой записи не снимает общий флаг.
			expect(failed, "отмена не того ивента", cancel(first, "CtF"), tr("Ты не записан на этот ивент."));
			expect(failed, "отмена не того ивента, флаг", first.isRegisteredOnEvent(), true);

			// Отмена и вторая отмена.
			if (!cancel(first, "TvT").startsWith(tr("Запись отменена: ")))
			{
				failed.add("отмена, ответ");
			}
			expect(failed, "отмена, флаг", first.isRegisteredOnEvent(), false);
			status = status("TvT", first);
			expect(failed, "отмена, счёт", status != null ? status.count() : -1, empty);
			expect(failed, "вторая отмена", cancel(first, "TvT"), tr("Ты не записан на этот ивент."));

			// Мёртвому и на ивенте.
			second.doDie(null);
			expect(failed, "мёртвый", join(second, "TvT"), tr("Мёртвому на ивент не записаться."));
			second.doRevive();
			second.setOnEvent(true);
			expect(failed, "на ивенте", join(second, "TvT"), tr("Ты уже на ивенте."));
			second.setOnEvent(false);
			expect(failed, "мёртвый и на ивенте, флаг", second.isRegisteredOnEvent(), false);

			// Порог уровня: настоящий отказ скрипта, если есть кто ниже порога (PvpMinLevel на копии); и расчёт причины всегда.
			final int threshold = status != null ? status.minLevel() : 1;
			if (lowest.getLevel() < threshold)
			{
				expect(failed, "уровень ниже порога", join(lowest, "TvT"), tr("Твой уровень ниже порога: нужен ") + threshold + ".");
				expect(failed, "уровень ниже порога, флаг", lowest.isRegisteredOnEvent(), false);
			}
			else
			{
				LOGGER.info(tag + "nobody below level " + threshold + ", only the calculation is checked: set PvpMinLevel higher to see the script refuse.");
			}
			expect(failed, "причина, уровень", reason(third, new Status("PARTICIPATING", 0, 24, third.getLevel() + 1, 200, false, 0)), tr("Твой уровень ниже порога: нужен ") + (third.getLevel() + 1) + ".");
			expect(failed, "причина, места", reason(third, new Status("PARTICIPATING", 24, 24, 1, 200, false, 0)), tr("Мест больше нет: записано ") + 24 + tr(" из ") + 24 + ".");

			// Охотники — через менеджера в Гиране, как и раньше.
			PhantomHuntersConfig.HUNTER_PVP_EVENTS = wasHunters;
			int hunters = 0;
			for (int attempt = 0; wasHunters && (attempt < 30) && (hunters == 0); attempt++)
			{
				Thread.sleep(5000);
				hunters = countRegistered();
			}
			if (wasHunters && (hunters == 0))
			{
				failed.add("пешки через менеджера не записались за 150 с");
			}
			LOGGER.info(tag + hunters + " hunters signed up through the manager, by the script " + status("TvT", first) + ".");
			PhantomHuntersConfig.HUNTER_PVP_EVENTS = false;

			tvt.eventStop();
			expect(failed, "после стопа, флаги", countRegistered(), 0);
			expect(failed, "после стопа, запись", join(first, "TvT"), tr("Запись на этот ивент сейчас не открыта."));

			// Deathmatch: запись в бою проходила бы, его Participate состояние не проверял. Арена на четверых с доски.
			expect(failed, "старт Deathmatch", deathmatch.eventStart(null), true);
			final List<Player> fighters = new ArrayList<>();
			Status dm = status("Deathmatch", first);
			LOGGER.info(tag + "Deathmatch " + dm + ".");
			for (Player player : free)
			{
				if ((fighters.size() < 4) && (dm != null) && (reason(player, dm) == null) && join(player, "Deathmatch").startsWith(tr("Ты записан: ")))
				{
					fighters.add(player);
				}
			}
			final Player late = free.stream().filter(player -> !fighters.contains(player)).findFirst().orElse(null);
			if ((fighters.size() < 4) || (late == null))
			{
				failed.add("Deathmatch, на арену записалось " + fighters.size());
			}
			else
			{
				((Quest) deathmatch).onEvent("TeleportToArena", null, null);
				dm = status("Deathmatch", late);
				expect(failed, "Deathmatch, бой", (dm != null) && dm.running(), true);
				final int fighting = dm != null ? dm.count() : -1;
				expect(failed, "Deathmatch, запись в бою", join(late, "Deathmatch"), tr("Запись на этот ивент закрыта: идёт бой. Записаться можно будет на следующий."));
				expect(failed, "Deathmatch, запись в бою, флаг", late.isRegisteredOnEvent(), false);
				dm = status("Deathmatch", late);
				expect(failed, "Deathmatch, запись в бою, счёт", dm != null ? dm.count() : -1, fighting);
				final String fight = render(late, "");
				if (!fight.contains(tr("Идёт бой: ")) || fight.contains(" join "))
				{
					failed.add("Deathmatch, страница в бою: " + fight);
				}
				expect(failed, "Deathmatch, участник отменяет", cancel(fighters.get(0), "Deathmatch"), tr("Ты уже на арене, отменять поздно."));
			}
			deathmatch.eventStop();
		}
		catch (Exception e)
		{
			failed.add("исключение: " + e);
			LOGGER.log(Level.WARNING, tag + "failed.", e);
		}
		finally
		{
			for (Event event : new Event[]
			{
				tvt,
				deathmatch
			})
			{
				final Status status = status(((Quest) event).getName(), first);
				if ((status != null) && !"INACTIVE".equals(status.state()))
				{
					event.eventStop();
				}
			}
			PhantomHuntersConfig.HUNTER_PVP_EVENTS = wasHunters;
		}
		LOGGER.info(tag + (failed.isEmpty() ? "passed." : "FAILED: " + failed));
	}

	private static int countRegistered()
	{
		int count = 0;
		for (Player player : World.getPlayers())
		{
			if (player.isRegisteredOnEvent())
			{
				count++;
			}
		}
		return count;
	}

	private static void expect(List<String> failed, String what, Object got, Object want)
	{
		if ((got == null) ? (want != null) : !got.equals(want))
		{
			failed.add(what + ": " + got + " вместо " + want);
		}
	}

	public static PhantomEventBoard getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomEventBoard INSTANCE = new PhantomEventBoard();
	}
}
