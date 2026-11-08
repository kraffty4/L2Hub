package org.l2jmobius.gameserver.managers.world;

import java.util.HashSet;
import java.util.List;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;

/** Полнота каталога поручений и описаний валют на обоих языках. */
public final class X10ContentCheck
{
	public static void main(String[] args) throws Exception
	{
		L2HubX10Config.DROP_CHANCE = 200;
		for (String language : new String[] {"ru", "en"})
		{
			L2HubWorldConfig.LANGUAGE = language;
			final String help = X10Economy.help();
			final var eventName = X10FarmEvent.class.getDeclaredMethod("name", X10EventRules.Kind.class);
			final var farmTip = X10FarmSpots.class.getDeclaredMethod("tip", String.class);
			farmTip.setAccessible(true);
			for (String role : new String[] {"solo", "spoil", "undead", "mage", "party", "conditional"})
			{
				final String label = (String) farmTip.invoke(null, role);
				if (label.isBlank() || language.equals("en") && label.matches(".*[А-Яа-яЁё].*")) { throw new AssertionError("Farm tip translation: " + role); }
			}
			eventName.setAccessible(true);
			for (X10EventRules.Kind kind : X10EventRules.Kind.values())
			{
				final String label = (String) eventName.invoke(null, kind);
				if (label.isBlank() || language.equals("en") && label.matches(".*[А-Яа-яЁё].*")) { throw new AssertionError("Event translation: " + kind); }
			}
			if (language.equals("en") ? !help.contains("Coins") || !help.contains("Style Tokens") || help.matches("(?s).*[А-Яа-яЁё].*") : !help.contains("Коины") || !help.contains("Жетоны стиля"))
			{
				throw new AssertionError("Incomplete currency translation: " + language);
			}
			for (String fieldName : new String[] {"DAILY", "WEEKLY"})
			{
				final var field = X10Contracts.class.getDeclaredField(fieldName);
				field.setAccessible(true);
				final List<?> definitions = (List<?>) field.get(null);
				final boolean weekly = fieldName.equals("WEEKLY");
				if (definitions.size() != (weekly ? 15 : 8))
				{
					throw new AssertionError("Contract catalogue size");
				}
				final var title = X10Contracts.class.getDeclaredMethod("title", String.class, boolean.class);
				title.setAccessible(true);
				final HashSet<String> ids = new HashSet<>();
				for (Object definition : definitions)
				{
					final var accessor = definition.getClass().getDeclaredMethod("id");
					accessor.setAccessible(true);
					final String id = (String) accessor.invoke(definition);
					final String label = (String) title.invoke(null, id, weekly);
					if (!ids.add(id) || label.isBlank() || (language.equals("en") && label.matches(".*[А-Яа-яЁё].*")))
					{
						throw new AssertionError("Invalid contract label or duplicate id: " + id);
					}
				}
			}
		}
		if (!X10Text.escape("<name & \"value\">").equals("&lt;name &amp; &quot;value&quot;&gt;"))
		{
			throw new AssertionError("HTML escaping");
		}
		System.out.println("X10 content: 8 daily choices, 15 weekly contracts, currency help and titles in RU/EN passed");
	}
}
