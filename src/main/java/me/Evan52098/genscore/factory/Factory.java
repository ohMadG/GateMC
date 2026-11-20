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
        final int special;    // NORMAL, TOKEN, or CACHE + rarity: a drop that pays out at the sell point
        int display = -1;
        boolean placed; // its display has been put in place once (the first move is not animated)

        BeltItem(double pos, Material item, long baseCents, int special) {
            this.pos = pos;
            this.item = item;
            this.baseCents = baseCents;
            this.special = special;
        }

        /** What its display shows: the item, or the token / cache model. */
        String look() {
            return special == NORMAL ? item.name() : special == TOKEN ? "token" : "cache_" + GensPlayerRarity.name(special - CACHE);
        }
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
    private long lastRaidHit;

    // ---- defences (region thread)
    private final List<ItemDisplay> turretDisplays = new ArrayList<>();
    private final Map<Integer, Long> turretReady = new HashMap<>();
    private final Set<UUID> intruders = new HashSet<>();
    private int trapPulse;
    private int turretTick;

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
        closedUntil = System.currentTimeMillis() + module.gateSeconds(view.owner()) * 1000L;
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
        ProgressSettings ps = module.progress();
        var gp = module.gensPlayer(view.owner());
        genTimer += DT;
        if (genTimer >= s.genIntervalSeconds()) {
            BeltItem newest = items.peekLast();
            if (newest == null || newest.pos >= SPAWN_POS + 1.0) { // one item per segment: the gen waits when the start is full
                FactorySettings.Output out = s.tier(tier).pick(random); // a blended tier makes a mix of items
                double luck = 1 + ps.luck().perLevel() * (gp == null ? 0 : gp.luckLevel());
                int special = NORMAL;
                double roll = random.nextDouble();
                if (roll < ps.cacheChance() * luck) {
                    special = CACHE + ps.rollRarity(random);
                } else if (roll < (ps.cacheChance() + ps.tokenChance()) * luck) {
                    special = TOKEN;
                }
                items.addLast(new BeltItem(SPAWN_POS, out.item(), out.valueCents(), special));
                genTimer -= s.genIntervalSeconds();
            } else {
                genTimer = s.genIntervalSeconds();
            }
        }
        // Items move oldest first. Nothing passes a broken segment, and each item keeps a segment behind the one ahead.
        double limit = firstBroken(length) >= 0 ? firstBroken(length) - 0.5 : Double.MAX_VALUE;
        for (BeltItem item : items) {
            item.pos = Math.min(item.pos + speed * DT, limit);
            limit = item.pos - 1.0;
        }
        double boost = 1 + ps.value().perLevel() * (gp == null ? 0 : gp.valueLevel());
        while (!items.isEmpty() && items.peekFirst().pos >= length) {
            BeltItem sold = items.pollFirst();
            releaseDisplay(sold);
            if (sold.special != NORMAL) {
                module.rewards().belt(view.owner(), sold.special);
                continue;
            }
            long value = Math.round(s.saleValue(sold.baseCents, length) * boost);
            pendingCents += value;
            earnings.addLast(new long[]{System.currentTimeMillis(), value});
        }
        itemsOnBelt = items.size();
    }

    private void everySecond(Player owner, FactorySettings s) {
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
        if (++depositCountdown >= s.depositSeconds() && pendingCents > 0 && owner != null) {
            depositCountdown = 0;
            long cents = pendingCents;
            pendingCents = 0;
            module.pay(view.owner(), cents); // online wallet: in memory, saved soon after (no database on this thread)
            var g = module.gensPlayer(view.owner());
            if (g != null) {
                g.addLifetime(cents);
            }
            module.rewards().checkMilestones(owner, this);
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
            String look = item.look();
            if (!look.equals(shownItem.get(item.display))) {
                shownItem.set(item.display, look);
                d.setItemStack(item.special == NORMAL ? new ItemStack(item.item) : modelItem(look));
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
        for (Iterator<Map.Entry<Integer, ItemDisplay>> it = segmentDisplays.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<Integer, ItemDisplay> e = it.next();
            if (isBroken(e.getKey())) {
                e.getValue().remove();
                it.remove();
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
        lastHit[index] = now;
        int hp = health[index] < 0 ? maxHealth : health[index];
        hp -= Math.max(1, damage);
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
        double share = ps.lootShare(raiderData == null ? 0 : raiderData.gearLevel());
        long loot = 0;
        int tokens = 0;
        int caches = 0;
        for (Iterator<BeltItem> it = items.iterator(); it.hasNext(); ) {
            BeltItem item = it.next();
            if (item.pos >= index - 0.5 && item.pos < index + 0.5) {
                it.remove();
                releaseDisplay(item);
                view.world().spawnParticle(org.bukkit.Particle.ITEM, at, 10, 0.3, 0.3, 0.3, 0.08,
                        new ItemStack(item.special == NORMAL ? item.item : Material.SUNFLOWER));
                if (item.special == TOKEN) {
                    tokens++;
                } else if (item.special >= CACHE) {
                    caches++;
                    if (raiderData != null) {
                        raiderData.addCache(item.special - CACHE, 1);
                    }
                } else {
                    loot += Math.round(s.saleValue(item.baseCents, length) * share);
                }
            }
        }
        tokens += ps.tokensPerSegment();
        if (raiderData != null) {
            raiderData.addTokens(tokens);
            if (done == 0) {
                raiderData.addRaidWon();
            }
        }
        if (loot > 0) {
            module.pay(raider.getUniqueId(), loot);
        }
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

    /** Broken segments mend themselves after a while, so an owner who is away is not stuck. */
    private void autoRepair() {
        if (brokenAt == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long after = module.progress().autoRepairSeconds() * 1000L;
        for (int i = 0; i < brokenAt.length; i++) {
            if (brokenAt[i] > 0) {
                BlockVector c = path.segments().get(i).cell();
                view.world().spawnParticle(org.bukkit.Particle.LARGE_SMOKE, c.getBlockX() + 0.5, c.getBlockY() + 0.7,
                        c.getBlockZ() + 0.5, 2, 0.2, 0.1, 0.2, 0.01);
                if (now - brokenAt[i] >= after) {
                    brokenAt[i] = 0;
                    health[i] = -1;
                    segmentsDirty = true;
                }
            }
        }
        if (now - lastRaidHit > 120_000) {
            raidCounts.clear();
        }
    }

    // ------------------------------------------------------------------ defences (region thread)

    private boolean intruder(Player p) {
        return !p.getUniqueId().equals(view.owner()) && !p.hasPermission(FactoryModule.BYPASS) && !p.isDead()
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
        double damage = ps.turretDamage() + ps.turretDamagePerLevel() * (level - 1);
        for (int t = 0; t < active; t++) {
            if (turretReady.getOrDefault(t, 0L) > now) {
                continue;
            }
            BlockVector spot = spots.get(t);
            Location muzzle = new Location(view.world(), spot.getBlockX() + 0.5, spot.getBlockY() + 0.8, spot.getBlockZ() + 0.5);
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
            aimTurret(t, dir);
            Player victim = target;
            UUID owner = view.owner();
            Tasks.entity.run(victim, () -> {
                if (victim.isDead()) {
                    return;
                }
                victim.damage(damage);
                if (victim.isDead() || victim.getHealth() <= 0) {
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
                d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1, 1, 1), new Quaternionf()));
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
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf().rotateY(yaw), new Vector3f(1, 1, 1), new Quaternionf()));
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
