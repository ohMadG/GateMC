package me.Evan52098.genscore.teleport;

import me.Evan52098.genscore.storage.Database;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Named server locations (warps, spawn) in {@code pvp_locations}. Read from memory; changes are written in the
 * background. Keys: {@code spawn}, {@code warp:<name>}.
 */
final class LocationStore {

    private final Database db;
    private final Logger logger;
    private final Map<String, SavedLocation> byKey = new ConcurrentHashMap<>();

    LocationStore(Database db, Logger logger) throws SQLException {
        this.db = db;
        this.logger = logger;
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT loc_key, " + SavedLocation.COLUMNS + " FROM pvp_locations");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byKey.put(rs.getString(1), SavedLocation.read(rs, 2));
                }
            }
            return null;
        });
    }

    @Nullable SavedLocation get(String key) {
        return byKey.get(key);
    }

    /** Warp names (without the prefix), sorted. */
    List<String> warpNames() {
        Map<String, SavedLocation> sorted = new TreeMap<>();
        byKey.forEach((k, v) -> {
            if (k.startsWith("warp:")) {
                sorted.put(k.substring(5), v);
            }
        });
        return List.copyOf(sorted.keySet());
    }

    void set(String key, SavedLocation location) {
        byKey.put(key, location);
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_locations (loc_key, " + SavedLocation.COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (loc_key) DO UPDATE SET world = excluded.world, x = excluded.x, y = excluded.y, "
                            + "z = excluded.z, yaw = excluded.yaw, pitch = excluded.pitch")) {
                ps.setString(1, key);
                location.bind(ps, 2);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "Could not save location " + key, e);
            return null;
        });
    }

    boolean remove(String key) {
        if (byKey.remove(key) == null) {
            return false;
        }
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_locations WHERE loc_key = ?")) {
                ps.setString(1, key);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "Could not delete location " + key, e);
            return null;
        });
        return true;
    }
}
