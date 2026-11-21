package me.Evan52098.genscore.cosmetics;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The wardrobe categories. One cosmetic can be active per category. {@link #key()} is used in cosmetics.yml, in the
 * database, in permissions ({@code genscore.cosmetics.<key>.<id>}) and in admin commands.
 */
public enum CosmeticCategory {
    JOIN_MESSAGE("join-messages", "Join Messages", Material.OAK_DOOR, "How the server announces you."),
    LEAVE_MESSAGE("leave-messages", "Leave Messages", Material.IRON_DOOR, "How the server says goodbye."),
    CHAT_TAG("chat-tags", "Chat Tags", Material.OAK_SIGN, "A tag in front of your name in chat."),
    NAME_COLOR("name-colors", "Name Paints", Material.NAME_TAG, "Your name, animated in chat and the tab list."),
    CHAT_COLOR("chat-colors", "Chat Colors", Material.CYAN_DYE, "Your chat messages in a new colour."),
    AURA("auras", "Auras", Material.BEACON, "A helix of particles around you."),
    GLOW("glows", "Glows", Material.GLOW_INK_SAC, "A glowing outline around you.");

    private final String key;
    private final String title;
    private final Material icon;
    private final String blurb;

    CosmeticCategory(String key, String title, Material icon, String blurb) {
        this.key = key;
        this.title = title;
        this.icon = icon;
        this.blurb = blurb;
    }

    public String key() {
        return key;
    }

    public String title() {
        return title;
    }

    public Material icon() {
        return icon;
    }

    public String blurb() {
        return blurb;
    }

    public static @Nullable CosmeticCategory byKey(String key) {
        String k = key.toLowerCase(Locale.ROOT).trim();
        for (CosmeticCategory c : values()) {
            if (c.key.equals(k) || c.name().equalsIgnoreCase(k)) {
                return c;
            }
        }
        return null;
    }
}
