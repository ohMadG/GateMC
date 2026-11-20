package me.Evan52098.genscore.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * The only way GensCore schedules work. Wraps Paper's region-aware schedulers, which exist on both
 * Paper and Folia, so the same code runs on either server.
 *
 * <p>Pick the scheduler by <b>what the task touches</b>:
 * <ul>
 *   <li>a player or entity (health, inventory, teleport, messages to them) &rarr; {@link #entity}</li>
 *   <li>blocks or chunks at a place (claims, KOTH capture zone, spawners) &rarr; {@link #region}</li>
 *   <li>world-wide state with no location (day time, weather, global countdowns) &rarr; {@link #global}</li>
 *   <li>database, files, web requests &rarr; {@link #async} (never touch the world from here)</li>
 * </ul>
 *
 * <p>Never call {@code Bukkit.getScheduler()} in this plugin: on Folia it throws.
 * Delays and periods are in ticks unless a {@link TimeUnit} is given. Ticks below 1 are raised to 1,
 * because the region schedulers reject 0.
 */
public final class Tasks {

    private static final boolean FOLIA = classExists("io.papermc.paper.threadedregions.RegionizedServer");

    private static Plugin plugin;

    private Tasks() {
    }

    public static void init(Plugin owner) {
        plugin = owner;
    }

    /** True when running on Folia (useful only for logging; code must work the same either way). */
    public static boolean isFolia() {
        return FOLIA;
    }

    /** Cancels everything this plugin scheduled. Called on disable. */
    public static void cancelAll() {
        if (plugin == null) {
            return;
        }
        Bukkit.getGlobalRegionScheduler().cancelTasks(plugin);
        Bukkit.getAsyncScheduler().cancelTasks(plugin);
        // Region and entity tasks are cancelled by the server when the plugin disables.
    }

    // ---------------------------------------------------------------- global

    public static final class global {
        private global() {
        }

        public static ScheduledTask run(Runnable task) {
            return Bukkit.getGlobalRegionScheduler().run(plugin, t -> task.run());
        }

        public static ScheduledTask later(Runnable task, long delayTicks) {
            return Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), ticks(delayTicks));
        }

        public static ScheduledTask timer(Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
            return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, task, ticks(delayTicks), ticks(periodTicks));
        }
    }

    // ---------------------------------------------------------------- region

    public static final class region {
        private region() {
        }

        public static ScheduledTask run(Location at, Runnable task) {
            return Bukkit.getRegionScheduler().run(plugin, at, t -> task.run());
        }

        public static ScheduledTask later(Location at, Runnable task, long delayTicks) {
            return Bukkit.getRegionScheduler().runDelayed(plugin, at, t -> task.run(), ticks(delayTicks));
        }

        public static ScheduledTask timer(Location at, Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
            return Bukkit.getRegionScheduler().runAtFixedRate(plugin, at, task, ticks(delayTicks), ticks(periodTicks));
        }
    }

    // ---------------------------------------------------------------- entity

    /**
     * Entity tasks follow the entity between regions. They return null (and run {@code retired}
     * instead, if given) when the entity is gone, for example a player who logged out.
     */
    public static final class entity {
        private entity() {
        }

        public static @Nullable ScheduledTask run(Entity e, Runnable task) {
            return e.getScheduler().run(plugin, t -> task.run(), null);
        }

        public static @Nullable ScheduledTask later(Entity e, Runnable task, long delayTicks) {
            return e.getScheduler().runDelayed(plugin, t -> task.run(), null, ticks(delayTicks));
        }

        public static @Nullable ScheduledTask later(Entity e, Runnable task, @Nullable Runnable retired, long delayTicks) {
            return e.getScheduler().runDelayed(plugin, t -> task.run(), retired, ticks(delayTicks));
        }

        public static @Nullable ScheduledTask timer(Entity e, Consumer<ScheduledTask> task, long delayTicks, long periodTicks) {
            return e.getScheduler().runAtFixedRate(plugin, task, null, ticks(delayTicks), ticks(periodTicks));
        }
    }

    // ---------------------------------------------------------------- async

    public static final class async {
        private async() {
        }

        public static ScheduledTask run(Runnable task) {
            return Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        }

        public static ScheduledTask later(Runnable task, long delay, TimeUnit unit) {
            return Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delay, unit);
        }

        public static ScheduledTask timer(Consumer<ScheduledTask> task, long delay, long period, TimeUnit unit) {
            return Bukkit.getAsyncScheduler().runAtFixedRate(plugin, task, delay, period, unit);
        }
    }

    // ---------------------------------------------------------------- helpers

    /** True if the current thread may touch this location right now. */
    public static boolean owns(Location at) {
        return Bukkit.isOwnedByCurrentRegion(at);
    }

    /** True if the current thread may touch this entity right now. */
    public static boolean owns(Entity e) {
        return Bukkit.isOwnedByCurrentRegion(e);
    }

    /** Runs the task now if this thread owns the entity, otherwise schedules it on the entity's thread. */
    public static void onEntity(Entity e, Runnable task) {
        if (owns(e)) {
            task.run();
        } else {
            entity.run(e, task);
        }
    }

    /**
     * Runs the task on the thread that owns the command sender: the player's thread for a player, the global
     * thread for the console. Use it to answer a command after async work (a database lookup) finishes.
     */
    public static void forSender(CommandSender sender, Runnable task) {
        if (sender instanceof Entity e) {
            onEntity(e, task);
        } else {
            global.run(task);
        }
    }

    private static long ticks(long t) {
        return Math.max(1L, t);
    }

    private static boolean classExists(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
