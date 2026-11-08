package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.holders.player.BlockList;
import org.l2jmobius.gameserver.entity.groups.PartyDistributionType;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.managers.phantom.PhantomRelations.Kind;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: наставники живых новичков (docs/PHANTOMS.md, раздел 4.142; {@code HunterMentors}; идея из вклада пользователя).<br>
 * Раз в {@value #TICK} мс на весь мир: живой новичок до {@code HunterMentorsNoviceLevel} не в пати, кулдаун {@code HunterMentorsCooldown} прошёл, выпал {@code HunterMentorsChance} —
 * рядом ищется свободный охотник от {@code HunterMentorsMinLevel}. Друзья и те, кому новичок помогал, первыми. Это инициатива: фильтры и кулдауны общие с {@link PhantomInitiative},
 * не ответил на шёпот — пара «проигнорирована».
 * <ol>
 * <li>подбегает к новичку (пока идёт урок, своих дел охотник не делает, как в самопроверках: {@link PhantomFavors#freeze});</li>
 * <li>шепчет, где качаться по его уровню (спот из {@link HuntSpotData}), и отдаёт из своей сумки часть банок и шотов грейда новичка, если они у него есть;
 * слова — от мозгов, без мозгов — заготовка;</li>
 * <li>бафер бафает, чего на новичке нет ({@link PhantomSupport#missingBuffs});</li>
 * <li>с {@code HunterMentorsParty} зовёт в пати обычным окном и, если тот согласился, ходит его напарником ({@link PhantomCompanionManager#adopt})
 * {@code HunterMentorsPartyMin}–{@code HunterMentorsPartyMax} минут, потом уходит. Наставник в такой пати не берёт долю опыта и не поднимает уровень пати
 * ({@link PhantomPartyRules}): иначе новичок рядом с 40+ не получал бы опыта совсем. Это врезка в {@code Attackable}, она есть только на High Five — на других хрониках ключ не включать.</li>
 * </ol>
 * Отказ в пати запоминается в переменной новичка {@value #REFUSAL_VARIABLE}: {@code HunterMentorsRefusalHours} часов к нему наставники не подходят. В клан и академию наставник не зовёт.
 */
public final class PhantomMentors
{
	private static final Logger LOGGER = Logger.getLogger(PhantomMentors.class.getName());

	private static final long TICK = 30000;
	private static final long STEP = 1000;
	/** Кого видит наставник вокруг новичка. */
	private static final int RANGE = 1500;
	/** Подошёл. */
	private static final int NEAR = 150;
	private static final long RUN_MAX = 40000;
	/** Окно «вступить в пати?» Mobius держит 15 с; дольше этого ответа не будет. */
	private static final long ASK_MAX = 30000;
	private static final long BUFF_GAP = 2500;
	private static final int BUFF_MAX = 8;
	/** Хилки, которые новичок может пить: Greater, обычная, Lesser. */
	private static final int[] POTIONS =
	{
		1539,
		1061,
		1060
	};
	private static final int POTION_SPARE = 15;
	private static final int POTION_GIVE = 20;
	private static final int SHOT_SPARE = 300;
	private static final int SHOT_GIVE = 500;
	/** Новичок отказал в пати: когда (с от эпохи). */
	static final String REFUSAL_VARIABLE = "L2HubMentorNo";
	private static final String SELF_TEST_VARIABLE = "L2HubMentorsSelfTestId";
	private static final long SAY_GAP = 600000;

	private enum Stage
	{
		RUN,
		TALK,
		BUFF,
		ASK,
		PARTY
	}

	private static final class Lesson
	{
		final Player mentor;
		final PhantomTemplate template;
		final Player novice;
		Stage stage = Stage.RUN;
		long since;
		long nextMove;
		long nextBuff;
		int buffs;
		long until;
		boolean asked;
		ScheduledFuture<?> task;

		Lesson(Player mentor, PhantomTemplate template, Player novice, long now)
		{
			this.mentor = mentor;
			this.template = template;
			this.novice = novice;
			since = now;
		}
	}

	/** Новичок → к нему не подходят до. */
	private final Map<Integer, Long> _next = new ConcurrentHashMap<>();
	/** Наставник → урок. */
	private final Map<Integer, Lesson> _lessons = new ConcurrentHashMap<>();
	private boolean _started;
	/** Самопроверка: мировой проход молчит. */
	private volatile boolean _test;
	/** Самопроверка: «живой» без клиента соглашается на пати. */
	private volatile boolean _testAccept;
	private ScheduledFuture<?> _testWait;
	private long _testWaitSince;

	private PhantomMentors()
	{
	}

	public synchronized void start()
	{
		if (_started)
		{
			return;
		}
		_started = true;
		ThreadPool.scheduleAtFixedRate(this::tick, TICK, TICK);
		if ("MENTORS".equalsIgnoreCase(PhantomHuntersConfig.HUNTERS_SELF_TEST) && (GlobalVariablesManager.getInstance().getInt(SELF_TEST_VARIABLE, 0) != PhantomHuntersConfig.HUNTERS_SELF_TEST_ID))
		{
			_testWaitSince = System.currentTimeMillis();
			_testWait = ThreadPool.scheduleAtFixedRate(this::waitSelfTest, 10000, 10000);
		}
	}

	/**
	 * @param player участник пати
	 * @return он сейчас наставник в пати новичка: доля опыта и уровень пати — без него ({@link PhantomPartyRules})
	 */
	boolean isMentor(Player player)
	{
		final Lesson lesson = _lessons.isEmpty() ? null : _lessons.get(player.getObjectId());
		return (lesson != null) && (lesson.stage == Stage.PARTY);
	}

	boolean active()
	{
		return !_lessons.isEmpty();
	}

	private void tick()
	{
		if (!PhantomHuntersConfig.HUNTER_MENTORS || _test)
		{
			return;
		}
		final long now = System.currentTimeMillis();
		for (Player novice : World.getPlayers())
		{
			// Один урок за проход на весь мир: наставник — событие, а не очередь у каждого новичка.
			if (pass(novice, now, false))
			{
				return;
			}
		}
	}

	/**
	 * Проход по одному игроку.
	 * @param novice игрок
	 * @param now текущее время
	 * @param force самопроверка: без кулдауна и броска
	 * @return урок начался
	 */
	boolean pass(Player novice, long now, boolean force)
	{
		if (!canLearn(novice, now))
		{
			return false;
		}
		final int id = novice.getObjectId();
		if (!force)
		{
			// Только что вошёл: с ним здоровается {@link PhantomWelcome}, наставник — позже.
			final Long next = _next.putIfAbsent(id, now + Rnd.get(PhantomHuntersConfig.HUNTER_MENTORS_COOLDOWN * 250L));
			if ((next == null) || (now < next) || (Rnd.get(100) >= PhantomHuntersConfig.HUNTER_MENTORS_CHANCE))
			{
				return false;
			}
		}

		final List<Player> close = new ArrayList<>();
		final List<Player> others = new ArrayList<>();
		for (Player hunter : World.getVisibleObjectsInRange(novice, Player.class, RANGE))
		{
			if (!canTeach(hunter, novice, now))
			{
				continue;
			}
			if (hunter.getFriendList().contains(id) || (PhantomRelations.getInstance().gratitude(hunter, novice) >= 1))
			{
				close.add(hunter);
			}
			else
			{
				others.add(hunter);
			}
		}
		final List<Player> pool = close.isEmpty() ? others : close;
		if (pool.isEmpty())
		{
			return false;
		}
		begin(pool.get(Rnd.get(pool.size())), novice, now);
		return true;
	}

	/**
	 * Живой новичок, которому сейчас можно помочь: не в пати, не занят, не отказывал недавно, урока у него нет.
	 */
	private boolean canLearn(Player novice, long now)
	{
		if (!PhantomNear.getInstance().isLive(novice) || (novice.getLevel() > PhantomHuntersConfig.HUNTER_MENTORS_NOVICE_LEVEL) || novice.isInParty())
		{
			return false;
		}
		if (novice.isDead() || novice.isTeleporting() || novice.isInOlympiadMode() || novice.isInStoreMode() || novice.isInvisible() || refused(novice, now))
		{
			return false;
		}
		for (Lesson lesson : _lessons.values())
		{
			if (lesson.novice == novice)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * Свободный охотник от {@code HunterMentorsMinLevel}: не в пати, не в бою, не на ивенте и не в рейде, не враг новичку, инициатива разрешает.
	 */
	private boolean canTeach(Player hunter, Player novice, long now)
	{
		if ((hunter == novice) || !PhantomManager.getInstance().isPhantom(hunter) || !PhantomHunterManager.getInstance().isHunter(hunter) || PhantomManager.getInstance().isSecret(hunter) || PhantomNear.getInstance().isLive(hunter))
		{
			return false;
		}
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunter.getName());
		if ((template == null) || (hunter.getLevel() < PhantomHuntersConfig.HUNTER_MENTORS_MIN_LEVEL) || hunter.isInParty() || PhantomCompanionManager.getInstance().isCompanion(template) || _lessons.containsKey(hunter.getObjectId()))
		{
			return false;
		}
		if (hunter.isDead() || hunter.isInCombat() || hunter.isTeleporting() || hunter.isInOlympiadMode() || hunter.isInDuel() || hunter.isInStoreMode() || (PhantomCompat.karma(hunter) > 0))
		{
			return false;
		}
		if (PhantomEvents.getInstance().isBusy(hunter) || PhantomOlympiad.getInstance().isBusy(hunter) || (PhantomRaid.getInstance().raidOf(hunter) != null) || PhantomMoments.getInstance().holds(hunter) || PhantomTrade.getInstance().holds(hunter))
		{
			return false;
		}
		if ((PhantomHunterManager.getInstance().mood(hunter) == PhantomMood.TIRED) || PhantomRelations.getInstance().isEnemy(hunter, novice) || BlockList.isBlocked(novice, hunter))
		{
			return false;
		}
		return PhantomInitiative.getInstance().mayAddress(hunter, novice, now);
	}

	private void begin(Player mentor, Player novice, long now)
	{
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(mentor.getName());
		if (template == null)
		{
			return;
		}
		final Lesson lesson = new Lesson(mentor, template, novice, now);
		if (_lessons.putIfAbsent(mentor.getObjectId(), lesson) != null)
		{
			return;
		}
		_next.put(novice.getObjectId(), now + (PhantomHuntersConfig.HUNTER_MENTORS_COOLDOWN * 1000L));
		PhantomFavors.getInstance().freeze(mentor);
		LOGGER.info(getClass().getSimpleName() + ": " + mentor.getName() + " (" + mentor.getLevel() + ", " + template.getRole() + ") goes to help the novice " + novice.getName() + " (" + novice.getLevel() + ").");
		lesson.task = ThreadPool.scheduleAtFixedRate(() ->
		{
			try
			{
				step(lesson);
			}
			catch (Exception e)
			{
				LOGGER.log(Level.WARNING, getClass().getSimpleName() + ": lesson of " + mentor.getName() + " failed.", e);
				end(lesson, "error");
			}
		}, STEP, STEP);
	}

	private void step(Lesson lesson)
	{
		final Player mentor = lesson.mentor;
		final Player novice = lesson.novice;
		final long now = System.currentTimeMillis();
		if (!mentor.isOnline() || !novice.isOnline() || mentor.isDead())
		{
			end(lesson, "one of them is gone");
			return;
		}

		switch (lesson.stage)
		{
			case RUN:
			{
				if (mentor.calculateDistance3D(novice) > NEAR)
				{
					if ((now - lesson.since) > RUN_MAX)
					{
						end(lesson, "could not reach him");
					}
					else if (now >= lesson.nextMove)
					{
						lesson.nextMove = now + 3000;
						PhantomHunterManager.runTo(mentor, new Location(novice.getX(), novice.getY(), novice.getZ()));
					}
					return;
				}
				if (mentor.isMoving())
				{
					mentor.stopMove(null);
				}
				mentor.getAI().setIntentionIdle();
				lesson.stage = Stage.TALK;
				return;
			}
			case TALK:
			{
				talk(lesson, now);
				lesson.stage = Stage.BUFF;
				lesson.nextBuff = now + 1500;
				return;
			}
			case BUFF:
			{
				if (!buffs(lesson, now))
				{
					lesson.stage = Stage.ASK;
				}
				return;
			}
			case ASK:
			{
				ask(lesson, now);
				return;
			}
			case PARTY:
			{
				final boolean together = mentor.isInParty() && (mentor.getParty() == novice.getParty()) && PhantomCompanionManager.getInstance().isCompanion(lesson.template);
				if (!together)
				{
					end(lesson, "the party is over");
				}
				else if ((now >= lesson.until) || (novice.getLevel() > (PhantomHuntersConfig.HUNTER_MENTORS_NOVICE_LEVEL + 5)))
				{
					// «ладно, я дальше качаться, удачи» — заготовка ухода из пати.
					PhantomCompanionManager.getInstance().release(lesson.template, false);
					end(lesson, "time is up, goes back to hunting");
				}
				return;
			}
		}
	}

	/**
	 * Шёпот новичку: где качаться, что дал и что дальше; банки и шоты переходят из сумки наставника.
	 */
	private void talk(Lesson lesson, long now)
	{
		final Player mentor = lesson.mentor;
		final Player novice = lesson.novice;
		final HuntSpot spot = spotFor(novice);
		final int potions = give(mentor, novice, potionId(mentor), POTION_SPARE, POTION_GIVE);
		final int shots = give(mentor, novice, PhantomHunterOutfit.shotId(novice), SHOT_SPARE, SHOT_GIVE);
		final boolean buffer = lesson.template.getRole() == PhantomTemplate.Role.BUFFER;
		final boolean party = PhantomHuntersConfig.HUNTER_MENTORS_PARTY;

		final StringBuilder what = new StringBuilder(256).append("рядом новичок ").append(PhantomHunterManager.describe(novice)).append(", ").append(novice.getLevel()).append("-го уровня, один, без пати. Ты ").append(mentor.getLevel()).append("-го и решил ему помочь. Шепни ему коротко, по-дружески, как в игре");
		final StringBuilder ru = new StringBuilder(128);
		final StringBuilder en = new StringBuilder(128);
		if (spot != null)
		{
			what.append(", где ему качаться: ").append(place(spot)).append(", мобы ").append(spot.min).append('–').append(spot.max);
			ru.append("на ").append(place(spot)).append(" сходи, там мобы ").append(spot.min).append('-').append(spot.max).append(", тебе в самый раз");
			en.append("try ").append(place(spot)).append(", mobs ").append(spot.min).append('-').append(spot.max).append(" there, just right for you");
		}
		if ((potions > 0) || (shots > 0))
		{
			final String gift = (potions > 0 ? potions + " банок" : "") + ((potions > 0) && (shots > 0) ? " и " : "") + (shots > 0 ? shots + " шотов" : "");
			final String giftEn = (potions > 0 ? potions + " potions" : "") + ((potions > 0) && (shots > 0) ? " and " : "") + (shots > 0 ? shots + " shots" : "");
			what.append("; скажи, что кинул ему ").append(gift).append(" из своей сумки");
			ru.append(ru.isEmpty() ? "" : ". ").append("кинул тебе ").append(gift);
			en.append(en.isEmpty() ? "" : ". ").append("sent you ").append(giftEn);
		}
		if (buffer)
		{
			what.append("; ты бафер, сейчас его бафнёшь");
			ru.append(ru.isEmpty() ? "" : ", ").append("ща бафну");
			en.append(en.isEmpty() ? "" : ", ").append("buffing you now");
		}
		if (party && !refused(novice, now))
		{
			what.append("; позови его в пати минут на пятнадцать, присмотреть, пока качается");
			ru.append(ru.isEmpty() ? "" : ". ").append("го в пати ненадолго, присмотрю");
			en.append(en.isEmpty() ? "" : ". ").append("join my party for a bit, I'll look after you");
		}
		if (ru.isEmpty())
		{
			ru.append("если что непонятно — пиши, подскажу");
			en.append("if anything is unclear, just ask");
		}

		final Runnable waiting = PhantomInitiative.getInstance().book(mentor, novice, now);
		PhantomChat.getInstance().hunterEvent(mentor, novice, "MENTOR", what.toString(), pick(ru.toString(), en.toString()), SAY_GAP, true);
		waiting.run();
		if ((potions > 0) || (shots > 0))
		{
			PhantomRelations.getInstance().record(mentor, novice, Kind.GIFT_GIVEN);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + mentor.getName() + " -> " + novice.getName() + ": spot " + (spot == null ? "none" : place(spot)) + ", potions " + potions + ", shots " + shots + ".");
	}

	/**
	 * Бафер бафает новичка по одному скиллу, пока есть чего не хватает.
	 * @return ещё бафает
	 */
	private static boolean buffs(Lesson lesson, long now)
	{
		if ((lesson.template.getRole() != PhantomTemplate.Role.BUFFER) || (lesson.buffs >= BUFF_MAX))
		{
			return false;
		}
		final Player mentor = lesson.mentor;
		if ((now < lesson.nextBuff) || mentor.isCastingNow())
		{
			return true;
		}
		final List<Skill> missing = PhantomSupport.missingBuffs(mentor, lesson.novice);
		if (missing.isEmpty())
		{
			LOGGER.info(PhantomMentors.class.getSimpleName() + ": " + mentor.getName() + " buffed " + lesson.novice.getName() + ": " + lesson.buffs + " skills.");
			return false;
		}
		if (mentor.isSitting())
		{
			mentor.standUp();
			return true;
		}
		mentor.setTarget(lesson.novice);
		PhantomCompat.useMagic(mentor, missing.get(0), false, false);
		lesson.buffs++;
		lesson.nextBuff = now + BUFF_GAP;
		return true;
	}

	/**
	 * Пати: окно «вступить в пати?»; согласился — наставник становится его напарником, отказал или промолчал — отказ запоминается.
	 */
	private void ask(Lesson lesson, long now)
	{
		final Player mentor = lesson.mentor;
		final Player novice = lesson.novice;
		if (!lesson.asked)
		{
			if (!PhantomHuntersConfig.HUNTER_MENTORS_PARTY || refused(novice, now) || novice.isInParty() || mentor.isInParty() || novice.isDead())
			{
				end(lesson, "done without a party");
				return;
			}
			if (!PhantomCompat.askJoinParty(mentor, novice))
			{
				end(lesson, novice.getName() + " is busy with another request");
				return;
			}
			lesson.asked = true;
			lesson.since = now;
			LOGGER.info(getClass().getSimpleName() + ": " + mentor.getName() + " invites " + novice.getName() + " to his party.");
			return;
		}

		if (_testAccept && !mentor.isInParty() && ((now - lesson.since) >= 2000))
		{
			// Самопроверка: у «живого» нет клиента, ответить окну некому — вступает за него, как RequestAnswerJoinParty.
			novice.setActiveRequester(null);
			mentor.onTransactionResponse();
			PhantomCompat.newParty(mentor, PartyDistributionType.FINDERS_KEEPERS);
			novice.joinParty(mentor.getParty());
		}

		if (mentor.isInParty() && (mentor.getParty() == novice.getParty()))
		{
			if (!PhantomCompanionManager.getInstance().adopt(novice, mentor))
			{
				end(lesson, "could not become his companion");
				return;
			}
			final int min = Math.max(1, PhantomHuntersConfig.HUNTER_MENTORS_PARTY_MIN);
			final int minutes = Rnd.get(min, Math.max(min, PhantomHuntersConfig.HUNTER_MENTORS_PARTY_MAX));
			lesson.until = now + (minutes * 60000L);
			lesson.stage = Stage.PARTY;
			PhantomFavors.getInstance().unfreeze(mentor);
			LOGGER.info(getClass().getSimpleName() + ": " + novice.getName() + " accepted, " + mentor.getName() + " goes along for " + minutes + " min.");
			return;
		}
		if (!mentor.isProcessingRequest() || ((now - lesson.since) > ASK_MAX))
		{
			if (novice.getActiveRequester() == mentor)
			{
				novice.setActiveRequester(null);
			}
			mentor.onTransactionResponse();
			refuse(novice, now);
			end(lesson, novice.getName() + " said no to the party");
		}
	}

	private void end(Lesson lesson, String why)
	{
		if (!_lessons.remove(lesson.mentor.getObjectId(), lesson))
		{
			return;
		}
		if (lesson.task != null)
		{
			lesson.task.cancel(false);
		}
		PhantomFavors.getInstance().unfreeze(lesson.mentor);
		LOGGER.info(getClass().getSimpleName() + ": lesson of " + lesson.mentor.getName() + " for " + lesson.novice.getName() + " ended: " + why + ".");
	}

	// ---------- Что дать и куда послать ----------

	/**
	 * Спот по уровню новичка: мобы вокруг его уровня. Сначала тот, что гайды советуют его роли, потом тот, о котором гайды молчат,
	 * последним — советуемый только другим (docs/PHANTOMS.md, 4.147); среди равных — ближайший к нему.
	 */
	static HuntSpot spotFor(Player novice)
	{
		final int level = novice.getLevel();
		final String role = HuntSpotData.role(novice);
		HuntSpot best = null;
		int bestTier = Integer.MAX_VALUE;
		double bestDistance = Double.MAX_VALUE;
		for (HuntSpot spot : HuntSpotData.getInstance().getSpots())
		{
			if ((spot.min > (level + 2)) || (spot.max < (level - 2)) || (Math.abs(spot.level - level) > 3))
			{
				continue;
			}
			final int tier = spot.suits(role) ? 0 : spot.against(role) ? 2 : 1;
			final double distance = novice.calculateDistance2D(spot.entry);
			if ((tier < bestTier) || ((tier == bestTier) && (distance < bestDistance)))
			{
				best = spot;
				bestTier = tier;
				bestDistance = distance;
			}
		}
		return best;
	}

	/** Имя спота для реплики: «Drakos · юг» — «Drakos (юг)»: после точки бывает одна сторона света, без первой части подсказка ни о чём. */
	private static String place(HuntSpot spot)
	{
		final int dot = spot.name.lastIndexOf(" · ");
		return dot < 0 ? spot.name : spot.name.substring(0, dot) + " (" + spot.name.substring(dot + 3) + ")";
	}

	private static int potionId(Player mentor)
	{
		for (int id : POTIONS)
		{
			if (mentor.getInventory().getItemByItemId(id) != null)
			{
				return id;
			}
		}
		return POTIONS[0];
	}

	/**
	 * Переложить из сумки наставника новичку: не больше {@code most} и не больше трети, если у наставника хотя бы {@code spare}.
	 * @return сколько отдал
	 */
	private static int give(Player mentor, Player novice, int itemId, int spare, int most)
	{
		final Item item = itemId <= 0 ? null : mentor.getInventory().getItemByItemId(itemId);
		if ((item == null) || (item.getCount() < spare))
		{
			return 0;
		}
		final int count = (int) Math.min(most, item.getCount() / 3);
		if ((count <= 0) || !mentor.destroyItemByItemId(ItemProcessType.TRANSFER, itemId, count, novice, false))
		{
			return 0;
		}
		novice.addItem(ItemProcessType.TRANSFER, itemId, count, mentor, true);
		return count;
	}

	private static String pick(String ru, String en)
	{
		return "en".equals(L2HubWorldConfig.LANGUAGE) ? en : ru;
	}

	// ---------- Отказ ----------

	private static boolean refused(Player novice, long now)
	{
		final String value = novice.getVariables().getString(REFUSAL_VARIABLE, "");
		if (value.isEmpty())
		{
			return false;
		}
		try
		{
			return ((now / 1000) - Long.parseLong(value)) < (PhantomHuntersConfig.HUNTER_MENTORS_REFUSAL_HOURS * 3600L);
		}
		catch (NumberFormatException e)
		{
			return false;
		}
	}

	private static void refuse(Player novice, long now)
	{
		novice.getVariables().set(REFUSAL_VARIABLE, String.valueOf(now / 1000));
		novice.getVariables().storeMe();
		LOGGER.info(PhantomMentors.class.getSimpleName() + ": " + novice.getName() + " refused a mentor, no mentors for " + PhantomHuntersConfig.HUNTER_MENTORS_REFUSAL_HOURS + " h.");
	}

	// ---------- Самопроверка ----------

	private static void line(String text)
	{
		LOGGER.info(PhantomMentors.class.getSimpleName() + ": SelfTest MENTORS: " + text);
	}

	private static List<Player> freeHunters()
	{
		final List<Player> hunters = new ArrayList<>();
		for (Player player : World.getPlayers())
		{
			if (PhantomManager.getInstance().isPhantom(player) && PhantomHunterManager.getInstance().isHunter(player) && !PhantomManager.getInstance().isSecret(player) && !player.isDead() && !player.isInParty() && !player.isInOlympiadMode())
			{
				hunters.add(player);
			}
		}
		return hunters;
	}

	/**
	 * {@code HuntersSelfTest = MENTORS} (только на копии базы): ждём хотя бы 12 свободных охотников в мире, не дольше 10 минут.
	 */
	private void waitSelfTest()
	{
		final List<Player> hunters = freeHunters();
		final boolean late = (System.currentTimeMillis() - _testWaitSince) > 600000;
		if ((hunters.size() < 12) && !late)
		{
			return;
		}
		_testWait.cancel(false);
		GlobalVariablesManager.getInstance().set(SELF_TEST_VARIABLE, PhantomHuntersConfig.HUNTERS_SELF_TEST_ID);
		GlobalVariablesManager.getInstance().storeMe();
		if (hunters.size() < 2)
		{
			line("FAILED: only " + hunters.size() + " free hunters.");
			return;
		}
		selfTest(hunters);
	}

	/**
	 * Живого новичка играет самый младший охотник ({@link PhantomNear#testPlayer}), наставник — самый старший бафер от 40-го, а нет такого — самый старший охотник.
	 * Урок идёт настоящим путём; согласие на пати за «живого» даёт сама проверка. Дальше: доля опыта и уровень пати без наставника, уход по времени, кулдаун новичка, отказ.
	 */
	private void selfTest(List<Player> hunters)
	{
		hunters.sort((a, b) -> Integer.compare(a.getLevel(), b.getLevel()));
		final Player novice = hunters.get(0);
		Player mentor = null;
		for (int i = hunters.size() - 1; i > 0; i--)
		{
			final PhantomTemplate template = PhantomData.getInstance().getTemplate(hunters.get(i).getName());
			if ((template != null) && (template.getRole() == PhantomTemplate.Role.BUFFER) && (hunters.get(i).getLevel() >= 40))
			{
				mentor = hunters.get(i);
				break;
			}
		}
		if (mentor == null)
		{
			mentor = hunters.get(hunters.size() - 1);
		}
		final Player teacher = mentor;
		final PhantomTemplate template = PhantomData.getInstance().getTemplate(teacher.getName());

		_test = true;
		PhantomHuntersConfig.HUNTER_MENTORS = true;
		PhantomHuntersConfig.HUNTER_MENTORS_PARTY = true;
		PhantomHuntersConfig.HUNTER_MENTORS_CHANCE = 100;
		PhantomHuntersConfig.HUNTER_MENTORS_NOVICE_LEVEL = Math.max(PhantomHuntersConfig.HUNTER_MENTORS_NOVICE_LEVEL, novice.getLevel());
		PhantomHuntersConfig.HUNTER_MENTORS_MIN_LEVEL = Math.min(PhantomHuntersConfig.HUNTER_MENTORS_MIN_LEVEL, teacher.getLevel());
		PhantomNear.getInstance().testPlayer(novice);
		PhantomFavors.getInstance().freeze(novice);
		PhantomFavors.getInstance().freeze(teacher);
		novice.setTarget(null);
		if (novice.isSitting())
		{
			novice.standUp();
		}
		novice.getVariables().remove(REFUSAL_VARIABLE);
		PhantomInitiative.getInstance().forget(novice);
		PhantomInitiative.getInstance().forget(teacher);
		_next.remove(novice.getObjectId());

		// Своих банок и шотов грейда новичка у старшего обычно нет: для проверки передачи кладём ему запас.
		final int shotId = PhantomHunterOutfit.shotId(novice);
		if (count(teacher, potionId(teacher)) < (POTION_SPARE * 3))
		{
			teacher.addItem(ItemProcessType.REWARD, potionId(teacher), 60, null, false);
		}
		if ((shotId > 0) && (count(teacher, shotId) < (SHOT_SPARE * 3)))
		{
			teacher.addItem(ItemProcessType.REWARD, shotId, 900, null, false);
		}
		final long potionsBefore = count(novice, potionId(teacher));
		final long shotsBefore = count(novice, shotId);
		// На споте их бьют мобы: стойка боя не спадает, а «живой» может лечь. Урок — в Глудине, без мобов.
		for (Player player : List.of(novice, teacher))
		{
			if (player.isDead())
			{
				player.doRevive();
			}
			player.abortAttack();
			player.abortCast();
			player.getAI().setIntentionIdle();
		}
		final Location gludin = new Location(-80826, 149775, -3043);
		novice.teleToLocation(gludin, false);
		teacher.teleToLocation(new Location(gludin.getX() + 400, gludin.getY(), gludin.getZ()), false);
		line("novice " + novice.getName() + " (" + novice.getLevel() + "), mentor " + teacher.getName() + " (" + teacher.getLevel() + ", " + (template == null ? "?" : template.getRole()) + "), spot " + (spotFor(novice) == null ? "none" : place(spotFor(novice))) + ", brain " + PhantomChat.brainEnabled() + ", in Gludin, waiting for the combat stance to end.");

		final List<String> failed = new ArrayList<>();
		ThreadPool.schedule(() ->
		{
			final long now = System.currentTimeMillis();
			if (novice.isInParty())
			{
				novice.leaveParty();
			}
			if (teacher.isInParty())
			{
				teacher.leaveParty();
			}
			final boolean learn = canLearn(novice, now);
			final boolean teach = canTeach(teacher, novice, now);
			check(failed, "novice can learn, mentor can teach", learn && teach, "learn " + learn + ", teach " + teach + ", in combat " + novice.isInCombat() + "/" + teacher.isInCombat() + ", dead " + novice.isDead() + ", party " + novice.isInParty() + "/" + teacher.isInParty());
			_testAccept = true;
			begin(teacher, novice, now);
		}, 30000);
		ThreadPool.schedule(() ->
		{
			final Lesson lesson = _lessons.get(teacher.getObjectId());
			check(failed, "potions given", count(novice, potionId(teacher)) > potionsBefore, "before " + potionsBefore + ", now " + count(novice, potionId(teacher)));
			check(failed, "shots given", (shotId <= 0) || (count(novice, shotId) > shotsBefore), "shot " + shotId + ", before " + shotsBefore + ", now " + count(novice, shotId));
			if ((template != null) && (template.getRole() == PhantomTemplate.Role.BUFFER))
			{
				check(failed, "buffed", PhantomSupport.buffsFrom(teacher, novice) > 0, "buffs from the mentor " + PhantomSupport.buffsFrom(teacher, novice));
			}
			final boolean together = teacher.isInParty() && (teacher.getParty() == novice.getParty());
			check(failed, "in a party, the novice leads", together && teacher.getParty().isLeader(novice), "party " + together);
			check(failed, "mentor is his companion", (template != null) && PhantomCompanionManager.getInstance().isCompanion(template) && isMentor(teacher), "lesson " + (lesson == null ? "none" : lesson.stage));
			final List<Player> members = List.of(novice, teacher);
			final int level = PhantomPartyRules.partyLevel(members, teacher.getLevel());
			check(failed, "party level without the mentor", level == novice.getLevel(), "level " + level);
			check(failed, "exp share without the mentor", !PhantomPartyRules.expMembers(members).contains(teacher), "members " + PhantomPartyRules.expMembers(members).stream().map(Player::getName).toList());
			if (lesson != null)
			{
				lesson.until = System.currentTimeMillis();
			}
		}, 75000);
		ThreadPool.schedule(() ->
		{
			check(failed, "left after the time", !teacher.isInParty() && !isMentor(teacher) && !_lessons.containsKey(teacher.getObjectId()), "in party " + teacher.isInParty());
			if (novice.isInParty())
			{
				novice.leaveParty();
			}
			check(failed, "cooldown of the novice", !pass(novice, System.currentTimeMillis(), false), "a second lesson right away");
			// Отказ: «живой» молчит в окне «вступить в пати?».
			_testAccept = false;
			_next.remove(novice.getObjectId());
			PhantomInitiative.getInstance().forget(novice);
			PhantomInitiative.getInstance().forget(teacher);
			// После ухода из пати наставник вернулся к своим делам и мог улететь на спот: назад к «живому», снова держим.
			PhantomFavors.getInstance().freeze(teacher);
			if (teacher.isDead())
			{
				teacher.doRevive();
			}
			teacher.abortAttack();
			teacher.abortCast();
			teacher.getAI().setIntentionIdle();
			teacher.teleToLocation(new Location(novice.getX() + 100, novice.getY(), novice.getZ()), false);
			ThreadPool.schedule(() -> begin(teacher, novice, System.currentTimeMillis()), 4000);
		}, 81000);
		ThreadPool.schedule(() ->
		{
			check(failed, "refusal remembered", refused(novice, System.currentTimeMillis()) && !canLearn(novice, System.currentTimeMillis()), "refused " + refused(novice, System.currentTimeMillis()) + ", lesson " + _lessons.containsKey(teacher.getObjectId()));
			check(failed, "no party after the refusal", !teacher.isInParty(), "in party " + teacher.isInParty());
			novice.getVariables().remove(REFUSAL_VARIABLE);
			for (Lesson lesson : _lessons.values())
			{
				end(lesson, "self-test is over");
			}
			PhantomNear.getInstance().testPlayer(null);
			PhantomFavors.getInstance().unfreeze(novice);
			PhantomFavors.getInstance().unfreeze(teacher);
			_test = false;
			line(failed.isEmpty() ? "RESULT OK" : "RESULT FAILED: " + failed);
		}, 140000);
	}

	private static long count(Player player, int itemId)
	{
		final Item item = itemId <= 0 ? null : player.getInventory().getItemByItemId(itemId);
		return item == null ? 0 : item.getCount();
	}

	private static void check(List<String> failed, String name, boolean ok, String detail)
	{
		line((ok ? "OK " : "WRONG ") + name + " (" + detail + ")");
		if (!ok)
		{
			failed.add(name);
		}
	}

	public static PhantomMentors getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomMentors INSTANCE = new PhantomMentors();
	}
}
