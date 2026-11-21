package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /balance, /pay, /baltop and /eco. Ported from BlockvilleCore's BalanceCommand, PayCommand, BalTopCommand and
 * OfflineEcoCommand, with the same messages, minus the network-only parts (bank loans, cross-server pay).
 */
public final class EconomyCommands {

    private static final long CONFIRM_WINDOW_MS = 30_000;

    private record PendingPay(UUID target, String targetName, long cents, long expiresAt) {
    }

    private final EconomyService economy;
    private final PlayerDataService players;
    private final Map<UUID, PendingPay> pendingPays = new ConcurrentHashMap<>();

    EconomyCommands(EconomyService economy, PlayerDataService players) {
        this.economy = economy;
        this.players = players;
    }

    // ------------------------------------------------------------------ /balance

    @Command({"balance", "bal", "money"})
    @CommandPermission("genscore.balance")
    @Description("Check your balance")
    public void balanceSelf(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        player.sendMessage(Msg.neutral("Your balance: " + Msg.white(Money.format(economy.balance(player.getUniqueId())))));
    }

    @Command({"balance", "bal", "money"})
    @CommandPermission("genscore.balance.others")
    @Description("Check another player's balance")
    public void balanceOther(BukkitCommandActor actor, @Named("player") String targetName) {
        CommandSender sender = actor.sender();
        resolve(targetName).thenCompose(found -> found.isEmpty()
                        ? CompletableFuture.completedFuture((Long) null)
                        : economy.balanceAsync(found.get().uuid()).thenApply(b -> b))
                .whenComplete((balance, error) -> Tasks.forSender(sender, () -> {
                    if (error != null || balance == null) {
                        sender.sendMessage(Msg.bad("Player not found."));
                        return;
                    }
                    sender.sendMessage(Msg.neutral(Msg.white(targetName) + "'s balance: " + Msg.white(Money.format(balance))));
                }));
    }

    // ------------------------------------------------------------------ /pay

    @Command("pay")
    @CommandPermission("genscore.pay")
    @Description("Send money to a player")
    public void pay(BukkitCommandActor actor, @Named("player") Player target, @Named("amount") String amountText) {
        Player sender = actor.requirePlayer();
        OptionalLong parsed = Money.parse(amountText);
        EconomySettings s = economy.settings();
        if (parsed.isEmpty() || parsed.getAsLong() < s.payMinCents()) {
            sender.sendMessage(Msg.bad("Amount must be at least " + Msg.white(Money.format(s.payMinCents())) + "."));
            return;
        }
        long cents = parsed.getAsLong();
        if (sender.getUniqueId().equals(target.getUniqueId())) {
            sender.sendMessage(Msg.bad("You cannot pay yourself."));
            return;
        }
        if (cents > s.payMaxCents()) {
            sender.sendMessage(Msg.bad("Amount cannot exceed " + Msg.white(Money.format(s.payMaxCents())) + "."));
            return;
        }
        Wallet own = economy.wallet(sender.getUniqueId());
        if (own != null && own.payConfirm() && cents >= s.payConfirmCents()) {
            pendingPays.put(sender.getUniqueId(), new PendingPay(target.getUniqueId(), target.getName(), cents,
                    System.currentTimeMillis() + CONFIRM_WINDOW_MS));
            sender.sendMessage(Msg.neutral("You are about to send " + Msg.white(Money.format(cents)) + " to "
                    + Msg.white(target.getName()) + ". <green><click:run_command:/pay confirm>/pay confirm</click></green>"
                    + " <gray>|</gray> <red><click:run_command:/pay cancel>/pay cancel</click></red>"));
            return;
        }
        completePay(sender, target.getUniqueId(), target.getName(), cents);
    }

    @Command("pay confirm")
    @CommandPermission("genscore.pay")
    public void payConfirm(BukkitCommandActor actor) {
        Player sender = actor.requirePlayer();
        PendingPay pending = pendingPays.remove(sender.getUniqueId());
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            sender.sendMessage(Msg.bad("You have no payment waiting to be confirmed."));
            return;
        }
        completePay(sender, pending.target(), pending.targetName(), pending.cents());
    }

    @Command("pay cancel")
    @CommandPermission("genscore.pay")
    public void payCancel(BukkitCommandActor actor) {
        Player sender = actor.requirePlayer();
        if (pendingPays.remove(sender.getUniqueId()) != null) {
            sender.sendMessage(Msg.good("Payment cancelled."));
        } else {
            sender.sendMessage(Msg.bad("You have no payment waiting to be confirmed."));
        }
    }

    @Command("pay toggle")
    @CommandPermission("genscore.pay.toggle")
    @Description("Turn receiving payments on or off")
    public void payToggle(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Wallet w = economy.wallet(player.getUniqueId());
        if (w == null) {
            return;
        }
        boolean now = !w.payEnabled();
        w.payEnabled(now);
        player.sendMessage(Msg.good(now ? "Payments enabled." : "Payments disabled."));
    }

    @Command("pay confirmtoggle")
    @CommandPermission("genscore.pay.confirm.toggle")
    @Description("Toggle payment confirmation for large amounts")
    public void payConfirmToggle(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Wallet w = economy.wallet(player.getUniqueId());
        if (w == null) {
            return;
        }
        boolean now = !w.payConfirm();
        w.payConfirm(now);
        player.sendMessage(Msg.good(now ? "Payment confirmation enabled." : "Payment confirmation disabled."));
    }

    private void completePay(Player sender, UUID targetId, String targetName, long cents) {
        String formatted = Money.format(cents);
        switch (economy.pay(sender.getUniqueId(), targetId, cents)) {
            case SUCCESS -> {
                sender.sendMessage(Msg.good("Sent " + Msg.white(formatted) + " to " + Msg.white(targetName) + "."));
                Player target = Bukkit.getPlayer(targetId);
                if (target != null) {
                    Tasks.onEntity(target, () -> target.sendMessage(
                            Msg.good("You received " + Msg.white(formatted) + " from " + Msg.white(sender.getName()) + ".")));
                }
            }
            case SELF -> sender.sendMessage(Msg.bad("You cannot pay yourself."));
            case DISABLED -> sender.sendMessage(Msg.bad("That player has payments disabled or is no longer online."));
            case NOT_ENOUGH -> sender.sendMessage(Msg.bad("You don't have enough money."));
            case TOO_LOW -> sender.sendMessage(Msg.bad("Amount must be at least " + Msg.white(Money.format(economy.settings().payMinCents())) + "."));
            case TOO_HIGH -> sender.sendMessage(Msg.bad("Amount cannot exceed " + Msg.white(Money.format(economy.settings().payMaxCents())) + "."));
        }
    }

    // ------------------------------------------------------------------ /baltop

    @Command({"baltop", "balancetop", "moneytop"})
    @CommandPermission("genscore.baltop")
    @Description("The richest players")
    public void baltop(BukkitCommandActor actor, @Named("page") @Default("1") int page) {
        if (actor.sender() instanceof org.bukkit.entity.Player p && page == 1 && me.Evan52098.genscore.hub.HubModule.openBaltop(p)) {
            return;
        }
        CommandSender sender = actor.sender();
        economy.topAsync().whenComplete((list, error) -> Tasks.forSender(sender, () -> {
            if (error != null || list == null) {
                sender.sendMessage(Msg.bad("The top list could not be loaded right now."));
                return;
            }
            int per = economy.settings().topPerPage();
            int pages = Math.max(1, (list.size() + per - 1) / per);
            int p = Math.min(Math.max(1, page), pages);
            sender.sendMessage(Msg.parse("<gold><bold>Richest players</bold> <gray>(page " + p + "/" + pages + ")"));
            List<EconomyService.TopEntry> slice = list.subList(Math.min(list.size(), (p - 1) * per), Math.min(list.size(), p * per));
            int rank = (p - 1) * per;
            for (EconomyService.TopEntry e : slice) {
                rank++;
                sender.sendMessage(Msg.parse("<gray>" + rank + ". <white>" + Msg.escape(e.name()) + " <gray>- <green>" + Money.format(e.cents())));
            }
            if (list.isEmpty()) {
                sender.sendMessage(Msg.parse("<gray>Nobody has any money yet."));
            }
        }));
    }

    // ------------------------------------------------------------------ /eco (admin, works for offline players)

    @Command({"eco give", "eco add"})
    @CommandPermission("genscore.admin.eco")
    @Description("Give money to a player (online or offline)")
    public void ecoGive(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        adminChange(actor.sender(), name, amount, (target, cents) -> economy.deposit(target, cents, "admin:" + actor.name()),
                (target, cents) -> "Gave " + Msg.white(Money.format(cents)) + " to " + Msg.white(target) + ".");
    }

    @Command({"eco take", "eco remove"})
    @CommandPermission("genscore.admin.eco")
    @Description("Take money from a player (online or offline)")
    public void ecoTake(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        adminChange(actor.sender(), name, amount, (target, cents) -> economy.withdraw(target, cents, "admin:" + actor.name()),
                (target, cents) -> "Took " + Msg.white(Money.format(cents)) + " from " + Msg.white(target) + ".");
    }

    @Command("eco set")
    @CommandPermission("genscore.admin.eco")
    @Description("Set a player's balance (online or offline)")
    public void ecoSet(BukkitCommandActor actor, @Named("player") String name, @Named("amount") String amount) {
        CommandSender sender = actor.sender();
        if ("0".equals(amount.trim())) {
            runAdmin(sender, name, 0, (target, cents) -> economy.set(target, 0, "admin:" + actor.name()),
                    (target, cents) -> "Set " + Msg.white(target) + "'s balance to " + Msg.white(Money.format(0)) + ".");
            return;
        }
        adminChange(sender, name, amount, (target, cents) -> economy.set(target, cents, "admin:" + actor.name()),
                (target, cents) -> "Set " + Msg.white(target) + "'s balance to " + Msg.white(Money.format(cents)) + ".");
    }

    private interface Change {
        boolean apply(UUID target, long cents);
    }

    private interface Done {
        String message(String targetName, long cents);
    }

    private void adminChange(CommandSender sender, String name, String amountText, Change change, Done done) {
        OptionalLong parsed = Money.parse(amountText);
        if (parsed.isEmpty()) {
            sender.sendMessage(Msg.bad("That is not a valid amount."));
            return;
        }
        runAdmin(sender, name, parsed.getAsLong(), change, done);
    }

    private void runAdmin(CommandSender sender, String name, long cents, Change change, Done done) {
        resolve(name).whenComplete((found, error) -> {
            if (error != null || found.isEmpty()) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("Player not found.")));
                return;
            }
            Target target = found.get();
            // Offline changes touch the database, so they run off the game threads.
            Tasks.async.run(() -> {
                boolean ok = change.apply(target.uuid(), cents);
                Tasks.forSender(sender, () -> sender.sendMessage(ok
                        ? Msg.good(done.message(target.name(), cents))
                        : Msg.bad("That did not work (not enough money, or a database error; see the console).")));
            });
        });
    }

    // ------------------------------------------------------------------ helpers

    private record Target(UUID uuid, String name) {
    }

    /** Online players first, then anyone who has ever joined. */
    private CompletableFuture<Optional<Target>> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(Optional.of(new Target(online.getUniqueId(), online.getName())));
        }
        return players.findUuid(name).thenApply(id -> id.map(uuid -> new Target(uuid, name)));
    }
}
