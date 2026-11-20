package me.Evan52098.genscore.teleport;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * /tpa, /tpahere, /tpaccept, /tpdeny, /tpacancel. Ported from BlockvilleCore's TpaCommand and TpaService, same
 * messages, minus cross-server requests.
 *
 * <p>Folia: the two players may be on different threads. Each one's location is read on their own thread, and the
 * teleport runs on the moving player's thread.
 */
public final class TpaCommands {

    private record Request(UUID requester, boolean here, long expiresAt) {
    }

    private final TeleportService teleports;
    private final long timeoutMillis;
    private final long cooldownMillis;
    private final Cooldowns cooldowns = new Cooldowns();
    /** target -> (requester -> request) */
    private final Map<UUID, Map<UUID, Request>> requests = new ConcurrentHashMap<>();

    private final me.Evan52098.genscore.GensCore plugin;

    TpaCommands(me.Evan52098.genscore.GensCore plugin, TeleportService teleports, long timeoutSeconds, long cooldownSeconds) {
        this.plugin = plugin;
        this.teleports = teleports;
        this.timeoutMillis = TimeUnit.SECONDS.toMillis(Math.max(5, timeoutSeconds));
        this.cooldownMillis = TimeUnit.SECONDS.toMillis(Math.max(0, cooldownSeconds));
        Tasks.async.timer(t -> expire(), 1, 1, TimeUnit.SECONDS);
    }

    @Command("tpa")
    @CommandPermission("genscore.tpa")
    @Description("Ask to teleport to a player")
    public void tpa(BukkitCommandActor actor, @Named("player") Player target) {
        send(actor.requirePlayer(), target, false);
    }

    @Command("tpahere")
    @CommandPermission("genscore.tpa")
    @Description("Ask a player to teleport to you")
    public void tpaHere(BukkitCommandActor actor, @Named("player") Player target) {
        send(actor.requirePlayer(), target, true);
    }

    private void send(Player sender, Player target, boolean here) {
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(Msg.bad("You cannot send a TPA request to yourself."));
            return;
        }
        // /tptoggle (settings module): players can refuse teleport requests.
        var settings = plugin.modules().get(me.Evan52098.genscore.settings.SettingsModule.class);
        if (settings != null && !settings.tpaEnabled(target.getUniqueId()) && !sender.hasPermission("genscore.tp.toggle.bypass")) {
            sender.sendMessage(Msg.bad(Msg.white(target.getName()) + " isn't accepting teleport requests right now."));
            return;
        }
        long left = cooldowns.remaining(sender.getUniqueId(), "tpa");
        if (left > 0) {
            sender.sendMessage(Msg.bad("Please wait " + Msg.white(Cooldowns.format(left)) + " before sending another request."));
            return;
        }
        cooldowns.start(sender.getUniqueId(), "tpa", cooldownMillis);
        Request request = new Request(sender.getUniqueId(), here, System.currentTimeMillis() + timeoutMillis);
        requests.compute(target.getUniqueId(), (k, pending) -> {
            Map<UUID, Request> m = pending != null ? pending : new ConcurrentHashMap<>();
            m.put(sender.getUniqueId(), request);
            return m;
        });
        sender.sendMessage(here ? Msg.good("Asked " + Msg.white(target.getName()) + " to teleport to you.")
                : Msg.good("Teleport request sent to " + Msg.white(target.getName()) + "."));
        String name = sender.getName();
        String question = here ? Msg.white(name) + " wants you to teleport to them."
                : Msg.white(name) + " wants to teleport to you.";
        Tasks.onEntity(target, () -> target.sendMessage(Msg.neutral(question
                + " <green><click:run_command:/tpaccept " + name + ">/tpaccept</click></green> <gray>|</gray> "
                + "<red><click:run_command:/tpdeny " + name + ">/tpdeny</click></red>")));
    }

    @Command({"tpaccept", "tpaaccept"})
    @CommandPermission("genscore.tpa")
    @Description("Accept a teleport request")
    public void accept(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player from) {
        Player target = actor.requirePlayer();
        Request request = take(target.getUniqueId(), from == null ? null : from.getUniqueId());
        Player requester = request == null ? null : Bukkit.getPlayer(request.requester());
        if (request == null || requester == null) {
            target.sendMessage(Msg.bad("You have no pending teleport requests."));
            return;
        }
        target.sendMessage(Msg.good("Teleport request accepted."));
        if (!request.here()) {
            // The requester comes here: read our location on our thread, teleport them on theirs.
            Location destination = target.getLocation();
            Tasks.entity.run(requester, () -> {
                requester.sendMessage(Msg.good("Teleport request accepted."));
                teleports.teleport(requester, destination, TeleportService.Kind.TPA,
                        Msg.good("Teleported to " + Msg.white(target.getName()) + "."));
            });
        } else {
            // We go to the requester: read their location on their thread, then teleport us on ours.
            Tasks.entity.run(requester, () -> {
                Location destination = requester.getLocation();
                requester.sendMessage(Msg.good("Teleport request accepted."));
                Tasks.entity.run(target, () -> teleports.teleport(target, destination, TeleportService.Kind.TPA,
                        Msg.good("Teleported to " + Msg.white(requester.getName()) + ".")));
            });
        }
    }

    @Command({"tpdeny", "tpadeny"})
    @CommandPermission("genscore.tpa")
    @Description("Deny a teleport request")
    public void deny(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player from) {
        Player target = actor.requirePlayer();
        Request request = take(target.getUniqueId(), from == null ? null : from.getUniqueId());
        if (request == null) {
            target.sendMessage(Msg.bad("You have no pending teleport requests."));
            return;
        }
        target.sendMessage(Msg.neutral("Teleport request denied."));
        Player requester = Bukkit.getPlayer(request.requester());
        if (requester != null) {
            Tasks.onEntity(requester, () -> requester.sendMessage(Msg.bad("Teleport request denied.")));
        }
    }

    @Command("tpacancel")
    @CommandPermission("genscore.tpa")
    @Description("Cancel the teleport requests you sent")
    public void cancel(BukkitCommandActor actor) {
        Player sender = actor.requirePlayer();
        boolean any = false;
        for (Map<UUID, Request> perTarget : requests.values()) {
            any |= perTarget.remove(sender.getUniqueId()) != null;
        }
        sender.sendMessage(any ? Msg.neutral("Teleport request canceled.") : Msg.bad("You have no pending teleport requests."));
    }

    /** Removes and returns a request to {@code target}: from {@code from}, or the newest one when null. */
    private @Nullable Request take(UUID target, @Nullable UUID from) {
        Map<UUID, Request> pending = requests.get(target);
        if (pending == null) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (from != null) {
            Request r = pending.remove(from);
            return r != null && r.expiresAt() > now ? r : null;
        }
        Request newest = null;
        for (Request r : pending.values()) {
            if (r.expiresAt() > now && (newest == null || r.expiresAt() > newest.expiresAt())) {
                newest = r;
            }
        }
        if (newest != null) {
            pending.remove(newest.requester(), newest);
        }
        return newest;
    }

    private void expire() {
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Map<UUID, Request>> entry : requests.entrySet()) {
            Iterator<Request> it = entry.getValue().values().iterator();
            while (it.hasNext()) {
                Request r = it.next();
                if (r.expiresAt() > now) {
                    continue;
                }
                it.remove();
                Player requester = Bukkit.getPlayer(r.requester());
                Player target = Bukkit.getPlayer(entry.getKey());
                if (requester != null) {
                    String to = target != null ? target.getName() : "Unknown";
                    Tasks.entity.run(requester, () -> requester.sendMessage(
                            Msg.bad("Your teleport request to " + Msg.white(to) + " has expired.")));
                }
                if (target != null) {
                    String from = requester != null ? requester.getName() : "Unknown";
                    Tasks.entity.run(target, () -> target.sendMessage(
                            Msg.bad("The teleport request from " + Msg.white(from) + " has expired.")));
                }
            }
            requests.computeIfPresent(entry.getKey(), (k, m) -> m.isEmpty() ? null : m);
        }
    }

    /** Drops everything involving a player who left. */
    void forget(UUID player) {
        requests.remove(player);
        for (Map<UUID, Request> perTarget : requests.values()) {
            perTarget.remove(player);
        }
    }
}
