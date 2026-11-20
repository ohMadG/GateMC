package me.Evan52098.genscore.lagwatch;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-region tick rate on Folia, measured where the players are.
 *
 * <p>Folia has no single main thread, so {@code Bukkit.getTPS()} / {@code getAverageTickTime()} do not describe the
 * server, and this Paper API build has no region TPS call. Instead each online player gets a small timer on their own
 * entity scheduler that fires every 20 ticks of <i>their region</i>; the wall time between runs gives that region's TPS
 * (20 ticks in 1 s = 20 TPS, in 2 s = 10 TPS). On Paper this measures the one main thread, so it agrees with getTPS.
 *
 * <p>The same timer also copies the player's position and whether they are staff, on their own thread, so other
 * threads (lag reports, "who is near") can read them without touching the player (Folia rule 3).
 *
 * <p>Shared by lagwatch and antihack: {@link #start(Plugin, String)} is idempotent.
 */
public final class RegionTps implements Listener {

    /** A copy of where a player was, taken on their own thread. */
    public record Snapshot(UUID uuid, String name, UUID world, String worldName, double x, double y, double z, boolean staff, double tps, long at) {
        public double distanceSquared(UUID otherWorld, double ox, double oy, double oz) {
            if (!world.equals(otherWorld)) {
                return Double.MAX_VALUE;
            }
            double dx = x - ox;
            double dy = y - oy;
            double dz = z - oz;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private static final int SAMPLES = 5;
    private static volatile RegionTps instance;

    private static final class Probe {
        long lastNanos;
        final double[] tps = new double[SAMPLES];
        int index;
        int count;
        volatile double average = 20.0;
        volatile Snapshot snapshot;
        int staffCheck;
        boolean staff;
        ScheduledTask task;
    }

    private final String staffPermission;
    private final Map<UUID, Probe> probes = new ConcurrentHashMap<>();

    private RegionTps(String staffPermission) {
        this.staffPermission = staffPermission;
    }

    /** Starts measuring (once). Call while enabling. */
    public static synchronized RegionTps start(Plugin plugin, String staffPermission) {
        if (instance == null) {
            RegionTps created = new RegionTps(staffPermission);
            Bukkit.getPluginManager().registerEvents(created, plugin);
            for (Player online : Bukkit.getOnlinePlayers()) {
                Tasks.entity.run(online, () -> created.begin(online));
            }
            instance = created;
        }
        return instance;
    }

    public static @Nullable RegionTps get() {
        return instance;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        begin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Probe probe = probes.remove(event.getPlayer().getUniqueId());
        if (probe != null && probe.task != null) {
            probe.task.cancel();
        }
    }

    /** Player's thread. */
    private void begin(Player player) {
        Probe probe = new Probe();
        Probe previous = probes.put(player.getUniqueId(), probe);
        if (previous != null && previous.task != null) {
            previous.task.cancel();
        }
        sample(player, probe);
        probe.task = Tasks.entity.timer(player, t -> sample(player, probe), 20, 20);
    }

    /** Player's thread. */
    private void sample(Player player, Probe probe) {
        long now = System.nanoTime();
        if (probe.lastNanos != 0) {
            double seconds = (now - probe.lastNanos) / 1_000_000_000.0;
            double tps = seconds <= 0 ? 20.0 : Math.min(20.0, 20.0 / seconds);
            probe.tps[probe.index] = tps;
            probe.index = (probe.index + 1) % SAMPLES;
            probe.count = Math.min(SAMPLES, probe.count + 1);
            double sum = 0;
            for (int i = 0; i < probe.count; i++) {
                sum += probe.tps[i];
            }
            probe.average = sum / probe.count;
        }
        probe.lastNanos = now;
        if (probe.staffCheck-- <= 0) {
            probe.staffCheck = 5;
            probe.staff = player.hasPermission(staffPermission);
        }
        Location at = player.getLocation();
        World world = at.getWorld();
        if (world != null) {
            probe.snapshot = new Snapshot(player.getUniqueId(), player.getName(), world.getUID(), world.getName(),
                    at.getX(), at.getY(), at.getZ(), probe.staff, probe.average, System.currentTimeMillis());
        }
    }

    /** The measured TPS of the region the player is in (20 when not known yet). Any thread. */
    public double tps(UUID player) {
        Probe probe = probes.get(player);
        return probe == null ? 20.0 : probe.average;
    }

    /** Copies of every online player's last position. Any thread. */
    public Collection<Snapshot> snapshots() {
        List<Snapshot> out = new ArrayList<>(probes.size());
        for (Probe probe : probes.values()) {
            Snapshot s = probe.snapshot;
            if (s != null) {
                out.add(s);
            }
        }
        return out;
    }

    /** The TPS near a place: the region of the nearest measured player within {@code radius} blocks, or NaN. */
    public double tpsNear(UUID world, double x, double y, double z, double radius) {
        double best = radius * radius;
        double tps = Double.NaN;
        for (Snapshot s : snapshots()) {
            double d = s.distanceSquared(world, x, y, z);
            if (d <= best) {
                best = d;
                tps = s.tps();
            }
        }
        return tps;
    }

    /** Server-wide TPS on Paper; NaN on Folia, where there is no single number. */
    public static double serverTps() {
        if (Tasks.isFolia()) {
            return Double.NaN;
        }
        try {
            return Math.min(20.0, Bukkit.getTPS()[0]);
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }

    /** Server-wide average tick time (ms) on Paper; NaN on Folia. */
    public static double serverMspt() {
        if (Tasks.isFolia()) {
            return Double.NaN;
        }
        try {
            return Bukkit.getAverageTickTime();
        } catch (RuntimeException e) {
            return Double.NaN;
        }
    }
}
