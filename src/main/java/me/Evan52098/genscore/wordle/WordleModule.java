package me.Evan52098.genscore.wordle;

import io.papermc.paper.event.player.AsyncChatEvent;
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
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * /wordle (aliases /word, /words): guess a five-letter word in six tries for money. Written fresh from HxCore's
 * {@code wordle} config (rewards 3000/2000/1500/1000/700/400 by number of guesses, a daily cap per player, an optional
 * real-words-only rule) and its bundled word list ({@code modules/wordle/words.txt}).
 *
 * <p>Each player's first game of the day is the shared daily word (the same for everyone that day); after finishing it
 * they may play more games with random words until their Wordle earnings for the day reach {@code daily-cap}. Guesses
 * are typed in chat after pressing "Guess" in the menu, or with {@code /wordle guess <word>}.
 *
 * <p>The game is stored in {@code pvp_wordle} (one row per player) so leaving and rejoining continues the same word:
 * no rerolling a hard word by relogging. Every guess is a conditional UPDATE on the previous state (checklist 2/3), and
 * the reward is paid only after the row recording the win (and the new daily total) committed (checklist 1).
 */
public final class WordleModule implements GensModule, Listener {

    private static final long PROMPT_MILLIS = 60_000;

    private GensCore plugin;
    private Database db;
    private Logger logger;

    private volatile List<String> words = List.of();
    private volatile Set<String> wordSet = Set.of();
    private volatile long[] rewards = new long[0];
    private volatile long dailyCapCents;
    private volatile boolean onlyRealWords;
    private volatile ZoneId zone = ZoneId.systemDefault();

    private final Map<UUID, WordleGame> cache = new ConcurrentHashMap<>();
    private final Set<UUID> busy = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Long> prompts = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "wordle";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.db = plugin.database();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_wordle ("
                        + "uuid TEXT PRIMARY KEY, day TEXT NOT NULL, game INTEGER NOT NULL, word TEXT NOT NULL, guesses TEXT NOT NULL, "
                        + "finished INTEGER NOT NULL DEFAULT 0, earned_cents BIGINT NOT NULL DEFAULT 0, updated_at BIGINT NOT NULL)");
        reload();
        if (words.isEmpty()) {
            throw new IllegalStateException("modules/wordle/words.txt has no five-letter words");
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("wordle");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        List<?> raw = c.getList("rewards", List.of(3000, 2000, 1500, 1000, 700, 400));
        long[] r = new long[WordleGame.MAX_GUESSES];
        for (int i = 0; i < r.length; i++) {
            r[i] = i < raw.size() ? cents(raw.get(i)) : 0;
        }
        rewards = r;
        dailyCapCents = cents(c.get("daily-cap", 15000));
        onlyRealWords = c.getBoolean("only-real-words", false);
        String tz = c.getString("timezone", "");
        try {
            zone = tz == null || tz.isBlank() ? ZoneId.systemDefault() : ZoneId.of(tz);
        } catch (RuntimeException e) {
            logger.warning("[wordle] Unknown timezone '" + tz + "' - using the server's.");
            zone = ZoneId.systemDefault();
        }
        loadWords();
    }

    /** Checklist 8: amounts from config become sane cents. */
    private static long cents(Object value) {
        double d;
        try {
            d = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
        return Double.isFinite(d) && d > 0 ? Money.toCents(Math.min(d, 10_000_000)) : 0;
    }

    private void loadWords() {
        File file = new File(plugin.getDataFolder(), "modules/wordle/words.txt");
        if (!file.exists()) {
            plugin.saveResource("modules/wordle/words.txt", false);
        }
        Set<String> set = new LinkedHashSet<>();
        try {
            for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
                for (String w : line.trim().toLowerCase(Locale.ROOT).split("[\\s,]+")) {
                    if (w.matches("[a-z]{5}")) {
                        set.add(w);
                    }
                }
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "[wordle] Could not read " + file + " - using the bundled list", e);
            try (InputStream in = plugin.getResource("modules/wordle/words.txt")) {
                if (in != null) {
                    for (String w : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\\s+")) {
                        if (w.matches("[a-z]{5}")) {
                            set.add(w);
                        }
                    }
                }
            } catch (IOException ignored) {
                // nothing more to try
            }
        }
        words = List.copyOf(set);
        wordSet = Set.copyOf(set);
    }

    private String today() {
        return LocalDate.now(zone).toString();
    }

    /** The shared word for a day: the same for every player, different each day. */
    private String dailyWord(String day) {
        List<String> list = words;
        return list.get(new Random(("genscore-wordle:" + day).hashCode() * 0x9E3779B97F4A7C15L).nextInt(list.size()));
    }

    private String randomWord(String avoid) {
        List<String> list = words;
        String w;
        int tries = 0;
        do {
            w = list.get(ThreadLocalRandom.current().nextInt(list.size()));
        } while (w.equals(avoid) && list.size() > 1 && ++tries < 20);
        return w;
    }

    // ------------------------------------------------------------------ storage

    private static @Nullable WordleGame read(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT day, game, word, guesses, finished, earned_cents FROM pvp_wordle WHERE uuid = ?")) {
            ps.setString(1, id.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new WordleGame(rs.getString(1), rs.getInt(2), rs.getString(3), WordleGame.parseGuesses(rs.getString(4)),
                        rs.getInt(5) != 0, rs.getLong(6));
            }
        }
    }

    /**
     * Replaces the stored game only if it is still {@code expected} (or absent when expected is null). Checklist 2:
     * two threads or servers racing on one player's row cannot both win.
     */
    private static boolean replace(Connection c, UUID id, @Nullable WordleGame expected, WordleGame next) throws SQLException {
        if (expected == null) {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_wordle (uuid, day, game, word, guesses, finished, earned_cents, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
                            + "ON CONFLICT (uuid) DO NOTHING")) {
                ps.setString(1, id.toString());
                ps.setString(2, next.day());
                ps.setInt(3, next.game());
                ps.setString(4, next.word());
                ps.setString(5, next.guessesText());
                ps.setInt(6, next.finished() ? 1 : 0);
                ps.setLong(7, next.earnedCents());
                ps.setLong(8, System.currentTimeMillis());
                return ps.executeUpdate() == 1;
            }
        }
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE pvp_wordle SET day = ?, game = ?, word = ?, guesses = ?, finished = ?, earned_cents = ?, updated_at = ? "
                        + "WHERE uuid = ? AND day = ? AND game = ? AND word = ? AND guesses = ? AND finished = ? AND earned_cents = ?")) {
            ps.setString(1, next.day());
            ps.setInt(2, next.game());
            ps.setString(3, next.word());
            ps.setString(4, next.guessesText());
            ps.setInt(5, next.finished() ? 1 : 0);
            ps.setLong(6, next.earnedCents());
            ps.setLong(7, System.currentTimeMillis());
            ps.setString(8, id.toString());
            ps.setString(9, expected.day());
            ps.setInt(10, expected.game());
            ps.setString(11, expected.word());
            ps.setString(12, expected.guessesText());
            ps.setInt(13, expected.finished() ? 1 : 0);
            ps.setLong(14, expected.earnedCents());
            return ps.executeUpdate() == 1;
        }
    }

    // ------------------------------------------------------------------ commands

    @Command({"wordle", "word", "words"})
    @Description("Play Wordle - guess the word to earn money")
    public void wordle(BukkitCommandActor actor) {
        open(actor.requirePlayer(), false);
    }

    @Command({"wordle guess", "word guess", "words guess"})
    @Description("Guess the Wordle word")
    public void guessCommand(BukkitCommandActor actor, @Named("word") String word) {
        guess(actor.requirePlayer(), word);
    }

    /** Loads (and starts, when needed) the player's game, then shows the board. Player thread. */
    private void open(Player player, boolean playAgain) {
        UUID id = player.getUniqueId();
        // Checklist 3: one database step per player at a time.
        if (!busy.add(id)) {
            return;
        }
        String day = today();
        db.async(c -> Database.transaction(c, tx -> {
            WordleGame row = read(tx, id);
            if (row != null && row.day().equals(day) && (!row.finished() || !playAgain)) {
                return row; // resume, or show the finished board
            }
            WordleGame next;
            if (row == null || !row.day().equals(day)) {
                next = new WordleGame(day, 1, dailyWord(day), List.of(), false, 0);
            } else {
                if (row.earnedCents() >= dailyCapCents && dailyCapCents > 0) {
                    return row;
                }
                next = new WordleGame(day, row.game() + 1, randomWord(dailyWord(day)), List.of(), false, row.earnedCents());
            }
            return replace(tx, id, row, next) ? next : read(tx, id);
        })).whenComplete((game, error) -> {
            busy.remove(id);
            Tasks.entity.run(player, () -> {
                if (error != null) {
                    logger.log(Level.WARNING, "[wordle] Could not load the game for " + player.getName(), error);
                    player.sendMessage(Msg.bad("Wordle could not be loaded right now. Try again in a moment."));
                    return;
                }
                if (game == null) {
                    return;
                }
                cache.put(id, game);
                if (playAgain && game.finished() && game.day().equals(day)) {
                    player.sendMessage(Msg.neutral("You've earned the most Wordle money for today. Come back tomorrow!"));
                }
                showMenu(player, game);
            });
        });
    }

    /** Player thread. */
    private void guess(Player player, String raw) {
        UUID id = player.getUniqueId();
        String word = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (!word.matches("[a-z]{5}")) {
            player.sendMessage(Msg.bad("Guesses are five letters, A to Z."));
            return;
        }
        if (onlyRealWords && !wordSet.contains(word)) {
            player.sendMessage(Msg.bad("That word isn't in the word list."));
            return;
        }
        WordleGame game = cache.get(id);
        if (game == null) {
            player.sendMessage(Msg.neutral("Start a game first with " + Msg.white("/wordle") + "."));
            return;
        }
        if (!game.day().equals(today())) {
            cache.remove(id);
            player.sendMessage(Msg.neutral("A new day started - type " + Msg.white("/wordle") + " for today's word."));
            return;
        }
        if (game.finished()) {
            player.sendMessage(Msg.neutral("This game is over. Open " + Msg.white("/wordle") + " to play again."));
            return;
        }
        if (game.guesses().contains(word)) {
            player.sendMessage(Msg.bad("You already guessed that word."));
            return;
        }
        // Checklist 3: ignore guesses while the last one is still being saved.
        if (!busy.add(id)) {
            return;
        }
        List<String> guesses = new ArrayList<>(game.guesses());
        guesses.add(word);
        boolean solved = word.equals(game.word());
        boolean finished = solved || guesses.size() >= WordleGame.MAX_GUESSES;
        long reward = 0;
        if (solved) {
            long base = rewards[Math.min(guesses.size(), rewards.length) - 1];
            long room = dailyCapCents > 0 ? Math.max(0, dailyCapCents - game.earnedCents()) : base;
            reward = Math.min(base, room);
        }
        long paid = reward;
        WordleGame next = new WordleGame(game.day(), game.game(), game.word(), guesses, finished, Math.addExact(game.earnedCents(), paid));
        db.async(c -> replace(c, id, game, next)).whenComplete((ok, error) -> {
            busy.remove(id);
            if (error != null || !Boolean.TRUE.equals(ok)) {
                if (error != null) {
                    logger.log(Level.WARNING, "[wordle] Could not save a guess for " + player.getName(), error);
                }
                cache.remove(id); // reload the true state next time
                Tasks.entity.run(player, () -> player.sendMessage(Msg.bad("That guess could not be saved. Open " + Msg.white("/wordle") + " and try again.")));
                return;
            }
            // Checklist 1: the win and the new daily total are stored; only now is the money paid.
            boolean deposited = false;
            if (paid > 0) {
                EconomyModule economy = plugin.modules().get(EconomyModule.class);
                deposited = economy != null && economy.service().deposit(id, paid, "wordle");
                // Checklist 11.
                logger.info("[wordle] " + player.getName() + " solved game " + game.game() + " (" + game.day() + ") in " + guesses.size()
                        + (deposited ? ", paid " + Money.format(paid) : ", reward " + Money.format(paid) + " NOT paid (economy unavailable)"));
            }
            boolean gotPaid = deposited;
            Tasks.entity.run(player, () -> {
                cache.put(id, next);
                player.sendMessage(Msg.parse("<gray>Wordle " + next.guesses().size() + "/6 <dark_gray>» " + coloured(word, game.word())));
                if (solved) {
                    player.sendMessage(Msg.good("You got it in " + guesses.size() + "!" + (gotPaid ? " <aqua>+" + Money.format(paid) + "</aqua>"
                            : (paid == 0 && dailyCapCents > 0 ? " You've hit today's Wordle money limit." : ""))));
                    player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.8f, 1.0f);
                } else if (finished) {
                    player.sendMessage(Msg.bad("Out of guesses! The word was " + Msg.white(game.word().toUpperCase(Locale.ROOT)) + "."));
                } else {
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.6f, 1.2f);
                }
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu || finished) {
                    showMenu(player, next);
                }
            });
        });
    }

    private static String coloured(String guess, String answer) {
        int[] s = WordleGame.score(guess, answer);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < guess.length(); i++) {
            String tag = s[i] == WordleGame.GREEN ? "<green>" : s[i] == WordleGame.YELLOW ? "<yellow>" : "<dark_gray>";
            b.append(tag).append("<bold>").append(Character.toUpperCase(guess.charAt(i))).append("</bold> ");
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------------ menu

    private void showMenu(Player player, WordleGame game) {
        Menu menu = new Menu(6, Msg.parse("<dark_gray>Wordle"));
        for (int row = 0; row < WordleGame.MAX_GUESSES; row++) {
            String g = row < game.guesses().size() ? game.guesses().get(row) : null;
            int[] s = g == null ? null : WordleGame.score(g, game.word());
            for (int col = 0; col < 5; col++) {
                ItemStack item;
                if (g == null) {
                    boolean current = row == game.guesses().size() && !game.finished();
                    item = Lore.item(current ? Material.LIGHT_GRAY_STAINED_GLASS_PANE : Material.BLACK_STAINED_GLASS_PANE, "&7 ", List.of());
                } else {
                    char letter = Character.toUpperCase(g.charAt(col));
                    Material m = s[col] == WordleGame.GREEN ? Material.LIME_STAINED_GLASS_PANE
                            : s[col] == WordleGame.YELLOW ? Material.YELLOW_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
                    String colour = s[col] == WordleGame.GREEN ? "&a" : s[col] == WordleGame.YELLOW ? "&e" : "&8";
                    String hint = s[col] == WordleGame.GREEN ? "Right letter, right spot" : s[col] == WordleGame.YELLOW ? "In the word, wrong spot" : "Not in the word";
                    item = Lore.item(m, colour + "&l" + letter, List.of("&7" + hint));
                }
                menu.set(2 + col, row, item, null);
            }
        }
        menu.set(0, 0, Lore.item(Material.BOOK, "&6&lHow to play", Lore.builder(Lore.Accent.WHITE)
                .description("Guess the five-letter word", "in six tries.", "", "&aGreen&7: right letter, right spot",
                        "&eYellow&7: in the word, wrong spot", "&8Gray&7: not in the word")
                .build()), null);
        Lore info = Lore.builder(Lore.Accent.CYAN).subcategory(game.game() == 1 ? "Today's daily word" : "Bonus word #" + game.game());
        for (int i = 0; i < rewards.length; i++) {
            info.stat((i + 1) + " guess" + (i == 0 ? "" : "es"), Money.format(rewards[i]));
        }
        if (dailyCapCents > 0) {
            info.divider().stat("Earned today", Money.format(game.earnedCents()) + " / " + Money.format(dailyCapCents));
        }
        menu.set(8, 0, Lore.item(Material.GOLD_INGOT, "&e&lRewards", info.build()), null);
        menu.set(8, 2, Lore.item(Material.PAPER, "&f&lLetters", lettersLore(game)), null);
        if (!game.finished()) {
            menu.set(8, 1, Lore.item(Material.WRITABLE_BOOK, "&a&lGuess", Lore.builder(Lore.Accent.GREEN)
                    .description("Type your guess in chat.", "Or use /wordle guess <word>.")
                    .stat("Guesses left", String.valueOf(WordleGame.MAX_GUESSES - game.guesses().size()))
                    .clickPrompt("Click to guess").build()), click -> {
                player.closeInventory();
                prompts.put(player.getUniqueId(), System.currentTimeMillis() + PROMPT_MILLIS);
                player.sendMessage(Msg.neutral("Type your five-letter guess in chat, or " + Msg.white("cancel") + "."));
            });
        } else {
            boolean capped = dailyCapCents > 0 && game.earnedCents() >= dailyCapCents;
            String result = game.solved() ? "&aSolved in " + game.guesses().size() + "!" : "&cThe word was &f" + game.word().toUpperCase(Locale.ROOT);
            menu.set(8, 1, Lore.item(capped ? Material.CLOCK : Material.LIME_DYE, capped ? "&e&lCome back tomorrow" : "&a&lPlay again",
                    capped ? List.of(result, "&7You've earned today's Wordle limit.") : List.of(result, "", "&e" + Lore.smallCaps("Click for a new word"))),
                    capped ? null : click -> open(player, true));
        }
        menu.set(8, 5, Lore.item(Material.BARRIER, "&cClose", List.of()), click -> player.closeInventory());
        menu.open(player);
    }

    private static List<String> lettersLore(WordleGame game) {
        Map<Character, Integer> best = new TreeMap<>();
        for (String g : game.guesses()) {
            int[] s = WordleGame.score(g, game.word());
            for (int i = 0; i < g.length(); i++) {
                best.merge(Character.toUpperCase(g.charAt(i)), s[i], Math::max);
            }
        }
        StringBuilder green = new StringBuilder();
        StringBuilder yellow = new StringBuilder();
        StringBuilder gray = new StringBuilder();
        Set<Character> seen = new HashSet<>();
        for (Map.Entry<Character, Integer> e : best.entrySet()) {
            if (!seen.add(e.getKey())) {
                continue;
            }
            StringBuilder target = e.getValue() == WordleGame.GREEN ? green : e.getValue() == WordleGame.YELLOW ? yellow : gray;
            target.append(e.getKey()).append(' ');
        }
        List<String> lore = new ArrayList<>();
        lore.add("&aIn place: &f" + (green.isEmpty() ? "-" : green.toString().trim()));
        lore.add("&eIn the word: &f" + (yellow.isEmpty() ? "-" : yellow.toString().trim()));
        lore.add("&8Not in it: &7" + (gray.isEmpty() ? "-" : gray.toString().trim()));
        return lore;
    }

    // ------------------------------------------------------------------ chat input

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        Long until = prompts.remove(player.getUniqueId());
        if (until == null || until < System.currentTimeMillis()) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Tasks.entity.run(player, () -> {
            if (text.equalsIgnoreCase("cancel")) {
                player.sendMessage(Msg.neutral("Guess cancelled."));
                return;
            }
            guess(player, text);
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        prompts.remove(id);
        cache.remove(id);
    }
}
