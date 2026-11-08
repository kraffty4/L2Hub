package custom.L2HubProfQuestTest;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.managers.phantom.PhantomHunterManager;
import org.l2jmobius.gameserver.managers.phantom.PhantomOlympiad;
import org.l2jmobius.gameserver.managers.phantom.PhantomProfessionQuest;

/**
 * L2Hub: самопроверка первой профы квестом, только на копии базы: {@code HuntersSelfTest = L2HUBPROFQ} в PhantomHunters.ini (docs/PHANTOMS.md, раздел 4.152);
 * {@code L2HUBPROFQ:410,418} — только эти квесты, по охотнику на каждый (расы любые), зачёт — все. {@code L2HUBPROFQ2} и {@code L2HUBPROFQ2:217} — квесты 2-й профы:
 * охотника откатывают на 1-ю профу, знаки прежних квестов пути выдаются, зачёт — знак этого квеста.<br>
 * Берёт до пяти охотников разных рас, временно откатывает в базовый класс и ведёт квестом ({@link PhantomProfessionQuest}, ключ для них не нужен). В конце,
 * как бы ни кончилось, каждому возвращает прежний класс и базу; что было, лежит в переменной охотника, и если сервер встал посреди проверки, вернёт сам
 * квест при следующем входе. Итог — строки {@code L2HubProfQuestTest:} в логе.
 * @author L2Hub
 */
public class L2HubProfQuestTest
{
	private static final Logger LOGGER = Logger.getLogger(L2HubProfQuestTest.class.getName());
	private static final String TAG = "L2HubProfQuestTest: ";
	private static final long START_DELAY = 360000;
	private static final long LIMIT = 50 * 60000L;
	private static final long POLL = 30000;
	private static final int WANTED = 5;
	private static final int PASS = 3;
	/** Сначала квесты без вызванных квестом мобов и без спойла. */
	private static final int[] ORDER = { 401, 406, 410, 418, 412, 408, 404, 413, 414, 402, 405, 407, 409, 411, 415, 416, 417, 62, 63, 403 };
	/** 2-я профа: сначала испытания (Trial), первые квесты путей. */
	private static final int[] ORDER2 = { 211, 212, 213, 214, 215, 216, 217, 218, 219, 220, 221 };

	public static void main(String[] args)
	{
		if (PhantomHuntersConfig.HUNTERS_SELF_TEST.toUpperCase().startsWith("L2HUBPROFQ"))
		{
			ThreadPool.schedule(L2HubProfQuestTest::run, START_DELAY);
		}
	}

	private static PlayerClass atTier(PlayerClass c, int tier)
	{
		PlayerClass cls = c;
		while ((cls != null) && (cls.level() > tier))
		{
			cls = cls.getParent();
		}
		return (cls != null) && (cls.level() == tier) ? cls : null;
	}

	private static void run()
	{
		final Map<Integer, String> was = new LinkedHashMap<>(); // объект → класс до отката
		final Map<Integer, String> names = new LinkedHashMap<>();
		final Map<Integer, Integer> quests = new LinkedHashMap<>();
		int pass = PASS;
		try
		{
			final Map<Integer, int[]> byClass = PhantomProfessionQuest.pathsByClass();
			final String[] arg = PhantomHuntersConfig.HUNTERS_SELF_TEST.split(":", 2);
			final int tier = arg[0].strip().equalsIgnoreCase("L2HUBPROFQ2") ? 2 : 1;
			LOGGER.info(TAG + "start, tier " + tier + ", " + byClass.size() + " class paths in data, players online " + World.getPlayers().size() + ".");
			final Set<String> races = new HashSet<>();
			final int[] order = arg.length > 1 ? java.util.Arrays.stream(arg[1].split(",")).map(String::strip).mapToInt(Integer::parseInt).toArray() : (tier == 2 ? ORDER2 : ORDER);
			final int wanted = arg.length > 1 ? order.length : WANTED;
			pass = arg.length > 1 ? order.length : PASS;
			for (int questId : order)
			{
				for (Player p : World.getPlayers())
				{
					if (was.size() >= wanted)
					{
						break;
					}
					final PlayerClass cls = atTier(p.getPlayerClass(), tier);
					final int[] path = cls == null ? null : byClass.get(cls.getId());
					if ((path == null) || java.util.Arrays.stream(path).noneMatch(q -> q == questId) || ((arg.length == 1) && races.contains(p.getRace().name())))
					{
						continue;
					}
					if (!PhantomHunterManager.getInstance().isHunter(p) || p.isDead() || p.isInParty() || p.isInOlympiadMode() || PhantomOlympiad.getInstance().isBusy(p) || p.isSubClassActive() || (p.getLevel() < Math.max(20, PhantomProfessionQuest.questLevel(questId))))
					{
						continue;
					}
					final String before = PhantomProfessionQuest.selfTestBegin(p, tier, questId);
					if (before == null)
					{
						continue;
					}
					races.add(p.getRace().name());
					was.put(p.getObjectId(), before);
					names.put(p.getObjectId(), p.getName());
					quests.put(p.getObjectId(), questId);
					LOGGER.info(TAG + "begin " + p.getName() + " (" + p.getLevel() + ", " + p.getRace() + ") was " + before + ", now " + p.getPlayerClass().name() + ", Q" + questId + ".");
					if (arg.length > 1)
					{
						break; // список: по охотнику на квест, расы любые — массовый замер
					}
				}
			}
			if (was.size() < pass)
			{
				LOGGER.warning(TAG + "WRONG only " + was.size() + " hunters fit.");
			}

			final long end = System.currentTimeMillis() + LIMIT;
			final Map<Integer, String> status = new LinkedHashMap<>();
			while (System.currentTimeMillis() < end)
			{
				Thread.sleep(POLL);
				boolean running = false;
				for (int id : was.keySet())
				{
					final Player p = World.getPlayer(id);
					final String s = p == null ? "offline" : PhantomProfessionQuest.selfTestStatus(p, tier, quests.get(id));
					if (!s.equals(status.get(id)))
					{
						LOGGER.info(TAG + names.get(id) + " Q" + quests.get(id) + ": " + s + (p == null ? "" : " (" + p.getPlayerClass().name() + ", " + p.getX() + " " + p.getY() + ")") + ".");
						status.put(id, s);
					}
					running |= s.equals("run") || s.equals("offline");
				}
				if (!running)
				{
					break;
				}
			}

			int passed = 0;
			int restored = 0;
			final List<String> lines = new ArrayList<>();
			for (int id : was.keySet())
			{
				final Player p = World.getPlayer(id);
				final String s = status.getOrDefault(id, "run");
				if (s.equals("done"))
				{
					passed++;
				}
				if (p == null)
				{
					lines.add(names.get(id) + " Q" + quests.get(id) + " " + s + ", offline: class comes back on login");
					continue;
				}
				final String back = PhantomProfessionQuest.selfTestEnd(p);
				final boolean ok = back.equals(was.get(id));
				if (ok)
				{
					restored++;
				}
				lines.add(names.get(id) + " Q" + quests.get(id) + " " + s + ", back to " + back + (ok ? "" : " WRONG, was " + was.get(id)));
			}
			for (String line : lines)
			{
				LOGGER.info(TAG + line + ".");
			}
			final boolean good = (passed >= pass) && (restored == was.size());
			LOGGER.info(TAG + (good ? "done" : "WRONG") + " quests passed " + passed + " of " + was.size() + ", restored " + restored + " of " + was.size() + ".");
		}
		catch (Throwable e)
		{
			LOGGER.warning(TAG + "WRONG failed: " + e);
			e.printStackTrace();
			for (int id : was.keySet())
			{
				final Player p = World.getPlayer(id);
				if (p != null)
				{
					LOGGER.info(TAG + names.get(id) + " back to " + PhantomProfessionQuest.selfTestEnd(p) + ".");
				}
			}
		}
	}
}
