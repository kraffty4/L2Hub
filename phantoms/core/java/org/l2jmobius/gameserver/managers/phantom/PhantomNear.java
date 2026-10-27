package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PrivateStoreType;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterStrategy.Choice;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.PrivateStoreMsgSell;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: «Разум 2.0», шаг 6 — охотник в фокусе ведёт себя как человек, когда рядом живой игрок (docs/MIND.md, раздел 6; протокол — docs/PHANTOMS.md, 4.3, «Протокол v2»).
 * <ul>
 * <li>Что видит модель: живые рядом ({@code players} в запросе стратега, {@link #json}) и что делает собеседник ({@code player.doing} в разговорах, {@link #doing}) — только то, что видно глазами.</li>
 * <li>События с живым рядом ({@link #watch}): пришёл на спот, мало HP, умер, бьёт моба охотника, на нём толпа — внеочередное решение стратега ({@code trigger}).</li>
 * <li>Действия по решению стратега: помочь ({@code HELP}: рес, хил, мана, бафы, его моб — чем, решает сервер), делить спот или позвать к себе ({@code SHARE}),
 * предложить вещь лавкой на месте ({@code TRADE}); цену в открытой лавке мозги разговора меняют блоком {@code [[PRICE N]]} ({@link #price}). Уступить спот ({@code YIELD}) — у менеджера.</li>
 * </ul>
 * Живого без клиента играет охотник самопроверки NEAR ({@link #testPlayer}).
 */
final class PhantomNear
{
	private static final Logger LOGGER = Logger.getLogger(PhantomNear.class.getName());

	/** Живые рядом — не больше. */
	static final int MAX_PLAYERS = 3;
	/** Мобы на живом — в этом радиусе от него. */
	private static final int AGGRO_RANGE = 1200;
	/** Агрессивные мобы ближе этого — лавку на месте не открыть. */
	private static final int TRADE_AGGRO_RANGE = 1200;
	/** Лавку не открыть ближе ShopMinRangeFromNpc к NPC и мобу (и трупу) и ShopMinRangeFromPlayer к сидящему: отойти на столько от ближнего. */
	private static final int TRADE_STEP_AWAY = 250;
	/** Mobius меряет и с радиусами обоих: запас на них. */
	private static final int STORE_RANGE_MARGIN = 60;
	/** На споте — в этом множителе радиуса спота от его точки (как итог решения в менеджере). */
	private static final double SPOT_MARGIN = 1.3;
	/** Мало HP — событие LOW_HP; снова может сработать, когда поднимется выше {@value #LOW_HP_RESET}. */
	private static final double LOW_HP = 0.35;
	private static final double LOW_HP_RESET = 0.5;
	/** Толпа на живом — событие AGGRO; снова — когда мобов не больше одного. */
	private static final int AGGRO_EVENT = 3;
	/** Пришёл на спот (ARRIVED) — если перед этим не был на нём столько. */
	private static final long ARRIVED_GAP = 120000;
	/** Одно и то же событие пары «охотник — живой» — не чаще. */
	private static final long EVENT_PAIR_GAP = 300000;
	/** Пара не виделась столько — забыть (минуты рядом и события начинаются заново). */
	private static final long PAIR_FORGET = 600000;
	/** HELP: хилит, пока HP ниже; мана — магу ниже; его моб — основную цель живого, только если HP живого ниже или мобов на нём не меньше. */
	private static final double HEAL_BELOW = 0.7;
	private static final double HEAL_UNTIL = 0.85;
	private static final double MANA_BELOW = 0.5;
	private static final double MOB_MAIN_HP = 0.5;
	private static final int MOB_MAIN_AGGRO = 2;
	/** HELP: дойти и начать — не дольше, бить его моба — не дольше, всё вместе — не дольше; хилов подряд — не больше. */
	private static final long HELP_REACH = 15000;
	private static final long HELP_MOB_TIME = 30000;
	private static final long HELP_TIMEOUT = 60000;
	private static final int HELP_HEALS = 3;
	/** SHARE: позвать к себе — ждать, и одного живого — не чаще. */
	private static final long INVITE_WAIT = 600000;
	private static final long INVITE_GAP = 600000;
	/** TRADE: цена — от и до цены магазина. */
	private static final double TRADE_MIN = 0.5;
	private static final double TRADE_MAX = 3.0;
	private static final int TRADE_CHOICE_ITEMS = 5;

	/** Событие с живым рядом — повод думать раньше срока ({@code "trigger"} в запросе стратега). */
	record Trigger(String event, int playerId, String name, long at)
	{
	}

	/** Пара «охотник — живой»: с какого времени рядом, когда виделись, прошлое состояние живого — для событий. */
	private static final class Pair
	{
		long firstSeen;
		long lastSeen;
		boolean dead;
		boolean low;
		boolean aggro;
		long onSpotSeen;
		final Map<String, Long> fired = new HashMap<>();
	}

	/** Чем помогает (HELP). */
	private enum Need
	{
		RES("рес"),
		HEAL("хил"),
		MANA("мана"),
		BUFF("баф"),
		MOB("моб");

		final String word;

		Need(String word)
		{
			this.word = word;
		}
	}

	/** Что охотник может сделать для живого: чем, что именно; или почему нечем. */
	private record Help(Need need, String what, Monster mob, String refusal)
	{
	}

	/** HELP в работе. */
	private static final class HelpTask
	{
		final Choice choice;
		final int targetId;
		final Need need;
		final Monster mob;
		final long started;
		long casting;
		int casts;
		long reached;
		/** Ждал отката скилла: не вышло — «скилл на откате», а не «не дотянулся». */
		boolean reuse;
		/** Сидел, ждал маны на рес: не дождался — «мало MP». */
		boolean mana;

		HelpTask(Choice choice, int targetId, Need need, Monster mob, long now)
		{
			this.choice = choice;
			this.targetId = targetId;
			this.need = need;
			this.mob = mob;
			started = now;
		}
	}

	/** SHARE: позвал к себе — ждёт. */
	private record Invite(Choice choice, int playerId, HuntSpot spot, long until)
	{
	}

	/** TRADE: лавка на месте для живого. */
	private static final class TradeTask
	{
		final Choice choice;
		final int playerId;
		final int itemObjectId;
		final String itemName;
		final long count;
		final long refPrice;
		volatile long price;
		final long until;
		final long adenaBefore;
		/** Когда открыл лавку: {@code minutes} в торге — сколько она уже открыта. */
		final long opened = System.currentTimeMillis();

		TradeTask(Choice choice, int playerId, Item item, long price, long until, long adenaBefore)
		{
			this.choice = choice;
			this.playerId = playerId;
			itemObjectId = item.getObjectId();
			itemName = item.getName();
			count = item.getCount();
			refPrice = item.getTemplate().getReferencePrice();
			this.price = price;
			this.until = until;
			this.adenaBefore = adenaBefore;
		}
	}

	private final Map<Long, Pair> _pairs = new ConcurrentHashMap<>();
	/** Охотник → событие для стратега, ещё не спрошенное (SAME_MOB приходит из {@link PhantomNeighbors}, не с тика). */
	private final Map<Integer, Trigger> _pending = new ConcurrentHashMap<>();
	private final Map<Integer, HelpTask> _helps = new ConcurrentHashMap<>();
	/** Охотник → живой → до какого времени делят спот. */
	private final Map<Integer, Map<Integer, Long>> _shares = new ConcurrentHashMap<>();
	private final Map<Integer, Invite> _invites = new ConcurrentHashMap<>();
	/** Живой → когда его можно звать к себе снова (кто бы ни звал). */
	private final Map<Integer, Long> _inviteNext = new ConcurrentHashMap<>();
	private final Map<Integer, TradeTask> _trades = new ConcurrentHashMap<>();
	/** Самопроверка NEAR: этот охотник играет живого игрока. */
	private volatile int _testPlayerId;

	private PhantomNear()
	{
	}

	// ---------- Кто живой и кто рядом ----------

	/**
	 * Охотник самопроверки NEAR играет живого игрока: фокус, {@code players}, события и действия считают его живым.
	 */
	void testPlayer(Player player)
	{
		_testPlayerId = player != null ? player.getObjectId() : 0;
	}

	boolean isTestPlayer(Player player)
	{
		return (player != null) && (_testPlayerId != 0) && (player.getObjectId() == _testPlayerId);
	}

	/**
	 * @return живой игрок для шага 6: не фантом, с клиентом, не невидимый; или «живой» самопроверки NEAR
	 */
	boolean isLive(Player player)
	{
		if (player == null)
		{
			return false;
		}
		if (isTestPlayer(player))
		{
			return true;
		}
		return !PhantomManager.getInstance().isPhantom(player) && (player.getClient() != null) && !player.getClient().isDetached() && !player.isInvisible();
	}

	/**
	 * Живые рядом с охотником, как их видит фокус ({@link PhantomFocus}): в одной пати, говорил с ним или в {@link PhantomFocus#NEAR_RANGE} и в прямой видимости.
	 * @return ближние первыми, не больше {@value #MAX_PLAYERS}
	 */
	List<Player> players(Player hunter)
	{
		final List<Player> result = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if ((player == hunter) || !isLive(player) || (player.getInstanceId() != hunter.getInstanceId()))
			{
				continue;
			}
			final boolean party = hunter.isInParty() && (hunter.getParty() == player.getParty());
			final boolean near = (hunter.calculateDistance2D(player) <= PhantomFocus.NEAR_RANGE) && GeoEngine.getInstance().canSeeTarget(hunter, player);
			if (party || near || PhantomFocus.getInstance().talking(hunter, player))
			{
				result.add(player);
			}
		}
		result.sort(Comparator.comparingDouble(hunter::calculateDistance2D));
		return result.size() > MAX_PLAYERS ? new ArrayList<>(result.subList(0, MAX_PLAYERS)) : result;
	}

	/**
	 * @return живой рядом по имени (без регистра) или {@code null}
	 */
	Player find(Player hunter, String name)
	{
		final String wanted = normalize(name);
		for (Player player : players(hunter))
		{
			if (normalize(player.getName()).equals(wanted))
			{
				return player;
			}
		}
		return null;
	}

	/**
	 * @return спот охотников, где он стоит (в радиусе спота): сначала {@code prefer}, потом ближайший; {@code null} — не на споте
	 */
	static HuntSpot spotOf(Player player, HuntSpot prefer)
	{
		if ((prefer != null) && (player.calculateDistance2D(prefer.entry) <= (prefer.radius * SPOT_MARGIN)))
		{
			return prefer;
		}
		HuntSpot best = null;
		double bestDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			final double distance = player.calculateDistance2D(spot.entry);
			if ((distance <= (spot.radius * SPOT_MARGIN)) && (distance < bestDistance))
			{
				best = spot;
				bestDistance = distance;
			}
		}
		return best;
	}

	/**
	 * @return моб, которого он бьёт сейчас, или {@code null}
	 */
	private static Monster mobTarget(Player player)
	{
		final WorldObject target = player.getTarget();
		if (!(target instanceof Monster) || ((Monster) target).isDead() || (!player.isAttackingNow() && !player.isCastingNow() && !player.isInCombat()))
		{
			return null;
		}
		return (Monster) target;
	}

	/**
	 * @return он в бою с мобом: бьёт моба или мобы бьют его (боевая стойка после последнего удара — не в счёт)
	 */
	static boolean fighting(Player player)
	{
		return (mobTarget(player) != null) || !attackers(player).isEmpty();
	}

	/**
	 * @return мобы, которые бьют его сейчас
	 */
	private static List<Monster> attackers(Player player)
	{
		final List<Monster> result = new ArrayList<>();
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, AGGRO_RANGE))
		{
			if (!monster.isDead() && (monster.getTarget() == player) && monster.isInCombat())
			{
				result.add(monster);
			}
		}
		return result;
	}

	// ---------- Что видит модель ----------

	/**
	 * {@code "players":[…],} в запрос стратега — живые рядом: уровень, класс, роль, HP (MP — только сопартийцу, его видно в окне пати), мёртв, сидит, расстояние, пати,
	 * кого бьёт (тот же ли моб, что охотник), сколько мобов на нём, на каком споте (на том же ли), сколько минут рядом, ПК, флаг, друг или враг. Никого — ничего.
	 * @param players {@link #players}
	 */
	void json(StringBuilder sb, Player hunter, HuntSpot spot, List<Player> players, long now)
	{
		if (players.isEmpty())
		{
			return;
		}

		sb.append("\"players\":[");
		boolean first = true;
		final WorldObject hunterTarget = hunter.getTarget();
		for (Player other : players)
		{
			sb.append(first ? "" : ",").append('{');
			first = false;
			FakePlayerBrain.field(sb, "name", other.getName()).append(',');
			sb.append("\"level\":").append(other.getLevel()).append(',');
			FakePlayerBrain.field(sb, "class", other.getPlayerClass().name().replace('_', ' ').toLowerCase(Locale.ROOT)).append(',');
			FakePlayerBrain.field(sb, "role", PhantomHunterStrategy.role(other)).append(',');
			sb.append("\"hpPct\":").append(percent(other.getCurrentHp(), other.getMaxHp())).append(',');
			final boolean withMe = hunter.isInParty() && (hunter.getParty() == other.getParty());
			if (withMe)
			{
				sb.append("\"mpPct\":").append(percent(other.getCurrentMp(), other.getMaxMp())).append(',');
			}
			sb.append("\"dead\":").append(other.isDead()).append(',');
			sb.append("\"sitting\":").append(other.isSitting()).append(',');
			sb.append("\"distance\":").append((int) hunter.calculateDistance2D(other)).append(',');
			sb.append("\"party\":").append(other.isInParty() ? other.getParty().getMemberCount() : 0).append(',');
			sb.append("\"withMe\":").append(withMe).append(',');
			final Monster mob = mobTarget(other);
			final WorldObject target = other.getTarget();
			if (mob != null)
			{
				sb.append("\"target\":{");
				FakePlayerBrain.field(sb, "name", mob.getName()).append(',');
				sb.append("\"level\":").append(mob.getLevel()).append(',');
				sb.append("\"hpPct\":").append(percent(mob.getCurrentHp(), mob.getMaxHp())).append(',');
				sb.append("\"mine\":").append(mob == hunterTarget).append("},");
			}
			else if ((target instanceof Player) && (target != other) && (other.isAttackingNow() || other.isCastingNow()))
			{
				sb.append("\"target\":{");
				FakePlayerBrain.field(sb, "name", ((Player) target).getName()).append(',');
				sb.append("\"player\":true},");
			}
			sb.append("\"aggro\":").append(attackers(other).size()).append(',');
			final HuntSpot where = spotOf(other, spot);
			FakePlayerBrain.field(sb, "spot", where != null ? where.name : "").append(',');
			sb.append("\"same\":").append((where != null) && (where == spot)).append(',');
			final Pair pair = _pairs.get(key(hunter, other));
			sb.append("\"minutes\":").append(pair != null ? (now - pair.firstSeen) / 60000 : 0).append(',');
			sb.append("\"pk\":").append(PhantomCompat.karma(other) > 0).append(',');
			sb.append("\"flag\":").append(other.getPvpFlag() != 0).append(',');
			final String relation = PhantomRelations.getInstance().isEnemy(hunter, other) ? "enemy" : PhantomRelations.getInstance().isFriend(hunter, other) ? "friend" : "";
			FakePlayerBrain.field(sb, "relation", relation).append('}');
		}
		sb.append("],");
	}

	/**
	 * {@code "doing":{…},} в блок собеседника в разговорах ({@code "kind":"phantom"}): кого он бьёт, сколько мобов на нём, на каком споте и на том же ли, что охотник, —
	 * чтобы охотник мог сказать «о, ты тоже на скелетов?». Только живому собеседнику и только охотником.
	 * @param spot спот охотника или {@code null}
	 */
	void doing(StringBuilder sb, Player phantom, Player player, HuntSpot spot)
	{
		if (!isLive(player))
		{
			return;
		}

		sb.append("\"doing\":{");
		final Monster mob = mobTarget(player);
		if (mob != null)
		{
			FakePlayerBrain.field(sb, "target", mob.getName()).append(',');
			sb.append("\"targetLevel\":").append(mob.getLevel()).append(',');
		}
		sb.append("\"aggro\":").append(attackers(player).size()).append(',');
		final HuntSpot where = spotOf(player, spot);
		FakePlayerBrain.field(sb, "spot", where != null ? where.name : "").append(',');
		sb.append("\"same\":").append((where != null) && (where == spot)).append("},");
	}

	private static int percent(double value, double max)
	{
		return (int) ((value * 100) / Math.max(1, max));
	}

	// ---------- События ----------

	/**
	 * Тик охотника в фокусе (раз в 2 с): кто из живых рядом и что с ними случилось. Пара «охотник — живой» помнит, когда они впервые оказались рядом ({@code minutes}).
	 * @param events искать ли события (расширенный режим и {@code HunterFocusEvents}); нет — только счёт минут
	 * @return событие для стратега (самое важное из случившихся) или {@code null}
	 */
	Trigger watch(Player hunter, HuntSpot spot, boolean events, long now)
	{
		Trigger best = _pending.remove(hunter.getObjectId());
		int bestRank = best != null ? rank(best.event()) : Integer.MAX_VALUE;
		for (Player other : players(hunter))
		{
			final Pair pair = _pairs.computeIfAbsent(key(hunter, other), k -> new Pair());
			if ((pair.lastSeen == 0) || ((now - pair.lastSeen) > PAIR_FORGET))
			{
				pair.firstSeen = now;
				pair.onSpotSeen = 0;
			}
			pair.lastSeen = now;

			final String event;
			final boolean dead = other.isDead();
			final double hp = other.getCurrentHp() / Math.max(1, other.getMaxHp());
			final int aggro = dead ? 0 : attackers(other).size();
			final boolean onSpot = (spot != null) && (spotOf(other, spot) == spot) && (mobTarget(other) != null);
			if (dead && !pair.dead)
			{
				event = "DIED";
			}
			else if (!dead && other.isInCombat() && (hp < LOW_HP) && !pair.low)
			{
				event = "LOW_HP";
			}
			else if ((aggro >= AGGRO_EVENT) && !pair.aggro)
			{
				event = "AGGRO";
			}
			else if (onSpot && ((pair.onSpotSeen == 0) || ((now - pair.onSpotSeen) > ARRIVED_GAP)) && !(hunter.isInParty() && (hunter.getParty() == other.getParty())))
			{
				event = "ARRIVED";
			}
			else
			{
				event = null;
			}
			pair.dead = dead;
			pair.low = dead ? false : (pair.low ? hp < LOW_HP_RESET : (other.isInCombat() && (hp < LOW_HP)));
			pair.aggro = pair.aggro ? aggro > 1 : aggro >= AGGRO_EVENT;
			if (onSpot)
			{
				pair.onSpotSeen = now;
			}

			if (!events || (event == null) || ((now - pair.fired.getOrDefault(event, 0L)) < EVENT_PAIR_GAP))
			{
				continue;
			}
			pair.fired.put(event, now);
			if (rank(event) < bestRank)
			{
				best = new Trigger(event, other.getObjectId(), other.getName(), now);
				bestRank = rank(event);
			}
		}
		_pairs.values().removeIf(pair -> (now - pair.lastSeen) > PAIR_FORGET);
		return events ? best : null;
	}

	/**
	 * Живой бьёт моба, которого охотник бил первым ({@link PhantomNeighbors}): событие SAME_MOB — на ближайшем тике охотника.
	 */
	void sameMob(Player hunter, Player other, long now)
	{
		if (!isLive(other))
		{
			return;
		}
		final Pair pair = _pairs.computeIfAbsent(key(hunter, other), k -> new Pair());
		if ((now - pair.fired.getOrDefault("SAME_MOB", 0L)) < EVENT_PAIR_GAP)
		{
			return;
		}
		pair.fired.put("SAME_MOB", now);
		_pending.merge(hunter.getObjectId(), new Trigger("SAME_MOB", other.getObjectId(), other.getName(), now), (a, b) -> rank(a.event()) <= rank(b.event()) ? a : b);
	}

	/**
	 * @return важность события: меньше — важнее
	 */
	private static int rank(String event)
	{
		switch (event)
		{
			case "DIED":
			{
				return 0;
			}
			case "LOW_HP":
			{
				return 1;
			}
			case "AGGRO":
			{
				return 2;
			}
			case "SAME_MOB":
			{
				return 3;
			}
			default:
			{
				return 4;
			}
		}
	}

	private static long key(Player hunter, Player player)
	{
		return ((long) hunter.getObjectId() << 32) | (player.getObjectId() & 0xffffffffL);
	}

	// ---------- HELP ----------

	/**
	 * Чем охотник может помочь живому сейчас: рес мёртвому; хил, если HP ниже {@value #HEAL_BELOW}; мана магу ниже {@value #MANA_BELOW}; бафы, которых на нём нет;
	 * его моб — тот, кто бьёт живого, кроме его основной цели (основную — только если HP живого ниже {@value #MOB_MAIN_HP} или мобов на нём от {@value #MOB_MAIN_AGGRO}):
	 * опыт и дроп достаются тому, кто нанёс больше урона, и охотник не уводит у живого его моба.
	 */
	private static Help help(Player hunter, Player other)
	{
		if (other.isDead())
		{
			final Skill res = hunter.getKnownSkill(PhantomNeighbors.RESURRECTION);
			if (res == null)
			{
				return new Help(null, "", null, "нечем помочь: нет реса");
			}
			if (other.isReviveRequested())
			{
				return new Help(null, "", null, "его уже ресают");
			}
			// Скилл на откате или маны мало — не отказ: помощь дождётся отката, а ману — сидя (helpTick). Отказ — только когда рес не по карману и с полной маной.
			return hunter.getMaxMp() < (res.getMpConsume() + res.getMpInitialConsume()) ? new Help(null, "", null, "мало MP") : new Help(Need.RES, res.getName(), null, null);
		}

		boolean lowMp = false;
		final double hp = other.getCurrentHp() / Math.max(1, other.getMaxHp());
		if ((hp < HEAL_BELOW) && (PhantomSupport.knownHeal(hunter) != null))
		{
			final Skill ready = PhantomSupport.usableHeal(hunter);
			final Skill heal = ready != null ? ready : PhantomSupport.affordableHeal(hunter);
			if (heal != null)
			{
				return new Help(Need.HEAL, heal.getName(), null, null);
			}
			lowMp = true;
		}
		if (other.isMageClass() && ((other.getCurrentMp() / Math.max(1, other.getMaxMp())) < MANA_BELOW) && (hunter.getKnownSkill(PhantomSupport.RECHARGE) != null))
		{
			final Skill recharge = PhantomSupport.affordable(hunter, PhantomSupport.RECHARGE);
			if (recharge != null)
			{
				return new Help(Need.MANA, recharge.getName(), null, null);
			}
			lowMp = true;
		}
		final List<Skill> buffs = PhantomSupport.missingBuffs(hunter, other);
		if (!buffs.isEmpty())
		{
			return new Help(Need.BUFF, buffs.size() + " " + (buffs.size() == 1 ? "баф" : buffs.size() < 5 ? "бафа" : "бафов"), null, null);
		}

		final List<Monster> attackers = attackers(other);
		final Monster main = mobTarget(other);
		Monster mob = null;
		for (Monster monster : attackers)
		{
			if (monster != main)
			{
				mob = monster;
				break;
			}
		}
		if ((mob == null) && (main != null) && attackers.contains(main) && ((hp < MOB_MAIN_HP) || (attackers.size() >= MOB_MAIN_AGGRO)))
		{
			mob = main;
		}
		if (mob != null)
		{
			return new Help(Need.MOB, mob.getName(), mob, null);
		}
		if (lowMp)
		{
			return new Help(null, "", null, "мало MP");
		}
		return new Help(null, "", null, attackers.isEmpty() ? "нечем помочь: он в порядке" : "он справляется");
	}

	/**
	 * Выбор для HELP: кому из живых рядом охотник может помочь и чем ({@code need}: рес, хил, мана, баф, моб; {@code what} — скилл, сколько бафов, имя моба). Врагам не помогает.
	 * @return {@code [{"name":…,"need":…,"what":…},…]} или {@code null} — помочь некому
	 */
	String helpChoices(Player hunter, List<Player> players)
	{
		final StringBuilder sb = new StringBuilder("[");
		int count = 0;
		for (Player other : players)
		{
			if (PhantomRelations.getInstance().isEnemy(hunter, other))
			{
				continue;
			}
			final Help help = help(hunter, other);
			if (help.need() == null)
			{
				continue;
			}
			sb.append(count++ > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", other.getName()).append(',');
			FakePlayerBrain.field(sb, "need", help.need().word).append(',');
			FakePlayerBrain.field(sb, "what", help.what()).append('}');
		}
		return count == 0 ? null : sb.append(']').toString();
	}

	/**
	 * {@code [[HELP имя]]}: проверить и начать — дальше {@link #tick}. Мысль модели — ему вслух в белый чат (пустая — молча).
	 * @return {@code null} — начал; иначе причина отказа по-русски — для {@code last}
	 */
	String startHelp(Player hunter, Choice choice, long now)
	{
		final Player other = find(hunter, choice.arg);
		if (other == null)
		{
			return "нет такого рядом";
		}
		if (PhantomRelations.getInstance().isEnemy(hunter, other))
		{
			return "он враг";
		}
		final Help help = help(hunter, other);
		if (help.need() == null)
		{
			return help.refusal();
		}

		_helps.put(hunter.getObjectId(), new HelpTask(choice, other.getObjectId(), help.need(), help.mob(), now));
		AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
		if (hunter.isSitting())
		{
			hunter.standUp();
		}
		say(hunter, choice);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " helps " + other.getName() + ": " + help.need().word + " (" + help.what() + ").");
		return null;
	}

	/**
	 * Помощь идёт: каст по одному (подходит к цели сам, как у напарника), его моб — бой до смерти моба. Кончил — итог в решение.
	 * @return {@code true}, пока помогает: остальной тик охотника ждёт
	 */
	private boolean helpTick(Player hunter, HelpTask task, long now)
	{
		final Player other = World.getPlayer(task.targetId);
		if ((other == null) || !isLive(other) || (hunter.calculateDistance2D(other) > (PhantomFocus.NEAR_RANGE * 1.5)))
		{
			return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "" : "он ушёл");
		}
		if (hunter.isCastingNow())
		{
			task.reached = now;
			return true;
		}
		if ((now - task.started) > HELP_TIMEOUT)
		{
			return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "" : task.mana ? lowMp(hunter, PhantomNeighbors.RESURRECTION) : task.reuse ? "скилл на откате" : "не дотянулся");
		}
		// Каст ушёл, а ещё идёт к цели или каст не начался — ждать, но не дольше HELP_REACH с последнего каста.
		if ((now - Math.max(task.started, task.reached)) > HELP_REACH)
		{
			return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "" : task.reuse ? "скилл на откате" : "не дотянулся");
		}
		if ((task.casting > 0) && hunter.isMoving())
		{
			return true;
		}

		switch (task.need)
		{
			case RES:
			{
				if (!other.isDead() || other.isReviveRequested())
				{
					return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "реснул" : "его уже подняли");
				}
				final Skill res = PhantomSupport.usable(hunter, PhantomNeighbors.RESURRECTION);
				if (res == null)
				{
					if (PhantomSupport.affordable(hunter, PhantomNeighbors.RESURRECTION) != null)
					{
						return waitReuse(task, now);
					}
					// Маны на рес не хватает — сесть и ждать её, как живой хилер, если самого не бьют (прогон NEAR H5 2026-10-26: пока ждал отката, ману съели хилы).
					return attackers(hunter).isEmpty() ? waitMana(hunter, task, now) : finishHelp(hunter, task, false, lowMp(hunter, PhantomNeighbors.RESURRECTION));
				}
				if (hunter.isSitting())
				{
					hunter.standUp();
					return true;
				}
				return cast(hunter, task, other, res, now);
			}
			case HEAL:
			{
				final double hp = other.getCurrentHp() / Math.max(1, other.getMaxHp());
				if (other.isDead() || (hp >= HEAL_UNTIL) || (task.casts >= HELP_HEALS))
				{
					return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "отхилил до " + (int) (hp * 100) + " %" : "он уже здоров");
				}
				final Skill heal = PhantomSupport.usableHeal(hunter);
				if ((heal == null) && (PhantomSupport.affordableHeal(hunter) != null))
				{
					return waitReuse(task, now);
				}
				return heal == null ? finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "отхилил до " + (int) (hp * 100) + " %" : "мало MP") : cast(hunter, task, other, heal, now);
			}
			case MANA:
			{
				if (task.casts > 0)
				{
					return finishHelp(hunter, task, true, "дал ману");
				}
				final Skill recharge = PhantomSupport.usable(hunter, PhantomSupport.RECHARGE);
				if (recharge == null)
				{
					return PhantomSupport.affordable(hunter, PhantomSupport.RECHARGE) == null ? finishHelp(hunter, task, false, lowMp(hunter, PhantomSupport.RECHARGE)) : waitReuse(task, now);
				}
				return cast(hunter, task, other, recharge, now);
			}
			case BUFF:
			{
				final List<Skill> buffs = other.isDead() ? List.of() : PhantomSupport.missingBuffs(hunter, other);
				if (buffs.isEmpty())
				{
					return finishHelp(hunter, task, task.casts > 0, task.casts > 0 ? "бафнул: " + task.casts : "мало MP");
				}
				return cast(hunter, task, other, buffs.get(0), now);
			}
			default:
			{
				final Monster mob = task.mob;
				if ((mob == null) || mob.isDead())
				{
					return finishHelp(hunter, task, true, "добили моба");
				}
				if ((now - task.started) > HELP_MOB_TIME)
				{
					return finishHelp(hunter, task, true, "бил его моба " + (HELP_MOB_TIME / 1000) + " с");
				}
				task.casts++;
				task.reached = now;
				PhantomCompanionManager.fight(hunter, mob);
				return true;
			}
		}
	}

	/**
	 * Скилл на откате, MP хватает: ждать отката (стоит рядом), но не дольше {@value #HELP_TIMEOUT} мс от начала помощи.
	 */
	private static boolean waitReuse(HelpTask task, long now)
	{
		task.reuse = true;
		task.reached = now;
		return true;
	}

	/**
	 * Маны на рес нет: сидеть и ждать её, но не дольше {@value #HELP_TIMEOUT} мс от начала помощи.
	 */
	private static boolean waitMana(Player hunter, HelpTask task, long now)
	{
		if (!task.mana)
		{
			task.mana = true;
			LOGGER.info(PhantomNear.class.getSimpleName() + ": " + hunter.getName() + " sits down to regain MP for a resurrection (" + (int) hunter.getCurrentMp() + " MP).");
		}
		if (!hunter.isSitting() && !hunter.isMoving() && !hunter.isCastingNow())
		{
			hunter.sitDown();
		}
		task.reached = now;
		return true;
	}

	/**
	 * «мало MP» — и в лог, сколько MP и сколько нужно: отказ по мане должен быть правдой (прогон NEAR 2026-10-26: «мало MP» при полной мане был откатом).
	 */
	private static String lowMp(Player hunter, int skillId)
	{
		final Skill skill = hunter.getKnownSkill(skillId);
		LOGGER.info(PhantomNear.class.getSimpleName() + ": " + hunter.getName() + " lacks MP for " + (skill != null ? skill.getName() + ": " + (int) hunter.getCurrentMp() + " of " + (skill.getMpConsume() + skill.getMpInitialConsume()) : skillId) + ".");
		return "мало MP";
	}

	private static boolean cast(Player hunter, HelpTask task, Player other, Skill skill, long now)
	{
		hunter.setTarget(other);
		if (PhantomCompat.useMagic(hunter, skill, false, false))
		{
			task.casts++;
			task.casting = now;
		}
		return true;
	}

	private boolean finishHelp(Player hunter, HelpTask task, boolean ok, String reason)
	{
		_helps.remove(hunter.getObjectId());
		task.choice.result(ok, ok && reason.isEmpty() ? "" : reason);
		// Удача с причиной — что сделал («реснул», «бафнул: 5»): итог для модели, как «купил не всё» у BUY.
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " " + task.choice + ": " + (ok ? "done" : "failed") + (reason.isEmpty() ? "" : " (" + reason + ")") + ".");
		return false;
	}

	// ---------- SHARE ----------

	/**
	 * Выбор для SHARE: живые рядом, кроме своей пати и врагов. {@code here} — на его споте («бей своих, я своих»), иначе — позвать к себе (одного живого — не чаще раза в 10 минут).
	 * @return {@code [{"name":…,"here":…},…]} или {@code null}
	 */
	String shareChoices(Player hunter, HuntSpot spot, List<Player> players, long now)
	{
		if (spot == null)
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder("[");
		int count = 0;
		for (Player other : players)
		{
			final boolean here = spotOf(other, spot) == spot;
			if ((hunter.isInParty() && (hunter.getParty() == other.getParty())) || PhantomRelations.getInstance().isEnemy(hunter, other) || shared(hunter, other, now) || (!here && (now < _inviteNext.getOrDefault(other.getObjectId(), 0L))))
			{
				continue;
			}
			sb.append(count++ > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", other.getName()).append(',');
			sb.append("\"here\":").append(here).append('}');
		}
		return count == 0 ? null : sb.append(']').toString();
	}

	/**
	 * {@code [[SHARE имя]]}: на его споте — делят спот {@code HunterShareMinutes} минут, мысль — ему вслух; не на нём — мысль шёпотом с названием спота, ждёт его 10 минут.
	 * @return {@code null} — сделал (позвал); иначе причина отказа
	 */
	String share(Player hunter, HuntSpot spot, Choice choice, long now)
	{
		final Player other = find(hunter, choice.arg);
		if (other == null)
		{
			return "нет такого рядом";
		}
		if (hunter.isInParty() && (hunter.getParty() == other.getParty()))
		{
			return "он в твоей пати";
		}
		if (PhantomRelations.getInstance().isEnemy(hunter, other))
		{
			return "он враг";
		}
		if (spot == null)
		{
			return "ты не на споте";
		}

		if (spotOf(other, spot) == spot)
		{
			shareNow(hunter, other, now);
			say(hunter, choice);
			choice.result(true, "");
			return null;
		}
		if (now < _inviteNext.getOrDefault(other.getObjectId(), 0L))
		{
			return "его недавно звали";
		}
		_inviteNext.put(other.getObjectId(), now + INVITE_GAP);
		_invites.put(hunter.getObjectId(), new Invite(choice, other.getObjectId(), spot, now + INVITE_WAIT));
		final String where = spot.name + ", мобы " + spot.min + "-" + spot.max;
		PhantomCompanionManager.whisper(hunter, other, choice.thought.isEmpty() ? "иди к нам: " + where : choice.thought + " (" + where + ")");
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " calls " + other.getName() + " to " + spot.name + ", waits " + (INVITE_WAIT / 60000) + " min.");
		return null;
	}

	private void shareNow(Player hunter, Player other, long now)
	{
		_shares.computeIfAbsent(hunter.getObjectId(), k -> new ConcurrentHashMap<>()).put(other.getObjectId(), now + (Math.max(1, PhantomHuntersConfig.HUNTER_SHARE_MINUTES) * 60000L));
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " shares the spot with " + other.getName() + " for " + PhantomHuntersConfig.HUNTER_SHARE_MINUTES + " min.");
	}

	/**
	 * @return делит спот с ним: не берёт его мобов и не обижается за «отжал моба» ({@link PhantomNeighbors})
	 */
	boolean shared(Player hunter, Player other, long now)
	{
		final Map<Integer, Long> shares = _shares.get(hunter.getObjectId());
		return (shares != null) && (shares.getOrDefault(other.getObjectId(), 0L) > now);
	}

	/**
	 * Делят спот: моба, которого бьёт напарник по споту, охотник бросает — AutoPlay возьмёт другого. Позвал к себе — пришёл ли.
	 */
	private void shareTick(Player hunter, HuntSpot spot, long now)
	{
		final Invite invite = _invites.get(hunter.getObjectId());
		if (invite != null)
		{
			final Player other = World.getPlayer(invite.playerId());
			if ((other != null) && (spot == invite.spot()) && (spotOf(other, spot) == spot))
			{
				_invites.remove(hunter.getObjectId());
				shareNow(hunter, other, now);
				invite.choice().result(true, "");
				LOGGER.info(getClass().getSimpleName() + ": " + other.getName() + " came to " + spot.name + " on the call of " + hunter.getName() + ".");
			}
			else if ((now > invite.until()) || (other == null) || (spot != invite.spot()))
			{
				_invites.remove(hunter.getObjectId());
				invite.choice().result(false, "не пришёл");
				LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " called in vain" + (other != null ? " " + other.getName() : "") + ".");
			}
		}

		final Map<Integer, Long> shares = _shares.get(hunter.getObjectId());
		if (shares == null)
		{
			return;
		}
		shares.values().removeIf(until -> until <= now);
		final WorldObject target = hunter.getTarget();
		if (!(target instanceof Monster))
		{
			return;
		}
		for (Integer id : shares.keySet())
		{
			final Player other = World.getPlayer(id);
			if ((other != null) && (mobTarget(other) == target))
			{
				hunter.setTarget(null);
				hunter.abortAttack();
				hunter.abortCast();
				return;
			}
		}
	}

	// ---------- TRADE ----------

	/**
	 * Выбор для TRADE: кому предложить (живые рядом, не враги) и что — вещи из сумки, которые не надеты и которые можно отдать, дорогие первыми, по цене магазина.
	 * @return {@code [{"name":…,"items":[{"name":…,"count":…,"price":…},…]},…]} или {@code null}
	 */
	String tradeChoices(Player hunter, List<Player> players)
	{
		final List<Item> goods = tradeGoods(hunter);
		if (goods.isEmpty() || (_trades.get(hunter.getObjectId()) != null))
		{
			return null;
		}
		final StringBuilder items = new StringBuilder("[");
		for (int i = 0; i < Math.min(TRADE_CHOICE_ITEMS, goods.size()); i++)
		{
			final Item item = goods.get(i);
			items.append(i > 0 ? "," : "").append('{');
			FakePlayerBrain.field(items, "name", item.getName()).append(',');
			items.append("\"count\":").append(item.getCount()).append(',');
			items.append("\"price\":").append(item.getTemplate().getReferencePrice()).append('}');
		}
		items.append(']');

		final StringBuilder sb = new StringBuilder("[");
		int count = 0;
		for (Player other : players)
		{
			if (other.isDead() || PhantomRelations.getInstance().isEnemy(hunter, other))
			{
				continue;
			}
			sb.append(count++ > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", other.getName()).append(',');
			sb.append("\"items\":").append(items).append('}');
		}
		return count == 0 ? null : sb.append(']').toString();
	}

	/**
	 * @return что охотник может предложить живому: не надето, отдаётся ({@link PhantomHunterOutfit#junk}), передаётся; дорогое первым
	 */
	static List<Item> tradeGoods(Player hunter)
	{
		final List<Item> goods = new ArrayList<>();
		for (Item item : hunter.getInventory().getItems())
		{
			if (PhantomHunterOutfit.junk(hunter, item) && item.isTradeable())
			{
				goods.add(item);
			}
		}
		goods.sort((a, b) -> Long.compare(b.getTemplate().getReferencePrice() * b.getCount(), a.getTemplate().getReferencePrice() * a.getCount()));
		return goods;
	}

	/**
	 * {@code [[TRADE имя; вещь; цена]]}: шёпот ему (мысль модели) и лавка на месте на одну вещь на {@code HunterTradeMinutes} минут — он покупает обычным окном лавки.
	 * Цена зажимается в {@value #TRADE_MIN}–{@value #TRADE_MAX} цены магазина. Агрессивные мобы рядом — отказ без шёпота: сидящего в лавке съедят.
	 * @return {@code null} — лавка открыта; иначе причина отказа
	 */
	String trade(Player hunter, Choice choice, long now)
	{
		final String[] parts = choice.arg.split(";", 3);
		final Player other = find(hunter, parts[0]);
		if (other == null)
		{
			return "нет такого рядом";
		}
		if (PhantomRelations.getInstance().isEnemy(hunter, other))
		{
			return "он враг";
		}
		if ((parts.length < 2) || normalize(parts[1]).isEmpty())
		{
			return "не назвал, что продать";
		}
		Item item = null;
		boolean worn = false;
		for (Item candidate : hunter.getInventory().getItems())
		{
			if (!normalize(candidate.getName()).equals(normalize(parts[1])))
			{
				continue;
			}
			if (candidate.isEquipped())
			{
				worn = true;
			}
			else if (PhantomHunterOutfit.junk(hunter, candidate) && candidate.isTradeable())
			{
				item = candidate;
				break;
			}
		}
		if (item == null)
		{
			return worn ? "надето — сначала смени" : "нет у тебя такого";
		}
		// Без цены (мозги не узнали её в ответе) — цена магазина.
		final long asked = parts.length > 2 ? parsePrice(parts[2]) : 0;
		if ((asked <= 0) && (item.getTemplate().getReferencePrice() <= 0))
		{
			return "не назвал цену";
		}
		if (World.getFirstVisibleObject(hunter, Monster.class, monster -> !monster.isDead() && monster.isAggressive() && (hunter.calculateDistance2D(monster) < TRADE_AGGRO_RANGE)) != null)
		{
			return "тут агро-мобы";
		}
		if (!attackers(hunter).isEmpty())
		{
			return "на тебе мобы";
		}
		if (hunter.isInStoreMode())
		{
			return "тут нельзя открыть лавку";
		}
		if (!hunter.canOpenPrivateStore())
		{
			final Creature blocker = storeBlocker(hunter);
			return hunter.isCastingNow() ? "кастует" : blocker != null ? "рядом " + blocker.getName() + " — лавку не поставить" : "тут нельзя открыть лавку";
		}

		final long price = asked > 0 ? clamp(asked, item.getTemplate().getReferencePrice()) : item.getTemplate().getReferencePrice();
		final TradeTask task = new TradeTask(choice, other.getObjectId(), item, price, now + (Math.max(1, PhantomHuntersConfig.HUNTER_TRADE_MINUTES) * 60000L), hunter.getAdena());
		if (!openStore(hunter, task, other))
		{
			return "тут нельзя открыть лавку";
		}
		_trades.put(hunter.getObjectId(), task);
		PhantomCompanionManager.whisper(hunter, other, choice.thought.isEmpty() ? "продам " + item.getName() + " за " + PhantomHunterManager.money(price) + ", лавка тут" : choice.thought);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " offers " + other.getName() + " " + item.getName() + (task.count > 1 ? " x" + task.count : "") + " for " + price + (price != asked ? " (asked " + asked + ")" : "") + ", shop price " + task.refPrice + ".");
		return null;
	}

	/**
	 * Кто не даёт открыть лавку здесь: NPC, моб или его труп ближе ShopMinRangeFromNpc, сидящий игрок ближе ShopMinRangeFromPlayer (PrivateStoreRange.ini). Ближний первым.
	 * @return он или {@code null}
	 */
	static Creature storeBlocker(Player hunter)
	{
		Creature nearest = null;
		double best = Double.MAX_VALUE;
		for (Creature creature : World.getVisibleObjectsInRange(hunter, Creature.class, 400))
		{
			final int range = creature.getMinShopDistance();
			final double distance = hunter.calculateDistance2D(creature);
			if ((range > 0) && (distance < (range + STORE_RANGE_MARGIN)) && (distance < best))
			{
				nearest = creature;
				best = distance;
			}
		}
		return nearest;
	}

	/**
	 * TRADE: лавку здесь пока не открыть — кастует или идёт (ждать); рядом моб, труп, NPC или сидящий — отойти от ближнего на {@value #TRADE_STEP_AWAY}.
	 * @return {@code true} — ещё готовит место, лавку открывать рано
	 */
	static boolean makeRoom(Player hunter)
	{
		if (hunter.isCastingNow() || hunter.isMoving())
		{
			return true;
		}
		final Creature blocker = storeBlocker(hunter);
		if (blocker == null)
		{
			return false;
		}
		final double dx = hunter.getX() - blocker.getX();
		final double dy = hunter.getY() - blocker.getY();
		final double length = Math.hypot(dx, dy);
		// Стоят вплотную — в случайную сторону.
		final double angle = length < 1 ? Math.random() * 2 * Math.PI : Math.atan2(dy, dx);
		for (int turn = 0; turn < 6; turn++)
		{
			final double a = angle + ((turn % 2) == 0 ? 1 : -1) * (((turn + 1) / 2) * (Math.PI / 4));
			final int x = hunter.getX() + (int) (Math.cos(a) * TRADE_STEP_AWAY);
			final int y = hunter.getY() + (int) (Math.sin(a) * TRADE_STEP_AWAY);
			final int z = GeoEngine.getInstance().getHeight(x, y, hunter.getZ());
			if ((Math.abs(z - hunter.getZ()) <= 150) && PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), hunter.getX(), hunter.getY(), hunter.getZ(), x, y, z, hunter.getInstanceId()))
			{
				AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
				hunter.setTarget(null);
				hunter.getAI().setIntentionMoveTo(new Location(x, y, z));
				LOGGER.info(PhantomNear.class.getSimpleName() + ": " + hunter.getName() + " steps away from " + blocker.getName() + " (" + (int) hunter.calculateDistance2D(blocker) + ") to open a store.");
				return true;
			}
		}
		return false;
	}

	private static boolean openStore(Player hunter, TradeTask task, Player other)
	{
		AutoPlayTaskManager.getInstance().stopAutoPlay(hunter);
		hunter.setTarget(null);
		hunter.abortAttack();
		final TradeList list = hunter.getSellList();
		list.clear();
		list.setPackaged(false);
		if (PhantomCompat.addTradeItem(list, task.itemObjectId, task.count, task.price) == null)
		{
			return false;
		}
		final String title = "Для " + other.getName();
		list.setTitle(title.length() > 29 ? title.substring(0, 29) : title);
		hunter.sitDown();
		hunter.setPrivateStoreType(PrivateStoreType.SELL);
		hunter.broadcastUserInfo();
		hunter.broadcastPacket(new PrivateStoreMsgSell(hunter));
		return true;
	}

	/**
	 * {@code "trade":{…},} в блок охотника в разговоре с тем, кому открыта лавка TRADE: вещь, сколько, цена, цена магазина, сколько минут лавка уже открыта — мозги торгуются
	 * и возвращают {@code [[PRICE N]]}. Лавки ему нет — ничего.
	 */
	void tradeJson(StringBuilder sb, Player hunter, Player player, long now)
	{
		final TradeTask task = _trades.get(hunter.getObjectId());
		if ((task == null) || (player == null) || (player.getObjectId() != task.playerId))
		{
			return;
		}
		sb.append("\"trade\":{");
		FakePlayerBrain.field(sb, "player", player.getName()).append(',');
		FakePlayerBrain.field(sb, "item", task.itemName).append(',');
		sb.append("\"count\":").append(task.count).append(',');
		sb.append("\"price\":").append(task.price).append(',');
		sb.append("\"shopPrice\":").append(task.refPrice).append(',');
		sb.append("\"minutes\":").append(Math.max(0, (now - task.opened) / 60000)).append("},");
	}

	/**
	 * Мозги разговора сбавили (или подняли) цену: {@code [[PRICE N]]} в ответе на шёпот живого, которому открыта лавка TRADE.
	 * @return новая цена (зажатая в {@value #TRADE_MIN}–{@value #TRADE_MAX} цены магазина) или -1 — лавки для него нет
	 */
	long price(Player hunter, Player buyer, long asked)
	{
		final TradeTask task = _trades.get(hunter.getObjectId());
		if ((task == null) || (buyer == null) || (buyer.getObjectId() != task.playerId) || (asked <= 0) || (hunter.getPrivateStoreType() != PrivateStoreType.SELL))
		{
			return -1;
		}
		final Item item = hunter.getInventory().getItemByObjectId(task.itemObjectId);
		if (item == null)
		{
			return -1;
		}
		final long price = clamp(asked, task.refPrice);
		final TradeList list = hunter.getSellList();
		list.clear();
		list.setPackaged(false);
		if (PhantomCompat.addTradeItem(list, task.itemObjectId, item.getCount(), price) == null)
		{
			return -1;
		}
		list.setTitle("Для " + buyer.getName());
		task.price = price;
		hunter.broadcastUserInfo();
		hunter.broadcastPacket(new PrivateStoreMsgSell(hunter));
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " sets the price for " + buyer.getName() + ": " + task.itemName + " " + price + (price != asked ? " (asked " + asked + ")" : "") + ".");
		return price;
	}

	/**
	 * Лавка на месте: купили — итог с выручкой; время вышло, напали, кто-то закрыл — свернуться.
	 * @return {@code true}, пока сидит в лавке: остальной тик ждёт
	 */
	private boolean tradeTick(Player hunter, TradeTask task, long now)
	{
		final Item item = hunter.getInventory().getItemByObjectId(task.itemObjectId);
		final long left = item != null ? item.getCount() : 0;
		final boolean open = hunter.getPrivateStoreType() == PrivateStoreType.SELL;
		if (left < task.count)
		{
			closeStore(hunter);
			_trades.remove(hunter.getObjectId());
			task.choice.result(true, "купили: +" + PhantomHunterManager.money(Math.max(0, hunter.getAdena() - task.adenaBefore)) + " адены");
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " sold " + task.itemName + " x" + (task.count - left) + " for " + task.price + " each; " + hunter.getAdena() + " adena.");
			return false;
		}
		// Боевая стойка после последнего удара (isInCombat) — не нападение: напали — это моб бьёт его сейчас.
		final String why = !open ? "лавку закрыли" : hunter.isDead() || !attackers(hunter).isEmpty() ? "напали мобы" : (now > task.until) ? "не купил" : null;
		if (why == null)
		{
			return true;
		}
		closeStore(hunter);
		_trades.remove(hunter.getObjectId());
		task.choice.result(false, why);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " closes the store for the player: " + why + ".");
		return false;
	}

	private static void closeStore(Player hunter)
	{
		hunter.getSellList().clear();
		if (hunter.getPrivateStoreType() != PrivateStoreType.NONE)
		{
			hunter.setPrivateStoreType(PrivateStoreType.NONE);
			hunter.broadcastUserInfo();
		}
		if (hunter.isSitting())
		{
			hunter.standUp();
		}
	}

	/**
	 * @return цена из ответа модели: «25000», «25к», «25k», «1.2кк» — в адене; не число — 0
	 */
	static long parsePrice(String text)
	{
		final String value = text.strip().toLowerCase(Locale.ROOT).replace(" ", "").replace(',', '.');
		final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("^(\\d+(?:\\.\\d+)?)(кк|kk|к|k|м|m)?").matcher(value);
		if (!matcher.find())
		{
			return 0;
		}
		final double number = Double.parseDouble(matcher.group(1));
		final String suffix = matcher.group(2) == null ? "" : matcher.group(2);
		final double factor = suffix.isEmpty() ? 1 : (suffix.equals("к") || suffix.equals("k")) ? 1000 : 1000000;
		return Math.round(number * factor);
	}

	private static long clamp(long asked, long reference)
	{
		if (reference <= 0)
		{
			return asked;
		}
		return Math.max(Math.round(reference * TRADE_MIN), Math.min(Math.round(reference * TRADE_MAX), asked));
	}

	// ---------- Тик ----------

	/**
	 * Тик охотника на споте: помощь и лавка для живого по решению стратега, делёж спота.
	 * @return {@code true}, пока помогает или сидит в лавке: остальной тик охотника ждёт
	 */
	boolean tick(Player hunter, HuntSpot spot, long now)
	{
		final HelpTask help = _helps.get(hunter.getObjectId());
		if ((help != null) && helpTick(hunter, help, now))
		{
			return true;
		}
		final TradeTask trade = _trades.get(hunter.getObjectId());
		if ((trade != null) && tradeTick(hunter, trade, now))
		{
			return true;
		}
		shareTick(hunter, spot, now);
		return false;
	}

	/**
	 * Охотник ушёл из мира, умер, сменил фазу: недоделанное — с итогом «не дошло до дела», лавку — свернуть.
	 */
	void drop(Player hunter)
	{
		final HelpTask help = _helps.remove(hunter.getObjectId());
		if (help != null)
		{
			help.choice.result(help.casts > 0, help.casts > 0 ? "" : PhantomHunterStrategy.NOT_DONE);
		}
		final TradeTask trade = _trades.remove(hunter.getObjectId());
		if (trade != null)
		{
			closeStore(hunter);
			trade.choice.result(false, PhantomHunterStrategy.NOT_DONE);
		}
		final Invite invite = _invites.remove(hunter.getObjectId());
		if (invite != null)
		{
			invite.choice().result(false, "ушёл со спота");
		}
	}

	/**
	 * @return помогает или сидит в лавке для живого
	 */
	boolean busy(Player hunter)
	{
		return _helps.containsKey(hunter.getObjectId()) || _trades.containsKey(hunter.getObjectId());
	}

	/**
	 * Мысль модели — ему вслух в общий чат рядом (обращена к нему); пустая — молча.
	 */
	private static void say(Player hunter, Choice choice)
	{
		if (!choice.thought.isEmpty())
		{
			PhantomChat.getInstance().hunterSay(hunter, choice.thought, "near");
		}
	}

	private static String normalize(String name)
	{
		return name == null ? "" : name.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
	}

	public static PhantomNear getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomNear INSTANCE = new PhantomNear();
	}
}
