package me.Evan52098.genscore.shards;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/** /shards, /shards &lt;player&gt;, /shards top, /shards give|take|set. */
public final class ShardsCommands {

    private static final Logger LOG = Logger.getLogger("GensCore");

    private final ShardsService shards;
    private final PlayerDataService players;
    private final Cooldowns cooldowns = new Cooldowns();

    ShardsCommands(ShardsService shards, PlayerDataService players) {
        this.shards = shards;
        this.players = players;
    }

    @Command("shards")
    @CommandPermission("genscore.shards")
    @Description("Check your shards")
    public void self(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        player.sendMessage(Msg.neutral("Your shards: " + Msg.white(fmt(shards.balance(player.getUniqueId())))));
    }

    @Command("shards")
    @CommandPermission("genscore.shards.others")
    @Description("Check another player's shards")
    public void other(BukkitCommandActor actor, @Named("player") String name) {
        CommandSender sender = actor.sender();
        if (!rateLimit(sender)) {
            return;
        }
        resolve(name).thenCompose(found -> found.isEmpty()
                ? CompletableFuture.completedFuture((Long) null)
                : shards.balanceAsync(found.get())).thenAccept(amount -> Tasks.forSender(sender, () -> sender.sendMessage(amount == null
                ? Msg.bad("No player named " + Msg.white(name) + " has joined.")
                : Msg.neutral(Msg.escape(name) + " has " + Msg.white(fmt(amount)) + " shards."))));
    }

    @Command("shards top")
    @CommandPermission("genscore.shards.top")
    @Description("The players with the most shards")
    public void top(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        shards.topAsync().thenAccept(list -> Tasks.forSender(sender, () -> {
            sender.sendMessage(Msg.parse("<aqua><bold>Top shards"));
            if (list.isEmpty()) {
                sender.sendMessage(Msg.parse("<gray>Nobody has shards yet."));
            }
            int rank = 1;
            for (ShardsService.TopEntry e : List.copyOf(list)) {
                sender.sendMessage(Msg.parse("<gray>" + rank++ + ". <white>" + Msg.escape(e.name()) + " <dark_gray>- <aqua>" + fmt(e.shards())));
            }
        }));
    }

    @Command({"shards give", "shards add"})
    @CommandPermission("genscore.shards.admin")
    @Description("Give a player shards")
    public void give(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        change(actor.sender(), name, amount, Op.GIVE);
    }

    @Command({"shards take", "shards remove"})
    @CommandPermission("genscore.shards.admin")
    @Description("Take shards from a player")
    public void take(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        change(actor.sender(), name, amount, Op.TAKE);
    }

    @Command("shards set")
    @CommandPermission("genscore.shards.admin")
    @Description("Set a player's shards")
    public void set(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        change(actor.sender(), name, amount, Op.SET);
    }

    private enum Op { GIVE, TAKE, SET }

    private void change(CommandSender sender, String name, String amountText, Op op) {
        long amount = parse(amountText, op == Op.SET);
        long cap = op == Op.SET ? shards.settings().maxBalance() : shards.settings().maxAdminAmount();
        if (amount < 0 || amount > cap) {
            sender.sendMessage(Msg.bad("Enter a whole number" + (op == Op.SET ? " from 0" : " from 1") + " to " + fmt(cap) + "."));
            return;
        }
        String by = sender.getName();
        resolve(name).thenAcceptAsync(found -> {
            if (found.isEmpty()) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.white(name) + " has joined.")));
                return;
            }
            UUID uuid = found.get();
            String reason = "admin " + op.name().toLowerCase() + " by " + by;
            // Offline changes query the database on this (async) thread, never on a game thread.
            boolean ok = switch (op) {
                case GIVE -> shards.deposit(uuid, amount, reason);
                case TAKE -> shards.withdraw(uuid, amount, reason);
                case SET -> shards.set(uuid, amount, reason);
            };
            long now = shards.balance(uuid);
            LOG.info("[shards] " + by + " " + op.name().toLowerCase() + " " + amount + " shards " + (op == Op.TAKE ? "from " : "for ")
                    + name + " (" + uuid + "): " + (ok ? "done, now " + now : "refused"));
            Tasks.forSender(sender, () -> sender.sendMessage(ok
                    ? Msg.good(switch (op) {
                        case GIVE -> "Gave " + Msg.white(fmt(amount)) + " shards to " + Msg.escape(name) + ".";
                        case TAKE -> "Took " + Msg.white(fmt(amount)) + " shards from " + Msg.escape(name) + ".";
                        case SET -> "Set " + Msg.escape(name) + "'s shards to " + Msg.white(fmt(amount)) + ".";
                    } + " They now have " + Msg.white(fmt(now)) + ".")
                    : Msg.bad(op == Op.TAKE ? Msg.escape(name) + " does not have " + Msg.white(fmt(amount)) + " shards."
                    : "That would put " + Msg.escape(name) + " over the shard limit.")));
            Player online = Bukkit.getPlayer(uuid);
            if (ok && online != null && op == Op.GIVE) {
                Tasks.entity.run(online, () -> online.sendMessage(Msg.good("You received " + Msg.white(fmt(amount)) + " shards.")));
            }
        });
    }

    private boolean rateLimit(CommandSender sender) {
        if (sender instanceof Player p && !cooldowns.tryStart(p.getUniqueId(), "shards-lookup", 2000)) {
            sender.sendMessage(Msg.bad("Slow down a little."));
            return false;
        }
        return true;
    }

    /** Whole positive numbers ("1500", "1,500"); 0 allowed only for set. -1 when invalid. */
    private static long parse(String text, boolean allowZero) {
        try {
            long v = Long.parseLong(text.trim().replace(",", "").replace("_", ""));
            return v > 0 || (allowZero && v == 0) ? v : -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private CompletableFuture<Optional<UUID>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(online.getUniqueId()));
        }
        return players.findUuid(name);
    }

    static String fmt(long n) {
        return String.format("%,d", n);
    }
}
