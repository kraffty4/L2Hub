package org.l2jmobius.gameserver.managers;

import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Decoy;
import org.l2jmobius.gameserver.network.serverpackets.ActionFailed;
import org.l2jmobius.gameserver.network.serverpackets.DeleteObject;

/** Зрение получателя и обновление его клиентского окружения; права GM не меняются. */
public final class PlayingGmVision
{
	private PlayingGmVision()
	{
	}

	/** Проверяется до использования общей broadcast-копии пакета для каждого клиента. */
	public static boolean canReceive(Player viewer, WorldObject subject)
	{
		return (subject == null) || (viewer == null) || (subject == viewer)
			|| !PlayingGmPolicy.isPlaying(viewer) || subject.isVisibleFor(viewer);
	}

	/** Кэш старого режима не должен возвращать GM-декор после изменения роли зрителя/персонажа. */
	public static boolean needsFreshInfo(Player viewer, WorldObject subject)
	{
		return (subject != null) && (PlayingGmPolicy.isPlaying(viewer)
			|| ((subject.isPlayer() || (subject instanceof Decoy)) && PlayingGmPolicy.isPlaying(subject.asPlayer())));
	}

	private static boolean blocked(Player viewer, WorldObject subject)
	{
		return (subject != null) && !canReceive(viewer, subject);
	}

	/** Вызывается после изменения режима. Мир/спавн/невидимость других объектов не меняет. */
	public static void refresh(Player viewer)
	{
		if ((viewer == null) || !viewer.isGM())
		{
			return;
		}
		refresh(viewer, World.getVisibleObjects(viewer, WorldObject.class));
	}

	/** Отдельная обработка выборки позволяет проверить refresh без запуска мира. */
	static void refresh(Player viewer, Iterable<WorldObject> visibleObjects)
	{
		if ((viewer == null) || !viewer.isGM())
		{
			return;
		}

		final boolean hiddenTarget = blocked(viewer, viewer.getTarget());
		final boolean hasAI = viewer.hasAI();
		final boolean unsafeAction = hiddenTarget || (hasAI && (blocked(viewer, viewer.getAI().getAttackTarget())
			|| blocked(viewer, viewer.getAI().getCastTarget()) || blocked(viewer, viewer.getAI().getFollowTarget())));
		if (unsafeAction)
		{
			if (hasAI)
			{
				viewer.getAI().setNextAction(null);
			}
			viewer.abortAttack();
			viewer.abortCast();
			if (hasAI)
			{
				viewer.getAI().setIntentionIdle();
			}
		}
		if (hiddenTarget)
		{
			viewer.setTarget(null);
			viewer.sendPacket(ActionFailed.STATIC_PACKET);
		}

		// Native выбор соседних регионов и того же инстанса; весь мир не обходим.
		for (WorldObject object : visibleObjects)
		{
			// Родное окно собственного питомца сохраняется, даже если тот скрыт от других.
			if (object.isSummon() && (object.asSummon().getOwner() == viewer))
			{
				object.sendInfo(viewer);
				continue;
			}
			if (object.isCreature())
			{
				object.asCreature().getKnownRelations().remove(viewer.getObjectId());
			}
			if (object.isVisibleFor(viewer))
			{
				object.sendInfo(viewer);
			}
			else
			{
				viewer.sendPacket(new DeleteObject(object));
			}
		}
	}
}
