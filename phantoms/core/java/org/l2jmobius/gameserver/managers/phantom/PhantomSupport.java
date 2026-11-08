package org.l2jmobius.gameserver.managers.phantom;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.config.custom.PhantomPartyConfig;
import org.l2jmobius.gameserver.data.SchemeBufferTable;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.npc.BuffSkillHolder;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.mechanics.skill.enums.SkillFinishType;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * L2Hub: саппорты в пати (этап 3). Хилер лечит и поднимает, баффер держит бафы на всей пати; оба отдыхают по MP и не дерутся, кроме нежити ({@link #smiteUndead}).<br>
 * Каждый тик напарника (1 с) выбирается одно действие: отдых, хил или рес, баф. Скиллы — по id из таблиц ниже; берутся только выученные на текущем уровне, поэтому одна таблица годится для всей ветки (Cleric → Bishop → Cardinal,
 * Cleric → Prophet → Hierophant).<br>
 * Приказы словами (этап 4, {@link PhantomChat}): «хил» и «рес» выполняются раньше всего, «бафни» перекидывает свои бафы заново, «сядь» и «встань» — поверх отдыха по MP.
 */
final class PhantomSupport
{
	private static final Logger LOGGER = Logger.getLogger(PhantomSupport.class.getName());

	/** Снятие дебаффов (раздел 4.124): Purify по таблице уровней, Cleanse за воду — всё остальное. */
	private static final int PURIFY = 1018;
	private static final int CLEANSE = 1409;
	private static final int HOLY_WATER = 8874;
	private static final long WATER_STOCK = 10;
	private static final long ORE_STOCK = 30;
	/** Выключает из боя: снимается раньше сильного хила. */
	private static final String[] HARD_DEBUFFS =
	{
		"PARAL",
		"STONE",
		"STUN",
		"SLEEP",
		"MUTE",
		"SILENCE",
		"ROOT",
		"FEAR"
	};
	/** Самопроверка: Poison 1-го уровня. */
	private static final int POISON_SKILL = 129;

	/** Удары по нежити, сильный первым: Might of Heaven, Disrupt Undead. */
	private static final int[] UNDEAD_STRIKES =
	{
		1028,
		1031
	};
	/** Ниже этого MP по нежити не бьёт: мана нужна на хил и бафы. */
	private static final int SMITE_MIN_MP = 50;
	/** Сильные одиночные хилы, по убыванию: Major Heal, Greater Heal, Heal. */
	private static final int[] STRONG_HEALS =
	{
		1401,
		1217,
		1011
	};
	/** Быстрые одиночные хилы, когда кто-то почти умер: Greater Battle Heal, Battle Heal, Heal. */
	private static final int[] FAST_HEALS =
	{
		1218,
		1015,
		1011
	};
	/** Групповые хилы: Major Group Heal, Greater Group Heal, Group Heal. */
	private static final int[] GROUP_HEALS =
	{
		1402,
		1219,
		1027
	};
	/** Benediction — вся пати разом, долгий откат: только когда низко сразу трое. */
	private static final int BENEDICTION = 1271;
	private static final int RESURRECTION = 1016;
	/** Recharge — мана другому (Elven Elder, Shillien Elder; у Bishop его нет): тому, у кого её меньше {@value #RECHARGE_BELOW_MP}%, маг — первым. */
	static final int RECHARGE = 1013;
	private static final int RECHARGE_BELOW_MP = 50;
	/** Баффер в городе (патч 0018, config/Custom/L2HubTown.ini: TownBuffer). */
	private static final String TOWN_CONFIG = "./config/Custom/L2HubTown.ini";
	private static volatile Boolean _townBuffer;
	/** Greater Heal (1217) лечит ещё и со временем (LIFE_FORCE_OTHERS): поверх своего же действия его не кидаем. */
	private static final int GREATER_HEAL = 1217;

	/** Бафы воину, по приоритету: Haste, Improved Combat, Might, Shield, Greater Might, Focus, Death Whisper, Guidance, Wind Walk, Improved Condition, Bless the Body, Bless the Soul, Magic Barrier, Mental Shield, Regeneration, Elemental Protection. */
	private static final int[] FIGHTER_BUFFS =
	{
		1086,
		1499,
		1068,
		1040,
		1388,
		1077,
		1242,
		1240,
		1204,
		1501,
		1045,
		1048,
		1036,
		1035,
		1044,
		1352
	};
	/** Бафы магу: Acumen, Wind Walk, Improved Combat, Shield, Greater Shield, Concentration, Improved Condition, Bless the Body, Bless the Soul, Magic Barrier, Mental Shield, Regeneration, Elemental Protection. */
	private static final int[] MAGE_BUFFS =
	{
		1085,
		1204,
		1499,
		1040,
		1389,
		1078,
		1501,
		1045,
		1048,
		1036,
		1035,
		1044,
		1352
	};
	/** Improved Combat (1499) заменяет Might и Shield, Improved Condition (1501) — Bless the Body и Bless the Soul: блокирует их слоты, и простой баф не встанет. */
	private static final Map<Integer, Integer> REPLACED_BY = Map.of(1068, 1499, 1040, 1499, 1045, 1501, 1048, 1501);
	private static final Set<AbnormalType> BLOCKED_BY_IMPROVED_COMBAT = Set.of(AbnormalType.PA_UP, AbnormalType.PD_UP, AbnormalType.PA_PD_UP);
	private static final Set<AbnormalType> BLOCKED_BY_IMPROVED_CONDITION = Set.of(AbnormalType.MAX_HP_UP, AbnormalType.MAX_MP_UP);

	/** Свой баф обновляется, когда до конца осталось меньше этого, секунд. */
	private static final int REBUFF_SECONDS = 60;
	/** Лечим и бафаем участников пати не дальше этого от себя. */
	private static final int SUPPORT_RANGE = 900;
	/** Мобы ближе этого бьют кого-то из пати — опасно, не садимся. */
	private static final int DANGER_RANGE = 900;
	private static final int REST_BELOW_MP = 25;
	private static final int REST_UNTIL_MP = 85;
	/** Садится отдыхать, только если владелец не дальше этого; убежал дальше {@link #REST_LEASH} — встаёт и идёт следом. */
	private static final int REST_OWNER_RANGE = 400;
	private static final int REST_LEASH = 600;
	/** Каст дольше этого — флаг каста без клиента залип: прервать. */
	private static final long CAST_WATCHDOG = 10000;
	/** После каста бафа на цель — пауза, пока он встаёт; три неудачи подряд — баф на эту цель откладывается надолго. */
	private static final long BUFF_SETTLE = 8000;
	private static final long BUFF_GIVE_UP = 600000;

	/** Приказ «хил» или «рес» ждёт выполнения не дольше этого. */
	private static final long ORDER_TIMEOUT = 20000;
	/** Приказ «бафни»: свои бафы перекидываются заново в течение этого времени. */
	private static final long REFRESH_TIME = 60000;
	/** Приказ «встань»: столько не садится на MP сам. */
	private static final long NO_REST_TIME = 60000;

	private static class State
	{
		long castSince;
		boolean restAnnounced;
		final Map<Long, long[]> buffAttempts = new ConcurrentHashMap<>(); // ключ цель+скилл → {попыток подряд, не раньше чем}
		// Приказы словами (этап 4): ставит PhantomChat, выполняет тик.
		volatile int healTarget;
		volatile long healUntil;
		volatile int resTarget;
		volatile long resUntil;
		volatile long refreshUntil;
		final Set<Long> refreshed = ConcurrentHashMap.newKeySet(); // цель+скилл, перекинутые по приказу «бафни»
		volatile boolean sitOrdered;
		volatile long noRestUntil;
		int smiteTarget;
		/** Бафер ждёт, что труп поднимет хилер: труп (objectId) → с какого времени ждёт. */
		final Map<Integer, Long> resWaitSince = new HashMap<>();
	}

	/**
	 * Заявка на рес: один труп — один ресающий. Кто первым взял, тот и поднимает; остальные берут другой труп или ждут.
	 * Живёт {@link #RES_CLAIM_TIME} с последнего продления и всё время, пока ресающий кастует по трупу или идёт на каст.
	 */
	private static final class ResClaim
	{
		final int casterId;
		final long until;

		ResClaim(int casterId, long until)
		{
			this.casterId = casterId;
			this.until = until;
		}
	}

	/** Труп (objectId) → заявка. */
	private static final Map<Integer, ResClaim> RES_CLAIMS = new ConcurrentHashMap<>();
	/** Заявка без продления живёт столько, мс: ресающий отвлёкся — труп свободен. */
	private static final long RES_CLAIM_TIME = 8000;
	/** Бафер не берёт труп, пока свободный хилер пати с готовым Resurrection может поднять его сам, — но ждёт не дольше этого, мс. */
	private static final long HEALER_RES_FIRST = 10000;

	private static final Map<Integer, State> STATES = new ConcurrentHashMap<>();

	private PhantomSupport()
	{
	}

	/**
	 * Баффер в городе включён (патч 0018, {@code TownBuffer} в L2HubTown.ini; нет файла — баффер есть, как с патчем 0003): бафы на максимуме и на 3 часа даёт NPC,
	 * и чистый баффер охотников (BD, SWS, Prophet, Warcryer, Overlord) в пати не нужен как баффер — он качается рядом (мысль автора 2026-10-21, docs/PHANTOMS.md 4.36).
	 * Читается один раз: ключ меняется со следующего запуска сервера, как и сам баффер.
	 */
	static boolean townBuffer()
	{
		Boolean on = _townBuffer;
		if (on == null)
		{
			final Properties config = new Properties();
			try (InputStream in = new FileInputStream(TOWN_CONFIG))
			{
				config.load(in);
			}
			catch (Exception e)
			{
				// Нет файла — нет и выключателя: баффер стоит.
			}
			on = !"false".equalsIgnoreCase(config.getProperty("TownBuffer", "True").trim());
			_townBuffer = on;
		}
		return on;
	}

	/**
	 * Самопроверка BUDDIES: подменить ответ {@link #townBuffer()}.
	 * @param on что отвечать; {@code null} — снова как в файле
	 */
	static void setTownBuffer(Boolean on)
	{
		_townBuffer = on;
	}

	/**
	 * @param phantom напарник
	 * @return набор городского баффера для него (docs/PHANTOMS.md, раздел 4.94): полный набор его роли. Пассивки и мгновенные {@code applyEffects} не вешает (Might и Shield на Essence), их тут нет: иначе их не хватало бы вечно.
	 */
	static List<Skill> selfBuffSet(Player phantom)
	{
		final String group = PhantomBufferSets.classGroup(phantom);
		final SchemeBufferTable table = SchemeBufferTable.getInstance();
		final List<Skill> set = new ArrayList<>();
		for (int skillId : table.getSkillsIdsByType(group))
		{
			final BuffSkillHolder holder = table.getAvailableBuff(group, skillId);
			final Skill skill = holder == null ? null : SkillData.getInstance().getSkill(skillId, holder.getLevel());
			if ((skill != null) && skill.isContinuous() && PhantomPersonalNoblesse.allows(phantom, skillId))
			{
				set.add(skill);
			}
		}
		return set;
	}

	/**
	 * Напарник держит набор на себе: только то, чего нет, бесплатно и без каста, как охотник. Несколько скиллов одного типа в наборе (Essence) встают первым: тип уже есть, остальных не хватать не будет.<br>
	 * Что не влезает в слоты, не накладывается и не считается: сервер вытеснил бы самый старый баф, а следующая проверка вернула бы его, вытеснив другой.
	 * @param phantom напарник
	 * @param apply {@code false} — только посчитать, ничего не накладывая
	 * @return сколько скиллов набора наложено, а при {@code apply == false} — сколько не хватает из того, что влезет
	 */
	static int selfBuff(Player phantom, boolean apply)
	{
		int count = 0;
		int buffs = phantom.getBuffCount();
		int dances = phantom.getDanceCount();
		for (Skill skill : selfBuffSet(phantom))
		{
			if (hasBuff(phantom, skill) || !PhantomCompat.buffSlotFree(phantom, skill.isDance(), buffs, dances))
			{
				continue;
			}

			if (apply)
			{
				skill.applyEffects(phantom, phantom);
			}

			if (skill.isDance())
			{
				dances++;
			}
			else
			{
				buffs++;
			}
			count++;
		}
		return count;
	}

	/**
	 * Самопроверка BUDDIES.
	 * @param buffer баффер-напарник
	 * @param member цель
	 * @return сколько бафов из его таблицы висит на цели от него самого
	 */
	static int buffsFrom(Player buffer, Player member)
	{
		int count = 0;
		for (int skillId : member.isMageClass() ? MAGE_BUFFS : FIGHTER_BUFFS)
		{
			final Skill skill = buffer.getKnownSkill(skillId);
			final BuffInfo info = skill == null ? null : PhantomCompat.buffByAbnormal(member, skill.getAbnormalType());
			if ((info != null) && (info.getEffector() == buffer))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Баф этого типа висит, откуда бы он ни был, или его слот закрыт. Скилл без типа — по его id.
	 */
	private static boolean hasBuff(Player phantom, Skill skill)
	{
		final AbnormalType type = skill.getAbnormalType();
		if (type == AbnormalType.NONE)
		{
			return phantom.isAffectedBySkill(skill.getId());
		}
		return (PhantomCompat.buffByAbnormal(phantom, type) != null) || slotBlocked(phantom, type);
	}

	/**
	 * @param template напарник
	 * @return {@code true}, если это саппорт: он не дерётся, а лечит или бафает
	 */
	static boolean isSupport(PhantomTemplate template)
	{
		return (template.getRole() == PhantomTemplate.Role.HEALER) || (template.getRole() == PhantomTemplate.Role.BUFFER);
	}

	/**
	 * Одно действие саппорта за тик.
	 * @param phantom фантом в пати с владельцем
	 * @param owner владелец
	 * @param role роль
	 * @param now текущее время
	 * @return {@code true}, если фантом занят (кастует, идёт на каст, отдыхает) и следовать за владельцем в этот тик не надо
	 */
	static boolean act(Player phantom, Player owner, PhantomTemplate.Role role, long now)
	{
		final State state = STATES.computeIfAbsent(phantom.getObjectId(), id -> new State());
		if (role == PhantomTemplate.Role.HEALER)
		{
			PhantomCompanionManager.keepHealPace(phantom);
		}

		// Кастует или идёт на дистанцию каста: ждём, но не вечно.
		if (phantom.isCastingNow() || (phantom.getAI().getIntention() == Intention.CAST))
		{
			// Намерение могло остаться от чужого каста (атака напарника, цель пропала): тогда отсчёт начинается здесь.
			if (state.castSince == 0)
			{
				state.castSince = now;
			}
			if ((now - state.castSince) > CAST_WATCHDOG)
			{
				phantom.abortCast();
				phantom.getAI().setIntentionActive();
				state.castSince = 0;
				return false;
			}
			return true;
		}
		state.castSince = 0;

		if (orders(state, phantom, now))
		{
			return true;
		}

		final List<Player> members = partyNearby(phantom);
		final boolean danger = inDanger(phantom);
		final boolean urgent = (role == PhantomTemplate.Role.HEALER) && someoneBelow(members, 50);
		// Благословение на пати (раздел 4.124): плановое — пока никому ничего не грозит.
		if (!danger && !urgent && PhantomPartyNoblesse.protectParty(phantom, false, now))
		{
			state.castSince = now;
			return true;
		}
		if (rest(state, phantom, owner, danger, urgent, now))
		{
			return true;
		}

		if (role == PhantomTemplate.Role.HEALER)
		{
			return heal(state, phantom, members, now);
		}
		return buff(state, phantom, members, now);
	}

	/**
	 * Хилер на стадионе ({@link PhantomOlympiad}): лечит себя. Приказов пати и отдыха здесь нет: {@link #act} сажал его посреди матча (раздел 4.119).
	 * @param phantom хилер
	 * @param now текущее время
	 * @return занят кастом, бить соперника в этот тик не надо
	 */
	static boolean olympiadAct(Player phantom, long now)
	{
		// Кастует или идёт на каст: ветка ожидания из act, до приказов и отдыха она не доходит.
		if (phantom.isCastingNow() || (phantom.getAI().getIntention() == Intention.CAST))
		{
			return act(phantom, phantom, PhantomTemplate.Role.HEALER, now);
		}
		final State state = STATES.computeIfAbsent(phantom.getObjectId(), id -> new State());
		state.castSince = 0;
		PhantomCompanionManager.keepHealPace(phantom);
		return heal(state, phantom, List.of(phantom), now);
	}

	/**
	 * Следующий хил сразу после прошлого, не дожидаясь тика (раздел 4.130). Приказы пати и отдых — только на тике.
	 * @param phantom хилер, не кастует и не идёт
	 * @param now текущее время
	 */
	static void paceHeal(Player phantom, long now)
	{
		final State state = STATES.get(phantom.getObjectId());
		if (state == null)
		{
			return;
		}
		final List<Player> members = phantom.isInOlympiadMode() ? List.of(phantom) : partyNearby(phantom);
		if (someoneBelow(members, PACE_HEAL_HP))
		{
			heal(state, phantom, members, now);
		}
	}

	// ---------- Нежить ----------

	/**
	 * @return саппорт умеет бить нежить, и это не выключено
	 */
	static boolean smitesUndead(Player phantom)
	{
		return PhantomPartyConfig.PHANTOM_SUPPORT_SMITE_UNDEAD && (known(phantom, UNDEAD_STRIKES) != null);
	}

	/**
	 * Хилить и бафать некого, а пати дерётся с нежитью: саппорт бьёт её сам (docs/PHANTOMS.md, раздел 4.99). Хил и бафы идут раньше, их разбирает {@link #act}.
	 * Цель — только нежить, которая уже дерётся с пати: сам он мобов не поднимает. Скилл не достаёт с места — подходит, если не приказано стоять.
	 * @param phantom фантом в пати
	 * @param walk можно подойти к цели
	 * @param now текущее время
	 * @return занят: следовать за владельцем в этот тик не надо
	 */
	static boolean smiteUndead(Player phantom, boolean walk, long now)
	{
		if (!PhantomPartyConfig.PHANTOM_SUPPORT_SMITE_UNDEAD || !phantom.isInParty() || (mpPercent(phantom) < SMITE_MIN_MP))
		{
			return false;
		}

		final Skill skill = firstUsable(phantom, UNDEAD_STRIKES, null);
		if (skill == null)
		{
			return false;
		}

		Monster target = null;
		double nearest = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, SUPPORT_RANGE))
		{
			final WorldObject victim = monster.getTarget();
			if (!monster.isAlikeDead() && monster.isUndead() && monster.isInCombat() && (victim instanceof Player) && phantom.getParty().containsPlayer((Player) victim))
			{
				final double distance = phantom.calculateDistance2D(monster);
				if (distance < nearest)
				{
					nearest = distance;
					target = monster;
				}
			}
		}
		if (target == null)
		{
			return false;
		}

		if (!PhantomCompanionManager.inReach(phantom, target, phantom.getMagicalAttackRange(skill)))
		{
			if (!walk)
			{
				return false;
			}
			if (!phantom.isMoving())
			{
				phantom.setRunning();
				phantom.getAI().setIntentionMoveTo(target);
			}
			return true;
		}

		final State state = state(phantom);
		if (!cast(state, phantom, skill, target, now))
		{
			return false;
		}
		if (state.smiteTarget != target.getObjectId())
		{
			state.smiteTarget = target.getObjectId();
			LOGGER.info(PhantomSupport.class.getSimpleName() + ": " + phantom.getName() + " smites " + target.getName() + " with " + skill.getName() + " at " + (int) nearest + ", MP " + (int) mpPercent(phantom) + "%.");
		}
		return true;
	}

	// ---------- Приказы словами (этап 4) ----------

	/**
	 * «Хильни»: подлечить того, кто просит, даже если до порога автохила ещё далеко. Мёртвого — поднять.
	 * @return что сделано — для мозгов
	 */
	static String orderHeal(Player phantom, Player target)
	{
		if (target.isDead())
		{
			return orderRes(phantom, target);
		}

		if ((known(phantom, STRONG_HEALS) == null) && (known(phantom, FAST_HEALS) == null))
		{
			return "нельзя: у тебя нет хила";
		}

		if (hpPercent(target) >= 95)
		{
			return "нельзя: у " + target.getName() + " и так почти полное HP, лечить нечего";
		}

		final State state = state(phantom);
		state.healTarget = target.getObjectId();
		state.healUntil = System.currentTimeMillis() + ORDER_TIMEOUT;
		return "сделано: лечишь " + target.getName();
	}

	/**
	 * «Рес»: поднять того, кто просит (если лежит), иначе первого павшего рядом.
	 * @return что сделано — для мозгов
	 */
	static String orderRes(Player phantom, Player speaker)
	{
		if (phantom.getKnownSkill(RESURRECTION) == null)
		{
			return "нельзя: ты не умеешь ресать";
		}

		Player dead = speaker.isDead() ? speaker : null;
		if (dead == null)
		{
			for (Player member : partyNearby(phantom))
			{
				if (member.isDead())
				{
					dead = member;
					break;
				}
			}
		}

		if (dead == null)
		{
			return "нельзя: никто не лежит, поднимать некого";
		}

		if (dead.isReviveRequested())
		{
			return "сделано: " + dead.getName() + " уже предложили рес, пусть примет";
		}

		final State state = state(phantom);
		state.resTarget = dead.getObjectId();
		state.resUntil = System.currentTimeMillis() + ORDER_TIMEOUT;
		return "сделано: поднимаешь " + dead.getName();
	}

	/**
	 * Заявка жива: труп лежит без предложенного реса, ресающий в игре, жив, в том же инстансе и не бросил труп
	 * (срок не вышел или он кастует по трупу и идёт на каст).
	 */
	private static boolean claimActive(int corpseId, ResClaim claim, long now)
	{
		final Player corpse = World.getPlayer(corpseId);
		final Player caster = World.getPlayer(claim.casterId);
		if ((corpse == null) || !corpse.isDead() || corpse.isReviveRequested() || (caster == null) || !caster.isOnline() || caster.isDead() || (caster.getInstanceId() != corpse.getInstanceId()))
		{
			return false;
		}
		return (now < claim.until) || ((caster.getTarget() == corpse) && (caster.isCastingNow() || (caster.getAI().getIntention() == Intention.CAST)));
	}

	/**
	 * @return труп уже поднимает другой
	 */
	private static boolean claimedByOther(Player phantom, Player corpse, long now)
	{
		final ResClaim claim = RES_CLAIMS.get(corpse.getObjectId());
		if ((claim == null) || (claim.casterId == phantom.getObjectId()))
		{
			return false;
		}
		if (claimActive(corpse.getObjectId(), claim, now))
		{
			return true;
		}
		RES_CLAIMS.remove(corpse.getObjectId(), claim);
		return false;
	}

	/**
	 * Взять труп себе или продлить свою заявку. {@code force} — приказ «рес» от игрока: главнее чужой заявки.
	 * @return труп за этим фантомом
	 */
	private static boolean claimRes(Player phantom, Player corpse, long now, boolean force)
	{
		final int me = phantom.getObjectId();
		final ResClaim claim = RES_CLAIMS.compute(corpse.getObjectId(), (id, old) -> force || (old == null) || (old.casterId == me) || !claimActive(id, old, now) ? new ResClaim(me, now + RES_CLAIM_TIME) : old);
		if (RES_CLAIMS.size() > 32)
		{
			RES_CLAIMS.entrySet().removeIf(entry -> !claimActive(entry.getKey(), entry.getValue(), now));
		}
		return claim.casterId == me;
	}

	/**
	 * @return фантом держит живую заявку на другой труп — занят
	 */
	private static boolean ressingOther(Player phantom, Player corpse, long now)
	{
		for (Map.Entry<Integer, ResClaim> entry : RES_CLAIMS.entrySet())
		{
			if ((entry.getValue().casterId == phantom.getObjectId()) && (entry.getKey() != corpse.getObjectId()) && claimActive(entry.getKey(), entry.getValue(), now))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Resurrection готов и труп удалось взять себе — скилл, иначе {@code null}: труп поднимет другой.
	 */
	private static Skill resSkill(Player phantom, Player corpse, long now)
	{
		final Skill res = usable(phantom, RESURRECTION);
		return (res != null) && claimRes(phantom, corpse, now, false) ? res : null;
	}

	/**
	 * Пати без трупов, которые уже поднимает другой: хилер выбирает павшего только из свободных.
	 */
	private static List<Player> withoutClaimed(Player phantom, List<Player> members, long now)
	{
		final List<Player> result = new ArrayList<>(members.size());
		for (Player member : members)
		{
			if (!member.isDead() || !claimedByOther(phantom, member, now))
			{
				result.add(member);
			}
		}
		return result;
	}

	/**
	 * Хилер раньше бафера: в пати есть живой хилер-фантом с готовым Resurrection, не занятый другим трупом, —
	 * бафер ждёт его до {@link #HEALER_RES_FIRST}, потом поднимает сам.
	 */
	private static boolean healerResFirst(State state, Player phantom, Player corpse, List<Player> members, long now)
	{
		state.resWaitSince.keySet().removeIf(id ->
		{
			final Player player = World.getPlayer(id);
			return (player == null) || !player.isDead();
		});
		final long since = state.resWaitSince.computeIfAbsent(corpse.getObjectId(), id -> now);
		if ((now - since) >= HEALER_RES_FIRST)
		{
			return false;
		}
		for (Player member : members)
		{
			if ((member == phantom) || member.isDead())
			{
				continue;
			}
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(member.getName());
			if ((template != null) && (template.getRole() == PhantomTemplate.Role.HEALER) && (usable(member, RESURRECTION) != null) && !ressingOther(member, corpse, now))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * «Бафни»: свои бафы на пати перекидываются заново, отложенные после неудач — пробуются снова.
	 * @return что сделано — для мозгов
	 */
	static String orderBuff(Player phantom)
	{
		if ((known(phantom, FIGHTER_BUFFS) == null) && (known(phantom, MAGE_BUFFS) == null))
		{
			return "нельзя: у тебя нет бафов, ты хилер — бафы у баффера";
		}

		final State state = state(phantom);
		state.buffAttempts.clear();
		state.refreshed.clear();
		state.refreshUntil = System.currentTimeMillis() + REFRESH_TIME;
		return "сделано: перебафываешь пати";
	}

	/**
	 * «Сядь» — сидит, пока не скажут встать или не начнётся драка (после неё садится снова); владелец ушёл дальше {@link #REST_LEASH} — встаёт и идёт следом.<br>
	 * «Встань» — встаёт и минуту не садится на MP сам.
	 * @return что сделано — для мозгов
	 */
	static String orderSit(Player phantom, boolean sit)
	{
		final State state = state(phantom);
		state.sitOrdered = sit;
		if (sit)
		{
			return inDanger(phantom) ? "сделано: сядешь, как только отобьётесь — сейчас бьют пати" : "сделано: садишься отдохнуть";
		}

		state.noRestUntil = System.currentTimeMillis() + NO_REST_TIME;
		return "сделано: встаёшь";
	}

	/**
	 * Новый приказ движения («за мной», «стой») отменяет «сядь».
	 */
	static void clearSit(Player phantom)
	{
		state(phantom).sitOrdered = false;
	}

	static boolean isSitOrdered(Player phantom)
	{
		final State state = STATES.get(phantom.getObjectId());
		return (state != null) && state.sitOrdered;
	}

	/**
	 * Приказанные хил и рес — раньше всего остального.
	 * @return {@code true}, если занят приказом в этот тик
	 */
	private static boolean orders(State state, Player phantom, long now)
	{
		// Сидя cast() только встаёт; сам каст — в следующий тик, приказ остаётся.
		final boolean sitting = phantom.isSitting();
		if (state.resTarget != 0)
		{
			final Player dead = World.getPlayer(state.resTarget);
			if ((now > state.resUntil) || (dead == null) || !dead.isDead() || dead.isReviveRequested())
			{
				state.resTarget = 0;
			}
			// Приказ игрока главнее заявок: труп переходит к этому фантому, остальные его пропускают.
			else if (claimRes(phantom, dead, now, true) && (phantom.calculateDistance2D(dead) <= SUPPORT_RANGE) && cast(state, phantom, usable(phantom, RESURRECTION), dead, now))
			{
				if (!sitting)
				{
					state.resTarget = 0;
					partySay(phantom, "ресаю " + dead.getName());
				}
				return true;
			}
		}

		if (state.healTarget != 0)
		{
			final Player target = World.getPlayer(state.healTarget);
			if ((now > state.healUntil) || (target == null) || target.isDead() || (hpPercent(target) >= 95))
			{
				state.healTarget = 0;
			}
			else if (phantom.calculateDistance2D(target) <= SUPPORT_RANGE)
			{
				Skill heal = firstUsable(phantom, STRONG_HEALS, null);
				if (heal == null)
				{
					heal = firstUsable(phantom, FAST_HEALS, null);
				}

				if (cast(state, phantom, heal, target, now))
				{
					if (!sitting)
					{
						state.healTarget = 0;
					}
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * @return первый выученный скилл из списка (откат и MP не важны) или {@code null}
	 */
	private static Skill known(Player phantom, int[] skillIds)
	{
		for (int skillId : skillIds)
		{
			final Skill skill = phantom.getKnownSkill(skillId);
			if (skill != null)
			{
				return skill;
			}
		}
		return null;
	}

	private static State state(Player phantom)
	{
		return STATES.computeIfAbsent(phantom.getObjectId(), id -> new State());
	}

	// ---------- Хил и рес ----------

	private static boolean heal(State state, Player phantom, List<Player> members, long now)
	{
		Player worst = null;
		double worstHp = 100;
		Player dead = null;
		int below70 = 0;
		int below40 = 0;
		for (Player member : withoutClaimed(phantom, members, now))
		{
			if (member.isDead())
			{
				if ((dead == null) && !member.isReviveRequested())
				{
					dead = member;
				}
				continue;
			}

			final double hp = hpPercent(member);
			if (hp < 70)
			{
				below70++;
			}
			if (hp < 40)
			{
				below40++;
			}
			if (hp < worstHp)
			{
				worst = member;
				worstHp = hp;
			}
		}

		if ((below40 >= 3) && cast(state, phantom, usable(phantom, BENEDICTION), phantom, now))
		{
			return true;
		}

		// Рейд на эпика, охота на Танатоса (разделы 4.43, 4.45): танк (кого бьёт босс) и лидер — первыми.
		for (Player vip : PhantomRaid.getInstance().keepAlive(phantom))
		{
			if (vip.isDead() || !members.contains(vip))
			{
				continue;
			}
			final double hp = hpPercent(vip);
			if ((hp < 50) && castFirst(state, phantom, FAST_HEALS, null, vip, now))
			{
				return true;
			}
			if ((hp < 85) && castFirst(state, phantom, STRONG_HEALS, PhantomCompat.buffByAbnormal(vip, AbnormalType.LIFE_FORCE_OTHERS) != null ? GREATER_HEAL : null, vip, now))
			{
				return true;
			}
		}

		if ((worst != null) && (worstHp < 35) && castFirst(state, phantom, FAST_HEALS, null, worst, now))
		{
			return true;
		}

		// Раздел 4.124: поднятому и потерявшему — благословение сразу, тяжёлый дебафф — раньше сильного хила.
		if (PhantomPartyNoblesse.protectParty(phantom, true, now))
		{
			state.castSince = now;
			return true;
		}
		if (cure(state, phantom, members, true, now))
		{
			return true;
		}

		if ((below70 >= 2) && castFirst(state, phantom, GROUP_HEALS, null, phantom, now))
		{
			return true;
		}

		// Поднять павшего, если остальным ничего не грозит.
		if ((dead != null) && (worstHp >= 50))
		{
			final Skill res = resSkill(phantom, dead, now);
			if (cast(state, phantom, res, dead, now))
			{
				partySay(phantom, "ресаю " + dead.getName());
				return true;
			}
		}

		if ((worst != null) && (worstHp < 75))
		{
			final Integer skip = PhantomCompat.buffByAbnormal(worst, AbnormalType.LIFE_FORCE_OTHERS) != null ? GREATER_HEAL : null;
			if (castFirst(state, phantom, STRONG_HEALS, skip, worst, now))
			{
				return true;
			}
		}

		if (cure(state, phantom, members, false, now))
		{
			return true;
		}

		if (dead != null)
		{
			final Skill res = resSkill(phantom, dead, now);
			if (cast(state, phantom, res, dead, now))
			{
				partySay(phantom, "ресаю " + dead.getName());
				return true;
			}
		}

		// Elven Elder и Shillien Elder (мысль автора 2026-10-21): лечить нечего — держат ману пати.
		final Player drained = drained(phantom, members);
		return (drained != null) && cast(state, phantom, usable(phantom, RECHARGE), drained, now);
	}

	/**
	 * @return кому нужен Recharge: живой из пати, не сам (себе нельзя), MP меньше {@value #RECHARGE_BELOW_MP}%; маги — первыми, из них — у кого меньше
	 */
	private static Player drained(Player phantom, List<Player> members)
	{
		if (phantom.getKnownSkill(RECHARGE) == null)
		{
			return null;
		}
		Player best = null;
		double bestMp = RECHARGE_BELOW_MP;
		boolean bestMage = false;
		for (Player member : members)
		{
			if ((member == phantom) || member.isDead())
			{
				continue;
			}
			final double mp = mpPercent(member);
			final boolean mage = member.isMageClass();
			if ((mp < RECHARGE_BELOW_MP) && ((best == null) || (mage && !bestMage) || ((mage == bestMage) && (mp < bestMp))))
			{
				best = member;
				bestMp = mp;
				bestMage = mage;
			}
		}
		return best;
	}

	// ---------- Бафы ----------

	/**
	 * Полный набор сначала владельцу и остальным, себе — в конце; воину и магу — свой набор.
	 */
	private static boolean buff(State state, Player phantom, List<Player> members, long now)
	{
		// Бафер с Resurrection поднимает павших из пати сам, как хилер, — описание в окне это обещает. Раньше — только по приказу «рес».
		final Skill res = usable(phantom, RESURRECTION);
		if (res != null)
		{
			for (Player member : members)
			{
				// Один труп — один ресающий; свободный хилер пати поднимает раньше бафера.
				if (!member.isDead() || member.isReviveRequested() || claimedByOther(phantom, member, now) || healerResFirst(state, phantom, member, members, now) || !claimRes(phantom, member, now, false))
				{
					continue;
				}
				// Сидя cast() только встаёт; сам каст — в следующий тик, «ресаю» — тогда же.
				final boolean sitting = phantom.isSitting();
				if (cast(state, phantom, res, member, now))
				{
					if (!sitting)
					{
						partySay(phantom, "ресаю " + member.getName());
					}
					return true;
				}
				break;
			}
		}

		final List<Player> order = new ArrayList<>(members);
		order.remove(phantom);
		order.add(phantom);
		for (Player member : order)
		{
			if (member.isDead())
			{
				continue;
			}

			for (int skillId : member.isMageClass() ? MAGE_BUFFS : FIGHTER_BUFFS)
			{
				final Integer replacement = REPLACED_BY.get(skillId);
				if ((replacement != null) && (phantom.getKnownSkill(replacement) != null))
				{
					continue;
				}

				final Skill skill = usable(phantom, skillId);
				if ((skill == null) || !needsBuff(state, member, skill, now))
				{
					continue;
				}

				final boolean wasSitting = phantom.isSitting();
				if (cast(state, phantom, skill, member, now))
				{
					final long key = ((long) member.getObjectId() << 32) | skillId;
					final long[] attempt = state.buffAttempts.computeIfAbsent(key, k -> new long[2]);
					attempt[0]++;
					attempt[1] = now + (attempt[0] >= 3 ? BUFF_GIVE_UP : BUFF_SETTLE);
					if ((now < state.refreshUntil) && !wasSitting)
					{
						state.refreshed.add(key);
					}
					return true;
				}
			}
		}

		// Перекидывать по приказу «бафни» больше нечего.
		state.refreshUntil = 0;
		return false;
	}

	private static boolean needsBuff(State state, Player member, Skill skill, long now)
	{
		final long key = ((long) member.getObjectId() << 32) | skill.getId();
		if (covered(member, skill) && !refreshing(state, member, skill, key, now))
		{
			state.buffAttempts.remove(key);
			return false;
		}

		final long[] attempt = state.buffAttempts.get(key);
		return (attempt == null) || (now >= attempt[1]);
	}

	/**
	 * Приказ «бафни»: висящий свой баф перекидывается заново, один раз на цель за приказ. Чужой баф того же типа не трогаем.
	 */
	private static boolean refreshing(State state, Player member, Skill skill, long key, long now)
	{
		if ((now >= state.refreshUntil) || state.refreshed.contains(key))
		{
			return false;
		}

		final BuffInfo info = PhantomCompat.buffByAbnormal(member, skill.getAbnormalType());
		return (info != null) && (info.getSkill().getId() == skill.getId());
	}

	/**
	 * Баф этого типа уже висит: свой — пока не подходит к концу, чужой (другой скилл того же типа, например с баффера в городе) — пока не спадёт. Improved Combat и Improved Condition закрывают свои слоты.
	 */
	private static boolean covered(Player member, Skill skill)
	{
		final AbnormalType type = skill.getAbnormalType();
		final BuffInfo info = PhantomCompat.buffByAbnormal(member, type);
		if ((info != null) && ((info.getSkill().getId() != skill.getId()) || (info.getTime() > REBUFF_SECONDS)))
		{
			return true;
		}
		return slotBlocked(member, type);
	}

	private static boolean slotBlocked(Player member, AbnormalType type)
	{
		if (BLOCKED_BY_IMPROVED_COMBAT.contains(type) && (PhantomCompat.buffByAbnormal(member, AbnormalType.IMPROVE_PA_PD_UP) != null))
		{
			return true;
		}
		return BLOCKED_BY_IMPROVED_CONDITION.contains(type) && (PhantomCompat.buffByAbnormal(member, AbnormalType.IMPROVE_HP_MP_UP) != null);
	}

	// ---------- Отдых ----------

	private static boolean rest(State state, Player phantom, Player owner, boolean danger, boolean urgent, long now)
	{
		final double mp = mpPercent(phantom);
		final boolean far = phantom.calculateDistance2D(owner) > REST_LEASH;
		if (far)
		{
			state.sitOrdered = false;
		}

		if (phantom.isSitting())
		{
			// По приказу «сядь» сидит и с полным MP.
			if (danger || urgent || far || (now < state.noRestUntil) || ((mp >= REST_UNTIL_MP) && !state.sitOrdered))
			{
				phantom.standUp();
				state.restAnnounced = false;
			}
			return true;
		}

		if (now < state.noRestUntil)
		{
			return false;
		}

		if (((mp < REST_BELOW_MP) || state.sitOrdered) && !danger && !urgent && !phantom.isInCombat() && !phantom.isMoving() && (phantom.calculateDistance2D(owner) <= (state.sitOrdered ? REST_LEASH : REST_OWNER_RANGE)))
		{
			phantom.sitDown();
			if (!state.restAnnounced)
			{
				state.restAnnounced = true;
				if (!state.sitOrdered)
				{
					PhantomChat.getInstance().event(phantom, owner, PhantomChat.Event.REST, "у тебя кончается MP (" + (int) mp + "%), ты садишься отдохнуть", "сяду на мп");
				}
			}
			return true;
		}
		return false;
	}

	// ---------- Общее ----------

	private static boolean cast(State state, Player phantom, Skill skill, Creature target, long now)
	{
		if (skill == null)
		{
			return false;
		}

		if (phantom.isSitting())
		{
			phantom.standUp();
			return true;
		}

		phantom.setTarget(PhantomCompat.isSelfTarget(skill) ? phantom : target);
		if (PhantomCompat.useMagic(phantom, skill, false, false))
		{
			state.castSince = now;
			return true;
		}
		return false;
	}

	/**
	 * Хил по порядку: скилл сорвался на условиях — следующий, а не пустой тик посреди боя (раздел 4.119). Без {@code HunterCombatFixes} — только первый годный.
	 * @return начал каст
	 */
	private static boolean castFirst(State state, Player phantom, int[] skillIds, Integer skip, Creature target, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_COMBAT_FIXES)
		{
			return cast(state, phantom, firstUsable(phantom, skillIds, skip), target, now);
		}
		for (int skillId : skillIds)
		{
			if (((skip == null) || (skip != skillId)) && cast(state, phantom, usable(phantom, skillId), target, now))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * @return выученный скилл, который можно кастовать прямо сейчас (не на откате, хватает MP), или {@code null}
	 */
	static Skill usable(Player phantom, int skillId)
	{
		final Skill skill = phantom.getKnownSkill(skillId);
		if ((skill == null) || phantom.isSkillDisabled(skill) || (phantom.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())))
		{
			return null;
		}
		return skill;
	}

	private static Skill firstUsable(Player phantom, int[] skillIds, Integer skip)
	{
		for (int skillId : skillIds)
		{
			if ((skip != null) && (skip == skillId))
			{
				continue;
			}

			final Skill skill = usable(phantom, skillId);
			if (skill != null)
			{
				return skill;
			}
		}
		return null;
	}

	// ---------- Помощь живому рядом («Разум 2.0», шаг 6, {@link PhantomNear}) ----------

	/**
	 * @return выученный одиночный хил (откат и MP не важны) или {@code null} — не умеет
	 */
	static Skill knownHeal(Player phantom)
	{
		final Skill skill = known(phantom, STRONG_HEALS);
		return skill != null ? skill : known(phantom, FAST_HEALS);
	}

	/**
	 * @return одиночный хил, который можно кастовать сейчас, сильные первыми, или {@code null}
	 */
	static Skill usableHeal(Player phantom)
	{
		final Skill skill = firstUsable(phantom, STRONG_HEALS, null);
		return skill != null ? skill : firstUsable(phantom, FAST_HEALS, null);
	}

	/**
	 * @return выученный скилл, на который хватает MP (откат не важен: дождётся), или {@code null}
	 */
	static Skill affordable(Player phantom, int skillId)
	{
		final Skill skill = phantom.getKnownSkill(skillId);
		return (skill == null) || (phantom.getCurrentMp() < (skill.getMpConsume() + skill.getMpInitialConsume())) ? null : skill;
	}

	/**
	 * @return одиночный хил, на который хватает MP (откат не важен), сильные первыми, или {@code null}
	 */
	static Skill affordableHeal(Player phantom)
	{
		for (int[] heals : new int[][]
		{
			STRONG_HEALS,
			FAST_HEALS
		})
		{
			for (int skillId : heals)
			{
				final Skill skill = affordable(phantom, skillId);
				if (skill != null)
				{
					return skill;
				}
			}
		}
		return null;
	}

	/**
	 * @return бафы из таблиц саппорта (воину или магу — по цели), которые охотник может кинуть сейчас и которых на цели нет; по приоритету таблицы
	 */
	static List<Skill> missingBuffs(Player buffer, Player target)
	{
		final List<Skill> result = new ArrayList<>();
		for (int skillId : target.isMageClass() ? MAGE_BUFFS : FIGHTER_BUFFS)
		{
			final Integer replacement = REPLACED_BY.get(skillId);
			if ((replacement != null) && (buffer.getKnownSkill(replacement) != null))
			{
				continue;
			}
			final Skill skill = usable(buffer, skillId);
			if ((skill != null) && !covered(target, skill))
			{
				result.add(skill);
			}
		}
		return result;
	}

	private static List<Player> partyNearby(Player phantom)
	{
		final List<Player> result = new ArrayList<>();
		if (!phantom.isInParty())
		{
			result.add(phantom);
			return result;
		}

		for (Player member : phantom.getParty().getMembers())
		{
			if ((member == phantom) || (member.isOnline() && (member.getInstanceId() == phantom.getInstanceId()) && (phantom.calculateDistance2D(member) <= SUPPORT_RANGE)))
			{
				result.add(member);
			}
		}
		return result;
	}

	/**
	 * Мобы рядом бьют кого-то из пати или самого фантома.
	 */
	private static boolean inDanger(Player phantom)
	{
		for (Monster monster : World.getVisibleObjectsInRange(phantom, Monster.class, DANGER_RANGE))
		{
			if (monster.isAlikeDead())
			{
				continue;
			}

			final WorldObject target = monster.getTarget();
			if ((target == phantom) || ((target instanceof Player) && phantom.isInParty() && phantom.getParty().containsPlayer((Player) target) && monster.isInCombat()))
			{
				return true;
			}
		}
		return false;
	}

	/** Между тиками хил только по делу: как нижний порог обычного хила в {@link #heal}. */
	private static final int PACE_HEAL_HP = 75;

	private static boolean someoneBelow(List<Player> members, int percent)
	{
		for (Player member : members)
		{
			if (member.isDead() || (hpPercent(member) < percent))
			{
				return true;
			}
		}
		return false;
	}

	private static double hpPercent(Creature creature)
	{
		return (creature.getCurrentHp() * 100) / Math.max(1, creature.getMaxHp());
	}

	private static double mpPercent(Creature creature)
	{
		return (creature.getCurrentMp() * 100) / Math.max(1, creature.getMaxMp());
	}

	private static void partySay(Player phantom, String text)
	{
		if (phantom.isInParty())
		{
			phantom.getParty().broadcastCreatureSay(new CreatureSay(phantom, ChatType.PARTY, phantom.getName(), text), phantom);
		}
	}

	// ---------- Снятие дебаффов (раздел 4.124; вклад пользователя, только 1018 и 1409) ----------

	/**
	 * Снять дебафф с кого-то из пати, ключ {@code HunterSupportCures}.
	 * @param hard только то, что выключает из боя
	 * @return начал каст
	 */
	private static boolean cure(State state, Player phantom, List<Player> members, boolean hard, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_SUPPORT_CURES)
		{
			return false;
		}
		// Напарнику вода даром, как и шоты; охотник покупает сам ({@link #supplyNeeds}).
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(phantom.getName());
		if ((template != null) && !template.isHunter() && (phantom.getKnownSkill(CLEANSE) != null) && (phantom.getInventory().getInventoryItemCount(HOLY_WATER, -1) < 1))
		{
			PhantomCompat.addItem(phantom, ItemProcessType.REWARD, HOLY_WATER, WATER_STOCK, null);
		}
		// Переделка сопровождающего Эпилога (2026-11-07): у кого тяжелее — первым; кто в досягаемости — первым, к дальнему идёт, только если ближним снимать нечего.
		final List<Player> ordered = new ArrayList<>(members);
		ordered.sort((a, b) -> Integer.compare(curePriority(b), curePriority(a)));
		Player approach = null;
		Skill approachSkill = null;
		for (Player member : ordered)
		{
			final Skill skill = member.isDead() ? null : cureSkill(phantom, member, hard);
			if (skill == null)
			{
				continue;
			}
			final int range = phantom.getMagicalAttackRange(skill);
			if ((member != phantom) && (range >= 0)
				&& (!PhantomCompanionManager.inReach(phantom, member, range)
					|| !org.l2jmobius.gameserver.geoengine.GeoEngine.getInstance().canSeeTarget(phantom, member)))
			{
				if (approach == null)
				{
					approach = member;
					approachSkill = skill;
				}
				continue;
			}
			if (cast(state, phantom, skill, member, now))
			{
				return true;
			}
		}
		return (approach != null) && cast(state, phantom, approachSkill, approach, now);
	}

	/**
	 * Очередь снятия дебаффов: 2 — есть тяжёлый (выключает из боя), 1 — есть другой снимаемый, 0 — снимать нечего.
	 * @param player член пати
	 * @return вес в очереди, больше — раньше
	 */
	static int curePriority(Player player)
	{
		int result = 0;
		for (BuffInfo info : player.getEffectList().getEffects())
		{
			final Skill bad = info.getSkill();
			if (bad.isDebuff() && PhantomCompat.canBeDispeled(bad))
			{
				result = Math.max(result, isHard(String.valueOf(bad.getAbnormalType())) ? 2 : 1);
			}
		}
		return result;
	}

	/**
	 * @return чем снять: сначала Purify, если он это берёт, потом Cleanse при реагенте; {@code null} — нечем или нечего
	 */
	private static Skill cureSkill(Player phantom, Player target, boolean hard)
	{
		final Skill purify = usable(phantom, PURIFY);
		final Skill cleanse = phantom.getInventory().getInventoryItemCount(HOLY_WATER, -1) > 0 ? usable(phantom, CLEANSE) : null;
		if ((purify == null) && (cleanse == null))
		{
			return null;
		}

		boolean other = false;
		for (BuffInfo info : target.getEffectList().getEffects())
		{
			final Skill bad = info.getSkill();
			if (!bad.isDebuff() || !PhantomCompat.canBeDispeled(bad))
			{
				continue;
			}
			final String type = String.valueOf(bad.getAbnormalType());
			if (hard && !isHard(type))
			{
				continue;
			}
			if ((purify != null) && purifyCures(purify.getLevel(), type, bad.getAbnormalLevel()))
			{
				return purify;
			}
			other = true;
		}
		return other ? cleanse : null;
	}

	private static boolean isHard(String type)
	{
		for (String word : HARD_DEBUFFS)
		{
			if (type.contains(word))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Таблица Purify, как в скилле: яд и кровотечение по уровню, паралич, камень со 2-го уровня.
	 * @param level уровень Purify
	 * @param type тип дебаффа
	 * @param abnormalLevel его уровень
	 * @return Purify его снимет
	 */
	static boolean purifyCures(int level, String type, int abnormalLevel)
	{
		switch (type)
		{
			case "POISON":
			case "BLEEDING":
			{
				return abnormalLevel <= (level >= 3 ? 9 : level == 2 ? 7 : 3);
			}
			case "PARALYZE":
			{
				return abnormalLevel <= 1;
			}
			case "TURN_STONE":
			{
				return (level >= 2) && (abnormalLevel <= 2);
			}
			default:
			{
				return false;
			}
		}
	}

	/**
	 * @return чем охотник запасается в городе ({@link PhantomHunterOutfit}): вода на Cleanse и руда на благословение, при своих ключах и если знает скилл
	 */
	static Map<Integer, Long> supplyNeeds(Player player)
	{
		final boolean cures = PhantomHuntersConfig.HUNTER_SUPPORT_CURES && (player.getKnownSkill(CLEANSE) != null);
		final boolean noblesse = PhantomHuntersConfig.HUNTER_PARTY_NOBLESSE && player.isNoble() && (player.getKnownSkill(PhantomPartyNoblesse.BLESSING) != null);
		if (!cures && !noblesse)
		{
			return Map.of();
		}
		final Map<Integer, Long> needs = new HashMap<>();
		if (cures)
		{
			needs.put(HOLY_WATER, WATER_STOCK);
		}
		if (noblesse)
		{
			needs.put(PhantomPartyNoblesse.SPIRIT_ORE, ORE_STOCK);
		}
		return needs;
	}

	/**
	 * {@code HuntersSelfTest = CURES}: таблица Purify, реагенты по ключу; хилер в пати с «живым»: ключ выключен — яд на месте, включён — снят. Идёт в своём потоке: ждёт тиков напарника.
	 * @param world охотники в мире
	 */
	static void selfTest(List<Player> world)
	{
		ThreadPool.execute(() -> runSelfTest(world));
	}

	private static void runSelfTest(List<Player> world)
	{
		final String tag = "SelfTest CURES: ";
		final List<String> failed = new ArrayList<>();
		final boolean key = PhantomHuntersConfig.HUNTER_SUPPORT_CURES;
		Player owner = null;
		PhantomTemplate healerTemplate = null;
		Player member = null;
		PhantomTemplate memberTemplate = null;
		try
		{
			check(tag, failed, "1. таблица Purify", purifyCures(1, "POISON", 3) && !purifyCures(1, "POISON", 4) && purifyCures(2, "BLEEDING", 7) && !purifyCures(2, "BLEEDING", 8) && purifyCures(3, "POISON", 9) && !purifyCures(3, "POISON", 10) && purifyCures(1, "PARALYZE", 1) && !purifyCures(1, "TURN_STONE", 2) && purifyCures(2, "TURN_STONE", 2) && !purifyCures(3, "STUN", 1), "");

			Player healer = null;
			for (Player player : world)
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
				if ((template == null) || (template.getRole() != PhantomTemplate.Role.HEALER) || (player.getKnownSkill(PURIFY) == null) || player.isInParty() || player.isDead() || !PhantomHunterManager.getInstance().isHunting(player))
				{
					continue;
				}
				for (Player other : world)
				{
					if ((other != player) && !other.isInParty() && !other.isDead() && PhantomHunterManager.getInstance().isHunting(other) && PhantomHunterManager.getInstance().acceptsInvite(template, other))
					{
						healer = player;
						healerTemplate = template;
						owner = other;
						break;
					}
				}
				if (healer != null)
				{
					break;
				}
			}
			if (healer == null)
			{
				LOGGER.warning(tag + "needs a free healer with Purify and a hunter near his level.");
				LOGGER.info(tag + "done, " + failed.size() + " failed, the party part is skipped.");
				return;
			}
			LOGGER.info(tag + "healer " + healer.getName() + " (" + healer.getLevel() + "), Purify " + healer.getKnownSkill(PURIFY).getLevel() + ", owner " + owner.getName() + " (" + owner.getLevel() + ").");

			PhantomHuntersConfig.HUNTER_SUPPORT_CURES = false;
			check(tag, failed, "2. ключ выключен: реагент не нужен", !supplyNeeds(healer).containsKey(HOLY_WATER), "");
			PhantomHuntersConfig.HUNTER_SUPPORT_CURES = true;
			check(tag, failed, "3. ключ включён: вода нужна тому, кто знает Cleanse", supplyNeeds(healer).containsKey(HOLY_WATER) == (healer.getKnownSkill(CLEANSE) != null), "Cleanse " + (healer.getKnownSkill(CLEANSE) != null));
			PhantomHuntersConfig.HUNTER_SUPPORT_CURES = false;

			PhantomClans.getInstance().testPlayer(owner);
			PhantomFavors.getInstance().freeze(owner);
			PhantomCompanionManager.getInstance().onInvited(owner, healer, null);
			Thread.sleep(3000);
			if (!healer.isInParty() || (healer.getParty() != owner.getParty()))
			{
				check(tag, failed, "4. хилер вступил в пати", false, "в пати " + healer.isInParty());
				LOGGER.info(tag + "done, " + failed.size() + " failed: " + failed);
				return;
			}
			healer.setCurrentMp(healer.getMaxMp());
			owner.setCurrentHp(owner.getMaxHp());

			// Самый сильный яд, который берёт его Purify: слабый на высоком уровне почти всегда сопротивляется.
			final int purifyLevel = healer.getKnownSkill(PURIFY).getLevel();
			Skill poison = SkillData.getInstance().getSkill(POISON_SKILL, 1);
			for (int level = 2; level <= 5; level++)
			{
				final Skill stronger = SkillData.getInstance().getSkill(POISON_SKILL, level);
				if ((stronger != null) && purifyCures(purifyLevel, "POISON", stronger.getAbnormalLevel()))
				{
					poison = stronger;
				}
			}
			// Шанс дебаффа случайный: кладём, пока не ляжет.
			for (int i = 0; (i < 100) && (owner.getEffectList().getBuffInfoBySkillId(POISON_SKILL) == null); i++)
			{
				poison.applyEffects(owner, owner);
			}
			if (owner.getEffectList().getBuffInfoBySkillId(POISON_SKILL) == null)
			{
				check(tag, failed, "4. яд лёг", false, "уровень яда " + poison.getLevel());
				LOGGER.info(tag + "done, " + failed.size() + " failed: " + failed);
				return;
			}
			Thread.sleep(6000);
			check(tag, failed, "4. ключ выключен: яд на месте", owner.getEffectList().getBuffInfoBySkillId(POISON_SKILL) != null, "яд " + poison.getLevel() + ", дебафф " + poison.isDebuff() + ", снимается " + PhantomCompat.canBeDispeled(poison) + ", уровень " + poison.getAbnormalLevel());

			PhantomHuntersConfig.HUNTER_SUPPORT_CURES = true;
			healer.setCurrentMp(healer.getMaxMp());
			final long deadline = System.currentTimeMillis() + 15000;
			while ((owner.getEffectList().getBuffInfoBySkillId(POISON_SKILL) != null) && (System.currentTimeMillis() < deadline))
			{
				Thread.sleep(250);
			}
			check(tag, failed, "5. ключ включён: хилер снял яд", owner.getEffectList().getBuffInfoBySkillId(POISON_SKILL) == null, "HP " + hpPercent(owner));

			// Шаг 6 (мост, ep-cardinal-cleanse-skips-party-phantoms): тот же яд на охотнике в той же пати — снимает и его, а не только живого.
			for (Player player : world)
			{
				final PhantomTemplate template = PhantomData.getInstance().getTemplate(player.getName());
				if ((player != healer) && (player != owner) && (template != null) && !player.isInParty() && !player.isDead() && PhantomHunterManager.getInstance().isHunting(player) && PhantomHunterManager.getInstance().acceptsInvite(template, owner))
				{
					member = player;
					memberTemplate = template;
					break;
				}
			}
			if (member == null)
			{
				LOGGER.warning(tag + "6. no third hunter near the owner's level, the party member step is skipped.");
			}
			else
			{
				PhantomFavors.getInstance().freeze(member);
				member.teleToLocation(new Location(owner.getX() + 60, owner.getY(), owner.getZ()), false);
				PhantomCompanionManager.getInstance().onInvited(owner, member, null);
				Thread.sleep(3000);
				if (!member.isInParty() || (member.getParty() != owner.getParty()))
				{
					check(tag, failed, "6. охотник вступил в пати", false, member.getName() + " в пати " + member.isInParty());
				}
				else
				{
					member.setCurrentHp(member.getMaxHp());
					healer.setCurrentMp(healer.getMaxMp());
					for (int i = 0; (i < 100) && (member.getEffectList().getBuffInfoBySkillId(POISON_SKILL) == null); i++)
					{
						poison.applyEffects(member, member);
					}
					final boolean poisoned = member.getEffectList().getBuffInfoBySkillId(POISON_SKILL) != null;
					final long memberDeadline = System.currentTimeMillis() + 15000;
					while (poisoned && (member.getEffectList().getBuffInfoBySkillId(POISON_SKILL) != null) && (System.currentTimeMillis() < memberDeadline))
					{
						Thread.sleep(250);
					}
					check(tag, failed, "6. хилер снял яд с охотника в пати", poisoned && (member.getEffectList().getBuffInfoBySkillId(POISON_SKILL) == null), member.getName() + " (" + member.getLevel() + "), яд лёг " + poisoned + ", до хилера " + Math.round(healer.calculateDistance2D(member)));
						orderSteps(tag, failed, healer, owner, member, poison, purifyLevel);
				}
			}
			LOGGER.info(tag + "done, " + failed.size() + " failed" + (failed.isEmpty() ? "." : ": " + failed));
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed", e);
			LOGGER.info(tag + "done, stopped by an exception.");
		}
		finally
		{
			PhantomHuntersConfig.HUNTER_SUPPORT_CURES = key;
			PhantomClans.getInstance().testPlayer(null);
			if (healerTemplate != null)
			{
				PhantomCompanionManager.getInstance().release(healerTemplate, true);
			}
			if (memberTemplate != null)
			{
				PhantomCompanionManager.getInstance().release(memberTemplate, true);
				PhantomFavors.getInstance().unfreeze(member);
			}
			if (owner != null)
			{
				PhantomFavors.getInstance().unfreeze(owner);
				if (owner.isInParty())
				{
					owner.leaveParty();
				}
			}
		}
	}

	/**
	 * Шаги 7 и 8 CURES — порядок снятия (переделка сопровождающего Эпилога): тяжёлый дебафф раньше лёгкого, досягаемый раньше дальнего.
	 * Владелец первым в списке пати, поэтому без очереди хилер начинал бы с него.
	 */
	private static void orderSteps(String tag, List<String> failed, Player healer, Player owner, Player member, Skill poison, int purifyLevel) throws InterruptedException
	{
		// Шаг 7: на владельце яд, на охотнике рядом паралич, который берёт Purify, — первым снимается паралич.
		Skill paralysis = null;
		for (int id = 1; (id < 30000) && (paralysis == null); id++)
		{
			if (SkillData.getInstance().getMaxLevel(id) < 1)
			{
				continue;
			}
			final Skill skill = SkillData.getInstance().getSkill(id, 1);
			if ((skill != null) && skill.isDebuff() && PhantomCompat.canBeDispeled(skill) && "PARALYZE".equals(String.valueOf(skill.getAbnormalType())) && purifyCures(purifyLevel, "PARALYZE", skill.getAbnormalLevel()))
			{
				paralysis = skill;
			}
		}
		final int poisonId = poison.getId();
		if (paralysis == null)
		{
			LOGGER.warning(tag + "7. no paralysis Purify takes in the datapack, the hard-first step is skipped.");
		}
		else
		{
			owner.setCurrentHp(owner.getMaxHp());
			member.setCurrentHp(member.getMaxHp());
			healer.setCurrentMp(healer.getMaxMp());
			for (int i = 0; (i < 100) && (owner.getEffectList().getBuffInfoBySkillId(poisonId) == null); i++)
			{
				poison.applyEffects(owner, owner);
			}
			for (int i = 0; (i < 100) && (member.getEffectList().getBuffInfoBySkillId(paralysis.getId()) == null); i++)
			{
				paralysis.applyEffects(member, member);
			}
			final boolean laid = (owner.getEffectList().getBuffInfoBySkillId(poisonId) != null) && (member.getEffectList().getBuffInfoBySkillId(paralysis.getId()) != null);
			final long[] gone = waitCured(owner, poisonId, member, paralysis.getId(), laid);
			check(tag, failed, "7. тяжёлое первым: паралич охотника снят раньше яда владельца", laid && (gone[1] > 0) && ((gone[0] == 0) || (gone[1] < gone[0])),
				"паралич " + paralysis.getId() + ", лёг " + laid + ", приоритеты " + curePriority(owner) + "/" + curePriority(member) + ", яд снят через " + gone[0] + " мс, паралич через " + gone[1] + " мс");
			clearLeft(owner, poisonId);
			clearLeft(member, paralysis.getId());
		}

		// Шаг 8: оба отравлены, владелец вне досягаемости Purify (в пати, в радиусе поддержки), охотник рядом — каст идёт на охотника.
		// Решение берётся сразу после телепорта прямым вызовом cure: напарник бежит за владельцем и через пару секунд уже достаёт до него.
		final int range = healer.getMagicalAttackRange(healer.getKnownSkill(PURIFY));
		final int far = Math.min(SUPPORT_RANGE - 40, range + 220);
		owner.setCurrentHp(owner.getMaxHp());
		member.setCurrentHp(member.getMaxHp());
		healer.setCurrentMp(healer.getMaxMp());
		for (int i = 0; (i < 100) && (owner.getEffectList().getBuffInfoBySkillId(poisonId) == null); i++)
		{
			poison.applyEffects(owner, owner);
		}
		for (int i = 0; (i < 100) && (member.getEffectList().getBuffInfoBySkillId(poisonId) == null); i++)
		{
			poison.applyEffects(member, member);
		}
		final boolean laid = (owner.getEffectList().getBuffInfoBySkillId(poisonId) != null) && (member.getEffectList().getBuffInfoBySkillId(poisonId) != null);
		healer.abortCast();
		owner.teleToLocation(new Location(healer.getX() + far, healer.getY(), healer.getZ()), false);
		member.teleToLocation(new Location(healer.getX() + 60, healer.getY(), healer.getZ()), false);
		Thread.sleep(300);
		healer.abortCast();
		final int distance = (int) Math.round(healer.calculateDistance2D(owner));
		final boolean listed = partyNearby(healer).contains(owner) && partyNearby(healer).contains(member);
		final boolean first = !healer.getParty().getMembers().isEmpty() && (healer.getParty().getMembers().get(0) == owner);
		final boolean started = cure(new State(), healer, partyNearby(healer), false, System.currentTimeMillis());
		final WorldObject picked = healer.getTarget();
		final long[] gone = waitCured(owner, poisonId, member, poisonId, laid);
		// Цель сразу после вызова — только сведения: свой тик напарника может успеть навести её на себя. Проверка — кто вылечен первым.
		check(tag, failed, "8. досягаемый первым: дальний владелец первым в пати, яд охотника рядом снят раньше", laid && listed && first && (distance > (range + 40)) && (gone[1] > 0) && ((gone[0] == 0) || (gone[1] < gone[0])),
			"лёг " + laid + ", оба в радиусе поддержки " + listed + ", владелец первый в пати " + first + ", владелец в " + distance + " при дальности Purify " + range + ", первый каст " + started + " на " + (picked == null ? "нет" : picked.getName()) + ", яд владельца снят через " + gone[0] + " мс, охотника через " + gone[1] + " мс");
		clearLeft(owner, poisonId);
		clearLeft(member, poisonId);
	}

	/** Ждёт до 20 с, пока снимут оба дебаффа. @return через сколько мс снят первый и второй, 0 — не снят */
	private static long[] waitCured(Player first, int firstSkill, Player second, int secondSkill, boolean laid) throws InterruptedException
	{
		final long[] gone = new long[2];
		final long start = System.currentTimeMillis();
		while (laid && ((gone[0] == 0) || (gone[1] == 0)) && ((System.currentTimeMillis() - start) < 20000))
		{
			Thread.sleep(100);
			final long at = Math.max(1, System.currentTimeMillis() - start);
			if ((gone[0] == 0) && (first.getEffectList().getBuffInfoBySkillId(firstSkill) == null))
			{
				gone[0] = at;
			}
			if ((gone[1] == 0) && (second.getEffectList().getBuffInfoBySkillId(secondSkill) == null))
			{
				gone[1] = at;
			}
		}
		return gone;
	}

	/** Не оставлять самопроверочный дебафф: снять тем же путём, что и снятие в бою. */
	private static void clearLeft(Player player, int skillId)
	{
		if (player.getEffectList().getBuffInfoBySkillId(skillId) != null)
		{
			player.stopSkillEffects(SkillFinishType.REMOVED, skillId);
		}
	}

	private static void check(String tag, List<String> failed, String name, boolean ok, String detail)
	{
		LOGGER.info(tag + (ok ? "OK " : "FAIL ") + name + (detail.isEmpty() ? "" : " (" + detail + ")"));
		if (!ok)
		{
			failed.add(name);
		}
	}
}
