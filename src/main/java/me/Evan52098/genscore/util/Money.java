package me.Evan52098.genscore.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Money is stored as whole cents in a {@code long}, so sums are exact (no floating-point drift).
 * Doubles only appear at the edges: Vault, which uses doubles, and display.
 */
public final class Money {

    private static final ThreadLocal<DecimalFormat> FORMAT = ThreadLocal.withInitial(() -> new DecimalFormat("#,##0.00"));
    private static final String[] SUFFIXES = {"", "k", "m", "b", "t"};

    private Money() {
    }

    public static long toCents(double amount) {
        return BigDecimal.valueOf(amount).setScale(2, RoundingMode.HALF_UP).movePointRight(2).longValueExact();
    }

    public static double toDouble(long cents) {
        return cents / 100.0;
    }

    private static final String[] COMPACT = {"", "K", "M", "B", "T", "Qa", "Qi"};

    /** "$950.00", "$12.5K", "$3.42M", "$1.07B": short enough for labels and menus at any size. */
    public static String compact(long cents) {
        double v = toDouble(cents);
        if (Math.abs(v) < 10_000) {
            return format(cents);
        }
        int i = 0;
        while (Math.abs(v) >= 1000 && i < COMPACT.length - 1) {
            v /= 1000;
            i++;
        }
        String n = Math.abs(v) >= 100 ? String.format(Locale.ROOT, "%.0f", v)
                : Math.abs(v) >= 10 ? String.format(Locale.ROOT, "%.1f", v) : String.format(Locale.ROOT, "%.2f", v);
        return "$" + n + COMPACT[i];
    }

    /** "$1,234.50" */
    public static String format(long cents) {
        return "$" + FORMAT.get().format(toDouble(cents));
    }

    /**
     * Parses what a player types: "1500", "1,500", "1.5k", "2m", "$20". Returns empty for anything invalid,
     * zero, negative, or with more than two decimal places.
     */
    public static OptionalLong parse(String input) {
        if (input == null) {
            return OptionalLong.empty();
        }
        String s = input.trim().toLowerCase(Locale.ROOT).replace(",", "").replace("$", "");
        if (s.isEmpty()) {
            return OptionalLong.empty();
        }
        int power = 0;
        char last = s.charAt(s.length() - 1);
        for (int i = 1; i < SUFFIXES.length; i++) {
            if (last == SUFFIXES[i].charAt(0)) {
                power = i;
                s = s.substring(0, s.length() - 1);
                break;
            }
        }
        try {
            BigDecimal value = new BigDecimal(s).movePointRight(3 * power);
            if (value.signum() <= 0 || value.scale() > 2) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(value.movePointRight(2).longValueExact());
        } catch (NumberFormatException | ArithmeticException e) {
            return OptionalLong.empty();
        }
    }
}
