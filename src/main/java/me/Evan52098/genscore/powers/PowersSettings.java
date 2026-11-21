package me.Evan52098.genscore.powers;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

record PowersSettings(
        String menuTitle,
        String opMenuTitle,
        String updateId,
        String updateMessage,
        long doubleSneakMs,
        boolean firstFree,
        Set<String> disabledWorlds,
        boolean disableAllInCombat,
        String iconModel,
        Map<String, PowerDef> powers) {

    static final long MAX_COST = 1_000_000_000L;

    static PowersSettings load(File file, Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        Map<String, PowerDef> powers = new LinkedHashMap<>();
        ConfigurationSection list = y.getConfigurationSection("list");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                String id = key.toLowerCase(Locale.ROOT);
                ConfigurationSection s = list.getConfigurationSection(key);
                PowerDef.Kind kind = PowerDef.KNOWN.get(id);
                if (s == null || kind == null) {
                    log.warning("[powers] Unknown power '" + key + "' in powers.yml, skipped (only the built-in powers can be configured)");
                    continue;
                }
                Material mat = Material.matchMaterial(s.getString("material", "NETHER_STAR"));
                if (mat == null || !mat.isItem() || mat.isAir()) {
                    log.warning("[powers] " + id + ": bad material '" + s.getString("material") + "', using NETHER_STAR");
                    mat = Material.NETHER_STAR;
                }
                long cost = s.getLong("cost", 0);
                if (cost < 0 || cost > MAX_COST) {
                    log.warning("[powers] " + id + ": cost must be 0 to " + MAX_COST + "; the power is turned off");
                    continue;
                }
                Map<String, Double> numbers = new HashMap<>();
                for (String k : s.getKeys(false)) {
                    if (s.isInt(k) || s.isDouble(k) || s.isLong(k)) {
                        numbers.put(k, s.getDouble(k));
                    }
                }
                powers.put(id, new PowerDef(id, kind,
                        s.getString("name", id),
                        cost,
                        mat,
                        List.copyOf(s.getStringList("lore")),
                        s.getBoolean("op", false),
                        s.getBoolean("enabled", true),
                        s.getBoolean("in-combat", !PowerDef.NOT_IN_COMBAT_BY_DEFAULT.contains(id)),
                        Map.copyOf(numbers)));
            }
        }
        List<String> missing = new ArrayList<>();
        for (String id : PowerDef.KNOWN.keySet()) {
            if (!powers.containsKey(id)) {
                missing.add(id);
            }
        }
        if (!missing.isEmpty()) {
            log.info("[powers] Not in powers.yml (so not offered): " + String.join(", ", missing));
        }
        return new PowersSettings(
                y.getString("menu-title", "&c&l❄ &f&lSUPER POWERS &c&l❄"),
                y.getString("op-menu-title", "&6&l★ &e&lSUPER POWERS &6&l★"),
                y.getString("update-id", ""),
                y.getString("update-message", ""),
                Math.max(100, Math.min(2000, y.getLong("double-sneak-ms", 400))),
                y.getBoolean("first-free", true),
                y.getStringList("disabled-worlds").stream().map(w -> w.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet()),
                y.getBoolean("disable-all-in-combat", false),
                y.getString("icon-model", ""),
                Collections.unmodifiableMap(powers));
    }

    PowerDef power(String id) {
        return id == null ? null : powers.get(id.toLowerCase(Locale.ROOT));
    }

    boolean worldDisabled(String worldName) {
        return disabledWorlds.contains(worldName.toLowerCase(Locale.ROOT));
    }
}
