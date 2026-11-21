package me.Evan52098.genscore.essentials;

import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * /msg, /r, /msgtoggle, /socialspy, /ignore, /unignore, /ignorelist, /nick, /realname, /afk, /seen, /playtime, /near,
 * /rules. Ported from BlockvilleCore with the same messages, minus cross-server messaging. Public chat ignoring is
 * done by the chat module through {@link #isIgnoring}.
 */
public final class SocialCommands implements Listener {

    private static final Pattern NICK = Pattern.compile("[A-Za-z0-9_]+");

    private final PlayerDataService players;
    private final Social.Section section;
    private final int nickMin;
    private final int nickMax;
    private final long nickCooldownMillis;
    private final long autoAfkMillis;
    private final List<String> rules;

    private final Map<UUID, UUID> lastMessaged = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastActive = new ConcurrentHashMap<>();
    private final Set<UUID> afk = ConcurrentHashMap.newKeySet();
    private final Cooldowns lookups = new Cooldowns();

    SocialCommands(PlayerDataService players, Social.Section section, int nickMin, int nickMax, long nickCooldownSeconds,
                   long autoAfkMinutes, List<String> rules) {
        this.players = players;
        this.section = section;
        this.nickMin = nickMin;
        this.nickMax = nickMax;
        this.nickCooldownMillis = TimeUnit.SECONDS.toMillis(nickCooldownSeconds);
        this.autoAfkMillis = TimeUnit.MINUTES.toMillis(autoAfkMinutes);
        this.rules = List.copyOf(rules);
        if (autoAfkMillis > 0) {
            Tasks.async.timer(t -> checkIdle(), 30, 30, TimeUnit.SECONDS);
        }
    }

    private boolean lookupLimited(CommandSender sender) {
        if (sender instanceof Player p && !lookups.tryStart(p.getUniqueId(), "lookup", 1000)) {
            sender.sendMessage(Msg.bad("Slow down."));
            return true;
        }
        return false;
    }

    /** The online player's social data (used by the settings menu). */
    public @Nullable Social social(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data == null ? null : data.get(section);
    }

    /** True when {@code receiver} ignores {@code sender}. For the chat module. */
    public boolean isIgnoring(UUID receiver, UUID sender) {
        Social s = social(receiver);
        return s != null && s.isIgnoring(sender);
    }

    public boolean isAfk(UUID player) {
        return afk.contains(player);
    }

    // ------------------------------------------------------------------ /msg

    @Command({"msg", "m", "whisper", "w", "tell"})
    @CommandPermission("genscore.msg")
    @Description("Send a private message")
    public void msg(BukkitCommandActor actor, @Named("player") Player target, @Named("message") String message) {
        send(actor.sender(), target, message);
    }

    @Command({"reply", "r"})
    @CommandPermission("genscore.msg")
    @Description("Reply to your last private message")
    public void reply(BukkitCommandActor actor, @Named("message") String message) {
        Player sender = actor.requirePlayer();
        UUID last = lastMessaged.get(sender.getUniqueId());
        Player target = last == null ? null : Bukkit.getPlayer(last);
        if (target == null) {
            sender.sendMessage(Msg.bad("You have nobody to reply to."));
            return;
        }
        send(sender, target, message);
    }

    private void send(CommandSender sender, Player target, String message) {
        if (sender instanceof Player p) {
            if (p.equals(target)) {
                sender.sendMessage(Msg.bad("You can't message yourself."));
                return;
            }
            Social own = social(p.getUniqueId());
            if (own != null && !own.msgEnabled()) {
                sender.sendMessage(Msg.bad("You have messages turned off. Use " + Msg.white("/msgtoggle") + "."));
                return;
            }
            Social theirs = social(target.getUniqueId());
            boolean staff = p.hasPermission("genscore.msg.bypass");
            if (theirs != null && !staff && (!theirs.msgEnabled() || theirs.isIgnoring(p.getUniqueId()))) {
                sender.sendMessage(Msg.bad("That player isn't accepting messages."));
                return;
            }
            me.Evan52098.genscore.chat.ChatModule chat = me.Evan52098.genscore.command.Suggest.module(me.Evan52098.genscore.chat.ChatModule.class);
            String blocked = chat == null ? null : chat.privateMessageBlocked(p, message);
            if (blocked != null) {
                sender.sendMessage(Msg.bad("Your message was not sent: " + blocked + "."));
                return;
            }
            if (chat != null && chat.privateMessageShadowed(p)) {
                sender.sendMessage(Msg.parse("<gray>[<white>me</white> -> <white>" + target.getName() + "</white>]</gray> " + Msg.escape(message)));
                return;
            }
            lastMessaged.put(p.getUniqueId(), target.getUniqueId());
            lastMessaged.put(target.getUniqueId(), p.getUniqueId());
            if (afk.contains(target.getUniqueId())) {
                sender.sendMessage(Msg.neutral("Heads up - " + Msg.white(target.getName()) + " is AFK and may not respond right away."));
            }
        }
        String text = Msg.escape(message);
        String from = sender.getName();
        String to = target.getName();
        sender.sendMessage(Msg.parse("<gray>[<white>me</white> -> <white>" + to + "</white>]</gray> " + text));
        Tasks.onEntity(target, () -> target.sendMessage(Msg.parse("<gray>[<white>" + from + "</white> -> <white>me</white>]</gray> " + text)));
        Component spy = Msg.parse("<dark_gray>[spy] <gray>" + from + " -> " + to + ":</gray> " + text);
        for (Player online : Bukkit.getOnlinePlayers()) {
            Social s = social(online.getUniqueId());
            if (s != null && s.socialSpy() && !online.equals(sender) && !online.equals(target)) {
                Tasks.onEntity(online, () -> online.sendMessage(spy));
            }
        }
    }

    @Command("msgtoggle")
    @CommandPermission("genscore.msg")
    @Description("Turn private messages on or off")
    public void msgToggle(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Social s = social(player.getUniqueId());
        if (s == null) {
            return;
        }
        boolean on = !s.msgEnabled();
        s.msgEnabled(on);
        player.sendMessage(Msg.good(on ? "Messages enabled." : "Messages disabled."));
    }

    @Command("socialspy")
    @CommandPermission("genscore.socialspy")
    @Description("See other players' private messages")
    public void socialSpy(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Social s = social(player.getUniqueId());
        if (s == null) {
            return;
        }
        boolean on = !s.socialSpy();
        s.socialSpy(on);
        player.sendMessage(Msg.good(on ? "Social spy enabled." : "Social spy disabled."));
    }

    // ------------------------------------------------------------------ ignore

    @Command("ignore")
    @CommandPermission("genscore.ignore")
    @Description("Hide a player's messages")
    public void ignore(BukkitCommandActor actor, @Named("player") String name) {
        Player player = actor.requirePlayer();
        if (lookupLimited(player)) {
            return;
        }
        Social own = social(player.getUniqueId());
        if (own != null && own.ignored().size() >= 200) {
            player.sendMessage(Msg.bad("You can't ignore more than " + Msg.white("200") + " players."));
            return;
        }
        players.findUuid(name).whenComplete((found, error) -> Tasks.entity.run(player, () -> {
            Social s = social(player.getUniqueId());
            if (error != null || found.isEmpty() || s == null) {
                player.sendMessage(Msg.bad("Player not found."));
                return;
            }
            UUID other = found.get();
            if (other.equals(player.getUniqueId())) {
                player.sendMessage(Msg.bad("You can't ignore yourself."));
                return;
            }
            Player online = Bukkit.getPlayer(other);
            if (online != null && online.hasPermission("genscore.ignore.exempt")) {
                player.sendMessage(Msg.bad("You can't ignore staff."));
                return;
            }
            player.sendMessage(s.ignore(other) ? Msg.good("You are now ignoring " + Msg.white(name) + ".")
                    : Msg.bad("You are already ignoring " + Msg.white(name) + "."));
        }));
    }

    @Command("unignore")
    @CommandPermission("genscore.ignore")
    @Description("Stop ignoring a player")
    public void unignore(BukkitCommandActor actor, @Named("player") String name) {
        Player player = actor.requirePlayer();
        if (lookupLimited(player)) {
            return;
        }
        players.findUuid(name).whenComplete((found, error) -> Tasks.entity.run(player, () -> {
            Social s = social(player.getUniqueId());
            if (error != null || found.isEmpty() || s == null || !s.unignore(found.get())) {
                player.sendMessage(Msg.bad("You are not ignoring " + Msg.white(name) + "."));
                return;
            }
            player.sendMessage(Msg.good("You are no longer ignoring " + Msg.white(name) + "."));
        }));
    }

    @Command({"ignorelist", "ignored"})
    @CommandPermission("genscore.ignore")
    @Description("List the players you ignore")
    public void ignoreList(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (lookupLimited(player)) {
            return;
        }
        Social s = social(player.getUniqueId());
        if (s == null || s.ignored().isEmpty()) {
            player.sendMessage(Msg.neutral("You are not ignoring anyone."));
            return;
        }
        List<java.util.concurrent.CompletableFuture<String>> names = new ArrayList<>();
        for (UUID id : s.ignored()) {
            names.add(players.findName(id).thenApply(n -> n.orElse(id.toString().substring(0, 8))));
        }
        java.util.concurrent.CompletableFuture.allOf(names.toArray(new java.util.concurrent.CompletableFuture[0]))
                .whenComplete((v, e) -> Tasks.entity.run(player, () -> {
                    List<String> list = new ArrayList<>();
                    names.forEach(f -> list.add(f.getNow("?")));
                    player.sendMessage(Msg.neutral("Ignored players: " + Msg.white(String.join(", ", list))));
                }));
    }

    // ------------------------------------------------------------------ nicknames

    @Command({"nickname", "nick"})
    @CommandPermission("genscore.nickname")
    @Description("Set a nickname")
    public void nick(BukkitCommandActor actor, @Named("nickname") String nick) {
        Player player = actor.requirePlayer();
        Social s = social(player.getUniqueId());
        if (s == null) {
            player.sendMessage(Msg.bad("Your data is still loading. Try again in a moment."));
            return;
        }
        if (!NICK.matcher(nick).matches()) {
            player.sendMessage(Msg.bad("Nicknames may only contain letters, numbers and underscores."));
            return;
        }
        if (nick.length() < nickMin || nick.length() > nickMax) {
            player.sendMessage(Msg.bad("Your nickname must be between " + Msg.white(String.valueOf(nickMin)) + " and "
                    + Msg.white(String.valueOf(nickMax)) + " characters."));
            return;
        }
        long left = s.nickChangedAt() + nickCooldownMillis - System.currentTimeMillis();
        if (left > 0 && !player.hasPermission("genscore.nickname.bypasscooldown")) {
            player.sendMessage(Msg.bad("You must wait " + Msg.white(Cooldowns.format(left)) + " before changing your nickname again."));
            return;
        }
        players.findUuid(nick).whenComplete((owner, error) -> Tasks.entity.run(player, () -> {
            if (error == null && owner.isPresent() && !owner.get().equals(player.getUniqueId())) {
                player.sendMessage(Msg.bad("That name belongs to another player."));
                return;
            }
            s.nickname(nick);
            applyNick(player, nick);
            player.sendMessage(Msg.good("Your nickname is now " + Msg.white(nick) + "."));
        }));
    }

    @Command({"nickname off", "nick off", "nickname reset", "nick reset"})
    @CommandPermission("genscore.nickname")
    public void nickOff(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Social s = social(player.getUniqueId());
        if (s == null || s.nickname() == null) {
            player.sendMessage(Msg.neutral("You don't have a nickname set."));
            return;
        }
        s.nickname(null);
        applyNick(player, null);
        player.sendMessage(Msg.good("Your nickname has been removed."));
    }

    @Command({"nickname admin reset", "nick admin reset"})
    @CommandPermission("genscore.nickname.admin")
    public void nickAdminReset(BukkitCommandActor actor, @Named("player") Player target) {
        Social s = social(target.getUniqueId());
        if (s == null) {
            actor.sender().sendMessage(Msg.bad("Their data is still loading. Try again in a moment."));
            return;
        }
        s.nickname(null);
        Tasks.onEntity(target, () -> applyNick(target, null));
        actor.sender().sendMessage(Msg.good("Removed " + Msg.white(target.getName()) + "'s nickname."));
    }

    @Command("realname")
    @CommandPermission("genscore.realname")
    @Description("Find who is using a nickname")
    public void realname(BukkitCommandActor actor, @Named("nickname") String nick) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            Social s = social(online.getUniqueId());
            if (s != null && nick.equalsIgnoreCase(s.nickname())) {
                actor.sender().sendMessage(Msg.neutral(Msg.white(nick) + " is " + Msg.white(online.getName()) + "."));
                return;
            }
        }
        actor.sender().sendMessage(Msg.bad("No player is using the nickname " + Msg.white(nick) + "."));
    }

    private static void applyNick(Player player, @Nullable String nick) {
        Component name = nick == null ? Component.text(player.getName()) : Component.text(nick);
        player.displayName(name);
        player.playerListName(nick == null ? null : name);
    }

    // ------------------------------------------------------------------ AFK

    @Command("afk")
    @CommandPermission("genscore.afk")
    @Description("Mark yourself as away")
    public void afk(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (afk.remove(player.getUniqueId())) {
            player.sendMessage(Msg.good("You are no longer AFK."));
        } else {
            afk.add(player.getUniqueId());
            player.sendMessage(Msg.good("You are now " + Msg.white("AFK") + ". Others will be told you might not respond."));
        }
    }

    private void checkIdle() {
        long now = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            Long last = lastActive.get(player.getUniqueId());
            if (last != null && now - last > autoAfkMillis && afk.add(player.getUniqueId())) {
                Tasks.entity.run(player, () -> player.sendMessage(Msg.neutral("You are now " + Msg.white("AFK") + ".")));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!event.hasChangedBlock() && !event.hasChangedOrientation()) {
            return;
        }
        Player player = event.getPlayer();
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        if (afk.remove(player.getUniqueId())) {
            player.sendMessage(Msg.good("You are no longer AFK."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        lastActive.put(player.getUniqueId(), System.currentTimeMillis());
        Social s = social(player.getUniqueId());
        if (s != null && s.nickname() != null) {
            applyNick(player, s.nickname());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        lastMessaged.remove(id);
        lastActive.remove(id);
        afk.remove(id);
    }

    // ------------------------------------------------------------------ seen, playtime, near, rules

    @Command("seen")
    @CommandPermission("genscore.seen")
    @Description("When a player was last online")
    public void seen(BukkitCommandActor actor, @Named("player") String name) {
        CommandSender sender = actor.sender();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && (!(sender instanceof Player viewer) || viewer.canSee(online))) {
            sender.sendMessage(Msg.neutral(Msg.white(online.getName()) + " is online now."));
            return;
        }
        if (lookupLimited(sender)) {
            return;
        }
        players.lookup(name).whenComplete((found, error) -> Tasks.forSender(sender, () -> {
            if (error != null || found.isEmpty()) {
                sender.sendMessage(Msg.bad("Player not found."));
                return;
            }
            long ago = System.currentTimeMillis() - found.get().lastSeen();
            sender.sendMessage(Msg.neutral(Msg.white(found.get().name()) + " was last seen " + Msg.white(duration(ago)) + " ago."));
        }));
    }

    @Command("playtime")
    @CommandPermission("genscore.playtime")
    @Description("How long you (or another player) have played")
    public void playtime(BukkitCommandActor actor, @Named("player") @Optional @Nullable String name) {
        CommandSender sender = actor.sender();
        if (name == null) {
            Player self = actor.requirePlayer();
            sender.sendMessage(Msg.neutral("Your playtime: " + Msg.white(duration(players.get(self).playtimeMs()))));
            return;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null && players.get(online.getUniqueId()) != null) {
            sender.sendMessage(Msg.neutral(Msg.white(online.getName()) + "'s playtime: " + Msg.white(duration(players.get(online).playtimeMs()))));
            return;
        }
        if (lookupLimited(sender)) {
            return;
        }
        players.lookup(name).whenComplete((found, error) -> Tasks.forSender(sender, () -> {
            if (error != null || found.isEmpty()) {
                sender.sendMessage(Msg.bad("Player not found."));
                return;
            }
            sender.sendMessage(Msg.neutral(Msg.white(found.get().name()) + "'s playtime: " + Msg.white(duration(found.get().playtimeMs()))));
        }));
    }

    @Command("near")
    @CommandPermission("genscore.near")
    @Description("Players near you")
    public void near(BukkitCommandActor actor, @Named("radius") @Optional @Nullable Integer radius) {
        Player player = actor.requirePlayer();
        int r = Math.min(radius != null && radius > 0 ? radius : 200, 1000);
        Location here = player.getLocation();
        List<String> names = new ArrayList<>();
        // getNearbyPlayers only sees players in this region, which is what Folia allows us to read.
        for (Player other : here.getNearbyPlayers(r)) {
            if (!other.equals(player) && player.canSee(other)) { // vanished staff stay hidden
                names.add("<yellow>" + other.getName() + "</yellow> <dark_gray>(<red>" + (int) other.getLocation().distance(here) + "</red>m)</dark_gray>");
            }
        }
        player.sendMessage(names.isEmpty() ? Msg.neutral("No players nearby.")
                : Msg.neutral("Players nearby (" + Msg.white(String.valueOf(r)) + " blocks): " + String.join("<gray>, </gray>", names)));
    }

    // /rules moved to the info module (HXSMP rules menu, group E). essentials.rules in config.yml is no longer read here.

    static String duration(long millis) {
        long minutes = Math.max(0, millis) / 60_000;
        long days = minutes / 1440;
        long hours = (minutes % 1440) / 60;
        long mins = minutes % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + mins + "m";
        }
        return mins + "m";
    }
}
