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

    /**
     * Gen tiers made from {@code gen.generate}: {@code count} tiers spread evenly over the {@code items} list in order
     * (each item gets a few tiers: "Coal I", "Coal II", ...). Tier {@code t} (from 0) is worth
     * {@code base-value * value-growth^t} and costs {@code base-price * price-growth^(t-1)}; the first tier is free.
     */
    static List<GenTier> generateTiers(ConfigurationSection root) {
        List<Material> items = new ArrayList<>();
        for (String name : root.getStringList("gen.generate.items")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isItem()) {
                items.add(m);
            }
        }
        if (items.isEmpty()) {
            for (String name : DEFAULT_ITEMS) {
                Material m = Material.matchMaterial(name);
                if (m != null && m.isItem()) {
                    items.add(m);
                }
            }
        }
        int count = Math.max(1, root.getInt("gen.generate.count", 150));
        double baseValue = Math.max(0.01, root.getDouble("gen.generate.base-value", 1));
        double valueGrowth = Math.max(1, root.getDouble("gen.generate.value-growth", 1.12));
        double basePrice = Math.max(0, root.getDouble("gen.generate.base-price", 300));
        double priceGrowth = Math.max(1, root.getDouble("gen.generate.price-growth", 1.135));
        List<GenTier> out = new ArrayList<>(count);
        // Spread the tiers evenly so every item is used: item i covers tiers [first(i), first(i + 1)).
        java.util.function.IntUnaryOperator first = i -> (int) Math.ceil((double) i * count / items.size());
        for (int t = 0; t < count; t++) {
            int itemIndex = (int) Math.min(items.size() - 1, (long) t * items.size() / count);
            int sub = t - first.applyAsInt(itemIndex);
            int span = first.applyAsInt(itemIndex + 1) - first.applyAsInt(itemIndex);
            Material item = items.get(itemIndex);
            String name = pretty(item) + (span > 1 ? " " + ROMAN[Math.max(0, Math.min(sub, ROMAN.length - 1))] : "");
            long value = Math.round(baseValue * Math.pow(valueGrowth, t) * 100);
            long price = t == 0 ? 0 : Math.round(basePrice * Math.pow(priceGrowth, t - 1) * 100);
            out.add(new GenTier(name, item, value, price));
        }
        return out;
    }

    private static final String[] ROMAN = {"I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X"};

    /** Items from plain to precious; generated tiers walk through them in this order. */
    static final List<String> DEFAULT_ITEMS = List.of(
            "cobblestone", "coal", "raw_copper", "copper_ingot", "raw_iron", "iron_ingot", "redstone", "lapis_lazuli",
            "raw_gold", "gold_ingot", "quartz", "amethyst_shard", "slime_ball", "honeycomb", "resin_clump",
            "armadillo_scute", "turtle_scute", "rabbit_foot", "glowstone_dust", "magma_cream", "prismarine_shard",
            "prismarine_crystals", "emerald", "diamond", "blaze_rod", "breeze_rod", "ender_pearl", "ghast_tear",
            "phantom_membrane", "golden_apple", "nautilus_shell", "copper_block", "iron_block", "gold_block",
            "trial_key", "ominous_trial_key", "shulker_shell", "echo_shard", "heart_of_the_sea", "dragon_breath",
            "emerald_block", "diamond_block", "netherite_scrap", "netherite_ingot", "end_crystal", "heavy_core",
            "totem_of_undying", "enchanted_golden_apple", "nether_star", "netherite_block", "beacon", "dragon_egg");

    private static String pretty(Material m) {
        StringBuilder sb = new StringBuilder();
        for (String w : m.name().toLowerCase(java.util.Locale.ROOT).split("_")) {
            sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
        }
        return sb.toString();
    }

    static FactorySettings from(ConfigurationSection root) {
        if (root == null) {
            root = new MemoryConfiguration();
        }
        double startSpeed = Math.max(0.05, root.getDouble("belt.start-speed", 0.5));
        List<SpeedLevel> levels = new ArrayList<>();
        for (Map<?, ?> m : root.getMapList("belt.speed-levels")) {
            Object speed = m.get("speed");
            Object price = m.get("price");
            if (speed instanceof Number s && price instanceof Number p) {
                levels.add(new SpeedLevel(s.doubleValue(), Money.toCents(p.doubleValue())));
            }
        }
        if (levels.isEmpty()) {
            // Generated: from 1x to belt.speed.max-multiplier times the start speed, in equal steps.
            double max = Math.max(1, root.getDouble("belt.speed.max-multiplier", 10));
            double step = Math.max(0.01, root.getDouble("belt.speed.step", 0.25));
            double price = Math.max(0, root.getDouble("belt.speed.base-price", 150));
            double growth = Math.max(1, root.getDouble("belt.speed.price-growth", 1.35));
            int n = 0;
            for (double mult = 1 + step; mult <= max + 1e-9; mult += step) {
                levels.add(new SpeedLevel(startSpeed * mult, Math.round(price * Math.pow(growth, n++) * 100)));
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
            tiers.addAll(generateTiers(root));
        }
        return new FactorySettings(
                Math.max(1, root.getInt("gate.close-seconds", 45)),
                door != null && door.isBlock() ? door : Material.LIGHT_GRAY_CONCRETE,
                startSpeed,
                List.copyOf(levels),
                Math.max(1, root.getInt("belt.length-step", 2)),
                Money.toCents(Math.max(0, root.getDouble("belt.length-base-price", 100))),
                Math.max(1.0, root.getDouble("belt.length-price-growth", 1.3)),
                Math.max(0.05, root.getDouble("belt.gen-interval-seconds", 0.1)),
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
