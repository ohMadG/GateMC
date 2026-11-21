package me.Evan52098.genscore.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Posts Discord embeds off the game threads, one at a time with a small gap so a burst of alerts does not hit
 * Discord's rate limit. Port of BlockvilleCore's AntiMinerWebhookService queue (and the LagWatch webhook), without
 * blockville-common's ReportWebhookClient.
 *
 * <p>The URL comes from config and is empty by default; an empty URL sends nothing. The URL is never written to the
 * log (it is a secret): failures log only the HTTP status or the error type.
 */
public final class DiscordWebhook {

    private static final long MIN_SEND_INTERVAL_MS = 400L;
    private static final int MAX_QUEUED = 200;
    private static final int MAX_FIELD = 1000;

    private record Queued(String url, String payload) {
    }

    private final Logger logger;
    private final String label;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Queue<Queued> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService dispatcher;
    private volatile boolean draining;
    private volatile long lastSendAt;
    private volatile boolean warnedQueueFull;

    public DiscordWebhook(Logger logger, String label) {
        this.logger = logger;
        this.label = label;
        this.dispatcher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GensCore-" + label + "-webhook");
            t.setDaemon(true);
            return t;
        });
    }

    public void stop() {
        dispatcher.shutdownNow();
        queue.clear();
    }

    /** True if the text looks like a URL we are willing to post to (https only). */
    public static boolean usable(String url) {
        return url != null && !url.isBlank() && url.trim().startsWith("https://");
    }

    /** Queues an embed. {@code wide} names fields shown full width instead of inline. */
    public void send(String url, String title, int color, Map<String, String> fields, Set<String> wide) {
        if (!usable(url)) {
            return;
        }
        if (queue.size() >= MAX_QUEUED) {
            if (!warnedQueueFull) {
                warnedQueueFull = true;
                logger.warning("[" + label + "] Discord webhook queue is full (" + MAX_QUEUED + " pending) - dropping new entries until it drains.");
            }
            return;
        }
        warnedQueueFull = false;
        queue.add(new Queued(url.trim(), payload(title, color, fields, wide)));
        scheduleDrain();
    }

    private synchronized void scheduleDrain() {
        if (draining) {
            return;
        }
        draining = true;
        long delay = Math.max(0L, MIN_SEND_INTERVAL_MS - (System.currentTimeMillis() - lastSendAt));
        try {
            dispatcher.schedule(this::drainOne, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException stopped) {
            draining = false;
        }
    }

    private void drainOne() {
        Queued next = queue.poll();
        synchronized (this) {
            draining = false;
        }
        if (next == null) {
            return;
        }
        lastSendAt = System.currentTimeMillis();
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(next.url()))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(next.payload()))
                    .build();
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() / 100 != 2) {
                logger.warning("[" + label + "] Discord did not accept a webhook post (HTTP " + response.statusCode() + ").");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        } catch (Exception e) {
            // Never log the URL: only the kind of failure.
            logger.warning("[" + label + "] Could not post to the Discord webhook (" + e.getClass().getSimpleName() + ").");
        }
        if (!queue.isEmpty()) {
            scheduleDrain();
        }
    }

    private static String payload(String title, int color, Map<String, String> fields, Set<String> wide) {
        JsonObject embed = new JsonObject();
        embed.addProperty("title", title);
        embed.addProperty("color", color);
        embed.addProperty("timestamp", DateTimeFormatter.ISO_INSTANT.format(Instant.now()));
        JsonArray list = new JsonArray();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String value = entry.getValue() == null || entry.getValue().isBlank() ? "-" : entry.getValue();
            JsonObject field = new JsonObject();
            field.addProperty("name", entry.getKey());
            field.addProperty("value", value.length() > MAX_FIELD ? value.substring(0, MAX_FIELD) : value);
            field.addProperty("inline", !wide.contains(entry.getKey()));
            list.add(field);
        }
        embed.add("fields", list);
        JsonArray embeds = new JsonArray();
        embeds.add(embed);
        JsonObject payload = new JsonObject();
        payload.add("embeds", embeds);
        JsonObject mentions = new JsonObject();
        mentions.add("parse", new JsonArray());
        payload.add("allowed_mentions", mentions);
        return payload.toString();
    }
}
