package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.CharInfoTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: отношения охотника к живым игрокам (патч 0032, docs/PHANTOMS.md, раздел 4.13; приём L2Solo — {@code RelationshipLayers}, {@code CombatHelpMemory}).<br>
 * Обида и благодарность копятся из поступков и остывают вдвое за {@link PhantomHuntersConfig#HUNTER_RELATION_HALF_LIFE} часов игры охотника — пока сервер выключен, ничего не остывает.
 * Остывание считается при чтении, без таймеров. Хранится в переменной охотника {@value #VARIABLE}: до {@value #ENTRIES} пар
 * «objectId:обида:благодарность:час:убийств:причины», причины — «вид@время,…» (последние {@value #REASONS}, время — секунды Unix).<br>
 * Враг — обида не меньше {@link PhantomHuntersConfig#HUNTER_RELATION_ENEMY} и больше благодарности: отказ в пати и по «лфм», «о, опять ты…», первым не заговаривает.
 * Друг — благодарность от 3 и больше обиды: заговаривает первым чаще. Помириться можно: добро перевешивает, обида остывает.<br>
 * События ловятся без правок ядра: хил и рес — глобальный {@code ON_CREATURE_SKILL_USE}, «спас» — глобальный {@code ON_ATTACKABLE_KILL} по угрозам из {@link #threat},
 * «напал», «убил» и «качались вместе» передаёт {@link PhantomHunterManager}. Одинаковое событие на пару засчитывается не чаще раза в {@value #COOLDOWN} мс; «убил» — каждый раз.<br>
 * Рес: пока на мёртвого охотника идёт каст, он не уходит в город ({@link #reviveCastUntil}); «реснул» засчитывается, когда рес принят ({@link #revived}), а не на начале каста.
 */
public class PhantomRelations
{
	private static final Logger LOGGER = Logger.getLogger(PhantomRelations.class.getName());

	static final String VARIABLE = "L2HubRelations";
	private static final int ENTRIES = 30;
	private static final int REASONS = 3;
	private static final long COOLDOWN = 1800000;
	/** Угроза: моб бил охотника на < 40 % HP не позже стольких мс назад — убивший его живой игрок «спас». */
	private static final long THREAT = 15000;
	private static final double INJURED = 0.4;
	/** Друг — благодарность от стольких очков (и больше обиды). */
	private static final double FRIEND = 3;
	/** Запас к времени каста реса: столько охотник ещё ждёт, пока придёт предложение реса. */
	private static final long REVIVE_MARGIN = 3000;
	/** Рес принят не позже стольких мс после конца каста — «реснул» тому, кто кастовал. */
	private static final long REVIVE_ACCEPT = 30000;

	public enum Kind
	{
		KILLED("killed", 3, 0),
		ATTACKED("attacked", 1, 0),
		HEALED("healed", 0, 1),
		REVIVED("revived", 0, 3),
		SAVED("saved", 0, 2),
		HUNTED("hunted", 0, 1),
		/** Отжал моба: бил моба, которого охотник бил первым (патч 0033, {@link PhantomNeighbors}). */
		CONTESTED("contested", 1, 0),
		/** На предъяву за моба ответил «сорян» и ушёл на другой спот (патч 0037) — гасит «отжал моба». */
		APOLOGIZED("apologized", 0, 1),
		/** На предъяву за моба послал (патч 0037) — ещё минус очко. */
		INSULTED("insulted", 1, 0),
		/** Победил охотника на дуэли (раздел 4.40): уважение и чуть-чуть обиды. */
		DUEL_WIN("duel_win", 0.5, 1),
		/** Проиграл охотнику дуэль: хороший бой. */
		DUEL_LOSS("duel_loss", 0, 1);

		final String key;
		final double grudge;
		final double gratitude;

		Kind(String key, double grudge, double gratitude)
		{
			this.key = key;
			this.grudge = grudge;
			this.gratitude = gratitude;
		}

		static Kind of(String key)
		{
			for (Kind kind : values())
			{
				if (kind.key.equals(key))
				{
					return kind;
				}
			}
			return null;
		}
	}

	static class Relation
	{
		double grudge;
		double gratitude;
		/** Часы игры охотника, на которые посчитаны обида и благодарность. */
		double hour;
		int kills;
		/** Последние причины: вид и время (секунды Unix), свежие первыми. */
		final Deque<Object[]> reasons = new ArrayDeque<>();

		double grudgeAt(double now)
		{
			return decay(grudge, now - hour);
		}

		double gratitudeAt(double now)
		{
			return decay(gratitude, now - hour);
		}

		long lastAt()
		{
			return reasons.isEmpty() ? 0 : (long) reasons.peekFirst()[1];
		}
	}

	/** Охотник (objectId) → игрок (objectId) → отношение; читается из переменной при первом обращении. */
	private final Map<Integer, Map<Integer, Relation>> _relations = new ConcurrentHashMap<>();
	/** «охотник:игрок:вид» → когда засчитано последний раз, мс. */
	private final Map<String, Long> _cooldowns = new ConcurrentHashMap<>();
	/** Моб (objectId) → {охотник, когда бил его на < 40 % HP}. */
	private final Map<Integer, long[]> _threats = new ConcurrentHashMap<>();
	/** Мёртвый охотник (objectId) → {кто кастует на него рес, когда каст кончится + запас}. */
	private final Map<Integer, long[]> _revives = new ConcurrentHashMap<>();
	private boolean _started;

	protected PhantomRelations()
	{
	}

	/**
	 * Глобальные слушатели хила, реса и убийства мобов — один раз, из {@link PhantomHunterManager#start()}.
	 */
	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_CREATURE_SKILL_USE, (OnCreatureSkillUse event) -> onSkillUse(event), this));
		PhantomCompat.addAttackableKillListener(this, this::onAttackableKill);
	}

	// ---------- События ----------

	/**
	 * Засчитать поступок игрока по отношению к охотнику. Запись в базу — в фоне.
	 * @param hunter охотник
	 * @param player живой игрок
	 * @param kind что сделал
	 * @return сколько раз игрок убивал охотника (после события)
	 */
	public int record(Player hunter, Player player, Kind kind)
	{
		// Тот, о ком помнят, — живой игрок или другой охотник (патч 0033); напарники из .buddy — нет.
		if ((hunter == null) || (player == null) || (hunter == player) || (PhantomManager.getInstance().isPhantom(player) && !isHunter(player)))
		{
			return 0;
		}
		// Выключено — как раньше: копятся только убийства.
		if (!PhantomHuntersConfig.HUNTER_RELATIONS && (kind != Kind.KILLED))
		{
			return kills(hunter, player);
		}

		final long now = System.currentTimeMillis();
		final String cooldownKey = hunter.getObjectId() + ":" + player.getObjectId() + ":" + kind.key;
		final boolean counts = (kind == Kind.KILLED) || (kind == Kind.HUNTED) || (now - _cooldowns.getOrDefault(cooldownKey, 0L)) >= COOLDOWN;
		if (!counts)
		{
			return kills(hunter, player);
		}
		_cooldowns.put(cooldownKey, now);

		final double hour = PhantomHunterManager.getInstance().playedHours(hunter);
		final Map<Integer, Relation> map = relations(hunter);
		final String value;
		final Relation relation;
		synchronized (map)
		{
			relation = map.computeIfAbsent(player.getObjectId(), id -> new Relation());
			apply(relation, kind, hour, now / 1000);
			trim(map, hour);
			value = serialize(map);
		}

		hunter.getVariables().set(VARIABLE, value);
		ThreadPool.execute(() -> hunter.getVariables().storeMe());
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " " + kind.key + " " + hunter.getName() + " — обида " + format(relation.grudge) + ", благодарность " + format(relation.gratitude) + (relation.kills > 0 ? ", убийств " + relation.kills : "") + ".");
		return relation.kills;
	}

	/**
	 * Моб бьёт охотника на < 40 % HP (из слушателя урона охотника): если его убьёт живой игрок в ближайшие {@value #THREAT} мс — «спас».
	 */
	void threat(Creature attacker, Player hunter)
	{
		if (!PhantomHuntersConfig.HUNTER_RELATIONS || !(attacker instanceof Attackable) || hunter.isDead() || (hunter.getCurrentHp() >= (hunter.getMaxHp() * INJURED)))
		{
			return;
		}

		final long now = System.currentTimeMillis();
		if (_threats.size() > 256)
		{
			_threats.values().removeIf(t -> (now - t[1]) > THREAT);
		}
		_threats.put(attacker.getObjectId(), new long[]
		{
			hunter.getObjectId(),
			now
		});
	}

	/**
	 * Каст живого игрока: хил раненого охотника в бою или рес мёртвого. Глобальный слушатель — на каждый каст на сервере, поэтому сначала дешёвый отсев.
	 */
	private void onSkillUse(OnCreatureSkillUse event)
	{
		// Без отношений тоже: каст реса держит охотника на месте ({@link #reviveCastUntil}); хил record() сам пропустит.
		final Creature caster = event.getCaster();
		if ((caster == null) || !caster.isPlayer())
		{
			return;
		}

		final Skill skill = event.getSkill();
		final boolean heal = skill.hasEffectType(EffectType.HEAL);
		final boolean revive = skill.hasEffectType(EffectType.RESURRECTION);
		// Хилит и ресает живой игрок или охотник-хилер ({@link PhantomNeighbors}, патч 0033).
		if ((!heal && !revive) || (PhantomManager.getInstance().isPhantom(caster.asPlayer()) && !isHunter(caster.asPlayer())))
		{
			return;
		}

		final Player helper = caster.asPlayer();
		final List<WorldObject> all = PhantomCompat.skillTargets(event);

		for (WorldObject target : all)
		{
			if (!(target instanceof Player) || (target == helper) || !isHunter((Player) target))
			{
				continue;
			}

			final Player hunter = (Player) target;
			if (revive && hunter.isDead())
			{
				// «Реснул» — только когда рес принят ({@link #revived}); пока идёт каст, охотник не уходит в город.
				_revives.put(hunter.getObjectId(), new long[]
				{
					helper.getObjectId(),
					System.currentTimeMillis() + skill.getHitTime() + REVIVE_MARGIN
				});
			}
			else if (heal && !hunter.isDead() && hunter.isInCombat() && (hunter.getCurrentHp() < (hunter.getMaxHp() * INJURED)))
			{
				ThreadPool.execute(() -> record(hunter, helper, Kind.HEALED));
			}
		}
	}

	/**
	 * @param hunter мёртвый охотник
	 * @return до какого времени (мс) на него идёт каст реса с запасом; 0 — никто не ресает
	 */
	public long reviveCastUntil(Player hunter)
	{
		final long[] revive = _revives.get(hunter.getObjectId());
		return revive != null ? revive[1] : 0;
	}

	/**
	 * Охотник принял рес: «реснул» — тому, кто на него кастовал (если каст был только что). Предложение без каста, которого мы видели, — без благодарности.
	 * @param hunter охотник
	 */
	public void revived(Player hunter)
	{
		final long[] revive = _revives.remove(hunter.getObjectId());
		if ((revive == null) || (System.currentTimeMillis() > (revive[1] + REVIVE_ACCEPT)))
		{
			return;
		}

		final Player reviver = World.getPlayer((int) revive[0]);
		if (reviver != null)
		{
			record(hunter, reviver, Kind.REVIVED);
		}
	}

	/**
	 * Охотник ушёл в город без реса: начатый каст не засчитывается.
	 * @param hunter охотник
	 */
	public void clearRevive(Player hunter)
	{
		_revives.remove(hunter.getObjectId());
	}

	/**
	 * Живой игрок убил моба: если тот только что добивал охотника — «спас».
	 */
	private void onAttackableKill(Player killer, Attackable target)
	{
		if (_threats.isEmpty() || (target == null))
		{
			return;
		}

		final long[] threat = _threats.remove(target.getObjectId());
		if ((threat == null) || (killer == null) || ((System.currentTimeMillis() - threat[1]) > THREAT) || (PhantomManager.getInstance().isPhantom(killer) && !isHunter(killer)))
		{
			return;
		}

		final Player hunter = World.getPlayer((int) threat[0]);
		if ((hunter != null) && !hunter.isDead() && (hunter != killer))
		{
			record(hunter, killer, Kind.SAVED);
		}
	}

	// ---------- Чтение ----------

	/**
	 * @return враг: обида не меньше {@link PhantomHuntersConfig#HUNTER_RELATION_ENEMY} и больше благодарности (выключено — убивал хоть раз)
	 */
	public boolean isEnemy(Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		if (relation == null)
		{
			return false;
		}
		if (!PhantomHuntersConfig.HUNTER_RELATIONS)
		{
			return relation.kills > 0;
		}

		return enemy(relation, PhantomHunterManager.getInstance().playedHours(hunter));
	}

	/**
	 * @return друг: благодарность от 3 и больше обиды
	 */
	public boolean isFriend(Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		return (relation != null) && friend(relation, PhantomHunterManager.getInstance().playedHours(hunter));
	}

	/**
	 * @return обида сейчас (с остыванием); отношения выключены — 0 (патч 0037: порог махача)
	 */
	public double grudge(Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		return (relation == null) || !PhantomHuntersConfig.HUNTER_RELATIONS ? 0 : relation.grudgeAt(PhantomHunterManager.getInstance().playedHours(hunter));
	}

	/**
	 * @return благодарность сейчас (с остыванием)
	 */
	public double gratitude(Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		return (relation == null) || !PhantomHuntersConfig.HUNTER_RELATIONS ? 0 : relation.gratitudeAt(PhantomHunterManager.getInstance().playedHours(hunter));
	}

	/**
	 * @return сколько раз игрок убивал охотника
	 */
	public int kills(Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		return relation != null ? relation.kills : 0;
	}

	/**
	 * @return множитель шанса заговорить первым: другу — 1 + благодарность / 3, не больше 2
	 */
	public double initiativeFactor(Player hunter, Player player)
	{
		return isFriend(hunter, player) ? Math.min(2, 1 + (gratitude(hunter, player) / FRIEND)) : 1;
	}

	/**
	 * @return есть ли у охотника хоть один враг — быстрый отсев для «обидчик мимо»
	 */
	public boolean hasEnemies(Player hunter)
	{
		final Map<Integer, Relation> map = relations(hunter);
		if (map.isEmpty())
		{
			return false;
		}

		final double hour = PhantomHunterManager.getInstance().playedHours(hunter);
		synchronized (map)
		{
			for (Relation relation : map.values())
			{
				if (enemy(relation, hour))
				{
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Для мозгов — в блок игрока: {@code "relation":{"grudge":…,"gratitude":…,"kills":…,"enemy":…,"friend":…,"reasons":[{"type":…,"ago":с},…]},}; нет отношения — ничего.
	 */
	void json(StringBuilder sb, Player hunter, Player player)
	{
		final Relation relation = get(hunter, player);
		if (relation == null)
		{
			return;
		}

		final double hour = PhantomHunterManager.getInstance().playedHours(hunter);
		sb.append("\"relation\":{\"grudge\":").append(format(relation.grudgeAt(hour))).append(",\"gratitude\":").append(format(relation.gratitudeAt(hour)));
		sb.append(",\"kills\":").append(relation.kills).append(",\"enemy\":").append(isEnemy(hunter, player)).append(",\"friend\":").append(isFriend(hunter, player)).append(",\"reasons\":[");
		reasons(sb, relation, true);
		sb.append("]},");
	}

	/**
	 * Для лаунчера ({@code L2HubHunters.json}) и стратега: самые сильные отношения охотника.
	 * @param max сколько
	 */
	void top(StringBuilder sb, Player hunter, int max)
	{
		final Map<Integer, Relation> map = relations(hunter);
		final double hour = PhantomHunterManager.getInstance().playedHours(hunter);
		final List<Map.Entry<Integer, Relation>> list;
		synchronized (map)
		{
			list = new ArrayList<>(map.entrySet());
		}
		list.sort((a, b) -> Double.compare(score(b.getValue(), hour), score(a.getValue(), hour)));

		sb.append('[');
		int count = 0;
		for (Map.Entry<Integer, Relation> entry : list)
		{
			final String name = CharInfoTable.getInstance().getNameById(entry.getKey());
			if ((name == null) || (count >= max))
			{
				continue;
			}

			final Relation relation = entry.getValue();
			final double grudge = relation.grudgeAt(hour);
			final double gratitude = relation.gratitudeAt(hour);
			sb.append(count > 0 ? "," : "").append('{');
			FakePlayerBrain.field(sb, "name", name).append(',');
			FakePlayerBrain.field(sb, "kind", enemy(relation, hour) ? "enemy" : friend(relation, hour) ? "friend" : "neutral").append(',');
			sb.append("\"grudge\":").append(format(grudge)).append(",\"gratitude\":").append(format(gratitude)).append(",\"kills\":").append(relation.kills);
			sb.append(",\"online\":").append(World.getPlayer(entry.getKey()) != null).append(",\"reasons\":[");
			reasons(sb, relation, false);
			sb.append("]}");
			count++;
		}
		sb.append(']');
	}

	// ---------- Хранение ----------

	/**
	 * @return охотник-фантом (не напарник из .buddy)
	 */
	static boolean isHunter(Player player)
	{
		if (!PhantomManager.getInstance().isPhantom(player))
		{
			return false;
		}

		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && template.isHunter();
	}

	private Relation get(Player hunter, Player player)
	{
		if ((hunter == null) || (player == null))
		{
			return null;
		}

		final Map<Integer, Relation> map = relations(hunter);
		synchronized (map)
		{
			return map.get(player.getObjectId());
		}
	}

	private Map<Integer, Relation> relations(Player hunter)
	{
		return _relations.computeIfAbsent(hunter.getObjectId(), id -> load(hunter));
	}

	/**
	 * Из переменной; её нет — перенос старых обид {@link PhantomHunterManager#GRUDGE_VARIABLE} «objectId:убийств»: каждое убийство — обида +3.
	 */
	private static Map<Integer, Relation> load(Player hunter)
	{
		final String stored = hunter.getVariables().getString(VARIABLE, "");
		if (!stored.isEmpty())
		{
			return parse(stored);
		}

		final Map<Integer, Relation> map = migrate(hunter.getVariables().getString(PhantomHunterManager.GRUDGE_VARIABLE, ""), PhantomHunterManager.getInstance().playedHours(hunter), System.currentTimeMillis() / 1000);
		if (!map.isEmpty())
		{
			hunter.getVariables().set(VARIABLE, serialize(map));
			LOGGER.info(PhantomRelations.class.getSimpleName() + ": " + hunter.getName() + " — старые обиды перенесены (" + map.size() + ").");
		}
		return map;
	}

	// ---------- Правила (статические — их проверяет тест без сервера) ----------

	/**
	 * Поступок: обида и благодарность остывают до этого часа, к ним прибавляется вес события; причина — первой.
	 */
	static void apply(Relation relation, Kind kind, double hour, long nowSeconds)
	{
		relation.grudge = relation.grudgeAt(hour) + kind.grudge;
		relation.gratitude = relation.gratitudeAt(hour) + kind.gratitude;
		relation.hour = hour;
		if (kind == Kind.KILLED)
		{
			relation.kills++;
		}
		relation.reasons.addFirst(new Object[]
		{
			kind.key,
			nowSeconds
		});
		while (relation.reasons.size() > REASONS)
		{
			relation.reasons.pollLast();
		}
	}

	/**
	 * @return враг на этот час: обида не меньше {@link PhantomHuntersConfig#HUNTER_RELATION_ENEMY} и больше благодарности; отношения выключены — убивал хоть раз
	 */
	static boolean enemy(Relation relation, double hour)
	{
		if (!PhantomHuntersConfig.HUNTER_RELATIONS)
		{
			return relation.kills > 0;
		}

		final double grudge = relation.grudgeAt(hour);
		return (grudge >= PhantomHuntersConfig.HUNTER_RELATION_ENEMY) && (grudge > relation.gratitudeAt(hour));
	}

	/**
	 * @return друг на этот час: благодарность от {@value #FRIEND} и больше обиды
	 */
	static boolean friend(Relation relation, double hour)
	{
		if (!PhantomHuntersConfig.HUNTER_RELATIONS)
		{
			return false;
		}

		final double gratitude = relation.gratitudeAt(hour);
		return (gratitude >= FRIEND) && (gratitude > relation.grudgeAt(hour));
	}

	/**
	 * Переменная {@value #VARIABLE} → пары; кривые записи пропускаются.
	 */
	static Map<Integer, Relation> parse(String stored)
	{
		final Map<Integer, Relation> map = new ConcurrentHashMap<>();
		for (String entry : stored.split(";"))
		{
			final String[] parts = entry.split(":", 6);
			if (parts.length < 5)
			{
				continue;
			}

			try
			{
				final Relation relation = new Relation();
				relation.grudge = Double.parseDouble(parts[1]);
				relation.gratitude = Double.parseDouble(parts[2]);
				relation.hour = Double.parseDouble(parts[3]);
				relation.kills = Integer.parseInt(parts[4]);
				if ((parts.length > 5) && !parts[5].isEmpty())
				{
					for (String reason : parts[5].split(","))
					{
						final String[] r = reason.split("@");
						if ((r.length == 2) && (Kind.of(r[0]) != null))
						{
							relation.reasons.addLast(new Object[]
							{
								r[0],
								Long.parseLong(r[1])
							});
						}
					}
				}
				map.put(Integer.parseInt(parts[0]), relation);
			}
			catch (NumberFormatException e)
			{
				// кривая запись — пропуск
			}
		}
		return map;
	}

	/**
	 * Старые обиды {@link PhantomHunterManager#GRUDGE_VARIABLE} «objectId:убийств;…» → пары: каждое убийство — обида +3.
	 */
	static Map<Integer, Relation> migrate(String grudges, double hour, long nowSeconds)
	{
		final Map<Integer, Relation> map = new ConcurrentHashMap<>();
		for (String entry : grudges.split(";"))
		{
			final String[] parts = entry.split(":");
			if (parts.length != 2)
			{
				continue;
			}

			try
			{
				final Relation relation = new Relation();
				relation.kills = Integer.parseInt(parts[1]);
				relation.grudge = relation.kills * Kind.KILLED.grudge;
				relation.hour = hour;
				relation.reasons.add(new Object[]
				{
					Kind.KILLED.key,
					nowSeconds
				});
				map.put(Integer.parseInt(parts[0]), relation);
			}
			catch (NumberFormatException e)
			{
				// кривая запись — пропуск
			}
		}
		return map;
	}

	/**
	 * Больше {@value #ENTRIES} пар — выбыть самым слабым: убийства и сильные чувства держатся дольше.
	 */
	static void trim(Map<Integer, Relation> map, double hour)
	{
		while (map.size() > ENTRIES)
		{
			Integer weakest = null;
			double min = Double.MAX_VALUE;
			for (Map.Entry<Integer, Relation> entry : map.entrySet())
			{
				final double score = score(entry.getValue(), hour);
				if (score < min)
				{
					min = score;
					weakest = entry.getKey();
				}
			}
			map.remove(weakest);
		}
	}

	private static double score(Relation relation, double hour)
	{
		return Math.max(relation.grudgeAt(hour), relation.gratitudeAt(hour)) + (relation.kills > 0 ? 1 : 0) + (relation.lastAt() / 1e12);
	}

	static String serialize(Map<Integer, Relation> map)
	{
		final StringBuilder sb = new StringBuilder(map.size() * 48);
		for (Map.Entry<Integer, Relation> entry : map.entrySet())
		{
			final Relation relation = entry.getValue();
			sb.append(sb.length() > 0 ? ";" : "").append(entry.getKey()).append(':').append(format(relation.grudge)).append(':').append(format(relation.gratitude));
			sb.append(':').append(String.format(Locale.ROOT, "%.3f", relation.hour)).append(':').append(relation.kills).append(':');
			boolean first = true;
			for (Object[] reason : relation.reasons)
			{
				sb.append(first ? "" : ",").append(reason[0]).append('@').append(reason[1]);
				first = false;
			}
		}
		return sb.toString();
	}

	private static void reasons(StringBuilder sb, Relation relation, boolean withAgo)
	{
		final long now = System.currentTimeMillis() / 1000;
		boolean first = true;
		for (Iterator<Object[]> it = relation.reasons.iterator(); it.hasNext();)
		{
			final Object[] reason = it.next();
			sb.append(first ? "" : ",");
			first = false;
			if (withAgo)
			{
				sb.append("{\"type\":\"").append(reason[0]).append("\",\"ago\":").append(Math.max(0, now - (long) reason[1])).append('}');
			}
			else
			{
				sb.append('"').append(reason[0]).append('"');
			}
		}
	}

	private static double decay(double value, double hours)
	{
		final int halfLife = PhantomHuntersConfig.HUNTER_RELATION_HALF_LIFE;
		return (halfLife <= 0) || (hours <= 0) ? value : value * Math.pow(0.5, hours / halfLife);
	}

	private static String format(double value)
	{
		return String.format(Locale.ROOT, "%.2f", value);
	}

	public static PhantomRelations getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomRelations INSTANCE = new PhantomRelations();
	}
}
