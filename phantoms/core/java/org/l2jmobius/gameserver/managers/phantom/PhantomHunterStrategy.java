package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * L2Hub: мозги-стратег охотников (раздел 4.3 docs/PHANTOMS.md). Охотник на споте раз в несколько минут ({@link PhantomHuntersConfig#HUNTER_STRATEGY_PER_HOUR} решений в час, со случайным разбросом)
 * спрашивает сервис мозгов ({@code "kind":"strategy"} в launcher/l2hub/brain.py), что делать дальше. Модель видит его состояние и характер и отвечает тегом из доступных вариантов и короткой мыслью от его лица.<br>
 * Решение выполняет {@link PhantomHunterManager} через свои фазы на ближайшем тике. Модель не ответила, ответила мусором или бюджет в минуту ({@link PhantomHuntersConfig#HUNTER_STRATEGY_PER_MINUTE}) исчерпан —
 * охотник живёт по правилам, как без стратега.<br>
 * Протокол v1 («Разум 2.0», шаг 3, docs/MIND.md): в запросе — цель охотника ({@code goal}) и итог прошлого решения ({@code last}: выполнено ли, а нет — почему);
 * в ответе, кроме тега, — новая цель {@code [[GOAL текст]]} и решение с аргументом: переезд на спот {@code [[SPOT_TO имя]]}, покупка и продажа вещи у NPC {@code [[BUY имя]]},
 * {@code [[SELL имя]]}, пати с тем, кто рядом, {@code [[PARTY имя]]} — у таких вариантов в запросе {@code "arg"} и {@code "choices"}. Старый ответ «[[ТЕГ]] мысль» работает как был.
 */
final class PhantomHunterStrategy
{
	/** Варианты решения — теги для модели. */
	enum Decision
	{
		/** Качаться дальше на этом споте. */
		HUNT(null),
		/** Сменить спот на мобов повыше. */
		SPOT_UP(null),
		/** Сменить спот на мобов пониже. */
		SPOT_DOWN(null),
		/** Переезд на спот по имени (аргумент — имя из L2HubHuntSpots.xml, протокол v1). */
		SPOT_TO("spot"),
		/** В город: хлам, покупки, ребаф — и обратно. */
		TOWN(null),
		/** В город, потом лавка в Гиране с ценным дропом. */
		STORE(null),
		/** В город: купить у NPC вещь по имени и надеть (протокол v1). */
		BUY("item"),
		/** В город: продать NPC вещь из сумки по имени (протокол v1). */
		SELL("item"),
		/** Поискать пати: «лфп» в торговый чат (до 20-го — в общий рядом); с именем — позвать того, кто рядом, или попроситься к нему (протокол v1). */
		PARTY("name"),
		/** Отдохнуть в городе N минут. */
		AFK(null),
		/** Собрать рейд охотников на РБ своего уровня и повести его (раздел 4.32). */
		RAID(null);

		/** Что называет аргумент ({@code "arg"} варианта в запросе); {@code null} — тег без аргумента. */
		final String arg;

		Decision(String arg)
		{
			this.arg = arg;
		}
	}

	static final int AFK_MIN = 5;
	static final int AFK_MAX = 30;
	/** Решение, которое за это время не удалось выполнить (бой, другие дела), забывается. */
	static final long CHOICE_TTL = 180000;
	/**
	 * Блок ответа brain.py: «[[ТЕГ]]», «[[AFK 15]]», «[[SPOT_TO имя спота]]», «[[BUY имя вещи]]», «[[GOAL цель]]». Блоки — в любом порядке, мысль — текст вокруг них.
	 */
	private static final Pattern BLOCK = Pattern.compile("\\[\\[\\s*([A-Z_]+)(?:[\\s:]+([^\\]]*?))?\\s*\\]\\]");
	/** Роль живого игрока по классу (у охотника — по шаблону): имена PlayerClass у всех пяти хроник — заглавные, через «_». */
	private static final Pattern HEALER_CLASS = Pattern.compile("BISHOP|ELDER|CARDINAL|SAINT|CLERIC|ORACLE");
	private static final Pattern BUFFER_CLASS = Pattern.compile("PROPHET|HIEROPHANT|WARCRYER|DOOMCRYER|OVERLORD|DOMINATOR|SWORDSINGER|SWORD_SINGER|SWORD_MUSE|BLADEDANCER|BLADE_DANCER|SPECTRAL_DANCER|INSPECTOR|JUDICATOR");
	private static final Pattern TANK_CLASS = Pattern.compile("KNIGHT|PALADIN|AVENGER|TEMPLAR");
	private static final int MAX_THOUGHT = 120;
	/** Выбор у варианта с аргументом (протокол v1): вещей на покупку, вещей на продажу, людей рядом — не больше. */
	static final int MAX_BUY_CHOICES = 12;
	static final int MAX_SELL_CHOICES = 8;
	static final int MAX_PARTY_CHOICES = 8;
	/** Цель — не длиннее; аргумент тега (имя спота, вещи, игрока) — не длиннее. */
	static final int MAX_GOAL = 60;
	private static final int MAX_ARG = 80;
	/** Цель охотника — переменная персонажа: живёт до следующей смены и переживает рестарт. «[[GOAL -]]» — снять. */
	static final String GOAL_VARIABLE = "L2HubHunterGoal";
	/** Решение не дошло до дела (бой, город по правилам, смерть) — причина для {@code last}. */
	static final String NOT_DONE = "не дошло до дела: был занят другим";

	/** Решение модели и его итог. */
	static final class Choice
	{
		final Decision decision;
		final int minutes;
		/** Аргумент тега: имя спота у SPOT_TO, вещи у BUY и SELL, игрока у PARTY (у PARTY может быть пустым); у остальных пусто. */
		final String arg;
		final String thought;
		/** Новая цель из ответа: {@code null} — не менялась, пусто — снять. */
		final String goal;
		final long at;
		/** Итог для следующего запроса ({@code last}): известен ли, выполнено ли, а нет — почему (по-русски, коротко). */
		volatile boolean done;
		volatile boolean ok;
		volatile String reason = "";
		// «Разум 2.0», шаг 4: что было с охотником от решения до следующего запроса ({@code last.result}). Срез — при решении ({@link #start}), счёт — на тиках менеджера, убийства — из события.
		int levelFrom;
		double expFrom;
		long adenaFrom;
		boolean started;
		/** Охотник выходил из игры после решения: итог неполный, {@code result} не шлётся. */
		volatile boolean away;
		long trackedAt;
		long tracked;
		long partyTime;
		int deaths;
		final AtomicInteger kills = new AtomicInteger();
		/** Где был, мс: имя спота или пусто — город, дорога, не на споте. */
		final Map<String, Long> where = new HashMap<>();

		Choice(Decision decision, int minutes, String arg, String thought, String goal, long at)
		{
			this.decision = decision;
			this.minutes = minutes;
			this.arg = arg;
			this.thought = thought;
			this.goal = goal;
			this.at = at;
		}

		/**
		 * Итог: выполнено или нет, с причиной — её увидит модель в следующем запросе.
		 */
		void result(boolean success, String why)
		{
			reason = why;
			ok = success;
			done = true;
		}

		/**
		 * Срез на момент решения: от него считается {@code result}.
		 */
		void start(Player player, long now)
		{
			levelFrom = player.getLevel();
			expFrom = levelPercent(player);
			adenaFrom = player.getAdena();
			trackedAt = now;
			started = true;
		}

		/**
		 * Тик менеджера: сколько времени прошло с прошлого тика и где охотник был.
		 * @param spot имя спота, если он на нём; пусто — город, дорога
		 */
		void track(long now, String spot, boolean party)
		{
			// Пропуск тиков (сервер подвис) не считается: не больше 10 с за раз.
			final long delta = Math.min(now - trackedAt, 10000);
			trackedAt = now;
			if (delta <= 0)
			{
				return;
			}
			tracked += delta;
			where.merge(spot, delta, Long::sum);
			if (party)
			{
				partyTime += delta;
			}
		}

		@Override
		public String toString()
		{
			return decision == Decision.AFK ? decision + " " + minutes : arg.isEmpty() ? decision.name() : decision + " " + arg;
		}
	}

	/** Что о нём знает менеджер (спот, простой, смерти, город, лавка) — для запроса. */
	static final class Facts
	{
		HuntSpot spot;
		int spotMinutes;
		double spotExpPercent;
		int idleMinutes;
		int roams;
		int deathsHour;
		int townMinutes;
		/** С прошлой лавки, минут; -1 — лавки ещё не было. */
		int storeMinutes = -1;
		int realPlayers;
		/** Самопроверка для brain.py: {@code garbage} — ответить мусором, {@code random} — случайный вариант без модели; пусто — как обычно. */
		String test = "";
		/** Цель охотника (пусто — нет) и прошлое решение с итогом ({@code null} — не было). */
		String goal = "";
		Choice last;
		/** Выбор у вариантов с аргументом (протокол v1): вариант → JSON-массив {@code choices}. Нет ключа — без выбора. */
		final Map<Decision, String> choices = new EnumMap<>(Decision.class);
		/** В ближайшем городе нечего купить лучше надетого (торговцы NPC и GM-шоп): варианта BUY нет, в запросе — {@code "buy_empty":true}. */
		boolean buyEmpty;
	}

	/** Запросы стратега за последнюю минуту — на всех охотников. */
	private static final Deque<Long> CALLS = new ArrayDeque<>();

	private PhantomHunterStrategy()
	{
	}

	/**
	 * @return через сколько мс следующее решение: в среднем 60 / HunterStrategyPerHour минут, от половины до полутора
	 */
	static long nextDelay()
	{
		final long mean = 3600000L / Math.max(1, PhantomHuntersConfig.HUNTER_STRATEGY_PER_HOUR);
		return Rnd.get(mean / 2, (mean * 3) / 2);
	}

	/**
	 * Бюджет запросов стратега в минуту на всех охотников.
	 * @return можно ли спросить сейчас (и запрос засчитан)
	 */
	static boolean budget(long now)
	{
		synchronized (CALLS)
		{
			while (!CALLS.isEmpty() && ((now - CALLS.peekFirst()) > 60000))
			{
				CALLS.pollFirst();
			}

			if (CALLS.size() >= PhantomHuntersConfig.HUNTER_STRATEGY_PER_MINUTE)
			{
				return false;
			}
			CALLS.addLast(now);
			return true;
		}
	}

	/**
	 * Ответ мозгов: тег проверяет и brain.py, но сервер верит только своему списку вариантов. Решение — первый блок с тегом из списка, цель — первый блок GOAL.
	 * @param reply «[[ТЕГ]] мысль», «[[AFK 15]] мысль», «[[SPOT_TO имя]] [[GOAL цель]] мысль»
	 * @param options что было доступно
	 * @return решение или {@code null}, если это мусор (тега из списка нет)
	 */
	static Choice parse(String reply, Collection<Decision> options, long now)
	{
		final String text = reply == null ? "" : reply.strip();
		Decision decision = null;
		String arg = "";
		String goal = null;
		final Matcher matcher = BLOCK.matcher(text);
		while (matcher.find())
		{
			final String value = matcher.group(2) == null ? "" : matcher.group(2).replaceAll("\\s+", " ").strip();
			if ("GOAL".equals(matcher.group(1)))
			{
				if ((goal == null) && !value.isEmpty())
				{
					goal = "-".equals(value) ? "" : cut(value, MAX_GOAL);
				}
				continue;
			}
			final Decision tag = decision(matcher.group(1));
			if ((decision == null) && (tag != null) && options.contains(tag))
			{
				decision = tag;
				arg = cut(value, MAX_ARG);
			}
		}
		if (decision == null)
		{
			return null;
		}

		final int minutes = decision == Decision.AFK ? Math.max(AFK_MIN, Math.min(AFK_MAX, number(arg, 15))) : 0;
		final String thought = BLOCK.matcher(text).replaceAll(" ").replaceAll("\\s+", " ").strip();
		return new Choice(decision, minutes, decision.arg != null ? arg : "", cut(thought, MAX_THOUGHT), goal, now);
	}

	private static Decision decision(String tag)
	{
		try
		{
			return Decision.valueOf(tag);
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
	}

	/**
	 * @return число в начале строки («15», «15 мин») или {@code fallback}
	 */
	private static int number(String text, int fallback)
	{
		final Matcher matcher = Pattern.compile("^(\\d{1,3})").matcher(text);
		return matcher.find() ? Integer.parseInt(matcher.group(1)) : fallback;
	}

	private static String cut(String text, int max)
	{
		return text.length() > max ? text.substring(0, max).strip() : text;
	}

	// ---------- Запрос ----------

	/**
	 * Тело POST /chat для brain.py: {@code "kind":"strategy"}, состояние охотника, доступные варианты (с пометкой, куда ведут) и диапазон AFK. Характер brain.py берёт сам из L2HubPhantomPersonas.json.
	 * Протокол v1: цель ({@code goal}), итог прошлого решения ({@code last}: tag, arg, ok, reason, result — что было с охотником с тех пор, minutes — сколько минут назад решил), у варианта с аргументом — {@code "arg"}
	 * ({@code spot}, {@code item}, {@code name}) и, если есть, {@code "choices"} ({@link Facts#choices}). У PARTY аргумент — только при выборе: без него это крик «лфп», как раньше.
	 * @param options вариант → пометка для модели (куда сменить спот, что продавать), может быть пустой
	 */
	static String request(Player player, PhantomTemplate template, Facts facts, Map<Decision, String> options)
	{
		final StringBuilder sb = new StringBuilder(2048);
		sb.append('{');
		FakePlayerBrain.field(sb, "kind", "strategy").append(',');
		FakePlayerBrain.field(sb, "fpc", player.getName()).append(',');
		sb.append("\"npcId\":").append(player.getObjectId()).append(',');
		FakePlayerBrain.field(sb, "test", facts.test).append(',');
		// Протокол v1: цель и итог прошлого решения — модель видит, что вышло, и почему сервер отказал.
		if (!facts.goal.isEmpty())
		{
			FakePlayerBrain.field(sb, "goal", facts.goal).append(',');
		}
		final Choice last = facts.last;
		if (last != null)
		{
			sb.append("\"last\":{");
			FakePlayerBrain.field(sb, "tag", last.decision.name()).append(',');
			if (!last.arg.isEmpty())
			{
				FakePlayerBrain.field(sb, "arg", last.arg).append(',');
			}
			final boolean ok = last.done && last.ok;
			sb.append("\"ok\":").append(ok).append(',');
			// Удача с причиной — сделал не всё (BUY списком: купил часть, причина — про первую некупленную).
			if (!ok || !last.reason.isEmpty())
			{
				FakePlayerBrain.field(sb, "reason", last.done ? last.reason : NOT_DONE).append(',');
			}
			// «Разум 2.0», шаг 4: что вышло из решения — мозги копят опыт по местам. Выходил из игры — итог неполный, его нет.
			if (last.started && !last.away)
			{
				result(sb, player, last);
			}
			// Когда решил, unix-секунды: мозги по нему узнают повтор того же last (запрос после ошибки) и обновляют ту же запись журнала.
			sb.append("\"at\":").append(last.at / 1000).append(',');
			sb.append("\"minutes\":").append((System.currentTimeMillis() - last.at) / 60000).append("},");
		}
		// «Разум 2.0»: охотник рядом с живым игроком (PhantomFocus) — мозги думают с размышлением.
		final String focus = PhantomFocus.getInstance().of(player);
		if (focus != null)
		{
			FakePlayerBrain.field(sb, "focus", focus).append(',');
		}
		sb.append("\"hunter\":{");
		sb.append("\"level\":").append(player.getLevel()).append(',');
		FakePlayerBrain.field(sb, "class", player.getPlayerClass().name()).append(',');
		FakePlayerBrain.field(sb, "race", player.getRace().name()).append(',');
		FakePlayerBrain.field(sb, "role", template.getRole().name()).append(',');
		sb.append("\"mage\":").append(player.isMageClass()).append(',');
		sb.append("\"adena\":").append(player.getAdena()).append(',');
		FakePlayerBrain.field(sb, "money", PhantomHunterManager.money(player.getAdena())).append(',');
		sb.append("\"expPct\":").append(String.format(Locale.ROOT, "%.1f", levelPercent(player))).append(',');
		sb.append("\"hpPct\":").append((int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp()))).append(',');
		sb.append("\"mpPct\":").append((int) ((player.getCurrentMp() * 100) / Math.max(1, player.getMaxMp()))).append(',');
		FakePlayerBrain.field(sb, "gear", PhantomHunterOutfit.gearReport(player, template)).append(',');
		FakePlayerBrain.field(sb, "supplies", PhantomHunterOutfit.suppliesReport(player)).append(',');
		FakePlayerBrain.field(sb, "drop", drop(player)).append(',');
		sb.append("\"inventoryPct\":").append((player.getInventory().getSize() * 100) / Math.max(1, player.getInventoryLimit())).append(',');
		FakePlayerBrain.field(sb, "spot", facts.spot != null ? facts.spot.name : "").append(',');
		sb.append("\"spotMin\":").append(facts.spot != null ? facts.spot.min : 0).append(',');
		sb.append("\"spotMax\":").append(facts.spot != null ? facts.spot.max : 0).append(',');
		sb.append("\"spotMinutes\":").append(facts.spotMinutes).append(',');
		sb.append("\"spotExpPct\":").append(String.format(Locale.ROOT, "%.1f", facts.spotExpPercent)).append(',');
		sb.append("\"idleMinutes\":").append(facts.idleMinutes).append(',');
		sb.append("\"roams\":").append(facts.roams).append(',');
		sb.append("\"deathsHour\":").append(facts.deathsHour).append(',');
		sb.append("\"townMinutes\":").append(facts.townMinutes).append(',');
		sb.append("\"storeMinutes\":").append(facts.storeMinutes).append(',');
		FakePlayerBrain.field(sb, "town", MapRegionData.getInstance().getClosestTownName(player)).append(',');
		if (facts.buyEmpty)
		{
			sb.append("\"buy_empty\":true,");
		}
		// Отношения (патч 0032): враги и друзья с причинами, в игре ли сейчас.
		sb.append("\"relations\":");
		PhantomRelations.getInstance().top(sb, player, 5);
		sb.append("},\"options\":[");
		boolean first = true;
		for (Map.Entry<Decision, String> option : options.entrySet())
		{
			sb.append(first ? "" : ",").append('{');
			first = false;
			FakePlayerBrain.field(sb, "tag", option.getKey().name()).append(',');
			final String choices = facts.choices.get(option.getKey());
			if ((option.getKey().arg != null) && ((choices != null) || (option.getKey() == Decision.SPOT_TO)))
			{
				FakePlayerBrain.field(sb, "arg", option.getKey().arg).append(',');
			}
			FakePlayerBrain.field(sb, "note", option.getValue());
			if (choices != null)
			{
				sb.append(",\"choices\":").append(choices);
			}
			sb.append('}');
		}
		sb.append("],\"afk\":[").append(AFK_MIN).append(',').append(AFK_MAX).append("],");
		sb.append("\"realPlayers\":").append(facts.realPlayers).append(',');
		final int time = GameTimeTaskManager.getInstance().getGameTime();
		FakePlayerBrain.field(sb, "gameTime", String.format("%02d:%02d", (time / 60) % 24, time % 60)).append(',');
		sb.append("\"night\":").append(GameTimeTaskManager.getInstance().isNight());
		sb.append('}');
		return sb.toString();
	}

	/**
	 * {@code "result":{…},} — что было с охотником от решения до запроса: минуты, опыт в процентах уровня (+100 за уровень, потеря при смерти — минус), уровень, смерти,
	 * чистая разница адены, убитые мобы (им и его пати), спот, где пробыл дольше всего (пусто — город или дорога), минуты в пати.
	 */
	private static void result(StringBuilder sb, Player player, Choice last)
	{
		String spot = "";
		long longest = -1;
		for (Map.Entry<String, Long> entry : last.where.entrySet())
		{
			if (entry.getValue() > longest)
			{
				spot = entry.getKey();
				longest = entry.getValue();
			}
		}
		final double exp = ((player.getLevel() - last.levelFrom) * 100.0) + levelPercent(player) - last.expFrom;
		sb.append("\"result\":{\"minutes\":").append(last.tracked / 60000).append(',');
		sb.append("\"exp\":").append(String.format(Locale.ROOT, "%.1f", exp)).append(',');
		sb.append("\"level\":").append(player.getLevel()).append(',');
		sb.append("\"deaths\":").append(last.deaths).append(',');
		sb.append("\"adena\":").append(player.getAdena() - last.adenaFrom).append(',');
		sb.append("\"kills\":").append(last.kills.get()).append(',');
		FakePlayerBrain.field(sb, "spot", spot).append(',');
		sb.append("\"party\":").append(last.partyTime / 60000).append("},");
	}

	/**
	 * @return сколько процентов уровня уже набрано
	 */
	static double levelPercent(Player player)
	{
		final long base = ExperienceData.getInstance().getExpForLevel(player.getLevel());
		final long next = ExperienceData.getInstance().getExpForLevel(player.getLevel() + 1);
		return next > base ? ((player.getExp() - base) * 100.0) / (next - base) : 100;
	}

	/**
	 * @param since опыт на момент прихода на спот
	 * @return сколько процентов текущего уровня набрано с тех пор
	 */
	static double expGainPercent(Player player, long since)
	{
		final long base = ExperienceData.getInstance().getExpForLevel(player.getLevel());
		final long next = ExperienceData.getInstance().getExpForLevel(player.getLevel() + 1);
		return next > base ? (Math.max(0, player.getExp() - since) * 100.0) / (next - base) : 0;
	}

	/**
	 * @return ценный дроп для лавки — «Elven Long Sword, Mithril Gloves и ещё 2 — всего ~180к по цене магазина»; пусто — нечего продавать
	 */
	static String drop(Player player)
	{
		final List<Item> goods = PhantomHunterOutfit.storeGoods(player);
		if (goods.isEmpty())
		{
			return "";
		}

		long total = 0;
		final StringBuilder names = new StringBuilder();
		for (int i = 0; i < goods.size(); i++)
		{
			final Item item = goods.get(i);
			total += item.getTemplate().getReferencePrice() * item.getCount();
			if (i < 3)
			{
				names.append(i > 0 ? ", " : "").append(item.getName()).append(item.getCount() > 1 ? " x" + item.getCount() : "");
			}
		}
		return names + (goods.size() > 3 ? " и ещё " + (goods.size() - 3) : "") + " — всего ~" + PhantomHunterManager.money(total) + " по цене магазина";
	}

	// ---------- Выбор у вариантов с аргументом (протокол v1) ----------

	/**
	 * Выбор для BUY: только то, что по карману, не больше {@value #MAX_BUY_CHOICES}.
	 * @param offers вещи у NPC лучше надетого ({@link PhantomHunterOutfit#buyOffers})
	 * @return {@code [{"name":…,"price":…,"part":…,"grade":…},…]} или {@code null} — по карману ничего
	 */
	static String buyChoices(List<PhantomHunterOutfit.Offer> offers)
	{
		final StringBuilder sb = new StringBuilder("[");
		int count = 0;
		for (PhantomHunterOutfit.Offer offer : offers)
		{
			if (!offer.afford() || (count >= MAX_BUY_CHOICES))
			{
				continue;
			}
			sb.append(count++ > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", offer.name()).append(',');
			sb.append("\"price\":").append(offer.price()).append(',');
			FakePlayerBrain.field(sb, "part", offer.part()).append(',');
			FakePlayerBrain.field(sb, "grade", offer.grade()).append('}');
		}
		return count == 0 ? null : sb.append(']').toString();
	}

	/**
	 * Выбор для SELL: не больше {@value #MAX_SELL_CHOICES}, дорогое первым.
	 * @param offers что из сумки купит NPC ({@link PhantomHunterOutfit#sellOffers}); цена — сколько он даст за всё
	 * @return {@code [{"name":…,"count":…,"price":…},…]} или {@code null} — продать нечего
	 */
	static String sellChoices(List<PhantomHunterOutfit.Offer> offers)
	{
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(MAX_SELL_CHOICES, offers.size()); i++)
		{
			final PhantomHunterOutfit.Offer offer = offers.get(i);
			sb.append(i > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", offer.name()).append(',');
			sb.append("\"count\":").append(offer.count()).append(',');
			sb.append("\"price\":").append(offer.price()).append('}');
		}
		return offers.isEmpty() ? null : sb.append(']').toString();
	}

	/**
	 * Выбор для PARTY с именем: кто рядом и подходит ({@link PhantomHunterManager#partyCandidates}), не больше {@value #MAX_PARTY_CHOICES}.
	 * @return {@code [{"name":…,"class":…,"level":…,"role":…,"real":…,"party":…},…]} (party — 0, если свободен, иначе сколько в его пати) или {@code null} — никого
	 */
	static String partyChoices(List<Player> others)
	{
		final StringBuilder sb = new StringBuilder("[");
		for (int i = 0; i < Math.min(MAX_PARTY_CHOICES, others.size()); i++)
		{
			final Player other = others.get(i);
			sb.append(i > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", other.getName()).append(',');
			FakePlayerBrain.field(sb, "class", other.getPlayerClass().name().replace('_', ' ').toLowerCase(Locale.ROOT)).append(',');
			sb.append("\"level\":").append(other.getLevel()).append(',');
			FakePlayerBrain.field(sb, "role", role(other)).append(',');
			sb.append("\"real\":").append(!PhantomManager.getInstance().isPhantom(other)).append(',');
			sb.append("\"party\":").append(other.isInParty() ? other.getParty().getMemberCount() : 0).append('}');
		}
		return others.isEmpty() ? null : sb.append(']').toString();
	}

	/**
	 * @return роль для выбора пати: у охотника — по шаблону, у живого — по классу; урон — лучник (лук или арбалет в руках), нюкер (маг) или мили
	 */
	static String role(Player other)
	{
		final PhantomTemplate template = PhantomManager.getInstance().isPhantom(other) ? PhantomData.getInstance().getTemplate(other.getName()) : null;
		final String name = other.getPlayerClass().name();
		final PhantomTemplate.Role role = template != null ? template.getRole() : HEALER_CLASS.matcher(name).find() ? PhantomTemplate.Role.HEALER : BUFFER_CLASS.matcher(name).find() ? PhantomTemplate.Role.BUFFER : TANK_CLASS.matcher(name).find() ? PhantomTemplate.Role.TANK : PhantomTemplate.Role.DAMAGE;
		switch (role)
		{
			case HEALER:
			{
				return "хил";
			}
			case BUFFER:
			{
				return "бафер";
			}
			case TANK:
			{
				return "танк";
			}
			default:
			{
				return PhantomHunterOutfit.ammoId(other) > 0 ? "лучник" : other.isMageClass() ? "нюкер" : "мили";
			}
		}
	}
}
