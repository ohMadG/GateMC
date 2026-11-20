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

    /**
     * The built-in factory used while there is no template file: a walled fortress. Front (gate side) faces south.
     *
     * <ul>
     *   <li>Stone-brick walls 5 high with battlements, and a 3x3 tower at each corner.</li>
     *   <li>A 7-wide, 4-high gate opening in the middle of the front wall, framed in polished deepslate with a door
     *       housing above it ("gate" markers; the garage door is drawn by the factory module).</li>
     *   <li>A belt path snaking across the floor on slabs ("belt" markers, in belt order), gen pads along the back
     *       ("pad"), a gate button inside next to the gate ("gate_button") and the arrival spot ("spawn").</li>
     * </ul>
     */
    static PlotTemplate fallbackFactory(int size, PlotSettings.Live live) {
        int s = size - 1;
        Map<Long, TemplateBlock> grid = new java.util.HashMap<>();
        Map<String, List<BlockVector>> markers = new LinkedHashMap<>();
        java.util.function.BiConsumer<int[], BlockData> put = (p, d) ->
                grid.put(((long) p[0] << 40) | ((long) p[1] << 20) | p[2], new TemplateBlock(p[0], p[1], p[2], d));
        BlockData floor = live.fallbackFloor().createBlockData();
        BlockData floorEdge = Material.POLISHED_ANDESITE.createBlockData();
        BlockData wall = live.fallbackBorder().createBlockData();
        BlockData wallBase = Material.DEEPSLATE_BRICKS.createBlockData();
        BlockData cap = Material.CHISELED_STONE_BRICKS.createBlockData();
        BlockData battlement = Material.STONE_BRICK_WALL.createBlockData();
        BlockData tower = Material.POLISHED_BLACKSTONE_BRICKS.createBlockData();
        BlockData frame = Material.POLISHED_DEEPSLATE.createBlockData();
        BlockData housing = Material.IRON_BLOCK.createBlockData();
        BlockData light = Material.SEA_LANTERN.createBlockData();
        BlockData slab = Bukkit.createBlockData("minecraft:smooth_stone_slab[type=bottom]");
        BlockData pad = Material.POLISHED_DIORITE.createBlockData();
        int gateHalf = size >= 15 ? 3 : 1;
        int gateLo = size / 2 - gateHalf;
        int gateHi = size / 2 + gateHalf;
        int wallTop = 5;
        int gateTop = 4;

        // Floor.
        for (int x = 0; x <= s; x++) {
            for (int z = 0; z <= s; z++) {
                boolean edge = x == 0 || z == 0 || x == s || z == s;
                put.accept(new int[]{x, 0, z}, edge ? floorEdge : floor);
            }
        }
        // Walls with battlements, leaving the gate opening in the front (z = s).
        for (int i = 0; i <= s; i++) {
            for (int[] p : new int[][]{{i, 0}, {i, s}, {0, i}, {s, i}}) {
                boolean front = p[1] == s && p[0] != 0 && p[0] != s;
                for (int y = 1; y <= wallTop; y++) {
                    if (front && p[0] >= gateLo && p[0] <= gateHi && y <= gateTop) {
                        continue;
                    }
                    put.accept(new int[]{p[0], y, p[1]}, y == 1 ? wallBase : y == wallTop ? cap : wall);
                }
                if (i % 2 == 0) {
                    put.accept(new int[]{p[0], wallTop + 1, p[1]}, battlement);
                }
            }
        }
        // Corner towers.
        for (int[] c : new int[][]{{0, 0}, {s - 2, 0}, {0, s - 2}, {s - 2, s - 2}}) {
            for (int dx = 0; dx < 3; dx++) {
                for (int dz = 0; dz < 3; dz++) {
                    for (int y = 1; y <= wallTop + 2; y++) {
                        put.accept(new int[]{c[0] + dx, y, c[1] + dz}, tower);
                    }
                    if ((dx + dz) % 2 == 0 && !(dx == 1 && dz == 1)) {
                        put.accept(new int[]{c[0] + dx, wallTop + 3, c[1] + dz}, battlement);
                    }
                }
            }
            // The middle of each tower top is where a turret stands (drawn by the factory module).
            markers.computeIfAbsent("turret", k -> new ArrayList<>()).add(new BlockVector(c[0] + 1, wallTop + 3, c[1] + 1));
        }
        // Gate frame and the door housing above the opening.
        for (int y = 1; y <= wallTop; y++) {
            put.accept(new int[]{gateLo - 1, y, s}, frame);
            put.accept(new int[]{gateHi + 1, y, s}, frame);
        }
        for (int x = gateLo; x <= gateHi; x++) {
            put.accept(new int[]{x, wallTop, s}, housing);
            for (int y = 1; y <= gateTop; y++) {
                markers.computeIfAbsent("gate", k -> new ArrayList<>()).add(new BlockVector(x, y, s));
            }
        }
        // Gate button inside, on the front wall beside the opening.
        BlockData button = Bukkit.createBlockData("minecraft:polished_blackstone_button[face=wall,facing=north]");
        put.accept(new int[]{gateLo - 2, 2, s - 1}, button);
        markers.computeIfAbsent("gate_button", k -> new ArrayList<>()).add(new BlockVector(gateLo - 2, 2, s - 1));
        // Belt: rows across the floor joined at alternate ends, on slabs, in belt order.
        List<int[]> path = new ArrayList<>();
        int left = 4;
        int right = s - 4;
        boolean eastward = true;
        int lastRow = -1;
        for (int z = 4; z <= s - 8; z += 6) {
            if (lastRow >= 0) {
                int x = eastward ? left : right; // the turn column is the end the previous row finished at
                for (int dz = lastRow + 1; dz < z; dz++) {
                    path.add(new int[]{x, dz});
                }
            }
            if (eastward) {
                for (int x = lastRow >= 0 ? left : left; x <= right; x++) {
                    path.add(new int[]{x, z});
                }
            } else {
                for (int x = right; x >= left; x--) {
                    path.add(new int[]{x, z});
                }
            }
            eastward = !eastward;
            lastRow = z;
        }
        // The turn cells were added before each row, at the row's starting end: drop duplicates.
        List<int[]> unique = new ArrayList<>();
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int[] p : path) {
            if (seen.add(((long) p[0] << 20) | p[1])) {
                unique.add(p);
            }
        }
        for (int[] p : unique) {
            put.accept(new int[]{p[0], 1, p[1]}, slab);
            markers.computeIfAbsent("belt", k -> new ArrayList<>()).add(new BlockVector(p[0], 1, p[1]));
        }
        // Gen pads along the back, behind the start of the belt.
        for (int x = left; x <= Math.min(right, left + 12); x += 3) {
            put.accept(new int[]{x, 0, 2}, pad);
            markers.computeIfAbsent("pad", k -> new ArrayList<>()).add(new BlockVector(x, 0, 2));
        }
        // Floor lights between the belt rows.
        for (int x = 2; x <= s - 2; x += 5) {
            for (int z = 7; z <= s - 5; z += 6) {
                put.accept(new int[]{x, 0, z}, light);
            }
        }
        markers.computeIfAbsent("spawn", k -> new ArrayList<>()).add(new BlockVector(size / 2, 1, s - 3));
        List<TemplateBlock> out = new ArrayList<>(grid.values());
        sort(out);
        return new PlotTemplate(size, size, size, out, markers, wallTop + 4, "built-in fortress");
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
