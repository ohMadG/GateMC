package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The {@code plots:} section of config.yml.
 *
 * <p>{@link Grid} is where factories are in the world. It is read once at start-up and never changes on a reload,
 * because moving it while factories exist would leave blocks behind. Everything in {@link Live} can be reloaded.
 */
final class PlotSettings {

    /** One factory lot: its lowest corner, and the rotation that turns the template's front (south) toward its road. */
    record Lot(int minX, int minZ, StructureRotation rotation) {
    }

    /**
     * The platform layout. A platform is a square plaza in the middle with four roads running out of it (east, south,
     * west, north). Each road has {@code lotsPerSide} factory lots on each of its two sides, all facing the road, so a
     * platform holds {@code 8 * lotsPerSide} factories (24 by default). When a platform is full the next one opens,
     * {@code spacing} blocks away.
     *
     * <pre>
     *              F | F
     *              F | F
     *              F | F
     *     F F F  +-------+  F F F
     *     -----  | plaza |  -----
     *     F F F  +-------+  F F F
     *              F | F
     *              F | F
     *              F | F
     * </pre>
     *
     * Slot numbers fill each platform from the plaza outwards (the nearest lot on every road first), then the next
     * platform. Templates are built with their front (gate side) facing south (+Z); each lot rotates it to face its road.
     *
     * <p>Folia: a whole platform is one region (its players can walk between factories to raid, so they must share a
     * thread). Separate platforms are {@code spacing} apart and tick in parallel. Keep spacing a multiple of 256 and the
     * platform centre about 128 blocks into a 256-block section (the default 1152 = 1024 + 128).
     */
    record Grid(String worldName, int originX, int originZ, int floorY, int spacing, int columns, int maxPlatforms,
                int size, int lotsPerSide, int roadHalf, int gap) {

        private static final int[][] ARMS = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}}; // east, south, west, north

        int perPlatform() {
            return 8 * lotsPerSide;
        }

        int maxSlots() {
            return maxPlatforms * perPlatform();
        }

        int roadWidth() {
            return roadHalf * 2 + 1;
        }

        /** Distance from the platform centre to the first lot along a road. */
        int plazaReach() {
            return roadHalf + size + gap;
        }

        /** Half the plaza's width: the plaza covers centre +- this. */
        int plazaHalf() {
            return plazaReach() - 1;
        }

        /** Distance from the centre to the far end of a road (the last lot's far edge plus a short road end). */
        int reach() {
            return plazaReach() + lotsPerSide * (size + gap) - gap + 2;
        }

        int platformOf(int slot) {
            return slot / perPlatform();
        }

        int centerX(int platform) {
            return originX + (platform % columns) * spacing;
        }

        int centerZ(int platform) {
            return originZ + (platform / columns) * spacing;
        }

        Lot lot(int slot) {
            int platform = platformOf(slot);
            int i = slot % perPlatform();
            int k = i / 8;              // how far out along the road
            int[] d = ARMS[(i / 2) % 4];
            int sign = i % 2 == 0 ? 1 : -1;
            int px = -d[1] * sign;      // perpendicular, toward this side of the road
            int pz = d[0] * sign;
            int a0 = plazaReach() + k * (size + gap);
            int a1 = a0 + size - 1;
            int c0 = roadHalf + 1;
            int c1 = roadHalf + size;
            int cx = centerX(platform);
            int cz = centerZ(platform);
            int x1 = cx + d[0] * a0 + px * c0;
            int x2 = cx + d[0] * a1 + px * c1;
            int z1 = cz + d[1] * a0 + pz * c0;
            int z2 = cz + d[1] * a1 + pz * c1;
            // The front faces the road: the direction from the lot back toward the road is -(px, pz).
            return new Lot(Math.min(x1, x2), Math.min(z1, z2), rotationFacing(-px, -pz));
        }

        /** The rotation that turns a template facing south (+Z) to face (fx, fz). */
        static StructureRotation rotationFacing(int fx, int fz) {
            if (fz == 1) {
                return StructureRotation.NONE;
            }
            if (fx == -1) {
                return StructureRotation.CLOCKWISE_90;
            }
            if (fz == -1) {
                return StructureRotation.CLOCKWISE_180;
            }
            return StructureRotation.COUNTERCLOCKWISE_90;
        }

        int minX(int slot) {
            return lot(slot).minX();
        }

        int minZ(int slot) {
            return lot(slot).minZ();
        }

        Location minCorner(World world, int slot) {
            Lot lot = lot(slot);
            return new Location(world, lot.minX(), floorY, lot.minZ());
        }

        /** The slot whose lot contains this block column, or -1. */
        int slotAt(int x, int z) {
            int col = Math.floorDiv(x - originX + spacing / 2, spacing);
            int row = Math.floorDiv(z - originZ + spacing / 2, spacing);
            if (col < 0 || col >= columns || row < 0) {
                return -1;
            }
            int platform = row * columns + col;
            if (platform >= maxPlatforms) {
                return -1;
            }
            int first = platform * perPlatform();
            for (int slot = first; slot < first + perPlatform(); slot++) {
                Lot lot = lot(slot);
                if (x >= lot.minX() && x < lot.minX() + size && z >= lot.minZ() && z < lot.minZ() + size) {
                    return slot;
                }
            }
            return -1;
        }

        void warnings(Logger log) {
            if (spacing % 256 != 0) {
                log.warning("plots.layout.platform-spacing is " + spacing + ": a multiple of 256 keeps each platform in its own Folia region.");
            }
            if (spacing < 2 * reach() + 64) {
                log.warning("plots.layout.platform-spacing (" + spacing + ") is too small for platforms " + (2 * reach() + 1)
                        + " blocks across: they would touch.");
            }
            if (2 * reach() + 1 > 256) {
                log.warning("Platforms are " + (2 * reach() + 1) + " blocks across, more than one 256-block region section.");
            }
        }
    }

    /** A block in the template that marks a place (spawn, belt path, gen pad) instead of being built as-is. */
    record Marker(String name, Material block, BlockData replaceWith) {
    }

    /** What the shared platform is made of. Changes apply to platforms built or rebuilt afterwards. */
    record Platform(String hubFile, Material plaza, Material road, Material lot) {
    }

    record Live(String templateFile, int blocksPerTick, int clearChecksPerTick, int clearHeight, int maxConcurrent,
                long removeDelaySeconds, boolean teleportOnJoin, float spawnYaw, int voidY, boolean protectWorld,
                boolean setWorldSpawn, Map<Material, Marker> markers, Material fallbackFloor, Material fallbackBorder,
                int defaultBeltLength, Platform platform) {
    }

    private PlotSettings() {
    }

    static Grid grid(ConfigurationSection root) {
        ConfigurationSection g = section(root, "layout");
        return new Grid(
                root.getString("world", "world"),
                g.getInt("origin-x", 1152),
                g.getInt("origin-z", 1152),
                g.getInt("floor-y", 64),
                Math.max(256, g.getInt("platform-spacing", 1024)),
                Math.max(1, g.getInt("platform-columns", 8)),
                Math.max(1, g.getInt("max-platforms", 16)),
                Math.max(5, root.getInt("size", 25)),
                Math.max(1, g.getInt("lots-per-side", 3)),
                Math.max(1, g.getInt("road-width", 7) / 2),
                Math.max(0, g.getInt("lot-gap", 3)));
    }

    static Live live(ConfigurationSection root, Logger log) {
        Map<Material, Marker> markers = new LinkedHashMap<>();
        ConfigurationSection m = section(root, "template.markers");
        for (String name : m.getKeys(false)) {
            ConfigurationSection one = m.getConfigurationSection(name);
            if (one == null) {
                continue;
            }
            Material block = Material.matchMaterial(one.getString("block", ""));
            if (block == null || !block.isBlock()) {
                log.warning("plots.template.markers." + name + ".block is not a block; marker ignored.");
                continue;
            }
            BlockData replace;
            try {
                replace = Bukkit.createBlockData(one.getString("replace-with", "minecraft:air"));
            } catch (IllegalArgumentException e) {
                log.warning("plots.template.markers." + name + ".replace-with is not valid block data; using air.");
                replace = Material.AIR.createBlockData();
            }
            markers.put(block, new Marker(name.toLowerCase(Locale.ROOT), block, replace));
        }
        ConfigurationSection p = section(root, "platform");
        return new Live(
                root.getString("template.file", "template.nbt"),
                Math.max(16, root.getInt("build.blocks-per-tick", 2000)),
                Math.max(64, root.getInt("build.clear-checks-per-tick", 8000)),
                Math.max(1, root.getInt("build.clear-height", 64)),
                Math.max(1, root.getInt("build.max-concurrent", 4)),
                Math.max(0, root.getLong("remove-delay-seconds", 0)),
                root.getBoolean("teleport-on-join", true),
                (float) root.getDouble("spawn-yaw", 180),
                root.getInt("void-y", 0),
                root.getBoolean("protect-world", true),
                root.getBoolean("set-world-spawn", true),
                Map.copyOf(markers),
                material(root.getString("template.fallback-floor"), Material.GRASS_BLOCK),
                material(root.getString("template.fallback-border"), Material.STONE_BRICKS),
                Math.max(1, root.getInt("defaults.belt-length", 8)),
                new Platform(
                        p.getString("hub-file", "hub.nbt"),
                        material(p.getString("plaza-block"), Material.POLISHED_ANDESITE),
                        material(p.getString("road-block"), Material.GRAY_CONCRETE),
                        material(p.getString("lot-block"), Material.SMOOTH_STONE)));
    }

    private static Material material(String name, Material fallback) {
        Material m = name == null ? null : Material.matchMaterial(name);
        return m != null && m.isBlock() ? m : fallback;
    }

    private static ConfigurationSection section(ConfigurationSection root, String path) {
        ConfigurationSection s = root.getConfigurationSection(path);
        return s != null ? s : new MemoryConfiguration();
    }
}
