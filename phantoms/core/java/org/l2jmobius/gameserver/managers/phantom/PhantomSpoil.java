package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: спойл и свип у гномов-охотников (ключ {@code HunterSpoil}). Спойлит цель до смерти, свипает свой заспойленный труп.
 */
final class PhantomSpoil
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSpoil.class.getName());

	private static final int SPOIL = 254;
	private static final int SWEEPER = 42;
	/** Спойлить, пока у моба больше этой доли HP: потом поздно, умрёт раньше, чем дойдёт каст. */
	private static final double SPOIL_HP_RATIO = 0.5;
	/** Попыток спойла на одного моба: спойл может не пройти. */
	private static final int SPOIL_TRIES = 2;
	/** Трупы для свипа — в этом радиусе. */
	private static final int SWEEP_RANGE = 400;
	/** Моб выше магического уровня Spoil больше чем на столько — не спойлить: шанс 100 − 1,3^разница, на 10 ещё 86 %, дальше быстро к нулю (Spoil 1 у ремесленника на мобе 60-го не проходит никогда). */
	private static final int SPOIL_LEVEL_GAP = 10;
	/** Строка лога — не чаще раза в столько на охотника (как в PhantomTactics). */
	private static final long NOTE_GAP = 60000;

	/** objectId моба → сколько раз пытались заспойлить. Чистить записи мёртвых и исчезнувших мобов. */
	private static final Map<Integer, Integer> TRIES = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> LAST_NOTE = new ConcurrentHashMap<>();

	private PhantomSpoil()
	{
	}

	/**
	 * Тик охотника в бою: свип своего трупа (важнее — труп исчезнет) или спойл цели.
	 * @return {@code true} — в этот тик кастует (спойл или свип)
	 */
	static boolean tick(Player player, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_SPOIL)
		{
			return false;
		}
		if (player.getKnownSkill(SPOIL) == null)
		{
			return false;
		}
		if (TRIES.size() > 500)
		{
			TRIES.clear();
		}

		// Свип важнее спойла: заспойленный труп скоро исчезнет.
		final Skill sweepSkill = usable(player, SWEEPER);
		if (sweepSkill != null)
		{
			for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, SWEEP_RANGE))
			{
				if (monster.isDead() && canSweep(monster, player))
				{
					final WorldObject previous = player.getTarget();
					player.setTarget(monster);
					if (PhantomCompat.useMagic(player, sweepSkill, false, false))
					{
						note(player, "sweeps " + monster.getName(), now);
						return true;
					}
					player.setTarget(previous); // свип не пошёл — вернуть живую цель, иначе каждый тик уводил бы её на труп
					return false;
				}
			}
		}

		// Спойл цели.
		if (player.getTarget() instanceof Monster)
		{
			final Monster monster = (Monster) player.getTarget();
			if (!monster.isDead() && !monster.isRaid() && !isSpoiled(monster) && (monster.getCurrentHp() > (monster.getMaxHp() * SPOIL_HP_RATIO)))
			{
				final int objectId = monster.getObjectId();
				if (TRIES.getOrDefault(objectId, 0) < SPOIL_TRIES)
				{
					final Skill spoilSkill = usable(player, SPOIL);
					if ((spoilSkill != null) && ((monster.getLevel() - (spoilSkill.getMagicLevel() > 0 ? spoilSkill.getMagicLevel() : player.getLevel())) <= SPOIL_LEVEL_GAP))
					{
						if (PhantomCompat.useMagic(player, spoilSkill, false, false))
						{
							TRIES.put(objectId, TRIES.getOrDefault(objectId, 0) + 1);
							note(player, "spoils " + monster.getName(), now);
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	private static boolean isSpoiled(Monster monster)
	{
		return monster.isSpoiled();
	}

	private static boolean canSweep(Monster monster, Player player)
	{
		return monster.isSpoiled() && (monster.getSpoilerObjectId() == player.getObjectId()) && monster.isSweepActive();
	}

	private static Skill usable(Player player, int skillId)
	{
		final Skill skill = player.getKnownSkill(skillId);
		return (skill == null) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())) ? null : skill;
	}

	private static void note(Player player, String what, long now)
	{
		final Long last = LAST_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= NOTE_GAP))
		{
			LAST_NOTE.put(player.getObjectId(), now);
			LOGGER.info(PhantomSpoil.class.getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}
}
