package org.l2jmobius.gameserver.managers.world;

import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;

/** Двуязычные строки новых окон; пользовательский текст экранируется отдельно. */
final class X10Text
{
	static String text(String ru, String en)
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE) ? en : ru;
	}

	static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static String button(String title, String action, int width)
	{
		return "<button value=\"" + escape(title) + "\" action=\"" + escape(action) + "\" width=" + width + " height=21 back=\"" + PhantomCompat.BUTTON_BACK + "\" fore=\"" + PhantomCompat.BUTTON_FORE + "\">";
	}

	static String pager(int current, int pages, String prefix, int width)
	{
		// Ячейки фиксированной ширины и высоты: кнопки не уезжают, когда соседней нет.
		// Те же поля, что у списков (cellpadding=4, правая колонка 140): «Назад» под левым краем текста, «Далее» под кнопками справа.
		return "<table width=" + width + " cellpadding=4 cellspacing=0><tr><td width=" + (width - 145) + " height=26 align=left valign=middle><table width=" + (width - 145) + " cellpadding=0 cellspacing=0><tr><td width=130>" + (current > 0 ? button(text("← Назад", "← Back"), prefix + (current - 1), 130) : "")
			+ "</td><td align=center>" + (current + 1) + " / " + pages + "</td></tr></table></td><td width=140 align=left valign=middle>"
			+ (current + 1 < pages ? button(text("Далее →", "Next →"), prefix + (current + 1), 130) : "") + "</td></tr></table>";
	}

	/** Предупреждение в чат заметным цветом (тип сообщения подобран по клиенту H5). */
	static void warn(org.l2jmobius.gameserver.entity.actor.Player player, String message)
	{
		// Доска H5 иногда присылает одно нажатие дважды: одно и то же предупреждение — не чаще раза в 3 секунды.
		final String key = player.getObjectId() + ":" + message;
		final long now = System.currentTimeMillis();
		final Long last = WARNED.put(key, now);
		if (last != null && now - last < 3000) { return; }
		if (WARNED.size() > 500) { WARNED.clear(); }
		player.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.CreatureSay(null, WARN_TYPE, "", message));
	}

	private static final java.util.Map<String, Long> WARNED = new java.util.concurrent.ConcurrentHashMap<>();

	static org.l2jmobius.gameserver.network.enums.ChatType WARN_TYPE = org.l2jmobius.gameserver.network.enums.ChatType.CRITICAL_ANNOUNCE;

	static String tabs(String[] titles, String[] actions, int width)
	{
		final StringBuilder out = new StringBuilder("<table width=" + width + " cellpadding=2><tr>");
		for (int i = 0; i < titles.length; i++) { out.append("<td>").append(button(titles[i], actions[i], width / titles.length - 6)).append("</td>"); }
		return out.append("</tr></table>").toString();
	}

	private X10Text()
	{
	}
}
