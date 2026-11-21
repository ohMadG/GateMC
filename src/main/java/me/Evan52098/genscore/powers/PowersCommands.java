package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

public final class PowersCommands {

    private static final Logger LOG = Logger.getLogger("GensCore");

    private final PowersModule m;

    PowersCommands(PowersModule m) {
        this.m = m;
    }

    @Command({"powers", "power", "superpowers"})
    @CommandPermission(PowersModule.USE)
    @Description("Buy and switch super powers")
    public void open(BukkitCommandActor actor) {
        m.menu().open(actor.requirePlayer(), false);
    }

    @Command({"powers op", "power op", "superpowers op", "powers super", "power super"})
    @CommandPermission(PowersModule.USE)
    @Description("The paid super powers")
    public void openOp(BukkitCommandActor actor) {
        m.menu().open(actor.requirePlayer(), true);
    }

    @Command({"powers off", "power off", "superpowers off"})
    @CommandPermission(PowersModule.USE)
    @Description("Turn your active power off")
    public void off(BukkitCommandActor actor) {
        m.service().turnOff(actor.requirePlayer());
    }

    @Command({"powers grant", "powers give"})
    @CommandPermission(PowersModule.ADMIN)
    @Description("Give a player a power (super powers too)")
    public void grant(BukkitCommandActor actor, @Named("player") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String who, @Named("power") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.powers.PowersCommands.Powers.class) String power) {
        CommandSender sender = actor.sender();
        PowerDef def = m.settings().power(power);
        if (def == null) {
            sender.sendMessage(Msg.bad("Unknown power " + Msg.white(power) + ". Powers: " + Msg.escape(String.join(", ", m.settings().powers().keySet()))));
            return;
        }
        resolve(who).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture((Boolean) null)
                : m.service().grant(found.get(), def.id(), "command by " + sender.getName())).thenAccept(ok -> Tasks.forSender(sender, () -> {
            if (ok == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(who) + " has joined."));
            } else if (ok) {
                sender.sendMessage(Msg.good("Gave " + Msg.escape(Text.plain(def.name())) + " to " + Msg.white(who) + "."));
            } else {
                sender.sendMessage(Msg.bad("Could not save the grant; see the console."));
            }
        }));
    }

    @Command({"powers revoke", "powers take"})
    @CommandPermission(PowersModule.ADMIN)
    @Description("Take a power from a player")
    public void revoke(BukkitCommandActor actor, @Named("player") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String who, @Named("power") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.powers.PowersCommands.Powers.class) String power) {
        CommandSender sender = actor.sender();
        String id = power.toLowerCase(java.util.Locale.ROOT);
        resolve(who).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture((Boolean) null)
                : m.service().revoke(found.get(), id)).whenComplete((had, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                LOG.warning("[powers] Revoke failed: " + e);
                sender.sendMessage(Msg.bad("Could not revoke; see the console."));
            } else if (had == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(who) + " has joined."));
            } else {
                sender.sendMessage(had ? Msg.good("Took " + Msg.white(id) + " from " + Msg.white(who) + ".")
                        : Msg.neutral(Msg.white(who) + " did not have " + Msg.white(id) + "."));
            }
        }));
    }

    @Command("powers list")
    @CommandPermission(PowersModule.ADMIN)
    @Description("List power ids, or a player's powers")
    public void list(BukkitCommandActor actor) {
        StringBuilder sb = new StringBuilder();
        for (PowerDef def : m.settings().powers().values()) {
            sb.append(sb.isEmpty() ? "" : ", ").append(def.id()).append(def.op() ? " (op)" : "").append(def.enabled() ? "" : " (off)");
        }
        actor.sender().sendMessage(Msg.neutral("Powers: " + Msg.escape(sb.toString())));
    }

    @Command("powers check")
    @CommandPermission(PowersModule.ADMIN)
    @Description("Show an online player's powers")
    public void check(BukkitCommandActor actor, @Named("player") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String who) {
        Player target = Bukkit.getPlayerExact(who);
        PowerData d = target == null ? null : m.data(target.getUniqueId());
        if (d == null) {
            actor.sender().sendMessage(Msg.bad(Msg.white(who) + " is not online."));
            return;
        }
        actor.sender().sendMessage(Msg.neutral(Msg.escape(target.getName()) + ": active " + Msg.white(String.valueOf(d.active()))
                + ", owned " + Msg.white(String.join(", ", d.owned())) + (d.freeUsed() ? ", free power used" : "")));
    }

    @Command("powers reload")
    @CommandPermission(PowersModule.ADMIN)
    @Description("Reload powers.yml")
    public void reload(BukkitCommandActor actor) {
        m.reload();
        actor.sender().sendMessage(Msg.good("Reloaded powers.yml (" + m.settings().powers().size() + " powers)."));
    }

    private CompletableFuture<Optional<UUID>> resolve(String who) {
        try {
            return CompletableFuture.completedFuture(Optional.of(UUID.fromString(who)));
        } catch (IllegalArgumentException notUuid) {
            Player online = Bukkit.getPlayerExact(who);
            if (online != null) {
                return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
            }
            return m.players().findUuid(who);
        }
    }

    public static final class Powers implements revxrsal.commands.autocomplete.SuggestionProvider<revxrsal.commands.bukkit.actor.BukkitCommandActor> {
        @Override
        public java.util.Collection<String> getSuggestions(revxrsal.commands.node.ExecutionContext<revxrsal.commands.bukkit.actor.BukkitCommandActor> context) {
            PowersModule m = me.Evan52098.genscore.command.Suggest.module(PowersModule.class);
            return m == null ? java.util.List.of() : new java.util.ArrayList<>(m.settings().powers().keySet());
        }
    }
}
