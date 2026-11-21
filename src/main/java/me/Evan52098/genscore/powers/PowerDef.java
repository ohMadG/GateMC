package me.Evan52098.genscore.powers;

import org.bukkit.Material;

import java.util.List;
import java.util.Map;
import java.util.Set;

record PowerDef(
        String id,
        Kind kind,
        String name,
        long cost,
        Material material,
        List<String> lore,
        boolean op,
        boolean enabled,
        boolean inCombat,
        Map<String, Double> numbers) {

    enum Kind {
        PASSIVE,
        TRIGGERED,
        ACTIVE
    }

    static final Map<String, Kind> KNOWN = Map.ofEntries(
            Map.entry("ant_size", Kind.PASSIVE),
            Map.entry("titan", Kind.PASSIVE),
            Map.entry("aqua_lungs", Kind.PASSIVE),
            Map.entry("owl_eyes", Kind.PASSIVE),
            Map.entry("speedster", Kind.PASSIVE),
            Map.entry("moonwalker", Kind.PASSIVE),
            Map.entry("ice_skater", Kind.PASSIVE),
            Map.entry("magnet", Kind.PASSIVE),
            Map.entry("ember_walker", Kind.PASSIVE),
            Map.entry("miners_rush", Kind.PASSIVE),
            Map.entry("vampire", Kind.TRIGGERED),
            Map.entry("second_wind", Kind.TRIGGERED),
            Map.entry("immortal", Kind.TRIGGERED),
            Map.entry("soul_siphon", Kind.TRIGGERED),
            Map.entry("berserker", Kind.TRIGGERED),
            Map.entry("jetpack", Kind.ACTIVE),
            Map.entry("blink", Kind.ACTIVE),
            Map.entry("phantom", Kind.ACTIVE),
            Map.entry("blizzard", Kind.ACTIVE),
            Map.entry("grapple", Kind.ACTIVE),
            Map.entry("shockwave", Kind.ACTIVE),
            Map.entry("rewind", Kind.ACTIVE),
            Map.entry("shadow_step", Kind.ACTIVE),
            Map.entry("thors_wrath", Kind.ACTIVE),
            Map.entry("black_hole", Kind.ACTIVE),
            Map.entry("dragon_breath", Kind.ACTIVE),
            Map.entry("time_stop", Kind.ACTIVE),
            Map.entry("reapers_mark", Kind.ACTIVE),
            Map.entry("divine_aegis", Kind.ACTIVE),
            Map.entry("angel_wings", Kind.ACTIVE),
            Map.entry("overclock", Kind.PASSIVE),
            Map.entry("appraiser", Kind.PASSIVE),
            Map.entry("lucky_streak", Kind.PASSIVE),
            Map.entry("token_tycoon", Kind.PASSIVE),
            Map.entry("cache_cracker", Kind.PASSIVE),
            Map.entry("reinforced_gate", Kind.PASSIVE),
            Map.entry("quick_fix", Kind.PASSIVE),
            Map.entry("plunderer", Kind.PASSIVE),
            Map.entry("demolisher", Kind.PASSIVE),
            Map.entry("turret_tech", Kind.PASSIVE),
            Map.entry("lockdown", Kind.ACTIVE),
            Map.entry("repair_drone", Kind.ACTIVE),
            Map.entry("rush_hour", Kind.ACTIVE),
            Map.entry("midas_touch", Kind.PASSIVE),
            Map.entry("hyperdrive", Kind.PASSIVE),
            Map.entry("jackpot", Kind.PASSIVE),
            Map.entry("iron_curtain", Kind.PASSIVE),
            Map.entry("warlord", Kind.PASSIVE),
            Map.entry("sentinel", Kind.PASSIVE),
            Map.entry("golden_hour", Kind.ACTIVE),
            Map.entry("overdrive", Kind.ACTIVE),
            Map.entry("fortress_mode", Kind.ACTIVE));

    static final Set<String> NOT_IN_COMBAT_BY_DEFAULT = Set.of(
            "jetpack", "blink", "phantom", "grapple", "rewind", "shadow_step", "angel_wings");

    double num(String key, double fallback) {
        Double v = numbers.get(key);
        return v == null || v.isNaN() || v.isInfinite() ? fallback : v;
    }

    int ticks(String key, double fallbackSeconds) {
        double s = num(key, fallbackSeconds);
        return (int) Math.max(1, Math.min(12_000, Math.round(s * 20)));
    }

    long cooldownMs() {
        return (long) Math.max(0, Math.min(86_400_000L, num("cooldown", 0) * 1000));
    }

    boolean isActive() {
        return kind == Kind.ACTIVE;
    }
}
