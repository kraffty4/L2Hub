package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.l2jmobius.gameserver.config.FeatureConfig;
import org.l2jmobius.gameserver.config.custom.AutoPlayConfig;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Attackable;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
import org.l2jmobius.gameserver.entity.actor.enums.creature.Race;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.RaidBossSpawnManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.attackable.OnAttackableKill;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.olympiad.CompetitionType;
import org.l2jmobius.gameserver.mechanics.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.sevensigns.SevenSigns;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.targets.TargetType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.holders.RequestTrade;
import org.l2jmobius.gameserver.network.holders.TradeItem;
import org.l2jmobius.gameserver.network.holders.TradeList;
import org.l2jmobius.gameserver.network.serverpackets.JoinParty;
import org.l2jmobius.gameserver.network.serverpackets.SystemMessage;

/**
 * L2Hub: отличия API хроник для общего кода фантомов (phantoms/core). У каждой хроники своя копия класса
 * в phantoms/&lt;id&gt;/java — сборка кладёт в исходники нужную. Здесь — High Five: адена и количество предметов {@code long},
 * есть камаэли и арбалеты, кнопки окон — текстуры {@code L2UI_CT1}.
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

	/** Живые рейд-боссы мира — для рейдов охотников (раздел 4.32): здесь их держит {@code RaidBossSpawnManager}. */
	public static Collection<? extends Npc> raidBosses()
	{
		return RaidBossSpawnManager.getInstance().getBosses().values();
	}

	public static RequestTrade requestTrade(int objectId, int itemId, long count, long price)
	{
		return new RequestTrade(objectId, itemId, count, price);
	}

	public static boolean isCrossbow(Object type)
	{
		return type == WeaponType.CROSSBOW;
	}

	/** Сосы без грейда, если торговцы хроники продают только их (Classic, Essence); 0 — сосы по грейду оружия (таблицы PhantomHunterOutfit). */
	public static int gradelessShot(boolean mage)
	{
		return 0;
	}

	/** Призванный слуга (не пет): у CT он один — getSummon(). */
	public static Summon servitor(Player player)
	{
		final Summon summon = player.getSummon();
		return (summon != null) && summon.isServitor() ? summon : null;
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

	/** Цели атакующих скиллов охотника: по одной цели и по области. */
	private static final Set<TargetType> ATTACK_TARGETS = Set.of(TargetType.ONE, TargetType.AREA, TargetType.FRONT_AREA, TargetType.AURA, TargetType.FRONT_AURA, TargetType.BEHIND_AREA, TargetType.BEHIND_AURA);

	/** Вещи, которые AutoPlay не подбирает. */
	public static Set<Integer> ignoredAutoPickItems()
	{
		return AutoPlayConfig.IGNORED_AUTO_PICK_ITEMS;
	}

	/** Проход по геодате; у Classic и Essence инстанс — объект. */
	public static boolean canMoveToTarget(GeoEngine geo, int x, int y, int z, int tx, int ty, int tz, int instanceId)
	{
		return geo.canMoveToTarget(x, y, z, tx, ty, tz, instanceId);
	}

	public static void setInstanceId(Player player, int instanceId)
	{
		player.setInstanceId(instanceId);
	}

	/** Приглашающий принят в пати напарника: ответ клиенту и пати, если её ещё нет. */
	public static void acceptPartyJoin(Player requestor, PartyDistributionType distribution)
	{
		requestor.sendPacket(new JoinParty(1));
		if (!requestor.isInParty())
		{
			requestor.setPartyDistributionType(distribution);
			requestor.setParty(new Party(requestor, distribution));
		}
	}

	/** Новая пати с распределением лута. */
	public static void newParty(Player leader, PartyDistributionType distribution)
	{
		leader.setPartyDistributionType(distribution);
		leader.setParty(new Party(leader, distribution));
	}

	public static boolean useMagic(Player player, Skill skill, boolean forceUse, boolean dontMove)
	{
		return player.useMagic(skill, forceUse, dontMove);
	}

	public static int karma(Player player)
	{
		return player.getKarma();
	}

	public static boolean isInTown(Player player)
	{
		return player.isInsideZone(ZoneId.TOWN);
	}

	/** Цель моба в драке: {@code AttackableAI} держит её только в ИИ, {@code getTarget()} моба пустой или старый (патч 0034). */
	public static Creature attackTarget(Creature npc)
	{
		return npc.getAI().getAttackTarget();
	}

	/** Скилл бьёт врага по одной цели или по области; что это атака, а не баф, общий код проверяет по эффектам урона. */
	public static boolean isAttackSkillTarget(Skill skill)
	{
		return ATTACK_TARGETS.contains(skill.getTargetType());
	}

	/** Сила атакующего скилла для выбора лучших. */
	public static double skillPower(Skill skill)
	{
		return skill.getPower();
	}

	/** Баф кастуется на себя или на пати. */
	public static boolean isSelfTarget(Skill skill)
	{
		final TargetType type = skill.getTargetType();
		return (type == TargetType.SELF) || (type == TargetType.PARTY);
	}

	public static void rechargeShots(Player player)
	{
		player.rechargeShots(true, true);
	}

	public static int expertiseLevel(Player player)
	{
		return player.getExpertiseLevel();
	}

	public static void refreshExpertisePenalty(Player player)
	{
		player.refreshExpertisePenalty();
	}

	public static void refreshOverloaded(Player player)
	{
		player.refreshOverloaded();
	}

	public static void leave7sDungeon(Player player)
	{
		player.setIn7sDungeon(false);
	}

	public static BuffInfo buffByAbnormal(Creature creature, AbnormalType type)
	{
		return creature.getEffectList().getBuffInfoByAbnormalType(type);
	}

	/** Слушатель «игрок убил моба». */
	public static void addAttackableKillListener(Object owner, BiConsumer<Player, Attackable> handler)
	{
		Containers.Global().addListener(new ConsumerEventListener(Containers.Global(), EventType.ON_ATTACKABLE_KILL, (OnAttackableKill event) -> handler.accept(event.getAttacker(), event.getTarget()), owner));
	}

	/** «Приглашён не тот» (сообщение 152). */
	public static void sendWrongInviteTarget(Player requestor)
	{
		requestor.sendPacket(SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET);
	}

	/** Цели скилла: все из события, а если их нет — основная цель. */
	public static List<WorldObject> skillTargets(OnCreatureSkillUse event)
	{
		final Collection<WorldObject> targets = event.getTargets();
		final List<WorldObject> all = new ArrayList<>(targets != null ? targets : List.of());
		if (all.isEmpty() && (event.getTarget() != null))
		{
			all.add(event.getTarget());
		}
		return all;
	}

	// ---------- Семь печатей (docs/PHANTOMS.md, раздел 4.42) ----------

	/** @return у хроники есть Семь печатей */
	public static boolean sevenSigns()
	{
		return true;
	}

	/** @return период: 0 — набор, 1 — соревнование, 2 — итоги, 3 — проверка печатей */
	public static int sevenSignsPeriod()
	{
		return SevenSigns.getInstance().getCurrentPeriod();
	}

	public static int sevenSignsCycle()
	{
		return SevenSigns.getInstance().getCurrentCycle();
	}

	/** @return сторона игрока: 0 — нет, 1 — Закат, 2 — Рассвет */
	public static int sevenSignsCabal(Player player)
	{
		return SevenSigns.getInstance().getPlayerCabal(player.getObjectId());
	}

	/** @return сторонников стороны */
	public static int sevenSignsMembers(int cabal)
	{
		return SevenSigns.getInstance().getTotalMembers(cabal);
	}

	/** @return голосов стороны за печать: 1 — Алчность, 2 — Гнозис, 3 — Раздор */
	public static int sevenSignsVotes(int cabal, int seal)
	{
		return SevenSigns.getInstance().getSealProportion(seal, cabal);
	}

	/** @return сторона с наибольшим счётом (0 — ничья) */
	public static int sevenSignsWinner()
	{
		return SevenSigns.getInstance().getCabalHighestScore();
	}

	/** @return чья печать (0 — ничья) */
	public static int sevenSignsSealOwner(int seal)
	{
		return SevenSigns.getInstance().getSealOwner(seal);
	}

	/**
	 * Вступить, как у жреца ({@code SignsPriest}): в Закат нельзя клану с замком, в Рассвет после второй профы — за адену, если у клана нет замка.
	 * @return вступил
	 */
	public static boolean sevenSignsJoin(Player player, int cabal, int seal)
	{
		final boolean castle = (player.getClan() != null) && (player.getClan().getCastleId() > 0);
		if ((cabal == SevenSigns.CABAL_DUSK) && FeatureConfig.ALT_GAME_CASTLE_DUSK && castle)
		{
			return false;
		}
		if ((cabal == SevenSigns.CABAL_DAWN) && FeatureConfig.ALT_GAME_CASTLE_DAWN && (player.getPlayerClass().level() >= 2) && !castle && !reduceAdena(player, ItemProcessType.FEE, FeatureConfig.SSQ_JOIN_DAWN_ADENA_FEE, player, false))
		{
			return false;
		}
		SevenSigns.getInstance().setPlayerInfo(player.getObjectId(), cabal, seal);
		return true;
	}

	/**
	 * Сдать жрецу все печатные камни: вклад и древняя адена — как у {@code SignsPriest}.
	 * @return очки вклада; 0 — нечего сдавать; -1 — вклад на пределе ({@code AltMaxPlayerContrib})
	 */
	public static long sevenSignsContribute(Player player)
	{
		final long blue = player.getInventory().getInventoryItemCount(SevenSigns.SEAL_STONE_BLUE_ID, -1);
		final long green = player.getInventory().getInventoryItemCount(SevenSigns.SEAL_STONE_GREEN_ID, -1);
		final long red = player.getInventory().getInventoryItemCount(SevenSigns.SEAL_STONE_RED_ID, -1);
		if ((blue + green + red) <= 0)
		{
			return 0;
		}
		final long score = SevenSigns.getInstance().addPlayerStoneContrib(player.getObjectId(), blue, green, red);
		if (score < 0)
		{
			return -1;
		}
		if (blue > 0)
		{
			player.destroyItemByItemId(ItemProcessType.FEE, SevenSigns.SEAL_STONE_BLUE_ID, blue, player, false);
		}
		if (green > 0)
		{
			player.destroyItemByItemId(ItemProcessType.FEE, SevenSigns.SEAL_STONE_GREEN_ID, green, player, false);
		}
		if (red > 0)
		{
			player.destroyItemByItemId(ItemProcessType.FEE, SevenSigns.SEAL_STONE_RED_ID, red, player, false);
		}
		return score;
	}
}
