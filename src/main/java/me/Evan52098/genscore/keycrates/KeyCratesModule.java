package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Key crates (HxCrates): physical keys, crate blocks, chance previews, open one / open all with an animation, the claim
 * box, broadcasts, playtime and vote keys, retired keys turning into shards, and the welcome spin. The Daily Lockbox
 * is a different module ({@code crates}).
 *
 * <p>For other modules: {@link #giveKeys(UUID, String, int, String)} (any thread; offline players get the keys in
 * their claim box) and {@link #vote(Player)} (the vote module's hook).
 */
public final class KeyCratesModule implements GensModule, Listener {

    private static volatile @Nullable KeyCratesModule instance;

    private GensCore plugin;
    private CrateService service;
    private CrateBlocks blocks;
    private File folder;

    @Override
    public String id() {
        return "keycrates";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "modules/keycrates");
        CrateStore store = new CrateStore(plugin.database(), plugin.getLogger());
        KeyItems keys = new KeyItems(plugin);
        KeyProgress.Section progress = new KeyProgress.Section();
        plugin.playerData().register(progress);
        CrateConfig config = CrateConfig.load(plugin.getConfig().getConfigurationSection("keycrates"), folder, plugin.getLogger());
        service = new CrateService(plugin, store, keys, progress, config);
        CrateMenus menus = new CrateMenus(service);
        service.menus(menus);
        blocks = new CrateBlocks(service, menus, plugin.getLogger());
        service.blocks(blocks);
        blocks.start(plugin);
        CrateSuggestions.service = service;

        Bukkit.getPluginManager().registerEvents(new KeyGuard(keys), plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(new KeyCratesCommands(plugin, this, service, menus, blocks));
        Tasks.async.timer(t -> playtimeMinute(), 60, 60, TimeUnit.SECONDS);
        plugin.getLogger().info("[keycrates] " + config.crates.size() + " crate(s) open.");
        instance = this;
    }

    @Override
    public void disable() {
        instance = null;
        CrateSuggestions.service = null;
        HandlerList.unregisterAll(this);
        if (blocks != null) {
            blocks.stop();
        }
        // Opens still animating stay PENDING in the database and are delivered at the player's next join.
    }

    @Override
    public void reload() {
        service.config(CrateConfig.load(plugin.getConfig().getConfigurationSection("keycrates"), folder, plugin.getLogger()));
        blocks.reload();
    }

    // ------------------------------------------------------------------ API for other modules

    /**
     * Gives crate keys from any thread. Online players get them on their own thread (overflow to /claim); offline players
     * get them in /claim. Keys of a retired crate become shards. Returns false if the crate is unknown, the module is off
     * or the amount is not positive.
     */
    public static boolean giveKeys(UUID player, String crate, int amount, String reason) {
        KeyCratesModule m = instance;
        return m != null && amount > 0 && amount <= 10_000 && m.service.giveKeys(player, crate, amount, reason);
    }

    /** The vote module's hook: call on the voter's thread after a vote is paid. Gives vote keys and vote shards. */
    public static void vote(Player player) {
        KeyCratesModule m = instance;
        if (m != null) {
            Tasks.onEntity(player, () -> m.service.onVote(player));
        }
    }

    // ------------------------------------------------------------------ events and timers

    private void playtimeMinute() {
        EssentialsModule essentials = plugin.modules().get(EssentialsModule.class);
        for (Player player : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(player, () -> {
                boolean afk = essentials != null && essentials.social() != null && essentials.social().isAfk(player.getUniqueId());
                service.playtimeMinute(player, afk);
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        CrateService.later(player, () -> service.onJoined(player), null, 40);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        service.forget(event.getPlayer().getUniqueId());
    }

    /** Retired keys taken out of a chest, shulker or any other container turn into shards when it is closed. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player) || service.config().retiredKeys.isEmpty()
                || event.getInventory().getType() == InventoryType.CRAFTING) {
            return;
        }
        CrateService.later(player, () -> service.convertRetired(player), null, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        String id = service.keys().crateOf(event.getItem().getItemStack());
        if (id != null && service.config().retiredValue(id) > 0) {
            CrateService.later(player, () -> service.convertRetired(player), null, 1);
        }
    }
}
