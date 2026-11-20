package me.Evan52098.genscore.plots;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Builds and clears plots a slice at a time, so a paste never costs more than one slice of a tick.
 *
 * <p>Folia: every block change runs on the region thread that owns the plot ({@link Tasks.region}). The plot's chunks
 * are loaded first ({@code getChunkAtAsync}) and held with a plugin chunk ticket while the plot exists, so the region
 * stays loaded between slices even with nobody standing on the plot. A plot is kept inside one region section by the
 * grid settings (see {@link PlotSettings.Grid}), so one region thread owns every block of it.
 */
final class PlotBuilder {

    /** One piece of tick-spread work. Returns true when it has finished. */
    private interface Step {
        boolean run();
    }

    private final Plugin plugin;
    private final Logger log;
    private final PlotSettings.Grid grid;
    private final Supplier<PlotSettings.Live> live;
    private final Supplier<PlotTemplate> template;
    private final AtomicBoolean ticketWarned = new AtomicBoolean();

    PlotBuilder(Plugin plugin, PlotSettings.Grid grid, Supplier<PlotSettings.Live> live, Supplier<PlotTemplate> template) {
        this.plugin = plugin;
        this.log = plugin.getLogger();
        this.grid = grid;
        this.live = live;
        this.template = template;
    }

    // ------------------------------------------------------------------ job queue

    /**
     * At most {@code build.max-concurrent} pastes and clears run at once; the rest wait in order. Loading a plot's
     * chunks also loads (and on first use generates) a ring of neighbouring chunks, so starting many at once runs the
     * server out of memory. Tested: 112 clears started together at start-up filled a 2 GB heap.
     */
    private final java.util.ArrayDeque<Runnable> waiting = new java.util.ArrayDeque<>();
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

    // ------------------------------------------------------------------ paste and clear

    /** Places the current template on the slot. Completes on the plot's region thread. */
    CompletableFuture<Void> paste(World world, int slot) {
        return queued(() -> pasteNow(world, slot));
    }

    /** Clears the slot (see {@link #clearNow}). */
    CompletableFuture<Void> clear(World world, int slot) {
        return queued(() -> clearNow(world, slot));
    }

    private CompletableFuture<Void> pasteNow(World world, int slot) {
        PlotTemplate t = template.get();
        int perTick = live.get().blocksPerTick();
        Location min = grid.minCorner(world, slot);
        return loadChunks(world, slot).thenCompose(v -> spread(min, new Step() {
            private int next;
            private boolean ticketsTaken;

            @Override
            public boolean run() {
                if (!ticketsTaken) {
                    ticketsTaken = true;
                    tickets(world, slot, true);
                }
                List<PlotTemplate.TemplateBlock> blocks = t.blocks();
                int end = Math.min(blocks.size(), next + perTick);
                for (; next < end; next++) {
                    PlotTemplate.TemplateBlock b = blocks.get(next);
                    world.getBlockAt(min.getBlockX() + b.x(), min.getBlockY() + b.y(), min.getBlockZ() + b.z())
                            .setBlockData(b.data(), false);
                }
                return next >= blocks.size();
            }
        }));
    }

    /**
     * Removes every block in the slot's volume and every non-player entity above it, then drops the chunk tickets.
     * Completes on the plot's region thread.
     */
    private CompletableFuture<Void> clearNow(World world, int slot) {
        PlotSettings.Live l = live.get();
        int height = Math.max(l.clearHeight(), template.get().height());
        int checksPerTick = l.clearChecksPerTick();
        int size = grid.size();
        int x0 = grid.minX(slot);
        int z0 = grid.minZ(slot);
        int y0 = Math.max(world.getMinHeight(), grid.floorY());
        int y1 = Math.min(world.getMaxHeight(), grid.floorY() + height);
        Location min = grid.minCorner(world, slot);
        return loadChunks(world, slot).thenCompose(v -> spread(min, new Step() {
            private int y = y0;
            private int x;
            private int z;
            private boolean entitiesDone;

            @Override
            public boolean run() {
                if (!entitiesDone) {
                    entitiesDone = true;
                    tickets(world, slot, true); // also for slots recovered after a crash, released below
                    BoundingBox box = new BoundingBox(x0, y0 - 64, z0, x0 + size, y1 + 64, z0 + size);
                    for (Entity e : world.getNearbyEntities(box)) {
                        if (!(e instanceof Player)) {
                            e.remove();
                        }
                    }
                }
                for (int n = 0; n < checksPerTick; n++) {
                    if (y >= y1) {
                        tickets(world, slot, false);
                        return true;
                    }
                    Block block = world.getBlockAt(x0 + x, y, z0 + z);
                    if (PlotTemplate.needsClearing(block.getType())) {
                        block.setType(org.bukkit.Material.AIR, false);
                    }
                    if (++x >= size) {
                        x = 0;
                        if (++z >= size) {
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

    private CompletableFuture<Void> loadChunks(World world, int slot) {
        List<CompletableFuture<Chunk>> loads = new ArrayList<>();
        forEachChunk(slot, (cx, cz) -> loads.add(world.getChunkAtAsync(cx, cz, true)));
        return CompletableFuture.allOf(loads.toArray(CompletableFuture[]::new));
    }

    /** Adds or removes the plugin chunk tickets for the plot. Must run on the plot's region thread. */
    private void tickets(World world, int slot, boolean add) {
        forEachChunk(slot, (cx, cz) -> {
            try {
                if (add) {
                    world.addPluginChunkTicket(cx, cz, plugin);
                } else {
                    world.removePluginChunkTicket(cx, cz, plugin);
                }
            } catch (RuntimeException e) {
                // Without tickets plots still work; their chunks may just unload between slices when nobody is near.
                if (ticketWarned.compareAndSet(false, true)) {
                    log.log(Level.WARNING, "Chunk tickets are not available here; plots are built without them", e);
                }
            }
        });
    }

    private void forEachChunk(int slot, ChunkVisitor visitor) {
        int cx0 = grid.minX(slot) >> 4;
        int cz0 = grid.minZ(slot) >> 4;
        int cx1 = (grid.minX(slot) + grid.size() - 1) >> 4;
        int cz1 = (grid.minZ(slot) + grid.size() - 1) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                visitor.visit(cx, cz);
            }
        }
    }

    private interface ChunkVisitor {
        void visit(int cx, int cz);
    }
}
