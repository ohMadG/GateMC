package me.Evan52098.genscore.lagwatch;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.util.DiscordWebhook;
import me.Evan52098.genscore.module.GensModule;
import org.bukkit.Bukkit;

import java.io.File;

/** Lag machine watch with per-region slow-down reports on Folia. See {@link LagWatchService}. */
public final class LagWatchModule implements GensModule {

    private GensCore plugin;
    private LagWatchService service;

    @Override
    public String id() {
        return "lagwatch";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        RegionTps regionTps = RegionTps.start(plugin, LagWatchService.ALERT_PERMISSION);
        service = new LagWatchService(plugin, plugin.getConfig().getConfigurationSection("lagwatch"), new File(plugin.getDataFolder(), "modules/" + id()),
                new DiscordWebhook(plugin.getLogger(), "LagWatch"), regionTps);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        plugin.commands().register(new LagWatchCommands(service));
        service.start();
    }

    @Override
    public void reload() {
        service.config(plugin.getConfig().getConfigurationSection("lagwatch"));
    }

    @Override
    public void disable() {
        if (service != null) {
            service.stop();
        }
    }

    /** For other modules, e.g. the factions module naming claim owners with {@link LagWatchService#addOwnerResolver}. */
    public LagWatchService service() {
        return service;
    }
}
