package me.Evan52098.genscore.maintenance;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.worldrules.Text;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Locale;

/**
 * Server housekeeping from HxCore's {@code maintenance} section and its small fixes: clear-lag ({@link ClearLag}),
 * the daily restart and /reboot ({@link DailyRestart}), the per-chunk mob cap ({@link MobCap}) and the Bedrock
 * fly-kick fix ({@link BedrockFlyFix}).
 */
public final class MaintenanceModule implements GensModule {

    public static final String REBOOT = "genscore.reboot";
    public static final String CLEARLAG = "genscore.clearlag";

    private GensCore plugin;
    private final ClearLag clearLag = new ClearLag();
    private final MobCap mobCap = new MobCap();
    private DailyRestart restart;

    @Override
    public String id() {
        return "maintenance";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        restart = new DailyRestart(plugin.getLogger());
        reload();
        Bukkit.getPluginManager().registerEvents(clearLag, plugin);
        Bukkit.getPluginManager().registerEvents(mobCap, plugin);
        clearLag.start();
        restart.start();
        plugin.commands().register(this);
        // On the first tick, after every plugin (Floodgate too) has enabled.
        Tasks.global.later(() -> {
            if (section().getBoolean("bedrock-fly-fix", true)) {
                BedrockFlyFix fix = BedrockFlyFix.create(plugin.getLogger());
                if (fix != null) {
                    Bukkit.getPluginManager().registerEvents(fix, plugin);
                    plugin.getLogger().info("[maintenance] Floodgate found: Bedrock players are no longer kicked for flying.");
                }
            }
        }, 1);
    }

    private ConfigurationSection section() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("maintenance");
        return c == null ? new MemoryConfiguration() : c;
    }

    private static ConfigurationSection sub(ConfigurationSection c, String key) {
        ConfigurationSection s = c.getConfigurationSection(key);
        return s == null ? new MemoryConfiguration() : s;
    }

    @Override
    public void reload() {
        ConfigurationSection c = section();
        clearLag.load(sub(c, "clear-drops"));
        restart.load(sub(c, "reboot"));
        mobCap.load(sub(c, "mob-cap"));
    }

    // ----------------------------------------------------------------------------------------------- commands

    @Command("reboot")
    @CommandPermission(REBOOT)
    @Description("Restart the server after a countdown, or cancel the countdown")
    public void reboot(BukkitCommandActor actor, @Named("seconds|cancel") @Optional @Nullable String arg) {
        if (arg == null) {
            long left = restart.secondsLeft();
            actor.reply(left < 0
                    ? Msg.neutral("No restart is planned. Use " + Msg.white("/reboot <seconds>") + " to start one.")
                    : Msg.neutral("The " + (restart.isManual() ? "manual" : "daily") + " restart is in " + Msg.white(Text.duration(left)) + "."));
            return;
        }
        if (arg.toLowerCase(Locale.ROOT).equals("cancel")) {
            if (restart.cancel()) {
                Text.broadcast(Msg.good("The server restart was cancelled."));
                plugin.getLogger().info("[maintenance] restart cancelled by " + actor.name());
            } else {
                actor.reply(Msg.bad("No restart is counting down."));
            }
            return;
        }
        long seconds;
        try {
            seconds = Long.parseLong(arg);
        } catch (NumberFormatException e) {
            actor.reply(Msg.bad("Use " + Msg.white("/reboot <seconds>") + " or " + Msg.white("/reboot cancel") + "."));
            return;
        }
        if (seconds < 1 || seconds > 86_400) {
            actor.reply(Msg.bad("Pick between 1 and 86400 seconds."));
            return;
        }
        restart.rebootIn(seconds);
        plugin.getLogger().info("[maintenance] restart in " + seconds + "s started by " + actor.name());
    }

    @Command("clearlag")
    @CommandPermission(CLEARLAG)
    @Description("Clear old ground items, XP orbs and stuck arrows now")
    public void clearNow(BukkitCommandActor actor) {
        actor.reply(Msg.neutral("Clearing " + Msg.white(String.valueOf(clearLag.trackedCount())) + " tracked items, orbs and arrows (old ones only)."));
        clearLag.sweepNow();
    }
}
