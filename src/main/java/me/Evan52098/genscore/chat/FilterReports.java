package me.Evan52098.genscore.chat;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Turns repeated filter hits into a report. Every blocked message in a counted category is a strike; {@code strikes}
 * strikes within {@code window-minutes} file one report (kind {@code filter}) in {@code pvp_reports} with the blocked
 * messages as evidence. While that report is open, later strikes are added to it instead of opening another. Staff
 * accept (mute on the ladder) or decline it in /reports. Nothing here punishes on its own.
 */
final class FilterReports {

    static final String REPORTER = "00000000-0000-0000-0000-000000000000";
    static final String REPORTER_NAME = "Chat Filter";
    private static final int MAX_EVIDENCE = 4000;

    private record Strike(long at, String category, String message) {
    }

    private final Database db;
    private final Logger log;
    private final boolean enabled;
    private final int threshold;
    private final long windowMillis;
    private final Set<String> categories;
    private final Map<UUID, Deque<Strike>> strikes = new ConcurrentHashMap<>();

    FilterReports(@Nullable ConfigurationSection s, Database db, Logger log) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        this.db = db;
        this.log = log;
        enabled = s.getBoolean("enabled", true);
        threshold = Math.max(1, s.getInt("strikes", 3));
        windowMillis = Math.max(1, s.getLong("window-minutes", 30)) * 60_000L;
        List<String> cats = s.getStringList("categories");
        categories = Set.copyOf(cats.isEmpty() ? List.of("high-risk", "blocked-word", "advertising") : cats);
    }

    /** Called from the async chat thread after a message was blocked. */
    void strike(Player player, String raw, ChatFilter.Result result) {
        if (!enabled || !categories.contains(result.category())) {
            return;
        }
        long now = System.currentTimeMillis();
        String word = result.matchedWord();
        Strike s = new Strike(now, result.category() + (word == null ? "" : " \"" + word + "\""), raw);
        Deque<Strike> list = strikes.computeIfAbsent(player.getUniqueId(), k -> new ArrayDeque<>());
        List<Strike> batch;
        synchronized (list) {
            list.addLast(s);
            while (!list.isEmpty() && now - list.peekFirst().at() > windowMillis) {
                list.pollFirst();
            }
            if (list.size() < threshold) {
                return;
            }
            batch = new ArrayList<>(list);
            list.clear();
        }
        file(player.getUniqueId(), player.getName(), batch);
    }

    void forget(UUID player) {
        strikes.remove(player);
    }

    private void file(UUID target, String name, List<Strike> batch) {
        SimpleDateFormat time = new SimpleDateFormat("HH:mm");
        StringBuilder lines = new StringBuilder();
        for (Strike st : batch) {
            lines.append('[').append(time.format(new Date(st.at()))).append("] (").append(st.category()).append(") ")
                    .append(st.message().length() > 200 ? st.message().substring(0, 200) : st.message()).append('\n');
        }
        String reason = batch.size() + " blocked messages in " + (windowMillis / 60_000) + " minutes";
        db.async(c -> {
            long id = -1;
            String evidence = "";
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, evidence FROM pvp_reports WHERE target = ? AND kind = 'filter' AND open = 1 ORDER BY id DESC LIMIT 1")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        id = rs.getLong(1);
                        evidence = rs.getString(2) == null ? "" : rs.getString(2);
                    }
                }
            }
            if (id >= 0) {
                String merged = evidence + lines;
                if (merged.length() > MAX_EVIDENCE) {
                    merged = merged.substring(merged.length() - MAX_EVIDENCE);
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_reports SET evidence = ?, reason = ?, at = ? WHERE id = ?")) {
                    ps.setString(1, merged);
                    ps.setString(2, (merged.split("\n").length) + " blocked messages (still open)");
                    ps.setLong(3, System.currentTimeMillis());
                    ps.setLong(4, id);
                    ps.executeUpdate();
                }
                return new long[]{id, 0};
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_reports (reporter, reporter_name, target, target_name, reason, at, open, kind, evidence) "
                            + "VALUES (?, ?, ?, ?, ?, ?, 1, 'filter', ?) RETURNING id")) {
                ps.setString(1, REPORTER);
                ps.setString(2, REPORTER_NAME);
                ps.setString(3, target.toString());
                ps.setString(4, name);
                ps.setString(5, reason);
                ps.setLong(6, System.currentTimeMillis());
                ps.setString(7, lines.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return new long[]{rs.getLong(1), 1};
                }
            }
        }).whenComplete((r, error) -> {
            if (error != null) {
                log.log(Level.WARNING, "[chat] could not file a filter report for " + name, error);
                return;
            }
            boolean opened = r[1] == 1;
            Component alert = Msg.parse("<gold>[Report] <white>" + Msg.escape(name) + "</white> <gray>"
                    + (opened ? "tripped the chat filter " + batch.size() + " times" : "kept tripping the chat filter")
                    + " (report #" + r[0] + ").</gray> <aqua><click:run_command:'/reports view " + r[0] + "'>[review]</click></aqua>");
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("genscore.reports")) {
                    Tasks.onEntity(staff, () -> staff.sendMessage(alert));
                }
            }
            Bukkit.getConsoleSender().sendMessage(alert);
        });
    }
}
