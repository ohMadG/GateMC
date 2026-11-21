package me.Evan52098.genscore.staff;

import io.papermc.paper.event.player.AsyncChatEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.teleport.TeleportGuard;
import me.Evan52098.genscore.teleport.TeleportService;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Staff chat (/sc), admin chat (/ac), freeze, command spy and sign spy. Ported from BlockvilleCore's staffchat
 * package, FreezeService/FreezeCommand, StaffMonitor and SignSpy, without the cross-server bridge and Discord relay.
 */
public final class StaffTools implements Listener, TeleportGuard {

    private enum Channel {
        STAFF("genscore.staffchat", "<dark_aqua>[Staff]</dark_aqua>"),
        ADMIN("genscore.adminchat", "<dark_red>[Admin]</dark_red>");

        final String permission;
        final String tag;

        Channel(String permission, String tag) {
            this.permission = permission;
            this.tag = tag;
        }
    }

    private static final List<String> FROZEN_ALLOWED_COMMANDS = List.of("/msg", "/r", "/reply", "/tell", "/w", "/sc");

    private final java.util.Map<UUID, Channel> chatMode = new ConcurrentHashMap<>();
    private final Set<UUID> frozen = ConcurrentHashMap.newKeySet();
    private final Set<UUID> commandSpy = ConcurrentHashMap.newKeySet();
    private final Set<UUID> signSpy = ConcurrentHashMap.newKeySet();

    // ------------------------------------------------------------------ staff and admin chat

    @Command({"staffchat", "sc"})
    @CommandPermission("genscore.staffchat")
    @Description("Talk in staff chat, or toggle it on with no message")
    public void staffChat(BukkitCommandActor actor, @Named("message") @Optional @Nullable String message) {
        channel(actor, Channel.STAFF, message);
    }

    @Command({"adminchat", "ac"})
    @CommandPermission("genscore.adminchat")
    @Description("Talk in admin chat, or toggle it on with no message")
    public void adminChat(BukkitCommandActor actor, @Named("message") @Optional @Nullable String message) {
        channel(actor, Channel.ADMIN, message);
    }

    private void channel(BukkitCommandActor actor, Channel channel, @Nullable String message) {
        if (message != null && !message.isBlank()) {
            send(actor.sender(), channel, message);
            return;
        }
        Player player = actor.requirePlayer();
        if (chatMode.get(player.getUniqueId()) == channel) {
            chatMode.remove(player.getUniqueId());
            player.sendMessage(Msg.good("Your chat now goes to everyone."));
        } else {
            chatMode.put(player.getUniqueId(), channel);
            player.sendMessage(Msg.good("Your chat now goes to " + (channel == Channel.STAFF ? "staff" : "admin") + " chat."));
        }
    }

    private void send(CommandSender from, Channel channel, String message) {
        Component line = Msg.parse(channel.tag + " <white>" + Msg.escape(from.getName()) + "</white><gray>:</gray> " + Msg.escape(message));
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission(channel.permission)) {
                Tasks.onEntity(p, () -> p.sendMessage(line));
            }
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    /** Runs before the public chat module, so a toggled player's chat goes to their channel instead. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Channel channel = chatMode.get(player.getUniqueId());
        if (channel == null) {
            return;
        }
        if (!player.hasPermission(channel.permission)) {
            chatMode.remove(player.getUniqueId());
            return;
        }
        event.setCancelled(true);
        send(player, channel, PlainTextComponentSerializer.plainText().serialize(event.message()));
    }

    // ------------------------------------------------------------------ freeze

    @Command({"freeze", "ss"})
    @CommandPermission("genscore.freeze")
    @Description("Freeze a player for a screenshare, or unfreeze them")
    public void freeze(BukkitCommandActor actor, @Named("player") Player target) {
        if (target.hasPermission("genscore.freeze.exempt")) {
            actor.sender().sendMessage(Msg.bad("You can't freeze that player."));
            return;
        }
        if (frozen.contains(target.getUniqueId())) {
            unfreeze(target.getUniqueId(), actor.name());
        } else {
            freeze(target, actor.name(), null);
        }
    }

    /** Freezes a player (also used by the anti x-ray module). Safe from any thread. */
    public void freeze(Player target, String by, @Nullable String reason) {
        if (!frozen.add(target.getUniqueId())) {
            return;
        }
        Tasks.onEntity(target, () -> target.sendMessage(Msg.bad("You have been frozen by " + Msg.white(by)
                + ". Do not log out. Follow staff instructions.")));
        staffAlert(Msg.parse("<aqua>[Staff] <white>" + Msg.escape(by) + "</white> <gray>froze</gray> <white>" + target.getName()
                + "</white>" + (reason == null ? "" : " <gray>(" + Msg.escape(reason) + ")</gray>")));
    }

    /** Unfreezes a player. Safe from any thread. */
    public void unfreeze(UUID player) {
        unfreeze(player, "the server");
    }

    private void unfreeze(UUID player, String by) {
        if (!frozen.remove(player)) {
            return;
        }
        Player target = Bukkit.getPlayer(player);
        if (target != null) {
            Tasks.onEntity(target, () -> target.sendMessage(Msg.good("You have been unfrozen.")));
        }
        staffAlert(Msg.parse("<aqua>[Staff] <white>" + Msg.escape(by) + "</white> <gray>unfroze</gray> <white>"
                + (target != null ? target.getName() : player.toString())));
    }

    public int frozenCount() {
        return frozen.size();
    }

    public boolean isFrozen(UUID player) {
        return frozen.contains(player);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId()) && event.hasChangedPosition()) {
            Location from = event.getFrom();
            Location to = event.getTo().clone();
            to.setX(from.getX());
            to.setZ(from.getZ());
            if (to.getY() > from.getY()) {
                to.setY(from.getY());
            }
            event.setTo(to); // can still look around and fall
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p && frozen.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    /** A frozen player can't be hurt, so they must not be able to hurt anyone either (melee or projectiles). */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onAttack(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        org.bukkit.entity.Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            damager = shooter;
        }
        if (damager instanceof Player p && frozen.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onLaunch(org.bukkit.event.entity.ProjectileLaunchEvent event) {
        if (event.getEntity().getShooter() instanceof Player p && frozen.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(org.bukkit.event.block.BlockPlaceEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (frozen.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true); // no buckets, flint and steel, ender pearls, doors
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onFrozenJoin(org.bukkit.event.player.PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (frozen.contains(player.getUniqueId())) {
            player.sendMessage(Msg.bad("You are still frozen. Do not log out. Follow staff instructions."));
            staffAlert(Msg.parse("<red>[Staff] <white>" + player.getName() + "</white> rejoined while frozen."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        String label = event.getMessage().split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (frozen.contains(player.getUniqueId()) && FROZEN_ALLOWED_COMMANDS.stream().noneMatch(label::equals)) {
            event.setCancelled(true);
            player.sendMessage(Msg.bad("You can't use commands while frozen."));
            return;
        }
        if (!commandSpy.isEmpty() && !player.hasPermission("genscore.commandspy.exempt")) {
            Component line = Msg.parse("<dark_gray>[cmd] <gray>" + Msg.escape(player.getName()) + ":</gray> <white>" + Msg.escape(event.getMessage()));
            for (UUID id : commandSpy) {
                Player spy = Bukkit.getPlayer(id);
                if (spy != null && !spy.equals(player)) {
                    Tasks.onEntity(spy, () -> spy.sendMessage(line));
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        // Logging out does not end a freeze (a relog must not escape a screenshare); staff unfreeze with /freeze.
        if (frozen.contains(id)) {
            staffAlert(Msg.parse("<red>[Staff] <white>" + event.getPlayer().getName() + "</white> logged out while frozen."));
        }
        chatMode.remove(id);
        commandSpy.remove(id);
        signSpy.remove(id);
    }

    /** Frozen players cannot teleport (used by the teleport module). */
    @Override
    public @Nullable String deny(Player player, Location destination, TeleportService.Kind kind) {
        return frozen.contains(player.getUniqueId()) ? "You can't teleport while frozen." : null;
    }

    // ------------------------------------------------------------------ spies

    @Command({"commandspy", "cmdspy"})
    @CommandPermission("genscore.commandspy")
    @Description("See the commands players run")
    public void commandSpy(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        boolean on = commandSpy.add(player.getUniqueId()) || !commandSpy.remove(player.getUniqueId());
        player.sendMessage(Msg.good(on ? "Command spy enabled." : "Command spy disabled."));
    }

    @Command("signspy")
    @CommandPermission("genscore.signspy")
    @Description("See what players write on signs")
    public void signSpy(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        boolean on = signSpy.add(player.getUniqueId()) || !signSpy.remove(player.getUniqueId());
        player.sendMessage(Msg.good(on ? "Sign spy enabled." : "Sign spy disabled."));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        if (signSpy.isEmpty()) {
            return;
        }
        StringBuilder text = new StringBuilder();
        for (Component line : event.lines()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (!plain.isBlank()) {
                text.append(text.isEmpty() ? "" : " | ").append(plain);
            }
        }
        if (text.isEmpty()) {
            return;
        }
        Location l = event.getBlock().getLocation();
        Component line = Msg.parse("<dark_gray>[sign] <gray>" + Msg.escape(event.getPlayer().getName()) + " at "
                + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ() + ":</gray> <white>" + Msg.escape(text.toString()));
        for (UUID id : signSpy) {
            Player spy = Bukkit.getPlayer(id);
            if (spy != null) {
                Tasks.onEntity(spy, () -> spy.sendMessage(line));
            }
        }
    }

    static void staffAlert(Component message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.hasPermission("genscore.staff.alerts")) {
                Tasks.onEntity(p, () -> p.sendMessage(message));
            }
        }
        Bukkit.getConsoleSender().sendMessage(message);
    }
}
