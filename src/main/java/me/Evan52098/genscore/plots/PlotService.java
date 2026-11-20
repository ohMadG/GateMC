package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The plot lifecycle: create on join, remove after quit, recover after a crash.
 *
 * <p>Crash safety: a slot's {@code gens_slots} row is written <i>before</i> anything is built on it and deleted only
 * <i>after</i> it has been cleared. So after a crash, every slot that might hold blocks has a row, and start-up clears
 * those slots before they can be handed out again. Plot blocks are never saved (roadmap rule 4).
 */
public final class PlotService {

    public record SlotRow(int slot, UUID owner) {
    }

    private final Database db;
    private final Logger log;
    private final World world;
    private final PlotSettings.Grid grid;
    private final Supplier<PlotSettings.Live> live;
    private final Supplier<PlotTemplate> template;
    private final SlotAllocator slots;
    private final PlotBuilder builder;
    private final Map<UUID, Plot> plots = new ConcurrentHashMap<>();
    private volatile boolean closing;

    PlotService(Database db, Logger log, World world, PlotSettings.Grid grid, Supplier<PlotSettings.Live> live,
                Supplier<PlotTemplate> template, PlotBuilder builder) {
        this.db = db;
        this.log = log;
        this.world = world;
        this.grid = grid;
        this.live = live;
        this.template = template;
        this.slots = new SlotAllocator(grid.maxSlots());
        this.builder = builder;
    }

    // ------------------------------------------------------------------ lookups

    public @Nullable Plot plotOf(UUID owner) {
        return plots.get(owner);
    }

    /** The plot whose footprint contains this location (any height), or null. */
    public @Nullable Plot plotAt(Location at) {
        if (at.getWorld() == null || !at.getWorld().equals(world)) {
            return null;
        }
        int slot = grid.slotAt(at.getBlockX(), at.getBlockZ());
        if (slot < 0) {
            return null;
        }
        UUID owner = slots.owner(slot);
        Plot plot = owner == null ? null : plots.get(owner);
        return plot != null && plot.slot() == slot ? plot : null;
    }

    /** Where a player arrives on the plot, turned with the factory to face the way the template says. */
    public Location spawnOf(Plot plot) {
        PlotSettings.Lot lot = grid.lot(plot.slot());
        Location spawn = grid.minCorner(world, plot.slot()).add(template.get().spawnOffset(lot.rotation()));
        spawn.setYaw(PlotTemplate.turnYaw(live.get().spawnYaw(), lot.rotation()));
        return spawn;
    }

    /** The hub spawn of the first platform, once it is built; null before that. */
    public @Nullable Location hubSpawn() {
        CompletableFuture<Void> first = platforms.get(0);
        return first != null && first.isDone() && !first.isCompletedExceptionally() ? builder.hubSpawn(world, 0) : null;
    }

    // ------------------------------------------------------------------ platforms

    /** Platform number -> its build (completed once built). Built platforms are listed in gens_platforms. */
    private final Map<Integer, CompletableFuture<Void>> platforms = new ConcurrentHashMap<>();

    /** Builds the platform the first time a lot on it is needed. Completes when it exists. */
    CompletableFuture<Void> ensurePlatform(int platform) {
        CompletableFuture<Void> build = platforms.computeIfAbsent(platform, p -> {
            log.info("Building platform " + p + " (" + grid.perPlatform() + " factory lots).");
            return builder.buildPlatform(world, p).thenCompose(v -> db.asyncRun(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO gens_platforms (id, built_at) VALUES (?, ?) ON CONFLICT (id) DO UPDATE SET built_at = excluded.built_at")) {
                    ps.setInt(1, p);
                    ps.setLong(2, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }));
        });
        return build.whenComplete((v, error) -> {
            if (error != null) {
                log.log(Level.SEVERE, "Could not build platform " + platform + "; it will be tried again", error);
                platforms.remove(platform, build);
            }
        });
    }

    /** Admin: builds the platform floor and hub again (blocks on the lots are not touched). */
    CompletableFuture<Void> rebuildPlatform(int platform) {
        platforms.remove(platform);
        return ensurePlatform(platform);
    }

    /** Admin: clears the plaza and builds the current hub template there again. */
    CompletableFuture<Void> rebuildHub(int platform) {
        return builder.rebuildHub(world, platform);
    }

    CompletableFuture<PlotBuilder.Check> verifyFactory(int slot) {
        return builder.verifyFactory(world, slot);
    }

    CompletableFuture<PlotBuilder.Check> verifyPlatform(int platform) {
        return builder.verifyPlatform(world, platform);
    }

    List<Integer> builtPlatformIds() {
        List<Integer> out = new ArrayList<>();
        platforms.forEach((id, f) -> {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                out.add(id);
            }
        });
        return out;
    }

    int builtPlatforms() {
        int n = 0;
        for (CompletableFuture<Void> f : platforms.values()) {
            if (f.isDone() && !f.isCompletedExceptionally()) {
                n++;
            }
        }
        return n;
    }

    public World world() {
        return world;
    }

    PlotSettings.Grid grid() {
        return grid;
    }

    SlotAllocator.Counts counts() {
        return slots.counts();
    }

    int queuedJobs() {
        return builder.queuedJobs();
    }

    int loadedCount() {
        return plots.size();
    }

    Collection<Plot> loaded() {
        return List.copyOf(plots.values());
    }

    int heldBy(java.util.Set<UUID> owners) {
        int inMap = 0;
        for (UUID o : owners) {
            if (plots.containsKey(o)) {
                inMap++;
            }
        }
        return inMap + slots.heldBy(owners);
    }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Makes sure {@code owner} has a plot and completes when it is built. A plot that is still loaded (the owner
     * rejoined before it was removed) is kept; otherwise a new one is built on the lowest free slot.
     */
    public CompletableFuture<Plot> create(UUID owner) {
        if (closing) {
            return CompletableFuture.failedFuture(new IllegalStateException("The server is stopping."));
        }
        Plot existing = plots.get(owner);
        if (existing != null && existing.keep()) {
            return existing.built();
        }
        int slot = slots.allocate(owner);
        if (slot < 0) {
            return CompletableFuture.failedFuture(new IllegalStateException("Every plot slot is in use."));
        }
        Plot plot = new Plot(owner, slot);
        Plot raced = plots.putIfAbsent(owner, plot);
        if (raced != null) {
            if (raced.keep()) { // two creates for one owner at once: the first wins, this slot was never written
                slots.cancel(slot, owner);
                return raced.built();
            }
            // The old plot started removing between the lookup above and now: replace it with the new one.
            if (!plots.replace(owner, raced, plot)) {
                slots.cancel(slot, owner);
                return CompletableFuture.failedFuture(new IllegalStateException("Your plot is busy, try again."));
            }
        }
        // Platform, then row, then blocks: a crash after the row is written leaves it, and start-up clears the slot.
        ensurePlatform(grid.platformOf(slot)).thenCompose(p -> db.asyncRun(c -> {
                    try (PreparedStatement ps = c.prepareStatement(
                            "INSERT INTO gens_slots (slot, owner, since) VALUES (?, ?, ?) "
                                    + "ON CONFLICT (slot) DO UPDATE SET owner = excluded.owner, since = excluded.since")) {
                        ps.setInt(1, slot);
                        ps.setString(2, owner.toString());
                        ps.setLong(3, System.currentTimeMillis());
                        ps.executeUpdate();
                    }
                }))
                .thenCompose(v -> builder.paste(world, slot))
                .whenComplete((v, error) -> {
                    if (error != null) {
                        log.log(Level.SEVERE, "Could not build plot slot " + slot + " for " + owner, error);
                        plot.failBuild(error);
                        plots.remove(owner, plot);
                        teardown(plot.slot());
                        return;
                    }
                    long removal = plot.finishBuild();
                    if (removal != Plot.NO_REMOVAL) {
                        remove(plot, removal);
                    }
                });
        return plot.built();
    }

    /** The owner left: remove their plot after {@code plots.remove-delay-seconds} unless they come back first. */
    public void ownerLeft(UUID owner) {
        Plot plot = plots.get(owner);
        if (plot == null) {
            return;
        }
        long token = plot.removalToken();
        long delay = live.get().removeDelaySeconds();
        if (delay <= 0) {
            remove(plot, token);
            return;
        }
        plot.setPendingRemoval(Tasks.async.later(() -> remove(plot, token), delay, TimeUnit.SECONDS));
    }

    /** Removes the plot now if {@code token} is still current. Completes when the slot is free again. */
    CompletableFuture<Void> remove(Plot plot, long token) {
        if (!plot.beginRemoval(token)) {
            return CompletableFuture.completedFuture(null);
        }
        plots.remove(plot.owner(), plot);
        return teardown(plot.slot());
    }

    /** Admin reset: clears the plot and builds it again from the current template on the same slot. */
    CompletableFuture<Boolean> rebuild(Plot plot) {
        if (!plot.beginRebuild()) {
            return CompletableFuture.completedFuture(false);
        }
        return builder.clear(world, plot.slot())
                .thenCompose(v -> builder.paste(world, plot.slot()))
                .handle((v, error) -> {
                    if (error != null) {
                        log.log(Level.SEVERE, "Rebuild of plot slot " + plot.slot() + " failed", error);
                    }
                    long removal = plot.finishRebuild();
                    if (removal != Plot.NO_REMOVAL) {
                        remove(plot, removal);
                    }
                    return error == null;
                });
    }

    /** Clears a slot, deletes its row, frees it. A slot whose clear fails stays quarantined until the next start. */
    private CompletableFuture<Void> teardown(int slot) {
        slots.markClearing(slot);
        return builder.clear(world, slot)
                .thenCompose(v -> db.asyncRun(c -> {
                    try (PreparedStatement ps = c.prepareStatement("DELETE FROM gens_slots WHERE slot = ?")) {
                        ps.setInt(1, slot);
                        ps.executeUpdate();
                    }
                }).exceptionally(error -> {
                    // Cleared but the row stayed: harmless, the next start clears an empty slot.
                    log.log(Level.WARNING, "Cleared plot slot " + slot + " but could not delete its row", error);
                    return null;
                }))
                .whenComplete((v, error) -> {
                    if (error != null) {
                        log.log(Level.SEVERE, "Could not clear plot slot " + slot + "; it stays out of use until restart", error);
                    } else {
                        slots.markFree(slot);
                    }
                });
    }

    /**
     * Start-up: every slot with a row may still hold blocks from before a crash or stop. Marks them out of use and
     * clears them in the background. Runs on the enabling thread (database access is allowed there).
     */
    int recover(Runnable firstPlatformReady) throws SQLException {
        List<Integer> built = db.sync(c -> {
            List<Integer> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM gens_platforms");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getInt(1));
                }
            }
            return out;
        });
        for (int id : built) {
            platforms.put(id, CompletableFuture.completedFuture(null));
        }
        List<SlotRow> rows = db.sync(c -> {
            List<SlotRow> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT slot, owner FROM gens_slots");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new SlotRow(rs.getInt(1), UUID.fromString(rs.getString(2))));
                }
            }
            return out;
        });
        for (SlotRow row : rows) {
            if (row.slot() >= slots.capacity()) {
                log.warning("gens_slots has slot " + row.slot() + ", beyond plots.layout.max-platforms; clearing it anyway.");
            }
            slots.markClearing(row.slot()); // out of use from now on
        }
        // Folia: chunk loads and region tasks started while plugins enable never run (tested: slots stayed "clearing"
        // forever). Start the clears from the first global tick, once the regions are running.
        // The first platform is built straight away (not on the first join) so the hub exists for spawning players.
        Tasks.global.later(() -> {
            ensurePlatform(0).thenRun(firstPlatformReady);
            for (SlotRow row : rows) {
                teardown(row.slot());
            }
        }, 20);
        return rows.size();
    }

    /** Shutdown: stop building. Blocks stay in the world and their rows stay, so the next start clears them. */
    void close() {
        closing = true;
        for (Plot plot : plots.values()) {
            plot.setPendingRemoval(null);
        }
    }
}
