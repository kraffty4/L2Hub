package org.l2jmobius.gameserver.managers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.FakePlayersConfig;
import org.l2jmobius.gameserver.data.xml.MapRegionData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.taskmanagers.GameTimeTaskManager;

/**
 * L2Hub: мозги фейковых игроков. Реплику фейка пишет внешний сервис мозгов (launcher/l2hub/brain.py).<br>
 * Запрос уходит асинхронно и не держит игровые потоки; ответ показывается с задержкой «набора текста».<br>
 * Пустой ответ — фейк промолчал. Если сервис недоступен или ответил ошибкой, вызывается запасной вариант.
 */
public class FakePlayerBrain
{
	private static final Logger LOGGER = Logger.getLogger(FakePlayerBrain.class.getName());
	private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
	private static final long FAILURE_LOG_INTERVAL = 60000;

	private volatile long _lastFailureLog = 0;

	protected FakePlayerBrain()
	{
	}

	/**
	 * @param player игрок, который обратился к фейку
	 * @param npc фейк
	 * @param channel канал: whisper или say
	 * @param message что сказал игрок
	 * @param onReply куда отдать реплику фейка
	 * @param onFail что сделать, если сервис мозгов не ответил
	 */
	public void ask(Player player, Npc npc, String channel, String message, Consumer<String> onReply, Runnable onFail)
	{
		post(buildRequest(player, npc, channel, message), onReply, onFail, false);
	}

	/**
	 * Запрос с готовым JSON (L2Hub: напарники-фантомы, патч 0010). В отличие от {@link #ask}, пустой ответ тоже отдаётся в onReply — сразу, без задержки.
	 * @param json тело запроса POST /chat
	 * @param onReply куда отдать реплику (пустая строка — промолчал)
	 * @param onFail что сделать, если сервис мозгов не ответил
	 */
	public void send(String json, Consumer<String> onReply, Runnable onFail)
	{
		post(json, onReply, onFail, true);
	}

	private void post(String json, Consumer<String> onReply, Runnable onFail, boolean replyEmpty)
	{
		final long start = System.currentTimeMillis();
		final HttpRequest request;
		try
		{
			request = HttpRequest.newBuilder(URI.create(FakePlayersConfig.FAKE_PLAYER_BRAIN_URL + "/chat")).timeout(Duration.ofSeconds(FakePlayersConfig.FAKE_PLAYER_BRAIN_TIMEOUT)).header("Content-Type", "application/json; charset=utf-8").POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
		}
		catch (Exception e)
		{
			logFailure("неверный запрос: " + e.getMessage());
			onFail.run();
			return;
		}

		CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((response, error) ->
		{
			if ((error != null) || (response.statusCode() != 200))
			{
				logFailure(error != null ? error.getClass().getSimpleName() + ": " + error.getMessage() : "HTTP " + response.statusCode());
				onFail.run();
				return;
			}

			final String reply = response.body().strip();
			if (reply.isEmpty())
			{
				if (replyEmpty)
				{
					onReply.accept(reply);
				}
				return;
			}

			final long typing = Math.max(0, (1500 + (reply.length() * 60L)) - (System.currentTimeMillis() - start));
			ThreadPool.schedule(() -> onReply.accept(reply), typing);
		});
	}

	private void logFailure(String reason)
	{
		final long now = System.currentTimeMillis();
		if ((now - _lastFailureLog) > FAILURE_LOG_INTERVAL)
		{
			_lastFailureLog = now;
			LOGGER.warning(getClass().getSimpleName() + ": сервис мозгов " + FakePlayersConfig.FAKE_PLAYER_BRAIN_URL + " не ответил (" + reason + "), фейки отвечают заготовками.");
		}
	}

	private static String buildRequest(Player player, Npc npc, String channel, String message)
	{
		final StringBuilder sb = new StringBuilder(640);
		sb.append('{');
		field(sb, "fpc", npc.getName()).append(',');
		sb.append("\"npcId\":").append(npc.getId()).append(',');
		field(sb, "channel", channel).append(',');
		field(sb, "message", message).append(',');
		sb.append("\"player\":{");
		field(sb, "name", player.getName()).append(',');
		sb.append("\"level\":").append(player.getLevel()).append(',');
		field(sb, "class", player.getPlayerClass().name()).append(',');
		field(sb, "race", player.getRace().name()).append(',');
		sb.append("\"female\":").append(player.getAppearance().isFemale()).append(',');
		field(sb, "clan", player.getClan() != null ? player.getClan().getName() : "").append(',');
		sb.append("\"gm\":").append(player.isGM()).append(',');
		sb.append("\"distance\":").append((int) npc.calculateDistance2D(player));
		sb.append("},\"npc\":{");
		sb.append("\"level\":").append(npc.getLevel()).append(',');
		sb.append("\"hpPct\":").append((int) ((npc.getCurrentHp() * 100) / Math.max(1, npc.getMaxHp()))).append(',');
		sb.append("\"inCombat\":").append(npc.isInCombat()).append(',');
		final WorldObject target = npc.getTarget();
		field(sb, "target", (target != null) && (target != npc) ? target.getName() : "").append(',');
		field(sb, "town", MapRegionData.getInstance().getClosestTownName(npc)).append(',');
		sb.append("\"x\":").append(npc.getX()).append(",\"y\":").append(npc.getY()).append(",\"z\":").append(npc.getZ());
		sb.append("},");
		final int time = GameTimeTaskManager.getInstance().getGameTime();
		field(sb, "gameTime", String.format("%02d:%02d", (time / 60) % 24, time % 60)).append(',');
		sb.append("\"night\":").append(GameTimeTaskManager.getInstance().isNight());
		sb.append('}');
		return sb.toString();
	}

	/**
	 * Строковое поле JSON {@code "key":"value"} с экранированием.
	 */
	public static StringBuilder field(StringBuilder sb, String key, String value)
	{
		sb.append('"').append(key).append("\":\"");
		final String text = value == null ? "" : value;
		for (int i = 0; i < text.length(); i++)
		{
			final char c = text.charAt(i);
			switch (c)
			{
				case '"' -> sb.append("\\\"");
				case '\\' -> sb.append("\\\\");
				case '\n' -> sb.append("\\n");
				case '\r' -> sb.append("\\r");
				case '\t' -> sb.append("\\t");
				default ->
				{
					if (c < 0x20)
					{
						sb.append(String.format("\\u%04x", (int) c));
					}
					else
					{
						sb.append(c);
					}
				}
			}
		}
		return sb.append('"');
	}

	public static FakePlayerBrain getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final FakePlayerBrain INSTANCE = new FakePlayerBrain();
	}
}
