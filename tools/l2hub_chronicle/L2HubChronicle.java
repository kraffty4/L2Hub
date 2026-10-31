/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package custom.L2HubChronicle;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.SuperChampion;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomNews;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLevelChanged;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerProfessionChange;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.script.LongTimeEvent;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * L2Hub: «Летопись мира» — лента заметных событий для лаунчера, log/L2HubChronicle.json (последние MAX записей, старые
 * — из того же файла после перезапуска). Убиты рейд-босс, суперчемпион или эпик (кем: игрок, охотник, пати); эпик встал;
 * итоги PvP-ивентов TvT, Deathmatch, CtF (счёт из их скриптов, когда участники вернулись с арены); сезонное событие началось
 * или кончилось; пришли эльпи; охотник или игрок взял профессию или уровень 20, 40, 52, 61, 76; итоги дуэлей с охотниками (очередь
 * PhantomNews модуля фантомов). Убийства, уровни и профессии —
 * глобальные слушатели Mobius, остальное — опрос раз в POLL мс без врезок в чужие скрипты. Только пишет — ничего не меняет.
 * @author L2Hub
 */
public class L2HubChronicle
{
	private static final Logger LOGGER = Logger.getLogger(L2HubChronicle.class.getName());
	private static final Path FILE = Paths.get("./log/L2HubChronicle.json");
	private static final int MAX = 200;
	/** Эльпи скрипта Elpies (у всех хроник Mobius — 900100). */
	private static final int ELPY = 900100;
	private static final long POLL = 10000;
	private static final int[] MILESTONES =
	{
		20,
		40,
		52,
		61,
		76
	};
	private static final String[] PVP =
	{
		"TvT",
		"Deathmatch",
		"CtF"
	};

	private static final Deque<String> ENTRIES = new ArrayDeque<>();
	/** Опрос: что было на прошлом шаге (null — ещё не было шага: первый только запоминает). */
	private static Set<Integer> _epicsAlive;
	private static Map<String, Boolean> _seasonal;
	private static Boolean _elpies;
	private static final Map<String, Boolean> PVP_FIGHTING = new HashMap<>();
	private static final Map<String, Integer> PVP_REPORTED = new HashMap<>();
	/** Охотники бьют суперчемпионов часто, и лента из них одних уже не лента: их победа — запись не чаще этого; победа с живым — всегда. */
	private static final long SUPER_GAP = 7200000;
	private static long _nextSuper;
	/** Сколько побед охотников не попало в ленту: следующая запись — одним числом. */
	private static int _skippedSupers;

	private L2HubChronicle()
	{
	}

	// ---------- Запись ----------

	private static synchronized void add(String kind, String text, String who, String where, Integer x, Integer y)
	{
		final StringBuilder sb = new StringBuilder("{\"time\":").append(System.currentTimeMillis());
		field(sb.append(','), "kind", kind);
		field(sb.append(','), "text", text);
		nullable(sb, "who", who);
		nullable(sb, "where", where);
		sb.append(",\"x\":").append(x == null ? "null" : x.toString()).append(",\"y\":").append(y == null ? "null" : y.toString()).append('}');
		ENTRIES.addLast(sb.toString());
		while (ENTRIES.size() > MAX)
		{
			ENTRIES.removeFirst();
		}
		write();
	}

	private static void write()
	{
		final StringBuilder sb = new StringBuilder("{\"version\":1,\"time\":").append(System.currentTimeMillis()).append(",\"entries\":[\n");
		boolean first = true;
		for (String entry : ENTRIES)
		{
			sb.append(first ? "" : ",\n").append(entry);
			first = false;
		}
		sb.append("\n]}\n");
		try
		{
			final Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
			Files.writeString(tmp, sb, StandardCharsets.UTF_8);
			Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubChronicle: " + FILE + ": " + e.getMessage());
		}
	}

	/**
	 * Записи прошлых запусков: в файле каждая — своей строкой, начинается с {"time":.
	 */
	private static synchronized void load()
	{
		if (!Files.isRegularFile(FILE))
		{
			return;
		}

		try
		{
			for (String line : Files.readAllLines(FILE, StandardCharsets.UTF_8))
			{
				final String entry = line.endsWith(",") ? line.substring(0, line.length() - 1) : line;
				if (entry.startsWith("{\"time\":") && entry.endsWith("}"))
				{
					ENTRIES.addLast(entry);
				}
			}
			while (ENTRIES.size() > MAX)
			{
				ENTRIES.removeFirst();
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubChronicle: " + FILE + ": " + e.getMessage());
		}
	}

	private static StringBuilder field(StringBuilder sb, String name, String value)
	{
		sb.append('"').append(name).append("\":\"");
		for (char c : String.valueOf(value).toCharArray())
		{
			switch (c)
			{
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default -> sb.append(c < 0x20 ? String.format("\\u%04x", (int) c) : String.valueOf(c));
			}
		}
		return sb.append('"');
	}

	private static void nullable(StringBuilder sb, String name, String value)
	{
		if (value == null)
		{
			sb.append(",\"").append(name).append("\":null");
		}
		else
		{
			field(sb.append(','), name, value);
		}
	}

	// ---------- Кто ----------

	/**
	 * Охотник — фантом L2Hub (модуль фантомов: у них свой клиент-заглушка, по клиенту их не отличить).
	 */
	private static boolean hunter(Player player)
	{
		return PhantomManager.getInstance().isPhantom(player);
	}

	/**
	 * @return убил охотник, и в его пати нет живых
	 */
	private static boolean onlyHunters(Player player)
	{
		if (!hunter(player))
		{
			return false;
		}
		final Party party = player.getParty();
		return (party == null) || party.getMembers().stream().allMatch(L2HubChronicle::hunter);
	}

	/**
	 * «игрок Имя», «охотник Имя», «пати Лидера (5)», «охотники Лидера (5)».
	 */
	private static String who(Player player)
	{
		final Party party = player.getParty();
		if ((party != null) && (party.getMemberCount() > 1))
		{
			final Player leader = party.getLeader();
			return (hunter(leader) ? "охотники " : "пати ") + leader.getName() + " (" + party.getMemberCount() + ")";
		}
		return (hunter(player) ? "охотник " : "игрок ") + player.getName();
	}

	private static String town(Npc npc)
	{
		try
		{
			return MapRegionData.getInstance().getClosestTownName(npc);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	// ---------- Слушатели ----------

	private static void onKill(OnAttackableKill event)
	{
		final Attackable target = event.getTarget();
		final Player killer = event.getAttacker();
		if ((target == null) || (killer == null))
		{
			return;
		}

		if (SuperChampion.isSuper(target) && !target.isRaid() && onlyHunters(killer))
		{
			final long now = System.currentTimeMillis();
			if (now < _nextSuper)
			{
				_skippedSupers++;
				return;
			}
			_nextSuper = now + SUPER_GAP;
			if (_skippedSupers > 0)
			{
				final String name = target.getName() + " (" + target.getLevel() + ")";
				final int count = _skippedSupers + 1;
				_skippedSupers = 0;
				add("super_killed", "en".equals(org.l2jmobius.gameserver.config.custom.L2HubWorldConfig.LANGUAGE) ? "Hunters killed " + count + " super champions, the last one " + name : "Охотники убили суперчемпионов: " + count + ", последний — " + name, who(killer), town(target), target.getX(), target.getY());
				return;
			}
		}

		final String by = who(killer);
		final String kind;
		final String text;
		if (target instanceof GrandBoss)
		{
			kind = "epic_killed";
			text = "Эпик " + target.getName() + " (" + target.getLevel() + ") повержен: " + by;
		}
		else if (target.isRaid() && !target.isRaidMinion() && !target.isMinion())
		{
			kind = "raid_killed";
			text = "Рейд-босс " + target.getName() + " (" + target.getLevel() + ") убит: " + by;
		}
		else if (SuperChampion.isSuper(target))
		{
			kind = "super_killed";
			text = "Суперчемпион " + target.getName() + " (" + target.getLevel() + ") убит: " + by;
		}
		else
		{
			return;
		}
		add(kind, text, by, town(target), target.getX(), target.getY());
	}

	private static void onLevel(OnPlayerLevelChanged event)
	{
		final Player player = event.getPlayer();
		// Танатос (модуль фантомов) — секрет: его 76-й уровень и профа — не новость.
		if (PhantomManager.getInstance().isSecret(player))
		{
			return;
		}
		for (int level : MILESTONES)
		{
			if ((event.getOldLevel() < level) && (event.getNewLevel() >= level))
			{
				final String name = (hunter(player) ? "Охотник " : "Игрок ") + player.getName();
				add("level", name + " достиг " + level + "-го уровня", player.getName(), null, null, null);
			}
		}
	}

	private static void onProfession(OnPlayerProfessionChange event)
	{
		if (event.isSubClass() || (event.getTemplate() == null) || (event.getTemplate().getPlayerClass().level() < 1))
		{
			return;
		}
		final Player player = event.getPlayer();
		if (PhantomManager.getInstance().isSecret(player))
		{
			return;
		}
		final String name = (hunter(player) ? "Охотник " : "Игрок ") + player.getName();
		add("profession", name + " стал " + className(event.getTemplate().getPlayerClass().name()), player.getName(), null, null, null);
	}

	/**
	 * DARK_AVENGER → Dark Avenger.
	 */
	private static String className(String name)
	{
		final StringBuilder sb = new StringBuilder();
		for (String part : name.split("_"))
		{
			if (!part.isEmpty())
			{
				sb.append(sb.length() > 0 ? " " : "").append(part.charAt(0)).append(part.substring(1).toLowerCase());
			}
		}
		return sb.toString();
	}

	// ---------- Опрос ----------

	private static void poll()
	{
		try
		{
			pollEpics();
			pollSeasonal();
			pollElpies();
			pollNews();
			for (String name : PVP)
			{
				pollPvp(name);
			}
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubChronicle: " + e);
		}
	}

	/**
	 * Новости модуля фантомов (PhantomNews): итоги дуэлей с охотниками и между охотниками.
	 */
	private static void pollNews()
	{
		for (PhantomNews.Item item : PhantomNews.drain())
		{
			add(item.kind(), item.text(), item.who(), item.where(), item.x(), item.y());
		}
	}

	private static void pollEpics()
	{
		final Set<Integer> alive = new HashSet<>();
		for (int id : grandIds())
		{
			final GrandBoss boss = GrandBossManager.getInstance().getBoss(id);
			if ((boss != null) && boss.isSpawned() && !boss.isDead())
			{
				alive.add(id);
				if ((_epicsAlive != null) && !_epicsAlive.contains(id))
				{
					add("epic_spawned", "Эпик " + boss.getName() + " (" + boss.getLevel() + ") появился", null, town(boss), boss.getX(), boss.getY());
				}
			}
		}
		_epicsAlive = alive;
	}

	@SuppressWarnings("unchecked")
	private static List<Integer> grandIds()
	{
		try
		{
			final Field field = GrandBossManager.class.getDeclaredField("_storedInfo");
			field.setAccessible(true);
			return new ArrayList<>(((Map<Integer, ?>) field.get(null)).keySet());
		}
		catch (Exception e)
		{
			return List.of();
		}
	}

	private static void pollSeasonal()
	{
		final Map<String, Boolean> now = new HashMap<>();
		for (Quest quest : ScriptManager.getInstance().getScripts().values())
		{
			if (quest instanceof LongTimeEvent event)
			{
				final boolean active = event.isEventPeriod();
				now.put(quest.getName(), active);
				final Boolean before = _seasonal == null ? null : _seasonal.get(quest.getName());
				if ((before != null) && (before != active))
				{
					add(active ? "seasonal_start" : "seasonal_end", (active ? "Началось событие " : "Кончилось событие ") + quest.getName(), null, null, null, null);
				}
			}
		}
		_seasonal = now;
	}

	private static void pollElpies()
	{
		final Quest script = ScriptManager.getInstance().getScript("Elpies");
		final Object active = script == null ? null : staticField(script, "EVENT_ACTIVE");
		if (!(active instanceof Boolean on))
		{
			return;
		}
		if (on && Boolean.FALSE.equals(_elpies))
		{
			String where = null;
			Integer x = null;
			Integer y = null;
			if (instanceField(script, "_elpies") instanceof Collection<?> elpies)
			{
				for (Object npc : elpies)
				{
					if (npc instanceof Npc elpy)
					{
						where = town(elpy);
						x = elpy.getX();
						y = elpy.getY();
						break;
					}
				}
			}
			// У Elpies Classic и Essence набора _elpies нет (считают CURRENT_ELPY_COUNT), а Quest.addSpawn в SpawnTable не пишет —
			// эльпи ищем среди объектов мира (прогон classic-essence 2026-10-22).
			if (where == null)
			{
				final Npc elpy = World.getNpc(ELPY);
				if ((elpy != null) && !elpy.isDead())
				{
					where = town(elpy);
					x = elpy.getX();
					y = elpy.getY();
				}
			}
			add("elpies", "Нашествие эльпи", null, where, x, y); // город — в where: экран летописи пишет его рядом
		}
		_elpies = on;
	}

	/**
	 * Итог PvP-ивента: бой идёт, пока кто-то из PLAYER_SCORES скрипта на арене (isOnEvent); когда все вернулись — счёт ещё
	 * не стёрт (скрипт чистит его при следующем старте), его и пишем.
	 */
	@SuppressWarnings("unchecked")
	private static void pollPvp(String name)
	{
		final Quest script = ScriptManager.getInstance().getScript(name);
		if (!(staticField(script, "PLAYER_SCORES") instanceof Map<?, ?> raw))
		{
			return;
		}
		final Map<Player, Integer> scores = (Map<Player, Integer>) raw;
		boolean fighting = false;
		Player best = null;
		int bestScore = -1;
		for (Map.Entry<Player, Integer> entry : scores.entrySet())
		{
			fighting |= (entry.getKey() != null) && entry.getKey().isOnEvent();
			if ((entry.getKey() != null) && (entry.getValue() != null) && (entry.getValue() > bestScore))
			{
				best = entry.getKey();
				bestScore = entry.getValue();
			}
		}
		final boolean before = PVP_FIGHTING.getOrDefault(name, false);
		PVP_FIGHTING.put(name, fighting);
		if (!before || fighting || scores.isEmpty())
		{
			return;
		}
		// Счёт живёт до следующего старта скрипта, а его участник уходит на другой ивент и возвращается: тот же счёт второй раз не пишем.
		final int signature = scores.hashCode();
		final Integer reported = PVP_REPORTED.put(name, signature);
		if ((reported != null) && (reported == signature))
		{
			return;
		}

		final StringBuilder text = new StringBuilder(name).append(": ");
		if ((staticField(script, "BLUE_SCORE") instanceof Integer blue) && (staticField(script, "RED_SCORE") instanceof Integer red))
		{
			text.append(blue > red ? "победили синие " + blue + ":" + red : red > blue ? "победили красные " + red + ":" + blue : "ничья " + blue + ":" + red);
			if (best != null)
			{
				text.append(", лучший — ").append(best.getName()).append(" (убийств: ").append(bestScore).append(")");
			}
		}
		else if (best != null)
		{
			text.append("победил ").append(best.getName()).append(" (убийств: ").append(bestScore).append(")");
		}
		text.append(", участников ").append(scores.size());
		add("pvp_result", text.toString(), best == null ? null : best.getName(), null, null, null);
	}

	private static Object staticField(Object script, String name)
	{
		try
		{
			final Field field = script.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(null);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private static Object instanceField(Object script, String name)
	{
		try
		{
			final Field field = script.getClass().getDeclaredField(name);
			field.setAccessible(true);
			return field.get(script);
		}
		catch (Exception e)
		{
			return null;
		}
	}

	public static void main(String[] args)
	{
		load();
		final L2HubChronicle owner = new L2HubChronicle();
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> onKill(event), owner));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_LEVEL_CHANGED, (OnPlayerLevelChanged event) -> onLevel(event), owner));
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_PROFESSION_CHANGE, (OnPlayerProfessionChange event) -> onProfession(event), owner));
		ThreadPool.scheduleAtFixedRate(L2HubChronicle::poll, 30000, POLL);
		LOGGER.info("L2HubChronicle: летопись мира — " + FILE + ", записей из прошлых запусков: " + ENTRIES.size() + ".");
	}
}
