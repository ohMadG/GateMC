package me.Evan52098.genscore.teleport;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every player-initiated teleport goes through here: guards (combat tag and so on), a warmup that is cancelled by
 * moving or taking damage, the "back" location, and Folia's {@code teleportAsync}.
 *
 * <p>All methods must be called on the moving player's thread ({@link Tasks#onEntity}).
 */
public final class TeleportService {

    public enum Kind { HOME, WARP, SPAWN, TPA, BACK, OTHER }

    public static final String BYPASS_WARMUP = "genscore.teleport.bypasswarmup";

    private record Warmup(ScheduledTask task, int x, int y, int z, String world) {
    }

    private final Map<Kind, Integer> warmupSeconds = new EnumMap<>(Kind.class);
    private final boolean cancelOnMove;
    private final boolean cancelOnDamage;
    private final List<TeleportGuard> guards = new CopyOnWriteArrayList<>();
    private final Map<UUID, Warmup> warmups = new ConcurrentHashMap<>();
    private final Map<UUID, SavedLocation> back = new ConcurrentHashMap<>();

    TeleportService(ConfigurationSection config) {
        ConfigurationSection w = config == null ? null : config.getConfigurationSection("warmup-seconds");
        for (Kind kind : Kind.values()) {
            warmupSeconds.put(kind, w == null ? 0 : Math.max(0, w.getInt(kind.name().toLowerCase(), 0)));
        }
        cancelOnMove = config == null || config.getBoolean("cancel-on-move", true);
        cancelOnDamage = config == null || config.getBoolean("cancel-on-damage", true);
    }

    /** Adds a rule that can refuse teleports. Other modules call this while enabling. */
    public void addGuard(TeleportGuard guard) {
        guards.add(guard);
    }

    /**
     * Starts a teleport. Sends the "don't move" message when there is a warmup, then {@code arrived} after landing.
     * Returns false (and tells the player why) if a guard refused it.
     */
    public boolean teleport(Player player, Location destination, Kind kind, @Nullable Component arrived) {
        String denied = denial(player, destination, kind);
        if (denied != null) {
            player.sendMessage(Msg.bad(denied));
            return false;
        }
        cancel(player, null);
        int seconds = player.hasPermission(BYPASS_WARMUP) ? 0 : warmupSeconds.getOrDefault(kind, 0);
        if (seconds <= 0) {
            go(player, destination, kind, arrived);
            return true;
        }
        player.sendMessage(Msg.neutral("Teleporting in " + Msg.white(String.valueOf(seconds)) + " seconds - don't move!"));
        Location start = player.getLocation();
        long[] ticksLeft = {seconds * 20L};
        ScheduledTask task = Tasks.entity.timer(player, t -> {
            Warmup current = warmups.get(player.getUniqueId());
            if (current == null || current.task() != t) {
                t.cancel();
                return;
            }
            if (cancelOnMove && moved(player, current)) {
                cancel(player, "Teleport canceled - you moved.");
                return;
            }
            ticksLeft[0] -= 5;
            if (ticksLeft[0] <= 0) {
                warmups.remove(player.getUniqueId(), current);
                t.cancel();
                go(player, destination, kind, arrived);
            }
        }, 5, 5);
        if (task != null) {
            warmups.put(player.getUniqueId(), new Warmup(task, start.getBlockX(), start.getBlockY(), start.getBlockZ(),
                    start.getWorld().getName()));
        }
        return true;
    }

    private void go(Player player, Location destination, Kind kind, @Nullable Component arrived) {
        String denied = denial(player, destination, kind); // things may have changed during the warmup
        if (denied != null) {
            player.sendMessage(Msg.bad(denied));
            return;
        }
        rememberBack(player, player.getLocation());
        player.teleportAsync(destination, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(ok -> {
            if (ok && arrived != null) {
                player.sendMessage(arrived);
            } else if (!ok) {
                player.sendMessage(Msg.bad("The teleport failed. Try again."));
            }
        });
    }

    private @Nullable String denial(Player player, Location destination, Kind kind) {
        for (TeleportGuard guard : guards) {
            String reason = guard.deny(player, destination, kind);
            if (reason != null) {
                return reason;
            }
        }
        return null;
    }

    private static boolean moved(Player player, Warmup w) {
        Location now = player.getLocation();
        return now.getBlockX() != w.x() || now.getBlockY() != w.y() || now.getBlockZ() != w.z()
                || !now.getWorld().getName().equals(w.world());
    }

    /** Stops a running warmup, telling the player why (or silently when {@code reason} is null). */
    public void cancel(Player player, @Nullable String reason) {
        Warmup w = warmups.remove(player.getUniqueId());
        if (w != null) {
            w.task().cancel();
            if (reason != null) {
                player.sendMessage(Msg.bad(reason));
            }
        }
    }

    boolean cancelOnDamage() {
        return cancelOnDamage;
    }

    boolean inWarmup(UUID player) {
        return warmups.containsKey(player);
    }

    void forget(UUID player) {
        Warmup w = warmups.remove(player);
        if (w != null) {
            w.task().cancel();
        }
        back.remove(player);
    }

    // ------------------------------------------------------------------ /back

    public void rememberBack(Player player, Location location) {
        back.put(player.getUniqueId(), SavedLocation.of(location));
    }

    public @Nullable SavedLocation back(UUID player) {
        return back.get(player);
    }
}
