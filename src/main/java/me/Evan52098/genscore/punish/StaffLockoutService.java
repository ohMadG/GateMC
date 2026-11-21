package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

public final class StaffLockoutService {
    public record Lockout(long id, String staffName, UUID staffUuid, int actionCount, int windowSeconds, long trippedAt) {
        public String displayId() {
            return "#" + id;
        }
    }

    private final Database db;
    private final boolean enabled;
    private final int windowSeconds;
    private final int maxActions;
    private final PunishWebhook webhook;
    private final ConcurrentHashMap<String, Deque<Long>> recentActions = new ConcurrentHashMap<>();
    private volatile BiConsumer<String, Lockout> trippedListener;

    public StaffLockoutService(Database db, boolean enabled, int windowSeconds, int maxActions, PunishWebhook webhook) {
        this.db = db;
        this.enabled = enabled;
        this.windowSeconds = Math.max(1, windowSeconds);
        this.maxActions = Math.max(1, maxActions);
        this.webhook = webhook;
    }

    public void onTripped(BiConsumer<String, Lockout> listener) {
        this.trippedListener = listener;
    }

    public CompletableFuture<Boolean> checkAndRecord(String staffName, UUID staffUuid) {
        if (!enabled) {
            return CompletableFuture.completedFuture(true);
        }
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pvp_staff_lockouts WHERE staff_name = ? AND cleared = 0 LIMIT 1")) {
                ps.setString(1, staffName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        }).thenCompose(alreadyLockedOut -> {
            if (alreadyLockedOut) {
                return CompletableFuture.completedFuture(false);
            }
            return recordAndMaybeTrip(staffName, staffUuid);
        });
    }

    private CompletableFuture<Boolean> recordAndMaybeTrip(String staffName, UUID staffUuid) {
        long now = System.currentTimeMillis();
        long windowMillis = windowSeconds * 1000L;
        Deque<Long> deque = recentActions.computeIfAbsent(staffName, ignored -> new ArrayDeque<>());
        int countAfter;
        synchronized (deque) {
            while (!deque.isEmpty() && now - deque.peekFirst() > windowMillis) {
                deque.pollFirst();
            }
            deque.addLast(now);
            countAfter = deque.size();
        }
        if (countAfter < maxActions) {
            return CompletableFuture.completedFuture(true);
        }
        synchronized (deque) {
            deque.clear();
        }
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_staff_lockouts (staff_name, staff_uuid, action_count, window_seconds, tripped_at, cleared, cleared_by, cleared_at) VALUES (?, ?, ?, ?, ?, 0, '', NULL)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, staffName);
                ps.setString(2, staffUuid == null ? null : staffUuid.toString());
                ps.setInt(3, countAfter);
                ps.setInt(4, windowSeconds);
                ps.setLong(5, now);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
                return new Lockout(id, staffName, staffUuid, countAfter, windowSeconds, now);
            }
        }).thenApply(lockout -> {
            if (webhook != null) {
                webhook.staffLockout(staffName, countAfter, windowSeconds);
            }
            if (trippedListener != null) {
                trippedListener.accept(staffName, lockout);
            }
            return false;
        });
    }

    public CompletableFuture<List<Lockout>> listActive() {
        return db.async(c -> {
            List<Lockout> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_staff_lockouts WHERE cleared = 0 ORDER BY tripped_at DESC LIMIT 100");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(map(rs));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Integer> clear(String staffName, String clearedBy) {
        recentActions.remove(staffName);
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_staff_lockouts SET cleared = 1, cleared_by = ?, cleared_at = ? WHERE staff_name = ? AND cleared = 0")) {
                ps.setString(1, clearedBy);
                ps.setLong(2, System.currentTimeMillis());
                ps.setString(3, staffName);
                return ps.executeUpdate();
            }
        }).thenApply(count -> {
            if (count > 0 && webhook != null) {
                webhook.staffLockoutCleared(staffName, clearedBy);
            }
            return count;
        });
    }

    private Lockout map(ResultSet rs) throws SQLException {
        String staffUuidStr = rs.getString("staff_uuid");
        return new Lockout(rs.getLong("id"), rs.getString("staff_name"),
                staffUuidStr == null ? null : UUID.fromString(staffUuidStr),
                rs.getInt("action_count"), rs.getInt("window_seconds"), rs.getLong("tripped_at"));
    }
}
