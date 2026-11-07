package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.clan.Clan;

/** Shared declaration requirements and optional automatic war limits. */
public final class ClanWarPolicy
{
	static final int TRUCE_HOURS = Math.max(0, PhantomHuntersConfig.HUNTER_CLAN_WAR_TRUCE_HOURS);

	private ClanWarPolicy()
	{
	}

	static int load(Path file)
	{
		if (!Files.isRegularFile(file))
		{
			return 24;
		}
		try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
		{
			final Properties config = new Properties();
			config.load(reader);
			return Math.max(0, Math.min(720, Integer.parseInt(config.getProperty("TruceHours", "24").strip())));
		}
		catch (IOException | NumberFormatException e)
		{
			Logger.getLogger(ClanWarPolicy.class.getName()).log(Level.WARNING, "Could not read local clan war settings; using 24 hours.", e);
			return 24;
		}
	}

	public static int minimumLevel()
	{
		return Math.max(1, PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL);
	}

	public static int minimumMembers()
	{
		return Math.max(1, PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR);
	}

	public static boolean eligible(int level, int members)
	{
		return (level >= minimumLevel()) && (members >= minimumMembers());
	}

	public static boolean eligible(Clan clan)
	{
		return (clan != null) && eligible(clan.getLevel(), clan.getMembersCount());
	}

	public static String requirements()
	{
		return "Для войны оба клана должны быть от " + minimumLevel() + "-го уровня и иметь не меньше " + minimumMembers() + " участников.";
	}

	enum AutoEnd
	{
		NONE, KILLS, TIME
	}

	static AutoEnd automaticEnd(int kills, long elapsed, int killLimit, int hours)
	{
		if ((killLimit > 0) && (kills >= killLimit))
		{
			return AutoEnd.KILLS;
		}
		if ((hours > 0) && (elapsed >= (hours * 3600000L)))
		{
			return AutoEnd.TIME;
		}
		return AutoEnd.NONE;
	}

	static long endsAt(long since, int hours)
	{
		return (since <= 0) || (hours <= 0) ? 0 : since + (hours * 3600000L);
	}
}
