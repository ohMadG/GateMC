package me.Evan52098.genscore.reputation;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Player reputation: /vouch, /scam, /vouches. Written fresh from SEBGamble's community commands and config
 * ({@code community.vote-cooldown-seconds}, the vouch broadcast settings).
 *
 * <p>One entry per (voter, target) pair, vouch or scam report, enforced by the table's primary key (checklist 2: two
 * fast commands cannot both count). No self-vouching; the target must have joined before. A cooldown between entries
 * (checklist 10). Nothing here moves money or items. Scam reporters' names are shown only to staff
 * ({@code genscore.reputation.staff}), so a report cannot be used to start a fight; staff can remove an entry made by an
 * alt or in bad faith with /vouches remove.
 */
public final class ReputationModule implements GensModule {

    private static final String VOUCH = "VOUCH";
    private static final String SCAM = "SCAM";

    private GensCore plugin;
    private Database db;
    private Logger logger;
    private final Cooldowns cooldowns = new Cooldowns();
    private volatile long cooldownMillis = 60_000;
    private volatile @Nullable ScheduledTask broadcastTask;

    @Override
    public String id() {
        return "reputation";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_reputation ("
                        + "voter TEXT NOT NULL, target TEXT NOT NULL, voter_name TEXT NOT NULL, target_name TEXT NOT NULL, "
                        + "kind TEXT NOT NULL, created_at BIGINT NOT NULL, PRIMARY KEY (voter, target));"
                        + "CREATE INDEX IF NOT EXISTS pvp_reputation_target ON pvp_reputation (target, kind, created_at)");
        reload();
        plugin.commands().register(this);
    }

    @Override
    public void disable() {
        ScheduledTask t = broadcastTask;
        if (t != null) {
            t.cancel();
        }
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("reputation");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        cooldownMillis = Math.max(0, c.getLong("cooldown-seconds", 60)) * 1000L;
        ScheduledTask old = broadcastTask;
        if (old != null) {
            old.cancel();
            broadcastTask = null;
        }
        if (c.getBoolean("broadcast-enabled", false)) {
            long period = Math.max(1, c.getLong("broadcast-interval-minutes", 45)) * 60L * 20L;
            Component line = Msg.parse(c.getString("broadcast-message",
                    "<gold>[Community]</gold> <yellow>Vouch players or check their credibility with <green>/vouch \\<player></green>. View ratings with <green>/vouches \\<player></green>."));
            broadcastTask = Tasks.global.timer(t -> {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    Tasks.onEntity(p, () -> p.sendMessage(line));
                }
            }, period, period);
        }
    }

    // ------------------------------------------------------------------ commands

    @Command("vouch")
    @CommandPermission("genscore.reputation")
    @Description("Vouch for a player you trust")
    public void vouch(BukkitCommandActor actor, @Named("player") String name) {
        record(actor.requirePlayer(), name, VOUCH);
    }

    @Command("scam")
    @CommandPermission("genscore.reputation")
    @Description("Report a player who scammed you")
    public void scam(BukkitCommandActor actor, @Named("player") String name) {
        record(actor.requirePlayer(), name, SCAM);
    }

    private void record(Player voter, String name, String kind) {
        OfflinePlayer target = find(name);
        if (target == null) {
            voter.sendMessage(Msg.bad("That player has never joined."));
            return;
        }
        if (target.getUniqueId().equals(voter.getUniqueId())) {
            voter.sendMessage(Msg.bad(kind.equals(VOUCH) ? "You can't vouch for yourself." : "You can't report yourself."));
            return;
        }
        UUID voterId = voter.getUniqueId();
        long left = cooldowns.remaining(voterId, "reputation");
        if (left > 0) {
            voter.sendMessage(Msg.bad("Wait " + Cooldowns.format(left) + " before doing that again."));
            return;
        }
        // Checklist 3 and 10: the cooldown starts before the database call, so spamming the command cannot queue writes.
        if (!cooldowns.tryStart(voterId, "reputation", Math.max(1000, cooldownMillis))) {
            return;
        }
        String voterName = voter.getName();
        String targetName = target.getName() != null ? target.getName() : name;
        UUID targetId = target.getUniqueId();
        db.async(c -> {
            // Checklist 2: the (voter, target) primary key lets only one entry per pair exist.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_reputation (voter, target, voter_name, target_name, kind, created_at) VALUES (?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (voter, target) DO NOTHING")) {
                ps.setString(1, voterId.toString());
                ps.setString(2, targetId.toString());
                ps.setString(3, voterName);
                ps.setString(4, targetName);
                ps.setString(5, kind);
                ps.setLong(6, System.currentTimeMillis());
                if (ps.executeUpdate() == 1) {
                    return null;
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT kind FROM pvp_reputation WHERE voter = ? AND target = ?")) {
                ps.setString(1, voterId.toString());
                ps.setString(2, targetId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getString(1) : "?";
                }
            }
        }).whenComplete((existing, error) -> {
            if (error != null) {
                cooldowns.clear(voterId, "reputation");
                logger.log(Level.WARNING, "[reputation] Could not save a " + kind + " from " + voterName, error);
                Tasks.entity.run(voter, () -> voter.sendMessage(Msg.bad("That could not be saved right now. Try again in a moment.")));
                return;
            }
            if (existing != null) {
                cooldowns.clear(voterId, "reputation");
                String what = VOUCH.equals(existing) ? "vouched for" : "reported";
                Tasks.entity.run(voter, () -> voter.sendMessage(Msg.bad("You've already " + what + " " + Msg.white(targetName) + ".")));
                return;
            }
            logger.info("[reputation] " + voterName + " " + (kind.equals(VOUCH) ? "vouched for " : "reported as a scammer: ") + targetName);
            Tasks.entity.run(voter, () -> voter.sendMessage(Msg.good(kind.equals(VOUCH)
                    ? "You vouched for " + Msg.white(targetName) + "."
                    : "You reported " + Msg.white(targetName) + " as a scammer. Staff can see who made reports.")));
            Player online = Bukkit.getPlayer(targetId);
            if (online != null && kind.equals(VOUCH)) {
                Tasks.entity.run(online, () -> online.sendMessage(Msg.good(Msg.white(voterName) + " vouched for you.")));
            }
        });
    }

    @Command("vouches")
    @Description("See a player's vouches and scam reports")
    public void vouches(BukkitCommandActor actor, @Optional @Named("player") @Nullable String name) {
        CommandSender sender = actor.sender();
        OfflinePlayer target;
        if (name == null || name.isBlank()) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Msg.bad("Name a player: /vouches \\<player>"));
                return;
            }
            target = p;
        } else {
            target = find(name);
        }
        if (target == null) {
            sender.sendMessage(Msg.bad("That player has never joined."));
            return;
        }
        boolean staff = sender.hasPermission("genscore.reputation.staff");
        String shown = target.getName() != null ? target.getName() : String.valueOf(name);
        UUID targetId = target.getUniqueId();
        db.async(c -> {
            int vouches = 0;
            int scams = 0;
            List<String> recentVouchers = new ArrayList<>();
            List<String> reporters = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT kind, voter_name FROM pvp_reputation WHERE target = ? ORDER BY created_at DESC")) {
                ps.setString(1, targetId.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        if (VOUCH.equals(rs.getString(1))) {
                            vouches++;
                            if (recentVouchers.size() < 8) {
                                recentVouchers.add(rs.getString(2));
                            }
                        } else {
                            scams++;
                            if (reporters.size() < 8) {
                                reporters.add(rs.getString(2));
                            }
                        }
                    }
                }
            }
            return new Object[]{vouches, scams, recentVouchers, reporters};
        }).whenComplete((r, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "[reputation] Could not load vouches for " + shown, error);
                sender.sendMessage(Msg.bad("Vouches could not be loaded right now."));
                return;
            }
            int vouches = (Integer) r[0];
            int scams = (Integer) r[1];
            @SuppressWarnings("unchecked") List<String> vouchers = (List<String>) r[2];
            @SuppressWarnings("unchecked") List<String> reporters = (List<String>) r[3];
            String rating = vouches + scams == 0 ? "<gray>No ratings yet"
                    : scams == 0 ? "<green>Trusted" : scams >= vouches ? "<red>Be careful" : "<yellow>Mixed";
            sender.sendMessage(Msg.parse("<gold><bold>Reputation</bold></gold> <gray>- <white>" + Msg.escape(shown) + " <dark_gray>(" + rating + "<dark_gray>)"));
            sender.sendMessage(Msg.parse("<gray>  Vouches: <green>" + vouches + "</green>   Scam reports: <red>" + scams));
            if (!vouchers.isEmpty()) {
                sender.sendMessage(Msg.parse("<gray>  Recent vouches: <white>" + Msg.escape(String.join(", ", vouchers))));
            }
            if (staff && !reporters.isEmpty()) {
                sender.sendMessage(Msg.parse("<gray>  Reported by <dark_gray>(staff)</dark_gray>: <white>" + Msg.escape(String.join(", ", reporters))));
            }
        }));
    }

    @Command("vouches remove")
    @CommandPermission("genscore.reputation.admin")
    @Description("Remove one player's vouch or report on another")
    public void remove(BukkitCommandActor actor, @Named("target") String targetName, @Named("voter") String voterName) {
        CommandSender sender = actor.sender();
        OfflinePlayer target = find(targetName);
        OfflinePlayer voter = find(voterName);
        if (target == null || voter == null) {
            sender.sendMessage(Msg.bad("That player has never joined."));
            return;
        }
        String by = sender.getName();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_reputation WHERE voter = ? AND target = ?")) {
                ps.setString(1, voter.getUniqueId().toString());
                ps.setString(2, target.getUniqueId().toString());
                return ps.executeUpdate();
            }
        }).whenComplete((n, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("Could not remove it - check the console."));
                logger.log(Level.WARNING, "[reputation] Could not remove an entry", error);
                return;
            }
            if (n == 0) {
                sender.sendMessage(Msg.bad(Msg.white(voterName) + " has no vouch or report on " + Msg.white(targetName) + "."));
                return;
            }
            logger.info("[reputation] " + by + " removed " + voterName + "'s entry on " + targetName);
            sender.sendMessage(Msg.good("Removed " + Msg.white(voterName) + "'s entry on " + Msg.white(targetName) + "."));
        }));
    }

    /** An online player, or one the server has seen before (never a blocking name lookup). */
    private static @Nullable OfflinePlayer find(String name) {
        if (name == null || name.isBlank() || name.length() > 16) {
            return null;
        }
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online;
        }
        return Bukkit.getOfflinePlayerIfCached(name);
    }
}
