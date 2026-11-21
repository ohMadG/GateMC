package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.scheduler.Tasks;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class PunishWebhook {
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    private static final HttpClient CLIENT = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private final String url;
    private final Logger logger;

    public PunishWebhook(String url, Logger logger) {
        this.url = url == null ? "" : url.trim();
        this.logger = logger;
    }

    private boolean enabled() {
        return !url.isEmpty();
    }

    public void issued(PunishmentData p) {
        String duration = p.isInstantType() ? "" : p.expiresAt() == null ? " (permanent)" : " until " + TIME.format(Instant.ofEpochMilli(p.expiresAt()));
        send(":hammer: **" + p.staffName() + "** " + verb(p.type()) + " **" + p.targetName() + "**" + duration
                + " - " + (p.reason().isBlank() ? "No reason given" : p.reason()) + " (" + p.displayId() + ")");
    }

    public void revoked(PunishmentData p, String removedBy) {
        send(":white_check_mark: **" + removedBy + "** revoked the " + p.type().toLowerCase() + " on **" + p.targetName() + "** (" + p.displayId() + ")");
    }

    public void staffLockout(String staffName, int actionCount, int windowSeconds) {
        send(":rotating_light: **" + staffName + "** was locked out of punishments after " + actionCount + " actions in " + windowSeconds + "s");
    }

    public void staffLockoutCleared(String staffName, String by) {
        send(":white_check_mark: **" + by + "** cleared **" + staffName + "**'s punishment lockout");
    }

    private static String verb(String type) {
        return switch (type) {
            case PunishmentService.TYPE_BAN -> "banned";
            case PunishmentService.TYPE_IPBAN -> "IP banned";
            case PunishmentService.TYPE_MUTE -> "muted";
            case PunishmentService.TYPE_SHADOWMUTE -> "shadow muted";
            case PunishmentService.TYPE_WARN -> "warned";
            case PunishmentService.TYPE_KICK -> "kicked";
            default -> type.toLowerCase();
        };
    }

    private static String json(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            switch (ch) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (ch < 0x20) {
                        out.append(String.format("\\u%04x", (int) ch));
                    } else {
                        out.append(ch);
                    }
                }
            }
        }
        return out.toString();
    }

    private void send(String content) {
        if (!enabled()) {
            return;
        }
        String body = "{\"content\":\"" + json(content) + "\",\"allowed_mentions\":{\"parse\":[]}}";
        Tasks.async.run(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                        .header("Content-Type", "application/json")
                        .timeout(Duration.ofSeconds(8))
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            } catch (Exception e) {
                logger.log(Level.FINE, "Punish webhook post failed", e);
            }
        });
    }
}
