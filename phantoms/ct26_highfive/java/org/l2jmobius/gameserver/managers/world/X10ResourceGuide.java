package org.l2jmobius.gameserver.managers.world;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;

/** Только подтверждённые дропом/спойлом и спавнами точки, подготовленные генератором. */
final class X10ResourceGuide
{
	private record Source(int item, int x, int y)
	{
	}

	private static final List<Source> SOURCES = load();

	private static List<Source> load()
	{
		final List<Source> sources = new ArrayList<>();
		try
		{
			final var factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			final var nodes = factory.newDocumentBuilder().parse(Path.of("./data/L2HubX10Resources.xml").toFile()).getDocumentElement().getElementsByTagName("source");
			for (int i = 0; i < nodes.getLength(); i++)
			{
				final Element node = (Element) nodes.item(i);
				sources.add(new Source(Integer.parseInt(node.getAttribute("item")), Integer.parseInt(node.getAttribute("x")), Integer.parseInt(node.getAttribute("y"))));
			}
		}
		catch (Exception e)
		{
			Logger.getLogger(X10ResourceGuide.class.getName()).log(Level.WARNING, "Material guide unavailable", e);
		}
		return List.copyOf(sources);
	}

	static HuntSpot find(int item, int level)
	{
		return HuntSpotData.getInstance().getSpots().stream()
			.filter(spot -> SOURCES.stream().anyMatch(source -> (source.item == item) && (source.x == spot.entry.getX()) && (source.y == spot.entry.getY())))
			.min(Comparator.comparingInt(spot -> Math.abs(spot.level - level) + (spot.min > level + 3 ? 100 : 0))).orElse(null);
	}

	private X10ResourceGuide()
	{
	}
}
