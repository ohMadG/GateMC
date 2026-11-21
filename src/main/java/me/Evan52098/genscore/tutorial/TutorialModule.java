package me.Evan52098.genscore.tutorial;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.factory.FactoryModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.Plot;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.plots.PlotService;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class TutorialModule implements GensModule, Listener {

    sealed interface Beat permits Chapter, Say, Point, Clear, Give, Wait {
    }

    record Chapter(String title, String subtitle) implements Beat {
    }

    record Say(String text) implements Beat {
    }

    record Point(String landmark, String label) implements Beat {
    }

    record Clear() implements Beat {
    }

    record Give(String id, long cents, int tokens, String why) implements Beat {
    }

    record Wait(String id, String task, String command, @Nullable String trail) implements Beat {
    }

    private static final String GUIDE = "<green><bold>Guide</bold></green> <dark_gray>»</dark_gray> ";

    static final List<Beat> SCRIPT = List.of(
            new Chapter("Welcome to GateMC", "Your factory tutorial"),
            new Say("Hey! I'm the Guide. I'll walk you through everything, one step at a time."),
            new Say("GateMC is a <gold>factory tycoon</gold>. You own a factory, it makes items, a <white>conveyor belt</white> carries them, and they sell for money."),
            new Say("You spend that money to grow the factory, and you raid other players' factories to steal theirs. They will try to raid you too."),
            new Say("When I give you a job, it shows in the <yellow>bar at the top</yellow>. Click any of my messages to skip ahead. <white>/tutorial</white> repeats the current step."),

            new Chapter("1. Your Factory", "Where the money comes from"),
            new Say("First, let's go home. Every player gets their own factory on a platform."),
            new Wait("factory", "Go to your factory", "/plot", "home"),
            new Say("This is yours. Nobody can build here and you can't lose it."),
            new Point("gen", "GENERATOR"),
            new Say("That's your <gold>generator</gold>. It makes an item every moment and drops it on the start of the belt."),
            new Point("belt", "CONVEYOR BELT"),
            new Say("The <white>belt</white> carries items along. Every working belt segment adds <green>+10%</green> to the item's value, so a longer belt means more money per item."),
            new Point("sell", "SELL POINT"),
            new Say("At the end of the belt, items sell automatically and the money lands in your balance every few seconds. While I'm showing you around, sales don't pay out yet: you get a <gold>starting bonus</gold> when you finish."),
            new Wait("sale", "Watch your first item sell", "/belt", null),
            new Clear(),
            new Say("<green>Ka-ching!</green> That's your factory working. Once the tutorial is done it keeps earning while you're online, even while you walk around."),

            new Chapter("2. Upgrades", "Make more, sell faster"),
            new Say("Open your belt menu with <white>/belt</white>. There are three main upgrades in it, and the ones I ask for are <green>free</green> during the tutorial."),
            new Say("<gold>Gen</gold>: your generator makes better items. There are 150 tiers, from Cobblestone up to Dragon Eggs, and each tier is worth more."),
            new Wait("gen", "Upgrade your Gen once", "/belt", null),
            new Say("Nice. <white>Length</white> adds belt segments: more items on the belt at once, and each one is worth more when it sells."),
            new Wait("length", "Buy a Length upgrade", "/belt", null),
            new Say("<white>Speed</white> moves the belt faster, so more items sell every second. Speed is how your income really grows."),
            new Wait("speed", "Buy a Speed upgrade", "/belt", null),
            new Say("Those were on the house. From now on upgrades cost money, and they get pricier as you go. Early ones are quick, later ones take a while. That's normal: keep the belt running and keep buying."),

            new Chapter("3. Tokens & Caches", "The second currency"),
            new Say("Now and then a <yellow>token</yellow> or a <light_purple>cache</light_purple> rides your belt instead of an item. You can spot them by their model."),
            new Say("Tokens pay for the token shop, defences, powers and managers. Caches are mystery boxes: money, tokens, crate keys and pet eggs."),
            new Say("Tokens also come from raids, daily tasks, events and the hourly playtime reward."),
            new Wait("tokens", "Open the token shop", "/tokens", null),
            new Say("<gold>Sell value</gold> adds to everything you sell, forever. <green>Drop luck</green> makes tokens and caches show up more often. Both are great first buys."),

            new Chapter("4. Your Gate", "The only way in"),
            new Say("Here's the important part. Raiders can only get into your factory while your <red>gate is open</red>."),
            new Point("button", "GATE BUTTON"),
            new Say("Press the button at your gate (or type <white>/gate</white>) to close it. It stays shut for <white>45 seconds</white>, then rolls open again."),
            new Say("In the last few seconds you can <white>re-up</white> it to keep it closed. Clicking too early or on a perfect timer gets it locked for a bit, so play it like a person."),
            new Wait("gate", "Close your gate", "/gate", "gate"),
            new Clear(),
            new Say("Closed! Raiders bounce off it now. Players inside get pushed out when it shuts."),
            new Say("Brand new players get a 15 minute shield on top, so nobody can raid you while you learn."),

            new Chapter("5. Defences", "Make raiders regret it"),
            new Say("You can't keep the gate closed forever, so build defences. Open <white>/defence</white> to see them."),
            new Say("<white>Armour</white> gives every belt segment more health. <red>Turrets</red> shoot anyone who walks in. <dark_purple>Traps</dark_purple> slow intruders and, later, throw them back out."),
            new Say("<white>Gate time</white> keeps your gate closed longer with each level."),
            new Point("turret", "TURRET"),
            new Wait("turret", "Buy a Turret in /defence", "/defence", null),
            new Clear(),
            new Say("Your turret is up. An alarm also tells you whenever someone walks into your factory."),

            new Chapter("6. Raiding", "Take their money"),
            new Say("Your turn to attack. Find a factory with an <red>open gate</red>, walk in and break its belt with a <white>Raid Pick</white>."),
            new Say("Each belt segment takes a few hits. When it breaks, you take part of the items on it, a share of the owner's balance and a token."),
            new Say("After you break <white>3 segments</white> the factory gets a 5 minute shield. Broken belts fix themselves after a while, so be quick."),
            new Say("While you're raiding, money the owner earns is <gold>siphoned</gold> to you until they close their gate. Watch out for their turrets!"),
            new Wait("raidpick", "Grab a Raid Pick from /gear", "/gear", null),
            new Say("Upgrading your gear in <white>/gear</white> hits harder and loots more. It costs money, not tokens."),
            new Say("Gate closed? A <white>Lockpick</white> (10 tokens) lets you pick it with a quick minigame. <white>/cloak</white> makes you invisible to turrets for a short time."),
            new Say("Playing with friends? <white>/trust</white> lets someone through your gate, past your turrets, and stops them raiding you."),

            new Chapter("7. Growing Big", "The long game"),
            new Say("<gold>Machines</gold> unlock as your belt gets longer. Each one multiplies everything that passes it: Smelter, Polisher, Refinery and more."),
            new Wait("machines", "Look at /machines", "/machines", null),
            new Say("<white>Managers</white> keep upgrading your factory for you while you play. Ranks get some for free."),
            new Wait("managers", "Look at /managers", "/managers", null),
            new Say("<light_purple>Powers</light_purple> are perks you buy with tokens: faster belts, better luck, stronger raids, tougher defences. Only one is active at a time."),
            new Wait("powers", "Open /powers", "/powers", null),
            new Say("<green>Pets</green> hatch from eggs and follow you around, each with a bonus. Three of the same pet merge into a golden one."),
            new Wait("pets", "Open /pets", "/pets", null),
            new Say("Once your Gen reaches tier 60, you can <light_purple>/rebirth</light_purple>. You start the factory again, but sell for <green>+50%</green> forever, and it stacks each time."),
            new Say("Rebirth keeps everything you bought with tokens: managers, defences, powers, pets and the token shop."),

            new Chapter("8. Every Day", "Free stuff"),
            new Say("<white>/daily</white> gives you cash or a 2x money boost every day. Claim it daily to build a streak for bigger rewards."),
            new Wait("daily", "Claim your /daily reward", "/daily", null),
            new Say("<white>/tasks</white> has three new tasks every day. <white>/weekly</white> boards pay prizes to the top earners and raiders each week."),
            new Say("Watch for <yellow>events</yellow> like 2x money, and the <gold>Travelling Merchant</gold> at spawn every hour with deals."),
            new Say("Link your Discord with <white>/link</white> for a reward. Boosting the Discord gives a pet egg every month."),
            new Say("<white>/menu</white> (the Nether Star in slot 5) has everything I showed you. <white>/tutorial book</white> has it all written down."),
            new Say("That's it! Close your gate, grow your belt and go take some money. Good luck!"));

    private static final int GATE_STEP = gateStep();
    private static final Set<UUID> HELD = ConcurrentHashMap.newKeySet();

    private static volatile TutorialModule instance;

    private GensCore plugin;
    private NamespacedKey stateKey;
    private NamespacedKey beatKey;
    private NamespacedKey paidKey;
    private final Map<UUID, Run> runs = new ConcurrentHashMap<>();
    private volatile int finalTokens;
    private volatile long finalCents;
    private volatile List<String> finalCommands = List.of();
    private volatile boolean autoStart;

    private static final class Run {
        final BossBar bar = BossBar.bossBar(Component.empty(), 0f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
        final Set<String> events = ConcurrentHashMap.newKeySet();
        long nextAt;
        volatile int waitingFor = -1;
        long baseline;
        long remindAt;
        String chapter = "";
        @Nullable TextDisplay label;
        @Nullable Location pointAt;
        volatile @Nullable Mannequin npc;
        volatile @Nullable TextDisplay speech;
        volatile boolean spawning;
        volatile String typing = "";
        volatile String finalText = "";
        volatile int typed;
        @Nullable ScheduledTask typer;
    }

    @Override
    public String id() {
        return "tutorial";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.stateKey = new NamespacedKey(plugin, "tutorial_state");
        this.beatKey = new NamespacedKey(plugin, "tutorial_beat");
        this.paidKey = new NamespacedKey(plugin, "tutorial_paid");
        reload();
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Tasks.global.timer(t -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (runs.containsKey(p.getUniqueId())) {
                    Tasks.onEntity(p, () -> tick(p));
                }
            }
        }, 40, 10);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("tutorial");
        autoStart = c == null || c.getBoolean("auto-start", true);
        finalTokens = c == null ? 25 : Math.max(0, c.getInt("final-tokens", 25));
        finalCents = Math.max(0, Math.round((c == null ? 350 : c.getDouble("final-cash", 350)) * 100));
        finalCommands = c == null || !c.isList("final-commands") ? List.of("crate give {player} vote 2") : c.getStringList("final-commands");
    }

    @Override
    public void disable() {
        instance = null;
        HELD.clear();
        for (Map.Entry<UUID, Run> e : runs.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p != null) {
                p.hideBossBar(e.getValue().bar);
            }
            removeLabel(e.getValue());
            removeNpc(e.getValue());
        }
        runs.clear();
    }

    // ------------------------------------------------------------------ hooks other modules call

    public static void done(Player p, String event) {
        TutorialModule m = instance;
        if (m == null) {
            return;
        }
        Run r = m.runs.get(p.getUniqueId());
        if (r != null) {
            r.events.add(event);
        }
    }

    public static boolean freeUpgrade(Player p, String id) {
        TutorialModule m = instance;
        Run r = m == null ? null : m.runs.get(p.getUniqueId());
        if (r == null) {
            return false;
        }
        int w = r.waitingFor;
        return w >= 0 && w < SCRIPT.size() && SCRIPT.get(w) instanceof Wait wait && wait.id().equals(id)
                && !r.events.contains("free:" + id);
    }

    public static void usedFree(Player p, String id) {
        TutorialModule m = instance;
        Run r = m == null ? null : m.runs.get(p.getUniqueId());
        if (r != null) {
            r.events.add("free:" + id);
        }
    }

    public static boolean noIncome(UUID owner) {
        TutorialModule m = instance;
        return m != null && m.runs.containsKey(owner);
    }

    public static void sold(UUID owner) {
        TutorialModule m = instance;
        Run r = m == null ? null : m.runs.get(owner);
        if (r != null) {
            r.events.add("sale");
        }
    }

    public static boolean holdsGate(UUID owner) {
        return HELD.contains(owner);
    }

    private static int gateStep() {
        for (int i = 0; i < SCRIPT.size(); i++) {
            if (SCRIPT.get(i) instanceof Wait w && "gate".equals(w.id())) {
                for (int j = i; j >= 0; j--) {
                    if (SCRIPT.get(j) instanceof Chapter) {
                        return j;
                    }
                }
                return i;
            }
        }
        return 0;
    }

    private void hold(Player p, int beat) {
        if (beat < GATE_STEP) {
            HELD.add(p.getUniqueId());
        } else if (HELD.remove(p.getUniqueId())) {
            FactoryModule.tutorialReleased(p.getUniqueId());
        }
    }

    public static boolean openFor(Player p) {
        TutorialModule m = instance;
        if (m == null) {
            return false;
        }
        Tasks.onEntity(p, () -> m.command(p));
        return true;
    }

    public static void introFinished(Player p) {
        TutorialModule m = instance;
        if (m == null) {
            return;
        }
        Tasks.onEntity(p, () -> {
            if ("pending".equals(m.state(p))) {
                m.start(p, true);
            }
        });
    }

    // ------------------------------------------------------------------ state

    private String state(Player p) {
        return p.getPersistentDataContainer().getOrDefault(stateKey, PersistentDataType.STRING, "");
    }

    private void setState(Player p, String s) {
        p.getPersistentDataContainer().set(stateKey, PersistentDataType.STRING, s);
    }

    private int beat(Player p) {
        return p.getPersistentDataContainer().getOrDefault(beatKey, PersistentDataType.INTEGER, 0);
    }

    private void setBeat(Player p, int i) {
        p.getPersistentDataContainer().set(beatKey, PersistentDataType.INTEGER, i);
    }

    private boolean paid(Player p, String id) {
        String raw = p.getPersistentDataContainer().getOrDefault(paidKey, PersistentDataType.STRING, "");
        return ("," + raw + ",").contains("," + id + ",");
    }

    private void markPaid(Player p, String id) {
        String raw = p.getPersistentDataContainer().getOrDefault(paidKey, PersistentDataType.STRING, "");
        p.getPersistentDataContainer().set(paidKey, PersistentDataType.STRING, raw.isEmpty() ? id : raw + "," + id);
    }

    // ------------------------------------------------------------------ flow

    private void start(Player p, boolean fromStart) {
        if (fromStart) {
            setBeat(p, 0);
        }
        setState(p, "active");
        Run old = runs.remove(p.getUniqueId());
        if (old != null) {
            p.hideBossBar(old.bar);
            removeLabel(old);
            removeNpc(old);
        }
        Run r = new Run();
        r.nextAt = System.currentTimeMillis() + 500;
        runs.put(p.getUniqueId(), r);
        p.showBossBar(r.bar);
        chapterFor(p, r, beat(p));
        tick(p);
    }

    private void stop(Player p, String newState) {
        setState(p, newState);
        HELD.remove(p.getUniqueId());
        Run r = runs.remove(p.getUniqueId());
        if (r != null) {
            p.hideBossBar(r.bar);
            removeLabel(r);
            removeNpc(r);
        }
    }

    private void tick(Player p) {
        Run r = runs.get(p.getUniqueId());
        if (r == null || !p.isOnline()) {
            return;
        }
        long now = System.currentTimeMillis();
        int i = beat(p);
        hold(p, i);
        if (i >= SCRIPT.size()) {
            finish(p);
            return;
        }
        Beat b = SCRIPT.get(i);
        follow(p, r);
        if (b instanceof Wait w) {
            waitTick(p, r, i, w, now);
            return;
        }
        if (r.pointAt != null) {
            beam(p, r.pointAt);
        }
        if (now < r.nextAt) {
            return;
        }
        switch (b) {
            case Chapter c -> {
                r.chapter = c.title();
                p.showTitle(Title.title(Msg.parse("<gold><bold>" + c.title()), Msg.parse("<gray>" + c.subtitle()),
                        Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2200), Duration.ofMillis(500))));
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 1f, 1.2f);
                bar(p, r, i, "<white>" + c.title());
                speak(r, "<gold><bold>" + c.title() + "</bold></gold>\n<gray>" + c.subtitle());
                r.nextAt = now + 2600;
            }
            case Say s -> {
                p.sendMessage(Msg.parse(GUIDE + "<gray>" + s.text())
                        .hoverEvent(HoverEvent.showText(Msg.parse("<gray>Click to continue")))
                        .clickEvent(ClickEvent.runCommand("/tutorial next")));
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_AMBIENT, 0.5f, 1.3f);
                bar(p, r, i, "<white>" + r.chapter);
                speak(r, "<white>" + s.text());
                r.nextAt = now + readMillis(s.text());
            }
            case Point pt -> {
                Location at = FactoryModule.landmark(p.getUniqueId(), pt.landmark());
                point(p, r, at, pt.label());
                r.nextAt = now;
            }
            case Clear c -> {
                removeLabel(r);
                r.nextAt = now;
            }
            case Give g -> {
                if (!paid(p, g.id())) {
                    markPaid(p, g.id());
                    give(p, g.cents(), g.tokens());
                    p.sendMessage(Msg.parse(GUIDE + "<gray>" + g.why() + " <green>(+" + (g.cents() > 0 ? Money.compact(g.cents()) : g.tokens() + " tokens") + ")"));
                    p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.1f);
                    r.nextAt = now + 2500;
                } else {
                    r.nextAt = now;
                }
            }
            case Wait w -> {
            }
        }
        setBeat(p, i + 1);
    }

    private void waitTick(Player p, Run r, int i, Wait w, long now) {
        if (now < r.nextAt) {
            return;
        }
        if (r.waitingFor != i) {
            r.waitingFor = i;
            r.events.clear();
            r.baseline = baseline(p, w.id());
            r.remindAt = now + 45_000;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 1.6f);
            p.sendMessage(Msg.parse("<yellow><bold>➜ " + Msg.escape(w.task()) + "</bold></yellow> ")
                    .append(Msg.parse("<gray>[<white><u>" + w.command() + "</u></white>]")
                            .clickEvent(ClickEvent.runCommand(w.command()))
                            .hoverEvent(HoverEvent.showText(Msg.parse("<gray>Click to run " + w.command())))));
            bar(p, r, i, "<yellow>➜ " + Msg.escape(w.task()));
            speak(r, "<yellow><bold>➜ " + Msg.escape(w.task()) + "</bold></yellow>\n<gray>" + w.command());
        }
        if (w.trail() != null) {
            Location to = "home".equals(w.trail()) ? home(p) : FactoryModule.landmark(p.getUniqueId(), w.trail());
            trail(p, to);
        }
        if (r.pointAt != null) {
            beam(p, r.pointAt);
        }
        if (met(p, r, w.id())) {
            r.waitingFor = -1;
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            p.sendActionBar(Msg.parse("<green>✔ " + Msg.escape(w.task())));
            setBeat(p, i + 1);
            r.nextAt = now + 1200;
            return;
        }
        if (now >= r.remindAt) {
            r.remindAt = now + 45_000;
            p.sendMessage(Msg.parse("<gray>Still to do: <yellow>" + Msg.escape(w.task()) + "</yellow> <gray>[<white><u>" + w.command() + "</u></white>]")
                    .clickEvent(ClickEvent.runCommand(w.command())));
        }
    }

    private long baseline(Player p, String id) {
        UUID u = p.getUniqueId();
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotData d = plots == null ? null : plots.data(u);
        return switch (id) {
            case "gen" -> d == null ? 0 : d.genLevel();
            case "speed" -> d == null ? 0 : d.speedLevel();
            case "length" -> d == null ? 0 : d.beltLength();
            case "turret" -> d == null ? 0 : d.turretLevel();
            default -> 0;
        };
    }

    private boolean met(Player p, Run r, String id) {
        UUID u = p.getUniqueId();
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotData d = plots == null ? null : plots.data(u);
        return switch (id) {
            case "factory" -> inOwnFactory(p, plots);
            case "gen" -> d != null && d.genLevel() > r.baseline;
            case "speed" -> d != null && d.speedLevel() > r.baseline;
            case "length" -> d != null && d.beltLength() > r.baseline;
            case "turret" -> d != null && d.turretLevel() > r.baseline;
            case "raidpick" -> FactoryModule.hasRaidPick(p);
            default -> r.events.contains(id);
        };
    }

    private void finish(Player p) {
        boolean first = !paid(p, "final");
        stop(p, "done");
        p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f);
        p.showTitle(Title.title(Msg.parse("<gold><bold>TUTORIAL COMPLETE"), Msg.parse("<gray>Go build the biggest factory on GateMC"),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(600))));
        if (first) {
            markPaid(p, "final");
            give(p, finalCents, finalTokens);
            for (String cmd : finalCommands) {
                String line = cmd.replace("{player}", p.getName());
                Tasks.global.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), line));
            }
            p.sendMessage(Msg.good("Tutorial complete! Starting bonus: " + Msg.white(Money.compact(finalCents)) + ", "
                    + Msg.white(finalTokens + " tokens") + " and crate keys. Your factory now earns money."));
        } else {
            p.sendMessage(Msg.good("Tutorial complete!"));
        }
    }

    // ------------------------------------------------------------------ guide npc

    private @Nullable Location factoryMiddle(Player p) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotService service = plots == null ? null : plots.service();
        Plot plot = service == null ? null : service.plotOf(p.getUniqueId());
        return plot == null ? null : service.view(plot).center();
    }

    private static Location clearSpot(Location middle) {
        org.bukkit.World w = middle.getWorld();
        int cx = middle.getBlockX();
        int cz = middle.getBlockZ();
        int y = middle.getBlockY();
        for (int radius = 0; radius <= 8; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    for (int dy = 0; dy <= 2; dy++) {
                        int x = cx + dx;
                        int z = cz + dz;
                        int by = y + dy;
                        if (w.getBlockAt(x, by - 1, z).isSolid() && w.getBlockAt(x, by, z).isPassable() && w.getBlockAt(x, by + 1, z).isPassable()
                                && !w.getBlockAt(x, by, z).isLiquid()) {
                            return new Location(w, x + 0.5, by, z + 0.5);
                        }
                    }
                }
            }
        }
        return middle.clone();
    }

    private void follow(Player p, Run r) {
        Mannequin npc = r.npc;
        if (npc == null) {
            Location middle = r.spawning ? null : factoryMiddle(p);
            if (middle != null && middle.getWorld() == p.getWorld() && middle.distanceSquared(p.getLocation()) < 100 * 100) {
                spawnNpc(p, r);
            }
            return;
        }
        Location viewer = p.getLocation();
        Tasks.onEntity(npc, () -> {
            if (!npc.isValid()) {
                r.npc = null;
                return;
            }
            Location at = npc.getLocation();
            if (at.getWorld() != viewer.getWorld() || at.distanceSquared(viewer) > 64 * 64) {
                return;
            }
            Vector look = viewer.toVector().subtract(at.toVector()).setY(0);
            if (look.lengthSquared() < 1.0E-4) {
                return;
            }
            Location face = at.clone();
            face.setDirection(look);
            face.setPitch(0);
            float diff = Math.abs(((face.getYaw() - at.getYaw()) % 360 + 540) % 360 - 180);
            if (diff > 6) {
                npc.setRotation(face.getYaw(), 0);
            }
        });
    }

    private void spawnNpc(Player p, Run r) {
        Location middle = factoryMiddle(p);
        if (middle == null) {
            return;
        }
        r.spawning = true;
        UUID viewer = p.getUniqueId();
        Tasks.region.run(middle, () -> {
            Location at = clearSpot(middle);
            Mannequin m = at.getWorld().spawn(at, Mannequin.class, n -> {
                n.setPersistent(false);
                n.setVisibleByDefault(false);
                n.setImmovable(true);
                n.setAI(false);
                n.setGravity(false);
                n.setInvulnerable(true);
                n.setSilent(true);
                n.setCollidable(false);
                n.setCustomNameVisible(false);
                n.addScoreboardTag("genscore_tutorial");
                n.setProfile(ResolvableProfile.resolvableProfile()
                        .skinPatch(patch -> patch.body(net.kyori.adventure.key.Key.key("genscore", "entity/npc/guide"))
                                .model(org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC))
                        .build());
            });
            TextDisplay bubble = at.getWorld().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setLineWidth(190);
                d.setShadowed(true);
                d.setBackgroundColor(Color.fromARGB(150, 10, 10, 10));
                d.setTransformation(new Transformation(new Vector3f(0, 0.55f, 0), new Quaternionf(), new Vector3f(0.9f, 0.9f, 0.9f), new Quaternionf()));
                d.text(Msg.parse("<green><bold>Guide</bold></green>"));
                d.addScoreboardTag("genscore_tutorial");
            });
            m.addPassenger(bubble);
            if (runs.get(viewer) != r) {
                bubble.remove();
                m.remove();
                return;
            }
            r.npc = m;
            r.speech = bubble;
            r.spawning = false;
            Player v = Bukkit.getPlayer(viewer);
            if (v != null) {
                Tasks.entity.run(v, () -> {
                    v.showEntity(plugin, m);
                    v.showEntity(plugin, bubble);
                    startTyper(v, r);
                });
            }
        });
    }

    private void speak(Run r, String mini) {
        r.finalText = mini;
        r.typing = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(Msg.parse(mini));
        r.typed = 0;
    }

    private void startTyper(Player p, Run r) {
        if (r.typer != null) {
            return;
        }
        r.typer = Tasks.entity.timer(p, t -> {
            if (runs.get(p.getUniqueId()) != r) {
                t.cancel();
                return;
            }
            TextDisplay bubble = r.speech;
            String full = r.typing;
            if (bubble == null || full.isEmpty() || r.typed > full.length()) {
                return;
            }
            int n = Math.min(full.length(), r.typed + 2);
            boolean done = n >= full.length();
            r.typed = done ? full.length() + 1 : n;
            Component shown = done
                    ? Msg.parse("<green><bold>Guide</bold></green>\n" + r.finalText)
                    : Msg.parse("<green><bold>Guide</bold></green>\n<white>" + Msg.escape(full.substring(0, n)) + "<gray>▌");
            Tasks.onEntity(bubble, () -> bubble.text(shown));
            if (n % 6 == 0 || done) {
                p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.25f, 1.8f);
            }
        }, 2, 2);
    }

    private void removeNpc(Run r) {
        ScheduledTask t = r.typer;
        r.typer = null;
        if (t != null) {
            t.cancel();
        }
        TextDisplay s = r.speech;
        r.speech = null;
        if (s != null) {
            Tasks.onEntity(s, s::remove);
        }
        Mannequin m = r.npc;
        r.npc = null;
        if (m != null) {
            Tasks.onEntity(m, m::remove);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static long readMillis(String text) {
        String plain = text.replaceAll("<[^>]+>", "");
        return Math.max(3200, Math.min(10_000, plain.length() * 33L + 2200));
    }

    private void bar(Player p, Run r, int index, String text) {
        r.bar.name(Msg.parse("<green><bold>Tutorial</bold></green> <dark_gray>»</dark_gray> " + text));
        r.bar.progress(Math.min(1f, (float) index / SCRIPT.size()));
        p.showBossBar(r.bar);
    }

    private void chapterFor(Player p, Run r, int index) {
        for (int i = Math.min(index, SCRIPT.size() - 1); i >= 0; i--) {
            if (SCRIPT.get(i) instanceof Chapter c) {
                r.chapter = c.title();
                bar(p, r, index, "<white>" + c.title());
                return;
            }
        }
    }

    private void give(Player p, long cents, int tokens) {
        UUID u = p.getUniqueId();
        if (cents > 0) {
            EconomyModule eco = plugin.modules().get(EconomyModule.class);
            if (eco != null && eco.service() != null) {
                eco.service().deposit(u, cents, "tutorial");
            }
        }
        if (tokens > 0) {
            PlotsModule plots = plugin.modules().get(PlotsModule.class);
            GensPlayer g = plots == null ? null : plots.gensPlayer(u);
            if (g != null) {
                g.addTokens(tokens);
                plots.saveSoon(u);
            }
        }
    }

    private long balance(UUID u) {
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        return eco == null || eco.service() == null ? 0 : eco.service().balance(u);
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

    private @Nullable Location home(Player p) {
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
        for (double d = 1.2; d < Math.min(length, 16); d += 0.6) {
            p.spawnParticle(Particle.DUST, from.clone().add(dir.clone().multiply(d)), 1, 0, 0, 0, 0, dust);
        }
    }

    private static void beam(Player p, Location at) {
        if (at.getWorld() != p.getWorld() || at.distanceSquared(p.getLocation()) > 80 * 80) {
            return;
        }
        Particle.DustOptions dust = new Particle.DustOptions(Color.fromRGB(255, 210, 60), 1.3f);
        for (double y = 0.4; y < 4.5; y += 0.35) {
            p.spawnParticle(Particle.DUST, at.clone().add(0, y, 0), 1, 0.05, 0, 0.05, 0, dust);
        }
    }

    private void point(Player p, Run r, @Nullable Location at, String label) {
        removeLabel(r);
        if (at == null) {
            return;
        }
        r.pointAt = at.clone();
        Location spot = at.clone().add(0, 2.2, 0);
        UUID viewer = p.getUniqueId();
        Tasks.region.run(spot, () -> {
            TextDisplay d = spot.getWorld().spawn(spot, TextDisplay.class, e -> {
                e.setPersistent(false);
                e.setVisibleByDefault(false);
                e.setBillboard(Display.Billboard.CENTER);
                e.text(Msg.parse("<gold><bold>▼ " + label + " ▼"));
                e.setSeeThrough(true);
                e.setShadowed(true);
                e.addScoreboardTag("genscore_tutorial");
            });
            Run now = runs.get(viewer);
            if (now != r || r.pointAt == null) {
                d.remove();
                return;
            }
            r.label = d;
            Player v = Bukkit.getPlayer(viewer);
            if (v != null) {
                Tasks.entity.run(v, () -> v.showEntity(plugin, d));
            }
        });
    }

    private void removeLabel(Run r) {
        r.pointAt = null;
        Entity e = r.label;
        r.label = null;
        if (e != null) {
            Tasks.onEntity(e, e::remove);
        }
    }

    // ------------------------------------------------------------------ commands

    private void command(Player p) {
        String st = state(p);
        Run r = runs.get(p.getUniqueId());
        if (r != null) {
            int i = beat(p);
            if (i < SCRIPT.size() && SCRIPT.get(i) instanceof Wait w) {
                r.waitingFor = -1;
                r.nextAt = 0;
                p.sendMessage(Msg.neutral("Current step: " + Msg.white(w.task()) + ". " + Msg.white("/tutorial skip") + " stops the tutorial."));
            } else {
                p.sendMessage(Msg.neutral("The tutorial is running. " + Msg.white("/tutorial skip") + " stops it, " + Msg.white("/tutorial restart") + " starts over."));
            }
            return;
        }
        boolean resume = "off".equals(st) && beat(p) > 0 && beat(p) < SCRIPT.size();
        start(p, !resume);
        if (resume) {
            p.sendMessage(Msg.neutral("Picking up where you left off. " + Msg.white("/tutorial restart") + " starts from the beginning."));
        }
    }

    @Command({"tutorial", "guide"})
    @Description("Play through the GateMC tutorial")
    public void tutorialCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> command(p));
    }

    @Command("tutorial restart")
    @Description("Start the tutorial from the beginning")
    public void tutorialRestart(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> start(p, true));
    }

    @Command("tutorial next")
    @Description("Skip ahead to the next tutorial message")
    public void tutorialNext(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> {
            Run r = runs.get(p.getUniqueId());
            if (r != null) {
                r.nextAt = 0;
                tick(p);
            }
        });
    }

    @Command("tutorial skip")
    @Description("Stop the tutorial")
    public void tutorialSkip(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> {
            stop(p, "off");
            p.sendMessage(Msg.neutral("Tutorial stopped. " + Msg.white("/tutorial") + " picks it up again."));
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
        String st = pdc.getOrDefault(stateKey, PersistentDataType.STRING, "");
        if ("active".equals(st)) {
            Tasks.entity.later(p, () -> {
                if (p.isOnline() && "active".equals(state(p))) {
                    start(p, false);
                    p.sendMessage(Msg.neutral("Welcome back! Your tutorial picks up where you left off. " + Msg.white("/tutorial skip") + " to stop."));
                }
            }, 60L);
            return;
        }
        if (!st.isEmpty()) {
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
        HELD.remove(event.getPlayer().getUniqueId());
        Run r = runs.remove(event.getPlayer().getUniqueId());
        if (r != null) {
            removeLabel(r);
            removeNpc(r);
        }
    }
}
