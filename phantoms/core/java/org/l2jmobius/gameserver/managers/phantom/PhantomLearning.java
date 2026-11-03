package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Choice;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Decision;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Advice;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Lesson;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Memory;
import org.l2jmobius.gameserver.managers.phantom.PhantomLearningModel.Observation;

/**
 * L2Hub contrib: постоянный опыт решений и спотов. Работает независимо от Python brain.py и не меняет протокол ответов стратега.
 * Модель получает только проверенные числовые оценки; выполнение решений по-прежнему ограничено PhantomHunterStrategy.parse.
 */
public final class PhantomLearning
{
	private static final Logger LOGGER = Logger.getLogger(PhantomLearning.class.getName());

	static final String ACTIONS_VARIABLE = "L2HubLearnActionsV1";
	static final String SPOTS_VARIABLE = "L2HubLearnSpotsV1";
	static final String ADVICE_VARIABLE = "L2HubLearnAdviceV1";
	private static final long MIN_SAMPLE = 300000;
	/** Решение без смены дольше трёх часов (ночь по правилам без живых) — не опыт одного решения: не учитывается. */
	private static final long MAX_SAMPLE = 3 * 3600000L;
	/** Спот получает итог, только если охотник пробыл на нём хотя бы половину решения (иначе это город и дорога). */
	private static final double SPOT_SHARE = 0.5;
	private static final long LOOK_GAP = 30000;

	private record Pending(String spot, long until)
	{
	}

	private final Map<Integer, Memory> _memories = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _observed = new ConcurrentHashMap<>();
	private final Map<Long, Pending> _questions = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextLook = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _nextQuestion = new ConcurrentHashMap<>();

	private PhantomLearning()
	{
	}

	/**
	 * Итог решения целиком — в момент, когда его сменяет следующее решение стратега или охотник возвращается в игру после выхода
	 * (менеджер зовёт до {@code away}). Не по первой минуте: смена спота — это и дорога, и сама охота. Короче {@value #MIN_SAMPLE} мс
	 * наблюдения не учитывается: нормировка на час раздула бы случайность. Одно решение — один раз.
	 */
	public synchronized void observe(Player player, Choice choice)
	{
		// Решения самопроверок (facts.test) в память охотника не ложатся.
		if (!PhantomHuntersConfig.HUNTER_LEARNING || (choice == null) || !choice.started || choice.away || choice.test || (choice.tracked < MIN_SAMPLE) || (choice.tracked > MAX_SAMPLE) || (_observed.getOrDefault(player.getObjectId(), 0L) == choice.at))
		{
			return;
		}
		_observed.put(player.getObjectId(), choice.at);

		final double exp = ((player.getLevel() - choice.levelFrom) * 100.0) + PhantomHunterStrategy.levelPercent(player) - choice.expFrom;
		final String spot = longestSpot(choice);
		final Observation observation = new Observation(choice.decision.name(), spot, choice.done && choice.ok, choice.tracked, choice.deaths, choice.kills.get(), exp, player.getAdena() - choice.adenaFrom, choice.partyTime);
		final Memory memory = memory(player);
		final long now = System.currentTimeMillis();
		PhantomLearningModel.rememberLesson(memory.actions, PhantomLearningModel.learn(memory.actions.get(choice.decision.name()), observation, PhantomHuntersConfig.HUNTER_LEARNING_EXP_PER_HOUR, PhantomHuntersConfig.HUNTER_LEARNING_ADENA_PER_HOUR, now), PhantomLearningModel.MAX_ACTIONS);
		if (!spot.isEmpty())
		{
			PhantomLearningModel.rememberLesson(memory.spots, PhantomLearningModel.learnSpot(memory.spots.get(spot), observation, PhantomHuntersConfig.HUNTER_LEARNING_EXP_PER_HOUR, PhantomHuntersConfig.HUNTER_LEARNING_ADENA_PER_HOUR, now), PhantomLearningModel.MAX_SPOTS);
		}
		storeLessons(player, memory);
		final Lesson learned = memory.actions.get(choice.decision.name());
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " learned " + choice + " -> score " + String.format(Locale.ROOT, "%.2f", learned.score) + " of " + learned.attempts + (spot.isEmpty() ? "" : ", spot " + spot) + ".");
	}

	private static String longestSpot(Choice choice)
	{
		String spot = "";
		long longest = 0;
		long total = 0;
		for (Map.Entry<String, Long> entry : choice.where.entrySet())
		{
			total += entry.getValue();
			if (!entry.getKey().isEmpty() && (entry.getValue() > longest))
			{
				spot = entry.getKey();
				longest = entry.getValue();
			}
		}
		// Минута на споте и двадцать в городе — итог города, а не спота.
		return (total > 0) && (longest >= (total * SPOT_SHARE)) ? spot : "";
	}

	/**
	 * Опыт добавляется в существующий note варианта: даже brain.py, не знающий поле learning, увидит подсказку в привычном месте.
	 */
	public synchronized String note(Player player, Decision decision)
	{
		return PhantomHuntersConfig.HUNTER_LEARNING ? PhantomLearningModel.note(memory(player).actions.get(decision.name())) : "";
	}

	/**
	 * Поле {@code learning} запроса стратега — только то, чего нет в журнале brain.py: советы живых игроков о местах и насколько охотник знает
	 * текущий спот. Опыт по местам в цифрах brain.py считает сам из итогов решений ({@code last.result} → журнал → «Твой опыт по местам»),
	 * поэтому здесь его нет. Ключ выключен или сказать нечего — поля нет совсем.
	 */
	public synchronized void json(StringBuilder sb, Player player, String currentSpot)
	{
		if (!PhantomHuntersConfig.HUNTER_LEARNING)
		{
			return;
		}
		final Memory memory = memory(player);
		final long fresh = System.currentTimeMillis() - adviceTtl();
		final List<Advice> advice = new ArrayList<>();
		for (Advice value : memory.advice.values())
		{
			// Совет старше HunterLearningAdviceDays устарел: стратегу не уходит.
			if (value.updated >= fresh)
			{
				advice.add(value);
			}
		}
		advice.sort(Comparator.comparing((Advice value) -> !value.topic.equals(currentSpot)).thenComparing(Comparator.comparingLong((Advice value) -> value.updated).reversed()));
		final Lesson here = currentSpot.isEmpty() ? null : memory.spots.get(currentSpot);
		if (advice.isEmpty() && (here == null))
		{
			return;
		}
		sb.append("\"learning\":{");
		if (here != null)
		{
			FakePlayerBrain.field(sb, "spot", here.key).append(',');
			sb.append("\"knowledge\":").append(PhantomLearningModel.level(here)).append(',');
		}
		sb.append("\"advice\":[");
		int count = 0;
		for (Advice value : advice)
		{
			if (count >= 3)
			{
				break;
			}
			sb.append(count++ > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "spot", value.topic).append(',');
			sb.append("\"rating\":").append(String.format(Locale.ROOT, "%.2f", value.rating)).append(",\"people\":").append(value.support).append('}');
		}
		sb.append("]},");
	}

	/**
	 * Короткая сводка для существующего L2HubHunters.json, без увеличения его на всю историю.
	 */
	public synchronized void status(StringBuilder sb, Player player)
	{
		if (!PhantomHuntersConfig.HUNTER_LEARNING)
		{
			return;
		}
		final Memory memory = memory(player);
		double knowledge = 0;
		for (Lesson lesson : memory.actions.values())
		{
			knowledge += PhantomLearningModel.level(lesson);
		}
		for (Lesson lesson : memory.spots.values())
		{
			knowledge += PhantomLearningModel.level(lesson);
		}
		final int records = memory.actions.size() + memory.spots.size();
		sb.append(",\"learning\":{\"actions\":").append(memory.actions.size()).append(",\"spots\":").append(memory.spots.size()).append(",\"knowledge\":").append(String.format(Locale.ROOT, "%.1f", records == 0 ? 0 : knowledge / records)).append('}');
	}

	/**
	 * Вопрос только при неопределённости, живому игроку рядом и через общие фильтры инициативы. Ответ принимается шёпотом в ограниченном окне.
	 */
	public synchronized void control(Player hunter, String spot, boolean realPlayers, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_LEARNING || !PhantomHuntersConfig.HUNTER_LEARNING_QUESTIONS || !realPlayers || spot.isEmpty() || hunter.isDead() || (now < _nextLook.getOrDefault(hunter.getObjectId(), 0L)) || (now < _nextQuestion.getOrDefault(hunter.getObjectId(), 0L)))
		{
			return;
		}
		// Те же шлюзы, что у «заговорить первым» (PhantomInitiative.look): выключатель, мозги, не в бою, не на бегу, не усталый, вокруг не только что говорили.
		// Заготовки вопроса русские, а банка реплик по-английски нет: при L2HubLanguage = en не спрашивает.
		if (!PhantomHuntersConfig.HUNTER_INITIATIVE || !PhantomChat.brainEnabled() || "en".equals(L2HubWorldConfig.LANGUAGE) || hunter.isInCombat() || hunter.isTeleporting() || (hunter.getCurrentHp() < (hunter.getMaxHp() * 0.5)) || (PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED) || !PhantomQuiet.getInstance().allowed(hunter, PhantomQuiet.INITIATIVE, now))
		{
			return;
		}
		_nextLook.put(hunter.getObjectId(), now + LOOK_GAP);
		final long high = (long) hunter.getObjectId() << 32;
		_questions.entrySet().removeIf(entry -> ((entry.getKey() & 0xffffffff00000000L) == high) && (now >= entry.getValue().until));
		if (!PhantomLearningModel.shouldAsk(memory(hunter), spot, now, adviceTtl()))
		{
			return;
		}

		for (Player player : World.getVisibleObjectsInRange(hunter, Player.class, PhantomHuntersConfig.HUNTER_INITIATIVE_RANGE))
		{
			if (!isLive(player) || !PhantomInitiative.getInstance().mayAddress(hunter, player, now))
			{
				continue;
			}
			// Кулдауны инициативы — да, ожидание ответа инициативы — нет: молчание на вопрос о споте игнором не считается
			// (мозги могли и промолчать), ответ ждёт своё окно в _questions.
			PhantomInitiative.getInstance().book(hunter, player, now);
			final long until = now + (PhantomHuntersConfig.HUNTER_LEARNING_ANSWER_WAIT * 1000L);
			_questions.put(pair(hunter, player), new Pending(spot, until));
			_nextQuestion.put(hunter.getObjectId(), now + (PhantomHuntersConfig.HUNTER_LEARNING_QUESTION_COOLDOWN * 1000L));
			final String question = "ты качался на споте «" + spot + "»? мне " + hunter.getLevel() + "-му, стоит тут качаться или лучше мобов послабее?";
			final String context = "Ты пробуешь спот «" + spot + "», но своих наблюдений пока мало. Задай " + player.getName() + " один короткий вопрос: стоит ли тебе здесь качаться или лучше поискать мобов послабее. Не обещай действий и не используй теги.";
			PhantomChat.getInstance().hunterEvent(hunter, player, "LEARNING_ASK", context, question, 0, true);
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " asks " + player.getName() + " about " + spot + ", answer window " + PhantomHuntersConfig.HUNTER_LEARNING_ANSWER_WAIT + " s.");
			return;
		}
	}

	/**
	 * Вопрос и благодарность — обычные реплики существующего чата. В память попадает только рейтинг, не текст и не команда.
	 * @return true, если распознанный ответ уже обработан; непонятная реплика продолжает обычный маршрут PhantomChat
	 */
	public synchronized boolean onWhisper(Player speaker, Player hunter, String text)
	{
		if (!PhantomHuntersConfig.HUNTER_LEARNING || !PhantomHuntersConfig.HUNTER_LEARNING_QUESTIONS || PhantomManager.getInstance().isPhantom(speaker))
		{
			return false;
		}
		final long key = pair(hunter, speaker);
		final Pending pending = _questions.get(key);
		if (pending == null)
		{
			return false;
		}
		final long now = System.currentTimeMillis();
		if (now >= pending.until)
		{
			_questions.remove(key);
			return false;
		}
		// Шёпот с просьбой («да, кинь реку») или длинный — не оценка: идёт в обычный чат, вопрос ждёт ответа дальше.
		final int rating = PhantomLearningModel.isPlainAnswer(text) ? PhantomLearningModel.parseRating(text) : 0;
		if (rating == 0)
		{
			return false;
		}
		_questions.remove(key);
		// Ответ забран здесь, до talk(): пометку «проигнорировал» снимаем сами, как это сделал бы обычный маршрут шёпота.
		PhantomInitiative.getInstance().onSpoke(hunter, speaker);

		final Memory memory = memory(hunter);
		PhantomLearningModel.rememberAdvice(memory, PhantomLearningModel.advise(memory.advice.get(pending.spot), pending.spot, speaker.getObjectId(), rating, now));
		hunter.getVariables().set(ADVICE_VARIABLE, PhantomLearningModel.encodeAdvice(memory.advice));
		store(hunter);
		final String understood = rating > 0 ? "рекомендует этот спот" : "не рекомендует этот спот";
		final String fallback = rating > 0 ? "понял, спасибо. попробую тут ещё" : "понял, спасибо. буду осторожнее, гляну другое место";
		PhantomChat.getInstance().hunterEvent(hunter, speaker, "LEARNING_THANKS", speaker.getName() + " ответил на твой вопрос о споте «" + pending.spot + "»: он " + understood + ". Коротко поблагодари; свой опыт будешь проверять дальше, не обещай ничего.", fallback, 30000, true);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " recorded advice of " + speaker.getName() + " about " + pending.spot + ": " + rating + ".");
		return true;
	}

	private static long adviceTtl()
	{
		return PhantomHuntersConfig.HUNTER_LEARNING_ADVICE_DAYS * 86400000L;
	}

	private static boolean isLive(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode();
	}

	private Memory memory(Player player)
	{
		return _memories.computeIfAbsent(player.getObjectId(), id ->
		{
			final Memory memory = new Memory();
			memory.actions.putAll(PhantomLearningModel.decodeLessons(player.getVariables().getString(ACTIONS_VARIABLE, ""), PhantomLearningModel.MAX_ACTIONS));
			memory.spots.putAll(PhantomLearningModel.decodeLessons(player.getVariables().getString(SPOTS_VARIABLE, ""), PhantomLearningModel.MAX_SPOTS));
			memory.advice.putAll(PhantomLearningModel.decodeAdvice(player.getVariables().getString(ADVICE_VARIABLE, "")));
			return memory;
		});
	}

	private static void storeLessons(Player player, Memory memory)
	{
		player.getVariables().set(ACTIONS_VARIABLE, PhantomLearningModel.encodeLessons(memory.actions, PhantomLearningModel.MAX_ACTIONS));
		player.getVariables().set(SPOTS_VARIABLE, PhantomLearningModel.encodeLessons(memory.spots, PhantomLearningModel.MAX_SPOTS));
		store(player);
	}

	private static void store(Player player)
	{
		ThreadPool.execute(() -> player.getVariables().storeMe());
	}

	private static long pair(Player hunter, Player player)
	{
		return ((long) hunter.getObjectId() << 32) | (player.getObjectId() & 0xffffffffL);
	}

	static void selfTest()
	{
		final List<String> failed = PhantomLearningModelTest.check();
		failed.addAll(PhantomRussianSpeechTest.check());
		LOGGER.info("PhantomLearning: SelfTest LEARNING: " + (failed.isEmpty() ? "passed, model and Russian speech checks only; chat and persistence require the client scenario." : "FAILED: " + failed));
	}

	public static PhantomLearning getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		private static final PhantomLearning INSTANCE = new PhantomLearning();
	}
}
