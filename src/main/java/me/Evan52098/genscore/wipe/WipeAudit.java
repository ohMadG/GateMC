package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.util.DiscordWebhook;
import me.Evan52098.genscore.scheduler.Tasks;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

/**
 * Every wipe, restore and refusal goes to the console, to {@code modules/wipe/audit.log}, and (when
 * {@code wipe.discord-webhook-url} is set) to Discord (BlockvilleCore's WipeAuditReporter, which posted to the network
 * alerts channel). Checklist 11.
 */
final class WipeAudit {

    private final Logger logger;
    private final File file;
    private final DiscordWebhook webhook;
    private volatile String webhookUrl;

    WipeAudit(Logger logger, File file, DiscordWebhook webhook, String webhookUrl) {
        this.logger = logger;
        this.file = file;
        this.webhook = webhook;
        this.webhookUrl = webhookUrl;
    }

    void webhookUrl(String url) {
        webhookUrl = url;
    }

    void wiped(String actor, String target, String scope, String wipeId, @Nullable File backup, String receipt, @Nullable String reason) {
        String backupName = backup == null ? "none" : backup.getName();
        String line = actor + " ran a " + scope + " wipe of " + target + " (wipe id " + wipeId + "), backup " + backupName + ". " + receipt
                + (reason == null ? "" : " Reason: " + reason);
        logger.warning("[wipe] " + line);
        write(line);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Player", target);
        fields.put("Ran by", actor);
        fields.put("Scope", scope);
        fields.put("Wipe id", wipeId);
        fields.put("Backup", backupName);
        fields.put("Receipt", receipt);
        fields.put("Why you are seeing this", "An admin ran a wipe command. The backup named here is what it removed and /gensadmin wiperestore puts it back.");
        webhook.send(webhookUrl, "Player wipe - " + scope, 0x9B59B6, fields, Set.of("Backup", "Receipt", "Why you are seeing this"));
    }

    void restored(String actor, String target, File backup, String receipt) {
        String line = actor + " restored " + target + " from " + backup.getName() + ". " + receipt;
        logger.warning("[wipe] " + line);
        write(line);
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("Player", target);
        fields.put("Ran by", actor);
        fields.put("Backup", backup.getName());
        fields.put("Receipt", receipt);
        webhook.send(webhookUrl, "Player wipe restored", 0x9B59B6, fields, Set.of("Backup", "Receipt"));
    }

    void refused(String actor, String target, String scope, String reason) {
        String line = actor + " asked for a " + scope + " wipe of " + target + " and it was refused: " + reason;
        logger.warning("[wipe] " + line);
        write(line);
    }

    void note(String text) {
        logger.warning("[wipe] " + text);
        write(text);
    }

    private void write(String line) {
        String stamped = Instant.now() + " " + line + System.lineSeparator();
        Tasks.async.run(() -> {
            synchronized (file) {
                try {
                    File parent = file.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    Files.writeString(file.toPath(), stamped, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    logger.warning("Could not write the wipe audit log: " + e.getMessage());
                }
            }
        });
    }
}
