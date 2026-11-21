package me.Evan52098.genscore.staff;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * /report and /reports. A text version of BlockvilleCore's report package (its menu, reminders and Discord webhook
 * are not ported): players report, online staff are alerted, staff list and close open reports.
 */
public final class Reports {

    private record Report(long id, String reporter, String target, String reason, long at, String kind) {
    }

    private final Database db;
    private final Cooldowns cooldowns = new Cooldowns();
    private final long cooldownMillis;

    Reports(Database db, long cooldownSeconds) {
        this.db = db;
        this.cooldownMillis = Math.max(0, cooldownSeconds) * 1000L;
    }

    @Command("report")
    @CommandPermission("genscore.report")
    @Description("Report a player to staff")
    public void report(BukkitCommandActor actor, @Named("player") Player target, @Named("reason") String reason) {
        Player reporter = actor.requirePlayer();
        if (target.equals(reporter)) {
            reporter.sendMessage(Msg.bad("You can't report yourself."));
            return;
        }
        long left = cooldowns.remaining(reporter.getUniqueId(), "report");
        if (left > 0) {
            reporter.sendMessage(Msg.bad("Please wait " + Msg.white(Cooldowns.format(left)) + " before reporting again."));
            return;
        }
        cooldowns.start(reporter.getUniqueId(), "report", cooldownMillis);
        String reporterName = reporter.getName();
        String targetName = target.getName();
        String text = reason.length() > 200 ? reason.substring(0, 200) : reason;
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_reports (reporter, reporter_name, target, target_name, reason, at, open) VALUES (?, ?, ?, ?, ?, ?, 1)")) {
                ps.setString(1, reporter.getUniqueId().toString());
                ps.setString(2, reporterName);
                ps.setString(3, target.getUniqueId().toString());
                ps.setString(4, targetName);
                ps.setString(5, text);
                ps.setLong(6, System.currentTimeMillis());
                ps.executeUpdate();
            }
            return null;
        }).whenComplete((v, error) -> Tasks.entity.run(reporter, () -> {
            if (error != null) {
                reporter.sendMessage(Msg.bad("Your report could not be saved. Please tell a staff member directly."));
                return;
            }
            reporter.sendMessage(Msg.good("Thanks, your report about " + Msg.white(targetName) + " was sent to staff."));
            StaffTools.staffAlert(Msg.parse("<gold>[Report] <white>" + reporterName + "</white> <gray>reported</gray> <white>"
                    + targetName + "</white><gray>:</gray> " + Msg.escape(text)
                    + " <aqua><click:run_command:/reports>[open reports]</click></aqua>"));
        }));
    }

    @Command("reports")
    @CommandPermission("genscore.reports")
    @Description("List open reports")
    public void list(BukkitCommandActor actor) {
        var sender = actor.sender();
        db.async(c -> {
            List<Report> list = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, reporter_name, target_name, reason, at, kind FROM pvp_reports WHERE open = 1 ORDER BY id DESC LIMIT 15");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getString(6)));
                }
            }
            return list;
        }).whenComplete((list, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("Reports could not be loaded right now."));
                return;
            }
            if (list.isEmpty()) {
                sender.sendMessage(Msg.neutral("There are no open reports."));
                return;
            }
            sender.sendMessage(Msg.neutral("Open reports (newest first):"));
            long now = System.currentTimeMillis();
            for (Report r : list) {
                String actions = "filter".equals(r.kind())
                        ? "<aqua><click:run_command:/reports view " + r.id() + ">[view]</click></aqua> "
                        + "<red><click:run_command:/reports accept " + r.id() + "><hover:show_text:'<gray>Punish them (escalating)'>[accept]</hover></click></red> "
                        + "<green><click:run_command:/reports decline " + r.id() + "><hover:show_text:'<gray>No punishment'>[decline]</hover></click></green>"
                        : "<green><click:run_command:/reports close " + r.id() + ">[close]</click></green>";
                sender.sendMessage(Msg.parse("<gray>#" + r.id() + " <white>" + Msg.escape(r.target()) + "</white> by " + Msg.escape(r.reporter())
                        + " (" + Cooldowns.format(now - r.at()) + " ago): <white>" + Msg.escape(r.reason()) + "</white> " + actions));
            }
        }));
    }

    @Command("reports close")
    @CommandPermission("genscore.reports")
    public void close(BukkitCommandActor actor, @Named("id") long id) {
        var sender = actor.sender();
        String by = actor.name();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_reports SET open = 0, closed_by = ? WHERE id = ? AND open = 1")) {
                ps.setString(1, by);
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        }).whenComplete((rows, error) -> Tasks.forSender(sender, () -> sender.sendMessage(
                error == null && rows > 0 ? Msg.good("Closed report #" + id + ".") : Msg.bad("No open report #" + id + "."))));
    }

    @Command("reports view")
    @CommandPermission("genscore.reports")
    @Description("Show the blocked messages behind a chat filter report")
    public void view(BukkitCommandActor actor, @Named("id") long id) {
        var sender = actor.sender();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT target_name, reason, evidence, open, kind, outcome FROM pvp_reports WHERE id = ?")) {
                ps.setLong(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2), rs.getString(3),
                            String.valueOf(rs.getInt(4)), rs.getString(5), rs.getString(6)} : null;
                }
            }
        }).whenComplete((r, error) -> Tasks.forSender(sender, () -> {
            if (error != null || r == null) {
                sender.sendMessage(Msg.bad("No report #" + id + "."));
                return;
            }
            sender.sendMessage(Msg.neutral("Report #" + id + " on " + Msg.white(Msg.escape(r[0])) + ": " + Msg.escape(r[1])));
            if (r[2] != null) {
                for (String line : r[2].split("\n")) {
                    if (!line.isBlank()) {
                        sender.sendMessage(Msg.parse("<dark_gray>  » <gray>" + Msg.escape(line)));
                    }
                }
            }
            if ("1".equals(r[3]) && "filter".equals(r[4])) {
                sender.sendMessage(Msg.parse("<red><click:run_command:/reports accept " + id + ">[accept: punish]</click></red> "
                        + "<green><click:run_command:/reports decline " + id + ">[decline: no punishment]</click></green>"));
            } else if (!"1".equals(r[3])) {
                sender.sendMessage(Msg.neutral("Already closed" + (r[5] == null ? "" : " (" + r[5] + ")") + "."));
            }
        }));
    }

    @Command("reports accept")
    @CommandPermission("genscore.reports")
    @Description("Accept a chat filter report: the player is punished (escalating)")
    public void accept(BukkitCommandActor actor, @Named("id") long id) {
        var sender = actor.sender();
        String by = actor.name();
        java.util.UUID byUuid = sender instanceof Player staff ? staff.getUniqueId() : null;
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_reports SET open = 0, closed_by = ?, outcome = 'accepted' WHERE id = ? AND open = 1 AND kind = 'filter' "
                            + "RETURNING target, target_name")) {
                ps.setString(1, by);
                ps.setLong(2, id);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new String[]{rs.getString(1), rs.getString(2)} : null;
                }
            }
        }).whenComplete((r, error) -> {
            if (error != null || r == null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No open chat filter report #" + id + ".")));
                return;
            }
            String name = r[1];
            me.Evan52098.genscore.punish.PunishModule.punishFilterReport(java.util.UUID.fromString(r[0]), name, byUuid, by, id).whenComplete((outcome, e) ->
                    Tasks.forSender(sender, () -> {
                        if (e != null) {
                            Throwable cause = e instanceof java.util.concurrent.CompletionException && e.getCause() != null ? e.getCause() : e;
                            sender.sendMessage(Msg.bad("Report #" + id + " was accepted but no punishment was applied: " + Msg.escape(String.valueOf(cause.getMessage()))));
                            return;
                        }
                        StaffTools.staffAlert(Msg.parse("<gold>[Report] <white>" + Msg.escape(by) + "</white> <gray>accepted #" + id
                                + ": </gray><white>" + Msg.escape(name) + "</white> <gray>" + Msg.escape(outcome) + ".</gray>"));
                    }));
        });
    }

    @Command("reports decline")
    @CommandPermission("genscore.reports")
    @Description("Decline a chat filter report: no punishment")
    public void decline(BukkitCommandActor actor, @Named("id") long id) {
        var sender = actor.sender();
        String by = actor.name();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_reports SET open = 0, closed_by = ?, outcome = 'declined' WHERE id = ? AND open = 1 AND kind = 'filter'")) {
                ps.setString(1, by);
                ps.setLong(2, id);
                return ps.executeUpdate();
            }
        }).whenComplete((rows, error) -> Tasks.forSender(sender, () -> sender.sendMessage(
                error == null && rows > 0 ? Msg.good("Declined report #" + id + ". No punishment.")
                        : Msg.bad("No open chat filter report #" + id + "."))));
    }
}
