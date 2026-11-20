package me.Evan52098.genscore.plots;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and clears factories, and builds the shared platform under them, a slice at a time so no build costs more
 * than one slice of a tick.
 *
 * <p>Folia: every block change runs on the region thread that owns the area ({@link Tasks.region}). The area's chunks
 * are loaded first ({@code getChunkAtAsync}) and held with plugin chunk tickets while the work runs, so the region stays
 * loaded between slices even with nobody near. A platform fits in one region section (see {@link PlotSettings.Grid}),
 * so one region thread owns every block of it.
 */
final class PlotBuilder {

    /** One piece of tick-spread work. Returns true when it has finished. */
    private interface Step {
        boolean run();
    }

    /** A block-column rectangle, inclusive. */
    private record Area(int x0, int z0, int x1, int z1) {
        Location anchor(World world, int y) {
            return new Location(world, (x0 + x1) / 2, y, (z0 + z1) / 2);
        }
    }

    private final Plugin plugin;
    private final Logger log;
    private final PlotSettings.Grid grid;
    private final Supplier<PlotSettings.Live> live;
    private final Supplier<PlotTemplate> template;
    private final Supplier<PlotTemplate> hub;
    private final AtomicBoolean ticketWarned = new AtomicBoolean();

    PlotBuilder(Plugin plugin, PlotSettings.Grid grid, Supplier<PlotSettings.Live> live, Supplier<PlotTemplate> template,
                Supplier<PlotTemplate> hub) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.grid = grid;
        this.live = live;
        this.template = template;
        this.hub = hub;
    }

    // ------------------------------------------------------------------ job queue

    /**
     * At most {@code build.max-concurrent} jobs run at once; the rest wait in order. Loading an area's chunks also
     * loads (and on first use generates) a ring of chunks around it, so starting many at once runs the server out of
     * memory. Tested: 112 clears started together at start-up filled a 2 GB heap.
     */
    private final ArrayDeque<Runnable> waiting = new ArrayDeque<>();
    private int running;

    private CompletableFuture<Void> queued(Supplier<CompletableFuture<Void>> job) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Runnable start = () -> {
            CompletableFuture<Void> f;
            try {
                f = job.get();
            } catch (Throwable t) {
                f = CompletableFuture.failedFuture(t);
            }
            f.whenComplete((v, e) -> {
                finished();
                if (e != null) {
                    result.completeExceptionally(e);
                } else {
                    result.complete(null);
                }
            });
        };
        boolean now;
        synchronized (this) {
            now = running < live.get().maxConcurrent();
            if (now) {
                running++;
            } else {
                waiting.add(start);
            }
        }
        if (now) {
            start.run();
        }
        return result;
    }

    private void finished() {
        Runnable next;
        synchronized (this) {
            next = waiting.poll();
            if (next == null) {
                running--;
            }
        }
        if (next != null) {
            next.run();
        }
    }

    synchronized int queuedJobs() {
        return waiting.size();
    }

    // ------------------------------------------------------------------ factories

    /** Builds the factory template on the slot's lot, turned to face its road. */
    CompletableFuture<Void> paste(World world, int slot) {
        return queued(() -> pasteNow(world, slot));
    }

    /** Removes everything on the slot's lot above the platform floor. */
    CompletableFuture<Void> clear(World world, int slot) {
        return queued(() -> clearNow(world, slot));
    }

    private Area lotArea(int slot) {
        PlotSettings.Lot lot = grid.lot(slot);
        return new Area(lot.minX(), lot.minZ(), lot.minX() + grid.size() - 1, lot.minZ() + grid.size() - 1);
    }

    private CompletableFuture<Void> pasteNow(World world, int slot) {
        PlotSettings.Lot lot = grid.lot(slot);
        List<PlotTemplate.TemplateBlock> blocks = template.get().blocks(lot.rotation());
        return place(world, lotArea(slot), lot.minX(), grid.floorY(), lot.minZ(), blocks, slot);
    }

    private CompletableFuture<Void> clearNow(World world, int slot) {
        int height = Math.max(live.get().clearHeight(), template.get().height());
        return wipe(world, lotArea(slot), grid.floorY(), grid.floorY() + height, false, slot);
    }

    // ------------------------------------------------------------------ platforms

    /**
     * Builds platform {@code p}: the plaza, four roads and a base under every lot, one block below {@code floor-y}, then
     * the hub template on the plaza. Lots are left empty; factories are built on them as players join.
     */
    CompletableFuture<Void> buildPlatform(World world, int platform) {
        return queued(() -> buildPlatformNow(world, platform));
    }

    /** Clears the plaza above the floor and builds the current hub template there again (after a hub change). */
    CompletableFuture<Void> rebuildHub(World world, int platform) {
        return queued(() -> {
            Area plaza = plazaArea(platform);
            int height = Math.max(live.get().clearHeight(), hub.get().height());
            return wipe(world, plaza, grid.floorY(), grid.floorY() + height, true, null).thenCompose(v -> pasteHub(world, platform));
        });
    }

    private Area platformArea(int platform) {
        int cx = grid.centerX(platform);
        int cz = grid.centerZ(platform);
        int r = grid.reach();
        return new Area(cx - r, cz - r, cx + r, cz + r);
    }

    private Area plazaArea(int platform) {
        int cx = grid.centerX(platform);
        int cz = grid.centerZ(platform);
        int h = grid.plazaHalf();
        return new Area(cx - h, cz - h, cx + h, cz + h);
    }

    /** The platform's floor blocks, one layer below floor-y. */
    private List<PlotTemplate.TemplateBlock> platformFloor(int platform) {
        PlotSettings.Platform look = live.get().platform();
        BlockData plaza = look.plaza().createBlockData();
        BlockData road = look.road().createBlockData();
        BlockData lotBase = look.lot().createBlockData();
        int cx = grid.centerX(platform);
        int cz = grid.centerZ(platform);
        int h = grid.plazaHalf();
        int w = grid.roadWidth() / 2;
        List<PlotTemplate.TemplateBlock> out = new ArrayList<>();
        // Positions here are absolute; place() is called with a zero origin.
        for (int x = cx - h; x <= cx + h; x++) {
            for (int z = cz - h; z <= cz + h; z++) {
                out.add(new PlotTemplate.TemplateBlock(x, 0, z, plaza));
            }
        }
        for (int along = h + 1; along <= grid.reach(); along++) {
            for (int across = -w; across <= w; across++) {
                out.add(new PlotTemplate.TemplateBlock(cx + along, 0, cz + across, road));
                out.add(new PlotTemplate.TemplateBlock(cx - along, 0, cz + across, road));
                out.add(new PlotTemplate.TemplateBlock(cx + across, 0, cz + along, road));
                out.add(new PlotTemplate.TemplateBlock(cx + across, 0, cz - along, road));
            }
        }
        int first = platform * grid.perPlatform();
        for (int slot = first; slot < first + grid.perPlatform(); slot++) {
            PlotSettings.Lot lot = grid.lot(slot);
            for (int x = 0; x < grid.size(); x++) {
                for (int z = 0; z < grid.size(); z++) {
                    out.add(new PlotTemplate.TemplateBlock(lot.minX() + x, 0, lot.minZ() + z, lotBase));
                }
            }
        }
        return out;
    }

    private CompletableFuture<Void> buildPlatformNow(World world, int platform) {
        Area area = platformArea(platform);
        return place(world, area, 0, grid.floorY() - 1, 0, platformFloor(platform), null)
                .thenCompose(v -> pasteHub(world, platform));
    }

    private CompletableFuture<Void> pasteHub(World world, int platform) {
        PlotTemplate h = hub.get();
        if (h == null || h.blocks(org.bukkit.block.structure.StructureRotation.NONE).isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        Area plaza = plazaArea(platform);
        int width = plaza.x1() - plaza.x0() + 1;
        int x0 = plaza.x0() + Math.max(0, (width - h.sizeX()) / 2);
        int z0 = plaza.z0() + Math.max(0, (width - h.sizeZ()) / 2);
        return place(world, plaza, x0, grid.floorY(), z0, h.blocks(org.bukkit.block.structure.StructureRotation.NONE), null);
    }

    /** Where the hub's spawn marker (or the plaza centre) is, for players with nowhere else to go. */
    Location hubSpawn(World world, int platform) {
        PlotTemplate h = hub.get();
        Area plaza = plazaArea(platform);
        int width = plaza.x1() - plaza.x0() + 1;
        var spawn = h.marker("spawn", org.bukkit.block.structure.StructureRotation.NONE);
        if (!spawn.isEmpty()) {
            int x0 = plaza.x0() + Math.max(0, (width - h.sizeX()) / 2);
            int z0 = plaza.z0() + Math.max(0, (width - h.sizeZ()) / 2);
            var v = spawn.get(0);
            return new Location(world, x0 + v.getX() + 0.5, grid.floorY() + v.getY(), z0 + v.getZ() + 0.5);
        }
        return new Location(world, grid.centerX(platform) + 0.5, grid.floorY(), grid.centerZ(platform) + 0.5);
    }

    // ------------------------------------------------------------------ verification (admin / tests)

    /** Result of a verify: blocks that differ from what was built, and whether the factory's front looks onto road. */
    record Check(int checked, int wrong, boolean frontOnRoad, List<String> examples) {
        Check(int checked, int wrong, boolean frontOnRoad) {
            this(checked, wrong, frontOnRoad, List.of());
        }
    }

    /**
     * Compares a built factory with its template (turned for its lot) on the lot's region thread, and checks that the
     * block just outside the middle of its front edge, one below the floor, is road.
     */
    CompletableFuture<Check> verifyFactory(World world, int slot) {
        PlotSettings.Lot lot = grid.lot(slot);
        Area area = lotArea(slot);
        List<PlotTemplate.TemplateBlock> blocks = template.get().blocks(lot.rotation());
        Material road = live.get().platform().road();
        return loadChunks(world, area).thenCompose(v -> {
            CompletableFuture<Check> out = new CompletableFuture<>();
            Tasks.region.run(area.anchor(world, grid.floorY()), () -> {
                try {
                    int wrong = 0;
                    List<String> examples = new ArrayList<>();
                    for (PlotTemplate.TemplateBlock b : blocks) {
                        Block at = world.getBlockAt(lot.minX() + b.x(), grid.floorY() + b.y(), lot.minZ() + b.z());
                        if (!at.getBlockData().equals(b.data())) {
                            wrong++;
                            if (examples.size() < 3) {
                                examples.add(at.getX() + "," + at.getY() + "," + at.getZ() + " want " + b.data().getAsString()
                                        + " got " + at.getBlockData().getAsString());
                            }
                        }
                    }
                    int s = grid.size() - 1;
                    int mid = grid.size() / 2;
                    // The template's front middle is (mid, s), facing +Z; turn both the spot and the direction.
                    int[] spot;
                    int[] facing;
                    switch (lot.rotation()) {
                        case CLOCKWISE_90 -> { spot = new int[]{s - s, mid}; facing = new int[]{-1, 0}; }
                        case CLOCKWISE_180 -> { spot = new int[]{s - mid, s - s}; facing = new int[]{0, -1}; }
                        case COUNTERCLOCKWISE_90 -> { spot = new int[]{s, s - mid}; facing = new int[]{1, 0}; }
                        default -> { spot = new int[]{mid, s}; facing = new int[]{0, 1}; }
                    }
                    int rx = lot.minX() + spot[0] + facing[0];
                    int rz = lot.minZ() + spot[1] + facing[1];
                    boolean onRoad = world.getBlockAt(rx, grid.floorY() - 1, rz).getType() == road;
                    out.complete(new Check(blocks.size(), wrong, onRoad, examples));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
            return out;
        });
    }

    /** Compares a platform's floor with what it should be, on the platform's region thread. */
    CompletableFuture<Check> verifyPlatform(World world, int platform) {
        Area area = platformArea(platform);
        List<PlotTemplate.TemplateBlock> floor = platformFloor(platform);
        return loadChunks(world, area).thenCompose(v -> {
            CompletableFuture<Check> out = new CompletableFuture<>();
            Tasks.region.run(area.anchor(world, grid.floorY() - 1), () -> {
                try {
                    tickets(world, area, true);
                    int wrong = 0;
                    for (PlotTemplate.TemplateBlock b : floor) {
                        if (world.getBlockAt(b.x(), grid.floorY() - 1, b.z()).getType() != b.data().getMaterial()) {
                            wrong++;
                        }
                    }
                    tickets(world, area, false);
                    out.complete(new Check(floor.size(), wrong, true));
                } catch (Throwable t) {
                    out.completeExceptionally(t);
                }
            });
            return out;
        });
    }

    // ------------------------------------------------------------------ shared work

    /**
     * Places blocks at (ox + x, oy + y, oz + z), a slice per tick, on the region that owns {@code area}. With
     * {@code holdSlot} (a factory) keeps the chunk holds after the build, until that factory is cleared.
     */
    private CompletableFuture<Void> place(World world, Area area, int ox, int oy, int oz,
                                          List<PlotTemplate.TemplateBlock> blocks, Integer holdSlot) {
        int perTick = live.get().blocksPerTick();
        return loadChunks(world, area).thenCompose(v -> spread(area.anchor(world, oy), new Step() {
            private int next;
            private boolean ticketsTaken;

            @Override
            public boolean run() {
                if (!ticketsTaken) {
                    ticketsTaken = true;
                    tickets(world, area, true);
                    if (holdSlot != null) {
                        heldSlots.add(holdSlot);
                    }
                }
                int end = Math.min(blocks.size(), next + perTick);
                for (; next < end; next++) {
                    PlotTemplate.TemplateBlock b = blocks.get(next);
                    world.getBlockAt(ox + b.x(), oy + b.y(), oz + b.z()).setBlockData(b.data(), false);
                }
                if (next >= blocks.size()) {
                    if (holdSlot == null) {
                        tickets(world, area, false);
                    }
                    return true;
                }
                return false;
            }
        }));
    }

    /**
     * Removes every block in the area from y0 (inclusive) to y1 (exclusive) and every non-player entity above it, then
     * drops the area's chunk tickets.
     */
    private CompletableFuture<Void> wipe(World world, Area area, int fromY, int toY, boolean keepEntities, Integer releaseSlot) {
        int checksPerTick = live.get().clearChecksPerTick();
        int y0 = Math.max(world.getMinHeight(), fromY);
        int y1 = Math.min(world.getMaxHeight(), toY);
        int width = area.x1() - area.x0() + 1;
        int depth = area.z1() - area.z0() + 1;
        return loadChunks(world, area).thenCompose(v -> spread(area.anchor(world, y0), new Step() {
            private int y = y0;
            private int x;
            private int z;
            private boolean started;

            @Override
            public boolean run() {
                if (!started) {
                    started = true;
                    tickets(world, area, true); // also for slots recovered after a crash, released below
                    if (!keepEntities) {
                        BoundingBox box = new BoundingBox(area.x0(), y0, area.z0(), area.x1() + 1, y1 + 64, area.z1() + 1);
                        for (Entity e : world.getNearbyEntities(box)) {
                            if (!(e instanceof Player)) {
                                e.remove();
                            }
                        }
                    }
                }
                for (int n = 0; n < checksPerTick; n++) {
                    if (y >= y1) {
                        tickets(world, area, false);
                        if (releaseSlot != null && heldSlots.remove(releaseSlot)) {
                            tickets(world, area, false); // the hold the factory took when it was built
                        }
                        return true;
                    }
                    Block block = world.getBlockAt(area.x0() + x, y, area.z0() + z);
                    if (PlotTemplate.needsClearing(block.getType())) {
                        block.setType(Material.AIR, false);
                    }
                    if (++x >= width) {
                        x = 0;
                        if (++z >= depth) {
                            z = 0;
                            y++;
                        }
                    }
                }
                return false;
            }
        }));
    }

    /** Runs the step once per tick on the region that owns {@code anchor} until it reports done. */
    private CompletableFuture<Void> spread(Location anchor, Step step) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Tasks.region.timer(anchor, (ScheduledTask task) -> {
            if (done.isDone()) {
                task.cancel();
                return;
            }
            try {
                if (step.run()) {
                    task.cancel();
                    done.complete(null);
                }
            } catch (Throwable t) {
                task.cancel();
                done.completeExceptionally(t);
            }
        }, 1, 1);
        return done;
    }

    private CompletableFuture<Void> loadChunks(World world, Area area) {
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        forEachChunk(area, (cx, cz) -> loads.add(world.getChunkAtAsync(cx, cz, true)));
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    /**
     * How many builds and factories hold each chunk. Plugin chunk tickets are not counted by the server (one per plugin
     * per chunk), and neighbouring lots share chunks, so the ticket is added on the first hold and removed on the last.
     */
    private final java.util.Map<Long, Integer> holds = new java.util.HashMap<>();

    /** Factories whose paste took a chunk hold that their clear must give back. */
    private final java.util.Set<Integer> heldSlots = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Adds or removes holds on the area's chunks. Must run on the area's region thread. */
    private void tickets(World world, Area area, boolean add) {
        forEachChunk(area, (cx, cz) -> {
            long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
            boolean change;
            synchronized (holds) {
                int before = holds.getOrDefault(key, 0);
                int after = Math.max(0, before + (add ? 1 : -1));
                if (after == 0) {
                    holds.remove(key);
                } else {
                    holds.put(key, after);
                }
                change = add ? before == 0 : before == 1;
            }
            if (!change) {
                return;
            }
            try {
                if (add) {
                    world.addPluginChunkTicket(cx, cz, plugin);
                } else {
                    world.removePluginChunkTicket(cx, cz, plugin);
                }
            } catch (RuntimeException e) {
                // Without tickets builds still work; chunks may just unload between slices when nobody is near.
                if (ticketWarned.compareAndSet(false, true)) {
                    log.log(Level.WARNING, "Chunk tickets are not available here; building without them", e);
                }
            }
        });
    }

    private void forEachChunk(Area area, ChunkVisitor visitor) {
        for (int cx = area.x0() >> 4; cx <= area.x1() >> 4; cx++) {
            for (int cz = area.z0() >> 4; cz <= area.z1() >> 4; cz++) {
                visitor.visit(cx, cz);
            }
        }
    }

    private interface ChunkVisitor {
        void visit(int cx, int cz);
    }
}
