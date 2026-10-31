package org.l2jmobius.gameserver.managers.phantom;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanAccess;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: кланы на доске Alt+B (docs/PHANTOMS.md, раздел 4.59; по вкладу пользователя).
 * <ul>
 * <li>{@code _bbshubwars} — все кланы и объявление войны с подтверждением, по тем же условиям, что пакет ({@link PhantomClanWars#eligible});</li>
 * <li>{@code _bbshubclan} — набор в клан игрока: глава открывает набор с фильтром уровня и роли, свободные охотники подают заявки, глава или член клана с правом
 * приглашать принимает. Заявка — решение самого охотника, поэтому правило приглашения «незнакомый отказывает» ({@code PhantomClans.decide}) здесь не при чём.
 * Настройки набора переживают перезапуск (глобальная переменная {@value #RECRUIT_VARIABLE}), заявки — нет: охотники подадут снова.</li>
 * </ul>
 * Весь набор — под одним монитором {@link PhantomHunterClans}: и ход кланов, и окно, и приём.
 */
public final class PhantomClanBoard
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanBoard.class.getName());

	private static final String WARS = "bypass _bbshubwars";
	private static final String RECRUIT = "bypass _bbshubclan";
	private static final int PAGE_SIZE = 10;
	/** Подтверждение войны действует столько. */
	private static final long CONFIRM_TIME = 120000;
	/** Заявки пополняются не чаще. */
	private static final long FILL_GAP = 60000;
	/** Заявка действует столько; после отказа тот же охотник не подаёт столько же. */
	private static final long APPLICATION_TIME = 3600000;
	private static final String RECRUIT_VARIABLE = "L2HubClanRecruit_";

	/** Роль в фильтре набора. */
	private enum Wanted
	{
		ANY,
		FIGHTER,
		MAGE,
		TANK,
		HEALER,
		BUFFER;

		String label()
		{
			switch (this)
			{
				case FIGHTER:
				{
					return tr("Бойцы");
				}
				case MAGE:
				{
					return tr("Маги");
				}
				case TANK:
				{
					return tr("Танки");
				}
				case HEALER:
				{
					return tr("Хилеры");
				}
				case BUFFER:
				{
					return tr("Бафферы");
				}
				default:
				{
					return tr("Любые");
				}
			}
		}

		static Wanted parse(String value)
		{
			for (Wanted wanted : values())
			{
				if (wanted.name().equalsIgnoreCase(value) || wanted.label().equals(value))
				{
					return wanted;
				}
			}
			return null;
		}
	}

	private record Pending(int source, int target, long expires, String token)
	{
	}

	private record Applicant(int id, String name, int level, String profession, long created)
	{
	}

	/** Набор клана. Настройки — в глобальной переменной, заявки и отказы — только в памяти. */
	private static final class Recruit
	{
		final int clanId;
		boolean open;
		int min;
		int max;
		Wanted wanted = Wanted.ANY;
		long nextFill;
		final Map<Integer, Applicant> applicants = new LinkedHashMap<>();
		final Map<Integer, Long> declined = new HashMap<>();

		Recruit(int clanId)
		{
			this.clanId = clanId;
			min = 1;
			max = levelCap();
			final String[] stored = GlobalVariablesManager.getInstance().getString(RECRUIT_VARIABLE + clanId, "").split(";");
			if (stored.length == 4)
			{
				try
				{
					open = Boolean.parseBoolean(stored[0]);
					min = Integer.parseInt(stored[1]);
					max = Integer.parseInt(stored[2]);
					final Wanted role = Wanted.parse(stored[3]);
					wanted = role != null ? role : Wanted.ANY;
				}
				catch (NumberFormatException e)
				{
					open = false;
					min = 1;
					max = levelCap();
				}
			}
		}

		void save()
		{
			GlobalVariablesManager.getInstance().set(RECRUIT_VARIABLE + clanId, open + ";" + min + ";" + max + ";" + wanted.name());
		}
	}

	private final Map<Integer, Pending> _pending = new HashMap<>();
	private final Map<Integer, Recruit> _recruits = new HashMap<>();
	private final Map<Integer, Set<Integer>> _picked = new HashMap<>();
	private boolean _restored;

	private PhantomClanBoard()
	{
	}

	private static int levelCap()
	{
		return ExperienceData.getInstance().getMaxLevel() - 1;
	}

	// ---------- Войны ----------

	/**
	 * _bbshubwars [all|hunters|players] [страница] | confirm &lt;клан&gt; [вкладка] [страница] | declare &lt;клан&gt; &lt;вкладка&gt; &lt;страница&gt; &lt;токен&gt;
	 * @return содержимое страницы
	 */
	public String wars(Player player, String[] args)
	{
		synchronized (PhantomHunterClans.getInstance())
		{
			final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "all";
			final boolean request = action.equals("confirm") || action.equals("declare");
			final int shift = request ? 3 : 1;
			final String tab = tab(args.length > shift ? args[shift] : "all");
			final int page = number(args.length > (shift + 1) ? args[shift + 1] : "0");
			String message = "";
			if (request)
			{
				final Clan own = player.getClan();
				final Clan target = ClanTable.getInstance().getClan(number(args.length > 2 ? args[2] : "0"));
				final String refusal = refusal(player, own, target);
				if (refusal != null)
				{
					message = refusal;
				}
				else if (action.equals("confirm"))
				{
					return confirmation(player, own, target, tab, page);
				}
				else if (!takeToken(player, own, target, args.length > 5 ? args[5] : ""))
				{
					message = tr("Подтверждение устарело. Выбери клан и подтверди войну заново.");
				}
				else
				{
					PhantomCompat.declareWar(own, target);
					LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " declares war of " + own.getName() + " on " + target.getName() + " from the board.");
					message = tr("Объявлена война клану «") + target.getName() + tr("». Взаимной она станет, когда другая сторона ответит.");
				}
			}
			return warList(player, tab, page, message);
		}
	}

	/**
	 * @return почему объявить нельзя или {@code null}
	 */
	private static String refusal(Player player, Clan own, Clan target)
	{
		if (target == null)
		{
			return tr("Такого клана больше нет.");
		}
		if (own == null)
		{
			return tr("Чтобы объявить войну, нужно состоять в клане.");
		}
		if (own.getId() == target.getId())
		{
			return tr("Твой клан");
		}
		if (!player.hasAccess(ClanAccess.WAR_DECLARATION))
		{
			return tr("Нет права объявлять войну.");
		}
		if (!PhantomClanWars.eligible(own) || !PhantomClanWars.eligible(target))
		{
			return PhantomClanWars.requirements();
		}
		if ((own.getAllyId() != 0) && (own.getAllyId() == target.getAllyId()))
		{
			return tr("Клан в вашем альянсе.");
		}
		if (PhantomCompat.atWar(own, target))
		{
			return tr("Идёт война");
		}
		return PhantomCompat.warDeclaredBy(own, target) ? tr("Война уже объявлена") : null;
	}

	private String confirmation(Player player, Clan own, Clan target, String tab, int page)
	{
		final long now = System.currentTimeMillis();
		_pending.values().removeIf(pending -> pending.expires() <= now);
		final String token = UUID.randomUUID().toString();
		_pending.put(player.getObjectId(), new Pending(own.getId(), target.getId(), now + CONFIRM_TIME, token));
		return "<br>" + tr("Объявить войну клану ") + "<font color=\"LEVEL\">" + escape(target.getName()) + "</font>?<br><br>" //
			+ tr("Уровень: ") + target.getLevel() + tr("; участников: ") + target.getMembersCount() + tr("; в сети: ") + target.getOnlineMembers(0).size() + ".<br>" //
			+ tr("Твой клан: ") + escape(own.getName()) + ".<br><br>" + tr("Взаимной война станет, когда другая сторона ответит.") + "<br><br>" //
			+ button(tr("Подтвердить войну"), WARS + " declare " + target.getId() + " " + tab + " " + page + " " + token, 180) + "<br>" + button(tr("Отмена"), WARS + " " + tab + " " + page, 130);
	}

	private boolean takeToken(Player player, Clan own, Clan target, String token)
	{
		final Pending pending = _pending.remove(player.getObjectId());
		return (pending != null) && (pending.source() == own.getId()) && (pending.target() == target.getId()) && (System.currentTimeMillis() < pending.expires()) && pending.token().equals(token);
	}

	private static String warList(Player player, String tab, int requestedPage, String message)
	{
		final Clan own = player.getClan();
		final List<Clan> clans = new ArrayList<>();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (tab.equals("all") || (tab.equals("hunters") == PhantomHunterClans.isHunterClan(clan)))
			{
				clans.add(clan);
			}
		}
		clans.sort(Comparator.comparingInt(Clan::getLevel).reversed().thenComparing(Comparator.comparingInt(Clan::getMembersCount).reversed()).thenComparing(Clan::getName, String.CASE_INSENSITIVE_ORDER));
		final int pages = Math.max(1, (clans.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		final int page = Math.max(0, Math.min(requestedPage, pages - 1));

		final StringBuilder out = new StringBuilder(9000);
		out.append("<table width=520><tr><td>").append(button(tr("Все"), WARS + " all 0", 110)).append("</td><td>").append(button(tr("Кланы охотников"), WARS + " hunters 0", 145)).append("</td><td>").append(button(tr("Кланы игроков"), WARS + " players 0", 145)).append("</td></tr></table><br>");
		if (!message.isEmpty())
		{
			out.append("<font color=\"LEVEL\">").append(escape(message)).append("</font><br><br>");
		}
		out.append("<font color=\"AFAFAF\">").append(tr("Кланов: ")).append(clans.size()).append(tr(". Состав: всего участников / в сети.")).append("<br>").append(escape(PhantomClanWars.requirements())).append("</font><br>");
		if (own == null)
		{
			out.append("<font color=\"AFAFAF\">").append(tr("Ты не в клане: список только для просмотра.")).append("</font><br>");
		}
		out.append("<table width=520 cellpadding=3 cellspacing=0><tr><td width=165>").append(tr("Клан / глава")).append("</td><td width=30>").append(tr("Ур.")).append("</td><td width=70>").append(tr("Состав")).append("</td><td width=255>").append(tr("Война")).append("</td></tr>");
		for (int i = page * PAGE_SIZE; i < Math.min(clans.size(), (page + 1) * PAGE_SIZE); i++)
		{
			final Clan clan = clans.get(i);
			out.append("<tr><td><font color=\"CDB67F\">").append(escape(clan.getName())).append("</font><br><font color=\"909090\">").append(PhantomHunterClans.isHunterClan(clan) ? tr("Охотники") : tr("Игроки")).append("; ").append(escape(clan.getLeaderName())).append("</font></td><td>").append(clan.getLevel()).append("</td><td>").append(clan.getMembersCount()).append(" / ").append(clan.getOnlineMembers(0).size()).append("</td><td>");
			final String refusal = own == null ? "" : refusal(player, own, clan);
			if (refusal == null)
			{
				final boolean incoming = PhantomCompat.warDeclaredBy(clan, own);
				out.append(incoming ? tr("Вам объявлена война") + "<br>" : "").append(button(incoming ? tr("Ответить войной") : tr("Объявить войну"), WARS + " confirm " + clan.getId() + " " + tab + " " + page, 145));
			}
			else
			{
				out.append("<font color=\"909090\">").append(escape(refusal.equals(PhantomClanWars.requirements()) ? tr("Не по условиям") : refusal)).append("</font>");
			}
			out.append("</td></tr>");
		}
		if (clans.isEmpty())
		{
			out.append("<tr><td>").append(tr("Здесь кланов пока нет.")).append("</td></tr>");
		}
		out.append("</table><br><table width=520><tr><td width=150>");
		if (page > 0)
		{
			out.append(button(tr("Назад"), WARS + " " + tab + " " + (page - 1), 110));
		}
		out.append("</td><td width=220 align=center>").append(tr("Страница ")).append(page + 1).append(" / ").append(pages).append("</td><td width=150>");
		if ((page + 1) < pages)
		{
			out.append(button(tr("Далее"), WARS + " " + tab + " " + (page + 1), 110));
		}
		return out.append("</td></tr></table>").toString();
	}

	private static String tab(String value)
	{
		return "hunters".equals(value) || "players".equals(value) ? value : "all";
	}

	// ---------- Набор ----------

	/**
	 * Из {@link PhantomHunterClans#control}, уже под его монитором: заявки устарели, новые — пока глава клана, живой игрок, в мире.
	 */
	void control(List<Player> hunters, long now)
	{
		if (!enabled())
		{
			return;
		}
		if (!_restored)
		{
			_restored = true;
			for (Clan clan : ClanTable.getInstance().getClans())
			{
				if (!GlobalVariablesManager.getInstance().getString(RECRUIT_VARIABLE + clan.getId(), "").isEmpty())
				{
					_recruits.computeIfAbsent(clan.getId(), Recruit::new);
				}
			}
		}
		for (Iterator<Recruit> iterator = _recruits.values().iterator(); iterator.hasNext();)
		{
			final Recruit recruit = iterator.next();
			final Clan clan = ClanTable.getInstance().getClan(recruit.clanId);
			if (clan == null)
			{
				iterator.remove();
				continue;
			}
			prune(recruit, clan, now);
			final Player leader = clan.getLeader() == null ? null : clan.getLeader().getPlayer();
			if (recruit.open && (room(clan) > 0) && (leader != null) && leader.isOnline() && !PhantomManager.getInstance().isPhantom(leader) && (now >= recruit.nextFill))
			{
				recruit.nextFill = now + FILL_GAP;
				final int count = fill(recruit, clan, leader, hunters, PhantomHuntersConfig.HUNTER_CLAN_RECRUIT_PER_MINUTE, now);
				if (count > 0)
				{
					leader.sendMessage(tr("Набор в клан: новых заявок — ") + count + tr(". Alt+B, «Набор в клан»."));
				}
			}
		}
		_picked.keySet().removeIf(id -> World.getPlayer(id) == null);
	}

	private static boolean enabled()
	{
		return PhantomsConfig.PHANTOMS_ENABLED && PhantomHuntersConfig.HUNTER_CLAN_JOIN && PhantomHuntersConfig.HUNTER_CLAN_RECRUIT;
	}

	private static Wanted role(Player hunter)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		switch (template.getRole())
		{
			case TANK:
			{
				return Wanted.TANK;
			}
			case HEALER:
			{
				return Wanted.HEALER;
			}
			case BUFFER:
			{
				return Wanted.BUFFER;
			}
			default:
			{
				final PlayerClass playerClass = PlayerClass.getPlayerClass(template.getClassId(levelCap()));
				return (playerClass != null) && playerClass.isMage() ? Wanted.MAGE : Wanted.FIGHTER;
			}
		}
	}

	private static boolean matches(Recruit recruit, int level, Wanted role)
	{
		return (level >= recruit.min) && (level <= recruit.max) && ((recruit.wanted == Wanted.ANY) || (recruit.wanted == role));
	}

	/**
	 * @return охотник может подать заявку: свободен, не одиночка, клан без его врагов
	 */
	private static boolean eligible(Player hunter, Clan clan)
	{
		if ((hunter == null) || !hunter.isOnline() || (hunter.getClan() != null) || (hunter.getClanJoinExpiryTime() > System.currentTimeMillis()) || !PhantomManager.getInstance().isPhantom(hunter))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if ((template == null) || !template.isHunter() || template.isReaper() || PhantomManager.getInstance().isSecret(hunter) || PhantomHunterClans.getInstance().isLoner(hunter))
		{
			return false;
		}
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Player member : clan.getOnlineMembers(0))
		{
			if (relations.isEnemy(hunter, member) || relations.isEnemy(member, hunter))
			{
				return false;
			}
		}
		return true;
	}

	private static int room(Clan clan)
	{
		int result = clan.getMaxNrOfMembers(0) - clan.getSubPledgeMembersCount(0);
		if (PhantomHuntersConfig.HUNTER_CLAN_JOIN_MAX > 0)
		{
			result = Math.min(result, PhantomHuntersConfig.HUNTER_CLAN_JOIN_MAX - PhantomClans.hunters(clan));
		}
		return Math.max(0, result);
	}

	private static void prune(Recruit recruit, Clan clan, long now)
	{
		recruit.declined.values().removeIf(until -> until <= now);
		for (Iterator<Applicant> iterator = recruit.applicants.values().iterator(); iterator.hasNext();)
		{
			final Applicant applicant = iterator.next();
			final Player hunter = World.getPlayer(applicant.id());
			if (((now - applicant.created()) >= APPLICATION_TIME) || ((hunter != null) && (!eligible(hunter, clan) || !matches(recruit, hunter.getLevel(), role(hunter)))))
			{
				iterator.remove();
			}
		}
	}

	private int fill(Recruit recruit, Clan clan, Player leader, List<Player> hunters, int limit, long now)
	{
		final Set<Integer> applied = new HashSet<>();
		for (Recruit other : _recruits.values())
		{
			applied.addAll(other.applicants.keySet());
		}
		final List<Player> candidates = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (!applied.contains(hunter.getObjectId()) && !recruit.declined.containsKey(hunter.getObjectId()) && eligible(hunter, clan) && !hunter.isAlikeDead() && !hunter.isInCombat() && !hunter.isInOlympiadMode() && !hunter.isOnEvent() && matches(recruit, hunter.getLevel(), role(hunter)))
			{
				candidates.add(hunter);
			}
		}
		Collections.shuffle(candidates);
		candidates.sort(Comparator.comparingInt(hunter -> PhantomRelations.getInstance().isFriend(hunter, leader) ? 0 : 1));
		int added = 0;
		for (Player hunter : candidates)
		{
			if ((added >= limit) || (recruit.applicants.size() >= PhantomHuntersConfig.HUNTER_CLAN_RECRUIT_MAX))
			{
				break;
			}
			recruit.applicants.put(hunter.getObjectId(), new Applicant(hunter.getObjectId(), hunter.getName(), hunter.getLevel(), PhantomUi.className(hunter), now));
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") applies to clan " + clan.getName() + ".");
			added++;
		}
		return added;
	}

	/**
	 * _bbshubclan [view] | open &lt;от&gt; &lt;до&gt; &lt;роль&gt; | close | pick|accept|reject &lt;id&gt; | acceptpicked. Только свой клан; настройки — глава, заявки — право приглашать.
	 * @return содержимое страницы
	 */
	public String recruit(Player player, String[] args)
	{
		final Clan clan = player.getClan();
		if (clan == null)
		{
			return tr("Чтобы набирать охотников, вступи в клан или создай свой.");
		}
		if (!enabled())
		{
			return recruitOff();
		}
		synchronized (PhantomHunterClans.getInstance())
		{
			final Recruit recruit = _recruits.computeIfAbsent(clan.getId(), Recruit::new);
			final Set<Integer> picked = _picked.computeIfAbsent(player.getObjectId(), id -> new HashSet<>());
			final long now = System.currentTimeMillis();
			prune(recruit, clan, now);
			final boolean leader = player.isClanLeader();
			final boolean recruiter = player.hasAccess(ClanAccess.INVITE_MEMBER);
			final String action = args.length > 1 ? args[1].toLowerCase(Locale.ROOT) : "view";
			String message = "";
			if (action.equals("open") || action.equals("close"))
			{
				message = leader ? settings(recruit, clan, player, args, action, now) : tr("Открывать и настраивать набор может глава клана.");
			}
			else if (!action.equals("view"))
			{
				message = recruiter ? applications(recruit, clan, player, picked, args, action, now) : tr("Для работы с заявками нужно право приглашать в клан.");
			}
			picked.retainAll(recruit.applicants.keySet());
			return recruitPage(recruit, clan, message, leader, recruiter, picked);
		}
	}

	private String settings(Recruit recruit, Clan clan, Player player, String[] args, String action, long now)
	{
		if (action.equals("close"))
		{
			recruit.open = false;
			recruit.save();
			return tr("Набор закрыт. Поданные заявки остаются в списке.");
		}
		final int cap = levelCap();
		final int from = number(args.length > 2 ? args[2] : "0");
		final int to = number(args.length > 3 ? args[3] : "0");
		final Wanted wanted = Wanted.parse(args.length > 4 ? args[4] : "");
		if ((from < 1) || (to > cap) || (from > to) || (wanted == null))
		{
			return tr("Укажи уровни от 1 до ") + cap + tr(": нижний не выше верхнего, и роль.");
		}
		final boolean wasOpen = recruit.open;
		recruit.min = from;
		recruit.max = to;
		recruit.wanted = wanted;
		recruit.open = true;
		recruit.save();
		prune(recruit, clan, now);
		if (!wasOpen && (room(clan) > 0))
		{
			final List<Player> everyone = new ArrayList<>();
			for (Player other : World.getPlayers())
			{
				if (PhantomRelations.isHunter(other))
				{
					everyone.add(other);
				}
			}
			fill(recruit, clan, player, everyone, PhantomHuntersConfig.HUNTER_CLAN_RECRUIT_MAX, now);
		}
		recruit.nextFill = now + FILL_GAP;
		return tr("Набор открыт. Подходящие свободные охотники подают заявки.");
	}

	private String applications(Recruit recruit, Clan clan, Player player, Set<Integer> picked, String[] args, String action, long now)
	{
		final int id = number(args.length > 2 ? args[2] : "0");
		switch (action)
		{
			case "pick":
			{
				if (recruit.applicants.containsKey(id) && !picked.add(id))
				{
					picked.remove(id);
				}
				return "";
			}
			case "reject":
			{
				if (recruit.applicants.remove(id) == null)
				{
					return tr("Заявка уже недоступна.");
				}
				recruit.declined.put(id, now + APPLICATION_TIME);
				return tr("Заявка отклонена. Этот охотник не подаст её снова ещё час.");
			}
			case "accept":
			case "acceptpicked":
			{
				final List<Integer> ids = action.equals("accept") ? List.of(id) : new ArrayList<>(picked);
				if (ids.isEmpty())
				{
					return tr("Сначала отметь охотников кнопкой «Выбрать».");
				}
				int accepted = 0;
				String note = "";
				for (int applicantId : ids)
				{
					if (!recruit.applicants.containsKey(applicantId))
					{
						continue;
					}
					final Player hunter = World.getPlayer(applicantId);
					if ((hunter == null) || !hunter.isOnline())
					{
						note = tr("Кто-то из охотников не в сети, заявка осталась.");
						continue;
					}
					if (!eligible(hunter, clan) || !matches(recruit, hunter.getLevel(), role(hunter)))
					{
						recruit.applicants.remove(applicantId);
						continue;
					}
					if (room(clan) == 0)
					{
						note = tr("В клане больше нет мест.");
						break;
					}
					if (!clan.checkClanJoinCondition(player, hunter, 0))
					{
						note = tr("Вступление не позволяют правила клана, подробность — в системном чате.");
						break;
					}
					PhantomClans.getInstance().joinByApplication(player, hunter);
					if (hunter.getClan() == clan)
					{
						recruit.applicants.remove(applicantId);
						picked.remove(applicantId);
						accepted++;
					}
				}
				return tr("Принято в клан: ") + accepted + ". " + note;
			}
			default:
			{
				return tr("Неизвестное действие.");
			}
		}
	}

	private static String recruitPage(Recruit recruit, Clan clan, String message, boolean leader, boolean recruiter, Set<Integer> picked)
	{
		final int room = room(clan);
		final StringBuilder out = new StringBuilder(10000);
		out.append("<table width=520><tr><td><font color=\"LEVEL\">").append(escape(clan.getName())).append("</font></td><td align=right>").append(clan.getSubPledgeMembersCount(0)).append(" / ").append(clan.getMaxNrOfMembers(0)).append("</td></tr></table><br>");
		out.append(tr("Набор: ")).append("<font color=\"LEVEL\">").append(recruit.open ? room > 0 ? tr("открыт") : tr("приостановлен, нет мест") : tr("закрыт")).append("</font><br>");
		if (leader)
		{
			out.append("<table width=490><tr><td>").append(tr("Уровень от")).append("</td><td>").append(levelCombo("recruitmin", recruit.min)).append("</td><td>").append(tr("до")).append("</td><td>").append(levelCombo("recruitmax", recruit.max)).append("</td><td>").append(tr("Роль")).append("</td><td><combobox var=\"recruitrole\" width=110 list=\"").append(recruit.wanted.label());
			for (Wanted wanted : Wanted.values())
			{
				if (wanted != recruit.wanted)
				{
					out.append(';').append(wanted.label());
				}
			}
			out.append("\"></td></tr></table><br><table><tr><td>").append(button(recruit.open ? tr("Сохранить фильтр") : tr("Открыть набор"), RECRUIT + " open $recruitmin $recruitmax $recruitrole", 170)).append("</td><td>").append(button(tr("Закрыть набор"), RECRUIT + " close", 140)).append("</td></tr></table>");
		}
		else if (!recruiter)
		{
			out.append(tr("Принимать заявки может глава клана или участник с правом приглашать.")).append("<br>");
		}
		if (!message.isEmpty())
		{
			out.append("<br><font color=\"LEVEL\">").append(escape(message)).append("</font><br>");
		}
		out.append("<br>").append(tr("Заявки: ")).append(recruit.applicants.size()).append(" / ").append(PhantomHuntersConfig.HUNTER_CLAN_RECRUIT_MAX).append(tr(". Выбрано: ")).append(picked.size()).append(".<br>");
		if (recruiter)
		{
			out.append("<table><tr><td>").append(button(tr("Принять отмеченных"), RECRUIT + " acceptpicked", 180)).append("</td><td>");
		}
		out.append(button(tr("Обновить список"), RECRUIT + " view", 150));
		if (recruiter)
		{
			out.append("</td></tr></table>");
		}
		out.append("<br><table width=520 bgcolor=\"171717\"><tr><td width=75></td><td width=95>").append(tr("Имя")).append("</td><td width=30>").append(tr("Ур.")).append("</td><td width=145>").append(tr("Профессия")).append("</td><td width=85></td><td width=85></td></tr>");
		for (Applicant applicant : recruit.applicants.values())
		{
			out.append("<tr><td>");
			if (recruiter)
			{
				out.append(button(picked.contains(applicant.id()) ? tr("Выбран") : tr("Выбрать"), RECRUIT + " pick " + applicant.id(), 75));
			}
			out.append("</td><td>").append(escape(applicant.name())).append("</td><td>").append(applicant.level()).append("</td><td>").append(escape(applicant.profession())).append("</td><td>");
			if (recruiter)
			{
				out.append(button(tr("Принять"), RECRUIT + " accept " + applicant.id(), 80));
			}
			out.append("</td><td>");
			if (recruiter)
			{
				out.append(button(tr("Отказать"), RECRUIT + " reject " + applicant.id(), 80));
			}
			out.append("</td></tr>");
		}
		return out.append("</table><br><font color=\"909090\">").append(tr("Заявки подают свободные охотники, пока глава в игре. Заявка действует час; после отказа охотник час не подаёт снова. В клане охотник качается сам.")).append("</font>").toString();
	}

	private static String levelCombo(String variable, int current)
	{
		final StringBuilder list = new StringBuilder(Integer.toString(current));
		for (int value : new int[]
		{
			1,
			20,
			40,
			52,
			61,
			76,
			levelCap()
		})
		{
			if ((value != current) && (value <= levelCap()))
			{
				list.append(';').append(value);
			}
		}
		return "<combobox var=\"" + variable + "\" width=50 list=\"" + list + "\">";
	}

	// ---------- Общее ----------

	private static String button(String label, String action, int width)
	{
		// Широкие кнопки — своей текстурой: в Interlude обычная не растягивается шире родной ширины.
		final boolean wide = width > 90;
		return "<button value=\"" + label + "\" action=\"" + action + "\" width=" + width + " height=25 back=\"" + (wide ? PhantomCompat.WIDE_BUTTON_BACK : PhantomCompat.BUTTON_BACK) + "\" fore=\"" + (wide ? PhantomCompat.WIDE_BUTTON_FORE : PhantomCompat.BUTTON_FORE) + "\">";
	}

	private static String escape(String value)
	{
		return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
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

	/**
	 * @return заголовок страницы войн
	 */
	public static String warsTitle()
	{
		return tr("Кланы и войны");
	}

	/**
	 * @return заголовок страницы набора
	 */
	public static String recruitTitle()
	{
		return tr("Набор в клан");
	}

	private static String recruitOff()
	{
		return tr("Набор охотников выключен в настройках сервера (HunterClanRecruit в PhantomHunters.ini).");
	}

	/**
	 * @return текст, когда доска сейчас недоступна; подробность уже в системном чате
	 */
	public static String busy()
	{
		return tr("Сейчас недоступно, подробность — в системном чате.");
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка CLANBOARD ({@code HuntersSelfTest}, только на копии базы): старший свободный охотник играет живого главу. Открывает набор, принимает одну заявку и
	 * отказывает другой, закрывает. Война: не по условиям — отказ, по условиям — чужой токен отвергнут, с токеном война объявлена с одной стороны. Страницы — в
	 * {@code log/L2HubClanBoard.html}. Кланы распускаются.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		final String tag = getClass().getSimpleName() + ": SelfTest CLANBOARD: ";
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() == null) && !hunter.isDead() && !PhantomHunterClans.getInstance().isLoner(hunter))
			{
				free.add(hunter);
			}
		}
		free.sort(Comparator.comparingInt((Player hunter) -> -hunter.getLevel()));
		if (free.size() < 4)
		{
			LOGGER.warning(tag + "needs four free hunters, found " + free.size() + ".");
			return;
		}
		final Player leader = free.get(0);
		final long started = System.currentTimeMillis();
		final Player founder = free.stream().filter(hunter -> (hunter != leader) && PhantomHunterClans.canFound(hunter, started)).findFirst().orElse(null);
		if (founder == null)
		{
			LOGGER.warning(tag + "no hunter may found a clan among " + free.size() + " free.");
			return;
		}
		String name = PhantomClans.TEST_CLAN;
		for (int i = 2; ClanTable.getInstance().getClanByName(name) != null; i++)
		{
			name = PhantomClans.TEST_CLAN + i;
		}
		final Clan clan = ClanTable.getInstance().createClan(leader, name);
		if (clan == null)
		{
			LOGGER.warning(tag + leader.getName() + " could not create clan " + name + ", stop.");
			return;
		}
		PhantomClans.getInstance().testPlayer(leader);
		final boolean recruitWas = PhantomHuntersConfig.HUNTER_CLAN_RECRUIT;
		final int membersWas = PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR;
		final StringBuilder pages = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>L2Hub: кланы на доске</title><body style=\"background:#1b1b1b;color:#ddd;font:12px Tahoma\">");
		Clan rival = null;
		try
		{
			// Набор.
			final String board = "_bbshubclan";
			LOGGER.info(tag + leader.getName() + " (" + leader.getLevel() + ") plays the clan leader of " + clan.getName() + "; recruiting off: " + recruit(leader, new String[] { board }).equals(recruitOff()) + " (key " + recruitWas + ").");
			PhantomHuntersConfig.HUNTER_CLAN_RECRUIT = true;
			final String badLevels = recruit(leader, new String[] { board, "open", "50", "10", "ANY" });
			LOGGER.info(tag + "levels 50..10: " + (_recruits.get(clan.getId()).open ? "OPENED (unexpected)" : "refused") + ", page " + badLevels.length() + " chars.");
			pages.append(recruit(leader, new String[] { board, "open", "1", String.valueOf(levelCap()), Wanted.ANY.label() }));
			final Recruit recruit = _recruits.get(clan.getId());
			LOGGER.info(tag + "opened: " + recruit.open + ", applications " + recruit.applicants.size() + " of " + PhantomHuntersConfig.HUNTER_CLAN_RECRUIT_MAX + ", stored \"" + GlobalVariablesManager.getInstance().getString(RECRUIT_VARIABLE + clan.getId(), "") + "\".");
			final List<Integer> ids = new ArrayList<>(recruit.applicants.keySet());
			ids.removeIf(id -> id == founder.getObjectId());
			if (ids.size() >= 2)
			{
				final Player accepted = World.getPlayer(ids.get(0));
				final Player rejected = World.getPlayer(ids.get(1));
				recruit(leader, new String[] { board, "pick", String.valueOf(ids.get(0)) });
				recruit(leader, new String[] { board, "acceptpicked" });
				LOGGER.info(tag + "accept " + accepted.getName() + ": in the clan " + (accepted.getClan() == clan) + ", application gone " + !recruit.applicants.containsKey(ids.get(0)) + ", members " + clan.getMembersCount() + ".");
				recruit(leader, new String[] { board, "reject", String.valueOf(ids.get(1)) });
				recruit.nextFill = 0;
				fill(recruit, clan, leader, List.of(rejected), 1, System.currentTimeMillis());
				LOGGER.info(tag + "reject " + rejected.getName() + ": in the clan " + (rejected.getClan() == clan) + ", applies again at once " + recruit.applicants.containsKey(ids.get(1)) + ".");
			}
			pages.append("<hr>").append(recruit(leader, new String[] { board, "close" }));
			LOGGER.info(tag + "closed: " + !recruit.open + ", applications kept " + recruit.applicants.size() + ".");

			// Война.
			final String wars = "_bbshubwars";
			rival = PhantomHunterClans.getInstance().found(founder, PhantomHunterClans.fallbackName(), "self-test");
			if (rival == null)
			{
				LOGGER.warning(tag + founder.getName() + " could not found a clan, the war part is skipped.");
				return;
			}
			clan.changeLevel(Math.max(clan.getLevel(), PhantomClanWars.requiredLevel()));
			rival.changeLevel(Math.max(rival.getLevel(), PhantomClanWars.requiredLevel()));
			pages.append("<hr>").append(wars(leader, new String[] { wars }));
			final String[] confirm = { wars, "confirm", String.valueOf(rival.getId()), "all", "0" };
			PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR = clan.getMembersCount() + 1;
			LOGGER.info(tag + "own clan of " + clan.getMembersCount() + " members with " + PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR + " required: " + (wars(leader, confirm).contains(" declare ") ? "CONFIRMATION (unexpected)" : "refused") + ".");
			PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR = 1;
			wars(leader, new String[] { wars, "declare", String.valueOf(rival.getId()), "all", "0", "wrong" });
			LOGGER.info(tag + "wrong token: at war " + clan.isAtWarWith(rival.getId()) + ".");
			final String page = wars(leader, confirm);
			pages.append("<hr>").append(page);
			final int at = page.indexOf(" declare ");
			final String token = at < 0 ? "" : page.substring(at, page.indexOf('"', at)).trim().split(" ")[4];
			wars(leader, new String[] { wars, "declare", String.valueOf(rival.getId()), "all", "0", token });
			LOGGER.info(tag + "with the token: own clan at war " + clan.isAtWarWith(rival.getId()) + ", one-sided " + PhantomCompat.warDeclaredBy(clan, rival) + "; the rival sees \"" + (wars(founder, new String[] { wars }).contains(tr("Вам объявлена война")) ? "incoming" : "NOTHING") + "\".");
			pages.append("<hr>").append(wars(leader, new String[] { wars, "hunters", "0" }));
			java.nio.file.Files.writeString(java.nio.file.Path.of("./log/L2HubClanBoard.html"), pages.toString());
			LOGGER.info(tag + "pages written to ./log/L2HubClanBoard.html.");
		}
		catch (Exception e)
		{
			LOGGER.log(java.util.logging.Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_CLAN_RECRUIT = recruitWas;
			PlayerConfig.ALT_CLAN_MEMBERS_FOR_WAR = membersWas;
			PhantomClans.getInstance().testPlayer(null);
			_recruits.remove(clan.getId());
			GlobalVariablesManager.getInstance().remove(RECRUIT_VARIABLE + clan.getId());
			if (rival != null)
			{
				PhantomCompat.endWar(clan, rival);
				ClanTable.getInstance().destroyClan(rival.getId());
			}
			ClanTable.getInstance().destroyClan(clan.getId());
			LOGGER.info(tag + "clans dismissed.");
		}
	}

	public static PhantomClanBoard getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClanBoard INSTANCE = new PhantomClanBoard();
	}
}
