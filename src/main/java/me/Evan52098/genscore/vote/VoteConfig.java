package me.Evan52098.genscore.vote;

import me.Evan52098.genscore.util.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * The {@code vote:} section of config.yml. Same keys as BlockvilleCore's votes.yml, except that money is read into cents
 * and checked (checklist 8), and {@code xp} is vanilla experience points (BlockvilleCore's "Network XP" does not exist
 * here). Each reward may carry {@code display} lines for the /vote menu.
 */
final class VoteConfig {

    record Site(String id, String name, String url, String service, int cooldownHours, String icon) {
    }

    record Reward(long moneyCents, int xp, List<String> commands, List<String> display) {
        static final Reward NONE = new Reward(0, 0, List.of(), List.of());
    }

    private static final long MAX_REWARD_CENTS = 100_000_000L; // $1,000,000

    final boolean enabled;
    final ZoneId zone;
    final boolean listenerEnabled;
    final String host;
    final int port;
    final int maxPerIpPerMinute;
    final int duplicateWindowMinutes;
    final int expireDays;
    final Map<String, Site> sites;
    final Reward voteReward;
    final String voteMessage;
    final TreeMap<Integer, Reward> streakMilestones;
    final boolean partyEnabled;
    final int partyGoal;
    final Reward partyReward;
    final String announce;
    final boolean joinReminder;
    final File votifierFolder;

    VoteConfig(@Nullable ConfigurationSection yaml, File moduleFolder, Logger logger) {
        if (yaml == null) {
            yaml = new MemoryConfiguration();
        }
        enabled = yaml.getBoolean("enabled", true);
        ZoneId z;
        try {
            z = ZoneId.of(yaml.getString("timezone", "America/New_York"));
        } catch (DateTimeException e) {
            z = ZoneId.of("America/New_York");
        }
        zone = z;
        listenerEnabled = yaml.getBoolean("listener.enabled", false);
        host = yaml.getString("listener.host", "0.0.0.0");
        port = Math.max(1, Math.min(65535, yaml.getInt("listener.port", 8192)));
        maxPerIpPerMinute = Math.max(1, yaml.getInt("listener.max-per-ip-per-minute", 30));
        duplicateWindowMinutes = Math.max(0, yaml.getInt("listener.duplicate-window-minutes", 60));
        expireDays = Math.max(1, yaml.getInt("listener.expire-days", 30));

        Map<String, Site> siteMap = new LinkedHashMap<>();
        ConfigurationSection siteSection = yaml.getConfigurationSection("sites");
        if (siteSection != null) {
            for (String id : siteSection.getKeys(false)) {
                ConfigurationSection s = siteSection.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String name = s.getString("name", id);
                siteMap.put(id, new Site(id, name, s.getString("url", ""), s.getString("service", name),
                        Math.max(1, s.getInt("cooldown-hours", 24)), s.getString("icon", "PAPER")));
            }
        }
        sites = Collections.unmodifiableMap(siteMap);

        voteReward = reward(yaml.getConfigurationSection("reward"));
        voteMessage = yaml.getString("reward.message", "");
        TreeMap<Integer, Reward> milestones = new TreeMap<>();
        ConfigurationSection streak = yaml.getConfigurationSection("streak.milestones");
        if (streak != null) {
            for (String key : streak.getKeys(false)) {
                try {
                    int days = Integer.parseInt(key.trim());
                    if (days > 0) {
                        milestones.put(days, reward(streak.getConfigurationSection(key)));
                    }
                } catch (NumberFormatException e) {
                    logger.warning("[votes] vote.streak.milestone '" + key + "' is not a number of days - ignored.");
                }
            }
        }
        streakMilestones = milestones;
        partyEnabled = yaml.getBoolean("party.enabled", true);
        partyGoal = Math.max(1, yaml.getInt("party.goal", 50));
        partyReward = reward(yaml.getConfigurationSection("party"));
        announce = yaml.getString("announce", "");
        joinReminder = yaml.getBoolean("reminder.on-join", true);
        votifierFolder = new File(moduleFolder, "votifier");
    }

    private static Reward reward(@Nullable ConfigurationSection section) {
        if (section == null) {
            return Reward.NONE;
        }
        List<String> commands = new ArrayList<>();
        for (String raw : section.getStringList("commands")) {
            String command = raw.trim();
            if (command.startsWith("/")) {
                command = command.substring(1);
            }
            if (!command.isEmpty()) {
                commands.add(command);
            }
        }
        double money = section.getDouble("money", 0.0);
        long cents = Double.isFinite(money) && money > 0 ? Math.min(MAX_REWARD_CENTS, Money.toCents(Math.min(money, 1_000_000.0))) : 0;
        int xp = (int) Math.max(0, Math.min(1_000_000, section.getDouble("xp", 0.0)));
        return new Reward(cents, xp, List.copyOf(commands), List.copyOf(section.getStringList("display")));
    }

    @Nullable Site siteForService(String service) {
        String wanted = squash(service);
        for (Site site : sites.values()) {
            if (squash(site.service()).equals(wanted) || squash(site.id()).equals(wanted)) {
                return site;
            }
        }
        return null;
    }

    private static String squash(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    LocalDate today() {
        return LocalDate.now(zone);
    }
}
