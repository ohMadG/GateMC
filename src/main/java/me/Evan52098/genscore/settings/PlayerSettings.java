package me.Evan52098.genscore.settings;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * One online player's settings and admin preferences. Port of BlockvilleCore's PlayerSettingsData and AdminPrefsData
 * (same defaults). Thread-safe: changed on the player's thread, read by the saver and by other modules.
 *
 * <p>Settings that belong to other modules are NOT stored here (private messages live in essentials' Social, the pay
 * toggle in economy's Wallet); the menus call those modules instead, so there is only ever one copy.
 */
public final class PlayerSettings {

    public enum Key {
        NOTIFICATION_SOUNDS(true),
        TPA(true),
        TRADE_REQUESTS(true),
        STAFF_CHAT_SOUND(true),
        FORCEFIELD(false),
        IGNORE_VELOCITY(false),
        BUILD_BYPASS(false);

        final boolean def;

        Key(boolean def) {
            this.def = def;
        }
    }

    private final boolean[] values = new boolean[Key.values().length];

    PlayerSettings() {
        for (Key k : Key.values()) {
            values[k.ordinal()] = k.def;
        }
    }

    public synchronized boolean get(Key key) {
        return values[key.ordinal()];
    }

    public synchronized void set(Key key, boolean value) {
        values[key.ordinal()] = value;
    }

    /** Flips the value and returns the new one. */
    public synchronized boolean toggle(Key key) {
        values[key.ordinal()] = !values[key.ordinal()];
        return values[key.ordinal()];
    }

    private synchronized boolean[] snapshot() {
        return values.clone();
    }

    /** {@code pvp_settings}, one row per player. */
    static final class Section implements PlayerSection<PlayerSettings> {

        @Override
        public String id() {
            return "settings";
        }

        @Override
        public PlayerSettings load(Connection c, UUID player) throws SQLException {
            PlayerSettings s = new PlayerSettings();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT notification_sounds, tpa_enabled, trade_requests, staff_chat_sound, forcefield, ignore_velocity, build_bypass "
                            + "FROM pvp_settings WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        for (Key k : Key.values()) {
                            s.values[k.ordinal()] = rs.getInt(k.ordinal() + 1) != 0;
                        }
                    }
                }
            }
            return s;
        }

        @Override
        public void save(Connection c, UUID player, PlayerSettings data) throws SQLException {
            boolean[] v = data.snapshot();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_settings (uuid, notification_sounds, tpa_enabled, trade_requests, staff_chat_sound, forcefield, "
                            + "ignore_velocity, build_bypass) VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET "
                            + "notification_sounds = excluded.notification_sounds, tpa_enabled = excluded.tpa_enabled, "
                            + "trade_requests = excluded.trade_requests, staff_chat_sound = excluded.staff_chat_sound, "
                            + "forcefield = excluded.forcefield, ignore_velocity = excluded.ignore_velocity, build_bypass = excluded.build_bypass")) {
                ps.setString(1, player.toString());
                for (Key k : Key.values()) {
                    ps.setInt(k.ordinal() + 2, v[k.ordinal()] ? 1 : 0);
                }
                ps.executeUpdate();
            }
        }
    }
}
