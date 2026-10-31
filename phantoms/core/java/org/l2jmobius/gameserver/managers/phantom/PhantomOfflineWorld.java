package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.L2HubWorldConfig;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ExperienceData;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: мир идёт при выключенном сервере (docs/PHANTOMS.md, раздел 4.73; docs/LIVE_SERVER.md, п. 11; ключ {@code HunterOfflineWorld}, по умолчанию выключен).
 * Боёв не считаем: правдоподобный итог. При старте сервера считаем простой с последней отметки «сервер жив» ({@value #ALIVE}, раз в {@value #STAMP} мс), копим его в {@value #DOWN_TOTAL}.
 * Охотник при входе в мир получает то, чего ещё не получил ({@value #PAID}): опыт и адену за долю простоя ({@code HunterOfflineWorldShare}, человек не играет сутками; у каждого своя).
 * Уровни, профессии и снаряжение дальше идут обычным ходом: мир сам увидит новый уровень. Летопись получает общий итог.
 * Отношения здесь не трогаем: не остывают по решению автора ({@link PhantomRelations}). Кланы тоже: их меняет мир на ходу.
 */
public final class PhantomOfflineWorld
{
	private static final Logger LOGGER = Logger.getLogger(PhantomOfflineWorld.class.getName());

	private static final String ALIVE = "L2HubWorldAlive";
	private static final String DOWN_TOTAL = "L2HubWorldDownMinutes";
	private static final String PAID = "L2HubWorldDownPaid";
	private static final long STAMP = 300000;
	/** Простой короче этого — просто перезапуск. */
	private static final long MIN_DOWN_MINUTES = 60;
	/** Итог в летопись: большинство охотников к этому времени уже вошли. */
	private static final long REPORT_DELAY = 900000;
	/** Кто вырос сильнее всех — в летопись по именам. */
	private static final int REPORT_NAMES = 3;

	private final List<Grown> _grown = new ArrayList<>();
	private long _downMinutes;

	private record Grown(String name, int from, int to)
	{
	}

	void start()
	{
		if (!PhantomHuntersConfig.HUNTER_OFFLINE_WORLD)
		{
			return;
		}

		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		final long now = System.currentTimeMillis();
		final long alive = variables.getLong(ALIVE, 0);
		if (alive > 0)
		{
			final long minutes = Math.min((now - alive) / 60000, PhantomHuntersConfig.HUNTER_OFFLINE_WORLD_MAX_DAYS * 1440L);
			if (minutes >= MIN_DOWN_MINUTES)
			{
				_downMinutes = minutes;
				variables.set(DOWN_TOTAL, variables.getLong(DOWN_TOTAL, 0) + minutes);
				LOGGER.info(getClass().getSimpleName() + ": server was down " + minutes + " min.");
				ThreadPool.schedule(this::report, REPORT_DELAY);
			}
		}
		variables.set(ALIVE, now);
		ThreadPool.scheduleAtFixedRate(() -> variables.set(ALIVE, System.currentTimeMillis()), STAMP, STAMP);
	}

	/**
	 * Охотник вошёл в мир ({@link PhantomManager}): выдать то, что накопилось, пока сервер был выключен.
	 * @param hunter охотник
	 */
	void onEnter(Player hunter)
	{
		if (!PhantomHuntersConfig.HUNTER_OFFLINE_WORLD || PhantomManager.getInstance().isSecret(hunter))
		{
			return;
		}

		final long total = GlobalVariablesManager.getInstance().getLong(DOWN_TOTAL, 0);
		final long paid = hunter.getVariables().getLong(PAID, -1);
		// Первая встреча с ключом: прошлое не оплачиваем.
		if (paid < 0)
		{
			hunter.getVariables().set(PAID, total);
			return;
		}
		if (paid >= total)
		{
			return;
		}

		hunter.getVariables().set(PAID, total);
		catchUp(hunter, total - paid);
	}

	/** Сколько часов охотник качает этот уровень: сотни часов до 70-го, как и живьём ({@code tools/fake_players.py}). */
	private static double hoursForLevel(int level)
	{
		final double share = Math.min(level, 85) / 76.0;
		return 0.25 + (16 * share * share * share);
	}

	private void catchUp(Player hunter, long minutes)
	{
		final int from = hunter.getLevel();
		// Доля простоя у каждого своя: 0,5–1,5 от ключа.
		double left = ((minutes / 60.0) * PhantomHuntersConfig.HUNTER_OFFLINE_WORLD_SHARE * (50 + Rnd.get(101))) / 10000.0;
		final double hours = left;
		final int cap = ExperienceData.getInstance().getMaxLevel() - 1;
		long exp = hunter.getExp();
		int level = from;
		while ((left > 0) && (level < cap))
		{
			final long base = ExperienceData.getInstance().getExpForLevel(level);
			final long next = ExperienceData.getInstance().getExpForLevel(level + 1);
			final double need = hoursForLevel(level);
			final double spend = (need * (next - Math.max(exp, base))) / (next - base);
			if (spend <= left)
			{
				left -= spend;
				exp = next;
				level++;
			}
			else
			{
				exp += (long) (((next - base) * left) / need);
				left = 0;
			}
		}

		if (exp > hunter.getExp())
		{
			hunter.addExpAndSp(exp - hunter.getExp(), 0);
		}
		final long adena = Math.round(hours * from * from * 20);
		if (adena > 0)
		{
			PhantomCompat.addItem(hunter, ItemProcessType.REWARD, Inventory.ADENA_ID, adena, null);
		}
		LOGGER.info(getClass().getSimpleName() + ": " + hunter.getName() + " played " + Math.round(hours * 10) / 10.0 + " h of " + (minutes / 60) + " h down: level " + from + " -> " + hunter.getLevel() + ", adena +" + adena + ".");
		synchronized (_grown)
		{
			_grown.add(new Grown(hunter.getName(), from, hunter.getLevel()));
		}
	}

	private void report()
	{
		final List<Grown> grown;
		synchronized (_grown)
		{
			grown = new ArrayList<>(_grown);
			_grown.clear();
		}

		final int levels = grown.stream().mapToInt(item -> item.to() - item.from()).sum();
		if (levels <= 0)
		{
			return;
		}

		grown.sort(Comparator.comparingInt((Grown item) -> item.from() - item.to()));
		final boolean en = "en".equals(L2HubWorldConfig.LANGUAGE);
		final List<String> names = new ArrayList<>();
		for (Grown item : grown.subList(0, Math.min(REPORT_NAMES, grown.size())))
		{
			if (item.to() > item.from())
			{
				names.add(item.name() + " " + item.from() + "→" + item.to());
			}
		}
		final long days = _downMinutes / 1440;
		final String time = days >= 1 ? days + (en ? " d" : " дн.") : (_downMinutes / 60) + (en ? " h" : " ч");
		PhantomNews.post("world_offline", (en ? "While the server was off (" + time + ") hunters gained levels: " : "Пока сервер был выключен (" + time + "), охотники набрали уровней: ") + levels + (en ? ", furthest: " : ", дальше всех: ") + String.join(", ", names), null, null);
	}

	public static PhantomOfflineWorld getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomOfflineWorld INSTANCE = new PhantomOfflineWorld();
	}
}
