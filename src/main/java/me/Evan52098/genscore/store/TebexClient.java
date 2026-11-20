package me.Evan52098.genscore.store;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Reads completed store payments from the Tebex plugin API ({@code GET https://plugin.tebex.io/payments?paged=1}).
 *
 * <p><b>The secret key.</b> It is read from the Tebex plugin's own config file at the moment of each request and kept
 * only in a local variable: it is never copied into GensCore's config, stored in a field, logged, or put into an error
 * message. The API host is fixed in code, so a config change can't send the key anywhere else. Every method here
 * blocks: call it on an async thread only.
 */
final class TebexClient {

    /** The only host the key is ever sent to. */
    private static final String API = "https://plugin.tebex.io";
    /** Tebex secret keys are hex/alphanumeric; anything else is refused before it reaches a header. */
    private static final Pattern KEY = Pattern.compile("[A-Za-z0-9_\\-]{16,128}");
    private static final Pattern DASHLESS = Pattern.compile("[0-9a-fA-F]{32}");

    record Payment(String id, @Nullable UUID uuid, String name, long amountCents, String currency, String status, long createdAt) {
    }

    record Page(List<Payment> payments, int currentPage, int lastPage) {
    }

    /** A failed request, with a message that is safe to log (no key, no headers). */
    static final class TebexException extends Exception {
        TebexException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    private final File pluginsFolder;

    TebexClient(File pluginsFolder) {
        this.pluginsFolder = pluginsFolder;
    }

    /** Reads the key from the Tebex config file, or null if the file or key is missing. Async thread only. */
    @Nullable String readKey(String configFile, List<String> keyPaths) throws TebexException {
        File file;
        try {
            Path root = pluginsFolder.getCanonicalFile().toPath();
            file = new File(pluginsFolder, configFile).getCanonicalFile();
            if (!file.toPath().startsWith(root)) {
                throw new TebexException("store.tebex.config-file must be inside the plugins folder");
            }
        } catch (IOException e) {
            throw new TebexException("could not resolve store.tebex.config-file");
        }
        if (!file.isFile()) {
            return null;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String path : keyPaths) {
            String value = yaml.getString(path, "");
            if (value != null && !value.isBlank()) {
                String key = value.trim();
                if (!KEY.matcher(key).matches()) {
                    throw new TebexException("the Tebex secret key in " + configFile + " has an unexpected format");
                }
                return key;
            }
        }
        return null;
    }

    /** One page of payments, newest first. Async thread only. */
    Page fetch(String key, int page) throws TebexException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(API + "/payments?paged=1&page=" + page))
                .timeout(Duration.ofSeconds(20))
                .header("X-Tebex-Secret", key)
                .header("Accept", "application/json")
                .header("User-Agent", "GensCore")
                .GET().build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            // Only the exception type: messages from the HTTP stack are not needed and are kept out of logs.
            throw new TebexException("network error talking to Tebex (" + e.getClass().getSimpleName() + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new TebexException("interrupted");
        }
        int status = response.statusCode();
        if (status == 401 || status == 403) {
            throw new TebexException("Tebex refused the secret key (HTTP " + status + ")");
        }
        if (status == 429) {
            throw new TebexException("Tebex rate limit reached (HTTP 429); will try again next refresh");
        }
        if (status / 100 != 2) {
            throw new TebexException("Tebex answered HTTP " + status);
        }
        try {
            return parse(JsonParser.parseString(response.body()), page);
        } catch (RuntimeException e) {
            throw new TebexException("could not read the Tebex response (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Page parse(JsonElement root, int page) {
        JsonArray data;
        int current = page;
        int last = page;
        if (root.isJsonArray()) {
            data = root.getAsJsonArray();
        } else {
            JsonObject o = root.getAsJsonObject();
            data = o.has("data") && o.get("data").isJsonArray() ? o.getAsJsonArray("data") : new JsonArray();
            current = intOr(o, "current_page", page);
            last = intOr(o, "last_page", page);
        }
        List<Payment> out = new ArrayList<>();
        for (JsonElement e : data) {
            if (!e.isJsonObject()) {
                continue;
            }
            Payment p = payment(e.getAsJsonObject());
            if (p != null) {
                out.add(p);
            }
        }
        return new Page(out, current, last);
    }

    private static @Nullable Payment payment(JsonObject o) {
        String id = str(o, "id");
        if (id == null || id.isEmpty() || id.length() > 64) {
            return null;
        }
        long cents;
        try {
            String amount = str(o, "amount");
            BigDecimal value = new BigDecimal(amount == null ? "0" : amount);
            if (value.signum() < 0 || value.compareTo(BigDecimal.valueOf(10_000_000)) > 0) {
                return null; // nonsense amounts never reach the totals (checklist 8)
            }
            cents = value.setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
        String currency = "";
        if (o.has("currency") && o.get("currency").isJsonObject()) {
            String iso = str(o.getAsJsonObject("currency"), "iso_4217");
            currency = iso == null ? "" : iso;
        }
        String name = "";
        UUID uuid = null;
        if (o.has("player") && o.get("player").isJsonObject()) {
            JsonObject p = o.getAsJsonObject("player");
            String n = str(p, "name");
            name = n == null ? "" : n;
            uuid = parseUuid(str(p, "uuid"));
        }
        if (name.isEmpty() || name.length() > 32) {
            return null;
        }
        if (uuid == null) {
            // Offline-mode or missing uuid: the same uuid an offline-mode server would give the name.
            uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        String status = str(o, "status");
        return new Payment(id, uuid, name, cents, currency.toUpperCase(Locale.ROOT),
                status == null ? "" : status.trim().toLowerCase(Locale.ROOT), parseDate(str(o, "date")));
    }

    private static @Nullable UUID parseUuid(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        String t = s.trim();
        if (DASHLESS.matcher(t).matches()) {
            t = t.substring(0, 8) + "-" + t.substring(8, 12) + "-" + t.substring(12, 16) + "-" + t.substring(16, 20) + "-" + t.substring(20);
        }
        try {
            return UUID.fromString(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static long parseDate(@Nullable String s) {
        if (s == null || s.isBlank()) {
            return System.currentTimeMillis();
        }
        try {
            return OffsetDateTime.parse(s.trim()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(s.trim().replace(' ', 'T')).toInstant(ZoneOffset.UTC).toEpochMilli();
            } catch (DateTimeParseException e2) {
                return System.currentTimeMillis();
            }
        }
    }

    private static @Nullable String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() || !e.isJsonPrimitive() ? null : e.getAsString();
    }

    private static int intOr(JsonObject o, String key, int fallback) {
        try {
            JsonElement e = o.get(key);
            return e == null || e.isJsonNull() ? fallback : e.getAsInt();
        } catch (RuntimeException ex) {
            return fallback;
        }
    }
}
