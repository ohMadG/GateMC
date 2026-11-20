package me.Evan52098.genscore.maintenance;

import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerKickEvent;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Bedrock players (through Geyser) move in ways the server's own fly check misreads, and get kicked for "flying" while
 * walking. With Floodgate installed, cancel that one kick for Floodgate players. GrimAC still checks them properly.
 * Floodgate is looked up by reflection, so GensCore needs no dependency on it and this does nothing without it.
 */
final class BedrockFlyFix implements Listener {

    private final Object api;
    private final Method isFloodgatePlayer;

    private BedrockFlyFix(Object api, Method isFloodgatePlayer) {
        this.api = api;
        this.isFloodgatePlayer = isFloodgatePlayer;
    }

    /** Null when Floodgate is not installed or its API can't be found. */
    static BedrockFlyFix create(Logger logger) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) {
            return null;
        }
        try {
            Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object api = type.getMethod("getInstance").invoke(null);
            Method check = type.getMethod("isFloodgatePlayer", UUID.class);
            return new BedrockFlyFix(api, check);
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.warning("[maintenance] Floodgate is installed but its API was not found; the Bedrock fly-kick fix is off.");
            return null;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onKick(PlayerKickEvent event) {
        if (event.getCause() != PlayerKickEvent.Cause.FLYING_PLAYER) {
            return;
        }
        try {
            if (Boolean.TRUE.equals(isFloodgatePlayer.invoke(api, event.getPlayer().getUniqueId()))) {
                event.setCancelled(true);
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // leave the kick alone
        }
    }
}
