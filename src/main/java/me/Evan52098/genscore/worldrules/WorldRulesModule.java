package me.Evan52098.genscore.worldrules;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.teleport.TeleportModule;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.util.logging.Level;

/**
 * Spawn bounds (no nether portals inside the spawn box, falling below fall-y sends you back to spawn, a "spawn
 * closed" switch) and the End lock (stronghold portals and gateways can't reach the dragon island, players near the
 * End centre are sent to spawn, the dragon never spawns). Written fresh from HxCore's config (sections
 * {@code spawn} and {@code end-lock}).
 */
public final class WorldRulesModule implements GensModule {

    public static final String ADMIN = "genscore.worldrules.admin";
    public static final String SPAWN_BYPASS = "genscore.spawn.bypassclosed";
    public static final String END_BYPASS = "genscore.endlock.bypass";

    /** Immutable view of the settings, swapped whole on reload so listeners on many threads see one version. */
    record Settings(@Nullable Box spawn, int fallY, boolean blockSpawnPortals, String closedMessage,
                    boolean endLock, int endRadius, String endWorld, String endMessage, boolean noDragon) {
    }

    private GensCore plugin;
    private volatile Settings settings;
    private volatile boolean spawnClosed;
    private File stateFile;

    @Override
    public String id() {
        return "worldrules";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        SpawnPoint.init(plugin);
        stateFile = new File(plugin.getDataFolder(), "modules/worldrules/state.yml");
        spawnClosed = YamlConfiguration.loadConfiguration(stateFile).getBoolean("spawn-closed", false);
        reload();
        Bukkit.getPluginManager().registerEvents(new WorldRulesListener(this), plugin);
        plugin.commands().register(this);

        TeleportModule teleport = plugin.modules().get(TeleportModule.class);
        if (teleport != null) {
            // While spawn is closed, nobody without the bypass teleports into the spawn box (/spawn, homes, warps, tpa).
            teleport.service().addGuard((player, destination, kind) -> {
                Settings s = settings;
                if (spawnClosed && s.spawn() != null && s.spawn().contains(destination) && !player.hasPermission(SPAWN_BYPASS)) {
                    return "Spawn is closed right now.";
                }
                return null;
            });
        }
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("worldrules");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        ConfigurationSection sp = c.getConfigurationSection("spawn");
        ConfigurationSection end = c.getConfigurationSection("end-lock");
        if (end == null) {
            end = new MemoryConfiguration();
        }
        Box box = Box.read(sp, null);
        settings = new Settings(box,
                sp == null ? Integer.MIN_VALUE : sp.getInt("fall-y", Integer.MIN_VALUE),
                sp == null || sp.getBoolean("block-nether-portals", true),
                sp == null ? "&c&lSPAWN &8» &7Spawn is &cclosed &7right now." : sp.getString("closed-message", "&c&lSPAWN &8» &7Spawn is &cclosed &7right now."),
                end.getBoolean("enabled", false),
                Math.max(0, end.getInt("radius", 900)),
                end.getString("world", "world_the_end"),
                end.getString("message", "&5&lTHE END &8» &7The dragon island is &cclosed&7. Use &d/rtp end &7to visit the outer islands."),
                end.getBoolean("prevent-dragon", true));
        if (sp != null && box == null) {
            plugin.getLogger().warning("[worldrules] spawn: needs world, min: [x, y, z] and max: [x, y, z]; spawn bounds are off.");
        }
    }

    Settings settings() {
        return settings;
    }

    boolean spawnClosed() {
        return spawnClosed;
    }

    GensCore plugin() {
        return plugin;
    }

    // ----------------------------------------------------------------------------------------------- commands

    @Command("worldrules")
    @CommandPermission(ADMIN)
    @Description("Show the spawn and End lock state")
    public void status(BukkitCommandActor actor) {
        Settings s = settings;
        actor.reply(Msg.neutral("Spawn box: " + Msg.white(s.spawn() == null ? "not set" : s.spawn().toString())
                + ", fall-y " + Msg.white(String.valueOf(s.fallY())) + ", spawn " + Msg.white(spawnClosed ? "CLOSED" : "open") + "."));
        actor.reply(Msg.neutral("End lock: " + Msg.white(s.endLock() ? "on" : "off") + ", radius "
                + Msg.white(String.valueOf(s.endRadius())) + ", dragon " + Msg.white(s.noDragon() ? "blocked" : "allowed") + "."));
    }

    @Command("worldrules spawn close")
    @CommandPermission(ADMIN)
    @Description("Close spawn to players without the bypass permission")
    public void close(BukkitCommandActor actor) {
        setClosed(true);
        actor.reply(Msg.good("Spawn is now closed. Players without " + Msg.white(SPAWN_BYPASS) + " are sent away from it."));
    }

    @Command("worldrules spawn open")
    @CommandPermission(ADMIN)
    @Description("Open spawn again")
    public void open(BukkitCommandActor actor) {
        setClosed(false);
        actor.reply(Msg.good("Spawn is open again."));
    }

    @Command("worldrules reload")
    @CommandPermission(ADMIN)
    @Description("Reread the worldrules section of config.yml")
    public void reloadCommand(BukkitCommandActor actor) {
        Tasks.global.run(() -> {
            plugin.reloadConfig();
            reload();
            Tasks.forSender(actor.sender(), () -> actor.reply(Msg.good("World rules reloaded.")));
        });
    }

    private void setClosed(boolean closed) {
        spawnClosed = closed;
        Tasks.async.run(() -> {
            YamlConfiguration y = new YamlConfiguration();
            y.set("spawn-closed", closed);
            try {
                stateFile.getParentFile().mkdirs();
                y.save(stateFile);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "[worldrules] could not save " + stateFile, e);
            }
        });
        plugin.getLogger().info("[worldrules] spawn " + (closed ? "closed" : "opened"));
    }
}
