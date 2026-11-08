package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Kind;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Life;
import org.l2jmobius.gameserver.managers.phantom.PhantomLifecycleModel.Plan;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.IBaseEvent;
import org.l2jmobius.gameserver.mechanics.events.listeners.FunctionEventListener;
import org.l2jmobius.gameserver.mechanics.events.returns.TerminateReturn;

/**
 * Жизненный цикл охотников (раздел 4.151, вклад пользователя, решение автора 2026-11-07 «Да, без наследства»): тип игрока и личный потолок уровня, уход насовсем и
 * новички на освободившиеся места из таблицы сверх {@code HuntersMax}. Правила — {@link PhantomLifecycleModel}, здесь — хранение, журнал и связь с миром.<br>
 * Ключ {@code HunterLifecycle} (выкл). При {@code HunterLifecycleShadow} (вкл) мир не меняется: биографии живут в файле {@value #SHADOW_FILE}, решения — только
 * в журнале {@value #JOURNAL_FILE}. Без тени биографии — в глобальных переменных {@code L2HubLife_<имя>}: персонаж, вещи и память ушедшего остаются в базе, он
 * просто не входит. Перерывы ({@link PhantomAbsence}) и сутки ({@link PhantomDayCycle}) работают как раньше, поверх.
 */
final class PhantomLifecycle
{
	private static final Logger LOGGER = Logger.getLogger(PhantomLifecycle.class.getName());

	private static final String VARIABLE = "L2HubLife_";
	/** Глобальная переменная: день, когда директор уже решил. */
	private static final String DAY_VARIABLE = "L2HubLifeDay";
	private static final String JOURNAL_FILE = "./log/L2HubLifecycle.jsonl";
	private static final String SHADOW_FILE = "./log/L2HubLifecycleShadow.txt";
	private static final String SHADOW_DAY = "#day";
	/** Как часто смотреть мир, мс. */
	private static final long PERIOD = 60000;

	private final Map<String, Life> _shadow = new ConcurrentHashMap<>();
	private boolean _shadowLoaded;
	private boolean _shadowDirty;
	private long _lastRun;
	/** На ком уже висит запрет опыта на потолке: objectId → тот самый объект игрока (после перезахода — новый). */
	private final Map<Integer, Player> _capped = new ConcurrentHashMap<>();
	private final Set<String> _broken = ConcurrentHashMap.newKeySet();
	/** Из таблицы сверх предела, но уже в базе: не новички. */
	private final Set<String> _existing = ConcurrentHashMap.newKeySet();
	/** Мест в мире у последнего решения директора (самопроверка). */
	private volatile int _lastSeats = -1;

	private PhantomLifecycle()
	{
	}

	static PhantomLifecycle getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_LIFECYCLE;
	}

	/**
	 * @return ключ включён и не в тени: решения меняют мир
	 */
	static boolean live()
	{
		return PhantomHuntersConfig.HUNTER_LIFECYCLE && !PhantomHuntersConfig.HUNTER_LIFECYCLE_SHADOW;
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	private static int maxLevel()
	{
		return ExperienceData.getInstance().getMaxLevel() - 1;
	}

	// ---------- Для менеджера охотников ----------

	/**
	 * @return охотник ушёл насовсем ({@code PhantomHunterManager.control}): в мир не входит, начатое доводит, как по суткам
	 */
	boolean retired(String name)
	{
		if (!live())
		{
			return false;
		}
		final Life life = get(name);
		return (life != null) && !life.active();
	}

	/**
	 * @return охотник из таблицы сверх {@code HuntersMax} пришёл новичком на освободившееся место: для предела он не лишний
	 */
	boolean joined(String name)
	{
		if (!live())
		{
			return false;
		}
		final Life life = get(name);
		return (life != null) && life.newcomer() && life.active();
	}

	/**
	 * @return новичок ({@code PhantomManager.spawnHunter}): рождается с 1-го уровня, без старта ветерана и возраста мира
	 */
	boolean newcomer(String name)
	{
		if (!live())
		{
			return false;
		}
		final Life life = get(name);
		return (life != null) && life.newcomer();
	}

	/**
	 * Раз в {@value #PERIOD} мс из {@code PhantomHunterManager.control}: кто в мире — +1 сыгранный день и отметка потолка, раз в день — директор населения.
	 * @param names вся таблица охотников в её порядке (индекс — место в таблице)
	 */
	synchronized void control(long now, Supplier<List<String>> names)
	{
		if (!enabled() || ((now - _lastRun) < PERIOD))
		{
			return;
		}
		_lastRun = now;
		final boolean shadow = !live();
		if (shadow)
		{
			loadShadow();
		}
		else
		{
			_shadowLoaded = false;
			_shadow.clear();
		}

		final List<String> table = names.get();
		final int max = maxLevel();
		final long day = PhantomDayCycle.day(now);
		for (String name : table)
		{
			final Player player = World.getPlayer(name);
			if ((player == null) || !player.isOnline() || PhantomManager.getInstance().isSecret(player))
			{
				continue;
			}
			Life life = get(name);
			if (life == null)
			{
				life = PhantomLifecycleModel.create(name, player.getLevel(), day, max, false);
				journal("PROFILE", name, life, player.getLevel(), shadow);
			}
			if (!life.active())
			{
				continue;
			}
			final Life seen = PhantomLifecycleModel.observe(life, player.getLevel(), day);
			if ((life.doneAt() < 0) && (seen.doneAt() >= 0))
			{
				journal("GOAL", name, seen, player.getLevel(), shadow);
			}
			if (!seen.equals(life))
			{
				put(name, seen);
			}
			if (!shadow)
			{
				capListener(player);
			}
		}

		if (directedDay() != day)
		{
			direct(day, table, max, shadow);
		}
		saveShadow();
	}

	private void direct(long day, List<String> table, int max, boolean shadow)
	{
		// Места — таблица без запаса «следующего поколения» (раздел 4.155): при HuntersMax = 0 все прежние в мире, сверх них никого; запас ждёт ухода насовсем.
		int regular = 0;
		for (String name : table)
		{
			regular += reserve(name) ? 0 : 1;
		}
		final int seats = PhantomHuntersConfig.HUNTERS_MAX > 0 ? Math.min(PhantomHuntersConfig.HUNTERS_MAX, regular) : regular;
		_lastSeats = seats;
		int place = 0;
		int active = 0;
		final List<String> due = new ArrayList<>();
		final List<String> spares = new ArrayList<>();
		final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
		final Map<Integer, Integer> bands = new TreeMap<>();
		for (String name : table)
		{
			final Life life = get(name);
			final boolean inSeat = !reserve(name) && (place++ < seats);
			final boolean seated = inSeat ? (life == null) || life.active() : (life != null) && life.newcomer() && life.active();
			if (seated)
			{
				active++;
				if ((life != null) && PhantomLifecycleModel.due(life, name))
				{
					due.add(name);
				}
				if (life != null)
				{
					kinds.merge(life.kind(), 1, Integer::sum);
				}
				final int level = level(name);
				if (level > 0)
				{
					bands.merge(PhantomLifecycleModel.band(level), 1, Integer::sum);
				}
			}
			else if (!inSeat && (life == null) && !_existing.contains(name))
			{
				spares.add(name);
			}
		}
		// Новичок — только тот, кого ещё нет в базе: прежний житель сверх предела вернулся бы со своим уровнем. База — только у первых в очереди.
		int verified = 0;
		for (Iterator<String> it = spares.iterator(); it.hasNext() && (verified < PhantomHuntersConfig.HUNTER_LIFECYCLE_MAX_NEW_PER_DAY);)
		{
			final String name = it.next();
			if (CharInfoTable.getInstance().getIdByName(name) > 0)
			{
				_existing.add(name);
				it.remove();
			}
			else
			{
				verified++;
			}
		}

		final Plan plan = PhantomLifecycleModel.direct(day, seats, active, due, spares, PhantomHuntersConfig.HUNTER_LIFECYCLE_NEW_PER_DAY, PhantomHuntersConfig.HUNTER_LIFECYCLE_MAX_NEW_PER_DAY);
		for (String name : plan.retire())
		{
			final Life gone = PhantomLifecycleModel.retire(get(name), day);
			put(name, gone);
			journal("RETIRED", name, gone, level(name), shadow);
			if (!shadow)
			{
				final Player player = World.getPlayer(name);
				PhantomNews.post("gone", en() ? "Pawn " + name + " has quit the game for good" : name + " (пешка) больше не играет: бросил" + (((player != null) && player.getAppearance().isFemale()) ? "а" : "") + " игру", name, player);
			}
		}
		for (String name : plan.admit())
		{
			final Life life = PhantomLifecycleModel.create(name, 1, day, max, true);
			put(name, life);
			journal("JOINED", name, life, 1, shadow);
		}
		setDirectedDay(day);

		final StringBuilder sb = new StringBuilder();
		sb.append("{\"time\":").append(System.currentTimeMillis()).append(",\"event\":\"POPULATION\",\"shadow\":").append(shadow).append(",\"day\":").append(day);
		sb.append(",\"seats\":").append(seats).append(",\"active\":").append(active).append(",\"due\":").append(due.size()).append(",\"spares\":").append(spares.size());
		sb.append(",\"quota\":").append(plan.quota()).append(",\"retired\":").append(plan.retire().size()).append(",\"joined\":").append(plan.admit().size());
		sb.append(",\"bands\":{");
		boolean first = true;
		for (Map.Entry<Integer, Integer> entry : bands.entrySet())
		{
			sb.append(first ? "" : ",").append('"').append(PhantomLifecycleModel.bandName(entry.getKey())).append("\":").append(entry.getValue());
			first = false;
		}
		sb.append("},\"kinds\":{");
		first = true;
		for (Map.Entry<Kind, Integer> entry : kinds.entrySet())
		{
			sb.append(first ? "" : ",").append('"').append(entry.getKey().name()).append("\":").append(entry.getValue());
			first = false;
		}
		sb.append("}}");
		append(sb.toString());
		LOGGER.info(getClass().getSimpleName() + (shadow ? " (shadow)" : "") + ": day " + day + ", " + active + " of " + seats + " seats, " + due.size() + " ready to quit, " + spares.size() + " newcomers waiting; quits " + plan.retire() + ", joins " + plan.admit() + ".");
	}

	/**
	 * @return охотник из запаса «следующего поколения» (раздел 4.155)
	 */
	private static boolean reserve(String name)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(name);
		return (template != null) && template.isReserve();
	}

	/**
	 * @return уровень охотника в мире; 0 — его сейчас нет (в снимке населения — только кто в мире)
	 */
	private static int level(String name)
	{
		final Player player = World.getPlayer(name);
		return player != null ? player.getLevel() : 0;
	}

	// ---------- Потолок ----------

	private void capListener(Player player)
	{
		if (_capped.get(player.getObjectId()) == player)
		{
			return;
		}
		_capped.put(player.getObjectId(), player);
		player.addListener(new FunctionEventListener(player, EventType.ON_PLAYABLE_EXP_CHANGED, (IBaseEvent event) -> blocked(player) ? new TerminateReturn(true, true, true) : null, this));
	}

	/**
	 * @return опыт не идёт: охотник на своём потолке (живой игрок сюда не попадает — слушатель вешается только на охотников)
	 */
	private boolean blocked(Player player)
	{
		if (!live())
		{
			return false;
		}
		final Life life = get(player.getName());
		return (life != null) && life.active() && PhantomLifecycleModel.capped(life, player.getLevel());
	}

	// ---------- Хранение ----------

	private Life get(String name)
	{
		if (!live())
		{
			return _shadow.get(name);
		}
		final String text = GlobalVariablesManager.getInstance().getString(VARIABLE + name, "");
		final Life life = PhantomLifecycleModel.decode(text);
		if ((life == null) && !text.isEmpty() && _broken.add(name))
		{
			// Битая запись: охотник не трогается, запись создаётся заново с нынешнего уровня.
			LOGGER.warning(getClass().getSimpleName() + ": broken record for " + name + ": " + text);
			append("{\"time\":" + System.currentTimeMillis() + ",\"event\":\"RECOVERY_REQUIRED\",\"name\":\"" + escape(name) + "\"}");
		}
		return life;
	}

	private void put(String name, Life life)
	{
		if (!live())
		{
			_shadow.put(name, life);
			_shadowDirty = true;
			return;
		}
		GlobalVariablesManager.getInstance().set(VARIABLE + name, PhantomLifecycleModel.encode(life));
	}

	private long directedDay()
	{
		if (!live())
		{
			final Life marker = _shadow.get(SHADOW_DAY);
			return marker == null ? Long.MIN_VALUE : marker.born();
		}
		return GlobalVariablesManager.getInstance().getLong(DAY_VARIABLE, Long.MIN_VALUE);
	}

	private void setDirectedDay(long day)
	{
		if (!live())
		{
			_shadow.put(SHADOW_DAY, new Life(Kind.REGULAR, 1, day, 0, Long.MIN_VALUE, -1, -1, false, false));
			_shadowDirty = true;
			return;
		}
		GlobalVariablesManager.getInstance().set(DAY_VARIABLE, day);
	}

	private void loadShadow()
	{
		if (_shadowLoaded)
		{
			return;
		}
		_shadowLoaded = true;
		_shadow.clear();
		final Path path = Path.of(SHADOW_FILE);
		if (!Files.exists(path))
		{
			return;
		}
		try
		{
			for (String line : Files.readAllLines(path, StandardCharsets.UTF_8))
			{
				final int eq = line.indexOf('=');
				final Life life = eq > 0 ? PhantomLifecycleModel.decode(line.substring(eq + 1)) : null;
				if (life != null)
				{
					_shadow.put(line.substring(0, eq), life);
				}
			}
		}
		catch (IOException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": could not read " + SHADOW_FILE + ": " + e.getMessage());
		}
	}

	private void saveShadow()
	{
		if (!_shadowDirty)
		{
			return;
		}
		_shadowDirty = false;
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Life> entry : new TreeMap<>(_shadow).entrySet())
		{
			sb.append(entry.getKey()).append('=').append(PhantomLifecycleModel.encode(entry.getValue())).append('\n');
		}
		try
		{
			final Path path = Path.of(SHADOW_FILE);
			final Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(temporary, sb.toString(), StandardCharsets.UTF_8);
			Files.move(temporary, path, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": could not write " + SHADOW_FILE + ": " + e.getMessage());
		}
	}

	// ---------- Журнал ----------

	private void journal(String event, String name, Life life, int level, boolean shadow)
	{
		append("{\"time\":" + System.currentTimeMillis() + ",\"event\":\"" + event + "\",\"shadow\":" + shadow + ",\"name\":\"" + escape(name) + "\",\"kind\":\"" + life.kind().name() + "\",\"level\":" + level + ",\"target\":" + life.target() + ",\"played\":" + life.played() + ",\"newcomer\":" + life.newcomer() + ",\"oldTimer\":" + life.oldTimer() + "}");
	}

	private static String escape(String text)
	{
		return text.replace("\\", "\\\\").replace("\"", "\\\"");
	}

	private static void append(String line)
	{
		try
		{
			final Path path = Path.of(JOURNAL_FILE);
			Files.createDirectories(path.toAbsolutePath().getParent());
			Files.writeString(path, line + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		}
		catch (IOException e)
		{
			LOGGER.warning(PhantomLifecycle.class.getSimpleName() + ": could not write " + JOURNAL_FILE + ": " + e.getMessage());
		}
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка LIFECYCLE ({@code HuntersSelfTest}, только на копии базы): модель и симуляция мира на 480 дней ({@link PhantomLifecycleModelTest}), проход в тени по
	 * нынешнему миру, затем по-настоящему: потолок не даёт опыта; запас «следующего поколения» (раздел 4.155) не в мире; директор сжатым временем отпускает готового насовсем
	 * (он выходит, персонаж остаётся в базе), а на другой день на его место приходит новичок из запаса с 1-го уровня. Записи теста и ключи
	 * возвращаются, мир — как был.
	 * @param names таблица охотников в её порядке
	 * @param online кто в мире
	 */
	void selfTest(Supplier<List<String>> names, Supplier<List<Player>> online)
	{
		org.l2jmobius.commons.threads.ThreadPool.execute(() -> runTest(names, online));
	}

	private void runTest(Supplier<List<String>> names, Supplier<List<Player>> online)
	{
		final String tag = "SelfTest LIFECYCLE: ";
		final boolean keyWas = PhantomHuntersConfig.HUNTER_LIFECYCLE;
		final boolean shadowWas = PhantomHuntersConfig.HUNTER_LIFECYCLE_SHADOW;
		final int perDayWas = PhantomHuntersConfig.HUNTER_LIFECYCLE_NEW_PER_DAY;
		final int maxPerDayWas = PhantomHuntersConfig.HUNTER_LIFECYCLE_MAX_NEW_PER_DAY;
		final List<String> touched = new ArrayList<>();
		int failed = 0;
		try
		{
			final List<String> report = new ArrayList<>();
			final List<String> model = PhantomLifecycleModelTest.check(report);
			report.forEach(line -> LOGGER.info(tag + line));
			LOGGER.info(tag + (model.isEmpty() ? "model OK" : "model FAIL: " + model));
			failed += model.size();

			// Тень: профили по миру, мир не меняется.
			PhantomHuntersConfig.HUNTER_LIFECYCLE = true;
			PhantomHuntersConfig.HUNTER_LIFECYCLE_SHADOW = true;
			synchronized (this)
			{
				_lastRun = 0;
				_shadowLoaded = false;
			}
			control(System.currentTimeMillis(), names);
			final Map<Kind, Integer> kinds = new EnumMap<>(Kind.class);
			int profiles = 0;
			for (Map.Entry<String, Life> entry : _shadow.entrySet())
			{
				if (!SHADOW_DAY.equals(entry.getKey()))
				{
					kinds.merge(entry.getValue().kind(), 1, Integer::sum);
					profiles++;
				}
			}
			final boolean shadowOk = (profiles > 0) && (directedDay() == PhantomDayCycle.day(System.currentTimeMillis()));
			failed += shadowOk ? 0 : 1;
			LOGGER.info(tag + (shadowOk ? "" : "WRONG ") + "shadow: " + profiles + " profiles of " + online.get().size() + " in the world, kinds " + kinds + ", directed today " + (directedDay() == PhantomDayCycle.day(System.currentTimeMillis())) + ".");

			// По-настоящему. Директор сегодня уже «решил»: в тесте уходят и приходят только свои.
			final long day = PhantomDayCycle.day(System.currentTimeMillis());
			GlobalVariablesManager.getInstance().set(DAY_VARIABLE, day);
			PhantomHuntersConfig.HUNTER_LIFECYCLE_SHADOW = false;
			final List<Player> world = new ArrayList<>(online.get());
			world.removeIf(player -> PhantomManager.getInstance().isSecret(player) || player.isInOlympiadMode() || player.isDead());
			// Под потолком хроники опыт идёт и без ключа — для проверки потолка первым ставится тот, кто ниже.
			world.sort(java.util.Comparator.comparingInt(Player::getLevel));
			if (world.size() < 2)
			{
				LOGGER.info(tag + "FAIL: fewer than 2 hunters in the world.");
				return;
			}

			// Потолок: опыт не идёт.
			final Player capped = world.get(0);
			touched.add(capped.getName());
			put(capped.getName(), new Life(Kind.REGULAR, capped.getLevel(), day, 1, day, 1, -1, false, false));
			capListener(capped);
			final long before = capped.getExp();
			capped.getStat().addExp(1000);
			final boolean blockedOk = capped.getExp() == before;
			GlobalVariablesManager.getInstance().remove(VARIABLE + capped.getName());
			capped.getStat().addExp(1000);
			final boolean freeOk = capped.getExp() > before;
			failed += (blockedOk && freeOk) ? 0 : 1;
			LOGGER.info(tag + ((blockedOk && freeOk) ? "" : "WRONG ") + "cap: " + capped.getName() + " level " + capped.getLevel() + ", exp blocked on the cap " + blockedOk + ", flows without it " + freeOk + ".");

			// Запас «следующего поколения» (раздел 4.155): до прихода новичком его нет в мире.
			final List<String> table = names.get();
			int reserves = 0;
			int reservesOnline = 0;
			int regular = 0;
			for (String name : table)
			{
				if (reserve(name))
				{
					reserves++;
					final Player player = World.getPlayer(name);
					reservesOnline += (player != null) && player.isOnline() ? 1 : 0;
				}
				else
				{
					regular++;
				}
			}
			final boolean reserveOk = (reserves > 0) && (reservesOnline == 0);
			failed += reserveOk ? 0 : 1;
			LOGGER.info(tag + (reserveOk ? "" : "WRONG ") + "reserve: " + reserves + " in the table, " + reservesOnline + " of them in the world, " + regular + " regular hunters.");

			// Уход насовсем и новичок — через директора, сжатым временем: завтра готовый к уходу уходит, послезавтра на его место приходит новичок из запаса.
			PhantomHuntersConfig.HUNTER_LIFECYCLE_NEW_PER_DAY = 2;
			PhantomHuntersConfig.HUNTER_LIFECYCLE_MAX_NEW_PER_DAY = 4;
			final long dayMillis = 86400000L;
			final long base = System.currentTimeMillis();
			final Player quitting = world.get(1);
			final String quitter = quitting.getName();
			touched.add(quitter);
			// Дошёл до потолка давно: отжил своё, директор отпустит его первым же днём.
			put(quitter, new Life(Kind.REGULAR, quitting.getLevel(), day, 100, day, 0, -1, false, false));
			synchronized (this)
			{
				_lastRun = 0;
			}
			control(base + dayMillis, names);
			final int seatsExpected = PhantomHuntersConfig.HUNTERS_MAX > 0 ? Math.min(PhantomHuntersConfig.HUNTERS_MAX, regular) : regular;
			final boolean seatsOk = _lastSeats == seatsExpected;
			final Life gone = get(quitter);
			final boolean retiredOk = (gone != null) && !gone.active();
			boolean left = false;
			for (int i = 0; (i < 60) && !left; i++)
			{
				Thread.sleep(5000);
				final Player now = World.getPlayer(quitter);
				left = (now == null) || !now.isOnline();
			}
			final boolean keptOk = CharInfoTable.getInstance().getIdByName(quitter) > 0;
			failed += (seatsOk && retiredOk && left && keptOk) ? 0 : 1;
			LOGGER.info(tag + ((seatsOk && retiredOk && left && keptOk) ? "" : "WRONG ") + "retire (day +1): seats " + _lastSeats + " of expected " + seatsExpected + ", " + quitter + " level " + quitting.getLevel() + " retired by the director " + retiredOk + ", left the world " + left + ", character kept " + keptOk + ".");

			String admitted = null;
			synchronized (this)
			{
				_lastRun = 0;
			}
			control(base + (2 * dayMillis), names);
			for (String name : names.get())
			{
				final Life life = get(name);
				if ((life != null) && life.newcomer() && life.active())
				{
					admitted = name;
					break;
				}
			}
			if (admitted == null)
			{
				failed++;
				LOGGER.info(tag + "WRONG join (day +2): the director admitted nobody to the free seat.");
			}
			else
			{
				touched.add(admitted);
				final boolean fromReserve = reserve(admitted);
				final boolean fresh = CharInfoTable.getInstance().getIdByName(admitted) <= 0;
				Player joined = null;
				for (int i = 0; (i < 60) && (joined == null); i++)
				{
					Thread.sleep(5000);
					final Player now = World.getPlayer(admitted);
					joined = (now != null) && now.isOnline() ? now : null;
				}
				final boolean joinOk = fromReserve && (joined != null) && (joined.getLevel() <= 2);
				failed += joinOk ? 0 : 1;
				LOGGER.info(tag + (joinOk ? "" : "WRONG ") + "join (day +2): " + admitted + " from the reserve " + fromReserve + ", no character before the join " + fresh + ", entered " + (joined != null) + ", level " + (joined == null ? 0 : joined.getLevel()) + (joined == null ? ", daily rhythm wants online " + PhantomDayCycle.wantsOnline(admitted, System.currentTimeMillis()) : "") + ".");
			}
		}
		catch (InterruptedException e)
		{
			Thread.currentThread().interrupt();
		}
		catch (Exception e)
		{
			failed++;
			LOGGER.warning(tag + "failed: " + e);
		}
		finally
		{
			// Записи теста и все, что завёл проход по-настоящему.
			touched.addAll(names.get());
			for (String name : touched)
			{
				GlobalVariablesManager.getInstance().remove(VARIABLE + name);
			}
			GlobalVariablesManager.getInstance().remove(DAY_VARIABLE);
			GlobalVariablesManager.getInstance().storeMe();
			PhantomHuntersConfig.HUNTER_LIFECYCLE = keyWas;
			PhantomHuntersConfig.HUNTER_LIFECYCLE_SHADOW = shadowWas;
			PhantomHuntersConfig.HUNTER_LIFECYCLE_NEW_PER_DAY = perDayWas;
			PhantomHuntersConfig.HUNTER_LIFECYCLE_MAX_NEW_PER_DAY = maxPerDayWas;
			synchronized (this)
			{
				// Сжатое время теста сдвинуло отметку в будущее: обычный ход — с нынешней минуты.
				_lastRun = 0;
			}
		}
		LOGGER.info(tag + (failed == 0 ? "PASS" : "FAIL") + ", failed checks " + failed + ".");
		LOGGER.info(tag + "done.");
	}

	private static class SingletonHolder
	{
		protected static final PhantomLifecycle INSTANCE = new PhantomLifecycle();
	}
}
