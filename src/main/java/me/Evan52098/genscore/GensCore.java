package me.Evan52098.genscore;

import me.Evan52098.genscore.announce.AnnouncementsModule;
import me.Evan52098.genscore.chat.ChatModule;
import me.Evan52098.genscore.kits.KitsModule;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.vaults.VaultsModule;
import me.Evan52098.genscore.command.CommandService;
import me.Evan52098.genscore.staff.StaffModule;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.module.ModuleManager;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.teleport.TeleportModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.StorageConfig;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/** Entry point. Owns storage and player data; every feature lives in its own module. */
public final class GensCore extends JavaPlugin {

    private Database database;
    private PlayerDataService playerData;
    private ModuleManager modules;
    private CommandService commands;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        Tasks.init(this);
        getLogger().info("Running on " + (Tasks.isFolia() ? "Folia" : "Paper"));

        try {
            ConfigurationSection storage = getConfig().getConfigurationSection("storage");
            database = new Database(StorageConfig.from(storage != null ? storage : new MemoryConfiguration(), getDataFolder()), getLogger());
            playerData = new PlayerDataService(this, database);
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Could not start storage; GensCore is disabling itself. Check storage: in config.yml.", e);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        Msg.load(getConfig().getConfigurationSection("messages"));
        commands = new CommandService(this);
        commands.register(new me.Evan52098.genscore.command.CoreAdminCommands(this));
        getServer().getPluginManager().registerEvents(Menu.listener(), this);

        modules = new ModuleManager(this);
        // Modules in load order. A module can use the ones registered before it.
        modules.register(new EconomyModule());
        modules.register(new me.Evan52098.genscore.plots.PlotsModule());     // gens phase 1; uses economy and teleport if on
        modules.register(new me.Evan52098.genscore.factory.FactoryModule()); // gens phase 2 + gate; needs plots
        modules.register(new TeleportModule());
        modules.register(new EssentialsModule());
        modules.register(new ChatModule());
        modules.register(new me.Evan52098.genscore.punish.PunishModule());
        modules.register(new StaffModule());
        modules.register(new KitsModule());
        modules.register(new VaultsModule());
        modules.register(new AnnouncementsModule());
        modules.register(new me.Evan52098.genscore.settings.SettingsModule());         // uses essentials
        modules.register(new me.Evan52098.genscore.trade.TradeModule());               // reads settings
        modules.register(new me.Evan52098.genscore.mail.MailModule());
        modules.register(new me.Evan52098.genscore.polls.PollsModule());
        modules.register(new me.Evan52098.genscore.crates.CratesModule());     // Daily Lockbox; pays through economy
        modules.register(new me.Evan52098.genscore.vouchers.VouchersModule());
        modules.register(new me.Evan52098.genscore.daily.DailyModule());       // after crates (/daily shows the lockbox)
        modules.register(new me.Evan52098.genscore.leaderboards.LeaderboardsModule()); // after economy
        modules.register(new me.Evan52098.genscore.wipe.WipeModule());          // before future factions/combat (they join wipes)
        modules.register(new me.Evan52098.genscore.lagwatch.LagWatchModule());
        modules.register(new me.Evan52098.genscore.antihack.AntiHackModule());
        // HXSMP features (groups E-N, see HXSMP-PARITY.md and integration/group-e.md .. group-n.md)
        modules.register(new me.Evan52098.genscore.info.InfoModule());            // /rules /guide /ranks /discord /store
        modules.register(new me.Evan52098.genscore.store.StoreModule());          // after economy + leaderboards (spenders board)
        modules.register(new me.Evan52098.genscore.ranks.RanksModule());          // PlayerSection "tags"
        modules.register(new me.Evan52098.genscore.kitalerts.KitAlertsModule()); // after kits
        modules.register(new me.Evan52098.genscore.shards.ShardsModule());       // before keycrates, powers, pass
        modules.register(new me.Evan52098.genscore.keycrates.KeyCratesModule()); // uses economy + shards
        modules.register(new me.Evan52098.genscore.reputation.ReputationModule());
        modules.register(new me.Evan52098.genscore.wordle.WordleModule());
        modules.register(new me.Evan52098.genscore.chatgames.ChatGamesModule());
        modules.register(new me.Evan52098.genscore.worldrules.WorldRulesModule()); // after teleport; uses rtp
        modules.register(new me.Evan52098.genscore.maintenance.MaintenanceModule());
        modules.register(new me.Evan52098.genscore.sit.SitModule());
        modules.register(new me.Evan52098.genscore.sus.SusModule());               // before staffmode
        modules.register(new me.Evan52098.genscore.staffmode.StaffModeModule());   // needs staff
        modules.register(new me.Evan52098.genscore.seasonpass.SeasonPassModule()); // after shards, keycrates
        modules.register(new me.Evan52098.genscore.cosmetics.CosmeticsModule());   // PlayerSection "cosmetics"
        modules.register(new me.Evan52098.genscore.powers.PowersModule());
        modules.register(new me.Evan52098.genscore.pets.PetsModule());
        modules.register(new me.Evan52098.genscore.gameplay.GameplayModule());
        modules.register(new me.Evan52098.genscore.basestyles.BaseStylesModule());
        modules.register(new me.Evan52098.genscore.hub.HubModule());
        modules.register(new me.Evan52098.genscore.npcs.NpcsModule());
        modules.register(new me.Evan52098.genscore.weekly.WeeklyModule());
        modules.register(new me.Evan52098.genscore.merchant.MerchantModule());
        modules.register(new me.Evan52098.genscore.discord.DiscordModule());
        modules.register(new me.Evan52098.genscore.tutorial.TutorialModule());
        modules.register(new me.Evan52098.genscore.retention.RetentionModule());
        modules.register(new me.Evan52098.genscore.media.MediaModule());         // join addresses for media.gatemc.org
        modules.enableAll();
        connectModules();

        playerData.start(); // after modules, so every module's player data is registered before anyone joins
    }

    /** Hooks between modules that are enabled in either order. */
    private void connectModules() {
        var wipe = modules.get(me.Evan52098.genscore.wipe.WipeModule.class);
        var vaults = modules.get(VaultsModule.class);
        if (wipe != null && vaults != null) {
            wipe.service().addParticipant(vaults::lockForWipe);
        }
        var teleport = modules.get(TeleportModule.class);
        if (teleport != null) {
            // group K: worldrules reads the real /setspawn location instead of polling the database
            me.Evan52098.genscore.worldrules.SpawnPoint.use(teleport::spawnLocation);
            // group N: /homeicon only accepts homes the player has
            var cosmetics = modules.get(me.Evan52098.genscore.cosmetics.CosmeticsModule.class);
            if (cosmetics != null) {
                cosmetics.setHomeNames(teleport::homeNames);
            }
        }
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new me.Evan52098.genscore.ranks.GensCorePlaceholders().register(); // one %genscore_*% expansion for every module
        }
    }

    @Override
    public void onDisable() {
        if (modules != null) {
            modules.disableAll();
        }
        if (playerData != null) {
            playerData.shutdown(15, TimeUnit.SECONDS);
        }
        if (database != null) {
            database.shutdown(10, TimeUnit.SECONDS);
        }
        Tasks.cancelAll();
    }

    public Database database() {
        return database;
    }

    public PlayerDataService playerData() {
        return playerData;
    }

    public ModuleManager modules() {
        return modules;
    }

    public CommandService commands() {
        return commands;
    }
}
