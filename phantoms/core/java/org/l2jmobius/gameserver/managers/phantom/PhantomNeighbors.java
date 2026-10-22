package org.l2jmobius.gameserver.managers.phantom;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.managers.phantom.PhantomRelations.Kind;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: соседи-охотники (патч 0033, docs/PHANTOMS.md, раздел 4.17).<br>
 * <ul>
 * <li>Охотник-хилер (Alenka, Nyusha, Ded) на споте ресает мёртвого охотника рядом и хилит раненого (меньше 40 % HP в бою) — настоящим кастом: благодарность засчитывают слушатели {@link PhantomRelations}.
 * Врагам не помогает. Пока помогает, AutoPlay выключен — иначе он перебьёт каст своей целью; кончил — тик охотника включает его снова.</li>
 * <li>«Отжал моба»: охотник бил моба первым, а кто-то (охотник или живой игрок) стал бить того же моба позже — обида +1 ({@link Kind#CONTESTED}, не чаще раза в 30 минут на пару).
 * Живому игроку охотник ещё и скажет об этом вслух.</li>
 * </ul>
 */
public class PhantomNeighbors
{
	private static final Logger LOGGER = Logger.getLogger(PhantomNeighbors.class.getName());

	/** Одиночные хилы, сильные первыми: Major Heal, Greater Heal, Greater Battle Heal, Battle Heal, Heal. */
	private static final int[] HEALS =
	{
		1401,
		1217,
		1218,
		1015,
		1011
	};
	static final int RESURRECTION = 1016;
	/** Хилер замечает соседа не дальше этого. */
	private static final int HELP_RANGE = 900;
	private static final double INJURED = 0.4;
	/** Хилер помогает не чаще. */
	private static final long HELP_GAP = 5000;
	/** Помощь (подход и каст) — не дольше этого, потом снова свой кач. */
	private static final long HELP_TIMEOUT = 15000;
	/** Кто бьёт моба охотника — смотрим не дальше этого. */
	private static final int CONTEST_RANGE = 1200;
	/** Отжал — если начал бить моба хотя бы на столько позже охотника. */
	private static final long CONTEST_LEAD = 3000;
	private static final long CONTEST_SAY_COOLDOWN = 600000;
	/** Тема для тишины в округе ({@link PhantomQuiet}). */
	private static final String CONTEST_TOPIC = "contested";

	/** Хилер → не помогать до. */
	private final Map<Integer, Long> _nextHelp = new ConcurrentHashMap<>();
	/** Хилер → помогает до (AutoPlay выключен). */
	private final Map<Integer, Long> _helping = new ConcurrentHashMap<>();
	/** Охотник → {моб, с какого времени охотник его бьёт}. */
	private final Map<Integer, long[]> _targets = new ConcurrentHashMap<>();
	/** Охотник → кто ещё бьёт его моба → когда замечен впервые. */
	private final Map<Integer, Map<Integer, Long>> _seen = new ConcurrentHashMap<>();
	/** Пара (обиженный, отжавший) → не говорить вслух до (патч 0037). */
	private final Map<Long, Long> _sayNext = new ConcurrentHashMap<>();

	protected PhantomNeighbors()
	{
	}

	/**
	 * Тик охотника на споте (фаза HUNT, раз в 2 с, из {@link PhantomHunterManager}).
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 * @return {@code true}, пока хилер помогает соседу: остальной тик (AutoPlay, поводок, отдых) ждёт
	 */
	public boolean tick(Player hunter, PhantomTemplate template, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_NEIGHBORS || hunter.isDead())
		{
			_helping.remove(hunter.getObjectId());
			return false;
		}

		contest(hunter, now);
		return (template.getRole() == PhantomTemplate.Role.HEALER) && help(hunter, now);
	}

	/**
	 * @param healer охотник
	 * @param dead мёртвый охотник
	 * @return этот охотник может реснуть того: хилер с ресом, живой, не враг — мёртвому стоит подождать
	 */
	boolean canRevive(Player healer, Player dead)
	{
		return PhantomHuntersConfig.HUNTER_NEIGHBORS && !healer.isDead() && PhantomRelations.isHunter(healer) && (healer.getKnownSkill(RESURRECTION) != null) && !PhantomRelations.getInstance().isEnemy(healer, dead);
	}

	// ---------- Хилер помогает ----------

	private boolean help(Player healer, long now)
	{
		final int id = healer.getObjectId();
		final long until = _helping.getOrDefault(id, 0L);
		if (until > 0)
		{
			// Идёт к соседу или кастует — ждать; кончил или вышло время — снова свой кач.
			if ((now < until) && (healer.isCastingNow() || healer.isMoving()))
			{
				return true;
			}
			_helping.remove(id);
			return false;
		}

		if ((now < _nextHelp.getOrDefault(id, 0L)) || healer.isCastingNow() || (healer.getCurrentHp() < (healer.getMaxHp() * 0.5)))
		{
			return false;
		}

		Player dead = null;
		Player injured = null;
		double worst = INJURED;
		for (Player other : World.getVisibleObjectsInRange(healer, Player.class, HELP_RANGE))
		{
			if ((other == healer) || !PhantomRelations.isHunter(other) || PhantomRelations.getInstance().isEnemy(healer, other))
			{
				continue;
			}

			if (other.isDead())
			{
				if ((dead == null) && !other.isReviveRequested() && (now >= PhantomRelations.getInstance().reviveCastUntil(other)))
				{
					dead = other;
				}
				continue;
			}

			final double hp = other.getCurrentHp() / Math.max(1, other.getMaxHp());
			if (other.isInCombat() && (hp < worst))
			{
				worst = hp;
				injured = other;
			}
		}

		Skill skill = null;
		Player target = null;
		if (dead != null)
		{
			skill = usable(healer, RESURRECTION);
			target = dead;
		}
		if ((skill == null) && (injured != null))
		{
			for (int skillId : HEALS)
			{
				skill = usable(healer, skillId);
				if (skill != null)
				{
					break;
				}
			}
			target = injured;
		}
		if (skill == null)
		{
			return false;
		}

		_nextHelp.put(id, now + HELP_GAP);
		if (healer.isSitting())
		{
			healer.standUp();
			return true;
		}

		AutoPlayTaskManager.getInstance().stopAutoPlay(healer);
		healer.setTarget(target);
		if (!PhantomCompat.useMagic(healer, skill, false, false))
		{
			return false;
		}

		_helping.put(id, now + HELP_TIMEOUT);
		LOGGER.info(getClass().getSimpleName() + ": " + healer.getName() + " " + (target.isDead() ? "ресает" : "хилит") + " " + target.getName() + " (" + skill.getName() + ").");
		return true;
	}

	/**
	 * @return выученный скилл, который можно кастовать сейчас (не на откате, хватает MP), или {@code null}
	 */
	private static Skill usable(Player player, int skillId)
	{
		final Skill skill = player.getKnownSkill(skillId);
		if ((skill == null) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())))
		{
			return null;
		}
		return skill;
	}

	// ---------- Отжал моба ----------

	private void contest(Player hunter, long now)
	{
		final int id = hunter.getObjectId();
		final WorldObject target = hunter.getTarget();
		if (!(target instanceof Monster) || ((Monster) target).isDead())
		{
			_targets.remove(id);
			_seen.remove(id);
			return;
		}

		long[] mine = _targets.get(id);
		if ((mine == null) || (mine[0] != target.getObjectId()))
		{
			mine = new long[]
			{
				target.getObjectId(),
				now
			};
			_targets.put(id, mine);
			_seen.remove(id);
			return;
		}

		final Map<Integer, Long> seen = _seen.computeIfAbsent(id, k -> new ConcurrentHashMap<>());
		for (Player other : World.getVisibleObjectsInRange(hunter, Player.class, CONTEST_RANGE))
		{
			if ((other == hunter) || other.isDead() || (other.getTarget() != target) || (!other.isAttackingNow() && !other.isCastingNow()))
			{
				continue;
			}
			// Свои по пати и напарники из .buddy — не в счёт.
			if ((hunter.isInParty() && (hunter.getParty() == other.getParty())) || (PhantomManager.getInstance().isPhantom(other) && !PhantomRelations.isHunter(other)))
			{
				continue;
			}

			// Кто взял моба позже. У охотника — по его собственной отметке (иначе двое, взявшие моба почти разом, засчитали бы друг друга:
			// тики разъезжаются); у живого игрока — когда охотник впервые увидел его на своём мобе.
			final boolean otherHunter = PhantomRelations.isHunter(other);
			final long[] theirs = otherHunter ? _targets.get(other.getObjectId()) : null;
			if (otherHunter && ((theirs == null) || (theirs[0] != target.getObjectId())))
			{
				continue; // сосед только переключился на этого моба — его отметка появится на его тике
			}
			final long first = otherHunter ? theirs[1] : seen.computeIfAbsent(other.getObjectId(), k -> now);
			if (first < (mine[1] + CONTEST_LEAD))
			{
				continue;
			}

			PhantomRelations.getInstance().record(hunter, other, Kind.CONTESTED);
			if (!otherHunter)
			{
				PhantomChat.getInstance().hunterEvent(hunter, other, "CONTESTED", other.getName() + " бьёт моба (" + target.getName() + "), которого ты бил первым, — отжимает его у тебя", "эй, это мой моб!", CONTEST_SAY_COOLDOWN);
				continue;
			}

			// Охотник отжал у охотника (патч 0037): последняя капля — махач ({@link PhantomBrawl}); нет — предъява, раз в 10 минут на пару.
			if (PhantomBrawl.getInstance().provoked(hunter, other, "он опять отжал у тебя моба (" + target.getName() + ")", now))
			{
				continue;
			}
			final long pair = ((long) id << 32) | (other.getObjectId() & 0xffffffffL);
			if (now >= _sayNext.getOrDefault(pair, 0L))
			{
				_sayNext.put(pair, now + CONTEST_SAY_COOLDOWN);
				warn(hunter, other, target.getName(), now);
			}
		}
	}

	/**
	 * Предъява за моба и ответ (патч 0037, решение автора): отжавший по броску × вспыльчивость либо «сорян» и уходит на другой спот,
	 * либо «пнх» — ещё минус очко, и тогда может начаться махач. Решает сервер всегда; вслух — только при живом зрителе и если вокруг тихо.
	 */
	private void warn(Player victim, Player taker, String mob, long now)
	{
		final PhantomTemplate victimTemplate = PhantomData.getInstance().getTemplate(victim.getName());
		final PhantomTemplate takerTemplate = PhantomData.getInstance().getTemplate(taker.getName());
		if ((victimTemplate == null) || (takerTemplate == null))
		{
			return;
		}

		// Бросок × вспыльчивость; врагу хамят охотнее, другу — реже.
		final PhantomRelations relations = PhantomRelations.getInstance();
		final double attitude = relations.isEnemy(taker, victim) ? 1.5 : relations.isFriend(taker, victim) ? 0.5 : 1;
		final double chance = Math.min(95, PhantomHuntersConfig.HUNTER_CONTEST_RUDE_CHANCE * takerTemplate.getTemper() * attitude);
		final boolean rude = Rnd.get(100.0) < chance;
		LOGGER.info(getClass().getSimpleName() + ": " + victim.getName() + " предъявляет " + taker.getName() + " за моба; шанс «пнх» " + String.format(Locale.ROOT, "%.0f", chance) + "% — " + (rude ? "пнх." : "сорян."));

		final Runnable after = () ->
		{
			if (rude)
			{
				relations.record(victim, taker, Kind.INSULTED);
				PhantomBrawl.getInstance().provoked(victim, taker, "он отжал у тебя моба и ещё послал", System.currentTimeMillis());
			}
			else
			{
				relations.record(victim, taker, Kind.APOLOGIZED);
				PhantomHunterManager.getInstance().yieldSpot(taker);
			}
		};

		if (PhantomBanter.audience(victim) && PhantomQuiet.getInstance().allowed(victim, CONTEST_TOPIC, now))
		{
			PhantomChat.getInstance().warn(victim, taker, victimTemplate, PhantomHunterManager.describe(taker) + " — такой же охотник, как ты, — бьёт моба (" + mob + "), которого ты бил первым: отжимает его у тебя. Предъяви ему это вслух", rude, after);
		}
		else
		{
			after.run();
		}
	}

	public static PhantomNeighbors getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomNeighbors INSTANCE = new PhantomNeighbors();
	}
}
