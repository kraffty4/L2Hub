package org.l2jmobius.gameserver.managers.phantom;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.logging.Logger;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.l2jmobius.gameserver.entity.actor.Player;

/** Метаданные готового банка: речевая роль не выводится из пола аватара. */
public final class LocalDialogueBindings
{
	private static final Logger LOG = Logger.getLogger(LocalDialogueBindings.class.getName());
	private static final Path FILE = Path.of("data/LocalDialogueBindings.xml");
	record SceneRule(String scope, Map<String, String> voices) { }
	record Rules(boolean loaded, Map<String, SceneRule> scenes, Map<String, String> lines, Map<String, String> openers) { }
	private static volatile Rules current = new Rules(false, Map.of(), Map.of(), Map.of());
	private static long nextCheck, modified = -1;

	private LocalDialogueBindings() { }
	private static String key(String file, String id) { return file + "#" + id; }
	private static String file(Path path) { return path.getFileName().toString(); }

	/** Виртуальная речевая личность v1: постоянный character ID, не текущий вид/класс/пати. */
	static String voice(int objectId, String name)
	{
		int x = objectId > 0 ? objectId : name.hashCode();
		x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
		return (x & 1) == 0 ? "male" : "female";
	}
	static String voice(Player actor) { return voice(actor.getObjectId(), actor.getName()); }
	private static boolean accepts(String required, String actual) { return required == null || required.equals("either") || required.equals(actual); }
	private static void validVoice(String value)
	{
		if (!List.of("male", "female", "either").contains(value)) throw new IllegalArgumentException("Unknown speech identity: " + value);
	}
	static Rules read(Path path) throws Exception
	{
		var factory = DocumentBuilderFactory.newInstance();
		factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
		factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
		factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
		factory.setXIncludeAware(false); factory.setExpandEntityReferences(false);
		Element root = factory.newDocumentBuilder().parse(path.toFile()).getDocumentElement();
		if (!root.getTagName().equals("bindings") || !root.getAttribute("format").equals("1")) throw new IllegalArgumentException("Unknown bindings format");
		Map<String, SceneRule> scenes = new HashMap<>(); Map<String, String> lines = new HashMap<>(), openers = new HashMap<>();
		for (Node node = root.getFirstChild(); node != null; node = node.getNextSibling())
		{
			if (!(node instanceof Element e)) continue;
			if (e.getTagName().equals("scene"))
			{
				Map<String, String> voices = new LinkedHashMap<>();
				for (Node child = e.getFirstChild(); child != null; child = child.getNextSibling()) if (child instanceof Element role && role.getTagName().equals("role"))
				{
					String gender = role.getAttribute("voice"); validVoice(gender);
					if (voices.put(role.getAttribute("id"), gender) != null) throw new IllegalArgumentException("Duplicate role");
				}
				String scope = e.getAttribute("scope");
				if (!List.of("shared", "party", "nonparty").contains(scope)) throw new IllegalArgumentException("Unknown scene scope");
				if (scenes.put(key(e.getAttribute("file"), e.getAttribute("id")), new SceneRule(scope, Map.copyOf(voices))) != null) throw new IllegalArgumentException("Duplicate scene rule");
			}
			else if (e.getTagName().equals("line") || e.getTagName().equals("opener"))
			{
				String gender = e.getAttribute("voice"); validVoice(gender);
				String id = e.getTagName().equals("line") ? key(e.getAttribute("file"), e.getAttribute("id")) : e.getAttribute("text");
				if ((e.getTagName().equals("line") ? lines : openers).put(id, gender) != null) throw new IllegalArgumentException("Duplicate line/opener binding");
			}
		}
		return new Rules(true, Map.copyOf(scenes), Map.copyOf(lines), Map.copyOf(openers));
	}
	static synchronized Rules rules()
	{
		long now = System.currentTimeMillis(); if (now < nextCheck) return current; nextCheck = now + 10000;
		try
		{
			long stamp = Files.getLastModifiedTime(FILE).toMillis();
			if (stamp != modified) { current = read(FILE); modified = stamp; }
		}
		catch (Exception e) { if (modified != -2) { LOG.warning("Keeping previous dialogue bindings: " + e.getMessage()); modified = -2; } }
		return current;
	}
	static boolean lineAllowed(String identity, String bank, String id)
	{
		String required = rules().lines().get(key(bank, id));
		// Новые авторские строки без обязательных метаданных не раздаются произвольно.
		return required == null ? !id.startsWith("rr20261106-") : accepts(required, identity);
	}
	static boolean openerAllowed(Player actor, String text)
	{
		Rules r = rules(); return r.loaded() && accepts(r.openers().get(text), voice(actor));
	}
	static boolean sceneAllowed(Path bank, String id, List<String> lineIds)
	{
		Rules r = rules();
		if (lineIds.stream().anyMatch(x -> x.startsWith("rr20261106-") && !r.lines().containsKey(key(file(bank), x)))) return false;
		SceneRule rule = r.scenes().get(key(file(bank), id));
		if (rule == null) return lineIds.stream().noneMatch(x -> x.startsWith("rr20261106-"));
		boolean party = file(bank).equals("LocalPartyDialogues.xml") || file(bank).equals("LocalPartyLongDialogues.xml");
		return !(party && rule.scope().equals("nonparty")) && !(!party && rule.scope().equals("party"));
	}
	private static Map<String, String> voices(Path bank, String scene)
	{
		SceneRule r = rules().scenes().get(key(file(bank), scene)); return r == null ? Map.of() : r.voices();
	}
	static boolean canBind(Path bank, String scene, List<String> roles, List<Player> actors, Player required)
	{
		if (!actors.contains(required) || actors.size() < roles.size()) return false;
		Map<String, String> wanted = voices(bank, scene);
		if (!wanted.isEmpty() && !wanted.keySet().equals(new java.util.HashSet<>(roles))) return false;
		long male = actors.stream().filter(p -> voice(p).equals("male")).count(), female = actors.size() - male;
		long needMale = roles.stream().filter(r -> "male".equals(wanted.get(r))).count(), needFemale = roles.stream().filter(r -> "female".equals(wanted.get(r))).count();
		return male >= needMale && female >= needFemale && roles.stream().anyMatch(r -> accepts(wanted.get(r), voice(required)));
	}
	static Map<String, Player> bind(Path bank, String scene, List<String> roles, List<Player> actors, Player required, Random random, Map<String, Player> fallback)
	{
		Map<String, String> wanted = voices(bank, scene);
		if (wanted.values().stream().allMatch("either"::equals)) return fallback;
		if (!canBind(bank, scene, roles, actors, required)) return Map.of();
		List<String> slots = new ArrayList<>(roles); Collections.shuffle(slots, random);
		List<Player> candidates = new ArrayList<>(actors); Collections.shuffle(candidates, random);
		for (String slot : slots)
		{
			if (!accepts(wanted.get(slot), voice(required))) continue;
			Map<String, Player> assigned = new HashMap<>(); assigned.put(slot, required);
			List<String> remaining = new ArrayList<>(roles); remaining.remove(slot);
			remaining.sort(java.util.Comparator.comparingInt(r -> "either".equals(wanted.getOrDefault(r, "either")) ? 1 : 0));
			for (String role : remaining)
			{
				for (Player actor : candidates) if (!assigned.containsValue(actor) && accepts(wanted.get(role), voice(actor))) { assigned.put(role, actor); break; }
			}
			if (assigned.size() == roles.size()) { Map<String, Player> result = new LinkedHashMap<>(); for (String role : roles) result.put(role, assigned.get(role)); return result; }
		}
		return Map.of();
	}
	static boolean compatible(Path bank, String scene, Map<String, Player> assigned)
	{
		Map<String, String> wanted = voices(bank, scene);
		if (!wanted.isEmpty() && !wanted.keySet().equals(assigned.keySet())) return false;
		return assigned.entrySet().stream().allMatch(e -> accepts(wanted.get(e.getKey()), voice(e.getValue())));
	}
}
