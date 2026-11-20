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

    private record Report(long id, String reporter, String target, String reason, long at) {
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
                    "SELECT id, reporter_name, target_name, reason, at FROM pvp_reports WHERE open = 1 ORDER BY id DESC LIMIT 15");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new Report(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5)));
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
                sender.sendMessage(Msg.parse("<gray>#" + r.id() + " <white>" + r.target() + "</white> by " + r.reporter()
                        + " (" + Cooldowns.format(now - r.at()) + " ago): <white>" + Msg.escape(r.reason()) + "</white> "
                        + "<green><click:run_command:/reports close " + r.id() + ">[close]</click></green>"));
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
}
