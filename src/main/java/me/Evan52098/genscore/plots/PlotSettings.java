package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The {@code plots:} section of config.yml.
 *
 * <p>{@link Grid} is where plots are in the world. It is read once at start-up and never changes on a reload, because
 * moving the grid while plots exist would leave blocks behind. Everything in {@link Live} can be reloaded.
 */
final class PlotSettings {

    /**
     * The slot grid. Slot {@code i} sits in column {@code i % columns}, row {@code i / columns}; its plot's lowest corner
     * is {@code (originX + column * spacing, floorY, originZ + row * spacing)}.
     *
     * <p>Folia: each plot should sit in its own region so plots tick in parallel. Keep {@code spacing} a multiple of 256
     * and the origin a multiple of 16 a few chunks into a 256-block section (the default 1088 = 1024 + 64), so a plot
     * never straddles two region sections and players on neighbouring plots do not load each other's chunks.
     */
    record Grid(String worldName, int originX, int originZ, int floorY, int spacing, int columns, int maxSlots, int size) {

        int minX(int slot) {
            return originX + (slot % columns) * spacing;
        }

        int minZ(int slot) {
            return originZ + (slot / columns) * spacing;
        }

        Location minCorner(World world, int slot) {
            return new Location(world, minX(slot), floorY, minZ(slot));
        }

        /** The slot whose plot footprint (size x size) contains this block column, or -1. */
        int slotAt(int x, int z) {
            int col = Math.floorDiv(x - originX, spacing);
            int row = Math.floorDiv(z - originZ, spacing);
            if (col < 0 || col >= columns || row < 0) {
                return -1;
            }
            int slot = row * columns + col;
            if (slot >= maxSlots) {
                return -1;
            }
            int dx = x - minX(slot);
            int dz = z - minZ(slot);
            return dx >= 0 && dx < size && dz >= 0 && dz < size ? slot : -1;
        }

        void warnings(Logger log) {
            if (spacing % 256 != 0) {
                log.warning("plots.grid.spacing is " + spacing + ": a multiple of 256 keeps each plot in its own Folia region.");
            }
            if (Math.floorMod(originX, 16) != 0 || Math.floorMod(originZ, 16) != 0) {
                log.warning("plots.grid.origin is not chunk aligned: plots will cover more chunks than they need.");
            }
            if (spacing < size + 32) {
                log.warning("plots.grid.spacing (" + spacing + ") leaves less than 32 blocks between plots.");
            }
        }
    }

    /** A block in the template that marks a place (spawn, belt path, gen pad) instead of being built as-is. */
    record Marker(String name, Material block, BlockData replaceWith) {
    }

    record Live(String templateFile, int blocksPerTick, int clearChecksPerTick, int clearHeight, int maxConcurrent, long removeDelaySeconds,
                boolean teleportOnJoin, float spawnYaw, int voidY, boolean protectWorld, Map<Material, Marker> markers,
                Material fallbackFloor, Material fallbackBorder, int defaultBeltLength) {
    }

    private PlotSettings() {
    }

    static Grid grid(ConfigurationSection root) {
        ConfigurationSection g = section(root, "grid");
        return new Grid(
                root.getString("world", "world"),
                g.getInt("origin-x", 1088),
                g.getInt("origin-z", 1088),
                g.getInt("floor-y", 64),
                Math.max(64, g.getInt("spacing", 1024)),
                Math.max(1, g.getInt("columns", 32)),
                Math.max(1, g.getInt("max-slots", 512)),
                Math.max(5, root.getInt("size", 25)));
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
        return new Live(
                root.getString("template.file", "template.nbt"),
                Math.max(16, root.getInt("build.blocks-per-tick", 2000)),
                Math.max(64, root.getInt("build.clear-checks-per-tick", 8000)),
                Math.max(1, root.getInt("build.clear-height", 64)),
                Math.max(1, root.getInt("build.max-concurrent", 4)),
                Math.max(0, root.getLong("remove-delay-seconds", 0)),
                root.getBoolean("teleport-on-join", true),
                (float) root.getDouble("spawn-yaw", 0),
                root.getInt("void-y", 0),
                root.getBoolean("protect-world", true),
                Map.copyOf(markers),
                material(root.getString("template.fallback-floor"), Material.GRASS_BLOCK),
                material(root.getString("template.fallback-border"), Material.STONE_BRICKS),
                Math.max(1, root.getInt("defaults.belt-length", 8)));
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
