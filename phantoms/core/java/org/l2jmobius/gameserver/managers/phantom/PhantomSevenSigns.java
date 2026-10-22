package org.l2jmobius.gameserver.managers.phantom;

import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: охотники и Семь печатей (docs/PHANTOMS.md, раздел 4.42; {@code HunterSevenSigns} в PhantomHunters.ini). У Mobius за Рассвет и Закат голосуют сотни
 * игроков; в малом мире без голосов печати ни у кого, и Маммон (купец — при Печати Алчности у победителя, кузнец — при Гнозисе) не приходит.
 * <ul>
 * <li>вступление — в неделю соревнования, в городе «у жреца» ({@link #townVisit}), с {@code HunterSevenSignsLevel} (20): друг живого игрока со стороной — чаще
 * за его сторону, враг — против; иначе по характеру (горячие — к бунтарям Заката, спокойные — к лордам Рассвета) с поправкой на перекос сторон.
 * Рассвет после второй профы — за адену, как у жреца ({@link PhantomCompat#sevenSignsJoin}). Голос — за печать, которой у стороны не хватает до долей
 * {@link #SEAL_SHARE} (Алчность и Гнозис по 45 %: новую печать дают от 35 % голосов стороны; при 40/40/20 на семи голосах Раздор брал третий,
 * и Алчность выходила 29 % — прогон Epilogue 2026-10-22);</li>
 * <li>печатные камни — у Mobius их роняют только мобы катакомб и некрополей, а охотники там не качаются: охотник со стороной в неделю соревнования
 * находит их на своём споте — шанс {@code HunterSevenSignsStoneChance} % с убитого моба, цвет по уровню моба; сдаёт в городе ({@link PhantomCompat#sevenSignsContribute});</li>
 * <li>итог — смена периода в «Летопись мира» ({@link PhantomNews}): начало соревнования, победитель и печати в неделю проверки, придёт ли Маммон.</li>
 * </ul>
 * Фестиваль тьмы и катакомбы — не здесь. У хроник без Семи печатей (Classic, Essence) {@link PhantomCompat#sevenSigns} — {@code false}, и всё молчит.
 */
public final class PhantomSevenSigns
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSevenSigns.class.getName());

	private static final int CABAL_DUSK = 1;
	private static final int CABAL_DAWN = 2;
	private static final int SEAL_AVARICE = 1;
	private static final int SEAL_GNOSIS = 2;
	private static final int SEAL_STRIFE = 3;
	private static final int PERIOD_COMPETITION = 1;
	private static final int PERIOD_SEAL_VALIDATION = 3;
	/** Печатные камни: синий, зелёный, красный. */
	private static final int STONE_BLUE = 6360;
	private static final int STONE_GREEN = 6361;
	private static final int STONE_RED = 6362;

	/** Доли голосов стороны за печать: —, Алчность, Гнозис, Раздор. Маммону нужны Алчность (купец) и Гнозис (кузнец) у победителя. */
	private static final double[] SEAL_SHARE =
	{
		0,
		0.45,
		0.45,
		0.1
	};
	/** Друг живого игрока идёт за его сторону, %; враг — против, %. */
	private static final int FRIEND_FOLLOW = 80;
	private static final int ENEMY_OPPOSE = 70;
	/** Перекос сторон: на каждого лишнего члена — столько вероятности в пользу другой стороны. */
	private static final double BALANCE_STEP = 0.05;
	/** Сообщённый в летопись период — цикл × 10 + период (переживает перезапуск). */
	private static final String REPORTED_VARIABLE = "L2HubSevenSignsReported";
	private static final long CONTROL_GAP = 60000;

	private boolean _started;
	private long _lastControl;

	private PhantomSevenSigns()
	{
	}

	private static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_SEVEN_SIGNS && PhantomCompat.sevenSigns();
	}

	/**
	 * Из {@code PhantomHunterManager.control}: слушатель убийств (камни) и смена периода — в летопись.
	 * @param now текущее время
	 */
	synchronized void control(long now)
	{
		if (!enabled() || ((now - _lastControl) < CONTROL_GAP))
		{
			return;
		}
		_lastControl = now;
		if (!_started)
		{
			_started = true;
			PhantomCompat.addAttackableKillListener(this, this::onKill);
		}

		final int period = PhantomCompat.sevenSignsPeriod();
		final int cycle = PhantomCompat.sevenSignsCycle();
		final int key = (cycle * 10) + period;
		final int reported = GlobalVariablesManager.getInstance().getInt(REPORTED_VARIABLE, 0);
		if (key == reported)
		{
			return;
		}
		GlobalVariablesManager.getInstance().set(REPORTED_VARIABLE, key);
		GlobalVariablesManager.getInstance().storeMe();
		if (period == PERIOD_COMPETITION)
		{
			PhantomNews.post("sevensigns", "Семь печатей, цикл " + cycle + ": неделя соревнования — жрецы Рассвета и Заката набирают сторонников, в городах сдают печатные камни", null, null);
		}
		else if (period == PERIOD_SEAL_VALIDATION)
		{
			PhantomNews.post("sevensigns", result(cycle), null, null);
		}
		LOGGER.info(getClass().getSimpleName() + ": cycle " + cycle + ", period " + period + " (Dawn " + PhantomCompat.sevenSignsMembers(CABAL_DAWN) + ", Dusk " + PhantomCompat.sevenSignsMembers(CABAL_DUSK) + ").");
	}

	/**
	 * «Семь печатей, цикл 3: победил Рассвет; Печать Алчности — Рассвет, Гнозиса — Закат, Раздора — ничья. Маммон: купец придёт, кузнеца не будет».
	 */
	private static String result(int cycle)
	{
		final int winner = PhantomCompat.sevenSignsWinner();
		final StringBuilder sb = new StringBuilder("Семь печатей, цикл ").append(cycle).append(": ");
		if (winner == 0)
		{
			return sb.append("ничья — печатей никто не сдал, Маммон не придёт").toString();
		}
		sb.append("победил ").append(cabalName(winner)).append("; Печать Алчности — ").append(owner(SEAL_AVARICE)).append(", Гнозиса — ").append(owner(SEAL_GNOSIS)).append(", Раздора — ").append(owner(SEAL_STRIFE));
		final boolean merchant = PhantomCompat.sevenSignsSealOwner(SEAL_AVARICE) == winner;
		final boolean smith = PhantomCompat.sevenSignsSealOwner(SEAL_GNOSIS) == winner;
		return sb.append(". Маммон: купец ").append(merchant ? "придёт" : "не придёт").append(", кузнец ").append(smith ? "придёт" : "не придёт").toString();
	}

	private static String owner(int seal)
	{
		final int cabal = PhantomCompat.sevenSignsSealOwner(seal);
		return cabal == 0 ? "ничья" : cabalName(cabal);
	}

	private static String cabalName(int cabal)
	{
		return cabal == CABAL_DAWN ? "Рассвет" : "Закат";
	}

	private static String sealName(int seal)
	{
		return seal == SEAL_AVARICE ? "Печать Алчности" : seal == SEAL_GNOSIS ? "Печать Гнозиса" : "Печать Раздора";
	}

	/**
	 * Охотник в городе (из {@code PhantomHunterOutfit.townChores}): в неделю соревнования — вступить или сдать найденные камни.
	 * @param done что сделал — для лога
	 */
	void townVisit(Player player, PhantomTemplate template, List<String> done)
	{
		if (!enabled() || (player.getLevel() < PhantomHuntersConfig.HUNTER_SEVEN_SIGNS_LEVEL) || (PhantomCompat.sevenSignsPeriod() != PERIOD_COMPETITION))
		{
			return;
		}
		if (PhantomCompat.sevenSignsCabal(player) == 0)
		{
			join(player, template, done);
			return;
		}
		final long score = PhantomCompat.sevenSignsContribute(player);
		if (score > 0)
		{
			done.add("сдал печати жрецу " + (PhantomCompat.sevenSignsCabal(player) == CABAL_DAWN ? "Рассвета" : "Заката") + " (вклад " + score + ")");
		}
		else if (score < 0)
		{
			done.add("печати не сдал — вклад на пределе");
		}
	}

	private synchronized void join(Player player, PhantomTemplate template, List<String> done)
	{
		final StringBuilder why = new StringBuilder();
		int cabal = chooseCabal(player, template, why);
		int seal = chooseSeal(cabal);
		if (!PhantomCompat.sevenSignsJoin(player, cabal, seal))
		{
			// В Рассвет не по карману (или Закат закрыт замком клана) — на другую сторону.
			cabal = cabal == CABAL_DAWN ? CABAL_DUSK : CABAL_DAWN;
			seal = chooseSeal(cabal);
			why.append(", на ту сторону не вышло");
			if (!PhantomCompat.sevenSignsJoin(player, cabal, seal))
			{
				return;
			}
		}
		done.add("вступил в " + cabalName(cabal) + " (" + sealName(seal) + ", " + why + ")");
	}

	/**
	 * Сторона: друг или враг живого игрока со стороной; иначе характер с поправкой на перекос.
	 */
	private static int chooseCabal(Player hunter, PhantomTemplate template, StringBuilder why)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Player player : World.getPlayers())
		{
			if ((player == hunter) || PhantomManager.getInstance().isPhantom(player) || !player.isOnline())
			{
				continue;
			}
			final int cabal = PhantomCompat.sevenSignsCabal(player);
			if (cabal == 0)
			{
				continue;
			}
			if (relations.isFriend(hunter, player) && (Rnd.get(100) < FRIEND_FOLLOW))
			{
				why.append("за друга ").append(player.getName());
				return cabal;
			}
			if (relations.isEnemy(hunter, player) && (Rnd.get(100) < ENEMY_OPPOSE))
			{
				why.append("против врага ").append(player.getName());
				return cabal == CABAL_DAWN ? CABAL_DUSK : CABAL_DAWN;
			}
		}
		final float temper = template != null ? template.getTemper() : 1;
		double dawn = 0.5 + ((1 - temper) * 0.4);
		dawn += (PhantomCompat.sevenSignsMembers(CABAL_DUSK) - PhantomCompat.sevenSignsMembers(CABAL_DAWN)) * BALANCE_STEP;
		dawn = Math.max(0.15, Math.min(0.85, dawn));
		why.append("по характеру, Рассвет ").append((int) (dawn * 100)).append('%');
		return Rnd.get(1.0) < dawn ? CABAL_DAWN : CABAL_DUSK;
	}

	/**
	 * Голос — за печать, которой у стороны больше всего не хватает до своей доли ({@link #SEAL_SHARE}).
	 */
	private static int chooseSeal(int cabal)
	{
		int total = 1;
		for (int seal = SEAL_AVARICE; seal <= SEAL_STRIFE; seal++)
		{
			total += PhantomCompat.sevenSignsVotes(cabal, seal);
		}
		int best = SEAL_AVARICE;
		double bestGap = -Double.MAX_VALUE;
		for (int seal = SEAL_AVARICE; seal <= SEAL_STRIFE; seal++)
		{
			final double gap = (SEAL_SHARE[seal] * total) - PhantomCompat.sevenSignsVotes(cabal, seal) + (Rnd.get(10) / 100.0);
			if (gap > bestGap)
			{
				bestGap = gap;
				best = seal;
			}
		}
		return best;
	}

	/**
	 * Охотник со стороной убил моба в неделю соревнования — с шансом нашёл печатные камни: цвет по уровню моба (до 40 — синие, до 60 — зелёные, выше — красные),
	 * штук — 1 + уровень / 10.
	 */
	private void onKill(Player killer, Attackable target)
	{
		if ((killer == null) || (target == null) || !PhantomHuntersConfig.HUNTER_SEVEN_SIGNS || (PhantomHuntersConfig.HUNTER_SEVEN_SIGNS_STONE_CHANCE <= 0) || !PhantomRelations.isHunter(killer))
		{
			return;
		}
		if ((target.getLevel() < (PhantomHuntersConfig.HUNTER_SEVEN_SIGNS_LEVEL - 5)) || (Rnd.get(100.0) >= PhantomHuntersConfig.HUNTER_SEVEN_SIGNS_STONE_CHANCE) || (PhantomCompat.sevenSignsPeriod() != PERIOD_COMPETITION) || (PhantomCompat.sevenSignsCabal(killer) == 0))
		{
			return;
		}
		final int level = target.getLevel();
		PhantomCompat.addItem(killer, ItemProcessType.LOOT, level < 40 ? STONE_BLUE : level < 60 ? STONE_GREEN : STONE_RED, 1 + (level / 10), target);
	}

	public static PhantomSevenSigns getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomSevenSigns INSTANCE = new PhantomSevenSigns();
	}
}
