package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.l2jmobius.commons.threads.ThreadPool;
import org.l2jmobius.commons.util.Rnd;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.sql.ClanTable;
import org.l2jmobius.gameserver.entity.Location;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.clan.Clan;
import org.l2jmobius.gameserver.entity.clan.ClanMember;
import org.l2jmobius.gameserver.managers.GlobalVariablesManager;

/**
 * L2Hub: войны кланов охотников (docs/PHANTOMS.md, раздел 4.50, этапы 3 и 4; {@code HunterClanWars} и {@code HunterClanWarsPlayer} в PhantomHunters.ini).
 * <ul>
 * <li>объявление — раз в {@value #DECLARE_GAP} мс сверяются обиды между членами кланов охотников в мире, в обе стороны. Сумма не меньше
 * {@code HunterClanWarGrudge}, оба клана от {@code HunterClanWarLevel}-го уровня и ни с кем не воюют — глава обиженного клана (в мире) объявляет
 * войну ({@link #declare});</li>
 * <li>война с кланом игрока (этап 4) — так же, но обиды только охотников клана охотников на членов клана живого игрока в мире; у клана игрока одна
 * такая война за раз и сутки мира после неё ({@link #declarePlayer});</li>
 * <li>война — штатная война Mobius, объявленная сразу с обеих сторон ({@link PhantomCompat#startWar}): враги бьют друг друга без флага и кармы,
 * репутацию за убийства переводит Mobius;</li>
 * <li>драки — раз в минуту охотники враждующих кланов рядом лезут в драку, как в махаче, но повод — война ({@link PhantomBrawl#warProvoked}); на живого
 * врага — не больше {@code HunterClanWarPlayerAttackers} разом ({@link PhantomBrawl#warOnLiving});</li>
 * <li>перемирие — через {@code HunterClanWarHours} ч или после {@code HunterClanWarKills} убийств на двоих; та же пара кланов потом не воюет
 * {@value #TRUCE_HOURS} ч. Клан игрока может выйти из войны и сам — окном клана, по правилам Mobius.</li>
 * </ul>
 * Начало и конец со счётом — в «Летопись» (вид {@code clan_war}), война и счёт — в {@code .clans} и в окне «Охотники». Счёт и начало войны
 * переживают перезапуск: войну Mobius хранит в базе, счёт — глобальная переменная {@value #WAR_VARIABLE}.
 */
public final class PhantomClanWars
{
	private static final Logger LOGGER = Logger.getLogger(PhantomClanWars.class.getName());

	/** Обиды между кланами сверяются не чаще; первый раз — через столько после старта. */
	private static final long DECLARE_GAP = 600000;
	/** Охотники враждующих кланов ищут друг друга рядом не чаще. */
	private static final long FIGHT_GAP = 60000;
	/** «Рядом» — не дальше. */
	private static final int FIGHT_RANGE = 1500;
	/** После перемирия та же пара кланов не воюет столько часов; клан игрока после войны с охотниками — тоже. */
	private static final int TRUCE_HOURS = 24;
	/** Реплика о войне в клан-чат — не чаще. */
	private static final long SAY_GAP = 60000;
	/** Драка в самопроверке CLANWAR длится столько, потом — счёт и перемирие. */
	private static final long TEST_FIGHT = 45000;
	private static final String WAR_VARIABLE = "L2HubClanWar_";
	private static final String TRUCE_VARIABLE = "L2HubClanTruce_";
	/** Клан игрока: когда кончилась его последняя война с охотниками. */
	private static final String PLAYER_TRUCE_VARIABLE = "L2HubClanPlayerTruce_";
	private static final String[] SHOUTS =
	{
		"клан %1$s объявляет войну клану %2$s. встретим — ляжете",
		"%2$s, вы допрыгались. %1$s объявляет вам войну!",
		"война: %1$s против %2$s. сами напросились"
	};
	private static final String[] PLAYER_SHOUTS =
	{
		"%2$s, вы перешли черту. клан %1$s объявляет вам войну",
		"%1$s объявляет войну клану %2$s. за наших ответите",
		"%2$s, ждите гостей. %1$s идёт войной"
	};

	/** Война: кланы (меньший id, больший), их имена, когда началась, кто объявил, клан игрока (0 — оба кланы охотников), убийства каждой стороны. */
	static final class War
	{
		final int low;
		final int high;
		final String lowName;
		final String highName;
		final long start;
		final int declarer;
		final int playerClan;
		final AtomicInteger killsLow = new AtomicInteger();
		final AtomicInteger killsHigh = new AtomicInteger();

		War(Clan a, Clan b, long start, int declarer)
		{
			final Clan lowClan = a.getId() < b.getId() ? a : b;
			final Clan highClan = lowClan == a ? b : a;
			low = lowClan.getId();
			high = highClan.getId();
			lowName = lowClan.getName();
			highName = highClan.getName();
			this.start = start;
			this.declarer = declarer;
			playerClan = !PhantomHunterClans.isHunterClan(lowClan) ? low : !PhantomHunterClans.isHunterClan(highClan) ? high : 0;
		}

		int kills(int clanId)
		{
			return clanId == low ? killsLow.get() : killsHigh.get();
		}

		int enemy(int clanId)
		{
			return clanId == low ? high : low;
		}

		String name(int clanId)
		{
			return clanId == low ? lowName : highName;
		}

		int total()
		{
			return killsLow.get() + killsHigh.get();
		}
	}

	/** Повод для войны: обиженный клан, второй, обида первого на второй и обратно. */
	private record Candidate(Clan offended, Clan other, double grudge, double back)
	{
	}

	private final Map<Long, War> _wars = new ConcurrentHashMap<>();
	private long _nextDeclare;
	private long _nextFight;
	private boolean _restored;

	private PhantomClanWars()
	{
	}

	// ---------- Ход ----------

	/**
	 * Раз в 10 с из {@link PhantomHunterClans#control}: войны из базы после старта, перемирия, объявления, драки.
	 * @param hunters охотники в мире, живые
	 * @param now текущее время
	 */
	synchronized void control(List<Player> hunters, long now)
	{
		if (!_restored)
		{
			_restored = true;
			restore(now);
		}
		// Выключили — идущие войны этого вида кончаются перемирием.
		for (War war : new ArrayList<>(_wars.values()))
		{
			if (!PhantomHuntersConfig.HUNTER_CLANS || !enabled(war))
			{
				end(war, now, war.playerClan == 0 ? "войны кланов выключены" : "войны с кланами игроков выключены", "switched off");
			}
		}
		if (!PhantomHuntersConfig.HUNTER_CLANS || (!PhantomHuntersConfig.HUNTER_CLAN_WARS && !PhantomHuntersConfig.HUNTER_CLAN_WARS_PLAYER))
		{
			return;
		}
		endings(now);
		// Первый раз — не сразу после старта: охотники входят в мир по одному.
		if (_nextDeclare == 0)
		{
			_nextDeclare = now + DECLARE_GAP;
		}
		else if (now >= _nextDeclare)
		{
			_nextDeclare = now + DECLARE_GAP;
			if (PhantomHuntersConfig.HUNTER_CLAN_WARS)
			{
				declare(now);
			}
			if (PhantomHuntersConfig.HUNTER_CLAN_WARS_PLAYER)
			{
				declarePlayer(now);
			}
		}
		if (now >= _nextFight)
		{
			_nextFight = now + FIGHT_GAP;
			fights(hunters, now);
		}
	}

	private static boolean enabled(War war)
	{
		return war.playerClan == 0 ? PhantomHuntersConfig.HUNTER_CLAN_WARS : PhantomHuntersConfig.HUNTER_CLAN_WARS_PLAYER;
	}

	/**
	 * Войны кланов охотников, которые Mobius поднял из базы: счёт и начало — из переменной, её нет — война началась сейчас. Войны с кланами игроков —
	 * только наши, с переменной: свою войну игрок мог объявить и сам.
	 */
	private void restore(long now)
	{
		final List<Clan> clans = hunterClans();
		for (int i = 0; i < clans.size(); i++)
		{
			for (int j = i + 1; j < clans.size(); j++)
			{
				final Clan a = clans.get(i);
				final Clan b = clans.get(j);
				if (!PhantomCompat.atWar(a, b))
				{
					continue;
				}
				final War war = load(a, b, now);
				_wars.put(key(a.getId(), b.getId()), war);
				LOGGER.info(getClass().getSimpleName() + ": war of " + war.lowName + " and " + war.highName + " goes on, " + war.killsLow + ":" + war.killsHigh + ".");
			}
		}
		for (Clan hunterClan : clans)
		{
			for (Clan other : ClanTable.getInstance().getClans())
			{
				if (PhantomHunterClans.isHunterClan(other) || !PhantomCompat.atWar(hunterClan, other) || GlobalVariablesManager.getInstance().getString(variable(WAR_VARIABLE, hunterClan.getId(), other.getId()), "").isEmpty())
				{
					continue;
				}
				final War war = load(hunterClan, other, now);
				_wars.put(key(hunterClan.getId(), other.getId()), war);
				LOGGER.info(getClass().getSimpleName() + ": war of " + hunterClan.getName() + " and the player clan " + other.getName() + " goes on, " + war.killsLow + ":" + war.killsHigh + ".");
			}
		}
	}

	private static War load(Clan a, Clan b, long now)
	{
		final String stored = GlobalVariablesManager.getInstance().getString(variable(WAR_VARIABLE, a.getId(), b.getId()), "");
		final String[] parts = stored.split(";");
		if (parts.length == 4)
		{
			try
			{
				final War war = new War(a, b, Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
				war.killsLow.set(Integer.parseInt(parts[2]));
				war.killsHigh.set(Integer.parseInt(parts[3]));
				return war;
			}
			catch (NumberFormatException e)
			{
				// Кривая запись — война с начала.
			}
		}
		final War war = new War(a, b, now, Math.min(a.getId(), b.getId()));
		save(war);
		return war;
	}

	private static void save(War war)
	{
		GlobalVariablesManager.getInstance().set(variable(WAR_VARIABLE, war.low, war.high), war.start + ";" + war.declarer + ";" + war.killsLow + ";" + war.killsHigh);
	}

	// ---------- Объявление ----------

	/**
	 * Обиды охотников одного клана на членов другого в мире (живых тоже), в обе стороны. Сумма не меньше {@code HunterClanWarGrudge} — повод;
	 * из поводов берётся самый сильный, чей обиженный глава в мире. Не больше одной войны за проход и одной войны на клан.
	 */
	private void declare(long now)
	{
		final List<Clan> clans = new ArrayList<>();
		final Map<Integer, List<Player>> online = new HashMap<>();
		for (Clan clan : hunterClans())
		{
			if ((clan.getLevel() < PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL) || (war(clan) != null))
			{
				continue;
			}
			final List<Player> members = clan.getOnlineMembers(0);
			if (!members.isEmpty())
			{
				clans.add(clan);
				online.put(clan.getId(), members);
			}
		}

		final List<Candidate> candidates = new ArrayList<>();
		for (int i = 0; i < clans.size(); i++)
		{
			for (int j = i + 1; j < clans.size(); j++)
			{
				final Clan a = clans.get(i);
				final Clan b = clans.get(j);
				if (now < truceUntil(a, b))
				{
					continue;
				}
				final double ab = grudges(online.get(a.getId()), online.get(b.getId()));
				final double ba = grudges(online.get(b.getId()), online.get(a.getId()));
				if ((ab + ba) >= PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE)
				{
					candidates.add(ab >= ba ? new Candidate(a, b, ab, ba) : new Candidate(b, a, ba, ab));
				}
			}
		}
		candidates.sort(Comparator.comparingDouble((Candidate c) -> -(c.grudge() + c.back())));
		for (Candidate candidate : candidates)
		{
			final Player leader = hunterLeader(candidate.offended());
			if (leader != null)
			{
				start(candidate, leader, now);
				return;
			}
		}
	}

	/**
	 * Война с кланом игрока (этап 4): обиды охотников клана охотников на членов клана живого игрока в мире — на живых и на охотников в нём, только в
	 * одну сторону. Не меньше {@code HunterClanWarGrudge} — глава клана охотников (в мире) объявляет войну. Оба клана от {@code HunterClanWarLevel}-го
	 * уровня и ни с кем не воюют, у клана игрока прошли сутки мира после прошлой войны с охотниками. Не больше одной войны за проход.
	 */
	private void declarePlayer(long now)
	{
		final List<Clan> hunterClans = new ArrayList<>();
		final List<Clan> playerClans = new ArrayList<>();
		final Map<Integer, List<Player>> online = new HashMap<>();
		final int test = PhantomHunterClans.getInstance().testClanId();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if ((clan.getId() == test) || (clan.getLevel() < PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL) || (war(clan) != null))
			{
				continue;
			}
			final boolean hunterClan = PhantomHunterClans.isHunterClan(clan);
			if (hunterClan ? (hunterLeader(clan) == null) : (!PhantomClans.getInstance().livingOnline(clan) || (now < playerTruceUntil(clan))))
			{
				continue;
			}
			(hunterClan ? hunterClans : playerClans).add(clan);
			online.put(clan.getId(), clan.getOnlineMembers(0));
		}

		Candidate best = null;
		for (Clan hunterClan : hunterClans)
		{
			for (Clan playerClan : playerClans)
			{
				if (now < truceUntil(hunterClan, playerClan))
				{
					continue;
				}
				final double grudge = grudges(online.get(hunterClan.getId()), online.get(playerClan.getId()));
				if ((grudge >= PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE) && ((best == null) || (grudge > best.grudge())))
				{
					best = new Candidate(hunterClan, playerClan, grudge, 0);
				}
			}
		}
		if (best != null)
		{
			start(best, hunterLeader(best.offended()), now);
		}
	}

	/**
	 * @return сумма обид охотников из {@code from} на всех из {@code to}
	 */
	private static double grudges(List<Player> from, List<Player> to)
	{
		final PhantomRelations relations = PhantomRelations.getInstance();
		double sum = 0;
		for (Player hunter : from)
		{
			if (!PhantomRelations.isHunter(hunter))
			{
				continue;
			}
			for (Player other : to)
			{
				sum += relations.grudge(hunter, other);
			}
		}
		return sum;
	}

	/**
	 * Война: штатная, с обеих сторон. В летопись; глава кричит в торговый чат, если в игре живые; в клан-чат обоих кланов — если там есть живые
	 * (за клан игрока говорит охотник из него).
	 * @return война или {@code null}, если Mobius её не записал
	 */
	private War start(Candidate candidate, Player leader, long now)
	{
		final Clan offended = candidate.offended();
		final Clan other = candidate.other();
		final boolean player = !PhantomHunterClans.isHunterClan(other);
		PhantomCompat.startWar(offended, other);
		if (!PhantomCompat.atWar(offended, other))
		{
			LOGGER.warning(getClass().getSimpleName() + ": Mobius did not start the war of " + offended.getName() + " and " + other.getName() + ".");
			return null;
		}
		final War war = new War(offended, other, now, offended.getId());
		_wars.put(key(offended.getId(), other.getId()), war);
		save(war);
		LOGGER.info(getClass().getSimpleName() + ": clan " + offended.getName() + " (" + leader.getName() + ") declares war on " + (player ? "the player clan " : "") + other.getName() + (player ? String.format(Locale.ROOT, " — grudges %.1f (needs %d).", candidate.grudge(), PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE) : String.format(Locale.ROOT, " — grudges %.1f, back %.1f (needs %d together).", candidate.grudge(), candidate.back(), PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE)));
		PhantomNews.post("clan_war", "Клан «" + offended.getName() + "» объявил войну " + (player ? "клану игрока «" : "клану «") + other.getName() + "»", leader.getName(), leader);
		if (PhantomHunterManager.getInstance().getRealPlayers() > 0)
		{
			final String[] shouts = player ? PLAYER_SHOUTS : SHOUTS;
			PhantomChat.getInstance().hunterShout(leader, String.format(shouts[Rnd.get(shouts.length)], offended.getName(), other.getName()));
		}
		say(offended, leader, "ты глава клана «" + offended.getName() + "» и объявил войну клану «" + other.getName() + "»: их люди достали твоих. Скажи своим в клан-чат", "война с " + other.getName() + ". видите их — бейте");
		final Player voice = voice(other);
		if (voice != null)
		{
			say(other, voice, "клан «" + offended.getName() + "» объявил войну твоему клану «" + other.getName() + "»" + (player ? "; скажи своим в клан-чат" : "; ты глава — скажи своим в клан-чат"), player ? offended.getName() + " объявили нам войну. держимся вместе" : offended.getName() + " объявили нам войну. не прощаем");
		}
		return war;
	}

	// ---------- Драки ----------

	/**
	 * Охотники враждующих кланов в мире рядом друг с другом (не дальше {@value #FIGHT_RANGE}): один лезет в драку ({@link PhantomBrawl#warProvoked}).
	 * В войне с кланом игрока охотники ещё нападают на его живых ({@link #livingFights}). Каждый — не больше одной драки за проход.
	 */
	private void fights(List<Player> hunters, long now)
	{
		if (_wars.isEmpty())
		{
			return;
		}
		final Set<Player> busy = new HashSet<>();
		for (War war : _wars.values())
		{
			if (war.playerClan != 0)
			{
				livingFights(war, hunters, busy, now, false);
			}
			final List<Player> lows = new ArrayList<>();
			final List<Player> highs = new ArrayList<>();
			for (Player hunter : hunters)
			{
				// Охотник, который в самопроверке играет живого, — живой.
				if (PhantomClans.getInstance().isTestPlayer(hunter))
				{
					continue;
				}
				if (hunter.getClanId() == war.low)
				{
					lows.add(hunter);
				}
				else if (hunter.getClanId() == war.high)
				{
					highs.add(hunter);
				}
			}
			if (lows.isEmpty() || highs.isEmpty())
			{
				continue;
			}
			Collections.shuffle(lows);
			for (Player low : lows)
			{
				for (Player high : highs)
				{
					if (busy.contains(low) || busy.contains(high) || (low.getInstanceId() != high.getInstanceId()) || (low.calculateDistance2D(high) > FIGHT_RANGE))
					{
						continue;
					}
					final boolean lowFirst = Rnd.nextBoolean();
					if (PhantomBrawl.getInstance().warProvoked(lowFirst ? low : high, lowFirst ? high : low, lowFirst ? war.highName : war.lowName, now))
					{
						busy.add(low);
						busy.add(high);
						break;
					}
				}
			}
		}
	}

	/**
	 * Война с кланом игрока (этап 4): охотники клана охотников рядом с живыми из клана игрока нападают на них ({@link PhantomBrawl#warOnLiving}),
	 * на одного — не больше {@code HunterClanWarPlayerAttackers} разом, считая тех, кто уже с ним дерётся.
	 * @param sure без броска (самопроверка)
	 * @return сколько охотников напало
	 */
	private int livingFights(War war, List<Player> hunters, Set<Player> busy, long now, boolean sure)
	{
		final Clan playerClan = ClanTable.getInstance().getClan(war.playerClan);
		if (playerClan == null)
		{
			return 0;
		}
		final int hunterClan = war.enemy(war.playerClan);
		final List<Player> attackers = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if ((hunter.getClanId() == hunterClan) && !busy.contains(hunter))
			{
				attackers.add(hunter);
			}
		}
		if (attackers.isEmpty())
		{
			return 0;
		}
		Collections.shuffle(attackers);
		int started = 0;
		for (Player target : playerClan.getOnlineMembers(0))
		{
			if (!PhantomClans.getInstance().living(target) || target.isDead())
			{
				continue;
			}
			int count = PhantomHunterManager.getInstance().attackers(target);
			for (Player hunter : attackers)
			{
				if (count >= PhantomHuntersConfig.HUNTER_CLAN_WAR_PLAYER_ATTACKERS)
				{
					break;
				}
				if (busy.contains(hunter) || (hunter.getInstanceId() != target.getInstanceId()) || (hunter.calculateDistance2D(target) > FIGHT_RANGE))
				{
					continue;
				}
				if (PhantomBrawl.getInstance().warOnLiving(hunter, target, playerClan.getName(), now, sure))
				{
					busy.add(hunter);
					count++;
					started++;
				}
			}
		}
		return started;
	}

	/**
	 * Убийство на войне (из {@code PhantomHunterManager}: охотника убили; охотник убил живого врага): счёт стороне убийцы.
	 * @param victim убитый
	 * @param killer убийца
	 */
	public void onKill(Player victim, Player killer)
	{
		if ((victim == null) || (killer == null) || (victim.getClan() == null) || (killer.getClan() == null) || (victim.getClanId() == killer.getClanId()))
		{
			return;
		}
		final War war = _wars.get(key(victim.getClanId(), killer.getClanId()));
		if (war == null)
		{
			return;
		}
		(killer.getClanId() == war.low ? war.killsLow : war.killsHigh).incrementAndGet();
		save(war);
		LOGGER.info(getClass().getSimpleName() + ": " + killer.getName() + " (" + war.name(killer.getClanId()) + ") killed " + victim.getName() + " (" + war.name(victim.getClanId()) + "): " + war.lowName + " " + war.killsLow + ":" + war.killsHigh + " " + war.highName + ".");
	}

	/**
	 * @return двое из кланов, которые воюют (для махача: бить врага по войне можно и без флага)
	 */
	public boolean atWar(Player a, Player b)
	{
		return (a != null) && (b != null) && (a.getClan() != null) && (b.getClan() != null) && (a.getClanId() != b.getClanId()) && _wars.containsKey(key(a.getClanId(), b.getClanId()));
	}

	// ---------- Перемирие ----------

	/**
	 * Война кончается после {@code HunterClanWarKills} убийств на двоих или через {@code HunterClanWarHours} ч. Клан распался или Mobius снял войну
	 * сам — война снимается без перемирия; клан игрока вышел из войны сам (окно клана) — перемирие.
	 */
	private void endings(long now)
	{
		for (War war : new ArrayList<>(_wars.values()))
		{
			final Clan low = ClanTable.getInstance().getClan(war.low);
			final Clan high = ClanTable.getInstance().getClan(war.high);
			if ((low == null) || (high == null))
			{
				drop(war, low, high);
				continue;
			}
			if (!PhantomCompat.atWar(low, high))
			{
				// «Прекратить войну» или «сдаться» в окне клана: у CT Mobius снимает войну только со стороны игрока — вторую снимет end.
				if (war.playerClan != 0)
				{
					end(war, now, "клан «" + war.name(war.playerClan) + "» вышел из войны", "left by the player clan");
				}
				else
				{
					drop(war, low, high);
				}
				continue;
			}
			if (war.total() >= PhantomHuntersConfig.HUNTER_CLAN_WAR_KILLS)
			{
				end(war, now, null, "kills");
			}
			else if ((now - war.start) >= (PhantomHuntersConfig.HUNTER_CLAN_WAR_HOURS * 3600000L))
			{
				end(war, now, null, "time");
			}
		}
	}

	/**
	 * Перемирие: войну снять, счёт — в летопись, пара кланов не воюет {@value #TRUCE_HOURS} ч; клан игрока — ни с одним кланом охотников.
	 * @param reason почему, для летописи ({@code null} — счёт решил или время вышло)
	 * @param why для лога
	 */
	private void end(War war, long now, String reason, String why)
	{
		_wars.remove(key(war.low, war.high));
		final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
		variables.remove(variable(WAR_VARIABLE, war.low, war.high));
		variables.set(variable(TRUCE_VARIABLE, war.low, war.high), now);
		if (war.playerClan != 0)
		{
			variables.set(PLAYER_TRUCE_VARIABLE + war.playerClan, now);
		}
		final Clan low = ClanTable.getInstance().getClan(war.low);
		final Clan high = ClanTable.getInstance().getClan(war.high);
		if ((low != null) && (high != null) && (PhantomCompat.atWar(low, high) || (war.playerClan != 0)))
		{
			PhantomCompat.endWar(low, high);
		}

		final int killsLow = war.killsLow.get();
		final int killsHigh = war.killsHigh.get();
		final String result = killsLow == killsHigh ? "ничья, " + killsLow + ":" + killsHigh : "победил «" + (killsLow > killsHigh ? war.lowName : war.highName) + "», " + Math.max(killsLow, killsHigh) + ":" + Math.min(killsLow, killsHigh);
		LOGGER.info(getClass().getSimpleName() + ": war of " + war.lowName + " and " + war.highName + " is over (" + why + "): " + killsLow + ":" + killsHigh + ".");
		final String title = war.playerClan == 0 ? "Война кланов «" + war.lowName + "» и «" + war.highName + "»" : "Война клана «" + war.name(war.enemy(war.playerClan)) + "» с кланом игрока «" + war.name(war.playerClan) + "»";
		PhantomNews.post("clan_war", title + " окончена: " + result + (reason != null ? " (" + reason + ")" : ""), null, null);
		if ((low != null) && (high != null))
		{
			for (Clan clan : List.of(low, high))
			{
				final Player voice = voice(clan);
				if (voice == null)
				{
					continue;
				}
				final int own = war.kills(clan.getId());
				final int theirs = war.kills(war.enemy(clan.getId()));
				final String outcome = own > theirs ? "вы победили" : own < theirs ? "вы проиграли" : "ничья";
				say(clan, voice, "война твоего клана «" + clan.getName() + "» с кланом «" + war.name(war.enemy(clan.getId())) + "» кончилась перемирием: " + outcome + ", " + own + ":" + theirs + ". Скажи своим в клан-чат", own >= theirs ? "мир с " + war.name(war.enemy(clan.getId())) + ". " + own + ":" + theirs + ", неплохо" : "перемирие. " + own + ":" + theirs + "... в следующий раз отыграемся");
			}
		}
	}

	/**
	 * Войны больше нет не по перемирию (клан распался, глава стал живым, Mobius снял): счёт — в лог, переменная — прочь.
	 */
	private void drop(War war, Clan low, Clan high)
	{
		_wars.remove(key(war.low, war.high));
		GlobalVariablesManager.getInstance().remove(variable(WAR_VARIABLE, war.low, war.high));
		if ((low != null) && (high != null) && PhantomCompat.atWar(low, high))
		{
			PhantomCompat.endWar(low, high);
		}
		LOGGER.info(getClass().getSimpleName() + ": war of " + war.lowName + " and " + war.highName + " dropped (" + (low == null ? war.lowName + " is gone" : high == null ? war.highName + " is gone" : "not at war in Mobius any more") + "), " + war.killsLow + ":" + war.killsHigh + ".");
		if ((low == null) != (high == null))
		{
			PhantomNews.post("clan_war", "Война кланов «" + war.lowName + "» и «" + war.highName + "» окончена: клана «" + (low == null ? war.lowName : war.highName) + "» больше нет", null, null);
		}
	}

	private static long truceUntil(Clan a, Clan b)
	{
		final long since = GlobalVariablesManager.getInstance().getLong(variable(TRUCE_VARIABLE, a.getId(), b.getId()), 0);
		return since == 0 ? 0 : since + (TRUCE_HOURS * 3600000L);
	}

	/**
	 * @return до каких пор клан игрока не воюет с охотниками: сутки после прошлой такой войны
	 */
	private static long playerTruceUntil(Clan clan)
	{
		final long since = GlobalVariablesManager.getInstance().getLong(PLAYER_TRUCE_VARIABLE + clan.getId(), 0);
		return since == 0 ? 0 : since + (TRUCE_HOURS * 3600000L);
	}

	// ---------- Видимость ----------

	/**
	 * @return война клана: {имя врага, счёт «наши:их», «1» — враг клан живого игрока, иначе пусто} или {@code null}
	 */
	String[] war(Clan clan)
	{
		if (clan == null)
		{
			return null;
		}
		for (War war : _wars.values())
		{
			if ((war.low == clan.getId()) || (war.high == clan.getId()))
			{
				return new String[]
				{
					war.name(war.enemy(clan.getId())),
					war.kills(clan.getId()) + ":" + war.kills(war.enemy(clan.getId())),
					war.playerClan == war.enemy(clan.getId()) ? "1" : ""
				};
			}
		}
		return null;
	}

	// ---------- Общее ----------

	private static List<Clan> hunterClans()
	{
		final List<Clan> clans = new ArrayList<>();
		final int test = PhantomHunterClans.getInstance().testClanId();
		for (Clan clan : ClanTable.getInstance().getClans())
		{
			if (PhantomHunterClans.isHunterClan(clan) && (clan.getId() != test))
			{
				clans.add(clan);
			}
		}
		return clans;
	}

	/**
	 * @return глава клана — охотник в мире, или {@code null}
	 */
	private static Player hunterLeader(Clan clan)
	{
		final ClanMember head = clan.getLeader();
		final Player leader = head == null ? null : head.getPlayer();
		return (leader != null) && leader.isOnline() && PhantomRelations.isHunter(leader) ? leader : null;
	}

	/**
	 * @return кто говорит о войне за клан: у клана охотников — глава в мире, у клана игрока — охотник из него в мире; или {@code null}
	 */
	private static Player voice(Clan clan)
	{
		if (PhantomHunterClans.isHunterClan(clan))
		{
			return hunterLeader(clan);
		}
		for (Player member : clan.getOnlineMembers(0))
		{
			if (PhantomRelations.isHunter(member) && !PhantomClans.getInstance().isTestPlayer(member))
			{
				return member;
			}
		}
		return null;
	}

	/**
	 * Реплика охотника о войне в клан-чат — только если в клане есть живой в игре: клан-чат видят только живые.
	 */
	private static void say(Clan clan, Player hunter, String what, String fallback)
	{
		for (ClanMember member : clan.getMembers())
		{
			final Player player = member.getPlayer();
			if (PhantomClans.getInstance().living(player))
			{
				PhantomChat.getInstance().clanEvent(hunter, player, clan, "CLAN_WAR", what, fallback, SAY_GAP);
				return;
			}
		}
	}

	private static long key(int a, int b)
	{
		final long low = Math.min(a, b);
		final long high = Math.max(a, b);
		return (low << 32) | (high & 0xffffffffL);
	}

	private static String variable(String prefix, int a, int b)
	{
		return prefix + Math.min(a, b) + "_" + Math.max(a, b);
	}

	// ---------- Самопроверка ----------

	/**
	 * Самопроверка CLANWAR ({@code HuntersSelfTest}, только на копии базы: кланы, войны, отношения и штрафы ложатся в базу). Два клана по двое
	 * из свободных охотников, 3-й уровень; обиды до порога — война объявлена с обеих сторон, враг атакуем без флага; драка — сближение и нападение,
	 * через {@value #TEST_FIGHT} мс — кто как; счёт до {@code HunterClanWarKills} — перемирие, повторной войны нет; кланы распускаются.
	 * @param hunters охотники в мире
	 */
	void selfTest(List<Player> hunters)
	{
		final String tag = getClass().getSimpleName() + ": SelfTest CLANWAR: ";
		final long now = System.currentTimeMillis();
		final PhantomHunterClans clans = PhantomHunterClans.getInstance();
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (PhantomHunterClans.canFound(hunter, now) && !clans.isLoner(hunter) && !hunter.isDead() && PhantomHunterManager.getInstance().isHunting(hunter))
			{
				free.add(hunter);
			}
		}
		// Бойцы — двое на каче с разницей уровней не больше HunterBrawlLevelGap (иначе махач не начнётся); к каждому — ещё по одному.
		Player fighterA = null;
		Player fighterB = null;
		for (int i = 0; (i < free.size()) && (fighterA == null); i++)
		{
			for (int j = i + 1; j < free.size(); j++)
			{
				if (Math.abs(free.get(i).getLevel() - free.get(j).getLevel()) <= PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP)
				{
					fighterA = free.get(i);
					fighterB = free.get(j);
					break;
				}
			}
		}
		if ((fighterA == null) || (free.size() < 4))
		{
			LOGGER.warning(tag + "needs four free hunters of level 10+ on a spot, two of them within " + PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP + " levels; found " + free.size() + ".");
			return;
		}
		free.remove(fighterA);
		free.remove(fighterB);
		final Player mateA = free.get(0);
		final Player mateB = free.get(1);
		final Clan clanA = clans.found(fighterA, PhantomHunterClans.fallbackName(), "self-test");
		final Clan clanB = clans.found(fighterB, PhantomHunterClans.fallbackName(), "self-test");
		if ((clanA == null) || (clanB == null))
		{
			LOGGER.warning(tag + "could not found two clans, stop.");
			return;
		}
		try
		{
			// Напрямую, а не набором: незнакомого охотника набор берёт только рядом с главой.
			if (!clans.join(clanA, fighterA, mateA, false) || !clans.join(clanB, fighterB, mateB, false))
			{
				LOGGER.warning(tag + "mates did not join the clans, stop.");
				selfTestCleanup(tag, clanA, clanB);
				return;
			}
			clanA.changeLevel(Math.max(clanA.getLevel(), PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL));
			clanB.changeLevel(Math.max(clanB.getLevel(), PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL));
			LOGGER.info(tag + "clans " + clanA.getName() + " (" + fighterA.getName() + " " + fighterA.getLevel() + ", " + mateA.getName() + ") and " + clanB.getName() + " (" + fighterB.getName() + " " + fighterB.getLevel() + ", " + mateB.getName() + "), level " + clanA.getLevel() + ".");

			// Обиды ниже порога — войны нет; выше — есть.
			final PhantomRelations relations = PhantomRelations.getInstance();
			relations.record(mateB, fighterA, PhantomRelations.Kind.INSULTED);
			synchronized (this)
			{
				declare(now);
			}
			LOGGER.info(tag + String.format(Locale.ROOT, "grudges %.1f of %d: ", grudges(List.of(fighterB, mateB), List.of(fighterA, mateA)) + grudges(List.of(fighterA, mateA), List.of(fighterB, mateB)), PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE) + (war(clanA) == null ? "no war — right" : "WAR (unexpected)") + ".");
			for (int i = 0; i < 5; i++)
			{
				relations.record(mateB, fighterA, PhantomRelations.Kind.KILLED);
				relations.record(fighterB, mateA, PhantomRelations.Kind.KILLED);
			}
			synchronized (this)
			{
				declare(now);
			}
			final String[] war = war(clanB);
			LOGGER.info(tag + String.format(Locale.ROOT, "grudges %.1f: ", grudges(List.of(fighterB, mateB), List.of(fighterA, mateA)) + grudges(List.of(fighterA, mateA), List.of(fighterB, mateB))) + (war != null ? "war with " + war[0] + ", declared by " + clanB.getName() + " (offended)" : "NO WAR") + "; Mobius at war " + PhantomCompat.atWar(clanA, clanB) + ", " + fighterB.getName() + " attackable by " + fighterA.getName() + " without a flag " + fighterB.isAutoAttackable(fighterA) + ".");
			if (war == null)
			{
				selfTestCleanup(tag, clanA, clanB);
				return;
			}

			// Драка: второй боец — рядом с первым, первый нападает (без зрителя, если HunterClanWarWitness = False).
			fighterB.teleToLocation(new Location(fighterA.getX() + 80, fighterA.getY(), fighterA.getZ()), false);
			final Player a = fighterA;
			final Player b = fighterB;
			ThreadPool.schedule(() -> selfTestFight(tag, a, b, clanA, clanB), 3000);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
			selfTestCleanup(tag, clanA, clanB);
		}
	}

	private void selfTestFight(String tag, Player a, Player b, Clan clanA, Clan clanB)
	{
		try
		{
			final long now = System.currentTimeMillis();
			final boolean started = PhantomBrawl.getInstance().warProvoked(a, b, clanB.getName(), now) || PhantomBrawl.getInstance().warProvoked(b, a, clanA.getName(), now + 1);
			// Не началась: оценка махача отказала (причина — ниже) или пара на паузе — значит, драку уже начал обычный ход войн.
			final String why = started ? null : PhantomBrawl.refusal(a, b, PhantomHuntersConfig.HUNTER_CLAN_WAR_WITNESS);
			LOGGER.info(tag + a.getName() + " and " + b.getName() + " are " + (int) a.calculateDistance2D(b) + " apart: fight " + (started ? "started" : "NOT started (" + (why != null ? why : "pair pause — the war pass has already started a fight") + ")") + ".");
			if (!started)
			{
				PhantomHunterManager.getInstance().startWarFight(a, b, clanB.getName());
				LOGGER.info(tag + "forced the attack for the check.");
			}
			ThreadPool.schedule(() ->
			{
				try
				{
					final War war = _wars.get(key(clanA.getId(), clanB.getId()));
					LOGGER.info(tag + "after " + (TEST_FIGHT / 1000) + " s: " + a.getName() + " HP " + percent(a) + "%, karma " + PhantomCompat.karma(a) + ", flag " + a.getPvpFlag() + (a.isDead() ? ", dead" : "") + "; " + b.getName() + " HP " + percent(b) + "%, karma " + PhantomCompat.karma(b) + ", flag " + b.getPvpFlag() + (b.isDead() ? ", dead" : "") + (war != null ? "; score " + war.lowName + " " + war.killsLow + ":" + war.killsHigh + " " + war.highName : "; NO WAR") + ".");
					// Счёт до перемирия.
					if (war != null)
					{
						while (war.total() < PhantomHuntersConfig.HUNTER_CLAN_WAR_KILLS)
						{
							onKill(b, a);
						}
					}
					synchronized (PhantomClanWars.this)
					{
						endings(System.currentTimeMillis());
					}
					LOGGER.info(tag + "after " + PhantomHuntersConfig.HUNTER_CLAN_WAR_KILLS + " kills: " + (war(clanA) == null ? "truce" : "STILL AT WAR") + ", Mobius at war " + PhantomCompat.atWar(clanA, clanB) + ".");
					synchronized (PhantomClanWars.this)
					{
						declare(System.currentTimeMillis());
					}
					LOGGER.info(tag + "declare again right after the truce: " + (war(clanA) == null ? "no war — truce for " + TRUCE_HOURS + " h" : "WAR (unexpected)") + ".");
				}
				catch (Exception e)
				{
					LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
				}
				finally
				{
					selfTestCleanup(tag, clanA, clanB);
				}
			}, TEST_FIGHT);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
			selfTestCleanup(tag, clanA, clanB);
		}
	}

	private void selfTestCleanup(String tag, Clan clanA, Clan clanB)
	{
		synchronized (this)
		{
			final War war = _wars.get(key(clanA.getId(), clanB.getId()));
			if (war != null)
			{
				end(war, System.currentTimeMillis(), "самопроверка", "self-test");
			}
			GlobalVariablesManager.getInstance().remove(variable(TRUCE_VARIABLE, clanA.getId(), clanB.getId()));
			GlobalVariablesManager.getInstance().remove(PLAYER_TRUCE_VARIABLE + clanA.getId());
			GlobalVariablesManager.getInstance().remove(PLAYER_TRUCE_VARIABLE + clanB.getId());
		}
		for (Clan clan : List.of(clanA, clanB))
		{
			final List<Player> members = clan.getOnlineMembers(0);
			ClanTable.getInstance().destroyClan(clan.getId());
			for (Player member : members)
			{
				member.setClanJoinExpiryTime(0);
				member.setClanCreateExpiryTime(0);
				ThreadPool.execute(member::storeMe);
			}
		}
		LOGGER.info(tag + "done.");
	}

	/**
	 * Самопроверка CLANWARPLAYER ({@code HuntersSelfTest}, только на копии базы: кланы, войны, отношения и штрафы ложатся в базу). Живого игрока
	 * играет охотник со своим кланом {@code L2HubTest}, против — клан охотников из тех, кто в пределах {@code HunterBrawlLevelGap} от него (до четырёх);
	 * оба 3-го уровня. Обида ниже порога — войны нет; убийства — клан охотников объявляет войну, Mobius воюет, «игрок» атакуем без флага. Охотники
	 * рядом с ним нападают, не больше {@code HunterClanWarPlayerAttackers} разом; через {@value #TEST_FIGHT} мс — кто как. Счёт до
	 * {@code HunterClanWarKills} — перемирие и сутки мира клану игрока; вторая война и выход из неё самого клана игрока (как «прекратить войну» в окне
	 * клана) — перемирие, у Mobius войны нет ни с одной стороны. Кланы распускаются.
	 * @param hunters охотники в мире
	 */
	void selfTestPlayer(List<Player> hunters)
	{
		final String tag = getClass().getSimpleName() + ": SelfTest CLANWARPLAYER: ";
		final long now = System.currentTimeMillis();
		final PhantomHunterClans clans = PhantomHunterClans.getInstance();
		final List<Player> free = new ArrayList<>();
		for (Player hunter : hunters)
		{
			if (PhantomHunterClans.canFound(hunter, now) && !clans.isLoner(hunter) && !hunter.isDead() && PhantomHunterManager.getInstance().isHunting(hunter))
			{
				free.add(hunter);
			}
		}
		// «Игрок» — тот, у кого больше всего охотников в пределах HunterBrawlLevelGap, которым его спот по уровню: они и будут его врагами
		// (до четырёх). Спот не по уровню — тик менеджера уведёт охотника на другой раньше прохода драк.
		Player living = null;
		List<Player> team = List.of();
		for (Player candidate : free)
		{
			final List<Player> near = new ArrayList<>();
			for (Player other : free)
			{
				if ((other != candidate) && (Math.abs(other.getLevel() - candidate.getLevel()) <= PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP) && PhantomHunterManager.getInstance().fitsSpot(other, candidate))
				{
					near.add(other);
				}
			}
			if (near.size() > team.size())
			{
				living = candidate;
				team = near.subList(0, Math.min(4, near.size()));
			}
		}
		if ((living == null) || (team.size() < 2))
		{
			LOGGER.warning(tag + "needs three free hunters of level 10+ on a spot within " + PhantomHuntersConfig.HUNTER_BRAWL_LEVEL_GAP + " levels, the living player's spot fitting the others; found " + free.size() + " free.");
			return;
		}
		String name = PhantomClans.TEST_CLAN;
		for (int i = 2; ClanTable.getInstance().getClanByName(name) != null; i++)
		{
			name = PhantomClans.TEST_CLAN + i;
		}
		final Clan playerClan = ClanTable.getInstance().createClan(living, name);
		if (playerClan == null)
		{
			LOGGER.warning(tag + living.getName() + " could not create clan " + name + ", stop.");
			return;
		}
		PhantomClans.getInstance().testPlayer(living);
		final Player founder = team.get(0);
		final Clan hunterClan = clans.found(founder, PhantomHunterClans.fallbackName(), "self-test");
		if (hunterClan == null)
		{
			LOGGER.warning(tag + founder.getName() + " could not found a clan, stop.");
			selfTestPlayerCleanup(tag, playerClan, null);
			return;
		}
		try
		{
			for (Player mate : team.subList(1, team.size()))
			{
				if (!clans.join(hunterClan, founder, mate, false))
				{
					LOGGER.warning(tag + mate.getName() + " did not join " + hunterClan.getName() + ", stop.");
					selfTestPlayerCleanup(tag, playerClan, hunterClan);
					return;
				}
			}
			playerClan.changeLevel(Math.max(playerClan.getLevel(), PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL));
			hunterClan.changeLevel(Math.max(hunterClan.getLevel(), PhantomHuntersConfig.HUNTER_CLAN_WAR_LEVEL));
			final List<String> names = new ArrayList<>();
			for (Player hunter : team)
			{
				names.add(hunter.getName() + " " + hunter.getLevel());
			}
			LOGGER.info(tag + living.getName() + " (" + living.getLevel() + ") plays the living player, clan " + playerClan.getName() + "; hunter clan " + hunterClan.getName() + " (" + String.join(", ", names) + "), level " + hunterClan.getLevel() + ".");

			// Обида ниже порога — войны нет; убийства — есть.
			final PhantomRelations relations = PhantomRelations.getInstance();
			final Player mate = team.get(1);
			relations.record(mate, living, PhantomRelations.Kind.INSULTED);
			synchronized (this)
			{
				declarePlayer(now);
			}
			LOGGER.info(tag + String.format(Locale.ROOT, "grudges %.1f of %d: ", grudges(team, List.of(living)), PhantomHuntersConfig.HUNTER_CLAN_WAR_GRUDGE) + (war(playerClan) == null ? "no war — right" : "WAR (unexpected)") + ".");
			for (int i = 0; i < 3; i++)
			{
				relations.record(founder, living, PhantomRelations.Kind.KILLED);
				relations.record(mate, living, PhantomRelations.Kind.KILLED);
			}
			synchronized (this)
			{
				declarePlayer(now);
			}
			final String[] war = war(playerClan);
			LOGGER.info(tag + String.format(Locale.ROOT, "grudges %.1f: ", grudges(team, List.of(living))) + (war != null ? "war with " + war[0] + " (" + hunterClan.getName() + " declared)" : "NO WAR") + "; Mobius at war " + PhantomCompat.atWar(hunterClan, playerClan) + ", " + living.getName() + " attackable by " + founder.getName() + " without a flag " + living.isAutoAttackable(founder) + "; the hunters see the enemy as a player clan " + "1".equals(war(hunterClan) != null ? war(hunterClan)[2] : "") + ".");
			if (war == null)
			{
				selfTestPlayerCleanup(tag, playerClan, hunterClan);
				return;
			}

			// Нападение: все враги — рядом с «игроком» и на его споте (свой спот далеко — тик вернул бы их туда).
			for (int i = 0; i < team.size(); i++)
			{
				if (!PhantomHunterManager.getInstance().sameSpot(team.get(i), living))
				{
					LOGGER.warning(tag + team.get(i).getName() + " could not move to the spot of " + living.getName() + ".");
				}
				team.get(i).teleToLocation(new Location(living.getX() + (60 * (i + 1)), living.getY() + 40, living.getZ()), false);
			}
			final Player target = living;
			final List<Player> enemies = team;
			ThreadPool.schedule(() -> selfTestPlayerFight(tag, target, enemies, playerClan, hunterClan), 3000);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
			selfTestPlayerCleanup(tag, playerClan, hunterClan);
		}
	}

	private void selfTestPlayerFight(String tag, Player living, List<Player> team, Clan playerClan, Clan hunterClan)
	{
		try
		{
			final long now = System.currentTimeMillis();
			final War war = _wars.get(key(playerClan.getId(), hunterClan.getId()));
			if (war == null)
			{
				LOGGER.warning(tag + "the war is gone before the fight, stop.");
				selfTestPlayerCleanup(tag, playerClan, hunterClan);
				return;
			}
			final int before = PhantomHunterManager.getInstance().attackers(living);
			LOGGER.info(tag + "before the pass: " + selfTestState(living, team, now) + ".");
			final int started;
			synchronized (this)
			{
				started = livingFights(war, team, new HashSet<>(), now, true);
			}
			final int after = PhantomHunterManager.getInstance().attackers(living);
			int near = 0;
			for (Player hunter : team)
			{
				if ((hunter.getInstanceId() == living.getInstanceId()) && (hunter.calculateDistance2D(living) <= FIGHT_RANGE))
				{
					near++;
				}
			}
			LOGGER.info(tag + near + " of " + team.size() + " hunters near " + living.getName() + ": fighting him " + before + " -> " + after + " (cap " + PhantomHuntersConfig.HUNTER_CLAN_WAR_PLAYER_ATTACKERS + ", attacked now " + started + "); after the pass: " + selfTestState(living, team, System.currentTimeMillis()) + ".");
			ThreadPool.schedule(() ->
			{
				try
				{
					final List<String> state = new ArrayList<>();
					for (Player hunter : team)
					{
						state.add(hunter.getName() + " HP " + percent(hunter) + "%, karma " + PhantomCompat.karma(hunter) + (hunter.isDead() ? ", dead" : ""));
					}
					LOGGER.info(tag + "after " + (TEST_FIGHT / 1000) + " s: " + living.getName() + " HP " + percent(living) + "%, karma " + PhantomCompat.karma(living) + ", flag " + living.getPvpFlag() + (living.isDead() ? ", dead" : "") + "; " + String.join("; ", state) + "; score " + war.lowName + " " + war.killsLow + ":" + war.killsHigh + " " + war.highName + ".");
					// Счёт до перемирия.
					while (war.total() < PhantomHuntersConfig.HUNTER_CLAN_WAR_KILLS)
					{
						onKill(living, team.get(0));
					}
					synchronized (PhantomClanWars.this)
					{
						endings(System.currentTimeMillis());
					}
					LOGGER.info(tag + "after " + PhantomHuntersConfig.HUNTER_CLAN_WAR_KILLS + " kills: " + (war(playerClan) == null ? "truce" : "STILL AT WAR") + ", Mobius at war " + PhantomCompat.atWar(hunterClan, playerClan) + ".");
					synchronized (PhantomClanWars.this)
					{
						declarePlayer(System.currentTimeMillis());
					}
					LOGGER.info(tag + "declare again right after the truce: " + (war(playerClan) == null ? "no war — the player clan has " + TRUCE_HOURS + " h of peace" : "WAR (unexpected)") + ".");

					// Вторая война без суток мира — и клан «игрока» выходит из неё сам, как «прекратить войну» в окне клана.
					final GlobalVariablesManager variables = GlobalVariablesManager.getInstance();
					variables.remove(variable(TRUCE_VARIABLE, playerClan.getId(), hunterClan.getId()));
					variables.remove(PLAYER_TRUCE_VARIABLE + playerClan.getId());
					synchronized (PhantomClanWars.this)
					{
						declarePlayer(System.currentTimeMillis());
					}
					if (war(playerClan) == null)
					{
						LOGGER.info(tag + "second war: NOT declared (grudges " + String.format(Locale.ROOT, "%.1f", grudges(team, List.of(living))) + ").");
					}
					else
					{
						ClanTable.getInstance().deleteClanWars(playerClan.getId(), hunterClan.getId());
						LOGGER.info(tag + "second war declared; the player clan stops it: Mobius at war " + PhantomCompat.atWar(hunterClan, playerClan) + ", the hunter clan still at war with it " + hunterClan.isAtWarWith(playerClan.getId()) + ".");
						synchronized (PhantomClanWars.this)
						{
							endings(System.currentTimeMillis());
						}
						LOGGER.info(tag + "after the pass: " + (war(playerClan) == null ? "truce" : "STILL AT WAR") + ", the hunter clan at war with it " + hunterClan.isAtWarWith(playerClan.getId()) + ", the player clan at war " + playerClan.isAtWarWith(hunterClan.getId()) + ", peace for the player clan " + (playerTruceUntil(playerClan) > System.currentTimeMillis() ? "set" : "NOT set") + ".");
					}
				}
				catch (Exception e)
				{
					LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
				}
				finally
				{
					selfTestPlayerCleanup(tag, playerClan, hunterClan);
				}
			}, TEST_FIGHT);
		}
		catch (Exception e)
		{
			LOGGER.log(Level.WARNING, tag + "failed: " + e.getMessage(), e);
			selfTestPlayerCleanup(tag, playerClan, hunterClan);
		}
	}

	/**
	 * @return по каждому охотнику: дерётся ли с «игроком», расстояние, инстанс, пауза пары, отказ оценки
	 */
	private static String selfTestState(Player living, List<Player> team, long now)
	{
		final List<String> lines = new ArrayList<>();
		for (Player hunter : team)
		{
			final String why = PhantomBrawl.livingRefusal(hunter, living);
			final long wait = PhantomBrawl.getInstance().pairWait(hunter, living, now);
			lines.add(hunter.getName() + " " + (int) hunter.calculateDistance2D(living) + " away" + (hunter.getInstanceId() != living.getInstanceId() ? ", other instance" : "") + (wait > 0 ? ", pair pause " + (wait / 1000) + " s" : "") + ", " + (PhantomHunterManager.getInstance().isFighting(hunter, living) ? "fighting" : why != null ? why : "ok"));
		}
		return String.join("; ", lines);
	}

	private void selfTestPlayerCleanup(String tag, Clan playerClan, Clan hunterClan)
	{
		try
		{
			if (hunterClan != null)
			{
				selfTestCleanup(tag, playerClan, hunterClan);
				return;
			}
			ClanTable.getInstance().destroyClan(playerClan.getId());
			LOGGER.info(tag + "done.");
		}
		finally
		{
			PhantomClans.getInstance().testPlayer(null);
		}
	}

	private static int percent(Player player)
	{
		return (int) ((player.getCurrentHp() * 100) / Math.max(1, player.getMaxHp()));
	}

	public static PhantomClanWars getInstance()
	{
		return SingletonHolder.INSTANCE;
	}

	private static class SingletonHolder
	{
		protected static final PhantomClanWars INSTANCE = new PhantomClanWars();
	}
}
