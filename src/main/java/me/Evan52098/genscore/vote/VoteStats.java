package me.Evan52098.genscore.vote;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** One online player's voting record (BlockvilleCore's VoteData). Thread-safe. Stored in {@code pvp_vote_stats}. */
public final class VoteStats {

    private int totalVotes;
    private String monthKey = "";
    private int monthVotes;
    private String lastVoteDay = "";
    private int streak;
    private int bestStreak;
    private final Map<String, Long> siteTimes = new LinkedHashMap<>();

    public synchronized int totalVotes() {
        return totalVotes;
    }

    public synchronized int bestStreak() {
        return bestStreak;
    }

    /** Counts a vote cast on {@code day}. Returns {streak before, streak after}. */
    synchronized int[] record(LocalDate day, String siteId, long votedAt) {
        totalVotes++;
        String month = YearMonth.from(day).toString();
        if (month.equals(monthKey)) {
            monthVotes++;
        } else {
            monthKey = month;
            monthVotes = 1;
        }
        int before = streak;
        if (!day.toString().equals(lastVoteDay)) {
            LocalDate last = parse(lastVoteDay);
            if (last == null || !last.isAfter(day)) {
                streak = last != null && last.plusDays(1).equals(day) ? before + 1 : 1;
                lastVoteDay = day.toString();
                bestStreak = Math.max(bestStreak, streak);
            }
        }
        siteTimes.put(siteId.replace(";", "").replace("=", ""), votedAt);
        return new int[]{before, streak};
    }

    record Status(int total, int month, int streak, int bestStreak, boolean votedToday, Map<String, Long> siteTimes) {
    }

    synchronized Status status(LocalDate today) {
        String month = YearMonth.from(today).toString();
        LocalDate last = parse(lastVoteDay);
        int current = last != null && !last.plusDays(1).isBefore(today) ? streak : 0;
        return new Status(totalVotes, month.equals(monthKey) ? monthVotes : 0, current, bestStreak,
                today.toString().equals(lastVoteDay), Map.copyOf(siteTimes));
    }

    static LocalDate parse(String day) {
        if (day == null || day.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(day);
        } catch (Exception e) {
            return null;
        }
    }

    private synchronized String encodeSites() {
        StringBuilder out = new StringBuilder();
        siteTimes.forEach((k, v) -> out.append(out.isEmpty() ? "" : ";").append(k).append('=').append(v));
        return out.toString();
    }

    private void decodeSites(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        for (String part : text.split(";")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                siteTimes.put(part.substring(0, eq), Long.parseLong(part.substring(eq + 1)));
            } catch (NumberFormatException ignored) {
                // skip a bad entry
            }
        }
    }

    static final class Section implements PlayerSection<VoteStats> {

        @Override
        public String id() {
            return "vote";
        }

        @Override
        public VoteStats load(Connection c, UUID player) throws SQLException {
            VoteStats s = new VoteStats();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT total_votes, month_key, month_votes, last_vote_day, streak, best_streak, site_times FROM pvp_vote_stats WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        s.totalVotes = rs.getInt(1);
                        s.monthKey = rs.getString(2);
                        s.monthVotes = rs.getInt(3);
                        s.lastVoteDay = rs.getString(4);
                        s.streak = rs.getInt(5);
                        s.bestStreak = rs.getInt(6);
                        s.decodeSites(rs.getString(7));
                    }
                }
            }
            return s;
        }

        @Override
        public void save(Connection c, UUID player, VoteStats data) throws SQLException {
            String sites = data.encodeSites();
            int total;
            String monthKey;
            int monthVotes;
            String lastDay;
            int streak;
            int best;
            synchronized (data) {
                total = data.totalVotes;
                monthKey = data.monthKey;
                monthVotes = data.monthVotes;
                lastDay = data.lastVoteDay;
                streak = data.streak;
                best = data.bestStreak;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_vote_stats (uuid, total_votes, month_key, month_votes, last_vote_day, streak, best_streak, site_times) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET total_votes = excluded.total_votes, "
                            + "month_key = excluded.month_key, month_votes = excluded.month_votes, last_vote_day = excluded.last_vote_day, "
                            + "streak = excluded.streak, best_streak = excluded.best_streak, site_times = excluded.site_times")) {
                ps.setString(1, player.toString());
                ps.setInt(2, total);
                ps.setString(3, monthKey);
                ps.setInt(4, monthVotes);
                ps.setString(5, lastDay);
                ps.setInt(6, streak);
                ps.setInt(7, best);
                ps.setString(8, sites);
                ps.executeUpdate();
            }
        }
    }
}
