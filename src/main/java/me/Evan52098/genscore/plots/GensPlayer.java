package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.player.PlayerSection;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A player's gens progress that is not about the plot: the {@code gens_players} row. Balance lives in the economy
 * module and last seen in the core player data.
 *
 * <p>Tokens and caches are counts here, never items, so they cannot be duplicated by inventory tricks; every spend
 * goes through a synchronized take-then-give method (exploit checklist item 1). Thread-safe.
 */
public final class GensPlayer {

    /** Cache rarities, in order. */
    public static final String[] RARITIES = {"common", "rare", "epic", "legendary"};

    private int rebirths;
    private long tokens;
    private int valueLevel;
    private int luckLevel;
    private int gearLevel;
    private final int[] caches = new int[RARITIES.length];
    private final Set<String> milestones = new HashSet<>();
    private long lifetimeCents;
    private int raidsWon;
    private int raidsDefended;
    private int hoursRewarded;

    GensPlayer() {
    }

    public synchronized int rebirths() {
        return rebirths;
    }

    public synchronized void setRebirths(int rebirths) {
        this.rebirths = Math.max(0, rebirths);
    }

    // ---- tokens

    public synchronized long tokens() {
        return tokens;
    }

    public synchronized void addTokens(long amount) {
        tokens = Math.max(0, tokens + Math.max(0, amount));
    }

    /** Takes tokens if there are enough. Returns false and takes nothing otherwise. */
    public synchronized boolean takeTokens(long amount) {
        if (amount < 0 || tokens < amount) {
            return false;
        }
        tokens -= amount;
        return true;
    }

    // ---- upgrade levels bought with tokens or money

    public synchronized int valueLevel() {
        return valueLevel;
    }

    public synchronized void setValueLevel(int level) {
        valueLevel = Math.max(0, level);
    }

    public synchronized int luckLevel() {
        return luckLevel;
    }

    public synchronized void setLuckLevel(int level) {
        luckLevel = Math.max(0, level);
    }

    public synchronized int gearLevel() {
        return gearLevel;
    }

    public synchronized void setGearLevel(int level) {
        gearLevel = Math.max(0, level);
    }

    // ---- caches

    public synchronized int caches(int rarity) {
        return rarity >= 0 && rarity < caches.length ? caches[rarity] : 0;
    }

    public synchronized void addCache(int rarity, int amount) {
        if (rarity >= 0 && rarity < caches.length && amount > 0) {
            caches[rarity] += amount;
        }
    }

    /** Takes one cache of this rarity if there is one. */
    public synchronized boolean takeCache(int rarity) {
        if (rarity < 0 || rarity >= caches.length || caches[rarity] <= 0) {
            return false;
        }
        caches[rarity]--;
        return true;
    }

    // ---- milestones and stats

    /** Marks a milestone as reached. Returns true only the first time (so its reward is paid once). */
    public synchronized boolean claimMilestone(String id) {
        return milestones.add(id);
    }

    public synchronized boolean hasMilestone(String id) {
        return milestones.contains(id);
    }

    public synchronized int milestoneCount() {
        return milestones.size();
    }

    public synchronized long lifetimeCents() {
        return lifetimeCents;
    }

    public synchronized void addLifetime(long cents) {
        lifetimeCents += Math.max(0, cents);
    }

    public synchronized int raidsWon() {
        return raidsWon;
    }

    public synchronized void addRaidWon() {
        raidsWon++;
    }

    public synchronized int raidsDefended() {
        return raidsDefended;
    }

    public synchronized void addRaidDefended() {
        raidsDefended++;
    }

    /** Hours of playtime already paid a reward; the playtime milestone pays each new hour once. */
    public synchronized int hoursRewarded() {
        return hoursRewarded;
    }

    public synchronized void setHoursRewarded(int hours) {
        hoursRewarded = Math.max(hoursRewarded, hours);
    }

    private synchronized Object[] snapshot() {
        return new Object[]{rebirths, tokens, valueLevel, luckLevel, gearLevel, cachesText(), String.join(",", milestones),
                lifetimeCents, raidsWon, raidsDefended, hoursRewarded};
    }

    private String cachesText() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < caches.length; i++) {
            sb.append(i == 0 ? "" : ",").append(caches[i]);
        }
        return sb.toString();
    }

    static final PlayerSection<GensPlayer> SECTION = new PlayerSection<>() {
        @Override
        public String id() {
            return "gens";
        }

        @Override
        public GensPlayer load(Connection c, UUID player) throws SQLException {
            GensPlayer g = new GensPlayer();
            try (PreparedStatement ps = c.prepareStatement("SELECT rebirths, tokens, value_level, luck_level, gear_level, caches, "
                    + "milestones, lifetime_cents, raids_won, raids_defended, hours_rewarded FROM gens_players WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        g.rebirths = rs.getInt(1);
                        g.tokens = rs.getLong(2);
                        g.valueLevel = rs.getInt(3);
                        g.luckLevel = rs.getInt(4);
                        g.gearLevel = rs.getInt(5);
                        String caches = rs.getString(6);
                        if (caches != null && !caches.isBlank()) {
                            String[] parts = caches.split(",");
                            for (int i = 0; i < Math.min(parts.length, g.caches.length); i++) {
                                try {
                                    g.caches[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
                                } catch (NumberFormatException ignored) {
                                    // keep 0
                                }
                            }
                        }
                        String ms = rs.getString(7);
                        if (ms != null && !ms.isBlank()) {
                            g.milestones.addAll(Arrays.asList(ms.split(",")));
                        }
                        g.lifetimeCents = rs.getLong(8);
                        g.raidsWon = rs.getInt(9);
                        g.raidsDefended = rs.getInt(10);
                        g.hoursRewarded = rs.getInt(11);
                    }
                }
            }
            return g;
        }

        @Override
        public void save(Connection c, UUID player, GensPlayer data) throws SQLException {
            Object[] s = data.snapshot();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gens_players (uuid, rebirths, tokens, value_level, luck_level, gear_level, caches, milestones, "
                            + "lifetime_cents, raids_won, raids_defended, hours_rewarded) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (uuid) DO UPDATE SET rebirths = excluded.rebirths, tokens = excluded.tokens, "
                            + "value_level = excluded.value_level, luck_level = excluded.luck_level, gear_level = excluded.gear_level, "
                            + "caches = excluded.caches, milestones = excluded.milestones, lifetime_cents = excluded.lifetime_cents, "
                            + "raids_won = excluded.raids_won, raids_defended = excluded.raids_defended, "
                            + "hours_rewarded = excluded.hours_rewarded")) {
                ps.setString(1, player.toString());
                ps.setInt(2, (Integer) s[0]);
                ps.setLong(3, (Long) s[1]);
                ps.setInt(4, (Integer) s[2]);
                ps.setInt(5, (Integer) s[3]);
                ps.setInt(6, (Integer) s[4]);
                ps.setString(7, (String) s[5]);
                ps.setString(8, (String) s[6]);
                ps.setLong(9, (Long) s[7]);
                ps.setInt(10, (Integer) s[8]);
                ps.setInt(11, (Integer) s[9]);
                ps.setInt(12, (Integer) s[10]);
                ps.executeUpdate();
            }
        }
    };
}
