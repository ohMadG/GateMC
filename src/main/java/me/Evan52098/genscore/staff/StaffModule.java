package me.Evan52098.genscore.staff;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.teleport.TeleportModule;
import org.bukkit.Bukkit;

/** Staff tools: vanish, staff and admin chat, freeze, command and sign spy, inventory history, reports. */
public final class StaffModule implements GensModule {

    private Vanish vanish;
    private StaffTools tools;

    @Override
    public String id() {
        return "staff";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Database db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_inv_snapshots ("
                        + db.autoIdColumn("id") + ", uuid TEXT NOT NULL, at BIGINT NOT NULL, reason TEXT NOT NULL, "
                        + "items INTEGER NOT NULL, data TEXT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_inv_snapshots_uuid ON pvp_inv_snapshots (uuid, id);"
                        + "CREATE TABLE IF NOT EXISTS pvp_reports ("
                        + db.autoIdColumn("id") + ", reporter TEXT NOT NULL, reporter_name TEXT NOT NULL, "
                        + "target TEXT NOT NULL, target_name TEXT NOT NULL, reason TEXT NOT NULL, at BIGINT NOT NULL, "
                        + "open INTEGER NOT NULL DEFAULT 1, closed_by TEXT);"
                        + "CREATE INDEX IF NOT EXISTS pvp_reports_open ON pvp_reports (open, id)",
                // v2: snapshots include the ender chest; restores for offline players wait in pvp_inv_pending.
                "ALTER TABLE pvp_inv_snapshots ADD COLUMN ender TEXT;"
                        + "CREATE INDEX IF NOT EXISTS pvp_inv_snapshots_reason ON pvp_inv_snapshots (uuid, reason, id);"
                        + "CREATE TABLE IF NOT EXISTS pvp_inv_pending (uuid TEXT PRIMARY KEY, snapshot_id BIGINT NOT NULL)",
                "ALTER TABLE pvp_reports ADD COLUMN kind TEXT NOT NULL DEFAULT 'player';"
                        + "ALTER TABLE pvp_reports ADD COLUMN evidence TEXT;"
                        + "ALTER TABLE pvp_reports ADD COLUMN outcome TEXT");

        vanish = new Vanish(plugin);
        tools = new StaffTools();
        InvHistory history = new InvHistory(db, plugin.playerData(), plugin.getLogger());
        Reports reports = new Reports(db, plugin.getConfig().getLong("staff.reports.cooldown-seconds", 60));

        plugin.commands().register(vanish, tools, history, reports);
        Bukkit.getPluginManager().registerEvents(vanish, plugin);
        Bukkit.getPluginManager().registerEvents(tools, plugin);
        Bukkit.getPluginManager().registerEvents(history, plugin);

        TeleportModule teleport = plugin.modules().get(TeleportModule.class);
        if (teleport != null) {
            teleport.service().addGuard(tools); // frozen players can't teleport
        }
    }

    public Vanish vanish() {
        return vanish;
    }

    public StaffTools tools() {
        return tools;
    }
}
