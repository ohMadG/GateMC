package me.Evan52098.genscore.chat;

import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Decides whether a chat message is allowed (HawkMC's filter): zero-tolerance, banned and staff-added words with safe
 * words and an English dictionary against false positives, advertising, caps, floods and spam. Staff decisions from
 * {@code /filter add} and {@code /filter deny} live in {@code pvp_chat_filter_overrides}. It only blocks; repeated word
 * hits become a report (see {@link FilterReports}). Thread-safe (chat is async).
 */
final class ChatFilter {

    public record Result(boolean blocked, boolean alertStaff, String category, String reason, @Nullable String matchedWord) {
        static final Result OK = new Result(false, false, "", "", null);

        static Result wordHit(String category, String reason, String matchedWord, boolean alert) {
            return new Result(true, alert, category, reason, matchedWord);
        }

        static Result other(String category, String reason) {
            return new Result(true, false, category, reason, null);
        }
    }

    private static final Pattern DOMAIN = Pattern.compile(
            "(?i)\\b[a-z0-9-]{2,}\\s*(\\.|\\(dot\\)|\\[dot\\]| dot )\\s*(com|net|org|gg|io|me|us|uk|co|xyz|club|fun|pro|tk|ml|ga|cf|de|eu|ru)\\b");
    private static final Pattern IPV4 = Pattern.compile("\\b\\d{1,3}(\\s*[.,]\\s*\\d{1,3}){3}\\b");

    private static final Set<String> DICTIONARY = loadDictionary();

    private static Set<String> loadDictionary() {
        Set<String> words = new HashSet<>(400_000);
        try (InputStream in = ChatFilter.class.getResourceAsStream("/dictionary-en.txt")) {
            if (in == null) {
                return words;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String word = line.trim().toLowerCase(Locale.ROOT);
                    if (!word.isEmpty()) {
                        words.add(word);
                    }
                }
            }
        } catch (IOException e) {
            Logger.getLogger(ChatFilter.class.getName()).log(Level.WARNING, "Failed to load dictionary-en.txt", e);
        }
        return words;
    }

    private final boolean enabled;
    private final boolean alertBannedWords;
    private final List<String> severe;
    private final List<String> banned;
    private final List<String> safe;
    private final Map<String, String> display;
    private final boolean advertising;
    private final List<String> allowedDomains;
    private final int capsMinLength;
    private final double capsMaxRatio;
    private final int repeatWindowSeconds;
    private final int burstMessages;
    private final int floodRun;
    private final int floodMinLength;
    private final int floodMaxDistinct;
    private final int burstSeconds;

    private final Map<UUID, Deque<Long>> sentAt = new ConcurrentHashMap<>();
    private final Map<UUID, String> lastNormalized = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastAt = new ConcurrentHashMap<>();

    private final Database db;
    private final Logger log;
    private volatile List<String> addedWords = List.of();
    private volatile Set<String> deniedWords = Set.of();

    ChatFilter(@Nullable ConfigurationSection s, Database db, Logger log) throws SQLException {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        enabled = s.getBoolean("enabled", true);
        alertBannedWords = s.getBoolean("alert-banned-words", false);
        Map<String, String> displayMap = new ConcurrentHashMap<>();
        severe = normalizeAll(s.getStringList("zero-tolerance-words"), displayMap);
        banned = normalizeAll(s.getStringList("banned-words"), displayMap);
        safe = normalizeAll(s.getStringList("safe-words"));
        display = displayMap;
        advertising = s.getBoolean("advertising.enabled", true);
        allowedDomains = s.getStringList("advertising.allowed-domains").stream().map(d -> d.toLowerCase(Locale.ROOT)).toList();
        capsMinLength = s.getInt("caps.min-length", 8);
        capsMaxRatio = s.getDouble("caps.max-ratio", 0.7);
        repeatWindowSeconds = s.getInt("spam.repeat-window-seconds", 30);
        burstMessages = s.getInt("spam.burst-messages", 5);
        floodRun = s.getInt("spam.max-repeated-characters", 12);
        floodMinLength = s.getInt("spam.flood-min-length", 40);
        floodMaxDistinct = s.getInt("spam.flood-max-distinct", 6);
        burstSeconds = s.getInt("spam.burst-seconds", 5);

        this.db = db;
        this.log = log;
        Migrations.apply(db, "chatfilter", log,
                "CREATE TABLE IF NOT EXISTS pvp_chat_filter_overrides (word TEXT PRIMARY KEY, decision TEXT NOT NULL, "
                        + "decided_by TEXT NOT NULL, decided_at BIGINT NOT NULL)");
        loadOverrides();
    }

    private void loadOverrides() throws SQLException {
        List<String> added = new ArrayList<>();
        Set<String> denied = new HashSet<>();
        db.sync(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT word, decision FROM pvp_chat_filter_overrides");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String word = rs.getString(1);
                    if ("added".equals(rs.getString(2))) {
                        added.add(word);
                    } else {
                        denied.add(word);
                    }
                }
            }
            return null;
        });
        addedWords = List.copyOf(added);
        deniedWords = Set.copyOf(denied);
    }

    boolean addWord(String rawWord, String staffName) {
        String norm = ChatFilterNormalizer.normalize(rawWord);
        if (norm.isBlank()) {
            return false;
        }
        if (severe.contains(norm) || banned.contains(norm) || addedWords.contains(norm)) {
            return false;
        }
        display.putIfAbsent(norm, rawWord.trim().toLowerCase(Locale.ROOT));
        List<String> added = new ArrayList<>(addedWords);
        added.add(norm);
        addedWords = List.copyOf(added);
        Set<String> denied = new HashSet<>(deniedWords);
        denied.remove(norm);
        deniedWords = Set.copyOf(denied);
        persistOverride(norm, "added", staffName);
        return true;
    }

    enum DenyOutcome { DENIED, ALREADY_ALLOWED, NOT_LISTED }

    DenyOutcome denyWord(String rawWord, String staffName) {
        String norm = ChatFilterNormalizer.normalize(rawWord);
        if (norm.isBlank()) {
            return DenyOutcome.NOT_LISTED;
        }
        if (deniedWords.contains(norm)) {
            return DenyOutcome.ALREADY_ALLOWED;
        }
        if (!severe.contains(norm) && !banned.contains(norm) && !addedWords.contains(norm)) {
            return DenyOutcome.NOT_LISTED;
        }
        Set<String> denied = new HashSet<>(deniedWords);
        denied.add(norm);
        deniedWords = Set.copyOf(denied);
        List<String> added = new ArrayList<>(addedWords);
        added.remove(norm);
        addedWords = List.copyOf(added);
        persistOverride(norm, "denied", staffName);
        return DenyOutcome.DENIED;
    }

    List<String> listedWithin(String rawWord) {
        String norm = ChatFilterNormalizer.normalize(rawWord);
        List<String> out = new ArrayList<>();
        for (List<String> list : List.of(severe, banned, addedWords)) {
            for (String w : list) {
                if (!w.equals(norm) && norm.contains(w) && !deniedWords.contains(w)) {
                    out.add(display.getOrDefault(w, w));
                }
            }
        }
        return out;
    }

    private void persistOverride(String word, String decision, String staffName) {
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_chat_filter_overrides (word, decision, decided_by, decided_at) VALUES (?, ?, ?, ?) "
                            + "ON CONFLICT (word) DO UPDATE SET decision = excluded.decision, decided_by = excluded.decided_by, "
                            + "decided_at = excluded.decided_at")) {
                ps.setString(1, word);
                ps.setString(2, decision);
                ps.setString(3, staffName);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            log.log(Level.SEVERE, "[chat] Could not save the filter override for \"" + word + "\"", e);
            return null;
        });
    }

    private static List<String> normalizeAll(List<String> words) {
        return words.stream().map(ChatFilterNormalizer::normalize).filter(w -> !w.isBlank()).distinct().toList();
    }

    private static List<String> normalizeAll(List<String> words, Map<String, String> displayOut) {
        List<String> out = new ArrayList<>();
        for (String raw : words) {
            String norm = ChatFilterNormalizer.normalize(raw);
            if (norm.isBlank()) {
                continue;
            }
            displayOut.putIfAbsent(norm, raw.trim().toLowerCase(Locale.ROOT));
            if (!out.contains(norm)) {
                out.add(norm);
            }
        }
        return out;
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

        String severeHit = contains(withoutSafe, tokens, severe);
        if (severeHit != null) {
            return Result.wordHit("high-risk", "Zero-tolerance content", display.getOrDefault(severeHit, severeHit), true);
        }
        String bannedHit = contains(withoutSafe, tokens, banned);
        if (bannedHit != null) {
            return Result.wordHit("blocked-word", "Banned word or phrase", display.getOrDefault(bannedHit, bannedHit), alertBannedWords);
        }
        String addedHit = contains(withoutSafe, tokens, addedWords);
        if (addedHit != null) {
            return Result.wordHit("blocked-word", "Banned word or phrase", display.getOrDefault(addedHit, addedHit), true);
        }
        if (advertising) {
            String reason = advertisingReason(raw);
            if (reason != null) {
                return Result.other("advertising", reason);
            }
        }
        if (raw.length() >= capsMinLength) {
            long letters = raw.chars().filter(Character::isLetter).count();
            long upper = raw.chars().filter(Character::isUpperCase).count();
            if (letters >= capsMinLength && (double) upper / letters > capsMaxRatio) {
                return Result.other("caps", "Too many capital letters");
            }
        }
        if (floodRun > 0 && flooding(raw)) {
            return Result.other("spam", "Please don't flood chat");
        }
        long now = System.currentTimeMillis();
        String previous = lastNormalized.put(player, normalized);
        Long previousAt = lastAt.put(player, now);
        if (repeatWindowSeconds > 0 && previous != null && previous.equals(normalized) && !normalized.isEmpty()
                && previousAt != null && now - previousAt < repeatWindowSeconds * 1000L) {
            return Result.other("spam", "Please don't repeat the same message");
        }
        if (burstMessages > 0) {
            Deque<Long> times = sentAt.computeIfAbsent(player, k -> new ArrayDeque<>());
            synchronized (times) {
                times.addLast(now);
                while (!times.isEmpty() && now - times.peekFirst() > burstSeconds * 1000L) {
                    times.pollFirst();
                }
                if (times.size() > burstMessages) {
                    return Result.other("spam", "You are sending messages too fast");
                }
            }
        }
        return Result.OK;
    }

    private boolean flooding(String raw) {
        int run = 1;
        int longest = 1;
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        int counted = 0;
        int prev = -1;
        for (int i = 0; i < raw.length(); i++) {
            int ch = Character.toLowerCase(raw.charAt(i));
            if (Character.isWhitespace(ch)) {
                prev = -1;
                continue;
            }
            counted++;
            distinct.add(ch);
            run = ch == prev ? run + 1 : 1;
            longest = Math.max(longest, run);
            prev = ch;
        }
        return longest >= floodRun || (counted >= floodMinLength && distinct.size() <= floodMaxDistinct);
    }

    Result wordsOnly(String raw) {
        if (!enabled) {
            return Result.OK;
        }
        String normalized = ChatFilterNormalizer.normalize(raw);
        String withoutSafe = normalized;
        for (String s : safe) {
            withoutSafe = withoutSafe.replace(s, "");
        }
        List<ChatFilterNormalizer.Token> tokens = ChatFilterNormalizer.tokenize(raw);
        String severeHit = contains(withoutSafe, tokens, severe);
        if (severeHit != null) {
            return Result.wordHit("high-risk", "Zero-tolerance content", display.getOrDefault(severeHit, severeHit), true);
        }
        String bannedHit = contains(withoutSafe, tokens, banned);
        if (bannedHit != null) {
            return Result.wordHit("blocked-word", "Banned word or phrase", display.getOrDefault(bannedHit, bannedHit), alertBannedWords);
        }
        String addedHit = contains(withoutSafe, tokens, addedWords);
        if (addedHit != null) {
            return Result.wordHit("blocked-word", "Banned word or phrase", display.getOrDefault(addedHit, addedHit), true);
        }
        return Result.OK;
    }

    private @Nullable String contains(String joined, List<ChatFilterNormalizer.Token> tokens, List<String> words) {
        List<String> tokensWithoutSafe = new ArrayList<>(tokens.size());
        for (ChatFilterNormalizer.Token t : tokens) {
            String stripped = t.matchable();
            for (String s : safe) {
                stripped = stripped.replace(s, "");
            }
            tokensWithoutSafe.add(stripped);
        }
        for (String w : words) {
            if (deniedWords.contains(w)) {
                continue;
            }
            boolean exactOnly = w.length() < 4;
            for (int i = 0; i < tokens.size(); i++) {
                ChatFilterNormalizer.Token t = tokens.get(i);
                String rawToken = t.matchable();
                if (exactOnly) {
                    if (rawToken.equals(w)) {
                        return w;
                    }
                    continue;
                }
                if (rawToken.equals(w)) {
                    return w;
                }
                if (!tokensWithoutSafe.get(i).contains(w)) {
                    continue;
                }
                if (DICTIONARY.contains(t.display())) {
                    continue;
                }
                return w;
            }
            if (!exactOnly && tokens.size() > 1 && joined.contains(w) && spansFromWordStart(tokensWithoutSafe, w)) {
                return w;
            }
        }
        return null;
    }

    private static boolean spansFromWordStart(List<String> tokens, String word) {
        for (int i = 0; i < tokens.size() - 1; i++) {
            String first = ChatFilterNormalizer.collapse(tokens.get(i));
            if (first.isEmpty() || first.length() >= word.length() || !word.startsWith(first)) {
                continue;
            }
            StringBuilder run = new StringBuilder(tokens.get(i));
            for (int k = i + 1; k < tokens.size(); k++) {
                run.append(tokens.get(k));
                String collapsed = ChatFilterNormalizer.collapse(run.toString());
                if (collapsed.startsWith(word)) {
                    return true;
                }
                if (!word.startsWith(collapsed)) {
                    break;
                }
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
