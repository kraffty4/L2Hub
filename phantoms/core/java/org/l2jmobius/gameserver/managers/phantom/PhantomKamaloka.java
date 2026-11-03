package org.l2jmobius.gameserver.managers.phantom;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.script.Quest;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: охотники пати ходят в Камалоку (Hall of the Abyss; ключ {@code HunterKamaloka} в PhantomHunters.ini).<br>
 * Раз в {@code HunterKamalokaInterval} минут 3–{@code HunterKamalokaSize} свободных охотников одного зала
 * (войти можно, если уровень каждого в пределах level ± 5) собирают пати, самый высокий — лидер.
 * Камалока в High Five открывается заново каждый день: кто сегодня уже был, не идёт.
 * Есть живой под этот зал — сначала сбор 3 минуты у лидера: крик, сообщение живым, шёпот-намёк рядом, {@code .kama join} / {@code .kama go}.
 * Пати входит в Камалоку штатным скриптом Mobius ({@code Kamaloka}, как будто лидер нажал кнопку у Pathfinder Worker).
 * Внутри охотники бьются родным AutoPlay; когда внутри не осталось никого из участников, лидер кричит итог
 * (успех — лёг босс: у вошедших появился откат инстанса), при успехе ещё один охотник говорит в пати,
 * пати распускается, охотники возвращаются на кач. День похода отмечается только у вошедших.<br>
 * Охотник в походе уходит из правил кача: его ведёт {@link #drive} из {@code PhantomHunterManager.think}.
 */
final class PhantomKamaloka
{
	private static final Logger LOGGER = Logger.getLogger(PhantomKamaloka.class.getName());

	/** Переменная охотника с днём последнего похода в Камалоку. */
	private static final String DAY_VARIABLE = "L2HubKamalokaDay";
	/** Минимум охотников для похода. */
	private static final int MIN_PARTY = 3;
	/** Полная пати Камалоки: не больше шести. */
	private static final int FULL_PARTY = 6;
	/** Страховка: поход дольше 45 минут снимается (Камалока длится 30). */
	private static final long KAMALOKA_TIME = 45 * 60000L;
	/** Сбор с живыми у лидера: 3 минуты. */
	private static final long GATHER_TIME = 180000;
	/** Рядом с кем из пати живому шепчут намёк. */
	private static final int WHISPER_RANGE = 1500;
	/** Успех без API откатов инстансов: поход шёл дольше этого. */
	private static final long MIN_SUCCESS = 8 * 60000L;
	/** Охотник не примет инвайт от того, кто выше его больше чем на столько уровней. */
	private static final int LEVEL_GAP = 5;

	/** Уровни залов Hall of the Abyss по index (High Five). */
	private static final int[] HALL_LEVELS =
	{
		23, 26, 29, 33, 36, 39, 43, 46, 49, 53, 56, 59, 63, 66, 69, 73, 78, 81, 83
	};

	/** Крик о сборе с живыми (%d — уровень зала, потом %d — сколько охотников). */
	private static final String[] GATHER_SHOUTS =
	{
		"кама %d, нас %d, кто с нами — .kama join",
		"собираем камалоку %d, хил есть"
	};

	/** Шёпот-намёк живому рядом (%d — уровень зала; строки без %d формат игнорирует). */
	private static final String[] WHISPER_LINES =
	{
		"го с нами в каму %d, одному там не вывезти",
		"у нас пати в камалоку, пиши .kama join",
		"кама с пати — быстро и без смертей, залетай"
	};

	/** Крик о походе (%d — уровень зала). */
	private static final String[] GO_SHOUTS =
	{
		"идём в камалоку (%d ур.), зал полный",
		"кама %d пати собрана, погнали",
		"кто в камалоку %d? мы уже идём"
	};

	/** Крик итога, успех (%d — уровень зала). */
	private static final String[] SUCCESS_SHOUTS =
	{
		"камалока %d закрыта, босс лёг",
		"сходили в камалоку %d, лут норм",
		"кама %d всё, завтра снова"
	};

	/** Крик итога, слились (%d — уровень зала). */
	private static final String[] FAIL_SHOUTS =
	{
		"слились в камалоке %d, хил лёг первым",
		"кама %d не зашла, завтра реванш"
	};

	/** Фраза случайного участника в пати при успехе. */
	private static final String[] PARTY_LINES =
	{
		"лёгкая была",
		"с меня дроп не упал((",
		"гг, норм пати"
	};

	/** Что делает охотник на этом тике ({@link #drive}). */
	public enum Drive
	{
		/** Не в походе — правила кача. */
		NONE,
		/** Ведёт поход — правила кача не трогать. */
		LEADS,
		/** Поход кончился — назад на кач. */
		DONE
	}

	/** Поход в Камалоку: лидер, охотники пати, зал, когда начался, вошли ли уже внутрь. */
	private static final class Run
	{
		int leaderId;
		final Set<Integer> members = ConcurrentHashMap.newKeySet();
		int hall;
		long startedAt;
		boolean entered;
		long gatherUntil;                                            // 0 — сбора нет
		final Set<Integer> players = ConcurrentHashMap.newKeySet();  // живые игроки похода
		final Set<Integer> whispered = ConcurrentHashMap.newKeySet(); // кому уже шептали
		final Map<Integer, Map<?, ?>> before = new ConcurrentHashMap<>(); // откаты инстансов вошедших охотников при входе
	}

	/** Текущий поход: один за раз. */
	private Run _run;
	/** Когда следующий поход. */
	private long _nextAt;
	/** Кто только что вышел из похода: {@link #drive} вернёт {@link Drive#DONE} и уберёт. */
	private final Set<Integer> _justLeft = ConcurrentHashMap.newKeySet();
	/** Pathfinder Worker в мире: найденный держится, пока стоит. */
	private Npc _pathfinder;

	private PhantomKamaloka()
	{
		VoicedCommandHandler.getInstance().registerHandler(new PhantomKamalokaCommand());
	}

	/**
	 * Раз в общий тик из {@code PhantomHunterManager.control}: пора — собрать поход в Камалоку из охотников на каче.
	 * @param hunting охотники, которых можно позвать (отфильтрованы менеджером)
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_KAMALOKA || (_run != null) || (now < _nextAt))
		{
			return;
		}
		// Ставится до попытки сбора: неудачный сбор не повторяется каждый тик.
		_nextAt = now + (PhantomHuntersConfig.HUNTER_KAMALOKA_INTERVAL * 60000L);
		// Камалока есть только там, где есть скрипт Kamaloka (High Five, Epilogue).
		if (ScriptManager.getInstance().getScript("Kamaloka") == null)
		{
			return;
		}

		// Свободные охотники с кача, кто сегодня ещё не был в Камалоке: из города и лавки лидер увёз бы в инстанс свою лавку и дела.
		final List<Player> free = new ArrayList<>();
		for (Player player : hunting)
		{
			if ((player != null) && !player.isInParty() && !player.isDead() && !player.isInStoreMode() && PhantomHunterManager.getInstance().isHunting(player) && (PhantomRaid.getInstance().raidOf(player) == null) && !wentToday(player))
			{
				free.add(player);
			}
		}

		// Зал: с самого высокого вниз — первый, где хотя бы трое попадают в level ± 5 и не ниже лидера на пять уровней.
		final int hall = hallFor(free);
		if (hall < 0)
		{
			return;
		}
		final List<Player> team = team(free, hall);

		// Лидер — самый высокий; к нему до HunterKamalokaSize случайных.
		final List<Player> group = new ArrayList<>();
		group.add(team.remove(0));
		while (!team.isEmpty() && (group.size() < PhantomHuntersConfig.HUNTER_KAMALOKA_SIZE))
		{
			group.add(team.remove(Rnd.get(team.size())));
		}

		final Player leader = group.get(0);
		final Run run = new Run();
		run.leaderId = leader.getObjectId();
		run.hall = hall;
		formParty(leader, group, run.members);
		if (run.members.size() < 2)
		{
			release(run);
			return;
		}

		// Скрипт пускает, только когда каждый в 1000 от лидера: все к нему сразу, вход — на следующем тике.
		for (int id : run.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				member.teleToLocation(leader.getLocation(), false);
			}
		}

		// Есть живой под этот зал — сначала сбор у лидера и зов живых.
		final List<Player> live = livePlayersFor(hall);
		if (!live.isEmpty())
		{
			run.gatherUntil = now + GATHER_TIME;
			run.entered = false;
			run.startedAt = now; // страховка по времени считает и сбор; при входе enter() начнёт отсчёт заново
			_run = run;
			PhantomChat.getInstance().hunterShout(leader, String.format(GATHER_SHOUTS[Rnd.get(GATHER_SHOUTS.length)], HALL_LEVELS[hall], run.members.size() + 1));
			callPlayers(run, live);
			LOGGER.info(PhantomKamaloka.class.getSimpleName() + ": " + leader.getName() + " gathers for a kamaloka (hall " + hall + ", " + (run.members.size() + 1) + " hunters) and calls live players.");
			return;
		}

		// Без живых — короткий сбор у лидера: войти на следующем тике, когда все рядом.
		run.gatherUntil = now + 15000;
		run.entered = false;
		run.startedAt = now;
		_run = run;
		LOGGER.info(PhantomKamaloka.class.getSimpleName() + ": " + leader.getName() + " leads a kamaloka party (hall " + hall + ", " + (run.members.size() + 1) + " hunters).");
	}

	/**
	 * Зал для группы: с самого высокого вниз — первый, где набирается команда {@link #team} хотя бы из {@value #MIN_PARTY}.
	 * @return индекс зала, −1 — не набрались
	 */
	private static int hallFor(List<Player> free)
	{
		for (int hall = HALL_LEVELS.length - 1; hall >= 0; hall--)
		{
			if (team(free, hall).size() >= MIN_PARTY)
			{
				return hall;
			}
		}
		return -1;
	}

	/**
	 * Команда зала: кто из свободных проходит по уровню ({@code level ± 5}) и не ниже самого высокого на {@value #LEVEL_GAP} уровней
	 * (дальше охотник инвайт не примет). Самый высокий — первым.
	 * @return команда зала
	 */
	private static List<Player> team(List<Player> free, int hall)
	{
		final List<Player> fit = new ArrayList<>();
		for (Player player : free)
		{
			if (Math.abs(player.getLevel() - HALL_LEVELS[hall]) <= 5)
			{
				fit.add(player);
			}
		}
		fit.sort(Comparator.comparingInt(Player::getLevel).reversed());
		if (!fit.isEmpty())
		{
			final int top = fit.get(0).getLevel();
			fit.removeIf(player -> (top - player.getLevel()) > LEVEL_GAP);
		}
		return fit;
	}

	private boolean enter(Run run, Player leader, long now)
	{
		// Кто дальше 1000 от лидера: охотника — к нему; живого — из пати, иначе скрипт не пустит никого.
		if (leader.isInParty())
		{
			for (Player member : new ArrayList<>(leader.getParty().getMembers()))
			{
				if ((member == leader) || member.isInsideRadius3D(leader, 900))
				{
					continue;
				}
				if (PhantomManager.getInstance().isPhantom(member))
				{
					member.teleToLocation(leader.getLocation(), false);
				}
				else
				{
					member.sendMessage(tr("Ты далеко от лидера — пати ушла в Камалоку без тебя."));
					member.leaveParty();
				}
			}
		}
		PhantomChat.getInstance().hunterShout(leader, String.format(GO_SHOUTS[Rnd.get(GO_SHOUTS.length)], HALL_LEVELS[run.hall]));
		if (!enterKamaloka(leader, run.hall))
		{
			// День не сгорает: кого не пустили, тот сегодня ещё пойдёт.
			PhantomChat.getInstance().hunterShout(leader, "не пустили в камалоку, отбой");
			release(run);
			return false;
		}
		// Отметка дня и снимок откатов инстансов — только у вошедших: по новому откату видно, что лёг босс.
		for (int id : hunters(run))
		{
			final Player hunter = World.getPlayer(id);
			if ((hunter != null) && inKamaloka(hunter))
			{
				markToday(hunter);
				final Map<?, ?> times = instanceTimes(id);
				if (times != null)
				{
					run.before.put(Integer.valueOf(id), times);
				}
			}
		}
		run.entered = true;
		run.startedAt = now;
		return true;
	}

	/**
	 * Раз в общий тик ({@code PhantomHunterManager.control}): лидер пропал, страховка по времени или поход кончился.
	 * @param now текущее время
	 */
	synchronized void check(long now)
	{
		final Run run = _run;
		if (run == null)
		{
			return;
		}
		final Player leader = World.getPlayer(run.leaderId);
		if (leader == null)
		{
			release(run);
			_run = null;
			return;
		}
		// Сбор с живыми: ждём у лидера, шепчем намёки, потом — вход.
		if (!run.entered && (run.gatherUntil > 0))
		{
			hint(run, leader);
			if ((now >= run.gatherUntil) || (leader.isInParty() && (leader.getParty().getMemberCount() >= FULL_PARTY)))
			{
				if (!enter(run, leader, now))
				{
					_run = null;
				}
			}
			return;
		}
		if ((now - run.startedAt) > KAMALOKA_TIME)
		{
			LOGGER.info(PhantomKamaloka.class.getSimpleName() + ": kamaloka timeout, leader " + leader.getName() + ".");
			release(run);
			_run = null;
			return;
		}
		// Поход кончился, когда внутри не осталось никого из участников (лидер мог лечь или выйти раньше пати).
		if (run.entered && !anyInside(run))
		{
			// Успех — лёг босс: скрипт Mobius ставит откат входа только на убийство босса. Нет такого API — по времени похода.
			final boolean success = run.before.isEmpty() ? (((now - run.startedAt) > MIN_SUCCESS) && !leader.isDead()) : bossDown(run, now);
			final String[] lines = success ? SUCCESS_SHOUTS : FAIL_SHOUTS;
			PhantomChat.getInstance().hunterShout(leader, String.format(lines[Rnd.get(lines.length)], HALL_LEVELS[run.hall]));
			if (success)
			{
				final List<Integer> others = new ArrayList<>(run.members);
				Collections.shuffle(others);
				for (int id : others)
				{
					final Player member = World.getPlayer(id);
					if (member != null)
					{
						PhantomCompanionManager.partySay(member, PARTY_LINES[Rnd.get(PARTY_LINES.length)]);
						break;
					}
				}
			}
			release(run);
			_run = null;
		}
	}

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}, фаза HUNT): в походе правила кача не трогают; поход кончился — {@link Drive#DONE}.
	 * @param player охотник
	 * @param now текущее время
	 * @return что с ним делать на этом тике
	 */
	Drive drive(Player player, long now)
	{
		final Run run = _run;
		if (run == null)
		{
			return _justLeft.remove(Integer.valueOf(player.getObjectId())) ? Drive.DONE : Drive.NONE;
		}
		final int id = player.getObjectId();
		if ((run.leaderId != id) && !run.members.contains(Integer.valueOf(id)))
		{
			return Drive.NONE;
		}
		// Внутри Камалоки лидер бьётся сам: включаем AutoPlay, если остановился. Во время сбора бить некого.
		if ((run.leaderId == id) && run.entered && !player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(player);
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		return Drive.LEADS;
	}

	/** Ведёт поход в Камалоку: {@code PhantomRaid.raidOf} отдаёт это всем модулям как занятость (смерть, выход из игры, ивенты, клан). */
	boolean leads(Player player)
	{
		final Run run = _run;
		return (run != null) && (run.leaderId == player.getObjectId());
	}

	/** Участник текущего похода: лидер или охотник его пати. */
	boolean inRun(Player player)
	{
		final Run run = _run;
		return (run != null) && ((run.leaderId == player.getObjectId()) || run.members.contains(Integer.valueOf(player.getObjectId())));
	}

	/** Живой — с клиентом: офлайн-торговцу звать и шептать некого. */
	private static boolean isLive(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode();
	}

	/**
	 * Живые игроки (не фантомы) под этот зал, не в пати и не на олимпиаде: их зовут в поход.
	 * @param hall index зала
	 * @return живые игроки зала
	 */
	private static List<Player> livePlayersFor(int hall)
	{
		final List<Player> live = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if ((player != null) && isLive(player) && (Math.abs(player.getLevel() - HALL_LEVELS[hall]) <= 5) && !player.isInParty() && !player.isInOlympiadMode())
			{
				live.add(player);
			}
		}
		return live;
	}

	/**
	 * Сообщение живым под этот зал: охотники зовут в Камалоку.
	 * @param run поход
	 * @param live живые игроки зала
	 */
	private static void callPlayers(Run run, List<Player> live)
	{
		for (Player player : live)
		{
			player.sendMessage(tr("Охотники идут в Камалоку (") + HALL_LEVELS[run.hall] + tr(" ур.) — .kama join: вступить, .kama go: телепорт к ним"));
		}
	}

	/**
	 * Шёпот-намёк: живой рядом с охотником похода — один шёпот за тик, одному игроку за поход.
	 * @param run поход
	 * @param leader лидер пати
	 */
	private static void hint(Run run, Player leader)
	{
		final List<Player> hunters = new ArrayList<>();
		hunters.add(leader);
		for (int id : run.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				hunters.add(member);
			}
		}
		for (Player hunter : hunters)
		{
			for (Player player : World.getVisibleObjectsInRange(hunter, Player.class, WHISPER_RANGE))
			{
				final int id = player.getObjectId();
				if (isLive(player) && (Math.abs(player.getLevel() - HALL_LEVELS[run.hall]) <= 5) && !player.isInParty() && !run.whispered.contains(Integer.valueOf(id)) && !run.players.contains(Integer.valueOf(id)))
				{
					run.whispered.add(Integer.valueOf(id));
					PhantomCompanionManager.whisper(hunter, player, String.format(WHISPER_LINES[Rnd.get(WHISPER_LINES.length)], HALL_LEVELS[run.hall]));
					return;
				}
			}
		}
	}

	/**
	 * Вступить в пати похода ({@code .kama join}), пока идёт сбор.
	 * @param player живой игрок
	 * @return ошибка или {@code null}
	 */
	synchronized String join(Player player)
	{
		final Run run = _run;
		final Player leader = run == null ? null : World.getPlayer(run.leaderId);
		if ((run == null) || run.entered || (run.gatherUntil == 0) || (leader == null))
		{
			return tr("Сейчас охотники в Камалоку не собираются.");
		}
		if (Math.abs(player.getLevel() - HALL_LEVELS[run.hall]) > 5)
		{
			return tr("Этот зал Камалоки для уровней ") + (HALL_LEVELS[run.hall] - 5) + "–" + (HALL_LEVELS[run.hall] + 5) + tr(".");
		}
		if (player.isInParty())
		{
			return player.getParty() == leader.getParty() ? tr("Ты уже с ними.") : tr("Сначала выйди из своей пати.");
		}
		if (player.isInOlympiadMode())
		{
			return tr("Не с олимпиады.");
		}
		if (leader.isInParty() && (leader.getParty().getMemberCount() >= FULL_PARTY))
		{
			return tr("Пати полная.");
		}
		if (!leader.isInParty())
		{
			return tr("Сейчас охотники в Камалоку не собираются.");
		}
		player.joinParty(leader.getParty());
		run.players.add(Integer.valueOf(player.getObjectId()));
		PhantomCompanionManager.partySay(leader, Rnd.nextBoolean() ? player.getName() + ", велкам в каму" : "о, " + player.getName() + " с нами, норм");
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") joins the kamaloka party (hall " + run.hall + ").");
		return null;
	}

	/**
	 * Телепорт живого к лидеру похода ({@code .kama go}), пока идёт сбор.
	 * @param player живой игрок
	 * @return ошибка или {@code null}
	 */
	synchronized String teleport(Player player)
	{
		final Run run = _run;
		if ((run == null) || !run.players.contains(Integer.valueOf(player.getObjectId())))
		{
			return tr("Сначала .kama join.");
		}
		if (run.entered || (run.gatherUntil == 0))
		{
			return tr("Уже ушли внутрь.");
		}
		final Player leader = World.getPlayer(run.leaderId);
		if (leader == null)
		{
			return tr("Сейчас охотники в Камалоку не собираются.");
		}
		// Вышел из пати — телепорт к лидеру больше не положен.
		if (!player.isInParty() || !leader.isInParty() || (player.getParty() != leader.getParty()))
		{
			run.players.remove(Integer.valueOf(player.getObjectId()));
			return tr("Сначала .kama join.");
		}
		player.teleToLocation(leader.getLocation(), false);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " teleports to the kamaloka party (hall " + run.hall + ").");
		return null;
	}

	/**
	 * Статус похода для живого ({@code .kama} без аргументов): идёт ли сбор, зал, лидер, до входа.
	 * @param player живой игрок
	 */
	synchronized void show(Player player)
	{
		final Run run = _run;
		final Player leader = run == null ? null : World.getPlayer(run.leaderId);
		if ((run == null) || run.entered || (run.gatherUntil == 0) || (leader == null))
		{
			player.sendMessage(tr("Сейчас охотники в Камалоку не собираются."));
			return;
		}
		player.sendMessage(tr("Охотники собираются в Камалоку (") + HALL_LEVELS[run.hall] + tr(" ур.), лидер — ") + leader.getName() + tr(", в пати ") + (leader.isInParty() ? leader.getParty().getMemberCount() : 1) + tr("."));
		player.sendMessage(tr("До входа ~") + Math.max(0, (run.gatherUntil - System.currentTimeMillis()) / 1000) + tr(" с. .kama join — вступить, .kama go — телепорт к ним."));
	}

	/**
	 * Роспуск похода: слуг и бафов участникам убрать, пати распустить; все id — в «только что вышли».
	 * @param run поход
	 */
	private void release(Run run)
	{
		for (int id : run.members)
		{
			final Player member = World.getPlayer(id);
			final PhantomTemplate template = member == null ? null : PhantomData.getInstance().getTemplate(member.getName());
			if (template != null)
			{
				PhantomCompanionManager.getInstance().release(template, true);
			}
			_justLeft.add(Integer.valueOf(id));
		}
		final Player leader = World.getPlayer(run.leaderId);
		if ((leader != null) && leader.isInParty())
		{
			leader.leaveParty();
		}
		_justLeft.add(Integer.valueOf(run.leaderId));
	}

	/**
	 * Пати без сбора в одной точке: участники к лидеру в пати, бафы, как перед рейдом.
	 * @param leader лидер
	 * @param team охотники пати (лидер первый)
	 * @param into кто реально оказался в пати лидера
	 */
	private static void formParty(Player leader, List<Player> team, Set<Integer> into)
	{
		PhantomHunterManager.getInstance().raidStarts(leader);
		for (Player member : team)
		{
			if (member == leader)
			{
				continue; // лидер не зовёт сам себя
			}
			PhantomHunterManager.getInstance().prepareForRaid(member);
			PhantomCompanionManager.getInstance().onInvited(leader, member, PartyDistributionType.RANDOM);
			if (member.isInParty() && (member.getParty() == leader.getParty()))
			{
				into.add(member.getObjectId());
			}
		}
	}

	// ---------- Обёртки над Kamaloka-API Mobius ----------

	/**
	 * @param player охотник или живой игрок
	 * @return игрок внутри инстанса Камалоки
	 */
	private static boolean inKamaloka(Player player)
	{
		return player.getInstanceId() != 0;
	}

	/**
	 * Охотники похода: лидер и его пати.
	 * @param run поход
	 * @return id охотников
	 */
	private static List<Integer> hunters(Run run)
	{
		final List<Integer> ids = new ArrayList<>(run.members);
		ids.add(0, Integer.valueOf(run.leaderId));
		return ids;
	}

	/**
	 * @param run поход
	 * @return кто-то из участников (охотники и вступившие живые) ещё внутри инстанса
	 */
	private static boolean anyInside(Run run)
	{
		final List<Integer> ids = hunters(run);
		ids.addAll(run.players);
		for (int id : ids)
		{
			final Player player = World.getPlayer(id);
			if ((player != null) && inKamaloka(player))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Босс лёг: у кого-то из вошедших охотников появился новый откат инстанса (скрипт ставит его только на убийство босса).
	 * @param run поход
	 * @param now текущее время
	 */
	private static boolean bossDown(Run run, long now)
	{
		for (Map.Entry<Integer, Map<?, ?>> entry : run.before.entrySet())
		{
			final Map<?, ?> after = instanceTimes(entry.getKey().intValue());
			if (after == null)
			{
				continue;
			}
			for (Map.Entry<?, ?> time : after.entrySet())
			{
				if ((time.getValue() instanceof Long until) && (until.longValue() > now) && !until.equals(entry.getValue().get(time.getKey())))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Откаты инстансов игрока: id шаблона → когда снова можно войти. Через отражение: у хроник без Камалоки API другое.
	 * @param objectId id игрока
	 * @return копия откатов, {@code null} — API нет
	 */
	private static Map<?, ?> instanceTimes(int objectId)
	{
		try
		{
			final Class<?> manager = Class.forName("org.l2jmobius.gameserver.managers.InstanceManager");
			final Object instance = manager.getMethod("getInstance").invoke(null);
			if (manager.getMethod("getAllInstanceTimes", int.class).invoke(instance, Integer.valueOf(objectId)) instanceof Map<?, ?> times)
			{
				return new HashMap<>(times);
			}
		}
		catch (Exception e)
		{
			// API другое: успех похода — по времени.
		}
		return null;
	}

	/**
	 * «Сегодня» для Камалоки: день сменяется в 6:30, как откат входа в скрипте Mobius ({@code RESET_HOUR}, {@code RESET_MIN}).
	 */
	private static String kamalokaDay()
	{
		return LocalDateTime.now().minusHours(6).minusMinutes(30).toLocalDate().toString();
	}

	/**
	 * @param player охотник
	 * @return охотник уже был в Камалоке сегодня
	 */
	private static boolean wentToday(Player player)
	{
		return kamalokaDay().equals(player.getVariables().getString(DAY_VARIABLE, ""));
	}

	/**
	 * Отметка «сегодня был в Камалоке»; сохраняем сразу — иначе после рестарта отметка пропадёт.
	 * @param player охотник
	 */
	private static void markToday(Player player)
	{
		player.getVariables().set(DAY_VARIABLE, kamalokaDay());
		player.getVariables().storeMe();
	}

	/**
	 * Pathfinder Worker в мире: перебираем весь мир, найденный держится, пока стоит.
	 * @return NPC у входа в Камалоку, {@code null} — не нашёлся
	 */
	private Npc pathfinder()
	{
		if ((_pathfinder != null) && _pathfinder.isSpawned() && !_pathfinder.isDecayed())
		{
			return _pathfinder;
		}
		_pathfinder = null;
		for (WorldObject object : World.getVisibleObjects())
		{
			if ((object instanceof Npc npc) && (npc.getInstanceId() == 0) && npc.getName().contains("Pathfinder"))
			{
				_pathfinder = npc;
				break;
			}
		}
		return _pathfinder;
	}

	/**
	 * Вход в Камалоку штатным скриптом Mobius: как будто лидер нажал кнопку у Pathfinder Worker.
	 * @param leader лидер пати
	 * @param hall index зала
	 * @return пати внутри Камалоки
	 */
	private static boolean enterKamaloka(Player leader, int hall)
	{
		try
		{
			final Quest script = ScriptManager.getInstance().getScript("Kamaloka");
			final Npc npc = getInstance().pathfinder();
			if ((script == null) || (npc == null))
			{
				LOGGER.info(PhantomKamaloka.class.getSimpleName() + ": no kamaloka script or Pathfinder Worker in the world.");
				return false;
			}
			script.notifyEvent(String.valueOf(hall), npc, leader);
			return inKamaloka(leader);
		}
		catch (Exception e)
		{
			LOGGER.info(PhantomKamaloka.class.getSimpleName() + ": " + leader.getName() + " cannot enter the kamaloka.");
			return false;
		}
	}

	public static PhantomKamaloka getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomKamaloka INSTANCE = new PhantomKamaloka();
	}
}