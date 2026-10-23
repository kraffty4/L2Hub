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
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * L2Hub: ПК-охотники и реакция на ПК (docs/PHANTOMS.md, раздел 4.44; {@code HunterPk} в PhantomHunters.ini, по умолчанию выключено — решает автор).
 * <ul>
 * <li>стать ПК — раз в {@value #CHECK_GAP} мс охотник на споте (не в городе) с шансом {@code HunterPkChance} % × вспыльчивость нападает на белого рядом,
 * только горячий (вспыльчивость от {@value #HOT_TEMPER}) или у кого есть враги, не чаще раза в {@value #PK_GAP} мс; бьёт, пока не убьёт (махач, но не отходит),
 * карму даёт Mobius, отмывается она штатно — убийством мобов;</li>
 * <li>жертва — охотник рядом (враг — первым) не младше ПК больше чем на {@value #VICTIM_LEVEL_GAP} уровня; живой игрок — только если сам флаганулся
 * или в одиночку (без пати, других живых рядом нет) зашёл на спот ПК, тоже не младше: не «убил новичка у Talking Island»;</li>
 * <li>реакция — охотник видит ПК (карма) в {@value #SEE_RANGE}: не слабее его больше чем на {@value #FIGHT_LEVEL_GAP} уровня, с половиной HP и не трус
 * (вспыльчивость от {@value #BRAVE_TEMPER} или ПК ему враг) — бьёт (ПК бьют без кармы), иначе убегает на {@value #FLEE_DISTANCE} и иногда кричит
 * в торговый чат «ПК там-то»;</li>
 * <li>в «Летопись мира» ({@link PhantomNews}, вид {@code pk}) — убийство ПК и смерть ПК от игрока или охотника.</li>
 * </ul>
 */
public final class PhantomPk
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPk.class.getName());

	/** Кто-то из охотников может стать ПК — проверка не чаще. */
	private static final long CHECK_GAP = 600000;
	/** Один охотник — ПК не чаще раза в столько. */
	private static final long PK_GAP = 10800000;
	/** Горячий — с такой вспыльчивости; смелый (бьёт ПК) — с такой. */
	private static final float HOT_TEMPER = 1.2f;
	private static final float BRAVE_TEMPER = 0.8f;
	/** Жертва — в этом радиусе и не младше ПК больше чем на столько уровней. */
	private static final int VICTIM_RANGE = 1500;
	private static final int VICTIM_LEVEL_GAP = 3;
	/** Охотник видит ПК в этом радиусе; бьёт, если не слабее его больше чем на столько уровней. */
	private static final int SEE_RANGE = 1200;
	private static final int FIGHT_LEVEL_GAP = 2;
	/** Пара «охотник — ПК»: решение (бить или бежать) — не чаще. */
	private static final long REACT_GAP = 60000;
	/** Убегает на столько; кричит в торговый чат с таким шансом, %. */
	private static final int FLEE_DISTANCE = 1500;
	private static final int SHOUT_CHANCE = 30;
	/** ПК гонится за жертвой не дольше. */
	static final long CHASE = 90000;

	private long _lastCheck;
	private final Map<Integer, Long> _nextPk = new ConcurrentHashMap<>();
	private final Map<Long, Long> _reacted = new ConcurrentHashMap<>();

	private PhantomPk()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control} (раз в 10 с): реакция на ПК вокруг; раз в {@value #CHECK_GAP} мс — не станет ли кто ПК.
	 * @param onSpots охотники на каче, не в пати, живы, не на ивенте, олимпиаде и дуэли
	 * @param now текущее время
	 */
	synchronized void control(List<Player> onSpots, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PK)
		{
			return;
		}
		react(now);
		if ((now - _lastCheck) < CHECK_GAP)
		{
			return;
		}
		_lastCheck = now;

		final List<Player> candidates = new ArrayList<>(onSpots);
		Collections.shuffle(candidates);
		for (Player pk : candidates)
		{
			if ((PhantomCompat.karma(pk) > 0) || (now < _nextPk.getOrDefault(pk.getObjectId(), 0L)) || pk.isInsideZone(ZoneId.PEACE))
			{
				continue;
			}
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(pk.getName());
			if ((template == null) || ((template.getTemper() < HOT_TEMPER) && !PhantomRelations.getInstance().hasEnemies(pk)))
			{
				continue;
			}
			if (Rnd.get(100.0) >= (PhantomHuntersConfig.HUNTER_PK_CHANCE * template.getTemper()))
			{
				continue;
			}
			final Player victim = victim(pk);
			if (victim == null)
			{
				continue;
			}
			_nextPk.put(pk.getObjectId(), now + PK_GAP);
			LOGGER.info(getClass().getSimpleName() + ": " + pk.getName() + " (" + pk.getLevel() + ") goes PK on " + victim.getName() + " (" + victim.getLevel() + (PhantomManager.getInstance().isPhantom(victim) ? "" : ", player") + ").");
			PhantomHunterManager.getInstance().startPk(pk, victim);
			return;
		}
	}

	/**
	 * Жертва: охотник на каче рядом (враг — первым) или живой игрок — флаганулся сам или один на споте ПК; не младше ПК больше чем на {@value #VICTIM_LEVEL_GAP}.
	 */
	private static Player victim(Player pk)
	{
		Player best = null;
		int bestScore = -1;
		for (Player other : World.getVisibleObjectsInRange(pk, Player.class, VICTIM_RANGE))
		{
			if ((other == pk) || other.isDead() || other.isInvisible() || other.isGM() || other.isInsideZone(ZoneId.PEACE) || (other.getLevel() < (pk.getLevel() - VICTIM_LEVEL_GAP)) || (PhantomCompat.karma(other) > 0) || other.isInDuel() || other.isInOlympiadMode() || other.isInParty())
			{
				continue;
			}
			final int score;
			if (PhantomManager.getInstance().isPhantom(other))
			{
				if (!PhantomRelations.isHunter(other) || !PhantomHunterManager.getInstance().isHunting(other))
				{
					continue;
				}
				score = PhantomRelations.getInstance().isEnemy(pk, other) ? 3 : 1;
			}
			else
			{
				if ((other.getPvpFlag() == 0) && !alone(other))
				{
					continue;
				}
				score = other.getPvpFlag() > 0 ? 2 : 1;
			}
			if (score > bestScore)
			{
				best = other;
				bestScore = score;
			}
		}
		return best;
	}

	/**
	 * @return живой игрок один: других живых игроков рядом нет
	 */
	private static boolean alone(Player player)
	{
		return World.getFirstVisibleObject(player, Player.class, other -> (other != player) && !PhantomManager.getInstance().isPhantom(other) && (player.calculateDistance2D(other) < VICTIM_RANGE)) == null;
	}

	/**
	 * Охотники вокруг ПК (карма у охотника или живого игрока): бить или бежать — по силе и характеру.
	 */
	private void react(long now)
	{
		for (Player pk : World.getPlayers())
		{
			if ((PhantomCompat.karma(pk) <= 0) || pk.isDead() || pk.isInsideZone(ZoneId.PEACE))
			{
				continue;
			}
			for (Player hunter : World.getVisibleObjectsInRange(pk, Player.class, SEE_RANGE))
			{
				if ((hunter == pk) || hunter.isDead() || !PhantomRelations.isHunter(hunter) || !PhantomHunterManager.getInstance().isHunting(hunter))
				{
					continue;
				}
				final long key = ((long) hunter.getObjectId() << 32) | pk.getObjectId();
				if (now < _reacted.getOrDefault(key, 0L))
				{
					continue;
				}
				_reacted.put(key, now + REACT_GAP);
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
				final float temper = template != null ? template.getTemper() : 1;
				final boolean strong = (hunter.getLevel() >= (pk.getLevel() - FIGHT_LEVEL_GAP)) && (hunter.getCurrentHp() > (hunter.getMaxHp() / 2));
				final boolean brave = (temper >= BRAVE_TEMPER) || PhantomRelations.getInstance().isEnemy(hunter, pk);
				if (strong && brave && PhantomHunterManager.getInstance().engagePk(hunter, pk))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") attacks PK " + pk.getName() + " (" + pk.getLevel() + ").");
					if (PhantomBanter.audience(hunter))
					{
						PhantomChat.getInstance().hunterSay(hunter, Rnd.nextBoolean() ? "бей ПК!" : "ПК, ну держись", "PK");
					}
					continue;
				}
				flee(hunter, pk);
			}
		}
	}

	/**
	 * На охотника напал ПК ({@code PhantomHunterManager.onDamaged}): слабый или трус не отвечает, а убегает — тогда убийство даст ПК карму.
	 * @return убегает (сдачи не давать)
	 */
	boolean fleeFromAttacker(Player hunter, Player pk)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		final float temper = template != null ? template.getTemper() : 1;
		final boolean strong = (hunter.getLevel() >= (pk.getLevel() - FIGHT_LEVEL_GAP)) && (hunter.getCurrentHp() > (hunter.getMaxHp() / 2));
		final boolean brave = (temper >= BRAVE_TEMPER) || PhantomRelations.getInstance().isEnemy(hunter, pk);
		if (strong && brave)
		{
			return false;
		}
		final long now = System.currentTimeMillis();
		final long key = ((long) hunter.getObjectId() << 32) | pk.getObjectId();
		if (now >= _reacted.getOrDefault(key, 0L))
		{
			_reacted.put(key, now + 5000);
			flee(hunter, pk);
		}
		return true;
	}

	/**
	 * Убежать от ПК; иногда — крик в торговый чат.
	 */
	private static void flee(Player hunter, Player pk)
	{
		final double dx = hunter.getX() - pk.getX();
		final double dy = hunter.getY() - pk.getY();
		final double length = Math.max(1, Math.hypot(dx, dy));
		final int x = hunter.getX() + (int) ((dx / length) * FLEE_DISTANCE);
		final int y = hunter.getY() + (int) ((dy / length) * FLEE_DISTANCE);
		final int z = GeoEngine.getInstance().getHeight(x, y, hunter.getZ());
		if ((Math.abs(z - hunter.getZ()) < 300) && PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), hunter.getX(), hunter.getY(), hunter.getZ(), x, y, z, hunter.getInstanceId()))
		{
			hunter.abortAttack();
			hunter.setRunning();
			hunter.getAI().setIntentionMoveTo(new Location(x, y, z));
		}
		LOGGER.info(PhantomPk.class.getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") runs from PK " + pk.getName() + " (" + pk.getLevel() + ").");
		if (Rnd.get(100) < SHOUT_CHANCE)
		{
			PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "осторожно, ПК " + pk.getName() + " " + pk.getLevel() + " лвл тут бегает" : "ПК " + pk.getName() + " режет народ, не ходите сюда");
		}
	}

	/**
	 * ПК добил жертву ({@code PhantomHunterManager.fight}).
	 */
	void onPkKill(Player pk, Player victim)
	{
		final int karma = PhantomCompat.karma(pk);
		LOGGER.info(getClass().getSimpleName() + ": " + pk.getName() + " killed " + victim.getName() + " (karma " + karma + ").");
		final String ending = pk.getAppearance().isFemale() ? "а" : "";
		// Жертва отбивалась (флаг) — по правилам это ПвП, кармы нет.
		PhantomNews.post("pk", karma > 0 ? "ПК: " + who(pk, false) + " убил" + ending + " " + who(victim, true) : who(pk, false) + " напал" + ending + " на " + who(victim, true) + " и убил" + ending + " — тот отбивался, без кармы", pk.getName(), pk);
		if (PhantomBanter.audience(pk))
		{
			PhantomChat.getInstance().hunterSay(pk, Rnd.nextBoolean() ? "ничего личного)" : "не повезло тебе", "PK");
		}
	}

	/**
	 * Охотника с кармой убил игрок или охотник ({@code PhantomHunterManager.onKilled}).
	 */
	void onPkKilled(Player pk, Player killer)
	{
		LOGGER.info(getClass().getSimpleName() + ": PK " + pk.getName() + " was killed by " + killer.getName() + ".");
		PhantomNews.post("pk", "ПК " + pk.getName() + " (" + pk.getLevel() + ") получил по заслугам: " + who(killer, false), killer.getName(), pk);
	}

	private static String who(Player player, boolean accusative)
	{
		final boolean hunter = PhantomManager.getInstance().isPhantom(player);
		return (hunter ? (accusative ? "охотника " : "охотник ") : (accusative ? "игрока " : "игрок ")) + player.getName() + " (" + player.getLevel() + ")";
	}

	public static PhantomPk getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomPk INSTANCE = new PhantomPk();
	}
}
