package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The blocks every plot is built from, plus the marked places in it (spawn, belt path, gen pads).
 *
 * <p>Rule 4 of the roadmap: a plot is this template plus the owner's data; plot blocks are never saved. The template
 * is a vanilla structure file ({@code .nbt}), made in game with {@code /plotadmin template pos1|pos2|save}. Without a
 * file, a plain floor with a border is used so the server works before anyone builds a template.
 *
 * <p>Immutable, so every thread can read it. Only block data is copied: chests, signs and other block entities lose
 * their contents (a gens plot should use display entities for text).
 */
final class PlotTemplate {

    record TemplateBlock(int x, int y, int z, BlockData data) {
    }

    private final List<TemplateBlock> blocks;
    private final Map<String, List<BlockVector>> markers;
    private final int height;
    private final String source;

    private PlotTemplate(List<TemplateBlock> blocks, Map<String, List<BlockVector>> markers, int height, String source) {
        this.blocks = List.copyOf(blocks);
        Map<String, List<BlockVector>> copy = new LinkedHashMap<>();
        markers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.markers = Map.copyOf(copy);
        this.height = height;
        this.source = source;
    }

    /** Blocks to place, lowest layer first, so blocks that need support are placed after it. No air. */
    List<TemplateBlock> blocks() {
        return blocks;
    }

    /** Marker positions by marker name, relative to the plot's lowest corner. Empty list if none. */
    List<BlockVector> marker(String name) {
        return markers.getOrDefault(name, List.of());
    }

    Map<String, List<BlockVector>> markers() {
        return markers;
    }

    int height() {
        return height;
    }

    String source() {
        return source;
    }

    /** Where a player stands on arrival, relative to the lowest corner: the "spawn" marker, or the middle of the plot. */
    Vector spawnOffset(int size) {
        List<BlockVector> spawn = marker("spawn");
        if (!spawn.isEmpty()) {
            BlockVector v = spawn.get(0);
            return new Vector(v.getX() + 0.5, v.getY(), v.getZ() + 0.5);
        }
        int top = 0;
        int mid = size / 2;
        for (TemplateBlock b : blocks) {
            if (b.x() == mid && b.z() == mid) {
                top = Math.max(top, b.y() + 1);
            }
        }
        return new Vector(mid + 0.5, top, mid + 0.5);
    }

    /** Loads the structure file, or returns the fallback floor when there is no file. Startup or async thread. */
    static PlotTemplate load(File file, int size, PlotSettings.Live live, Logger log) {
        if (!file.isFile()) {
            log.info("No plot template at " + file.getPath() + "; using a plain " + size + "x" + size + " floor.");
            return fallback(size, live);
        }
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(file);
        } catch (IOException | RuntimeException e) {
            log.severe("Could not read plot template " + file.getPath() + " (" + e.getMessage() + "); using a plain floor.");
            return fallback(size, live);
        }
        List<Palette> palettes = structure.getPalettes();
        if (palettes.isEmpty()) {
            log.severe("Plot template " + file.getName() + " has no blocks; using a plain floor.");
            return fallback(size, live);
        }
        BlockVector dims = structure.getSize();
        if (dims.getBlockX() > size || dims.getBlockZ() > size) {
            log.warning("Plot template " + file.getName() + " is " + dims.getBlockX() + "x" + dims.getBlockZ()
                    + ", bigger than plots.size (" + size + "). Blocks outside " + size + "x" + size + " are left out.");
        }
        List<TemplateBlock> out = new ArrayList<>();
        Map<String, List<BlockVector>> markers = new LinkedHashMap<>();
        for (BlockState state : palettes.get(0).getBlocks()) {
            int x = state.getX();
            int y = state.getY();
            int z = state.getZ();
            if (x < 0 || z < 0 || y < 0 || x >= size || z >= size) {
                continue;
            }
            BlockData data = state.getBlockData();
            PlotSettings.Marker marker = live.markers().get(data.getMaterial());
            if (marker != null) {
                markers.computeIfAbsent(marker.name(), k -> new ArrayList<>()).add(new BlockVector(x, y, z));
                data = marker.replaceWith();
            }
            if (!data.getMaterial().isAir()) {
                out.add(new TemplateBlock(x, y, z, data));
            }
        }
        sort(out);
        log.info("Loaded plot template " + file.getName() + ": " + out.size() + " blocks, " + dims.getBlockY() + " high, markers "
                + summary(markers) + ".");
        return new PlotTemplate(out, markers, dims.getBlockY(), file.getName());
    }

    static PlotTemplate fallback(int size, PlotSettings.Live live) {
        List<TemplateBlock> out = new ArrayList<>();
        BlockData floor = live.fallbackFloor().createBlockData();
        BlockData border = live.fallbackBorder().createBlockData();
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                out.add(new TemplateBlock(x, 0, z, edge ? border : floor));
            }
        }
        return new PlotTemplate(out, Map.of(), 1, "fallback");
    }

    private static void sort(List<TemplateBlock> blocks) {
        blocks.sort(Comparator.comparingInt(TemplateBlock::y).thenComparingInt(TemplateBlock::x).thenComparingInt(TemplateBlock::z));
    }

    private static String summary(Map<String, List<BlockVector>> markers) {
        if (markers.isEmpty()) {
            return "none";
        }
        StringBuilder sb = new StringBuilder();
        markers.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append(" x").append(v.size()));
        return sb.toString();
    }

    /** True for blocks the clear pass must remove. */
    static boolean needsClearing(Material m) {
        return !m.isAir();
    }
}
