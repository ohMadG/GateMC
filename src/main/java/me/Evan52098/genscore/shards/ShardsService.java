package me.Evan52098.genscore.shards;

import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.storage.Database;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Shards: the second currency (whole numbers, no decimals). Built exactly like {@code economy/EconomyService}:
 *
 * <ul>
 *   <li>Online players: the {@link ShardWallet} in memory is the truth, saved with their player data; every change asks
 *       for a save within a second ({@code saveSoon}) so a crash cannot undo one side of a purchase.</li>
 *   <li>Offline players: changes are rows in {@code pvp_shards_pending}, folded in at their next login. Offline calls
 *       run a query <b>on the calling thread</b>, so make them from an async thread (commands here do).</li>
 *   <li>{@link #withdraw} is atomic: it takes the shards only if there are enough, in one step, so two purchases on two
 *       threads can never both spend the same shards (checklist 2 and 8).</li>
 *   <li>Every change is written to {@code pvp_shards_ledger} (checklist 11); per-minute playtime pay is grouped into one
 *       ledger row per save.</li>
 * </ul>
 *
 * <p>Public API for other modules (super powers, season pass): {@link #balance}, {@link #withdraw}, {@link #deposit},
 * {@link #persist}. Get it with {@code ShardsModule.service()} (null when the module is off).
 */
public final class ShardsService {

    public record TopEntry(UUID uuid, String name, long shards) {
    }

    private final Database db;
    private final PlayerDataService players;
    private final ShardsSection section;
    private final Logger logger;
    private volatile ShardsSettings settings;

    private volatile List<TopEntry> top = List.of();
    private volatile long topAt;
    private final AtomicBoolean topRefreshing = new AtomicBoolean();

    ShardsService(Database db, PlayerDataService players, ShardsSection section, ShardsSettings settings, Logger logger) {
        this.db = db;
        this.players = players;
        this.section = section;
        this.settings = settings;
        this.logger = logger;
    }

    public ShardsSettings settings() {
        return settings;
    }

    void settings(ShardsSettings s) {
        this.settings = s;
    }

    /** The wallet of an online player, or null. */
    @Nullable ShardWallet wallet(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data == null || !data.hasJoined() ? null : data.get(section);
    }

    /**
     * Completes once the player's shards are saved to the database. Use it when something written to the database is
     * paid for with shards: withdraw, then {@code persist(uuid).thenRun(write)} (checklist 12).
     */
    public CompletableFuture<Void> persist(UUID uuid) {
        return players.saveNow(uuid);
    }

    // ------------------------------------------------------------------ balances

    /** The player's shards. Online: from memory (any thread). Offline: a query on the calling thread. */
    public long balance(UUID uuid) {
        ShardWallet w = wallet(uuid);
        if (w != null) {
            return w.shards();
        }
        try {
            return db.sync(c -> stored(c, uuid));
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not read the credits of " + uuid, e);
            return 0;
        }
    }

    public CompletableFuture<Long> balanceAsync(UUID uuid) {
        ShardWallet w = wallet(uuid);
        if (w != null) {
            return CompletableFuture.completedFuture(w.shards());
        }
        return db.async(c -> stored(c, uuid));
    }

    // ------------------------------------------------------------------ changes

    /**
     * Takes {@code amount} shards if the player has that many; returns false (and changes nothing) otherwise.
     * Atomic for online players; offline it checks and queues in one transaction.
     */
    public boolean withdraw(UUID uuid, long amount, String reason) {
        if (amount <= 0 || amount > ShardsSettings.HARD_MAX) {
            return false;
        }
        ShardWallet w = wallet(uuid);
        if (w != null) {
            if (!w.tryWithdraw(amount)) {
                return false;
            }
            ledger(uuid, -amount, reason);
            players.saveSoon(uuid);
            return true;
        }
        if (players.hasPendingSave(uuid)) {
            return false;
        }
        try {
            boolean ok = db.sync(c -> Database.transaction(c, tx -> {
                if (stored(tx, uuid) < amount) {
                    return false;
                }
                insertPending(tx, uuid, -amount, reason);
                return true;
            }));
            if (ok) {
                ledger(uuid, -amount, reason);
            }
            return ok;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not take credits from offline player " + uuid, e);
            return false;
        }
    }

    /** Adds shards. Returns false if the amount is not positive or the balance would pass {@code max-balance}. */
    public boolean deposit(UUID uuid, long amount, String reason) {
        long max = settings.maxBalance();
        if (amount <= 0 || amount > max) {
            return false;
        }
        ShardWallet w = wallet(uuid);
        if (w != null) {
            if (!w.tryAdd(amount, max)) {
                return false;
            }
            ledger(uuid, amount, reason);
            players.saveSoon(uuid);
            return true;
        }
        try {
            boolean ok = db.sync(c -> Database.transaction(c, tx -> {
                if (stored(tx, uuid) > max - amount) {
                    return false;
                }
                insertPending(tx, uuid, amount, reason);
                return true;
            }));
            if (ok) {
                ledger(uuid, amount, reason);
            }
            return ok;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not give credits to offline player " + uuid, e);
            return false;
        }
    }

    /** Admin set. Online: immediate. Offline: queued as the difference from the stored balance. */
    public boolean set(UUID uuid, long amount, String reason) {
        if (amount < 0 || amount > settings.maxBalance()) {
            return false;
        }
        ShardWallet w = wallet(uuid);
        if (w != null) {
            long before = w.set(amount);
            ledger(uuid, amount - before, reason);
            players.saveSoon(uuid);
            return true;
        }
        try {
            long delta = db.sync(c -> Database.transaction(c, tx -> {
                long d = amount - stored(tx, uuid);
                if (d != 0) {
                    insertPending(tx, uuid, d, reason);
                }
                return d;
            }));
            ledger(uuid, delta, reason);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not set the credits of offline player " + uuid, e);
            return false;
        }
    }

    /** Per-minute pay for an online player. Returns whole shards added. Ledgered at the next save. */
    long earn(UUID uuid, long milli) {
        ShardWallet w = wallet(uuid);
        if (w == null || milli <= 0) {
            return 0;
        }
        return w.earn(milli, settings.maxBalance());
    }

    // ------------------------------------------------------------------ offline changes reaching online players

    /** Folds pending rows into online wallets (shards given in the moment a player was logging in). Async thread. */
    void sweepPending() {
        List<UUID> withPending;
        try {
            withPending = db.sync(c -> {
                List<UUID> list = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pvp_shards_pending");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(UUID.fromString(rs.getString(1)));
                    }
                }
                return list;
            });
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not check for pending credit changes", e);
            return;
        }
        for (UUID uuid : withPending) {
            ShardWallet w = wallet(uuid);
            if (w == null) {
                continue;
            }
            players.runSerialized(uuid, c -> Database.transaction(c, tx -> {
                long delta = ShardsSection.consumePending(tx, uuid);
                if (delta == 0) {
                    return null;
                }
                long before = w.shards();
                w.add(delta);
                try {
                    ShardsSection.write(tx, uuid, w.snapshot());
                } catch (SQLException e) {
                    w.set(before); // the transaction rolls back, so the pending rows come back too
                    throw e;
                }
                return null;
            })).exceptionally(e -> {
                logger.log(Level.WARNING, "Could not apply pending credit changes for " + uuid, e);
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ top

    public CompletableFuture<List<TopEntry>> topAsync() {
        ShardsSettings s = settings;
        if (topAt > 0 && System.currentTimeMillis() - topAt <= s.topCacheMillis()) {
            return CompletableFuture.completedFuture(top);
        }
        if (!topRefreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(top);
        }
        return db.async(c -> {
            List<TopEntry> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT s.uuid, p.name, s.shards FROM pvp_shards s LEFT JOIN pvp_players p ON p.uuid = s.uuid "
                            + "WHERE s.shards > 0 ORDER BY s.shards DESC LIMIT ?")) {
                ps.setInt(1, s.topSize());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        UUID id = UUID.fromString(rs.getString(1));
                        ShardWallet w = wallet(id); // online wallets are newer than the last save
                        list.add(new TopEntry(id, rs.getString(2) == null ? "?" : rs.getString(2), w != null ? w.shards() : rs.getLong(3)));
                    }
                }
            }
            list.sort((a, b) -> Long.compare(b.shards(), a.shards()));
            return List.copyOf(list);
        }).handle((list, e) -> {
            topRefreshing.set(false);
            if (e != null) {
                logger.log(Level.WARNING, "Could not load the credits top list", e);
                return top;
            }
            top = list;
            topAt = System.currentTimeMillis();
            return list;
        });
    }

    // ------------------------------------------------------------------ SQL

    private static long stored(Connection c, UUID uuid) throws SQLException {
        long total = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT shards FROM pvp_shards WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total = rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(delta), 0) FROM pvp_shards_pending WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    total += rs.getLong(1);
                }
            }
        }
        return total;
    }

    private static void insertPending(Connection c, UUID uuid, long delta, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_shards_pending (uuid, delta, reason, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setString(3, reason == null ? "" : reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    static void insertLedger(Connection c, UUID uuid, long delta, String reason, long at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_shards_ledger (at, uuid, delta, reason) VALUES (?, ?, ?, ?)")) {
            ps.setLong(1, at);
            ps.setString(2, uuid.toString());
            ps.setLong(3, delta);
            ps.setString(4, reason == null ? "" : reason);
            ps.executeUpdate();
        }
    }

    /** Written in the background; a failure is logged and never blocks the change. */
    private void ledger(UUID uuid, long delta, String reason) {
        if (delta == 0) {
            return;
        }
        long at = System.currentTimeMillis();
        db.asyncRun(c -> insertLedger(c, uuid, delta, reason, at)).exceptionally(e -> {
            logger.log(Level.WARNING, "Could not write a credits ledger row", e);
            return null;
        });
    }
}
