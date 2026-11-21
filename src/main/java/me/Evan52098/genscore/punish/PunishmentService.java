package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.storage.Database;
import org.bukkit.Bukkit;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PunishmentService {
    public static final String TYPE_BAN = "BAN";
    public static final String TYPE_MUTE = "MUTE";
    public static final String TYPE_SHADOWMUTE = "SHADOWMUTE";
    public static final String TYPE_WARN = "WARN";
    public static final String TYPE_KICK = "KICK";
    public static final String TYPE_IPBAN = "IPBAN";

    private static final long MUTE_CACHE_REFRESH_MS = 30_000L;
    private static final MuteState NO_MUTE = new MuteState(null, null);

    private final Database db;
    private final Logger logger;
    private final Map<UUID, CachedMuteState> muteStateCache = new ConcurrentHashMap<>();

    private final Set<UUID> pendingMutes = ConcurrentHashMap.newKeySet();
    private final Set<UUID> refreshing = ConcurrentHashMap.newKeySet();

    public PunishmentService(Database db, Logger logger) {
        this.db = db;
        this.logger = logger;
    }

    public boolean isMutePending(UUID targetUuid) {
        return pendingMutes.contains(targetUuid);
    }

    public void pruneMuteStates() {
        long cutoff = System.currentTimeMillis() - 5 * 60_000L;
        muteStateCache.entrySet().removeIf(e -> e.getValue().cachedAt() < cutoff && Bukkit.getPlayer(e.getKey()) == null);
    }

    public CompletableFuture<PunishmentData> create(String type, UUID targetUuid, String targetName, UUID staffUuid, String staffName,
                                                      String reason, Long durationMs, String ipAddress, String category, int severity) {
        boolean isMuteType = TYPE_MUTE.equals(type) || TYPE_SHADOWMUTE.equals(type);
        if (isMuteType) {
            pendingMutes.add(targetUuid);
        }
        return clearExpiredConflicts(targetUuid, type).thenCompose(ignored -> db.async(c -> {
            long now = System.currentTimeMillis();
            Long expiresAt = durationMs == null ? null : now + durationMs;
            String shortId = generateShortId();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_punishments (short_id, target_uuid, target_name, type, reason, created_at, expires_at, staff_uuid, staff_name, active, ip_address, category, severity, remove_admin, remove_reason, removed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, '', '', NULL)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, shortId);
                ps.setString(2, targetUuid.toString());
                ps.setString(3, targetName);
                ps.setString(4, type);
                ps.setString(5, reason);
                ps.setLong(6, now);
                if (expiresAt == null) {
                    ps.setNull(7, Types.BIGINT);
                } else {
                    ps.setLong(7, expiresAt);
                }
                ps.setString(8, staffUuid == null ? null : staffUuid.toString());
                ps.setString(9, staffName);
                ps.setString(10, ipAddress == null ? "" : ipAddress);
                ps.setString(11, category == null ? "" : category);
                ps.setInt(12, severity);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
                return new PunishmentData(id, shortId, targetUuid, targetName, type, reason, now, expiresAt, staffUuid, staffName,
                        true, ipAddress == null ? "" : ipAddress, category == null ? "" : category, severity, "", "", null);
            }
        })).handle((created, ex) -> {
            if (isMuteType) {
                getActiveMuteState(targetUuid).whenComplete((state, error) -> pendingMutes.remove(targetUuid));
            }
            if (ex != null) {
                logger.log(Level.WARNING, "Failed to record punishment for " + targetName, ex);
                return null;
            }
            return created;
        });
    }

    private CompletableFuture<Void> clearExpiredConflicts(UUID targetUuid, String type) {
        List<String> family = TYPE_MUTE.equals(type) || TYPE_SHADOWMUTE.equals(type)
                ? List.of(TYPE_MUTE, TYPE_SHADOWMUTE)
                : TYPE_BAN.equals(type) ? List.of(TYPE_BAN) : List.of();
        if (family.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.async(c -> {
            long now = System.currentTimeMillis();
            List<Long> toRevoke = new ArrayList<>();
            for (String familyType : family) {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT id, expires_at FROM pvp_punishments WHERE target_uuid = ? AND type = ? AND active = 1")) {
                    ps.setString(1, targetUuid.toString());
                    ps.setString(2, familyType);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            long expiresAt = rs.getLong(2);
                            if (!rs.wasNull() && expiresAt <= now) {
                                toRevoke.add(rs.getLong(1));
                            }
                        }
                    }
                }
            }
            for (long id : toRevoke) {
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE pvp_punishments SET active = 0, remove_admin = 'system', remove_reason = 'auto-cleared: expired before a new punishment was recorded', removed_at = ? WHERE id = ?")) {
                    ps.setLong(1, now);
                    ps.setLong(2, id);
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    public CompletableFuture<Boolean> revoke(long punishmentId, String removedBy, String removeReason) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_punishments SET active = 0, remove_admin = ?, remove_reason = ?, removed_at = ? WHERE id = ? AND active = 1")) {
                ps.setString(1, removedBy);
                ps.setString(2, removeReason);
                ps.setLong(3, System.currentTimeMillis());
                ps.setLong(4, punishmentId);
                if (ps.executeUpdate() == 0) {
                    return null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT target_uuid, type FROM pvp_punishments WHERE id = ?")) {
                ps.setLong(1, punishmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : new String[0];
                }
            }
        }).thenCompose(row -> {
            if (row == null) {
                return CompletableFuture.completedFuture(false);
            }
            if (row.length == 2 && (TYPE_MUTE.equals(row[1]) || TYPE_SHADOWMUTE.equals(row[1]))) {
                return getActiveMuteState(UUID.fromString(row[0])).handle((state, error) -> true);
            }
            return CompletableFuture.completedFuture(true);
        });
    }

    public CompletableFuture<Boolean> revokeActiveOfType(UUID targetUuid, String type, String removedBy, String removeReason) {
        return getActive(targetUuid, type).thenCompose(existing -> {
            if (existing == null) {
                return CompletableFuture.completedFuture(false);
            }
            return revoke(existing.id(), removedBy, removeReason);
        });
    }

    public CompletableFuture<PunishmentData> getActive(UUID targetUuid, String type) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM pvp_punishments WHERE target_uuid = ? AND type = ? AND active = 1 ORDER BY id DESC LIMIT 20")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, type);
                try (ResultSet rs = ps.executeQuery()) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        PunishmentData row = map(rs);
                        if (row.expiresAt() == null || row.expiresAt() > now) {
                            return row;
                        }
                    }
                    return null;
                }
            }
        });
    }

    public record MuteState(PunishmentData mute, PunishmentData shadowMute) {
    }

    private record CachedMuteState(MuteState state, long cachedAt) {
    }

    public CompletableFuture<MuteState> getActiveMuteState(UUID targetUuid) {
        long now = System.currentTimeMillis();
        return getActive(targetUuid, TYPE_MUTE).thenCombine(getActive(targetUuid, TYPE_SHADOWMUTE), MuteState::new)
                .thenApply(state -> {
                    muteStateCache.merge(targetUuid, new CachedMuteState(state, now), (old, fresh) -> fresh.cachedAt() >= old.cachedAt() ? fresh : old);
                    return state;
                });
    }

    public MuteState cachedMuteState(UUID targetUuid) {
        CachedMuteState cached = muteStateCache.get(targetUuid);
        long now = System.currentTimeMillis();
        if ((cached == null || now - cached.cachedAt() >= MUTE_CACHE_REFRESH_MS) && refreshing.add(targetUuid)) {
            getActiveMuteState(targetUuid).whenComplete((state, error) -> {
                refreshing.remove(targetUuid);
                if (error != null) {
                    logger.log(Level.WARNING, "Failed to refresh the mute state of " + targetUuid, error);
                }
            });
        }
        if (cached == null) {
            return NO_MUTE;
        }
        PunishmentData mute = cached.state().mute();
        PunishmentData shadowMute = cached.state().shadowMute();
        if (mute != null && !mute.isCurrentlyInEffect()) {
            mute = null;
        }
        if (shadowMute != null && !shadowMute.isCurrentlyInEffect()) {
            shadowMute = null;
        }
        return mute == null && shadowMute == null ? NO_MUTE : new MuteState(mute, shadowMute);
    }

    public CompletableFuture<Integer> countOffenses(UUID targetUuid, Category category, int severity) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT active, remove_admin FROM pvp_punishments WHERE target_uuid = ? AND category = ? AND severity = ?")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, category.name());
                ps.setInt(3, severity);
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        if (rs.getInt(1) == 1 || "system".equals(rs.getString(2))) {
                            count++;
                        }
                    }
                    return count;
                }
            }
        });
    }

    public CompletableFuture<Integer> countCategoryOffenses(UUID targetUuid, Category category) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT active, remove_admin FROM pvp_punishments WHERE target_uuid = ? AND category = ?")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, category.name());
                try (ResultSet rs = ps.executeQuery()) {
                    int count = 0;
                    while (rs.next()) {
                        if (rs.getInt(1) == 1 || "system".equals(rs.getString(2))) {
                            count++;
                        }
                    }
                    return count;
                }
            }
        });
    }

    public CompletableFuture<PunishmentData> findById(long id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        });
    }

    public CompletableFuture<PunishmentData> findMostRecentWarn(UUID targetUuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM pvp_punishments WHERE target_uuid = ? AND type = ? ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, TYPE_WARN);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        });
    }

    public CompletableFuture<PunishmentData> findByDisplayId(String displayId) {
        String shortId = displayId.trim().replace("#", "").toUpperCase(Locale.ROOT);
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments WHERE short_id = ?")) {
                ps.setString(1, shortId);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? map(rs) : null;
                }
            }
        });
    }

    private static String generateShortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
    }

    public void backfillShortIds() {
        try {
            db.sync(c -> {
                List<Long> ids = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT id FROM pvp_punishments WHERE short_id IS NULL OR short_id = ''");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ids.add(rs.getLong(1));
                    }
                }
                for (long id : ids) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_punishments SET short_id = ? WHERE id = ?")) {
                        ps.setString(1, generateShortId());
                        ps.setLong(2, id);
                        ps.executeUpdate();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to backfill punishment short ids", e);
        }
    }

    public CompletableFuture<List<PunishmentData>> recentGlobal(int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments ORDER BY id DESC LIMIT ?")) {
                ps.setInt(1, limit);
                return mapAll(ps);
            }
        });
    }

    public CompletableFuture<List<PunishmentData>> activeBans(int limit) {
        long now = System.currentTimeMillis();
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM pvp_punishments WHERE type IN (?, ?) AND active = 1 AND (expires_at IS NULL OR expires_at > ?) ORDER BY created_at DESC LIMIT ?")) {
                ps.setString(1, TYPE_BAN);
                ps.setString(2, TYPE_IPBAN);
                ps.setLong(3, now);
                ps.setInt(4, limit);
                return mapAll(ps);
            }
        });
    }

    public CompletableFuture<List<PunishmentData>> history(UUID targetUuid, int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments WHERE target_uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, targetUuid.toString());
                ps.setInt(2, limit);
                return mapAll(ps);
            }
        });
    }

    public CompletableFuture<List<PunishmentData>> byStaff(UUID staffUuid, int limit) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments WHERE staff_uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, staffUuid.toString());
                ps.setInt(2, limit);
                return mapAll(ps);
            }
        });
    }

    public CompletableFuture<List<PunishmentData>> searchByReason(String query, int limit) {
        String needle = query.trim().toLowerCase(Locale.ROOT);
        return db.async(c -> {
            List<PunishmentData> matches = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_punishments ORDER BY id DESC LIMIT 2000");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next() && matches.size() < limit) {
                    PunishmentData row = map(rs);
                    if (row.reason() != null && row.reason().toLowerCase(Locale.ROOT).contains(needle)) {
                        matches.add(row);
                    }
                }
            }
            return matches;
        });
    }

    public void recordLogin(UUID playerUuid, String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return;
        }
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pvp_login_ips (uuid, ip, last_seen) VALUES (?, ?, ?)")) {
                ps.setString(1, playerUuid.toString());
                ps.setString(2, ipAddress);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to record login IP for " + playerUuid, ex);
            return null;
        });
    }

    public void purgeOldLogins(int retentionDays) {
        long cutoff = System.currentTimeMillis() - (long) retentionDays * 86_400_000L;
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_login_ips WHERE last_seen < ?")) {
                ps.setLong(1, cutoff);
                ps.executeUpdate();
            }
        }).exceptionally(ex -> {
            logger.log(Level.WARNING, "Failed to purge old login IP records", ex);
            return null;
        });
    }

    public CompletableFuture<String> mostRecentIp(UUID playerUuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT ip FROM pvp_login_ips WHERE uuid = ? ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : null;
                }
            }
        });
    }

    public CompletableFuture<Set<UUID>> findAltAccounts(UUID playerUuid) {
        return db.async(c -> {
            Set<String> ips = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT ip FROM pvp_login_ips WHERE uuid = ?")) {
                ps.setString(1, playerUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        ips.add(rs.getString(1));
                    }
                }
            }
            Set<UUID> alts = new HashSet<>();
            for (String ip : ips) {
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pvp_login_ips WHERE ip = ?")) {
                    ps.setString(1, ip);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            UUID other = UUID.fromString(rs.getString(1));
                            if (!other.equals(playerUuid)) {
                                alts.add(other);
                            }
                        }
                    }
                }
            }
            return alts;
        });
    }

    public CompletableFuture<Set<UUID>> findAccountsByIp(String ipAddress, UUID excludeUuid) {
        return db.async(c -> {
            Set<UUID> accounts = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pvp_login_ips WHERE ip = ?")) {
                ps.setString(1, ipAddress);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID other = UUID.fromString(rs.getString(1));
                        if (!other.equals(excludeUuid)) {
                            accounts.add(other);
                        }
                    }
                }
            }
            return accounts;
        });
    }

    public CompletableFuture<Boolean> isAltBanExempt(UUID targetUuid) {
        return findAltBanExemption(targetUuid).thenApply(e -> e != null);
    }

    public CompletableFuture<AltExemption> findAltBanExemption(UUID targetUuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_alt_exemptions WHERE target_uuid = ?")) {
                ps.setString(1, targetUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapExemption(rs) : null;
                }
            }
        });
    }

    public CompletableFuture<AltExemption> addAltBanExemption(UUID targetUuid, String targetName, UUID addedByUuid, String addedByName, String reason) {
        long now = System.currentTimeMillis();
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_alt_exemptions (target_uuid, target_name, added_by_uuid, added_by_name, reason, created_at) VALUES (?, ?, ?, ?, ?, ?) ON CONFLICT (target_uuid) DO UPDATE SET target_name = excluded.target_name, added_by_uuid = excluded.added_by_uuid, added_by_name = excluded.added_by_name, reason = excluded.reason, created_at = excluded.created_at")) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, targetName);
                ps.setString(3, addedByUuid.toString());
                ps.setString(4, addedByName);
                ps.setString(5, reason);
                ps.setLong(6, now);
                ps.executeUpdate();
            }
            return new AltExemption(targetUuid, targetName, addedByUuid, addedByName, reason, now);
        });
    }

    public CompletableFuture<List<AltExemption>> listAltBanExemptions() {
        return db.async(c -> {
            List<AltExemption> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_alt_exemptions ORDER BY created_at DESC");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(mapExemption(rs));
                }
            }
            return list;
        });
    }

    public CompletableFuture<Boolean> removeAltBanExemption(UUID targetUuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_alt_exemptions WHERE target_uuid = ?")) {
                ps.setString(1, targetUuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<PunishmentData> findActiveIpBan(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT * FROM pvp_punishments WHERE type = ? AND ip_address = ? AND active = 1 ORDER BY id DESC LIMIT 20")) {
                ps.setString(1, TYPE_IPBAN);
                ps.setString(2, ipAddress);
                try (ResultSet rs = ps.executeQuery()) {
                    long now = System.currentTimeMillis();
                    while (rs.next()) {
                        PunishmentData row = map(rs);
                        if (row.expiresAt() == null || row.expiresAt() > now) {
                            return row;
                        }
                    }
                    return null;
                }
            }
        });
    }

    private PunishmentData map(ResultSet rs) throws SQLException {
        long expiresAtRaw = rs.getLong("expires_at");
        Long expiresAt = rs.wasNull() ? null : expiresAtRaw;
        long removedAtRaw = rs.getLong("removed_at");
        Long removedAt = rs.wasNull() ? null : removedAtRaw;
        String staffUuidStr = rs.getString("staff_uuid");
        return new PunishmentData(
                rs.getLong("id"),
                rs.getString("short_id"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("type"),
                rs.getString("reason"),
                rs.getLong("created_at"),
                expiresAt,
                staffUuidStr == null ? null : UUID.fromString(staffUuidStr),
                rs.getString("staff_name"),
                rs.getInt("active") == 1,
                rs.getString("ip_address"),
                rs.getString("category"),
                rs.getInt("severity"),
                rs.getString("remove_admin"),
                rs.getString("remove_reason"),
                removedAt);
    }

    private List<PunishmentData> mapAll(PreparedStatement ps) throws SQLException {
        List<PunishmentData> list = new ArrayList<>();
        try (ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(map(rs));
            }
        }
        return list;
    }

    private AltExemption mapExemption(ResultSet rs) throws SQLException {
        String addedByUuid = rs.getString("added_by_uuid");
        return new AltExemption(
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                addedByUuid == null ? null : UUID.fromString(addedByUuid),
                rs.getString("added_by_name"),
                rs.getString("reason"),
                rs.getLong("created_at"));
    }
}
