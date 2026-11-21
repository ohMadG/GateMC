package me.Evan52098.genscore.punish;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Locale;

final class PunishMessages {
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private PunishMessages() {
    }

    static Component banMessage(PunishmentData p, String appealUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<red><bold>").append(p.expiresAt() == null ? "YOU ARE PERMANENTLY BANNED" : "YOU ARE TEMPORARILY BANNED").append("</bold></red>\n\n");
        sb.append("<gray>Reason: <white>").append(reasonOrDefault(p.reason())).append("</white>\n");
        sb.append("<gray>Expires: <white>").append(expiry(p)).append("</white>\n");
        if (appealUrl != null && !appealUrl.isBlank()) {
            sb.append("\n<gray>Appeal: <aqua>").append(escape(shortUrl(appealUrl))).append("</aqua>\n");
        }
        sb.append("<gray>Ban ID: <white>").append(p.displayId()).append("</white>\n");
        sb.append("<gray>Keep your Ban ID private - sharing it may affect your appeal.");
        return MM.deserialize(sb.toString());
    }

    static Component kickMessage(PunishmentData p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<gold><bold>YOU HAVE BEEN KICKED</bold></gold>\n\n");
        sb.append("<gray>Reason: <white>").append(reasonOrDefault(p.reason())).append("</white>\n\n");
        sb.append("<gray>This is not a ban - you are welcome to rejoin.\n");
        sb.append("<gray>Kick ID: <white>").append(p.displayId()).append("</white>");
        return MM.deserialize(sb.toString());
    }

    static Component muteMessage(PunishmentData p, String appealUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("<red><bold>YOU ARE MUTED</bold></red>\n\n");
        sb.append("<gray>Reason: <white>").append(reasonOrDefault(p.reason())).append("</white>\n");
        sb.append("<gray>Expires: <white>").append(expiry(p)).append("</white>\n");
        if (appealUrl != null && !appealUrl.isBlank()) {
            sb.append("\n<gray>Appeal: <aqua>").append(escape(shortUrl(appealUrl))).append("</aqua>\n");
        }
        sb.append("<gray>Mute ID: <white>").append(p.displayId()).append("</white>");
        return MM.deserialize(sb.toString());
    }

    static Component warnMessage(PunishmentData p) {
        StringBuilder sb = new StringBuilder();
        sb.append("<yellow><bold>OFFICIAL WARNING</bold></yellow>\n\n");
        sb.append("<gray>Reason: <white>").append(reasonOrDefault(p.reason())).append("</white>\n\n");
        sb.append("<gray>Further breaches of the rules may lead to a mute or a ban.\n");
        sb.append("<gray>Warning ID: <white>").append(p.displayId()).append("</white>");
        return MM.deserialize(sb.toString());
    }

    private static String expiry(PunishmentData p) {
        if (p.expiresAt() == null) {
            return "Never";
        }
        long left = Math.max(0L, p.expiresAt() - System.currentTimeMillis());
        return "in " + formatHoursOrDays(left);
    }

    static String duration(long millis) {
        return formatHoursOrDays(Math.max(0L, millis));
    }

    private static String formatHoursOrDays(long millis) {
        long hours = millis / 3_600_000L;
        if (hours >= 730) {
            return String.format(Locale.ROOT, "%.1f months", hours / 730.0);
        }
        if (hours >= 24) {
            return String.format(Locale.ROOT, "%.1f days", hours / 24.0);
        }
        if (millis >= 3_600_000L) {
            return String.format(Locale.ROOT, "%.1f hours", millis / 3_600_000.0);
        }
        long minutes = Math.max(1, (millis + 59_999) / 60_000);
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    private static String shortUrl(String url) {
        return url.replaceFirst("^https?://", "");
    }

    private static String reasonOrDefault(String reason) {
        return reason == null || reason.isBlank() ? "No reason given" : escape(reason);
    }

    private static String escape(String text) {
        return text == null ? "" : MM.escapeTags(text);
    }
}
