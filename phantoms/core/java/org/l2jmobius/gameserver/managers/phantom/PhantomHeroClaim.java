package org.l2jmobius.gameserver.managers.phantom;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.OlympiadConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.mechanics.olympiad.Hero;

/**
 * Геройство охотникам по-честному (перенос с Эпилога, A17 сверки): победитель периода Олимпиады, ещё не забравший геройство, идёт в город
 * к монументу героя и забирает его штатным {@link Hero#claimHero}, как живой игрок. Героя даром не выдаёт: только запись победителя Mobius.
 * Не идёт, пока записан на Олимпиаду, на ивенте, в рейде, ждёт приглашения в пати ({@link PhantomCompat#partyInvitePending}), в пати или в лавке.
 * Монумент не найден или не дошёл за 90 с — новая попытка через 5 минут. Выключатель — {@code HunterOlympiad} и {@code OlympiadEnabled}.
 */
public final class PhantomHeroClaim
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHeroClaim.class.getName());
	private static final Set<Integer> MONUMENTS = Set.of(31690, 31769, 31770, 31771, 31772);
	private static final long VISIT_TIMEOUT = 90000;
	private static final long RETRY_DELAY = 300000;
	private static final long MOVE_GAP = 2000;
	private static final Map<Player, Visit> VISITS = Collections.synchronizedMap(new WeakHashMap<>());
	private static final Services NATIVE = new RuntimeServices();

	private static final class Visit
	{
		Npc monument;
		long deadline;
		long retryAt;
		long nextMove;
	}

	/**
	 * Внешние зависимости — ради отдельных проверок; в игре всегда {@link RuntimeServices}.
	 */
	interface Services
	{
		boolean enabled();

		boolean unclaimed(Player player);

		boolean queued(Player player);

		Npc nearestMonument(Player player);

		boolean canSee(Player player, Npc npc);

		boolean claim(Player player, Npc npc);
	}

	private static final class RuntimeServices implements Services
	{
		@Override
		public boolean enabled()
		{
			return OlympiadConfig.OLYMPIAD_ENABLED && PhantomHuntersConfig.HUNTER_OLYMPIAD;
		}

		@Override
		public boolean unclaimed(Player player)
		{
			return Hero.getInstance().isUnclaimedHero(player.getObjectId());
		}

		@Override
		public boolean queued(Player player)
		{
			return PhantomCompat.olympiadRegistered(player) || PhantomEvents.getInstance().isBusy(player) || (PhantomRaid.getInstance().raidOf(player) != null) || PhantomCompat.partyInvitePending(player);
		}

		@Override
		public Npc nearestMonument(Player player)
		{
			return World.getNearestVisibleObjectInRange(player, Npc.class, 6000, npc -> candidateMonument(player, npc));
		}

		@Override
		public boolean canSee(Player player, Npc npc)
		{
			return GeoEngine.getInstance().canSeeTarget(player, npc);
		}

		@Override
		public boolean claim(Player player, Npc npc)
		{
			final Hero hero = Hero.getInstance();
			synchronized (hero)
			{
				// Штатный claimHero сам не проверяет ни права победителя, ни повторную выдачу: проверить прямо перед единственным вызовом.
				if (!validMonument(player, npc) || !npc.canInteract(player) || !canSee(player, npc) || !hero.isUnclaimedHero(player.getObjectId()))
				{
					return false;
				}
				hero.claimHero(player);
				return hero.isHero(player.getObjectId());
			}
		}
	}

	private PhantomHeroClaim()
	{
	}

	static boolean validMonument(Player player, Npc npc)
	{
		return candidateMonument(player, npc) && (Math.abs((long) npc.getZ() - player.getZ()) <= 200);
	}

	/**
	 * Поиск — штатный обход мира в 6000 по 3D; победитель должен успеть подняться по лестнице города до проверок взаимодействия.
	 */
	static boolean candidateMonument(Player player, Npc npc)
	{
		return (npc != null) && MONUMENTS.contains(npc.getId()) && npc.isSpawned() && !npc.isDead() && (npc.getInstanceId() == player.getInstanceId());
	}

	private static boolean eligible(Player player, PhantomTemplate template, Services services)
	{
		return (player != null) && (template != null) && template.isHunter() && !template.isReaper() && (player.getClient() == null) && player.isOnline() && services.enabled() && !player.isDead() && !player.isFakeDeath() && !player.isTeleporting() && !player.isInOlympiadMode() && !player.inObserverMode() && !player.isInDuel() && !player.isInSiege() && !player.isOnEvent() && !player.isInParty() && !player.isInStoreMode() && !player.isProcessingTransaction() && (player.getInstanceId() == 0);
	}

	/**
	 * @return победителю пора в город за геройством
	 */
	public static boolean wantsTown(Player player, PhantomTemplate template, long now)
	{
		return wantsTown(player, template, now, NATIVE);
	}

	static boolean wantsTown(Player player, PhantomTemplate template, long now, Services services)
	{
		if (!eligible(player, template, services))
		{
			return false;
		}
		if (!services.unclaimed(player))
		{
			VISITS.remove(player);
			return false;
		}
		if (services.queued(player))
		{
			return false;
		}
		final Visit visit = VISITS.get(player);
		return (visit == null) || (now >= visit.retryAt);
	}

	/**
	 * Шаг в городе: найти монумент, дойти, забрать геройство.
	 * @return занят этим делом — остальное в этот тик не решать
	 */
	public static boolean tick(Player player, PhantomTemplate template, long now)
	{
		return tick(player, template, now, NATIVE);
	}

	static boolean tick(Player player, PhantomTemplate template, long now, Services services)
	{
		if (!wantsTown(player, template, now, services) || !player.isInsideZone(ZoneId.PEACE) || player.isInCombat() || player.isAttackingNow() || player.isCastingNow() || player.isMovementDisabled())
		{
			return false;
		}
		synchronized (player)
		{
			final Visit visit = VISITS.computeIfAbsent(player, key -> new Visit());
			if (now < visit.retryAt)
			{
				return false;
			}
			if (visit.monument == null)
			{
				visit.monument = services.nearestMonument(player);
				if (!candidateMonument(player, visit.monument))
				{
					retry(visit, now);
					return false;
				}
				visit.deadline = now + VISIT_TIMEOUT;
			}
			final Npc npc = visit.monument;
			if ((now >= visit.deadline) || !candidateMonument(player, npc))
			{
				retry(visit, now);
				return false;
			}
			if (player.isSitting())
			{
				player.standUp();
				return true;
			}
			if (validMonument(player, npc) && npc.canInteract(player) && services.canSee(player, npc))
			{
				// Право на геройство могло пропасть по дороге или на смене периода.
				if (!services.unclaimed(player))
				{
					VISITS.remove(player);
					return false;
				}
				player.stopMove(null);
				if (services.claim(player, npc))
				{
					VISITS.remove(player);
					LOGGER.info("Olympiad winner " + player.getName() + " claimed hero status at monument " + npc.getId() + ".");
				}
				else
				{
					retry(visit, now);
				}
				return true;
			}
			if (now >= visit.nextMove)
			{
				final double angle = Math.atan2(player.getY() - npc.getY(), player.getX() - npc.getX());
				final Location target = new Location(npc.getX() + (int) (Math.cos(angle) * 100), npc.getY() + (int) (Math.sin(angle) * 100), npc.getZ());
				if (!player.isMoving() || (Math.abs((long) player.getXdestination() - target.getX()) > 40) || (Math.abs((long) player.getYdestination() - target.getY()) > 40))
				{
					player.setRunning();
					player.getAI().setIntentionMoveTo(target);
				}
				visit.nextMove = now + MOVE_GAP;
			}
			return true;
		}
	}

	private static void retry(Visit visit, long now)
	{
		visit.monument = null;
		visit.retryAt = now + RETRY_DELAY;
	}
}
