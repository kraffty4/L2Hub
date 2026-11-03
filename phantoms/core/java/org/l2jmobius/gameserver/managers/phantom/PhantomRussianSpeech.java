package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L2Hub (ключ {@code HunterSlang}): локальный разбор игрового сленга в сообщении игрока — без модели и без сети. Находит термины
 * («кв», «пт», «бсс», «мамон») и кладёт в запрос мозгам поле {@code slang}: что сказано и что это значит. Сообщение игрока не меняется.<br>
 * Характер и язык ответа решают персонаж ({@code L2HubPhantomPersonas.json}) и правила {@code brain.py}, а не этот класс.
 */
public final class PhantomRussianSpeech
{
	/** Терминов в одном запросе — не больше: подсказка, а не перевод. */
	private static final int MAX_TERMS = 8;
	private static final int MAX_MESSAGE = 2000;
	private static final Pattern FPC = Pattern.compile("\"fpc\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
	private static final Pattern MESSAGE = Pattern.compile("\"message\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"");
	private static final List<Term> TERMS = new ArrayList<>();

	private record Term(Pattern pattern, String meaning)
	{
	}

	static
	{
		// Сокращения из подсказки brain.py («го, пт, фарм, дроп, сс, бсс, рб, лвл, ап, кх, пк, хил, баф, рес»), затем остальное по частоте в чате.
		term("го", "пойдём, согласие или приглашение начать");
		term("пт|пати|party", "пати, группа игроков или приглашение в группу");
		term("фарм(?:а|у|ом|ить|им|ишь|ят)?", "добыча опыта, адены или предметов");
		term("дроп(?:а|у|ом|ы|ов|е)?", "добыча, выпадающая с мобов");
		term("сс|ss|сосы|сос(?:ок|ки|ку|ками)|соулшот(?:ы|ов)?", "Soulshot, заряды усиления физической атаки");
		term("бсс|bss|спиритшот(?:ы|ов)?|бсп", "Blessed Spiritshot, заряды усиления магии");
		term("рб|рейд[ -]?босс(?:а|у|ом|ы|ов)?", "рейд-босс");
		term("лвл|лвла|лвле|lvl", "уровень персонажа");
		term("ап(?:нул|нулся|нуть)?", "новый уровень (апнуть — поднять уровень)");
		term("кх|клан[ -]?холл(?:а|у|ом|ы|ов)?", "клан-холл");
		term("пк|pk", "player killer или убийство игрока, по контексту");
		term("хил(?:а|у|ом|ер|ера|еры|ь|ю|ить|ни)?", "лечение или хилер, по контексту");
		term("баф(?:а|у|ом|ы|ов|ни|нуть|ер|ера|еры)?", "положительный эффект или просьба дать баф");
		term("рес(?:а|у|ом|ни|ните|нуть)?", "воскрешение или просьба воскресить");
		term("кв|клан[ -]?вар(?:а|у|ом|ы|ов)?", "клан-вар, война кланов");
		term("пот(?:ка|ки|ку|ке|ой|ы|ов|ами)|pots?|банк(?:а|и|у|ой|ок|ами)", "зелья, чаще HP или MP");
		term("п(?:э|е)т(?:а|у|ом|ы|ов|ами|е)?", "питомец");
		term("lfm|лфм", "лидер ищет игроков в пати");
		term("lfp|лфп", "игрок сам ищет пати");
		term("пнх", "грубый отказ; не игровая команда");
		term("эпик(?:а|у|ом|и|ов|ами)?", "эпический рейд-босс или его бижутерия, по контексту");
		term("дд", "damage dealer, персонаж, наносящий урон");
		term("танк(?:а|у|ом|и|ов|ами)?", "танк: держит мобов на себе");
		term("спот(?:а|у|ом|ы|ов|ами|е)?", "место охоты на мобов");
		term("кач(?:а|у|ом|ь|аться|аюсь|аешься)?", "прокачка персонажа");
		term("шмот(?:а|у|ом|ки|ок|ками|е)?", "экипировка");
		term("пух(?:а|и|е|у|ой|ами)", "оружие");
		term("точ(?:ка|ки|ку|кой|нуть|ить)|заточ(?:ка|ки|ку|кой|ить)", "заточка экипировки");
		term("гк|гейткипер(?:а|у|ом|ы|ов)?", "Gatekeeper, NPC телепортации");
		term("гм[ -]?шоп(?:а|у|ом|е)?", "GM Shop, магазин сервера");
		term("кп|конста", "constant party, постоянная группа");
		term("спойл(?:а|у|ом|ить|ер|ера)?", "Spoil, дополнительная добыча с моба, или гном-спойлер");
		term("крафт(?:а|у|ом|ить|ер|ера)?", "создание предметов или гном-крафтер");
		term("саб(?:а|у|ом|ы|е)?|сабкласс(?:а|у|ом|ы)?", "сабкласс персонажа");
		term("проф(?:а|ы|е|у|ой)", "профессия, смена профессии");
		term("мамон(?:а|у|ом|е)?", "кузнец или торговец Маммона, по контексту");
		term("афк|afk", "игрок отошёл от компьютера");
		term("тп", "телепорт или свиток телепортации");
		term("агр(?:о|а|у|ом|ы|ов)?", "агрессия моба или агрессивный моб");
		term("кк", "миллион адены");
	}

	private PhantomRussianSpeech()
	{
	}

	private static void term(String regex, String meaning)
	{
		TERMS.add(new Term(Pattern.compile("(?<![\\p{L}\\p{N}_])(?:" + regex + ")(?![\\p{L}\\p{N}_])", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE), meaning));
	}

	/**
	 * Запрос мозгам с полем {@code slang} в начале, если в {@code message} нашёлся сленг; иначе запрос как был — ни байта лишнего.
	 */
	public static String enrichRequest(String json)
	{
		if ((json == null) || !json.startsWith("{") || !FPC.matcher(json).find())
		{
			return json;
		}
		final Matcher message = MESSAGE.matcher(json);
		final String slang = message.find() ? slang(unescape(message.group(1))) : "";
		return slang.isEmpty() ? json : "{\"slang\":" + slang + ',' + json.substring(1);
	}

	/**
	 * @return JSON-массив {@code [{"said":…,"meaning":…}]} найденных терминов или пустая строка
	 */
	static String slang(String message)
	{
		final String normalized = (message == null ? "" : message).replace('ё', 'е').replace('Ё', 'Е');
		final StringBuilder sb = new StringBuilder(256);
		int found = 0;
		for (Term term : TERMS)
		{
			final Matcher matcher = term.pattern.matcher(normalized);
			if (!matcher.find())
			{
				continue;
			}
			sb.append(found++ == 0 ? "[" : ",").append('{');
			field(sb, "said", matcher.group().toLowerCase(Locale.ROOT)).append(',');
			field(sb, "meaning", term.meaning).append('}');
			if (found >= MAX_TERMS)
			{
				break;
			}
		}
		return found == 0 ? "" : sb.append(']').toString();
	}

	static StringBuilder field(StringBuilder sb, String key, String value)
	{
		sb.append('"').append(key).append("\":\"");
		final String text = value == null ? "" : value;
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default ->
				{
					if (c < 0x20)
					{
						sb.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"');
	}

	private static String unescape(String text)
	{
		final StringBuilder sb = new StringBuilder(Math.min(text.length(), MAX_MESSAGE));
		for (int i = 0; (i < text.length()) && (sb.length() < MAX_MESSAGE); i++)
		{
			final char c = text.charAt(i);
			if ((c != '\\') || ((i + 1) >= text.length()))
			{
				sb.append(c);
				continue;
			}
			final char escaped = text.charAt(++i);
			switch (escaped)
			{
				case 'n' -> sb.append('\n');
				case 'r' -> sb.append('\r');
				case 't' -> sb.append('\t');
				case 'b' -> sb.append('\b');
				case 'f' -> sb.append('\f');
				case 'u' ->
				{
					if ((i + 4) < text.length())
					{
						try
						{
							sb.append((char) Integer.parseInt(text.substring(i + 1, i + 5), 16));
							i += 4;
						}
						catch (NumberFormatException e)
						{
							sb.append('u');
						}
					}
				}
				default -> sb.append(escaped);
			}
		}
		return sb.toString();
	}
}
