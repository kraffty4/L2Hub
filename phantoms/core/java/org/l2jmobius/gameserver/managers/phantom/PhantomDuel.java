package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.Duel;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.managers.DuelManager;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: дуэль с охотником (docs/PHANTOMS.md, раздел 4.40; {@code HunterDuels} в PhantomHunters.ini).
 * <ul>
 * <li>вызов — штатный /duel игрока (врезка в {@code RequestDuelStart}: окна «принять дуэль?» у фантома нет, отвечает сервер) или охотник вызывает соседа
 * по споту ({@code HunterDuelRivalChance}, враги — первыми);</li>
 * <li>ответ — по отношениям и характеру: врагу — охотно, другу — со смешком, незнакомому — шанс {@value #NEUTRAL_CHANCE} % × вспыльчивость (сдержанный
 * чаще отказывает); старше охотника больше чем на {@value #LEVEL_GAP} уровней — отказ (кроме врага), младше — отказ;</li>
 * <li>ожидание — принял, но Mobius дуэль не начнёт (охотник в бою, HP или MP меньше половины): отбиться от моба, отдохнуть, подойти; не готовы оба за
 * {@value #WAIT} мс — отбой;</li>
 * <li>бой — по правилам дуэли Mobius (отсчёт, сдача, итог, HP после боя): хилер лечит себя, лучник и маг отходят от того, кто бьёт вплотную
 * ({@link PhantomTactics#kiteFrom}), остальные бьют;</li>
 * <li>исход — в отношения ({@link PhantomRelations.Kind#DUEL_WIN}, {@link PhantomRelations.Kind#DUEL_LOSS}), реплику вслух и «Летопись мира» ({@link PhantomNews}).</li>
 * </ul>
 */
public final class PhantomDuel
{
	private static final Logger LOGGER = Logger.getLogger(PhantomDuel.class.getName());

	/** Сколько ждать готовности обоих (добить моба, отдохнуть до половины HP и MP, подойти). */
	private static final long WAIT = 120000;
	/** Отсчёт Mobius — 5 с; дуэль не началась за столько — отбой (Mobius отказал: у кого-то флаг PvP). */
	private static final long COUNTDOWN = 15000;
	/** Вызов слышен с такого расстояния (как у Mobius). */
	private static final int CHALLENGE_RANGE = 250;
	/** Подойти к сопернику на столько перед началом. */
	private static final int READY_RANGE = 200;
	/** Мобы, бьющие охотника, — в этом радиусе: сначала отбиться. */
	private static final int THREAT_RANGE = 900;
	/** Разница уровней больше — отказ. */
	private static final int LEVEL_GAP = 5;
	/** Шансы принять вызов, %: врага, друга, незнакомого (× вспыльчивость). */
	private static final int ENEMY_CHANCE = 95;
	private static final int FRIEND_CHANCE = 85;
	private static final int NEUTRAL_CHANCE = 40;
	/** Соседи для дуэли охотников — не дальше. */
	private static final int RIVAL_RANGE = 1500;
	/** Дуэль охотников затевается не чаще раза в столько (шанс — {@code HunterDuelRivalChance}). */
	private static final long CONTROL_GAP = 60000;
	/** Та же пара охотников — снова не раньше (после дуэли, отказа или отбоя): иначе враги со спота вызывали друг друга каждую минуту — прогон H5 2026-10-22. */
	private static final long PAIR_GAP = 1800000;
	/** Повтор реплики о вызове тому же игроку — не раньше. */
	private static final long SAY_GAP = 10000;
	/** Строка лога о бое — не чаще раза в столько на охотника. */
	private static final long NOTE_GAP = 30000;

	private enum State
	{
		/** Вызов принят, ждут готовности обоих. */
		WAIT,
		/** Дуэль заведена, идёт отсчёт Mobius. */
		COUNTDOWN,
		/** Бой. */
		FIGHT
	}

	/** Дуэль: кто вызвал, кто принял; последние увиденные состояния дуэли сторон — по ним исход (после конца Mobius их сбрасывает). */
	private static class Bout
	{
		final Player a;
		final Player b;
		final boolean rivals;
		final long waitUntil;
		volatile State state = State.WAIT;
		long countdownUntil;
		volatile int stateA;
		volatile int stateB;
		boolean done;

		Bout(Player a, Player b, boolean rivals, long now)
		{
			this.a = a;
			this.b = b;
			this.rivals = rivals;
			waitUntil = now + WAIT;
		}

		Player other(Player player)
		{
			return player == a ? b : a;
		}

		void observe()
		{
			final int da = a.getDuelState();
			final int db = b.getDuelState();
			if (da != Duel.DUELSTATE_NODUEL)
			{
				stateA = da;
			}
			if (db != Duel.DUELSTATE_NODUEL)
			{
				stateB = db;
			}
		}

		Player winner()
		{
			if ((stateA == Duel.DUELSTATE_WINNER) || (stateB == Duel.DUELSTATE_DEAD))
			{
				return a;
			}
			if ((stateB == Duel.DUELSTATE_WINNER) || (stateA == Duel.DUELSTATE_DEAD))
			{
				return b;
			}
			return null;
		}
	}

	/** Ответ на вызов: принял ли, почему (для лога), что сказать — для мозгов и заготовка. */
	private record Answer(boolean accept, String why, String what, String fallback)
	{
	}

	private final Map<Integer, Bout> _bouts = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lastNote = new ConcurrentHashMap<>();
	/** Пара охотников ({@link #pair}) — когда снова можно вызвать. */
	private final Map<Long, Long> _pairUntil = new ConcurrentHashMap<>();
	private long _lastControl;

	private PhantomDuel()
	{
	}

	/**
	 * Игрок вызвал фантома на дуэль 1 на 1 (врезка в {@code RequestDuelStart}; сам игрок дуэлиться может — проверил Mobius).
	 * @param challenger игрок
	 * @param phantom фантом
	 */
	public synchronized void onChallenged(Player challenger, Player phantom)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		final String ending = (template != null) && template.isFemale() ? "а" : "";
		if (!PhantomHuntersConfig.HUNTER_DUELS || (template == null) || !template.isHunter())
		{
			challenger.sendMessage(phantom.getName() + " не принимает вызовов на дуэль.");
			return;
		}
		if (challenger.calculateDistance2D(phantom) > CHALLENGE_RANGE)
		{
			challenger.sendMessage(phantom.getName() + " слишком далеко — подойди ближе, чтобы вызвать на дуэль.");
			return;
		}
		final String busy = busy(phantom, challenger);
		if (busy != null)
		{
			challenger.sendMessage(phantom.getName() + " сейчас не может: " + busy + ".");
			return;
		}

		final Answer answer = decide(phantom, template, challenger);
		LOGGER.info(getClass().getSimpleName() + ": " + challenger.getName() + " (" + challenger.getLevel() + ") challenges " + phantom.getName() + " (" + phantom.getLevel() + ") to a duel — " + (answer.accept ? "accepted" : "declined") + " (" + answer.why + ").");
		if (!answer.accept)
		{
			PhantomChat.getInstance().hunterEvent(phantom, challenger, "DUEL", answer.what, answer.fallback, SAY_GAP);
			challenger.sendMessage(phantom.getName() + " отклонил" + ending + " вызов на дуэль.");
			return;
		}
		final boolean ready = phantom.canDuel();
		PhantomChat.getInstance().hunterEvent(phantom, challenger, "DUEL", answer.what + (ready ? "" : "; но ты в бою или не отдышался — сначала добьёшь моба и отдохнёшь, пусть подождёт"), ready ? answer.fallback : answer.fallback + " только моба добью, погоди", SAY_GAP);
		challenger.sendMessage(phantom.getName() + " принял" + ending + " вызов на дуэль" + (ready ? "." : " — дуэль начнётся, когда он" + (ending.isEmpty() ? "" : "а") + " будет готов" + ending + "."));
		begin(challenger, phantom, false);
	}

	/**
	 * Из {@code PhantomHunterManager.control}: охотник вызывает соседа по споту (одна дуэль охотников за раз).
	 * @param onSpots охотники на каче, не в пати, живы, не на ивенте и олимпиаде
	 * @param now текущее время
	 */
	synchronized void control(List<Player> onSpots, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_DUELS || (PhantomHuntersConfig.HUNTER_DUEL_RIVAL_CHANCE <= 0) || ((now - _lastControl) < CONTROL_GAP))
		{
			return;
		}
		_lastControl = now;
		for (Bout bout : _bouts.values())
		{
			if (bout.rivals)
			{
				return;
			}
		}
		if (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_DUEL_RIVAL_CHANCE)
		{
			return;
		}

		final List<Player> candidates = new ArrayList<>();
		for (Player hunter : onSpots)
		{
			if (busy(hunter, null) == null)
			{
				candidates.add(hunter);
			}
		}
		Collections.shuffle(candidates);
		// Сначала враги, потом кто угодно рядом.
		for (int pass = 0; pass < 2; pass++)
		{
			for (Player a : candidates)
			{
				for (Player b : candidates)
				{
					if ((a == b) || (a.calculateDistance2D(b) > RIVAL_RANGE) || (Math.abs(a.getLevel() - b.getLevel()) > LEVEL_GAP) || (now < _pairUntil.getOrDefault(pair(a, b), 0L)))
					{
						continue;
					}
					if ((pass == 0) && !PhantomRelations.getInstance().isEnemy(a, b))
					{
						continue;
					}
					_pairUntil.put(pair(a, b), now + PAIR_GAP);
					challengeRival(a, b);
					return;
				}
			}
		}
	}

	/**
	 * Охотник {@code a} вызывает охотника {@code b}: вслух — только если рядом есть кому слышать ({@link PhantomBanter#audience}).
	 */
	private void challengeRival(Player a, Player b)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(b.getName());
		if (template == null)
		{
			return;
		}
		final Answer answer = decide(b, template, a);
		LOGGER.info(getClass().getSimpleName() + ": " + a.getName() + " (" + a.getLevel() + ") challenges " + b.getName() + " (" + b.getLevel() + ") to a duel — " + (answer.accept ? "accepted" : "declined") + " (" + answer.why + ").");
		if (PhantomBanter.audience(a) || PhantomBanter.audience(b))
		{
			PhantomChat.getInstance().hunterSay(a, b.getName() + ", на дуэль выйдешь?", "DUEL");
			PhantomChat.getInstance().hunterSay(b, answer.fallback, "DUEL");
		}
		if (answer.accept)
		{
			begin(a, b, true);
		}
	}

	private void begin(Player a, Player b, boolean rivals)
	{
		final Bout bout = new Bout(a, b, rivals, System.currentTimeMillis());
		if (PhantomManager.getInstance().isPhantom(a))
		{
			_bouts.put(a.getObjectId(), bout);
		}
		_bouts.put(b.getObjectId(), bout);
	}

	/**
	 * Ответ охотника на вызов.
	 */
	private static Answer decide(Player hunter, PhantomTemplate template, Player challenger)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		final boolean enemy = relations.isEnemy(hunter, challenger);
		final boolean friend = !enemy && relations.isFriend(hunter, challenger);
		final int gap = challenger.getLevel() - hunter.getLevel();
		final String who = PhantomHunterManager.describe(challenger) + " вызывает тебя на дуэль";
		if (gap < -LEVEL_GAP)
		{
			return new Answer(false, "younger by " + -gap, who + ", но он младше тебя на " + -gap + " ур. — ты отказываешься: с мелкими не дерёшься", "мелковат ты ещё для дуэли со мной)");
		}
		if ((gap > LEVEL_GAP) && !enemy)
		{
			return new Answer(false, "older by " + gap, who + ", но он старше тебя на " + gap + " ур. — ты отказываешься, шансов нет", "ты меня на " + gap + " ур. старше, нашёл с кем драться)");
		}

		final int chance = enemy ? ENEMY_CHANCE : friend ? FRIEND_CHANCE : (int) Math.min(90, NEUTRAL_CHANCE * template.getTemper());
		final boolean accept = Rnd.get(100) < chance;
		final String why = (enemy ? "enemy" : friend ? "friend" : "neutral") + ", chance " + chance + "%";
		if (enemy)
		{
			return accept ? new Answer(true, why, who + "; он твой враг — ты охотно принимаешь, давно хотел ему навалять", "о, давно хотел тебе вломить. давай!") : new Answer(false, why, who + "; он твой враг, но сегодня ты не в настроении — отказываешь, но обещаешь запомнить", "не сегодня. но я тебя запомнил");
		}
		if (friend)
		{
			return accept ? new Answer(true, why, who + "; он твой друг — принимаешь со смешком, по-дружески", "ахах, ну давай) только потом не плачь") : new Answer(false, why, who + "; он твой друг — отшучиваешься и отказываешь", "не, с тобой не буду, мы ж свои)");
		}
		if (accept)
		{
			return new Answer(true, why, who + "; ты его толком не знаешь — принимаешь, посмотрим кто кого", "ну давай, посмотрим кто кого");
		}
		return new Answer(false, why, who + "; ты его толком не знаешь и драться просто так не хочешь — отказываешь", template.getTemper() < 1 ? "не, я просто так не дерусь" : "не до тебя щас, качаюсь");
	}

	/**
	 * @param challenger кто вызывает или {@code null} (подбор соперников)
	 * @return чем занят (не может на дуэль) или {@code null}
	 */
	private String busy(Player hunter, Player challenger)
	{
		final Bout bout = _bouts.get(hunter.getObjectId());
		if ((bout != null) || hunter.isInDuel())
		{
			return (bout != null) && (challenger != null) && (bout.other(hunter) == challenger) ? "уже идёт к тебе на дуэль" : "уже на дуэли";
		}
		if (hunter.isDead())
		{
			return "лежит";
		}
		if (PhantomEvents.getInstance().isBusy(hunter))
		{
			return "на PvP-ивенте";
		}
		if (PhantomOlympiad.getInstance().isBusy(hunter))
		{
			return "записан на олимпиаду";
		}
		if (PhantomRaid.getInstance().raidOf(hunter) != null)
		{
			return "в рейде";
		}
		if (hunter.isInParty())
		{
			return (challenger != null) && hunter.isInParty() && challenger.isInParty() && (hunter.getParty() == challenger.getParty()) ? "вы в одной пати" : "в пати";
		}
		if (hunter.isInStoreMode())
		{
			return "торгует";
		}
		return null;
	}

	/**
	 * @return охотник на дуэли или ждёт её — в рейд, на ивент и олимпиаду не брать
	 */
	public boolean isBusy(Player player)
	{
		return _bouts.containsKey(player.getObjectId()) || player.isInDuel();
	}

	/**
	 * Тик охотника (из {@code PhantomHunterManager.think}, после ивентов и олимпиады).
	 * @return {@code true} — дуэль ведёт он: правила кача не трогать
	 */
	boolean drive(Player player, PhantomTemplate template, long now)
	{
		final Bout bout = _bouts.get(player.getObjectId());
		if (bout == null)
		{
			return false;
		}
		final Player other = bout.other(player);
		bout.observe();
		switch (bout.state)
		{
			case WAIT:
			{
				return waitFor(bout, player, other, now);
			}
			case COUNTDOWN:
			{
				if (player.isInDuel())
				{
					bout.state = State.FIGHT;
					return fight(bout, player, template, other, now);
				}
				if (now > bout.countdownUntil)
				{
					finish(bout);
					return false;
				}
				return true;
			}
			default:
			{
				return fight(bout, player, template, other, now);
			}
		}
	}

	/**
	 * Ожидание: отбиться от моба, подойти, отдохнуть; готовы оба — завести дуэль Mobius.
	 */
	private boolean waitFor(Bout bout, Player player, Player other, long now)
	{
		if ((now > bout.waitUntil) || !other.isOnline() || other.isDead() || player.isDead())
		{
			if (!player.isDead() && other.isOnline() && (!bout.rivals || PhantomBanter.audience(player)))
			{
				PhantomChat.getInstance().hunterSay(player, other.isDead() ? "ну вот, без дуэли обошлось" : "ладно, в другой раз", "DUEL");
			}
			LOGGER.info(getClass().getSimpleName() + ": the duel of " + bout.a.getName() + " and " + bout.b.getName() + " did not start (" + (now > bout.waitUntil ? "not ready in " + (WAIT / 1000) + " s" : "someone is dead or offline") + ").");
			finish(bout);
			return false;
		}
		final Monster attacker = attacker(player);
		if (attacker != null)
		{
			if (player.isSitting())
			{
				player.standUp();
				return true;
			}
			PhantomCompanionManager.fight(player, attacker);
			note(player, "finishes " + attacker.getName() + " before the duel with " + other.getName(), now);
			return true;
		}
		if (player.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		}
		final int distance = (int) player.calculateDistance2D(other);
		if (distance > READY_RANGE)
		{
			if (player.isSitting())
			{
				player.standUp();
			}
			else if (!player.isMoving())
			{
				player.setRunning();
				player.getAI().setIntentionMoveTo(new Location(other.getX(), other.getY(), other.getZ()));
			}
			return true;
		}
		if (!player.canDuel())
		{
			// Бой не остыл — ждёт; мало HP или MP — сидит отдыхает.
			if (!player.isInCombat() && !player.isSitting() && ((player.getCurrentHp() < (player.getMaxHp() / 2)) || (player.getCurrentMp() < (player.getMaxMp() / 2))))
			{
				player.sitDown();
			}
			note(player, "gets ready for the duel with " + other.getName() + " (" + (player.isInCombat() ? "in combat" : player.isSitting() ? "rests" : "HP or MP below half or a no-duel zone") + ")", now);
			return true;
		}
		if (!other.canDuel() || (PhantomManager.getInstance().isPhantom(other) && other.isSitting()))
		{
			if (player.isSitting() && (player.getCurrentHp() >= (player.getMaxHp() * 0.9)) && (player.getCurrentMp() >= (player.getMaxMp() * 0.9)))
			{
				player.standUp();
			}
			return true;
		}
		if (player.isSitting())
		{
			player.standUp();
			return true;
		}
		start(bout, now);
		return true;
	}

	private synchronized void start(Bout bout, long now)
	{
		if (bout.state != State.WAIT)
		{
			return;
		}
		bout.state = State.COUNTDOWN;
		bout.countdownUntil = now + COUNTDOWN;
		DuelManager.getInstance().addDuel(bout.a, bout.b, 0);
		LOGGER.info(getClass().getSimpleName() + ": the duel of " + bout.a.getName() + " (" + bout.a.getLevel() + ") and " + bout.b.getName() + " (" + bout.b.getLevel() + ") starts.");
	}

	/**
	 * Бой: хилер лечит себя, лучник и маг отходят от того, кто бьёт вплотную, остальные бьют. Победил или сдался — ждёт конца (Mobius сам остановит бой).
	 */
	private boolean fight(Bout bout, Player player, PhantomTemplate template, Player other, long now)
	{
		if (!player.isInDuel())
		{
			finish(bout);
			return false;
		}
		if ((player.getDuelState() != Duel.DUELSTATE_DUELLING) || player.isCastingNow())
		{
			return true;
		}
		if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && PhantomSupport.act(player, player, template.getRole(), now))
		{
			return true;
		}
		if (PhantomTactics.kiteFrom(player, other, now))
		{
			return true;
		}
		note(player, "duels " + other.getName() + " (HP " + (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp())) + "% vs " + (int) ((other.getCurrentHp() * 100) / Math.max(1, other.getMaxHp())) + "%)", now);
		PhantomCompanionManager.fight(player, other);
		return true;
	}

	/**
	 * Конец (или отбой): исход — в отношения, вслух и в летопись.
	 */
	private synchronized void finish(Bout bout)
	{
		if (bout.done)
		{
			return;
		}
		bout.done = true;
		_bouts.remove(bout.a.getObjectId(), bout);
		_bouts.remove(bout.b.getObjectId(), bout);
		if (bout.rivals)
		{
			_pairUntil.put(pair(bout.a, bout.b), System.currentTimeMillis() + PAIR_GAP);
		}
		if (bout.state == State.WAIT)
		{
			return;
		}
		bout.observe();
		final Player winner = bout.winner();
		final Player loser = winner == null ? null : bout.other(winner);
		LOGGER.info(getClass().getSimpleName() + ": the duel of " + bout.a.getName() + " and " + bout.b.getName() + " is over — " + (winner == null ? "a draw or cancelled" : winner.getName() + " wins") + ".");
		for (Player side : new Player[]
		{
			bout.a,
			bout.b
		})
		{
			if (PhantomRelations.isHunter(side))
			{
				outcome(bout, side, bout.other(side), winner);
			}
		}
		if (winner != null)
		{
			PhantomNews.post("pvp_result", "Дуэль: " + who(winner, false) + " победил" + (winner.getAppearance().isFemale() ? "а" : "") + " " + who(loser, true), winner.getName(), winner);
		}
	}

	private void outcome(Bout bout, Player hunter, Player other, Player winner)
	{
		if (winner != null)
		{
			PhantomRelations.getInstance().record(hunter, other, winner == hunter ? PhantomRelations.Kind.DUEL_LOSS : PhantomRelations.Kind.DUEL_WIN);
		}
		final String what;
		final String fallback;
		if (winner == hunter)
		{
			what = "ты победил " + other.getName() + " на дуэли — скажи ему пару слов";
			fallback = Rnd.nextBoolean() ? "гг) приходи ещё" : "гг, неплохо держался";
		}
		else if (winner == other)
		{
			what = "ты проиграл дуэль " + other.getName() + " — признай поражение по-своему";
			fallback = Rnd.nextBoolean() ? "гг, сильный... в следующий раз отыграюсь" : "ладно, твоя взяла. гг";
		}
		else
		{
			what = "дуэль с " + other.getName() + " кончилась ничьей — скажи что-нибудь";
			fallback = "ничья, ну такое. потом добьём";
		}
		if (bout.rivals)
		{
			if (PhantomBanter.audience(hunter))
			{
				PhantomChat.getInstance().hunterSay(hunter, fallback, "DUEL");
			}
			return;
		}
		PhantomChat.getInstance().hunterEvent(hunter, other, "DUEL_END", what, fallback, SAY_GAP);
	}

	/**
	 * «охотник Имя (52)» или «игрок Имя (50)»; {@code accusative} — «охотника», «игрока».
	 */
	private static String who(Player player, boolean accusative)
	{
		final boolean hunter = PhantomManager.getInstance().isPhantom(player);
		return (hunter ? (accusative ? "охотника " : "охотник ") : (accusative ? "игрока " : "игрок ")) + player.getName() + " (" + player.getLevel() + ")";
	}

	private static long pair(Player a, Player b)
	{
		final long low = Math.min(a.getObjectId(), b.getObjectId());
		final long high = Math.max(a.getObjectId(), b.getObjectId());
		return (high << 32) | low;
	}

	/**
	 * @return моб рядом бьёт охотника
	 */
	private static Monster attacker(Player player)
	{
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, THREAT_RANGE))
		{
			if (!monster.isAlikeDead() && (PhantomHunterOutfit.victimOf(monster) == player))
			{
				return monster;
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

	public static PhantomDuel getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomDuel INSTANCE = new PhantomDuel();
	}
}
