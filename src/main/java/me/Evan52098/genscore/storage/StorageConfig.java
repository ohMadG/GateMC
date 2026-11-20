package me.Evan52098.genscore.storage;

import org.bukkit.configuration.ConfigurationSection;

import java.io.File;

/**
 * The {@code storage:} section of config.yml.
 *
 * <p>The database password is read from the environment variable {@code GENSCORE_DB_PASSWORD} when it is set,
 * so it never has to be written into config.yml.
 */
public record StorageConfig(
        Type type,
        String host,
        int port,
        String database,
        String username,
        String password,
        int poolSize,
        File sqliteFile,
        int autosaveMinutes
) {

    public enum Type { SQLITE, POSTGRES }

    public static StorageConfig from(ConfigurationSection s, File dataFolder) {
        String rawType = s.getString("type", "sqlite").trim().toUpperCase();
        Type type;
        try {
            type = Type.valueOf(rawType);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("storage.type must be sqlite or postgres, not '" + rawType.toLowerCase() + "'");
        }
        String envPassword = System.getenv("GENSCORE_DB_PASSWORD");
        String password = envPassword != null && !envPassword.isEmpty() ? envPassword : s.getString("password", "");
        return new StorageConfig(
                type,
                s.getString("host", "localhost"),
                s.getInt("port", 5432),
                s.getString("database", "genscore"),
                s.getString("username", "genscore"),
                password,
                Math.max(1, s.getInt("pool-size", 8)),
                new File(dataFolder, s.getString("sqlite-file", "data.db")),
                Math.max(1, s.getInt("autosave-minutes", 5))
        );
    }
}
