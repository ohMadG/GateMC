package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inventory, ender chest and experience live in the server's own player files, not the database, so a wipe arms them
 * for the player's next join (BlockvilleCore's PendingWipeListener and PendingWipeData). A restore arms the opposite:
 * giving back what that wipe emptied.
 *
 * <p>Safety:
 * <ul>
 *   <li>The armed row is read during pre-login (already off the game threads) and applied in the join event itself, on
 *       the player's thread, before they can click, drop or hand anything to anyone (BlockvilleCore waited a couple of
 *       seconds after join, which is a window to drop items).</li>
 *   <li>What is emptied is copied to {@code pvp_wipe_cleared} (first copy wins, so a re-applied wipe never overwrites
 *       it with an empty inventory), which is what a restore gives back.</li>
 *   <li>Order (checklist 7): copy, empty, save the player file, then delete the armed row. A crash before the row is
 *       deleted re-applies on the next join; the player file is saved before, so the items can't come back.</li>
 * </ul>
 */
final class PendingWipes implements Listener {

    private record Pending(String wipeId, boolean inventory, boolean ender, boolean xp, String labels, boolean fullWipe) {
    }

    private record Giveback(String wipeId, String restoreId, @Nullable String inventory, @Nullable String ender, int level, float exp, int totalExp) {
    }

    private record Loaded(@Nullable Pending pending, @Nullable Giveback giveback) {
    }

    private static final int INVENTORY_SIZE = 41;

    private final Database db;
    private final Logger logger;
    private final Map<UUID, Loaded> waiting = new ConcurrentHashMap<>();
    /** Give-backs applied whose row delete has not committed yet; a quick relog must not read and apply them again. */
    private final java.util.Set<String> appliedGivebacks = ConcurrentHashMap.newKeySet();
    private volatile boolean sendToSpawn;

    PendingWipes(Database db, Logger logger, boolean sendToSpawn) {
        this.db = db;
        this.logger = logger;
        this.sendToSpawn = sendToSpawn;
    }

    void sendToSpawn(boolean value) {
        sendToSpawn = value;
    }

    /** Pre-login is already async: read what is armed for this player. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID uuid = event.getUniqueId();
        try {
            Loaded read = db.sync(c -> new Loaded(readPending(c, uuid), readGiveback(c, uuid)));
            Giveback gb = read.giveback();
            Loaded loaded = gb != null && appliedGivebacks.contains(uuid + ":" + gb.restoreId())
                    ? new Loaded(read.pending(), null) : read;
            if (loaded.pending() != null || loaded.giveback() != null) {
                waiting.put(uuid, loaded);
            } else {
                waiting.remove(uuid);
            }
        } catch (SQLException e) {
            // Refuse rather than let them in with a wipe that could not be checked (they could move the items away).
            logger.log(Level.WARNING, "[wipe] Could not check for a pending wipe of " + event.getName(), e);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.bad("Your player data could not be loaded. Please try again in a moment."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Loaded loaded = waiting.remove(player.getUniqueId());
        if (loaded == null) {
            return;
        }
        if (loaded.pending() != null) {
            applyWipe(player, loaded.pending());
        } else if (loaded.giveback() != null) {
            applyGiveback(player, loaded.giveback());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }

    /** Player's thread. */
    private void applyWipe(Player player, Pending pending) {
        UUID uuid = player.getUniqueId();
        Snapshot copy = Snapshot.of(player);
        if (pending.inventory()) {
            player.closeInventory();
            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setArmorContents(null);
            inv.setItemInOffHand(null);
            player.setItemOnCursor(null);
        }
        if (pending.ender()) {
            player.getEnderChest().clear();
        }
        if (pending.xp()) {
            player.setLevel(0);
            player.setExp(0.0f);
            player.setTotalExperience(0);
        }
        player.saveData(); // the emptied inventory is on disk before the armed row goes (checklist 7)
        logger.info("[wipe] Emptied " + (pending.inventory() ? "inventory, " : "") + (pending.ender() ? "ender chest, " : "") + (pending.xp() ? "experience " : "")
                + "of " + player.getName() + " on join, as ordered by wipe " + pending.wipeId() + ".");
        db.asyncRun(c -> Database.transaction(c, tx -> {
            insertCleared(tx, pending.wipeId(), uuid, copy);
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM pvp_wipe_pending WHERE uuid = ? AND wipe_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, pending.wipeId());
                ps.executeUpdate();
            }
            return null;
        })).exceptionally(e -> {
            logger.log(Level.SEVERE, "[wipe] Emptied " + player.getName() + " but could not clear their pending-wipe row, so they will be emptied again on their next join", e);
            return null;
        });
        if (sendToSpawn) {
            Location spawn = Bukkit.getWorlds().get(0).getSpawnLocation();
            player.teleportAsync(spawn, PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
        player.sendMessage(Msg.bad("Your account has been wiped & player progress has been reset by an admin."));
        if (!pending.fullWipe() && !pending.labels().isBlank()) {
            player.sendMessage(Msg.neutral("Cleared: " + Msg.white(pending.labels())));
        }
        player.sendMessage(Msg.neutral("Wipe ID: " + Msg.white(pending.wipeId())));
    }

    /** Player's thread: puts back what a wipe emptied, keeping a copy of what they have now under the restore id. */
    private void applyGiveback(Player player, Giveback back) {
        UUID uuid = player.getUniqueId();
        String appliedKey = uuid + ":" + back.restoreId();
        if (!appliedGivebacks.add(appliedKey)) {
            return; // already applied this session; its row delete is still on the way
        }
        Snapshot current = Snapshot.of(player);
        PlayerInventory inv = player.getInventory();
        player.closeInventory();
        if (back.inventory() != null) {
            ItemStack[] items = fit(Items.decode(back.inventory()), INVENTORY_SIZE);
            inv.setStorageContents(Arrays.copyOfRange(items, 0, 36));
            inv.setArmorContents(Arrays.copyOfRange(items, 36, 40));
            inv.setItemInOffHand(items[40]);
        }
        if (back.ender() != null) {
            player.getEnderChest().setContents(fit(Items.decode(back.ender()), player.getEnderChest().getSize()));
        }
        player.setLevel(back.level());
        player.setExp(Math.max(0f, Math.min(0.9999f, back.exp())));
        player.setTotalExperience(back.totalExp());
        player.saveData();
        db.asyncRun(c -> Database.transaction(c, tx -> {
            insertCleared(tx, back.restoreId(), uuid, current);
            try (PreparedStatement ps = tx.prepareStatement("DELETE FROM pvp_wipe_giveback WHERE uuid = ? AND restore_id = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, back.restoreId());
                ps.executeUpdate();
            }
            return null;
        })).whenComplete((v, e) -> {
            if (e != null) {
                // Keep the in-memory guard: not applied again this uptime; after a restart it is applied again.
                logger.log(Level.SEVERE, "[wipe] Gave " + player.getName() + " their inventory back but could not clear the give-back row; it will be applied again after a restart", e);
            } else {
                appliedGivebacks.remove(appliedKey);
            }
        });
        player.sendMessage(Msg.good("Your inventory, ender chest and experience were restored by staff."));
    }

    private static ItemStack[] fit(ItemStack[] items, int size) {
        ItemStack[] out = new ItemStack[size];
        for (int i = 0; i < size && i < items.length; i++) {
            out[i] = items[i] == null || items[i].isEmpty() ? null : items[i];
        }
        return out;
    }

    /** A copy of everything a wipe can empty. Taken on the player's thread. */
    private record Snapshot(String inventory, String ender, int level, float exp, int totalExp) {
        static Snapshot of(Player player) {
            PlayerInventory inv = player.getInventory();
            ItemStack[] items = new ItemStack[INVENTORY_SIZE];
            ItemStack[] storage = inv.getStorageContents();
            for (int i = 0; i < 36 && i < storage.length; i++) {
                items[i] = storage[i];
            }
            ItemStack[] armor = inv.getArmorContents();
            for (int i = 0; i < 4 && i < armor.length; i++) {
                items[36 + i] = armor[i];
            }
            items[40] = inv.getItemInOffHand();
            return new Snapshot(Items.encode(items), Items.encode(player.getEnderChest().getContents()), player.getLevel(), player.getExp(), player.getTotalExperience());
        }
    }

    private static void insertCleared(Connection c, String wipeId, UUID uuid, Snapshot copy) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_wipe_cleared (wipe_id, uuid, inventory, ender, level, exp, total_exp, at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                        + "ON CONFLICT (wipe_id, uuid) DO NOTHING")) {
            ps.setString(1, wipeId);
            ps.setString(2, uuid.toString());
            ps.setString(3, copy.inventory());
            ps.setString(4, copy.ender());
            ps.setInt(5, copy.level());
            ps.setDouble(6, copy.exp());
            ps.setInt(7, copy.totalExp());
            ps.setLong(8, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    private static @Nullable Pending readPending(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT wipe_id, wipe_inventory, wipe_ender, wipe_xp, labels, full_wipe FROM pvp_wipe_pending WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new Pending(rs.getString(1), rs.getInt(2) != 0, rs.getInt(3) != 0, rs.getInt(4) != 0, rs.getString(5), rs.getInt(6) != 0) : null;
            }
        }
    }

    private static @Nullable Giveback readGiveback(Connection c, UUID uuid) throws SQLException {
        String wipeId;
        String restoreId;
        try (PreparedStatement ps = c.prepareStatement("SELECT wipe_id, restore_id FROM pvp_wipe_giveback WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                wipeId = rs.getString(1);
                restoreId = rs.getString(2);
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT inventory, ender, level, exp, total_exp FROM pvp_wipe_cleared WHERE wipe_id = ? AND uuid = ?")) {
            ps.setString(1, wipeId);
            ps.setString(2, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Giveback(wipeId, restoreId, rs.getString(1), rs.getString(2), rs.getInt(3), (float) rs.getDouble(4), rs.getInt(5));
            }
        }
    }
}
