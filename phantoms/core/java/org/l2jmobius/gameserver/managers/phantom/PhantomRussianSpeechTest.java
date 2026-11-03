package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;

/** Автономная проверка словаря сленга и поля {@code slang} без сервера, БД и JUnit. Её же зовёт {@code HuntersSelfTest = LEARNING}. */
final class PhantomRussianSpeechTest
{
	private PhantomRussianSpeechTest()
	{
	}

	public static void main(String[] args)
	{
		final List<String> failed = check();
		if (!failed.isEmpty())
		{
			throw new IllegalStateException(String.join("; ", failed));
		}
		System.out.println("PhantomRussianSpeechTest: all checks passed.");
	}

	static List<String> check()
	{
		final List<String> failed = new ArrayList<>();

		final String war = PhantomRussianSpeech.slang("го в кв, потом в КХ; возьми поты и сосы");
		expect(failed, "клан-вар", war.contains("клан-вар"));
		expect(failed, "клан-холл", war.contains("клан-холл"));
		expect(failed, "зелья", war.contains("зелья"));
		expect(failed, "сосы — Soulshot", war.contains("Soulshot"));
		expect(failed, "го", war.contains("\"said\":\"го\""));

		// Сокращения, которые уже знает подсказка brain.py: словарь их понимает так же.
		final String brain = PhantomRussianSpeech.slang("пт на рб, нужен хил, ап 40 лвл, сс и бсс есть");
		expect(failed, "пт", brain.contains("\"said\":\"пт\""));
		expect(failed, "рб", brain.contains("рейд-босс"));
		expect(failed, "лвл", brain.contains("уровень персонажа"));
		expect(failed, "ап", brain.contains("\"said\":\"ап\""));
		expect(failed, "сс", brain.contains("\"said\":\"сс\""));
		expect(failed, "бсс", brain.contains("Blessed Spiritshot"));

		final String party = PhantomRussianSpeech.slang("лфм нужен дд, пэт и мамон");
		expect(failed, "поиск пати", party.contains("ищет игроков в пати"));
		expect(failed, "питомец", party.contains("питомец"));
		expect(failed, "мамон", party.contains("Маммона"));
		expect(failed, "латинский сленг", PhantomRussianSpeech.slang("LFP healer, AFK 5 min").contains("сам ищет пати"));

		// Выдуманные значения из первого комплекта убраны: «смс» и «лист» — не сленг L2.
		expect(failed, "смс — не термин", PhantomRussianSpeech.slang("кинь смс").isEmpty());
		expect(failed, "лист — не термин", PhantomRussianSpeech.slang("лист бумаги").isEmpty());
		expect(failed, "подстроки не становятся терминами", PhantomRussianSpeech.slang("купил котлету, качество хорошее, ресурс").isEmpty());
		expect(failed, "обычная речь — пусто", PhantomRussianSpeech.slang("привет, как дела?").isEmpty());
		expect(failed, "не больше восьми терминов", count(PhantomRussianSpeech.slang("го пт фарм дроп сс бсс рб лвл ап кх пк хил баф рес"), "\"said\"") == 8);

		final String request = "{\"kind\":\"phantom\",\"fpc\":\"Кузя\",\"message\":\"где кач? \\u043a\\u0432\",\"player\":{\"message\":\"ложное\"}}";
		final String enriched = PhantomRussianSpeech.enrichRequest(request);
		expect(failed, "одно поле slang в начале", enriched.startsWith("{\"slang\":[{"));
		expect(failed, "kind остаётся", enriched.contains("\"kind\":\"phantom\""));
		expect(failed, "сообщение игрока не тронуто", enriched.endsWith(request.substring(1)));
		expect(failed, "Unicode из JSON распознан", enriched.contains("клан-вар"));
		expect(failed, "без сленга запрос не меняется", PhantomRussianSpeech.enrichRequest("{\"fpc\":\"Кузя\",\"message\":\"привет\"}"), "{\"fpc\":\"Кузя\",\"message\":\"привет\"}");
		expect(failed, "без персонажа запрос не меняется", PhantomRussianSpeech.enrichRequest("{\"kind\":\"other\",\"message\":\"го кв\"}"), "{\"kind\":\"other\",\"message\":\"го кв\"}");
		expect(failed, "null безопасен", PhantomRussianSpeech.enrichRequest(null), null);

		final StringBuilder escaped = new StringBuilder();
		PhantomRussianSpeech.field(escaped, "x", "кв \"тест\"\\\n\r\t\u0001").append(',');
		expect(failed, "field возвращает StringBuilder для цепочки", escaped.toString().endsWith("\","));
		expect(failed, "кавычки экранируются", escaped.toString().contains("\\\"тест\\\""));
		expect(failed, "перевод строки экранируется", escaped.toString().contains("\\n\\r\\t"));
		expect(failed, "управляющий символ экранируется", escaped.toString().contains("\\u0001"));
		return failed;
	}

	private static int count(String text, String what)
	{
		int count = 0;
		for (int i = text.indexOf(what); i >= 0; i = text.indexOf(what, i + 1))
		{
			count++;
		}
		return count;
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
