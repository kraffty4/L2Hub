package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanAccess;

/** Clan directory and confirmed declarations using the existing Epilogue war rules. */
public final class CommunityClanWars
{
	private static final int PAGE_SIZE = 10;
	private static final String COMMAND = "bypass _bbshubwars";
	private static final Confirmations CONFIRMATIONS = new Confirmations();
	private static final Object DECLARE_LOCK = new Object();

	public record Entry(int id, String name, String leader, int level, int members, int online, int allyId, boolean hunter, boolean declared, boolean received)
	{
	}

	public record Viewer(Entry own, boolean authorized)
	{
	}

	private record Pending(int source, int target, long expires, String token)
	{
	}

	static final class Confirmations
	{
		private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();

		String issue(int playerId, int source, int target, long now)
		{
			pending.entrySet().removeIf(entry -> entry.getValue().expires() <= now);
			final String token = UUID.randomUUID().toString();
			pending.put(playerId, new Pending(source, target, now + 120000, token));
			return token;
		}

		boolean take(int playerId, int source, int target, String token, long now)
		{
			final Pending value = pending.remove(playerId);
			return (value != null) && (value.source() == source) && (value.target() == target) && (now < value.expires()) && value.token().equals(token);
		}
	}

	private CommunityClanWars()
	{
	}

	public static String board(Player player, String[] args)
	{
		final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";
		final boolean request = action.equals("confirm") || action.equals("declare");
		final String filter = filter(args.length > (request ? 3 : 1) ? args[request ? 3 : 1] : "all");
		final int page = number(args.length > (request ? 4 : 2) ? args[request ? 4 : 2] : "0");
		final Clan own = player.getClan();
		final Viewer viewer = new Viewer(own == null ? null : entry(own, own), player.hasAccess(ClanAccess.WAR_DECLARATION));
		String message = "";
		if (request)
		{
			final Clan target = ClanTable.getInstance().getClan(number(args.length > 2 ? args[2] : "0"));
			final Entry selected = target == null ? null : entry(target, own);
			final String error = reason(viewer, selected, PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR);
			if (error != null)
			{
				message = error;
			}
			else if (action.equals("confirm"))
			{
				final String token = CONFIRMATIONS.issue(player.getObjectId(), own.getId(), target.getId(), System.currentTimeMillis());
				return confirmation(viewer.own(), selected, filter, page, token);
			}
			else
			{
				if (!CONFIRMATIONS.take(player.getObjectId(), own.getId(), target.getId(), args.length > 5 ? args[5] : "", System.currentTimeMillis()))
				{
					message = "Подтверждение истекло. Выбери клан и подтверди объявление войны заново.";
				}
				else
				{
					synchronized (DECLARE_LOCK)
					{
						final Clan current = player.getClan();
						final Clan currentTarget = ClanTable.getInstance().getClan(target.getId());
						final Viewer currentViewer = new Viewer(current == null ? null : entry(current, current), player.hasAccess(ClanAccess.WAR_DECLARATION));
						final String recheck = reason(currentViewer, currentTarget == null ? null : entry(currentTarget, current), PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR);
						if ((current == null) || (current.getId() != own.getId()))
						{
							message = "Твой клан изменился. Открой список заново.";
						}
						else if (recheck != null)
						{
							message = recheck;
						}
						else
						{
							ClanTable.getInstance().storeClanWars(current.getId(), currentTarget.getId());
							for (Clan clan : List.of(current, currentTarget))
							{
								for (Player member : clan.getOnlineMembers(0))
								{
									member.broadcastUserInfo();
								}
							}
							message = "Объявлена война клану «" + currentTarget.getName() + "». Ответное объявление другой стороны необходимо для взаимного вара.";
						}
					}
				}
			}
		}
		final Clan present = player.getClan();
		final List<Entry> clans = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			clans.add(entry(clan, present));
		}
		return render(clans, new Viewer(present == null ? null : entry(present, present), player.hasAccess(ClanAccess.WAR_DECLARATION)), filter, page, PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR, message);
	}

	private static Entry entry(Clan clan, Clan own)
	{
		return new Entry(clan.getId(), clan.getName(), clan.getLeaderName(), clan.getLevel(), clan.getMembersCount(), clan.getOnlineMembers(0).size(), clan.getAllyId(), PhantomHunterClans.isHunterClan(clan), (own != null) && own.isAtWarWith(clan.getId()), (own != null) && clan.isAtWarWith(own.getId()));
	}

	static String reason(Viewer viewer, Entry target, int minimum)
	{
		if (target == null)
		{
			return "Клан больше не существует.";
		}
		final Entry own = viewer.own();
		if (own == null)
		{
			return "Для объявления войны нужно состоять в клане.";
		}
		if (own.id() == target.id())
		{
			return "Твой клан";
		}
		if (!viewer.authorized())
		{
			return "Нет права объявлять войну.";
		}
		if ((own.level() < ClanWarPolicy.minimumLevel()) || (own.members() < minimum))
		{
			return "Твоему клану нужны уровень " + ClanWarPolicy.minimumLevel() + " и минимум " + minimum + " участников.";
		}
		if ((own.allyId() != 0) && (own.allyId() == target.allyId()))
		{
			return "Клан в вашем альянсе.";
		}
		if ((target.level() < ClanWarPolicy.minimumLevel()) || (target.members() < minimum))
		{
			return "Сопернику нужны уровень " + ClanWarPolicy.minimumLevel() + " и минимум " + minimum + " участников.";
		}
		if (target.declared())
		{
			return target.received() ? "Взаимный вар" : "Вар уже объявлен";
		}
		return null;
	}

	static String render(List<Entry> entries, Viewer viewer, String requestedFilter, int requestedPage, int minimum, String message)
	{
		final String tab = filter(requestedFilter);
		final List<Entry> clans = entries.stream().filter(clan -> tab.equals("all") || (tab.equals("hunters") == clan.hunter())).sorted(Comparator.comparingInt(Entry::level).reversed().thenComparing(Comparator.comparingInt(Entry::members).reversed()).thenComparing(Entry::name, String.CASE_INSENSITIVE_ORDER).thenComparingInt(Entry::id)).toList();
		final int pages = Math.max(1, (clans.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = Math.max(0, Math.min(requestedPage, pages - 1));
		final StringBuilder out = new StringBuilder(9000);
		out.append("<table width=520><tr><td>").append(button("Все", COMMAND + " all 0", 110)).append("</td><td>").append(button("Ботокланы", COMMAND + " hunters 0", 130)).append("</td><td>").append(button("Кланы игроков", COMMAND + " players 0", 145)).append("</td></tr></table><br>");
		if (!message.isEmpty())
		{
			out.append("<font color=\"LEVEL\">").append(escape(message)).append("</font><br><br>");
		}
		out.append("<font color=\"AFAFAF\">Кланов: ").append(clans.size()).append(". Состав: всего участников / в сети.<br>Для объявления: оба клана от ").append(ClanWarPolicy.minimumLevel()).append("-го уровня и от ").append(minimum).append(" участников.</font><br>");
		if (viewer.own() == null)
		{
			out.append("<font color=\"AFAFAF\">Ты не состоишь в клане. Список доступен для просмотра.</font><br>");
		}
		out.append("<table width=520 cellpadding=3 cellspacing=0><tr><td width=165>Клан / глава</td><td width=30>Ур.</td><td width=70>Состав</td><td width=255>Война</td></tr>");
		for (int i = page * PAGE_SIZE; i < Math.min(clans.size(), (page + 1) * PAGE_SIZE); i++)
		{
			final Entry clan = clans.get(i);
			out.append("<tr><td><font color=\"CDB67F\">").append(escape(clan.name())).append("</font><br><font color=\"909090\">").append(clan.hunter() ? "Боты" : "Игроки").append("; ").append(escape(clan.leader())).append("</font></td><td>").append(clan.level()).append("</td><td>").append(clan.members()).append(" / ").append(clan.online()).append("</td><td>");
			final String error = reason(viewer, clan, minimum);
			if (error == null)
			{
				if (clan.received())
				{
					out.append("Входящий вар<br>");
				}
				out.append(button(clan.received() ? "Ответить варом" : "Объявить вар", COMMAND + " confirm " + clan.id() + " " + tab + " " + page, 145));
			}
			else
			{
				out.append("<font color=\"909090\">").append(escape(error)).append("</font>");
			}
			out.append("</td></tr>");
		}
		if (clans.isEmpty())
		{
			out.append("<tr><td>В этой категории кланов пока нет.</td></tr>");
		}
		out.append("</table><br><table width=520><tr><td width=150>");
		if (page > 0)
		{
			out.append(button("Назад", COMMAND + " " + tab + " " + (page - 1), 110));
		}
		out.append("</td><td width=220 align=center>Страница ").append(page + 1).append(" / ").append(pages).append("</td><td width=150>");
		if ((page + 1) < pages)
		{
			out.append(button("Далее", COMMAND + " " + tab + " " + (page + 1), 110));
		}
		return out.append("</td></tr></table>").toString();
	}

	static String confirmation(Entry own, Entry target, String tab, int page, String token)
	{
		return "<br>Объявить войну клану <font color=\"LEVEL\">" + escape(target.name()) + "</font>?<br><br>Уровень: " + target.level() + "; участников: " + target.members() + "; в сети: " + target.online() + ".<br>Твой клан: " + escape(own.name()) + ".<br><br>Для взаимного вара другая сторона должна ответить объявлением.<br><br>" + button("Подтвердить вар", COMMAND + " declare " + target.id() + " " + filter(tab) + " " + Math.max(0, page) + " " + token, 180) + "<br>" + button("Отмена", COMMAND + " " + filter(tab) + " " + Math.max(0, page), 130);
	}

	private static String button(String label, String action, int width)
	{
		return "<button value=\"" + label + "\" action=\"" + action + "\" width=" + width + " height=25 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	static String escape(String value)
	{
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}

	private static String filter(String value)
	{
		return "hunters".equals(value) || "players".equals(value) ? value : "all";
	}

	private static int number(String value)
	{
		try
		{
			return Integer.parseInt(value);
		}
		catch (NumberFormatException e)
		{
			return 0;
		}
	}
}
