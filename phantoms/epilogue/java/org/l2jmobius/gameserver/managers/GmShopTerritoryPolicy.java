package org.l2jmobius.gameserver.managers;

import java.util.Locale;
import java.util.regex.Pattern;
import org.l2jmobius.commons.util.ConfigReader;

/** Единый выключатель продажи территориальных флагов; предметы самой ТВ не меняются. */
public final class GmShopTerritoryPolicy
{
	private static final Pattern BUTTON = Pattern.compile("(?is)<button\\b(?=[^>]*\\baction\\s*=\\s*\"[^\"]*(?:multisell\\s+5003999[12]|admin_buy\\s+999[12])\\b)[^>]*>(?:\\s*<br1>)?");
	private static final class Settings
	{
		private static final boolean ALLOW = new ConfigReader("./config/Custom/GmShopTerritory.ini").getBoolean("GmShopAllowTerritoryFlags", false);
	}
	private GmShopTerritoryPolicy() {}
	public static boolean enabled() { return Settings.ALLOW; }
	public static boolean territoryItem(int id) { return id >= 13530 && id <= 13538 || id >= 13560 && id <= 13568; }
	public static boolean allows(int id) { return enabled() || !territoryItem(id); }
	public static boolean catalog(int id) { return id == 9991 || id == 9992 || id == 50039991 || id == 50039992; }

	/** Скрываем только две кнопки, сохраняя язык и остальную страницу магазина. */
	public static String html(String path, String content)
	{
		if (enabled() || path == null || content == null) return content;
		final String normalized = path.replace('\\', '/').toLowerCase(Locale.ROOT);
		if (!normalized.endsWith("html/merchant/50010-6.htm") && !normalized.endsWith("html/admin/gmstore/territory.htm")) return content;
		return BUTTON.matcher(content).replaceAll("");
	}
}
