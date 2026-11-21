package me.Evan52098.genscore.tutorial;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.factory.FactoryModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.Plot;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.plots.PlotService;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class TutorialModule implements GensModule, Listener {

    enum Kind { EVENT, POLL }

    record Step(String id, String title, String hint, String command, Material icon, Kind kind, boolean trail) {
    }

    static final List<Step> STEPS = List.of(
            new Step("menu", "Open the menu", "Right-click the Nether Star in slot 5, or type /menu.", "/menu", Material.NETHER_STAR, Kind.EVENT, false),
            new Step("factory", "Go to your factory", "Follow the green trail, or type /plot.", "/plot", Material.CHEST_MINECART, Kind.POLL, true),
            new Step("gen", "Upgrade your generator", "Open /belt and upgrade your Gen for better items.", "/belt", Material.FURNACE, Kind.POLL, false),
            new Step("speed", "Speed up your belt", "Open /belt and buy a Speed upgrade to sell faster.", "/belt", Material.SUGAR, Kind.POLL, false),
            new Step("gate", "Close your gate", "Press the button on your gate, or type /gate. Closed gates can't be raided.", "/gate", Material.IRON_DOOR, Kind.EVENT, true),
            new Step("defence", "Check your defences", "Open /defence: turrets, traps and armour stop raiders.", "/defence", Material.SHIELD, Kind.EVENT, false),
            new Step("gear", "Look at raid gear", "Open /gear: a Raid Pick lets you break open belts.", "/gear", Material.IRON_PICKAXE, Kind.EVENT, false),
            new Step("daily", "Claim your daily reward", "Open /daily and take the cash or the boost.", "/daily", Material.CLOCK, Kind.EVENT, false));

    private static volatile TutorialModule instance;

    private GensCore plugin;
    private NamespacedKey doneKey;
    private NamespacedKey stateKey;
    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();
    private final Map<UUID, String> announced = new ConcurrentHashMap<>();
    private volatile int stepTokens;
    private volatile int finalTokens;
    private volatile List<String> finalCommands = List.of();
    private volatile boolean autoStart;

    @Override
    public String id() {
        return "tutorial";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.doneKey = new NamespacedKey(plugin, "tutorial_done");
        this.stateKey = new NamespacedKey(plugin, "tutorial_state");
        reload();
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Tasks.global.timer(t -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(p, () -> tick(p));
            }
        }, 40, 20);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("tutorial");
        autoStart = c == null || c.getBoolean("auto-start", true);
        stepTokens = c == null ? 2 : Math.max(0, c.getInt("step-tokens", 2));
        finalTokens = c == null ? 10 : Math.max(0, c.getInt("final-tokens", 10));
        finalCommands = c == null || !c.isList("final-commands") ? List.of("crate give {player} vote 2") : c.getStringList("final-commands");
    }

    @Override
    public void disable() {
        instance = null;
        for (Map.Entry<UUID, BossBar> e : bars.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue());
            }
        }
        bars.clear();
    }

    public static void done(Player p, String step) {
        TutorialModule m = instance;
        if (m != null) {
            Tasks.onEntity(p, () -> m.complete(p, step));
        }
    }

    public static boolean begin(Player p) {
        TutorialModule m = instance;
        if (m == null) {
            return false;
        }
        Tasks.onEntity(p, () -> m.start(p, false));
        return true;
    }

    public static boolean openFor(Player p) {
        TutorialModule m = instance;
        if (m == null) {
            return false;
        }
        Tasks.onEntity(p, () -> m.menu(p));
        return true;
    }

    // ------------------------------------------------------------------ state

    private String state(Player p) {
        return p.getPersistentDataContainer().getOrDefault(stateKey, PersistentDataType.STRING, "");
    }

    private void setState(Player p, String s) {
        p.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, s);
    }

    private Set<String> doneSet(Player p) {
        String raw = p.getPersistentDataContainer().getOrDefault(doneKey, PersistentDataType.STRING, "");
        Set<String> out = new LinkedHashSet<>();
        for (String s : raw.split(",")) {
            if (!s.isBlank()) {
                out.add(s);
            }
        }
        return out;
    }

    private void saveDone(Player p, Set<String> done) {
        p.getPersistentDataContainer().set(doneKey, PersistentDataType.STRING, String.join(",", done));
    }

    private @Nullable Step current(Set<String> done) {
        for (Step s : STEPS) {
            if (!done.contains(s.id())) {
                return s;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ flow

    private void start(Player p, boolean restart) {
        if (restart) {
            saveDone(p, new LinkedHashSet<>());
        }
        setState(p, "active");
        announced.remove(p.getUniqueId());
        p.showTitle(Title.title(Msg.parse("<gold><bold>TUTORIAL"), Msg.parse("<gray>Learn GateMC in " + STEPS.size() + " quick steps"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(2), Duration.ofMillis(500))));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1f, 1.4f);
        p.sendMessage(Msg.neutral("Tutorial started. Each step finishes by itself when you do it, and pays " + Msg.white(stepTokens + " tokens") + ". "
                + "Type " + Msg.white("/tutorial") + " for the checklist or " + Msg.white("/tutorial skip") + " to stop."));
        tick(p);
    }

    private void complete(Player p, String step) {
        if (!"active".equals(state(p))) {
            return;
        }
        Set<String> done = doneSet(p);
        if (!done.add(step)) {
            return;
        }
        saveDone(p, done);
        Step def = STEPS.stream().filter(s -> s.id().equals(step)).findFirst().orElse(null);
        if (def == null) {
            return;
        }
        giveTokens(p, stepTokens);
        Step next = current(done);
        if (next == null) {
            finish(p);
            return;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
        p.showTitle(Title.title(Msg.parse("<green>✔ " + def.title()), Msg.parse("<gray>Next: <white>" + next.title()),
                Title.Times.times(Duration.ofMillis(200), Duration.ofMillis(1600), Duration.ofMillis(400))));
        tick(p);
    }

    private void finish(Player p) {
        setState(p, "done");
        hideBar(p);
        giveTokens(p, finalTokens);
        for (String cmd : finalCommands) {
            String line = cmd.replace("{player}", p.getName());
            Tasks.global.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
        }
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.showTitle(Title.title(Msg.parse("<gold><bold>TUTORIAL DONE"), Msg.parse("<gray>You're ready. Go build the biggest factory!"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        p.sendMessage(Msg.good("Tutorial complete! Bonus: " + Msg.white(finalTokens + " tokens") + " and crate keys. "
                + "Next: rebirths, machines, pets and raiding. " + Msg.white("/tutorial book") + " has the full guide."));
    }

    private void stop(Player p) {
        setState(p, "off");
        hideBar(p);
        announced.remove(p.getUniqueId());
    }

    private void giveTokens(Player p, int amount) {
        if (amount <= 0) {
            return;
        }
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        GensPlayer g = plots == null ? null : plots.gensPlayer(p.getUniqueId());
        if (g == null) {
            return;
        }
        g.addTokens(amount);
        plots.saveSoon(p.getUniqueId());
        p.sendActionBar(Msg.parse("<yellow>+" + amount + " tokens</yellow> <gray>(tutorial)"));
    }

    private void tick(Player p) {
        if (!p.isOnline() || !"active".equals(state(p))) {
            return;
        }
        Set<String> done = doneSet(p);
        for (Step s : STEPS) {
            if (s.kind() == Kind.POLL && !done.contains(s.id()) && polled(p, s.id())) {
                complete(p, s.id());
                return;
            }
        }
        Step step = current(done);
        if (step == null) {
            finish(p);
            return;
        }
        int index = STEPS.indexOf(step);
        BossBar bar = bars.computeIfAbsent(p.getUniqueId(), u -> BossBar.bossBar(Component.empty(), 0f, BossBar.Color.YELLOW, BossBar.Overlay.NOTCHED_10));
        bar.name(Msg.parse("<gold><bold>Tutorial " + (index + 1) + "/" + STEPS.size() + "</bold></gold> <dark_gray>»</dark_gray> <white>" + step.title()));
        bar.progress(Math.min(1f, (float) done.size() / STEPS.size()));
        p.showBossBar(bar);
        if (!step.id().equals(announced.get(p.getUniqueId()))) {
            announced.put(p.getUniqueId(), step.id());
            p.sendMessage(Msg.parse("<gold><bold>Tutorial " + (index + 1) + "/" + STEPS.size() + ":</bold></gold> <white>" + step.title() + "</white>")
                    .append(Msg.parse("<gray> - " + Msg.escape(step.hint()) + " "))
                    .append(Msg.parse("<yellow><u>[" + step.command() + "]</u></yellow>")
                            .clickEvent(ClickEvent.runCommand(step.command()))
                            .hoverEvent(HoverEvent.showText(Msg.parse("<gray>Click to run " + step.command())))));
        }
        if (step.trail()) {
            trail(p, target(p, step.id()));
        }
    }

    private boolean polled(Player p, String step) {
        UUID u = p.getUniqueId();
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotData data = plots == null ? null : plots.data(u);
        return switch (step) {
            case "factory" -> inOwnFactory(p, plots);
            case "gen" -> data != null && data.genLevel() >= 1;
            case "speed" -> data != null && data.speedLevel() >= 1;
            default -> false;
        };
    }

    private boolean inOwnFactory(Player p, @Nullable PlotsModule plots) {
        PlotService service = plots == null ? null : plots.service();
        if (service == null) {
            return false;
        }
        Location at = p.getLocation();
        Plot plot = service.plotAt(at);
        return plot != null && plot.owner().equals(p.getUniqueId()) && service.view(plot).inside(at.getBlockX(), at.getBlockZ());
    }

    private @Nullable Location target(Player p, String step) {
        if ("gate".equals(step)) {
            return FactoryModule.gateFront(p.getUniqueId());
        }
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotService service = plots == null ? null : plots.service();
        Plot plot = service == null ? null : service.plotOf(p.getUniqueId());
        return plot == null ? null : service.spawnOf(plot);
    }

    private static void trail(Player p, @Nullable Location to) {
        if (to == null || to.getWorld() != p.getWorld()) {
            return;
        }
        Location from = p.getLocation().add(0, 0.3, 0);
        Vector dir = to.toVector().subtract(from.toVector());
        double length = dir.length();
        if (length < 2.5) {
            return;
        }
        dir.normalize();
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(80, 255, 120), 1.1f);
        for (double d = 1.2; d < Math.min(length, 14); d += 0.6) {
            p.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0, dust);
        }
    }

    private void hideBar(Player p) {
        BossBar bar = bars.remove(p.getUniqueId());
        if (bar != null) {
            p.hideBossBar(bar);
        }
    }

    // ------------------------------------------------------------------ menu

    private void menu(Player p) {
        Set<String> done = doneSet(p);
        String st = state(p);
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Tutorial"));
        Step cur = current(done);
        for (int i = 0; i < STEPS.size(); i++) {
            Step s = STEPS.get(i);
            boolean ok = done.contains(s.id());
            boolean now = "active".equals(st) && s == cur;
            Lore lore = Lore.builder(ok ? Lore.Accent.GREEN : now ? Lore.Accent.CYAN : Lore.Accent.WHITE)
                    .subcategory("Step " + (i + 1)).divider().description(s.hint());
            lore.stat("Status", ok ? "&aDone" : now ? "&eIn progress" : "&7To do");
            if (!ok) {
                lore.clickPrompt("Click to run " + s.command());
            }
            int slot = 10 + i + (i >= 4 ? 5 : 0);
            Consumer<org.bukkit.event.inventory.InventoryClickEvent> click = ok ? null : e -> {
                p.closeInventory();
                p.performCommand(s.command().substring(1));
            };
            menu.set(slot, Lore.item(ok ? Material.LIME_DYE : s.icon(), (ok ? "&a✔ " : now ? "&e➜ " : "&f") + s.title(), lore.build()), click);
        }
        boolean active = "active".equals(st);
        menu.set(rows * 9 - 7, Lore.item(Material.WRITABLE_BOOK, "&fGuide book", Lore.builder(Lore.Accent.WHITE)
                .description("Everything explained", "on a few short pages.").clickPrompt("Click to read").build()), e -> {
            p.closeInventory();
            p.performCommand("guidebook");
        });
        if (active) {
            menu.set(rows * 9 - 3, Lore.item(Material.BARRIER, "&cSkip tutorial", Lore.builder(Lore.Accent.WHITE)
                    .description("Hide the tutorial.", "Start again any time.").clickPrompt("Click to skip").build()), e -> {
                p.closeInventory();
                stop(p);
                p.sendMessage(Msg.neutral("Tutorial hidden. " + Msg.white("/tutorial") + " brings it back."));
            });
        } else {
            menu.set(rows * 9 - 3, Lore.item(Material.LIME_CONCRETE, "done".equals(st) ? "&aPlay it again" : "&aStart tutorial",
                    Lore.builder(Lore.Accent.GREEN).description("Steps you've already", "done tick off by themselves.").clickPrompt("Click to start").build()), e -> {
                p.closeInventory();
                start(p, "done".equals(st));
            });
        }
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    // ------------------------------------------------------------------ commands

    @Command({"tutorial", "guide"})
    @Description("The GateMC tutorial checklist")
    public void tutorialCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> menu(p));
    }

    @Command({"tutorial start", "tutorial restart"})
    @Description("Start the tutorial")
    public void tutorialStart(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> start(p, "done".equals(state(p))));
    }

    @Command("tutorial skip")
    @Description("Hide the tutorial")
    public void tutorialSkip(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> {
            stop(p);
            p.sendMessage(Msg.neutral("Tutorial hidden. " + Msg.white("/tutorial") + " brings it back."));
        });
    }

    @Command("tutorial book")
    @Description("Read the GateMC guide book")
    public void tutorialBook(BukkitCommandActor actor) {
        actor.requirePlayer().performCommand("guidebook");
    }

    // ------------------------------------------------------------------ events

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        if (pdc.has(stateKey, PersistentDataType.STRING)) {
            return;
        }
        PlayerData data = plugin.playerData().get(p.getUniqueId());
        boolean first = data != null ? data.isFirstTime() : !p.hasPlayedBefore();
        if (first && autoStart) {
            setState(p, "pending");
            Tasks.entity.later(p, () -> introFinished(p), 20L * 45);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        bars.remove(event.getPlayer().getUniqueId());
        announced.remove(event.getPlayer().getUniqueId());
    }

    public static void introFinished(Player p) {
        TutorialModule m = instance;
        if (m == null) {
            return;
        }
        Tasks.onEntity(p, () -> {
            if ("pending".equals(m.state(p))) {
                m.start(p, false);
            }
        });
    }
}
