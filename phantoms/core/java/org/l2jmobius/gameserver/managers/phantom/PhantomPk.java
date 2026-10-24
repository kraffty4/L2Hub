package org.l2jmobius.gameserver.managers.phantom;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/**
 * L2Hub: охотники и ПК (docs/PHANTOMS.md, раздел 4.44; решения автора 2026-10-23). Своих ПК среди охотников больше нет — ПК на сервере один, Танатос ({@link PhantomThanatos}).
 * <ul>
 * <li>ПК — приоритетная цель («либо они, либо их»): охотник видит ПК (карма — живой игрок или охотник, взявший карму по «крови за кровь») в {@value #SEE_RANGE}:
 * не слабее его больше чем на {@value #FIGHT_LEVEL_GAP} уровня, с половиной HP и не трус (вспыльчивость от {@value #BRAVE_TEMPER} или ПК ему враг) — бросает мобов и бьёт
 * (ПК бьют без кармы), иначе убегает на {@value #FLEE_DISTANCE} и иногда кричит в торговый чат «ПК там-то». Танатоса охотники встречают по-своему
 * ({@link PhantomThanatos#seen}): слабые бегут, сильные собирают охоту;</li>
 * <li>кровь за кровь: охотник на каче видит живого игрока, которому он враг ({@link PhantomRelations}: тот нападал на него или убивал), — нападает сам и добивает
 * (белого — с кармой, как у игрока), если не слабее его больше чем на {@value #REVENGE_LEVEL_GAP} уровней и цел; одна пара — не чаще раза в {@value #REVENGE_GAP} мс;</li>
 * <li>в «Летопись мира» ({@link PhantomNews}, вид {@code pk}) — кровь за кровь до смерти и смерть ПК от игрока или охотника.</li>
 * </ul>
 */
public final class PhantomPk
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPk.class.getName());

	/** Смелый (бьёт ПК) — с такой вспыльчивости. */
	private static final float BRAVE_TEMPER = 0.8f;
	/** Охотник видит ПК в этом радиусе; бьёт, если не слабее его больше чем на столько уровней. */
	private static final int SEE_RANGE = 1200;
	private static final int FIGHT_LEVEL_GAP = 2;
	/** Пара «охотник — ПК»: решение (бить или бежать) — не чаще. */
	private static final long REACT_GAP = 60000;
	/** Убегает на столько; кричит в торговый чат с таким шансом, %. */
	private static final int FLEE_DISTANCE = 1500;
	private static final int SHOUT_CHANCE = 30;
	/** Охотник гонится за обидчиком (кровь за кровь) не дольше. */
	static final long CHASE = 90000;
	/** Кровь за кровь: видит обидчика в этом радиусе; не слабее его больше чем на столько уровней; HP — не меньше стольких %; пара — не чаще. */
	private static final int REVENGE_RANGE = 1200;
	private static final int REVENGE_LEVEL_GAP = 5;
	private static final int REVENGE_HP = 70;
	private static final long REVENGE_GAP = 1800000;

	private final Map<Long, Long> _reacted = new ConcurrentHashMap<>();
	private final Map<Long, Long> _revenged = new ConcurrentHashMap<>();

	private PhantomPk()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control} (раз в 10 с): реакция на ПК вокруг; кровь за кровь.
	 * @param onSpots охотники на каче, не в пати, живы, не на ивенте, олимпиаде и дуэли
	 * @param now текущее время
	 */
	synchronized void control(List<Player> onSpots, long now)
	{
		react(now);
		revenge(onSpots, now);
	}

	/**
	 * Охотники вокруг ПК (карма у живого игрока или охотника; не Танатос): бить или бежать — по силе и характеру.
	 */
	private void react(long now)
	{
		for (Player pk : World.getPlayers())
		{
			if ((PhantomCompat.karma(pk) <= 0) || pk.isDead() || pk.isInsideZone(ZoneId.PEACE) || PhantomThanatos.is(pk))
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
				if (strongAndBrave(hunter, pk) && PhantomHunterManager.getInstance().engagePk(hunter, pk))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") attacks PK " + pk.getName() + " (" + pk.getLevel() + ").");
					if (PhantomBanter.audience(hunter))
					{
						PhantomChat.getInstance().hunterSay(hunter, Rnd.nextBoolean() ? "бей ПК!" : "ПК, ну держись", "PK");
					}
					continue;
				}
				flee(hunter, pk, true);
			}
		}
	}

	/**
	 * @return охотник не слабее ПК больше чем на {@value #FIGHT_LEVEL_GAP} уровня (Танатоса — с {@link PhantomRaid#HUNT_LEVEL}-го), цел больше чем наполовину и не трус
	 */
	private static boolean strongAndBrave(Player hunter, Player pk)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		final float temper = template != null ? template.getTemper() : 1;
		final int level = PhantomThanatos.is(pk) ? PhantomRaid.HUNT_LEVEL : pk.getLevel() - FIGHT_LEVEL_GAP;
		final boolean strong = (hunter.getLevel() >= level) && (hunter.getCurrentHp() > (hunter.getMaxHp() / 2));
		final boolean brave = (temper >= BRAVE_TEMPER) || PhantomRelations.getInstance().isEnemy(hunter, pk);
		return strong && brave;
	}

	/**
	 * Кровь за кровь: охотник на каче видит живого обидчика — нападает и добивает ({@code PhantomHunterManager.startRevenge}).
	 */
	private void revenge(List<Player> onSpots, long now)
	{
		for (Player hunter : onSpots)
		{
			if (!PhantomRelations.getInstance().hasEnemies(hunter) || hunter.isInsideZone(ZoneId.PEACE) || ((hunter.getCurrentHp() * 100) < (hunter.getMaxHp() * REVENGE_HP)))
			{
				continue;
			}
			for (Player other : World.getVisibleObjectsInRange(hunter, Player.class, REVENGE_RANGE))
			{
				if (PhantomManager.getInstance().isPhantom(other) || other.isDead() || other.isInvisible() || other.isGM() || other.isInsideZone(ZoneId.PEACE) || other.isInOlympiadMode() || other.isInDuel() || other.isOnEvent() || !PhantomRelations.getInstance().isEnemy(hunter, other) || (hunter.getLevel() < (other.getLevel() - REVENGE_LEVEL_GAP)))
				{
					continue;
				}
				final long key = ((long) hunter.getObjectId() << 32) | other.getObjectId();
				if (now < _revenged.getOrDefault(key, 0L))
				{
					continue;
				}
				_revenged.put(key, now + REVENGE_GAP);
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") takes revenge on " + other.getName() + " (" + other.getLevel() + ").");
				PhantomHunterManager.getInstance().startRevenge(hunter, other);
				return;
			}
		}
	}

	/**
	 * На охотника напал ПК или Танатос ({@code PhantomHunterManager.onDamaged}): слабый или трус не отвечает, а убегает — тогда убийство даст ПК карму.
	 * @return убегает (сдачи не давать)
	 */
	boolean fleeFromAttacker(Player hunter, Player pk)
	{
		if (strongAndBrave(hunter, pk))
		{
			return false;
		}
		final long now = System.currentTimeMillis();
		final long key = ((long) hunter.getObjectId() << 32) | pk.getObjectId();
		if (now >= _reacted.getOrDefault(key, 0L))
		{
			_reacted.put(key, now + 5000);
			flee(hunter, pk, !PhantomThanatos.is(pk));
		}
		return true;
	}

	/**
	 * Убежать от Танатоса ({@link PhantomThanatos}): без крика — кричит один, сам Танатос выбирает кто.
	 */
	void fleeFrom(Player hunter, Player from)
	{
		final long now = System.currentTimeMillis();
		final long key = ((long) hunter.getObjectId() << 32) | from.getObjectId();
		if ((now >= _reacted.getOrDefault(key, 0L)) && PhantomHunterManager.getInstance().isHunting(hunter))
		{
			_reacted.put(key, now + 15000);
			flee(hunter, from, false);
		}
	}

	/**
	 * Убежать от ПК; иногда — крик в торговый чат.
	 */
	private static void flee(Player hunter, Player pk, boolean shout)
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
		LOGGER.info(PhantomPk.class.getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") runs from " + pk.getName() + " (" + pk.getLevel() + ").");
		if (shout && (Rnd.get(100) < SHOUT_CHANCE))
		{
			PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "осторожно, ПК " + pk.getName() + " " + pk.getLevel() + " лвл тут бегает" : "ПК " + pk.getName() + " режет народ, не ходите сюда");
		}
	}

	/**
	 * Охотник добил обидчика по «крови за кровь» ({@code PhantomHunterManager.fight}).
	 */
	void onRevengeKill(Player hunter, Player victim)
	{
		final int karma = PhantomCompat.karma(hunter);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " killed " + victim.getName() + " for revenge (karma " + karma + ").");
		final String ending = hunter.getAppearance().isFemale() ? "а" : "";
		// Обидчик отбивался (флаг) — по правилам это ПвП, кармы нет.
		PhantomNews.post("pk", "Кровь за кровь: охотник " + hunter.getName() + " (" + hunter.getLevel() + ") отомстил" + ending + " игроку " + victim.getName() + " (" + victim.getLevel() + ")" + (karma > 0 ? " — и стал" + ending + " ПК" : ""), hunter.getName(), hunter);
		if (PhantomBanter.audience(hunter))
		{
			PhantomChat.getInstance().hunterSay(hunter, Rnd.nextBoolean() ? "кровь за кровь" : "теперь квиты", "PK");
		}
	}

	/**
	 * Охотника с кармой убил игрок или охотник ({@code PhantomHunterManager.onKilled}).
	 */
	void onPkKilled(Player pk, Player killer)
	{
		LOGGER.info(getClass().getSimpleName() + ": PK " + pk.getName() + " was killed by " + killer.getName() + ".");
		PhantomNews.post("pk", "ПК " + pk.getName() + " (" + pk.getLevel() + ") получил по заслугам: " + (PhantomManager.getInstance().isPhantom(killer) ? "охотник " : "игрок ") + killer.getName() + " (" + killer.getLevel() + ")", killer.getName(), pk);
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
