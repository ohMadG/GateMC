package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.function.IntSupplier;

/**
 * A player's lasting plot data: the {@code gens_plots} row. Loaded before join and saved on quit and autosave with
 * the rest of the player's data (roadmap rule 2: gameplay reads this cached copy, never the database).
 *
 * <p>Thread-safe: changed on the owner's thread, read by the saver on a database thread.
 */
public final class PlotData {

    private int beltLength;
    private int lockLevel;
    private long shieldUntil;

    PlotData(int beltLength, int lockLevel, long shieldUntil) {
        this.beltLength = beltLength;
        this.lockLevel = lockLevel;
        this.shieldUntil = shieldUntil;
    }

    public synchronized int beltLength() {
        return beltLength;
    }

    public synchronized void setBeltLength(int beltLength) {
        this.beltLength = Math.max(1, beltLength);
    }

    public synchronized int lockLevel() {
        return lockLevel;
    }

    public synchronized void setLockLevel(int lockLevel) {
        this.lockLevel = Math.max(0, lockLevel);
    }

    /** Epoch millis until which the plot cannot be raided; 0 for none. */
    public synchronized long shieldUntil() {
        return shieldUntil;
    }

    public synchronized void setShieldUntil(long shieldUntil) {
        this.shieldUntil = Math.max(0, shieldUntil);
    }

    synchronized void reset(int defaultBeltLength) {
        beltLength = defaultBeltLength;
        lockLevel = 0;
        shieldUntil = 0;
    }

    private synchronized Object[] snapshot() {
        return new Object[]{beltLength, lockLevel, shieldUntil};
    }

    /** The player-data section for {@code gens_plots}. */
    static PlayerSection<PlotData> section(IntSupplier defaultBeltLength) {
        return new PlayerSection<>() {
            @Override
            public String id() {
                return "plots";
            }

            @Override
            public PlotData load(Connection c, UUID player) throws SQLException {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT belt_length, lock_level, shield_until FROM gens_plots WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return new PlotData(rs.getInt(1), rs.getInt(2), rs.getLong(3));
                        }
                    }
                }
                return new PlotData(defaultBeltLength.getAsInt(), 0, 0);
            }

            @Override
            public void save(Connection c, UUID player, PlotData data) throws SQLException {
                Object[] s = data.snapshot();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO gens_plots (uuid, belt_length, lock_level, shield_until) VALUES (?, ?, ?, ?) "
                                + "ON CONFLICT (uuid) DO UPDATE SET belt_length = excluded.belt_length, "
                                + "lock_level = excluded.lock_level, shield_until = excluded.shield_until")) {
                    ps.setString(1, player.toString());
                    ps.setInt(2, (Integer) s[0]);
                    ps.setInt(3, (Integer) s[1]);
                    ps.setLong(4, (Long) s[2]);
                    ps.executeUpdate();
                }
            }
        };
    }
}
