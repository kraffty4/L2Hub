package org.l2jmobius.gameserver.managers.world;

import static org.l2jmobius.gameserver.managers.world.X10Text.text;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.data.xml.NpcData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.geoengine.GeoEngine;

/** Каталог фарма x10 и ограниченные дополнительные пачки поверх обычных спавнов. */
final class X10FarmSpots
{
	private static final Logger LOGGER = Logger.getLogger(X10FarmSpots.class.getName());
	private record Pack(int npc, int source, Location location, int count) { }
	private static final class Spot
	{
		final String id;
		final String ru;
		final String en;
		final String role;
		final boolean adena;
		final boolean conditional;
		final int min;
		final int max;
		final int maxPlayer;
		final Location entry;
		final List<Pack> packs;
		final List<Spawn> spawns = new ArrayList<>();
		volatile boolean active;
		long absentSince;
		long retryAfter;

		Spot(Element node)
		{
			id = node.getAttribute("id");
			if (!id.matches("[a-z_]+")) { throw new IllegalArgumentException("Invalid farm id"); }
			ru = node.getAttribute("ru");
			en = node.getAttribute("en");
			role = node.getAttribute("role");
			adena = node.getAttribute("kind").equals("adena");
			conditional = Boolean.parseBoolean(node.getAttribute("conditional"));
			min = integer(node, "min");
			max = integer(node, "max");
			maxPlayer = integer(node, "maxPlayer");
			entry = location(node);
			final List<Pack> rows = new ArrayList<>();
			final var children = node.getElementsByTagName("pack");
			if (children.getLength() < 1 || children.getLength() > 12) { throw new IllegalArgumentException("Invalid farm pack count"); }
			for (int n = 0; n < children.getLength(); n++)
			{
				final Element child = (Element) children.item(n);
				final int count = integer(child, "count");
				final int npc = integer(child, "npc");
				if (count < 1 || count > 8 || NpcData.getInstance().getTemplate(npc) == null) { throw new IllegalArgumentException("Invalid farm NPC/count"); }
				rows.add(new Pack(npc, child.hasAttribute("source") ? integer(child, "source") : npc, location(child), count));
			}
			packs = List.copyOf(rows);
		}
	}

	private static volatile List<Spot> spots = List.of();

	static void start()
	{
		if (!L2HubX10Config.FARM_SPOTS) { return; }
		try
		{
			final var factory = DocumentBuilderFactory.newInstance();
			factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
			final var nodes = factory.newDocumentBuilder().parse(Path.of("./data/L2HubX10FarmSpots.xml").toFile()).getDocumentElement().getElementsByTagName("spot");
			final List<Spot> loaded = new ArrayList<>();
			final Set<String> ids = new java.util.HashSet<>();
			for (int n = 0; n < nodes.getLength(); n++)
			{
				final Spot spot = new Spot((Element) nodes.item(n));
				if (!ids.add(spot.id)) { throw new IllegalArgumentException("Duplicate farm id"); }
				loaded.add(spot);
			}
			spots = List.copyOf(loaded);
			tick();
			ThreadPool.scheduleAtFixedRate(X10FarmSpots::tick, 10000, 10000);
			LOGGER.info("X10 farm catalogue loaded: " + spots.size() + " areas; " + spots.stream().mapToInt(spot -> spot.spawns.size()).sum() + " extra monsters active.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.SEVERE, "X10 farm catalogue failed", e);
		}
	}

	private static synchronized void tick()
	{
		final long now = System.currentTimeMillis();
		for (Spot spot : spots)
		{
			try
			{
				final boolean allowed = !spot.conditional || nativePresent(spot);
				if (!allowed)
				{
					if (spot.absentSince == 0) { spot.absentSince = now; }
					if (spot.active && now - spot.absentSince > 180000) { clear(spot); }
					continue;
				}
				spot.absentSince = 0;
				if (spot.active || now < spot.retryAfter) { continue; }
				for (Pack pack : spot.packs)
				{
					for (int n = 0; n < pack.count; n++)
					{
						final double angle = n * Math.PI * 2 / pack.count;
						final Location point = GeoEngine.getInstance().getValidLocation(pack.location.getX(), pack.location.getY(), pack.location.getZ(), pack.location.getX() + (int) (Math.cos(angle) * 35), pack.location.getY() + (int) (Math.sin(angle) * 35), pack.location.getZ(), 0);
						final Spawn spawn = new Spawn(NpcData.getInstance().getTemplate(pack.npc));
						spawn.setXYZ(point.getX(), point.getY(), point.getZ());
						spawn.setAmount(1);
						spawn.setRespawnDelay(25);
						spot.spawns.add(spawn);
						if (spawn.init() != 1) { throw new IllegalStateException("Farm spawn failed"); }
					}
				}
				spot.active = true;
			}
			catch (Exception e)
			{
				clear(spot);
				spot.retryAfter = now + 60000;
				LOGGER.log(Level.WARNING, "X10 farm area failed: " + spot.id, e);
			}
		}
	}

	private static boolean nativePresent(Spot spot)
	{
		for (var object : World.getVisibleObjects())
		{
			if (!(object instanceof Npc npc) || npc.getInstanceId() != 0 || !npc.isMonster() || npc.isAlikeDead() || spot.spawns.contains(npc.getSpawn())) { continue; }
			if (spot.packs.stream().anyMatch(pack -> pack.source == npc.getId() && npc.calculateDistance3D(pack.location) < 1800)) { return true; }
		}
		return false;
	}

	private static void clear(Spot spot)
	{
		spot.active = false;
		for (Spawn spawn : spot.spawns)
		{
			spawn.stopRespawn();
			final Npc npc = spawn.getLastSpawn();
			if (npc != null) { npc.deleteMe(); }
		}
		spot.spawns.clear();
	}

	static String html(Player player, String[] args, String prefix, int width)
	{
		String category = args.length > 1 && args[1].equals("adena") ? "adena" : "recommended";
		int page = args.length > 2 ? number(args[2]) : 0;
		String message = "";
		if (args.length > 2 && args[1].equals("go"))
		{
			category = args.length > 3 && args[3].equals("adena") ? "adena" : "recommended";
			page = args.length > 4 ? number(args[4]) : 0;
			final Spot destination = spots.stream().filter(spot -> spot.id.equals(args[2])).findFirst().orElse(null);
			message = destination == null ? text("Точка не найдена.", "Destination not found.") : !destination.active ? text("Площадка пока не активна. Для особых зон нужен штатный доступ мира.", "This area is not active yet. Special zones require normal world access.") : player.getLevel() > destination.maxPlayer ? text("Вход сюда доступен до уровня ", "Entry is available up to level ") + destination.maxPlayer : X10World.teleport(player, destination.entry, true);
		}
		final boolean adena = category.equals("adena");
		final List<Spot> selected = spots.stream().filter(spot -> spot.adena == adena).sorted(java.util.Comparator.comparingInt(spot -> spot.min)).toList();
		final int pages = Math.max(1, (selected.size() + 3) / 4);
		page = Math.clamp(page, 0, pages - 1);
		final StringBuilder out = new StringBuilder(X10Text.tabs(new String[] {text("Увеличенные споты", "Expanded areas"), text("Усиленные · адена x2", "Stronger · adena x2")}, new String[] {prefix + " spots recommended 0", prefix + " spots adena 0"}, width));
		if (!message.isEmpty()) { out.append("<font color=\"LEVEL\">").append(X10Text.escape(message)).append("</font><br>"); }
		out.append(adena ? text("Усиленные мобы: HP ×2, атака ×1,5, количество адены ×2 поверх рейта сервера.", "Stronger monsters: HP ×2, attack ×1.5, adena amount ×2 on top of server rates.") : text("Обычный дроп и рейты; больше монстров по площади локации.", "Ordinary loot and rates; more monsters spread across the area.")).append("<br><br><table width=").append(width).append(" cellpadding=4>");
		final int fee = player.getLevel() > PlayerConfig.MAX_FREE_TELEPORT_LEVEL ? L2HubWorldConfig.WORLD_DAILY_TELEPORT_FEE : 0;
		for (Spot spot : selected.subList(page * 4, Math.min(selected.size(), page * 4 + 4)))
		{
			out.append("<tr><td width=").append(width - 145).append(" height=50 valign=top><font color=\"LEVEL\">").append(X10Text.escape(text(spot.ru, spot.en))).append("</font><br1>")
				.append(text("Уровни ", "Levels ")).append(spot.min).append(spot.max == spot.min ? "" : "–" + spot.max).append(" · ").append(adena ? text("Адена x2", "Adena x2") : text("Больше мобов", "More monsters"));
			out.append("<br1>").append(spot.maxPlayer < 85 ? text("Вход до ", "Entry up to ") + spot.maxPlayer : "&nbsp;");
			out.append("</td><td width=140 valign=top>");
			if (spot.active) { out.append(X10Text.button(text("Телепорт", "Teleport") + (fee > 0 ? " · " + fee + " A" : ""), prefix + " spots go " + spot.id + " " + category + " " + page, 130)); }
			else { out.append(text("Сейчас закрыто", "Currently closed")); }
			out.append("</td></tr><tr><td height=8></td></tr>");
		}
		for (int empty = Math.min(selected.size(), page * 4 + 4) - page * 4; empty < 4; empty++) { out.append("<tr><td height=50>&nbsp;</td><td></td></tr><tr><td height=8></td></tr>"); }
		out.append("</table>").append(X10Text.pager(page, pages, prefix + " spots " + category + " ", width));
		if (selected.isEmpty()) { out.append(text("Каталог пока недоступен.", "The catalogue is currently unavailable.")); }
		return out.toString();
	}

	private static String tip(String role)
	{
		return switch (role)
		{
			case "spoil" -> text("Охота и сбор ресурсов; удобен для спойлера.", "Hunting and resources; suitable for spoilers.");
			case "undead" -> text("Есть нежить: подходят и классы с навыками против нежити.", "Includes undead: suitable for classes with anti-undead skills too.");
			case "mage" -> text("Вариант для магов; оцени силу мобов перед большим пулом.", "An option for mages; check monster strength before a large pull.");
			case "party" -> text("Плотный групповой фарм; желательно иметь хил и бафф.", "Dense party farming; healing and buffs are recommended.");
			case "conditional" -> text("Особая зона: пачки доступны только при наличии штатных мобов.", "Special area: packs are available only while native monsters are present.");
			default -> text("Соло или небольшая группа по экипировке.", "Solo or a small group, depending on gear.");
		};
	}

	private static int number(String value)
	{
		try { return Integer.parseInt(value); }
		catch (NumberFormatException e) { return 0; }
	}
	private static int integer(Element node, String key) { return Integer.parseInt(node.getAttribute(key)); }
	private static Location location(Element node) { return new Location(integer(node, "x"), integer(node, "y"), integer(node, "z")); }
	private X10FarmSpots() { }
}
