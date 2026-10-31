package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;

/**
 * L2Hub: знакомый пишет издалека (docs/PHANTOMS.md, раздел 4.79; {@code HunterFarWhisper}).<br>
 * Раз в минуту для каждого живого игрока: прошёл кулдаун {@code HunterFarWhisperCooldown} и выпал {@code HunterFarWhisperChance} — ищем, кто ему напишет шёпотом.
 * Сначала друзья и те, кому он помогал, потом кто-то из охотников, с кем он когда-то говорил (мозги, {@code "kind":"acquaintance"}, без модели).
 * Пишет только свободный охотник дальше {@value #FAR} от игрока. Письмо — это инициатива: фильтры и кулдауны общие с {@link PhantomInitiative}, не ответил — пара «проигнорирована».
 * Слова пишут мозги; мозги недоступны — тишина.
 */
public final class PhantomFarWhisper
{
	private static final Logger LOGGER = Logger.getLogger(PhantomFarWhisper.class.getName());

	/** Ближе — охотник и так может заговорить вслух: письмо издалека не нужно. */
	static final int FAR = 2000;
	private static final long TICK = 60000;
	/** Скольких знакомых спросить за проход: запрос к памяти дешёвый, но не на весь мир. */
	private static final int SAMPLE = 6;
	private static final Pattern MET = Pattern.compile("\"met\"\\s*:\\s*(true|false)");
	private static final Pattern AGO = Pattern.compile("\"ago\"\\s*:\\s*(-?\\d+)");

	/** Игрок → не писать ему до. */
	private final Map<Integer, Long> _next = new ConcurrentHashMap<>();
	private boolean _started;

	private PhantomFarWhisper()
	{
	}

	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
	}

	private void tick()
	{
		if (!PhantomHuntersConfig.HUNTER_FAR_WHISPER || !PhantomChat.brainEnabled())
		{
			return;
		}
		final long now = System.currentTimeMillis();
		for (Player player : World.getPlayers())
		{
			if (PhantomNear.getInstance().isLive(player))
			{
				pass(player, now, false);
			}
		}
	}

	/**
	 * Проход по одному живому игроку.
	 * @param player игрок
	 * @param now текущее время
	 * @param force самопроверка: не ждать кулдауна и первого прохода
	 * @return нашлись кандидаты, письмо ищет автора
	 */
	boolean pass(Player player, long now, boolean force)
	{
		final int id = player.getObjectId();
		if (!force)
		{
			// Только вошёл: с ним здоровается {@link PhantomWelcome}, письмо — позже.
			final Long next = _next.putIfAbsent(id, now + Rnd.get(PhantomHuntersConfig.HUNTER_FAR_WHISPER_COOLDOWN * 1000L));
			if ((next == null) || (now < next) || (Rnd.get(100.0) >= PhantomHuntersConfig.HUNTER_FAR_WHISPER_CHANCE))
			{
				return false;
			}
		}

		final List<Player> close = new ArrayList<>();
		final List<Player> others = new ArrayList<>();
		for (Player hunter : World.getPlayers())
		{
			if (!free(hunter, player, now))
			{
				continue;
			}
			if (hunter.getFriendList().contains(id) || (PhantomRelations.getInstance().gratitude(hunter, player) >= 1))
			{
				close.add(hunter);
			}
			else
			{
				others.add(hunter);
			}
		}
		if (force)
		{
			LOGGER.info(getClass().getSimpleName() + ": SelfTest FAVORS: candidates for " + player.getName() + ": close " + close.stream().map(Player::getName).toList() + ", others " + others.size() + ".");
		}
		if (close.isEmpty() && others.isEmpty())
		{
			return false;
		}

		// Кулдаун игрока — сразу: пока ищем автора, следующий проход его не трогает.
		_next.put(id, now + (PhantomHuntersConfig.HUNTER_FAR_WHISPER_COOLDOWN * 1000L));
		Collections.shuffle(close);
		Collections.shuffle(others);
		final List<Player> order = new ArrayList<>(close);
		order.addAll(others);
		seek(player, order.subList(0, Math.min(SAMPLE, order.size())), close.size(), 0);
		return true;
	}

	/**
	 * Охотник свободен написать: не секретный, живой, не в бою, не на ивенте, не усталый, далеко и инициатива разрешает.
	 */
	private static boolean free(Player hunter, Player player, long now)
	{
		if ((hunter == player) || !PhantomManager.getInstance().isPhantom(hunter) || !PhantomHunterManager.getInstance().isHunter(hunter) || PhantomManager.getInstance().isSecret(hunter))
		{
			return false;
		}
		if (hunter.isDead() || hunter.isInCombat() || hunter.isTeleporting() || hunter.isInOlympiadMode() || PhantomEvents.getInstance().isBusy(hunter) || (hunter.calculateDistance2D(player) <= FAR))
		{
			return false;
		}
		return (PhantomHunterManager.getInstance().mood(hunter) != PhantomMood.TIRED) && PhantomInitiative.getInstance().mayAddress(hunter, player, now);
	}

	/**
	 * Спросить мозги о следующем кандидате: первые {@code closeCount} и так знакомы, остальным нужно {@code met}; говорили недавно — не пишет.
	 */
	private void seek(Player player, List<Player> order, int closeCount, int index)
	{
		if ((index >= order.size()) || !player.isOnline())
		{
			return;
		}

		final Player hunter = order.get(index);
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if (template == null)
		{
			seek(player, order, closeCount, index + 1);
			return;
		}
		final StringBuilder sb = new StringBuilder(128).append('{');
		FakePlayerBrain.field(sb, "kind", "acquaintance").append(',');
		FakePlayerBrain.field(sb, "fpc", template.getName()).append(',');
		sb.append("\"player\":{");
		FakePlayerBrain.field(sb, "name", player.getName());
		sb.append("}}");
		FakePlayerBrain.getInstance().send(sb.toString(), reply ->
		{
			final Matcher met = MET.matcher(reply);
			final Matcher ago = AGO.matcher(reply);
			final long seconds = ago.find() ? Long.parseLong(ago.group(1)) : -1;
			final boolean known = (index < closeCount) || (met.find() && Boolean.parseBoolean(met.group(1)));
			if (known && ((seconds < 0) || (seconds >= PhantomHuntersConfig.HUNTER_INITIATIVE_RECENT_TALK)))
			{
				write(hunter, template, player);
			}
			else
			{
				seek(player, order, closeCount, index + 1);
			}
		}, () ->
		{
			// Мозги недоступны — никто не пишет.
		});
	}

	private void write(Player hunter, PhantomTemplate template, Player player)
	{
		final long now = System.currentTimeMillis();
		if (!free(hunter, player, now) || !player.isOnline())
		{
			return;
		}

		final Runnable waiting = PhantomInitiative.getInstance().book(hunter, player, now);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " writes to " + player.getName() + " from " + Math.round(hunter.calculateDistance2D(player)) + " away.");
		final String what = PhantomHunterManager.describe(player) + " сейчас далеко от тебя, вы не видите друг друга. Ты сам вспомнил о нём и пишешь ему шёпотом: коротко, как знакомому, без просьб и без приглашений";
		PhantomChat.getInstance().favor(hunter, player, template, "far", what, true, waiting);
	}

	public static PhantomFarWhisper getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomFarWhisper INSTANCE = new PhantomFarWhisper();
	}
}
