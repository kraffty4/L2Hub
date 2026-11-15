package custom.L2HubX10Smoke;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Team;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.handler.ItemHandler;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.phantom.PersonalCompanions;
import org.l2jmobius.gameserver.managers.phantom.PhantomCompanionManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomData;
import org.l2jmobius.gameserver.managers.phantom.PhantomEvents;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomTemplate;
import org.l2jmobius.gameserver.managers.world.X10PlayerTools;
import org.l2jmobius.gameserver.managers.world.X10World;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/** Только тестовый HOME: временные игроки, реальные навыки/AI/обработчики предметов. Не запускать вместе с другими smoke. */
public final class X10EventCombatSmoke
{
	private static final Logger LOGGER = Logger.getLogger(X10EventCombatSmoke.class.getName());
	private static final List<Player> PLAYERS = new ArrayList<>();
	private static final List<Object> ROOMS = new ArrayList<>();
	private static final List<String> CHECKS = new ArrayList<>();
	private static final Class<?> EVENT = type("org.l2jmobius.gameserver.managers.world.X10FarmEvent");
	private static final Class<?> ROOM = type(EVENT.getName() + "$Room");
	private static final Class<?> COMPANION = type(PhantomCompanionManager.class.getName() + "$Companion");
	private static int nextId = -930000;

	public static class MemoryVariables extends PlayerVariables
	{
		public MemoryVariables(int id) { super(id); }
		@Override public boolean restoreMe() { return true; }
		@Override public boolean storeMe() { return true; }
		@Override public boolean saveNow() { return true; }
	}

	public static void main(String[] args) { ThreadPool.schedule(X10EventCombatSmoke::run, 45000); }
	private static Class<?> type(String name)
	{
		try { return Class.forName(name); }
		catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static Field field(Class<?> type, String name) throws Exception
	{
		final Field field = type.getDeclaredField(name); field.setAccessible(true); return field;
	}
	private static Object call(Class<?> type, Object receiver, String name, Class<?>[] args, Object... values) throws Exception
	{
		final Method method = type.getDeclaredMethod(name, args); method.setAccessible(true); return method.invoke(receiver, values);
	}
	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> map(Class<?> type, Object receiver, String name) throws Exception
	{
		return (Map<K, V>) field(type, name).get(receiver);
	}
	private static void check(boolean value, String name)
	{
		if (!value) { throw new AssertionError(name); }
		CHECKS.add(name); LOGGER.info("X10 combat smoke: OK " + name);
	}
	private static void await(BooleanSupplier done, Runnable tick, String name) throws Exception
	{
		final long until = System.currentTimeMillis() + 15000;
		while (!done.getAsBoolean() && System.currentTimeMillis() < until) { tick.run(); Thread.sleep(200); }
		check(done.getAsBoolean(), name);
	}
	@SuppressWarnings("unchecked")
	private static Player player(String name, int classId) throws Exception
	{
		final Constructor<Player> ctor = Player.class.getDeclaredConstructor(int.class, PlayerTemplate.class, String.class, PlayerAppearance.class);
		ctor.setAccessible(true);
		final Player player = ctor.newInstance(--nextId, PlayerTemplateData.getInstance().getTemplate(classId), "x10-combat-smoke", new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
		player.setName(name); PLAYERS.add(player);
		final MemoryVariables variables = new MemoryVariables(player.getObjectId()); player.addScript(variables);
		map(org.l2jmobius.gameserver.entity.WorldObject.class, player, "_scripts").put(PlayerVariables.class.getName(), variables);
		player.setOnlineStatus(true, false); player.fullRestore();
		return player;
	}
	private static void enter(Player player, Object room, int offset) throws Exception
	{
		call(EVENT, null, "enter", new Class<?>[] {Player.class, ROOM}, player, room);
		if (player.isTeleporting()) { player.onTeleported(); }
		final Location center = (Location) field(ROOM, "center").get(room);
		player.setXYZ(center.getX() + offset, center.getY(), center.getZ());
		player.getStatus().stopHpMpRegeneration();
	}
	private static PhantomTemplate template(Player player, PhantomTemplate.Role role, boolean hunter)
	{
		return new PhantomTemplate(player.getName(), role, false, (byte) 0, (byte) 0, (byte) 0, new int[] {0}, new Location(0, 0, 0), List.of(), "", hunter ? "hunter" : "companion", 0);
	}
	private static Object companion(Player owner, Player helper) throws Exception
	{
		final Constructor<?> ctor = COMPANION.getDeclaredConstructor(String.class); ctor.setAccessible(true);
		final Object companion = ctor.newInstance(helper.getName()); field(COMPANION, "ownerId").setInt(companion, owner.getObjectId());
		map(PhantomCompanionManager.class, PhantomCompanionManager.getInstance(), "_companions").put(helper.getName().toLowerCase(), companion);
		return companion;
	}
	private static void personalTick(Object companion)
	{
		try { call(PhantomCompanionManager.class, PhantomCompanionManager.getInstance(), "think", new Class<?>[] {COMPANION, long.class}, companion, System.currentTimeMillis()); }
		catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static void hunterTick(Player hunter, PhantomTemplate template)
	{
		try { call(PhantomEvents.class, PhantomEvents.getInstance(), "drive", new Class<?>[] {Player.class, PhantomTemplate.class, long.class}, hunter, template, System.currentTimeMillis()); }
		catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static void potion(Player player, int id, boolean automatic) throws Exception
	{
		player.fullRestore(); player.getStatus().stopHpMpRegeneration();
		if (id == 728) { player.setCurrentMp(1); }
		else if (id == 5592) { player.setCurrentCp(1); }
		else { player.setCurrentHp(1); }
		player.getStatus().stopHpMpRegeneration();
		final Item item = player.addItem(ItemProcessType.REWARD, id, 3, player, false);
		final long before = item.getCount();
		if (automatic)
		{
			player.getVariables().set("L2HubAutoPotions", true);
			player.getVariables().set("L2HubAutoPotItem_" + (id == 728 ? "mp" : id == 5592 ? "cp" : "hp"), id);
			call(X10PlayerTools.class, null, "tick", new Class<?>[0]);
		}
		else { check(ItemHandler.getInstance().getHandler(item.getEtcItem()).onItemUse(player, item, false), "manual handler accepted " + id); }
		await(() -> item.getCount() < before, () -> {}, (automatic ? "auto" : "manual") + " potion consumed " + id);
		player.getVariables().set("L2HubAutoPotions", false);
	}

	@SuppressWarnings("unchecked")
	private static void run()
	{
		Map<String, PhantomTemplate> oldTemplates = null;
		Object oldKind = null;
		long oldStarted = 0, oldNextStart = 0;
		boolean prepared = false;
		try
		{
			check(Files.isRegularFile(Path.of("log/x10-combat-smoke.allow")), "explicit test-server marker");
			check(X10World.enabled() && World.getPlayers().stream().noneMatch(p -> p.getClient() != null), "x10 enabled, no connected clients");
			check(!field(EVENT, "running").getBoolean(null) && map(EVENT, null, "MEMBERS").isEmpty() && map(EVENT, null, "QUEUE").isEmpty(), "no live event or queue");
			oldKind = field(EVENT, "kind").get(null);
			oldStarted = field(EVENT, "started").getLong(null); oldNextStart = field(EVENT, "nextStart").getLong(null);
			field(EVENT, "nextStart").setLong(null, System.currentTimeMillis() + 86400000L);
			oldTemplates = (Map<String, PhantomTemplate>) field(PhantomData.class, "_templates").get(PhantomData.getInstance());
			final Map<String, PhantomTemplate> templates = new HashMap<>(oldTemplates);
			field(PhantomData.class, "_templates").set(PhantomData.getInstance(), templates); prepared = true;
			// Установка площадки атомарна; во время ожидания штатный тик и события навыков работают.
			for (Object kind : type("org.l2jmobius.gameserver.managers.world.X10EventRules$Kind").getEnumConstants())
			{
				final Object room, otherRoom, helper;
				final Player owner, healer, hunter, outsider;
				final PhantomTemplate healerTemplate, hunterTemplate;
				final Creature target;
				synchronized (EVENT)
				{
					field(EVENT, "kind").set(null, kind); field(EVENT, "started").setLong(null, System.currentTimeMillis());
					final Constructor<?> ctor = ROOM.getDeclaredConstructor(int.class); ctor.setAccessible(true);
					room = ctor.newInstance(1); otherRoom = ctor.newInstance(1); ROOMS.add(room); ROOMS.add(otherRoom);
					((List<Object>) field(EVENT, "ROOMS").get(null)).add(room);
					field(ROOM, "nextWave").setLong(room, Long.MAX_VALUE);
					owner = player("X10Owner" + -nextId, 0); healer = player("X10Heal" + -nextId, 42); hunter = player("X10Hunter" + -nextId, 0); outsider = player("X10Outside" + -nextId, 0);
					healerTemplate = template(healer, PhantomTemplate.Role.HEALER, false); hunterTemplate = template(hunter, PhantomTemplate.Role.DAMAGE, true);
					for (PhantomTemplate t : List.of(healerTemplate, hunterTemplate)) { templates.put(t.getName().toLowerCase(), t); }
					map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").put(healer.getObjectId(), healerTemplate);
					map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").put(hunter.getObjectId(), hunterTemplate);
					owner.setParty(new Party(owner, PartyDistributionType.FINDERS_KEEPERS)); healer.joinParty(owner.getParty());
					helper = companion(owner, healer);
					for (Player p : List.of(owner, healer, hunter)) { enter(p, room, 0); }
					enter(outsider, otherRoom, 0);
					if (kind.toString().equals("HUNT"))
					{
						final Player quarry = player("X10Quarry" + -nextId, 0); enter(quarry, room, 40); quarry.setTeam(Team.RED);
						field(ROOM, "quarry").set(room, map(EVENT, null, "MEMBERS").get(quarry.getObjectId())); target = quarry;
						field(EVENT, "running").setBoolean(null, true);
						check(!X10World.pveAllies(healer, 900).contains(quarry) && X10World.pveAllies(quarry, 900).equals(List.of(quarry)), "hunt sides separate support");
						check(X10World.pveTarget(quarry) != quarry && X10World.pveEnemy(quarry, owner), "quarry retaliates only against pursuers");
					}
					else
					{
						call(EVENT, null, "spawn", new Class<?>[] {ROOM}, room);
						target = ((List<Spawn>) field(ROOM, "spawns").get(room)).getFirst().getLastSpawn(); target.disableCoreAI(true); target.setImmobilized(true);
						target.setXYZ(hunter.getX() + 40, hunter.getY(), hunter.getZ());

						field(EVENT, "running").setBoolean(null, true);
					}
					check(X10World.pveTarget(hunter) == target && !X10World.pveEnemy(hunter, outsider) && !X10World.pveEnemy(hunter, owner), kind + " targets isolated");
				}
				check(PersonalCompanions.owned(healer) && PersonalCompanions.readyForAction(healer, System.currentTimeMillis()), kind + " personal helper ready");
				healer.addSkill(SkillData.getInstance().getSkill(1011, 3), false); owner.setCurrentHp(1); owner.getStatus().stopHpMpRegeneration();
				await(() -> owner.getCurrentHp() > 10, () -> personalTick(helper), kind + " companion heals owner");
				healer.abortCast(); owner.fullRestore(); owner.setCurrentMp(1); owner.getStatus().stopHpMpRegeneration(); healer.fullRestore();
				healer.addSkill(SkillData.getInstance().getSkill(1013, 1), false);
				await(() -> owner.getCurrentMp() > 10, () -> personalTick(helper), kind + " companion recharges owner");
				healer.abortCast(); owner.fullRestore(); hunter.setCurrentHp(1); hunter.getStatus().stopHpMpRegeneration();
				check(!hunter.isInParty(), "ally outside healer party");
				await(() -> hunter.getCurrentHp() > 10, () -> personalTick(helper), kind + " heals ally outside party");
				healer.abortCast(); target.setCurrentHp(target.getMaxHp()); final double hp = target.getCurrentHp();
				await(() -> target.getCurrentHp() < hp, () -> hunterTick(hunter, hunterTemplate), kind + " hunter AI damages target"); hunter.abortAttack();
				for (boolean auto : new boolean[] {false, true})
				{
					for (int id : new int[] {1539, 728, 5592})
					{
						final Player drinker = player("X10Pot" + -nextId, 0); enter(drinker, room, 100); potion(drinker, id, auto);
					}
				}
				field(ROOM, "closed").setBoolean(room, true); field(ROOM, "closed").setBoolean(otherRoom, true);
				check(!X10World.inPveEvent(owner) && X10World.pveTarget(hunter) == null, "closed room rejects targets");
				synchronized (EVENT)
				{
					field(EVENT, "running").setBoolean(null, false);
					((List<Object>) field(EVENT, "ROOMS").get(null)).clear();
					map(EVENT, null, "MEMBERS").clear();
					for (Player p : PLAYERS)
					{
						p.abortAttack(); p.abortCast(); p.setOnlineStatus(false, false);
						map(PhantomCompanionManager.class, PhantomCompanionManager.getInstance(), "_companions").remove(p.getName().toLowerCase());
						map(PhantomCompanionManager.class, null, "PACES").remove(p.getObjectId());
					}
				}
			}
			final Player world = player("X10World" + -nextId, 0); world.spawnMe(83400, 147900, -3400);
			potion(world, 728, false);
			final Player pvp = player("X10Pvp" + -nextId, 0); pvp.setOnEvent(true); pvp.spawnMe(83400, 147900, -3400);
			final Item item = pvp.addItem(ItemProcessType.REWARD, 728, 2, pvp, false);
			check(!ItemHandler.getInstance().getHandler(item.getEtcItem()).onItemUse(pvp, item, false) && item.getCount() == 2, "PvP event potion prohibition retained");
			Files.writeString(Path.of("log/x10-combat-smoke.json"), "{\"passed\":true,\"checks\":" + CHECKS.size() + ",\"formats\":3}");
			LOGGER.info("X10 combat smoke: PASSED (" + CHECKS.size() + " checks)");
		}
		catch (Throwable e)
		{
			LOGGER.log(Level.SEVERE, "X10 combat smoke: FAILED", e);
			try { Files.writeString(Path.of("log/x10-combat-smoke.json"), "{\"passed\":false,\"checks\":" + CHECKS.size() + "}"); }
			catch (Exception writeError) { LOGGER.log(Level.SEVERE, "Cannot write smoke result", writeError); }
		}
		finally
		{
			try
			{
				if (prepared) { synchronized (EVENT) { field(EVENT, "running").setBoolean(null, false); ((List<Object>) field(EVENT, "ROOMS").get(null)).clear(); } }
				for (Object room : ROOMS)
				{
					field(ROOM, "closed").setBoolean(room, true);
					for (Spawn spawn : (List<Spawn>) field(ROOM, "spawns").get(room)) { spawn.stopRespawn(); if (spawn.getLastSpawn() != null) { map(EVENT, null, "HEALTH").remove(spawn.getLastSpawn().getObjectId()); spawn.getLastSpawn().deleteMe(); } }
					InstanceManager.getInstance().destroyInstance(((Instance) field(ROOM, "instance").get(room)).getId());
				}
				for (Player p : PLAYERS)
				{
					p.abortAttack(); p.abortCast(); p.setTarget(null); p.setOnlineStatus(false, false); p.stopVitalityTask();
					AutoUseTaskManager.getInstance().stopAutoUseTask(p); p.getStatus().stopHpMpRegeneration();
					p.getInventory().destroyAllItems(ItemProcessType.DESTROY, p, null);
					map(EVENT, null, "MEMBERS").remove(p.getObjectId());
					map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").remove(p.getObjectId());
					map(PhantomCompanionManager.class, PhantomCompanionManager.getInstance(), "_companions").remove(p.getName().toLowerCase());
					p.setInstanceId(0); p.decayMe(); World.removeObject(p);
				}
				if (prepared)
				{
					map(EVENT, null, "MOBS").entrySet().removeIf(e -> ROOMS.contains(e.getValue()));
					((List<Object>) field(EVENT, "ROOMS").get(null)).clear();
					field(EVENT, "started").setLong(null, oldStarted); field(EVENT, "nextStart").setLong(null, oldNextStart);
					field(EVENT, "kind").set(null, oldKind); field(PhantomData.class, "_templates").set(PhantomData.getInstance(), oldTemplates);
				}
			}
			catch (Exception e) { LOGGER.log(Level.SEVERE, "X10 combat smoke cleanup failed", e); }
		}
	}
}
