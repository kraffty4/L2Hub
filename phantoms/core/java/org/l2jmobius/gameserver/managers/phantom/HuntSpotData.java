package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * L2Hub: споты охотников-фантомов из data/L2HubHuntSpots.xml (пишет tools/fake_players.py): группы спавна мобов с узким разбросом уровней.
 */
public class HuntSpotData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(HuntSpotData.class.getName());

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

	public static HuntSpotData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final HuntSpotData INSTANCE = new HuntSpotData();
	}
}
