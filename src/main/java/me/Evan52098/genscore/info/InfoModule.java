package me.Evan52098.genscore.info;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Server information pages from HxCore: {@code /rules}, {@code /guide} (alias /helpmenu), {@code /ranks} as configurable
 * chest menus (chat lines for Bedrock players), and {@code /discord} (alias /dc) with a clickable invite. {@code /store}
 * is in the store module. Everything is read from {@code info:} in config.yml and reloads without a restart.
 *
 * <p>The essentials module already has a plain {@code /rules}; this module only registers its own {@code /rules} when
 * {@code info.rules-command} is true (see the integration notes).
 */
public final class InfoModule implements GensModule, Listener {

    private final InfoMenus menus = new InfoMenus();
    private GensCore plugin;
    private volatile String discordUrl = "";
    private volatile String discordLabel = "&9&l[CLICK FOR DISCORD]";
    private volatile List<String> discordMessage = List.of();

    @Override
    public String id() {
        return "info";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        loadConfig();
        plugin.commands().register(this);
        if (section().getBoolean("rules-command", true)) {
            try {
                plugin.commands().register(new RulesCommand());
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "[info] Could not register /rules (another module has it). "
                        + "Remove the essentials /rules or set info.rules-command to false.", e);
            }
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void reload() {
        loadConfig();
    }

    private ConfigurationSection section() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("info");
        return c != null ? c : new MemoryConfiguration();
    }

    private void loadConfig() {
        ConfigurationSection c = section();
        menus.load(c.getConfigurationSection("menus"), plugin.getLogger());
        ConfigurationSection d = c.getConfigurationSection("discord");
        if (d == null) {
            d = new MemoryConfiguration();
        }
        discordUrl = d.getString("url", "");
        discordLabel = d.getString("label", "&9&l[CLICK FOR DISCORD]");
        List<String> lines = d.getStringList("message");
        discordMessage = lines.isEmpty() ? List.of("&9&lDISCORD &8» &7Join the community: {discord}") : List.copyOf(lines);
        if (!discordUrl.isEmpty() && !Text.isWebLink(discordUrl)) {
            plugin.getLogger().warning("[info] info.discord.url is not an http(s) link; it will not be clickable.");
        }
        Bedrock.reset();
    }

    // ------------------------------------------------------------------ commands

    @Command({"guide", "helpmenu"})
    @CommandPermission("genscore.info")
    @Description("Server guide")
    public void guide(BukkitCommandActor actor) {
        open(actor.requirePlayer(), "guide");
    }

    @Command("ranks")
    @CommandPermission("genscore.info")
    @Description("Ranks and their perks")
    public void ranks(BukkitCommandActor actor) {
        open(actor.requirePlayer(), "ranks");
    }

    @Command({"discord", "dc"})
    @CommandPermission("genscore.info")
    @Description("Link to the Discord")
    public void discord(BukkitCommandActor actor) {
        if (discordUrl.isEmpty()) {
            actor.sender().sendMessage(Msg.bad("The Discord link isn't set up yet."));
            return;
        }
        Component link = Text.link(discordLabel, discordUrl, "&7Click to join our Discord");
        for (String line : discordMessage) {
            actor.sender().sendMessage(Text.replace(Text.legacy(line), Map.of("{discord}", link)));
        }
    }

    /** {@code /rules} as the configured menu. A separate object so it can be left out when essentials keeps /rules. */
    public final class RulesCommand {
        @Command("rules")
        @CommandPermission("genscore.info")
        @Description("Server rules")
        public void rules(BukkitCommandActor actor) {
            open(actor.requirePlayer(), "rules");
        }
    }

    /** Opens one of the configured pages (rules, guide, ranks, or any other id under info.menus). Player's thread. */
    public void open(Player player, String id) {
        if (!menus.show(player, id)) {
            player.sendMessage(Msg.bad("This page isn't set up yet."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        menus.forget(event.getPlayer());
    }
}
