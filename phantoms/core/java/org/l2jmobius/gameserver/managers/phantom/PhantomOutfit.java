package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: сборка фантома под уровень — опыт, профессия ветки, скиллы, шмот своего грейда и BSS.<br>
 * Вызывается до входа в мир (тогда первый CharInfo уже показывает шмот) или для фантома в мире.
 */
final class PhantomOutfit
{
	private static final Logger LOGGER = Logger.getLogger(PhantomOutfit.class.getName());

	private PhantomOutfit()
	{
	}

	/**
	 * Полная сборка: уровень, класс и скиллы (если уровень или класс меняются), шмот заново, BSS, полные бары.
	 * @param player фантом
	 * @param template напарник
	 * @param level уровень, до которого его подтянуть
	 */
	static void build(Player player, PhantomTemplate template, int level)
	{
		// Стопка BSS даёт перегруз, а с перегрузом 4-й степени фантом не может сдвинуться с места.
		player.setDietMode(true);
		revive(player);

		final int classId = template.getClassId(level);
		final boolean relearn = (player.getLevel() != level) || (player.getPlayerClass().getId() != classId) || player.getAllSkills().isEmpty();

		// Сначала снять и уничтожить старый шмот: его скиллы не должны пережить пересборку.
		player.getInventory().destroyAllItems(ItemProcessType.DESTROY, player, null);
		player.getAutoSoulShot().clear();

		setLevel(player, level);
		if (relearn)
		{
			relearn(player, classId);
		}

		dress(player, template.getGear(level));
		refill(player, template.getGear(level));
		fullBars(player);
		LOGGER.info("PhantomOutfit: " + player.getName() + " is level " + player.getLevel() + " " + player.getPlayerClass().name() + ", " + player.getAllSkills().size() + " skills" + (relearn ? " (relearned)" : "") + ".");
	}

	/**
	 * Лёгкое обновление после рестарта: шмот и скиллы уже в базе, а регистрация BSS и вес — только в памяти.
	 * @param player фантом
	 * @param template напарник
	 */
	static void refresh(Player player, PhantomTemplate template)
	{
		player.setDietMode(true);
		revive(player);
		final PhantomTemplate.Gear gear = template.getGear(player.getLevel());
		if (player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND) == null)
		{
			dress(player, gear);
		}
		refill(player, gear);
		fullBars(player);
	}

	/**
	 * Мёртвому HP и MP не выставляются ({@code CreatureStatus} молча пропускает), а {@link Player#load} считает мёртвым любого с curHp &lt; 0.5 в базе. Поэтому перед сборкой фантома поднимаем: в мире — честным ресом с пакетом,
	 * до входа в мир — просто снимаем флаг.
	 */
	private static void revive(Player player)
	{
		if (!player.isDead())
		{
			return;
		}

		if (player.isSpawned())
		{
			player.doRevive();
		}
		else
		{
			player.setDead(false);
		}
	}

	private static void setLevel(Player player, int level)
	{
		final long current = player.getExp();
		final long wanted = ExperienceData.getInstance().getExpForLevel(level);
		if (current > wanted)
		{
			player.getStat().setLevel((byte) level);
			player.removeExpAndSp(current - wanted, 0, false);
		}
		else if (current < wanted)
		{
			player.addExpAndSp(wanted - current, 0);
		}

		if (player.getLevel() != level)
		{
			LOGGER.warning("PhantomOutfit: " + player.getName() + " stopped at level " + player.getLevel() + " instead of " + level + ".");
		}
	}

	/**
	 * Скиллы заново: всё старое снять, выставить класс ветки, выучить всё доступное классу на этом уровне.
	 */
	private static void relearn(Player player, int classId)
	{
		for (Skill skill : new ArrayList<>(player.getAllSkills()))
		{
			player.removeSkill(skill, true, true);
		}

		if (player.getPlayerClass().getId() != classId)
		{
			player.setPlayerClass(classId);
		}
		player.setBaseClass(classId);
		player.giveAvailableSkills(true, true, true);
		// Клановые скиллы (раздел 4.41) Mobius вешает при входе и вступлении в клан, а здесь они снялись со всеми.
		if (player.getClan() != null)
		{
			player.getClan().addSkillEffects(player);
		}
	}

	static void dress(Player player, PhantomTemplate.Gear gear)
	{
		if (gear == null)
		{
			return;
		}

		for (int itemId : gear.items())
		{
			if (itemId <= 0)
			{
				continue;
			}

			final Item item = player.getInventory().addItem(ItemProcessType.REWARD, itemId, 1, player, null);
			if ((item != null) && item.isEquipable())
			{
				player.getInventory().equipItem(item);
			}
			else
			{
				LOGGER.warning("PhantomOutfit: " + player.getName() + " could not equip item " + itemId + ".");
			}
		}
		PhantomCompat.refreshOverloaded(player);
		PhantomCompat.refreshExpertisePenalty(player);
	}

	private static void refill(Player player, PhantomTemplate.Gear gear)
	{
		if ((gear == null) || (gear.shots <= 0))
		{
			return;
		}

		final Item shots = player.getInventory().getItemByItemId(gear.shots);
		final long have = shots == null ? 0 : shots.getCount();
		if (have < PhantomsConfig.PHANTOM_SHOT_COUNT)
		{
			PhantomCompat.addItem(player, ItemProcessType.REWARD, gear.shots, PhantomsConfig.PHANTOM_SHOT_COUNT - have, null);
		}
		player.addAutoSoulShot(gear.shots);
		PhantomCompat.rechargeShots(player);
	}

	private static void fullBars(Player player)
	{
		player.setCurrentHpMp(player.getMaxHp(), player.getMaxMp());
		player.setCurrentCp(player.getMaxCp());
	}
}
