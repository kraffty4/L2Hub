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
package custom.L2HubTownFakes;

import java.io.Reader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.SuperChampion;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomRaid;
import org.l2jmobius.gameserver.mechanics.script.LongTimeEvent;
import org.l2jmobius.gameserver.mechanics.script.Quest;
import org.l2jmobius.gameserver.mechanics.script.Script;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.SocialAction;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * L2Hub: фейки в городах — игроки-NPC Mobius (шаблоны с fakePlayer) во всех городах и деревнях. Кто где и чем занят, сгенерировано
 * tools/l2hub_town_fakes.py в data/L2HubTownFakes.xml: у каждого роль (торговец, скупщик и крафтер на лавке, AFK у склада, болтун у
 * гейткипера, новичок, ищет пати, бегает по делам, понторез, гуляка, полуночник) и места на утро и день, вечер и ночь по игровому
 * времени — на часть суток фейк уходит из игры (ночью их меньше). Ходоки ходят по маршруту, остальные стоят или сидят и изредка жестикулируют.
 * Говорят, только если рядом живой игрок: реплики в общий чат, торговцы — в торговый (не чаще TownFakesChatMinutes на город), плюс короткие
 * реакции на события мира — суперчемпион, рейд охотников, эпик, сезонное событие, PvP-ивент. Реплики пишут мозги фейков (FakePlayerBrain,
 * запрос "kind": "town"), без них — заготовки data/L2HubTownFakesChat.xml на языке L2HubLanguage. Фейки — в SpawnTable, поэтому шёпот им
 * идёт обычным путём FakePlayerChatManager.
 * <p>
 * Крючок для других модулей (Танатос и др.): {@code ScriptManager.getInstance().getScript("L2HubTownFakes").notifyEvent("world:<вид>:<x>,<y>:<текст>", null, null)}
 * — фейки в городах с живыми игроками откликнутся репликами из «event.<вид>» (место — ближайший город к x,y; {text} — текст).
 * @author L2Hub
 */
public class L2HubTownFakes extends Script
{
	private static final Logger LOGGER = Logger.getLogger(L2HubTownFakes.class.getName());
	private static final Path CONFIG = Paths.get("./config/Custom/L2HubTownFakes.ini");
	private static final Path WORLD_CONFIG = Paths.get("./config/Custom/L2HubWorld.ini");
	private static final Path POPULATION = Paths.get("./data/L2HubTownFakes.xml");
	private static final Path CHAT = Paths.get("./data/L2HubTownFakesChat.xml");
	private static final long FIRST_DELAY = 20000;
	private static final long TICK = 3000;
	private static final long POLL = 20000;
	private static final int SAY_RANGE = 1250;
	private static final int TRADE_RANGE = 15000;
	private static final int LIVE_RANGE = 2500;
	private static final int MOVE_RANGE = 4000;
	private static final long REACT_COOLDOWN = 90000;
	private static final int WALK_SPEED = 80;
	private static final int RUN_SPEED = 130;
	private static final int[] PVP_MANAGERS =
	{
		70010,
		70011,
		70012
	};
	private static final String[] PVP_NAMES =
	{
		"TvT",
		"Deathmatch",
		"CtF"
	};
	/** Сидят на лавке: не ходят и не жестикулируют, в общий чат пишут только на события, сами — в торговый. */
	private static final Set<String> SEATED = Set.of("trader", "buyer", "crafter", "afk");
	/** Молчат совсем. */
	private static final Set<String> QUIET = Set.of("afk");
	/** Свои реплики — объявления в торговый чат (ищущий пати — в крик). */
	private static final Set<String> ADVERTISERS = Set.of("trader", "buyer", "crafter", "lfp");
	/** Жесты стоящих (SocialAction): 2 привет, 3 победа, 4 вперёд, 5 нет, 6 да, 7 поклон, 8 растерянность, 9 ожидание, 10 смех, 11 аплодисменты, 12 танец. */
	private static final Map<String, int[]> GESTURES = Map.of("talker", new int[]
	{
		2,
		5,
		6,
		10
	}, "newbie", new int[]
	{
		2,
		7,
		8
	}, "lfp", new int[]
	{
		2,
		9
	}, "runner", new int[]
	{
		6
	}, "showoff", new int[]
	{
		3,
		4,
		11,
		12
	}, "stroller", new int[]
	{
		2,
		10
	}, "nightowl", new int[]
	{
		9,
		10
	});

	private enum Phase
	{
		DAY,
		EVENING,
		NIGHT
	}

	private static final class Spot
	{
		final String key;
		final String near;
		final int heading;
		final boolean run;
		final List<Location> points;

		Spot(String key, String near, int heading, boolean run, List<Location> points)
		{
			this.key = key;
			this.near = near;
			this.heading = heading;
			this.run = run;
			this.points = points;
		}
	}

	private static final class Town
	{
		final String en;
		final String ru;
		final String ruIn;
		final String ruNear;
		final int x;
		final int y;
		final List<Fake> fakes = new ArrayList<>();
		long nextChat;

		Town(Element el)
		{
			en = el.getAttribute("en");
			ru = el.getAttribute("ru");
			ruIn = el.getAttribute("ruIn");
			ruNear = el.getAttribute("ruNear");
			x = Integer.parseInt(el.getAttribute("x"));
			y = Integer.parseInt(el.getAttribute("y"));
		}
	}

	private static final class Fake
	{
		final int npcId;
		final String name;
		final String role;
		final String cls;
		final String store;
		final int rank;
		final int shift;
		final Town town;
		final Map<Phase, Spot> spots = new EnumMap<>(Phase.class);
		Spawn spawn;
		Npc npc;
		Spot spot;
		boolean placed;
		int point;
		long next;

		Fake(Element el, Town town)
		{
			npcId = Integer.parseInt(el.getAttribute("npcId"));
			name = el.getAttribute("name");
			role = el.getAttribute("role");
			cls = el.getAttribute("cls");
			store = el.getAttribute("store");
			rank = Integer.parseInt(el.getAttribute("rank"));
			shift = Integer.parseInt(el.getAttribute("shift"));
			this.town = town;
		}
	}

	private final List<Town> _towns = new ArrayList<>();
	private final Map<String, List<String[]>> _lines = new HashMap<>();
	private final Map<String, String[]> _words = new HashMap<>();
	private final Map<String, Long> _reacted = new ConcurrentHashMap<>();
	private final boolean _english;
	private final int _density;
	private final long _chatPeriod;
	private final boolean _reactions;
	private final boolean _brain;
	private boolean _started;
	private Phase _worldPhase;
	private Set<Integer> _supers;
	private Set<Integer> _epics;
	private Map<String, Boolean> _seasonal;
	private final List<String> _seasonalActive = new ArrayList<>();
	private Set<Integer> _pvp;
	private String _raidKey;

	private L2HubTownFakes(Properties config)
	{
		_english = "en".equalsIgnoreCase(properties(WORLD_CONFIG).getProperty("L2HubLanguage", "ru").trim());
		_density = Math.max(0, Math.min(100, integer(config, "TownFakesDensity", 100)));
		_chatPeriod = Math.max(0, integer(config, "TownFakesChatMinutes", 3)) * 60000L;
		_reactions = !"false".equalsIgnoreCase(config.getProperty("TownFakesReactions", "True").trim());
		_brain = !"false".equalsIgnoreCase(config.getProperty("TownFakesBrain", "True").trim());
		int count = 0;
		try
		{
			loadChat();
			count = loadPopulation();
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubTownFakes: " + e.getMessage());
			return;
		}
		LOGGER.info("L2HubTownFakes: городов " + _towns.size() + ", фейков " + count + " (плотность " + _density + " %), реплики — " + (_english ? "en" : "ru") + (_brain && FakePlayersConfig.FAKE_PLAYER_BRAIN ? ", пишут мозги." : ", заготовки."));
		ThreadPool.scheduleAtFixedRate(this::tick, FIRST_DELAY, TICK);
		ThreadPool.scheduleAtFixedRate(this::poll, FIRST_DELAY + 5000, POLL);
	}

	// ---------- Данные ----------

	private int loadPopulation() throws Exception
	{
		final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(POPULATION.toFile());
		int count = 0;
		for (Element townEl : children(doc.getDocumentElement(), "town"))
		{
			final Town town = new Town(townEl);
			for (Element fakeEl : children(townEl, "fake"))
			{
				final Fake fake = new Fake(fakeEl, town);
				if (fake.rank >= _density)
				{
					continue;
				}
				final Map<String, Spot> known = new HashMap<>();
				for (Phase phase : Phase.values())
				{
					final List<Element> found = children(fakeEl, phase.name().toLowerCase());
					if (!found.isEmpty())
					{
						final Spot spot = spot(found.get(0));
						fake.spots.put(phase, known.computeIfAbsent(spot.key, k -> spot));
					}
				}
				town.fakes.add(fake);
				count++;
			}
			_towns.add(town);
		}
		return count;
	}

	/**
	 * Место на часть суток: точка (стоит или сидит) или маршрут из &lt;point&gt; (ходит по кругу).
	 */
	private static Spot spot(Element el)
	{
		final List<Location> points = new ArrayList<>();
		final List<Element> route = children(el, "point");
		if (route.isEmpty())
		{
			points.add(new Location(Integer.parseInt(el.getAttribute("x")), Integer.parseInt(el.getAttribute("y")), Integer.parseInt(el.getAttribute("z"))));
		}
		for (Element p : route)
		{
			points.add(new Location(Integer.parseInt(p.getAttribute("x")), Integer.parseInt(p.getAttribute("y")), Integer.parseInt(p.getAttribute("z"))));
		}
		final StringBuilder key = new StringBuilder();
		for (Location p : points)
		{
			key.append(p.getX()).append(',').append(p.getY()).append(';');
		}
		final String heading = el.getAttribute("heading");
		return new Spot(key.toString(), el.getAttribute("near"), heading.isEmpty() ? 0 : Integer.parseInt(heading), "true".equals(el.getAttribute("run")), points);
	}

	private void loadChat() throws Exception
	{
		final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(CHAT.toFile());
		for (Element word : children(doc.getDocumentElement(), "word"))
		{
			_words.put(word.getAttribute("key"), new String[]
			{
				word.getAttribute("ru"),
				word.getAttribute("en")
			});
		}
		for (Element lines : children(doc.getDocumentElement(), "lines"))
		{
			final List<String[]> list = _lines.computeIfAbsent(lines.getAttribute("key"), k -> new ArrayList<>());
			for (Element line : children(lines, "line"))
			{
				list.add(new String[]
				{
					line.getAttribute("ru"),
					line.getAttribute("en")
				});
			}
		}
	}

	private static List<Element> children(Element parent, String name)
	{
		final List<Element> out = new ArrayList<>();
		for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling())
		{
			if ((node instanceof Element el) && el.getTagName().equals(name))
			{
				out.add(el);
			}
		}
		return out;
	}

	// ---------- Распорядок ----------

	/**
	 * Утро и день 6:00–18:00, вечер 18:00–24:00, ночь 0:00–6:00 игрового времени (игровые сутки — 4 часа).
	 */
	private static Phase phase(int minute)
	{
		final int m = Math.floorMod(minute, 1440);
		return m < 360 ? Phase.NIGHT : m < 1080 ? Phase.DAY : Phase.EVENING;
	}

	private void tick()
	{
		try
		{
			final long now = System.currentTimeMillis();
			final int time = GameTimeTaskManager.getInstance().getGameTime();
			for (Town town : _towns)
			{
				for (Fake fake : town.fakes)
				{
					final Spot want = fake.spots.get(phase(time - fake.shift));
					if (!fake.placed || (want != fake.spot))
					{
						place(fake, want, now);
					}
					else if (fake.npc != null)
					{
						behave(fake, now);
					}
				}
				chatter(town, now);
			}
			final Phase world = phase(time);
			if (world != _worldPhase)
			{
				_worldPhase = world;
				int present = 0;
				for (Town town : _towns)
				{
					for (Fake fake : town.fakes)
					{
						present += fake.spots.containsKey(phase(time - fake.shift)) ? 1 : 0;
					}
				}
				LOGGER.info("L2HubTownFakes: " + (world == Phase.DAY ? "утро и день" : world == Phase.EVENING ? "вечер" : "ночь") + " — в городах фейков " + present + ".");
			}
			_started = true;
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubTownFakes: " + e);
		}
	}

	/**
	 * Новое место на часть суток: выйти в игру, уйти из игры, дойти (близко) или телепортироваться (далеко).
	 */
	private void place(Fake fake, Spot want, long now)
	{
		final boolean first = !fake.placed;
		fake.placed = true;
		fake.spot = want;
		fake.point = 0;
		if (want == null)
		{
			if (fake.npc != null)
			{
				// Прощание и приветствие — заготовкой: ответ мозгов пришёл бы, когда фейк уже ушёл.
				if (!first && !QUIET.contains(fake.role) && liveNear(fake.npc) && Rnd.nextBoolean())
				{
					say(fake, ChatType.GENERAL, line("leave", fake, null));
				}
				despawn(fake);
			}
			return;
		}

		final Location start = want.points.get(0);
		if (fake.npc == null)
		{
			spawn(fake, start, want.heading);
			fake.next = now + Rnd.get(5000, 30000);
			if (_started && (fake.npc != null) && !QUIET.contains(fake.role) && liveNear(fake.npc) && (Rnd.get(100) < 40))
			{
				ThreadPool.schedule(() -> say(fake, ChatType.GENERAL, line("arrive", fake, null)), Rnd.get(3000, 10000));
			}
			return;
		}

		if (fake.npc.calculateDistance2D(start) > MOVE_RANGE)
		{
			fake.npc.teleToLocation(start);
			fake.next = now + Rnd.get(5000, 20000);
			return;
		}
		move(fake.npc, start, want.run || SEATED.contains(fake.role));
		fake.next = now + travel(fake.npc, start, true) + Rnd.get(3000, 15000);
	}

	private static void spawn(Fake fake, Location at, int heading)
	{
		try
		{
			if (fake.spawn == null)
			{
				fake.spawn = new Spawn(fake.npcId);
				fake.spawn.setAmount(1);
				fake.spawn.setRespawnDelay(60);
			}
			fake.spawn.setXYZ(at.getX(), at.getY(), at.getZ());
			fake.spawn.setHeading(heading);
			fake.spawn.stopRespawn();
			SpawnTable.getInstance().addSpawn(fake.spawn);
			fake.npc = fake.spawn.doSpawn(false);
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubTownFakes: " + fake.name + " (" + fake.npcId + ") не вышел в мир: " + e);
			fake.npc = null;
		}
	}

	private static void despawn(Fake fake)
	{
		final Npc npc = fake.npc;
		fake.npc = null;
		fake.spawn.stopRespawn();
		npc.deleteMe();
		SpawnTable.getInstance().removeSpawn(fake.spawn);
	}

	private static void move(Npc npc, Location to, boolean run)
	{
		if (run)
		{
			npc.setRunning();
		}
		else
		{
			npc.setWalking();
		}
		npc.getAI().setIntentionMoveTo(to);
	}

	private static long travel(Npc npc, Location to, boolean run)
	{
		return (long) ((npc.calculateDistance2D(to) * 1000) / (run ? RUN_SPEED : WALK_SPEED));
	}

	/**
	 * Ходок — к следующей точке маршрута; стоящий изредка жестикулирует, если его видит живой игрок.
	 */
	private void behave(Fake fake, long now)
	{
		final Npc npc = fake.npc;
		if (npc.isMoving() || (now < fake.next))
		{
			return;
		}

		final List<Location> points = fake.spot.points;
		if (points.size() > 1)
		{
			fake.point = (fake.point + 1) % points.size();
			final Location to = points.get(fake.point);
			move(npc, to, fake.spot.run);
			fake.next = now + travel(npc, to, fake.spot.run) + Rnd.get(3000, 25000);
			return;
		}

		final int[] gestures = GESTURES.get(fake.role);
		if ((gestures != null) && liveNear(npc))
		{
			npc.broadcastPacket(new SocialAction(npc.getObjectId(), gestures[Rnd.get(gestures.length)]));
		}
		fake.next = now + Rnd.get(40000, 150000);
	}

	// ---------- Чат ----------

	/**
	 * Реплика в городе: не чаще TownFakesChatMinutes и только при живом игроке рядом. Торговцы и ищущие пати — объявления
	 * в торговый чат и крик, остальные — болтовня в общий.
	 */
	private void chatter(Town town, long now)
	{
		if ((_chatPeriod <= 0) || (now < town.nextChat))
		{
			return;
		}

		final List<Fake> advertisers = new ArrayList<>();
		final List<Fake> talkers = new ArrayList<>();
		boolean heard = false;
		for (Fake fake : town.fakes)
		{
			if (fake.npc == null)
			{
				continue;
			}
			heard |= liveNear(fake.npc);
			if (ADVERTISERS.contains(fake.role))
			{
				advertisers.add(fake);
			}
			if (!SEATED.contains(fake.role))
			{
				talkers.add(fake);
			}
		}
		if (!heard)
		{
			town.nextChat = now + 30000;
			return;
		}

		town.nextChat = now + (long) (_chatPeriod * (0.7 + Rnd.get(0.6)));
		if (!advertisers.isEmpty() && (talkers.isEmpty() || (Rnd.get(100) < 40)))
		{
			final Fake fake = advertisers.get(Rnd.get(advertisers.size()));
			final String text = line("trade." + fake.role, fake, null);
			if (text != null)
			{
				say(fake, fake.role.equals("lfp") ? ChatType.SHOUT : ChatType.TRADE, text);
			}
			return;
		}
		if (talkers.isEmpty())
		{
			return;
		}

		final Fake fake = talkers.get(Rnd.get(talkers.size()));
		final Phase phase = phase(GameTimeTaskManager.getInstance().getGameTime() - fake.shift);
		String text = null;
		String event = "idle";
		final Map<String, String> vars = new HashMap<>();
		synchronized (_seasonalActive)
		{
			if (!_seasonalActive.isEmpty() && (Rnd.get(100) < 25))
			{
				vars.put("event", word("event." + _seasonalActive.get(Rnd.get(_seasonalActive.size())), null));
				text = line("idle.seasonal", fake, vars);
				event = "seasonal_on";
			}
		}
		if (text == null)
		{
			text = line("idle." + fake.role + "." + phase.name().toLowerCase(), fake, null);
		}
		if (text == null)
		{
			text = line("idle." + fake.role, fake, null);
		}
		speak(fake, ChatType.GENERAL, event, text, "");
	}

	/**
	 * Реплика фейка: мозгами (подсказка — заготовка, факты — что случилось), а без них или если мозги не ответили — заготовкой.
	 */
	private void speak(Fake fake, ChatType type, String event, String hint, String facts)
	{
		if ((hint == null) && (!_brain || !FakePlayersConfig.FAKE_PLAYER_BRAIN || facts.isEmpty()))
		{
			return;
		}
		if (!_brain || !FakePlayersConfig.FAKE_PLAYER_BRAIN || (fake.npc == null))
		{
			say(fake, type, hint);
			return;
		}

		final Npc npc = fake.npc;
		final StringBuilder sb = new StringBuilder(640).append('{');
		FakePlayerBrain.field(sb, "kind", "town").append(',');
		FakePlayerBrain.field(sb, "fpc", fake.name).append(',');
		sb.append("\"npcId\":").append(fake.npcId).append(',');
		FakePlayerBrain.field(sb, "role", fake.role).append(',');
		FakePlayerBrain.field(sb, "event", event).append(',');
		FakePlayerBrain.field(sb, "channel", type == ChatType.GENERAL ? "say" : type == ChatType.SHOUT ? "shout" : "trade").append(',');
		FakePlayerBrain.field(sb, "facts", facts).append(',');
		FakePlayerBrain.field(sb, "hint", hint == null ? "" : hint).append(',');
		FakePlayerBrain.field(sb, "town", _english ? fake.town.en : fake.town.ru).append(',');
		FakePlayerBrain.field(sb, "where", fake.spot == null ? "" : word("near." + fake.spot.near, null)).append(',');
		FakePlayerBrain.field(sb, "lang", _english ? "en" : "ru").append(',');
		sb.append("\"level\":").append(npc.getLevel()).append(',');
		final int time = GameTimeTaskManager.getInstance().getGameTime();
		FakePlayerBrain.field(sb, "gameTime", String.format("%02d:%02d", (time / 60) % 24, time % 60)).append(',');
		sb.append("\"night\":").append(GameTimeTaskManager.getInstance().isNight());
		sb.append('}');
		FakePlayerBrain.getInstance().send(sb.toString(), reply ->
		{
			if (!reply.isBlank())
			{
				say(fake, type, reply.strip());
			}
		}, () ->
		{
			if (hint != null)
			{
				say(fake, type, hint);
			}
		});
	}

	private static void say(Fake fake, ChatType type, String text)
	{
		final Npc npc = fake.npc;
		if ((npc == null) || (text == null) || text.isBlank())
		{
			return;
		}
		final CreatureSay packet = new CreatureSay(npc, type, npc.getName(), text);
		final int range = type == ChatType.GENERAL ? SAY_RANGE : TRADE_RANGE;
		for (Player player : World.getPlayers())
		{
			if (player.isOnline() && (player.calculateDistance2D(npc) <= range))
			{
				player.sendPacket(packet);
			}
		}
	}

	/**
	 * Случайная заготовка по ключу на языке L2HubLanguage (нет английской — русская) с подставленными {переменными}; нет ключа — null.
	 */
	private String line(String key, Fake fake, Map<String, String> vars)
	{
		final List<String[]> list = _lines.get(key);
		if ((list == null) || list.isEmpty())
		{
			return null;
		}
		final String[] pair = list.get(Rnd.get(list.size()));
		String text = (_english && !pair[1].isEmpty()) ? pair[1] : pair[0];
		final Map<String, String> all = new HashMap<>();
		all.put("town", _english ? fake.town.en : fake.town.ru);
		all.put("in", _english ? "in " + fake.town.en : fake.town.ruIn);
		all.put("where", fake.spot == null ? "" : word("near." + fake.spot.near, null));
		all.put("store", fake.store);
		all.put("cls", fake.cls);
		all.put("level", String.valueOf(fake.npc == null ? "" : fake.npc.getLevel()));
		if (vars != null)
		{
			all.putAll(vars);
		}
		for (Map.Entry<String, String> entry : all.entrySet())
		{
			text = text.replace("{" + entry.getKey() + "}", entry.getValue() == null ? "" : entry.getValue());
		}
		return text;
	}

	private String word(String key, String fallback)
	{
		final String[] pair = _words.get(key);
		if (pair == null)
		{
			return fallback != null ? fallback : key.substring(key.indexOf('.') + 1);
		}
		return (_english && !pair[1].isEmpty()) ? pair[1] : pair[0];
	}

	// ---------- Живые игроки ----------

	private static boolean live(Player player)
	{
		return player.isOnline() && !player.isInOfflineMode() && !PhantomManager.getInstance().isPhantom(player);
	}

	private static boolean liveNear(Npc npc)
	{
		for (Player player : World.getPlayers())
		{
			if (live(player) && (player.calculateDistance2D(npc) < LIVE_RANGE))
			{
				return true;
			}
		}
		return false;
	}

	// ---------- События мира ----------

	private void poll()
	{
		if (!_reactions)
		{
			return;
		}
		try
		{
			pollSupers();
			pollEpics();
			pollSeasonal();
			pollPvp();
			pollRaid();
		}
		catch (Exception e)
		{
			LOGGER.warning("L2HubTownFakes: " + e);
		}
	}

	private void pollSupers()
	{
		final Set<Integer> now = new HashSet<>();
		for (Attackable npc : SuperChampion.alive())
		{
			now.add(npc.getObjectId());
			if ((_supers != null) && !_supers.contains(npc.getObjectId()))
			{
				final Map<String, String> vars = boss(npc);
				react("super_champion", vars, "Суперчемпион " + npc.getName() + " (" + npc.getLevel() + " ур.) появился " + near(npc.getX(), npc.getY()));
			}
		}
		_supers = now;
	}

	private void pollEpics()
	{
		final Set<Integer> alive = new HashSet<>();
		final Map<Integer, GrandBoss> bosses = new HashMap<>();
		for (int id : grandIds())
		{
			final GrandBoss boss = GrandBossManager.getInstance().getBoss(id);
			if ((boss != null) && boss.isSpawned() && !boss.isDead())
			{
				alive.add(id);
				bosses.put(id, boss);
			}
		}
		if (_epics != null)
		{
			for (int id : alive)
			{
				if (!_epics.contains(id))
				{
					final GrandBoss boss = bosses.get(id);
					react("epic_spawned", boss(boss), "Эпик " + boss.getName() + " (" + boss.getLevel() + " ур.) появился " + near(boss.getX(), boss.getY()));
				}
			}
			for (int id : _epics)
			{
				final GrandBoss boss = GrandBossManager.getInstance().getBoss(id);
				if (!alive.contains(id) && (boss != null) && boss.isDead())
				{
					react("epic_killed", boss(boss), "Эпик " + boss.getName() + " убит");
				}
			}
		}
		_epics = alive;
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

	private void pollSeasonal()
	{
		final Map<String, Boolean> now = new HashMap<>();
		final List<String> active = new ArrayList<>();
		for (Quest quest : ScriptManager.getInstance().getScripts().values())
		{
			if (quest instanceof LongTimeEvent event)
			{
				final boolean on = event.isEventPeriod();
				now.put(quest.getName(), on);
				if (on)
				{
					active.add(quest.getName());
				}
				if ((_seasonal != null) && on && Boolean.FALSE.equals(_seasonal.get(quest.getName())))
				{
					final Map<String, String> vars = new HashMap<>();
					vars.put("event", word("event." + quest.getName(), null));
					react("seasonal_start", vars, "Началось сезонное событие " + quest.getName());
				}
			}
		}
		_seasonal = now;
		synchronized (_seasonalActive)
		{
			_seasonalActive.clear();
			_seasonalActive.addAll(active);
		}
	}

	private void pollPvp()
	{
		final Set<Integer> now = new HashSet<>();
		for (int i = 0; i < PVP_MANAGERS.length; i++)
		{
			final Npc manager = World.getNpc(PVP_MANAGERS[i]);
			if (manager == null)
			{
				continue;
			}
			now.add(PVP_MANAGERS[i]);
			if ((_pvp != null) && !_pvp.contains(PVP_MANAGERS[i]))
			{
				final Map<String, String> vars = new HashMap<>();
				vars.put("event", PVP_NAMES[i]);
				vars.put("place", near(manager.getX(), manager.getY()));
				react("pvp_event", vars, "Открыта регистрация на PvP-ивент " + PVP_NAMES[i]);
			}
		}
		_pvp = now;
	}

	/**
	 * Рейд охотников (PhantomRaid модуля фантомов): новый сбор — одна реакция. Поля читаются по именам: нет их — тишина.
	 */
	private void pollRaid()
	{
		final Object raid = field(PhantomRaid.getInstance(), "_raid");
		if (raid == null)
		{
			_raidKey = "";
			return;
		}
		final String key = field(raid, "leaderId") + ":" + field(raid, "bossName");
		if (key.equals(_raidKey))
		{
			return;
		}
		final boolean first = (_raidKey == null);
		_raidKey = key;
		final Object stage = field(raid, "stage");
		if (first || (stage == null) || !"GATHER".equals(stage.toString()))
		{
			return;
		}
		final Map<String, String> vars = new HashMap<>();
		vars.put("boss", String.valueOf(field(raid, "bossName")));
		vars.put("blevel", String.valueOf(field(raid, "bossLevel")));
		final Player leader = (field(raid, "leaderId") instanceof Integer id) ? World.getPlayer(id) : null;
		vars.put("leader", leader == null ? "" : leader.getName());
		final Object boss = field(raid, "boss");
		vars.put("place", boss instanceof Creature creature ? near(creature.getX(), creature.getY()) : "");
		react("hunter_raid", vars, "Охотники " + vars.get("leader") + " собирают рейд на " + vars.get("boss") + " (" + vars.get("blevel") + " ур.) " + vars.get("place"));
	}

	private static Object field(Object owner, String name)
	{
		try
		{
			for (Class<?> c = owner.getClass(); c != null; c = c.getSuperclass())
			{
				try
				{
					final Field field = c.getDeclaredField(name);
					field.setAccessible(true);
					return field.get(owner);
				}
				catch (NoSuchFieldException e)
				{
					// выше по иерархии
				}
			}
		}
		catch (Exception e)
		{
			// нет поля — нет реакции
		}
		return null;
	}

	private Map<String, String> boss(Npc npc)
	{
		final Map<String, String> vars = new HashMap<>();
		vars.put("boss", npc.getName());
		vars.put("blevel", String.valueOf(npc.getLevel()));
		vars.put("place", near(npc.getX(), npc.getY()));
		return vars;
	}

	/**
	 * Ближайший город из населения: «у Гирана» / «near Giran».
	 */
	private String near(int x, int y)
	{
		Town best = null;
		double bestDistance = Double.MAX_VALUE;
		for (Town town : _towns)
		{
			final double distance = Math.hypot(town.x - x, town.y - y);
			if (distance < bestDistance)
			{
				best = town;
				bestDistance = distance;
			}
		}
		if (best == null)
		{
			return "";
		}
		return _english ? "near " + best.en : best.ruNear;
	}

	/**
	 * Реакция на событие мира: в каждом городе, где фейков слышит живой игрок, — один-два из них, с паузой.
	 */
	private void react(String kind, Map<String, String> vars, String facts)
	{
		final long now = System.currentTimeMillis();
		final Long last = _reacted.get(kind);
		if ((last != null) && ((now - last) < REACT_COOLDOWN))
		{
			return;
		}
		_reacted.put(kind, now);
		int towns = 0;
		for (Town town : _towns)
		{
			final List<Fake> heard = new ArrayList<>();
			for (Fake fake : town.fakes)
			{
				if ((fake.npc != null) && !QUIET.contains(fake.role) && liveNear(fake.npc))
				{
					heard.add(fake);
				}
			}
			if (heard.isEmpty())
			{
				continue;
			}
			towns++;
			Collections.shuffle(heard);
			final int speakers = Math.min(heard.size(), Rnd.nextBoolean() ? 2 : 1);
			for (int i = 0; i < speakers; i++)
			{
				final Fake fake = heard.get(i);
				final String text = line("event." + kind, fake, vars);
				ThreadPool.schedule(() -> speak(fake, ChatType.GENERAL, kind, text, facts), i == 0 ? Rnd.get(3000, 12000) : Rnd.get(15000, 40000));
			}
		}
		if (towns > 0)
		{
			LOGGER.info("L2HubTownFakes: " + facts + " — откликаются фейки в городах: " + towns + ".");
		}
	}

	/**
	 * Крючок для других модулей: notifyEvent("world:&lt;вид&gt;:&lt;x&gt;,&lt;y&gt;:&lt;текст&gt;", null, null).
	 */
	@Override
	public String onEvent(String event, Npc npc, Player player)
	{
		if ((event == null) || !event.startsWith("world:") || !_reactions)
		{
			return null;
		}
		final String[] parts = event.split(":", 4);
		final Map<String, String> vars = new HashMap<>();
		final String text = parts.length > 3 ? parts[3] : "";
		vars.put("text", text);
		vars.put("place", "");
		if (parts.length > 2)
		{
			final String[] xy = parts[2].split(",");
			if (xy.length == 2)
			{
				try
				{
					vars.put("place", near(Integer.parseInt(xy[0].trim()), Integer.parseInt(xy[1].trim())));
				}
				catch (NumberFormatException e)
				{
					// без места
				}
			}
		}
		react(parts.length > 1 ? parts[1] : "world", vars, text);
		return null;
	}

	// ---------- Настройки ----------

	private static Properties properties(Path path)
	{
		final Properties properties = new Properties();
		if (Files.isRegularFile(path))
		{
			try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8))
			{
				properties.load(reader);
			}
			catch (Exception e)
			{
				LOGGER.warning("L2HubTownFakes: " + path + ": " + e.getMessage());
			}
		}
		return properties;
	}

	private static int integer(Properties config, String key, int fallback)
	{
		try
		{
			return Integer.parseInt(config.getProperty(key, String.valueOf(fallback)).trim());
		}
		catch (NumberFormatException e)
		{
			return fallback;
		}
	}

	public static void main(String[] args)
	{
		if (!FakePlayersConfig.FAKE_PLAYERS_ENABLED)
		{
			LOGGER.info("L2HubTownFakes: EnableFakePlayers = False — фейков в городах нет.");
			return;
		}
		final Properties config = properties(CONFIG);
		if (integer(config, "TownFakesDensity", 100) <= 0)
		{
			LOGGER.info("L2HubTownFakes: TownFakesDensity = 0 — фейков в городах нет.");
			return;
		}
		new L2HubTownFakes(config);
	}
}
