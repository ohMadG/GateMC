package me.Evan52098.genscore.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.configuration.ConfigurationSection;

/**
 * Chat message styles, the GensCore version of BlockvilleCore's {@code F.good/bad/neutral}. Messages are MiniMessage.
 * The prefixes and colours come from {@code messages:} in config.yml (BlockvilleCore used resource-pack glyphs, which
 * this server does not have).
 */
public final class Msg {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static String goodPrefix = "<#10b900>✔ ";
    private static String badPrefix = "<#b90000>✘ ";
    private static String neutralPrefix = "<#e0b000>» ";
    private static String goodColor = "<#10b900>";
    private static String badColor = "<#b90000>";
    private static String neutralColor = "<#e0b000>";

    private Msg() {
    }

    public static void load(ConfigurationSection s) {
        if (s == null) {
            return;
        }
        goodPrefix = s.getString("good-prefix", goodPrefix);
        badPrefix = s.getString("bad-prefix", badPrefix);
        neutralPrefix = s.getString("neutral-prefix", neutralPrefix);
        goodColor = s.getString("good-color", goodColor);
        badColor = s.getString("bad-color", badColor);
        neutralColor = s.getString("neutral-color", neutralColor);
    }

    public static Component good(String message) {
        return MM.deserialize(goodPrefix + goodColor + message);
    }

    public static Component bad(String message) {
        return MM.deserialize(badPrefix + badColor + message);
    }

    public static Component neutral(String message) {
        return MM.deserialize(neutralPrefix + neutralColor + message);
    }

    public static Component parse(String miniMessage) {
        return MM.deserialize(miniMessage);
    }

    /** Highlights a value inside a message. */
    public static String white(String value) {
        return "<white>" + escape(value) + "</white>";
    }

    /** Escapes MiniMessage tags in text that came from players (names, item names). */
    public static String escape(String text) {
        return MM.escapeTags(text);
    }
}
