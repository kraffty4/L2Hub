package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.Deque;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.actor.Player;

/**
 * L2Hub: тишина в округе (патч 0026, docs/PHANTOMS.md, раздел 4.10) — общий бюджет непрошеных реплик фантомов вслух; приём L2Solo ({@code BotChatterBudget}).<br>
 * Каждая реплика фантома в общий чат рядом записывается сюда ({@link PhantomChat}, там все реплики вслух идут через одно место).
 * Непрошеная реплика — мысль стратега вслух, «о, опять ты» обидчику, встрял в чужую реплику, заговорил первым — проходит, только если:
 * <ul>
 * <li>в пределах слышимости ({@value #RANGE}) никто из фантомов ничего не говорил {@link PhantomHuntersConfig#HUNTER_CHAT_QUIET} с;</li>
 * <li>сам он ничего не говорил {@link PhantomHuntersConfig#HUNTER_CHAT_SPEAKER_GAP} с;</li>
 * <li>ту же тему ни он, ни кто-то рядом не поднимал {@link PhantomHuntersConfig#HUNTER_CHAT_TOPIC_GAP} с.</li>
 * </ul>
 * Ответы игроку и реплики на его действия (напал, убил, повержен) бюджет не режет: они только записываются, чтобы следом не встревали другие.
 */
public class PhantomQuiet
{
	/** Слышимость общего чата — как у игроков. */
	static final int RANGE = 1250;
	/** Темы непрошеных реплик; обидчик мимо — вид события охотника {@code GRUDGE}. */
	static final String THOUGHT = "thought";
	static final String OVERHEARD = "overheard";
	static final String INITIATIVE = "initiative";
	private static final int HISTORY = 256;

	private static class Line
	{
		final int speaker;
		final int x;
		final int y;
		final String topic;
		final long at;

		Line(Player speaker, String topic, long at)
		{
			this.speaker = speaker.getObjectId();
			this.x = speaker.getX();
			this.y = speaker.getY();
			this.topic = topic;
			this.at = at;
		}
	}

	private final Deque<Line> _lines = new ArrayDeque<>();

	protected PhantomQuiet()
	{
	}

	/**
	 * @param speaker фантом
	 * @param topic тема непрошеной реплики
	 * @param now текущее время
	 * @return можно сказать: вокруг тихо, сам давно молчит, тема не повторяется
	 */
	public synchronized boolean allowed(Player speaker, String topic, long now)
	{
		final long quiet = PhantomHuntersConfig.HUNTER_CHAT_QUIET * 1000L;
		final long speakerGap = PhantomHuntersConfig.HUNTER_CHAT_SPEAKER_GAP * 1000L;
		final long topicGap = PhantomHuntersConfig.HUNTER_CHAT_TOPIC_GAP * 1000L;
		prune(now, Math.max(quiet, Math.max(speakerGap, topicGap)));
		for (Line line : _lines)
		{
			final long age = now - line.at;
			final boolean self = line.speaker == speaker.getObjectId();
			final boolean near = self || (Math.hypot(line.x - speaker.getX(), line.y - speaker.getY()) <= RANGE);
			if ((self && (age < speakerGap)) || (near && (age < quiet)) || (near && (age < topicGap) && line.topic.equals(topic)))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Фантом сказал что-то вслух в общий чат рядом.
	 * @param speaker фантом
	 * @param topic тема: непрошеная или вид реплики (ответ, событие)
	 * @param now текущее время
	 */
	public synchronized void said(Player speaker, String topic, long now)
	{
		_lines.addLast(new Line(speaker, topic, now));
		while (_lines.size() > HISTORY)
		{
			_lines.pollFirst();
		}
	}

	private void prune(long now, long keep)
	{
		while (!_lines.isEmpty() && ((now - _lines.peekFirst().at) >= keep))
		{
			_lines.pollFirst();
		}
	}

	public static PhantomQuiet getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomQuiet INSTANCE = new PhantomQuiet();
	}
}
