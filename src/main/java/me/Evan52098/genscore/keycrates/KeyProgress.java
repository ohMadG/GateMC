package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/** Minutes of active (non-AFK) playtime counted towards the next playtime key. Thread-safe. */
final class KeyProgress {

    private int minutes;

    KeyProgress(int minutes) {
        this.minutes = minutes;
    }

    synchronized int minutes() {
        return minutes;
    }

    /** Adds a minute; returns true (and subtracts {@code per}) when a key is earned. */
    synchronized boolean tick(int per) {
        minutes++;
        if (minutes >= per) {
            minutes -= per;
            if (minutes >= per) {
                minutes = 0; // the interval was lowered in config: don't pay a backlog
            }
            return true;
        }
        return false;
    }

    static final class Section implements PlayerSection<KeyProgress> {

        @Override
        public String id() {
            return "keycrates";
        }

        @Override
        public KeyProgress load(Connection c, UUID player) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement("SELECT minutes FROM pvp_keycrate_progress WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return new KeyProgress(rs.next() ? Math.max(0, rs.getInt(1)) : 0);
                }
            }
        }

        @Override
        public void save(Connection c, UUID player, KeyProgress data) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_keycrate_progress (uuid, minutes) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET minutes = excluded.minutes")) {
                ps.setString(1, player.toString());
                ps.setInt(2, data.minutes());
                ps.executeUpdate();
            }
        }
    }
}
