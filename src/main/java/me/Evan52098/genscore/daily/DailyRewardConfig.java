package me.Evan52098.genscore.daily;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * The {@code daily} section of config.yml, read the same way as BlockvilleCore's DailyRewardConfig
 * ({@code daily-reward.*}). Tiers are the five dig layers, shallowest first; order is depth and depth is rarity.
 */
record DailyRewardConfig(boolean enabled, ZoneId zone, double streakMultiplierPerDay, double maxStreakMultiplier,
                         List<DailyRewardTier> tiers, List<DailyRewardMilestone> milestones) {

    /** The menu has five layers. */
    static final int MAX_TIERS = 5;

    record DailyRewardTier(String id, String displayName, int weight, Material oreBlock, List<String> commands,
                           boolean broadcast, String rewardText) {
    }

    record DailyRewardMilestone(int streakLength, String displayName, List<String> commands) {
    }

    static DailyRewardConfig from(@Nullable ConfigurationSection s, Logger logger) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        ZoneId zone;
        try {
            zone = ZoneId.of(s.getString("reset-timezone", "America/New_York"));
        } catch (RuntimeException ex) {
            logger.warning("[daily] Invalid daily.reset-timezone, falling back to America/New_York: " + ex.getMessage());
            zone = ZoneId.of("America/New_York");
        }
        boolean enabled = s.getBoolean("enabled", true);
        List<DailyRewardTier> tiers = parseTiers(s.getList("tiers", Collections.emptyList()), logger);
        if (tiers.size() > MAX_TIERS) {
            logger.warning("[daily] Only the first " + MAX_TIERS + " daily reward tiers are used (the menu has five layers).");
            tiers = List.copyOf(tiers.subList(0, MAX_TIERS));
        }
        if (enabled && tiers.isEmpty()) {
            logger.warning("[daily] Daily reward is enabled but no usable tiers are configured under 'daily.tiers' in config.yml; /daily will have nothing to strike.");
        }
        return new DailyRewardConfig(enabled, zone,
                Math.max(0.0, s.getDouble("streak.multiplier-per-day", 0.05)),
                Math.max(1.0, s.getDouble("streak.max-multiplier", 3.0)),
                tiers, parseMilestones(s.getConfigurationSection("streak.milestones"), logger));
    }

    private static List<DailyRewardTier> parseTiers(List<?> rawTiers, Logger logger) {
        List<DailyRewardTier> parsed = new ArrayList<>();
        HashSet<String> seenIds = new HashSet<>();
        int index = 0;
        for (Object raw : rawTiers) {
            index++;
            if (!(raw instanceof Map<?, ?> map)) {
                logger.warning("[daily] Daily reward tier #" + index + " is not a list entry; skipping it.");
                continue;
            }
            String id = readString(map, "id", "");
            if (id.isBlank() || !seenIds.add(id)) {
                logger.warning("[daily] Daily reward tier #" + index + " has a missing or duplicate 'id'; skipping it.");
                continue;
            }
            Material material = Material.matchMaterial(readString(map, "icon", "STONE"));
            if (material == null || !material.isItem()) {
                logger.warning("[daily] Daily reward tier '" + id + "' has an unknown 'icon' material; defaulting to STONE.");
                material = Material.STONE;
            }
            int weight = map.get("weight") instanceof Number n ? n.intValue() : 1;
            List<String> commands = readCommands(map.get("commands"), id, logger);
            if (commands.isEmpty()) {
                logger.warning("[daily] Daily reward tier '" + id + "' has no 'commands'; skipping it.");
                continue;
            }
            boolean broadcast = map.get("broadcast") instanceof Boolean b && b;
            parsed.add(new DailyRewardTier(id, readString(map, "display-name", id), Math.max(1, weight), material, commands,
                    broadcast, readString(map, "reward-text", "")));
        }
        return List.copyOf(parsed);
    }

    private static List<DailyRewardMilestone> parseMilestones(@Nullable ConfigurationSection section, Logger logger) {
        if (section == null) {
            return List.of();
        }
        List<DailyRewardMilestone> parsed = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            int length;
            try {
                length = Integer.parseInt(key.trim());
            } catch (NumberFormatException e) {
                logger.warning("[daily] Daily reward milestone '" + key + "' is not a whole number of days; skipping it.");
                continue;
            }
            ConfigurationSection tier = section.getConfigurationSection(key);
            if (tier == null || length < 1) {
                continue;
            }
            List<String> commands = readCommands(tier.getList("commands"), key, logger);
            if (!commands.isEmpty()) {
                parsed.add(new DailyRewardMilestone(length, tier.getString("display-name", key + "-Day Streak"), commands));
            }
        }
        parsed.sort(Comparator.comparingInt(DailyRewardMilestone::streakLength));
        return List.copyOf(parsed);
    }

    private static List<String> readCommands(@Nullable Object value, String owner, Logger logger) {
        List<String> commands = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object entry : list) {
                if (entry == null) {
                    continue;
                }
                String command = String.valueOf(entry).trim();
                if (command.startsWith("/")) {
                    command = command.substring(1).trim();
                }
                if (!command.isEmpty()) {
                    commands.add(command);
                }
            }
        } else if (value != null) {
            logger.warning("[daily] Daily reward '" + owner + "' has 'commands' written as a single value; write it as a list with '- ' in front of each command.");
        }
        return List.copyOf(commands);
    }

    private static String readString(Map<?, ?> map, String key, String fallback) {
        Object value = map.get(key);
        if (value == null) {
            return fallback;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    @Nullable DailyRewardMilestone milestoneFor(int streak) {
        for (DailyRewardMilestone m : milestones) {
            if (m.streakLength() == streak) {
                return m;
            }
        }
        return null;
    }

    @Nullable DailyRewardTier tier(String id) {
        for (DailyRewardTier t : tiers) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }
}
