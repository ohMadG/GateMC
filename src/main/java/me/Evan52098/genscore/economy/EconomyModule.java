package me.Evan52098.genscore.economy;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;

import java.util.concurrent.TimeUnit;

/** Money: balances, /pay, /baltop, /eco, and the Vault economy other plugins use. */
public final class EconomyModule implements GensModule {

    private EconomyService service;
    private VaultEconomy vault;

    @Override
    public String id() {
        return "economy";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Database db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_balances ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "balance_cents BIGINT NOT NULL DEFAULT 0, "
                        + "pay_enabled INTEGER NOT NULL DEFAULT 1, "
                        + "pay_confirm INTEGER NOT NULL DEFAULT 1);"
                        + "CREATE INDEX IF NOT EXISTS pvp_balances_top ON pvp_balances (balance_cents);"
                        + "CREATE TABLE IF NOT EXISTS pvp_balance_pending ("
                        + db.autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, "
                        + "delta_cents BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_balance_pending_uuid ON pvp_balance_pending (uuid);"
                        + "CREATE TABLE IF NOT EXISTS pvp_economy_ledger ("
                        + db.autoIdColumn("id") + ", "
                        + "at BIGINT NOT NULL, "
                        + "uuid TEXT NOT NULL, "
                        + "other_uuid TEXT, "
                        + "delta_cents BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_economy_ledger_uuid ON pvp_economy_ledger (uuid, at)");

        EconomySettings settings = EconomySettings.from(plugin.getConfig().getConfigurationSection("economy"));
        WalletSection section = new WalletSection(settings.startingCents());
        plugin.playerData().register(section);
        service = new EconomyService(db, plugin.playerData(), section, settings, plugin.getLogger());

        plugin.commands().register(new EconomyCommands(service, plugin.playerData()));

        // Money sent to someone in the moment they were logging in reaches them within 10 seconds.
        Tasks.async.timer(task -> service.sweepPending(), 10, 10, TimeUnit.SECONDS);

        if (Bukkit.getPluginManager().getPlugin("Vault") != null) {
            vault = new VaultEconomy(service, plugin.playerData());
            Bukkit.getServicesManager().register(Economy.class, vault, plugin, ServicePriority.Highest);
            plugin.getLogger().info("Registered GensCore as the Vault economy");
        } else {
            plugin.getLogger().info("Vault is not installed; other plugins cannot use GensCore money");
        }
    }

    @Override
    public void disable() {
        if (vault != null) {
            Bukkit.getServicesManager().unregister(Economy.class, vault);
        }
    }

    public EconomyService service() {
        return service;
    }
}
