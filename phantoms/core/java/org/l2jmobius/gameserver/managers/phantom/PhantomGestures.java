package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.serverpackets.SocialAction;
import org.l2jmobius.gameserver.network.serverpackets.StopMove;
import org.l2jmobius.gameserver.taskmanagers.AttackStanceTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: жесты охотников (docs/PHANTOMS.md, раздел 4.80; {@code HunterGestures} в PhantomHunters.ini).
 * <ul>
 * <li>поздоровался вслух рядом с живым или ответил на его приветствие — машет;</li>
 * <li>убил РБ в рейде, выиграл дуэль или PvP — победный жест;</li>
 * <li>стоит в городе без дела — изредка свой жест;</li>
 * <li>живой сделал жест, держа охотника в цели, — тот отвечает тем же или близким; враг отмахивается или молчит, в бою не отвечает.</li>
 * </ul>
 * Пакет и номера 2–13 одинаковы во всех хрониках. О жесте живого узнаём по-разному: на Classic и Essence — событие Mobius ({@link PhantomCompat#addSocialActionListener}), на остальных — врезка в {@code RequestActionUse}.
 * Смотреть некому — жеста нет: все случаи, кроме ответа, сначала ищут живого рядом.
 */
public final class PhantomGestures
{
	private static final Logger LOGGER = Logger.getLogger(PhantomGestures.class.getName());

	// Номера {@code SocialAction}. 14 и выше охотники не играют: 15 на Interlude — повышение уровня.
	private static final int GREETING = 2;
	private static final int VICTORY = 3;
	private static final int ADVANCE = 4;
	private static final int NO = 5;
	private static final int YES = 6;
	private static final int BOW = 7;
	private static final int UNAWARE = 8;
	private static final int WAITING = 9;
	private static final int LAUGH = 10;
	private static final int APPLAUD = 11;
	private static final int DANCE = 12;
	private static final int SORROW = 13;
	/** Жесты живого: дальше на Classic и Essence — салон красоты, на него не отвечают. */
	private static final int LAST_PLAYER_GESTURE = 29;

	/** Из них каждый охотник берёт два своих: один и тот же человек стоит и делает одно и то же. */
	private static final int[] IDLE =
	{
		VICTORY,
		ADVANCE,
		UNAWARE,
		WAITING,
		LAUGH,
		APPLAUD,
		DANCE,
		SORROW
	};
	private static final int[] REFUSAL =
	{
		NO
	};

	/** Приветствие: слово в начале реплики. Дальше — уже не приветствие, а «передавай привет». */
	private static final Pattern GREETING_WORDS = Pattern.compile("\\b(?:привет\\w*|прив|здаров\\w*|здорова|здравствуй\\w*|здрасьте|здрасте|дарова|даров|хай|хелло|ку|куку|салют|добрый (?:день|вечер)|доброе утро|доброй ночи|hi|hello|hey|yo|greetings)\\b", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
	private static final int GREETING_HEAD = 24;

	/** Живой дальше этого — жест не ему, отвечать незачем. */
	private static final int REPLY_RANGE = 600;
	/** Живой дальше этого жеста не увидит. */
	private static final int SEEN_RANGE = 1250;
	/** Одна пара отвечает не чаще: иначе зажатая кнопка превращает охотника в зеркало. */
	private static final long REPLY_GAP = 8000;
	private static final int REPLY_DELAY_MIN = 700;
	private static final int REPLY_DELAY_MAX = 2500;
	/** Машет не чаще. */
	private static final long WAVE_GAP = 30000;
	private static final int WAVE_DELAY_MIN = 200;
	private static final int WAVE_DELAY_MAX = 1200;
	/** Живой поздоровался: ответ охотника в этот срок считается ответом на приветствие. */
	private static final long HEARD_WINDOW = 60000;
	/** Победный жест не чаще. */
	private static final long VICTORY_GAP = 15000;
	/** У каждого своя задержка: рейд не вскидывает руки по команде. */
	private static final int VICTORY_DELAY_MIN = 500;
	private static final int VICTORY_DELAY_MAX = 3500;
	/** Столько стоит, пока отвечает. */
	private static final long HOLD = 3000;

	/** Пара охотник и игрок → когда снова можно ответить. */
	private final Map<Long, Long> _replyNext = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _waveNext = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _victoryNext = new ConcurrentHashMap<>();
	/** Охотник → когда в следующий раз бросить на жест в городе. Первый срок случайный, иначе толпа дёрнется разом. */
	private final Map<Integer, Long> _idleNext = new ConcurrentHashMap<>();
	/** Охотник → когда живой с ним поздоровался. */
	private final Map<Integer, Long> _heard = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _holdUntil = new ConcurrentHashMap<>();

	// Самопроверка: что и кому разослано.
	private volatile boolean _test;
	private final Map<String, Integer> _counts = new ConcurrentHashMap<>();
	private final Map<Integer, Integer> _last = new ConcurrentHashMap<>();
	private final List<long[]> _idleLog = Collections.synchronizedList(new ArrayList<>());
	private final AtomicInteger _recipients = new AtomicInteger();
	private final AtomicInteger _seenByLive = new AtomicInteger();
	private final AtomicInteger _ok = new AtomicInteger();
	private final AtomicInteger _wrong = new AtomicInteger();

	private PhantomGestures()
	{
	}

	/** Подписка на жесты живых там, где у Mobius есть такое событие. */
	void start()
	{
		PhantomCompat.addSocialActionListener(this, this::onPlayerSocial);
	}

	// ---------- Ответ на жест живого ----------

	/**
	 * Игрок сделал жест (врезка в {@code RequestActionUse} или событие Mobius): держит в цели охотника рядом — тот отвечает.
	 * @param player игрок
	 * @param id номер жеста
	 */
	public void onPlayerSocial(Player player, int id)
	{
		if (!PhantomHuntersConfig.HUNTER_GESTURES || (id < GREETING) || (id > LAST_PLAYER_GESTURE) || !PhantomNear.getInstance().isLive(player) || !(player.getTarget() instanceof Player))
		{
			return;
		}
		final Player hunter = (Player) player.getTarget();
		if ((hunter == player) || !counts(hunter) || (player.calculateDistance2D(hunter) > REPLY_RANGE) || busy(hunter))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		final long pair = (((long) hunter.getObjectId()) << 32) | player.getObjectId();
		if (now < _replyNext.getOrDefault(pair, 0L))
		{
			return;
		}
		_replyNext.put(pair, now + REPLY_GAP);

		// Враг: то отмахнётся, то сделает вид, что не заметил.
		final boolean enemy = PhantomRelations.getInstance().isEnemy(hunter, player);
		if (enemy && Rnd.nextBoolean())
		{
			count("silent");
			return;
		}
		final int[] answers = enemy ? REFUSAL : answers(id);
		final int answer = answers[Rnd.get(answers.length)];
		ThreadPool.schedule(() ->
		{
			if (!busy(hunter) && (player.calculateDistance2D(hunter) <= REPLY_RANGE))
			{
				face(hunter, player);
				_holdUntil.put(hunter.getObjectId(), System.currentTimeMillis() + HOLD);
				send(hunter, answer, enemy ? "refuse" : "reply", player);
			}
		}, Rnd.get(REPLY_DELAY_MIN, REPLY_DELAY_MAX));
	}

	/** Чем отвечают: тем же или близким. Редких жестов Classic и Essence у охотников нет. */
	private static int[] answers(int id)
	{
		return switch (id)
		{
			case GREETING -> new int[]
			{
				GREETING,
				GREETING,
				BOW
			};
			case VICTORY -> new int[]
			{
				VICTORY,
				APPLAUD
			};
			case ADVANCE -> new int[]
			{
				ADVANCE,
				VICTORY
			};
			case NO -> new int[]
			{
				NO,
				UNAWARE
			};
			case YES -> new int[]
			{
				YES,
				GREETING
			};
			case BOW -> new int[]
			{
				BOW,
				BOW,
				GREETING
			};
			case UNAWARE -> new int[]
			{
				UNAWARE,
				LAUGH
			};
			case WAITING -> new int[]
			{
				WAITING,
				UNAWARE
			};
			case LAUGH -> new int[]
			{
				LAUGH,
				APPLAUD
			};
			case APPLAUD -> new int[]
			{
				APPLAUD,
				BOW
			};
			case DANCE -> new int[]
			{
				DANCE,
				APPLAUD
			};
			case SORROW -> new int[]
			{
				SORROW,
				UNAWARE
			};
			default -> new int[]
			{
				LAUGH,
				UNAWARE
			};
		};
	}

	// ---------- Поздоровался ----------

	/**
	 * Охотнику что-то сказали ({@link PhantomChat}): поздоровались — его ответ будет ответом на приветствие, какими бы словами он ни ответил.
	 * @param hunter охотник
	 * @param text что сказали
	 */
	void heard(Player hunter, String text)
	{
		if (PhantomHuntersConfig.HUNTER_GESTURES && isGreeting(text))
		{
			_heard.put(hunter.getObjectId(), System.currentTimeMillis());
		}
	}

	/**
	 * Фантом сказал реплику вслух рядом ({@link PhantomChat}): охотник поздоровался или ответил на приветствие, живой рядом — машет.
	 * @param speaker кто сказал
	 * @param text что сказал
	 */
	void onSaid(Player speaker, String text)
	{
		if (!PhantomHuntersConfig.HUNTER_GESTURES || !counts(speaker))
		{
			return;
		}
		final int id = speaker.getObjectId();
		final long now = System.currentTimeMillis();
		final Long heard = _heard.remove(id);
		if (((heard == null) || ((now - heard) > HEARD_WINDOW)) && !isGreeting(text))
		{
			return;
		}
		if (now < _waveNext.getOrDefault(id, 0L))
		{
			return;
		}
		final Player live = nearestLive(speaker);
		if (live == null)
		{
			return;
		}
		_waveNext.put(id, now + WAVE_GAP);
		ThreadPool.schedule(() ->
		{
			if (!busy(speaker))
			{
				face(speaker, live);
				_holdUntil.put(id, System.currentTimeMillis() + HOLD);
				send(speaker, GREETING, "wave", live);
			}
		}, Rnd.get(WAVE_DELAY_MIN, WAVE_DELAY_MAX));
	}

	private static boolean isGreeting(String text)
	{
		return GREETING_WORDS.matcher(text.length() > GREETING_HEAD ? text.substring(0, GREETING_HEAD) : text).find();
	}

	// ---------- Победа ----------

	/**
	 * Охотник победил: убил в PvP или выиграл дуэль. Не охотник — ничего.
	 * @param hunter победитель
	 */
	void onVictory(Player hunter)
	{
		if (!PhantomHuntersConfig.HUNTER_GESTURES || (hunter == null) || !counts(hunter))
		{
			return;
		}
		final long now = System.currentTimeMillis();
		if (now < _victoryNext.getOrDefault(hunter.getObjectId(), 0L))
		{
			return;
		}
		final Player live = nearestLive(hunter);
		if (live == null)
		{
			return;
		}
		_victoryNext.put(hunter.getObjectId(), now + VICTORY_GAP);
		ThreadPool.schedule(() ->
		{
			// Сразу после боя он ещё «в бою»: это не помеха.
			if (hunter.isOnline() && !hunter.isDead() && !hunter.isCastingNow() && !hunter.isAllSkillsDisabled() && !hunter.isSitting() && !hunter.isInStoreMode())
			{
				send(hunter, VICTORY, "victory", live);
			}
		}, Rnd.get(VICTORY_DELAY_MIN, VICTORY_DELAY_MAX));
	}

	/**
	 * Рейд убил босса: у каждого охотника свой момент.
	 * @param hunters охотники рейда
	 */
	void onRaidKilled(List<Integer> hunters)
	{
		if (!PhantomHuntersConfig.HUNTER_GESTURES)
		{
			return;
		}
		for (int id : hunters)
		{
			onVictory(World.getPlayer(id));
		}
	}

	// ---------- Стоит в городе ----------

	/**
	 * Проход охотника ({@link PhantomHunterManager}): стоит в городе без дела, срок подошёл, живой рядом — бросок на свой жест.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	void tick(Player hunter, PhantomTemplate template, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_GESTURES)
		{
			return;
		}
		final int id = hunter.getObjectId();
		final long cooldown = PhantomHuntersConfig.HUNTER_GESTURE_IDLE_COOLDOWN * 1000L;
		final Long next = _idleNext.get(id);
		if (next == null)
		{
			_idleNext.put(id, now + Rnd.get((int) cooldown));
			return;
		}
		if ((now < next) || hunter.isMoving() || (hunter.getAI().getIntention() != Intention.IDLE) || !PhantomCompat.isInTown(hunter) || !counts(hunter) || busy(hunter))
		{
			return;
		}
		final Player live = nearestLive(hunter);
		if (live == null)
		{
			return;
		}
		_idleNext.put(id, now + (long) (cooldown * (0.5 + Rnd.nextDouble())));
		if (Rnd.get(100.0) >= (PhantomHuntersConfig.HUNTER_GESTURE_IDLE_CHANCE * template.getSocial()))
		{
			return;
		}
		final int[] own = own(hunter);
		send(hunter, own[Rnd.get(own.length)], "idle", live);
	}

	/** Два своих жеста охотника: по имени, поэтому те же и после рестарта. */
	private static int[] own(Player hunter)
	{
		final int hash = hunter.getName().hashCode();
		final int first = Math.floorMod(hash, IDLE.length);
		final int second = Math.floorMod(hash >>> 8, IDLE.length - 1);
		return new int[]
		{
			IDLE[first],
			IDLE[second >= first ? second + 1 : second]
		};
	}

	// ---------- Общее ----------

	/**
	 * @param hunter охотник
	 * @param now текущее время
	 * @return отвечает жестом: постоять на месте
	 */
	boolean holds(Player hunter, long now)
	{
		final Long until = _holdUntil.get(hunter.getObjectId());
		if (until == null)
		{
			return false;
		}
		if (now >= until)
		{
			_holdUntil.remove(hunter.getObjectId());
			return false;
		}
		return true;
	}

	/** Жесты — только у охотников, и не у секретных. */
	private static boolean counts(Player player)
	{
		return PhantomRelations.isHunter(player) && !PhantomManager.getInstance().isSecret(player);
	}

	private static boolean busy(Player hunter)
	{
		return !hunter.isOnline() || hunter.isDead() || hunter.isInCombat() || hunter.isCastingNow() || hunter.isAllSkillsDisabled() || hunter.isSitting() || hunter.isInStoreMode() || hunter.isTeleporting() || hunter.isFishing() || hunter.isOnEvent() || hunter.isInOlympiadMode() || hunter.isInDuel();
	}

	private static Player nearestLive(Player hunter)
	{
		Player nearest = null;
		double best = Double.MAX_VALUE;
		for (Player player : World.getVisibleObjectsInRange(hunter, Player.class, SEEN_RANGE))
		{
			if (PhantomNear.getInstance().isLive(player))
			{
				final double distance = hunter.calculateDistance2D(player);
				if (distance < best)
				{
					best = distance;
					nearest = player;
				}
			}
		}
		return nearest;
	}

	/** Останавливается, если шёл, и поворачивается к тому, кому жест: клиент берёт направление из {@link StopMove}. */
	private static void face(Player hunter, Player other)
	{
		if (hunter.isMoving())
		{
			hunter.stopMove(null);
			hunter.getAI().setIntentionIdle();
		}
		final double degrees = Math.toDegrees(Math.atan2(other.getY() - hunter.getY(), other.getX() - hunter.getX()));
		hunter.setHeading((int) ((degrees < 0 ? degrees + 360 : degrees) * (65536.0 / 360)));
		hunter.broadcastPacket(new StopMove(hunter));
	}

	private void send(Player hunter, int id, String reason, Player live)
	{
		hunter.broadcastPacket(new SocialAction(hunter.getObjectId(), id));
		if (!_test)
		{
			return;
		}

		count(reason);
		_last.put(hunter.getObjectId(), id);
		final List<Player> around = World.getVisibleObjectsInRange(hunter, Player.class, SEEN_RANGE);
		_recipients.addAndGet(around.size());
		if (around.contains(live))
		{
			_seenByLive.incrementAndGet();
		}
		if ("idle".equals(reason))
		{
			_idleLog.add(new long[]
			{
				hunter.getObjectId(),
				id,
				System.currentTimeMillis()
			});
		}
		line(hunter.getName() + " gesture " + id + " (" + reason + "), around " + around.size() + ", live sees " + around.contains(live) + ".");
	}

	private void count(String reason)
	{
		if (_test)
		{
			_counts.merge(reason, 1, Integer::sum);
		}
	}

	// ---------- Самопроверка ----------

	private void line(String text)
	{
		LOGGER.info(getClass().getSimpleName() + ": SelfTest GESTURES: " + text);
	}

	private void check(String what, boolean ok)
	{
		(ok ? _ok : _wrong).incrementAndGet();
		line((ok ? "ok: " : "WRONG: ") + what + ".");
	}

	private int sent(String reason)
	{
		return _counts.getOrDefault(reason, 0);
	}

	private int last(Player hunter)
	{
		return _last.getOrDefault(hunter.getObjectId(), 0);
	}

	private static void calm(Player player)
	{
		AutoPlayTaskManager.getInstance().stopAutoPlay(player);
		player.setTarget(null);
		if (player.isMoving())
		{
			player.stopMove(null);
		}
		player.getAI().setIntentionIdle();
		if (player.isSitting())
		{
			player.standUp();
		}
	}

	/**
	 * Самопроверка {@code HuntersSelfTest = GESTURES} на копии базы: один охотник играет живого ({@link PhantomNear#testPlayer}), считаем рассылки и тех, кто их видит.
	 * Клиента нет: жест живого здесь вызывается напрямую, мимо врезки; что пакет показывает в игре — только глазами. Ключи включает сама.
	 * @param world охотники в мире
	 */
	void selfTest(List<Player> world)
	{
		selfTest(world, 0);
	}

	private void selfTest(List<Player> world, int tries)
	{
		final List<Player> hunters = new ArrayList<>();
		for (Player player : world)
		{
			if (!PhantomManager.getInstance().isSecret(player) && !player.isDead() && !player.isInParty() && (player.getClan() == null))
			{
				hunters.add(player);
			}
		}
		Player found = null;
		for (Player player : hunters)
		{
			if ((PhantomHunterManager.getInstance().huntSpot(player) != null) && !PhantomCompat.isInTown(player))
			{
				found = player;
				break;
			}
		}
		if ((hunters.size() < 16) || (found == null))
		{
			// Вход в самопроверку ждёт только 12 охотников без клана, остальные ещё входят по одному.
			if (tries < 30)
			{
				ThreadPool.schedule(() -> selfTest(inWorld(), tries + 1), 10000);
				return;
			}
			line("failed: free hunters " + hunters.size() + ", on a spot " + (found != null) + ".");
			return;
		}

		_test = true;
		PhantomHuntersConfig.HUNTER_GESTURES = true;
		final Player live = found;
		hunters.remove(live);
		// Стоят все: иначе пати и дорога уводят их от «живого».
		for (Player hunter : world)
		{
			PhantomFavors.getInstance().freeze(hunter);
		}
		PhantomNear.getInstance().testPlayer(live);
		calm(live);
		PhantomFavors.clear(live);
		final List<Player> near = new ArrayList<>(hunters.subList(0, 5));
		final List<Player> duel = new ArrayList<>(hunters.subList(5, 7));
		final List<Player> crowd = new ArrayList<>(hunters.subList(7, 13));
		final Player far = hunters.get(13);
		for (Player hunter : near)
		{
			calm(hunter);
			PhantomFavors.bring(hunter, live);
		}
		calm(far);
		PhantomFavors.toTown(far);
		line("live " + live.getName() + " (" + live.getLevel() + ") at " + PhantomHunterManager.getInstance().huntSpot(live).name + ", waiting for the combat stance to end.");
		ThreadPool.schedule(() -> reply(live, near, duel, crowd, far), 20000);
	}

	private static List<Player> inWorld()
	{
		final List<Player> result = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) && PhantomRelations.isHunter(player))
			{
				result.add(player);
			}
		}
		return result;
	}

	private void reply(Player live, List<Player> near, List<Player> duel, List<Player> crowd, Player far)
	{
		PhantomFavors.clear(live);
		final Player hunter = near.get(0);
		line(hunter.getName() + " busy " + busy(hunter) + ", in combat " + hunter.isInCombat() + ", at " + Math.round(live.calculateDistance2D(hunter)) + ".");
		live.setTarget(hunter);
		onPlayerSocial(live, BOW);
		onPlayerSocial(live, BOW);
		ThreadPool.schedule(() ->
		{
			check("a bow to " + hunter.getName() + " is answered once, with gesture " + last(hunter), (sent("reply") == 1) && ((last(hunter) == BOW) || (last(hunter) == GREETING)));
			check(hunter.getName() + " stands while answering", holds(hunter, System.currentTimeMillis()));
			silence(live, near, duel, crowd, far);
		}, REPLY_DELAY_MAX + 100);
	}

	/** Кому не отвечают: нет цели, сам себе, далеко, в бою, мёртвый, салон красоты, ключ выключен, жест не от живого. */
	private void silence(Player live, List<Player> near, List<Player> duel, List<Player> crowd, Player far)
	{
		final int before = sent("reply");
		final Player fighter = near.get(2);
		final Player dead = near.get(3);
		final Player other = near.get(4);
		live.setTarget(null);
		onPlayerSocial(live, GREETING);
		live.setTarget(live);
		onPlayerSocial(live, GREETING);
		live.setTarget(far);
		onPlayerSocial(live, GREETING);
		AttackStanceTaskManager.getInstance().addAttackStanceTask(fighter);
		live.setTarget(fighter);
		onPlayerSocial(live, GREETING);
		dead.doDie(null);
		live.setTarget(dead);
		onPlayerSocial(live, GREETING);
		live.setTarget(other);
		onPlayerSocial(live, LAST_PLAYER_GESTURE + 1);
		PhantomHuntersConfig.HUNTER_GESTURES = false;
		onPlayerSocial(live, GREETING);
		PhantomHuntersConfig.HUNTER_GESTURES = true;
		fighter.setTarget(other);
		onPlayerSocial(fighter, GREETING);
		ThreadPool.schedule(() ->
		{
			check("no target, self, far, in combat, dead, beauty shop, key off, not live: no answer", (sent("reply") == before) && (last(fighter) == 0) && (last(dead) == 0) && (last(other) == 0) && (last(far) == 0));
			enemy(live, near, duel, crowd, far);
		}, REPLY_DELAY_MAX + 500);
	}

	/** Враг отмахивается или молчит, по-дружески не отвечает никогда. */
	private void enemy(Player live, List<Player> near, List<Player> duel, List<Player> crowd, Player far)
	{
		PhantomFavors.clear(live);
		final Player hunter = near.get(1);
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (int i = 0; (i < 8) && !relations.isEnemy(hunter, live); i++)
		{
			relations.record(hunter, live, PhantomRelations.Kind.KILLED);
		}
		check(hunter.getName() + " is an enemy", relations.isEnemy(hunter, live));
		final int friendly = sent("reply");
		live.setTarget(hunter);
		final int tries = 16;
		for (int i = 0; i < tries; i++)
		{
			_replyNext.clear();
			onPlayerSocial(live, GREETING);
		}
		ThreadPool.schedule(() ->
		{
			check("the enemy refuses " + sent("refuse") + " and ignores " + sent("silent") + " of " + tries + ", never answers kindly", (sent("refuse") > 0) && (sent("silent") > 0) && ((sent("refuse") + sent("silent")) == tries) && (sent("reply") == friendly) && (last(hunter) == NO));
			greeting(live, near, duel, crowd, far);
		}, REPLY_DELAY_MAX + 500);
	}

	private void greeting(Player live, List<Player> near, List<Player> duel, List<Player> crowd, Player far)
	{
		for (String text : new String[]
		{
			"Привет всем",
			"ку",
			"о, здарова",
			"hi all",
			"Добрый вечер",
			"прив)"
		})
		{
			check("«" + text + "» is a greeting", isGreeting(text));
		}
		for (String text : new String[]
		{
			"пойду на спот",
			"кулдаун ещё идёт",
			"куда идёшь",
			"прикольно",
			"хайповая пушка",
			"давно тут стою и всем передавай привет"
		})
		{
			check("«" + text + "» is not a greeting", !isGreeting(text));
		}

		final PhantomChat chat = PhantomChat.getInstance();
		final Player first = near.get(0);
		final Player second = near.get(4);
		chat.hunterSay(first, "Привет всем", "test");
		chat.hunterSay(first, "Привет ещё раз", "test");
		chat.hunterSay(second, "пойду на спот", "test");
		final int plain = sent("wave");
		heard(second, "привет");
		chat.hunterSay(second, "и тебе не болеть", "test");
		chat.hunterSay(far, "привет", "test");
		ThreadPool.schedule(() ->
		{
			check("greeted aloud once despite two lines, answered a greeting without the word, nobody to see far away: waves " + sent("wave"), (plain == 0) && (sent("wave") == 2) && (last(first) == GREETING) && (last(second) == GREETING) && (last(far) == 0));
			victory(live, near, duel, crowd, far);
		}, WAVE_DELAY_MAX + 500);
	}

	/** Настоящая смерть от руки охотника идёт через {@code onKilled}. Рейд — прямой вызов: своего хода у него здесь нет. */
	private void victory(Player live, List<Player> near, List<Player> duel, List<Player> crowd, Player far)
	{
		final Player winner = near.get(0);
		final Player victim = near.get(4);
		victim.doDie(winner);
		ThreadPool.schedule(() ->
		{
			check("killed " + victim.getName() + ": victory gestures " + sent("victory") + " by " + winner.getName(), (sent("victory") == 1) && (last(winner) == VICTORY));
			onRaidKilled(List.of(winner.getObjectId(), near.get(1).getObjectId(), far.getObjectId()));
			ThreadPool.schedule(() ->
			{
				check("raid killed: one more (" + near.get(1).getName() + "), the winner is on the gap, nobody sees the far one: " + sent("victory"), (sent("victory") == 2) && (last(near.get(1)) == VICTORY) && (last(far) == 0));
				duel(live, duel, crowd, far);
			}, VICTORY_DELAY_MAX + 500);
		}, VICTORY_DELAY_MAX + 1000);
	}

	/** Настоящая дуэль Mobius: дуэль ведёт {@link PhantomDuel} раньше заморозки. */
	private void duel(Player live, List<Player> duel, List<Player> crowd, Player far)
	{
		final Player a = duel.get(0);
		final Player b = duel.get(1);
		PhantomFavors.clear(live);
		PhantomFavors.bring(a, live);
		PhantomFavors.bring(b, live);
		final int before = sent("victory");
		ThreadPool.schedule(() ->
		{
			line(a.getName() + " (" + a.getLevel() + ") and " + b.getName() + " (" + b.getLevel() + ") start a duel.");
			PhantomDuel.getInstance().testBegin(a, b);
			duelWait(live, a, b, before, crowd, far, 36);
		}, 5000);
	}

	private void duelWait(Player live, Player a, Player b, int before, List<Player> crowd, Player far, int left)
	{
		if (PhantomDuel.getInstance().isBusy(a) && (left > 0))
		{
			ThreadPool.schedule(() -> duelWait(live, a, b, before, crowd, far, left - 1), 5000);
			return;
		}
		ThreadPool.schedule(() ->
		{
			check("the duel is over after " + ((36 - left) * 5) + " s, victory gestures " + (sent("victory") - before), left > 0);
			line("last gestures: " + a.getName() + " " + last(a) + ", " + b.getName() + " " + last(b) + "; no gesture means a draw or a cancelled duel, the duel log has the result.");
			idle(live, crowd, far);
		}, VICTORY_DELAY_MAX + 500);
	}

	/** Город: живого нет — ни жеста; живой пришёл — каждый в свой момент и своим жестом. */
	private void idle(Player live, List<Player> crowd, Player far)
	{
		PhantomHuntersConfig.HUNTER_GESTURE_IDLE_COOLDOWN = 20;
		PhantomHuntersConfig.HUNTER_GESTURE_IDLE_CHANCE = 1000;
		for (Player hunter : crowd)
		{
			calm(hunter);
			PhantomFavors.toTown(hunter);
		}
		ThreadPool.schedule(() ->
		{
			final long now = System.currentTimeMillis();
			for (Player hunter : crowd)
			{
				_idleNext.put(hunter.getObjectId(), 0L);
				line(hunter.getName() + " in town " + PhantomCompat.isInTown(hunter) + ", intention " + hunter.getAI().getIntention() + ", busy " + busy(hunter) + ", own " + own(hunter)[0] + " and " + own(hunter)[1] + ".");
				tick(hunter, PhantomData.getInstance().getTemplate(hunter.getName()), now);
			}
			check("in town with nobody live around: no gesture", sent("idle") == 0);
			calm(live);
			PhantomFavors.toTown(live);
			_idleNext.clear();
			ThreadPool.schedule(() -> idlePass(live, crowd, far, 60), 5000);
		}, 6000);
	}

	private void idlePass(Player live, List<Player> crowd, Player far, int left)
	{
		if (left > 0)
		{
			final long now = System.currentTimeMillis();
			for (Player hunter : crowd)
			{
				tick(hunter, PhantomData.getInstance().getTemplate(hunter.getName()), now);
			}
			ThreadPool.schedule(() -> idlePass(live, crowd, far, left - 1), 1000);
			return;
		}

		final Map<Long, List<long[]>> byHunter = new HashMap<>();
		synchronized (_idleLog)
		{
			for (long[] entry : _idleLog)
			{
				byHunter.computeIfAbsent(entry[0], k -> new ArrayList<>()).add(entry);
			}
		}
		boolean ownOnly = true;
		long firstMin = Long.MAX_VALUE;
		long firstMax = 0;
		long gapMin = Long.MAX_VALUE;
		for (Player hunter : crowd)
		{
			final List<long[]> entries = byHunter.getOrDefault((long) hunter.getObjectId(), List.of());
			final int[] own = own(hunter);
			for (int i = 0; i < entries.size(); i++)
			{
				final long[] entry = entries.get(i);
				ownOnly &= (entry[1] == own[0]) || (entry[1] == own[1]);
				if (i == 0)
				{
					firstMin = Math.min(firstMin, entry[2]);
					firstMax = Math.max(firstMax, entry[2]);
				}
				else
				{
					gapMin = Math.min(gapMin, entry[2] - entries.get(i - 1)[2]);
				}
			}
		}
		check("the crowd gestured " + sent("idle") + " times, " + byHunter.size() + " of " + crowd.size() + " hunters", byHunter.size() == crowd.size());
		check("own gestures only", ownOnly);
		check("not at once: first gestures spread over " + ((firstMax - firstMin) / 1000) + " s", (firstMax - firstMin) >= 3000);
		check("the cooldown holds: shortest gap " + (gapMin / 1000) + " s of 20 x 0.5", gapMin >= 9000);

		// Настоящий путь: тик из прохода охотника, заморозку снимаем.
		final int before = sent("idle");
		for (Player hunter : crowd.subList(0, 3))
		{
			_idleNext.put(hunter.getObjectId(), 0L);
			PhantomFavors.getInstance().unfreeze(hunter);
		}
		ThreadPool.schedule(() ->
		{
			check("through the hunter's own pass: " + (sent("idle") - before) + " gestures", sent("idle") > before);
			line("sent: reply " + sent("reply") + ", refuse " + sent("refuse") + ", wave " + sent("wave") + ", victory " + sent("victory") + ", idle " + sent("idle") + "; ignored by an enemy " + sent("silent") + "; players around in total " + _recipients.get() + ", seen by the live one " + _seenByLive.get() + ".");
			line("done: " + _ok.get() + " ok, " + _wrong.get() + " wrong.");
		}, 20000);
	}

	public static PhantomGestures getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomGestures INSTANCE = new PhantomGestures();
	}
}
