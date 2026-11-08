package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;

/**
 * Услуги магазина стиля за жетоны: цвет ника и титула, аура героя и свечение оружия на срок, смена ника.
 * Только внешний вид: ни статов, ни геройских умений. Сроки и цвета — в переменных персонажа, применяются при входе.
 */
final class X10Style
{
	private static final Logger LOGGER = Logger.getLogger(X10Style.class.getName());
	private static final Map<String, Service> SERVICES = new LinkedHashMap<>();
	/** Цвета в RGB; клиент H5 ждёт BGR, перевод — в bgr(). */
	private static final int[] COLORS = {0xFFD700, 0xFF8C00, 0xFF4040, 0xFF69B4, 0xB070FF, 0x40A0FF, 0x40E0D0, 0x60E060};
	private static final String[][] COLOR_NAMES = {{"Золотой", "Gold"}, {"Оранжевый", "Orange"}, {"Красный", "Red"}, {"Розовый", "Pink"}, {"Фиолетовый", "Violet"}, {"Синий", "Blue"}, {"Бирюзовый", "Turquoise"}, {"Зелёный", "Green"}};
	/** Свечение = видимая заточка; вид при каждом уровне рисует клиент. */
	private static final int[] GLOW_LEVELS = {6, 10, 13, 16};
	private static final String[][] GLOW_NAMES = {{"Слабое (+6)", "Soft (+6)"}, {"Среднее (+10)", "Medium (+10)"}, {"Сильное (+13)", "Strong (+13)"}, {"Максимальное (+16)", "Maximum (+16)"}};

	record Service(String key, long price, int days, String ru, String en, String aboutRu, String aboutEn)
	{
		String text()
		{
			return X10Text.text(ru, en);
		}
	}

	/** Цены и сроки — в L2HubX10StyleShop.xml (элементы service); без файла — значения по умолчанию. */
	static void load(Path file)
	{
		SERVICES.clear();
		put("name", 10, 7, "Цвет ника", "Name colour", "Ник другого цвета для всех вокруг.", "Your name in another colour for everyone.");
		put("title", 8, 7, "Цвет титула", "Title colour", "Титул другого цвета.", "Your title in another colour.");
		put("aura", 25, 7, "Аура героя", "Hero aura", "Свечение героя вокруг персонажа. Только вид: без геройских умений, оружия и чата.", "Hero glow around your character. Looks only: no hero skills, weapons or chat.");
		put("glow", 20, 7, "Свечение оружия", "Weapon glow", "Оружие светится как заточенное: от +6 до +16 на выбор. Только вид, статы не меняются.", "Your weapon glows as if enchanted, from +6 to +16. Looks only, stats are unchanged.");
		put("rename", 30, 0, "Смена ника", "Name change", "Новый ник навсегда. Друзья, клан и пати увидят новый ник.", "A new name for good. Friends, clan and party will see it.");
		try
		{
			final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			final var rows = factory.newDocumentBuilder().parse(file.toFile()).getDocumentElement().getElementsByTagName("service");
			for (int i = 0; i < rows.getLength(); i++)
			{
				final Element row = (Element) rows.item(i);
				final Service base = SERVICES.get(row.getAttribute("key"));
				if (base == null) { continue; }
				final long price = Long.parseLong(row.getAttribute("style"));
				final int days = row.hasAttribute("days") ? Integer.parseInt(row.getAttribute("days")) : base.days;
				if (price > 0 && days >= 0) { SERVICES.put(base.key, new Service(base.key, price, days, base.ru, base.en, base.aboutRu, base.aboutEn)); }
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, "X10 style services: defaults used", e);
		}
	}

	private static void put(String key, long price, int days, String ru, String en, String aboutRu, String aboutEn)
	{
		SERVICES.put(key, new Service(key, price, days, ru, en, aboutRu, aboutEn));
	}

	private static int bgr(int rgb)
	{
		return ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
	}

	private static String hex(int rgb)
	{
		return String.format("%06X", rgb);
	}

	private static long until(Player player, String key)
	{
		return player.getVariables().getLong("L2HubStyle_" + key, 0);
	}

	private static boolean active(Player player, String key)
	{
		return until(player, key) > System.currentTimeMillis();
	}

	static boolean heroAura(Player player)
	{
		return active(player, "aura");
	}

	static int enchantEffect(Player player)
	{
		final int real = player.getEnchantEffect();
		return active(player, "glow") && player.getActiveWeaponInstance() != null ? Math.max(real, player.getVariables().getInt("L2HubStyleGlowLevel", 16)) : real;
	}

	/** Раз в секунду из X10PlayerTools: держит купленные цвета и снимает истёкшие. */
	static void tick(Player player)
	{
		boolean changed = false;
		for (String key : new String[] {"name", "title", "aura", "glow"})
		{
			final long end = until(player, key);
			if (end > 0 && end <= System.currentTimeMillis())
			{
				player.getVariables().remove("L2HubStyle_" + key);
				if (key.equals("name")) { player.getAppearance().setNameColor(player.getVariables().getInt("L2HubStyleNameOrig", 0xFFFFFF)); }
				if (key.equals("title")) { player.getAppearance().setTitleColor(player.getVariables().getInt("L2HubStyleTitleOrig", 0xECF9A2)); }
				player.getVariables().storeMe();
				player.sendMessage(text("Закончился срок: ", "Expired: ") + SERVICES.get(key).text() + text(". Продлить — Alt+B → Ивенты → Магазин.", ". Renew in Alt+B → Events → Shop."));
				changed = true;
			}
		}
		if (active(player, "name"))
		{
			final int wanted = player.getVariables().getInt("L2HubStyleNameColor", 0xFFFFFF);
			if (player.getAppearance().getNameColor() != wanted) { player.getAppearance().setNameColor(wanted); changed = true; }
		}
		if (active(player, "title"))
		{
			final int wanted = player.getVariables().getInt("L2HubStyleTitleColor", 0xECF9A2);
			if (player.getAppearance().getTitleColor() != wanted) { player.getAppearance().setTitleColor(wanted); changed = true; }
		}
		if (changed) { player.broadcastUserInfo(); }
	}

	private static String left(Player player, String key)
	{
		final long ms = until(player, key) - System.currentTimeMillis();
		if (ms <= 0) { return ""; }
		final long hours = ms / 3600000;
		return hours >= 24 ? text("ещё ", "") + (hours / 24) + text(" дн.", " d left") : text("ещё ", "") + Math.max(1, hours) + text(" ч", " h left");
	}

	/** Раздел услуг на странице магазина: строка на услугу, кнопка ведёт к выбору/подтверждению. */
	static String services(Player player, String prefix)
	{
		final StringBuilder out = new StringBuilder("<font color=\"LEVEL\">").append(text("Услуги стиля", "Style services")).append("</font><br1><table width=520 cellpadding=0 cellspacing=3>");
		for (Service s : SERVICES.values())
		{
			final String state = left(player, s.key);
			out.append("<tr><td width=330 valign=top>").append(s.text()).append(" · <font color=\"LEVEL\">").append(s.price).append("</font>").append(text(" жетонов", " tokens"))
				.append(s.days > 0 ? text(" / ", " / ") + s.days + text(" дн.", " d") : "").append(state.isEmpty() ? "" : " <font color=\"55DD55\">(" + state + ")</font>")
				.append("<br1><font color=\"AFAFAF\">").append(text(s.aboutRu, s.aboutEn)).append("</font></td><td width=180 align=right valign=top>")
				.append(X10Text.button(state.isEmpty() ? text("Выбрать", "Choose") : text("Продлить", "Renew"), prefix + " style " + s.key, 140)).append("</td></tr><tr><td height=4></td></tr>");
		}
		return out.append("</table>").toString();
	}

	/** Страницы выбора и подтверждения: style &lt;key&gt; [ask|buy] [цвет|ник]. */
	static String page(Player player, String[] args, String prefix)
	{
		final String key = args.length > 1 ? args[1] : "";
		final Service s = SERVICES.get(key);
		if (s == null) { return services(player, prefix); }
		final String step = args.length > 2 ? args[2] : "";
		final StringBuilder out = new StringBuilder("<font color=\"LEVEL\">").append(s.text()).append("</font> · ").append(s.price).append(text(" жетонов стиля", " Style Tokens")).append(s.days > 0 ? text(" на ", " for ") + s.days + text(" дн.", " days") : "").append("<br1><font color=\"AFAFAF\">").append(text(s.aboutRu, s.aboutEn)).append("</font><br><br>");
		if (step.equals("buy"))
		{
			out.append("<font color=\"LEVEL\">").append(X10Text.escape(buy(player, s, args))).append("</font><br><br>");
			return out.append(X10Text.button(text("← К магазину", "← Back to shop"), prefix + " shop", 160)).toString();
		}
		if ((key.equals("name") || key.equals("title")) && !step.equals("ask"))
		{
			out.append(text("Выбери цвет:", "Choose a colour:")).append("<br><table width=520 cellpadding=0 cellspacing=4>");
			for (int i = 0; i < COLORS.length; i++)
			{
				if (i % 2 == 0) { out.append("<tr>"); }
				out.append("<td width=130><font color=\"").append(hex(COLORS[i])).append("\">").append(X10Text.escape(player.getName())).append("</font></td><td width=130>")
					.append(X10Text.button(text(COLOR_NAMES[i][0], COLOR_NAMES[i][1]), prefix + " style " + key + " ask " + i, 120)).append("</td>");
				if (i % 2 == 1) { out.append("</tr>"); }
			}
			return out.append("</table><br>").append(X10Text.button(text("← Назад", "← Back"), prefix + " shop", 120)).toString();
		}
		if (key.equals("glow") && !step.equals("ask"))
		{
			out.append(text("Выбери силу свечения:", "Choose the glow strength:")).append("<br><table width=520 cellpadding=0 cellspacing=4>");
			for (int i = 0; i < GLOW_LEVELS.length; i++)
			{
				if (i % 2 == 0) { out.append("<tr>"); }
				out.append("<td width=255>").append(X10Text.button(text(GLOW_NAMES[i][0], GLOW_NAMES[i][1]), prefix + " style glow ask " + i, 200)).append("</td>");
				if (i % 2 == 1) { out.append("</tr>"); }
			}
			return out.append("</table><br>").append(X10Text.button(text("← Назад", "← Back"), prefix + " shop", 120)).toString();
		}
		if (key.equals("rename") && !step.equals("ask"))
		{
			out.append(text("Новый ник (буквы и цифры, как при создании персонажа):", "New name (letters and digits, as at character creation):")).append("<br1><edit var=\"newname\" width=200 height=15><br>")
				.append(X10Text.button(text("Дальше", "Next"), prefix + " style rename ask $newname", 140)).append("<br><br>").append(X10Text.button(text("← Назад", "← Back"), prefix + " shop", 120));
			return out.toString();
		}
		// Подтверждение покупки.
		final String extra = args.length > 3 ? args[3] : "";
		String what = s.text();
		if (key.equals("name") || key.equals("title"))
		{
			final int i = colorIndex(extra);
			if (i < 0) { return out.append(text("Цвет не найден.", "Colour not found.")).toString(); }
			what += " · <font color=\"" + hex(COLORS[i]) + "\">" + text(COLOR_NAMES[i][0], COLOR_NAMES[i][1]) + "</font>";
		}
		if (key.equals("glow"))
		{
			final int i = glowIndex(extra);
			if (i < 0) { return out.append(text("Вариант не найден.", "Option not found.")).toString(); }
			what += " · " + text(GLOW_NAMES[i][0], GLOW_NAMES[i][1]);
		}
		if (key.equals("rename"))
		{
			final String error = nameError(extra);
			if (!error.isEmpty()) { return out.append("<font color=\"FF6060\">").append(error).append("</font><br><br>").append(X10Text.button(text("← Другой ник", "← Another name"), prefix + " style rename", 160)).toString(); }
			what += ": " + X10Text.escape(extra);
		}
		out.append(text("Купить ", "Buy ")).append(what).append(text(" за ", " for ")).append(s.price).append(text(" жетонов стиля?", " Style Tokens?")).append(active(player, key) ? text(" Срок продлится.", " The time will be extended.") : "").append("<br><br>")
			.append(X10Text.button(text("Купить", "Buy"), prefix + " style " + key + " buy " + extra, 140)).append(" ").append(X10Text.button(text("Отмена", "Cancel"), prefix + " shop", 140));
		return out.toString();
	}

	private static int colorIndex(String value)
	{
		try
		{
			final int i = Integer.parseInt(value);
			return i >= 0 && i < COLORS.length ? i : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static int glowIndex(String value)
	{
		try
		{
			final int i = Integer.parseInt(value);
			return i >= 0 && i < GLOW_LEVELS.length ? i : -1;
		}
		catch (NumberFormatException e)
		{
			return -1;
		}
	}

	private static String nameError(String name)
	{
		if (name == null || name.isBlank()) { return text("Введи ник.", "Enter a name."); }
		if (name.length() > 16 || !ServerConfig.CHARNAME_TEMPLATE_PATTERN.matcher(name).matches()) { return text("Такой ник нельзя: только буквы и цифры, до 16 знаков.", "This name is not allowed: letters and digits only, up to 16 characters."); }
		if (CharInfoTable.getInstance().doesCharNameExist(name)) { return text("Этот ник уже занят.", "This name is already taken."); }
		return "";
	}

	private static String buy(Player player, Service s, String[] args)
	{
		if (PhantomManager.getInstance().isPhantom(player)) { return ""; }
		final String extra = args.length > 3 ? args[3] : "";
		final int color = colorIndex(extra);
		if ((s.key.equals("name") || s.key.equals("title")) && color < 0) { return text("Цвет не найден.", "Colour not found."); }
		if (s.key.equals("glow") && glowIndex(extra) < 0) { return text("Вариант не найден.", "Option not found."); }
		if (s.key.equals("rename"))
		{
			final String error = nameError(extra);
			if (!error.isEmpty()) { return error; }
		}
		final String source = "style:" + s.key + ":" + UUID.randomUUID();
		final int result = X10Economy.change(player.getObjectId(), 0, -s.price, source, true);
		if (result != 1)
		{
			return result == 0 ? text("Не хватает жетонов стиля.", "Not enough Style Tokens.") : text("Покупка временно недоступна.", "Purchases temporarily unavailable.");
		}
		try
		{
			apply(player, s, extra, color);
			X10Economy.syncInventory(player);
			return s.days > 0 ? s.text() + text(": готово, ", ": done, ") + left(player, s.key) + "." : s.text() + text(": готово.", ": done.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "X10 style service failed: " + source, e);
			X10Economy.credit(player, 0, s.price, "refund:" + source);
			return text("Не получилось, жетоны возвращены.", "It failed; tokens were refunded.");
		}
	}

	private static void apply(Player player, Service s, String extra, int color)
	{
		final var vars = player.getVariables();
		if (s.days > 0)
		{
			final long from = Math.max(System.currentTimeMillis(), until(player, s.key));
			vars.set("L2HubStyle_" + s.key, from + s.days * 86400000L);
		}
		switch (s.key)
		{
			case "name" ->
			{
				if (!vars.contains("L2HubStyleNameOrig")) { vars.set("L2HubStyleNameOrig", player.getAppearance().getNameColor()); }
				vars.set("L2HubStyleNameColor", bgr(COLORS[color]));
			}
			case "title" ->
			{
				if (!vars.contains("L2HubStyleTitleOrig")) { vars.set("L2HubStyleTitleOrig", player.getAppearance().getTitleColor()); }
				vars.set("L2HubStyleTitleColor", bgr(COLORS[color]));
			}
			case "glow" -> vars.set("L2HubStyleGlowLevel", GLOW_LEVELS[glowIndex(extra)]);
			case "rename" ->
			{
				final String old = player.getName();
				player.setName(extra);
				player.storeMe();
				LOGGER.info("X10 style rename: " + old + " -> " + extra);
			}
			default ->
			{
			}
		}
		vars.storeMe();
		tick(player);
		player.broadcastUserInfo();
		if (player.getClan() != null) { player.getClan().broadcastClanStatus(); }
	}

	private X10Style()
	{
	}
}
