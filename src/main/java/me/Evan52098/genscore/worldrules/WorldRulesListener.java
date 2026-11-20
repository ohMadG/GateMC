package me.Evan52098.genscore.worldrules;

import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.entity.EnderDragon;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** The event side of {@link WorldRulesModule}. Every handler runs on the thread that owns the player or place. */
final class WorldRulesListener implements Listener {

    /** Teleport causes that could carry a player onto the closed dragon island. */
    private static final Set<String> END_BLOCKED_CAUSES = Set.of("END_GATEWAY", "ENDER_PEARL", "CHORUS_FRUIT",
            "CONSUMABLE_EFFECT", "SPECTATE", "UNKNOWN");

    private final WorldRulesModule module;
    /** Players we are already moving, so one fall or one step does not start ten teleports. Value: when it started. */
    private final Map<UUID, Long> moving = new ConcurrentHashMap<>();

    WorldRulesListener(WorldRulesModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------------------------------- spawn: portals

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPortalCreate(PortalCreateEvent event) {
        WorldRulesModule.Settings s = module.settings();
        if (s.spawn() == null || !s.blockSpawnPortals() || event.getReason() == PortalCreateEvent.CreateReason.END_PLATFORM) {
            return;
        }
        for (BlockState block : event.getBlocks()) {
            if (s.spawn().contains(block.getWorld().getName(), block.getX(), block.getY(), block.getZ())) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        WorldRulesModule.Settings s = module.settings();
        Player player = event.getPlayer();
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL && s.spawn() != null
                && s.blockSpawnPortals() && s.spawn().contains(event.getFrom())) {
            event.setCancelled(true);
            return;
        }
        // End lock: the stronghold portal would land on the obsidian platform next to the dragon island.
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL && s.endLock()
                && event.getFrom().getWorld().getEnvironment() != World.Environment.THE_END && !endBypass(player)) {
            event.setCancelled(true);
            player.sendMessage(Text.legacy(s.endMessage()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onEntityPortal(EntityPortalEvent event) {
        WorldRulesModule.Settings s = module.settings();
        Location from = event.getFrom();
        if (s.spawn() != null && s.blockSpawnPortals() && s.spawn().contains(from)
                && from.getWorld().getEnvironment() == World.Environment.NORMAL
                && event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.NETHER) {
            event.setCancelled(true);
            return;
        }
        // Items and mobs thrown into a stronghold portal would also reach the main island.
        if (s.endLock() && event.getTo() != null && event.getTo().getWorld() != null
                && event.getTo().getWorld().getEnvironment() == World.Environment.THE_END
                && from.getWorld().getEnvironment() != World.Environment.THE_END) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------------------------------- end lock

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        WorldRulesModule.Settings s = module.settings();
        if (!s.endLock() || !END_BLOCKED_CAUSES.contains(event.getCause().name()) || endBypass(event.getPlayer())) {
            return;
        }
        if (insideEndLock(s, event.getTo())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Text.legacy(s.endMessage()));
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDragonSpawn(CreatureSpawnEvent event) {
        WorldRulesModule.Settings s = module.settings();
        if (s.endLock() && s.noDragon() && event.getEntityType() == EntityType.ENDER_DRAGON) {
            event.setCancelled(true);
        }
    }

    /** A dragon saved in the world before the lock was switched on is removed when its chunk loads. */
    @EventHandler
    public void onEntityAdd(EntityAddToWorldEvent event) {
        WorldRulesModule.Settings s = module.settings();
        Entity e = event.getEntity();
        if (s.endLock() && s.noDragon() && e instanceof EnderDragon) {
            Tasks.entity.run(e, e::remove); // not while it is being added
        }
    }

    // ------------------------------------------------------------------------------------------- movement

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        check(event.getPlayer(), event.getTo());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        Tasks.entity.later(p, () -> check(p, p.getLocation()), 20);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        moving.remove(event.getPlayer().getUniqueId());
    }

    /** Runs on the player's thread. */
    private void check(Player player, Location to) {
        WorldRulesModule.Settings s = module.settings();
        if (to == null || to.getWorld() == null || busy(player.getUniqueId())) {
            return;
        }
        if (s.endLock() && insideEndLock(s, to) && !endBypass(player)) {
            player.sendMessage(Text.legacy(s.endMessage()));
            toSpawn(player);
            return;
        }
        Box spawn = s.spawn();
        if (spawn == null || !spawn.containsColumn(to.getWorld().getName(), to.getBlockX(), to.getBlockZ())) {
            return;
        }
        boolean closed = module.spawnClosed() && !player.hasPermission(WorldRulesModule.SPAWN_BYPASS)
                && player.getGameMode() != GameMode.SPECTATOR && to.getBlockY() >= spawn.minY() && to.getBlockY() <= spawn.maxY();
        if (closed) {
            player.sendMessage(Text.legacy(s.closedMessage()));
            awayFromSpawn(player);
            return;
        }
        if (to.getBlockY() < s.fallY() && player.getGameMode() != GameMode.SPECTATOR) {
            toSpawn(player);
        }
    }

    private boolean busy(UUID id) {
        Long since = moving.get(id);
        if (since == null) {
            return false;
        }
        if (System.currentTimeMillis() - since > 10_000) { // a teleport that never finished
            moving.remove(id, since);
            return false;
        }
        return true;
    }

    private void toSpawn(Player player) {
        Location spawn = SpawnPoint.get();
        if (spawn == null) {
            return;
        }
        if (module.spawnClosed() && !player.hasPermission(WorldRulesModule.SPAWN_BYPASS)
                && module.settings().spawn() != null && module.settings().spawn().contains(spawn)) {
            awayFromSpawn(player);
            return;
        }
        UUID id = player.getUniqueId();
        moving.put(id, System.currentTimeMillis());
        player.setFallDistance(0);
        // Not from inside the move event: on the next tick of the player's own thread.
        Tasks.entity.run(player, () -> player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((ok, err) -> moving.remove(id)));
    }

    /** Spawn is closed: only the message is sent (GensCore has no rtp; players go to their plot with /plot). */
    private void awayFromSpawn(Player player) {
        moving.put(player.getUniqueId(), System.currentTimeMillis()); // stops the message repeating every step
    }

    private static boolean insideEndLock(WorldRulesModule.Settings s, Location l) {
        if (l == null || l.getWorld() == null || l.getWorld().getEnvironment() != World.Environment.THE_END) {
            return false;
        }
        if (!l.getWorld().getName().equals(s.endWorld())) {
            return false;
        }
        double r = s.endRadius();
        return l.getX() * l.getX() + l.getZ() * l.getZ() < r * r;
    }

    /** HxCore rule: the bypass only works in creative or spectator, so staff can't fight the dragon by accident. */
    private static boolean endBypass(Player p) {
        return p.hasPermission(WorldRulesModule.END_BYPASS)
                && (p.getGameMode() == GameMode.CREATIVE || p.getGameMode() == GameMode.SPECTATOR);
    }
}
