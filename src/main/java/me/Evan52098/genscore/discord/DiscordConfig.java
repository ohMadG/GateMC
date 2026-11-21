package me.Evan52098.genscore.discord;

import me.Evan52098.genscore.rewardbox.Reward;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

final class DiscordConfig {

    record Tier(String id, int days, String name, Material icon, List<String> description, List<Reward> rewards) {
    }

    final boolean enabled;
    final String invite;
    final long codeMillis;
    final int pollSeconds;
    final long unlinkCooldownMillis;
    final List<Reward> linkReward;
    final List<String> nitroPermissions;
    final boolean nitroBroadcast;
    final String nitroKit;
    final int monthlyDays;
    final List<Reward> monthlyRewards;
    final List<Tier> path;
    final Map<String, String> roleGroups;
    final List<String> rankGroups;
    final int rankSyncSeconds;

    DiscordConfig(@Nullable ConfigurationSection s, Logger logger) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        enabled = s.getBoolean("enabled", true);
        invite = s.getString("invite", "");
        codeMillis = Math.max(1, s.getInt("code-minutes", 10)) * 60_000L;
        pollSeconds = Math.max(5, s.getInt("poll-seconds", 15));
        unlinkCooldownMillis = Math.max(0, s.getInt("unlink-cooldown-hours", 24)) * 3_600_000L;
        linkReward = Reward.parseList(s.getList("link-reward"), logger, "discord.link-reward");
        ConfigurationSection n = s.getConfigurationSection("nitro");
        if (n == null) {
            n = new MemoryConfiguration();
        }
        List<String> perms = new ArrayList<>();
        for (String p : n.getStringList("permissions")) {
            if (!p.isBlank()) {
                perms.add(p.trim().toLowerCase(Locale.ROOT));
            }
        }
        nitroPermissions = List.copyOf(perms);
        nitroBroadcast = n.getBoolean("broadcast", true);
        nitroKit = n.getString("kit", "nitro");
        ConfigurationSection mo = n.getConfigurationSection("monthly");
        monthlyDays = mo == null ? 30 : Math.max(1, mo.getInt("days", 30));
        monthlyRewards = mo == null || !mo.getBoolean("enabled", true) ? List.of()
                : Reward.parseList(mo.getList("rewards"), logger, "discord.nitro.monthly.rewards");
        List<Tier> tiers = new ArrayList<>();
        List<?> raw = n.getList("path");
        if (raw != null) {
            int index = 0;
            for (Object o : raw) {
                index++;
                Map<?, ?> m = Reward.asMap(o);
                Object id = m.get("id");
                if (id == null) {
                    logger.warning("[discord] nitro.path #" + index + " has no id; skipped.");
                    continue;
                }
                int days = m.get("days") == null ? 0 : Integer.parseInt(String.valueOf(m.get("days")));
                String name = m.get("name") == null ? String.valueOf(id) : String.valueOf(m.get("name"));
                Material icon = m.get("icon") == null ? null : Material.matchMaterial(String.valueOf(m.get("icon")));
                if (icon == null || !icon.isItem() || icon.isAir()) {
                    icon = Material.AMETHYST_SHARD;
                }
                List<String> desc = new ArrayList<>();
                if (m.get("description") instanceof List<?> lines) {
                    for (Object line : lines) {
                        desc.add(String.valueOf(line));
                    }
                }
                List<Reward> rewards = Reward.parseList(m.get("rewards") instanceof List<?> l ? l : null, logger,
                        "discord.nitro.path." + id);
                tiers.add(new Tier(String.valueOf(id).toLowerCase(Locale.ROOT), Math.max(0, days), name, icon, List.copyOf(desc), rewards));
            }
        }
        tiers.sort(Comparator.comparingInt(Tier::days));
        path = List.copyOf(tiers);
        Map<String, String> groups = new LinkedHashMap<>();
        ConfigurationSection rs = s.getConfigurationSection("role-sync");
        if (rs != null) {
            ConfigurationSection g = rs.getConfigurationSection("groups");
            if (g != null) {
                for (String roleId : g.getKeys(false)) {
                    String group = g.getString(roleId, "").trim().toLowerCase(Locale.ROOT);
                    if (!group.isEmpty()) {
                        groups.put(roleId.trim(), group);
                    }
                }
            }
        }
        roleGroups = Map.copyOf(groups);
        rankGroups = rs == null ? List.of() : rs.getStringList("ranks").stream().map(r -> r.trim().toLowerCase(Locale.ROOT)).filter(r -> !r.isEmpty()).toList();
        rankSyncSeconds = rs == null ? 300 : Math.max(30, rs.getInt("rank-sync-seconds", 300));
    }

    @Nullable Tier tier(String id) {
        for (Tier t : path) {
            if (t.id().equals(id)) {
                return t;
            }
        }
        return null;
    }
}
