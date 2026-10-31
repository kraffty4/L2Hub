/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package custom.L2HubPvpEvents;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.time.SchedulingPattern;
import org.l2jmobius.commons.time.TimeUtil;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.mechanics.script.Event;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * L2Hub: PvP-ивенты Mobius (custom/events: TvT, Deathmatch, CtF) по расписанию из config/Custom/L2HubEvents.ini — для
 * охотников-фантомов, которых ядро фантомов записывает на открытую регистрацию; без них в одиночку ивент не набирает
 * участников и Mobius отменяет его сам.<br>
 * PvpEvents = False (по умолчанию) — расписания нет и порог уровня в скриптах прежний. Ключи TvT, Deathmatch, CtF — крон
 * «минуты часы день месяц день_недели» (SchedulingPattern Mobius), пусто — не запускать. PvpOnlyWithPlayers — старт только
 * при живом игроке в мире (с клиентом, не фантом и не офлайн-торговец). PvpMinLevel — порог уровня вместо зашитого в
 * скриптах (их константа MINIMUM_PARTICIPANT_LEVEL берёт его через minLevel). Пока идёт любой из трёх ивентов (в мире
 * стоит его менеджер или кто-то записан либо на арене), следующий не стартует: у Deathmatch на арене менеджера нет.
 * Перед ареной скрипты выкидывают вышедших из игры через offline: фантомы без клиента остаются в списке.
 * Размеры и бой TvT (TvTMinParticipants, TvTMaxParticipants, TvTReservedSlots, TvTFightMinutes) скрипт берёт через tvt, места — через full.
 * Скрипт хронико-независимый: Interlude, Gracia Epilogue, High Five.
 * @author L2Hub
 */
public class L2HubPvpEvents
{
	private static final Logger LOGGER = Logger.getLogger(L2HubPvpEvents.class.getName());
	private static final Path CONFIG = Paths.get("./config/Custom/L2HubEvents.ini");
	private static final String[] EVENTS =
	{
		"TvT",
		"Deathmatch",
		"CtF"
	};
	private static final Set<Integer> MANAGERS = Set.of(70010, 70011, 70012);

	private final boolean _onlyWithPlayers;

	private L2HubPvpEvents(Properties config)
	{
		_onlyWithPlayers = !"false".equalsIgnoreCase(config.getProperty("PvpOnlyWithPlayers", "True").trim());
		for (String name : EVENTS)
		{
			final String pattern = config.getProperty(name, "").trim();
			if (pattern.isEmpty())
			{
				continue;
			}
			try
			{
				schedule(name, new SchedulingPattern(pattern));
			}
			catch (Exception e)
			{
				LOGGER.warning("L2HubPvpEvents: " + name + " = " + pattern + " — не крон: " + e.getMessage());
			}
		}
	}

	private void schedule(String name, SchedulingPattern pattern)
	{
		final long delay = pattern.getDelayToNextFromNow();
		ThreadPool.schedule(() ->
		{
			fire(name);
			schedule(name, pattern);
		}, delay + 5000); // +5 с, как у Mobius: не попасть в ту же минуту повторно
		LOGGER.info("L2HubPvpEvents: " + name + " — следующий старт " + TimeUtil.getDateTimeString(System.currentTimeMillis() + delay));
	}

	private void fire(String name)
	{
		if (_onlyWithPlayers && !livePlayerOnline())
		{
			LOGGER.info("L2HubPvpEvents: " + name + " пропущен — в мире нет живого игрока.");
			return;
		}
		if (eventRunning())
		{
			LOGGER.info("L2HubPvpEvents: " + name + " пропущен — идёт PvP-ивент.");
			return;
		}
		final Quest script = ScriptManager.getInstance().getScript(name);
		if (!(script instanceof Event event))
		{
			LOGGER.warning("L2HubPvpEvents: нет ивента " + name + " среди скриптов.");
			return;
		}
		LOGGER.info("L2HubPvpEvents: " + (event.eventStart(null) ? "старт " + name + "." : name + " не стартовал — уже идёт."));
	}

	private static boolean livePlayerOnline()
	{
		for (Player player : World.getPlayers())
		{
			if ((player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode())
			{
				return true;
			}
		}
		return false;
	}

	private static boolean eventRunning()
	{
		for (Player player : World.getPlayers())
		{
			if (player.isRegisteredOnEvent() || player.isOnEvent())
			{
				return true;
			}
		}
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Npc npc) && MANAGERS.contains(npc.getId()))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Участник вышел из игры — скрипт ивента убирает его перед ареной. У Mobius там isOnlineInt() != 1, а это и фантомы
	 * (персонажи без клиента): их выкидывало, ивент отменялся «мало участников», флаг записи у них оставался.
	 * @param player участник
	 * @return не в игре или офлайн-торговец
	 */
	public static boolean offline(Player player)
	{
		return (player == null) || !player.isOnline() || (player.isOnlineInt() == 2);
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
				LOGGER.warning("L2HubPvpEvents: " + CONFIG + ": " + e.getMessage());
			}
		}
		return properties;
	}

	private static boolean enabled(Properties config)
	{
		return "true".equalsIgnoreCase(config.getProperty("PvpEvents", "False").trim());
	}

	/**
	 * Порог уровня участника PvP-ивента: PvpMinLevel из L2HubEvents.ini, если PvpEvents = True, иначе прежний порог скрипта.
	 * @param original порог, зашитый в скрипте ивента
	 * @return порог уровня
	 */
	public static int minLevel(int original)
	{
		final Properties config = config();
		if (!enabled(config))
		{
			return original;
		}
		try
		{
			return Math.max(1, Integer.parseInt(config.getProperty("PvpMinLevel", String.valueOf(original)).trim()));
		}
		catch (NumberFormatException e)
		{
			return original;
		}
	}

	/**
	 * Размер или время TvT: ключ из L2HubEvents.ini, если PvpEvents = True, иначе значение из скрипта ивента.
	 * @param key ключ
	 * @param original значение, зашитое в скрипте
	 * @param min нижняя граница ключа
	 * @param max верхняя граница ключа
	 * @return значение; не число — значение из скрипта
	 */
	public static int tvt(String key, int original, int min, int max)
	{
		final Properties config = config();
		if (!enabled(config))
		{
			return original;
		}
		try
		{
			return Math.max(min, Math.min(max, Integer.parseInt(config.getProperty(key, String.valueOf(original)).trim())));
		}
		catch (NumberFormatException e)
		{
			return original;
		}
	}

	/**
	 * Мест на TvT нет: заняты все или для охотника — все, кроме reserved мест живым игрокам.
	 * @param registered записанные
	 * @param player кто записывается
	 * @param max всего мест
	 * @param reserved мест только для живых игроков
	 * @return записать нельзя
	 */
	public static boolean full(Set<Player> registered, Player player, int max, int reserved)
	{
		if (registered.size() >= max)
		{
			return true;
		}
		final PhantomManager phantoms = PhantomManager.getInstance();
		return (reserved > 0) && phantoms.isPhantom(player) && (registered.stream().filter(phantoms::isPhantom).count() >= (max - reserved));
	}

	public static void main(String[] args)
	{
		final Properties config = config();
		if (enabled(config))
		{
			new L2HubPvpEvents(config);
		}
		else
		{
			LOGGER.info("L2HubPvpEvents: PvP-ивенты по расписанию выключены (PvpEvents = False в L2HubEvents.ini).");
		}
	}
}
