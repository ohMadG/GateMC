package me.Evan52098.genscore.player;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Everything loaded for one online player: the core fields here plus each module's {@link PlayerSection} data.
 * Safe to read from any thread.
 */
public final class PlayerData {

    private final UUID uuid;
    private final long firstJoin;
    private final boolean firstTime;
    private final Map<PlayerSection<?>, Object> sections;

    private String name;
    private long lastSeen;
    private long storedPlaytimeMs;
    private long sessionStart;   // 0 while not in a session
    private boolean joined;
    private int claims;          // logins currently using this data (2 briefly when someone relogs over an open session)
    private long lastClaimAt;

    PlayerData(UUID uuid, String name, long firstJoin, long lastSeen, long playtimeMs, boolean firstTime,
               Map<PlayerSection<?>, Object> sections) {
        this.uuid = uuid;
        this.name = name;
        this.firstJoin = firstJoin;
        this.lastSeen = lastSeen;
        this.storedPlaytimeMs = playtimeMs;
        this.firstTime = firstTime;
        this.sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections)); // keeps registration order
    }

    public UUID uuid() {
        return uuid;
    }

    public synchronized String name() {
        return name;
    }

    public long firstJoin() {
        return firstJoin;
    }

    /** True on the player's very first join to this server. */
    public boolean isFirstTime() {
        return firstTime;
    }

    public synchronized long lastSeen() {
        return lastSeen;
    }

    /** Total playtime including the current session. */
    public synchronized long playtimeMs() {
        return storedPlaytimeMs + (sessionStart > 0 ? System.currentTimeMillis() - sessionStart : 0);
    }

    /** A module's data for this player. Throws if the section was never registered. */
    @SuppressWarnings("unchecked")
    public <T> T get(PlayerSection<T> section) {
        Object value = sections.get(section);
        if (value == null) {
            throw new IllegalStateException("Player section '" + section.id() + "' is not registered");
        }
        return (T) value;
    }

    // ------------------------------------------------------------ used by PlayerDataService

    Map<PlayerSection<?>, Object> sections() {
        return sections;
    }

    synchronized void claim() {
        claims++;
        lastClaimAt = System.currentTimeMillis();
    }

    /** Returns true when no login is using this data any more. */
    synchronized boolean release() {
        claims = Math.max(0, claims - 1);
        return claims == 0;
    }

    synchronized long lastClaimAt() {
        return lastClaimAt;
    }

    /** True once the player has fully joined (not just pre-loaded while connecting). */
    public synchronized boolean hasJoined() {
        return joined;
    }

    synchronized void startSession(String currentName) {
        long now = System.currentTimeMillis();
        name = currentName;
        lastSeen = now;
        sessionStart = now;
        joined = true;
    }

    synchronized void endSession() {
        long now = System.currentTimeMillis();
        if (sessionStart > 0) {
            storedPlaytimeMs += now - sessionStart;
            sessionStart = 0;
        }
        lastSeen = now;
    }

    /** Values written by the saver, taken together under the lock. */
    synchronized CoreSnapshot snapshot() {
        long now = System.currentTimeMillis();
        return new CoreSnapshot(name, firstJoin, sessionStart > 0 ? now : lastSeen, playtimeMs());
    }

    record CoreSnapshot(String name, long firstJoin, long lastSeen, long playtimeMs) {
    }
}
