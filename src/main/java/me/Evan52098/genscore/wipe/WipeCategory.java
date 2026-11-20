package me.Evan52098.genscore.wipe;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * What kind of data a wipe section holds (blockville-common's WipeCategory). A keep-list can keep whole categories, so
 * a module added later joins a wipe simply by declaring its sections with the right category.
 *
 * <p>Mapping from BlockvilleCore: ISLAND (box, homes, last location) became PLACES, since this server has no islands;
 * FACTIONS is new for the HCF modules. ECONOMY, PROGRESS, ITEMS, IDENTITY and PURCHASES mean what they meant there.
 */
public enum WipeCategory {
    PLACES,
    ECONOMY,
    PROGRESS,
    ITEMS,
    IDENTITY,
    PURCHASES,
    FACTIONS;

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static @Nullable WipeCategory parse(@Nullable String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim().toUpperCase(Locale.ROOT);
        if (t.equals("ISLAND")) {
            return PLACES; // BlockvilleCore's name
        }
        for (WipeCategory c : values()) {
            if (c.name().equals(t)) {
                return c;
            }
        }
        return null;
    }
}
