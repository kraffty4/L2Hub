package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.groups.PartyMessageType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomData;
import org.l2jmobius.gameserver.managers.phantom.PhantomEvents;
import org.l2jmobius.gameserver.managers.phantom.PhantomOlympiad;
import org.l2jmobius.gameserver.managers.phantom.PhantomRaid;
import org.l2jmobius.gameserver.managers.phantom.PhantomTemplate;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageDealt;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDeath;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.stats.Stat;
import org.l2jmobius.gameserver.mechanics.stats.functions.FuncMul;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Team;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;
import org.l2jmobius.gameserver.network.serverpackets.StatusUpdate;
import org.l2jmobius.gameserver.managers.world.X10EventRules.Kind;

/** Общий жизненный цикл трёх форматов x10: запись, копии, группы, активность, награды и возврат. */
final class X10FarmEvent
{
	private static final Logger LOGGER = Logger.getLogger(X10FarmEvent.class.getName());
	private static final Location CENTER = new Location(149465, 46714, -3414);
	// Штатная точка H5 Front Of Giran Castle из chamberlain/35184.xml.
	private static final Location GATE = new Location(107954, 145841, -3224);
	private static final String RETURN = "L2HubX10Return";
	private static final Map<Integer, Player> QUEUE = new ConcurrentHashMap<>();
	private static final Map<Integer, Member> MEMBERS = new ConcurrentHashMap<>();
	private static final Map<Integer, Room> MOBS = new ConcurrentHashMap<>();
	private static final Map<Integer, Double> HEALTH = new ConcurrentHashMap<>();
	private static final List<Room> ROOMS = new ArrayList<>();
	private static volatile long nextStart;
	private static long started;
	private static long sequence;
	private static volatile boolean running;
	private static long announced;
	private static Kind nextKind;
	private static Kind kind = Kind.FARM;
	private static X10EventSchedule schedule;
	private static boolean testStart;
	private static final String SCHEDULE = "L2HubX10EventSchedule";

	private static L2HubX10Config.EventOptions options(Kind event)
	{
		return switch (event)
		{
			case FARM -> L2HubX10Config.FARM_EVENT;
			case DEFENCE -> L2HubX10Config.DEFENCE_EVENT;
			case HUNT -> L2HubX10Config.HUNT_EVENT;
		};
	}

	private static long duration() { return options(kind).minutes() * 60000L; }
	private static long registration() { return L2HubX10Config.REGISTRATION * 60000L; }
	private static void saveSchedule()
	{
		GlobalVariablesManager.getInstance().set(SCHEDULE, schedule.encode());
		X10World.saveClock();
	}

	private static final class Member
	{
		Player player;
		final Room room;
		final X10EventProgress score = new X10EventProgress();
		boolean joinedByEvent;
		Object joinedParty;
		long respawnAt;
		int squad;
		final Map<Integer, Set<Integer>> buffs = new java.util.HashMap<>();

		Member(Player player, Room room)
		{
			this.player = player;
			this.room = room;
		}
	}

	private static final class Room
	{
		final Instance instance;
		final int level;
		final List<Member> players = new ArrayList<>();
		final List<Spawn> spawns = new ArrayList<>();
		final Location center;
		Npc headquarters;
		double integrity = 1;
		int wave;
		long nextWave;
		Member quarry;
		final X10EventRules.QuarryBonus slain = new X10EventRules.QuarryBonus();
		final Set<Integer> winningSquad = new HashSet<>();
		boolean quarryKilled;
		long finishedElapsed;
		long quarryMissingSince;
		long nextEscape;
		boolean closed;

		Room(int level)
		{
			this.level = level;
			center = kind == Kind.DEFENCE ? GATE : CENTER;
			instance = InstanceManager.getInstance().createDynamicInstance(0);
			instance.setPvP(false);
			instance.setAllowSummon(false);
			instance.setEmptyDestroyTime(120000);
			instance.setDuration((options(kind).minutes() + 5) * 60000);
		}
	}

	static void start()
	{
		for (int level : new int[] {1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85})
		{
			if (NpcData.getInstance().getTemplate(900500 + level) == null)
			{
				throw new IllegalStateException("Missing X10 event NPC data for level " + level);
			}
		}
		if (NpcData.getInstance().getTemplate(900600) == null)
		{
			throw new IllegalStateException("Missing X10 headquarters template");
		}
		final Map<Kind, X10EventSchedule.Spec> specs = new java.util.EnumMap<>(Kind.class);
		for (Kind event : Kind.values())
		{
			final var rule = options(event);
			specs.put(event, new X10EventSchedule.Spec(L2HubX10Config.EVENTS_ENABLED && rule.enabled(), rule.intervalMinutes(), rule.firstDelayMinutes(), rule.minutes()));
		}
		schedule = new X10EventSchedule(specs, L2HubX10Config.START_GAP, L2HubX10Config.REGISTRATION, X10World.activeSeconds(), GlobalVariablesManager.getInstance().getString(SCHEDULE, ""));
		saveSchedule();
		scheduleNext();
		PhantomCompat.addAttackableKillListener(X10FarmEvent.class, X10FarmEvent::kill);
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DAMAGE_DEALT, (OnCreatureDamageDealt event) -> damage(event), X10FarmEvent.class));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_SKILL_USE, (OnCreatureSkillUse event) -> support(event), X10FarmEvent.class));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_DEATH, (OnCreatureDeath event) -> death(event), X10FarmEvent.class));
		ThreadPool.scheduleAtFixedRate(X10FarmEvent::tickSafe, 1000, 1000);
	}

	static synchronized String action(Player player, String action)
	{
		return action(player, action, Kind.FARM);
	}

	static synchronized String action(Player player, String action, Kind requested)
	{
		if (action.equals("cancel"))
		{
			if (QUEUE.remove(player.getObjectId()) != null)
			{
				player.setRegisteredOnEvent(false);
			}
			return text("Запись отменена.", "Sign-up cancelled.");
		}
		if (action.equals("test") && player.isGM())
		{
			if ((!L2HubX10Config.EVENTS_ENABLED || !options(requested).enabled())) { return text("Этот ивент выключен в настройках Хаба.", "This event is disabled in Hub settings."); }
			if (running) { return text("Сначала дождись конца текущего боя или отмени его.", "Wait for the current fight to finish or cancel it first."); }
			if (!QUEUE.isEmpty() && nextKind != requested) { return text("Сначала отмени записи на другой формат.", "Cancel registrations for the other format first."); }
			if (!running)
			{
				nextStart = System.currentTimeMillis() + 10000;
				nextKind = requested;
				testStart = true;
			}
			return text("Тестовый старт через 10 секунд.", "Test starts in 10 seconds.");
		}
		if (action.equals("stop") && player.isGM())
		{
			finish(false);
			return text("Тестовое событие отменено без наград.", "Test event cancelled without rewards.");
		}
		if (action.equals("joinparty"))
		{
			if (!player.isInParty() || !player.getParty().isLeader(player)) { return text("Записать пати может её лидер.", "Only the party leader can sign up the party."); }
			if (running || nextKind != requested || nextStart - System.currentTimeMillis() > registration() || !L2HubX10Config.EVENTS_ENABLED || !options(requested).enabled()) { return text("Запись на этот формат пока закрыта.", "Sign-up for this format is currently closed."); }
			final List<Player> people = player.getParty().getMembers().stream().filter(p -> !PhantomManager.getInstance().isPhantom(p)).toList();
			for (Player member : people)
			{
				if (!member.isOnline() || (!QUEUE.containsKey(member.getObjectId()) && !X10World.teleportError(member).isEmpty())) { return text("Не удалось записать пати: участник занят или не в игре: ", "Cannot sign up the party: member busy or offline: ") + member.getName(); }
			}
			for (Player member : people) { QUEUE.put(member.getObjectId(), member); member.setRegisteredOnEvent(true); member.sendMessage(text("Лидер записал вашу пати на ", "Your leader signed up the party for ") + name(requested) + text(". ТП при старте; отмена в Alt+B → Ивенты.", ". Teleport at start; cancel in Alt+B → Events.")); }
			return text("Пати записана вместе. Напарники в вашей пати тоже перейдут на площадку.", "Your party is signed up together. Companions in your party will enter too.");
		}
		if (!action.equals("join"))
		{
			return text("Неизвестное действие.", "Unknown action.");
		}
		if ((!L2HubX10Config.EVENTS_ENABLED || !options(requested).enabled())) { return text("Этот ивент выключен в настройках Хаба.", "This event is disabled in Hub settings."); }
		if (running || (nextKind != requested) || (nextStart - System.currentTimeMillis() > registration()))
		{
			final String early = text("Ещё рано: запись на «", "Too early: sign-up for ") + name(requested) + text("» откроется за ", " opens ") + L2HubX10Config.REGISTRATION + text(" мин до старта (", " min before the start (") + startTime(requested) + text("). Дождись объявления.", "). Wait for the announcement.");
			X10Text.warn(player, early);
			return early;
		}
		if (QUEUE.containsKey(player.getObjectId()))
		{
			QUEUE.put(player.getObjectId(), player);
			player.setRegisteredOnEvent(true);
			return text("Ты уже записан.", "You are already signed up.");
		}
		final String blocked = X10World.teleportError(player);
		if (!blocked.isEmpty())
		{
			return blocked;
		}
		QUEUE.put(player.getObjectId(), player);
		player.setRegisteredOnEvent(true);
		return text("Запись принята. На старте перенесёт на площадку события. ТП бесплатный.", "Signed up. You will be teleported to the event at the start. Teleport is free.");
	}

	private static void scheduleNext()
	{
		final long seconds = X10World.activeSeconds();
		final var slot = schedule.next(seconds);
		nextStart = slot == null ? Long.MAX_VALUE : System.currentTimeMillis() + (slot.seconds() - seconds) * 1000;
		nextKind = slot == null ? null : slot.kind();
		testStart = false;
	}

	private static String name(Kind event)
	{
		return switch (event)
		{
			case DEFENCE -> text("Оборона Гирана", "Giran defence");
			case HUNT -> text("Охота на сильнейшего", "Hunt the strongest");
			default -> text("Массовая охота", "Monster rush");
		};
	}

	static synchronized String html(Player player, String prefix)
	{
		final StringBuilder out = new StringBuilder();
		for (Kind event : Kind.values())
		{
			final String command = prefix + " " + event.name().toLowerCase(java.util.Locale.ROOT);
			final boolean open = L2HubX10Config.EVENTS_ENABLED && options(event).enabled() && !running && nextKind == event && nextStart - System.currentTimeMillis() <= registration();
			out.append("<table width=520 cellpadding=4><tr><td width=350><font color=\"LEVEL\">").append(name(event)).append("</font><br1>")
				.append(text("Старт ", "Starts ")).append(startTime(event)).append(" · ").append(options(event).minutes()).append(text(" мин · ", " min · ")).append(options(event).coins()).append(text(" коинов", " coins")).append("<br1>")
				.append(open ? text("ЗАПИСЬ ОТКРЫТА — ТП при старте", "SIGN-UP OPEN — teleport at start") : running && kind == event ? text("Сейчас идёт бой", "Fight in progress") : text("Запись за ", "Sign-up opens ") + L2HubX10Config.REGISTRATION + text(" мин до старта", " min before start"))
				.append("</td><td width=150 valign=top>").append(QUEUE.containsKey(player.getObjectId()) && nextKind == event ? X10Text.button(text("Отменить запись", "Cancel sign-up"), command + " cancel", 140) : X10Text.button(text("Записаться", "Sign up"), command + " join", 140)).append("<br1>").append(X10Text.button(text("Подробнее", "Details"), command, 140)).append("</td></tr></table><br>");
		}
		return out.toString();
	}

	static synchronized String html(Player player, String prefix, Kind requested)
	{
		final var rules = options(requested);
		final String command = prefix + " " + requested.name().toLowerCase(java.util.Locale.ROOT);
		final StringBuilder out = new StringBuilder("<font color=\"LEVEL\">").append(name(requested)).append("</font><br1>");
		if (requested == Kind.FARM)
		{
			out.append(text("Отдельные арены по уровню. Участников на копию: ", "Private level-matched arenas. Participants per copy: ")).append(L2HubX10Config.FARM_ROOM).append(text(". Мобов: ", ". Monsters: ")).append(L2HubX10Config.FARM_MOBS).append(text("; возрождение через ", "; respawn after ")).append(L2HubX10Config.FARM_RESPAWN).append(text(" сек. Убийства и урон — для статистики; полезное участие даёт одинаковую базовую награду.", " sec. Kills and damage are statistics; useful participation earns the same base reward.")).append("<br1>");
		}
		else if (requested == Kind.DEFENCE)
		{
			out.append(text("Отдельная копия входа в замок Гирана. Защищай Штаб. Волн: ", "Private copy of the Giran castle entrance. Defend Headquarters. Waves: ")).append(L2HubX10Config.DEFENCE_WAVES).append(text(". Уровень наступающих по накопленному времени мира: ", ". Invader level based on accumulated world uptime: ")).append(X10EventRules.invasionLevel(X10World.activeSeconds())).append(".<br1>");
			out.append(text("Монеты зависят от прочности Штаба; минимальная доля базовой награды: ", "Coins depend on Headquarters integrity; minimum share of the base reward: ")).append(L2HubX10Config.DEFENCE_MIN_REWARD).append(text("%. Раннее разрушение уменьшает монеты пропорционально длительности.", "%. Early destruction reduces coins in proportion to duration.")).append("<br1>");
			if (running && kind == requested && !ROOMS.isEmpty()) { out.append(text("Прочность Штаба: ", "Headquarters integrity: ")).append(Math.round(ROOMS.getFirst().integrity * 100)).append("%<br1>"); }
		}
		else
		{
			out.append(text("Цель — самый сильный свободный охотник-бот. На время боя его HP умножается на ", "The target is the strongest available hunter bot. Its HP multiplier during this fight is ")).append(L2HubX10Config.HUNT_HP).append(text(". Преследователи на одной стороне, можно играть в пати и помогать саппортом. Бой заканчивается по таймеру либо после смерти цели.", ". Pursuers share a side and may group up or play support. The fight ends when time runs out or the target dies.")).append("<br1>");
			out.append(text("Ранний конец уменьшает базовые монеты пропорционально времени. Бонус активным участникам группы, убившей цель: ", "An early end reduces base coins in proportion to duration. Bonus for active members of the group that killed the target: ")).append(L2HubX10Config.HUNT_GROUP_BONUS).append(text(". Бонус цели за уникального активного преследователя: ", ". Target bonus per unique active pursuer killed: ")).append(L2HubX10Config.HUNT_KILL_BONUS).append(text(", суммарно не более ", ", capped at ")).append(L2HubX10Config.HUNT_BONUS_CAP).append(text(". Последнего удара без полезного участия недостаточно.", ". A last hit without useful participation is insufficient.")).append("<br1>");
			if (running && kind == requested && !ROOMS.isEmpty() && ROOMS.getFirst().quarry != null)
			{
				final Player target = ROOMS.getFirst().quarry.player;
				out.append(text("Цель: ", "Target: ")).append(X10Text.escape(target.getName())).append(" (Lv ").append(target.getLevel()).append("). HP: ").append(Math.round(target.getCurrentHp() * 100.0 / Math.max(1, target.getMaxHp()))).append("%<br1>");
			}
		}
		out.append(text("Длительность, мин: ", "Duration, min: ")).append(rules.minutes()).append(text(". Базовые монеты: ", ". Base coins: ")).append(rules.coins()).append(text(". Жетоны стиля за полный бой: ", ". Style Tokens for a full fight: ")).append(rules.style()).append(".<br1>");
		out.append(text("Полезная активность минимум в ", "Useful activity during at least ")).append(L2HubX10Config.ACTIVITY).append(text("% прошедшего боя. Учитываются урон, полезное лечение и бафы. Обычного опыта и дропа нет; ТП при старте бесплатный, после боя вернёшься обратно.", "% of the elapsed fight. Damage, useful healing and buffs count. No ordinary XP or loot; free teleport at the start and return afterwards.")).append("<br1>");
		out.append(text("Период формата, мин: ", "Format interval, min: ")).append(rules.intervalMinutes()).append(text(". Минимум между стартами всех ивентов, мин: ", ". Minimum gap between all event starts, min: ")).append(L2HubX10Config.START_GAP).append(text(". Запись открывается за ", ". Sign-up opens ")).append(L2HubX10Config.REGISTRATION).append(text(" мин. При совпадении сроков события идут по очереди.", " min before the start. Coinciding events are queued.")).append("<br1>");
		out.append(text("Ближайший старт: ", "Next start: ")).append(startTime(requested)).append("<br1>");
		if (running && kind == requested) { out.append(text("Сейчас идёт бой.", "Fight in progress.")).append("<br1>"); }
		else if (nextKind == requested) { out.append(text("Записано на ближайшее событие: ", "Signed up for the next event: ")).append(QUEUE.size()).append("<br1>"); }
		if (QUEUE.containsKey(player.getObjectId())) { out.append(X10Text.button(text("Отменить", "Cancel"), command + " cancel", 130)); }
		else if (L2HubX10Config.EVENTS_ENABLED && rules.enabled() && !running && nextKind == requested && nextStart - System.currentTimeMillis() <= registration()) { out.append(X10Text.button(text("Записаться / ТП", "Sign up / teleport"), command + " join", 170)); }
		else if (!running) { out.append(X10Text.button(text("Записаться", "Sign up"), command + " join", 170)).append("<br1><font color=\"AFAFAF\">").append(text("Запись откроется за ", "Sign-up opens ")).append(L2HubX10Config.REGISTRATION).append(text(" мин до старта.", " min before the start.")).append("</font><br1>"); }
		if (player.isInParty() && player.getParty().isLeader(player) && !running && nextKind == requested && nextStart - System.currentTimeMillis() <= registration()) { out.append("<br1>").append(X10Text.button(text("Записать пати", "Sign up party"), command + " joinparty", 170)); }
		final Member participant = MEMBERS.get(player.getObjectId());
		if (running && kind == requested && participant != null)
		{
			out.append("<br><table width=500><tr><td># / ").append(text("Игрок", "Player")).append("</td><td>").append(text("Убито", "Kills")).append("</td><td>").append(text("Урон", "Damage")).append("</td></tr>");
			int rank = 0;
			for (Member member : participant.room.players.stream().sorted(Comparator.comparingInt((Member m) -> m.score.kills).thenComparingDouble(m -> m.score.damage).reversed()).toList())
			{
				out.append("<tr><td>").append(++rank).append(". ").append(X10Text.escape(member.player.getName())).append("</td><td>").append(member.score.kills).append("</td><td>").append(Math.round(member.score.damage)).append("</td></tr>");
			}
			out.append("</table>");
		}
		if (player.isGM())
		{
			if (L2HubX10Config.EVENTS_ENABLED && rules.enabled()) { out.append("<br1>").append(X10Text.button(text("GM: тест", "GM: test"), command + " test", 120)); }
			out.append(X10Text.button(text("GM: отменить", "GM: cancel"), command + " stop", 120));
		}
		return out.toString();
	}

	private static String startTime(Kind requested)
	{
		if ((!L2HubX10Config.EVENTS_ENABLED || !options(requested).enabled())) { return text("выключен", "disabled"); }
		final long seconds = X10World.activeSeconds();
		final X10EventSchedule forecast = testStart ? schedule.afterTest(nextKind, seconds + Math.max(0, (nextStart - System.currentTimeMillis() + 999) / 1000)) : schedule;
		final long planned = forecast.nextFor(requested, seconds);
		if (planned < 0) { return text("ожидает расписания", "awaiting schedule"); }
		final long time = nextKind == requested ? nextStart : System.currentTimeMillis() + (planned - seconds) * 1000;
		return DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(time));
	}

	private static void tickSafe()
	{
		try
		{
			tick();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "X10 farm event failed; returning participants", e);
			finish(false);
		}
	}

	private static synchronized void tick() throws Exception
	{
		final long now = System.currentTimeMillis();
		if (!running)
		{
			if (nextKind == null) { return; }
			if ((nextStart - now <= registration()) && (announced != nextStart))
			{
				announced = nextStart;
				final String message = name(nextKind) + text(": запись открыта на ", ": sign-up is open for ") + L2HubX10Config.REGISTRATION + text(" мин. Alt+B → Ивенты → Записаться.", " min. Alt+B → Events → Sign up.");
				for (Player online : World.getPlayers())
				{
					if (online.isOnline() && !PhantomManager.getInstance().isPhantom(online))
					{
						online.sendPacket(new org.l2jmobius.gameserver.network.serverpackets.CreatureSay(null, org.l2jmobius.gameserver.network.enums.ChatType.ANNOUNCEMENT, "", message));
						online.sendPacket(new ExShowScreenMessage(message, 8000));
					}
				}
			}
			if (now >= nextStart)
			{
				kind = nextKind;
				schedule.start(kind, X10World.activeSeconds(), testStart);
				saveSchedule();
				scheduleNext();
				begin();
				if (!running)
				{
					schedule.finish(X10World.activeSeconds());
					saveSchedule();
					scheduleNext();
				}
			}
			return;
		}
		if (now - started >= duration())
		{
			finish(true);
			return;
		}
		if (kind == Kind.DEFENCE)
		{
			for (Room room : ROOMS) { defenceTick(room, now); }
			if (ROOMS.stream().anyMatch(room -> room.integrity <= 0)) { finish(true); return; }
		}
		if (kind == Kind.HUNT)
		{
			final Room room = ROOMS.getFirst();
			if (room.quarryKilled) { finish(true); return; }
			if (!room.quarry.player.isOnline() || room.quarry.player.getInstanceId() != room.instance.getId())
			{
				if (room.quarryMissingSince == 0) { room.quarryMissingSince = now; }
				if (now - room.quarryMissingSince > 30000) { finish(false); return; }
			}
			else { room.quarryMissingSince = 0; }
		}
		for (Member member : MEMBERS.values())
		{
			final Player current = World.getPlayer(member.player.getObjectId());
			if (current != null && current != member.player && current.isOnline()) { recover(current); }
			final Player player = member.player;
			if (!player.isOnline())
			{
				continue;
			}
			if (player.getInstanceId() != member.room.instance.getId())
			{
				player.abortAttack();
				player.abortCast();
				player.teleToLocation(member.room.center, member.room.instance.getId(), 80);
				continue;
			}
			if (player.isDead())
			{
				if (member.respawnAt == 0)
				{
					member.respawnAt = now + L2HubX10Config.PLAYER_RESPAWN * 1000L;
				}
				else if (now >= member.respawnAt)
				{
					player.doRevive();
					player.fullRestore();
					player.teleToLocation(member.room.center, member.room.instance.getId(), 80);
					member.respawnAt = 0;
				}
				continue;
			}
			if ((player.calculateDistance3D(member.room.center) > 1800) || (Math.abs(player.getZ() - member.room.center.getZ()) > 400))
			{
				player.teleToLocation(member.room.center, member.room.instance.getId(), 80);
			}
			if (supporting(member, now))
			{
				member.score.active(now - started);
			}
			if (now / 15000 != (now - 1000) / 15000)
			{
				player.sendPacket(new ExShowScreenMessage(name(kind) + ": " + timeLeft(duration() - (now - started)) + text(" мин. Убийств: ", " min. Kills: ") + member.score.kills + text(". Урон: ", ". Damage: ") + Math.round(member.score.damage)
					+ (kind == Kind.DEFENCE ? text(". Штаб: ", ". HQ: ") + Math.round(member.room.integrity * 100) + "%" : "") + "\n" + leaders(member.room), ExShowScreenMessage.TOP_RIGHT, 15000, 1, false, false));
			}
		}
	}

	private static String timeLeft(long millis)
	{
		final long seconds = Math.max(0, (millis + 999) / 1000);
		return (seconds / 60) + ":" + String.format(java.util.Locale.ROOT, "%02d", seconds % 60);
	}

	private static String leaders(Room room)
	{
		final StringBuilder out = new StringBuilder(text("Лидеры: ", "Leaders: "));
		int rank = 0;
		for (Member member : room.players.stream().sorted(Comparator.comparingInt((Member m) -> m.score.kills).thenComparingDouble(m -> m.score.damage).reversed()).limit(3).toList())
		{
			if (rank++ > 0) { out.append(" | "); }
			out.append(rank).append(". ").append(member.player.getName()).append(' ').append(member.score.kills).append('/').append(Math.round(member.score.damage));
		}
		return out.toString();
	}

	private static void begin() throws Exception
	{
		final List<Player> humans = new ArrayList<>(QUEUE.values());
		QUEUE.clear();
		for (Player player : humans)
		{
			player.setRegisteredOnEvent(false);
		}
		humans.removeIf(player ->
		{
			final String blocked = X10World.teleportError(player);
			if (!player.isOnline() || PhantomManager.getInstance().isPhantom(player) || !blocked.isEmpty())
			{
				if (player.isOnline() && !blocked.isEmpty())
				{
					player.sendMessage(text("Вход в массовую охоту отменён: ", "Monster rush entry cancelled: ") + blocked);
				}
				return true;
			}
			return false;
		});
		if (humans.isEmpty())
		{
			return;
		}
		final List<Player> companions = new ArrayList<>();
		for (Player human : humans)
		{
			if (!human.isInParty()) { continue; }
			for (Player ally : human.getParty().getMembers())
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(ally.getName());
				if (template != null && PhantomManager.getInstance().isPhantom(ally) && org.l2jmobius.gameserver.managers.phantom.PhantomCompanionManager.getInstance().isOwner(human, template)
					&& ally.isOnline() && !ally.isAlikeDead() && !ally.isOnEvent() && !ally.isRegisteredOnEvent() && !ally.isInInstance() && !companions.contains(ally)) { companions.add(ally); }
			}
		}
		humans.addAll(companions);
		sequence = GlobalVariablesManager.getInstance().getLong("L2HubX10EventSequence", 0) + 1;
		GlobalVariablesManager.getInstance().set("L2HubX10EventSequence", sequence);
		GlobalVariablesManager.getInstance().storeMe();
		started = System.currentTimeMillis();
		running = true;
		if (kind == Kind.HUNT)
		{
			final Player quarry = World.getPlayers().stream().filter(X10FarmEvent::availableHunter).max(Comparator.comparingInt(Player::getLevel).thenComparingDouble(player -> player.getMaxHp() + player.getMaxCp() + player.getPAtk(null) + player.getMAtk(null, null))).orElse(null);
			if (quarry == null)
			{
				humans.forEach(player -> player.sendMessage(text("Охота отменена: нет свободного охотника для роли цели.", "Hunt cancelled: no available hunter can be the target.")));
				running = false;
				return;
			}
			final Room room = new Room(quarry.getLevel());
			ROOMS.add(room);
			enter(quarry, room);
			room.quarry = MEMBERS.get(quarry.getObjectId());
			boostQuarry(room);
			for (Player player : humans) { enter(player, room); }
			fillHunters(room, Math.min(30, Math.max(10, humans.size() + 6)));
			groups(room);
			for (Member member : room.players) { member.squad = member.player.isInParty() ? member.player.getParty().getMembers().stream().mapToInt(Player::getObjectId).min().orElse(member.player.getObjectId()) : member.player.getObjectId(); }
			return;
		}
		if (kind == Kind.DEFENCE)
		{
			final Room room = new Room(X10EventRules.invasionLevel(X10World.activeSeconds()));
			ROOMS.add(room);
			for (Player player : humans) { enter(player, room); }
			fillHunters(room, Math.min(30, Math.max(10, humans.size() + 5)));
			groups(room);
			final Spawn hq = new Spawn(NpcData.getInstance().getTemplate(900600));
			hq.setXYZ(GATE.getX() + 250, GATE.getY(), GATE.getZ());
			hq.setInstanceId(room.instance.getId());
			hq.stopRespawn();
			room.headquarters = hq.doSpawn(false);
			if (room.headquarters == null) { throw new IllegalStateException("Headquarters spawn failed"); }
			room.headquarters.setInvul(true);
			room.headquarters.setImmobilized(true);
			room.headquarters.setName(text("Штаб обороны", "Defence Headquarters"));
			room.spawns.add(hq);
			room.nextWave = started + Math.min(15000, duration() / (L2HubX10Config.DEFENCE_WAVES * 4L));
			return;
		}
		humans.sort(Comparator.comparingInt(Player::getLevel));
		final Map<Object, List<Player>> units = new LinkedHashMap<>();
		for (Player player : humans)
		{
			units.computeIfAbsent(player.isInParty() ? player.getParty() : player.getObjectId(), ignored -> new ArrayList<>()).add(player);
		}
		final Map<Integer, List<List<Player>>> bands = new LinkedHashMap<>();
		for (List<Player> unit : units.values())
		{
			if (unit.size() > L2HubX10Config.FARM_ROOM)
			{
				unit.forEach(player -> player.sendMessage(text("Группа не помещается в копию. Лимит: ", "The group does not fit in one arena. Limit: ") + L2HubX10Config.FARM_ROOM));
				continue;
			}
			final int level = Math.max(1, unit.stream().mapToInt(Player::getLevel).min().orElse(1) / 5 * 5);
			bands.computeIfAbsent(level, ignored -> new ArrayList<>()).add(unit);
		}
		for (var band : bands.entrySet())
		{
			final var pending = new ArrayList<>(band.getValue());
			while (!pending.isEmpty())
			{
				final Room room = new Room(band.getKey());
				ROOMS.add(room);
				final List<Player> selected = new ArrayList<>();
				for (var iterator = pending.iterator(); iterator.hasNext();)
				{
					final List<Player> unit = iterator.next();
					if (selected.size() + unit.size() <= L2HubX10Config.FARM_ROOM)
					{
						selected.addAll(unit);
						iterator.remove();
					}
				}
				final List<Player> hunters = L2HubX10Config.EVENT_HUNTERS ? World.getPlayers().stream().filter(hunter -> availableHunter(hunter) && (Math.abs(hunter.getLevel() - room.level) <= 4)).toList() : List.of();
				for (PhantomTemplate.Role role : new PhantomTemplate.Role[] {PhantomTemplate.Role.HEALER, PhantomTemplate.Role.BUFFER, PhantomTemplate.Role.TANK})
				{
					for (Player hunter : hunters)
					{
						final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
						if ((selected.size() < L2HubX10Config.FARM_ROOM) && !selected.contains(hunter) && (template != null) && (template.getRole() == role))
						{
							selected.add(hunter);
							break;
						}
					}
				}
				for (Player hunter : hunters)
				{
					if ((selected.size() < L2HubX10Config.FARM_ROOM) && !selected.contains(hunter))
					{
						selected.add(hunter);
					}
				}
				for (Player player : selected)
				{
					enter(player, room);
				}
				groups(room);
				for (int n = 0; n < L2HubX10Config.FARM_MOBS; n++)
				{
					spawn(room);
				}
			}
		}
	}

	private static boolean availableHunter(Player player)
	{
		// Обычную охоту бот вправе прервать ради ивента; занятые рейдом,
		// осадой, олимпиадой, чужой пати или PvP не забираются. isHunting также
		// исключает RECRUIT: бот уже откликнулся на сбор пати, в том числе на РБ в 0.5.221.
		return PhantomHunterManager.getInstance().isHunting(player) && player.isOnline() && !MEMBERS.containsKey(player.getObjectId())
			&& !player.isInParty() && !player.isOnEvent() && !player.isRegisteredOnEvent() && !player.isAlikeDead() && !player.isInStoreMode() && !player.isInInstance() && !player.isInDuel()
			&& !player.isFishing() && !player.isInSiege() && !player.isInOlympiadMode() && (player.getPvpFlag() == 0) && (PhantomCompat.karma(player) == 0)
			&& !player.isCursedWeaponEquipped() && !PhantomEvents.getInstance().isBusy(player) && !PhantomOlympiad.getInstance().isBusy(player)
			&& (PhantomRaid.getInstance().raidOf(player) == null) && (!(player.getTarget() instanceof Attackable target) || !target.isRaid());
	}

	private static void enter(Player player, Room room)
	{
		player.getVariables().set(RETURN, player.getX() + "," + player.getY() + "," + player.getZ());
		player.getVariables().storeMe();
		final Member member = new Member(player, room);
		MEMBERS.put(player.getObjectId(), member);
		room.players.add(member);
		room.instance.addPlayer(player.getObjectId());
		player.abortAttack();
		player.abortCast();
		player.setTarget(null);
		player.setOnEvent(true);
		player.setTeam(Team.BLUE);
		if (player.hasSummon()) { player.getSummon().setTeam(Team.BLUE); }
		player.teleToLocation(room.center, room.instance.getId(), 100);
	}

	private static void fillHunters(Room room, int limit)
	{
		if (!L2HubX10Config.EVENT_HUNTERS) { return; }
		final List<Player> free = World.getPlayers().stream().filter(player -> availableHunter(player) && Math.abs(player.getLevel() - room.level) <= 9).sorted(Comparator.comparingInt(player ->
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
			return template != null && (template.getRole() == PhantomTemplate.Role.HEALER || template.getRole() == PhantomTemplate.Role.BUFFER) ? 0 : 1;
		})).toList();
		for (Player player : free) { if (room.players.size() >= limit) { break; } enter(player, room); }
	}

	private static void boostQuarry(Room room)
	{
		final Player player = room.quarry.player;
		player.removeStatsOwner(room);
		player.addStatFunc(new FuncMul(Stat.MAX_HP, 0x70, room, L2HubX10Config.HUNT_HP, null));
		player.fullRestore();
		player.setTeam(Team.RED);
		if (player.hasSummon()) { player.getSummon().setTeam(Team.RED); }
		player.teleToLocation(new Location(CENTER.getX() + 600, CENTER.getY(), CENTER.getZ()), room.instance.getId(), 50);
		player.broadcastUserInfo();
	}

	private static void defenceTick(Room room, long now) throws Exception
	{
		if (now >= room.nextWave && room.wave < L2HubX10Config.DEFENCE_WAVES)
		{
			final int living = (int) MOBS.values().stream().filter(value -> value == room).count();
			final int count = Math.max(0, Math.min(L2HubX10Config.DEFENCE_MAX_MOBS - living, X10EventRules.waveSize(room.wave, room.players.size(), L2HubX10Config.DEFENCE_FIRST_WAVE, L2HubX10Config.DEFENCE_WAVE_GROWTH, L2HubX10Config.DEFENCE_WAVE_CAP)));
			for (int n = 0; n < count; n++) { spawn(room); }
			room.wave++;
			room.nextWave += Math.max(1000, duration() / L2HubX10Config.DEFENCE_WAVES);
		}
		if (now / 3000 == (now - 1000) / 3000) { return; }
		for (var entry : MOBS.entrySet())
		{
			if (entry.getValue() != room || !(World.findObject(entry.getKey()) instanceof Attackable npc) || npc.isAlikeDead()) { continue; }
			final Player enemy = npc.getTarget() instanceof Creature target ? owner(target) : null;
			if (enemy != null && MEMBERS.containsKey(enemy.getObjectId()) && enemy.getInstanceId() == room.instance.getId() && !enemy.isAlikeDead() && npc.calculateDistance3D(enemy) < 650) { continue; }
			if (npc.calculateDistance3D(room.headquarters) <= 150)
			{
				room.integrity = Math.max(0, room.integrity - L2HubX10Config.DEFENCE_HIT_PERCENT / 100.0 / Math.max(1, room.players.size() / 10.0));
				npc.setTarget(room.headquarters);
				npc.getAI().setIntentionAttack(room.headquarters);
			}
			else
			{
				npc.setRunning();
				npc.getAI().setIntentionMoveTo(room.headquarters.getLocation());
			}
		}
		room.headquarters.setCurrentHp(Math.max(1, room.headquarters.getMaxHp() * room.integrity));
		room.headquarters.setTitle(text("Прочность ", "Integrity ") + Math.round(room.integrity * 100) + "%");
		final StatusUpdate status = new StatusUpdate(room.headquarters);
		status.addAttribute(StatusUpdate.CUR_HP, (int) room.headquarters.getCurrentHp());
		status.addAttribute(StatusUpdate.MAX_HP, room.headquarters.getMaxHp());
		for (Member member : room.players) { if (member.player.isOnline()) { member.player.sendPacket(status); } }
	}

	private static void groups(Room room)
	{
		Player leader = room.players.stream().map(member -> member.player).filter(player -> player.isInParty() && player.getParty().getMemberCount() < 9
			&& player.getParty().getMembers().stream().allMatch(ally -> MEMBERS.containsKey(ally.getObjectId()) && MEMBERS.get(ally.getObjectId()).room == room)).findFirst().orElse(null);
		for (Member member : room.players)
		{
			final Player player = member.player;
			if (member == room.quarry) { continue; }
			if (player.isInParty())
			{
				continue;
			}
			if ((leader == null) || (leader.getParty().getMemberCount() >= 9))
			{
				leader = player;
				PhantomCompat.newParty(leader, PartyDistributionType.RANDOM);
			}
			else
			{
				player.joinParty(leader.getParty());
			}
			member.joinedByEvent = true;
			member.joinedParty = player.getParty();
		}
	}

	private static synchronized void spawn(Room room) throws Exception
	{
		if (room.closed)
		{
			return;
		}
		final Spawn spawn = new Spawn(NpcData.getInstance().getTemplate(900500 + room.level));
		spawn.setXYZ(room.center.getX() + (kind == Kind.DEFENCE ? -1000 : 0) + ThreadLocalRandom.current().nextInt(-350, 351), room.center.getY() + ThreadLocalRandom.current().nextInt(-350, 351), room.center.getZ());
		spawn.setInstanceId(room.instance.getId());
		spawn.stopRespawn();
		final Npc npc = spawn.doSpawn(false);
		if (npc == null)
		{
			throw new IllegalStateException("Event spawn failed");
		}
		npc.getVariables().set("L2HubX10Event", true);
		npc.setName(text("Ивентовый противник", "Event monster"));
		npc.setTitle(text("Ивент", "Event"));
		room.spawns.add(spawn);
		MOBS.put(npc.getObjectId(), room);
		HEALTH.put(npc.getObjectId(), npc.getCurrentHp());
	}

	private static Player owner(Creature creature)
	{
		return creature == null ? null : creature.isPlayer() ? creature.asPlayer() : creature.isSummon() ? creature.asSummon().getOwner() : null;
	}

	private static synchronized void damage(OnCreatureDamageDealt event)
	{
		if (!running || (event.getDamage() <= 0))
		{
			return;
		}
		final Player player = owner(event.getAttacker());
		final Member member = player == null ? null : MEMBERS.get(player.getObjectId());
		final Player victim = owner(event.getTarget());
		final Member opponent = victim == null ? null : MEMBERS.get(victim.getObjectId());
		if (kind == Kind.HUNT && member != null && opponent != null && member.room == opponent.room && player.getTeam() != victim.getTeam() && player.getInstanceId() == member.room.instance.getId() && victim.getInstanceId() == player.getInstanceId())
		{
			member.score.damage += Math.min(event.getDamage(), event.getTarget().getMaxHp() + event.getTarget().getMaxCp());
			member.score.lastCombat = opponent.score.lastCombat = System.currentTimeMillis();
			member.score.active(System.currentTimeMillis() - started);
			opponent.score.active(System.currentTimeMillis() - started);
		}
		if ((member != null) && (MOBS.get(event.getTarget().getObjectId()) == member.room))
		{
			// Mobius сообщает об уроне после изменения HP. Остаток до удара
			// нужен для последнего попадания, чтобы не засчитывать избыточный урон.
			final double hp = Math.max(0, event.getTarget().getCurrentHp());
			final double previous = HEALTH.getOrDefault(event.getTarget().getObjectId(), (double) event.getTarget().getMaxHp());
			member.score.damage += hp > 0 ? event.getDamage() : Math.min(event.getDamage(), previous);
			HEALTH.put(event.getTarget().getObjectId(), hp);
			member.score.lastCombat = System.currentTimeMillis();
			member.score.active(System.currentTimeMillis() - started);
		}
		final Player tank = owner(event.getTarget());
		final Member defender = tank == null ? null : MEMBERS.get(tank.getObjectId());
		if ((defender != null) && (MOBS.get(event.getAttacker().getObjectId()) == defender.room))
		{
			defender.score.lastCombat = System.currentTimeMillis();
			defender.score.active(System.currentTimeMillis() - started);
		}
	}

	private static synchronized void support(OnCreatureSkillUse event)
	{
		if (!running || (event.getSkill() == null) || event.getSkill().isDebuff())
		{
			return;
		}
		final Player player = owner(event.getCaster());
		final Member member = player == null ? null : MEMBERS.get(player.getObjectId());
		if ((member == null) || (event.getTargets() == null))
		{
			return;
		}
		for (var object : event.getTargets())
		{
			if (!object.isPlayer())
			{
				continue;
			}
			final Player ally = object.asPlayer();
			final Member recipient = MEMBERS.get(ally.getObjectId());
			if ((recipient == null) || (recipient.room != member.room) || (ally.getTeam() != player.getTeam()) || (ally.getInstanceId() != player.getInstanceId()) || player.calculateDistance3D(ally) > 1500)
			{
				continue;
			}
			final boolean healing = (event.getSkill().hasEffectType(EffectType.HEAL) && ally.getCurrentHp() < ally.getMaxHp() * 0.98)
				|| (event.getSkill().hasEffectType(EffectType.CPHEAL) && ally.getCurrentCp() < ally.getMaxCp() * 0.98)
				|| (event.getSkill().hasEffectType(EffectType.MANAHEAL_PERCENT, EffectType.MANAHEAL_BY_LEVEL) && ally.getCurrentMp() < ally.getMaxMp() * 0.8)
				|| (event.getSkill().hasEffectType(EffectType.RESURRECTION, EffectType.RESURRECTION_SPECIAL) && ally.isDead());
			final boolean buff = (ally != player) && event.getSkill().isContinuous() && !ally.getEffectList().isAffectedBySkill(event.getSkill().getId());
			if (healing || buff)
			{
				if (buff) { member.buffs.computeIfAbsent(ally.getObjectId(), ignored -> new HashSet<>()).add(event.getSkill().getId()); }
				member.score.lastSupport = System.currentTimeMillis();
				member.score.active(System.currentTimeMillis() - started);
				break;
			}
		}
	}

	private static boolean supporting(Member member, long now)
	{
		final Player player = member.player;
		for (Member ally : member.room.players)
		{
			if (ally == member || ally.player.getTeam() != player.getTeam() || !ally.player.isOnline() || ally.player.isAlikeDead() || ally.player.getInstanceId() != player.getInstanceId() || player.calculateDistance3D(ally.player) > 1500 || now - ally.score.lastCombat > 30000) { continue; }
			final Set<Integer> buffs = member.buffs.get(ally.player.getObjectId());
			if (buffs != null) { buffs.removeIf(id -> !ally.player.getEffectList().isAffectedBySkill(id)); }
			if ((buffs != null && !buffs.isEmpty()) || (now - member.score.lastSupport < 30000 && player.isInParty() && player.getParty().getMembers().contains(ally.player))) { return true; }
		}
		return false;
	}

	private static synchronized void death(OnCreatureDeath event)
	{
		if (!running || kind != Kind.HUNT || !event.getTarget().isPlayer()) { return; }
		final Member victim = MEMBERS.get(event.getTarget().getObjectId());
		if (victim == null || event.getTarget().getInstanceId() != victim.room.instance.getId()) { return; }
		final Player killer = owner(event.getAttacker());
		final Member attacker = killer == null ? null : MEMBERS.get(killer.getObjectId());
		final Room room = victim.room;
		if (victim == room.quarry)
		{
			room.quarryKilled = true;
			room.finishedElapsed = System.currentTimeMillis() - started;
			if (attacker != null && attacker.room == room && attacker != victim)
			{
				attacker.score.kills++;
				attacker.score.active(room.finishedElapsed);
				for (Member member : room.players)
				{
					if (member == attacker || (member.squad == attacker.squad && killer.isInParty() && killer.getParty().getMembers().stream().anyMatch(ally -> ally.getObjectId() == member.player.getObjectId()))) { room.winningSquad.add(member.player.getObjectId()); }
				}
			}
		}
		else if (attacker == room.quarry && victim.score.lastCombat >= started && System.currentTimeMillis() - victim.score.lastCombat <= 60000)
		{
			room.slain.record(victim.player.getObjectId(), true);
			attacker.score.kills++;
		}
	}

	private static synchronized void kill(Player killer, Attackable mob)
	{
		final Room room = MOBS.remove(mob.getObjectId());
		if ((room == null) || room.closed || !running)
		{
			return;
		}
		room.spawns.remove(mob.getSpawn());
		final Member member = killer == null ? null : MEMBERS.get(killer.getObjectId());
		final Double remainingHp = HEALTH.remove(mob.getObjectId());
		if ((member != null) && (member.room == room))
		{
			// При синхронном уведомлении смерти последний damage-event ещё не пришёл.
			member.score.damage += remainingHp == null ? 0 : Math.max(0, remainingHp);
			member.score.kills++;
			member.score.lastCombat = System.currentTimeMillis();
			member.score.active(System.currentTimeMillis() - started);
		}
		if (kind == Kind.DEFENCE) { return; }
		ThreadPool.schedule(() ->
		{
			try
			{
				spawn(room);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "X10 respawn failed", e);
				finish(false);
			}
		}, L2HubX10Config.FARM_RESPAWN * 1000L);
	}

	static boolean contains(Player player)
	{
		return running && MEMBERS.containsKey(player.getObjectId());
	}

	static synchronized Creature target(Player player)
	{
		final Member member = MEMBERS.get(player.getObjectId());
		if ((member == null) || member.room.closed)
		{
			return null;
		}
		if (kind == Kind.HUNT)
		{
			final Room room = member.room;
			if (room.quarryKilled) { return null; }
			if (member != room.quarry) { return room.quarry.player.isAlikeDead() ? null : room.quarry.player; }
			final Player nearest = room.players.stream().filter(other -> other != room.quarry && other.player.isOnline() && !other.player.isAlikeDead() && other.player.getInstanceId() == room.instance.getId()).map(other -> other.player).min(Comparator.comparingDouble(player::calculateDistance3D)).orElse(null);
			if (L2HubX10Config.HUNT_RETREAT && nearest != null && player.getCurrentHp() < player.getMaxHp() * L2HubX10Config.HUNT_RETREAT_PERCENT / 100.0 && System.currentTimeMillis() >= room.nextEscape)
			{
				final double angle = Math.atan2(player.getY() - nearest.getY(), player.getX() - nearest.getX());
				player.abortAttack();
				player.setRunning();
				player.getAI().setIntentionMoveTo(new Location((int) Math.clamp(player.getX() + Math.cos(angle) * 500, CENTER.getX() - 1000, CENTER.getX() + 1000), (int) Math.clamp(player.getY() + Math.sin(angle) * 500, CENTER.getY() - 1000, CENTER.getY() + 1000), CENTER.getZ()));
				room.nextEscape = System.currentTimeMillis() + 5000;
				return null;
			}
			return nearest;
		}
		Creature closest = null;
		double distance = Double.MAX_VALUE;
		for (var entry : MOBS.entrySet())
		{
			if (entry.getValue() != member.room)
			{
				continue;
			}
			final var object = World.findObject(entry.getKey());
			if ((object instanceof Attackable npc) && !npc.isAlikeDead())
			{
				final double next = player.calculateDistance3D(npc);
				if (next < distance)
				{
					distance = next;
					closest = npc;
				}
			}
		}
		return closest;
	}

	static synchronized void recover(Player player) { recover(player, true); }

	static synchronized void recover(Player player, boolean credit)
	{
		if (!running && QUEUE.containsKey(player.getObjectId()))
		{
			QUEUE.put(player.getObjectId(), player);
			player.setRegisteredOnEvent(true);
		}
		final Member member = MEMBERS.get(player.getObjectId());
		if (running && (member != null))
		{
			if (member.player != player)
			{
				member.player.removeStatsOwner(member.room);
				member.player = player;
				player.setOnEvent(true);
				player.setTeam(member == member.room.quarry ? Team.RED : Team.BLUE);
				member.room.instance.addPlayer(player.getObjectId());
				player.teleToLocation(member.room.center, member.room.instance.getId(), 100);
				if (member == member.room.quarry) { boostQuarry(member.room); }
				if (member.joinedByEvent)
				{
					groups(member.room);
				}
			}
			return;
		}
		final String saved = player.getVariables().getString(RETURN, "");
		if (!saved.isEmpty())
		{
			try
			{
				final String[] xyz = saved.split(",");
				player.setOnEvent(false);
				player.setRegisteredOnEvent(false);
				player.setTeam(Team.NONE);
				if (player.hasSummon()) { player.getSummon().setTeam(Team.NONE); }
				if (player.isDead())
				{
					player.doRevive();
				}
				player.fullRestore();
				player.setTarget(null);
				player.teleToLocation(new Location(Integer.parseInt(xyz[0]), Integer.parseInt(xyz[1]), Integer.parseInt(xyz[2])), 0, 0);
				player.getVariables().remove(RETURN);
				player.getVariables().storeMe();
			}
			catch (RuntimeException e)
			{
				LOGGER.log(Level.WARNING, "X10 return failed for " + player.getObjectId(), e);
			}
		}
		if (credit) { retryReward(player); }
	}

	private static void retryReward(Player player)
	{
		final String pending = player.getVariables().getString("L2HubX10PendingRewards", "");
		if (pending.isEmpty())
		{
			return;
		}
		final StringBuilder remaining = new StringBuilder();
		for (String entry : pending.split("\\|"))
		{
			final String[] fields = entry.split(",");
			if ((fields.length != 3) || (X10Economy.credit(player, Long.parseLong(fields[1]), Long.parseLong(fields[2]), fields[0]) < 0))
			{
				remaining.append(entry).append('|');
			}
		}
		player.getVariables().set("L2HubX10PendingRewards", remaining.toString());
		player.getVariables().storeMe();
		X10Contracts.refreshDailyCount(player);
	}

	private static synchronized void finish(boolean rewards)
	{
		final long duration = duration();
		final long elapsed = Math.clamp(kind == Kind.HUNT && !ROOMS.isEmpty() && ROOMS.getFirst().quarryKilled ? ROOMS.getFirst().finishedElapsed : System.currentTimeMillis() - started, 0, duration);
		if (!running && ROOMS.isEmpty())
		{
			for (Player player : QUEUE.values())
			{
				player.setRegisteredOnEvent(false);
			}
			QUEUE.clear();
			if (schedule != null) { scheduleNext(); }
			return;
		}
		running = false;
		final List<Member> members = new ArrayList<>(MEMBERS.values());
		for (Room room : ROOMS)
		{
			room.closed = true;
			for (Spawn spawn : room.spawns)
			{
				spawn.stopRespawn();
			}
		}
		MEMBERS.clear();
		MOBS.clear();
		HEALTH.clear();
		final String day = X10TaskState.day(java.time.LocalDateTime.now(), org.l2jmobius.gameserver.config.custom.L2HubWorldConfig.WORLD_DAILY_RESET_HOUR, org.l2jmobius.gameserver.config.custom.L2HubWorldConfig.WORLD_DAILY_RESET_MINUTE);
		for (Member member : members)
		{
			// Завершение может попасть между входом игрока и очередным tick:
			// не сохраняем переменные из уже отключённого объекта поверх новых.
			final Player current = World.getPlayer(member.player.getObjectId());
			if (current != null && current != member.player && current.isOnline())
			{
				member.player.removeStatsOwner(member.room);
				member.player = current;
			}
			final Player player = member.player;
			try
			{
				player.abortAttack();
				player.abortCast();
				player.removeStatsOwner(member.room);
				if (rewards && member.score.eligible(elapsed, L2HubX10Config.ACTIVITY))
				{
					final int coins = kind == Kind.DEFENCE ? X10EventRules.defenceCoins(options(kind).coins(), member.room.integrity, elapsed, duration, L2HubX10Config.DEFENCE_MIN_REWARD) : kind == Kind.HUNT ? X10EventRules.huntCoins(options(kind).coins(), elapsed, duration, member == member.room.quarry ? member.room.slain.coins(L2HubX10Config.HUNT_KILL_BONUS, L2HubX10Config.HUNT_BONUS_CAP) : member.room.winningSquad.contains(player.getObjectId()) ? L2HubX10Config.HUNT_GROUP_BONUS : 0) : options(kind).coins();
					final int style = elapsed >= duration ? options(kind).style() : 0;
					final String source = "event:" + day + ":" + sequence;
					final String pending = player.getVariables().getString("L2HubX10PendingRewards", "");
					player.getVariables().set("L2HubX10PendingRewards", pending + source + "," + coins + "," + style + "|");
					player.getVariables().storeMe();
					retryReward(player);
					player.sendMessage(name(kind) + text(" завершена. Награда: ", " complete. Reward: ") + coins + text(" монет, жетонов стиля: ", " coins; Style Tokens: ") + style + text(". При временной ошибке начисление повторится автоматически.", ". A failed credit will be retried automatically."));
				}
				else
				{
					player.sendMessage(rewards ? text("Ивент: не хватило полезной активности для награды.", "Event: not enough useful activity to earn the reward.") : text("Ивент отменён. Возвращаем участников.", "Event cancelled. Returning participants."));
				}
				if (member.joinedByEvent && player.isInParty() && player.getParty() == member.joinedParty)
				{
					player.getParty().removePartyMember(player, PartyMessageType.LEFT);
				}
				if (player.isOnline())
				{
					recover(player);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, "X10 participant finalization failed: " + player.getObjectId(), e);
			}
		}
		for (Room room : ROOMS)
		{
			InstanceManager.getInstance().destroyInstance(room.instance.getId());
		}
		ROOMS.clear();
		schedule.finish(X10World.activeSeconds());
		saveSchedule();
		scheduleNext();
	}
}
