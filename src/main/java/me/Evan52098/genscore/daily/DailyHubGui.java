package me.Evan52098.genscore.daily;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.crates.CratesModule;
import me.Evan52098.genscore.crates.LockboxGame;
import me.Evan52098.genscore.crates.LockboxService;
import me.Evan52098.genscore.crates.LoreKit;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

/**
 * /daily: the Daily Rewards menu with the Daily Strike and the Daily Lockbox, ported from BlockvilleCore's DailyHubGui.
 * The lockbox button needs the crates module; with it off the button shows "Unavailable Right Now".
 */
public final class DailyHubGui {

    private static final int ROWS = 4;
    private static final int STRIKE_SLOT = 2 + 9;
    private static final int LOCKBOX_SLOT = 6 + 9;
    private static final int CLOSE_SLOT = 4 + 3 * 9;

    private final GensCore plugin;
    private final DailyRewardService service;
    private final DailyRewardGui strikeGui;

    DailyHubGui(GensCore plugin, DailyRewardService service, DailyRewardGui strikeGui) {
        this.plugin = plugin;
        this.service = service;
        this.strikeGui = strikeGui;
    }

    /** Call on the player's thread. */
    public void open(Player player) {
        CratesModule crates = plugin.modules().get(CratesModule.class);
        if (crates == null) {
            show(player, null, null, true);
            return;
        }
        LockboxService lockbox = crates.lockbox();
        CompletableFuture<LockboxGame> latest = lockbox.fetchLatestGame(player.getUniqueId());
        latest.whenComplete((game, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Daily menu couldn't load the lockbox state for " + player.getName(), error);
            }
            show(player, crates, error != null ? null : game, error != null);
        }));
    }

    private void show(Player player, @Nullable CratesModule crates, @Nullable LockboxGame lockboxGame, boolean lockboxUnavailable) {
        Menu menu = new Menu(ROWS, LegacyComponentSerializer.legacyAmpersand().deserialize("&8Daily Rewards"));
        ItemStack filler = LoreKit.filler();
        for (int slot = 0; slot < ROWS * 9; slot++) {
            menu.set(slot, filler);
        }
        menu.set(STRIKE_SLOT, strikeDye(player), click -> strikeGui.open(player));
        boolean canPlay = crates != null && !lockboxUnavailable;
        menu.set(LOCKBOX_SLOT, lockboxDye(crates, lockboxGame, !canPlay), canPlay ? click -> crates.gui().open(player) : null);
        menu.set(CLOSE_SLOT, Lore.item(Material.BARRIER, Lore.Accent.RED.code() + "&lClose",
                LoreKit.builder(Lore.Accent.RED)
                        .divider()
                        .description("Close this menu.")
                        .clickPrompt("Click to Close")
                        .build()), click -> player.closeInventory());
        menu.open(player);
    }

    private ItemStack strikeDye(Player player) {
        DailyRewardService.EligibilityInfo info = service.peek(player.getUniqueId());
        String[] description = {"Dig down through five layers of rock -", "the deeper you strike, the better the reward."};
        String name = "&lThe Daily Strike";
        if (!info.ready()) {
            return Lore.item(Material.GRAY_DYE, Lore.Accent.WHITE.code() + name, LoreKit.builder(Lore.Accent.WHITE)
                    .subcategory("Daily Game")
                    .divider()
                    .description(description)
                    .statusPrompt("Profile Loading")
                    .build());
        }
        if (info.eligible()) {
            return Lore.item(Material.LIME_DYE, Lore.Accent.GREEN.code() + name, LoreKit.builder(Lore.Accent.GREEN)
                    .subcategory("Daily Game")
                    .divider()
                    .description(description)
                    .divider()
                    .stat("Current Streak", DailyRewardGui.days(info.currentStreak()))
                    .stat("Longest Streak", DailyRewardGui.days(info.longestStreak()))
                    .clickPrompt("Click to Dig")
                    .build());
        }
        return Lore.item(Material.GRAY_DYE, Lore.Accent.RED.code() + name, LoreKit.builder(Lore.Accent.RED)
                .subcategory("Daily Game")
                .divider()
                .description(description)
                .divider()
                .stat("Next Dig", LoreKit.duration(info.remainingMillis()))
                .stat("Current Streak", DailyRewardGui.days(info.currentStreak()))
                .clickPrompt("Click to View")
                .build());
    }

    private ItemStack lockboxDye(@Nullable CratesModule crates, @Nullable LockboxGame game, boolean unavailable) {
        int maxAttempts = crates == null ? 4 : crates.lockbox().maxAttempts();
        // BlockvilleCore said "in 6 tries"; the number now follows the configured attempts.
        String[] description = {"A puzzle - guess the 3-symbol", "combination in " + maxAttempts + " tries!"};
        String name = "&lThe Daily Lockbox";
        if (unavailable || crates == null) {
            return Lore.item(Material.GRAY_DYE, Lore.Accent.WHITE.code() + name, LoreKit.builder(Lore.Accent.WHITE)
                    .subcategory("Daily Game")
                    .divider()
                    .description(description)
                    .statusPrompt("Unavailable Right Now")
                    .build());
        }
        if (game != null && game.active()) {
            return Lore.item(Material.LIME_DYE, Lore.Accent.GREEN.code() + name, LoreKit.builder(Lore.Accent.GREEN)
                    .subcategory("Daily Game")
                    .divider()
                    .description(description)
                    .divider()
                    .stat("Attempts Used", game.attemptsUsed() + "/" + maxAttempts)
                    .clickPrompt("Click to Continue")
                    .build());
        }
        long remaining = crates.lockbox().cooldownRemainingMillis(game);
        if (game == null || remaining <= 0L) {
            return Lore.item(Material.LIME_DYE, Lore.Accent.GREEN.code() + name, LoreKit.builder(Lore.Accent.GREEN)
                    .subcategory("Daily Game")
                    .divider()
                    .description(description)
                    .clickPrompt("Click to Play")
                    .build());
        }
        boolean solved = LockboxGame.SOLVED.equals(game.status());
        return Lore.item(Material.GRAY_DYE, Lore.Accent.RED.code() + name, LoreKit.builder(Lore.Accent.RED)
                .subcategory("Daily Game")
                .divider()
                .description(description)
                .divider()
                .stat("Last Game", solved ? "Solved" : "Failed")
                .stat("Next Game", LoreKit.duration(remaining))
                .clickPrompt("Click to View")
                .build());
    }
}
