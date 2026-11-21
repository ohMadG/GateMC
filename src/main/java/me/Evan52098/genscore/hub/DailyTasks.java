package me.Evan52098.genscore.hub;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class DailyTasks {

    enum Type {
        SELL("Sell %s items off your belt", "HOPPER"),
        EARN("Earn %s from your belt", "GOLD_INGOT"),
        UPGRADE("Buy %s factory upgrades", "ANVIL"),
        RAID("Break %s belt segments in raids", "IRON_PICKAXE"),
        CACHE("Open %s caches", "CHEST"),
        PLAYTIME("Play for %s minutes", "CLOCK"),
        DEFEND("Stop %s raiders with your turrets", "DISPENSER");

        final String text;
        final String icon;

        Type(String text, String icon) {
            this.text = text;
            this.icon = icon;
        }
    }

    static final class Task {
        final Type type;
        final long target;
        final long rewardCents;
        final int rewardTokens;
        final int difficulty;
        long progress;
        boolean done;

        Task(Type type, long target, long rewardCents, int rewardTokens, int difficulty, long progress, boolean done) {
            this.type = type;
            this.target = target;
            this.rewardCents = rewardCents;
            this.rewardTokens = rewardTokens;
            this.difficulty = difficulty;
            this.progress = progress;
            this.done = done;
        }

        String encode() {
            return type.name() + ":" + target + ":" + rewardCents + ":" + rewardTokens + ":" + difficulty + ":" + progress + ":" + (done ? 1 : 0);
        }

        static Task decode(String s) {
            String[] p = s.split(":");
            return new Task(Type.valueOf(p[0]), Long.parseLong(p[1]), Long.parseLong(p[2]), Integer.parseInt(p[3]),
                    Integer.parseInt(p[4]), Long.parseLong(p[5]), "1".equals(p[6]));
        }
    }

    static final class Day {
        String day = "";
        final List<Task> tasks = new ArrayList<>();
        boolean bonusPaid;
        String lastClaim = "";
        int streak;
        volatile boolean dirty;

        synchronized String encode() {
            StringBuilder sb = new StringBuilder((bonusPaid ? "B" : "-") + "|" + lastClaim + "|" + streak);
            for (Task t : tasks) {
                sb.append(';').append(t.encode());
            }
            return sb.toString();
        }

        synchronized void decode(String day, String data) {
            this.day = day;
            tasks.clear();
            if (data == null || data.isEmpty()) {
                return;
            }
            String[] parts = data.split(";");
            String[] head = parts[0].split("\\|", -1);
            bonusPaid = head[0].equals("B");
            lastClaim = head.length > 1 ? head[1] : "";
            try {
                streak = head.length > 2 ? Integer.parseInt(head[2]) : 0;
            } catch (NumberFormatException e) {
                streak = 0;
            }
            for (int i = 1; i < parts.length; i++) {
                try {
                    tasks.add(Task.decode(parts[i]));
                } catch (RuntimeException ignored) {
                }
            }
        }
    }

    static final PlayerSection<Day> SECTION = new PlayerSection<>() {
        @Override
        public String id() {
            return "dailytasks";
        }

        @Override
        public Day load(Connection c, UUID player) throws SQLException {
            Day d = new Day();
            try (PreparedStatement ps = c.prepareStatement("SELECT day, data FROM gens_daily_tasks WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        d.decode(rs.getString(1), rs.getString(2));
                    }
                }
            }
            return d;
        }

        @Override
        public void save(Connection c, UUID player, Day data) throws SQLException {
            if (!data.dirty) {
                return;
            }
            data.dirty = false;
            String day;
            String encoded;
            synchronized (data) {
                day = data.day;
                encoded = data.encode();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO gens_daily_tasks (uuid, day, data) VALUES (?, ?, ?) "
                    + "ON CONFLICT (uuid) DO UPDATE SET day = excluded.day, data = excluded.data")) {
                ps.setString(1, player.toString());
                ps.setString(2, day);
                ps.setString(3, encoded);
                ps.executeUpdate();
            }
        }
    };

    private DailyTasks() {
    }
}
