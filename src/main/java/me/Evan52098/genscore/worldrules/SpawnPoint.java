package me.Evan52098.genscore.worldrules;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Where "sent to spawn" goes, for the group K modules (spawn bounds, end lock, arena, rtp).
 *
 * <p>The teleport module keeps /setspawn in {@code pvp_locations} under the key {@code spawn}, but does not expose it.
 * Until it does (see integration/group-k.md), this class reads that row itself at start and every 30 seconds, so a
 * /setspawn is picked up within half a minute. Without a row it uses the first world's spawn. Once teleport exposes
 * {@code spawnLocation()}, GensCore.connectModules can call {@link #use(Supplier)} and the polling stops mattering.
 */
public final class SpawnPoint {

    private record Saved(String world, double x, double y, double z, float yaw, float pitch) {
    }

    private static volatile @Nullable Saved saved;
    private static volatile @Nullable Supplier<Location> override;
    private static volatile boolean started;

    private SpawnPoint() {
    }

    /** Starts the background refresh. Safe to call more than once (every group K module calls it). */
    public static synchronized void init(GensCore plugin) {
        if (started) {
            return;
        }
        started = true;
        Tasks.async.timer(t -> refresh(plugin), 0, 30, TimeUnit.SECONDS);
    }

    /** Lets the teleport module supply the spawn directly. */
    public static void use(@Nullable Supplier<Location> supplier) {
        override = supplier;
    }

    private static void refresh(GensCore plugin) {
        try {
            saved = plugin.database().sync(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "SELECT world, x, y, z, yaw, pitch FROM pvp_locations WHERE loc_key = 'spawn'")) {
                    try (ResultSet rs = ps.executeQuery()) {
                        return rs.next() ? new Saved(rs.getString(1), rs.getDouble(2), rs.getDouble(3), rs.getDouble(4),
                                rs.getFloat(5), rs.getFloat(6)) : null;
                    }
                }
            });
        } catch (Exception e) {
            // The table belongs to the teleport module and may not exist when it is switched off.
            plugin.getLogger().log(Level.FINE, "[worldrules] could not read the spawn location", e);
        }
    }

    /** The spawn location (a new copy every call), never null while a world is loaded. */
    public static @Nullable Location get() {
        Supplier<Location> o = override;
        if (o != null) {
            Location l = o.get();
            if (l != null) {
                return l.clone();
            }
        }
        Saved s = saved;
        if (s != null) {
            World w = Bukkit.getWorld(s.world());
            if (w != null) {
                return new Location(w, s.x(), s.y(), s.z(), s.yaw(), s.pitch());
            }
        }
        List<World> worlds = Bukkit.getWorlds();
        return worlds.isEmpty() ? null : worlds.get(0).getSpawnLocation();
    }
}
