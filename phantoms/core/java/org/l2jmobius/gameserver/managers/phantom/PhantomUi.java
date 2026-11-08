package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.phantom.PhantomOrders.Order;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: окно напарников — одно и то же в .buddy (окно NPC) и на вкладке «Напарники» доски Alt+B.<br>
 * Кнопки шлют {@code <bypass> <действие> <имя>}: summon, invite, dismiss или приказ (assist, follow, stay, sit, stand, buff, heal, res).
 */
public final class PhantomUi
{
	private static final String BUTTON = "<button value=\"%s\" action=\"%s %s %s\" width=85 height=21 back=\"" + PhantomCompat.BUTTON_BACK + "\" fore=\"" + PhantomCompat.BUTTON_FORE + "\">";
	/** Блок с приказами — только у стольких своих, остальные напарники одной строкой: окно Interlude режется на 8192 знаках. */
	private static final int FULL_BLOCKS = 2;

	/** Кнопки приказов: действие → приказ и как он звучал бы словами (для мозгов). */
	private static final Map<String, Order> ORDERS = Map.of("assist", Order.ASSIST, "follow", Order.FOLLOW, "stay", Order.STAY, "sit", Order.SIT, "stand", Order.STAND, "buff", Order.BUFF, "heal", Order.HEAL, "res", Order.RES);
	private static final Map<Order, String> SAID = new EnumMap<>(Order.class);
	static
	{
		SAID.put(Order.ASSIST, "бей мою цель");
		SAID.put(Order.FOLLOW, "за мной");
		SAID.put(Order.STAY, "стой тут");
		SAID.put(Order.SIT, "сядь отдохни");
		SAID.put(Order.STAND, "встань");
		SAID.put(Order.BUFF, "бафни");
		SAID.put(Order.HEAL, "хильни");
		SAID.put(Order.RES, "рес");
	}

	private PhantomUi()
	{
	}

	/**
	 * Кнопка или команда: позвать, взять в пати, отпустить или приказ.
	 * @param player кто нажал
	 * @param action действие
	 * @param key имя напарника или роль (healer, buffer)
	 * @return текст ошибки для игрока или {@code null}
	 */
	public static String handle(Player player, String action, String key)
	{
		// Личная пачка (PersonalSavedParty, PersonalBattleCatalog): свой состав, выбор класса, страницы списка.
		if ("squad".equalsIgnoreCase(action))
		{
			return PersonalSavedParty.handle(player, key);
		}
		if ("add".equalsIgnoreCase(action))
		{
			return PersonalBattleCatalog.add(player, key);
		}
		if ("page".equalsIgnoreCase(action))
		{
			return PersonalBattleCatalog.setPage(player, key);
		}
		if ("classes".equalsIgnoreCase(action))
		{
			return PersonalBattleCatalog.open(player, key);
		}
		if ("back".equalsIgnoreCase(action))
		{
			PersonalBattleCatalog.back(player);
			PersonalSavedParty.close(player);
			return null;
		}
		final boolean summon = "summon".equalsIgnoreCase(action);
		final PhantomTemplate template = key == null ? null : find(player, key, summon);
		if (template == null)
		{
			return summon && (key != null) && (find(player, key, false) != null) ? tr("Свободных напарников такой роли нет: все заняты. Кто с кем — в .buddy") : tr("Нет такого напарника. Список — .buddy");
		}

		final String accessError = PersonalCompanionClans.access(player, template);
		if (accessError != null)
		{
			return accessError;
		}
		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		switch (action.toLowerCase())
		{
			case "summon":
			{
				// Помощник с профилем пачки — через каталог: сразу в пати и в сохранённый состав.
				return PersonalCompanionNames.named(template.getName()) != null ? PersonalBattleCatalog.summon(player, template) : companions.summon(player, template);
			}
			case "clan":
			{
				return PersonalCompanionClans.join(player, template);
			}
			case "selfbuff":
			{
				return PersonalCompanions.prepareNow(player, template);
			}
			case "invite":
			{
				return companions.invite(player, template);
			}
			case "dismiss":
			{
				return companions.dismiss(player, template);
			}
			default:
			{
				final Order order = ORDERS.get(action.toLowerCase());
				if (order == null)
				{
					return tr("Не понял: ") + action + tr(". Можно summon, invite, dismiss, assist, follow, stay, sit, stand, buff, heal, res.");
				}

				if (!companions.isPartiedWith(player, template))
				{
					return template.getName() + tr(" не в пати с тобой — нажми «Позвать» или «В пати».");
				}

				PhantomChat.getInstance().command(player, template, order, SAID.get(order));
				return null;
			}
		}
	}

	/**
	 * @param player кто смотрит
	 * @param bypass начало действия кнопок: {@code bypass voice .buddy} или {@code bypass _bbshubbuddy}
	 * @return список напарников с состоянием и кнопками (без html и body)
	 */
	public static String render(Player player, String bypass)
	{
		if (PersonalSavedParty.open(player))
		{
			return PersonalSavedParty.board(player, bypass);
		}
		final String view = PersonalBattleCatalog.view(player);
		if (view != null)
		{
			return PersonalBattleCatalog.chooser(player, bypass, view);
		}
		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		final StringBuilder sb = new StringBuilder(6144);
		if (PersonalBattleCatalog.available())
		{
			sb.append(PersonalSavedParty.summary(player, bypass));
			sb.append(PersonalBattleCatalog.menu(bypass));
		}
		final List<PhantomTemplate> mine = new ArrayList<>();
		final List<PhantomTemplate> free = new ArrayList<>();
		final List<PhantomTemplate> busy = new ArrayList<>();
		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			if (!PersonalBattleCatalog.visible(player, template))
			{
				continue; // чертёж класса пачки и чужие экземпляры не показываются
			}
			if ((PhantomManager.getInstance().getPhantom(template) != null) && companions.isOwner(player, template))
			{
				mine.add(template);
			}
			else if (companions.busyWith(player, template) != null)
			{
				busy.add(template);
			}
			else
			{
				free.add(template);
			}
		}

		final int limit = PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER;
		if (limit > 0)
		{
			sb.append("<table width=280><tr><td width=280>").append(tr("Напарников с тобой: ")).append(mine.size()).append(tr(" из ")).append(limit).append("</td></tr></table><br>");
		}
		for (int i = 0; i < mine.size(); i++)
		{
			if (i < FULL_BLOCKS)
			{
				full(sb, player, mine.get(i), bypass);
			}
			else
			{
				row(sb, player, mine.get(i), bypass, tr("Отпустить"), "dismiss");
			}
		}
		for (PhantomTemplate template : free)
		{
			row(sb, player, template, bypass, tr("Позвать"), "summon");
		}
		for (PhantomTemplate template : busy)
		{
			row(sb, player, template, bypass, null, null);
		}
		sb.append("<br>");
		sb.append(tr("<table width=280><tr><td width=280><font color=\"808080\">«Позвать» — напарник прибежит, подтянется до твоего уровня и сразу вступит в пати. Идёт следом, отставший телепортируется к тебе. Хилер лечит и поднимает, баффер держит бафы, оба садятся на MP. Опыт и лут — тебе.</font></td></tr>"));
		sb.append(tr("<tr><td width=280><font color=\"808080\">Кнопки — то же, что словами в чате пати или шёпотом: «за мной», «стой», «бафни», «хил», «рес», «сядь», «встань», «пока». По имени — только одному: «Митяй, бафни». Просто поговорить с ними тоже можно.</font></td></tr></table><br>"));
		commands(sb);
		return sb.toString();
	}

	/**
	 * Свой напарник: состояние и все кнопки.
	 */
	private static void full(StringBuilder sb, Player player, PhantomTemplate template, String bypass)
	{
		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		final String name = template.getName();
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		final boolean partied = companions.isPartiedWith(player, template);
		sb.append("<table width=280><tr><td width=280><font color=\"LEVEL\">").append(name).append("</font> — ").append(caption(template));
		if (phantom != null)
		{
			sb.append(", ").append(phantom.getLevel()).append(" ").append(className(phantom));
		}
		sb.append("</td></tr><tr><td width=280>").append(profile(player, template, phantom)).append("</td></tr>");
		sb.append("<tr><td width=280><font color=\"B09878\">").append(companions.describe(player, template)).append("</font></td></tr></table>");

		sb.append("<table width=280><tr>");
		cell(sb, bypass, tr("Позвать"), "summon", name);
		cell(sb, bypass, tr("Отпустить"), "dismiss", name);
		if (!partied)
		{
			cell(sb, bypass, tr("В пати"), "invite", name);
		}
		else
		{
			sb.append("<td></td>");
		}
		sb.append("</tr>");

		if (partied)
		{
			// Хилер и баффер не дерутся: вместо «Бей мою цель» — их работа по кнопке.
			sb.append("<tr>");
			switch (template.getRole())
			{
				case HEALER:
				{
					cell(sb, bypass, tr("За мной"), "follow", name);
					cell(sb, bypass, tr("Стой"), "stay", name);
					cell(sb, bypass, tr("Хильни"), "heal", name);
					sb.append("</tr><tr>");
					cell(sb, bypass, tr("Рес"), "res", name);
					cell(sb, bypass, tr("Сядь"), "sit", name);
					cell(sb, bypass, tr("Встань"), "stand", name);
					break;
				}
				case BUFFER:
				{
					cell(sb, bypass, tr("За мной"), "follow", name);
					cell(sb, bypass, tr("Стой"), "stay", name);
					cell(sb, bypass, tr("Бафни"), "buff", name);
					sb.append("</tr><tr>");
					cell(sb, bypass, tr("Рес"), "res", name);
					cell(sb, bypass, tr("Сядь"), "sit", name);
					cell(sb, bypass, tr("Встань"), "stand", name);
					break;
				}
				default:
				{
					cell(sb, bypass, tr("Бей мою цель"), "assist", name);
					cell(sb, bypass, tr("За мной"), "follow", name);
					cell(sb, bypass, tr("Стой"), "stay", name);
					break;
				}
			}
			sb.append("</tr>");
			if (PhantomCompat.personalPrepareSupported() && PersonalCompanions.personal(template))
			{
				sb.append("<tr>");
				cell(sb, bypass, tr("Бафы себе"), "selfbuff", name);
				sb.append("<td></td><td></td></tr>");
			}
		}
		// Свой помощник с профилем пачки — в клан владельца; членство остаётся и после отзыва.
		if ((phantom != null) && companions.isOwner(player, template) && (player.getClan() != null) && (PersonalCompanionNames.named(name) != null))
		{
			sb.append("<tr>");
			if (phantom.getClanId() == 0)
			{
				cell(sb, bypass, tr("В клан"), "clan", name);
			}
			else
			{
				sb.append("<td colspan=3><font color=808080>").append(phantom.getClanId() == player.getClanId() ? tr("В твоём клане") : tr("В другом клане")).append("</font></td>");
			}
			sb.append("</tr>");
		}
		sb.append("</table><br>");
	}

	/**
	 * Роль напарника или класс помощника пачки («воин — Гладиатор»).
	 */
	private static String caption(PhantomTemplate template)
	{
		final String caption = PersonalBattleCatalog.caption(template);
		return caption == null ? roleName(template.getRole()) : caption;
	}

	/**
	 * Напарник одной строкой: имя, роль, состояние и не больше одной кнопки.
	 */
	private static void row(StringBuilder sb, Player player, PhantomTemplate template, String bypass, String label, String action)
	{
		sb.append("<table width=280><tr><td width=195><font color=\"LEVEL\">").append(template.getName()).append("</font> — ").append(caption(template));
		sb.append("<br1>").append(profile(player, template, PhantomManager.getInstance().getPhantom(template)));
		sb.append("<br1><font color=\"B09878\">").append(PhantomCompanionManager.getInstance().describe(player, template)).append("</font></td>");
		if (label != null)
		{
			cell(sb, bypass, label, action, template.getName());
		}
		sb.append("</tr></table>");
	}

	/**
	 * Шпаргалка приказов: напарникам и охотникам в пати (их понимает {@link PhantomOrders}), где искать охотников.
	 */
	private static void commands(StringBuilder sb)
	{
		sb.append(tr("<table width=280><tr><td width=280><font color=\"LEVEL\">Приказы в чате пати</font> <font color=\"808080\">(# перед текстом — в пати)</font></td></tr>"));
		order(sb, tr("за мной"), tr("идут следом"));
		order(sb, tr("стой"), tr("ждут на месте"));
		order(sb, tr("бей"), tr("бьют твою цель"));
		order(sb, tr("кач тут"), tr("сами бьют всех мобов вокруг тебя"));
		order(sb, tr("веди"), tr("пешка качается, ты идёшь за ней"));
		order(sb, tr("сядь / встань"), tr("отдых на HP и MP"));
		order(sb, tr("бафни / хил / рес"), tr("работа баффера и хилера"));
		order(sb, tr("пока"), tr("уходит из пати"));
		sb.append(tr("<tr><td width=280><font color=\"808080\">Пешку в пати — подойди и напиши «лфм» в общий чат. Где они сейчас — .hunters.</font></td></tr></table>"));
	}

	private static void order(StringBuilder sb, String phrase, String meaning)
	{
		sb.append("<tr><td width=280><font color=\"B09878\">").append(phrase).append("</font> — ").append(meaning).append("</td></tr>");
	}

	/**
	 * Имя напарника или его роль (healer, buffer). По роли — сперва свой той же роли, а когда зовут — сперва свободный, потому что своего уже позвали.
	 * @param player кто спрашивает
	 * @param key имя или роль
	 * @param free зовут: занятый другими по роли не подходит
	 * @return напарник или {@code null}
	 */
	private static PhantomTemplate find(Player player, String key, boolean free)
	{
		final PhantomTemplate byName = PhantomData.getInstance().getTemplate(PersonalCompanionNames.canonical(key));
		if (byName != null)
		{
			return byName;
		}

		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		PhantomTemplate mine = null;
		PhantomTemplate idle = null;
		PhantomTemplate any = null;
		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			if (!template.getRole().name().equalsIgnoreCase(key))
			{
				continue;
			}

			any = any == null ? template : any;
			if (companions.isOwner(player, template))
			{
				mine = mine == null ? template : mine;
			}
			else if ((idle == null) && (companions.busyWith(player, template) == null))
			{
				idle = template;
			}
		}
		if (free)
		{
			return idle != null ? idle : mine;
		}
		return mine != null ? mine : any;
	}

	private static void cell(StringBuilder sb, String bypass, String label, String action, String name)
	{
		sb.append("<td>").append(String.format(BUTTON, label, bypass, action, name)).append("</td>");
	}

	/** Смены профессии напарника: с каких уровней берётся следующий класс ветки (как в {@code PhantomTemplate}). */
	private static final int[] TIER_LEVELS =
	{
		1,
		20,
		40,
		76
	};

	/**
	 * Кто он в пати (отчёт автора 2026-11-06: шесть напарников, и не понятно, кто какой профы будет и кто за что отвечает): что делает по роли,
	 * каким классом придёт на твоём уровне (или какой он сейчас) и во что вырастет дальше.
	 */
	private static String profile(Player player, PhantomTemplate template, Player phantom)
	{
		final int level = phantom != null ? phantom.getLevel() : player.getLevel();
		final StringBuilder sb = new StringBuilder(160);
		sb.append("<font color=\"A3A0A3\">").append(roleJob(template.getRole())).append("</font><br1>");
		sb.append(phantom != null ? tr("Класс: ") : tr("Придёт: ")).append(className(PlayerClass.getPlayerClass(template.getClassId(level))));
		int last = template.getClassId(level);
		for (int tier : TIER_LEVELS)
		{
			final int classId = template.getClassId(tier);
			if ((tier > level) && (classId != last))
			{
				sb.append(", ").append(tr("с ")).append(tier).append(" — ").append(className(PlayerClass.getPlayerClass(classId)));
				last = classId;
			}
		}
		return sb.toString();
	}

	/**
	 * @return что напарник делает в пати
	 */
	private static String roleJob(PhantomTemplate.Role role)
	{
		switch (role)
		{
			case HEALER:
			{
				return tr("Лечит и поднимает пати, сам не дерётся.");
			}
			case BUFFER:
			{
				return tr("Держит бафы на пати и поднимает павших, сам не дерётся.");
			}
			case TANK:
			{
				return tr("Держит мобов на себе и снимает их с пати.");
			}
			default:
			{
				return tr("Бьёт твою цель или мобов вокруг.");
			}
		}
	}

	private static String roleName(PhantomTemplate.Role role)
	{
		switch (role)
		{
			case HEALER:
			{
				return tr("хилер");
			}
			case BUFFER:
			{
				return tr("баффер");
			}
			case TANK:
			{
				return tr("танк");
			}
			default:
			{
				return tr("дамагер");
			}
		}
	}

	/**
	 * BISHOP → Bishop, ELVEN_ELDER → Elven Elder.
	 */
	static String className(Player phantom)
	{
		return className(phantom.getPlayerClass());
	}

	/**
	 * BISHOP → Bishop, ELVEN_ELDER → Elven Elder.
	 */
	static String className(PlayerClass playerClass)
	{
		if (playerClass == null)
		{
			return "?";
		}
		final StringBuilder sb = new StringBuilder();
		for (String word : playerClass.name().split("_"))
		{
			if (sb.length() > 0)
			{
				sb.append(' ');
			}
			sb.append(word.charAt(0)).append(word.substring(1).toLowerCase());
		}
		return sb.toString();
	}
}
