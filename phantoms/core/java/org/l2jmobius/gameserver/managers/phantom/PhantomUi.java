package org.l2jmobius.gameserver.managers.phantom;

import java.util.EnumMap;
import java.util.Map;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.phantom.PhantomOrders.Order;

/**
 * L2Hub: окно напарников — одно и то же в .buddy (окно NPC) и на вкладке «Напарники» доски Alt+B.<br>
 * Кнопки шлют {@code <bypass> <действие> <имя>}: summon, invite, dismiss или приказ (assist, follow, stay, sit, stand, buff, heal, res).
 */
public final class PhantomUi
{
	private static final String BUTTON = "<button value=\"%s\" action=\"%s %s %s\" width=85 height=21 back=\"" + PhantomCompat.BUTTON_BACK + "\" fore=\"" + PhantomCompat.BUTTON_FORE + "\">";

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
		final PhantomTemplate template = key == null ? null : find(key);
		if (template == null)
		{
			return "Нет такого напарника. Список — .buddy";
		}

		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		switch (action.toLowerCase())
		{
			case "summon":
			{
				return companions.summon(player, template);
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
					return "Не понял: " + action + ". Можно summon, invite, dismiss, assist, follow, stay, sit, stand, buff, heal, res.";
				}

				if (!companions.isPartiedWith(player, template))
				{
					return template.getName() + " не в пати с тобой — нажми «Позвать» или «В пати».";
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
		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		final StringBuilder sb = new StringBuilder(6144);
		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			final String name = template.getName();
			final Player phantom = PhantomManager.getInstance().getPhantom(template);
			final boolean partied = companions.isPartiedWith(player, template);
			final boolean mine = (phantom != null) && companions.isOwner(player, template);
			sb.append("<table width=280><tr><td width=280><font color=\"LEVEL\">").append(name).append("</font> — ").append(roleName(template.getRole()));
			if (phantom != null)
			{
				sb.append(", ").append(phantom.getLevel()).append(" ").append(className(phantom));
			}
			sb.append("</td></tr><tr><td width=280><font color=\"B09878\">").append(companions.describe(player, template)).append("</font></td></tr></table>");

			sb.append("<table width=280><tr>");
			cell(sb, bypass, "Позвать", "summon", name);
			cell(sb, bypass, "Отпустить", "dismiss", name);
			if (mine && !partied)
			{
				cell(sb, bypass, "В пати", "invite", name);
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
						cell(sb, bypass, "За мной", "follow", name);
						cell(sb, bypass, "Стой", "stay", name);
						cell(sb, bypass, "Хильни", "heal", name);
						sb.append("</tr><tr>");
						cell(sb, bypass, "Рес", "res", name);
						cell(sb, bypass, "Сядь", "sit", name);
						cell(sb, bypass, "Встань", "stand", name);
						break;
					}
					case BUFFER:
					{
						cell(sb, bypass, "За мной", "follow", name);
						cell(sb, bypass, "Стой", "stay", name);
						cell(sb, bypass, "Бафни", "buff", name);
						sb.append("</tr><tr>");
						cell(sb, bypass, "Рес", "res", name);
						cell(sb, bypass, "Сядь", "sit", name);
						cell(sb, bypass, "Встань", "stand", name);
						break;
					}
					default:
					{
						cell(sb, bypass, "Бей мою цель", "assist", name);
						cell(sb, bypass, "За мной", "follow", name);
						cell(sb, bypass, "Стой", "stay", name);
						break;
					}
				}
				sb.append("</tr>");
			}
			sb.append("</table><br>");
		}
		sb.append("<table width=280><tr><td width=280><font color=\"808080\">«Позвать» — напарник прибежит, подтянется до твоего уровня и сразу вступит в пати. Идёт следом, отставший телепортируется к тебе. Хилер лечит и поднимает, баффер держит бафы, оба садятся на MP. Опыт и лут — тебе.</font></td></tr>");
		sb.append("<tr><td width=280><font color=\"808080\">Кнопки — то же, что словами в чате пати или шёпотом: «за мной», «стой», «бафни», «хил», «рес», «сядь», «встань», «пока». По имени — только одному: «Митяй, бафни». Просто поговорить с ними тоже можно.</font></td></tr></table><br>");
		commands(sb);
		return sb.toString();
	}

	/**
	 * Шпаргалка приказов: напарникам и охотникам в пати (их понимает {@link PhantomOrders}), где искать охотников.
	 */
	private static void commands(StringBuilder sb)
	{
		sb.append("<table width=280><tr><td width=280><font color=\"LEVEL\">Приказы в чате пати</font> <font color=\"808080\">(# перед текстом — в пати)</font></td></tr>");
		order(sb, "за мной", "идут следом");
		order(sb, "стой", "ждут на месте");
		order(sb, "бей", "бьют твою цель");
		order(sb, "кач тут", "сами бьют всех мобов вокруг тебя");
		order(sb, "веди", "охотник качается, ты идёшь за ним");
		order(sb, "сядь / встань", "отдых на HP и MP");
		order(sb, "бафни / хил / рес", "работа баффера и хилера");
		order(sb, "пока", "уходит из пати");
		sb.append("<tr><td width=280><font color=\"808080\">Охотника в пати — подойди и напиши «лфм» в общий чат. Где они сейчас — .hunters.</font></td></tr></table>");
	}

	private static void order(StringBuilder sb, String phrase, String meaning)
	{
		sb.append("<tr><td width=280><font color=\"B09878\">").append(phrase).append("</font> — ").append(meaning).append("</td></tr>");
	}

	/**
	 * Имя напарника или его роль (healer, buffer).
	 */
	private static PhantomTemplate find(String key)
	{
		final PhantomTemplate byName = PhantomData.getInstance().getTemplate(key);
		if (byName != null)
		{
			return byName;
		}

		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			if (template.getRole().name().equalsIgnoreCase(key))
			{
				return template;
			}
		}
		return null;
	}

	private static void cell(StringBuilder sb, String bypass, String label, String action, String name)
	{
		sb.append("<td>").append(String.format(BUTTON, label, bypass, action, name)).append("</td>");
	}

	private static String roleName(PhantomTemplate.Role role)
	{
		switch (role)
		{
			case HEALER:
			{
				return "хилер";
			}
			case BUFFER:
			{
				return "баффер";
			}
			case TANK:
			{
				return "танк";
			}
			default:
			{
				return "дамагер";
			}
		}
	}

	/**
	 * BISHOP → Bishop, ELVEN_ELDER → Elven Elder.
	 */
	static String className(Player phantom)
	{
		final StringBuilder sb = new StringBuilder();
		for (String word : phantom.getPlayerClass().name().split("_"))
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
