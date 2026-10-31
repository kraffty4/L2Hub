package org.l2jmobius.gameserver.managers.phantom;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterManager.HunterView;
import org.l2jmobius.gameserver.network.serverpackets.ShowBoard;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: где охотники — окно ГМ {@code //hunters} и раздел «Охотники» на доске Alt+B.<br>
 * ГМ видит всех: что делает, адену, доход в час, квест, долг за профу, расстояние; «К нему» — телепорт к охотнику, он же становится целью.<br>
 * Игрок видит, кто качается на его уровне (±{@link #NEAR_LEVELS}), кто где и кто сидит в лавке. Кнопка ведёт на спот охотника или к его лавке
 * телепортом по правилам охотников: до {@code MaxFreeTeleportLevel} бесплатно, дальше {@link PhantomHuntersConfig#HUNTER_TELEPORT_FEE}. В пати и на зов — без кнопки.
 */
public final class PhantomHunterUi
{
	/** Игроку по умолчанию — охотники его уровня ± столько. */
	public static final int NEAR_LEVELS = 5;
	private static final int GM_PAGE = 8;
	private static final int BOARD_PAGE = 12;
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

	/** Вкладки: ключ в bypass, подпись. ГМ видит все, игрок — первые три. */
	private enum Tab
	{
		NEAR(tr("Мой уровень")),
		ALL(tr("Все")),
		STORE(tr("Лавки")),
		HUNT(tr("На каче")),
		TOWN(tr("В городе")),
		PARTY(tr("В пати"));

		final String label;

		Tab(String label)
		{
			this.label = label;
		}

		static Tab of(String key, Tab fallback)
		{
			for (Tab tab : values())
			{
				if (tab.name().equalsIgnoreCase(key))
				{
					return tab;
				}
			}
			return fallback;
		}
	}

	private enum Sort
	{
		LEVEL(tr("По уровню")),
		NAME(tr("По имени")),
		NEAR(tr("Ближе ко мне"));

		final String label;

		Sort(String label)
		{
			this.label = label;
		}

		static Sort of(String key)
		{
			for (Sort sort : values())
			{
				if (sort.name().equalsIgnoreCase(key))
				{
					return sort;
				}
			}
			return LEVEL;
		}
	}

	private PhantomHunterUi()
	{
	}

	// ---------- ГМ: //hunters ----------

	/**
	 * {@code //hunters [вкладка] [сортировка] [страница]} или {@code //hunters goto <имя> [вкладка] [сортировка] [страница]}.
	 * @param gm кто смотрит
	 * @param args команда, разбитая по пробелам ({@code args[0]} — {@code admin_hunters})
	 * @return окно целиком (html)
	 */
	public static String admin(Player gm, String[] args)
	{
		int i = 1;
		if ((args.length > 2) && args[1].equalsIgnoreCase("goto"))
		{
			final String error = gmGoto(gm, args[2]);
			if (error != null)
			{
				gm.sendSysMessage(error);
			}
			i = 3;
		}
		final Tab tab = Tab.of(args.length > i ? args[i] : null, Tab.ALL);
		final Sort sort = Sort.of(args.length > (i + 1) ? args[i + 1] : null);
		final int page = args.length > (i + 2) ? parse(args[i + 2]) : 0;
		final String state = tab.name().toLowerCase() + " " + sort.name().toLowerCase();

		final List<HunterView> all = PhantomHunterManager.getInstance().views();
		final StringBuilder sb = new StringBuilder(12288);
		sb.append(tr("<html><title>Охотники</title><body><center>"));
		sb.append(tr("<font color=\"LEVEL\">Охотников в мире: ")).append(all.size()).append("</font><br1>");
		sb.append("<font color=\"808080\">").append(counts(all)).append(tr(" · живых игроков: ")).append(PhantomHunterManager.getInstance().getRealPlayers()).append("</font><br>");

		// Вкладки — две строки по три, сортировка — третья.
		sb.append("<table width=270><tr>");
		for (Tab t : Tab.values())
		{
			if (t.ordinal() == 3)
			{
				sb.append("</tr><tr>");
			}
			sb.append("<td>").append(gmButton(t == tab ? "[" + t.label + "]" : t.label, t.name().toLowerCase() + " " + sort.name().toLowerCase() + " 0", 88)).append("</td>");
		}
		sb.append("</tr><tr>");
		for (Sort s : Sort.values())
		{
			sb.append("<td>").append(gmButton(s == sort ? "[" + s.label + "]" : s.label, tab.name().toLowerCase() + " " + s.name().toLowerCase() + " 0", 88)).append("</td>");
		}
		sb.append("</tr></table>");

		final List<HunterView> list = filter(all, tab, gm);
		sortList(list, sort, tab, gm);
		final int pages = Math.max(1, (list.size() + GM_PAGE - 1) / GM_PAGE);
		final int current = Math.max(0, Math.min(page, pages - 1));
		sb.append("<img src=\"L2UI.SquareGray\" width=270 height=1><br1>");
		if (list.isEmpty())
		{
			sb.append(tr("<font color=\"808080\">Никого.</font><br>"));
		}
		for (HunterView view : list.subList(current * GM_PAGE, Math.min(list.size(), (current + 1) * GM_PAGE)))
		{
			final Player hunter = view.player;
			sb.append("<table width=270><tr><td width=200><font color=\"LEVEL\">").append(hunter.getName()).append("</font> ").append(hunter.getLevel()).append(' ').append(PhantomUi.className(hunter)).append("</td>");
			sb.append("<td width=70>").append(gmButton(tr("К нему"), "goto " + hunter.getName() + " " + state + " " + current, 66)).append("</td></tr></table>");
			sb.append("<table width=270><tr><td width=270><font color=\"").append(phaseColor(view.phase)).append("\">").append(doing(view)).append("</font></td></tr></table>");
			sb.append("<table width=270><tr><td width=270><font color=\"808080\">").append(economy(view, gm)).append("</font></td></tr></table>");
			sb.append("<img src=\"L2UI.SquareGray\" width=270 height=1><br1>");
		}

		if (pages > 1)
		{
			sb.append("<table width=270><tr><td width=90>").append(current > 0 ? gmButton(tr("Назад"), state + " " + (current - 1), 60) : "").append("</td>");
			sb.append("<td width=90 align=center>").append(current + 1).append(" / ").append(pages).append("</td>");
			sb.append("<td width=90 align=right>").append((current + 1) < pages ? gmButton(tr("Дальше"), state + " " + (current + 1), 60) : "").append("</td></tr></table>");
		}
		sb.append(tr("<br><font color=\"808080\">Цель после прыжка — охотник. Позвать к себе (//recall) не выйдет: поводок вернёт его на спот.</font>"));
		sb.append("</center></body></html>");
		return sb.toString();
	}

	private static String gmGoto(Player gm, String name)
	{
		final HunterView view = find(name);
		if (view == null)
		{
			return "No such hunter in the world: " + name;
		}

		final Player hunter = view.player;
		PhantomCompat.setInstanceId(gm, hunter.getInstanceId());
		gm.teleToLocation(new Location(hunter.getX(), hunter.getY(), hunter.getZ()), true);
		gm.setTarget(hunter);
		return null;
	}

	private static String gmButton(String label, String action, int width)
	{
		return "<button value=\"" + label + "\" action=\"bypass -h admin_hunters " + action + "\" width=" + width + " height=21 back=\"" + PhantomCompat.BUTTON_BACK + "\" fore=\"" + PhantomCompat.BUTTON_FORE + "\">";
	}

	/**
	 * Адена, доход в час, квест, долг за профу, расстояние от ГМ.
	 */
	private static String economy(HunterView view, Player gm)
	{
		final Player hunter = view.player;
		final StringBuilder sb = new StringBuilder(128);
		sb.append(money(hunter.getAdena())).append(tr(" адены"));
		if (view.earnedPerHour > 0)
		{
			sb.append(" · ~").append(money(view.earnedPerHour)).append(tr("/ч"));
		}
		if (view.questReady)
		{
			sb.append(tr(" · сдаёт квест ")).append(view.questLevel);
		}
		else if (view.questLevel > 0)
		{
			sb.append(tr(" · квест ")).append(view.questLevel).append(tr(" до ")).append(TIME.format(Instant.ofEpochMilli(view.questUntil)));
		}
		if (view.professionDue > 0)
		{
			sb.append(tr(" · на профу не хватает ")).append(money(view.professionDue));
		}
		if (hunter.getInstanceId() == gm.getInstanceId())
		{
			sb.append(" · ").append(distance(gm, hunter)).append(tr(" от тебя"));
		}
		return sb.toString();
	}

	// ---------- Игрок: доска Alt+B ----------

	/**
	 * Раздел «Охотники» доски: вкладки «Мой уровень», «Все», «Лавки», таблица и кнопки телепорта.
	 * @param player кто смотрит
	 * @param tabKey вкладка ({@code near}, {@code all}, {@code store}) или {@code null}
	 * @param page страница с 0
	 * @param bypass начало действия кнопок: {@code bypass _bbshubhunters}
	 * @return содержимое страницы доски (без html и body)
	 */
	public static String board(Player player, String tabKey, int page, String bypass)
	{
		final int W = PhantomCompat.BOARD_WIDTH;
		Tab tab = Tab.of(tabKey, Tab.NEAR);
		if (tab.ordinal() > Tab.STORE.ordinal())
		{
			tab = Tab.NEAR;
		}

		final List<HunterView> list = filter(PhantomHunterManager.getInstance().views(), tab, player);
		sortList(list, tab == Tab.STORE ? Sort.NAME : Sort.LEVEL, tab, player);
		final int pages = Math.max(1, (list.size() + BOARD_PAGE - 1) / BOARD_PAGE);
		final int current = Math.max(0, Math.min(page, pages - 1));
		final int fee = fee(player);

		final StringBuilder sb = new StringBuilder(12288);
		sb.append("<table width=" + W + "><tr><td width=" + W + tr("><font color=\"B09878\">Охотники — такие же игроки, только без клиента: качаются сами с 1-го уровня, сидят в лавках в Гиране и идут в пати, если рядом крикнуть «лфм». "));
		sb.append(tr("Кнопка — телепорт на их спот или к лавке: ")).append(fee > 0 ? tr("за ") + money(fee) + tr(" адены (с ") + (PlayerConfig.MAX_FREE_TELEPORT_LEVEL + 1) + tr("-го уровня, как у них).") : player.getLevel() <= PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? tr("пока бесплатно (до ") + PlayerConfig.MAX_FREE_TELEPORT_LEVEL + tr("-го уровня включительно).") : tr("бесплатно.")).append("</font></td></tr></table><br>");

		sb.append("<table width=" + W + "><tr>");
		for (Tab t : new Tab[]
		{
			Tab.NEAR,
			Tab.ALL,
			Tab.STORE
		})
		{
			final String label = t == Tab.NEAR ? t.label + " (" + Math.max(1, player.getLevel() - NEAR_LEVELS) + "–" + (player.getLevel() + NEAR_LEVELS) + ")" : t.label;
			sb.append("<td align=center>").append(boardButton(t == tab ? "[" + label + "]" : label, bypass + " " + t.name().toLowerCase() + " 0", px(160))).append("</td>");
		}
		sb.append("</tr></table><br>");
		if (PhantomHuntersConfig.HUNTER_CLAN_TOP)
		{
			sb.append("<table width=" + W + "><tr><td align=center>").append(boardButton(tr("Топ кланов"), "bypass voice .clans top", px(160))).append("</td></tr></table><br>");
		}

		if (list.isEmpty())
		{
			sb.append("<font color=\"808080\">").append(tab == Tab.STORE ? tr("Сейчас никто не торгует.") : tab == Tab.NEAR ? tr("На твоём уровне сейчас никого — загляни во вкладку «Все».") : tr("Охотников в мире нет.")).append("</font>");
			return sb.toString();
		}

		sb.append("<table width=" + W + "><tr><td width=" + px(90) + tr("><font color=\"LEVEL\">Имя</font></td><td width=") + px(30) + tr("><font color=\"LEVEL\">Ур.</font></td><td width=") + px(105) + tr("><font color=\"LEVEL\">Класс</font></td><td width=") + px(210) + tr("><font color=\"LEVEL\">Где и что делает</font></td><td width=") + px(85) + "></td></tr></table>");
		sb.append("<img src=\"L2UI.SquareGray\" width=" + W + " height=1>");
		for (HunterView view : list.subList(current * BOARD_PAGE, Math.min(list.size(), (current + 1) * BOARD_PAGE)))
		{
			final Player hunter = view.player;
			final Location target = destination(view);
			sb.append("<table width=" + W + "><tr><td width=" + px(90) + ">").append(hunter.getName()).append("</td><td width=" + px(30) + ">").append(hunter.getLevel()).append("</td><td width=" + px(105) + ">").append(PhantomUi.className(hunter)).append("</td>");
			sb.append("<td width=" + px(210) + "><font color=\"").append(phaseColor(view.phase)).append("\">").append(doing(view, true)).append("</font></td><td width=" + px(85) + ">");
			if (target != null)
			{
				sb.append(boardButton(view.phase.equals("STORE") || view.phase.equals("TOWN") ? tr("К нему") : tr("На спот"), bypass + " go " + hunter.getName() + " " + tab.name().toLowerCase() + " " + current, 80));
			}
			sb.append("</td></tr></table>");
		}
		sb.append("<img src=\"L2UI.SquareGray\" width=" + W + " height=1><br>");

		if (pages > 1)
		{
			sb.append("<table width=300><tr><td width=100>").append(current > 0 ? boardButton(tr("Назад"), bypass + " " + tab.name().toLowerCase() + " " + (current - 1), 60) : "").append("</td>");
			sb.append("<td width=100 align=center>").append(current + 1).append(" / ").append(pages).append("</td>");
			sb.append("<td width=100 align=right>").append((current + 1) < pages ? boardButton(tr("Дальше"), bypass + " " + tab.name().toLowerCase() + " " + (current + 1), 60) : "").append("</td></tr></table><br>");
		}
		sb.append(tr("<font color=\"808080\">Позвать в пати: подойди и напиши «лфм» в общий чат (с 20-го — можно в торговый). Поговорить — просто назови по имени в общем чате.</font>"));
		return sb.toString();
	}

	/**
	 * Телепорт с доски на спот охотника или к нему (лавка, город). Проверки доски (бой, олимпиада, карма) — на стороне доски.
	 * @param player кто летит
	 * @param name имя охотника
	 * @return {@code null} — улетел (доска закрыта), иначе почему нельзя
	 */
	public static String teleport(Player player, String name)
	{
		final HunterView view = find(name);
		if (view == null)
		{
			return tr("Такого охотника сейчас нет в игре.");
		}

		final Location target = destination(view);
		if (target == null)
		{
			return view.phase.equals("PARTY") || view.phase.equals("RECRUIT") ? view.player.getName() + tr(" сейчас в пати — спроси шёпотом, где он.") : tr("Сейчас к ") + view.player.getName() + tr(" не попасть.");
		}
		if (player.isJailed() || (player.getInstanceId() != 0))
		{
			return tr("Отсюда телепорт не работает.");
		}

		final int fee = fee(player);
		if ((fee > 0) && !player.reduceAdena(ItemProcessType.FEE, fee, player, true))
		{
			return tr("Не хватает адены: телепорт стоит ") + money(fee) + ".";
		}

		player.sendPacket(new ShowBoard());
		PhantomCompat.leave7sDungeon(player);
		player.teleToLocation(target, true);
		return null;
	}

	/**
	 * Куда ведёт кнопка: на каче (и мёртвый на споте) — точка входа спота, в лавке и в городе — к охотнику; в пати, на зов и в инстансе — никуда.
	 */
	private static Location destination(HunterView view)
	{
		final Player hunter = view.player;
		if (hunter.getInstanceId() != 0)
		{
			return null;
		}

		switch (view.phase)
		{
			case "HUNT":
			case "DEAD":
			{
				return view.spot != null ? view.spot.entry : null;
			}
			case "STORE":
			case "TOWN":
			{
				return new Location(hunter.getX(), hunter.getY(), hunter.getZ());
			}
			default:
			{
				return null;
			}
		}
	}

	private static int fee(Player player)
	{
		return player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? Math.max(0, PhantomHuntersConfig.HUNTER_TELEPORT_FEE) : 0;
	}

	private static String boardButton(String label, String action, int width)
	{
		// Широкие кнопки (вкладки) — своей текстурой: в Interlude обычная не растягивается шире родной ширины.
		final boolean wide = width > 90;
		return "<button value=\"" + label + "\" action=\"" + action + "\" width=" + width + " height=22 back=\"" + (wide ? PhantomCompat.WIDE_BUTTON_BACK : PhantomCompat.BUTTON_BACK) + "\" fore=\"" + (wide ? PhantomCompat.WIDE_BUTTON_FORE : PhantomCompat.BUTTON_FORE) + "\">";
	}

	/** Ширина колонки окна охотников: в H5 доска шириной 520, у других хроник — {@link PhantomCompat#BOARD_WIDTH}. */
	private static int px(int h5)
	{
		return (h5 * PhantomCompat.BOARD_WIDTH) / 520;
	}

	// ---------- Общее ----------

	private static String doing(HunterView view)
	{
		return doing(view, false);
	}

	/**
	 * Что делает охотник — одной строкой.
	 * @param brief для узкой колонки доски: на каче — только спот и уровни мобов (цвет и так говорит, что качается)
	 */
	private static String doing(HunterView view, boolean brief)
	{
		final Player hunter = view.player;
		switch (view.phase)
		{
			case "DEAD":
			{
				return tr("лежит мёртвый") + (view.spot != null ? " — " + PhantomText.spotName(view.spot.name) : "");
			}
			case "HUNT":
			{
				if (view.spot == null)
				{
					return tr("выбирает спот");
				}
				final String party = hunter.isInParty() ? " · " + partyText(hunter) : "";
				return (brief ? PhantomText.spotName(view.spot.name) + tr(", мобы ") + view.spot.min + "–" + view.spot.max : tr("качается: ") + PhantomText.spotName(view.spot.name) + tr(" (мобы ") + view.spot.min + "–" + view.spot.max + ")") + party;
			}
			case "TOWN":
			{
				return tr("в городе: ") + MapRegionData.getInstance().getClosestTownName(hunter);
			}
			case "STORE":
			{
				if (!view.storeOpen)
				{
					return tr("идёт в лавку");
				}
				if (hunter.getPrivateStoreType() == PrivateStoreType.BUY)
				{
					return tr("скупка «") + escape(hunter.getBuyList().getTitle()) + "»";
				}
				return tr("лавка «") + escape(hunter.getSellList().getTitle()) + tr("», товаров: ") + hunter.getSellList().getItemCount();
			}
			case "RECRUIT":
			{
				return view.recruiter.isEmpty() ? tr("бежит на зов в пати") : tr("бежит в пати к ") + view.recruiter;
			}
			case "PARTY":
			{
				return hunter.isInParty() ? partyText(hunter) : tr("в пати");
			}
			default:
			{
				return view.phase;
			}
		}
	}

	/**
	 * Пати охотника (патч 0042): лидер — «ведёт пати: A, B»; участник — «в пати у Лидера (ещё A)».
	 */
	private static String partyText(Player hunter)
	{
		final Party party = hunter.getParty();
		final Player leader = party.getLeader();
		final List<String> others = new ArrayList<>();
		for (Player member : party.getMembers())
		{
			if ((member != hunter) && (member != leader))
			{
				others.add(member.getName());
			}
		}
		if (leader == hunter)
		{
			return tr("ведёт пати: ") + String.join(", ", others);
		}
		return tr("в пати у ") + leader.getName() + (others.isEmpty() ? "" : tr(" (ещё ") + String.join(", ", others) + ")");
	}

	private static String phaseColor(String phase)
	{
		switch (phase)
		{
			case "HUNT":
			{
				return "99CC99";
			}
			case "STORE":
			{
				return "E6C35C";
			}
			case "PARTY":
			case "RECRUIT":
			{
				return "88AAEE";
			}
			case "DEAD":
			{
				return "CC6666";
			}
			default:
			{
				return "B09878";
			}
		}
	}

	private static String counts(List<HunterView> all)
	{
		int hunt = 0;
		int town = 0;
		int store = 0;
		int party = 0;
		int dead = 0;
		for (HunterView view : all)
		{
			// В пати — и охотники в пати друг с другом, даже когда качаются на споте лидера (патч 0042).
			switch (view.player.isInParty() && !view.phase.equals("DEAD") ? "PARTY" : view.phase)
			{
				case "HUNT":
				{
					hunt++;
					break;
				}
				case "TOWN":
				{
					town++;
					break;
				}
				case "STORE":
				{
					store++;
					break;
				}
				case "DEAD":
				{
					dead++;
					break;
				}
				default:
				{
					party++;
					break;
				}
			}
		}
		return tr("на каче ") + hunt + tr(" · в городе ") + town + tr(" · в лавках ") + store + tr(" · в пати ") + party + tr(" · мёртвых ") + dead;
	}

	private static List<HunterView> filter(List<HunterView> all, Tab tab, Player viewer)
	{
		final List<HunterView> result = new ArrayList<>();
		for (HunterView view : all)
		{
			final boolean fits;
			switch (tab)
			{
				case NEAR:
				{
					fits = Math.abs(view.player.getLevel() - viewer.getLevel()) <= NEAR_LEVELS;
					break;
				}
				case STORE:
				{
					fits = view.phase.equals("STORE");
					break;
				}
				case HUNT:
				{
					fits = view.phase.equals("HUNT") || view.phase.equals("DEAD");
					break;
				}
				case TOWN:
				{
					fits = view.phase.equals("TOWN");
					break;
				}
				case PARTY:
				{
					// И пати охотников между собой (патч 0042): лидер качается на своём споте, фаза у него — HUNT.
					fits = view.phase.equals("PARTY") || view.phase.equals("RECRUIT") || view.player.isInParty();
					break;
				}
				default:
				{
					fits = true;
					break;
				}
			}
			if (fits)
			{
				result.add(view);
			}
		}
		return result;
	}

	private static void sortList(List<HunterView> list, Sort sort, Tab tab, Player viewer)
	{
		final Comparator<HunterView> byName = Comparator.comparing(view -> view.player.getName().toLowerCase());
		switch (sort)
		{
			case NAME:
			{
				list.sort(byName);
				break;
			}
			case NEAR:
			{
				list.sort(Comparator.<HunterView> comparingDouble(view -> view.player.getInstanceId() == viewer.getInstanceId() ? viewer.calculateDistance2D(view.player) : Double.MAX_VALUE).thenComparing(byName));
				break;
			}
			default:
			{
				// «Мой уровень» — сначала самые близкие по уровню, остальные — от старших к младшим.
				final Comparator<HunterView> level = tab == Tab.NEAR ? Comparator.comparingInt(view -> Math.abs(view.player.getLevel() - viewer.getLevel())) : Comparator.comparingInt(view -> -view.player.getLevel());
				list.sort(level.thenComparing(byName));
				break;
			}
		}
	}

	private static HunterView find(String name)
	{
		for (HunterView view : PhantomHunterManager.getInstance().views())
		{
			if (view.player.getName().equalsIgnoreCase(name))
			{
				return view;
			}
		}
		return null;
	}

	/**
	 * 950 → 950, 14 297 → 14к, 1 234 567 → 1,2кк.
	 */
	private static String money(long value)
	{
		if (value >= 1000000)
		{
			final long tenths = value / 100000;
			return (tenths / 10) + ((tenths % 10) != 0 ? "," + (tenths % 10) : "") + tr("кк");
		}
		if (value >= 10000)
		{
			return (value / 1000) + tr("к");
		}
		return String.valueOf(value);
	}

	private static String distance(Player from, Player to)
	{
		final int d = (int) from.calculateDistance2D(to);
		return d >= 10000 ? (d / 1000) + tr("к") : String.valueOf(d);
	}

	private static String escape(String text)
	{
		return text == null ? "" : text.replace("<", "&lt;").replace(">", "&gt;").replace("\"", "'");
	}

	private static int parse(String value)
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
