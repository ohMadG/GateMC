package me.Evan52098.genscore.store;

import me.Evan52098.genscore.leaderboards.LeaderboardsModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * GensCore's copy of the Tebex payments it has seen, so totals and top lists are SQL queries instead of walking the
 * whole Tebex history every refresh. Also the one-time markers ({@code pvp_store_events}) that make goal rewards and
 * parties single-winner, and the admin adjustments to the monthly goal. All methods block: database threads only.
 */
final class StorePayments {

    static final String COMPLETE = "complete";

    private final Database db;

    StorePayments(Database db) {
        this.db = db;
    }

    void migrate(Logger log) throws SQLException {
        Migrations.apply(db, "store", log,
                "CREATE TABLE IF NOT EXISTS pvp_store_payments (id TEXT PRIMARY KEY, uuid TEXT NOT NULL, name TEXT NOT NULL, "
                        + "amount_cents BIGINT NOT NULL, currency TEXT NOT NULL, status TEXT NOT NULL, created_at BIGINT NOT NULL, "
                        + "seen_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_store_payments_time ON pvp_store_payments (status, created_at);"
                        + "CREATE INDEX IF NOT EXISTS pvp_store_payments_uuid ON pvp_store_payments (uuid);"
                        + "CREATE TABLE IF NOT EXISTS pvp_store_events (kind TEXT NOT NULL, ref TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL, PRIMARY KEY (kind, ref));"
                        + "CREATE TABLE IF NOT EXISTS pvp_store_goal_adjust (" + db.autoIdColumn("id") + ", month TEXT NOT NULL, "
                        + "cents BIGINT NOT NULL, by_name TEXT NOT NULL, created_at BIGINT NOT NULL)");
    }

    /**
     * Stores one page of payments: new ones are inserted, known ones get their status (refunds, chargebacks) and name
     * updated. Returns how many were new. One transaction per page.
     */
    int upsert(List<TebexClient.Payment> page) throws SQLException {
        if (page.isEmpty()) {
            return 0;
        }
        long now = System.currentTimeMillis();
        return db.sync(c -> Database.transaction(c, tx -> {
            Set<String> known = new HashSet<>();
            StringBuilder in = new StringBuilder();
            for (int i = 0; i < page.size(); i++) {
                in.append(i == 0 ? "?" : ", ?");
            }
            try (PreparedStatement ps = tx.prepareStatement("SELECT id FROM pvp_store_payments WHERE id IN (" + in + ")")) {
                for (int i = 0; i < page.size(); i++) {
                    ps.setString(i + 1, page.get(i).id());
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        known.add(rs.getString(1));
                    }
                }
            }
            int added = 0;
            try (PreparedStatement ins = tx.prepareStatement(
                    "INSERT INTO pvp_store_payments (id, uuid, name, amount_cents, currency, status, created_at, seen_at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING");
                 PreparedStatement upd = tx.prepareStatement(
                         "UPDATE pvp_store_payments SET status = ?, name = ? WHERE id = ?")) {
                for (TebexClient.Payment p : page) {
                    if (known.contains(p.id())) {
                        upd.setString(1, p.status());
                        upd.setString(2, p.name());
                        upd.setString(3, p.id());
                        upd.addBatch();
                    } else {
                        ins.setString(1, p.id());
                        ins.setString(2, String.valueOf(p.uuid()));
                        ins.setString(3, p.name());
                        ins.setLong(4, p.amountCents());
                        ins.setString(5, p.currency());
                        ins.setString(6, p.status());
                        ins.setLong(7, p.createdAt());
                        ins.setLong(8, now);
                        ins.addBatch();
                        added++;
                    }
                }
                ins.executeBatch();
                upd.executeBatch();
            }
            return added;
        }));
    }

    long count() throws SQLException {
        return db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_store_payments");
                 ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        });
    }

    /** Completed payments in a calendar month (in the goal's time zone) plus admin adjustments. */
    long monthTotal(YearMonth month, ZoneId zone) throws SQLException {
        long from = month.atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        long to = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant().toEpochMilli();
        return db.sync(c -> {
            long total = 0;
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(amount_cents), 0) FROM pvp_store_payments WHERE status = ? AND created_at >= ? AND created_at < ?")) {
                ps.setString(1, COMPLETE);
                ps.setLong(2, from);
                ps.setLong(3, to);
                try (ResultSet rs = ps.executeQuery()) {
                    total += rs.next() ? rs.getLong(1) : 0;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(cents), 0) FROM pvp_store_goal_adjust WHERE month = ?")) {
                ps.setString(1, month.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    total += rs.next() ? rs.getLong(1) : 0;
                }
            }
            return Math.max(0, total);
        });
    }

    long totalSince(long fromMillis) throws SQLException {
        return db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT COALESCE(SUM(amount_cents), 0) FROM pvp_store_payments WHERE status = ? AND created_at >= ?")) {
                ps.setString(1, COMPLETE);
                ps.setLong(2, fromMillis);
                try (ResultSet rs = ps.executeQuery()) {
                    return Math.max(0, rs.next() ? rs.getLong(1) : 0L);
                }
            }
        });
    }

    long markerTime(String kind, String ref) throws SQLException {
        return db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT created_at FROM pvp_store_events WHERE kind = ? AND ref = ?")) {
                ps.setString(1, kind);
                ps.setString(2, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getLong(1) : -1L;
                }
            }
        });
    }

    void adjust(YearMonth month, long cents, String by) throws SQLException {
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_store_goal_adjust (month, cents, by_name, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, month.toString());
                ps.setLong(2, cents);
                ps.setString(3, by);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        });
    }

    /** All-time completed spending per player, highest first. Names in {@code exclude} (lowercase) are skipped. */
    List<LeaderboardsModule.Entry> top(Connection c, int limit, Set<String> exclude) throws SQLException {
        List<LeaderboardsModule.Entry> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT uuid, MAX(name), SUM(amount_cents) AS total FROM pvp_store_payments WHERE status = ? "
                        + "GROUP BY uuid ORDER BY total DESC LIMIT ?")) {
            ps.setString(1, COMPLETE);
            ps.setInt(2, limit + exclude.size());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next() && out.size() < limit) {
                    String name = rs.getString(2);
                    if (name == null || exclude.contains(name.toLowerCase(Locale.ROOT))) {
                        continue;
                    }
                    UUID uuid;
                    try {
                        uuid = UUID.fromString(rs.getString(1));
                    } catch (IllegalArgumentException e) {
                        continue;
                    }
                    out.add(new LeaderboardsModule.Entry(uuid, name, rs.getLong(3)));
                }
            }
        }
        return out;
    }

    /**
     * Claims a one-time marker. True for exactly one caller, however many threads or servers race (checklist 2):
     * the primary key decides.
     */
    boolean claim(String kind, String ref) throws SQLException {
        return db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_store_events (kind, ref, created_at) VALUES (?, ?, ?) ON CONFLICT (kind, ref) DO NOTHING")) {
                ps.setString(1, kind);
                ps.setString(2, ref);
                ps.setLong(3, System.currentTimeMillis());
                return ps.executeUpdate() == 1;
            }
        });
    }

    boolean has(String kind, String ref) throws SQLException {
        return db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM pvp_store_events WHERE kind = ? AND ref = ?")) {
                ps.setString(1, kind);
                ps.setString(2, ref);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        });
    }
}
