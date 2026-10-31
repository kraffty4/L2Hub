package org.l2jmobius.gameserver.managers.phantom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.managers.phantom.PhantomRelations.Kind;

/**
 * L2Hub: летопись как истории (docs/PHANTOMS.md, раздел 4.66; docs/LIVE_SERVER.md, вторая волна, п. 3). Выключатель {@code HunterStories}. В ленту идёт то, о чём на сервере говорят:
 * ссора (обида дошла до «враг»), махач и его исход, уход из клана после ссоры, поломка заточки. Повтор помнит прошлый раз: «снова ссора, уже 3-я». Ссора громкая: половина кончается криком в торговый чат.
 * Между охотниками запись не чаще {@code HunterStoryGapMinutes} на весь мир, с живым всегда. В сводке «Пока тебя не было» это идёт вперёд суперчемпионов ({@link PhantomWelcome}).
 */
final class PhantomStories
{
	private static final Logger LOGGER = Logger.getLogger(PhantomStories.class.getName());

	/** Что в сводке входа идёт сразу за новостями о самом игроке. */
	private static final Set<String> LOUD = Set.of("quarrel", "left", "gone", "back", "drop_fight", "scam", "enchant");
	/** Поломка заточки — новость с этого уровня: с него заточка уже может сломаться. */
	static final int BREAK_LEVEL = 3;

	/** Переменная охотника: с кем он ссорился, «objectId:раз:секунды,…», свежие первыми. */
	private static final String QUARRELS = "L2HubStoryQuarrels";
	private static final int QUARRELS_KEPT = 8;
	/** Переменная охотника: поломки заточки, «раз:секунды». */
	private static final String BREAKS = "L2HubStoryBreaks";
	/** Столько помнится прошлая ссора или поломка. */
	private static final long MEMORY = 1209600000;
	/** Ссора той же пары — новая запись не раньше. */
	private static final long QUARREL_PAIR_GAP = 21600000;
	/** Махач той же пары — новая запись не раньше. */
	private static final long FIGHT_PAIR_GAP = 3600000;
	private static final int SHOUT_CHANCE = 50;

	private static final String[] QUARREL_LINES = { "%1$s, я тебя запомнил%2$s", "%1$s крыса, всем на заметку", "%1$s, ещё раз увижу рядом, пеняй на себя", "с %1$s больше не играю, не зовите" };
	private static final String[] QUARREL_LINES_EN = { "%1$s, I'll remember this", "%1$s is a rat, you've been warned", "%1$s, next time you'll regret it", "not playing with %1$s anymore, don't invite me" };
	private static final String[] AGAIN_LINES = { "%1$s, опять ты. ну всё", "%1$s, тебе прошлого раза мало?", "%1$s снова за своё, народ, вы это видите?" };
	private static final String[] AGAIN_LINES_EN = { "%1$s, you again. that's it", "%1$s, wasn't last time enough?", "%1$s is at it again, you all seeing this?" };

	private static final Pattern ENTRY = Pattern.compile("^\\{\"time\":(\\d+),\"kind\":\"([^\"]*)\",\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");

	/** Когда можно следующую запись: «вид» — на весь мир, «вид:меньший id:больший id» — на пару. */
	private static final Map<String, Long> NEXT = new HashMap<>();

	private PhantomStories()
	{
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_STORIES;
	}

	/**
	 * @return вид записи летописи, который в сводке входа идёт вперёд обычных
	 */
	static boolean loud(String kind)
	{
		return LOUD.contains(kind);
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	private static boolean female(Player player)
	{
		return player.getAppearance().isFemale();
	}

	private static boolean quiet(Player... players)
	{
		for (Player player : players)
		{
			if (PhantomManager.getInstance().isSecret(player))
			{
				return true;
			}
		}
		return !enabled();
	}

	private static boolean due(String kind, Player a, Player b, long pairGap, long now)
	{
		final boolean hunters = PhantomManager.getInstance().isPhantom(a) && PhantomManager.getInstance().isPhantom(b);
		final String pair = kind + ":" + Math.min(a.getObjectId(), b.getObjectId()) + ":" + Math.max(a.getObjectId(), b.getObjectId());
		synchronized (NEXT)
		{
			if ((now < NEXT.getOrDefault(pair, 0L)) || (hunters && (now < NEXT.getOrDefault(kind, 0L))))
			{
				return false;
			}
			NEXT.put(pair, now + pairGap);
			if (hunters)
			{
				NEXT.put(kind, now + (PhantomHuntersConfig.HUNTER_STORY_GAP_MINUTES * 60000L));
			}
			return true;
		}
	}

	/**
	 * Память пары — у охотника с меньшим id, чтобы история была одна с обеих сторон; у живого своих переменных не заводим.
	 * @return которая это стычка пары за {@value #MEMORY} мс, считая эту
	 */
	private static int remember(Player hunter, Player other, long now)
	{
		final boolean swap = PhantomManager.getInstance().isPhantom(other) && (other.getObjectId() < hunter.getObjectId());
		final Player holder = swap ? other : hunter;
		final int partner = swap ? hunter.getObjectId() : other.getObjectId();
		final StringBuilder kept = new StringBuilder();
		int count = 0;
		int rows = 0;
		for (String row : holder.getVariables().getString(QUARRELS, "").split(","))
		{
			final String[] parts = row.split(":");
			if ((parts.length != 3) || ((now - (Long.parseLong(parts[2]) * 1000)) > MEMORY))
			{
				continue;
			}
			if (Integer.parseInt(parts[0]) == partner)
			{
				count = Integer.parseInt(parts[1]);
			}
			else if (rows < (QUARRELS_KEPT - 1))
			{
				kept.append(',').append(row);
				rows++;
			}
		}
		holder.getVariables().set(QUARRELS, partner + ":" + (count + 1) + ":" + (now / 1000) + kept);
		ThreadPool.execute(() -> holder.getVariables().storeMe());
		return count + 1;
	}

	private static String reason(Kind kind, boolean en)
	{
		return switch (kind.key)
		{
			case "killed" -> en ? "a kill" : "убийство";
			case "attacked" -> en ? "an attack" : "нападение";
			case "contested" -> en ? "a stolen mob" : "отжатый моб";
			case "insulted" -> en ? "an insult" : "послал";
			case "loot_fight" -> en ? "a drop" : "дроп";
			case "duel_win" -> en ? "a duel" : "дуэль";
			default -> en ? "a grudge" : "обида";
		};
	}

	/**
	 * Обида охотника дошла до «враг» ({@link PhantomRelations#record}). Кидок в обмене не считаем: у него своя запись.
	 * @param hunter кто обижен
	 * @param other на кого: живой или охотник
	 * @param kind последняя капля
	 */
	static void quarrel(Player hunter, Player other, Kind kind)
	{
		final long now = System.currentTimeMillis();
		if (quiet(hunter, other) || "scammed".equals(kind.key) || !due("quarrel", hunter, other, QUARREL_PAIR_GAP, now))
		{
			return;
		}

		final int count = remember(hunter, other, now);
		final boolean en = en();
		final String names = hunter.getName() + (en ? " and " : " и ") + other.getName();
		final String why = reason(kind, en);
		final String text = count > 1 ? (en ? "Another quarrel: " + names + ", " + why + " (" + count + " so far)" : "Снова ссора: " + names + " — " + why + " (уже " + count + "-я)") : (en ? "Quarrel: " + names + ", " + why : "Ссора: " + names + " — " + why);
		PhantomNews.post("quarrel", text, hunter.getName(), hunter);
		LOGGER.info(PhantomStories.class.getSimpleName() + ": " + text + ".");
		if (Rnd.get(100) < SHOUT_CHANCE)
		{
			final String[] pool = count > 1 ? (en ? AGAIN_LINES_EN : AGAIN_LINES) : (en ? QUARREL_LINES_EN : QUARREL_LINES);
			final String line = String.format(pool[Rnd.get(pool.length)], other.getName(), female(hunter) ? "а" : "");
			PhantomChat.getInstance().hunterShout(hunter, line);
			LOGGER.info(PhantomStories.class.getSimpleName() + ": " + hunter.getName() + " shouts: " + line);
		}
	}

	/**
	 * Махач начался ({@link PhantomBrawl#provoked}).
	 */
	static void brawl(Player angry, Player other)
	{
		final long now = System.currentTimeMillis();
		if (quiet(angry, other) || !due("brawl", angry, other, FIGHT_PAIR_GAP, now))
		{
			return;
		}

		final int count = remember(angry, other, now);
		final boolean en = en();
		final String text = en ? (count > 1 ? "Another brawl: " : "Brawl: ") + angry.getName() + " jumped " + other.getName() : (count > 1 ? "Снова махач: " : "Махач: ") + angry.getName() + " набросил" + (female(angry) ? "ась" : "ся") + " на " + other.getName();
		PhantomNews.post("quarrel", text, angry.getName(), angry);
		LOGGER.info(PhantomStories.class.getSimpleName() + ": " + text + ".");
	}

	/**
	 * Охотник убит другим охотником. Война кланов и ивенты считают это сами, в ленту не идёт.
	 */
	static void fell(Player loser, Player winner)
	{
		final long now = System.currentTimeMillis();
		final Clan clan = loser.getClan();
		if (quiet(loser, winner) || ((clan != null) && (winner.getClan() != null) && PhantomCompat.atWar(clan, winner.getClan())) || PhantomEvents.getInstance().isBusy(loser) || !due("fell", loser, winner, FIGHT_PAIR_GAP, now))
		{
			return;
		}

		final String text = en() ? "Brawl: " + winner.getName() + " took down " + loser.getName() : "Махач: " + winner.getName() + " положил" + (female(winner) ? "а " : " ") + loser.getName();
		PhantomNews.post("quarrel", text, winner.getName(), loser);
		LOGGER.info(PhantomStories.class.getSimpleName() + ": " + text + ".");
	}

	/**
	 * Охотник ушёл из клана живого, потому что стал ему врагом ({@link PhantomClans#onGrudge}).
	 */
	static void leftClan(Player hunter, Clan clan, Player other)
	{
		if (quiet(hunter, other))
		{
			return;
		}

		final String text = en() ? hunter.getName() + " left clan " + clan.getName() + " after a quarrel with " + other.getName() : hunter.getName() + (female(hunter) ? " ушла" : " ушёл") + " из клана «" + clan.getName() + "»: поссорил" + (female(hunter) ? "ась" : "ся") + " с " + other.getName();
		PhantomNews.post("left", text, hunter.getName(), hunter);
		LOGGER.info(PhantomStories.class.getSimpleName() + ": " + text + ".");
	}

	/**
	 * Поломка заточки ({@link PhantomEnchant#announce}): считает поломки охотника.
	 * @return хвост записи летописи: пусто или «(уже 2-я за две недели)»
	 */
	static String brokeAgain(Player player, boolean en)
	{
		if (quiet(player))
		{
			return "";
		}

		final long now = System.currentTimeMillis();
		final String[] parts = player.getVariables().getString(BREAKS, "").split(":");
		final int count = ((parts.length == 2) && ((now - (Long.parseLong(parts[1]) * 1000)) <= MEMORY) ? Integer.parseInt(parts[0]) : 0) + 1;
		player.getVariables().set(BREAKS, count + ":" + (now / 1000));
		ThreadPool.execute(() -> player.getVariables().storeMe());
		return count > 1 ? (en ? " (" + count + " in two weeks)" : " (уже " + count + "-я за две недели)") : "";
	}

	// ---------- Самопроверка ----------

	/**
	 * Записи летописи с этого момента: для самопроверок, файл пишет скрипт {@code L2HubChronicle} раз в 10 с.
	 */
	static List<String> chronicle(long since)
	{
		final List<String> rows = new ArrayList<>();
		try
		{
			for (String line : Files.readAllLines(Paths.get("./log/L2HubChronicle.json"), StandardCharsets.UTF_8))
			{
				final Matcher m = ENTRY.matcher(line);
				if (m.find() && (Long.parseLong(m.group(1)) >= since))
				{
					rows.add(m.group(2) + ": " + m.group(3));
				}
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomStories.class.getSimpleName() + ": " + e, e);
		}
		return rows;
	}

	/**
	 * Самопроверка STORIES ({@code HuntersSelfTest}, только на копии базы): все виды записей на настоящих охотниках, порог мира, память пары и поломок, порядок в сводке входа.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		final String tag = "PhantomStories: SelfTest STORIES: ";
		final List<Player> hunters = new ArrayList<>();
		final PhantomRelations relations = PhantomRelations.getInstance();
		for (Player player : world)
		{
			if ((player.getLevel() >= 20) && !PhantomManager.getInstance().isSecret(player))
			{
				hunters.add(player);
			}
		}
		if (hunters.size() < 4)
		{
			LOGGER.warning(tag + "no four hunters from level 20 in the world.");
			return;
		}

		final Player a = hunters.get(0);
		final Player b = hunters.get(1);
		final Player c = hunters.get(2);
		final Player d = hunters.get(3);
		final long start = System.currentTimeMillis();
		final boolean keyWas = PhantomHuntersConfig.HUNTER_STORIES;

		PhantomHuntersConfig.HUNTER_STORIES = false;
		relations.record(a, b, Kind.KILLED);
		PhantomEnchant.announce(d, "Bastard Sword", BREAK_LEVEL, true);
		LOGGER.info(tag + "key off: " + a.getName() + " became an enemy of " + b.getName() + " " + relations.isEnemy(a, b) + ", " + d.getName() + " broke a weapon +" + BREAK_LEVEL + ": neither should be in the chronicle below.");

		PhantomHuntersConfig.HUNTER_STORIES = true;
		final boolean crossed = !relations.isEnemy(c, d);
		relations.record(c, d, Kind.KILLED);
		LOGGER.info(tag + "first quarrel: " + c.getName() + " and " + d.getName() + " became enemies " + (crossed && relations.isEnemy(c, d)) + ".");
		quarrel(a, c, Kind.CONTESTED);
		LOGGER.info(tag + a.getName() + " and " + c.getName() + " right after it should be refused by HunterStoryGapMinutes: see the chronicle below.");
		synchronized (NEXT)
		{
			NEXT.clear();
		}
		quarrel(c, d, Kind.CONTESTED);
		quarrel(a, b, Kind.INSULTED);
		synchronized (NEXT)
		{
			NEXT.clear();
		}
		brawl(a, b);
		synchronized (NEXT)
		{
			NEXT.remove("fell");
		}
		fell(b, a);
		for (Player player : world)
		{
			if (player.getClan() != null)
			{
				leftClan(player, player.getClan(), d);
				break;
			}
		}
		PhantomEnchant.announce(d, "Bastard Sword", BREAK_LEVEL, true);
		PhantomEnchant.announce(d, "Bastard Sword", 5, true);

		ThreadPool.schedule(() ->
		{
			for (String row : chronicle(start))
			{
				LOGGER.info(tag + "chronicle " + row);
			}
			try
			{
				LOGGER.info(tag + "welcome summary for " + d.getName() + ": " + PhantomWelcome.lines(d, start - 3600000));
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, tag + e, e);
			}
			PhantomHuntersConfig.HUNTER_STORIES = keyWas;
			LOGGER.info(tag + "done.");
		}, 25000);
	}
}
