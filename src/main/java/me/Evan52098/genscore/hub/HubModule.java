package me.Evan52098.genscore.hub;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.economy.EconomyService;
import me.Evan52098.genscore.factory.FactoryModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPotionEffectEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class HubModule implements GensModule, Listener {

    public static final String USE = "genscore.hub";

    private static volatile @Nullable HubModule instance;
    private static final ZoneId ZONE = ZoneId.of("America/New_York");
    private static final String[] DISCS = {"13", "cat", "blocks", "chirp", "far", "mall", "mellohi", "stal", "strad", "ward",
            "11", "wait", "otherside", "5", "pigstep", "relic", "creator", "creator_music_box", "precipice", "tears", "lava_chicken"};
    private static final Material[] DISC_ICONS = {Material.MUSIC_DISC_13, Material.MUSIC_DISC_CAT, Material.MUSIC_DISC_BLOCKS,
            Material.MUSIC_DISC_CHIRP, Material.MUSIC_DISC_FAR, Material.MUSIC_DISC_MALL, Material.MUSIC_DISC_MELLOHI,
            Material.MUSIC_DISC_STAL, Material.MUSIC_DISC_STRAD, Material.MUSIC_DISC_WARD, Material.MUSIC_DISC_11,
            Material.MUSIC_DISC_WAIT, Material.MUSIC_DISC_OTHERSIDE, Material.MUSIC_DISC_5, Material.MUSIC_DISC_PIGSTEP,
            Material.MUSIC_DISC_RELIC, Material.MUSIC_DISC_CREATOR, Material.MUSIC_DISC_CREATOR_MUSIC_BOX,
            Material.MUSIC_DISC_PRECIPICE, Material.MUSIC_DISC_TEARS, Material.MUSIC_DISC_LAVA_CHICKEN};
    private static final String[][] HORNS = {{"Ponder", "item.goat_horn.sound.0"}, {"Sing", "item.goat_horn.sound.1"},
            {"Seek", "item.goat_horn.sound.2"}, {"Feel", "item.goat_horn.sound.3"}, {"Admire", "item.goat_horn.sound.4"},
            {"Call", "item.goat_horn.sound.5"}, {"Yearn", "item.goat_horn.sound.6"}, {"Dream", "item.goat_horn.sound.7"}};

    private GensCore plugin;
    private HubTiles tiles;
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Long> cloakedUntil = new ConcurrentHashMap<>();
    private final Set<UUID> turboOff = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Integer> playMinutes = new ConcurrentHashMap<>();
    private final Map<UUID, Long> boostUntil = new ConcurrentHashMap<>();

    private record GameEvent(String id, String name, String color, String description, Map<String, Double> bonus) {
    }

    private static final List<GameEvent> EVENTS = List.of(
            new GameEvent("double_money", "2x MONEY", "<green>", "Everything off your belt sells for double.", Map.of("sale-value", 1.0)),
            new GameEvent("double_speed", "2x GEN SPEED", "<aqua>", "Every belt runs twice as fast.", Map.of("belt-speed", 1.0)),
            new GameEvent("token_rush", "TOKEN RUSH", "<yellow>", "Tokens and caches drop three times as often.", Map.of("luck", 2.0)),
            new GameEvent("raid_frenzy", "RAID FRENZY", "<red>", "Raids take double loot and hit harder.", Map.of("raid-loot", 1.0, "raid-damage", 1.0)));

    private volatile @Nullable GameEvent event;
    private volatile long eventEnds;
    private volatile long nextEventAt;
    private int eventMinutes;
    private int gapMin;
    private int gapMax;
    private final net.kyori.adventure.bossbar.BossBar eventBar = net.kyori.adventure.bossbar.BossBar.bossBar(Component.empty(), 1f,
            net.kyori.adventure.bossbar.BossBar.Color.YELLOW, net.kyori.adventure.bossbar.BossBar.Overlay.NOTCHED_10);

    private int cloakTokens;
    private int cloakSeconds;
    private int cloakCooldown;
    private int turboLevel;
    private double rewardMinutesScale;
    private long rewardFloorCents;

    @Override
    public String id() {
        return "hub";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.tiles = HubTiles.load(plugin.getResource("modules/hub/menu_title.json"), plugin.getLogger());
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_daily_tasks (uuid TEXT PRIMARY KEY, day TEXT NOT NULL, data TEXT NOT NULL)");
        plugin.playerData().register(DailyTasks.SECTION);
        reload();
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.timer(t -> minuteTick(), 60, 60, TimeUnit.SECONDS);
        nextEventAt = System.currentTimeMillis() + randomGap();
        Tasks.async.timer(t -> eventTick(), 1, 1, TimeUnit.SECONDS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> applyTurbo(p));
        }
        instance = this;
    }

    @Override
    public void disable() {
        instance = null;
    }

    @Override
    public void reload() {
        var c = plugin.getConfig();
        cloakTokens = Math.max(0, c.getInt("hub.cloak.tokens", 2));
        cloakSeconds = Math.max(3, c.getInt("hub.cloak.seconds", 20));
        cloakCooldown = Math.max(0, c.getInt("hub.cloak.cooldown-seconds", 240));
        turboLevel = Math.max(0, Math.min(4, c.getInt("hub.turbo.speed-level", 2)));
        rewardMinutesScale = Math.max(0, c.getDouble("hub.daily-tasks.reward-scale", 1.0));
        rewardFloorCents = Math.round(Math.max(0, c.getDouble("hub.daily-tasks.reward-floor", 150)) * 100);
        eventMinutes = Math.max(1, c.getInt("hub.events.minutes", 5));
        gapMin = Math.max(1, c.getInt("hub.events.gap-min-minutes", 20));
        gapMax = Math.max(gapMin, c.getInt("hub.events.gap-max-minutes", 40));
    }

    public static void open(Player p) {
        HubModule m = instance;
        Menu.clearReturn(p);
        if (m != null) {
            m.lastCategory.remove(p.getUniqueId());
            m.hubMenu(p);
        }
    }

    public static boolean openBaltop(Player p) {
        HubModule m = instance;
        if (m == null) {
            return false;
        }
        Tasks.onEntity(p, () -> m.baltopMenu(p));
        return true;
    }

    public static boolean cloaked(Player p) {
        HubModule m = instance;
        if (m == null) {
            return false;
        }
        Long until = m.cloakedUntil.get(p.getUniqueId());
        return until != null && until > System.currentTimeMillis();
    }

    public static double bonus(UUID player, String key) {
        HubModule m = instance;
        if (m == null) {
            return 0;
        }
        double total = 0;
        GameEvent e = m.event;
        if (e != null && m.eventEnds > System.currentTimeMillis()) {
            total += e.bonus().getOrDefault(key, 0.0);
        }
        if ("sale-value".equals(key)) {
            Long until = m.boostUntil.get(player);
            if (until != null && until > System.currentTimeMillis()) {
                total += 1.0;
            }
        }
        return total;
    }

    public static @Nullable String placeholder(UUID player, String key) {
        HubModule m = instance;
        if (m == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        GameEvent e = m.event;
        boolean live = e != null && m.eventEnds > now;
        return switch (key) {
            case "event" -> live ? "&e" + e.name() : "&7No event";
            case "event_time" -> live ? "&fends in &e" + clock(m.eventEnds - now) : "&fnext in &e" + clock(Math.max(0, m.nextEventAt - now));
            case "boost" -> {
                Long until = m.boostUntil.get(player);
                yield until != null && until > now ? "&a2x money &f" + clock(until - now) : "&7None";
            }
            default -> {
                String weekly = me.Evan52098.genscore.weekly.WeeklyModule.placeholder(player, key);
                yield weekly != null ? weekly : me.Evan52098.genscore.merchant.MerchantModule.placeholder(key);
            }
        };
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 60 ? (s / 60) + "m " + (s % 60) + "s" : s + "s";
    }

    private long randomGap() {
        return (gapMin + ThreadLocalRandom.current().nextInt(gapMax - gapMin + 1)) * 60_000L;
    }

    private void eventTick() {
        long now = System.currentTimeMillis();
        GameEvent e = event;
        if (e != null && eventEnds <= now) {
            event = null;
            nextEventAt = now + randomGap();
            broadcast(Msg.parse(Msg.headerText("Event Over") + "<gray>" + e.name() + " has ended. Next event in about</gray> <white>"
                    + clock(nextEventAt - now) + "</white>"), null, false);
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.entity.run(p, () -> p.hideBossBar(eventBar));
            }
            return;
        }
        if (e == null && now >= nextEventAt && !Bukkit.getOnlinePlayers().isEmpty()) {
            startEvent(EVENTS.get(ThreadLocalRandom.current().nextInt(EVENTS.size())));
            return;
        }
        if (e != null) {
            float left = (eventEnds - now) / (eventMinutes * 60_000f);
            eventBar.progress(Math.max(0f, Math.min(1f, left)));
            eventBar.name(Msg.parse(e.color() + "<bold>" + e.name() + "</bold></" + e.color().substring(1) + " <gray>-</gray> <white>" + clock(eventEnds - now) + "</white>"));
        }
    }

    private void startEvent(GameEvent e) {
        event = e;
        eventEnds = System.currentTimeMillis() + eventMinutes * 60_000L;
        Component title = Msg.parse(e.color() + "<bold>" + e.name());
        broadcast(Msg.parse(Msg.headerText("Event") + "" + e.color() + "<bold>" + e.name() + "</bold></" + e.color().substring(1)
                + " <gray>for " + eventMinutes + " minutes! " + e.description() + "</gray>"), title, true);
    }

    private void broadcast(Component chat, @Nullable Component title, boolean showBar) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(p, () -> {
                p.sendMessage(chat);
                if (title != null) {
                    p.showTitle(net.kyori.adventure.title.Title.title(title, Msg.parse("<gray>Event live!")));
                    p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
                }
                if (showBar) {
                    p.showBossBar(eventBar);
                }
            });
        }
    }

    public static void progress(UUID player, String type, long amount) {
        HubModule m = instance;
        if (m == null || amount <= 0) {
            return;
        }
        me.Evan52098.genscore.weekly.WeeklyModule.record(player, type, amount);
        DailyTasks.Type t;
        try {
            t = DailyTasks.Type.valueOf(type);
        } catch (IllegalArgumentException e) {
            return;
        }
        m.addProgress(player, t, amount);
    }

    // ------------------------------------------------------------------ commands

    @Command({"menu", "gatemc", "gm"})
    @CommandPermission(USE)
    @Description("Open the GateMC menu")
    public void menuCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Menu.clearReturn(p);
        lastCategory.remove(p.getUniqueId());
        Tasks.onEntity(p, () -> hubMenu(p));
    }

    @Command({"daily", "claim"})
    @CommandPermission(USE)
    @Description("Claim your daily cash or boost")
    public void dailyCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> dailyMenu(p));
    }

    @Command({"worlds", "platforms", "servers"})
    @CommandPermission(USE)
    @Description("Pick a platform to visit")
    public void worldsCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> worldsMenu(p));
    }

    @Command("gensreward")
    @CommandPermission("genscore.admin.reward")
    @Description("Give a gens-scaled reward (crates and the store run this)")
    public void gensReward(BukkitCommandActor actor, @revxrsal.commands.annotation.Named("player") String name,
                           @revxrsal.commands.annotation.Named("type") String type, @revxrsal.commands.annotation.Named("amount") String amountText,
                           @revxrsal.commands.annotation.Optional @revxrsal.commands.annotation.Named("count") @Nullable Integer count) {
        Player p = Bukkit.getPlayerExact(name);
        if (p == null) {
            actor.sender().sendMessage(Msg.bad(name + " is not online."));
            return;
        }
        UUID uuid = p.getUniqueId();
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        switch (type.toLowerCase(java.util.Locale.ROOT)) {
            case "income" -> {
                long minutes = Math.max(1, Long.parseLong(amountText));
                long perMinute = Math.max(20_00L, FactoryModule.incomePerMinute(uuid));
                long cents = perMinute * minutes;
                EconomyModule eco = plugin.modules().get(EconomyModule.class);
                if (eco != null && eco.service() != null) {
                    eco.service().deposit(uuid, cents, "reward income " + minutes + "m");
                }
                Tasks.entity.run(p, () -> p.sendMessage(Msg.good("+" + Msg.white(Money.compact(cents)) + " (" + minutes + " minutes of income)")));
            }
            case "tokens" -> {
                long n = Math.max(1, Long.parseLong(amountText));
                if (g != null) {
                    g.addTokens(n);
                    plots.saveSoon(uuid);
                }
                Tasks.entity.run(p, () -> p.sendMessage(Msg.good("+" + Msg.white(n + " tokens"))));
            }
            case "cache" -> {
                int rarity = switch (amountText.toLowerCase(java.util.Locale.ROOT)) {
                    case "rare" -> 1;
                    case "epic" -> 2;
                    case "legendary" -> 3;
                    default -> 0;
                };
                int n = Math.max(1, count == null ? 1 : count);
                if (g != null) {
                    g.addCache(rarity, n);
                    plots.saveSoon(uuid);
                }
                Tasks.entity.run(p, () -> p.sendMessage(Msg.good("+" + Msg.white(n + "x " + amountText + " cache") + ". Break it in " + Msg.white("/caches") + ".")));
            }
            case "boost" -> {
                long minutes = Math.max(1, Long.parseLong(amountText));
                long base = Math.max(System.currentTimeMillis(), boostUntil.getOrDefault(uuid, 0L));
                boostUntil.put(uuid, base + minutes * 60_000L);
                Tasks.entity.run(p, () -> p.sendMessage(Msg.good(Msg.white("2x money") + " for " + Msg.white(minutes + " minutes") + "!")));
            }
            default -> actor.sender().sendMessage(Msg.bad("Types: income <minutes>, tokens <n>, cache <rarity> [n], boost <minutes>"));
        }
    }

    @Command("event start")
    @CommandPermission("genscore.admin.events")
    @Description("Start a random event now")
    public void eventStart(BukkitCommandActor actor) {
        if (event != null) {
            actor.sender().sendMessage(Msg.bad("An event is already running."));
            return;
        }
        startEvent(EVENTS.get(ThreadLocalRandom.current().nextInt(EVENTS.size())));
    }

    @Command({"tasks", "dailytasks", "quests"})
    @CommandPermission(USE)
    @Description("Your daily tasks")
    public void tasksCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> tasksMenu(p));
    }

    @Command({"boombox", "music"})
    @CommandPermission(USE)
    @Description("Play music for the players around you")
    public void boomboxCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> boomboxMenu(p));
    }

    @Command("cloak")
    @CommandPermission(USE)
    @Description("Turn invisible to turrets and players for a raid")
    public void cloakCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> cloak(p));
    }

    @Command({"turbo", "sprint"})
    @CommandPermission(USE)
    @Description("Toggle your turbo speed")
    public void turboCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> toggleTurbo(p));
    }

    @Command({"topbal", "richest"})
    @CommandPermission(USE)
    @Description("The richest players")
    public void baltopCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> baltopMenu(p));
    }

    // ------------------------------------------------------------------ hub menu

    private record Entry(Material icon, String name, String line1, String line2, java.util.function.Consumer<Player> action) {
    }

    private void hubMenu(Player p) {
        if (tiles != null && tileMenu(p)) {
            return;
        }
        int rows = 5;
        Menu menu = new Menu(rows, MenuStyle.title("GateMC"));
        long tokens = tokens(p.getUniqueId());
        menu.set(4, icon(Material.NETHER_STAR, "&6&lGATEMC", "&7Tokens: &e" + String.format("%,d", tokens),
                "&7Pick a category below."));
        menu.set(19, icon(Material.HOPPER, "&e&lFactory", "Your factory, belt, gen,", "machines, gate and rebirth."), c -> category(p, "factory"));
        menu.set(20, icon(Material.IRON_PICKAXE, "&c&lRaiding", "Raid gear, the cloak", "and other platforms."), c -> category(p, "raiding"));
        menu.set(21, icon(Material.BLAZE_POWDER, "&b&lPowers", "Powers, super powers,", "token shop and caches."), c -> category(p, "powers"));
        menu.set(23, icon(Material.CHEST_MINECART, "&a&lRewards", "Daily reward, tasks,", "crates and events."), c -> category(p, "rewards"));
        menu.set(24, icon(Material.LEATHER_CHESTPLATE, "&d&lStyle", "Base styles, cosmetics,", "tags and the boombox."), c -> category(p, "style"));
        menu.set(25, icon(Material.GOLD_BLOCK, "&6&lServer", "Richest players, tutorial", "and the store."), c -> category(p, "server"));
        menu.set(22, icon(Material.ENDER_PEARL, "&a&lMy Factory", "Go to your factory.", "&e/plot"), c -> run(p, "plot"));
        boolean on = !turboOff.contains(p.getUniqueId());
        menu.set(30, icon(on ? Material.SUGAR : Material.GUNPOWDER, "&b&lTurbo", "Run faster around the map.",
                on ? "&aOn &7(click to turn off)" : "&cOff &7(click to turn on)"), c -> {
            toggleTurbo(p);
            hubMenu(p);
        });
        GameEvent e = event;
        menu.set(31, icon(Material.FIREWORK_ROCKET, "&6&lEvent", e != null ? "&aLive: &f" + e.name() : "&7No event right now.",
                e != null ? "&7" + e.description() : "&7Next in about &f" + clock(Math.max(0, nextEventAt - System.currentTimeMillis()))));
        menu.set(32, icon(Material.EMERALD, "&a&lStore", "Ranks, credits, keys and more.", "&e/store"), c -> run(p, "store"));
        menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), c -> p.closeInventory());
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private boolean tileMenu(Player p) {
        lastCategory.remove(p.getUniqueId());
        Menu.clearReturn(p);
        GameEvent e = event;
        String eventLine = e != null ? "&aLive: &f" + e.name() : "&7Next event in about &f" + clock(Math.max(0, nextEventAt - System.currentTimeMillis()));
        List<HubTiles.Tile> list = List.of(
                new HubTiles.Tile("factory", 0, 0, Material.HOPPER, "&a&lFactory",
                        tileLore("Your factory, belt, gen,", "machines, gate and rebirth."), x -> category(x, "factory")),
                new HubTiles.Tile("store", 1, 0, Material.EMERALD, "&d&lStore",
                        tileLore("Ranks, credits, keys", "and bundles."), x -> run(x, "store")),
                new HubTiles.Tile("quests", 2, 0, Material.WRITABLE_BOOK, "&e&lQuests",
                        tileLore("Three tasks a day for", "money and tokens."), this::tasksMenu),
                new HubTiles.Tile("powers", 0, 1, Material.BLAZE_POWDER, "&6&lPowers",
                        tileLore("Powers, super powers,", "token shop and caches."), x -> category(x, "powers")),
                new HubTiles.Tile("travel", 1, 1, Material.COMPASS, "&d&lTravel",
                        tileLore("See how busy each platform", "is and visit another."), this::worldsMenu),
                new HubTiles.Tile("rewards", 2, 1, Material.CHEST_MINECART, "&a&lRewards",
                        tileLore("Daily reward, tasks,", "crates and events."), x -> category(x, "rewards")),
                new HubTiles.Tile("raiding", 0, 2, Material.IRON_PICKAXE, "&b&lRaiding",
                        tileLore("Raid gear, the cloak", "and other platforms."), x -> category(x, "raiding")),
                new HubTiles.Tile("style", 1, 2, Material.ARMOR_STAND, "&3&lStyle",
                        tileLore("Base styles, cosmetics,", "tags and the boombox."), x -> category(x, "style")),
                new HubTiles.Tile("leaders", 2, 2, Material.GOLD_INGOT, "&6&lLeaders",
                        tileLore("The top players on", "the server."), x -> run(x, "leaderboard")));
        boolean on = !turboOff.contains(p.getUniqueId());
        List<HubTiles.Button> bar = List.of(
                new HubTiles.Button(0, icon(Material.ENDER_PEARL, "&a&lMy Factory", "Go to your factory.", "&e/plot"), x -> run(x, "plot")),
                new HubTiles.Button(1, icon(Material.CLOCK, "&6&lDaily Reward", "Cash or a 2x money boost,", "once a day."), this::dailyMenu),
                new HubTiles.Button(2, icon(Material.ENDER_CHEST, "&5&lCrates", "Your keys and the crates", "at spawn."), x -> run(x, "crates")),
                new HubTiles.Button(3, icon(on ? Material.SUGAR : Material.GUNPOWDER, "&b&lTurbo", "Run faster around the map.",
                        !p.hasPermission(TURBO) ? "&cLocked &7- a rank perk, see /store"
                                : on ? "&aOn &7(click to turn off)" : "&cOff &7(click to turn on)"), x -> {
                    toggleTurbo(x);
                    hubMenu(x);
                }),
                new HubTiles.Button(4, MenuStyle.close(), Player::closeInventory),
                new HubTiles.Button(5, icon(Material.FIREWORK_ROCKET, "&6&lEvent", eventLine), x -> {
                }),
                new HubTiles.Button(6, icon(Material.EGG, "&d&lPets", "Hatch and equip pets."), x -> run(x, "pets")),
                new HubTiles.Button(7, icon(Material.WRITTEN_BOOK, "&e&lTutorial", "How to play GateMC."), x -> run(x, "tutorial")),
                new HubTiles.Button(8, icon(Material.GOLD_BLOCK, "&6&lServer", "Richest players, tutorial", "and the store."), x -> category(x, "server")));
        return tiles.open(p, list, bar);
    }

    private static List<String> tileLore(String a, String b) {
        return List.of("&7" + a, "&7" + b, "", me.Evan52098.genscore.menu.Lore.prompt("open"));
    }

    private void category(Player p, String id) {
        Menu.clearReturn(p);
        lastCategory.put(p.getUniqueId(), id);
        long cd = cooldowns.remaining(p.getUniqueId(), "cloak");
        List<Entry> entries = switch (id) {
            case "factory" -> List.of(
                    new Entry(Material.ENDER_PEARL, "&a&lMy Factory", "Go to your factory.", "/plot", x -> run(x, "plot")),
                    new Entry(Material.HOPPER, "&e&lBelt & Gen", "Upgrade your gen, belt", "speed and length. /belt", x -> run(x, "belt")),
                    new Entry(Material.BLAST_FURNACE, "&6&lMachines", "Machines that multiply", "item value. /machines", x -> run(x, "machines")),
                    new Entry(Material.IRON_DOOR, "&7&lGate", "Close your gate at your", "factory. /gate", x -> run(x, "gate")),
                    new Entry(Material.SHIELD, "&b&lDefences", "Gate time, armour, turrets", "and traps. /defences", x -> run(x, "defences")),
                    new Entry(Material.END_CRYSTAL, "&d&lRebirth", "Reset for a permanent", "+50% sale value. /rebirth", x -> run(x, "rebirth")),
                    new Entry(Material.GOLDEN_HELMET, "&6&lManagers", "Hire staff that buy your", "upgrades for you. /managers", x -> run(x, "managers")));
            case "raiding" -> List.of(
                    new Entry(Material.IRON_PICKAXE, "&c&lRaid Gear", "Upgrade your Raid Pick.", "/gear", x -> run(x, "gear")),
                    new Entry(Material.PHANTOM_MEMBRANE, "&8&lRaid Cloak", "Invisible to turrets for " + cloakSeconds + "s.",
                            cd > 0 ? "&cRecharging: " + Cooldowns.format(cd) : "&aReady (" + cloakTokens + " tokens)", x -> {
                        x.closeInventory();
                        cloak(x);
                    }),
                    new Entry(Material.MAP, "&a&lPlatforms", "See how busy each platform", "is and visit another. /worlds", this::worldsMenu));
            case "powers" -> List.of(
                    new Entry(Material.BLAZE_POWDER, "&b&lPowers", "Bought with tokens.", "/powers", x -> run(x, "powers")),
                    new Entry(Material.NETHER_STAR, "&6&lSuper Powers", "Bought with credits.", "/powers super", x -> run(x, "powers super")),
                    new Entry(Material.SUNFLOWER, "&e&lToken Shop", "Spend your tokens.", "/tokens", x -> run(x, "tokens")),
                    new Entry(Material.CHEST, "&d&lCaches", "Break the caches your", "belt finds. /caches", x -> run(x, "caches")),
                    new Entry(Material.BEE_NEST, "&d&lPets", "Hatch, level and fuse pets", "that boost you. /pets", x -> run(x, "pets")));
            case "rewards" -> List.of(
                    new Entry(Material.CLOCK, "&6&lDaily Reward", "Cash or a 2x money boost,", "once a day. /daily", this::dailyMenu),
                    new Entry(Material.WRITABLE_BOOK, "&a&lDaily Tasks", "Three tasks a day for", "money and tokens. /tasks", this::tasksMenu),
                    new Entry(Material.ENDER_CHEST, "&5&lCrates", "Your keys and the four", "crates at spawn. /crates", x -> run(x, "crates")),
                    new Entry(Material.EMERALD, "&6&lMerchant", "Hourly deals at spawn.", "/merchant", x -> run(x, "merchant")),
                    new Entry(Material.FIREWORK_ROCKET, "&6&lEvents", event != null ? "&aLive: &f" + event.name() : "&7No event right now.",
                            "&7Next in about &f" + clock(Math.max(0, nextEventAt - System.currentTimeMillis())), x -> category(x, "rewards")));
            case "style" -> List.of(
                    new Entry(Material.BRICKS, "&f&lBase Styles", "Rank themes for your", "factory. /basestyle", x -> run(x, "basestyle")),
                    new Entry(Material.LEATHER_CHESTPLATE, "&d&lCosmetics", "Chat colours, trails,", "auras and effects. /cosmetics", x -> run(x, "cosmetics")),
                    new Entry(Material.NAME_TAG, "&e&lTags", "Pick a chat tag.", "/tags", x -> run(x, "tags")),
                    new Entry(Material.JUKEBOX, "&d&lBoombox", "Play music for the players", "around you. /boombox", this::boomboxMenu));
            default -> List.of(
                    new Entry(Material.GOLD_BLOCK, "&e&lRichest Players", "The top balances.", "/baltop", this::baltopMenu),
                    new Entry(Material.DIAMOND, "&b&lWeekly Top", "Weekly boards with prizes", "for the top 3. /weekly", x -> run(x, "weekly")),
                    new Entry(Material.WRITTEN_BOOK, "&a&lTutorial", "How to play GateMC.", "/tutorial", x -> run(x, "tutorial")),
                    new Entry(Material.EMERALD, "&a&lStore", "Ranks, credits, keys", "and more. /store", x -> run(x, "store")));
        };
        String title = switch (id) {
            case "factory" -> "Factory";
            case "raiding" -> "Raiding";
            case "powers" -> "Powers";
            case "rewards" -> "Rewards";
            case "style" -> "Style";
            default -> "Server";
        };
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("GateMC", title));
        int[] slots = MenuStyle.symmetric(entries.size(), 1);
        for (int i = 0; i < entries.size() && i < slots.length; i++) {
            Entry en = entries.get(i);
            menu.set(slots[i], icon(en.icon(), en.name(), en.line1(), en.line2()), c -> en.action().accept(p));
        }
        menu.set(MenuStyle.shelf(rows, 0), MenuStyle.back("the menu"), c -> {
            lastCategory.remove(p.getUniqueId());
            hubMenu(p);
        });
        menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), c -> p.closeInventory());
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private void dailyMenu(Player p) {
        DailyTasks.Day d = day(p.getUniqueId());
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        String today = LocalDate.now(ZONE).toString();
        String yesterday = LocalDate.now(ZONE).minusDays(1).toString();
        boolean claimed;
        int streak;
        synchronized (d) {
            claimed = today.equals(d.lastClaim);
            streak = claimed ? d.streak : (yesterday.equals(d.lastClaim) ? d.streak + 1 : 1);
        }
        int s = Math.min(streak, 10);
        long cashMinutes = 15 + 5L * s;
        long cash = Math.max(rewardFloorCents * 2, FactoryModule.incomePerMinute(p.getUniqueId()) * cashMinutes);
        int boostMinutes = 10 + 2 * s;
        int rows = 3;
        Menu menu = new Menu(rows, MenuStyle.title("Daily Reward"));
        if (claimed) {
            menu.set(13, icon(Material.LIME_DYE, "&a&lClaimed today", "Streak: &e" + streak + " day" + (streak == 1 ? "" : "s"),
                    "Come back tomorrow to keep it going."));
        } else {
            menu.set(11, icon(Material.GOLD_INGOT, "&a&lTake the cash", "&a+" + Money.compact(cash),
                    "(" + cashMinutes + " minutes of your income)", me.Evan52098.genscore.menu.Lore.prompt("click to claim")), c -> claimDaily(p, d, today, yesterday, cash, 0));
            menu.set(13, icon(Material.CLOCK, "&6&lStreak: " + streak + " day" + (streak == 1 ? "" : "s"),
                    "Claim every day for bigger rewards", "(up to day 10)."));
            menu.set(15, icon(Material.BLAZE_POWDER, "&b&lTake the boost", "&b2x money for " + boostMinutes + " minutes",
                    "Stacks with events and powers.", me.Evan52098.genscore.menu.Lore.prompt("click to claim")), c -> claimDaily(p, d, today, yesterday, 0, boostMinutes));
        }
        menu.set(18, MenuStyle.back("the menu"), c -> backFromSub(p));
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private void claimDaily(Player p, DailyTasks.Day d, String today, String yesterday, long cash, int boostMinutes) {
        int streak;
        synchronized (d) {
            if (today.equals(d.lastClaim)) {
                return;
            }
            d.streak = yesterday.equals(d.lastClaim) ? d.streak + 1 : 1;
            d.lastClaim = today;
            d.dirty = true;
            streak = d.streak;
        }
        p.closeInventory();
        if (cash > 0) {
            EconomyModule eco = plugin.modules().get(EconomyModule.class);
            if (eco != null && eco.service() != null) {
                eco.service().deposit(p.getUniqueId(), cash, "daily reward");
            }
            p.sendMessage(Msg.good("Daily reward: " + Msg.white("+" + Money.compact(cash)) + ". Streak: " + Msg.white(streak + " days") + "."));
        } else {
            long base = Math.max(System.currentTimeMillis(), boostUntil.getOrDefault(p.getUniqueId(), 0L));
            boostUntil.put(p.getUniqueId(), base + boostMinutes * 60_000L);
            p.sendMessage(Msg.good("Daily boost: " + Msg.white("2x money for " + boostMinutes + " minutes") + ". Streak: " + Msg.white(streak + " days") + "."));
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.2f);
    }

    private void worldsMenu(Player p) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        if (plots == null || plots.service() == null) {
            return;
        }
        var service = plots.service();
        int[] counts = service.playersPerPlatform();
        int per = service.perPlatform();
        int mine = service.platformOf(p.getUniqueId());
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Platforms"));
        int shown = Math.min(counts.length + 1, 14);
        for (int i = 0; i < shown; i++) {
            int platform = i;
            int n = i < counts.length ? counts[i] : 0;
            boolean full = n >= per;
            Material m = platform == mine ? Material.LIME_CONCRETE : full ? Material.RED_CONCRETE : n == 0 ? Material.LIGHT_GRAY_CONCRETE : Material.YELLOW_CONCRETE;
            int slot = 10 + (i / 7) * 9 + (i % 7);
            menu.set(slot, icon(m, "&f&lPlatform " + (platform + 1), "&7Factories: &f" + n + "&7/&f" + per,
                    platform == mine ? "&aYour factory is here." : full ? "&cFull - pick another." : me.Evan52098.genscore.menu.Lore.prompt("click to visit its spawn")), c -> {
                if (!full || platform == mine) {
                    p.closeInventory();
                    p.teleportAsync(service.platformHub(platform));
                } else {
                    p.sendMessage(Msg.bad("Platform " + (platform + 1) + " is full. Pick another one."));
                }
            });
        }
        menu.set(27, MenuStyle.back("the menu"), c -> backFromSub(p));
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private void run(Player p, String command) {
        String from = lastCategory.get(p.getUniqueId());
        if (from != null) {
            Menu.setReturn(p, "the " + categoryTitle(from) + " menu", x -> category(x, from));
        } else {
            Menu.setReturn(p, "the GateMC menu", this::hubMenu);
        }
        p.closeInventory();
        p.performCommand(command);
    }

    private final Map<UUID, String> lastCategory = new ConcurrentHashMap<>();

    private void backFromSub(Player p) {
        String from = lastCategory.get(p.getUniqueId());
        if (from != null) {
            category(p, from);
        } else {
            hubMenu(p);
        }
    }

    private static String categoryTitle(String id) {
        return switch (id) {
            case "factory" -> "Factory";
            case "raiding" -> "Raiding";
            case "powers" -> "Powers";
            case "rewards" -> "Rewards";
            case "style" -> "Style";
            default -> "Server";
        };
    }

    private static ItemStack icon(Material m, String name, String... lines) {
        Lore lore = Lore.builder(Lore.Accent.WHITE);
        lore.description(lines);
        return MenuStyle.clean(Lore.item(m, name, lore.build()), false);
    }

    private long tokens(UUID uuid) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        return g == null ? 0 : g.tokens();
    }

    // ------------------------------------------------------------------ baltop

    private void baltopMenu(Player p) {
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco == null || eco.service() == null) {
            p.sendMessage(Msg.bad("The economy is off."));
            return;
        }
        eco.service().topAsync().whenComplete((top, error) -> Tasks.onEntity(p, () -> {
            List<EconomyService.TopEntry> list = error == null && top != null ? top : List.of();
            int rows = 5;
            Menu menu = new Menu(rows, MenuStyle.title("Richest Players"));
            int[] slots = {13, 21, 22, 23, 29, 30, 31, 32, 33, 4};
            String[] medals = {"&6&l#1", "&f&l#2", "&c&l#3"};
            for (int i = 0; i < Math.min(10, list.size()); i++) {
                EconomyService.TopEntry e = list.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                int rank = i + 1;
                head.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(Bukkit.createProfile(e.uuid(), e.name())));
                Lore lore = Lore.builder(Lore.Accent.WHITE).stat("Balance", "&a" + Money.compact(e.cents()));
                ItemStack item = Lore.item(Material.PLAYER_HEAD, (rank <= 3 ? medals[rank - 1] : "&7#" + rank) + " &f" + e.name(), lore.build());
                head.setItemMeta(mergeName(head, item));
                menu.set(slots[i == 9 ? 9 : i], head);
            }
            long mine = eco.service().balance(p.getUniqueId());
            menu.set(40, icon(Material.GOLD_INGOT, "&eYou", "Balance: &a" + Money.compact(mine)));
            menu.set(36, MenuStyle.back("the menu"), c -> backFromSub(p));
            MenuStyle.frame(menu, rows);
            menu.open(p);
        }));
    }

    private static org.bukkit.inventory.meta.ItemMeta mergeName(ItemStack head, ItemStack named) {
        var meta = head.getItemMeta();
        var src = named.getItemMeta();
        meta.displayName(src.displayName());
        meta.lore(src.lore());
        return meta;
    }

    // ------------------------------------------------------------------ boombox

    private void boomboxMenu(Player p) {
        int rows = 5;
        Menu menu = new Menu(rows, MenuStyle.title("Boombox"));
        for (int i = 0; i < DISCS.length && i < 21; i++) {
            String disc = DISCS[i];
            int slot = 10 + (i / 7) * 9 + (i % 7);
            menu.set(slot, icon(DISC_ICONS[i], "&d&l" + pretty(disc), "Click to play for everyone", "within 32 blocks."), c -> play(p, "minecraft:music_disc." + disc, pretty(disc)));
        }
        for (int i = 0; i < HORNS.length && i < 7; i++) {
            String[] h = HORNS[i];
            menu.set(37 + i, icon(Material.GOAT_HORN, "&e&l" + h[0] + " Horn", "Blow a goat horn."), c -> play(p, "minecraft:" + h[1], h[0] + " Horn"));
        }
        menu.set(MenuStyle.shelf(rows, 8), icon(Material.BARRIER, "&c&lStop", "Stop the music around you."), c -> stopMusic(p));
        menu.set(MenuStyle.shelf(rows, 0), MenuStyle.back("the menu"), c -> backFromSub(p));
        menu.open(p);
    }

    private static String pretty(String id) {
        String s = id.replace('_', ' ');
        StringBuilder out = new StringBuilder();
        for (String w : s.split(" ")) {
            if (!w.isEmpty()) {
                out.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1)).append(' ');
            }
        }
        return out.toString().trim();
    }

    private void play(Player p, String sound, String name) {
        long left = cooldowns.remaining(p.getUniqueId(), "boombox");
        if (left > 0) {
            p.sendActionBar(Msg.bad("The boombox is warming up: " + Msg.white(Cooldowns.format(left))));
            return;
        }
        cooldowns.start(p.getUniqueId(), "boombox", 5000);
        stopMusic(p);
        Location at = p.getLocation();
        p.getWorld().playSound(at, sound, SoundCategory.RECORDS, 2.0f, 1.0f);
        p.getWorld().spawnParticle(Particle.NOTE, at.clone().add(0, 2.2, 0), 8, 0.6, 0.3, 0.6, 1);
        Component line = Msg.parse("<light_purple>♪</light_purple> <white>" + Msg.escape(p.getName()) + "</white> <gray>is playing</gray> <light_purple>"
                + Msg.escape(name) + "</light_purple>");
        for (Player o : p.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(at) <= 32 * 32) {
                Tasks.entity.run(o, () -> o.sendActionBar(line));
            }
        }
    }

    private void stopMusic(Player p) {
        Location at = p.getLocation();
        for (Player o : p.getWorld().getPlayers()) {
            if (o.getLocation().distanceSquared(at) <= 48 * 48) {
                Tasks.entity.run(o, () -> o.stopSound(SoundCategory.RECORDS));
            }
        }
    }

    // ------------------------------------------------------------------ cloak

    private void cloak(Player p) {
        long left = cooldowns.remaining(p.getUniqueId(), "cloak");
        if (left > 0) {
            p.sendMessage(Msg.bad("Your cloak is recharging: " + Msg.white(Cooldowns.format(left))));
            return;
        }
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(p.getUniqueId());
        if (cloakTokens > 0 && (g == null || !g.takeTokens(cloakTokens))) {
            p.sendMessage(Msg.bad("A cloak costs " + Msg.white(cloakTokens + " tokens") + "."));
            return;
        }
        if (cloakTokens > 0) {
            plots.saveSoon(p.getUniqueId());
        }
        cooldowns.start(p.getUniqueId(), "cloak", cloakCooldown * 1000L);
        cloakedUntil.put(p.getUniqueId(), System.currentTimeMillis() + cloakSeconds * 1000L);
        p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, cloakSeconds * 20, 0, false, false, true));
        p.removePotionEffect(PotionEffectType.GLOWING);
        p.setGlowing(false);
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p) && !o.hasPermission("genscore.vanish.see")) {
                Tasks.entity.run(o, () -> o.hidePlayer(plugin, p));
            }
        }
        p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1, 0), 25, 0.4, 0.6, 0.4, 0.02);
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 1f, 0.8f);
        p.sendMessage(Msg.good("Cloaked for " + Msg.white(cloakSeconds + "s") + ". Turrets and players can't see you."));
        Tasks.entity.later(p, () -> uncloak(p), cloakSeconds * 20L);
    }

    private void uncloak(Player p) {
        if (cloakedUntil.remove(p.getUniqueId()) == null) {
            return;
        }
        for (Player o : Bukkit.getOnlinePlayers()) {
            if (!o.equals(p)) {
                Tasks.entity.run(o, () -> o.showPlayer(plugin, p));
            }
        }
        if (p.isOnline()) {
            p.sendActionBar(Msg.neutral("Your cloak wore off."));
            p.playSound(p.getLocation(), Sound.ENTITY_ILLUSIONER_MIRROR_MOVE, 0.6f, 1.4f);
        }
    }

    // ------------------------------------------------------------------ turbo

    public static final String TURBO = "genscore.turbo";

    private void toggleTurbo(Player p) {
        if (!p.hasPermission(TURBO)) {
            p.sendMessage(Msg.bad("Turbo is a rank perk. Get a rank at " + Msg.white("/store") + " to run faster around the map."));
            return;
        }
        if (!turboOff.remove(p.getUniqueId())) {
            turboOff.add(p.getUniqueId());
            p.removePotionEffect(PotionEffectType.SPEED);
            p.sendActionBar(Msg.neutral("Turbo " + Msg.white("off") + "."));
        } else {
            applyTurbo(p);
            p.sendActionBar(Msg.good("Turbo " + Msg.white("on") + "."));
        }
    }

    private void applyTurbo(Player p) {
        if (!p.hasPermission(TURBO)) {
            PotionEffect have = p.getPotionEffect(PotionEffectType.SPEED);
            if (have != null && have.isInfinite() && have.isAmbient()) {
                p.removePotionEffect(PotionEffectType.SPEED);
            }
            return;
        }
        if (turboLevel <= 0 || turboOff.contains(p.getUniqueId())) {
            return;
        }
        p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, PotionEffect.INFINITE_DURATION, turboLevel - 1, true, false, false));
    }

    @EventHandler
    public void onPotion(EntityPotionEffectEvent event) {
        if (event.getEntity() instanceof Player p && event.getModifiedType() == PotionEffectType.SPEED
                && event.getNewEffect() == null && !turboOff.contains(p.getUniqueId())
                && event.getCause() != EntityPotionEffectEvent.Cause.PLUGIN) {
            Tasks.entity.later(p, () -> applyTurbo(p), 2L);
        }
    }

    // ------------------------------------------------------------------ lifecycle

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Tasks.entity.later(p, () -> applyTurbo(p), 5L);
        if (this.event != null && eventEnds > System.currentTimeMillis()) {
            Tasks.entity.later(p, () -> p.showBossBar(eventBar), 20L);
        }
        for (Map.Entry<UUID, Long> e : cloakedUntil.entrySet()) {
            Player c = Bukkit.getPlayer(e.getKey());
            if (c != null && e.getValue() > System.currentTimeMillis() && !p.hasPermission("genscore.vanish.see")) {
                p.hidePlayer(plugin, c);
            }
        }
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Tasks.entity.later(p, () -> applyTurbo(p), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (cloakedUntil.containsKey(p.getUniqueId())) {
            uncloak(p);
        }
        playMinutes.remove(p.getUniqueId());
    }

    private void minuteTick() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            addProgress(p.getUniqueId(), DailyTasks.Type.PLAYTIME, 1);
        }
    }

    // ------------------------------------------------------------------ daily tasks

    private @Nullable DailyTasks.Day day(UUID uuid) {
        PlayerData pd = plugin.playerData().get(uuid);
        if (pd == null) {
            return null;
        }
        DailyTasks.Day d = pd.get(DailyTasks.SECTION);
        if (d == null) {
            return null;
        }
        String today = LocalDate.now(ZONE).toString();
        synchronized (d) {
            if (!today.equals(d.day) || d.tasks.isEmpty()) {
                d.day = today;
                d.tasks.clear();
                d.bonusPaid = false;
                d.tasks.addAll(roll());
                d.dirty = true;
            }
        }
        return d;
    }

    private List<DailyTasks.Task> roll() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        List<DailyTasks.Type> pool = new ArrayList<>(List.of(DailyTasks.Type.values()));
        Collections.shuffle(pool, r);
        List<DailyTasks.Task> out = new ArrayList<>();
        int difficulty = 0;
        for (DailyTasks.Type t : pool) {
            if (out.size() >= 3) {
                break;
            }
            if (t == DailyTasks.Type.DEFEND && difficulty < 2) {
                continue;
            }
            long target = switch (t) {
                case SELL -> new long[]{300, 1200, 3500}[difficulty];
                case EARN -> new long[]{20, 60, 150}[difficulty];
                case UPGRADE -> new long[]{3, 8, 15}[difficulty];
                case RAID -> new long[]{1, 3, 6}[difficulty];
                case CACHE -> new long[]{1, 1, 2}[difficulty];
                case PLAYTIME -> new long[]{15, 40, 90}[difficulty];
                case DEFEND -> 2;
            };
            int minutes = new int[]{10, 25, 60}[difficulty];
            int tokens = new int[]{1, 2, 4}[difficulty];
            out.add(new DailyTasks.Task(t, target, minutes, tokens, difficulty, 0, false));
            difficulty++;
        }
        return out;
    }

    private void addProgress(UUID uuid, DailyTasks.Type type, long amount) {
        DailyTasks.Day d = day(uuid);
        if (d == null) {
            return;
        }
        List<DailyTasks.Task> finished = new ArrayList<>();
        boolean allDone;
        boolean payBonus = false;
        synchronized (d) {
            for (DailyTasks.Task t : d.tasks) {
                if (t.type != type || t.done) {
                    continue;
                }
                long add = type == DailyTasks.Type.EARN ? amount : amount;
                t.progress = Math.min(target(uuid, t), t.progress + add);
                if (t.progress >= target(uuid, t)) {
                    t.done = true;
                    finished.add(t);
                }
                d.dirty = true;
            }
            allDone = !d.tasks.isEmpty() && d.tasks.stream().allMatch(t -> t.done);
            if (allDone && !d.bonusPaid && !finished.isEmpty()) {
                d.bonusPaid = true;
                payBonus = true;
            }
        }
        for (DailyTasks.Task t : finished) {
            reward(uuid, t.rewardCents, t.rewardTokens, describe(uuid, t));
        }
        if (payBonus) {
            reward(uuid, 45, 3, "All three daily tasks");
        }
    }

    private long target(UUID uuid, DailyTasks.Task t) {
        return t.type == DailyTasks.Type.EARN ? t.target * 100 * Math.max(1, incomeAnchor(uuid)) / 100 : t.target;
    }

    private long incomeAnchor(UUID uuid) {
        return Math.max(500, FactoryModule.incomePerMinute(uuid));
    }

    private void reward(UUID uuid, long minutes, int tokens, String what) {
        long cents = Math.max(rewardFloorCents, Math.round(FactoryModule.incomePerMinute(uuid) * minutes * rewardMinutesScale));
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco != null && eco.service() != null) {
            eco.service().deposit(uuid, cents, "daily task");
        }
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        var g = plots == null ? null : plots.gensPlayer(uuid);
        if (g != null && tokens > 0) {
            g.addTokens(me.Evan52098.genscore.factory.RankPerks.tokens(uuid, tokens));
            plots.saveSoon(uuid);
        }
        plugin.playerData().saveSoon(uuid);
        Player p = Bukkit.getPlayer(uuid);
        if (p != null) {
            Tasks.entity.run(p, () -> {
                p.sendMessage(Msg.parse(Msg.headerText("Task Done") + "<white>" + Msg.escape(what) + "</white> <gray>→</gray> <green>+"
                        + Money.compact(cents) + "</green>" + (tokens > 0 ? " <yellow>+" + tokens + " tokens</yellow>" : "")));
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.2f);
            });
        }
    }

    private String describe(UUID uuid, DailyTasks.Task t) {
        String amount = t.type == DailyTasks.Type.EARN ? Money.compact(target(uuid, t)) : String.format("%,d", t.target);
        return String.format(t.type.text, amount);
    }

    private void tasksMenu(Player p) {
        DailyTasks.Day d = day(p.getUniqueId());
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Daily Tasks"));
        List<DailyTasks.Task> tasks;
        boolean bonus;
        synchronized (d) {
            tasks = new ArrayList<>(d.tasks);
            bonus = d.bonusPaid;
        }
        int[] slots = {11, 13, 15};
        String[] diff = {"&aEasy", "&eMedium", "&cHard"};
        for (int i = 0; i < tasks.size() && i < 3; i++) {
            DailyTasks.Task t = tasks.get(i);
            long target = target(p.getUniqueId(), t);
            long prog = Math.min(target, t.progress);
            int bars = (int) Math.round(20.0 * prog / Math.max(1, target));
            String bar = "&a" + "|".repeat(bars) + "&8" + "|".repeat(20 - bars);
            long cents = Math.max(rewardFloorCents, Math.round(FactoryModule.incomePerMinute(p.getUniqueId()) * t.rewardCents * rewardMinutesScale));
            Lore lore = Lore.builder(t.done ? Lore.Accent.CYAN : Lore.Accent.WHITE).subcategory(diff[Math.min(2, t.difficulty)].substring(2) + " task")
                    .description(describe(p.getUniqueId(), t)).divider()
                    .stat("Progress", t.type == DailyTasks.Type.EARN ? Money.compact(prog) + " / " + Money.compact(target) : prog + " / " + target)
                    .description(bar)
                    .stat("Reward", "&a" + Money.compact(cents) + " &7+ &e" + t.rewardTokens + " tokens");
            if (t.done) {
                lore.description("&a✔ Done");
            }
            Material icon = t.done ? Material.LIME_DYE : Material.matchMaterial(t.type.icon);
            menu.set(slots[i], MenuStyle.clean(Lore.item(icon == null ? Material.PAPER : icon,
                    diff[Math.min(2, t.difficulty)] + " &7- &f" + describe(p.getUniqueId(), t), lore.build()), t.done));
        }
        menu.set(22, icon(bonus ? Material.LIME_DYE : Material.GOLD_NUGGET, "&6&lDaily Bonus",
                "Finish all three for an extra", "45 minutes of income and &e3 tokens&7.", bonus ? "&a✔ Claimed" : "&7New tasks at midnight (New York)."));
        menu.set(27, MenuStyle.back("the menu"), c -> backFromSub(p));
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }
}
