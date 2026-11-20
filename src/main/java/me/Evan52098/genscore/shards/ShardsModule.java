package me.Evan52098.genscore.shards;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Shards, the second currency (HxCrates' shards): earned every minute of playtime (more with a rank), from votes and
 * from retired crate keys; spent on super powers. {@code /shards}, {@code /shards top}, admin give/take/set.
 *
 * <p>For other modules: {@link #service()} (static, null when the module is off) with {@code balance}, {@code withdraw}
 * (atomic), {@code deposit} and {@code persist}.
 */
public final class ShardsModule implements GensModule {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static volatile @Nullable ShardsService instance;

    private GensCore plugin;
    private ShardsService service;

    /** The shards service, or null when the shards module is off or failed to start. Safe from any thread. */
    public static @Nullable ShardsService service() {
        return instance;
    }

    @Override
    public String id() {
        return "shards";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Database db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_shards ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "shards BIGINT NOT NULL DEFAULT 0, "
                        + "earn_milli BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_shards_top ON pvp_shards (shards);"
                        + "CREATE TABLE IF NOT EXISTS pvp_shards_pending ("
                        + db.autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, "
                        + "delta BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_shards_pending_uuid ON pvp_shards_pending (uuid);"
                        + "CREATE TABLE IF NOT EXISTS pvp_shards_ledger ("
                        + db.autoIdColumn("id") + ", "
                        + "at BIGINT NOT NULL, "
                        + "uuid TEXT NOT NULL, "
                        + "delta BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_shards_ledger_uuid ON pvp_shards_ledger (uuid, at)");

        ShardsSection section = new ShardsSection();
        plugin.playerData().register(section);
        service = new ShardsService(db, plugin.playerData(), section,
                ShardsSettings.from(plugin.getConfig().getConfigurationSection("shards")), plugin.getLogger());
        plugin.commands().register(new ShardsCommands(service, plugin.playerData()));

        Tasks.async.timer(t -> service.sweepPending(), 12, 10, TimeUnit.SECONDS);
        Tasks.async.timer(t -> payMinute(), 60, 60, TimeUnit.SECONDS);
        instance = service;
    }

    @Override
    public void disable() {
        instance = null;
    }

    @Override
    public void reload() {
        service.settings(ShardsSettings.from(plugin.getConfig().getConfigurationSection("shards")));
    }

    // ------------------------------------------------------------------ per-minute pay (HxCrates "afk:")

    private void payMinute() {
        ShardsSettings s = service.settings();
        if (!s.earnEnabled()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Permissions and messages belong to the player's thread (Folia).
            Tasks.entity.run(player, () -> payOne(player, s));
        }
    }

    private void payOne(Player player, ShardsSettings s) {
        if (!player.isOnline()) {
            return;
        }
        if (!s.countAfk() && isAfk(player)) {
            return;
        }
        long rate = s.baseMilliPerMinute();
        String best = null;
        for (Map.Entry<String, Long> rank : s.rankMilliPerMinute().entrySet()) {
            if (rank.getValue() > rate && player.hasPermission("genscore.shards.rank." + rank.getKey())) {
                rate = rank.getValue(); // the best matching rank counts, as in HxCrates
                best = rank.getKey();
            }
        }
        long added = service.earn(player.getUniqueId(), rate);
        String bar = s.actionbar();
        if (added > 0 && best != null && bar != null && !bar.isBlank()) {
            long now = service.balance(player.getUniqueId());
            player.sendActionBar(LEGACY.deserialize(bar.replace("{amount}", Long.toString(added)).replace("{shards}", String.format("%,d", now))));
        }
    }

    private boolean isAfk(Player player) {
        EssentialsModule essentials = plugin.modules().get(EssentialsModule.class);
        return essentials != null && essentials.social() != null && essentials.social().isAfk(player.getUniqueId());
    }
}
