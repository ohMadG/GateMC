package me.Evan52098.genscore.chatgames;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
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
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Chat games: every few minutes a round is posted in chat and the first player to type the answer wins money.
 * Ported from BlockvilleCore's ChatGameService, ChatGameListener, ChatGamesCommand and ChatGameStatsService.
 *
 * <p>Folia: the answer arrives on an async chat thread, so the active round lives in an {@link AtomicReference} and the
 * winner is the one {@code compareAndSet(round, null)} that succeeds (checklist 2: one winner even when two players
 * answer at the same moment on two threads; the timeout uses the same compareAndSet, so a round is either won or timed
 * out, never both). Rounds are scheduled on the global thread; messages go to each player on their own thread.
 *
 * <p>Left out from BlockvilleCore: the network relay (Redis) and host-server choice (one server here), chat game XP,
 * ranks, milestones and achievements (the progression system was not ported). Timings and reward range follow HxCore's
 * chat-games defaults (every 7 minutes, first round 4 minutes after start, 60 s to answer, random 250 to 1000 reward).
 */
public final class ChatGamesModule implements GensModule, Listener {

    private record Settings(boolean enabled, long everyMinutes, long offsetMinutes, int minPlayers, int answerSeconds,
                            long rewardMinCents, long rewardMaxCents, List<ChatGameType> types, long minAnswerMs,
                            String sound, String winSound, ChatGameGenerator.Data data) {
    }

    private GensCore plugin;
    private Database db;
    private Logger logger;
    private volatile Settings settings;
    private final AtomicReference<ChatGameRound> active = new AtomicReference<>();
    private final Set<UUID> participants = ConcurrentHashMap.newKeySet();
    private volatile @Nullable ScheduledTask timer;
    private volatile long nextAt;
    /** "uuid:streak" of the last winner (in memory, like BlockvilleCore's local fallback). */
    private final AtomicReference<String> streak = new AtomicReference<>("");

    @Override
    public String id() {
        return "chatgames";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_chatgame_stats ("
                        + "uuid TEXT PRIMARY KEY, name TEXT NOT NULL, wins INTEGER NOT NULL DEFAULT 0, best_streak INTEGER NOT NULL DEFAULT 0, "
                        + "fastest_ms INTEGER NOT NULL DEFAULT 0, played INTEGER NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE INDEX IF NOT EXISTS pvp_chatgame_stats_wins ON pvp_chatgame_stats (wins)");
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
    }

    @Override
    public void disable() {
        ScheduledTask t = timer;
        if (t != null) {
            t.cancel();
        }
        active.set(null);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("chatgames");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        List<ChatGameType> types = new ArrayList<>();
        for (String raw : c.getStringList("enabled-types")) {
            ChatGameType t = ChatGameType.parse(raw);
            if (t == null) {
                logger.warning("[chatgames] Unknown game type '" + raw + "' in chatgames.enabled-types - skipping it.");
            } else if (!types.contains(t)) {
                types.add(t);
            }
        }
        if (types.isEmpty()) {
            types = List.of(ChatGameType.values());
        }
        long min = cents(c.getDouble("reward-min", 250));
        long max = cents(c.getDouble("reward-max", 1000));
        if (max < min) {
            max = min;
        }
        ChatGameGenerator.Data data = new ChatGameGenerator.Data(
                c.getStringList("words").stream().filter(w -> w != null && !w.isBlank()).map(String::trim).toList(),
                ChatGameGenerator.parseTrivia(c.getMapList("trivia")),
                c.getInt("number-max", 50));
        settings = new Settings(c.getBoolean("enabled", true),
                Math.max(1, c.getLong("every-minutes", 7)),
                Math.max(0, c.getLong("offset-minutes", 4)),
                Math.max(0, c.getInt("min-players", 1)),
                Math.max(5, Math.min(600, c.getInt("answer-seconds", 60))),
                min, max, List.copyOf(types),
                Math.max(0, c.getLong("min-answer-ms", 700)),
                c.getString("sound", "entity.player.levelup"),
                c.getString("win-sound", "ui.toast.challenge_complete"),
                data);
        ScheduledTask old = timer;
        if (old != null) {
            old.cancel();
        }
        Settings s = settings;
        long period = s.everyMinutes() * 60L * 20L;
        long delay = s.offsetMinutes() > 0 ? s.offsetMinutes() * 60L * 20L : period;
        nextAt = System.currentTimeMillis() + delay * 50L;
        timer = Tasks.global.timer(t -> {
            nextAt = System.currentTimeMillis() + period * 50L;
            tryStartRound();
        }, delay, period);
    }

    /** Checklist 8: a sane reward, cents only, never negative. */
    private static long cents(double amount) {
        if (!Double.isFinite(amount) || amount <= 0) {
            return 0;
        }
        return Money.toCents(Math.min(amount, 10_000_000));
    }

    // ------------------------------------------------------------------ rounds

    /** Starts a round if none is running. Any thread. */
    public boolean tryStartRound() {
        Settings s = settings;
        if (!s.enabled() || Bukkit.getOnlinePlayers().size() < s.minPlayers() || active.get() != null) {
            return false;
        }
        ChatGameType type = s.types().get(ThreadLocalRandom.current().nextInt(s.types().size()));
        ChatGameRound round = ChatGameGenerator.generate(type, s.data());
        if (!active.compareAndSet(null, round)) {
            return false;
        }
        participants.clear();
        Component gap = Component.newline();
        Component line = Msg.neutral("<white>" + type.displayName() + "!</white> Type the answer in chat: <white>" + Msg.escape(round.prompt()) + "</white>");
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> {
                p.sendMessage(gap);
                p.sendMessage(line);
                p.sendMessage(gap);
                playSound(p, s.sound());
            });
        }
        Bukkit.getConsoleSender().sendMessage(line);
        Tasks.global.later(() -> timeout(round), s.answerSeconds() * 20L);
        return true;
    }

    private void timeout(ChatGameRound round) {
        // Checklist 2: either the timeout or a winner clears the round, never both.
        if (!active.compareAndSet(round, null)) {
            return;
        }
        broadcast(Msg.neutral("Nobody answered in time! The answer was <white>" + Msg.escape(round.answer()) + "</white>"));
        recordPlayed();
    }

    // Runs after the chat filter (HIGH) and skips cancelled messages, so a muted or filtered message cannot win.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        ChatGameRound round = active.get();
        if (round == null) {
            return;
        }
        Player player = event.getPlayer();
        String message = PlainTextComponentSerializer.plainText().serialize(event.message());
        participants.add(player.getUniqueId());
        if (!round.matches(message)) {
            return;
        }
        event.setCancelled(true); // the answer is not shown to others (BlockvilleCore behaviour)
        long answerMs = Math.max(0, System.currentTimeMillis() - round.startedAtMs());
        Settings s = settings;
        if (answerMs < s.minAnswerMs()) {
            // Faster than a person can read and type: ignored (BlockvilleCore only skipped stats; here it cannot win money).
            logger.warning("[chatgames] " + player.getName() + " answered a " + round.type() + " round in " + answerMs + "ms - too fast, ignored.");
            return;
        }
        // Checklist 2: exactly one winner.
        if (!active.compareAndSet(round, null)) {
            return;
        }
        UUID id = player.getUniqueId();
        String name = player.getName();
        int wins = nextStreak(id);
        long reward = s.rewardMaxCents() <= s.rewardMinCents() ? s.rewardMinCents()
                : ThreadLocalRandom.current().nextLong(s.rewardMinCents(), s.rewardMaxCents() + 1);
        boolean paid = reward <= 0;
        if (reward > 0) {
            EconomyModule economy = plugin.modules().get(EconomyModule.class);
            paid = economy != null && economy.service().deposit(id, reward, "chat game " + round.type().name().toLowerCase(Locale.ROOT));
        }
        if (!paid) {
            logger.warning("[chatgames] Could not pay the " + Money.format(reward) + " reward for a " + round.type() + " win to " + name);
        }
        // Checklist 11: logged.
        logger.info("[chatgames] " + name + " won a " + round.type() + " round in " + answerMs + "ms" + (paid && reward > 0 ? ", paid " + Money.format(reward) : ""));
        String time = String.format(Locale.US, "%.1fs", answerMs / 1000.0);
        String rewardText = paid && reward > 0 ? " (+<white>" + Money.format(reward) + "</white>)" : "";
        String streakText = wins >= 2 ? " <white>x" + wins + " win streak!</white>" : "";
        broadcast(Msg.good("<white>" + Msg.escape(name) + "</white> won the <white>" + round.type().displayName() + "</white> game in <white>"
                + time + "</white>!" + rewardText + streakText));
        Tasks.entity.run(player, () -> playSound(player, s.winSound()));
        recordWin(id, name, answerMs, wins);
        recordPlayed();
    }

    private int nextStreak(UUID winner) {
        String[] result = new String[1];
        streak.updateAndGet(prev -> {
            int n = 1;
            String[] parts = prev.split(":", 2);
            if (parts.length == 2 && parts[0].equals(winner.toString())) {
                try {
                    n = Integer.parseInt(parts[1]) + 1;
                } catch (NumberFormatException ignored) {
                    n = 1;
                }
            }
            result[0] = String.valueOf(n);
            return winner + ":" + n;
        });
        return Integer.parseInt(result[0]);
    }

    private static void playSound(Player p, String sound) {
        if (sound != null && !sound.isBlank()) {
            try {
                p.playSound(p.getLocation(), sound, 1.0f, 1.0f);
            } catch (RuntimeException ignored) {
                // a bad sound key in config only loses the sound
            }
        }
    }

    private static void broadcast(Component line) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(line));
        }
        Bukkit.getConsoleSender().sendMessage(line);
    }

    // ------------------------------------------------------------------ stats

    private void recordWin(UUID id, String name, long answerMs, int currentStreak) {
        int ms = (int) Math.min(Integer.MAX_VALUE, answerMs);
        db.asyncRun(c -> Database.transaction(c, tx -> {
            ensureRow(tx, id, name);
            try (PreparedStatement ps = tx.prepareStatement(
                    "UPDATE pvp_chatgame_stats SET name = ?, wins = wins + 1, "
                            + "best_streak = CASE WHEN best_streak < ? THEN ? ELSE best_streak END, "
                            + "fastest_ms = CASE WHEN fastest_ms = 0 OR fastest_ms > ? THEN ? ELSE fastest_ms END, updated_at = ? WHERE uuid = ?")) {
                ps.setString(1, name);
                ps.setInt(2, currentStreak);
                ps.setInt(3, currentStreak);
                ps.setInt(4, ms);
                ps.setInt(5, ms);
                ps.setLong(6, System.currentTimeMillis());
                ps.setString(7, id.toString());
                ps.executeUpdate();
            }
            return null;
        })).exceptionally(e -> {
            logger.log(Level.WARNING, "[chatgames] Could not save a win for " + name, e);
            return null;
        });
    }

    private void recordPlayed() {
        List<UUID> played = new ArrayList<>(participants);
        participants.clear();
        if (played.isEmpty()) {
            return;
        }
        List<String[]> rows = new ArrayList<>();
        for (UUID u : played) {
            Player p = Bukkit.getPlayer(u);
            rows.add(new String[]{u.toString(), p != null ? p.getName() : "?"});
        }
        db.asyncRun(c -> Database.transaction(c, tx -> {
            for (String[] r : rows) {
                ensureRow(tx, UUID.fromString(r[0]), r[1]);
                try (PreparedStatement ps = tx.prepareStatement("UPDATE pvp_chatgame_stats SET played = played + 1 WHERE uuid = ?")) {
                    ps.setString(1, r[0]);
                    ps.executeUpdate();
                }
            }
            return null;
        })).exceptionally(e -> {
            logger.log(Level.WARNING, "[chatgames] Could not save games played", e);
            return null;
        });
    }

    private static void ensureRow(java.sql.Connection c, UUID id, String name) throws java.sql.SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO pvp_chatgame_stats (uuid, name, updated_at) VALUES (?, ?, ?) ON CONFLICT (uuid) DO NOTHING")) {
            ps.setString(1, id.toString());
            ps.setString(2, name);
            ps.setLong(3, System.currentTimeMillis());
            ps.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ commands

    @Command({"chatgames", "chatgame"})
    @Description("Chat games: what is running and when the next one starts")
    public void info(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        ChatGameRound round = active.get();
        if (round != null) {
            sender.sendMessage(Msg.neutral("<white>" + round.type().displayName() + "</white> is running: <white>" + Msg.escape(round.prompt()) + "</white>"));
        } else if (settings.enabled()) {
            sender.sendMessage(Msg.neutral("The next chat game starts in about <white>"
                    + me.Evan52098.genscore.util.Cooldowns.format(Math.max(1000, nextAt - System.currentTimeMillis())) + "</white>."));
        } else {
            sender.sendMessage(Msg.neutral("Chat games are switched off."));
        }
        sender.sendMessage(Msg.neutral("See your record with <white>/chatgames stats</white> and the best players with <white>/chatgames top</white>."));
    }

    @Command({"chatgames stats", "chatgame stats"})
    @Description("Show a player's chat game record")
    public void stats(BukkitCommandActor actor, @Optional @Named("player") @Nullable String name) {
        CommandSender sender = actor.sender();
        UUID target;
        String shown;
        if (name == null || name.isBlank()) {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(Msg.bad("Name a player: /chatgames stats \\<player>"));
                return;
            }
            target = p.getUniqueId();
            shown = p.getName();
        } else {
            OfflinePlayer off = Bukkit.getOfflinePlayerIfCached(name);
            if (off == null) {
                sender.sendMessage(Msg.bad("That player has never joined."));
                return;
            }
            target = off.getUniqueId();
            shown = off.getName() != null ? off.getName() : name;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT wins, best_streak, fastest_ms, played FROM pvp_chatgame_stats WHERE uuid = ?")) {
                ps.setString(1, target.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? new int[]{rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)} : new int[]{0, 0, 0, 0};
                }
            }
        }).whenComplete((r, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("The chat game record could not be loaded right now."));
                return;
            }
            sender.sendMessage(Msg.parse("<gold><bold>Chat Games</bold></gold> <gray>- <white>" + Msg.escape(shown)));
            if (r[0] == 0) {
                sender.sendMessage(Msg.parse("<gray>  No wins yet. Be the first to type the answer when a game starts!"));
            } else {
                sender.sendMessage(Msg.parse("<gray>  Wins: <white>" + r[0] + "</white>  Best streak: <white>" + r[1] + "</white>  Fastest: <white>"
                        + String.format(Locale.US, "%.1fs", r[2] / 1000.0) + "</white>"));
            }
            sender.sendMessage(Msg.parse("<gray>  Games played: <white>" + r[3]));
        }));
    }

    @Command({"chatgames top", "chatgame top"})
    @Description("The players with the most chat game wins")
    public void top(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        db.async(c -> {
            List<String> lines = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT name, wins, best_streak FROM pvp_chatgame_stats WHERE wins > 0 ORDER BY wins DESC, best_streak DESC LIMIT 10");
                 ResultSet rs = ps.executeQuery()) {
                int rank = 1;
                while (rs.next()) {
                    lines.add("<yellow>  #" + rank++ + " <white>" + Msg.escape(rs.getString(1)) + " <gray>- <white>" + rs.getInt(2)
                            + " wins <dark_gray>(best streak " + rs.getInt(3) + ")");
                }
            }
            return lines;
        }).whenComplete((lines, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                sender.sendMessage(Msg.bad("Could not load the leaderboard right now."));
                return;
            }
            if (lines.isEmpty()) {
                sender.sendMessage(Msg.neutral("Nobody has won a chat game yet."));
                return;
            }
            sender.sendMessage(Msg.parse("<gold><bold>Top Chat Gamers"));
            lines.forEach(l -> sender.sendMessage(Msg.parse(l)));
        }));
    }

    @Command({"chatgames start", "chatgame start"})
    @CommandPermission("genscore.chatgames.start")
    @Description("Start a chat game round now")
    public void start(BukkitCommandActor actor) {
        if (active.get() != null) {
            actor.sender().sendMessage(Msg.bad("A round is already in progress."));
            return;
        }
        if (!tryStartRound()) {
            actor.sender().sendMessage(Msg.bad("Couldn't start a round right now (chat games are disabled or not enough players are online)."));
        }
    }
}
