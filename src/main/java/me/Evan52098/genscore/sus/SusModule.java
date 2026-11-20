package me.Evan52098.genscore.sus;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * HxCore's /sus checks that the antihack module does not already have: auto-clicker (held CPS), auto-crystal,
 * auto-totem and nuker. Flags only: pings staff and lists players, never punishes. Flags live in memory.
 */
public final class SusModule implements GensModule {

    private GensCore plugin;
    private SusService service;

    @Override
    public String id() {
        return "sus";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        service = new SusService(SusSettings.read(plugin.getConfig().getConfigurationSection("sus")), plugin.getLogger());
        Bukkit.getPluginManager().registerEvents(new SusDetectors(service), plugin);
        plugin.commands().register(new SusCommands(service));
    }

    @Override
    public void reload() {
        service.settings(SusSettings.read(plugin.getConfig().getConfigurationSection("sus")));
    }

    /** Number of /sus flags a player has (0 if none or the module is off). For staff mode's inspect tool. */
    public int flags(Player player) {
        SusService.Record r = service.find(player.getName());
        return r == null ? 0 : r.total();
    }
}
