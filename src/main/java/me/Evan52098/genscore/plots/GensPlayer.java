package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Gens progress that is not about the plot: the {@code gens_players} row. Balance lives in the economy module and
 * last seen in the core player data, so this holds only what is new to the gens server. Thread-safe.
 */
public final class GensPlayer {

    private int rebirths;

    GensPlayer(int rebirths) {
        this.rebirths = rebirths;
    }

    public synchronized int rebirths() {
        return rebirths;
    }

    public synchronized void setRebirths(int rebirths) {
        this.rebirths = Math.max(0, rebirths);
    }

    static final PlayerSection<GensPlayer> SECTION = new PlayerSection<>() {
        @Override
        public String id() {
            return "gens";
        }

        @Override
        public GensPlayer load(Connection c, UUID player) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT rebirths FROM gens_players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new GensPlayer(rs.getInt(1));
                    }
                }
            }
            return new GensPlayer(0);
        }

        @Override
        public void save(Connection c, UUID player, GensPlayer data) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gens_players (uuid, rebirths) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET rebirths = excluded.rebirths")) {
                ps.setString(1, player.toString());
                ps.setInt(2, data.rebirths());
                ps.executeUpdate();
            }
        }
    };
}
