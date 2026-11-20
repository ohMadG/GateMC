package me.Evan52098.genscore.trade;

import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Items;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * SQL for trades. Three tables:
 * <ul>
 *   <li>{@code pvp_trades}: one row per trade, state OPEN / DONE / CANCELLED (the audit trail, never deleted).</li>
 *   <li>{@code pvp_trade_escrow}: items currently offered in an OPEN trade. A row exists only while its trade is open.</li>
 *   <li>{@code pvp_trade_claims}: items waiting to be handed to a player (the other side after a trade, the owner after a
 *       cancel, or anything that did not fit). Rows are marked claimed, never deleted, so movements stay auditable.</li>
 * </ul>
 * Every item that leaves escrow goes escrow -> claim inside one transaction, then claim -> inventory with a single-winner
 * UPDATE. So at any moment each item is in exactly one place in the database (checklist 2, 7).
 */
final class TradeStore {

    enum CompleteResult { DONE, CHANGED, NOT_OPEN }

    record Claim(long id, ItemStack item) {
    }

    private TradeStore() {
    }

    static String[] migrations(Database db) {
        return new String[]{
                "CREATE TABLE IF NOT EXISTS pvp_trades ("
                        + "id TEXT PRIMARY KEY, a_uuid TEXT NOT NULL, a_name TEXT NOT NULL, b_uuid TEXT NOT NULL, b_name TEXT NOT NULL, "
                        + "state TEXT NOT NULL, created_at BIGINT NOT NULL, finished_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_trades_state ON pvp_trades (state);"
                        + "CREATE TABLE IF NOT EXISTS pvp_trade_escrow ("
                        + "id TEXT PRIMARY KEY, trade_id TEXT NOT NULL, owner_uuid TEXT NOT NULL, owner_name TEXT NOT NULL, "
                        + "item TEXT NOT NULL, created_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_trade_escrow_trade ON pvp_trade_escrow (trade_id);"
                        + "CREATE TABLE IF NOT EXISTS pvp_trade_claims ("
                        + db.autoIdColumn("id") + ", uuid TEXT NOT NULL, name TEXT NOT NULL, item TEXT NOT NULL, source TEXT NOT NULL, "
                        + "trade_id TEXT, created_at BIGINT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, claimed_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_trade_claims_uuid ON pvp_trade_claims (uuid, claimed)"
        };
    }

    static String encode(ItemStack item) {
        return Items.encode(new ItemStack[]{item});
    }

    static @Nullable ItemStack decode(String data) {
        ItemStack[] items = Items.decode(data);
        return items.length == 0 || items[0] == null || items[0].isEmpty() ? null : items[0];
    }

    static void createTrade(Connection c, TradeSession s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_trades (id, a_uuid, a_name, b_uuid, b_name, state, created_at) VALUES (?, ?, ?, ?, ?, 'OPEN', ?)")) {
            ps.setString(1, s.id);
            ps.setString(2, s.a.toString());
            ps.setString(3, s.nameA);
            ps.setString(4, s.b.toString());
            ps.setString(5, s.nameB);
            ps.setLong(6, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static @Nullable String tradeState(Connection c, String tradeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT state FROM pvp_trades WHERE id = ?")) {
            ps.setString(1, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /**
     * Records an offered item. If the trade is no longer open (it ended while this write was queued), the item becomes a
     * claim for its owner instead, so it is never orphaned. Returns true when it went into escrow.
     */
    static boolean insertEscrow(Connection c, String tradeId, UUID owner, String ownerName, TradeSession.Offer offer) throws SQLException {
        return Database.transaction(c, tx -> {
            String data = encode(offer.item());
            if ("OPEN".equals(tradeState(tx, tradeId))) {
                try (PreparedStatement ps = tx.prepareStatement(
                        "INSERT INTO pvp_trade_escrow (id, trade_id, owner_uuid, owner_name, item, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, offer.id());
                    ps.setString(2, tradeId);
                    ps.setString(3, owner.toString());
                    ps.setString(4, ownerName);
                    ps.setString(5, data);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return true;
            }
            insertClaim(tx, owner, ownerName, data, "trade-return", tradeId);
            return false;
        });
    }

    /** Takes one offered item back: escrow row -> claim for its owner. False if the row was already gone. */
    static boolean withdrawEscrow(Connection c, String tradeId, String escrowId) throws SQLException {
        return Database.transaction(c, tx -> {
            String owner;
            String name;
            String data;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT owner_uuid, owner_name, item FROM pvp_trade_escrow WHERE id = ? AND trade_id = ?")) {
                ps.setString(1, escrowId);
                ps.setString(2, tradeId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return false;
                    }
                    owner = rs.getString(1);
                    name = rs.getString(2);
                    data = rs.getString(3);
                }
            }
            if (deleteEscrow(tx, escrowId) != 1) {
                return false;
            }
            insertClaim(tx, UUID.fromString(owner), name, data, "trade-withdraw", tradeId);
            return true;
        });
    }

    private static int deleteEscrow(Connection c, String escrowId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_trade_escrow WHERE id = ?")) {
            ps.setString(1, escrowId);
            return ps.executeUpdate();
        }
    }

    private record EscrowRow(String id, UUID owner, String ownerName, String item) {
    }

    private static List<EscrowRow> escrow(Connection c, String tradeId) throws SQLException {
        List<EscrowRow> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, owner_uuid, owner_name, item FROM pvp_trade_escrow WHERE trade_id = ?")) {
            ps.setString(1, tradeId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new EscrowRow(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4)));
                }
            }
        }
        return rows;
    }

    private static boolean setState(Connection c, String tradeId, String state) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE pvp_trades SET state = ?, finished_at = ? WHERE id = ? AND state = 'OPEN'")) {
            ps.setString(1, state);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, tradeId);
            return ps.executeUpdate() == 1;
        }
    }

    /**
     * Completes the trade in one transaction: OPEN -> DONE (single winner), then every escrow row becomes a claim for the
     * other side. The escrow in the database must match what both players saw and accepted ({@code expected}: escrow id
     * -> owner); if it does not, nothing changes and CHANGED is returned.
     */
    static CompleteResult complete(Connection c, TradeSession s, Map<String, UUID> expected) throws SQLException {
        return Database.transaction(c, tx -> {
            List<EscrowRow> rows = escrow(tx, s.id);
            Map<String, UUID> actual = new HashMap<>();
            for (EscrowRow r : rows) {
                actual.put(r.id(), r.owner());
            }
            if (!actual.equals(expected)) {
                return CompleteResult.CHANGED;
            }
            if (!setState(tx, s.id, "DONE")) {
                return CompleteResult.NOT_OPEN;
            }
            for (EscrowRow r : rows) {
                UUID receiver = s.other(r.owner());
                insertClaim(tx, receiver, s.nameOf(receiver), r.item(), "trade", s.id);
                deleteEscrow(tx, r.id());
            }
            return CompleteResult.DONE;
        });
    }

    /**
     * Cancels the trade: OPEN -> CANCELLED and every escrow row goes back to its owner as a claim. Rows owned by
     * {@code droppedOwner} are deleted without a claim (their items were dropped where that player died).
     * Returns false when the trade was not open.
     */
    static boolean cancel(Connection c, String tradeId, @Nullable UUID droppedOwner) throws SQLException {
        return Database.transaction(c, tx -> {
            if (!setState(tx, tradeId, "CANCELLED")) {
                return false;
            }
            for (EscrowRow r : escrow(tx, tradeId)) {
                if (!r.owner().equals(droppedOwner)) {
                    insertClaim(tx, r.owner(), r.ownerName(), r.item(), "trade-return", tradeId);
                }
                deleteEscrow(tx, r.id());
            }
            return true;
        });
    }

    /** Startup: trades left OPEN by a crash are cancelled and their escrow returned to the owners. */
    static int recoverOpen(Connection c) throws SQLException {
        List<String> open = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM pvp_trades WHERE state = 'OPEN'");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                open.add(rs.getString(1));
            }
        }
        int count = 0;
        for (String id : open) {
            if (cancel(c, id, null)) {
                count++;
            }
        }
        // Escrow rows whose trade is not open can only come from a bug; hand them back rather than lose them.
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT e.id, e.trade_id FROM pvp_trade_escrow e LEFT JOIN pvp_trades t ON t.id = e.trade_id "
                        + "WHERE t.id IS NULL OR t.state <> 'OPEN'");
             ResultSet rs = ps.executeQuery()) {
            List<String[]> orphans = new ArrayList<>();
            while (rs.next()) {
                orphans.add(new String[]{rs.getString(1), rs.getString(2)});
            }
            for (String[] o : orphans) {
                withdrawEscrow(c, o[1], o[0]);
            }
        }
        return count;
    }

    static long insertClaim(Connection c, UUID uuid, String name, String itemData, String source, @Nullable String tradeId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_trade_claims (uuid, name, item, source, trade_id, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.setString(3, itemData);
            ps.setString(4, source);
            ps.setString(5, tradeId);
            ps.setLong(6, System.currentTimeMillis());
            return ps.executeUpdate();
        }
    }

    /** Claims every waiting item for the player, each with a single-winner UPDATE; returns the ones this call won. */
    static List<Claim> claimAll(Connection c, UUID uuid) throws SQLException {
        List<long[]> ids = new ArrayList<>();
        List<String> data = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, item FROM pvp_trade_claims WHERE uuid = ? AND claimed = 0 ORDER BY id")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    ids.add(new long[]{rs.getLong(1)});
                    data.add(rs.getString(2));
                }
            }
        }
        List<Claim> won = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            long id = ids.get(i)[0];
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_trade_claims SET claimed = 1, claimed_at = ? WHERE id = ? AND claimed = 0")) {
                ps.setLong(1, System.currentTimeMillis());
                ps.setLong(2, id);
                if (ps.executeUpdate() != 1) {
                    continue;
                }
            }
            ItemStack item = decode(data.get(i));
            if (item != null) {
                won.add(new Claim(id, item));
            }
        }
        return won;
    }

    /** Puts a claim back (the player left before it could be given). */
    static void release(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE pvp_trade_claims SET claimed = 0, claimed_at = 0 WHERE id = ? AND claimed = 1")) {
            ps.setLong(1, id);
            ps.executeUpdate();
        }
    }

    static int countUnclaimed(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_trade_claims WHERE uuid = ? AND claimed = 0")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
