package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;

/**
 * L2Hub: охотник заговаривает первым (патч 0025, docs/PHANTOMS.md, раздел 4.9).<br>
 * Тот же проход раз в 2 с, что и обиды ({@link PhantomHunterManager}): охотник на каче, в городе или в лавке смотрит, кто из живых игроков рядом.
 * Кто рядом второй проход подряд — подошёл, а не пролетает мимо и не в момент телепорта. Дальше по порядку:
 * <ul>
 * <li>фильтры и кулдауны без модели: игрок в бою, мёртв, невидим, телепортируется, на олимпиаде, в лавке, уже разговаривает с фантомом, есть обида; кулдауны охотника, пары и игрока, потолок в час на игрока, пара «проигнорирована»;</li>
 * <li>бронь игрока за этим охотником — двое разом не здороваются;</li>
 * <li>мозги отвечают из памяти, знакомы ли и давно ли говорили ({@code "kind":"acquaintance"}, без модели);</li>
 * <li>бросок здесь: шанс для знакомого или незнакомого × общительность охотника ({@link PhantomTemplate#getSocial()}) × его настроение ({@link PhantomMood}, патч 0026);</li>
 * <li>выпало — реплика модели вслух ({@link PhantomChat#initiative}); модель промолчала или недоступна — тишина.</li>
 * </ul>
 * Игрок не ответил за {@link PhantomHuntersConfig#HUNTER_INITIATIVE_ANSWER_WAIT} с — пара «проигнорирована»: этот охотник больше не заговаривает с ним первым, пока игрок сам ему не напишет.
 * Время последней инициативы пары и пометка игнора хранятся в переменной персонажа охотника {@value #SOCIAL_VARIABLE} и переживают рестарт; остальные кулдауны — в памяти.
 */
public class PhantomInitiative
{
	private static final Logger LOGGER = Logger.getLogger(PhantomInitiative.class.getName());

	/** Пары охотника: «objectId игрока:время последней инициативы, с:1 — проигнорировал;...», свежие первыми. */
	static final String SOCIAL_VARIABLE = "L2HubSocial";
	private static final int SOCIAL_ENTRIES = 30;
	/** Столько проходов подряд игрок рядом — подошёл. */
	private static final int ARRIVAL_PASSES = 2;
	/** Проходов не было дольше этого (охотник был вне мира, в пати, дрался с игроком) — кто рядом, считается заново и без бросков. */
	private static final long PASS_GAP = 10000;
	/** Бронь игрока, пока мозги отвечают, знакомы ли. */
	private static final long RESERVE = 30000;
	private static final long HOUR = 3600000;
	private static final Pattern MET = Pattern.compile("\"met\"\\s*:\\s*(true|false)");
	private static final Pattern AGO = Pattern.compile("\"ago\"\\s*:\\s*(-?\\d+)");

	/** Охотник → игроки рядом: objectId → сколько проходов подряд. */
	private final Map<Integer, Map<Integer, Integer>> _near = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _lastPass = new ConcurrentHashMap<>();
	/** Охотник → не заговаривает первым до. */
	private final Map<Integer, Long> _hunterNext = new ConcurrentHashMap<>();
	/** Игрок → к нему никто не обращается до (бронь или промежуток). */
	private final Map<Integer, Long> _playerNext = new ConcurrentHashMap<>();
	/** Игрок → когда к нему обращались первыми за последний час. */
	private final Map<Integer, Deque<Long>> _playerHour = new ConcurrentHashMap<>();
	/** Пара → не бросать снова до. */
	private final Map<Long, Long> _pairRoll = new ConcurrentHashMap<>();
	/** Пара → ждёт ответа до. */
	private final Map<Long, Long> _waiting = new ConcurrentHashMap<>();

	protected PhantomInitiative()
	{
	}

	/**
	 * Проход охотника (из тика {@link PhantomHunterManager}, раз в 2 с): кто рядом, не пора ли заговорить первым. За проход — не больше одного кандидата.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param busy охотник занят (дерётся с игроком)
	 * @param realPlayers в мире есть живые игроки
	 * @param now текущее время
	 */
	public void look(Player hunter, PhantomTemplate template, boolean busy, boolean realPlayers, long now)
	{
		final int hunterId = hunter.getObjectId();
		expireWaiting(hunter, now);
		if (!PhantomHuntersConfig.HUNTER_INITIATIVE || !realPlayers || !PhantomChat.brainEnabled())
		{
			_near.remove(hunterId);
			_lastPass.remove(hunterId);
			return;
		}

		final Long last = _lastPass.put(hunterId, now);
		final boolean fresh = (last == null) || ((now - last) > PASS_GAP);
		final Map<Integer, Integer> before = fresh ? Map.of() : _near.getOrDefault(hunterId, Map.of());
		final Map<Integer, Integer> near = new HashMap<>();
		final List<Player> arrived = new ArrayList<>();
		for (Player player : World.getVisibleObjectsInRange(hunter, Player.class, PhantomHuntersConfig.HUNTER_INITIATIVE_RANGE))
		{
			if (!isLive(player))
			{
				continue;
			}

			// Первый проход: все, кто рядом, — «давно здесь», бросков нет.
			final int passes = fresh ? ARRIVAL_PASSES + 1 : Math.min(ARRIVAL_PASSES + 1, before.getOrDefault(player.getObjectId(), 0) + 1);
			near.put(player.getObjectId(), passes);
			if (passes == ARRIVAL_PASSES)
			{
				arrived.add(player);
			}
		}
		_near.put(hunterId, near);

		if (arrived.isEmpty() || busy || hunter.isDead() || hunter.isTeleporting() || (hunter.getCurrentHp() < (hunter.getMaxHp() * 0.5)) || (now < _hunterNext.getOrDefault(hunterId, 0L)))
		{
			return;
		}

		// Патч 0026: усталый первым не заговаривает, а вокруг только что говорили — не время.
		if ((PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED) || !PhantomQuiet.getInstance().allowed(hunter, PhantomQuiet.INITIATIVE, now))
		{
			return;
		}

		for (Player player : arrived)
		{
			if (allowed(hunter, player, now))
			{
				consider(hunter, template, player, now);
				return;
			}
		}
	}

	/**
	 * Игрок что-то сказал охотнику (шёпот, общий чат с именем или в разговоре): ответил на инициативу; пометка «проигнорировал» снимается — он заговорил первым.
	 * @param hunter охотник
	 * @param player игрок
	 */
	public void onSpoke(Player hunter, Player player)
	{
		_waiting.remove(pair(hunter, player));
		final long[] entry = entry(hunter, player.getObjectId());
		if ((entry != null) && (entry[1] == 1))
		{
			store(hunter, player.getObjectId(), entry[0], false);
			LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " заговорил с " + hunter.getName() + " сам — пометка игнора снята.");
		}
	}

	// ---------- Правила ----------

	private static boolean isLive(Player player)
	{
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInOfflineMode();
	}

	/**
	 * Фильтры без модели: игрок занят, кулдауны, потолок в час, пара проигнорирована.
	 */
	private boolean allowed(Player hunter, Player player, long now)
	{
		if (player.isInvisible() || player.isDead() || player.isInCombat() || player.isTeleporting() || player.isInOlympiadMode() || player.isInStoreMode())
		{
			return false;
		}
		if ((hunter.isInParty() && (hunter.getParty() == player.getParty())) || PhantomRelations.getInstance().isEnemy(hunter, player) || PhantomChat.getInstance().inConversation(player, now))
		{
			return false;
		}

		final int playerId = player.getObjectId();
		if ((now < _playerNext.getOrDefault(playerId, 0L)) || (now < _pairRoll.getOrDefault(pair(hunter, player), 0L)) || (perHour(playerId, now) >= PhantomHuntersConfig.HUNTER_INITIATIVE_PLAYER_PER_HOUR))
		{
			return false;
		}

		final long[] entry = entry(hunter, playerId);
		return (entry == null) || ((entry[1] == 0) && (((now / 1000) - entry[0]) >= PhantomHuntersConfig.HUNTER_INITIATIVE_PAIR_COOLDOWN));
	}

	/**
	 * Бронь игрока и вопрос мозгам, знакомы ли; решение — в {@link #decide}.
	 */
	private void consider(Player hunter, PhantomTemplate template, Player player, long now)
	{
		final int playerId = player.getObjectId();
		_playerNext.put(playerId, now + RESERVE);
		_pairRoll.put(pair(hunter, player), now + (PhantomHuntersConfig.HUNTER_INITIATIVE_PLAYER_GAP * 1000L));

		final StringBuilder sb = new StringBuilder(128).append('{');
		FakePlayerBrain.field(sb, "kind", "acquaintance").append(',');
		FakePlayerBrain.field(sb, "fpc", template.getName()).append(',');
		sb.append("\"player\":{");
		FakePlayerBrain.field(sb, "name", player.getName());
		sb.append("}}");
		FakePlayerBrain.getInstance().send(sb.toString(), reply -> decide(hunter, template, player, reply), () -> _playerNext.remove(playerId));
	}

	/**
	 * Ответ мозгов «знакомы ли» пришёл: бросок и, если выпало, реплика.
	 */
	private void decide(Player hunter, PhantomTemplate template, Player player, String reply)
	{
		final int playerId = player.getObjectId();
		final Matcher met = MET.matcher(reply);
		if (!met.find())
		{
			_playerNext.remove(playerId);
			return;
		}

		// Знакомый — кто писал охотнику, или тот, кто ему помогал (патч 0032: хилил, реснул, качались вместе).
		final boolean known = Boolean.parseBoolean(met.group(1)) || (PhantomRelations.getInstance().gratitude(hunter, player) >= 1);
		final Matcher agoMatcher = AGO.matcher(reply);
		final long ago = agoMatcher.find() ? Long.parseLong(agoMatcher.group(1)) : -1;
		final String who = getClass().getSimpleName() + ": " + hunter.getName() + " -> " + player.getName() + ": " + (known ? "знаком" : "не знаком");
		if ((ago >= 0) && (ago < PhantomHuntersConfig.HUNTER_INITIATIVE_RECENT_TALK))
		{
			_playerNext.remove(playerId);
			LOGGER.info(who + ", говорили " + (ago / 60) + " мин назад — не заговаривает.");
			return;
		}

		// Настроение (патч 0026): настороженный незнакомым не пишет, занятый — вдвое реже, скучающий — в полтора раза чаще.
		final PhantomMood mood = PhantomHunterManager.getInstance().mood(hunter);
		// Другу — чаще (патч 0032): × (1 + благодарность / 3), не больше × 2.
		final double friend = PhantomRelations.getInstance().initiativeFactor(hunter, player);
		final double chance = (known ? PhantomHuntersConfig.HUNTER_INITIATIVE_CHANCE_KNOWN : PhantomHuntersConfig.HUNTER_INITIATIVE_CHANCE_STRANGER) * template.getSocial() * (mood != null ? mood.initiativeFactor(known) : 1) * friend;
		final boolean yes = Rnd.get(100.0) < chance;
		LOGGER.info(who + (friend > 1 ? ", друг" : "") + (mood != null ? ", " + mood.getWord() : "") + ", шанс " + String.format(Locale.ROOT, "%.0f", chance) + "% — " + (yes ? "заговаривает." : "нет."));
		final long now = System.currentTimeMillis();
		if (!yes || hunter.isDead() || !isLive(player) || player.isInCombat() || player.isDead() || player.isTeleporting() || (hunter.calculateDistance2D(player) > (PhantomHuntersConfig.HUNTER_INITIATIVE_RANGE * 2)))
		{
			_playerNext.remove(playerId);
			return;
		}

		_hunterNext.put(hunter.getObjectId(), now + (PhantomHuntersConfig.HUNTER_INITIATIVE_HUNTER_COOLDOWN * 1000L));
		_playerNext.put(playerId, now + (PhantomHuntersConfig.HUNTER_INITIATIVE_PLAYER_GAP * 1000L));
		final Deque<Long> hour = _playerHour.computeIfAbsent(playerId, k -> new ArrayDeque<>());
		synchronized (hour)
		{
			hour.addLast(now);
		}
		store(hunter, playerId, now / 1000, false);

		final long pair = pair(hunter, player);
		PhantomChat.getInstance().initiative(hunter, player, template, situation(player), () -> _waiting.put(pair, System.currentTimeMillis() + (PhantomHuntersConfig.HUNTER_INITIATIVE_ANSWER_WAIT * 1000L)));
	}

	/**
	 * Что видит охотник — для мозгов, от его лица; где он сам и чем занят, мозги знают из его состояния.
	 */
	private static String situation(Player player)
	{
		String what = "рядом с тобой остановился " + PhantomHunterManager.describe(player);
		if (player.isInParty())
		{
			what += ", он в пати (" + player.getParty().getMemberCount() + " чел.)";
		}
		if (player.isSitting())
		{
			what += ", сидит отдыхает";
		}
		return what;
	}

	/**
	 * Ответа не было — пара «проигнорирована».
	 */
	private void expireWaiting(Player hunter, long now)
	{
		final long high = (long) hunter.getObjectId() << 32;
		for (Iterator<Map.Entry<Long, Long>> it = _waiting.entrySet().iterator(); it.hasNext();)
		{
			final Map.Entry<Long, Long> e = it.next();
			if (((e.getKey() & 0xffffffff00000000L) != high) || (now < e.getValue()))
			{
				continue;
			}

			it.remove();
			final int playerId = (int) (e.getKey() & 0xffffffffL);
			final long[] entry = entry(hunter, playerId);
			store(hunter, playerId, entry != null ? entry[0] : now / 1000, true);
			final Player player = World.getPlayer(playerId);
			LOGGER.info(getClass().getSimpleName() + ": " + (player != null ? player.getName() : String.valueOf(playerId)) + " не ответил " + hunter.getName() + " — тот больше не заговаривает с ним первым.");
		}
	}

	private int perHour(int playerId, long now)
	{
		final Deque<Long> hour = _playerHour.get(playerId);
		if (hour == null)
		{
			return 0;
		}

		synchronized (hour)
		{
			while (!hour.isEmpty() && ((now - hour.peekFirst()) > HOUR))
			{
				hour.pollFirst();
			}
			return hour.size();
		}
	}

	// ---------- Пары: переменная персонажа охотника ----------

	/**
	 * @return {время последней инициативы, с; 1 — проигнорировал} или {@code null}
	 */
	private static long[] entry(Player hunter, int playerId)
	{
		final String id = String.valueOf(playerId);
		for (String entry : hunter.getVariables().getString(SOCIAL_VARIABLE, "").split(";"))
		{
			final String[] parts = entry.split(":");
			if ((parts.length == 3) && parts[0].equals(id))
			{
				try
				{
					return new long[]
					{
						Long.parseLong(parts[1]),
						Long.parseLong(parts[2])
					};
				}
				catch (NumberFormatException e)
				{
					return null;
				}
			}
		}
		return null;
	}

	private static void store(Player hunter, int playerId, long time, boolean ignored)
	{
		final String id = String.valueOf(playerId);
		final List<String> entries = new ArrayList<>();
		entries.add(id + ":" + time + ":" + (ignored ? 1 : 0));
		for (String entry : hunter.getVariables().getString(SOCIAL_VARIABLE, "").split(";"))
		{
			if ((entry.split(":").length == 3) && !entry.startsWith(id + ":"))
			{
				entries.add(entry);
			}
		}
		hunter.getVariables().set(SOCIAL_VARIABLE, String.join(";", entries.subList(0, Math.min(entries.size(), SOCIAL_ENTRIES))));
		hunter.getVariables().storeMe();
	}

	private static long pair(Player hunter, Player player)
	{
		return ((long) hunter.getObjectId() << 32) | (player.getObjectId() & 0xffffffffL);
	}

	public static PhantomInitiative getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomInitiative INSTANCE = new PhantomInitiative();
	}
}
