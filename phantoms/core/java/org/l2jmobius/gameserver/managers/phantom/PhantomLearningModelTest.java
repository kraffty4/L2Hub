package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Advice;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Lesson;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Memory;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Observation;

/** Автономная проверка без сервера, БД и JUnit: javac двух исходников, затем java с этим классом. */
final class PhantomLearningModelTest
{
	private PhantomLearningModelTest()
	{
	}

	public static void main(String[] args)
	{
		final List<String> failed = check();
		if (!failed.isEmpty())
		{
			throw new IllegalStateException(String.join("; ", failed));
		}
		System.out.println("PhantomLearningModelTest: all checks passed.");
	}

	static List<String> check()
	{
		final List<String> failed = new ArrayList<>();
		final Observation bad = observation("HUNT", "Спот;~А", false, 600000, 2, 3, -4, -1000, 0);
		final Observation good = observation("HUNT", "Спот;~А", true, 600000, 0, 60, 8, 12000, 0);
		expect(failed, "полезная охота лучше смертельной", PhantomLearningModel.reward(good, 20, 50000) > PhantomLearningModel.reward(bad, 20, 50000));
		expect(failed, "награда ограничена", PhantomLearningModel.reward(observation("HUNT", "", true, 60000, 0, 100000, 10000, Long.MAX_VALUE, 60000), 20, 50000) <= 1);

		Lesson lesson = PhantomLearningModel.learn(null, bad, 20, 50000, 1000);
		expect(failed, "первый уровень", PhantomLearningModel.level(lesson), 1);
		expect(failed, "одна неудача не запрещает вариант", PhantomLearningModel.note(lesson).isEmpty());
		for (int i = 0; i < 5; i++)
		{
			lesson = PhantomLearningModel.learn(lesson, good, 20, 50000, 2000 + i);
		}
		expect(failed, "опыт восстанавливается после неудачи", lesson.score > 0);
		expect(failed, "число наблюдений", lesson.attempts, 6);
		expect(failed, "третий уровень", PhantomLearningModel.level(lesson), 3);
		for (int i = 0; i < 6; i++)
		{
			lesson = PhantomLearningModel.learn(lesson, good, 20, 50000, 3000 + i);
		}
		expect(failed, "четвёртый уровень", PhantomLearningModel.level(lesson), 4);
		expect(failed, "уверенность не становится стопроцентной", PhantomLearningModel.confidence(lesson) < 1);

		// Сохраняется запись спота: ключ — имя спота с разделителями и кириллицей (у записи действия ключ — тег, HUNT).
		final Lesson spotLesson = PhantomLearningModel.learnSpot(lesson, good, 20, 50000, 4000);
		final Map<String, Lesson> lessons = new LinkedHashMap<>();
		PhantomLearningModel.rememberLesson(lessons, spotLesson, PhantomLearningModel.MAX_SPOTS);
		final String encoded = PhantomLearningModel.encodeLessons(lessons, PhantomLearningModel.MAX_SPOTS);
		final Map<String, Lesson> decoded = PhantomLearningModel.decodeLessons(encoded + ";битая~запись", PhantomLearningModel.MAX_SPOTS);
		expect(failed, "Unicode и разделители переживают сохранение", decoded.containsKey("Спот;~А"));
		expect(failed, "округлённая полезность переживает сохранение", decoded.containsKey("Спот;~А") && (Math.abs(decoded.get("Спот;~А").score - spotLesson.score) < 0.0001));
		expect(failed, "пустая память", PhantomLearningModel.decodeLessons(null, 10).isEmpty());
		expect(failed, "битая память не роняет загрузку", PhantomLearningModel.decodeLessons("%%%%~x", 10).isEmpty());
		for (int i = 0; i < 40; i++)
		{
			PhantomLearningModel.rememberLesson(lessons, PhantomLearningModel.learnSpot(null, observation("HUNT", "spot-" + i, true, 60000, 0, 1, 1, 0, 0), 20, 50000, 10000 + i), PhantomLearningModel.MAX_SPOTS);
		}
		expect(failed, "память ограничена", lessons.size(), PhantomLearningModel.MAX_SPOTS);
		expect(failed, "старые записи вытесняются", !lessons.containsKey("spot-0") && lessons.containsKey("spot-39"));

		Memory memory = new Memory();
		expect(failed, "неопределённость до трёх наблюдений", PhantomLearningModel.shouldAsk(memory, "Спот", 4000, 10000));
		Advice advice = PhantomLearningModel.advise(null, "Спот", 100, 1, 1000);
		expect(failed, "первый совет мало весит", advice.trust() == 0.2);
		advice = PhantomLearningModel.advise(advice, "Спот", 100, -1, 2000);
		expect(failed, "тот же человек не накручивает доверие", advice.support, 1);
		advice = PhantomLearningModel.advise(advice, "Спот", 200, 1, 3000);
		expect(failed, "второй человек повышает доверие", advice.support, 2);
		PhantomLearningModel.rememberAdvice(memory, advice);
		final Map<String, Advice> decodedAdvice = PhantomLearningModel.decodeAdvice(PhantomLearningModel.encodeAdvice(memory.advice));
		expect(failed, "совет переживает сохранение", decodedAdvice.containsKey("Спот") && decodedAdvice.get("Спот").sources.equals(advice.sources));
		expect(failed, "свежий совет не переспрашивается", !PhantomLearningModel.shouldAsk(memory, "Спот", 4000, 10000));
		expect(failed, "устаревший совет можно уточнить", PhantomLearningModel.shouldAsk(memory, "Спот", 14001, 10000));
		memory.spots.put("Другой", PhantomLearningModel.learnSpot(null, good, 20, 50000, 1));
		expect(failed, "неизвестный спот можно уточнить", PhantomLearningModel.shouldAsk(memory, "Другой", 1, 10000));

		// Ответы на «стоит тут качаться?»: +1 советует, -1 не советует, 0 непонятно. Фразы разбора 0.5.172 — первыми.
		final Object[][] answers =
		{
			{"Да, тут норм, качай", 1},
			{"Не советую, я там дважды умер", -1},
			{"не плохо", 1},
			{"не хорошо", -1},
			{"не безопасно", -1},
			{"да ну нафиг", -1},
			{"not good", -1},
			{"not safe", -1},
			{"no problem", 1},
			{"too dangerous", -1},
			{"неплохо", 1},
			{"хорошо", 1},
			{"отлично", 1},
			{"плохо", -1},
			{"опасно", -1},
			{"не опасно", 1},
			{"хорошо, но опасно", 0},
			{"привет, как дела?", 0},
			{"да", 1},
			{"нет", -1},
			{"неа", -1},
			{"ага, норм спот", 1},
			{"нормально", 1},
			{"советую", 1},
			{"не советую", -1},
			{"не стоит", -1},
			{"стоит", 1},
			{"не очень", -1},
			{"не норм", -1},
			{"лучше мобов послабее", -1},
			{"тебе рановато сюда", -1},
			{"тут сливают постоянно", -1},
			{"иди в другое место", -1},
			{"уходи отсюда, агро", -1},
			{"изи спот, качайся", 1},
			{"годный спот", 1},
			{"пойдет", 1},
			{"не пойдет", -1},
			{"не знаю", 0},
			{"хз", 0},
			{"да не знаю я", 0},
			{"не был там", 0},
			{"ок", 1},
			{"топ", 1},
			{"топать далеко", 0},
			{"дали мне по шее", 0},
			{"Yes, good spot", 1},
			{"no, bad", -1},
			{"тяжело тут", -1},
			{"безопасно", 1},
			{"ёлки, отлично", 1},
		};
		for (Object[] answer : answers)
		{
			expect(failed, "оценка «" + answer[0] + "»", PhantomLearningModel.parseRating((String) answer[0]), answer[1]);
		}
		expect(failed, "слишком длинный текст не записывается", PhantomLearningModel.parseRating("да".repeat(200)), 0);

		// Шёпот с просьбой — не оценка: его выполняет обычный чат.
		expect(failed, "короткий ответ — оценка", PhantomLearningModel.isPlainAnswer("да, тут норм"));
		expect(failed, "«да, кинь реку» — просьба", !PhantomLearningModel.isPlainAnswer("да, кинь реку"));
		expect(failed, "«нет, дай реку» — просьба", !PhantomLearningModel.isPlainAnswer("нет, дай реку"));
		expect(failed, "«да, возьми в пати» — просьба", !PhantomLearningModel.isPlainAnswer("да, возьми в пати"));
		expect(failed, "«не советую, бафни» — просьба", !PhantomLearningModel.isPlainAnswer("не советую, бафни"));
		expect(failed, "тег — не оценка", !PhantomLearningModel.isPlainAnswer("да [[SPOT_TO Cruma]]"));
		expect(failed, "рекомендую — не «рек»", PhantomLearningModel.isPlainAnswer("рекомендую"));
		expect(failed, "длинный рассказ — не оценка", !PhantomLearningModel.isPlainAnswer("да, ".repeat(30)));
		return failed;
	}

	private static Observation observation(String action, String spot, boolean executed, long millis, int deaths, int kills, double exp, long adena, long party)
	{
		return new Observation(action, spot, executed, millis, deaths, kills, exp, adena, party);
	}

	private static void expect(List<String> failed, String what, boolean condition)
	{
		if (!condition)
		{
			failed.add(what);
		}
	}

	private static void expect(List<String> failed, String what, Object got, Object want)
	{
		if ((got == null) ? (want != null) : !got.equals(want))
		{
			failed.add(what + ": " + got + " вместо " + want);
		}
	}
}
