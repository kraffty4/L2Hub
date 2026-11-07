package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;

/**
 * L2Hub: подгруппы кланов охотников (docs/PHANTOMS.md, 4.106, ключ {@code HunterClanSubUnits}). Настоящие подгруппы Mobius: те же таблицы, та же цена в репутации, те же пределы.
 * <ul>
 * <li>академия — с 5-го уровня клана, бесплатно; в неё идут новые и старые члены до 40-го уровня без второй профессии;</li>
 * <li>гвардия — с 6-го, рыцари — с 7-го, за {@code ROYAL_GUARD_COST} и {@code KNIGHT_UNIT_COST} репутации; капитан — охотник основного состава;</li>
 * <li>выпуск из академии — как в Mobius: вторая профессия даёт клану репутацию и выводит из клана ({@code Player.setPlayerClass}), выпускник возвращается в тот же клан уже в основной состав.</li>
 * </ul>
 * Сколько людей в подгруппах, считается от мира: честная доля клана — охотники в мире на кланы охотников ({@link #unitSize}). Трогает только охотников в мире: живого игрока в клане охотников не переводит и капитаном не ставит.
 */
final class PhantomSubUnits
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSubUnits.class.getName());

	/** Клан запоминает своего ученика: выпускник вернётся сюда. */
	static final String ACADEMY_VARIABLE = "L2HubAcademyClan";
	/** Академия, гвардия, рыцари — с этих уровней клана (как в VillageMaster). */
	private static final int ACADEMY_LEVEL = 5;
	private static final int GUARD_LEVEL = 6;
	private static final int KNIGHT_LEVEL = 7;
	/** До этого уровня и без второй профессии — материал для академии (как в Clan.checkClanJoinCondition). */
	private static final int ACADEMY_MAX_LEVEL = 40;
	/** Столько членов нужно на 5-й уровень клана ({@code PhantomHunterClans.LEVEL_MEMBERS}): клан с малым аппетитом иначе не дорастёт до академии. */
	private static final int ACADEMY_MEMBERS = 10;
	/** Боевая подгруппа — когда в клане столько людей. */
	private static final int UNIT_MEMBERS = 15;
	/** Столько остаётся в основном составе, из него больше не забирают. */
	private static final int MAIN_KEEP = 8;
	/** Подгруппа — не меньше стольких, если честная доля клана мала. */
	private static final int UNIT_MIN = 4;
	/** Порядок открытия: сначала гвардия, потом рыцари; у Mobius гвардий две, рыцарей четыре. */
	private static final int[] COMBAT_UNITS =
	{
		Clan.SUBUNIT_ROYAL1,
		Clan.SUBUNIT_KNIGHT1,
		Clan.SUBUNIT_ROYAL2,
		Clan.SUBUNIT_KNIGHT2,
		Clan.SUBUNIT_KNIGHT3,
		Clan.SUBUNIT_KNIGHT4
	};
	private static final String[] ACADEMY_NAMES =
	{
		"Cadets",
		"Novices",
		"Youth"
	};
	private static final String[] GUARD_NAMES =
	{
		"Guard",
		"Wardens"
	};
	private static final String[] KNIGHT_NAMES =
	{
		"Knights",
		"Lancers",
		"Riders",
		"Vanguard"
	};

	private PhantomSubUnits()
	{
	}

	static boolean enabled()
	{
		return PhantomHuntersConfig.HUNTER_CLAN_SUB_UNITS;
	}

	/**
	 * @return годится в академию: до {@value #ACADEMY_MAX_LEVEL}-го уровня, без второй профессии
	 */
	static boolean academyMaterial(Player hunter)
	{
		return (hunter.getLevel() <= ACADEMY_MAX_LEVEL) && (hunter.getPlayerClass().level() < 2);
	}

	/**
	 * @return у клана есть академия и в ней есть место
	 */
	static boolean academyRoom(Clan clan)
	{
		return enabled() && (clan.getSubPledge(Clan.SUBUNIT_ACADEMY) != null) && (clan.getSubPledgeMembersCount(Clan.SUBUNIT_ACADEMY) < clan.getMaxNrOfMembers(Clan.SUBUNIT_ACADEMY));
	}

	/**
	 * Куда вступает новый член: материал для академии — туда, если есть место; остальные — в основной состав, а он полон — в боевую подгруппу с местом.
	 * @return тип подгруппы; ключ выключен — 0
	 */
	static int unitFor(Clan clan, Player hunter)
	{
		if (!enabled())
		{
			return 0;
		}
		if (academyMaterial(hunter) && academyRoom(clan))
		{
			return Clan.SUBUNIT_ACADEMY;
		}
		if (clan.getSubPledgeMembersCount(0) < clan.getMaxNrOfMembers(0))
		{
			return 0;
		}
		for (int type : COMBAT_UNITS)
		{
			if ((clan.getSubPledge(type) != null) && (clan.getSubPledgeMembersCount(type) < clan.getMaxNrOfMembers(type)))
			{
				return type;
			}
		}
		return 0;
	}

	/**
	 * Сытый клан с подгруппами: академия не считается, каждая боевая подгруппа прибавляет к аппетиту свой размер.
	 * @param appetite аппетит клана
	 * @param world охотников в мире
	 * @return клан набрал, сколько хотел
	 */
	static boolean sated(Clan clan, int appetite, int world)
	{
		if (!enabled())
		{
			return clan.getMembersCount() >= appetite;
		}
		int wanted = Math.max(appetite, ACADEMY_MEMBERS);
		for (int type : COMBAT_UNITS)
		{
			if (clan.getSubPledge(type) != null)
			{
				wanted += unitSize(clan, type, world);
			}
		}
		return (clan.getMembersCount() - clan.getSubPledgeMembersCount(Clan.SUBUNIT_ACADEMY)) >= wanted;
	}

	/**
	 * Размер боевой подгруппы от данных мира: половина честной доли клана (охотники в мире на кланы охотников), не больше предела Mobius.
	 */
	static int unitSize(Clan clan, int type, int world)
	{
		int clans = 0;
		for (Clan other : ClanTable.getInstance().getClans())
		{
			if (PhantomHunterClans.isHunterClan(other))
			{
				clans++;
			}
		}
		return Math.min(clan.getMaxNrOfMembers(type), Math.max(UNIT_MIN, world / Math.max(1, clans) / 2));
	}

	/**
	 * @return сколько репутации держать на следующую боевую подгруппу, чтобы клановые скиллы её не проели; 0 — пока не нужна
	 */
	static int reserve(Clan clan)
	{
		if (!enabled() || (clan.getMembersCount() < UNIT_MEMBERS))
		{
			return 0;
		}
		final int type = nextUnit(clan);
		return type == 0 ? 0 : cost(type);
	}

	/**
	 * Ход подгрупп: выпускники возвращаются, открывается новая подгруппа, свободное место капитана занято, один человек переходит за ход. Зовётся из {@code PhantomHunterClans.life} раз в минуту.
	 * @param leader глава в мире
	 * @param hunters охотники в мире
	 */
	static void control(Clan clan, Player leader, List<Player> hunters)
	{
		if (!enabled())
		{
			return;
		}
		try
		{
			graduates(clan, leader, hunters);
			open(clan, leader, hunters);
			captains(clan);
			move(clan, hunters.size());
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, PhantomSubUnits.class.getSimpleName() + ": clan " + clan.getName() + ": " + e.getMessage(), e);
		}
	}

	/**
	 * Выпускник академии свободен (Mobius вывел его из клана без штрафа): возвращается в тот же клан, уже не в академию.
	 */
	private static void graduates(Clan clan, Player leader, List<Player> hunters)
	{
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() != null) || (hunter.getVariables().getInt(ACADEMY_VARIABLE, 0) != clan.getId()))
			{
				continue;
			}
			hunter.getVariables().remove(ACADEMY_VARIABLE);
			if (!academyMaterial(hunter) && PhantomHunterClans.getInstance().join(clan, leader, hunter, true))
			{
				LOGGER.info(PhantomSubUnits.class.getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") graduated from the academy of clan " + clan.getName() + " and came back, unit " + hunter.getPledgeType() + ", reputation " + clan.getReputationScore() + ".");
				PhantomNews.post("clan", hunter.getName() + " окончил академию клана «" + clan.getName() + "» и остался в клане", hunter.getName(), hunter);
			}
		}
	}

	/**
	 * Открыть подгруппу, если клан дорос и есть кого в неё взять. Цену в репутации берёт сам {@code Clan.createSubPledge}.
	 */
	private static void open(Clan clan, Player leader, List<Player> hunters)
	{
		if ((clan.getLevel() >= ACADEMY_LEVEL) && (clan.getSubPledge(Clan.SUBUNIT_ACADEMY) == null) && hasAcademyMaterial(clan, hunters))
		{
			create(clan, leader, Clan.SUBUNIT_ACADEMY, 0);
			return;
		}
		if (clan.getMembersCount() < UNIT_MEMBERS)
		{
			return;
		}
		final int type = nextUnit(clan);
		if ((type == 0) || (clan.getReputationScore() < cost(type)))
		{
			return;
		}
		final Player captain = captain(clan);
		if ((captain != null) && (clan.getSubPledgeMembersCount(0) > MAIN_KEEP))
		{
			create(clan, leader, type, captain.getObjectId());
		}
	}

	/**
	 * @return следующая боевая подгруппа, которую клан может открыть по уровню; прошлые ещё не набраны — 0
	 */
	private static int nextUnit(Clan clan)
	{
		for (int type : COMBAT_UNITS)
		{
			if (clan.getSubPledge(type) == null)
			{
				return clan.getLevel() >= (type < Clan.SUBUNIT_KNIGHT1 ? GUARD_LEVEL : KNIGHT_LEVEL) ? type : 0;
			}
			if (clan.getSubPledgeMembersCount(type) < UNIT_MIN)
			{
				return 0;
			}
		}
		return 0;
	}

	private static int cost(int type)
	{
		return type < Clan.SUBUNIT_KNIGHT1 ? FeatureConfig.ROYAL_GUARD_COST : FeatureConfig.KNIGHT_UNIT_COST;
	}

	private static boolean hasAcademyMaterial(Clan clan, List<Player> hunters)
	{
		for (ClanMember member : clan.getMembers())
		{
			final Player mate = member.getPlayer();
			if ((mate != null) && mate.isOnline() && (member.getObjectId() != clan.getLeaderId()) && (member.getPledgeType() == 0) && PhantomHunterClans.isHunter(mate) && academyMaterial(mate))
			{
				return true;
			}
		}
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() == null) && academyMaterial(hunter))
			{
				return true;
			}
		}
		return false;
	}

	private static void create(Clan clan, Player leader, int type, int captainId)
	{
		final String name = freeName(clan, type);
		if ((name == null) || (clan.createSubPledge(leader, type, captainId, name) == null))
		{
			LOGGER.info(PhantomSubUnits.class.getSimpleName() + ": clan " + clan.getName() + " did not open unit " + type + " (name " + name + ", reputation " + clan.getReputationScore() + ").");
			return;
		}
		ThreadPool.execute(clan::updateClanInDB);
		LOGGER.info(PhantomSubUnits.class.getSimpleName() + ": clan " + clan.getName() + " (level " + clan.getLevel() + ") opened unit " + type + " «" + name + "», captain " + captainId + ", reputation left " + clan.getReputationScore() + ".");
		final String what = type == Clan.SUBUNIT_ACADEMY ? "академию" : type < Clan.SUBUNIT_KNIGHT1 ? "гвардию" : "рыцарский орден";
		PhantomNews.post("clan", "Клан пешек «" + clan.getName() + "» открыл " + what + " «" + name + "»", clan.getLeaderName(), leader);
	}

	/**
	 * @return свободное имя подгруппы: начало имени клана и слово; имя у Mobius — латиница и цифры, до 16 знаков, единое на весь мир
	 */
	private static String freeName(Clan clan, int type)
	{
		final String[] words = type == Clan.SUBUNIT_ACADEMY ? ACADEMY_NAMES : type < Clan.SUBUNIT_KNIGHT1 ? GUARD_NAMES : KNIGHT_NAMES;
		for (String word : words)
		{
			final String head = clan.getName().substring(0, Math.min(clan.getName().length(), 16 - word.length()));
			final String name = head + word;
			boolean taken = false;
			for (Clan other : ClanTable.getInstance().getClans())
			{
				taken |= other.getSubPledge(name) != null;
			}
			if (!taken)
			{
				return name;
			}
		}
		return null;
	}

	/**
	 * Подгруппа без капитана (он ушёл из клана): назначить, как глава у NPC.
	 */
	private static void captains(Clan clan)
	{
		for (int type : COMBAT_UNITS)
		{
			final Clan.SubPledge unit = clan.getSubPledge(type);
			if ((unit == null) || ((unit.getLeaderId() != 0) && (clan.getClanMember(unit.getLeaderId()) != null)))
			{
				continue;
			}
			final Player captain = captain(clan);
			if (captain != null)
			{
				unit.setLeaderId(captain.getObjectId());
				clan.updateSubPledgeInDB(type);
				clan.broadcastClanStatus();
				captain.setPledgeClass(ClanMember.calculatePledgeClass(captain));
				captain.broadcastUserInfo();
				LOGGER.info(PhantomSubUnits.class.getSimpleName() + ": " + captain.getName() + " is the captain of «" + unit.getName() + "» of clan " + clan.getName() + ".");
			}
		}
	}

	/**
	 * @return сильнейший охотник основного состава в мире: не глава и не капитан; {@code null} — нет такого
	 */
	private static Player captain(Clan clan)
	{
		return mainHunters(clan).stream().max(Comparator.comparingInt(Player::getLevel)).orElse(null);
	}

	private static List<Player> mainHunters(Clan clan)
	{
		final List<Player> result = new ArrayList<>();
		for (ClanMember member : clan.getMembers())
		{
			final Player mate = member.getPlayer();
			if ((mate != null) && mate.isOnline() && (member.getPledgeType() == 0) && (member.getObjectId() != clan.getLeaderId()) && (clan.getLeaderSubPledge(member.getObjectId()) == 0) && PhantomHunterClans.isHunter(mate))
			{
				result.add(mate);
			}
		}
		return result;
	}

	/**
	 * Один переход за ход: сначала материал для академии, потом сильнейший — в гвардию, слабейший — к рыцарям, пока подгруппа меньше своего размера и основной состав больше {@value #MAIN_KEEP}.
	 * @param world охотников в мире
	 * @return тот, кто перешёл, или {@code null}
	 */
	static Player move(Clan clan, int world)
	{
		final List<Player> main = mainHunters(clan);
		if (academyRoom(clan))
		{
			for (Player mate : main)
			{
				if (academyMaterial(mate))
				{
					transfer(clan, mate, Clan.SUBUNIT_ACADEMY);
					return mate;
				}
			}
		}
		if (clan.getSubPledgeMembersCount(0) <= MAIN_KEEP)
		{
			return null;
		}
		for (int type : COMBAT_UNITS)
		{
			if ((clan.getSubPledge(type) == null) || (clan.getSubPledgeMembersCount(type) >= unitSize(clan, type, world)))
			{
				continue;
			}
			final Comparator<Player> byLevel = Comparator.comparingInt(Player::getLevel);
			final Player mate = main.stream().max(type < Clan.SUBUNIT_KNIGHT1 ? byLevel : byLevel.reversed()).orElse(null);
			if (mate != null)
			{
				transfer(clan, mate, type);
			}
			return mate;
		}
		return null;
	}

	/**
	 * Перевод, как {@code RequestPledgeReorganizeMember}, плюс то, что в академии Mobius делает при вступлении: ранг 9 и уровень поступления (от него зависит репутация за выпуск).
	 */
	private static void transfer(Clan clan, Player hunter, int type)
	{
		final ClanMember member = clan.getClanMember(hunter.getObjectId());
		member.setPledgeType(type);
		if (type == Clan.SUBUNIT_ACADEMY)
		{
			member.setPowerGrade(9);
			hunter.setLvlJoinedAcademy(hunter.getLevel());
			hunter.setClanPrivileges(clan.getRankPrivs(9));
			hunter.getVariables().set(ACADEMY_VARIABLE, clan.getId());
		}
		hunter.setPledgeClass(ClanMember.calculatePledgeClass(hunter));
		clan.broadcastClanStatus();
		hunter.broadcastUserInfo();
		ThreadPool.execute(hunter::storeMe);
		LOGGER.info(PhantomSubUnits.class.getSimpleName() + ": " + hunter.getName() + " (" + hunter.getLevel() + ") moved to unit " + type + " «" + clan.getSubPledge(type).getName() + "» of clan " + clan.getName() + ".");
	}

	/**
	 * Самопроверка SUBUNITS ({@code HuntersSelfTest}, только на копии базы: уровень и репутация клана поднимаются на месте, подгруппы ложатся в базу). Самый большой клан охотников: академия, гвардия, рыцари, капитаны, переходы;
	 * новичок вступает в академию; выпуск даёт репутацию и возвращает; живой не трогается; ключ выключен — всё как раньше.
	 * @param hunters охотники в мире
	 */
	static void selfTest(List<Player> hunters)
	{
		final String tag = PhantomSubUnits.class.getSimpleName() + ": SelfTest SUBUNITS: ";
		try
		{
			Clan clan = null;
			for (Clan other : ClanTable.getInstance().getClans())
			{
				final Player head = other.getLeader() == null ? null : other.getLeader().getPlayer();
				if (PhantomHunterClans.isHunterClan(other) && (head != null) && head.isOnline() && ((clan == null) || (other.getMembersCount() > clan.getMembersCount())))
				{
					clan = other;
				}
			}
			if (clan == null)
			{
				LOGGER.warning(tag + "needs a hunter clan with its leader in the world.");
				return;
			}
			final Player leader = clan.getLeader().getPlayer();
			final int world = hunters.size();
			LOGGER.info(tag + "clan " + clan.getName() + ", level " + clan.getLevel() + ", members " + clan.getMembersCount() + " (online main " + mainHunters(clan).size() + "), reputation " + clan.getReputationScore() + ", world " + world + ", unit size " + unitSize(clan, Clan.SUBUNIT_ROYAL1, world) + ", costs " + FeatureConfig.ROYAL_GUARD_COST + "/" + FeatureConfig.KNIGHT_UNIT_COST + ".");

			// До 5-го уровня подгрупп нет, и ключ выключен — ничего не меняется.
			final int levelWas = clan.getLevel();
			clan.changeLevel(4);
			control(clan, leader, hunters);
			check(tag, "1. уровень 4: подгрупп нет", clan.getAllSubPledges().isEmpty(), "подгрупп " + clan.getAllSubPledges().size());
			clan.changeLevel(5);
			PhantomHuntersConfig.HUNTER_CLAN_SUB_UNITS = false;
			control(clan, leader, hunters);
			final boolean offEmpty = clan.getAllSubPledges().isEmpty();
			final Player junior = junior(hunters);
			final int offUnit = junior == null ? 0 : unitFor(clan, junior);
			PhantomHuntersConfig.HUNTER_CLAN_SUB_UNITS = true;
			check(tag, "2. ключ выключен: подгрупп нет, вступают в основной состав", offEmpty && (offUnit == 0), "уровень был " + levelWas);

			// Академия бесплатна и с 5-го уровня.
			final int repBefore = clan.getReputationScore();
			control(clan, leader, hunters);
			final Clan.SubPledge academy = clan.getSubPledge(Clan.SUBUNIT_ACADEMY);
			check(tag, "3. академия открыта бесплатно", (academy != null) && (clan.getReputationScore() == repBefore), academy == null ? "нет (материала в мире нет?)" : academy.getName() + ", репутация " + repBefore + " -> " + clan.getReputationScore());

			// Новичок вступает в академию настоящим вступлением.
			if ((junior != null) && (academy != null))
			{
				final int unit = unitFor(clan, junior);
				final boolean joined = PhantomHunterClans.getInstance().join(clan, leader, junior, true);
				check(tag, "4. новичок " + junior.getName() + " (" + junior.getLevel() + ") вступил в академию", joined && (unit == Clan.SUBUNIT_ACADEMY) && (junior.getPledgeType() == Clan.SUBUNIT_ACADEMY) && (junior.getPowerGrade() == 9) && (junior.getLvlJoinedAcademy() > 0), "тип " + junior.getPledgeType() + ", ранг " + junior.getPowerGrade() + ", поступил на " + junior.getLvlJoinedAcademy());
				final int repGraduate = clan.getReputationScore();
				final int classId = junior.getPlayerClass().getId();
				final int second = secondClass(junior);
				if (second > 0)
				{
					junior.setPlayerClass(second);
					final boolean ousted = junior.getClan() == null;
					final int gained = clan.getReputationScore() - repGraduate;
					graduates(clan, leader, hunters);
					check(tag, "5. выпуск: вторая профессия, репутация клану, вышел и вернулся в основной состав", ousted && (gained > 0) && (junior.getClan() == clan) && (junior.getPledgeType() == 0) && !junior.getVariables().hasVariable(ACADEMY_VARIABLE), "вышел " + ousted + ", репутация +" + gained + ", клан " + (junior.getClan() == null ? "нет" : junior.getClan().getName()) + ", тип " + junior.getPledgeType() + ", класс " + classId + " -> " + second);
				}
				else
				{
					LOGGER.info(tag + "5. SKIP: для " + junior.getName() + " не нашлось второй профессии.");
				}
			}
			else
			{
				LOGGER.info(tag + "4. SKIP: нет охотника без клана до 40-го уровня без второй профессии (" + (junior == null ? "не нашёл" : junior.getName()) + ").");
			}

			// Гвардия и рыцари: уровень, репутация и люди — как у взрослого клана (свободные охотники, уже со второй профессией, иначе уйдут в академию); размер — от мира.
			clan.changeLevel(7);
			clan.addReputationScore(30000);
			for (Player hunter : hunters)
			{
				final int promoted = secondClass(hunter);
				if ((clan.getMembersCount() < 22) && (hunter.getClan() == null) && (promoted > 0) && PhantomHunterClans.isHunter(hunter))
				{
					if (academyMaterial(hunter))
					{
						hunter.setPlayerClass(promoted);
					}
					hunter.setClanJoinExpiryTime(0);
					PhantomHunterClans.getInstance().join(clan, leader, hunter, true);
				}
			}
			for (int i = 0; (i < 30) && (clan.getSubPledge(Clan.SUBUNIT_KNIGHT1) == null); i++)
			{
				control(clan, leader, hunters);
			}
			final Clan.SubPledge guard = clan.getSubPledge(Clan.SUBUNIT_ROYAL1);
			final Clan.SubPledge knights = clan.getSubPledge(Clan.SUBUNIT_KNIGHT1);
			check(tag, "6. гвардия открыта, капитан из основного состава", (guard != null) && (guard.getLeaderId() != 0) && (guard.getLeaderId() != clan.getLeaderId()), guard == null ? "нет: основной состав " + clan.getSubPledgeMembersCount(0) + ", в мире " + mainHunters(clan).size() : guard.getName() + ", капитан " + guard.getLeaderId() + ", людей " + clan.getSubPledgeMembersCount(Clan.SUBUNIT_ROYAL1));
			check(tag, "7. рыцари открыты", knights != null, knights == null ? "нет: гвардия " + clan.getSubPledgeMembersCount(Clan.SUBUNIT_ROYAL1) + " из " + unitSize(clan, Clan.SUBUNIT_ROYAL1, world) + ", основной состав " + clan.getSubPledgeMembersCount(0) + ", репутация " + clan.getReputationScore() : knights.getName() + ", капитан " + knights.getLeaderId() + ", людей " + clan.getSubPledgeMembersCount(Clan.SUBUNIT_KNIGHT1) + ", репутация " + clan.getReputationScore());

			// Живой игрок не переводится и капитаном не становится: mainHunters берёт только охотников.
			boolean clean = true;
			for (ClanMember member : clan.getMembers())
			{
				if ((member.getPledgeType() != 0) && (PhantomData.getInstance().getTemplate(member.getName()) == null))
				{
					clean = false;
				}
			}
			check(tag, "8. в подгруппах только охотники", clean, "");

			// Капитан ушёл — на его место другой.
			if (guard != null)
			{
				final ClanMember head = clan.getClanMember(guard.getLeaderId());
				final Player old = head == null ? null : head.getPlayer();
				if ((old != null) && !old.isClanLeader())
				{
					PhantomCompat.leaveClan(old);
					old.setClanJoinExpiryTime(0);
					final int vacant = guard.getLeaderId();
					captains(clan);
					check(tag, "9. капитан ушёл, назначен другой", (vacant == 0) && (guard.getLeaderId() != 0) && (clan.getClanMember(guard.getLeaderId()) != null), "было " + vacant + ", стал " + guard.getLeaderId());
				}
			}

			final StringBuilder units = new StringBuilder();
			for (Clan.SubPledge unit : clan.getAllSubPledges())
			{
				units.append(unit.getId()).append(" «").append(unit.getName()).append("» ").append(clan.getSubPledgeMembersCount(unit.getId())).append(" из ").append(clan.getMaxNrOfMembers(unit.getId())).append("; ");
			}
			LOGGER.info(tag + "units: " + units + "main " + clan.getSubPledgeMembersCount(0) + " of " + clan.getMaxNrOfMembers(0) + ", members " + clan.getMembersCount() + ", sated " + sated(clan, PhantomHunterClans.getInstance().appetite(clan), world) + ".");
			ThreadPool.execute(clan::updateClanInDB);
			LOGGER.info(tag + "done.");
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
		}
	}

	private static Player junior(List<Player> hunters)
	{
		for (Player hunter : hunters)
		{
			if ((hunter.getClan() == null) && academyMaterial(hunter) && PhantomHunterClans.isHunter(hunter) && (hunter.getClanJoinExpiryTime() <= System.currentTimeMillis()))
			{
				return hunter;
			}
		}
		return null;
	}

	/**
	 * @return вторая профессия из шаблона охотника или 0
	 */
	private static int secondClass(Player hunter)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		return template == null ? 0 : template.getClassId(40);
	}

	private static void check(String tag, String step, boolean ok, String detail)
	{
		LOGGER.info(tag + step + ": " + (ok ? "OK" : "FAIL") + (detail.isEmpty() ? "" : " (" + detail + ")") + ".");
	}
}
