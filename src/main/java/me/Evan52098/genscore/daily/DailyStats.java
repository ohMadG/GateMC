package me.Evan52098.genscore.daily;

import me.Evan52098.genscore.player.PlayerSection;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A player's daily-reward record (BlockvilleCore's DailyRewardData), cached while they are online. Stores the last
 * claimed <b>day</b> (a date in the reset time zone), not a timer, so streaks are right across midnight and restarts.
 * Thread-safe. The database row is written only by the claim transaction, never by the player-data saver, so a stale
 * cache can never overwrite a claim.
 */
final class DailyStats {

    private @Nullable LocalDate lastDay;
    private int currentStreak;
    private int longestStreak;
    private int totalClaims;

    synchronized @Nullable LocalDate lastDay() {
        return lastDay;
    }

    synchronized int currentStreak() {
        return currentStreak;
    }

    synchronized int longestStreak() {
        return longestStreak;
    }

    synchronized int totalClaims() {
        return totalClaims;
    }

    synchronized void set(@Nullable LocalDate lastDay, int current, int longest, int total) {
        this.lastDay = lastDay;
        this.currentStreak = current;
        this.longestStreak = longest;
        this.totalClaims = total;
    }

    /** The streak as it stands today: it is broken (0) once a whole day was missed. */
    synchronized int liveStreak(LocalDate today) {
        return lastDay != null && !lastDay.isBefore(today.minusDays(1)) ? currentStreak : 0;
    }

    static @Nullable LocalDate parseDay(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw);
        } catch (RuntimeException e) {
            return null;
        }
    }

    static final PlayerSection<DailyStats> SECTION = new PlayerSection<>() {
        @Override
        public String id() {
            return "daily";
        }

        @Override
        public DailyStats load(Connection c, UUID player) throws SQLException {
            DailyStats stats = new DailyStats();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT last_day, current_streak, longest_streak, total_claims FROM pvp_daily_stats WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        stats.set(parseDay(rs.getString(1)), rs.getInt(2), rs.getInt(3), rs.getInt(4));
                    }
                }
            }
            return stats;
        }

        @Override
        public void save(Connection c, UUID player, DailyStats data) {
            // Written by DailyRewardService's claim transaction only.
        }
    };
}
