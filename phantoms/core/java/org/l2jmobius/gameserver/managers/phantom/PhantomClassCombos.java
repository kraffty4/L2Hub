package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Logger;

import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import org.l2jmobius.gameserver.config.ServerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Kind;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * Мастерство охотников по связкам классов (раздел 4.153, слово автора 2026-11-07 «Я тоже», вариант (б)): ветеран жмёт атаки в порядке связки своего класса, новичок —
 * хуже. Связки — {@code data/L2HubClassCombos.xml} (пишет {@code tools/class_combos.py} из базы знаний, только id и порядок); класс без связки и хроника без файла —
 * автобой как раньше. Мастерство — от типа игрока жизненного цикла ({@link PhantomLifecycleModel}): залётный новичок связки не знает, казуал знает только главную
 * кнопку, остальные — всю. Тип считается из имени и уровня и без ключа {@code HunterLifecycle}: отдельной оси поверх {@code risk} нет.
 */
final class PhantomClassCombos
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClassCombos.class.getName());
	private static final String FILE = "data/L2HubClassCombos.xml";

	/** Насколько охотник знает связку своего класса. */
	enum Mastery
	{
		/** Связки не знает: атаки по силе, как без связок. */
		NOVICE,
		/** Знает главную кнопку: первая атака связки, дальше по силе. */
		LEARNING,
		/** Жмёт по связке: её атаки по порядку, дальше по силе. */
		VETERAN
	}

	private static volatile Map<Integer, List<Integer>> _combos;

	private PhantomClassCombos()
	{
	}

	private static Map<Integer, List<Integer>> combos()
	{
		Map<Integer, List<Integer>> combos = _combos;
		if (combos == null)
		{
			combos = load();
			_combos = combos;
		}
		return combos;
	}

	private static Map<Integer, List<Integer>> load()
	{
		final Map<Integer, List<Integer>> combos = new HashMap<>();
		final File file = new File(ServerConfig.DATAPACK_ROOT, FILE);
		if (!file.isFile())
		{
			return combos;
		}
		try
		{
			final Document document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file);
			final NodeList nodes = document.getElementsByTagName("class");
			for (int i = 0; i < nodes.getLength(); i++)
			{
				final Element element = (Element) nodes.item(i);
				final List<Integer> ids = new ArrayList<>();
				for (String id : element.getAttribute("attacks").split(","))
				{
					if (!id.isBlank())
					{
						ids.add(Integer.parseInt(id.strip()));
					}
				}
				combos.put(Integer.parseInt(element.getAttribute("id")), List.copyOf(ids));
			}
			LOGGER.info(PhantomClassCombos.class.getSimpleName() + ": " + combos.size() + " class combos.");
		}
		catch (Exception e)
		{
			LOGGER.warning(PhantomClassCombos.class.getSimpleName() + ": could not read " + FILE + ": " + e.getMessage());
		}
		return combos;
	}

	/**
	 * @return связка класса охотника: своя или, пока он не дорос до третьей профессии, того класса, в который он растёт; {@code null} — нет
	 */
	static List<Integer> combo(Player player)
	{
		final Map<Integer, List<Integer>> combos = combos();
		if (combos.isEmpty())
		{
			return null;
		}
		final PlayerClass own = player.getPlayerClass();
		final List<Integer> direct = combos.get(own.getId());
		if (direct != null)
		{
			return direct;
		}
		for (PlayerClass next : PlayerClass.values())
		{
			if ((next != own) && combos.containsKey(next.getId()) && next.childOf(own))
			{
				return combos.get(next.getId());
			}
		}
		return null;
	}

	/**
	 * @return мастерство по типу игрока: залётный новичок — {@link Mastery#NOVICE}, казуал — {@link Mastery#LEARNING}, остальные — {@link Mastery#VETERAN}
	 */
	static Mastery mastery(Player player)
	{
		final Kind kind = PhantomLifecycleModel.create(player.getName(), player.getLevel(), 0, ExperienceData.getInstance().getMaxLevel() - 1, false).kind();
		return kind == Kind.TOURIST ? Mastery.NOVICE : kind == Kind.CASUAL ? Mastery.LEARNING : Mastery.VETERAN;
	}

	/**
	 * Очередь атак по мастерству ({@code PhantomHunterOutfit.setupCombat}).
	 * @param player охотник
	 * @param attacks атакующие скиллы, уже отобранные автобоем, по его порядку
	 * @return новый список: атаки связки, которые есть среди {@code attacks} и годятся под нынешнее оружие, по порядку связки, за ними остальные по прежнему порядку
	 */
	static List<Skill> order(Player player, List<Skill> attacks)
	{
		return PhantomHuntersConfig.HUNTER_CLASS_COMBOS ? order(player, attacks, mastery(player)) : attacks;
	}

	static List<Skill> order(Player player, List<Skill> attacks, Mastery mastery)
	{
		final List<Integer> combo = mastery == Mastery.NOVICE ? null : combo(player);
		if ((combo == null) || combo.isEmpty())
		{
			return attacks;
		}
		final List<Skill> first = new ArrayList<>();
		for (int id : combo)
		{
			for (Skill skill : attacks)
			{
				// Под это оружие удар не встанет (щит, кастет): в слоте он только мешал бы. У ударов на зарядах в том же условии и заряды (<and><using/><player Charges/></and>):
				// проверка зависела бы от зарядов в момент настройки, поэтому их не проверяем — оружие у них и так главное оружие класса.
				if ((skill.getId() == id) && !first.contains(skill) && ((skill.getChargeConsumeCount() > 0) || skill.checkCondition(player, player, false)))
				{
					first.add(skill);
				}
			}
			if ((mastery == Mastery.LEARNING) && !first.isEmpty())
			{
				break;
			}
		}
		final List<Skill> result = new ArrayList<>(first);
		for (Skill skill : attacks)
		{
			if (!result.contains(skill))
			{
				result.add(skill);
			}
		}
		return result;
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка COMBOS ({@code HuntersSelfTest}, только на копии базы): у каждого охотника в мире со связкой класса очередь автобоя совпадает с той, что дают его
	 * мастерство и связка ({@code PhantomHunterOutfit.testQueue}); в лог — очереди ветерана, казуала и новичка рядом. Итог — {@code PASS}, если совпало у всех, ветераны
	 * есть и хотя бы у одного связка поменяла очередь против новичка.
	 * @param online кто в мире
	 */
	static void selfTest(Supplier<List<Player>> online)
	{
		org.l2jmobius.commons.threads.ThreadPool.execute(() ->
		{
			final String tag = "SelfTest COMBOS: ";
			try
			{
				// У Эпилога свой PhantomHunterOutfit (patches/epilogue/0512) без testQueue: вызов по имени, чтобы ядро собиралось и там.
				final java.lang.reflect.Method testQueue;
				try
				{
					testQueue = PhantomHunterOutfit.class.getDeclaredMethod("testQueue", Player.class, Mastery.class);
				}
				catch (NoSuchMethodException e)
				{
					LOGGER.warning(tag + "not available on this chronicle.");
					return;
				}
				final java.util.function.BiFunction<Player, Mastery, List<Integer>> queueOf = (p, m) ->
				{
					try
					{
						@SuppressWarnings("unchecked")
						final List<Integer> ids = (List<Integer>) testQueue.invoke(null, p, m);
						return ids;
					}
					catch (ReflectiveOperationException e)
					{
						throw new IllegalStateException(e);
					}
				};
				final Map<Mastery, Integer> count = new HashMap<>();
				int wrong = 0;
				int checked = 0;
				int changed = 0;
				LOGGER.info(tag + "key " + PhantomHuntersConfig.HUNTER_CLASS_COMBOS + ", " + combos().size() + " class combos, in the world " + online.get().size() + ".");
				for (Player player : online.get())
				{
					final List<Integer> combo = combo(player);
					if (combo == null)
					{
						continue;
					}
					checked++;
					final Mastery mastery = mastery(player);
					count.merge(mastery, 1, Integer::sum);
					final List<Integer> queue = new ArrayList<>(player.getAutoUseSettings().getAutoSkills());
					final List<Integer> expected = queueOf.apply(player, PhantomHuntersConfig.HUNTER_CLASS_COMBOS ? mastery : Mastery.NOVICE);
					final List<Integer> veteran = queueOf.apply(player, Mastery.VETERAN);
					final List<Integer> novice = queueOf.apply(player, Mastery.NOVICE);
					final boolean ok = queue.equals(expected);
					wrong += ok ? 0 : 1;
					changed += (mastery == Mastery.VETERAN) && !veteran.equals(novice) ? 1 : 0;
					LOGGER.info(tag + (ok ? "" : "WRONG ") + player.getName() + " level " + player.getLevel() + " " + player.getPlayerClass().name() + " " + mastery + ": combo " + combo + ", queue " + queue + (ok ? "" : ", expected " + expected) + " | veteran " + veteran + ", main button " + queueOf.apply(player, Mastery.LEARNING) + ", novice " + novice + ".");
				}
				final boolean pass = (wrong == 0) && (count.getOrDefault(Mastery.VETERAN, 0) > 0) && (changed > 0);
				LOGGER.info(tag + (pass ? "PASS" : "FAIL") + ": checked " + checked + ", wrong " + wrong + ", mastery " + count + ", veterans whose queue the combo changed " + changed + ".");
			}
			catch (Exception e)
			{
				LOGGER.warning(tag + "failed: " + e);
			}
			LOGGER.info(tag + "done.");
		});
	}
}
