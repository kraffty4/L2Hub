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

import org.l2jmobius.commons.database.DatabaseFactory;
import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.L2HubX10Config;
import org.l2jmobius.gameserver.data.xml.PlayerTemplateData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.appearance.PlayerAppearance;
import org.l2jmobius.gameserver.entity.actor.templates.PlayerTemplate;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.instancezone.Instance;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.spawns.Spawn;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.managers.IdManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomTemplate;
import org.l2jmobius.gameserver.managers.world.X10World;
import org.l2jmobius.gameserver.mechanics.variables.PlayerVariables;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;

/** Только отдельная тестовая БД и явный маркер. Настоящие волны, Player, телепорт и ledger. */
public final class X10DefenceSmoke
{
	private static final Logger LOGGER = Logger.getLogger(X10DefenceSmoke.class.getName());
	private static final String ACCOUNT = "x10-defence-smoke";
	private static final List<Player> PLAYERS = new ArrayList<>();
	private static final List<Integer> CREATED_IDS = new ArrayList<>();
	private static final List<Object> ROOMS = new ArrayList<>();
	private static final Class<?> EVENT = type("org.l2jmobius.gameserver.managers.world.X10FarmEvent");
	private static final Class<?> ROOM = type(EVENT.getName() + "$Room");
	private static final Class<?> MEMBER = type(EVENT.getName() + "$Member");
	private static final Class<?> KIND = type("org.l2jmobius.gameserver.managers.world.X10EventRules$Kind");
	private static int checks;

	public static class MemoryVariables extends PlayerVariables
	{
		public MemoryVariables(int id) { super(id); }
		@Override public boolean restoreMe() { return true; }
		@Override public boolean storeMe() { return true; }
		@Override public boolean saveNow() { return true; }
	}
	public static void main(String[] args) { ThreadPool.schedule(X10DefenceSmoke::run, 45000); }
	private static Class<?> type(String name)
	{
		try { return Class.forName(name); } catch (Exception e) { throw new IllegalStateException(e); }
	}
	private static Field field(Class<?> cls, String name) throws Exception
	{
		final Field f = cls.getDeclaredField(name); f.setAccessible(true); return f;
	}
	private static Object call(Class<?> cls, Object receiver, String name, Class<?>[] types, Object... args) throws Exception
	{
		final Method m = cls.getDeclaredMethod(name, types); m.setAccessible(true); return m.invoke(receiver, args);
	}
	@SuppressWarnings("unchecked")
	private static <K, V> Map<K, V> map(Class<?> cls, Object receiver, String name) throws Exception { return (Map<K, V>) field(cls, name).get(receiver); }
	private static void check(boolean value, String name)
	{
		if (!value) { throw new AssertionError(name); } checks++; LOGGER.info("X10 defence smoke: OK " + name);
	}
	private static Player player(int level) throws Exception
	{
		final int id = IdManager.getInstance().getNextId();
		// Минимальная строка нужна только для FK настоящего кошелька, чужие персонажи не используются.
		try (var con = DatabaseFactory.getConnection(); var st = con.prepareStatement("INSERT INTO characters (charId, char_name, account_name) VALUES (?, ?, ?)"))
		{
			st.setInt(1, id); st.setString(2, "X10Def" + id); st.setString(3, ACCOUNT); st.executeUpdate();
			CREATED_IDS.add(id);
		}
		final Constructor<Player> ctor = Player.class.getDeclaredConstructor(int.class, PlayerTemplate.class, String.class, PlayerAppearance.class);
		ctor.setAccessible(true);
		final Player p = ctor.newInstance(id, PlayerTemplateData.getInstance().getTemplate(0), ACCOUNT, new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
		PLAYERS.add(p); p.setName("X10Def" + id);
		final MemoryVariables variables = new MemoryVariables(id); p.addScript(variables);
		map(org.l2jmobius.gameserver.entity.WorldObject.class, p, "_scripts").put(PlayerVariables.class.getName(), variables);
		p.getStat().setLevel((byte) level); p.setOnlineStatus(true, false); p.fullRestore();
		p.spawnMe(83400, 147900, -3400); return p;
	}
	private static void teleported(Player p) { if (p.isTeleporting()) { p.onTeleported(); } }
	private static Object room(Player p) throws Exception { return field(MEMBER, "room").get(map(EVENT, null, "MEMBERS").get(p.getObjectId())); }
	private static void tick(Object room) throws Exception { call(EVENT, null, "defenceTick", new Class<?>[] {ROOM, long.class}, room, System.currentTimeMillis()); }
	private static void spawnWave(Object room, int modifier) throws Exception
	{
		field(ROOM, "modifier").setInt(room, modifier); field(ROOM, "nextWave").setLong(room, 0); tick(room);
	}
	private static List<Attackable> mobs(Object room) throws Exception
	{
		final List<Attackable> result = new ArrayList<>();
		for (var entry : X10DefenceSmoke.<Integer, Object>map(EVENT, null, "MOBS").entrySet())
		{
			if (entry.getValue() == room && World.findObject(entry.getKey()) instanceof Attackable npc)
			{
				npc.disableCoreAI(true); npc.setImmobilized(true); result.add(npc);
			}
		}
		return result;
	}
	private static void clearWave(Object room, Player killer) throws Exception
	{
		for (Attackable npc : mobs(room))
		{
			npc.doDie(killer);
			call(EVENT, null, "kill", new Class<?>[] {Player.class, Attackable.class}, killer, npc);
		}
		tick(room);
	}
	private static long[] wallet(Player p) throws Exception
	{
		try (var con = DatabaseFactory.getConnection(); var st = con.prepareStatement("SELECT coins, (SELECT COUNT(*) FROM l2hub_x10_ledger WHERE char_id=?) FROM l2hub_x10_wallet WHERE char_id=?"))
		{
			st.setInt(1, p.getObjectId()); st.setInt(2, p.getObjectId());
			try (var rs = st.executeQuery()) { return rs.next() ? new long[] {rs.getLong(1), rs.getLong(2)} : new long[] {0, 0}; }
		}
	}
	@SuppressWarnings("unchecked")
	private static void run()
	{
		boolean prepared = false;
		final Map<Field, Object> saved = new java.util.HashMap<>();
		try
		{
			if (!Files.isRegularFile(Path.of("log/x10-defence-smoke.allow"))) { throw new IllegalStateException("Explicit test marker required"); }
			try (var con = DatabaseFactory.getConnection(); var st = con.createStatement(); var rs = st.executeQuery("SELECT DATABASE()"))
			{
				check(rs.next() && "l2hub_hf_test".equals(rs.getString(1)), "test database only");
			}
			synchronized (EVENT)
			{
				check(!field(EVENT, "running").getBoolean(null) && map(EVENT, null, "QUEUE").isEmpty() && map(EVENT, null, "MEMBERS").isEmpty(), "no live event or sign-ups");
				check(World.getPlayers().stream().noneMatch(p -> p.isOnline() && p.getClient() != null), "no connected clients");
				for (String name : List.of("kind", "nextKind", "nextStart", "started", "sequence", "defenceGate")) { final Field f = field(EVENT, name); saved.put(f, f.get(null)); }
				for (String name : List.of("EVENT_HUNTERS", "EVENTS_ENABLED", "DEFENCE_MODIFIERS", "DEFENCE_WAVES", "DEFENCE_WAVE_COINS", "DEFENCE_COIN_GROWTH")) { final Field f = field(L2HubX10Config.class, name); saved.put(f, f.get(null)); }
				prepared = true;
				L2HubX10Config.EVENT_HUNTERS = false; L2HubX10Config.EVENTS_ENABLED = true; L2HubX10Config.DEFENCE_MODIFIERS = false;
				L2HubX10Config.DEFENCE_WAVES = 10; L2HubX10Config.DEFENCE_WAVE_COINS = 5; L2HubX10Config.DEFENCE_COIN_GROWTH = 5;
				final Object defence = java.util.Arrays.stream(KIND.getEnumConstants()).filter(k -> k.toString().equals("DEFENCE")).findFirst().orElseThrow();
				field(EVENT, "kind").set(null, defence); field(EVENT, "nextKind").set(null, defence); field(EVENT, "nextStart").setLong(null, System.currentTimeMillis() + 60000);
				final Player leader = player(40), ally = player(50), helper = player(45), solo = player(40), stay = player(40);
				final Player single1 = player(30), single2 = player(32);
				leader.setParty(new Party(leader, PartyDistributionType.FINDERS_KEEPERS)); ally.joinParty(leader.getParty()); helper.joinParty(leader.getParty());
				final Party original = leader.getParty();
				map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").put(helper.getObjectId(), new PhantomTemplate(helper.getName(), PhantomTemplate.Role.HEALER, false, (byte) 0, (byte) 0, (byte) 0, new int[] {0}, new Location(0, 0, 0), List.of(), "", "companion", 0));
				solo.setParty(new Party(solo, PartyDistributionType.FINDERS_KEEPERS)); stay.joinParty(solo.getParty()); final Party outside = solo.getParty();
				call(EVENT, null, "action", new Class<?>[] {Player.class, String.class, KIND}, leader, "joinparty", defence);
				for (Player p : List.of(solo, single1, single2)) { call(EVENT, null, "action", new Class<?>[] {Player.class, String.class, KIND}, p, "join", defence); }
				call(EVENT, null, "begin", new Class<?>[0]);
				ROOMS.addAll((List<Object>) field(EVENT, "ROOMS").get(null)); PLAYERS.forEach(X10DefenceSmoke::teleported);
				check(ROOMS.size() == 3, "party, solo-in-party and singles receive separate copies");
				final Object room = room(leader), soloRoom = room(solo), singles = room(single1);
				check(room(ally) == room && room(helper) == room && leader.getParty() == original && helper.getParty() == original, "explicit party and companion preserved");
				check(solo.getParty() == outside && !stay.isOnEvent() && stay.getInstanceId() == 0 && !map(EVENT, null, "MEMBERS").containsKey(stay.getObjectId()), "personal entry keeps world party and brings nobody else");
				check(room(single2) == singles && single1.getParty() == single2.getParty(), "ungrouped singles form temporary party");
				check(field(ROOM, "level").getInt(room) == 45, "average level of entering party");
				check(!leader.isInsideZone(ZoneId.PEACE) && !solo.isInsideZone(ZoneId.PEACE), "combat is outside town peace zone");
				spawnWave(room, 0); final List<Attackable> first = mobs(room); final int count = first.size(); final double hp = first.getFirst().getMaxHp();
				check(first.stream().allMatch(n -> n.getLevel() == 35), "wave one is average minus ten");
				check(X10World.pveEnemy(helper, first.getFirst()) && !X10World.pveEnemy(solo, first.getFirst()), "bot targets only its own copy");
				clearWave(room, leader); spawnWave(room, 4);
				check(mobs(room).size() > count && mobs(room).getFirst().getLevel() == 38 && mobs(room).getFirst().getMaxHp() > hp, "second wave grows in count level and HP");
				clearWave(room, leader);
				final Object deadMember = map(EVENT, null, "MEMBERS").get(leader.getObjectId());
				check(field(MEMBER, "waveCoins").getLong(deadMember) == 25, "5 plus doubled 10 coins");
				leader.doDie(ally); tick(room); leader.doRevive();
				check(leader.isDead() && X10World.defenceEliminated(leader), "death eliminates and resurrection cannot restore participation");
				spawnWave(room, 2);
				check(mobs(room).stream().anyMatch(n -> n.getKnownSkill(4001) != null) && mobs(room).stream().anyMatch(n -> n.getPhysicalAttackRange() >= 600), "ranged wave has casters and archers");
				clearWave(room, ally); spawnWave(room, 3); clearWave(room, ally); spawnWave(room, 1);
				check(mobs(room).stream().filter(n -> n.getVariables().getBoolean("L2HubX10MiniBoss", false)).count() == 1, "fifth wave has exactly one mini-boss");
				clearWave(room, ally);
				check(field(MEMBER, "waveCoins").getLong(deadMember) == 25 && field(MEMBER, "completedWaves").getInt(deadMember) == 2, "death freezes already earned waves and coins");
				ally.doDie(null); helper.doDie(null); tick(room); PLAYERS.forEach(X10DefenceSmoke::teleported);
				check(field(ROOM, "closed").getBoolean(room) && !field(ROOM, "closed").getBoolean(soloRoom), "wipe closes only that party's instance");
				check(List.of(leader, ally, helper).stream().allMatch(p -> !p.isOnEvent() && !p.isDead() && p.getInstanceId() == 0), "whole party returns together and revives");
				check(leader.getParty() == original && helper.getParty() == original, "original party survives return");
				final long[] paid = wallet(leader); check(paid[0] == 25 && paid[1] == 1, "earned coins credited once to real ledger");
				call(EVENT, null, "finishDefenceRoom", new Class<?>[] {ROOM, boolean.class, boolean.class}, room, true, false);
				call(EVENT, null, "recover", new Class<?>[] {Player.class}, leader);
				check(java.util.Arrays.equals(wallet(leader), paid), "repeat finish and recover cannot pay twice");
				call(EVENT, null, "finishDefenceRoom", new Class<?>[] {ROOM, boolean.class, boolean.class}, singles, true, false);
				check(!single1.isInParty() && !single2.isInParty(), "temporary party dissolves");
				call(EVENT, null, "finishDefenceRoom", new Class<?>[] {ROOM, boolean.class, boolean.class}, soloRoom, true, true);
				check(solo.getParty() == outside && stay.getParty() == outside, "solo keeps original world party at end");
			}
			Files.writeString(Path.of("log/x10-defence-smoke.json"), "{\"passed\":true,\"checks\":" + checks + "}");
			LOGGER.info("X10 defence smoke: PASSED (" + checks + " checks)");
		}
		catch (Throwable e)
		{
			LOGGER.log(Level.SEVERE, "X10 defence smoke: FAILED", e);
			try { Files.writeString(Path.of("log/x10-defence-smoke.json"), "{\"passed\":false,\"checks\":" + checks + "}"); } catch (Exception ignored) { }
		}
		finally
		{
			try
			{
				synchronized (EVENT)
				{
					if (prepared) { field(EVENT, "running").setBoolean(null, false); }
					for (Object room : ROOMS)
					{
						field(ROOM, "closed").setBoolean(room, true);
						for (Spawn spawn : (List<Spawn>) field(ROOM, "spawns").get(room))
						{
							spawn.stopRespawn();
							if (spawn.getLastSpawn() != null) { map(EVENT, null, "MOBS").remove(spawn.getLastSpawn().getObjectId()); map(EVENT, null, "HEALTH").remove(spawn.getLastSpawn().getObjectId()); spawn.getLastSpawn().deleteMe(); }
						}
						InstanceManager.getInstance().destroyInstance(((Instance) field(ROOM, "instance").get(room)).getId());
					}
					for (Player p : PLAYERS)
					{
						p.abortAttack(); p.abortCast(); p.setTarget(null); p.stopVitalityTask(); p.getStatus().stopHpMpRegeneration(); AutoUseTaskManager.getInstance().stopAutoUseTask(p);
						if (p.isInParty()) { p.leaveParty(); }
						p.getInventory().destroyAllItems(ItemProcessType.DESTROY, p, null); p.setOnlineStatus(false, false);
						map(EVENT, null, "MEMBERS").remove(p.getObjectId()); map(EVENT, null, "QUEUE").remove(p.getObjectId()); map(EVENT, null, "PARTY_QUEUE").remove(p.getObjectId());
						map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").remove(p.getObjectId());
						p.setInstanceId(0); p.decayMe(); World.removeObject(p);
					}
					if (prepared)
					{
						((List<Object>) field(EVENT, "ROOMS").get(null)).clear();
						for (var entry : saved.entrySet()) { entry.getKey().set(null, entry.getValue()); }
					}
					// Убираются и строки, для которых конструктор Player не успел завершиться.
					try (var con = DatabaseFactory.getConnection(); var st = con.prepareStatement("DELETE FROM characters WHERE charId=? AND account_name=?"))
					{
						for (int id : CREATED_IDS) { st.setInt(1, id); st.setString(2, ACCOUNT); st.addBatch(); }
						st.executeBatch();
					}
				}
			}
			catch (Exception e) { LOGGER.log(Level.SEVERE, "X10 defence smoke cleanup failed", e); }
		}
	}
}
