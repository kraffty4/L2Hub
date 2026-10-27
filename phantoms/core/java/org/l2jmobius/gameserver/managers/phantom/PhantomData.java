package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.w3c.dom.Document;

import org.l2jmobius.commons.util.IXmlReader;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.util.StatSet;

/**
 * L2Hub: напарники-фантомы из data/L2HubPhantoms.xml (пишет tools/fake_players.py).
 */
public class PhantomData implements IXmlReader
{
	private static final Logger LOGGER = Logger.getLogger(PhantomData.class.getName());

	private volatile Map<String, PhantomTemplate> _templates = Collections.emptyMap();
	private Map<String, PhantomTemplate> _loading;

	protected PhantomData()
	{
		load();
	}

	@Override
	public synchronized void load()
	{
		_loading = new LinkedHashMap<>();
		parseDatapackFile("data/L2HubPhantoms.xml");
		_templates = Collections.unmodifiableMap(_loading);
		_loading = null;
		LOGGER.info(getClass().getSimpleName() + ": Loaded " + getCompanions().size() + " phantom companions and " + getHunters().size() + " hunters.");
	}

	@Override
	public void parseDocument(Document document, File file)
	{
		forEach(document, "list", listNode -> forEach(listNode, "phantom", phantomNode ->
		{
			final StatSet set = new StatSet(parseAttributes(phantomNode));
			final String name = set.getString("name");
			try
			{
				final String[] classIds = set.getString("classes").split(",");
				final int[] classes = new int[classIds.length];
				for (int i = 0; i < classIds.length; i++)
				{
					classes[i] = Integer.parseInt(classIds[i].strip());
					if (PlayerTemplateData.getInstance().getTemplate(classes[i]) == null)
					{
						throw new IllegalArgumentException("нет шаблона класса " + classes[i]);
					}
				}

				final List<PhantomTemplate.Gear> gear = new ArrayList<>();
				forEach(phantomNode, "gear", gearNode ->
				{
					final StatSet g = new StatSet(parseAttributes(gearNode));
					gear.add(new PhantomTemplate.Gear(g.getString("grade"), g.getInt("minLevel"), g.getInt("rhand", 0), g.getInt("lhand", 0), g.getInt("head", 0), g.getInt("chest", 0), g.getInt("legs", 0), g.getInt("gloves", 0), g.getInt("feet", 0), g.getInt("shots", 0), g.getInt("neck", 0), g.getInt("ear", 0), g.getInt("ring", 0), g.getInt("shirt", 0)));
				});

				final PhantomTemplate template = new PhantomTemplate(name, PhantomTemplate.Role.valueOf(set.getString("role")), set.getBoolean("female"), (byte) set.getInt("face"), (byte) set.getInt("hairStyle"), (byte) set.getInt("hairColor"), classes, new Location(set.getInt("x"), set.getInt("y"), set.getInt("z")), gear, set.getString("aliases", ""), set.getString("kind", "companion"), set.getFloat("social", 1));
				// Вспыльчивость (патч 0037): «сорян» или «пнх» в ответ на предъяву за моба.
				template.setTemper(set.getFloat("temper", 1));
				// Риск («Разум 2.0», шаг 6): осторожный или рисковый — ошибки по характеру.
				template.setRisk(set.getFloat("risk", 1));
				_loading.put(name.toLowerCase(), template);
			}
			catch (Exception e)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Skipped phantom " + name + ": " + e.getMessage());
			}
		}));
	}

	/**
	 * @param name имя напарника (без учёта регистра)
	 * @return напарник или {@code null}
	 */
	public PhantomTemplate getTemplate(String name)
	{
		return name == null ? null : _templates.get(name.toLowerCase());
	}

	/**
	 * @return все фантомы: напарники и охотники
	 */
	public Collection<PhantomTemplate> getTemplates()
	{
		return _templates.values();
	}

	/**
	 * @return напарники (их зовут через .buddy)
	 */
	public List<PhantomTemplate> getCompanions()
	{
		return filter(false);
	}

	/**
	 * @return охотники (живут сами, этап 5)
	 */
	public List<PhantomTemplate> getHunters()
	{
		return filter(true);
	}

	/**
	 * @return Танатос (раздел 4.45) или {@code null}, если его нет в L2HubPhantoms.xml
	 */
	public PhantomTemplate getReaper()
	{
		for (PhantomTemplate template : _templates.values())
		{
			if (template.isReaper())
			{
				return template;
			}
		}
		return null;
	}

	private List<PhantomTemplate> filter(boolean hunters)
	{
		final List<PhantomTemplate> result = new ArrayList<>();
		for (PhantomTemplate template : _templates.values())
		{
			if ((template.isHunter() == hunters) && !template.isReaper())
			{
				result.add(template);
			}
		}
		return result;
	}

	public static PhantomData getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomData INSTANCE = new PhantomData();
	}
}
