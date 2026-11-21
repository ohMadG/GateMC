package me.Evan52098.genscore.store;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.leaderboards.LeaderboardsModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import io.papermc.paper.connection.PlayerGameConnection;
import io.papermc.paper.event.player.PlayerCustomClickEvent;
import net.kyori.adventure.key.Key;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Subcommand;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * The Tebex store side of HxCore ({@code purchase-announce}, {@code store-popup}, the spenders leaderboard, and the
 * {@code SpendGoal}/{@code StoreParty}/{@code TopSpender}/{@code StorePayments} classes, designed fresh here):
 * <ul>
 *   <li>{@code /store} (aliases /buy, /tebex): the store link.</li>
 *   <li>{@code /storeadmin purchase <player> <item>}: what a Tebex package runs; broadcasts a clickable store line.</li>
 *   <li>Top spenders: completed Tebex payments, read in the background with the secret key from the Tebex plugin's
 *       own config, kept in {@code pvp_store_payments}, and shown as the {@code spenders} leaderboard.</li>
 *   <li>Community goal ({@code /goal}): this month's completed payments against a target, with a progress bar; when
 *       the target is reached, everyone online gets the configured reward, once per month.</li>
 *   <li>Store party ({@code /storeadmin party <player> [transaction]}): run by a Tebex package; after a short
 *       countdown everyone online (one account per IP) gets the party reward. With a transaction id it runs only
 *       once per transaction, even if Tebex sends the command twice.</li>
 *   <li>Store pop-up zones ({@link StorePopup}).</li>
 * </ul>
 */
public final class StoreModule implements GensModule {

    private static final Pattern PLAYER_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");
    private static final Pattern TRANSACTION = Pattern.compile("[A-Za-z0-9_\\-]{1,64}");

    private GensCore plugin;
    private volatile StoreSettings settings;
    private StorePayments payments;
    private TebexClient tebex;
    private StoreRewards rewards;
    private StorePopup popup;
    private volatile StoreCatalog catalog;
    private StoreMenu menu;
    private StoreArt art;
    private final Set<UUID> packLoaded = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean syncing = new AtomicBoolean();
    private final AtomicBoolean syncSoon = new AtomicBoolean();
    private final Cooldowns cooldowns = new Cooldowns();

    // Status, written by the sync thread and read by commands.
    private volatile long lastSyncAt;
    private volatile String lastSyncResult = "not run yet";
    private volatile long monthTotalCents;
    private volatile YearMonth monthShown = YearMonth.now();
    private volatile long lastCelebration;
    private volatile long keyAllTotalCents = -1;
    private final BossBar keyAllBar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10);

    @Override
    public String id() {
        return "store";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.settings = StoreSettings.load(plugin.getConfig().getConfigurationSection("store"), plugin.getLogger());
        this.payments = new StorePayments(plugin.database());
        payments.migrate(plugin.getLogger());
        this.tebex = new TebexClient(plugin.getDataFolder().getParentFile());
        this.rewards = new StoreRewards(plugin.modules(), plugin.getLogger());
        this.popup = new StorePopup(() -> settings, this::storeLine);
        Bukkit.getPluginManager().registerEvents(popup, plugin);
        this.catalog = loadCatalog();
        this.art = StoreArt.load(plugin.getResource("modules/store/art.txt"));
        this.menu = new StoreMenu(() -> catalog, () -> settings, () -> art, packLoaded, plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPack(PlayerResourcePackStatusEvent event) {
                switch (event.getStatus()) {
                    case SUCCESSFULLY_LOADED -> packLoaded.add(event.getPlayer().getUniqueId());
                    case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED ->
                            packLoaded.remove(event.getPlayer().getUniqueId());
                    default -> {
                    }
                }
            }

            @EventHandler
            public void onCustomClick(PlayerCustomClickEvent event) {
                Key id = event.getIdentifier();
                if (!id.namespace().equals(StoreDialog.NS) || !id.value().startsWith("store/")
                        || !(event.getCommonConnection() instanceof PlayerGameConnection connection)) {
                    return;
                }
                Player player = connection.getPlayer();
                String path = id.value().substring("store/".length());
                Tasks.onEntity(player, () -> {
                    if (player.isOnline() && cooldowns.tryStart(player.getUniqueId(), "store-click", 120)) {
                        menu.click(player, path);
                    }
                });
            }

            @EventHandler
            public void onJoin(PlayerJoinEvent event) {
                Player p = event.getPlayer();
                if (settings.keyAll().enabled() && keyAllTotalCents >= 0) {
                    p.showBossBar(keyAllBar);
                }
            }

            @EventHandler
            public void onQuit(PlayerQuitEvent event) {
                packLoaded.remove(event.getPlayer().getUniqueId());
                menu.forget(event.getPlayer().getUniqueId());
            }
        }, plugin);
        plugin.commands().register(this, new Admin());
        registerBoard();
        warnBadUrl();
        // First sync shortly after start, then every refresh-minutes. The period is read at enable; reload changes the
        // rest of the settings at once and the period after a restart.
        Tasks.async.timer(t -> sync(null), 20, (long) settings.refreshMinutes() * 60, TimeUnit.SECONDS);
    }

    @Override
    public void disable() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.hideBossBar(keyAllBar);
        }
    }

    @Override
    public void reload() {
        settings = StoreSettings.load(plugin.getConfig().getConfigurationSection("store"), plugin.getLogger());
        Tasks.async.run(() -> {
            try {
                refreshKeyAll();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[store] Could not update the key all", e);
            }
        });
        popup.forgetAll();
        catalog = loadCatalog();
        warnBadUrl();
    }

    private StoreCatalog loadCatalog() {
        File file = new File(plugin.getDataFolder(), "modules/store/store.yml");
        if (!file.isFile()) {
            plugin.saveResource("modules/store/store.yml", false);
        }
        return StoreCatalog.load(file, plugin.getLogger());
    }

    private void warnBadUrl() {
        if (!settings.url().isEmpty() && !Text.isWebLink(settings.url())) {
            plugin.getLogger().warning("[store] store.url is not an http(s) link; it will not be clickable.");
        }
    }

    private void registerBoard() {
        LeaderboardsModule boards = plugin.modules().get(LeaderboardsModule.class);
        if (boards == null || !settings.topEnabled()) {
            return;
        }
        boards.register(new LeaderboardsModule.Board("spenders", "Top Spenders", "Spent", Material.DIAMOND,
                (db, limit) -> db.sync(c -> payments.top(c, limit, settings.topExclude())),
                cents -> settings.formatMoney(cents), null));
    }

    // ------------------------------------------------------------------ links

    /** The clickable store link (HxCore's {store}). */
    Component storeLink() {
        StoreSettings s = settings;
        return Text.link(s.linkLabel(), s.url(), "&7Click to open the store");
    }

    /** The first store message line with {store} filled in (used by the pop-up). */
    Component storeLine() {
        StoreSettings s = settings;
        String first = s.storeMessage().isEmpty() ? "{store}" : s.storeMessage().get(0);
        return Text.replace(Text.legacy(first), Map.of("{store}", storeLink()));
    }

    @Command({"store", "buy", "tebex"})
    @CommandPermission("genscore.store")
    @Description("Link to the server store")
    public void store(BukkitCommandActor actor) {
        if (actor.sender() instanceof Player player && !catalog.categories().isEmpty()) {
            menu.open(player, 0, 0);
            return;
        }
        linkCommand(actor);
    }

    @Command({"storelink"})
    @CommandPermission("genscore.store")
    @Description("Link to the server store")
    public void linkCommand(BukkitCommandActor actor) {
        StoreSettings s = settings;
        if (s.url().isEmpty()) {
            actor.sender().sendMessage(Msg.bad("The store link isn't set up yet."));
            return;
        }
        Component link = storeLink();
        for (String line : s.storeMessage()) {
            actor.sender().sendMessage(Text.replace(Text.legacy(line), Map.of("{store}", link)));
        }
    }

    @Command({"goal", "spendgoal", "storegoal"})
    @CommandPermission("genscore.store")
    @Description("This month's community store goal")
    public void goal(BukkitCommandActor actor) {
        StoreSettings.Goal g = settings.goal();
        if (!g.enabled()) {
            actor.sender().sendMessage(Msg.neutral("There is no community goal right now."));
            return;
        }
        // Cached numbers only: no database work, so no rate limit needed.
        actor.sender().sendMessage(goalLine());
        actor.sender().sendMessage(Text.replace(Text.legacy("&7Every store purchase counts: {store}"), Map.of("{store}", storeLink())));
    }

    Component goalLine() {
        StoreSettings s = settings;
        StoreSettings.Goal g = s.goal();
        long current = YearMonth.now(g.zone()).equals(monthShown) ? monthTotalCents : 0;
        int percent = (int) Math.min(100, g.targetCents() <= 0 ? 0 : current * 100 / g.targetCents());
        int filled = (int) Math.min(g.barLength(), g.targetCents() <= 0 ? 0 : current * g.barLength() / g.targetCents());
        String bar = "&a" + "|".repeat(filled) + "&7" + "|".repeat(g.barLength() - filled);
        return Text.legacy(g.line()
                .replace("{current}", s.formatMoney(current))
                .replace("{target}", s.formatMoney(g.targetCents()))
                .replace("{percent}", String.valueOf(percent))
                .replace("{bar}", bar));
    }

    // ------------------------------------------------------------------ sync

    /**
     * Pulls new payments from Tebex into pvp_store_payments, then refreshes the month total and checks the goal.
     * Single-flight: a second call while one runs does nothing (checklist 3). Async thread only.
     */
    private void sync(@Nullable CommandSender reportTo) {
        if (!syncing.compareAndSet(false, true)) {
            if (reportTo != null) {
                Tasks.forSender(reportTo, () -> reportTo.sendMessage(Msg.neutral("A store sync is already running.")));
            }
            return;
        }
        String result;
        try {
            result = pull();
        } catch (Exception e) {
            result = "failed: " + e.getClass().getSimpleName();
            plugin.getLogger().log(Level.WARNING, "[store] Store sync failed", e);
        } finally {
            syncing.set(false);
        }
        lastSyncAt = System.currentTimeMillis();
        lastSyncResult = result;
        try {
            refreshGoal();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[store] Could not update the community goal", e);
        }
        try {
            refreshKeyAll();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "[store] Could not update the key all", e);
        }
        if (reportTo != null) {
            String r = result;
            Tasks.forSender(reportTo, () -> reportTo.sendMessage(Msg.neutral("Store sync: " + Msg.white(r) + ".")));
        }
    }

    private String pull() throws Exception {
        StoreSettings s = settings;
        if (!s.tebexEnabled()) {
            return "Tebex reading is off";
        }
        String key;
        try {
            key = tebex.readKey(s.tebexConfigFile(), s.tebexKeyPaths());
        } catch (TebexClient.TebexException e) {
            plugin.getLogger().warning("[store] " + e.getMessage());
            return e.getMessage();
        }
        if (key == null) {
            return "no Tebex secret key found in plugins/" + s.tebexConfigFile();
        }
        // A full walk until one has reached the oldest page; after that only new pages plus a few to catch refunds.
        boolean complete = payments.has("sync", "complete");
        int added = 0;
        int pages = 0;
        try {
            for (int page = 1; page <= s.maxPages(); page++) {
                TebexClient.Page p = tebex.fetch(key, page);
                pages++;
                if (p.payments().isEmpty()) {
                    payments.claim("sync", "complete");
                    break;
                }
                int fresh = payments.upsert(p.payments());
                added += fresh;
                if (page >= p.lastPage()) {
                    payments.claim("sync", "complete");
                    break;
                }
                if (complete && fresh == 0 && page >= s.recheckPages()) {
                    break;
                }
                Thread.sleep(300); // stay well under the Tebex rate limit
            }
        } catch (TebexClient.TebexException e) {
            plugin.getLogger().warning("[store] " + e.getMessage());
            return e.getMessage() + " (after " + pages + " page(s), " + added + " new)";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } finally {
            key = null; // drop the only reference as soon as we're done
        }
        if (added > 0) {
            plugin.getLogger().info("[store] Read " + added + " new Tebex payment(s).");
        }
        return "ok, " + pages + " page(s), " + added + " new, " + payments.count() + " stored";
    }

    /** Recomputes this month's total and pays the goal reward once if it was just reached. Database thread. */
    private void refreshGoal() throws Exception {
        StoreSettings s = settings;
        StoreSettings.Goal g = s.goal();
        YearMonth month = YearMonth.now(g.zone());
        long total = payments.monthTotal(month, g.zone());
        monthShown = month;
        monthTotalCents = total;
        if (!g.enabled() || total < g.targetCents()) {
            return;
        }
        // Single winner per month (checklist 2); the marker is written before the reward is given, so a crash can lose
        // the reward but never pay it twice (checklist 1, 7).
        if (!payments.claim("goal", month.toString())) {
            return;
        }
        plugin.getLogger().info("[store] Community goal for " + month + " reached (" + s.formatMoney(total) + "); giving rewards.");
        broadcast(Text.legacy(g.reachedBroadcast().replace("{target}", s.formatMoney(g.targetCents()))
                .replace("{current}", s.formatMoney(total))));
        if (!g.reward().isEmpty()) {
            rewards.give(g.reward(), "store goal " + month, false, null);
        }
    }

    private void refreshKeyAll() throws Exception {
        StoreSettings s = settings;
        StoreSettings.KeyAll k = s.keyAll();
        if (!k.enabled()) {
            keyAllTotalCents = -1;
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(p, () -> p.hideBossBar(keyAllBar));
            }
            return;
        }
        payments.claim("key-all", "start");
        long start = payments.markerTime("key-all", "start");
        long total = start < 0 ? 0 : payments.totalSince(start);
        long due = total / k.stepCents();
        for (long n = 1; n <= due; n++) {
            if (!payments.claim("key-all", Long.toString(n))) {
                continue;
            }
            plugin.getLogger().info("[store] Key all #" + n + " reached (" + s.formatMoney(total) + " since the start); giving rewards.");
            broadcast(Text.legacy(k.reachedBroadcast().replace("{step}", s.formatMoney(k.stepCents()))
                    .replace("{current}", s.formatMoney(total))));
            if (!k.reward().isEmpty()) {
                rewards.give(k.reward(), "store key all " + n, false, null);
            }
        }
        long into = total % k.stepCents();
        keyAllBar.name(Text.legacy(k.bossbar().replace("{current}", s.formatMoney(into))
                .replace("{step}", s.formatMoney(k.stepCents()))
                .replace("{left}", s.formatMoney(k.stepCents() - into))));
        keyAllBar.progress(Math.max(0f, Math.min(1f, into / (float) k.stepCents())));
        boolean first = keyAllTotalCents < 0;
        keyAllTotalCents = total;
        if (first) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(p, () -> p.showBossBar(keyAllBar));
            }
        }
    }

    private void celebrate(String buyer) {
        StoreSettings.Celebration c = settings.celebration();
        if (!c.enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastCelebration < c.repeatGapSeconds() * 1000L) {
            return;
        }
        lastCelebration = now;
        Sound sound = null;
        if (!c.sound().isBlank()) {
            try {
                sound = Sound.sound(Key.key(c.sound().trim()), Sound.Source.MASTER, c.volume(), 1f);
            } catch (Exception e) {
                plugin.getLogger().warning("[store] store.celebration.sound is not a valid sound key.");
            }
        }
        Title title = Title.title(Text.legacy(c.title().replace("{player}", buyer)), Text.legacy(c.subtitle().replace("{player}", buyer)),
                Title.Times.times(Duration.ofMillis(500), Duration.ofSeconds(3), Duration.ofSeconds(1)));
        int seconds = c.ggWaveSeconds();
        BossBar bar = seconds <= 0 ? null : BossBar.bossBar(Text.legacy(c.bossbar().replace("{player}", buyer)), 1f,
                BossBar.Color.PURPLE, BossBar.Overlay.PROGRESS);
        Sound s = sound;
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> {
                if (s != null) {
                    p.playSound(s, Sound.Emitter.self());
                }
                p.showTitle(title);
                if (bar != null) {
                    p.showBossBar(bar);
                }
            });
        }
        if (bar == null) {
            return;
        }
        me.Evan52098.genscore.chat.GgWave.start(seconds * 1000L);
        if (!c.ggWaveBroadcast().isBlank()) {
            broadcast(Text.legacy(c.ggWaveBroadcast().replace("{player}", buyer).replace("{seconds}", String.valueOf(seconds))));
        }
        long total = seconds * 20L;
        long[] left = {total};
        Tasks.global.timer(task -> {
            left[0] -= 10;
            if (left[0] <= 0) {
                task.cancel();
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Tasks.onEntity(p, () -> p.hideBossBar(bar));
                }
                return;
            }
            bar.progress(Math.max(0f, left[0] / (float) total));
        }, 10, 10);
    }

    /** A sync about a minute from now, so a purchase shows up in the goal and top list quickly. Debounced. */
    private void syncSoon() {
        if (settings.tebexEnabled() && syncSoon.compareAndSet(false, true)) {
            Tasks.async.later(() -> {
                syncSoon.set(false);
                sync(null);
            }, 60, TimeUnit.SECONDS);
        }
    }

    private static void broadcast(Component line) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    // ------------------------------------------------------------------ admin

    /** /storeadmin: what Tebex packages run (as console), plus status and goal adjustments for staff. */
    @Command("storeadmin")
    @CommandPermission("genscore.store.admin")
    public final class Admin {

        @Subcommand("purchase")
        @Description("Announce a store purchase (run by Tebex)")
        public void purchase(BukkitCommandActor actor, @Named("player") String player, @Named("item") String item) {
            String name = Text.stripCodes(player);
            if (!PLAYER_NAME.matcher(name).matches()) {
                actor.sender().sendMessage(Msg.bad("Invalid player name."));
                return;
            }
            String what = item.length() > 100 ? item.substring(0, 100) : item;
            StoreSettings s = settings;
            Component line = Text.legacy(s.purchaseFormat().replace("{player}", name).replace("{item}", what));
            if (Text.isWebLink(s.url())) {
                line = line.clickEvent(ClickEvent.openUrl(s.url().trim())).hoverEvent(HoverEvent.showText(Text.legacy(s.purchaseHover())));
            }
            broadcast(line);
            celebrate(name);
            plugin.getLogger().info("[store] Purchase announced: " + name + " bought " + what);
            syncSoon();
        }

        @Subcommand("celebrate")
        @Description("Play the purchase sound and start a GG wave")
        public void celebrateNow(BukkitCommandActor actor, @Named("player") String player) {
            String name = Text.stripCodes(player);
            if (!PLAYER_NAME.matcher(name).matches()) {
                actor.sender().sendMessage(Msg.bad("Invalid player name."));
                return;
            }
            lastCelebration = 0;
            celebrate(name);
        }

        @Subcommand("party")
        @Description("Start a store party (run by Tebex)")
        public void party(BukkitCommandActor actor, @Named("player") String player, @Named("transaction") @Optional @Nullable String transaction) {
            StoreSettings.Party p = settings.party();
            CommandSender sender = actor.sender();
            if (!p.enabled()) {
                sender.sendMessage(Msg.bad("Store parties are off (store.party.enabled)."));
                return;
            }
            String name = Text.stripCodes(player);
            if (!PLAYER_NAME.matcher(name).matches()) {
                sender.sendMessage(Msg.bad("Invalid player name."));
                return;
            }
            if (transaction != null && !TRANSACTION.matcher(transaction).matches()) {
                sender.sendMessage(Msg.bad("Invalid transaction id."));
                return;
            }
            Tasks.async.run(() -> {
                try {
                    // One party per transaction, however often Tebex runs the command (checklist 2).
                    if (transaction != null && !payments.claim("party", transaction)) {
                        Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("That transaction already had its party.")));
                        return;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "[store] Could not record the party", e);
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("Could not start the party (database error).")));
                    return;
                }
                startParty(name, p);
            });
        }

        @Subcommand("goal add")
        @Description("Add money to this month's community goal (e.g. a payment made outside Tebex)")
        public void goalAdd(BukkitCommandActor actor, @Named("amount") String amount) {
            adjustGoal(actor, amount, 1);
        }

        @Subcommand("goal remove")
        @Description("Take money off this month's community goal")
        public void goalRemove(BukkitCommandActor actor, @Named("amount") String amount) {
            adjustGoal(actor, amount, -1);
        }

        @Subcommand("sync")
        @Description("Read new payments from Tebex now")
        public void syncNow(BukkitCommandActor actor) {
            CommandSender sender = actor.sender();
            // Each sync calls the Tebex API, so it is rate limited (checklist 10).
            if (sender instanceof Player pl && !cooldowns.tryStart(pl.getUniqueId(), "sync", 30_000)) {
                sender.sendMessage(Msg.bad("Please wait before syncing again."));
                return;
            }
            sender.sendMessage(Msg.neutral("Reading payments from Tebex..."));
            Tasks.async.run(() -> sync(sender));
        }

        @Subcommand("status")
        @Description("Store sync status")
        public void status(BukkitCommandActor actor) {
            CommandSender sender = actor.sender();
            sender.sendMessage(Msg.neutral("Last sync: " + Msg.white(lastSyncAt == 0 ? "never" : Instant.ofEpochMilli(lastSyncAt).toString())
                    + ", result: " + Msg.white(lastSyncResult) + "."));
            sender.sendMessage(goalLine());
        }
    }

    private void adjustGoal(BukkitCommandActor actor, String amount, int sign) {
        CommandSender sender = actor.sender();
        OptionalLong cents = Money.parse(amount); // refuses NaN, zero, negatives, overflow (checklist 8)
        if (cents.isEmpty() || cents.getAsLong() > 100_000_000L) {
            sender.sendMessage(Msg.bad("Invalid amount."));
            return;
        }
        long delta = sign * cents.getAsLong();
        YearMonth month = YearMonth.now(settings.goal().zone());
        String by = sender.getName();
        Tasks.async.run(() -> {
            try {
                payments.adjust(month, delta, by);
                plugin.getLogger().info("[store] " + by + " adjusted the " + month + " goal by " + delta + " cents.");
                refreshGoal();
                Tasks.forSender(sender, () -> {
                    sender.sendMessage(Msg.good("Goal adjusted."));
                    sender.sendMessage(goalLine());
                });
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[store] Could not adjust the goal", e);
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("Could not adjust the goal (database error).")));
            }
        });
    }

    private void startParty(String buyer, StoreSettings.Party p) {
        plugin.getLogger().info("[store] Store party started by " + buyer + ".");
        broadcast(Text.legacy(p.startBroadcast().replace("{player}", buyer).replace("{seconds}", String.valueOf(p.countdownSeconds()))));
        Runnable go = () -> {
            rewards.give(p.reward(), "store party (" + buyer + ")", p.onePerIp(), null);
            broadcast(Text.legacy(p.doneBroadcast().replace("{player}", buyer)));
        };
        if (p.countdownSeconds() <= 0) {
            Tasks.global.run(go);
        } else {
            Tasks.global.later(go, p.countdownSeconds() * 20L);
        }
    }
}
