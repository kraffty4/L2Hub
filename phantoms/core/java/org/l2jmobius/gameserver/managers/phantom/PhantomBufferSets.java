package org.l2jmobius.gameserver.managers.phantom;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.SchemeBufferConfig;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: свои наборы бафов на доске Alt+B (docs/PHANTOMS.md, раздел 4.60; по вкладу пользователя). Хранилище то же, что у городского баффера схем: таблица
 * {@code buffer_schemes} и {@link SchemeBufferTable}, поэтому набор виден и там и там, а лимит и плата те же: {@code BufferMaxSchemesPerChar},
 * {@code BufferStaticCostPerBuff}, {@code BufferItemId}. Команды идут через {@code _bbshubbuff}, питомца и «можно ли сейчас бафать» передаёт доска: они у хроник разные.
 * <p>
 * Имя набора в bypass — base64: в нём могут быть кириллица и пробел. Бафы одного типа в наборе заменяют друг друга; какие составные бафы что перекрывают, здесь не
 * прошито: лишний баф просто займёт слот или не наложится.
 */
public final class PhantomBufferSets
{
	private static final Logger LOGGER = Logger.getLogger(PhantomBufferSets.class.getName());

	private static final String BOARD = "_bbshubbuff ";
	private static final int PAGE_SIZE = 12;
	private static final int NAME_LIMIT = 14;
	private static final String SAVE = "INSERT INTO buffer_schemes (object_id,scheme_name,skills) VALUES (?,?,?) ON DUPLICATE KEY UPDATE skills=VALUES(skills)";
	private static final String DELETE = "DELETE FROM buffer_schemes WHERE object_id=? AND scheme_name=?";

	private PhantomBufferSets()
	{
	}

	/**
	 * @return заголовок страницы и подпись кнопки на странице баффера
	 */
	public static String title()
	{
		return tr("Мои наборы");
	}

	/**
	 * _bbshubbuff schemes | scheme_create|scheme_capture &lt;имя&gt; | scheme_edit &lt;набор&gt; [категория] [страница] | scheme_toggle &lt;набор&gt; &lt;id&gt; &lt;категория&gt; &lt;страница&gt;
	 * | scheme_capture_existing &lt;набор&gt; | scheme_delete &lt;набор&gt; | scheme_cast &lt;набор&gt; [pet]
	 * @param args слова команды, первое — {@code _bbshubbuff}
	 * @param mayCast сейчас можно бафать; отказ доска уже написала в чат
	 * @param pet питомец игрока или {@code null}
	 * @return содержимое страницы
	 */
	public static String handle(Player player, String[] args, boolean mayCast, Creature pet)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "schemes";
		if (action.equals("scheme_create") || action.equals("scheme_capture"))
		{
			return create(player, args, action.equals("scheme_capture"), pet);
		}
		if (action.equals("schemes"))
		{
			return list(player, pet);
		}
		final String name = known(player, args.length > 2 ? decode(args[2]) : null);
		if (name == null)
		{
			player.sendMessage(tr("Набор не найден у этого персонажа."));
			return list(player, pet);
		}
		final boolean toggle = action.equals("scheme_toggle");
		final String category = category(args.length > (toggle ? 4 : 3) ? args[toggle ? 4 : 3] : "");
		final int page = number(args, toggle ? 5 : 4);
		switch (action)
		{
			case "scheme_edit":
			{
				return editor(player, name, category, page);
			}
			case "scheme_toggle":
			{
				toggle(player, name, number(args, 3), category);
				return editor(player, name, category, page);
			}
			case "scheme_capture_existing":
			{
				final List<Integer> buffs = currentBuffs(player);
				if (buffs.isEmpty())
				{
					player.sendMessage(tr("Нет доступных бафов для сохранения. Старый набор оставлен."));
				}
				else if (withinLimits(player, buffs) && persist(player, name, buffs))
				{
					player.sendMessage(tr("Текущие бафы записаны в набор «") + name + tr("»."));
				}
				return editor(player, name, category, 0);
			}
			case "scheme_delete":
			{
				delete(player, name, table);
				return list(player, pet);
			}
			case "scheme_cast":
			{
				if (mayCast)
				{
					final Creature target = (args.length > 3) && args[3].equalsIgnoreCase("pet") ? pet : player;
					if (target == null)
					{
						player.sendMessage(tr("У тебя нет питомца."));
					}
					else
					{
						cast(player, name, target);
					}
				}
				return list(player, pet);
			}
			default:
			{
				return list(player, pet);
			}
		}
	}

	private static String create(Player player, String[] args, boolean capture, Creature pet)
	{
		final String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip() : "";
		final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		if (!validName(name))
		{
			player.sendMessage(tr("Название: от 1 до 14 букв или цифр; можно пробел, _ и -."));
		}
		else if ((schemes != null) && schemes.containsKey(name))
		{
			player.sendMessage(tr("Такой набор уже есть. Открой его для изменения."));
		}
		else if ((schemes != null) && (schemes.size() >= SchemeBufferConfig.BUFFER_MAX_SCHEMES))
		{
			player.sendMessage(tr("Достигнут лимит наборов: ") + SchemeBufferConfig.BUFFER_MAX_SCHEMES + ".");
		}
		else
		{
			final List<Integer> buffs = capture ? currentBuffs(player) : new ArrayList<>();
			if (capture && buffs.isEmpty())
			{
				player.sendMessage(tr("Сейчас на тебе нет бафов, доступных у этого баффера."));
			}
			else if (withinLimits(player, buffs) && persist(player, name, buffs))
			{
				player.sendMessage(tr("Набор сохранён: ") + name + ".");
				return editor(player, name, category(""), 0);
			}
		}
		return list(player, pet);
	}

	private static void toggle(Player player, String name, int id, String category)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final List<Integer> buffs = new ArrayList<>(table.getScheme(player.getObjectId(), name));
		if (buffs.contains(id))
		{
			buffs.remove(Integer.valueOf(id));
		}
		else
		{
			final Skill skill = skill(id);
			if ((skill == null) || !table.getSkillsIdsByType(category).contains(id))
			{
				player.sendMessage(tr("Этот баф недоступен в выбранной категории."));
				return;
			}
			buffs.removeIf(previous -> sameType(skill(previous), skill));
			buffs.add(id);
		}
		if (withinLimits(player, buffs))
		{
			persist(player, name, buffs);
		}
	}

	private static void cast(Player player, String name, Creature target)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final List<Integer> ids = table.getScheme(player.getObjectId(), name);
		if (ids.isEmpty())
		{
			player.sendMessage(tr("Набор пуст."));
			return;
		}
		for (int id : ids)
		{
			if (skill(id) == null)
			{
				player.sendMessage(tr("В наборе есть недоступный баф. Открой набор для изменения."));
				return;
			}
		}
		final int fee = fee(ids);
		if ((fee > 0) && !(SchemeBufferConfig.BUFFER_ITEM_ID == 57 ? player.reduceAdena(ItemProcessType.FEE, fee, player, true) : player.destroyItemByItemId(ItemProcessType.FEE, SchemeBufferConfig.BUFFER_ITEM_ID, fee, player, true)))
		{
			return;
		}
		for (int id : ids)
		{
			skill(id).applyEffects(player, target);
		}
		player.sendMessage(tr("Набор «") + name + tr("»: наложено бафов ") + ids.size() + ".");
	}

	/**
	 * Плата как у городского баффера.
	 */
	private static int fee(List<Integer> ids)
	{
		if (SchemeBufferConfig.BUFFER_STATIC_BUFF_COST > 0)
		{
			return ids.size() * SchemeBufferConfig.BUFFER_STATIC_BUFF_COST;
		}
		int fee = 0;
		for (int id : ids)
		{
			final BuffSkillHolder holder = SchemeBufferTable.getInstance().getAvailableBuff(id);
			fee += holder == null ? 0 : holder.getPrice();
		}
		return fee;
	}

	private static boolean persist(Player player, String name, List<Integer> ids)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		synchronized (table)
		{
			try (Connection connection = DatabaseFactory.getConnection();
				PreparedStatement statement = connection.prepareStatement(SAVE))
			{
				statement.setInt(1, player.getObjectId());
				statement.setString(2, name);
				statement.setString(3, ids.stream().map(String::valueOf).collect(Collectors.joining(",")));
				statement.executeUpdate();
				table.setScheme(player.getObjectId(), name, ids);
				return true;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "PhantomBufferSets: could not save a set of " + player.getName() + ".", e);
				player.sendMessage(tr("Не удалось сохранить набор. Попробуй ещё раз."));
				return false;
			}
		}
	}

	private static void delete(Player player, String name, SchemeBufferTable table)
	{
		synchronized (table)
		{
			try (Connection connection = DatabaseFactory.getConnection();
				PreparedStatement statement = connection.prepareStatement(DELETE))
			{
				statement.setInt(1, player.getObjectId());
				statement.setString(2, name);
				statement.executeUpdate();
				table.deleteScheme(player.getObjectId(), name);
				player.sendMessage(tr("Набор удалён: ") + name + ".");
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "PhantomBufferSets: could not delete a set of " + player.getName() + ".", e);
				player.sendMessage(tr("Не удалось удалить набор. Попробуй ещё раз."));
			}
		}
	}

	private static List<Integer> currentBuffs(Player player)
	{
		final List<Integer> result = new ArrayList<>();
		for (BuffInfo info : player.getEffectList().getEffects())
		{
			final int id = info.getSkill().getId();
			final Skill available = skill(id);
			if (info.isInUse() && (available != null) && !info.getSkill().isDebuff() && !result.contains(id))
			{
				result.removeIf(previous -> sameType(skill(previous), available));
				result.add(id);
			}
		}
		return result;
	}

	private static boolean sameType(Skill first, Skill second)
	{
		if ((first == null) || (second == null))
		{
			return false;
		}
		final String type = first.getAbnormalType().name();
		return !type.equals("NONE") && type.equals(second.getAbnormalType().name());
	}

	private static boolean withinLimits(Player player, List<Integer> ids)
	{
		int buffs = 0;
		int dances = 0;
		for (int id : ids)
		{
			final Skill skill = skill(id);
			if ((skill != null) && skill.isDance())
			{
				dances++;
			}
			else if (skill != null)
			{
				buffs++;
			}
		}
		if ((buffs <= player.getStat().getMaxBuffCount()) && (dances <= PlayerConfig.DANCES_MAX_AMOUNT))
		{
			return true;
		}
		player.sendMessage(tr("Лимит набора: ") + player.getStat().getMaxBuffCount() + tr(" обычных бафов и ") + PlayerConfig.DANCES_MAX_AMOUNT + tr(" песен и танцев."));
		return false;
	}

	private static String list(Player player, Creature pet)
	{
		final StringBuilder html = new StringBuilder(4096);
		html.append(button(tr("К бафам"), "", 120)).append("<br>");
		html.append(tr("До ")).append(SchemeBufferConfig.BUFFER_MAX_SCHEMES).append(tr(" наборов на персонажа. Доступны и у городского баффера.")).append("<br><table width=500><tr><td width=190><edit var=\"buff_scheme_name\" width=180 height=20></td><td>");
		html.append(button(tr("Создать пустой"), "scheme_create $buff_scheme_name", 145)).append("</td><td>");
		html.append(button(tr("Из моих бафов"), "scheme_capture $buff_scheme_name", 145)).append("</td></tr></table><br>");
		html.append(tr("Название: до 14 символов. Чтобы сохранить готовый набор, сначала наложи нужные бафы и нажми «Из моих бафов».")).append("<br>");
		final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		if ((schemes == null) || schemes.isEmpty())
		{
			return html.append(tr("Сохранённых наборов пока нет.")).toString();
		}
		for (Map.Entry<String, List<Integer>> entry : schemes.entrySet())
		{
			final String token = encode(entry.getKey());
			final int fee = fee(entry.getValue());
			html.append("<table width=500 bgcolor=252525><tr><td colspan=4><font color=LEVEL>").append(escape(entry.getKey())).append("</font>").append(tr(" — бафов: ")).append(entry.getValue().size()).append(fee > 0 ? tr("; цена ") + fee : "").append("</td></tr><tr><td>");
			html.append(button(tr("На себя"), "scheme_cast " + token, 90)).append("</td><td>");
			if (pet != null)
			{
				html.append(button(tr("Питомцу"), "scheme_cast " + token + " pet", 90));
			}
			html.append("</td><td>").append(button(tr("Изменить"), "scheme_edit " + token, 110)).append("</td><td>");
			html.append(button(tr("Удалить"), "scheme_delete " + token, 90)).append("</td></tr></table><br>");
		}
		return html.toString();
	}

	private static String editor(Player player, String name, String category, int requestedPage)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final String token = encode(name);
		final List<Integer> selected = table.getScheme(player.getObjectId(), name);
		final List<Integer> available = table.getSkillsIdsByType(category).stream().filter(id -> skill(id) != null).toList();
		final int pages = Math.max(1, (available.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = Math.max(0, Math.min(requestedPage, pages - 1));
		final StringBuilder html = new StringBuilder(8192);
		html.append("<table width=300><tr><td>").append(button(title(), "schemes", 130)).append("</td><td>").append(button(tr("Наложить"), "scheme_cast " + token, 110)).append("</td></tr></table><br>");
		html.append("<font color=LEVEL>").append(escape(name)).append("</font>").append(tr(" — выбрано бафов: ")).append(selected.size()).append(".<br1>");
		html.append(tr("Лимит: ")).append(player.getStat().getMaxBuffCount()).append(tr(" обычных бафов и ")).append(PlayerConfig.DANCES_MAX_AMOUNT).append(tr(" песен и танцев.")).append("<br1>");
		html.append(tr("Нажми на баф, чтобы добавить или убрать его. Изменения сохраняются сразу. Бафы одного типа заменяют друг друга.")).append("<br><table width=510><tr>");
		int column = 0;
		for (String type : table.getSkillTypes())
		{
			if ((column > 0) && ((column % 6) == 0))
			{
				html.append("</tr><tr>");
			}
			html.append("<td>").append(button(type.equalsIgnoreCase(category) ? "[" + categoryName(type) + "]" : categoryName(type), "scheme_edit " + token + " " + type + " 0", 82)).append("</td>");
			column++;
		}
		html.append("</tr></table><br><table width=510>");
		final int end = Math.min(available.size(), (page + 1) * PAGE_SIZE);
		for (int index = page * PAGE_SIZE; index < end; index++)
		{
			if ((index % 2) == 0)
			{
				html.append("<tr>");
			}
			final int id = available.get(index);
			final String action = "bypass " + BOARD + "scheme_toggle " + token + " " + id + " " + category + " " + page;
			final String icon = String.format("icon.skill%04d", id);
			final boolean active = selected.contains(id);
			html.append("<td width=36><button value=\" \" action=\"").append(action).append("\" width=32 height=32 back=\"").append(icon).append("\" fore=\"").append(icon).append("\"></td><td width=215>");
			html.append("<a action=\"").append(action).append("\"><font color=\"").append(active ? "88DD88" : "FFFFFF").append("\">").append(active ? "[-] " : "[+] ").append(escape(skill(id).getName())).append("</font></a><br1>").append(tr("Ур. ")).append(table.getAvailableBuff(id).getLevel()).append("</td>");
			if ((index % 2) == 1)
			{
				html.append("</tr>");
			}
		}
		if ((end % 2) == 1)
		{
			html.append("<td></td><td></td></tr>");
		}
		html.append("</table><br><table width=400><tr><td>");
		if (page > 0)
		{
			html.append(button(tr("Назад"), "scheme_edit " + token + " " + category + " " + (page - 1), 90));
		}
		html.append("</td><td align=center>").append(page + 1).append(" / ").append(pages).append("</td><td>");
		if ((page + 1) < pages)
		{
			html.append(button(tr("Далее"), "scheme_edit " + token + " " + category + " " + (page + 1), 90));
		}
		html.append("</td></tr></table><br>").append(button(tr("Заменить моими бафами"), "scheme_capture_existing " + token + " " + category, 230));
		html.append("<br><font color=LEVEL>").append(tr("Состав: ")).append("</font>");
		for (int id : selected)
		{
			final Skill skill = skill(id);
			if (skill != null)
			{
				html.append(escape(skill.getName())).append("; ");
			}
			else
			{
				html.append("<a action=\"bypass ").append(BOARD).append("scheme_toggle ").append(token).append(' ').append(id).append(' ').append(category).append(' ').append(page).append("\">").append(tr("Убрать недоступный #")).append(id).append("</a>; ");
			}
		}
		return html.toString();
	}

	private static Skill skill(int id)
	{
		final BuffSkillHolder holder = SchemeBufferTable.getInstance().getAvailableBuff(id);
		return holder == null ? null : SkillData.getInstance().getSkill(id, holder.getLevel());
	}

	/**
	 * @return имя набора, как оно хранится у игрока, или {@code null}
	 */
	private static String known(Player player, String requested)
	{
		final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		if ((requested == null) || (schemes == null))
		{
			return null;
		}
		return schemes.keySet().stream().filter(key -> key.equalsIgnoreCase(requested)).findFirst().orElse(null);
	}

	private static boolean validName(String name)
	{
		return (name != null) && !name.isBlank() && (name.length() <= NAME_LIMIT) && name.matches("[\\p{L}\\p{N} _-]+");
	}

	private static String encode(String name)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String value)
	{
		try
		{
			final String name = value.length() > 80 ? null : new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
			return validName(name) ? name : null;
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	private static int number(String[] args, int index)
	{
		try
		{
			return index < args.length ? Math.max(0, Integer.parseInt(args[index])) : 0;
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	private static String button(String label, String action, int width)
	{
		return "<button value=\"" + escape(label) + "\" action=\"bypass " + BOARD + action + "\" width=" + width + " height=25 back=\"" + (width > 90 ? PhantomCompat.WIDE_BUTTON_BACK : PhantomCompat.BUTTON_BACK) + "\" fore=\"" + (width > 90 ? PhantomCompat.WIDE_BUTTON_FORE : PhantomCompat.BUTTON_FORE) + "\">";
	}

	/**
	 * @return категория баффера, как она записана в таблице; незнакомая — первая
	 */
	private static String category(String value)
	{
		final List<String> types = SchemeBufferTable.getInstance().getSkillTypes();
		return types.stream().filter(type -> type.equalsIgnoreCase(value)).findFirst().orElse(types.isEmpty() ? "" : types.get(0));
	}

	private static String categoryName(String type)
	{
		switch (type.toLowerCase(Locale.ROOT))
		{
			case "buffs":
			{
				return tr("Бафы");
			}
			case "dances":
			{
				return tr("Танцы");
			}
			case "songs":
			{
				return tr("Песни");
			}
			case "chants":
			{
				return tr("Чанты");
			}
			case "special":
			{
				return tr("Особые");
			}
			case "resist":
			{
				return tr("Резисты");
			}
			default:
			{
				return type;
			}
		}
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка BOARDSHOP ({@code HuntersSelfTest}, только на копии базы): охотник играет игрока на доске. Наборы: неверное имя, создание, выбор бафов, строка в базе,
	 * наложение и плата, запись из своих бафов, чужой набор, запрет наложения, удаление. Продажа лута: условия {@link PhantomLootSell#canUse}, сами пакеты без клиента не идут.
	 * Страницы — в {@code log/L2HubBufferSets.html}.
	 * @param hunters охотники в мире
	 */
	static void selfTest(List<Player> hunters)
	{
		final String tag = "PhantomBufferSets: SelfTest BOARDSHOP: ";
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final List<Player> alive = hunters.stream().filter(hunter -> !hunter.isDead()).toList();
		final List<String> types = table.getSkillTypes();
		if ((alive.size() < 2) || types.isEmpty())
		{
			LOGGER.warning(tag + "needs two living hunters and buffer categories, found " + alive.size() + " and " + types.size() + ".");
			return;
		}
		final Player player = alive.get(0);
		final Player stranger = alive.get(1);
		final int id = player.getObjectId();
		final String name = "Тест набор-1";
		final String token = encode(name);
		final StringBuilder pages = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>L2Hub: наборы бафов</title><body style=\"background:#1b1b1b;color:#ddd;font:12px Tahoma\">");
		try
		{
			final String refused = handle(player, new String[] { "_bbshubbuff", "scheme_create", "имя/со<знаками>" }, true, null);
			LOGGER.info(tag + player.getName() + ": a bad name refused " + (table.getPlayerSchemes(id) == null || table.getPlayerSchemes(id).isEmpty()) + ", page " + refused.length() + " chars.");
			pages.append(handle(player, new String[] { "_bbshubbuff", "scheme_create", "Тест", "набор-1" }, true, null));
			final String category = types.get(0);
			final List<Integer> ids = table.getSkillsIdsByType(category).stream().filter(skill -> skill(skill) != null).limit(3).toList();
			for (int skill : ids)
			{
				handle(player, new String[] { "_bbshubbuff", "scheme_toggle", token, String.valueOf(skill), category, "0" }, true, null);
			}
			LOGGER.info(tag + "created with a space and Cyrillic: " + (known(player, name) != null) + "; picked " + table.getScheme(id, name) + " of " + category + "; in the base \"" + stored(id, name) + "\".");
			pages.append("<hr>").append(handle(player, new String[] { "_bbshubbuff", "scheme_edit", token, category, "0" }, true, null));

			final int fee = fee(table.getScheme(id, name));
			if ((SchemeBufferConfig.BUFFER_ITEM_ID == 57) && (player.getAdena() < fee))
			{
				player.addAdena(ItemProcessType.FEE, fee, player, false);
			}
			final long adena = player.getAdena();
			handle(player, new String[] { "_bbshubbuff", "scheme_cast", token }, false, null);
			final int before = currentBuffs(player).size();
			LOGGER.info(tag + "cast while the board forbids it: adena spent " + (adena - player.getAdena()) + ", own buffs " + before + ".");
			handle(player, new String[] { "_bbshubbuff", "scheme_cast", token }, true, null);
			LOGGER.info(tag + "cast: fee " + fee + ", adena spent " + (adena - player.getAdena()) + ", own buffs " + before + " -> " + currentBuffs(player).size() + ".");

			handle(player, new String[] { "_bbshubbuff", "scheme_capture", "Снимок" }, true, null);
			LOGGER.info(tag + "captured from own buffs: " + table.getScheme(id, "Снимок") + ".");
			handle(stranger, new String[] { "_bbshubbuff", "scheme_delete", token }, true, null);
			LOGGER.info(tag + stranger.getName() + " deletes a set of another: still there " + (known(player, name) != null) + ".");
			pages.append("<hr>").append(handle(player, new String[] { "_bbshubbuff", "schemes" }, true, null));
			handle(player, new String[] { "_bbshubbuff", "scheme_delete", token }, true, null);
			handle(player, new String[] { "_bbshubbuff", "scheme_delete", encode("Снимок") }, true, null);
			LOGGER.info(tag + "deleted: left in the base \"" + stored(id, name) + "\", in memory " + (known(player, name) != null) + ".");

			LOGGER.info(tag + "loot sale: list " + PhantomLootSell.isList(PhantomLootSell.LIST_ID) + ", shop list 423 " + PhantomLootSell.isList(423) + ", " + player.getName() + " (dead " + player.isDead() + ", in combat " + player.isInCombat() + ") may sell " + PhantomLootSell.canUse(player) + ".");
			final Player dead = hunters.stream().filter(Player::isDead).findFirst().orElse(null);
			LOGGER.info(tag + "loot sale, the dead: " + (dead == null ? "nobody dead now" : dead.getName() + " may sell " + PhantomLootSell.canUse(dead)) + ".");
			pages.append("<hr>").append(PhantomLootSell.content("_bbshubshop"));
			Files.writeString(Path.of("./log/L2HubBufferSets.html"), pages.toString());
			LOGGER.info(tag + "pages written to ./log/L2HubBufferSets.html, done.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
	}

	private static String stored(int id, String name)
	{
		try (Connection connection = DatabaseFactory.getConnection();
			PreparedStatement statement = connection.prepareStatement("SELECT skills FROM buffer_schemes WHERE object_id=? AND scheme_name=?"))
		{
			statement.setInt(1, id);
			statement.setString(2, name);
			try (ResultSet result = statement.executeQuery())
			{
				return result.next() ? result.getString(1) : "(no row)";
			}
		}
		catch (Exception e)
		{
			return "(error " + e.getMessage() + ")";
		}
	}
}
