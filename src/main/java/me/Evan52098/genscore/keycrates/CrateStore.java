package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Database side of key crates. Every reward is a row in {@code pvp_keycrate_opens} that goes PENDING -&gt; DONE with a
 * conditional UPDATE before anything is given (checklist 2 and 7): a reward can be delivered once, by whoever wins
 * the row (the end of the animation, or the join sweep after a disconnect or crash), never twice and never lost.
 * Keys and items that did not fit wait in {@code pvp_keycrate_claims} ({@code /claim}).
 */
final class CrateStore {

    static final String KEY = "KEY";
    static final String ITEM = "ITEM";

    record OpenRow(long id, String crate, String reward) {
    }

    /** Rows created by one open; {@code refunded} keys went to the claim box because nothing was left to win. */
    record OpenBatch(List<OpenRow> rows, int refunded) {
    }

    record ClaimRow(long id, String kind, String crate, int amount, @Nullable String item) {
    }

    /** Take {@code take} of a claim row; the rest ({@code row.amount - take}) goes back as a new row. */
    record ClaimTake(ClaimRow row, int take) {
    }

    record BlockRow(String world, int x, int y, int z, String crate) {
    }

    private final Database db;

    CrateStore(Database db, Logger logger) throws SQLException {
        this.db = db;
        Migrations.apply(db, "keycrates", logger,
                "CREATE TABLE IF NOT EXISTS pvp_keycrate_opens ("
                        + db.autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, "
                        + "crate TEXT NOT NULL, "
                        + "reward TEXT NOT NULL, "
                        + "state TEXT NOT NULL, "
                        + "source TEXT NOT NULL, "
                        + "batch TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL, "
                        + "delivered_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_keycrate_opens_uuid ON pvp_keycrate_opens (uuid, state);"
                        + "CREATE INDEX IF NOT EXISTS pvp_keycrate_opens_batch ON pvp_keycrate_opens (batch);"
                        + "CREATE TABLE IF NOT EXISTS pvp_keycrate_claims ("
                        + db.autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, "
                        + "kind TEXT NOT NULL, "
                        + "crate TEXT NOT NULL, "
                        + "amount INTEGER NOT NULL, "
                        + "item TEXT, "
                        + "reason TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL, "
                        + "claimed INTEGER NOT NULL DEFAULT 0, "
                        + "claimed_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_keycrate_claims_uuid ON pvp_keycrate_claims (uuid, claimed);"
                        + "CREATE TABLE IF NOT EXISTS pvp_keycrate_owned ("
                        + "uuid TEXT NOT NULL, crate TEXT NOT NULL, reward TEXT NOT NULL, at BIGINT NOT NULL, "
                        + "PRIMARY KEY (uuid, crate, reward));"
                        + "CREATE TABLE IF NOT EXISTS pvp_keycrate_blocks ("
                        + "world TEXT NOT NULL, x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, crate TEXT NOT NULL, "
                        + "PRIMARY KEY (world, x, y, z));"
                        + "CREATE TABLE IF NOT EXISTS pvp_keycrate_progress ("
                        + "uuid TEXT PRIMARY KEY, minutes INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS pvp_keycrate_welcome ("
                        + "uuid TEXT PRIMARY KEY, at BIGINT NOT NULL)");
    }

    Database db() {
        return db;
    }

    // ------------------------------------------------------------------ opens

    /**
     * Rolls {@code count} rewards and writes them as PENDING rows, all in one transaction (checklist 7). The keys were
     * already taken and the player file saved before this is called (checklist 1 and 12). For no-duplicates crates the
     * rewards already won are skipped; keys with nothing left to win go to the claim box in the same transaction.
     * With {@code welcomeOnce}, the spin happens only if this is the first welcome row for the player (checklist 2).
     */
    CompletableFuture<OpenBatch> createOpens(UUID uuid, CrateConfig.Crate crate, int count, String source, boolean welcomeOnce) {
        String batch = UUID.randomUUID().toString();
        return db.async(c -> Database.transaction(c, tx -> {
            long now = System.currentTimeMillis();
            if (welcomeOnce) {
                try (PreparedStatement ps = tx.prepareStatement(
                        "INSERT INTO pvp_keycrate_welcome (uuid, at) VALUES (?, ?) ON CONFLICT (uuid) DO NOTHING")) {
                    ps.setString(1, uuid.toString());
                    ps.setLong(2, now);
                    if (ps.executeUpdate() != 1) {
                        return new OpenBatch(List.of(), 0);
                    }
                }
            }
            Set<String> owned = crate.noDuplicates() ? owned(tx, uuid, crate.id()) : new HashSet<>();
            int refunded = 0;
            try (PreparedStatement ins = tx.prepareStatement(
                    "INSERT INTO pvp_keycrate_opens (uuid, crate, reward, state, source, batch, created_at) VALUES (?, ?, ?, 'PENDING', ?, ?, ?)")) {
                for (int i = 0; i < count; i++) {
                    CrateConfig.Reward r = crate.roll(owned);
                    if (r == null) {
                        refunded++;
                        continue;
                    }
                    ins.setString(1, uuid.toString());
                    ins.setString(2, crate.id());
                    ins.setString(3, r.id());
                    ins.setString(4, source);
                    ins.setString(5, batch);
                    ins.setLong(6, now);
                    ins.executeUpdate();
                    if (crate.noDuplicates()) {
                        owned.add(r.id());
                        try (PreparedStatement own = tx.prepareStatement(
                                "INSERT INTO pvp_keycrate_owned (uuid, crate, reward, at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
                            own.setString(1, uuid.toString());
                            own.setString(2, crate.id());
                            own.setString(3, r.id());
                            own.setLong(4, now);
                            own.executeUpdate();
                        }
                    }
                }
            }
            if (refunded > 0 && !welcomeOnce) {
                insertClaim(tx, uuid, KEY, crate.id(), refunded, null, "refund: nothing left to win");
            }
            List<OpenRow> rows = new ArrayList<>();
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT id, crate, reward FROM pvp_keycrate_opens WHERE batch = ? ORDER BY id")) {
                ps.setString(1, batch);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new OpenRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            return new OpenBatch(List.copyOf(rows), refunded);
        }));
    }

    /** Rewards the player has never received (disconnect, crash, stop during an open). */
    CompletableFuture<List<OpenRow>> pendingOpens(UUID uuid) {
        return db.async(c -> {
            List<OpenRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, crate, reward FROM pvp_keycrate_opens WHERE uuid = ? AND state = 'PENDING' ORDER BY id LIMIT 512")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new OpenRow(rs.getLong(1), rs.getString(2), rs.getString(3)));
                    }
                }
            }
            return rows;
        });
    }

    /** Marks rows DONE; returns only the rows this call won (checklist 2). Give exactly these, nothing else. */
    CompletableFuture<List<OpenRow>> markDelivered(List<OpenRow> rows) {
        return db.async(c -> Database.transaction(c, tx -> {
            List<OpenRow> won = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE pvp_keycrate_opens SET state = 'DONE', delivered_at = ? WHERE id = ? AND state = 'PENDING'")) {
                for (OpenRow r : rows) {
                    ps.setLong(1, now);
                    ps.setLong(2, r.id());
                    if (ps.executeUpdate() == 1) {
                        won.add(r);
                    }
                }
            }
            return won;
        }));
    }

    /** Undo {@link #markDelivered} for rows that were never given (the player left before their thread ran). */
    CompletableFuture<Void> revertDelivered(List<OpenRow> rows) {
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_keycrate_opens SET state = 'PENDING', delivered_at = 0 WHERE id = ? AND state = 'DONE'")) {
                for (OpenRow r : rows) {
                    ps.setLong(1, r.id());
                    ps.executeUpdate();
                }
            }
        });
    }

    /** A won reward that no longer exists in crates.yml: kept for staff instead of being silently dropped. */
    CompletableFuture<Void> markMissing(OpenRow row) {
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_keycrate_opens SET state = 'MISSING' WHERE id = ?")) {
                ps.setLong(1, row.id());
                ps.executeUpdate();
            }
        });
    }

    private static Set<String> owned(Connection c, UUID uuid, String crate) throws SQLException {
        Set<String> set = new HashSet<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT reward FROM pvp_keycrate_owned WHERE uuid = ? AND crate = ?")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, crate);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    set.add(rs.getString(1));
                }
            }
        }
        return set;
    }

    // ------------------------------------------------------------------ claim box

    CompletableFuture<Void> addClaim(UUID uuid, String kind, String crate, int amount, @Nullable String item, String reason) {
        return db.asyncRun(c -> insertClaim(c, uuid, kind, crate, amount, item, reason));
    }

    private static void insertClaim(Connection c, UUID uuid, String kind, String crate, int amount, @Nullable String item, String reason)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_keycrate_claims (uuid, kind, crate, amount, item, reason, created_at) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, kind);
            ps.setString(3, crate);
            ps.setInt(4, amount);
            ps.setString(5, item);
            ps.setString(6, reason);
            ps.setLong(7, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    CompletableFuture<List<ClaimRow>> claims(UUID uuid, int limit) {
        return db.async(c -> {
            List<ClaimRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, kind, crate, amount, item FROM pvp_keycrate_claims WHERE uuid = ? AND claimed = 0 ORDER BY id LIMIT ?")) {
                ps.setString(1, uuid.toString());
                ps.setInt(2, limit);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new ClaimRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5)));
                    }
                }
            }
            return rows;
        });
    }

    /** Keys waiting (sum of amounts) and item rows waiting. */
    CompletableFuture<int[]> claimCounts(UUID uuid) {
        return db.async(c -> {
            int[] out = new int[2];
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT kind, COALESCE(SUM(amount), 0), COUNT(*) FROM pvp_keycrate_claims WHERE uuid = ? AND claimed = 0 GROUP BY kind")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (KEY.equals(rs.getString(1))) {
                            out[0] += (int) Math.min(Integer.MAX_VALUE, rs.getLong(2));
                        } else {
                            out[1] += rs.getInt(3);
                        }
                    }
                }
            }
            return out;
        });
    }

    /**
     * Claims rows: each is marked claimed only if still unclaimed (checklist 2), and a partly taken key row leaves its
     * remainder as a new row in the same transaction. Returns the takes this call won; give exactly those.
     */
    CompletableFuture<List<ClaimTake>> take(UUID uuid, List<ClaimTake> takes) {
        return db.async(c -> Database.transaction(c, tx -> {
            List<ClaimTake> won = new ArrayList<>();
            long now = System.currentTimeMillis();
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE pvp_keycrate_claims SET claimed = 1, claimed_at = ? WHERE id = ? AND uuid = ? AND claimed = 0")) {
                for (ClaimTake t : takes) {
                    ps.setLong(1, now);
                    ps.setLong(2, t.row().id());
                    ps.setString(3, uuid.toString());
                    if (ps.executeUpdate() != 1) {
                        continue;
                    }
                    int rest = t.row().amount() - t.take();
                    if (rest > 0) {
                        insertClaim(tx, uuid, t.row().kind(), t.row().crate(), rest, t.row().item(), "remainder of claim #" + t.row().id());
                    }
                    won.add(t);
                }
            }
            return won;
        }));
    }

    // ------------------------------------------------------------------ crate blocks

    List<BlockRow> loadBlocks() throws SQLException {
        return db.sync(c -> {
            List<BlockRow> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT world, x, y, z, crate FROM pvp_keycrate_blocks");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new BlockRow(rs.getString(1), rs.getInt(2), rs.getInt(3), rs.getInt(4), rs.getString(5)));
                }
            }
            return rows;
        });
    }

    CompletableFuture<Void> putBlock(BlockRow b) {
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_keycrate_blocks (world, x, y, z, crate) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (world, x, y, z) DO UPDATE SET crate = excluded.crate")) {
                ps.setString(1, b.world());
                ps.setInt(2, b.x());
                ps.setInt(3, b.y());
                ps.setInt(4, b.z());
                ps.setString(5, b.crate());
                ps.executeUpdate();
            }
        });
    }

    CompletableFuture<Void> removeBlock(String world, int x, int y, int z) {
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_keycrate_blocks WHERE world = ? AND x = ? AND y = ? AND z = ?")) {
                ps.setString(1, world);
                ps.setInt(2, x);
                ps.setInt(3, y);
                ps.setInt(4, z);
                ps.executeUpdate();
            }
        });
    }
}
