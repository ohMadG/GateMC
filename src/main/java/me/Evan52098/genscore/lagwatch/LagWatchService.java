package me.Evan52098.genscore.lagwatch;

import me.Evan52098.genscore.util.DiscordWebhook;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Hopper;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FallingBlock;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Villager;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockRedstoneEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntitySpawnEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.vehicle.VehicleCreateEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * Lag machine watch, ported from BlockvilleCore's LagWatchService: counts redstone, pistons, hopper moves, dispenses,
 * item drops, falling blocks, TNT, breeding, vehicles and portal use per chunk over 10-second windows, and standing
 * piles (items, vehicles, armor stands, frames, mobs, villagers, projectiles, falling blocks, hoppers) every 30
 * seconds; tells staff where, with a click to teleport, and who may be behind it. It only reports.
 *
 * <p>Folia, what changed:
 * <ul>
 *   <li>Events arrive on many region threads at once, so the current window is a concurrent map of atomic counters,
 *       swapped out whole by the global thread every 10 seconds. Judging, alerting state and report ids live on the
 *       global thread only.</li>
 *   <li>Standing piles are counted chunk by chunk <b>on the thread that owns each chunk</b>: loaded chunks are tracked
 *       from chunk load/unload events, and a scan hops through the regions (one region task counts every pending chunk
 *       that region owns, then hands over to the next region), with a timeout so one stuck region cannot stall it.</li>
 *   <li>"The server is running slow" is per region on Folia: {@link RegionTps} measures each player's region, and a
 *       region below {@code slow-tps} for 30 seconds is reported with the busiest places. On Paper the original
 *       server-wide {@code slow-mspt} check runs unchanged.</li>
 *   <li>"Who is near" uses position copies taken on each player's own thread; staff are told on their own threads.</li>
 * </ul>
 * BentoBox island owners ("box of X") are gone; other modules (factions claims) can name an owner through
 * {@link #addOwnerResolver}.
 */
public final class LagWatchService implements Listener {

    public static final String ALERT_PERMISSION = "genscore.antihack.alert";
    public static final String ADMIN_PERMISSION = "genscore.antihack.admin";

    private static final String PREFIX = "&6&lLagWatch &8» ";
    private static final long WINDOW_TICKS = 200L;
    private static final long COUNT_TICKS = 600L;
    private static final long SCAN_TIMEOUT_SECONDS = 15;
    private static final int SCAN_CHAINS = 4;
    private static final long REALERT_MS = 5 * 60_000L;
    private static final long SLOW_REALERT_MS = 10 * 60_000L;
    private static final int SLOW_WINDOWS = 3;
    private static final int SEVERE_FACTOR = 3;
    private static final long BUILDER_MEMORY_MS = 6 * 3_600_000L;
    private static final double NEARBY_BLOCKS = 128.0;
    private static final int RECENT_LIMIT = 200;
    private static final int PLACE_LIMIT = 400;
    private static final LagCheck[] CHECKS = LagCheck.values();
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Set<Material> MACHINE_PARTS = EnumSet.noneOf(Material.class);

    static {
        for (Material material : Material.values()) {
            String name = material.name();
            if (name.endsWith("RAIL") || name.endsWith("PRESSURE_PLATE") || name.startsWith("REDSTONE") || name.endsWith("_BUTTON")) {
                MACHINE_PARTS.add(material);
            }
        }
        for (String name : List.of("REPEATER", "COMPARATOR", "OBSERVER", "PISTON", "STICKY_PISTON", "HOPPER", "DISPENSER", "DROPPER", "CRAFTER", "LEVER", "TNT",
                "SLIME_BLOCK", "HONEY_BLOCK", "TARGET", "DAYLIGHT_DETECTOR", "TRIPWIRE_HOOK", "NOTE_BLOCK", "SCULK_SENSOR", "CALIBRATED_SCULK_SENSOR")) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                MACHINE_PARTS.add(material);
            }
        }
        MACHINE_PARTS.remove(Material.REDSTONE_ORE);
        MACHINE_PARTS.remove(Material.DEEPSLATE_REDSTONE_ORE);
    }

    record ChunkRef(UUID world, int x, int z) {
    }

    /** Counts for one chunk. Written from region threads, read by the global thread: atomics. */
    private static final class Tally {
        final AtomicIntegerArray n = new AtomicIntegerArray(CHECKS.length);
        final AtomicBoolean placed = new AtomicBoolean();
        volatile int bx;
        volatile int by;
        volatile int bz;

        void add(LagCheck check, int x, int y, int z) {
            n.incrementAndGet(check.ordinal());
            place(x, y, z);
        }

        void place(int x, int y, int z) {
            if (placed.compareAndSet(false, true)) {
                bx = x;
                by = y;
                bz = z;
            }
        }

        int get(LagCheck check) {
            return n.get(check.ordinal());
        }
    }

    /** Global thread only. */
    private static final class Heat {
        final int[] mask = new int[CHECKS.length];
        final int[] value = new int[CHECKS.length];
        final long[] alertedAt = new long[CHECKS.length];
        final boolean[] alertedSevere = new boolean[CHECKS.length];
        int bx;
        int by;
        int bz;
    }

    private static final class Builders {
        final Map<UUID, Integer> parts = new ConcurrentHashMap<>();
        volatile long lastAt;
    }

    public record Incident(int id, long at, String world, int x, int y, int z, LagCheck check, int value, int limit, boolean severe, String suspect, String who) {
    }

    private final Plugin plugin;
    private final File logFile;
    private final File overridesFile;
    private final Object logLock = new Object();
    private final DiscordWebhook webhook;
    private final RegionTps regionTps;

    private final AtomicReference<Map<ChunkRef, Tally>> window = new AtomicReference<>(new ConcurrentHashMap<>());
    private volatile Map<ChunkRef, Tally> lastWindow = Map.of();
    private volatile Map<ChunkRef, Tally> lastCount = Map.of();
    private final Map<ChunkRef, Heat> heat = new HashMap<>(); // global thread only
    private final Map<ChunkRef, Builders> builders = new ConcurrentHashMap<>();
    private final Set<ChunkRef> loadedChunks = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Location> places = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, Location> eldest) {
            return size() > PLACE_LIMIT;
        }
    });
    private final AtomicInteger nextId = new AtomicInteger(1);
    private final AtomicBoolean scanning = new AtomicBoolean();
    private final List<Function<Location, String>> ownerResolvers = new CopyOnWriteArrayList<>();
    private int countScans;          // global thread
    private int slowWindows;         // global thread
    private long lastSlowAlertAt;    // global thread
    private volatile List<String> lastBusiestText = List.of();

    private final Deque<Incident> recent = new ConcurrentLinkedDeque<>();
    private final Set<UUID> mutedStaff = ConcurrentHashMap.newKeySet();

    private volatile ConfigurationSection config;
    private volatile boolean enabled;
    private volatile int[] limits = new int[CHECKS.length];
    private volatile Set<String> ignoredWorlds = Set.of();
    private volatile double slowMspt;
    private volatile double slowTps;
    private volatile String webhookUrl = "";

    LagWatchService(Plugin plugin, @Nullable ConfigurationSection config, File moduleFolder, DiscordWebhook webhook, RegionTps regionTps) {
        this.plugin = plugin;
        this.config = config;
        this.webhook = webhook;
        this.regionTps = regionTps;
        this.logFile = new File(plugin.getDataFolder(), "lagwatch.log");
        this.overridesFile = new File(moduleFolder, "settings.yml");
        reloadSettings();
    }

    void start() {
        // Chunks already loaded (Paper; on Folia this may be refused, and load events fill the set as regions load).
        Tasks.global.run(() -> {
            for (World world : Bukkit.getWorlds()) {
                try {
                    for (Chunk chunk : world.getLoadedChunks()) {
                        loadedChunks.add(new ChunkRef(world.getUID(), chunk.getX(), chunk.getZ()));
                    }
                } catch (RuntimeException notHere) {
                    // Folia: not readable from the global thread; ChunkLoadEvent will fill it in.
                }
            }
        });
        Tasks.global.timer(t -> closeWindow(), WINDOW_TICKS, WINDOW_TICKS);
        Tasks.global.timer(t -> countEntities(), COUNT_TICKS, COUNT_TICKS);
        Tasks.async.timer(t -> forgetOldBuilders(), 10, 10, TimeUnit.MINUTES);
    }

    void stop() {
        webhook.stop();
    }

    // ------------------------------------------------------------------ settings

    void config(@Nullable ConfigurationSection section) {
        this.config = section;
        reloadSettings();
    }

    /** config.yml's {@code lagwatch:} section; /lagwatch on|off is kept in modules/lagwatch/settings.yml. */
    public synchronized void reloadSettings() {
        ConfigurationSection c = config;
        boolean on = c == null || c.getBoolean("enabled", true);
        if (overridesFile.isFile()) {
            on = YamlConfiguration.loadConfiguration(overridesFile).getBoolean("enabled", on);
        }
        enabled = on;
        int[] next = new int[CHECKS.length];
        for (LagCheck check : CHECKS) {
            next[check.ordinal()] = c == null ? check.defaultLimit() : Math.max(0, c.getInt("thresholds." + check.key(), check.defaultLimit()));
        }
        limits = next;
        Set<String> worlds = new HashSet<>();
        if (c != null) {
            for (String name : c.getStringList("ignore-worlds")) {
                worlds.add(name.toLowerCase(Locale.ROOT));
            }
        }
        ignoredWorlds = worlds;
        slowMspt = c == null ? 45.0 : c.getDouble("slow-mspt", 45.0);
        slowTps = c == null ? 18.0 : c.getDouble("slow-tps", 18.0);
        webhookUrl = c == null ? "" : c.getString("discord-webhook-url", "");
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** Applies now; the file is written on an async thread. */
    public synchronized void setEnabled(boolean value) {
        enabled = value;
        Tasks.async.run(() -> {
            synchronized (overridesFile) {
                YamlConfiguration overrides = overridesFile.isFile() ? YamlConfiguration.loadConfiguration(overridesFile) : new YamlConfiguration();
                overrides.set("enabled", value);
                try {
                    File parent = overridesFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    overrides.save(overridesFile);
                } catch (IOException e) {
                    plugin.getLogger().warning("[LagWatch] Could not save " + overridesFile.getName() + ": " + e.getMessage());
                }
            }
        });
    }

    public int limit(LagCheck check) {
        return limits[check.ordinal()];
    }

    public boolean toggleAlerts(UUID staff) {
        if (mutedStaff.remove(staff)) {
            return true;
        }
        mutedStaff.add(staff);
        return false;
    }

    public boolean hasWebhook() {
        return DiscordWebhook.usable(webhookUrl);
    }

    /**
     * Lets another module name who owns a place (for example a faction claim), shown as "claim of X". The function is
     * called on the global thread or a command thread, so it must only read thread-safe data; return null for none.
     */
    public void addOwnerResolver(Function<Location, String> resolver) {
        ownerResolvers.add(resolver);
    }

    public List<Incident> recentIncidents(int limit) {
        List<Incident> out = new ArrayList<>(recent);
        Collections.reverse(out);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    public @Nullable Location place(int id) {
        Location at = places.get(id);
        return at == null || at.getWorld() == null ? null : at.clone();
    }

    private boolean watching(World world) {
        return enabled && !ignoredWorlds.contains(world.getName().toLowerCase(Locale.ROOT));
    }

    // ------------------------------------------------------------------ counting (any region thread)

    private void count(LagCheck check, World world, int x, int y, int z) {
        if (limits[check.ordinal()] <= 0 || !watching(world)) {
            return;
        }
        window.get().computeIfAbsent(new ChunkRef(world.getUID(), x >> 4, z >> 4), k -> new Tally()).add(check, x, y, z);
    }

    private void count(LagCheck check, org.bukkit.block.Block block) {
        count(check, block.getWorld(), block.getX(), block.getY(), block.getZ());
    }

    private void count(LagCheck check, @Nullable Location at) {
        if (at != null && at.getWorld() != null) {
            count(check, at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRedstone(BlockRedstoneEvent event) {
        if (event.getOldCurrent() != event.getNewCurrent()) {
            count(LagCheck.REDSTONE, event.getBlock());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        count(LagCheck.PISTONS, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        count(LagCheck.PISTONS, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        if (enabled) {
            count(LagCheck.HOPPER_MOVES, event.getSource().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDispense(BlockDispenseEvent event) {
        count(LagCheck.DISPENSERS, event.getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        count(LagCheck.ITEM_DROPS, event.getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntitySpawn(EntitySpawnEvent event) {
        Entity entity = event.getEntity();
        if (entity instanceof FallingBlock) {
            count(LagCheck.FALLING_SPAWNS, event.getLocation());
        } else if (entity instanceof TNTPrimed) {
            count(LagCheck.TNT, event.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        switch (event.getSpawnReason()) {
            case BREEDING, EGG, DISPENSE_EGG, SPAWNER_EGG, BUILD_SNOWMAN, BUILD_IRONGOLEM -> count(LagCheck.MOB_SPAWNS, event.getLocation());
            default -> {
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleCreate(VehicleCreateEvent event) {
        if (!event.isCancelled()) {
            count(LagCheck.VEHICLE_SPAWNS, event.getVehicle().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPortal(EntityPortalEvent event) {
        count(LagCheck.PORTALS, event.getFrom());
    }

    private void built(@Nullable Player player, @Nullable Location at) {
        if (player == null || at == null || at.getWorld() == null || !watching(at.getWorld())) {
            return;
        }
        Builders entry = builders.computeIfAbsent(new ChunkRef(at.getWorld().getUID(), at.getBlockX() >> 4, at.getBlockZ() >> 4), ref -> new Builders());
        entry.parts.merge(player.getUniqueId(), 1, Integer::sum);
        entry.lastAt = System.currentTimeMillis();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (MACHINE_PARTS.contains(event.getBlockPlaced().getType())) {
            built(event.getPlayer(), event.getBlockPlaced().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityPlace(EntityPlaceEvent event) {
        built(event.getPlayer(), event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHangingPlace(HangingPlaceEvent event) {
        built(event.getPlayer(), event.getEntity().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player player) {
            built(player, event.getEntity().getLocation());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkLoad(ChunkLoadEvent event) {
        loadedChunks.add(new ChunkRef(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onChunkUnload(ChunkUnloadEvent event) {
        loadedChunks.remove(new ChunkRef(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        mutedStaff.remove(event.getPlayer().getUniqueId());
    }

    private void forgetOldBuilders() {
        long cutoff = System.currentTimeMillis() - BUILDER_MEMORY_MS;
        builders.values().removeIf(entry -> entry.lastAt < cutoff);
    }

    // ------------------------------------------------------------------ judging (global thread)

    private Heat heat(ChunkRef ref) {
        return heat.computeIfAbsent(ref, r -> new Heat());
    }

    private void markWindow(LagCheck.Kind kind, Map<ChunkRef, Tally> tallies) {
        for (Heat entry : heat.values()) {
            for (LagCheck check : CHECKS) {
                if (check.kind() == kind) {
                    entry.mask[check.ordinal()] = (entry.mask[check.ordinal()] << 1) & check.windowMask();
                }
            }
        }
        int[] limits = this.limits;
        for (Map.Entry<ChunkRef, Tally> tally : tallies.entrySet()) {
            Tally t = tally.getValue();
            for (LagCheck check : CHECKS) {
                int limit = limits[check.ordinal()];
                int n = t.get(check);
                if (check.kind() != kind || limit <= 0 || n < limit) {
                    continue;
                }
                Heat entry = heat(tally.getKey());
                entry.mask[check.ordinal()] |= 1;
                entry.value[check.ordinal()] = n;
                entry.bx = t.bx;
                entry.by = t.by;
                entry.bz = t.bz;
            }
        }
        judge(kind);
    }

    private void judge(LagCheck.Kind kind) {
        long now = System.currentTimeMillis();
        int[] limits = this.limits;
        Iterator<Map.Entry<ChunkRef, Heat>> it = heat.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<ChunkRef, Heat> row = it.next();
            Heat entry = row.getValue();
            boolean any = false;
            for (LagCheck check : CHECKS) {
                int i = check.ordinal();
                if (entry.mask[i] == 0) {
                    entry.alertedAt[i] = 0L;
                    entry.alertedSevere[i] = false;
                    continue;
                }
                any = true;
                if (check.kind() != kind || limits[i] <= 0 || Integer.bitCount(entry.mask[i]) < check.hitsNeeded() || (entry.mask[i] & 1) == 0) {
                    continue;
                }
                boolean severe = entry.value[i] >= limits[i] * SEVERE_FACTOR;
                boolean due = entry.alertedAt[i] == 0L || now - entry.alertedAt[i] >= REALERT_MS || (severe && !entry.alertedSevere[i]);
                if (!due) {
                    continue;
                }
                entry.alertedAt[i] = now;
                entry.alertedSevere[i] = entry.alertedSevere[i] || severe;
                report(row.getKey(), entry, check, entry.value[i], limits[i], severe);
            }
            if (!any) {
                it.remove();
            }
        }
    }

    /** Global thread, every 10 seconds: closes the event window. */
    private void closeWindow() {
        Map<ChunkRef, Tally> tallies = window.getAndSet(new ConcurrentHashMap<>());
        lastWindow = tallies;
        if (!enabled) {
            return;
        }
        markWindow(LagCheck.Kind.RATE, tallies);
        checkSlow();
    }

    /**
     * Global thread, every 30 seconds: counts the standing piles, each chunk on the thread that owns it. A few chains
     * run at once; each chain's task counts every pending chunk its region owns, then moves to a chunk still pending
     * (another region). When all are done, or after the timeout, the result is judged back on the global thread.
     */
    private void countEntities() {
        if (!enabled || !scanning.compareAndSet(false, true)) {
            return;
        }
        boolean hoppers = limits[LagCheck.HOPPERS.ordinal()] > 0 && (countScans++ % 2 == 0);
        Set<ChunkRef> pending = ConcurrentHashMap.newKeySet();
        for (ChunkRef ref : loadedChunks) {
            World world = Bukkit.getWorld(ref.world());
            if (world != null && watching(world)) {
                pending.add(ref);
            }
        }
        Map<ChunkRef, Tally> result = new ConcurrentHashMap<>();
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                Tasks.global.run(() -> {
                    try {
                        finishCount(result, hoppers);
                    } finally {
                        scanning.set(false);
                    }
                });
            }
        };
        if (pending.isEmpty()) {
            finish.run();
            return;
        }
        AtomicInteger chains = new AtomicInteger();
        int start = Math.min(SCAN_CHAINS, pending.size());
        chains.set(start);
        Iterator<ChunkRef> seeds = new ArrayList<>(pending).iterator();
        for (int i = 0; i < start && seeds.hasNext(); i++) {
            scheduleScan(seeds.next(), pending, result, hoppers, chains, finish);
        }
        Tasks.global.later(() -> {
            if (!finished.get()) {
                plugin.getLogger().fine("[LagWatch] entity count gave up waiting on " + pending.size() + " chunks; judging what was counted");
                finish.run();
            }
        }, SCAN_TIMEOUT_SECONDS * 20);
    }

    private void scheduleScan(ChunkRef at, Set<ChunkRef> pending, Map<ChunkRef, Tally> result, boolean hoppers, AtomicInteger chains, Runnable finish) {
        World world = Bukkit.getWorld(at.world());
        if (world == null) {
            pending.remove(at);
            continueChain(pending, result, hoppers, chains, finish);
            return;
        }
        Location location = new Location(world, (at.x() << 4) + 8, 64, (at.z() << 4) + 8);
        Tasks.region.run(location, () -> {
            try {
                pending.remove(at); // always make progress, even if this chunk unloaded meanwhile
                scanOwned(world, at, result, hoppers);
                for (ChunkRef ref : pending) {
                    if (ref.world().equals(at.world()) && Bukkit.isOwnedByCurrentRegion(world, ref.x(), ref.z()) && pending.remove(ref)) {
                        scanOwned(world, ref, result, hoppers);
                    }
                }
            } finally {
                continueChain(pending, result, hoppers, chains, finish);
            }
        });
    }

    private void continueChain(Set<ChunkRef> pending, Map<ChunkRef, Tally> result, boolean hoppers, AtomicInteger chains, Runnable finish) {
        Iterator<ChunkRef> next = pending.iterator();
        if (next.hasNext()) {
            scheduleScan(next.next(), pending, result, hoppers, chains, finish);
            return;
        }
        if (chains.decrementAndGet() <= 0) {
            finish.run();
        }
    }

    /** On the thread that owns the chunk. */
    private static void scanOwned(World world, ChunkRef ref, Map<ChunkRef, Tally> result, boolean hoppers) {
        if (!Bukkit.isOwnedByCurrentRegion(world, ref.x(), ref.z()) || !world.isChunkLoaded(ref.x(), ref.z())) {
            return;
        }
        Chunk chunk = world.getChunkAt(ref.x(), ref.z());
        for (Entity entity : chunk.getEntities()) {
            LagCheck check = classify(entity);
            if (check == null) {
                continue;
            }
            Location at = entity.getLocation();
            Tally tally = result.computeIfAbsent(new ChunkRef(ref.world(), at.getBlockX() >> 4, at.getBlockZ() >> 4), k -> new Tally());
            tally.add(check, at.getBlockX(), at.getBlockY(), at.getBlockZ());
            if (check == LagCheck.VILLAGERS) {
                tally.n.incrementAndGet(LagCheck.MOBS.ordinal());
            }
        }
        if (hoppers) {
            int found = 0;
            BlockState first = null;
            for (BlockState state : chunk.getTileEntities(false)) {
                if (state instanceof Hopper) {
                    found++;
                    first = first == null ? state : first;
                }
            }
            if (found > 0) {
                Tally tally = result.computeIfAbsent(ref, k -> new Tally());
                tally.n.set(LagCheck.HOPPERS.ordinal(), found);
                tally.place(first.getX(), first.getY(), first.getZ());
            }
        }
    }

    /** Global thread. */
    private void finishCount(Map<ChunkRef, Tally> tallies, boolean hoppers) {
        if (!hoppers) {
            // Hoppers are counted every other scan: carry the last count over.
            for (Map.Entry<ChunkRef, Tally> old : lastCount.entrySet()) {
                int n = old.getValue().get(LagCheck.HOPPERS);
                if (n > 0) {
                    Tally tally = tallies.computeIfAbsent(old.getKey(), k -> new Tally());
                    tally.n.set(LagCheck.HOPPERS.ordinal(), n);
                    tally.place(old.getValue().bx, old.getValue().by, old.getValue().bz);
                }
            }
        }
        lastCount = tallies;
        markWindow(LagCheck.Kind.COUNT, tallies);
    }

    private static @Nullable LagCheck classify(Entity entity) {
        if (entity instanceof Item) {
            return LagCheck.ITEMS;
        }
        if (entity instanceof Minecart || entity instanceof Boat) {
            return LagCheck.VEHICLES;
        }
        if (entity instanceof ArmorStand stand) {
            return stand.isMarker() || !stand.isVisible() ? null : LagCheck.ARMOR_STANDS;
        }
        if (entity instanceof ItemFrame) {
            return LagCheck.ITEM_FRAMES;
        }
        if (entity instanceof Villager) {
            return LagCheck.VILLAGERS;
        }
        if (entity instanceof Mob) {
            return LagCheck.MOBS;
        }
        if (entity instanceof Projectile) {
            return LagCheck.PROJECTILES;
        }
        if (entity instanceof FallingBlock) {
            return LagCheck.FALLING;
        }
        return null;
    }

    // ------------------------------------------------------------------ slow server / slow regions (global thread)

    private void checkSlow() {
        if (Tasks.isFolia()) {
            checkSlowRegions();
            return;
        }
        double limit = slowMspt;
        double mspt = RegionTps.serverMspt();
        if (limit <= 0 || Double.isNaN(mspt) || mspt < limit) {
            slowWindows = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (++slowWindows < SLOW_WINDOWS || now - lastSlowAlertAt < SLOW_REALERT_MS) {
            return;
        }
        lastSlowAlertAt = now;
        double tps = RegionTps.serverTps();
        List<Component> lines = busiest(3);
        String head = PREFIX + "&cThe server is running slow &8(&f" + String.format("%.1f", mspt) + "ms&7 per tick, tps &f" + String.format("%.1f", tps)
                + "&8)&7. " + (lines.isEmpty() ? "No chunk stands out: check /spark profiler." : "Busiest places:");
        tellStaff(LEGACY.deserialize(head));
        lines.forEach(this::tellStaff);
        log(Instant.now() + " SLOW mspt=" + String.format("%.1f", mspt) + " busiest=" + lines.size());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Server", Bukkit.getServer().getName());
        fields.put("Tick time", String.format("%.1f ms (TPS %.1f)", mspt, tps));
        fields.put("Busiest places", lines.isEmpty() ? "nothing stands out" : String.join("\n", lastBusiestText));
        webhook.send(webhookUrl, "Server running slow", 0xE67E22, fields, Set.of("Who may be behind it", "Busiest places"));
    }

    /** Folia: report the regions (by a player in them) whose measured TPS stayed below slow-tps for 30 seconds. */
    private void checkSlowRegions() {
        double limit = slowTps;
        if (limit <= 0) {
            slowWindows = 0;
            return;
        }
        List<RegionTps.Snapshot> slow = new ArrayList<>();
        for (RegionTps.Snapshot s : regionTps.snapshots()) {
            if (s.tps() < limit) {
                slow.add(s);
            }
        }
        if (slow.isEmpty()) {
            slowWindows = 0;
            return;
        }
        long now = System.currentTimeMillis();
        if (++slowWindows < SLOW_WINDOWS || now - lastSlowAlertAt < SLOW_REALERT_MS) {
            return;
        }
        lastSlowAlertAt = now;
        slow.sort(Comparator.comparingDouble(RegionTps.Snapshot::tps));
        // Players in one region report the same TPS; keep one per (world, rounded TPS, nearby) group.
        List<RegionTps.Snapshot> shown = new ArrayList<>();
        for (RegionTps.Snapshot s : slow) {
            boolean same = false;
            for (RegionTps.Snapshot kept : shown) {
                if (kept.distanceSquared(s.world(), s.x(), s.y(), s.z()) < 512.0 * 512.0 && Math.abs(kept.tps() - s.tps()) < 0.5) {
                    same = true;
                    break;
                }
            }
            if (!same) {
                shown.add(s);
            }
            if (shown.size() >= 3) {
                break;
            }
        }
        List<Component> lines = busiest(3);
        List<String> regions = new ArrayList<>();
        for (RegionTps.Snapshot s : shown) {
            regions.add(s.worldName() + " " + (int) s.x() + " " + (int) s.y() + " " + (int) s.z() + " (around " + s.name() + ", " + String.format("%.1f", s.tps()) + " tps)");
        }
        tellStaff(LEGACY.deserialize(PREFIX + "&cSome regions are running slow&7: &f" + String.join("&7, &f", regions) + "&7. "
                + (lines.isEmpty() ? "No chunk stands out: check /spark profiler." : "Busiest places:")));
        lines.forEach(this::tellStaff);
        log(Instant.now() + " SLOW-REGIONS " + String.join(" | ", regions) + " busiest=" + lines.size());
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Server", Bukkit.getServer().getName());
        fields.put("Slow regions", String.join("\n", regions));
        fields.put("Busiest places", lines.isEmpty() ? "nothing stands out" : String.join("\n", lastBusiestText));
        webhook.send(webhookUrl, "Regions running slow", 0xE67E22, fields, Set.of("Slow regions", "Busiest places"));
    }

    // ------------------------------------------------------------------ ranking and reports

    private record Busy(ChunkRef ref, double score, LagCheck top, int value, Tally tally) {
    }

    private List<Busy> rank(int limit) {
        int[] limits = this.limits;
        Map<ChunkRef, Busy> out = new HashMap<>();
        for (Map<ChunkRef, Tally> source : List.of(lastWindow, lastCount)) {
            for (Map.Entry<ChunkRef, Tally> row : source.entrySet()) {
                Tally t = row.getValue();
                Busy busy = out.get(row.getKey());
                double score = busy == null ? 0 : busy.score();
                LagCheck top = busy == null ? null : busy.top();
                int topValue = busy == null ? 0 : busy.value();
                double topRatio = top == null ? 0 : (double) topValue / limits[top.ordinal()];
                for (LagCheck check : CHECKS) {
                    int l = limits[check.ordinal()];
                    int n = t.get(check);
                    if (l <= 0 || n == 0) {
                        continue;
                    }
                    double ratio = (double) n / l;
                    score += ratio;
                    if (ratio > topRatio) {
                        topRatio = ratio;
                        top = check;
                        topValue = n;
                    }
                }
                if (top != null) {
                    out.put(row.getKey(), new Busy(row.getKey(), score, top, topValue, busy == null ? t : busy.tally()));
                }
            }
        }
        List<Busy> list = new ArrayList<>(out.values());
        list.removeIf(busy -> busy.score() < 0.1);
        list.sort(Comparator.comparingDouble(Busy::score).reversed());
        return list.size() > limit ? list.subList(0, limit) : list;
    }

    /** The busiest chunks right now, one line each with a teleport button. Any thread (reads published snapshots). */
    public List<Component> busiest(int limit) {
        List<Component> lines = new ArrayList<>();
        List<String> text = new ArrayList<>();
        for (Busy busy : rank(limit)) {
            World world = Bukkit.getWorld(busy.ref().world());
            if (world == null) {
                continue;
            }
            Location at = new Location(world, busy.tally().bx + 0.5, busy.tally().by + 1, busy.tally().bz + 0.5);
            int id = remember(at);
            Suspects who = suspects(busy.ref(), at);
            int percent = (int) Math.round(100.0 * busy.value() / limits[busy.top().ordinal()]);
            String line = "&7#" + id + " &f" + world.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " &8- &e" + busy.top().label() + " &7"
                    + busy.top().describe(busy.value()) + " &8(" + percent + "% of the limit) &8- &7" + who.text();
            lines.add(teleportButton(LEGACY.deserialize("  " + line), id));
            text.add(world.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + ": " + busy.top().label() + ", " + busy.top().describe(busy.value())
                    + " (" + percent + "%) - " + who.plain());
        }
        lastBusiestText = text;
        return lines;
    }

    private record Suspects(String main, String text, String plain) {
    }

    private Suspects suspects(ChunkRef ref, Location at) {
        List<String> parts = new ArrayList<>();
        List<String> plain = new ArrayList<>();
        String main = null;
        for (Function<Location, String> resolver : ownerResolvers) {
            try {
                String owner = resolver.apply(at);
                if (owner != null && !owner.isBlank()) {
                    main = owner;
                    parts.add("&7claim of &f" + owner);
                    plain.add("claim of " + owner);
                    break;
                }
            } catch (RuntimeException ignored) {
                // a broken resolver must not stop the report
            }
        }
        Map<UUID, Integer> placed = new HashMap<>();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                Builders entry = builders.get(new ChunkRef(ref.world(), ref.x() + dx, ref.z() + dz));
                if (entry != null) {
                    entry.parts.forEach((id, n) -> placed.merge(id, n, Integer::sum));
                }
            }
        }
        if (!placed.isEmpty()) {
            List<Map.Entry<UUID, Integer>> top = new ArrayList<>(placed.entrySet());
            top.sort(Map.Entry.<UUID, Integer>comparingByValue().reversed());
            List<String> names = new ArrayList<>();
            for (Map.Entry<UUID, Integer> row : top.subList(0, Math.min(3, top.size()))) {
                String name = nameOf(row.getKey());
                names.add(name + " (" + row.getValue() + ")");
                if (main == null) {
                    main = name;
                }
            }
            parts.add("&7built by &f" + String.join("&7, &f", names));
            plain.add("built by " + String.join(", ", names));
        }
        // Positions copied on each player's own thread (Folia rule 3), never read from here.
        List<RegionTps.Snapshot> near = new ArrayList<>();
        UUID worldId = ref.world();
        for (RegionTps.Snapshot s : regionTps.snapshots()) {
            if (!s.staff() && s.distanceSquared(worldId, at.getX(), at.getY(), at.getZ()) <= NEARBY_BLOCKS * NEARBY_BLOCKS) {
                near.add(s);
            }
        }
        if (!near.isEmpty()) {
            near.sort(Comparator.comparingDouble(s -> s.distanceSquared(worldId, at.getX(), at.getY(), at.getZ())));
            List<String> names = new ArrayList<>();
            for (RegionTps.Snapshot s : near.subList(0, Math.min(3, near.size()))) {
                names.add(s.name() + " " + (int) Math.sqrt(s.distanceSquared(worldId, at.getX(), at.getY(), at.getZ())) + "m");
                if (main == null) {
                    main = s.name();
                }
            }
            parts.add("&7near: &f" + String.join("&7, &f", names));
            plain.add("near: " + String.join(", ", names));
        }
        if (parts.isEmpty()) {
            return new Suspects("-", "&7nobody near, builder unknown (try /co inspect)", "nobody near, builder unknown");
        }
        return new Suspects(main, String.join(" &8· ", parts), String.join(" · ", plain));
    }

    private static String nameOf(UUID id) {
        Player online = Bukkit.getPlayer(id);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offline = Bukkit.getOfflinePlayer(id);
        return offline.getName() == null ? id.toString().substring(0, 8) : offline.getName();
    }

    private int remember(Location at) {
        int id = nextId.getAndIncrement();
        places.put(id, at);
        return id;
    }

    private static Component teleportButton(Component line, int id) {
        return line.append(LEGACY.deserialize(" &8[&eTeleport&8]")
                .clickEvent(ClickEvent.runCommand("/lagwatch tp " + id))
                .hoverEvent(HoverEvent.showText(LEGACY.deserialize("&7Go and look"))));
    }

    private String tpsText(World world, Location at) {
        double server = RegionTps.serverTps();
        if (!Double.isNaN(server)) {
            return String.format("%.1f ms (TPS %.1f)", RegionTps.serverMspt(), server);
        }
        double region = regionTps.tpsNear(world.getUID(), at.getX(), at.getY(), at.getZ(), NEARBY_BLOCKS);
        return Double.isNaN(region) ? "region TPS unknown (no player near)" : String.format("region TPS %.1f", region);
    }

    /** Global thread. */
    private void report(ChunkRef ref, Heat entry, LagCheck check, int value, int limit, boolean severe) {
        World world = Bukkit.getWorld(ref.world());
        if (world == null) {
            return;
        }
        Location at = new Location(world, entry.bx + 0.5, entry.by + 1, entry.bz + 0.5);
        int id = remember(at);
        Suspects who = suspects(ref, at);
        String level = severe ? "&c&lSEVERE" : "&e&lPOSSIBLE";
        String line = PREFIX + level + " &f" + check.label() + " &8(&7" + check.describe(value) + ", limit " + String.format("%,d", limit) + "&8) &7at &f" + world.getName() + " "
                + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " &8- " + who.text();
        tellStaff(teleportButton(LEGACY.deserialize(line), id));

        Incident incident = new Incident(id, System.currentTimeMillis(), world.getName(), at.getBlockX(), at.getBlockY(), at.getBlockZ(), check, value, limit, severe, who.main(), who.plain());
        recent.addLast(incident);
        while (recent.size() > RECENT_LIMIT) {
            recent.pollFirst();
        }
        String tps = tpsText(world, at);
        log(Instant.ofEpochMilli(incident.at()) + " " + (severe ? "SEVERE" : "POSSIBLE") + " " + check.key() + " " + value + "/" + limit + " " + world.getName() + " "
                + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ() + " suspect=" + who.main() + " [" + who.plain() + "] " + tps);

        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Server", Bukkit.getServer().getName());
        fields.put("What", check.describe(value) + " (limit " + String.format("%,d", limit) + ")");
        fields.put("Where", world.getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
        fields.put("Who may be behind it", who.plain());
        fields.put("Tick time", tps);
        fields.put("In game", "/lagwatch tp " + id);
        webhook.send(webhookUrl, (severe ? "Severe lag machine: " : "Possible lag machine: ") + check.label(), severe ? 0xE74C3C : 0xF1C40F, fields,
                Set.of("Who may be behind it", "Busiest places"));
    }

    private void tellStaff(Component message) {
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission(ALERT_PERMISSION) && !mutedStaff.contains(staff.getUniqueId())) {
                Tasks.onEntity(staff, () -> staff.sendMessage(message));
            }
        }
    }

    private void log(String line) {
        if (!plugin.isEnabled()) {
            return;
        }
        Tasks.async.run(() -> {
            synchronized (logLock) {
                try {
                    Files.writeString(logFile.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not write lagwatch.log: " + ex.getMessage());
                }
            }
        });
    }
}
