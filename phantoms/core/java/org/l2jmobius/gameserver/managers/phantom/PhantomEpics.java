package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.GrandBossConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Npc;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.managers.GrandBossManager;
import org.l2jmobius.gameserver.mechanics.events.EventType;
import org.l2jmobius.gameserver.mechanics.events.holders.actor.creature.OnCreatureDamageReceived;
import org.l2jmobius.gameserver.mechanics.events.listeners.ConsumerEventListener;

/**
 * L2Hub: эпики малого мира и «измор» (docs/PHANTOMS.md, раздел 4.43).
 * <ul>
 * <li>«Эпики малого мира» ({@code SmallWorldEpics} в GrandBoss.ini, патч 0028) ослабляют самих эпиков из {@link #EPICS} — для всех, а не только для охотников:
 * любой удар по эпику (игрока, его слуги, охотника) снимает в {@code 1 / }{@value #HP_SHARE} раз больше — как будто HP у него {@value #HP_SHARE} от обычного
 * (так Mobius делает и HP чемпионов: делит урон); свиту — нянек и гвардию Королевы, рыцарей и призраков Ядра, шаблонных Raikel Leos Орфена — урезает
 * до {@link #KEEP}: лишние умирают сами по себе (без лута) и не встают. Выключен — эпики как у Mobius;</li>
 * <li>«измор»: после неудачного рейда ({@link #exhaust}) эпик не восстанавливает HP — ни регенерацией, ни лечением нянек — до конца срока
 * {@code HunterEpicExhaust}: несколько заходов подряд его изматывают.</li>
 * </ul>
 * Работает из {@code PhantomHunterManager.control} раз в 10 с — и при выключенных охотниках (HuntersMode OFF): малый мир — свойство мира, а не охотников.
 */
public final class PhantomEpics
{
	private static final Logger LOGGER = Logger.getLogger(PhantomEpics.class.getName());

	static final int QUEEN_ANT = 29001;
	static final int LARVA = 29002;
	static final int NURSE = 29003;
	static final int CORE = 29006;
	static final int ORFEN = 29014;
	static final int ZAKEN = 29022;
	/**
	 * Эпики без входа через NPC и предмет: Королева муравьёв, Ядро, Орфен, старый Закен (на Devil's Isle; у H5 и Epilogue — инстанс, его нет). Баюма будят тканью у статуи,
	 * к Антарасу и Валакасу пускают через NPC с камнем, Фринтеза и Белет — инстансы под командный канал: не здесь.
	 */
	static final int[] EPICS =
	{
		QUEEN_ANT,
		CORE,
		ORFEN,
		ZAKEN
	};
	/** Малый мир: «HP» эпика — эта доля от обычного (урон по нему — в {@code 1 / HP_SHARE} раз больше). */
	private static final double HP_SHARE = 0.4;
	/**
	 * Малый мир: сколько миньонов каждого вида остаётся у эпика {эпик, миньон, сколько оставить, только шаблонных}. Шаблонные — из {@code <minions>} эпика
	 * (их Mobius поднимает сам); у Ядра ещё 19 расставленных скриптом по залу — они в счёте; расставленных скриптом Орфена (4 Raikel Leos) скрипт
	 * таскает за ним телепортом — их не трогать, урезаются только шаблонные.
	 */
	private static final int[][] KEEP =
	{
		{QUEEN_ANT, NURSE, 2, 1}, // Nurse Ant: 6 → 2, лечат Королеву раз в секунду
		{QUEEN_ANT, 29005, 3, 1}, // Court Guard Ant: 8 → 3
		{CORE, 29007, 4, 0}, // Death Knight: 10 + 10 → 4
		{CORE, 29008, 2, 0}, // Doom Wraith: 3 + 5 → 2
		{CORE, 29011, 2, 0}, // Susceptor: 4 + 4 → 2
		{ORFEN, 29016, 0, 1}, // Raikel Leos: 4 шаблонных + 4 скриптовых → 4
	};
	/** Свита эпика — в этом радиусе от него (зал Ядра — около 2,5 тыс.). */
	private static final int RETINUE_RANGE = 3500;
	private static final long EXHAUST_TICK = 1000;

	/** На эпике: удар по нему снимает больше (малый мир). */
	private final Map<Integer, ConsumerEventListener> _weakened = new ConcurrentHashMap<>();
	/** Измор: objectId эпика → до какого времени и с каким HP. */
	private final Map<Integer, double[]> _exhausted = new ConcurrentHashMap<>();
	private ScheduledFuture<?> _exhaustTask;

	private PhantomEpics()
	{
	}

	/**
	 * Из {@code PhantomHunterManager.control} раз в 10 с: малый мир — ослабить живых эпиков (слушатель урона, урезать свиту).
	 */
	synchronized void control()
	{
		if (!GrandBossConfig.SMALL_WORLD_EPICS)
		{
			return;
		}
		for (Npc boss : alive())
		{
			if (!_weakened.containsKey(boss.getObjectId()))
			{
				final ConsumerEventListener listener = new ConsumerEventListener(boss, EventType.ON_CREATURE_DAMAGE_RECEIVED, (OnCreatureDamageReceived event) -> onDamaged(boss, event), this);
				boss.addListener(listener);
				_weakened.put(boss.getObjectId(), listener);
				LOGGER.info(getClass().getSimpleName() + ": small world — " + boss.getName() + " takes " + Math.round(100 / HP_SHARE) + "% damage.");
			}
			trim(boss);
		}
		// Эпик умер — его слушатель ушёл вместе с ним; новый (респаун) — другой объект.
		_weakened.keySet().removeIf(id -> !(World.findObject(id) instanceof GrandBoss) || ((GrandBoss) World.findObject(id)).isDead());
	}

	/**
	 * @return живые эпики из {@link #EPICS} (каких нет у хроники — {@code null} у менеджера, пропускаются)
	 */
	static List<Npc> alive()
	{
		final List<Npc> epics = new ArrayList<>();
		for (int id : EPICS)
		{
			final GrandBoss boss = GrandBossManager.getInstance().getBoss(id);
			if ((boss != null) && boss.isSpawned() && !boss.isDead())
			{
				epics.add(boss);
			}
		}
		return epics;
	}

	/**
	 * Удар по эпику — ещё урона, чтобы всего вышло в {@code 1 / HP_SHARE} раз больше. Добавка идёт через {@code reduceCurrentHp}: новый удар слушателя не будит.
	 */
	private static void onDamaged(Npc boss, OnCreatureDamageReceived event)
	{
		if ((event.getDamage() <= 0) || boss.isDead())
		{
			return;
		}
		boss.reduceCurrentHp(event.getDamage() * ((1 / HP_SHARE) - 1), event.getAttacker(), null);
	}

	/**
	 * Свита сверх {@link #KEEP}: лишние умирают сами по себе (убийца — он сам: без лута, скрипты босса о смерти не слышат) и не встают — шаблонный сперва отвязан
	 * от эпика без респауна. Лишними становятся свободные от боя, затем дальние.
	 */
	private static void trim(Npc boss)
	{
		final Monster master = boss instanceof Monster ? (Monster) boss : null;
		final List<Monster> template = (master != null) && master.hasMinions() ? new ArrayList<>(master.getMinionList().getSpawnedMinions()) : List.of();
		for (int[] rule : KEEP)
		{
			if (rule[0] != boss.getId())
			{
				continue;
			}
			final List<Monster> retinue = new ArrayList<>();
			for (Monster minion : template)
			{
				if ((minion != null) && (minion.getId() == rule[1]) && !minion.isDead())
				{
					retinue.add(minion);
				}
			}
			if (rule[3] == 0)
			{
				for (Monster other : World.getVisibleObjectsInRange(boss, Monster.class, RETINUE_RANGE))
				{
					if ((other.getId() == rule[1]) && !other.isDead() && !retinue.contains(other))
					{
						retinue.add(other);
					}
				}
			}
			if (retinue.size() <= rule[2])
			{
				continue;
			}
			// Ключ — один раз: «в бою» меняется на ходу, а сортировке нужен постоянный порядок.
			final Map<Monster, Double> order = new HashMap<>();
			for (Monster minion : retinue)
			{
				order.put(minion, (minion.isInCombat() ? 1e7 : 0) - minion.calculateDistance2D(boss));
			}
			retinue.sort(Comparator.comparingDouble(order::get));
			final int extra = retinue.size() - rule[2];
			for (Monster minion : retinue.subList(0, extra))
			{
				if (template.contains(minion))
				{
					master.getMinionList().onMinionDie(minion, 0);
				}
				minion.doDie(minion);
			}
			LOGGER.info(PhantomEpics.class.getSimpleName() + ": small world — " + boss.getName() + " loses " + extra + " " + retinue.get(0).getName() + " (" + rule[2] + " left).");
		}
	}

	/**
	 * «Измор» (раздел 4.43): рейд на эпика не удался — его HP не растёт столько минут (регенерация и лечение нянек срезаются раз в секунду).
	 * @param boss эпик
	 * @param minutes сколько
	 */
	synchronized void exhaust(Creature boss, int minutes)
	{
		if ((minutes <= 0) || boss.isDead())
		{
			return;
		}
		final long until = System.currentTimeMillis() + (minutes * 60000L);
		final double[] state = _exhausted.get(boss.getObjectId());
		if (state != null)
		{
			state[0] = Math.max(state[0], until);
		}
		else
		{
			_exhausted.put(boss.getObjectId(), new double[]
			{
				until,
				boss.getCurrentHp()
			});
		}
		LOGGER.info(getClass().getSimpleName() + ": " + boss.getName() + " is exhausted for " + minutes + " min at " + (int) ((boss.getCurrentHp() * 100) / Math.max(1, boss.getMaxHp())) + "% HP.");
		if ((_exhaustTask == null) || _exhaustTask.isDone())
		{
			_exhaustTask = ThreadPool.scheduleAtFixedRate(this::holdExhausted, EXHAUST_TICK, EXHAUST_TICK);
		}
	}

	/**
	 * @return эпик измотан — до какого времени (0 — нет)
	 */
	long exhaustedUntil(Creature boss)
	{
		final double[] state = _exhausted.get(boss.getObjectId());
		return state == null ? 0 : (long) state[0];
	}

	private void holdExhausted()
	{
		final long now = System.currentTimeMillis();
		for (Map.Entry<Integer, double[]> entry : _exhausted.entrySet())
		{
			final double[] state = entry.getValue();
			final Object object = World.findObject(entry.getKey());
			if (!(object instanceof Creature) || ((Creature) object).isDead() || (now >= state[0]))
			{
				_exhausted.remove(entry.getKey());
				if (object instanceof Creature)
				{
					LOGGER.info(getClass().getSimpleName() + ": " + ((Creature) object).getName() + " is no longer exhausted.");
				}
				continue;
			}
			final Creature boss = (Creature) object;
			if (boss.getCurrentHp() > state[1])
			{
				boss.setCurrentHp(state[1]);
			}
			else
			{
				state[1] = boss.getCurrentHp();
			}
		}
	}

	/**
	 * Рецепт Королевы: няньки лечат личинку (бессмертную, у входа в гнездо) раньше Королевы — пока личинку бьют, Королеву не лечит никто.
	 * Личинка в двух тысячах от гнезда — на краю видимых регионов Королевы, поэтому ищется и вокруг второго (охотника рядом).
	 * @param queen Королева
	 * @param also где ещё искать ({@code null} — только вокруг Королевы)
	 * @return живая личинка рядом или {@code null}
	 */
	static Npc larva(Creature queen, Creature also)
	{
		if (queen.getId() != QUEEN_ANT)
		{
			return null;
		}
		for (Creature from : new Creature[]
		{
			queen,
			also
		})
		{
			if (from == null)
			{
				continue;
			}
			for (Npc npc : World.getVisibleObjectsInRange(from, Npc.class, RETINUE_RANGE))
			{
				if ((npc.getId() == LARVA) && !npc.isDead())
				{
					return npc;
				}
			}
		}
		return null;
	}

	public static PhantomEpics getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomEpics INSTANCE = new PhantomEpics();
	}
}
