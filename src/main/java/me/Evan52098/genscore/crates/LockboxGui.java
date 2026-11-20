package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * The /lockbox board, ported from BlockvilleCore's LockboxGui: the past guesses with green/yellow/grey marks, an open
 * row to fill, the eight symbols, Submit, Clear, How to Play, status and Close. The resource-pack tile title is left
 * out (this server has no Blockville pack); the marks are on the items instead.
 *
 * <p>Folia: everything here runs on the player's thread (clicks arrive there; database answers hop back with
 * {@code Tasks.entity}). A session ends when its menu is closed or the player quits.
 */
public final class LockboxGui implements Listener {

    private static final int SIZE = 54;
    private static final int ROWS = 4;
    private static final int BOARD_COLUMN = 3;
    private static final int[] SYMBOL_SLOTS = {1, 10, 19, 28, 7, 16, 25, 34};
    private static final LockboxSymbol[] SYMBOL_ORDER = LockboxSymbol.values();
    private static final int HELP_SLOT = 45;
    private static final int CLEAR_SLOT = 47;
    private static final int SUBMIT_SLOT = 49;
    private static final int STATUS_SLOT = 51;
    private static final int CLOSE_SLOT = 53;
    private static final Component TITLE = LegacyComponentSerializer.legacyAmpersand().deserialize("&8Daily Lockbox");

    private static final class Session {
        LockboxGame game;
        final Menu menu = new Menu(6, TITLE);
        final LockboxSymbol[] draft = new LockboxSymbol[3];
        int draftRow = -1;
        int selectedPosition;
        boolean submitting; // checklist 3: one guess in flight at a time

        Session(LockboxGame game) {
            this.game = game;
        }
    }

    private final GensCore plugin;
    private final LockboxService service;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final me.Evan52098.genscore.util.Cooldowns openLimiter = new me.Evan52098.genscore.util.Cooldowns();

    LockboxGui(GensCore plugin, LockboxService service) {
        this.plugin = plugin;
        this.service = service;
    }

    /** Opens the player's lockbox. Call on the player's thread. */
    public void open(Player player) {
        if (!openLimiter.tryStart(player.getUniqueId(), "open", 500L)) {
            return; // checklist 10: each open reads (and may create) a game in the database
        }
        load(player);
    }

    private void load(Player player) {
        service.getOrCreateGame(player.getUniqueId()).whenComplete((game, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to open /lockbox for " + player.getName(), error);
            }
            if (game == null) {
                player.sendMessage(Msg.bad("Your lockbox couldn't be loaded right now - try again in a moment."));
                return;
            }
            Session session = new Session(game);
            sessions.put(player.getUniqueId(), session);
            render(player, session, true);
        }));
    }

    private static int boardSlot(int row, int position) {
        return row * 9 + BOARD_COLUMN + position;
    }

    private void render(Player player, Session session, boolean open) {
        LockboxGame game = session.game;
        boolean active = game.active();
        Menu menu = session.menu;
        for (int slot = 0; slot < SIZE; slot++) {
            menu.set(slot, null, null);
        }
        menu.set(HELP_SLOT, helpItem(), click -> sendFullRules(player));

        List<LockboxService.HistoryEntry> history = service.parseHistory(game.guessHistory());
        int shown = Math.min(history.size(), active ? ROWS - 1 : ROWS);
        int skipped = history.size() - shown;
        for (int row = 0; row < shown; row++) {
            LockboxService.HistoryEntry entry = history.get(skipped + row);
            List<LockboxService.Mark> marks = service.marks(game, entry.guess());
            for (int i = 0; i < entry.guess().size() && i < 3; i++) {
                menu.set(boardSlot(row, i), boardCell(skipped + row + 1, i, entry.guess().get(i), marks.get(i)), null);
            }
        }
        session.draftRow = active ? shown : -1;
        if (active) {
            for (int i = 0; i < 3; i++) {
                int position = i;
                menu.set(boardSlot(shown, i), guessSlotItem(i, session.draft[i], session.selectedPosition == i), click -> {
                    session.selectedPosition = position;
                    render(player, session, false);
                });
            }
            for (int i = 0; i < SYMBOL_ORDER.length && i < SYMBOL_SLOTS.length; i++) {
                LockboxSymbol symbol = SYMBOL_ORDER[i];
                menu.set(SYMBOL_SLOTS[i], symbolItem(symbol), click -> {
                    session.draft[session.selectedPosition] = symbol;
                    session.selectedPosition = (session.selectedPosition + 1) % 3;
                    render(player, session, false);
                });
            }
            menu.set(SUBMIT_SLOT, Lore.item(Material.LIME_DYE, Lore.Accent.GREEN.code() + "&lSubmit Guess",
                    LoreKit.builder(Lore.Accent.GREEN)
                            .description("Lock in your three symbols.", "Uses one attempt.")
                            .clickPrompt("Click to Submit")
                            .build()), click -> handleSubmit(player, session));
            menu.set(CLEAR_SLOT, Lore.item(Material.GRAY_DYE, Lore.Accent.RED.code() + "&lClear Guess",
                    LoreKit.builder(Lore.Accent.RED)
                            .description("Resets your current pick.", "Doesn't use an attempt.")
                            .clickPrompt("Click to Clear")
                            .build()), click -> {
                session.draft[0] = null;
                session.draft[1] = null;
                session.draft[2] = null;
                session.selectedPosition = 0;
                render(player, session, false);
            });
        }
        menu.set(STATUS_SLOT, statusItem(game), null);
        menu.set(CLOSE_SLOT, Lore.item(Material.BARRIER, Lore.Accent.RED.code() + "&lClose",
                LoreKit.builder(Lore.Accent.RED).clickPrompt("Click to Close").build()), click -> player.closeInventory());

        ItemStack filler = LoreKit.filler();
        for (int slot = 0; slot < SIZE; slot++) {
            if (menu.getInventory().getItem(slot) == null) {
                menu.set(slot, filler, null);
            }
        }
        if (open || player.getOpenInventory().getTopInventory().getHolder() != menu) {
            menu.open(player);
        }
    }

    private ItemStack helpItem() {
        LoreKit builder = LoreKit.builder(Lore.Accent.GREEN)
                .subcategory("How to Play")
                .divider()
                .bullet("Guess the secret 3-symbol code.")
                .bullet("Pick a spot on the board, then a symbol.")
                .divider()
                .bullet("&aGreen &7- right symbol, right spot.")
                .bullet("&eYellow &7- in the code, another spot.")
                .bullet("&8Gray &7- not in the code.")
                .divider()
                .detail(guessesText() + ". One lockbox every 24 hours.")
                .divider();
        addRewardLadder(builder);
        return Lore.item(Material.KNOWLEDGE_BOOK, Lore.Accent.GREEN.code() + "&lHow to Play",
                builder.divider().clickPrompt("Click for Full Rules").build());
    }

    private ItemStack guessSlotItem(int position, @Nullable LockboxSymbol drafted, boolean selected) {
        String label = "Spot " + (position + 1);
        String prefix = selected ? "&e&l" : Lore.Accent.CYAN.code() + "&l";
        if (drafted == null) {
            Material material = selected ? Material.YELLOW_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE;
            return Lore.item(material, prefix + label + ": &fempty", LoreKit.builder(Lore.Accent.CYAN)
                    .clickPrompt(selected ? "Click a Symbol Below" : "Click to Select").build());
        }
        ItemStack item = Lore.item(drafted.material(), prefix + label + ": &e" + drafted.displayName(),
                LoreKit.builder(Lore.Accent.CYAN).clickPrompt("Click to Select").build());
        return selected ? LoreKit.glint(item) : item;
    }

    private static ItemStack symbolItem(LockboxSymbol symbol) {
        return Lore.item(symbol.material(), Lore.Accent.WHITE.code() + "&l" + symbol.displayName(),
                LoreKit.builder(Lore.Accent.WHITE).clickPrompt("Click to Place").build());
    }

    private ItemStack statusItem(LockboxGame game) {
        int maxAttempts = service.settings().maxAttempts();
        int used = game.attemptsUsed();
        double fraction = maxAttempts <= 0 ? 0.0 : Math.min(1.0, used / (double) maxAttempts);
        if (LockboxGame.SOLVED.equals(game.status())) {
            LoreKit builder = LoreKit.builder(Lore.Accent.GREEN)
                    .subcategory("Daily Lockbox")
                    .divider()
                    .stat("Attempts Used", used + "/" + maxAttempts)
                    .progressBar(fraction)
                    .divider()
                    .bullet("Solved!");
            if (LockboxGame.REWARD_GRANTED.equals(game.rewardStatus())) {
                builder.stat("Reward", game.reward().display() + " (granted)");
            } else if (LockboxGame.REWARD_PENDING.equals(game.rewardStatus())) {
                builder.detail("Reward pending - try reopening in a moment.");
            } else {
                builder.detail("No reward earned this time.");
            }
            builder.divider()
                    .stat("Next Game", LoreKit.duration(service.cooldownRemainingMillis(game)))
                    .statusPrompt("Not Available Yet");
            return Lore.item(Material.LIME_CONCRETE, Lore.Accent.GREEN.code() + "&lLockbox Solved!", builder.build());
        }
        if (LockboxGame.FAILED.equals(game.status())) {
            LoreKit failed = LoreKit.builder(Lore.Accent.RED)
                    .subcategory("Daily Lockbox")
                    .divider()
                    .stat("Attempts Used", used + "/" + maxAttempts)
                    .progressBar(fraction)
                    .divider()
                    .bullet("Out of attempts.");
            if (LockboxGame.REWARD_GRANTED.equals(game.rewardStatus())) {
                failed.stat("Thanks for Playing", game.reward().display() + " (granted)");
            } else if (LockboxGame.REWARD_PENDING.equals(game.rewardStatus())) {
                failed.detail("Reward pending - try reopening in a moment.");
            }
            return Lore.item(Material.RED_CONCRETE, Lore.Accent.RED.code() + "&lLockbox Failed", failed
                    .divider()
                    .stat("Next Game", LoreKit.duration(service.cooldownRemainingMillis(game)))
                    .statusPrompt("Not Available Yet")
                    .build());
        }
        return Lore.item(Material.YELLOW_CONCRETE, Lore.Accent.GREEN.code() + "&lLockbox In Progress",
                LoreKit.builder(Lore.Accent.GREEN)
                        .subcategory("Daily Lockbox")
                        .divider()
                        .stat("Attempts Used", used + "/" + maxAttempts)
                        .progressBar(fraction)
                        .divider()
                        .bullet("Keep guessing!")
                        .build());
    }

    private void addRewardLadder(LoreKit builder) {
        String[] ordinals = {"1st", "2nd", "3rd", "4th"};
        LockboxSettings s = service.settings();
        for (int i = 1; i <= s.maxAttempts(); i++) {
            LockboxReward reward = s.rewardFor(true, i);
            if (!reward.isEmpty()) {
                builder.stat(ordinals[i - 1] + " Guess", reward.display());
            }
        }
        LockboxReward participation = s.rewardFor(false, s.maxAttempts());
        if (!participation.isEmpty()) {
            builder.stat("Not Cracked", participation.display());
        }
    }

    String guessesText() {
        int max = service.settings().maxAttempts();
        String[] words = {"One guess", "Two guesses", "Three guesses", "Four guesses"};
        return max >= 1 && max <= words.length ? words[max - 1] : max + " guesses";
    }

    private static ItemStack boardCell(int guessNumber, int position, LockboxSymbol symbol, LockboxService.Mark mark) {
        String color;
        String icon;
        String colorName;
        String meaning;
        switch (mark) {
            case CORRECT -> {
                color = "&a";
                icon = "✔ ";
                colorName = "Green";
                meaning = "Right symbol, right spot";
            }
            case PRESENT -> {
                color = "&e";
                icon = "◐ ";
                colorName = "Yellow";
                meaning = "In the code, wrong spot";
            }
            default -> {
                color = "&7";
                icon = "✗ ";
                colorName = "Grey";
                meaning = "Not in the code";
            }
        }
        return Lore.item(symbol.material(), color + "&l" + icon + symbol.displayName(), LoreKit.builder(Lore.Accent.WHITE)
                .subcategory("Guess " + guessNumber + " - Spot " + (position + 1))
                .divider()
                .stat("Selection", colorName, color)
                .detail(color + meaning)
                .build());
    }

    // ------------------------------------------------------------------ submitting

    private void handleSubmit(Player player, Session session) {
        if (session.submitting) {
            return; // checklist 3: ignore clicks while a guess is being recorded
        }
        LockboxSymbol[] draft = session.draft;
        if (draft[0] == null || draft[1] == null || draft[2] == null) {
            player.sendMessage(Msg.bad("Fill all three positions before submitting."));
            return;
        }
        List<LockboxSymbol> guess = List.of(draft[0], draft[1], draft[2]);
        if (new HashSet<>(guess).size() != 3) {
            player.sendMessage(Msg.bad("A guess can't use the same symbol twice."));
            return;
        }
        session.submitting = true;
        service.submitGuess(player.getUniqueId(), guess).whenComplete((outcome, error) -> Tasks.entity.run(player, () -> {
            session.submitting = false;
            if (error != null || outcome == null) {
                plugin.getLogger().log(Level.WARNING, "Lockbox guess submission failed for " + player.getName(), error);
                player.sendMessage(Msg.bad("That guess didn't go through - try again."));
                return;
            }
            handleOutcome(player, session, outcome);
        }));
    }

    private boolean stillOpen(Player player, Session session) {
        return sessions.get(player.getUniqueId()) == session
                && player.getOpenInventory().getTopInventory().getHolder() == session.menu;
    }

    private void handleOutcome(Player player, Session session, LockboxService.SubmitOutcome outcome) {
        boolean open = stillOpen(player, session);
        switch (outcome.type()) {
            case RATE_LIMITED -> player.sendMessage(Msg.neutral("Slow down a little before your next guess."));
            case STALE, NO_ACTIVE_GAME -> {
                player.sendMessage(Msg.bad("That guess didn't go through - reopening your lockbox."));
                load(player);
            }
            case ALREADY_FINISHED -> {
                if (outcome.game() != null) {
                    session.game = outcome.game();
                }
                if (open) {
                    render(player, session, false);
                }
            }
            case PROGRESS -> {
                session.game = outcome.game();
                session.draft[0] = null;
                session.draft[1] = null;
                session.draft[2] = null;
                session.selectedPosition = 0;
                player.sendMessage(legacy(guessLine(outcome.game())));
                if (open) {
                    render(player, session, false);
                }
            }
            case SOLVED -> {
                session.game = outcome.game();
                if (open) {
                    render(player, session, false);
                }
                celebrate(player, session);
            }
            case FAILED -> {
                session.game = outcome.game();
                player.sendMessage(Msg.bad("Out of attempts - a new lockbox opens in 24 hours."));
                String reward = outcome.game().rewardStatus();
                if (LockboxGame.REWARD_GRANTED.equals(reward) || LockboxGame.REWARD_PENDING.equals(reward)) {
                    player.sendMessage(Msg.good("Thanks for playing! You get " + Msg.white(outcome.game().reward().display()) + "."));
                }
                if (open) {
                    render(player, session, false);
                }
            }
            case INVALID_GUESS -> player.sendMessage(Msg.bad("That guess isn't valid."));
        }
    }

    private void celebrate(Player player, Session session) {
        LockboxGame game = session.game;
        int guesses = game.attemptsUsed();
        String guessText = guesses + " guess" + (guesses == 1 ? "" : "es");
        String rewardStatus = game.rewardStatus();
        String reward = game.reward().display();
        player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1.0f, 1.0f);
        if (LockboxGame.REWARD_GRANTED.equals(rewardStatus)) {
            player.sendMessage(Msg.good("You cracked the Daily Lockbox in <white>" + guessText + "</white> and won <white>" + reward + "</white>!"));
        } else if (LockboxGame.REWARD_PENDING.equals(rewardStatus)) {
            player.sendMessage(Msg.good("You cracked the Daily Lockbox in <white>" + guessText + "</white>! Your <white>" + reward + "</white> is on the way."));
        } else {
            player.sendMessage(Msg.good("You cracked the Daily Lockbox in <white>" + guessText + "</white>!"));
        }
        // Effects only (the reward was settled before this). Runs on the player's thread; skipped if they left.
        Tasks.entity.later(player, () -> {
            if (player.getOpenInventory().getTopInventory().getHolder() == session.menu) {
                player.closeInventory();
            }
            String subtitle = LockboxGame.REWARD_GRANTED.equals(rewardStatus) || LockboxGame.REWARD_PENDING.equals(rewardStatus)
                    ? "&7Cracked in &f" + guessText + " &8· &6+" + reward
                    : "&7Cracked in &f" + guessText;
            player.showTitle(Title.title(legacy("&a&l✔ UNLOCKED!"), legacy(subtitle),
                    Title.Times.times(Duration.ofMillis(200L), Duration.ofMillis(2500L), Duration.ofMillis(600L))));
            Location burst = player.getLocation().add(0.0, 2.2, 0.0);
            player.getWorld().spawnParticle(Particle.FIREWORK, burst, 50, 0.1, 0.1, 0.1, 0.18);
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0.0, 1.2, 0.0), 30, 0.4, 0.6, 0.4, 0.15);
            player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_BLAST, 0.9f, 1.0f);
            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.3f);
            Tasks.entity.later(player, () -> player.playSound(player.getLocation(), Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, 0.8f, 1.0f), 8L);
        }, 30L);
    }

    private String guessLine(LockboxGame game) {
        List<LockboxService.HistoryEntry> history = service.parseHistory(game.guessHistory());
        if (history.isEmpty()) {
            return "&7Guess submitted.";
        }
        List<LockboxSymbol> guess = history.get(history.size() - 1).guess();
        List<LockboxService.Mark> marks = service.marks(game, guess);
        StringBuilder line = new StringBuilder("&7Guess " + history.size() + ": ");
        for (int i = 0; i < guess.size(); i++) {
            if (i > 0) {
                line.append(" &8· ");
            }
            line.append(switch (marks.get(i)) {
                case CORRECT -> "&a✔ ";
                case PRESENT -> "&e◐ ";
                case ABSENT -> "&8✗ ";
            }).append(guess.get(i).displayName());
        }
        return line.toString();
    }

    public void sendFullRules(Player player) {
        List<String> lines = new ArrayList<>();
        lines.add("&8&m--------&r &eDaily Lockbox Rules &8&m--------");
        // BlockvilleCore said "6 possible symbols"; there are eight, so the count is taken from the symbol list.
        lines.add("&7Guess the secret 3-symbol combination, drawn from " + LockboxSymbol.values().length + " possible symbols.");
        lines.add("&7Click a spot in the board's open row, then click a symbol to fill it.");
        lines.add("&7Click Submit to lock in a full guess - that's when an attempt is used.");
        lines.add("&aGreen &7= right symbol, right spot.");
        lines.add("&eYellow &7= right symbol, somewhere else in the code.");
        lines.add("&8Gray &7= a symbol that isn't in the combination at all.");
        lines.add("&7" + guessesText() + ". Crack it in fewer guesses for a bigger reward - one lockbox every 24 hours.");
        for (String line : lines) {
            player.sendMessage(legacy(line));
        }
    }

    static Component legacy(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }

    // ------------------------------------------------------------------ cleanup

    @EventHandler(priority = EventPriority.MONITOR)
    public void onClose(InventoryCloseEvent event) {
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null && event.getInventory().getHolder() == session.menu) {
            sessions.remove(event.getPlayer().getUniqueId(), session);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        sessions.remove(event.getPlayer().getUniqueId());
    }

    void clear() {
        sessions.clear();
    }
}
