package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.sql.CharSummonTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: охотники-призыватели (мозги под класс, docs/PHANTOMS.md, раздел 4.37). Призыв слуги учат Human, Elven и Dark Wizard (с 20-го уровня), Warlock, Elemental и
 * Phantom Summoner, Necromancer, Artisan и Warsmith — у охотников это нюкеры и гномы, слуга им — танк и второй ствол.<br>
 * На каче: слуги нет или он погиб — призвать (вне боя, хватает кристаллов и маны; не чаще раза в {@value #RETRY} мс); слуга бьёт цель хозяина, пока тот в бою;
 * слуге мало HP или MP — Servitor Heal или Servitor Recharge. Кубики (рыцари Шилен и Евы, призыватели) — держать свои, перекастовывать к концу их 15 минут.
 * Кристаллы на призыв (Spirit Ore, кристаллы грейда — что берёт скилл) охотник покупает в городе вместе с сосами ({@link PhantomHunterOutfit}).
 */
final class PhantomSummoner
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSummoner.class.getName());

	/**
	 * Призывы по убыванию ценности: третья профа, вторая, первая; из пары Wizard — боец, а не бафер (Kat раньше Mew, Boxer раньше Mirage, Shadow раньше Silhouette).
	 * Осадных големов и пушек Warsmith нет: они для осад.
	 */
	private static final int[] SUMMONS =
	{
		1406, // Summon Feline King
		1407, // Summon Magnus the Unicorn
		1408, // Summon Spectral Lord
		1331, // Summon Feline Queen
		1332, // Summon Seraphim the Unicorn
		1333, // Summon Nightshade
		1334, // Summon Cursed Man
		1276, // Summon Kai the Cat
		1277, // Summon Merrow the Unicorn
		1278, // Summon Soulless
		1129, // Summon Reanimated Man
		1154, // Summon Corrupted Man
		1111, // Summon Kat the Cat
		1226, // Summon Boxer the Unicorn
		1128, // Summon Shadow
		1225, // Summon Mew the Cat
		1227, // Summon Mirage the Unicorn
		1228, // Summon Silhouette
		283, // Summon Dark Panther
		25 // Summon Mechanic Golem
	};
	/** Призыв не удался (нет кристаллов, маны, откат) — следующая попытка не раньше. */
	private static final long RETRY = 30000;
	/** Моб в этом радиусе бьёт охотника — не призывать: каст сорвут. */
	private static final int UNDER_ATTACK_RANGE = 900;
	/** Запас кристаллов на призыв, в призывах: слуга ещё и подъедает их сам, пока призван. */
	static final int CRYSTAL_STOCK = 30;

	private static final Map<Integer, Long> NEXT_TRY = new ConcurrentHashMap<>();
	/** У кого слуга уже был: призыв после этого — перепризыв (погиб, отозван, кончились кристаллы). */
	private static final Set<Integer> HAD = ConcurrentHashMap.newKeySet();
	/** Строка лога «слуга бьёт цель хозяина» — не чаще раза в столько на охотника (без клиента иначе не видно, работает ли слуга). */
	private static final long ASSIST_NOTE_GAP = 60000;
	private static final Map<Integer, Long> LAST_ASSIST_NOTE = new ConcurrentHashMap<>();

	/** Servitor Heal (Wizard — с 20-го, призыватели — с 40-го) и Servitor Recharge (с 25-го): цель — свой слуга, цель хозяина они не сбивают. */
	private static final int SERVITOR_HEAL = 1127;
	private static final int SERVITOR_RECHARGE = 1126;
	/** Слугу лечит, когда у него HP ниже стольких %, ману даёт — когда MP ниже стольких %. */
	private static final int HEAL_BELOW = 50;
	private static final int RECHARGE_BELOW = 30;
	/** Хил или речардж слуги — не чаще раза в столько на охотника (каст 4 с). */
	private static final long SUPPORT_GAP = 6000;
	private static final Map<Integer, Long> NEXT_SUPPORT = new ConcurrentHashMap<>();
	/** Строка лога «лечит слугу» — не чаще раза в столько на охотника, со счётом кастов за это время. */
	private static final long SUPPORT_NOTE_GAP = 60000;
	private static final Map<Integer, Long> LAST_SUPPORT_NOTE = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> SUPPORT_COUNT = new ConcurrentHashMap<>();
	/** Кубик живёт 15 минут (cubicDuration 900): свой кубик охотник кастует заново раз в столько. */
	private static final long CUBIC_REFRESH = 840000;
	/** Кубиков нет, а последний каст был раньше — умер или кубики кончились: все свои кастуются заново. */
	private static final long CUBIC_RETRY = 30000;
	/** Когда охотник кастовал кубик: ключ — objectId охотника и id скилла. */
	private static final Map<Long, Long> LAST_CUBIC = new ConcurrentHashMap<>();

	private PhantomSummoner()
	{
	}

	/**
	 * @return лучший выученный призыв слуги или {@code null} — не призыватель
	 */
	static Skill summonSkill(Player player)
	{
		for (int id : SUMMONS)
		{
			final Skill skill = player.getKnownSkill(id);
			if (skill != null)
			{
				return skill;
			}
		}
		return null;
	}

	/**
	 * Тик охотника на споте.
	 * @param player охотник
	 * @param now текущее время
	 * @return {@code true} — начал призыв: правила кача на этот тик не нужны
	 */
	static boolean tick(Player player, long now)
	{
		Summon servitor = PhantomCompat.servitor(player);
		// Слугу, сохранённого при выходе (character_summons, RestoreServitorOnReconnect), Mobius возвращает только по первому действию клиента
		// (Player.onActionRequest), а у фантома клиента нет. Пока запись есть, новый призыв запрещён (ConditionPlayerCanSummon) — до 2026-10-24 охотник,
		// ушедший в рестарт со слугой, оставался без него навсегда. Вернуть сохранённого.
		if ((servitor == null) && !player.isDead() && (now >= NEXT_TRY.getOrDefault(player.getObjectId(), 0L)) && CharSummonTable.getInstance().getServitors().containsKey(player.getObjectId()))
		{
			NEXT_TRY.put(player.getObjectId(), now + RETRY);
			CharSummonTable.getInstance().restoreServitor(player);
			servitor = PhantomCompat.servitor(player);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") " + (servitor != null ? "gets back his saved " + servitor.getName() + " (servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%)" : "could not get back his saved servitor") + ".");
		}
		final boolean alive = (servitor != null) && !servitor.isDead();
		if (alive)
		{
			HAD.add(player.getObjectId());
			if (support(player, servitor, now))
			{
				return true;
			}
			assist(player, servitor, now);
		}

		// Не «вне боя» (isInCombat): на споте с AutoPlay охотник из него почти не выходит — прогон H5 2026-10-22, ни одного призыва за 15 минут.
		// Призывает слугу и кубики, когда его никто не бьёт и сам не бьёт и не кастует — между мобами.
		if (player.isDead() || player.isCastingNow() || player.isAttackingNow() || underAttack(player))
		{
			return false;
		}
		if (cubic(player, now))
		{
			return true;
		}
		if (alive || (now < NEXT_TRY.getOrDefault(player.getObjectId(), 0L)))
		{
			return false;
		}
		final Skill skill = summonSkill(player);
		if (skill == null)
		{
			return false;
		}
		NEXT_TRY.put(player.getObjectId(), now + RETRY);
		// Нечем призвать: кристаллы купит в городе; маны мало или откат — в следующий раз.
		if (((skill.getItemConsumeId() > 0) && (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount())) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())))
		{
			return false;
		}
		if (player.isSitting())
		{
			player.standUp();
			return true;
		}
		if (!PhantomCompat.useMagic(player, skill, false, false))
		{
			return false;
		}
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") summons " + skill.getName() + (HAD.contains(player.getObjectId()) ? " again (the servitor is gone)" : "") + ".");
		return true;
	}

	/**
	 * Лечит слугу (HP ниже {@value #HEAL_BELOW} %) или даёт ему ману (MP ниже {@value #RECHARGE_BELOW} %) — и в бою: слуга держит мобов на себе. Сидя не встаёт: сидит,
	 * когда у самого мало маны.
	 * @return {@code true} — начал каст
	 */
	private static boolean support(Player player, Summon servitor, long now)
	{
		if (player.isDead() || player.isSitting() || player.isCastingNow() || (now < NEXT_SUPPORT.getOrDefault(player.getObjectId(), 0L)))
		{
			return false;
		}
		final int hp = (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp()));
		final int mp = servitor.getMaxMp() > 0 ? (int) ((servitor.getCurrentMp() * 100) / servitor.getMaxMp()) : 100;
		final Skill heal = hp < HEAL_BELOW ? player.getKnownSkill(SERVITOR_HEAL) : null;
		final Skill skill = heal != null ? heal : mp < RECHARGE_BELOW ? player.getKnownSkill(SERVITOR_RECHARGE) : null;
		if ((skill == null) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())) || ((skill.getCastRange() > 0) && (player.calculateDistance2D(servitor) > skill.getCastRange())))
		{
			return false;
		}
		NEXT_SUPPORT.put(player.getObjectId(), now + SUPPORT_GAP);
		if (!PhantomCompat.useMagic(player, skill, false, false))
		{
			return false;
		}
		final int count = SUPPORT_COUNT.merge(player.getObjectId(), 1, Integer::sum);
		final Long last = LAST_SUPPORT_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= SUPPORT_NOTE_GAP))
		{
			LAST_SUPPORT_NOTE.put(player.getObjectId(), now);
			SUPPORT_COUNT.put(player.getObjectId(), 0);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " casts " + skill.getName() + " on his " + servitor.getName() + " (servitor HP " + hp + "%, MP " + mp + "%" + (count > 1 ? ", " + count + " casts since the last note" : "") + ").");
		}
		return true;
	}

	/**
	 * Кубики (Shillien и Temple Knight, Warlock, Elemental и Phantom Summoner — с 40-го, Smart Cubic третьей профы — с 80-го): держит свои, каст — между мобами.
	 * Каждый свой — заново раз в {@value #CUBIC_REFRESH} мс, к концу его 15 минут; кубиков нет, а последний каст был раньше {@value #CUBIC_RETRY} мс — все заново.
	 * Выучил больше, чем держит Cubic Mastery, — новый вытесняет старший, и они сменяются раз в те же 14 минут.
	 * @return {@code true} — начал каст
	 */
	private static boolean cubic(Player player, long now)
	{
		final List<Skill> cubics = new ArrayList<>();
		long latest = 0;
		for (Skill skill : player.getAllSkills())
		{
			// Свой кубик — «Summon … Cubic»; «Mass Summon Storm Cubic» Warlock'а — на пати, его не держим.
			if (skill.isActive() && skill.getName().startsWith("Summon ") && skill.getName().endsWith(" Cubic"))
			{
				cubics.add(skill);
				latest = Math.max(latest, LAST_CUBIC.getOrDefault(cubicKey(player, skill), 0L));
			}
		}
		if (cubics.isEmpty() || player.isSitting())
		{
			return false;
		}
		final boolean gone = (latest > 0) && player.getCubics().isEmpty() && ((now - latest) >= CUBIC_RETRY);
		if (gone)
		{
			for (Skill skill : cubics)
			{
				LAST_CUBIC.remove(cubicKey(player, skill));
			}
		}
		Skill pick = null;
		long pickLast = Long.MAX_VALUE;
		for (Skill skill : cubics)
		{
			final long last = LAST_CUBIC.getOrDefault(cubicKey(player, skill), 0L);
			if (((now - last) >= CUBIC_REFRESH) && (last < pickLast) && !player.isSkillDisabled(skill) && (player.getCurrentMp() >= (skill.getMpConsume() + skill.getMpInitialConsume())))
			{
				pick = skill;
				pickLast = last;
			}
		}
		if (pick == null)
		{
			return false;
		}
		LAST_CUBIC.put(cubicKey(player, pick), now);
		if (!PhantomCompat.useMagic(player, pick, false, false))
		{
			return false;
		}
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") summons " + pick.getName() + (gone ? " again (the cubics are gone)" : pickLast > 0 ? " again (refresh)" : "") + ".");
		return true;
	}

	private static long cubicKey(Player player, Skill skill)
	{
		return (((long) player.getObjectId()) << 32) | skill.getId();
	}

	/**
	 * @return его бьёт моб (цель моба — он) рядом: каст призыва сорвут
	 */
	private static boolean underAttack(Player player)
	{
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, UNDER_ATTACK_RANGE))
		{
			if (!monster.isAlikeDead() && (PhantomHunterOutfit.victimOf(monster) == player))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Слуга бьёт цель хозяина, пока хозяин в бою; иначе — как у Mobius (идёт следом, защищает).
	 */
	private static void assist(Player player, Summon servitor, long now)
	{
		final WorldObject target = player.getTarget();
		if (!(target instanceof Monster) || ((Monster) target).isAlikeDead() || !(player.isInCombat() || player.isAttackingNow() || player.isCastingNow()))
		{
			return;
		}
		final Monster monster = (Monster) target;
		if ((servitor.getTarget() == monster) && (servitor.getAI().getIntention() == Intention.ATTACK))
		{
			return;
		}
		servitor.setTarget(monster);
		servitor.getAI().setIntentionAttack(monster);
		final Long last = LAST_ASSIST_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= ASSIST_NOTE_GAP))
		{
			LAST_ASSIST_NOTE.put(player.getObjectId(), now);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + "'s " + servitor.getName() + " attacks " + monster.getName() + " (" + (int) servitor.calculateDistance2D(monster) + ", servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%).");
		}
	}
}
