package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * L2Hub: споты охотников-фантомов из data/L2HubHuntSpots.xml (пишет tools/fake_players.py): группы спавна мобов с узким разбросом уровней.
 */
public class HuntSpotData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(HuntSpotData.class.getName());
	/** Шанс взять спот, который гайды советуют роли пешки, когда такой есть среди подходящих: остальное — разнообразие. */
	public static final int GUIDE_CHANCE = 75;
	private static final Set<String> SUMMONER_CLASSES = Set.of("WARLOCK", "ARCANA_LORD", "ELEMENTAL_SUMMONER", "ELEMENTAL_MASTER", "PHANTOM_SUMMONER", "SPECTRAL_MASTER");

	/**
	 * Спот: уровень мобов (медиана и разброс), регион (папка спавнов), точка входа по геодате и радиус охоты.
	 */
	public static class HuntSpot
	{
		public final int level;
		public final int min;
		public final int max;
		public final String region;
		public final String name;
		public final Location entry;
		public final int radius;
		public final int mobs;
		/** Кому этот спот советуют гайды ({@link #role}; party — зовут пачкой); пусто — гайды о нём молчат. */
		public final Set<String> guide;

		HuntSpot(StatSet set)
		{
			level = set.getInt("level");
			min = set.getInt("min");
			max = set.getInt("max");
			region = set.getString("region");
			name = set.getString("name");
			entry = new Location(set.getInt("x"), set.getInt("y"), set.getInt("z"));
			radius = set.getInt("radius");
			mobs = set.getInt("mobs");
			final String roles = set.getString("guide", "").trim();
			guide = roles.isEmpty() ? Collections.emptySet() : Set.of(roles.split("\\s+"));
		}

		/**
		 * @return гайды советуют спот этой роли
		 */
		public boolean suits(String role)
		{
			return guide.contains(role);
		}

		/**
		 * @return гайды советуют спот другим, а этой роли — нет
		 */
		public boolean against(String role)
		{
			return !guide.isEmpty() && !guide.contains(role);
		}

		@Override
		public String toString()
		{
			return name + " (" + region + ", мобы " + min + "-" + max + ")";
		}
	}

	private volatile List<HuntSpot> _spots = Collections.emptyList();
	private List<HuntSpot> _loading;

	protected HuntSpotData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loading = new ArrayList<>();
		parseDatapackFile("data/L2HubHuntSpots.xml");
		_spots = Collections.unmodifiableList(_loading);
		_loading = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + _spots.size() + " hunting spots.");
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "spot", spotNode -> _loading.add(new HuntSpot(new StatSet(parseAttributes(spotNode))))));
	}

	public List<HuntSpot> getSpots()
	{
		return _spots;
	}

	/**
	 * @param player персонаж
	 * @return роль для спотов из гайдов (атрибут guide): summoner, tank, dagger, archer, healer, mage или fighter
	 */
	public static String role(Player player)
	{
		if (SUMMONER_CLASSES.contains(player.getPlayerClass().name()))
		{
			return "summoner";
		}
		return PhantomBufferSets.classRole(player).toLowerCase();
	}

	/**
	 * Выбор из подходящих спотов с оглядкой на гайды (docs/PHANTOMS.md, 4.147): спот, который советуют его роли, — с шансом {@link #GUIDE_CHANCE} %;
	 * иначе любой, кроме советуемых только другим ролям (маг не идёт туда, куда гайды зовут одних воинов); других нет — любой.
	 * @param choice непустой список
	 */
	public static HuntSpot choose(Player player, List<HuntSpot> choice)
	{
		final String role = role(player);
		final List<HuntSpot> suits = new ArrayList<>();
		final List<HuntSpot> fair = new ArrayList<>();
		for (HuntSpot spot : choice)
		{
			if (spot.suits(role))
			{
				suits.add(spot);
			}
			if (!spot.against(role))
			{
				fair.add(spot);
			}
		}
		final List<HuntSpot> from = !suits.isEmpty() && (Rnd.get(100) < GUIDE_CHANCE) ? suits : !fair.isEmpty() ? fair : choice;
		return from.get(Rnd.get(from.size()));
	}

	public static HuntSpotData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HuntSpotData INSTANCE = new HuntSpotData();
	}
}
