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

    // ---- gate (closedUntil is written by any thread, everything else on the region thread)
    private volatile long closedUntil;
    private volatile boolean gateShut;           // what the world shows now
    private final List<BlockDisplay> slats = new ArrayList<>();
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
        int display = -1;
        boolean placed; // its display has been put in place once (the first move is not animated)

        BeltItem(double pos, Material item, long baseCents) {
            this.pos = pos;
            this.item = item;
            this.baseCents = baseCents;
        }
    }

    private final java.util.Random random = new java.util.Random();
    private final List<Material> shownItem = new ArrayList<>(); // what each item display shows now

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
        long cents = pendingCents;
        pendingCents = 0;
        if (cents > 0) {
            Tasks.async.run(() -> module.pay(view.owner(), cents));
        }
        BossBar b = bar;
        Player owner = Bukkit.getPlayer(view.owner());
        if (b != null && owner != null) {
            Tasks.entity.run(owner, () -> owner.hideBossBar(b));
        }
    }

    // ------------------------------------------------------------------ queries (any thread)

    boolean gateClosed() {
        return System.currentTimeMillis() < closedUntil;
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
        closedUntil = System.currentTimeMillis() + module.settings().gateCloseSeconds() * 1000L;
    }

    /** Owner opens the gate early. */
    void requestOpen() {
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

    private void tick() {
        FactorySettings s = module.settings();
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
            if (p.getUniqueId().equals(view.owner()) || p.hasPermission(FactoryModule.BYPASS)) {
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

    private void spawnDoor(boolean shut) {
        int[] b = doorBox();
        int rows = b[4] - b[1] + 1;
        Location at = new Location(view.world(), b[0], b[1], b[2]);
        for (int k = 0; k < rows; k++) {
            int row = k;
            slats.add(view.world().spawn(at, BlockDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBlock(module.settings().doorBlock().createBlockData());
                d.setBrightness(new Display.Brightness(12, 12));
                d.setTransformation(slatTransform(row, shut));
            }));
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
        Location mid = gateCenter().add(0, height / 2.0 - 0.2, 0);
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
                + "<gray>Right-click: close again\nSneak + right-click: open</gray>")
                : Msg.parse(who + "\n<red><bold>OPEN</bold></red>\n<gray>Right-click to close (" + module.settings().gateCloseSeconds() + "s)</gray>"));
    }

    /** True if this entity is one of the gate's click boxes. */
    boolean isClickBox(Entity e) {
        return e instanceof Interaction && clickBoxes.contains(e);
    }

    private Transformation slatTransform(int row, boolean shut) {
        int[] b = doorBox();
        boolean alongX = b[3] > b[0] || b[5] == b[2] && b[3] == b[0] && view.facing()[1] != 0;
        float width = alongX ? b[3] - b[0] + 1 : b[5] - b[2] + 1;
        float height = b[4] - b[1] + 1;
        float thick = 0.22f;
        float inset = 0.5f - thick / 2;
        float y = shut ? row + 0.02f : height + 0.05f + row * 0.03f;
        float h = shut ? 0.96f : 0.12f;
        Vector3f translation = alongX ? new Vector3f(0.02f, y, inset) : new Vector3f(inset, y, 0.02f);
        Vector3f scale = alongX ? new Vector3f(width - 0.04f, h, thick) : new Vector3f(thick, h, width - 0.04f);
        return new Transformation(translation, new Quaternionf(), scale, new Quaternionf());
    }

    private void animateDoor(boolean shut) {
        for (int k = 0; k < slats.size(); k++) {
            BlockDisplay d = slats.get(k);
            if (!d.isValid()) {
                continue;
            }
            // Rolling up: the bottom slat travels furthest, so stagger them for a roll-up look.
            int delay = shut ? (slats.size() - 1 - k) * 2 : k * 2;
            d.setInterpolationDelay(delay);
            d.setInterpolationDuration(16);
            d.setTransformation(slatTransform(k, shut));
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
        double speed = s.speed(data == null ? 0 : data.speedLevel());
        int tier = data == null ? 0 : data.genLevel();
        genTimer += DT;
        if (genTimer >= s.genIntervalSeconds()) {
            BeltItem newest = items.peekLast();
            if (newest == null || newest.pos >= SPAWN_POS + 1.0) { // one item per segment: the gen waits when the start is full
                FactorySettings.Output out = s.tier(tier).pick(random); // a blended tier makes a mix of items
                items.addLast(new BeltItem(SPAWN_POS, out.item(), out.valueCents()));
                genTimer -= s.genIntervalSeconds();
            } else {
                genTimer = s.genIntervalSeconds();
            }
        }
        for (BeltItem item : items) {
            item.pos += speed * DT;
        }
        while (!items.isEmpty() && items.peekFirst().pos >= length) {
            BeltItem sold = items.pollFirst();
            releaseDisplay(sold);
            long value = s.saleValue(sold.baseCents, length);
            pendingCents += value;
            earnings.addLast(new long[]{System.currentTimeMillis(), value});
        }
        itemsOnBelt = items.size();
    }

    private void everySecond(Player owner, FactorySettings s) {
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
        if (++depositCountdown >= s.depositSeconds() && pendingCents > 0 && owner != null) {
            depositCountdown = 0;
            long cents = pendingCents;
            pendingCents = 0;
            module.pay(view.owner(), cents); // online wallet: in memory, saved soon after (no database on this thread)
        }
        if (owner != null && hasGate()) {
            updateBar(owner, s);
        }
        if (owner != null && view.contains(owner.getLocation().getBlockX(), owner.getLocation().getBlockZ())) {
            Component line = Msg.parse("<gold>+" + Money.compact(incomePerMinute) + "/min</gold>  <gray>|</gray>  <white>Belt "
                    + activeLength() + "/" + path.size() + "</white>  <gray>|</gray>  " + (gateClosed()
                    ? "<green>Gate closed " + gateSecondsLeft() + "s</green>" : "<red>Gate OPEN</red>"));
            Tasks.entity.run(owner, () -> owner.sendActionBar(line));
        }
    }

    private void updateBar(Player owner, FactorySettings s) {
        boolean shut = gateClosed();
        long left = gateSecondsLeft();
        Component title = shut ? Msg.parse("<green>Gate closed</green> <white>" + left + "s</white> <gray>- close it again before it opens</gray>")
                : Msg.parse("<red><bold>GATE OPEN</bold></red> <gray>- press the gate button or /gate</gray>");
        float progress = shut ? Math.min(1f, Math.max(0f, left / (float) s.gateCloseSeconds())) : 1f;
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
        int tier = currentTier();
        drawnTier = tier;
        freeDisplays.clear();
        shownItem.clear();
        Material first = s.tier(tier).item();
        for (int i = 0; i < s.maxDrawnItems(); i++) {
            freeDisplays.add(i);
            shownItem.add(first);
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
        freeDisplays.clear();
        for (BeltItem item : items) {
            item.display = -1;
            item.placed = false;
        }
        drawnLength = -1;
    }

    private void redraw(FactorySettings s) {
        int length = activeLength();
        if (length != drawnLength) {
            drawSegments(length, s);
            drawnLength = length;
        }
        int tier = currentTier();
        if (tier != drawnTier) {
            drawnTier = tier;
            updateMachineLabel(s);
        }
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
            if (shownItem.get(item.display) != item.item) {
                shownItem.set(item.display, item.item);
                d.setItemStack(new ItemStack(item.item));
            }
            d.setInterpolationDelay(0);
            // The first placement jumps straight there; after that every move glides over one update period.
            d.setInterpolationDuration(item.placed ? PERIOD_TICKS : 0);
            d.setTransformation(itemTransform(path.at(item.pos), true));
            item.placed = true;
        }
    }

    /** An item lying flat on the belt at {@code world}, relative to the display's spawn point; hidden when not shown. */
    private Transformation itemTransform(Vector world, boolean shown) {
        Quaternionf flat = new Quaternionf().rotateX((float) Math.toRadians(90));
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
        for (int i = 0; i < length; i++) {
            if (segmentDisplays.containsKey(i)) {
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
            });
            long value = s.itemValue(length, currentTier());
            sellLabel = view.world().spawn(at.clone().add(0.5, 1.4, 0.5), TextDisplay.class, d -> {
                d.setPersistent(false);
                d.addScoreboardTag(TAG);
                d.setBillboard(Display.Billboard.CENTER);
                d.text(Msg.parse("<gold><bold>SELL</bold></gold>\n<white>about " + Money.compact(value) + " each</white>"));
            });
        }
    }

    static String tag() {
        return TAG;
    }

    static UUID ownerOf(Factory f) {
        return f.view.owner();
    }
}
