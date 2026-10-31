package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.PlayerConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.data.xml.SkillData;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.managers.phantom.HuntSpotData.HuntSpot;
import org.l2jmobius.gameserver.mechanics.skill.BuffInfo;
import org.l2jmobius.gameserver.mechanics.skill.Skill;
import org.l2jmobius.gameserver.taskmanagers.AutoPlayTaskManager;

/**
 * L2Hub: просьбы встреченных (docs/PHANTOMS.md, раздел 4.78; {@code HunterFavors}).<br>
 * Охотник просит живого игрока рядом о том, чего ему правда не хватает, и только если этот игрок может помочь:
 * <ul>
 * <li>лежит мёртвый, у игрока есть Resurrection, — «реснёшь?»;</li>
 * <li>на споте почти без баффов, у игрока есть бафф на другого, — «дай бафф»;</li>
 * <li>копит на вещь ({@link PhantomMarket#buyOrder}) — «продай», сделка через {@link PhantomTrade};</li>
 * <li>в городе, телепорт платный, а денег на него нет, — «подкинь на гейт»: возить в игре некому, поэтому просит адену.</li>
 * </ul>
 * Две последние — только с {@code HunterTrade}: иначе охотник не примет обмен. Просьба — это инициатива: все фильтры и кулдауны общие с {@link PhantomInitiative}, в том числе «проигнорировал».
 * Слова пишут мозги; мозги недоступны — тишина, заготовок нет. Помог — запись в отношения ({@link PhantomRelations}), и он больше не считается промолчавшим.
 */
public final class PhantomFavors
{
	private static final Logger LOGGER = Logger.getLogger(PhantomFavors.class.getName());

	enum Favor
	{
		REVIVE,
		BUFF,
		SELL,
		GATE
	}

	private record Need(Favor favor, String what)
	{
	}

	/** Кого и о чём попросил, ждёт до. */
	private record Asked(int playerId, Favor favor, long until)
	{
	}

	/** Что считается помощью: дуэль и «качались вместе» — нет. */
	private static final Set<PhantomRelations.Kind> HELP = EnumSet.of(PhantomRelations.Kind.REVIVED, PhantomRelations.Kind.HEALED, PhantomRelations.Kind.SAVED, PhantomRelations.Kind.BUFFED, PhantomRelations.Kind.SOLD, PhantomRelations.Kind.GIFTED);
	/** Меньше стольких баффов — «почти без баффов». */
	private static final int BUFF_NEED = 2;
	/** Баффы на другого, которые ставят в цель, а не на пати: Wind Walk, Shield, Might, Haste, Empower. */
	private static final int[] BUFFER_SKILLS =
	{
		1204,
		1040,
		1068,
		1086,
		1059
	};
	/** Мёртвый смотрит, кто рядом, не чаще. */
	private static final long DEAD_PASS = 5000;
	/** Самопроверка: пауза шага, успевают каст, ответ мозгов и благодарность. */
	private static final long STEP_WAIT = 46000;
	/** Самопроверка: столько длится окно разговора в {@link PhantomChat}, с запасом. */
	private static final long CONVERSATION_WAIT = 125000;
	/** Пока не ответили, не повторять благодарность чаще. */
	private static final long THANKS_COOLDOWN = 60000;

	/** Охотник → последняя просьба, на которую ещё ждёт ответа. */
	private final Map<Integer, Asked> _asked = new ConcurrentHashMap<>();
	private final Map<Integer, Long> _deadNext = new ConcurrentHashMap<>();
	/** Самопроверка: кого держать на месте. */
	private final Set<Integer> _frozen = ConcurrentHashMap.newKeySet();

	private PhantomFavors()
	{
	}

	/**
	 * Живой игрок подошёл к охотнику, фильтры и кулдауны инициативы уже пройдены (проход {@link PhantomInitiative#look}): есть нужда, с которой он поможет, — бросок и просьба.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param player кто подошёл
	 * @param now текущее время
	 * @return {@code true} — охотник просит; обычной инициативы на этом проходе нет
	 */
	boolean ask(Player hunter, PhantomTemplate template, Player player, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_FAVORS || PhantomManager.getInstance().isSecret(hunter))
		{
			return false;
		}
		final Need need = need(hunter, template, player);
		if (need == null)
		{
			return false;
		}

		final double chance = PhantomHuntersConfig.HUNTER_FAVOR_CHANCE * template.getSocial() * PhantomRelations.getInstance().initiativeFactor(hunter, player);
		if (Rnd.get(100.0) >= chance)
		{
			LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " needs " + need.favor + " from " + player.getName() + ", chance " + Math.round(chance) + "% — no.");
			return false;
		}
		say(hunter, template, player, need, now);
		return true;
	}

	/**
	 * Мёртвый охотник лежит на месте (из тика {@link PhantomHunterManager}): рядом живой с ресом — просит. Подходить он не может, поэтому считается каждый, кто рядом, а не только подошедший.
	 * @param hunter охотник
	 * @param template его шаблон
	 * @param now текущее время
	 */
	void onDead(Player hunter, PhantomTemplate template, long now)
	{
		if (!PhantomHuntersConfig.HUNTER_FAVORS || !PhantomChat.brainEnabled() || PhantomManager.getInstance().isSecret(hunter) || (now < _deadNext.getOrDefault(hunter.getObjectId(), 0L)))
		{
			return;
		}
		_deadNext.put(hunter.getObjectId(), now + DEAD_PASS);
		if (!PhantomQuiet.getInstance().allowed(hunter, PhantomQuiet.INITIATIVE, now))
		{
			return;
		}

		for (Player player : World.getVisibleObjectsInRange(hunter, Player.class, PhantomHuntersConfig.HUNTER_INITIATIVE_RANGE))
		{
			if (PhantomNear.getInstance().isLive(player) && (player.getKnownSkill(PhantomNeighbors.RESURRECTION) != null) && PhantomInitiative.getInstance().mayAddress(hunter, player, now))
			{
				say(hunter, template, player, new Need(Favor.REVIVE, "ты лежишь мёртвый, а рядом стоит " + PhantomHunterManager.describe(player) + ", и у него есть рес. Попроси его реснуть тебя, коротко, как просят в игре"), now);
				return;
			}
		}
	}

	/**
	 * @param hunter охотник
	 * @param favor о чём
	 * @param now текущее время
	 * @return он только что попросил об этом и ждёт ответа: никуда не уходит
	 */
	boolean holds(Player hunter, Favor favor, long now)
	{
		final Asked asked = _asked.get(hunter.getObjectId());
		return (asked != null) && (asked.favor == favor) && (now < asked.until);
	}

	/**
	 * Игрок сделал охотнику доброе ({@link PhantomRelations#record}): ответ на инициативу, молчанием это не считается; если помог с тем, о чём просили, — поблагодарить.
	 * @param hunter охотник
	 * @param player игрок
	 * @param kind что сделал
	 */
	void onHelped(Player hunter, Player player, PhantomRelations.Kind kind)
	{
		if (!HELP.contains(kind))
		{
			return;
		}
		PhantomInitiative.getInstance().onHelped(hunter, player);
		final Asked asked = _asked.get(hunter.getObjectId());
		if ((asked == null) || (asked.playerId != player.getObjectId()))
		{
			return;
		}

		final Favor favor = favorOf(kind);
		if (favor != asked.favor)
		{
			return;
		}
		_asked.remove(hunter.getObjectId());
		LOGGER.info(getClass().getSimpleName() + ": " + player.getName() + " did what " + hunter.getName() + " asked: " + favor + ".");
		// Обмен сам говорит за себя: {@link PhantomTrade} уже просит мозги ответить.
		if ((favor == Favor.REVIVE) || (favor == Favor.BUFF))
		{
			final String deed = favor == Favor.REVIVE ? "реснул тебя" : "дал тебе бафф";
			PhantomChat.getInstance().hunterEvent(hunter, player, "FAVOR_DONE", "игрок " + player.getName() + " " + deed + ", как ты и просил. Поблагодари его коротко", null, THANKS_COOLDOWN);
		}
	}

	/**
	 * Обмен с охотником прошёл ({@link PhantomTrade}): продал то, о чём просили, — помог.
	 * @param hunter охотник
	 * @param player игрок
	 */
	void onSold(Player hunter, Player player)
	{
		if (!PhantomHuntersConfig.HUNTER_FAVORS)
		{
			return;
		}
		final Asked asked = _asked.get(hunter.getObjectId());
		if ((asked != null) && (asked.playerId == player.getObjectId()) && (asked.favor == Favor.SELL))
		{
			PhantomRelations.getInstance().record(hunter, player, PhantomRelations.Kind.SOLD);
		}
	}

	private static Favor favorOf(PhantomRelations.Kind kind)
	{
		return switch (kind)
		{
			case REVIVED -> Favor.REVIVE;
			case BUFFED -> Favor.BUFF;
			case SOLD -> Favor.SELL;
			case GIFTED -> Favor.GATE;
			default -> null;
		};
	}

	/**
	 * Чего охотнику правда не хватает и может ли этот игрок помочь: сначала деньги на гейт, потом бафф, потом вещь.
	 */
	private static Need need(Player hunter, PhantomTemplate template, Player player)
	{
		if (hunter.isInStoreMode() || hunter.isDead())
		{
			return null;
		}

		final boolean town = PhantomCompat.isInTown(hunter);
		if (PhantomHuntersConfig.HUNTER_TRADE && town && !PhantomHunterManager.teleportFree(hunter) && (hunter.getAdena() < PhantomHuntersConfig.HUNTER_TELEPORT_FEE))
		{
			final HuntSpot spot = PhantomHunterManager.getInstance().huntSpot(hunter);
			return new Need(Favor.GATE, "у тебя кончилась адена, на телепорт" + (spot != null ? " до " + place(spot) : "") + " не хватает, нужно хотя бы " + PhantomHuntersConfig.HUNTER_TELEPORT_FEE + ". Рядом стоит " + PhantomHunterManager.describe(player) + ". Попроси его подкинуть тебе на гейт, коротко, деньги он может передать через обмен");
		}
		if (!town && canBuff(player) && (buffs(hunter) < BUFF_NEED))
		{
			return new Need(Favor.BUFF, "ты качаешься почти без баффов, а рядом остановился " + PhantomHunterManager.describe(player) + ", и он умеет баффать. Попроси у него бафф, коротко, как просят в игре");
		}
		if (PhantomHuntersConfig.HUNTER_TRADE)
		{
			final PhantomMarket.Order order = PhantomMarket.buyOrder(hunter, template);
			if (order != null)
			{
				return new Need(Favor.SELL, "ты копишь на " + order.name() + " и готов дать за это " + order.price() + " адены. Рядом остановился " + PhantomHunterManager.describe(player) + ". Спроси, нет ли у него такого на продажу, коротко; сделка через обмен");
			}
		}
		return null;
	}

	/** Имя спота для реплики: «Talking Island Monsters · Talking Island Village» — только то, что после точки. */
	private static String place(HuntSpot spot)
	{
		final int dot = spot.name.lastIndexOf(" · ");
		return dot < 0 ? spot.name : spot.name.substring(dot + 3);
	}

	private static boolean canBuff(Player player)
	{
		for (int skillId : BUFFER_SKILLS)
		{
			if (player.getKnownSkill(skillId) != null)
			{
				return true;
			}
		}
		return false;
	}

	private static int buffs(Player player)
	{
		int count = 0;
		for (BuffInfo info : player.getEffectList().getEffects())
		{
			if (info.isInUse() && !info.getSkill().isDebuff())
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Просьба: кулдауны — сразу, как у обычной инициативы; ждать ответа — с того момента, как охотник правда сказал.
	 */
	private void say(Player hunter, PhantomTemplate template, Player player, Need need, long now)
	{
		final Runnable waiting = PhantomInitiative.getInstance().book(hunter, player, now);
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " asks " + player.getName() + ": " + need.favor + ".");
		PhantomChat.getInstance().favor(hunter, player, template, "favor", need.what, false, () ->
		{
			waiting.run();
			_asked.put(hunter.getObjectId(), new Asked(player.getObjectId(), need.favor, System.currentTimeMillis() + (PhantomHuntersConfig.HUNTER_INITIATIVE_ANSWER_WAIT * 1000L)));
		});
	}

	// ---------- Самопроверка ----------

	private void line(String text)
	{
		LOGGER.info(getClass().getSimpleName() + ": SelfTest FAVORS: " + text);
	}

	/**
	 * Самопроверка {@code HuntersSelfTest = FAVORS} на копии базы: один охотник играет живого игрока ({@link PhantomNear#testPlayer}), просьбы и письмо издалека идут настоящим путём; слова — от заглушки мозгов или никакие.
	 * Ключи включает сама: в ini они выключены.
	 * @param world охотники в мире
	 */
	void selfTest(List<Player> world)
	{
		final List<Player> hunters = new ArrayList<>();
		for (Player player : world)
		{
			if (!PhantomManager.getInstance().isSecret(player) && !player.isDead() && !player.isInParty() && (player.getClan() == null))
			{
				hunters.add(player);
			}
		}
		if (hunters.size() < 6)
		{
			line("failed: only " + hunters.size() + " free hunters.");
			return;
		}

		PhantomHuntersConfig.HUNTER_FAVORS = true;
		PhantomHuntersConfig.HUNTER_FAVOR_CHANCE = 1000;
		PhantomHuntersConfig.HUNTER_TRADE = true;
		PhantomHuntersConfig.HUNTER_FAR_WHISPER = true;
		PhantomHuntersConfig.HUNTER_FAR_WHISPER_CHANCE = 100;
		PhantomHuntersConfig.HUNTER_INITIATIVE_ANSWER_WAIT = 25;
		PhantomHuntersConfig.HUNTER_BUY_STORES = true;
		// Шаги идут рядом, а тема «initiative» по соседству молчит 300 с: без этого их реплики глохнут.
		PhantomHuntersConfig.HUNTER_CHAT_QUIET = 0;
		PhantomHuntersConfig.HUNTER_CHAT_SPEAKER_GAP = 0;
		PhantomHuntersConfig.HUNTER_CHAT_TOPIC_GAP = 0;
		PlayerConfig.MAX_FREE_TELEPORT_LEVEL = 0;
		Player found = null;
		for (Player player : hunters)
		{
			if ((PhantomHunterManager.getInstance().huntSpot(player) != null) && !PhantomCompat.isInTown(player) && ((found == null) || (player.getLevel() > found.getLevel())))
			{
				found = player;
			}
		}
		if (found == null)
		{
			line("failed: nobody on a spot.");
			return;
		}

		final Player live = found;
		hunters.remove(live);
		// Стоят все: иначе чужая пати и дорога уводят их от «живого».
		for (Player hunter : world)
		{
			_frozen.add(hunter.getObjectId());
		}
		PhantomNear.getInstance().testPlayer(live);
		_frozen.add(live.getObjectId());
		live.setTarget(null);
		// Сидя каст не идёт, а замороженный сам не встанет.
		if (live.isSitting())
		{
			live.standUp();
		}
		live.addSkill(SkillData.getInstance().getSkill(PhantomNeighbors.RESURRECTION, 1), false);
		final Skill windWalk = SkillData.getInstance().getSkill(1204, 1);
		live.addSkill(windWalk, false);
		clear(live);
		line("live " + live.getName() + " (" + live.getLevel() + ") at " + PhantomHunterManager.getInstance().huntSpot(live).name + ", hunters " + hunters.size() + ", brain " + PhantomChat.brainEnabled() + ", waiting for the combat stance to end.");
		ThreadPool.schedule(() -> revive(live, hunters, windWalk), 20000);
	}

	/** Самопроверка: держать на месте. */
	void freeze(Player player)
	{
		_frozen.add(player.getObjectId());
	}

	/** Самопроверка: отпустить. */
	void unfreeze(Player player)
	{
		_frozen.remove(player.getObjectId());
	}

	/**
	 * @param player охотник
	 * @return самопроверка держит его на месте: живой, но своих дел не делает
	 */
	boolean frozen(Player player)
	{
		return !_frozen.isEmpty() && _frozen.contains(player.getObjectId()) && !player.isDead();
	}

	/** Мобов вокруг «живого» — долой: в бою к нему не обращаются. Только копия базы. */
	static void clear(Player live)
	{
		for (Monster monster : World.getVisibleObjectsInRange(live, Monster.class, 1500))
		{
			monster.doDie(null);
		}
		live.setCurrentMp(live.getMaxMp());
	}

	/** В город: для просьб, которым не важно, кто рядом. */
	static void toTown(Player hunter)
	{
		PhantomCompat.setInstanceId(hunter, 0);
		hunter.teleToLocation(new Location(82698, 148638, -3473), false);
	}

	private static PhantomTemplate template(Player hunter)
	{
		return PhantomData.getInstance().getTemplate(hunter.getName());
	}

	/** Рядом с «живым»: телепорт к нему, без драки. */
	static void bring(Player hunter, Player live)
	{
		PhantomCompat.setInstanceId(hunter, 0);
		hunter.teleToLocation(new Location(live.getX() + 80, live.getY() + 40, live.getZ()), false);
	}

	/** Настоящий каст: цель, {@link PhantomCompat#useMagic}. */
	private void cast(Player caster, Player target, Skill skill)
	{
		AutoPlayTaskManager.getInstance().stopAutoPlay(caster);
		caster.setTarget(target);
		line(caster.getName() + " casts " + skill.getName() + " on " + target.getName() + ": " + PhantomCompat.useMagic(caster, skill, false, false) + ".");
	}

	/** Первый шаг: мёртвый просит реса, «живой» ресает настоящим кастом. */
	private void revive(Player live, List<Player> hunters, Skill windWalk)
	{
		final Player dead = hunters.get(0);
		bring(dead, live);
		ThreadPool.schedule(() ->
		{
			dead.doDie(null);
			line(dead.getName() + " lies dead next to " + live.getName() + ", waiting for the request.");
		}, 3000);
		ThreadPool.schedule(() ->
		{
			line("after 15 s: holds " + holds(dead, Favor.REVIVE, System.currentTimeMillis()) + ", dead " + dead.isDead() + ", mana " + Math.round(live.getCurrentMp()) + ".");
			cast(live, dead, SkillData.getInstance().getSkill(PhantomNeighbors.RESURRECTION, 1));
		}, 18000);
		ThreadPool.schedule(() ->
		{
			line(dead.getName() + " dead " + dead.isDead() + ", gratitude " + PhantomRelations.getInstance().gratitude(dead, live) + ", still asked " + _asked.containsKey(dead.getObjectId()) + ".");
			line("cooldown of the player: mayAddress " + PhantomInitiative.getInstance().mayAddress(hunters.get(1), live, System.currentTimeMillis()) + ".");
			clear(live);
			buff(live, hunters, windWalk);
		}, STEP_WAIT + 5000);
	}

	/** Второй шаг: почти без баффов просит «живого», тот баффает настоящим кастом. */
	private void buff(Player live, List<Player> hunters, Skill windWalk)
	{
		PhantomInitiative.getInstance().forget(live);
		final Player hunter = hunters.get(2);
		bring(hunter, live);
		ThreadPool.schedule(() ->
		{
			hunter.stopAllEffects();
			final long now = System.currentTimeMillis();
			PhantomInitiative.getInstance().forget(hunter);
			final boolean town = PhantomCompat.isInTown(hunter);
			final boolean asked = ask(hunter, template(hunter), live, now);
			line(hunter.getName() + " in town " + town + ", buffs " + buffs(hunter) + ", asked " + asked + ".");
		}, 3000);
		ThreadPool.schedule(() ->
		{
			cast(live, hunter, windWalk);
		}, 12000);
		ThreadPool.schedule(() ->
		{
			line(hunter.getName() + " buffs " + buffs(hunter) + ", gratitude " + PhantomRelations.getInstance().gratitude(hunter, live) + ", still asked " + _asked.containsKey(hunter.getObjectId()) + ".");
			sell(live, hunters);
		}, 22000);
	}

	/** Третий шаг: кто копит на вещь, просит продать; сама сделка — раздел 4.70, здесь только её последствие. */
	private void sell(Player live, List<Player> hunters)
	{
		PhantomInitiative.getInstance().forget(live);
		Player buyer = null;
		PhantomMarket.Order order = null;
		for (int i = hunters.size() - 2; i > 3; i--)
		{
			order = saving(hunters.get(i));
			if (order != null)
			{
				buyer = hunters.get(i);
				break;
			}
		}
		if (buyer == null)
		{
			line("nobody saves for an item: SELL not checked.");
			gate(live, hunters);
			return;
		}

		final Player hunter = buyer;
		final PhantomMarket.Order wanted = order;
		toTown(hunter);
		ThreadPool.schedule(() ->
		{
			PhantomInitiative.getInstance().forget(hunter);
			final boolean asked = ask(hunter, template(hunter), live, System.currentTimeMillis());
			line(hunter.getName() + " wants " + wanted.name() + " for " + wanted.price() + ", asked " + asked + ".");
		}, 4000);
		ThreadPool.schedule(() ->
		{
			onSold(hunter, live);
			line(hunter.getName() + " after the deal: gratitude " + PhantomRelations.getInstance().gratitude(hunter, live) + ", still asked " + _asked.containsKey(hunter.getObjectId()) + ".");
			gate(live, hunters);
		}, STEP_WAIT + 5000);
	}

	/**
	 * Самопроверка: охотник дорастает до D-грейда (ниже него нужды нет, стартовый шмот того же грейда не покупают) и получает кошелёк, с которого начинает копить. Копия базы.
	 * @return заказ или {@code null}: у него уже всё есть
	 */
	private static PhantomMarket.Order saving(Player hunter)
	{
		if (hunter.getLevel() < 22)
		{
			hunter.addExpAndSp(ExperienceData.getInstance().getExpForLevel(22) - hunter.getExp(), 0);
		}
		for (long wallet : new long[]
		{
			100000,
			300000,
			1000000,
			3000000
		})
		{
			if (hunter.getAdena() < wallet)
			{
				PhantomCompat.addAdena(hunter, ItemProcessType.REWARD, wallet - hunter.getAdena(), hunter, false);
			}
			final PhantomMarket.Order order = PhantomMarket.buyOrder(hunter, template(hunter));
			if (order != null)
			{
				return order;
			}
		}
		return null;
	}

	/** Четвёртый шаг: в городе без денег на телепорт; адену на копии базы забираем. */
	private void gate(Player live, List<Player> hunters)
	{
		PhantomInitiative.getInstance().forget(live);
		Player free = hunters.get(1);
		for (Player candidate : hunters.subList(1, hunters.size() - 1))
		{
			if (!candidate.isInParty())
			{
				free = candidate;
				break;
			}
		}
		final Player hunter = free;
		toTown(hunter);
		ThreadPool.schedule(() ->
		{
			PhantomInitiative.getInstance().forget(hunter);
			PhantomCompat.reduceAdena(hunter, ItemProcessType.FEE, hunter.getAdena(), hunter, false);
			final boolean asked = ask(hunter, template(hunter), live, System.currentTimeMillis());
			line(hunter.getName() + " in town " + PhantomCompat.isInTown(hunter) + ", adena " + hunter.getAdena() + ", asked " + asked + ".");
		}, 4000);
		ThreadPool.schedule(() ->
		{
			line(hunter.getName() + " holds " + holds(hunter, Favor.GATE, System.currentTimeMillis()) + ".");
			PhantomRelations.getInstance().record(hunter, live, PhantomRelations.Kind.GIFTED);
			line(hunter.getName() + " after the gift: gratitude " + PhantomRelations.getInstance().gratitude(hunter, live) + ", holds " + holds(hunter, Favor.GATE, System.currentTimeMillis()) + ".");
		}, 14000);
		ThreadPool.schedule(() -> ignore(live, hunters), STEP_WAIT + 5000);
	}

	/** Пятый шаг: на просьбу не ответили — пара «проигнорирована», как у обычной инициативы. */
	private void ignore(Player live, List<Player> hunters)
	{
		PhantomInitiative.getInstance().forget(live);
		clear(live);
		// Случайная пати срывала этот шаг: в пати он до прохода с пометкой не доходит.
		PhantomHuntersConfig.HUNTER_PARTIES = false;
		Player free = hunters.get(3);
		for (Player candidate : hunters.subList(3, hunters.size() - 1))
		{
			if (!candidate.isInParty())
			{
				free = candidate;
				break;
			}
		}
		final Player hunter = free;
		// Проход охотника снимает с него ожидание ответа: этот не стоит.
		_frozen.remove(hunter.getObjectId());
		bring(hunter, live);
		ThreadPool.schedule(() ->
		{
			hunter.stopAllEffects();
			PhantomInitiative.getInstance().forget(hunter);
			final boolean asked = ask(hunter, template(hunter), live, System.currentTimeMillis());
			line(hunter.getName() + " asks and nobody answers: asked " + asked + ".");
		}, 3000);
		ThreadPool.schedule(() ->
		{
			line(hunter.getName() + " ignored by " + live.getName() + ": " + PhantomInitiative.ignored(hunter, live.getObjectId()) + ", mayAddress " + PhantomInitiative.getInstance().mayAddress(hunter, live, System.currentTimeMillis()) + ".");
			line("waiting for the end of the conversation window: in conversation " + PhantomChat.getInstance().inConversation(live, System.currentTimeMillis()) + ".");
		}, 45000);
		// В разговоре к игроку не обращаются, в том числе издалека: ждём, пока окно разговора закроется.
		ThreadPool.schedule(() -> far(live, hunters, hunter), 45000 + CONVERSATION_WAIT);
	}

	/** Последний шаг: письмо издалека. «Живой» уже далеко от того, кто ему благодарен; кто проигнорирован — не пишет. */
	private void far(Player live, List<Player> hunters, Player ignoredHunter)
	{
		PhantomInitiative.getInstance().forget(live);
		final Player friend = hunters.get(0);
		final Player enemy = hunters.get(hunters.size() - 1);
		PhantomInitiative.getInstance().forget(friend);
		PhantomInitiative.getInstance().forget(enemy);
		PhantomRelations.getInstance().record(enemy, live, PhantomRelations.Kind.KILLED);
		PhantomRelations.getInstance().record(enemy, live, PhantomRelations.Kind.KILLED);
		// После реса он на грани: усталый первым не пишет.
		friend.setCurrentHp(friend.getMaxHp());
		friend.setCurrentMp(friend.getMaxMp());
		line("friend " + friend.getName() + " at " + Math.round(friend.calculateDistance2D(live)) + ", gratitude " + PhantomRelations.getInstance().gratitude(friend, live) + "; enemy " + enemy.getName() + " " + PhantomRelations.getInstance().isEnemy(enemy, live) + ", mayAddress " + PhantomInitiative.getInstance().mayAddress(enemy, live, System.currentTimeMillis()) + "; ignored " + ignoredHunter.getName() + ", mayAddress " + PhantomInitiative.getInstance().mayAddress(ignoredHunter, live, System.currentTimeMillis()) + ".");
		if (friend.calculateDistance2D(live) <= PhantomFarWhisper.FAR)
		{
			// Гиран или Глудин: что дальше от «живого».
			final Location giran = new Location(82698, 148638, -3473);
			final Location gludin = new Location(-80826, 149775, -3043);
			friend.teleToLocation(live.calculateDistance2D(giran) > live.calculateDistance2D(gludin) ? giran : gludin, false);
		}
		ThreadPool.schedule(() ->
		{
			final boolean wrote = PhantomFarWhisper.getInstance().pass(live, System.currentTimeMillis(), true);
			line("far pass: chosen " + wrote + ".");
		}, 3000);
		ThreadPool.schedule(() ->
		{
			line("second pass right after: chosen " + PhantomFarWhisper.getInstance().pass(live, System.currentTimeMillis(), false) + " (cooldown of the player).");
			// Друг уже на кулдауне: теперь пишет кто-то из знакомых по памяти мозгов.
			PhantomInitiative.getInstance().forget(live);
			line("third pass, the friend is on cooldown: chosen " + PhantomFarWhisper.getInstance().pass(live, System.currentTimeMillis(), true) + ".");
		}, 18000);
		ThreadPool.schedule(() ->
		{
			PhantomNear.getInstance().testPlayer(null);
			_frozen.clear();
			line("done");
		}, 40000);
	}

	public static PhantomFavors getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomFavors INSTANCE = new PhantomFavors();
	}
}
