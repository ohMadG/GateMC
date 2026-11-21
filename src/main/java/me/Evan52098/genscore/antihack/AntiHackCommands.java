package me.Evan52098.genscore.antihack;

import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/** BlockvilleCore's AntiHackCommand: /antihack [check|log|alerts|reload|on|off|strict|setback|kick]. */
public final class AntiHackCommands {

    private static final String PREFIX = "&6&lANTIHACK &8» &7";
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final AntiHackService service;

    AntiHackCommands(AntiHackService service) {
        this.service = service;
    }

    private static void say(CommandSender to, String message) {
        to.sendMessage(LEGACY.deserialize(PREFIX + message));
    }

    private static String state(boolean on) {
        return on ? "&aon" : "&coff";
    }

    @Command("antihack")
    @CommandPermission(AntiHackService.ALERT_PERMISSION)
    @Description("Anti-hack status")
    public void status(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        say(sender, "Detection " + state(service.isEnabled()) + "&7, strict " + state(service.isStrict()) + "&7, setback " + state(service.isSetback())
                + "&7, kick " + state(service.isKick()) + "&7. Alerts from &f" + service.alertLevel().label() + "&7 up.");
        say(sender, "&f/antihack check <player>&7, &f/antihack log [player]&7, &f/antihack alerts&7 (mute or unmute your own alerts)");
        if (sender.hasPermission(AntiHackService.ADMIN_PERMISSION)) {
            say(sender, "&f/antihack on|off&7, &f/antihack strict|setback|kick on|off&7, &f/antihack reload");
        }
    }

    @Command("antihack check")
    @CommandPermission(AntiHackService.ALERT_PERMISSION)
    @Description("What a player's anti-hack record looks like right now")
    public void check(BukkitCommandActor actor, @Named("player") String name) {
        CommandSender sender = actor.sender();
        Player player = Bukkit.getPlayerExact(name);
        if (player == null) {
            say(sender, "&f" + name + " &7is not on this server.");
            return;
        }
        // The record lives on the target's thread: read it there, answer on the sender's thread (Folia rule 3).
        Tasks.onEntity(player, () -> {
            String description = service.describe(player);
            String playerName = player.getName();
            Tasks.forSender(sender, () -> say(sender, "&f" + playerName + "&7: " + description));
        });
    }

    @Command("antihack log")
    @CommandPermission(AntiHackService.ALERT_PERMISSION)
    @Description("Recent anti-hack alerts (the whole history is in plugins/GensCore/antihack.log)")
    public void log(BukkitCommandActor actor, @Optional @Named("player") @Nullable String name) {
        List<AntiHackService.Incident> incidents = service.recentIncidents(name, 10);
        if (incidents.isEmpty()) {
            say(actor.sender(), name == null ? "Nothing has been flagged since the server started." : "Nothing flagged for &f" + name + "&7 since the server started.");
            return;
        }
        for (AntiHackService.Incident incident : incidents) {
            actor.sender().sendMessage(LEGACY.deserialize("&8" + TIME.format(Instant.ofEpochMilli(incident.at())) + " &f" + incident.player() + " "
                    + incident.level().colour() + incident.level().label() + " &8(&7" + incident.summary() + "&8) &7" + incident.ping() + "ms"));
        }
    }

    @Command("antihack alerts")
    @CommandPermission(AntiHackService.ALERT_PERMISSION)
    @Description("Mute or unmute your own anti-hack alerts")
    public void alerts(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        say(player, "Your anti-hack alerts are now " + state(service.toggleAlerts(player.getUniqueId())) + "&7.");
    }

    @Command("antihack reload")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Re-read the antihack settings from config.yml")
    public void reload(BukkitCommandActor actor) {
        service.reloadSettings();
        say(actor.sender(), "Settings reloaded.");
    }

    @Command("antihack on")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Turn detection on")
    public void on(BukkitCommandActor actor) {
        service.setFlag("enabled", true);
        say(actor.sender(), "Detection is now " + state(true) + "&7.");
    }

    @Command("antihack off")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Turn detection off")
    public void off(BukkitCommandActor actor) {
        service.setFlag("enabled", false);
        say(actor.sender(), "Detection is now " + state(false) + "&7.");
    }

    @Command("antihack strict")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Strict mode: flag on less evidence (more false alarms)")
    public void strict(BukkitCommandActor actor, @Named("on|off") String value) {
        set(actor, "strict", value, "Strict mode");
    }

    @Command("antihack setback")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Put flagged players back where they last stood properly, and cancel flagged hits")
    public void setback(BukkitCommandActor actor, @Named("on|off") String value) {
        set(actor, "setback", value, "Setback");
    }

    @Command("antihack kick")
    @CommandPermission(AntiHackService.ADMIN_PERMISSION)
    @Description("Kick players who reach Extreme while no staff are online to look")
    public void kick(BukkitCommandActor actor, @Named("on|off") String value) {
        set(actor, "kick", value, "Kicking");
    }

    private void set(BukkitCommandActor actor, String key, String value, String label) {
        String word = value.toLowerCase(Locale.ROOT);
        if (!word.equals("on") && !word.equals("off")) {
            say(actor.sender(), "Say &fon&7 or &foff&7.");
            return;
        }
        service.setFlag(key, word.equals("on"));
        say(actor.sender(), label + " is now " + state(word.equals("on")) + "&7.");
    }
}
