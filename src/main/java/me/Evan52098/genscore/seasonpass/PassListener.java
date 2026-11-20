package me.Evan52098.genscore.seasonpass;

import org.bukkit.GameMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Counts the event-based challenge objectives. Every handler runs on the thread that owns the player/entity/block and
 * only touches in-memory pass state ({@link PassPlayer} is synchronized), so it is Folia-safe.
 *
 * <p>Anti-farm: creative mode never counts; a block a player placed does not count as mined for 6 hours (stops
 * place-and-break farming); a kill of the same victim counts once per {@code anti-farm.same-victim-minutes}; mobs from
 * spawners count only with {@code anti-farm.count-spawner-mobs}; AFK players earn no playtime.
 */
final class PassListener implements Listener {

    private static final long PLACED_TTL_MS = 6 * 60 * 60 * 1000L;
    private static final int PLACED_CAP = 500_000;

    private final PassService service;
    /** Recently player-placed blocks: "world:x:y:z" -> time placed. Thread-safe; purged by {@link #purgePlaced()}. */
    private final Map<String, Long> placed = new ConcurrentHashMap<>();

    PassListener(PassService service) {
        this.service = service;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.join(event.getPlayer().getUniqueId(), event.getPlayer().getName());
        service.box().deliverAfterJoin(event.getPlayer()); // checklist 5: rewards left in the box
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.quit(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (placed.size() >= PLACED_CAP) {
            purgePlaced();
        }
        placed.put(key(event.getBlockPlaced()), System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        Long when = placed.remove(key(event.getBlock()));
        if (p.getGameMode() == GameMode.CREATIVE || (when != null && System.currentTimeMillis() - when < PLACED_TTL_MS)) {
            return;
        }
        service.progress(p.getUniqueId(), Objective.MINE_BLOCKS, event.getBlock().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerKill(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.equals(victim) || killer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (service.killCounts(killer.getUniqueId(), victim.getUniqueId())) {
            service.progress(killer.getUniqueId(), Objective.KILL_PLAYERS, null, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMobKill(EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead instanceof Player) {
            return;
        }
        Player killer = dead.getKiller();
        if (killer == null || killer.getGameMode() == GameMode.CREATIVE) {
            return;
        }
        if (dead.fromMobSpawner() && !service.config().countSpawnerMobs()) {
            return;
        }
        service.progress(killer.getUniqueId(), Objective.KILL_MOBS, dead.getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH || !(event.getCaught() instanceof Item item)) {
            return;
        }
        service.progress(event.getPlayer().getUniqueId(), Objective.FISH, item.getItemStack().getType().name(), 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreed(EntityBreedEvent event) {
        if (event.getBreeder() instanceof Player p && p.getGameMode() != GameMode.CREATIVE) {
            service.progress(p.getUniqueId(), Objective.BREED_ANIMALS, event.getEntity().getType().name(), 1);
        }
    }

    void purgePlaced() {
        long cutoff = System.currentTimeMillis() - PLACED_TTL_MS;
        placed.values().removeIf(t -> t < cutoff);
        if (placed.size() >= PLACED_CAP) {
            placed.clear(); // last resort so memory stays bounded
        }
    }

    private static String key(Block b) {
        return b.getWorld().getUID() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }
}
