package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Loads and saves a player's wallet. Money that arrived while the player was offline sits in
 * {@code pvp_balance_pending}; loading folds it into the balance in the same transaction.
 */
final class WalletSection implements PlayerSection<Wallet> {

    private final long startingCents;

    WalletSection(long startingCents) {
        this.startingCents = startingCents;
    }

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public Wallet load(Connection c, UUID player) throws SQLException {
        return Database.transaction(c, tx -> {
            long cents = startingCents;
            boolean payEnabled = true;
            boolean payConfirm = true;
            boolean exists = false;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT balance_cents, pay_enabled, pay_confirm FROM pvp_balances WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        cents = rs.getLong(1);
                        payEnabled = rs.getInt(2) != 0;
                        payConfirm = rs.getInt(3) != 0;
                        exists = true;
                    }
                }
            }
            long pending = consumePending(tx, player);
            if (pending != 0 || !exists) {
                cents += pending;
                write(tx, player, new Wallet.Snapshot(cents, payEnabled, payConfirm));
            }
            return new Wallet(cents, payEnabled, payConfirm);
        });
    }

    @Override
    public void save(Connection c, UUID player, Wallet wallet) throws SQLException {
        write(c, player, wallet.snapshot());
    }

    static void write(Connection c, UUID player, Wallet.Snapshot s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_balances (uuid, balance_cents, pay_enabled, pay_confirm) VALUES (?, ?, ?, ?) "
                        + "ON CONFLICT (uuid) DO UPDATE SET balance_cents = excluded.balance_cents, "
                        + "pay_enabled = excluded.pay_enabled, pay_confirm = excluded.pay_confirm")) {
            ps.setString(1, player.toString());
            ps.setLong(2, s.cents());
            ps.setInt(3, s.payEnabled() ? 1 : 0);
            ps.setInt(4, s.payConfirm() ? 1 : 0);
            ps.executeUpdate();
        }
    }

    /** Sums and deletes the player's pending offline changes. Call inside a transaction. */
    static boolean isPostgres(Connection c) throws SQLException {
        return c.getMetaData().getDatabaseProductName().toLowerCase(java.util.Locale.ROOT).contains("postgres");
    }

    static long consumePending(Connection tx, UUID player) throws SQLException {
        if (isPostgres(tx)) {
            // One statement deletes exactly the rows it sums. The SELECT-then-DELETE below could, under PostgreSQL's
            // READ COMMITTED, delete a row committed between the two statements without counting it (lost deposit).
            long total = 0;
            try (PreparedStatement ps = tx.prepareStatement(
                    "DELETE FROM pvp_balance_pending WHERE uuid = ? RETURNING delta_cents")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        total = Math.addExact(total, rs.getLong(1));
                    }
                }
            }
            return total;
        }
        // SQLite: one connection and one writer, so nothing can commit between these two statements.
        long sum = 0;
        long maxId = -1;
        try (PreparedStatement ps = tx.prepareStatement(
                "SELECT COALESCE(SUM(delta_cents), 0), COALESCE(MAX(id), -1) FROM pvp_balance_pending WHERE uuid = ?")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    sum = rs.getLong(1);
                    maxId = rs.getLong(2);
                }
            }
        }
        if (maxId >= 0) {
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM pvp_balance_pending WHERE uuid = ? AND id <= ?")) {
                ps.setString(1, player.toString());
                ps.setLong(2, maxId);
                ps.executeUpdate();
            }
        }
        return sum;
    }
}
