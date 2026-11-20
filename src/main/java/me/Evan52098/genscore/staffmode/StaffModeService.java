package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.util.PlayerFiles;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Staff mode: vanish, a staff hotbar instead of the inventory, flight, no damage, look-only (no item moves).
 *
 * <p><b>Saving the inventory</b> (dupe checklist 5, 7, 12). The real inventory is written to {@code pvp_staffmode_saved}
 * <i>before</i> it is cleared, and the player carries a marker ({@code genscore:staffmode} in their persistent data,
 * which lives in their player file next to the inventory). The player file therefore always tells which inventory it
 * holds: marker present = the staff hotbar (restore from the row), marker absent = their real items (any row left is
 * stale and is deleted, never restored). Restoring <i>replaces</i> the inventory with the saved one, it never adds.
 * <ul>
 *   <li>enter: write row, then on the player's thread check the inventory did not change since the copy, set the
 *       marker, clear, give the hotbar, save the player file;</li>
 *   <li>leave (command, quit, death, disable): put the saved contents back, remove the marker, save the player file,
 *       then delete the row;</li>
 *   <li>join: marker and row = restore; no marker = delete any row.</li>
 * </ul>
 * A crash at any step leaves either the real items in the file (no marker), or the hotbar with a marker and a row.
 * Staff in staff mode cannot pick up, drop, move, place or take items, so the hotbar holds nothing of value.
 */
final class StaffModeService {

    static final String PERM = "genscore.staffmode";
    static final String BYPASS = "genscore.staffmode.bypass";

    /** What is needed to put a staff member back. */
    record Saved(ItemStack[] contents, GameMode gameMode, boolean allowFlight, boolean flying, boolean wasVanished, long at) {
    }

    private final Plugin plugin;
    private final Database db;
    private final Logger log;
    private final VanishBridge vanish;
    private final DebugInfo debug;
    private final NamespacedKey markerKey;
    private final NamespacedKey toolKey;
    private final Map<UUID, Saved> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();

    StaffModeService(Plugin plugin, Database db, Logger log, VanishBridge vanish, DebugInfo debug) {
        this.plugin = plugin;
        this.db = db;
        this.log = log;
        this.vanish = vanish;
        this.debug = debug;
        this.markerKey = new NamespacedKey(plugin, "staffmode");
        this.toolKey = new NamespacedKey(plugin, "staff_tool");
    }

    boolean inStaffMode(UUID player) {
        return sessions.containsKey(player);
    }

    Set<UUID> staff() {
        return sessions.keySet();
    }

    VanishBridge vanish() {
        return vanish;
    }

    DebugInfo debug() {
        return debug;
    }

    /** The staff tool id on an item, or null. */
    @Nullable String toolOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(toolKey, PersistentDataType.STRING);
    }

    // ------------------------------------------------------------------ enter

    /** Enters staff mode. Call on the player's thread. {@code then} runs on their thread once they are in. */
    void enter(Player player, @Nullable Runnable then) {
        UUID id = player.getUniqueId();
        if (sessions.containsKey(id)) {
            if (then != null) {
                then.run();
            }
            return;
        }
        if (!busy.add(id)) { // checklist 3: one enter or leave at a time
            return;
        }
        ItemStack[] contents = copy(player.getInventory().getContents());
        String encoded = Items.encode(contents);
        GameMode mode = player.getGameMode();
        boolean allowFlight = player.getAllowFlight();
        boolean flying = player.isFlying();
        boolean wasVanished = vanish.isVanished(player);
        long at = System.currentTimeMillis();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_staffmode_saved (uuid, contents, gamemode, allow_flight, flying, was_vanished, at) "
                            + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET contents = excluded.contents, "
                            + "gamemode = excluded.gamemode, allow_flight = excluded.allow_flight, flying = excluded.flying, "
                            + "was_vanished = excluded.was_vanished, at = excluded.at")) {
                ps.setString(1, id.toString());
                ps.setString(2, encoded);
                ps.setString(3, mode.name());
                ps.setInt(4, allowFlight ? 1 : 0);
                ps.setInt(5, flying ? 1 : 0);
                ps.setInt(6, wasVanished ? 1 : 0);
                ps.setLong(7, at);
                ps.executeUpdate();
            }
        }).whenComplete((v, error) -> {
            if (error != null) {
                busy.remove(id);
                log.log(Level.SEVERE, "[staffmode] Could not save the inventory of " + player.getName() + "; not entering", error);
                Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("Could not save your inventory, so staff mode was not turned on.")));
                return;
            }
            if (Tasks.entity.later(player, () -> finishEnter(player, encoded, mode, allowFlight, flying, wasVanished, at, then),
                    () -> busy.remove(id), 1) == null) {
                busy.remove(id); // logged out meanwhile: inventory untouched, the row is stale and removed at next join
            }
        });
    }

    private void finishEnter(Player player, String encoded, GameMode mode, boolean allowFlight, boolean flying,
                             boolean wasVanished, long at, @Nullable Runnable then) {
        UUID id = player.getUniqueId();
        try {
            // Checklist 9: the row must match what is in the inventory now, or a drop in between would duplicate.
            if (!player.isOnline() || !encoded.equals(Items.encode(copy(player.getInventory().getContents())))) {
                player.sendMessage(Msg.bad("Your inventory changed while staff mode was starting. Try again."));
                deleteRow(id, at, 0);
                return;
            }
            sessions.put(id, new Saved(Items.decode(encoded), mode, allowFlight, flying, wasVanished, at));
            player.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
            PlayerInventory inv = player.getInventory();
            inv.clear();
            giveHotbar(player);
            PlayerFiles.save(player); // checklist 12: the cleared inventory and the marker reach disk together
            player.closeInventory();
            player.setAllowFlight(true);
            player.setFlying(true);
            player.setFallDistance(0);
            vanish.set(player, true);
            if (!player.hasPermission(BYPASS)) {
                debug.apply(player, true);
            }
            player.sendMessage(Msg.good("Staff mode enabled. You are vanished. Your inventory is saved."));
            log.info("[staffmode] " + player.getName() + " entered staff mode");
        } finally {
            busy.remove(id);
        }
        if (then != null) {
            then.run();
        }
    }

    // ------------------------------------------------------------------ leave

    /** Leaves staff mode and restores everything. Call on the player's thread. Returns false if not in staff mode. */
    boolean leave(Player player, boolean quitting) {
        UUID id = player.getUniqueId();
        Saved saved = sessions.remove(id);
        if (saved == null) {
            return false;
        }
        restore(player, saved, quitting);
        player.sendMessage(Msg.good("Staff mode disabled. Your inventory is back."));
        log.info("[staffmode] " + player.getName() + " left staff mode" + (quitting ? " (logged out)" : ""));
        return true;
    }

    private void restore(Player player, Saved saved, boolean quitting) {
        player.closeInventory();
        player.setItemOnCursor(null);
        player.getInventory().setContents(copy(saved.contents())); // replaces, never adds
        player.getPersistentDataContainer().remove(markerKey);
        player.setGameMode(saved.gameMode());
        player.setAllowFlight(saved.allowFlight() || saved.gameMode() == GameMode.CREATIVE || saved.gameMode() == GameMode.SPECTATOR);
        player.setFlying(saved.flying() && player.getAllowFlight());
        player.setFallDistance(0);
        if (!quitting) {
            vanish.set(player, saved.wasVanished()); // on quit the vanish state is left as it is (it lasts across relogs)
            debug.apply(player, false);
            PlayerFiles.save(player); // before the row goes (checklist 12)
            deleteRow(player.getUniqueId(), saved.at(), 0);
        } else {
            // The player file is written by the server right after the quit event; delete the row only well after
            // that, and only this exact row (a quick rejoin may already have written a newer one).
            deleteRow(player.getUniqueId(), saved.at(), 10);
        }
    }

    /** Join: restore after a crash, or drop a stale row. Call on the player's thread. */
    void onJoin(Player player) {
        UUID id = player.getUniqueId();
        boolean marked = player.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
        if (!marked) {
            removeStrayTools(player);
            deleteRow(id, -1, 0); // a row without the marker is stale: the file holds the real items
            return;
        }
        busy.add(id);
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT contents, gamemode, allow_flight, flying, was_vanished, at FROM pvp_staffmode_saved WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        return (Saved) null;
                    }
                    GameMode gm;
                    try {
                        gm = GameMode.valueOf(rs.getString(2));
                    } catch (IllegalArgumentException e) {
                        gm = GameMode.SURVIVAL;
                    }
                    return new Saved(Items.decode(rs.getString(1)), gm, rs.getInt(3) != 0, rs.getInt(4) != 0, rs.getInt(5) != 0, rs.getLong(6));
                }
            }
        }).whenComplete((saved, error) -> {
            if (Tasks.entity.run(player, () -> {
                try {
                    if (error != null) {
                        log.log(Level.SEVERE, "[staffmode] Could not load the saved inventory of " + player.getName(), error);
                        player.sendMessage(Msg.bad("Your saved inventory could not be loaded. Rejoin, or ask an admin; do not pick anything up."));
                        return;
                    }
                    if (saved == null) {
                        log.warning("[staffmode] " + player.getName() + " has the staff mode marker but no saved inventory; removing staff tools only");
                        removeStrayTools(player);
                        player.getPersistentDataContainer().remove(markerKey);
                        PlayerFiles.save(player);
                        return;
                    }
                    restore(player, saved, false);
                    player.sendMessage(Msg.good("You were in staff mode when you left. Your inventory is back."));
                    log.info("[staffmode] Restored the saved inventory of " + player.getName() + " at join");
                } finally {
                    busy.remove(id);
                }
            }) == null) {
                busy.remove(id);
            }
        });
    }

    /** Disable: put back everyone this thread may touch; the rest are restored at their next join. */
    void leaveAll() {
        for (UUID id : List.copyOf(sessions.keySet())) {
            Player p = plugin.getServer().getPlayer(id);
            if (p != null && Tasks.owns(p)) {
                Saved saved = sessions.remove(id);
                if (saved != null) {
                    try {
                        restore(p, saved, true);
                        PlayerFiles.save(p); // the row is deleted 10 s later, or at the next join if the server stops first
                    } catch (RuntimeException e) {
                        log.log(Level.WARNING, "[staffmode] Could not restore " + p.getName() + " on disable; restored at next join", e);
                    }
                }
            }
        }
    }

    /**
     * Deletes the saved row after {@code delaySeconds}: {@code at} = only that exact row, -1 = any row written before
     * now (so a staff mode entered right after this call keeps its new row).
     */
    private void deleteRow(UUID id, long at, long delaySeconds) {
        long before = System.currentTimeMillis();
        Runnable delete = () -> db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(at < 0
                    ? "DELETE FROM pvp_staffmode_saved WHERE uuid = ? AND at < ?"
                    : "DELETE FROM pvp_staffmode_saved WHERE uuid = ? AND at = ?")) {
                ps.setString(1, id.toString());
                ps.setLong(2, at < 0 ? before : at);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            // Harmless: without the marker the row is never used, and the next join deletes it again.
            log.log(Level.WARNING, "[staffmode] Could not delete the saved row of " + id, e);
            return null;
        });
        if (delaySeconds <= 0) {
            delete.run();
        } else {
            Tasks.async.later(delete, delaySeconds, java.util.concurrent.TimeUnit.SECONDS);
        }
    }

    private void removeStrayTools(Player player) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] contents = inv.getContents();
        boolean changed = false;
        for (int i = 0; i < contents.length; i++) {
            if (toolOf(contents[i]) != null) {
                contents[i] = null;
                changed = true;
            }
        }
        if (changed) {
            inv.setContents(contents);
        }
    }

    // ------------------------------------------------------------------ hotbar

    private void giveHotbar(Player player) {
        PlayerInventory inv = player.getInventory();
        inv.setItem(0, tool("random", Material.COMPASS, "Random teleport", "Teleport to a random player."));
        inv.setItem(1, tool("freeze", Material.PACKED_ICE, "Freeze", "Right-click a player to freeze or unfreeze them."));
        inv.setItem(2, tool("invsee", Material.CHEST, "Inventory", "Right-click a player to see their inventory."));
        inv.setItem(4, tool("inspect", Material.BOOK, "Inspect", "Right-click a player to see their details."));
        inv.setItem(8, tool("vanish", Material.LIME_DYE, "Vanish", "Toggle vanish."));
        inv.setHeldItemSlot(0);
    }

    private ItemStack tool(String id, Material material, String name, String description) {
        ItemStack item = Lore.item(material, "&b" + name, Lore.builder(Lore.Accent.CYAN)
                .description(description).clickPrompt("use").build());
        item.editMeta(meta -> meta.getPersistentDataContainer().set(toolKey, PersistentDataType.STRING, id));
        return item;
    }

    private static ItemStack[] copy(ItemStack[] items) {
        ItemStack[] out = new ItemStack[items.length];
        for (int i = 0; i < items.length; i++) {
            out[i] = items[i] == null ? null : items[i].clone();
        }
        return out;
    }
}
