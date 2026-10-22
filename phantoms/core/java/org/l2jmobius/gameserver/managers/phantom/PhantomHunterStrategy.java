package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
 * охотник живёт по правилам, как без стратега.
 */
final class PhantomHunterStrategy
{
	/** Варианты решения — теги для модели. */
	enum Decision
	{
		/** Качаться дальше на этом споте. */
		HUNT,
		/** Сменить спот на мобов повыше. */
		SPOT_UP,
		/** Сменить спот на мобов пониже. */
		SPOT_DOWN,
		/** В город: хлам, покупки, ребаф — и обратно. */
		TOWN,
		/** В город, потом лавка в Гиране с ценным дропом. */
		STORE,
		/** Поискать пати: «лфп» в торговый чат (до 20-го — в общий рядом). */
		PARTY,
		/** Отдохнуть в городе N минут. */
		AFK,
		/** Собрать рейд охотников на РБ своего уровня и повести его (раздел 4.32). */
		RAID
	}

	static final int AFK_MIN = 5;
	static final int AFK_MAX = 30;
	/** Решение, которое за это время не удалось выполнить (бой, другие дела), забывается. */
	static final long CHOICE_TTL = 180000;
	/** Ответ brain.py: «[[ТЕГ]] мысль» или «[[AFK 15]] мысль». */
	private static final Pattern REPLY = Pattern.compile("^\\[\\[([A-Z_]+)(?: (\\d{1,3}))?\\]\\]\\s*(.*)$", Pattern.DOTALL);
	private static final int MAX_THOUGHT = 120;

	/** Решение модели. */
	static final class Choice
	{
		final Decision decision;
		final int minutes;
		final String thought;
		final long at;

		Choice(Decision decision, int minutes, String thought, long at)
		{
			this.decision = decision;
			this.minutes = minutes;
			this.thought = thought;
			this.at = at;
		}

		@Override
		public String toString()
		{
			return decision == Decision.AFK ? decision + " " + minutes : decision.name();
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
	 * Ответ мозгов: тег проверяет и brain.py, но сервер верит только своему списку вариантов.
	 * @param reply «[[ТЕГ]] мысль» или «[[AFK 15]] мысль»
	 * @param options что было доступно
	 * @return решение или {@code null}, если это мусор
	 */
	static Choice parse(String reply, Collection<Decision> options, long now)
	{
		final Matcher matcher = REPLY.matcher(reply == null ? "" : reply.strip());
		if (!matcher.matches())
		{
			return null;
		}

		final Decision decision;
		try
		{
			decision = Decision.valueOf(matcher.group(1));
		}
		catch (IllegalArgumentException e)
		{
			return null;
		}
		if (!options.contains(decision))
		{
			return null;
		}

		final int minutes = decision == Decision.AFK ? Math.max(AFK_MIN, Math.min(AFK_MAX, matcher.group(2) != null ? Integer.parseInt(matcher.group(2)) : 15)) : 0;
		final String thought = matcher.group(3).replaceAll("\\s+", " ").strip();
		return new Choice(decision, minutes, thought.length() > MAX_THOUGHT ? thought.substring(0, MAX_THOUGHT) : thought, now);
	}

	// ---------- Запрос ----------

	/**
	 * Тело POST /chat для brain.py: {@code "kind":"strategy"}, состояние охотника, доступные варианты (с пометкой, куда ведут) и диапазон AFK. Характер brain.py берёт сам из L2HubPhantomPersonas.json.
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
			FakePlayerBrain.field(sb, "note", option.getValue());
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
}
