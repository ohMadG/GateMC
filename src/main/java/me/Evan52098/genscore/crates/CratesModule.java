package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Crates: for now the Daily Lockbox (BlockvilleCore's lockbox), /lockbox and /lockboxadmin. Settings are under
 * {@code crates.lockbox} in config.yml. Rewards are paid through the economy module; with it off they wait as PENDING
 * and are paid on a later join once it is on.
 */
public final class CratesModule implements GensModule, Listener {

    private GensCore plugin;
    private LockboxService lockbox;
    private LockboxGui gui;

    @Override
    public String id() {
        return "crates";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(), LockboxService.schema(plugin.database()));
        lockbox = new LockboxService(plugin, settings());
        gui = new LockboxGui(plugin, lockbox);
        Bukkit.getPluginManager().registerEvents(gui, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(new LockboxCommands(plugin, this, lockbox, gui));
    }

    private LockboxSettings settings() {
        return LockboxSettings.from(plugin.getConfig().getConfigurationSection("crates.lockbox"), plugin.getLogger());
    }

    @Override
    public void reload() {
        if (lockbox != null) {
            lockbox.settings(settings());
        }
    }

    @Override
    public void disable() {
        // Nothing is held in escrow: guesses and rewards are written to the database as they happen, and any reward
        // still PENDING is paid on the player's next join.
        if (gui != null) {
            gui.clear();
        }
    }

    /** Checklist 5 and 7: pays lockbox rewards left PENDING by a crash, a disconnect or the economy being off. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        lockbox.deliverPending(event.getPlayer().getUniqueId());
    }

    public LockboxService lockbox() {
        return lockbox;
    }

    public LockboxGui gui() {
        return gui;
    }
}
