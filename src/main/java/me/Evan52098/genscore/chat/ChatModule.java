package me.Evan52098.genscore.chat;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.essentials.SocialCommands;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.punish.PunishModule;
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
 * The filter only blocks; repeated word hits file a report that staff accept (chat mute) or decline in /reports.
 *
 * <p>Folia: {@link AsyncChatEvent} is already off the game threads. Messages to other players go out on their threads.
 */
public final class ChatModule implements GensModule, Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private GensCore plugin;
    private ChatFilter filter;
    private FilterReports filterReports;
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
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("chat");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        filter = new ChatFilter(c.getConfigurationSection("filter"), plugin.database(), plugin.getLogger());
        filterReports = new FilterReports(c.getConfigurationSection("filter.reports"), plugin.database(), plugin.getLogger());
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
        boolean gg = GgWave.active() && GgWave.isGg(raw);
        if (gg && GgWave.tooFast(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }

        if (!gg && cooldownMillis > 0 && !player.hasPermission("genscore.chat.bypass.cooldown")
                && !cooldowns.tryStart(player.getUniqueId(), "chat", cooldownMillis)) {
            event.setCancelled(true);
            player.sendMessage(Msg.bad("Please wait " + Msg.white(Cooldowns.format(cooldowns.remaining(player.getUniqueId(), "chat"))) + " before chatting again."));
            return;
        }

        Component muteNotice = PunishModule.chatBlock(player.getUniqueId());
        if (muteNotice != null) {
            event.setCancelled(true);
            player.sendMessage(muteNotice);
            return;
        }

        if (!gg && !allowed(player, raw, false)) {
            event.setCancelled(true);
            return;
        }

        if (PunishModule.shadowMuted(player.getUniqueId())) {
            event.viewers().removeIf(a -> a instanceof Player viewer && !viewer.equals(player));
        }

        // Ignores: hide the message from players who ignore the sender.
        if (social != null) {
            event.viewers().removeIf(a -> a instanceof Player viewer && social.isIgnoring(viewer.getUniqueId(), player.getUniqueId()));
        }

        Component colored = raw.indexOf('&') >= 0 && player.hasPermission("genscore.chat.color") ? null
                : me.Evan52098.genscore.cosmetics.CosmeticsModule.chatColored(player.getUniqueId(), raw);
        Component message = gg ? GgWave.render(raw) : colored != null ? colored
                : player.hasPermission("genscore.chat.color") ? LEGACY.deserialize(raw) : Component.text(raw);
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

    public @org.jetbrains.annotations.Nullable String privateMessageBlocked(Player player, String raw) {
        String muted = PunishModule.privateMessageBlock(player.getUniqueId());
        if (muted != null) {
            return muted;
        }
        return allowed(player, raw, true) ? null : "it was blocked by the chat filter";
    }

    public boolean privateMessageShadowed(Player player) {
        return PunishModule.shadowMuted(player.getUniqueId());
    }

    private boolean allowed(Player player, String raw, boolean privateMessage) {
        if (player.hasPermission("genscore.chat.bypass.filter")) {
            return true;
        }
        ChatFilter.Result result = filter.check(player.getUniqueId(), raw);
        if (!result.blocked()) {
            return true;
        }
        if (!privateMessage) {
            String why = result.matchedWord() != null ? "contains the word \"" + result.matchedWord() + "\"" : result.reason();
            player.sendMessage(Msg.bad("Your message was not sent: " + Msg.escape(why) + "."));
        }
        if (result.alertStaff()) {
            String label = "high-risk".equals(result.category()) ? "high risk, please review" : result.category();
            String word = result.matchedWord();
            String buttons = word == null ? "" : " <gray>[</gray><white>" + Msg.escape(word) + "</white><gray>]</gray>"
                    + " <click:run_command:'/filter deny " + Msg.escape(word) + "'><hover:show_text:'<gray>Stop flagging this word'>"
                    + "<green><bold>[Deny]</bold></green></hover></click>"
                    + " <click:run_command:'/filter add " + Msg.escape(word) + "'><hover:show_text:'<gray>Confirm this word is banned'>"
                    + "<red><bold>[Add]</bold></red></hover></click>";
            Component alert = Msg.parse("<dark_red>[Filter] <red>" + Msg.escape(player.getName()) + "</red> <gray>(" + label
                    + (privateMessage ? ", /msg" : "") + "):</gray> <white>" + Msg.escape(raw) + "</white>" + buttons);
            for (Player staff : Bukkit.getOnlinePlayers()) {
                if (staff.hasPermission("genscore.chat.alerts")) {
                    Tasks.onEntity(staff, () -> staff.sendMessage(alert));
                }
            }
            Bukkit.getConsoleSender().sendMessage(alert);
        }
        filterReports.strike(player, raw, result);
        return false;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        filter.forget(event.getPlayer().getUniqueId());
        filterReports.forget(event.getPlayer().getUniqueId());
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
        Component line = Msg.parse(Msg.headerText("Broadcast")).append(Msg.parse(message));
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
        Audience console = Bukkit.getConsoleSender();
        console.sendMessage(line);
    }

    @Command("filter add")
    @CommandPermission("genscore.chat.filter.admin")
    @Description("Confirm a chat filter word or phrase as banned (live)")
    public void filterAdd(BukkitCommandActor actor, @Named("word") String word) {
        if (filter.addWord(word, actor.name())) {
            actor.sender().sendMessage(Msg.good("Added " + Msg.white(Msg.escape(word)) + " to the chat filter."));
        } else {
            actor.sender().sendMessage(Msg.neutral(Msg.white(Msg.escape(word)) + " is already blocked."));
        }
    }

    @Command("filter deny")
    @CommandPermission("genscore.chat.filter.admin")
    @Description("Stop a chat filter word or phrase from being flagged (live)")
    public void filterDeny(BukkitCommandActor actor, @Named("word") String word) {
        switch (filter.denyWord(word, actor.name())) {
            case DENIED -> actor.sender().sendMessage(Msg.good("Denied " + Msg.white(Msg.escape(word)) + "; it will no longer be flagged."));
            case ALREADY_ALLOWED -> actor.sender().sendMessage(Msg.neutral(Msg.white(Msg.escape(word)) + " is already allowed."));
            case NOT_LISTED -> {
                actor.sender().sendMessage(Msg.bad(Msg.white(Msg.escape(word)) + " isn't on the filter list, so nothing changed."));
                for (String listed : filter.listedWithin(word)) {
                    actor.sender().sendMessage(Msg.parse("<gray>Did you mean </gray><click:run_command:'/filter deny "
                            + Msg.escape(listed) + "'><green><bold>[" + Msg.escape(listed) + "]</bold></green></click><gray>?</gray>"));
                }
            }
        }
    }

    @Command("mute")
    @CommandPermission("genscore.mute")
    @Description("Mute a player in chat")
    public void mute(BukkitCommandActor actor, @Named("player") String name, @Named("duration") String duration, @Named("reason") String reason) {
        var sender = actor.sender();
        long millis = parseDuration(duration);
        if (millis <= 0) {
            sender.sendMessage(Msg.bad("Use a duration like " + Msg.white("30m") + ", " + Msg.white("2h") + " or " + Msg.white("1d") + "."));
            return;
        }
        String by = actor.name();
        java.util.UUID byUuid = sender instanceof Player staff ? staff.getUniqueId() : null;
        resolve(name).whenComplete((target, error) -> Tasks.forSender(sender, () -> {
            if (target == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(Msg.escape(name)) + " has joined."));
                return;
            }
            PunishModule.mute(target.getKey(), target.getValue(), millis, reason, byUuid, by).whenComplete((outcome, e) -> Tasks.forSender(sender, () -> {
                if (e != null) {
                    Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                    sender.sendMessage(Msg.bad("The mute was not applied: " + Msg.escape(String.valueOf(cause.getMessage())) + "."));
                    return;
                }
                sender.sendMessage(Msg.good(Msg.white(target.getValue()) + " " + Msg.escape(outcome) + "."));
            }));
        }));
    }

    @Command("unmute")
    @CommandPermission("genscore.mute")
    @Description("Unmute a player")
    public void unmute(BukkitCommandActor actor, @Named("player") String name) {
        var sender = actor.sender();
        resolve(name).whenComplete((target, error) -> Tasks.forSender(sender, () -> {
            if (target == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(Msg.escape(name)) + " has joined."));
                return;
            }
            PunishModule.unmute(target.getKey(), actor.name()).whenComplete((had, e) -> Tasks.forSender(sender, () -> {
                if (e != null || !had) {
                    sender.sendMessage(Msg.neutral(Msg.white(target.getValue()) + " isn't muted."));
                    return;
                }
                sender.sendMessage(Msg.good("Unmuted " + Msg.white(target.getValue()) + "."));
                Player p = Bukkit.getPlayer(target.getKey());
                if (p != null) {
                    Tasks.onEntity(p, () -> p.sendMessage(Msg.good("You can chat again.")));
                }
            }));
        }));
    }

    private static long parseDuration(String text) {
        String t = text.trim().toLowerCase(java.util.Locale.ROOT);
        if (!t.matches("\\d{1,6}[smhdw]")) {
            return -1;
        }
        long n = Long.parseLong(t.substring(0, t.length() - 1));
        return switch (t.charAt(t.length() - 1)) {
            case 's' -> n * 1000L;
            case 'm' -> n * 60_000L;
            case 'h' -> n * 3_600_000L;
            case 'd' -> n * 86_400_000L;
            default -> n * 604_800_000L;
        };
    }

    private java.util.concurrent.CompletableFuture<java.util.Map.Entry<java.util.UUID, String>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Map.entry(online.getUniqueId(), online.getName()));
        }
        return plugin.playerData().findUuid(name).thenApply(o -> o.map(u -> java.util.Map.entry(u, name)).orElse(null));
    }
}
