package me.Evan52098.genscore.sus;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** The {@code sus:} section of config.yml (defaults are HxCore's). Immutable. */
record SusSettings(
        String prefix,
        long alertCooldownMillis,
        long holdMillis,
        boolean autoClicker,
        int maxCps,
        boolean autoCrystal,
        int maxCrystalsPerSecond,
        boolean autoTotem,
        long totemMinMs,
        int totemFlagsNeeded,
        long totemWindowMillis,
        boolean nuker,
        int maxBlocksPerSecond,
        float nukerMinHardness) {

    static SusSettings read(ConfigurationSection root) {
        ConfigurationSection c = root != null ? root : new MemoryConfiguration();
        return new SusSettings(
                c.getString("prefix", "<red><bold>SUS</bold> <dark_gray>» "),
                Math.max(0, c.getLong("alert-cooldown-seconds", 60)) * 1000L,
                Math.max(500, c.getLong("hold-millis", 2000)),
                c.getBoolean("checks.autoclicker", true),
                Math.max(5, c.getInt("max-cps", 25)),
                c.getBoolean("checks.autocrystal", true),
                Math.max(2, c.getInt("max-crystals-per-second", 12)),
                c.getBoolean("checks.autototem", true),
                Math.max(0, c.getLong("totem-min-ms", 120)),
                Math.max(1, c.getInt("totem-flags-needed", 3)),
                Math.max(1, c.getLong("totem-window-minutes", 10)) * 60_000L,
                c.getBoolean("checks.nuker", true),
                Math.max(5, c.getInt("max-blocks-broken-per-second", 22)),
                (float) c.getDouble("nuker-min-hardness", 0.5));
    }
}
