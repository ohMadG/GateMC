package me.Evan52098.genscore.weekly;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.DayOfWeek;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.logging.Level;

public final class WeeklyModule implements GensModule, Listener {

    record Board(String id, String stat, String name, Material icon, boolean money, String unit) {
    }

    record Row(UUID uuid, String name, long value) {
    }

    static final List<Board> BOARDS = List.of(
            new Board("earned", "EARN", "&6&lTop Earners", Material.GOLD_BLOCK, true, ""),
            new Board("raids", "RAID", "&c&lTop Raiders", Material.IRON_PICKAXE, false, " segments"),
            new Board("defends", "DEFEND", "&b&lTop Defenders", Material.SHIELD, false, " raiders stopped"),
            new Board("rebirths", "REBIRTH", "&d&lMost Rebirths", Material.END_CRYSTAL, false, " rebirths"));

    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String[] PLACE_COLOR = {"&6", "&f", "&c"};
    private static final List<List<String>> DEFAULT_PRIZES = List.of(
            List.of("crate give {player} op 1", "credits give {player} 150", "gensreward {player} tokens 100"),
            List.of("crate give {player} amethyst 2", "credits give {player} 75", "gensreward {player} tokens 50"),
            List.of("crate give {player} amethyst 1", "gensreward {player} tokens 30"));
    private static final List<String> PRIZE_TEXT = List.of(
            "Legendary Key, 150 credits, 100 tokens", "2 Epic Keys, 75 credits, 50 tokens", "Epic Key, 30 tokens");

    private static volatile WeeklyModule instance;

    private GensCore plugin;
    private final Map<String, LongAdder> pending = new ConcurrentHashMap<>();
    private final Map<String, List<Row>> top = new ConcurrentHashMap<>();
    private volatile List<List<String>> prizes = DEFAULT_PRIZES;
    private volatile String lastWeek;

    @Override
    public String id() {
        return "weekly";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_weekly (week TEXT NOT NULL, board TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, "
                        + "value BIGINT NOT NULL, PRIMARY KEY (week, board, uuid));"
                        + "CREATE TABLE IF NOT EXISTS gens_weekly_prizes (uuid TEXT NOT NULL, week TEXT NOT NULL, board TEXT NOT NULL, "
                        + "place INTEGER NOT NULL, paid INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (uuid, week, board));"
                        + "CREATE TABLE IF NOT EXISTS gens_weekly_meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)");
        reload();
        lastWeek = plugin.database().sync(c -> meta(c, "last_week"));
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Tasks.async.timer(t -> flush(), 30, 30, TimeUnit.SECONDS);
        Tasks.async.timer(t -> minute(), 5, 60, TimeUnit.SECONDS);
        plugin.getLogger().info("[weekly] week " + week(ZonedDateTime.now(ZONE)) + ", resets in " + resetText() + ".");
    }

    @Override
    public void reload() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("weekly.prizes");
        if (s == null) {
            prizes = DEFAULT_PRIZES;
            return;
        }
        List<List<String>> out = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            List<String> l = s.getStringList(String.valueOf(i));
            out.add(l.isEmpty() ? DEFAULT_PRIZES.get(i - 1) : l);
        }
        prizes = out;
    }

    @Override
    public void disable() {
        instance = null;
        try {
            plugin.database().sync(c -> {
                write(c);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[weekly] could not save on shutdown", e);
        }
    }

    public static void record(UUID player, String stat, long amount) {
        WeeklyModule m = instance;
        if (m == null || amount <= 0) {
            return;
        }
        for (Board b : BOARDS) {
            if (b.stat().equals(stat)) {
                m.pending.computeIfAbsent(player + "|" + b.id(), k -> new LongAdder()).add(amount);
            }
        }
    }

    public static @Nullable String placeholder(UUID player, String key) {
        WeeklyModule m = instance;
        if (m == null) {
            return null;
        }
        return switch (key) {
            case "weekly_reset" -> m.resetText();
            case "weekly_rank" -> {
                List<Row> rows = m.top.get("earned");
                if (rows != null) {
                    for (int i = 0; i < rows.size(); i++) {
                        if (rows.get(i).uuid().equals(player)) {
                            yield "#" + (i + 1);
                        }
                    }
                }
                yield "-";
            }
            default -> null;
        };
    }

    static String week(ZonedDateTime t) {
        return t.get(IsoFields.WEEK_BASED_YEAR) + "-W" + String.format("%02d", t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR));
    }

    String resetText() {
        ZonedDateTime now = ZonedDateTime.now(ZONE);
        ZonedDateTime next = now.with(TemporalAdjusters.next(DayOfWeek.MONDAY)).toLocalDate().atStartOfDay(ZONE);
        long s = Math.max(0, java.time.Duration.between(now, next).getSeconds());
        long d = s / 86400;
        long h = s % 86400 / 3600;
        long m = s % 3600 / 60;
        return d > 0 ? d + "d " + h + "h" : h > 0 ? h + "h " + m + "m" : m + "m";
    }

    private void flush() {
        if (pending.isEmpty()) {
            return;
        }
        try {
            plugin.database().sync(c -> {
                write(c);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[weekly] could not save stats", e);
        }
    }

    private void write(Connection c) throws SQLException {
        String week = week(ZonedDateTime.now(ZONE));
        Map<String, Long> batch = new HashMap<>();
        for (var e : pending.entrySet()) {
            long v = e.getValue().sumThenReset();
            if (v > 0) {
                batch.put(e.getKey(), v);
            }
        }
        if (batch.isEmpty()) {
            return;
        }
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO gens_weekly (week, board, uuid, name, value) VALUES (?, ?, ?, ?, ?) "
                + "ON CONFLICT (week, board, uuid) DO UPDATE SET value = gens_weekly.value + excluded.value, name = excluded.name")) {
            for (var e : batch.entrySet()) {
                String[] k = e.getKey().split("\\|");
                UUID uuid = UUID.fromString(k[0]);
                String name = Bukkit.getOfflinePlayer(uuid).getName();
                ps.setString(1, week);
                ps.setString(2, k[1]);
                ps.setString(3, k[0]);
                ps.setString(4, name == null ? "?" : name);
                ps.setLong(5, e.getValue());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private void minute() {
        try {
            String now = week(ZonedDateTime.now(ZONE));
            plugin.database().sync(c -> {
                if (lastWeek == null) {
                    setMeta(c, "last_week", now);
                    lastWeek = now;
                } else if (!lastWeek.equals(now)) {
                    write(c);
                    award(c, lastWeek);
                    setMeta(c, "last_week", now);
                    lastWeek = now;
                }
                for (Board b : BOARDS) {
                    top.put(b.id(), load(c, now, b.id(), 10));
                }
                return null;
            });
            for (Player p : Bukkit.getOnlinePlayers()) {
                payout(p.getUniqueId());
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[weekly] tick failed", e);
        }
    }

    private void award(Connection c, String week) throws SQLException {
        List<String> lines = new ArrayList<>();
        for (Board b : BOARDS) {
            List<Row> rows = load(c, week, b.id(), 3);
            if (rows.isEmpty()) {
                continue;
            }
            StringBuilder line = new StringBuilder(b.name() + "&7: ");
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO gens_weekly_prizes (uuid, week, board, place) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (uuid, week, board) DO NOTHING")) {
                for (int i = 0; i < rows.size(); i++) {
                    Row r = rows.get(i);
                    ps.setString(1, r.uuid().toString());
                    ps.setString(2, week);
                    ps.setString(3, b.id());
                    ps.setInt(4, i + 1);
                    ps.addBatch();
                    line.append(i > 0 ? "&7, " : "").append(PLACE_COLOR[i]).append("#").append(i + 1).append(" ").append(r.name());
                }
                ps.executeBatch();
            }
            lines.add(line.toString());
        }
        plugin.getLogger().info("[weekly] awarded week " + week + ": " + lines.size() + " boards.");
        if (lines.isEmpty()) {
            return;
        }
        Tasks.global.run(() -> {
            Bukkit.broadcast(Msg.parse(Msg.headerText("Weekly Results") + "<gray>The week is over! Winners get their prizes when they're online.</gray>"));
            for (String l : lines) {
                Bukkit.broadcast(Msg.parse(me.Evan52098.genscore.rewardbox.RewardBox.legacyToMini(l)));
            }
        });
    }

    private void payout(UUID uuid) {
        List<String[]> won = new ArrayList<>();
        try {
            plugin.database().sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT week, board, place FROM gens_weekly_prizes WHERE uuid = ? AND paid = 0")) {
                    ps.setString(1, uuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            won.add(new String[]{rs.getString(1), rs.getString(2), String.valueOf(rs.getInt(3))});
                        }
                    }
                }
                List<String[]> claimed = new ArrayList<>();
                for (String[] w : won) {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE gens_weekly_prizes SET paid = 1 WHERE uuid = ? AND week = ? AND board = ? AND paid = 0")) {
                        ps.setString(1, uuid.toString());
                        ps.setString(2, w[0]);
                        ps.setString(3, w[1]);
                        if (ps.executeUpdate() == 1) {
                            claimed.add(w);
                        }
                    }
                }
                won.retainAll(claimed);
                return null;
            });
        } catch (SQLException e) {
            plugin.getLogger().log(Level.WARNING, "[weekly] payout failed", e);
            return;
        }
        if (won.isEmpty()) {
            return;
        }
        Tasks.global.run(() -> {
            Player p = Bukkit.getPlayer(uuid);
            if (p == null) {
                plugin.database().asyncRun(c -> {
                    try (PreparedStatement ps = c.prepareStatement("UPDATE gens_weekly_prizes SET paid = 0 WHERE uuid = ? AND week = ? AND board = ?")) {
                        for (String[] w : won) {
                            ps.setString(1, uuid.toString());
                            ps.setString(2, w[0]);
                            ps.setString(3, w[1]);
                            ps.addBatch();
                        }
                        ps.executeBatch();
                    }
                });
                return;
            }
            for (String[] w : won) {
                int place = Integer.parseInt(w[2]);
                for (String cmd : prizes.get(Math.min(2, place - 1))) {
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd.replace("{player}", p.getName()));
                }
                String board = BOARDS.stream().filter(b -> b.id().equals(w[1])).map(Board::name).findFirst().orElse(w[1]);
                Tasks.entity.run(p, () -> {
                    p.sendMessage(Msg.parse(me.Evan52098.genscore.rewardbox.RewardBox.legacyToMini(
                            "&6&lWEEKLY PRIZE &8» &7You placed " + PLACE_COLOR[Math.min(2, place - 1)] + "#" + place + " &7on " + board
                                    + " &7(" + w[0] + "): &f" + PRIZE_TEXT.get(Math.min(2, place - 1)))));
                    p.playSound(p.getLocation(), org.bukkit.Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
                });
            }
        });
    }

    private static List<Row> load(Connection c, String week, String board, int limit) throws SQLException {
        List<Row> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, name, value FROM gens_weekly WHERE week = ? AND board = ? AND value > 0 "
                + "ORDER BY value DESC LIMIT " + limit)) {
            ps.setString(1, week);
            ps.setString(2, board);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                }
            }
        }
        return out;
    }

    private static @Nullable String meta(Connection c, String key) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT v FROM gens_weekly_meta WHERE k = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private static void setMeta(Connection c, String key, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("INSERT INTO gens_weekly_meta (k, v) VALUES (?, ?) ON CONFLICT (k) DO UPDATE SET v = excluded.v")) {
            ps.setString(1, key);
            ps.setString(2, value);
            ps.executeUpdate();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        Tasks.async.later(() -> payout(uuid), 5, TimeUnit.SECONDS);
    }

    @Command({"weekly", "weeklytop", "wtop"})
    @CommandPermission("genscore.weekly")
    @Description("This week's leaderboards and prizes")
    public void weeklyCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        String week = week(ZonedDateTime.now(ZONE));
        UUID me = p.getUniqueId();
        plugin.database().async(c -> {
            Map<String, long[]> mine = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT board, value, (SELECT COUNT(*) FROM gens_weekly o WHERE o.week = w.week "
                    + "AND o.board = w.board AND o.value > w.value) FROM gens_weekly w WHERE w.week = ? AND w.uuid = ?")) {
                ps.setString(1, week);
                ps.setString(2, me.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        mine.put(rs.getString(1), new long[]{rs.getLong(2), rs.getLong(3) + 1});
                    }
                }
            }
            return mine;
        }).thenAccept(mine -> Tasks.entity.run(p, () -> open(p, mine)));
    }

    private void open(Player p, Map<String, long[]> mine) {
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Weekly Top"));
        int[] slots = {10, 12, 14, 16};
        for (int i = 0; i < BOARDS.size(); i++) {
            Board b = BOARDS.get(i);
            Lore lore = Lore.builder(Lore.Accent.WHITE).subcategory("This week");
            List<Row> rows10 = top.getOrDefault(b.id(), List.of());
            if (rows10.isEmpty()) {
                lore.description("&7Nobody yet. Be the first!");
            }
            for (int r = 0; r < rows10.size(); r++) {
                Row row = rows10.get(r);
                String color = r < 3 ? PLACE_COLOR[r] : "&7";
                lore.description(color + "#" + (r + 1) + " &f" + row.name() + " &8- " + color + value(b, row.value()));
            }
            long[] me = mine.get(b.id());
            lore.divider().stat("You", me == null ? "not ranked" : "#" + me[1] + " (" + value(b, me[0]) + ")");
            menu.set(slots[i], Lore.item(b.icon(), b.name(), lore.build()), null);
        }
        Lore prizesLore = Lore.builder(Lore.Accent.WHITE).subcategory("Every board, every week")
                .description("&6#1 &f" + PRIZE_TEXT.get(0), "&f#2 &f" + PRIZE_TEXT.get(1), "&c#3 &f" + PRIZE_TEXT.get(2))
                .divider().stat("Resets in", resetText()).description("&7Mondays at midnight (New York).");
        menu.set(22, Lore.item(Material.CLOCK, "&e&lPrizes", prizesLore.build()), null);
        menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), e -> p.closeInventory());
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private static String value(Board b, long v) {
        return b.money() ? Money.compact(v) : v + b.unit();
    }
}
