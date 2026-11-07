package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.effects.EffectFlag;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;

/**
 * L2Hub: ноблесс в пати живого дворянина (docs/PHANTOMS.md, раздел 4.124; вклад пользователя, переписан).<br>
 * Напарник в пати живого дворянина сам становится дворянином: родное дерево скиллов Mobius, там и Noblesse Blessing ({@value #BLESSING}). Флаг хранится в базе и при выходе из пати не снимается.
 * <b>Охотник дворянство отсюда не получает</b>: с 76-го уровня он берёт его сам ({@link PhantomOlympiad}), а здесь только пользуется скиллом, если он уже есть.<br>
 * Каст родной: реагент и мана настоящие, неудача не замораживает. Саппорт держит благословение на пати после экстренного хила и реса, боец — на себе.
 */
final class PhantomPartyNoblesse
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPartyNoblesse.class.getName());

	/** Noblesse Blessing: 5 Spirit Ore на каст. */
	static final int BLESSING = 1323;
	static final int SPIRIT_ORE = 3031;
	/** Напарник докладывает реагент, когда осталось меньше, до этого запаса (шоты напарника тоже даром, {@code PhantomOutfit}). */
	private static final long ORE_LOW = 10;
	private static final long ORE_STOCK = 100;
	/** Поднятому или потерявшему благословение — срочно: столько, мс. */
	private static final long URGENT = 20000;
	private static final long CLEAN_GAP = 60000;
	private static final long FORGET = 300000;
	/** Плановое благословение — когда у всех в пати HP не ниже (%). */
	private static final int CALM_HP = 85;
	/** Срочное — когда у всех HP не ниже (%). */
	private static final int URGENT_HP = 50;

	private record Link(int owner, boolean support, long seen)
	{
	}

	private record Seen(boolean dead, boolean blessed, long urgentUntil, long seen)
	{
	}

	private record Claim(int caster, long started, long until)
	{
	}

	private static final Map<Integer, Link> LINKS = new ConcurrentHashMap<>();
	private static final Map<Integer, Seen> OBSERVED = new ConcurrentHashMap<>();
	private static final Map<Integer, Claim> CLAIMS = new ConcurrentHashMap<>();
	private static volatile long _cleanAt;

	private PhantomPartyNoblesse()
	{
	}

	private static boolean protectedBy(Player player)
	{
		if (player.isAffected(EffectFlag.NOBLESS_BLESSING))
		{
			return true;
		}
		final BuffInfo info = player.getEffectList().getBuffInfoBySkillId(BLESSING);
		return (info != null) && info.isInUse();
	}

	private static boolean live(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) || PhantomClans.getInstance().isTestPlayer(player);
	}

	/**
	 * Тик напарника: связать с живым дворянином и, если это не охотник, дать дворянство.
	 * @param owner владелец
	 * @param bot фантом в его пати
	 * @param template шаблон фантома
	 * @param now текущее время
	 */
	static void synchronize(Player owner, Player bot, PhantomTemplate template, long now)
	{
		// Личный помощник наследует право на автоматический1323 только от текущего дворянина.
		if ((bot != null) && ((owner == null) || (template == null)
			|| (!template.isHunter() && !template.isReaper() && (!owner.isNoble() || !PhantomPersonalNoblesse.allows(bot, BLESSING)))))
		{
			LINKS.remove(bot.getObjectId());
			return;
		}
		if (!PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE || (owner == null) || (bot == null) || (owner == bot) || !live(owner) || !PhantomManager.getInstance().isPhantom(bot) || (!owner.isNoble() && !bot.isNoble()) || bot.isInOlympiadMode() || bot.isOnEvent() || bot.isInDuel() || !bot.isInParty() || (bot.getParty() != owner.getParty()))
		{
			return;
		}

		LINKS.put(bot.getObjectId(), new Link(owner.getObjectId(), PhantomSupport.isSupport(template), now));
		if (!template.isHunter())
		{
			final boolean grant = owner.isNoble() && !bot.isNoble();
			if (grant || (bot.isNoble() && (bot.getKnownSkill(BLESSING) == null)))
			{
				bot.setNoble(true);
				if (grant)
				{
					bot.storeMe();
					LOGGER.info(PhantomPartyNoblesse.class.getSimpleName() + ": companion " + bot.getName() + " is a noble now, after " + owner.getName() + ".");
				}
			}
			if ((bot.getKnownSkill(BLESSING) != null) && (bot.getInventory().getInventoryItemCount(SPIRIT_ORE, -1) < ORE_LOW))
			{
				PhantomCompat.addItem(bot, ItemProcessType.REWARD, SPIRIT_ORE, ORE_STOCK, null);
			}
		}
		observe(bot.getParty().getMembers(), now);
		cleanup(now);
	}

	private static void cleanup(long now)
	{
		if (now < _cleanAt)
		{
			return;
		}
		_cleanAt = now + CLEAN_GAP;
		LINKS.entrySet().removeIf(entry -> (now - entry.getValue().seen()) > FORGET);
		OBSERVED.entrySet().removeIf(entry -> (now - entry.getValue().seen()) > FORGET);
		CLAIMS.entrySet().removeIf(entry -> entry.getValue().until() <= now);
	}

	private static void observe(List<Player> members, long now)
	{
		for (Player member : members)
		{
			final boolean dead = member.isDead();
			final boolean blessed = protectedBy(member);
			OBSERVED.compute(member.getObjectId(), (id, old) ->
			{
				long urgent = old == null ? 0 : old.urgentUntil();
				if ((old != null) && !dead && (old.dead() || (old.blessed() && !blessed)))
				{
					urgent = now + URGENT;
				}
				return new Seen(dead, blessed, blessed ? 0 : urgent, now);
			});
			if (dead || blessed)
			{
				CLAIMS.remove(member.getObjectId());
			}
		}
	}

	private static boolean eligible(Player caster)
	{
		final Link link = LINKS.get(caster.getObjectId());
		if ((link == null) || !caster.isNoble() || !caster.isInParty() || caster.isDead() || caster.isTeleporting() || caster.isInOlympiadMode() || caster.isOnEvent() || caster.isInDuel())
		{
			return false;
		}
		for (Player member : caster.getParty().getMembers())
		{
			if (member.getObjectId() == link.owner())
			{
				return PhantomPersonalNoblesse.allows(caster, BLESSING);
			}
		}
		return false;
	}

	private static boolean recent(Player player, long now)
	{
		final Seen state = OBSERVED.get(player.getObjectId());
		return (state != null) && (state.urgentUntil() > now);
	}

	/** Кто-то уже кастует на эту цель. */
	private static boolean claimed(Player target, long now)
	{
		final Claim claim = CLAIMS.get(target.getObjectId());
		if (claim == null)
		{
			return false;
		}
		if ((claim.until() > now) && target.isInParty())
		{
			for (Player member : target.getParty().getMembers())
			{
				if ((member.getObjectId() == claim.caster()) && !member.isDead() && !member.isTeleporting() && (((now - claim.started()) <= 1000) || member.isCastingNow()))
				{
					return true;
				}
			}
		}
		CLAIMS.remove(target.getObjectId(), claim);
		return false;
	}

	private static boolean hasReagent(Player player, Skill skill)
	{
		return (skill.getItemConsumeId() <= 0) || (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) >= skill.getItemConsumeCount());
	}

	private static boolean reachable(Player caster, Player target, Skill skill)
	{
		return (caster == target) || ((caster.calculateDistance3D(target) <= skill.getCastRange()) && GeoEngine.getInstance().canSeeTarget(caster, target));
	}

	/** Свободный саппорт рядом сделает это лучше: боец держит себя, остальных — только если саппорта нет. */
	private static boolean supportCanCover(Player caster, Player target)
	{
		final Link own = LINKS.get(caster.getObjectId());
		if ((target == caster) || ((own != null) && own.support()))
		{
			return false;
		}
		for (Player member : caster.getParty().getMembers())
		{
			final Link link = LINKS.get(member.getObjectId());
			if ((member == caster) || (link == null) || !link.support() || !eligible(member) || member.isCastingNow() || member.isSitting())
			{
				continue;
			}
			final Skill skill = PhantomSupport.usable(member, BLESSING);
			if ((skill != null) && hasReagent(member, skill) && reachable(member, target, skill))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean cast(Player caster, Player target, long now)
	{
		if (!eligible(caster) || target.isDead() || (target.getInstanceId() != caster.getInstanceId()) || !caster.getParty().getMembers().contains(target) || protectedBy(target))
		{
			return false;
		}
		final Skill skill = PhantomSupport.usable(caster, BLESSING);
		if ((skill == null) || !hasReagent(caster, skill) || !reachable(caster, target, skill) || supportCanCover(caster, target))
		{
			return false;
		}
		synchronized (CLAIMS)
		{
			if (protectedBy(target) || claimed(target, now))
			{
				return false;
			}
			if (caster.isSitting())
			{
				caster.standUp();
				return true;
			}
			caster.setTarget(target);
			if (!PhantomCompat.useMagic(caster, skill, false, false))
			{
				return false;
			}
			CLAIMS.put(target.getObjectId(), new Claim(caster.getObjectId(), now, now + Math.max(2000, skill.getHitTime() + 2000)));
			return true;
		}
	}

	/**
	 * Боец перед ударом: сначала благословение себе, потом тем, кого некому прикрыть.
	 * @return занят кастом в этот тик
	 */
	static boolean maintain(Player caster, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE || !eligible(caster) || caster.isCastingNow())
		{
			return false;
		}
		if (!protectedBy(caster) && cast(caster, caster, now))
		{
			return true;
		}
		return protectParty(caster, true, now) || protectParty(caster, false, now);
	}

	/**
	 * Благословение на пати.
	 * @param caster кастующий
	 * @param recentOnly только поднятые и потерявшие его — срочно, сразу после экстренного хила
	 * @param now текущее время
	 * @return начал каст
	 */
	static boolean protectParty(Player caster, boolean recentOnly, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE || !eligible(caster) || caster.isCastingNow())
		{
			return false;
		}

		final List<Player> members = caster.getParty().getMembers();
		observe(members, now);
		final int calm = recentOnly ? URGENT_HP : CALM_HP;
		for (Player member : members)
		{
			if (!member.isDead() && (member.getInstanceId() == caster.getInstanceId()) && ((member.getCurrentHp() * 100) < (member.getMaxHp() * (double) calm)))
			{
				return false;
			}
		}

		final List<Player> targets = new ArrayList<>(members);
		targets.sort(Comparator.comparingInt((Player player) -> recent(player, now) ? 0 : !PhantomManager.getInstance().isPhantom(player) ? 1 : player == caster ? 2 : 3).thenComparingInt(Player::getObjectId));
		for (Player target : targets)
		{
			if ((!recentOnly || recent(target, now)) && cast(caster, target, now))
			{
				return true;
			}
		}
		return false;
	}

	// ---------- Самопроверка ----------

	/**
	 * {@code HuntersSelfTest = NOBLESSE}: охотник дворянства не получает, напарник получает, держит благословение на владельце. Идёт в своём потоке: ждёт каста.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		ThreadPool.execute(() -> runSelfTest(world));
	}

	private static void runSelfTest(List<Player> world)
	{
		final String tag = "SelfTest NOBLESSE: ";
		final List<String> failed = new ArrayList<>();
		final boolean key = PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE;
		Player owner = null;
		boolean ownerWasNoble = false;
		PhantomTemplate companionTemplate = null;
		try
		{
			// Хозяин — охотник-не дворянин, играет живого.
			Player hunter = null;
			for (Player player : world)
			{
				if (!player.isNoble() && !player.isInParty() && !player.isDead() && PhantomHunterManager.getInstance().isHunting(player))
				{
					if (owner == null)
					{
						owner = player;
					}
					else if (Math.abs(player.getLevel() - owner.getLevel()) <= 5)
					{
						hunter = player;
						break;
					}
				}
			}
			if ((owner == null) || (hunter == null))
			{
				LOGGER.warning(tag + "needs two hunters of close levels out of parties, not nobles.");
				LOGGER.info(tag + "done, nothing to test.");
				return;
			}
			PhantomClans.getInstance().testPlayer(owner);
			PhantomFavors.getInstance().freeze(owner);
			ownerWasNoble = owner.isNoble();
			owner.setNoble(true);
			final PhantomTemplate hunterTemplate = PhantomData.getInstance().getTemplate(hunter.getName());
			LOGGER.info(tag + "owner " + owner.getName() + " (" + owner.getLevel() + "), hunter " + hunter.getName() + " (" + hunter.getLevel() + ").");

			PhantomCompanionManager.getInstance().onInvited(owner, hunter, null);
			Thread.sleep(3000);
			PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE = true;
			synchronize(owner, hunter, hunterTemplate, System.currentTimeMillis());
			check(tag, failed, "1. охотник в пати живого дворянина дворянином не стал", hunter.isInParty() && !hunter.isNoble() && (hunter.getKnownSkill(BLESSING) == null), "в пати " + hunter.isInParty() + ", дворянин " + hunter.isNoble());
			PhantomCompanionManager.getInstance().release(hunterTemplate, true);

			PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE = false;
			PhantomTemplate free = null;
			for (PhantomTemplate template : PhantomData.getInstance().getTemplates())
			{
				if (!template.isHunter() && !template.isReaper() && (PhantomManager.getInstance().getPhantom(template) == null))
				{
					free = template;
					break;
				}
			}
			if (free == null)
			{
				LOGGER.warning(tag + "no free companion.");
				LOGGER.info(tag + "done, " + failed.size() + " failed, the companion part is skipped.");
				return;
			}
			companionTemplate = free;
			final Player companion = PhantomManager.getInstance().spawn(free, owner.getLocation(), owner.getLevel());
			if (companion == null)
			{
				check(tag, failed, "2. напарник не вышел в мир", false, free.getName());
				LOGGER.info(tag + "done, " + failed.size() + " failed: " + failed);
				return;
			}
			PhantomCompanionManager.getInstance().onInvited(owner, companion, null);
			Thread.sleep(3000);
			check(tag, failed, "2. ключ выключен: напарник не дворянин", companion.isInParty() && !companion.isNoble(), "в пати " + companion.isInParty());

			PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE = true;
			long deadline = System.currentTimeMillis() + 15000;
			while (!companion.isNoble() && (System.currentTimeMillis() < deadline))
			{
				Thread.sleep(250);
			}
			check(tag, failed, "3. напарник в пати живого дворянина стал дворянином, скилл и реагент есть", companion.isNoble() && (companion.getKnownSkill(BLESSING) != null) && (companion.getInventory().getInventoryItemCount(SPIRIT_ORE, -1) >= ORE_LOW), "дворянин " + companion.isNoble() + ", скилл " + (companion.getKnownSkill(BLESSING) != null) + ", руда " + companion.getInventory().getInventoryItemCount(SPIRIT_ORE, -1));

			deadline = System.currentTimeMillis() + 45000;
			while (!(protectedBy(owner) && protectedBy(companion)) && (System.currentTimeMillis() < deadline))
			{
				Thread.sleep(500);
			}
			check(tag, failed, "4. держит благословение на себе и на владельце", protectedBy(companion) && protectedBy(owner), "на себе " + protectedBy(companion) + ", на владельце " + protectedBy(owner) + ", роль " + free.getRole());

			// Без реагента не замораживается.
			final long ore = companion.getInventory().getInventoryItemCount(SPIRIT_ORE, -1);
			companion.stopSkillEffects(SkillFinishType.REMOVED, BLESSING);
			companion.getInventory().destroyItemByItemId(ItemProcessType.NONE, SPIRIT_ORE, (int) ore, companion, null);
			final boolean casting = cast(companion, companion, System.currentTimeMillis());
			check(tag, failed, "5. нет реагента: не кастует и не занят", !casting, "руды было " + ore);

			LOGGER.info(tag + "done, " + failed.size() + " failed" + (failed.isEmpty() ? "." : ": " + failed));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed", e);
			LOGGER.info(tag + "done, stopped by an exception.");
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE = key;
			PhantomClans.getInstance().testPlayer(null);
			if (owner != null)
			{
				PhantomFavors.getInstance().unfreeze(owner);
				if (!ownerWasNoble)
				{
					owner.setNoble(false);
				}
				if (owner.isInParty())
				{
					owner.leaveParty();
				}
			}
			if (companionTemplate != null)
			{
				PhantomCompanionManager.getInstance().release(companionTemplate, true);
			}
		}
	}

	private static void check(String tag, List<String> failed, String name, boolean ok, String detail)
	{
		LOGGER.info(tag + (ok ? "OK " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
		if (!ok)
		{
			failed.add(name);
		}
	}
}
