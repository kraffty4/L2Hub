package org.l2jmobius.gameserver.managers;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.logging.Logger;

import org.l2jmobius.gameserver.entity.actor.Creature;
import org.l2jmobius.gameserver.entity.actor.instance.Monster;
import org.l2jmobius.gameserver.entity.actor.instance.GrandBoss;

/** Настраиваемые уровневые штрафы рейд-боссов и совместимость прежнего допуска эпиков. */
public final class RaidLevelAccess
{
    private static final Logger LOGGER = Logger.getLogger(RaidLevelAccess.class.getName());
    static final Map<String, int[]> FAMILIES = Map.ofEntries(
        Map.entry("EpicQueenAntLevel", new int[] {29001, 29002, 29003, 29004, 29005}),
        Map.entry("EpicCoreLevel", new int[] {29006, 29007, 29008, 29011}),
        Map.entry("EpicOrfenLevel", new int[] {29014, 29015, 29016, 29017, 29018}),
        Map.entry("EpicZakenLevel", new int[] {29022, 29176, 29023, 29024, 29026, 29027}),
        Map.entry("EpicBaiumLevel", new int[] {29020}),
        Map.entry("EpicAntharasLevel", new int[] {29019, 29066, 29067, 29068}),
        Map.entry("EpicValakasLevel", new int[] {29028}),
        Map.entry("EpicFrintezzaLevel", new int[] {29045, 29046, 29047}),
        Map.entry("EpicBelethLevel", new int[] {29118}),
        Map.entry("EpicBaylorLevel", new int[] {29099}),
        Map.entry("EpicEkimusLevel", new int[] {29150}),
        Map.entry("EpicTiatLevel", new int[] {29163, 29175}),
        Map.entry("EpicChaosGolemLevel", new int[] {25512}));

    private static final Set<Integer> EPIC_IDS = FAMILIES.values().stream().flatMapToInt(java.util.Arrays::stream).boxed().collect(Collectors.toUnmodifiableSet());

    record Settings(boolean enabled, boolean epicNoPenalty, boolean raidNoPenalty, Map<Integer, Integer> levels)
    {
        Settings
        {
            levels = Map.copyOf(levels);
        }
    }

    private RaidLevelAccess()
    {
    }

    static Settings parse(Properties properties)
    {
        final boolean enabled = Boolean.parseBoolean(properties.getProperty("EpicLevelAccessEnabled", "False").trim());
        final Map<Integer, Integer> levels = new HashMap<>();
        for (var family : FAMILIES.entrySet())
        {
            final String raw = properties.getProperty(family.getKey(), "0").trim();
            try
            {
                final int level = Integer.parseInt(raw);
                if ((level < 0) || (level > 99))
                {
                    throw new IllegalArgumentException("expected 0..99");
                }
                if (level > 0)
                {
                    for (int npcId : family.getValue())
                    {
                        levels.put(npcId, level);
                    }
                }
            }
            catch (IllegalArgumentException error)
            {
                LOGGER.warning("RaidLevelAccess: invalid " + family.getKey() + "=" + raw + "; native level retained.");
            }
        }
        return new Settings(enabled, Boolean.parseBoolean(properties.getProperty("EpicNoLevelPenalty", "False").trim()),
            Boolean.parseBoolean(properties.getProperty("RaidNoLevelPenalty", "False").trim()), levels);
    }

    static Settings load(Path file)
    {
        final Properties properties = new Properties();
        if (Files.isRegularFile(file))
        {
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8))
            {
                properties.load(reader);
            }
            catch (IOException | IllegalArgumentException error)
            {
                LOGGER.warning("RaidLevelAccess: could not read " + file + "; native levels retained: " + error.getMessage());
                return new Settings(false, false, false, Map.of());
            }
        }
        return parse(properties);
    }

    static int configuredLevel(int npcId, int nativeLevel, Settings settings)
    {
        return settings.enabled() ? settings.levels().getOrDefault(npcId, nativeLevel) : nativeLevel;
    }

    /** Использовать только в проверках рейдового проклятия и разницы уровней для дропа. */
    public static int restrictionLevel(Creature target)
    {
        final int nativeLevel = target.getLevel();
        final Settings settings = Holder.SETTINGS;
        if (!settings.enabled() || !target.isNpc())
        {
            return nativeLevel;
        }
        final var npc = target.asNpc();
        final Integer direct = settings.levels().get(npc.getId());
        if (direct != null)
        {
            return direct;
        }
        // Связанные миньоны используют тот же допуск, даже если у них другой уровень.
        if (target.isMonster())
        {
            Monster parent = target.asMonster().getLeader();
            for (int depth = 0; (parent != null) && (depth < 4); depth++)
            {
                final Integer inherited = settings.levels().get(parent.getId());
                if (inherited != null)
                {
                    return inherited;
                }
                parent = parent.getLeader();
            }
        }
        return nativeLevel;
    }

    /** Определяет только принадлежность к рейду/эпику; обычные монстры остаются штатными. */
    public static boolean penaltiesDisabled(Creature target)
    {
        return penaltiesDisabled(target, Holder.SETTINGS);
    }

    static boolean penaltiesDisabled(Creature target, Settings settings)
    {
        if ((target == null) || !target.isNpc() || (!settings.epicNoPenalty() && !settings.raidNoPenalty())) return false;
        Creature current = target;
        for (int depth = 0; (current != null) && (depth < 5); depth++)
        {
            final boolean epic = (current instanceof GrandBoss) || EPIC_IDS.contains(current.asNpc().getId());
            if ((settings.epicNoPenalty() && epic)
                || (settings.raidNoPenalty() && (epic || current.isRaid() || current.isRaidMinion()))) return true;
            current = current.isMonster() ? current.asMonster().getLeader() : null;
        }
        return false;
    }

    /** Только предикат наказания за разницу уровней, с сохранением прежнего допуска85. */
    public static boolean exceedsCurseLevel(Creature target, int attackerLevel, int allowance)
    {
        return !penaltiesDisabled(target) && ((long) attackerLevel > ((long) restrictionLevel(target) + allowance));
    }

    public static int dropLevelDifference(Creature victim, int killerLevel)
    {
        return penaltiesDisabled(victim) ? 0 : restrictionLevel(victim) - killerLevel;
    }

    /** Старый proxy эпиков не меняет XP; новые явные выключатели убирают уровневой штраф. */
    public static int experienceLevelDifference(Creature victim, int characterLevel)
    {
        return penaltiesDisabled(victim) ? 0 : characterLevel - victim.getLevel();
    }

    /** Входная зона проклятия АК не зависит от агро и проверяется отдельно. */
    public static int minimumZoneLevel(org.l2jmobius.gameserver.entity.zone.ZoneType zone, int nativeMinimum)
    {
        if (!(zone instanceof org.l2jmobius.gameserver.entity.zone.type.EffectZone effect)
            || !"Queen Ant Curse Zone".equals(zone.getName()) || (effect.getSkillLevel(4515) < 1))
        {
            return nativeMinimum;
        }
        final Settings settings = Holder.SETTINGS;
        if (settings.epicNoPenalty() || settings.raidNoPenalty()) return Integer.MAX_VALUE;
        final Integer configured = settings.enabled() ? settings.levels().get(29001) : null;
        return configured == null ? nativeMinimum : configured + 9;
    }

    private static final class Holder
    {
        private static final Settings SETTINGS = load(Path.of("config/Custom/EpicLevelAccess.ini"));
    }
}
