package me.Evan52098.genscore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.essentials.SocialCommands;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * Public chat: format (LuckPerms prefix, nickname), filter, cooldown, ignores, /clearchat, /broadcast.
 * Ported from BlockvilleCore's chat listeners and ChatFilterService (smaller), ClearChatCommand and BroadcastCommand.
 * Mutes belong to a punishment plugin and are not handled here.
 *
 * <p>Folia: {@link AsyncChatEvent} is already off the game threads. Messages to other players go out on their threads.
 */
public final class ChatModule implements GensModule, Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private ChatFilter filter;
    private String format;
    private long cooldownMillis;
    private boolean luckPerms;
    private SocialCommands social;
    private final Cooldowns cooldowns = new Cooldowns();

    @Override
    public String id() {
        return "chat";
    }

    @Override
    public void enable(GensCore plugin) {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("chat");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        filter = new ChatFilter(c.getConfigurationSection("filter"));
        format = c.getString("format", "<prefix><name><gray>: </gray><message>");
        cooldownMillis = Math.max(0, c.getLong("cooldown-ms", 1000));
        luckPerms = Bukkit.getPluginManager().getPlugin("LuckPerms") != null;
        EssentialsModule essentials = plugin.modules().get(EssentialsModule.class);
        social = essentials == null ? null : essentials.social();
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String raw = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (cooldownMillis > 0 && !player.hasPermission("genscore.chat.bypass.cooldown")
                && !cooldowns.tryStart(player.getUniqueId(), "chat", cooldownMillis)) {
            event.setCancelled(true);
            player.sendMessage(Msg.bad("Please wait " + Msg.white(Cooldowns.format(cooldowns.remaining(player.getUniqueId(), "chat"))) + " before chatting again."));
            return;
        }

        if (!player.hasPermission("genscore.chat.bypass.filter")) {
            ChatFilter.Result result = filter.check(player.getUniqueId(), raw);
            if (result.blocked()) {
                event.setCancelled(true);
                player.sendMessage(Msg.bad("Your message was not sent: " + result.reason() + "."));
                if (result.alertStaff()) {
                    Component alert = Msg.parse("<dark_red>[Filter] <red>" + Msg.escape(player.getName()) + "</red> <gray>("
                            + result.category() + "):</gray> <white>" + Msg.escape(raw));
                    for (Player staff : Bukkit.getOnlinePlayers()) {
                        if (staff.hasPermission("genscore.chat.alerts")) {
                            Tasks.onEntity(staff, () -> staff.sendMessage(alert));
                        }
                    }
                    Bukkit.getConsoleSender().sendMessage(alert);
                }
                return;
            }
        }

        // Ignores: hide the message from players who ignore the sender.
        if (social != null) {
            event.viewers().removeIf(a -> a instanceof Player viewer && social.isIgnoring(viewer.getUniqueId(), player.getUniqueId()));
        }

        Component message = player.hasPermission("genscore.chat.color") ? LEGACY.deserialize(raw) : Component.text(raw);
        Component prefix = luckPerms ? LEGACY.deserialize(LuckPermsPrefix.prefix(player)) : Component.empty();
        Component suffix = luckPerms ? LEGACY.deserialize(LuckPermsPrefix.suffix(player)) : Component.empty();
        Component line = MM.deserialize(format,
                Placeholder.component("prefix", prefix),
                Placeholder.component("suffix", suffix),
                Placeholder.component("badge", me.Evan52098.genscore.ranks.RanksModule.badgeComponent(player)), // group E
                Placeholder.component("tag", me.Evan52098.genscore.ranks.RanksModule.tagComponent(player)),     // group E
                Placeholder.component("name", me.Evan52098.genscore.cosmetics.CosmeticsModule.nameColor(player.getUniqueId()).isEmpty() // group N
                        ? player.displayName()
                        : me.Evan52098.genscore.cosmetics.CosmeticsModule.coloredName(player.getUniqueId(),
                                net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(player.displayName()))),
                Placeholder.component("message", message));
        event.renderer((source, displayName, msg, viewer) -> line);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        filter.forget(event.getPlayer().getUniqueId());
        cooldowns.clear(event.getPlayer().getUniqueId(), "chat");
    }

    // ------------------------------------------------------------------ commands

    @Command({"clearchat", "cc"})
    @CommandPermission("genscore.clearchat")
    @Description("Clear chat for everyone except staff")
    public void clearChat(BukkitCommandActor actor) {
        Component blank = Component.text(" ");
        String by = actor.name();
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> {
                if (!p.hasPermission("genscore.clearchat.exempt")) {
                    for (int i = 0; i < 100; i++) {
                        p.sendMessage(blank);
                    }
                }
                p.sendMessage(Msg.neutral("Chat was cleared by " + Msg.white(by) + "."));
            });
        }
    }

    @Command({"broadcast", "bc"})
    @CommandPermission("genscore.broadcast")
    @Description("Send a message to everyone")
    public void broadcast(BukkitCommandActor actor, @Named("message") String message) {
        Component line = Msg.parse("<dark_gray>[<gold>Broadcast</gold>]</dark_gray> ").append(Msg.parse(message));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
        Audience console = Bukkit.getConsoleSender();
        console.sendMessage(line);
    }
}
