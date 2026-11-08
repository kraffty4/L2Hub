package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.SpawnTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.script.Quest;
import org.l2jmobius.gameserver.mechanics.script.QuestState;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/**
 * L2Hub, High Five: профа охотника настоящим квестом (ключи {@code HunterProfessionQuests} и {@code HunterProfessionQuestTiers}, docs/PHANTOMS.md, раздел 4.152).<br>
 * 1-я профа — один квест пути с 18-го, класс с 20-го; 2-я — три квеста (Trial, Testimony, Test) с 35, 37 и 39-го, класс с 40-го: каждый квест даёт знак, мастер берёт все.<br>
 * Охотник с 18-го уровня без профы берёт квест пути своего класса у стартового NPC, бьёт мобов шага (споты — по спавну мобов) и сдаёт шаги
 * штатными диалогами квеста: тот же {@code onTalk}/{@code onEvent}, что у игрока, по ссылкам {@code bypass … Quest <квест> <событие>} из html. Таблиц событий
 * на квест нет: шаг засчитан, если у квеста сдвинулись состояние или предметы. Собрал знаки пути и дорос — класс у мастера штатным скриптом
 * {@code village_master/*Change1} или {@code *Change2}, скиллы и бой — как при покупке. Не сдвинулся {@link #STALL} — бросает квест, и профу он получает прежним путём
 * ({@link PhantomHunterOutfit}). Данные — {@code data/L2HubProfessionQuests.xml}, их пишет {@code tools/profession_quests.py}.
 * @author L2Hub
 */
public final class PhantomProfessionQuest
{
	private static final Logger LOGGER = Logger.getLogger(PhantomProfessionQuest.class.getName());
	private static final String TAG = "PhantomProfessionQuest: ";
	private static final String DATA_FILE = "data/L2HubProfessionQuests.xml";
	private static final String VAR_STATE = "L2HubProfQuest";
	private static final String VAR_END = "L2HubProfQuestEnd";
	private static final String VAR_TEST = "L2HubProfQTest";
	private static final Pattern LINK = Pattern.compile("bypass (?:-h )?(?:npc_%objectId%_)?(?:Script|Quest) (\\S+) ([^\"]+)\"");
	/** Скрипты мастеров смены класса по ступеням: [0] — 1-я профа, [1] — 2-я. */
	private static final String[][] MASTERS =
	{
		{
			"ElfHumanFighterChange1",
			"ElfHumanWizardChange1",
			"DarkElfChange1",
			"OrcChange1",
			"DwarfBlacksmithChange1",
			"DwarfWarehouseChange1",
			"KamaelChange1",
			"FirstClassTransferTalk"
		},
		{
			"ElfHumanFighterChange2",
			"ElfHumanWizardChange2",
			"ElfHumanClericChange2",
			"DarkElfChange2",
			"OrcChange2",
			"DwarfBlacksmithChange2",
			"DwarfWarehouseChange2",
			"KamaelChange2"
		}
	};
	/** С какого уровня мастер даёт класс ступени. */
	private static final int[] TRANSFER_LEVEL = { 20, 40 };
	/** Квест не сдвинулся столько — бросить. */
	private static final long STALL = 40 * 60000L;
	/** Как часто пробовать диалоги, пока бьёт мобов. */
	private static final long TALK_EVERY = 15000;
	private static final int MOB_RANGE = 1500;
	/** Shadow Item Exchange Coupon (D) и (C), по 15 штук даёт мастер при смене класса 1-й и 2-й ступени. */
	private static final int[] COUPONS = { 8869, 8870 };
	private static final int TELEPORT_RANGE = 4000;
	/** Цель держится, а боя по ней нет столько — автобой её не берёт (моб занят другим игроком, не виден): отложить. */
	private static final long STUCK_TARGET = 20000;
	private static final long SKIP_TARGET = 120000;
	/** Разница высот, с которой моб или спавн — на другом этаже (пещера под полем, как у Boogle Ratman в Q418): пешком туда не дойти. */
	private static final int LAYER = 300;

	private static final class Def
	{
		int quest;
		int tier;
		String script;
		int level;
		int start;
		int mark;
		boolean fist;
		final List<int[]> steps = new ArrayList<>(); // [npc или 0, мобы…]
	}

	/** Путь класса: его квесты по порядку и мастера смены класса. */
	private static final class Path
	{
		int tier;
		int classId;
		int[] quests;
		int[] masters;
	}

	private static final class State
	{
		Def def;
		int pointer;
		int flag = -1;
		long progressAt;
		long talkAt;
		String sig = "";
		int fails;
		int weapon; // объект оружия, снятого под квест
		long tickAt; // для журнала самопроверки: прошлый тик и прошлая запись
		long traceAt;
		String hunt = "";
		int targetId; // цель, которую держит квест, и с какого времени по ней нет боя
		long targetSince;
		final Map<Integer, Long> skip = new HashMap<>(); // мобы, которых автобой не берёт, — до какого времени не брать
	}

	/**
	 * Журнал только у охотников самопроверки: перерыв между тиками (охотника водила другая логика) и раз в 2 минуты — где он и что видит.
	 */
	private static void trace(Player player, State st, Quest quest, long now)
	{
		if (!FORCED.contains(player.getObjectId()))
		{
			return;
		}
		if ((st.tickAt > 0) && ((now - st.tickAt) > 30000))
		{
			LOGGER.info(TAG + player.getName() + " Q" + st.def.quest + " trace: no quest tick for " + ((now - st.tickAt) / 1000) + " s, now at " + player.getX() + " " + player.getY() + " " + player.getZ() + ".");
		}
		st.tickAt = now;
		if ((now - st.traceAt) >= 120000)
		{
			st.traceAt = now;
			LOGGER.info(TAG + player.getName() + " Q" + st.def.quest + " trace: step " + st.pointer + ", at " + player.getX() + " " + player.getY() + " " + player.getZ() + ", target " + (player.getTarget() == null ? "-" : player.getTarget().getName()) + ", autoplay " + player.isAutoPlaying() + ", combat " + player.isInCombat() + ", " + st.hunt + ", quest " + signature(player, quest) + ".");
		}
	}

	private static final Map<Integer, Def> QUESTS = new HashMap<>();
	private static final Map<Integer, Path> PATHS = new HashMap<>();
	private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();
	private static final Set<Integer> FORCED = ConcurrentHashMap.newKeySet();
	private static volatile boolean _loaded;

	private PhantomProfessionQuest()
	{
	}

	private static synchronized void load()
	{
		if (_loaded)
		{
			return;
		}
		_loaded = true;
		final File file = new File(ServerConfig.DATAPACK_ROOT, DATA_FILE);
		if (!file.isFile())
		{
			LOGGER.info(TAG + DATA_FILE + " is missing, quests are off.");
			return;
		}
		try
		{
			final Element root = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file).getDocumentElement();
			final NodeList quests = root.getElementsByTagName("quest");
			for (int i = 0; i < quests.getLength(); i++)
			{
				final Element e = (Element) quests.item(i);
				final Def def = new Def();
				def.quest = Integer.parseInt(e.getAttribute("id"));
				def.tier = Integer.parseInt(e.getAttribute("tier"));
				def.script = e.getAttribute("script");
				def.level = Integer.parseInt(e.getAttribute("level"));
				def.start = Integer.parseInt(e.getAttribute("start"));
				def.mark = Integer.parseInt(e.getAttribute("mark"));
				def.fist = "fist".equals(e.getAttribute("weapon"));
				final NodeList steps = e.getElementsByTagName("step");
				for (int j = 0; j < steps.getLength(); j++)
				{
					final Element s = (Element) steps.item(j);
					final List<Integer> row = new ArrayList<>();
					row.add(s.hasAttribute("npc") ? Integer.parseInt(s.getAttribute("npc")) : 0);
					if (!s.getAttribute("mobs").isEmpty())
					{
						for (String mob : s.getAttribute("mobs").split(","))
						{
							row.add(Integer.parseInt(mob.strip()));
						}
					}
					def.steps.add(row.stream().mapToInt(Integer::intValue).toArray());
				}
				QUESTS.put(def.quest, def);
			}
			final NodeList paths = root.getElementsByTagName("path");
			for (int i = 0; i < paths.getLength(); i++)
			{
				final Element e = (Element) paths.item(i);
				final Path path = new Path();
				path.tier = Integer.parseInt(e.getAttribute("tier"));
				path.classId = Integer.parseInt(e.getAttribute("class"));
				path.quests = ids(e.getAttribute("quests"));
				path.masters = ids(e.getAttribute("masters"));
				if (Arrays.stream(path.quests).allMatch(QUESTS::containsKey))
				{
					PATHS.put(path.classId, path);
				}
			}
			LOGGER.info(TAG + "loaded " + QUESTS.size() + " quests and " + PATHS.size() + " class paths.");
		}
		catch (Exception ex)
		{
			LOGGER.warning(TAG + "failed to read " + DATA_FILE + ": " + ex);
		}
	}

	private static int[] ids(String list)
	{
		return list.isBlank() ? new int[0] : Arrays.stream(list.split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray();
	}

	/**
	 * @return ступень, на которую охотник идёт сейчас (1 или 2), если она включена ключами или он в самопроверке; 0 — нет
	 */
	private static int tier(Player player)
	{
		final int tier = player.getPlayerClass().level() + 1;
		if ((tier > MASTERS.length) || player.isSubClassActive())
		{
			return 0;
		}
		if (FORCED.contains(player.getObjectId()))
		{
			return tier;
		}
		return PhantomHuntersConfig.HUNTER_PROFESSION_QUESTS && PhantomHuntersConfig.HUNTER_PROFESSION_QUEST_TIERS.contains(tier) ? tier : 0;
	}

	private static String endVar(int tier)
	{
		return tier == 1 ? VAR_END : VAR_END + tier;
	}

	/**
	 * @return путь класса, к которому охотник идёт на этой ступени, или {@code null}: ступень выключена, бросил, пути в данных нет, не дорос до первого квеста
	 */
	private static Path path(Player player, PhantomTemplate template)
	{
		final int tier = tier(player);
		if ((tier == 0) || (template == null) || !player.getVariables().getString(endVar(tier), "").isEmpty())
		{
			return null;
		}
		load();
		PlayerClass target = PlayerClass.getPlayerClass(template.getClassId(Math.max(TRANSFER_LEVEL[tier - 1], player.getLevel())));
		while ((target != null) && (target.level() > tier))
		{
			target = target.getParent();
		}
		final Path path = (target == null) || (target.level() != tier) ? null : PATHS.get(target.getId());
		return (path == null) || (path.tier != tier) || (player.getLevel() < QUESTS.get(path.quests[0]).level) ? null : path;
	}

	/**
	 * @return квест пути, знака которого ещё нет; {@code null} — все знаки собраны, пора к мастеру
	 */
	private static Def current(Player player, Path path)
	{
		for (int id : path.quests)
		{
			final Def def = QUESTS.get(id);
			if (player.getInventory().getItemByItemId(def.mark) == null)
			{
				return def;
			}
		}
		return null;
	}

	/**
	 * Пока охотник идёт путём (и пока ждёт уровня под следующий квест пути), класс ему сам не выдаётся и не покупается.
	 */
	static boolean holds(Player player, PhantomTemplate template)
	{
		return path(player, template) != null;
	}

	/**
	 * Шаг квеста за тик охотника ({@code PhantomHunterManager.think}).
	 * @return {@code true} — тик занят квестом, остальной охоты в нём нет
	 */
	static boolean tick(Player player, PhantomTemplate template, long now)
	{
		if (!player.getVariables().getString(VAR_TEST, "").isEmpty() && !FORCED.contains(player.getObjectId()))
		{
			LOGGER.info(TAG + player.getName() + ": self-test leftover, " + selfTestEnd(player));
		}
		final Path path = path(player, template);
		if (path == null)
		{
			STATES.remove(player.getObjectId());
			return false;
		}
		final Def def = current(player, path);
		if (def == null)
		{
			return transfer(player, path, STATES.remove(player.getObjectId()));
		}
		if (player.getLevel() < def.level)
		{
			return false; // следующий квест пути — с уровня выше: охотится, класс держит путь
		}
		if (player.isInParty() && player.getParty().getMembers().stream().anyMatch(m -> !PhantomManager.getInstance().isPhantom(m)))
		{
			return false; // с живым игроком — его дела важнее
		}
		State st = STATES.computeIfAbsent(player.getObjectId(), id -> restore(player, def, now));
		if (st.def != def)
		{
			disarm(player, st); // прошлый квест пути сдан — знак есть
			st = restore(player, def, now);
			STATES.put(player.getObjectId(), st);
		}
		final Quest quest = ScriptManager.getInstance().getQuest(def.quest);
		if (quest == null)
		{
			return giveUp(player, st, "no script " + def.script);
		}

		trace(player, st, quest, now);
		final QuestState qs = quest.getQuestState(player, false);
		if ((qs != null) && qs.isCompleted())
		{
			return giveUp(player, st, "Q" + def.quest + " completed without its mark " + def.mark);
		}
		if ((now - st.progressAt) > STALL)
		{
			return giveUp(player, st, "stalled at step " + st.pointer + " cond " + ((qs == null) ? 0 : qs.getCond()) + (st.hunt.isEmpty() ? "" : ", last: " + st.hunt));
		}

		if ((qs == null) || !qs.isStarted())
		{
			final Npc npc = npc(def.start);
			if (npc == null)
			{
				return giveUp(player, st, "no start npc " + def.start);
			}
			come(player, npc);
			talk(quest, npc, player);
			final QuestState taken = quest.getQuestState(player, false);
			if ((taken != null) && taken.isStarted())
			{
				progressed(player, st, quest, def.start, now);
				LOGGER.info(TAG + player.getName() + " (" + player.getLevel() + ", " + player.getPlayerClass().name() + ") takes Q" + def.quest + " at " + npc.getName() + ".");
			}
			else if (++st.fails >= 3)
			{
				return giveUp(player, st, "start refused at " + npc.getName());
			}
			return true;
		}

		// Диалоги: шаг сдан, если у квеста что-то сдвинулось.
		if (((now - st.talkAt) >= TALK_EVERY) || !st.sig.equals(signature(player, quest)))
		{
			st.talkAt = now;
			for (int npcId : candidates(def, st.pointer))
			{
				final Npc npc = npc(npcId);
				if (npc == null)
				{
					continue;
				}
				final String before = signature(player, quest);
				talk(quest, npc, player);
				if (!before.equals(signature(player, quest)))
				{
					come(player, npc);
					progressed(player, st, quest, npcId, now);
					final QuestState after = quest.getQuestState(player, false);
					LOGGER.info(TAG + player.getName() + " Q" + def.quest + ": " + npc.getName() + " -> step " + st.pointer + ((after == null) ? ", quest closed" : ", cond " + after.getCond()) + ".");
					return true;
				}
			}
			st.sig = signature(player, quest);
		}

		hunt(player, st, quest, now);
		return true;
	}

	private static State restore(Player player, Def def, long now)
	{
		final State st = new State();
		st.def = def;
		st.progressAt = now;
		final String[] saved = player.getVariables().getString(VAR_STATE, "").split(";");
		if ((saved.length >= 3) && saved[0].equals(String.valueOf(def.quest)))
		{
			st.pointer = Integer.parseInt(saved[1]);
			st.flag = Integer.parseInt(saved[2]);
			if (saved.length >= 4)
			{
				st.weapon = Integer.parseInt(saved[3]);
			}
		}
		return st;
	}

	private static void save(Player player, State st)
	{
		player.getVariables().set(VAR_STATE, st.def.quest + ";" + st.pointer + ";" + st.flag + ";" + st.weapon);
	}

	/**
	 * Шаг сдан у NPC: указатель — за шаг этого NPC, а если у шага ещё и мобы, сначала на сам шаг (после разговора их бить).
	 */
	private static void progressed(Player player, State st, Quest quest, int npcId, long now)
	{
		final List<int[]> steps = st.def.steps;
		int found = -1;
		for (int i = st.pointer; (i < steps.size()) && (found < 0); i++)
		{
			if (steps.get(i)[0] == npcId)
			{
				found = i;
			}
		}
		if (found >= 0)
		{
			if ((steps.get(found).length > 1) && (st.flag != found))
			{
				st.pointer = found;
				st.flag = found;
			}
			else
			{
				st.pointer = found + 1;
				st.flag = -1;
			}
		}
		st.progressAt = now;
		st.fails = 0;
		st.sig = signature(player, quest);
		save(player, st);
	}

	/**
	 * С кем говорить: NPC ближайших шагов, по порядку, без повторов.
	 */
	private static List<Integer> candidates(Def def, int pointer)
	{
		final List<Integer> list = new ArrayList<>();
		for (int i = Math.max(0, pointer); (i < def.steps.size()) && (i < (pointer + 4)); i++)
		{
			final int npc = def.steps.get(i)[0];
			if ((npc > 0) && !list.contains(npc))
			{
				list.add(npc);
			}
		}
		if (list.isEmpty())
		{
			list.add(def.start);
		}
		return list;
	}

	/**
	 * Состояние квеста для «сдвинулось ли»: шаг, память и предметы квеста.
	 */
	private static String signature(Player player, Quest quest)
	{
		final QuestState qs = quest.getQuestState(player, false);
		final StringBuilder sb = new StringBuilder();
		sb.append(qs == null ? "-" : (qs.isStarted() ? "s" : (qs.isCompleted() ? "c" : "n")) + qs.getCond() + "/" + qs.getMemoState());
		final int[] items = quest.getRegisteredItemIds();
		if (items != null)
		{
			for (int id : items)
			{
				final Item item = player.getInventory().getItemByItemId(id);
				if (item != null)
				{
					sb.append(' ').append(id).append(':').append(item.getCount());
				}
			}
		}
		return sb.toString();
	}

	/**
	 * Разговор, как у игрока: {@code onTalk}, потом ссылки этого квеста из html по очереди, пока они есть.
	 */
	private static void talk(Quest quest, Npc npc, Player player)
	{
		try
		{
			String res = quest.onTalk(npc, player);
			final Set<String> seen = new HashSet<>();
			for (int i = 0; (i < 8) && (res != null); i++)
			{
				final String html = (res.endsWith(".htm") || res.endsWith(".html")) ? quest.getHtm(player, res) : res;
				if (html == null)
				{
					return;
				}
				String next = null;
				final Matcher m = LINK.matcher(html);
				while (m.find() && (next == null))
				{
					final String event = m.group(2).strip();
					if (m.group(1).equals(quest.getName()) && seen.add(event))
					{
						next = event;
					}
				}
				if (next == null)
				{
					return;
				}
				res = quest.onEvent(next, npc, player);
			}
		}
		catch (Exception e)
		{
			LOGGER.warning(TAG + player.getName() + " Q" + quest.getId() + " at " + npc.getId() + ": " + e);
		}
	}

	/**
	 * Мобы шага: оружие под квест, цель — ближний живой моб шага, не занятый другим игроком; рядом нет — к их спавну.
	 */
	private static void hunt(Player player, State st, Quest quest, long now)
	{
		int[] mobs = null;
		for (int i = Math.max(0, st.pointer); (i < st.def.steps.size()) && (mobs == null); i++)
		{
			if (st.def.steps.get(i).length > 1)
			{
				mobs = st.def.steps.get(i);
			}
		}
		if (mobs == null)
		{
			return; // осталось только говорить
		}
		arm(player, st, quest);
		final Set<Integer> ids = new HashSet<>();
		for (int i = 1; i < mobs.length; i++)
		{
			ids.add(mobs[i]);
		}

		st.skip.values().removeIf(until -> until < now);
		if ((player.getTarget() instanceof Monster current) && !current.isAlikeDead() && ids.contains(current.getId()) && (Math.abs(current.getZ() - player.getZ()) <= LAYER))
		{
			if (current.getObjectId() != st.targetId)
			{
				st.targetId = current.getObjectId();
				st.targetSince = now;
			}
			else if (player.isInCombat() || player.isAttackingNow() || player.isCastingNow())
			{
				st.targetSince = now;
			}
			// Автобой Mobius не бьёт моба, занятого другим игроком, а квест держал бы его целью вечно (гном Q418 у Boogle Ratman Leader, 40 минут).
			if (busy(player, current) || ((now - st.targetSince) > STUCK_TARGET))
			{
				st.skip.put(current.getObjectId(), now + SKIP_TARGET);
				st.targetId = 0;
				player.setTarget(null);
				st.hunt = "drops " + current.getId();
				return;
			}
			st.hunt = "fights " + current.getId();
			attack(player, current);
			return;
		}
		Monster best = null;
		double bestDist = Double.MAX_VALUE;
		int seen = 0;
		int taken = 0;
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, MOB_RANGE))
		{
			if (!ids.contains(monster.getId()) || monster.isAlikeDead() || (Math.abs(monster.getZ() - player.getZ()) > LAYER) || st.skip.containsKey(monster.getObjectId()))
			{
				continue;
			}
			seen++;
			if (busy(player, monster))
			{
				taken++;
				continue;
			}
			final double dist = player.calculateDistance3D(monster);
			if (dist < bestDist)
			{
				best = monster;
				bestDist = dist;
			}
		}
		st.hunt = "mobs near " + seen + ", busy " + taken;
		if (best != null)
		{
			player.setTarget(best);
			attack(player, best);
			return;
		}

		// Чужой моб напал — отбиться: раньше охотник «в бою» стоял, не шёл к мобам квеста, и квест вставал до застоя (Q403, Q401).
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, 400))
		{
			if (!monster.isAlikeDead() && (monster.getTarget() == player))
			{
				st.hunt += ", defends from " + monster.getId();
				player.setTarget(monster);
				attack(player, monster);
				return;
			}
		}
		if (player.isMoving())
		{
			st.hunt += ", moving";
			return;
		}
		Spawn near = null;
		double nearDist = Double.MAX_VALUE;
		for (int id : ids)
		{
			for (Spawn spawn : SpawnTable.getInstance().getSpawns(id))
			{
				final double dist = player.calculateDistance3D(spawn);
				if (dist < nearDist)
				{
					near = spawn;
					nearDist = dist;
				}
			}
		}
		if (near == null)
		{
			st.hunt += ", no spawn";
			return; // мобы квеста появляются по ходу квеста: ждать, пока квест их не вызовет, или до STALL
		}
		st.hunt += ", to spawn " + near.getId() + " " + (int) nearDist;
		final Location loc = new Location(near.getX() + Rnd.get(-150, 150), near.getY() + Rnd.get(-150, 150), near.getZ());
		if (Math.abs(near.getZ() - player.getZ()) > LAYER)
		{
			// другой этаж: ровно в точку спавна, без разброса — в пещере он кладёт в стену
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
			player.teleToLocation(new Location(near.getX(), near.getY(), near.getZ()), false);
		}
		else if (nearDist > TELEPORT_RANGE)
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
			player.teleToLocation(loc, true);
		}
		else
		{
			player.getAI().setIntentionMoveTo(loc);
		}
	}

	private static boolean busy(Player player, Monster monster)
	{
		final Creature victim = monster.getTarget() instanceof Creature c ? c : null;
		return (victim != null) && (victim != player) && victim.isPlayer();
	}

	/**
	 * Бой по цели квеста сам, как у напарника ({@code PhantomCompanionManager}): автобой Mobius цель квеста часто не бьёт (свои проверки «занят другим»,
	 * «не видно», пауза «не пройти» в обходе) — гном Q418 так простоял у Boogle Ratman Leader 40 минут. Автобой на время квеста выключен, автоскиллы и банки работают.
	 */
	private static void attack(Player player, Monster target)
	{
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		}
		AutoUseTaskManager.getInstance().startAutoUseTask(player);
		// Намерение ATTACK ведёт по прямой: упёрся и стоит — к цели обычным ходом.
		final double reach = player.getPhysicalAttackRange() + target.getTemplate().getCollisionRadius() + player.getCollisionRadius() + 40;
		if ((player.calculateDistance2D(target) > reach) && !player.isMoving() && (player.getAI().getIntention() == Intention.ATTACK) && !player.isAttackingNow())
		{
			player.setRunning();
			player.getAI().setIntentionMoveTo(target);
			return;
		}
		if ((player.getAI().getIntention() != Intention.ATTACK) || (!player.isAttackingNow() && !player.isMoving()))
		{
			player.setRunning();
			player.getAI().setIntentionAttack(target);
		}
	}

	/**
	 * Оружие под квест: выданное квестом (меч испытания у воителя, оружие Нетиса у разбойника) — надеть; квест на кулаки (монах) — снять оружие. Своё вернётся после квеста.
	 */
	private static void arm(Player player, State st, Quest quest)
	{
		final Item worn = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		Item questWeapon = null;
		final int[] items = quest.getRegisteredItemIds();
		if (items != null)
		{
			for (int id : items)
			{
				final Item item = player.getInventory().getItemByItemId(id);
				if ((item != null) && item.isWeapon())
				{
					questWeapon = item;
				}
			}
		}
		if ((questWeapon != null) && !questWeapon.isEquipped())
		{
			if ((worn != null) && (st.weapon == 0))
			{
				st.weapon = worn.getObjectId();
			}
			player.getInventory().equipItem(questWeapon);
			player.broadcastUserInfo();
			save(player, st);
		}
		else if ((questWeapon == null) && st.def.fist && (worn != null) && (worn.getItemType() != WeaponType.FIST) && (worn.getItemType() != WeaponType.DUALFIST))
		{
			st.weapon = worn.getObjectId();
			player.getInventory().unEquipItemInSlot(Inventory.PAPERDOLL_RHAND);
			player.broadcastUserInfo();
			save(player, st);
		}
	}

	private static void disarm(Player player, State st)
	{
		if ((st == null) || (st.weapon == 0))
		{
			return;
		}
		final Item own = player.getInventory().getItemByObjectId(st.weapon);
		if ((own != null) && !own.isEquipped())
		{
			player.getInventory().equipItem(own);
			player.broadcastUserInfo();
		}
		st.weapon = 0;
	}

	/**
	 * Знаки пути собраны: дорос до уровня ступени — класс у ближнего мастера штатным скриптом смены класса; нет — охотится дальше, класс держит путь.
	 */
	private static boolean transfer(Player player, Path path, State st)
	{
		disarm(player, st);
		if (player.getLevel() < TRANSFER_LEVEL[path.tier - 1])
		{
			return false;
		}
		Npc master = null;
		for (int id : path.masters)
		{
			final Npc npc = npc(id);
			if ((npc != null) && ((master == null) || (player.calculateDistance2D(npc) < player.calculateDistance2D(master))))
			{
				master = npc;
			}
		}
		if (master == null)
		{
			return end(player, path.tier, "gave up: no master spawned for class " + path.classId);
		}
		come(player, master);
		// Событие — id класса («1» у ElfHumanFighterChange1), а у DarkElfChange1/2 — номер строки его таблицы; без знаков своего класса скрипт класс не меняет.
		final List<String> events = new ArrayList<>();
		events.add(String.valueOf(path.classId));
		for (int i = 0; i < 10; i++)
		{
			events.add(String.valueOf(i));
		}
		final int was = player.getPlayerClass().level();
		for (String name : MASTERS[path.tier - 1])
		{
			final Quest script = ScriptManager.getInstance().getScript(name);
			for (int e = 0; (script != null) && (e < events.size()) && (player.getPlayerClass().level() == was); e++)
			{
				try
				{
					script.onEvent(events.get(e), master, player);
				}
				catch (Exception ex)
				{
					// чужой номер для таблицы скрипта — дальше
				}
			}
			if (player.getPlayerClass().level() == path.tier)
			{
				player.giveAvailableSkills(true, true, true);
				PhantomHunterOutfit.setupCompanionCombat(player);
				player.broadcastUserInfo();
				end(player, path.tier, "done");
				LOGGER.info(TAG + player.getName() + " (" + player.getLevel() + ") becomes " + player.getPlayerClass().name() + " at " + master.getName() + " by " + Arrays.toString(path.quests) + " (" + name + ").");
				return true;
			}
		}
		LOGGER.info(TAG + player.getName() + " (" + player.getLevel() + ") gives up: master " + master.getName() + " refused class " + path.classId + "; the profession comes the old way.");
		return end(player, path.tier, "gave up: master " + master.getName() + " refused class " + path.classId);
	}

	private static boolean giveUp(Player player, State st, String why)
	{
		disarm(player, st);
		final Quest quest = ScriptManager.getInstance().getQuest(st.def.quest);
		final QuestState qs = quest == null ? null : quest.getQuestState(player, false);
		if ((qs != null) && qs.isStarted())
		{
			qs.exitQuest(true);
		}
		LOGGER.info(TAG + player.getName() + " (" + player.getLevel() + ") gives up Q" + st.def.quest + ": " + why + "; the profession comes the old way.");
		return end(player, st.def.tier, "gave up: " + why);
	}

	/**
	 * Ступень кончилась — классом или отказом; отказ — профа этой ступени прежним путём.
	 * @return {@code false}: тик охотнику свободен
	 */
	private static boolean end(Player player, int tier, String how)
	{
		STATES.remove(player.getObjectId());
		player.getVariables().remove(VAR_STATE);
		player.getVariables().set(endVar(tier), how);
		return false;
	}

	private static Npc npc(int npcId)
	{
		final Spawn spawn = SpawnTable.getInstance().getAnySpawn(npcId);
		return spawn == null ? null : spawn.getLastSpawn();
	}

	/**
	 * К NPC: издалека — телепортом, как игрок со свитком; рядом уже — остаётся.
	 */
	private static void come(Player player, Npc npc)
	{
		if (player.calculateDistance2D(npc) > 600)
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
			player.setTarget(null);
			player.teleToLocation(new Location(npc.getX() + Rnd.get(-80, 80), npc.getY() + Rnd.get(-80, 80), npc.getZ()), false);
		}
	}

	// ---------- Самопроверка (только тестовая папка данных, HuntersSelfTest = L2HUBPROFQ) ----------

	/**
	 * Самопроверка: охотника с профой — временно на класс ступени ниже ({@code tier} 1 — базовый, 2 — 1-я профа) и на квест; что было, записано в переменную,
	 * {@link #selfTestEnd} вернёт. Для квеста не первым в пути ({@code questId}) охотник получает знаки прежних квестов пути, их заберёт конец проверки.
	 * @return класс до отката или {@code null}, если охотник не подходит
	 */
	public static String selfTestBegin(Player player, int tier, int questId)
	{
		if (player.isSubClassActive() || !player.getVariables().getString(VAR_TEST, "").isEmpty())
		{
			return null;
		}
		PlayerClass base = player.getPlayerClass();
		PlayerClass goal = null;
		while ((base != null) && (base.level() > (tier - 1)))
		{
			if (base.level() == tier)
			{
				goal = base;
			}
			base = base.getParent();
		}
		load();
		final Path path = goal == null ? null : PATHS.get(goal.getId());
		if ((base == null) || (path == null) || Arrays.stream(path.quests).noneMatch(q -> q == questId))
		{
			return null;
		}
		final String was = player.getPlayerClass().name();
		player.getVariables().set(VAR_TEST, player.getPlayerClass().getId() + ";" + player.getBaseClass());
		player.getVariables().remove(endVar(tier));
		player.getVariables().remove(VAR_STATE);
		FORCED.add(player.getObjectId());
		player.setPlayerClass(base.getId());
		player.setBaseClass(base.getId());
		for (int id : path.quests)
		{
			if (id == questId)
			{
				break;
			}
			Quest.giveItems(player, QUESTS.get(id).mark, 1);
		}
		player.broadcastUserInfo();
		return was;
	}

	/**
	 * @return что с квестом самопроверки: {@code run}, {@code done} (класс у мастера или знак этого квеста), {@code gave up: …}
	 */
	public static String selfTestStatus(Player player, int tier, int questId)
	{
		final String end = player.getVariables().getString(endVar(tier), "");
		final Def def = QUESTS.get(questId);
		if (end.isEmpty() && (def != null) && (tier > 1) && (player.getInventory().getItemByItemId(def.mark) != null))
		{
			return "done";
		}
		return end.isEmpty() ? "run" : end;
	}

	/**
	 * Конец самопроверки, как бы она ни кончилась: квест снят, предметы квеста забраны, своё оружие надето, класс и база — как до отката.
	 * @return класс после возврата
	 */
	public static String selfTestEnd(Player player)
	{
		final String[] saved = player.getVariables().getString(VAR_TEST, "").split(";");
		for (int tier = 1; tier <= COUPONS.length; tier++)
		{
			if ("done".equals(player.getVariables().getString(endVar(tier), "")))
			{
				Quest.takeItems(player, COUPONS[tier - 1], 15); // купоны, что дал мастер
			}
		}
		FORCED.remove(player.getObjectId());
		final State st = STATES.remove(player.getObjectId());
		disarm(player, st);
		for (Def def : QUESTS.values())
		{
			final Quest quest = ScriptManager.getInstance().getQuest(def.quest);
			final QuestState qs = quest == null ? null : quest.getQuestState(player, false);
			if (qs != null)
			{
				qs.exitQuest(true);
			}
			if (quest != null)
			{
				final int[] items = quest.getRegisteredItemIds();
				if (items != null)
				{
					for (int id : items)
					{
						Quest.takeItems(player, id, -1);
					}
				}
				Quest.takeItems(player, def.mark, -1);
			}
		}
		if (saved.length == 2)
		{
			player.setPlayerClass(Integer.parseInt(saved[0]));
			player.setBaseClass(Integer.parseInt(saved[1]));
			player.giveAvailableSkills(true, true, true);
			PhantomHunterOutfit.setupCompanionCombat(player);
			player.broadcastUserInfo();
		}
		player.getVariables().remove(VAR_TEST);
		player.getVariables().remove(VAR_STATE);
		for (int tier = 1; tier <= MASTERS.length; tier++)
		{
			player.getVariables().remove(endVar(tier));
		}
		return player.getPlayerClass().name();
	}

	/**
	 * @return квесты пути по id класса ступени (1-й или 2-й профы) — для самопроверки
	 */
	public static Map<Integer, int[]> pathsByClass()
	{
		load();
		final Map<Integer, int[]> map = new HashMap<>();
		for (Path path : PATHS.values())
		{
			map.put(path.classId, path.quests.clone());
		}
		return Collections.unmodifiableMap(map);
	}

	/**
	 * @return с какого уровня берётся квест, 0 — нет в данных
	 */
	public static int questLevel(int questId)
	{
		load();
		final Def def = QUESTS.get(questId);
		return def == null ? 0 : def.level;
	}
}
