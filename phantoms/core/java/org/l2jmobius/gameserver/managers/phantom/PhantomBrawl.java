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
	/** Война кланов ({@link PhantomClanWars}): та же пара охотников лезет в драку не чаще, мс. */
	private static final long WAR_PAIR_GAP = 600000;
	/** Война кланов: шанс полезть в драку при встрече, %. */
	private static final int WAR_CHANCE = 50;

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

		final String refusal = refusal(angry, other, PhantomHuntersConfig.HUNTER_BRAWL_WITNESS);
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
			PhantomStories.brawl(angry, other);
		}
		return yes;
	}

	/**
	 * Война кланов ({@link PhantomClanWars}): охотники враждующих кланов встретились. Как махач, но повод — война, а не обида: оценка та же
	 * ({@link #refusal}), зритель нужен, только если {@code HunterClanWarWitness}; та же пара — не чаще {@value #WAR_PAIR_GAP} мс, шанс {@value #WAR_CHANCE} %.
	 * @param angry кто нападает
	 * @param other на кого
	 * @param enemyClan клан врага — для угрозы вслух
	 * @param now текущее время
	 * @return драка началась
	 */
	public boolean warProvoked(Player angry, Player other, String enemyClan, long now)
	{
		if (!PhantomRelations.isHunter(angry) || !PhantomRelations.isHunter(other))
		{
			return false;
		}
		final long pair = pair(angry, other);
		if (now < _pairNext.getOrDefault(pair, 0L))
		{
			return false;
		}
		final String refusal = refusal(angry, other, PhantomHuntersConfig.HUNTER_CLAN_WAR_WITNESS);
		if (refusal != null)
		{
			return false;
		}

		_pairNext.put(pair, now + WAR_PAIR_GAP);
		final boolean yes = Rnd.get(100) < WAR_CHANCE;
		LOGGER.info(getClass().getSimpleName() + ": " + angry.getName() + " -> " + other.getName() + " (война с кланом " + enemyClan + ") — " + (yes ? "в бой." : "разошлись."));
		if (yes)
		{
			PhantomHunterManager.getInstance().startWarFight(angry, other, enemyClan);
		}
		return yes;
	}

	/**
	 * Война с кланом игрока ({@link PhantomClanWars}, этап 4): охотник встретил живого из клана, с которым воюет его клан. Оценка — своя
	 * ({@link #livingRefusal}): зритель не нужен — игрок сам здесь; та же пара — не чаще {@value #WAR_PAIR_GAP} мс, шанс {@value #WAR_CHANCE} %.
	 * Сколько охотников разом на одного — считает {@link PhantomClanWars}.
	 * @param angry охотник
	 * @param other живой игрок из враждующего клана
	 * @param enemyClan его клан — для угрозы вслух
	 * @param now текущее время
	 * @param sure без броска (самопроверка)
	 * @return драка началась
	 */
	public boolean warOnLiving(Player angry, Player other, String enemyClan, long now, boolean sure)
	{
		if (!PhantomRelations.isHunter(angry))
		{
			return false;
		}
		final long pair = pair(angry, other);
		if (now < _pairNext.getOrDefault(pair, 0L))
		{
			return false;
		}
		if (livingRefusal(angry, other) != null)
		{
			return false;
		}

		_pairNext.put(pair, now + WAR_PAIR_GAP);
		final boolean yes = sure || (Rnd.get(100) < WAR_CHANCE);
		LOGGER.info(getClass().getSimpleName() + ": " + angry.getName() + " -> " + other.getName() + " (война с кланом игрока " + enemyClan + ") — " + (yes ? "в бой." : "разошлись."));
		if (yes)
		{
			PhantomHunterManager.getInstance().startWarFight(angry, other, enemyClan);
		}
		return yes;
	}

	/**
	 * Оценка для войны с кланом игрока: почему охотнику сейчас не нападать на живого врага (или {@code null} — можно). Как {@link #refusal}, но
	 * «на каче» — только охотник, а живой не занят: не в олимпиаде, ивенте и торговле; бить его без флага даёт Mobius (не академия, не «хочет мира»).
	 */
	static String livingRefusal(Player angry, Player other)
	{
		if (!PhantomHunterManager.getInstance().isHunting(angry))
		{
			return "охотник не на каче";
		}
		if (other.isDead() || other.isInvisible() || other.isInOlympiadMode() || other.isOnEvent() || other.isInStoreMode())
		{
			return "игрок занят";
		}
		if (angry.isInParty() && (angry.getParty() == other.getParty()))
		{
			return "в одной пати";
		}
		if (angry.isInsideZone(ZoneId.PEACE) || other.isInsideZone(ZoneId.PEACE) || (PhantomCompat.karma(angry) > 0))
		{
			return "мирная зона или карма";
		}
		if (!other.isAutoAttackable(angry))
		{
			return "Mobius не даёт бить без флага";
		}
		if (angry.getCurrentHp() < (angry.getMaxHp() * 0.7))
		{
			return "мало HP";
		}
		if (Math.abs(angry.getLevel() - other.getLevel()) > PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP)
		{
			return "разные весовые категории";
		}
		return PhantomHunterManager.getInstance().mood(angry) == PhantomMood.TIRED ? "устал" : null;
	}

	/**
	 * Оценка: почему сейчас не драться (или {@code null} — можно). Главное — второй должен ответить, а не умереть белым.
	 * @param witness нужен живой зритель рядом
	 */
	static String refusal(Player angry, Player other, boolean witness)
	{
		if (witness && (World.getFirstVisibleObject(angry, Player.class, p -> !PhantomManager.getInstance().isPhantom(p) && !p.isInvisible() && (angry.calculateDistance2D(p) < WITNESS)) == null))
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

	/**
	 * @return сколько ещё мс эта пара не дерётся (самопроверка: почему драка не началась)
	 */
	long pairWait(Player a, Player b, long now)
	{
		return Math.max(0, _pairNext.getOrDefault(pair(a, b), 0L) - now);
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
