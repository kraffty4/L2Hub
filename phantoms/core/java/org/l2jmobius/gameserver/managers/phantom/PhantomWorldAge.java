package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;

/**
 * L2Hub: возраст мира (docs/PHANTOMS.md, раздел 4.90, ключ {@code HunterWorldAgeDays}): те же охотники, но мир как будто открылся N дней назад. Разовое действие над миром:
 * первый старт с ключом записывает возраст в {@value #WORLD_VARIABLE}, и дальше ключ на него не влияет, снимает только сброс всех охотников ({@link #forget}).<br>
 * Поднимает {@link PhantomManager#spawnHunter} при первом входе охотника после метки, тем же путём, что ветерана (раздел 4.52). Доля у каждого своя и стабильная, от имени:
 * {@link #level}. Только вверх: кто выше, остаётся как есть. Напарников, живых и секретного персонажа это не касается.
 */
final class PhantomWorldAge
{
	private static final Logger LOGGER = Logger.getLogger(PhantomWorldAge.class.getName());

	/** Возраст мира в днях: значение ключа при первом старте. */
	private static final String WORLD_VARIABLE = "L2HubWorldAgeDays";
	/** Охотник свою долю уже получил: метка персонажа, удаляется вместе с ним при сбросе. */
	private static final String HUNTER_VARIABLE = "L2HubWorldAged";
	/** Потолок мира растёт к потолку хроники так: за столько дней проходит две трети пути. */
	private static final double TOP_DAYS = 12;
	/** Степень кривой потолка: меньше единицы — первые дни самые быстрые. 7 дней — 54 % потолка хроники, 14 — 76 %, 30 — 94 %. */
	private static final double TOP_POWER = 0.75;
	/** Верхушка: столько процентов охотников. */
	private static final int TOP_PERCENT = 5;
	/** Верхушка и середина вместе: столько процентов. Остальные — низы. */
	private static final int MIDDLE_PERCENT = 30;
	private static final int SALT_LAYER = 8101;
	private static final int SALT_PLACE = 8102;
	/** Самопроверка: столько мс живут после входа всех, прежде чем на них смотреть, и столько между двумя взглядами. */
	private static final long TEST_WAIT = 240000;
	private static final long TEST_STEP = 60000;

	/** Сколько охотников поднялось с этого старта: второму старту положено 0. */
	private static final AtomicInteger RISEN = new AtomicInteger();

	private PhantomWorldAge()
	{
	}

	/**
	 * Раз в 10 с из {@link PhantomHunterManager}, до входа охотников: первый раз с ключом больше нуля — записать возраст мира.
	 */
	static void control()
	{
		final int key = PhantomHuntersConfig.HUNTER_WORLD_AGE_DAYS;
		if ((key > 0) && (GlobalVariablesManager.getInstance().getInt(WORLD_VARIABLE, 0) <= 0))
		{
			GlobalVariablesManager.getInstance().set(WORLD_VARIABLE, key);
			GlobalVariablesManager.getInstance().storeMe();
			LOGGER.info(PhantomWorldAge.class.getSimpleName() + ": The world is " + key + " days old now, hunters rise as they enter. Changing the key does nothing after this; only a reset of all hunters does.");
		}
	}

	/**
	 * Сброс всех охотников: персонажей больше нет, мир начинается заново. Если ключ ещё стоит, следующий {@link #control} запишет возраст снова.
	 */
	static void forget()
	{
		GlobalVariablesManager.getInstance().remove(WORLD_VARIABLE);
		GlobalVariablesManager.getInstance().storeMe();
	}

	/**
	 * @return возраст мира в днях; 0 — ключ снят или возраста ещё нет
	 */
	static int days()
	{
		return PhantomHuntersConfig.HUNTER_WORLD_AGE_DAYS > 0 ? GlobalVariablesManager.getInstance().getInt(WORLD_VARIABLE, 0) : 0;
	}

	/**
	 * @return выше этого охотника не поднимают: последний уровень хроники или самый высокий спот, если он ниже, — выше охотнику негде качаться
	 */
	private static int cap()
	{
		int top = 1;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			top = Math.max(top, spot.level);
		}
		return Math.min(top, PlayerConfig.PLAYER_MAXIMUM_LEVEL - 1);
	}

	/**
	 * @return уровень самых сильных в мире такого возраста
	 */
	private static double top(int days, int cap)
	{
		return cap * Math.pow(1 - Math.exp(-days / TOP_DAYS), TOP_POWER);
	}

	/**
	 * Своя доля охотника. Верхушка — 85–100 % от {@link #top}, середина — 45–75 %, низы — до 40 % со смещением вниз: большинство только начали.
	 * @param name имя
	 * @param days возраст мира
	 * @param cap потолок хроники
	 * @return его уровень в этом мире
	 */
	static int level(String name, int days, int cap)
	{
		final int layer = PhantomDayCycle.mix(name, 0, SALT_LAYER, 100);
		final double place = PhantomDayCycle.mix(name, 0, SALT_PLACE, 1000) / 999.0;
		final double part = layer < TOP_PERCENT ? 0.85 + (0.15 * place) : layer < MIDDLE_PERCENT ? 0.45 + (0.30 * place) : 0.40 * place * place;
		return Math.max(1, Math.min(cap, (int) Math.round(top(days, cap) * part)));
	}

	/**
	 * @param player охотник перед входом в мир
	 * @param template его описание
	 * @return до какого уровня его поднять; 0 — не поднимать
	 */
	static int levelFor(Player player, PhantomTemplate template)
	{
		final int days = days();
		if ((days <= 0) || template.isReaper() || (player.getVariables().getInt(HUNTER_VARIABLE, 0) > 0))
		{
			return 0;
		}
		return level(template.getName(), days, cap());
	}

	/**
	 * Свою долю охотник получил, в том числе если он и так выше неё: больше его не трогать.
	 * @param player охотник
	 */
	static void mark(Player player)
	{
		final int days = days();
		if ((days > 0) && (player.getVariables().getInt(HUNTER_VARIABLE, 0) <= 0))
		{
			player.getVariables().set(HUNTER_VARIABLE, days);
			player.getVariables().storeMe();
		}
	}

	/**
	 * @param player поднятый охотник
	 * @param from с какого уровня
	 */
	static void risen(Player player, int from)
	{
		RISEN.incrementAndGet();
		LOGGER.info(PhantomWorldAge.class.getSimpleName() + ": " + player.getName() + " rose from level " + from + " to " + player.getLevel() + ".");
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка WORLDAGE ({@code HuntersSelfTest}, только на копии базы). Гистограмма уровней тех, кто в мире; через 5 минут по верхушке и середине — уровень против своей доли,
	 * оружие, грудь, сосы, банки, адена, опыт за эти 5 минут, спот и работает ли; куча после GC. Ключ и базу ставит и возвращает прогон.
	 * @param online кто в мире
	 * @param state фаза и спот охотника от менеджера
	 */
	static void selfTest(Supplier<List<Player>> online, Function<Player, String> state)
	{
		ThreadPool.execute(() -> runTest(online, state));
	}

	private static void runTest(Supplier<List<Player>> online, Function<Player, String> state)
	{
		final String tag = "SelfTest WORLDAGE: ";
		try
		{
			final int days = days();
			final int cap = cap();
			LOGGER.info(tag + "key " + PhantomHuntersConfig.HUNTER_WORLD_AGE_DAYS + ", world " + days + " days, cap " + cap + ", top level " + (days > 0 ? Math.round(top(days, cap)) : 0) + ", in the world " + online.get().size() + ", risen since start " + RISEN.get());
			Thread.sleep(TEST_WAIT);
			final List<Player> first = new ArrayList<>(online.get());
			final Map<String, long[]> seen = new TreeMap<>();
			for (Player player : first)
			{
				seen.put(player.getName(), new long[]
				{
					player.getX(),
					player.getY(),
					player.getExp()
				});
			}
			Thread.sleep(TEST_STEP);

			final List<Player> all = new ArrayList<>(online.get());
			final Map<Integer, Integer> histogram = new TreeMap<>();
			final int lowest = days > 0 ? (int) Math.round(top(days, cap) * 0.45) : 40;
			final List<Player> upper = new ArrayList<>();
			int below = 0;
			for (Player player : all)
			{
				histogram.merge(player.getLevel() / 10, 1, Integer::sum);
				final int own = days > 0 ? level(player.getName(), days, cap) : 0;
				if (player.getLevel() < own)
				{
					below++;
				}
				if ((days > 0 ? own : player.getLevel()) >= lowest)
				{
					upper.add(player);
				}
			}
			upper.sort(Comparator.comparingInt(Player::getLevel).reversed());
			LOGGER.info(tag + "levels by tens " + histogram + " of " + all.size() + ", below their share " + below + ", risen since start " + RISEN.get());
			int standing = 0;
			for (Player player : upper)
			{
				final long[] was = seen.get(player.getName());
				final boolean moved = (was == null) || (Math.hypot(player.getX() - was[0], player.getY() - was[1]) > 50);
				final long gained = (was == null) ? 0 : player.getExp() - was[2];
				final boolean busy = moved || (gained > 0) || player.isInCombat() || player.isAttackingNow() || player.isCastingNow() || player.isInStoreMode();
				if (!busy)
				{
					standing++;
				}
				final String stands = busy ? "" : " | STANDS target " + ((player.getTarget() == null) ? "none" : player.getTarget().getName()) + (player.isSitting() ? ", sits" : "") + (player.isMoving() ? ", moving" : "");
				final Item weapon = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
				final Item chest = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_CHEST);
				LOGGER.info(tag + player.getName() + " level " + player.getLevel() + " share " + (days > 0 ? level(player.getName(), days, cap) : 0) + " " + player.getPlayerClass().name() + " | " + (weapon == null ? "no weapon" : weapon.getTemplate().getName()) + " | " + (chest == null ? "no chest" : chest.getTemplate().getName()) + " | " + PhantomHunterOutfit.suppliesReport(player) + " | adena " + player.getAdena() + " | exp +" + gained + " | " + state.apply(player) + stands);
			}
			System.gc();
			final Runtime runtime = Runtime.getRuntime();
			LOGGER.info(tag + "upper " + upper.size() + ", standing " + standing + ", heap after GC " + ((runtime.totalMemory() - runtime.freeMemory()) / 1048576) + " MB of " + (runtime.maxMemory() / 1048576) + " MB.");
			LOGGER.info(tag + "done.");
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed.", e);
		}
	}
}
