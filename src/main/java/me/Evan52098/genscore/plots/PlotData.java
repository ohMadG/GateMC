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
    private int speedLevel;
    private int genLevel;
    private int armorLevel;
    private int turretLevel;
    private int trapLevel;
    private int machines;

    public synchronized int machines() {
        return machines;
    }

    public synchronized void setMachines(int machines) {
        this.machines = Math.max(0, machines);
    }

    /** Belt armour: each level adds health to every belt segment, so raids take longer. */
    public synchronized int armorLevel() {
        return armorLevel;
    }

    public synchronized void setArmorLevel(int level) {
        armorLevel = Math.max(0, level);
    }

    /** Turrets on the towers: more turrets, more damage, faster fire. */
    public synchronized int turretLevel() {
        return turretLevel;
    }

    public synchronized void setTurretLevel(int level) {
        turretLevel = Math.max(0, level);
    }

    /** Traps inside the walls: slow (and at higher levels throw back) intruders. */
    public synchronized int trapLevel() {
        return trapLevel;
    }

    public synchronized void setTrapLevel(int level) {
        trapLevel = Math.max(0, level);
    }

    PlotData(int beltLength, int lockLevel, long shieldUntil, int speedLevel, int genLevel) {
        this.beltLength = beltLength;
        this.lockLevel = lockLevel;
        this.shieldUntil = shieldUntil;
        this.speedLevel = speedLevel;
        this.genLevel = genLevel;
    }

    /** The gen tier (0 = the first tier). Higher tiers make items worth more. */
    public synchronized int genLevel() {
        return genLevel;
    }

    public synchronized void setGenLevel(int genLevel) {
        this.genLevel = Math.max(0, genLevel);
    }

    /** The belt speed upgrade level (0 = the starting speed). */
    public synchronized int speedLevel() {
        return speedLevel;
    }

    public synchronized void setSpeedLevel(int speedLevel) {
        this.speedLevel = Math.max(0, speedLevel);
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
        speedLevel = 0;
        genLevel = 0;
        armorLevel = 0;
        turretLevel = 0;
        trapLevel = 0;
        machines = 0;
    }

    private synchronized Object[] snapshot() {
        return new Object[]{beltLength, lockLevel, shieldUntil, speedLevel, genLevel, armorLevel, turretLevel, trapLevel, machines};
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
                        "SELECT belt_length, lock_level, shield_until, speed_level, gen_level, armor_level, turret_level, trap_level, machines FROM gens_plots WHERE uuid = ?")) {
                    ps.setString(1, player.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            PlotData d = new PlotData(rs.getInt(1), rs.getInt(2), rs.getLong(3), rs.getInt(4), rs.getInt(5));
                            d.armorLevel = rs.getInt(6);
                            d.turretLevel = rs.getInt(7);
                            d.trapLevel = rs.getInt(8);
                            d.machines = rs.getInt(9);
                            return d;
                        }
                    }
                }
                return new PlotData(defaultBeltLength.getAsInt(), 0, 0, 0, 0);
            }

            @Override
            public void save(Connection c, UUID player, PlotData data) throws SQLException {
                Object[] s = data.snapshot();
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO gens_plots (uuid, belt_length, lock_level, shield_until, speed_level, gen_level, armor_level, turret_level, "
                                + "trap_level, machines) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                                + "ON CONFLICT (uuid) DO UPDATE SET belt_length = excluded.belt_length, "
                                + "lock_level = excluded.lock_level, shield_until = excluded.shield_until, "
                                + "speed_level = excluded.speed_level, gen_level = excluded.gen_level, "
                                + "armor_level = excluded.armor_level, turret_level = excluded.turret_level, "
                                + "trap_level = excluded.trap_level, machines = excluded.machines")) {
                    ps.setString(1, player.toString());
                    ps.setInt(2, (Integer) s[0]);
                    ps.setInt(3, (Integer) s[1]);
                    ps.setLong(4, (Long) s[2]);
                    ps.setInt(5, (Integer) s[3]);
                    ps.setInt(6, (Integer) s[4]);
                    ps.setInt(7, (Integer) s[5]);
                    ps.setInt(8, (Integer) s[6]);
                    ps.setInt(9, (Integer) s[7]);
                    ps.setInt(10, (Integer) s[8]);
                    ps.executeUpdate();
                }
            }
        };
    }
}
