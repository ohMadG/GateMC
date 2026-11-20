package me.Evan52098.genscore.daily;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The Daily Strike, ported from BlockvilleCore's DailyRewardService: once a day a player digs, a weighted roll picks
 * one of five layers (a longer streak makes the deeper layers likelier), its console commands run, and streak
 * milestones add guaranteed bonus commands.
 *
 * <p>Changed from BlockvilleCore on purpose: a claim is allowed once per <b>calendar day</b> in {@code daily.reset-timezone}
 * (BlockvilleCore used a rolling 24 hours), and the streak continues when the last claim was yesterday. The last claimed
 * day is stored, never a timer, so midnight and restarts cannot break it.
 *
 * <p>Safety:
 * <ul>
 *   <li>One transaction decides a claim: it inserts {@code (uuid, day)} into {@code pvp_daily_claims}, whose primary key
 *       makes it impossible to claim twice on one day, whatever the clicks, relogs or threads. Checklist 2.</li>
 *   <li>A per-player in-flight flag ignores clicks while a claim is being written. Checklist 3.</li>
 *   <li>The roll, the streak and the exact commands are written in that same transaction as PENDING, before anything is
 *       given; the commands run after a second conditional UPDATE flips PENDING to GIVEN (single winner). A claim still
 *       PENDING after a crash or stop is given on the player's next join. Checklist 1, 5 and 7.</li>
 * </ul>
 */
public final class DailyRewardService {

    private static final String PENDING = "PENDING";
    private static final String GIVEN = "GIVEN";

    public record EligibilityInfo(boolean ready, boolean eligible, long remainingMillis, int currentStreak, int longestStreak,
                                  int totalClaims) {
        static EligibilityInfo notReady() {
            return new EligibilityInfo(false, false, 0L, 0, 0, 0);
        }
    }

    record ClaimResult(boolean success, DailyRewardConfig.DailyRewardTier tier, int tierDepthIndex, int newStreak,
                       boolean streakContinued, DailyRewardConfig.DailyRewardMilestone milestoneHit) {
        static ClaimResult failed() {
            return new ClaimResult(false, null, -1, 0, false, null);
        }
    }

    private record Stored(@Nullable LocalDate lastDay, int current, int longest, int total) {
    }

    private record TxResult(ClaimResult claim, Stored stats) {
    }

    private final GensCore plugin;
    private final Database db;
    private final Logger logger;
    private final SecureRandom random = new SecureRandom();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    /** Claims marked GIVEN whose console commands have not run yet; reverted at shutdown so they are retried. */
    private final Map<String, Runnable> pendingDispatch = new ConcurrentHashMap<>();
    private volatile DailyRewardConfig config;

    DailyRewardService(GensCore plugin, DailyRewardConfig config) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        this.config = config;
    }

    static String schema() {
        return "CREATE TABLE IF NOT EXISTS pvp_daily_stats ("
                + "uuid TEXT PRIMARY KEY, "
                + "last_day TEXT, "
                + "current_streak INTEGER NOT NULL DEFAULT 0, "
                + "longest_streak INTEGER NOT NULL DEFAULT 0, "
                + "total_claims INTEGER NOT NULL DEFAULT 0);"
                + "CREATE TABLE IF NOT EXISTS pvp_daily_claims ("
                + "uuid TEXT NOT NULL, "
                + "day TEXT NOT NULL, "
                + "tier_id TEXT NOT NULL, "
                + "tier_index INTEGER NOT NULL, "
                + "streak INTEGER NOT NULL, "
                + "milestone INTEGER NOT NULL DEFAULT 0, "
                + "commands TEXT NOT NULL, "
                + "state TEXT NOT NULL, "
                + "claimed_at BIGINT NOT NULL, "
                + "PRIMARY KEY (uuid, day));"
                + "CREATE INDEX IF NOT EXISTS pvp_daily_claims_state ON pvp_daily_claims (uuid, state)";
    }

    DailyRewardConfig config() {
        return config;
    }

    void config(DailyRewardConfig updated) {
        this.config = updated;
    }

    LocalDate today() {
        return LocalDate.now(config.zone());
    }

    long millisUntilNextDay() {
        ZonedDateTime now = ZonedDateTime.now(config.zone());
        ZonedDateTime next = now.toLocalDate().plusDays(1).atStartOfDay(config.zone());
        return Math.max(0L, next.toInstant().toEpochMilli() - now.toInstant().toEpochMilli());
    }

    private @Nullable DailyStats stats(UUID uuid) {
        PlayerData data = plugin.playerData().get(uuid);
        return data == null ? null : data.get(DailyStats.SECTION);
    }

    public EligibilityInfo peek(UUID uuid) {
        DailyStats stats = stats(uuid);
        if (stats == null) {
            return EligibilityInfo.notReady();
        }
        LocalDate today = today();
        LocalDate last = stats.lastDay();
        boolean eligible = last == null || last.isBefore(today);
        return new EligibilityInfo(true, eligible, eligible ? 0L : millisUntilNextDay(), stats.liveStreak(today),
                stats.longestStreak(), stats.totalClaims());
    }

    boolean isClaiming(UUID uuid) {
        return claiming.contains(uuid);
    }

    /** Claims today's reward. Completes on a database thread; the result says whether this call won. */
    CompletableFuture<ClaimResult> claim(UUID uuid, String playerName) {
        DailyRewardConfig cfg = config;
        if (!cfg.enabled() || cfg.tiers().isEmpty()) {
            return CompletableFuture.completedFuture(ClaimResult.failed());
        }
        if (!claiming.add(uuid)) {
            return CompletableFuture.completedFuture(ClaimResult.failed()); // checklist 3
        }
        LocalDate today = LocalDate.now(cfg.zone());
        CompletableFuture<ClaimResult> result = db.async(c -> Database.transaction(c, tx -> {
            Stored before = readStats(tx, uuid);
            if (before.lastDay() != null && !before.lastDay().isBefore(today)) {
                return new TxResult(ClaimResult.failed(), before);
            }
            boolean continued = before.lastDay() != null && before.lastDay().equals(today.minusDays(1));
            int newStreak = continued ? before.current() + 1 : 1;
            int tierIndex = rollTierIndex(cfg, newStreak);
            DailyRewardConfig.DailyRewardTier tier = cfg.tiers().get(tierIndex);
            DailyRewardConfig.DailyRewardMilestone milestone = cfg.milestoneFor(newStreak);
            List<String> commands = new ArrayList<>(tier.commands());
            if (milestone != null) {
                commands.addAll(milestone.commands());
            }
            // Checklist 2: the (uuid, day) key lets exactly one claim per day through.
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_daily_claims (uuid, day, tier_id, tier_index, streak, "
                    + "milestone, commands, state, claimed_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uuid, day) DO NOTHING")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, today.toString());
                ps.setString(3, tier.id());
                ps.setInt(4, tierIndex);
                ps.setInt(5, newStreak);
                ps.setInt(6, milestone == null ? 0 : milestone.streakLength());
                ps.setString(7, String.join("\n", commands));
                ps.setString(8, PENDING);
                ps.setLong(9, System.currentTimeMillis());
                if (ps.executeUpdate() != 1) {
                    return new TxResult(ClaimResult.failed(), readStats(tx, uuid));
                }
            }
            Stored after = new Stored(today, newStreak, Math.max(before.longest(), newStreak), before.total() + 1);
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_daily_stats (uuid, last_day, current_streak, "
                    + "longest_streak, total_claims) VALUES (?, ?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET "
                    + "last_day = excluded.last_day, current_streak = excluded.current_streak, "
                    + "longest_streak = excluded.longest_streak, total_claims = excluded.total_claims")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, today.toString());
                ps.setInt(3, after.current());
                ps.setInt(4, after.longest());
                ps.setInt(5, after.total());
                ps.executeUpdate();
            }
            return new TxResult(new ClaimResult(true, tier, tierIndex, newStreak, continued, milestone), after);
        })).thenApply(tx -> {
            DailyStats cached = stats(uuid);
            if (cached != null) {
                cached.set(tx.stats().lastDay(), tx.stats().current(), tx.stats().longest(), tx.stats().total());
            }
            if (tx.claim().success()) {
                logger.info("[daily] " + playerName + " struck " + tx.claim().tier().id() + " (streak " + tx.claim().newStreak()
                        + (tx.claim().milestoneHit() != null ? ", milestone " + tx.claim().milestoneHit().streakLength() : "") + ")"); // checklist 11
                deliver(uuid, today.toString(), playerName);
            }
            return tx.claim();
        });
        return result.whenComplete((r, e) -> {
            claiming.remove(uuid);
            if (e != null) {
                logger.log(Level.WARNING, "[daily] Claim failed for " + playerName, e);
            }
        });
    }

    private int rollTierIndex(DailyRewardConfig cfg, int streak) {
        List<DailyRewardConfig.DailyRewardTier> tiers = cfg.tiers();
        double multiplier = Math.min(1.0 + streak * cfg.streakMultiplierPerDay(), cfg.maxStreakMultiplier());
        double[] weights = new double[tiers.size()];
        double total = 0.0;
        for (int i = 0; i < tiers.size(); i++) {
            double weight = tiers.get(i).weight();
            weights[i] = i == 0 ? weight : weight * multiplier;
            total += weights[i];
        }
        double roll = random.nextDouble() * total;
        double cumulative = 0.0;
        for (int i = 0; i < weights.length; i++) {
            cumulative += weights[i];
            if (roll < cumulative) {
                return i;
            }
        }
        return tiers.size() - 1;
    }

    // ------------------------------------------------------------------ giving

    /** Runs a PENDING claim's commands exactly once. */
    private void deliver(UUID uuid, String day, String playerName) {
        db.async(c -> Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE pvp_daily_claims SET state = ? WHERE uuid = ? AND day = ? AND state = ?")) {
                ps.setString(1, GIVEN);
                ps.setString(2, uuid.toString());
                ps.setString(3, day);
                ps.setString(4, PENDING);
                if (ps.executeUpdate() != 1) {
                    return null; // already given (checklist 2)
                }
            }
            try (PreparedStatement ps = tx.prepareStatement("SELECT commands FROM pvp_daily_claims WHERE uuid = ? AND day = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, day);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : "";
                }
            }
        })).whenComplete((commands, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "[daily] Could not give " + playerName + "'s daily reward for " + day + "; it is retried on their next join", e);
                return;
            }
            if (commands != null) {
                dispatch(uuid, day, playerName, commands);
            }
        });
    }

    private void dispatch(UUID uuid, String day, String playerName, String commands) {
        String key = uuid + ":" + day;
        Runnable revert = () -> {
            try {
                db.sync(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_daily_claims SET state = ? WHERE uuid = ? AND day = ?")) {
                        ps.setString(1, PENDING);
                        ps.setString(2, uuid.toString());
                        ps.setString(3, day);
                        return ps.executeUpdate();
                    }
                });
            } catch (SQLException ex) {
                logger.log(Level.SEVERE, "[daily] " + playerName + "'s daily reward for " + day + " was not given and could not be re-queued; run these by hand: " + commands, ex);
            }
        };
        pendingDispatch.put(key, revert);
        try {
            Tasks.global.run(() -> {
                if (pendingDispatch.remove(key) == null) {
                    return; // re-queued at shutdown
                }
                for (String raw : commands.split("\n")) {
                    if (raw.isBlank()) {
                        continue;
                    }
                    String command = raw.replace("{player}", playerName).replace("{uuid}", uuid.toString());
                    try {
                        if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                            logger.severe("Daily reward command failed for " + playerName + ": /" + command
                                    + " (unknown command or it refused to run). The claim was already recorded, so run it by hand if the player is owed it.");
                        }
                    } catch (Exception ex) {
                        logger.log(Level.SEVERE, "Daily reward command threw for " + playerName + ": /" + command
                                + ". The claim was already recorded, so run it by hand if the player is owed it.", ex);
                    }
                }
            });
        } catch (RuntimeException notRunning) {
            if (pendingDispatch.remove(key) != null) {
                revert.run();
            }
        }
    }

    /** Gives every claim of this player still PENDING (after a crash or a stop mid-claim). */
    void deliverPending(UUID uuid, String playerName) {
        db.async(c -> {
            List<String> days = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT day FROM pvp_daily_claims WHERE uuid = ? AND state = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, PENDING);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        days.add(rs.getString(1));
                    }
                }
            }
            return days;
        }).whenComplete((days, e) -> {
            if (e != null) {
                logger.log(Level.WARNING, "[daily] Could not check pending daily rewards for " + playerName, e);
                return;
            }
            for (String day : days) {
                deliver(uuid, day, playerName);
            }
        });
    }

    /** At shutdown: re-queues claims whose commands had not run yet, so they are given on next join. */
    void shutdown() {
        for (String key : List.copyOf(pendingDispatch.keySet())) {
            Runnable revert = pendingDispatch.remove(key);
            if (revert != null) {
                revert.run();
            }
        }
    }

    private static Stored readStats(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT last_day, current_streak, longest_streak, total_claims FROM pvp_daily_stats WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return new Stored(DailyStats.parseDay(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                }
            }
        }
        return new Stored(null, 0, 0, 0);
    }
}
