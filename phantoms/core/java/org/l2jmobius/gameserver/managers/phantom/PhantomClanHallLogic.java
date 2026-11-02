package org.l2jmobius.gameserver.managers.phantom;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;

/**
 * L2Hub: кланхолл клана охотников (docs/PHANTOMS.md, 4.108, ключ {@code HunterClanHall}). Механика холлов у хроник разная: на Classic и Essence нет {@code ClanHallTable}, на Interlude адена в {@code int}. Поэтому работа — в
 * {@code PhantomClanHall} хроники ({@code phantoms/<id>/java}), у High Five и Gracia Epilogue; у других хроник класса нет, ключ там ничего не делает.
 */
interface PhantomClanHallLogic
{
	/**
	 * Раз в минуту из {@code PhantomHunterClans.life}: ставка, функции, долг.
	 * @param leader глава в мире
	 */
	void life(Clan clan, Player leader, long now);

	/**
	 * Городские дела охотника: взнос в казну клана, настоящей передачей на склад.
	 * @param done что сделал — для лога
	 */
	void town(Player player, PhantomTemplate template, List<String> done);

	/**
	 * @return для {@code .clans}: что с холлом у клана, с запятой впереди; пусто — нечего сказать
	 */
	String note(Clan clan);

	void selfTest(List<Player> hunters);

	/** Логика этой хроники. */
	PhantomClanHallLogic INSTANCE = load();

	private static PhantomClanHallLogic load()
	{
		try
		{
			final var constructor = Class.forName(PhantomClanHallLogic.class.getPackageName() + ".PhantomClanHall").getDeclaredConstructor();
			constructor.setAccessible(true);
			return (PhantomClanHallLogic) constructor.newInstance();
		}
		catch (ClassNotFoundException e)
		{
			return new PhantomClanHallLogic()
			{
				@Override
				public void life(Clan clan, Player leader, long now)
				{
				}

				@Override
				public void town(Player player, PhantomTemplate template, List<String> done)
				{
				}

				@Override
				public String note(Clan clan)
				{
					return "";
				}

				@Override
				public void selfTest(List<Player> hunters)
				{
					Logger.getLogger(PhantomClanHallLogic.class.getName()).warning("PhantomClanHallLogic: SelfTest CLANHALL: no clan hall logic in this chronicle.");
				}
			};
		}
		catch (ReflectiveOperationException e)
		{
			Logger.getLogger(PhantomClanHallLogic.class.getName()).log(Level.SEVERE, "PhantomClanHallLogic: " + e.getMessage(), e);
			throw new IllegalStateException(e);
		}
	}
}
