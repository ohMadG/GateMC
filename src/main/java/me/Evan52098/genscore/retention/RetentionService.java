package me.Evan52098.genscore.retention;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.storage.Database;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * One row per player per day they were online ({@code gens_activity}), with the number of joins and the time played
 * that day. Days use {@code retention.timezone}. A player's cohort is the day of {@code first_join} in
 * {@code pvp_players}.
 */
public final class RetentionService implements Listener {

    static final int[] DAYS = {1, 3, 7, 14, 30};
    private static final int WINDOW = 90;
    private static final String UPSERT = "INSERT INTO gens_activity (uuid, day, sessions, playtime_ms) VALUES (?, ?, ?, ?) "
            + "ON CONFLICT (uuid, day) DO UPDATE SET sessions = gens_activity.sessions + excluded.sessions, "
            + "playtime_ms = gens_activity.playtime_ms + excluded.playtime_ms";

    private final GensCore plugin;
    private final Database db;
    private final Map<UUID, Long> online = new ConcurrentHashMap<>();
    private volatile ZoneId zone;
    private volatile boolean closed;

    RetentionService(GensCore plugin, String zone) {
        this.plugin = plugin;
        this.db = plugin.database();
        zone(zone);
    }

    void zone(String id) {
        try {
            zone = ZoneId.of(id);
        } catch (DateTimeException e) {
            plugin.getLogger().warning("retention.timezone '" + id + "' is not a time zone; using America/New_York");
            zone = ZoneId.of("America/New_York");
        }
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }

    private LocalDate day(long millis) {
        return Instant.ofEpochMilli(millis).atZone(zone).toLocalDate();
    }

    void backfill() throws SQLException {
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM gens_activity LIMIT 1"); ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return null;
                }
            }
            List<String[]> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, first_join, last_seen FROM pvp_players"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new String[]{rs.getString(1), day(rs.getLong(2)).toString(), day(rs.getLong(3)).toString()});
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO gens_activity (uuid, day, sessions, playtime_ms) VALUES (?, ?, 1, 0) "
                    + "ON CONFLICT (uuid, day) DO NOTHING")) {
                for (String[] r : rows) {
                    for (int i = 1; i <= 2; i++) {
                        ps.setString(1, r[0]);
                        ps.setString(2, r[i]);
                        ps.addBatch();
                    }
                }
                ps.executeBatch();
            }
            plugin.getLogger().info("Retention: started tracking with " + rows.size() + " existing players");
            return null;
        });
    }

    void track(Player player) {
        online.put(player.getUniqueId(), System.currentTimeMillis());
        write(Map.of(player.getUniqueId(), new long[]{1, 0}));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        track(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Long since = online.remove(event.getPlayer().getUniqueId());
        if (since != null) {
            write(Map.of(event.getPlayer().getUniqueId(), new long[]{0, Math.max(0, System.currentTimeMillis() - since)}));
        }
    }

    private Map<UUID, long[]> elapsed() {
        long now = System.currentTimeMillis();
        Map<UUID, long[]> out = new HashMap<>();
        for (Map.Entry<UUID, Long> e : online.entrySet()) {
            Long before = online.replace(e.getKey(), now);
            if (before != null) {
                out.put(e.getKey(), new long[]{0, Math.max(0, now - before)});
            }
        }
        return out;
    }

    void flush() {
        Map<UUID, long[]> rows = elapsed();
        if (!rows.isEmpty()) {
            write(rows);
        }
    }

    void flushNow() {
        closed = true;
        Map<UUID, long[]> rows = elapsed();
        if (rows.isEmpty()) {
            return;
        }
        try {
            db.sync(c -> {
                upsert(c, today().toString(), rows);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "Retention: could not save playtime at shutdown", e);
        }
    }

    private void write(Map<UUID, long[]> rows) {
        if (closed) {
            return;
        }
        String day = today().toString();
        db.asyncRun(c -> upsert(c, day, rows)).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Retention: could not record activity", e);
            return null;
        });
    }

    private static void upsert(Connection c, String day, Map<UUID, long[]> rows) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(UPSERT)) {
            for (Map.Entry<UUID, long[]> e : rows.entrySet()) {
                ps.setString(1, e.getKey().toString());
                ps.setString(2, day);
                ps.setLong(3, e.getValue()[0]);
                ps.setLong(4, e.getValue()[1]);
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    record Rate(int returned, int cohort, boolean partial) {
    }

    record Cohort(LocalDate day, int size, Map<Integer, Rate> rates) {
    }

    record DayStats(LocalDate day, int active, int fresh, long playtimeMs) {
    }

    record Report(LocalDate today, LocalDate since, int players, int weekly, int monthly, List<DayStats> days,
                  Map<Integer, Rate> retention, List<Cohort> cohorts) {
    }

    CompletableFuture<Report> report(int cohortDays) {
        flush();
        LocalDate today = today();
        LocalDate from = today.minusDays(WINDOW);
        return db.async(c -> {
            Map<UUID, LocalDate> first = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, first_join FROM pvp_players"); ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    first.put(UUID.fromString(rs.getString(1)), day(rs.getLong(2)));
                }
            }
            Map<LocalDate, Set<UUID>> active = new TreeMap<>();
            Map<LocalDate, Long> playtime = new HashMap<>();
            LocalDate since = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT uuid, day, playtime_ms FROM gens_activity WHERE day >= ?")) {
                ps.setString(1, from.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        LocalDate d = LocalDate.parse(rs.getString(2));
                        active.computeIfAbsent(d, k -> new HashSet<>()).add(UUID.fromString(rs.getString(1)));
                        playtime.merge(d, rs.getLong(3), Long::sum);
                        since = since == null || d.isBefore(since) ? d : since;
                    }
                }
            }
            if (since == null) {
                since = today;
            }
            Map<LocalDate, List<UUID>> cohorts = new TreeMap<>();
            for (Map.Entry<UUID, LocalDate> e : first.entrySet()) {
                if (!e.getValue().isBefore(since) && !e.getValue().isAfter(today)) {
                    cohorts.computeIfAbsent(e.getValue(), k -> new ArrayList<>()).add(e.getKey());
                }
            }

            Map<Integer, int[]> totals = new TreeMap<>();
            Map<Integer, Boolean> partial = new HashMap<>();
            List<Cohort> recent = new ArrayList<>();
            for (Map.Entry<LocalDate, List<UUID>> e : cohorts.entrySet()) {
                Map<Integer, Rate> rates = new TreeMap<>();
                for (int n : DAYS) {
                    LocalDate target = e.getKey().plusDays(n);
                    if (target.isAfter(today)) {
                        continue;
                    }
                    Set<UUID> on = active.getOrDefault(target, Set.of());
                    int back = (int) e.getValue().stream().filter(on::contains).count();
                    boolean open = target.equals(today);
                    rates.put(n, new Rate(back, e.getValue().size(), open));
                    int[] t = totals.computeIfAbsent(n, k -> new int[2]);
                    t[0] += back;
                    t[1] += e.getValue().size();
                    partial.merge(n, open, Boolean::logicalOr);
                }
                if (!e.getKey().isBefore(today.minusDays(cohortDays - 1))) {
                    recent.add(new Cohort(e.getKey(), e.getValue().size(), rates));
                }
            }
            Map<Integer, Rate> retention = new TreeMap<>();
            totals.forEach((n, t) -> retention.put(n, new Rate(t[0], t[1], partial.getOrDefault(n, false))));

            List<DayStats> days = new ArrayList<>();
            for (int i = 6; i >= 0; i--) {
                LocalDate d = today.minusDays(i);
                if (d.isBefore(since)) {
                    continue;
                }
                List<UUID> fresh = cohorts.getOrDefault(d, List.of());
                days.add(new DayStats(d, active.getOrDefault(d, Set.of()).size(), fresh.size(), playtime.getOrDefault(d, 0L)));
            }
            return new Report(today, since, first.size(), unique(active, today, 7), unique(active, today, 30), days, retention, recent);
        });
    }

    private static int unique(Map<LocalDate, Set<UUID>> active, LocalDate today, int days) {
        Set<UUID> all = new HashSet<>();
        for (int i = 0; i < days; i++) {
            all.addAll(active.getOrDefault(today.minusDays(i), Set.of()));
        }
        return all.size();
    }
}
