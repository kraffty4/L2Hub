package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanAccess;

/** Open recruitment for human clans. Membership still uses the normal Mobius join path. */
public final class PhantomClanRecruitment
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanRecruitment.class.getName());
	static final int MAX_APPLICATIONS = 10;
	static final long FILL_GAP = 60000;
	static final long APPLICATION_TTL = 60 * 60000L;
	static final long DECLINE_GAP = 60 * 60000L;
	private static final Path STATE_FILE = Path.of("data", "ClanRecruitment.properties");

	enum Role
	{
		ANY("Любые"), FIGHTER("Бойцы"), MAGE("Маги"), TANK("Танки"),
		HEALER("Хилеры"), BUFFER("Бафферы"), BARD("БД/СВС");

		final String label;

		Role(String label)
		{
			this.label = label;
		}

		static Role parse(String value)
		{
			for (Role role : values())
			{
				if (role.name().equalsIgnoreCase(value) || role.label.equals(value))
				{
					return role;
				}
			}
			throw new IllegalArgumentException("Неизвестная роль.");
		}
	}

	record Applicant(int id, String name, int level, String profession, Role role, long created)
	{
		String encode()
		{
			return created + "|" + level + "|" + role.name() + "|" + name + "|" + profession;
		}

		static Applicant decode(int id, String value)
		{
			final String[] parts = value.split("\\|", 5);
			if ((parts.length != 5) || (id <= 0))
			{
				throw new IllegalArgumentException("Invalid applicant");
			}
			final int level = Integer.parseInt(parts[1]);
			if ((level < 1) || (level > 85))
			{
				throw new IllegalArgumentException("Invalid applicant level");
			}
			return new Applicant(id, parts[3], level, parts[4], Role.parse(parts[2]), Long.parseLong(parts[0]));
		}
	}

	/** Pure queue/policy state, also used by the offline regression checks. */
	static final class State
	{
		final int clanId;
		boolean open;
		int min = 1;
		int max = 85;
		Role role = Role.ANY;
		long nextFill;
		final Map<Integer, Applicant> pending = new LinkedHashMap<>();
		final Map<Integer, Long> declined = new HashMap<>();

		State(int clanId)
		{
			this.clanId = clanId;
		}

		void configure(int from, int to, Role wanted)
		{
			if ((from < 1) || (to > 85) || (from > to) || (wanted == null))
			{
				throw new IllegalArgumentException("Укажи уровни от 1 до 85: минимум не выше максимума.");
			}
			min = from;
			max = to;
			role = wanted;
		}

		boolean matches(int level, Role actual)
		{
			return (level >= min) && (level <= max) && ((role == Role.ANY) || (role == actual));
		}

		boolean offer(Applicant applicant, long now)
		{
			if (!open || !matches(applicant.level(), applicant.role()) || (pending.size() >= MAX_APPLICATIONS)
				|| pending.containsKey(applicant.id()) || (declined.getOrDefault(applicant.id(), 0L) > now))
			{
				return false;
			}
			pending.put(applicant.id(), applicant);
			return true;
		}

		void decline(int id, long now)
		{
			if (pending.remove(id) != null)
			{
				declined.put(id, now + DECLINE_GAP);
			}
		}

		boolean expire(long now)
		{
			final boolean expired = pending.values().removeIf(a -> (now - a.created()) >= APPLICATION_TTL);
			return declined.values().removeIf(until -> until <= now) || expired;
		}
	}

	private final Map<Integer, State> _states = new LinkedHashMap<>();
	private final Map<Integer, Set<Integer>> _selected = new HashMap<>();
	private final AtomicBoolean _controlPending = new AtomicBoolean();

	private PhantomClanRecruitment()
	{
		if (Files.isRegularFile(STATE_FILE))
		{
			try (Reader reader = Files.newBufferedReader(STATE_FILE, StandardCharsets.UTF_8))
			{
				final Properties properties = new Properties();
				properties.load(reader);
				_states.putAll(decode(properties));
			}
			catch (IOException | IllegalArgumentException e)
			{
				LOGGER.log(Level.WARNING, "Could not restore clan recruitment.", e);
			}
		}
	}

	/** Enqueue after the hunter-clan tick, without nesting its monitor with the recruitment monitor. */
	void schedule(List<Player> hunters, long now)
	{
		if (!_controlPending.compareAndSet(false, true))
		{
			return;
		}
		final List<Player> snapshot = List.copyOf(hunters);
		ThreadPool.execute(() ->
		{
			try
			{
				control(snapshot, now);
			}
			catch (RuntimeException e)
			{
				LOGGER.log(Level.WARNING, "Clan recruitment tick failed.", e);
			}
			finally
			{
				_controlPending.set(false);
			}
		});
	}

	private synchronized void control(List<Player> hunters, long now)
	{
		boolean dirty = false;
		for (Iterator<State> iterator = _states.values().iterator(); iterator.hasNext();)
		{
			final State state = iterator.next();
			final Clan clan = ClanTable.getInstance().getClan(state.clanId);
			if (clan == null)
			{
				iterator.remove();
				dirty = true;
				continue;
			}
			dirty |= prune(state, clan, now);
			final Player leader = clan.getLeader() == null ? null : clan.getLeader().getPlayer();
			if (enabled() && state.open && (room(clan) > 0) && (leader != null) && leader.isOnline()
				&& !PhantomManager.getInstance().isPhantom(leader) && (now >= state.nextFill))
			{
				state.nextFill = now + FILL_GAP;
				final int count = fill(state, clan, leader, hunters, 3, now);
				if (count > 0)
				{
					leader.sendMessage("Набор в клан: новых заявок — " + count + ". Alt+B → Набор в клан.");
				}
				dirty = true;
			}
		}
		_selected.keySet().removeIf(id -> World.getPlayer(id) == null);
		if (dirty)
		{
			save();
		}
	}

	private static boolean enabled()
	{
		return PhantomsConfig.PHANTOMS_ENABLED && PhantomHuntersConfig.HUNTER_CLAN_JOIN;
	}

	static Role classify(PhantomTemplate.Role role, int finalClass, boolean mage)
	{
		if ((finalClass == 21) || (finalClass == 34) || (finalClass == 100) || (finalClass == 107))
		{
			return Role.BARD;
		}
		return switch (role)
		{
			case TANK -> Role.TANK;
			case HEALER -> Role.HEALER;
			case BUFFER -> Role.BUFFER;
			case DAMAGE -> mage ? Role.MAGE : Role.FIGHTER;
		};
	}

	private static Role role(Player hunter)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		final int finalClass = template.getClassId(85);
		final PlayerClass playerClass = PlayerClass.getPlayerClass(finalClass);
		return classify(template.getRole(), finalClass, (playerClass != null) && playerClass.isMage());
	}

	private static boolean eligible(Player hunter, Clan clan)
	{
		if ((hunter == null) || !hunter.isOnline() || (hunter.getClan() != null)
			|| (hunter.getClanJoinExpiryTime() > System.currentTimeMillis())
			|| !PhantomManager.getInstance().isPhantom(hunter))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if ((template == null) || !template.isHunter() || template.isReaper()
			|| PhantomManager.getInstance().isSecret(hunter) || PhantomHunterClans.getInstance().isLoner(hunter))
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

	private boolean prune(State state, Clan clan, long now)
	{
		boolean dirty = state.expire(now);
		for (Iterator<Applicant> iterator = state.pending.values().iterator(); iterator.hasNext();)
		{
			final Applicant applicant = iterator.next();
			final Player hunter = World.getPlayer(applicant.id());
			// Keep offline applications during a server restart; validate again once the hunter logs in.
			if (!state.matches(applicant.level(), applicant.role())
				|| ((hunter != null) && hunter.isOnline() && (!eligible(hunter, clan) || !state.matches(hunter.getLevel(), role(hunter)))))
			{
				iterator.remove();
				dirty = true;
			}
		}
		return dirty;
	}

	private int fill(State state, Clan clan, Player leader, List<Player> hunters, int limit, long now)
	{
		final Set<Integer> applied = new HashSet<>();
		for (State other : _states.values())
		{
			applied.addAll(other.pending.keySet());
		}
		final List<Player> candidates = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (!applied.contains(hunter.getObjectId()) && eligible(hunter, clan)
				&& !hunter.isAlikeDead() && !hunter.isInCombat() && !hunter.isInOlympiadMode()
				&& !hunter.isOnEvent() && state.matches(hunter.getLevel(), role(hunter)))
			{
				candidates.add(hunter);
			}
		}
		Collections.shuffle(candidates);
		candidates.sort(Comparator.comparingInt(h -> PhantomRelations.getInstance().isFriend(h, leader) ? 0 : 1));
		int added = 0;
		for (Player hunter : candidates)
		{
			if (state.offer(new Applicant(hunter.getObjectId(), hunter.getName(), hunter.getLevel(), PhantomUi.className(hunter), role(hunter), now), now))
			{
				added++;
			}
			if ((added >= limit) || (state.pending.size() >= MAX_APPLICATIONS))
			{
				break;
			}
		}
		return added;
	}

	/** Only the viewer's own clan is addressable; settings require its leader, admission requires INVITE_MEMBER. */
	public synchronized String board(Player player, String[] args, String bypass)
	{
		final Clan clan = player.getClan();
		if (clan == null)
		{
			return "Для набора пешек сначала вступи в клан или создай свой.";
		}
		if (!enabled())
		{
			return "Набор пешек отключён в настройках сервера.";
		}
		final State state = _states.computeIfAbsent(clan.getId(), State::new);
		final Set<Integer> selected = _selected.computeIfAbsent(player.getObjectId(), id -> new HashSet<>());
		final long now = System.currentTimeMillis();
		boolean dirty = prune(state, clan, now);
		String message = "";
		final boolean leader = player.isClanLeader();
		final boolean recruiter = player.hasAccess(ClanAccess.INVITE_MEMBER);
		final String action = args.length > 1 ? args[1] : "view";
		try
		{
			if (!action.equals("view"))
			{
				if (!enabled())
				{
					throw new IllegalArgumentException("Набор пешек отключён в настройках сервера.");
				}
				if (action.equals("open") || action.equals("close"))
				{
					if (!leader)
					{
						throw new IllegalArgumentException("Открывать и настраивать набор может глава клана.");
					}
					if (action.equals("open"))
					{
						if (args.length != 5)
						{
							throw new IllegalArgumentException("Укажи минимальный и максимальный уровень и роль.");
						}
						final boolean wasOpen = state.open;
						state.configure(Integer.parseInt(args[2]), Integer.parseInt(args[3]), Role.parse(args[4]));
						state.open = true;
						prune(state, clan, now);
						if (!wasOpen && (room(clan) > 0))
						{
							fill(state, clan, player, new ArrayList<>(World.getPlayers()), MAX_APPLICATIONS, now);
						}
						state.nextFill = now + FILL_GAP;
						message = "Набор открыт. Подходящие свободные пешки подают заявки.";
					}
					else
					{
						state.open = false;
						message = "Набор закрыт. Уже поданные заявки остаются в списке.";
					}
					dirty = true;
				}
				else
				{
					if (!recruiter)
					{
						throw new IllegalArgumentException("Для работы с заявками нужны права приглашать в клан.");
					}
					if (action.equals("pick"))
					{
						final int id = Integer.parseInt(args[2]);
						if (state.pending.containsKey(id) && !selected.add(id))
						{
							selected.remove(id);
						}
					}
					else if (action.equals("reject"))
					{
						final int id = Integer.parseInt(args[2]);
						if (state.pending.containsKey(id))
						{
							state.decline(id, now);
							message = "Заявка отклонена. Эта пешка не подаст её повторно в течение часа.";
							dirty = true;
						}
						else
						{
							message = "Заявка уже недоступна.";
						}
					}
					else if (action.equals("accept") || action.equals("acceptselected"))
					{
						final List<Integer> ids = action.equals("accept") ? List.of(Integer.parseInt(args[2])) : selectedApplicants(state, selected);
						int accepted = 0;
						for (int id : ids)
						{
							if (!state.pending.containsKey(id))
							{
								continue;
							}
							final Player hunter = World.getPlayer(id);
							// The same lock is used by autonomous clan admission and direct phantom invitations.
							synchronized (PhantomHunterClans.getInstance())
							{
								if ((hunter == null) || !hunter.isOnline())
								{
									message = "Часть пешек пока не в сети. Их заявки сохранены.";
									continue;
								}
								if (!eligible(hunter, clan) || !state.matches(hunter.getLevel(), role(hunter)))
								{
									state.pending.remove(id);
									dirty = true;
									continue;
								}
								if (room(clan) == 0)
								{
									message = "Достигнут лимит участников клана или пешек.";
									break;
								}
								if (!clan.checkClanJoinCondition(player, hunter, 0))
								{
									message = "Вступление ограничено правилами клана. Подробность — в системном чате.";
									break;
								}
								PhantomClans.getInstance().onInvited(player, hunter, 0);
								if (hunter.getClan() == clan)
								{
									state.pending.remove(id);
									selected.remove(id);
									accepted++;
									dirty = true;
								}
							}
						}
						message = (ids.isEmpty() ? "Сначала отметь пешек кнопками «Выбрать»." : "Принято в клан: " + accepted + ". ") + message;
					}
					else
					{
						throw new IllegalArgumentException("Неизвестное действие.");
					}
				}
			}
		}
		catch (IllegalArgumentException | IndexOutOfBoundsException e)
		{
			message = e instanceof NumberFormatException || e instanceof IndexOutOfBoundsException ? "Некорректные параметры заявки." : e.getMessage();
		}
		selected.retainAll(state.pending.keySet());
		if (dirty && !save())
		{
			message += " Не удалось сохранить набор на диск; проверь журнал сервера.";
		}
		return render(state, clan.getName(), clan.getSubPledgeMembersCount(0), clan.getMaxNrOfMembers(0), room(clan),
			message, leader, recruiter, selected, bypass);
	}

	static List<Integer> selectedApplicants(State state, Set<Integer> selected)
	{
		return state.pending.keySet().stream().filter(selected::contains).toList();
	}

	static String render(State state, String clanName, int members, int maximum, int room, String message,
		boolean leader, boolean recruiter, Set<Integer> selected, String bypass)
	{
		final StringBuilder out = new StringBuilder(10000);
		out.append("<table width=520><tr><td><font color=\"LEVEL\">").append(escape(clanName)).append("</font></td><td align=right>")
			.append(members).append(" / ").append(maximum).append("</td></tr></table><br>");
		out.append("Набор: <font color=\"LEVEL\">").append(state.open ? room > 0 ? "открыт" : "приостановлен — нет мест" : "закрыт").append("</font><br>");
		if (leader)
		{
			out.append("<table width=490><tr><td>Уровень от</td><td>").append(levelCombo("recruitmin", state.min)).append("</td><td>до</td><td>")
				.append(levelCombo("recruitmax", state.max)).append("</td><td>Роль</td><td><combobox var=\"recruitrole\" width=110 list=\"")
				.append(state.role.label);
			for (Role role : Role.values())
			{
				if (role != state.role)
				{
					out.append(';').append(role.label);
				}
			}
			out.append("\"></td></tr></table><br><table><tr><td>")
				.append(button(state.open ? "Сохранить фильтр" : "Открыть набор", bypass + " open $recruitmin $recruitmax $recruitrole", 170))
				.append("</td><td>").append(button("Закрыть набор", bypass + " close", 140)).append("</td></tr></table>");
		}
		else if (!recruiter)
		{
			out.append("Принимать заявки может глава клана или участник с правом приглашения.<br>");
		}
		if (!message.isEmpty())
		{
			out.append("<br><font color=\"LEVEL\">").append(escape(message)).append("</font><br>");
		}
		out.append("<br>Заявки: ").append(state.pending.size()).append(" / ").append(MAX_APPLICATIONS)
			.append(". Выбрано: ").append(selected.size()).append(".<br>");
		if (recruiter)
		{
			out.append("<table><tr><td>").append(button("Принять отмеченных", bypass + " acceptselected", 180)).append("</td><td>");
		}
		out.append(button("Обновить список", bypass + " view", 150));
		if (recruiter)
		{
			out.append("</td></tr></table>");
		}
		out.append("<br><table width=520 bgcolor=\"171717\"><tr><td width=75></td><td width=95>Имя</td><td width=30>Ур.</td><td width=145>Профессия</td><td width=85></td><td width=85></td></tr>");
		for (Applicant applicant : state.pending.values())
		{
			out.append("<tr><td>");
			if (recruiter)
			{
				out.append(button(selected.contains(applicant.id()) ? "Выбран" : "Выбрать", bypass + " pick " + applicant.id(), 75));
			}
			out.append("</td><td>").append(escape(applicant.name())).append("</td><td>").append(applicant.level()).append("</td><td>")
				.append(escape(applicant.profession())).append("</td><td>");
			if (recruiter)
			{
				out.append(button("Принять", bypass + " accept " + applicant.id(), 80));
			}
			out.append("</td><td>");
			if (recruiter)
			{
				out.append(button("Отказать", bypass + " reject " + applicant.id(), 80));
			}
			out.append("</td></tr>");
		}
		out.append("</table><br><font color=\"909090\">Заявки подают свободные пешки. Дружба и совместная прокачка не требуются.<br>")
			.append("До 10 заявок, пополнение — до 3 в минуту, пока глава в игре.<br>Заявка действует час; после отказа — час до повторной.<br>")
			.append("После вступления пешки продолжают качаться самостоятельно.</font>");
		return out.toString();
	}

	private static String levelCombo(String variable, int current)
	{
		final StringBuilder list = new StringBuilder(Integer.toString(current));
		for (int value : new int[]{1, 20, 40, 52, 61, 76, 80, 85})
		{
			if (value != current)
			{
				list.append(';').append(value);
			}
		}
		return "<combobox var=\"" + variable + "\" width=50 list=\"" + list + "\">";
	}

	private static String button(String label, String action, int width)
	{
		return "<button value=\"" + label + "\" action=\"" + action + "\" width=" + width
			+ " height=23 back=\"L2UI_CT1.Button_DF_Down\" fore=\"L2UI_CT1.Button_DF\">";
	}

	private static String escape(String value)
	{
		return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
	}

	static Properties encode(Map<Integer, State> states)
	{
		final Properties properties = new Properties();
		for (State state : states.values())
		{
			final String key = state.clanId + ".";
			properties.setProperty(key + "settings", state.open + "," + state.min + "," + state.max + "," + state.role.name() + "," + state.nextFill);
			state.pending.forEach((id, applicant) -> properties.setProperty(key + "application." + id, applicant.encode()));
			state.declined.forEach((id, until) -> properties.setProperty(key + "declined." + id, until.toString()));
		}
		return properties;
	}

	static Map<Integer, State> decode(Properties properties)
	{
		final Map<Integer, State> states = new LinkedHashMap<>();
		for (String key : properties.stringPropertyNames())
		{
			if (!key.endsWith(".settings"))
			{
				continue;
			}
			try
			{
				final int id = Integer.parseInt(key.substring(0, key.indexOf('.')));
				if (id <= 0)
				{
					continue;
				}
				final String[] fields = properties.getProperty(key).split(",", -1);
				if ((fields.length != 5) || (!fields[0].equals("true") && !fields[0].equals("false")))
				{
					throw new IllegalArgumentException("Invalid recruitment settings");
				}
				final State state = new State(id);
				state.configure(Integer.parseInt(fields[1]), Integer.parseInt(fields[2]), Role.parse(fields[3]));
				state.open = Boolean.parseBoolean(fields[0]);
				state.nextFill = Long.parseLong(fields[4]);
				for (String other : properties.stringPropertyNames())
				{
					try
					{
						if (other.startsWith(id + ".application.") && (state.pending.size() < MAX_APPLICATIONS))
						{
							final int hunterId = Integer.parseInt(other.substring(other.lastIndexOf('.') + 1));
							state.pending.put(hunterId, Applicant.decode(hunterId, properties.getProperty(other)));
						}
						else if (other.startsWith(id + ".declined."))
						{
							state.declined.put(Integer.parseInt(other.substring(other.lastIndexOf('.') + 1)), Long.parseLong(properties.getProperty(other)));
						}
					}
					catch (IllegalArgumentException e)
					{
						LOGGER.warning("Skipped invalid recruitment entry " + other);
					}
				}
				states.put(id, state);
			}
			catch (IllegalArgumentException e)
			{
				LOGGER.warning("Skipped invalid recruitment settings " + key);
			}
		}
		return states;
	}

	private boolean save()
	{
		final Path temp = STATE_FILE.resolveSibling(STATE_FILE.getFileName() + ".tmp");
		try
		{
			Files.createDirectories(STATE_FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8))
			{
				encode(_states).store(writer, "L2Hub clan recruitment - runtime state, preserve on updates");
			}
			try
			{
				Files.move(temp, STATE_FILE, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			}
			catch (AtomicMoveNotSupportedException e)
			{
				Files.move(temp, STATE_FILE, StandardCopyOption.REPLACE_EXISTING);
			}
			return true;
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, "Could not save clan recruitment.", e);
			return false;
		}
	}

	public static PhantomClanRecruitment getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		static final PhantomClanRecruitment INSTANCE = new PhantomClanRecruitment();
	}
}

