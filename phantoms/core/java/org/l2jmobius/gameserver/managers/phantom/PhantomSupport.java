package org.l2jmobius.gameserver.managers.phantom;

import java.io.FileInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.mechanics.skill.AbnormalType;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.network.enums.ChatType;
import org.l2jmobius.gameserver.network.serverpackets.CreatureSay;

/**
 * L2Hub: саппорты в пати (этап 3). Хилер лечит и поднимает, баффер держит бафы на всей пати; оба отдыхают по MP и не дерутся.<br>
 * Каждый тик напарника (1 с) выбирается одно действие: отдых, хил или рес, баф. Скиллы — по id из таблиц ниже; берутся только выученные на текущем уровне, поэтому одна таблица годится для всей ветки (Cleric → Bishop → Cardinal,
 * Cleric → Prophet → Hierophant).<br>
 * Приказы словами (этап 4, {@link PhantomChat}): «хил» и «рес» выполняются раньше всего, «бафни» перекидывает свои бафы заново, «сядь» и «встань» — поверх отдыха по MP.
 */
final class PhantomSupport
{
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
	private static final int RECHARGE = 1013;
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
	}

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

		// Кастует или идёт на дистанцию каста: ждём, но не вечно.
		if (phantom.isCastingNow() || (phantom.getAI().getIntention() == Intention.CAST))
		{
			if ((state.castSince > 0) && ((now - state.castSince) > CAST_WATCHDOG))
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
			else if ((phantom.calculateDistance2D(dead) <= SUPPORT_RANGE) && cast(state, phantom, usable(phantom, RESURRECTION), dead, now))
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
		for (Player member : members)
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
			if ((hp < 50) && cast(state, phantom, firstUsable(phantom, FAST_HEALS, null), vip, now))
			{
				return true;
			}
			if ((hp < 85) && cast(state, phantom, firstUsable(phantom, STRONG_HEALS, PhantomCompat.buffByAbnormal(vip, AbnormalType.LIFE_FORCE_OTHERS) != null ? GREATER_HEAL : null), vip, now))
			{
				return true;
			}
		}

		if ((worst != null) && (worstHp < 35) && cast(state, phantom, firstUsable(phantom, FAST_HEALS, null), worst, now))
		{
			return true;
		}

		if ((below70 >= 2) && cast(state, phantom, firstUsable(phantom, GROUP_HEALS, null), phantom, now))
		{
			return true;
		}

		// Поднять павшего, если остальным ничего не грозит.
		if ((dead != null) && (worstHp >= 50))
		{
			final Skill res = usable(phantom, RESURRECTION);
			if (cast(state, phantom, res, dead, now))
			{
				partySay(phantom, "ресаю " + dead.getName());
				return true;
			}
		}

		if ((worst != null) && (worstHp < 75))
		{
			final Integer skip = PhantomCompat.buffByAbnormal(worst, AbnormalType.LIFE_FORCE_OTHERS) != null ? GREATER_HEAL : null;
			if (cast(state, phantom, firstUsable(phantom, STRONG_HEALS, skip), worst, now))
			{
				return true;
			}
		}

		if (dead != null)
		{
			final Skill res = usable(phantom, RESURRECTION);
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
	 * @return выученный скилл, который можно кастовать прямо сейчас (не на откате, хватает MP), или {@code null}
	 */
	private static Skill usable(Player phantom, int skillId)
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
}
