package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.util.Money;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;

/** The {@code economy:} section of config.yml. Same keys and defaults as BlockvilleCore where they existed. */
public record EconomySettings(
        long startingCents,
        long payMinCents,
        long payMaxCents,
        long payConfirmCents,
        int topSize,
        int topPerPage,
        long topCacheMillis,
        String currencySingular,
        String currencyPlural
) {

    public static EconomySettings from(ConfigurationSection s) {
        if (s == null) {
            s = new MemoryConfiguration();
        }
        return new EconomySettings(
                Money.toCents(Math.max(0, s.getDouble("starting-balance", 0))),
                Money.toCents(Math.max(0.01, s.getDouble("pay.min-amount", 1.0))),
                Money.toCents(s.getDouble("pay.max-amount", 1_000_000.0)),
                Money.toCents(s.getDouble("pay.confirm-threshold", 10_000.0)),
                Math.max(1, s.getInt("balance-top.cache-size", 100)),
                Math.max(1, s.getInt("balance-top.entries-per-page", 10)),
                Math.max(1, s.getLong("balance-top.cache-seconds", 60)) * 1000L,
                s.getString("currency-singular", "Dollar"),
                s.getString("currency-plural", "Dollars")
        );
    }
}
