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
    CompletableFuture<Void> paste(World world, int slot, java.util.UUID owner) {
        return queued(() -> pasteNow(world, slot, owner));
    }

    /** Removes everything on the slot's lot above the platform floor. */
    CompletableFuture<Void> clear(World world, int slot) {
        return queued(() -> clearNow(world, slot));
    }

    private Area lotArea(int slot) {
        PlotSettings.Lot lot = grid.lot(slot);
        return new Area(lot.minX(), lot.minZ(), lot.minX() + grid.size() - 1, lot.minZ() + grid.size() - 1);
    }

    private CompletableFuture<Void> pasteNow(World world, int slot, java.util.UUID owner) {
        PlotSettings.Lot lot = grid.lot(slot);
        PlotTemplate t = template.get();
        List<PlotTemplate.TemplateBlock> blocks = new ArrayList<>(t.blocks(lot.rotation()));
        if (t.builtIn()) {
            blocks.addAll(t.turned(PlotProps.blocks(t.footprint(), -1, PlotProps.level(owner)), lot.rotation()));
        }
        blocks = PlotStyles.apply(owner, blocks);
        return place(world, lotArea(slot), lot.minX(), grid.floorY(), lot.minZ(), blocks, slot);
    }

    CompletableFuture<Void> addProps(World world, int slot, java.util.UUID owner, int fromExclusive, int toInclusive) {
        return queued(() -> {
            PlotSettings.Lot lot = grid.lot(slot);
            PlotTemplate t = template.get();
            if (!t.builtIn()) {
                return CompletableFuture.completedFuture(null);
            }
            List<PlotTemplate.TemplateBlock> props = PlotStyles.apply(owner,
                    t.turned(PlotProps.blocks(t.footprint(), fromExclusive, toInclusive), lot.rotation()));
            if (props.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return place(world, lotArea(slot), lot.minX(), grid.floorY(), lot.minZ(), props, null);
        });
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
                .thenCompose(v -> place(world, area, 0, grid.floorY() - 1, 0, platformDecor(platform), null))
                .thenCompose(v -> pasteHub(world, platform));
    }

    private static final int[][] DECOR_ARMS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};

    private List<PlotTemplate.TemplateBlock> platformDecor(int platform) {
        java.util.Map<Long, PlotTemplate.TemplateBlock> out = new java.util.LinkedHashMap<>();
        int cx = grid.centerX(platform);
        int cz = grid.centerZ(platform);
        int size = grid.size();
        int gap = grid.gap();
        int c0 = grid.roadHalf() + 1;
        int c1 = grid.roadHalf() + size;
        java.util.function.BiConsumer<int[], String> put = (pos, data) -> out.put(
                ((long) (pos[0] + 4096) << 32) | ((long) (pos[1] + 512) << 16) | (pos[2] + 4096 & 0xffffL),
                new PlotTemplate.TemplateBlock(pos[0], pos[1], pos[2], org.bukkit.Bukkit.createBlockData("minecraft:" + data)));
        for (int[] d : DECOR_ARMS) {
            for (int sign : new int[]{1, -1}) {
                int px = -d[1] * sign;
                int pz = d[0] * sign;
                for (int k = 0; k + 1 < grid.lotsPerSide(); k++) {
                    int g0 = grid.plazaReach() + k * (size + gap) + size;
                    for (int ga = 0; ga < gap; ga++) {
                        int along = g0 + ga;
                        for (int c = c0; c <= c1; c++) {
                            int x = cx + d[0] * along + px * c;
                            int z = cz + d[1] * along + pz * c;
                            boolean mid = ga == gap / 2;
                            put.accept(new int[]{x, 0, z}, mid ? "polished_andesite" : "gray_concrete");
                            if (c == c0) {
                                for (int y = 1; y <= 4; y++) {
                                    put.accept(new int[]{x, y, z}, "barrier");
                                }
                                continue;
                            }
                            int depth = c - c0;
                            if (mid) {
                                if (depth % 8 == 4) {
                                    for (int y = 1; y <= 5; y++) {
                                        put.accept(new int[]{x, y, z}, y == 1 ? "chiseled_polished_blackstone" : "polished_deepslate");
                                    }
                                    put.accept(new int[]{x, 6, z}, depth > 16 ? "campfire[lit=true]" : "lantern[hanging=false]");
                                } else if (depth == 1) {
                                    put.accept(new int[]{x, 1, z}, "potted_flowering_azalea_bush");
                                }
                            } else {
                                put.accept(new int[]{x, 1, z}, depth % 3 == 0 ? "flowering_azalea_leaves[persistent=true]" : "azalea_leaves[persistent=true]");
                                if (depth >= 2) {
                                    put.accept(new int[]{x, ga == 0 ? 3 : 5, z}, ga == 0 ? "waxed_copper_block" : "iron_block");
                                }
                            }
                        }
                    }
                }
            }
        }
        int island = grid.plazaReach() + size + gap + size / 2 + 4;
        for (int qx : new int[]{1, -1}) {
            for (int qz : new int[]{1, -1}) {
                decorIsland(put, cx + qx * island, cz + qz * island, qx, qz);
                int far = grid.reach() - 10;
                int near = grid.roadHalf() + size + 16;
                decorCrane(put, cx + qx * far, cz + qz * near, 0, -qz);
                decorTanks(put, cx + qx * near, cz + qz * (grid.reach() - 12));
            }
        }
        decorArms(put, cx, cz);
        List<PlotTemplate.TemplateBlock> list = new ArrayList<>(out.values());
        list.sort(java.util.Comparator.comparingInt(PlotTemplate.TemplateBlock::y));
        return list;
    }

    private void decorDisc(java.util.function.BiConsumer<int[], String> put, int ix, int iz, int base, double radius) {
        int r = (int) Math.ceil(radius);
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) {
                    continue;
                }
                put.accept(new int[]{ix + dx, base, iz + dz}, d > radius - 1.2 ? "gray_concrete" : (dx * 3 + dz) % 7 == 0 ? "polished_andesite" : "smooth_stone");
                int under = (int) Math.round((radius - d) / 2.0);
                for (int y = 1; y <= under; y++) {
                    put.accept(new int[]{ix + dx, base - y, iz + dz}, y == under ? "cobbled_deepslate" : "deepslate");
                }
            }
        }
    }

    private void decorCrane(java.util.function.BiConsumer<int[], String> put, int ix, int iz, int jx, int jz) {
        int base = -6;
        decorDisc(put, ix, iz, base, 8.5);
        int top = base + 34;
        for (int y = base + 1; y <= top; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    boolean corner = dx != 0 && dz != 0;
                    boolean ring = (y - base) % 4 == 0;
                    if (dx == 0 && dz == 0) {
                        if (y <= base + 2) {
                            put.accept(new int[]{ix, y, iz}, "polished_deepslate");
                        }
                        continue;
                    }
                    if (corner || ring) {
                        put.accept(new int[]{ix + dx, y, iz + dz}, y <= base + 2 ? "polished_deepslate" : "yellow_concrete");
                    }
                }
            }
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                put.accept(new int[]{ix + dx, top + 1, iz + dz}, "white_concrete");
                put.accept(new int[]{ix + dx, top + 2, iz + dz}, dx == 0 && dz == 0 ? "white_concrete" : "light_blue_stained_glass");
                put.accept(new int[]{ix + dx, top + 3, iz + dz}, "white_concrete");
            }
        }
        int jib = 18;
        for (int k = -7; k <= jib; k++) {
            int x = ix + jx * k;
            int z = iz + jz * k;
            put.accept(new int[]{x, top + 4, z}, (k / 2) % 2 == 0 ? "yellow_concrete" : "black_concrete");
            if (k > 1 && k % 3 == 0) {
                put.accept(new int[]{x, top + 5, z}, "iron_bars");
            }
        }
        for (int y = top + 4; y <= top + 9; y++) {
            put.accept(new int[]{ix, y, iz}, y == top + 9 ? "red_concrete" : "yellow_concrete");
        }
        put.accept(new int[]{ix, top + 10, iz}, "end_rod[facing=up]");
        for (int k = -7; k >= -8; k--) {
            for (int y = top + 1; y <= top + 3; y++) {
                put.accept(new int[]{ix + jx * k, y, iz + jz * k}, "polished_blackstone");
            }
        }
        int hx = ix + jx * (jib - 2);
        int hz = iz + jz * (jib - 2);
        for (int y = top + 3; y >= top - 6; y--) {
            put.accept(new int[]{hx, y, hz}, "iron_chain[axis=y]");
        }
        int lx = jz != 0 ? 1 : 0;
        int lz = jx != 0 ? 1 : 0;
        for (int along = -3; along <= 3; along++) {
            for (int side = -1; side <= 1; side++) {
                for (int y = top - 9; y <= top - 7; y++) {
                    int x = hx + lx * along + jx * side;
                    int z = hz + lz * along + jz * side;
                    boolean rib = Math.abs(along) == 3 || y == top - 7;
                    put.accept(new int[]{x, y, z}, rib ? "orange_terracotta" : "orange_concrete");
                }
            }
        }
        for (int a = 0; a < 360; a += 45) {
            int dx = (int) Math.round(Math.cos(Math.toRadians(a)) * 6.5);
            int dz = (int) Math.round(Math.sin(Math.toRadians(a)) * 6.5);
            if (a % 90 == 0) {
                put.accept(new int[]{ix + dx, base + 1, iz + dz}, "polished_deepslate");
                put.accept(new int[]{ix + dx, base + 2, iz + dz}, "lantern[hanging=false]");
            } else {
                for (int y = 1; y <= 2; y++) {
                    for (int s = 0; s <= 1; s++) {
                        put.accept(new int[]{ix + dx + s, base + y, iz + dz}, (a / 45 + s) % 2 == 0 ? "blue_concrete" : "red_concrete");
                    }
                }
            }
        }
    }

    private void decorTanks(java.util.function.BiConsumer<int[], String> put, int ix, int iz) {
        int base = -10;
        decorDisc(put, ix, iz, base, 10.5);
        int[][] tanks = {{-4, -3, 11}, {4, -3, 9}, {0, 4, 13}};
        for (int[] t : tanks) {
            int tx = ix + t[0];
            int tz = iz + t[1];
            int h = t[2];
            for (int y = 1; y <= h + 2; y++) {
                double r = y <= h ? 3.2 : y == h + 1 ? 2.3 : 1.2;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        double d = Math.sqrt(dx * dx + dz * dz);
                        if (d > r) {
                            continue;
                        }
                        boolean wall = d > r - 1 || y > h;
                        if (!wall) {
                            continue;
                        }
                        String b = y > h ? "light_gray_concrete" : y % 4 == 0 ? "light_gray_concrete" : y == 1 ? "polished_deepslate" : "white_concrete";
                        put.accept(new int[]{tx + dx, base + y, tz + dz}, b);
                    }
                }
            }
            put.accept(new int[]{tx, base + h + 3, tz}, "lightning_rod[facing=up]");
            for (int y = 2; y <= h; y += 1) {
                put.accept(new int[]{tx + 3, base + y, tz}, y % 2 == 0 ? "iron_bars" : "iron_bars");
            }
        }
        for (int x = -4; x <= 4; x++) {
            put.accept(new int[]{ix + x, base + 3, iz - 3}, "waxed_copper_grate");
        }
        for (int z = -3; z <= 4; z++) {
            put.accept(new int[]{ix, base + 4, iz + z}, "waxed_copper_grate");
        }
        for (int a = 0; a < 360; a += 60) {
            int dx = (int) Math.round(Math.cos(Math.toRadians(a)) * 9);
            int dz = (int) Math.round(Math.sin(Math.toRadians(a)) * 9);
            put.accept(new int[]{ix + dx, base + 1, iz + dz}, "polished_deepslate");
            put.accept(new int[]{ix + dx, base + 2, iz + dz}, "lantern[hanging=false]");
        }
    }

    private void decorArms(java.util.function.BiConsumer<int[], String> put, int cx, int cz) {
        int reach = grid.reach();
        int half = grid.roadHalf();
        for (int[] d : DECOR_ARMS) {
            int px = -d[1];
            int pz = d[0];
            for (int along = grid.plazaReach() + 14; along < reach - 4; along += 28) {
                for (int y = -1; y >= -40; y--) {
                    int w = y < -30 ? 0 : 1;
                    for (int a = -w; a <= w; a++) {
                        for (int b = -w; b <= w; b++) {
                            int x = cx + d[0] * (along + a) + px * b;
                            int z = cz + d[1] * (along + a) + pz * b;
                            put.accept(new int[]{x, y, z}, y % 6 == 0 ? "deepslate_tiles" : "polished_deepslate");
                        }
                    }
                }
                for (int side : new int[]{-half, half}) {
                    for (int y = -1; y >= -6; y--) {
                        put.accept(new int[]{cx + d[0] * along + px * side, y, cz + d[1] * along + pz * side}, "iron_chain[axis=y]");
                    }
                    put.accept(new int[]{cx + d[0] * along + px * side, -7, cz + d[1] * along + pz * side}, "lantern[hanging=true]");
                }
            }
            int end = reach - 1;
            for (int side : new int[]{-(half + 2), half + 2}) {
                for (int y = 1; y <= 9; y++) {
                    put.accept(new int[]{cx + d[0] * end + px * side, y, cz + d[1] * end + pz * side}, y == 1 ? "chiseled_polished_blackstone" : "polished_deepslate");
                }
                put.accept(new int[]{cx + d[0] * end + px * side, 10, cz + d[1] * end + pz * side}, "lantern[hanging=false]");
            }
            for (int c = -(half + 2); c <= half + 2; c++) {
                int x = cx + d[0] * end + px * c;
                int z = cz + d[1] * end + pz * c;
                put.accept(new int[]{x, 8, z}, ((c + 20) / 2) % 2 == 0 ? "yellow_concrete" : "black_concrete");
                put.accept(new int[]{x, 9, z}, "polished_deepslate");
                if (Math.abs(c) <= half && c % 2 == 0) {
                    put.accept(new int[]{x, 7, z}, "sea_lantern");
                }
            }
            String bars = d[0] != 0 ? "iron_bars[north=true,south=true]" : "iron_bars[east=true,west=true]";
            for (int c = -half; c <= half; c++) {
                int x = cx + d[0] * reach + px * c;
                int z = cz + d[1] * reach + pz * c;
                put.accept(new int[]{x, 1, z}, (c & 1) == 0 ? "yellow_concrete" : "black_concrete");
                put.accept(new int[]{x, 2, z}, bars);
            }
        }
    }

    private void decorIsland(java.util.function.BiConsumer<int[], String> put, int ix, int iz, int qx, int qz) {
        int base = -8;
        for (int dx = -14; dx <= 14; dx++) {
            for (int dz = -14; dz <= 14; dz++) {
                double r = Math.sqrt(dx * dx + dz * dz);
                if (r > 13.5) {
                    continue;
                }
                put.accept(new int[]{ix + dx, base, iz + dz}, r > 12 ? "gray_concrete" : (dx + dz) % 5 == 0 ? "andesite" : "stone");
                int under = (int) Math.round((13.5 - r) / 2.2);
                for (int y = 1; y <= under; y++) {
                    put.accept(new int[]{ix + dx, base - y, iz + dz}, y == under ? "cobbled_deepslate" : "deepslate");
                }
            }
        }
        for (int y = 1; y <= 26; y++) {
            double t = y / 26.0;
            double radius = 8.5 - 4.2 * Math.sin(Math.PI * t * 0.85);
            for (int dx = -10; dx <= 10; dx++) {
                for (int dz = -10; dz <= 10; dz++) {
                    double r = Math.sqrt(dx * dx + dz * dz);
                    if (r >= radius - 0.5 && r <= radius + 0.5) {
                        put.accept(new int[]{ix + dx, base + y, iz + dz}, y % 7 == 0 ? "light_gray_concrete" : "white_concrete");
                    }
                }
            }
            if (y == 26) {
                for (int a = 0; a < 360; a += 30) {
                    int dx = (int) Math.round(Math.cos(Math.toRadians(a)) * (radius - 1.5));
                    int dz = (int) Math.round(Math.sin(Math.toRadians(a)) * (radius - 1.5));
                    put.accept(new int[]{ix + dx, base + y - 1, iz + dz}, "hay_block[axis=y]");
                    put.accept(new int[]{ix + dx, base + y, iz + dz}, "campfire[lit=true,signal_fire=true]");
                }
            }
        }
        int sx = ix - qx * 9;
        int sz = iz + qz * 8;
        for (int y = 1; y <= 32; y++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    String b = y <= 3 ? "polished_deepslate" : (y >= 24 && y <= 29) ? ((y / 2) % 2 == 0 ? "red_concrete" : "white_concrete") : "bricks";
                    if (y == 32 && dx == 0 && dz == 0) {
                        b = "campfire[lit=true,signal_fire=true]";
                    } else if (y == 31 && dx == 0 && dz == 0) {
                        b = "hay_block[axis=y]";
                    }
                    put.accept(new int[]{sx + dx, base + y, sz + dz}, b);
                }
            }
        }
        for (int y = 1; y <= 4; y++) {
            put.accept(new int[]{ix + qx * 10, base + y, iz - qz * 9}, y == 4 ? "lantern[hanging=false]" : "polished_deepslate");
        }
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
