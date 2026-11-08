package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.config.custom.PhantomsConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Playable;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.Summon;
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
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.network.SystemMessageId;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;
import org.l2jmobius.gameserver.taskmanagers.AutoUseTaskManager;
import static org.l2jmobius.gameserver.managers.phantom.PhantomText.tr;

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
	/** Быстрый проход между тиками: следующий каст мага и хилера, раздел 4.130. */
	private static final long PACE_INTERVAL = 200;
	/** Сколько бой держится без нового приказа и без каста: тик охотника — 2 с. */
	private static final long PACE_HOLD = 2400;
	/** Автобафф проверяет набор не чаще этого. Танцы живут 120 секунд: дырка в них не длиннее интервала. */
	private static final long AUTO_BUFF_CHECK = 15000;
	/** Самопроверка: столько ждать, чтобы тик заведомо прошёл; столько дать бафферу на владельца. */
	private static final long AUTO_BUFF_SETTLE = 2500;
	private static final long AUTO_BUFF_OWNER_WAIT = 20000;
	/** Боец дальше своей дальности на этот запас считается не дошедшим до цели: цель могла шагнуть, а обход ей не нужен. */
	private static final int REACH_SLACK = 50;
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
	/** Дальше этого окно NPC в Interlude обрезается (AbstractHtmlPacket), а клиент может упасть: самопроверка следит за окном .buddy. */
	private static final int WINDOW_LIMIT = 8192;
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
	/** «Кач тут»: моб, которого уже бьёт кто-то из пати, считается дальше на столько — напарники расходятся по разным мобам. */
	private static final int HUNT_SPREAD = 400;
	/** Сказали качать, а вокруг столько времени ни одного свободного моба и владелец стоит, мс: дальше ведёт на спот сам, как по «веди». */
	private static final long HUNT_EMPTY_WAIT = 15000;
	/** Как часто в пати проверяет банки, мс. */
	private static final long SUPPLY_INTERVAL = 30000;
	/** Охотник в пати живого: городские дела, когда пати пришла в город, — не чаще раза в столько, мс (как {@code PhantomHunterManager.TOWN_COOLDOWN}). */
	private static final long CHORES_GAP = 60000;
	/** Пати стоит в городе: городские дела снова — раз в столько, мс (как {@code PhantomHunterManager.TOWN_SOFT_COOLDOWN}). */
	private static final long CHORES_INTERVAL = 600000;
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
		/** С какого времени лежит мёртвым без реса; 0 — жив. */
		long deadSince;
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
		/** Автобафф: следующая проверка набора; 0 — в ближайший тик (призыв, рес, новый уровень). */
		long nextAutoBuff;
		/** Банки в пати: следующая проверка; 0 — в ближайший тик. */
		long nextSupply;
		/** С какого времени в HUNT вокруг пусто, а владелец стоит; 0 — есть кого бить. */
		long huntEmptySince;
		/** Охотник в пати живого (раздел 4.146): прошлые городские дела, был ли в городе в прошлый тик и ждут ли дела после прихода в город. */
		long lastChores;
		boolean inTown;
		boolean choresDue;
		/** Что не продалось в лавке: после двух раз — NPC, как у охотника на кач ({@link PhantomHunterOutfit#townChores}). */
		final Map<Integer, Integer> unsold = new ConcurrentHashMap<>();

		Companion(String name)
		{
			this.name = name;
		}
	}

	/** Кого бьёт маг между тиками; {@code target == null} — хилер, цели выбирает сам. */
	private static class Pace
	{
		final Player phantom;
		final Creature target;
		volatile long until;

		Pace(Player phantom, Creature target, long until)
		{
			this.phantom = phantom;
			this.target = target;
			this.until = until;
		}
	}

	private static final Map<Integer, Pace> PACES = new ConcurrentHashMap<>();

	private final Map<String, Companion> _companions = new ConcurrentHashMap<>();
	private ScheduledFuture<?> _task;
	private int _pass;

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
		// Личная пачка (PersonalCompanions): помощник чужого клана или чужой сохранённой пачки не зовётся; в чужую пати зовёт только лидер.
		final String accessError = PersonalCompanionClans.access(owner, template);
		if (accessError != null)
		{
			return accessError;
		}
		if (PersonalCompanions.personal(template) && owner.isInParty() && !isPartiedWith(owner, template))
		{
			if (!owner.getParty().isLeader(owner))
			{
				return tr("Призвать помощника в пати может её лидер.");
			}
			if (owner.getParty().getMemberCount() >= 9)
			{
				return tr("В пати нет места для помощника.");
			}
		}
		if (!PhantomsConfig.PHANTOMS_ENABLED)
		{
			return tr("Напарники выключены (EnablePhantoms в Phantoms.ini).");
		}

		if (template.isHunter())
		{
			return template.getName() + tr(" — не напарник, качается сам.");
		}

		if (owner.isDead() || owner.isInOlympiadMode() || owner.isJailed() || (owner.getInstanceId() != 0))
		{
			return tr("Сейчас позвать нельзя: ты мёртв, на олимпиаде, в тюрьме или в инстансе.");
		}

		final Companion existing = _companions.get(key(template.getName()));
		final Player other = existing == null ? null : World.getPlayer(existing.ownerId);
		final Player current = PhantomManager.getInstance().getPhantom(template);
		if ((other != null) && (other != owner) && other.isOnline() && (current != null) && current.isInParty() && current.getParty().containsPlayer(other))
		{
			return template.getName() + tr(" сейчас в пати с ") + other.getName() + ".";
		}

		final String overLimit = overLimit(owner, template);
		if (overLimit != null)
		{
			return overLimit;
		}

		final Companion companion = existing != null ? existing : new Companion(template.getName());
		companion.ownerId = owner.getObjectId();
		companion.mode = Mode.ASSIST;
		companion.ownerAwaySince = 0;
		companion.stayAt = null;
		companion.lastDestination = null;
		companion.stuckTicks = 0;
		companion.ownerLevel = 0;
		companion.nextAutoBuff = 0;
		companion.nextSupply = 0;
		companion.huntEmptySince = 0;
		_companions.put(key(template.getName()), companion);

		final Player phantom = PhantomManager.getInstance().spawn(template, slot(owner, slotIndex(companion)), owner.getLevel());
		if (phantom == null)
		{
			_companions.remove(key(template.getName()));
			return tr("Не вышло позвать ") + template.getName() + tr(", подробности — в логе сервера.");
		}

		if (phantom.isInParty() && !phantom.getParty().containsPlayer(owner))
		{
			phantom.leaveParty();
		}

		if (!template.isHunter())
		{
			PersonalCompanions.tick(phantom, System.currentTimeMillis());
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
	 * Лимит напарников в одни руки ({@link PhantomPartyConfig#PHANTOM_COMPANIONS_PER_PLAYER}): их на весь мир шесть. Охотники в пати по крику не считаются, позвать заново своего можно всегда.
	 * @param owner кто зовёт
	 * @param template кого зовёт
	 * @return текст отказа или {@code null}
	 */
	private String overLimit(Player owner, PhantomTemplate template)
	{
		final int limit = PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER;
		if (limit <= 0)
		{
			return null;
		}

		final List<String> names = ownedBy(owner);
		names.remove(template.getName());
		return names.size() < limit ? null : tr("Больше напарников нельзя: с тобой уже ") + String.join(", ", names) + tr(" (лимит ") + limit + tr("). Отпусти одного и зови другого.");
	}

	/**
	 * @param owner игрок
	 * @return имена напарников из .buddy, которых он позвал; охотники в пати по крику сюда не входят
	 */
	private List<String> ownedBy(Player owner)
	{
		final List<String> names = new ArrayList<>();
		for (PhantomTemplate template : PhantomData.getInstance().getCompanions())
		{
			if (isOwner(owner, template))
			{
				names.add(template.getName());
			}
		}
		return names;
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
		final String accessError = PersonalCompanionClans.access(owner, template);
		if (accessError != null)
		{
			return accessError;
		}
		final Companion companion = _companions.get(key(template.getName()));
		final Player other = companion == null ? null : World.getPlayer(companion.ownerId);
		if ((other != null) && (other != owner) && other.isOnline())
		{
			return template.getName() + tr(" ходит с ") + other.getName() + tr(", отпустить его может только он.");
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
			return template.getName() + tr(" и так не в мире.");
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
			return template.getName() + tr(" не с тобой — сначала позови его.");
		}

		if (isPartiedWith(owner, phantom))
		{
			return template.getName() + tr(" уже в пати с тобой.");
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
			return template.getName() + tr(" не с тобой — сначала позови его.");
		}

		final Mode previous = companion.mode;
		companion.mode = mode;
		companion.huntEmptySince = 0;
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

		final String accessError = PersonalCompanionClans.access(requestor, template);
		if (accessError != null)
		{
			requestor.sendMessage(accessError);
			return;
		}

		// Охотник идёт, если звал криком именно этот игрок или если он свободен, уровень близкий и не обидчик; уровень остаётся свой.
		final boolean hunter = template.isHunter();
		// Зовёт живой соклан (раздел 4.123): отказ сказан сразу, а переход — на тике самого охотника.
		if (hunter && PhantomClanInvite.getInstance().defer(requestor, phantom, template, type))
		{
			return;
		}
		if (hunter && !PhantomClanInvite.getInstance().dispatching(template) && !PhantomHunterManager.getInstance().acceptsInvite(template, requestor))
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
			requestor.sendMessage(phantom.getName() + tr(" ходит с ") + other.getName() + ".");
			return;
		}

		final String overLimit = hunter ? null : overLimit(requestor, template);
		if (overLimit != null)
		{
			requestor.sendMessage(overLimit);
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
			requestor.sendMessage(phantom.getName() + tr(" лежит без сознания: подними его или позови заново через .buddy."));
			return;
		}

		// Уровень подтягивается до уровня пригласившего (профа, скиллы и шмот собираются заново на месте).
		Player member = phantom;
		if (!hunter && (phantom.getLevel() != requestor.getLevel()))
		{
			member = PhantomManager.getInstance().spawn(template, new Location(phantom.getX(), phantom.getY(), phantom.getZ(), phantom.getHeading()), requestor.getLevel());
			if (member == null)
			{
				requestor.sendMessage(tr("Не вышло пригласить ") + template.getName() + tr(", подробности — в логе сервера."));
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

		if (PersonalCompanionClans.access(owner, template) != null)
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
		companion.nextAutoBuff = 0;
		companion.nextSupply = 0;
		companion.huntEmptySince = 0;
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
			return tr("свободен");
		}

		if (phantom.isDead())
		{
			return tr("лежит без сознания — позови заново");
		}

		final Companion companion = _companions.get(key(template.getName()));
		final Player owner = companion == null ? null : World.getPlayer(companion.ownerId);
		if ((owner != null) && (owner != viewer) && owner.isOnline())
		{
			return tr("ходит с ") + owner.getName();
		}

		if ((owner == viewer) && (companion != null))
		{
			if (isPartiedWith(viewer, phantom))
			{
				return tr("в пати с тобой: ") + (PhantomSupport.isSupport(template) ? supportLabel(template.getRole(), companion.mode) : modeLabel(companion.mode));
			}
			return tr("рядом, ждёт пати: «В пати» или /invite ") + template.getName();
		}
		return tr("свободен");
	}

	/**
	 * @param viewer кто смотрит
	 * @param template напарник
	 * @return другой живой игрок, с которым ходит напарник, или {@code null}
	 */
	public Player busyWith(Player viewer, PhantomTemplate template)
	{
		final Companion companion = _companions.get(key(template.getName()));
		final Player owner = companion == null ? null : World.getPlayer(companion.ownerId);
		return (owner != null) && (owner != viewer) && owner.isOnline() ? owner : null;
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
	 * Помощник уже ходит с другим живым игроком (личная пачка: такого не выдаёт пул).
	 */
	public boolean hasHumanOwnerOtherThan(Player requestor, PhantomTemplate template)
	{
		final Companion existing = _companions.get(key(template.getName()));
		final Player other = existing == null ? null : World.getPlayer(existing.ownerId);
		return (other != null) && (other != requestor) && other.isOnline() && !PhantomManager.getInstance().isPhantom(other);
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
	 * @param template охотник
	 * @param requestor кто зовёт
	 * @return охотник ходит с другим живым игроком в сети
	 */
	boolean ownedByOther(PhantomTemplate template, Player requestor)
	{
		final Companion companion = _companions.get(key(template.getName()));
		final Player other = companion == null ? null : World.getPlayer(companion.ownerId);
		return (other != null) && (other != requestor) && other.isOnline() && !PhantomManager.getInstance().isPhantom(other);
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
		final String noFight = PhantomSupport.smitesUndead(phantom) ? "сделано: идёшь следом и " + job + ", из мобов бьёшь только нежить, которая дерётся с пати" : "нельзя: ты саппорт и в драку не лезешь — идёшь следом и " + job;
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
				return support ? noFight : "сделано: бьёшь его цель";
			}
			case HUNT:
			{
				PhantomSupport.clearSit(phantom);
				setMode(owner, template, support ? Mode.ASSIST : Mode.HUNT);
				return support ? noFight : "сделано: качаешься рядом с ним — сам бьёшь и добиваешь всех мобов вокруг, держишься недалеко";
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
		final String job = role == PhantomTemplate.Role.HEALER ? tr("лечит и поднимает") : tr("держит бафы на пати");
		return (mode == Mode.STAY ? tr("стоит на месте, ") : tr("идёт за тобой, ")) + job;
	}

	private static String modeLabel(Mode mode)
	{
		switch (mode)
		{
			case FOLLOW:
			{
				return tr("идёт за тобой, только защищается");
			}
			case STAY:
			{
				return tr("стоит на месте");
			}
			default:
			{
				return tr("идёт за тобой и бьёт твою цель");
			}
		}
	}

	// ---------- Поведение ----------

	private synchronized void startTicking()
	{
		if ((_task == null) || _task.isDone())
		{
			_task = ThreadPool.scheduleAtFixedRate(this::tick, PACE_INTERVAL, PACE_INTERVAL);
		}
	}

	private void tick()
	{
		final long now = System.currentTimeMillis();
		paceCasts(now);
		if (++_pass < (TICK_INTERVAL / PACE_INTERVAL))
		{
			return;
		}
		_pass = 0;

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

	/**
	 * Следующий каст сразу после прошлого, а не на тике (раздел 4.130): тик напарника — 1 с, охотника — 2 с, и маг с быстрым кастом большую часть боя стоял.
	 * Только то, что тик уже приказал: идёт, сидит, сменил цель — проход не вмешивается, белого игрока не бьёт.
	 */
	private static void paceCasts(long now)
	{
		OlympiadActionPace.tick(now);
		for (Pace pace : PACES.values())
		{
			final Player phantom = pace.phantom;
			try
			{
				final Creature target = pace.target;
				if (!PhantomHuntersConfig.HUNTER_COMBAT_FIXES || (now >= pace.until) || phantom.isDead() || (World.getPlayer(phantom.getObjectId()) != phantom) || phantom.isAutoPlaying() || ((target != null) && (target.isDead() || (phantom.getTarget() != target))))
				{
					PACES.remove(phantom.getObjectId(), pace);
					continue;
				}

				// У зарегистрированного боя один полный проход решений; повторный cast-only проход пропускается.
				if (OlympiadActionPace.owns(phantom)) continue;
				if (phantom.isCastingNow())
				{
					pace.until = now + PACE_HOLD;
					continue;
				}

				// Моб бьёт вплотную — сперва отойти, а не следующий каст под ударами.
				if (PhantomTactics.peel(phantom, now))
				{
					continue;
				}

				if (phantom.isMoving() || phantom.isSitting() || phantom.isTeleporting() || (phantom.getAI().getIntention() == Intention.CAST))
				{
					continue;
				}

				if (target == null)
				{
					PhantomSupport.paceHeal(phantom, now);
				}
				else if (target.isAutoAttackable(phantom))
				{
					castSkill(phantom, target);
				}
			}
			catch (Exception e)
			{
				PACES.remove(phantom.getObjectId(), pace);
				LOGGER.log(Level.WARNING, "PhantomCompanionManager: " + phantom.getName() + " cast pace failed.", e);
			}
		}
	}

	/**
	 * Приказ тика: держать темп каста по цели до {@link #PACE_HOLD} (AutoPlay кастует сам, через AutoUse).
	 */
	private static void keepPace(Player phantom, Creature target)
	{
		if (!PhantomHuntersConfig.HUNTER_COMBAT_FIXES || phantom.isAutoPlaying())
		{
			return;
		}

		final long until = System.currentTimeMillis() + PACE_HOLD;
		final Pace pace = PACES.get(phantom.getObjectId());
		if ((pace != null) && (pace.target == target))
		{
			pace.until = until;
			return;
		}
		PACES.put(phantom.getObjectId(), new Pace(phantom, target, until));
		getInstance().startTicking();
	}

	static long combatPaceInterval() { return PACE_INTERVAL; }
	static void ensureOlympiadPace() { getInstance().startTicking(); }

	/**
	 * Хилер: {@link PhantomSupport#paceHeal} между тиками.
	 */
	static void keepHealPace(Player phantom)
	{
		keepPace(phantom, null);
	}

	/**
	 * Автобафф (docs/PHANTOMS.md, раздел 4.94): напарник, особенно хилер без баффера рядом, сам держит на себе набор городского баффера. Охотник в пати по крику бафается по-своему, а нет баффера в мире — нет и бесплатных бафов.
	 */
	private static void autoBuff(Companion companion, Player phantom, long now)
	{
		if (companion.hunter || (now < companion.nextAutoBuff))
		{
			return;
		}

		companion.nextAutoBuff = now + AUTO_BUFF_CHECK;
		if (PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF && PhantomSupport.townBuffer())
		{
			PhantomSupport.selfBuff(phantom, true);
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
		if (PersonalCompanionClans.access(owner, template) != null)
		{
			_companions.remove(key(companion.name));
			if (phantom.isInParty())
			{
				phantom.leaveParty();
			}
			PhantomManager.getInstance().despawn(template);
			return;
		}
		companion.ownerAwaySince = 0;
		observe(companion, template, phantom, owner);

		// Мёртвому фантому рес предлагают окном «воскресить?», ответить некому — соглашаемся за него.
		if (phantom.isDead())
		{
			companion.nextAutoBuff = 0;
			if (phantom.isReviveRequested())
			{
				phantom.reviveAnswer(1);
				companion.deadSince = 0;
				return;
			}
			if (companion.deadSince == 0)
			{
				companion.deadSince = now;
			}
			recallDead(companion, phantom, owner, now);
			return;
		}

		if (phantom.isTeleporting() || owner.isTeleporting())
		{
			return;
		}

		companion.deadSince = 0;
		autoBuff(companion, phantom, now);

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

		// В пати садиться некогда: банки, как у живого, и AutoUse, который их пьёт (напарнику по призыву его больше никто не включает). Только там, где есть слот маны.
		if (PhantomCompat.hasAutoMana() && (now >= companion.nextSupply))
		{
			companion.nextSupply = now + SUPPLY_INTERVAL;
			PhantomHunterOutfit.partySupplies(phantom, !companion.hunter);
			AutoUseTaskManager.getInstance().startAutoUseTask(phantom);
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
					// Скиллы выучены заново, вместе со старыми могли слететь их бафы.
					companion.nextAutoBuff = 0;
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

		// Охотник в пати живого пришёл с ней в город — свои городские дела, как после захода с кача (раздел 4.146).
		if (companion.hunter && PhantomHuntersConfig.HUNTER_PARTY_LIFE)
		{
			townChores(companion, template, phantom, owner, now);
		}

		// Ноблесс в пати живого дворянина и фон банка реплик (разделы 4.124, 4.122).
		PhantomPartyNoblesse.synchronize(owner, phantom, template, now);
		PhantomChatBank.getInstance().partyTick(phantom, template, now);
		PersonalCompanions.tick(phantom, now);
		if (!PersonalCompanions.readyForAction(phantom, now))
		{
			return;
		}
		if (PersonalCompanions.owned(phantom) && PhantomSummoner.tick(phantom, now))
		{
			return;
		}

		if (PhantomSupport.isSupport(template))
		{
			// Саппорты не дерутся (этап 3): лечат, бафают, отдыхают по MP, бьют только нежить, в остальное время идут следом.
			if (phantom.getAI().getIntention() == Intention.ATTACK)
			{
				calmDown(phantom);
			}

			// Моб бьёт саппорта вплотную — отойти от него и лечить с расстояния; отход не сбивать.
			if (PhantomTactics.kiting(phantom, now) || PhantomTactics.peel(phantom, now))
			{
				return;
			}

			if (PhantomSupport.act(phantom, owner, template.getRole(), now) || PhantomSupport.smiteUndead(phantom, companion.mode != Mode.STAY, now))
			{
				return;
			}
		}
		else
		{
			if (PhantomPartyNoblesse.maintain(phantom, now))
			{
				return;
			}

			// Маг или лучник отбегает от моба: каст по цели сбил бы отход. Моб бьёт вплотную — отойти сейчас, а не ждать тика охотника.
			if (PhantomTactics.kiting(phantom, now) || PhantomTactics.peel(phantom, now))
			{
				return;
			}

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

			// Охотник в пати живого — тактика, как на споте: спойл и свип у гнома, агро танка на мобов, что бьют пати, отход мага и лучника (раздел 4.146).
			if (companion.hunter && PhantomHuntersConfig.HUNTER_PARTY_LIFE && PhantomTactics.tick(phantom, template, now))
			{
				return;
			}

			final Creature enemy = chooseEnemy(companion, phantom, owner);
			if (enemy != null)
			{
				companion.huntEmptySince = 0;
				attack(phantom, enemy);
				return;
			}

			PACES.remove(phantom.getObjectId());
			if (phantom.getAI().getIntention() == Intention.ATTACK)
			{
				calmDown(phantom);
			}

			// «Покачай меня», а бить некого: стоял бы рядом, пока кто-нибудь не реснётся. Ведёт на спот сам.
			if (huntEmpty(companion, owner, now))
			{
				setMode(owner, template, Mode.LEAD);
				PhantomChat.getInstance().event(phantom, owner, Event.HUNT_EMPTY, "вокруг не осталось свободных мобов — ты говоришь ему, что тут пусто, и ведёшь его за собой на другой спот", "тут пусто, пошли за мной");
				return;
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
	 * Охотник в пати живого (раздел 4.146): своя жизнь, как на кач, — в городе надеть удачный дроп, сдать хлам, купить шмот, профу, заточку, стрелы, банки и сосы
	 * ({@link PhantomHunterOutfit#townChores}: за свою адену, автососки снова на сосы грейда). Сам в город не ходит, водит пати живой: дела — когда оба в мирной зоне
	 * и не в бою. Пришли в город — сразу (не чаще {@value #CHORES_GAP} мс), стоят в городе — раз в {@value #CHORES_INTERVAL} мс.
	 */
	private static void townChores(Companion companion, PhantomTemplate template, Player phantom, Player owner, long now)
	{
		final boolean inTown = phantom.isInsideZone(ZoneId.PEACE) && owner.isInsideZone(ZoneId.PEACE);
		if (inTown && !companion.inTown)
		{
			companion.choresDue = true;
		}
		companion.inTown = inTown;
		if (!inTown || phantom.isInCombat() || owner.isInCombat() || phantom.isCastingNow() || phantom.isAttackingNow())
		{
			return;
		}

		final long since = now - companion.lastChores;
		if ((since < CHORES_GAP) || (!companion.choresDue && (since < CHORES_INTERVAL)))
		{
			return;
		}

		companion.choresDue = false;
		companion.lastChores = now;
		final List<String> done = PhantomHunterOutfit.townChores(phantom, template, companion.unsold);
		LOGGER.info(PhantomCompanionManager.class.getSimpleName() + ": " + companion.name + " (" + phantom.getLevel() + ") in town with the party of " + owner.getName() + ": " + (done.isEmpty() ? "nothing to do" : String.join(", ", done)) + ".");
	}

	/**
	 * @return режим HUNT, вокруг пусто и владелец стоит уже {@value #HUNT_EMPTY_WAIT} мс
	 */
	private static boolean huntEmpty(Companion companion, Player owner, long now)
	{
		if ((companion.mode != Mode.HUNT) || owner.isMoving())
		{
			companion.huntEmptySince = 0;
			return false;
		}

		if (companion.huntEmptySince == 0)
		{
			companion.huntEmptySince = now;
		}
		return (now - companion.huntEmptySince) >= HUNT_EMPTY_WAIT;
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
			PersonalCompanions.afterRevive(phantom);
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
	 * Кого бить: моба, который бьёт самого напарника ({@link #selfAttacker}), затем цель владельца (режимы ASSIST и HUNT, владелец в бою), затем текущего противника, затем ближайшего моба, который бьёт нас;
	 * в режиме HUNT — ещё и свободного моба вокруг владельца. Владелец убежал дальше поводка «кач тут» — никого: догнать.
	 * В HUNT цель владельца достаётся одному ({@link #ownerTargetIsMine}), остальные берут её, только когда свободных мобов нет (2026-11-06).
	 */
	private static Creature chooseEnemy(Companion companion, Player phantom, Player owner)
	{
		// Рейд (разделы 4.43, 4.45): своё дело (личинка Королевы) и охота на Танатоса — цель-игрок, которую ниже не берут.
		final Creature raidTarget = PhantomRaid.getInstance().companionTarget(phantom);
		if (raidTarget != null)
		{
			return raidTarget;
		}

		// PvP (раздел 4.119): цель владельца и те, кто бьёт пати. Раньше напарник видел только монстров и стоял, пока владельца убивали.
		if (PhantomHuntersConfig.HUNTER_COMBAT_FIXES)
		{
			final Creature foe = pvpEnemy((companion.mode == Mode.ASSIST) || (companion.mode == Mode.HUNT), companion.mode != Mode.STAY, phantom, owner);
			if (foe != null)
			{
				return foe;
			}
		}

		final boolean hunt = companion.mode == Mode.HUNT;
		final int leash = hunt ? (owner.isMoving() ? HUNT_RUN_LEASH : HUNT_LEASH) : CHASE_LEASH;
		if (hunt && (phantom.calculateDistance2D(owner) > leash))
		{
			return null;
		}

		// Сначала свой обидчик (2026-11-04): моб бьёт самого напарника — сперва он, потом снова цель владельца. Раньше цель владельца шла первой,
		// и Arhimag в пати автора бил её, пока сзади его бил другой моб; отбивался только маг и только от того, кто вплотную (attack, meleeAttacker).
		if ((companion.mode == Mode.ASSIST) || hunt)
		{
			final Monster own = selfAttacker(phantom);
			if (own != null)
			{
				return own;
			}
		}

		// «Кач тут» (решение автора 2026-11-06, жалоба пользователя «по факту я их качаю, а не они меня»): цель владельца берёт один напарник,
		// остальные бьют свободных мобов вокруг; свободных нет — тоже на неё (в конце). Раньше на неё бежали все, и вокруг стояли нетронутые мобы.
		Monster ownerTarget = null;
		if (((companion.mode == Mode.ASSIST) || hunt) && !owner.isDead())
		{
			final WorldObject target = owner.getTarget();
			if ((target instanceof Monster) && isEnemy(phantom, (Monster) target) && (owner.calculateDistance2D(target) <= PhantomPartyConfig.PHANTOM_ASSIST_RANGE) && (owner.isInCombat() || (PhantomHunterOutfit.victimOf((Monster) target) == owner)))
			{
				if (!hunt || ownerTargetIsMine(phantom, owner, (Monster) target))
				{
					return (Monster) target;
				}
				ownerTarget = (Monster) target;
			}
		}

		// Своего противника держит; в «кач тут» — добивает, даже если моб бьёт не его. Цель владельца — только тот, кому она досталась.
		final WorldObject current = phantom.getTarget();
		if ((current instanceof Monster) && (current != ownerTarget) && isEnemy(phantom, (Monster) current) && (hunt ? !takenByOthers((Monster) current, phantom) : attacksUs((Monster) current, companion, phantom, owner)) && (owner.calculateDistance2D(current) <= leash))
		{
			return (Monster) current;
		}

		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, PhantomPartyConfig.PHANTOM_DEFEND_RANGE))
		{
			if ((monster == ownerTarget) || !isEnemy(phantom, monster) || !attacksUs(monster, companion, phantom, owner))
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
			if ((monster == ownerTarget) || !isEnemy(phantom, monster) || monster.isRaid() || (monster.getLevel() > (owner.getLevel() + HUNT_LEVEL_UP)) || takenByOthers(monster, phantom))
			{
				continue;
			}

			// Моб, которого уже бьёт кто-то из пати, — как будто дальше: разойтись по разным, а не толпой на ближайшего.
			final double distance = phantom.calculateDistance2D(monster) + (targetedByParty(monster, phantom) ? HUNT_SPREAD : 0);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest != null ? nearest : ownerTarget;
	}

	/**
	 * «Кач тут»: кому из напарников владельца в этом режиме достаётся его цель. Сначала тем, кто уже её бьёт (чтобы цель не прыгала
	 * между ними каждый тик), из них — ближайшему; никто не бьёт — ближайшему к ней. Равные — по меньшему objectId.
	 */
	private static boolean ownerTargetIsMine(Player phantom, Player owner, Monster target)
	{
		if (!owner.isInParty())
		{
			return true;
		}
		Player best = null;
		boolean bestHolds = false;
		double bestDistance = Double.MAX_VALUE;
		for (Player member : owner.getParty().getMembers())
		{
			if ((member == owner) || member.isDead())
			{
				continue;
			}
			final Companion other = getInstance()._companions.get(key(member.getName()));
			if ((other == null) || (other.ownerId != owner.getObjectId()) || (other.mode != Mode.HUNT))
			{
				continue;
			}
			final boolean holds = member.getTarget() == target;
			final double distance = member.calculateDistance2D(target);
			if ((best == null) || (holds && !bestHolds) || ((holds == bestHolds) && ((distance < bestDistance) || ((distance == bestDistance) && (member.getObjectId() < best.getObjectId())))))
			{
				best = member;
				bestHolds = holds;
				bestDistance = distance;
			}
		}
		return (best == null) || (best == phantom);
	}

	/**
	 * @return моба уже держит целью кто-то ещё из пати
	 */
	private static boolean targetedByParty(Monster monster, Player phantom)
	{
		if (!phantom.isInParty())
		{
			return false;
		}
		for (Player member : phantom.getParty().getMembers())
		{
			if ((member != phantom) && (member.getTarget() == monster))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Противник в PvP: цель владельца, пока он в бою, или ближайший, кто бьёт самого напарника, а кроме режима «стой» — владельца и его пати. Драк сам не начинает: война или флаг рядом — не повод.
	 * @param assist бить цель владельца
	 * @param protect защищать владельца и пати, а не только себя
	 * @return цель или {@code null}
	 */
	private static Creature pvpEnemy(boolean assist, boolean protect, Player phantom, Player owner)
	{
		if (assist && !owner.isDead() && owner.isInCombat() && (owner.getTarget() instanceof Creature))
		{
			final Creature target = (Creature) owner.getTarget();
			if ((owner.calculateDistance2D(target) <= PhantomPartyConfig.PHANTOM_ASSIST_RANGE) && isPvpEnemy(phantom, owner, target))
			{
				return target;
			}
		}

		Creature nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Playable other : World.getVisibleObjectsInRange(phantom, Playable.class, PhantomPartyConfig.PHANTOM_DEFEND_RANGE))
		{
			final WorldObject victim = other.getTarget();
			final boolean onUs = (victim == phantom) || (protect && ((victim == owner) || ((victim instanceof Player) && owner.isInParty() && owner.getParty().containsPlayer((Player) victim))));
			if (!onUs || (!other.isAttackingNow() && !other.isCastingNow() && (other.getAI().getIntention() != Intention.ATTACK)) || (owner.calculateDistance2D(other) > CHASE_LEASH) || !isPvpEnemy(phantom, owner, other))
			{
				continue;
			}

			final double distance = phantom.calculateDistance2D(other);
			if (distance < nearestDistance)
			{
				nearest = other;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * @return игрока или его слугу можно бить, не становясь ПК: чужой, вне мирной зоны, на него законно нападать и напарнику, и владельцу. Белого не тронет, даже если он в цели у владельца.
	 */
	private static boolean isPvpEnemy(Player phantom, Player owner, Creature target)
	{
		final Player enemy = target instanceof Player ? (Player) target : target instanceof Summon ? ((Summon) target).getOwner() : null;
		if ((enemy == null) || (enemy == owner) || (enemy == phantom) || target.isAlikeDead() || target.isInvul() || enemy.isInOlympiadMode() || enemy.isInDuel() || enemy.isOnEvent() || enemy.isSpawnProtected())
		{
			return false;
		}
		if ((owner.isInParty() && owner.getParty().containsPlayer(enemy)) || (phantom.isInParty() && phantom.getParty().containsPlayer(enemy)) || ((owner.getClanId() != 0) && (owner.getClanId() == enemy.getClanId())) || ((owner.getAllyId() != 0) && (owner.getAllyId() == enemy.getAllyId())))
		{
			return false;
		}
		if ((target.getInstanceId() != phantom.getInstanceId()) || phantom.isInsideZone(ZoneId.PEACE) || owner.isInsideZone(ZoneId.PEACE) || target.isInsideZone(ZoneId.PEACE))
		{
			return false;
		}
		return target.isAutoAttackable(phantom) && target.isAutoAttackable(owner) && GeoEngine.getInstance().canSeeTarget(phantom, target);
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

		// Маг и вплотную кастует, цель — тот, кто его бьёт. Оружием отмахивается только без маны (до 2026-11-01, патч 0028, — и под ударами: в пати это выглядело как «бьёт рукой вместо скиллов»).
		Creature target = enemy;
		final boolean caster = PhantomHunterOutfit.isCaster(phantom);
		// Против игрока от моба не отвлекается: иначе маг бросал цель в PvP ради любого, кто его ткнул.
		final Monster attacker = caster && !(PhantomHuntersConfig.HUNTER_COMBAT_FIXES && enemy.isPlayable()) ? PhantomHunterOutfit.meleeAttacker(phantom) : null;
		final boolean melee = caster && PhantomHunterOutfit.outOfMana(phantom);
		if (attacker != null)
		{
			target = attacker;
		}

		if (phantom.getTarget() != target)
		{
			phantom.setTarget(target);
		}

		if (caster && !melee)
		{
			keepPace(phantom, target);
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
			// Цель за геодатой: скиллы не берутся (Cannot see target), а сам к цели он не пойдёт. Vedma простояла так две минуты боя с РБ, пока босс не пришёл к ней сам.
			// То же, если цель дальше всех его скиллов.
			if (!phantom.isMoving() && (!GeoEngine.getInstance().canSeeTarget(phantom, target) || beyondSkills(phantom, target)))
			{
				phantom.setRunning();
				phantom.getAI().setIntentionMoveTo(target);
			}
			return;
		}

		// Намерение ATTACK ведёт к цели по прямой, без поиска пути: упёрся в препятствие и стоит, повтор намерения не поможет. Обходим обычным ходом.
		// Bastion простоял так весь бой с Lilim Knight Commander на дистанции 779.
		if (target.isMonster() && !inReach(phantom, target, phantom.getPhysicalAttackRange() + REACH_SLACK))
		{
			final Intention intention = phantom.getAI().getIntention();
			if (phantom.isMoving() && (intention == Intention.MOVE_TO))
			{
				return;
			}
			if (!phantom.isMoving() && (intention == Intention.ATTACK) && !phantom.isAttackingNow())
			{
				phantom.setRunning();
				phantom.getAI().setIntentionMoveTo(target);
				return;
			}
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
	 * мобов рядом нет — ведёт к ближайшему споту по силам и бьёт всех по дороге. Бой — тот же {@link #attack}: скиллы, маг без маны отмахивается оружием.
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
		// Свой обидчик — раньше своей цели, как и в chooseEnemy.
		final Monster own = selfAttacker(phantom);
		if (own != null)
		{
			return own;
		}

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
	 * Моб, который бьёт самого напарника, в {@code PhantomDefendRange}. Кого бьёт моб — из его ИИ ({@link #hitsMe}): в драке {@code getTarget()} моба врёт.
	 * Своя цель, если бьёт его, — первой: двое на одном не перекидывают его с моба на моба на каждом тике.
	 * @return обидчик или {@code null}
	 */
	private static Monster selfAttacker(Player phantom)
	{
		final WorldObject current = phantom.getTarget();
		if ((current instanceof Monster) && hitsMe((Monster) current, phantom))
		{
			return (Monster) current;
		}

		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, PhantomPartyConfig.PHANTOM_DEFEND_RANGE))
		{
			if (!hitsMe(monster, phantom))
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
	 * Только намерение ATTACK и цель атаки из ИИ: запасной {@code getTarget()} у {@link PhantomHunterOutfit#victimOf} после драки остаётся старым,
	 * и моб, ушедший домой, считался бы обидчиком.
	 * @return моб бьёт самого напарника, и его можно бить ({@link #isEnemy})
	 */
	private static boolean hitsMe(Monster monster, Player phantom)
	{
		return (monster.getAI().getIntention() == Intention.ATTACK) && (PhantomCompat.attackTarget(monster) == phantom) && isEnemy(phantom, monster);
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
	 * Атакующий скилл охотника из его AutoUse (сильные первыми), если он готов сейчас: хватает MP, не на откате, условия выполнены, цель в дальности.
	 * Дальнюю цель каст не берёт: AI игрока подвёл бы к ней только задачей следования, и при её смерти охотник стоял бы с намерением CAST весь бой (раздел 4.98).
	 * К цели подводит {@link #attack}. У напарников из .buddy таких скиллов нет — они бьют оружием, как раньше.
	 * @return начал каст
	 */
	private static boolean castSkill(Player phantom, Creature enemy)
	{
		for (Integer skillId : phantom.getAutoUseSettings().getAutoSkills())
		{
			final Skill skill = phantom.getKnownSkill(skillId);
			if ((skill == null) || phantom.isSkillDisabled(skill) || (phantom.getCurrentMp() < (skill.getMpInitialConsume() + skill.getMpConsume())) || !inCastRange(phantom, skill, enemy) || !skill.checkCondition(phantom, enemy, false))
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
	 * @return цель в пределах range с поправкой на радиусы обоих, как считает CreatureAI.maybeMoveToPawn
	 */
	static boolean inReach(Player phantom, Creature target, int range)
	{
		return phantom.isInsideRadius2D(target, range + phantom.getTemplate().getCollisionRadius() + target.getTemplate().getCollisionRadius());
	}

	/**
	 * Отрицательная дальность у скиллов вокруг себя (Blazing Circle, Aura Flash): к цели ради них не подходят (как и в CreatureAI.maybeMoveToPawn), и бьют они, только
	 * когда цель в радиусе удара — так Mobius собирает их цели (обработчик Aura, {@code getAffectRange}). До 2026-11-04 такой скилл считался достающим любую цель:
	 * маг в пати с игроком крутил массуху в пустоту за 800 от моба, а к цели дальше остальных скиллов не шёл ({@link #beyondSkills} отвечал «достаёт»).
	 * @return скилл достаёт цель с места
	 */
	private static boolean inCastRange(Player phantom, Skill skill, Creature target)
	{
		final int range = phantom.getMagicalAttackRange(skill);
		if (range < 0)
		{
			return phantom.isInsideRadius3D(target, skill.getAffectRange());
		}
		return inReach(phantom, target, range);
	}

	/**
	 * @return у охотника есть скиллы автобоя, и ни один из них не достаёт цель с места
	 */
	private static boolean beyondSkills(Player phantom, Creature target)
	{
		boolean any = false;
		for (Integer skillId : phantom.getAutoUseSettings().getAutoSkills())
		{
			final Skill skill = phantom.getKnownSkill(skillId);
			if (skill != null)
			{
				if (inCastRange(phantom, skill, target))
				{
					return false;
				}
				any = true;
			}
		}
		return any;
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
	 * Мёртвый напарник, которого никто не ресает: владелец ушёл далеко (другой инстанс, дальше {@code PhantomDeadRecallRange})
	 * или прошло {@code PhantomDeadRecallSeconds} — встаёт и становится к владельцу, как после отзыва и призыва.
	 * Пока владелец рядом и срок не вышел — лежит и ждёт рес. Владелец мёртв, в бою, в инстансе или на олимпиаде — ждём.
	 */
	private void recallDead(Companion companion, Player phantom, Player owner, long now)
	{
		final boolean away = (owner.getInstanceId() != phantom.getInstanceId()) || ((PhantomPartyConfig.PHANTOM_DEAD_RECALL_RANGE > 0) && (phantom.calculateDistance2D(owner) > PhantomPartyConfig.PHANTOM_DEAD_RECALL_RANGE));
		final boolean expired = (PhantomPartyConfig.PHANTOM_DEAD_RECALL_SECONDS > 0) && ((now - companion.deadSince) > (PhantomPartyConfig.PHANTOM_DEAD_RECALL_SECONDS * 1000L));
		if (!away && !expired)
		{
			return;
		}
		if (owner.isDead() || owner.isInCombat() || owner.isTeleporting() || (owner.getInstanceId() != 0) || owner.isInOlympiadMode())
		{
			return;
		}

		companion.deadSince = 0;
		companion.nextAutoBuff = 0;
		companion.stayAt = null;
		phantom.doRevive();
		phantom.setCurrentHp(phantom.getMaxHp());
		phantom.setCurrentMp(phantom.getMaxMp());
		phantom.setCurrentCp(phantom.getMaxCp());
		teleport(companion, phantom, slot(owner, slotIndex(companion)));
		LOGGER.info(getClass().getSimpleName() + ": " + companion.name + " was not revived (" + (away ? "owner away" : "time out") + "), gets up next to " + owner.getName() + ".");
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
		if (PhantomPath.hasFloor(GeoEngine.getInstance(), x, y, owner.getZ()) && PhantomCompat.canMoveToTarget(GeoEngine.getInstance(), owner.getX(), owner.getY(), owner.getZ(), x, y, z, owner.getInstanceId()))
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

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка {@code HuntersSelfTest = BUDDIES} (docs/PHANTOMS.md, раздел 4.93, только на копии базы): живых играют трое охотников. Роль берёт свободного, лимит отказывает и не мешает позвать своего заново,
	 * занятого в чужой пати не позвать и не отпустить, на свободных кончается понятным отказом, окно не длиннее {@value #WINDOW_LIMIT} знаков. Автобафф (раздел 4.94) — {@link #autoBuffTest}. Напарники уходят из мира, лимит и автобафф возвращаются.
	 * @param online охотники в мире
	 */
	void selfTest(List<Player> online)
	{
		ThreadPool.execute(() -> runSelfTest(online));
	}

	private void runSelfTest(List<Player> online)
	{
		final String tag = "SelfTest BUDDIES: ";
		final int wasLimit = PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER;
		final boolean wasAutoBuff = PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF;
		final List<PhantomTemplate> all = PhantomData.getInstance().getCompanions();
		final long healers = all.stream().filter(template -> template.getRole() == PhantomTemplate.Role.HEALER).count();
		final long buffers = all.stream().filter(template -> template.getRole() == PhantomTemplate.Role.BUFFER).count();
		final List<Player> free = new ArrayList<>();
		for (Player player : online)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
			if ((template != null) && !template.isReaper() && !player.isDead() && !player.isInParty() && (player.getInstanceId() == 0) && !player.isInOlympiadMode())
			{
				free.add(player);
			}
		}
		if ((healers != 3) || (buffers != 3) || (free.size() < 3))
		{
			LOGGER.info(tag + "needs three healers, three buffers and three free hunters, has " + healers + ", " + buffers + ", " + free.size() + ".");
			return;
		}

		final Player first = free.get(0);
		final Player second = free.get(1);
		final Player third = free.get(2);
		final List<String> failed = new ArrayList<>();
		try
		{
			PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER = 2;

			// Роль берёт свободного: второй хилер — другой.
			final String healerA = PhantomUi.handle(first, "summon", "healer");
			final String healerB = PhantomUi.handle(first, "summon", "healer");
			final List<String> firstOwns = ownedBy(first);
			if ((healerA != null) || (healerB != null) || (firstOwns.size() != 2))
			{
				failed.add("два хилера одному: " + healerA + ", " + healerB + ", с ним " + firstOwns);
			}

			// Лимит: третьего нельзя, своего позвать заново можно.
			final String refused = PhantomUi.handle(first, "summon", "buffer");
			if ((refused == null) || !refused.startsWith(tr("Больше напарников нельзя: с тобой уже ")) || (ownedBy(first).size() != 2))
			{
				failed.add("лимит: " + refused + ", с ним " + ownedBy(first));
			}
			final String mineName = firstOwns.get(0);
			final String again = PhantomUi.handle(first, "summon", mineName);
			if ((again != null) || (ownedBy(first).size() != 2))
			{
				failed.add("своего заново: " + again + ", с ним " + ownedBy(first));
			}

			// Третий хилер — другому, четвёртому игроку хилеров уже нет.
			final String last = PhantomUi.handle(second, "summon", "healer");
			final List<String> secondOwns = ownedBy(second);
			if ((last != null) || (secondOwns.size() != 1) || firstOwns.contains(secondOwns.get(0)))
			{
				failed.add("третий хилер: " + last + ", у второго " + secondOwns + ", у первого " + firstOwns);
			}
			final String none = PhantomUi.handle(third, "summon", "healer");
			if (!tr("Свободных напарников такой роли нет: все заняты. Кто с кем — в .buddy").equals(none) || !ownedBy(third).isEmpty())
			{
				failed.add("хилеры кончились: " + none + ", у третьего " + ownedBy(third));
			}

			// Занятого по имени не позвать и не отпустить.
			final PhantomTemplate taken = PhantomData.getInstance().getTemplate(mineName);
			final String stolen = PhantomUi.handle(third, "summon", mineName);
			final String released = PhantomUi.handle(third, "dismiss", mineName);
			if ((stolen == null) || (released == null) || !isOwner(first, taken))
			{
				failed.add("занятый " + mineName + ": зовут — " + stolen + ", отпускают — " + released);
			}

			// Свободный баффер — третьему, и окно показывает, кто с кем.
			final String buffer = PhantomUi.handle(third, "summon", "buffer");
			final String window = PhantomUi.render(third, "bypass voice .buddy");
			if ((buffer != null) || (ownedBy(third).size() != 1) || !window.contains(tr("ходит с ") + first.getName()) || !window.contains(tr("свободен")) || !window.contains(tr("Напарников с тобой: ") + 1 + tr(" из ") + 2))
			{
				failed.add("баффер и окно третьего: " + buffer + ", с ним " + ownedBy(third) + ", окно " + window.length());
			}

			// Без лимита — сколько угодно, окно не длиннее предела: блок с приказами только у двух своих.
			PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER = 0;
			final String more = PhantomUi.handle(first, "summon", "buffer");
			final String most = PhantomUi.handle(first, "summon", "buffer");
			if ((more != null) || (most != null) || (ownedBy(first).size() != 4))
			{
				failed.add("без лимита: " + more + ", " + most + ", с ним " + ownedBy(first));
			}
			for (Player player : new Player[] { first, second, third })
			{
				final int length = PhantomUi.render(player, "bypass voice .buddy").length();
				LOGGER.info(tag + player.getName() + " has " + ownedBy(player) + ", window " + length + " chars.");
				if (length > WINDOW_LIMIT)
				{
					failed.add("окно " + player.getName() + ": " + length + " знаков");
				}
			}

			// Отпустить по роли — своего, чужой той же роли остаётся.
			final String dismissed = PhantomUi.handle(first, "dismiss", "healer");
			if ((dismissed != null) || (ownedBy(first).size() != 3) || (ownedBy(second).size() != 1))
			{
				failed.add("отпустить по роли: " + dismissed + ", у первого " + ownedBy(first) + ", у второго " + ownedBy(second));
			}

			// Автобафф: у первого остались хилер и два баффера. Выключен в настройках — проверять нечего.
			if (PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF && PhantomSupport.townBuffer())
			{
				autoBuffTest(first, PhantomTemplate.Role.HEALER, failed);
				final Player partyBuffer = autoBuffTest(first, PhantomTemplate.Role.BUFFER, failed);

				// Баффер сам, без «бафни», бафает владельца после вступления в пати. Только в журнал: сколько успеет, зависит от уровня и маны.
				first.stopAllEffects();
				onInvited(first, partyBuffer, PartyDistributionType.FINDERS_KEEPERS);
				Thread.sleep(AUTO_BUFF_OWNER_WAIT);
				LOGGER.info(tag + partyBuffer.getName() + ", level " + partyBuffer.getLevel() + ", in party " + partyBuffer.isInParty() + ", put " + PhantomSupport.buffsFrom(partyBuffer, first) + " buffs on " + first.getName() + " by itself.");
			}
			else
			{
				LOGGER.info(tag + "auto buff is off, not checked.");
			}
		}
		catch (Exception e)
		{
			failed.add("исключение: " + e);
			LOGGER.log(Level.WARNING, tag + "failed.", e);
		}
		finally
		{
			for (PhantomTemplate template : all)
			{
				final Companion companion = _companions.get(key(template.getName()));
				final Player owner = companion == null ? null : World.getPlayer(companion.ownerId);
				if (owner != null)
				{
					dismiss(owner, template, true);
				}
			}
			PhantomPartyConfig.PHANTOM_COMPANIONS_PER_PLAYER = wasLimit;
			PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF = wasAutoBuff;
			PhantomSupport.setTownBuffer(null);
		}

		final long left = all.stream().filter(template -> PhantomManager.getInstance().getPhantom(template) != null).count();
		if (left != 0)
		{
			failed.add("остались в мире: " + left);
		}
		LOGGER.info(tag + (failed.isEmpty() ? "passed." : "FAILED: " + failed));
	}

	/**
	 * Автобафф одного напарника этой роли: набор встал сам при призыве и не перебрал слотов, висящее заново не накладывается; снятое не возвращается до конца интервала, без ключа и без баффера в мире, а потом возвращается; после смерти и реса набор встаёт снова.
	 * @param owner владелец
	 * @param role роль
	 * @param failed сюда — что не сошлось
	 * @return проверенный напарник
	 * @throws InterruptedException сервер останавливается
	 */
	private Player autoBuffTest(Player owner, PhantomTemplate.Role role, List<String> failed) throws InterruptedException
	{
		PhantomTemplate template = null;
		for (String name : ownedBy(owner))
		{
			final PhantomTemplate candidate = PhantomData.getInstance().getTemplate(name);
			if ((template == null) && (candidate.getRole() == role))
			{
				template = candidate;
			}
		}

		final Player phantom = PhantomManager.getInstance().getPhantom(template);
		final Companion companion = _companions.get(key(template.getName()));
		final String who = template.getName() + ": ";
		if (!autoBuffed(phantom))
		{
			failed.add(who + "набор не встал при призыве, не хватает " + PhantomSupport.selfBuff(phantom, false));
			return phantom;
		}

		LOGGER.info("SelfTest BUDDIES: " + template.getName() + " holds " + phantom.getBuffCount() + " of " + phantom.getStat().getMaxBuffCount() + " buffs and " + phantom.getDanceCount() + " of " + PlayerConfig.DANCES_MAX_AMOUNT + " dances, set of " + PhantomSupport.selfBuffSet(phantom).size() + ".");

		// Без последнего бафа и без последнего танца слот под него должен быть: иначе он кого-то вытеснил.
		if (!PhantomCompat.buffSlotFree(phantom, false, phantom.getBuffCount() - 1, phantom.getDanceCount()) || !PhantomCompat.buffSlotFree(phantom, true, phantom.getBuffCount(), phantom.getDanceCount() - 1))
		{
			failed.add(who + "перебор слотов: " + phantom.getBuffCount() + " и " + phantom.getDanceCount());
		}
		if (PhantomSupport.selfBuff(phantom, true) != 0)
		{
			failed.add(who + "висящее накладывается заново");
		}

		// Снятое не возвращается до конца интервала.
		companion.nextAutoBuff = System.currentTimeMillis() + AUTO_BUFF_CHECK;
		phantom.stopSkillEffects(SkillFinishType.REMOVED, PhantomSupport.selfBuffSet(phantom).get(0).getId());
		Thread.sleep(AUTO_BUFF_SETTLE);
		if (PhantomSupport.selfBuff(phantom, false) == 0)
		{
			failed.add(who + "снятое вернулось раньше интервала");
		}

		PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF = false;
		companion.nextAutoBuff = 0;
		Thread.sleep(AUTO_BUFF_SETTLE);
		if (PhantomSupport.selfBuff(phantom, false) == 0)
		{
			failed.add(who + "наложено без ключа");
		}
		PhantomPartyConfig.PHANTOM_COMPANION_AUTO_BUFF = true;

		PhantomSupport.setTownBuffer(false);
		companion.nextAutoBuff = 0;
		Thread.sleep(AUTO_BUFF_SETTLE);
		if (PhantomSupport.selfBuff(phantom, false) == 0)
		{
			failed.add(who + "наложено без баффера в мире");
		}
		PhantomSupport.setTownBuffer(null);

		companion.nextAutoBuff = 0;
		if (!autoBuffed(phantom))
		{
			failed.add(who + "снятое не вернулось, не хватает " + PhantomSupport.selfBuff(phantom, false));
		}

		// Смерть снимает бафы, а тик, пока лежит, сбрасывает интервал.
		phantom.doDie(null);
		Thread.sleep(AUTO_BUFF_SETTLE);
		if (PhantomSupport.selfBuff(phantom, false) == 0)
		{
			failed.add(who + "смерть не сняла бафы, проверять рес не на чем");
		}
		phantom.doRevive();
		if (!autoBuffed(phantom))
		{
			failed.add(who + "после реса набор не встал, не хватает " + PhantomSupport.selfBuff(phantom, false));
		}
		return phantom;
	}

	/**
	 * Ждёт, пока тик наложит набор: пока охотник, который играет владельца, в телепорте, тика нет.
	 */
	private static boolean autoBuffed(Player phantom) throws InterruptedException
	{
		for (int attempt = 0; attempt < 12; attempt++)
		{
			if (PhantomSupport.selfBuff(phantom, false) == 0)
			{
				return true;
			}
			Thread.sleep(500);
		}
		return false;
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
