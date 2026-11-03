package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;

import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

/**
 * L2Hub: приоритет соклана (docs/PHANTOMS.md, раздел 4.123; вклад пользователя, переписан). Живой соклан зовёт охотника в пати — охотник уступает кач, город, лавку, пати охотников,
 * запись на ивент и на олимпиаду. Другого живого, идущий бой, матч, дуэль, осаду и рейд не бросает.<br>
 * Ответ — на тике самого охотника ({@link #process}): так старое дело в городе не доедет после перехода. Пока приглашение ждёт, охотник занят ({@link #pending}), но не дольше {@value #TTL} мс:
 * срок проверяется и там, и если тик не пришёл, охотник не остаётся занятым навсегда. Журнал — одна строка на итог, а не на каждую проверку.
 */
public final class PhantomClanInvite
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanInvite.class.getName());

	private static final long TTL = 20000;
	private static final int PARTY_MAX = 9;

	private record Invite(int ownerId, long until, PartyDistributionType type)
	{
	}

	private final Map<String, Invite> _pending = new ConcurrentHashMap<>();
	/** Кого сейчас ведёт {@link #process}: {@code onInvited} зовётся повторно и не должен откладывать снова. */
	private final ThreadLocal<String> _dispatch = new ThreadLocal<>();

	private PhantomClanInvite()
	{
	}

	private static String key(String name)
	{
		return name.toLowerCase(Locale.ROOT);
	}

	private static boolean live(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) || PhantomClans.getInstance().isTestPlayer(player);
	}

	/**
	 * @return живой соклан зовёт охотника, и ключ включён
	 */
	boolean priority(Player owner, Player bot)
	{
		return PhantomHuntersConfig.HUNTER_CLAN_INVITE_PRIORITY && (owner != null) && (bot != null) && live(owner) && (owner.getClanId() != 0) && (owner.getClanId() == bot.getClanId());
	}

	/**
	 * @return {@code onInvited} зовётся отсюда: охотника уже подготовили, обычные проверки инвайта не нужны
	 */
	boolean dispatching(PhantomTemplate template)
	{
		return key(template.getName()).equals(_dispatch.get());
	}

	/**
	 * Инвайт охотнику (из {@code PhantomCompanionManager.onInvited}).
	 * @return инвайт забран: отказ сказан или ответ отложен до тика охотника; {@code false} — обычный путь
	 */
	boolean defer(Player owner, Player bot, PhantomTemplate template, PartyDistributionType type)
	{
		if (!template.isHunter() || !priority(owner, bot) || dispatching(template))
		{
			return false;
		}

		String refusal = senderRefusal(owner);
		if (refusal == null)
		{
			refusal = PhantomHunterManager.getInstance().clanInviteRefusal(template, owner);
		}
		if (refusal != null)
		{
			tell(owner, bot, refusal);
			LOGGER.info(getClass().getSimpleName() + ": " + bot.getName() + " refuses " + owner.getName() + ": " + refusal);
			return true;
		}

		final long now = System.currentTimeMillis();
		final Invite old = _pending.get(key(template.getName()));
		if ((old != null) && (now <= old.until()) && (old.ownerId() != owner.getObjectId()))
		{
			tell(owner, bot, tr("Уже отвечаю на приглашение соклановца."));
			return true;
		}
		_pending.put(key(template.getName()), new Invite(owner.getObjectId(), now + TTL, type));
		return true;
	}

	/**
	 * @return охотника ждёт живой соклан: в город, лавку и на ивент не уходить. Срок вышел — забывает сам.
	 */
	public boolean pending(Player bot)
	{
		if (_pending.isEmpty() || (bot == null))
		{
			return false;
		}
		final String name = key(bot.getName());
		final Invite invite = _pending.get(name);
		if (invite == null)
		{
			return false;
		}
		if (System.currentTimeMillis() <= invite.until())
		{
			return true;
		}
		if (_pending.remove(name, invite))
		{
			final Player owner = World.getPlayer(invite.ownerId());
			if (owner != null)
			{
				tell(owner, bot, outdated());
			}
			LOGGER.info(getClass().getSimpleName() + ": invite for " + bot.getName() + " timed out.");
		}
		return false;
	}

	/**
	 * Тик охотника, начало {@code PhantomHunterManager.think}.
	 * @return охотник перешёл в пати соклана, дальше его ведёт менеджер напарников
	 */
	boolean process(PhantomTemplate template, Player bot, long now)
	{
		if (_pending.isEmpty())
		{
			return false;
		}
		final Invite invite = _pending.remove(key(template.getName()));
		if (invite == null)
		{
			return false;
		}

		final Player owner = World.getPlayer(invite.ownerId());
		String refusal = senderRefusal(owner);
		if ((refusal == null) && ((now > invite.until()) || !priority(owner, bot)))
		{
			refusal = outdated();
		}
		if ((refusal == null) && !bot.isInOlympiadMode() && PhantomCompat.olympiadRegistered(bot) && !PhantomCompat.olympiadUnregister(bot))
		{
			refusal = tr("Не вышло отменить запись на Олимпиаду.");
		}
		if ((refusal == null) && !PhantomEvents.getInstance().cancelForClanInvite(bot))
		{
			refusal = tr("Не вышло отменить запись на ивент.");
		}
		if (refusal == null)
		{
			refusal = PhantomHunterManager.getInstance().clanInviteRefusal(template, owner);
		}
		if (refusal != null)
		{
			if (owner != null)
			{
				tell(owner, bot, refusal);
			}
			LOGGER.info(getClass().getSimpleName() + ": " + bot.getName() + " refuses " + (owner == null ? "a player who left" : owner.getName()) + ": " + refusal);
			return false;
		}

		PhantomHunterParty.getInstance().yieldToClanInvite(bot, now);
		PhantomCompanionManager.getInstance().release(template, true);
		PhantomHunterManager.getInstance().prepareClanInvite(template, bot);
		_dispatch.set(key(template.getName()));
		try
		{
			PhantomCompanionManager.getInstance().onInvited(owner, bot, invite.type());
		}
		finally
		{
			_dispatch.remove();
		}
		final boolean joined = bot.isInParty() && owner.isInParty() && (bot.getParty() == owner.getParty());
		LOGGER.info(getClass().getSimpleName() + ": " + bot.getName() + (joined ? " joins " : " could not join ") + owner.getName() + ".");
		return joined;
	}

	private static String outdated()
	{
		return tr("Приглашение соклановца устарело.");
	}

	private static String senderRefusal(Player owner)
	{
		if ((owner == null) || !owner.isOnline())
		{
			return tr("Приглашающий вышел из игры.");
		}
		if (owner.isInOlympiadMode() || owner.isOnEvent() || owner.isInDuel())
		{
			return tr("Во время матча приглашение недоступно.");
		}
		if (owner.isInParty() && !owner.getParty().isLeader(owner))
		{
			return tr("Приглашать может только лидер пати.");
		}
		if (owner.isInParty() && (owner.getParty().getMemberCount() >= PARTY_MAX))
		{
			return tr("В пати уже девять участников.");
		}
		return null;
	}

	private static void tell(Player owner, Player bot, String text)
	{
		owner.sendMessage(bot.getName() + ": " + text);
	}

	// ---------- Самопроверка ----------

	/**
	 * {@code HuntersSelfTest = CLANINVITE}: ключ, срок ожидания, переход на тике, отказы. Идёт в своём потоке: ждёт тиков охотника.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		ThreadPool.execute(() -> getInstance().runSelfTest(world));
	}

	private void runSelfTest(List<Player> world)
	{
		final String tag = "SelfTest CLANINVITE: ";
		final List<String> failed = new ArrayList<>();
		final boolean key = PhantomHuntersConfig.HUNTER_CLAN_INVITE_PRIORITY;
		final PhantomCompanionManager companions = PhantomCompanionManager.getInstance();
		Player tester = null;
		Player bot = null;
		PhantomTemplate botTemplate = null;
		try
		{
			// Клан с двумя охотниками не в пати: один играет живого.
			for (Player player : world)
			{
				final Clan clan = player.getClan();
				if ((clan == null) || player.isInParty() || player.isDead())
				{
					continue;
				}
				for (ClanMember member : clan.getMembers())
				{
					final Player other = member.getPlayer();
					if ((other != null) && (other != player) && other.isOnline() && !other.isInParty() && !other.isDead() && PhantomHunterManager.getInstance().isHunting(other) && !PhantomEvents.getInstance().isBusy(other) && (PhantomRaid.getInstance().raidOf(other) == null))
					{
						tester = player;
						bot = other;
						break;
					}
				}
				if (bot != null)
				{
					break;
				}
			}
			if (bot == null)
			{
				LOGGER.warning(tag + "needs a clan with two hunters out of parties.");
				LOGGER.info(tag + "done, nothing to test.");
				return;
			}
			botTemplate = PhantomData.getInstance().getTemplate(bot.getName());
			PhantomClans.getInstance().testPlayer(tester);
			LOGGER.info(tag + tester.getName() + " (" + tester.getLevel() + ") calls " + bot.getName() + " (" + bot.getLevel() + "), clan " + bot.getClan().getName() + ".");

			PhantomHuntersConfig.HUNTER_CLAN_INVITE_PRIORITY = false;
			check(tag, failed, "1. ключ выключен: обычный путь", !priority(tester, bot) && !defer(tester, bot, botTemplate, PartyDistributionType.FINDERS_KEEPERS) && !pending(bot), "");
			PhantomHuntersConfig.HUNTER_CLAN_INVITE_PRIORITY = true;

			// Срок: тика не было, инвайт просрочен — охотник свободен, и занятым его больше никто не считает.
			final String name = key(bot.getName());
			_pending.put(name, new Invite(tester.getObjectId(), System.currentTimeMillis() - 1, PartyDistributionType.FINDERS_KEEPERS));
			check(tag, failed, "2. срок вышел: охотник не занят", !pending(bot) && !_pending.containsKey(name) && !PhantomEvents.getInstance().isBusy(bot), "");

			// Живой соклан: инвайт ждёт тика.
			final boolean took = defer(tester, bot, botTemplate, PartyDistributionType.FINDERS_KEEPERS);
			check(tag, failed, "3. инвайт отложен до тика, охотник занят", took && pending(bot) && PhantomEvents.getInstance().isBusy(bot), "");

			final long deadline = System.currentTimeMillis() + TTL;
			while (!bot.isInParty() && (System.currentTimeMillis() < deadline))
			{
				Thread.sleep(250);
			}
			check(tag, failed, "4. на своём тике перешёл в пати соклана", bot.isInParty() && (bot.getParty() == tester.getParty()) && companions.isCompanion(botTemplate) && !pending(bot), "пати " + bot.isInParty());

			// Повторный инвайт: уже в пати — отказ, а не вторая очередь.
			final boolean again = defer(tester, bot, botTemplate, PartyDistributionType.FINDERS_KEEPERS);
			check(tag, failed, "5. уже в его пати: отказ", again && !pending(bot) && "Я уже в твоей пати.".equals(PhantomHunterManager.getInstance().clanInviteRefusal(botTemplate, tester)), "");

			// Отпустили: тик сам возвращает его на кач.
			companions.release(botTemplate, true);
			if (tester.isInParty())
			{
				tester.leaveParty();
			}
			final long back = System.currentTimeMillis() + TTL;
			while (!PhantomHunterManager.getInstance().isHunting(bot) && (System.currentTimeMillis() < back))
			{
				Thread.sleep(250);
			}
			check(tag, failed, "6. отпустили: снова охотится", !bot.isInParty() && PhantomHunterManager.getInstance().isHunting(bot), "");

			// Лавка уступает: охотник в лавке и соклан рядом в мире. Нет такого на копии — шаг пропущен, это не провал.
			Player seller = null;
			Player buyer = null;
			for (Player player : world)
			{
				if (player.isInStoreMode() && (player.getClan() != null) && !player.isInParty())
				{
					for (ClanMember member : player.getClan().getMembers())
					{
						final Player other = member.getPlayer();
						if ((other != null) && (other != player) && other.isOnline() && !other.isInParty() && !other.isDead())
						{
							seller = player;
							buyer = other;
							break;
						}
					}
				}
				if (seller != null)
				{
					break;
				}
			}
			if (seller == null)
			{
				LOGGER.info(tag + "7. нет охотника в лавке с сокланом: шаг пропущен.");
			}
			else
			{
				final PhantomTemplate sellerTemplate = PhantomData.getInstance().getTemplate(seller.getName());
				PhantomClans.getInstance().testPlayer(buyer);
				defer(buyer, seller, sellerTemplate, PartyDistributionType.FINDERS_KEEPERS);
				final long deadlineStore = System.currentTimeMillis() + TTL;
				while (!seller.isInParty() && (System.currentTimeMillis() < deadlineStore))
				{
					Thread.sleep(250);
				}
				check(tag, failed, "7. лавка закрыта, охотник в мире и в пати соклана", seller.isInParty() && !seller.isInStoreMode() && seller.isOnline() && !seller.isInOfflineMode(), seller.getName() + " в пати " + seller.isInParty());
				companions.release(sellerTemplate, true);
				if (buyer.isInParty())
				{
					buyer.leaveParty();
				}
			}
			LOGGER.info(tag + "done, " + failed.size() + " failed" + (failed.isEmpty() ? "." : ": " + failed));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed", e);
			LOGGER.info(tag + "done, stopped by an exception.");
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_CLAN_INVITE_PRIORITY = key;
			PhantomClans.getInstance().testPlayer(null);
			_pending.clear();
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

	public static PhantomClanInvite getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClanInvite INSTANCE = new PhantomClanInvite();
	}
}
