package me.Evan52098.genscore.plots;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.util.BlockVector;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What other modules need to know about one built factory: who owns it, where it is, which way it faces, and the
 * template's marked places (belt, pads, gate, gate button, spawn) already turned and moved to world coordinates.
 * Immutable.
 *
 * @param minX       lowest X of the lot
 * @param floorY     the height the template's bottom layer is built at
 * @param minZ       lowest Z of the lot
 * @param size       the lot is size x size
 * @param rotation   how the template was turned (its front faced south before turning)
 * @param markers    marker name -> world block positions, in template order (belt markers are not yet in belt order)
 */
public record FactoryView(UUID owner, int slot, int platform, World world, int minX, int floorY, int minZ, int size,
                          StructureRotation rotation, Map<String, List<BlockVector>> markers) {

    public List<BlockVector> marker(String name) {
        return markers.getOrDefault(name, List.of());
    }

    /** True if the block column is inside the lot, walls included. */
    public boolean contains(int x, int z) {
        return x >= minX && x < minX + size && z >= minZ && z < minZ + size;
    }

    /** True if the block column is inside the fortress walls (the lot minus its outer ring). */
    public boolean inside(int x, int z) {
        return x > minX && x < minX + size - 1 && z > minZ && z < minZ + size - 1;
    }

    public Location center() {
        return new Location(world, minX + size / 2.0, floorY + 1, minZ + size / 2.0);
    }

    /** The unit direction the factory's front faces (toward its road), as {dx, dz}. */
    public int[] facing() {
        return switch (rotation) {
            case NONE -> new int[]{0, 1};
            case CLOCKWISE_90 -> new int[]{-1, 0};
            case CLOCKWISE_180 -> new int[]{0, -1};
            case COUNTERCLOCKWISE_90 -> new int[]{1, 0};
        };
    }
}
