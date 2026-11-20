package me.Evan52098.genscore.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Lets another module stop a player teleporting (for example: combat tagged, or the destination is inside an enemy
 * claim). Called on the player's thread, both before the warmup starts and again just before the teleport.
 */
@FunctionalInterface
public interface TeleportGuard {

    /** A message explaining why the teleport is not allowed (MiniMessage), or null to allow it. */
    @Nullable String deny(Player player, Location destination, TeleportService.Kind kind);
}
