package org.l2jmobius.gameserver.managers.phantom;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;

/**
 * L2Hub: махач охотников-врагов (патч 0037, docs/PHANTOMS.md, раздел 4.20).<br>
 * Решение автора: у вражды есть порог «невозможно терпеть» — дальше драка; но боты боятся ПК, лишний раз не пкшат и оценивают друг друга,
 * чтобы второй тоже флаганулся и было ПвП; всё — с перепалкой в белом чате.<br>
 * Повод — новая обида (отжал моба) или встреча врагов ({@link PhantomNeighbors}, {@link PhantomBanter}). Охотник лезет в драку, только если:
 * <ul>
 * <li>обида не меньше {@link PhantomHuntersConfig#HUNTER_BRAWL_GRUDGE} и он враг;</li>
 * <li>рядом есть живой зритель ({@link PhantomHuntersConfig#HUNTER_BRAWL_WITNESS}) — драка должна быть видна;</li>
 * <li>оба на каче, не в одной пати, не в мирной зоне, без кармы; у него HP от 70 %, у врага от 50 %;</li>
 * <li>разница уровней не больше {@link PhantomHuntersConfig#HUNTER_BRAWL_LEVEL_GAP}: на сильного не лезет, слабого не бьёт — тот ответит, а не умрёт белым;</li>
 * <li>пара не дралась {@link PhantomHuntersConfig#HUNTER_BRAWL_COOLDOWN} с, и выпал бросок {@link PhantomHuntersConfig#HUNTER_BRAWL_CHANCE}.</li>
 * </ul>
 * Дальше {@link PhantomHunterManager#startBrawl}: угроза вслух и первый удар. Второй отвечает (слушатель урона охотника) и тоже получает флаг — ПвП без кармы.
 * Не ответил за {@value #GRACE} мс или почти умер без флага — зачинщик отходит со словами: убить белого — это карма.
 */
public class PhantomBrawl
{
	private static final Logger LOGGER = Logger.getLogger(PhantomBrawl.class.getName());

	/** Зачинщик ждёт ответного удара столько; второй не флагнулся — отходит. */
	static final long GRACE = 6000;
	/** Зритель — живой игрок не дальше этого. */
	private static final int WITNESS = 2000;

	/** Пара (меньший objectId, больший) → не драться до. */
	private final Map<Long, Long> _pairNext = new ConcurrentHashMap<>();

	protected PhantomBrawl()
	{
	}

	/**
	 * Повод для драки: дошла ли обида до «невозможно терпеть», и если да — оценка и нападение.
	 * @param angry кто обижен
	 * @param other на кого
	 * @param why что сейчас случилось — для угрозы вслух («он опять отжал у тебя моба»)
	 * @param now текущее время
	 * @return драка началась
	 */
	public boolean provoked(Player angry, Player other, String why, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_BRAWLS || !PhantomRelations.isHunter(angry) || !PhantomRelations.isHunter(other))
		{
			return false;
		}

		final PhantomRelations relations = PhantomRelations.getInstance();
		final double grudge = relations.grudge(angry, other);
		if ((grudge < PhantomHuntersConfig.HUNTER_BRAWL_GRUDGE) || !relations.isEnemy(angry, other))
		{
			return false;
		}

		final long pair = pair(angry, other);
		if (now < _pairNext.getOrDefault(pair, 0L))
		{
			return false;
		}

		final String refusal = refusal(angry, other);
		if (refusal != null)
		{
			return false;
		}

		_pairNext.put(pair, now + (PhantomHuntersConfig.HUNTER_BRAWL_COOLDOWN * 1000L));
		final boolean yes = Rnd.get(100) < PhantomHuntersConfig.HUNTER_BRAWL_CHANCE;
		LOGGER.info(getClass().getSimpleName() + ": " + angry.getName() + " -> " + other.getName() + " (обида " + String.format(Locale.ROOT, "%.1f", grudge) + ", " + why + ") — " + (yes ? "махач." : "стерпел."));
		if (yes)
		{
			PhantomHunterManager.getInstance().startBrawl(angry, other, why);
		}
		return yes;
	}

	/**
	 * Оценка: почему сейчас не драться (или {@code null} — можно). Главное — второй должен ответить, а не умереть белым.
	 */
	private static String refusal(Player angry, Player other)
	{
		if (PhantomHuntersConfig.HUNTER_BRAWL_WITNESS && (World.getFirstVisibleObject(angry, Player.class, p -> !PhantomManager.getInstance().isPhantom(p) && !p.isInvisible() && (angry.calculateDistance2D(p) < WITNESS)) == null))
		{
			return "нет зрителя";
		}
		if (!PhantomHunterManager.getInstance().isHunting(angry) || !PhantomHunterManager.getInstance().isHunting(other))
		{
			return "кто-то не на каче";
		}
		if (angry.isInParty() && (angry.getParty() == other.getParty()))
		{
			return "в одной пати";
		}
		if (angry.isInsideZone(ZoneId.PEACE) || other.isInsideZone(ZoneId.PEACE) || (PhantomCompat.karma(angry) > 0) || (PhantomCompat.karma(other) > 0))
		{
			return "мирная зона или карма";
		}
		if ((angry.getCurrentHp() < (angry.getMaxHp() * 0.7)) || (other.getCurrentHp() < (other.getMaxHp() * 0.5)))
		{
			return "мало HP";
		}
		if (Math.abs(angry.getLevel() - other.getLevel()) > PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP)
		{
			return "разные весовые категории";
		}
		return PhantomHunterManager.getInstance().mood(angry) == PhantomMood.TIRED ? "устал" : null;
	}

	private static long pair(Player a, Player b)
	{
		final long low = Math.min(a.getObjectId(), b.getObjectId());
		final long high = Math.max(a.getObjectId(), b.getObjectId());
		return (low << 32) | (high & 0xffffffffL);
	}

	public static PhantomBrawl getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomBrawl INSTANCE = new PhantomBrawl();
	}
}
