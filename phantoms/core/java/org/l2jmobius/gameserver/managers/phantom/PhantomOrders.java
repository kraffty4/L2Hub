package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L2Hub: приказы напарникам словами — по-русски, транслитом и по-английски.<br>
 * Приказ — короткая фраза без вопроса и без отрицания: «за мной», «Митяй, бафни», «весна рес», «stay». Понятный приказ выполняется сразу, без мозгов; мозги только отвечают в характере.
 * Длинное, вопрос («у тебя есть баф?») или отрицание («не ходи за мной») уходят в мозги: они могут ответить тегом приказа ({@code [[STAY]]}), сервер его проверит и выполнит.
 */
public class PhantomOrders
{
	public enum Order
	{
		/** Идти следом (воин — только защищается). */
		FOLLOW,
		/** Стоять на месте. */
		STAY,
		/** Бить цель владельца (саппорт просто идёт следом). */
		ASSIST,
		/** Перебафать пати. */
		BUFF,
		/** Подлечить того, кто просит. */
		HEAL,
		/** Поднять павшего. */
		RES,
		/** Сесть отдохнуть. */
		SIT,
		/** Встать. */
		STAND,
		/** Уйти из пати и из мира. */
		LEAVE,
		/** Качаться тут (патч 0028): бить всех мобов вокруг владельца и добивать, держась рядом с ним. */
		HUNT,
		/** «Паровозик» (патч 0029): вести кач самому, как на своём споте, — владелец идёт следом. */
		LEAD
	}

	/** Длиннее этого (слов без имён напарников) — уже разговор, его разбирают мозги. */
	private static final int MAX_WORDS = 5;

	private static final Set<String> NEGATIONS = Set.of("не", "ни", "нет", "ne", "net", "not", "dont", "don", "no");

	/** Фразы где угодно в сообщении: целые слова через пробел; «*» в конце — любое окончание. */
	private static final Map<Order, Pattern> ANYWHERE = new EnumMap<>(Order.class);
	/** Сообщение целиком (после имён напарников). */
	private static final Map<Order, Set<String>> WHOLE = new EnumMap<>(Order.class);
	static
	{
		ANYWHERE.put(Order.FOLLOW, phrases("за мной", "замной", "иди за мной", "ко мне", "иди сюда", "давай сюда", "следуй*", "держись рядом", "follow*", "come here", "za mnoy", "za mnoi", "za mnoj", "ko mne", "idi syuda", "idi suda"));
		ANYWHERE.put(Order.STAY, phrases("стой", "стойте", "стоять", "стоп", "жди", "ждите", "подожди", "подождите", "останься", "оставайся", "stay", "stop", "wait", "hold", "stoi", "stoy", "stoj", "zhdi", "jdi", "podozhdi"));
		ANYWHERE.put(Order.ASSIST, phrases("бей", "бейте", "атакуй", "атакуйте", "мочи", "асист*", "ассист*", "attack", "assist", "fight", "kill", "bei", "bey", "atakuy", "asist*"));
		ANYWHERE.put(Order.BUFF, phrases("баф*", "ребаф*", "перебаф*", "buf*", "baf*", "rebaf*", "rebuf*", "perebaf*"));
		ANYWHERE.put(Order.HEAL, Pattern.compile("(?:^| )(?:хил(?!ер|к)\\S*|подлеч\\S*|полеч\\S*|лечи|хп|heal(?!er)\\S*|hil(?!er|k)\\S*|podlech\\S*|polechi|lechi|hp)(?= |$)"));
		ANYWHERE.put(Order.RES, Pattern.compile("(?:^| )(?:рес(?!урс|тарт|торан)\\S*|подними|поднимай|воскреси|res(?!t|pect)\\S*|rez|revive|podnimi|voskresi)(?= |$)"));
		ANYWHERE.put(Order.SIT, phrases("сядь", "сиди", "садись", "присядь", "отдохни", "отдыхай", "передохни", "sit", "sit down", "rest", "syad", "sadis", "sidi", "otdohni", "otdyhay"));
		ANYWHERE.put(Order.STAND, phrases("встань", "вставай", "встать", "подъем", "поднимайся", "stand", "stand up", "vstan", "vstavai", "vstavay"));
		ANYWHERE.put(Order.LEAVE, phrases("отпускаю", "свободен", "свободна", "свободны", "иди домой", "можешь идти", "можешь уходить", "уходи", "dismiss", "otpuskayu", "svoboden", "svobodna"));
		ANYWHERE.put(Order.HUNT, phrases("кач тут", "кач здесь", "качаемся тут", "качаемся здесь", "качайся тут", "качайтесь тут", "фарм тут", "фарм здесь", "фармим тут", "фармим здесь", "фарми тут",
			"бей всех", "бейте всех", "мочи всех", "мочите всех", "убивай всех", "зачищай*", "зачисти*", "чисти тут", "качай меня", "покачай меня", "покачай", "кач меня", "качай", "качайте", "сижу качай",
			"kach tut", "kach zdes", "farm tut", "farm here", "farm zdes", "hunt here", "kill all", "kill everything", "bei vseh", "mochi vseh", "kachai menya", "pokachai"));
		ANYWHERE.put(Order.LEAD, phrases("веди", "веди меня", "веди на кач", "паровоз*", "я за тобой", "иду за тобой", "пойду за тобой", "показывай дорогу",
			"vedi", "vedi menya", "parovoz*", "lead", "lead me", "lead the way"));
		WHOLE.put(Order.FOLLOW, Set.of("го", "пошли", "пойдем", "погнали", "идем", "go", "poshli", "pognali", "idem"));
		WHOLE.put(Order.LEAVE, Set.of("пока", "бб", "bb", "bye", "bye bye", "poka", "ну пока", "всем пока", "пока пока", "отбой", "otboy"));
		WHOLE.put(Order.HUNT, Set.of("кач", "фарм", "качаемся", "фармим", "kach", "farm", "kachaemsya", "farmim"));
	}

	/** Чтобы мозги не распустили напарника по ошибке: тег LEAVE выполняется, только если в сообщении есть прощание. */
	private static final Pattern FAREWELL = Pattern.compile("(?:^| )(?:пока\\S*|бб|bb|bye\\S*|poka|свобод\\S*|отпуска\\S*|домой|уход\\S*|уйди|отбой|dismiss\\S*|svobod\\S*|otpuska\\S*|domoy)(?= |$)");

	private PhantomOrders()
	{
	}

	/**
	 * @param text что сказал игрок
	 * @return приказы в порядке упоминания; пусто — это не приказ (вопрос, отрицание, длинная фраза)
	 */
	public static List<Order> parse(String text)
	{
		final List<Order> result = new ArrayList<>();
		if (text.indexOf('?') >= 0)
		{
			return result;
		}

		final String clean = withoutNames(normalize(text));
		if (clean.isEmpty())
		{
			return result;
		}

		final String[] words = clean.split(" ");
		if (words.length > MAX_WORDS)
		{
			return result;
		}

		for (String word : words)
		{
			if (NEGATIONS.contains(word))
			{
				return result;
			}
		}

		final List<int[]> found = new ArrayList<>(); // {позиция, приказ}
		for (Map.Entry<Order, Set<String>> entry : WHOLE.entrySet())
		{
			if (entry.getValue().contains(clean))
			{
				found.add(new int[]
				{
					0,
					entry.getKey().ordinal()
				});
			}
		}

		for (Map.Entry<Order, Pattern> entry : ANYWHERE.entrySet())
		{
			final Matcher matcher = entry.getValue().matcher(clean);
			if (matcher.find())
			{
				found.add(new int[]
				{
					matcher.start(),
					entry.getKey().ordinal()
				});
			}
		}

		found.sort((a, b) -> Integer.compare(a[0], b[0]));
		for (int[] item : found)
		{
			final Order order = Order.values()[item[1]];
			if (!result.contains(order))
			{
				result.add(order);
			}
		}
		// «Бей всех» — это «качаемся тут», а не «бей мою цель»; «веди, я за тобой» — паровоз, а не «за мной».
		if (result.contains(Order.HUNT) || result.contains(Order.LEAD))
		{
			result.remove(Order.ASSIST);
			result.remove(Order.FOLLOW);
		}
		// «Паровозиком покачай» — паровоз: режим один, иначе выполнились бы оба и остался последний (патч 0030).
		if (result.contains(Order.LEAD))
		{
			result.remove(Order.HUNT);
		}
		return result;
	}

	/**
	 * @param text что сказал игрок
	 * @param templates среди кого искать
	 * @return напарники, к которым обратились по имени или прозвищу
	 */
	public static List<PhantomTemplate> addressed(String text, Collection<PhantomTemplate> templates)
	{
		final String clean = " " + normalize(text) + " ";
		final List<PhantomTemplate> result = new ArrayList<>();
		for (PhantomTemplate template : templates)
		{
			for (String alias : template.getAliases())
			{
				if (clean.contains(" " + alias + " "))
				{
					result.add(template);
					break;
				}
			}
		}
		return result;
	}

	/**
	 * @param tag тег из ответа мозгов без скобок
	 * @return приказ или {@code null}
	 */
	public static Order fromTag(String tag)
	{
		for (Order order : Order.values())
		{
			if (order.name().equals(tag))
			{
				return order;
			}
		}
		return null;
	}

	/**
	 * @param text что сказал игрок
	 * @return {@code true}, если игрок прощается или отпускает: только тогда тег LEAVE из ответа мозгов выполняется
	 */
	public static boolean confirmsLeave(String text)
	{
		return FAREWELL.matcher(normalize(text)).find();
	}

	/**
	 * Нижний регистр, «ё» → «е», всё, кроме букв и цифр, — пробел, пробелы схлопнуты.
	 */
	private static String normalize(String text)
	{
		final StringBuilder sb = new StringBuilder(text.length());
		boolean space = true;
		for (char c : text.toLowerCase().replace('ё', 'е').toCharArray())
		{
			if (Character.isLetterOrDigit(c))
			{
				sb.append(c);
				space = false;
			}
			else if (!space)
			{
				sb.append(' ');
				space = true;
			}
		}
		return sb.toString().strip();
	}

	/**
	 * Без имён и прозвищ напарников: «Митяй, бафни» → «бафни».
	 */
	private static String withoutNames(String clean)
	{
		final List<String> words = new ArrayList<>();
		for (String word : clean.split(" "))
		{
			boolean name = false;
			for (PhantomTemplate template : PhantomData.getInstance().getTemplates())
			{
				if (template.getAliases().contains(word))
				{
					name = true;
					break;
				}
			}
			if (!name)
			{
				words.add(word);
			}
		}
		return String.join(" ", words);
	}

	/**
	 * Фразы целыми словами; «*» в конце фразы — любое окончание последнего слова.
	 */
	private static Pattern phrases(String... phrases)
	{
		final StringBuilder sb = new StringBuilder("(?:^| )(?:");
		for (int i = 0; i < phrases.length; i++)
		{
			if (i > 0)
			{
				sb.append('|');
			}
			final String phrase = phrases[i];
			if (phrase.endsWith("*"))
			{
				sb.append(Pattern.quote(phrase.substring(0, phrase.length() - 1))).append("\\S*");
			}
			else
			{
				sb.append(Pattern.quote(phrase));
			}
		}
		return Pattern.compile(sb.append(")(?= |$)").toString());
	}
}
