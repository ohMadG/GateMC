package me.Evan52098.genscore.retention;

import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Level;

/** /retention: active players, new players and how many come back after 1, 3, 7, 14 and 30 days. */
public final class RetentionCommands {

    static final String PERMISSION = "genscore.admin.retention";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE MMM d", Locale.ENGLISH);

    private final RetentionService service;

    RetentionCommands(RetentionService service) {
        this.service = service;
    }

    private static void say(CommandSender to, String message) {
        to.sendMessage(LEGACY.deserialize(message));
    }

    private static String pct(RetentionService.Rate r) {
        if (r == null || r.cohort() == 0) {
            return "&8-";
        }
        int p = Math.round(100f * r.returned() / r.cohort());
        String colour = p >= 40 ? "&a" : p >= 20 ? "&e" : "&c";
        return colour + p + "%" + (r.partial() ? "&8*" : "");
    }

    private static String hours(long ms) {
        long minutes = ms / 60000;
        return minutes < 60 ? minutes + "m" : (minutes / 60) + "h " + (minutes % 60) + "m";
    }

    @Command("retention")
    @CommandPermission(PERMISSION)
    @Description("Player retention: who comes back after their first day")
    public void retention(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        say(sender, "&7Working out retention...");
        service.report(7).whenComplete((r, err) -> {
            if (err != null) {
                say(sender, "&cCould not read retention data. See the console.");
                org.bukkit.Bukkit.getLogger().log(Level.WARNING, "Retention report failed", err);
                return;
            }
            say(sender, "&6&lRETENTION &8» &7tracking since &f" + DAY.format(r.since()) + "&7, " + r.players() + " players ever");
            say(sender, "&7Active: &f" + r.days().get(r.days().size() - 1).active() + "&7 today, &f" + r.weekly()
                    + "&7 this week, &f" + r.monthly() + "&7 this month");
            StringBuilder line = new StringBuilder("&7Come back after:");
            for (int n : RetentionService.DAYS) {
                RetentionService.Rate rate = r.retention().get(n);
                line.append("  &fD").append(n).append(" ").append(pct(rate));
                if (rate != null) {
                    line.append(" &8(").append(rate.returned()).append("/").append(rate.cohort()).append(")");
                }
            }
            say(sender, line.toString());
            say(sender, "&8Last 7 days: active / new / avg time each");
            for (RetentionService.DayStats d : r.days()) {
                say(sender, " &7" + DAY.format(d.day()) + "  &f" + d.active() + "&7 / &a+" + d.fresh() + "&7 / &f"
                        + (d.active() == 0 ? "-" : hours(d.playtimeMs() / d.active())));
            }
            if (!r.cohorts().isEmpty()) {
                say(sender, "&8New players by first day: how many came back on day 1 / 3 / 7");
                for (RetentionService.Cohort c : r.cohorts()) {
                    Map<Integer, RetentionService.Rate> rates = c.rates();
                    say(sender, " &7" + DAY.format(c.day()) + "  &f" + c.size() + " new&7  D1 " + pct(rates.get(1))
                            + "&7  D3 " + pct(rates.get(3)) + "&7  D7 " + pct(rates.get(7)));
                }
            }
            say(sender, "&8* today is still counting");
        });
    }
}
