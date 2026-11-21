package me.Evan52098.genscore.discord;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.rewardbox.Reward;
import me.Evan52098.genscore.rewardbox.RewardBox;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.luckperms.api.LuckPerms;
import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import net.luckperms.api.node.NodeType;
import net.luckperms.api.node.types.InheritanceNode;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class DiscordModule implements GensModule, Listener {

    static final String ADMIN = "genscore.discord.admin";
    private static final String CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final long DAY = 86_400_000L;

    record Link(UUID uuid, String playerName, String discordId, String discordName, long linkedAt,
                boolean boosting, long boostSince, long bankedMs) {

        long boostedMs(long now) {
            return bankedMs + (boosting && boostSince > 0 ? Math.max(0, now - boostSince) : 0);
        }

        int boostedDays(long now) {
            return (int) (boostedMs(now) / DAY);
        }
    }

    private final SecureRandom random = new SecureRandom();
    private final Cooldowns cooldowns = new Cooldowns();
    private final AtomicBoolean polling = new AtomicBoolean();

    private GensCore plugin;
    private Database db;
    private Logger logger;
    private volatile DiscordConfig config;
    private RewardBox box;
    private NitroMenu menu;
    private volatile @Nullable ScheduledTask pollTask;
    private volatile boolean warnedNoLuckPerms;
    private volatile long lastRankSync;
    private final Set<String> warnedMissingGroups = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "discord";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_discord_codes (code TEXT PRIMARY KEY, uuid TEXT NOT NULL, "
                        + "player_name TEXT NOT NULL, expires_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_discord_codes_uuid ON pvp_discord_codes (uuid);"
                        + "CREATE TABLE IF NOT EXISTS pvp_discord_links (uuid TEXT PRIMARY KEY, player_name TEXT NOT NULL, "
                        + "discord_id TEXT NOT NULL UNIQUE, discord_name TEXT NOT NULL DEFAULT '', linked_at BIGINT NOT NULL, "
                        + "boosting INTEGER NOT NULL DEFAULT 0, boost_since BIGINT NOT NULL DEFAULT 0, "
                        + "boost_banked_ms BIGINT NOT NULL DEFAULT 0, rewarded INTEGER NOT NULL DEFAULT 0, "
                        + "updated_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS pvp_discord_boosts (discord_id TEXT PRIMARY KEY, "
                        + "boost_banked_ms BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS pvp_discord_claims (owner TEXT NOT NULL, reward TEXT NOT NULL, "
                        + "uuid TEXT NOT NULL, claimed_at BIGINT NOT NULL, PRIMARY KEY (owner, reward));"
                        + "CREATE TABLE IF NOT EXISTS pvp_discord_nitro (uuid TEXT PRIMARY KEY, permissions TEXT NOT NULL, "
                        + "granted_at BIGINT NOT NULL)",
                "ALTER TABLE pvp_discord_links ADD COLUMN discord_roles TEXT NOT NULL DEFAULT '';"
                        + "ALTER TABLE pvp_discord_links ADD COLUMN game_ranks TEXT NOT NULL DEFAULT '';"
                        + "CREATE TABLE IF NOT EXISTS pvp_discord_role_grants (uuid TEXT PRIMARY KEY, groups TEXT NOT NULL, "
                        + "granted_at BIGINT NOT NULL)");
        config = new DiscordConfig(plugin.getConfig().getConfigurationSection("discord"), logger);
        box = new RewardBox(plugin, "discord:", "/nitro box", "discord");
        menu = new NitroMenu(this);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        schedule();
    }

    private void schedule() {
        ScheduledTask old = pollTask;
        if (old != null) {
            old.cancel();
        }
        int every = config.pollSeconds;
        pollTask = Tasks.async.timer(t -> poll(), 10, every, TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        config = new DiscordConfig(plugin.getConfig().getConfigurationSection("discord"), logger);
        schedule();
    }

    @Override
    public void disable() {
        ScheduledTask t = pollTask;
        if (t != null) {
            t.cancel();
        }
        HandlerList.unregisterAll(this);
        if (box != null) {
            box.shutdown();
        }
    }

    DiscordConfig config() {
        return config;
    }

    RewardBox box() {
        return box;
    }

    Cooldowns cooldowns() {
        return cooldowns;
    }

    CompletableFuture<@Nullable Link> link(UUID uuid) {
        return db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT l.uuid, l.player_name, l.discord_id, l.discord_name, l.linked_at, "
                    + "l.boosting, l.boost_since, COALESCE(b.boost_banked_ms, 0) FROM pvp_discord_links l "
                    + "LEFT JOIN pvp_discord_boosts b ON b.discord_id = l.discord_id WHERE l.uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? readLink(rs) : null;
                }
            }
        });
    }

    private @Nullable Link linkByName(Connection c, String name) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT l.uuid, l.player_name, l.discord_id, l.discord_name, l.linked_at, "
                + "l.boosting, l.boost_since, COALESCE(b.boost_banked_ms, 0) FROM pvp_discord_links l "
                + "LEFT JOIN pvp_discord_boosts b ON b.discord_id = l.discord_id WHERE LOWER(l.player_name) = LOWER(?)")) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readLink(rs) : null;
            }
        }
    }

    private static Link readLink(ResultSet rs) throws SQLException {
        return new Link(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5),
                rs.getInt(6) != 0, rs.getLong(7), rs.getLong(8));
    }

    CompletableFuture<Set<String>> claimed(String discordId) {
        return db.async(c -> {
            Set<String> out = new HashSet<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT reward FROM pvp_discord_claims WHERE owner = ?")) {
                ps.setString(1, "d:" + discordId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(rs.getString(1));
                    }
                }
            }
            return out;
        });
    }

    @Command("link")
    @Description("Link your Minecraft account to Discord")
    public void linkCommand(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        DiscordConfig c = config;
        if (!c.enabled) {
            player.sendMessage(Msg.bad("Discord linking is turned off right now."));
            return;
        }
        if (!cooldowns.tryStart(player.getUniqueId(), "discord-link", 3000L)) {
            player.sendMessage(Msg.bad("Please wait a moment."));
            return;
        }
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        db.async(con -> {
            Link existing = null;
            try (PreparedStatement ps = con.prepareStatement("SELECT l.uuid, l.player_name, l.discord_id, l.discord_name, l.linked_at, "
                    + "l.boosting, l.boost_since, 0 FROM pvp_discord_links l WHERE l.uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        existing = readLink(rs);
                    }
                }
            }
            if (existing != null) {
                return new Object[]{existing, null};
            }
            String code = newCode();
            long expires = System.currentTimeMillis() + c.codeMillis;
            Database.transaction(con, tx -> {
                try (PreparedStatement del = tx.prepareStatement("DELETE FROM pvp_discord_codes WHERE uuid = ? OR expires_at < ?")) {
                    del.setString(1, uuid.toString());
                    del.setLong(2, System.currentTimeMillis());
                    del.executeUpdate();
                }
                try (PreparedStatement ins = tx.prepareStatement("INSERT INTO pvp_discord_codes (code, uuid, player_name, expires_at) VALUES (?, ?, ?, ?)")) {
                    ins.setString(1, code);
                    ins.setString(2, uuid.toString());
                    ins.setString(3, name);
                    ins.setLong(4, expires);
                    ins.executeUpdate();
                }
                return null;
            });
            return new Object[]{null, code};
        }).whenComplete((result, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "[discord] Could not create a link code for " + name, error);
                player.sendMessage(Msg.bad("Something went wrong. Try again in a moment."));
                return;
            }
            if (result[0] instanceof Link l) {
                player.sendMessage(Msg.neutral("You're already linked to " + Msg.white(l.discordName()) + ". Use "
                        + Msg.white("/unlink") + " to unlink."));
                return;
            }
            String code = (String) result[1];
            int minutes = (int) (c.codeMillis / 60_000L);
            player.sendMessage(Msg.neutral("Your link code is <white><b>" + code + "</b></white>."));
            player.sendMessage(Msg.neutral("In our Discord, run ")
                    .append(Msg.parse("<#5865f2><u>/link " + code + "</u>"))
                    .hoverEvent(HoverEvent.showText(Component.text("Click to copy")))
                    .clickEvent(ClickEvent.copyToClipboard("/link " + code))
                    .append(Msg.parse("<gray> - it expires in " + minutes + " minutes.")));
            if (!c.invite.isBlank()) {
                player.sendMessage(Msg.neutral("Not in the Discord yet? ")
                        .append(Msg.parse("<#5865f2><u>" + Msg.escape(c.invite) + "</u>"))
                        .clickEvent(ClickEvent.openUrl(c.invite)));
            }
            if (!c.linkReward.isEmpty()) {
                player.sendMessage(Msg.neutral("Linking rewards: " + summary(c.linkReward)));
            }
        }));
    }

    @Command("unlink")
    @Description("Unlink your Discord account")
    public void unlinkCommand(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (!cooldowns.tryStart(player.getUniqueId(), "discord-link", 3000L)) {
            player.sendMessage(Msg.bad("Please wait a moment."));
            return;
        }
        long cooldown = config.unlinkCooldownMillis;
        link(player.getUniqueId()).thenCompose(l -> {
            if (l == null) {
                return CompletableFuture.completedFuture("none");
            }
            long left = l.linkedAt() + cooldown - System.currentTimeMillis();
            if (left > 0) {
                return CompletableFuture.completedFuture("wait:" + left);
            }
            return db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_discord_links WHERE uuid = ?")) {
                    ps.setString(1, player.getUniqueId().toString());
                    ps.executeUpdate();
                }
                return "ok:" + l.discordName();
            });
        }).whenComplete((r, e) -> Tasks.entity.run(player, () -> {
            if (e != null) {
                logger.log(Level.WARNING, "[discord] Could not unlink " + player.getName(), e);
                player.sendMessage(Msg.bad("Something went wrong. Try again in a moment."));
            } else if (r.equals("none")) {
                player.sendMessage(Msg.bad("Your account isn't linked. Use " + Msg.white("/link") + " to link it."));
            } else if (r.startsWith("wait:")) {
                player.sendMessage(Msg.bad("You can unlink in " + Msg.white(Cooldowns.format(Long.parseLong(r.substring(5)))) + "."));
            } else {
                player.sendMessage(Msg.good("Unlinked from " + Msg.white(r.substring(3)) + "."));
                Tasks.async.run(this::poll);
            }
        }));
    }

    @Command({"nitro", "boost", "boosting"})
    @Description("Open the Nitro booster reward path")
    public void nitroCommand(BukkitCommandActor actor) {
        menu.open(actor.requirePlayer());
    }

    @Command({"nitro box", "boost box"})
    @Description("Collect Discord rewards that didn't fit in your inventory")
    public void nitroBox(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (!cooldowns.tryStart(player.getUniqueId(), "discord-box", 3000L)) {
            player.sendMessage(Msg.bad("Please wait a moment."));
            return;
        }
        box.waiting(player.getUniqueId()).thenAccept(n -> Tasks.entity.run(player, () -> {
            if (n == 0) {
                player.sendMessage(Msg.neutral("Your Discord reward box is empty."));
            } else {
                player.sendMessage(Msg.neutral("Collecting " + Msg.white(String.valueOf(n)) + " waiting reward(s)..."));
                box.deliver(player.getUniqueId());
            }
        }));
    }

    @Command("linkadmin")
    @CommandPermission(ADMIN)
    @Description("See who a player is linked to on Discord")
    public void adminLookup(BukkitCommandActor actor, @Named("player") @SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String name) {
        CommandSender sender = actor.sender();
        db.async(c -> linkByName(c, name)).whenComplete((l, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                sender.sendMessage(Msg.bad("Lookup failed: " + Msg.escape(String.valueOf(e.getMessage()))));
            } else if (l == null) {
                sender.sendMessage(Msg.neutral(Msg.white(name) + " isn't linked."));
            } else {
                long now = System.currentTimeMillis();
                sender.sendMessage(Msg.neutral(Msg.white(l.playerName()) + " is linked to " + Msg.white(l.discordName())
                        + " (" + l.discordId() + "), boosting: " + Msg.white(l.boosting() ? "yes" : "no")
                        + ", boosted " + Msg.white(l.boostedDays(now) + " days") + "."));
            }
        }));
    }

    @Command("linkadmin unlink")
    @CommandPermission(ADMIN)
    @Description("Force-unlink a player's Discord account")
    public void adminUnlink(BukkitCommandActor actor, @Named("player") @SuggestWith(me.Evan52098.genscore.command.Suggest.Players.class) String name) {
        CommandSender sender = actor.sender();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_discord_links WHERE LOWER(player_name) = LOWER(?)")) {
                ps.setString(1, name);
                return ps.executeUpdate();
            }
        }).whenComplete((n, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                sender.sendMessage(Msg.bad("Unlink failed: " + Msg.escape(String.valueOf(e.getMessage()))));
            } else if (n == 0) {
                sender.sendMessage(Msg.neutral(Msg.white(name) + " isn't linked."));
            } else {
                sender.sendMessage(Msg.good("Unlinked " + Msg.white(name) + "."));
                Tasks.async.run(this::poll);
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        UUID uuid = p.getUniqueId();
        String name = p.getName();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_discord_links SET player_name = ? WHERE uuid = ? AND player_name <> ?")) {
                ps.setString(1, name);
                ps.setString(2, uuid.toString());
                ps.setString(3, name);
                ps.executeUpdate();
            }
        });
        box.deliverAfterJoin(p);
    }

    void claimTier(Player player, DiscordConfig.Tier tier) {
        UUID uuid = player.getUniqueId();
        String name = player.getName();
        long now = System.currentTimeMillis();
        link(uuid).thenCompose(l -> {
            if (l == null) {
                return CompletableFuture.completedFuture("Link your Discord first with " + Msg.white("/link") + ".");
            }
            if (!l.boosting()) {
                return CompletableFuture.completedFuture("You need to be boosting the Discord to claim this.");
            }
            if (l.boostedDays(now) < tier.days()) {
                return CompletableFuture.completedFuture("You need " + Msg.white(tier.days() + " days") + " of boosting for this.");
            }
            return db.async(c -> Database.transaction(c, tx -> {
                if (!insertClaim(tx, "d:" + l.discordId(), "nitro:" + tier.id(), uuid, now)) {
                    return "You've already claimed this reward.";
                }
                box.grant(tx, uuid, name, "discord:nitro:" + tier.id(), tier.rewards());
                return null;
            }));
        }).whenComplete((err, e) -> Tasks.entity.run(player, () -> {
            if (e != null) {
                logger.log(Level.WARNING, "[discord] Could not claim nitro tier " + tier.id() + " for " + name, e);
                player.sendMessage(Msg.bad("Something went wrong. Try again in a moment."));
                return;
            }
            if (err != null) {
                player.sendMessage(Msg.bad(err));
                return;
            }
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
            player.sendMessage(Msg.good("Claimed " + RewardBox.legacyToMini(tier.name()) + "!"));
            box.deliver(uuid);
            menu.open(player);
        }));
    }

    private static boolean insertClaim(Connection tx, String owner, String reward, UUID uuid, long now) throws SQLException {
        try (PreparedStatement ps = tx.prepareStatement("INSERT INTO pvp_discord_claims (owner, reward, uuid, claimed_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, owner);
            ps.setString(2, reward);
            ps.setString(3, uuid.toString());
            ps.setLong(4, now);
            return ps.executeUpdate() > 0;
        }
    }

    private String newCode() {
        char[] out = new char[6];
        for (int i = 0; i < out.length; i++) {
            out[i] = CODE_CHARS.charAt(random.nextInt(CODE_CHARS.length()));
        }
        return new String(out);
    }

    static String summary(List<Reward> rewards) {
        List<String> parts = new ArrayList<>();
        for (Reward r : rewards) {
            parts.add(RewardBox.legacyToMini(r.display()));
        }
        return parts.isEmpty() ? "nothing" : String.join("<gray>, ", parts);
    }

    private void poll() {
        if (!config.enabled || !polling.compareAndSet(false, true)) {
            return;
        }
        try {
            db.sync(c -> {
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_discord_codes WHERE expires_at < ?")) {
                    ps.setLong(1, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
            rewardNewLinks();
            syncNitro();
            grantMonthly();
            syncRoles();
        } catch (Exception e) {
            logger.log(Level.WARNING, "[discord] Sync failed", e);
        } finally {
            polling.set(false);
        }
    }

    private void rewardNewLinks() throws SQLException {
        DiscordConfig c = config;
        List<Link> fresh = db.sync(con -> {
            List<Link> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT uuid, player_name, discord_id, discord_name, linked_at, boosting, "
                    + "boost_since, 0 FROM pvp_discord_links WHERE rewarded = 0")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readLink(rs));
                    }
                }
            }
            return out;
        });
        for (Link l : fresh) {
            long now = System.currentTimeMillis();
            boolean paid = db.sync(con -> Database.transaction(con, tx -> {
                try (PreparedStatement ps = tx.prepareStatement("UPDATE pvp_discord_links SET rewarded = 1 WHERE uuid = ? AND rewarded = 0")) {
                    ps.setString(1, l.uuid().toString());
                    if (ps.executeUpdate() == 0) {
                        return false;
                    }
                }
                boolean newAccount = insertClaim(tx, "u:" + l.uuid(), "link", l.uuid(), now);
                boolean newDiscord = insertClaim(tx, "d:" + l.discordId(), "link", l.uuid(), now);
                if (newAccount && newDiscord && !c.linkReward.isEmpty()) {
                    box.grant(tx, l.uuid(), l.playerName(), "discord:link", c.linkReward);
                    return true;
                }
                return false;
            }));
            logger.info("[discord] " + l.playerName() + " linked to Discord " + l.discordName() + " (" + l.discordId() + ")"
                    + (paid ? ", link reward granted" : ""));
            Player p = Bukkit.getPlayer(l.uuid());
            if (p != null) {
                Tasks.entity.run(p, () -> {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
                    p.sendMessage(Msg.good("Linked to Discord as " + Msg.white(l.discordName()) + "!"));
                    if (paid) {
                        box.deliver(p.getUniqueId());
                    } else if (!c.linkReward.isEmpty()) {
                        p.sendMessage(Msg.neutral("The link reward was already claimed by this Minecraft or Discord account."));
                    }
                });
            }
        }
    }

    private void grantMonthly() throws SQLException {
        DiscordConfig c = config;
        if (c.monthlyRewards.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        long month = c.monthlyDays * DAY;
        List<Link> boosters = db.sync(con -> {
            List<Link> out = new ArrayList<>();
            try (PreparedStatement ps = con.prepareStatement("SELECT l.uuid, l.player_name, l.discord_id, l.discord_name, l.linked_at, "
                    + "l.boosting, l.boost_since, COALESCE(b.boost_banked_ms, 0) FROM pvp_discord_links l "
                    + "LEFT JOIN pvp_discord_boosts b ON b.discord_id = l.discord_id WHERE l.boosting = 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        out.add(readLink(rs));
                    }
                }
            }
            return out;
        });
        for (Link l : boosters) {
            int months = (int) (l.boostedMs(now) / month);
            if (months <= 0) {
                continue;
            }
            int fresh = db.sync(con -> Database.transaction(con, tx -> {
                int n = 0;
                for (int m = 1; m <= months; m++) {
                    if (insertClaim(tx, "d:" + l.discordId(), "nitro:monthly:" + m, l.uuid(), now)) {
                        box.grant(tx, l.uuid(), l.playerName(), "discord:nitro:monthly:" + m, c.monthlyRewards);
                        n++;
                    }
                }
                return n;
            }));
            if (fresh <= 0) {
                continue;
            }
            logger.info("[discord] " + l.playerName() + " got " + fresh + " monthly booster reward(s) (" + months + " months boosted).");
            Player p = Bukkit.getPlayer(l.uuid());
            if (p != null) {
                Tasks.entity.run(p, () -> {
                    p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.4f);
                    p.sendMessage(Msg.good("Thanks for boosting! Your monthly booster reward: " + summary(c.monthlyRewards)));
                    box.deliver(p.getUniqueId());
                });
            }
        }
    }

    private void syncNitro() throws SQLException {
        DiscordConfig c = config;
        String wanted = String.join(",", c.nitroPermissions);
        Map<UUID, Link> boosters = new HashMap<>();
        Map<UUID, String> granted = new HashMap<>();
        db.sync(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT uuid, player_name, discord_id, discord_name, linked_at, boosting, "
                    + "boost_since, 0 FROM pvp_discord_links WHERE boosting = 1")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        Link l = readLink(rs);
                        boosters.put(l.uuid(), l);
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT uuid, permissions FROM pvp_discord_nitro")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        granted.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                    }
                }
            }
            return null;
        });
        boolean changes = false;
        for (UUID u : boosters.keySet()) {
            if (!wanted.equals(granted.get(u))) {
                changes = true;
                break;
            }
        }
        for (UUID u : granted.keySet()) {
            if (!boosters.containsKey(u)) {
                changes = true;
                break;
            }
        }
        if (!changes) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            if (!warnedNoLuckPerms) {
                warnedNoLuckPerms = true;
                logger.warning("[discord] LuckPerms is not running, so Nitro booster permissions can't be given.");
            }
            return;
        }
        LuckPerms lp = LuckPermsProvider.get();
        for (Map.Entry<UUID, Link> e : boosters.entrySet()) {
            UUID u = e.getKey();
            String had = granted.get(u);
            if (wanted.equals(had)) {
                continue;
            }
            Set<String> old = had == null || had.isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(had.split(",")));
            setPermissions(lp, u, c.nitroPermissions, old);
            db.sync(con -> {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM pvp_discord_nitro WHERE uuid = ?")) {
                    ps.setString(1, u.toString());
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO pvp_discord_nitro (uuid, permissions, granted_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, u.toString());
                    ps.setString(2, wanted);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return null;
            });
            if (had == null) {
                Link l = e.getValue();
                logger.info("[discord] " + l.playerName() + " is boosting; Nitro perks given.");
                announceBoost(l);
            }
        }
        for (Map.Entry<UUID, String> e : granted.entrySet()) {
            UUID u = e.getKey();
            if (boosters.containsKey(u)) {
                continue;
            }
            Set<String> old = e.getValue().isEmpty() ? Set.of() : new HashSet<>(Arrays.asList(e.getValue().split(",")));
            setPermissions(lp, u, List.of(), old);
            db.sync(con -> {
                try (PreparedStatement ps = con.prepareStatement("DELETE FROM pvp_discord_nitro WHERE uuid = ?")) {
                    ps.setString(1, u.toString());
                    ps.executeUpdate();
                }
                return null;
            });
            OfflinePlayer op = Bukkit.getOfflinePlayer(u);
            logger.info("[discord] " + (op.getName() != null ? op.getName() : u) + " stopped boosting or unlinked; Nitro perks removed.");
            Player p = op.getPlayer();
            if (p != null) {
                Tasks.entity.run(p, () -> p.sendMessage(Msg.neutral("Your Nitro booster perks have ended. Thanks for boosting!")));
            }
        }
    }

    private void syncRoles() throws SQLException {
        DiscordConfig c = config;
        if (c.roleGroups.isEmpty() && c.rankGroups.isEmpty()) {
            return;
        }
        if (!Bukkit.getPluginManager().isPluginEnabled("LuckPerms")) {
            return;
        }
        LuckPerms lp = LuckPermsProvider.get();
        Map<UUID, String[]> links = new HashMap<>();
        Map<UUID, String> granted = new HashMap<>();
        db.sync(con -> {
            try (PreparedStatement ps = con.prepareStatement("SELECT uuid, player_name, discord_roles, game_ranks FROM pvp_discord_links")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        links.put(UUID.fromString(rs.getString(1)), new String[]{rs.getString(2), rs.getString(3), rs.getString(4)});
                    }
                }
            }
            try (PreparedStatement ps = con.prepareStatement("SELECT uuid, groups FROM pvp_discord_role_grants")) {
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        granted.put(UUID.fromString(rs.getString(1)), rs.getString(2));
                    }
                }
            }
            return null;
        });
        for (Map.Entry<UUID, String[]> e : links.entrySet()) {
            UUID u = e.getKey();
            java.util.TreeSet<String> wanted = new java.util.TreeSet<>();
            for (String roleId : splitList(e.getValue()[1])) {
                String group = c.roleGroups.get(roleId);
                if (group == null) {
                    continue;
                }
                if (lp.getGroupManager().getGroup(group) == null) {
                    if (warnedMissingGroups.add(group)) {
                        logger.warning("[discord] Role sync wants LuckPerms group '" + group + "', which doesn't exist. Create it with /lp creategroup " + group);
                    }
                    continue;
                }
                wanted.add(group);
            }
            String want = String.join(",", wanted);
            String had = granted.getOrDefault(u, "");
            if (want.equals(had)) {
                continue;
            }
            Set<String> old = new HashSet<>(splitList(had));
            setGroups(lp, u, wanted, old);
            saveGrant(u, want);
            logger.info("[discord] Role sync for " + e.getValue()[0] + ": in-game groups " + (want.isEmpty() ? "none" : want) + " (was " + (had.isEmpty() ? "none" : had) + ")");
        }
        for (Map.Entry<UUID, String> e : granted.entrySet()) {
            if (links.containsKey(e.getKey()) || e.getValue().isEmpty()) {
                continue;
            }
            setGroups(lp, e.getKey(), Set.of(), new HashSet<>(splitList(e.getValue())));
            saveGrant(e.getKey(), "");
            logger.info("[discord] Role sync removed groups " + e.getValue() + " from unlinked " + e.getKey());
        }
        long now = System.currentTimeMillis();
        if (c.rankGroups.isEmpty() || now - lastRankSync < c.rankSyncSeconds * 1000L) {
            return;
        }
        lastRankSync = now;
        for (Map.Entry<UUID, String[]> e : links.entrySet()) {
            Set<String> held = new HashSet<>();
            lp.getUserManager().loadUser(e.getKey()).join().getNodes(NodeType.INHERITANCE)
                    .forEach(n -> held.add(n.getGroupName().toLowerCase(java.util.Locale.ROOT)));
            String ranks = String.join(",", c.rankGroups.stream().filter(held::contains).toList());
            if (ranks.equals(e.getValue()[2])) {
                continue;
            }
            db.sync(con -> {
                try (PreparedStatement ps = con.prepareStatement("UPDATE pvp_discord_links SET game_ranks = ? WHERE uuid = ?")) {
                    ps.setString(1, ranks);
                    ps.setString(2, e.getKey().toString());
                    ps.executeUpdate();
                }
                return null;
            });
        }
    }

    private static List<String> splitList(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    private void saveGrant(UUID uuid, String groups) throws SQLException {
        db.sync(con -> {
            try (PreparedStatement ps = con.prepareStatement("DELETE FROM pvp_discord_role_grants WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            if (!groups.isEmpty()) {
                try (PreparedStatement ps = con.prepareStatement("INSERT INTO pvp_discord_role_grants (uuid, groups, granted_at) VALUES (?, ?, ?)")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, groups);
                    ps.setLong(3, System.currentTimeMillis());
                    ps.executeUpdate();
                }
            }
            return null;
        });
    }

    private void setGroups(LuckPerms lp, UUID uuid, Set<String> add, Set<String> old) {
        lp.getUserManager().loadUser(uuid).thenCompose(user -> {
            for (String g : old) {
                if (!add.contains(g)) {
                    user.data().remove(InheritanceNode.builder(g).build());
                }
            }
            for (String g : add) {
                user.data().add(InheritanceNode.builder(g).build());
            }
            return lp.getUserManager().saveUser(user);
        }).join();
    }

    private void setPermissions(LuckPerms lp, UUID uuid, List<String> add, Set<String> old) {
        lp.getUserManager().loadUser(uuid).thenCompose(user -> {
            for (String p : old) {
                if (!add.contains(p)) {
                    user.data().remove(Node.builder(p).build());
                }
            }
            for (String p : add) {
                user.data().add(Node.builder(p).build());
            }
            return lp.getUserManager().saveUser(user);
        }).join();
    }

    private void announceBoost(Link l) {
        Player p = Bukkit.getPlayer(l.uuid());
        if (p != null) {
            Tasks.entity.run(p, () -> {
                p.playSound(p.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
                p.sendMessage(Msg.good("Thanks for boosting the Discord! Your Nitro perks are active - open " + Msg.white("/nitro") + "."));
            });
        }
        if (config.nitroBroadcast) {
            Component msg = Msg.parse("<#f47fff>✦ <white>" + Msg.escape(l.playerName()) + "</white> <gray>just boosted the Discord! "
                    + "<#f47fff>Thank you! <dark_gray>(/nitro)");
            Tasks.global.run(() -> Bukkit.broadcast(msg));
        }
    }
}
