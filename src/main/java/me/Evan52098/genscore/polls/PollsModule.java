package me.Evan52098.genscore.polls;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Server polls: /poll (vote menu), /poll create, /poll close. Ported from BlockvilleCore's PollCommand, PollService,
 * PollData and PollVoteData with the same menu and messages.
 *
 * <p>One vote per player per poll is enforced by the database: the votes table's primary key is (poll, player), and the
 * insert only happens while the poll is open. The voting reward is paid only after that insert succeeded (checklists
 * 1 and 2). BlockvilleCore paid "credits", a currency this server does not have; the reward here is money from
 * {@code polls.reward} in config.yml (0 = none). Announcements go to this server only (no network broadcast).
 */
public final class PollsModule implements GensModule, Listener {

    private static final Material[] CHOICE_ICONS = {Material.LIME_DYE, Material.LIGHT_BLUE_DYE, Material.YELLOW_DYE, Material.PINK_DYE, Material.ORANGE_DYE};
    private static final String[] CHOICE_COLOURS = {"&a", "&b", "&e", "&d", "&6"};
    private static final String[] CHOICE_TAGS = {"<green>", "<aqua>", "<yellow>", "<light_purple>", "<gold>"};
    private static final String ORANGE = "<#ffaa33>";

    record Poll(String id, String question, List<String> options, long closesAt, long rewardCents) {
    }

    private GensCore plugin;
    private Database db;
    private Logger logger;
    private long rewardCents;

    private volatile @Nullable Poll current;
    private volatile AtomicIntegerArray counts = new AtomicIntegerArray(0);
    private final Set<UUID> voters = ConcurrentHashMap.newKeySet();
    private final Set<UUID> voting = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "polls";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_polls ("
                        + "id TEXT PRIMARY KEY, question TEXT NOT NULL, options TEXT NOT NULL, created_by TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL, closes_at BIGINT NOT NULL, is_open INTEGER NOT NULL DEFAULT 1, reward_cents BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_polls_open ON pvp_polls (is_open, created_at);"
                        + "CREATE TABLE IF NOT EXISTS pvp_poll_votes ("
                        + "poll_id TEXT NOT NULL, uuid TEXT NOT NULL, name TEXT NOT NULL, choice INTEGER NOT NULL, voted_at BIGINT NOT NULL, "
                        + "PRIMARY KEY (poll_id, uuid))");
        reload();
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.timer(t -> refresh(), 2, 30, TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("polls");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        double reward = c.getDouble("reward", 100);
        // Checklist 8: a sane, non-negative amount in cents.
        rewardCents = Double.isFinite(reward) && reward > 0 ? Math.min(Money.toCents(Math.min(reward, 1_000_000)), 100_000_000L) : 0;
    }

    private String rewardText(long cents) {
        return Money.format(cents);
    }

    // ------------------------------------------------------------------ state

    private static List<String> splitOptions(String raw) {
        return Arrays.asList(raw.split("\n"));
    }

    private static @Nullable Poll readOpen(Connection c) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT id, question, options, closes_at, reward_cents FROM pvp_polls WHERE is_open = 1 ORDER BY created_at DESC LIMIT 1");
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                return null;
            }
            return new Poll(rs.getString(1), rs.getString(2), splitOptions(rs.getString(3)), rs.getLong(4), rs.getLong(5));
        }
    }

    private record Tally(int[] counts, Set<UUID> voters, int total) {
    }

    private static Tally tally(Connection c, Poll poll) throws SQLException {
        int[] counts = new int[poll.options().size()];
        Set<UUID> who = new HashSet<>();
        int total = 0;
        try (PreparedStatement ps = c.prepareStatement("SELECT uuid, choice FROM pvp_poll_votes WHERE poll_id = ?")) {
            ps.setString(1, poll.id());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    who.add(UUID.fromString(rs.getString(1)));
                    int choice = rs.getInt(2);
                    if (choice >= 0 && choice < counts.length) {
                        counts[choice]++;
                    }
                    total++;
                }
            }
        }
        return new Tally(counts, who, total);
    }

    /** Async: loads the open poll and its votes, and closes it when its time is up. */
    private void refresh() {
        db.async(c -> {
            Poll poll = readOpen(c);
            return poll == null ? null : new Object[]{poll, tally(c, poll)};
        }).whenComplete((result, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "[polls] Could not refresh the current poll", error);
                return;
            }
            if (result == null) {
                current = null;
                voters.clear();
                counts = new AtomicIntegerArray(0);
                return;
            }
            Poll poll = (Poll) result[0];
            Tally t = (Tally) result[1];
            if (poll.closesAt() > 0 && poll.closesAt() <= System.currentTimeMillis()) {
                close(null, poll);
                return;
            }
            Poll before = current;
            if (before == null || !before.id().equals(poll.id())) {
                voters.clear();
            }
            counts = new AtomicIntegerArray(t.counts());
            voters.addAll(t.voters());
            current = poll;
        });
    }

    // ------------------------------------------------------------------ commands

    @Command("poll")
    @Description("Vote in the current poll")
    public void poll(BukkitCommandActor actor) {
        open(actor.requirePlayer());
    }

    @Command("poll create")
    @CommandPermission("genscore.admin.poll")
    @Description("Start a poll: /poll create <hours> Question? | Answer one | Answer two")
    public void create(BukkitCommandActor actor, @Named("hours") int hours, @Named("question and answers") String text) {
        CommandSender sender = actor.sender();
        List<String> parts = new ArrayList<>();
        for (String part : text.split("\\|")) {
            if (!part.trim().isEmpty()) {
                parts.add(part.trim().replace("\n", " "));
            }
        }
        if (parts.size() < 3 || parts.size() > 6) {
            sender.sendMessage(Msg.bad("Write it as: /poll create <hours> Question? | Answer one | Answer two  (2 to 5 answers)."));
            return;
        }
        String question = parts.get(0);
        List<String> options = List.copyOf(parts.subList(1, parts.size()));
        int clampedHours = Math.max(1, Math.min(24 * 14, hours));
        long closes = System.currentTimeMillis() + clampedHours * 3_600_000L;
        Poll poll = new Poll(UUID.randomUUID().toString(), question, options, closes, rewardCents);
        db.asyncRun(c -> Database.transaction(c, tx -> {
            try (PreparedStatement ps = tx.prepareStatement("UPDATE pvp_polls SET is_open = 0 WHERE is_open = 1")) {
                ps.executeUpdate();
            }
            try (PreparedStatement ps = tx.prepareStatement(
                    "INSERT INTO pvp_polls (id, question, options, created_by, created_at, closes_at, is_open, reward_cents) VALUES (?, ?, ?, ?, ?, ?, 1, ?)")) {
                ps.setString(1, poll.id());
                ps.setString(2, question);
                ps.setString(3, String.join("\n", options));
                ps.setString(4, sender.getName());
                ps.setLong(5, System.currentTimeMillis());
                ps.setLong(6, closes);
                ps.setLong(7, poll.rewardCents());
                ps.executeUpdate();
            }
            return null;
        })).whenComplete((v, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("Couldn't save the poll - check the console."));
                logger.log(Level.WARNING, "[polls] Could not save a poll", error);
                return;
            }
            voters.clear();
            counts = new AtomicIntegerArray(options.size());
            current = poll;
            sender.sendMessage(Msg.good("Poll started - it closes in " + clampedHours + "h."));
            broadcast(" \n" + ORANGE + "<bold>POLL</bold> <dark_gray>» <white>" + Msg.escape(question) + "\n"
                    + "<gray>" + (poll.rewardCents() > 0 ? "Vote for <aqua>" + rewardText(poll.rewardCents()) + "</aqua>: " : "Vote: ")
                    + "<click:run_command:'/poll'><hover:show_text:'Open the poll'><green><bold>[Vote]</bold></green></hover></click>"
                    + "<gray> or type <white>/poll\n ");
        }));
    }

    @Command("poll close")
    @CommandPermission("genscore.admin.poll")
    @Description("Close the current poll and announce the results")
    public void closeCommand(BukkitCommandActor actor) {
        close(actor.sender(), null);
    }

    /** Checklist 2: only the call that flips the poll from open to closed announces the results. */
    private void close(@Nullable CommandSender sender, @Nullable Poll poll) {
        Poll target = poll != null ? poll : current;
        if (target == null) {
            if (sender != null) {
                sender.sendMessage(Msg.bad("There's no open poll."));
            }
            return;
        }
        db.async(c -> {
            int changed;
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_polls SET is_open = 0 WHERE id = ? AND is_open = 1")) {
                ps.setString(1, target.id());
                changed = ps.executeUpdate();
            }
            return changed == 1 ? tally(c, target) : null;
        }).whenComplete((tally, error) -> {
            Poll now = current;
            if (now != null && now.id().equals(target.id())) {
                current = null;
                voters.clear();
                counts = new AtomicIntegerArray(0);
            }
            if (error != null) {
                logger.log(Level.WARNING, "[polls] Could not close poll " + target.id(), error);
                if (sender != null) {
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("Couldn't close the poll - check the console.")));
                }
                return;
            }
            if (tally == null) {
                if (sender != null) {
                    Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("There's no open poll.")));
                }
                return;
            }
            StringBuilder results = new StringBuilder(" \n" + ORANGE + "<bold>POLL RESULTS</bold> <dark_gray>» <white>" + Msg.escape(target.question()));
            for (int i = 0; i < target.options().size(); i++) {
                results.append("\n  ").append(CHOICE_TAGS[i % CHOICE_TAGS.length]).append(Msg.escape(target.options().get(i)))
                        .append("<dark_gray> · <white>").append(percent(tally.counts()[i], tally.total())).append("%<gray> (")
                        .append(tally.counts()[i]).append(")");
            }
            results.append("\n ");
            if (sender != null) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.good("Poll closed.")));
            }
            broadcast(results.toString());
        });
    }

    private static void broadcast(String miniMessage) {
        Component line = Msg.parse(miniMessage);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    private static int percent(int part, int total) {
        return total <= 0 ? 0 : (int) Math.round(part * 100.0 / total);
    }

    // ------------------------------------------------------------------ menu

    private void open(Player player) {
        Poll poll = current;
        if (poll == null) {
            player.sendMessage(Msg.neutral("There's no poll running right now."));
            return;
        }
        List<String> options = poll.options();
        boolean voted = voters.contains(player.getUniqueId());
        AtomicIntegerArray tally = counts;
        int total = 0;
        for (int i = 0; i < tally.length(); i++) {
            total += tally.get(i);
        }
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Poll"));
        long hoursLeft = Math.max(0, (poll.closesAt() - System.currentTimeMillis()) / 3_600_000L);
        menu.set(4, Lore.item(Material.WRITABLE_BOOK, "&6&l" + legacySafe(poll.question()), List.of(
                "&7Closes in &f" + (hoursLeft >= 1 ? hoursLeft + "h" : "under an hour"),
                "&7" + total + " vote" + (total == 1 ? "" : "s") + " so far",
                voted ? "&aYou've voted - thanks!" : (poll.rewardCents() > 0 ? "&bVote for " + rewardText(poll.rewardCents()) : "&bClick an answer to vote"))));
        int start = 4 - (options.size() - 1);
        for (int i = 0; i < options.size(); i++) {
            int choice = i;
            int count = i < tally.length() ? tally.get(i) : 0;
            List<String> lore = new ArrayList<>();
            if (voted) {
                int pct = percent(count, total);
                lore.add("&7" + bar(pct) + " &f" + pct + "%&7 (" + count + ")");
            } else {
                lore.add(me.Evan52098.genscore.menu.Lore.prompt("click to vote"));
            }
            menu.set(start + i * 2, 1, Lore.item(CHOICE_ICONS[i % CHOICE_ICONS.length],
                    CHOICE_COLOURS[i % CHOICE_COLOURS.length] + "&l" + legacySafe(options.get(i)), lore), click -> vote(player, poll, choice));
        }
        menu.set(4, 2, Lore.item(Material.BARRIER, "&cClose", List.of()), click -> player.closeInventory());
        menu.open(player);
    }

    /** Admin-written text shown in item names: strip '&' so it cannot inject colour codes. */
    private static String legacySafe(String text) {
        return text.replace('&', '+');
    }

    private static String bar(int pct) {
        int filled = Math.round(pct / 10f);
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(10 - filled);
    }

    /** Runs on the voter's thread. */
    private void vote(Player player, Poll poll, int choice) {
        UUID id = player.getUniqueId();
        if (choice < 0 || choice >= poll.options().size()) {
            return;
        }
        // Checklist 3: one vote in flight per player; a fast double click cannot vote twice.
        if (voters.contains(id) || !voting.add(id)) {
            player.sendMessage(Msg.neutral("You've already voted on this poll."));
            return;
        }
        String name = player.getName();
        db.async(c -> {
            // Checklist 2: the (poll, player) primary key lets exactly one insert win, and only while the poll is open.
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_poll_votes (poll_id, uuid, name, choice, voted_at) "
                            + "SELECT ?, ?, ?, ?, ? WHERE EXISTS (SELECT 1 FROM pvp_polls WHERE id = ? AND is_open = 1) "
                            + "ON CONFLICT (poll_id, uuid) DO NOTHING")) {
                ps.setString(1, poll.id());
                ps.setString(2, id.toString());
                ps.setString(3, name);
                ps.setInt(4, choice);
                ps.setLong(5, System.currentTimeMillis());
                ps.setString(6, poll.id());
                return ps.executeUpdate() == 1;
            }
        }).whenComplete((won, error) -> {
            voting.remove(id);
            if (error != null) {
                logger.log(Level.WARNING, "[polls] Could not record a vote for " + name, error);
                Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("Your vote could not be saved right now. Try again in a moment.")));
                return;
            }
            voters.add(id);
            if (!won) {
                Tasks.entity.run(player, () -> player.sendMessage(Msg.neutral("You've already voted on this poll.")));
                return;
            }
            AtomicIntegerArray tally = counts;
            Poll now = current;
            if (now != null && now.id().equals(poll.id()) && choice < tally.length()) {
                tally.incrementAndGet(choice);
            }
            // Checklist 1: the reward only after the vote is recorded.
            boolean paid = false;
            if (poll.rewardCents() > 0) {
                EconomyModule economy = plugin.modules().get(EconomyModule.class);
                paid = economy != null && economy.service().deposit(id, poll.rewardCents(), "poll");
            }
            boolean rewarded = paid;
            Tasks.entity.run(player, () -> {
                player.sendMessage(Msg.good("Thanks for voting!" + (rewarded ? " <aqua>+" + rewardText(poll.rewardCents()) + "</aqua>" : "")));
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, SoundCategory.PLAYERS, 0.8f, 1.2f);
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                    open(player);
                }
            });
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Tasks.entity.later(player, () -> {
            Poll poll = current;
            if (poll == null || !player.isOnline() || voters.contains(player.getUniqueId())) {
                return;
            }
            player.sendMessage(Msg.parse(ORANGE + "<bold>POLL</bold> <dark_gray>» <white>" + Msg.escape(poll.question()) + " "
                    + "<click:run_command:'/poll'><hover:show_text:'Open the poll'><green><bold>[Vote]</bold></green></hover></click>"
                    + (poll.rewardCents() > 0 ? "<gray> +" + rewardText(poll.rewardCents()) : "")));
        }, 120);
    }
}
