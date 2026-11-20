package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
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

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/** /lockbox and /lockboxadmin, ported from BlockvilleCore's LockboxCommand and LockboxAdminCommand (same messages). */
public final class LockboxCommands {

    private final GensCore plugin;
    private final CratesModule module;
    private final LockboxService service;
    private final LockboxGui gui;

    LockboxCommands(GensCore plugin, CratesModule module, LockboxService service, LockboxGui gui) {
        this.plugin = plugin;
        this.module = module;
        this.service = service;
        this.gui = gui;
    }

    @Command("lockbox")
    @CommandPermission("genscore.lockbox.play")
    @Description("Open today's Daily Lockbox")
    public void open(BukkitCommandActor actor) {
        gui.open(actor.requirePlayer());
    }

    @Command("lockbox help")
    @CommandPermission("genscore.lockbox.play")
    @Description("Explain the Daily Lockbox rules")
    public void help(BukkitCommandActor actor) {
        gui.sendFullRules(actor.requirePlayer());
    }

    @Command("lockboxadmin inspect")
    @CommandPermission("genscore.lockbox.admin.inspect")
    @Description("View a player's lockbox progress and reward status, without the answer")
    public void inspect(BukkitCommandActor actor, @Named("player") String playerName, @Named("day") @Optional @Nullable String day) {
        CommandSender sender = actor.sender();
        String period = day == null ? "" : day;
        resolveUuid(playerName).thenCompose(uuid -> {
            if (uuid == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.escape(playerName) + " found.")));
                return CompletableFuture.<Void>completedFuture(null);
            }
            CompletableFuture<LockboxGame> lookup = period.isEmpty() ? service.fetchLatestGame(uuid) : service.inspect(uuid, period);
            return lookup.thenAccept(game -> Tasks.forSender(sender, () -> {
                if (game == null) {
                    sender.sendMessage(Msg.neutral(Msg.escape(playerName) + " has no lockbox" + (period.isEmpty() ? "" : " for " + Msg.escape(period)) + "."));
                    return;
                }
                boolean owed = LockboxGame.REWARD_GRANTED.equals(game.rewardStatus()) || LockboxGame.REWARD_PENDING.equals(game.rewardStatus());
                sender.sendMessage(LockboxGui.legacy("&8&m--------&r &eLockbox: " + playerName + " (" + game.dailyPeriod() + ") &8&m--------"));
                sender.sendMessage(LockboxGui.legacy("&7Status: &f" + game.status()));
                sender.sendMessage(LockboxGui.legacy("&7Attempts used: &f" + game.attemptsUsed()));
                sender.sendMessage(LockboxGui.legacy("&7Reward status: &f" + game.rewardStatus() + (owed ? " (&f" + game.reward().display() + "&7)" : "")));
                sender.sendMessage(LockboxGui.legacy("&7Rules version: &f" + game.rulesVersion()));
            }));
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "lockboxadmin inspect failed for " + playerName, ex);
            return null;
        });
    }

    @Command("lockboxadmin reload")
    @CommandPermission("genscore.lockbox.admin.reload")
    @Description("Reload Lockbox configuration")
    public void reload(BukkitCommandActor actor) {
        plugin.reloadConfig();
        module.reload();
        actor.sender().sendMessage(Msg.good("Lockbox configuration reloaded."));
    }

    @Command("lockboxadmin reconcile")
    @CommandPermission("genscore.lockbox.admin.reconcile")
    @Description("Check and safely resolve a pending lockbox reward")
    public void reconcile(BukkitCommandActor actor, @Named("player") String playerName, @Named("day") @Optional @Nullable String day) {
        CommandSender sender = actor.sender();
        boolean latest = day == null || day.isEmpty();
        String targetDay = latest ? "their latest game" : day;
        resolveUuid(playerName).thenCompose(uuid -> {
            if (uuid == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.escape(playerName) + " found.")));
                return CompletableFuture.<Void>completedFuture(null);
            }
            CompletableFuture<String> period = latest
                    ? service.fetchLatestGame(uuid).thenApply(game -> game == null ? null : game.dailyPeriod())
                    : CompletableFuture.completedFuture(day);
            return period.thenCompose(p -> p == null ? CompletableFuture.completedFuture(false) : service.reconcile(uuid, p))
                    .thenAccept(delivered -> Tasks.forSender(sender, () -> sender.sendMessage(delivered
                            ? Msg.good("Reconciled: delivered " + Msg.escape(playerName) + "'s pending lockbox reward for " + Msg.escape(targetDay) + ".")
                            : Msg.neutral("Nothing to reconcile for " + Msg.escape(playerName) + " on " + Msg.escape(targetDay) + " (no pending reward)."))));
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "lockboxadmin reconcile failed for " + playerName, ex);
            return null;
        });
    }

    private CompletableFuture<UUID> resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return plugin.playerData().findUuid(name).thenApply(found -> found.orElse(null));
    }
}
