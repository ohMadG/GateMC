package me.Evan52098.genscore.util;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Per-player cooldowns, safe from any thread. In memory only (cleared on restart). */
public final class Cooldowns {

    private final Map<String, Long> until = new ConcurrentHashMap<>();

    private static String key(UUID player, String action) {
        return player + ":" + action;
    }

    /** Milliseconds left, or 0 if ready. */
    public long remaining(UUID player, String action) {
        Long end = until.get(key(player, action));
        if (end == null) {
            return 0;
        }
        long left = end - System.currentTimeMillis();
        if (left <= 0) {
            until.remove(key(player, action), end);
            return 0;
        }
        return left;
    }

    public void start(UUID player, String action, long millis) {
        until.put(key(player, action), System.currentTimeMillis() + millis);
    }

    /** Starts the cooldown and returns true if it was ready; returns false (and changes nothing) if not. */
    public boolean tryStart(UUID player, String action, long millis) {
        long now = System.currentTimeMillis();
        boolean[] started = {false};
        until.compute(key(player, action), (k, end) -> {
            if (end != null && end > now) {
                return end;
            }
            started[0] = true;
            return now + millis;
        });
        return started[0];
    }

    public void clear(UUID player, String action) {
        until.remove(key(player, action));
    }

    /** "1m 05s" style text for a remaining time. */
    public static String format(long millis) {
        long seconds = Math.max(1, (millis + 999) / 1000);
        long minutes = seconds / 60;
        long hours = minutes / 60;
        if (hours > 0) {
            return hours + "h " + String.format("%02dm", minutes % 60);
        }
        if (minutes > 0) {
            return minutes + "m " + String.format("%02ds", seconds % 60);
        }
        return seconds + "s";
    }
}
