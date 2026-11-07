package org.l2jmobius.gameserver.managers;

import java.util.ArrayList;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;

/** Персональный выбор и снятие случайно оставшихся игровых GM-эффектов. */
public final class PlayingGmState
{
	public enum ChangeResult
	{
		CHANGED,
		NOT_GM,
		DISABLED,
		OBSERVER,
		SAVE_FAILED
	}

	private PlayingGmState()
	{
	}

	/** Переключает только самого администратора, без смены AccessLevel или чужих переменных. */
	public static ChangeResult change(Player player, boolean playing)
	{
		if ((player == null) || !player.isGM())
		{
			return ChangeResult.NOT_GM;
		}
		if (!PlayingGmPolicy.isEnabled())
		{
			return ChangeResult.DISABLED;
		}
		// Наблюдение временно использует невидимость и неуязвимость; сначала надо выйти из него.
		if (player.inObserverMode())
		{
			return ChangeResult.OBSERVER;
		}

		final PlayerVariables variables = player.getVariables();
		synchronized (variables)
		{
			final boolean existed = variables.hasVariable(PlayingGmPolicy.MODE_VARIABLE);
			final String previous = variables.getString(PlayingGmPolicy.MODE_VARIABLE, "false");
			variables.set(PlayingGmPolicy.MODE_VARIABLE, playing);
			if (!variables.saveNow())
			{
				if (existed)
				{
					variables.set(PlayingGmPolicy.MODE_VARIABLE, previous);
				}
				else
				{
					variables.remove(PlayingGmPolicy.MODE_VARIABLE);
				}
				return ChangeResult.SAVE_FAILED;
			}
		}

		if (playing)
		{
			normalize(player);
		}
		// Возврат GM-обходов не должен сам включать бессмертие, скрытие или Super Haste.
		player.broadcastUserInfo();
		return ChangeResult.CHANGED;
	}

	/** Вызывается при включении режима и после штатного восстановления персонажа при входе. */
	public static void normalize(Player player)
	{
		if (!PlayingGmPolicy.isPlaying(player) || player.inObserverMode())
		{
			return;
		}
		for (BuffInfo info : new ArrayList<>(player.getEffectList().getEffects()))
		{
			if (info.getSkill().isGMSkill())
			{
				player.stopSkillEffects(SkillFinishType.REMOVED, info.getSkill().getId());
			}
		}
		// Hide сам использует setInvisible: нельзя снимать законную классовую невидимость.
		final boolean legitimateHide = player.getEffectList().getEffects().stream()
			.filter(info -> !info.getSkill().isGMSkill())
			.anyMatch(info -> info.getEffects().stream().anyMatch(effect -> "Hide".equals(effect.getClass().getSimpleName())));
		player.setInvul(false);
		player.setDietMode(false);
		player.refreshOverloaded();
		if (player.isInvisible() && !legitimateHide)
		{
			player.setInvisible(false);
		}
	}
}
