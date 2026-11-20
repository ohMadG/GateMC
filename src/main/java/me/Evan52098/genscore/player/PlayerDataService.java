package me.Evan52098.genscore.player;

import com.destroystokyo.paper.profile.PlayerProfile;
import io.papermc.paper.connection.PlayerConfigurationConnection;
import io.papermc.paper.connection.PlayerLoginConnection;
import io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Loads a player's data before they join, keeps it in memory while they are online, and saves it on quit, on a
 * timer, and at shutdown.
 *
 * <p>How it stays safe:
 * <ul>
 *   <li>Data is loaded in {@link AsyncPlayerPreLoginEvent} (already off the game threads). If loading fails the player
 *       is refused with "try again", so nobody plays on blank data that would then overwrite their real data.</li>
 *   <li>Saves for the same player run one after another, and a rejoin waits for the previous save before loading,
 *       so a quick relog never reads stale data.</li>
 *   <li>A failed save is retried twice before it is logged as lost.</li>
 * </ul>
 */
public final class PlayerDataService implements Listener {

    private static final long PENDING_SAVE_WAIT_SECONDS = 10;
    private static final long UNJOINED_DROP_MILLIS = TimeUnit.MINUTES.toMillis(2);
    private static final int SAVE_ATTEMPTS = 3;

    private final Plugin plugin;
    private final Database db;
    private final Logger logger;
    private final List<PlayerSection<?>> sections = Collections.synchronizedList(new ArrayList<>());
    private final Map<UUID, PlayerData> loaded = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> pendingSaves = new ConcurrentHashMap<>();
    private volatile boolean acceptingSections = true;

    public PlayerDataService(Plugin plugin, Database db) throws SQLException {
        this.plugin = plugin;
        this.db = db;
        this.logger = plugin.getLogger();
        Migrations.apply(db, "core", logger,
                "CREATE TABLE IF NOT EXISTS pvp_players ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "name TEXT NOT NULL, "
                        + "name_lower TEXT NOT NULL, "
                        + "first_join BIGINT NOT NULL, "
                        + "last_seen BIGINT NOT NULL, "
                        + "playtime_ms BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_players_name_lower ON pvp_players (name_lower)");
    }

    /** Adds a module's player data. Only allowed while the plugin is enabling, before anyone can join. */
    public void register(PlayerSection<?> section) {
        if (!acceptingSections) {
            throw new IllegalStateException("Player sections must be registered while the plugin enables");
        }
        sections.add(section);
    }

    /** Starts autosave and stops accepting new sections. Call once all modules are enabled. */
    public void start() {
        acceptingSections = false;
        long minutes = db.config().autosaveMinutes();
        Tasks.async.timer(task -> autosave(), minutes, minutes, TimeUnit.MINUTES);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /** The data of an online player, or null if they are not loaded. */
    public @Nullable PlayerData get(UUID uuid) {
        return loaded.get(uuid);
    }

    /** The data of an online player. Always present between join and quit. */
    public PlayerData get(Player player) {
        PlayerData data = loaded.get(player.getUniqueId());
        if (data == null) {
            throw new IllegalStateException("No data loaded for online player " + player.getName());
        }
        return data;
    }

    /** Finds a player who has ever joined by name (any case). Completes on a database thread. */
    public CompletableFuture<Optional<UUID>> findUuid(String name) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid FROM pvp_players WHERE name_lower = ? ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, name.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(UUID.fromString(rs.getString(1))) : Optional.empty();
                }
            }
        });
    }

    /** What is stored about a player who has joined before. */
    public record PlayerRecord(UUID uuid, String name, long firstJoin, long lastSeen, long playtimeMs) {
    }

    /** Looks a player up by name (any case) in the stored data. Completes on a database thread. */
    public CompletableFuture<Optional<PlayerRecord>> lookup(String name) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, first_join, last_seen, playtime_ms FROM pvp_players WHERE name_lower = ? "
                            + "ORDER BY last_seen DESC LIMIT 1")) {
                ps.setString(1, name.toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next()
                            ? Optional.of(new PlayerRecord(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getLong(4), rs.getLong(5)))
                            : Optional.empty();
                }
            }
        });
    }

    /** The last known name of a player who has ever joined. Completes on a database thread. */
    public CompletableFuture<Optional<String>> findName(UUID uuid) {
        PlayerData online = loaded.get(uuid);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.name()));
        }
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM pvp_players WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
                }
            }
        });
    }

    // ------------------------------------------------------------------------------------------- events

    @EventHandler(priority = EventPriority.HIGH)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return; // already refused (ban, whitelist): don't load
        }
        UUID uuid = event.getUniqueId();
        // Relogging over a session that is still open: reuse the in-memory data, which is newer than the database.
        if (loaded.computeIfPresent(uuid, (id, current) -> {
            current.claim();
            return current;
        }) != null) {
            return;
        }
        try {
            CompletableFuture<Void> pending = pendingSaves.get(uuid);
            if (pending != null) {
                pending.get(PENDING_SAVE_WAIT_SECONDS, TimeUnit.SECONDS);
            }
            PlayerData fresh = db.sync(c -> load(c, uuid, event.getName()));
            loaded.compute(uuid, (id, current) -> {
                PlayerData use = current != null ? current : fresh;
                use.claim();
                return use;
            });
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Could not load data for " + event.getName() + " (" + uuid + ")", e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    Component.text("Your player data could not be loaded. Please try again in a moment.", NamedTextColor.RED));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPreLoginResult(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            release(event.getUniqueId(), false); // refused after we loaded (a later plugin said no)
        }
    }

    /**
     * The server's own checks (whitelist, server full, duplicate login) happen after pre-login, so a player we loaded
     * can still be refused here. Release them now instead of waiting for the autosave sweep.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onValidateLogin(PlayerConnectionValidateLoginEvent event) {
        if (event.isAllowed()) {
            return;
        }
        PlayerProfile profile = null;
        if (event.getConnection() instanceof PlayerLoginConnection login) {
            profile = login.getAuthenticatedProfile();
        } else if (event.getConnection() instanceof PlayerConfigurationConnection config) {
            profile = config.getProfile();
        }
        UUID uuid = profile != null ? profile.getId() : null;
        // Only for players not in game: a refused reconfiguration of an in-game player ends in a normal quit.
        if (uuid != null && Bukkit.getPlayer(uuid) == null && loaded.containsKey(uuid)) {
            release(uuid, false);
            logger.info("Released data for " + profile.getName() + ": the server refused their login");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = loaded.get(player.getUniqueId());
        if (data == null) {
            // Should not happen; refuse rather than play on empty data.
            logger.severe("No data was loaded for " + player.getName() + " at join; kicking them to be safe");
            player.kick(Component.text("Your player data could not be loaded. Please rejoin.", NamedTextColor.RED));
            return;
        }
        data.startSession(player.getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        release(event.getPlayer().getUniqueId(), true);
    }

    /**
     * Drops one login's claim. The data leaves memory when no login is using it; it is saved whenever the player
     * had actually joined (a quit always saves, so a relog over an open session still writes the old session).
     */
    private void release(UUID uuid, boolean endSession) {
        PlayerData[] kept = new PlayerData[1];
        loaded.computeIfPresent(uuid, (id, current) -> {
            if (current.release()) {
                // Last login using this data: queue its save BEFORE it leaves `loaded`, so a pre-login landing in
                // between always finds either the data or its pending save, never stale database rows.
                if (endSession) {
                    current.endSession();
                }
                if (current.hasJoined()) {
                    queueSave(current);
                }
                return null;
            }
            kept[0] = current;
            return current;
        });
        PlayerData target = kept[0];
        if (target == null) {
            return;
        }
        if (endSession) {
            target.endSession();
        }
        if (target.hasJoined()) {
            queueSave(target);
        }
    }

    /**
     * Drops data that was loaded at pre-login for players who have not joined yet, without saving it (nothing they
     * did can be in it). Wipes call this so a player who was mid-login when the wipe started cannot join afterwards
     * and save their pre-wipe data back over it: with no data, their join is refused. Returns how many were dropped.
     */
    public int dropUnjoined() {
        int[] count = {0};
        loaded.entrySet().removeIf(e -> {
            boolean drop = !e.getValue().hasJoined();
            if (drop) {
                count[0]++;
            }
            return drop;
        });
        return count[0];
    }

    /** How many players have data in memory (joined or still connecting). */
    public int loadedCount() {
        return loaded.size();
    }

    // ------------------------------------------------------------------------------------------- saving

    private void autosave() {
        long now = System.currentTimeMillis();
        for (PlayerData data : loaded.values()) {
            if (Bukkit.getPlayer(data.uuid()) == null && now - data.lastClaimAt() > UNJOINED_DROP_MILLIS) {
                // Loaded but nobody online for 2 minutes (connection dropped during login, or a missed quit).
                if (loaded.remove(data.uuid(), data) && data.hasJoined()) {
                    data.endSession();
                    queueSave(data);
                }
            } else if (data.hasJoined()) {
                queueSave(data);
            }
        }
    }

    /** Queues a save behind any earlier save for the same player. */
    private CompletableFuture<Void> queueSave(PlayerData data) {
        UUID uuid = data.uuid();
        CompletableFuture<Void> future = runSerialized(uuid, c -> saveWithRetry(c, data));
        future.whenComplete((v, e) -> {
            if (e != null) {
                logger.log(Level.SEVERE, "Data for " + data.name() + " (" + uuid + ") was NOT saved", e);
            }
        });
        return future;
    }

    private final Set<UUID> saveSoonQueued = ConcurrentHashMap.newKeySet();

    /**
     * Saves an online player's data now and completes when it is on disk (completes at once if they are not loaded).
     * Use it when the next step must not happen before a "take" is durable, e.g. write a sale only after the money
     * taken for it is saved, so a crash can lose at most the purchase, never duplicate it.
     */
    public CompletableFuture<Void> saveNow(UUID uuid) {
        PlayerData data = loaded.get(uuid);
        if (data == null || !data.hasJoined()) {
            return CompletableFuture.completedFuture(null);
        }
        return queueSave(data); // fails if the save failed: callers must not go ahead on an unsaved "take"
    }

    /** True while a save or other queued database work for this player has not finished (e.g. their quit save). */
    public boolean hasPendingSave(UUID uuid) {
        return pendingSaves.containsKey(uuid);
    }

    /**
     * Saves an online player's data within about a second, coalescing many calls into one save. Call it after any
     * change that must survive a crash (money moved, a reward claimed): otherwise a crash before the next autosave
     * can undo one side of a transfer and duplicate it (exploit checklist item 7).
     */
    public void saveSoon(UUID uuid) {
        if (!saveSoonQueued.add(uuid)) {
            return; // a save is already on its way and will include this change
        }
        Tasks.async.later(() -> {
            saveSoonQueued.remove(uuid);
            PlayerData data = loaded.get(uuid);
            if (data != null && data.hasJoined()) {
                queueSave(data);
            }
        }, 1, TimeUnit.SECONDS);
    }

    /**
     * Runs database work for one player in the same queue as that player's saves and loads, so the two can never
     * interleave. Use it when a module writes a player's data outside the normal save (for example applying money
     * that arrived while they were offline). Completes on a database thread.
     */
    public CompletableFuture<Void> runSerialized(UUID uuid, Database.SqlConsumer work) {
        CompletableFuture<Void> future = pendingSaves.compute(uuid, (id, previous) -> {
            CompletableFuture<Void> after = previous == null ? CompletableFuture.completedFuture(null) : previous;
            return after.handle((v, e) -> null).thenCompose(v -> db.asyncRun(work));
        });
        future.whenComplete((v, e) -> pendingSaves.remove(uuid, future));
        return future;
    }

    private void saveWithRetry(Connection c, PlayerData data) throws SQLException {
        SQLException last = null;
        for (int attempt = 1; attempt <= SAVE_ATTEMPTS; attempt++) {
            try {
                Database.transaction(c, tx -> {
                    save(tx, data);
                    return null;
                });
                return;
            } catch (SQLException e) {
                last = e;
                logger.warning("Saving " + data.name() + " failed (attempt " + attempt + " of " + SAVE_ATTEMPTS + "): " + e.getMessage());
                if (attempt < SAVE_ATTEMPTS) {
                    try {
                        Thread.sleep(2000L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        throw last;
    }

    /** Saves everyone still loaded and waits (up to the timeout) for all saves to finish. Called on disable. */
    public void shutdown(long timeout, TimeUnit unit) {
        for (PlayerData data : loaded.values()) {
            if (data.hasJoined()) {
                data.endSession();
                queueSave(data);
            }
        }
        loaded.clear();
        CompletableFuture<?>[] all = pendingSaves.values().toArray(new CompletableFuture[0]);
        try {
            CompletableFuture.allOf(all).get(timeout, unit);
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Not every player's data finished saving at shutdown", e);
        }
    }

    // ------------------------------------------------------------------------------------------- SQL

    private PlayerData load(Connection c, UUID uuid, String name) throws SQLException {
        long now = System.currentTimeMillis();
        long firstJoin = now;
        long lastSeen = now;
        long playtime = 0;
        boolean firstTime = true;
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT first_join, last_seen, playtime_ms FROM pvp_players WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    firstJoin = rs.getLong(1);
                    lastSeen = rs.getLong(2);
                    playtime = rs.getLong(3);
                    firstTime = false;
                }
            }
        }
        Map<PlayerSection<?>, Object> values = new LinkedHashMap<>();
        List<PlayerSection<?>> snapshot;
        synchronized (sections) {
            snapshot = List.copyOf(sections);
        }
        for (PlayerSection<?> section : snapshot) {
            Object value = section.load(c, uuid);
            if (value == null) {
                throw new SQLException("Player section '" + section.id() + "' returned null for " + uuid);
            }
            values.put(section, value);
        }
        return new PlayerData(uuid, name, firstJoin, lastSeen, playtime, firstTime, values);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void save(Connection c, PlayerData data) throws SQLException {
        PlayerData.CoreSnapshot core = data.snapshot();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_players (uuid, name, name_lower, first_join, last_seen, playtime_ms) VALUES (?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (uuid) DO UPDATE SET name = excluded.name, name_lower = excluded.name_lower, "
                        + "last_seen = excluded.last_seen, playtime_ms = excluded.playtime_ms")) {
            ps.setString(1, data.uuid().toString());
            ps.setString(2, core.name());
            ps.setString(3, core.name().toLowerCase());
            ps.setLong(4, core.firstJoin());
            ps.setLong(5, core.lastSeen());
            ps.setLong(6, core.playtimeMs());
            ps.executeUpdate();
        }
        for (Map.Entry<PlayerSection<?>, Object> entry : data.sections().entrySet()) {
            ((PlayerSection) entry.getKey()).save(c, data.uuid(), entry.getValue());
        }
    }
}
