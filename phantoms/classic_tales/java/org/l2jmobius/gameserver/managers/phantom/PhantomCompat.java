package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import org.l2jmobius.gameserver.config.GeneralConfig;
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
import org.l2jmobius.gameserver.managers.DatabaseSpawnManager;
import org.l2jmobius.gameserver.managers.InstanceManager;
import org.l2jmobius.gameserver.mechanics.events.Containers;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureSkillUse;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.npc.OnAttackableKill;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;
import org.l2jmobius.gameserver.mechanics.olympiad.CompetitionType;
import org.l2jmobius.gameserver.mechanics.olympiad.OlympiadManager;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.CommonSkill;
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
 * в phantoms/&lt;id&gt;/java — сборка кладёт в исходники нужную. Здесь — Classic Tales Untold: адена и количество предметов {@code long},
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

	/** «Приглашён не тот» (сообщение 152). */
	public static void sendWrongInviteTarget(Player requestor)
	{
		requestor.sendPacket(SystemMessageId.YOU_HAVE_INVITED_THE_WRONG_TARGET);
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
}
