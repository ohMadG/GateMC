package me.Evan52098.genscore.teleport;

import me.Evan52098.genscore.player.PlayerDataService;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

/** Warmup cancel on damage, /back after death, respawn and first join at spawn. All events run on the player's thread. */
final class TeleportListener implements Listener {

    private final TeleportService teleports;
    private final TeleportCommands commands;
    private final TpaCommands tpa;
    private final PlayerDataService players;
    private final boolean backOnDeath;
    private final boolean respawnAtSpawn;
    private final boolean firstJoinAtSpawn;

    TeleportListener(TeleportService teleports, TeleportCommands commands, TpaCommands tpa, PlayerDataService players,
                     boolean backOnDeath, boolean respawnAtSpawn, boolean firstJoinAtSpawn) {
        this.teleports = teleports;
        this.commands = commands;
        this.tpa = tpa;
        this.players = players;
        this.backOnDeath = backOnDeath;
        this.respawnAtSpawn = respawnAtSpawn;
        this.firstJoinAtSpawn = firstJoinAtSpawn;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && teleports.cancelOnDamage() && teleports.inWarmup(player.getUniqueId())) {
            teleports.cancel(player, "Teleport canceled - you took damage.");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        teleports.cancel(player, null);
        if (backOnDeath) {
            teleports.rememberBack(player, player.getLocation());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!respawnAtSpawn || event.isBedSpawn() || event.isAnchorSpawn()) {
            return;
        }
        Location spawn = commands.spawnLocation();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!firstJoinAtSpawn || players.get(player.getUniqueId()) == null || !players.get(player).isFirstTime()) {
            return;
        }
        Location spawn = commands.spawnLocation();
        if (spawn != null) {
            player.teleportAsync(spawn);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        teleports.forget(event.getPlayer().getUniqueId());
        tpa.forget(event.getPlayer().getUniqueId());
    }
}
