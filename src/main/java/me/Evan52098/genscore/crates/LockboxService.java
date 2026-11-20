package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Cooldowns;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Daily Lockbox: a code-breaking game (guess a secret 3-symbol combination in up to four tries, one game every
 * 24 hours, more money for fewer guesses). Ported from BlockvilleCore's LockboxService.
 *
 * <p>Safety:
 * <ul>
 *   <li>The secret and the reward are decided on the server and stored; the client never sees the secret.</li>
 *   <li>A game is created with an INSERT on a unique key (player + period), so two clicks or two threads can never make
 *       two games for the same period. Checklist 2.</li>
 *   <li>Each guess is one conditional UPDATE ({@code WHERE status = 'ACTIVE' AND attempts_used = expected}); a second,
 *       simultaneous guess changes no row and is reported as stale. Checklist 2 and 3.</li>
 *   <li>The reward is written as PENDING in the same UPDATE that finishes the game, before anything is paid. Payment
 *       flips PENDING to GRANTED with one conditional UPDATE (single winner), then deposits; if the deposit fails it is
 *       flipped back. A reward still PENDING (crash, disconnect, economy off) is paid on the player's next join.
 *       Checklist 1, 5 and 7.</li>
 * </ul>
 * The lockbox costs nothing to play (as in BlockvilleCore), so there is no key or price to take.
 */
public final class LockboxService {

    static final Duration COOLDOWN = Duration.ofHours(24);
    private static final int RULES_VERSION = 1;
    private static final String COLUMNS = "game_key, uuid, daily_period, secret, rules_version, guess_history, attempts_used, "
            + "status, reward_status, reward_cents, created_at, updated_at";

    public enum OutcomeType {
        INVALID_GUESS, RATE_LIMITED, NO_ACTIVE_GAME, ALREADY_FINISHED, STALE, PROGRESS, SOLVED, FAILED
    }

    public record SubmitOutcome(OutcomeType type, @Nullable LockboxGame game, int correctPosition, int wrongPosition) {
    }

    public enum Mark {
        CORRECT, PRESENT, ABSENT
    }

    public record HistoryEntry(List<LockboxSymbol> guess, int correctPosition, int wrongPosition) {
    }

    private final GensCore plugin;
    private final Database db;
    private final Logger logger;
    private final Cooldowns rateLimiter = new Cooldowns();
    private final SecureRandom random = new SecureRandom();
    private volatile LockboxSettings settings;

    LockboxService(GensCore plugin, LockboxSettings settings) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        this.settings = settings;
    }

    LockboxSettings settings() {
        return settings;
    }

    /** Guesses allowed per lockbox (1 to 4). */
    public int maxAttempts() {
        return settings.maxAttempts();
    }

    void settings(LockboxSettings updated) {
        this.settings = updated;
    }

    public String currentDailyPeriod() {
        return LocalDate.now(settings.zone()).toString();
    }

    private static String gameKey(UUID uuid, String period) {
        return uuid + ":" + period;
    }

    // ------------------------------------------------------------------ reading

    public CompletableFuture<LockboxGame> fetchLatestGame(UUID uuid) {
        return db.async(c -> latest(c, uuid));
    }

    public CompletableFuture<LockboxGame> inspect(UUID uuid, String dailyPeriod) {
        return db.async(c -> byKey(c, gameKey(uuid, dailyPeriod)));
    }

    public long cooldownRemainingMillis(@Nullable LockboxGame game) {
        if (game == null || game.active()) {
            return 0L;
        }
        return Math.max(0L, game.updatedAt() + COOLDOWN.toMillis() - System.currentTimeMillis());
    }

    // ------------------------------------------------------------------ playing

    /** The active or cooling-down game, or a new one. Completes on a database thread; null if it could not be made. */
    public CompletableFuture<LockboxGame> getOrCreateGame(UUID uuid) {
        return db.async(c -> {
            LockboxGame latest = latest(c, uuid);
            if (latest != null && (latest.active() || cooldownRemainingMillis(latest) > 0L)) {
                return latest;
            }
            String period = latest == null
                    ? currentDailyPeriod()
                    : Instant.ofEpochMilli(latest.updatedAt()).plus(COOLDOWN).truncatedTo(ChronoUnit.SECONDS).toString();
            String key = gameKey(uuid, period);
            long now = System.currentTimeMillis();
            // Single winner on the unique game key: a second simultaneous open inserts nothing and reads the same game.
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pvp_lockbox_games (" + COLUMNS + ") "
                    + "VALUES (?, ?, ?, ?, ?, '', 0, ?, ?, 0, ?, ?) ON CONFLICT (game_key) DO NOTHING")) {
                ps.setString(1, key);
                ps.setString(2, uuid.toString());
                ps.setString(3, period);
                ps.setString(4, encode(secretCombination()));
                ps.setInt(5, RULES_VERSION);
                ps.setString(6, LockboxGame.ACTIVE);
                ps.setString(7, LockboxGame.REWARD_NOT_EARNED);
                ps.setLong(8, now);
                ps.setLong(9, now);
                ps.executeUpdate();
            }
            return byKey(c, key);
        });
    }

    private List<LockboxSymbol> secretCombination() {
        List<LockboxSymbol> pool = new ArrayList<>(List.of(LockboxSymbol.values()));
        Collections.shuffle(pool, random);
        return new ArrayList<>(pool.subList(0, 3));
    }

    /**
     * Checks a guess and records it. Call on the player's thread (it reads their playtime). Completes on a database
     * or async thread.
     */
    public CompletableFuture<SubmitOutcome> submitGuess(UUID uuid, List<LockboxSymbol> guess) {
        if (guess.size() != 3 || guess.stream().anyMatch(Objects::isNull) || new HashSet<>(guess).size() != 3) {
            return CompletableFuture.completedFuture(new SubmitOutcome(OutcomeType.INVALID_GUESS, null, 0, 0));
        }
        LockboxSettings s = settings;
        // Checklist 10: one guess per rate-limit window, even if the menu is clicked faster.
        if (!rateLimiter.tryStart(uuid, "guess", s.rateLimitMillis())) {
            return CompletableFuture.completedFuture(new SubmitOutcome(OutcomeType.RATE_LIMITED, null, 0, 0));
        }
        boolean playtimeOk = rewardEligible(uuid, s);
        CompletableFuture<SubmitOutcome> recorded = db.async(c -> {
            LockboxGame game = latest(c, uuid);
            if (game == null) {
                return new SubmitOutcome(OutcomeType.NO_ACTIVE_GAME, null, 0, 0);
            }
            if (!game.active()) {
                return new SubmitOutcome(OutcomeType.ALREADY_FINISHED, game, 0, 0);
            }
            int expected = game.attemptsUsed();
            List<LockboxSymbol> secret = decode(game.secret());
            int correct = 0;
            for (int i = 0; i < 3 && i < secret.size(); i++) {
                if (secret.get(i) == guess.get(i)) {
                    correct++;
                }
            }
            Set<LockboxSymbol> secretSet = new HashSet<>(secret);
            int intersection = 0;
            for (LockboxSymbol sym : guess) {
                if (secretSet.contains(sym)) {
                    intersection++;
                }
            }
            int wrong = intersection - correct;
            int attempts = expected + 1;
            boolean solved = correct == 3;
            boolean failed = !solved && attempts >= s.maxAttempts();
            String status = solved ? LockboxGame.SOLVED : failed ? LockboxGame.FAILED : LockboxGame.ACTIVE;
            String history = appendHistory(game.guessHistory(), guess, correct, wrong);
            // The reward is decided here, once, and stored with the result. Checklist 1 and 7.
            LockboxReward reward = solved || failed ? s.rewardFor(solved, attempts) : LockboxReward.NONE;
            boolean eligible = !reward.isEmpty() && playtimeOk;
            String rewardStatus = solved || failed ? (eligible ? LockboxGame.REWARD_PENDING : LockboxGame.REWARD_NOT_EARNED) : game.rewardStatus();
            long rewardCents = solved || failed ? (eligible ? reward.cents() : 0L) : game.rewardCents();
            long now = System.currentTimeMillis();
            int rows;
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_lockbox_games SET attempts_used = ?, guess_history = ?, "
                    + "status = ?, reward_status = ?, reward_cents = ?, updated_at = ? "
                    + "WHERE game_key = ? AND status = ? AND attempts_used = ?")) {
                ps.setInt(1, attempts);
                ps.setString(2, history);
                ps.setString(3, status);
                ps.setString(4, rewardStatus);
                ps.setLong(5, rewardCents);
                ps.setLong(6, now);
                ps.setString(7, game.gameKey());
                ps.setString(8, LockboxGame.ACTIVE);
                ps.setInt(9, expected);
                rows = ps.executeUpdate();
            }
            if (rows == 0) {
                return new SubmitOutcome(OutcomeType.STALE, null, 0, 0);
            }
            LockboxGame updated = new LockboxGame(game.gameKey(), uuid, game.dailyPeriod(), game.secret(), game.rulesVersion(),
                    history, attempts, status, rewardStatus, rewardCents, game.createdAt(), now);
            OutcomeType type = solved ? OutcomeType.SOLVED : failed ? OutcomeType.FAILED : OutcomeType.PROGRESS;
            if (solved || failed) {
                logger.info("[lockbox] " + uuid + " " + status.toLowerCase() + " in " + attempts + " guess(es); reward "
                        + (eligible ? reward.display() + " pending" : "none"));
            }
            return new SubmitOutcome(type, updated, correct, wrong);
        });
        return recorded.thenCompose(outcome -> {
            LockboxGame game = outcome.game();
            if (game == null || !LockboxGame.REWARD_PENDING.equals(game.rewardStatus())
                    || (outcome.type() != OutcomeType.SOLVED && outcome.type() != OutcomeType.FAILED)) {
                return CompletableFuture.completedFuture(outcome);
            }
            return deliver(game.gameKey(), uuid).thenApply(delivered -> new SubmitOutcome(outcome.type(),
                    game.withRewardStatus(delivered ? LockboxGame.REWARD_GRANTED : LockboxGame.REWARD_PENDING),
                    outcome.correctPosition(), outcome.wrongPosition()));
        });
    }

    private boolean rewardEligible(UUID uuid, LockboxSettings s) {
        if (s.minPlaytimeMinutes() <= 0L) {
            return true;
        }
        PlayerData data = plugin.playerData().get(uuid);
        return data != null && data.playtimeMs() / 60_000L >= s.minPlaytimeMinutes();
    }

    // ------------------------------------------------------------------ paying

    /**
     * Pays a PENDING reward exactly once. Runs on an async (non-database) thread, because an offline deposit queries
     * the database on the calling thread. Completes with true if this call paid it.
     */
    CompletableFuture<Boolean> deliver(String gameKey, UUID uuid) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Tasks.async.run(() -> {
                try {
                    result.complete(deliverNow(gameKey, uuid));
                } catch (Throwable t) {
                    logger.log(Level.WARNING, "[lockbox] Failed to process reward delivery for " + gameKey, t);
                    result.complete(false);
                }
            });
        } catch (RuntimeException notRunning) {
            // Plugin is disabling: nothing was marked, so the reward stays PENDING and is paid on next join.
            result.complete(false);
        }
        return result;
    }

    private boolean deliverNow(String gameKey, UUID uuid) throws SQLException {
        EconomyModule economy = plugin.modules().get(EconomyModule.class);
        if (economy == null) {
            return false; // stays PENDING until the economy module is on
        }
        // Single winner: only the call that flips PENDING to GRANTED pays. Checklist 2.
        long cents = db.sync(c -> Database.transaction(c, tx -> {
            long amount = -1;
            try (PreparedStatement ps = tx.prepareStatement(
                    "SELECT reward_cents FROM pvp_lockbox_games WHERE game_key = ? AND reward_status = ?")) {
                ps.setString(1, gameKey);
                ps.setString(2, LockboxGame.REWARD_PENDING);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        amount = rs.getLong(1);
                    }
                }
            }
            if (amount < 0) {
                return -1L;
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE pvp_lockbox_games SET reward_status = ? WHERE game_key = ? AND reward_status = ?")) {
                ps.setString(1, LockboxGame.REWARD_GRANTED);
                ps.setString(2, gameKey);
                ps.setString(3, LockboxGame.REWARD_PENDING);
                return ps.executeUpdate() == 1 ? amount : -1L;
            }
        }));
        if (cents < 0) {
            return false;
        }
        boolean paid;
        try {
            // Checklist 8: amount came from the config parser (positive, capped, whole cents).
            paid = cents > 0 && cents <= LockboxReward.MAX_CENTS && economy.service().deposit(uuid, cents, "lockbox");
        } catch (RuntimeException ex) {
            logger.log(Level.WARNING, "[lockbox] Reward delivery threw for " + uuid, ex);
            paid = false;
        }
        if (paid) {
            logger.info("[lockbox] Paid " + new LockboxReward(cents).display() + " to " + uuid + " for " + gameKey); // checklist 11
            return true;
        }
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_lockbox_games SET reward_status = ? WHERE game_key = ? AND reward_status = ?")) {
                ps.setString(1, LockboxGame.REWARD_PENDING);
                ps.setString(2, gameKey);
                ps.setString(3, LockboxGame.REWARD_GRANTED);
                return ps.executeUpdate();
            }
        });
        return false;
    }

    /** Pays every reward still PENDING for this player (after a crash, a disconnect, or with the economy off). */
    void deliverPending(UUID uuid) {
        db.async(c -> {
            List<String> keys = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT game_key FROM pvp_lockbox_games WHERE uuid = ? AND reward_status = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, LockboxGame.REWARD_PENDING);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        keys.add(rs.getString(1));
                    }
                }
            }
            return keys;
        }).thenAccept(keys -> keys.forEach(k -> deliver(k, uuid))).exceptionally(e -> {
            logger.log(Level.WARNING, "[lockbox] Could not check pending rewards for " + uuid, e);
            return null;
        });
    }

    /** Admin: pays a pending reward for one period. Completes with true if it was paid now. */
    public CompletableFuture<Boolean> reconcile(UUID uuid, String dailyPeriod) {
        return inspect(uuid, dailyPeriod).thenCompose(game -> game == null || !LockboxGame.REWARD_PENDING.equals(game.rewardStatus())
                ? CompletableFuture.completedFuture(false)
                : deliver(game.gameKey(), uuid));
    }

    // ------------------------------------------------------------------ helpers

    public String encode(List<LockboxSymbol> combination) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < combination.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(combination.get(i).name());
        }
        return sb.toString();
    }

    public List<LockboxSymbol> decode(@Nullable String encoded) {
        List<LockboxSymbol> result = new ArrayList<>();
        if (encoded == null) {
            return result;
        }
        for (String part : encoded.split(",")) {
            LockboxSymbol symbol = LockboxSymbol.byName(part);
            if (symbol != null) {
                result.add(symbol);
            }
        }
        return result;
    }

    private String appendHistory(@Nullable String existing, List<LockboxSymbol> guess, int correct, int wrong) {
        String entry = encode(guess) + "|" + correct + "|" + wrong;
        return existing == null || existing.isEmpty() ? entry : existing + ";" + entry;
    }

    public List<Mark> marks(LockboxGame game, List<LockboxSymbol> guess) {
        List<LockboxSymbol> secret = decode(game.secret());
        List<Mark> result = new ArrayList<>();
        for (int i = 0; i < guess.size(); i++) {
            LockboxSymbol symbol = guess.get(i);
            result.add(i < secret.size() && secret.get(i) == symbol ? Mark.CORRECT : secret.contains(symbol) ? Mark.PRESENT : Mark.ABSENT);
        }
        return result;
    }

    public List<HistoryEntry> parseHistory(@Nullable String encoded) {
        List<HistoryEntry> result = new ArrayList<>();
        if (encoded == null || encoded.isEmpty()) {
            return result;
        }
        for (String part : encoded.split(";")) {
            String[] fields = part.split("\\|");
            if (fields.length != 3) {
                continue;
            }
            try {
                result.add(new HistoryEntry(decode(fields[0]), Integer.parseInt(fields[1]), Integer.parseInt(fields[2])));
            } catch (NumberFormatException ignored) {
                // skip a damaged entry
            }
        }
        return result;
    }

    // ------------------------------------------------------------------ SQL

    private static @Nullable LockboxGame latest(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM pvp_lockbox_games WHERE uuid = ? "
                + "ORDER BY created_at DESC LIMIT 1")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static @Nullable LockboxGame byKey(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT " + COLUMNS + " FROM pvp_lockbox_games WHERE game_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        }
    }

    private static LockboxGame read(ResultSet rs) throws SQLException {
        return new LockboxGame(rs.getString(1), UUID.fromString(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getInt(5),
                rs.getString(6), rs.getInt(7), rs.getString(8), rs.getString(9), rs.getLong(10), rs.getLong(11), rs.getLong(12));
    }

    static String schema(Database db) {
        return "CREATE TABLE IF NOT EXISTS pvp_lockbox_games ("
                + "game_key TEXT PRIMARY KEY, "
                + "uuid TEXT NOT NULL, "
                + "daily_period TEXT NOT NULL, "
                + "secret TEXT NOT NULL, "
                + "rules_version INTEGER NOT NULL, "
                + "guess_history TEXT NOT NULL DEFAULT '', "
                + "attempts_used INTEGER NOT NULL DEFAULT 0, "
                + "status TEXT NOT NULL, "
                + "reward_status TEXT NOT NULL, "
                + "reward_cents BIGINT NOT NULL DEFAULT 0, "
                + "created_at BIGINT NOT NULL, "
                + "updated_at BIGINT NOT NULL);"
                + "CREATE INDEX IF NOT EXISTS pvp_lockbox_games_uuid ON pvp_lockbox_games (uuid, created_at);"
                + "CREATE INDEX IF NOT EXISTS pvp_lockbox_games_reward ON pvp_lockbox_games (uuid, reward_status)";
    }
}
