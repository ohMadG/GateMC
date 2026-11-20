package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.util.Money;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code factory:} section of config.yml. Every number reloads live with {@code /gensadmin reload} (roadmap rule 5).
 *
 * @param gateCloseSeconds   how long the gate stays shut after the owner closes it
 * @param doorBlock          what the garage door slats are made of
 * @param startSpeed         belt speed at level 0, in segments per second (one item per segment at most)
 * @param speedLevels        the speed upgrades: speed and price of each level above 0
 * @param lengthStep         segments added by one length upgrade
 * @param lengthBasePrice    price of the first length upgrade, in cents
 * @param lengthGrowth       each length upgrade costs this much more than the one before
 * @param genIntervalSeconds seconds between items from the gen; caps throughput at 1 / interval
 * @param itemBaseCents      what an item is worth if sold straight off the gen
 * @param valuePerSegment    each belt segment an item rides adds this fraction of its base value
 * @param depositSeconds     earnings are paid into the owner's balance this often (one ledger entry each time)
 */
record FactorySettings(int gateCloseSeconds, Material doorBlock, double startSpeed, List<SpeedLevel> speedLevels,
                       int lengthStep, long lengthBasePrice, double lengthGrowth, double genIntervalSeconds,
                       long itemBaseCents, double valuePerSegment, int depositSeconds, Material beltItem,
                       int maxDrawnItems, int viewDistance, float modelYawOffset, Pack pack) {

    record SpeedLevel(double speed, long price) {
    }

    record Pack(String url, String sha1, boolean required, String prompt) {
        boolean enabled() {
            return url != null && !url.isBlank() && sha1 != null && sha1.matches("[0-9a-fA-F]{40}");
        }
    }

    /** Belt speed in segments per second at an upgrade level. */
    double speed(int level) {
        if (level <= 0 || speedLevels.isEmpty()) {
            return startSpeed;
        }
        return speedLevels.get(Math.min(level, speedLevels.size()) - 1).speed();
    }

    /** Price of going from {@code level} to the next speed level, or -1 at the top. */
    long nextSpeedPrice(int level) {
        return level < speedLevels.size() ? speedLevels.get(level).price() : -1;
    }

    /** Price of the length upgrade number {@code done + 1}. */
    long lengthPrice(int done) {
        return Math.round(lengthBasePrice * Math.pow(lengthGrowth, done));
    }

    /** What one item is worth after riding {@code segments} segments, in cents. */
    long itemValue(int segments) {
        return Math.round(itemBaseCents * (1 + valuePerSegment * segments));
    }

    static FactorySettings from(ConfigurationSection root) {
        if (root == null) {
            root = new MemoryConfiguration();
        }
        List<SpeedLevel> levels = new ArrayList<>();
        for (Map<?, ?> m : root.getMapList("belt.speed-levels")) {
            Object speed = m.get("speed");
            Object price = m.get("price");
            if (speed instanceof Number s && price instanceof Number p) {
                levels.add(new SpeedLevel(s.doubleValue(), Money.toCents(p.doubleValue())));
            }
        }
        if (levels.isEmpty()) {
            double[][] d = {{0.75, 150}, {1.0, 400}, {1.25, 900}, {1.5, 2000}, {2.0, 4500}};
            for (double[] l : d) {
                levels.add(new SpeedLevel(l[0], Money.toCents(l[1])));
            }
        }
        Material door = Material.matchMaterial(root.getString("gate.door-block", "light_gray_concrete"));
        Material item = Material.matchMaterial(root.getString("belt.item", "raw_gold"));
        return new FactorySettings(
                Math.max(1, root.getInt("gate.close-seconds", 45)),
                door != null && door.isBlock() ? door : Material.LIGHT_GRAY_CONCRETE,
                Math.max(0.05, root.getDouble("belt.start-speed", 0.5)),
                List.copyOf(levels),
                Math.max(1, root.getInt("belt.length-step", 2)),
                Money.toCents(Math.max(0, root.getDouble("belt.length-base-price", 100))),
                Math.max(1.0, root.getDouble("belt.length-price-growth", 1.3)),
                Math.max(0.05, root.getDouble("belt.gen-interval-seconds", 0.5)),
                Money.toCents(Math.max(0, root.getDouble("belt.item-value", 1.0))),
                Math.max(0, root.getDouble("belt.value-per-segment", 0.10)),
                Math.max(1, root.getInt("belt.deposit-every-seconds", 5)),
                item != null && item.isItem() ? item : Material.RAW_GOLD,
                Math.max(0, root.getInt("visuals.max-items-drawn", 24)),
                Math.max(16, root.getInt("visuals.view-distance", 64)),
                (float) root.getDouble("visuals.model-yaw-offset", 0),
                new Pack(root.getString("resource-pack.url", ""), root.getString("resource-pack.sha1", ""),
                        root.getBoolean("resource-pack.required", false),
                        root.getString("resource-pack.prompt", "GensCore needs its resource pack for conveyor belts.")));
    }
}
