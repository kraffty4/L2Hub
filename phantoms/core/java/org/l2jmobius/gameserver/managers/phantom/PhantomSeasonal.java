package org.l2jmobius.gameserver.managers.phantom;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Folk;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.ScriptManager;
import org.l2jmobius.gameserver.mechanics.script.Quest;

/**
 * L2Hub: охотники на сезонных событиях Mobius (data/scripts/events, календарь L2HubEvents.ini; docs/PHANTOMS.md, раздел 4.34; {@code HunterSeasonalEvents}).<br>
 * NPC событий берутся из {@code <spawnlist><add npc=…>} в config.xml каждого события — для любой хроники без своего списка; событие идёт — его NPC стоят в мире.
 * Охотник в городе подходит к NPC идущего события рядом (у них собирается народ) и, где умеет, делает то же, что живой игрок:
 * <ul>
 * <li>L2 Day — сдаёт слова LINEAGE II, NCSOFT, CHRONICLE из букв с монстров у менеджера ({@code collect_l2}, {@code collect_nc}, {@code collect_ch});</li>
 * <li>Heavy Medal — у кошки Винни «орёл или решка» на следующий значок за блестящие медали ({@code heads}/{@code tails});</li>
 * <li>Gift of Vitality — баф от Стива ({@code vitality}), Freya Celebration — зелье Фрейи за 1 адену ({@code give_potion}), Valentine — рецепт шоколада, если его нет;</li>
 * <li>обмен по мультиселлам NPC (кот Рой — медали на призы, кот Базз — нектар на семена и хроно-оружие и подобные в других хрониках): списки — те, на которые
 * ссылается окно NPC, как его видит этот игрок ({@code onFirstTalk}, одна ссылка {@code Script … talk} — у Роя так выбирается список по уровню значка), меняется
 * только валюта события (складываемые предметы, не адена), самое дорогое из того, на что хватает, до трёх обменов за визит.</li>
 * </ul>
 * Остальное (Санта, посох и свитки мастера Йоги, превращение у гейткипера) — только подойти и постоять.
 */
final class PhantomSeasonal
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSeasonal.class.getName());

	private static final Pattern SPAWN_NPC = Pattern.compile("<add\\s+npc=\"(\\d+)\"");
	/** L2 Day: буквы A, C, E, F, G, H, I, L, N, O, R, S, T, II. */
	private static final int LETTER_FIRST = 3875;
	private static final int LETTER_LAST = 3888;
	private static final String[] L2DAY_WORDS =
	{
		"collect_l2",
		"collect_nc",
		"collect_ch"
	};
	private static final int CAT_WINNIE = 31229;
	private static final int GLITTERING_MEDAL = 6393;
	private static final int EVENT_MEDAL = 6392;
	private static final int FREYA_POTION = 15440;
	private static final int CHOCOLATE_RECIPE = 20191;
	private static final String VITALITY_REUSE = "GiftOfVitality_reuse";
	private static final int ADENA = 57;

	/** Мультиселлы: ссылка в окне NPC, «Script событие talk» — показать окно по игроку, разметка списка. */
	private static final Pattern MULTISELL_LINK = Pattern.compile("_multisell (\\d+)");
	private static final Pattern TALK_LINK = Pattern.compile("(?:Script|Quest) (\\w+) talk\"");
	private static final Pattern COMMENT = Pattern.compile("<!--.*?-->", Pattern.DOTALL);
	private static final Pattern ENTRY = Pattern.compile("<item\\b[^>]*>(.*?)</item>", Pattern.DOTALL);
	private static final Pattern PART = Pattern.compile("<(ingredient|production)\\s([^>]*?)/?>");
	private static final Pattern ATTR = Pattern.compile("(\\w+)=\"([^\"]*)\"");
	/** Обменов за визит. */
	private static final int TRADES_PER_VISIT = 3;

	/** Позиция мультиселла: ингредиенты {id, count, остаётся ли (1/0)}, товары {id, count, шанс в сотых процента; 0 — без шанса}. */
	private record Entry(List<int[]> ingredients, List<int[]> products)
	{
	}

	/** NPC события → папка события (имя скрипта). */
	private static Map<Integer, String> _npcs;
	/** Папка события → его имя из config.xml ({@code <event name=…>}) — для лаунчера. */
	private static final Map<String, String> TITLES = new ConcurrentHashMap<>();
	private static final Pattern EVENT_NAME = Pattern.compile("<event\\s+name=\"([^\"]+)\"");
	/** id мультиселла → файл (data/multisell и подпапки), один раз. */
	private static Map<Integer, File> _multisellFiles;
	/** id мультиселла → позиции. */
	private static final Map<Integer, List<Entry>> MULTISELLS = new ConcurrentHashMap<>();

	private PhantomSeasonal()
	{
	}

	/**
	 * NPC событий из data/scripts/events/&lt;событие&gt;/config.xml ({@code <spawnlist>}), один раз.
	 */
	private static synchronized Map<Integer, String> npcs()
	{
		if (_npcs != null)
		{
			return _npcs;
		}
		final Map<Integer, String> npcs = new HashMap<>();
		final File[] events = new File("./data/scripts/events").listFiles(File::isDirectory);
		if (events != null)
		{
			for (File event : events)
			{
				final File config = new File(event, "config.xml");
				if (!config.isFile())
				{
					continue;
				}
				try
				{
					final String xml = Files.readString(config.toPath(), StandardCharsets.UTF_8);
					final Matcher title = EVENT_NAME.matcher(xml);
					if (title.find())
					{
						TITLES.put(event.getName(), title.group(1));
					}
					final int list = xml.indexOf("<spawnlist");
					if (list < 0)
					{
						continue;
					}
					final Matcher matcher = SPAWN_NPC.matcher(xml.substring(list));
					while (matcher.find())
					{
						npcs.putIfAbsent(Integer.parseInt(matcher.group(1)), event.getName());
					}
				}
				catch (Exception e)
				{
					LOGGER.warning(PhantomSeasonal.class.getSimpleName() + ": " + config + ": " + e.getMessage());
				}
			}
		}
		LOGGER.info(PhantomSeasonal.class.getSimpleName() + ": " + npcs.size() + " event NPC ids from data/scripts/events.");
		_npcs = npcs;
		return npcs;
	}

	/**
	 * @param player охотник в городе
	 * @param range не дальше
	 * @return ближайший NPC идущего события (с ним можно говорить — не украшение) или {@code null}
	 */
	static Npc nearestEventNpc(Player player, int range)
	{
		final Map<Integer, String> npcs = npcs();
		Npc nearest = null;
		double best = Double.MAX_VALUE;
		for (Npc npc : World.getVisibleObjectsInRange(player, Npc.class, range))
		{
			if (!(npc instanceof Folk) || !npcs.containsKey(npc.getId()) || !npc.isSpawned())
			{
				continue;
			}
			// Событие, где охотнику есть что сдать, — в четыре раза «ближе»: с буквами — к менеджеру L2 Day, а не к ближайшему из мастеров Йоги (прогон H5 2026-10-22).
			final double distance = player.calculateDistance2D(npc) * (useful(player, npcs.get(npc.getId())) ? 0.25 : 1);
			if (distance < best)
			{
				best = distance;
				nearest = npc;
			}
		}
		return nearest;
	}

	/**
	 * @return событие этого NPC (папка в data/scripts/events)
	 */
	static String eventOf(Npc npc)
	{
		return npcs().get(npc.getId());
	}

	/**
	 * @return имя события этого NPC для людей (из config.xml), нет — папка
	 */
	static String titleOf(Npc npc)
	{
		final String event = eventOf(npc);
		return event == null ? "" : TITLES.getOrDefault(event, event);
	}

	/**
	 * Сделать у NPC события то, что умеет охотник, тем же путём, что окно живого игрока ({@code notifyEvent} скрипта события).
	 * @return что сделал — для лога; {@code null} — только постоял
	 */
	static String act(Player player, Npc npc)
	{
		final String event = eventOf(npc);
		final Quest script = event == null ? null : ScriptManager.getInstance().getScript(event);
		if (script == null)
		{
			return null;
		}

		final List<String> done = new ArrayList<>();
		final String special = special(player, npc, script, event);
		if (special != null)
		{
			done.add(special);
		}
		final String traded = exchange(player, npc, script, event);
		if (traded != null)
		{
			done.add(traded);
		}
		return done.isEmpty() ? null : String.join("; ", done);
	}

	/**
	 * Своё у NPC события — тем же путём, что кнопка в окне живого игрока ({@code notifyEvent} скрипта события).
	 * @return что сделал — для лога; {@code null} — ничего
	 */
	private static String special(Player player, Npc npc, Quest script, String event)
	{
		switch (event)
		{
			case "L2Day":
			{
				int words = 0;
				for (String word : L2DAY_WORDS)
				{
					// Слово можно сдать несколько раз, пока хватает букв; не больше трёх за визит.
					for (int i = 0; i < 3; i++)
					{
						final long before = letters(player);
						script.notifyEvent(word, npc, player);
						if (letters(player) >= before)
						{
							break;
						}
						words++;
					}
				}
				return words > 0 ? "turned in " + words + " words" : null;
			}
			case "HeavyMedal":
			{
				if (npc.getId() != CAT_WINNIE)
				{
					return null;
				}
				final long before = player.getInventory().getInventoryItemCount(GLITTERING_MEDAL, -1);
				script.notifyEvent(Rnd.nextBoolean() ? "heads" : "tails", npc, player);
				final long spent = before - player.getInventory().getInventoryItemCount(GLITTERING_MEDAL, -1);
				return spent > 0 ? "played heads or tails for a badge (" + spent + " glittering medals)" : null;
			}
			case "GiftOfVitality":
			{
				final long before = player.getVariables().getLong(VITALITY_REUSE, 0);
				script.notifyEvent("vitality", npc, player);
				return player.getVariables().getLong(VITALITY_REUSE, 0) != before ? "got Gift of Vitality" : null;
			}
			case "FreyaCelebration":
			{
				final long before = count(player, FREYA_POTION);
				script.notifyEvent("give_potion", npc, player);
				return count(player, FREYA_POTION) > before ? "got Freya's potion" : null;
			}
			case "TheValentineEvent":
			{
				if (count(player, CHOCOLATE_RECIPE) > 0)
				{
					return null;
				}
				script.notifyEvent("4301-3.htm", npc, player);
				return count(player, CHOCOLATE_RECIPE) > 0 ? "got a chocolate recipe" : null;
			}
			default:
			{
				return null;
			}
		}
	}

	// ---------- Обмен по мультиселлам ----------

	/**
	 * Обмен у NPC события: валюта события (складываемые предметы, не адена) — на самое дорогое, на что хватает, до {@value #TRADES_PER_VISIT} раз.
	 * @return что выменял — для лога; {@code null} — нечего или не на что
	 */
	private static String exchange(Player player, Npc npc, Quest script, String event)
	{
		final List<Entry> entries = new ArrayList<>();
		for (int list : multisellsOf(player, npc, script, event))
		{
			entries.addAll(entries(list));
		}

		final List<String> trades = new ArrayList<>();
		String prize = null;
		for (int i = 0; i < TRADES_PER_VISIT; i++)
		{
			Entry best = null;
			long bestCost = 0;
			for (Entry entry : entries)
			{
				final long cost = affordable(player, entry);
				if (cost > bestCost)
				{
					best = entry;
					bestCost = cost;
				}
			}
			final String[] trade = best == null ? null : trade(player, npc, best);
			if (trade == null)
			{
				break;
			}
			trades.add(trade[0]);
			if (prize == null)
			{
				prize = trade[1];
			}
		}
		if (trades.isEmpty())
		{
			return null;
		}
		if ((prize != null) && (Rnd.get(100) < 20))
		{
			PhantomChat.getInstance().hunterShout(player, Rnd.nextBoolean() ? "выменял у " + npc.getName() + " " + prize + ")" : prize + " за ивент, норм)");
		}
		return String.join(", ", trades);
	}

	/**
	 * Списки мультиселлов из окна NPC — как его видит этот игрок: {@code onFirstTalk} (нет своего — data/html/default) и одна ссылка {@code Script <событие> talk}.
	 */
	private static Set<Integer> multisellsOf(Player player, Npc npc, Quest script, String event)
	{
		final StringBuilder html = new StringBuilder(page(event, npc, script.onFirstTalk(npc, player)));
		final Matcher talk = TALK_LINK.matcher(html);
		if (talk.find() && talk.group(1).equals(script.getName()))
		{
			html.append(page(event, npc, script.onEvent("talk", npc, player)));
		}
		final Set<Integer> lists = new LinkedHashSet<>();
		final Matcher link = MULTISELL_LINK.matcher(html);
		while (link.find())
		{
			lists.add(Integer.parseInt(link.group(1)));
		}
		return lists;
	}

	/**
	 * Окно по ответу скрипта: имя файла — из папки события, пусто — data/html/default/&lt;NPC&gt;.htm, иначе сам ответ.
	 */
	private static String page(String event, Npc npc, String result)
	{
		final File file;
		if ((result == null) || result.isEmpty())
		{
			file = new File("./data/html/default/" + npc.getId() + ".htm");
		}
		else if (result.endsWith(".htm") || result.endsWith(".html"))
		{
			file = new File("./data/scripts/events/" + event + "/" + result);
		}
		else
		{
			return result;
		}
		return read(file);
	}

	/** Файл целиком; нужны только числа и латиница разметки — ISO-8859-1 читает любую кодировку без ошибок. */
	private static String read(File file)
	{
		try
		{
			return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.ISO_8859_1) : "";
		}
		catch (Exception e)
		{
			return "";
		}
	}

	/**
	 * Позиции мультиселла: разметка data/multisell (одна на все хроники Mobius), один раз на список.
	 */
	private static List<Entry> entries(int list)
	{
		return MULTISELLS.computeIfAbsent(list, id ->
		{
			final List<Entry> entries = new ArrayList<>();
			final File file = multisellFiles().get(id);
			if (file == null)
			{
				return entries;
			}
			final Matcher entry = ENTRY.matcher(COMMENT.matcher(read(file)).replaceAll(""));
			while (entry.find())
			{
				final List<int[]> ingredients = new ArrayList<>();
				final List<int[]> products = new ArrayList<>();
				final Matcher part = PART.matcher(entry.group(1));
				while (part.find())
				{
					final Map<String, String> attrs = new HashMap<>();
					final Matcher attr = ATTR.matcher(part.group(2));
					while (attr.find())
					{
						attrs.put(attr.group(1), attr.group(2));
					}
					try
					{
						final int itemId = Integer.parseInt(attrs.get("id"));
						final int count = (int) Math.min(Integer.MAX_VALUE, Long.parseLong(attrs.getOrDefault("count", "1")));
						if ("ingredient".equals(part.group(1)))
						{
							ingredients.add(new int[]
							{
								itemId,
								count,
								Boolean.parseBoolean(attrs.get("maintainIngredient")) ? 1 : 0
							});
						}
						else
						{
							products.add(new int[]
							{
								itemId,
								count,
								(int) Math.round(Double.parseDouble(attrs.getOrDefault("chance", "0")) * 100)
							});
						}
					}
					catch (Exception e)
					{
						// Не число — позиция не для охотника.
						ingredients.add(new int[]
						{
							0,
							Integer.MAX_VALUE,
							0
						});
					}
				}
				if (!ingredients.isEmpty() && !products.isEmpty())
				{
					entries.add(new Entry(ingredients, products));
				}
			}
			return entries;
		});
	}

	/**
	 * Файлы мультиселлов по id, один раз: data/multisell и подпапки; одноимённый из custom — поверх основного, как у Mobius.
	 */
	private static synchronized Map<Integer, File> multisellFiles()
	{
		if (_multisellFiles != null)
		{
			return _multisellFiles;
		}
		final Map<Integer, File> files = new HashMap<>();
		try (Stream<Path> paths = Files.walk(Path.of("./data/multisell")))
		{
			paths.filter(path -> path.toString().endsWith(".xml")).forEach(path ->
			{
				final String name = path.getFileName().toString();
				try
				{
					final int id = Integer.parseInt(name.substring(0, name.length() - 4));
					if ("custom".equals(path.getParent().getFileName().toString()))
					{
						files.put(id, path.toFile());
					}
					else
					{
						files.putIfAbsent(id, path.toFile());
					}
				}
				catch (NumberFormatException e)
				{
					// Не список по номеру.
				}
			});
		}
		catch (Exception e)
		{
			LOGGER.warning(PhantomSeasonal.class.getSimpleName() + ": data/multisell: " + e.getMessage());
		}
		_multisellFiles = files;
		return files;
	}

	/**
	 * @return цена позиции в валюте события (сумма ингредиентов) — хватает и годится; 0 — нет: ингредиент — адена, не складывается или его мало;
	 *         расходовать нечего; несложенный товар уже есть
	 */
	private static long affordable(Player player, Entry entry)
	{
		long cost = 0;
		boolean spends = false;
		for (int[] ingredient : entry.ingredients())
		{
			final ItemTemplate template = ingredient[0] > 0 ? ItemData.getInstance().getTemplate(ingredient[0]) : null;
			if ((template == null) || (ingredient[0] == ADENA) || !template.isStackable() || (count(player, ingredient[0]) < ingredient[1]))
			{
				return 0;
			}
			cost += ingredient[1];
			spends |= ingredient[2] == 0;
		}
		for (int[] product : entry.products())
		{
			final ItemTemplate template = ItemData.getInstance().getTemplate(product[0]);
			if ((template == null) || (!template.isStackable() && (count(player, product[0]) > 0)))
			{
				return 0;
			}
		}
		return spends ? cost : 0;
	}

	/**
	 * Обмен: ингредиенты (кроме остающихся) — из сумки, товары — в сумку; у товаров с шансом — один по шансу (как в мультиселлах Classic и Essence).
	 * @return {строка лога, имя первого товара}; {@code null} — не вышло
	 */
	private static String[] trade(Player player, Npc npc, Entry entry)
	{
		final List<String> paid = new ArrayList<>();
		for (int[] ingredient : entry.ingredients())
		{
			if (ingredient[2] != 0)
			{
				continue;
			}
			if (player.getInventory().destroyItemByItemId(ItemProcessType.FEE, ingredient[0], ingredient[1], player, npc) == null)
			{
				return null;
			}
			paid.add(ingredient[1] + " " + ItemData.getInstance().getTemplate(ingredient[0]).getName());
		}

		final List<int[]> products = pick(entry.products());
		final List<String> got = new ArrayList<>();
		for (int[] product : products)
		{
			player.getInventory().addItem(ItemProcessType.BUY, product[0], product[1], player, npc);
			got.add(product[1] + " " + ItemData.getInstance().getTemplate(product[0]).getName());
		}
		return new String[]
		{
			"traded " + String.join(" + ", paid) + " for " + (got.isEmpty() ? "nothing (chance)" : String.join(" + ", got)) + " at " + npc.getName(),
			products.isEmpty() ? null : ItemData.getInstance().getTemplate(products.get(0)[0]).getName()
		};
	}

	/** Товары без шанса — все; с шансом — один из них по шансу (сумма меньше 100% — может не выпасть ничего). */
	private static List<int[]> pick(List<int[]> products)
	{
		int total = 0;
		for (int[] product : products)
		{
			total += product[2];
		}
		if (total <= 0)
		{
			return products;
		}
		int roll = Rnd.get(Math.max(10000, total));
		for (int[] product : products)
		{
			roll -= product[2];
			if (roll < 0)
			{
				return List.of(product);
			}
		}
		return List.of();
	}

	/**
	 * @return у охотника есть что сдать на этом событии: буквы L2 Day, медали Heavy Medal (5 — столько берёт и Рой, и Винни на первый значок)
	 */
	private static boolean useful(Player player, String event)
	{
		switch (event)
		{
			case "L2Day":
			{
				return letters(player) > 0;
			}
			case "HeavyMedal":
			{
				return (count(player, EVENT_MEDAL) >= 5) || (count(player, GLITTERING_MEDAL) >= 5);
			}
			default:
			{
				return false;
			}
		}
	}

	private static long count(Player player, int itemId)
	{
		return player.getInventory().getInventoryItemCount(itemId, -1);
	}

	private static long letters(Player player)
	{
		long count = 0;
		for (int id = LETTER_FIRST; id <= LETTER_LAST; id++)
		{
			count += player.getInventory().getInventoryItemCount(id, -1);
		}
		return count;
	}
}
