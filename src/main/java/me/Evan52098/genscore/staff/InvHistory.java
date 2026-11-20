package me.Evan52098.genscore.staff;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Inventory history, matching BlockvilleCore's InventorySnapshotService and InventoryHistoryGui:
 * <ul>
 *   <li>Snapshots of inventory, armor, off-hand and ender chest on death, join, quit, every 10 minutes, and before a
 *       restore, each kind with its own limit (death 10, periodic 6, quit 6, join 3, pre-restore 5).</li>
 *   <li>{@code /invhistory <player>} opens a menu of snapshots (21 per page, icon by trigger). Clicking one opens a
 *       preview with Inventory / Armor &amp; Off-hand / Ender Chest views and a Restore button.</li>
 *   <li>Restoring saves the player's current state first, then replaces inventory, armor, off-hand and ender chest.
 *       An offline player's restore is queued and applied the next time they join.</li>
 * </ul>
 * Left out: the cross-server relay (one server here) and the "Server" line.
 *
 * <p>Folia: a snapshot is copied on the player's thread, stored on a database thread; a restore runs on the
 * player's thread; menus open on the staff member's thread.
 */
public final class InvHistory implements Listener {

    public static final String PERIODIC = "PERIODIC";
    public static final String DEATH = "DEATH";
    public static final String PRE_RESTORE = "PRE_RESTORE";
    public static final String MANUAL = "MANUAL";
    public static final String JOIN = "JOIN";
    public static final String QUIT = "QUIT";

    private static final int INVENTORY_SIZE = 41; // 0-35 storage, 36-39 armor (boots..helmet), 40 off-hand
    private static final int ENDER_SIZE = 27;
    private static final int PAGE_SIZE = 21;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneOffset.UTC);

    private record Row(long id, long at, String trigger) {
    }

    private record Snapshot(long id, UUID owner, long at, String trigger, ItemStack[] inventory, ItemStack[] ender) {
    }

    private enum Outcome { APPLIED_LIVE, QUEUED_OFFLINE, NOT_FOUND }

    private enum View {
        INVENTORY("Inventory", Material.CHEST),
        GEAR("Armor & Off-hand", Material.IRON_CHESTPLATE),
        ENDER_CHEST("Ender Chest", Material.ENDER_CHEST);

        final String label;
        final Material icon;

        View(String label, Material icon) {
            this.label = label;
            this.icon = icon;
        }
    }

    private final Database db;
    private final PlayerDataService players;
    private final Logger logger;

    InvHistory(Database db, PlayerDataService players, Logger logger) {
        this.db = db;
        this.players = players;
        this.logger = logger;
        Tasks.async.timer(t -> {
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.entity.run(p, () -> capture(p, PERIODIC));
            }
        }, 10, 10, TimeUnit.MINUTES);
    }

    private static int retentionFor(String trigger) {
        return switch (trigger) {
            case PERIODIC, QUIT -> 6;
            case DEATH -> 10;
            case PRE_RESTORE -> 5;
            case JOIN -> 3;
            default -> 10;
        };
    }

    // ------------------------------------------------------------------ capturing

    /** Copies the player's inventory and ender chest. Call on the player's thread. */
    public CompletableFuture<Void> capture(Player player, String trigger) {
        PlayerInventory inv = player.getInventory();
        ItemStack[] items = new ItemStack[INVENTORY_SIZE];
        ItemStack[] storage = inv.getStorageContents();
        for (int i = 0; i < 36 && i < storage.length; i++) {
            items[i] = copy(storage[i]);
        }
        ItemStack[] armor = inv.getArmorContents();
        for (int i = 0; i < 4 && i < armor.length; i++) {
            items[36 + i] = copy(armor[i]);
        }
        items[40] = copy(inv.getItemInOffHand());
        ItemStack[] ender = player.getEnderChest().getContents();
        ItemStack[] enderCopy = new ItemStack[ENDER_SIZE];
        for (int i = 0; i < ENDER_SIZE && i < ender.length; i++) {
            enderCopy[i] = copy(ender[i]);
        }
        String invData = Items.encode(items);
        String enderData = Items.encode(enderCopy);
        int stacks = countStacks(items);
        UUID uuid = player.getUniqueId();
        long at = System.currentTimeMillis();
        int keep = retentionFor(trigger);
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_inv_snapshots (uuid, at, reason, items, data, ender) VALUES (?, ?, ?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setLong(2, at);
                ps.setString(3, trigger);
                ps.setInt(4, stacks);
                ps.setString(5, invData);
                ps.setString(6, enderData);
                ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "DELETE FROM pvp_inv_snapshots WHERE uuid = ? AND reason = ? AND id NOT IN "
                            + "(SELECT id FROM pvp_inv_snapshots WHERE uuid = ? AND reason = ? ORDER BY id DESC LIMIT ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, trigger);
                ps.setString(3, uuid.toString());
                ps.setString(4, trigger);
                ps.setInt(5, keep);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "Failed to capture an inventory snapshot for " + player.getName(), e);
            return null;
        });
    }

    private static @Nullable ItemStack copy(@Nullable ItemStack s) {
        return s == null || s.isEmpty() ? null : s.clone();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        capture(event.getPlayer(), DEATH);
    }

    /** Queued restores read (and taken out of the table) at pre-login, applied in the join tick. */
    private final java.util.Map<UUID, Snapshot> queuedRestores = new java.util.concurrent.ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(org.bukkit.event.player.AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != org.bukkit.event.player.AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        UUID id = event.getUniqueId();
        try {
            Snapshot snap = db.sync(c -> {
                Long snapshotId = null;
                try (PreparedStatement ps = c.prepareStatement("SELECT snapshot_id FROM pvp_inv_pending WHERE uuid = ?")) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            snapshotId = rs.getLong(1);
                        }
                    }
                }
                return snapshotId == null ? null : read(c, snapshotId);
            });
            if (snap != null) {
                queuedRestores.put(id, snap);
            }
        } catch (SQLException e) {
            logger.log(Level.WARNING, "Failed to check pending inventory restore for " + event.getName(), e);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Snapshot queued = queuedRestores.remove(player.getUniqueId());
        if (queued == null) {
            capture(player, JOIN);
            return;
        }
        // Same tick as the join: the player has no chance to drop or stash anything first.
        capture(player, PRE_RESTORE);
        apply(player, queued);
        me.Evan52098.genscore.util.PlayerFiles.save(player);
        UUID id = player.getUniqueId();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_inv_pending WHERE uuid = ? AND snapshot_id = ?")) {
                ps.setString(1, id.toString());
                ps.setLong(2, queued.id());
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.SEVERE, "Restored a queued inventory for " + player.getName() + " but could not clear the queue row; it would apply again on their next join", e);
            return null;
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        queuedRestores.remove(event.getPlayer().getUniqueId()); // still in the table: applied on their next join
        capture(event.getPlayer(), QUIT);
    }

    // ------------------------------------------------------------------ menu

    @Command("invhistory")
    @CommandPermission("genscore.invhistory")
    @Description("Browse and restore a player's inventory snapshot history")
    public void history(BukkitCommandActor actor, @Named("player") String targetName) {
        Player staff = actor.requirePlayer();
        players.findUuid(targetName).whenComplete((found, error) -> Tasks.entity.run(staff, () -> {
            if (error != null || found.isEmpty()) {
                staff.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined."));
                return;
            }
            open(staff, found.get(), targetName, 0);
        }));
    }

    private void open(Player staff, UUID target, String targetName, int page) {
        db.async(c -> {
            List<Row> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, at, reason FROM pvp_inv_snapshots WHERE uuid = ? ORDER BY id DESC")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Row(rs.getLong(1), rs.getLong(2), rs.getString(3).toUpperCase(Locale.ROOT)));
                    }
                }
            }
            return rows;
        }).whenComplete((rows, error) -> Tasks.entity.run(staff, () -> {
            if (error != null) {
                staff.sendMessage(Msg.bad("Inventory history could not be loaded right now."));
                return;
            }
            render(staff, target, targetName, rows, page);
        }));
    }

    private void render(Player staff, UUID target, String targetName, List<Row> rows, int page) {
        int totalPages = Math.max(1, (int) Math.ceil(rows.size() / (double) PAGE_SIZE));
        int clamped = Math.max(0, Math.min(page, totalPages - 1));
        int start = clamped * PAGE_SIZE;
        int end = Math.min(start + PAGE_SIZE, rows.size());
        String title = "<gray>" + Msg.escape(targetName) + "'s Inventory History</gray>"
                + (totalPages > 1 ? " <dark_gray>" + (clamped + 1) + "/" + totalPages : "");
        Menu menu = new Menu(4, Msg.parse(title));

        if (rows.isEmpty()) {
            menu.set(4, 1, Lore.item(Material.BARRIER, Lore.Accent.GREEN.code() + "&lNo Snapshots Yet",
                    Lore.builder(Lore.Accent.GREEN).divider().description("Nothing has been captured", "for this player.").divider().build()), null);
        }
        int slot = 0;
        for (int i = start; i < end; i++) {
            Row row = rows.get(i);
            List<String> lore = Lore.builder(Lore.Accent.WHITE)
                    .subcategory("Inventory Snapshot")
                    .divider()
                    .stat("Trigger", row.trigger())
                    .stat("Captured", TIME.format(Instant.ofEpochMilli(row.at())))
                    .clickPrompt("Click to Preview")
                    .build();
            menu.set(slot++, Lore.item(iconFor(row.trigger()), Lore.Accent.WHITE.code() + "&l" + row.trigger() + " Snapshot", lore),
                    click -> preview(staff, target, targetName, row.id(), View.INVENTORY, clamped));
        }
        if (clamped > 0) {
            menu.set(29, pageButton(false), click -> open(staff, target, targetName, clamped - 1));
        }
        if (clamped < totalPages - 1) {
            menu.set(33, pageButton(true), click -> open(staff, target, targetName, clamped + 1));
        }
        menu.set(31, Lore.item(Material.BARRIER, Lore.Accent.RED.code() + "&lClose",
                Lore.builder(Lore.Accent.RED).divider().description("Closes this menu.").clickPrompt("Click to Close").build()),
                click -> staff.closeInventory());
        menu.open(staff);
    }

    private static ItemStack pageButton(boolean forward) {
        Lore.Accent accent = forward ? Lore.Accent.GREEN : Lore.Accent.RED;
        String label = forward ? "Next Page →" : "← Previous Page";
        return Lore.item(Material.ARROW, accent.code() + "&l" + label,
                Lore.builder(accent).divider().clickPrompt(forward ? "Click for Next Page" : "Click for Previous Page").build());
    }

    private void preview(Player staff, UUID target, String targetName, long id, View view, int page) {
        load(id).whenComplete((snap, error) -> Tasks.entity.run(staff, () -> {
            if (error != null || snap == null) {
                staff.sendMessage(Msg.bad("That snapshot no longer exists."));
                open(staff, target, targetName, page);
                return;
            }
            showPreview(staff, targetName, snap, view, page);
        }));
    }

    private void showPreview(Player staff, String targetName, Snapshot snap, View view, int page) {
        ItemStack[] items = snap.inventory();
        ItemStack[] ender = snap.ender();
        Menu menu = new Menu(6, Msg.parse("<gray>" + Msg.escape(targetName) + " - " + view.label + "</gray>"));
        switch (view) {
            case INVENTORY -> {
                for (int i = 9; i < 36; i++) {
                    shown(menu, items[i], i % 9, i / 9 - 1);
                }
                for (int i = 0; i < 9; i++) {
                    shown(menu, items[i], i, 3);
                }
            }
            case GEAR -> {
                String[] names = {"Helmet", "Chestplate", "Leggings", "Boots", "Off-hand"};
                ItemStack[] gear = {items[39], items[38], items[37], items[36], items[40]};
                for (int i = 0; i < gear.length; i++) {
                    if (isEmpty(gear[i])) {
                        menu.set(2 + i, 1, Lore.item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "&7No " + names[i], List.of()), null);
                    } else {
                        shown(menu, gear[i], 2 + i, 1);
                    }
                }
            }
            case ENDER_CHEST -> {
                for (int i = 0; i < ender.length; i++) {
                    shown(menu, ender[i], i % 9, i / 9);
                }
            }
        }
        menu.set(0, 5, Lore.item(Material.ARROW, Lore.Accent.RED.code() + "&l← Back",
                Lore.builder(Lore.Accent.RED).divider().description("Back to the snapshot list.").clickPrompt("Click to Go Back").build()),
                click -> open(staff, snap.owner(), targetName, page));
        View[] others = Arrays.stream(View.values()).filter(v -> v != view).toArray(View[]::new);
        for (int i = 0; i < others.length; i++) {
            View other = others[i];
            menu.set(i == 0 ? 2 : 6, 5, Lore.item(other.icon, Lore.Accent.WHITE.code() + "&l" + other.label,
                    Lore.builder(Lore.Accent.WHITE).divider().description("Show this snapshot's " + other.label.toLowerCase(Locale.ROOT) + ".")
                            .clickPrompt("Click to View").build()),
                    click -> showPreview(staff, targetName, snap, other, page));
        }
        menu.set(4, 5, Lore.item(iconFor(snap.trigger()), Lore.Accent.WHITE.code() + "&l" + snap.trigger() + " Snapshot",
                Lore.builder(Lore.Accent.WHITE)
                        .subcategory("Inventory Snapshot")
                        .divider()
                        .stat("Trigger", snap.trigger())
                        .stat("Captured", TIME.format(Instant.ofEpochMilli(snap.at())))
                        .stat("Inventory", countStacks(items) + " stacks")
                        .stat("Ender Chest", countStacks(ender) + " stacks")
                        .build()), null);
        menu.set(8, 5, Lore.item(Material.LIME_DYE, Lore.Accent.GREEN.code() + "&lRestore This Snapshot",
                Lore.builder(Lore.Accent.GREEN).divider()
                        .description("Replaces " + targetName + "'s inventory,", "armor, off-hand and ender chest",
                                "with this snapshot. Their current", "state is saved as a snapshot first.")
                        .clickPrompt("Click to Restore").build()),
                click -> restore(staff, snap, targetName));
        menu.open(staff);
    }

    private static void shown(Menu menu, @Nullable ItemStack item, int x, int y) {
        if (!isEmpty(item)) {
            menu.set(x, y, item.clone(), null);
        }
    }

    // ------------------------------------------------------------------ restoring

    private void restore(Player staff, Snapshot snap, String targetName) {
        Player target = Bukkit.getPlayer(snap.owner());
        CompletableFuture<Outcome> outcome;
        if (target != null) {
            CompletableFuture<Outcome> done = new CompletableFuture<>();
            // Copy the current state and replace it in the same tick (capture copies synchronously and stores the copy
            // in the background), so there is no gap in which the player can drop or stash their gear and then also
            // receive the snapshot. Then write the player file at once (checklist 12).
            Tasks.entity.run(target, () -> {
                capture(target, PRE_RESTORE);
                apply(target, snap);
                me.Evan52098.genscore.util.PlayerFiles.save(target);
                done.complete(Outcome.APPLIED_LIVE);
            });
            outcome = done;
        } else {
            outcome = db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO pvp_inv_pending (uuid, snapshot_id) VALUES (?, ?) "
                                + "ON CONFLICT (uuid) DO UPDATE SET snapshot_id = excluded.snapshot_id")) {
                    ps.setString(1, snap.owner().toString());
                    ps.setLong(2, snap.id());
                    ps.executeUpdate();
                }
                return Outcome.QUEUED_OFFLINE;
            });
        }
        outcome.whenComplete((result, error) -> Tasks.entity.run(staff, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to restore inventory snapshot " + snap.id() + " for " + targetName, error);
                staff.sendMessage(Msg.bad("That snapshot could not be restored. See the console."));
            } else if (result == Outcome.APPLIED_LIVE) {
                staff.sendMessage(Msg.good("Restored " + Msg.white(targetName) + "'s inventory. Their previous state was saved as a snapshot first."));
            } else {
                staff.sendMessage(Msg.neutral(Msg.white(targetName) + " is offline - the restore will apply the next time they join."));
            }
            staff.closeInventory();
        }));
    }

    /** Applies a queued restore at join. Completes with true when one was applied. */
    private CompletableFuture<Boolean> applyPendingFor(Player player) {
        UUID id = player.getUniqueId();
        return db.async(c -> {
            Long snapshotId = null;
            try (PreparedStatement ps = c.prepareStatement("SELECT snapshot_id FROM pvp_inv_pending WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        snapshotId = rs.getLong(1);
                    }
                }
            }
            if (snapshotId == null) {
                return null;
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_inv_pending WHERE uuid = ?")) {
                ps.setString(1, id.toString());
                ps.executeUpdate();
            }
            return read(c, snapshotId);
        }).handle((snap, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to check pending inventory restore for " + player.getName(), error);
                return false;
            }
            if (snap == null) {
                return false;
            }
            Tasks.entity.run(player, () -> capture(player, PRE_RESTORE).whenComplete((v, e) -> Tasks.entity.run(player, () -> apply(player, snap))));
            return true;
        });
    }

    /** Replaces inventory, armor, off-hand and ender chest. Call on the player's thread. */
    private static void apply(Player player, Snapshot snap) {
        ItemStack[] items = snap.inventory();
        PlayerInventory inventory = player.getInventory();
        inventory.setStorageContents(Arrays.copyOfRange(items, 0, 36));
        inventory.setArmorContents(Arrays.copyOfRange(items, 36, 40));
        inventory.setItemInOffHand(items[40] == null ? ItemStack.empty() : items[40]);
        player.getEnderChest().setContents(snap.ender());
        player.updateInventory();
        player.sendMessage(Msg.good("Your inventory was restored by staff to a previous snapshot."));
    }

    // ------------------------------------------------------------------ storage

    private CompletableFuture<Snapshot> load(long id) {
        return db.async(c -> read(c, id));
    }

    private static @Nullable Snapshot read(Connection c, long id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, at, reason, data, ender FROM pvp_inv_snapshots WHERE id = ?")) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new Snapshot(id, UUID.fromString(rs.getString(1)), rs.getLong(2), rs.getString(3).toUpperCase(Locale.ROOT),
                        decode(rs.getString(4), INVENTORY_SIZE), decode(rs.getString(5), ENDER_SIZE));
            }
        }
    }

    private static ItemStack[] decode(@Nullable String data, int size) {
        ItemStack[] out = new ItemStack[size];
        if (data == null || data.isEmpty()) {
            return out;
        }
        ItemStack[] decoded = Items.decode(data);
        for (int i = 0; i < size && i < decoded.length; i++) {
            out[i] = isEmpty(decoded[i]) ? null : decoded[i];
        }
        return out;
    }

    private static boolean isEmpty(@Nullable ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static int countStacks(ItemStack[] items) {
        int count = 0;
        for (ItemStack item : items) {
            if (!isEmpty(item)) {
                count++;
            }
        }
        return count;
    }

    private static Material iconFor(String trigger) {
        return switch (trigger) {
            case DEATH -> Material.SKELETON_SKULL;
            case PRE_RESTORE -> Material.CLOCK;
            case MANUAL -> Material.PAPER;
            case QUIT -> Material.OAK_DOOR;
            case JOIN -> Material.LIME_DYE;
            default -> Material.CHEST;
        };
    }
}
