package me.Evan52098.genscore.player;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A module's slice of player data (combat stats, lives, balance, ...). The module owns its table, creates it with
 * {@code Migrations}, and registers the section with {@link PlayerDataService#register} while enabling.
 *
 * <p>All sections are loaded together before the player joins and saved together (one transaction) on quit and on
 * autosave. {@code load} and {@code save} run on a database thread.
 *
 * <p><b>The data object {@code T} must be thread-safe.</b> It is changed on the player's thread and read by the saver
 * on a database thread at the same time. Use {@code synchronized} methods or atomics, as {@link PlayerData} does.
 */
public interface PlayerSection<T> {

    /** Short id for logs. */
    String id();

    /** Reads the player's data. Returns fresh defaults (never null) when the player has no row yet. */
    T load(Connection c, UUID player) throws SQLException;

    /** Writes the player's data, normally with an upsert. */
    void save(Connection c, UUID player, T data) throws SQLException;
}
