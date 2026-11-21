package me.Evan52098.genscore.factory;

import org.bukkit.Material;
import org.bukkit.Particle;

import java.util.List;

final class Machines {

    record Machine(String name, String color, int segment, double multiplier, long priceCents, Material icon,
                   String hood, Particle particle, String description) {
    }

    static final List<Machine> ALL = List.of(
            new Machine("Smelter", "<#FF8A1F>", 10, 1.5, 5_000_00L, Material.BLAST_FURNACE,
                    "minecraft:blast_furnace[facing=north,lit=true]", Particle.FLAME, "Melts raw items into something better."),
            new Machine("Polisher", "<aqua>", 20, 1.5, 250_000_00L, Material.STONECUTTER,
                    "minecraft:stonecutter[facing=north]", Particle.WAX_ON, "Buffs every item to a shine."),
            new Machine("Refinery", "<yellow>", 30, 1.75, 10_000_000_00L, Material.WAXED_COPPER_BULB,
                    "minecraft:waxed_copper_bulb[lit=true]", Particle.ELECTRIC_SPARK, "Refines items with a jolt of power."),
            new Machine("Packager", "<green>", 42, 2.0, 500_000_000_00L, Material.CRAFTER,
                    "minecraft:crafter[orientation=down_north,crafting=true]", Particle.HAPPY_VILLAGER, "Boxes items up for premium buyers."),
            new Machine("Quantum Press", "<light_purple>", 55, 2.5, 50_000_000_000_00L, Material.RESPAWN_ANCHOR,
                    "minecraft:respawn_anchor[charges=4]", Particle.END_ROD, "Compresses items into pure value."));

    private Machines() {
    }

    static double multiplier(int owned, int beltLength) {
        double m = 1;
        for (int i = 0; i < Math.min(owned, ALL.size()); i++) {
            if (ALL.get(i).segment() < beltLength) {
                m *= ALL.get(i).multiplier();
            }
        }
        return m;
    }
}
