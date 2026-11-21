package me.Evan52098.genscore.chat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

public final class GgWave {

    private static final Pattern GG = Pattern.compile("(?i)\\s*g{2,}[\\s!1.]*");
    private static final long MIN_GAP_MS = 250;
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static volatile long until;
    private static final Map<UUID, Long> last = new ConcurrentHashMap<>();

    private GgWave() {
    }

    public static void start(long millis) {
        long end = System.currentTimeMillis() + millis;
        if (end > until) {
            until = end;
        }
        last.clear();
    }

    public static boolean active() {
        return System.currentTimeMillis() < until;
    }

    static boolean isGg(String raw) {
        return raw.length() <= 32 && GG.matcher(raw).matches();
    }

    static boolean tooFast(UUID player) {
        long now = System.currentTimeMillis();
        Long before = last.put(player, now);
        return before != null && now - before < MIN_GAP_MS;
    }

    static Component render(String raw) {
        int phase = ThreadLocalRandom.current().nextInt(10);
        return MM.deserialize("<bold><rainbow:" + phase + ">" + MM.escapeTags(raw.trim()) + "</rainbow></bold>");
    }
}
