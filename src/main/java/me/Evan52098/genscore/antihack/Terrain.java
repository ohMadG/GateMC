package me.Evan52098.genscore.antihack;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Shulker;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.Nullable;

/**
 * Block lookups around a player (BlockvilleCore's Terrain). Folia: only blocks owned by the current region thread are
 * read; a block the thread does not own (or an unloaded one) counts as UNLOADED, which the checks treat as "can't
 * tell, don't flag", exactly like an unloaded chunk on Paper.
 */
final class Terrain {
    static final int SUPPORT = 1;
    static final int MEDIUM = 2;
    static final int BOUNCY = 4;
    static final int FAST_SURFACE = 8;
    static final int UNLOADED = 16;

    private Terrain() {
    }

    /** The block, or null if this thread may not read it (another region owns it) or it is not loaded. */
    static @Nullable Block blockAt(World world, int x, int y, int z) {
        if (!Bukkit.isOwnedByCurrentRegion(world, x >> 4, z >> 4) || !world.isChunkLoaded(x >> 4, z >> 4)) {
            return null;
        }
        return world.getBlockAt(x, y, z);
    }

    /** The block type, or AIR when it can't be read. */
    static Material typeAt(World world, int x, int y, int z) {
        Block block = blockAt(world, x, y, z);
        return block == null ? Material.AIR : block.getType();
    }

    static @Nullable Block blockAt(org.bukkit.Location at) {
        return at.getWorld() == null ? null : blockAt(at.getWorld(), at.getBlockX(), at.getBlockY(), at.getBlockZ());
    }

    static Material typeAt(org.bukkit.Location at) {
        Block block = blockAt(at);
        return block == null ? Material.AIR : block.getType();
    }

    /** A readable block that is liquid. */
    static boolean liquidAt(org.bukkit.Location at) {
        Block block = blockAt(at);
        return block != null && block.isLiquid();
    }

    /** Unreadable blocks count as passable (no support), like air. */
    static boolean passableAt(World world, int x, int y, int z) {
        Block block = blockAt(world, x, y, z);
        return block == null || block.isPassable();
    }

    static int classify(World world, double x, double y, double z, double width, double height) {
        double reach = width / 2.0 + 0.35;
        int minX = floor(x - reach), maxX = floor(x + reach);
        int minZ = floor(z - reach), maxZ = floor(z + reach);
        int minY = floor(y - 0.6), maxY = floor(y + height + 0.25);
        int flags = 0;
        for (int bx = minX; bx <= maxX; bx++) {
            for (int bz = minZ; bz <= maxZ; bz++) {
                if (!Bukkit.isOwnedByCurrentRegion(world, bx >> 4, bz >> 4) || !world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    return flags | UNLOADED | SUPPORT;
                }
                for (int by = minY; by <= maxY; by++) {
                    Block block = world.getBlockAt(bx, by, bz);
                    Material type = block.getType();
                    if (!type.isAir()) {
                        flags |= flagsOf(block, type);
                    }
                }
            }
        }
        return flags;
    }

    private static int flagsOf(Block block, Material type) {
        if (type == Material.SLIME_BLOCK || Tag.BEDS.isTagged(type)) {
            return SUPPORT | BOUNCY;
        }
        if (type == Material.ICE || type == Material.PACKED_ICE || type == Material.BLUE_ICE || type == Material.FROSTED_ICE
                || type == Material.SOUL_SAND || type == Material.SOUL_SOIL) {
            return SUPPORT | FAST_SURFACE;
        }
        if (type == Material.WATER || type == Material.LAVA || type == Material.BUBBLE_COLUMN || type == Material.SEAGRASS || type == Material.TALL_SEAGRASS
                || type == Material.KELP || type == Material.KELP_PLANT || type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH
                || type == Material.POWDER_SNOW || Tag.CLIMBABLE.isTagged(type)) {
            return SUPPORT | MEDIUM;
        }
        return block.isPassable() ? 0 : SUPPORT;
    }

    /** Folia: getNearbyEntities from a region thread only sees entities that region owns, which is all that can be this close. */
    static boolean standableEntityNear(World world, double x, double y, double z, double width, double height) {
        double reach = width / 2.0 + 0.5;
        BoundingBox area = new BoundingBox(x - reach, y - 1.0, z - reach, x + reach, y + height + 0.25, z + reach);
        return !world.getNearbyEntities(area, entity -> entity instanceof Boat || entity instanceof Shulker || entity instanceof Minecart).isEmpty();
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
