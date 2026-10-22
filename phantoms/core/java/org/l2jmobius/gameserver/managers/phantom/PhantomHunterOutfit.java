package org.l2jmobius.gameserver.managers.phantom;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.l2jmobius.gameserver.ai.Intention;
import org.l2jmobius.gameserver.config.custom.PhantomHuntersConfig;
import org.l2jmobius.gameserver.data.xml.ItemData;
import org.l2jmobius.gameserver.entity.World;
import org.l2jmobius.gameserver.entity.WorldObject;
import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.Player;
import org.l2jmobius.gameserver.entity.actor.enums.player.PlayerClass;
import org.l2jmobius.gameserver.entity.actor.holders.player.AutoPlaySettingsHolder;
import org.l2jmobius.gameserver.entity.actor.holders.player.AutoUseSettingsHolder;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.item.ItemTemplate;
import org.l2jmobius.gameserver.entity.item.enums.BodyPart;
import org.l2jmobius.gameserver.entity.item.enums.ItemProcessType;
import org.l2jmobius.gameserver.entity.item.instance.Item;
import org.l2jmobius.gameserver.entity.item.type.WeaponType;
import org.l2jmobius.gameserver.entity.itemcontainer.Inventory;
import org.l2jmobius.gameserver.mechanics.effects.EffectType;
import org.l2jmobius.gameserver.mechanics.skill.Skill;

/**
 * L2Hub: профессия, скиллы и хозяйство охотника — как у живого игрока на x1 (раздел 4.2 docs/PHANTOMS.md).<br>
 * Профессия ветки — на 20, 40 и 76; новые скиллы — после каждого уровня. Стартовый шмот — бесплатно, как при создании персонажа; дальше всё за свою адену:
 * стрелы и сосы под грейд надетого оружия, шмот по частям (оружие, потом броня) по цене магазина и GM-шопа, на что не хватает — грейдом ниже. Удачный дроп надевает сам, хлам сдаёт NPC за полцены, ценное — в лавку.
 */
final class PhantomHunterOutfit
{
	/** Автоатака в AutoUse (как в AutoPlayTaskManager): без неё AutoPlay считает игрока кастером и не бьёт оружием. */
	private static final Integer AUTO_ATTACK_ACTION = 2;
	/** Моб ближе этого и бьёт мага — каст собьют, отмахнуться оружием. */
	private static final int MELEE_RANGE = 150;
	/** Сосы, BSS, стрелы и болты по уровню грейда оружия (CrystalType.getLevel: NONE 0, D 1 … S 5, S80 6, S84 7; у S80 и S84 — S-грейдовые). */
	private static final int[] SOULSHOTS =
	{
		1835,
		1463,
		1464,
		1465,
		1466,
		1467,
		1467,
		1467
	};
	private static final int[] SPIRITSHOTS =
	{
		3947,
		3948,
		3949,
		3950,
		3951,
		3952,
		3952,
		3952
	};
	private static final int[] ARROWS =
	{
		17,
		1341,
		1342,
		1343,
		1344,
		1345,
		1345,
		1345
	};
	private static final int[] BOLTS =
	{
		9632,
		9633,
		9634,
		9635,
		9636,
		9637,
		9637,
		9637
	};
	/** Порядок покупки шмота: сначала оружие. */
	private static final int[] SLOTS =
	{
		Inventory.PAPERDOLL_RHAND,
		Inventory.PAPERDOLL_CHEST,
		Inventory.PAPERDOLL_LEGS,
		Inventory.PAPERDOLL_HEAD,
		Inventory.PAPERDOLL_GLOVES,
		Inventory.PAPERDOLL_FEET,
		Inventory.PAPERDOLL_LHAND
	};
	private static final Set<BodyPart> JEWELS = Set.of(BodyPart.NECK, BodyPart.R_EAR, BodyPart.L_EAR, BodyPart.LR_EAR, BodyPart.R_FINGER, BodyPart.L_FINGER, BodyPart.LR_FINGER);
	/** Сколько сосов и стрел держать в запасе при покупке шмота: новая вещь не оставит охотника без них. */
	private static final int SHOT_RESERVE = 300;
	private static final int AMMO_RESERVE = 500;
	private static final int AMMO_LOAN = 300;
	/** Хилки по уровню: Lesser (1060, до 20), Greater (1061, до 40), Greater (1539, дальше) — так в датапаке H5. */
	private static final int[] HEALING_POTIONS =
	{
		1060,
		1061,
		1539
	};
	private static final int POTION_HP_PERCENT = 40;
	/** Левые слоты — первыми: так их заполняет Inventory.equipItem, иначе вторая серьга и второе кольцо не покупались бы. */
	private static final int[] JEWEL_SLOTS =
	{
		Inventory.PAPERDOLL_NECK,
		Inventory.PAPERDOLL_LEAR,
		Inventory.PAPERDOLL_REAR,
		Inventory.PAPERDOLL_LFINGER,
		Inventory.PAPERDOLL_RFINGER
	};
	private static final Pattern JEWEL_SKIP = Pattern.compile(" - |Commendation|Event|Shadow|Common|Limited|Sealed|Olympiad|Hero|Lord|Test|Monster|Pvp|PvP|Retail|Baguette|Valakas|Antharas|Baium|Zaken|Orfen|Core|Queen Ant|Frintezza|Beleth|Freya");
	private static int[][] _jewels;
	/** Идти в город за сосами есть смысл, если хватает хотя бы на столько. */
	private static final int RESTOCK_MIN = 100;

	private PhantomHunterOutfit()
	{
	}

	/**
	 * После входа в мир и после каждого нового уровня: профессия, скиллы, стартовый шмот новичку, бой, автососки.
	 * @param player охотник
	 * @param template его описание
	 */
	static void update(Player player, PhantomTemplate template)
	{
		// Лут и сосы весят: с перегрузом 4-й степени охотник встанет столбом.
		player.setDietMode(true);
		final int classId = template.getClassId(player.getLevel());
		if ((player.getPlayerClass().getId() != classId) && !professionLocked(player, classId))
		{
			player.setPlayerClass(classId);
			player.setBaseClass(classId);
		}
		player.giveAvailableSkills(true, true, true);

		// Только что созданный — без оружия: одеть, как при создании персонажа (стартовое снаряжение класса).
		if (player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND) == null)
		{
			final List<PhantomTemplate.Gear> all = template.getAllGear();
			if (!all.isEmpty() && (all.get(0).minLevel <= 1))
			{
				PhantomOutfit.dress(player, all.get(0));
			}
		}

		setupCombat(player);
		syncShots(player);
	}

	// ---------- Профессия за адену ----------

	/**
	 * @param tier ступень профессии: 1 — первая (с 20-го), 2 — вторая, 3 — третья
	 * @return уровень, с которого она, и цена по HunterProfessionPrices; нет в настройках — {@code null} (тогда бесплатно, как раньше)
	 */
	private static Map.Entry<Integer, Long> tier(int tier)
	{
		int i = 0;
		for (Map.Entry<Integer, Long> entry : PhantomHuntersConfig.HUNTER_PROFESSION_PRICES.entrySet())
		{
			if (++i == tier)
			{
				return entry;
			}
		}
		return null;
	}

	/**
	 * Следующая ступень класса сама не выдаётся: за неё охотник платит в городе ({@link #buyProfession}).
	 */
	private static boolean professionLocked(Player player, int classId)
	{
		final PlayerClass target = PlayerClass.getPlayerClass(classId);
		final Map.Entry<Integer, Long> next = tier(player.getPlayerClass().level() + 1);
		return (target != null) && (target.level() > player.getPlayerClass().level()) && (next != null) && (next.getValue() > 0);
	}

	/**
	 * @return сколько стоит профа, до которой охотник дорос, но ещё не купил; 0 — такой нет. Пока не купил, всё на неё и копит.
	 */
	static long professionDue(Player player)
	{
		final Map.Entry<Integer, Long> next = tier(player.getPlayerClass().level() + 1);
		return (next != null) && (player.getLevel() >= next.getKey()) ? next.getValue() : 0;
	}

	/**
	 * Профа в городе, как у классмастера: дорос и накопил — платит и получает класс со скиллами; не накопил — копит.
	 * @return что сделал — для лога, {@code null} — профа не нужна
	 */
	private static String buyProfession(Player player, PhantomTemplate template)
	{
		final List<String> done = new ArrayList<>();
		long price;
		while ((price = professionDue(player)) > 0)
		{
			if (player.getAdena() < price)
			{
				done.add("копит на профу: " + PhantomHunterManager.money(player.getAdena()) + " из " + PhantomHunterManager.money(price));
				break;
			}

			final int classId = template.getClassId(tier(player.getPlayerClass().level() + 1).getKey());
			if ((classId == player.getPlayerClass().getId()) || !PhantomCompat.reduceAdena(player, ItemProcessType.FEE, price, player, false))
			{
				break; // у класса нет следующей ступени
			}

			player.setPlayerClass(classId);
			player.setBaseClass(classId);
			player.giveAvailableSkills(true, true, true);
			setupCombat(player);
			player.broadcastUserInfo();
			done.add("профа " + player.getPlayerClass().name() + " за " + PhantomHunterManager.money(price));
		}
		return done.isEmpty() ? null : String.join(", ", done);
	}

	// ---------- Расходники ----------

	private static int weaponGrade(Player player)
	{
		final Item weapon = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		return weapon == null ? 0 : Math.min(7, weapon.getTemplate().getCrystalType().getLevel());
	}

	/**
	 * @return id сосов под надетое оружие: BSS у магов, соулшоты у воинов
	 */
	static int shotId(Player player)
	{
		return shotId(player.isMageClass(), weaponGrade(player));
	}

	/**
	 * @return id сосов грейда: по таблицам, а где торговцы продают только сосы без грейда (Classic, Essence) — их
	 */
	private static int shotId(boolean mage, int grade)
	{
		final int gradeless = PhantomCompat.gradelessShot(mage);
		return gradeless > 0 ? gradeless : mage ? SPIRITSHOTS[grade] : SOULSHOTS[grade];
	}

	/**
	 * @return id стрел или болтов под надетый лук или арбалет, 0 — оружию они не нужны
	 */
	static int ammoId(Player player)
	{
		final Item weapon = player.getInventory().getPaperdollItem(Inventory.PAPERDOLL_RHAND);
		if ((weapon == null) || !weapon.isWeapon())
		{
			return 0;
		}

		final WeaponType type = (WeaponType) weapon.getTemplate().getItemType();
		return type == WeaponType.BOW ? ARROWS[weaponGrade(player)] : PhantomCompat.isCrossbow(type) ? BOLTS[weaponGrade(player)] : 0;
	}

	private static long count(Player player, int itemId)
	{
		final Item item = itemId > 0 ? player.getInventory().getItemByItemId(itemId) : null;
		return item == null ? 0 : item.getCount();
	}

	/**
	 * @return доля запаса сосов (и стрел у лучника), что ещё осталась: 0…1 — по меньшему из двух
	 */
	static double suppliesLeft(Player player)
	{
		double left = Math.min(1, count(player, shotId(player)) / (double) Math.max(1, PhantomHuntersConfig.HUNTER_SHOT_COUNT));
		final int ammo = ammoId(player);
		if (ammo > 0)
		{
			left = Math.min(left, count(player, ammo) / (double) Math.max(1, PhantomHuntersConfig.HUNTER_AMMO_COUNT));
		}
		return left;
	}

	/**
	 * @return хватит ли бюджета на сосы (HunterShotBudget % свободной адены — не отложенной на профу) хотя бы на {@value #RESTOCK_MIN} сосов (или стрел у лучника): нет — бьёт без них, в город за ними не идёт
	 */
	static boolean canRestock(Player player)
	{
		final long need = Math.min(price(shotId(player)), ammoId(player) > 0 ? price(ammoId(player)) : Long.MAX_VALUE);
		return shotBudget(player) >= (need * RESTOCK_MIN);
	}

	/**
	 * Сосы с умом: на них — не больше HunterShotBudget % свободной адены (всё, кроме отложенного на профу).
	 */
	private static long shotBudget(Player player)
	{
		return (Math.max(0, player.getAdena() - professionDue(player)) * PhantomHuntersConfig.HUNTER_SHOT_BUDGET) / 100;
	}

	/**
	 * @return нужны ли оружию стрелы, а их нет: такой охотник стоять будет, а не бить
	 */
	static boolean outOfAmmo(Player player)
	{
		final int ammo = ammoId(player);
		return (ammo > 0) && (count(player, ammo) <= 0);
	}

	/**
	 * Автососки — на сосы своего грейда, если они есть.
	 */
	static void syncShots(Player player)
	{
		player.getAutoSoulShot().clear();
		final int shotId = shotId(player);
		if (count(player, shotId) > 0)
		{
			player.addAutoSoulShot(shotId);
			PhantomCompat.rechargeShots(player);
		}
		syncPotion(player);
	}

	// ---------- Город ----------

	/**
	 * Дела в городе: надеть удачный дроп, сдать хлам, купить шмот, стрелы и сосы.
	 * @param player охотник
	 * @param template его описание
	 * @param unsold сколько раз вещь (objectId) не продалась в лавке: после двух — NPC
	 * @return что сделал — для лога
	 */
	static List<String> townChores(Player player, PhantomTemplate template, Map<Integer, Integer> unsold)
	{
		final List<String> done = new ArrayList<>();
		final int equipped = equipBetterDrops(player);
		if (equipped > 0)
		{
			done.add("надел дроп: " + equipped);
		}

		final long sold = sellJunk(player, unsold);
		if (sold > 0)
		{
			done.add("сдал хлам на " + sold);
		}

		// Профа — первой; не накопил — откладывает на неё всё: шмот, банки и сосы — только со свободной адены.
		final String profession = buyProfession(player, template);
		if (profession != null)
		{
			done.add(profession);
		}
		final long reserve = professionDue(player);

		done.addAll(buyGear(player, template, reserve));

		// Стрелы — первыми: без них лук не стреляет. На сосы — не больше половины того, что осталось.
		final long arrows = buy(player, ammoId(player), PhantomHuntersConfig.HUNTER_AMMO_COUNT, player.getAdena());
		if (arrows > 0)
		{
			done.add("купил стрел: " + arrows);
		}
		else if (outOfAmmo(player))
		{
			// Совсем без денег и без стрел лучник стоял бы столбом: пачка стрел в долг (единственная поблажка экономики).
			player.getInventory().addItem(ItemProcessType.REWARD, ammoId(player), AMMO_LOAN, player, null);
			done.add("взял стрел в долг: " + AMMO_LOAN);
		}

		// Банки — на четверть свободной адены (копит на профу — на десятую всей: без банок помрёт): хилка своего уровня, AutoUse пьёт её на 40 % HP.
		final long free = Math.max(0, player.getAdena() - reserve);
		final long potions = buy(player, potionId(player), PhantomHuntersConfig.HUNTER_POTION_COUNT, free > 0 ? free / 4 : player.getAdena() / 10);
		if (potions > 0)
		{
			done.add("купил банок: " + potions);
		}

		// Сосы с умом: бюджет — HunterShotBudget % свободной адены; меньше чем на RESTOCK_MIN — не берёт вовсе, бьёт без них.
		final long budget = shotBudget(player);
		final long shots = budget >= (price(shotId(player)) * RESTOCK_MIN) ? buy(player, shotId(player), PhantomHuntersConfig.HUNTER_SHOT_COUNT, budget) : 0;
		if (shots > 0)
		{
			done.add("купил сосов: " + shots);
		}

		// Призыватель (раздел 4.37): кристаллы, которые ест его призыв, — запас на CRYSTAL_STOCK призывов, на четверть свободной адены.
		final Skill summon = PhantomSummoner.summonSkill(player);
		if ((summon != null) && (summon.getItemConsumeId() > 0))
		{
			final long crystals = buy(player, summon.getItemConsumeId(), PhantomSummoner.CRYSTAL_STOCK * Math.max(1, summon.getItemConsumeCount()), free > 0 ? free / 4 : player.getAdena() / 10);
			if (crystals > 0)
			{
				done.add("купил на призыв " + ItemData.getInstance().getTemplate(summon.getItemConsumeId()).getName() + ": " + crystals);
			}
		}

		// Семь печатей (раздел 4.42): в неделю соревнования — вступить у жреца или сдать найденные камни.
		PhantomSevenSigns.getInstance().townVisit(player, template, done);
		syncShots(player);
		return done;
	}

	// ---------- Для стратега ----------

	/** Названия частей шмота в порядке {@link #SLOTS}. */
	private static final String[] SLOT_NAMES =
	{
		"оружие",
		"нагрудник",
		"штаны",
		"шлем",
		"перчатки",
		"ботинки",
		"щит"
	};

	/**
	 * Для стратега: что надето по грейдам и чего не хватает до сета лучшего разрешённого грейда — те же вещи и тот же порядок, что у покупок в городе.
	 * @return «надето: оружие D, нагрудник D, …, бижа 2/5; до сета C не хватает: оружие (~1.2кк), … — всего ~3.4кк; дальше — B с 52 ур.»
	 */
	static String gearReport(Player player, PhantomTemplate template)
	{
		final List<PhantomTemplate.Gear> grades = new ArrayList<>();
		for (PhantomTemplate.Gear gear : template.getAllGear())
		{
			if ((gear.minLevel <= player.getLevel()) && (grade(gear.rhand) <= PhantomCompat.expertiseLevel(player)))
			{
				grades.add(0, gear); // лучший грейд — первым
			}
		}
		final PhantomTemplate.Gear best = grades.isEmpty() ? null : grades.get(0);

		final StringBuilder worn = new StringBuilder("надето:");
		final List<String> missing = new ArrayList<>();
		long missingPrice = 0;
		for (int i = 0; i < SLOTS.length; i++)
		{
			final int slot = SLOTS[i];
			final boolean skip = ((slot == Inventory.PAPERDOLL_LEGS) && (wornBodyPart(player, Inventory.PAPERDOLL_CHEST) == BodyPart.FULL_ARMOR)) || ((slot == Inventory.PAPERDOLL_LHAND) && ((wornBodyPart(player, Inventory.PAPERDOLL_RHAND) == BodyPart.LR_HAND) || (best == null) || (best.lhand <= 0)));
			if (skip)
			{
				continue;
			}

			final Item current = player.getInventory().getPaperdollItem(slot);
			final int currentGrade = (current == null) || current.isEtcItem() ? -1 : current.getTemplate().getCrystalType().getLevel();
			worn.append(' ').append(SLOT_NAMES[i]).append(' ').append(currentGrade < 0 ? "—" : gradeName(currentGrade)).append(',');

			final ItemTemplate wanted = best == null ? null : ItemData.getInstance().getTemplate(itemFor(best, slot));
			if ((wanted != null) && (wanted.getCrystalType().getLevel() > currentGrade))
			{
				missing.add(SLOT_NAMES[i] + " (~" + PhantomHunterManager.money(wanted.getReferencePrice()) + ")");
				missingPrice += wanted.getReferencePrice();
			}
		}

		int jewels = 0;
		for (int slot : JEWEL_SLOTS)
		{
			final Item current = player.getInventory().getPaperdollItem(slot);
			if (current != null)
			{
				jewels++;
			}
			else if (missing.isEmpty() && (best != null))
			{
				final ItemTemplate wanted = ItemData.getInstance().getTemplate(jewel(Math.min(7, grade(best.rhand)), slot));
				if (wanted != null)
				{
					missing.add("бижа (~" + PhantomHunterManager.money(wanted.getReferencePrice()) + ")");
					missingPrice += wanted.getReferencePrice();
				}
			}
		}
		worn.append(" бижа ").append(jewels).append('/').append(JEWEL_SLOTS.length);

		if (best != null)
		{
			final String setGrade = gradeName(grade(best.rhand));
			worn.append(missing.isEmpty() ? "; сет " + setGrade + " собран" : "; до сета " + setGrade + " не хватает: " + String.join(", ", missing) + " — всего ~" + PhantomHunterManager.money(missingPrice));
			for (PhantomTemplate.Gear gear : template.getAllGear())
			{
				if (grade(gear.rhand) > grade(best.rhand))
				{
					worn.append("; дальше — ").append(gradeName(grade(gear.rhand))).append(" с ").append(Math.max(gear.minLevel, expertiseLevel(grade(gear.rhand)))).append(" ур.");
					break;
				}
			}
		}
		final long due = professionDue(player);
		if (due > 0)
		{
			worn.append("; профа: дорос до следующей, но она стоит ").append(PhantomHunterManager.money(due)).append(" — копишь, шмот пока не покупаешь");
		}
		return worn.toString();
	}

	/**
	 * Для стратега: сосы, стрелы и банки против запаса из PhantomHunters.ini.
	 * @return «сосы D 1500/2000, стрелы 2000/3000, банок 12/30»
	 */
	static String suppliesReport(Player player)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(player.isMageClass() ? "BSS " : "сосы ").append(gradeName(weaponGrade(player))).append(' ').append(count(player, shotId(player))).append('/').append(PhantomHuntersConfig.HUNTER_SHOT_COUNT);
		final int ammo = ammoId(player);
		if (ammo > 0)
		{
			sb.append(", стрелы ").append(count(player, ammo)).append('/').append(PhantomHuntersConfig.HUNTER_AMMO_COUNT);
		}
		long potions = 0;
		for (int potion : HEALING_POTIONS)
		{
			potions += count(player, potion);
		}
		return sb.append(", банок ").append(potions).append('/').append(PhantomHuntersConfig.HUNTER_POTION_COUNT).toString();
	}

	private static String gradeName(int grade)
	{
		return switch (grade)
		{
			case 0 -> "NG";
			case 1 -> "D";
			case 2 -> "C";
			case 3 -> "B";
			case 4 -> "A";
			case 5 -> "S";
			case 6 -> "S80";
			default -> "S84";
		};
	}

	/**
	 * @return с какого уровня грейд без штрафа (как у игроков H5)
	 */
	private static int expertiseLevel(int grade)
	{
		return switch (grade)
		{
			case 0 -> 1;
			case 1 -> 20;
			case 2 -> 40;
			case 3 -> 52;
			case 4 -> 61;
			case 5 -> 76;
			case 6 -> 80;
			default -> 84;
		};
	}

	/**
	 * @return хилка по уровню: до 20 — 1060, до 40 — 1061, дальше — 1539
	 */
	private static int potionId(Player player)
	{
		return player.getLevel() < 20 ? HEALING_POTIONS[0] : player.getLevel() < 40 ? HEALING_POTIONS[1] : HEALING_POTIONS[2];
	}

	/**
	 * Авто-банка: своя хилка, а нет её — любая из оставшихся; пьёт на {@value #POTION_HP_PERCENT} % HP.
	 */
	private static void syncPotion(Player player)
	{
		int potion = 0;
		for (int i = HEALING_POTIONS.length - 1; i >= 0; i--)
		{
			if (count(player, HEALING_POTIONS[i]) > 0)
			{
				potion = HEALING_POTIONS[i];
				if (potion == potionId(player))
				{
					break;
				}
			}
		}
		player.getAutoUseSettings().setAutoPotionItem(potion);
		player.getAutoPlaySettings().setAutoPotionPercent(POTION_HP_PERCENT);
	}

	/**
	 * Бижутерия (шея, две серьги, два кольца) — после полного сета брони: самая дешёвая обычная своего грейда, по цене магазина. Не хватает — копит.
	 * @param grade грейд шмота, который он сейчас носит по набору
	 * @param reserve запас на расходники и телепорт
	 * @return что купил
	 */
	private static List<String> buyJewels(Player player, int grade, long reserve)
	{
		final List<String> bought = new ArrayList<>();
		for (int slot : JEWEL_SLOTS)
		{
			final int itemId = jewel(grade, slot);
			final ItemTemplate item = itemId > 0 ? ItemData.getInstance().getTemplate(itemId) : null;
			final Item current = player.getInventory().getPaperdollItem(slot);
			if ((item == null) || ((current != null) && (current.getTemplate().getCrystalType().getLevel() >= item.getCrystalType().getLevel())))
			{
				continue;
			}

			if (((player.getAdena() - reserve) < item.getReferencePrice()) || !player.reduceAdena(ItemProcessType.BUY, item.getReferencePrice(), player, false))
			{
				break;
			}

			final Item added = player.getInventory().addItem(ItemProcessType.BUY, itemId, 1, player, null);
			if (added == null)
			{
				break;
			}
			if (current != null)
			{
				player.getInventory().unEquipItemInSlot(slot);
			}
			player.getInventory().equipItem(added);
			if ((current != null) && !current.isEquipped())
			{
				sell(player, current);
			}
			bought.add(item.getName());
		}
		return bought;
	}

	/**
	 * @return самая дешёвая обычная бижутерия грейда для слота (нет в грейде — грейдом ниже); 0 — нет
	 */
	private static int jewel(int grade, int slot)
	{
		final int kind = slot == Inventory.PAPERDOLL_NECK ? 0 : (slot == Inventory.PAPERDOLL_REAR) || (slot == Inventory.PAPERDOLL_LEAR) ? 1 : 2;
		final int[][] catalog = jewels();
		for (int g = Math.min(7, grade); g >= 0; g--)
		{
			if (catalog[g][kind] > 0)
			{
				return catalog[g][kind];
			}
		}
		return 0;
	}

	/**
	 * Каталог бижутерии [грейд][шея, серьга, кольцо] — собирается один раз из ItemData: продаётся, передаётся, с ценой, без SA, наградных, ивентовых и геройских.
	 */
	private static synchronized int[][] jewels()
	{
		if (_jewels != null)
		{
			return _jewels;
		}

		final int[][] catalog = new int[8][3];
		final long[][] best = new long[8][3];
		for (ItemTemplate item : ItemData.getInstance().getAllItems())
		{
			if ((item == null) || !item.isArmor() || (item.getReferencePrice() <= 0) || !item.isSellable() || !item.isTradeable() || item.isQuestItem() || item.isHeroItem() || item.isPvpItem())
			{
				continue;
			}

			final BodyPart part = item.getBodyPart();
			final int kind = part == BodyPart.NECK ? 0 : (part == BodyPart.LR_EAR) || (part == BodyPart.R_EAR) || (part == BodyPart.L_EAR) ? 1 : (part == BodyPart.LR_FINGER) || (part == BodyPart.R_FINGER) || (part == BodyPart.L_FINGER) ? 2 : -1;
			final String name = item.getName();
			if ((kind < 0) || (name == null) || JEWEL_SKIP.matcher(name).find())
			{
				continue;
			}

			final int grade = Math.min(7, item.getCrystalType().getLevel());
			if ((catalog[grade][kind] == 0) || (item.getReferencePrice() < best[grade][kind]))
			{
				catalog[grade][kind] = item.getId();
				best[grade][kind] = item.getReferencePrice();
			}
		}
		_jewels = catalog;
		return catalog;
	}

	/**
	 * Докупить расходник до запаса по цене магазина.
	 * @return сколько купил
	 */
	private static long buy(Player player, int itemId, int stock, long budget)
	{
		final long price = Math.max(1, price(itemId));
		final long want = itemId <= 0 ? 0 : Math.min(stock - count(player, itemId), budget / price);
		if ((want <= 0) || !PhantomCompat.reduceAdena(player, ItemProcessType.BUY, want * price, player, false))
		{
			return 0;
		}

		PhantomCompat.addItem(player, ItemProcessType.BUY, itemId, want, null);
		return want;
	}

	private static long price(int itemId)
	{
		final ItemTemplate template = itemId > 0 ? ItemData.getInstance().getTemplate(itemId) : null;
		return template == null ? 0 : template.getReferencePrice();
	}

	/**
	 * Шмот по частям: оружие, потом броня. Для каждой части — лучший разрешённый грейд из своего набора, на который хватает адены (с запасом на сосы, стрелы и телепорт); не хватает — грейдом ниже, если это лучше надетого; и на это нет — копит, следующие части не покупает. Старое — NPC за полцены.
	 * @return что купил — для лога
	 */
	private static List<String> buyGear(Player player, PhantomTemplate template, long professionReserve)
	{
		final List<String> bought = new ArrayList<>();
		final int expertise = PhantomCompat.expertiseLevel(player);
		final List<PhantomTemplate.Gear> grades = new ArrayList<>();
		for (PhantomTemplate.Gear gear : template.getAllGear())
		{
			if ((gear.minLevel <= player.getLevel()) && (grade(gear.rhand) <= expertise))
			{
				grades.add(0, gear); // лучший грейд — первым
			}
		}

		boolean stopped = false;
		for (int slot : SLOTS)
		{
			// Штаны к цельному доспеху и щит к двуручу не покупаются: надев их, он снял бы доспех или оружие.
			if (((slot == Inventory.PAPERDOLL_LEGS) && (wornBodyPart(player, Inventory.PAPERDOLL_CHEST) == BodyPart.FULL_ARMOR)) || ((slot == Inventory.PAPERDOLL_LHAND) && (wornBodyPart(player, Inventory.PAPERDOLL_RHAND) == BodyPart.LR_HAND)))
			{
				continue;
			}

			final Item current = player.getInventory().getPaperdollItem(slot);
			final int currentGrade = (current == null) || current.isEtcItem() ? -1 : current.getTemplate().getCrystalType().getLevel();
			boolean saving = false; // есть вещь лучше надетой, но не хватает ни на один грейд
			for (PhantomTemplate.Gear gear : grades)
			{
				final int itemId = itemFor(gear, slot);
				if (itemId <= 0)
				{
					continue;
				}

				final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
				if ((item == null) || (item.getCrystalType().getLevel() <= currentGrade) || ((current != null) && (current.getId() == itemId)))
				{
					break; // надетое не хуже этого грейда, ниже искать нечего
				}
				saving = true;

				// Запас на расходники того грейда, под который покупается (новое оружие меняет грейд сосов).
				final int g = Math.min(7, grade(gear.rhand));
				final long ammoReserve = (slot == Inventory.PAPERDOLL_RHAND ? isRanged(gear.rhand) : ammoId(player) > 0) ? price(ARROWS[g]) * AMMO_RESERVE : 0;
				final long reserve = (price(shotId(player.isMageClass(), g)) * SHOT_RESERVE) + ammoReserve + (player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0) + professionReserve;
				if ((player.getAdena() - reserve) < item.getReferencePrice())
				{
					continue; // не хватает — посмотреть грейд ниже
				}

				if (!player.reduceAdena(ItemProcessType.BUY, item.getReferencePrice(), player, false))
				{
					break;
				}

				saving = false;
				final Item added = player.getInventory().addItem(ItemProcessType.BUY, itemId, 1, player, null);
				if ((added != null) && added.isEquipable())
				{
					player.getInventory().equipItem(added);
					retire(player, current);
					bought.add(item.getName());
				}
				break;
			}

			// Не хватает на эту вещь — копит на неё, остальное потом (сначала оружие, как у игроков).
			if (saving)
			{
				stopped = true;
				break;
			}
		}

		// Броня собрана — бижутерия своего грейда.
		if (!stopped && !grades.isEmpty())
		{
			final int g = Math.min(7, grade(grades.get(0).rhand));
			final long reserve = (price(shotId(player.isMageClass(), g)) * SHOT_RESERVE) + (ammoId(player) > 0 ? price(ARROWS[g]) * AMMO_RESERVE : 0) + (player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0) + professionReserve;
			bought.addAll(buyJewels(player, g, reserve));
		}

		if (!bought.isEmpty())
		{
			PhantomCompat.refreshExpertisePenalty(player);
			player.broadcastUserInfo();
		}
		return bought;
	}

	/**
	 * Снятое после покупки: NPC за полцены; с грейдом ({@code HunterSellOldGear}) — в сумку, на рынок: у младших охотников и у игрока на него спрос,
	 * а NPC оно уйдёт само, если дважды не продастся в лавке или окажется дешевле порога лавки ({@link #sellJunk}).
	 */
	private static void retire(Player player, Item old)
	{
		if ((old == null) || old.isEquipped() || old.isEtcItem())
		{
			return;
		}
		if (PhantomHuntersConfig.HUNTER_SELL_OLD_GEAR && (old.getTemplate().getCrystalType().getLevel() > 0) && old.isTradeable())
		{
			return;
		}
		sell(player, old);
	}

	/**
	 * Купленное в своей лавке скупки — надеть, как покупку в магазине ({@link #buyGear}), снятое — {@link #retire}.
	 * @param player охотник
	 * @param itemId что скупал
	 * @return надел
	 */
	static boolean equipBought(Player player, int itemId)
	{
		Item item = null;
		for (Item candidate : player.getInventory().getAllItemsByItemId(itemId))
		{
			if (!candidate.isEquipped() && candidate.isEquipable())
			{
				item = candidate;
				break;
			}
		}
		if ((item == null) || (item.getTemplate().getCrystalType().getLevel() > PhantomCompat.expertiseLevel(player)))
		{
			return false;
		}

		final int slot = Inventory.getPaperdollIndex(item.getTemplate().getBodyPart());
		final Item current = slot < 0 ? null : player.getInventory().getPaperdollItem(slot);
		player.getInventory().equipItem(item);
		retire(player, current);
		PhantomCompat.refreshExpertisePenalty(player);
		player.broadcastUserInfo();
		return true;
	}

	/**
	 * @return место надетой в слоте вещи или {@code null}
	 */
	private static BodyPart wornBodyPart(Player player, int slot)
	{
		final Item item = slot < 0 ? null : player.getInventory().getPaperdollItem(slot);
		return item == null ? null : item.getTemplate().getBodyPart();
	}

	private static boolean isRanged(int itemId)
	{
		final ItemTemplate item = itemId > 0 ? ItemData.getInstance().getTemplate(itemId) : null;
		return (item != null) && item.isWeapon() && ((item.getItemType() == WeaponType.BOW) || PhantomCompat.isCrossbow(item.getItemType()));
	}

	/**
	 * Удачный дроп: разрешённого грейда и дороже надетого. Оружие — того же вида и хвата (и магическое у мага), броня — того же типа и места; бижутерия — в пустой слот или вместо самой дешёвой. Старое потом уйдёт NPC как хлам.
	 * @return сколько вещей надел
	 */
	static int equipBetterDrops(Player player)
	{
		final int expertise = PhantomCompat.expertiseLevel(player);
		int count = 0;
		for (Item item : new ArrayList<>(player.getInventory().getItems()))
		{
			if (item.isEquipped() || !item.isEquipable() || (!item.isWeapon() && !item.isArmor()) || (item.getTemplate().getCrystalType().getLevel() > expertise))
			{
				continue;
			}

			final ItemTemplate drop = item.getTemplate();
			if (JEWELS.contains(drop.getBodyPart()))
			{
				if (equipJewel(player, item))
				{
					count++;
				}
				continue;
			}

			final int slot = Inventory.getPaperdollIndex(drop.getBodyPart());
			final Item current = slot < 0 ? null : player.getInventory().getPaperdollItem(slot);
			if ((current == null) || current.isEtcItem() || (current.getTemplate().getItemType() != drop.getItemType()) || (current.getTemplate().getBodyPart() != drop.getBodyPart()) || (drop.getReferencePrice() <= current.getTemplate().getReferencePrice()))
			{
				continue;
			}

			if (item.isWeapon() && (drop.isMagicWeapon() != current.getTemplate().isMagicWeapon()))
			{
				continue;
			}

			player.getInventory().equipItem(item);
			count++;
		}

		if (count > 0)
		{
			PhantomCompat.refreshExpertisePenalty(player);
			player.broadcastUserInfo();
		}
		return count;
	}

	/**
	 * Вещь из чужой лавки — апгрейд (патч 0035, рынок): те же правила, что у удачного дропа ({@link #equipBetterDrops}) — разрешённого грейда,
	 * того же вида, хвата и места, дороже надетого; бижутерия — если наденет: есть пустой слот её вида или она дороже самой дешёвой надетой (патч 0041).
	 * @param player охотник
	 * @param item вещь продавца
	 * @return надел бы вместо своей
	 */
	static boolean isUpgrade(Player player, Item item)
	{
		final ItemTemplate drop = item.getTemplate();
		if (!item.isEquipable() || (!item.isWeapon() && !item.isArmor()) || (drop.getCrystalType().getLevel() > PhantomCompat.expertiseLevel(player)))
		{
			return false;
		}
		if (JEWELS.contains(drop.getBodyPart()))
		{
			return jewelUpgrade(player, drop);
		}

		final int slot = Inventory.getPaperdollIndex(drop.getBodyPart());
		final Item current = slot < 0 ? null : player.getInventory().getPaperdollItem(slot);
		if ((current == null) || current.isEtcItem() || (current.getTemplate().getItemType() != drop.getItemType()) || (current.getTemplate().getBodyPart() != drop.getBodyPart()) || (drop.getReferencePrice() <= current.getTemplate().getReferencePrice()))
		{
			return false;
		}
		return !item.isWeapon() || (drop.isMagicWeapon() == current.getTemplate().isMagicWeapon());
	}

	/**
	 * Свободная адена (патч 0035): за вычетом того, что отложено на профу, запаса на сосы и платы за телепорт.
	 * @param player охотник
	 * @return сколько можно потратить на рынке
	 */
	static long freeAdena(Player player)
	{
		return player.getAdena() - professionDue(player) - (price(shotId(player)) * SHOT_RESERVE) - (player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0);
	}

	/**
	 * Сколько адены не хватает на ближайшую цель (патч 0035, лавка от нужды): на профу, до которой дорос, или на первую вещь, на которую копит ({@link #buyGear}).
	 * @param player охотник
	 * @param template его шаблон
	 * @return нехватка; 0 — ни на что не копит, всё по карману
	 */
	static long shortfall(Player player, PhantomTemplate template)
	{
		final long due = professionDue(player);
		if (due > player.getAdena())
		{
			return due - player.getAdena();
		}
		final Want want = gearWant(player, template, due);
		return want == null ? 0 : want.missing();
	}

	/**
	 * Вещь, на которую копит (рынок, лавка скупки): первая из {@link #buyGear}, на которую не хватает ни в одном грейде, — самый дешёвый из этих грейдов.
	 * @param itemId вещь
	 * @param price её цена в магазине
	 * @param missing сколько не хватает с учётом запаса на сосы, стрелы, телепорт и профу: потратить на неё можно {@code price − missing}
	 */
	record Want(int itemId, long price, long missing)
	{
	}

	/**
	 * @param player охотник
	 * @param template его шаблон
	 * @return вещь, на которую копит, или {@code null}: копит на профу или всё по карману
	 */
	static Want want(Player player, PhantomTemplate template)
	{
		final long due = professionDue(player);
		return due > player.getAdena() ? null : gearWant(player, template, due);
	}

	/**
	 * Сухой прогон {@link #buyGear}: по тем же правилам и в том же порядке «покупает» на бумаге; первая вещь, на которую не хватает ни в одном грейде, — нехватка до самой дешёвой из них.
	 */
	private static Want gearWant(Player player, PhantomTemplate template, long professionReserve)
	{
		final int expertise = PhantomCompat.expertiseLevel(player);
		final List<PhantomTemplate.Gear> grades = new ArrayList<>();
		for (PhantomTemplate.Gear gear : template.getAllGear())
		{
			if ((gear.minLevel <= player.getLevel()) && (grade(gear.rhand) <= expertise))
			{
				grades.add(0, gear);
			}
		}

		long adena = player.getAdena();
		for (int slot : SLOTS)
		{
			if (((slot == Inventory.PAPERDOLL_LEGS) && (wornBodyPart(player, Inventory.PAPERDOLL_CHEST) == BodyPart.FULL_ARMOR)) || ((slot == Inventory.PAPERDOLL_LHAND) && (wornBodyPart(player, Inventory.PAPERDOLL_RHAND) == BodyPart.LR_HAND)))
			{
				continue;
			}

			final Item current = player.getInventory().getPaperdollItem(slot);
			final int currentGrade = (current == null) || current.isEtcItem() ? -1 : current.getTemplate().getCrystalType().getLevel();
			Want missing = null;
			boolean bought = false;
			for (PhantomTemplate.Gear gear : grades)
			{
				final int itemId = itemFor(gear, slot);
				if (itemId <= 0)
				{
					continue;
				}

				final ItemTemplate item = ItemData.getInstance().getTemplate(itemId);
				if ((item == null) || (item.getCrystalType().getLevel() <= currentGrade) || ((current != null) && (current.getId() == itemId)))
				{
					break;
				}

				final int g = Math.min(7, grade(gear.rhand));
				final long ammoReserve = (slot == Inventory.PAPERDOLL_RHAND ? isRanged(gear.rhand) : ammoId(player) > 0) ? price(ARROWS[g]) * AMMO_RESERVE : 0;
				final long reserve = (price(shotId(player.isMageClass(), g)) * SHOT_RESERVE) + ammoReserve + (player.getLevel() >= 40 ? PhantomHuntersConfig.HUNTER_TELEPORT_FEE : 0) + professionReserve;
				if ((adena - reserve) >= item.getReferencePrice())
				{
					adena -= item.getReferencePrice();
					bought = true;
					break;
				}
				missing = new Want(itemId, item.getReferencePrice(), (item.getReferencePrice() + reserve) - adena); // грейдом ниже — дешевле: остаётся самая маленькая нехватка
			}
			if (!bought && (missing != null) && (missing.missing() > 0))
			{
				return missing;
			}
		}
		return null;
	}

	/**
	 * Бижутерия с рынка (патч 0041): надел бы, как в {@link #equipJewel}, — есть пустой слот её вида или она дороже самой дешёвой надетой.
	 */
	private static boolean jewelUpgrade(Player player, ItemTemplate drop)
	{
		long cheapest = Long.MAX_VALUE;
		for (int slot : jewelSlots(drop.getBodyPart()))
		{
			final Item worn = player.getInventory().getPaperdollItem(slot);
			if (worn == null)
			{
				return true;
			}
			cheapest = Math.min(cheapest, worn.getTemplate().getReferencePrice());
		}
		return cheapest < drop.getReferencePrice();
	}

	private static int[] jewelSlots(BodyPart part)
	{
		final int[] slots;
		switch (part)
		{
			case NECK:
			{
				slots = new int[]
				{
					Inventory.PAPERDOLL_NECK
				};
				break;
			}
			case R_EAR:
			case L_EAR:
			case LR_EAR:
			{
				slots = new int[]
				{
					Inventory.PAPERDOLL_REAR,
					Inventory.PAPERDOLL_LEAR
				};
				break;
			}
			default:
			{
				slots = new int[]
				{
					Inventory.PAPERDOLL_RFINGER,
					Inventory.PAPERDOLL_LFINGER
				};
				break;
			}
		}
		return slots;
	}

	private static boolean equipJewel(Player player, Item item)
	{
		final int[] slots = jewelSlots(item.getTemplate().getBodyPart());
		Item cheapest = null;
		for (int slot : slots)
		{
			final Item worn = player.getInventory().getPaperdollItem(slot);
			if (worn == null)
			{
				player.getInventory().equipItem(item);
				return true;
			}
			if ((cheapest == null) || (worn.getTemplate().getReferencePrice() < cheapest.getTemplate().getReferencePrice()))
			{
				cheapest = worn;
			}
		}

		if ((cheapest == null) || (cheapest.getTemplate().getReferencePrice() >= item.getTemplate().getReferencePrice()))
		{
			return false;
		}

		player.getInventory().unEquipItemInSlot(cheapest.getLocationSlot());
		player.getInventory().equipItem(item);
		return true;
	}

	/**
	 * Хлам — NPC за полцены: всё ненадетое, что продаётся и дешевле порога лавки, и то, что дважды не продалось в лавке. Адена, сосы и стрелы своего грейда, квестовое не трогаются.
	 * @return сколько адены выручил
	 */
	private static long sellJunk(Player player, Map<Integer, Integer> unsold)
	{
		long total = 0;
		for (Item item : new ArrayList<>(player.getInventory().getItems()))
		{
			if (!junk(player, item) || (isStoreGood(player, item) && (unsold.getOrDefault(item.getObjectId(), 0) < 2)))
			{
				continue;
			}

			total += sell(player, item);
			unsold.remove(item.getObjectId());
		}
		return total;
	}

	/**
	 * @return ценное для лавки: ненадетое, продаваемое, не дешевле порога; дорогое — первым
	 */
	static List<Item> storeGoods(Player player)
	{
		final List<Item> goods = new ArrayList<>();
		for (Item item : player.getInventory().getItems())
		{
			if (junk(player, item) && isStoreGood(player, item) && item.isTradeable())
			{
				goods.add(item);
			}
		}
		goods.sort((a, b) -> Long.compare(b.getTemplate().getReferencePrice() * b.getCount(), a.getTemplate().getReferencePrice() * a.getCount()));
		return goods;
	}

	/**
	 * То, что охотник может отдать: не надето, не адена, не квестовое, продаётся NPC; сосы и стрелы — только чужого грейда (остались от прошлого оружия);
 * кристаллы, которые ест его призыв, — не отдаёт (прогон H5 2026-10-22: Vedma выставила в лавку свой запас Spirit Ore).
	 */
	static boolean junk(Player player, Item item)
	{
		final int id = item.getId();
		if (item.isEquipped() || (id == 57) || item.isQuestItem() || !item.isSellable() || (item.getTemplate().getReferencePrice() <= 0))
		{
			return false;
		}
		for (int potion : HEALING_POTIONS)
		{
			if (id == potion)
			{
				return false;
			}
		}
		final Skill summon = PhantomSummoner.summonSkill(player);
		// Печатные камни (6360–6362) — жрецу Семи печатей, а не в магазин (раздел 4.42).
		return (id != shotId(player)) && (id != ammoId(player)) && ((summon == null) || (id != summon.getItemConsumeId())) && ((id < 6360) || (id > 6362));
	}

	/**
	 * Ценное для лавки — не дешевле порога своего уровня (HunterStoreMinPrices); у пачек (ресурсы, рецепты, заточки) — по цене всей пачки.
	 */
	static boolean isStoreGood(Player player, Item item)
	{
		final long price = item.getTemplate().getReferencePrice();
		return (item.isStackable() ? price * item.getCount() : price) >= storeMinPrice(player.getLevel());
	}

	static long storeMinPrice(int level)
	{
		final Map.Entry<Integer, Long> entry = PhantomHuntersConfig.HUNTER_STORE_MIN_PRICES.floorEntry(level);
		return entry == null ? Long.MAX_VALUE : entry.getValue();
	}

	/**
	 * Продать NPC за полцены (как у торговца).
	 * @return выручка
	 */
	private static long sell(Player player, Item item)
	{
		final long price = (item.getTemplate().getReferencePrice() / 2L) * item.getCount();
		if (item.isEquipped())
		{
			player.getInventory().unEquipItemInSlot(item.getLocationSlot());
		}
		if (!player.destroyItem(ItemProcessType.SELL, item, player, false))
		{
			return 0;
		}
		if (price > 0)
		{
			PhantomCompat.addAdena(player, ItemProcessType.SELL, price, player, false);
		}
		return price;
	}

	private static int itemFor(PhantomTemplate.Gear gear, int slot)
	{
		switch (slot)
		{
			case Inventory.PAPERDOLL_RHAND:
			{
				return gear.rhand;
			}
			case Inventory.PAPERDOLL_LHAND:
			{
				return gear.lhand;
			}
			case Inventory.PAPERDOLL_HEAD:
			{
				return gear.head;
			}
			case Inventory.PAPERDOLL_CHEST:
			{
				return gear.chest;
			}
			case Inventory.PAPERDOLL_LEGS:
			{
				return gear.legs;
			}
			case Inventory.PAPERDOLL_GLOVES:
			{
				return gear.gloves;
			}
			default:
			{
				return gear.feet;
			}
		}
	}

	private static int grade(int itemId)
	{
		final ItemTemplate item = itemId > 0 ? ItemData.getInstance().getTemplate(itemId) : null;
		return item == null ? 0 : item.getCrystalType().getLevel();
	}

	// ---------- Бой ----------

	/**
	 * AutoPlay и AutoUse: цель — мобы рядом, не отбивать чужих мобов, подбирать лут; два-три самых сильных атакующих скилла класса. Воин бьёт ещё и оружием; кастер без атакующих скиллов (хилер) — тоже.
	 */
	private static void setupCombat(Player player)
	{
		final List<Skill> attacks = new ArrayList<>();
		for (Skill skill : player.getAllSkills())
		{
			if (!skill.isActive() || skill.isToggle() || (skill.getItemConsumeCount() > 0) || (skill.getChargeConsumeCount() > 0) || !PhantomCompat.isAttackSkillTarget(skill))
			{
				continue;
			}

			if (skill.hasEffectType(EffectType.PHYSICAL_ATTACK, EffectType.MAGICAL_ATTACK, EffectType.HP_DRAIN, EffectType.DEATH_LINK, EffectType.PHYSICAL_ATTACK_HP_LINK))
			{
				attacks.add(skill);
			}
		}
		attacks.sort((a, b) -> Double.compare(PhantomCompat.skillPower(b), PhantomCompat.skillPower(a)));

		final boolean caster = player.isMageClass() && !attacks.isEmpty();
		final AutoUseSettingsHolder use = player.getAutoUseSettings();
		use.getAutoSkills().clear();
		use.getAutoActions().clear();
		use.resetSkillOrder();
		if (!caster)
		{
			use.getAutoActions().add(AUTO_ATTACK_ACTION);
		}
		for (int i = 0; i < Math.min(caster ? 3 : 2, attacks.size()); i++)
		{
			use.getAutoSkills().add(attacks.get(i).getId());
		}

		final AutoPlaySettingsHolder play = player.getAutoPlaySettings();
		play.setNextTargetMode(1); // мобы
		// Дальний поиск (LongRange, 1400): споты — по 1500 в радиусе; с ближним (600) охотник стоял и ждал, пока моб подойдёт (патч 0028).
		play.setShortRange(false);
		play.setRespectfulHunting(true);
		play.setPickup(true);
	}

	/**
	 * @param player охотник
	 * @return кастер: маг с атакующими скиллами в AutoUse — бьёт ими, а не оружием ({@link #setupCombat}). По классу, а не по автоатаке: её включает {@link #syncMelee}.
	 */
	static boolean isCaster(Player player)
	{
		return player.isMageClass() && !player.getAutoUseSettings().getAutoSkills().isEmpty();
	}

	/**
	 * @param player охотник
	 * @return моб, который бьёт его вплотную (ближе {@link #MELEE_RANGE}): кастовать бесполезно — удар сбивает каст ({@code AltGameCancelByHit}); {@code null} — никто
	 */
	static Monster meleeAttacker(Player player)
	{
		Monster nearest = null;
		double nearestDistance = Double.MAX_VALUE;
		for (Monster monster : World.getVisibleObjectsInRange(player, Monster.class, MELEE_RANGE))
		{
			if (monster.isAlikeDead() || (victimOf(monster) != player) || (!monster.isInCombat() && (monster.getAI().getIntention() != Intention.ATTACK)))
			{
				continue;
			}

			final double distance = player.calculateDistance2D(monster);
			if (distance < nearestDistance)
			{
				nearest = monster;
				nearestDistance = distance;
			}
		}
		return nearest;
	}

	/**
	 * Кого бьёт моб (патч 0034). В драке {@code AttackableAI} пишет цель только в ИИ ({@code getAI().getAttackTarget()}), а {@code getTarget()} моба
	 * остаётся пустым или старым: проверки «моб бьёт меня» по {@code getTarget()} почти всегда отвечали «нет» — охотник не отбивался от агро.
	 * @param monster моб
	 * @return кого он атакует; не атакует — его {@code getTarget()}
	 */
	static WorldObject victimOf(Monster monster)
	{
		final Creature attackTarget = PhantomCompat.attackTarget(monster);
		return (attackTarget != null) && (monster.getAI().getIntention() == Intention.ATTACK) ? attackTarget : monster.getTarget();
	}

	/**
	 * @param player охотник
	 * @return маны не хватает ни на один атакующий скилл из AutoUse
	 */
	static boolean outOfMana(Player player)
	{
		for (Integer skillId : player.getAutoUseSettings().getAutoSkills())
		{
			final Skill skill = player.getKnownSkill(skillId);
			if ((skill != null) && (player.getCurrentMp() >= (skill.getMpInitialConsume() + skill.getMpConsume())))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * На споте (патч 0028): маг, которого бьют вплотную, или без маны — с автоатакой, AutoPlay бьёт оружием; отбился — снова кастер.
	 * @param player охотник
	 */
	static void syncMelee(Player player)
	{
		if (!isCaster(player))
		{
			return;
		}

		final boolean melee = (meleeAttacker(player) != null) || outOfMana(player);
		final AutoUseSettingsHolder use = player.getAutoUseSettings();
		if (melee && !use.getAutoActions().contains(AUTO_ATTACK_ACTION))
		{
			use.getAutoActions().add(AUTO_ATTACK_ACTION);
		}
		else if (!melee && use.getAutoActions().contains(AUTO_ATTACK_ACTION))
		{
			use.getAutoActions().remove(AUTO_ATTACK_ACTION);
			if (player.getAI().getIntention() == Intention.ATTACK)
			{
				player.abortAttack();
				player.getAI().setIntentionActive();
			}
		}
	}
}
