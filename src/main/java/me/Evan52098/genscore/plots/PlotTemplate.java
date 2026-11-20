package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.structure.Palette;
import org.bukkit.structure.Structure;
import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Blocks to build from a vanilla structure file ({@code .nbt}), plus the marked places in it (spawn, belt path, gen
 * pads). Used for the factory template (every lot is built from it) and for the platform's central hub.
 *
 * <p>Roadmap rule 4: a factory is this template plus the owner's data; factory blocks are never saved. Without a file
 * a fallback is used, so the server works before anyone builds a template.
 *
 * <p>Factory templates are built with their front (gate side) facing south (+Z). Each lot rotates the template a
 * quarter turn at a time so the front faces its road; the four rotated copies are worked out once, at load.
 *
 * <p>Immutable, so every thread can read it. Only block data is copied: chests, signs and other block entities lose
 * their contents (use display entities for text).
 */
final class PlotTemplate {

    record TemplateBlock(int x, int y, int z, BlockData data) {
    }

    private final int footprint;
    private final int sizeX;
    private final int sizeZ;
    private final int height;
    private final String source;
    private final List<TemplateBlock> blocks;
    private final Map<StructureRotation, List<TemplateBlock>> rotated = new EnumMap<>(StructureRotation.class);
    private final Map<String, List<BlockVector>> markers;

    private PlotTemplate(int footprint, int sizeX, int sizeZ, List<TemplateBlock> blocks, Map<String, List<BlockVector>> markers,
                         int height, String source) {
        this.footprint = footprint;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.blocks = List.copyOf(blocks);
        Map<String, List<BlockVector>> copy = new LinkedHashMap<>();
        markers.forEach((k, v) -> copy.put(k, List.copyOf(v)));
        this.markers = Map.copyOf(copy);
        this.height = height;
        this.source = source;
    }

    /** Blocks lowest layer first (so blocks that need support come after it), turned for a lot. No air. */
    List<TemplateBlock> blocks(StructureRotation rotation) {
        if (rotation == StructureRotation.NONE) {
            return blocks;
        }
        synchronized (rotated) {
            return rotated.computeIfAbsent(rotation, r -> {
                List<TemplateBlock> out = new ArrayList<>(blocks.size());
                for (TemplateBlock b : blocks) {
                    int[] xz = turn(b.x(), b.z(), r);
                    BlockData data = b.data().clone();
                    data.rotate(r);
                    out.add(new TemplateBlock(xz[0], b.y(), xz[1], data));
                }
                return List.copyOf(out);
            });
        }
    }

    /** Marker positions by name, relative to the lot's lowest corner and turned for the lot. Empty if none. */
    List<BlockVector> marker(String name, StructureRotation rotation) {
        List<BlockVector> out = new ArrayList<>();
        for (BlockVector v : markers.getOrDefault(name, List.of())) {
            int[] xz = turn(v.getBlockX(), v.getBlockZ(), rotation);
            out.add(new BlockVector(xz[0], v.getBlockY(), xz[1]));
        }
        return out;
    }

    Map<String, List<BlockVector>> markers() {
        return markers;
    }

    int height() {
        return height;
    }

    int sizeX() {
        return sizeX;
    }

    int sizeZ() {
        return sizeZ;
    }

    String source() {
        return source;
    }

    /**
     * Where a player stands on arrival, relative to the lowest corner: the "spawn" marker, or the middle on top of
     * whatever is there.
     */
    Vector spawnOffset(StructureRotation rotation) {
        List<BlockVector> spawn = marker("spawn", rotation);
        if (!spawn.isEmpty()) {
            BlockVector v = spawn.get(0);
            return new Vector(v.getX() + 0.5, v.getY(), v.getZ() + 0.5);
        }
        int midX = (rotation == StructureRotation.NONE || rotation == StructureRotation.CLOCKWISE_180 ? sizeX : sizeZ) / 2;
        int midZ = (rotation == StructureRotation.NONE || rotation == StructureRotation.CLOCKWISE_180 ? sizeZ : sizeX) / 2;
        int top = 0;
        for (TemplateBlock b : blocks(rotation)) {
            if (b.x() == midX && b.z() == midZ) {
                top = Math.max(top, b.y() + 1);
            }
        }
        return new Vector(midX + 0.5, top, midZ + 0.5);
    }

    /** A yaw from the template's own frame turned for a lot (Minecraft yaw: 0 south, 90 west, 180 north). */
    static float turnYaw(float yaw, StructureRotation rotation) {
        return switch (rotation) {
            case NONE -> yaw;
            case CLOCKWISE_90 -> yaw + 90;
            case CLOCKWISE_180 -> yaw + 180;
            case COUNTERCLOCKWISE_90 -> yaw - 90;
        };
    }

    /** Turns a position inside the footprint x footprint square a quarter turn at a time, like the game does. */
    private int[] turn(int x, int z, StructureRotation r) {
        int s = footprint - 1;
        return switch (r) {
            case NONE -> new int[]{x, z};
            case CLOCKWISE_90 -> new int[]{s - z, x};
            case CLOCKWISE_180 -> new int[]{s - x, s - z};
            case COUNTERCLOCKWISE_90 -> new int[]{z, s - x};
        };
    }

    // ------------------------------------------------------------------ loading

    /**
     * Loads a structure file, clipped to {@code limit} x {@code limit}. Returns null when there is no file (the caller
     * picks the fallback). Startup or async thread.
     */
    static PlotTemplate load(File file, int limit, Map<Material, PlotSettings.Marker> markerBlocks, String what, Logger log) {
        if (!file.isFile()) {
            return null;
        }
        Structure structure;
        try {
            structure = Bukkit.getStructureManager().loadStructure(file);
        } catch (IOException | RuntimeException e) {
            log.severe("Could not read the " + what + " " + file.getPath() + " (" + e.getMessage() + "); using the fallback.");
            return null;
        }
        List<Palette> palettes = structure.getPalettes();
        if (palettes.isEmpty()) {
            log.severe("The " + what + " " + file.getName() + " has no blocks; using the fallback.");
            return null;
        }
        BlockVector dims = structure.getSize();
        if (dims.getBlockX() > limit || dims.getBlockZ() > limit) {
            log.warning("The " + what + " " + file.getName() + " is " + dims.getBlockX() + "x" + dims.getBlockZ()
                    + ", bigger than " + limit + "x" + limit + ". Blocks outside that are left out.");
        }
        List<TemplateBlock> out = new ArrayList<>();
        Map<String, List<BlockVector>> markers = new LinkedHashMap<>();
        for (BlockState state : palettes.get(0).getBlocks()) {
            int x = state.getX();
            int y = state.getY();
            int z = state.getZ();
            if (x < 0 || z < 0 || y < 0 || x >= limit || z >= limit) {
                continue;
            }
            BlockData data = state.getBlockData();
            PlotSettings.Marker marker = markerBlocks.get(data.getMaterial());
            if (marker != null) {
                markers.computeIfAbsent(marker.name(), k -> new ArrayList<>()).add(new BlockVector(x, y, z));
                data = marker.replaceWith();
            }
            if (!data.getMaterial().isAir()) {
                out.add(new TemplateBlock(x, y, z, data));
            }
        }
        sort(out);
        log.info("Loaded the " + what + " " + file.getName() + ": " + out.size() + " blocks, " + dims.getBlockX() + "x"
                + dims.getBlockY() + "x" + dims.getBlockZ() + ", markers " + summary(markers) + ".");
        return new PlotTemplate(limit, Math.min(limit, dims.getBlockX()), Math.min(limit, dims.getBlockZ()), out, markers,
                dims.getBlockY(), file.getName());
    }

    /** The factory used while there is no template file: a floor with a border, the front edge marked in the road block. */
    static PlotTemplate fallbackFactory(int size, PlotSettings.Live live) {
        List<TemplateBlock> out = new ArrayList<>();
        BlockData floor = live.fallbackFloor().createBlockData();
        BlockData border = live.fallbackBorder().createBlockData();
        BlockData front = Material.YELLOW_CONCRETE.createBlockData(); // shows which way the factory faces (its road)
        for (int x = 0; x < size; x++) {
            for (int z = 0; z < size; z++) {
                boolean edge = x == 0 || z == 0 || x == size - 1 || z == size - 1;
                out.add(new TemplateBlock(x, 0, z, z == size - 1 ? front : edge ? border : floor));
            }
        }
        return new PlotTemplate(size, size, size, out, Map.of(), 1, "fallback");
    }

    /** No hub file: nothing on top of the plaza floor. */
    static PlotTemplate emptyHub(int width) {
        return new PlotTemplate(width, width, width, List.of(), Map.of(), 0, "none");
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
