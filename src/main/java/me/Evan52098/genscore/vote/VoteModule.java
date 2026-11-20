package me.Evan52098.genscore.vote;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Voting: the built-in Votifier receiver, vote rewards (paid now or at the voter's next login), streak milestones, vote
 * parties, /vote and /voteadmin. Ported from BlockvilleCore's VoteService, VoteConfig, VoteGui, VoteListener,
 * VotifierServer, VoteCommand, VoteData and VoteRecordData.
 *
 * <p>Single server: BlockvilleCore stored each vote for the whole network and relayed it over Redis so every server
 * could pay it; here the vote is stored and paid on this server, and the Redis relay is left out.
 *
 * <p>Votes are rows in {@code pvp_vote_records}. A reward is paid only after this server wins the row with
 * {@code UPDATE ... SET claimed = 1 WHERE id = ? AND claimed = 0} (checklist 2), so a vote can never be paid twice even
 * if a join, a sweep and an incoming vote try to pay it at the same moment. Votes for players who are offline wait in
 * the table until they join (up to {@code listener.expire-days}).
 */
public final class VoteModule implements GensModule, Listener {

    static final String PARTY_SITE = "__party__";
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private record VoteRow(long id, String siteId, String serviceName, long votedAt) {
    }

    private final VoteStats.Section section = new VoteStats.Section();
    private final Set<UUID> claiming = ConcurrentHashMap.newKeySet();
    private final Set<String> unknownServicesLogged = ConcurrentHashMap.newKeySet();
    private final Object incomingLock = new Object();

    private GensCore plugin;
    private Database db;
    private PlayerDataService players;
    private Logger logger;
    private File folder;
    private volatile VoteConfig config;
    private volatile @Nullable VotifierServer server;
    private volatile int partyProgress = -1;
    private VoteMenu menu;

    @Override
    public String id() {
        return "vote";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.players = plugin.playerData();
        this.logger = plugin.getLogger();
        this.folder = new File(plugin.getDataFolder(), "modules/vote");
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_vote_records ("
                        + db.autoIdColumn("id") + ", username TEXT NOT NULL, display_name TEXT NOT NULL, site_id TEXT NOT NULL, "
                        + "service_name TEXT NOT NULL, voted_at BIGINT NOT NULL, claimed INTEGER NOT NULL DEFAULT 0, "
                        + "claimed_at BIGINT NOT NULL DEFAULT 0, claimed_by TEXT);"
                        + "CREATE INDEX IF NOT EXISTS pvp_vote_records_user ON pvp_vote_records (username, claimed);"
                        + "CREATE INDEX IF NOT EXISTS pvp_vote_records_site ON pvp_vote_records (site_id, voted_at);"
                        + "CREATE INDEX IF NOT EXISTS pvp_vote_records_time ON pvp_vote_records (voted_at);"
                        + "CREATE TABLE IF NOT EXISTS pvp_vote_stats ("
                        + "uuid TEXT PRIMARY KEY, total_votes INTEGER NOT NULL DEFAULT 0, month_key TEXT NOT NULL DEFAULT '', "
                        + "month_votes INTEGER NOT NULL DEFAULT 0, last_vote_day TEXT NOT NULL DEFAULT '', streak INTEGER NOT NULL DEFAULT 0, "
                        + "best_streak INTEGER NOT NULL DEFAULT 0, site_times TEXT NOT NULL DEFAULT '');"
                        + "CREATE INDEX IF NOT EXISTS pvp_vote_stats_total ON pvp_vote_stats (total_votes)",
                // v2: the account a vote belongs to, resolved when the vote arrives (NULL = no account had that name yet).
                "ALTER TABLE pvp_vote_records ADD COLUMN uuid TEXT;"
                        + "CREATE INDEX IF NOT EXISTS pvp_vote_records_uuid ON pvp_vote_records (uuid, claimed)");
        this.config = new VoteConfig(plugin.getConfig().getConfigurationSection("vote"), folder, logger);
        players.register(section);
        this.menu = new VoteMenu(this);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.run(this::startListener);
        // BlockvilleCore's sweep: every minute, pay anyone online with waiting votes and recount the party.
        Tasks.async.timer(t -> sweep(), 30, 60, TimeUnit.SECONDS);
    }

    @Override
    public void disable() {
        VotifierServer s = server;
        if (s != null) {
            s.stop();
            server = null;
        }
    }

    @Override
    public void reload() {
        config = new VoteConfig(plugin.getConfig().getConfigurationSection("vote"), folder, logger);
        Tasks.async.run(() -> {
            disable();
            startListener();
        });
    }

    VoteConfig config() {
        return config;
    }

    int partyProgress() {
        return partyProgress;
    }

    boolean listening() {
        return server != null;
    }

    /** Runs on an async thread: binding the socket and creating keys must never block a game thread. */
    private synchronized void startListener() {
        VoteConfig c = config;
        if (!c.enabled || !c.listenerEnabled || server != null) {
            return;
        }
        VotifierServer created = new VotifierServer(logger, c.votifierFolder, c.host, c.port, c.maxPerIpPerMinute, this::onIncoming);
        try {
            created.start();
            server = created;
        } catch (Exception e) {
            logger.log(Level.SEVERE, "[votes] Could not start the Votifier listener on " + c.host + ":" + c.port
                    + " - votes will not be received until this is fixed", e);
        }
    }

    // ------------------------------------------------------------------ incoming votes (Votifier worker threads)

    private void onIncoming(VotifierServer.IncomingVote vote) {
        if (!config.enabled) {
            return;
        }
        synchronized (incomingLock) {
            try {
                record(vote.username(), vote.serviceName(), "v" + vote.protocol() + " from " + vote.fromIp());
            } catch (Exception e) {
                logger.log(Level.WARNING, "[votes] Could not save a vote for " + vote.username() + " from " + vote.serviceName(), e);
            }
        }
    }

    /** Stores a vote and announces it. Runs on an async thread (Votifier worker or /voteadmin test). */
    private void record(String rawName, String serviceName, String via) throws SQLException {
        VoteConfig c = config;
        String display = rawName.trim();
        String username = display.toLowerCase(Locale.ROOT);
        VoteConfig.Site site = c.siteForService(serviceName);
        String siteId = site != null ? site.id() : serviceName;
        if (site == null && unknownServicesLogged.add(serviceName)) {
            logger.info("[votes] Vote from unknown service '" + serviceName + "' - add it to vote.sites in config.yml (service: \""
                    + serviceName + "\") to show it in /vote.");
        }
        // Resolve the account now (before taking a connection: SQLite has a single one), so the vote is claimed by UUID
        // and a Bedrock player whose gamertag equals another player's Java name cannot take it (checklist 2).
        UUID owner = resolveVoter(display);
        long now = System.currentTimeMillis();
        boolean[] party = {false};
        int[] progress = {-1};
        boolean stored = db.sync(con -> Database.transaction(con, tx -> {
            // Replays and site retries: the same name on the same site inside the window counts once.
            if (c.duplicateWindowMinutes > 0) {
                try (PreparedStatement ps = tx.prepareStatement(
                        "SELECT 1 FROM pvp_vote_records WHERE username = ? AND site_id = ? AND voted_at > ? LIMIT 1")) {
                    ps.setString(1, username);
                    ps.setString(2, siteId);
                    ps.setLong(3, now - c.duplicateWindowMinutes * 60_000L);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return false;
                        }
                    }
                }
            }
            insertRecord(tx, username, display, owner, siteId, serviceName, now);
            if (c.partyEnabled) {
                int count = partyCount(tx);
                if (count >= c.partyGoal) {
                    insertRecord(tx, "", "", null, PARTY_SITE, PARTY_SITE, now + 1);
                    count = 0;
                    party[0] = true;
                }
                progress[0] = count;
            }
            return true;
        }));
        if (!stored) {
            logger.info("[votes] Ignored a repeat vote for " + display + " from " + serviceName + " (" + via + ").");
            return;
        }
        if (progress[0] >= 0) {
            partyProgress = progress[0];
        }
        logger.info("[votes] Vote for " + display + (owner != null ? " (" + owner + ")" : " (no account with that name yet)")
                + " from " + serviceName + " (" + via + ").");
        String announce = c.announce;
        if (announce != null && !announce.isBlank()) {
            Component line = Msg.neutral(announce.replace("{player}", Msg.escape(display)));
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(p, () -> p.sendMessage(line));
            }
        }
        Player voter = owner != null ? Bukkit.getPlayer(owner) : exactOnline(username);
        if (voter != null) {
            claimPending(voter);
        }
        if (party[0]) {
            holdParty();
        }
    }

    private static void insertRecord(Connection c, String username, String display, @Nullable UUID owner, String siteId,
                                     String service, long at) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_vote_records (username, display_name, uuid, site_id, service_name, voted_at) VALUES (?, ?, ?, ?, ?, ?)")) {
            ps.setString(1, username);
            ps.setString(2, display);
            ps.setString(3, owner == null ? null : owner.toString());
            ps.setString(4, siteId);
            ps.setString(5, service);
            ps.setLong(6, at);
            ps.executeUpdate();
        }
    }

    /**
     * The account a voted name belongs to. An exact name (online, then any player who has joined) always wins; only when
     * no account has that exact name is the Bedrock form ({@code "." + name}, Floodgate's prefix) tried. Null when no
     * account matches: the row is then stored by name only and can be claimed only by a player with exactly that name.
     * Runs on an async thread that holds no database connection (it waits on the player-data lookup).
     */
    private @Nullable UUID resolveVoter(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        java.util.Optional<UUID> known = players.findUuid(name).join();
        if (known.isPresent()) {
            return known.get();
        }
        if (name.startsWith(".")) {
            return null; // already the Bedrock form
        }
        String bedrock = "." + name;
        Player bedrockOnline = Bukkit.getPlayerExact(bedrock);
        if (bedrockOnline != null) {
            return bedrockOnline.getUniqueId();
        }
        return players.findUuid(bedrock).join().orElse(null);
    }

    private static @Nullable Player exactOnline(String username) {
        Player player = Bukkit.getPlayerExact(username);
        return player != null && player.getName().toLowerCase(Locale.ROOT).equals(username) ? player : null;
    }

    private static int partyCount(Connection c) throws SQLException {
        long since = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT MAX(voted_at) FROM pvp_vote_records WHERE site_id = ?")) {
            ps.setString(1, PARTY_SITE);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    since = rs.getLong(1);
                }
            }
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_vote_records WHERE voted_at > ? AND site_id <> ?")) {
            ps.setLong(1, since);
            ps.setString(2, PARTY_SITE);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private void sweep() {
        if (!config.enabled) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            claimPending(player);
        }
        if (config.partyEnabled) {
            db.async(VoteModule::partyCount).thenAccept(n -> partyProgress = n).exceptionally(e -> null);
        }
    }

    // ------------------------------------------------------------------ paying

    /**
     * Pays every waiting vote of an online player. One claim in flight per player (checklist 3); each vote row is won with
     * a single-winner UPDATE (checklist 2); rewards are given on the player's thread only for rows this call won, and
     * rows won for a player who logged off meanwhile are put back.
     */
    public void claimPending(Player player) {
        VoteConfig c = config;
        UUID uuid = player.getUniqueId();
        if (!c.enabled || players.get(uuid) == null) {
            return;
        }
        if (!claiming.add(uuid)) {
            return;
        }
        long oldest = System.currentTimeMillis() - c.expireDays * 86_400_000L;
        // Votes resolved to this account, plus name-only rows (stored before the account existed, or before the uuid
        // column) for exactly this name. No dot-stripped matching here: that is decided once, in resolveVoter.
        String exactName = player.getName().toLowerCase(Locale.ROOT);
        db.async(con -> {
            List<VoteRow> won = new ArrayList<>();
            {
                List<VoteRow> rows = new ArrayList<>();
                try (PreparedStatement ps = con.prepareStatement(
                        "SELECT id, site_id, service_name, voted_at FROM pvp_vote_records "
                                + "WHERE (uuid = ? OR (uuid IS NULL AND username = ?)) AND claimed = 0 AND voted_at > ? AND site_id <> ?")) {
                    ps.setString(1, uuid.toString());
                    ps.setString(2, exactName);
                    ps.setLong(3, oldest);
                    ps.setString(4, PARTY_SITE);
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            rows.add(new VoteRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                        }
                    }
                }
                for (VoteRow row : rows) {
                    try (PreparedStatement ps = con.prepareStatement(
                            "UPDATE pvp_vote_records SET claimed = 1, claimed_at = ?, claimed_by = ? WHERE id = ? AND claimed = 0")) {
                        ps.setLong(1, System.currentTimeMillis());
                        ps.setString(2, uuid.toString());
                        ps.setLong(3, row.id());
                        if (ps.executeUpdate() == 1) {
                            won.add(row);
                        }
                    }
                }
            }
            won.sort(Comparator.comparingLong(VoteRow::votedAt));
            return won;
        }).whenComplete((won, error) -> {
            if (error != null) {
                claiming.remove(uuid);
                logger.log(Level.WARNING, "[votes] Could not look up votes for " + player.getName(), error);
                return;
            }
            if (won.isEmpty()) {
                claiming.remove(uuid);
                return;
            }
            ScheduledTask t = Tasks.entity.later(player, () -> {
                try {
                    for (VoteRow row : won) {
                        if (player.isOnline() && players.get(uuid) != null) {
                            pay(player, row);
                        } else {
                            release(row);
                        }
                    }
                    players.saveSoon(uuid); // checklist 7: stats and wallet saved soon after the claim
                } finally {
                    claiming.remove(uuid);
                }
            }, () -> {
                won.forEach(this::release);
                claiming.remove(uuid);
            }, 1);
            if (t == null) {
                won.forEach(this::release);
                claiming.remove(uuid);
            }
        });
    }

    private void release(VoteRow row) {
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE pvp_vote_records SET claimed = 0, claimed_at = 0, claimed_by = NULL WHERE id = ? AND claimed = 1")) {
                ps.setLong(1, row.id());
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            logger.log(Level.WARNING, "[votes] Could not release vote " + row.id(), e);
            return null;
        });
    }

    /** On the player's thread. */
    private void pay(Player player, VoteRow row) {
        VoteConfig c = config;
        PlayerData data = players.get(player.getUniqueId());
        if (data == null) {
            release(row);
            return;
        }
        LocalDate day = Instant.ofEpochMilli(row.votedAt()).atZone(c.zone).toLocalDate();
        int[] streak = data.get(section).record(day, row.siteId(), row.votedAt());
        VoteConfig.Site site = c.sites.get(row.siteId());
        String siteName = site != null ? site.name() : row.serviceName();
        VoteConfig.Reward reward = c.voteReward;
        grant(player, reward, siteName, "vote");
        me.Evan52098.genscore.keycrates.KeyCratesModule.vote(player); // group F: vote keys + shards
        me.Evan52098.genscore.seasonpass.SeasonPassModule.progress(player.getUniqueId(), // group H
                me.Evan52098.genscore.seasonpass.Objective.VOTE, null, 1);
        String text = c.voteMessage;
        if (text != null && !text.isBlank()) {
            player.sendMessage(Msg.good(text.replace("{site}", Msg.escape(siteName)).replace("{money}", Money.format(reward.moneyCents()))
                    .replace("{xp}", String.valueOf(reward.xp()))));
        }
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.6f);
        if (streak[1] > streak[0]) {
            VoteConfig.Reward milestone = c.streakMilestones.get(streak[1]);
            if (milestone != null) {
                grant(player, milestone, siteName, "vote-streak");
                player.sendMessage(Msg.good("<white>" + streak[1] + "-day voting streak!</white> Streak bonus"
                        + (milestone.moneyCents() > 0 ? ": +<white>" + Money.format(milestone.moneyCents()) + "</white>" : "") + "."));
                player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.8f, 1.0f);
            }
        }
        logger.info("[votes] Paid " + player.getName() + " for a vote on " + siteName + " (streak " + streak[1] + ").");
    }

    private void holdParty() {
        VoteConfig c = config;
        VoteConfig.Reward reward = c.partyReward;
        List<String> lines = List.of("", "&e&lVOTE PARTY!", "&fThe server reached &e" + c.partyGoal
                + " votes&f - everyone online gets a reward!", "&7Vote with &f/vote &7to start the next one.", "");
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            count++;
            Tasks.entity.run(player, () -> {
                for (String line : lines) {
                    player.sendMessage(LEGACY.deserialize(line));
                }
                if (players.get(player.getUniqueId()) != null) {
                    grant(player, reward, "Vote Party", "vote-party");
                    players.saveSoon(player.getUniqueId());
                }
                player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 1.0f, 1.0f);
            });
        }
        logger.info("[votes] Vote party held for " + count + " players.");
    }

    /** On the player's thread. Money through the economy (ledgered), vanilla XP, and console commands on the global thread. */
    private void grant(Player player, VoteConfig.Reward reward, String siteName, String cause) {
        if (reward.moneyCents() > 0) {
            EconomyModule economy = plugin.modules().get(EconomyModule.class);
            boolean paid = economy != null && economy.service().deposit(player.getUniqueId(), reward.moneyCents(), cause);
            if (!paid) {
                logger.warning("[votes] Could not pay " + Money.format(reward.moneyCents()) + " to " + player.getName() + " (" + cause + ").");
            }
        }
        if (reward.xp() > 0) {
            player.giveExp(reward.xp());
        }
        String name = player.getName();
        String uuid = player.getUniqueId().toString();
        for (String command : reward.commands()) {
            String resolved = command.replace("{player}", name).replace("{uuid}", uuid).replace("{site}", siteName);
            Tasks.global.run(() -> {
                try {
                    if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), resolved)) {
                        logger.warning("[votes] Reward command failed for " + name + ": /" + resolved);
                    }
                } catch (RuntimeException e) {
                    logger.warning("[votes] Reward command threw for " + name + ": /" + resolved + " (" + e.getMessage() + ")");
                }
            });
        }
    }

    // ------------------------------------------------------------------ reading

    @Nullable VoteStats.Status status(Player player) {
        PlayerData data = players.get(player.getUniqueId());
        return data == null ? null : data.get(section).status(config.today());
    }

    /** The online player's voting record, or null. For other modules (leaderboards, placeholders). */
    public @Nullable VoteStats stats(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data == null ? null : data.get(section);
    }

    boolean votedToday(Player player) {
        VoteStats.Status s = status(player);
        return s != null && s.votedToday();
    }

    // ------------------------------------------------------------------ join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!config.enabled) {
            return;
        }
        Player player = event.getPlayer();
        Tasks.entity.later(player, () -> {
            claimPending(player);
            VoteConfig c = config;
            if (!c.joinReminder || c.sites.isEmpty()) {
                return;
            }
            Tasks.entity.later(player, () -> {
                if (player.isOnline() && !votedToday(player)) {
                    player.sendMessage(Msg.neutral("You haven't voted today - <white>/vote</white> for rewards!"));
                }
            }, 60);
        }, 40);
    }

    // ------------------------------------------------------------------ commands

    @Command({"vote", "votes"})
    @Description("Vote for the server and see your voting rewards")
    public void vote(BukkitCommandActor actor) {
        menu.open(actor.requirePlayer());
    }

    @Command("vote menu")
    @Description("Open the voting menu")
    public void voteFromMenu(BukkitCommandActor actor) {
        menu.open(actor.requirePlayer());
    }

    @Command("voteadmin reload")
    @CommandPermission("genscore.admin.votes")
    @Description("Reload the vote settings (and restart the Votifier listener)")
    public void reloadCommand(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        plugin.reloadConfig();
        config = new VoteConfig(plugin.getConfig().getConfigurationSection("vote"), folder, logger);
        Tasks.async.run(() -> {
            disable();
            startListener();
            VoteConfig c = config;
            Tasks.forSender(sender, () -> sender.sendMessage(Msg.good("Votes reloaded." + (c.listenerEnabled
                    ? (listening() ? " Votifier is listening." : " <red>Votifier failed to start - see the console.</red>") : ""))));
        });
    }

    @Command("voteadmin test")
    @CommandPermission("genscore.admin.votes")
    @Description("Record a vote as if a voting site sent it: /voteadmin test <player> [service]")
    public void test(BukkitCommandActor actor, @Named("player") String name, @Optional @Named("service") @Nullable String service) {
        CommandSender sender = actor.sender();
        String site = service == null || service.isBlank() ? "GensCoreTest" : service;
        if (!name.matches("[A-Za-z0-9_.]{1,32}") || site.length() > 64) {
            sender.sendMessage(Msg.bad("That isn't a valid player name or service."));
            return;
        }
        CompletableFuture.runAsync(() -> {
            synchronized (incomingLock) {
                try {
                    record(name, site, "test by " + sender.getName());
                } catch (SQLException e) {
                    throw new IllegalStateException(e);
                }
            }
        }).whenComplete((ok, error) -> Tasks.forSender(sender, () -> sender.sendMessage(error == null
                ? Msg.good("Test vote recorded for <white>" + Msg.escape(name) + "</white> from <white>" + Msg.escape(site) + "</white>.")
                : Msg.bad("Test vote failed: " + Msg.escape(String.valueOf(error.getMessage()))))));
    }

    @Command("voteadmin status")
    @CommandPermission("genscore.admin.votes")
    @Description("Show the Votifier listener and vote party state")
    public void status(BukkitCommandActor actor) {
        VoteConfig c = config;
        actor.sender().sendMessage(Msg.neutral("Votifier: <white>" + (listening() ? "listening on port " + c.port
                : c.listenerEnabled ? "NOT running (failed to start)" : "off on this server") + "</white>, sites: <white>"
                + c.sites.size() + "</white>, party: <white>" + partyProgress + "/" + c.partyGoal + "</white>."));
    }
}
