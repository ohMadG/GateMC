package me.Evan52098.genscore.info;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextReplacementConfig;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;

/**
 * Text helpers for group E (info, store, ranks, kit alerts). HxCore's settings are written with {@code &} colour codes
 * and {@code &#RRGGBB} hex colours, so these modules keep that style instead of MiniMessage. Safe from any thread.
 */
public final class Text {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&').hexColors().build();

    private Text() {
    }

    /** {@code &} colour codes (and {@code &#RRGGBB}) to a component. Null is empty. */
    public static Component legacy(@Nullable String text) {
        return text == null || text.isEmpty() ? Component.empty() : LEGACY.deserialize(text);
    }

    /** Like {@link #legacy} but never italic, for item names and lore. */
    public static Component item(@Nullable String text) {
        return Component.text().decoration(TextDecoration.ITALIC, false).append(legacy(text)).build();
    }

    /** Removes colour codes a player could have typed ({@code &} and {@code §}), for names put into coloured lines. */
    public static String stripCodes(String text) {
        return text == null ? "" : text.replace('&', ' ').replace('§', ' ').trim();
    }

    /** True for http and https links. Anything else is never made clickable. */
    public static boolean isWebLink(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String u = url.trim().toLowerCase(Locale.ROOT);
        return (u.startsWith("https://") || u.startsWith("http://")) && !u.contains(" ");
    }

    /** A clickable label that opens a web link, with a hover line. A bad link gives the plain label. */
    public static Component link(String label, @Nullable String url, @Nullable String hover) {
        Component c = legacy(label);
        if (!isWebLink(url)) {
            return c;
        }
        c = c.clickEvent(ClickEvent.openUrl(url.trim()));
        Component tip = hover == null || hover.isEmpty() ? legacy("&7" + url.trim()) : legacy(hover);
        return c.hoverEvent(HoverEvent.showText(tip));
    }

    /** Replaces literal tokens such as {@code {store}} in an already-built line with components. */
    public static Component replace(Component line, Map<String, Component> tokens) {
        Component out = line;
        for (Map.Entry<String, Component> e : tokens.entrySet()) {
            out = out.replaceText(TextReplacementConfig.builder().matchLiteral(e.getKey()).replacement(e.getValue()).build());
        }
        return out;
    }
}
