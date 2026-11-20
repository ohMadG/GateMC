package me.Evan52098.genscore.cosmetics;

import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * One online player's cosmetics: the active one per category, the stored unlocks (crate rewards, admin grants), the
 * kill count and the home icons. Thread-safe: every method is synchronized (changed on the player's thread, read by
 * the saver and by other players' threads, e.g. the victim's thread when this player gets a kill).
 *
 * <p>Unlocks are not written by the player-data save: grants and revokes go straight to the database
 * ({@link CosmeticsStore}), so a crash never loses a crate reward and a save never brings back a revoked one.
 */
final class PlayerCosmetics {

    private final Map<CosmeticCategory, String> active = new EnumMap<>(CosmeticCategory.class);
    private final Set<String> unlocks = new HashSet<>();
    private final Map<String, String> homeIcons = new LinkedHashMap<>();
    private int kills;
    private boolean dirty;

    static String unlockKey(CosmeticCategory category, String id) {
        return category.key() + ":" + id;
    }

    // ------------------------------------------------------------------ active

    synchronized @Nullable String active(CosmeticCategory category) {
        return active.get(category);
    }

    synchronized void setActive(CosmeticCategory category, @Nullable String id) {
        if (id == null) {
            if (active.remove(category) != null) {
                dirty = true;
            }
        } else if (!id.equals(active.put(category, id))) {
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ unlocks

    synchronized boolean hasUnlock(CosmeticCategory category, String id) {
        return unlocks.contains(unlockKey(category, id));
    }

    synchronized void addUnlock(CosmeticCategory category, String id) {
        unlocks.add(unlockKey(category, id));
    }

    synchronized void removeUnlock(CosmeticCategory category, String id) {
        unlocks.remove(unlockKey(category, id));
    }

    // ------------------------------------------------------------------ kills

    synchronized int kills() {
        return kills;
    }

    synchronized int addKill() {
        if (kills < Integer.MAX_VALUE) {
            kills++;
            dirty = true;
        }
        return kills;
    }

    synchronized void setKills(int value) {
        kills = Math.max(0, value);
        dirty = true;
    }

    // ------------------------------------------------------------------ home icons

    synchronized @Nullable String homeIcon(String home) {
        return homeIcons.get(home);
    }

    synchronized int homeIconCount() {
        return homeIcons.size();
    }

    synchronized java.util.List<String> homeIconNames() {
        return new java.util.ArrayList<>(homeIcons.keySet());
    }

    synchronized boolean hasHomeIcon(String home) {
        return homeIcons.containsKey(home);
    }

    synchronized void setHomeIcon(String home, @Nullable String material) {
        if (material == null) {
            if (homeIcons.remove(home) != null) {
                dirty = true;
            }
        } else if (!material.equals(homeIcons.put(home, material))) {
            dirty = true;
        }
    }

    // ------------------------------------------------------------------ persistence

    /** What the saver writes, or null when nothing changed since the last save. Clears the dirty flag. */
    synchronized @Nullable Snapshot takeChanges() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return new Snapshot(new EnumMap<>(active), kills, new LinkedHashMap<>(homeIcons));
    }

    /** A failed save puts the flag back so the next autosave retries. */
    synchronized void markDirty() {
        dirty = true;
    }

    /** Used only by the loader before the object is shared. */
    void loadActive(CosmeticCategory category, String id) {
        active.put(category, id);
    }

    void loadUnlock(CosmeticCategory category, String id) {
        unlocks.add(unlockKey(category, id));
    }

    void loadKills(int value) {
        kills = Math.max(0, value);
    }

    void loadHomeIcon(String home, String material) {
        homeIcons.put(home, material);
    }

    record Snapshot(Map<CosmeticCategory, String> active, int kills, Map<String, String> homeIcons) {
    }
}
