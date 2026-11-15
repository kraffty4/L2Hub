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
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.ItemManager;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
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
import org.l2jmobius.gameserver.managers.world.X10EventRules.Kind;

/** Общий жизненный цикл трёх форматов x10: запись, копии, группы, активность, награды и возврат. */
final class X10FarmEvent
{
	private static final Logger LOGGER = Logger.getLogger(X10FarmEvent.class.getName());
	private static final Location CENTER = new Location(149465, 46714, -3414);
	// Площадки за городскими воротами: NPC и магазины остаются в нулевом инстансе.
	private record Gate(String ru, String en, Location center) { }
	private static final Gate[] GATES = {
		new Gate("Гиран", "Giran", new Location(81520, 154800, -3424)),
		new Gate("Аден", "Aden", new Location(147450, 33800, -2736)),
		new Gate("Дион", "Dion", new Location(24000, 145000, -3296))
	};
	private static Gate defenceGate;
	// Только явная запись лидером переносит всю пати. Личная запись этого не делает.
	private record PartyEntry(Object party, List<Player> players) { }
	private static final Map<Integer, PartyEntry> PARTY_QUEUE = new java.util.HashMap<>();
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
	private static long warnedStart;
	private static int warnedSeconds;
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
		volatile boolean eliminated;
		long missingSince;
		int completedWaves;
		long waveCoins;
		int squad;
		final long[] boosts = new long[3];
		BuffInfo speedHerb;
		long nextSplash;
		long nextScoreAt;
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
		int level;
		final List<Member> players = new ArrayList<>();
		final List<Spawn> spawns = new ArrayList<>();
		final Location center;
		String city;
		int completedWaves;
		boolean waveSpawned;
		int modifier;
		boolean partyEntry;
		int wave;
		long nextWave;
		Member quarry;
		final X10EventRules.QuarryBonus slain = new X10EventRules.QuarryBonus();
		final Set<Integer> winningSquad = new HashSet<>();
		boolean quarryKilled;
		long finishedElapsed;
		long quarryMissingSince;
		long nextEscape;
		long nextGolden;
		int goldenId;
		long goldenExpires;
		int goldenSequence;
		int bossId;
		boolean bossSpawned;
		boolean bossKilled;
		final Map<Integer, Herb> herbs = new LinkedHashMap<>();
		volatile boolean closed;

		Room(int level)
		{
			this.level = level;
			final X10FarmArenas.Arena arena = kind == Kind.FARM ? X10FarmArenas.choose(level) : null;
			center = arena != null ? arena.center() : kind == Kind.DEFENCE ? (defenceGate == null ? GATES[0] : defenceGate).center() : CENTER;
			city = kind == Kind.DEFENCE ? text((defenceGate == null ? GATES[0] : defenceGate).ru(), (defenceGate == null ? GATES[0] : defenceGate).en()) : arena != null ? text(arena.ru(), arena.en()) : "";
			nextGolden = System.currentTimeMillis() + goldenDelay();
			instance = InstanceManager.getInstance().createDynamicInstance(0);
			instance.setPvP(false);
			instance.setAllowSummon(false);
			instance.setEmptyDestroyTime(120000);
			instance.setDuration((options(kind).minutes() + 5) * 60000);
		}
	}

	static void start()
	{
		try { X10FarmArenas.load(); }
		catch (Exception e) { throw new IllegalStateException("Invalid X10 farm arenas", e); }
		for (int level : new int[] {1, 5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60, 65, 70, 75, 80, 85})
		{
			if (NpcData.getInstance().getTemplate(902000 + level) == null || NpcData.getInstance().getTemplate(902100 + level) == null) { throw new IllegalStateException("Missing X10 farm special NPC for level " + level); }
			if (NpcData.getInstance().getTemplate(900500 + level) == null)
			{
				throw new IllegalStateException("Missing X10 event NPC data for level " + level);
			}
		}
		for (int variant = 0; variant < 3; variant++)
		{
			for (int level = 1; level <= 85; level++)
			{
				if (NpcData.getInstance().getTemplate(901000 + variant * 100 + level) == null) { throw new IllegalStateException("Missing X10 defence NPC: " + variant + "/" + level); }
			}
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
			final PartyEntry entry = PARTY_QUEUE.get(player.getObjectId());
			if (entry != null)
			{
				for (Player ally : entry.players())
				{
					if (QUEUE.remove(ally.getObjectId()) != null) { ally.setRegisteredOnEvent(false); }
					PARTY_QUEUE.remove(ally.getObjectId());
					if (ally.isOnline()) { ally.sendMessage(text("Запись пати отменена участником.", "Party sign-up cancelled by a member.")); }
				}
			}
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
			for (Player member : requested != Kind.HUNT ? player.getParty().getMembers() : people)
			{
				if (!member.isOnline() || !entryError(member, requested).isEmpty()) { return text("Не удалось записать пати: ", "Cannot sign up the party: ") + member.getName() + ": " + (member.isOnline() ? entryError(member, requested) : text("не в игре", "offline")); }
			}
			if (requested != Kind.HUNT)
			{
				if (player.getParty().getMemberCount() > 9) { return text("Лимит пати — 9 участников.", "Party limit: 9 members."); }
				final PartyEntry entry = new PartyEntry(player.getParty(), List.copyOf(player.getParty().getMembers()));
				for (Player member : entry.players())
				{
					if (PARTY_QUEUE.containsKey(member.getObjectId()) && PARTY_QUEUE.get(member.getObjectId()).party() != entry.party()) { return text("Участник уже записан другой пати.", "A member is already signed up with another party."); }
				}
				for (Player member : people) { PARTY_QUEUE.put(member.getObjectId(), entry); }
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
		final String blocked = entryError(player, requested);
		if (!blocked.isEmpty()) { return blocked; }
		if (QUEUE.containsKey(player.getObjectId()))
		{
			QUEUE.put(player.getObjectId(), player);
			player.setRegisteredOnEvent(true);
			return text("Ты уже записан.", "You are already signed up.");
		}
		QUEUE.put(player.getObjectId(), player);
		player.setRegisteredOnEvent(true);
		return text("Запись принята. На старте перенесёт на площадку события. ТП бесплатный.", "Signed up. You will be teleported to the event at the start. Teleport is free.");
	}

	// Допуск на ивент независим от строгих ограничений обычного телепорта.
	static String entryError(Player player, Kind requested)
	{
		if (player.isDead()) { return text("персонаж мёртв", "you are dead"); }
		if (player.isInOlympiadMode() || PhantomCompat.olympiadRegistered(player)) { return text("олимпиада или запись на олимпиаду", "Olympiad participation or registration"); }
		final boolean queuedHere = nextKind == requested && QUEUE.containsKey(player.getObjectId());
		final boolean enteredHere = kind == requested && MEMBERS.containsKey(player.getObjectId());
		if ((player.isOnEvent() && !enteredHere) || (player.isRegisteredOnEvent() && !queuedHere && !enteredHere)) { return text("участие или запись на другой ивент", "participation or registration in another event"); }
		if (player.isJailed()) { return text("персонаж в тюрьме", "you are jailed"); }
		// isInSiege() ставит только вход в зону осады; участник идущей осады вне зоны виден по siegeState.
		if (player.isInSiege() || (player.getSiegeState() != 0) || player.isInsideZone(org.l2jmobius.gameserver.entity.zone.ZoneId.SIEGE)) { return text("участие в осаде или зона осады", "siege participation or siege zone"); }
		return "";
	}

	private static void notice(Player player, String message)
	{
		player.sendMessage(message);
		player.sendPacket(new ExShowScreenMessage(message, ExShowScreenMessage.MIDDLE_CENTER, 8000, 0, false, false));
	}

	private static void warnStart(long now)
	{
		if (warnedStart != nextStart) { warnedStart = nextStart; warnedSeconds = 0; }
		final long remaining = nextStart - now;
		final int seconds = remaining > 10000 ? 30 : 10;
		if (remaining <= 0 || remaining > 30000 || (seconds == 30 && warnedSeconds != 0) || warnedSeconds == 10) { return; }
		warnedSeconds = seconds;
		for (Player player : QUEUE.values())
		{
			if (player.isOnline()) { notice(player, name(nextKind) + text(": старт через ", ": starts in ") + seconds + text(" сек. Приготовься к телепорту!", " sec. Prepare to teleport!")); }
		}
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
			case DEFENCE -> text("Оборона — нашествие у ворот", "Defence — invasion at the gates");
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
				.append(text("Старт ", "Starts ")).append(startTime(event)).append(" · ").append(options(event).minutes()).append(text(" мин · ", " min · ")).append(event == Kind.DEFENCE ? L2HubX10Config.DEFENCE_WAVE_COINS : options(event).coins()).append(event == Kind.DEFENCE ? text("+ коинов за волну", "+ coins per wave") : text(" коинов", " coins")).append("<br1>")
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
		if (requested == Kind.DEFENCE) { out.append(X10Text.escape(player.getVariables().getString("L2HubX10DefenceResult", ""))).append("<br1>"); }
		if (requested == Kind.FARM)
		{
			out.append(X10Text.escape(player.getVariables().getString("L2HubX10FarmResult", ""))).append("<br1>");
			out.append(text("Личная запись переносит только тебя; запись лидером — всю пати. Свободные одиночки объединяются временно. Золотые беглецы дают коины убившей пати. Хербы: Сила — урон x2, Скорость, Воин — AoE вокруг атакующего; действуют только здесь. На последней минуте босс: убили — бонус всем в копии. Арены своего уровня чередуются.", "Individual sign-up brings only you; leader sign-up brings the whole party. Ungrouped players form temporary parties. Golden runners reward the killing party. Herbs: Power doubles damage, Speed, Warrior adds splash around the attacker; arena only. A boss arrives in the final minute: kill it to reward everyone in the instance. Level-matched arenas rotate.")).append("<br1>");
			out.append(text("Отдельные арены по уровню. Участников на копию: ", "Private level-matched arenas. Participants per copy: ")).append(L2HubX10Config.FARM_ROOM).append(text(". Мобов: ", ". Monsters: ")).append(L2HubX10Config.FARM_MOBS).append(text("; возрождение через ", "; respawn after ")).append(L2HubX10Config.FARM_RESPAWN).append(text(" сек. Гонка пати по убийствам; награда за участие плюс фонд за место, поделённый между активными игроками пати.", " sec. Party kill race; participation reward plus a placement pool shared by active human party members.")).append("<br1>");
		}
		else if (requested == Kind.DEFENCE)
		{
			out.append(text("Нашествие у ворот Гирана, Адена или Диона в отдельной копии. Лидер записывает всю пати; личная запись переносит только тебя, без напарников. Одиночки без пати объединяются временно; свободные места до 9 занимают доступные боты по уровню.", "Invasion at Giran, Aden or Dion gates in a private instance. A leader signs up the whole party; individual sign-up brings only you, without companions. Ungrouped players form temporary parties; available level-matched bots fill up to 9 slots.")).append("<br1>");
			out.append(text("Первая волна: средний уровень пати минус ", "First wave: average party level minus ")).append(L2HubX10Config.DEFENCE_LEVEL_OFFSET).append(text("; шаг уровня ", "; level step ")).append(L2HubX10Config.DEFENCE_LEVEL_STEP).append(text(" (предел 85). Каждая пятая — мини-босс. Следующая волна после полной зачистки; возможны элита, стрелки/маги, быстрые или двойные коины. Умер — выбыл до конца забега. Вайп завершает только вашу копию и возвращает всех вместе.", " (cap 85). Every fifth wave has a mini-boss. Clear a wave to advance; elite, ranged, fast or double-coin modifiers may appear. Death eliminates you until the run ends. A wipe ends only your instance and returns everyone together.")).append("<br1>");
			out.append(text("Коины за первую волну: ", "First wave coins: ")).append(L2HubX10Config.DEFENCE_WAVE_COINS).append(text("; прибавка за следующую: ", "; increase per wave: ")).append(L2HubX10Config.DEFENCE_COIN_GROWTH).append(text(". Заработанное до смерти сохраняется; начисление при общем возврате. Жетон стиля — за завершение всех волн или лимита времени с пройденной волной.", ". Earnings before death are kept and paid on the group's return. Style reward requires clearing all waves or reaching the time limit with a cleared wave.")).append("<br1>");
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
		if (requested != Kind.DEFENCE) { out.append(text("Полезная активность минимум в ", "Useful activity during at least ")).append(L2HubX10Config.ACTIVITY).append(text("% прошедшего боя. Учитываются урон, полезное лечение и бафы. Обычного опыта и дропа нет; ТП при старте бесплатный, после боя вернёшься обратно.", "% of the elapsed fight. Damage, useful healing and buffs count. No ordinary XP or loot; free teleport at the start and return afterwards.")).append("<br1>"); }
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
			if (requested == Kind.FARM) { out.append(farmTable(participant.room)); }
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
			warnStart(now);
			if (now >= nextStart)
			{
				kind = nextKind;
				schedule.start(kind, X10World.activeSeconds(), testStart);
				saveSchedule();
				begin();
				scheduleNext();
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
		if (kind == Kind.FARM) { for (Room room : ROOMS) { farmTick(room, now); } }
		if (kind == Kind.DEFENCE)
		{
			for (Room room : ROOMS) { if (!room.closed) { defenceTick(room, now); } }
			if (ROOMS.stream().allMatch(room -> room.closed)) { finish(true); return; }
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
				clearBoosts(member);
				continue;
			}
			if (player.getInstanceId() != member.room.instance.getId())
			{
				clearBoosts(member);
				player.abortAttack();
				player.abortCast();
				player.teleToLocation(member.room.center, member.room.instance.getId(), 80);
				continue;
			}
			if (kind == Kind.DEFENCE && member.eliminated) { continue; }
			if (player.isDead())
			{
				clearBoosts(member);
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
			if (kind == Kind.FARM && now >= member.nextScoreAt)
			{
				member.nextScoreAt = now + L2HubX10Config.FARM_SCORE_SECONDS * 1000L;
				player.sendPacket(new ExShowScreenMessage(farmScore(member, now), ExShowScreenMessage.TOP_CENTER, 10000, 0, false, false));
			}
			if (kind != Kind.FARM && now / 15000 != (now - 1000) / 15000)
			{
				player.sendPacket(new ExShowScreenMessage(name(kind) + ": " + timeLeft(duration() - (now - started)) + text(" мин. Убийств: ", " min. Kills: ") + member.score.kills + text(". Урон: ", ". Damage: ") + Math.round(member.score.damage)
					+ (kind == Kind.DEFENCE ? text(". Волн: ", ". Waves: ") + member.completedWaves + text(". Коины: ", ". Coins: ") + member.waveCoins : "") + "\n" + leaders(member.room), ExShowScreenMessage.TOP_RIGHT, 15000, 1, false, false));
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
		humans.removeIf(player ->
		{
			final String blocked = entryError(player, kind);
			if (!player.isOnline() || PhantomManager.getInstance().isPhantom(player) || !blocked.isEmpty())
			{
				if (player.isOnline() && !blocked.isEmpty())
				{
					notice(player, name(kind) + text(": вход отменён — ", ": entry cancelled — ") + blocked);
				}
				return true;
			}
			return false;
		});
		for (Player player : QUEUE.values()) { player.setRegisteredOnEvent(false); }
		QUEUE.clear();
		if (humans.isEmpty())
		{
			PARTY_QUEUE.clear();
			return;
		}
		final List<Player> companions = new ArrayList<>();
		for (Player human : humans)
		{
			if (kind != Kind.HUNT || !human.isInParty()) { continue; }
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
				humans.forEach(player -> notice(player, text("Охота отменена: нет свободного охотника для роли цели.", "Hunt cancelled: no available hunter can be the target.")));
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
			beginDefence(humans);
			PARTY_QUEUE.clear();
			if (ROOMS.isEmpty()) { running = false; }
			return;
		}
		humans.sort(Comparator.comparingInt(Player::getLevel));
		final Map<Object, List<Player>> units = new LinkedHashMap<>();
		final Set<Integer> selectedParty = new HashSet<>();
		for (Player player : humans)
		{
			if (!selectedParty.add(player.getObjectId())) { continue; }
			final PartyEntry entry = PARTY_QUEUE.get(player.getObjectId());
			if (entry == null) { units.put(player.getObjectId(), new ArrayList<>(List.of(player))); continue; }
			entry.players().forEach(ally -> selectedParty.add(ally.getObjectId()));
			final boolean valid = entry.players().stream().allMatch(ally -> ally.isOnline() && ally.getParty() == entry.party()
				&& ally.getParty().getMemberCount() == entry.players().size() && entryError(ally, Kind.FARM).isEmpty()
				&& (humans.contains(ally) || PhantomManager.getInstance().isPhantom(ally)));
			if (valid) { units.put(entry.party(), new ArrayList<>(entry.players())); }
			else { entry.players().forEach(ally -> { if (ally.isOnline()) { notice(ally, text("Зачистка: пати изменилась или недоступна. Запишитесь снова.", "Monster rush: party changed or unavailable. Sign up again.")); } }); }
		}
		PARTY_QUEUE.clear();
		final Map<Integer, List<List<Player>>> bands = new LinkedHashMap<>();
		for (List<Player> unit : units.values())
		{
			if (unit.size() > L2HubX10Config.FARM_ROOM)
			{
				unit.forEach(player -> notice(player, text("Группа не помещается в копию. Лимит: ", "The group does not fit in one arena. Limit: ") + L2HubX10Config.FARM_ROOM));
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
				freezeSquads(room);
				room.players.forEach(member -> notice(member.player, text("Зачистка: ", "Monster rush: ") + room.city + text(". Гонка началась!", ". The race is on!")));
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
		final int pvpFlag = player.getPvpFlag();
		final int karma = PhantomCompat.karma(player);
		player.getVariables().set(RETURN, player.getX() + "," + player.getY() + "," + player.getZ());
		player.getVariables().storeMe();
		final Member member = new Member(player, room);
		MEMBERS.put(player.getObjectId(), member);
		room.players.add(member);
		room.instance.addPlayer(player.getObjectId());
		player.abortAttack();
		player.abortCast();
		player.setTarget(null);
		if (player.isInDuel())
		{
			final var duel = org.l2jmobius.gameserver.managers.DuelManager.getInstance().getDuel(player.getDuelId());
			if (duel != null) { duel.endDuel(org.l2jmobius.gameserver.entity.actor.enums.player.DuelResult.CANCELED); }
		}
		if (player.isFakeDeath()) { player.stopFakeDeath(true); }
		if (player.isFishing()) { player.endFishing(false); }
		if (player.isInStoreMode())
		{
			player.setPrivateStoreType(org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType.NONE);
			player.standUp();
			player.broadcastUserInfo();
		}
		if (player.isInInstance()) { player.setInstanceId(0); }
		player.setOnEvent(true);
		player.setTeam(Team.BLUE);
		if (player.hasSummon()) { player.getSummon().setTeam(Team.BLUE); }
		player.teleToLocation(room.center, room.instance.getId(), 100);
		// Выход из FlagPvPZone сам сбрасывает флаг: вход на ивент не должен его отмывать.
		if (player.getPvpFlag() != pvpFlag) { player.updatePvPFlag(pvpFlag); }
		if (PhantomCompat.karma(player) != karma) { player.setKarma(karma); }
	}

	private static void fillHunters(Room room, int limit)
	{
		if (!L2HubX10Config.EVENT_HUNTERS) { return; }
		final List<Player> free = World.getPlayers().stream().filter(player -> availableHunter(player) && Math.abs(player.getLevel() - room.level) <= 9).sorted(Comparator.comparingInt(player ->
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
			return template != null && (template.getRole() == PhantomTemplate.Role.HEALER || template.getRole() == PhantomTemplate.Role.BUFFER) ? 0 : 1;
		})).toList();
		if (kind == Kind.DEFENCE)
		{
			// Один саппорт каждого вида, затем бойцы; не заполняем все места хилерами.
			for (PhantomTemplate.Role role : new PhantomTemplate.Role[] {PhantomTemplate.Role.HEALER, PhantomTemplate.Role.BUFFER, PhantomTemplate.Role.TANK})
			{
				if (room.players.size() >= limit) { break; }
				if (room.players.stream().map(member -> PhantomData.getInstance().getTemplate(member.player.getName())).anyMatch(template -> template != null && template.getRole() == role)) { continue; }
				final Player recruit = free.stream().filter(player -> !MEMBERS.containsKey(player.getObjectId()))
					.filter(player -> { final PhantomTemplate t = PhantomData.getInstance().getTemplate(player.getName()); return t != null && t.getRole() == role; }).findFirst().orElse(null);
				if (recruit != null) { enter(recruit, room); }
			}
			final List<Player> fighters = free.stream().filter(player -> !MEMBERS.containsKey(player.getObjectId())).sorted(Comparator.comparingInt(player ->
			{
				final PhantomTemplate t = PhantomData.getInstance().getTemplate(player.getName());
				return t != null && (t.getRole() == PhantomTemplate.Role.HEALER || t.getRole() == PhantomTemplate.Role.BUFFER) ? 1 : 0;
			})).toList();
			for (Player player : fighters) { if (room.players.size() >= limit) { break; } enter(player, room); }
			return;
		}
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

	private static void beginDefence(List<Player> humans) throws Exception
	{
		final List<Gate> gates = new ArrayList<>();
		if (L2HubX10Config.DEFENCE_GIRAN) { gates.add(GATES[0]); }
		if (L2HubX10Config.DEFENCE_ADEN) { gates.add(GATES[1]); }
		if (L2HubX10Config.DEFENCE_DION) { gates.add(GATES[2]); }
		if (gates.isEmpty()) { gates.add(GATES[0]); }
		final long gateSequence = Math.max(0, GlobalVariablesManager.getInstance().getLong("L2HubX10DefenceGate", 0));
		defenceGate = gates.get((int) (gateSequence % gates.size()));
		GlobalVariablesManager.getInstance().set("L2HubX10DefenceGate", gateSequence + 1);
		GlobalVariablesManager.getInstance().storeMe();
		final Set<Integer> selected = new HashSet<>();
		final List<Player> singles = new ArrayList<>();
		for (Player player : humans)
		{
			if (!selected.add(player.getObjectId())) { continue; }
			final PartyEntry entry = PARTY_QUEUE.get(player.getObjectId());
			if (entry != null)
			{
				entry.players().forEach(ally -> selected.add(ally.getObjectId()));
				final boolean valid = entry.players().stream().allMatch(ally -> ally.isOnline() && ally.getParty() == entry.party()
					&& ally.getParty().getMemberCount() == entry.players().size() && entryError(ally, Kind.DEFENCE).isEmpty()
					&& (humans.contains(ally) || PhantomManager.getInstance().isPhantom(ally)));
				if (!valid)
				{
					entry.players().forEach(ally -> { if (ally.isOnline()) { notice(ally, text("Оборона: пати изменилась или участник недоступен. Запишитесь снова вместе.", "Defence: the party changed or a member is unavailable. Sign up together again.")); } });
					continue;
				}
				defenceRoom(entry.players(), true);
			}
			else if (player.isInParty()) { defenceRoom(List.of(player), false); }
			else { singles.add(player); }
		}
		singles.sort(Comparator.comparingInt(Player::getLevel));
		while (!singles.isEmpty())
		{
			final int level = singles.getFirst().getLevel();
			final List<Player> group = new ArrayList<>();
			for (var iterator = singles.iterator(); iterator.hasNext() && group.size() < 9;)
			{
				final Player player = iterator.next();
				if (player.getLevel() - level > 9) { break; }
				group.add(player); iterator.remove();
			}
			defenceRoom(group, false);
		}
	}

	private static void defenceRoom(List<Player> players, boolean partyEntry) throws Exception
	{
		final int level = (int) Math.round(players.stream().mapToInt(Player::getLevel).average().orElse(1));
		final Room room = new Room(level);
		room.partyEntry = partyEntry;
		ROOMS.add(room);
		for (Player player : players) { enter(player, room); }
		fillHunters(room, 9);
		room.level = (int) Math.round(room.players.stream().mapToInt(member -> member.player.getLevel()).average().orElse(level));
		groups(room);
		prepareWave(room, started);
	}

	private static void prepareWave(Room room, long now)
	{
		room.wave++;
		room.modifier = L2HubX10Config.DEFENCE_MODIFIERS && ThreadLocalRandom.current().nextInt(100) < L2HubX10Config.DEFENCE_MODIFIER_CHANCE
			? ThreadLocalRandom.current().nextInt(1, 5) : 0;
		room.waveSpawned = false;
		room.nextWave = now + L2HubX10Config.DEFENCE_PAUSE * 1000L;
		final String modifier = switch (room.modifier)
		{
			case 1 -> text(" · ЭЛИТНАЯ", " · ELITE");
			case 2 -> text(" · ЛУЧНИКИ / МАГИ", " · ARCHERS / MAGES");
			case 3 -> text(" · БЫСТРЫЕ", " · FAST");
			case 4 -> text(" · ДВОЙНЫЕ КОИНЫ", " · DOUBLE COINS");
			default -> "";
		};
		for (Member member : room.players)
		{
			if (member.player.isOnline()) { notice(member.player, room.city + text(": волна ", ": wave ") + room.wave + modifier
				+ (X10DefenceRules.boss(room.wave) ? text(" · МИНИ-БОСС", " · MINI-BOSS") : "")); }
		}
	}

	private static void eliminate(Member member)
	{
		if (member.eliminated) { return; }
		member.eliminated = true;
		member.player.abortAttack(); member.player.abortCast(); member.player.setTarget(null);
		if (member.player.hasSummon()) { member.player.getSummon().abortAttack(); member.player.getSummon().abortCast(); member.player.getSummon().setTarget(null); }
		if (member.player.isOnline()) { notice(member.player, text("Ты выбыл. Жди окончания забега пати. Сохранено коинов: ", "Eliminated. Wait for your party's run to end. Coins kept: ") + member.waveCoins); }
	}

	static boolean eliminated(Player player)
	{
		final Member member = player == null ? null : MEMBERS.get(player.getObjectId());
		return running && kind == Kind.DEFENCE && member != null && !member.room.closed && (member.eliminated || player.isDead());
	}

	private static void defenceTick(Room room, long now) throws Exception
	{
		for (Member member : room.players)
		{
			if (member.player.isDead()) { eliminate(member); }
			if (!member.player.isOnline())
			{
				if (member.missingSince == 0) { member.missingSince = now; }
				if (now - member.missingSince >= 30000) { eliminate(member); }
			}
			else { member.missingSince = 0; }
		}
		// Награду получают только люди, а выбывший больше не копит волны: когда выбыли все люди, держать копию ради ботов незачем
		// (иначе человек лежит трупом до гибели последнего бота или до таймера — воскрешение выбывшего закрывает 0413).
		if (room.players.stream().allMatch(member -> member.eliminated || PhantomManager.getInstance().isPhantom(member.player))) { finishDefenceRoom(room, true, false); return; }
		if (!room.waveSpawned && now >= room.nextWave)
		{
			final int count = Math.min(L2HubX10Config.DEFENCE_MAX_MOBS, X10EventRules.waveSize(room.wave - 1, room.players.size(), L2HubX10Config.DEFENCE_FIRST_WAVE, L2HubX10Config.DEFENCE_WAVE_GROWTH, L2HubX10Config.DEFENCE_WAVE_CAP));
			for (int n = 0; n < count; n++) { spawnDefender(room, X10DefenceRules.boss(room.wave) && n == 0, n); }
			room.waveSpawned = true;
		}
		if (room.waveSpawned && MOBS.values().stream().noneMatch(value -> value == room))
		{
			room.completedWaves++;
			for (Member member : room.players)
			{
				if (member.eliminated) { continue; }
				member.completedWaves++;
				member.waveCoins += X10DefenceRules.coins(room.wave, L2HubX10Config.DEFENCE_WAVE_COINS, L2HubX10Config.DEFENCE_COIN_GROWTH, room.modifier == 4);
			}
			if (room.wave >= L2HubX10Config.DEFENCE_WAVES) { finishDefenceRoom(room, true, true); return; }
			prepareWave(room, now);
		}
		// Враги наступают на живых участников только своей копии, включая дальний бой.
		for (var entry : MOBS.entrySet())
		{
			if (entry.getValue() != room || !(World.findObject(entry.getKey()) instanceof Attackable npc) || npc.isAlikeDead()) { continue; }
			final Player target = room.players.stream().filter(member -> !member.eliminated && member.player.isOnline() && !member.player.isAlikeDead()
				&& member.player.getInstanceId() == room.instance.getId()).map(member -> member.player).min(Comparator.comparingDouble(npc::calculateDistance3D)).orElse(null);
			if (target != null) { npc.setRunning(); npc.addDamageHate(target, 0, 100); npc.setTarget(target); npc.getAI().setIntentionAttack(target); }
		}
	}

	private static void spawnDefender(Room room, boolean boss, int index) throws Exception
	{
		final int level = X10DefenceRules.level(room.level, room.wave, L2HubX10Config.DEFENCE_LEVEL_OFFSET, L2HubX10Config.DEFENCE_LEVEL_STEP);
		final int variant = room.modifier == 2 ? 1 + index % 2 : 0;
		final Spawn spawn = new Spawn(NpcData.getInstance().getTemplate(901000 + variant * 100 + level));
		final int x = room.center.getX() + ThreadLocalRandom.current().nextInt(-350, 351);
		final int y = room.center.getY() + 850 + ThreadLocalRandom.current().nextInt(-200, 201);
		final int z = org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().getHeight(x, y, room.center.getZ());
		spawn.setXYZ(x, y, z);
		spawn.setInstanceId(room.instance.getId()); spawn.stopRespawn();
		final Npc npc = spawn.doSpawn(false);
		if (npc == null) { throw new IllegalStateException("Defence spawn failed"); }
		npc.getVariables().set("L2HubX10Event", true);
		npc.getVariables().set("L2HubX10MiniBoss", boss);
		npc.setName(boss ? text("Предводитель нашествия", "Invasion commander") : text("Захватчик", "Invader"));
		npc.setTitle(text("Волна ", "Wave ") + room.wave);
		npc.addStatFunc(new FuncMul(Stat.MAX_HP, 0x70, room, X10DefenceRules.hp(room.wave, L2HubX10Config.DEFENCE_HP_GROWTH, room.modifier == 1 ? L2HubX10Config.DEFENCE_ELITE_HP : 1, boss ? L2HubX10Config.DEFENCE_BOSS_HP : 1), null));
		if (boss || room.modifier == 1)
		{
			npc.addStatFunc(new FuncMul(Stat.POWER_ATTACK, 0x70, room, L2HubX10Config.DEFENCE_ELITE_ATTACK, null));
			npc.addStatFunc(new FuncMul(Stat.MAGIC_ATTACK, 0x70, room, L2HubX10Config.DEFENCE_ELITE_ATTACK, null));
		}
		if (room.modifier == 3) { npc.addStatFunc(new FuncMul(Stat.MOVE_SPEED, 0x70, room, L2HubX10Config.DEFENCE_SPEED, null)); }
		npc.setCurrentHp(npc.getMaxHp());
		room.spawns.add(spawn); MOBS.put(npc.getObjectId(), room); HEALTH.put(npc.getObjectId(), npc.getCurrentHp());
	}

	private static void groups(Room room)
	{
		if (kind == Kind.FARM) { farmGroups(room); return; }
		Player leader = room.players.stream().map(member -> member.player).filter(player -> player.isInParty() && player.getParty().getMemberCount() < 9
			&& (kind != Kind.DEFENCE || room.partyEntry)
			&& (kind != Kind.FARM || room.players.stream().filter(m -> m.player.getParty() == player.getParty()).count() == player.getParty().getMemberCount())
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
		if (kind == Kind.FARM)
		{
			final Location point = X10FarmArenas.point(room.center, ThreadLocalRandom.current().nextInt(-400,401), ThreadLocalRandom.current().nextInt(-400,401), room.instance.getId());
			spawn.setXYZ(point.getX(), point.getY(), point.getZ());
		}
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

	// Личные записи из мирной пати не перегруппировываются. В исходные пати
	// добираются только свободные боты; одиночки создают свои временные группы.
	private static void farmGroups(Room room)
	{
		final List<Player> leaders = room.players.stream().map(m -> m.player).filter(p -> p.isInParty() && p.getParty().isLeader(p)
			&& p.getParty().getMembers().stream().allMatch(ally -> MEMBERS.containsKey(ally.getObjectId()) && MEMBERS.get(ally.getObjectId()).room == room)).toList();
		for (Member m : room.players)
		{
			if (m.player.isInParty()) { continue; }
			Player leader = PhantomManager.getInstance().isPhantom(m.player) ? leaders.stream().filter(p -> p.getParty().getMemberCount() < 9).findFirst().orElse(null) : null;
			if (leader == null)
			{
				leader = room.players.stream().filter(other -> other.joinedByEvent && other.player.isInParty() && other.player.getParty().getMemberCount() < 9 && leaders.stream().noneMatch(original -> original.getParty() == other.player.getParty())).map(other -> other.player).findFirst().orElse(null);
			}
			if (leader == null) { PhantomCompat.newParty(m.player, PartyDistributionType.RANDOM); }
			else { m.player.joinParty(leader.getParty()); }
			m.joinedByEvent = true; m.joinedParty = m.player.getParty();
		}
	}

	private record Herb(Item item, int boost, long expires) { }
	private static final ThreadLocal<Boolean> SPLASH = ThreadLocal.withInitial(() -> false);
	private static long goldenDelay()
	{
		return ThreadLocalRandom.current().nextLong(Math.max(5, L2HubX10Config.FARM_GOLDEN_MIN), Math.max(5, Math.max(L2HubX10Config.FARM_GOLDEN_MIN, L2HubX10Config.FARM_GOLDEN_MAX)) + 1) * 1000L;
	}
	private static void freezeSquads(Room room)
	{
		for (Member member : room.players)
		{
			member.squad = member.player.isInParty() ? room.players.stream().filter(m -> m.player.getParty() == member.player.getParty()).mapToInt(m -> m.player.getObjectId()).min().orElse(member.player.getObjectId()) : member.player.getObjectId();
		}
	}
	private static int squadKills(Room room, int squad) { return room.players.stream().filter(m -> m.squad == squad).mapToInt(m -> m.score.kills).sum(); }
	private static List<Integer> squads(Room room)
	{
		return room.players.stream().map(m -> m.squad).distinct().sorted(Comparator.comparingInt((Integer id) -> squadKills(room, id)).reversed().thenComparingInt(Integer::intValue)).toList();
	}
	private static int squadRank(Room room, int squad)
	{
		return 1 + (int) squads(room).stream().filter(id -> squadKills(room, id) > squadKills(room, squad)).count();
	}
	private static String farmScore(Member m, long now)
	{
		return text("Зачистка ", "Monster rush ") + timeLeft(duration() - (now - started))
			+ text(" · Ты: ", " · You: ") + m.score.kills + text(" · Пати: ", " · Party: ") + squadKills(m.room, m.squad)
			+ text(" · Место ", " · Place ") + squadRank(m.room, m.squad) + "/" + squads(m.room).size()
			+ (m.boosts[0] > now ? " · DMG x2" : "") + (m.boosts[1] > now ? text(" · СКОРОСТЬ", " · SPEED") : "") + (m.boosts[2] > now ? " · AoE" : "")
			+ (m.room.bossKilled ? text(" · Босс убит!", " · Boss defeated!") : m.room.bossSpawned ? text(" · БОСС!", " · BOSS!") : "");
	}
	private static String farmTable(Room room)
	{
		final StringBuilder out = new StringBuilder("<br><font color=\"LEVEL\">").append(X10Text.escape(room.city)).append(text(" — гонка пати", " — party race")).append("</font><br1>");
		for (int id : squads(room))
		{
			final Member leader = room.players.stream().filter(m -> m.player.getObjectId() == id).findFirst().orElseThrow();
			out.append(squadRank(room, id)).append(". ").append(X10Text.escape(leader.player.getName())).append(" · ").append(squadKills(room, id)).append(text(" убийств · фонд места ", " kills · placement pool "))
				.append(X10FarmRules.rankBonus(squadRank(room, id), squads(room).size(), L2HubX10Config.FARM_RANK_FIRST, L2HubX10Config.FARM_RANK_LAST)).append("<br1>");
		}
		return out.toString();
	}
	private static Map<Integer, Long> farmRewards(Room room, long elapsed)
	{
		final Map<Integer, Long> result = new java.util.HashMap<>();
		for (int id : squads(room))
		{
			final List<Member> eligible = room.players.stream().filter(m -> m.squad == id && !PhantomManager.getInstance().isPhantom(m.player) && m.score.eligible(elapsed, L2HubX10Config.ACTIVITY)).sorted(Comparator.comparingInt(m -> m.player.getObjectId())).toList();
			final long pool = X10FarmRules.rankBonus(squadRank(room, id), squads(room).size(), L2HubX10Config.FARM_RANK_FIRST, L2HubX10Config.FARM_RANK_LAST);
			for (int i = 0; i < eligible.size(); i++) { result.put(eligible.get(i).player.getObjectId(), options(Kind.FARM).coins() + X10FarmRules.share(pool, i, eligible.size())); }
		}
		if (room.bossKilled)
		{
			for (Member m : room.players) { if (!PhantomManager.getInstance().isPhantom(m.player)) { result.merge(m.player.getObjectId(), (long) L2HubX10Config.FARM_BOSS_COINS, Long::sum); } }
		}
		return result;
	}
	private static void creditFarm(Player player, long coins, String source)
	{
		if (coins <= 0 || PhantomManager.getInstance().isPhantom(player)) { return; }
		player.getVariables().set("L2HubX10PendingRewards", player.getVariables().getString("L2HubX10PendingRewards", "") + source + "," + coins + ",0|");
		player.getVariables().storeMe();
		try { retryReward(player); }
		catch (RuntimeException e) { LOGGER.log(Level.WARNING, "X10 farm credit deferred: " + player.getObjectId(), e); }
	}
	private static void goldenReward(Room room, Member killer)
	{
		final List<Member> recipients = room.players.stream().filter(m -> m.squad == killer.squad && !PhantomManager.getInstance().isPhantom(m.player)).sorted(Comparator.comparingInt(m -> m.player.getObjectId())).toList();
		for (int i = 0; i < recipients.size(); i++)
		{
			final Member recipient = recipients.get(i);
			final Player current = World.getPlayer(recipient.player.getObjectId());
			if (current != null && current != recipient.player && current.isOnline()) { recover(current); }
			final Player player = recipient.player;
			final long coins = X10FarmRules.share(L2HubX10Config.FARM_GOLDEN_COINS, i, recipients.size());
			creditFarm(player, coins, "event:gold:" + sequence + ":" + room.instance.getId() + ":" + room.goldenSequence);
			if (player.isOnline()) { notice(player, text("Золотой моб пойман! Твоя доля: ", "Golden monster caught! Your share: ") + coins); }
		}
	}
	private static Attackable spawnSpecial(Room room, boolean golden) throws Exception
	{
		final Spawn spawn = new Spawn(NpcData.getInstance().getTemplate((golden ? 902000 : 902100) + room.level));
		final int side = ThreadLocalRandom.current().nextInt(4);
		final Location point = X10FarmArenas.point(room.center, side == 1 ? 500 : side == 3 ? -500 : 0, side == 0 ? -500 : side == 2 ? 500 : 0, room.instance.getId());
		spawn.setXYZ(point.getX(), point.getY(), point.getZ()); spawn.setInstanceId(room.instance.getId()); spawn.stopRespawn();
		final Attackable npc = (Attackable) spawn.doSpawn(false);
		if (npc == null) { throw new IllegalStateException("Farm special spawn failed"); }
		npc.getVariables().set("L2HubX10Event", true);
		npc.setName(golden ? text("Золотой беглец", "Golden runner") : text("Хозяин арены", "Arena overlord"));
		npc.setTitle(golden ? text("Поймай меня!", "Catch me!") : text("ФИНАЛ", "FINALE"));
		if (golden)
		{
			room.goldenId = npc.getObjectId(); room.goldenSequence++;
			room.goldenExpires = System.currentTimeMillis() + L2HubX10Config.FARM_GOLDEN_LIFE * 1000L;
			npc.disableCoreAI(true); npc.setRunning();
			npc.addStatFunc(new FuncMul(Stat.MOVE_SPEED, 0x70, room, L2HubX10Config.FARM_GOLDEN_SPEED, null));
		}
		else
		{
			room.bossId = npc.getObjectId(); room.bossSpawned = true;
			npc.addStatFunc(new FuncMul(Stat.MAX_HP, 0x70, room, L2HubX10Config.FARM_BOSS_HP * room.players.size(), null));
		}
		npc.setCurrentHp(npc.getMaxHp());
		room.spawns.add(spawn); MOBS.put(npc.getObjectId(), room); HEALTH.put(npc.getObjectId(), npc.getCurrentHp());
		final String direction = switch (side) { case 0 -> text("на севере", "to the north"); case 1 -> text("на востоке", "to the east"); case 2 -> text("на юге", "to the south"); default -> text("на западе", "to the west"); };
		for (Member m : room.players) { if (m.player.isOnline()) { notice(m.player, golden ? text("Золотой беглец ", "Golden runner ") + direction + "!" : text("Финальный босс! Убейте до конца времени!", "Final boss! Defeat it before time runs out!")); } }
		return npc;
	}
	private static void farmTick(Room room, long now) throws Exception
	{
		if (room.closed) { return; }
		for (Member m : room.players)
		{
			if (!m.player.isOnline() || m.player.isAlikeDead() || m.player.getInstanceId() != room.instance.getId()) { clearBoosts(m); }
		}
		for (Herb h : new ArrayList<>(room.herbs.values()))
		{
			if (now >= h.expires()) { room.herbs.remove(h.item().getObjectId()); destroyHerb(h.item()); continue; }
			final Player bot = room.players.stream().map(m -> m.player).filter(p -> PhantomManager.getInstance().isPhantom(p) && p.isOnline() && !p.isAlikeDead() && p.getInstanceId() == room.instance.getId() && p.calculateDistance3D(h.item()) <= 100).findFirst().orElse(null);
			if (bot != null) { pickupHerb(bot, h.item()); }
		}
		if (room.goldenId != 0)
		{
			if (World.findObject(room.goldenId) instanceof Attackable npc)
			{
				if (npc.isAlikeDead()) { /* Асинхронный kill-listener ещё должен получить цель. */ }
				else if (now >= room.goldenExpires)
				{
					MOBS.remove(npc.getObjectId()); HEALTH.remove(npc.getObjectId()); room.spawns.remove(npc.getSpawn()); room.goldenId = 0; npc.deleteMe();
					room.players.forEach(m -> { if (m.player.isOnline()) { m.player.sendMessage(text("Золотой беглец скрылся.", "The golden runner escaped.")); } });
				}
				else
				{
					final Player nearest = room.players.stream().map(m -> m.player).filter(p -> p.isOnline() && !p.isAlikeDead() && p.getInstanceId() == room.instance.getId()).min(Comparator.comparingDouble(npc::calculateDistance3D)).orElse(null);
					if (nearest != null)
					{
						final double angle = Math.atan2(npc.getY() - nearest.getY(), npc.getX() - nearest.getX()) + ThreadLocalRandom.current().nextDouble(-0.5,0.5);
						final int dx = (int) Math.clamp(npc.getX() - room.center.getX() + Math.cos(angle)*250,-550,550);
						final int dy = (int) Math.clamp(npc.getY() - room.center.getY() + Math.sin(angle)*250,-550,550);
						final Location destination = X10FarmArenas.point(room.center, dx, dy, room.instance.getId());
						final Location valid = org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().getValidLocation(npc.getX(), npc.getY(), npc.getZ(), destination.getX(), destination.getY(), destination.getZ(), room.instance.getId());
						npc.getAI().setIntentionMoveTo(valid);
					}
				}
			}
			else { room.goldenId = 0; }
		}
		else if (L2HubX10Config.FARM_GOLDEN && now >= room.nextGolden)
		{
			spawnSpecial(room, true); room.nextGolden = now + goldenDelay();
		}
		if (L2HubX10Config.FARM_BOSS && !room.bossSpawned && now - started >= X10FarmRules.bossAt(duration(), L2HubX10Config.FARM_BOSS_LAST)) { spawnSpecial(room, false); }
	}
	private static void dropHerb(Room room, Attackable mob)
	{
		final int boost = ThreadLocalRandom.current().nextInt(3);
		final Item item = ItemManager.createItem(ItemProcessType.NONE, new int[] {8606,8611,8612}[boost], 1, null);
		item.setInstanceId(room.instance.getId());
		room.herbs.put(item.getObjectId(), new Herb(item, boost, System.currentTimeMillis() + 30000));
		item.dropMe(mob, mob.getX(), mob.getY(), mob.getZ());
		// Временный усилитель не сохраняется как обычный дроп после перезапуска.
		org.l2jmobius.gameserver.managers.ItemsOnGroundManager.getInstance().removeObject(item);
	}
	/** Хук 0414 зовётся на каждый подбор предмета в мире: вне Зачистки — без замка класса (он же у тика ивента). */
	static boolean pickupHerb(Player player, Item item)
	{
		if (!running || (kind != Kind.FARM)) { return false; }
		return pickupHerbLocked(player, item);
	}

	private static synchronized boolean pickupHerbLocked(Player player, Item item)
	{
		for (Room room : ROOMS)
		{
			final Herb herb = room.herbs.remove(item.getObjectId());
			if (herb == null) { continue; }
			try
			{
				final Member m = MEMBERS.get(player.getObjectId());
				if (kind == Kind.FARM && running && !room.closed && m != null && m.room == room && !player.isAlikeDead() && player.getInstanceId() == room.instance.getId() && System.currentTimeMillis() < herb.expires() && player.calculateDistance3D(item) < 150)
				{
					m.boosts[herb.boost()] = System.currentTimeMillis() + L2HubX10Config.FARM_HERB_SECONDS * 1000L;
					if (herb.boost() == 1)
					{
						// Не затираем чужой Herb of Speed, если его принесли в ивент.
						final BuffInfo current = player.getEffectList().getBuffInfoBySkillId(2285);
						if (current == null || current == m.speedHerb)
						{
							SkillData.getInstance().getSkill(2285,1).applyEffects(player,player,true,L2HubX10Config.FARM_HERB_SECONDS);
							m.speedHerb = player.getEffectList().getBuffInfoBySkillId(2285);
						}
					}
					notice(player, text("Усилитель: ", "Booster: ") + new String[] {text("двойной урон", "double damage"), text("скорость", "speed"), text("массовый удар", "splash attacks")}[herb.boost()] + " · " + L2HubX10Config.FARM_HERB_SECONDS + text(" сек", " sec"));
				}
			}
			finally { destroyHerb(item); }
			return true;
		}
		return false;
	}
	private static void destroyHerb(Item item)
	{
		item.decayMe();
		org.l2jmobius.gameserver.managers.ItemsOnGroundManager.getInstance().removeObject(item);
		ItemManager.destroyItem(ItemProcessType.NONE,item,null,null);
	}
	private static void clearHerbs(Room room)
	{
		for (Herb herb : room.herbs.values()) { destroyHerb(herb.item()); }
		room.herbs.clear();
	}
	private static void clearBoosts(Member m)
	{
		java.util.Arrays.fill(m.boosts,0);
		if (m.speedHerb != null && m.player.getEffectList().getBuffInfoBySkillId(2285) == m.speedHerb) { m.player.getEffectList().remove(SkillFinishType.REMOVED,m.speedHerb); }
		m.speedHerb = null;
	}
	// Вызывается синхронно до списания HP. Ни PvP, ни обычный мир не усиливаются.
	/** Хук на каждый удар сервера: вне Зачистки — без блокировки. */
	static double farmDamage(Creature attacker, Creature victim, double damage, boolean dot)
	{
		if (!running || (kind != Kind.FARM) || MEMBERS.isEmpty() || (MOBS.get(victim.getObjectId()) == null)) { return damage; }
		return farmDamageLocked(attacker, victim, damage, dot);
	}

	private static synchronized double farmDamageLocked(Creature attacker, Creature victim, double damage, boolean dot)
	{
		final Player player = owner(attacker);
		final Member m = player == null ? null : MEMBERS.get(player.getObjectId());
		if (!running || kind != Kind.FARM || m == null || m.room.closed || !player.isOnline() || player.isAlikeDead() || player.getInstanceId() != m.room.instance.getId() || attacker.getInstanceId() != player.getInstanceId() || MOBS.get(victim.getObjectId()) != m.room || !enemy(player,victim) || SPLASH.get()) { return damage; }
		final long now = System.currentTimeMillis();
		final double result = m.boosts[0] > now ? damage*2 : damage;
		if (!dot && m.boosts[2] > now && now >= m.nextSplash && damage > 0)
		{
			m.nextSplash = now + L2HubX10Config.FARM_SPLASH_COOLDOWN * 1000L;
			SPLASH.set(true);
			try
			{
				int count = 0;
				for (var entry : MOBS.entrySet())
				{
					if (entry.getValue() != m.room || entry.getKey() == victim.getObjectId()) { continue; }
					if (World.findObject(entry.getKey()) instanceof Attackable other && enemy(player,other) && attacker.calculateDistance3D(other) <= L2HubX10Config.FARM_SPLASH_RADIUS && org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().canSeeTarget(attacker,other))
					{
						other.reduceCurrentHp(result * L2HubX10Config.FARM_SPLASH_PERCENT / 100.0,attacker,null);
						if (++count >= 10) { break; }
					}
				}
			}
			finally { SPLASH.set(false); }
		}
		return result;
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
		if ((member != null) && !member.eliminated && (MOBS.get(event.getTarget().getObjectId()) == member.room))
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
		if ((member == null) || member.eliminated || (event.getTargets() == null))
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
			if ((recipient == null) || recipient.eliminated || (recipient.room != member.room) || (ally.getTeam() != player.getTeam()) || (ally.getInstanceId() != player.getInstanceId()) || player.calculateDistance3D(ally) > 1500)
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
		if (!running || !event.getTarget().isPlayer()) { return; }
		if (kind == Kind.FARM)
		{
			final Member member = MEMBERS.get(event.getTarget().getObjectId());
			if (member != null) { clearBoosts(member); }
			return;
		}
		if (kind == Kind.DEFENCE)
		{
			final Member member = MEMBERS.get(event.getTarget().getObjectId());
			if (member != null && event.getTarget().getInstanceId() == member.room.instance.getId()) { eliminate(member); }
			return;
		}
		if (kind != Kind.HUNT) { return; }
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
		if (kind == Kind.FARM)
		{
			if (mob.getObjectId() == room.goldenId)
			{
				room.goldenId = 0;
				if (member != null && member.room == room && killer.getInstanceId() == room.instance.getId()) { goldenReward(room, member); }
				return;
			}
			if (mob.getObjectId() == room.bossId)
			{
				room.bossKilled = member != null && member.room == room && killer.getInstanceId() == room.instance.getId();
				if (!room.bossKilled) { return; }
				room.players.forEach(m -> { if (m.player.isOnline()) { notice(m.player, text("Босс повержен! Бонус каждому: ", "Boss defeated! Bonus for everyone: ") + L2HubX10Config.FARM_BOSS_COINS); } });
				return;
			}
			if (L2HubX10Config.FARM_HERBS && ThreadLocalRandom.current().nextInt(100) < L2HubX10Config.FARM_HERB_CHANCE) { dropHerb(room, mob); }
		}
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
		final Member member = player == null ? null : MEMBERS.get(player.getObjectId());
		return running && member != null && member.player == player && !member.room.closed
			&& player.isOnEvent();
	}

	// Союзники определяются площадкой и ролью Охоты, а не кланом или размером пати.
	static synchronized List<Player> allies(Player player, int range)
	{
		if (!contains(player) || eliminated(player)) { return List.of(); }
		final Member member = MEMBERS.get(player.getObjectId());
		return member.room.players.stream().filter(other -> contains(other.player) && !eliminated(other.player) && other.player.getInstanceId() == member.room.instance.getId()
			&& (kind != Kind.HUNT || (other == member.room.quarry) == (member == member.room.quarry)))
			.map(other -> other.player).filter(ally -> ally.isOnline() && !ally.isTeleporting()
				&& player.getInstanceId() == member.room.instance.getId() && player.calculateDistance3D(ally) <= range).toList();
	}

	static synchronized boolean enemy(Player player, Creature target)
	{
		if (!contains(player) || eliminated(player) || player.getInstanceId() != MEMBERS.get(player.getObjectId()).room.instance.getId() || target == null || target == player || target.isAlikeDead()
			|| target.getInstanceId() != player.getInstanceId()) { return false; }
		final Member member = MEMBERS.get(player.getObjectId());
		if (kind == Kind.HUNT)
		{
			final Member other = MEMBERS.get(target.getObjectId());
			return member.room.quarry != null && !member.room.quarryKilled && other != null && other.room == member.room
				&& contains(other.player) && other.player.isOnline() && !other.player.isTeleporting()
				&& (member == member.room.quarry) != (other == member.room.quarry);
		}
		return target instanceof Attackable npc && MOBS.get(npc.getObjectId()) == member.room
			&& npc.getVariables().getBoolean("L2HubX10Event", false);
	}

	static synchronized Creature target(Player player)
	{
		final Member member = MEMBERS.get(player.getObjectId());
		if (!contains(player))
		{
			return null;
		}
		if (kind == Kind.HUNT)
		{
			final Room room = member.room;
			if (room.quarry == null || room.quarryKilled) { return null; }
			if (member != room.quarry) { return enemy(player, room.quarry.player) ? room.quarry.player : null; }
			final Player nearest = room.players.stream().filter(other -> enemy(player, other.player)).map(other -> other.player).min(Comparator.comparingDouble(player::calculateDistance3D)).orElse(null);
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
		if (kind == Kind.FARM)
		{
			for (int id : new int[] {member.room.goldenId, member.room.bossId})
			{
				if (World.findObject(id) instanceof Attackable npc && enemy(player, npc)) { return npc; }
			}
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
			if ((object instanceof Attackable npc) && enemy(player, npc))
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
				clearBoosts(member);
				member.player.removeStatsOwner(member.room);
				member.player = player;
				player.setOnEvent(true);
				player.setTeam(member == member.room.quarry ? Team.RED : Team.BLUE);
				member.room.instance.addPlayer(player.getObjectId());
				player.teleToLocation(member.room.center, member.room.instance.getId(), 100);
				if (member == member.room.quarry) { boostQuarry(member.room); }
				if (member.eliminated && !player.isDead()) { player.doDie(null); }
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

	private static void finishDefenceRoom(Room room, boolean rewards, boolean completed)
	{
		if (room.closed) { return; }
		room.closed = true;
		for (Spawn spawn : room.spawns)
		{
			spawn.stopRespawn();
			if (spawn.getLastSpawn() != null)
			{
				MOBS.remove(spawn.getLastSpawn().getObjectId()); HEALTH.remove(spawn.getLastSpawn().getObjectId());
				spawn.getLastSpawn().deleteMe();
			}
		}
		for (Member member : room.players)
		{
			final Player current = World.getPlayer(member.player.getObjectId());
			if (current != null && current.isOnline()) { member.player = current; }
			final Player player = member.player;
			MEMBERS.remove(player.getObjectId());
			try
			{
				player.abortAttack(); player.abortCast(); player.setTarget(null);
				final long coins = rewards ? member.waveCoins : 0;
				final int style = rewards && completed && !member.eliminated && !player.isDead() && member.completedWaves > 0 ? options(Kind.DEFENCE).style() : 0;
				if (rewards && !PhantomManager.getInstance().isPhantom(player) && (coins > 0 || style > 0))
				{
					final String source = "event:defence:" + sequence;
					final String pending = player.getVariables().getString("L2HubX10PendingRewards", "");
					player.getVariables().set("L2HubX10PendingRewards", pending + source + "," + coins + "," + style + "|");
					player.getVariables().storeMe();
					try { retryReward(player); }
					catch (RuntimeException e) { LOGGER.log(Level.WARNING, "X10 defence credit deferred: " + player.getObjectId(), e); }
				}
				final String result = text("Оборона завершена. Волн пати: ", "Defence complete. Party waves: ") + room.completedWaves
					+ text("; твоих: ", "; yours: ") + member.completedWaves + text(". Коины: ", ". Coins: ") + coins + text(". Жетоны стиля: ", ". Style Tokens: ") + style;
				player.getVariables().set("L2HubX10DefenceResult", result); player.getVariables().storeMe();
				if (player.isOnline()) { notice(player, rewards ? result : text("Оборона отменена без наград.", "Defence cancelled without rewards.")); }
			}
			catch (Exception e) { LOGGER.log(Level.WARNING, "X10 defence finalization failed: " + player.getObjectId(), e); }
			finally
			{
				try
				{
					if (member.joinedByEvent && player.isInParty() && player.getParty() == member.joinedParty) { player.getParty().removePartyMember(player, PartyMessageType.LEFT); }
					if (player.isOnline()) { recover(player); }
				}
				catch (Exception e) { LOGGER.log(Level.WARNING, "X10 defence return failed: " + player.getObjectId(), e); }
			}
		}
		InstanceManager.getInstance().destroyInstance(room.instance.getId());
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
			PARTY_QUEUE.clear();
			if (schedule != null) { scheduleNext(); }
			return;
		}
		if (kind == Kind.DEFENCE)
		{
			for (Room room : ROOMS) { finishDefenceRoom(room, rewards, rewards); }
			running = false; ROOMS.clear(); PARTY_QUEUE.clear();
			schedule.finish(X10World.activeSeconds()); saveSchedule(); scheduleNext();
			return;
		}
		final Map<Integer, Long> farmRewards = new java.util.HashMap<>();
		if (kind == Kind.FARM && rewards)
		{
			for (Room room : ROOMS) { farmRewards.putAll(farmRewards(room, elapsed)); }
		}
		running = false;
		final List<Member> members = new ArrayList<>(MEMBERS.values());
		for (Room room : ROOMS)
		{
			room.closed = true;
			clearHerbs(room);
			for (Member member : room.players) { clearBoosts(member); }
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
				if (rewards && (kind == Kind.FARM ? farmRewards.containsKey(player.getObjectId()) : member.score.eligible(elapsed, L2HubX10Config.ACTIVITY)))
				{
					final long coins = kind == Kind.FARM ? farmRewards.get(player.getObjectId()) : kind == Kind.HUNT ? X10EventRules.huntCoins(options(kind).coins(), elapsed, duration, member == member.room.quarry ? member.room.slain.coins(L2HubX10Config.HUNT_KILL_BONUS, L2HubX10Config.HUNT_BONUS_CAP) : member.room.winningSquad.contains(player.getObjectId()) ? L2HubX10Config.HUNT_GROUP_BONUS : 0) : options(kind).coins();
					final int style = elapsed >= duration && member.score.eligible(elapsed, L2HubX10Config.ACTIVITY) ? options(kind).style() : 0;
					final String source = "event:" + day + ":" + sequence;
					final String pending = player.getVariables().getString("L2HubX10PendingRewards", "");
					player.getVariables().set("L2HubX10PendingRewards", pending + source + "," + coins + "," + style + "|");
					player.getVariables().storeMe();
					try { retryReward(player); }
					catch (RuntimeException e) { LOGGER.log(Level.WARNING, "X10 final credit deferred: " + player.getObjectId(), e); }
					if (kind == Kind.FARM) { player.getVariables().set("L2HubX10FarmResult", farmScore(member, started + elapsed) + text(". Награда: ", ". Reward: ") + coins); player.getVariables().storeMe(); }
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
