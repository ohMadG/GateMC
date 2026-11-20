package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Balances for everyone, online or not. Amounts are cents.
 *
 * <p>Online players: their {@link Wallet} in memory is the truth (saved with their player data).
 * Offline players: changes are written as rows in {@code pvp_balance_pending} and folded in when they next log in
 * (or within a few seconds if they are already logging in). Nothing is ever lost to a race with a login.
 *
 * <p>Offline calls hit the database on the calling thread; they are rare (admin commands, a sale to an offline
 * seller) and Vault requires an immediate answer.
 */
public final class EconomyService {

    public enum PayResult { SUCCESS, SELF, DISABLED, NOT_ENOUGH, TOO_LOW, TOO_HIGH }

    public record TopEntry(UUID uuid, String name, long cents) {
    }

    private final Database db;
    private final PlayerDataService players;
    private final WalletSection section;
    private final EconomySettings settings;
    private final Logger logger;

    private volatile List<TopEntry> top = List.of();
    private volatile long topAt;
    private final AtomicBoolean topRefreshing = new AtomicBoolean();

    EconomyService(Database db, PlayerDataService players, WalletSection section, EconomySettings settings, Logger logger) {
        this.db = db;
        this.players = players;
        this.section = section;
        this.settings = settings;
        this.logger = logger;
    }

    /** Completes when the player's wallet is saved to the database (at once for offline players, whose changes are already rows). */
    public CompletableFuture<Void> persist(UUID uuid) {
        return players.saveNow(uuid);
    }

    public EconomySettings settings() {
        return settings;
    }

    /** The wallet of an online player, or null. */
    public @Nullable Wallet wallet(UUID uuid) {
        PlayerData data = players.get(uuid);
        // Only for players who have fully joined. A player still connecting has a wallet in memory that is never saved
        // if they disconnect before joining, so money for them goes to the pending table instead (and reaches them
        // within 10 s of joining through sweepPending).
        return data == null || !data.hasJoined() ? null : data.get(section);
    }

    // ------------------------------------------------------------------ balances

    public long balance(UUID uuid) {
        Wallet w = wallet(uuid);
        if (w != null) {
            return w.cents();
        }
        try {
            return db.sync(c -> storedBalance(c, uuid));
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not read the balance of " + uuid, e);
            return 0;
        }
    }

    public CompletableFuture<Long> balanceAsync(UUID uuid) {
        Wallet w = wallet(uuid);
        if (w != null) {
            return CompletableFuture.completedFuture(w.cents());
        }
        return db.async(c -> storedBalance(c, uuid));
    }

    public boolean has(UUID uuid, long cents) {
        return balance(uuid) >= cents;
    }

    // ------------------------------------------------------------------ changes

    public boolean withdraw(UUID uuid, long cents, String reason) {
        if (cents < 0) {
            return false;
        }
        Wallet w = wallet(uuid);
        if (w != null) {
            if (!w.tryWithdraw(cents)) {
                return false;
            }
            ledger(uuid, null, -cents, reason);
            players.saveSoon(uuid); // persist quickly so a crash can't undo half a transfer
            return true;
        }
        if (players.hasPendingSave(uuid)) {
            return false; // their logout save is still being written; the stored balance may be out of date
        }
        try {
            boolean ok = db.sync(c -> Database.transaction(c, tx -> {
                lockMoney(tx, uuid);
                if (storedBalance(tx, uuid) < cents) {
                    return false;
                }
                insertPending(tx, uuid, -cents, reason);
                return true;
            }));
            if (ok) {
                ledger(uuid, null, -cents, reason);
            }
            return ok;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not withdraw from offline player " + uuid, e);
            return false;
        }
    }

    public boolean deposit(UUID uuid, long cents, String reason) {
        if (cents < 0) {
            return false;
        }
        Wallet w = wallet(uuid);
        if (w != null) {
            w.add(cents);
            ledger(uuid, null, cents, reason);
            players.saveSoon(uuid);
            return true;
        }
        try {
            db.sync(c -> {
                insertPending(c, uuid, cents, reason);
                return null;
            });
            ledger(uuid, null, cents, reason);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not deposit to offline player " + uuid, e);
            return false;
        }
    }

    /** Admin set. Online: immediate. Offline: queued as the difference from the stored balance. */
    public boolean set(UUID uuid, long cents, String reason) {
        Wallet w = wallet(uuid);
        if (w != null) {
            long before;
            synchronized (w) {
                before = w.cents();
                w.set(cents);
            }
            ledger(uuid, null, cents - before, reason);
            players.saveSoon(uuid);
            return true;
        }
        if (players.hasPendingSave(uuid)) {
            return false;
        }
        try {
            long delta = db.sync(c -> Database.transaction(c, tx -> {
                lockMoney(tx, uuid);
                long d = cents - storedBalance(tx, uuid);
                if (d != 0) {
                    insertPending(tx, uuid, d, reason);
                }
                return d;
            }));
            ledger(uuid, null, delta, reason);
            return true;
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not set the balance of offline player " + uuid, e);
            return false;
        }
    }

    /** Player to player payment. Both must be online. */
    public PayResult pay(UUID from, UUID to, long cents) {
        if (from.equals(to)) {
            return PayResult.SELF;
        }
        if (cents < settings.payMinCents()) {
            return PayResult.TOO_LOW;
        }
        if (cents > settings.payMaxCents()) {
            return PayResult.TOO_HIGH;
        }
        Wallet source = wallet(from);
        Wallet target = wallet(to);
        if (source == null || target == null || !target.payEnabled()) {
            return PayResult.DISABLED;
        }
        if (!source.tryWithdraw(cents)) {
            return PayResult.NOT_ENOUGH;
        }
        ledger(from, to, -cents, "pay");
        // Rule 12: the sender's loss is saved before the receiver gets anything. Crediting first let "B quits (saved +X),
        // crash before A is saved" duplicate X. Now a crash can at worst lose the payment in flight.
        players.saveNow(from).whenComplete((saved, error) -> Tasks.async.run(() -> {
            if (error != null) {
                logger.log(Level.WARNING, "A payment from " + from + " was not saved; refunding it", error);
                deposit(from, cents, "pay refund");
                return;
            }
            Wallet receiver = wallet(to);
            if (receiver != null) {
                receiver.add(cents);
                ledger(to, from, cents, "pay");
                players.saveSoon(to);
            } else {
                deposit(to, cents, "pay"); // they left meanwhile: queued for their next login
            }
        }));
        return PayResult.SUCCESS;
    }

    // ------------------------------------------------------------------ offline money reaching online players

    /**
     * Folds pending offline changes into online wallets. Covers money sent in the moment a player was logging in.
     * Runs in each player's save queue, so it never interleaves with their saves.
     */
    void sweepPending() {
        List<UUID> withPending;
        try {
            withPending = db.sync(c -> {
                List<UUID> list = new ArrayList<>();
                try (PreparedStatement ps = c.prepareStatement("SELECT DISTINCT uuid FROM pvp_balance_pending");
                     ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(UUID.fromString(rs.getString(1)));
                    }
                }
                return list;
            });
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Could not check for pending balance changes", e);
            return;
        }
        for (UUID uuid : withPending) {
            Wallet w = wallet(uuid);
            if (w == null) {
                continue; // offline: applied at their next login
            }
            players.runSerialized(uuid, c -> Database.transaction(c, tx -> {
                long delta = WalletSection.consumePending(tx, uuid);
                if (delta == 0) {
                    return null;
                }
                w.add(delta);
                try {
                    WalletSection.write(tx, uuid, w.snapshot());
                } catch (SQLException e) {
                    w.add(-delta); // the transaction rolls back, so the pending rows come back too
                    throw e;
                }
                return null;
            })).exceptionally(e -> {
                logger.log(Level.WARNING, "Could not apply pending balance changes for " + uuid, e);
                return null;
            });
        }
    }

    // ------------------------------------------------------------------ baltop

    /** The cached top list (refreshed in the background when older than the configured time). */
    public List<TopEntry> top() {
        if (System.currentTimeMillis() - topAt > settings.topCacheMillis() && topRefreshing.compareAndSet(false, true)) {
            refreshTop();
        }
        return top;
    }

    public CompletableFuture<List<TopEntry>> topAsync() {
        if (topAt > 0 && System.currentTimeMillis() - topAt <= settings.topCacheMillis()) {
            return CompletableFuture.completedFuture(top);
        }
        return refreshTop();
    }

    private CompletableFuture<List<TopEntry>> refreshTop() {
        topRefreshing.set(true);
        return db.async(this::queryTop).handle((list, e) -> {
            topRefreshing.set(false);
            if (e != null) {
                logger.log(Level.WARNING, "Could not load the balance top list", e);
                return top;
            }
            top = list;
            topAt = System.currentTimeMillis();
            return list;
        });
    }

    private List<TopEntry> queryTop(Connection c) throws SQLException {
        Map<UUID, TopEntry> byId = new HashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                // Everyone with a saved balance or money waiting for them (someone paid while never yet loaded).
                "SELECT u.uuid, p.name, COALESCE(b.balance_cents, 0) + COALESCE(d.s, 0) AS effective "
                        + "FROM (SELECT uuid FROM pvp_balances UNION SELECT uuid FROM pvp_balance_pending) u "
                        + "LEFT JOIN pvp_balances b ON b.uuid = u.uuid "
                        + "LEFT JOIN (SELECT uuid, SUM(delta_cents) AS s FROM pvp_balance_pending GROUP BY uuid) d ON d.uuid = u.uuid "
                        + "LEFT JOIN pvp_players p ON p.uuid = u.uuid "
                        + "ORDER BY effective DESC LIMIT ?")) {
            ps.setInt(1, settings.topSize());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    UUID id = UUID.fromString(rs.getString(1));
                    String name = rs.getString(2);
                    byId.put(id, new TopEntry(id, name != null ? name : "?", rs.getLong(3)));
                }
            }
        }
        // Online wallets are newer than what was last saved.
        for (TopEntry e : List.copyOf(byId.values())) {
            Wallet w = wallet(e.uuid());
            if (w != null) {
                byId.put(e.uuid(), new TopEntry(e.uuid(), e.name(), w.cents()));
            }
        }
        List<TopEntry> list = new ArrayList<>(byId.values());
        list.removeIf(e -> e.cents() <= 0);
        list.sort(Comparator.comparingLong(TopEntry::cents).reversed());
        return List.copyOf(list);
    }

    // ------------------------------------------------------------------ SQL helpers

    /**
     * Serialises offline money changes for one player inside a transaction. PostgreSQL's default isolation lets two
     * concurrent "check balance then insert a withdrawal" transactions both pass and overdraw; an advisory lock on the
     * player stops that. SQLite has one writer at a time already.
     */
    private static void lockMoney(Connection tx, UUID uuid) throws SQLException {
        if (!WalletSection.isPostgres(tx)) {
            return;
        }
        try (PreparedStatement ps = tx.prepareStatement("SELECT pg_advisory_xact_lock(hashtext(?))")) {
            ps.setString(1, "pvp_money:" + uuid);
            ps.executeQuery().close();
        }
    }

    private static long storedBalance(Connection c, UUID uuid) throws SQLException {
        long stored = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT balance_cents FROM pvp_balances WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stored = rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(delta_cents), 0) FROM pvp_balance_pending WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    stored += rs.getLong(1);
                }
            }
        }
        return stored;
    }

    private static void insertPending(Connection c, UUID uuid, long delta, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_balance_pending (uuid, delta_cents, reason, created_at) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setLong(2, delta);
            ps.setString(3, reason);
            ps.setLong(4, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    /** Records every change for audits. Written in the background; a failure is logged, never blocks the change. */
    private void ledger(UUID uuid, @Nullable UUID other, long delta, String reason) {
        if (delta == 0) {
            return;
        }
        long at = System.currentTimeMillis();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_economy_ledger (at, uuid, other_uuid, delta_cents, reason) VALUES (?, ?, ?, ?, ?)")) {
                ps.setLong(1, at);
                ps.setString(2, uuid.toString());
                ps.setString(3, other == null ? null : other.toString());
                ps.setLong(4, delta);
                ps.setString(5, reason == null ? "" : reason);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "Could not write an economy ledger row", e);
            return null;
        });
    }
}
