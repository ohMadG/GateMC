package me.Evan52098.genscore.store;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

final class StoreCheckout {

    private static final String API = "https://headless.tebex.io/api";
    private static final Pattern IDENT = Pattern.compile("[A-Za-z0-9\\-]{1,128}");

    static final class CheckoutException extends Exception {
        CheckoutException(String message) {
            super(message);
        }
    }

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    String create(String token, String username, String completeUrl, Map<StoreCatalog.Product, Integer> cart)
            throws CheckoutException, InterruptedException {
        JsonObject body = new JsonObject();
        body.addProperty("username", username);
        body.addProperty("complete_auto_redirect", !completeUrl.isEmpty());
        if (!completeUrl.isEmpty()) {
            body.addProperty("complete_url", completeUrl);
            body.addProperty("cancel_url", completeUrl);
        }
        JsonObject basket = post("/accounts/" + URLEncoder.encode(token, StandardCharsets.UTF_8) + "/baskets", body);
        String ident = string(basket, "ident");
        if (ident == null || !IDENT.matcher(ident).matches()) {
            throw new CheckoutException("Tebex did not return a basket");
        }
        JsonObject last = basket;
        for (Map.Entry<StoreCatalog.Product, Integer> e : cart.entrySet()) {
            JsonObject add = new JsonObject();
            add.addProperty("package_id", e.getKey().packageId());
            add.addProperty("quantity", e.getValue());
            if (e.getKey().subscription()) {
                add.addProperty("type", "subscription");
            }
            last = post("/baskets/" + ident + "/packages", add);
        }
        String link = checkoutLink(last);
        if (link == null) {
            link = checkoutLink(basket);
        }
        if (link == null || !link.startsWith("https://")) {
            throw new CheckoutException("Tebex did not return a checkout link");
        }
        return link;
    }

    private JsonObject post(String path, JsonObject body) throws CheckoutException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(API + path))
                .timeout(Duration.ofSeconds(15))
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .header("User-Agent", "GensCore")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (java.io.IOException e) {
            throw new CheckoutException("could not reach Tebex");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(res.body());
        } catch (RuntimeException e) {
            throw new CheckoutException("Tebex sent a bad reply (" + res.statusCode() + ")");
        }
        JsonObject obj = parsed.isJsonObject() ? parsed.getAsJsonObject() : new JsonObject();
        if (res.statusCode() / 100 != 2) {
            String detail = string(obj, "detail");
            if (detail == null) {
                detail = string(obj, "title");
            }
            throw new CheckoutException(detail == null ? "Tebex error " + res.statusCode() : detail);
        }
        return obj.has("data") && obj.get("data").isJsonObject() ? obj.getAsJsonObject("data") : obj;
    }

    private static String checkoutLink(JsonObject o) {
        if (o.has("links") && o.get("links").isJsonObject()) {
            return string(o.getAsJsonObject("links"), "checkout");
        }
        return null;
    }

    private static String string(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e != null && e.isJsonPrimitive() ? e.getAsString() : null;
    }
}
