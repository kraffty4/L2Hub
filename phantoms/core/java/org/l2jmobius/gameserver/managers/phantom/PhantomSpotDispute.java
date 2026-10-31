package org.l2jmobius.gameserver.managers.phantom;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.managers.phantom.PhantomRelations.Kind;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: споры за спот (docs/PHANTOMS.md, раздел 4.64; docs/LIVE_SERVER.md, п. 5). Ключ {@code HunterSpotDisputes}: спорит с вежливой охотой, автор снял её для таких охотников сам (решение 2026-10-31).
 * Горячий охотник, который стоит на споте давно, не уступает живому: сначала просит уйти, потом отжимает его моба и копит обиду, потом зовёт знакомого. Живого не бьёт: новых
 * ПК-охотников нет; ответит живой сам — дальше обычные предъява и махач. На сильного не лезет, друга, напарника и того, с кем договорился делить спот, не трогает.
 */
public class PhantomSpotDispute
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSpotDispute.class.getName());

	private static final int RANGE = 900;
	/** Живой считается сильным, если он на столько уровней выше: на такого не лезут. */
	private static final int STRONG_GAP = 3;
	/** Столько мс охотник должен простоять на споте: спор начинает тот, кто пришёл первым. */
	private static final long SETTLED = 180000;
	private static final long CHECK = 30000;
	private static final long STEP_MIN = 120000;
	private static final long STEP_MAX = 240000;
	/** Дольше спор не длится: живой мог уйти и вернуться, охотник — уйти в город. */
	private static final long DISPUTE_MAX = 900000;
	private static final long STEAL_HOLD = 12000;
	private static final long LIVING_GAP = 600000;
	private static final long SAY_COOLDOWN = 60000;

	private static final String[] ASK =
	{
		"тут занято, соседний спот свободен",
		"это мой спот, иди на соседний",
		"слышь, я тут давно стою. поищи другое место"
	};
	private static final String[] STEAL =
	{
		"этот моб мой",
		"я предупреждал, мобы тут мои",
		"не успел — твои проблемы"
	};
	private static final String[] FRIEND =
	{
		"щас кореша позову, вдвоём быстрее решим",
		"ладно, зову своих",
		"сам напросился, сейчас подойдут"
	};

	private enum Step
	{
		ASK,
		STEAL,
		FRIEND
	}

	private static class Dispute
	{
		final int living;
		final long start;
		Step step = Step.ASK;
		long next;

		Dispute(int living, long now)
		{
			this.living = living;
			start = now;
			next = now;
		}
	}

	/** Охотник → идущий спор. */
	private final Map<Integer, Dispute> _disputes = new ConcurrentHashMap<>();
	/** Пара (охотник, живой) → снова спорить не раньше. */
	private final Map<Long, Long> _pairNext = new ConcurrentHashMap<>();
	/** Живой → новый спор с ним не раньше. */
	private final Map<Integer, Long> _livingNext = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _checkNext = new ConcurrentHashMap<>();
	/** Охотник отжимает моба: AutoPlay стоит до этого времени. */
	private final Map<Integer, Long> _stealing = new ConcurrentHashMap<>();
	/** Самопроверка: шаги идут через столько мс, реплики не уходят в чат и мозгам. */
	private volatile long _testGap;

	protected PhantomSpotDispute()
	{
	}

	/**
	 * Тик охотника на споте (фаза HUNT, раз в 2 с).
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param spotSince с какого времени он на споте
	 * @param busy дерётся с врагом
	 * @param now текущее время
	 * @return {@code true}, пока он отжимает моба: остальной тик ждёт
	 */
	boolean tick(Player hunter, PhantomTemplate template, long spotSince, boolean busy, long now)
	{
		final int id = hunter.getObjectId();
		final long hold = _stealing.getOrDefault(id, 0L);
		if (hold > 0)
		{
			final WorldObject target = hunter.getTarget();
			if ((now < hold) && !hunter.isDead() && (target instanceof Monster) && !((Monster) target).isAlikeDead())
			{
				return true;
			}
			_stealing.remove(id);
			return false;
		}
		if (!PhantomHuntersConfig.HUNTER_SPOT_DISPUTES)
		{
			_disputes.remove(id);
			return false;
		}
		if (busy || hunter.isDead() || (now < _checkNext.getOrDefault(id, 0L)))
		{
			return false;
		}
		_checkNext.put(id, now + (_testGap > 0 ? 2000 : CHECK));
		final Dispute dispute = _disputes.get(id);
		if (dispute != null)
		{
			advance(hunter, dispute, now);
			return _stealing.containsKey(id);
		}
		for (Player other : World.getVisibleObjectsInRange(hunter, Player.class, RANGE))
		{
			if (isLiving(other) && (refusal(hunter, template, other, spotSince, now) == null))
			{
				_disputes.put(id, new Dispute(other.getObjectId(), now));
				_livingNext.put(other.getObjectId(), now + DISPUTE_MAX);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") does not yield the spot to " + other.getName() + " (" + other.getLevel() + ").");
				break;
			}
		}
		return false;
	}

	private void advance(Player hunter, Dispute dispute, long now)
	{
		if (now < dispute.next)
		{
			return;
		}
		final Player living = World.getPlayer(dispute.living);
		if ((living == null) || living.isDead() || !hunter.isInsideRadius2D(living, RANGE * 2) || ((now - dispute.start) > DISPUTE_MAX) || hunter.isInParty())
		{
			end(hunter, dispute, now, "the living player left or the time is up");
			return;
		}
		switch (dispute.step)
		{
			case ASK:
			{
				say(hunter, living, "SPOT_ASK", living.getName() + " пришёл качаться на твой спот, а ты здесь давно и уступать не хочешь. Скажи ему, чтобы шёл на соседний", ASK);
				dispute.step = Step.STEAL;
				break;
			}
			case STEAL:
			{
				final WorldObject target = living.getTarget();
				if (!(target instanceof Monster) || ((Monster) target).isAlikeDead() || !hunter.isInsideRadius2D(target, RANGE))
				{
					return; // живой сейчас не бьёт моба — следующая проверка
				}
				AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
				hunter.setTarget(target);
				hunter.getAI().setIntentionAttack((Monster) target);
				_stealing.put(hunter.getObjectId(), now + STEAL_HOLD);
				PhantomRelations.getInstance().record(hunter, living, Kind.CONTESTED);
				say(hunter, living, "SPOT_STEAL", living.getName() + " не ушёл с твоего спота, и ты назло бьёшь его моба (" + target.getName() + ")", STEAL);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " takes the mob " + target.getName() + " from " + living.getName() + ".");
				dispute.step = Step.FRIEND;
				break;
			}
			case FRIEND:
			{
				final Player friend = PhantomHunterManager.getInstance().callToSpot(hunter);
				if (friend != null)
				{
					say(hunter, living, "SPOT_FRIEND", living.getName() + " так и стоит на твоём споте, и ты зовёшь знакомого " + friend.getName() + ". Скажи об этом", FRIEND);
				}
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " calls " + (friend != null ? friend.getName() : "nobody: no free friend") + " to the spot.");
				end(hunter, dispute, now, "all said and done");
				return;
			}
		}
		dispute.next = now + (_testGap > 0 ? _testGap : Rnd.get(STEP_MIN, STEP_MAX));
	}

	private void end(Player hunter, Dispute dispute, long now, String why)
	{
		_disputes.remove(hunter.getObjectId());
		_pairNext.put(pair(hunter.getObjectId(), dispute.living), now + (PhantomHuntersConfig.HUNTER_SPOT_DISPUTE_MINUTES * 60000L));
		_livingNext.put(dispute.living, now + LIVING_GAP);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " ends the dispute: " + why + ".");
	}

	private void say(Player hunter, Player living, String kind, String what, String[] lines)
	{
		final String line = lines[Rnd.get(lines.length)];
		if (_testGap > 0)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " would say " + kind + " (not sent in the self-test).");
			return;
		}
		PhantomChat.getInstance().hunterEvent(hunter, living, kind, what, line, SAY_COOLDOWN);
	}

	private static boolean isLiving(Player player)
	{
		return PhantomNear.getInstance().isTestPlayer(player) || (!PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached());
	}

	/**
	 * @return горячий: рисковый или из доли {@code HunterSpotDisputeShare} по нику; осторожный никогда
	 */
	static boolean hot(PhantomTemplate template, String name)
	{
		if (template.isCautious())
		{
			return false;
		}
		final int hash = name.toLowerCase(Locale.ROOT).hashCode();
		return template.isReckless() || (Math.floorMod(hash ^ (hash >>> 16), 100) < PhantomHuntersConfig.HUNTER_SPOT_DISPUTE_SHARE);
	}

	/**
	 * @return почему охотник не спорит с этим живым, {@code null} — спорит
	 */
	private String refusal(Player hunter, PhantomTemplate template, Player living, long spotSince, long now)
	{
		if (!hot(template, hunter.getName()))
		{
			return "not hot";
		}
		if (PhantomManager.getInstance().isSecret(hunter))
		{
			return "secret";
		}
		if ((now - spotSince) < SETTLED)
		{
			return "came recently";
		}
		if (living.isDead() || !(living.getTarget() instanceof Monster))
		{
			return "the living player does not hunt";
		}
		if (living.getLevel() > (hunter.getLevel() + STRONG_GAP))
		{
			return "too strong";
		}
		if (hunter.isInParty() && (hunter.getParty() == living.getParty()))
		{
			return "same party";
		}
		if (PhantomRelations.getInstance().isFriend(hunter, living))
		{
			return "friend";
		}
		if (PhantomNear.getInstance().shared(hunter, living, now))
		{
			return "they share the spot";
		}
		if (now < _pairNext.getOrDefault(pair(hunter.getObjectId(), living.getObjectId()), 0L))
		{
			return "this pair disputed recently";
		}
		if (now < _livingNext.getOrDefault(living.getObjectId(), 0L))
		{
			return "the living player has a dispute already";
		}
		return null;
	}

	private static long pair(int hunter, int living)
	{
		return ((long) hunter << 32) | (living & 0xffffffffL);
	}

	/**
	 * Самопроверка SPOTDISPUTE (из {@link PhantomHunterManager}, только на копии базы).
	 * @param tag метка в логе
	 * @param hunter горячий охотник на споте
	 * @param living охотник, который играет живого, уже рядом
	 * @param template шаблон охотника
	 * @param cleanup что сделать в конце
	 */
	void selfTest(String tag, Player hunter, Player living, PhantomTemplate template, Runnable cleanup)
	{
		final boolean keyWas = PhantomHuntersConfig.HUNTER_SPOT_DISPUTES;
		final int id = hunter.getObjectId();
		_testGap = 10000;
		_disputes.remove(id);
		_checkNext.remove(id);
		PhantomHuntersConfig.HUNTER_SPOT_DISPUTES = false;
		final long started = System.currentTimeMillis();
		tick(hunter, template, started - 600000, false, started);
		LOGGER.info(tag + "key off: dispute " + _disputes.containsKey(id) + " (want false). " + hunter.getName() + " (" + hunter.getLevel() + ") and " + living.getName() + " (" + living.getLevel() + ").");
		LOGGER.info(tag + "stands for a minute only: " + refusal(hunter, template, living, started - 60000, started) + "; hot by the name at share 0 if not reckless: " + template.isReckless() + ".");
		PhantomHuntersConfig.HUNTER_SPOT_DISPUTES = true;
		final int grudgeKillsBefore = PhantomRelations.getInstance().kills(hunter, living);
		for (int second = 5; second <= 60; second += 5)
		{
			final int at = second;
			ThreadPool.schedule(() ->
			{
				// «Живой» и его автобой могут отвлечься: цель держим на ближайшем мобе.
				if (!(living.getTarget() instanceof Monster))
				{
					for (Monster mob : World.getVisibleObjectsInRange(living, Monster.class, RANGE))
					{
						if (!mob.isAlikeDead())
						{
							living.setTarget(mob);
							break;
						}
					}
				}
				// Настоящий тик видит настоящее время на споте, а мир только что встал: тут охотник стоит давно.
				final long tickAt = System.currentTimeMillis();
				_checkNext.remove(id);
				tick(hunter, template, tickAt - 600000, false, tickAt);
				final Dispute dispute = _disputes.get(id);
				LOGGER.info(tag + at + " s: step " + (dispute != null ? dispute.step : "none") + ", " + hunter.getName() + " target " + (hunter.getTarget() != null ? hunter.getTarget().getName() : "none") + ", " + living.getName() + " target " + (living.getTarget() != null ? living.getTarget().getName() : "none") + ", stealing " + _stealing.containsKey(id) + ".");
			}, second * 1000L);
		}
		ThreadPool.schedule(() ->
		{
			final long now = System.currentTimeMillis();
			LOGGER.info(tag + "after the dispute: again at once: " + refusal(hunter, template, living, now - 600000, now) + "; a stronger living player: " + (living.getLevel() > (hunter.getLevel() + STRONG_GAP) ? "by the level" : "rule " + STRONG_GAP + " levels") + "; kills between them " + grudgeKillsBefore + " -> " + PhantomRelations.getInstance().kills(hunter, living) + ", living flag " + living.getPvpFlag() + ", karma " + PhantomCompat.karma(hunter) + ".");
			PhantomHuntersConfig.HUNTER_SPOT_DISPUTES = keyWas;
			_testGap = 0;
			_pairNext.clear();
			_livingNext.clear();
			cleanup.run();
			LOGGER.info(tag + "done.");
		}, 70000);
	}

	public static PhantomSpotDispute getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomSpotDispute INSTANCE = new PhantomSpotDispute();
	}
}
