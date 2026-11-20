package me.Evan52098.genscore.cosmetics;

import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Tables and SQL for cosmetics.
 * <ul>
 *   <li>{@code pvp_cosmetics_active}: the selected cosmetic per category (player data, saved with the player).</li>
 *   <li>{@code pvp_cosmetics_kills}: total player kills (player data).</li>
 *   <li>{@code pvp_home_icons}: icon per home (player data).</li>
 *   <li>{@code pvp_cosmetics_unlocks}: stored unlocks; written at once by {@link #grant}/{@link #revoke}, never by the
 *       player-data save.</li>
 * </ul>
 */
final class CosmeticsStore implements PlayerSection<PlayerCosmetics> {

    private final Database db;

    CosmeticsStore(Database db, Logger log) throws SQLException {
        this.db = db;
        Migrations.apply(db, "cosmetics", log,
                "CREATE TABLE IF NOT EXISTS pvp_cosmetics_active (uuid TEXT NOT NULL, category TEXT NOT NULL, "
                        + "cosmetic_id TEXT NOT NULL, PRIMARY KEY (uuid, category));"
                        + "CREATE TABLE IF NOT EXISTS pvp_cosmetics_unlocks (uuid TEXT NOT NULL, category TEXT NOT NULL, "
                        + "cosmetic_id TEXT NOT NULL, source TEXT NOT NULL, unlocked_at BIGINT NOT NULL, "
                        + "PRIMARY KEY (uuid, category, cosmetic_id));"
                        + "CREATE TABLE IF NOT EXISTS pvp_cosmetics_kills (uuid TEXT PRIMARY KEY, kills INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS pvp_home_icons (uuid TEXT NOT NULL, home TEXT NOT NULL, "
                        + "material TEXT NOT NULL, PRIMARY KEY (uuid, home))");
    }

    @Override
    public String id() {
        return "cosmetics";
    }

    @Override
    public PlayerCosmetics load(Connection c, UUID player) throws SQLException {
        PlayerCosmetics data = new PlayerCosmetics();
        String id = player.toString();
        try (PreparedStatement ps = c.prepareStatement("SELECT category, cosmetic_id FROM pvp_cosmetics_active WHERE uuid = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CosmeticCategory cat = CosmeticCategory.byKey(rs.getString(1));
                    if (cat != null) {
                        data.loadActive(cat, rs.getString(2));
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT category, cosmetic_id FROM pvp_cosmetics_unlocks WHERE uuid = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CosmeticCategory cat = CosmeticCategory.byKey(rs.getString(1));
                    if (cat != null) {
                        data.loadUnlock(cat, rs.getString(2));
                    }
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT kills FROM pvp_cosmetics_kills WHERE uuid = ?")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    data.loadKills(rs.getInt(1));
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT home, material FROM pvp_home_icons WHERE uuid = ? ORDER BY home")) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    data.loadHomeIcon(rs.getString(1), rs.getString(2));
                }
            }
        }
        return data;
    }

    @Override
    public void save(Connection c, UUID player, PlayerCosmetics data) throws SQLException {
        PlayerCosmetics.Snapshot snap = data.takeChanges();
        if (snap == null) {
            return;
        }
        String id = player.toString();
        try {
            try (PreparedStatement del = c.prepareStatement("DELETE FROM pvp_cosmetics_active WHERE uuid = ?")) {
                del.setString(1, id);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO pvp_cosmetics_active (uuid, category, cosmetic_id) VALUES (?, ?, ?)")) {
                for (Map.Entry<CosmeticCategory, String> e : snap.active().entrySet()) {
                    ins.setString(1, id);
                    ins.setString(2, e.getKey().key());
                    ins.setString(3, e.getValue());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_cosmetics_kills (uuid, kills) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET kills = excluded.kills")) {
                ps.setString(1, id);
                ps.setInt(2, snap.kills());
                ps.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM pvp_home_icons WHERE uuid = ?")) {
                del.setString(1, id);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement("INSERT INTO pvp_home_icons (uuid, home, material) VALUES (?, ?, ?)")) {
                for (Map.Entry<String, String> e : snap.homeIcons().entrySet()) {
                    ins.setString(1, id);
                    ins.setString(2, e.getKey());
                    ins.setString(3, e.getValue());
                    ins.addBatch();
                }
                ins.executeBatch();
            }
        } catch (SQLException e) {
            data.markDirty(); // so the retry (and the next autosave) writes it again
            throw e;
        }
    }

    // ------------------------------------------------------------------ unlocks (written at once)

    /**
     * Stores an unlock. Completes with true if it is new, false if the player already had it. One insert on the
     * primary key decides (checklist 2: two grants at once cannot both count as new).
     */
    CompletableFuture<Boolean> grant(UUID player, CosmeticCategory category, String id, String source) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_cosmetics_unlocks (uuid, category, cosmetic_id, source, unlocked_at) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (uuid, category, cosmetic_id) DO NOTHING")) {
                ps.setString(1, player.toString());
                ps.setString(2, category.key());
                ps.setString(3, id);
                ps.setString(4, source.length() > 64 ? source.substring(0, 64) : source);
                ps.setLong(5, System.currentTimeMillis());
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Removes a stored unlock. Completes with true if there was one. */
    CompletableFuture<Boolean> revoke(UUID player, CosmeticCategory category, String id) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pvp_cosmetics_unlocks WHERE uuid = ? AND category = ? AND cosmetic_id = ?")) {
                ps.setString(1, player.toString());
                ps.setString(2, category.key());
                ps.setString(3, id);
                return ps.executeUpdate() == 1;
            }
        });
    }

    /** Kill count of a player who may be offline (the stored value; an online player's live count is newer). */
    CompletableFuture<Integer> storedKills(UUID player) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT kills FROM pvp_cosmetics_kills WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }
}
