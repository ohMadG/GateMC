package me.Evan52098.genscore.factory;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.plots.FactoryView;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * One running factory: its garage-door gate, its belt (simulated as data), and the display entities that show them.
 *
 * <p>Folia: everything here runs on the factory's region thread through one timer ({@link #tick}). Other threads only
 * call {@link #requestClose()} / {@link #requestOpen()} (which set a volatile) and read the volatile summary fields.
 *
 * <p>The gate is not made of blocks: the door is display entities, and {@link FactoryModule} stops everyone but the
 * owner from stepping into the doorway or the fortress while it is closed. So the owner walks straight through their
 * own closed gate. Click boxes ({@link Interaction} entities) fill the doorway and a label floats in it.
 *
 * <p>Roadmap rule 1: money comes from the simulation, never from entities. The item displays are only a picture of
 * the simulated items, drawn while a player is near (rule 6), and they are not saved with the world.
 */
final class Factory {

    static final int PERIOD_TICKS = 2;
    private static final double DT = PERIOD_TICKS / 20.0;
    private static final String TAG = "genscore_factory";

    private final FactoryModule module;
    final FactoryView view;
    final BeltPath path;
    private final Set<Long> gateCells = new HashSet<>();
    private final Set<Long> buttonCells = new HashSet<>();
    private final Location base;
    private ScheduledTask task;
    private volatile boolean stopped;
    private volatile boolean wipe;

    // ---- gate (closedUntil is written by any thread, everything else on the region thread)
    private volatile long closedUntil;
    private volatile boolean gateShut;           // what the world shows now
    private final List<BlockDisplay> slats = new ArrayList<>();
    private final List<int[]> slatParts = new ArrayList<>();
    private final List<Interaction> clickBoxes = new ArrayList<>();
    private TextDisplay gateLabel;
    private BossBar bar;
    private boolean barShown;

    // ---- belt simulation (region thread only)
    /** One item on the belt. It keeps the same display entity from the gen to the sell point, so it never jumps. */
    private static final class BeltItem {
        double pos;
        final Material item;
        final long baseCents; // what this one item is worth before the belt bonus (rule 1: money from data)
        final int special;    // NORMAL, TOKEN, or CACHE + rarity: a drop that pays out at the sell point
        final double gold;
        int display = -1;
        boolean placed; // its display has been put in place once (the first move is not animated)

        BeltItem(double pos, Material item, long baseCents, int special, double gold) {
            this.pos = pos;
            this.item = item;
            this.baseCents = baseCents;
            this.special = special;
            this.gold = gold;
        }

        boolean golden() {
            return gold > 1;
        }

        double worth() {
            return golden() ? gold : 1;
        }

        String look() {
            if (special != NORMAL) {
                return special == TOKEN ? "token" : "cache_" + GensPlayerRarity.name(special - CACHE);
            }
            return golden() ? "golden" : item.name();
        }
    }

    private static ItemStack goldenStack() {
        ItemStack stack = new ItemStack(Material.GOLD_INGOT);
        var meta = stack.getItemMeta();
        meta.setEnchantmentGlintOverride(true);
        stack.setItemMeta(meta);
        return stack;
    }

    static final int NORMAL = -1;
    static final int TOKEN = 0;
    static final int CACHE = 1; // + rarity

    /** Lower-case rarity ids, for model names. */
    static final class GensPlayerRarity {
        static String name(int rarity) {
            return me.Evan52098.genscore.plots.GensPlayer.RARITIES[Math.max(0, Math.min(rarity, 3))];
        }
    }

    // ---- raids (region thread)
    private int[] health;          // per segment; -1 = full
    private long[] brokenAt;       // per segment; 0 = working
    private long[] lastHit;        // per segment
    private boolean segmentsDirty;
    private final Map<UUID, Integer> raidCounts = new HashMap<>();
    private final Map<UUID, Long> raidHitAt = new HashMap<>();
    private long lastRaidHit;

    // ---- defences (region thread)
    private final List<ItemDisplay> turretDisplays = new ArrayList<>();
    private static final float TURRET_SCALE = 2.5f;
    private static final Vector3f TURRET_LIFT = new Vector3f(0, (TURRET_SCALE - 1) / 2, 0);
    private final Map<Integer, Long> turretReady = new HashMap<>();
    private final Set<UUID> intruders = new HashSet<>();
    private int trapPulse;
    private int turretTick;

    // ---- effects (region thread)
    private boolean emitted;        // the gen made an item this tick: pulse the machine
    private boolean machinePulsed;  // the machine is scaled up and must settle back
    private long popupCents;        // sold this second, shown as a floating "+$X" over the sell point
    private int salesThisTick;
    private int goldenThisTick;

    private final java.util.Random random = new java.util.Random();
    private final List<String> shownItem = new ArrayList<>(); // what each item display shows now

    /** Where a new item appears: inside the gen's chute, just behind the start of the belt. */
    private static final double SPAWN_POS = -0.55;
    private final ArrayDeque<BeltItem> items = new ArrayDeque<>(); // first = oldest = furthest along
    private final ArrayDeque<Integer> freeDisplays = new ArrayDeque<>();
    private int drawnTier = -1;
    private ItemDisplay machineDisplay;
    private TextDisplay machineLabel;
    private boolean machinePlaced;
    private double genTimer;
    private long pendingCents;
    private int depositCountdown;
    private final ArrayDeque<long[]> earnings = new ArrayDeque<>(); // [millis, cents] for the last minute
    volatile long incomePerMinute;
    volatile int itemsOnBelt;
    volatile boolean ownerOnline;

    // ---- visuals (region thread only)
    private boolean drawn;
    private int drawnLength = -1;
    private final Map<Integer, ItemDisplay> segmentDisplays = new HashMap<>();
    private final Map<Integer, ItemDisplay> wreckDisplays = new HashMap<>();
    private long soldSinceDeposit;
    private volatile UUID siphonTo;
    private long siphoned;
    private TextDisplay ownerMarker;
    private TextDisplay nameplate;
    private final List<Display> machineParts = new ArrayList<>();
    private int drawnMachines = -1;
    private final long[] machinePuff = new long[8];
    private final long[] machineSpark = new long[8];
    private long lastPulse;
    private long lastSparkle;
    private String plateColor = "<white>";
    private String plateName = "?";
    private int plateTick;
    private int bestBracket = -1;
    private TextDisplay brokenLabel;
    private final List<ItemDisplay> itemDisplays = new ArrayList<>();
    private BlockDisplay sellDisplay;
    private TextDisplay sellLabel;
    private BlockDisplay genDisplay;
    private int viewCheck;
    private int secondCounter;

    Factory(FactoryModule module, FactoryView view) {
        this.module = module;
        this.view = view;
        this.path = BeltPath.of(view.marker("belt"), view.marker("pad"));
        for (BlockVector v : view.marker("gate")) {
            gateCells.add(BeltPath.key(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
        }
        for (BlockVector v : view.marker("gate_button")) {
            buttonCells.add(BeltPath.key(v.getBlockX(), v.getBlockY(), v.getBlockZ()));
        }
        this.base = new Location(view.world(), view.minX() + view.size() / 2.0, view.floorY() + 1, view.minZ() + view.size() / 2.0);
    }

    // ------------------------------------------------------------------ lifecycle

    void start() {
        task = Tasks.region.timer(base, t -> {
            if (stopped) {
                t.cancel();
                return;
            }
            try {
                tick();
            } catch (RuntimeException e) {
                module.plugin().getLogger().log(java.util.logging.Level.SEVERE, "Factory " + view.slot() + " tick failed", e);
            }
        }, 1, PERIOD_TICKS);
    }

    /** Stops the factory. Pays out what it earned. Entities and gate blocks go with the plot clear. */
    void stop() {
        stopped = true;
        long cents = wipe ? 0 : pendingCents;
        pendingCents = 0;
        if (cents > 0) {
            Tasks.async.run(() -> module.pay(view.owner(), cents));
        }
        TextDisplay label = gateLabel;
        if (label != null && module.plugin().isEnabled()) {
            try {
                Tasks.entity.run(label, label::remove);
            } catch (RuntimeException ignored) {
            }
        }
        BossBar b = bar;
        Player owner = Bukkit.getPlayer(view.owner());
        if (b != null && owner != null) {
            Tasks.entity.run(owner, () -> owner.hideBossBar(b));
        }
        if (module.plugin().isEnabled()) {
            Tasks.region.run(base, this::removeDisplays);
        }
    }

    private void removeDisplays() {
        undraw();
        for (BlockDisplay d : slats) {
            if (d.isValid()) {
                d.remove();
            }
        }
        slats.clear();
        slatParts.clear();
        for (Interaction i : clickBoxes) {
            if (i.isValid()) {
                i.remove();
            }
        }
        clickBoxes.clear();
        for (Entity e : new Entity[]{ownerMarker, nameplate, brokenLabel, gateLabel}) {
            if (e != null && e.isValid()) {
                e.remove();
            }
        }
    }

    // ------------------------------------------------------------------ queries (any thread)

    boolean gateClosed() {
        return System.currentTimeMillis() < closedUntil;
    }

    static final int CLOSE_COOLDOWN = 5;
    static final int REUP_WINDOW = 5;
    private volatile long openedAt;

    long closeCooldownLeft() {
        if (gateClosed()) {
            return 0;
        }
        long opened = Math.max(openedAt, closedUntil);
        return Math.max(0, (opened + CLOSE_COOLDOWN * 1000L - System.currentTimeMillis() + 999) / 1000);
    }

    boolean canReup() {
        return gateSecondsLeft() <= REUP_WINDOW;
    }

    String gateStatus() {
        if (gateClosed()) {
            long left = gateSecondsLeft();
            return left <= REUP_WINDOW
                    ? "<yellow><bold>RE-UP NOW</bold> " + left + "s</yellow>"
                    : "<green>Gate closed " + left + "s</green> <gray>(re-up in " + (left - REUP_WINDOW) + "s)</gray>";
        }
        long cool = closeCooldownLeft();
        return cool > 0 ? "<red>Gate OPEN</red> <gray>close in " + cool + "s</gray>" : "<red><bold>Gate OPEN</bold></red>";
    }

    long gateSecondsLeft() {
        return Math.max(0, (closedUntil - System.currentTimeMillis() + 999) / 1000);
    }

    boolean isGateCell(int x, int y, int z) {
        return gateCells.contains(BeltPath.key(x, y, z));
    }

    boolean isButton(int x, int y, int z) {
        return buttonCells.contains(BeltPath.key(x, y, z));
    }

    boolean hasGate() {
        return !gateCells.isEmpty();
    }

    /** Owner closes the gate (or closes it again for a fresh timer). Applied on the next tick. */
    void requestClose() {
        closedUntil = System.currentTimeMillis() + module.gateSeconds(view.owner()) * 1000L;
    }

    void closeFor(int seconds) {
        closedUntil = System.currentTimeMillis() + seconds * 1000L;
    }

    void jamOpen(int seconds) {
        closedUntil = 0;
        openedAt = System.currentTimeMillis() + Math.max(0, seconds - CLOSE_COOLDOWN) * 1000L;
    }

    /** Owner opens the gate early. */
    void requestOpen() {
        if (gateClosed()) {
            openedAt = System.currentTimeMillis();
        }
        closedUntil = 0;
    }

    int activeLength() {
        PlotData data = module.plotData(view.owner());
        int length = data == null ? 0 : data.beltLength();
        return Math.max(0, Math.min(length, path.size()));
    }

    double speed() {
        PlotData data = module.plotData(view.owner());
        return module.settings().speed(data == null ? 0 : data.speedLevel());
    }

    // ------------------------------------------------------------------ tick (region thread)

    void wipeBelt() {
        wipe = true;
        incomePerMinute = 0;
    }

    private void tick() {
        FactorySettings s = module.settings();
        if (wipe) {
            wipe = false;
            for (BeltItem item : items) {
                releaseDisplay(item);
            }
            items.clear();
            pendingCents = 0;
            soldSinceDeposit = 0;
            earnings.clear();
            incomePerMinute = 0;
        }
        if (!machinePlaced) {
            machinePlaced = true;
            placeMachineBlock();
        }
        Player owner = Bukkit.getPlayer(view.owner());
        ownerOnline = owner != null;
        tickGate(owner);
        if (ownerOnline) {
            simulate(s);
        }
        if (++viewCheck >= 10) {
            viewCheck = 0;
            boolean near = !view.world().getNearbyPlayers(base, s.viewDistance()).isEmpty();
            if (near != drawn) {
                if (near) {
                    draw();
                } else {
                    undraw();
                }
            }
        }
        if (drawn) {
            redraw(s);
        }
        if (++turretTick >= 2) {
            turretTick = 0;
            turrets();
        }
        if (++secondCounter >= 20 / PERIOD_TICKS) {
            secondCounter = 0;
            everySecond(owner, s);
            updateGateLabel();
        }
    }

    // ---- gate

    private void tickGate(Player owner) {
        if (!hasGate()) {
            return;
        }
        boolean shut = gateClosed();
        if (shut == gateShut && !slats.isEmpty()) {
            return;
        }
        if (slats.isEmpty()) {
            spawnDoor(shut);
        }
        if (shut != gateShut) {
            gateShut = shut;
            if (shut) {
                endSiphon(true);
            }
            animateDoor(shut);
            updateGateLabel();
            Location at = gateCenter();
            view.world().playSound(at, shut ? Sound.BLOCK_PISTON_CONTRACT : Sound.BLOCK_PISTON_EXTEND, 1f, 0.6f);
            if (shut) {
                pushOutIntruders();
            }
            if (owner != null) {
                Component msg = shut
                        ? Msg.good("Gate closed for " + Msg.white(module.settings().gateCloseSeconds() + "s") + ". Close it again before it opens.")
                        : Msg.bad("Your gate is " + Msg.white("OPEN") + ". Anyone can walk in. Press the gate button or use " + Msg.white("/gate") + ".");
                Tasks.entity.run(owner, () -> {
                    owner.sendMessage(msg);
                    if (!shut) {
                        owner.playSound(owner.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1f, 0.5f);
                    }
                });
            }
        }
    }

    @org.jetbrains.annotations.Nullable Location landmark(String which) {
        World w = view.world();
        return switch (which) {
            case "gen" -> path.size() == 0 ? null : path.at(0).toLocation(w).add(0, 0.6, 0);
            case "belt" -> path.size() == 0 ? null : path.at(Math.max(0, activeLength() / 2.0)).toLocation(w).add(0, 0.6, 0);
            case "sell" -> path.size() == 0 ? null : path.at(Math.max(0, activeLength() - 1)).toLocation(w).add(0, 0.6, 0);
            case "gate" -> hasGate() ? gateCenter().add(0, 1, 0) : null;
            case "button" -> view.marker("gate_button").isEmpty() ? null : view.marker("gate_button").get(0).toLocation(w).add(0.5, 0.5, 0.5);
            case "turret" -> view.marker("turret").isEmpty() ? null : view.marker("turret").get(0).toLocation(w).add(0.5, 1.0, 0.5);
            default -> null;
        };
    }

    private Location gateCenter() {
        double x = 0;
        double y = Double.MAX_VALUE;
        double z = 0;
        List<BlockVector> cells = view.marker("gate");
        for (BlockVector v : cells) {
            x += v.getBlockX() + 0.5;
            z += v.getBlockZ() + 0.5;
            y = Math.min(y, v.getBlockY());
        }
        return new Location(view.world(), x / cells.size(), y, z / cells.size());
    }

    /** Just outside the gate, on the road, facing away from the factory. */
    Location outsideGate() {
        int[] f = view.facing();
        Location c = gateCenter().add(f[0] * 2.5, 0, f[1] * 2.5);
        c.setYaw(f[0] == 1 ? -90 : f[0] == -1 ? 90 : f[1] == 1 ? 0 : 180);
        return c;
    }

    private void setBarriers(boolean shut) {
        for (BlockVector v : view.marker("gate")) {
            Block b = view.world().getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ());
            if (shut && b.getType().isAir()) {
                b.setType(Material.BARRIER, false);
            } else if (!shut && b.getType() == Material.BARRIER) {
                b.setType(Material.AIR, false);
            }
        }
    }

    /** Players other than the owner inside the walls (or in the doorway) when the gate shuts are put outside it. */
    private void pushOutIntruders() {
        Location out = outsideGate();
        for (Player p : view.world().getNearbyPlayers(base, view.size())) {
            if (p.getUniqueId().equals(view.owner()) || module.bypasses(p) || module.trusted(view.owner(), p)) {
                continue;
            }
            Location l = p.getLocation();
            boolean inDoor = isGateCell(l.getBlockX(), l.getBlockY(), l.getBlockZ());
            if (view.inside(l.getBlockX(), l.getBlockZ()) || inDoor) {
                Tasks.entity.run(p, () -> {
                    p.teleportAsync(out, PlayerTeleportEvent.TeleportCause.PLUGIN);
                    p.sendMessage(Msg.bad("The gate closed. You were moved outside."));
                });
            }
        }
    }

    // ---- garage door: one slat per row of the doorway, rolling up into the housing above

    private int[] doorBox() {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockVector v : view.marker("gate")) {
            minX = Math.min(minX, v.getBlockX());
            maxX = Math.max(maxX, v.getBlockX());
            minY = Math.min(minY, v.getBlockY());
            maxY = Math.max(maxY, v.getBlockY());
            minZ = Math.min(minZ, v.getBlockZ());
            maxZ = Math.max(maxZ, v.getBlockZ());
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }

    private static final int PANEL = 0;
    private static final int RIDGE = 1;
    private static final int STRIPE = 2;
    private static final float STRIPE_WIDTH = 0.5f;

    private void spawnDoor(boolean shut) {
        int[] b = doorBox();
        int rows = b[4] - b[1] + 1;
        Location at = new Location(view.world(), b[0], b[1], b[2]);
        BlockData panel = me.Evan52098.genscore.plots.PlotStyles.door(view.owner(), module.settings().doorBlock()).createBlockData();
        BlockData ridge = Material.GRAY_CONCRETE.createBlockData();
        BlockData yellow = Material.YELLOW_CONCRETE.createBlockData();
        BlockData black = Material.BLACK_CONCRETE.createBlockData();
        for (int k = 0; k < rows; k++) {
            addSlat(at, panel, k, PANEL, 0, shut);
            if (k > 0) {
                addSlat(at, ridge, k, RIDGE, 0, shut);
            }
        }
        int stripes = (int) Math.ceil(doorWidth(b) / STRIPE_WIDTH);
        for (int i = 0; i < stripes; i++) {
            addSlat(at, i % 2 == 0 ? yellow : black, 0, STRIPE, i, shut);
        }
        gateShut = shut;
        setBarriers(false); // gates from older builds used barrier blocks; clear any left over
        // Click boxes: one per doorway column, as tall as the doorway, so any click in the gate lands on one.
        int height = b[4] - b[1] + 1;
        for (BlockVector v : view.marker("gate")) {
            if (v.getBlockY() != b[1]) {
                continue;
            }
            Location foot = new Location(view.world(), v.getBlockX() + 0.5, v.getBlockY(), v.getBlockZ() + 0.5);
            clickBoxes.add(view.world().spawn(foot, Interaction.class, i -> {
                i.setPersistent(false);
                i.addScoreboardTag(TAG);
                i.setInteractionWidth(1.0f);
                i.setInteractionHeight(height);
                i.setResponsive(true);
            }));
        }
        int[] f = view.facing();
        Location mid = gateCenter().add(f[0] * 0.9, height * 0.55, f[1] * 0.9);
        for (Entity old : view.world().getNearbyEntities(mid, 1.5, 2.5, 1.5,
                e -> e instanceof TextDisplay && e.getScoreboardTags().contains(TAG))) {
            old.remove();
        }
        gateLabel = view.world().spawn(mid, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setSeeThrough(false);
            d.setShadowed(true);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(150, 0, 0, 0));
            d.setLineWidth(220);
        });
        updateGateLabel();
        String ownerName = String.valueOf(Bukkit.getOfflinePlayer(view.owner()).getName());
        String color = switch (me.Evan52098.genscore.plots.PlotStyles.accent(view.owner())) {
            case "orange" -> "<#FF8A1F>";
            case "magenta" -> "<light_purple>";
            case "light_blue" -> "<aqua>";
            case "yellow" -> "<yellow>";
            case "lime" -> "<green>";
            case "pink" -> "<#FF9AD5>";
            case "gray", "light_gray" -> "<gray>";
            case "cyan" -> "<dark_aqua>";
            case "purple" -> "<dark_purple>";
            case "blue" -> "<blue>";
            case "brown" -> "<#B07040>";
            case "green" -> "<dark_green>";
            case "red" -> "<red>";
            case "black" -> "<dark_gray>";
            default -> "<white>";
        };
        plateColor = color;
        plateName = ownerName;
        Location plate = gateCenter().add(0, b[4] - b[1] + 4.4, 0);
        for (Entity old : view.world().getNearbyEntities(plate, 1.5, 1.5, 1.5,
                e -> e instanceof TextDisplay && e.getScoreboardTags().contains(TAG + "_plate"))) {
            old.remove();
        }
        nameplate = view.world().spawn(plate, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.addScoreboardTag(TAG + "_plate");
            d.setLineWidth(400);
            d.setBillboard(Display.Billboard.VERTICAL);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(140, 10, 10, 14));
            d.setShadowed(true);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.25f, 1.25f, 1.25f), new Quaternionf()));
            d.text(plateText());
        });
    }

    private Component plateText() {
        PlotData data = module.plotData(view.owner());
        int tier = (data == null ? 0 : data.genLevel()) + 1;
        int turrets = data == null ? 0 : data.turretLevel();
        int armour = data == null ? 0 : data.armorLevel();
        StringBuilder sb = new StringBuilder();
        sb.append(plateColor).append("<bold>").append(Msg.escape(plateName.toUpperCase(java.util.Locale.ROOT))).append("'S FACTORY</bold>\n");
        sb.append("<gray>Gen</gray> <white>Tier ").append(tier).append("</white> <dark_gray>•</dark_gray> <gold>")
                .append(Money.compact(incomePerMinute)).append("/min</gold>\n");
        sb.append("<gray>Belt</gray> <white>").append(activeLength()).append("/").append(path.size()).append("</white>")
                .append(" <dark_gray>•</dark_gray> <gray>Turrets</gray> <white>").append(turrets).append("</white>")
                .append(" <dark_gray>•</dark_gray> <gray>Armour</gray> <white>").append(armour).append("</white>\n");
        long shield = shieldLeftSeconds();
        if (shield > 0) {
            sb.append("<aqua>🛡 Shielded ").append(shield >= 60 ? shield / 60 + "m " + shield % 60 + "s" : shield + "s").append("</aqua>");
        } else if (!hasGate()) {
            sb.append("<gray>No gate</gray>");
        } else if (gateClosed()) {
            sb.append("<green>■ Gate closed</green> <white>").append(gateSecondsLeft()).append("s</white>");
        } else {
            sb.append("<red><bold>■ Gate OPEN</bold></red> <gray>- raidable</gray>");
        }
        UUID thief = siphonTo;
        if (thief != null) {
            String name = Bukkit.getOfflinePlayer(thief).getName();
            sb.append("\n<red><bold>⚠ Being raided by ").append(Msg.escape(name == null ? "?" : name)).append("</bold></red>");
        }
        return Msg.parse(sb.toString());
    }

    /** The floating label in the doorway: state, time left, and what a click does. */
    private void updateGateLabel() {
        if (gateLabel == null || !gateLabel.isValid()) {
            return;
        }
        String name = Bukkit.getOfflinePlayer(view.owner()).getName();
        String who = "<gray>" + Msg.escape(name == null ? "?" : name) + "'s gate</gray>";
        gateLabel.text(gateClosed()
                ? Msg.parse(who + "\n<green><bold>CLOSED</bold></green> <white>" + gateSecondsLeft() + "s</white>\n"
                + (canReup() ? "<yellow>Right-click: re-up now!</yellow>" : "<gray>Re-up in the last " + REUP_WINDOW + "s</gray>")
                + "\n<gray>Sneak + right-click: open</gray>")
                : Msg.parse(who + "\n<red><bold>OPEN</bold></red>\n" + (closeCooldownLeft() > 0
                ? "<gray>Can close in " + closeCooldownLeft() + "s</gray>"
                : "<gray>Right-click to close (" + module.settings().gateCloseSeconds() + "s)</gray>")));
    }

    /** True if this entity is one of the gate's click boxes. */
    boolean isClickBox(Entity e) {
        return e instanceof Interaction && clickBoxes.contains(e);
    }

    private void addSlat(Location at, BlockData data, int row, int kind, int index, boolean shut) {
        slats.add(view.world().spawn(at, BlockDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBlock(data);
            d.setTransformation(slatTransform(row, kind, index, shut));
        }));
        slatParts.add(new int[]{row, kind, index});
    }

    private boolean doorAlongX(int[] b) {
        return b[3] > b[0] || b[5] == b[2] && b[3] == b[0] && view.facing()[1] != 0;
    }

    private float doorWidth(int[] b) {
        return doorAlongX(b) ? b[3] - b[0] + 1 : b[5] - b[2] + 1;
    }

    private Transformation slatTransform(int row, int kind, int index, boolean shut) {
        int[] b = doorBox();
        boolean alongX = doorAlongX(b);
        float width = doorWidth(b) - 0.04f;
        float height = b[4] - b[1] + 1;
        float thick = 0.22f;
        float from = 0.02f;
        float span = width;
        if (kind == STRIPE) {
            from += index * STRIPE_WIDTH;
            span = Math.min(STRIPE_WIDTH, width - index * STRIPE_WIDTH);
        }
        float y;
        float h;
        float depth;
        if (shut) {
            switch (kind) {
                case RIDGE -> { y = row - 0.05f; h = 0.1f; depth = thick + 0.08f; }
                case STRIPE -> { y = 0.02f; h = 0.2f; depth = thick + 0.06f; }
                default -> { y = row; h = 1.0f; depth = thick; }
            }
        } else {
            float roll = height + 0.05f + row * 0.03f;
            if (kind == PANEL) {
                y = roll; h = 0.12f; depth = thick;
            } else {
                y = roll + 0.01f; h = 0.1f; depth = thick - 0.02f;
                if (kind == STRIPE) {
                    span = Math.max(0.01f, Math.min(span, width - from + 0.02f));
                }
            }
        }
        float inset = 0.5f - depth / 2;
        Vector3f translation = alongX ? new Vector3f(from, y, inset) : new Vector3f(inset, y, from);
        Vector3f scale = alongX ? new Vector3f(span, h, depth) : new Vector3f(depth, h, span);
        return new Transformation(translation, new Quaternionf(), scale, new Quaternionf());
    }

    private void animateDoor(boolean shut) {
        Location g = gateCenter();
        if (shut) {
            Tasks.region.later(g, () -> {
                view.world().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, g.clone().add(0, 0.1, 0), 30, 1.6, 0.05, 0.3, 0.15);
                view.world().spawnParticle(org.bukkit.Particle.BLOCK, g.clone().add(0, 0.1, 0), 20, 1.6, 0.05, 0.3, 0,
                        module.settings().doorBlock().createBlockData());
                view.world().playSound(g, Sound.BLOCK_IRON_DOOR_CLOSE, 1f, 0.5f);
                view.world().playSound(g, Sound.BLOCK_ANVIL_LAND, 0.4f, 0.6f);
            }, 22);
        } else {
            view.world().spawnParticle(org.bukkit.Particle.CLOUD, g.clone().add(0, 3, 0), 12, 1.5, 0.2, 0.3, 0.02);
            view.world().playSound(g, Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.6f);
        }
        int rows = doorBox()[4] - doorBox()[1] + 1;
        for (int k = 0; k < slats.size(); k++) {
            BlockDisplay d = slats.get(k);
            if (!d.isValid()) {
                continue;
            }
            int[] part = slatParts.get(k);
            int delay = shut ? (rows - 1 - part[0]) * 2 : part[0] * 2;
            d.setInterpolationDelay(delay);
            d.setInterpolationDuration(16);
            d.setTransformation(slatTransform(part[0], part[1], part[2], shut));
        }
    }

    // ---- belt simulation (rule 1: money from data)

    private void simulate(FactorySettings s) {
        int length = activeLength();
        if (length <= 0) {
            for (BeltItem item : items) {
                releaseDisplay(item);
            }
            items.clear();
            return;
        }
        PlotData data = module.plotData(view.owner());
        double speed = s.speed(data == null ? 0 : data.speedLevel())
                * (1 + me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "belt-speed"));
        int tier = data == null ? 0 : data.genLevel();
        ProgressSettings ps = module.progress();
        var gp = module.gensPlayer(view.owner());
        genTimer += DT;
        if (isBroken(0)) {
            genTimer = Math.min(genTimer, s.genIntervalSeconds());
        } else if (genTimer >= s.genIntervalSeconds()) {
            BeltItem newest = items.peekLast();
            if (newest == null || newest.pos >= SPAWN_POS + 1.0) { // one item per segment: the gen waits when the start is full
                FactorySettings.Output out = s.tier(tier).pick(random); // a blended tier makes a mix of items
                double luck = (1 + ps.luck().perLevel() * (gp == null ? 0 : gp.luckLevel()))
                        * (1 + me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "luck"));
                int special = NORMAL;
                double roll = random.nextDouble();
                if (roll < ps.cacheChance() * luck) {
                    special = CACHE + ps.rollRarity(random);
                } else if (roll < (ps.cacheChance() + ps.tokenChance()) * luck) {
                    special = TOKEN;
                }
                double gold = 0;
                if (special == NORMAL) {
                    double chance = me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "golden-chance");
                    if (chance > 0 && random.nextDouble() < chance) {
                        gold = 1 + me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "golden-value");
                    }
                }
                items.addLast(new BeltItem(SPAWN_POS, out.item(), out.valueCents(), special, gold));
                emitted = true;
                genTimer -= s.genIntervalSeconds();
            } else {
                genTimer = s.genIntervalSeconds();
            }
        }
        // Items move oldest first. Nothing passes a broken segment, and each item keeps a segment behind the one ahead.
        if (firstBroken(length) >= 0) {
            for (Iterator<BeltItem> it = items.iterator(); it.hasNext(); ) {
                BeltItem item = it.next();
                int seg = (int) Math.round(item.pos);
                if (seg >= 0 && isBroken(seg)) {
                    it.remove();
                    releaseDisplay(item);
                }
            }
        }
        double limit = Double.MAX_VALUE;
        for (BeltItem item : items) {
            double cap = limit;
            for (int b = Math.max(0, (int) Math.round(item.pos)); b < length; b++) {
                if (isBroken(b)) {
                    cap = Math.min(cap, b - 0.5);
                    break;
                }
            }
            double before = item.pos;
            item.pos = Math.max(item.pos, Math.min(item.pos + speed * DT, cap));
            limit = item.pos - 1.0;
            if (drawn && data != null && data.machines() > 0) {
                machineCross(before, item.pos, data.machines(), length);
            }
        }
        double boost = Machines.multiplier(data == null ? 0 : data.machines(), length)
                * (1 + FactoryModule.REBIRTH_BONUS * (gp == null ? 0 : gp.rebirths()))
                * (1 + ps.value().perLevel() * (gp == null ? 0 : gp.valueLevel()))
                * (1 + me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "sale-value"))
                * RankPerks.sale(view.owner());
        while (!items.isEmpty() && items.peekFirst().pos >= length) {
            BeltItem sold = items.pollFirst();
            releaseDisplay(sold);
            if (sold.special != NORMAL) {
                module.rewards().belt(view.owner(), sold.special);
                continue;
            }
            long value = Math.round(s.saleValue(sold.baseCents, length) * boost * sold.worth());
            if (sold.golden()) {
                goldenThisTick++;
            }
            pendingCents += value;
            popupCents += value;
            salesThisTick++;
            soldSinceDeposit++;
            earnings.addLast(new long[]{System.currentTimeMillis(), value});
        }
        itemsOnBelt = items.size();
    }

    private void everySecond(Player owner, FactorySettings s) {
        salePopup();
        autoRepair();
        defences(owner);
        long cutoff = System.currentTimeMillis() - 60_000;
        long sum = 0;
        for (Iterator<long[]> it = earnings.iterator(); it.hasNext(); ) {
            long[] e = it.next();
            if (e[0] < cutoff) {
                it.remove();
            } else {
                sum += e[1];
            }
        }
        incomePerMinute = sum;
        if (owner != null && sum > 0) {
            int bracket = (int) Math.floor(Math.log10(Math.max(1, sum / 100.0)));
            if (bestBracket < 0) {
                bestBracket = bracket;
            } else if (bracket > bestBracket && bracket >= 2) {
                {
                    long shown = (long) Math.pow(10, bracket) * 100;
                    module.celebrate(owner, "<gold><bold>" + Money.compact(shown) + "/MIN", "<white>Your factory hit a new income level!", bracket >= 4);
                    if (bracket >= 6) {
                        Component line = Msg.parse(Msg.headerText("Tycoon") + "<white>" + Msg.escape(owner.getName())
                                + "'s</white> <gray>factory now makes</gray> <gold>" + Money.compact(shown) + "/min</gold><gray>!</gray>");
                        for (Player o : Bukkit.getOnlinePlayers()) {
                            Tasks.entity.run(o, () -> o.sendMessage(line));
                        }
                    }
                }
                bestBracket = bracket;
            }
        }
        if (++depositCountdown >= s.depositSeconds() && pendingCents > 0 && owner != null) {
            depositCountdown = 0;
            long cents = pendingCents;
            pendingCents = 0;
            if (siphonPay(cents, owner)) {
                cents = 0;
            } else {
                module.pay(view.owner(), cents); // online wallet: in memory, saved soon after (no database on this thread)
                Tasks.entity.run(owner, () -> owner.playSound(owner.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.25f, 1.7f));
            }
            me.Evan52098.genscore.hub.HubModule.progress(view.owner(), "EARN", cents);
            me.Evan52098.genscore.hub.HubModule.progress(view.owner(), "SELL", soldSinceDeposit);
            soldSinceDeposit = 0;
            var g = module.gensPlayer(view.owner());
            if (g != null) {
                g.addLifetime(cents);
            }
            module.rewards().checkMilestones(owner, this);
        }
        if (owner != null && hasGate()) {
            updateBar(owner, s);
        }
        if (nameplate != null && nameplate.isValid() && ++plateTick % 2 == 0) {
            nameplate.text(plateText());
        }
        if (owner != null) {
            ownerBeacon(owner);
        }
        if (owner != null && view.contains(owner.getLocation().getBlockX(), owner.getLocation().getBlockZ())) {
            Component line = Msg.parse("<gold>+" + Money.compact(incomePerMinute) + "/min</gold>  <gray>|</gray>  <white>Belt "
                    + activeLength() + "/" + path.size() + "</white>  <gray>|</gray>  " + gateStatus());
            Tasks.entity.run(owner, () -> owner.sendActionBar(line));
        } else if (owner != null && hasGate() && (gateClosed() || closeCooldownLeft() > 0)) {
            Component line = Msg.parse(gateStatus());
            Tasks.entity.run(owner, () -> owner.sendActionBar(line));
        }
    }

    private void updateBar(Player owner, FactorySettings s) {
        boolean shut = gateClosed();
        long left = gateSecondsLeft();
        Component title = shut ? Msg.parse("<green>Gate closed</green> <white>" + left + "s</white> <gray>- re-up in the last " + REUP_WINDOW + "s</gray>")
                : Msg.parse("<red><bold>GATE OPEN</bold></red> <gray>- press the gate button or /gate</gray>");
        float progress = shut ? Math.min(1f, Math.max(0f, left / (float) module.gateSeconds(view.owner()))) : 1f;
        if (bar == null) {
            bar = BossBar.bossBar(title, progress, shut ? BossBar.Color.GREEN : BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        } else {
            bar.name(title);
            bar.progress(progress);
            bar.color(shut ? (left <= 10 ? BossBar.Color.YELLOW : BossBar.Color.GREEN) : BossBar.Color.RED);
        }
        if (!barShown) {
            barShown = true;
            BossBar b = bar;
            Tasks.entity.run(owner, () -> owner.showBossBar(b));
        }
    }

    /** The owner came back online: show the bar again on the next second. */
    void ownerJoined() {
        barShown = false;
    }

    // ---- visuals (rule 6: only drawn with a player near)

    private void draw() {
        drawn = true;
        drawnLength = -1;
        FactorySettings s = module.settings();
        drawMachine(s);
        drawTurrets();
        int tier = currentTier();
        drawnTier = tier;
        freeDisplays.clear();
        shownItem.clear();
        Material first = s.tier(tier).item();
        for (int i = 0; i < s.maxDrawnItems(); i++) {
            freeDisplays.add(i);
            shownItem.add(first.name());
            itemDisplays.add(view.world().spawn(base, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setItemStack(new ItemStack(first));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                d.setViewRange(1.0f);
                // The display sits in the middle of the factory and is moved to its spot on the belt. Without a
                // culling box as big as the factory the game hides it whenever the middle is off screen (flicker).
                d.setDisplayWidth(view.size() + 2);
                d.setDisplayHeight(4);
                d.setTransformation(itemTransform(null, false));
            }));
        }
        for (BeltItem item : items) {
            item.display = -1;
            item.placed = false;
        }
    }

    /** Small effects every update: the gen pulses and puffs when it makes an item; the sell point sparkles. */
    private void effects() {
        if (machineDisplay != null && machineDisplay.isValid()) {
            long nowMs = System.currentTimeMillis();
            if (emitted && !machinePulsed && nowMs - lastPulse >= 600) {
                lastPulse = nowMs;
                Transformation t = machineDisplay.getTransformation();
                machineDisplay.setInterpolationDelay(0);
                machineDisplay.setInterpolationDuration(3);
                machineDisplay.setTransformation(new Transformation(new Vector3f(0, 0.02f, 0), t.getLeftRotation(),
                        new Vector3f(1.07f, 0.94f, 1.07f), t.getRightRotation()));
                machinePulsed = true;
                Vector chute = path.size() > 0 ? path.at(-0.45) : null;
                if (chute != null) {
                    view.world().spawnParticle(org.bukkit.Particle.SMOKE, chute.getX(), chute.getY() + 0.2, chute.getZ(), 2, 0.08, 0.05, 0.08, 0.01);
                }
            } else if (machinePulsed && nowMs - lastPulse >= 200) {
                Transformation t = machineDisplay.getTransformation();
                machineDisplay.setInterpolationDelay(0);
                machineDisplay.setInterpolationDuration(5);
                machineDisplay.setTransformation(new Transformation(new Vector3f(), t.getLeftRotation(), new Vector3f(1, 1, 1), t.getRightRotation()));
                machinePulsed = false;
            }
        }
        emitted = false;
        if (salesThisTick > 0 && System.currentTimeMillis() - lastSparkle >= 500) {
            lastSparkle = System.currentTimeMillis();
            Vector end = path.size() > 0 ? path.at(activeLength()) : null;
            if (end != null) {
                view.world().spawnParticle(org.bukkit.Particle.WAX_ON, end.getX(), end.getY() + 0.3, end.getZ(),
                        2, 0.15, 0.1, 0.15, 0.2);
            }
        }
        salesThisTick = 0;
        if (goldenThisTick > 0 && path.size() > 0) {
            Vector end = path.at(activeLength());
            Location at = new Location(view.world(), end.getX(), end.getY() + 0.4, end.getZ());
            view.world().spawnParticle(org.bukkit.Particle.DUST, at, 24, 0.3, 0.3, 0.3, 0,
                    new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(255, 200, 40), 1.3f));
            view.world().spawnParticle(org.bukkit.Particle.WAX_OFF, at, 6, 0.25, 0.2, 0.25, 0.3);
            view.world().playSound(at, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 1.6f);
        }
        goldenThisTick = 0;
    }

    /** A floating "+$X" that rises over the sell point and fades, once a second. */
    private void salePopup() {
        long cents = popupCents;
        popupCents = 0;
        if (cents <= 0 || !drawn || path.size() == 0) {
            return;
        }
        Vector end = path.at(activeLength());
        Location at = new Location(view.world(), end.getX(), end.getY() + 1.0, end.getZ());
        TextDisplay popup = view.world().spawn(at, TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(0, 0, 0, 0));
            d.setShadowed(true);
            float size = (float) Math.min(1.4, 0.55 + 0.08 * Math.log10(Math.max(10, cents)));
            String color = cents >= 1_000_000_00L ? "<gradient:#FFD54F:#FF7A18>" : cents >= 10_000_00L ? "<gold>" : "<green>";
            d.text(Msg.parse(color + "<bold>+" + Money.compact(cents) + "</bold>"));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(size, size, size), new Quaternionf()));
        });
        Tasks.region.later(at, () -> {
            if (popup.isValid()) {
                popup.setInterpolationDelay(0);
                popup.setInterpolationDuration(18);
                popup.setTransformation(new Transformation(new Vector3f(0, 1.1f, 0), new Quaternionf(), new Vector3f(1.1f, 1.1f, 1.1f), new Quaternionf()));
            }
        }, 2);
        Tasks.region.later(at, popup::remove, 22);
    }

    /** A flash of light. Some versions want a colour for it, some want nothing: never let an effect throw. */
    private void flash(Location at, org.bukkit.Color color) {
        try {
            if (org.bukkit.Particle.FLASH.getDataType() == org.bukkit.Color.class) {
                view.world().spawnParticle(org.bukkit.Particle.FLASH, at, 1, 0, 0, 0, 0, color);
            } else {
                view.world().spawnParticle(org.bukkit.Particle.FLASH, at, 1, 0, 0, 0, 0);
            }
        } catch (IllegalArgumentException ignored) {
            // effects are optional
        }
    }

    /** The gen went up a tier: a burst over the machine. */
    private void tierUpFlash() {
        BlockVector m = machineCell();
        if (m == null) {
            return;
        }
        Location at = new Location(view.world(), m.getBlockX() + 0.5, m.getBlockY() + 1.0, m.getBlockZ() + 0.5);
        view.world().spawnParticle(org.bukkit.Particle.TOTEM_OF_UNDYING, at, 60, 0.4, 0.6, 0.4, 0.35);
        flash(at, org.bukkit.Color.WHITE);
        view.world().playSound(at, Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.4f);
        if (machineDisplay != null && machineDisplay.isValid()) {
            Transformation t = machineDisplay.getTransformation();
            machineDisplay.setInterpolationDelay(0);
            machineDisplay.setInterpolationDuration(4);
            machineDisplay.setTransformation(new Transformation(new Vector3f(0, 0.1f, 0), t.getLeftRotation(), new Vector3f(1.25f, 1.25f, 1.25f), t.getRightRotation()));
            machinePulsed = true;
        }
    }

    private int currentTier() {
        PlotData data = module.plotData(view.owner());
        return data == null ? 0 : data.genLevel();
    }

    /** The cell just behind the start of the belt, where the gen machine stands. */
    private BlockVector machineCell() {
        if (path.size() == 0) {
            return null;
        }
        BeltPath.Segment first = path.segments().get(0);
        return new BlockVector(first.cell().getBlockX() - first.dx(), first.cell().getBlockY(), first.cell().getBlockZ() - first.dz());
    }

    boolean isMachine(int x, int y, int z) {
        BlockVector m = machineCell();
        return m != null && m.getBlockX() == x && m.getBlockY() == y && m.getBlockZ() == z;
    }

    /** A barrier in the machine's cell makes it solid and clickable even while it is not drawn. */
    private void placeMachineBlock() {
        BlockVector m = machineCell();
        if (m == null) {
            return;
        }
        Block b = view.world().getBlockAt(m.getBlockX(), m.getBlockY(), m.getBlockZ());
        if (b.getType().isAir()) {
            b.setType(Material.BARRIER, false);
        }
    }

    private void drawMachine(FactorySettings s) {
        BlockVector m = machineCell();
        if (m == null) {
            return;
        }
        BeltPath.Segment first = path.segments().get(0);
        float yaw = (float) (Math.atan2(first.dx(), first.dz()) + Math.PI + Math.toRadians(s.modelYawOffset()));
        Location at = new Location(view.world(), m.getBlockX() + 0.5, m.getBlockY() + 0.5, m.getBlockZ() + 0.5);
        machineDisplay = view.world().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setItemStack(modelItem("gen_machine"));
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(yaw), new Vector3f(1, 1, 1), new Quaternionf()));
        });
        machineLabel = view.world().spawn(at.clone().add(0, 0.9, 0), TextDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setBillboard(Display.Billboard.CENTER);
            d.setDefaultBackground(false);
            d.setBackgroundColor(org.bukkit.Color.fromARGB(120, 0, 0, 0));
        });
        updateMachineLabel(s);
    }

    private void updateMachineLabel(FactorySettings s) {
        if (machineLabel == null || !machineLabel.isValid()) {
            return;
        }
        int tier = currentTier();
        FactorySettings.GenTier t = s.tier(tier);
        machineLabel.text(Msg.parse("<gold><bold>GEN</bold></gold> <white>Tier " + (tier + 1) + "</white>\n<yellow>"
                + Msg.escape(t.mix()) + "</yellow>\n<gray>about " + Money.compact(t.valueCents()) + " each before the belt</gray>"));
    }

    private static ItemStack modelItem(String model) {
        ItemStack stack = new ItemStack(Material.PAPER);
        ItemMeta meta = stack.getItemMeta();
        meta.setItemModel(new NamespacedKey("genscore", model));
        stack.setItemMeta(meta);
        return stack;
    }

    private void releaseDisplay(BeltItem item) {
        if (item.display >= 0) {
            if (item.display < itemDisplays.size()) {
                ItemDisplay d = itemDisplays.get(item.display);
                d.setInterpolationDuration(0);
                d.setTransformation(itemTransform(null, false));
            }
            freeDisplays.add(item.display);
            item.display = -1;
        }
    }

    private void undraw() {
        drawn = false;
        for (Display d : machineParts) {
            d.remove();
        }
        machineParts.clear();
        drawnMachines = -1;
        for (ItemDisplay d : wreckDisplays.values()) {
            d.remove();
        }
        wreckDisplays.clear();
        for (ItemDisplay d : segmentDisplays.values()) {
            d.remove();
        }
        segmentDisplays.clear();
        for (ItemDisplay d : itemDisplays) {
            d.remove();
        }
        itemDisplays.clear();
        for (Entity e : new Entity[]{sellDisplay, sellLabel, genDisplay, machineDisplay, machineLabel}) {
            if (e != null) {
                e.remove();
            }
        }
        sellDisplay = null;
        sellLabel = null;
        genDisplay = null;
        machineDisplay = null;
        machineLabel = null;
        for (ItemDisplay t : turretDisplays) {
            t.remove();
        }
        turretDisplays.clear();
        freeDisplays.clear();
        for (BeltItem item : items) {
            item.display = -1;
            item.placed = false;
        }
        drawnLength = -1;
    }

    private void redraw(FactorySettings s) {
        int length = activeLength();
        if (length != drawnLength || segmentsDirty) {
            drawSegments(length, s);
            drawnLength = length;
            segmentsDirty = false;
        }
        PlotData md = module.plotData(view.owner());
        int machinesKey = (md == null ? 0 : md.machines()) * 1000 + length;
        if (machinesKey != drawnMachines) {
            drawMachines(md == null ? 0 : md.machines(), length);
            drawnMachines = machinesKey;
        }
        if (sellLabel != null && sellKey != rebirthKey() && sellLabel.isValid()) {
            sellKey = rebirthKey();
            sellLabel.text(sellText(s.itemValue(length, currentTier())));
        }
        int tier = currentTier();
        if (tier != drawnTier) {
            boolean up = drawnTier >= 0 && tier > drawnTier;
            drawnTier = tier;
            updateMachineLabel(s);
            if (up) {
                tierUpFlash();
            }
        }
        effects();
        // Each item keeps one display from the gen to the sell point. New items take a free display; when there are
        // none free the item rides undrawn (the money is worked out from the data either way).
        for (BeltItem item : items) {
            if (item.display < 0) {
                Integer free = freeDisplays.poll();
                if (free == null) {
                    continue;
                }
                item.display = free;
                item.placed = false;
            }
            ItemDisplay d = itemDisplays.get(item.display);
            String look = item.look();
            if (!look.equals(shownItem.get(item.display))) {
                shownItem.set(item.display, look);
                d.setItemStack(item.special != NORMAL ? modelItem(look) : item.golden() ? goldenStack() : new ItemStack(item.item));
            }
            d.setInterpolationDelay(0);
            // The first placement jumps straight there; after that every move glides over one update period.
            d.setInterpolationDuration(item.placed ? PERIOD_TICKS + 1 : 0);
            d.setTransformation(itemTransform(path.at(item.pos), true, (float) (item.pos * 1.4)));
            item.placed = true;
        }
    }

    /** An item lying flat on the belt at {@code world}, relative to the display's spawn point; hidden when not shown. */
    private Transformation itemTransform(Vector world, boolean shown) {
        return itemTransform(world, shown, 0);
    }

    /** An item lying flat on the belt, turned {@code spin} radians about the vertical so it slowly spins as it rides. */
    private Transformation itemTransform(Vector world, boolean shown, float spin) {
        Quaternionf flat = new Quaternionf().rotateY(spin).rotateX((float) Math.toRadians(90));
        if (!shown || world == null) {
            return new Transformation(new Vector3f(0, -2, 0), flat, new Vector3f(0, 0, 0), new Quaternionf());
        }
        Vector3f t = new Vector3f((float) (world.getX() - base.getX()), (float) (world.getY() - base.getY() + 0.02),
                (float) (world.getZ() - base.getZ()));
        return new Transformation(t, flat, new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf());
    }

    private void drawSegments(int length, FactorySettings s) {
        for (Iterator<Map.Entry<Integer, ItemDisplay>> it = segmentDisplays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ItemDisplay> e = it.next();
            if (e.getKey() >= length) {
                e.getValue().remove();
                it.remove();
            }
        }
        for (Iterator<Map.Entry<Integer, ItemDisplay>> it = segmentDisplays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ItemDisplay> e = it.next();
            if (isBroken(e.getKey())) {
                e.getValue().remove();
                it.remove();
            }
        }
        for (Iterator<Map.Entry<Integer, ItemDisplay>> it = wreckDisplays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ItemDisplay> e = it.next();
            if (e.getKey() >= length || !isBroken(e.getKey())) {
                e.getValue().remove();
                it.remove();
            }
        }
        for (int i = 0; i < length; i++) {
            if (isBroken(i) && !wreckDisplays.containsKey(i)) {
                spawnWreck(i, s);
            }
        }
        for (int i = 0; i < length; i++) {
            if (segmentDisplays.containsKey(i) || isBroken(i)) {
                continue;
            }
            BeltPath.Segment seg = path.segments().get(i);
            BlockVector c = seg.cell();
            String model = switch (seg.kind()) {
                case STRAIGHT -> "conveyor_belt";
                case CORNER_LEFT -> "conveyor_corner_left";
                case CORNER_RIGHT -> "conveyor_corner_right";
            };
            int fx = seg.kind() == BeltPath.Kind.STRAIGHT ? seg.dx() : seg.inDx();
            int fz = seg.kind() == BeltPath.Kind.STRAIGHT ? seg.dz() : seg.inDz();
            // Item displays draw their item turned half a circle (like item frames), so add PI to face the right way.
            float yaw = (float) (Math.atan2(fx, fz) + Math.PI + Math.toRadians(s.modelYawOffset()));
            ItemStack stack = modelItem(model);
            Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.5, c.getBlockZ() + 0.5);
            segmentDisplays.put(i, view.world().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setItemStack(stack);
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(yaw), new Vector3f(1, 1, 1), new Quaternionf()));
            }));
        }
        // The sell point sits just past the last working segment.
        if (sellDisplay != null) {
            sellDisplay.remove();
        }
        if (sellLabel != null) {
            sellLabel.remove();
        }
        Vector end = length > 0 ? path.at(length) : null;
        if (end != null) {
            Location at = new Location(view.world(), Math.floor(end.getX()), Math.floor(end.getY()), Math.floor(end.getZ()));
            sellDisplay = view.world().spawn(at, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBlock(Bukkit.createBlockData("minecraft:hopper"));
                d.setTransformation(new Transformation(new Vector3f(0.06f, 0.03f, 0.06f), new Quaternionf(),
                        new Vector3f(0.88f, 0.88f, 0.88f), new Quaternionf()));
            });
            long value = s.itemValue(length, currentTier());
            sellLabel = view.world().spawn(at.clone().add(0.5, 1.4, 0.5), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.text(sellText(value));
            });
            sellKey = rebirthKey();
        }
    }

    private int sellKey = -1;

    private int rebirthKey() {
        var gp = module.gensPlayer(view.owner());
        return (gp == null ? 0 : gp.rebirths()) * 100000 + currentTier();
    }

    private net.kyori.adventure.text.Component sellText(long value) {
        var gp = module.gensPlayer(view.owner());
        int r = gp == null ? 0 : gp.rebirths();
        int tier = currentTier() + 1;
        int need = FactoryModule.rebirthTier(r);
        String line;
        if (tier >= need) {
            line = "<light_purple><bold>REBIRTH " + (r + 1) + " READY</bold></light_purple>\n<gray>Type</gray> <white>/rebirth</white>";
        } else {
            int pct = Math.max(0, Math.min(99, tier * 100 / need));
            int filled = pct / 10;
            line = "<light_purple>Rebirth " + (r + 1) + "</light_purple> <dark_gray>-</dark_gray> <white>" + pct + "%</white>\n"
                    + "<light_purple>" + "|".repeat(filled * 2) + "</light_purple><dark_gray>" + "|".repeat(20 - filled * 2) + "</dark_gray>\n"
                    + "<gray>Gen tier</gray> <white>" + tier + "</white><gray>/" + need + "</gray>";
        }
        return Msg.parse("<gold><bold>SELL</bold></gold>\n<white>about " + Money.compact(value) + " each</white>\n" + line);
    }

    private void machineCross(double from, double to, int owned, int length) {
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(owned, Machines.ALL.size()); i++) {
            Machines.Machine m = Machines.ALL.get(i);
            if (m.segment() >= length || from >= m.segment() || to < m.segment() || m.segment() >= path.size()) {
                continue;
            }
            BlockVector c = path.segments().get(m.segment()).cell();
            Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.9, c.getBlockZ() + 0.5);
            if (now - machineSpark[i] < 500) {
                continue;
            }
            machineSpark[i] = now;
            org.bukkit.Particle soft = m.particle() == org.bukkit.Particle.FLAME ? org.bukkit.Particle.SMALL_FLAME : m.particle();
            view.world().spawnParticle(soft, at, 1, 0.12, 0.05, 0.12, 0.0);
            if (now - machinePuff[i] > 1500) {
                machinePuff[i] = now;
                view.world().playSound(at, i == 0 ? Sound.BLOCK_BLASTFURNACE_FIRE_CRACKLE : i == 1 ? Sound.UI_STONECUTTER_TAKE_RESULT
                        : i == 2 ? Sound.BLOCK_COPPER_BULB_TURN_ON : i == 3 ? Sound.BLOCK_CRAFTER_CRAFT : Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 0.35f, 1.2f);
            }
        }
    }

    private void drawMachines(int owned, int length) {
        for (Display d : machineParts) {
            d.remove();
        }
        machineParts.clear();
        for (int i = 0; i < Math.min(owned, Machines.ALL.size()); i++) {
            Machines.Machine m = Machines.ALL.get(i);
            if (m.segment() >= path.size()) {
                continue;
            }
            boolean active = m.segment() < length;
            BlockVector c = path.segments().get(m.segment()).cell();
            Location base = new Location(view.world(), c.getBlockX(), c.getBlockY(), c.getBlockZ());
            BeltPath.Segment seg = path.segments().get(m.segment());
            boolean alongX = seg.kind() == BeltPath.Kind.STRAIGHT ? seg.dx() != 0 : seg.inDx() != 0;
            machineParts.add(view.world().spawn(base, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBlock(Bukkit.createBlockData(m.hood()));
                d.setTransformation(new Transformation(new Vector3f(0.1f, 1.05f, 0.1f), new Quaternionf(), new Vector3f(0.8f, 0.7f, 0.8f), new Quaternionf()));
            }));
            for (int side = 0; side < 2; side++) {
                float sx = alongX ? 0.42f : (side == 0 ? 0.0f : 0.86f);
                float sz = alongX ? (side == 0 ? 0.0f : 0.86f) : 0.42f;
                machineParts.add(view.world().spawn(base, BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.addScoreboardTag(TAG);
                    d.setBlock(Bukkit.createBlockData("minecraft:polished_deepslate"));
                    d.setTransformation(new Transformation(new Vector3f(sx, 0.45f, sz), new Quaternionf(), new Vector3f(0.14f, 0.65f, 0.14f), new Quaternionf()));
                }));
            }
            machineParts.add(view.world().spawn(base.clone().add(0.5, 2.05, 0.5), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.6f, 0.6f, 0.6f), new Quaternionf()));
                d.text(Msg.parse(active
                        ? m.color() + "<bold>" + m.name().toUpperCase(java.util.Locale.ROOT) + "</bold>\n<white>x" + trim(m.multiplier()) + " value"
                        : "<gray><bold>" + m.name().toUpperCase(java.util.Locale.ROOT) + "</bold>\n<red>Extend your belt to reach it"));
            }));
        }
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private void spawnWreck(int i, FactorySettings s) {
        BeltPath.Segment seg = path.segments().get(i);
        BlockVector c = seg.cell();
        String model = switch (seg.kind()) {
            case STRAIGHT -> "conveyor_belt";
            case CORNER_LEFT -> "conveyor_corner_left";
            case CORNER_RIGHT -> "conveyor_corner_right";
        };
        int fx = seg.kind() == BeltPath.Kind.STRAIGHT ? seg.dx() : seg.inDx();
        int fz = seg.kind() == BeltPath.Kind.STRAIGHT ? seg.dz() : seg.inDz();
        float yaw = (float) (Math.atan2(fx, fz) + Math.PI + Math.toRadians(s.modelYawOffset()));
        float tilt = (i % 2 == 0 ? 1 : -1) * 0.22f;
        ItemStack stack = modelItem(model);
        Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.5, c.getBlockZ() + 0.5);
        wreckDisplays.put(i, view.world().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.addScoreboardTag(TAG);
            d.setItemStack(stack);
            d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(4, 4));
            d.setTransformation(new Transformation(new Vector3f(0, -0.12f, 0),
                    new Quaternionf().rotateY(yaw).rotateX(tilt).rotateZ(tilt * 0.6f),
                    new Vector3f(0.92f, 0.92f, 0.92f), new Quaternionf()));
        }));
    }

    private boolean siphonPay(long cents, Player owner) {
        UUID thief = siphonTo;
        if (thief == null) {
            return false;
        }
        Player t = Bukkit.getPlayer(thief);
        if (t == null || gateClosed()) {
            endSiphon(false);
            return false;
        }
        module.pay(thief, cents);
        siphoned += cents;
        String ownerName = String.valueOf(Bukkit.getOfflinePlayer(view.owner()).getName());
        Tasks.entity.run(t, () -> t.sendActionBar(Msg.parse("<green>+" + Money.compact(cents) + "</green> <gray>siphoned from</gray> <white>"
                + Msg.escape(ownerName) + "</white>")));
        if (owner != null) {
            Tasks.entity.run(owner, () -> owner.sendActionBar(Msg.parse("<red><bold>⚠ " + Msg.escape(t.getName())
                    + " is taking your income!</bold></red> <gray>Close your gate</gray>")));
        }
        return true;
    }

    private void endSiphon(boolean byGate) {
        UUID thief = siphonTo;
        if (thief == null) {
            return;
        }
        siphonTo = null;
        long total = siphoned;
        siphoned = 0;
        Player t = Bukkit.getPlayer(thief);
        Player o = Bukkit.getPlayer(view.owner());
        if (t != null && total > 0) {
            Tasks.entity.run(t, () -> t.sendMessage(Msg.neutral("Siphon over" + (byGate ? ": the gate closed" : "") + ". You took "
                    + Msg.white(Money.compact(total)) + ".")));
        }
        if (o != null && byGate && total > 0) {
            Tasks.entity.run(o, () -> o.sendMessage(Msg.good("Gate closed. The raider took " + Msg.white(Money.compact(total)) + " of your income.")));
        }
    }

    private void ownerBeacon(Player owner) {
        Location top = view.center().add(0, 12, 0);
        if (ownerMarker == null || !ownerMarker.isValid()) {
            ownerMarker = view.world().spawn(top, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setVisibleByDefault(false);
                d.setBillboard(Display.Billboard.CENTER);
                d.setShadowed(true);
                d.setDefaultBackground(false);
                d.setBackgroundColor(org.bukkit.Color.fromARGB(110, 255, 122, 24));
                d.setBrightness(new Display.Brightness(15, 15));
                d.setViewRange(8f);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(3.5f, 3.5f, 3.5f), new Quaternionf()));
                d.text(Msg.parse("<gold><bold>★ YOUR FACTORY ★</bold></gold>"));
            });
            TextDisplay marker = ownerMarker;
            Tasks.entity.run(owner, () -> owner.showEntity(module.plugin(), marker));
        }
        if (view.contains(owner.getLocation().getBlockX(), owner.getLocation().getBlockZ())) {
            return;
        }
        Location base = view.center();
        new com.destroystokyo.paper.ParticleBuilder(org.bukkit.Particle.END_ROD).receivers(owner).force(true)
                .location(base.clone().add(0, 8 + (System.currentTimeMillis() / 1000 % 4) * 6, 0)).count(12).offset(0.15, 3, 0.15).extra(0).spawn();
        new com.destroystokyo.paper.ParticleBuilder(org.bukkit.Particle.DUST).receivers(owner).force(true)
                .location(base.clone().add(0, 20, 0)).count(30).offset(0.25, 12, 0.25)
                .data(new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(255, 140, 30), 2.5f)).spawn();
    }

    private void updateBrokenLabel(long now, long after) {
        int first = -1;
        long oldest = Long.MAX_VALUE;
        if (brokenAt != null) {
            for (int i = 0; i < brokenAt.length && i < activeLength(); i++) {
                if (brokenAt[i] > 0) {
                    if (first < 0) {
                        first = i;
                    }
                    oldest = Math.min(oldest, brokenAt[i]);
                }
            }
        }
        if (first < 0) {
            if (brokenLabel != null) {
                brokenLabel.remove();
                brokenLabel = null;
            }
            return;
        }
        long left = Math.max(0, (oldest + after - now + 999) / 1000);
        Component text = Msg.parse("<red><bold>⚠ BELT BROKEN</bold></red>\n<gray>fixes itself in <white>" + left + "s</white></gray>");
        BlockVector c = path.segments().get(first).cell();
        Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.55, c.getBlockZ() + 0.5);
        if (brokenLabel == null || !brokenLabel.isValid()) {
            brokenLabel = view.world().spawn(at, TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.55f, 0.55f, 0.55f), new Quaternionf()));
                d.text(text);
            });
        } else {
            if (brokenLabel.getLocation().distanceSquared(at) > 0.01) {
                brokenLabel.teleportAsync(at);
            }
            brokenLabel.text(text);
        }
    }

    static String tag() {
        return TAG;
    }

    // ------------------------------------------------------------------ raids (region thread)

    enum Hit { DAMAGED, BROKE, BROKEN_ALREADY, IDLE_SEGMENT, SHIELDED, GATE_CLOSED, RAID_CAP }

    /** What a hit did, for the raider's message. */
    record HitResult(Hit hit, int healthLeft, int maxHealth, long loot, int tokens, int caches, long shieldSeconds) {
    }

    int segmentIndex(int x, int y, int z) {
        List<BeltPath.Segment> segs = path.segments();
        for (int i = 0; i < segs.size(); i++) {
            BlockVector c = segs.get(i).cell();
            if (c.getBlockX() == x && c.getBlockY() == y && c.getBlockZ() == z) {
                return i;
            }
        }
        return -1;
    }

    boolean isBroken(int i) {
        return brokenAt != null && i >= 0 && i < brokenAt.length && brokenAt[i] > 0;
    }

    long lastAttackedAt() {
        long[] hits = lastHit;
        long last = 0;
        if (hits != null) {
            for (long h : hits) {
                last = Math.max(last, h);
            }
        }
        return last;
    }

    int brokenCount() {
        int n = 0;
        if (brokenAt != null) {
            for (long b : brokenAt) {
                if (b > 0) {
                    n++;
                }
            }
        }
        return n;
    }

    private int firstBroken(int length) {
        if (brokenAt == null) {
            return -1;
        }
        for (int i = 0; i < Math.min(length, brokenAt.length); i++) {
            if (brokenAt[i] > 0) {
                return i;
            }
        }
        return -1;
    }

    private void ensureArrays() {
        if (health == null || health.length != path.size()) {
            health = new int[path.size()];
            java.util.Arrays.fill(health, -1);
            brokenAt = new long[path.size()];
            lastHit = new long[path.size()];
        }
    }

    long shieldLeftSeconds() {
        PlotData data = module.plotData(view.owner());
        long left = data == null ? 0 : data.shieldUntil() - System.currentTimeMillis();
        return Math.max(0, (left + 999) / 1000);
    }

    /** A raider hits belt segment {@code index}. Region thread. Only while the gate is open (raids need an open gate). */
    HitResult hit(Player raider, int index, int damage) {
        ensureArrays();
        ProgressSettings ps = module.progress();
        PlotData data = module.plotData(view.owner());
        int maxHealth = ps.segmentHealth(data == null ? 0 : data.armorLevel());
        if (gateClosed()) {
            return new HitResult(Hit.GATE_CLOSED, 0, maxHealth, 0, 0, 0, 0);
        }
        long shield = shieldLeftSeconds();
        if (shield > 0) {
            return new HitResult(Hit.SHIELDED, 0, maxHealth, 0, 0, 0, shield);
        }
        if (index < 0 || index >= activeLength()) {
            return new HitResult(Hit.IDLE_SEGMENT, 0, maxHealth, 0, 0, 0, 0);
        }
        if (isBroken(index)) {
            return new HitResult(Hit.BROKEN_ALREADY, 0, maxHealth, 0, 0, 0, 0);
        }
        int done = raidCounts.getOrDefault(raider.getUniqueId(), 0);
        if (done >= ps.segmentsPerRaid()) {
            return new HitResult(Hit.RAID_CAP, 0, maxHealth, 0, 0, 0, 0);
        }
        long now = System.currentTimeMillis();
        lastRaidHit = now;
        raidHitAt.put(raider.getUniqueId(), now);
        lastHit[index] = now;
        int hp = health[index] < 0 ? maxHealth : health[index];
        double defense = Math.min(0.9, me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "raid-defense"));
        hp -= Math.max(1, (int) Math.round(damage * (1 - defense)));
        BlockVector c = path.segments().get(index).cell();
        Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.6, c.getBlockZ() + 0.5);
        if (hp > 0) {
            health[index] = hp;
            view.world().spawnParticle(org.bukkit.Particle.CRIT, at, 8, 0.3, 0.2, 0.3, 0.05);
            view.world().playSound(at, Sound.BLOCK_ANVIL_LAND, 0.4f, 1.6f);
            return new HitResult(Hit.DAMAGED, hp, maxHealth, 0, 0, 0, 0);
        }
        // Broken: the items on it spill and the raider takes a share; everything behind it stops.
        health[index] = 0;
        brokenAt[index] = now;
        segmentsDirty = true;
        FactorySettings s = module.settings();
        int length = activeLength();
        var raiderData = module.gensPlayer(raider.getUniqueId());
        double share = Math.min(1.0, ps.lootShare(raiderData == null ? 0 : raiderData.gearLevel())
                * (1 + me.Evan52098.genscore.powers.PowersModule.perk(raider.getUniqueId(), "raid-loot")));
        long loot = 0;
        int tokens = 0;
        int caches = 0;
        for (Iterator<BeltItem> it = items.iterator(); it.hasNext(); ) {
            BeltItem item = it.next();
            if (item.pos >= index - 0.5 && item.pos < index + 0.5) {
                it.remove();
                releaseDisplay(item);
                view.world().spawnParticle(org.bukkit.Particle.ITEM, at, 10, 0.3, 0.3, 0.3, 0.08,
                        new ItemStack(item.special != NORMAL ? Material.SUNFLOWER : item.golden() ? Material.GOLD_INGOT : item.item));
                if (item.special == TOKEN) {
                    tokens++;
                } else if (item.special >= CACHE) {
                    caches++;
                    if (raiderData != null) {
                        raiderData.addCache(item.special - CACHE, 1);
                    }
                } else {
                    loot += Math.round(s.saleValue(item.baseCents, length) * share * item.worth());
                }
            }
        }
        tokens += ps.tokensPerSegment();
        if (raiderData != null) {
            tokens = (int) RankPerks.tokens(raider.getUniqueId(), tokens);
            raiderData.addTokens(tokens);
            if (done == 0) {
                raiderData.addRaidWon();
            }
        }
        if (loot > 0) {
            module.pay(raider.getUniqueId(), loot);
        }
        loot += module.steal(view.owner(), raider, incomePerMinute);
        raidCounts.merge(raider.getUniqueId(), 1, Integer::sum);
        view.world().spawnParticle(org.bukkit.Particle.EXPLOSION, at, 1);
        view.world().playSound(at, Sound.ENTITY_ITEM_BREAK, 1f, 0.7f);
        view.world().playSound(at, Sound.BLOCK_CHAIN_BREAK, 1f, 0.6f);
        long shieldSeconds = 0;
        if (done + 1 >= ps.segmentsPerRaid() && data != null) {
            // The raid cap is reached: the factory gets a shield so it is not raided again straight away.
            data.setShieldUntil(now + ps.shieldMinutes() * 60_000L);
            module.saveSoon(view.owner());
            raidCounts.clear();
            shieldSeconds = ps.shieldMinutes() * 60L;
        }
        module.raidLogged(raider, this, loot, tokens, caches, shieldSeconds);
        me.Evan52098.genscore.hub.HubModule.progress(raider.getUniqueId(), "RAID", 1);
        if (!raider.getUniqueId().equals(siphonTo)) {
            endSiphon(false);
            siphonTo = raider.getUniqueId();
            siphoned = 0;
            raider.sendMessage(Msg.good("You're now taking " + Msg.white(Msg.escape(String.valueOf(Bukkit.getOfflinePlayer(view.owner()).getName())))
                    + "'s income until they close their gate."));
        }
        return new HitResult(Hit.BROKE, 0, maxHealth, loot, tokens, caches, shieldSeconds);
    }

    /** Seconds until the owner may repair segment {@code i} (repairs wait a few seconds after the last hit). */
    long repairWait(int i) {
        if (lastHit == null || i < 0 || i >= lastHit.length) {
            return 0;
        }
        long wait = lastHit[i] + module.progress().repairLockSeconds() * 1000L - System.currentTimeMillis();
        return Math.max(0, (wait + 999) / 1000);
    }

    /** Repairs one broken segment now (the owner paid for it). Region thread. */
    boolean repair(int i) {
        if (!isBroken(i) || repairWait(i) > 0) {
            return false;
        }
        brokenAt[i] = 0;
        health[i] = -1;
        segmentsDirty = true;
        BlockVector c = path.segments().get(i).cell();
        Location at = new Location(view.world(), c.getBlockX() + 0.5, c.getBlockY() + 0.6, c.getBlockZ() + 0.5);
        view.world().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, at, 10, 0.3, 0.2, 0.3);
        view.world().playSound(at, Sound.BLOCK_ANVIL_USE, 0.7f, 1.3f);
        return true;
    }

    void repairAll() {
        if (brokenAt == null) {
            return;
        }
        for (int i = 0; i < brokenAt.length; i++) {
            if (brokenAt[i] > 0) {
                brokenAt[i] = 0;
                health[i] = -1;
                segmentsDirty = true;
                BlockVector c = path.segments().get(i).cell();
                view.world().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, c.getBlockX() + 0.5, c.getBlockY() + 0.6,
                        c.getBlockZ() + 0.5, 6, 0.3, 0.2, 0.3);
            }
        }
    }

    /** Broken segments mend themselves after a while, so an owner who is away is not stuck. */
    private void autoRepair() {
        if (brokenAt == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long after = Math.round(module.progress().autoRepairSeconds() * 1000L
                * Math.max(0.2, 1 - me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "repair-speed")));
        for (int i = 0; i < brokenAt.length; i++) {
            if (brokenAt[i] > 0) {
                BlockVector c = path.segments().get(i).cell();
                view.world().spawnParticle(org.bukkit.Particle.SMOKE, c.getBlockX() + 0.5, c.getBlockY() + 0.7,
                        c.getBlockZ() + 0.5, 3, 0.15, 0.05, 0.15, 0.01);
                if ((now / 1000 + i) % 2 == 0) {
                    view.world().spawnParticle(org.bukkit.Particle.ELECTRIC_SPARK, c.getBlockX() + 0.5, c.getBlockY() + 0.6,
                            c.getBlockZ() + 0.5, 4, 0.2, 0.1, 0.2, 0.05);
                }
                if (now - brokenAt[i] >= after) {
                    brokenAt[i] = 0;
                    health[i] = -1;
                    segmentsDirty = true;
                }
            }
        }
        updateBrokenLabel(now, after);
        if (now - lastRaidHit > 120_000) {
            raidCounts.clear();
        }
        raidHitAt.values().removeIf(t -> now - t > 60_000);
    }

    // ------------------------------------------------------------------ defences (region thread)

    private boolean intruder(Player p) {
        return !p.getUniqueId().equals(view.owner()) && !module.bypasses(p) && !p.isDead()
                && !module.trusted(view.owner(), p)
                && !me.Evan52098.genscore.hub.HubModule.cloaked(p)
                && (p.getGameMode() == org.bukkit.GameMode.SURVIVAL || p.getGameMode() == org.bukkit.GameMode.ADVENTURE);
    }

    /** Alarm, traps and turret targets, once a second. */
    private void defences(Player owner) {
        PlotData data = module.plotData(view.owner());
        Set<UUID> now = new HashSet<>();
        List<Player> inside = new ArrayList<>();
        for (Player p : view.world().getNearbyPlayers(base, view.size())) {
            Location l = p.getLocation();
            if (intruder(p) && view.contains(l.getBlockX(), l.getBlockZ())) {
                now.add(p.getUniqueId());
                if (view.inside(l.getBlockX(), l.getBlockZ())) {
                    inside.add(p);
                }
            }
        }
        // Alarm: tell the owner who came in.
        if (owner != null) {
            for (UUID id : now) {
                if (!intruders.contains(id)) {
                    Player p = Bukkit.getPlayer(id);
                    String name = p == null ? "Someone" : p.getName();
                    Tasks.entity.run(owner, () -> {
                        owner.sendMessage(Msg.bad(Msg.white(name) + " entered your factory!"));
                        owner.playSound(owner.getLocation(), Sound.BLOCK_BELL_USE, 1f, 1.4f);
                        owner.showTitle(net.kyori.adventure.title.Title.title(Msg.parse("<red><bold>INTRUDER"),
                                Msg.parse("<white>" + Msg.escape(name) + " <gray>is in your factory")));
                    });
                }
            }
        }
        intruders.clear();
        intruders.addAll(now);
        // Traps: slow intruders inside the walls; strong traps also throw them back toward the gate.
        int traps = data == null ? 0 : Math.min(data.trapLevel(), module.progress().traps().max());
        if (traps > 0 && !inside.isEmpty()) {
            boolean shove = traps >= 3 && ++trapPulse % 4 == 0;
            Location gate = outsideGate();
            for (Player p : inside) {
                Tasks.entity.run(p, () -> {
                    p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.SLOWNESS, 30,
                            Math.min(traps - 1, 3), false, true, true));
                    if (shove) {
                        Vector push = gate.toVector().subtract(p.getLocation().toVector()).setY(0);
                        if (push.lengthSquared() > 0.01) {
                            p.setVelocity(push.normalize().multiply(0.9).setY(0.35));
                            p.playSound(p.getLocation(), Sound.ENTITY_SLIME_JUMP, 1f, 0.6f);
                        }
                    }
                });
            }
        }
    }

    /** Turrets: called every tick period; each active turret fires at the nearest intruder in range when ready. */
    private void turrets() {
        PlotData data = module.plotData(view.owner());
        int level = data == null ? 0 : data.turretLevel();
        List<BlockVector> spots = view.marker("turret");
        int active = Math.min(level, spots.size());
        if (active <= 0 || intruders.isEmpty()) {
            return;
        }
        ProgressSettings ps = module.progress();
        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(250, (ps.turretCooldownTicks() - 3L * (level - 1)) * 50L);
        double damage = (ps.turretDamage() + ps.turretDamagePerLevel() * (level - 1))
                * (1 + me.Evan52098.genscore.powers.PowersModule.perk(view.owner(), "turret-damage"));
        for (int t = 0; t < active; t++) {
            if (turretReady.getOrDefault(t, 0L) > now) {
                continue;
            }
            BlockVector spot = spots.get(t);
            Location muzzle = new Location(view.world(), spot.getBlockX() + 0.5,
                    spot.getBlockY() + 0.5 + TURRET_LIFT.y + 0.3 * TURRET_SCALE, spot.getBlockZ() + 0.5);
            Player target = null;
            double best = ps.turretRange() * ps.turretRange();
            for (UUID id : intruders) {
                Player p = Bukkit.getPlayer(id);
                if (p == null || !p.getWorld().equals(view.world())) {
                    continue;
                }
                double d = p.getEyeLocation().distanceSquared(muzzle);
                if (d < best) {
                    best = d;
                    target = p;
                }
            }
            if (target == null) {
                continue;
            }
            turretReady.put(t, now + cooldownMs);
            Location eye = target.getLocation().add(0, 1.2, 0);
            Vector dir = eye.toVector().subtract(muzzle.toVector());
            double len = dir.length();
            Vector step = dir.clone().normalize().multiply(0.4);
            Location p = muzzle.clone();
            org.bukkit.Particle.DustOptions red = new org.bukkit.Particle.DustOptions(org.bukkit.Color.fromRGB(255, 40, 30), 0.9f);
            for (double d = 0; d < len; d += 0.4) {
                view.world().spawnParticle(org.bukkit.Particle.DUST, p, 1, 0, 0, 0, 0, red);
                p.add(step);
            }
            view.world().playSound(muzzle, Sound.ENTITY_BLAZE_SHOOT, 0.7f, 1.6f);
            flash(muzzle, org.bukkit.Color.RED);
            view.world().spawnParticle(org.bukkit.Particle.LAVA, eye, 4, 0.2, 0.3, 0.2, 0);
            aimTurret(t, dir);
            Player victim = target;
            UUID owner = view.owner();
            boolean raiding = now - raidHitAt.getOrDefault(victim.getUniqueId(), 0L) < 60_000;
            Tasks.entity.run(victim, () -> {
                if (victim.isDead()) {
                    return;
                }
                module.markCombat(victim.getUniqueId());
                victim.damage(damage);
                if (raiding && (victim.isDead() || victim.getHealth() <= 0)) {
                    module.rewards().turretKill(owner, victim);
                }
            });
        }
    }

    private void drawTurrets() {
        PlotData data = module.plotData(view.owner());
        int level = data == null ? 0 : data.turretLevel();
        List<BlockVector> spots = view.marker("turret");
        for (int t = 0; t < Math.min(level, spots.size()); t++) {
            BlockVector spot = spots.get(t);
            Location at = new Location(view.world(), spot.getBlockX() + 0.5, spot.getBlockY() + 0.5, spot.getBlockZ() + 0.5);
            turretDisplays.add(view.world().spawn(at, ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setItemStack(modelItem("turret"));
                d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
                d.setTransformation(new Transformation(new Vector3f(TURRET_LIFT), new Quaternionf(),
                        new Vector3f(TURRET_SCALE, TURRET_SCALE, TURRET_SCALE), new Quaternionf()));
            }));
        }
    }

    /** Turns turret {@code t} to face along {@code dir} (smoothly). */
    private void aimTurret(int t, Vector dir) {
        if (t >= turretDisplays.size()) {
            return;
        }
        ItemDisplay d = turretDisplays.get(t);
        float yaw = (float) (Math.atan2(dir.getX(), dir.getZ()) + Math.PI);
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(4);
        d.setTransformation(new Transformation(new Vector3f(TURRET_LIFT), new Quaternionf().rotateY(yaw),
                new Vector3f(TURRET_SCALE, TURRET_SCALE, TURRET_SCALE), new Quaternionf()));
    }

    /** The turret count changed (an upgrade): draw them again. */
    void redrawTurrets() {
        for (ItemDisplay t : turretDisplays) {
            t.remove();
        }
        turretDisplays.clear();
        if (drawn) {
            drawTurrets();
        }
    }

    static UUID ownerOf(Factory f) {
        return f.view.owner();
    }
}
