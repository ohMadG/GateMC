package me.Evan52098.genscore.teleport;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/** A location stored by world name, so it survives restarts and never holds a World reference. */
public record SavedLocation(String world, double x, double y, double z, float yaw, float pitch) {

    public static SavedLocation of(Location l) {
        return new SavedLocation(l.getWorld().getName(), l.getX(), l.getY(), l.getZ(), l.getYaw(), l.getPitch());
    }

    /** The Bukkit location, or null if the world is not loaded any more. */
    public @Nullable Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w == null ? null : new Location(w, x, y, z, yaw, pitch);
    }

    /** Columns: world, x, y, z, yaw, pitch starting at {@code first}. */
    public void bind(PreparedStatement ps, int first) throws SQLException {
        ps.setString(first, world);
        ps.setDouble(first + 1, x);
        ps.setDouble(first + 2, y);
        ps.setDouble(first + 3, z);
        ps.setFloat(first + 4, yaw);
        ps.setFloat(first + 5, pitch);
    }

    public static SavedLocation read(ResultSet rs, int first) throws SQLException {
        return new SavedLocation(rs.getString(first), rs.getDouble(first + 1), rs.getDouble(first + 2),
                rs.getDouble(first + 3), rs.getFloat(first + 4), rs.getFloat(first + 5));
    }

    /** SQL column list matching {@link #bind} and {@link #read}. */
    public static final String COLUMNS = "world, x, y, z, yaw, pitch";
    public static final String COLUMN_DEFS = "world TEXT NOT NULL, x DOUBLE PRECISION NOT NULL, y DOUBLE PRECISION NOT NULL, "
            + "z DOUBLE PRECISION NOT NULL, yaw REAL NOT NULL, pitch REAL NOT NULL";
}
