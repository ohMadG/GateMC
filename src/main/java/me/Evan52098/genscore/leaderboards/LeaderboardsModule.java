package me.Evan52098.genscore.leaderboards;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.economy.EconomyService;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.ToLongFunction;
import java.util.logging.Level;

/**
 * Leaderboards over GensCore's own data: richest players (pvp_balances), most playtime (pvp_players).
 * BlockvilleCore built these from its balance top,
 * PlaytimeService's playtime top and the {@code bvcore_leaderboard_stats} store (rank placeholders, the balance top
 * menu, and floating TextDisplay boards placed with a command); this module ports those:
 * <ul>
 *   <li>{@code /leaderboard [board]} (aliases /lb, /leaderboards): a menu of boards, and each board in the
 *       balance-top menu layout (heads on rows 1 and 3).</li>
 *   <li>{@code /lbholo place <board>}, {@code /lbholo clear}: floating boards (TextDisplay) in front of you, saved in
 *       modules/leaderboards/holograms.yml and refreshed every minute, like BlockvilleCore's arena boards.</li>
 *   <li>{@link #rank} for other modules (BlockvilleCore exposed {@code rank_balance} and {@code rank_playtime} as
 *       PlaceholderAPI placeholders; PlaceholderAPI is not a GensCore dependency, so the lookup is a method instead).</li>
 * </ul>
 * Every query runs on a database thread and is cached ({@code leaderboards.cache-seconds}); online players' values,
 * which are newer than the last save, are laid over the stored ones. Other modules (combat kills, factions) can add a
 * board with {@link #register}.
 */
public final class LeaderboardsModule implements GensModule {

    /** One row of a board. */
    public record Entry(UUID uuid, String name, long value) {
    }

    /** Loads up to {@code limit} rows, highest first. Runs on a database thread. */
    @FunctionalInterface
    public interface Source {
        List<Entry> load(Database db, int limit) throws Exception;
    }

    /** A board: how to load it, how to show a value, and an optional fresher value for online players. */
    public record Board(String id, String title, String valueLabel, Material icon, Source source, LongFunction<String> format,
                        @Nullable ToLongFunction<UUID> online) {
    }

    private static final class Cache {
        volatile List<Entry> entries = List.of();
        volatile long at;
        final AtomicBoolean refreshing = new AtomicBoolean();
    }

    private static final int[] SLOTS = {0, 2, 4, 6, 8, 18, 20, 22, 24, 26};

    private final Map<String, Board> boards = new LinkedHashMap<>();
    private final Map<String, Cache> caches = new ConcurrentHashMap<>();
    private GensCore plugin;
    private Database db;
    private PlayerDataService players;
    private long cacheMillis;
    private int size;
    private Holograms holograms;

    @Override
    public String id() {
        return "leaderboards";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.players = plugin.playerData();
        Migrations.apply(db, id(), plugin.getLogger(),
                // The playtime board sorts every player by playtime; an index keeps that cheap.
                "CREATE INDEX IF NOT EXISTS pvp_players_playtime ON pvp_players (playtime_ms)");
        loadConfig();
        registerBuiltIns();
        holograms = new Holograms(this, new File(plugin.getDataFolder(), "modules/leaderboards/holograms.yml"), plugin.getLogger());
        holograms.start(plugin);
        plugin.commands().register(this);
        Tasks.async.timer(t -> {
            for (Board b : boardsSnapshot()) {
                refresh(b);
            }
        }, 5, Math.max(30, cacheMillis / 1000), TimeUnit.SECONDS);
    }

    private void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("leaderboards");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        cacheMillis = TimeUnit.SECONDS.toMillis(Math.max(10, c.getLong("cache-seconds", 60)));
        size = Math.max(1, Math.min(10, c.getInt("size", 10)));
    }

    @Override
    public void reload() {
        loadConfig();
    }

    @Override
    public void disable() {
        if (holograms != null) {
            holograms.stop();
        }
    }

    // ------------------------------------------------------------------ boards

    private void registerBuiltIns() {
        if (plugin.modules().get(EconomyModule.class) != null) {
            register(new Board("balance", "Top Balances", "Balance", Material.GOLD_INGOT, (database, limit) -> {
                EconomyModule eco = plugin.modules().get(EconomyModule.class);
                if (eco == null) {
                    return List.of();
                }
                List<Entry> out = new ArrayList<>();
                for (EconomyService.TopEntry e : eco.service().topAsync().get(30, TimeUnit.SECONDS)) {
                    out.add(new Entry(e.uuid(), e.name(), e.cents()));
                    if (out.size() >= limit) {
                        break;
                    }
                }
                return out;
            }, Money::format, null)); // the economy's own top list already uses online wallets
        }
        register(new Board("playtime", "Top Playtime", "Playtime", Material.CLOCK, (database, limit) -> database.sync(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT uuid, name, playtime_ms FROM pvp_players ORDER BY playtime_ms DESC LIMIT ?")) {
                ps.setInt(1, limit + 20); // a little extra so online players' newer totals can reorder the top
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        }), LeaderboardsModule::formatDuration, uuid -> {
            PlayerData d = players.get(uuid);
            return d == null ? -1 : d.playtimeMs();
        }));
        register(new Board("rebirths", "Top Rebirths", "Rebirths", Material.NETHER_STAR, (database, limit) -> database.sync(c -> {
            List<Entry> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT g.uuid, p.name, g.rebirths FROM gens_players g LEFT JOIN pvp_players p ON p.uuid = g.uuid "
                            + "WHERE g.rebirths > 0 ORDER BY g.rebirths DESC LIMIT ?")) {
                ps.setInt(1, limit + 20);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(new Entry(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3)));
                    }
                }
            }
            return out;
        }), n -> n + (n == 1 ? " rebirth" : " rebirths"), uuid -> {
            PlotsModule plots = plugin.modules().get(PlotsModule.class);
            GensPlayer g = plots == null ? null : plots.gensPlayer(uuid);
            return g == null ? -1 : g.rebirths();
        }));
    }

    /** Adds a board (for other modules, while enabling). Replaces one with the same id. */
    public synchronized void register(Board board) {
        boards.put(board.id().toLowerCase(Locale.ROOT), board);
        caches.putIfAbsent(board.id().toLowerCase(Locale.ROOT), new Cache());
    }

    synchronized List<Board> boardsSnapshot() {
        return List.copyOf(boards.values());
    }

    synchronized @Nullable Board board(String id) {
        return id == null ? null : boards.get(id.toLowerCase(Locale.ROOT));
    }

    /** The cached rows of a board (refreshed in the background when stale). Safe from any thread. */
    public List<Entry> top(String boardId) {
        Board board = board(boardId);
        if (board == null) {
            return List.of();
        }
        Cache cache = caches.get(board.id().toLowerCase(Locale.ROOT));
        if (System.currentTimeMillis() - cache.at > cacheMillis) {
            refresh(board);
        }
        return cache.entries;
    }

    /** 1-based rank of a player on a board's cached top list, or 0 if not on it. */
    public int rank(String boardId, UUID player) {
        List<Entry> list = top(boardId);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).uuid().equals(player)) {
                return i + 1;
            }
        }
        return 0;
    }

    /** Fresh rows when the cache is stale, otherwise the cache. Completes on a database or async thread. */
    CompletableFuture<List<Entry>> topAsync(Board board) {
        Cache cache = caches.get(board.id().toLowerCase(Locale.ROOT));
        if (cache.at > 0 && System.currentTimeMillis() - cache.at <= cacheMillis) {
            return CompletableFuture.completedFuture(cache.entries);
        }
        return refresh(board);
    }

    private CompletableFuture<List<Entry>> refresh(Board board) {
        Cache cache = caches.get(board.id().toLowerCase(Locale.ROOT));
        if (!cache.refreshing.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(cache.entries);
        }
        CompletableFuture<List<Entry>> done = new CompletableFuture<>();
        Tasks.async.run(() -> {
            try {
                List<Entry> rows = board.source().load(db, size);
                cache.entries = overlay(board, rows);
                cache.at = System.currentTimeMillis();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[leaderboards] Could not load the " + board.id() + " board", e);
            } finally {
                cache.refreshing.set(false);
                done.complete(cache.entries);
            }
        });
        return done;
    }

    /** Online players' values are newer than the database; use them and re-sort. */
    private List<Entry> overlay(Board board, List<Entry> rows) {
        Map<UUID, Entry> byId = new HashMap<>();
        for (Entry e : rows) {
            byId.put(e.uuid(), e);
        }
        if (board.online() != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                long v = board.online().applyAsLong(p.getUniqueId());
                if (v > 0) {
                    byId.put(p.getUniqueId(), new Entry(p.getUniqueId(), p.getName(), v));
                }
            }
        }
        List<Entry> list = new ArrayList<>(byId.values());
        list.removeIf(e -> e.value() <= 0);
        list.sort(Comparator.comparingLong(Entry::value).reversed());
        return List.copyOf(list.subList(0, Math.min(size, list.size())));
    }

    static String formatDuration(long millis) {
        long minutes = millis / 60_000L;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }

    // ------------------------------------------------------------------ commands and menus

    @Command({"leaderboard", "leaderboards", "lb"})
    @CommandPermission("genscore.leaderboard")
    @Description("Browse the server leaderboards")
    public void leaderboard(BukkitCommandActor actor, @Named("board") @Optional @Nullable String boardId) {
        Player player = actor.requirePlayer();
        if (boardId == null || boardId.isBlank()) {
            openList(player);
            return;
        }
        Board board = board(boardId);
        if (board == null) {
            player.sendMessage(Msg.bad("Unknown leaderboard. Boards: " + Msg.white(String.join(", ", boardIds())) + "."));
            return;
        }
        openBoard(player, board);
    }

    public List<String> boardIds() {
        List<String> ids = new ArrayList<>();
        for (Board b : boardsSnapshot()) {
            ids.add(b.id());
        }
        return ids;
    }

    private void openList(Player player) {
        List<Board> list = boardsSnapshot();
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Leaderboards"));
        int start = 13 - (list.size() - 1);
        for (int i = 0; i < list.size() && i < 9; i++) {
            Board b = list.get(i);
            menu.set(Math.max(9, Math.min(17, start + i * 2)), Lore.item(b.icon(), "&6&l" + b.title(),
                    Lore.builder(Lore.Accent.WHITE).subcategory("Leaderboard").divider().description("The top " + size + " players", "by " + b.valueLabel().toLowerCase(Locale.ROOT) + ".")
                            .clickPrompt("Click to View").build()), click -> openBoard(player, b));
        }
        menu.set(22, Lore.item(Material.BARRIER, "&c&lCLOSE", List.of()), click -> player.closeInventory());
        menu.open(player);
    }

    private void openBoard(Player player, Board board) {
        topAsync(board).whenComplete((entries, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                player.sendMessage(Msg.bad("The leaderboard could not be loaded right now."));
                return;
            }
            Menu menu = new Menu(5, Msg.parse("<dark_gray>" + Msg.escape(board.title())));
            for (int i = 0; i < SLOTS.length; i++) {
                if (i < entries.size()) {
                    Entry e = entries.get(i);
                    menu.set(SLOTS[i], head(e, i + 1, board), null);
                } else {
                    menu.set(SLOTS[i], Lore.item(Material.GRAY_STAINED_GLASS_PANE, "&7#" + (i + 1) + " &8- nobody yet", List.of()), null);
                }
            }
            menu.set(36, Lore.item(Material.ARROW, "&e&lBACK", List.of()), click -> openList(player));
            int mine = rank(board.id(), player.getUniqueId());
            menu.set(40, Lore.item(Material.NAME_TAG, "&f&lYour rank", List.of(mine > 0 ? "&7You are &6#" + mine + "&7 on this board." : "&7You are not in the top " + size + " yet.")), null);
            menu.set(44, Lore.item(Material.BARRIER, "&c&lCLOSE", List.of()), click -> player.closeInventory());
            menu.open(player);
        }));
    }

    private static ItemStack head(Entry e, int rank, Board board) {
        ItemStack item = Lore.item(Material.PLAYER_HEAD, "&6#" + rank + " &f" + e.name().replace('&', ' '),
                List.of("&7" + board.valueLabel() + ": &a" + board.format().apply(e.value())));
        if (item.getItemMeta() instanceof SkullMeta meta) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(e.uuid()); // by UUID: no lookup, no blocking
            meta.setOwningPlayer(owner);
            item.setItemMeta(meta);
        }
        return item;
    }

    @Command("lbholo place")
    @CommandPermission("genscore.admin.leaderboards")
    @Description("Put a floating leaderboard in front of you")
    public void place(BukkitCommandActor actor, @Named("board") String boardId) {
        Player player = actor.requirePlayer();
        Board board = board(boardId);
        if (board == null) {
            player.sendMessage(Msg.bad("Unknown board. Boards: " + Msg.white(String.join(", ", boardIds())) + "."));
            return;
        }
        holograms.place(board.id(), player.getLocation());
        player.sendMessage(Msg.good("Placed the <white>" + Msg.escape(board.id()) + "</white> leaderboard."));
    }

    @Command("lbholo clear")
    @CommandPermission("genscore.admin.leaderboards")
    @Description("Remove every floating leaderboard")
    public void clear(BukkitCommandActor actor) {
        actor.sender().sendMessage(Msg.good("Removed <white>" + holograms.clear() + "</white> leaderboard(s)."));
    }
}
