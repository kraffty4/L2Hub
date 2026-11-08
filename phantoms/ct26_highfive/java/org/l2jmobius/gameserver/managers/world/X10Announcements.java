package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.entity.World;

/** RU/EN объявления прежних PvP без изменения их механики. */
public final class X10Announcements
{
	public static String translate(String message)
	{
		if ("en".equals(L2HubWorldConfig.LANGUAGE)) { return message.replaceAll("You can register at Giran .*?Event Manager\\.", "Sign up in Alt+B → Events → PvP arenas."); }
		return message.replace(" Event:", ":")
			.replace("Registration opened for ", "Запись открыта на ").replace(" minutes.", " мин.")
			.replaceAll("You can register at Giran .*?Event Manager\\.", "Запись: Alt+B → Ивенты → PvP-арены.")
			.replace("Event was canceled, not enough participants.", "Событие отменено: мало участников.")
			.replace("Event was canceled.", "Событие отменено.");
	}

	public static void broadcast(String message) { World.broadcastToAllOnlinePlayers(translate(message)); }
	private X10Announcements() { }
}
