package me.Evan52098.genscore.pets;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;

record PetsSettings(int defaultSlots, int maxSlots, int storage, int maxLevel, double perLevel, double goldenMultiplier,
                    int xpPerMinute, int xpPerLevel, int treatTokens, int treatXp, int[] releaseTokens,
                    double[] cacheEggChance, double eggUpgradeChance, long[] eggPrices, boolean announceLegendary,
                    Map<String, PetDef> pets) {

    static final String[] RARITIES = {"common", "rare", "epic", "legendary"};
    static final String[] RARITY_NAMES = {"Common", "Rare", "Epic", "Legendary"};
    static final String[] RARITY_CODES = {"&7", "&9", "&5", "&6"};
    static final Material[] EGG_ICONS = {Material.TURTLE_SPAWN_EGG, Material.ALLAY_SPAWN_EGG, Material.ENDERMAN_SPAWN_EGG,
            Material.BLAZE_SPAWN_EGG};

    record PetDef(String id, String name, int rarity, Material icon, String perk, double value, int weight) {
    }

    static int rarity(@Nullable String name) {
        if (name == null) {
            return -1;
        }
        String n = name.toLowerCase(Locale.ROOT);
        for (int i = 0; i < RARITIES.length; i++) {
            if (RARITIES[i].equals(n)) {
                return i;
            }
        }
        return -1;
    }

    static PetsSettings load(File file, Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        Map<String, PetDef> pets = new LinkedHashMap<>();
        ConfigurationSection ps = y.getConfigurationSection("pets");
        if (ps != null) {
            for (String id : ps.getKeys(false)) {
                ConfigurationSection p = ps.getConfigurationSection(id);
                if (p == null) {
                    continue;
                }
                int rarity = rarity(p.getString("rarity"));
                Material icon = Material.matchMaterial(p.getString("icon", ""));
                if (rarity < 0 || icon == null || !icon.isItem()) {
                    log.warning("[pets] Pet " + id + " needs a rarity (common/rare/epic/legendary) and an item icon; skipped.");
                    continue;
                }
                pets.put(id.toLowerCase(Locale.ROOT), new PetDef(id.toLowerCase(Locale.ROOT), p.getString("name", id), rarity, icon,
                        p.getString("perk", "sale-value"), Math.max(0, p.getDouble("value", 0.01)), Math.max(1, p.getInt("weight", 10))));
            }
        }
        return new PetsSettings(
                Math.max(1, y.getInt("slots.default", 2)),
                Math.max(1, Math.min(5, y.getInt("slots.max", 5))),
                Math.max(5, Math.min(45, y.getInt("storage", 36))),
                Math.max(1, y.getInt("max-level", 20)),
                Math.max(0, y.getDouble("per-level", 0.05)),
                Math.max(1, y.getDouble("golden-multiplier", 1.5)),
                Math.max(0, y.getInt("xp.per-minute", 1)),
                Math.max(1, y.getInt("xp.per-level", 20)),
                Math.max(1, y.getInt("xp.treat-tokens", 10)),
                Math.max(1, y.getInt("xp.treat-xp", 40)),
                ints(y.getIntegerList("release-tokens"), new int[]{2, 6, 15, 40}),
                doubles(y.getDoubleList("egg-drops.cache-chance"), new double[]{0.04, 0.07, 0.12, 0.20}),
                Math.max(0, y.getDouble("egg-drops.egg-upgrade-chance", 0.10)),
                longs(y.getLongList("egg-prices"), new long[]{100, 250, 600, 1500}),
                y.getBoolean("announce-legendary", true),
                Map.copyOf(pets));
    }

    private static int[] ints(List<Integer> list, int[] fallback) {
        if (list.size() < 4) {
            return fallback;
        }
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Math.max(0, list.get(i));
        }
        return out;
    }

    private static long[] longs(List<Long> list, long[] fallback) {
        if (list.size() < 4) {
            return fallback;
        }
        long[] out = new long[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Math.max(0, list.get(i));
        }
        return out;
    }

    private static double[] doubles(List<Double> list, double[] fallback) {
        if (list.size() < 4) {
            return fallback;
        }
        double[] out = new double[4];
        for (int i = 0; i < 4; i++) {
            out[i] = Math.max(0, Math.min(1, list.get(i)));
        }
        return out;
    }

    @Nullable PetDef pet(String id) {
        return pets.get(id);
    }

    @Nullable PetDef roll(int rarity) {
        List<PetDef> pool = new ArrayList<>();
        int total = 0;
        for (PetDef d : pets.values()) {
            if (d.rarity() == rarity) {
                pool.add(d);
                total += d.weight();
            }
        }
        if (pool.isEmpty()) {
            return null;
        }
        int pick = ThreadLocalRandom.current().nextInt(total);
        for (PetDef d : pool) {
            pick -= d.weight();
            if (pick < 0) {
                return d;
            }
        }
        return pool.get(pool.size() - 1);
    }

    double value(PetDef def, int level, boolean golden) {
        return def.value() * (1 + perLevel * (Math.max(1, level) - 1)) * (golden ? goldenMultiplier : 1);
    }

    int xpToNext(int level) {
        return xpPerLevel * Math.max(1, level);
    }

    static String perkName(String key) {
        return switch (key) {
            case "sale-value" -> "sale value";
            case "belt-speed" -> "belt speed";
            case "luck" -> "drop luck";
            case "token-double" -> "double token chance";
            case "cache-money" -> "cache money";
            case "cache-upgrade" -> "cache upgrade chance";
            case "raid-loot" -> "raid loot";
            case "raid-damage" -> "raid damage";
            case "raid-defense" -> "raid defence";
            case "turret-damage" -> "turret damage";
            case "gate-seconds" -> "gate time";
            case "repair-speed" -> "repair speed";
            default -> key;
        };
    }

    static String perkValue(String key, double value) {
        if (key.equals("gate-seconds")) {
            return "+" + String.format(Locale.ROOT, "%.1f", value) + "s";
        }
        return "+" + String.format(Locale.ROOT, "%.1f", value * 100) + "%";
    }
}
