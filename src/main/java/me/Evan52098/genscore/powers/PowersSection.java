package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class PowersSection implements PlayerSection<PowerData> {

    @Override
    public String id() {
        return "powers";
    }

    @Override
    public PowerData load(Connection c, UUID player) throws SQLException {
        return Database.transaction(c, tx -> {
            Set<String> owned = new LinkedHashSet<>();
            String active = null;
            boolean freeUsed = false;
            String seen = "";
            boolean exists = false;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT owned, active, free_used, seen_update FROM pvp_powers WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        exists = true;
                        owned.addAll(split(rs.getString(1)));
                        active = rs.getString(2);
                        freeUsed = rs.getInt(3) != 0;
                        seen = rs.getString(4);
                    }
                }
            }
            List<Pending> pending = pending(tx, player);
            for (Pending p : pending) {
                owned.add(p.power());
            }
            PowerData data = new PowerData(owned, active, freeUsed, seen);
            if (!pending.isEmpty() || !exists) {
                write(tx, player, data.snapshot());
                deletePending(tx, player, pending);
            }
            return data;
        });
    }

    @Override
    public void save(Connection c, UUID player, PowerData data) throws SQLException {
        write(c, player, data.snapshot());
    }

    static void write(Connection c, UUID player, PowerData.Snapshot s) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_powers (uuid, owned, active, free_used, seen_update) VALUES (?, ?, ?, ?, ?) "
                        + "ON CONFLICT (uuid) DO UPDATE SET owned = excluded.owned, active = excluded.active, "
                        + "free_used = excluded.free_used, seen_update = excluded.seen_update")) {
            ps.setString(1, player.toString());
            ps.setString(2, s.owned());
            ps.setString(3, s.active());
            ps.setInt(4, s.freeUsed() ? 1 : 0);
            ps.setString(5, s.seenUpdate());
            ps.executeUpdate();
        }
    }

    record Pending(long id, UUID player, String power) {
    }

    static List<Pending> pending(Connection c, UUID player) throws SQLException {
        List<Pending> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT id, power FROM pvp_powers_pending WHERE uuid = ? ORDER BY id")) {
            ps.setString(1, player.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Pending(rs.getLong(1), player, rs.getString(2)));
                }
            }
        }
        return out;
    }

    static void deletePending(Connection c, UUID player, List<Pending> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_powers_pending WHERE uuid = ? AND id = ?")) {
            for (Pending p : rows) {
                ps.setString(1, player.toString());
                ps.setLong(2, p.id());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    static void insertPending(Connection c, UUID player, String power, String source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_powers_pending (uuid, power, source, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, player.toString());
            ps.setString(2, power);
            ps.setString(3, source.length() > 120 ? source.substring(0, 120) : source);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    static List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return out;
        }
        for (String s : csv.split(",")) {
            String t = s.trim();
            if (!t.isEmpty()) {
                out.add(t);
            }
        }
        return out;
    }
}
