package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.util.Money;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crate definitions ({@code modules/keycrates/crates.yml}) and settings ({@code keycrates:} in config.yml). Immutable:
 * a reload builds a new one and swaps it in, so a roll or a menu always sees one consistent version.
 */
final class CrateConfig {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final Pattern GIVE_COUNT = Pattern.compile("^(.*\\S)\\s+(\\d{1,4})$");
    private static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");

    /** One possible prize. {@code chance} is a percentage of the crate's total weight. */
    record Reward(String id, double weight, double chance, String name, ItemStack icon, List<ItemStack> items,
                  List<String> commands, long moneyCents, long shards, Map<String, Integer> keys) {
    }

    record Crate(String id, String name, String keyName, Material keyMaterial, @Nullable NamespacedKey keyModel,
                 ItemStack icon, int menuSlot, List<String> hologram, double hologramHeight, List<String> locations,
                 boolean noDuplicates, List<Reward> rewards, double totalWeight) {

        @Nullable Reward reward(String rewardId) {
            for (Reward r : rewards) {
                if (r.id().equals(rewardId)) {
                    return r;
                }
            }
            return null;
        }

        /** A weighted random prize, skipping {@code exclude} (no-duplicates crates). Null when nothing is left. */
        @Nullable Reward roll(Set<String> exclude) {
            double total = 0;
            for (Reward r : rewards) {
                if (!exclude.contains(r.id())) {
                    total += r.weight();
                }
            }
            if (total <= 0) {
                return null;
            }
            double pick = ThreadLocalRandom.current().nextDouble(total);
            Reward last = null;
            for (Reward r : rewards) {
                if (exclude.contains(r.id())) {
                    continue;
                }
                last = r;
                pick -= r.weight();
                if (pick < 0) {
                    return r;
                }
            }
            return last; // floating-point edge
        }
    }

    record Rarity(double min, String label) {
    }

    // ------------------------------------------------------------------ settings

    final String menuTitle;
    final @Nullable String playtimeCrate;
    final int playtimeMinutes;
    final boolean playtimeCountAfk;
    final @Nullable String voteCrate;
    final int voteKeys;
    final long voteShards;
    final String voteMessage;
    final double broadcastUnder;
    final String broadcast;
    final String broadcastRare;
    final String broadcastBulk;
    final int rareLinesMax;
    final List<Rarity> rarities;
    final Map<String, Long> retiredKeys;
    final int openAllMax;
    final long openCooldownMs;
    final boolean animation;
    final boolean welcomeEnabled;
    final @Nullable String welcomeCrate;
    final int welcomeDelaySeconds;
    final String welcomeMessage;
    final int claimRowsPerUse;

    final Map<String, Crate> crates;

    private CrateConfig(ConfigurationSection s, Map<String, Crate> crates, Logger logger) {
        this.crates = crates;
        menuTitle = s.getString("menu-title", "&c&l❄ &f&lCRATES &c&l❄");
        playtimeCrate = known(s.getString("playtime.crate", "amethyst"), crates, "playtime.crate", logger);
        playtimeMinutes = Math.max(1, Math.min(24 * 60, s.getInt("playtime.minutes", 60)));
        playtimeCountAfk = s.getBoolean("playtime.count-afk", false);
        voteCrate = known(s.getString("vote.crate", ""), crates, "vote.crate", logger);
        voteKeys = Math.max(0, Math.min(64, s.getInt("vote.keys", 0)));
        voteShards = Math.max(0, Math.min(1_000_000, s.getLong("vote.shards", 30)));
        voteMessage = s.getString("vote.message", "&a&lVOTE &8» &7Thanks for voting! &b+{shards} shards{keys}");
        broadcastUnder = s.getDouble("broadcast-under", 5.0);
        broadcast = s.getString("broadcast", "&5&lCRATES &8» &f{player} &7opened a {crate} &7and won {reward}&7!");
        broadcastRare = s.getString("broadcast-rare", "&d&l✦ RARE WIN ✦ &f{player} &7won {reward} &7from the {crate}&7! &8({chance}%)");
        broadcastBulk = s.getString("broadcast-bulk", "&5&lCRATES &8» &f{player} &7opened &f{amount}x {crate}&7!");
        rareLinesMax = Math.max(0, Math.min(20, s.getInt("bulk-rare-lines", 5)));
        List<Rarity> list = new ArrayList<>();
        for (Map<?, ?> m : s.getMapList("rarities")) {
            Object min = m.get("min");
            Object label = m.get("label");
            if (min instanceof Number n && label != null) {
                list.add(new Rarity(n.doubleValue(), label.toString()));
            }
        }
        if (list.isEmpty()) {
            list = List.of(new Rarity(15, "&7&lCOMMON"), new Rarity(6, "&a&lUNCOMMON"), new Rarity(2, "&9&lRARE"),
                    new Rarity(0.5, "&5&lEPIC"), new Rarity(0, "&6&lLEGENDARY"));
        }
        list.sort(Comparator.comparingDouble(Rarity::min).reversed());
        rarities = List.copyOf(list);
        Map<String, Long> retired = new LinkedHashMap<>();
        ConfigurationSection r = s.getConfigurationSection("retired-keys");
        if (r != null) {
            for (String key : r.getKeys(false)) {
                long each = Math.max(0, Math.min(1_000_000, r.getLong(key)));
                if (each > 0) {
                    retired.put(key.toLowerCase(Locale.ROOT), each);
                }
            }
        }
        retiredKeys = Map.copyOf(retired);
        openAllMax = Math.max(1, Math.min(512, s.getInt("open-all-max", 128)));
        openCooldownMs = Math.max(0, Math.min(10_000, s.getLong("open-cooldown-ms", 600)));
        animation = s.getBoolean("animation", true);
        welcomeEnabled = s.getBoolean("welcome-spin.enabled", true);
        welcomeCrate = known(s.getString("welcome-spin.crate", "vote"), crates, "welcome-spin.crate", logger);
        welcomeDelaySeconds = Math.max(1, Math.min(120, s.getInt("welcome-spin.delay-seconds", 5)));
        welcomeMessage = s.getString("welcome-spin.message", "&d&lWELCOME! &7Here's a free spin of the {crate}&7!");
        claimRowsPerUse = Math.max(1, Math.min(200, s.getInt("claim-rows-per-use", 100)));
    }

    private static @Nullable String known(@Nullable String id, Map<String, Crate> crates, String path, Logger logger) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        if (!crates.containsKey(lower)) {
            logger.warning("[keycrates] keycrates." + path + " names crate '" + id + "', which is not in crates.yml; it is ignored.");
            return null;
        }
        return lower;
    }

    @Nullable Crate crate(@Nullable String id) {
        return id == null ? null : crates.get(id.toLowerCase(Locale.ROOT));
    }

    Collection<Crate> all() {
        return crates.values();
    }

    /** Shards each key of a closed crate is worth, or 0 if that crate is not retired (or still open). */
    long retiredValue(String crateId) {
        if (crates.containsKey(crateId)) {
            return 0;
        }
        return retiredKeys.getOrDefault(crateId, 0L);
    }

    String rarity(double chance) {
        for (Rarity r : rarities) {
            if (chance >= r.min()) {
                return r.label();
            }
        }
        return rarities.isEmpty() ? "" : rarities.get(rarities.size() - 1).label();
    }

    static String chanceText(double chance) {
        if (chance >= 10) {
            return String.format(Locale.ROOT, "%.1f", chance);
        }
        if (chance >= 0.1) {
            return String.format(Locale.ROOT, "%.2f", chance);
        }
        return String.format(Locale.ROOT, "%.3f", chance);
    }

    static Component text(String legacy) {
        return LEGACY.deserialize(legacy == null ? "" : legacy).decoration(TextDecoration.ITALIC, false);
    }

    // ------------------------------------------------------------------ loading

    /** Reads both files. Writes the default crates.yml first if there is none. Call on the global or startup thread. */
    static CrateConfig load(@Nullable ConfigurationSection settings, File folder, Logger logger) {
        File file = new File(folder, "crates.yml");
        if (!file.isFile()) {
            try {
                Files.createDirectories(folder.toPath());
                Files.writeString(file.toPath(), CrateDefaults.CRATES_YML, StandardCharsets.UTF_8);
                logger.info("[keycrates] Wrote the default modules/keycrates/crates.yml");
            } catch (IOException e) {
                logger.warning("[keycrates] Could not write the default crates.yml: " + e.getMessage());
            }
        }
        YamlConfiguration yml = file.isFile() ? YamlConfiguration.loadConfiguration(file) : new YamlConfiguration();
        if (!file.isFile()) {
            try {
                yml.loadFromString(CrateDefaults.CRATES_YML);
            } catch (Exception e) {
                logger.warning("[keycrates] The built-in crates.yml did not load: " + e.getMessage());
            }
        }
        Map<String, Crate> crates = new LinkedHashMap<>();
        ConfigurationSection root = yml.getConfigurationSection("crates");
        if (root != null) {
            for (String rawId : root.getKeys(false)) {
                String id = rawId.toLowerCase(Locale.ROOT);
                ConfigurationSection c = root.getConfigurationSection(rawId);
                if (c == null || !ID.matcher(id).matches()) {
                    logger.warning("[keycrates] Skipped crate '" + rawId + "': ids are 1-32 of a-z, 0-9, _ and -.");
                    continue;
                }
                if (!c.getBoolean("enabled", true)) {
                    continue; // a closed crate: its keys count as retired
                }
                Crate crate = crate(id, c, logger);
                if (crate != null) {
                    crates.put(id, crate);
                }
            }
        }
        if (crates.isEmpty()) {
            logger.warning("[keycrates] No crates are defined in modules/keycrates/crates.yml.");
        }
        return new CrateConfig(settings != null ? settings : new MemoryConfiguration(), Map.copyOf(crates), logger);
    }

    private static @Nullable Crate crate(String id, ConfigurationSection c, Logger logger) {
        String name = c.getString("name", "&f" + id + " crate");
        ConfigurationSection rs = c.getConfigurationSection("rewards");
        if (rs == null) {
            logger.warning("[keycrates] Crate '" + id + "' has no rewards; skipped.");
            return null;
        }
        // First pass: weights, so each reward knows its chance.
        double total = 0;
        for (String rid : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(rid);
            if (r != null) {
                double w = r.getDouble("weight", 0);
                if (w > 0 && !Double.isNaN(w) && !Double.isInfinite(w)) {
                    total += w;
                }
            }
        }
        if (total <= 0) {
            logger.warning("[keycrates] Crate '" + id + "' has no reward with a weight above 0; skipped.");
            return null;
        }
        List<Reward> rewards = new ArrayList<>();
        for (String rid : rs.getKeys(false)) {
            ConfigurationSection r = rs.getConfigurationSection(rid);
            if (r == null) {
                continue;
            }
            double w = r.getDouble("weight", 0);
            if (!(w > 0) || Double.isInfinite(w)) {
                continue;
            }
            Reward reward = reward(id, rid.toLowerCase(Locale.ROOT), r, w, w / total * 100.0, logger);
            if (reward != null) {
                rewards.add(reward);
            }
        }
        if (rewards.isEmpty()) {
            return null;
        }
        Material keyMat = material(c.getString("key-material", "TRIPWIRE_HOOK"), Material.TRIPWIRE_HOOK);
        Material iconMat = material(c.getString("icon", "CHEST"), Material.CHEST);
        ItemStack icon = icon(iconMat, 1, name, List.of(), true, c.getString("icon-model"));
        double height = c.getDouble("hologram-height", 1.6);
        return new Crate(id, name, c.getString("key", "&f" + id + " Key"), keyMat, key(c.getString("key-model")), icon,
                c.getInt("menu-slot", -1), List.copyOf(c.getStringList("hologram")),
                Double.isNaN(height) ? 1.6 : Math.max(0.5, Math.min(5, height)),
                List.copyOf(c.getStringList("locations")), c.getBoolean("no-duplicates", false), List.copyOf(rewards), total);
    }

    private static @Nullable Reward reward(String crate, String id, ConfigurationSection r, double weight, double chance, Logger logger) {
        String name = r.getString("name", "&f" + id);
        List<ItemStack> items = new ArrayList<>();
        for (String give : r.getStringList("give")) {
            List<ItemStack> stacks = parseGive(give);
            if (stacks.isEmpty()) {
                logger.warning("[keycrates] Crate '" + crate + "' reward '" + id + "': could not read item '" + give + "'; that item is left out.");
            } else {
                items.addAll(stacks);
            }
        }
        long cents = 0;
        if (r.contains("money")) {
            double money = r.getDouble("money", 0);
            if (money > 0 && money <= 1_000_000_000 && !Double.isNaN(money)) {
                cents = Money.toCents(money);
            }
        }
        long shards = Math.max(0, Math.min(1_000_000_000L, r.getLong("shards", 0)));
        Map<String, Integer> keys = new LinkedHashMap<>();
        ConfigurationSection k = r.getConfigurationSection("keys");
        if (k != null) {
            for (String kc : k.getKeys(false)) {
                int n = Math.max(0, Math.min(64, k.getInt(kc)));
                if (n > 0) {
                    keys.put(kc.toLowerCase(Locale.ROOT), n);
                }
            }
        }
        List<String> commands = new ArrayList<>();
        for (String cmd : r.getStringList("commands")) {
            String trimmed = cmd.trim();
            if (trimmed.startsWith("/")) {
                trimmed = trimmed.substring(1);
            }
            if (!trimmed.isEmpty()) {
                commands.add(trimmed);
            }
        }
        if (items.isEmpty() && commands.isEmpty() && cents == 0 && shards == 0 && keys.isEmpty()) {
            logger.warning("[keycrates] Crate '" + crate + "' reward '" + id + "' gives nothing; skipped.");
            return null;
        }
        Material iconMat = material(r.getString("icon"), items.isEmpty() ? Material.CHEST : items.get(0).getType());
        ItemStack icon = icon(iconMat, Math.max(1, Math.min(64, r.getInt("amount", 1))), name, r.getStringList("lore"),
                r.getBoolean("glow", false), r.getString("model"));
        return new Reward(id, weight, chance, name, icon, List.copyOf(items), List.copyOf(commands), cents, shards, Map.copyOf(keys));
    }

    /**
     * A {@code /give}-style item: {@code minecraft:diamond_sword[enchantments={sharpness:2}]} with an optional count
     * after a space. Uses the server's own item parser, so components work as in the /give command. The count is split
     * into normal-sized stacks (checklist 9: never an over-stacked item). Empty when the text is not an item.
     */
    static List<ItemStack> parseGive(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String spec = raw.trim();
        int count = 1;
        Matcher m = GIVE_COUNT.matcher(spec);
        if (m.matches()) {
            String head = m.group(1);
            if (!head.contains("[") || head.endsWith("]")) { // the number is a count, not part of a component
                spec = head;
                count = Integer.parseInt(m.group(2));
            }
        }
        try {
            ItemStack item = Bukkit.getItemFactory().createItemStack(spec);
            if (item.isEmpty() || count < 1) {
                return List.of();
            }
            int left = Math.min(count, 64 * 27);
            int max = Math.max(1, item.getMaxStackSize());
            List<ItemStack> stacks = new ArrayList<>();
            while (left > 0) {
                ItemStack s = item.clone();
                s.setAmount(Math.min(max, left));
                left -= s.getAmount();
                stacks.add(s);
            }
            return stacks;
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private static Material material(@Nullable String name, Material fallback) {
        if (name == null) {
            return fallback;
        }
        Material m = Material.matchMaterial(name);
        return m != null && m.isItem() && !m.isAir() ? m : fallback;
    }

    private static @Nullable NamespacedKey key(@Nullable String text) {
        return text == null || text.isBlank() ? null : NamespacedKey.fromString(text.toLowerCase(Locale.ROOT));
    }

    private static ItemStack icon(Material mat, int amount, String name, List<String> lore, boolean glow, @Nullable String model) {
        ItemStack item = new ItemStack(mat, amount);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(text(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(text(l));
        }
        meta.lore(lines);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        NamespacedKey m = key(model);
        if (m != null) {
            meta.setItemModel(m);
        }
        item.setItemMeta(meta);
        return item;
    }
}
