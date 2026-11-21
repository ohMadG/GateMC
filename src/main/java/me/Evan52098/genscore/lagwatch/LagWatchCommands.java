package me.Evan52098.genscore.lagwatch;

import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** BlockvilleCore's LagWatchCommand: /lagwatch [top|log|limits|tp|alerts|reload|on|off]. */
public final class LagWatchCommands {

    private static final String PREFIX = "&6&lLAGWATCH &8» &7";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm").withZone(ZoneId.systemDefault());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final LagWatchService service;

    LagWatchCommands(LagWatchService service) {
        this.service = service;
    }

    private static void say(CommandSender to, String message) {
        to.sendMessage(LEGACY.deserialize(PREFIX + message));
    }

    private static String state(boolean on) {
        return on ? "&aon" : "&coff";
    }

    @Command("lagwatch")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("Lag machine watch status")
    public void status(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        say(sender, "Watching for lag machines: " + state(service.isEnabled()) + "&7. Discord reports " + state(service.hasWebhook()) + "&7.");
        say(sender, "&f/lagwatch top&7 (busiest places now), &f/lagwatch log&7, &f/lagwatch limits&7, &f/lagwatch alerts&7 (mute or unmute your own)");
        if (sender.hasPermission(LagWatchService.ADMIN_PERMISSION)) {
            say(sender, "&f/lagwatch on|off&7, &f/lagwatch reload");
        }
    }

    @Command("lagwatch top")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("The busiest chunks on this server right now")
    public void top(BukkitCommandActor actor) {
        List<Component> lines = service.busiest(8);
        if (lines.isEmpty()) {
            say(actor.sender(), "Nothing busy right now.");
            return;
        }
        say(actor.sender(), "Busiest places right now (as a share of each limit):");
        lines.forEach(actor.sender()::sendMessage);
    }

    @Command("lagwatch log")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("Recent lag machine reports (the whole history is in plugins/GensCore/lagwatch.log)")
    public void log(BukkitCommandActor actor) {
        List<LagWatchService.Incident> incidents = service.recentIncidents(10);
        if (incidents.isEmpty()) {
            say(actor.sender(), "Nothing reported since the server started.");
            return;
        }
        for (LagWatchService.Incident incident : incidents) {
            actor.sender().sendMessage(LEGACY.deserialize("&8" + TIME.format(Instant.ofEpochMilli(incident.at())) + " " + (incident.severe() ? "&c" : "&e")
                            + incident.check().label() + " &7" + incident.check().describe(incident.value()) + " &f" + incident.world() + " " + incident.x() + " "
                            + incident.y() + " " + incident.z() + " &8- &7" + incident.who())
                    .clickEvent(ClickEvent.runCommand("/lagwatch tp " + incident.id())));
        }
    }

    @Command("lagwatch limits")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("What counts as a lag machine (change under lagwatch.thresholds in config.yml)")
    public void limits(BukkitCommandActor actor) {
        StringBuilder rate = new StringBuilder();
        StringBuilder count = new StringBuilder();
        for (LagCheck check : LagCheck.values()) {
            int limit = service.limit(check);
            StringBuilder into = check.kind() == LagCheck.Kind.RATE ? rate : count;
            into.append(into.isEmpty() ? "" : "&7, ").append("&f").append(check.key()).append(" &7").append(limit <= 0 ? "off" : String.format("%,d", limit));
        }
        say(actor.sender(), "Per chunk in 10 seconds (3 of the last 6 windows): " + rate);
        say(actor.sender(), "Per chunk standing (two counts in a row): " + count);
    }

    @Command("lagwatch tp")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("Go to a reported place")
    public void tp(BukkitCommandActor actor, @Named("id") int id) {
        Player player = actor.requirePlayer();
        Location at = service.place(id);
        if (at == null) {
            say(player, "That place is not known any more (this server restarted, or it is too old). Use &f/lagwatch log&7.");
            return;
        }
        at.setYaw(player.getLocation().getYaw());
        at.setPitch(player.getLocation().getPitch());
        player.teleportAsync(at, PlayerTeleportEvent.TeleportCause.COMMAND).thenAccept(done -> {
            if (Boolean.TRUE.equals(done)) {
                Tasks.entity.run(player, () -> say(player, "Teleported to report &f#" + id + "&7. Use &f/co inspect&7 on the machine to see who built it."));
            }
        });
    }

    @Command("lagwatch alerts")
    @CommandPermission(LagWatchService.ALERT_PERMISSION)
    @Description("Mute or unmute your own lag machine reports")
    public void alerts(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        say(player, "Your lag machine reports are now " + state(service.toggleAlerts(player.getUniqueId())) + "&7.");
    }

    @Command("lagwatch reload")
    @CommandPermission(LagWatchService.ADMIN_PERMISSION)
    @Description("Re-read the lagwatch settings from config.yml")
    public void reload(BukkitCommandActor actor) {
        service.reloadSettings();
        say(actor.sender(), "Settings reloaded.");
    }

    @Command("lagwatch on")
    @CommandPermission(LagWatchService.ADMIN_PERMISSION)
    @Description("Turn the lag watch on")
    public void on(BukkitCommandActor actor) {
        service.setEnabled(true);
        say(actor.sender(), "The lag watch is now " + state(true) + "&7.");
    }

    @Command("lagwatch off")
    @CommandPermission(LagWatchService.ADMIN_PERMISSION)
    @Description("Turn the lag watch off")
    public void off(BukkitCommandActor actor) {
        service.setEnabled(false);
        say(actor.sender(), "The lag watch is now " + state(false) + "&7.");
    }
}
