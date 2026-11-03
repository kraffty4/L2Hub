package org.l2jmobius.gameserver.managers.phantom;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Детерминированная модель опыта охотника. Не зависит от Mobius: отдельно компилируется и проверяется на JDK.
 * Полезность — EMA с постепенно уменьшающимся весом нового наблюдения; уровень знания означает надёжность опыта, а не его положительность.
 */
final class PhantomLearningModel
{
	static final int MAX_ACTIONS = 16;
	static final int MAX_SPOTS = 24;
	static final int MAX_ADVICE = 6;
	static final int MAX_KEY = 96;
	static final int MAX_ANSWER = 240;
	static final int MAX_SOURCES = 4;

	/** Ответ длиннее — уже разговор, а не оценка спота. */
	static final int MAX_RATING_ANSWER = 80;

	// Основы слов: граница только перед словом, окончание любое («хорош» — хорошо, хороший; «опас» — опасно, опасный).
	// Короткие слова (да, нет, ок, топ) — целиком, иначе «да» найдётся в «дали», а «топ» — в «топать».
	private static final String POSITIVE_STEMS = "хорош|неплох|норм|отличн|отлично|годн|совет|рекоменд|подойд|подход|приход|качайс|качай|безопас|изи|легк|кайф|збс|сойд|пойдет|стоит";
	private static final String POSITIVE_WORDS = "да|ага|угу|ок|окей|ok|okay|yes|yep|good|safe|топ|имба|конечно|канеш";
	private static final String NEGATIVE_STEMS = "плох|опас|смерт|умер|умир|сдох|слив|слил|бред|послабе|слабе|уход|уйди|уйти|меняй|тяжел|жестк|жесть|больно|рано|ужас|отстой";
	private static final String NEGATIVE_WORDS = "нет|неа|не-а|no|nope|bad|die|danger|dangerous|deadly|фиг|фигня|мимо";
	private static final Pattern POSITIVE = Pattern.compile("(?<!\\p{L})(?:" + POSITIVE_STEMS + ")|(?<!\\p{L})(?:" + POSITIVE_WORDS + ")(?!\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern NEGATIVE = Pattern.compile("(?<!\\p{L})(?:" + NEGATIVE_STEMS + ")|(?<!\\p{L})(?:" + NEGATIVE_WORDS + ")(?!\\p{L})|(?<!\\p{L})друг\\p{L}*\\s+(?:спот|мест)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	/** Отрицание перед похвалой: «не советую», «не стоит», «не норм», «не очень». Ищется первым и вырезается, чтобы «советую» внутри не засчиталось. */
	private static final Pattern NEGATED = Pattern.compile("(?<!\\p{L})(?:не\\s+(?:совет|рекоменд|ход|иди|качай|кач|стои|стоит|пойд|подойд|норм|оч|топ|годн|приход|лезь|суйся|вывез|хорош|безопас|легк|изи|отличн)|(?:not|don'?t|do\\s+not)\\s+(?:good|safe|great|worth|recommend|go|come|ok|okay))\\p{L}*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	/** «Не знаю», «хз» — честное «непонятно», даже если рядом есть «да». */
	private static final Pattern UNSURE = Pattern.compile("(?<!\\p{L})(?:не\\s+знаю|хз|без\\s+понятия|не\\s+в\\s+курсе|не\\s+был|не\\s+качался|не\\s+помню)(?!\\p{L})", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	/** Просьба или приказ в ответе: «да, кинь реку», «нет, дай реку», «да, возьми в пати». Такой шёпот — не оценка, он идёт в обычный чат. */
	// Основы глаголов просьбы — с любым окончанием (бафни, бафните, возьмёшь); короткие слова — целиком (рес, пт, тп).
	private static final String REQUEST_STEMS = "кинь|кини|дай|дашь|дайте|возьм|прими|бафн|бафай|хильн|ресн|пригласи|инвайт|позови|продай|продаш|трейд|телепорт|подойди|приди|помоги|сопровод|следуй";
	private static final String REQUEST_WORDS = "баф|хил|хиль|рес|рек|реку|реки|пати|пт|invite|inv|зови|купи|купишь|trade|тп|иди\\s+сюда|за\\s+мной";
	private static final Pattern REQUEST = Pattern.compile("(?<!\\p{L})(?:" + REQUEST_STEMS + ")|(?<!\\p{L})(?:" + REQUEST_WORDS + ")(?!\\p{L})|\\[\\[", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	record Observation(String action, String spot, boolean executed, long millis, int deaths, int kills, double exp, long adena, long partyMillis)
	{
	}

	static final class Lesson
	{
		final String key;
		final int attempts;
		final int successes;
		final int deaths;
		final long kills;
		final double score;
		final double expPerHour;
		final double adenaPerHour;
		final long updated;

		private Lesson(String key, int attempts, int successes, int deaths, long kills, double score, double expPerHour, double adenaPerHour, long updated)
		{
			this.key = key;
			this.attempts = attempts;
			this.successes = successes;
			this.deaths = deaths;
			this.kills = kills;
			this.score = score;
			this.expPerHour = expPerHour;
			this.adenaPerHour = adenaPerHour;
			this.updated = updated;
		}
	}

	static final class Advice
	{
		final String topic;
		final double rating;
		final int support;
		final long updated;
		final List<Integer> sources;

		private Advice(String topic, double rating, int support, long updated, List<Integer> sources)
		{
			this.topic = topic;
			this.rating = rating;
			this.support = support;
			this.updated = updated;
			this.sources = List.copyOf(sources);
		}

		double trust()
		{
			return Math.min(0.8, support * 0.2);
		}
	}

	static final class Memory
	{
		final Map<String, Lesson> actions = new LinkedHashMap<>();
		final Map<String, Lesson> spots = new LinkedHashMap<>();
		final Map<String, Advice> advice = new LinkedHashMap<>();
	}

	private PhantomLearningModel()
	{
	}

	/**
	 * Оценка одного наблюдения от -1 до 1. Опыт и адена нормируются настройками сервера, поэтому рейты не вшиты в алгоритм.
	 * Смерть заметно снижает оценку, но следующие наблюдения могут её восстановить.
	 */
	static double reward(Observation observation, double targetExpPerHour, double targetAdenaPerHour)
	{
		final double hours = Math.max(observation.millis / 3600000.0, 1.0 / 60);
		final double expRate = observation.exp / hours;
		final double adenaRate = observation.adena / hours;
		final double killRate = observation.kills / hours;
		final double partyShare = observation.millis <= 0 ? 0 : clamp(observation.partyMillis / (double) observation.millis, 0, 1);
		double score = 0.45 * clamp(expRate / Math.max(1, targetExpPerHour), -1, 2);
		score += 0.10 * clamp(adenaRate / Math.max(1, targetAdenaPerHour), -1, 1);
		score += 0.10 * clamp(killRate / 30, 0, 1);
		score += observation.executed ? 0.10 : -0.20;
		score += partyShare * ("PARTY".equals(observation.action) ? 0.15 : 0.05);
		score -= Math.min(0.60, observation.deaths * 0.30);
		return clamp(score, -1, 1);
	}

	static Lesson learn(Lesson before, Observation observation, double targetExpPerHour, double targetAdenaPerHour, long now)
	{
		final double hours = Math.max(observation.millis / 3600000.0, 1.0 / 60);
		final double sample = reward(observation, targetExpPerHour, targetAdenaPerHour);
		final int attempts = before == null ? 1 : before.attempts + 1;
		final double weight = before == null ? 0 : Math.min(before.attempts, 8);
		return new Lesson(cut(observation.action, MAX_KEY), attempts, (before == null ? 0 : before.successes) + (observation.executed ? 1 : 0), (before == null ? 0 : before.deaths) + Math.max(0, observation.deaths),
			(before == null ? 0 : before.kills) + Math.max(0, observation.kills), average(before == null ? 0 : before.score, weight, sample), average(before == null ? 0 : before.expPerHour, weight, observation.exp / hours),
			average(before == null ? 0 : before.adenaPerHour, weight, observation.adena / hours), now);
	}

	static Lesson learnSpot(Lesson before, Observation observation, double targetExpPerHour, double targetAdenaPerHour, long now)
	{
		final Lesson action = learn(before, observation, targetExpPerHour, targetAdenaPerHour, now);
		return new Lesson(cut(observation.spot, MAX_KEY), action.attempts, action.successes, action.deaths, action.kills, action.score, action.expPerHour, action.adenaPerHour, action.updated);
	}

	private static double average(double before, double weight, double sample)
	{
		return ((before * weight) + sample) / (weight + 1);
	}

	static int level(Lesson lesson)
	{
		if ((lesson == null) || (lesson.attempts <= 0))
		{
			return 0;
		}
		return lesson.attempts >= 12 ? 4 : lesson.attempts >= 6 ? 3 : lesson.attempts >= 3 ? 2 : 1;
	}

	static String levelName(int level)
	{
		return switch (level)
		{
			case 1 -> "пробует";
			case 2 -> "знает";
			case 3 -> "опытный";
			case 4 -> "мастер";
			default -> "не знаком";
		};
	}

	static double confidence(Lesson lesson)
	{
		return lesson == null ? 0 : Math.min(0.95, lesson.attempts / (lesson.attempts + 3.0));
	}

	/**
	 * Вывод по своему опыту для {@code options[].note}: только когда он уже есть (3+ наблюдения) и ясен. Цифры по местам стратег получает
	 * из журнала brain.py («Твой опыт по местам»), поэтому здесь их нет — без дубля и лишних токенов.
	 */
	static String note(Lesson lesson)
	{
		if ((lesson == null) || (lesson.attempts < 3))
		{
			return "";
		}
		if (lesson.score <= -0.35)
		{
			return "по своему опыту выходило плохо (" + lesson.attempts + " раз), повторять только при веской причине";
		}
		if (lesson.score >= 0.25)
		{
			return "по своему опыту проверенный вариант (" + lesson.attempts + " раз)";
		}
		return "";
	}

	static boolean shouldAsk(Memory memory, String spot, long now, long adviceTtl)
	{
		if ((spot == null) || spot.isBlank())
		{
			return false;
		}
		final Advice advice = memory.advice.get(spot);
		if ((advice != null) && ((now - advice.updated) < adviceTtl))
		{
			return false;
		}
		final Lesson lesson = memory.spots.get(spot);
		return (lesson == null) || (lesson.attempts < 3);
	}

	static Advice advise(Advice before, String topic, int source, int rating, long now)
	{
		final List<Integer> sources = before == null ? new ArrayList<>() : new ArrayList<>(before.sources);
		final boolean known = sources.contains(source);
		if (!known)
		{
			sources.add(source);
			while (sources.size() > MAX_SOURCES)
			{
				sources.remove(0);
			}
		}
		final int support = before == null ? 1 : Math.min(MAX_SOURCES, before.support + (known ? 0 : 1));
		final double previousWeight = before == null ? 0 : Math.max(1, before.support - (known ? 1 : 0));
		final double previousRating = before == null ? 0 : before.rating * previousWeight;
		return new Advice(cut(topic, MAX_KEY), clamp((previousRating + rating) / (previousWeight + 1), -1, 1), support, now, sources);
	}

	static void rememberLesson(Map<String, Lesson> lessons, Lesson lesson, int limit)
	{
		lessons.remove(lesson.key);
		lessons.put(lesson.key, lesson);
		trimLessons(lessons, limit);
	}

	static void rememberAdvice(Memory memory, Advice advice)
	{
		memory.advice.remove(advice.topic);
		memory.advice.put(advice.topic, advice);
		while (memory.advice.size() > MAX_ADVICE)
		{
			final String oldest = memory.advice.values().stream().min(Comparator.comparingLong(value -> value.updated)).map(value -> value.topic).orElse("");
			memory.advice.remove(oldest);
		}
	}

	private static void trimLessons(Map<String, Lesson> lessons, int limit)
	{
		while (lessons.size() > limit)
		{
			final String oldest = lessons.values().stream().min(Comparator.comparingLong(value -> value.updated)).map(value -> value.key).orElse("");
			lessons.remove(oldest);
		}
	}

	static String encodeLessons(Map<String, Lesson> lessons, int limit)
	{
		final List<Lesson> ordered = new ArrayList<>(lessons.values());
		ordered.sort(Comparator.comparingLong((Lesson value) -> value.updated).reversed().thenComparing(value -> value.key));
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; (i < ordered.size()) && (i < limit); i++)
		{
			final Lesson lesson = ordered.get(i);
			sb.append(sb.length() > 0 ? ";" : "").append(encode(lesson.key)).append('~').append(lesson.attempts).append('~').append(lesson.successes).append('~').append(lesson.deaths).append('~').append(lesson.kills);
			sb.append('~').append(Math.round(lesson.score * 10000)).append('~').append(Math.round(lesson.expPerHour * 100)).append('~').append(Math.round(lesson.adenaPerHour)).append('~').append(lesson.updated);
		}
		return sb.toString();
	}

	static Map<String, Lesson> decodeLessons(String stored, int limit)
	{
		final Map<String, Lesson> lessons = new LinkedHashMap<>();
		if ((stored == null) || stored.isEmpty())
		{
			return lessons;
		}
		for (String row : stored.split(";"))
		{
			try
			{
				final String[] fields = row.split("~", -1);
				if (fields.length != 9)
				{
					continue;
				}
				final String key = decode(fields[0]);
				final double score = Double.parseDouble(fields[5]) / 10000;
				if (key.isEmpty() || !Double.isFinite(score))
				{
					continue;
				}
				final Lesson lesson = new Lesson(cut(key, MAX_KEY), Math.max(0, Integer.parseInt(fields[1])), Math.max(0, Integer.parseInt(fields[2])), Math.max(0, Integer.parseInt(fields[3])), Math.max(0, Long.parseLong(fields[4])), clamp(score, -1, 1),
					Double.parseDouble(fields[6]) / 100, Double.parseDouble(fields[7]), Math.max(0, Long.parseLong(fields[8])));
				if (Double.isFinite(lesson.expPerHour) && Double.isFinite(lesson.adenaPerHour))
				{
					rememberLesson(lessons, lesson, limit);
				}
			}
			catch (IllegalArgumentException e)
			{
				// Повреждённая запись пропускается, остальная память продолжает работать.
			}
		}
		return lessons;
	}

	static String encodeAdvice(Map<String, Advice> advice)
	{
		final List<Advice> ordered = new ArrayList<>(advice.values());
		ordered.sort(Comparator.comparingLong((Advice value) -> value.updated).reversed().thenComparing(value -> value.topic));
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; (i < ordered.size()) && (i < MAX_ADVICE); i++)
		{
			final Advice value = ordered.get(i);
			sb.append(sb.length() > 0 ? ";" : "").append(encode(value.topic)).append('~').append(Math.round(value.rating * 1000)).append('~').append(value.support).append('~').append(value.updated).append('~');
			for (int source = 0; source < value.sources.size(); source++)
			{
				sb.append(source > 0 ? "," : "").append(value.sources.get(source));
			}
		}
		return sb.toString();
	}

	static Map<String, Advice> decodeAdvice(String stored)
	{
		final Memory memory = new Memory();
		if ((stored == null) || stored.isEmpty())
		{
			return memory.advice;
		}
		for (String row : stored.split(";"))
		{
			try
			{
				final String[] fields = row.split("~", -1);
				if (fields.length != 5)
				{
					continue;
				}
				final String topic = decode(fields[0]);
				final List<Integer> sources = new ArrayList<>();
				for (String source : fields[4].split(","))
				{
					final int id = Integer.parseInt(source);
					if ((id > 0) && !sources.contains(id))
					{
						sources.add(id);
					}
				}
				if (!topic.isEmpty() && !sources.isEmpty())
				{
					rememberAdvice(memory, new Advice(cut(topic, MAX_KEY), clamp(Double.parseDouble(fields[1]) / 1000, -1, 1), Math.max(1, Math.min(MAX_SOURCES, Integer.parseInt(fields[2]))), Math.max(0, Long.parseLong(fields[3])), sources));
				}
			}
			catch (IllegalArgumentException e)
			{
				// Как и выше: одна битая строка не обнуляет память.
			}
		}
		return memory.advice;
	}

	/**
	 * Единственная интерпретация ответа человека: +1, -1 или 0 (непонятно). Никаких тегов, имён команд и свободных действий.
	 * Отрицание («не советую», «не стоит») сильнее похвалы внутри него; «не плохо» и «не опасно» — похвала; «не знаю» — непонятно.
	 */
	static int parseRating(String text)
	{
		if ((text == null) || text.isBlank() || (text.length() > MAX_ANSWER))
		{
			return 0;
		}
		String clean = text.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").strip();
		if (UNSURE.matcher(clean).find())
		{
			return 0;
		}
		clean = clean.replaceAll("(?<!\\p{L})не\\s+плох", "неплох").replaceAll("(?<!\\p{L})не\\s+опас", "безопас").replaceAll("(?<!\\p{L})не\\s+умр", "безопас");
		// «Да ну нафиг» — отказ, а не «да»; «no problem» — согласие, а не «no».
		clean = clean.replaceAll("(?<!\\p{L})да\\s+ну(?!\\p{L})", "нет").replaceAll("(?<!\\p{L})no\\s+problem\\p{L}*", "ok");
		final boolean negated = NEGATED.matcher(clean).find();
		final String rest = NEGATED.matcher(clean).replaceAll(" ");
		final boolean positive = POSITIVE.matcher(rest).find();
		final boolean negative = negated || NEGATIVE.matcher(rest).find();
		return positive == negative ? 0 : positive ? 1 : -1;
	}

	/**
	 * Годится ли шёпот в оценку спота: короткий и без другой просьбы. «Да, кинь реку» — просьба, её выполняет обычный чат, а не память.
	 */
	static boolean isPlainAnswer(String text)
	{
		return (text != null) && !text.isBlank() && (text.strip().length() <= MAX_RATING_ANSWER) && !REQUEST.matcher(text.toLowerCase(Locale.ROOT).replace('ё', 'е')).find();
	}

	private static String encode(String value)
	{
		return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
	}

	private static String decode(String value)
	{
		return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
	}

	private static String cut(String value, int max)
	{
		final String clean = value == null ? "" : value.strip();
		return clean.length() > max ? clean.substring(0, max) : clean;
	}

	private static double clamp(double value, double min, double max)
	{
		return Math.max(min, Math.min(max, value));
	}
}
