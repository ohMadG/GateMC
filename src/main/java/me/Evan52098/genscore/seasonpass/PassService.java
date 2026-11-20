package me.Evan52098.genscore.seasonpass;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.rewardbox.Reward;
import me.Evan52098.genscore.rewardbox.RewardBox;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Season pass state: XP and challenge progress of online players (in memory, saved every minute, on quit, after each
 * completed challenge and at stop), and tier claims (decided in the database).
 *
 * <p>Claim safety: {@code pvp_pass_claims} has the primary key (uuid, season, track, tier); a claim is one transaction
 * that inserts that row and writes the tier's rewards into the reward box, so one tier is claimed once whatever the
 * clicks, relogs or threads (checklist 2 and 7). All database work for one player goes through one queue
 * ({@link #serial}) so a quit save, a rejoin load and a claim never interleave.
 */
final class PassService {

    enum ClaimResult { OK, NOT_ACTIVE, NOT_LOADED, LOCKED, NOT_PREMIUM, ALREADY, NOTHING, BUSY, ERROR }

    private final GensCore plugin;
    private final Database db;
    private final Logger logger;
    private final RewardBox box;
    private volatile PassConfig config;
    private final Map<UUID, PassPlayer> players = new ConcurrentHashMap<>();
    private final Map<UUID, CompletableFuture<Void>> chains = new ConcurrentHashMap<>();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();

    PassService(GensCore plugin, PassConfig config, RewardBox box) {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        this.config = config;
        this.box = box;
    }

    static String schema() {
        return "CREATE TABLE IF NOT EXISTS pvp_pass_progress ("
                + "uuid TEXT NOT NULL, season TEXT NOT NULL, xp BIGINT NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid, season));"
                + "CREATE TABLE IF NOT EXISTS pvp_pass_challenges ("
                + "uuid TEXT NOT NULL, period TEXT NOT NULL, challenge TEXT NOT NULL, "
                + "progress BIGINT NOT NULL DEFAULT 0, done INTEGER NOT NULL DEFAULT 0, "
                + "PRIMARY KEY (uuid, period, challenge));"
                + "CREATE TABLE IF NOT EXISTS pvp_pass_claims ("
                + "uuid TEXT NOT NULL, season TEXT NOT NULL, track TEXT NOT NULL, tier INTEGER NOT NULL, "
                + "claimed_at BIGINT NOT NULL, "
                + "PRIMARY KEY (uuid, season, track, tier))";
    }

    PassConfig config() {
        return config;
    }

    RewardBox box() {
        return box;
    }

    @Nullable PassPlayer player(UUID uuid) {
        return players.get(uuid);
    }

    // ------------------------------------------------------------------ per-player queue

    private <T> CompletableFuture<T> serial(UUID uuid, Database.SqlFunction<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        CompletableFuture<?>[] holder = new CompletableFuture<?>[1];
        chains.compute(uuid, (id, previous) -> {
            CompletableFuture<Void> after = previous == null ? CompletableFuture.completedFuture(null) : previous;
            CompletableFuture<Void> next = after.handle((v, e) -> null).thenCompose(v -> db.async(work).handle((r, e) -> {
                if (e != null) {
                    result.completeExceptionally(e);
                } else {
                    result.complete(r);
                }
                return null;
            }));
            holder[0] = next;
            return next;
        });
        holder[0].whenComplete((v, e) -> chains.remove(uuid, holder[0]));
        return result;
    }

    // ------------------------------------------------------------------ load / save

    void join(UUID uuid, String name) {
        PassConfig cfg = config;
        PassPlayer pp = new PassPlayer(uuid, name, cfg.seasonId());
        players.put(uuid, pp);
        Instant now = Instant.now();
        String daily = cfg.dailyKey(now);
        String weekly = cfg.weeklyKey(now);
        serial(uuid, c -> {
            long xp = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT xp FROM pvp_pass_progress WHERE uuid = ? AND season = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pp.season);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        xp = rs.getLong(1);
                    }
                }
            }
            Map<String, PassPlayer.Progress> rows = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT period, challenge, progress, done FROM pvp_pass_challenges "
                    + "WHERE uuid = ? AND (period = ? OR period = ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, daily);
                ps.setString(3, weekly);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.put(rs.getString(1) + "|" + rs.getString(2), new PassPlayer.Progress(rs.getLong(3), rs.getInt(4) == 1));
                    }
                }
            }
            Set<String> claims = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT track, tier FROM pvp_pass_claims WHERE uuid = ? AND season = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pp.season);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        claims.add(rs.getString(1) + ":" + rs.getInt(2));
                    }
                }
            }
            pp.loaded(xp, rows, claims);
            return null;
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[seasonpass] Could not load the pass of " + name + "; their progress is not counted this session", e);
            return null;
        });
    }

    void quit(UUID uuid) {
        PassPlayer pp = players.remove(uuid);
        if (pp != null && pp.isLoaded()) {
            save(pp);
        }
    }

    CompletableFuture<Void> save(PassPlayer pp) {
        return this.<Void>serial(pp.uuid, c -> {
            write(c, pp);
            return null;
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[seasonpass] Could not save the pass of " + pp.name + "; retried at the next save", e);
            return null;
        });
    }

    void saveAll() {
        for (PassPlayer pp : players.values()) {
            if (pp.isLoaded()) {
                save(pp);
            }
        }
    }

    /** At disable: waits for queued work, then writes everyone on this thread. */
    void shutdown() {
        try {
            CompletableFuture.allOf(chains.values().toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            logger.warning("[seasonpass] Some pass saves were still running at shutdown.");
        }
        for (PassPlayer pp : players.values()) {
            if (!pp.isLoaded()) {
                continue;
            }
            try {
                db.sync(c -> {
                    write(c, pp);
                    return null;
                });
            } catch (SQLException e) {
                logger.log(Level.WARNING, "[seasonpass] Could not save the pass of " + pp.name + " at shutdown", e);
            }
        }
    }

    private void write(Connection c, PassPlayer pp) throws SQLException {
        PassConfig cfg = config;
        Instant now = Instant.now();
        PassPlayer.Snapshot s = pp.drain(Set.of(cfg.dailyKey(now), cfg.weeklyKey(now)));
        if (!s.xpDirty() && s.rows().isEmpty()) {
            return;
        }
        try {
            Database.transaction(c, tx -> {
                if (s.xpDirty()) {
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_pass_progress (uuid, season, xp) VALUES (?, ?, ?) "
                            + "ON CONFLICT (uuid, season) DO UPDATE SET xp = excluded.xp")) {
                        ps.setString(1, pp.uuid.toString());
                        ps.setString(2, pp.season);
                        ps.setLong(3, s.xp());
                        ps.executeUpdate();
                    }
                }
                if (!s.rows().isEmpty()) {
                    try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_pass_challenges (uuid, period, challenge, progress, done) "
                            + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uuid, period, challenge) DO UPDATE SET "
                            + "progress = excluded.progress, done = excluded.done")) {
                        for (Map.Entry<String, long[]> e : s.rows().entrySet()) {
                            int cut = e.getKey().lastIndexOf('|');
                            ps.setString(1, pp.uuid.toString());
                            ps.setString(2, e.getKey().substring(0, cut));
                            ps.setString(3, e.getKey().substring(cut + 1));
                            ps.setLong(4, e.getValue()[0]);
                            ps.setInt(5, (int) e.getValue()[1]);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                }
                return null;
            });
        } catch (SQLException e) {
            pp.undrain(s);
            throw e;
        }
    }

    // ------------------------------------------------------------------ progress

    /** Counts toward today's and this week's challenges. Safe from any thread. */
    void progress(UUID uuid, Objective type, @Nullable String what, long amount) {
        PassConfig cfg = config;
        Instant now = Instant.now();
        if (amount <= 0 || !cfg.active(now)) {
            return;
        }
        PassPlayer pp = players.get(uuid);
        if (pp == null || !pp.isLoaded() || !pp.season.equals(cfg.seasonId())) {
            return;
        }
        List<PassConfig.Challenge> completed = new ArrayList<>();
        count(pp, cfg.dailyKey(now), cfg.todaysDaily(now), type, what, amount, completed);
        count(pp, cfg.weeklyKey(now), cfg.thisWeeksWeekly(now), type, what, amount, completed);
        if (completed.isEmpty()) {
            return;
        }
        long before = pp.xp();
        long gained = 0;
        for (PassConfig.Challenge ch : completed) {
            gained += ch.xp();
        }
        pp.addXp(gained);
        int tierBefore = cfg.tierFor(before);
        int tierAfter = cfg.tierFor(pp.xp());
        save(pp); // a completed challenge is saved at once
        logger.info("[seasonpass] " + pp.name + " completed " + completed.size() + " challenge(s), +" + gained + " XP (tier " + tierAfter + ")");
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            Tasks.entity.run(player, () -> {
                for (PassConfig.Challenge ch : completed) {
                    player.sendMessage(Msg.good("Challenge complete: " + RewardBox.legacyToMini(ch.name()) + " <white>+" + ch.xp() + " pass XP</white>"));
                }
                if (tierAfter > tierBefore) {
                    player.sendMessage(Msg.neutral("You reached <white>tier " + tierAfter + "</white> of the " + Msg.escape(cfg.seasonName())
                            + " pass. Claim your rewards with <click:run_command:/pass><white><underlined>/pass</underlined></white></click>."));
                }
            });
        }
    }

    private static void count(PassPlayer pp, String period, List<PassConfig.Challenge> list, Objective type, @Nullable String what,
                              long amount, List<PassConfig.Challenge> completed) {
        for (PassConfig.Challenge ch : list) {
            if (ch.type() == type && ch.matches(what) && pp.add(period + "|" + ch.id(), amount, ch.amount())) {
                completed.add(ch);
            }
        }
    }

    boolean killCounts(UUID killer, UUID victim) {
        PassPlayer pp = players.get(killer);
        return pp != null && pp.killCounts(victim, config.killSameVictimMinutes() * 60_000L, System.currentTimeMillis());
    }

    void playtimeTick() {
        PassConfig cfg = config;
        if (!cfg.active(Instant.now())) {
            return;
        }
        EssentialsModule ess = plugin.modules().get(EssentialsModule.class);
        for (Player p : Bukkit.getOnlinePlayers()) {
            UUID id = p.getUniqueId();
            if (!cfg.countAfkPlaytime() && ess != null && ess.social().isAfk(id)) {
                continue;
            }
            progress(id, Objective.PLAYTIME_MINUTES, null, 1);
        }
    }

    // ------------------------------------------------------------------ claims

    /** Call on the player's thread (permission check). Completes on a database thread. */
    CompletableFuture<ClaimResult> claim(Player player, PassConfig.Track track, int tier) {
        PassConfig cfg = config;
        UUID uuid = player.getUniqueId();
        if (!cfg.active(Instant.now())) {
            return CompletableFuture.completedFuture(ClaimResult.NOT_ACTIVE);
        }
        PassPlayer pp = players.get(uuid);
        if (pp == null || !pp.isLoaded() || !pp.season.equals(cfg.seasonId())) {
            return CompletableFuture.completedFuture(ClaimResult.NOT_LOADED);
        }
        if (tier < 1 || tier > cfg.tierCount() || pp.xp() < cfg.xpForTier(tier)) {
            return CompletableFuture.completedFuture(ClaimResult.LOCKED);
        }
        if (track == PassConfig.Track.PREMIUM && !player.hasPermission(SeasonPassModule.PREMIUM)) {
            return CompletableFuture.completedFuture(ClaimResult.NOT_PREMIUM);
        }
        if (pp.hasClaimed(track, tier)) {
            return CompletableFuture.completedFuture(ClaimResult.ALREADY);
        }
        PassConfig.Tier t = cfg.tier(tier);
        List<Reward> rewards = t == null ? List.of() : (track == PassConfig.Track.FREE ? t.free() : t.premium());
        if (rewards.isEmpty()) {
            return CompletableFuture.completedFuture(ClaimResult.NOTHING);
        }
        if (!claiming.add(uuid)) {
            return CompletableFuture.completedFuture(ClaimResult.BUSY); // checklist 3
        }
        long xp = pp.xp();
        String name = player.getName();
        String season = pp.season;
        String source = "pass:" + season + ":" + track.name().toLowerCase() + ":" + tier;
        return serial(uuid, c -> Database.transaction(c, tx -> {
            // The XP that unlocked this tier is stored with the claim, so a crash cannot leave a claim without its XP.
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_pass_progress (uuid, season, xp) VALUES (?, ?, ?) "
                    + "ON CONFLICT (uuid, season) DO UPDATE SET xp = CASE WHEN excluded.xp > pvp_pass_progress.xp "
                    + "THEN excluded.xp ELSE pvp_pass_progress.xp END")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, season);
                ps.setLong(3, xp);
                ps.executeUpdate();
            }
            // Checklist 2: the primary key lets exactly one claim of this tier through.
            try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_pass_claims (uuid, season, track, tier, claimed_at) "
                    + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (uuid, season, track, tier) DO NOTHING")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, season);
                ps.setString(3, track.name());
                ps.setInt(4, tier);
                ps.setLong(5, System.currentTimeMillis());
                if (ps.executeUpdate() != 1) {
                    return ClaimResult.ALREADY;
                }
            }
            box.grant(tx, uuid, name, source, rewards); // checklist 1 and 7: rewards recorded with the claim, given after
            return ClaimResult.OK;
        })).handle((r, e) -> {
            claiming.remove(uuid);
            if (e != null) {
                logger.log(Level.WARNING, "[seasonpass] Claim of " + source + " by " + name + " failed", e);
                return ClaimResult.ERROR;
            }
            if (r == ClaimResult.OK || r == ClaimResult.ALREADY) {
                pp.markClaimed(track, tier);
            }
            if (r == ClaimResult.OK) {
                logger.info("[seasonpass] " + name + " claimed " + source); // checklist 11
                box.deliver(uuid);
            }
            return r;
        });
    }

    // ------------------------------------------------------------------ admin / reload

    boolean addXp(UUID uuid, long amount, boolean set) {
        PassPlayer pp = players.get(uuid);
        if (pp == null || !pp.isLoaded()) {
            return false;
        }
        if (set) {
            pp.setXp(amount);
        } else {
            pp.addXp(amount);
        }
        save(pp);
        return true;
    }

    void config(PassConfig updated) {
        PassConfig old = config;
        config = updated;
        if (!old.seasonId().equals(updated.seasonId())) {
            // New season: save the old season's state and load everyone online into the new one.
            for (PassPlayer pp : List.copyOf(players.values())) {
                players.remove(pp.uuid, pp);
                if (pp.isLoaded()) {
                    save(pp);
                }
                join(pp.uuid, pp.name);
            }
        }
    }
}
