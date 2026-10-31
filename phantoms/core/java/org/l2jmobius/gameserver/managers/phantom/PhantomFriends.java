package org.l2jmobius.gameserver.managers.phantom;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * L2Hub: охотник в списке друзей (docs/PHANTOMS.md, раздел 4.70; {@code HunterFriends} в PhantomHunters.ini).
 * <ul>
 * <li>заявка — штатное «добавить в друзья» игрока: врезка в {@code RequestFriendInvite}, окна у фантома нет, отвечает сервер через пару секунд;</li>
 * <li>ответ — по отношениям: врагу отказ, другу, соклановцу и напарнику по пати да, незнакомому — шанс {@value #STRANGER_CHANCE} %; отказал незнакомому — помнит это
 * {@value #REFUSED_MEMORY} мс, заявки подряд ответа не меняют;</li>
 * <li>принял — запись в {@code character_friends} и пакеты, как у {@code RequestAnswerFriendInvite} ({@link PhantomCompat#friendAdded}), и шёпот от мозгов;</li>
 * <li>в сети — фантом вошёл в мир: друзьям сообщение и значок, как из {@code EnterWorld} ({@link #onEnter}); вышел — {@code Player.deleteMe} сам;</li>
 * <li>письмо другу — врезка в {@code RequestSendFriendMsg}: отвечает как на шёпот.</li>
 * </ul>
 * Напарникам и прочим не-охотникам в друзья нельзя: штатное «не удалось добавить».
 */
public final class PhantomFriends
{
	private static final Logger LOGGER = Logger.getLogger(PhantomFriends.class.getName());

	private static final String INSERT = "INSERT INTO character_friends (charId, friendId) VALUES (?, ?), (?, ?)";
	/** Через столько отвечает на заявку, мс. */
	private static final int ANSWER_MIN = 2000;
	private static final int ANSWER_MAX = 6000;
	/** Шанс, что незнакомый примет заявку, %. */
	private static final int STRANGER_CHANCE = 70;
	/** Отказ незнакомому держится столько. */
	private static final long REFUSED_MEMORY = 1800000;
	/** Реплика о заявке тому же игроку — не чаще. */
	private static final long SAY_GAP = 10000;

	/** Пара «охотник — игрок» → до какого времени отказ в силе. */
	private final Map<Long, Long> _refused = new ConcurrentHashMap<>();

	private PhantomFriends()
	{
	}

	/**
	 * Из {@code RequestFriendInvite}: запрос уже отправлен штатно ({@code onTransactionRequest}, «вы пригласили»), ответа ждём здесь.
	 * @param player кто зовёт
	 * @param phantom кого зовут
	 */
	public void onInvited(Player player, Player phantom)
	{
		ThreadPool.schedule(() -> answer(player, phantom), Rnd.get(ANSWER_MIN, ANSWER_MAX));
	}

	private void answer(Player player, Player phantom)
	{
		phantom.setActiveRequester(null);
		player.onTransactionResponse();
		if (!player.isOnline())
		{
			return;
		}
		if (World.getPlayer(phantom.getObjectId()) == null)
		{
			player.sendPacket(new SystemMessage(SystemMessageId.YOU_HAVE_FAILED_TO_ADD_A_FRIEND_TO_YOUR_FRIENDS_LIST));
			return;
		}

		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if (!PhantomHuntersConfig.HUNTER_FRIENDS || (template == null) || !template.isHunter())
		{
			player.sendPacket(new SystemMessage(SystemMessageId.YOU_HAVE_FAILED_TO_ADD_A_FRIEND_TO_YOUR_FRIENDS_LIST));
			return;
		}

		final boolean enemy = PhantomRelations.getInstance().isEnemy(phantom, player);
		if (enemy || !accepts(player, phantom))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + phantom.getName() + " refuses " + player.getName() + (enemy ? " (enemy)." : " (stranger)."));
			player.sendPacket(new SystemMessage(SystemMessageId.YOU_HAVE_FAILED_TO_ADD_A_FRIEND_TO_YOUR_FRIENDS_LIST));
			PhantomChat.getInstance().hunterEvent(phantom, player, "FRIEND", "Игрок " + player.getName() + " просится к тебе в друзья, а ты " + (enemy ? "на него в обиде" : "его не знаешь") + " и отказал. Ответь ему коротко.", null, SAY_GAP, true);
			return;
		}
		if (!store(player, phantom))
		{
			player.sendPacket(new SystemMessage(SystemMessageId.YOU_HAVE_FAILED_TO_ADD_A_FRIEND_TO_YOUR_FRIENDS_LIST));
			return;
		}

		player.getFriendList().add(phantom.getObjectId());
		phantom.getFriendList().add(player.getObjectId());
		PhantomCompat.friendAdded(player, phantom);
		PhantomRelations.getInstance().record(phantom, player, PhantomRelations.Kind.FRIENDED);
		LOGGER.info(getClass().getSimpleName() + ": " + phantom.getName() + " is now a friend of " + player.getName() + ".");
		PhantomChat.getInstance().hunterEvent(phantom, player, "FRIEND", "Игрок " + player.getName() + " добавил тебя в друзья, и ты не против. Ответь ему коротко.", null, SAY_GAP, true);
	}

	/**
	 * Знакомым — да, незнакомому — как повезёт, и отказ держится.
	 */
	private boolean accepts(Player player, Player phantom)
	{
		if (PhantomRelations.getInstance().isFriend(phantom, player) || (phantom.isInParty() && (phantom.getParty() == player.getParty())) || ((phantom.getClan() != null) && (phantom.getClan() == player.getClan())))
		{
			return true;
		}
		final long now = System.currentTimeMillis();
		final long pair = (((long) phantom.getObjectId()) << 32) | player.getObjectId();
		final Long until = _refused.get(pair);
		if ((until != null) && (now < until))
		{
			return false;
		}
		if (Rnd.get(100) < STRANGER_CHANCE)
		{
			return true;
		}
		_refused.put(pair, now + REFUSED_MEMORY);
		return false;
	}

	private boolean store(Player player, Player phantom)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(INSERT))
		{
			ps.setInt(1, player.getObjectId());
			ps.setInt(2, phantom.getObjectId());
			ps.setInt(3, phantom.getObjectId());
			ps.setInt(4, player.getObjectId());
			ps.execute();
			return true;
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": could not add " + phantom.getName() + " to the friends of " + player.getName() + ".", e);
			return false;
		}
	}

	/**
	 * Фантом вошёл в мир: друзьям в сети — как у живого.
	 * @param phantom кто вошёл
	 */
	void onEnter(Player phantom)
	{
		for (int id : phantom.getFriendList())
		{
			final Player friend = World.getPlayer(id);
			if ((friend != null) && !PhantomManager.getInstance().isPhantom(friend))
			{
				PhantomCompat.friendLoggedIn(friend, phantom);
			}
		}
	}

	public static PhantomFriends getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomFriends INSTANCE = new PhantomFriends();
	}
}
