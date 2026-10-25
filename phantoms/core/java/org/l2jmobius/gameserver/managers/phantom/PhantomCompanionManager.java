package org.l2jmobius.gameserver.managers.phantom;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.groups.Party;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.zone.ZoneId;
import org.l2jmobius.gameserver.geoengine.GeoEngine;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomChat.Event;
import org.l2jmobius.gameserver.managers.phantom.PhantomOrders.Order;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: напарники — фантомы в пати с живым игроком (этап 2: пати, follow, assist, защита, догоняющий телепорт).<br>
 * Владелец зовёт напарника через .buddy — тот сразу вступает в пати (или по /invite: приглашение принимает сервер, {@code RequestJoinParty}). В пати напарник держится сзади-сбоку от владельца, бьёт цель владельца, когда тот в бою, защищает себя и пати,
 * а отставшего или застрявшего телепортирует к владельцу. Владелец вышел из игры — через {@link PhantomPartyConfig#PHANTOM_OWNER_LOGOUT_GRACE} секунд напарник уходит из мира.<br>
 * Бой — родным AI игрока (намерения ATTACK и MOVE_TO), без AutoPlay: он для живых игроков выключен ({@code EnableAutoPlay = False}).<br>
 * Охотник в пати (патч 0027) бьёт и своими скиллами из AutoUse, маг — только ими; AutoUse в пати работает ради банок. Подбирает вещи на земле рядом.
 */
public class PhantomCompanionManager
{
	private static final Logger LOGGER = Logger.getLogger(PhantomCompanionManager.class.getName());

	private static final long TICK_INTERVAL = 1000;
	/** Напарник держится сзади-сбоку от владельца на этом расстоянии. */
	private static final int SLOT_DISTANCE = 90;
	/** Ближе этого к своему месту — стоит. */
	private static final int SETTLED_RANGE = 120;
	/** Новый путь — только если место уехало дальше этого (владелец убежал), чтобы не дёргаться каждый тик. */
	private static final int REPATH_DELTA = 100;
	/** Столько тиков подряд почти не сдвинулся, хотя должен бежать, — застрял: телепорт на место. */
	private static final int STUCK_TICKS = 4;
	/** В режиме «стой» дальше этого от своей точки (догонял обидчика) — возвращается. */
	private static final int STAY_LEASH = 250;
	/** Противник дальше этого от владельца — бросить и вернуться к владельцу. */
	private static final int CHASE_LEASH = 1800;
	/** Отпустили словами («пока») — уходит через столько миллисекунд, успев попрощаться. */
	private static final long LEAVE_DELAY = 8000;
	/** У владельца в бою меньше этого HP (%) — хилер говорит «держись». */
	private static final int OWNER_LOW_HP = 30;
	/** Охотник в пати подбирает, что лежит ближе этого (адену, брошенную игроком, и вещи на земле). */
	private static final int PICKUP_RANGE = 300;
	// «Кач тут» (патч 0028). Опыт пати делится в AltPartyRange (1500) от моба — держаться ближе.
	/** Владелец стоит или сидит («сижу, качай»): бить свободных мобов в этом радиусе вокруг него — почти на всю дистанцию опыта (патч 0029, было 900). */
	private static final int HUNT_RADIUS = 1300;
	/** Владелец бежит: только тех, кто у него под ногами. */
	private static final int HUNT_RUN_RADIUS = 450;
	/** Дальше этого от владельца — бросить цель и догнать; владелец бежит — короче. */
	private static final int HUNT_LEASH = 1450;
	private static final int HUNT_RUN_LEASH = 700;
	/** Мобы сильнее владельца больше чем на столько уровней — не трогать самим (на них — только по его цели). */
	private static final int HUNT_LEVEL_UP = 6;
	// «Паровозик»: владелец идёт следом с задержкой Follow — пороги с запасом (патч 0030; было: ждать с 900, цель до 1400, телепорт с 2000).
	/** Владелец отстал дальше этого — ждать: новых мобов не брать. */
	private static final int LEAD_WAIT = 1600;
	/** Ждёт, пока владелец не подойдёт ближе этого, — потом снова качается. */
	private static final int LEAD_RESUME = 1000;
	/** Владелец дальше этого — идти к нему. */
	private static final int LEAD_RETURN = 2500;
	/** Владелец дальше этого — телепорт к нему. */
	private static final int LEAD_TELEPORT = 4000;
	/** Цель дальше этого от владельца — бросить и взять ближе (владелец идёт следом — с запасом). */
	private static final int LEAD_TARGET_RANGE = 3000;
	/**
	 * Свободных мобов искать в этом радиусе вокруг охотника (патч 0031, решение автора: «паровоз ищет мобов в большом радиусе, сижу-качай — вокруг игрока»).
	 */
	private static final int LEAD_RADIUS = 2500;
	/** Мобы сильнее охотника больше чем на столько уровней — самому не брать. */
	private static final int LEAD_LEVEL_UP = 5;

	public enum Mode
	{
		/** Идёт за владельцем, бьёт его цель, защищает пати. */
		ASSIST,
		/** Идёт за владельцем, только защищается и защищает пати. */
		FOLLOW,
		/** Стоит на месте, защищает только себя. */
		STAY,
		/** «Кач тут» (патч 0028): сам бьёт и добивает мобов вокруг владельца, держась рядом с ним; владелец бежит — догоняет и бьёт только тех, кто рядом с ним. */
		HUNT,
		/** «Паровозик» (патч 0029): охотник качается сам, родным AutoPlay, как на своём споте; владелец идёт следом. */
		LEAD
	}

	private static class Companion
	{
		final String name;
		int ownerId;
		Mode mode = Mode.ASSIST;
		/** «Паровозик»: владелец отстал — ждёт его (до {@link #LEAD_RESUME}), новых мобов не берёт. */
		boolean leadWaiting;
		long ownerAwaySince;
		Location stayAt;
		Location lastDestination;
		int lastX;
		int lastY;
		int stuckTicks;
		// События для реплик (этап 4): что было в прошлый тик.
		int ownerLevel;
		boolean ownerDead;
		boolean selfDead;
		/** Владелец получил уровень — подтянуться до него, когда бой кончится. */
		int relevelTo;
		/** Охотник, пришедший по крику (или принявший инвайт на споте): уровень свой, отпустили — назад на кач, а не из мира. */
		boolean hunter;

		Companion(String name)
		{
			this.name = name;
		}
	}

	private final Map<String, Companion> _companions = new ConcurrentHashMap<>();
	private ScheduledFuture<?> _task;

	protected PhantomCompanionManager()
	{
	}

	/**
	 * Позвать напарника: появляется рядом с владельцем, подтянутый до его уровня (профа, скиллы, шмот), и ждёт приглашения в пати.
	 * @param owner кто зовёт
	 * @param template напарник
	 * @return текст ошибки для игрока или {@code null}
	 */
	public synchronized String summon(Player owner, PhantomTemplate template)
	{
		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			return "Напарники выключены (EnablePhantoms в Phantoms.ini).";
		}

		if (template.isHunter())
		{
			return template.getName() + " — не напарник, качается сам.";
		}

		if (owner.isDead() || owner.isInOlympiadMode() || owner.isJailed() || (owner.getInstanceId() != 0))
		{
			return "Сейчас позвать нельзя: ты мёртв, на олимпиаде, в тюрьме или в инстансе.";
		}

		final Companion existing = _companions.get(key(template.getName()));
		final Player other = existing == null ? null : World.getPlayer(existing.ownerId);
		final Player current = PhantomManager.getInstance().getPhantom(template);
		if ((other != null) && (other != owner) && other.isOnline() && (current != null) && current.isInParty() && current.getParty().containsPlayer(other))
		{
			return template.getName() + " сейчас в пати с " + other.getName() + ".";
		}

		final Companion companion = existing != null ? existing : new Companion(template.getName());
		companion.ownerId = owner.getObjectId();
		companion.mode = Mode.ASSIST;
		companion.ownerAwaySince = 0;
		companion.stayAt = null;
		companion.lastDestination = null;
		companion.stuckTicks = 0;
		companion.ownerLevel = 0;
		_companions.put(key(template.getName()), companion);

		final Player phantom = PhantomManager.getInstance().spawn(template, slot(owner, slotIndex(companion)), owner.getLevel());
		if (phantom == null)
		{
			_companions.remove(key(template.getName()));
			return "Не вышло позвать " + template.getName() + ", подробности — в логе сервера.";
		}

		if (phantom.isInParty() && !phantom.getParty().containsPlayer(owner))
		{
			phantom.leaveParty();
		}

		startTicking();
		if (phantom.isInParty())
		{
			partySay(phantom, "тут, иду за тобой");
		}
		else if (!owner.isInParty() || (owner.getParty().isLeader(owner) && (owner.getParty().getMemberCount() < 9)))
		{
			// Одним кликом: позвал — сразу в пати.
			onInvited(owner, phantom, null);
		}
		else
		{
			whisper(phantom, owner, "я тут, кидай пати: /invite " + phantom.getName());
		}
		LOGGER.info(getClass().getSimpleName() + ": " + owner.getName() + " summoned " + template.getName() + ".");
		return null;
	}

	/**
	 * Отпустить напарника: выходит из пати и уходит из мира (персонаж остаётся в базе).
	 * @param owner кто отпускает
	 * @param template напарник
	 * @return текст ошибки для игрока или {@code null}
	 */
	public String dismiss(Player owner, PhantomTemplate template)
	{
		return dismiss(owner, template, false);
	}

	/**
	 * @param owner кто отпускает
	 * @param template напарник
	 * @param quiet без заготовки «ушёл» в чат пати (попрощался сам, через мозги)
	 * @return текст ошибки для игрока или {@code null}
	 */
	public synchronized String dismiss(Player owner, PhantomTemplate template, boolean quiet)
	{
		final Companion companion = _companions.get(key(template.getName()));
		final Player other = companion == null ? null : World.getPlayer(companion.ownerId);
		if ((other != null) && (other != owner) && other.isOnline())
		{
			return template.getName() + " ходит с " + other.getName() + ", отпустить его может только он.";
		}

		if ((companion != null) && companion.hunter)
		{
			release(template, quiet);
			LOGGER.info(getClass().getSimpleName() + ": " + owner.getName() + " released the hunter " + template.getName() + ".");
			return null;
		}

		_companions.remove(key(template.getName()));
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if (phantom == null)
		{
			return template.getName() + " и так не в мире.";
		}

		if (phantom.isInParty())
		{
			if (!quiet)
			{
				partySay(phantom, "ушёл, зови если что");
			}
			phantom.leaveParty();
		}
		PhantomManager.getInstance().despawn(template);
		LOGGER.info(getClass().getSimpleName() + ": " + owner.getName() + " dismissed " + template.getName() + ".");
		return null;
	}

	/**
	 * Охотник уходит из пати и из напарников: дальше его ведёт {@link PhantomHunterManager} (назад на кач). Из мира не уходит.
	 * @param template охотник
	 * @param quiet без «ушёл» в чат пати
	 */
	public synchronized void release(PhantomTemplate template, boolean quiet)
	{
		_companions.remove(key(template.getName()));
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if ((phantom != null) && phantom.isInParty())
		{
			if (!quiet)
			{
				partySay(phantom, "ладно, я дальше качаться, удачи");
			}
			phantom.leaveParty();
		}
	}

	/**
	 * Рейд на эпика или охота на Танатоса (разделы 4.43, 4.45): лидер лёг — ведёт другой. Напарники старого лидера — теперь его, сам новый лидер больше
	 * не напарник (ведёт рейд сам), старый — его напарник (лежит, ждёт реса, как участник).
	 */
	synchronized void handOver(Player oldOwner, Player newOwner)
	{
		_companions.remove(key(newOwner.getName()));
		for (Companion companion : _companions.values())
		{
			if (companion.ownerId == oldOwner.getObjectId())
			{
				companion.ownerId = newOwner.getObjectId();
				companion.ownerAwaySince = 0;
				companion.stayAt = null;
			}
		}
		final PhantomTemplate old = PhantomData.getInstance().getTemplate(oldOwner.getName());
		if ((old != null) && old.isHunter())
		{
			final Companion companion = new Companion(old.getName());
			companion.ownerId = newOwner.getObjectId();
			companion.mode = Mode.ASSIST;
			companion.hunter = true;
			companion.selfDead = oldOwner.isDead();
			_companions.put(key(old.getName()), companion);
			PhantomHunterManager.getInstance().joinedParty(old);
		}
	}

	/**
	 * @param template фантом
	 * @return ходит ли он сейчас напарником (для охотника — в пати по крику)
	 */
	public boolean isCompanion(PhantomTemplate template)
	{
		return _companions.containsKey(key(template.getName()));
	}

	/**
	 * Для карты мира в лаунчере ({@code L2HubHunters.json}, {@link PhantomHunterManager}): напарники из .buddy в мире — JSON-массив
	 * {@code [{"name","owner","x","y","z"}]}. Охотников в пати по крику нет: их координаты — в списке охотников. Имена — латиница и цифры, экранировать нечего.
	 */
	public void mapStatus(StringBuilder sb)
	{
		sb.append('[');
		boolean first = true;
		for (Companion companion : _companions.values())
		{
			final PhantomTemplate template = companion.hunter ? null : PhantomData.getInstance().getTemplate(companion.name);
			final Player phantom = template == null ? null : PhantomManager.getInstance().getPhantom(template);
			if (phantom == null)
			{
				continue;
			}
			final Player owner = World.getPlayer(companion.ownerId);
			sb.append(first ? "" : ",").append("{\"name\":\"").append(phantom.getName()).append("\",\"owner\":\"").append(owner == null ? "" : owner.getName());
			sb.append("\",\"x\":").append(phantom.getX()).append(",\"y\":").append(phantom.getY()).append(",\"z\":").append(phantom.getZ()).append('}');
			first = false;
		}
		sb.append(']');
	}

	/**
	 * Взять в пати позванного напарника (кнопка «В пати» в .buddy) — то же, что /invite.
	 * @param owner владелец
	 * @param template напарник
	 * @return текст ошибки для игрока или {@code null}
	 */
	public String invite(Player owner, PhantomTemplate template)
	{
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if ((phantom == null) || !isOwner(owner, template))
		{
			return template.getName() + " не с тобой — сначала позови его.";
		}

		if (isPartiedWith(owner, phantom))
		{
			return template.getName() + " уже в пати с тобой.";
		}

		onInvited(owner, phantom, null);
		return null;
	}

	/**
	 * @param owner владелец
	 * @param template напарник
	 * @param mode новый режим
	 * @return текст ошибки для игрока или {@code null}
	 */
	public String setMode(Player owner, PhantomTemplate template, Mode mode)
	{
		final Companion companion = _companions.get(key(template.getName()));
		if ((companion == null) || (companion.ownerId != owner.getObjectId()))
		{
			return template.getName() + " не с тобой — сначала позови его.";
		}

		final Mode previous = companion.mode;
		companion.mode = mode;
		companion.leadWaiting = false;
		companion.lastDestination = null;
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		// Из «паровозика» — AutoPlay выключить: дальше снова ведёт этот менеджер.
		if ((previous == Mode.LEAD) && (mode != Mode.LEAD) && (phantom != null))
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
		}
		companion.stayAt = (mode == Mode.STAY) && (phantom != null) ? new Location(phantom.getX(), phantom.getY(), phantom.getZ()) : null;
		if ((phantom != null) && (mode != Mode.ASSIST) && (mode != Mode.HUNT) && (mode != Mode.LEAD) && (phantom.getAI().getIntention() == Intention.ATTACK))
		{
			calmDown(phantom);
		}
		return null;
	}

	/**
	 * Приглашение в пати фантому (вызывается из {@code RequestJoinParty}): у него нет клиента и окна «вступить в пати?», отвечает сервер. Пригласивший становится владельцем, уровень напарника подтягивается до его уровня.
	 * @param requestor кто приглашает
	 * @param phantom фантом
	 * @param type распределение лута из пакета приглашения
	 */
	public synchronized void onInvited(Player requestor, Player phantom, PartyDistributionType type)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if (template == null)
		{
			PhantomCompat.sendWrongInviteTarget(requestor);
			return;
		}

		// Охотник идёт, если звал криком именно этот игрок или если он свободен, уровень близкий и не обидчик; уровень остаётся свой.
		final boolean hunter = template.isHunter();
		if (hunter && !PhantomHunterManager.getInstance().acceptsInvite(template, requestor))
		{
			whisper(phantom, requestor, "сорри, я соло качаюсь)");
			return;
		}

		if (phantom.isInParty())
		{
			PhantomCompat.sendMemberOfAnotherParty(requestor, phantom);
			return;
		}

		final Companion existing = _companions.get(key(template.getName()));
		final Player other = existing == null ? null : World.getPlayer(existing.ownerId);
		if ((other != null) && (other != requestor) && other.isOnline())
		{
			requestor.sendMessage(phantom.getName() + " ходит с " + other.getName() + ".");
			return;
		}

		if (requestor.isInOlympiadMode())
		{
			PhantomCompat.sendOlympiadNoInvite(requestor);
			return;
		}

		if (requestor.isInParty())
		{
			if (!requestor.getParty().isLeader(requestor))
			{
				requestor.sendPacket(SystemMessageId.ONLY_THE_LEADER_CAN_GIVE_OUT_INVITATIONS);
				return;
			}

			if (requestor.getParty().getMemberCount() >= 9)
			{
				requestor.sendPacket(SystemMessageId.THE_PARTY_IS_FULL);
				return;
			}
		}

		if (phantom.isDead())
		{
			requestor.sendMessage(phantom.getName() + " лежит без сознания: подними его или позови заново через .buddy.");
			return;
		}

		// Уровень подтягивается до уровня пригласившего (профа, скиллы и шмот собираются заново на месте).
		Player member = phantom;
		if (!hunter && (phantom.getLevel() != requestor.getLevel()))
		{
			member = PhantomManager.getInstance().spawn(template, new Location(phantom.getX(), phantom.getY(), phantom.getZ(), phantom.getHeading()), requestor.getLevel());
			if (member == null)
			{
				requestor.sendMessage("Не вышло пригласить " + template.getName() + ", подробности — в логе сервера.");
				return;
			}
		}

		PhantomCompat.acceptPartyJoin(requestor, type != null ? type : PartyDistributionType.FINDERS_KEEPERS);
		member.joinParty(requestor.getParty());
		register(requestor, member, template, existing, "тебя только что приняли в пати к " + requestor.getName() + ": поздоровайся и коротко скажи, что будешь делать");
	}

	/**
	 * Охотник позвал живого игрока в пати (стратег, {@code [[PARTY имя]]}) окном «вступить в пати?», и тот согласился: пати по правилам Mobius — у охотника.
	 * Лидерство — игроку, охотник — его напарник, как если бы игрок позвал его сам ({@link #onInvited}).
	 * @param owner живой игрок
	 * @param phantom охотник
	 * @return стал напарником
	 */
	public synchronized boolean adopt(Player owner, Player phantom)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		final Party party = phantom.getParty();
		if ((template == null) || (party == null) || (owner.getParty() != party))
		{
			return false;
		}

		if (party.isLeader(phantom))
		{
			party.changePartyLeader(owner.getName());
		}
		register(owner, phantom, template, _companions.get(key(template.getName())), "ты позвал " + owner.getName() + " в пати, он согласился, и ты отдал ему лидерство: скажи коротко, что будешь делать");
		return true;
	}

	/**
	 * Фантом вступил в пати живого игрока: он — напарник владельца, охотник выходит из своих дел ({@link PhantomHunterManager#joinedParty}); приветствие — от мозгов.
	 */
	private void register(Player owner, Player member, PhantomTemplate template, Companion existing, String prompt)
	{
		final boolean hunter = template.isHunter();
		final Companion companion = existing != null ? existing : new Companion(template.getName());
		companion.ownerId = owner.getObjectId();
		companion.mode = Mode.ASSIST;
		companion.ownerAwaySince = 0;
		companion.stayAt = null;
		companion.lastDestination = null;
		companion.ownerLevel = 0;
		companion.hunter = hunter;
		_companions.put(key(template.getName()), companion);
		if (hunter)
		{
			PhantomHunterManager.getInstance().joinedParty(template);
		}
		startTicking();
		final String greeting = template.getRole() == PhantomTemplate.Role.HEALER ? "в пати, хилю" : template.getRole() == PhantomTemplate.Role.BUFFER ? "в пати, ща бафну" : "в пати, иду за тобой и бью твою цель";
		PhantomChat.getInstance().event(member, owner, Event.JOINED, prompt, greeting);
		LOGGER.info(getClass().getSimpleName() + ": " + template.getName() + " joined the party of " + owner.getName() + ".");
	}

	/**
	 * @param viewer кто смотрит (.buddy)
	 * @param template напарник
	 * @return состояние напарника словами
	 */
	public String describe(Player viewer, PhantomTemplate template)
	{
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if (phantom == null)
		{
			return "не в мире";
		}

		if (phantom.isDead())
		{
			return "лежит без сознания — позови заново";
		}

		final Companion companion = _companions.get(key(template.getName()));
		final Player owner = companion == null ? null : World.getPlayer(companion.ownerId);
		if ((owner != null) && (owner != viewer) && owner.isOnline())
		{
			return "ходит с " + owner.getName();
		}

		if ((owner == viewer) && (companion != null))
		{
			if (isPartiedWith(viewer, phantom))
			{
				return "в пати с тобой: " + (PhantomSupport.isSupport(template) ? supportLabel(template.getRole(), companion.mode) : modeLabel(companion.mode));
			}
			return "рядом, ждёт пати: «В пати» или /invite " + template.getName();
		}
		return "в мире, свободен";
	}

	/**
	 * @param viewer игрок
	 * @param template напарник
	 * @return {@code true}, если напарник в пати с этим игроком
	 */
	public boolean isPartiedWith(Player viewer, PhantomTemplate template)
	{
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		return (phantom != null) && isPartiedWith(viewer, phantom);
	}

	private static boolean isPartiedWith(Player viewer, Player phantom)
	{
		return phantom.isInParty() && phantom.getParty().containsPlayer(viewer);
	}

	/**
	 * @param player игрок
	 * @param template напарник
	 * @return {@code true}, если игрок позвал этого напарника: только его приказы напарник выполняет
	 */
	public boolean isOwner(Player player, PhantomTemplate template)
	{
		final Companion companion = _companions.get(key(template.getName()));
		return (companion != null) && (companion.ownerId == player.getObjectId());
	}

	/**
	 * Приказ словами (из {@link PhantomChat}). Хил, рес, баф и отдых здесь только отмечаются — выполняет их тик.
	 * @param owner владелец
	 * @param template напарник
	 * @param order приказ
	 * @return что сделано — для мозгов: «сделано: …» или «нельзя: …»
	 */
	public String order(Player owner, PhantomTemplate template, Order order)
	{
		final Companion companion = _companions.get(key(template.getName()));
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		if ((companion == null) || (companion.ownerId != owner.getObjectId()) || (phantom == null))
		{
			return "нельзя: ты сейчас не ходишь с " + owner.getName();
		}

		if (order == Order.LEAVE)
		{
			// Прощается сам (ответ мозгов или заготовка), потом уходит.
			ThreadPool.schedule(() -> dismiss(owner, template, true), LEAVE_DELAY);
			return "сделано: он тебя отпустил, ты прощаешься и через пару секунд уходишь из пати";
		}

		if (!isPartiedWith(owner, phantom))
		{
			return "нельзя: ты ещё не в пати с ним — пусть пригласит: /invite " + template.getName();
		}

		final boolean support = PhantomSupport.isSupport(template);
		final String job = template.getRole() == PhantomTemplate.Role.HEALER ? "лечишь" : "бафаешь";
		switch (order)
		{
			case FOLLOW:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, support ? Mode.ASSIST : Mode.FOLLOW);
				return support ? "сделано: идёшь за ним следом и " + job : "сделано: идёшь за ним следом, только защищаешься";
			}
			case STAY:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, Mode.STAY);
				return support ? "сделано: стоишь на месте и " + job + " тех, до кого достаёшь" : "сделано: стоишь на месте";
			}
			case ASSIST:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, Mode.ASSIST);
				return support ? "нельзя: ты саппорт и в драку не лезешь — идёшь следом и " + job : "сделано: бьёшь его цель";
			}
			case HUNT:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, support ? Mode.ASSIST : Mode.HUNT);
				return support ? "нельзя: ты саппорт и в драку не лезешь — идёшь следом и " + job : "сделано: качаешься рядом с ним — сам бьёшь и добиваешь всех мобов вокруг, держишься недалеко";
			}
			case LEAD:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, support ? Mode.ASSIST : Mode.LEAD);
				return support ? "нельзя: ты саппорт и в драку не лезешь — идёшь следом и " + job : "сделано: ведёшь кач сам — качаешься как на своём споте, он идёт за тобой; отстанет — подождёшь";
			}
			default:
			{
				if (!support)
				{
					return "нельзя: ты так не умеешь";
				}

				switch (order)
				{
					case BUFF:
					{
						return PhantomSupport.orderBuff(phantom);
					}
					case HEAL:
					{
						return PhantomSupport.orderHeal(phantom, owner);
					}
					case RES:
					{
						return PhantomSupport.orderRes(phantom, owner);
					}
					case SIT:
					{
						return PhantomSupport.orderSit(phantom, true);
					}
					default:
					{
						return PhantomSupport.orderSit(phantom, false);
					}
				}
			}
		}
	}

	/**
	 * @param template напарник
	 * @return чем напарник сейчас занят — для мозгов, от его лица
	 */
	public String stateForBrain(PhantomTemplate template)
	{
		if (template.isHunter() && !isCompanion(template))
		{
			return PhantomHunterManager.getInstance().stateForBrain(template);
		}

		final Companion companion = _companions.get(key(template.getName()));
		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		final Player owner = companion == null ? null : World.getPlayer(companion.ownerId);
		if ((phantom == null) || (owner == null) || !owner.isOnline())
		{
			return "свободен, ни с кем не ходишь";
		}

		if (!isPartiedWith(owner, phantom))
		{
			return "тебя позвал " + owner.getName() + ", ждёшь, когда он пригласит в пати";
		}

		final String where = companion.mode == Mode.STAY ? "по его приказу стоишь на месте" : companion.mode == Mode.LEAD ? "ты впереди, он идёт за тобой" : "идёшь за ним следом";
		final String doing;
		if (template.getRole() == PhantomTemplate.Role.HEALER)
		{
			doing = "лечишь и поднимаешь пати, в драку не лезешь";
		}
		else if (template.getRole() == PhantomTemplate.Role.BUFFER)
		{
			doing = "держишь бафы на пати, в драку не лезешь";
		}
		else
		{
			doing = companion.mode == Mode.LEAD ? "ведёшь кач: сам выбираешь мобов и бьёшь, нет рядом — ведёшь его к споту" : companion.mode == Mode.HUNT ? "качаешься рядом с ним: сам бьёшь всех мобов вокруг"
				: companion.mode == Mode.ASSIST ? "бьёшь его цель" : "только защищаешься";
		}
		return "в пати с " + owner.getName() + ": " + where + ", " + doing + (PhantomSupport.isSitOrdered(phantom) ? ", по его просьбе сидишь отдыхаешь" : "");
	}

	private static String supportLabel(PhantomTemplate.Role role, Mode mode)
	{
		final String job = role == PhantomTemplate.Role.HEALER ? "лечит и поднимает" : "держит бафы на пати";
		return (mode == Mode.STAY ? "стоит на месте, " : "идёт за тобой, ") + job;
	}

	private static String modeLabel(Mode mode)
	{
		switch (mode)
		{
			case FOLLOW:
			{
				return "идёт за тобой, только защищается";
			}
			case STAY:
			{
				return "стоит на месте";
			}
			default:
			{
				return "идёт за тобой и бьёт твою цель";
			}
		}
	}

	// ---------- Поведение ----------

	private synchronized void startTicking()
	{
		if ((_task == null) || _task.isDone())
		{
			_task = ThreadPool.scheduleAtFixedRate(this::tick, TICK_INTERVAL, TICK_INTERVAL);
		}
	}

	private void tick()
	{
		final long now = System.currentTimeMillis();
		for (Companion companion : _companions.values())
		{
			try
			{
				think(companion, now);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": " + companion.name + " tick failed.", e);
			}
		}
	}

	private void think(Companion companion, long now)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(companion.name);
		final Player phantom = template == null ? null : PhantomManager.getInstance().getPhantom(template);
		if (phantom == null)
		{
			// Убран из мира (//phantom despawn, .buddy «Отпустить»).
			_companions.remove(key(companion.name));
			return;
		}

		final Player owner = World.getPlayer(companion.ownerId);
		if ((owner == null) || !owner.isOnline())
		{
			if (companion.ownerAwaySince == 0)
			{
				companion.ownerAwaySince = now;
			}
			else if ((now - companion.ownerAwaySince) > (PhantomPartyConfig.PHANTOM_OWNER_LOGOUT_GRACE * 1000L))
			{
				if (companion.hunter)
				{
					release(template, true);
					LOGGER.info(getClass().getSimpleName() + ": " + companion.name + " goes back to hunting, the party leader is offline.");
					return;
				}

				_companions.remove(key(companion.name));
				if (phantom.isInParty())
				{
					phantom.leaveParty();
				}
				PhantomManager.getInstance().despawn(template);
				LOGGER.info(getClass().getSimpleName() + ": " + companion.name + " left the world, the owner is offline.");
			}
			return;
		}
		companion.ownerAwaySince = 0;
		observe(companion, template, phantom, owner);

		// Мёртвому фантому рес предлагают окном «воскресить?», ответить некому — соглашаемся за него.
		if (phantom.isDead())
		{
			if (phantom.isReviveRequested())
			{
				phantom.reviveAnswer(1);
			}
			return;
		}

		if (phantom.isTeleporting() || owner.isTeleporting())
		{
			return;
		}

		// Не в пати с владельцем (выгнал, распустил пати или ещё не пригласил) — ждёт на месте.
		final Party party = phantom.getParty();
		if ((party == null) || !party.containsPlayer(owner))
		{
			// Охотника выгнали или пати распалась — назад на кач.
			if (companion.hunter)
			{
				release(template, true);
				return;
			}

			if (party != null)
			{
				phantom.leaveParty();
			}
			return;
		}

		// Владелец в инстансе — ждём снаружи: на этапе 2 фантомы в инстансы не ходят.
		if (owner.getInstanceId() != phantom.getInstanceId())
		{
			return;
		}

		// Владелец получил уровень — подтянуться до него, но не посреди драки: шмот и скиллы собираются заново.
		if (!companion.hunter && (companion.relevelTo > phantom.getLevel()))
		{
			if (!owner.isInCombat() && !phantom.isCastingNow() && !phantom.isInCombat())
			{
				final int level = companion.relevelTo;
				companion.relevelTo = 0;
				if (PhantomManager.getInstance().relevel(template, level))
				{
					LOGGER.info(getClass().getSimpleName() + ": " + companion.name + " leveled up to " + phantom.getLevel() + " after " + owner.getName() + ".");
				}
				return;
			}
		}
		else
		{
			companion.relevelTo = 0;
		}

		final int slotIndex = slotIndex(companion);
		// В «паровозике» впереди идёт охотник, а владелец догоняет с задержкой Follow — телепорт к владельцу только совсем издалека.
		final int catchUp = companion.mode == Mode.LEAD ? LEAD_TELEPORT : PhantomPartyConfig.PHANTOM_CATCH_UP_RANGE;
		if ((companion.mode != Mode.STAY) && (phantom.calculateDistance2D(owner) > catchUp))
		{
			teleport(companion, phantom, slot(owner, slotIndex));
			return;
		}

		if (PhantomSupport.isSupport(template))
		{
			// Саппорты не дерутся (этап 3): лечат, бафают, отдыхают по MP, в остальное время идут следом.
			if (phantom.getAI().getIntention() == Intention.ATTACK)
			{
				calmDown(phantom);
			}

			if (PhantomSupport.act(phantom, owner, template.getRole(), now))
			{
				return;
			}
		}
		else
		{
			// «Паровозик»: качается сам родным AutoPlay, владелец идёт следом.
			if (companion.mode == Mode.LEAD)
			{
				lead(companion, phantom, owner);
				return;
			}

			if (phantom.isCastingNow())
			{
				return;
			}

			final Creature enemy = chooseEnemy(companion, phantom, owner);
			if (enemy != null)
			{
				attack(phantom, enemy);
				return;
			}

			if (phantom.getAI().getIntention() == Intention.ATTACK)
			{
				calmDown(phantom);
			}
		}

		// Охотник подбирает, что лежит рядом: адену и вещи, брошенные игроком (лут с мобов по автолуту и так делит пати).
		if (companion.hunter && pickUp(phantom))
		{
			return;
		}

		if (companion.mode == Mode.STAY)
		{
			hold(companion, phantom);
		}
		else
		{
			follow(companion, phantom, owner, slotIndex);
		}
	}

	/**
	 * События для реплик (этап 4): новый уровень и смерть владельца, своя смерть и рес, мало HP у владельца (говорит хилер).
	 */
	private static void observe(Companion companion, PhantomTemplate template, Player phantom, Player owner)
	{
		if (!isPartiedWith(owner, phantom))
		{
			companion.ownerLevel = owner.getLevel();
			companion.ownerDead = owner.isDead();
			companion.selfDead = phantom.isDead();
			return;
		}

		final PhantomChat chat = PhantomChat.getInstance();
		final boolean healer = template.getRole() == PhantomTemplate.Role.HEALER;
		if ((companion.ownerLevel > 0) && (owner.getLevel() > companion.ownerLevel))
		{
			companion.relevelTo = owner.getLevel();
			chat.event(phantom, owner, Event.OWNER_LEVEL_UP, owner.getName() + " только что получил " + owner.getLevel() + " уровень", "грац с " + owner.getLevel() + "!");
		}
		companion.ownerLevel = owner.getLevel();

		// О смерти владельца говорит хилер (он и поднимет), без хилера в пати — любой.
		if (owner.isDead() && !companion.ownerDead && (healer || !partyHasHealer(phantom)))
		{
			chat.event(phantom, owner, Event.OWNER_DIED, owner.getName() + " погиб" + (healer ? ", ты сейчас его поднимешь" : ""), healer ? "ща подниму" : null);
		}
		companion.ownerDead = owner.isDead();

		if (phantom.isDead() && !companion.selfDead)
		{
			chat.event(phantom, owner, Event.DIED, "тебя убили, лежишь и ждёшь реса", null);
		}
		else if (!phantom.isDead() && companion.selfDead)
		{
			chat.event(phantom, owner, Event.REVIVED, "тебя подняли, ты снова на ногах", "спс за рес");
		}
		companion.selfDead = phantom.isDead();

		if (healer && !owner.isDead() && owner.isInCombat() && ((owner.getCurrentHp() * 100) < (owner.getMaxHp() * OWNER_LOW_HP)))
		{
			chat.event(phantom, owner, Event.OWNER_LOW_HP, "у " + owner.getName() + " в бою осталось " + (int) ((owner.getCurrentHp() * 100) / Math.max(1, owner.getMaxHp())) + "% HP, ты его лечишь", "держись!");
		}
	}

	private static boolean partyHasHealer(Player phantom)
	{
		for (Player member : phantom.getParty().getMembers())
		{
			final PhantomTemplate template = PhantomManager.getInstance().isPhantom(member) ? PhantomData.getInstance().getTemplate(member.getName()) : null;
			if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Кого бить: цель владельца (режимы ASSIST и HUNT, владелец в бою), затем текущего противника, затем ближайшего моба, который бьёт нас;
	 * в режиме HUNT — ещё и свободного моба вокруг владельца. Владелец убежал дальше поводка «кач тут» — никого: догнать.
	 */
	private static Creature chooseEnemy(Companion companion, Player phantom, Player owner)
	{
		// Рейд (разделы 4.43, 4.45): своё дело (личинка Королевы) и охота на Танатоса — цель-игрок, которую ниже не берут.
		final Creature raidTarget = PhantomRaid.getInstance().companionTarget(phantom);
		if (raidTarget != null)
		{
			return raidTarget;
		}

		final boolean hunt = companion.mode == Mode.HUNT;
		final int leash = hunt ? (owner.isMoving() ? HUNT_RUN_LEASH : HUNT_LEASH) : CHASE_LEASH;
		if (hunt && (phantom.calculateDistance2D(owner) > leash))
		{
			return null;
		}

		if (((companion.mode == Mode.ASSIST) || hunt) && !owner.isDead())
		{
			final WorldObject target = owner.getTarget();
			if ((target instanceof Monster) && isEnemy(phantom, (Monster) target) && (owner.calculateDistance2D(target) <= PhantomPartyConfig.PHANTOM_ASSIST_RANGE) && (owner.isInCombat() || (PhantomHunterOutfit.victimOf((Monster) target) == owner)))
			{
				return (Monster) target;
			}
		}

		// Своего противника держит; в «кач тут» — добивает, даже если моб бьёт не его.
		final WorldObject current = phantom.getTarget();
		if ((current instanceof Monster) && isEnemy(phantom, (Monster) current) && (hunt ? !takenByOthers((Monster) current, phantom) : attacksUs((Monster) current, companion, phantom, owner)) && (owner.calculateDistance2D(current) <= leash))
		{
			return (Monster) current;
		}

		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, PhantomPartyConfig.PHANTOM_DEFEND_RANGE))
		{
			if (!isEnemy(phantom, monster) || !attacksUs(monster, companion, phantom, owner))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(monster);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		if ((nearest != null) || !hunt)
		{
			return nearest;
		}

		// «Кач тут»: свободный моб вокруг владельца — стоит: HUNT_RADIUS, бежит: HUNT_RUN_RADIUS (только тех, кто у него под ногами).
		// Не РБ, не сильнее владельца больше чем на HUNT_LEVEL_UP, не чужой (бьёт не нашу пати).
		final int radius = owner.isMoving() ? HUNT_RUN_RADIUS : HUNT_RADIUS;
		for (Monster monster : World.getVisibleObjectsInRange(owner, Monster.class, radius))
		{
			if (!isEnemy(phantom, monster) || monster.isRaid() || (monster.getLevel() > (owner.getLevel() + HUNT_LEVEL_UP)) || takenByOthers(monster, phantom))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(monster);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * @return моб дерётся с кем-то не из нашей пати — чужой, не трогать
	 */
	private static boolean takenByOthers(Monster monster, Player phantom)
	{
		final WorldObject target = PhantomHunterOutfit.victimOf(monster);
		return (target instanceof Player) && (target != phantom) && (!phantom.isInParty() || !phantom.getParty().containsPlayer((Player) target));
	}

	private static boolean isEnemy(Player phantom, Monster monster)
	{
		return !monster.isAlikeDead() && !monster.isFakePlayer() && monster.isAutoAttackable(phantom) && !monster.isInsideZone(ZoneId.PEACE) && GeoEngine.getInstance().canSeeTarget(phantom, monster);
	}

	/**
	 * Моб бьёт нас: самого фантома — всегда, владельца и пати — кроме режима «стой». Кого бьёт — из ИИ моба ({@link PhantomHunterOutfit#victimOf}, патч 0034).
	 */
	private static boolean attacksUs(Monster monster, Companion companion, Player phantom, Player owner)
	{
		if (!monster.isInCombat() && (monster.getAI().getIntention() != Intention.ATTACK))
		{
			return false;
		}

		final WorldObject target = PhantomHunterOutfit.victimOf(monster);
		if (target == phantom)
		{
			return true;
		}

		if ((companion.mode == Mode.STAY) || !(target instanceof Player))
		{
			return false;
		}

		final Player victim = (Player) target;
		return (victim == owner) || (phantom.isInParty() && phantom.getParty().containsPlayer(victim));
	}

	private static void attack(Player phantom, Creature enemy)
	{
		if (phantom.isSitting())
		{
			phantom.standUp();
			return;
		}

		// Маг, которого бьют вплотную (удар сбивает каст, AltGameCancelByHit), или без маны отмахивается оружием от того, кто бьёт, пока не отобьётся (патч 0028).
		Creature target = enemy;
		final boolean caster = PhantomHunterOutfit.isCaster(phantom);
		final Monster attacker = caster ? PhantomHunterOutfit.meleeAttacker(phantom) : null;
		final boolean melee = caster && ((attacker != null) || PhantomHunterOutfit.outOfMana(phantom));
		if (attacker != null)
		{
			target = attacker;
		}

		if (phantom.getTarget() != target)
		{
			phantom.setTarget(target);
		}

		// Охотник бьёт своими скиллами, как на споте: AutoUse без AutoPlay атакующие скиллы не кастует, поэтому — отсюда.
		if (!melee && castSkill(phantom, target))
		{
			return;
		}

		// Маг оружием не машет, пока может кастовать: цель держит, ждёт отката.
		if (caster && !melee)
		{
			if (phantom.getAI().getIntention() == Intention.ATTACK)
			{
				phantom.abortAttack();
				phantom.getAI().setIntentionActive();
			}
			return;
		}

		// Повтор намерения раз в тик оживляет застрявшую атаку (так же делает AutoPlay Mobius).
		if ((phantom.getAI().getIntention() != Intention.ATTACK) || (!phantom.isAttackingNow() && !phantom.isMoving()))
		{
			phantom.setRunning();
			phantom.getAI().setIntentionAttack(target);
		}
	}

	/**
	 * «Паровозик»: охотник ведёт кач сам, своими целями (патч 0031: родной AutoPlay в пати брал только мобов, до которых путь по прямой, —
	 * на холмах и в толчее охотников не находил никого и стоял). Бьёт и добивает свободных мобов в {@link #LEAD_RADIUS} вокруг себя, защищается, подбирает;
	 * мобов рядом нет — ведёт к ближайшему споту по силам и бьёт всех по дороге. Бой — тот же {@link #attack}: скиллы, маг отмахивается под ударами.
	 * Владелец догоняет с задержкой Follow — пороги с запасом и с гистерезисом (патч 0030), иначе охотник метался бы туда-сюда:
	 * отстал дальше {@link #LEAD_WAIT} — ждёт (новых мобов не берёт, того, кто бьёт его, добивает) до {@link #LEAD_RESUME};
	 * дальше {@link #LEAD_RETURN} — идёт к нему; дальше {@link #LEAD_TELEPORT} — телепорт к нему (в {@link #think}).
	 */
	private static void lead(Companion companion, Player phantom, Player owner)
	{
		if (phantom.isAutoPlaying())
		{
			AutoPlayTaskManager.getInstance().stopAutoPlay(phantom);
		}
		if (phantom.isCastingNow())
		{
			return;
		}

		final double distance = phantom.calculateDistance2D(owner);
		if (companion.leadWaiting ? (distance > LEAD_RESUME) : (distance > LEAD_WAIT))
		{
			companion.leadWaiting = true;
			final Monster attacker = attackerOf(companion, phantom, owner);
			if (attacker != null)
			{
				attack(phantom, attacker);
				return;
			}
			if ((distance > LEAD_RETURN) && !phantom.isAttackingNow() && !phantom.isMoving())
			{
				phantom.setRunning();
				final Location at = new Location(owner.getX(), owner.getY(), owner.getZ());
				if (!PhantomHunterManager.runRoute(phantom, at))
				{
					PhantomHunterManager.runTo(phantom, at);
				}
			}
			return;
		}
		companion.leadWaiting = false;

		final Creature enemy = leadEnemy(companion, phantom, owner);
		if (enemy != null)
		{
			attack(phantom, enemy);
			return;
		}
		if (phantom.getAI().getIntention() == Intention.ATTACK)
		{
			calmDown(phantom);
		}
		if (pickUp(phantom))
		{
			return;
		}

		// Мобов рядом нет — к ближайшему споту по силам: там они есть, по дороге — бьёт встречных.
		final HuntSpot spot = nearestLeadSpot(phantom);
		if ((spot != null) && (phantom.calculateDistance2D(spot.entry) > (spot.radius / 2)) && !phantom.isMoving())
		{
			// К споту — по маршруту (раздел 4.30): игрок идёт следом, застрять у скалы на виду у него хуже, чем у охотника в одиночку.
			phantom.setRunning();
			if (!PhantomHunterManager.runRoute(phantom, spot.entry))
			{
				PhantomHunterManager.runTo(phantom, spot.entry);
			}
		}
	}

	/**
	 * Кого бить в «паровозике»: свою цель (жива, не чужая, не дальше {@link #LEAD_TARGET_RANGE} от владельца), того, кто бьёт нас или пати,
	 * иначе ближайшего свободного моба в {@link #LEAD_RADIUS} вокруг себя: не РБ, не сильнее охотника больше чем на {@link #LEAD_LEVEL_UP}, не далеко от владельца.
	 */
	private static Creature leadEnemy(Companion companion, Player phantom, Player owner)
	{
		final WorldObject current = phantom.getTarget();
		if ((current instanceof Monster) && isEnemy(phantom, (Monster) current) && !takenByOthers((Monster) current, phantom) && (owner.calculateDistance2D(current) <= LEAD_TARGET_RANGE))
		{
			return (Monster) current;
		}

		final Monster attacker = attackerOf(companion, phantom, owner);
		if (attacker != null)
		{
			return attacker;
		}

		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, LEAD_RADIUS))
		{
			if (!isEnemy(phantom, monster) || monster.isRaid() || (monster.getLevel() > (phantom.getLevel() + LEAD_LEVEL_UP)) || takenByOthers(monster, phantom) || (owner.calculateDistance2D(monster) > LEAD_TARGET_RANGE))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(monster);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * @return ближайший моб, который бьёт охотника или пати ({@link #attacksUs}), в {@code PhantomDefendRange}, или {@code null}
	 */
	private static Monster attackerOf(Companion companion, Player phantom, Player owner)
	{
		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, PhantomPartyConfig.PHANTOM_DEFEND_RANGE))
		{
			if (!isEnemy(phantom, monster) || !attacksUs(monster, companion, phantom, owner))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(monster);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Ближайший спот по силам охотника: не перерос (как в {@code PhantomHunterManager}: уровень не выше max + 3), мобы не сильнее {@link #LEAD_LEVEL_UP} уровней.
	 */
	private static HuntSpot nearestLeadSpot(Player phantom)
	{
		final int level = phantom.getLevel();
		HuntSpot nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((level > (spot.max + 3)) || (spot.level > (level + LEAD_LEVEL_UP)))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(spot.entry);
			if (distance < nearestDistance)
			{
				nearest = spot;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Атакующий скилл охотника из его AutoUse (сильные первыми), если он готов сейчас: хватает MP, не на откате, условия выполнены.
	 * До дистанции каста AI игрока подводит сам. У напарников из .buddy таких скиллов нет — они бьют оружием, как раньше.
	 * @return начал каст
	 */
	private static boolean castSkill(Player phantom, Creature enemy)
	{
		for (Integer skillId : phantom.getAutoUseSettings().getAutoSkills())
		{
			final Skill skill = phantom.getKnownSkill(skillId);
			if ((skill == null) || phantom.isSkillDisabled(skill) || (phantom.getCurrentMp() < (skill.getMpInitialConsume() + skill.getMpConsume())) || !skill.checkCondition(phantom, enemy, false))
			{
				continue;
			}

			phantom.setRunning();
			if (PhantomCompat.useMagic(phantom, skill, true, false))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Вещь на земле в {@link #PICKUP_RANGE}: далеко — подбежать, рядом — подобрать. Подобранное делится по правилам лута пати, как у живого игрока.
	 * Чужой защищённый дроп (не наш и не нашей пати) не трогает: иначе стоял бы над ним, пока не снимется защита.
	 * @return занят подбором
	 */
	private static boolean pickUp(Player phantom)
	{
		if (phantom.isCastingNow() || phantom.isAttackingNow() || !phantom.isInventoryUnder90(false))
		{
			return false;
		}

		Item nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Item item : World.getVisibleObjectsInRange(phantom, Item.class, PICKUP_RANGE))
		{
			if (!item.isSpawned() || PhantomCompat.ignoredAutoPickItems().contains(item.getId()) || !item.getDropProtection().tryPickUp(phantom) || !PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), phantom.getX(), phantom.getY(), phantom.getZ(), item.getX(), item.getY(), item.getZ(), phantom.getInstanceId()))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(item);
			if (distance < nearestDistance)
			{
				nearest = item;
				nearestDistance = distance;
			}
		}
		if (nearest == null)
		{
			return false;
		}

		if (nearestDistance > 70)
		{
			if (!phantom.isMoving())
			{
				phantom.setRunning();
				phantom.getAI().setIntentionMoveTo(nearest);
			}
			return true;
		}

		phantom.doPickupItem(nearest);
		return true;
	}

	private static void calmDown(Player phantom)
	{
		phantom.abortAttack();
		phantom.setTarget(null);
		phantom.getAI().setIntentionActive();
	}

	private static void follow(Companion companion, Player phantom, Player owner, int slotIndex)
	{
		// Сидя не ходят: сначала встать (иначе счётчик «застрял» телепортировал бы его каждые 4 секунды).
		if (phantom.isSitting())
		{
			phantom.standUp();
			return;
		}

		final Location slot = slot(owner, slotIndex);
		if (phantom.calculateDistance2D(slot) <= SETTLED_RANGE)
		{
			companion.stuckTicks = 0;
			companion.lastDestination = null;
		}
		else
		{
			final boolean barelyMoved = (Math.abs(phantom.getX() - companion.lastX) + Math.abs(phantom.getY() - companion.lastY)) < 20;
			companion.stuckTicks = barelyMoved ? companion.stuckTicks + 1 : 0;
			if (companion.stuckTicks >= STUCK_TICKS)
			{
				teleport(companion, phantom, slot);
				return;
			}

			if (!phantom.isMoving() || (companion.lastDestination == null) || (Math.hypot(companion.lastDestination.getX() - slot.getX(), companion.lastDestination.getY() - slot.getY()) > REPATH_DELTA))
			{
				phantom.setRunning();
				phantom.getAI().setIntentionMoveTo(slot);
				companion.lastDestination = slot;
			}
		}
		companion.lastX = phantom.getX();
		companion.lastY = phantom.getY();
	}

	private static void hold(Companion companion, Player phantom)
	{
		if (companion.stayAt == null)
		{
			companion.stayAt = new Location(phantom.getX(), phantom.getY(), phantom.getZ());
		}

		if ((phantom.calculateDistance2D(companion.stayAt) > STAY_LEASH) && !phantom.isMoving())
		{
			phantom.setRunning();
			phantom.getAI().setIntentionMoveTo(companion.stayAt);
		}
	}

	/**
	 * Телепорт без клиента: {@code onTeleported()} вызывает правка {@code Creature.teleToLocation} из патча 0007.
	 */
	private static void teleport(Companion companion, Player phantom, Location location)
	{
		phantom.abortAttack();
		phantom.abortCast();
		phantom.setTarget(null);
		phantom.teleToLocation(location, false);
		companion.stuckTicks = 0;
		companion.lastDestination = null;
		companion.lastX = location.getX();
		companion.lastY = location.getY();
	}

	/**
	 * Место напарника: сзади-сбоку от владельца, чётные слева, нечётные справа, по геодате; недоступно — точка владельца.
	 */
	private static Location slot(Player owner, int index)
	{
		final double behind = ((owner.getHeading() * 2 * Math.PI) / 65536) + Math.PI;
		final int side = (index % 2) == 0 ? 1 : -1;
		final double angle = behind + (side * Math.toRadians(35 + ((index / 2) * 30)));
		final int x = owner.getX() + (int) (Math.cos(angle) * SLOT_DISTANCE);
		final int y = owner.getY() + (int) (Math.sin(angle) * SLOT_DISTANCE);
		final int z = GeoEngine.getInstance().getHeight(x, y, owner.getZ());
		if (PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), owner.getX(), owner.getY(), owner.getZ(), x, y, z, owner.getInstanceId()))
		{
			return new Location(x, y, z, owner.getHeading());
		}
		return new Location(owner.getX(), owner.getY(), owner.getZ(), owner.getHeading());
	}

	private int slotIndex(Companion companion)
	{
		int index = 0;
		for (Companion other : _companions.values())
		{
			if ((other != companion) && (other.ownerId == companion.ownerId) && (other.name.compareTo(companion.name) < 0))
			{
				index++;
			}
		}
		return index;
	}

	// ---------- Служебные реплики-заготовки (кидай пати, ушёл); болтовня и события — PhantomChat ----------

	static void whisper(Player phantom, Player owner, String text)
	{
		owner.sendPacket(new CreatureSay(phantom, ChatType.WHISPER, phantom.getName(), text));
	}

	/**
	 * Бой с целью, как у напарника ({@link #attack}: скиллы, маг отмахивается под ударами) — для лидера рейда охотников ({@link PhantomRaid}).
	 */
	static void fight(Player phantom, Creature enemy)
	{
		attack(phantom, enemy);
	}

	/**
	 * Подобрать лежащее рядом, как охотник-напарник ({@link #pickUp}) — лидер рейда после боя.
	 * @return пошёл подбирать
	 */
	static boolean pickUpNear(Player phantom)
	{
		return pickUp(phantom);
	}

	static void partySay(Player phantom, String text)
	{
		if (phantom.isInParty())
		{
			phantom.getParty().broadcastCreatureSay(new CreatureSay(phantom, ChatType.PARTY, phantom.getName(), text), phantom);
		}
	}

	private static String key(String name)
	{
		return name.toLowerCase();
	}

	public static PhantomCompanionManager getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomCompanionManager INSTANCE = new PhantomCompanionManager();
	}
}
