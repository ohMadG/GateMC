package me.Evan52098.genscore.vouchers;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.logging.Level;
import java.util.logging.Logger;

/** /voucher, ported from BlockvilleCore's VoucherCommand (same subcommands and messages). */
public final class VoucherCommands {

    private static final String PERMISSION = VoucherService.STAFF_PERMISSION;

    private final VoucherService service;
    private final Logger logger;
    private final Cooldowns cooldowns = new Cooldowns();

    VoucherCommands(VoucherService service, Logger logger) {
        this.service = service;
        this.logger = logger;
    }

    private static boolean checkName(CommandSender sender, String name) {
        if (VoucherService.validName(name)) {
            return true;
        }
        sender.sendMessage(Msg.bad("Voucher names may only use letters, numbers, dashes and underscores (up to 32)."));
        return false;
    }

    @Command("voucher create")
    @CommandPermission(PERMISSION)
    @Description("Create a voucher type, using the item in your hand as its look")
    public void create(BukkitCommandActor actor, @Named("name") String name, @Named("command") String command) {
        Player player = actor.requirePlayer();
        if (checkName(player, name)) {
            service.createTemplate(player, name, command, player);
        }
    }

    @Command("voucher edit")
    @CommandPermission(PERMISSION)
    @Description("Re-capture a voucher's look from the item in your hand")
    public void edit(BukkitCommandActor actor, @Named("name") String name) {
        Player player = actor.requirePlayer();
        service.editAppearance(player, name, player);
    }

    @Command("voucher setcommand")
    @CommandPermission(PERMISSION)
    @Description("Change the command a voucher runs on redeem")
    public void setCommand(BukkitCommandActor actor, @Named("name") String name, @Named("command") String command) {
        service.setCommand(name, command, actor.sender().getName(), actor.sender());
    }

    @Command("voucher delete")
    @CommandPermission(PERMISSION)
    @Description("Delete a voucher type")
    public void delete(BukkitCommandActor actor, @Named("name") String name) {
        service.deleteTemplate(name, actor.sender().getName(), actor.sender());
    }

    @Command("voucher list")
    @CommandPermission(PERMISSION)
    @Description("List every voucher type")
    public void list(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        service.listTemplates().whenComplete((templates, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                logger.log(Level.WARNING, "Failed to list voucher templates", e);
                sender.sendMessage(Msg.bad("Something went wrong - check the console."));
                return;
            }
            if (templates.isEmpty()) {
                sender.sendMessage(Msg.neutral("No vouchers exist yet."));
                return;
            }
            sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize("&8&m--------&r &eVouchers &8&m--------"));
            for (VoucherService.Template template : templates) {
                sender.sendMessage(Msg.parse("<yellow>" + Msg.escape(template.name()) + "</yellow> <dark_gray>-</dark_gray> <gray>"
                        + Msg.escape(template.command()) + "</gray>"));
            }
        }));
    }

    @Command("voucher give")
    @CommandPermission(PERMISSION)
    @Description("Give a player one or more copies of a voucher")
    public void give(BukkitCommandActor actor, @Named("name") String name, @Named("player") String playerName,
                     @Named("amount") @Default("1") int amount) {
        Player target = Bukkit.getPlayerExact(playerName);
        if (target == null) {
            actor.sender().sendMessage(Msg.bad("No online player named <white>" + Msg.escape(playerName) + "</white>."));
            return;
        }
        service.give(actor.sender() instanceof Player p ? p : null, name, target, amount, actor.sender());
    }

    @Command("voucher check")
    @CommandPermission("genscore.voucher.check")
    @Description("Check whether the voucher you're holding has already been redeemed, without redeeming it")
    public void check(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        // Checklist 10: each check is a database read.
        long left = cooldowns.remaining(player.getUniqueId(), "check");
        if (left > 0) {
            player.sendMessage(Msg.bad("Wait " + Msg.white(Cooldowns.format(left)) + " before checking again."));
            return;
        }
        cooldowns.start(player.getUniqueId(), "check", 2000L);
        service.check(player);
    }

    @Command("voucher scan")
    @CommandPermission(PERMISSION)
    @Description("Force an immediate sweep for duplicate vouchers, instead of waiting for the periodic scan")
    public void scan(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        service.scanForDuplicates().whenComplete((found, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                logger.log(Level.WARNING, "Voucher duplicate scan failed", e);
                sender.sendMessage(Msg.bad("Something went wrong - check the console."));
                return;
            }
            sender.sendMessage(found == 0
                    ? Msg.good("Scan complete - no duplicates found.")
                    : Msg.bad("Scan complete - found <white>" + found + "</white> duplicate voucher(s)."));
        }));
    }
}
