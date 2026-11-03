package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.player.TeleportWhereType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.mechanics.olympiad.Olympiad;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/**
 * L2Hub: охотники на олимпиаде (docs/PHANTOMS.md, раздел 4.39; {@code HunterOlympiad} в PhantomHunters.ini) — «стать героем» в одиночку, когда соперники — охотники.
 * <ul>
 * <li>дворянство — охотник с {@code HunterOlympiadLevel} (76: к нему у охотника уже третья профа) становится дворянином, как после квеста;</li>
 * <li>запись — в часы соревнований ({@code Olympiad.inCompPeriod}) раз в минуту дворяне с кача записываются на матч без класса, тем же путём, что игрок у менеджера
 * ({@link PhantomCompat#olympiadRegister}), шанс — {@code HunterOlympiadChance} × вспыльчивость, не больше {@code HunterOlympiadMax};</li>
 * <li>бой — на стадионе (матч ведёт Mobius: телепорт, отсчёт, итог, очки, герои периода): соперник по матчу дальше {@value #ENGAGE_RANGE} — бежать к нему,
 * ближе — бой напарника, хилер — сначала себя;</li>
 * <li>с {@code HunterCombatFixes} (раздел 4.131) — подготовка на стадионе: бафы арены, автососки, сильные скиллы первыми, без фармового AutoPlay; на дистанции держится, как на дуэли; в запись идёт только с третьей профой и оружием; пара подобрана — бросает кач и ждёт телепорта в городе; после матча — обратно на кач.</li>
 * </ul>
 * Записанный ходит в город, как обычно (матча можно ждать долго), но в рейды и PvP-ивенты его не берут. Настройки малого мира (матч от двоих, герои раз в неделю) —
 * патч {@code 0025-olympiad-small-world} c4-interlude.
 */
public final class PhantomOlympiad
{
	private static final Logger LOGGER = Logger.getLogger(PhantomOlympiad.class.getName());

	/** Запись и дворянство — не чаще раза в столько. */
	private static final long CONTROL_GAP = 60000;
	/** Соперник дальше — бежать к нему: атака AI цели вне видимости сбрасывается (как на PvP-ивентах, раздел 4.33). */
	private static final int ENGAGE_RANGE = 1200;
	/** Строка лога о бое — не чаще раза в столько на охотника. */
	private static final long NOTE_GAP = 30000;
	/** Mobius снимает эффекты и соски сразу после телепорта ({@code AbstractOlympiadGame.removals}): готовиться — чуть позже. */
	private static final long PREPARE_DELAY = 4000;
	/** Метка в {@link #_stadium}: уже подготовился. */
	private static final long PREPARED = 0;
	/** Ждущий матча проверяет, в городе ли он, не чаще раза в столько. */
	private static final long AWAIT_RETRY = 10000;
	/** Баффер арены ({@code OlympiadManagerLink.ALLOWED_BUFFS}), скилл и уровень: Acumen, Empower, Concentration, Wind Walk, Berserker Spirit. */
	private static final int[][] MAGE_BUFFS =
	{
		{
			4355,
			3
		},
		{
			4356,
			3
		},
		{
			4351,
			6
		},
		{
			4342,
			2
		},
		{
			4352,
			2
		}
	};
	/** То же для воина и лучника: Haste, Might, Focus, Death Whisper, Berserker Spirit. */
	private static final int[][] FIGHTER_BUFFS =
	{
		{
			4357,
			2
		},
		{
			4345,
			3
		},
		{
			4359,
			3
		},
		{
			4360,
			3
		},
		{
			4352,
			2
		}
	};

	private long _lastControl;
	private final Map<Integer, Long> _lastNote = new ConcurrentHashMap<>();
	/** Кто на стадионе: с какого времени, {@link #PREPARED} — подготовился. */
	private final Map<Integer, Long> _stadium = new ConcurrentHashMap<>();
	/** Пара подобрана, ждёт телепорта: когда последний раз проверял, в городе ли. */
	private final Map<Integer, Long> _awaiting = new ConcurrentHashMap<>();

	private PhantomOlympiad()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control}: дворянство и запись.
	 * @param hunting охотники на каче, не в пати, живы
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunting, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_OLYMPIAD || ((now - _lastControl) < CONTROL_GAP))
		{
			return;
		}
		_lastControl = now;

		for (Player hunter : hunting)
		{
			if (!hunter.isNoble() && (hunter.getLevel() >= PhantomHuntersConfig.HUNTER_OLYMPIAD_LEVEL))
			{
				hunter.setNoble(true);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") became a noble.");
				if (Rnd.get(100) < 50)
				{
					PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "ну всё, я теперь дворянин, на оли пойду)" : "дворянство взял! кто на олимпиаду?");
				}
			}
		}
		if (!Olympiad.getInstance().inCompPeriod())
		{
			return;
		}

		int registered = 0;
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) && (PhantomCompat.olympiadRegistered(player) || player.isInOlympiadMode()))
			{
				registered++;
			}
		}
		final List<Player> candidates = new ArrayList<>(hunting);
		Collections.shuffle(candidates);
		for (Player hunter : candidates)
		{
			if (registered >= PhantomHuntersConfig.HUNTER_OLYMPIAD_MAX)
			{
				break;
			}
			if (!hunter.isNoble() || hunter.isInOlympiadMode() || PhantomCompat.olympiadRegistered(hunter) || PhantomEvents.getInstance().isBusy(hunter) || (PhantomRaid.getInstance().raidOf(hunter) != null))
			{
				continue;
			}
			// Дворянство даётся по уровню, а профу охотник покупает сам: без неё и без оружия на стадион незачем.
			if (PhantomHuntersConfig.HUNTER_COMBAT_FIXES && ((hunter.getPlayerClass().level() < 3) || (hunter.getActiveWeaponInstance() == null)))
			{
				continue;
			}
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			if (Rnd.get(100.0) >= (PhantomHuntersConfig.HUNTER_OLYMPIAD_CHANCE * (template != null ? template.getTemper() : 1)))
			{
				continue;
			}
			if (PhantomCompat.olympiadRegister(hunter))
			{
				registered++;
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") registers for the Olympiad (" + registered + " hunters).");
			}
		}
	}

	/**
	 * @return записан на матч или на стадионе — в рейд и на PvP-ивент не брать
	 */
	public boolean isBusy(Player player)
	{
		return player.isInOlympiadMode() || PhantomCompat.olympiadRegistered(player);
	}

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}, до смерти и фаз): на стадионе — бой матча.
	 * @return {@link PhantomEvents.Drive#FIGHTS} — на стадионе, правила кача не трогать; иначе {@link PhantomEvents.Drive#NONE}
	 */
	PhantomEvents.Drive drive(Player player, PhantomTemplate template, long now)
	{
		if (!player.isInOlympiadMode())
		{
			// Пара подобрана, идёт отсчёт до телепорта (раздел 4.131): ждать в городе, а не на споте.
			if (PhantomHuntersConfig.HUNTER_COMBAT_FIXES && (player.getOlympiadGameId() >= 0) && !player.isDead() && PhantomCompat.olympiadRegistered(player))
			{
				awaitMatch(player, now);
				return PhantomEvents.Drive.FIGHTS;
			}
			_awaiting.remove(player.getObjectId());
			if (_stadium.remove(player.getObjectId()) == null)
			{
				return PhantomEvents.Drive.NONE;
			}
			PhantomHunterOutfit.afterOlympiad(player);
			return PhantomEvents.Drive.DONE;
		}
		// Отсчёт до боя, телепорт, каст, лежит — ждёт; матч и итог ведёт Mobius.
		final boolean fixes = PhantomHuntersConfig.HUNTER_COMBAT_FIXES;
		if (fixes)
		{
			prepare(player, now);
		}
		if (!player.isOlympiadStart() || player.isDead() || player.isTeleporting() || (!fixes && player.isCastingNow()))
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		if (fixes)
		{
			// Сидящему и лежащему атака не дойдёт: сперва встать (раздел 4.119). Слуга бьёт соперника, даже пока хозяин кастует.
			if (player.isFakeDeath())
			{
				player.stopFakeDeath(true);
				return PhantomEvents.Drive.FIGHTS;
			}
			if (player.isSitting())
			{
				player.standUp();
				return PhantomEvents.Drive.FIGHTS;
			}
			final Player rival = opponent(player);
			if (rival != null)
			{
				PhantomSummoner.olympiadAssist(player, rival);
			}
			if (player.isCastingNow())
			{
				return PhantomEvents.Drive.FIGHTS;
			}
		}
		if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && (fixes ? PhantomSupport.olympiadAct(player, now) : PhantomSupport.act(player, player, template.getRole(), now)))
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		final Player opponent = opponent(player);
		if (opponent == null)
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		final int distance = (int) player.calculateDistance2D(opponent);
		if (distance > ENGAGE_RANGE)
		{
			if (!player.isMoving())
			{
				player.setRunning();
				player.getAI().setIntentionMoveTo(new Location(opponent.getX(), opponent.getY(), opponent.getZ()));
			}
			note(player, "runs to " + opponent.getName() + " on the Olympiad stadium (" + distance + ")", now);
			return PhantomEvents.Drive.FIGHTS;
		}
		if (fixes && PhantomTactics.kiteFrom(player, opponent, now))
		{
			return PhantomEvents.Drive.FIGHTS;
		}
		note(player, "fights " + opponent.getName() + " on the Olympiad stadium (HP " + (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp())) + "% vs " + (int) ((opponent.getCurrentHp() * 100) / Math.max(1, opponent.getMaxHp())) + "%)", now);
		PhantomCompanionManager.fight(player, opponent);
		return PhantomEvents.Drive.FIGHTS;
	}

	/**
	 * Пара подобрана, до телепорта на стадион идёт отсчёт (раздел 4.131): охотник бросает кач, уходит в город, как свитком возврата, и ждёт там.
	 * Раньше он качался до самого телепорта, умирал на споте, и Mobius отменял матч и снимал очки.
	 */
	private void awaitMatch(Player player, long now)
	{
		final Long since = _awaiting.get(player.getObjectId());
		if (((since != null) && ((now - since) < AWAIT_RETRY)) || player.isTeleporting())
		{
			return;
		}
		_awaiting.put(player.getObjectId(), now);
		if (player.isInsideZone(ZoneId.PEACE))
		{
			return;
		}
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		}
		player.abortAttack();
		player.abortCast();
		player.setTarget(null);
		player.getAI().setIntentionActive();
		if (player.isSitting())
		{
			player.standUp();
		}
		player.teleToLocation(MapRegionData.getInstance().getTeleToLocation(player, TeleportWhereType.TOWN), true);
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " leaves the hunt for town: the Olympiad match is about to start.");
	}

	/**
	 * Подготовка на стадионе, один раз за матч (раздел 4.131): до этого охотник входил в бой без бафов и соск, а фармовый AutoPlay сбрасывал цель-игрока.
	 */
	private void prepare(Player player, long now)
	{
		final Long since = _stadium.putIfAbsent(player.getObjectId(), now);
		if ((since == null) || (since == PREPARED) || ((now - since) < PREPARE_DELAY) || player.isTeleporting())
		{
			return;
		}
		_stadium.put(player.getObjectId(), PREPARED);
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		}
		// Банки пьёт AutoUse: телепорт мог его остановить.
		AutoUseTaskManager.getInstance().startAutoUseTask(player);
		PhantomHunterOutfit.olympiadSetup(player);
		// Пять бафов, как даёт баффер арены. Счётчик живого игрока не тратим: к NPC фантом не ходит, а на Classic и Essence счётчика нет.
		int buffs = 0;
		for (int[] buff : player.isMageClass() ? MAGE_BUFFS : FIGHTER_BUFFS)
		{
			final Skill skill = SkillData.getInstance().getSkill(buff[0], buff[1]);
			if (skill == null)
			{
				continue;
			}
			skill.applyEffects(player, player);
			final Summon summon = PhantomCompat.servitor(player);
			if (summon != null)
			{
				skill.applyEffects(summon, summon);
			}
			buffs++;
		}
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " prepares on the Olympiad stadium: " + buffs + " buffs, " + player.getAutoSoulShot().size() + " auto shots.");
	}

	/**
	 * Соперник по матчу: тот же матч ({@code getOlympiadGameId}), другая сторона.
	 */
	private static Player opponent(Player player)
	{
		for (Player other : World.getPlayers())
		{
			if ((other != player) && other.isInOlympiadMode() && (other.getOlympiadGameId() == player.getOlympiadGameId()) && (other.getOlympiadSide() != player.getOlympiadSide()) && !other.isDead())
			{
				return other;
			}
		}
		return null;
	}

	private void note(Player player, String what, long now)
	{
		final Long last = _lastNote.get(player.getObjectId());
		if ((last == null) || ((now - last) >= NOTE_GAP))
		{
			_lastNote.put(player.getObjectId(), now);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}

	public static PhantomOlympiad getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomOlympiad INSTANCE = new PhantomOlympiad();
	}
}
