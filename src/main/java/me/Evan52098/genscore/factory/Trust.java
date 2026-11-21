package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

final class Trust {

    static final int MAX = 10;

    private final GensCore plugin;
    private final Map<UUID, Set<UUID>> byOwner = new ConcurrentHashMap<>();

    Trust(GensCore plugin) throws SQLException {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), "trust", plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_trust (owner TEXT NOT NULL, trusted TEXT NOT NULL, since BIGINT NOT NULL, "
                        + "PRIMARY KEY (owner, trusted))");
        int rows = plugin.database().sync(c -> {
            int n = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT owner, trusted FROM gens_trust");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    byOwner.computeIfAbsent(UUID.fromString(rs.getString(1)), k -> ConcurrentHashMap.newKeySet())
                            .add(UUID.fromString(rs.getString(2)));
                    n++;
                }
            }
            return n;
        });
        plugin.getLogger().info("[trust] " + rows + " trusted players across " + byOwner.size() + " factories.");
    }

    boolean trusts(@Nullable UUID owner, UUID player) {
        if (owner == null) {
            return false;
        }
        Set<UUID> set = byOwner.get(owner);
        return set != null && set.contains(player);
    }

    @Command("trust")
    @CommandPermission("genscore.trust")
    @Description("Let a player into your factory: your gate, alarm and turrets ignore them and they can't raid you")
    public void trust(BukkitCommandActor actor, @Optional @Named("player") @Nullable String name) {
        Player p = actor.requirePlayer();
        if (name == null || name.isBlank() || name.equalsIgnoreCase("list")) {
            list(p);
            return;
        }
        resolve(name).thenAccept(target -> Tasks.entity.run(p, () -> {
            if (target == null) {
                p.sendMessage(Msg.bad(Msg.white(name) + " has never played here."));
                return;
            }
            if (target.equals(p.getUniqueId())) {
                p.sendMessage(Msg.bad("You can't trust yourself."));
                return;
            }
            Set<UUID> set = byOwner.computeIfAbsent(p.getUniqueId(), k -> ConcurrentHashMap.newKeySet());
            if (set.contains(target)) {
                p.sendMessage(Msg.neutral(Msg.white(name) + " is already trusted. Remove them with " + Msg.white("/untrust " + name) + "."));
                return;
            }
            if (set.size() >= MAX) {
                p.sendMessage(Msg.bad("You can trust up to " + Msg.white(String.valueOf(MAX)) + " players. Remove one with " + Msg.white("/untrust") + " first."));
                return;
            }
            set.add(target);
            save(p.getUniqueId(), target, true);
            p.sendMessage(Msg.good(Msg.white(name) + " is now trusted. They can walk through your gate, your turrets ignore them and they can't raid you."));
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
                Tasks.entity.run(t, () -> t.sendMessage(Msg.good(Msg.white(p.getName()) + " trusted you. You can enter their factory, even with the gate closed.")));
            }
        }));
    }

    @Command("untrust")
    @CommandPermission("genscore.trust")
    @Description("Stop trusting a player with your factory")
    public void untrust(BukkitCommandActor actor, @Named("player") String name) {
        Player p = actor.requirePlayer();
        resolve(name).thenAccept(target -> Tasks.entity.run(p, () -> {
            Set<UUID> set = byOwner.get(p.getUniqueId());
            if (target == null || set == null || !set.remove(target)) {
                p.sendMessage(Msg.bad(Msg.white(name) + " is not on your trusted list (" + Msg.white("/trusted") + ")."));
                return;
            }
            save(p.getUniqueId(), target, false);
            p.sendMessage(Msg.good(Msg.white(name) + " is no longer trusted."));
            Player t = Bukkit.getPlayer(target);
            if (t != null) {
                Tasks.entity.run(t, () -> t.sendMessage(Msg.neutral(Msg.white(p.getName()) + " no longer trusts you with their factory.")));
            }
        }));
    }

    @Command("trusted")
    @CommandPermission("genscore.trust")
    @Description("See who you trust with your factory")
    public void trusted(BukkitCommandActor actor) {
        list(actor.requirePlayer());
    }

    private void list(Player p) {
        Set<UUID> set = byOwner.get(p.getUniqueId());
        if (set == null || set.isEmpty()) {
            p.sendMessage(Msg.neutral("You don't trust anyone yet. Use " + Msg.white("/trust <player>") + " to let a friend into your factory."));
            return;
        }
        List<UUID> ids = new ArrayList<>(set);
        plugin.database().async(c -> {
            List<String> names = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT name FROM pvp_players WHERE uuid = ?")) {
                for (UUID id : ids) {
                    ps.setString(1, id.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        names.add(rs.next() ? rs.getString(1) : id.toString().substring(0, 8));
                    }
                }
            }
            return names;
        }).thenAccept(names -> Tasks.entity.run(p, () -> p.sendMessage(Msg.neutral("Trusted (" + Msg.white(names.size() + "/" + MAX) + "): "
                + Msg.white(String.join(", ", names)) + ". Remove with " + Msg.white("/untrust <player>") + "."))));
    }

    private CompletableFuture<@Nullable UUID> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return plugin.playerData().findUuid(name).thenApply(o -> o.orElse(null));
    }

    private void save(UUID owner, UUID target, boolean add) {
        plugin.database().async(c -> {
            try (PreparedStatement ps = c.prepareStatement(add
                    ? "INSERT INTO gens_trust (owner, trusted, since) VALUES (?, ?, ?) ON CONFLICT DO NOTHING"
                    : "DELETE FROM gens_trust WHERE owner = ? AND trusted = ?")) {
                ps.setString(1, owner.toString());
                ps.setString(2, target.toString());
                if (add) {
                    ps.setLong(3, System.currentTimeMillis());
                }
                ps.executeUpdate();
            }
            return null;
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "[trust] could not save", e);
            return null;
        });
    }
}
