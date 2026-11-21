package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.storage.Database;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class NoteService {
    private static final int HISTORY_LIMIT = 200;

    private final Database db;

    public NoteService(Database db) {
        this.db = db;
    }

    public static String schema(Database db) {
        return "CREATE TABLE IF NOT EXISTS pvp_player_notes ("
                + db.autoIdColumn("id") + ", target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, note TEXT NOT NULL, staff_uuid TEXT, staff_name TEXT NOT NULL, created_at BIGINT NOT NULL);CREATE INDEX IF NOT EXISTS pvp_player_notes_target ON pvp_player_notes (target_uuid, id DESC)";
    }

    public CompletableFuture<PlayerNote> create(UUID targetUuid, String targetName, UUID staffUuid, String staffName, String note) {
        long now = System.currentTimeMillis();
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_player_notes (target_uuid, target_name, note, staff_uuid, staff_name, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                    Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, targetUuid.toString());
                ps.setString(2, targetName);
                ps.setString(3, note);
                ps.setString(4, staffUuid == null ? null : staffUuid.toString());
                ps.setString(5, staffName);
                ps.setLong(6, now);
                ps.executeUpdate();
                long id;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    keys.next();
                    id = keys.getLong(1);
                }
                return new PlayerNote(id, targetUuid, targetName, note, staffUuid, staffName, now);
            }
        });
    }

    public CompletableFuture<List<PlayerNote>> history(UUID targetUuid) {
        return db.async(c -> {
            List<PlayerNote> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM pvp_player_notes WHERE target_uuid = ? ORDER BY id DESC LIMIT ?")) {
                ps.setString(1, targetUuid.toString());
                ps.setInt(2, HISTORY_LIMIT);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(map(rs));
                    }
                }
            }
            return list;
        });
    }

    private PlayerNote map(ResultSet rs) throws SQLException {
        String staffUuidStr = rs.getString("staff_uuid");
        return new PlayerNote(
                rs.getLong("id"),
                UUID.fromString(rs.getString("target_uuid")),
                rs.getString("target_name"),
                rs.getString("note"),
                staffUuidStr == null ? null : UUID.fromString(staffUuidStr),
                rs.getString("staff_name"),
                rs.getLong("created_at"));
    }
}
