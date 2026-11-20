package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.util.Money;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import net.milkbowl.vault.economy.EconomyResponse.ResponseType;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.UUID;

/**
 * Lets other plugins (shops, crates, Tebex rewards) use GensCore money through Vault or VaultUnlocked.
 * World-specific overloads ignore the world: there is one balance per player. Banks are not supported.
 */
final class VaultEconomy implements Economy {

    private final EconomyService economy;
    private final PlayerDataService players;

    VaultEconomy(EconomyService economy, PlayerDataService players) {
        this.economy = economy;
        this.players = players;
    }

    private UUID byName(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        return players.findUuid(name).join().orElse(null);
    }

    /** Cents for a Vault amount, or -1 when it is not usable: NaN, infinite, negative, or rounding to 0 cents. */
    private static long cents(double amount) {
        if (!Double.isFinite(amount) || amount < 0 || amount > 1e15) {
            return -1;
        }
        long c = Money.toCents(amount);
        return c == 0 && amount > 0 ? -1 : c; // a 0.004 charge must not "succeed" for free
    }

    private EconomyResponse withdraw(UUID uuid, double amount) {
        if (uuid == null) {
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Unknown player");
        }
        if (cents(amount) < 0) {
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Invalid amount");
        }
        if (amount < 0) {
            return new EconomyResponse(0, Money.toDouble(economy.balance(uuid)), ResponseType.FAILURE, "Cannot withdraw a negative amount");
        }
        boolean ok = economy.withdraw(uuid, Money.toCents(amount), "vault");
        double balance = Money.toDouble(economy.balance(uuid));
        return ok ? new EconomyResponse(amount, balance, ResponseType.SUCCESS, null)
                : new EconomyResponse(0, balance, ResponseType.FAILURE, "Insufficient funds");
    }

    private EconomyResponse deposit(UUID uuid, double amount) {
        if (uuid == null) {
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Unknown player");
        }
        if (cents(amount) < 0) {
            return new EconomyResponse(0, 0, ResponseType.FAILURE, "Invalid amount");
        }
        if (amount < 0) {
            return new EconomyResponse(0, Money.toDouble(economy.balance(uuid)), ResponseType.FAILURE, "Cannot deposit a negative amount");
        }
        boolean ok = economy.deposit(uuid, Money.toCents(amount), "vault");
        double balance = Money.toDouble(economy.balance(uuid));
        return ok ? new EconomyResponse(amount, balance, ResponseType.SUCCESS, null)
                : new EconomyResponse(0, balance, ResponseType.FAILURE, "Deposit failed");
    }

    private static EconomyResponse noBanks() {
        return new EconomyResponse(0, 0, ResponseType.NOT_IMPLEMENTED, "GensCore does not support banks");
    }

    @Override public boolean isEnabled() { return true; }
    @Override public String getName() { return "GensCore"; }
    @Override public boolean hasBankSupport() { return false; }
    @Override public int fractionalDigits() { return 2; }
    @Override public String format(double amount) { return Money.format(Money.toCents(amount)); }
    @Override public String currencyNamePlural() { return economy.settings().currencyPlural(); }
    @Override public String currencyNameSingular() { return economy.settings().currencySingular(); }

    @Override public boolean hasAccount(String name) { return byName(name) != null; }
    @Override public boolean hasAccount(OfflinePlayer p) { return true; }
    @Override public boolean hasAccount(String name, String world) { return hasAccount(name); }
    @Override public boolean hasAccount(OfflinePlayer p, String world) { return true; }

    @Override public double getBalance(String name) {
        UUID id = byName(name);
        return id == null ? 0 : Money.toDouble(economy.balance(id));
    }
    @Override public double getBalance(OfflinePlayer p) { return Money.toDouble(economy.balance(p.getUniqueId())); }
    @Override public double getBalance(String name, String world) { return getBalance(name); }
    @Override public double getBalance(OfflinePlayer p, String world) { return getBalance(p); }

    @Override public boolean has(String name, double amount) { return getBalance(name) >= amount; }
    @Override public boolean has(OfflinePlayer p, double amount) { long c = cents(amount); return c >= 0 && economy.has(p.getUniqueId(), c); }
    @Override public boolean has(String name, String world, double amount) { return has(name, amount); }
    @Override public boolean has(OfflinePlayer p, String world, double amount) { return has(p, amount); }

    @Override public EconomyResponse withdrawPlayer(String name, double amount) { return withdraw(byName(name), amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, double amount) { return withdraw(p.getUniqueId(), amount); }
    @Override public EconomyResponse withdrawPlayer(String name, String world, double amount) { return withdrawPlayer(name, amount); }
    @Override public EconomyResponse withdrawPlayer(OfflinePlayer p, String world, double amount) { return withdrawPlayer(p, amount); }

    @Override public EconomyResponse depositPlayer(String name, double amount) { return deposit(byName(name), amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, double amount) { return deposit(p.getUniqueId(), amount); }
    @Override public EconomyResponse depositPlayer(String name, String world, double amount) { return depositPlayer(name, amount); }
    @Override public EconomyResponse depositPlayer(OfflinePlayer p, String world, double amount) { return depositPlayer(p, amount); }

    @Override public EconomyResponse createBank(String name, String player) { return noBanks(); }
    @Override public EconomyResponse createBank(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse deleteBank(String name) { return noBanks(); }
    @Override public EconomyResponse bankBalance(String name) { return noBanks(); }
    @Override public EconomyResponse bankHas(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankWithdraw(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse bankDeposit(String name, double amount) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankOwner(String name, OfflinePlayer player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, String player) { return noBanks(); }
    @Override public EconomyResponse isBankMember(String name, OfflinePlayer player) { return noBanks(); }
    @Override public List<String> getBanks() { return List.of(); }

    @Override public boolean createPlayerAccount(String name) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p) { return true; }
    @Override public boolean createPlayerAccount(String name, String world) { return true; }
    @Override public boolean createPlayerAccount(OfflinePlayer p, String world) { return true; }
}
