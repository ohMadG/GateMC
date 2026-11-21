package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Paid rank boosts from {@code factory.rank-perks}: more tokens from everything earned in play, more sale value and
 * extra tokens per hour played. The best rank a player has (LuckPerms {@code group.<id>}, inherited ranks count) wins.
 * Resolved on the player's thread at join and once a minute; the belt and reward code only read the cache.
 */
public final class RankPerks {

    public record Perk(String rank, int tokenPercent, int salePercent, int hourlyTokens) {
    }

    private static final Perk NONE = new Perk("", 0, 0, 0);
    private static volatile List<Perk> tiers = List.of();
    private static final Map<UUID, Perk> cache = new ConcurrentHashMap<>();

    private RankPerks() {
    }

    static void load(@Nullable ConfigurationSection s) {
        List<Perk> out = new ArrayList<>();
        if (s != null) {
            for (String rank : s.getKeys(false)) {
                ConfigurationSection r = s.getConfigurationSection(rank);
                if (r != null) {
                    out.add(new Perk(rank, Math.max(0, r.getInt("tokens", 0)), Math.max(0, r.getInt("sale", 0)),
                            Math.max(0, r.getInt("hourly-tokens", 0))));
                }
            }
        }
        tiers = List.copyOf(out);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> refresh(p));
        }
    }

    static void refresh(Player p) {
        Perk best = NONE;
        for (Perk t : tiers) {
            if (p.hasPermission("group." + t.rank()) && t.tokenPercent() + t.salePercent() + t.hourlyTokens()
                    > best.tokenPercent() + best.salePercent() + best.hourlyTokens()) {
                best = t;
            }
        }
        cache.put(p.getUniqueId(), best);
    }

    static void forget(UUID player) {
        cache.remove(player);
    }

    public static Perk of(UUID player) {
        return cache.getOrDefault(player, NONE);
    }

    /** {@code base} tokens with the rank boost; fractions round up by chance, so +10% on 1 token is a 10% shot at 2. */
    public static long tokens(UUID player, long base) {
        int pct = of(player).tokenPercent();
        if (base <= 0 || pct <= 0) {
            return base;
        }
        double exact = base * (1 + pct / 100.0);
        long whole = (long) Math.floor(exact);
        return whole + (ThreadLocalRandom.current().nextDouble() < exact - whole ? 1 : 0);
    }

    /** Sale value multiplier, 1.0 without a rank. */
    public static double sale(UUID player) {
        return 1 + of(player).salePercent() / 100.0;
    }
}
