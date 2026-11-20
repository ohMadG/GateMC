package me.Evan52098.genscore.worldrules;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * An axis-aligned block box in one world, read from config as {@code world}, {@code min: [x, y, z]} and
 * {@code max: [x, y, z]} (both corners included, in any order). Immutable, so it is safe to read from any thread.
 */
public record Box(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {

    /** Reads a box; {@code worldOverride} is used when the section has no {@code world} key. Null if incomplete. */
    public static @Nullable Box read(@Nullable ConfigurationSection s, @Nullable String worldOverride) {
        if (s == null) {
            return null;
        }
        String world = s.getString("world", worldOverride);
        int[] a = triple(s.getIntegerList("min"));
        int[] b = triple(s.getIntegerList("max"));
        if (world == null || a == null || b == null) {
            return null;
        }
        return new Box(world, Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2]));
    }

    private static int @Nullable [] triple(List<Integer> list) {
        return list == null || list.size() < 3 ? null : new int[]{list.get(0), list.get(1), list.get(2)};
    }

    public boolean inWorld(@Nullable World w) {
        return w != null && w.getName().equals(world);
    }

    public boolean contains(String worldName, int x, int y, int z) {
        return world.equals(worldName) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
    }

    /** True if x and z are inside, whatever the height. */
    public boolean containsColumn(String worldName, int x, int z) {
        return world.equals(worldName) && x >= minX && x <= maxX && z >= minZ && z <= maxZ;
    }

    public boolean contains(@Nullable Location l) {
        return l != null && l.getWorld() != null && contains(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ());
    }

    public boolean contains(Block b) {
        return contains(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
    }
}
