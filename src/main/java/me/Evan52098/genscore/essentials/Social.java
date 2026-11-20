package me.Evan52098.genscore.essentials;

import me.Evan52098.genscore.player.PlayerSection;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/** One online player's social settings: ignores, nickname, /msg toggle, social spy. Thread-safe. */
public final class Social {

    private final Set<UUID> ignored = new LinkedHashSet<>();
    private @Nullable String nickname;
    private long nickChangedAt;
    private boolean msgEnabled = true;
    private boolean socialSpy;

    public synchronized boolean isIgnoring(UUID other) {
        return ignored.contains(other);
    }

    public synchronized boolean ignore(UUID other) {
        return ignored.add(other);
    }

    public synchronized boolean unignore(UUID other) {
        return ignored.remove(other);
    }

    public synchronized Set<UUID> ignored() {
        return Set.copyOf(ignored);
    }

    public synchronized @Nullable String nickname() {
        return nickname;
    }

    public synchronized long nickChangedAt() {
        return nickChangedAt;
    }

    public synchronized void nickname(@Nullable String nick) {
        nickname = nick;
        nickChangedAt = System.currentTimeMillis();
    }

    public synchronized boolean msgEnabled() {
        return msgEnabled;
    }

    public synchronized void msgEnabled(boolean on) {
        msgEnabled = on;
    }

    public synchronized boolean socialSpy() {
        return socialSpy;
    }

    public synchronized void socialSpy(boolean on) {
        socialSpy = on;
    }

    private record Snapshot(Set<UUID> ignored, String nickname, long nickChangedAt, boolean msgEnabled, boolean socialSpy) {
    }

    private synchronized Snapshot snapshot() {
        return new Snapshot(Set.copyOf(ignored), nickname, nickChangedAt, msgEnabled, socialSpy);
    }

    /** {@code pvp_social} (one row per player) and {@code pvp_ignores} (one row per ignored player). */
    static final class Section implements PlayerSection<Social> {

        @Override
        public String id() {
            return "social";
        }

        @Override
        public Social load(Connection c, UUID player) throws SQLException {
            Social s = new Social();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT nickname, nick_changed_at, msg_enabled, social_spy FROM pvp_social WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        s.nickname = rs.getString(1);
                        s.nickChangedAt = rs.getLong(2);
                        s.msgEnabled = rs.getInt(3) != 0;
                        s.socialSpy = rs.getInt(4) != 0;
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT ignored FROM pvp_ignores WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        s.ignored.add(UUID.fromString(rs.getString(1)));
                    }
                }
            }
            return s;
        }

        @Override
        public void save(Connection c, UUID player, Social data) throws SQLException {
            Snapshot s = data.snapshot();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_social (uuid, nickname, nick_changed_at, msg_enabled, social_spy) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (uuid) DO UPDATE SET nickname = excluded.nickname, nick_changed_at = excluded.nick_changed_at, "
                            + "msg_enabled = excluded.msg_enabled, social_spy = excluded.social_spy")) {
                ps.setString(1, player.toString());
                ps.setString(2, s.nickname());
                ps.setLong(3, s.nickChangedAt());
                ps.setInt(4, s.msgEnabled() ? 1 : 0);
                ps.setInt(5, s.socialSpy() ? 1 : 0);
                ps.executeUpdate();
            }
            try (PreparedStatement del = c.prepareStatement("DELETE FROM pvp_ignores WHERE uuid = ?")) {
                del.setString(1, player.toString());
                del.executeUpdate();
            }
            if (!s.ignored().isEmpty()) {
                try (PreparedStatement ins = c.prepareStatement("INSERT INTO pvp_ignores (uuid, ignored) VALUES (?, ?)")) {
                    for (UUID other : s.ignored()) {
                        ins.setString(1, player.toString());
                        ins.setString(2, other.toString());
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            }
        }
    }
}
