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
package org.l2jmobius.gameserver.managers.phantom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.IVoicedCommandHandler;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;

/**
 * L2Hub: честный счётчик онлайна. Общая цифра и из кого она: живых, охотников, фантомов. Фантомы здесь — городская толпа (NPC Mobius с шаблоном
 * {@code fakePlayer}) и напарники. Секретные персонажи не считаются нигде. Читают {@code .online} (Online.java), экран выбора сервера
 * (ServerList.java логин-сервера, только общая цифра) и лаунчер: все берут числа отсюда или из {@value #STATUS_FILE}.
 * @author L2Hub
 */
public final class PhantomOnline
{
	private static final Logger LOGGER = Logger.getLogger(PhantomOnline.class.getName());
	private static final String STATUS_FILE = "./log/L2HubOnline.json";
	private static final long PERIOD = 10000;

	public record Counts(int live, int hunters, int phantoms)
	{
		public int total()
		{
			return live + hunters + phantoms;
		}
	}

	private PhantomOnline()
	{
	}

	static void start()
	{
		ThreadPool.scheduleAtFixedRate(PhantomOnline::write, PERIOD, PERIOD);
	}

	public static Counts count()
	{
		int live = 0;
		int hunters = 0;
		int phantoms = 0;
		final PhantomManager manager = PhantomManager.getInstance();
		for (Player player : World.getPlayers())
		{
			if (!manager.isPhantom(player))
			{
				live++;
			}
			else if (!manager.isSecret(player))
			{
				if (PhantomRelations.isHunter(player))
				{
					hunters++;
				}
				else
				{
					phantoms++;
				}
			}
		}
		for (WorldObject object : World.getVisibleObjects())
		{
			if (object.isFakePlayer())
			{
				phantoms++;
			}
		}
		return new Counts(live, hunters, phantoms);
	}

	/**
	 * @return две строки для игрока: общая цифра и расшифровка
	 */
	public static String[] lines()
	{
		final Counts counts = count();
		return new String[]
		{
			PhantomText.tr("Онлайн") + ": " + counts.total(),
			PhantomText.tr("Живых") + ": " + counts.live() + ", " + PhantomText.tr("охотников") + ": " + counts.hunters() + ", " + PhantomText.tr("фантомов") + ": " + counts.phantoms()
		};
	}

	/**
	 * Самопроверка {@code HuntersSelfTest = ONLINE}, только на копии базы: числа против списка охотников, обработчик {@code .online} и файл для окна и логин-сервера.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		final String tag = "SelfTest ONLINE: ";
		// Охотники входят по одному: в списке он уже есть, а в мире ещё нет. Сперва те, кто уже в мире, потом счёт: он может оказаться только больше.
		final long hunters = world.stream().filter(player -> !PhantomManager.getInstance().isSecret(player) && (World.getPlayer(player.getObjectId()) != null)).count();
		final Counts counts = count();
		LOGGER.info(tag + "live " + counts.live() + ", hunters " + counts.hunters() + ", phantoms " + counts.phantoms() + ", total " + counts.total() + ".");
		for (String line : lines())
		{
			LOGGER.info(tag + "line: " + line);
		}
		int wrong = report(tag, counts.hunters() >= hunters, "hunters " + counts.hunters() + ", from the hunter list in the world " + hunters);
		wrong += report(tag, counts.phantoms() > 0, "town crowd is counted");
		final IVoicedCommandHandler handler = VoicedCommandHandler.getInstance().getHandler("online");
		wrong += report(tag, handler != null, ".online is registered");
		if (handler != null)
		{
			wrong += report(tag, handler.onCommand("online", world.get(0), null), ".online answered");
		}
		final int before = wrong;
		ThreadPool.schedule(() ->
		{
			int all = before;
			try
			{
				final String json = Files.readString(Path.of(STATUS_FILE), StandardCharsets.UTF_8);
				LOGGER.info(tag + "file: " + json);
				final long time = Long.parseLong(json.replaceAll(".*\"time\":(\\d+),.*", "$1"));
				all += report(tag, (System.currentTimeMillis() - time) < (2 * PERIOD), "file is fresh");
				all += report(tag, json.contains("\"time\":" + time + ",\"total\":"), "the login server finds time and total in one match");
			}
			catch (Exception e)
			{
				all += report(tag, false, "file: " + e);
			}
			LOGGER.info(tag + "done: " + all + " wrong.");
		}, 2 * PERIOD);
	}

	private static int report(String tag, boolean good, String what)
	{
		LOGGER.info(tag + (good ? "ok: " : "WRONG: ") + what + ".");
		return good ? 0 : 1;
	}

	private static void write()
	{
		try
		{
			final Counts counts = count();
			final String json = "{\"version\":1,\"time\":" + System.currentTimeMillis() + ",\"total\":" + counts.total() + ",\"live\":" + counts.live() + ",\"hunters\":" + counts.hunters() + ",\"phantoms\":" + counts.phantoms() + "}";
			final Path file = Path.of(STATUS_FILE);
			final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
			Files.writeString(tmp, json, StandardCharsets.UTF_8);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.FINE, "PhantomOnline: could not write " + STATUS_FILE + ".", e);
		}
	}
}
