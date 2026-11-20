package me.Evan52098.genscore.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

/**
 * Versioned schema changes, tracked per owner (the core or a module id) in {@code pvp_migrations}.
 *
 * <p>Each step runs once, in its own transaction. Never edit a step that has shipped: add a new version instead.
 *
 * <pre>
 * Migrations.apply(db, "factions", logger,
 *     "CREATE TABLE IF NOT EXISTS pvp_factions (...)",      // version 1
 *     "ALTER TABLE pvp_factions ADD COLUMN dtr DOUBLE PRECISION NOT NULL DEFAULT 1"); // version 2
 * </pre>
 */
public final class Migrations {

    private Migrations() {
    }

    /**
     * Applies every step newer than the owner's current version. Step {@code i} (0-based) is version {@code i + 1}.
     * A step may hold several statements separated by {@code ;}. Runs on the calling thread (startup only).
     */
    public static void apply(Database db, String owner, Logger logger, String... steps) throws SQLException {
        db.sync(c -> {
            ensureTable(c);
            int current = currentVersion(c, owner);
            for (int i = current; i < steps.length; i++) {
                int version = i + 1;
                String step = steps[i];
                Database.transaction(c, tx -> {
                    try (Statement st = tx.createStatement()) {
                        for (String sql : step.split(";")) {
                            if (!sql.isBlank()) {
                                st.execute(sql.trim());
                            }
                        }
                    }
                    try (PreparedStatement ps = tx.prepareStatement(
                            "INSERT INTO pvp_migrations (owner, version, applied_at) VALUES (?, ?, ?)")) {
                        ps.setString(1, owner);
                        ps.setInt(2, version);
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                    return null;
                });
                logger.info("Applied database migration " + owner + " v" + version);
            }
            return null;
        });
    }

    private static void ensureTable(Connection c) throws SQLException {
        try (Statement st = c.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS pvp_migrations ("
                    + "owner TEXT NOT NULL, "
                    + "version INTEGER NOT NULL, "
                    + "applied_at BIGINT NOT NULL, "
                    + "PRIMARY KEY (owner, version))");
        }
    }

    private static int currentVersion(Connection c, String owner) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(version) FROM pvp_migrations WHERE owner = ?")) {
            ps.setString(1, owner);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }
}
