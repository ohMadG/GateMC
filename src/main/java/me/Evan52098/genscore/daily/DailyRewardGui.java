package me.Evan52098.genscore.daily;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.crates.LoreKit;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;

/**
 * The Daily Strike menu, ported from BlockvilleCore's DailyRewardGui: five layers of sealed ground; clicking digs down
 * layer by layer until the pickaxe strikes the layer that was rolled.
 *
 * <p>The reward is decided and recorded by {@link DailyRewardService#claim} before the animation starts, and given
 * independently of it, so closing the menu, quitting, dying or a server stop mid-animation changes nothing about what
 * the player gets. The animation is cosmetic: it ticks on the player's thread ({@code Tasks.entity.timer}) and stops as
 * soon as the menu is no longer open, the player is dead, or they left (the entity scheduler retires the task).
 */
public final class DailyRewardGui {

    private static final int ROWS = 6;
    private static final int[] ROW_START_SLOT = {9, 18, 27, 36, 45};
    private static final int CLOCK_SLOT = 4;
    private static final int BACK_SLOT = 6;
    private static final int CLOSE_SLOT = 8;
    private static final Material SEALED_MATERIAL = Material.DEEPSLATE;
    private static final Material PASSED_MATERIAL = Material.BLACK_STAINED_GLASS_PANE;
    private static final Material DIG_MATERIAL = Material.COBBLESTONE;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final GensCore plugin;
    private final DailyRewardService service;
    private volatile @Nullable DailyHubGui hub;

    DailyRewardGui(GensCore plugin, DailyRewardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void setHub(@Nullable DailyHubGui hub) {
        this.hub = hub;
    }

    private static Menu newMenu() {
        Menu menu = new Menu(ROWS, LEGACY.deserialize("&8The Daily Strike"));
        ItemStack filler = LoreKit.filler();
        for (int slot = 0; slot < ROWS * 9; slot++) {
            menu.set(slot, filler);
        }
        return menu;
    }

    /** Call on the player's thread. */
    public void open(Player player) {
        DailyRewardService.EligibilityInfo info = service.peek(player.getUniqueId());
        if (!info.ready()) {
            player.sendMessage(Msg.neutral("Your profile is still loading - try again in a moment."));
            return;
        }
        if (!service.config().enabled()) {
            player.sendMessage(Msg.bad("Daily rewards are turned off right now."));
            return;
        }
        if (!info.eligible()) {
            cooldownMenu(player, info);
        } else {
            readyMenu(player, info);
        }
    }

    private void cooldownMenu(Player player, DailyRewardService.EligibilityInfo info) {
        Menu menu = newMenu();
        LoreKit clockLore = LoreKit.builder(Lore.Accent.RED)
                .subcategory("Daily Strike")
                .divider()
                .stat("Next Dig", LoreKit.duration(info.remainingMillis()))
                .stat("Current Streak", days(info.currentStreak()))
                .stat("Longest Streak", days(info.longestStreak()));
        milestoneProgress(clockLore, info.currentStreak());
        clockLore.statusPrompt("Not Available Yet");
        menu.set(CLOCK_SLOT, Lore.item(Material.CLOCK, Lore.Accent.RED.code() + "&lAlready Struck Today!", clockLore.build()));
        addHubButton(menu, player);
        for (int depth = 0; depth < 5; depth++) {
            setRow(menu, depth, lockedLayer(depth), null);
        }
        addCloseButton(menu);
        menu.open(player);
    }

    private void readyMenu(Player player, DailyRewardService.EligibilityInfo info) {
        Menu menu = newMenu();
        LoreKit pickaxeLore = LoreKit.builder(Lore.Accent.GREEN)
                .subcategory("Daily Strike")
                .divider()
                .stat("Current Streak", days(info.currentStreak()))
                .stat("Longest Streak", days(info.longestStreak()))
                .stat("Total Digs", String.valueOf(info.totalClaims()));
        milestoneProgress(pickaxeLore, info.currentStreak());
        pickaxeLore.clickPrompt("Click to Dig");
        menu.set(CLOCK_SLOT, pickaxeFor(info.currentStreak(), Lore.Accent.GREEN.code() + "&lClick Anywhere Below to Dig!",
                pickaxeLore.build()), click -> startDig(player));
        addHubButton(menu, player);
        ItemStack sealed = Lore.item(SEALED_MATERIAL, Lore.Accent.WHITE.code() + "&lSealed Ground",
                LoreKit.builder(Lore.Accent.WHITE)
                        .subcategory("Daily Strike")
                        .divider()
                        .description("A mystery reward is buried", "somewhere in this layer.")
                        .clickPrompt("Click to Start Digging")
                        .build());
        for (int depth = 0; depth < 5; depth++) {
            setRow(menu, depth, sealed, () -> startDig(player));
        }
        menu.open(player);
    }

    private void startDig(Player player) {
        if (service.isClaiming(player.getUniqueId())) {
            return; // checklist 3: a claim is already being written
        }
        service.claim(player.getUniqueId(), player.getName()).whenComplete((result, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                plugin.getLogger().log(Level.WARNING, "Daily Strike failed for " + player.getName(), error);
                player.sendMessage(Msg.bad("Something went wrong with your dig - try again in a moment."));
                return;
            }
            if (!result.success()) {
                open(player); // already claimed today (another click, another session): show the cooldown
                return;
            }
            new Dig(player, result).start();
        }));
    }

    /** One digging animation. All of it runs on the player's thread. */
    private final class Dig {
        private final Player player;
        private final DailyRewardService.ClaimResult result;
        private final Menu menu = newMenu();
        private int depth;
        private int wait;
        private boolean digging; // false: about to start digging `depth`; true: digging it, waiting to see what's there
        private boolean finished;

        Dig(Player player, DailyRewardService.ClaimResult result) {
            this.player = player;
            this.result = result;
        }

        void start() {
            menu.set(CLOCK_SLOT, pickaxeFor(result.newStreak(), Lore.Accent.WHITE.code() + "&lDigging...",
                    LoreKit.builder(Lore.Accent.WHITE).subcategory("Daily Strike").divider().description("Hold on tight!").build()));
            addHubButton(menu, player);
            for (int d = 0; d < 5; d++) {
                setRow(menu, d, Lore.item(SEALED_MATERIAL, "&8&lSealed Ground", List.of()), null);
            }
            menu.open(player);
            ScheduledTask task = Tasks.entity.timer(player, this::tick, 1L, 1L);
            if (task == null) {
                finishEarly();
            }
        }

        private void tick(ScheduledTask task) {
            if (finished) {
                task.cancel();
                return;
            }
            if (!player.isOnline() || player.isDead() || player.getOpenInventory().getTopInventory().getHolder() != menu) {
                task.cancel();
                finishEarly();
                return;
            }
            if (wait > 0) {
                wait--;
                return;
            }
            if (!digging) {
                setRow(menu, depth, Lore.item(DIG_MATERIAL, "&7&lDigging...", List.of()), null);
                player.playSound(player.getLocation(), Sound.BLOCK_STONE_BREAK, 1.0f, 0.8f + depth * 0.1f);
                digging = true;
                wait = 12 + depth * 5;
                return;
            }
            if (depth >= result.tierDepthIndex() || depth >= 4) {
                task.cancel();
                finished = true;
                reveal(player, menu, result);
                return;
            }
            setRow(menu, depth, Lore.item(PASSED_MATERIAL, "&8&lEmpty tunnel", List.of()), null);
            depth++;
            digging = false;
            wait = 8;
        }

        /** The menu was closed (or the player died) before the strike was shown: tell them what they got. */
        private void finishEarly() {
            if (finished) {
                return;
            }
            finished = true;
            if (player.isOnline()) {
                announce(player, result, true);
            }
        }
    }

    private void reveal(Player player, Menu menu, DailyRewardService.ClaimResult result) {
        DailyRewardConfig.DailyRewardTier tier = result.tier();
        LoreKit strikeLore = LoreKit.builder(Lore.Accent.GREEN)
                .subcategory("Daily Strike")
                .divider()
                .description("You struck " + tier.displayName() + "!")
                .divider()
                .stat("Prize", tier.rewardText().isBlank() ? "A reward" : tier.rewardText())
                .stat("New Streak", days(result.newStreak()));
        if (result.milestoneHit() != null) {
            strikeLore.bullet("&d&lMILESTONE: &f" + result.milestoneHit().displayName());
        }
        ItemStack strikeItem = LoreKit.glint(Lore.item(tier.oreBlock(), Lore.Accent.GREEN.code() + "&l" + tier.displayName(), strikeLore.build()));
        setRow(menu, result.tierDepthIndex(), strikeItem, null);
        LoreKit pickaxeLore = LoreKit.builder(Lore.Accent.GREEN)
                .subcategory("Daily Strike")
                .divider()
                .stat("New Streak", days(result.newStreak()));
        milestoneProgress(pickaxeLore, result.newStreak());
        menu.set(CLOCK_SLOT, pickaxeFor(result.newStreak(), Lore.Accent.GREEN.code() + "&lStruck It!", pickaxeLore.build()));
        addHubButton(menu, player);
        addCloseButton(menu);

        player.getWorld().spawnParticle(Particle.BLOCK, player.getLocation().add(0.0, 1.0, 0.0), 60, 0.5, 0.5, 0.5,
                tier.oreBlock().isBlock() ? tier.oreBlock().createBlockData() : Material.STONE.createBlockData());
        boolean big = tier.broadcast();
        player.playSound(player.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
        if (big) {
            player.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, player.getLocation().add(0.0, 1.2, 0.0), 40, 0.4, 0.6, 0.4, 0.02);
        }
        announce(player, result, false);
        player.showTitle(Title.title(LEGACY.deserialize("&f&lSTRUCK " + tier.displayName().toUpperCase() + "!"),
                LEGACY.deserialize("&7New streak: &6" + days(result.newStreak())),
                Title.Times.times(Duration.ofMillis(200L), Duration.ofSeconds(2L), Duration.ofMillis(500L))));
    }

    /** The chat line and, for big tiers, the server broadcast. Sent once per claim, from reveal or an early stop. */
    private void announce(Player player, DailyRewardService.ClaimResult result, boolean early) {
        DailyRewardConfig.DailyRewardTier tier = result.tier();
        String plainName = tier.displayName().replaceAll("(?i)&[0-9a-fk-or]", "");
        if (!tier.rewardText().isBlank()) {
            player.sendMessage(Msg.good("You struck <white>" + Msg.escape(plainName) + "</white> in the Daily Strike: <white>"
                    + Msg.escape(tier.rewardText()) + "</white>!"));
        } else if (early) {
            player.sendMessage(Msg.good("You struck <white>" + Msg.escape(plainName) + "</white> in the Daily Strike!"));
        }
        if (tier.broadcast()) {
            Component line = LEGACY.deserialize("&6[Daily Strike] &f" + player.getName() + " &6struck " + tier.displayName() + " &6on their daily dig!");
            for (Player online : Bukkit.getOnlinePlayers()) {
                Tasks.entity.run(online, () -> online.sendMessage(line));
            }
            Bukkit.getConsoleSender().sendMessage(line);
        }
    }

    private void addHubButton(Menu menu, Player player) {
        DailyHubGui hubGui = hub;
        if (hubGui == null) {
            return;
        }
        menu.set(BACK_SLOT, Lore.item(Material.ARROW, Lore.Accent.RED.code() + "&l✗ Back",
                LoreKit.builder(Lore.Accent.RED)
                        .divider()
                        .description("Return to the daily rewards menu.")
                        .clickPrompt("Click to Go Back")
                        .build()), click -> hubGui.open(player));
    }

    private static void addCloseButton(Menu menu) {
        menu.set(CLOSE_SLOT, Lore.item(Material.BARRIER, "&c&lCLOSE", List.of()), click -> click.getWhoClicked().closeInventory());
    }

    private static ItemStack pickaxeFor(int streak, String name, List<String> lore) {
        Material material;
        if (streak >= 30) {
            material = Material.NETHERITE_PICKAXE;
        } else if (streak >= 7) {
            material = Material.DIAMOND_PICKAXE;
        } else if (streak >= 3) {
            material = Material.IRON_PICKAXE;
        } else if (streak >= 1) {
            material = Material.STONE_PICKAXE;
        } else {
            material = Material.WOODEN_PICKAXE;
        }
        return Lore.item(material, name, lore);
    }

    private void milestoneProgress(LoreKit lore, int streak) {
        DailyRewardConfig.DailyRewardMilestone next = null;
        for (DailyRewardConfig.DailyRewardMilestone milestone : service.config().milestones()) {
            if (milestone.streakLength() > streak) {
                next = milestone;
                break;
            }
        }
        if (next == null) {
            return;
        }
        lore.detail("")
                .detail("&7Next milestone: &f" + next.displayName() + "&7 (" + next.streakLength() + "d)")
                .progressBar(streak / (double) next.streakLength());
    }

    private static ItemStack lockedLayer(int depth) {
        return Lore.item(SEALED_MATERIAL, Lore.Accent.RED.code() + "&lSealed Ground",
                LoreKit.builder(Lore.Accent.RED)
                        .divider()
                        .description("Depth " + (depth + 1) + " - come back later.")
                        .divider()
                        .statusPrompt("Not Available Yet")
                        .build());
    }

    private static void setRow(Menu menu, int depth, ItemStack item, @Nullable Runnable onClick) {
        int start = ROW_START_SLOT[Math.max(0, Math.min(4, depth))];
        for (int i = 0; i < 9; i++) {
            menu.set(start + i, item, onClick == null ? null : click -> onClick.run());
        }
    }

    static String days(long count) {
        return count + " day" + (count == 1 ? "" : "s");
    }
}
