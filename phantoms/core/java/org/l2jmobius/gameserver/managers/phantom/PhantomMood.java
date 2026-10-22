package org.l2jmobius.gameserver.managers.phantom;

/**
 * L2Hub: настроение охотника (патч 0026, docs/PHANTOMS.md, раздел 4.10) — из его состояния, без модели; приём L2Solo ({@code BotAmbientDirector}).<br>
 * Считает {@link PhantomHunterManager#mood}. Настроение решает, заговорит ли охотник первым ({@link PhantomInitiative}) и встрянет ли в чужую реплику ({@link PhantomChat}),
 * и уходит мозгам в запросе ({@code "mood"}): усталый отвечает короче, настороженный не рад незнакомым.
 */
public enum PhantomMood
{
	/** Мало HP или MP, сидит отдыхает на споте: первым не заговаривает и не встревает. */
	TIRED("tired", "устал"),
	/** Недавно на него напал живой игрок или его убили: незнакомым первым не пишет и не встревает. */
	GUARDED("guarded", "настороже"),
	/** Занят делом (город по делам, идёт к позвавшему, в пати, бежит на спот): заговаривает первым вдвое реже. */
	FOCUSED("focused", "занят делом"),
	/** Скучает (сидит афк в городе, на споте не находит мобов): заговаривает первым в полтора раза чаще. */
	RESTLESS("restless", "скучает"),
	/** Болтун по характеру (общительность от 1,5) и ничем не занят. */
	SOCIABLE("sociable", "хочет поболтать"),
	/** Спокоен. */
	CALM("calm", "спокоен");

	private final String _key;
	private final String _word;

	PhantomMood(String key, String word)
	{
		_key = key;
		_word = word;
	}

	/**
	 * @return ключ для мозгов ({@code "mood"} в запросе)
	 */
	public String getKey()
	{
		return _key;
	}

	/**
	 * @return по-русски — для лога и лаунчера
	 */
	public String getWord()
	{
		return _word;
	}

	/**
	 * @param known знаком ли охотник с игроком
	 * @return множитель шанса заговорить первым
	 */
	public double initiativeFactor(boolean known)
	{
		switch (this)
		{
			case TIRED:
			{
				return 0;
			}
			case GUARDED:
			{
				return known ? 1 : 0;
			}
			case FOCUSED:
			{
				return 0.5;
			}
			case RESTLESS:
			{
				return 1.5;
			}
			default:
			{
				return 1;
			}
		}
	}

	/**
	 * @return встревает ли охотник в чужую реплику в общем чате
	 */
	public boolean overhears()
	{
		return (this != TIRED) && (this != GUARDED);
	}
}
