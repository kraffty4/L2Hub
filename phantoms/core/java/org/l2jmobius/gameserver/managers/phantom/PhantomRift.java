package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: охотники пати ходят в Dimensional Rift (docs/PHANTOMS.md; ключ {@code HunterRift} в PhantomHunters.ini).<br>
 * Раз в {@code HunterRiftInterval} минут менеджер ищет 3–5 свободных охотников одного яруса Rift
 * (Recruit 20+ … Hero 70+), самый высокий становится лидером: собирает пати (как рейд, но без телепорта к сбору),
 * проверяет у всех Dimensional Fragment (кого не хватает — докупает за свою адену на рынке, не хватило адены — не идёт),
 * кричит о походе в торговый чат, и пати входит в Rift через штатный менеджер Mobius.
 * Внутри охотники бьются родным AutoPlay, комнаты Rift переключает сам Mobius.
 * Когда пати оказалась вне Rift (выкинуло по таймеру, легли все или пати распалась), лидер кричит итог,
 * пати распускается, охотники возвращаются на кач.<br>
 * Охотник в походе уходит из правил кача: его ведёт {@link #drive} из {@code PhantomHunterManager.think}.
 */
public class PhantomRift
{
	private static final Logger LOGGER = Logger.getLogger(PhantomRift.class.getName());

	/** Dimensional Fragment — вход в Rift: id у хроник общий. */
	private static final int FRAGMENT_ID = 7079;
	/** Цена фрагмента на рынке, если на нём ещё не торговали. */
	private static final int DEFAULT_FRAGMENT_PRICE = 300;
	/** Минимум охотников для похода. */
	private static final int MIN_PARTY = 3;
	/** Страховка: поход дольше часа снимается (штатный Rift — до 3 × 10 минут и комната босса 15). */
	private static final long RIFT_TIME = 60 * 60000L;
	/** Охотник принимает инвайт, только если разница уровней с лидером не больше пяти ({@code LFM_LEVEL_GAP}). */
	private static final int LEVEL_GAP = 5;
	/** Сбор с живыми в зале ожидания: 3 минуты. */
	private static final long GATHER_TIME = 180000;
	/** Рядом с кем из пати живому шепчут намёк. */
	private static final int WHISPER_RANGE = 1500;
	/** Полная пати: с ней сбор сразу кончается. */
	private static final int FULL_PARTY = 9;

	/** Ярус Rift по уровню: для криков. */
	private static final String[] TYPE_NAMES = { "", "рекрут", "солдат", "офицер", "капитан", "командир", "герой" };

	/** Крик о сборе с живыми (%s — ярус, %d — сколько охотников). */
	private static final String[] GATHER_SHOUTS =
	{
		"рифт %s, нас %d, кто с нами — .rift join",
		"собираем рифт %s, хил есть, залетайте",
		"в рифт %s не хватает пары человек, го к нам"
	};

	/** Шёпот-намёк живому рядом (%s — ярус; строки без %s формат игнорирует). */
	private static final String[] WHISPER_LINES =
	{
		"го с нами в рифт, в соло там делать нечего",
		"у нас хил, в рифте с пати раза в три быстрее",
		"рифт %s собираем, пиши .rift join, вместе веселее",
		"одному в рифт — только фраги жечь. давай к нам"
	};

	/** Крик о походе (%s — ярус). */
	private static final String[] GO_SHOUTS =
	{
		"идём в рифт (%s), фрагов хватает",
		"рифт %s пати собрана, погнали",
		"кто в рифт %s? мы уже идём"
	};

	/** Крик итога. */
	private static final String[] DONE_SHOUTS =
	{
		"вышли из рифта, норм сходили",
		"рифт всё, лут поделили",
		"рифт кончился, кто-то лёг, но вынесли"
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

	/** Поход в Rift: лидер, охотники пати, ярус, когда начался, вошли ли уже внутрь. */
	private static final class Run
	{
		int leaderId;
		final Set<Integer> members = ConcurrentHashMap.newKeySet();
		int type;
		long startedAt;
		boolean entered;
		long gatherUntil;                                            // 0 — сбора нет
		final Set<Integer> players = ConcurrentHashMap.newKeySet();  // живые игроки похода
		final Set<Integer> whispered = ConcurrentHashMap.newKeySet(); // кому уже шептали
	}

	/** Текущий поход: один за раз. */
	private Run _run;
	/** Когда следующий поход. */
	private long _nextAt;
	/** Кто только что вышел из похода: {@link #drive} вернёт {@link Drive#DONE} и уберёт. */
	private final Set<Integer> _justLeft = ConcurrentHashMap.newKeySet();

	private PhantomRift()
	{
		VoicedCommandHandler.getInstance().registerHandler(new PhantomRiftCommand());
	}

	/**
	 * Раз в общий тик из {@code PhantomHunterManager.control}: пора — собрать поход в Rift из охотников на каче.
	 * @param hunting охотники, которых можно позвать (отфильтрованы менеджером)
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_RIFT || !PhantomCompat.riftExists() || (_run != null) || (now < _nextAt))
		{
			return;
		}
		// Ставится до попытки сбора: неудачный сбор не повторяется каждый тик.
		_nextAt = now + (PhantomHuntersConfig.HUNTER_RIFT_INTERVAL * 60000L);

		// Свободные охотники 20-го уровня и выше — только с кача: из города и лавки лидер увёз бы в Rift свою лавку и дела.
		final List<Player> free = new ArrayList<>();
		for (Player player : hunting)
		{
			if ((player != null) && !player.isInParty() && !player.isDead() && (player.getLevel() >= 20) && !player.isInStoreMode() && PhantomHunterManager.getInstance().isHunting(player) && (PhantomRaid.getInstance().raidOf(player) == null))
			{
				free.add(player);
			}
		}

		// Группируем по ярусу Rift; идём в случайный ярус, где хотя бы трое.
		final Map<Integer, List<Player>> tiers = new HashMap<>();
		for (Player player : free)
		{
			tiers.computeIfAbsent(Integer.valueOf(riftType(player.getLevel())), k -> new ArrayList<>()).add(player);
		}
		final List<Integer> full = new ArrayList<>();
		for (Map.Entry<Integer, List<Player>> entry : tiers.entrySet())
		{
			if (entry.getValue().size() >= MIN_PARTY)
			{
				full.add(entry.getKey());
			}
		}
		if (full.isEmpty())
		{
			return;
		}
		final int type = full.get(Rnd.get(full.size())).intValue();

		// Лидер — самый высокий яруса; к нему до HunterRiftSize случайных не ниже его на пять уровней (дальше охотник инвайт не примет).
		final List<Player> group = tiers.get(Integer.valueOf(type));
		group.sort(Comparator.comparingInt(Player::getLevel).reversed());
		final Player top = group.remove(0);
		group.removeIf(player -> (top.getLevel() - player.getLevel()) > LEVEL_GAP);
		if ((group.size() + 1) < MIN_PARTY)
		{
			return;
		}
		final List<Player> team = new ArrayList<>();
		team.add(top);
		while (!group.isEmpty() && (team.size() < PhantomHuntersConfig.HUNTER_RIFT_SIZE))
		{
			team.add(group.remove(Rnd.get(group.size())));
		}
		team.sort(Comparator.comparingInt(Player::getLevel).reversed());

		// Фрагменты у каждого; кому не хватило адены — в Rift не идёт.
		final List<Player> ready = new ArrayList<>();
		for (Player member : team)
		{
			if (payFragments(member, type))
			{
				ready.add(member);
			}
		}
		if (ready.size() < MIN_PARTY)
		{
			LOGGER.info(PhantomRift.class.getSimpleName() + ": not enough fragments for a rift party (tier " + type + ").");
			return;
		}

		final Player leader = ready.get(0);
		final Run run = new Run();
		run.leaderId = leader.getObjectId();
		run.type = type;
		formParty(leader, ready, run.members);
		if (run.members.size() < 2)
		{
			release(run);
			return;
		}

		// Mobius пускает в Rift, только когда вся пати в зале ожидания: туда — сразу, вход — на следующем тике.
		// Только тех, кто правда в пати: отказавший от инвайта остался бы в зале один.
		PhantomCompat.riftWaitingRoom(leader);
		for (int id : run.members)
		{
			final Player member = World.getPlayer(id);
			if (member != null)
			{
				PhantomCompat.riftWaitingRoom(member);
			}
		}

		// Есть живой этого яруса — сначала сбор в зале ожидания и зов живых.
		final List<Player> live = livePlayersFor(type);
		if (!live.isEmpty())
		{
			run.gatherUntil = now + GATHER_TIME;
			run.entered = false;
			run.startedAt = now; // страховка по времени считает и сбор; при входе enter() начнёт отсчёт заново
			_run = run;
			PhantomChat.getInstance().hunterShout(leader, String.format(GATHER_SHOUTS[Rnd.get(GATHER_SHOUTS.length)], TYPE_NAMES[type], run.members.size() + 1));
			callPlayers(run, live);
			LOGGER.info(PhantomRift.class.getSimpleName() + ": " + leader.getName() + " gathers for a rift (tier " + type + ", " + (run.members.size() + 1) + " hunters) and calls live players.");
			return;
		}

		// Без живых — короткий сбор в зале ожидания: войти на следующем тике, когда все уже там.
		run.gatherUntil = now + 15000;
		run.entered = false;
		run.startedAt = now;
		_run = run;
		LOGGER.info(PhantomRift.class.getSimpleName() + ": " + leader.getName() + " leads a rift party (tier " + type + ", " + (run.members.size() + 1) + " hunters).");
	}

	/**
	 * Вход пати в Rift: крик о походе, штатный менеджер; не пустили — крик «отбой» и роспуск.
	 * @param run поход
	 * @param leader лидер пати
	 * @param now текущее время
	 * @return пати внутри Rift
	 */
	private boolean enter(Run run, Player leader, long now)
	{
		PhantomChat.getInstance().hunterShout(leader, String.format(GO_SHOUTS[Rnd.get(GO_SHOUTS.length)], TYPE_NAMES[run.type]));
		// Кто не в зале ожидания: охотника — туда; живого (не дошёл) — из пати, иначе Mobius не пустит никого.
		if (leader.isInParty())
		{
			for (Player member : new ArrayList<>(leader.getParty().getMembers()))
			{
				if (PhantomCompat.inRiftWaitingRoom(member))
				{
					continue;
				}
				if (PhantomManager.getInstance().isPhantom(member))
				{
					PhantomCompat.riftWaitingRoom(member);
				}
				else
				{
					member.sendMessage(tr("Ты не в зале ожидания Rift — пати ушла без тебя."));
					member.leaveParty();
				}
			}
		}
		if (!PhantomCompat.riftEnter(leader, run.type))
		{
			PhantomChat.getInstance().hunterShout(leader, "не пустили в рифт, отбой");
			release(run);
			return false;
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
		// Сбор с живыми: ждём в зале ожидания, шепчем намёки, потом — вход.
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
		// Лидер лёг внутри: после реса он в городе, а пати осталась бы в Rift, и напарники тянулись бы к нему. Поход кончен.
		if (run.entered && leader.isDead())
		{
			LOGGER.info(PhantomRift.class.getSimpleName() + ": rift leader " + leader.getName() + " died, the run is over.");
			PhantomChat.getInstance().hunterShout(leader, "слились в рифте, лидер лёг");
			release(run);
			_run = null;
			return;
		}
		if ((now - run.startedAt) > RIFT_TIME)
		{
			LOGGER.info(PhantomRift.class.getSimpleName() + ": rift timeout, leader " + leader.getName() + ".");
			release(run);
			_run = null;
			return;
		}
		if (run.entered && !PhantomCompat.inRift(leader))
		{
			PhantomChat.getInstance().hunterShout(leader, DONE_SHOUTS[Rnd.get(DONE_SHOUTS.length)]);
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
		// Внутри Rift лидер бьётся сам: включаем AutoPlay, если остановился. Во время сбора в зале ожидания бить некого.
		if ((run.leaderId == id) && run.entered && !player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().startAutoPlay(player);
			AutoUseTaskManager.getInstance().startAutoUseTask(player);
		}
		return Drive.LEADS;
	}

	/** Ведёт поход в Rift: {@code PhantomRaid.raidOf} отдаёт это всем модулям как занятость (смерть, выход из игры, ивенты, клан). */
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

	/**
	 * Живые игроки (не фантомы) этого яруса, не в пати и не на олимпиаде: их зовут в поход.
	 * @param type ярус Rift
	 * @return живые игроки яруса
	 */
	private static List<Player> livePlayersFor(int type)
	{
		final List<Player> live = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			// Живой — с клиентом: офлайн-торговцу звать некого.
			if ((player != null) && !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode() && (riftType(player.getLevel()) == type) && !player.isInParty() && !player.isInOlympiadMode())
			{
				live.add(player);
			}
		}
		return live;
	}

	/**
	 * Сообщение живым этого яруса: охотники зовут в Rift.
	 * @param run поход
	 * @param live живые игроки яруса
	 */
	private static void callPlayers(Run run, List<Player> live)
	{
		for (Player player : live)
		{
			player.sendMessage(tr("Пешки идут в Dimensional Rift (") + TYPE_NAMES[run.type] + tr(") — .rift join: вступить, .rift go: телепорт к ним"));
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
				if (!PhantomManager.getInstance().isPhantom(player) && (riftType(player.getLevel()) == run.type) && !player.isInParty() && !run.whispered.contains(Integer.valueOf(id)) && !run.players.contains(Integer.valueOf(id)))
				{
					run.whispered.add(Integer.valueOf(id));
					PhantomCompanionManager.whisper(hunter, player, String.format(WHISPER_LINES[Rnd.get(WHISPER_LINES.length)], TYPE_NAMES[run.type]));
					return;
				}
			}
		}
	}

	/**
	 * Диапазон уровней яруса Rift: для сообщения живому.
	 * @param type ярус 1…6
	 * @return текст диапазона
	 */
	private static String tierLevels(int type)
	{
		return type >= 6 ? "70+" : (((type + 1) * 10) + "–" + (((type + 1) * 10) + 9));
	}

	/**
	 * Вступить в пати похода ({@code .rift join}), пока идёт сбор.
	 * @param player живой игрок
	 * @return ошибка или {@code null}
	 */
	synchronized String join(Player player)
	{
		final Run run = _run;
		final Player leader = run == null ? null : World.getPlayer(run.leaderId);
		if ((run == null) || run.entered || (run.gatherUntil == 0) || (leader == null))
		{
			return tr("Сейчас пешки в Rift не собираются.");
		}
		if (riftType(player.getLevel()) != run.type)
		{
			return tr("Этот Rift для уровней ") + tierLevels(run.type) + tr(".");
		}
		if (player.isInParty())
		{
			return player.getParty() == leader.getParty() ? tr("Ты уже с ними.") : tr("Сначала выйди из своей пати.");
		}
		if (player.isInOlympiadMode())
		{
			return tr("Не с олимпиады.");
		}
		final Item fragments = player.getInventory().getItemByItemId(FRAGMENT_ID);
		final long have = fragments == null ? 0 : fragments.getCount();
		final int need = PhantomCompat.riftFragments(player, run.type);
		if (have < need)
		{
			return tr("Нужно ") + need + tr(" Dimensional Fragment, у тебя ") + have + tr(".");
		}
		if (leader.isInParty() && (leader.getParty().getMemberCount() >= FULL_PARTY))
		{
			return tr("Пати полная.");
		}
		if (!leader.isInParty())
		{
			return tr("Сейчас пешки в Rift не собираются.");
		}
		player.joinParty(leader.getParty());
		run.players.add(Integer.valueOf(player.getObjectId()));
		if (player.isInParty())
		{
			PhantomCompanionManager.partySay(leader, Rnd.nextBoolean() ? player.getName() + ", велкам в рифт" : "о, " + player.getName() + " с нами, норм");
		}
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") joins the rift party (tier " + run.type + ").");
		return null;
	}

	/**
	 * Телепорт живого в зал ожидания Rift ({@code .rift go}), пока идёт сбор.
	 * @param player живой игрок
	 * @return ошибка или {@code null}
	 */
	synchronized String teleport(Player player)
	{
		final Run run = _run;
		if ((run == null) || !run.players.contains(Integer.valueOf(player.getObjectId())))
		{
			return tr("Сначала .rift join.");
		}
		if (run.entered || (run.gatherUntil == 0))
		{
			return tr("Уже ушли внутрь.");
		}
		PhantomCompat.riftWaitingRoom(player);
		return null;
	}

	/**
	 * Статус похода для живого ({@code .rift} без аргументов): идёт ли сбор, ярус, лидер, до входа.
	 * @param player живой игрок
	 */
	synchronized void show(Player player)
	{
		final Run run = _run;
		final Player leader = run == null ? null : World.getPlayer(run.leaderId);
		if ((run == null) || run.entered || (run.gatherUntil == 0) || (leader == null))
		{
			player.sendMessage(tr("Сейчас пешки в Rift не собираются."));
			return;
		}
		player.sendMessage(tr("Пешки собираются в Dimensional Rift (") + TYPE_NAMES[run.type] + tr("), лидер — ") + leader.getName() + tr(", в пати ") + (leader.isInParty() ? leader.getParty().getMemberCount() : 1) + tr("."));
		player.sendMessage(tr("До входа ~") + Math.max(0, (run.gatherUntil - System.currentTimeMillis()) / 1000) + tr(" с. .rift join — вступить, .rift go — телепорт к ним."));
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

	/**
	 * Dimensional Fragment у охотника: хватает — мимо; не хватает — докупить за свою адену по рыночной цене.
	 * Списывать фрагменты при входе будет сам Mobius.
	 * @param player охотник
	 * @param type ярус Rift
	 * @return фрагменты в нужном количестве
	 */
	private static boolean payFragments(Player player, int type)
	{
		final int need = PhantomCompat.riftFragments(player, type);
		if (need == 0)
		{
			return true;
		}
		final Item fragments = player.getInventory().getItemByItemId(FRAGMENT_ID);
		final long have = fragments == null ? 0 : fragments.getCount();
		if (have >= need)
		{
			return true;
		}
		final long missing = need - have;
		long price = marketPrice(FRAGMENT_ID);
		if (price <= 0)
		{
			price = DEFAULT_FRAGMENT_PRICE;
		}
		final long total = price * missing;
		if (PhantomHunterOutfit.freeAdena(player) < total)
		{
			return false;
		}
		if (!PhantomCompat.reduceAdena(player, ItemProcessType.BUY, total, player, false))
		{
			return false;
		}
		PhantomMarketMemory.deal(FRAGMENT_ID, missing, total, true);
		PhantomCompat.addItem(player, ItemProcessType.BUY, FRAGMENT_ID, missing, player);
		return true;
	}

	/**
	 * Ярус Rift по уровню: меньше 20 — не ходит, дальше Recruit…Hero (High Five).
	 * @param level уровень охотника
	 * @return ярус, 0 — не ходит
	 */
	static int riftType(int level)
	{
		if (level < 20)
		{
			return 0;
		}
		if (level >= 70)
		{
			return 6;
		}
		return ((level - 20) / 10) + 1;
	}

	/**
	 * Рыночная цена вещи: цена магазина × текущий фактор рынка (как свитки в {@code PhantomEnchant}).
	 * @param itemId вещь
	 * @return средняя цена за штуку, 0 — нет данных
	 */
	private static long marketPrice(int itemId)
	{
		final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
		return item == null ? 0 : Math.round(item.getReferencePrice() * PhantomMarketMemory.factor(itemId));
	}

	public static PhantomRift getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomRift INSTANCE = new PhantomRift();
	}
}
