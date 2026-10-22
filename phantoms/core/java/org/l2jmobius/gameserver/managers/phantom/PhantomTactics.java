package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: тактика охотника по классу в бою (мозги под каждый класс, docs/PHANTOMS.md, раздел 4.38). Поверх AutoPlay, на каче и в пати:
 * <ul>
 * <li>танк в пати держит агро — моба, который бьёт товарища, перетягивает Aggression (28) или Aura of Hate (18);</li>
 * <li>лучник и маг держат дистанцию — моб бьёт вплотную, а сам быстрее моба: отбежать на {@value #KITE_DISTANCE} и бить с расстояния (с медленным не бегать — догонит);</li>
 * <li>маг держит ману — садится отдыхать раньше воинов (порог — в {@code PhantomHunterManager.rest}).</li>
 * </ul>
 */
final class PhantomTactics
{
	private static final Logger LOGGER = Logger.getLogger(PhantomTactics.class.getName());

	/** Перетянуть агро: Aggression — один моб, Aura of Hate — все вокруг танка. */
	private static final int AGGRESSION = 28;
	private static final int AURA_OF_HATE = 18;
	private static final int AURA_RANGE = 200;
	/** Товарищ дальше этого от танка — не его забота. */
	private static final int GUARD_RANGE = 900;
	/** Мобы, бьющие товарища, — в этом радиусе от него. */
	private static final int THREAT_RANGE = 400;
	/** Отбежать на столько; сам быстрее моба хотя бы на столько — иначе догонит. */
	private static final int KITE_DISTANCE = 400;
	private static final int KITE_SPEED_EDGE = 15;
	/** Бьёт вплотную — ближе этого (соперник на дуэли, {@link #kiteFrom}). */
	private static final int MELEE_RANGE = 150;
	/** Отбегает не чаще раза в столько: между отходами — бьёт. */
	private static final long KITE_GAP = 4000;
	/** Строка лога о тактике — не чаще раза в столько на охотника. */
	private static final long NOTE_GAP = 60000;

	private static final Map<Integer, Long> LAST_KITE = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> LAST_NOTE = new ConcurrentHashMap<>();

	private PhantomTactics()
	{
	}

	/**
	 * Тик охотника в бою.
	 * @return {@code true} — взял управление на этот тик (кастует агро, отбегает)
	 */
	static boolean tick(Player player, PhantomTemplate template, long now)
	{
		if ((template == null) || player.isDead() || player.isCastingNow() || player.isSitting())
		{
			return false;
		}
		if (template.getRole() == PhantomTemplate.Role.TANK)
		{
			return taunt(player, now);
		}
		return kite(player, now);
	}

	/**
	 * Танк: моб бьёт товарища по пати — перетянуть на себя.
	 */
	private static boolean taunt(Player tank, long now)
	{
		if (!tank.isInParty())
		{
			return false;
		}
		for (Player member : tank.getParty().getMembers())
		{
			if ((member == tank) || member.isDead() || (tank.calculateDistance2D(member) > GUARD_RANGE))
			{
				continue;
			}
			for (Monster monster : World.getVisibleObjectsInRange(member, Monster.class, THREAT_RANGE))
			{
				if (monster.isAlikeDead() || (PhantomHunterOutfit.victimOf(monster) != member))
				{
					continue;
				}
				final Skill aura = usable(tank, AURA_OF_HATE);
				final Skill skill = (aura != null) && (tank.calculateDistance2D(monster) <= AURA_RANGE) ? aura : usable(tank, AGGRESSION);
				if (skill == null)
				{
					return false;
				}
				tank.setTarget(skill == aura ? tank : monster);
				if (!PhantomCompat.useMagic(tank, skill, false, false))
				{
					return false;
				}
				note(tank, "pulls " + monster.getName() + " off " + member.getName() + " (" + skill.getName() + ")", now);
				return true;
			}
		}
		return false;
	}

	/**
	 * Лучник или маг: моб бьёт вплотную, а сам быстрее — отбежать от него и бить с расстояния.
	 */
	private static boolean kite(Player player, long now)
	{
		if (!isBow(player) && !PhantomHunterOutfit.isCaster(player))
		{
			return false;
		}
		final Monster attacker = PhantomHunterOutfit.meleeAttacker(player);
		return (attacker != null) && away(player, attacker, now);
	}

	/**
	 * Лучник или маг на дуэли ({@link PhantomDuel}): соперник-воин бьёт вплотную, а сам быстрее — отбежать.
	 * @return {@code true} — отбегает
	 */
	static boolean kiteFrom(Player player, Creature attacker, long now)
	{
		if ((!isBow(player) && !PhantomHunterOutfit.isCaster(player)) || (player.calculateDistance2D(attacker) > MELEE_RANGE))
		{
			return false;
		}
		if ((attacker instanceof Player) && (isBow((Player) attacker) || PhantomHunterOutfit.isCaster((Player) attacker)))
		{
			return false;
		}
		return away(player, attacker, now);
	}

	/**
	 * Отбежать от {@code attacker} на {@value #KITE_DISTANCE}, если сам быстрее и есть куда (геодата).
	 */
	private static boolean away(Player player, Creature attacker, long now)
	{
		if ((now - LAST_KITE.getOrDefault(player.getObjectId(), 0L)) < KITE_GAP)
		{
			return false;
		}
		if (player.getRunSpeed() < (attacker.getRunSpeed() + KITE_SPEED_EDGE))
		{
			return false;
		}

		final double dx = player.getX() - attacker.getX();
		final double dy = player.getY() - attacker.getY();
		final double length = Math.max(1, Math.hypot(dx, dy));
		final int x = player.getX() + (int) ((dx / length) * KITE_DISTANCE);
		final int y = player.getY() + (int) ((dy / length) * KITE_DISTANCE);
		final int z = GeoEngine.getInstance().getHeight(x, y, player.getZ());
		if ((Math.abs(z - player.getZ()) > 150) || !PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), player.getX(), player.getY(), player.getZ(), x, y, z, player.getInstanceId()))
		{
			return false;
		}
		LAST_KITE.put(player.getObjectId(), now);
		player.abortAttack();
		player.setRunning();
		player.getAI().setIntentionMoveTo(new Location(x, y, z));
		note(player, "keeps distance from " + attacker.getName() + " (" + (int) player.getRunSpeed() + " vs " + (int) attacker.getRunSpeed() + ")", now);
		return true;
	}

	private static boolean isBow(Player player)
	{
		final Item weapon = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		return (weapon != null) && weapon.isWeapon() && (weapon.getTemplate().getItemType() == WeaponType.BOW);
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
			LOGGER.info(PhantomTactics.class.getSimpleName() + ": " + player.getName() + " " + what + ".");
		}
	}
}
