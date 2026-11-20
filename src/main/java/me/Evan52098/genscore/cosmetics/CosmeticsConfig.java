package me.Evan52098.genscore.cosmetics;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Everything read from {@code modules/cosmetics/cosmetics.yml}. Immutable: a reload builds a new one and swaps it in,
 * so readers on any thread always see one complete version.
 */
final class CosmeticsConfig {

    static final Pattern ID = Pattern.compile("[a-z0-9_-]{1,32}");
    private static final MiniMessage MM = MiniMessage.miniMessage();

    record KillRank(int kills, String display) {
    }

    final Map<CosmeticCategory, Map<String, Cosmetic>> cosmetics;
    final List<KillRank> killRanks;          // sorted by kills, ascending
    final boolean sameIpKillsCount;
    final long sameVictimCooldownMillis;
    final int trailIntervalTicks;
    final double trailMinMoveSquared;
    final boolean trailsHideWhenInvisible;
    final boolean toolSkinsEnabled;
    final int toolSkinRefreshTicks;
    final String lockedHint;
    final int homeIconMax;
    final List<Material> homeIconChoices;
    final boolean homeIconAllowHeld;

    private CosmeticsConfig(ConfigurationSection root, Logger log) {
        ConfigurationSection s = section(root, "settings");
        trailIntervalTicks = clamp(s.getInt("trails.interval-ticks", 3), 2, 20);
        double move = Math.max(0, Math.min(2, s.getDouble("trails.min-move", 0.2)));
        trailMinMoveSquared = move * move;
        trailsHideWhenInvisible = s.getBoolean("trails.hide-when-invisible", true);
        toolSkinsEnabled = s.getBoolean("tool-skins.enabled", true);
        toolSkinRefreshTicks = clamp(s.getInt("tool-skins.refresh-ticks", 10), 5, 100);
        lockedHint = s.getString("locked-hint", "&7Unlock it on the store or from crates.");

        ConfigurationSection k = section(root, "kill-ranks");
        sameIpKillsCount = k.getBoolean("same-ip-kills-count", false);
        sameVictimCooldownMillis = Math.max(0, k.getLong("same-victim-cooldown-minutes", 10)) * 60_000L;
        List<KillRank> ranks = new ArrayList<>();
        for (Map<?, ?> m : k.getMapList("tiers")) {
            Object kills = m.get("kills");
            Object display = m.get("display");
            if (kills instanceof Number n && display != null && n.intValue() >= 0) {
                ranks.add(new KillRank(n.intValue(), display.toString()));
            }
        }
        ranks.sort((a, b) -> Integer.compare(a.kills(), b.kills()));
        killRanks = List.copyOf(ranks);

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

    /** The highest tier reached, or null when there are no tiers. */
    @Nullable KillRank rankFor(int kills) {
        KillRank best = null;
        for (KillRank r : killRanks) {
            if (kills >= r.kills()) {
                best = r;
            }
        }
        return best;
    }

    @Nullable KillRank nextRank(int kills) {
        for (KillRank r : killRanks) {
            if (r.kills() > kills) {
                return r;
            }
        }
        return null;
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
        Cosmetic.Skin skin = null;
        switch (cat) {
            case JOIN_MESSAGE, LEAVE_MESSAGE -> {
                message = c.getString("message");
                if (message == null || message.isBlank() || message.length() > 256) {
                    throw new IllegalArgumentException("needs a message (up to 256 characters)");
                }
            }
            case NAME_COLOR -> {
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
            case KILL_EFFECT, EXPLOSIVE_KILL, TRAIL -> effect = parseEffect(cat, section(c, "effect"));
            case TOOL_SKIN -> skin = parseSkin(c);
        }
        return new Cosmetic(cat, id, name, permission, icon, description, message, color, effect, skin);
    }

    private static Cosmetic.Effect parseEffect(CosmeticCategory cat, ConfigurationSection e) {
        boolean trail = cat == CosmeticCategory.TRAIL;
        String particleName = e.getString("particle", cat == CosmeticCategory.EXPLOSIVE_KILL ? "EXPLOSION_EMITTER" : "FLAME");
        Particle particle;
        try {
            particle = Particle.valueOf(particleName.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("unknown particle " + particleName);
        }
        if (!Cosmetic.Effect.supported(particle)) {
            throw new IllegalArgumentException("particle " + particleName + " needs block/item data, which is not supported");
        }
        // Rate limits: trails run many times a second, so they get a much smaller cap than one-off kill effects.
        int count = clamp(e.getInt("count", trail ? 2 : 20), 1, trail ? 6 : 60);
        double spread = clampD(e.getDouble("spread", trail ? 0.1 : 0.6), 0, trail ? 0.5 : 3);
        double speed = clampD(e.getDouble("speed", 0), 0, trail ? 0.1 : 1);
        double offsetY = clampD(e.getDouble("offset-y", trail ? 0.1 : 1.0), -1, 3);
        List<Color> colors = new ArrayList<>();
        for (String hex : e.getStringList("colors")) {
            Color col = parseColor(hex);
            if (col == null) {
                throw new IllegalArgumentException("bad colour " + hex + " (use #rrggbb)");
            }
            colors.add(col);
        }
        float size = (float) clampD(e.getDouble("size", 1.0), 0.1, 4);
        String sound = trail ? null : e.getString("sound");
        if (sound != null && (sound.isBlank() || !sound.matches("[a-z0-9_.:-]{1,100}"))) {
            throw new IllegalArgumentException("sound must be a sound key such as entity.generic.explode");
        }
        float volume = (float) clampD(e.getDouble("volume", 1.0), 0, 2);
        float pitch = (float) clampD(e.getDouble("pitch", 1.0), 0.5, 2);
        boolean lightning = !trail && e.getBoolean("lightning", false);
        return new Cosmetic.Effect(particle, count, spread, speed, offsetY, List.copyOf(colors), size, sound, volume, pitch, lightning);
    }

    private static Cosmetic.Skin parseSkin(ConfigurationSection c) {
        Set<Material> materials = EnumSet.noneOf(Material.class);
        for (String name : c.getStringList("materials")) {
            Material m = Material.matchMaterial(name);
            if (m == null || !m.isItem() || m.isAir()) {
                throw new IllegalArgumentException("materials: '" + name + "' is not an item");
            }
            materials.add(m);
        }
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("needs a materials list (which tools it skins)");
        }
        NamespacedKey model = null;
        String modelName = c.getString("item-model");
        if (modelName != null && !modelName.isBlank()) {
            model = NamespacedKey.fromString(modelName.toLowerCase(Locale.ROOT));
            if (model == null) {
                throw new IllegalArgumentException("item-model must look like namespace:path");
            }
        }
        Integer cmd = c.isInt("custom-model-data") ? c.getInt("custom-model-data") : null;
        if (model == null && cmd == null) {
            throw new IllegalArgumentException("needs item-model or custom-model-data");
        }
        return new Cosmetic.Skin(Collections.unmodifiableSet(materials), model, cmd);
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
