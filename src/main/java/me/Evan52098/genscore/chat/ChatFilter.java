package me.Evan52098.genscore.chat;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Decides whether a chat message is allowed. A smaller version of BlockvilleCore's ChatFilterService using its
 * normalizer: banned and zero-tolerance words and phrases (with safe words), advertising, caps and spam. Webhooks,
 * the false-positive review queue and the split-word tracker are not ported. Thread-safe (chat is async).
 */
final class ChatFilter {

    record Result(boolean blocked, boolean alertStaff, String category, String reason) {
        static final Result OK = new Result(false, false, "", "");
    }

    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b[a-z0-9-]{2,}\\s*(\\.|\\(dot\\)|\\[dot\\]| dot )\\s*(com|net|org|gg|io|me|us|uk|co|xyz|club|fun|pro|tk|ml|ga|cf|de|eu|ru)\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\s*[.,]\\s*\\d{1,3}){3}\\b");

    private final boolean enabled;
    private final List<String> severe;
    private final List<String> banned;
    private final List<String> safe;
    private final boolean advertising;
    private final List<String> allowedDomains;
    private final int capsMinLength;
    private final double capsMaxRatio;
    private final int repeatWindowSeconds;
    private final int burstMessages;
    private final int burstSeconds;

    private final Map<UUID, Deque<Long>> sentAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastNormalized = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAt = new ConcurrentHashMap<>();

    ChatFilter(@Nullable ConfigurationSection s) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        enabled = s.getBoolean("enabled", true);
        severe = normalizeAll(s.getStringList("zero-tolerance-words"));
        banned = normalizeAll(s.getStringList("banned-words"));
        safe = normalizeAll(s.getStringList("safe-words"));
        advertising = s.getBoolean("advertising.enabled", true);
        allowedDomains = s.getStringList("advertising.allowed-domains").stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        capsMinLength = s.getInt("caps.min-length", 8);
        capsMaxRatio = s.getDouble("caps.max-ratio", 0.7);
        repeatWindowSeconds = s.getInt("spam.repeat-window-seconds", 30);
        burstMessages = s.getInt("spam.burst-messages", 5);
        burstSeconds = s.getInt("spam.burst-seconds", 5);
    }

    private static List<String> normalizeAll(List<String> words) {
        return words.stream().map(ChatFilterNormalizer::normalize).filter(w -> !w.isBlank()).distinct().toList();
    }

    Result check(UUID player, String raw) {
        if (!enabled) {
            return Result.OK;
        }
        String normalized = ChatFilterNormalizer.normalize(raw);
        String withoutSafe = normalized;
        for (String s : safe) {
            withoutSafe = withoutSafe.replace(s, "");
        }
        List<ChatFilterNormalizer.Token> tokens = ChatFilterNormalizer.tokenize(raw);

        if (contains(withoutSafe, tokens, severe)) {
            return new Result(true, true, "zero-tolerance", "Zero-tolerance content");
        }
        if (contains(withoutSafe, tokens, banned)) {
            return new Result(true, true, "banned-word", "Banned word or phrase");
        }
        if (advertising) {
            String reason = advertisingReason(raw);
            if (reason != null) {
                return new Result(true, true, "advertising", reason);
            }
        }
        if (raw.length() >= capsMinLength) {
            long letters = raw.chars().filter(Character::isLetter).count();
            long upper = raw.chars().filter(Character::isUpperCase).count();
            if (letters >= capsMinLength && (double) upper / letters > capsMaxRatio) {
                return new Result(true, false, "caps", "Too many capital letters");
            }
        }
        long now = System.currentTimeMillis();
        String previous = lastNormalized.put(player, normalized);
        Long previousAt = lastAt.put(player, now);
        if (repeatWindowSeconds > 0 && previous != null && previous.equals(normalized) && !normalized.isEmpty()
                && previousAt != null && now - previousAt < repeatWindowSeconds * 1000L) {
            return new Result(true, false, "spam", "Please don't repeat the same message");
        }
        if (burstMessages > 0) {
            Deque<Long> times = sentAt.computeIfAbsent(player, k -> new ArrayDeque<>());
            synchronized (times) {
                times.addLast(now);
                while (!times.isEmpty() && now - times.peekFirst() > burstSeconds * 1000L) {
                    times.pollFirst();
                }
                if (times.size() > burstMessages) {
                    return new Result(true, false, "spam", "You are sending messages too fast");
                }
            }
        }
        return Result.OK;
    }

    /**
     * A word matches inside any single word of the message. Entries of 4+ letters (and phrases, which normalise to
     * one run of letters) also match across the whole message, which catches "b a d w o r d" style spacing.
     */
    private static boolean contains(String joined, List<ChatFilterNormalizer.Token> tokens, List<String> words) {
        for (String w : words) {
            for (ChatFilterNormalizer.Token t : tokens) {
                if (t.matchable().contains(w)) {
                    return true;
                }
            }
            if (w.length() >= 4 && joined.contains(w)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable String advertisingReason(String raw) {
        String lower = raw.toLowerCase(Locale.ROOT);
        for (String allowed : allowedDomains) {
            lower = lower.replace(allowed, "");
        }
        if (IPV4.matcher(lower).find()) {
            return "Server addresses are not allowed";
        }
        if (DOMAIN.matcher(lower).find()) {
            return "Links are not allowed";
        }
        return null;
    }

    void forget(UUID player) {
        sentAt.remove(player);
        lastNormalized.remove(player);
        lastAt.remove(player);
    }
}
