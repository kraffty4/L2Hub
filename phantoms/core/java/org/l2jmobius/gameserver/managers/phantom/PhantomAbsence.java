package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: старение знакомых (docs/PHANTOMS.md, раздел 4.67; docs/LIVE_SERVER.md, вторая волна, п. 4). Выключатель {@code HunterAbsences}. Охотник бросает игру на несколько дней и возвращается: «давно не заходил».
 * Перерыв считается, как настоящие сутки ({@link PhantomDayCycle}), из имени и даты: раз в четыре недели с шансом {@code HunterAbsenceChance}, на {@code HunterAbsenceDaysMin}–{@code HunterAbsenceDaysMax} дней;
 * перезапуск его не тасует. Начатое дело охотник не бросает, как и по суткам ({@code PhantomHunterManager.mayLeave}).
 * <ul>
 * <li>Уход: половина предупреждает в торговый чат в конце последней сессии, остальные пропадают молча, и летопись замечает это на третий день. Сломав дорогую заточку, охотник может бросить игру сгоряча
 * ({@code HunterAbsenceRageChance}).</li>
 * <li>Возвращение: запись в летописи (её видят и мозги), крик «давно не заходил», ответ знакомого охотника и шёпот знакомому живому, только через мозги.</li>
 * </ul>
 * В летопись идут только перерывы, начавшиеся при включённом ключе: иначе она врала бы про того, кто вчера был в сети.
 */
final class PhantomAbsence
{
	private static final Logger LOGGER = Logger.getLogger(PhantomAbsence.class.getName());

	/** Окно расписания, дней: в каждом не больше одного перерыва. */
	private static final int WINDOW = 28;
	private static final int SALT_CHANCE = 11;
	private static final int SALT_FROM = 12;
	private static final int SALT_LENGTH = 13;
	private static final int SALT_WHY = 14;
	private static final int SALT_FAREWELL = 15;
	/** Последняя сессия: перерыв начнётся не позже чем через столько, мс. */
	private static final long LAST_SESSION = 64800000;
	private static final int FAREWELL_CHANCE = 50;
	/** На какой по счёту день летопись замечает, что охотник пропал молча (0 — первый). */
	private static final int NOTICE_DAYS = 2;
	/** Вернувшийся в мир позже стольких дней после конца перерыва — новость уже не новость. */
	private static final int BACK_WINDOW = 5;
	private static final int BACK_SHOUT_CHANCE = 60;
	private static final int REPLY_CHANCE = 60;
	private static final int BACK_DELAY_MIN = 30000;
	private static final int BACK_DELAY_MAX = 120000;
	private static final int REPLY_DELAY_MIN = 15000;
	private static final int REPLY_DELAY_MAX = 60000;
	private static final long WHISPER_COOLDOWN = 86400000;
	/** Сгоряча бросают с такой заточки. */
	private static final int RAGE_LEVEL = 6;
	private static final int RAGE_DAYS_MIN = 2;
	private static final int RAGE_DAYS_MAX = 6;
	private static final int RAGE_SHOUT_DELAY = 4000;

	/** Глобальная переменная: перерыв сгоряча, «от:до:причина» (дни). */
	private static final String AWAY = "L2HubAbsenceAway_";
	/** Глобальная переменная: о перерыве с такого дня уже сказано. */
	private static final String SAID = "L2HubAbsenceSaid_";
	/** Глобальная переменная: о возвращении из перерыва с такого дня уже сказано. */
	private static final String BACK = "L2HubAbsenceBack_";
	/** Глобальная переменная: с какого дня ключ включён. */
	private static final String SINCE = "L2HubAbsenceSince";

	private static final String[][] WHY =
	{
		{ "сессия", "exams" },
		{ "работа завалила", "swamped at work" },
		{ "уезжаю к родителям", "visiting my parents" },
		{ "комп в ремонт", "my PC is going in for repairs" },
		{ "надо отдохнуть от этой игры", "need a break from this game" },
		{ "в отпуск", "going on vacation" },
		{ "интернет отключают", "my internet is getting cut off" },
		{ "дома попросили завязать", "promised my family to stop for a while" }
	};
	private static final String[] FAREWELL_LINES = { "всё, пропаду на %s: %s", "народ, меня не будет %s, %s. не теряйте", "я на %s, %s" };
	private static final String[] FAREWELL_LINES_EN = { "that's it, I'll be gone for %s: %s", "guys, I'm out for %s, %s. don't lose me", "away for %s, %s" };
	private static final String[] BACK_LINES = { "всем привет, давно не заходил%1$s, что нового?", "я снова тут. что без меня было?", "%2$d дн. не играл%1$s, ничего не изменилось?" };
	private static final String[] BACK_LINES_EN = { "hi all, long time no see, what's new?", "I'm back. what did I miss?", "haven't played for %2$d days, anything change?" };
	private static final String[] REPLY_LINES = { "%1$s, давно не заходил%2$s. где пропадал%2$s?", "о, кто вернулся. привет, %1$s", "%1$s, сколько лет. думали, бросил%2$s" };
	private static final String[] REPLY_LINES_EN = { "%1$s, long time no see. where have you been?", "oh, look who's back. hi %1$s", "%1$s, it's been a while. thought you quit" };
	private static final String[] RAGE_LINES = { "всё, с меня хватит. всем пока", "да ну эту игру. пока" };
	private static final String[] RAGE_LINES_EN = { "that's it, I'm done. bye all", "screw this game. bye" };

	/** Самопроверка: реплики без обычных пауз. */
	private static boolean _fast;

	/**
	 * Перерыв: дни от и до (не включая), причина.
	 */
	private record Break(long from, long to, int why)
	{
	}

	private PhantomAbsence()
	{
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_ABSENCES;
	}

	private static boolean en()
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE);
	}

	private static boolean female(Player player)
	{
		return player.getAppearance().isFemale();
	}

	private static long day(long now)
	{
		return PhantomDayCycle.day(now);
	}

	private static Break at(String name, long day)
	{
		final String[] rage = GlobalVariablesManager.getInstance().getString(AWAY + name, "").split(":");
		if ((rage.length == 3) && (Long.parseLong(rage[0]) <= day) && (day < Long.parseLong(rage[1])))
		{
			return new Break(Long.parseLong(rage[0]), Long.parseLong(rage[1]), Integer.parseInt(rage[2]));
		}

		final int min = PhantomHuntersConfig.HUNTER_ABSENCE_DAYS_MIN;
		final long window = Math.floorDiv(day, (long) WINDOW);
		// Перерыв прошлого окна мог перейти в это.
		for (long w = window; w >= (window - 1); w--)
		{
			if (PhantomDayCycle.mix(name, w, SALT_CHANCE, 100) >= PhantomHuntersConfig.HUNTER_ABSENCE_CHANCE)
			{
				continue;
			}
			final long from = (w * WINDOW) + PhantomDayCycle.mix(name, w, SALT_FROM, WINDOW);
			final long to = from + min + PhantomDayCycle.mix(name, w, SALT_LENGTH, (PhantomHuntersConfig.HUNTER_ABSENCE_DAYS_MAX - min) + 1);
			if ((from <= day) && (day < to))
			{
				return new Break(from, to, PhantomDayCycle.mix(name, w, SALT_WHY, WHY.length));
			}
		}
		return null;
	}

	/**
	 * @return охотник сейчас бросил игру
	 */
	static boolean away(String name, long now)
	{
		return at(name, day(now)) != null;
	}

	/**
	 * @return сколько дней ещё не будет, для окна; 0 — не на перерыве
	 */
	static long daysLeft(String name, long now)
	{
		final long day = day(now);
		final Break current = at(name, day);
		return current == null ? 0 : current.to() - day;
	}

	private static boolean fresh(Break item, long day)
	{
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		long since = variables.getLong(SINCE, -1);
		if (since < 0)
		{
			since = day;
			variables.set(SINCE, since);
		}
		return item.from() >= since;
	}

	private static boolean told(String variable, String name, Break item)
	{
		return GlobalVariablesManager.getInstance().getLong(variable + name, Long.MIN_VALUE) == item.from();
	}

	private static String span(long days, boolean en)
	{
		return days <= 4 ? (en ? "a couple of days" : "пару дней") : days <= 8 ? (en ? "a week" : "неделю") : (en ? "a couple of weeks" : "пару недель");
	}

	// ---------- Уход ----------

	/**
	 * Охотник выходит из игры ({@code PhantomHunterManager}): если это последняя сессия перед перерывом, половина предупреждает.
	 */
	static void onLeave(Player hunter, long now)
	{
		if (!enabled() || PhantomManager.getInstance().isSecret(hunter))
		{
			return;
		}

		final String name = hunter.getName();
		final long today = day(now);
		final Break current = at(name, today);
		final Break next = current != null ? current : at(name, day(now + LAST_SESSION));
		if ((next != null) && !told(SAID, name, next) && fresh(next, today) && (PhantomDayCycle.mix(name, next.from(), SALT_FAREWELL, 100) < FAREWELL_CHANCE))
		{
			farewell(hunter, next);
		}
	}

	private static void farewell(Player hunter, Break item)
	{
		final String name = hunter.getName();
		final boolean en = en();
		final String[] pool = en ? FAREWELL_LINES_EN : FAREWELL_LINES;
		final String why = WHY[item.why()][en ? 1 : 0];
		final String span = span(item.to() - item.from(), en);
		final String line = String.format(pool[Rnd.get(pool.length)], span, why);
		GlobalVariablesManager.getInstance().set(SAID + name, item.from());
		PhantomChat.getInstance().hunterShout(hunter, line);
		PhantomNews.post("gone", en ? "Hunter " + name + " will be away for " + span + ": " + why : "Охотник " + name + " пропадает на " + span + ": " + why, name, hunter);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + name + " says goodbye for " + (item.to() - item.from()) + " days: " + line);
	}

	/**
	 * Охотник на перерыве ({@code PhantomHunterManager.control}): пропал молча — на третий день это замечают.
	 */
	static void notice(String name, boolean female, long now)
	{
		final long today = day(now);
		final Break current = at(name, today);
		if ((current == null) || ((today - current.from()) < NOTICE_DAYS) || told(SAID, name, current) || !fresh(current, today))
		{
			return;
		}

		GlobalVariablesManager.getInstance().set(SAID + name, current.from());
		final long nth = (today - current.from()) + 1;
		PhantomNews.post("gone", en() ? "Hunter " + name + " has gone missing: day " + nth + " without logging in" : "Охотник " + name + " пропал" + (female ? "а" : "") + ": не заходит уже " + nth + "-й день", name, null);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + name + " went missing " + nth + " days ago, " + (current.to() - today) + " days left.");
	}

	/**
	 * Поломка заточки ({@link PhantomEnchant#announce}): с дорогой вещью охотник может бросить игру сгоряча.
	 */
	static void afterBreak(Player hunter, String item, int level)
	{
		if (!enabled() || (level < RAGE_LEVEL) || (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_ABSENCE_RAGE_CHANCE) || PhantomManager.getInstance().isSecret(hunter))
		{
			return;
		}

		final String name = hunter.getName();
		final long today = day(System.currentTimeMillis());
		final int days = Rnd.get(RAGE_DAYS_MIN, RAGE_DAYS_MAX);
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		variables.set(AWAY + name, today + ":" + (today + days) + ":" + Rnd.get(WHY.length));
		variables.set(SAID + name, today);
		final boolean en = en();
		PhantomNews.post("gone", en ? "Hunter " + name + " broke " + item + " +" + level + " and quit the game" : "Охотник " + name + " сломал" + (female(hunter) ? "а " : " ") + item + " +" + level + " и бросил" + (female(hunter) ? "а" : "") + " игру", name, hunter);
		final String[] pool = en ? RAGE_LINES_EN : RAGE_LINES;
		final String line = pool[Rnd.get(pool.length)];
		ThreadPool.schedule(() -> PhantomChat.getInstance().hunterShout(hunter, line), RAGE_SHOUT_DELAY);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + name + " rage quits for " + days + " days after " + item + " +" + level + ".");
	}

	// ---------- Возвращение ----------

	/**
	 * Охотник вошёл в мир ({@link PhantomManager}): перерыв только что кончился — он вернулся.
	 */
	static void onEnter(Player hunter)
	{
		if (!enabled() || PhantomManager.getInstance().isSecret(hunter))
		{
			return;
		}

		final String name = hunter.getName();
		final long today = day(System.currentTimeMillis());
		Break last = null;
		for (long d = today - 1; (d >= (today - BACK_WINDOW)) && (last == null); d--)
		{
			last = at(name, d);
		}
		if ((last == null) || told(BACK, name, last) || !fresh(last, today))
		{
			return;
		}

		GlobalVariablesManager.getInstance().set(BACK + name, last.from());
		final int days = (int) (today - last.from());
		PhantomNews.post("back", en() ? "Hunter " + name + " is back after " + days + " days" : "Охотник " + name + " вернул" + (female(hunter) ? "ась" : "ся") + ": не было " + days + " дн.", name, hunter);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + name + " is back after " + days + " days.");
		ThreadPool.schedule(() -> returned(hunter, days), pause(BACK_DELAY_MIN, BACK_DELAY_MAX));
	}

	private static int pause(int min, int max)
	{
		return _fast ? 2000 : Rnd.get(min, max);
	}

	private static void returned(Player hunter, int days)
	{
		if (!hunter.isOnline() || hunter.isDead())
		{
			return;
		}

		whisper(hunter, days);
		if (_fast || (Rnd.get(100) < BACK_SHOUT_CHANCE))
		{
			final boolean en = en();
			final String[] pool = en ? BACK_LINES_EN : BACK_LINES;
			final String line = String.format(pool[Rnd.get(pool.length)], female(hunter) ? "а" : "", days);
			PhantomChat.getInstance().hunterShout(hunter, line);
			LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + hunter.getName() + " shouts: " + line);
			if (_fast || (Rnd.get(100) < REPLY_CHANCE))
			{
				ThreadPool.schedule(() -> reply(hunter), pause(REPLY_DELAY_MIN, REPLY_DELAY_MAX));
			}
		}
	}

	/**
	 * Знакомый охотник отвечает вернувшемуся: друг, соклановец или тот, с кем он в списке друзей.
	 */
	private static void reply(Player back)
	{
		if (!back.isOnline())
		{
			return;
		}

		final PhantomRelations relations = PhantomRelations.getInstance();
		final List<Player> known = new ArrayList<>();
		for (Player other : World.getPlayers())
		{
			if ((other != back) && PhantomManager.getInstance().isPhantom(other) && PhantomRelations.isHunter(other) && !PhantomManager.getInstance().isSecret(other) && !other.isDead() && !relations.isEnemy(other, back) && (relations.isFriend(other, back) || other.getFriendList().contains(back.getObjectId()) || ((back.getClan() != null) && (other.getClan() == back.getClan()))))
			{
				known.add(other);
			}
		}
		if (known.isEmpty())
		{
			LOGGER.info(PhantomAbsence.class.getSimpleName() + ": nobody knows " + back.getName() + " well enough to answer.");
			return;
		}

		final Player other = known.get(Rnd.get(known.size()));
		final String[] pool = en() ? REPLY_LINES_EN : REPLY_LINES;
		final String line = String.format(pool[Rnd.get(pool.length)], back.getName(), female(back) ? "а" : "");
		PhantomChat.getInstance().hunterShout(other, line);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + other.getName() + " answers (" + known.size() + " know " + back.getName() + "): " + line);
	}

	/**
	 * Знакомому живому в игре вернувшийся пишет сам. Только мозги: дежурное «привет» хуже молчания.
	 */
	private static void whisper(Player hunter, int days)
	{
		if (!PhantomChat.brainEnabled())
		{
			return;
		}

		final PhantomRelations relations = PhantomRelations.getInstance();
		Player best = null;
		double bestScore = 0;
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) || relations.isEnemy(hunter, player) || PhantomInitiative.ignored(hunter, player.getObjectId()))
			{
				continue;
			}
			final double score = (hunter.getFriendList().contains(player.getObjectId()) ? 3 : 0) + relations.gratitude(hunter, player);
			if (score > bestScore)
			{
				best = player;
				bestScore = score;
			}
		}
		if (best == null)
		{
			return;
		}

		final String what = "тебя не было в игре " + days + " дн., ты только что вернулся. В игре " + PhantomHunterManager.describe(best) + ", вы знакомы. Напиши ему в личку одну короткую реплику своими словами и по своему характеру, как пишут знакомому после долгого перерыва: можно спросить, что нового. Вспоминай только то, что правда было. Сказать нечего — молчи";
		PhantomChat.getInstance().hunterEvent(hunter, best, "BACK", what, null, WHISPER_COOLDOWN, true);
		LOGGER.info(PhantomAbsence.class.getSimpleName() + ": " + hunter.getName() + " writes to " + best.getName() + " after " + days + " days.");
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка ABSENCE ({@code HuntersSelfTest}, только на копии базы). Без мира: сколько охотников отсутствует по дням на 8 недель вперёд и повторяемость. В мире: прощание, молчаливый уход, уход сгоряча,
	 * выход из игры по перерыву и возвращение с криком и ответом.
	 * @param names имена всех охотников
	 * @param online сейчас в мире
	 */
	static void selfTest(List<String> names, Supplier<List<Player>> online)
	{
		final String tag = "PhantomAbsence: SelfTest ABSENCE: ";
		final long now = System.currentTimeMillis();
		final long today = day(now);
		final boolean keyWas = PhantomHuntersConfig.HUNTER_ABSENCES;
		PhantomHuntersConfig.HUNTER_ABSENCES = true;

		int min = Integer.MAX_VALUE;
		int max = 0;
		long sum = 0;
		boolean same = true;
		final Set<String> breaks = new HashSet<>();
		for (long d = today; d < (today + 56); d++)
		{
			int count = 0;
			for (String name : names)
			{
				final Break item = at(name, d);
				same &= Objects.equals(item, at(name, d));
				if (item != null)
				{
					count++;
					if (breaks.add(name + ":" + item.from()) && (breaks.size() <= 3))
					{
						LOGGER.info(tag + "for example " + name + ": day +" + (item.from() - today) + ", " + (item.to() - item.from()) + " days, " + WHY[item.why()][1] + ".");
					}
				}
			}
			min = Math.min(min, count);
			max = Math.max(max, count);
			sum += count;
		}
		LOGGER.info(tag + names.size() + " hunters, 8 weeks: " + breaks.size() + " breaks, away per day min " + min + ", avg " + (sum / 56) + ", max " + max + (same ? ", repeatable." : ", NOT REPEATABLE."));

		final List<Player> world = new ArrayList<>();
		for (Player player : online.get())
		{
			if ((player.getLevel() >= 20) && !PhantomManager.getInstance().isSecret(player) && (PhantomRaid.getInstance().raidOf(player) == null))
			{
				world.add(player);
			}
		}
		if (world.size() < 3)
		{
			LOGGER.warning(tag + "no three hunters from level 20 in the world.");
			PhantomHuntersConfig.HUNTER_ABSENCES = keyWas;
			return;
		}

		_fast = true;
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		variables.set(SINCE, today - 30);
		final Player leaver = world.get(0);
		final Player missing = world.get(1);
		final Player rage = world.get(2);
		variables.set(AWAY + leaver.getName(), today + ":" + (today + 7) + ":0");
		farewell(leaver, at(leaver.getName(), today));
		variables.set(AWAY + missing.getName(), (today - 3) + ":" + (today + 3) + ":1");
		LOGGER.info(tag + leaver.getName() + " says goodbye, " + missing.getName() + " is noticed on the next control, " + rage.getName() + " breaks a weapon.");
		final int rageWas = PhantomHuntersConfig.HUNTER_ABSENCE_RAGE_CHANCE;
		PhantomHuntersConfig.HUNTER_ABSENCE_RAGE_CHANCE = 100;
		afterBreak(rage, "Bastard Sword", RAGE_LEVEL);
		PhantomHuntersConfig.HUNTER_ABSENCE_RAGE_CHANCE = rageWas;
		waitOffline(tag, leaver.getName(), online, now, 0, () ->
		{
			variables.set(AWAY + leaver.getName(), (today - 6) + ":" + today + ":0");
			LOGGER.info(tag + leaver.getName() + "'s break is over: waits for the return.");
			waitOnline(tag, leaver.getName(), online, now, keyWas, 0);
		});
	}

	private static void waitOffline(String tag, String name, Supplier<List<Player>> online, long start, int tries, Runnable then)
	{
		final boolean gone = online.get().stream().noneMatch(player -> player.getName().equals(name));
		if (gone || (tries >= 36))
		{
			LOGGER.info(tag + name + (gone ? " logged off after " : " STILL IN THE WORLD after ") + ((System.currentTimeMillis() - start) / 1000) + " s.");
			then.run();
			return;
		}
		ThreadPool.schedule(() -> waitOffline(tag, name, online, start, tries + 1, then), 5000);
	}

	private static void waitOnline(String tag, String name, Supplier<List<Player>> online, long start, boolean keyWas, int tries)
	{
		final boolean back = online.get().stream().anyMatch(player -> player.getName().equals(name));
		if (!back && (tries < 60))
		{
			ThreadPool.schedule(() -> waitOnline(tag, name, online, start, keyWas, tries + 1), 5000);
			return;
		}
		LOGGER.info(tag + name + (back ? " is back in the world after " : " DID NOT RETURN after ") + ((System.currentTimeMillis() - start) / 1000) + " s.");
		ThreadPool.schedule(() ->
		{
			for (String row : PhantomStories.chronicle(start))
			{
				LOGGER.info(tag + "chronicle " + row);
			}
			_fast = false;
			PhantomHuntersConfig.HUNTER_ABSENCES = keyWas;
			LOGGER.info(tag + "done.");
		}, 25000);
	}
}
