package me.Evan52098.genscore.seasonpass;

import me.Evan52098.genscore.rewardbox.Reward;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.logging.Logger;

/** The {@code seasonpass} section of config.yml, read once per load/reload into immutable values. */
record PassConfig(boolean enabled, String seasonId, String seasonName, ZoneId zone, Instant start, Instant end,
                  int tierCount, long xpPerTier, Map<Integer, Tier> tiers, int dailyCount, int weeklyCount,
                  List<Challenge> dailyPool, List<Challenge> weeklyPool, int killSameVictimMinutes,
                  boolean countSpawnerMobs, boolean countAfkPlaytime) {

    static final int MAX_TIERS = 200;

    record Tier(int number, List<Reward> free, List<Reward> premium, @Nullable Material icon) {
    }

    record Challenge(String id, String name, Objective type, long amount, long xp, Set<String> filter, Material icon) {
        boolean matches(@Nullable String what) {
            return filter.isEmpty() || (what != null && filter.contains(what.toUpperCase(Locale.ROOT)));
        }
    }

    enum Track { FREE, PREMIUM }

    static PassConfig from(@Nullable ConfigurationSection s, Logger logger) {
        if (s == null) {
            logger.warning("[seasonpass] No 'seasonpass' section in config.yml; the pass is off.");
            return new PassConfig(false, "none", "Season", ZoneId.of("UTC"), Instant.EPOCH, Instant.EPOCH, 0, 100,
                    Map.of(), 0, 0, List.of(), List.of(), 30, false, false);
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(s.getString("timezone", "UTC"));
        } catch (DateTimeException e) {
            logger.warning("[seasonpass] Unknown timezone " + s.getString("timezone") + "; using UTC.");
            zone = ZoneId.of("UTC");
        }
        String seasonId = s.getString("season-id", "season-1").toLowerCase(Locale.ROOT);
        if (!seasonId.matches("[a-z0-9_-]{1,32}")) {
            logger.warning("[seasonpass] season-id must be 1-32 of a-z 0-9 _ -; the pass is off.");
            return from(null, logger);
        }
        Instant start = parseTime(s.getString("start", "2026-01-01T00:00"), zone, logger, "start");
        Instant end = parseTime(s.getString("end", "2026-12-31T23:59"), zone, logger, "end");
        int tierCount = Math.max(1, Math.min(MAX_TIERS, s.getInt("tier-count", 30)));
        long xpPerTier = Math.max(1, s.getLong("xp-per-tier", 100));

        Map<Integer, Tier> tiers = new HashMap<>();
        ConfigurationSection ts = s.getConfigurationSection("tiers");
        if (ts != null) {
            for (String key : ts.getKeys(false)) {
                int n;
                try {
                    n = Integer.parseInt(key);
                } catch (NumberFormatException e) {
                    logger.warning("[seasonpass] Tier key '" + key + "' is not a number; skipped.");
                    continue;
                }
                if (n < 1 || n > tierCount) {
                    logger.warning("[seasonpass] Tier " + n + " is outside 1-" + tierCount + "; skipped.");
                    continue;
                }
                ConfigurationSection t = ts.getConfigurationSection(key);
                if (t == null) {
                    continue;
                }
                Material icon = t.getString("icon") == null ? null : Material.matchMaterial(t.getString("icon"));
                tiers.put(n, new Tier(n, Reward.parseList(t.getList("free"), logger, "seasonpass tier " + n + " free"),
                        Reward.parseList(t.getList("premium"), logger, "seasonpass tier " + n + " premium"), icon));
            }
        }
        ConfigurationSection cs = s.getConfigurationSection("challenges");
        int dailyCount = cs == null ? 0 : Math.max(0, Math.min(14, cs.getInt("daily-count", 3)));
        int weeklyCount = cs == null ? 0 : Math.max(0, Math.min(14, cs.getInt("weekly-count", 3)));
        List<Challenge> daily = cs == null ? List.of() : challenges(cs.getList("daily"), logger, "daily");
        List<Challenge> weekly = cs == null ? List.of() : challenges(cs.getList("weekly"), logger, "weekly");
        ConfigurationSection anti = s.getConfigurationSection("anti-farm");
        return new PassConfig(s.getBoolean("enabled", true), seasonId, s.getString("season-name", "Season 1"), zone, start, end,
                tierCount, xpPerTier, Map.copyOf(tiers), dailyCount, weeklyCount, daily, weekly,
                anti == null ? 30 : Math.max(0, anti.getInt("same-victim-minutes", 30)),
                anti != null && anti.getBoolean("count-spawner-mobs", false),
                anti != null && anti.getBoolean("count-afk-playtime", false));
    }

    private static Instant parseTime(String text, ZoneId zone, Logger logger, String what) {
        try {
            return LocalDateTime.parse(text).atZone(zone).toInstant();
        } catch (DateTimeParseException e) {
            try {
                return LocalDate.parse(text).atStartOfDay(zone).toInstant();
            } catch (DateTimeParseException e2) {
                logger.warning("[seasonpass] '" + what + "' must look like 2026-12-01T00:00; got " + text);
                return Instant.EPOCH;
            }
        }
    }

    private static List<Challenge> challenges(@Nullable List<?> raw, Logger logger, String pool) {
        List<Challenge> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        Set<String> seen = new HashSet<>();
        for (Object o : raw) {
            Map<?, ?> m = Reward.asMap(o);
            try {
                String id = String.valueOf(m.get("id")).toLowerCase(Locale.ROOT);
                if (!id.matches("[a-z0-9_-]{1,40}") || !seen.add(id)) {
                    throw new IllegalArgumentException("id missing, invalid or used twice");
                }
                Objective type = Objective.valueOf(String.valueOf(m.get("type")).toUpperCase(Locale.ROOT));
                long amount = Long.parseLong(String.valueOf(m.get("amount")));
                long xp = Long.parseLong(String.valueOf(m.get("xp")));
                if (amount <= 0 || amount > 1_000_000_000L || xp <= 0 || xp > 1_000_000L) {
                    throw new IllegalArgumentException("amount must be 1 to 1e9 and xp 1 to 1e6");
                }
                Set<String> filter = new HashSet<>();
                if (m.get("filter") instanceof List<?> f) {
                    for (Object x : f) {
                        filter.add(String.valueOf(x).toUpperCase(Locale.ROOT));
                    }
                }
                Material icon = m.get("icon") == null ? type.icon : Material.matchMaterial(String.valueOf(m.get("icon")));
                String name = m.get("name") == null ? type.describe(amount) : String.valueOf(m.get("name"));
                out.add(new Challenge(id, name, type, amount, xp, Set.copyOf(filter), icon == null ? type.icon : icon));
            } catch (RuntimeException e) {
                logger.warning("[seasonpass] A " + pool + " challenge is invalid (" + e.getMessage() + "): " + m + "; skipped.");
            }
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------ season clock

    boolean active(Instant now) {
        return enabled && !now.isBefore(start) && now.isBefore(end);
    }

    long xpForTier(int tier) {
        return xpPerTier * tier;
    }

    int tierFor(long xp) {
        return (int) Math.min(tierCount, xp / xpPerTier);
    }

    String dailyKey(Instant now) {
        return seasonId + "|d:" + LocalDate.ofInstant(now, zone);
    }

    String weeklyKey(Instant now) {
        LocalDate d = LocalDate.ofInstant(now, zone);
        return seasonId + "|w:" + d.get(IsoFields.WEEK_BASED_YEAR) + "-" + d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR);
    }

    /** The day's (or week's) challenges: the same for every player, picked from the pool with the period as the seed. */
    List<Challenge> pick(List<Challenge> pool, int count, String periodKey) {
        if (pool.size() <= count) {
            return pool;
        }
        List<Challenge> copy = new ArrayList<>(pool);
        Collections.shuffle(copy, new Random(periodKey.hashCode() * 31L + pool.size()));
        return List.copyOf(copy.subList(0, count));
    }

    List<Challenge> todaysDaily(Instant now) {
        return pick(dailyPool, dailyCount, dailyKey(now));
    }

    List<Challenge> thisWeeksWeekly(Instant now) {
        return pick(weeklyPool, weeklyCount, weeklyKey(now));
    }

    @Nullable Tier tier(int n) {
        return tiers.get(n);
    }
}
