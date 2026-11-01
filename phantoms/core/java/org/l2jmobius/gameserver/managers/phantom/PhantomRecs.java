package org.l2jmobius.gameserver.managers.phantom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;

/**
 * L2Hub: реки от охотников по просьбе живого игрока (docs/PHANTOMS.md, раздел 4.95).<br>
 * Понятную короткую просьбу шёпотом или в общем чате («кинь реку», «рекни», «кинешь реку?») решает сервер, мозги только отвечают в характере по пометке, без мозгов — заготовка.
 * Длинную фразу разбирают мозги: тег {@code [[REC]]} из их ответа проверяется теми же правилами. Правила — как у живого в {@code RequestVoteNew}, плюс свои отказы: далеко, враг, уже кидал этому игроку за сутки. Отказа «просто так» нет.<br>
 * Память — переменная охотника {@value #VARIABLE}: пары «objectId:секунды Unix» за последние сутки, поэтому переживает рестарт. От неё же считается запас ({@link PhantomCompat#recomAllowance}):
 * фантом не проходит {@code EnterWorld}, на High Five реки из базы не грузятся вовсе, а на Classic и Essence суточный сброс обнуляет их, и {@code RecoGiveTask} их не вернёт.
 */
public final class PhantomRecs
{
	private static final Logger LOGGER = Logger.getLogger(PhantomRecs.class.getName());

	static final String VARIABLE = "L2HubRecsGiven";
	/** Память о паре и запас — на столько секунд. */
	private static final long DAY = 86400;
	/** Дальше этого живой цель уже не возьмёт: отказ «подойди». */
	private static final int RANGE = 1500;
	/** Длиннее этого (слов без имён) — уже разговор, его разбирают мозги. */
	private static final int MAX_WORDS = 6;

	/** Глаголы и вежливость: просьба, только если рядом {@link #NOUNS}. */
	private static final Set<String> ASK = words("кинь киньте кинешь кинете кинуть кидай кидани закинь закинешь подкинь подкинешь брось бросишь дай дайте дашь дадите дать поставь поставишь отсыпь влепи сделай можно надо нужна нужно нужны хочу "
		+ "kin kinj kinesh kinte kinut kiday kidai zakin podkin bros day dai daj dash postav postavish mozhno nado nuzhna hochu give gimme drop throw send need want get "
		+ "плиз плз пж пжл пожалуйста пожалуй пожалста please pls plz plis pliz pozhaluysta мне mne me");
	/** Глаголы, которые просьба сами по себе. */
	private static final Set<String> DIRECT = words("рекни рекните рекнешь рекнете рекнуть рекай порекай порекомендуй порекомендуйте порекомендуешь порекомендуете рекомендуй "
		+ "rekni recni reknesh rekay rekai porekay porekai porekomenduy porekomendui rekomenduy rekomendui recommend recomend");
	private static final Set<String> NOUNS = words("реку реки рек реков рекомендация рекомендацию рекомендации рекомендашку рекомендаху reku reki rek rekov rec recs reco recom recomm rekomendaciyu rekomendatsiyu recommendation recommendations");
	/** Всё, что ещё может стоять в короткой просьбе. Любое другое слово («в реку», «до реки», «спот», «ваське») отдаёт фразу мозгам. */
	private static final Set<String> FILLERS = words("а ну ка да вот эй слушай бро брат братан друг дружище чел бы ты вы я меня мою еще одну разок хоть сюда тоже привет хай будь добр можешь сможешь можете "
		+ "a an ka nu da hey hi yo bro brat drug ti ty ya menya esche eshe odnu razok mozhesh smozhesh one some you u i can could will would pretty");
	/** Игрок вообще заговорил о реках: только тогда мозгам предлагается тег, и только тогда сервер его выполняет. */
	private static final Pattern MENTION = Pattern.compile("(?:^| )(?:рек|реку|реки|реков|рекай|порекай|рекн\\S*|порекоменд\\S*|рекоменд\\S*|reku|reki|rek|rekov|rec|recs|reco|recom\\S*|recn\\S*|rekn\\S*|rekay|rekai|porek\\S*|rekomend\\S*)(?= |$)");

	public enum Result
	{
		GIVEN("сделано: ты кинул ему реку", "кинул, держи"),
		ENEMY("нельзя: он твой враг, реку ты ему не дашь", "тебе? после всего? нет"),
		ALREADY("нельзя: ты уже кидал ему реку за эти сутки", "так я ж тебе уже кидал, завтра приходи"),
		LOW_LEVEL("нельзя: реки кидают с 10-го уровня, а ты ещё не дорос", "мне рано, реки с 10 лвла"),
		OUT("нельзя: у тебя кончились реки на сегодня", "реки кончились, сорян"),
		FULL("нельзя: у него уже 255 рек, больше игра не даёт", "у тебя и так 255, куда больше"),
		FAR("нельзя: он далеко, ты его не видишь, пусть подойдёт поближе", "подойди, отсюда не достану");

		/** Пометка для мозгов, как у приказов. */
		final String note;
		/** Реплика без мозгов. */
		final String fallback;

		Result(String note, String fallback)
		{
			this.note = note;
			this.fallback = fallback;
		}
	}

	/** Охотники, которые в самопроверке играют живых. */
	private final Set<Integer> _testAskers = ConcurrentHashMap.newKeySet();

	private PhantomRecs()
	{
	}

	private static Set<String> words(String list)
	{
		return Set.copyOf(List.of(list.split(" ")));
	}

	// ---------- Разбор ----------

	/**
	 * @param text что сказал игрок
	 * @return короткая понятная просьба кинуть реку: решает сервер; вопрос тоже считается
	 */
	static boolean isAsk(String text)
	{
		return askWords(PhantomOrders.withoutNames(PhantomOrders.normalize(text)));
	}

	/**
	 * @param clean нормализованная фраза без имён
	 * @return просьба ли это: только слова просьбы, есть прямой глагол или существительное вместе с глаголом, без отрицания
	 */
	static boolean askWords(String clean)
	{
		if (clean.isEmpty())
		{
			return false;
		}

		final String[] words = clean.split(" ");
		if (words.length > MAX_WORDS)
		{
			return false;
		}

		boolean noun = false;
		boolean ask = false;
		boolean direct = false;
		for (String word : words)
		{
			if (PhantomOrders.NEGATIONS.contains(word))
			{
				return false;
			}

			final boolean isNoun = NOUNS.contains(word);
			final boolean isAsk = ASK.contains(word);
			final boolean isDirect = DIRECT.contains(word);
			if (!isNoun && !isAsk && !isDirect && !FILLERS.contains(word))
			{
				return false;
			}
			noun |= isNoun;
			ask |= isAsk;
			direct |= isDirect;
		}
		return direct || (noun && ask);
	}

	/**
	 * @param text что сказал игрок
	 * @return в реплике есть слово о реках
	 */
	static boolean mentions(String text)
	{
		return (text != null) && MENTION.matcher(PhantomOrders.normalize(text)).find();
	}

	// ---------- Просьба ----------

	/**
	 * @return ключ включён, просит живой игрок, а не другой фантом, и отвечает охотник
	 */
	private boolean enabled(Player speaker, Player hunter, PhantomTemplate template)
	{
		return PhantomHuntersConfig.HUNTER_RECOMMENDATIONS && (speaker != hunter) && template.isHunter() && !template.isReaper() && (!PhantomManager.getInstance().isPhantom(speaker) || _testAskers.contains(speaker.getObjectId()));
	}

	/**
	 * @param speaker кто говорит
	 * @param hunter кому
	 * @param template кто это
	 * @param text что сказал
	 * @return это просьба, которую решает сервер ({@link #ask})
	 */
	boolean asks(Player speaker, Player hunter, PhantomTemplate template, String text)
	{
		return enabled(speaker, hunter, template) && isAsk(text);
	}

	/**
	 * Проверить условия и кинуть реку.
	 * @param hunter охотник
	 * @param target кому
	 * @return что вышло
	 */
	synchronized Result ask(Player hunter, Player target)
	{
		final Result refusal = refusal(hunter, target);
		final Result result = refusal != null ? refusal : give(hunter, target);
		LOGGER.info(getClass().getSimpleName() + ": " + target.getName() + " asks " + hunter.getName() + " for a recommendation: " + result + ", left " + hunter.getRecomLeft() + ", target has " + target.getRecomHave() + ".");
		return result;
	}

	private Result give(Player hunter, Player target)
	{
		PhantomCompat.giveRecom(hunter, target);
		final Map<Integer, Long> given = given(hunter);
		given.put(target.getObjectId(), System.currentTimeMillis() / 1000);
		final StringBuilder value = new StringBuilder();
		for (Map.Entry<Integer, Long> entry : given.entrySet())
		{
			value.append(value.length() > 0 ? "," : "").append(entry.getKey()).append(':').append(entry.getValue());
		}
		hunter.getVariables().set(VARIABLE, value.toString());
		ThreadPool.execute(() -> hunter.getVariables().storeMe());
		return Result.GIVEN;
	}

	/**
	 * @return почему нельзя; {@code null} — можно
	 */
	private Result refusal(Player hunter, Player target)
	{
		if (PhantomRelations.getInstance().isEnemy(hunter, target))
		{
			return Result.ENEMY;
		}

		final Map<Integer, Long> given = given(hunter);
		if (given.containsKey(target.getObjectId()) || !PhantomCompat.recomFree(hunter, target))
		{
			return Result.ALREADY;
		}

		final int allowance = PhantomCompat.recomAllowance(hunter);
		if (allowance <= 0)
		{
			return Result.LOW_LEVEL;
		}

		PhantomCompat.recomBudget(hunter, allowance - given.size());
		if ((given.size() >= allowance) || (hunter.getRecomLeft() <= 0))
		{
			return Result.OUT;
		}

		if (target.getRecomHave() >= 255)
		{
			return Result.FULL;
		}
		return (hunter.getInstanceId() != target.getInstanceId()) || (hunter.calculateDistance2D(target) > RANGE) ? Result.FAR : null;
	}

	/**
	 * @return кому кинул за последние сутки: objectId → секунды Unix; всё старше уже забыто
	 */
	private static Map<Integer, Long> given(Player hunter)
	{
		final Map<Integer, Long> result = new LinkedHashMap<>();
		final long since = (System.currentTimeMillis() / 1000) - DAY;
		final String stored = hunter.getVariables().getString(VARIABLE, "");
		if (stored.isEmpty())
		{
			return result;
		}

		for (String entry : stored.split(","))
		{
			final int colon = entry.indexOf(':');
			try
			{
				final long when = Long.parseLong(entry.substring(colon + 1));
				if ((colon > 0) && (when > since))
				{
					result.put(Integer.parseInt(entry.substring(0, colon)), when);
				}
			}
			catch (NumberFormatException e)
			{
				LOGGER.warning(PhantomRecs.class.getSimpleName() + ": " + hunter.getName() + " has a broken record: " + entry);
			}
		}
		return result;
	}

	// ---------- Мозги ----------

	/**
	 * Тег {@code [[REC]]} из ответа мозгов на длинную фразу: сервер проверяет те же правила и кидает сам.
	 * @param hunter охотник
	 * @param speaker кто говорил
	 * @param template кто это
	 * @param text что он сказал
	 * @return реплика отказа: мозги обещали, а правила не дали, и их слова заменяются ею; {@code null} — говорят свои слова
	 */
	synchronized String onTag(Player hunter, Player speaker, PhantomTemplate template, String text)
	{
		if (!enabled(speaker, hunter, template) || !mentions(text))
		{
			return null;
		}

		final Result result = ask(hunter, speaker);
		return result == Result.GIVEN ? null : result.fallback;
	}

	/**
	 * Для мозгов — в блок охотника: {@code "rec":{"can":true},} или {@code "rec":{"can":false,"why":"…"},}. Игрок не говорит о реках — ничего: тег не предлагается и подсказка не тратится.
	 * @param sb тело запроса
	 * @param hunter охотник
	 * @param player с кем говорит
	 * @param template кто это
	 * @param message что сказал игрок
	 */
	synchronized void json(StringBuilder sb, Player hunter, Player player, PhantomTemplate template, String message)
	{
		if (!enabled(player, hunter, template) || !mentions(message))
		{
			return;
		}

		final Result refusal = refusal(hunter, player);
		sb.append("\"rec\":{\"can\":").append(refusal == null);
		if (refusal != null)
		{
			sb.append(',');
			FakePlayerBrain.field(sb, "why", refusal.note);
		}
		sb.append("},");
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка {@code HuntersSelfTest = RECS} (раздел 4.95, только на копии базы): живых играют охотники. Просьба работает, у цели {@code rec_have} вырос ровно на 1 и лёг в базу; вторая за сутки — отказ;
	 * память о паре в базе; враг, далеко, кончились, 255 — отказ; ключ выключен — молчит; другой фантом не просит; тег только про реки. Ключ, память и {@code rec_have} возвращаются; обида, реки на Interlude и Epilogue — нет.
	 * @param online охотники в мире
	 */
	void selfTest(List<Player> online)
	{
		ThreadPool.execute(() -> runSelfTest(online));
	}

	private void runSelfTest(List<Player> online)
	{
		final String tag = "SelfTest RECS: ";
		final List<String> failed = new ArrayList<>(checkPhrases());
		final List<Player> free = new ArrayList<>();
		for (Player player : online)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
			if ((template != null) && template.isHunter() && !template.isReaper() && !player.isDead() && !player.isInParty() && (player.getInstanceId() == 0) && !player.isInOlympiadMode())
			{
				free.add(player);
			}
		}

		// Кидает старший: на Interlude и Epilogue реки с 10-го уровня, а запас зависит от него.
		free.sort((a, b) -> Integer.compare(b.getLevel(), a.getLevel()));
		Player giver = null;
		for (Player player : free)
		{
			final int allowance = PhantomCompat.recomAllowance(player);
			PhantomCompat.recomBudget(player, allowance - given(player).size());
			if ((allowance > 0) && (player.getRecomLeft() >= 2))
			{
				giver = player;
				break;
			}
		}

		final List<Player> askers = new ArrayList<>();
		for (Player player : free)
		{
			if ((giver != null) && (player != giver) && PhantomCompat.recomFree(giver, player) && !given(giver).containsKey(player.getObjectId()) && !PhantomRelations.getInstance().isEnemy(giver, player) && (player.getRecomHave() < 200))
			{
				askers.add(player);
			}
		}
		if (askers.size() < 4)
		{
			LOGGER.info(tag + "needs a hunter with recommendations left and four free hunters, has " + (giver == null ? "none" : giver.getName()) + " and " + askers.size() + ". Phrases: " + (failed.isEmpty() ? "passed." : "FAILED: " + failed));
			return;
		}

		final Player first = askers.get(0);
		final Player enemy = askers.get(1);
		final Player last = askers.get(2);
		final Player stranger = askers.get(3);
		final boolean wasKey = PhantomHuntersConfig.HUNTER_RECOMMENDATIONS;
		final String wasGiven = giver.getVariables().getString(VARIABLE, "");
		final Map<Player, Integer> wasHave = new LinkedHashMap<>();
		for (Player player : askers.subList(0, 4))
		{
			wasHave.put(player, player.getRecomHave());
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(giver.getName());
		LOGGER.info(tag + giver.getName() + ", level " + giver.getLevel() + ", gives, allowance " + PhantomCompat.recomAllowance(giver) + ", left " + giver.getRecomLeft() + "; asks " + first.getName() + ", has " + first.getRecomHave() + " and " + PhantomCompat.storedRecomHave(first) + " in the base.");
		try
		{
			PhantomHuntersConfig.HUNTER_RECOMMENDATIONS = true;
			expect(failed, "другой фантом просит", asks(stranger, giver, template, "кинь реку"), false);
			_testAskers.add(first.getObjectId());
			_testAskers.add(enemy.getObjectId());
			_testAskers.add(last.getObjectId());
			_testAskers.add(stranger.getObjectId());
			expect(failed, "живой просит", asks(first, giver, template, "кинь реку"), true);
			expect(failed, "живой просит по имени", asks(first, giver, template, giver.getName() + ", кинь реку"), true);
			expect(failed, "по имени, но не просьба", asks(first, giver, template, giver.getName() + ", дойди до реки"), false);

			// Ключ выключен: молчит и просьба, и тег, и подсказка мозгам.
			PhantomHuntersConfig.HUNTER_RECOMMENDATIONS = false;
			PhantomFavors.bring(first, giver);
			final StringBuilder off = new StringBuilder();
			json(off, giver, first, template, "кинь реку");
			expect(failed, "ключ выключен, просьба", asks(first, giver, template, "кинь реку"), false);
			expect(failed, "ключ выключен, тег", onTag(giver, first, template, "кинь реку"), null);
			expect(failed, "ключ выключен, подсказка", off.toString(), "");
			expect(failed, "ключ выключен, реки", first.getRecomHave(), wasHave.get(first));
			PhantomHuntersConfig.HUNTER_RECOMMENDATIONS = true;

			// Далеко — «подойди», реки не тронуты.
			place(first, giver, RANGE + 500);
			expect(failed, "далеко", ask(giver, first), Result.FAR);
			expect(failed, "далеко, реки", first.getRecomHave(), wasHave.get(first));

			// Просьба: ровно +1 и в базе.
			PhantomFavors.bring(first, giver);
			expect(failed, "просьба", ask(giver, first), Result.GIVEN);
			expect(failed, "просьба, реки", first.getRecomHave(), wasHave.get(first) + 1);
			expect(failed, "просьба, реки в базе", PhantomCompat.storedRecomHave(first), wasHave.get(first) + 1);

			// Вторая в те же сутки — отказ, и пара лежит в базе: рестарт её не забудет.
			PhantomFavors.bring(first, giver);
			expect(failed, "вторая за сутки", ask(giver, first), Result.ALREADY);
			expect(failed, "вторая за сутки, реки", first.getRecomHave(), wasHave.get(first) + 1);
			Thread.sleep(2000);
			final String stored = storedGiven(giver);
			if (!stored.contains(first.getObjectId() + ":"))
			{
				failed.add("память о паре в базе: «" + stored + "»");
			}

			// Зуб.
			for (int attempt = 0; (attempt < 5) && !PhantomRelations.getInstance().isEnemy(giver, enemy); attempt++)
			{
				PhantomRelations.getInstance().record(giver, enemy, PhantomRelations.Kind.KILLED);
			}
			PhantomFavors.bring(enemy, giver);
			expect(failed, "враг", ask(giver, enemy), Result.ENEMY);
			expect(failed, "враг, реки", enemy.getRecomHave(), wasHave.get(enemy));

			// Запас кончился, на всех хрониках одинаково: память полна.
			final String current = giver.getVariables().getString(VARIABLE, "");
			final StringBuilder full = new StringBuilder();
			for (int index = 0; index < PhantomCompat.recomAllowance(giver); index++)
			{
				full.append(index > 0 ? "," : "").append(-1 - index).append(':').append(System.currentTimeMillis() / 1000);
			}
			giver.getVariables().set(VARIABLE, full.toString());
			PhantomFavors.bring(last, giver);
			expect(failed, "кончились", ask(giver, last), Result.OUT);
			giver.getVariables().set(VARIABLE, current);

			last.setRecomHave(255);
			PhantomFavors.bring(last, giver);
			expect(failed, "у цели 255", ask(giver, last), Result.FULL);
			expect(failed, "у цели 255, реки", last.getRecomHave(), 255);
			last.setRecomHave(wasHave.get(last));

			// Тег: длинная фраза, правила те же; отказ заменяет слова мозгов; не о реках — не выполняется.
			PhantomFavors.bring(last, giver);
			final StringBuilder can = new StringBuilder();
			json(can, giver, last, template, "слушай если не сложно кинь реку а то мне на ник надо");
			if (!can.toString().equals("\"rec\":{\"can\":true},"))
			{
				failed.add("подсказка, можно: " + can);
			}
			expect(failed, "тег", onTag(giver, last, template, "слушай если не сложно кинь реку а то мне на ник надо"), null);
			expect(failed, "тег, реки", last.getRecomHave(), wasHave.get(last) + 1);
			expect(failed, "тег, реки в базе", PhantomCompat.storedRecomHave(last), wasHave.get(last) + 1);
			final StringBuilder cannot = new StringBuilder();
			json(cannot, giver, first, template, "кинь реку");
			if (!cannot.toString().contains("\"can\":false") || !cannot.toString().contains(Result.ALREADY.note))
			{
				failed.add("подсказка, нельзя: " + cannot);
			}
			expect(failed, "тег на отказ", onTag(giver, first, template, "кинь реку"), Result.ALREADY.fallback);
			PhantomFavors.bring(stranger, giver);
			final StringBuilder chat = new StringBuilder();
			json(chat, giver, stranger, template, "как дела");
			expect(failed, "тег не о реках", onTag(giver, stranger, template, "как дела"), null);
			expect(failed, "не о реках, реки", stranger.getRecomHave(), wasHave.get(stranger));
			expect(failed, "не о реках, подсказка", chat.toString(), "");
		}
		catch (Exception e)
		{
			failed.add("исключение: " + e);
			LOGGER.log(Level.WARNING, tag + "failed.", e);
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_RECOMMENDATIONS = wasKey;
			_testAskers.clear();
			giver.getVariables().set(VARIABLE, wasGiven);
			final Player storedGiver = giver;
			ThreadPool.execute(() -> storedGiver.getVariables().storeMe());
			for (Map.Entry<Player, Integer> entry : wasHave.entrySet())
			{
				entry.getKey().setRecomHave(entry.getValue());
				PhantomCompat.storeRecom(entry.getKey());
			}
		}
		LOGGER.info(tag + (failed.isEmpty() ? "passed." : "FAILED: " + failed));
	}

	/** Что считается просьбой, а что идёт мозгам: разбор без мира, поэтому прогоняется и на голой JVM. */
	static List<String> checkPhrases()
	{
		final List<String> failed = new ArrayList<>();
		for (String phrase : new String[]
		{
			"кинь реку", "Кинь реку!", "рекни", "порекомендуй", "реку дай", "rec pls", "kin reku", "кинешь реку?", "рекни плз", "дай реку", "дашь реку?", "брат, кинь реку", "киньте рек", "порекомендуй меня", "give me a rec", "can you rec me", "rec me", "можно реку?", "rekni", "реку пж", "реки дай", "кинь мне реку, пожалуйста", "можешь кинуть реку?", "сможешь рекнуть?"
		})
		{
			if (!askWords(PhantomOrders.normalize(phrase)))
			{
				failed.add("не просьба: " + phrase);
			}
		}
		for (String phrase : new String[]
		{
			"", "не кидай реку", "реку не надо", "no rec", "реку", "rec", "реки?", "кинь в реку", "переплыви реку", "дойди до реки", "у реки стоит рб", "реки тут глубокие", "дай денег", "кинь бафф", "порекомендуй спот", "кинь реку ваське", "реки на карте видел?", "рекомендую купить", "recommend me a spot", "слушай если не сложно кинь реку а то мне на ник надо"
		})
		{
			if (askWords(PhantomOrders.normalize(phrase)))
			{
				failed.add("просьба: " + phrase);
			}
		}
		for (String phrase : new String[]
		{
			"реку", "слушай если не сложно кинь реку а то мне на ник надо", "rec?", "рекни", "порекомендуй", "реки на карте видел", "kin reku", "recommend me"
		})
		{
			if (!mentions(phrase))
			{
				failed.add("не о реках: " + phrase);
			}
		}
		for (String phrase : new String[]
		{
			"", "привет", "дай денег", "рекорд", "река", "реке", "рекламируй", "record", "recent", "рес", "res"
		})
		{
			if (mentions(phrase))
			{
				failed.add("о реках: " + phrase);
			}
		}
		return failed;
	}

	private static void expect(List<String> failed, String what, Object got, Object want)
	{
		if ((got == null) ? (want != null) : !got.equals(want))
		{
			failed.add(what + ": " + got + " вместо " + want);
		}
	}

	/** Поодаль от охотника: дальше {@link #RANGE}, если отойти на столько. */
	private static void place(Player player, Player hunter, int distance)
	{
		PhantomCompat.setInstanceId(player, 0);
		player.teleToLocation(new Location(hunter.getX() + distance, hunter.getY(), hunter.getZ()), false);
	}

	private static String storedGiven(Player hunter)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement("SELECT val FROM character_variables WHERE charId=? AND var=?"))
		{
			ps.setInt(1, hunter.getObjectId());
			ps.setString(2, VARIABLE);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getString(1) : "";
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomRecs.class.getSimpleName() + ": could not read the record of " + hunter.getName() + ".", e);
			return "";
		}
	}

	public static PhantomRecs getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomRecs INSTANCE = new PhantomRecs();
	}
}
