package me.Evan52098.genscore.media;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.sql.PreparedStatement;
import java.util.Locale;

public final class MediaModule implements GensModule, Listener {

    private Database db;
    private GensCore plugin;

    @Override
    public String id() {
        return "media";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_join_hosts (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, "
                        + "first_host TEXT, new_player BOOLEAN NOT NULL, first_ip TEXT, first_seen BIGINT NOT NULL, "
                        + "last_host TEXT, last_seen BIGINT NOT NULL)");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        String host = host(player.getVirtualHost());
        boolean fresh = !player.hasPlayedBefore();
        InetSocketAddress address = player.getAddress();
        InetAddress inet = address == null ? null : address.getAddress();
        String ip = inet == null ? null : inet.getHostAddress();
        String uuid = player.getUniqueId().toString();
        String name = player.getName();
        long now = System.currentTimeMillis();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO pvp_join_hosts "
                    + "(uuid, player_name, first_host, new_player, first_ip, first_seen, last_host, last_seen) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (uuid) DO UPDATE SET "
                    + "player_name = excluded.player_name, last_host = excluded.last_host, last_seen = excluded.last_seen")) {
                ps.setString(1, uuid);
                ps.setString(2, name);
                ps.setString(3, host);
                ps.setBoolean(4, fresh);
                ps.setString(5, ip);
                ps.setLong(6, now);
                ps.setString(7, host);
                ps.setLong(8, now);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            plugin.getLogger().warning("Could not record join host for " + name + ": " + e.getMessage());
            return null;
        });
    }

    static String host(InetSocketAddress address) {
        if (address == null) {
            return null;
        }
        String h = address.getHostString();
        if (h == null) {
            return null;
        }
        int zero = h.indexOf('\0');
        if (zero >= 0) {
            h = h.substring(0, zero);
        }
        h = h.trim().toLowerCase(Locale.ROOT);
        while (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        return h.isEmpty() ? null : h;
    }
}
