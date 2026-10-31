package org.l2jmobius.gameserver.managers.phantom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: петиция, когда GM нет (docs/LIVE_SERVER.md, вторая волна, п. 6; {@code PetitionAnswer} в PhantomHunters.ini). Mobius отвечает одним системным сообщением, текст пропадает.
 * С ключом сервер отвечает сам и прямо говорит, что это автоответ программы, а не GM: за живого GM он себя не выдаёт. Текст ложится в {@code log/L2HubPetitions.log}, его читает хозяин сервера;
 * подсказка подбирается по словам петиции. Когда GM в сети, работает обычная петиция Mobius, сюда она не доходит. Врезка — {@code RequestPetition}, патч {@code 0110-petition-answer}.
 */
public final class PhantomPetition
{
	private static final Logger LOGGER = Logger.getLogger(PhantomPetition.class.getName());

	private static final Path JOURNAL = Path.of("log", "L2HubPetitions.log");
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	/** Один игрок пишет в журнал не чаще: петицию можно отправлять кнопкой хоть каждую секунду. */
	private static final long GAP = 30000;
	/** Длиннее в журнал не идёт. */
	private static final int TEXT_MAX = 2000;
	private static final int HINTS_MAX = 2;

	private static final class Hint
	{
		final Pattern words;
		final String ru;
		final String en;

		Hint(String words, String ru, String en)
		{
			this.words = Pattern.compile(words, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
			this.ru = ru;
			this.en = en;
		}
	}

	private static final Hint[] HINTS =
	{
		new Hint("\\bзастр|\\bзастял|текстур|не могу (выйти|двигаться|сдвинуться|пройти)|\\bstuck|\\bunstuck", "Застрял: команда /unstuck вернёт в город.", "Stuck: the /unstuck command takes you back to town."),
		new Hint("\\bрейд|\\bрб\\b|\\braid", "Рейды: команда .raid.", "Raids: the .raid command."),
		new Hint("напарник|\\bbuddy|\\bcompanion", "Напарники: команда .buddy.", "Companions: the .buddy command."),
		new Hint("охотник|\\bботы?\\b|\\bфейк|\\bhunter", "Охотники мира: команда .hunters.", "Hunters of this world: the .hunters command."),
		new Hint("поручен|ежедневн|\\bзадани|\\bdaily", "Поручения дня: команда .daily.", "Daily tasks: the .daily command."),
		new Hint("\\bклан|\\bвойн|\\bclan|\\bwar\\b|\\bбафф?|\\bbuff|\\bпрода", "Кланы, войны, бафы и продажа лута: доска Alt+B.", "Clans, wars, buffs and loot sale: the Alt+B board."),
		new Hint("пропал|исчез|\\bпотерял|\\bбаг|\\bошибк|\\bвернит|\\bbug|\\blost|\\bmissing", "Пропажу или ошибку автоответ не исправит: это решает хозяин сервера, он прочитает журнал.", "An automatic reply cannot fix a loss or a bug: that is up to the server owner, who reads the log.")
	};

	private static final Map<Integer, Long> NEXT = new ConcurrentHashMap<>();

	private PhantomPetition()
	{
	}

	private static String pick(String ru, String en)
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE) ? en : ru;
	}

	/**
	 * Из {@code RequestPetition}, ветка «GM нет»: системное сообщение Mobius уже ушло.
	 * @param player кто написал
	 * @param content текст петиции
	 * @return ответ ушёл и текст записан; {@code false} — ключ выключен или игрок только что уже писал
	 */
	public static boolean answer(Player player, String content)
	{
		if (!PhantomHuntersConfig.PETITION_ANSWER)
		{
			return false;
		}
		final long now = System.currentTimeMillis();
		if (now < NEXT.getOrDefault(player.getObjectId(), 0L))
		{
			return false;
		}
		NEXT.put(player.getObjectId(), now + GAP);

		final boolean saved = write(player, content);
		final String prefix = pick("[Автоответ сервера] ", "[Automatic server reply] ");
		player.sendMessage(prefix + pick("GM на этом сервере сейчас нет. Это пишет программа, а не человек.", "There is no GM on this server right now. This is a program writing, not a person."));
		if (saved)
		{
			player.sendMessage(prefix + pick("Текст петиции записан в журнал сервера, его прочитает хозяин сервера.", "Your petition is saved to the server log for the server owner to read."));
		}
		for (String hint : hints(content))
		{
			player.sendMessage(prefix + hint);
		}
		return true;
	}

	/**
	 * @param content текст петиции
	 * @return подсказки по словам; слова не совпали — общая: команды, какие есть
	 */
	static List<String> hints(String content)
	{
		final List<String> found = new ArrayList<>();
		final String text = content == null ? "" : content.toLowerCase(Locale.ROOT);
		for (Hint hint : HINTS)
		{
			if (hint.words.matcher(text).find())
			{
				found.add(pick(hint.ru, hint.en));
				if (found.size() == HINTS_MAX)
				{
					break;
				}
			}
		}
		if (found.isEmpty())
		{
			found.add(pick("Команды сервера: .hunters, .raid, .daily, .buddy; доска — Alt+B; застрял — /unstuck.", "Server commands: .hunters, .raid, .daily, .buddy; the board is Alt+B; if stuck, /unstuck."));
		}
		return found;
	}

	private static synchronized boolean write(Player player, String content)
	{
		String text = content == null ? "" : content.replace('\r', ' ').replace('\n', ' ').strip();
		if (text.length() > TEXT_MAX)
		{
			text = text.substring(0, TEXT_MAX) + "...";
		}
		final String line = LocalDateTime.now().format(STAMP) + "\t" + player.getName() + "\t" + player.getLevel() + "\t" + text + System.lineSeparator();
		try
		{
			Files.writeString(JOURNAL, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			return true;
		}
		catch (IOException e)
		{
			LOGGER.log(Level.WARNING, PhantomPetition.class.getSimpleName() + ": could not write " + JOURNAL + ".", e);
			return false;
		}
	}

	/**
	 * Самопроверка PETITION ({@code HuntersSelfTest}): игрока играет охотник. Ключ выключен — молчит и не пишет; включён — отвечает и пишет, повтор сразу — без ответа; подсказки по словам. Ключ возвращается.
	 * @param player кто пишет
	 */
	static void selfTest(Player player)
	{
		final String tag = PhantomPetition.class.getSimpleName() + ": SelfTest PETITION: ";
		final boolean was = PhantomHuntersConfig.PETITION_ANSWER;
		try
		{
			final long before = lines();
			PhantomHuntersConfig.PETITION_ANSWER = false;
			final boolean off = answer(player, "застрял");
			LOGGER.info(tag + "key off: answered " + off + ", journal lines added " + (lines() - before) + ".");

			PhantomHuntersConfig.PETITION_ANSWER = true;
			NEXT.remove(player.getObjectId());
			final boolean first = answer(player, "SelfTest: застрял в текстурах\r\nпомогите");
			final boolean second = answer(player, "SelfTest: ещё раз");
			LOGGER.info(tag + "key on: first answered " + first + ", repeat at once answered " + second + ", journal lines added " + (lines() - before) + ".");

			for (String text : new String[]
			{
				"застрял в текстурах",
				"когда рейд на рб",
				"куда пропал мой меч, баг",
				"а тут боты или люди?",
				"I am stuck, raid please",
				"работа над ошибками",
				"добрый вечер"
			})
			{
				LOGGER.info(tag + "«" + text + "» -> " + hints(text) + ".");
			}
			LOGGER.info(tag + "done.");
		}
		finally
		{
			PhantomHuntersConfig.PETITION_ANSWER = was;
			NEXT.remove(player.getObjectId());
		}
	}

	private static long lines()
	{
		try
		{
			return Files.isRegularFile(JOURNAL) ? Files.readAllLines(JOURNAL, StandardCharsets.UTF_8).size() : 0;
		}
		catch (IOException e)
		{
			return -1;
		}
	}
}
