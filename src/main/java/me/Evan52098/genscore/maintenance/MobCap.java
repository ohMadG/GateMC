package me.Evan52098.genscore.maintenance;

import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.world.EntitiesLoadEvent;

import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Mob cleanup: no more than {@code max-per-chunk} ordinary mobs in one chunk. New spawns over the cap are cancelled,
 * and when a chunk loads with more than the cap the extra ones are removed. Named, tamed, leashed and riding mobs and
 * the excluded types are never counted or removed. Both events fire on the region thread that owns the chunk, so
 * reading the chunk's entities there is allowed (Folia rule 2).
 */
final class MobCap implements Listener {

    private record Settings(boolean enabled, int maxPerChunk, boolean trimOnLoad, Set<CreatureSpawnEvent.SpawnReason> reasons,
                            Set<EntityType> exclude) {
    }

    private volatile Settings settings = new Settings(false, 0, false, Set.of(), Set.of());

    void load(ConfigurationSection c) {
        Set<CreatureSpawnEvent.SpawnReason> reasons = EnumSet.noneOf(CreatureSpawnEvent.SpawnReason.class);
        List<String> reasonNames = c.contains("reasons") ? c.getStringList("reasons") : List.of("NATURAL", "SPAWNER", "BREEDING",
                "EGG", "DISPENSE_EGG", "SPAWNER_EGG", "TRIAL_SPAWNER", "PATROL", "JOCKEY", "MOUNT", "REINFORCEMENTS");
        for (String n : reasonNames) {
            try {
                reasons.add(CreatureSpawnEvent.SpawnReason.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // unknown reason name in config
            }
        }
        Set<EntityType> exclude = EnumSet.noneOf(EntityType.class);
        List<String> excludeNames = c.contains("exclude") ? c.getStringList("exclude") : List.of("VILLAGER", "WANDERING_TRADER",
                "IRON_GOLEM", "ENDER_DRAGON", "WITHER", "ELDER_GUARDIAN", "WARDEN", "SHULKER", "ALLAY", "SNIFFER");
        for (String n : excludeNames) {
            try {
                exclude.add(EntityType.valueOf(n.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // unknown entity type in config
            }
        }
        settings = new Settings(c.getBoolean("enabled", true), Math.max(1, c.getInt("max-per-chunk", 40)),
                c.getBoolean("trim-on-load", true), reasons, exclude);
    }

    private static boolean counted(Entity e, Settings s) {
        if (!(e instanceof Mob mob) || s.exclude().contains(e.getType())) {
            return false;
        }
        if (mob.customName() != null || mob.isLeashed() || mob.isInsideVehicle() || !mob.getPassengers().isEmpty()) {
            return false;
        }
        return !(mob instanceof Tameable t && t.isTamed());
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        Settings s = settings;
        if (!s.enabled() || !s.reasons().contains(event.getSpawnReason()) || s.exclude().contains(event.getEntityType())) {
            return;
        }
        int count = 0;
        for (Entity e : event.getLocation().getChunk().getEntities()) {
            if (counted(e, s) && ++count >= s.maxPerChunk()) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler
    public void onLoad(EntitiesLoadEvent event) {
        Settings s = settings;
        if (!s.enabled() || !s.trimOnLoad()) {
            return;
        }
        int count = 0;
        for (Entity e : event.getEntities()) {
            if (counted(e, s) && ++count > s.maxPerChunk()) {
                Tasks.entity.run(e, () -> {
                    if (e.isValid()) {
                        e.remove();
                    }
                });
            }
        }
    }
}
