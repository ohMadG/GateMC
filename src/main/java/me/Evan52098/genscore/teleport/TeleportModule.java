package me.Evan52098.genscore.teleport;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** Homes, warps, spawn, /back, /top and /tpa, with warmups that combat and claims can veto. */
public final class TeleportModule implements GensModule {

    private volatile LocationStore locations;

    private TeleportService service;

    @Override
    public String id() {
        return "teleport";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_homes ("
                        + "uuid TEXT NOT NULL, name TEXT NOT NULL, " + SavedLocation.COLUMN_DEFS + ", "
                        + "PRIMARY KEY (uuid, name));"
                        + "CREATE TABLE IF NOT EXISTS pvp_locations ("
                        + "loc_key TEXT PRIMARY KEY, " + SavedLocation.COLUMN_DEFS + ")");

        ConfigurationSection c = plugin.getConfig().getConfigurationSection("teleport");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        service = new TeleportService(c);
        LocationStore locations = this.locations = new LocationStore(plugin.database(), plugin.getLogger());
        Homes.Section homesSection = new Homes.Section();
        plugin.playerData().register(homesSection);

        this.players = plugin.playerData();
        this.homesSection = homesSection;
        TeleportCommands commands = this.commands = new TeleportCommands(service, locations, plugin.playerData(), homesSection,
                Math.max(0, c.getInt("homes.max-default", 3)));
        TpaCommands tpa = new TpaCommands(plugin, service, c.getLong("tpa.timeout-seconds", 60), c.getLong("tpa.cooldown-seconds", 30));
        plugin.commands().register(commands, tpa);
        if (c.getBoolean("homes.enabled", true)) {
            plugin.commands().register(new HomeCommands(service, plugin.playerData(), homesSection,
                    Math.max(0, c.getInt("homes.max-default", 3))));
        }

        Bukkit.getPluginManager().registerEvents(new TeleportListener(service, commands, tpa, plugin.playerData(),
                c.getBoolean("back.on-death", true),
                c.getBoolean("spawn.respawn-at-spawn", true),
                c.getBoolean("spawn.first-join-at-spawn", true)), plugin);
    }

    /** For other modules: add teleport guards (combat tag, claims). */
    public TeleportService service() {
        return service;
    }

    private TeleportCommands commands;
    private me.Evan52098.genscore.player.PlayerDataService players;
    private Homes.Section homesSection;

    /** The /setspawn location, or null if none is set. Safe from any thread (cached). */
    public org.bukkit.Location spawnLocation() {
        return commands == null ? null : commands.spawnLocation();
    }

    /** The player's home names (empty if their data is not loaded). */
    public java.util.List<String> homeNames(org.bukkit.entity.Player p) {
        me.Evan52098.genscore.player.PlayerData d = players == null ? null : players.get(p.getUniqueId());
        return d == null ? java.util.List.of() : d.get(homesSection).names();
    }

    public java.util.List<String> warpNames() {
        LocationStore l = locations;
        return l == null ? java.util.List.of() : l.warpNames();
    }
}
