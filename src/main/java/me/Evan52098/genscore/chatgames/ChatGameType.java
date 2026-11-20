package me.Evan52098.genscore.chatgames;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** The chat game kinds, copied from BlockvilleCore's ChatGameType (same names, so config lists carry over). */
public enum ChatGameType {
    UNSCRAMBLE("Unscramble"),
    UNREVERSE("Unreverse"),
    REACTION("Reaction"),
    FILLOUT("Fill In"),
    RANDOM_STRING("Retype"),
    MATH("Math"),
    VARIABLE("Variable"),
    TRIVIA("Trivia");

    private final String displayName;

    ChatGameType(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }

    public static @Nullable ChatGameType parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_'));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
