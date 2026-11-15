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

/** Только тестовая БД и явный маркер: настоящий счёт, хербы, особые NPC, телепорт и ledger. */
public final class X10FarmSmoke
{
	private static final Logger LOGGER = Logger.getLogger(X10FarmSmoke.class.getName());
	private static final String ACCOUNT = "x10-farm-smoke";
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
	public static void main(String[] args) { ThreadPool.schedule(X10FarmSmoke::run, 45000); }
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
		if (!value) { throw new AssertionError(name); } checks++; LOGGER.info("X10 farm smoke: OK " + name);
	}
	private static Player player(int level) throws Exception
	{
		final int id = IdManager.getInstance().getNextId();
		// Минимальная строка нужна только для FK настоящего кошелька, чужие персонажи не используются.
		try (var con = DatabaseFactory.getConnection(); var st = con.prepareStatement("INSERT INTO characters (charId, char_name, account_name) VALUES (?, ?, ?)"))
		{
			st.setInt(1, id); st.setString(2, "X10Farm" + id); st.setString(3, ACCOUNT); st.executeUpdate();
			CREATED_IDS.add(id);
		}
		final Constructor<Player> ctor = Player.class.getDeclaredConstructor(int.class, PlayerTemplate.class, String.class, PlayerAppearance.class);
		ctor.setAccessible(true);
		final Player p = ctor.newInstance(id, PlayerTemplateData.getInstance().getTemplate(0), ACCOUNT, new PlayerAppearance((byte) 0, (byte) 0, (byte) 0, false));
		PLAYERS.add(p); p.setName("X10Farm" + id);
		final MemoryVariables variables = new MemoryVariables(id); p.addScript(variables);
		map(org.l2jmobius.gameserver.entity.WorldObject.class, p, "_scripts").put(PlayerVariables.class.getName(), variables);
		p.getStat().setLevel((byte) level); p.setOnlineStatus(true, false); p.fullRestore();
		p.spawnMe(83400, 147900, -3400); return p;
	}
	private static void teleported(Player p) { if (p.isTeleporting()) { p.onTeleported(); } }
	private static Object room(Player p) throws Exception { return field(MEMBER, "room").get(map(EVENT, null, "MEMBERS").get(p.getObjectId())); }
	private static List<Attackable> mobs(Object room) throws Exception
	{
		final List<Attackable> result = new ArrayList<>();
		for (var entry : X10FarmSmoke.<Integer, Object>map(EVENT, null, "MOBS").entrySet())
		{
			if (entry.getValue() == room && World.findObject(entry.getKey()) instanceof Attackable npc)
			{
				npc.disableCoreAI(true); npc.setImmobilized(true); result.add(npc);
			}
		}
		return result;
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
		final Map<String, Object> globals = new java.util.HashMap<>();
		try
		{
			if (!Files.isRegularFile(Path.of("log/x10-farm-smoke.allow"))) { throw new IllegalStateException("Explicit test marker required"); }
			try (var con = DatabaseFactory.getConnection(); var st = con.createStatement(); var rs = st.executeQuery("SELECT DATABASE()"))
			{
				check(rs.next() && "l2hub_hf_test".equals(rs.getString(1)), "test database only");
			}
			synchronized (EVENT)
			{
				check(!field(EVENT, "running").getBoolean(null) && map(EVENT, null, "QUEUE").isEmpty() && map(EVENT, null, "MEMBERS").isEmpty(), "no live event or sign-ups");
				check(World.getPlayers().stream().noneMatch(p -> p.isOnline() && p.getClient() != null), "no connected clients");
				for (String name : List.of("kind", "nextKind", "nextStart", "started", "sequence", "defenceGate", "schedule", "testStart", "warnedStart", "warnedSeconds")) { final Field f = field(EVENT, name); saved.put(f, f.get(null)); }
				for (String name : List.of("EVENT_HUNTERS", "EVENTS_ENABLED", "FARM_GOLDEN", "FARM_HERBS", "FARM_HERB_CHANCE", "FARM_BOSS", "FARM_ROOM", "FARM_MOBS")) { final Field f = field(L2HubX10Config.class, name); saved.put(f, f.get(null)); }
				final var vars = org.l2jmobius.gameserver.managers.GlobalVariablesManager.getInstance();
				for (String key : List.of("L2HubX10EventSequence", "L2HubX10EventSchedule", "L2HubX10FarmArena1", "L2HubX10FarmArena40", "L2HubX10FarmArena65")) { globals.put(key,vars.getSet().get(key)); }
				final Object schedule = field(EVENT,"schedule").get(null);
				final Constructor<?> clone = schedule.getClass().getDeclaredConstructor(schedule.getClass());clone.setAccessible(true);
				field(EVENT,"schedule").set(null,clone.newInstance(schedule));
				prepared = true;
				L2HubX10Config.EVENT_HUNTERS = false; L2HubX10Config.EVENTS_ENABLED = true; L2HubX10Config.FARM_GOLDEN = true; L2HubX10Config.FARM_HERBS = true; L2HubX10Config.FARM_HERB_CHANCE = 100; L2HubX10Config.FARM_BOSS = true; L2HubX10Config.FARM_ROOM = 18; L2HubX10Config.FARM_MOBS = 3;
				
				final Object farm = java.util.Arrays.stream(KIND.getEnumConstants()).filter(k -> k.toString().equals("FARM")).findFirst().orElseThrow();
				field(EVENT, "kind").set(null, farm); field(EVENT, "nextKind").set(null, farm); field(EVENT, "nextStart").setLong(null, System.currentTimeMillis() + 60000);
				final Player leader = player(40), ally = player(50), helper = player(45), solo = player(40), stay = player(40);
				final Player single1 = player(30), single2 = player(32);
				leader.setParty(new Party(leader, PartyDistributionType.FINDERS_KEEPERS)); ally.joinParty(leader.getParty()); helper.joinParty(leader.getParty());
				final Party original = leader.getParty();
				map(PhantomManager.class, PhantomManager.getInstance(), "_phantoms").put(helper.getObjectId(), new PhantomTemplate(helper.getName(), PhantomTemplate.Role.HEALER, false, (byte) 0, (byte) 0, (byte) 0, new int[] {0}, new Location(0, 0, 0), List.of(), "", "companion", 0));
				solo.setParty(new Party(solo, PartyDistributionType.FINDERS_KEEPERS)); stay.joinParty(solo.getParty()); final Party outside = solo.getParty();
				call(EVENT, null, "action", new Class<?>[] {Player.class, String.class, KIND}, leader, "joinparty", farm);
				for (Player p : List.of(solo, single1, single2)) { call(EVENT, null, "action", new Class<?>[] {Player.class, String.class, KIND}, p, "join", farm); }
				call(EVENT, null, "begin", new Class<?>[0]);
				ROOMS.addAll((List<Object>) field(EVENT, "ROOMS").get(null)); PLAYERS.forEach(X10FarmSmoke::teleported);
				final Object room = room(leader), singles = room(single1);
				check(room(ally)==room && room(helper)==room && leader.getParty()==original, "leader sign-up includes the full original party and companion");
				check(solo.getParty()==outside && !stay.isOnEvent() && stay.getInstanceId()==0, "personal sign-up brings only that member and preserves world party");
				check(single1.getParty()==single2.getParty(), "ungrouped singles form a temporary party");
				check(!leader.isInsideZone(ZoneId.PEACE), "arena is outside peace zone");
				final Object member=map(EVENT,null,"MEMBERS").get(leader.getObjectId());
				final Object allyMember=map(EVENT,null,"MEMBERS").get(ally.getObjectId());
				final int squad=field(MEMBER,"squad").getInt(member);
				check(squad==field(MEMBER,"squad").getInt(allyMember), "party has one frozen score identity");
				final int beforeKills=(Integer)call(EVENT,null,"squadKills",new Class<?>[]{ROOM,int.class},room,squad);
				final Attackable normal=mobs(room).getFirst();
				normal.doDie(leader); call(EVENT,null,"kill",new Class<?>[]{Player.class,Attackable.class},leader,normal);
				check((Integer)call(EVENT,null,"squadKills",new Class<?>[]{ROOM,int.class},room,squad)==beforeKills+1, "real kill increments party score exactly once");
				check((Integer)call(EVENT,null,"squadRank",new Class<?>[]{ROOM,int.class},room,squad)==1, "leading party gets first place");
				call(EVENT,null,"clearHerbs",new Class<?>[]{ROOM},room);
				// Каждый тип берётся настоящим Player.doPickupItem через врезку до штатного herb handler.
				for(int type=0;type<3;type++)
				{
					boolean obtained=false;
					for(int attempt=0;attempt<100 && !obtained;attempt++)
					{
						call(EVENT,null,"dropHerb",new Class<?>[]{ROOM,Attackable.class},room,normal);
						final Object herb=map(ROOM,room,"herbs").values().iterator().next();
						final Class<?> hc=herb.getClass(); final org.l2jmobius.gameserver.entity.item.instance.Item item=(org.l2jmobius.gameserver.entity.item.instance.Item)call(hc,herb,"item",new Class<?>[0]);
						final int boost=(Integer)call(hc,herb,"boost",new Class<?>[0]);
						if(boost==type)
						{
							leader.setXYZ(item.getX(),item.getY(),item.getZ()); leader.doPickupItem(item); obtained=true;
							check(((long[])field(MEMBER,"boosts").get(member))[type]>System.currentTimeMillis(), "herb pickup activates booster " + type);
							check(World.findObject(item.getObjectId())==null && leader.getInventory().getItemByObjectId(item.getObjectId())==null, "herb is consumed, never stored");
						}
						call(EVENT,null,"clearHerbs",new Class<?>[]{ROOM},room);
					}
					check(obtained,"all herb types available");
				}
				check(leader.getEffectList().isAffectedBySkill(2285),"stock speed skill and icon applied");
				final Attackable victim=mobs(room).getFirst(); victim.setXYZ(leader.getX()+30,leader.getY(),leader.getZ());
				check(X10World.farmDamage(leader,victim,10,false)==20,"double damage only against own event NPC");
				check(X10World.farmDamage(leader,stay,10,false)==10,"booster cannot amplify PvP or outside targets");
				call(EVENT,null,"spawn",new Class<?>[]{ROOM},room);
				final Attackable splash=mobs(room).stream().filter(n -> n!=victim && !n.isDead()).findFirst().orElseThrow();
				splash.setXYZ(leader.getX()+40,leader.getY(),leader.getZ());
				field(MEMBER,"nextSplash").setLong(member,0);
				final double hp=victim.getCurrentHp(), splashHp=splash.getCurrentHp();
				victim.reduceCurrentHp(1,leader,null);
				check(Math.abs(hp-victim.getCurrentHp()-2)<0.01,"native HP pipeline applies exactly double damage");
				check(splash.getCurrentHp()<splashHp,"native attack splashes to nearby arena enemy");
				((long[])field(MEMBER,"boosts").get(member))[0]=System.currentTimeMillis()-1;
				check(X10World.farmDamage(leader,victim,10,false)==10,"expired booster stops damage amplification");
				call(EVENT,null,"clearBoosts",new Class<?>[]{MEMBER},member);
				check(!leader.getEffectList().isAffectedBySkill(2285),"arena-owned speed effect removed");
				field(ROOM,"nextGolden").setLong(room,0); call(EVENT,null,"farmTick",new Class<?>[]{ROOM,long.class},room,System.currentTimeMillis());
				final int goldId=field(ROOM,"goldenId").getInt(room);
				check(World.findObject(goldId) instanceof Attackable,"golden target appears");
				final Attackable gold=(Attackable)World.findObject(goldId); gold.disableCoreAI(true);
				check(X10World.pveTarget(helper)==gold,"hunter/companion AI prioritizes golden target");
				gold.doDie(leader);call(EVENT,null,"kill",new Class<?>[]{Player.class,Attackable.class},leader,gold);
				final long[] paid=wallet(leader);
				check(paid[0]>0 && paid[1]==1,"golden reward uses real ledger");
				call(EVENT,null,"kill",new Class<?>[]{Player.class,Attackable.class},leader,gold);
				check(java.util.Arrays.equals(paid,wallet(leader)),"golden reward cannot be duplicated");
				final long duration=L2HubX10Config.FARM_EVENT.minutes()*60000L;
				field(EVENT,"started").setLong(null,System.currentTimeMillis()-duration+61000);
				call(EVENT,null,"farmTick",new Class<?>[]{ROOM,long.class},room,System.currentTimeMillis());
				check(!field(ROOM,"bossSpawned").getBoolean(room),"boss absent before final minute");
				field(EVENT,"started").setLong(null,System.currentTimeMillis()-duration+59000);
				call(EVENT,null,"farmTick",new Class<?>[]{ROOM,long.class},room,System.currentTimeMillis());
				final Attackable boss=(Attackable)World.findObject(field(ROOM,"bossId").getInt(room));
				check(boss!=null && boss.getLevel()==field(ROOM,"level").getInt(room),"boss arrives in final minute at arena level");
				
				check(X10World.pveTarget(helper)==boss,"bot AI prioritizes final boss");
				boss.doDie(leader);call(EVENT,null,"kill",new Class<?>[]{Player.class,Attackable.class},leader,boss);
				check(field(ROOM,"bossKilled").getBoolean(room),"boss death unlocks room bonus");
				// Все окна активности, чтобы отдельно проверить базу, фонд места и полный итог.
				for(Player p:PLAYERS)
				{
					final Object mm=map(EVENT,null,"MEMBERS").get(p.getObjectId());if(mm==null)continue;
					final Object score=field(MEMBER,"score").get(mm);
					for(long time=0;time<duration;time+=30000)call(score.getClass(),score,"active",new Class<?>[]{long.class},time);
				}
				final Map<Integer,Long> withBoss=(Map<Integer,Long>)call(EVENT,null,"farmRewards",new Class<?>[]{ROOM,long.class},room,duration);
				field(ROOM,"bossKilled").setBoolean(room,false);
				final Map<Integer,Long> withoutBoss=(Map<Integer,Long>)call(EVENT,null,"farmRewards",new Class<?>[]{ROOM,long.class},room,duration);
				check(withBoss.get(leader.getObjectId())-withoutBoss.get(leader.getObjectId())==L2HubX10Config.FARM_BOSS_COINS,"bonus only for killed boss");
				field(ROOM,"bossKilled").setBoolean(room,true);
				final Class<?> arenas=type("org.l2jmobius.gameserver.managers.world.X10FarmArenas");
				final Object a=call(arenas,null,"choose",new Class<?>[]{int.class},40),b=call(arenas,null,"choose",new Class<?>[]{int.class},40);
				check(!a.equals(b),"successive arenas differ");
				field(EVENT,"started").setLong(null,System.currentTimeMillis()-duration);
				call(EVENT,null,"finish",new Class<?>[]{boolean.class},true);PLAYERS.forEach(X10FarmSmoke::teleported);
				final long[] finished=wallet(leader);check(finished[0]==paid[0]+withBoss.get(leader.getObjectId()),"final base placement and boss credit match expected ledger");
				call(EVENT,null,"finish",new Class<?>[]{boolean.class},true);call(EVENT,null,"recover",new Class<?>[]{Player.class},leader);
				check(java.util.Arrays.equals(finished,wallet(leader)),"repeat finish cannot pay twice");
				check(leader.getParty()==original && solo.getParty()==outside && !single1.isInParty(),"original parties preserved and temporary parties dissolved");

			}
			Files.writeString(Path.of("log/x10-farm-smoke.json"), "{\"passed\":true,\"checks\":" + checks + "}");
			LOGGER.info("X10 farm smoke: PASSED (" + checks + " checks)");
		}
		catch (Throwable e)
		{
			LOGGER.log(Level.SEVERE, "X10 farm smoke: FAILED", e);
			try { Files.writeString(Path.of("log/x10-farm-smoke.json"), "{\"passed\":false,\"checks\":" + checks + "}"); } catch (Exception ignored) { }
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
						call(EVENT,null,"clearHerbs",new Class<?>[]{ROOM},room);
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
						final var vars = org.l2jmobius.gameserver.managers.GlobalVariablesManager.getInstance();
						for (var entry : globals.entrySet()) { if (entry.getValue()==null) { vars.remove(entry.getKey()); } else { vars.set(entry.getKey(),entry.getValue()); } }
						vars.storeMe();
					}
					// Убираются и строки, для которых конструктор Player не успел завершиться.
					try (var con = DatabaseFactory.getConnection(); var st = con.prepareStatement("DELETE FROM characters WHERE charId=? AND account_name=?"))
					{
						for (int id : CREATED_IDS) { st.setInt(1, id); st.setString(2, ACCOUNT); st.addBatch(); }
						st.executeBatch();
					}
				}
			}
			catch (Exception e) { LOGGER.log(Level.SEVERE, "X10 farm smoke cleanup failed", e); }
		}
	}
}
