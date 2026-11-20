package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.util.Msg;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Keeps players out while their data is being wiped or restored (BlockvilleCore's WipeInProgress and
 * WipeLoginGuardListener; WipeHoldGuard's role too, without the Redis key, since this is one server).
 *
 * <p>The guard runs at LOWEST priority in pre-login, before {@code PlayerDataService} (HIGH) loads anyone; a refused
 * login is never loaded, so it can never save its old data back over the wipe. A season wipe locks everyone.
 */
final class WipeLocks implements Listener {

    private final Set<UUID> inProgress = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean everyone = new AtomicBoolean();
    private final Logger logger;

    WipeLocks(Logger logger) {
        this.logger = logger;
    }

    /** False if that player is already being wiped or restored, or a season run is going. */
    synchronized boolean begin(UUID player) {
        return !everyone.get() && inProgress.add(player);
    }

    void end(UUID player) {
        inProgress.remove(player);
    }

    boolean contains(UUID player) {
        return everyone.get() || inProgress.contains(player);
    }

    /** False if a season run is already going, or any single player run is. */
    synchronized boolean beginEveryone() {
        if (!inProgress.isEmpty()) {
            return false;
        }
        return everyone.compareAndSet(false, true);
    }

    void endEveryone() {
        everyone.set(false);
    }

    boolean everyoneLocked() {
        return everyone.get();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (everyone.get()) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.bad("The season is being reset. Please reconnect in a minute."));
            return;
        }
        if (inProgress.contains(event.getUniqueId())) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.bad("Your data has been wiped by an administrator."));
            logger.warning("[wipe] Refused the login of " + event.getName() + " (" + event.getUniqueId() + "): their wipe or restore is still running on this server.");
        }
    }
}
