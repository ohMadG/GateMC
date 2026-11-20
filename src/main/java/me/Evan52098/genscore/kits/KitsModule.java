package me.Evan52098.genscore.kits;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.regex.Pattern;

/**
 * Kits: /kit, /kits, /kitcreate, /kiteditor, /kitdelete, and first-join kits. Ported from BlockvilleCore's
 * KitService, KitCommand and KitAdminCommand (same messages). Kits live in the database instead of kits.yml so all
 * servers sharing it see the same kits; the editor is a chest you fill and close.
 */
public final class KitsModule implements GensModule, Listener {

    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,24}");

    record Kit(String id, String permission, long cooldownMs, boolean firstJoin, List<ItemStack> items) {
    }

    /** Per-player last use of each kit. Thread-safe. */
    static final class Uses {
        private final Map<String, Long> lastUsed = new HashMap<>();
        private boolean dirty;

        synchronized long last(String kit) {
            return lastUsed.getOrDefault(kit, 0L);
        }

        synchronized void use(String kit) {
            lastUsed.put(kit, System.currentTimeMillis());
            dirty = true;
        }

        synchronized Map<String, Long> takeChanges() {
            if (!dirty) {
                return null;
            }
            dirty = false;
            return new HashMap<>(lastUsed);
        }
    }

    private static final class EditorHolder implements InventoryHolder {
        final String kitId;
        final long cooldownMs;
        final boolean firstJoin;
        Inventory inventory;

        EditorHolder(String kitId, long cooldownMs, boolean firstJoin) {
            this.kitId = kitId;
            this.cooldownMs = cooldownMs;
            this.firstJoin = firstJoin;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final Map<String, Kit> kits = new ConcurrentHashMap<>();
    private GensCore plugin;
    private Database db;
    private PlayerDataService players;
    private final PlayerSection<Uses> usesSection = new PlayerSection<>() {
        @Override
        public String id() {
            return "kits";
        }

        @Override
        public Uses load(Connection c, UUID player) throws SQLException {
            Uses u = new Uses();
            try (PreparedStatement ps = c.prepareStatement("SELECT kit, last_used FROM pvp_kit_uses WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        u.lastUsed.put(rs.getString(1), rs.getLong(2));
                    }
                }
            }
            return u;
        }

        @Override
        public void save(Connection c, UUID player, Uses data) throws SQLException {
            Map<String, Long> changes = data.takeChanges();
            if (changes == null) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_kit_uses (uuid, kit, last_used) VALUES (?, ?, ?) "
                            + "ON CONFLICT (uuid, kit) DO UPDATE SET last_used = excluded.last_used")) {
                for (Map.Entry<String, Long> e : changes.entrySet()) {
                    ps.setString(1, player.toString());
                    ps.setString(2, e.getKey());
                    ps.setLong(3, e.getValue());
                    ps.addBatch();
                }
                ps.executeBatch();
            }
        }
    };

    @Override
    public String id() {
        return "kits";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.players = plugin.playerData();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_kits (id TEXT PRIMARY KEY, permission TEXT NOT NULL, "
                        + "cooldown_ms BIGINT NOT NULL, first_join INTEGER NOT NULL DEFAULT 0, items TEXT NOT NULL);"
                        + "CREATE TABLE IF NOT EXISTS pvp_kit_uses (uuid TEXT NOT NULL, kit TEXT NOT NULL, "
                        + "last_used BIGINT NOT NULL, PRIMARY KEY (uuid, kit))");
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT id, permission, cooldown_ms, first_join, items FROM pvp_kits");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    kits.put(rs.getString(1), new Kit(rs.getString(1), rs.getString(2), rs.getLong(3), rs.getInt(4) != 0,
                            Items.nonEmpty(Items.decode(rs.getString(5)))));
                }
            }
            return null;
        });
        players.register(usesSection);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    // ------------------------------------------------------------------ players

    @Command("kits")
    @CommandPermission("genscore.kit")
    @Description("List the kits you can use")
    public void list(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        List<String> names = new TreeMap<>(kits).values().stream()
                .filter(k -> player.hasPermission(k.permission())).map(Kit::id).toList();
        player.sendMessage(names.isEmpty() ? Msg.neutral("No kits available.")
                : Msg.neutral("Available kits: " + Msg.white(String.join(", ", names))));
    }

    @Command("kit")
    @CommandPermission("genscore.kit")
    @Description("Claim a kit")
    public void kit(BukkitCommandActor actor, @Named("kit") String name) {
        Player player = actor.requirePlayer();
        Kit kit = kits.get(name.toLowerCase(Locale.ROOT));
        if (kit == null) {
            player.sendMessage(Msg.bad("Kit " + Msg.white(name) + " not found."));
            return;
        }
        if (!player.hasPermission(kit.permission())) {
            player.sendMessage(Msg.bad("You don't have permission to use kit " + Msg.white(kit.id()) + "."));
            return;
        }
        Uses uses = players.get(player).get(usesSection);
        long left = uses.last(kit.id()) + kit.cooldownMs() - System.currentTimeMillis();
        if (left > 0 && !player.hasPermission("genscore.kits.bypasscooldown")) {
            player.sendMessage(Msg.bad("Kit " + Msg.white(kit.id()) + " is on cooldown. Available in " + Msg.white(Cooldowns.format(left)) + "."));
            return;
        }
        int free = 0;
        for (ItemStack s : player.getInventory().getStorageContents()) {
            if (s == null || s.isEmpty()) {
                free++;
            }
        }
        if (free < kit.items().size()) {
            player.sendMessage(Msg.bad("You need " + Msg.white(String.valueOf(kit.items().size() - free)) + " more free inventory slots for this kit."));
            return;
        }
        uses.use(kit.id()); // record the claim before giving (checklist 1)
        players.saveSoon(player.getUniqueId()); // and persist it, so a crash can't reset the cooldown (checklist 7)
        give(player, kit);
        player.sendMessage(Msg.good("You received kit " + Msg.white(kit.id()) + "."));
    }

    private static void give(Player player, Kit kit) {
        for (ItemStack s : kit.items()) {
            player.getInventory().addItem(s.clone()).values()
                    .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = players.get(player.getUniqueId());
        if (data == null || !data.isFirstTime()) {
            return;
        }
        for (Kit kit : kits.values()) {
            if (kit.firstJoin()) {
                data.get(usesSection).use(kit.id());
                give(player, kit);
            }
        }
        players.saveSoon(player.getUniqueId());
    }

    // ------------------------------------------------------------------ admin

    @Command("kitcreate")
    @CommandPermission("genscore.kits.admin")
    @Description("Create a kit: fill the chest that opens, then close it")
    public void create(BukkitCommandActor actor, @Named("name") String name, @Named("cooldown hours") double hours) {
        Player player = actor.requirePlayer();
        String id = name.toLowerCase(Locale.ROOT);
        if (!ID.matcher(id).matches()) {
            player.sendMessage(Msg.bad("Kit names may only contain letters, numbers, dashes and underscores."));
            return;
        }
        if (hours < 0) {
            player.sendMessage(Msg.bad("The claim delay must be " + Msg.white("0") + " or more hours."));
            return;
        }
        if (kits.containsKey(id)) {
            player.sendMessage(Msg.bad("Kit " + Msg.white(id) + " already exists. Use " + Msg.white("/kiteditor " + id) + " to edit it."));
            return;
        }
        long cooldown = (long) (hours * 3_600_000L);
        openEditor(player, id, cooldown, false, List.of());
    }

    @Command("kiteditor")
    @CommandPermission("genscore.kits.admin")
    @Description("Edit a kit's items: change the chest, then close it")
    public void edit(BukkitCommandActor actor, @Named("name") String name) {
        Player player = actor.requirePlayer();
        Kit kit = kits.get(name.toLowerCase(Locale.ROOT));
        if (kit == null) {
            player.sendMessage(Msg.bad("Kit " + Msg.white(name) + " does not exist. Create it with " + Msg.white("/kitcreate " + name + " 24") + "."));
            return;
        }
        openEditor(player, kit.id(), kit.cooldownMs(), kit.firstJoin(), kit.items());
    }

    @Command("kitfirstjoin")
    @CommandPermission("genscore.kits.admin")
    @Description("Toggle whether new players get this kit automatically")
    public void firstJoin(BukkitCommandActor actor, @Named("name") String name) {
        Kit kit = kits.get(name.toLowerCase(Locale.ROOT));
        if (kit == null) {
            actor.sender().sendMessage(Msg.bad("Kit " + Msg.white(name) + " not found."));
            return;
        }
        Kit updated = new Kit(kit.id(), kit.permission(), kit.cooldownMs(), !kit.firstJoin(), kit.items());
        store(updated);
        actor.sender().sendMessage(Msg.good("Kit " + Msg.white(kit.id()) + (updated.firstJoin() ? " is now" : " is no longer") + " given on first join."));
    }

    @Command("kitdelete")
    @CommandPermission("genscore.kits.admin")
    @Description("Delete a kit")
    public void delete(BukkitCommandActor actor, @Named("name") String name) {
        String id = name.toLowerCase(Locale.ROOT);
        if (kits.remove(id) == null) {
            actor.sender().sendMessage(Msg.bad("Kit " + Msg.white(name) + " not found."));
            return;
        }
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_kits WHERE id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }
        });
        actor.sender().sendMessage(Msg.good("Kit " + Msg.white(id) + " deleted."));
    }

    /**
     * Milliseconds until the player can claim the kit again: 0 = ready, -1 = unknown kit, no permission, cooldown
     * bypass, or player data not loaded. Call on the player's thread. (Kit-ready alerts, group E.)
     */
    public long cooldownLeft(Player player, String kitId) {
        Kit kit = kits.get(kitId.toLowerCase(java.util.Locale.ROOT));
        if (kit == null || !player.hasPermission(kit.permission()) || player.hasPermission("genscore.kits.bypasscooldown")) {
            return -1;
        }
        PlayerData data = players.get(player.getUniqueId());
        if (data == null) {
            return -1;
        }
        return Math.max(0, data.get(usesSection).last(kit.id()) + kit.cooldownMs() - System.currentTimeMillis());
    }

    private void openEditor(Player player, String id, long cooldownMs, boolean firstJoin, List<ItemStack> items) {
        EditorHolder holder = new EditorHolder(id, cooldownMs, firstJoin);
        holder.inventory = Bukkit.createInventory(holder, 54, Component.text("Kit: " + id + " (close to save)"));
        for (ItemStack s : items) {
            holder.inventory.addItem(s.clone());
        }
        player.openInventory(holder.inventory);
        player.sendMessage(Msg.neutral("Editing kit " + Msg.white(id) + " (cooldown " + Msg.white(Cooldowns.format(cooldownMs))
                + "). Place items, then close the chest to save."));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof EditorHolder holder)) {
            return;
        }
        List<ItemStack> items = Items.nonEmpty(event.getInventory().getContents());
        Kit existing = kits.get(holder.kitId);
        String permission = existing != null ? existing.permission() : "genscore.kits." + holder.kitId;
        store(new Kit(holder.kitId, permission, holder.cooldownMs, holder.firstJoin, items));
        event.getPlayer().sendMessage(Msg.good("Kit " + Msg.white(holder.kitId) + " saved with " + items.size() + " stacks."));
    }

    private void store(Kit kit) {
        kits.put(kit.id(), kit);
        String data = Items.encode(kit.items().toArray(new ItemStack[0]));
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_kits (id, permission, cooldown_ms, first_join, items) VALUES (?, ?, ?, ?, ?) "
                            + "ON CONFLICT (id) DO UPDATE SET permission = excluded.permission, cooldown_ms = excluded.cooldown_ms, "
                            + "first_join = excluded.first_join, items = excluded.items")) {
                ps.setString(1, kit.id());
                ps.setString(2, kit.permission());
                ps.setLong(3, kit.cooldownMs());
                ps.setInt(4, kit.firstJoin() ? 1 : 0);
                ps.setString(5, data);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.SEVERE, "Could not save kit " + kit.id(), e);
            return null;
        });
    }
}
