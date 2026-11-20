package me.Evan52098.genscore.crates;

import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** The symbols a lockbox combination is drawn from. Same as BlockvilleCore's LockboxSymbol. */
public enum LockboxSymbol {

    CARROT(Material.CARROT, "Carrot"),
    APPLE(Material.APPLE, "Apple"),
    WHEAT(Material.WHEAT, "Wheat"),
    FISH(Material.COOKED_COD, "Fish"),
    COAL(Material.COAL, "Coal"),
    IRON(Material.IRON_INGOT, "Iron Ingot"),
    EMERALD(Material.EMERALD, "Emerald"),
    DIAMOND(Material.DIAMOND, "Diamond");

    private final Material material;
    private final String displayName;

    LockboxSymbol(Material material, String displayName) {
        this.material = material;
        this.displayName = displayName;
    }

    public Material material() {
        return material;
    }

    public String displayName() {
        return displayName;
    }

    public static @Nullable LockboxSymbol byName(@Nullable String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
