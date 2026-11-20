package me.Evan52098.genscore.shards;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** The {@code shards:} section of config.yml. Immutable; replaced as a whole on reload. */
public record ShardsSettings(
        long maxBalance,
        long maxAdminAmount,
        boolean earnEnabled,
        boolean countAfk,
        long baseMilliPerMinute,
        Map<String, Long> rankMilliPerMinute,
        String actionbar,
        int topSize,
        long topCacheMillis) {

    /** No balance can go above this, whatever the config says (keeps every sum far from overflow). */
    static final long HARD_MAX = 1_000_000_000_000L;

    static ShardsSettings from(ConfigurationSection s) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        long max = clamp(s.getLong("max-balance", 1_000_000_000L), 1, HARD_MAX);
        Map<String, Long> ranks = new LinkedHashMap<>();
        ConfigurationSection r = s.getConfigurationSection("earn.ranks");
        if (r != null) {
            for (String key : r.getKeys(false)) {
                ranks.put(key.toLowerCase(Locale.ROOT), milli(r.getDouble(key, 0)));
            }
        }
        return new ShardsSettings(
                max,
                clamp(s.getLong("max-admin-amount", 10_000_000L), 1, max),
                s.getBoolean("earn.enabled", true),
                s.getBoolean("earn.count-afk", true),
                milli(s.getDouble("earn.base-per-minute", 0.5)),
                Map.copyOf(ranks),
                s.getString("earn.actionbar", "&b+{amount} shards &8| &bShards: &f{shards}"),
                (int) clamp(s.getInt("top.size", 10), 1, 100),
                clamp(s.getLong("top.cache-seconds", 60), 5, 3600) * 1000L);
    }

    /** Thousandths of a shard, from a per-minute rate in config (0 to 1000 a minute). */
    private static long milli(double perMinute) {
        if (Double.isNaN(perMinute) || perMinute <= 0) {
            return 0;
        }
        return Math.round(Math.min(perMinute, 1000) * 1000);
    }

    private static long clamp(long v, long min, long max) {
        return Math.max(min, Math.min(max, v));
    }
}
