package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.l2jmobius.gameserver.config.GeneralConfig;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.WorldExchangeConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.data.xml.BuyListData;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Race;
import org.l2jmobius.gameserver.entity.actor.holders.player.ClientSettings;
import org.l2jmobius.gameserver.entity.actor.request.PartyRequest;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanWar;
import org.l2jmobius.gameserver.entity.clan.enums.ClanWarState;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.enums.WorldExchangeItemStatusType;
import org.l2jmobius.gameserver.entity.item.enums.WorldExchangeItemSubType;
import org.l2jmobius.gameserver.entity.item.enums.WorldExchangeSortType;
import org.l2jmobius.gameserver.entity.item.holders.ItemHolder;
import org.l2jmobius.gameserver.entity.item.holders.WorldExchangeHolder;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.CastleManager;
import org.l2jmobius.gameserver.managers.DatabaseSpawnManager;
import org.l2jmobius.gameserver.managers.FortManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.managers.WorldExchangeManager;
import org.l2jmobius.gameserver.mechanics.buylist.Product;
import org.l2jmobius.gameserver.mechanics.buylist.ProductList;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnAttackableKill;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.olympiad.CompetitionType;
import org.l2jmobius.gameserver.mechanics.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.siege.Castle;
import org.l2jmobius.gameserver.mechanics.siege.Fort;
import org.l2jmobius.gameserver.mechanics.siege.TaxType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.CommonSkill;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.holders.SkillLearn;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.clientpackets.RequestJoinPledge;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.AskJoinParty;
import org.l2jmobius.gameserver.network.serverpackets.AskJoinPledge;
import org.l2jmobius.gameserver.network.serverpackets.ExPledgeCount;
import org.l2jmobius.gameserver.network.serverpackets.JoinParty;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowInfoUpdate;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListAdd;
import org.l2jmobius.gameserver.network.serverpackets.PledgeShowMemberListDelete;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * L2Hub: отличия API хроник для общего кода фантомов (phantoms/core). У каждой хроники своя копия класса
 * в phantoms/&lt;id&gt;/java — сборка кладёт в исходники нужную. Здесь — Essence Samurai Crow (как Classic Tales Untold): адена и количество предметов {@code long},
 * есть камаэли и арбалеты, кнопки окон — текстуры {@code L2UI_CT1}. В отличие от CT-хроник: инстанс — объект {@code Instance},
 * вместо кармы — отрицательная репутация, нет зоны города, другая модель целей скиллов (цель + область), нет 7 печатей.
 */
public final class PhantomCompat
{
	/** Текстуры кнопок HTML-окон фантомов: нажатая и обычная. */
	public static final String BUTTON_BACK = "L2UI_CT1.Button_DF_Down";
	public static final String BUTTON_FORE = "L2UI_CT1.Button_DF";

	/** Широкие кнопки (вкладки окон): текстура, которая растягивается. */
	public static final String WIDE_BUTTON_BACK = "L2UI_CT1.Button_DF_Down";
	public static final String WIDE_BUTTON_FORE = "L2UI_CT1.Button_DF";
	/** Ширина таблиц окна охотников на доске Alt+B (поле справа от меню). */
	public static final int BOARD_WIDTH = 520;

	private PhantomCompat()
	{
	}

	public static boolean reduceAdena(Player player, ItemProcessType process, long count, WorldObject reference, boolean sendMessage)
	{
		return player.reduceAdena(process, count, reference, sendMessage);
	}

	public static void addAdena(Player player, ItemProcessType process, long count, WorldObject reference, boolean sendMessage)
	{
		player.addAdena(process, count, reference, sendMessage);
	}

	public static Item addItem(Player player, ItemProcessType process, int itemId, long count, Object reference)
	{
		return player.getInventory().addItem(process, itemId, count, player, reference);
	}

	public static TradeItem addTradeItem(TradeList list, int objectId, long count, long price)
	{
		return list.addItem(objectId, count, price);
	}

	/** Строка лавки скупки (раздел 4.29): вещь по шаблону, как {@code SetPrivateStoreListBuy}. */
	public static TradeItem addBuyItem(TradeList list, int itemId, long count, long price)
	{
		return list.addItemByItemId(itemId, count, price);
	}

	/** Живые рейд-боссы мира — для рейдов охотников: у Classic и Essence их держит {@code DatabaseSpawnManager} (вместе с другими NPC из базы). */
	public static Collection<? extends Npc> raidBosses()
	{
		return DatabaseSpawnManager.getInstance().getNpcs().values().stream().filter(Npc::isRaid).toList();
	}

	public static RequestTrade requestTrade(int objectId, int itemId, long count, long price)
	{
		return new RequestTrade(objectId, itemId, count, price);
	}

	public static boolean isCrossbow(Object type)
	{
		return (type instanceof WeaponType weapon) && weapon.isCrossbow();
	}

	/** Сосы без грейда: торговцы Classic и Essence продают только их (91927 Soulshot, 91930 Blessed Spiritshot), грейдовые там «Not Available». */
	public static int gradelessShot(boolean mage)
	{
		return mage ? 91930 : 91927;
	}

	/** Призванный слуга (не пет): у Classic и Essence их может быть несколько — любой. */
	public static Summon servitor(Player player)
	{
		return player.getAnyServitor();
	}

	/** Олимпиада: записать дворянина на матч без класса (у H5, Classic и Essence — OlympiadManager с CompetitionType). */
	public static boolean olympiadRegister(Player player)
	{
		return OlympiadManager.getInstance().registerNoble(player, CompetitionType.NON_CLASSED);
	}

	public static boolean olympiadRegistered(Player player)
	{
		return OlympiadManager.getInstance().isRegistered(player);
	}

	/** Родные зоны рас, которых нет в других хрониках. */
	public static void addHomeRegions(Map<Race, String> regions)
	{
		regions.put(Race.KAMAEL, "Kamael");
	}

	public static void sendMemberOfAnotherParty(Player requestor, Player target)
	{
		final SystemMessage sm = new SystemMessage(SystemMessageId.C1_IS_A_MEMBER_OF_ANOTHER_PARTY_AND_CANNOT_BE_INVITED);
		sm.addString(target.getName());
		requestor.sendPacket(sm);
	}

	public static void sendOlympiadNoInvite(Player requestor)
	{
		requestor.sendPacket(SystemMessageId.A_USER_CURRENTLY_PARTICIPATING_IN_THE_OLYMPIAD_CANNOT_SEND_PARTY_AND_FRIEND_INVITATIONS);
	}

	/** Вещи, которые AutoPlay не подбирает: у Classic список в {@code GeneralConfig}. */
	public static Set<Integer> ignoredAutoPickItems()
	{
		return GeneralConfig.IGNORED_AUTO_PICK_ITEMS;
	}

	/** Проход по геодате: у Classic инстанс — объект, 0 — основной мир. */
	public static boolean canMoveToTarget(GeoEngine geo, int x, int y, int z, int tx, int ty, int tz, int instanceId)
	{
		return geo.canMoveToTarget(x, y, z, tx, ty, tz, instanceId == 0 ? null : InstanceManager.getInstance().getInstance(instanceId));
	}

	public static void setInstanceId(Player player, int instanceId)
	{
		player.setInstanceById(instanceId);
	}

	/** Приглашающий принят в пати напарника: ответ клиенту и пати, если её ещё нет. */
	public static void acceptPartyJoin(Player requestor, PartyDistributionType distribution)
	{
		requestor.sendPacket(new JoinParty(1, requestor));
		if (!requestor.isInParty())
		{
			requestor.setParty(new Party(requestor, distribution));
		}
	}

	/** Новая пати с распределением лута: у Classic и Essence оно задаётся только пати. */
	public static void newParty(Player leader, PartyDistributionType distribution)
	{
		leader.setParty(new Party(leader, distribution));
	}

	public static boolean useMagic(Player player, Skill skill, boolean forceUse, boolean dontMove)
	{
		return player.useMagic(skill, null, forceUse, dontMove);
	}

	/** Карма как в CT: у Classic это отрицательная репутация. */
	public static int karma(Player player)
	{
		return Math.max(0, -player.getReputation());
	}

	/**
	 * Танатос (раздел 4.45): карма ПК — у хроник с репутацией это отрицательная репутация.
	 */
	public static void setKarma(Player player, int value)
	{
		player.setReputation(-value);
	}

	/** Город: зоны TOWN у Classic нет, города — мирные зоны. */
	public static boolean isInTown(Player player)
	{
		return player.isInsideZone(ZoneId.PEACE);
	}

	/** Цель моба в драке: у Classic ИИ держит её в {@code getTarget()}. */
	public static Creature attackTarget(Creature npc)
	{
		final WorldObject target = npc.getAI().getTarget();
		return (target != null) && target.isCreature() ? target.asCreature() : null;
	}

	/**
	 * Скилл бьёт врага: у Classic цель — враг ({@code ENEMY}, {@code ENEMY_ONLY}) или сам кастер (удар по области вокруг себя);
	 * что это атака, а не баф, общий код проверяет по эффектам урона.
	 */
	public static boolean isAttackSkillTarget(Skill skill)
	{
		final TargetType type = skill.getTargetType();
		return (type == TargetType.ENEMY) || (type == TargetType.ENEMY_ONLY) || (type == TargetType.SELF);
	}

	/** Сила атакующего скилла для выбора лучших: у Classic силы в скилле нет (она в эффекте) — магический уровень, при равных — расход MP. */
	public static double skillPower(Skill skill)
	{
		return skill.getMagicLevel() + ((skill.getMpConsume() + skill.getMpInitialConsume()) / 10000.0);
	}

	/** Баф кастуется на себя или на пати (у Classic пати — область вокруг себя). */
	public static boolean isSelfTarget(Skill skill)
	{
		return skill.getTargetType() == TargetType.SELF;
	}

	public static void rechargeShots(Player player)
	{
		player.rechargeShots(true, true, false);
	}

	/** Грейд, который персонаж носит без штрафа: уровень скилла Expertise (239), как у CT. */
	public static int expertiseLevel(Player player)
	{
		return Math.max(0, player.getSkillLevel(CommonSkill.EXPERTISE.getId()));
	}

	/** Штраф за грейд Classic считает сам в статах. */
	public static void refreshExpertisePenalty(Player player)
	{
	}

	public static void refreshOverloaded(Player player)
	{
		player.refreshOverloaded(true);
	}

	/** Семи печатей у Classic нет. */
	public static void leave7sDungeon(Player player)
	{
	}

	public static BuffInfo buffByAbnormal(Creature creature, AbnormalType type)
	{
		return creature.getEffectList().getFirstBuffInfoByAbnormalType(type);
	}

	/** Слушатель «игрок убил моба». */
	public static void addAttackableKillListener(Object owner, BiConsumer<Player, Attackable> handler)
	{
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> handler.accept(event.getAttacker(), event.getTarget()), owner));
	}

	/** «Приглашён не тот» (сообщение 152, у Essence — THE_TARGET_CANNOT_BE_INVITED). */
	public static void sendWrongInviteTarget(Player requestor)
	{
		requestor.sendPacket(SystemMessageId.THE_TARGET_CANNOT_BE_INVITED);
	}

	/** Цели скилла: у Classic событие их не несёт — цель кастера. */
	public static List<WorldObject> skillTargets(OnCreatureSkillUse event)
	{
		final List<WorldObject> targets = new ArrayList<>();
		final WorldObject target = event.getCaster().getTarget();
		if (target != null)
		{
			targets.add(target);
		}
		return targets;
	}

	// ---------- Семь печатей (docs/PHANTOMS.md, раздел 4.42): у хроники их нет ----------

	public static boolean sevenSigns()
	{
		return false;
	}

	public static int sevenSignsPeriod()
	{
		return -1;
	}

	public static int sevenSignsCycle()
	{
		return 0;
	}

	public static int sevenSignsCabal(Player player)
	{
		return 0;
	}

	public static int sevenSignsMembers(int cabal)
	{
		return 0;
	}

	public static int sevenSignsVotes(int cabal, int seal)
	{
		return 0;
	}

	public static int sevenSignsWinner()
	{
		return 0;
	}

	public static int sevenSignsSealOwner(int seal)
	{
		return 0;
	}

	public static boolean sevenSignsJoin(Player player, int cabal, int seal)
	{
		return false;
	}

	public static long sevenSignsContribute(Player player)
	{
		return 0;
	}

	/**
	 * Охотник зовёт живого игрока в пати (стратег, {@code [[PARTY имя]]}): обычное окно «вступить в пати?», как из RequestJoinParty — пати в ожидании и PartyRequest
	 * на 30 с; ответ разбирает RequestAnswerJoinParty Mobius.
	 * @return окно отправлено; {@code false} — у кого-то из двоих уже идёт другой запрос в пати или игрок в настройках клиента
	 *         не принимает приглашения от чужих (а охотник ему не друг и не из клана)
	 */
	public static boolean askJoinParty(Player requestor, Player target)
	{
		if (requestor.hasRequest(PartyRequest.class) || target.hasRequest(PartyRequest.class) || partyRequestsRestricted(target, requestor))
		{
			return false;
		}
		final Party party = new Party(requestor, PartyDistributionType.FINDERS_KEEPERS);
		party.setPendingInvitation(true);
		final PartyRequest request = new PartyRequest(requestor, target, party);
		request.scheduleTimeout(30 * 1000);
		requestor.addRequest(request);
		target.addRequest(request);
		target.sendPacket(new AskJoinParty(requestor.getAppearance().getVisibleName(), PartyDistributionType.FINDERS_KEEPERS));
		return true;
	}

	/** Запрет приглашений в настройках клиента игрока — как RequestJoinParty.checkInviteByIgnoredSettings: от чужих нельзя, от друзей и клана — если разрешено. */
	private static boolean partyRequestsRestricted(Player target, Player requestor)
	{
		final ClientSettings settings = target.getClientSettings();
		if (!settings.isPartyRequestRestrictedFromOthers())
		{
			return false;
		}
		final boolean clan = (target.getClan() != null) && (target.getClan() == requestor.getClan());
		return !((!settings.isPartyRequestRestrictedFromFriends() && target.getFriendList().contains(requestor.getObjectId())) || (!settings.isPartyRequestRestrictedFromClan() && clan));
	}

	/** Сколько человек в пати: AltPartyMaxMembers, как в RequestJoinParty. */
	public static int partyMax()
	{
		return PlayerConfig.ALT_PARTY_MAX_MEMBERS;
	}

	/**
	 * Для BUY стратега: кому из NPC разрешены списки торговцев.
	 * @param listIds номера списков (имена файлов data/buylists)
	 * @return id NPC-торговцев
	 */
	public static Set<Integer> merchantNpcIds(Collection<Integer> listIds)
	{
		final Set<Integer> npcs = new HashSet<>();
		for (int listId : listIds)
		{
			final ProductList list = BuyListData.getInstance().getBuyList(listId);
			if ((list != null) && (list.getNpcsAllowed() != null))
			{
				npcs.addAll(list.getNpcsAllowed());
			}
		}
		return npcs;
	}

	/**
	 * Для BUY стратега: что торговец продаёт по своим спискам и почём — с налогом, как в RequestBuyItem (налог замка и базовый налог товара).
	 * У торговцев бывают вещи с ценой 0 (у Essence их много) — они не в счёт: бесплатно игроку их не продадут.
	 * @param npc торговец в мире
	 * @param listIds номера списков (имена файлов data/buylists)
	 * @param goods куда сложить: id вещи → цена; одно и то же в двух списках — дешевле
	 */
	public static void merchantGoods(Npc npc, Collection<Integer> listIds, Map<Integer, Long> goods)
	{
		final double castleTax = npc.getCastleTaxRate(TaxType.BUY);
		for (int listId : listIds)
		{
			final ProductList list = BuyListData.getInstance().getBuyList(listId);
			if ((list == null) || !list.isNpcAllowed(npc.getId()))
			{
				continue;
			}
			for (Product product : list.getProducts())
			{
				if (product.getPrice() > 0)
				{
					goods.merge(product.getItemId(), (long) (product.getPrice() * (1 + castleTax + product.getBaseTaxRate())), Math::min);
				}
			}
		}
	}

	/**
	 * Охотник-друг принял приглашение в клан ({@link PhantomClans}, раздел 4.41): ветка «да» из RequestAnswerJoinPledge без пакетов самому фантому — клиента у него нет.
	 * @param requestor кто позвал; условия Mobius ({@code checkClanJoinCondition}) уже проверены в RequestJoinPledge
	 * @param phantom охотник
	 * @param pledgeType подразделение клана
	 */
	public static void joinClan(Player requestor, Player phantom, int pledgeType)
	{
		final Clan clan = requestor.getClan();
		phantom.setPledgeType(pledgeType);
		if (pledgeType == Clan.SUBUNIT_ACADEMY)
		{
			phantom.setPowerGrade(9);
			phantom.setLvlJoinedAcademy(phantom.getLevel());
		}
		else
		{
			phantom.setPowerGrade(5);
		}
		clan.addClanMember(phantom);
		phantom.setClanPrivileges(clan.getRankPrivs(phantom.getPowerGrade()));

		final SystemMessage sm = new SystemMessage(SystemMessageId.S1_HAS_JOINED_THE_CLAN);
		sm.addString(phantom.getName());
		clan.broadcastToOnlineMembers(sm);
		if (clan.getCastleId() > 0)
		{
			final Castle castle = CastleManager.getInstance().getCastleByOwner(clan);
			if (castle != null)
			{
				castle.giveResidentialSkills(phantom);
			}
		}
		if (clan.getFortId() > 0)
		{
			final Fort fort = FortManager.getInstance().getFortByOwner(clan);
			if (fort != null)
			{
				fort.giveResidentialSkills(phantom);
			}
		}
		clan.broadcastToOtherOnlineMembers(new PledgeShowMemberListAdd(phantom), phantom);
		clan.broadcastToOnlineMembers(new PledgeShowInfoUpdate(clan));
		clan.broadcastToOnlineMembers(new ExPledgeCount(clan));
		phantom.setClanJoinExpiryTime(0);
		phantom.broadcastUserInfo();
	}

	/**
	 * Охотник уходит из клана сам ({@link PhantomClans}: стал врагом соклановца) — как RequestWithdrawalPledge: штраф на вступление — ему, клану — нет.
	 * @param phantom охотник
	 */
	public static void leaveClan(Player phantom)
	{
		final Clan clan = phantom.getClan();
		if ((clan == null) || phantom.isClanLeader())
		{
			return;
		}
		clan.removeClanMember(phantom.getObjectId(), System.currentTimeMillis() + (PlayerConfig.ALT_CLAN_JOIN_MINS * 60000));

		final SystemMessage sm = new SystemMessage(SystemMessageId.S1_HAS_LEFT_THE_CLAN);
		sm.addString(phantom.getName());
		clan.broadcastToOnlineMembers(sm);
		clan.broadcastToOnlineMembers(new PledgeShowMemberListDelete(phantom.getName()));
		clan.broadcastToOnlineMembers(new ExPledgeCount(clan));
	}

	/**
	 * Глава клана охотников берёт живого игрока, который попросился шёпотом ({@link PhantomHunterClans}, раздел 4.50): обычное окно «вступить в клан?»,
	 * как из RequestJoinPledge; ответ разбирает RequestAnswerJoinPledge Mobius (условия — ещё раз там же).
	 * @return окно отправлено; {@code false} — у кого-то из двоих уже идёт другой запрос
	 */
	public static boolean askJoinClan(Player leader, Player target)
	{
		final Clan clan = leader.getClan();
		if ((clan == null) || !leader.getRequest().setRequest(target, new RequestJoinPledge()))
		{
			return false;
		}
		target.sendPacket(new AskJoinPledge(leader, clan.getName()));
		return true;
	}

	/** До какого уровня клан охотников растёт сам ({@link PhantomHunterClans}): Essence — 5, выше — клановый опыт Mobius. */
	public static int clanMaxLevel()
	{
		return 5;
	}

	/** Уровни клана выше 5-го — за клановый опыт Mobius (ClanLevelData), а не за репутацию и членов из Feature.ini ({@code ClanLevelNRequirement} не действует). */
	public static boolean clanLevelByExp()
	{
		return true;
	}

	/** Адена главы за 1-й и 2-й уровень клана — как в Clan.levelUpClan; за остальные — 0. */
	public static long clanLevelAdena(int level)
	{
		return level == 1 ? 150000 : level == 2 ? 300000 : 0;
	}

	/**
	 * Война кланов охотников (раздел 4.50): сразу взаимная ({@link ClanWarState#MUTUAL}), как если бы второй клан ответил на объявление.
	 * Счёт убийств и репутацию ведёт {@code ClanWar.onKill}.
	 */
	public static void startWar(Clan declarer, Clan other)
	{
		final ClanWar war = new ClanWar(declarer, other);
		war.mutualClanWarAccepted(declarer, other);
		war.mutualClanWarAccepted(other, declarer);
		ClanTable.getInstance().storeClanWars(war);
		refreshWar(declarer, other);
	}

	/** Конец войны — без победителя и штрафа репутации: запись войны просто убирается. */
	public static void endWar(Clan a, Clan b)
	{
		ClanTable.getInstance().deleteClanWars(a.getId(), b.getId());
		refreshWar(a, b);
	}

	/** Кланы воюют друг с другом (война взаимная). */
	public static boolean atWar(Clan a, Clan b)
	{
		if ((a == null) || (b == null))
		{
			return false;
		}
		final ClanWar war = a.getWarWith(b.getId());
		return (war != null) && (war.getState() == ClanWarState.MUTUAL);
	}

	private static void refreshWar(Clan a, Clan b)
	{
		for (Clan clan : List.of(a, b))
		{
			for (Player member : clan.getOnlineMembers(0))
			{
				member.broadcastUserInfo();
			}
		}
	}

	/**
	 * Адена в цене клан-скилла ({@code RequestAcquireSkill}, PLEDGE): у Classic и Essence — список «одно из»; если среди вариантов есть адена, платится она.
	 * Остальные предметы охотникам не нужны (раздел 4.50).
	 */
	public static long skillAdena(SkillLearn learn)
	{
		long adena = 0;
		for (List<ItemHolder> oneOf : learn.getRequiredItems())
		{
			for (ItemHolder item : oneOf)
			{
				if (item.getId() == Inventory.ADENA_ID)
				{
					adena += item.getCount();
					break;
				}
			}
		}
		return adena;
	}

	/**
	 * Заточка ивентовой вещи свитком (посох мастера Йоги, раздел 4.34): события MasterOfEnchanting в этой хронике нет.
	 * @return {@link Integer#MIN_VALUE} — свиток не подходит
	 */
	public static int enchantEventItem(Player player, Item scroll, Item item)
	{
		return Integer.MIN_VALUE;
	}

	// ---------- Мировая биржа (docs/PHANTOMS.md, раздел 4.51): фантом — игрок, всё — прямыми вызовами WorldExchangeManager ----------

	/**
	 * @return биржа включена (WorldExchange.ini: EnableWorldExchange)
	 */
	public static boolean exchangeEnabled()
	{
		return WorldExchangeConfig.ENABLE_WORLD_EXCHANGE;
	}

	/**
	 * @return L-Coin в сумке
	 */
	public static long lcoin(Player player)
	{
		return player.getInventory().getInventoryItemCount(Inventory.LCOIN_ID, -1);
	}

	/**
	 * Чужие выставленные лоты в разделах оружия, брони и бижутерии — как их видит покупатель в окне биржи.
	 * @param buyer охотник
	 * @return лоты, дешёвые первыми
	 */
	public static List<PhantomExchange.Lot> exchangeOffers(Player buyer)
	{
		final List<PhantomExchange.Lot> lots = new ArrayList<>();
		for (WorldExchangeItemSubType type : List.of(WorldExchangeItemSubType.WEAPON, WorldExchangeItemSubType.ARMOR, WorldExchangeItemSubType.ACCESSORY))
		{
			for (WorldExchangeHolder holder : WorldExchangeManager.getInstance().getItemBids(buyer.getObjectId(), type, WorldExchangeSortType.PRICE_ASCE, WorldExchangeConfig.WORLD_EXCHANGE_DEFAULT_LANG))
			{
				lots.add(exchangeLot(holder));
			}
		}
		return lots;
	}

	/**
	 * @param owner охотник
	 * @return его лоты: выставленные, проданные (ждут выручку) и просроченные (ждут возврата)
	 */
	public static List<PhantomExchange.Lot> exchangeOwnLots(Player owner)
	{
		final List<PhantomExchange.Lot> lots = new ArrayList<>();
		for (List<WorldExchangeHolder> holders : WorldExchangeManager.getInstance().getPlayerBids(owner.getObjectId()).values())
		{
			for (WorldExchangeHolder holder : holders)
			{
				lots.add(exchangeLot(holder));
			}
		}
		return lots;
	}

	private static PhantomExchange.Lot exchangeLot(WorldExchangeHolder holder)
	{
		final PhantomExchange.Status status = holder.getStoreType() == WorldExchangeItemStatusType.WORLD_EXCHANGE_SOLD ? PhantomExchange.Status.SOLD : holder.getStoreType() == WorldExchangeItemStatusType.WORLD_EXCHANGE_OUT_TIME ? PhantomExchange.Status.OUT_TIME : PhantomExchange.Status.REGISTERED;
		return new PhantomExchange.Lot(holder.getWorldExchangeId(), holder.getItemInstance(), holder.getPrice(), holder.getOldOwnerId(), status);
	}

	/**
	 * @return плата адены за лот этой цены — как в registerItemBid (AdenaFee, MaxAdenaFee)
	 */
	public static long exchangeFee(long price)
	{
		final long fee = Math.round(price * WorldExchangeConfig.WORLD_EXCHANGE_ADENA_FEE);
		return WorldExchangeConfig.WORLD_EXCHANGE_MAX_ADENA_FEE != -1 ? Math.min(fee, WorldExchangeConfig.WORLD_EXCHANGE_MAX_ADENA_FEE) : fee;
	}

	/**
	 * @return сколько L-Coin получит продавец за лот этой цены — как в takeBidMoney: минус LCoinFee, но не меньше одного L-Coin и не больше 20 000 (MaxLCoinFee)
	 */
	public static long exchangeNet(long price)
	{
		final long fee = Math.max(1, Math.min(20000, Math.round((price * WorldExchangeConfig.WORLD_EXCHANGE_LCOIN_TAX * 100) / 100)));
		return price - Math.min(fee, WorldExchangeConfig.WORLD_EXCHANGE_MAX_LCOIN_TAX != -1 ? WorldExchangeConfig.WORLD_EXCHANGE_MAX_LCOIN_TAX : Long.MAX_VALUE);
	}

	/**
	 * @return средняя цена лотов этой вещи на бирже, с проданными (0 — не было)
	 */
	public static long exchangeAverage(int itemId)
	{
		return WorldExchangeManager.getInstance().getAveragePriceOfItem(itemId);
	}

	/**
	 * Выставить вещь целиком. Раздел биржи Mobius узнаёт только из окна клиента (ExWorldExchangeItemList, putIfAbsent), а без раздела registerItemBid
	 * отрывает вещь из сумки и лота не делает — раздел ставим сами, если клиент его ещё не поставил.
	 * @param category раздел: 0 — оружие, 1 — броня, 2 — бижутерия
	 * @param price цена лота в L-Coin
	 * @return выставил
	 */
	public static boolean exchangeRegister(Player player, Item item, int category, long price)
	{
		final WorldExchangeManager manager = WorldExchangeManager.getInstance();
		manager.addCategoryType(List.of(item.getId()), category);
		final int before = manager.getPlayerBids(player.getObjectId()).getOrDefault(WorldExchangeItemStatusType.WORLD_EXCHANGE_REGISTERED, List.of()).size();
		manager.registerItemBid(player, item.getObjectId(), item.getCount(), price);
		return manager.getPlayerBids(player.getObjectId()).getOrDefault(WorldExchangeItemStatusType.WORLD_EXCHANGE_REGISTERED, List.of()).size() > before;
	}

	/**
	 * Купить лот за L-Coin.
	 * @return купил
	 */
	public static boolean exchangeBuy(Player player, long lotId)
	{
		final long before = lcoin(player);
		WorldExchangeManager.getInstance().buyItem(player, lotId);
		return lcoin(player) < before;
	}

	/**
	 * Свой проданный лот — забрать L-Coin, просроченный — вернуть вещь. Выставленный не трогает: getItemStatusAndMakeAction снял бы его с продажи.
	 * @return забрал
	 */
	public static boolean exchangeSettle(Player player, PhantomExchange.Lot lot)
	{
		if (lot.status() == PhantomExchange.Status.REGISTERED)
		{
			return false;
		}
		WorldExchangeManager.getInstance().getItemStatusAndMakeAction(player, lot.id());
		for (PhantomExchange.Lot left : exchangeOwnLots(player))
		{
			if (left.id() == lot.id())
			{
				return false;
			}
		}
		return true;
	}
}
