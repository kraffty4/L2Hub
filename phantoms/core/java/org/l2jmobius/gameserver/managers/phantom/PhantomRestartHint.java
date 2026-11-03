package org.l2jmobius.gameserver.managers.phantom;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryType;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;

/**
 * L2Hub: занятая куча после сборки и напоминание о перезапуске (docs/PHANTOMS.md, раздел 4.132). Раз в минуту пишет {@value #STATUS_FILE} для окна лаунчера.
 * Сборщик сам держит кучу почти полной, поэтому смотрим не на занятое сейчас, а на то, что осталось после последней большой сборки: это живые данные мира.
 * Выше {@code RestartHintHeapPercent} живым игрокам приходит сообщение: при входе и раз в {@code RestartHintRepeatMinutes}. Сам сервер не перезапускается.
 * @author L2Hub
 */
public final class PhantomRestartHint
{
	private static final Logger LOGGER = Logger.getLogger(PhantomRestartHint.class.getName());
	private static final String STATUS_FILE = "./log/L2HubHeap.json";
	private static final long PERIOD = 60000;
	private static final long LOGIN_DELAY = 20000;
	private static final long MB = 1048576;

	private static volatile boolean _hot;
	private static long _lastNotice;

	private PhantomRestartHint()
	{
	}

	static void start()
	{
		if (!PhantomHuntersConfig.RESTART_HINT)
		{
			return;
		}
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), PhantomRestartHint.class));
		ThreadPool.scheduleAtFixedRate(PhantomRestartHint::check, PERIOD, PERIOD);
	}

	/**
	 * @return байт в куче после последней сборки каждой её части; для ZGC это итог последнего большого цикла у старшего поколения
	 */
	private static long afterGc()
	{
		long used = 0;
		for (MemoryPoolMXBean pool : ManagementFactory.getMemoryPoolMXBeans())
		{
			if (pool.getType() == MemoryType.HEAP)
			{
				final MemoryUsage usage = pool.getCollectionUsage();
				if (usage != null)
				{
					used += usage.getUsed();
				}
			}
		}
		return used;
	}

	private static void check()
	{
		try
		{
			final long now = System.currentTimeMillis();
			final long used = afterGc();
			final long max = Runtime.getRuntime().maxMemory();
			final long uptime = ManagementFactory.getRuntimeMXBean().getUptime() / 1000;
			final boolean hot = (used * 100) >= (max * PhantomHuntersConfig.RESTART_HINT_HEAP_PERCENT);
			write(now, used, max, uptime, hot);
			if (hot && !_hot)
			{
				LOGGER.info("PhantomRestartHint: heap after GC " + (used / MB) + " of " + (max / MB) + " MB, uptime " + (uptime / 3600) + " h: time to restart.");
			}
			_hot = hot;
			if (hot && ((now - _lastNotice) >= (PhantomHuntersConfig.RESTART_HINT_REPEAT_MINUTES * 60000L)))
			{
				_lastNotice = now;
				for (Player player : World.getPlayers())
				{
					if (!PhantomManager.getInstance().isPhantom(player))
					{
						tell(player, used, max, uptime);
					}
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "PhantomRestartHint: check failed.", e);
		}
	}

	private static void onLogin(Player player)
	{
		if (_hot && (player != null) && !PhantomManager.getInstance().isPhantom(player))
		{
			ThreadPool.schedule(() ->
			{
				if (player.isOnline())
				{
					tell(player, afterGc(), Runtime.getRuntime().maxMemory(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000);
				}
			}, LOGIN_DELAY);
		}
	}

	private static void tell(Player player, long used, long max, long uptime)
	{
		final long percent = (used * 100) / max;
		final long hours = uptime / 3600;
		player.sendMessage("en".equals(L2HubWorldConfig.LANGUAGE) ? "Server: memory is " + percent + "% full after " + hours + " h of work. Restart it when it suits you: the world will slow down soon, then it may crash." : "Сервер: память занята на " + percent + " % после " + hours + " ч работы. Перезапусти его, когда будет удобно: скоро мир начнёт тормозить, а потом может упасть.");
	}

	private static void write(long now, long used, long max, long uptime, boolean hot) throws Exception
	{
		final String json = "{\"version\":1,\"time\":" + now + ",\"afterGcMb\":" + (used / MB) + ",\"maxMb\":" + (max / MB) + ",\"uptimeSec\":" + uptime + ",\"hot\":" + hot + "}";
		final Path file = Path.of(STATUS_FILE);
		final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		Files.writeString(tmp, json, StandardCharsets.UTF_8);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
	}
}
