package org.l2jmobius.gameserver.managers.phantom;

import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: первая профа охотника настоящим квестом (ключ {@code HunterProfessionQuests}, docs/PHANTOMS.md, раздел 4.152). Пока сделано только на High Five
 * ({@code phantoms/ct26_highfive/java}); здесь заглушка для остальных хроник: квестов нет, профа — прежним путём.
 * @author L2Hub
 */
public final class PhantomProfessionQuest
{
	private PhantomProfessionQuest()
	{
	}

	static boolean holds(Player player, PhantomTemplate template)
	{
		return false;
	}

	static boolean tick(Player player, PhantomTemplate template, long now)
	{
		return false;
	}
}
