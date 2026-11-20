package me.Evan52098.genscore.maintenance;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.worldrules.Text;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.AbstractArrow;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Trident;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Every few minutes removes ground items older than min-age, old XP orbs and arrows stuck in blocks, with warnings
 * first. Written fresh from HxCore's {@code maintenance.clear-drops}.
 *
 * <p>Folia: there is no global loop over the world's entities. Items, orbs and arrows are tracked as they enter and
 * leave the world (add/remove events, fired on their own region thread), and the sweep hands each one to its own
 * entity scheduler, which runs it on the region that owns it.
 */
final class ClearLag implements Listener {

    private record Settings(boolean enabled, int everyMinutes, long minAgeTicks, boolean xpOrbs, boolean arrows,
                            List<Integer> warnings, String warning, String done, Set<String> skipWorlds) {
    }

    private final Map<UUID, Entity> tracked = new ConcurrentHashMap<>();
    private volatile Settings settings;
    /** Seconds until the next sweep; counted down on the global thread only. */
    private volatile long countdown;

    void load(ConfigurationSection c) {
        List<Integer> warnings = new ArrayList<>(c.getIntegerList("warning-seconds"));
        if (!c.contains("warning-seconds")) {
            warnings = List.of(60, 30, 10);
        }
        settings = new Settings(c.getBoolean("enabled", true), Math.max(1, c.getInt("every-minutes", 5)),
                Math.max(0, c.getLong("min-age-seconds", 60)) * 20L, c.getBoolean("xp-orbs", true), c.getBoolean("arrows", true),
                List.copyOf(warnings),
                c.getString("warning", "&c&lCLEAR LAG &8» &7Items on the ground will be removed in &c{time}&7!"),
                c.getString("done", "&a&lCLEAR LAG &8» &7Removed &a{count} &7items from the ground."),
                new HashSet<>(c.getStringList("skip-worlds")));
        countdown = settings.everyMinutes() * 60L;
    }

    /** Started once; runs every second on the global thread. */
    void start() {
        Tasks.global.timer(t -> tick(), 20, 20);
    }

    private void tick() {
        Settings s = settings;
        if (!s.enabled()) {
            return;
        }
        countdown--;
        if (s.warnings().contains((int) countdown)) {
            Text.broadcast(Text.legacy(s.warning().replace("{time}", Text.duration(countdown))));
        }
        if (countdown <= 0) {
            countdown = s.everyMinutes() * 60L;
            sweep(s);
        }
    }

    /** Clears now (admin command). */
    void sweepNow() {
        sweep(settings);
    }

    private void sweep(Settings s) {
        AtomicInteger removed = new AtomicInteger();
        for (Entity e : List.copyOf(tracked.values())) {
            // Each entity on the thread that owns it. If it is already gone the task never runs.
            Tasks.entity.run(e, () -> {
                if (e.isValid() && removable(e, s)) {
                    e.remove();
                    removed.incrementAndGet();
                }
            });
        }
        // The tasks above run on the next tick of each region; report a couple of seconds later.
        Tasks.global.later(() -> Text.broadcast(Text.legacy(s.done().replace("{count}", String.valueOf(removed.get())))), 40);
    }

    private static boolean removable(Entity e, Settings s) {
        if (s.skipWorlds().contains(e.getWorld().getName()) || e.getTicksLived() < s.minAgeTicks()) {
            return false;
        }
        if (e instanceof Item) {
            return true;
        }
        if (e instanceof ExperienceOrb) {
            return s.xpOrbs();
        }
        if (e instanceof AbstractArrow a && !(e instanceof Trident)) {
            return s.arrows() && a.isInBlock();
        }
        return false;
    }

    private static boolean trackable(Entity e) {
        return e instanceof Item || e instanceof ExperienceOrb || (e instanceof AbstractArrow && !(e instanceof Trident));
    }

    @EventHandler
    public void onAdd(EntityAddToWorldEvent event) {
        if (trackable(event.getEntity())) {
            tracked.put(event.getEntity().getUniqueId(), event.getEntity());
        }
    }

    @EventHandler
    public void onRemove(EntityRemoveFromWorldEvent event) {
        tracked.remove(event.getEntity().getUniqueId());
    }

    int trackedCount() {
        return tracked.size();
    }
}
