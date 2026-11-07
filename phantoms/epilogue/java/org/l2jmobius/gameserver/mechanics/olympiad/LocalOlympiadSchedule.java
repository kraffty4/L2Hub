package org.l2jmobius.gameserver.mechanics.olympiad;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collection;
import java.util.Properties;

import org.l2jmobius.gameserver.config.OlympiadConfig;

/** Native24-hour/all-day configuration means one continuous competition window per weekly cycle. */
public final class LocalOlympiadSchedule
{
    static final long DAY = 86400000L;
    static final PendingStore STORE = new PendingStore(Path.of("data/L2HubOlympiadCycle.properties"));
    private LocalOlympiadSchedule() { }

    static boolean continuous(long duration, boolean customDays, Collection<Integer> days)
    {
        if (duration < DAY) return false;
        for (int day = 1; customDays && day <= 7; day++) if (!days.contains(day)) return false;
        return true;
    }
    static boolean continuous()
    {
        return continuous(OlympiadConfig.OLYMPIAD_CPERIOD, OlympiadConfig.OLYMPIAD_USE_CUSTOM_PERIOD_SETTINGS, OlympiadConfig.OLYMPIAD_COMPETITION_DAYS);
    }
    static long validationEnd(long now, long duration) { return Math.addExact(now, Math.max(0, duration)); }
    static long clampValidationEnd(long storedEnd, long now, long duration)
    {
        return storedEnd > now ? Math.min(storedEnd, validationEnd(now, duration)) : storedEnd;
    }
    static boolean allFinished(Collection<Boolean> matches) { return matches.stream().allMatch(Boolean::booleanValue); }

    static void pending(int cycle, long end) { STORE.pending(cycle, end); }
    static boolean pending(int cycle) { return STORE.cycle == cycle; }
    static long pendingValidationEnd() { return STORE.validationEnd; }
    static void finished(int cycle) { STORE.finished(cycle); }

    /** Mutable coordination state, not an INI override or replaceable data resource. Preserve on upgrades. */
    static final class PendingStore
    {
        final Path path;
        volatile int cycle, completedCycle;
        volatile long validationEnd;
        PendingStore(Path path)
        {
            this.path = path;
            if (!Files.exists(path)) return;
            Properties properties = new Properties();
            try (InputStream in = Files.newInputStream(path))
            {
                properties.load(in);
                cycle = Integer.parseInt(properties.getProperty("PendingCycle", "0"));
                completedCycle = Integer.parseInt(properties.getProperty("CompletedCycle", "0"));
                validationEnd = Long.parseLong(properties.getProperty("ValidationEnd", "0"));
            }
            catch (IOException | NumberFormatException error) { throw new IllegalStateException("Cannot read Olympiad cycle state: " + path, error); }
        }
        synchronized void pending(int cycle, long end)
        {
            this.cycle = cycle; validationEnd = end; save();
        }
        synchronized void finished(int cycle)
        {
            if (this.cycle != cycle) return;
            this.cycle = 0; validationEnd = 0; completedCycle = cycle; save();
        }
        private void save()
        {
            Properties p = new Properties(); p.setProperty("PendingCycle", Integer.toString(cycle));
            p.setProperty("CompletedCycle", Integer.toString(completedCycle)); p.setProperty("ValidationEnd", Long.toString(validationEnd));
            Path temp = path.resolveSibling(path.getFileName() + ".tmp");
            try
            {
                Files.createDirectories(path.toAbsolutePath().getParent());
                try (OutputStream out = Files.newOutputStream(temp)) { p.store(out, "Local Olympiad result finalization"); }
                try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
            }
            catch (IOException error) { throw new IllegalStateException("Cannot save Olympiad cycle state: " + path, error); }
        }
    }
}
