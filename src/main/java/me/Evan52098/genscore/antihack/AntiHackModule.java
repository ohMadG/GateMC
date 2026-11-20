package me.Evan52098.genscore.antihack;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.lagwatch.RegionTps;
import me.Evan52098.genscore.module.GensModule;
import org.bukkit.Bukkit;

import java.io.File;

/**
 * Anti-hack extras: BlockvilleCore's own movement, reach, combat, clicking, building and client checks, reporting to
 * staff next to GrimAC. See {@link AntiHackService} for what changed for Folia.
 */
public final class AntiHackModule implements GensModule {

    private GensCore plugin;
    private AntiHackService service;

    @Override
    public String id() {
        return "antihack";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        RegionTps regionTps = RegionTps.start(plugin, AntiHackService.ALERT_PERMISSION);
        File folder = new File(plugin.getDataFolder(), "modules/" + id());
        service = new AntiHackService(plugin, plugin.getConfig().getConfigurationSection("antihack"), folder, regionTps);
        Bukkit.getPluginManager().registerEvents(service, plugin);
        Bukkit.getPluginManager().registerEvents(new MovementDetector(service), plugin);
        Bukkit.getPluginManager().registerEvents(new ReachDetector(service), plugin);
        Bukkit.getPluginManager().registerEvents(new CombatDetector(service), plugin);
        Bukkit.getPluginManager().registerEvents(new MovementExtraDetector(service), plugin);
        Bukkit.getPluginManager().registerEvents(new PlayerChecksDetector(service), plugin);
        Bukkit.getPluginManager().registerEvents(new PacketDetector(service), plugin);
        plugin.commands().register(new AntiHackCommands(service));
        service.start();
    }

    @Override
    public void reload() {
        service.config(plugin.getConfig().getConfigurationSection("antihack"));
    }

    @Override
    public void disable() {
        if (service != null) {
            service.stop();
        }
    }

    /** For other modules: exemptions (e.g. event arenas) via {@link AntiHackService#addExemption}. */
    public AntiHackService service() {
        return service;
    }
}
