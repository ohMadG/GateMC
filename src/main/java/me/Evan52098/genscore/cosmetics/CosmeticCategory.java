package me.Evan52098.genscore.cosmetics;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * The wardrobe categories. One cosmetic can be active per category. {@link #key()} is used in cosmetics.yml, in the
 * database, in permissions ({@code genscore.cosmetics.<key>.<id>}) and in admin commands.
 */
public enum CosmeticCategory {
    KILL_EFFECT("kill-effects", "Kill Effects", Material.REDSTONE, "Particles and sounds where your victim falls."),
    EXPLOSIVE_KILL("explosive-kills", "Explosive Kills", Material.TNT, "A harmless blast where your victim falls."),
    JOIN_MESSAGE("join-messages", "Join Messages", Material.OAK_DOOR, "How the server announces you."),
    LEAVE_MESSAGE("leave-messages", "Leave Messages", Material.IRON_DOOR, "How the server says goodbye."),
    NAME_COLOR("name-colors", "Name Colours", Material.NAME_TAG, "The colour of your name in chat."),
    TRAIL("trails", "Trails", Material.BLAZE_POWDER, "Particles behind you as you walk."),
    TOOL_SKIN("tool-skins", "Tool Skins", Material.DIAMOND_PICKAXE, "A new look for your tools (others see it).");

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
