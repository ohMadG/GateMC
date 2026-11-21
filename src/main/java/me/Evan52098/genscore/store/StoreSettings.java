package me.Evan52098.genscore.store;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Collectors;

/** Everything under {@code store:} in config.yml, read once per enable/reload. Immutable. */
record StoreSettings(
        String url, String linkLabel, List<String> storeMessage,
        String purchaseFormat, String purchaseHover,
        boolean tebexEnabled, String tebexConfigFile, List<String> tebexKeyPaths, int refreshMinutes, int recheckPages,
        int maxPages,
        boolean topEnabled, Set<String> topExclude, String currencySymbol,
        Popup popup, Goal goal, Party party, Celebration celebration, KeyAll keyAll) {

    /** A box in one world, corners included. */
    record Zone(String world, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        boolean contains(String w, int x, int y, int z) {
            return world.equals(w) && x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
        }
    }

    record Popup(boolean enabled, long cooldownMillis, String title, String message, String openButton, String closeButton,
                 boolean chatLink, List<Zone> zones) {
    }

    /**
     * What a goal or a party gives. {@code consoleCommands} run once; {@code playerCommands} run once per online player
     * with {player}; {@code moneyCents} is paid to each online player (economy module).
     */
    record Reward(List<String> consoleCommands, List<String> playerCommands, long moneyCents) {
        boolean isEmpty() {
            return consoleCommands.isEmpty() && playerCommands.isEmpty() && moneyCents <= 0;
        }
    }

    record Goal(boolean enabled, long targetCents, ZoneId zone, int barLength, String line, String reachedBroadcast,
                Reward reward) {
    }

    record Celebration(boolean enabled, String sound, float volume, int ggWaveSeconds, int repeatGapSeconds,
                       String title, String subtitle, String bossbar, String ggWaveBroadcast) {
    }

    record KeyAll(boolean enabled, long stepCents, String scoreboard, String reachedBroadcast, Reward reward) {
    }

    record Party(boolean enabled, int countdownSeconds, boolean onePerIp, String startBroadcast, String doneBroadcast,
                 Reward reward) {
    }

    /** Most a reward may pay one player, so a typo in config can't flood the economy (checklist 8). */
    static final long MAX_REWARD_CENTS = 100_000_000L; // $1,000,000

    static StoreSettings load(@Nullable ConfigurationSection c, Logger log) {
        if (c == null) {
            c = new MemoryConfiguration();
        }
        ConfigurationSection pa = sub(c, "purchase-announce");
        ConfigurationSection t = sub(c, "tebex");
        ConfigurationSection top = sub(c, "top-spenders");
        List<String> keyPaths = t.getStringList("key-paths");
        if (keyPaths.isEmpty()) {
            keyPaths = List.of("server.secret-key", "secret-key", "secret");
        }
        List<String> storeMessage = c.getStringList("message");
        if (storeMessage.isEmpty()) {
            storeMessage = List.of("&6&lSTORE &8» &7Ranks, crate keys & more: {store}");
        }
        Set<String> exclude = top.getStringList("exclude").stream().map(s -> s.toLowerCase(Locale.ROOT)).collect(Collectors.toUnmodifiableSet());
        return new StoreSettings(
                c.getString("url", ""),
                c.getString("link-label", "&6&l[CLICK FOR STORE]"),
                List.copyOf(storeMessage),
                pa.getString("format", "&6&lSTORE &8» &e{player} &7just bought {item}&7! &8» &bClick to visit the store"),
                pa.getString("hover", "&7Click to open the store"),
                t.getBoolean("enabled", true),
                t.getString("config-file", "Tebex/config.yml"),
                List.copyOf(keyPaths),
                Math.max(2, t.getInt("refresh-minutes", 10)),
                Math.max(1, t.getInt("recheck-pages", 2)),
                Math.max(1, Math.min(1000, t.getInt("max-pages", 200))),
                top.getBoolean("enabled", true),
                exclude,
                top.getString("currency-symbol", "$"),
                popup(sub(c, "popup"), log),
                goal(sub(c, "goal"), log),
                party(sub(c, "party"), log),
                celebration(sub(c, "celebration")),
                keyAll(sub(c, "key-all"), log));
    }

    private static Celebration celebration(ConfigurationSection c) {
        return new Celebration(c.getBoolean("enabled", true),
                c.getString("sound", "hawkmc:donation"),
                (float) Math.max(0, Math.min(2, c.getDouble("volume", 0.6))),
                Math.max(0, Math.min(120, c.getInt("gg-wave-seconds", 15))),
                Math.max(0, c.getInt("repeat-gap-seconds", 10)),
                c.getString("title", "&6&l{player}"),
                c.getString("subtitle", "&fsupported GateMC! &7Type &a&lGG"),
                c.getString("bossbar", "&6&lGG WAVE &8- &fthank &e{player}"),
                c.getString("gg-wave-broadcast", "&6&lGG WAVE! &fType &a&lGG &fin chat to thank &e{player}&f! &7({seconds}s)"));
    }

    private static KeyAll keyAll(ConfigurationSection k, Logger log) {
        long step = cents(k.getDouble("every", 25.0));
        return new KeyAll(k.getBoolean("enabled", true) && step > 0, step,
                k.getString("scoreboard", "&f{current}&7/&f{step}"),
                k.getString("reached-broadcast", "&6&lKEY ALL &8» &7The store hit &f{step}&7! Everyone online gets a key."),
                reward(sub(k, "reward"), "store.key-all.reward", log));
    }

    private static ConfigurationSection sub(ConfigurationSection c, String path) {
        ConfigurationSection s = c.getConfigurationSection(path);
        return s != null ? s : new MemoryConfiguration();
    }

    private static Popup popup(ConfigurationSection p, Logger log) {
        List<Zone> zones = new ArrayList<>();
        for (Map<?, ?> m : p.getMapList("zones")) {
            Object world = m.get("world");
            int[] a = corner(m.get("min"));
            int[] b = corner(m.get("max"));
            if (world == null || a == null || b == null) {
                log.warning("[store] A popup zone needs world, min: [x, y, z] and max: [x, y, z]; skipped " + m);
                continue;
            }
            zones.add(new Zone(world.toString(), Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.min(a[2], b[2]),
                    Math.max(a[0], b[0]), Math.max(a[1], b[1]), Math.max(a[2], b[2])));
        }
        return new Popup(p.getBoolean("enabled", true),
                Math.max(1, p.getLong("cooldown-minutes", 10)) * 60_000L,
                p.getString("title", "STORE"),
                p.getString("message", "Crate keys, ranks and bundles. Every purchase helps keep the server running."),
                p.getString("open-button", "Open store"),
                p.getString("close-button", "Not now"),
                p.getBoolean("chat-link", true),
                List.copyOf(zones));
    }

    private static int @Nullable [] corner(Object o) {
        if (!(o instanceof List<?> list) || list.size() != 3) {
            return null;
        }
        int[] out = new int[3];
        for (int i = 0; i < 3; i++) {
            if (!(list.get(i) instanceof Number n)) {
                return null;
            }
            out[i] = (int) Math.floor(n.doubleValue());
        }
        return out;
    }

    private static Goal goal(ConfigurationSection g, Logger log) {
        ZoneId zone;
        try {
            zone = ZoneId.of(g.getString("timezone", "UTC"));
        } catch (DateTimeException e) {
            log.warning("[store] Unknown store.goal.timezone, using UTC.");
            zone = ZoneId.of("UTC");
        }
        long target = cents(g.getDouble("target", 500.0));
        return new Goal(g.getBoolean("enabled", true) && target > 0, target, zone,
                Math.max(5, Math.min(60, g.getInt("bar-length", 20))),
                g.getString("line", "&6&lCOMMUNITY GOAL &8» &f{current} &7/ &f{target} &8[{bar}&8] &f{percent}%"),
                g.getString("reached-broadcast", "&6&lCOMMUNITY GOAL &8» &7This month's store goal of &f{target} &7was reached! Everyone online gets a reward."),
                reward(sub(g, "reward"), "store.goal.reward", log));
    }

    private static Party party(ConfigurationSection p, Logger log) {
        return new Party(p.getBoolean("enabled", true),
                Math.max(0, Math.min(300, p.getInt("countdown-seconds", 10))),
                p.getBoolean("one-per-ip", true),
                p.getString("start-broadcast", "&6&lSTORE PARTY &8» &f{player} &7started a store party! Rewards in &f{seconds}s&7..."),
                p.getString("done-broadcast", "&6&lSTORE PARTY &8» &7Everyone online got a reward. Say thanks to &f{player}&7!"),
                reward(sub(p, "reward"), "store.party.reward", log));
    }

    private static Reward reward(ConfigurationSection r, String path, Logger log) {
        long money = cents(r.getDouble("money", 0));
        if (money < 0 || money > MAX_REWARD_CENTS) {
            log.warning("[" + "store] " + path + ".money must be between 0 and 1000000; using 0.");
            money = 0;
        }
        return new Reward(clean(r.getStringList("console-commands")), clean(r.getStringList("player-commands")), money);
    }

    private static List<String> clean(List<String> commands) {
        List<String> out = new ArrayList<>();
        for (String s : commands) {
            String c = s.trim();
            if (c.startsWith("/")) {
                c = c.substring(1);
            }
            if (!c.isEmpty()) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /** Dollars from config to cents; NaN, infinity and negatives become 0 (checklist 8). */
    static long cents(double amount) {
        if (Double.isNaN(amount) || Double.isInfinite(amount) || amount <= 0 || amount > 1e12) {
            return 0;
        }
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValue();
    }

    String formatMoney(long cents) {
        return currencySymbol + String.format(Locale.ROOT, "%,.2f", cents / 100.0);
    }
}
