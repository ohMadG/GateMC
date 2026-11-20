package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.util.Money;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * A lockbox reward: an amount of money in cents. BlockvilleCore also allowed "credits" (its premium currency); GensCore
 * has no credits, so those entries are refused when the config is read (see CratesModule).
 */
public record LockboxReward(long cents) {

    public static final LockboxReward NONE = new LockboxReward(0);

    /** Most one reward may pay, so a typo in the config cannot print money. Checklist 8. */
    static final long MAX_CENTS = 100_000_000L * 100L;

    /**
     * Reads "$1000", "1,000 money" or "1000". Returns null for anything else, including "credits" and amounts with
     * more than two decimals, negative, or above {@link #MAX_CENTS}.
     */
    public static @Nullable LockboxReward parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT).replace(",", "");
        if (text.contains("credit")) {
            return null;
        }
        String digits = text.replace("$", "").replace("money", "").trim();
        if (digits.equals("0")) {
            return NONE;
        }
        var parsed = Money.parse(digits);
        if (parsed.isEmpty() || parsed.getAsLong() > MAX_CENTS) {
            return null;
        }
        return new LockboxReward(parsed.getAsLong());
    }

    public boolean isEmpty() {
        return cents <= 0;
    }

    public String display() {
        return Money.format(cents);
    }
}
