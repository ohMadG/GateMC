package me.Evan52098.genscore.factory;

import org.bukkit.util.BlockVector;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * A factory's belt: the template's "belt" marker cells put in order, from the end next to the gens to the far end.
 * Immutable.
 *
 * <p>Cells are joined by their four flat neighbours. The start is the end cell (one neighbour) closest to the first gen
 * pad; the belt is then walked cell to cell. Cells that do not join the walk are left out.
 */
final class BeltPath {

    enum Kind { STRAIGHT, CORNER_LEFT, CORNER_RIGHT }

    /** One belt segment: its block, the direction items leave it in, and the model to draw it with. */
    record Segment(BlockVector cell, int dx, int dz, Kind kind, int inDx, int inDz) {
    }

    private final List<Segment> segments;
    private final Set<Long> keys = new HashSet<>();

    private BeltPath(List<Segment> segments) {
        this.segments = List.copyOf(segments);
        for (Segment s : segments) {
            keys.add(key(s.cell().getBlockX(), s.cell().getBlockY(), s.cell().getBlockZ()));
        }
    }

    List<Segment> segments() {
        return segments;
    }

    int size() {
        return segments.size();
    }

    boolean contains(int x, int y, int z) {
        return keys.contains(key(x, y, z));
    }

    /** The middle of a segment's top surface, where an item rides. */
    Vector surface(int index) {
        BlockVector c = segments.get(index).cell();
        return new Vector(c.getBlockX() + 0.5, c.getBlockY() + 10.5 / 16.0, c.getBlockZ() + 0.5);
    }

    /** Where an item at belt position {@code p} (in segments, 0 = start of segment 0) is in the world. */
    Vector at(double p) {
        if (segments.isEmpty()) {
            return new Vector();
        }
        int i = (int) Math.floor(p);
        double f = p - i;
        if (p < 0) { // still in the gen's chute, behind the start
            Segment first = segments.get(0);
            return surface(0).add(new Vector(first.dx() * p, -1.0 / 16, first.dz() * p));
        }
        if (i >= segments.size() - 1) {
            Segment last = segments.get(segments.size() - 1);
            double over = p - (segments.size() - 1);
            return surface(segments.size() - 1).add(new Vector(last.dx() * over, 0, last.dz() * over));
        }
        Vector a = surface(i);
        Vector b = surface(i + 1);
        return a.multiply(1 - f).add(b.multiply(f));
    }

    static long key(int x, int y, int z) {
        return ((long) (x & 0x3ffffff) << 38) | ((long) (z & 0x3ffffff) << 12) | (y & 0xfff);
    }

    static BeltPath of(List<BlockVector> cells, List<BlockVector> pads) {
        Map<Long, BlockVector> byKey = new HashMap<>();
        for (BlockVector c : cells) {
            byKey.put(key(c.getBlockX(), c.getBlockY(), c.getBlockZ()), c);
        }
        if (byKey.isEmpty()) {
            return new BeltPath(List.of());
        }
        // Ends have exactly one neighbour; start at the end closest to the first pad.
        BlockVector start = null;
        double best = Double.MAX_VALUE;
        Vector padAt = pads.isEmpty() ? null : pads.get(0);
        for (BlockVector c : byKey.values()) {
            if (neighbours(c, byKey).size() == 1) {
                double d = padAt == null ? c.getBlockX() * 1e6 + c.getBlockZ() : c.distanceSquared(padAt);
                if (d < best) {
                    best = d;
                    start = c;
                }
            }
        }
        if (start == null) { // a closed loop or a single cell: start anywhere
            start = byKey.values().iterator().next();
        }
        List<BlockVector> order = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        BlockVector at = start;
        while (at != null) {
            order.add(at);
            seen.add(key(at.getBlockX(), at.getBlockY(), at.getBlockZ()));
            BlockVector next = null;
            for (BlockVector n : neighbours(at, byKey)) {
                if (!seen.contains(key(n.getBlockX(), n.getBlockY(), n.getBlockZ()))) {
                    next = n;
                    break;
                }
            }
            at = next;
        }
        List<Segment> segs = new ArrayList<>();
        for (int i = 0; i < order.size(); i++) {
            BlockVector c = order.get(i);
            int[] out = i + 1 < order.size() ? dir(c, order.get(i + 1)) : (i > 0 ? dir(order.get(i - 1), c) : new int[]{0, 1});
            int[] in = i > 0 ? dir(order.get(i - 1), c) : out;
            Kind kind = Kind.STRAIGHT;
            if (in[0] != out[0] || in[1] != out[1]) {
                // Turn so items come in heading +Z; then leaving toward +X is the "right" model, -X the "left".
                // Rotating (0, 1) onto (inDx, inDz) turns (1, 0) into (inDz, -inDx).
                kind = out[0] == in[1] && out[1] == -in[0] ? Kind.CORNER_RIGHT : Kind.CORNER_LEFT;
            }
            segs.add(new Segment(c, out[0], out[1], kind, in[0], in[1]));
        }
        return new BeltPath(segs);
    }

    private static int[] dir(BlockVector a, BlockVector b) {
        return new int[]{Integer.signum(b.getBlockX() - a.getBlockX()), Integer.signum(b.getBlockZ() - a.getBlockZ())};
    }

    private static List<BlockVector> neighbours(BlockVector c, Map<Long, BlockVector> byKey) {
        List<BlockVector> out = new ArrayList<>(4);
        int[][] steps = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] s : steps) {
            BlockVector n = byKey.get(key(c.getBlockX() + s[0], c.getBlockY(), c.getBlockZ() + s[1]));
            if (n != null) {
                out.add(n);
            }
        }
        return out;
    }
}
