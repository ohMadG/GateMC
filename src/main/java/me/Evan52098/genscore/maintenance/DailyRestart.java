package me.Evan52098.genscore.maintenance;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.worldrules.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.logging.Logger;

/**
 * The daily restart at a set time and zone, with a countdown, plus a manual restart started with {@code /reboot}.
 * Written fresh from HxCore's {@code maintenance.reboot}. Runs on the global thread only.
 *
 * <p>This only stops the server. Something outside it (the start script's loop, a panel, systemd) must start it again.
 */
final class DailyRestart {

    private static final List<Integer> DEFAULT_WARNINGS = List.of(900, 600, 300, 120, 60, 30, 10, 5, 4, 3, 2, 1);

    private final Logger logger;
    private volatile boolean enabled;
    private volatile @Nullable LocalTime time;
    private volatile ZoneId zone = ZoneId.systemDefault();
    private volatile String warning = "&4&lRESTART &8» &7The server restarts in &c{time} &7- it'll be back in under a minute!";
    private volatile List<Integer> warnings = DEFAULT_WARNINGS;

    /** Epoch millis of the next restart, or 0 for none. Changed on the global thread and by commands (volatile). */
    private volatile long target;
    private volatile boolean manual;
    private volatile long lastWarned = Long.MAX_VALUE;

    DailyRestart(Logger logger) {
        this.logger = logger;
    }

    void load(ConfigurationSection c) {
        enabled = c.getBoolean("enabled", false);
        try {
            time = LocalTime.parse(c.getString("time", "06:00"));
        } catch (DateTimeException e) {
            logger.warning("[maintenance] reboot.time must look like 06:00; the daily restart is off.");
            time = null;
        }
        try {
            zone = ZoneId.of(c.getString("zone", ZoneId.systemDefault().getId()));
        } catch (DateTimeException e) {
            logger.warning("[maintenance] reboot.zone is not a known time zone; using " + ZoneId.systemDefault().getId());
            zone = ZoneId.systemDefault();
        }
        warning = c.getString("warning", warning);
        List<Integer> w = c.getIntegerList("warning-seconds");
        List<Integer> sorted = new java.util.ArrayList<>(w.isEmpty() ? DEFAULT_WARNINGS : w);
        sorted.sort(java.util.Comparator.reverseOrder()); // tick() walks them from the largest down
        warnings = List.copyOf(sorted);
        if (!manual) {
            scheduleDaily();
        }
    }

    private void scheduleDaily() {
        LocalTime t = time;
        if (!enabled || t == null) {
            target = 0;
            return;
        }
        // At least two minutes away, so a server that starts at 05:59:30 does not restart at once.
        ZonedDateTime soonest = ZonedDateTime.now(zone).plusMinutes(2);
        ZonedDateTime next = soonest.with(t).withSecond(0).withNano(0);
        if (!next.isAfter(soonest)) {
            next = next.plusDays(1);
        }
        target = next.toInstant().toEpochMilli();
        lastWarned = Long.MAX_VALUE;
    }

    void start() {
        Tasks.global.timer(t -> tick(), 20, 20);
    }

    private void tick() {
        long at = target;
        if (at == 0) {
            return;
        }
        long left = (at - System.currentTimeMillis() + 999) / 1000;
        if (left <= 0) {
            target = 0;
            Text.broadcast(Text.legacy(warning.replace("{time}", "now")));
            logger.info("[maintenance] " + (manual ? "manual" : "daily") + " restart: stopping the server");
            Bukkit.shutdown();
            return;
        }
        for (int w : warnings) {
            if (left <= w && w < lastWarned) {
                lastWarned = w;
                if (left == w || left > w - 2) { // not every threshold at once when a short /reboot starts
                    Text.broadcast(Text.legacy(warning.replace("{time}", Text.duration(w))));
                }
                break;
            }
        }
    }

    /** Starts (or moves) a manual restart this many seconds from now. */
    void rebootIn(long seconds) {
        manual = true;
        lastWarned = Long.MAX_VALUE;
        target = System.currentTimeMillis() + seconds * 1000L;
        Tasks.global.run(() -> Text.broadcast(Text.legacy(warning.replace("{time}", Text.duration(seconds)))));
    }

    /** Cancels the countdown in progress. A cancelled daily restart moves to the next day. Returns false if none. */
    boolean cancel() {
        if (target == 0) {
            return false;
        }
        boolean wasManual = manual;
        manual = false;
        if (wasManual) {
            scheduleDaily();
        } else {
            long skipped = target;
            scheduleDaily();
            if (target != 0 && target - skipped < 60_000) {
                target += 24L * 3600 * 1000;
            }
        }
        return true;
    }

    /** Seconds to the next restart, or -1. */
    long secondsLeft() {
        long at = target;
        return at == 0 ? -1 : Math.max(0, (at - System.currentTimeMillis()) / 1000);
    }

    boolean isManual() {
        return manual;
    }
}
