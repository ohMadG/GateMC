package me.Evan52098.genscore.util;

import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Writes a player's own file (inventory, ender chest) to disk now instead of at the next vanilla autosave.
 *
 * <p>Why: GensCore stores moved items in the database at once (vaults, auction listings, trades, mail), but the
 * player's inventory reaches disk only when the player file saves. A crash in between can duplicate items (taken
 * out of the inventory, stored in the database, but the old inventory comes back) or lose them. Every item move that
 * touches the database calls this right after changing the inventory, and orders the steps so a crash can lose at
 * most the one move in flight, never duplicate it:
 * <ul>
 *   <li>into storage: remove from inventory, {@code PlayerFiles.save(player)}, then write the database row;</li>
 *   <li>out of storage: mark the row taken in the database, give the items, then {@code PlayerFiles.save(player)}.</li>
 * </ul>
 * Call on the player's thread (Folia).
 */
public final class PlayerFiles {

    private static final Logger LOG = Logger.getLogger("GensCore");

    private PlayerFiles() {
    }

    public static void save(Player player) {
        if (!player.isOnline()) {
            return; // the quit save already wrote it
        }
        try {
            player.saveData();
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "Could not save the player file of " + player.getName() + " right away", e);
        }
    }
}
