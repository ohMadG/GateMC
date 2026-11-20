package me.Evan52098.genscore.teleport;

import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import me.Evan52098.genscore.player.PlayerSection;

/** One online player's homes. Thread-safe. */
public final class Homes {

    private static final Pattern VALID = Pattern.compile("[a-z0-9_-]{1,16}");

    private final Map<String, SavedLocation> homes = new LinkedHashMap<>();
    private boolean dirty;

    /** Lower-cases a name and checks it: 1 to 16 letters, digits, _ or -. Returns null if invalid. */
    public static @Nullable String normalize(String name) {
        String n = name.toLowerCase(Locale.ROOT).trim();
        return VALID.matcher(n).matches() ? n : null;
    }

    public synchronized @Nullable SavedLocation get(String name) {
        return homes.get(name);
    }

    public synchronized List<String> names() {
        return List.copyOf(homes.keySet());
    }

    public synchronized int size() {
        return homes.size();
    }

    public synchronized boolean has(String name) {
        return homes.containsKey(name);
    }

    public synchronized void set(String name, SavedLocation location) {
        homes.put(name, location);
        dirty = true;
    }

    public synchronized boolean remove(String name) {
        boolean removed = homes.remove(name) != null;
        dirty |= removed;
        return removed;
    }

    public synchronized boolean rename(String from, String to) {
        SavedLocation l = homes.get(from);
        if (l == null || homes.containsKey(to)) {
            return false;
        }
        homes.remove(from);
        homes.put(to, l);
        dirty = true;
        return true;
    }

    /** Copies the homes and clears the changed flag; null when nothing changed since the last save. */
    synchronized @Nullable Map<String, SavedLocation> takeChanges() {
        if (!dirty) {
            return null;
        }
        dirty = false;
        return new LinkedHashMap<>(homes);
    }

    synchronized void markDirty() {
        dirty = true;
    }

    /** Stored in {@code pvp_homes}, one row per home. */
    static final class Section implements PlayerSection<Homes> {

        @Override
        public String id() {
            return "homes";
        }

        @Override
        public Homes load(Connection c, UUID player) throws SQLException {
            Homes h = new Homes();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, " + SavedLocation.COLUMNS + " FROM pvp_homes WHERE uuid = ? ORDER BY name")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        h.homes.put(rs.getString(1), SavedLocation.read(rs, 2));
                    }
                }
            }
            return h;
        }

        @Override
        public void save(Connection c, UUID player, Homes data) throws SQLException {
            Map<String, SavedLocation> snapshot = data.takeChanges();
            if (snapshot == null) {
                return;
            }
            try {
                try (PreparedStatement del = c.prepareStatement("DELETE FROM pvp_homes WHERE uuid = ?")) {
                    del.setString(1, player.toString());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = c.prepareStatement(
                        "INSERT INTO pvp_homes (uuid, name, " + SavedLocation.COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                    for (Map.Entry<String, SavedLocation> e : snapshot.entrySet()) {
                        ins.setString(1, player.toString());
                        ins.setString(2, e.getKey());
                        e.getValue().bind(ins, 3);
                        ins.addBatch();
                    }
                    ins.executeBatch();
                }
            } catch (SQLException e) {
                data.markDirty(); // the save is retried, and must write again
                throw e;
            }
        }
    }
}
