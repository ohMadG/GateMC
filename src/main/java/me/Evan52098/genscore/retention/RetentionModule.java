package me.Evan52098.genscore.retention;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;

/** Records which days each player is online and reports retention with /retention. See {@link RetentionService}. */
public final class RetentionModule implements GensModule {

    private GensCore plugin;
    private RetentionService service;

    @Override
    public String id() {
        return "retention";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_activity ("
                        + "uuid TEXT NOT NULL, "
                        + "day TEXT NOT NULL, "
                        + "sessions INTEGER NOT NULL DEFAULT 0, "
                        + "playtime_ms BIGINT NOT NULL DEFAULT 0, "
                        + "PRIMARY KEY (uuid, day));"
                        + "CREATE INDEX IF NOT EXISTS gens_activity_day ON gens_activity (day)");
        service = new RetentionService(plugin, plugin.getConfig().getString("retention.timezone", "America/New_York"));
        service.backfill();
        Bukkit.getPluginManager().registerEvents(service, plugin);
        plugin.commands().register(new RetentionCommands(service));
        Bukkit.getOnlinePlayers().forEach(service::track);
        Tasks.async.timer(t -> service.flush(), 60, 60, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        service.zone(plugin.getConfig().getString("retention.timezone", "America/New_York"));
    }

    @Override
    public void disable() {
        if (service != null) {
            service.flushNow();
        }
    }
}
