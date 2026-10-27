package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.data.xml.SkillTreeData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.handler.VoicedCommandHandler;
import org.l2jmobius.gameserver.managers.FakePlayerBrain;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.network.serverpackets.PledgeSkillList;

/**
 * L2Hub: живые кланы охотников (docs/PHANTOMS.md, раздел 4.50, этапы 1 и 2; {@code HunterClans} в PhantomHunters.ini). Ни списка кланов, ни их числа:
 * кланы основывают сами охотники.
 * <ul>
 * <li>черты охотника — бросок с зерном objectId: одиночка (в кланы не идёт), лидерство (шанс основать клан), верность своему клану ({@link #traits});</li>
 * <li>основание — раз в {@value #FOUND_GAP} мс охотник без клана может основать клан; имя придумывают мозги ({@link #tryFound});</li>
 * <li>набор — глава зовёт друзей клана, потом знакомых со спота, пока клан не дорос до «аппетита» ({@link #recruit}), кричит о наборе;
 * живой игрок просится шёпотом главе ({@link #onWhisper});</li>
 * <li>уровни, скиллы и репутация — {@link #levelUp}, {@link #learnSkills}; репутацию дают рейды охотников клана ({@link PhantomClans#raidKilled});</li>
 * <li>роспуск — клан из одного главы дольше {@value #LONELY_HOURS} ч;</li>
 * <li>войны между кланами охотников и с кланом игрока — {@link PhantomClanWars} (этапы 3 и 4).</li>
 * </ul>
 * Кланы — обычные кланы Mobius: ложатся в базу и живут после перезапуска. {@code .clans} — список ({@link PhantomClansCommand}).
 */
public final class PhantomHunterClans
{
	private static final Logger LOGGER = Logger.getLogger(PhantomHunterClans.class.getName());

	/** Жизнь кланов (набор, уровни, скиллы, роспуск) — не чаще. */
	private static final long LIFE_GAP = 60000;
	/** Бросок на основание клана — не чаще. */
	private static final long FOUND_GAP = 600000;
	/** Клан основывают с этого уровня (Mobius: createClan — от 10-го). */
	private static final int FOUND_LEVEL = 10;
	/** Друзья и знакомые — не дальше стольких уровней от охотника. */
	private static final int LEVEL_GAP = 10;
	/** Знакомых со спота глава зовёт не дальше. */
	private static final int RECRUIT_RANGE = 2500;
	/** Та же пара «клан — охотник» — не чаще. */
	private static final long RECRUIT_PAIR_GAP = 7200000;
	/** Друг клана соглашается с шансом, %. */
	private static final double FRIEND_CHANCE = 80;
	/** Незнакомый — столько % × общительность, не больше {@value #STRANGER_MAX}. */
	private static final double STRANGER_CHANCE = 30;
	private static final double STRANGER_MAX = 90;
	/** Живой игрок просится в клан: незнакомого глава берёт с шансом столько % × общительность. */
	private static final double ASK_CHANCE = 50;
	/** Решение главы по просьбе игрока помнится столько, мс. */
	private static final long ASK_MEMORY = 3600000;
	/** Ответ на просьбу — не чаще. */
	private static final long ASK_GAP = 5000;
	/** Клан из одного главы распускается через столько часов. */
	private static final int LONELY_HOURS = 72;
	private static final String LONELY_VARIABLE = "L2HubClanLonely_";
	/** Крик о наборе — раз в столько мс и ещё до стольких сверху. */
	private static final long SHOUT_GAP = 1800000;
	private static final long SHOUT_SPREAD = 1800000;
	/** Реплика о клане тому же игроку — не чаще. */
	private static final long SAY_GAP = 30000;
	/** Уровни 1–5: сколько членов и какой средний уровень членов нужен. */
	private static final int[] LEVEL_MEMBERS =
	{
		3,
		5,
		7,
		9,
		10
	};
	private static final int[] LEVEL_AVERAGE =
	{
		10,
		20,
		30,
		40,
		50
	};
	/** Друг — благодарность от стольких очков (как в {@link PhantomRelations}); уйти к игроку из своего клана — от этого × (1 + 2 × верность). */
	private static final double FRIEND = 3;
	/** Имя от мозгов ждать до, с. */
	private static final int NAME_TIMEOUT = 30;
	private static final Pattern NAME = Pattern.compile("[A-Za-z0-9]{3,16}");
	private static final String[] SYLLABLES =
	{
		"Ar",
		"Bel",
		"Cor",
		"Dra",
		"El",
		"Fen",
		"Gor",
		"Hal",
		"Ith",
		"Kar",
		"Lun",
		"Mor",
		"Nar",
		"Or",
		"Ral",
		"Sil",
		"Tor",
		"Ul",
		"Val",
		"Wyr",
		"Zan",
		"Ys"
	};
	private static final String[] ENDINGS =
	{
		"",
		"ia",
		"or",
		"en",
		"heim",
		"guard",
		"blade",
		"wolf",
		"fang",
		"crow",
		"hold",
		"rim"
	};
	private static final String[] SHOUTS =
	{
		"клан %s (%d лвл) набирает народ: кач, рб, без нытья. пиши в пм «возьми в клан»",
		"%s ищет людей в клан, клан %d лвл, на рб ходим. кто хочет — в пм",
		"набор в клан %s! адекватным рады, пишите мне в личку",
		"в %s есть места) кто без клана — стучите в пм"
	};
	/** Просьба взять в клан: про клан, глагол просьбы и не «в мой клан» (это зовут охотника — окно клана, раздел 4.41). */
	private static final Pattern ASK_CLAN = Pattern.compile("(клан|clan|\\bкп\\b)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern ASK_VERB = Pattern.compile("(возьм|приним|прими|вступ|пуст|инвайт|invite|join|accept|take me|можно к|хочу к|хочу в|позов|кинь|набира|recruit)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	private static final Pattern ASK_NOT = Pattern.compile("(мой клан|моего клана|в мой|ко мне в|my clan)", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

	/**
	 * Черты охотника на этом сервере: в каждом мире свои (зерно — objectId).
	 * @param loner одиночка: в кланы не идёт
	 * @param leadership 0–1: шанс основать клан
	 * @param loyalty 0–1: верность своему клану
	 */
	record Traits(boolean loner, double leadership, double loyalty)
	{
	}

	private record Answer(boolean accept, String why, String what, String fallback)
	{
	}

	private final Map<Integer, Traits> _traits = new ConcurrentHashMap<>();
	/** «клан:охотник» → когда звали последний раз. */
	private final Map<String, Long> _recruitPairs = new ConcurrentHashMap<>();
	/** Клан → когда кричать о наборе. */
	private final Map<Integer, Long> _nextShout = new ConcurrentHashMap<>();
	/** «клан:игрок» → {взял (1/0), когда решил}. */
	private final Map<String, long[]> _asks = new ConcurrentHashMap<>();
	private long _nextLife;
	private long _nextFound;
	/** Охотник, который ждёт имя клана от мозгов (0 — никто): пока ждёт — нового основания нет. */
	private volatile int _founding;
	/** Клан самопроверки CLANLIFE: живёт по её сценарию. */
	private volatile int _testClanId;
	private boolean _commandRegistered;

	private PhantomHunterClans()
	{
	}

	// ---------- Черты ----------

	/**
	 * @return черты охотника; не охотник — средние
	 */
	Traits traits(Player hunter)
	{
		return _traits.computeIfAbsent(hunter.getObjectId(), id ->
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			return roll(id, template == null ? 1 : template.getSocial(), template == null ? 1 : template.getTemper());
		});
	}

	/**
	 * Одиночка — чем нелюдимее, тем чаще (60, 35, 15 и 5 % при общительности 0,2, 0,5, 1 и 1,5); лидерство — бросок под потолок от общительности
	 * и вспыльчивости; верность — бросок.
	 */
	static Traits roll(int objectId, double social, double temper)
	{
		final Random rnd = new Random(0x4C32487542L ^ (objectId * 0x9E3779B97F4A7C15L));
		final double lonerChance = social < 0.35 ? 0.6 : social < 0.75 ? 0.35 : social < 1.25 ? 0.15 : 0.05;
		final boolean loner = rnd.nextDouble() < lonerChance;
		final double leadership = rnd.nextDouble() * Math.min(1, 0.35 + (0.3 * social) + (0.2 * Math.max(0, temper - 1)));
		final double loyalty = rnd.nextDouble();
		return new Traits(loner, leadership, loyalty);
	}

	/**
	 * @return одиночка: в кланы не идёт — ни к охотникам, ни к живым ({@link PhantomClans#onInvited})
	 */
	boolean isLoner(Player hunter)
	{
		return traits(hunter).loner();
	}

	/**
	 * @return сколько благодарности к игроку нужно, чтобы уйти к нему из своего клана охотников: от 3 (неверный) до 9 (самый верный)
	 */
	double switchGratitude(Player hunter)
	{
		return FRIEND * (1 + (2 * traits(hunter).loyalty()));
	}

	/**
	 * @return клан охотников: его глава — охотник (не клан живого игрока и не клан самопроверки CLAN)
	 */
	static boolean isHunterClan(Clan clan)
	{
		if ((clan == null) || clan.getName().startsWith(PhantomClans.TEST_CLAN))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(clan.getLeaderName());
		return (template != null) && template.isHunter() && !template.isReaper();
	}

	/**
	 * @return двое в одном клане (для пати и рейдов охотников: соклановец — как друг)
	 */
	static boolean clanmates(Player a, Player b)
	{
		return (a != null) && (b != null) && (a != b) && (a.getClan() != null) && (a.getClan() == b.getClan());
	}

	/**
	 * «Аппетит» клана: до скольких членов глава набирает — случайно из {@code HunterClanSize} (зерно — id клана); у общительного главы — больше, у нелюдимого — меньше.
	 */
	int appetite(Clan clan)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(clan.getLeaderName());
		final double social = template == null ? 1 : template.getSocial();
		final Random rnd = new Random(0x436C616EL ^ (clan.getId() * 0x9E3779B97F4A7C15L));
		final double r = Math.pow(rnd.nextDouble(), social >= 1.5 ? 0.5 : social <= 0.5 ? 2 : 1);
		final int min = PhantomHuntersConfig.HUNTER_CLAN_SIZE_MIN;
		final int max = PhantomHuntersConfig.HUNTER_CLAN_SIZE_MAX;
		return min + (int) Math.round((max - min) * r);
	}

	private static boolean isHunter(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && template.isHunter() && !template.isReaper() && !PhantomManager.getInstance().isSecret(player);
	}

	/**
	 * @return без клана и без штрафов Mobius на вступление и основание
	 */
	private static boolean free(Player hunter, long now)
	{
		return (hunter.getClan() == null) && (hunter.getClanJoinExpiryTime() <= now) && isHunter(hunter);
	}

	// ---------- Ход ----------

	/**
	 * Раз в 10 с из {@code PhantomHunterManager.control}: жизнь кланов и бросок на основание.
	 * @param hunters охотники в мире, живые
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunters, long now)
	{
		if (!_commandRegistered)
		{
			_commandRegistered = true;
			VoicedCommandHandler.getInstance().registerHandler(new PhantomClansCommand());
		}
		// Войны — и при выключенных кланах: тогда идущие кончаются перемирием.
		PhantomClanWars.getInstance().control(hunters, now);
		if (!PhantomHuntersConfig.HUNTER_CLANS)
		{
			return;
		}
		if (now >= _nextLife)
		{
			_nextLife = now + LIFE_GAP;
			life(hunters, now);
		}
		// Первый бросок — не сразу после старта: охотники входят в мир по одному.
		if (_nextFound == 0)
		{
			_nextFound = now + FOUND_GAP;
		}
		else if (now >= _nextFound)
		{
			_nextFound = now + FOUND_GAP;
			tryFound(hunters, now);
		}
	}

	private void life(List<Player> hunters, long now)
	{
		final boolean living = PhantomHunterManager.getInstance().getRealPlayers() > 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (!isHunterClan(clan) || (clan.getId() == _testClanId))
			{
				continue;
			}
			try
			{
				if (dissolveLonely(clan, now))
				{
					continue;
				}
				final ClanMember head = clan.getLeader();
				final Player leader = head == null ? null : head.getPlayer();
				if ((leader == null) || !leader.isOnline())
				{
					continue;
				}
				levelUp(clan, leader);
				learnSkills(clan, leader);
				recruit(clan, leader, hunters, now, false);
				if (living)
				{
					recruitShout(clan, leader, now);
				}
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": clan " + clan.getName() + ": " + e.getMessage(), e);
			}
		}
	}

	// ---------- Основание ----------

	/**
	 * Каждый охотник от {@value #FOUND_LEVEL}-го уровня без клана (не одиночка) бросает: {@code HunterClanFoundChance} % × лидерство × доля охотников без клана
	 * × друзья (двое друзей без клана в ±{@value #LEVEL_GAP} уровнях — сполна, один — половина, ни одного — 15 %). Не больше одного основания за бросок.
	 */
	private void tryFound(List<Player> hunters, long now)
	{
		if (_founding != 0)
		{
			return;
		}
		final List<Player> eligible = new ArrayList<>();
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if ((hunter.getLevel() < FOUND_LEVEL) || !isHunter(hunter) || isLoner(hunter))
			{
				continue;
			}
			eligible.add(hunter);
			if (canFound(hunter, now))
			{
				free.add(hunter);
			}
		}
		if (free.isEmpty())
		{
			return;
		}

		final double share = free.size() / (double) eligible.size();
		final PhantomRelations relations = PhantomRelations.getInstance();
		Collections.shuffle(free);
		for (Player hunter : free)
		{
			int friends = 0;
			for (Player other : free)
			{
				if ((other != hunter) && (Math.abs(other.getLevel() - hunter.getLevel()) <= LEVEL_GAP) && (relations.isFriend(hunter, other) || relations.isFriend(other, hunter)))
				{
					friends++;
				}
			}
			final double friendFactor = friends >= 2 ? 1 : friends == 1 ? 0.5 : 0.15;
			final double chance = PhantomHuntersConfig.HUNTER_CLAN_FOUND_CHANCE * traits(hunter).leadership() * share * friendFactor;
			if (Rnd.get(100.0) < chance)
			{
				final String why = String.format(Locale.ROOT, "chance %.2f%%: leadership %.2f, without a clan %d of %d, free friends %d", chance, traits(hunter).leadership(), free.size(), eligible.size(), friends);
				_founding = hunter.getObjectId();
				requestName(hunter, name -> ThreadPool.execute(() -> found(hunter, name, why)));
				return;
			}
		}
	}

	static boolean canFound(Player hunter, long now)
	{
		return free(hunter, now) && (hunter.getLevel() >= FOUND_LEVEL) && (hunter.getClanCreateExpiryTime() <= now);
	}

	/**
	 * Основать клан: имя занято или кривое — из слогов. В летопись и, если в игре живые, крик в торговый чат.
	 * @return клан или {@code null}
	 */
	synchronized Clan found(Player hunter, String name, String why)
	{
		_founding = 0;
		final long now = System.currentTimeMillis();
		if (!hunter.isOnline() || !canFound(hunter, now))
		{
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " does not found a clan: left the world or not free any more.");
			return null;
		}
		final String clanName = (name != null) && NAME.matcher(name).matches() && (ClanTable.getInstance().getClanByName(name) == null) ? name : fallbackName();
		final Clan clan = ClanTable.getInstance().createClan(hunter, clanName);
		if (clan == null)
		{
			LOGGER.warning(getClass().getSimpleName() + ": " + hunter.getName() + " could not create clan " + clanName + ".");
			return null;
		}
		ThreadPool.execute(hunter::storeMe);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") founded clan " + clanName + (clanName.equals(name) ? "" : " (name from syllables)") + ", appetite " + appetite(clan) + " — " + why + ".");
		PhantomNews.post("clan", "Охотник " + hunter.getName() + " (" + hunter.getLevel() + ") основал" + (female(hunter) ? "а" : "") + " клан «" + clanName + "»", hunter.getName(), hunter);
		if (PhantomHunterManager.getInstance().getRealPlayers() > 0)
		{
			_nextShout.put(clan.getId(), now + SHOUT_GAP + Rnd.get(SHOUT_SPREAD));
			PhantomChat.getInstance().hunterShout(hunter, Rnd.nextBoolean() ? "основал клан " + clanName + ", набираю людей — пишите в пм" : "у нас новый клан — " + clanName + "! кто без клана, стучите в личку");
		}
		return clan;
	}

	/**
	 * Имя клана от мозгов ({@code "kind":"clanname"}, brain.py): латиница и цифры, 3–16 знаков, не занято. Мозги выключены, молчат или ответили не то — из слогов.
	 */
	private void requestName(Player hunter, Consumer<String> done)
	{
		if (!PhantomChat.brainEnabled())
		{
			done.accept(fallbackName());
			return;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		final StringBuilder sb = new StringBuilder(1024);
		sb.append('{');
		FakePlayerBrain.field(sb, "kind", "clanname").append(',');
		FakePlayerBrain.field(sb, "fpc", hunter.getName()).append(',');
		sb.append("\"level\":").append(hunter.getLevel()).append(',');
		FakePlayerBrain.field(sb, "class", hunter.getPlayerClass().name()).append(',');
		sb.append("\"female\":").append((template != null) && template.isFemale()).append(',');
		FakePlayerBrain.field(sb, "lang", L2HubWorldConfig.LANGUAGE != null ? L2HubWorldConfig.LANGUAGE : "ru").append(',');
		sb.append("\"taken\":[");
		int count = 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (count >= 40)
			{
				break;
			}
			sb.append(count++ > 0 ? "," : "").append('"').append(clan.getName()).append('"');
		}
		sb.append("]}");
		final AtomicBoolean once = new AtomicBoolean();
		FakePlayerBrain.getInstance().send(sb.toString(), reply ->
		{
			if (once.compareAndSet(false, true))
			{
				final Matcher matcher = NAME.matcher(reply == null ? "" : reply);
				final String name = matcher.find() ? matcher.group() : null;
				LOGGER.info(getClass().getSimpleName() + ": brain named the clan of " + hunter.getName() + ": «" + (reply == null ? "" : reply.strip()) + "» -> " + name + ".");
				done.accept(name);
			}
		}, () ->
		{
			if (once.compareAndSet(false, true))
			{
				done.accept(fallbackName());
			}
		}, NAME_TIMEOUT);
	}

	/**
	 * @return свободное имя из двух слогов и окончания (Korwolf, Silheim…), не нашлось — Clan с цифрами
	 */
	static String fallbackName()
	{
		for (int i = 0; i < 30; i++)
		{
			final String name = SYLLABLES[Rnd.get(SYLLABLES.length)] + SYLLABLES[Rnd.get(SYLLABLES.length)].toLowerCase() + ENDINGS[Rnd.get(ENDINGS.length)];
			if ((name.length() <= 16) && (ClanTable.getInstance().getClanByName(name) == null))
			{
				return name;
			}
		}
		return "Clan" + Rnd.get(10000, 100000);
	}

	// ---------- Набор ----------

	/**
	 * Глава в мире зовёт одного: сначала друзей клана (друг главы или любого соклановца в мире) без клана, потом знакомых со спота рядом с собой
	 * (не дальше {@value #RECRUIT_RANGE}, ±{@value #LEVEL_GAP} уровней). Одиночек и врагов хоть одного соклановца (с любой стороны) — нет;
	 * ту же пару — не чаще {@value #RECRUIT_PAIR_GAP} мс. Друг соглашается с шансом {@value #FRIEND_CHANCE} %, незнакомый — {@value #STRANGER_CHANCE} % × общительность.
	 * @param force самопроверка: соглашается всегда, без паузы пары
	 * @return кто вступил или {@code null}
	 */
	Player recruit(Clan clan, Player leader, List<Player> hunters, long now, boolean force)
	{
		if (!force && (clan.getMembersCount() >= appetite(clan)))
		{
			return null;
		}
		final List<Player> own = new ArrayList<>();
		for (ClanMember member : clan.getMembers())
		{
			final Player mate = member.getPlayer();
			if ((mate != null) && mate.isOnline())
			{
				own.add(mate);
			}
		}

		final PhantomRelations relations = PhantomRelations.getInstance();
		final List<Player> friends = new ArrayList<>();
		final List<Player> strangers = new ArrayList<>();
		for (Player other : hunters)
		{
			if (!free(other, now) || isLoner(other) || own.contains(other))
			{
				continue;
			}
			boolean enemy = false;
			boolean friend = false;
			for (Player mate : own)
			{
				if (relations.isEnemy(mate, other) || relations.isEnemy(other, mate))
				{
					enemy = true;
					break;
				}
				friend |= relations.isFriend(mate, other) || relations.isFriend(other, mate);
			}
			if (enemy)
			{
				continue;
			}
			if (friend)
			{
				friends.add(other);
			}
			else if ((Math.abs(other.getLevel() - leader.getLevel()) <= LEVEL_GAP) && (other.getInstanceId() == leader.getInstanceId()) && (leader.calculateDistance2D(other) <= RECRUIT_RANGE))
			{
				strangers.add(other);
			}
		}
		Collections.shuffle(friends);
		Collections.shuffle(strangers);
		final List<Player> candidates = new ArrayList<>(friends);
		candidates.addAll(strangers);
		for (Player other : candidates)
		{
			final String pair = clan.getId() + ":" + other.getObjectId();
			if (!force && (now < _recruitPairs.getOrDefault(pair, 0L)))
			{
				continue;
			}
			_recruitPairs.put(pair, now + RECRUIT_PAIR_GAP);
			if (_recruitPairs.size() > 4096)
			{
				_recruitPairs.values().removeIf(until -> until < now);
			}

			final boolean friend = friends.contains(other);
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(other.getName());
			final double chance = force ? 100 : friend ? FRIEND_CHANCE : Math.min(STRANGER_MAX, STRANGER_CHANCE * (template == null ? 1 : template.getSocial()));
			final boolean yes = Rnd.get(100.0) < chance;
			LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " calls " + other.getName() + " (" + other.getLevel() + ") to clan " + clan.getName() + " (" + (friend ? "friend" : "stranger") + String.format(Locale.ROOT, ", chance %.0f%%", chance) + ") — " + (yes ? "yes" : "no") + ".");
			if (yes && join(clan, leader, other, friend))
			{
				return other;
			}
			return null;
		}
		return null;
	}

	/**
	 * @return клан самопроверки CLANLIFE (0 — нет): живёт по её сценарию, войн у него нет
	 */
	int testClanId()
	{
		return _testClanId;
	}

	boolean join(Clan clan, Player leader, Player hunter, boolean friend)
	{
		if (!clan.checkClanJoinCondition(leader, hunter, 0))
		{
			LOGGER.info(getClass().getSimpleName() + ": Mobius does not let " + hunter.getName() + " join clan " + clan.getName() + " (checkClanJoinCondition).");
			return false;
		}
		PhantomCompat.joinClan(leader, hunter, 0);
		ThreadPool.execute(hunter::storeMe);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " joined clan " + clan.getName() + " (" + clan.getMembersCount() + " of " + appetite(clan) + ").");
		// Клан-чат видят только живые: без них реплику не тратить.
		if (PhantomClans.getInstance().livingOnline(clan))
		{
			final String what = "ты вступил в клан охотников «" + clan.getName() + "», его глава — " + leader.getName() + (friend ? ", твой знакомый" : "") + "; поздоровайся с кланом";
			PhantomChat.getInstance().clanEvent(hunter, leader, clan, "CLAN_JOIN", what, Rnd.nextBoolean() ? "всем привет)" : "привет, клан! рад к вам", SAY_GAP);
		}
		return true;
	}

	private void recruitShout(Clan clan, Player leader, long now)
	{
		if (clan.getMembersCount() >= appetite(clan))
		{
			return;
		}
		final Long next = _nextShout.get(clan.getId());
		if (next == null)
		{
			// Первый раз — вразнобой, чтобы кланы не кричали хором после старта.
			_nextShout.put(clan.getId(), now + Rnd.get(SHOUT_SPREAD));
			return;
		}
		if (now < next)
		{
			return;
		}
		_nextShout.put(clan.getId(), now + SHOUT_GAP + Rnd.get(SHOUT_SPREAD));
		final String channel = PhantomChat.getInstance().hunterShout(leader, String.format(SHOUTS[Rnd.get(SHOUTS.length)], clan.getName(), clan.getLevel()));
		LOGGER.info(getClass().getSimpleName() + ": " + leader.getName() + " shouts recruitment of clan " + clan.getName() + " to " + channel + " chat.");
	}

	// ---------- Просьба живого игрока ----------

	/**
	 * Шёпот охотнику (из {@link PhantomChat#onWhisper}): живой игрок просится в клан охотников. Глава решает и шлёт окно «вступить в клан?», соклановец отсылает к главе.
	 * @return {@code true} — это была просьба о клане и ответ уже идёт
	 */
	public boolean onWhisper(Player speaker, Player phantom, String text)
	{
		final Clan clan = phantom.getClan();
		if (!PhantomHuntersConfig.HUNTER_CLANS || PhantomManager.getInstance().isPhantom(speaker) || !isHunterClan(clan) || !isAsk(text))
		{
			return false;
		}
		final PhantomChat chat = PhantomChat.getInstance();
		final String who = PhantomHunterManager.describe(speaker) + " пишет тебе в личку «" + text + "» — просится в твой клан «" + clan.getName() + "»";
		if (!phantom.isClanLeader())
		{
			final String leader = clan.getLeaderName();
			chat.hunterEvent(phantom, speaker, "CLAN_ASK", who + "; в клан принимает глава, " + leader + ", — скажи, чтобы написал ему", "в клан берёт " + leader + ", пиши ему", ASK_GAP, true);
			return true;
		}
		final Answer answer = decideAsk(phantom, speaker, clan, true);
		LOGGER.info(getClass().getSimpleName() + ": " + speaker.getName() + " asks " + phantom.getName() + " to join clan " + clan.getName() + " — " + (answer.accept ? "invited" : "refused") + " (" + answer.why + ").");
		chat.hunterEvent(phantom, speaker, "CLAN_ASK", answer.what, answer.fallback, ASK_GAP, true);
		return true;
	}

	static boolean isAsk(String text)
	{
		return (text != null) && ASK_CLAN.matcher(text).find() && ASK_VERB.matcher(text).find() && !ASK_NOT.matcher(text).find();
	}

	/**
	 * @param invite отправить окно «вступить в клан?» ({@code false} — самопроверка: только решение)
	 */
	private Answer decideAsk(Player leader, Player player, Clan clan, boolean invite)
	{
		final long now = System.currentTimeMillis();
		final String who = PhantomHunterManager.describe(player) + " просится в твой клан «" + clan.getName() + "»";
		if (player.getClan() == clan)
		{
			return new Answer(false, "member", who + ", а он уже в нём — скажи ему об этом", "так ты ж уже у нас)");
		}
		if (player.getClan() != null)
		{
			return new Answer(false, "other clan", who + ", но он в другом клане, «" + player.getClan().getName() + "», — пусть сначала выйдет", "у тебя ж свой клан. выйдешь — пиши");
		}
		if (player.getClanJoinExpiryTime() > now)
		{
			return new Answer(false, "penalty", who + ", но у него штраф на вступление после ухода из клана — пусть напишет, когда пройдёт", "у тебя штраф на вступление, как спадёт — пиши");
		}
		final PhantomRelations relations = PhantomRelations.getInstance();
		final List<Player> own = new ArrayList<>();
		for (ClanMember member : clan.getMembers())
		{
			final Player mate = member.getPlayer();
			if ((mate != null) && mate.isOnline())
			{
				own.add(mate);
			}
		}
		boolean friend = false;
		for (Player mate : own)
		{
			if (relations.isEnemy(mate, player))
			{
				return new Answer(false, "enemy of " + mate.getName(), who + "; " + (mate == leader ? "он твой враг" : "он враг твоего соклановца " + mate.getName()) + " — резко откажи", mate == leader ? "тебя? в мой клан? не смеши" : "после того, что ты сделал " + mate.getName() + "? нет");
			}
			friend |= relations.isFriend(mate, player);
		}
		if (clan.getMembersCount() >= appetite(clan))
		{
			return new Answer(false, "full", who + ", но клан уже набран, новых не берёте — откажи по-дружески", "сорян, мы набрались. может позже");
		}

		final String key = clan.getId() + ":" + player.getObjectId();
		final long[] last = _asks.get(key);
		boolean yes;
		String why;
		if (friend)
		{
			yes = true;
			why = "friend";
		}
		else if ((last != null) && ((now - last[1]) < ASK_MEMORY))
		{
			yes = last[0] == 1;
			why = "decided before";
		}
		else
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(leader.getName());
			yes = Rnd.get(100.0) < (ASK_CHANCE * (template == null ? 1 : template.getSocial()));
			why = "stranger, roll";
			_asks.put(key, new long[]
			{
				yes ? 1 : 0,
				now
			});
		}
		if (!yes)
		{
			return new Answer(false, why, who + "; ты его почти не знаешь и пока не берёшь — откажи: в клан берёте тех, с кем уже качались и ходили на рб", "не, пока не беру. покачаемся вместе — тогда посмотрим");
		}
		if (!clan.checkClanJoinCondition(leader, player, 0))
		{
			return new Answer(false, why + ", Mobius refused", who + "; ты бы взял, но игра не даёт: клан полный по уровню или у него штраф — скажи, что пока не выходит", "хотел кинуть инвайт, а не даёт. попробуем позже");
		}
		if (invite && !PhantomCompat.askJoinClan(leader, player))
		{
			return new Answer(false, why + ", busy", who + "; ты согласен, но у одного из вас уже висит другое приглашение — попроси написать через минуту", "погоди минуту, щас с другим инвайтом разберусь");
		}
		return new Answer(true, why, who + (friend ? "; он друг клана" : "") + " — ты соглашаешься, окно приглашения в клан ему уже пришло: скажи, чтобы принял", friend ? "конечно, кидаю инвайт — жми да)" : "ладно, давай к нам. кинул инвайт");
	}

	// ---------- Уровни и скиллы ----------

	/**
	 * Уровни 1–5 — когда хватает членов и среднего уровня (3 и 10, 5 и 20, 7 и 30, 9 и 40, 10 и 50); за 1-й и 2-й глава платит адену, как в Mobius,
	 * SP и предметы — нет. Выше — по хронике: H5, Epilogue, Interlude — репутация и члены из Feature.ini ({@code ClanLevelNCost},
	 * {@code ClanLevelNRequirement}, «Кланы малого мира»), без предметов; Classic и Essence — клановый опыт Mobius ({@link PhantomCompat#clanLevelByExp}).
	 * @return уровень поднят
	 */
	boolean levelUp(Clan clan, Player leader)
	{
		final int level = clan.getLevel();
		if (level >= PhantomCompat.clanMaxLevel())
		{
			return false;
		}
		final int members = clan.getMembersCount();
		if (level < LEVEL_MEMBERS.length)
		{
			if ((members < LEVEL_MEMBERS[level]) || (averageLevel(clan) < LEVEL_AVERAGE[level]))
			{
				return false;
			}
			final long adena = PhantomCompat.clanLevelAdena(level + 1);
			if ((adena > 0) && ((leader.getAdena() < adena) || !PhantomCompat.reduceAdena(leader, ItemProcessType.FEE, adena, null, false)))
			{
				return false;
			}
			raise(clan, level + 1, adena > 0 ? "leader paid " + adena + " adena" : "members " + members);
			return true;
		}
		final int cost = feature("CLAN_LEVEL_" + (level + 1) + "_COST");
		final int need = feature("CLAN_LEVEL_" + (level + 1) + "_REQUIREMENT");
		if ((cost < 0) || (need < 0) || (members < need) || (clan.getReputationScore() < cost))
		{
			return false;
		}
		clan.takeReputationScore(cost);
		raise(clan, level + 1, cost + " reputation, members " + members);
		return true;
	}

	private void raise(Clan clan, int level, String how)
	{
		clan.changeLevel(level);
		// Mobius пишет репутацию в базу только при остановке сервера.
		ThreadPool.execute(clan::updateClanInDB);
		LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " is level " + level + " now (" + how + ").");
		PhantomNews.post("clan", "Клан охотников «" + clan.getName() + "» — " + level + "-й уровень", clan.getLeaderName(), clan.getLeader() == null ? null : clan.getLeader().getPlayer());
	}

	/**
	 * Клан учит сам самый дешёвый доступный скилл — один за ход: репутация клана, адена из цены скилла — с главы (как за 1-й и 2-й уровень), прочие
	 * предметы не нужны. У Classic и Essence многие скиллы стоят 0 репутации и только адену — без неё они были бы даром. Членов на следующий уровень
	 * уже хватает — копит на него: на скиллы — только сверх его цены.
	 * @param leader глава в мире — платит адену
	 * @return выученный скилл или {@code null}
	 */
	Skill learnSkills(Clan clan, Player leader)
	{
		final List<SkillLearn> available = new ArrayList<>(SkillTreeData.getInstance().getAvailablePledgeSkills(clan));
		if (available.isEmpty())
		{
			return null;
		}
		available.sort(Comparator.comparingLong((SkillLearn learn) -> (long) learn.getLevelUpSp()).thenComparingLong(PhantomCompat::skillAdena));
		int reserve = 0;
		final int level = clan.getLevel();
		if ((level >= LEVEL_MEMBERS.length) && (level < PhantomCompat.clanMaxLevel()))
		{
			final int need = feature("CLAN_LEVEL_" + (level + 1) + "_REQUIREMENT");
			final int cost = feature("CLAN_LEVEL_" + (level + 1) + "_COST");
			if ((need >= 0) && (cost > 0) && (clan.getMembersCount() >= need))
			{
				reserve = cost;
			}
		}
		for (SkillLearn learn : available)
		{
			final int cost = (int) learn.getLevelUpSp();
			final long adena = PhantomCompat.skillAdena(learn);
			if (((clan.getReputationScore() - reserve) < cost) || ((adena > 0) && ((leader == null) || (leader.getAdena() < adena))))
			{
				continue;
			}
			final Skill skill = SkillData.getInstance().getSkill(learn.getSkillId(), learn.getSkillLevel());
			if ((skill == null) || ((adena > 0) && !PhantomCompat.reduceAdena(leader, ItemProcessType.FEE, adena, null, false)))
			{
				continue;
			}
			if (cost > 0)
			{
				clan.takeReputationScore(cost);
			}
			clan.addNewSkill(skill);
			clan.broadcastToOnlineMembers(new PledgeSkillList(clan));
			ThreadPool.execute(clan::updateClanInDB);
			LOGGER.info(getClass().getSimpleName() + ": clan " + clan.getName() + " learned " + skill.getName() + " " + skill.getLevel() + " for " + cost + " reputation" + (adena > 0 ? " and " + adena + " adena of " + leader.getName() : "") + " (left " + clan.getReputationScore() + (reserve > 0 ? ", saves " + reserve + " for the next level" : "") + ").");
			return skill;
		}
		return null;
	}

	private static int averageLevel(Clan clan)
	{
		int sum = 0;
		int count = 0;
		for (ClanMember member : clan.getMembers())
		{
			sum += member.getLevel();
			count++;
		}
		return count == 0 ? 0 : sum / count;
	}

	/**
	 * @return поле {@code FeatureConfig} по имени; нет такого (уровня нет в хронике) — -1
	 */
	private static int feature(String field)
	{
		try
		{
			return FeatureConfig.class.getField(field).getInt(null);
		}
		catch (NoSuchFieldException | IllegalAccessException e)
		{
			return -1;
		}
	}

	// ---------- Роспуск ----------

	/**
	 * Клан из одного главы дольше {@value #LONELY_HOURS} ч распускается; с кем-то ещё — счёт сначала.
	 * @return распущен
	 */
	private boolean dissolveLonely(Clan clan, long now)
	{
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		final String key = LONELY_VARIABLE + clan.getId();
		if (clan.getMembersCount() > 1)
		{
			if (variables.hasVariable(key))
			{
				variables.remove(key);
			}
			return false;
		}
		final long since = variables.getLong(key, 0);
		if (since == 0)
		{
			variables.set(key, now);
			return false;
		}
		if ((now - since) < (LONELY_HOURS * 3600000L))
		{
			return false;
		}
		variables.remove(key);
		dissolve(clan, "only the leader for " + LONELY_HOURS + " h", "в нём так никто и не остался");
		return true;
	}

	private void dissolve(Clan clan, String why, String text)
	{
		final String name = clan.getName();
		final String leader = clan.getLeaderName();
		final Player at = clan.getLeader() == null ? null : clan.getLeader().getPlayer();
		GlobalVariablesManager.getInstance().remove(LONELY_VARIABLE + clan.getId());
		_nextShout.remove(clan.getId());
		ClanTable.getInstance().destroyClan(clan.getId());
		LOGGER.info(getClass().getSimpleName() + ": clan " + name + " of " + leader + " dissolved (" + why + ").");
		if (text != null)
		{
			PhantomNews.post("clan", "Клан охотников «" + name + "» распался: " + text, leader, at);
		}
	}

	/**
	 * {@code .clans reset} (GM): распустить все кланы охотников.
	 * @return сколько распущено
	 */
	synchronized int reset()
	{
		int count = 0;
		for (Clan clan : new ArrayList<>(ClanTable.getInstance().getClans()))
		{
			if (isHunterClan(clan))
			{
				dissolve(clan, ".clans reset", null);
				count++;
			}
		}
		return count;
	}

	// ---------- Видимость ----------

	/**
	 * Для {@code L2HubHunters.json}: клан охотника, его уровень, глава ли он, клан охотников ли; одиночка.
	 */
	void status(StringBuilder sb, Player hunter)
	{
		final Clan clan = hunter.getClan();
		if (clan != null)
		{
			sb.append(',');
			FakePlayerBrain.field(sb, "clan", clan.getName());
			sb.append(",\"clanLevel\":").append(clan.getLevel()).append(",\"clanLeader\":").append(hunter.isClanLeader()).append(",\"hunterClan\":").append(isHunterClan(clan));
			final String[] war = PhantomClanWars.getInstance().war(clan);
			if (war != null)
			{
				sb.append(',');
				FakePlayerBrain.field(sb, "clanWar", war[0]).append(',');
				FakePlayerBrain.field(sb, "clanWarScore", war[1]);
				if (!war[2].isEmpty())
				{
					sb.append(",\"clanWarPlayer\":true");
				}
			}
		}
		if (isLoner(hunter))
		{
			sb.append(",\"loner\":true");
		}
	}

	/**
	 * Текст {@code .clans}: кланы охотников — уровень, члены, сколько в игре, глава, репутация, набирает ли, с кем воюет и счёт.
	 */
	List<String> describe(Player viewer)
	{
		final List<String> lines = new ArrayList<>();
		final List<Clan> clans = new ArrayList<>();
		int members = 0;
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (isHunterClan(clan))
			{
				clans.add(clan);
				members += clan.getMembersCount();
			}
		}
		clans.sort(Comparator.comparingInt((Clan c) -> -c.getLevel()).thenComparingInt(c -> -c.getMembersCount()).thenComparing(Clan::getName));
		lines.add("Кланы охотников: " + clans.size() + ", в них " + members + " чел." + (PhantomHuntersConfig.HUNTER_CLANS ? "" : " Живые кланы выключены (HunterClans в PhantomHunters.ini): новые не появятся, старые не растут."));
		final int max = 15;
		for (Clan clan : clans.subList(0, Math.min(max, clans.size())))
		{
			int online = 0;
			for (ClanMember member : clan.getMembers())
			{
				if ((member.getPlayer() != null) && member.getPlayer().isOnline())
				{
					online++;
				}
			}
			final ClanMember head = clan.getLeader();
			final boolean recruiting = clan.getMembersCount() < appetite(clan);
			final String[] war = PhantomClanWars.getInstance().war(clan);
			lines.add("«" + clan.getName() + "» — " + clan.getLevel() + " ур., " + clan.getMembersCount() + " чел. (в игре " + online + "), глава " + clan.getLeaderName() + (head != null ? " (" + head.getLevel() + ")" : "") + ", репутация " + clan.getReputationScore() + (recruiting ? ", набирает" : "") + (war != null ? ", воюет с " + (war[2].isEmpty() ? "" : "кланом игрока ") + "«" + war[0] + "» (" + war[1] + ")" : "") + ((viewer.getClan() == clan) ? " — твой клан" : ""));
		}
		if (clans.size() > max)
		{
			lines.add("…и ещё " + (clans.size() - max) + ".");
		}
		if (viewer.getClan() == null)
		{
			lines.add("Вступить — шепни главе, например «возьмёшь в клан?».");
		}
		return lines;
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка CLANLIFE ({@code HuntersSelfTest}, только на копии базы: кланы, адена, отношения и штрафы ложатся в базу): распределение черт; старший свободный
	 * охотник основывает клан (имя — от мозгов); двое друзей вступают; 1-й уровень за адену; 5-й уровень, репутация и скиллы; 6-й ждёт членов; обида расходится
	 * по клану; .clans; просьба игрока; верность — уход к «живому» другу; роспуск одинокого клана.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		final long now = System.currentTimeMillis();
		final Map<Float, double[]> bySocial = new TreeMap<>();
		for (Player hunter : hunters)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
			if (template == null)
			{
				continue;
			}
			final Traits traits = traits(hunter);
			final double[] row = bySocial.computeIfAbsent(template.getSocial(), s -> new double[3]);
			row[0]++;
			row[1] += traits.loner() ? 1 : 0;
			row[2] += traits.leadership();
		}
		final StringBuilder sb = new StringBuilder();
		for (Map.Entry<Float, double[]> entry : bySocial.entrySet())
		{
			final double[] row = entry.getValue();
			sb.append(sb.length() > 0 ? "; " : "").append(String.format(Locale.ROOT, "social %.1f: %d, loners %d, leadership %.2f", entry.getKey(), (int) row[0], (int) row[1], row[2] / row[0]));
		}
		LOGGER.info(getClass().getSimpleName() + ": SelfTest CLANLIFE: traits — " + sb + ".");

		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (canFound(hunter, now) && !isLoner(hunter) && !hunter.isDead())
			{
				free.add(hunter);
			}
		}
		free.sort(Comparator.comparingInt((Player p) -> -p.getLevel()));
		if (free.size() < 5)
		{
			LOGGER.warning(getClass().getSimpleName() + ": SelfTest CLANLIFE: needs five free hunters of level " + FOUND_LEVEL + "+ (not loners), found " + free.size() + ".");
			return;
		}
		final Player founder = free.get(0);
		_founding = founder.getObjectId();
		requestName(founder, name -> ThreadPool.execute(() -> selfTestClan(founder, free.subList(1, 5), name)));
	}

	private void selfTestClan(Player founder, List<Player> others, String name)
	{
		final String tag = getClass().getSimpleName() + ": SelfTest CLANLIFE: ";
		final Clan clan = found(founder, name, "self-test");
		if (clan == null)
		{
			LOGGER.warning(tag + "no clan, stop.");
			return;
		}
		_testClanId = clan.getId();
		LOGGER.info(tag + founder.getName() + " founded " + clan.getName() + " (name from brain " + (clan.getName().equals(name) ? "«" + name + "»" : "unusable: «" + name + "»") + ").");
		final long now = System.currentTimeMillis();
		final PhantomRelations relations = PhantomRelations.getInstance();
		final Player first = others.get(0);
		final Player second = others.get(1);
		final Player outsider = others.get(2);
		final Player living = others.get(3);
		try
		{
			// Двое друзей вступают.
			for (Player friend : List.of(first, second))
			{
				relations.record(friend, founder, PhantomRelations.Kind.REVIVED);
				final Player joined = recruit(clan, founder, List.of(friend), now, true);
				LOGGER.info(tag + friend.getName() + " (" + friend.getLevel() + ", friend " + relations.isFriend(friend, founder) + ") — " + (joined == friend ? "joined" : "did NOT join") + "; clan has " + clan.getMembersCount() + ".");
			}

			// 1-й уровень: трое, средний от 10-го, адена главы.
			final long adena = PhantomCompat.clanLevelAdena(1);
			if (founder.getAdena() < adena)
			{
				PhantomCompat.addAdena(founder, ItemProcessType.REWARD, adena - founder.getAdena(), null, false);
			}
			final long before = founder.getAdena();
			LOGGER.info(tag + "level 0 -> 1: " + (levelUp(clan, founder) ? "raised" : "NOT raised") + ", level " + clan.getLevel() + ", leader paid " + (before - founder.getAdena()) + " (expected " + adena + ").");
			LOGGER.info(tag + "level 1 -> 2 with " + clan.getMembersCount() + " members: " + (levelUp(clan, founder) ? "RAISED (unexpected)" : "waits (needs " + LEVEL_MEMBERS[1] + ")") + ".");

			// 5-й уровень: репутация и скиллы; адену из цены скиллов платит глава.
			clan.changeLevel(5);
			clan.addReputationScore(20000);
			PhantomCompat.addAdena(founder, ItemProcessType.REWARD, 1000000, null, false);
			final long adenaBefore = founder.getAdena();
			final List<String> learned = new ArrayList<>();
			for (int i = 0; i < 5; i++)
			{
				final Skill skill = learnSkills(clan, founder);
				if (skill != null)
				{
					learned.add(skill.getName() + " " + skill.getLevel());
				}
			}
			LOGGER.info(tag + "level 5, reputation 20000 -> learned " + (learned.isEmpty() ? "NOTHING" : String.join(", ", learned)) + "; reputation left " + clan.getReputationScore() + ", leader paid " + (adenaBefore - founder.getAdena()) + " adena.");
			final boolean six = levelUp(clan, founder);
			LOGGER.info(tag + "level 5 -> 6: " + (six ? "raised" : "waits") + " (max for the chronicle " + PhantomCompat.clanMaxLevel() + ", by clan EXP " + PhantomCompat.clanLevelByExp() + ", needs members " + feature("CLAN_LEVEL_6_REQUIREMENT") + " and reputation " + feature("CLAN_LEVEL_6_COST") + ", has " + clan.getMembersCount() + " and " + clan.getReputationScore() + ").");

			// Обида расходится по клану.
			final double was = relations.grudge(second, outsider);
			relations.record(first, outsider, PhantomRelations.Kind.ATTACKED);
			LOGGER.info(tag + outsider.getName() + " attacked " + first.getName() + ": " + second.getName() + " grudge " + String.format(Locale.ROOT, "%.2f -> %.2f", was, relations.grudge(second, outsider)) + " (expected +" + (PhantomHuntersConfig.HUNTER_CLAN_SHARE / 100.0) + ").");

			for (String line : describe(founder))
			{
				LOGGER.info(tag + ".clans: " + line);
			}
			final Answer ask = decideAsk(founder, outsider, clan, false);
			LOGGER.info(tag + outsider.getName() + " asks to join (\"возьмёшь в клан?\" is ask " + isAsk("возьмёшь в клан?") + ", \"иди в мой клан\" is ask " + isAsk("иди в мой клан") + "): " + (ask.accept ? "yes" : "no") + " (" + ask.why + ") — " + ask.fallback);

			// Верность: «живой» игрок со своим кланом зовёт соклановца.
			String testName = PhantomClans.TEST_CLAN;
			for (int i = 2; ClanTable.getInstance().getClanByName(testName) != null; i++)
			{
				testName = PhantomClans.TEST_CLAN + i;
			}
			final Clan playerClan = ClanTable.getInstance().createClan(living, testName);
			if (playerClan != null)
			{
				final PhantomClans clans = PhantomClans.getInstance();
				final boolean refused = clans.beforeInvite(living, second);
				LOGGER.info(tag + living.getName() + " (playing a living player, clan " + testName + ") invites " + second.getName() + " (loyalty " + String.format(Locale.ROOT, "%.2f", traits(second).loyalty()) + ", needs gratitude " + String.format(Locale.ROOT, "%.1f", switchGratitude(second)) + ", has " + String.format(Locale.ROOT, "%.1f", relations.gratitude(second, living)) + "): " + (refused && (second.getClan() == clan) ? "refused, stays" : "UNEXPECTED: clan " + (second.getClan() == null ? "none" : second.getClan().getName())) + ".");
				for (PhantomRelations.Kind kind : List.of(PhantomRelations.Kind.REVIVED, PhantomRelations.Kind.SAVED, PhantomRelations.Kind.HEALED, PhantomRelations.Kind.HUNTED, PhantomRelations.Kind.HUNTED, PhantomRelations.Kind.HUNTED))
				{
					relations.record(second, living, kind);
				}
				final boolean refusedAgain = clans.beforeInvite(living, second);
				if (!refusedAgain && playerClan.checkClanJoinCondition(living, second, 0))
				{
					clans.onInvited(living, second, 0);
				}
				LOGGER.info(tag + "after revive, save, heal and three hunts (gratitude " + String.format(Locale.ROOT, "%.1f", relations.gratitude(second, living)) + "): " + second.getName() + " is in " + (second.getClan() == null ? "no clan" : second.getClan().getName()) + (second.getClan() == playerClan ? " — left for the friend" : " — UNEXPECTED") + ".");
				ClanTable.getInstance().destroyClan(playerClan.getId());
				second.setClanJoinExpiryTime(0);
			}

			// Роспуск: один глава дольше трёх суток.
			PhantomCompat.leaveClan(first);
			first.setClanJoinExpiryTime(0);
			if (second.getClan() == clan)
			{
				PhantomCompat.leaveClan(second);
				second.setClanJoinExpiryTime(0);
			}
			final long later = System.currentTimeMillis();
			dissolveLonely(clan, later);
			GlobalVariablesManager.getInstance().set(LONELY_VARIABLE + clan.getId(), later - ((LONELY_HOURS + 1) * 3600000L));
			final boolean dissolved = dissolveLonely(clan, later);
			LOGGER.info(tag + "only the leader left for " + (LONELY_HOURS + 1) + " h: " + (dissolved && (ClanTable.getInstance().getClan(clan.getId()) == null) ? "dissolved" : "NOT dissolved") + ".");
			for (Player hunter : List.of(founder, first, second, outsider, living))
			{
				ThreadPool.execute(hunter::storeMe);
			}
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
		finally
		{
			_testClanId = 0;
		}
		LOGGER.info(tag + "done.");
	}

	private static boolean female(Player player)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
		return (template != null) && template.isFemale();
	}

	public static PhantomHunterClans getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomHunterClans INSTANCE = new PhantomHunterClans();
	}
}
