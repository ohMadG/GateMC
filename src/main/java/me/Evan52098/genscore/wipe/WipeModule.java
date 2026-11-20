package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.util.DiscordWebhook;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Player and season wipes with backups and restore. See {@link WipeService} for the safety rules and
 * {@link WipeRegistry} for which tables a wipe touches and how other modules join in.
 */
public final class WipeModule implements GensModule {

    private GensCore plugin;
    private WipeRegistry registry;
    private WipeService service;
    private PendingWipes pending;
    private WipeAudit audit;
    private DiscordWebhook webhook;

    @Override
    public String id() {
        return "wipe";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Database db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_wipe_runs ("
                        + "wipe_id TEXT PRIMARY KEY, kind TEXT NOT NULL, target TEXT, target_name TEXT NOT NULL, scope TEXT NOT NULL, actor TEXT NOT NULL, "
                        + "reason TEXT, backup_file TEXT, state TEXT NOT NULL, receipt TEXT, created_at BIGINT NOT NULL, finished_at BIGINT);"
                        + "CREATE INDEX IF NOT EXISTS pvp_wipe_runs_state ON pvp_wipe_runs (state);"
                        + "CREATE INDEX IF NOT EXISTS pvp_wipe_runs_target ON pvp_wipe_runs (target, created_at);"
                        + "CREATE TABLE IF NOT EXISTS pvp_wipe_pending ("
                        + "uuid TEXT PRIMARY KEY, wipe_id TEXT NOT NULL, requested_by TEXT NOT NULL, requested_at BIGINT NOT NULL, "
                        + "wipe_inventory INTEGER NOT NULL DEFAULT 1, wipe_ender INTEGER NOT NULL DEFAULT 1, wipe_xp INTEGER NOT NULL DEFAULT 1, "
                        + "labels TEXT NOT NULL DEFAULT '', full_wipe INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_wipe_pending_wipe ON pvp_wipe_pending (wipe_id);"
                        + "CREATE TABLE IF NOT EXISTS pvp_wipe_cleared ("
                        + "wipe_id TEXT NOT NULL, uuid TEXT NOT NULL, inventory TEXT, ender TEXT, level INTEGER NOT NULL DEFAULT 0, "
                        + "exp DOUBLE PRECISION NOT NULL DEFAULT 0, total_exp INTEGER NOT NULL DEFAULT 0, at BIGINT NOT NULL, PRIMARY KEY (wipe_id, uuid));"
                        + "CREATE TABLE IF NOT EXISTS pvp_wipe_giveback (uuid TEXT PRIMARY KEY, wipe_id TEXT NOT NULL, restore_id TEXT NOT NULL)");

        ConfigurationSection c = section();
        registry = new WipeRegistry();
        registry.loadConfig(c.getMapList("sections"), plugin.getLogger());
        WipeReceipts.bind(c.getConfigurationSection("receipts"));

        File folder = new File(plugin.getDataFolder(), "modules/" + id());
        WipeStore store = new WipeStore(new File(folder, c.getString("backup-folder", "backups")));
        WipeLocks locks = new WipeLocks(plugin.getLogger());
        webhook = new DiscordWebhook(plugin.getLogger(), "Wipe");
        audit = new WipeAudit(plugin.getLogger(), new File(folder, "audit.log"), webhook, c.getString("discord-webhook-url", ""));
        service = new WipeService(db, plugin.playerData(), registry, store, locks, audit, plugin.getLogger(), settings(c));
        pending = new PendingWipes(db, plugin.getLogger(), c.getBoolean("send-to-spawn", true));
        ChatPrompt prompt = new ChatPrompt();

        service.recoverJournal();

        Bukkit.getPluginManager().registerEvents(locks, plugin);
        Bukkit.getPluginManager().registerEvents(pending, plugin);
        Bukkit.getPluginManager().registerEvents(prompt, plugin);
        plugin.commands().register(new WipeCommands(service, new WipeMenu(service, prompt)));
    }

    @Override
    public void reload() {
        ConfigurationSection c = section();
        registry.loadConfig(c.getMapList("sections"), plugin.getLogger());
        WipeReceipts.bind(c.getConfigurationSection("receipts"));
        service.settings(settings(c));
        pending.sendToSpawn(c.getBoolean("send-to-spawn", true));
        audit.webhookUrl(c.getString("discord-webhook-url", ""));
    }

    @Override
    public void disable() {
        if (webhook != null) {
            webhook.stop();
        }
    }

    /** For other modules: add their player tables to wipes ({@link WipeRegistry#register}). */
    public WipeRegistry registry() {
        return registry;
    }

    /** For other modules: flush live state before a wipe ({@link WipeService#addParticipant}). */
    public WipeService service() {
        return service;
    }

    private ConfigurationSection section() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("wipe");
        return c != null ? c : new MemoryConfiguration();
    }

    private static WipeService.Settings settings(ConfigurationSection c) {
        return new WipeService.Settings(
                Math.max(1, c.getLong("settle-ticks", 40)),
                Math.max(20, c.getLong("max-wait-ticks", 400)),
                Math.max(5, c.getLong("run-timeout-seconds", 120)),
                Math.max(1, c.getLong("confirm-window-minutes", 10)),
                keep(c.getConfigurationSection("season.keep")));
    }

    private static @Nullable Map<String, List<String>> keep(@Nullable ConfigurationSection keep) {
        if (keep == null) {
            return null;
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String module : keep.getKeys(false)) {
            out.put(module, keep.getStringList(module));
        }
        return out;
    }
}
