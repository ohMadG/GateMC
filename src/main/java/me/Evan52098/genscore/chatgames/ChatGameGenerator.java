package me.Evan52098.genscore.chatgames;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;

/**
 * Makes rounds. Copied from BlockvilleCore's ChatGameGenerator. Changes: the word list and riddles default to HxCore's
 * chat-games data (words, riddles), the math numbers go up to {@code number-max}, and the data is handed in (immutable)
 * instead of read from a ConfigManager, so a reload swaps it atomically.
 */
public final class ChatGameGenerator {

    /** HxCore's chat-games.words. */
    static final List<String> DEFAULT_WORDS = List.of(
            "diamond", "netherite", "creeper", "skeleton", "pumpkin", "enderman", "obsidian", "crystal", "totem", "elytra",
            "beacon", "villager", "zombie", "spider", "emerald", "redstone", "trident", "shulker", "anvil", "potion",
            "cobweb", "lantern", "phantom", "warden", "blaze", "ghast", "piglin", "wither", "dragon", "pickaxe", "shield",
            "arrow", "cauldron", "spawner", "witch", "scythe", "candle", "gravestone");

    /** HxCore's chat-games.riddles, then BlockvilleCore's default trivia. */
    static final List<Map.Entry<String, String>> DEFAULT_TRIVIA = List.of(
            Map.entry("I explode when you get too close and I hiss before I do. What am I?", "creeper"),
            Map.entry("I'm green, I'm ugly and I'll trade you anything for emeralds. What am I?", "villager"),
            Map.entry("Hold me in your hand when you're about to die and I'll save you. What am I?", "totem"),
            Map.entry("I'm the strongest material in the game and I come from ancient debris. What am I?", "netherite"),
            Map.entry("Look me in the eyes and I'll come for you. What mob am I?", "enderman"),
            Map.entry("I let you fly but only if you're falling first. What am I?", "elytra"),
            Map.entry("I'm orange, I grow on the ground and I get carved at Halloween. What am I?", "pumpkin"),
            Map.entry("I cry fireballs in the Nether. What am I?", "ghast"),
            Map.entry("I'm the dark block you get when water flows onto a lava source. What am I?", "obsidian"),
            Map.entry("I live in a deep dark city and I'm blind. What am I?", "warden"),
            Map.entry("I love gold and I live in the Nether. What am I?", "piglin"),
            Map.entry("Put me on a pyramid of iron or diamond blocks and I'll give you powers. What am I?", "beacon"),
            Map.entry("How many sides does a dice have? (number)", "6"),
            Map.entry("What mob drops ender pearls?", "Enderman"),
            Map.entry("How many slots does a single chest have? (number)", "27"),
            Map.entry("What currency do villagers trade for?", "Emerald"),
            Map.entry("How many eyes of ender are needed to activate a stronghold portal? (number)", "12"),
            Map.entry("What weapon can be thrown and comes back to you?", "Trident"),
            Map.entry("What is the maximum stack size for most items? (number)", "64"),
            Map.entry("What mob guards ocean monuments?", "Guardian"),
            Map.entry("What do you use to tame a wolf?", "Bone"));

    /** Immutable data for one configuration. */
    public record Data(List<String> words, List<Map.Entry<String, String>> trivia, int numberMax) {
        public Data {
            words = words.isEmpty() ? DEFAULT_WORDS : List.copyOf(words);
            trivia = trivia.isEmpty() ? DEFAULT_TRIVIA : List.copyOf(trivia);
            numberMax = Math.max(5, Math.min(10_000, numberMax));
        }
    }

    private ChatGameGenerator() {
    }

    public static ChatGameRound generate(ChatGameType type, Data data) {
        return switch (type) {
            case UNSCRAMBLE -> wordGame(type, data, ChatGameGenerator::scramble);
            case UNREVERSE -> wordGame(type, data, w -> new StringBuilder(w).reverse().toString());
            case REACTION -> wordGame(type, data, w -> w);
            case FILLOUT -> wordGame(type, data, ChatGameGenerator::blankOutRandomLetters);
            case RANDOM_STRING -> randomString();
            case MATH -> math(data.numberMax());
            case VARIABLE -> variable(data.numberMax());
            case TRIVIA -> trivia(data);
        };
    }

    private static ChatGameRound wordGame(ChatGameType type, Data data, Function<String, String> transform) {
        List<String> words = data.words();
        String word = words.get(ThreadLocalRandom.current().nextInt(words.size()));
        return new ChatGameRound(type, transform.apply(word), word, true);
    }

    private static ChatGameRound randomString() {
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder b = new StringBuilder(6);
        ThreadLocalRandom r = ThreadLocalRandom.current();
        for (int i = 0; i < 6; i++) {
            b.append(chars.charAt(r.nextInt(chars.length())));
        }
        String value = b.toString();
        return new ChatGameRound(ChatGameType.RANDOM_STRING, value, value, false);
    }

    private static ChatGameRound math(int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        char[] operators = {'+', '-', '*'};
        char op = operators[r.nextInt(operators.length)];
        int a = r.nextInt(1, max + 1);
        int b = r.nextInt(1, max + 1);
        int result = switch (op) {
            case '+' -> a + b;
            case '-' -> a - b;
            default -> a * b;
        };
        return new ChatGameRound(ChatGameType.MATH, a + " " + op + " " + b + " = ?", String.valueOf(result), false);
    }

    private static ChatGameRound variable(int max) {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int offset = r.nextInt(1, Math.max(2, max / 2));
        int result = r.nextInt(offset + 1, max + offset + 1);
        return new ChatGameRound(ChatGameType.VARIABLE, "x + " + offset + " = " + result + " (solve for x)", String.valueOf(result - offset), false);
    }

    private static ChatGameRound trivia(Data data) {
        Map.Entry<String, String> e = data.trivia().get(ThreadLocalRandom.current().nextInt(data.trivia().size()));
        return new ChatGameRound(ChatGameType.TRIVIA, e.getKey(), e.getValue(), false);
    }

    static String scramble(String word) {
        char[] letters = word.toCharArray();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String scrambled;
        int attempts = 0;
        do {
            for (int i = letters.length - 1; i > 0; i--) {
                int j = r.nextInt(i + 1);
                char tmp = letters[i];
                letters[i] = letters[j];
                letters[j] = tmp;
            }
            scrambled = new String(letters);
            attempts++;
        } while (scrambled.equalsIgnoreCase(word) && attempts < 10);
        return scrambled;
    }

    static String blankOutRandomLetters(String word) {
        char[] letters = word.toCharArray();
        ThreadLocalRandom r = ThreadLocalRandom.current();
        int blanks = Math.max(1, letters.length / 3);
        for (int i = 0; i < blanks; i++) {
            letters[r.nextInt(letters.length)] = '_';
        }
        return new String(letters);
    }

    /** Reads {@code trivia:} entries shaped {q: ..., a: ...} (HxCore's riddles use the same shape). */
    static List<Map.Entry<String, String>> parseTrivia(List<Map<?, ?>> raw) {
        List<Map.Entry<String, String>> out = new ArrayList<>();
        for (Map<?, ?> m : raw) {
            Object q = m.get("q");
            Object a = m.get("a");
            if (q != null && a != null && !q.toString().isBlank() && !a.toString().isBlank()) {
                out.add(Map.entry(q.toString(), a.toString().trim()));
            }
        }
        return out;
    }
}
