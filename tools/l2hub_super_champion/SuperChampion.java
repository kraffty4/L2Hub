/*
 * This file is part of the L2J Mobius project.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.l2jmobius.gameserver.entity.actor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.ChampionMonstersConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalVisualEffect;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.network.serverpackets.ExShowScreenMessage;

/**
 * L2Hub: суперчемпион — третий уровень чемпионов над красными, мини-РБ (ключи SuperChampion* в config/Custom/ChampionMonsters.ini).<br>
 * Уровень — {@link ChampionMonstersConfig#CHAMPION_SUPER} (тот же Tier, что у красных и синих); здесь — то, чего у них нет:
 * визуальные эффекты над моделью ({@link #onChampion}, из Attackable.setChampion), объявление при появлении ({@link #announce},
 * из места, где спавн бросает шанс чемпиона) и список живых суперчемпионов для охотников-фантомов ({@link #alive}).<br>
 * Класс общий для хроник Mobius; в файлы Mobius врезаны только вызовы (tools/l2hub_super_champion.py).
 * @author L2Hub
 */
public class SuperChampion
{
	private static final Logger LOGGER = Logger.getLogger(SuperChampion.class.getName());

	/** Суперчемпионы с наложенными эффектами (objectId → моб и его эффекты): снять при следующем spawn, найти для охотников. */
	private static final Map<Integer, Attackable> SUPER = new ConcurrentHashMap<>();
	private static final Map<Integer, AbnormalVisualEffect[]> EFFECTS = new ConcurrentHashMap<>();

	private SuperChampion()
	{
	}

	/**
	 * @param tier уровень чемпиона или null
	 * @return это уровень суперчемпиона (сравнение по ссылке: после перечитывания конфига прежние мобы — уже не супер)
	 */
	public static boolean isSuper(ChampionMonstersConfig.Tier tier)
	{
		return (tier != null) && (tier == ChampionMonstersConfig.CHAMPION_SUPER);
	}

	/**
	 * @param creature кто угодно
	 * @return суперчемпион
	 */
	public static boolean isSuper(Creature creature)
	{
		return (creature != null) && isSuper(creature.getChampionTier());
	}

	/**
	 * Из Attackable.setChampion: снять эффекты прежнего суперчемпиона и наложить новые, если моб стал суперчемпионом.
	 * @param npc моб
	 * @param tier новый уровень чемпиона или null
	 */
	public static void onChampion(Attackable npc, ChampionMonstersConfig.Tier tier)
	{
		final AbnormalVisualEffect[] old = EFFECTS.remove(npc.getObjectId());
		if (old != null)
		{
			npc.stopAbnormalVisualEffect(true, old);
		}
		if (!isSuper(tier))
		{
			SUPER.remove(npc.getObjectId());
			return;
		}

		SUPER.put(npc.getObjectId(), npc);
		final AbnormalVisualEffect[] effects = ChampionMonstersConfig.SUPER_CHAMPION_EFFECTS;
		if (effects.length > 0)
		{
			EFFECTS.put(npc.getObjectId(), effects);
			npc.startAbnormalVisualEffect(true, effects);
		}
	}

	/**
	 * Объявление о появлении суперчемпиона (SuperChampionAnnounce: Region — игрокам в том же регионе карты, World — всем, Off — нет)
	 * и строка в лог сервера.
	 * @param npc только что ставший суперчемпионом моб
	 */
	public static void announce(Attackable npc)
	{
		final String town = MapRegionData.getInstance().getClosestTownName(npc);
		final String text = ChampionMonstersConfig.SUPER_CHAMPION_ANNOUNCE_TEXT.replace("%name%", npc.getName()).replace("%level%", String.valueOf(npc.getLevel())).replace("%town%", town);
		LOGGER.info("SuperChampion: " + npc.getName() + " (" + npc.getLevel() + ") at " + npc.getX() + ", " + npc.getY() + ", " + npc.getZ() + " — " + text);
		final String scope = ChampionMonstersConfig.SUPER_CHAMPION_ANNOUNCE;
		final boolean world = "World".equalsIgnoreCase(scope);
		if (!world && !"Region".equalsIgnoreCase(scope))
		{
			return;
		}

		final CreatureSay say = new CreatureSay(null, ChatType.ANNOUNCEMENT, "", text);
		final ExShowScreenMessage screen = new ExShowScreenMessage(text, 10000);
		say.sendInBroadcast();
		screen.sendInBroadcast();
		final int region = MapRegionData.getInstance().getMapRegionLocId(npc);
		for (Player player : World.getPlayers())
		{
			if (player.isOnline() && (world || (MapRegionData.getInstance().getMapRegionLocId(player) == region)))
			{
				player.sendPacket(say);
				player.sendPacket(screen);
			}
		}
	}

	/**
	 * @return живые суперчемпионы в мире — для охотников-фантомов (собраться на мини-РБ)
	 */
	public static List<Attackable> alive()
	{
		final List<Attackable> result = new ArrayList<>();
		for (Attackable npc : SUPER.values())
		{
			if (isSuper(npc) && npc.isSpawned() && !npc.isDead())
			{
				result.add(npc);
			}
		}
		return result;
	}
}
