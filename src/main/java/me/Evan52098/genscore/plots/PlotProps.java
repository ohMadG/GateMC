package me.Evan52098.genscore.plots;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.ToIntFunction;

public final class PlotProps {

    public record Stage(int level, String name) {
    }

    private record Prop(int x, int y, int z, String data, int level) {
    }

    public static final List<Stage> STAGES = List.of(
            new Stage(0, "Starter Kit"),
            new Stage(5, "Workshop"),
            new Stage(15, "Power Station"),
            new Stage(30, "Stockpile"),
            new Stage(50, "Night Lights"),
            new Stage(80, "Control Room"),
            new Stage(120, "Trophy Wall"),
            new Stage(149, "Beacon"));

    private static final int SIZE = 25;
    private static final List<Prop> PROPS = build();
    private static volatile @Nullable ToIntFunction<UUID> levelOf;

    private PlotProps() {
    }

    static void setLevelSource(@Nullable ToIntFunction<UUID> source) {
        levelOf = source;
    }

    static int level(UUID owner) {
        ToIntFunction<UUID> f = levelOf;
        try {
            return f == null || owner == null ? 0 : f.applyAsInt(owner);
        } catch (RuntimeException e) {
            return 0;
        }
    }

    public static List<Stage> unlockedBetween(int fromExclusive, int toInclusive) {
        List<Stage> out = new ArrayList<>();
        for (Stage s : STAGES) {
            if (s.level() > fromExclusive && s.level() <= toInclusive) {
                out.add(s);
            }
        }
        return out;
    }

    static List<PlotTemplate.TemplateBlock> blocks(int size, int fromExclusive, int toInclusive) {
        List<PlotTemplate.TemplateBlock> out = new ArrayList<>();
        if (size != SIZE) {
            return out;
        }
        for (Prop p : PROPS) {
            if (p.level() > fromExclusive && p.level() <= toInclusive) {
                out.add(new PlotTemplate.TemplateBlock(p.x(), p.y(), p.z(), Bukkit.createBlockData("minecraft:" + p.data())));
            }
        }
        out.sort((a, b) -> Integer.compare(a.y(), b.y()));
        return out;
    }

    private static List<Prop> build() {
        List<Prop> p = new ArrayList<>();
        int l = 0;
        p.add(new Prop(19, 1, 1, "barrel[facing=up]", l));
        p.add(new Prop(20, 1, 1, "barrel[facing=up]", l));
        p.add(new Prop(19, 2, 1, "barrel[facing=up]", l));
        p.add(new Prop(21, 1, 1, "crafting_table", l));
        p.add(new Prop(21, 1, 2, "cauldron", l));
        p.add(new Prop(21, 2, 1, "lantern[hanging=false]", l));

        l = 5;
        p.add(new Prop(1, 1, 4, "anvil[facing=north]", l));
        p.add(new Prop(1, 1, 6, "smithing_table", l));
        p.add(new Prop(1, 2, 6, "lantern[hanging=false]", l));
        p.add(new Prop(1, 1, 7, "grindstone[face=floor,facing=east]", l));
        p.add(new Prop(1, 1, 8, "stonecutter", l));
        p.add(new Prop(1, 1, 9, "fletching_table", l));

        l = 15;
        p.add(new Prop(23, 1, 4, "blast_furnace[facing=west]", l));
        p.add(new Prop(23, 1, 5, "blast_furnace[facing=west]", l));
        p.add(new Prop(23, 2, 4, "hopper[facing=down]", l));
        p.add(new Prop(23, 2, 5, "hopper[facing=down]", l));
        for (int y = 1; y <= 4; y++) {
            p.add(new Prop(23, y, 6, "waxed_copper_block", l));
        }
        p.add(new Prop(23, 5, 6, "lightning_rod[facing=up]", l));
        p.add(new Prop(23, 1, 7, "observer[facing=up]", l));
        p.add(new Prop(23, 1, 8, "waxed_copper_bulb[lit=true]", l));
        p.add(new Prop(23, 2, 8, "lever[face=floor,facing=west]", l));
        p.add(new Prop(22, 1, 9, "iron_block", l));
        p.add(new Prop(23, 1, 9, "iron_block", l));
        p.add(new Prop(22, 2, 9, "iron_bars", l));
        p.add(new Prop(23, 2, 9, "copper_lantern[hanging=false]", l));

        l = 30;
        p.add(new Prop(3, 1, 18, "raw_iron_block", l));
        p.add(new Prop(4, 1, 18, "raw_copper_block", l));
        p.add(new Prop(3, 2, 18, "raw_iron_block", l));
        p.add(new Prop(3, 1, 19, "raw_gold_block", l));
        p.add(new Prop(4, 1, 19, "coal_block", l));
        p.add(new Prop(6, 1, 18, "hay_block[axis=y]", l));
        p.add(new Prop(6, 2, 18, "hay_block[axis=x]", l));
        p.add(new Prop(5, 1, 21, "barrel[facing=up]", l));
        p.add(new Prop(6, 1, 21, "barrel[facing=up]", l));
        p.add(new Prop(5, 2, 21, "barrel[facing=up]", l));
        p.add(new Prop(3, 1, 21, "stripped_spruce_log[axis=z]", l));

        l = 50;
        for (int i = 3; i <= 21; i += 2) {
            p.add(new Prop(i, 6, 0, "lantern[hanging=false]", l));
            p.add(new Prop(0, 6, i, "lantern[hanging=false]", l));
            p.add(new Prop(24, 6, i, "lantern[hanging=false]", l));
            if (i <= 7 || i >= 17) {
                p.add(new Prop(i, 6, 24, "lantern[hanging=false]", l));
            }
        }

        l = 80;
        for (int x = 18; x <= 20; x++) {
            p.add(new Prop(x, 1, 18, "iron_block", l));
            p.add(new Prop(x, 2, 18, "black_stained_glass", l));
            p.add(new Prop(x, 3, 18, "black_stained_glass", l));
        }
        p.add(new Prop(18, 1, 19, "crafter", l));
        p.add(new Prop(19, 1, 19, "observer[facing=north]", l));
        p.add(new Prop(20, 1, 19, "target", l));
        p.add(new Prop(18, 2, 19, "lever[face=floor,facing=south]", l));
        p.add(new Prop(20, 2, 19, "redstone_torch", l));
        p.add(new Prop(19, 1, 21, "spruce_stairs[facing=south]", l));
        p.add(new Prop(21, 1, 20, "lectern[facing=west]", l));

        l = 120;
        int[] xs = {5, 8, 11, 14};
        String[] trophies = {"gold_block", "diamond_block", "emerald_block", "netherite_block"};
        for (int i = 0; i < xs.length; i++) {
            p.add(new Prop(xs[i], 1, 1, "polished_blackstone", l));
            p.add(new Prop(xs[i], 2, 1, trophies[i], l));
        }

        l = 149;
        p.add(new Prop(17, 1, 1, "iron_block", l));
        p.add(new Prop(17, 2, 1, "beacon", l));
        return List.copyOf(p);
    }
}
