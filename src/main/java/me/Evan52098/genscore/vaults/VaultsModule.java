package me.Evan52098.genscore.vaults;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.economy.EconomyService;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Items;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.EventPriority;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.permissions.PermissionAttachmentInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Player vaults: /pv [number] [player] and /buyvault. Ported from BlockvilleCore's PlayerVaultService and
 * PlayerVaultCommand (same messages). A vault is loaded when opened and saved when closed; one person at a time.
 */
public final class VaultsModule implements GensModule, Listener {

    private static final class VaultHolder implements InventoryHolder {
        final UUID owner;
        final int number;
        Inventory inventory;
        /** Saves for this vault run one after another, so an older copy never lands after a newer one. */
        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        final java.util.concurrent.atomic.AtomicBoolean saveQueued = new java.util.concurrent.atomic.AtomicBoolean();

        VaultHolder(UUID owner, int number) {
            this.owner = owner;
            this.number = number;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    /** How many extra vaults a player has bought. */
    static final class Bought {
        private int count;

        synchronized int count() {
            return count;
        }

        synchronized void add() {
            count++;
        }
    }

    private final Map<String, VaultHolder> open = new ConcurrentHashMap<>();
    private GensCore plugin;
    private Database db;
    private PlayerDataService players;
    private int defaultVaults;
    private int maxNumber;
    private int rows;
    private boolean buyEnabled;
    private long buyBaseCents;
    private long buyStepCents;
    private int buyMax;

    private final PlayerSection<Bought> boughtSection = new PlayerSection<>() {
        @Override
        public String id() {
            return "vaults";
        }

        @Override
        public Bought load(Connection c, UUID player) throws SQLException {
            Bought b = new Bought();
            try (PreparedStatement ps = c.prepareStatement("SELECT bought FROM pvp_vault_slots WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        b.count = rs.getInt(1);
                    }
                }
            }
            return b;
        }

        @Override
        public void save(Connection c, UUID player, Bought data) throws SQLException {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_vault_slots (uuid, bought) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET bought = excluded.bought")) {
                ps.setString(1, player.toString());
                ps.setInt(2, data.count());
                ps.executeUpdate();
            }
        }
    };

    @Override
    public String id() {
        return "vaults";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.players = plugin.playerData();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_vaults (uuid TEXT NOT NULL, number INTEGER NOT NULL, items TEXT NOT NULL, "
                        + "PRIMARY KEY (uuid, number));"
                        + "CREATE TABLE IF NOT EXISTS pvp_vault_slots (uuid TEXT PRIMARY KEY, bought INTEGER NOT NULL DEFAULT 0)");
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("vaults");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        defaultVaults = Math.max(0, c.getInt("default", 1));
        maxNumber = Math.max(1, c.getInt("max-number", 50));
        rows = Math.max(1, Math.min(6, c.getInt("rows", 6)));
        buyEnabled = c.getBoolean("buy.enabled", true);
        buyBaseCents = Money.toCents(c.getDouble("buy.base-cost", 3000));
        buyStepCents = Money.toCents(c.getDouble("buy.cost-increment-per-slot", 2000));
        buyMax = Math.max(0, c.getInt("buy.max-bonus-slots", 5));
        players.register(boughtSection);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private int allowed(Player p) {
        if (p.hasPermission("genscore.vaults.unlimited")) {
            return maxNumber;
        }
        int max = defaultVaults;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (info.getValue() && node.startsWith("genscore.vaults.")) {
                try {
                    max = Math.max(max, Integer.parseInt(node.substring("genscore.vaults.".length())));
                } catch (NumberFormatException ignored) {
                    // not a number node
                }
            }
        }
        return Math.min(maxNumber, max + players.get(p).get(boughtSection).count());
    }

    @Command({"pv", "playervault", "vault"})
    @CommandPermission("genscore.vault")
    @Description("Open one of your vaults")
    public void vault(BukkitCommandActor actor, @Named("number") @Default("1") int number, @Named("player") @Optional @Nullable String other) {
        Player viewer = actor.requirePlayer();
        if (other != null && !other.equalsIgnoreCase(viewer.getName())) {
            if (!viewer.hasPermission("genscore.vaults.others")) {
                viewer.sendMessage(Msg.bad("You don't have permission to do that."));
                return;
            }
            players.findUuid(other).whenComplete((found, error) -> Tasks.entity.run(viewer, () -> {
                if (error != null || found.isEmpty()) {
                    viewer.sendMessage(Msg.bad("Player not found."));
                    return;
                }
                openVault(viewer, found.get(), number, other + "'s vault " + number);
            }));
            return;
        }
        int allowed = allowed(viewer);
        if (number < 1 || number > allowed) {
            viewer.sendMessage(Msg.bad("You have " + Msg.white(String.valueOf(allowed)) + (allowed == 1 ? " vault." : " vaults.")
                    + (buyEnabled ? " Buy more with " + Msg.white("/buyvault") + "." : "")));
            return;
        }
        openVault(viewer, viewer.getUniqueId(), number, "Vault " + number);
    }

    // ------------------------------------------------------------------ wipes

    private static final long WIPE_LOCK_MILLIS = 5 * 60_000L;
    private final Map<UUID, Long> wipeLocked = new ConcurrentHashMap<>();
    private volatile long allWipeLockedUntil;

    private boolean wipeLocked(UUID owner) {
        long now = System.currentTimeMillis();
        Long until = wipeLocked.get(owner);
        return allWipeLockedUntil > now || (until != null && until > now);
    }

    /**
     * Called by the wipe module before it wipes {@code owner} (or everyone when null): locks those vaults for a few
     * minutes so nobody can reopen one mid-wipe, closes any open view of them, and completes once their final saves
     * are written. Without this, a vault reopened during the wipe would write the old items back.
     */
    public CompletableFuture<Void> lockForWipe(@Nullable UUID owner) {
        long until = System.currentTimeMillis() + WIPE_LOCK_MILLIS;
        if (owner == null) {
            allWipeLockedUntil = until;
        } else {
            wipeLocked.put(owner, until);
        }
        List<String> keys = new java.util.ArrayList<>();
        for (Map.Entry<String, VaultHolder> e : open.entrySet()) {
            VaultHolder h = e.getValue();
            if (owner == null || h.owner.equals(owner)) {
                keys.add(e.getKey());
                if (h.inventory != null) {
                    for (org.bukkit.entity.HumanEntity viewer : List.copyOf(h.inventory.getViewers())) {
                        Tasks.entity.run(viewer, viewer::closeInventory);
                    }
                }
            }
        }
        // A view leaves `open` only after its close-save is written; wait for that (at most 15 s).
        CompletableFuture<Void> done = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + 15_000;
        Tasks.async.timer(t -> {
            boolean anyLeft = keys.stream().anyMatch(open::containsKey);
            if (!anyLeft || System.currentTimeMillis() > deadline) {
                t.cancel();
                done.complete(null);
            }
        }, 100, 250, TimeUnit.MILLISECONDS);
        return done;
    }

    private void openVault(Player viewer, UUID owner, int number, String title) {
        if (wipeLocked(owner)) {
            viewer.sendMessage(Msg.bad("That vault is being reset right now. Try again in a few minutes."));
            return;
        }
        if (number < 1 || number > maxNumber) {
            viewer.sendMessage(Msg.bad("Invalid number: " + Msg.white(String.valueOf(number)) + "."));
            return;
        }
        String key = owner + ":" + number;
        VaultHolder holder = new VaultHolder(owner, number);
        if (open.putIfAbsent(key, holder) != null) {
            viewer.sendMessage(Msg.bad("That vault is currently open elsewhere. Try again in a moment."));
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT items FROM pvp_vaults WHERE uuid = ? AND number = ?")) {
                ps.setString(1, owner.toString());
                ps.setInt(2, number);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Items.decode(rs.getString(1)) : new ItemStack[0];
                }
            }
        }).whenComplete((items, error) -> Tasks.entity.later(viewer, () -> {
            if (error != null) {
                open.remove(key, holder);
                plugin.getLogger().log(Level.WARNING, "Could not load vault " + key, error);
                viewer.sendMessage(Msg.bad("That vault could not be loaded. Try again in a moment."));
                return;
            }
            holder.inventory = Bukkit.createInventory(holder, rows * 9, Component.text(title));
            for (int i = 0; i < items.length && i < rows * 9; i++) {
                holder.inventory.setItem(i, items[i]);
            }
            viewer.openInventory(holder.inventory);
        }, () -> open.remove(key, holder), 1));
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof VaultHolder holder) {
            closeSave(holder, event.getInventory().getContents(), 0);
        }
    }

    /**
     * The final save of a closed vault. The vault stays locked (nobody can reopen it) until this save succeeds;
     * a failure is retried, because reopening would load the old contents while the player keeps what they took out.
     */
    private void closeSave(VaultHolder holder, ItemStack[] contents, int attempt) {
        save(holder, contents).whenComplete((v, e) -> {
            if (e == null) {
                open.remove(holder.owner + ":" + holder.number, holder);
            } else if (attempt < 20) {
                Tasks.async.later(() -> closeSave(holder, contents, attempt + 1), 5, TimeUnit.SECONDS);
            } else {
                plugin.getLogger().severe("Vault " + holder.owner + ":" + holder.number + " could not be saved after 20 tries; it stays locked until a restart");
            }
        });
    }

    /**
     * Saves the vault shortly after every change, not only on close. Otherwise: take items out of an open vault,
     * Minecraft saves your inventory, the server crashes, and the vault still has the items too (checklist 7).
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        queueSave(event.getView().getTopInventory(), event.getWhoClicked());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        queueSave(event.getView().getTopInventory(), event.getWhoClicked());
    }

    private void queueSave(Inventory top, org.bukkit.entity.HumanEntity viewer) {
        if (top.getHolder() instanceof VaultHolder holder && holder.saveQueued.compareAndSet(false, true)) {
            // One tick later the click has been applied; read the contents on the viewer's thread.
            Tasks.entity.later(viewer, () -> {
                holder.saveQueued.set(false);
                save(holder, holder.inventory.getContents());
                // The viewer's inventory changed too: write it now so vault and inventory reach disk together
                // (milliseconds apart instead of minutes), see util/PlayerFiles.
                if (viewer instanceof Player p) {
                    me.Evan52098.genscore.util.PlayerFiles.save(p);
                }
            }, () -> holder.saveQueued.set(false), 1);
        }
    }

    private CompletableFuture<Void> save(VaultHolder holder, ItemStack[] contents) {
        String data = Items.encode(contents);
        synchronized (holder) {
            holder.chain = holder.chain.handle((v, e) -> null).thenCompose(v -> write(holder, data));
            return holder.chain;
        }
    }

    private CompletableFuture<Void> write(VaultHolder holder, String data) {
        return db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_vaults (uuid, number, items) VALUES (?, ?, ?) ON CONFLICT (uuid, number) DO UPDATE SET items = excluded.items")) {
                ps.setString(1, holder.owner.toString());
                ps.setInt(2, holder.number);
                ps.setString(3, data);
                ps.executeUpdate();
            }
        }).whenComplete((v, e) -> {
            if (e != null) {
                plugin.getLogger().log(Level.SEVERE, "Vault " + holder.owner + ":" + holder.number + " was NOT saved", e);
            }
        });
    }

    /** Saves vaults still open at shutdown (close events arrive after the plugin is disabled). */
    @Override
    public void disable() {
        CompletableFuture<?>[] saves = open.values().stream()
                .filter(h -> h.inventory != null)
                .map(h -> save(h, h.inventory.getContents()))
                .toArray(CompletableFuture[]::new);
        try {
            CompletableFuture.allOf(saves).get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Not every open vault finished saving at shutdown", e);
        }
    }

    @Command("buyvault")
    @CommandPermission("genscore.buyvault")
    @Description("Buy another vault")
    public void buy(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        EconomyModule economyModule = plugin.modules().get(EconomyModule.class);
        if (!buyEnabled || economyModule == null) {
            player.sendMessage(Msg.bad("Vaults can't be bought on this server."));
            return;
        }
        Bought bought = players.get(player).get(boughtSection);
        if (bought.count() >= buyMax) {
            player.sendMessage(Msg.bad("You've bought as many extra vault slots as you can (" + Msg.white(String.valueOf(buyMax)) + ")."));
            return;
        }
        long cost = buyBaseCents + buyStepCents * bought.count();
        EconomyService economy = economyModule.service();
        if (!economy.withdraw(player.getUniqueId(), cost, "buyvault")) {
            player.sendMessage(Msg.bad("You can't afford another vault slot - it costs " + Msg.white(Money.format(cost)) + "."));
            return;
        }
        bought.add();
        player.sendMessage(Msg.good("Bought an extra vault slot for " + Msg.white(Money.format(cost)) + "! You can now use "
                + Msg.white(String.valueOf(allowed(player))) + " vaults."));
    }
}
