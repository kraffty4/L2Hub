package custom.L2HubX10Smoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.OfflineTradeConfig;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;

/** Только для отдельного тестового сервера. Временные Player и переменные не записываются в БД. */
public final class X10EventEntrySmoke
{
	private static final Logger LOGGER = Logger.getLogger(X10EventEntrySmoke.class.getName());
	private static final List<Player> PLAYERS = new ArrayList<>();
	private static final List<Integer> INSTANCES = new ArrayList<>();
	private static int checks;

	public static class MemoryVariables extends PlayerVariables
	{
		public MemoryVariables(int id) { super(id); }
		@Override public boolean restoreMe() { return true; }
		@Override public boolean storeMe() { return true; }
		@Override public boolean saveNow() { return true; }
	}

	public static void main(String[] args) { ThreadPool.schedule(X10EventEntrySmoke::run, 45000); }

	private static void check(boolean value, String message)
	{
		checks++;
		if (!value) { throw new AssertionError(message); }
	}

	private static Field field(Class<?> type, String name) throws Exception
	{
		final Field field = type.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	private static Method method(Class<?> type, String name, Class<?>... args) throws Exception
	{
		final Method method = type.getDeclaredMethod(name, args);
		method.setAccessible(true);
		return method;
	}

	@SuppressWarnings("unchecked")
	private static Player player(int id) throws Exception
	{
		final Constructor<Player> constructor = Player.class.getDeclaredConstructor(int.class, PlayerTemplate.class, String.class, PlayerAppearance.class);
		constructor.setAccessible(true);
		final Player player = constructor.newInstance(id, PlayerTemplateData.getInstance().getTemplate(0), "x10-smoke", new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
		player.setName("X10Smoke" + Math.abs(id));
		final MemoryVariables variables = new MemoryVariables(id);
		player.addScript(variables);
		final Map<String, Object> scripts = (Map<String, Object>) field(org.l2jmobius.gameserver.entity.WorldObject.class, "_scripts").get(player);
		scripts.put(PlayerVariables.class.getName(), variables);
		check(player.getVariables() == variables, "Memory-only variables are attached under base type");
		player.setXYZInvisible(83400, 147900, -3400);
		player.setOnlineStatus(true, false);
		player.fullRestore();
		PLAYERS.add(player);
		return player;
	}

	@SuppressWarnings("unchecked")
	private static void run()
	{
		final boolean offlineDisconnect = OfflineTradeConfig.OFFLINE_DISCONNECT_FINISHED;
		try
		{
			final Class<?> event = Class.forName("org.l2jmobius.gameserver.managers.world.X10FarmEvent");
			final Class<?> kindType = Class.forName("org.l2jmobius.gameserver.managers.world.X10EventRules$Kind");
			final Object[] kinds = kindType.getEnumConstants();
			final Object farm = java.util.Arrays.stream(kinds).filter(value -> value.toString().equals("FARM")).findFirst().orElseThrow();
			synchronized (event)
			{
				final Field nextKind = field(event, "nextKind"), nextStart = field(event, "nextStart"), kind = field(event, "kind");
				final Field warnedStart = field(event, "warnedStart"), warnedSeconds = field(event, "warnedSeconds");
				final Map<Integer, Player> queue = (Map<Integer, Player>) field(event, "QUEUE").get(null);
				final Map<Integer, ?> members = (Map<Integer, ?>) field(event, "MEMBERS").get(null);
				check(queue.isEmpty() && members.isEmpty() && !field(event, "running").getBoolean(null), "Test server must have no active event or registrations");
				final Object oldKind = kind.get(null), oldNextKind = nextKind.get(null);
				final long oldStart = nextStart.getLong(null), oldWarn = warnedStart.getLong(null);
				final int oldSeconds = warnedSeconds.getInt(null);
				try
				{
					OfflineTradeConfig.OFFLINE_DISCONNECT_FINISHED = false;
					final Player player = player(-910001), ally = player(-910002);
					final Method error = method(event, "entryError", Player.class, kindType);
					final Method action = method(event, "action", Player.class, String.class, kindType);
					final Class<?> world = Class.forName("org.l2jmobius.gameserver.managers.world.X10World");
					player.getAI().setAutoAttacking(true);
					ally.getAI().setAutoAttacking(true);
					player.setCastingNow(true);
					player.setFakeDeath(true);
					player.setTarget(ally);
					player.updatePvPFlag(1);
					player.setKarma(123);
					player.setCursedWeaponEquippedId(1);
					player.setInsideZone(ZoneId.PVP, true);
					player.setPrivateStoreType(PrivateStoreType.SELL);
					field(Player.class, "_fishing").setBoolean(player, true);
					final Instance source = InstanceManager.getInstance().createDynamicInstance(0);
					INSTANCES.add(source.getId());
					source.addPlayer(player.getObjectId());
					player.setInstanceId(source.getId());
					check(player.isInCombat(), "Real Player combat state");
					check(!((String) method(world, "teleportError", Player.class).invoke(null, player)).isEmpty(), "Ordinary teleport stays blocked");
					for (Object format : kinds)
					{
						nextKind.set(null, format);
						nextStart.setLong(null, System.currentTimeMillis() + 60000);
						check(((String) error.invoke(null, player, format)).isEmpty(), "Allowed combat/cast/fishing/store/instance/PvP/karma/cursed: " + format);
						action.invoke(null, player, "join", format);
						check(queue.containsKey(player.getObjectId()), "Combat registration: " + format);
						action.invoke(null, player, "join", format);
						check(queue.size() == 1 && ((String) error.invoke(null, player, format)).isEmpty(), "Own registration is allowed");
						action.invoke(null, player, "cancel", format);
					}
					player.setDead(true);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Dead blocked");
					player.setDead(false);
					player.setInOlympiadMode(true);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Olympiad blocked");
					player.setInOlympiadMode(false);
					player.setRegisteredOnEvent(true);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Other registration blocked");
					player.setRegisteredOnEvent(false);
					player.setOnEvent(true);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Other event blocked");
					player.setOnEvent(false);
					player.setSiegeState((byte) 1);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Siege participant blocked");
					player.setSiegeState((byte) 0);
					player.setInsideZone(ZoneId.SIEGE, true);
					check(!((String) error.invoke(null, player, farm)).isEmpty(), "Siege zone blocked");
					player.setInsideZone(ZoneId.SIEGE, false);
					player.setParty(new Party(player, PartyDistributionType.FINDERS_KEEPERS));
					ally.joinParty(player.getParty());
					for (Object format : kinds)
					{
						nextKind.set(null, format);
						action.invoke(null, player, "joinparty", format);
						check(queue.size() == 2, "Combat party sign-up: " + format);
						final long start = nextStart.getLong(null);
						method(event, "warnStart", long.class).invoke(null, start - 30000);
						check(warnedSeconds.getInt(null) == 30, "30 second warning");
						method(event, "warnStart", long.class).invoke(null, start - 10000);
						check(warnedSeconds.getInt(null) == 10, "10 second warning");
						action.invoke(null, player, "cancel", format);
						action.invoke(null, ally, "cancel", format);
						nextStart.setLong(null, start + 1000);
					}
					kind.set(null, farm);
					final Class<?> roomType = Class.forName(event.getName() + "$Room");
					final Constructor<?> roomConstructor = roomType.getDeclaredConstructor(int.class);
					roomConstructor.setAccessible(true);
					final Object room = roomConstructor.newInstance(1);
					final Instance instance = (Instance) field(roomType, "instance").get(room);
					INSTANCES.add(instance.getId());
					method(event, "enter", Player.class, roomType).invoke(null, player, room);
					check(player.getInstanceId() == instance.getId() && player.isOnEvent(), "Actual event teleport from combat and another instance");
					check(!player.isFakeDeath() && !player.isFishing() && !player.isInStoreMode() && !player.isCastingNow() && player.getTarget() == null, "Fishing/store/cast/target cleared");
					check(player.getPvpFlag() == 1 && player.getKarma() == 123 && player.isCursedWeaponEquipped(), "PvP/karma/cursed preserved");
					members.remove(player.getObjectId());
					Files.writeString(Path.of("log/x10-entry-smoke.json"), "{\"passed\":true,\"checks\":" + checks + ",\"combatTeleport\":true,\"partyFormats\":3,\"warnings\":[30,10]}");
					LOGGER.info("X10 event entry smoke: PASSED (" + checks + " checks)");
				}
				finally
				{
					for (Player player : PLAYERS) { queue.remove(player.getObjectId()); members.remove(player.getObjectId()); }
					kind.set(null, oldKind); nextKind.set(null, oldNextKind); nextStart.setLong(null, oldStart);
					warnedStart.setLong(null, oldWarn); warnedSeconds.setInt(null, oldSeconds);
				}
			}
		}
		catch (Throwable error) { LOGGER.log(Level.SEVERE, "X10 event entry smoke: FAILED", error); }
		finally
		{
			for (Player player : PLAYERS)
			{
				player.setOnlineStatus(false, false);
				player.getAI().setAutoAttacking(false);
				player.stopVitalityTask();
				player.getStatus().stopHpMpRegeneration();
				player.setInstanceId(0);
				player.decayMe();
				World.removeObject(player);
			}
			for (int id : INSTANCES) { InstanceManager.getInstance().destroyInstance(id); }
			OfflineTradeConfig.OFFLINE_DISCONNECT_FINISHED = offlineDisconnect;
		}
	}
}
