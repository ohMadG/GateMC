package me.Evan52098.genscore.seasonpass;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One online player's pass state for the current season. Every method is synchronized: events for the same player
 * can arrive from their region thread, the playtime timer and hooks on other threads (Folia rule 5).
 */
final class PassPlayer {

    /** Challenge progress row: key = "season|d:2026-11-07|challengeId". */
    static final class Progress {
        long value;
        boolean done;

        Progress(long value, boolean done) {
            this.value = value;
            this.done = done;
        }
    }

    record Snapshot(long xp, boolean xpDirty, Map<String, long[]> rows) {
    }

    final UUID uuid;
    final String name;
    final String season;
    private boolean loaded;
    private long xp;
    private boolean xpDirty;
    private final Map<String, Progress> progress = new HashMap<>();
    private final Set<String> dirty = new HashSet<>();
    private final Set<String> claimed = new HashSet<>();
    /** Kill-farm guard: victim -> time the kill last counted. */
    private final Map<UUID, Long> lastKill = new HashMap<>();

    PassPlayer(UUID uuid, String name, String season) {
        this.uuid = uuid;
        this.name = name;
        this.season = season;
    }

    synchronized void loaded(long xp, Map<String, Progress> rows, Set<String> claims) {
        this.xp = Math.max(this.xp, xp);
        for (Map.Entry<String, Progress> e : rows.entrySet()) {
            progress.putIfAbsent(e.getKey(), e.getValue());
        }
        claimed.addAll(claims);
        loaded = true;
    }

    synchronized boolean isLoaded() {
        return loaded;
    }

    synchronized long xp() {
        return xp;
    }

    synchronized void addXp(long amount) {
        xp = Math.max(0, Math.min(Long.MAX_VALUE / 4, xp + amount));
        xpDirty = true;
    }

    synchronized void setXp(long value) {
        xp = Math.max(0, value);
        xpDirty = true;
    }

    synchronized Progress progress(String key) {
        Progress p = progress.get(key);
        return p == null ? new Progress(0, false) : new Progress(p.value, p.done);
    }

    /**
     * Adds to a challenge. Returns true exactly once: on the call that completes it (then the caller adds the XP).
     */
    synchronized boolean add(String key, long amount, long target) {
        if (!loaded || amount <= 0) {
            return false;
        }
        Progress p = progress.computeIfAbsent(key, k -> new Progress(0, false));
        if (p.done) {
            return false;
        }
        p.value = Math.min(target, p.value + amount);
        dirty.add(key);
        if (p.value >= target) {
            p.done = true;
            return true;
        }
        return false;
    }

    synchronized boolean hasClaimed(PassConfig.Track track, int tier) {
        return claimed.contains(track.name() + ":" + tier);
    }

    synchronized void markClaimed(PassConfig.Track track, int tier) {
        claimed.add(track.name() + ":" + tier);
    }

    synchronized boolean killCounts(UUID victim, long cooldownMillis, long now) {
        Long last = lastKill.get(victim);
        if (last != null && now - last < cooldownMillis) {
            return false;
        }
        lastKill.put(victim, now);
        return true;
    }

    /** Takes what needs saving and clears the dirty marks. Old periods not in {@code keep} are dropped after this save. */
    synchronized Snapshot drain(Set<String> keepPrefixes) {
        Map<String, long[]> rows = new HashMap<>();
        for (String key : dirty) {
            Progress p = progress.get(key);
            if (p != null) {
                rows.put(key, new long[]{p.value, p.done ? 1 : 0});
            }
        }
        dirty.clear();
        boolean wasDirty = xpDirty;
        xpDirty = false;
        progress.keySet().removeIf(k -> keepPrefixes.stream().noneMatch(k::startsWith));
        return new Snapshot(xp, wasDirty, rows);
    }

    /** A failed save puts its marks back so the next save retries. */
    synchronized void undrain(Snapshot s) {
        if (s.xpDirty()) {
            xpDirty = true;
        }
        for (Map.Entry<String, long[]> e : s.rows().entrySet()) {
            Progress p = progress.get(e.getKey());
            if (p == null) {
                progress.put(e.getKey(), new Progress(e.getValue()[0], e.getValue()[1] == 1));
            }
            dirty.add(e.getKey());
        }
    }
}
