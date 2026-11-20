package me.Evan52098.genscore.factory;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.List;

/**
 * The progress, defence, raid and gear numbers: {@code factory.drops}, {@code factory.caches}, {@code factory.token-shop},
 * {@code factory.defences}, {@code factory.raid} and {@code factory.gear} in config.yml. Reloads live.
 */
record ProgressSettings(
        double tokenChance, double cacheChance, int[] rarityWeights,
        double[] cacheMinutes, int[][] cacheTokens, double[] keyChance, List<String> keyCrates,
        Upgrade value, Upgrade luck, long cacheTokenPrice,
        Upgrade gateTime, Upgrade armor, Upgrade turrets, Upgrade traps,
        int segmentHealth, long hitCooldownMs, int segmentsPerRaid, int shieldMinutes, int newPlayerShieldMinutes,
        int autoRepairSeconds, double repairCostMinutes, int repairLockSeconds, double baseLootShare, int tokensPerSegment,
        Upgrade gear, double lootSharePerGear, double turretRange, double turretDamage, double turretDamagePerLevel,
        int turretCooldownTicks) {

    /**
     * An upgrade with {@code max} levels. Level {@code n} (from 0) costs {@code basePrice * growth^n}; each level gives
     * {@code perLevel} of whatever the upgrade improves.
     */
    record Upgrade(int max, double basePrice, double growth, double perLevel) {
        /** Price of going from {@code level} to the next, or -1 at the top. */
        long price(int level) {
            return level >= max ? -1 : Math.round(basePrice * Math.pow(growth, level));
        }

        static Upgrade from(ConfigurationSection s, String path, int max, double base, double growth, double per) {
            return new Upgrade(Math.max(0, s.getInt(path + ".max-level", max)), Math.max(0, s.getDouble(path + ".base-price", base)),
                    Math.max(1, s.getDouble(path + ".price-growth", growth)), s.getDouble(path + ".per-level", per));
        }
    }

    static final String[] RARITY_NAMES = {"Common", "Rare", "Epic", "Legendary"};
    static final String[] RARITY_COLORS = {"<white>", "<aqua>", "<light_purple>", "<gold>"};

    int rollRarity(java.util.random.RandomGenerator r) {
        int total = 0;
        for (int w : rarityWeights) {
            total += Math.max(0, w);
        }
        int pick = r.nextInt(Math.max(1, total));
        for (int i = 0; i < rarityWeights.length; i++) {
            pick -= Math.max(0, rarityWeights[i]);
            if (pick < 0) {
                return i;
            }
        }
        return 0;
    }

    /** Belt segment health with this much armour. */
    int segmentHealth(int armorLevel) {
        return (int) Math.round(segmentHealth * (1 + armor.perLevel() * armorLevel));
    }

    /** Seconds the gate stays shut at a gate-time level. */
    int gateSeconds(int baseSeconds, int level) {
        return baseSeconds + (int) Math.round(gateTime.perLevel() * Math.min(level, gateTime.max()));
    }

    /** Raid damage per hit with this much gear. */
    int hitDamage(int gearLevel) {
        return 1 + (int) Math.round(gear.perLevel() * gearLevel);
    }

    /** Share of a broken segment's items the raider takes, with this much gear. */
    double lootShare(int gearLevel) {
        return Math.min(1.0, baseLootShare + lootSharePerGear * gearLevel);
    }

    static ProgressSettings from(ConfigurationSection root) {
        ConfigurationSection s = root != null ? root : new MemoryConfiguration();
        int[] weights = {
                s.getInt("caches.weights.common", 70), s.getInt("caches.weights.rare", 22),
                s.getInt("caches.weights.epic", 7), s.getInt("caches.weights.legendary", 1)};
        double[] minutes = {
                s.getDouble("caches.money-minutes.common", 2), s.getDouble("caches.money-minutes.rare", 10),
                s.getDouble("caches.money-minutes.epic", 30), s.getDouble("caches.money-minutes.legendary", 120)};
        int[][] tokens = {
                {s.getInt("caches.tokens.common.min", 1), s.getInt("caches.tokens.common.max", 3)},
                {s.getInt("caches.tokens.rare.min", 4), s.getInt("caches.tokens.rare.max", 10)},
                {s.getInt("caches.tokens.epic.min", 12), s.getInt("caches.tokens.epic.max", 25)},
                {s.getInt("caches.tokens.legendary.min", 40), s.getInt("caches.tokens.legendary.max", 80)}};
        double[] keys = {
                s.getDouble("caches.key-chance.common", 0.05), s.getDouble("caches.key-chance.rare", 0.2),
                s.getDouble("caches.key-chance.epic", 0.5), s.getDouble("caches.key-chance.legendary", 1.0)};
        List<String> crates = s.getStringList("caches.key-crates");
        return new ProgressSettings(
                Math.max(0, s.getDouble("drops.token-chance", 1 / 300.0)),
                Math.max(0, s.getDouble("drops.cache-chance", 1 / 900.0)),
                weights, minutes, tokens, keys, crates.isEmpty() ? List.of("amethyst") : List.copyOf(crates),
                Upgrade.from(s, "token-shop.value", 50, 15, 1.3, 0.02),
                Upgrade.from(s, "token-shop.luck", 20, 20, 1.35, 0.10),
                Math.max(1, s.getLong("token-shop.cache-price", 25)),
                Upgrade.from(s, "defences.gate-time", 15, 5, 1.35, 5),
                Upgrade.from(s, "defences.armor", 10, 8, 1.4, 0.5),
                Upgrade.from(s, "defences.turrets", 8, 15, 1.45, 1),
                Upgrade.from(s, "defences.traps", 5, 10, 1.5, 1),
                Math.max(1, s.getInt("raid.segment-health", 6)),
                Math.max(50, s.getLong("raid.hit-cooldown-ms", 250)),
                Math.max(1, s.getInt("raid.segments-per-raid", 3)),
                Math.max(0, s.getInt("raid.shield-minutes", 5)),
                Math.max(0, s.getInt("raid.new-player-shield-minutes", 15)),
                Math.max(5, s.getInt("raid.auto-repair-seconds", 90)),
                Math.max(0, s.getDouble("raid.repair-cost-minutes", 0.5)),
                Math.max(0, s.getInt("raid.repair-lock-seconds", 5)),
                Math.max(0, Math.min(1, s.getDouble("raid.loot-share", 0.4))),
                Math.max(0, s.getInt("raid.tokens-per-segment", 1)),
                Upgrade.from(s, "gear", 25, 2000, 1.6, 1),
                Math.max(0, s.getDouble("gear.loot-share-per-level", 0.025)),
                Math.max(4, s.getDouble("defences.turret-range", 18)),
                Math.max(0, s.getDouble("defences.turret-damage", 2)),
                Math.max(0, s.getDouble("defences.turret-damage-per-level", 0.5)),
                Math.max(5, s.getInt("defences.turret-cooldown-ticks", 40)));
    }
}
