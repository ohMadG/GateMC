package me.Evan52098.genscore.sus;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Map;

/** /sus [player] | /sus clear &lt;player|all&gt; | /sus alerts, and /sustp (the [TP] button). */
public final class SusCommands {

    private final SusService sus;

    SusCommands(SusService sus) {
        this.sus = sus;
    }

    @Command({"sus", "suslist", "flagged"})
    @CommandPermission(SusService.PERM)
    @Description("Players flagged for possible cheats")
    public void sus(BukkitCommandActor actor, @Named("player") @Optional @Nullable String name) {
        CommandSender to = actor.sender();
        boolean tp = to.hasPermission(SusService.PERM_TP);
        if (name != null) {
            SusService.Record r = sus.find(name);
            if (r == null) {
                to.sendMessage(Msg.neutral(Msg.white(name) + " has no flags."));
                return;
            }
            Component head = Msg.parse(sus.settings().prefix() + "<white>" + Msg.escape(r.name) + "</white> <gray>("
                    + r.total() + " flags, last " + Cooldowns.format(System.currentTimeMillis() - r.lastAt()) + " ago)");
            to.sendMessage(tp ? head.append(Component.text(" ")).append(SusService.tpButton(r.name)) : head);
            Map<SusService.Check, String> details = r.details();
            r.counts().forEach((check, count) -> to.sendMessage(Msg.parse("<gray> - <red>" + check.label + "</red> x" + count
                    + " <dark_gray>(" + Msg.escape(details.getOrDefault(check, "")) + ")")));
            to.sendMessage(Msg.parse("<dark_gray>Flags are hints, never proof. Go and watch first."));
            return;
        }
        List<SusService.Record> list = sus.list();
        if (list.isEmpty()) {
            to.sendMessage(Msg.good("Nobody is flagged."));
            return;
        }
        to.sendMessage(Msg.parse(sus.settings().prefix() + "<gray>" + list.size() + " flagged player(s), most recent first:"));
        for (SusService.Record r : list.subList(0, Math.min(15, list.size()))) {
            StringBuilder checks = new StringBuilder();
            r.counts().forEach((check, count) -> checks.append(checks.isEmpty() ? "" : ", ").append(check.label).append(" x").append(count));
            Component line = Msg.parse("<gray> - <white>" + Msg.escape(r.name) + "</white> <dark_gray>" + Msg.escape(checks.toString()));
            to.sendMessage(tp ? line.append(Component.text(" ")).append(SusService.tpButton(r.name)) : line);
        }
    }

    @Command("sus clear")
    @CommandPermission(SusService.PERM)
    @Description("Clear a player's flags, or everyone's with 'all'")
    public void clear(BukkitCommandActor actor, @Named("player") String name) {
        if (name.equalsIgnoreCase("all")) {
            actor.sender().sendMessage(Msg.good("Cleared the flags of " + sus.clearAll() + " player(s)."));
            return;
        }
        actor.sender().sendMessage(sus.clear(name)
                ? Msg.good("Cleared the flags of " + Msg.white(name) + ".")
                : Msg.neutral(Msg.white(name) + " has no flags."));
    }

    @Command("sus alerts")
    @CommandPermission(SusService.PERM)
    @Description("Turn /sus alerts on or off for yourself")
    public void alerts(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        player.sendMessage(sus.toggleAlerts(player.getUniqueId())
                ? Msg.good("Sus alerts enabled.") : Msg.neutral("Sus alerts disabled."));
    }

    @Command("sustp")
    @CommandPermission(SusService.PERM_TP)
    @Description("Teleport to a flagged player")
    public void tp(BukkitCommandActor actor, @Named("player") Player target) {
        Player staff = actor.requirePlayer();
        if (staff.equals(target)) {
            return;
        }
        // Folia: read the target's location on their thread, then teleport the staff member on theirs.
        Tasks.entity.run(target, () -> {
            Location at = target.getLocation();
            Tasks.entity.run(staff, () -> staff.teleportAsync(at).thenAccept(ok -> {
                if (ok) {
                    staff.sendMessage(Msg.good("Teleported to " + Msg.white(target.getName()) + "."));
                }
            }));
        });
    }
}
