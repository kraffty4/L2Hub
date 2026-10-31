package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.data.sql.CharSummonTable;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: охотники-призыватели (мозги под класс, docs/PHANTOMS.md, раздел 4.37). Призыв слуги учат Human, Elven и Dark Wizard (с 20-го уровня), Warlock, Elemental и
 * Phantom Summoner, Necromancer, Artisan и Warsmith — у охотников это нюкеры и гномы, слуга им — танк и второй ствол.<br>
 * На каче: слуги нет или он погиб — призвать (вне боя, хватает кристаллов и маны; не чаще раза в {@value #RETRY} мс); слуга бьёт цель хозяина, пока тот в бою,
 * а моба, который бьёт хозяина, — сразу (сторожит); слуге мало HP или MP — Servitor Heal или Servitor Recharge; между мобами — держать на слуге свои бафы
 * (Servitor Haste, Shield, Wind Walk и другие, раздел 4.55). Кубики (рыцари Шилен и Евы, призыватели) — держать свои, перекастовывать к концу их 15 минут.
 * Кристаллы на призыв (Spirit Ore, кристаллы грейда — что берёт скилл) охотник покупает в городе вместе с сосами ({@link PhantomHunterOutfit}); кончились — идёт
 * за ними ({@link #needsCrystals}); ветеран-призыватель рождается с запасом ({@link #giveCrystals}).
 */
final class PhantomSummoner
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSummoner.class.getName());

	/**
	 * Призывы по убыванию ценности: третья профа, вторая, первая; из пары Wizard — боец, а не бафер (Kat раньше Mew, Boxer раньше Mirage, Shadow раньше Silhouette).
	 * Осадных големов и пушек Warsmith нет: они для осад. У Essence третья профа призывает Cat Emperor и Lord Raise; Elemental Unicorn Essence (эффект
	 * SummonGuardian) Mobius не делает — его нет и здесь.
	 */
	private static final int[] SUMMONS =
	{
		1406, // Summon Feline King
		1407, // Summon Magnus the Unicorn
		1408, // Summon Spectral Lord
		47901, // Summon Cat Emperor (Essence, с 76-го)
		47907, // Summon Lord Raise (Essence, с 76-го)
		1331, // Summon Feline Queen
		1332, // Summon Seraphim the Unicorn
		1333, // Summon Nightshade
		1334, // Summon Cursed Man
		1276, // Summon Kai the Cat
		1277, // Summon Merrow the Unicorn
		1278, // Summon Soulless
		1129, // Summon Reanimated Man
		1154, // Summon Corrupted Man
		1111, // Summon Kat the Cat
		1226, // Summon Boxer the Unicorn
		1128, // Summon Shadow
		1225, // Summon Mew the Cat
		1227, // Summon Mirage the Unicorn
		1228, // Summon Silhouette
		283, // Summon Dark Panther
		25 // Summon Mechanic Golem
	};
	/** Призыв не удался (нет кристаллов, маны, откат) — следующая попытка не раньше. */
	private static final long RETRY = 30000;
	/** Моб в этом радиусе бьёт охотника — не призывать: каст сорвут. */
	private static final int UNDER_ATTACK_RANGE = 900;
	/** Запас кристаллов на призыв, в призывах: слуга ещё и подъедает их сам, пока призван. */
	static final int CRYSTAL_STOCK = 30;
	/** Запас кристаллов на свой кубик, в кастах: каст раз в 14 минут — на несколько часов кача. */
	static final int CUBIC_STOCK = 20;

	private static final Map<Integer, Long> NEXT_TRY = new ConcurrentHashMap<>();
	/** У кого слуга уже был: призыв после этого — перепризыв (погиб, отозван, кончились кристаллы). */
	private static final Set<Integer> HAD = ConcurrentHashMap.newKeySet();
	/** С какого времени охотник на споте без слуги — погибшего или ещё не призванного (первый призыв после входа тоже ждёт окна не дольше URGENT_*). */
	private static final Map<Integer, Long> LOST_AT = new ConcurrentHashMap<>();
	/** Сидел — встаёт ради призыва: до этого времени тик ждёт, пока встанет, и не отдаёт хода отдыху. */
	private static final Map<Integer, Long> STANDING_UP = new ConcurrentHashMap<>();
	private static final long STAND_UP_TIME = 8000;
	/** Без слуги столько — призывает и посреди своего каста или атаки (призыв встаёт в очередь за текущим кастом). */
	private static final long URGENT_BUSY = 20000;
	/** Без слуги столько — призывает, даже когда его бьют (каст могут сбить). */
	private static final long URGENT_ATTACKED = 60000;
	/** Строка лога «слуга бьёт цель хозяина» — не чаще раза в столько на охотника (без клиента иначе не видно, работает ли слуга). */
	private static final long ASSIST_NOTE_GAP = 60000;
	private static final Map<Integer, Long> LAST_ASSIST_NOTE = new ConcurrentHashMap<>();

	/** Servitor Heal (Wizard — с 20-го, призыватели — с 40-го) и Servitor Recharge (с 25-го): цель — свой слуга, цель хозяина они не сбивают. */
	private static final int SERVITOR_HEAL = 1127;
	private static final int SERVITOR_RECHARGE = 1126;
	/** Слугу лечит, когда у него HP ниже стольких %, ману даёт — когда MP ниже стольких %. */
	private static final int HEAL_BELOW = 50;
	private static final int RECHARGE_BELOW = 30;
	/** Хил или речардж слуги — не чаще раза в столько на охотника (каст 4 с). */
	private static final long SUPPORT_GAP = 6000;
	private static final Map<Integer, Long> NEXT_SUPPORT = new ConcurrentHashMap<>();
	/** Строка лога «лечит слугу» — не чаще раза в столько на охотника, со счётом кастов за это время. */
	private static final long SUPPORT_NOTE_GAP = 60000;
	private static final Map<Integer, Long> LAST_SUPPORT_NOTE = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> SUPPORT_COUNT = new ConcurrentHashMap<>();
	/** Кубик живёт 15 минут (cubicDuration 900): свой кубик охотник кастует заново раз в столько. */
	private static final long CUBIC_REFRESH = 840000;
	/** Кубиков нет, а последний каст был раньше — умер или кубики кончились: все свои кастуются заново. */
	private static final long CUBIC_RETRY = 30000;
	/** Когда охотник кастовал кубик: ключ — objectId охотника и id скилла. */
	private static final Map<Long, Long> LAST_CUBIC = new ConcurrentHashMap<>();

	/**
	 * Бафы слуге — свои скиллы с целью «свой слуга» (SERVITOR у CT, SUMMON у Classic и Essence) и длительностью. Не держит: Servitor Empowerment и Ultimate Defense
	 * (PD_UP_SPECIAL — слуга стоит столбом), Servitor Barrier (INVINCIBILITY — на крайний случай). Из бафов одного типа — меньший id: Warrior, Wizard и Assassin
	 * Servitor (1346–1348), а не Final Servitor (1349) — они вытесняют друг друга.
	 */
	private static final Set<String> BUFF_TARGETS = Set.of("SERVITOR", "SUMMON");
	private static final Set<String> BUFF_SKIP = Set.of("NONE", "PD_UP_SPECIAL", "INVINCIBILITY");
	/** Баф, который ест кристаллы призыва, — только если после него их останется на столько призывов. */
	private static final int BUFF_CRYSTAL_RESERVE = 10;
	/** Баф слуги — не чаще раза в столько на охотника (каст до 4 с). */
	private static final long BUFF_GAP = 4000;
	private static final Map<Integer, Long> NEXT_BUFF = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> LAST_BUFF_NOTE = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> BUFF_COUNT = new ConcurrentHashMap<>();
	/** Сторожит: моба, который бьёт хозяина в этом радиусе, слуга бьёт первым. */
	private static final int GUARD_RANGE = 900;
	private static final Map<Integer, Long> LAST_GUARD_NOTE = new ConcurrentHashMap<>();
	/** Кристаллов нет, слуги нет: в город за ними — если адены хватит на столько призывов. */
	private static final int CRYSTAL_TRIP_MIN = 5;

	/**
	 * Самопроверка SUMMON ({@code HuntersSelfTest = SUMMON}): у каждого призывателя на споте, чей слуга прожил {@value #SELF_TEST_ALIVE} мс, — бафы слуги в лог
	 * и смерть слуги; ждём перепризыва не дольше {@value #SELF_TEST_RESUMMON} мс. Итог — через {@value #SELF_TEST_WINDOW} мс после включения.
	 */
	private static final long SELF_TEST_ALIVE = 90000;
	private static final long SELF_TEST_RESUMMON = 180000;
	private static final long SELF_TEST_WINDOW = 840000;
	private static volatile long _selfTestUntil;
	private static final Map<Integer, Long> TEST_ALIVE_SINCE = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> TEST_KILLED_AT = new ConcurrentHashMap<>();
	private static final Map<Integer, Integer> TEST_KILLED_SUMMON = new ConcurrentHashMap<>();
	private static final Map<Integer, String> TEST_RESULT = new ConcurrentHashMap<>();
	/** Убийца слуги в самопроверке — моб в этом радиусе от слуги; нет такого — подождать. */
	private static final int SELF_TEST_FOE_RANGE = 1500;
	/** Строка «почему ещё не призвал» — не чаще раза в столько на призывателя. */
	private static final long SELF_TEST_WAIT_NOTE = 20000;
	private static final Map<Integer, Long> TEST_WAIT_NOTE = new ConcurrentHashMap<>();
	private static final Map<Integer, String> TEST_LAST_WAIT = new ConcurrentHashMap<>();
	/** Тика призывателя не было дольше — он был вне спота: это время в ожидание перепризыва не входит. */
	private static final long SELF_TEST_OFF_SPOT = 10000;
	private static final Map<Integer, Long> TEST_LAST_TICK = new ConcurrentHashMap<>();
	private static final Map<Integer, Long> TEST_OFF_SPOT = new ConcurrentHashMap<>();

	private PhantomSummoner()
	{
	}

	/**
	 * Включить самопроверку SUMMON (раздел 4.55).
	 */
	static void selfTest()
	{
		_selfTestUntil = System.currentTimeMillis() + SELF_TEST_WINDOW;
		TEST_ALIVE_SINCE.clear();
		TEST_KILLED_AT.clear();
		TEST_KILLED_SUMMON.clear();
		TEST_RESULT.clear();
		TEST_WAIT_NOTE.clear();
		TEST_LAST_WAIT.clear();
		TEST_LAST_TICK.clear();
		TEST_OFF_SPOT.clear();
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: on for " + (SELF_TEST_WINDOW / 60000) + " min — every summoner on his spot loses his servitor once and must summon again.");
	}

	/**
	 * Ветеран-призыватель ({@link PhantomManager}): запас кристаллов на призыв и кубики ({@link #crystalNeeds}), как после закупки в городе, — иначе слуги нет
	 * до первого похода за сосами.
	 * @return сколько дал; 0 — не призыватель или призыв без кристаллов
	 */
	static long giveCrystals(Player player)
	{
		long total = 0;
		for (Map.Entry<Integer, Long> need : crystalNeeds(player).entrySet())
		{
			PhantomCompat.addItem(player, ItemProcessType.REWARD, need.getKey(), need.getValue(), null);
			total += need.getValue();
		}
		return total;
	}

	/**
	 * @return призыватель без слуги и без кристаллов хоть на один призыв, а адены хватит на {@value #CRYSTAL_TRIP_MIN} — пора в город ({@link PhantomHunterManager})
	 */
	static boolean needsCrystals(Player player)
	{
		final Skill skill = summonSkill(player);
		if ((skill == null) || (skill.getItemConsumeId() <= 0))
		{
			return false;
		}
		final Summon servitor = PhantomCompat.servitor(player);
		if (((servitor != null) && !servitor.isDead()) || (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) >= skill.getItemConsumeCount()))
		{
			return false;
		}
		return player.getAdena() >= (PhantomHunterOutfit.price(skill.getItemConsumeId()) * Math.max(1, skill.getItemConsumeCount()) * CRYSTAL_TRIP_MIN);
	}

	/**
	 * @return лучший выученный призыв слуги или {@code null} — не призыватель
	 */
	static Skill summonSkill(Player player)
	{
		for (int id : SUMMONS)
		{
			final Skill skill = player.getKnownSkill(id);
			if (skill != null)
			{
				return skill;
			}
		}
		return null;
	}

	/**
	 * Тик охотника на споте.
	 * @param player охотник
	 * @param now текущее время
	 * @return {@code true} — начал призыв: правила кача на этот тик не нужны
	 */
	static boolean tick(Player player, long now)
	{
		Summon servitor = PhantomCompat.servitor(player);
		// Слугу, сохранённого при выходе (character_summons, RestoreServitorOnReconnect), Mobius возвращает только по первому действию клиента
		// (Player.onActionRequest), а у фантома клиента нет. Пока запись есть, новый призыв запрещён (ConditionPlayerCanSummon) — до 2026-10-24 охотник,
		// ушедший в рестарт со слугой, оставался без него навсегда. Вернуть сохранённого.
		if ((servitor == null) && !player.isDead() && (now >= NEXT_TRY.getOrDefault(player.getObjectId(), 0L)) && CharSummonTable.getInstance().getServitors().containsKey(player.getObjectId()))
		{
			NEXT_TRY.put(player.getObjectId(), now + RETRY);
			CharSummonTable.getInstance().restoreServitor(player);
			servitor = PhantomCompat.servitor(player);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") " + (servitor != null ? "gets back his saved " + servitor.getName() + " (servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%)" : "could not get back his saved servitor") + ".");
		}
		final boolean alive = (servitor != null) && !servitor.isDead();
		if (selfTestTick(player, servitor, alive, now))
		{
			return false;
		}
		if (alive)
		{
			HAD.add(player.getObjectId());
			LOST_AT.remove(player.getObjectId());
			STANDING_UP.remove(player.getObjectId());
			if (support(player, servitor, now))
			{
				return true;
			}
			if (!guard(player, servitor, now))
			{
				assist(player, servitor, now);
			}
		}
		else
		{
			// И до первого призыва: Fialka на Epilogue 2026-10-29 одиннадцать минут кастовала без перерыва и без слуги — срочность считалась только после потери.
			LOST_AT.putIfAbsent(player.getObjectId(), now);
		}

		// Не «вне боя» (isInCombat): на споте с AutoPlay охотник из него почти не выходит — прогон H5 2026-10-22, ни одного призыва за 15 минут.
		// Призывает слугу и кубики, бафает слугу, когда его никто не бьёт и сам не бьёт и не кастует — между мобами. Слуга погиб давно — призывает и посреди
		// своего боя (раздел 4.55): у мага на AutoPlay каст идёт за кастом, и Sumrak в прогоне 2026-10-28 три минуты не нашёл окна. Так же — первый призыв.
		final long lost = alive ? 0 : now - LOST_AT.getOrDefault(player.getObjectId(), now);
		final boolean busy = player.isCastingNow() || player.isAttackingNow();
		final boolean attacked = underAttack(player);
		if (player.isDead() || (busy && (lost < URGENT_BUSY)) || (attacked && (lost < URGENT_ATTACKED)))
		{
			testWait(player, now, player.isDead() ? "he is dead" : (busy && (lost < URGENT_BUSY)) ? (player.isCastingNow() ? "casting" : "attacking") : "under attack");
			return false;
		}
		final boolean urgent = busy || attacked;
		if (!urgent && cubic(player, now))
		{
			return true;
		}
		if (alive && buff(player, servitor, now))
		{
			return true;
		}
		if (alive)
		{
			return false;
		}
		// Тело слуги лежит до распада, и пока оно есть, призвать Mobius не даст («уже есть слуга») — подождать, не тратя попытку.
		if (servitor != null)
		{
			testWait(player, now, "the body of his " + servitor.getName() + " is still there");
			return false;
		}
		if (now < NEXT_TRY.getOrDefault(player.getObjectId(), 0L))
		{
			testWait(player, now, "next try in " + ((NEXT_TRY.get(player.getObjectId()) - now) / 1000) + " s");
			return false;
		}
		final Skill skill = summonSkill(player);
		if (skill == null)
		{
			return false;
		}
		// Нечем призвать: кристаллы купит в городе; маны мало или откат — в следующий раз. Сидя откат — только по отметке отката: у Classic и Essence сидящий —
		// hasBlockActions, isSkillDisabled у него всегда true, и охотник не вставал, пока не отдохнёт сам (Essence 2026-10-29: SUMMON 0 из 2, слуга вернулся через 6–8 минут).
		final boolean disabled = player.isSitting() ? player.hasSkillReuse(skill.getReuseHashCode()) : player.isSkillDisabled(skill);
		if (((skill.getItemConsumeId() > 0) && (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount())) || disabled || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())))
		{
			NEXT_TRY.put(player.getObjectId(), now + RETRY);
			testWait(player, now, "can't cast " + skill.getName() + " (crystals " + player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) + " of " + skill.getItemConsumeCount() + ", disabled " + disabled + ", sitting " + player.isSitting() + ", MP " + (int) player.getCurrentMp() + " of " + (skill.getMpConsume() + skill.getMpInitialConsume()) + ")");
			return false;
		}
		// Сидит (отдыхает) — встать и, не тратя попытку, призвать, как только встанет. До 2026-10-29 попытка уходила на «встать», а за RETRY отдых сажал его
		// обратно: Sumrak на Epilogue пять минут вставал и садился без слуги.
		if (player.isSitting())
		{
			final Long standing = STANDING_UP.get(player.getObjectId());
			if (standing == null)
			{
				STANDING_UP.put(player.getObjectId(), now + STAND_UP_TIME);
				player.standUp();
				testWait(player, now, "stands up to summon");
				return true;
			}
			if (now < standing)
			{
				// Mobius не даёт встать, пока идёт анимация «сесть» (2,5 с): первый вызов мог пропасть. Если уже встаёт, повтор ничего не делает.
				player.standUp();
				return true;
			}
			// Так и не встал — попытка уходит, пусть отдыхает.
			STANDING_UP.remove(player.getObjectId());
			NEXT_TRY.put(player.getObjectId(), now + RETRY);
			testWait(player, now, "can't stand up to summon");
			return false;
		}
		STANDING_UP.remove(player.getObjectId());
		NEXT_TRY.put(player.getObjectId(), now + RETRY);
		// Посреди своего каста Mobius ставит призыв в очередь (setQueuedSkill) и кастует его следом — useMagic при этом вернёт false.
		final boolean queued = player.isCastingNow();
		if (!PhantomCompat.useMagic(player, skill, false, false) && !queued)
		{
			testWait(player, now, "the server refused " + skill.getName() + " (intention " + player.getAI().getIntention() + ")");
			return false;
		}
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") " + (queued ? "queues " : "summons ") + skill.getName() + (HAD.contains(player.getObjectId()) ? " again (the servitor is gone" + (urgent ? " for " + (lost / 1000) + " s, in the middle of a fight" : "") + ")" : urgent ? " in the middle of a fight (no servitor for " + (lost / 1000) + " s)" : "") + ".");
		return true;
	}

	/**
	 * Лечит слугу (HP ниже {@value #HEAL_BELOW} %) или даёт ему ману (MP ниже {@value #RECHARGE_BELOW} %) — и в бою: слуга держит мобов на себе. Сидя не встаёт: сидит,
	 * когда у самого мало маны.
	 * @return {@code true} — начал каст
	 */
	private static boolean support(Player player, Summon servitor, long now)
	{
		if (player.isDead() || player.isSitting() || player.isCastingNow() || (now < NEXT_SUPPORT.getOrDefault(player.getObjectId(), 0L)))
		{
			return false;
		}
		final int hp = (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp()));
		final int mp = servitor.getMaxMp() > 0 ? (int) ((servitor.getCurrentMp() * 100) / servitor.getMaxMp()) : 100;
		final Skill heal = hp < HEAL_BELOW ? player.getKnownSkill(SERVITOR_HEAL) : null;
		final Skill skill = heal != null ? heal : mp < RECHARGE_BELOW ? player.getKnownSkill(SERVITOR_RECHARGE) : null;
		if ((skill == null) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())) || ((skill.getCastRange() > 0) && (player.calculateDistance2D(servitor) > skill.getCastRange())))
		{
			return false;
		}
		NEXT_SUPPORT.put(player.getObjectId(), now + SUPPORT_GAP);
		if (!PhantomCompat.useMagic(player, skill, false, false))
		{
			return false;
		}
		final int count = SUPPORT_COUNT.merge(player.getObjectId(), 1, Integer::sum);
		final Long last = LAST_SUPPORT_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= SUPPORT_NOTE_GAP))
		{
			LAST_SUPPORT_NOTE.put(player.getObjectId(), now);
			SUPPORT_COUNT.put(player.getObjectId(), 0);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " casts " + skill.getName() + " on his " + servitor.getName() + " (servitor HP " + hp + "%, MP " + mp + "%" + (count > 1 ? ", " + count + " casts since the last note" : "") + ").");
		}
		return true;
	}

	/**
	 * Кубики (Shillien и Temple Knight, Warlock, Elemental и Phantom Summoner — с 40-го, Smart Cubic третьей профы — с 80-го): держит свои, каст — между мобами.
	 * Каждый свой — заново раз в {@value #CUBIC_REFRESH} мс, к концу его 15 минут; кубиков нет, а последний каст был раньше {@value #CUBIC_RETRY} мс — все заново.
	 * Выучил больше, чем держит Cubic Mastery, — новый вытесняет старший, и они сменяются раз в те же 14 минут.
	 * @return {@code true} — начал каст
	 */
	private static boolean cubic(Player player, long now)
	{
		final List<Skill> cubics = new ArrayList<>();
		long latest = 0;
		for (Skill skill : cubicSkills(player))
		{
			// Нечем кастовать — не пробовать: у Interlude и Epilogue кубики едят D-кристаллы, а призыв — C (прогон Epilogue 2026-10-29: без D-кристаллов
			// каст молча не проходил и повторялся раз в 30 с, 22–24 раза за 14 минут).
			if ((skill.getItemConsumeId() > 0) && (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < skill.getItemConsumeCount()))
			{
				continue;
			}
			cubics.add(skill);
			latest = Math.max(latest, LAST_CUBIC.getOrDefault(cubicKey(player, skill), 0L));
		}
		if (cubics.isEmpty() || player.isSitting())
		{
			return false;
		}
		final boolean gone = (latest > 0) && player.getCubics().isEmpty() && ((now - latest) >= CUBIC_RETRY);
		if (gone)
		{
			for (Skill skill : cubics)
			{
				LAST_CUBIC.remove(cubicKey(player, skill));
			}
		}
		Skill pick = null;
		long pickLast = Long.MAX_VALUE;
		for (Skill skill : cubics)
		{
			final long last = LAST_CUBIC.getOrDefault(cubicKey(player, skill), 0L);
			if (((now - last) >= CUBIC_REFRESH) && (last < pickLast) && !player.isSkillDisabled(skill) && (player.getCurrentMp() >= (skill.getMpConsume() + skill.getMpInitialConsume())))
			{
				pick = skill;
				pickLast = last;
			}
		}
		if (pick == null)
		{
			return false;
		}
		LAST_CUBIC.put(cubicKey(player, pick), now);
		if (!PhantomCompat.useMagic(player, pick, false, false))
		{
			return false;
		}
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") summons " + pick.getName() + (gone ? " again (the cubics are gone)" : pickLast > 0 ? " again (refresh)" : "") + ".");
		return true;
	}

	private static long cubicKey(Player player, Skill skill)
	{
		return (((long) player.getObjectId()) << 32) | skill.getId();
	}

	/**
	 * @return свои кубики — «Summon … Cubic»; «Mass Summon Storm Cubic» Warlock'а — на пати, его не держим
	 */
	private static List<Skill> cubicSkills(Player player)
	{
		final List<Skill> cubics = new ArrayList<>();
		for (Skill skill : player.getAllSkills())
		{
			if (skill.isActive() && skill.getName().startsWith("Summon ") && skill.getName().endsWith(" Cubic"))
			{
				cubics.add(skill);
			}
		}
		return cubics;
	}

	/**
	 * Что едят призыв слуги и свои кубики и сколько держать: на {@value #CRYSTAL_STOCK} призывов и {@value #CUBIC_STOCK} кастов каждого кубика. Это охотник
	 * не сдаёт и докупает в городе ({@link PhantomHunterOutfit}), с этим рождается ветеран ({@link #giveCrystals}).
	 * @return id предмета → запас
	 */
	static Map<Integer, Long> crystalNeeds(Player player)
	{
		final Map<Integer, Long> needs = new HashMap<>();
		final Skill summon = summonSkill(player);
		if ((summon != null) && (summon.getItemConsumeId() > 0))
		{
			needs.merge(summon.getItemConsumeId(), CRYSTAL_STOCK * (long) Math.max(1, summon.getItemConsumeCount()), Long::sum);
		}
		for (Skill cubic : cubicSkills(player))
		{
			if (cubic.getItemConsumeId() > 0)
			{
				needs.merge(cubic.getItemConsumeId(), CUBIC_STOCK * (long) Math.max(1, cubic.getItemConsumeCount()), Long::sum);
			}
		}
		return needs;
	}

	/**
	 * @return бафы слуге, которые охотник держит (раздел 4.55): по одному на тип ({@link #BUFF_TARGETS}, {@link #BUFF_SKIP}), по возрастанию id
	 */
	private static List<Skill> servitorBuffs(Player player)
	{
		final Map<String, Skill> byType = new HashMap<>();
		for (Skill skill : player.getAllSkills())
		{
			if (!skill.isActive() || skill.isToggle() || (skill.getAbnormalTime() <= 0) || !BUFF_TARGETS.contains(skill.getTargetType().name()) || BUFF_SKIP.contains(skill.getAbnormalType().name()))
			{
				continue;
			}
			byType.merge(skill.getAbnormalType().name(), skill, (a, b) -> a.getId() <= b.getId() ? a : b);
		}
		final List<Skill> buffs = new ArrayList<>(byType.values());
		buffs.sort(Comparator.comparingInt(Skill::getId));
		return buffs;
	}

	/**
	 * Держит на слуге свои бафы (раздел 4.55): бафа такого типа на нём нет — кастует, по одному, не чаще раза в {@value #BUFF_GAP} мс. Бафы от бафера и пати тоже
	 * считаются. Баф, который ест кристаллы призыва, — только если после него их останется на {@value #BUFF_CRYSTAL_RESERVE} призывов.
	 * @return {@code true} — начал каст
	 */
	private static boolean buff(Player player, Summon servitor, long now)
	{
		if (player.isSitting() || (now < NEXT_BUFF.getOrDefault(player.getObjectId(), 0L)))
		{
			return false;
		}
		final Skill summon = summonSkill(player);
		Skill pick = null;
		for (Skill skill : servitorBuffs(player))
		{
			if ((PhantomCompat.buffByAbnormal(servitor, skill.getAbnormalType()) != null) || player.isSkillDisabled(skill) || (player.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())) || ((skill.getCastRange() > 0) && (player.calculateDistance2D(servitor) > skill.getCastRange())))
			{
				continue;
			}
			if (skill.getItemConsumeId() > 0)
			{
				final long reserve = (summon != null) && (summon.getItemConsumeId() == skill.getItemConsumeId()) ? BUFF_CRYSTAL_RESERVE * (long) Math.max(1, summon.getItemConsumeCount()) : 0;
				if (player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) < (skill.getItemConsumeCount() + reserve))
				{
					continue;
				}
			}
			pick = skill;
			break;
		}
		if (pick == null)
		{
			return false;
		}
		NEXT_BUFF.put(player.getObjectId(), now + BUFF_GAP);
		if (!PhantomCompat.useMagic(player, pick, false, false))
		{
			return false;
		}
		final int count = BUFF_COUNT.merge(player.getObjectId(), 1, Integer::sum);
		final Long last = LAST_BUFF_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= SUPPORT_NOTE_GAP))
		{
			LAST_BUFF_NOTE.put(player.getObjectId(), now);
			BUFF_COUNT.put(player.getObjectId(), 0);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + " (" + player.getLevel() + ") buffs his " + servitor.getName() + " with " + pick.getName() + (count > 1 ? " (" + count + " buffs since the last note)" : "") + ".");
		}
		return true;
	}

	/**
	 * @return бафы слуги из своих (раздел 4.55): «Servitor Haste, Servitor Shield; нет: Servitor Wind Walk»
	 */
	private static String describeBuffs(Player player, Summon servitor)
	{
		final List<String> on = new ArrayList<>();
		final List<String> off = new ArrayList<>();
		for (Skill skill : servitorBuffs(player))
		{
			(PhantomCompat.buffByAbnormal(servitor, skill.getAbnormalType()) != null ? on : off).add(skill.getName());
		}
		return (on.isEmpty() ? "none" : String.join(", ", on)) + (off.isEmpty() ? "" : "; missing: " + String.join(", ", off));
	}

	/**
	 * Сторожит хозяина (раздел 4.55): моба, который бьёт хозяина ближе {@value #GUARD_RANGE}, слуга бьёт сразу — даже если хозяин занят (лечит слугу, сидит, бьёт
	 * другую цель). Mobius сам этого не делает: его слуга на удары по хозяину не отвечает. Уже бьёт такого моба — не дёргает.
	 * @return {@code true} — слуга занят тем, кто бьёт хозяина
	 */
	private static boolean guard(Player player, Summon servitor, long now)
	{
		final WorldObject current = servitor.getTarget();
		if ((current instanceof Monster) && !((Monster) current).isAlikeDead() && (servitor.getAI().getIntention() == Intention.ATTACK) && (PhantomHunterOutfit.victimOf((Monster) current) == player))
		{
			return true;
		}
		Monster attacker = null;
		double nearest = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, GUARD_RANGE))
		{
			if (!monster.isAlikeDead() && (PhantomHunterOutfit.victimOf(monster) == player) && (player.calculateDistance2D(monster) < nearest))
			{
				attacker = monster;
				nearest = player.calculateDistance2D(monster);
			}
		}
		if (attacker == null)
		{
			return false;
		}
		servitor.setTarget(attacker);
		servitor.getAI().setIntentionAttack(attacker);
		final Long last = LAST_GUARD_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= ASSIST_NOTE_GAP))
		{
			LAST_GUARD_NOTE.put(player.getObjectId(), now);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + "'s " + servitor.getName() + " guards him from " + attacker.getName() + " (" + (int) servitor.calculateDistance2D(attacker) + ", servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%).");
		}
		return true;
	}

	/**
	 * Самопроверка SUMMON ({@link #selfTest}): слуга прожил {@value #SELF_TEST_ALIVE} мс — его бафы в лог, и он погибает (убийца — моб, с которым он дерётся, или
	 * ближний моб; без убийцы скрипт Mobius {@code Servitors} падает на NPE); новый слуга — ok, нет через {@value #SELF_TEST_RESUMMON} мс — FAILED. Окно кончилось — итог.
	 * @return {@code true} — слуга только что погиб: остальное на этот тик не нужно
	 */
	private static boolean selfTestTick(Player player, Summon servitor, boolean alive, long now)
	{
		if (_selfTestUntil == 0)
		{
			return false;
		}
		if (now >= _selfTestUntil)
		{
			finishSelfTest();
			return false;
		}
		final int id = player.getObjectId();
		if (TEST_RESULT.containsKey(id))
		{
			return false;
		}
		final Long killed = TEST_KILLED_AT.get(id);
		if (killed == null)
		{
			if (!alive)
			{
				TEST_ALIVE_SINCE.remove(id);
				return false;
			}
			final long since = TEST_ALIVE_SINCE.computeIfAbsent(id, k -> now);
			if ((now - since) < SELF_TEST_ALIVE)
			{
				return false;
			}
			// Призывы Wizard первых уровней у CT — с откатом 90 минут (5 400 000 мс в датапаке): такой охотник по правилам игры остался бы без слуги — не трогать.
			final Skill summon = summonSkill(player);
			if ((summon != null) && (summon.getReuseDelay() > SELF_TEST_RESUMMON))
			{
				TEST_RESULT.put(id, "skipped");
				LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " (" + player.getLevel() + " " + player.getPlayerClass().name() + ") skipped — " + summon.getName() + " (level " + summon.getLevel() + ") has a reuse of " + (summon.getReuseDelay() / 60000) + " min.");
				return false;
			}
			Monster foe = (servitor.getTarget() instanceof Monster) && !((Monster) servitor.getTarget()).isAlikeDead() ? (Monster) servitor.getTarget() : null;
			if (foe == null)
			{
				double nearest = Double.MAX_VALUE;
				for (Monster monster : World.getVisibleObjectsInRange(servitor, Monster.class, SELF_TEST_FOE_RANGE))
				{
					if (!monster.isAlikeDead() && (servitor.calculateDistance2D(monster) < nearest))
					{
						foe = monster;
						nearest = servitor.calculateDistance2D(monster);
					}
				}
			}
			if (foe == null)
			{
				return false;
			}
			TEST_KILLED_AT.put(id, now);
			TEST_LAST_TICK.put(id, now);
			TEST_KILLED_SUMMON.put(id, servitor.getObjectId());
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " (" + player.getLevel() + " " + player.getPlayerClass().name() + ") — his " + servitor.getName() + " dies after " + ((now - since) / 1000) + " s from " + foe.getName() + " (servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%, " + (int) player.calculateDistance2D(servitor) + " from him; buffs: " + describeBuffs(player, servitor) + ").");
			servitor.doDie(foe);
			return true;
		}
		// Тик призывателя идёт только на споте: время в городе, после смерти и в дороге назад — не в счёт (прогон Epilogue 2026-10-29: Koshatnik после смерти
		// слуги 6 минут шёл обратно на спот, призвал там за 4 с, а итог был FAILED).
		final long lastTick = TEST_LAST_TICK.getOrDefault(id, now);
		TEST_LAST_TICK.put(id, now);
		if ((now - lastTick) > SELF_TEST_OFF_SPOT)
		{
			TEST_KILLED_AT.merge(id, now - lastTick, Long::sum);
			TEST_OFF_SPOT.merge(id, now - lastTick, Long::sum);
		}
		final long onSpot = now - TEST_KILLED_AT.get(id);
		final String offSpot = TEST_OFF_SPOT.containsKey(id) ? ", " + (TEST_OFF_SPOT.get(id) / 1000) + " s more off the spot" : "";
		final Skill skill = summonSkill(player);
		final long crystals = (skill != null) && (skill.getItemConsumeId() > 0) ? player.getInventory().getInventoryItemCount(skill.getItemConsumeId(), -1) : -1;
		if (alive && (servitor.getObjectId() == TEST_KILLED_SUMMON.getOrDefault(id, 0)))
		{
			TEST_RESULT.put(id, "FAILED");
			LOGGER.warning(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " FAILED — his " + servitor.getName() + " did not die.");
		}
		else if (alive)
		{
			TEST_RESULT.put(id, "ok");
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " ok — " + servitor.getName() + " is back in " + (onSpot / 1000) + " s on the spot (crystals left " + crystals + offSpot + ").");
		}
		else if (onSpot >= SELF_TEST_RESUMMON)
		{
			TEST_RESULT.put(id, "FAILED");
			LOGGER.warning(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " FAILED — no servitor " + (onSpot / 1000) + " s on the spot after the death (crystals " + crystals + ", MP " + (int) player.getCurrentMp() + ", casting " + player.isCastingNow() + ", attacking " + player.isAttackingNow() + ", sitting " + player.isSitting() + ", under attack " + underAttack(player) + "; last wait: " + TEST_LAST_WAIT.getOrDefault(id, "none") + offSpot + ").");
		}
		return false;
	}

	/**
	 * Самопроверка SUMMON: почему убитый слуга ещё не призван заново — в лог, не чаще раза в {@value #SELF_TEST_WAIT_NOTE} мс на призывателя.
	 */
	private static void testWait(Player player, long now, String reason)
	{
		final int id = player.getObjectId();
		if ((_selfTestUntil == 0) || !TEST_KILLED_AT.containsKey(id) || TEST_RESULT.containsKey(id))
		{
			return;
		}
		TEST_LAST_WAIT.put(id, reason);
		final Long last = TEST_WAIT_NOTE.get(id);
		if ((last == null) || ((now - last) >= SELF_TEST_WAIT_NOTE))
		{
			TEST_WAIT_NOTE.put(id, now);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: " + player.getName() + " waits " + ((now - TEST_KILLED_AT.get(id)) / 1000) + " s: " + reason + ".");
		}
	}

	private static synchronized void finishSelfTest()
	{
		if (_selfTestUntil == 0)
		{
			return;
		}
		_selfTestUntil = 0;
		final long ok = TEST_RESULT.values().stream().filter("ok"::equals).count();
		final long skipped = TEST_RESULT.values().stream().filter("skipped"::equals).count();
		// Без итога — кто после смерти слуги так и не вернулся на спот (тик призывателя — только на споте).
		final List<String> pending = new ArrayList<>();
		for (int id : TEST_KILLED_AT.keySet())
		{
			if (!TEST_RESULT.containsKey(id))
			{
				final Player player = World.getPlayer(id);
				pending.add(player != null ? player.getName() : String.valueOf(id));
			}
		}
		LOGGER.info(PhantomSummoner.class.getSimpleName() + ": SelfTest SUMMON: done, " + ok + " of " + TEST_KILLED_AT.size() + " summoners summoned again (" + (TEST_RESULT.size() - skipped) + " with a result, " + skipped + " skipped for a long reuse, " + TEST_ALIVE_SINCE.size() + " had a servitor" + (pending.isEmpty() ? "" : "; off the spot since the death: " + String.join(", ", pending)) + ").");
	}

	/**
	 * @return его бьёт моб (цель моба — он, и моб в бою) рядом: каст призыва сорвут. Моб, который стоит без дела со старой целью, не в счёт: как {@code inDanger} у охотника.
	 *         До 2026-10-30 такой моб держал и сидящего, и охотника со слугой в «его бьют»: перепризыв ждал минуту, а бафы слуге откладывались.
	 */
	private static boolean underAttack(Player player)
	{
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, UNDER_ATTACK_RANGE))
		{
			if (!monster.isAlikeDead() && (PhantomHunterOutfit.victimOf(monster) == player) && (monster.isInCombat() || (monster.getAI().getIntention() == Intention.ATTACK)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Слуга бьёт цель хозяина, пока хозяин в бою; иначе — как у Mobius (идёт следом, защищает).
	 */
	private static void assist(Player player, Summon servitor, long now)
	{
		final WorldObject target = player.getTarget();
		if (!(target instanceof Monster) || ((Monster) target).isAlikeDead() || !(player.isInCombat() || player.isAttackingNow() || player.isCastingNow()))
		{
			return;
		}
		final Monster monster = (Monster) target;
		if ((servitor.getTarget() == monster) && (servitor.getAI().getIntention() == Intention.ATTACK))
		{
			return;
		}
		servitor.setTarget(monster);
		servitor.getAI().setIntentionAttack(monster);
		final Long last = LAST_ASSIST_NOTE.get(player.getObjectId());
		if ((last == null) || ((now - last) >= ASSIST_NOTE_GAP))
		{
			LAST_ASSIST_NOTE.put(player.getObjectId(), now);
			LOGGER.info(PhantomSummoner.class.getSimpleName() + ": " + player.getName() + "'s " + servitor.getName() + " attacks " + monster.getName() + " (" + (int) servitor.calculateDistance2D(monster) + ", servitor HP " + (int) ((servitor.getCurrentHp() * 100) / Math.max(1, servitor.getMaxHp())) + "%).");
		}
	}
}
