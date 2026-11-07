package handlers.bypass.communityboard;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

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

/** Personal buff schemes shared by the town NPC and the L2Hub community board. */
public final class L2HubBufferSchemes
{
	private static final Logger LOGGER = Logger.getLogger(L2HubBufferSchemes.class.getName());
	private static final String PREFIX = "_bbshubbuff ";
	private static final int PAGE_SIZE = 12;
	private static final int NAME_LIMIT = 14;
	private static final Map<Integer, List<String>> COMPOSITE_BLOCKS = Map.ofEntries(
		Map.entry(1499, List.of("PA_UP", "PD_UP")),
		Map.entry(1500, List.of("MA_UP", "MD_UP")),
		Map.entry(1501, List.of("MAX_HP_UP", "MAX_MP_UP")),
		Map.entry(1502, List.of("CRITICAL_PROB_UP", "CRITICAL_DMG_UP")),
		Map.entry(1503, List.of("SHIELD_PROB_UP", "SHIELD_DEFENCE_UP")),
		Map.entry(1504, List.of("SPEED_UP", "AVOID_UP")),
		Map.entry(1517, List.of("PA_UP", "PD_UP")),
		Map.entry(1518, List.of("CRITICAL_PROB_UP", "CRITICAL_DMG_UP")),
		Map.entry(1519, List.of("VAMPIRIC_ATTACK", "ATTACK_TIME_DOWN")),
		Map.entry(1535, List.of("SPEED_UP", "AVOID_UP"))
	);

	private L2HubBufferSchemes() {}

	static String handle(Player player, String[] args)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final String action = args.length > 1 ? args[1].toLowerCase() : "schemes";
		if (action.equals("scheme_create") || action.equals("scheme_capture"))
		{
			final String name = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length)).strip() : "";
			if (!validName(name))
			{
				player.sendMessage("Название: от 1 до 14 букв или цифр; допустимы пробел, _ и -.");
				return list(player);
			}
			final Map<String, List<Integer>> schemes = table.getPlayerSchemes(player.getObjectId());
			if ((schemes != null) && schemes.containsKey(name))
			{
				player.sendMessage("Такой набор уже есть. Открой его для изменения.");
				return list(player);
			}
			if ((schemes != null) && (schemes.size() >= SchemeBufferConfig.BUFFER_MAX_SCHEMES))
			{
				player.sendMessage("Достигнут лимит сохранённых наборов: " + SchemeBufferConfig.BUFFER_MAX_SCHEMES + ".");
				return list(player);
			}
			final List<Integer> skills = action.equals("scheme_capture") ? currentBuffs(player) : new ArrayList<>();
			if (action.equals("scheme_capture") && skills.isEmpty())
			{
				player.sendMessage("Сейчас на тебе нет баффов, доступных у этого баффера.");
				return list(player);
			}
			if (withinLimits(player, skills) && persist(player, name, skills))
			{
				player.sendMessage("Набор сохранён: " + name + ".");
				return editor(player, name, "Buffs", 0);
			}
			return list(player);
		}
		if (action.equals("schemes"))
		{
			return list(player);
		}
		final String requested = args.length > 2 ? decode(args[2]) : null;
		final Map<String, List<Integer>> schemes = table.getPlayerSchemes(player.getObjectId());
		if ((requested == null) || (schemes == null) || !schemes.containsKey(requested))
		{
			player.sendMessage("Набор не найден у этого персонажа.");
			return list(player);
		}
		final String name = schemes.keySet().stream().filter(key -> key.equalsIgnoreCase(requested)).findFirst().orElse(requested);
		final String category = canonicalCategory(args.length > 4 && action.equals("scheme_toggle") ? args[4] : args.length > 3 ? args[3] : "Buffs");
		final int page = number(args, action.equals("scheme_toggle") ? 5 : 4);
		switch (action)
		{
			case "scheme_edit":
				return editor(player, name, category, page);
			case "scheme_toggle":
			{
				final int id = number(args, 3);
				final Skill skill = skill(id);
				if (((skill == null) || !table.getSkillsIdsByType(category).contains(id)) && !table.getScheme(player.getObjectId(), name).contains(id))
				{
					player.sendMessage("Этот бафф недоступен в выбранной категории.");
					return editor(player, name, category, page);
				}
				final List<Integer> updated = new ArrayList<>(table.getScheme(player.getObjectId(), name));
				if (updated.contains(id))
				{
					updated.remove(Integer.valueOf(id));
				}
				else
				{
					updated.removeIf(previous -> conflicts(skill(previous), skill));
					updated.add(id);
				}
				if (withinLimits(player, updated))
				{
					persist(player, name, updated);
				}
				return editor(player, name, category, page);
			}
			case "scheme_capture_existing":
			{
				final List<Integer> updated = currentBuffs(player);
				if (updated.isEmpty())
				{
					player.sendMessage("Нет доступных баффов для сохранения. Старый набор сохранён.");
				}
				else if (withinLimits(player, updated) && persist(player, name, updated))
				{
					player.sendMessage("Текущие баффы записаны в набор «" + name + "».");
				}
				return editor(player, name, "Buffs", 0);
			}
			case "scheme_delete":
			{
				synchronized (table)
				{
					try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement("DELETE FROM buffer_schemes WHERE object_id=? AND scheme_name=?"))
					{
						statement.setInt(1, player.getObjectId());
						statement.setString(2, name);
						statement.executeUpdate();
						table.deleteScheme(player.getObjectId(), name);
						player.sendMessage("Набор удалён: " + name + ".");
					}
					catch (Exception error)
					{
						LOGGER.log(Level.WARNING, "Could not delete player buff scheme", error);
						player.sendMessage("Не удалось удалить набор. Попробуй ещё раз.");
					}
				}
				return list(player);
			}
			case "scheme_cast":
			{
				if (!L2HubBoard.canUse(player))
				{
					return list(player);
				}
				final Creature target = args.length > 3 && args[3].equalsIgnoreCase("pet") ? player.getSummon() : player;
				if (target == null)
				{
					player.sendMessage("У тебя нет питомца.");
					return list(player);
				}
				final List<Integer> ids = table.getScheme(player.getObjectId(), name);
				long fee = 0;
				for (int id : ids)
				{
					final BuffSkillHolder holder = table.getAvailableBuff(id);
					if ((holder == null) || (skill(id) == null))
					{
						player.sendMessage("В наборе есть недоступный бафф. Открой набор для изменения.");
						return list(player);
					}
					fee += SchemeBufferConfig.BUFFER_STATIC_BUFF_COST >= 0 ? SchemeBufferConfig.BUFFER_STATIC_BUFF_COST : holder.getPrice();
				}
				if ((fee > 0) && !(SchemeBufferConfig.BUFFER_ITEM_ID == 57 ? player.reduceAdena(ItemProcessType.FEE, fee, player, true) : player.destroyItemByItemId(ItemProcessType.FEE, SchemeBufferConfig.BUFFER_ITEM_ID, fee, player, true)))
				{
					return list(player);
				}
				int count = 0;
				for (int id : ids)
				{
					if (L2HubBoard.apply(player, target, table.getAvailableBuff(id)))
					{
						count++;
					}
				}
				player.sendMessage("Набор «" + name + "»: наложено баффов " + count + ".");
				return list(player);
			}
			default:
				return list(player);
		}
	}

	private static boolean persist(Player player, String name, List<Integer> ids)
	{
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		synchronized (table)
		{
			final Map<String, List<Integer>> existing = table.getPlayerSchemes(player.getObjectId());
			if (!validName(name) || ((existing != null) && !existing.containsKey(name) && (existing.size() >= SchemeBufferConfig.BUFFER_MAX_SCHEMES)))
			{
				player.sendMessage("Не удалось сохранить: достигнут лимит наборов или неверное название.");
				return false;
			}
			final String skills = ids.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(","));
			try (Connection connection = DatabaseFactory.getConnection(); PreparedStatement statement = connection.prepareStatement("INSERT INTO buffer_schemes (object_id,scheme_name,skills) VALUES (?,?,?) ON DUPLICATE KEY UPDATE skills=VALUES(skills)"))
			{
				statement.setInt(1, player.getObjectId());
				statement.setString(2, name);
				statement.setString(3, skills);
				statement.executeUpdate();
				table.setScheme(player.getObjectId(), name, ids);
				return true;
			}
			catch (Exception error)
			{
				LOGGER.log(Level.WARNING, "Could not save player buff scheme", error);
				player.sendMessage("Не удалось сохранить набор. Попробуй ещё раз.");
				return false;
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
				result.removeIf(previous -> conflicts(skill(previous), available));
				result.add(id);
			}
		}
		return result;
	}

	private static boolean conflicts(Skill first, Skill second)
	{
		if ((first == null) || (second == null)) { return false; }
		final String firstType = first.getAbnormalType().name();
		final String secondType = second.getAbnormalType().name();
		return (!firstType.equals("NONE") && firstType.equals(secondType))
			|| COMPOSITE_BLOCKS.getOrDefault(first.getId(), List.of()).contains(secondType)
			|| COMPOSITE_BLOCKS.getOrDefault(second.getId(), List.of()).contains(firstType);
	}

	private static boolean withinLimits(Player player, List<Integer> ids)
	{
		int buffs = 0;
		int dances = 0;
		for (int id : ids)
		{
			final Skill skill = skill(id);
			if (skill == null) { continue; }
			if (skill.isDance()) { dances++; } else { buffs++; }
		}
		if ((buffs > player.getStat().getMaxBuffCount()) || (dances > PlayerConfig.DANCES_MAX_AMOUNT))
		{
			player.sendMessage("Лимит набора: " + player.getStat().getMaxBuffCount() + " обычных баффов и " + PlayerConfig.DANCES_MAX_AMOUNT + " песен/танцев.");
			return false;
		}
		return true;
	}

	private static String list(Player player)
	{
		final StringBuilder html = new StringBuilder(4096);
		html.append(button("К баффам", "Buffs", 120)).append("<br>");
		html.append("<font color=LEVEL>Мои наборы</font><br1>До ").append(SchemeBufferConfig.BUFFER_MAX_SCHEMES).append(" наборов на персонажа. Доступны и у городского баффера.<br><table width=500><tr><td width=190><edit var=\"buff_scheme_name\" width=180 height=20></td><td>");
		html.append(button("Создать пустой", "scheme_create $buff_scheme_name", 145)).append("</td><td>");
		html.append(button("Из моих баффов", "scheme_capture $buff_scheme_name", 145)).append("</td></tr></table><br>");
		html.append("Название: до 14 символов. Для готового набора сначала наложи нужные баффы и нажми «Из моих баффов».<br>");
		final Map<String, List<Integer>> schemes = SchemeBufferTable.getInstance().getPlayerSchemes(player.getObjectId());
		if ((schemes == null) || schemes.isEmpty()) { return html.append("Сохранённых наборов пока нет.").toString(); }
		for (Map.Entry<String, List<Integer>> entry : schemes.entrySet())
		{
			final String token = encode(entry.getKey());
			html.append("<table width=500 bgcolor=252525><tr><td colspan=4><font color=LEVEL>").append(escape(entry.getKey())).append("</font> — ").append(entry.getValue().size()).append(" баффов</td></tr><tr><td>");
			html.append(button("На себя", "scheme_cast " + token, 90)).append("</td><td>");
			if (player.hasSummon()) { html.append(button("Питомцу", "scheme_cast " + token + " pet", 90)); } html.append("</td><td>");
			html.append(button("Изменить", "scheme_edit " + token + " Buffs 0", 110)).append("</td><td>");
			html.append(button("Удалить", "scheme_delete " + token, 90)).append("</td></tr></table><br>");
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
		html.append("<table width=300><tr><td>").append(button("Мои наборы", "schemes", 130)).append("</td><td>").append(button("Наложить", "scheme_cast " + token, 110)).append("</td></tr></table><br>");
		html.append("<font color=LEVEL>").append(escape(name)).append("</font> — выбрано ").append(selected.size()).append(" баффов.<br1>");
		html.append("Лимит: ").append(player.getStat().getMaxBuffCount()).append(" обычных баффов и ").append(PlayerConfig.DANCES_MAX_AMOUNT).append(" песен/танцев.<br1>");
		html.append("Нажми на бафф, чтобы добавить или убрать его. Изменения сохраняются сразу.<br1>CoV и пророчества заменяют друг друга; Greater Might и Greater Shield — тоже. Комбо заменяют свои отдельные баффы.<br><table width=510><tr>");
		int categoryIndex = 0; for (String type : table.getSkillTypes()) { if (categoryIndex > 0 && categoryIndex % 6 == 0) { html.append("</tr><tr>"); } html.append("<td>").append(button(type.equalsIgnoreCase(category) ? "[" + categoryName(type) + "]" : categoryName(type), "scheme_edit " + token + " " + type + " 0", 82)).append("</td>"); categoryIndex++; }
		html.append("</tr></table><br><table width=510>");
		for (int index = page * PAGE_SIZE; index < Math.min(available.size(), (page + 1) * PAGE_SIZE); index++)
		{
			if ((index % 2) == 0) { html.append("<tr>"); }
			final int id = available.get(index);
			final Skill skill = skill(id);
			final boolean active = selected.contains(id);
			final String action = "scheme_toggle " + token + " " + id + " " + category + " " + page;
			final String icon = "icon.skill" + String.format("%04d", id);
			html.append("<td width=36><button value=\" \" action=\"bypass ").append(PREFIX).append(action).append("\" width=32 height=32 back=\"").append(icon).append("\" fore=\"").append(icon).append("\"></td><td width=215>");
			html.append("<a action=\"bypass ").append(PREFIX).append(action).append("\"><font color=\"").append(active ? "88DD88" : "FFFFFF").append("\">").append(active ? "[-] " : "[+] ").append(escape(skill.getName())).append("</font></a><br1>Ур. ").append(table.getAvailableBuff(id).getLevel()).append("</td>");
			if ((index % 2) == 1) { html.append("</tr>"); }
		}
		if ((Math.min(available.size(), (page + 1) * PAGE_SIZE) % 2) == 1) { html.append("<td></td><td></td></tr>"); }
		html.append("</table><br><table width=400><tr><td>");
		if (page > 0) { html.append(button("Назад", "scheme_edit " + token + " " + category + " " + (page - 1), 90)); }
		html.append("</td><td align=center>").append(page + 1).append(" / ").append(pages).append("</td><td>");
		if (page + 1 < pages) { html.append(button("Далее", "scheme_edit " + token + " " + category + " " + (page + 1), 90)); }
		html.append("</td></tr></table><br>").append(button("Заменить моими баффами", "scheme_capture_existing " + token, 230));
		html.append("<br><font color=LEVEL>Состав:</font> ");
		for (int id : selected) { final Skill skill = skill(id); if (skill != null) { html.append(escape(skill.getName())).append("; "); } else { html.append("<a action=\"bypass ").append(PREFIX).append("scheme_toggle ").append(token).append(" ").append(id).append(" ").append(category).append(" ").append(page).append("\">Убрать недоступный #").append(id).append("</a>; "); } }
		return html.toString();
	}

	private static Skill skill(int id)
	{
		final BuffSkillHolder holder = SchemeBufferTable.getInstance().getAvailableBuff(id);
		return holder == null ? null : SkillData.getInstance().getSkill(id, holder.getLevel());
	}

	private static boolean validName(String name)
	{
		return name != null && name.length() > 0 && name.length() <= NAME_LIMIT && name.matches("[\\p{L}\\p{N} _-]+") && !name.isBlank();
	}

	private static String encode(String name) { return Base64.getUrlEncoder().withoutPadding().encodeToString(name.getBytes(StandardCharsets.UTF_8)); }
	private static String decode(String value)
	{
		try { if (value.length() > 80) { return null; } final String name = new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8); return validName(name) ? name : null; }
		catch (IllegalArgumentException error) { return null; }
	}
	private static int number(String[] args, int index) { try { return index < args.length ? Math.max(0, Integer.parseInt(args[index])) : 0; } catch (NumberFormatException error) { return 0; } }
	private static String escape(String value) { return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;"); }
	private static String button(String label, String action, int width) { return "<button value=\"" + escape(label) + "\" action=\"bypass " + PREFIX + action + "\" width=" + width + " height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">"; }
	private static String categoryName(String value) { return switch (value.toLowerCase()) { case "buffs" -> "Баффы"; case "dances" -> "Танцы"; case "songs" -> "Песни"; case "chants" -> "Чанты"; case "special" -> "Особые"; case "combined" -> "Комбо"; case "resist" -> "Резисты"; default -> value; }; }
	private static String canonicalCategory(String value) { return SchemeBufferTable.getInstance().getSkillTypes().stream().filter(type -> type.equalsIgnoreCase(value)).findFirst().orElse("Buffs"); }
}
