package me.Evan52098.genscore.wordle;

import java.util.ArrayList;
import java.util.List;

/**
 * A player's current Wordle state, as stored in {@code pvp_wordle}. Immutable: a guess makes a new one, which only
 * replaces the cached copy after the database accepted it.
 *
 * @param day      the server day (yyyy-mm-dd) this game and {@code earnedCents} belong to
 * @param game     how many games the player has started that day (game 1 is the shared daily word)
 * @param guesses  the guesses so far, lowercase
 * @param earnedCents Wordle money paid to the player that day (for the daily cap)
 */
public record WordleGame(String day, int game, String word, List<String> guesses, boolean finished, long earnedCents) {

    public static final int MAX_GUESSES = 6;
    public static final int GREEN = 2;
    public static final int YELLOW = 1;
    public static final int GRAY = 0;

    public WordleGame {
        guesses = List.copyOf(guesses);
    }

    public boolean solved() {
        return !guesses.isEmpty() && guesses.get(guesses.size() - 1).equals(word);
    }

    public String guessesText() {
        return String.join(",", guesses);
    }

    public static List<String> parseGuesses(String raw) {
        List<String> out = new ArrayList<>();
        if (raw != null && !raw.isBlank()) {
            for (String g : raw.split(",")) {
                if (!g.isBlank()) {
                    out.add(g.trim());
                }
            }
        }
        return out;
    }

    /** Wordle colouring with repeated letters handled: greens first, then yellows only while unmatched copies remain. */
    public static int[] score(String guess, String answer) {
        int n = answer.length();
        int[] result = new int[n];
        int[] left = new int[26];
        for (int i = 0; i < n; i++) {
            if (guess.charAt(i) == answer.charAt(i)) {
                result[i] = GREEN;
            } else {
                left[answer.charAt(i) - 'a']++;
            }
        }
        for (int i = 0; i < n; i++) {
            if (result[i] == GREEN) {
                continue;
            }
            int c = guess.charAt(i) - 'a';
            if (c >= 0 && c < 26 && left[c] > 0) {
                result[i] = YELLOW;
                left[c]--;
            }
        }
        return result;
    }
}
