package org.l2jmobius.gameserver.managers.phantom;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: перепалки и приветствия охотников между собой (патч 0033, docs/PHANTOMS.md, раздел 4.17).<br>
 * Охотник раз в {@value #LOOK_GAP} мс смотрит, нет ли рядом (в {@value #RANGE}) охотника, которому он враг или друг ({@link PhantomRelations}).
 * Говорят только при зрителе — живом игроке в пределах слышимости: без него модель жгла бы деньги впустую.
 * Дальше по порядку: тишина в округе ({@link PhantomQuiet}), кулдаун пары {@link PhantomHuntersConfig#HUNTER_BANTER_COOLDOWN}, бросок {@link PhantomHuntersConfig#HUNTER_BANTER_CHANCE} × общительность.
 * Выпало — первый говорит реплику (враг — подкол или претензия, друг — привет), второй через пару секунд отвечает в своём отношении ({@link PhantomChat#banter}).
 */
public class PhantomBanter
{
	private static final Logger LOGGER = Logger.getLogger(PhantomBanter.class.getName());

	static final String TOPIC = "banter";
	/** Встреча — охотники не дальше этого друг от друга. */
	private static final int RANGE = 600;
	/** Зритель — живой игрок в пределах слышимости общего чата. */
	private static final int AUDIENCE = 1250;
	/** Охотник оглядывается не чаще. */
	private static final long LOOK_GAP = 10000;

	private final Map<Integer, Long> _nextLook = new ConcurrentHashMap<>();
	/** Пара охотников (меньший objectId, больший) → не раньше. */
	private final Map<Long, Long> _pairNext = new ConcurrentHashMap<>();

	protected PhantomBanter()
	{
	}

	/**
	 * Тик охотника (на каче, в городе, в лавке — из {@link PhantomHunterManager}).
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	public void tick(Player hunter, PhantomTemplate template, long now)
	{
		final int id = hunter.getObjectId();
		if (!PhantomHuntersConfig.HUNTER_BANTER || !PhantomChat.brainEnabled() || (now < _nextLook.getOrDefault(id, 0L)))
		{
			return;
		}
		_nextLook.put(id, now + LOOK_GAP);
		if (hunter.isDead() || hunter.isTeleporting() || (PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED))
		{
			return;
		}
		if (World.getFirstVisibleObject(hunter, Player.class, p -> !PhantomManager.getInstance().isPhantom(p) && !p.isInvisible() && (hunter.calculateDistance2D(p) < AUDIENCE)) == null)
		{
			return;
		}

		for (Player other : World.getVisibleObjectsInRange(hunter, Player.class, RANGE))
		{
			if (other.isDead() || !PhantomRelations.isHunter(other))
			{
				continue;
			}

			final boolean enemy = PhantomRelations.getInstance().isEnemy(hunter, other);
			if (!enemy && !PhantomRelations.getInstance().isFriend(hunter, other))
			{
				continue;
			}
			// Встретились враги, а терпеть уже невозможно — махач вместо слов (патч 0037, {@link PhantomBrawl}).
			if (enemy && PhantomBrawl.getInstance().provoked(hunter, other, "он снова мозолит тебе глаза на твоём споте", now))
			{
				return;
			}

			final long pair = pair(hunter, other);
			if ((now < _pairNext.getOrDefault(pair, 0L)) || !PhantomQuiet.getInstance().allowed(hunter, TOPIC, now))
			{
				continue;
			}

			// Бросок — раз за кулдаун пары: не выпало — молчат до следующего раза.
			_pairNext.put(pair, now + (PhantomHuntersConfig.HUNTER_BANTER_COOLDOWN * 1000L));
			final double chance = PhantomHuntersConfig.HUNTER_BANTER_CHANCE * template.getSocial();
			final boolean yes = Rnd.get(100.0) < chance;
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " -> " + other.getName() + " (" + (enemy ? "враг" : "друг") + "), шанс " + String.format(Locale.ROOT, "%.0f", chance) + "% — " + (yes ? "говорит." : "молчит."));
			if (yes)
			{
				PhantomChat.getInstance().banter(hunter, other, template, enemy);
			}
			return;
		}
	}

	/**
	 * @param hunter охотник
	 * @return рядом (в слышимости общего чата) есть живой игрок — есть кому слышать, можно звать модель (патч 0037)
	 */
	static boolean audience(Player hunter)
	{
		return World.getFirstVisibleObject(hunter, Player.class, p -> !PhantomManager.getInstance().isPhantom(p) && !p.isInvisible() && (hunter.calculateDistance2D(p) < AUDIENCE)) != null;
	}

	private static long pair(Player a, Player b)
	{
		final long low = Math.min(a.getObjectId(), b.getObjectId());
		final long high = Math.max(a.getObjectId(), b.getObjectId());
		return (low << 32) | (high & 0xffffffffL);
	}

	public static PhantomBanter getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomBanter INSTANCE = new PhantomBanter();
	}
}
