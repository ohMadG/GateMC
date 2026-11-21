package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class PlotStyles {

    public record Style(String id, Map<Material, Material> palette, @Nullable Material door) {
    }

    public interface Styler {
        @Nullable Style styleFor(UUID owner);
    }

    private static volatile @Nullable Styler styler;

    private PlotStyles() {
    }

    public static void set(@Nullable Styler s) {
        styler = s;
    }

    public static @Nullable Style of(UUID owner) {
        Styler s = styler;
        if (s == null || owner == null) {
            return null;
        }
        try {
            return s.styleFor(owner);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static Material door(UUID owner, Material fallback) {
        Style s = of(owner);
        return s == null || s.door() == null ? fallback : s.door();
    }

    private static final String[] DYES = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

    public static String accent(UUID owner) {
        return DYES[Math.floorMod(owner.hashCode() * 31 + 7, DYES.length)];
    }

    static List<PlotTemplate.TemplateBlock> apply(@Nullable UUID owner, List<PlotTemplate.TemplateBlock> blocks) {
        if (owner == null) {
            return blocks;
        }
        Style style = of(owner);
        java.util.Map<Material, Material> palette = new java.util.EnumMap<>(Material.class);
        if (style != null) {
            palette.putAll(style.palette());
        }
        String dye = accent(owner);
        Material glass = Material.matchMaterial(dye + "_stained_glass");
        Material band = Material.matchMaterial(dye + "_terracotta");
        if (glass != null && !palette.containsKey(Material.ORANGE_STAINED_GLASS)) {
            palette.put(Material.ORANGE_STAINED_GLASS, glass);
        }
        if (band != null && !palette.containsKey(Material.ORANGE_TERRACOTTA)) {
            palette.put(Material.ORANGE_TERRACOTTA, band);
        }
        if (palette.isEmpty()) {
            return blocks;
        }
        List<PlotTemplate.TemplateBlock> out = new ArrayList<>(blocks.size());
        for (PlotTemplate.TemplateBlock b : blocks) {
            Material to = palette.get(b.data().getMaterial());
            out.add(to == null ? b : new PlotTemplate.TemplateBlock(b.x(), b.y(), b.z(), swap(b.data(), to)));
        }
        return out;
    }

    private static BlockData swap(BlockData data, Material to) {
        String from = data.getMaterial().getKey().toString();
        String state = data.getAsString();
        if (state.startsWith(from)) {
            try {
                return Bukkit.createBlockData(to.getKey() + state.substring(from.length()));
            } catch (IllegalArgumentException ignored) {
            }
        }
        return to.createBlockData();
    }
}
