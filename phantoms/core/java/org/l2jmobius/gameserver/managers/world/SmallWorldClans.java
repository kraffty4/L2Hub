package org.l2jmobius.gameserver.managers.world;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompat;

/**
 * L2Hub: кланы малого мира ({@code SmallWorldClans} в L2HubWorld.ini, docs/PHANTOMS.md, раздел 4.50). Клану на 6–11-й уровень хватает
 * {@code SmallWorldClanMembers} членов вместо официальных 30, 50, 80, 120, 140 и 170: на сервере сотня охотников, а не тысячи игроков.
 * Только снижает: меньшее значение из Feature.ini остаётся. У Classic и Essence уровни выше 5-го — за клановый опыт (ClanLevelData), членов там
 * Mobius не считает — ничего не меняет.<br>
 * Ключи Mobius {@code ClanLevelNRequirement} в Feature.ini не трогает — меняет прочитанное при старте. Перечитать конфиги Mobius на ходу
 * (//reload) — до перезапуска вернутся значения Feature.ini.
 */
public final class SmallWorldClans
{
	private static final Logger LOGGER = Logger.getLogger(SmallWorldClans.class.getName());

	/** Первый уровень клана в {@code SmallWorldClanMembers}: до него Mobius числа членов не требует. */
	private static final int FIRST_LEVEL = 6;

	private SmallWorldClans()
	{
	}

	/**
	 * Вызывается при старте ({@link WorldDaily#start}), после чтения L2HubWorld.ini.
	 */
	public static void apply()
	{
		if (!L2HubWorldConfig.SMALL_WORLD_CLANS)
		{
			return;
		}
		if (PhantomCompat.clanLevelByExp())
		{
			LOGGER.info(SmallWorldClans.class.getSimpleName() + ": clan levels above 5 come from clan EXP (ClanLevelData), Feature.ini member requirements are not used — nothing to change.");
			return;
		}

		final List<String> changed = new ArrayList<>();
		final int[] members = L2HubWorldConfig.SMALL_WORLD_CLAN_MEMBERS;
		for (int i = 0; i < members.length; i++)
		{
			final int level = FIRST_LEVEL + i;
			try
			{
				// Поле — по имени: у Interlude и Essence клан растёт до 10-го уровня, поля 11-го там нет.
				final Field field = FeatureConfig.class.getField("CLAN_LEVEL_" + level + "_REQUIREMENT");
				final int was = field.getInt(null);
				if (members[i] < was)
				{
					field.setInt(null, members[i]);
					changed.add(level + ": " + was + " -> " + members[i]);
				}
			}
			catch (NoSuchFieldException e)
			{
				// Такого уровня в хронике нет.
			}
			catch (IllegalAccessException e)
			{
				LOGGER.warning(SmallWorldClans.class.getSimpleName() + ": clan level " + level + " requirement not changed: " + e.getMessage());
			}
		}
		LOGGER.info(SmallWorldClans.class.getSimpleName() + ": clan members per level " + (changed.isEmpty() ? "as in Feature.ini" : String.join(", ", changed)) + ".");
	}
}
