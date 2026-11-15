package org.l2jmobius.gameserver.managers.phantom;

import java.lang.ref.WeakReference;
import java.util.Map;
import java.util.WeakHashMap;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;

/**
 * Замок реса: один труп — один каст Resurrection, откаты и цена скилла родные.
 * Механизм сопровождающего Эпилога ({@code 0512}, {@code SupportResurrection}), перенесён в core.
 * Отличие от оригинала одно: «текущий каст» берётся через {@link PhantomCompat#castingToken(Player)} и {@link PhantomCompat#castingSkillId(Player)},
 * потому что на Classic и Essence у игрока нет {@code getCurrentSkill()}. На Эпилоге этот файл перекрывает его собственный.
 */
public final class SupportResurrection
{
	// Слабые ключи и слабая ссылка на ресающего: отпущенные напарники не держатся в памяти.
	private static final Map<Player, Claim> CLAIMS = new WeakHashMap<>();

	private record Claim(WeakReference<Player> caster, Object cast, int instanceId)
	{
		boolean active(Player corpse)
		{
			final Player player = caster.get();
			return corpse.isDead() && !corpse.isReviveRequested() && (player != null) && !player.isDead() && player.isOnline() && (corpse.getInstanceId() == instanceId) && (player.getInstanceId() == instanceId) && player.isCastingNow() && (PhantomCompat.castingToken(player) == cast);
		}
	}

	private SupportResurrection()
	{
	}

	private static void prune()
	{
		CLAIMS.entrySet().removeIf(entry -> !entry.getValue().active(entry.getKey()));
	}

	/**
	 * Для выбора трупа: второй ресающий берёт другого павшего.
	 * @return по трупу уже идёт каст реса
	 */
	public static boolean inProgress(Player corpse)
	{
		if (corpse == null)
		{
			return false;
		}
		synchronized (CLAIMS)
		{
			prune();
			return CLAIMS.containsKey(corpse);
		}
	}

	/**
	 * Родной useMagic начинает каст синхронно, поэтому проверка и старт — под одним замком.
	 * Не рес — обычный useMagic.
	 */
	public static boolean useMagic(Player player, Skill skill, Creature target)
	{
		if ((skill.getTargetType() != TargetType.PC_BODY) || !skill.hasEffectType(EffectType.RESURRECTION) || !(target instanceof Player corpse))
		{
			return PhantomCompat.useMagic(player, skill, false, false);
		}
		synchronized (CLAIMS)
		{
			prune();
			if (!corpse.isDead() || corpse.isReviveRequested() || CLAIMS.containsKey(corpse))
			{
				return false;
			}
			final boolean started = PhantomCompat.useMagic(player, skill, false, false);
			final Object cast = PhantomCompat.castingToken(player);
			if (started && player.isCastingNow() && (cast != null) && (PhantomCompat.castingSkillId(player) == skill.getId()))
			{
				CLAIMS.put(corpse, new Claim(new WeakReference<>(player), cast, corpse.getInstanceId()));
			}
			return started;
		}
	}
}
