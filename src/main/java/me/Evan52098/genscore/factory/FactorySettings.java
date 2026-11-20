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
 * @param genTiers           what the gen makes at each tier: the item, its base value and the price of the tier
 * @param valuePerSegment    each belt segment an item rides adds this fraction of its base value
 * @param depositSeconds     earnings are paid into the owner's balance this often (one ledger entry each time)
 */
record FactorySettings(int gateCloseSeconds, Material doorBlock, double startSpeed, List<SpeedLevel> speedLevels,
                       int lengthStep, long lengthBasePrice, double lengthGrowth, double genIntervalSeconds,
                       List<GenTier> genTiers, double valuePerSegment, int depositSeconds,
                       int maxDrawnItems, int viewDistance, float modelYawOffset, Pack pack) {

    record SpeedLevel(double speed, long price) {
    }

    record GenTier(String name, Material item, long valueCents, long price) {
    }

    GenTier tier(int level) {
        return genTiers.get(Math.max(0, Math.min(level, genTiers.size() - 1)));
    }

    /** Price of going from gen tier {@code level} to the next, or -1 at the top. */
    long nextTierPrice(int level) {
        return level + 1 < genTiers.size() ? genTiers.get(level + 1).price() : -1;
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

    /** What one item from gen tier {@code tier} is worth after riding {@code segments} segments, in cents. */
    long itemValue(int segments, int tier) {
        return Math.round(tier(tier).valueCents() * (1 + valuePerSegment * segments));
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
        List<GenTier> tiers = new ArrayList<>();
        for (Map<?, ?> m : root.getMapList("gen.tiers")) {
            Material item = m.get("item") == null ? null : Material.matchMaterial(String.valueOf(m.get("item")));
            Object value = m.get("value");
            Object price = m.get("price");
            if (item != null && item.isItem() && value instanceof Number v) {
                tiers.add(new GenTier(String.valueOf(m.get("name") == null ? item.name() : m.get("name")), item,
                        Money.toCents(v.doubleValue()), price instanceof Number p ? Money.toCents(p.doubleValue()) : 0));
            }
        }
        if (tiers.isEmpty()) {
            tiers.add(new GenTier("Raw Copper", Material.RAW_COPPER, 100, 0));
            tiers.add(new GenTier("Raw Iron", Material.RAW_IRON, 250, Money.toCents(500)));
            tiers.add(new GenTier("Raw Gold", Material.RAW_GOLD, 600, Money.toCents(2500)));
            tiers.add(new GenTier("Emerald", Material.EMERALD, 1500, Money.toCents(10000)));
            tiers.add(new GenTier("Diamond", Material.DIAMOND, 4000, Money.toCents(40000)));
            tiers.add(new GenTier("Netherite Scrap", Material.NETHERITE_SCRAP, 10000, Money.toCents(150000)));
        }
        return new FactorySettings(
                Math.max(1, root.getInt("gate.close-seconds", 45)),
                door != null && door.isBlock() ? door : Material.LIGHT_GRAY_CONCRETE,
                Math.max(0.05, root.getDouble("belt.start-speed", 0.5)),
                List.copyOf(levels),
                Math.max(1, root.getInt("belt.length-step", 2)),
                Money.toCents(Math.max(0, root.getDouble("belt.length-base-price", 100))),
                Math.max(1.0, root.getDouble("belt.length-price-growth", 1.3)),
                Math.max(0.05, root.getDouble("belt.gen-interval-seconds", 0.5)),
                List.copyOf(tiers),
                Math.max(0, root.getDouble("belt.value-per-segment", 0.10)),
                Math.max(1, root.getInt("belt.deposit-every-seconds", 5)),
                Math.max(0, root.getInt("visuals.max-items-drawn", 24)),
                Math.max(16, root.getInt("visuals.view-distance", 64)),
                (float) root.getDouble("visuals.model-yaw-offset", 0),
                new Pack(root.getString("resource-pack.url", ""), root.getString("resource-pack.sha1", ""),
                        root.getBoolean("resource-pack.required", false),
                        root.getString("resource-pack.prompt", "GensCore needs its resource pack for conveyor belts.")));
    }
}
