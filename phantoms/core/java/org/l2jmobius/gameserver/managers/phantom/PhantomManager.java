package org.l2jmobius.gameserver.managers.phantom;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.stat.PlayerStat;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.world.WorldDaily;
import org.l2jmobius.gameserver.network.Disconnection;
import org.l2jmobius.gameserver.network.GameClient;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/**
 * L2Hub: фантомы — настоящие персонажи игроков без клиента (напарники).<br>
 * Приём тот же, что у Mobius для офлайн-игроков после рестарта ({@code OfflinePlayTable.restoreOfflinePlayers}): {@link Player#load}, вход в мир через {@code spawnMe}, {@code setOfflinePlay(true)} и
 * {@code setOnlineStatus(true, true)}. Персонажи живут на аккаунте {@link PhantomsConfig#PHANTOM_ACCOUNT}; вход под ним закрыт баном в таблице accounts.<br>
 * Кто из фантомов в мире, хранится в глобальной переменной {@value #ACTIVE_VARIABLE}: после рестарта они поднимаются на своих местах.
 */
public class PhantomManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomManager.class.getName());

	private static final String ACTIVE_VARIABLE = "L2HubPhantomsActive";
	private static final String FIND_CHARACTER = "SELECT charId FROM characters WHERE char_name=? AND account_name=?";
	private static final String BAN_ACCOUNT = "INSERT INTO accounts (login, password, accessLevel) VALUES (?, ?, -1) ON DUPLICATE KEY UPDATE accessLevel = -1";

	/** Фантомы в мире: objectId → напарник. */
	private final Map<Integer, PhantomTemplate> _phantoms = new ConcurrentHashMap<>();

	protected PhantomManager()
	{
	}

	/**
	 * Вызывается при старте сервера, когда мир уже загружен: закрывает вход под аккаунтом фантомов и поднимает тех, кто был в мире.
	 */
	public void onServerStart()
	{
		// Живой мир (L2HubWorld.ini, docs/WORLD.md) — не зависит от фантомов: поручения дня и прочее. Его сбой не должен остановить фантомов.
		try
		{
			WorldDaily.getInstance().start();
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": WorldDaily did not start.", e);
		}

		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			LOGGER.info(getClass().getSimpleName() + ": Disabled.");
			return;
		}

		PhantomData.getInstance();
		if (PhantomsConfig.PHANTOM_BAN_ACCOUNT_LOGIN)
		{
			banLogin();
		}

		if (PhantomsConfig.PHANTOM_RESTORE_ON_STARTUP)
		{
			restore();
		}

		// Этап 5: охотники живут сами, по режиму из PhantomHunters.ini.
		PhantomHunterManager.getInstance().start();
	}

	/**
	 * @param player игрок
	 * @return {@code true}, если это фантом в мире
	 */
	public boolean isPhantom(Player player)
	{
		return (player != null) && _phantoms.containsKey(player.getObjectId());
	}

	/**
	 * Секретный персонаж модуля — Танатос (docs/PHANTOMS.md, раздел 4.45): о нём не пишут ни летопись, ни окна. По имени, а не по {@link #isPhantom}:
	 * уровни и профессию он получает при сборке, ещё до входа в мир.
	 * @param player игрок
	 * @return это он
	 */
	public boolean isSecret(Player player)
	{
		final PhantomTemplate reaper = player == null ? null : PhantomData.getInstance().getReaper();
		return (reaper != null) && reaper.getName().equalsIgnoreCase(player.getName());
	}

	/**
	 * @param template напарник
	 * @return его фантом в мире или {@code null}
	 */
	public Player getPhantom(PhantomTemplate template)
	{
		final Player player = World.getPlayer(template.getName());
		return isPhantom(player) ? player : null;
	}

	/**
	 * Ставит напарника в точку: создаёт персонажа при первом вызове, подтягивает до уровня (профа, скиллы, шмот грейда) и вводит в мир. Если он уже в мире — пересобирает и переставляет.
	 * @param template напарник
	 * @param location куда поставить
	 * @param requestedLevel уровень
	 * @return фантом или {@code null}, если не вышло (причина — в логе)
	 */
	public synchronized Player spawn(PhantomTemplate template, Location location, int requestedLevel)
	{
		final int level = Math.max(1, Math.min(requestedLevel, PlayerConfig.PLAYER_MAXIMUM_LEVEL - 1));
		Player phantom = getPhantom(template);
		if (phantom != null)
		{
			PhantomOutfit.build(phantom, template, level);
			phantom.storeMe();
			phantom.setHeading(location.getHeading());
			phantom.teleToLocation(location, false);
			phantom.broadcastUserInfo();
			setActive(template.getName(), true);
			return phantom;
		}

		if (World.getPlayer(template.getName()) != null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + template.getName() + " is online as a real player, phantom not spawned.");
			return null;
		}

		phantom = loadOrCreate(template);
		if (phantom == null)
		{
			return null;
		}

		phantom.setOnlineStatus(true, false);
		PhantomOutfit.build(phantom, template, level);
		phantom.storeMe();
		enterWorld(phantom, template, location);
		setActive(template.getName(), true);
		return phantom;
	}

	/**
	 * Подтянуть напарника в мире до уровня на месте, без телепорта (владелец получил уровень): профа, скиллы, шмот грейда.
	 * @param template напарник
	 * @param requestedLevel уровень
	 * @return {@code true}, если пересобран
	 */
	public synchronized boolean relevel(PhantomTemplate template, int requestedLevel)
	{
		final Player phantom = getPhantom(template);
		if ((phantom == null) || phantom.isDead())
		{
			return false;
		}

		PhantomOutfit.build(phantom, template, Math.max(1, Math.min(requestedLevel, PlayerConfig.PLAYER_MAXIMUM_LEVEL - 1)));
		phantom.storeMe();
		phantom.broadcastUserInfo();
		return true;
	}

	/**
	 * Убирает напарника из мира. Персонаж остаётся в базе со своим уровнем и скиллами.
	 * @param template напарник
	 * @return {@code true}, если он был в мире
	 */
	public synchronized boolean despawn(PhantomTemplate template)
	{
		setActive(template.getName(), false);
		final Player phantom = getPhantom(template);
		if (phantom == null)
		{
			return false;
		}

		_phantoms.remove(phantom.getObjectId());
		if (phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
		}
		Disconnection.of(phantom).storeAndDelete();
		LOGGER.info(getClass().getSimpleName() + ": Despawned " + template.getName() + ".");
		return true;
	}

	/**
	 * Охотник входит в мир (этап 5): персонаж из базы как есть — уровень, профессия, инвентарь и место; новый — в стартовой деревне своей расы, 1 уровня.
	 * @param template охотник
	 * @return фантом или {@code null}, если не вышло (причина — в логе)
	 */
	public synchronized Player spawnHunter(PhantomTemplate template)
	{
		Player phantom = getPhantom(template);
		if (phantom != null)
		{
			return phantom;
		}

		if (World.getPlayer(template.getName()) != null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + template.getName() + " is online as a real player, hunter not spawned.");
			return null;
		}

		final boolean existed = findCharId(template.getName()) > 0;
		phantom = loadOrCreate(template);
		if (phantom == null)
		{
			return null;
		}

		phantom.setOnlineStatus(true, false);
		final Location location;
		if (existed && ((phantom.getX() != 0) || (phantom.getY() != 0)))
		{
			phantom.restoreEffects();
			location = new Location(phantom.getX(), phantom.getY(), phantom.getZ(), phantom.getHeading());
		}
		else
		{
			location = template.getHome();
		}
		PhantomHunterOutfit.update(phantom, template);
		enterWorld(phantom, template, location);
		return phantom;
	}

	/**
	 * Охотник уходит из мира с сохранением (режим охотников выключен или в игре никого).
	 * @param template охотник
	 * @return {@code true}, если он был в мире
	 */
	public synchronized boolean despawnHunter(PhantomTemplate template)
	{
		final Player phantom = getPhantom(template);
		if (phantom == null)
		{
			return false;
		}

		_phantoms.remove(phantom.getObjectId());
		AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
		AutoUseTaskManager.getInstance().stopAutoUseTask(phantom);
		Disconnection.of(phantom).storeAndDelete();
		return true;
	}

	/**
	 * Сброс прогресса охотника: уходит из мира и удаляется из базы целиком, как удалённый персонаж. В следующий раз создаётся заново с 1 уровня.
	 * @param template охотник
	 * @return {@code true}, если персонаж был в базе
	 */
	public synchronized boolean resetHunter(PhantomTemplate template)
	{
		despawnHunter(template);
		final int charId = findCharId(template.getName());
		if (charId <= 0)
		{
			return false;
		}

		GameClient.deleteCharByObjId(charId);
		LOGGER.info(getClass().getSimpleName() + ": Hunter " + template.getName() + " was reset (character " + charId + " deleted).");
		return true;
	}

	private void restore()
	{
		int restored = 0;
		for (String name : getActiveNames())
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(name);
			if (template == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Active phantom " + name + " is not in L2HubPhantoms.xml, dropped.");
				setActive(name, false);
				continue;
			}

			try
			{
				final boolean existed = findCharId(template.getName()) > 0;
				final Player phantom = loadOrCreate(template);
				if (phantom == null)
				{
					continue;
				}

				phantom.setOnlineStatus(true, false);
				final Location location;
				if (existed && ((phantom.getX() != 0) || (phantom.getY() != 0)))
				{
					phantom.restoreEffects();
					PhantomOutfit.refresh(phantom, template);
					location = new Location(phantom.getX(), phantom.getY(), phantom.getZ(), phantom.getHeading());
				}
				else
				{
					PhantomOutfit.build(phantom, template, PhantomsConfig.PHANTOM_DEFAULT_LEVEL);
					phantom.storeMe();
					location = template.getHome();
				}
				enterWorld(phantom, template, location);
				restored++;
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not restore phantom " + name + ".", e);
			}
		}
		LOGGER.info(getClass().getSimpleName() + ": Restored " + restored + " phantoms.");
	}

	/**
	 * Персонаж напарника из базы, а при первом вызове — новый: создаётся так же, как в {@code CharacterCreate}, сохраняется и загружается обратно полным {@link Player#load}.
	 */
	private Player loadOrCreate(PhantomTemplate template)
	{
		int charId = findCharId(template.getName());
		if (charId <= 0)
		{
			if (CharInfoTable.getInstance().doesCharNameExist(template.getName()))
			{
				LOGGER.warning(getClass().getSimpleName() + ": Name " + template.getName() + " is taken by a character of another account, phantom not created.");
				return null;
			}

			final PlayerTemplate playerTemplate = PlayerTemplateData.getInstance().getTemplate(template.getStartClassId());
			final Player created = Player.create(playerTemplate, PhantomsConfig.PHANTOM_ACCOUNT, template.getName(), new PlayerAppearance(template.getFace(), template.getHairColor(), template.getHairStyle(), template.isFemale()));
			if (created == null)
			{
				LOGGER.warning(getClass().getSimpleName() + ": Could not create character " + template.getName() + ".");
				return null;
			}

			// Как в CharacterCreate: полные бары до сохранения, иначе Player.load поднимет персонажа трупом (curHp = 0).
			created.setCurrentHp(created.getMaxHp());
			created.setCurrentMp(created.getMaxMp());
			created.setCurrentCp(created.getMaxCp());
			// И стартовая Sayha's Grace (vitality), как у живого: иначе созданный между недельными сбросами качается без её бонуса до ближайшего сброса.
			if (PlayerConfig.ENABLE_VITALITY)
			{
				created.setVitalityPoints(Math.min(PlayerConfig.STARTING_VITALITY_POINTS, PlayerStat.MAX_VITALITY_POINTS), true);
			}
			final Location home = template.getHome();
			created.setXYZInvisible(home.getX(), home.getY(), home.getZ());
			created.setTitle("");
			created.setOnlineStatus(true, false);
			Disconnection.of(created).storeAndDelete();
			charId = created.getObjectId();
			LOGGER.info(getClass().getSimpleName() + ": Created phantom character " + template.getName() + " (" + charId + ").");
		}

		final Player phantom = Player.load(charId);
		if (phantom == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not load phantom character " + template.getName() + " (" + charId + ").");
		}
		return phantom;
	}

	/**
	 * Вход в мир по образцу {@code OfflinePlayTable.restoreOfflinePlayers}: клиента нет и не будет. {@code setOfflinePlay(true)} нужен, чтобы AutoPlay не принял фантома за офлайн-торговца.
	 */
	private void enterWorld(Player phantom, PhantomTemplate template, Location location)
	{
		_phantoms.put(phantom.getObjectId(), template);
		phantom.setHeading(location.getHeading());
		phantom.spawnMe(location.getX(), location.getY(), location.getZ());
		phantom.setOfflinePlay(true);
		phantom.setOnlineStatus(true, true);
		phantom.setRunning();
		phantom.broadcastUserInfo();
		LOGGER.info(getClass().getSimpleName() + ": " + template.getName() + " entered the world at " + location.getX() + ", " + location.getY() + ", " + location.getZ() + ".");
	}

	private int findCharId(String name)
	{
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(FIND_CHARACTER))
		{
			ps.setString(1, name);
			ps.setString(2, PhantomsConfig.PHANTOM_ACCOUNT);
			try (ResultSet rs = ps.executeQuery())
			{
				return rs.next() ? rs.getInt("charId") : 0;
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": Could not look up " + name + ".", e);
			return 0;
		}
	}

	/**
	 * Закрывает вход под аккаунтом фантомов: строка в accounts с accessLevel = -1 (логин отвечает ACCOUNT_BANNED). Пароль — случайный, его никто не знает.
	 */
	private void banLogin()
	{
		final byte[] secret = new byte[24];
		new SecureRandom().nextBytes(secret);
		try (Connection con = DatabaseFactory.getConnection();
			PreparedStatement ps = con.prepareStatement(BAN_ACCOUNT))
		{
			ps.setString(1, PhantomsConfig.PHANTOM_ACCOUNT);
			ps.setString(2, Base64.getEncoder().encodeToString(secret));
			ps.executeUpdate();
		}
		catch (Exception e)
		{
			LOGGER.warning(getClass().getSimpleName() + ": Could not ban login of account " + PhantomsConfig.PHANTOM_ACCOUNT + ": " + e.getMessage());
		}
	}

	/**
	 * @return имена напарников, которые были в мире (в порядке появления)
	 */
	public List<String> getActiveNames()
	{
		final List<String> names = new ArrayList<>();
		for (String name : GlobalVariablesManager.getInstance().getString(ACTIVE_VARIABLE, "").split(";"))
		{
			if (!name.isBlank())
			{
				names.add(name.strip());
			}
		}
		return names;
	}

	private synchronized void setActive(String name, boolean active)
	{
		final Set<String> names = new LinkedHashSet<>(getActiveNames());
		final boolean changed = active ? names.add(name) : names.remove(name);
		if (changed)
		{
			GlobalVariablesManager.getInstance().set(ACTIVE_VARIABLE, String.join(";", names));
			GlobalVariablesManager.getInstance().storeMe();
		}
	}

	public static PhantomManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomManager INSTANCE = new PhantomManager();
	}
}
