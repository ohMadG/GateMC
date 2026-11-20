package me.Evan52098.genscore.command;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * {@code /gensadmin reload}: rereads config.yml and every module's settings without a restart (roadmap rule 5:
 * every balance number lives in config and reloads live). Modules decide what is safe to change live.
 */
public final class CoreAdminCommands {

    private final GensCore plugin;

    public CoreAdminCommands(GensCore plugin) {
        this.plugin = plugin;
    }

    @Command("gensadmin reload")
    @CommandPermission("genscore.admin.reload")
    @Description("Reload config.yml and every module")
    public void reload(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        long start = System.currentTimeMillis();
        plugin.reloadConfig();
        Msg.load(plugin.getConfig().getConfigurationSection("messages"));
        plugin.modules().reloadAll();
        sender.sendMessage(Msg.good("GensCore reloaded in " + Msg.white((System.currentTimeMillis() - start) + " ms")
                + ". Check the console for warnings. Module on/off switches need a restart."));
    }
}
