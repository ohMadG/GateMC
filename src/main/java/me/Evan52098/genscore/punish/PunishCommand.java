package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.trade.OnlinePlayerSuggestions;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.CommandPriority;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class PunishCommand {
    public static final String PERMISSION_GUI = "genscore.punish.gui";
    public static final String PERMISSION_LOOKUP = "genscore.punish";
    public static final String PERMISSION_APPROVE = "genscore.punish.approve";
    public static final String PERMISSION_ADMIN = "genscore.punish.admin";
    public static final String PERMISSION_ACTION_WARN = "genscore.punish.action.warn";
    public static final String PERMISSION_ACTION_MUTE = "genscore.punish.action.mute";
    public static final String PERMISSION_ACTION_KICK = "genscore.punish.action.kick";
    public static final String PERMISSION_ACTION_BAN = "genscore.punish.action.ban";
    public static final String PERMISSION_ACTION_UNBAN = "genscore.punish.action.unban";
    public static final String PERMISSION_ACTION_BLACKLIST = "genscore.punish.action.blacklist";

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm").withZone(ZoneOffset.UTC);
    private static final int PAGE_SIZE = 10;

    private static final Map<String, String> RESERVED = Map.ofEntries(
            Map.entry("log", PERMISSION_GUI), Map.entry("staff", PERMISSION_GUI), Map.entry("search", PERMISSION_GUI), Map.entry("bans", PERMISSION_GUI),
            Map.entry("altexempt", PERMISSION_ADMIN), Map.entry("altunexempt", PERMISSION_ADMIN), Map.entry("altexemptlist", PERMISSION_ADMIN),
            Map.entry("lockouts", PERMISSION_ADMIN), Map.entry("clearlockout", PERMISSION_ADMIN));

    private final Warden warden;
    private final PunishmentService punishmentService;
    private final StaffLockoutService lockoutService;
    private final PlayerDataService playerData;
    private final PunishGui gui;
    public PunishCommand(Warden warden, PlayerDataService playerData, ChatInput chatInput, NoteService noteService) {
        this.warden = warden;
        this.punishmentService = warden.punishments();
        this.lockoutService = warden.lockouts();
        this.playerData = playerData;
        this.gui = new PunishGui(warden, chatInput, PERMISSION_ADMIN, PERMISSION_APPROVE, noteService);
    }

    private CompletableFuture<Resolved> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(new Resolved(online.getUniqueId(), online.getName()));
        }
        return playerData.lookup(name).thenApply(found -> found.map(r -> new Resolved(r.uuid(), r.name())).orElse(null));
    }

    private record Resolved(UUID uuid, String name) {
    }

    @Command({"punish", "p"})
    @CommandPermission(PERMISSION_GUI)
    @Description("List the punishment commands")
    public void help(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        sender.sendMessage(Msg.neutral("Punishment commands:"));
        sender.sendMessage(Msg.parse("<gold>/punish <player> [reason] <dark_gray>- <gray>open the punishment menu"));
        sender.sendMessage(Msg.parse("<gold>/punish log [page] <dark_gray>- <gray>recent punishments"));
        sender.sendMessage(Msg.parse("<gold>/punish bans [page] <dark_gray>- <gray>active bans, newest first"));
        sender.sendMessage(Msg.parse("<gold>/punish staff <name> [page] <dark_gray>- <gray>punishments issued by a staff member"));
        sender.sendMessage(Msg.parse("<gold>/punish search <text> [page] <dark_gray>- <gray>search punishment reasons"));
        sender.sendMessage(Msg.parse("<gold>/punish altexempt|altunexempt|altexemptlist <dark_gray>- <gray>alt-ban-evasion exemptions"));
        if (sender.hasPermission(PERMISSION_ADMIN)) {
            sender.sendMessage(Msg.parse("<gold>/punish lockouts|clearlockout <staff> <dark_gray>- <gray>staff punishment lockouts"));
        }
        sender.sendMessage(Msg.parse("<gray>/p works as a shorthand for /punish everywhere above."));
    }

    @Command({"punish", "p"})
    @CommandPermission(PERMISSION_GUI)
    @Description("Open the tiered punishment menu for a player")
    @CommandPriority.Low
    public void punish(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName, @Named("reason") @Optional String reason) {
        CommandSender sender = actor.sender();
        String required = RESERVED.get(targetName.toLowerCase(Locale.ROOT));
        if (required != null && !sender.hasPermission(required)) {
            sender.sendMessage(Msg.bad("You don't have permission to use /punish " + targetName.toLowerCase(Locale.ROOT) + "."));
            return;
        }
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(Msg.bad("This command can only be used by a player."));
            return;
        }
        resolve(targetName).thenAccept(found -> Tasks.onEntity(staff, () -> {
            if (found == null) {
                staff.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined."));
                return;
            }
            if (Bukkit.getOfflinePlayer(found.uuid()).isOp()) {
                staff.sendMessage(Msg.bad(Msg.white(found.name()) + " is an operator and cannot be punished."));
                return;
            }
            gui.open(staff, found.uuid(), found.name(), reason == null || reason.isBlank() ? "No reason given" : reason);
        }));
    }

    @Command("warn")
    @CommandPermission(PERMISSION_GUI)
    @Description("Warn a player without opening the punish menu")
    public void warn(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName, @Named("reason") @Optional String reason) {
        CommandSender sender = actor.sender();
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(Msg.bad("This command can only be used by a player."));
            return;
        }
        resolve(targetName).thenAccept(found -> Tasks.onEntity(staff, () -> {
            if (found == null) {
                staff.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined."));
                return;
            }
            String finalReason = reason == null || reason.isBlank() ? "No reason given" : reason;
            warden.applyWarn(found.uuid(), found.name(), staff, finalReason).thenAccept(result -> Tasks.onEntity(staff, () -> {
                if (PunishGui.isRecentWarnConflict(result)) {
                    staff.sendMessage(Msg.bad(Msg.white(found.name()) + " was already warned recently - you must escalate the punishment instead. Opening the punish menu..."));
                    Tasks.entity.later(staff, () -> gui.open(staff, found.uuid(), found.name(), finalReason), 60L);
                    return;
                }
                PunishGui.report(staff, found.name(), result);
            }));
        }));
    }

    @Command({"punish log", "p log"})
    @CommandPermission(PERMISSION_GUI)
    @Description("Show the most recent punishments")
    public void log(BukkitCommandActor actor, @Named("page") @Optional Integer page) {
        CommandSender sender = actor.sender();
        int pageNumber = page(page);
        punishmentService.recentGlobal(pageNumber * PAGE_SIZE).thenAccept(rows -> Tasks.forSender(sender, () -> listPage(sender, rows, pageNumber, "Punishment Log", "/punish log")));
    }

    @Command({"punish staff", "p staff"})
    @CommandPermission(PERMISSION_GUI)
    @Description("Show the punishments a staff member has issued")
    public void staff(BukkitCommandActor actor, @Named("staff") @SuggestWith(OnlinePlayerSuggestions.class) String staffName, @Named("page") @Optional Integer page) {
        CommandSender sender = actor.sender();
        resolve(staffName).thenAccept(found -> Tasks.forSender(sender, () -> {
            if (found == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(staffName) + " has ever joined."));
                return;
            }
            int pageNumber = page(page);
            punishmentService.byStaff(found.uuid(), pageNumber * PAGE_SIZE)
                    .thenAccept(rows -> Tasks.forSender(sender, () -> listPage(sender, rows, pageNumber, "Punishments by " + found.name(), "/punish staff " + found.name())));
        }));
    }

    @Command({"punish search", "p search"})
    @CommandPermission(PERMISSION_GUI)
    @Description("Search punishment reasons")
    public void search(BukkitCommandActor actor, @Named("text") String text, @Named("page") @Optional Integer page) {
        CommandSender sender = actor.sender();
        int pageNumber = page(page);
        punishmentService.searchByReason(text, pageNumber * PAGE_SIZE)
                .thenAccept(rows -> Tasks.forSender(sender, () -> listPage(sender, rows, pageNumber, "Search: \"" + Msg.escape(text) + "\"", "/punish search " + Msg.escape(text))));
    }

    private static int page(Integer page) {
        return page == null ? 1 : Math.max(1, Math.min(200, page));
    }

    private void listPage(CommandSender sender, List<PunishmentData> rows, int pageNumber, String title, String nextCommand) {
        if (rows.isEmpty()) {
            sender.sendMessage(Msg.neutral("No punishments found."));
            return;
        }
        int start = (pageNumber - 1) * PAGE_SIZE;
        if (start >= rows.size()) {
            sender.sendMessage(Msg.bad("Page " + pageNumber + " is empty."));
            return;
        }
        int end = Math.min(start + PAGE_SIZE, rows.size());
        sender.sendMessage(Msg.neutral(title + " (page " + pageNumber + "):"));
        for (PunishmentData row : rows.subList(start, end)) {
            String status = row.isInstantType() ? "logged" : row.active() ? (row.expiresAt() == null || row.expiresAt() > System.currentTimeMillis() ? "active" : "expired") : "revoked";
            sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + row.type() + "</yellow> on <yellow>" + Msg.escape(row.targetName()) + "</yellow> by <yellow>"
                    + Msg.escape(row.staffName()) + "</yellow> at " + DATE_FORMAT.format(Instant.ofEpochMilli(row.createdAt())) + " [" + status + "] <yellow>"
                    + Msg.escape(row.reason()) + "</yellow> <dark_gray>(" + row.displayId() + ")"));
        }
        sender.sendMessage(Msg.neutral("Run " + nextCommand + " " + (pageNumber + 1) + " for the next page."));
    }

    @Command({"punish bans", "p bans"})
    @CommandPermission(PERMISSION_GUI)
    @Description("List active bans, newest first, with a click-to-unban button")
    public void bans(BukkitCommandActor actor, @Named("page") @Optional Integer page) {
        CommandSender sender = actor.sender();
        int pageNumber = page(page);
        punishmentService.activeBans(pageNumber * PAGE_SIZE).thenAccept(rows -> Tasks.forSender(sender, () -> listBansPage(sender, rows, pageNumber)));
    }

    private void listBansPage(CommandSender sender, List<PunishmentData> rows, int pageNumber) {
        if (rows.isEmpty()) {
            sender.sendMessage(Msg.neutral("No active bans."));
            return;
        }
        int start = (pageNumber - 1) * PAGE_SIZE;
        if (start >= rows.size()) {
            sender.sendMessage(Msg.bad("Page " + pageNumber + " is empty."));
            return;
        }
        int end = Math.min(start + PAGE_SIZE, rows.size());
        boolean canUnban = sender.hasPermission(PERMISSION_ACTION_UNBAN);
        sender.sendMessage(Msg.neutral("Active Bans (page " + pageNumber + "):"));
        for (PunishmentData row : rows.subList(start, end)) {
            String expires = row.expiresAt() == null ? "Permanent" : DATE_FORMAT.format(Instant.ofEpochMilli(row.expiresAt()));
            String button = canUnban ? " <click:run_command:'/punishment " + row.displayId()
                    + "'><hover:show_text:'<gray>Click to unban " + Msg.escape(row.targetName()) + "'><red><bold>[Unban]</bold></red></hover></click>" : "";
            sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + Msg.escape(row.targetName()) + "</yellow> <dark_gray>- <gray>banned by <yellow>"
                    + Msg.escape(row.staffName()) + "</yellow> at " + DATE_FORMAT.format(Instant.ofEpochMilli(row.createdAt())) + ", expires <yellow>" + expires
                    + "</yellow> <dark_gray>- <gray>" + Msg.escape(row.reason()) + "</gray>" + button));
        }
        sender.sendMessage(Msg.neutral("Run /punish bans " + (pageNumber + 1) + " for the next page."));
    }

    @Command({"punish altexempt", "p altexempt"})
    @CommandPermission(PERMISSION_ADMIN)
    @Description("Exempt a player from alt-ban-evasion auto-bans (e.g. family sharing an IP)")
    public void altExempt(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName, @Named("reason") @Optional String reason) {
        CommandSender sender = actor.sender();
        if (!(sender instanceof Player staff)) {
            sender.sendMessage(Msg.bad("This command can only be used by a player."));
            return;
        }
        String finalReason = reason == null || reason.isBlank() ? "No reason given" : reason;
        resolve(targetName).thenAccept(found -> Tasks.onEntity(staff, () -> {
            if (found == null) {
                staff.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined."));
                return;
            }
            punishmentService.addAltBanExemption(found.uuid(), found.name(), staff.getUniqueId(), staff.getName(), finalReason)
                    .thenAccept(ignored -> Tasks.onEntity(staff, () -> staff.sendMessage(Msg.good(Msg.white(found.name()) + " is now exempt from alt-ban-evasion auto-bans."))));
        }));
    }

    @Command({"punish altunexempt", "p altunexempt"})
    @CommandPermission(PERMISSION_ADMIN)
    @Description("Remove a player's alt-ban-evasion exemption")
    public void altUnexempt(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName) {
        CommandSender sender = actor.sender();
        resolve(targetName).thenAccept(found -> Tasks.forSender(sender, () -> {
            if (found == null) {
                sender.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined."));
                return;
            }
            punishmentService.removeAltBanExemption(found.uuid()).thenAccept(removed -> Tasks.forSender(sender, () -> sender.sendMessage(
                    removed ? Msg.good(Msg.white(found.name()) + " is no longer exempt from alt-ban-evasion.") : Msg.bad(Msg.white(found.name()) + " wasn't exempt from anything."))));
        }));
    }

    @Command({"punish altexemptlist", "p altexemptlist"})
    @CommandPermission(PERMISSION_ADMIN)
    @Description("List players exempt from alt-ban-evasion auto-bans")
    public void altExemptList(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        punishmentService.listAltBanExemptions().thenAccept(exemptions -> Tasks.forSender(sender, () -> {
            if (exemptions.isEmpty()) {
                sender.sendMessage(Msg.neutral("No players are currently exempt from alt-ban-evasion."));
                return;
            }
            sender.sendMessage(Msg.neutral("Alt-ban-evasion exemptions:"));
            for (AltExemption e : exemptions) {
                sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + Msg.escape(e.targetName()) + "</yellow> - " + Msg.escape(e.reason())
                        + " <dark_gray>(added by " + Msg.escape(e.addedByName()) + ")"));
            }
        }));
    }

    @Command({"punish lockouts", "p lockouts"})
    @CommandPermission(PERMISSION_ADMIN)
    @Description("List staff currently locked out of issuing punishments")
    public void lockouts(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        lockoutService.listActive().thenAccept(active -> Tasks.forSender(sender, () -> {
            if (active.isEmpty()) {
                sender.sendMessage(Msg.neutral("No staff are currently locked out."));
                return;
            }
            sender.sendMessage(Msg.neutral("Active staff lockouts:"));
            for (StaffLockoutService.Lockout row : active) {
                sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + Msg.escape(row.staffName()) + "</yellow> - " + row.actionCount() + " actions in "
                        + row.windowSeconds() + "s, tripped " + DATE_FORMAT.format(Instant.ofEpochMilli(row.trippedAt())) + " <dark_gray>(" + row.displayId() + ")"));
            }
            sender.sendMessage(Msg.neutral("Run /punish clearlockout \"staff name\" to restore access."));
        }));
    }

    @Command({"punish clearlockout", "p clearlockout"})
    @CommandPermission(PERMISSION_ADMIN)
    @Description("Clear a staff member's punishment lockout")
    public void clearLockout(BukkitCommandActor actor, @Named("staffName") @revxrsal.commands.annotation.SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String staffName) {
        CommandSender sender = actor.sender();
        if (sender instanceof Player && sender.getName().equalsIgnoreCase(staffName.trim())) {
            sender.sendMessage(Msg.bad("Another admin has to clear your own lockout."));
            return;
        }
        lockoutService.clear(staffName, sender.getName()).thenAccept(cleared -> Tasks.forSender(sender, () -> sender.sendMessage(
                cleared <= 0 ? Msg.neutral("No active lockout found for " + staffName + ".") : Msg.good(Msg.white(staffName) + " is no longer locked out of issuing punishments."))));
    }

    @Command("punishment")
    @CommandPermission(PERMISSION_LOOKUP)
    @Description("Revoke a punishment by its id")
    @CommandPriority.Low
    public void revokeById(BukkitCommandActor actor, @Named("id") @Single String id) {
        CommandSender sender = actor.sender();
        String removedBy = sender instanceof Player ? sender.getName() : "Console";
        punishmentService.findByDisplayId(id).thenCompose(found -> {
            if (found == null) {
                return CompletableFuture.completedFuture(false);
            }
            boolean isBan = PunishmentService.TYPE_BAN.equals(found.type()) || PunishmentService.TYPE_IPBAN.equals(found.type());
            String required = isBan ? PERMISSION_ACTION_UNBAN : PERMISSION_APPROVE;
            if (!sender.hasPermission(required)) {
                return CompletableFuture.<Boolean>failedFuture(new NoPermissionException());
            }
            return warden.revoke(found.id(), removedBy, "");
        }).thenAccept(ok -> Tasks.forSender(sender, () -> sender.sendMessage(ok ? Msg.good("Revoked punishment " + id + ".") : Msg.bad("No active punishment with id " + id + "."))))
                .exceptionally(ex -> {
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("You can't revoke that punishment. Use the history in " + Msg.white("/punish <player>") + " to request a revoke.")));
                    return null;
                });
    }

    private static final class NoPermissionException extends RuntimeException {
    }

    @Command("punishment info")
    @CommandPermission(PERMISSION_LOOKUP)
    @Description("Show details about a punishment by its id, without revoking it")
    public void info(BukkitCommandActor actor, @Named("id") String id) {
        CommandSender sender = actor.sender();
        punishmentService.findByDisplayId(id).thenAccept(found -> Tasks.forSender(sender, () -> {
            if (found == null) {
                sender.sendMessage(Msg.bad("No punishment found with id " + id + "."));
                return;
            }
            String status = found.isInstantType() ? "LOGGED" : !found.active() ? "REVOKED" : (found.expiresAt() == null || found.expiresAt() > System.currentTimeMillis() ? "ACTIVE" : "EXPIRED");
            sender.sendMessage(Msg.neutral("Punishment " + found.displayId() + ":"));
            sender.sendMessage(Msg.parse("<gray>Target: <yellow>" + Msg.escape(found.targetName())));
            sender.sendMessage(Msg.parse("<gray>Type: <yellow>" + found.type() + "</yellow> Status: <yellow>" + status));
            sender.sendMessage(Msg.parse("<gray>Reason: <yellow>" + (found.reason() == null || found.reason().isBlank() ? "No reason given" : Msg.escape(found.reason()))));
            sender.sendMessage(Msg.parse("<gray>Staff: <yellow>" + Msg.escape(found.staffName()) + "</yellow> at " + DATE_FORMAT.format(Instant.ofEpochMilli(found.createdAt()))));
            if (!found.isInstantType()) {
                sender.sendMessage(Msg.parse("<gray>Expires: <yellow>" + (found.expiresAt() == null ? "Permanent" : DATE_FORMAT.format(Instant.ofEpochMilli(found.expiresAt())))));
            }
            if (!found.active()) {
                sender.sendMessage(Msg.parse("<gray>Revoked by: <yellow>" + (found.removeAdmin() == null || found.removeAdmin().isBlank() ? "Unknown" : Msg.escape(found.removeAdmin()))));
            }
        }));
    }

    @Command("history")
    @CommandPermission(PERMISSION_LOOKUP)
    @Description("Show a player's punishment history")
    public void history(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName) {
        CommandSender sender = actor.sender();
        resolve(targetName).thenAccept(found -> {
            if (found == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined.")));
                return;
            }
            punishmentService.history(found.uuid(), 20).thenAccept(rows -> Tasks.forSender(sender, () -> {
                if (rows.isEmpty()) {
                    sender.sendMessage(Msg.neutral(found.name() + " has no punishment history."));
                    return;
                }
                sender.sendMessage(Msg.neutral(found.name() + "'s history:"));
                for (PunishmentData row : rows) {
                    String status = row.isInstantType() ? "logged" : row.active() ? (row.expiresAt() == null || row.expiresAt() > System.currentTimeMillis() ? "active" : "expired") : "revoked";
                    sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + row.type() + "</yellow> by <yellow>" + Msg.escape(row.staffName()) + "</yellow> at "
                            + DATE_FORMAT.format(Instant.ofEpochMilli(row.createdAt())) + " [" + status + "] <yellow>" + Msg.escape(row.reason()) + "</yellow> <dark_gray>(" + row.displayId() + ")"));
                }
            }));
        });
    }

    @Command("checkban")
    @CommandPermission(PERMISSION_LOOKUP)
    @Description("Check whether a player is currently banned or muted")
    public void checkban(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName) {
        CommandSender sender = actor.sender();
        resolve(targetName).thenAccept(found -> {
            if (found == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined.")));
                return;
            }
            punishmentService.getActive(found.uuid(), PunishmentService.TYPE_BAN).thenAccept(ban -> Tasks.forSender(sender, () -> sender.sendMessage(
                    ban != null ? Msg.neutral(found.name() + " is banned. Reason: " + Msg.escape(ban.reason()) + " (" + ban.displayId() + ")") : Msg.neutral(found.name() + " is not banned."))));
            punishmentService.getActive(found.uuid(), PunishmentService.TYPE_MUTE).thenAccept(mute -> {
                if (mute != null) {
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.neutral(found.name() + " is muted. Reason: " + Msg.escape(mute.reason()) + " (" + mute.displayId() + ")")));
                }
            });
        });
    }

    @Command("altcheck")
    @CommandPermission(PERMISSION_LOOKUP)
    @Description("List other accounts seen logging in from the same IP(s)")
    public void altCheck(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName) {
        CommandSender sender = actor.sender();
        resolve(targetName).thenAccept(found -> {
            if (found == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.white(targetName) + " has ever joined.")));
                return;
            }
            punishmentService.findAltAccounts(found.uuid()).thenAccept(alts -> {
                if (alts.isEmpty()) {
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.neutral("No other accounts found sharing an IP with " + found.name() + ".")));
                    return;
                }
                List<CompletableFuture<String>> names = alts.stream().map(u -> playerData.findName(u).thenApply(n -> n.orElse(u.toString()))).toList();
                CompletableFuture.allOf(names.toArray(new CompletableFuture[0])).thenRun(() -> Tasks.forSender(sender, () -> {
                    sender.sendMessage(Msg.neutral("Accounts sharing an IP with " + found.name() + ":"));
                    for (CompletableFuture<String> n : names) {
                        sender.sendMessage(Msg.parse("<dark_gray> - <yellow>" + Msg.escape(n.join())));
                    }
                }));
            });
        });
    }
}
