package custom.L2HubX10Smoke;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.data.xml.MultisellData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.handler.CommunityBoardHandler;
import org.l2jmobius.gameserver.managers.world.X10World;

/** Только для отдельного тестового сервера: скопировать в data/scripts/custom/L2HubX10Smoke. Мир не изменяет. */
public final class X10BoardSmoke
{
	private static final Logger LOGGER = Logger.getLogger(X10BoardSmoke.class.getName());
	private static int attempts;

	public static void main(String[] args)
	{
		ThreadPool.schedule(X10BoardSmoke::run, 90000);
	}

	private static void check(boolean value, String message)
	{
		if (!value) { throw new AssertionError(message); }
	}

	private static void run()
	{
		final Player player = World.getPlayers().stream().filter(p -> p.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL).findFirst().orElse(null);
		if (player == null && ++attempts < 8)
		{
			ThreadPool.schedule(X10BoardSmoke::run, 15000);
			return;
		}
		try
		{
			check(player != null, "No suitable test-world hunter");
			for (String command : new String[] {"_bbshubfarm", "_bbshubshop", "_bbshubhelp"})
			{
				check(CommunityBoardHandler.getInstance().getHandler(command) != null, "Missing handler " + command);
			}
			final String normal = X10World.farmSpots(player, new String[] {"_bbshubfarm", "spots", "recommended", "0"});
			final String elite = X10World.farmSpots(player, new String[] {"_bbshubfarm", "spots", "adena", "0"});
			check(normal.contains("36") && normal.contains("_bbshubfarm spots go "), "Normal areas/count/TP");
			check(elite.contains("72") && elite.contains("_bbshubfarm spots go "), "Elite areas/count/TP");
			check(normal.contains(Integer.toString(L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE)), "Actual teleport price");
			check(normal.contains("Доп. мобов:") || normal.contains("Extra monsters:"), "Missing count label");
			final Class<?> board = Class.forName("handlers.bypass.communityboard.L2HubBoard");
			final var method = board.getDeclaredMethod("shop", Player.class, String[].class);
			method.setAccessible(true);
			final String shop = (String) method.invoke(null, player, new String[] {"_bbshubshop"});
			check(shop.contains("_bbshubshop"), "GM-shop page is empty");
			check(MultisellData.getInstance().getList(50039001) != null, "GM-shop weapon list not loaded");
			Files.writeString(Path.of("log/x10-smoke-farm.html"), normal);
			Files.writeString(Path.of("log/x10-smoke-adena.html"), elite);
			Files.writeString(Path.of("log/x10-smoke-shop.html"), shop);
			Files.writeString(Path.of("log/x10-board-smoke.json"), "{\"passed\":true,\"normalExtra\":36,\"eliteExtra\":72,\"fee\":" + L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE + "}");
			LOGGER.info("X10 board smoke: PASSED; farm HTML, monster counts, fee, GM shop and handlers.");
		}
		catch (Throwable e)
		{
			LOGGER.log(Level.SEVERE, "X10 board smoke: FAILED", e);
		}
	}
}
