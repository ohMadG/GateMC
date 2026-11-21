package me.Evan52098.genscore.cosmetics;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Everything read from {@code modules/cosmetics/cosmetics.yml}. Immutable: a reload builds a new one and swaps it in,
 * so readers on any thread always see one complete version.
 */
final class CosmeticsConfig {

    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    final Map<CosmeticCategory, Map<String, Cosmetic>> cosmetics;
    final int auraIntervalTicks;
    final boolean hideWhenInvisible;
    final long glowCycleMillis;
    final String lockedHint;
    final int homeIconMax;
    final List<Material> homeIconChoices;
    final boolean homeIconAllowHeld;

    private CosmeticsConfig(ConfigurationSection root, Logger log) {
        ConfigurationSection s = section(root, "settings");
        auraIntervalTicks = clamp(s.getInt("auras.interval-ticks", 3), 2, 20);
        hideWhenInvisible = s.getBoolean("hide-when-invisible", true);
        glowCycleMillis = Math.max(250L, Math.round(s.getDouble("glows.cycle-seconds", 1.0) * 1000));
        lockedHint = s.getString("locked-hint", "&7Unlock it from crates.");

        ConfigurationSection h = section(root, "home-icons");
        homeIconMax = clamp(h.getInt("max-per-player", 32), 1, 200);
        homeIconAllowHeld = h.getBoolean("allow-held-item", true);
        List<Material> choices = new ArrayList<>();
        for (String name : h.getStringList("choices")) {
            Material m = Material.matchMaterial(name);
            if (m != null && m.isItem() && !m.isAir() && !choices.contains(m)) {
                choices.add(m);
            } else {
                log.warning("[cosmetics] home-icons.choices: '" + name + "' is not an item, skipped");
            }
        }
        homeIconChoices = List.copyOf(choices);

        Map<CosmeticCategory, Map<String, Cosmetic>> all = new EnumMap<>(CosmeticCategory.class);
        ConfigurationSection defs = section(root, "cosmetics");
        for (CosmeticCategory cat : CosmeticCategory.values()) {
            Map<String, Cosmetic> byId = new LinkedHashMap<>();
            ConfigurationSection catSec = defs.getConfigurationSection(cat.key());
            if (catSec != null) {
                for (String id : catSec.getKeys(false)) {
                    ConfigurationSection c = catSec.getConfigurationSection(id);
                    if (c == null) {
                        continue;
                    }
                    String where = cat.key() + "." + id;
                    if (!ID.matcher(id).matches()) {
                        log.warning("[cosmetics] " + where + ": ids may only use a-z, 0-9, _ and - (up to 32), skipped");
                        continue;
                    }
                    try {
                        byId.put(id, parse(cat, id, c));
                    } catch (IllegalArgumentException e) {
                        log.warning("[cosmetics] " + where + ": " + e.getMessage() + ", skipped");
                    }
                }
            }
            all.put(cat, Collections.unmodifiableMap(byId));
        }
        cosmetics = Collections.unmodifiableMap(all);
    }

    static CosmeticsConfig load(File file, Logger log) {
        YamlConfiguration yaml = new YamlConfiguration();
        if (file.isFile()) {
            try {
                yaml.load(file);
            } catch (Exception e) {
                log.severe("[cosmetics] " + file.getName() + " could not be read (" + e.getMessage() + "); no cosmetics loaded");
            }
        }
        return new CosmeticsConfig(yaml, log);
    }

    @Nullable Cosmetic get(CosmeticCategory category, @Nullable String id) {
        return id == null ? null : cosmetics.get(category).get(id);
    }

    // ------------------------------------------------------------------------------------------- parsing

    private static Cosmetic parse(CosmeticCategory cat, String id, ConfigurationSection c) {
        String name = c.getString("name", id);
        String permission = c.getString("permission", "");
        if (permission == null || permission.isBlank()) {
            permission = "genscore.cosmetics." + cat.key() + "." + id;
        }
        if (c.getBoolean("free", false)) {
            permission = ""; // everyone owns it
        }
        Material icon = Material.matchMaterial(c.getString("icon", cat.icon().name()));
        if (icon == null || !icon.isItem() || icon.isAir()) {
            icon = cat.icon();
        }
        List<String> description = List.copyOf(c.getStringList("description"));
        String message = null;
        String color = null;
        Cosmetic.Effect effect = null;
        long price = Math.max(0, c.getLong("price", 0));
        switch (cat) {
            case JOIN_MESSAGE, LEAVE_MESSAGE -> {
                message = c.getString("message");
                if (message == null || message.isBlank() || message.length() > 256) {
                    throw new IllegalArgumentException("needs a message (up to 256 characters)");
                }
            }
            case CHAT_TAG -> {
                message = c.getString("display");
                if (message == null || message.isBlank() || message.length() > 200) {
                    throw new IllegalArgumentException("needs a display (the tag text, up to 200 characters)");
                }
            }
            case GLOW -> {
                color = c.getString("color", "").toLowerCase(Locale.ROOT);
                if (!color.matches("[0-9a-f]{1,16}")) {
                    throw new IllegalArgumentException("needs a color: legacy colour codes 0-9 and a-f, several to cycle (e.g. \"b\" or \"c6e\")");
                }
            }
            case NAME_COLOR, CHAT_COLOR -> {
                color = c.getString("color");
                if (color == null || color.isBlank() || color.length() > 200) {
                    throw new IllegalArgumentException("needs a color (a MiniMessage tag such as <red> or <gradient:#ff0000:#ffaa00>)");
                }
                try {
                    MM.deserialize(color + "Name");
                } catch (Exception e) {
                    throw new IllegalArgumentException("color is not valid MiniMessage");
                }
            }
            case AURA -> effect = parseEffect(section(c, "effect"));
        }
        return new Cosmetic(cat, id, name, permission, icon, description, message, color, effect, price);
    }

    private static Cosmetic.Effect parseEffect(ConfigurationSection e) {
        String particleName = e.getString("particle", "FLAME");
        Particle particle;
        try {
            particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown particle " + particleName);
        }
        if (!Cosmetic.Effect.supported(particle)) {
            throw new IllegalArgumentException("particle " + particleName + " needs block/item data, which is not supported");
        }
        int count = clamp(e.getInt("count", 1), 1, 6);
        double spread = clampD(e.getDouble("spread", 0), 0, 0.5);
        double speed = clampD(e.getDouble("speed", 0), 0, 0.1);
        double offsetY = clampD(e.getDouble("offset-y", 0), -1, 3);
        List<Color> colors = new ArrayList<>();
        for (String hex : e.getStringList("colors")) {
            Color col = parseColor(hex);
            if (col == null) {
                throw new IllegalArgumentException("bad colour " + hex + " (use #rrggbb)");
            }
            colors.add(col);
        }
        float size = (float) clampD(e.getDouble("size", 1.0), 0.1, 4);
        return new Cosmetic.Effect(particle, count, spread, speed, offsetY, List.copyOf(colors), size, null, 1f, 1f, false);
    }

    private static @Nullable Color parseColor(String hex) {
        String h = hex.trim();
        if (h.startsWith("#")) {
            h = h.substring(1);
        }
        if (!h.matches("[0-9a-fA-F]{6}")) {
            return null;
        }
        return Color.fromRGB(Integer.parseInt(h, 16));
    }

    private static ConfigurationSection section(ConfigurationSection parent, String key) {
        ConfigurationSection s = parent.getConfigurationSection(key);
        return s != null ? s : new MemoryConfiguration();
    }

    private static int clamp(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clampD(double v, double min, double max) {
        return Double.isFinite(v) ? Math.max(min, Math.min(max, v)) : min;
    }
}
