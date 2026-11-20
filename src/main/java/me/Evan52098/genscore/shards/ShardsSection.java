package me.Evan52098.genscore.shards;

import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Loads and saves a player's shards, the same way {@code economy/WalletSection} does money: changes made while the
 * player was offline are rows in {@code pvp_shards_pending}, folded in at load in the same transaction.
 */
final class ShardsSection implements PlayerSection<ShardWallet> {

    @Override
    public String id() {
        return "shards";
    }

    @Override
    public ShardWallet load(Connection c, UUID player) throws SQLException {
        return Database.transaction(c, tx -> {
            long shards = 0;
            long milli = 0;
            boolean exists = false;
            try (PreparedStatement ps = tx.prepareStatement("SELECT shards, earn_milli FROM pvp_shards WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        shards = rs.getLong(1);
                        milli = rs.getLong(2);
                        exists = true;
                    }
                }
            }
            long pending = consumePending(tx, player);
            if (pending != 0 || !exists) {
                shards = Math.max(0, shards + pending);
                write(tx, player, new ShardWallet.Snapshot(shards, milli));
            }
            return new ShardWallet(shards, milli);
        });
    }

    @Override
    public void save(Connection c, UUID player, ShardWallet wallet) throws SQLException {
        write(c, player, wallet.snapshot());
        // Playtime pay is ledgered as one row per save instead of one row per minute (checklist 11).
        long earned = wallet.takeUnledgered();
        if (earned > 0) {
            try {
                ShardsService.insertLedger(c, player, earned, "playtime", System.currentTimeMillis());
            } catch (SQLException e) {
                wallet.returnUnledgered(earned);
                throw e;
            }
        }
    }

    static void write(Connection c, UUID player, ShardWallet.Snapshot s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_shards (uuid, shards, earn_milli) VALUES (?, ?, ?) "
                        + "ON CONFLICT (uuid) DO UPDATE SET shards = excluded.shards, earn_milli = excluded.earn_milli")) {
            ps.setString(1, player.toString());
            ps.setLong(2, s.shards());
            ps.setLong(3, s.earnMilli());
            ps.executeUpdate();
        }
    }

    /** Sums and deletes the player's pending offline changes. Call inside a transaction. */
    static long consumePending(Connection tx, UUID player) throws SQLException {
        long sum = 0;
        long maxId = -1;
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT COALESCE(SUM(delta), 0), COALESCE(MAX(id), -1) FROM pvp_shards_pending WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sum = rs.getLong(1);
                    maxId = rs.getLong(2);
                }
            }
        }
        if (maxId >= 0) {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM pvp_shards_pending WHERE uuid = ? AND id <= ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, maxId);
                ps.executeUpdate();
            }
        }
        return sum;
    }
}
