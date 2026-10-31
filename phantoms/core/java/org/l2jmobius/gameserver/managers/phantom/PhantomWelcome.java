package org.l2jmobius.gameserver.managers.phantom;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.player.OnPlayerLogin;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;

/**
 * L2Hub: вход в мир (docs/PHANTOMS.md, раздел 4.72; docs/LIVE_SERVER.md, п. 6). Живой вернулся после перерыва ({@code HunterWelcomeMinAway}):
 * <ul>
 * <li>сводка «пока тебя не было» — из Летописи мира (log/L2HubChronicle.json), с момента его выхода: сначала про него и его клан, потом заметное, мелочь — если осталось место;</li>
 * <li>знакомые пишут в личку ({@code HunterWelcomeWhispers}): друзья из списка, те, с кем уже что-то было ({@link PhantomRelations}). Только мозги, заготовок нет: «дежурное привет хуже молчания».
 * Кто проигнорировал инициативу охотника ({@link PhantomInitiative}), тому он сам не пишет.</li>
 * </ul>
 * Время — настоящее: от {@link Player#getLastAccess()} до входа. Сводку не пишем, если сказать нечего.
 */
public final class PhantomWelcome
{
	private static final Logger LOGGER = Logger.getLogger(PhantomWelcome.class.getName());

	private static final Path CHRONICLE = Paths.get("./log/L2HubChronicle.json");
	private static final Pattern ENTRY = Pattern.compile("^\\{\"time\":(\\d+),\"kind\":\"([^\"]*)\",\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"");
	/** Мелочь летописи: в сводку — только если осталось место. */
	private static final Set<String> MINOR = Set.of("level", "profession", "elpies", "sevensigns", "seasonal_start", "seasonal_end");
	/** Что в сводку не идёт совсем. */
	private static final Set<String> SKIP = Set.of("thanatos");
	/** Клиент ещё грузит мир: раньше строки потеряются среди приветствий сервера. */
	private static final long SUMMARY_DELAY = 8000;
	private static final int WHISPER_MIN = 20000;
	private static final int WHISPER_MAX = 60000;
	/** Следующий знакомый пишет через столько: двое разом — как по команде. */
	private static final int WHISPER_GAP_MIN = 25000;
	private static final int WHISPER_GAP_MAX = 90000;
	/** Пара «охотник — живой»: не чаще, мс. */
	private static final long WHISPER_COOLDOWN = 21600000;

	private record Known(Player hunter, String note, double score)
	{
	}

	private record Note(long time, String kind, String text, int rank)
	{
	}

	void start()
	{
		if (!PhantomHuntersConfig.HUNTER_WELCOME)
		{
			return;
		}
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_PLAYER_LOGIN, (OnPlayerLogin event) -> onLogin(event.getPlayer()), this));
		LOGGER.info(getClass().getSimpleName() + ": welcome on, from " + PhantomHuntersConfig.HUNTER_WELCOME_MIN_AWAY + " min away.");
	}

	private void onLogin(Player player)
	{
		if ((player == null) || PhantomManager.getInstance().isPhantom(player))
		{
			return;
		}

		// Значение из базы: конец прошлого захода. У нового персонажа перерыва нет.
		final long since = player.getLastAccess();
		if ((since <= 0) || ((System.currentTimeMillis() - since) < (PhantomHuntersConfig.HUNTER_WELCOME_MIN_AWAY * 60000L)))
		{
			return;
		}
		ThreadPool.schedule(() -> summary(player, since), SUMMARY_DELAY);
		ThreadPool.schedule(() -> recognize(player, since), Rnd.get(WHISPER_MIN, WHISPER_MAX));
	}

	// ---------- Сводка ----------

	private void summary(Player player, long since)
	{
		try
		{
			if (!player.isOnline() || (PhantomHuntersConfig.HUNTER_WELCOME_LINES <= 0))
			{
				return;
			}
			final List<Note> notes = notes(player, since);
			if (notes.isEmpty())
			{
				return;
			}

			final boolean en = "en".equals(L2HubWorldConfig.LANGUAGE);
			final long away = System.currentTimeMillis() - since;
			final String head = (en ? "While you were away (" : "Пока тебя не было (") + awayWords(away, en) + "):";
			player.sendMessage(head);
			for (Note note : notes)
			{
				player.sendMessage("— " + note.text());
			}
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " " + head + " " + notes.size() + " lines: " + notes.stream().map(Note::text).toList());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e, e);
		}
	}

	private static List<Note> notes(Player player, long since) throws Exception
	{
		if (!Files.isRegularFile(CHRONICLE))
		{
			return List.of();
		}

		final Clan clan = player.getClan();
		final String clanName = clan == null ? null : clan.getName();
		final List<Note> notes = new ArrayList<>();
		for (String line : Files.readAllLines(CHRONICLE, StandardCharsets.UTF_8))
		{
			final Matcher m = ENTRY.matcher(line);
			if (!m.find())
			{
				continue;
			}
			final long time = Long.parseLong(m.group(1));
			final String kind = m.group(2);
			if ((time < since) || SKIP.contains(kind))
			{
				continue;
			}
			final String text = unescape(m.group(3));
			final boolean mine = text.contains(player.getName()) || ((clanName != null) && text.contains(clanName));
			final boolean stories = PhantomStories.enabled();
			notes.add(new Note(time, kind, text, mine ? 0 : (stories && PhantomStories.loud(kind)) ? 1 : (MINOR.contains(kind) || (stories && "super_killed".equals(kind))) ? 3 : 2));
		}
		notes.sort(Comparator.comparingInt(Note::rank).thenComparingLong(note -> -note.time()));
		return notes.subList(0, Math.min(PhantomHuntersConfig.HUNTER_WELCOME_LINES, notes.size()));
	}

	/**
	 * Самопроверка STORIES.
	 * @return строки сводки входа
	 */
	static List<String> lines(Player player, long since) throws Exception
	{
		return notes(player, since).stream().map(Note::text).toList();
	}

	private static String unescape(String text)
	{
		return text.replace("\\n", " ").replace("\\r", "").replace("\\t", " ").replace("\\\"", "\"").replace("\\\\", "\\");
	}

	private static String awayWords(long away, boolean en)
	{
		final long hours = away / 3600000;
		if (hours >= 48)
		{
			return (hours / 24) + (en ? " days" : " дн.");
		}
		return hours >= 1 ? hours + (en ? " h" : " ч") : (away / 60000) + (en ? " min" : " мин");
	}

	// ---------- Знакомые ----------

	private void recognize(Player player, long since)
	{
		try
		{
			if (!player.isOnline() || (PhantomHuntersConfig.HUNTER_WELCOME_WHISPERS <= 0) || !PhantomChat.brainEnabled())
			{
				return;
			}

			final List<Known> known = known(player);
			known.sort(Comparator.comparingDouble((Known k) -> -k.score()));
			final String away = awayWords(System.currentTimeMillis() - since, false);
			long delay = 0;
			for (Known k : known.subList(0, Math.min(PhantomHuntersConfig.HUNTER_WELCOME_WHISPERS, known.size())))
			{
				final String what = "только что зашёл в игру " + player.getName() + ", его не было " + away + ". " + k.note() + " Напиши ему в личку одну короткую реплику своими словами и по своему характеру, как пишут знакомому: не обязательно про то, что его не было, можно и про то, что между вами было. Вспоминай только то, что правда было. Сказать нечего — молчи";
				final Player hunter = k.hunter();
				ThreadPool.schedule(() ->
				{
					if (player.isOnline() && !hunter.isDead())
					{
						PhantomChat.getInstance().hunterEvent(hunter, player, "WELCOME", what, null, WHISPER_COOLDOWN, true);
					}
				}, delay);
				delay += Rnd.get(WHISPER_GAP_MIN, WHISPER_GAP_MAX);
				LOGGER.info(getClass().getSimpleName() + ": " + k.hunter().getName() + " knows " + player.getName() + " (" + k.note() + "), away " + away + ".");
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + e, e);
		}
	}

	/** Охотники в мире, которые его помнят: друзья из списка и те, с кем что-то было. */
	private static List<Known> known(Player player)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		final List<Known> known = new ArrayList<>();
		for (Player hunter : World.getPlayers())
		{
			if (!PhantomManager.getInstance().isPhantom(hunter) || !PhantomHunterManager.getInstance().isHunter(hunter) || PhantomManager.getInstance().isSecret(hunter) || hunter.isDead() || PhantomInitiative.ignored(hunter, player.getObjectId()))
			{
				continue;
			}

			final boolean listed = hunter.getFriendList().contains(player.getObjectId());
			final double grudge = relations.grudge(hunter, player);
			final double gratitude = relations.gratitude(hunter, player);
			if (!listed && (grudge <= 0) && (gratitude <= 0))
			{
				continue;
			}

			final String note;
			if (relations.isEnemy(hunter, player))
			{
				final int kills = relations.kills(hunter, player);
				note = "Ты на него в обиде" + (kills > 0 ? ": он убивал тебя (" + kills + ")." : ".");
			}
			else if (listed || relations.isFriend(hunter, player))
			{
				note = (listed ? "Он у тебя в друзьях." : "Вы на хорошем счету.") + (gratitude > 0 ? " Он тебя выручал." : "");
			}
			else
			{
				note = gratitude >= grudge ? "Вы уже пересекались, он тебя выручал." : "Вы уже пересекались, он тебе немного насолил.";
			}
			known.add(new Known(hunter, note, (listed ? 3 : 0) + Math.max(grudge, gratitude) + Rnd.nextDouble()));
		}
		return known;
	}

	public static PhantomWelcome getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomWelcome INSTANCE = new PhantomWelcome();
	}
}
