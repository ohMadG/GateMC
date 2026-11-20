package me.Evan52098.genscore.crates;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

/** The {@code crates.lockbox} section of config.yml (BlockvilleCore's {@code lockbox.*} settings). */
record LockboxSettings(ZoneId zone, int maxAttempts, List<LockboxReward> byGuess, LockboxReward participation,
                       long rateLimitMillis, long minPlaytimeMinutes) {

    static LockboxSettings from(@Nullable ConfigurationSection s, Logger logger) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(s.getString("reset-timezone", "America/New_York"));
        } catch (RuntimeException ex) {
            logger.warning("[lockbox] Invalid crates.lockbox.reset-timezone, falling back to America/New_York: " + ex.getMessage());
            zone = ZoneId.of("America/New_York");
        }
        // At most four rows fit on the board (BlockvilleCore clamps the same way).
        int max = Math.max(1, Math.min(4, s.getInt("max-attempts", 4)));
        List<String> raw = s.getStringList("rewards.by-guess");
        if (raw.isEmpty()) {
            raw = List.of("$2500", "$1000", "$500", "$250");
        }
        List<LockboxReward> byGuess = new ArrayList<>();
        for (String r : raw) {
            byGuess.add(read(r, logger));
        }
        LockboxReward participation = read(s.getString("rewards.participation", "$50"), logger);
        return new LockboxSettings(zone, max, List.copyOf(byGuess), participation,
                Math.max(0L, s.getLong("rate-limit-millis", 1000L)), Math.max(0L, s.getLong("min-playtime-minutes", 0L)));
    }

    private static LockboxReward read(String raw, Logger logger) {
        LockboxReward reward = LockboxReward.parse(raw);
        if (reward == null) {
            logger.warning("[lockbox] Can't read reward '" + raw + "', use e.g. \"$1000\" (credits do not exist on this server); it pays nothing.");
            return LockboxReward.NONE;
        }
        return reward;
    }

    LockboxReward rewardFor(boolean solved, int attempts) {
        if (!solved) {
            return participation;
        }
        return attempts >= 1 && attempts <= byGuess.size() ? byGuess.get(attempts - 1) : LockboxReward.NONE;
    }
}
