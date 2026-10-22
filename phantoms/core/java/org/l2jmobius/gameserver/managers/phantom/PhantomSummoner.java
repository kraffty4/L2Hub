package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: охотники-призыватели (мозги под класс, docs/PHANTOMS.md, раздел 4.37). Призыв слуги учат Human, Elven и Dark Wizard (с 20-го уровня), Warlock, Elemental и
 * Phantom Summoner, Necromancer, Artisan и Warsmith — у охотников это нюкеры и гномы, слуга им — танк и второй ствол.<br>
 * На каче: слуги нет или он погиб — призвать (вне боя, хватает кристаллов и маны; не чаще раза в {@value #RETRY} мс); слуга бьёт цель хозяина, пока тот в бою.
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
		final Summon servitor = PhantomCompat.servitor(player);
		if ((servitor != null) && !servitor.isDead())
		{
			HAD.add(player.getObjectId());
			assist(player, servitor, now);
			return false;
		}

		// Не «вне боя» (isInCombat): на споте с AutoPlay охотник из него почти не выходит — прогон H5 2026-10-22, ни одного призыва за 15 минут.
		// Призывает, когда его никто не бьёт и сам не бьёт и не кастует — между мобами.
		if (player.isDead() || player.isCastingNow() || player.isAttackingNow() || underAttack(player) || (now < NEXT_TRY.getOrDefault(player.getObjectId(), 0L)))
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
