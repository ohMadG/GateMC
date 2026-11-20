package me.Evan52098.genscore.seasonpass;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.rewardbox.Reward;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** /pass (tiers, free row on top, premium row below) and /pass challenges. Every click is cancelled ({@link Menu}). */
final class PassMenus {

    private static final int PER_PAGE = 9;

    private final PassService service;
    private final Cooldowns cooldowns = new Cooldowns();

    PassMenus(PassService service) {
        this.service = service;
    }

    void openPass(Player player, int requestedPage) {
        PassConfig cfg = service.config();
        PassPlayer pp = service.player(player.getUniqueId());
        if (pp == null || !pp.isLoaded()) {
            player.sendMessage(Msg.bad("Your season pass is still loading. Try again in a moment."));
            return;
        }
        long xp = pp.xp();
        int tier = cfg.tierFor(xp);
        int pages = (cfg.tierCount() + PER_PAGE - 1) / PER_PAGE;
        int page = requestedPage < 0 ? Math.min(pages - 1, Math.max(0, tier) / PER_PAGE) : Math.max(0, Math.min(pages - 1, requestedPage));
        boolean premium = player.hasPermission(SeasonPassModule.PREMIUM);

        Menu menu = new Menu(6, Msg.parse("<dark_gray>" + Msg.escape(cfg.seasonName()) + " Pass <gray>(" + (page + 1) + "/" + pages + ")"));
        menu.set(4, info(cfg, xp, tier, premium));
        for (int i = 0; i < PER_PAGE; i++) {
            int n = page * PER_PAGE + i + 1;
            if (n > cfg.tierCount()) {
                break;
            }
            final int tierNumber = n;
            PassConfig.Tier t = cfg.tier(n);
            boolean reached = xp >= cfg.xpForTier(n);
            menu.set(9 + i, rewardIcon(t, PassConfig.Track.FREE, n, reached, pp.hasClaimed(PassConfig.Track.FREE, n), true),
                    click -> claim(player, PassConfig.Track.FREE, tierNumber));
            menu.set(18 + i, marker(cfg, n, xp));
            menu.set(27 + i, rewardIcon(t, PassConfig.Track.PREMIUM, n, reached, pp.hasClaimed(PassConfig.Track.PREMIUM, n), premium),
                    click -> claim(player, PassConfig.Track.PREMIUM, tierNumber));
        }
        if (page > 0) {
            menu.set(45, Lore.item(Material.ARROW, "&fPrevious page", List.of()), click -> openPass(player, page - 1));
        }
        if (page < pages - 1) {
            menu.set(53, Lore.item(Material.ARROW, "&fNext page", List.of()), click -> openPass(player, page + 1));
        }
        menu.set(48, Lore.item(Material.WRITABLE_BOOK, "&eChallenges", Lore.builder(Lore.Accent.WHITE)
                .description("Daily and weekly challenges", "give pass XP.").clickPrompt("Click to view").build()),
                click -> openChallenges(player));
        menu.set(50, Lore.item(Material.CHEST, "&6Reward box", Lore.builder(Lore.Accent.WHITE)
                .description("Rewards that did not fit", "in your inventory wait here.").clickPrompt("Click to collect").build()),
                click -> collect(player));
        menu.open(player);
    }

    private ItemStack info(PassConfig cfg, long xp, int tier, boolean premium) {
        Lore lore = Lore.builder(Lore.Accent.CYAN).subcategory("Season pass")
                .stat("Tier", tier + " / " + cfg.tierCount())
                .stat("XP", xp + (tier < cfg.tierCount() ? " / " + cfg.xpForTier(tier + 1) : ""))
                .stat("Premium", premium ? "&aYes" : "&cNo");
        Instant now = Instant.now();
        if (cfg.active(now)) {
            lore.stat("Ends in", Cooldowns.format(Duration.between(now, cfg.end()).toMillis()));
        } else {
            lore.divider().description("&cThe season is not running right now.");
        }
        lore.divider().description("Complete challenges for XP.", "Each tier has a free reward,", "and a premium one.");
        return Lore.item(Material.NETHER_STAR, "&b&l" + cfg.seasonName(), lore.build());
    }

    private ItemStack marker(PassConfig cfg, int n, long xp) {
        boolean reached = xp >= cfg.xpForTier(n);
        Material m = reached ? Material.LIME_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
        ItemStack item = Lore.item(m, (reached ? "&a" : "&7") + "Tier " + n, Lore.builder(Lore.Accent.WHITE)
                .stat("Needs", cfg.xpForTier(n) + " XP").build());
        item.setAmount(Math.max(1, Math.min(64, n)));
        return item;
    }

    private ItemStack rewardIcon(PassConfig.Tier t, PassConfig.Track track, int n, boolean reached, boolean claimed, boolean allowed) {
        List<Reward> rewards = t == null ? List.of() : (track == PassConfig.Track.FREE ? t.free() : t.premium());
        String label = (track == PassConfig.Track.FREE ? "&fFree" : "&6Premium") + " &7- Tier " + n;
        if (rewards.isEmpty()) {
            return Lore.item(Material.BLACK_STAINED_GLASS_PANE, label, List.of("&8No reward on this tier."));
        }
        List<String> lines = new ArrayList<>();
        lines.add("&8" + Lore.smallCaps("rewards"));
        for (Reward r : rewards) {
            lines.add("&7• " + r.display());
        }
        lines.add("");
        Material icon;
        if (claimed) {
            icon = Material.MINECART;
            lines.add("&aClaimed.");
        } else if (!allowed) {
            icon = Material.IRON_BARS;
            lines.add("&cNeeds the premium pass.");
        } else if (!reached) {
            icon = t != null && t.icon() != null ? t.icon() : Material.CHEST;
            lines.add("&7Reach tier " + n + " to claim.");
        } else {
            icon = Material.CHEST_MINECART;
            lines.add("&e" + Lore.smallCaps("click to claim"));
        }
        ItemStack item = Lore.item(icon, label, lines);
        if (reached && allowed && !claimed) {
            ItemMeta meta = item.getItemMeta();
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void claim(Player player, PassConfig.Track track, int tier) {
        if (!cooldowns.tryStart(player.getUniqueId(), "pass-claim", 400L)) {
            return; // checklist 3, together with the in-flight flag in PassService
        }
        service.claim(player, track, tier).thenAccept(result -> Tasks.entity.run(player, () -> {
            switch (result) {
                case OK -> player.sendMessage(Msg.good("Claimed the " + (track == PassConfig.Track.FREE ? "free" : "premium")
                        + " reward of " + Msg.white("tier " + tier) + "."));
                case ALREADY -> player.sendMessage(Msg.bad("You already claimed that reward."));
                case LOCKED -> player.sendMessage(Msg.bad("Reach tier " + tier + " first."));
                case NOT_PREMIUM -> player.sendMessage(Msg.bad("That reward is for premium pass holders."));
                case NOT_ACTIVE -> player.sendMessage(Msg.bad("The season is not running right now."));
                case NOTHING -> {
                    return;
                }
                case BUSY -> {
                    return;
                }
                default -> player.sendMessage(Msg.bad("Something went wrong. Please try again."));
            }
            if (player.getOpenInventory().getTopInventory().getHolder() instanceof Menu) {
                openPass(player, (tier - 1) / PER_PAGE);
            }
        }));
    }

    void collect(Player player) {
        if (!cooldowns.tryStart(player.getUniqueId(), "pass-box", 3000L)) {
            player.sendMessage(Msg.bad("Please wait a moment."));
            return;
        }
        service.box().waiting(player.getUniqueId()).thenAccept(n -> Tasks.entity.run(player, () -> {
            if (n == 0) {
                player.sendMessage(Msg.neutral("Your pass reward box is empty."));
            } else {
                player.sendMessage(Msg.neutral("Collecting " + Msg.white(String.valueOf(n)) + " waiting reward(s)..."));
                service.box().deliver(player.getUniqueId());
            }
        }));
    }

    void openChallenges(Player player) {
        PassConfig cfg = service.config();
        PassPlayer pp = service.player(player.getUniqueId());
        if (pp == null || !pp.isLoaded()) {
            player.sendMessage(Msg.bad("Your season pass is still loading. Try again in a moment."));
            return;
        }
        Instant now = Instant.now();
        Menu menu = new Menu(4, Msg.parse("<dark_gray>Pass Challenges"));
        boolean active = cfg.active(now);
        String daily = cfg.dailyKey(now);
        String weekly = cfg.weeklyKey(now);
        menu.set(0, Lore.item(Material.SUNFLOWER, "&eDaily", Lore.builder(Lore.Accent.WHITE)
                .stat("New in", Cooldowns.format(untilNextDay(cfg, now))).build()));
        menu.set(9, Lore.item(Material.CLOCK, "&dWeekly", Lore.builder(Lore.Accent.WHITE)
                .stat("New in", Cooldowns.format(untilNextWeek(cfg, now))).build()));
        int slot = 1;
        for (PassConfig.Challenge ch : cfg.todaysDaily(now)) {
            if (slot > 8) {
                break;
            }
            menu.set(slot++, challengeIcon(ch, pp.progress(daily + "|" + ch.id()), active));
        }
        slot = 10;
        for (PassConfig.Challenge ch : cfg.thisWeeksWeekly(now)) {
            if (slot > 17) {
                break;
            }
            menu.set(slot++, challengeIcon(ch, pp.progress(weekly + "|" + ch.id()), active));
        }
        menu.set(31, Lore.item(Material.ARROW, "&fBack to the pass", List.of()), click -> openPass(player, -1));
        menu.open(player);
    }

    private static ItemStack challengeIcon(PassConfig.Challenge ch, PassPlayer.Progress p, boolean active) {
        Lore lore = Lore.builder(p.done ? Lore.Accent.GREEN : Lore.Accent.CYAN)
                .stat("Progress", p.value + " / " + ch.amount())
                .stat("Reward", ch.xp() + " pass XP");
        if (!ch.filter().isEmpty()) {
            lore.stat("Counts", String.join(", ", ch.filter().stream().map(s -> s.toLowerCase().replace('_', ' ')).toList()));
        }
        lore.divider().description(p.done ? "&aComplete!" : active ? "In progress." : "&cThe season is not running.");
        ItemStack item = Lore.item(p.done ? Material.LIME_DYE : ch.icon(), (p.done ? "&a" : "&f") + ch.name(), lore.build());
        return item;
    }

    private static long untilNextDay(PassConfig cfg, Instant now) {
        var next = now.atZone(cfg.zone()).toLocalDate().plusDays(1).atStartOfDay(cfg.zone()).toInstant();
        return Duration.between(now, next).toMillis();
    }

    private static long untilNextWeek(PassConfig cfg, Instant now) {
        var date = now.atZone(cfg.zone()).toLocalDate();
        int daysToMonday = 8 - date.getDayOfWeek().getValue();
        var next = date.plusDays(daysToMonday).atStartOfDay(cfg.zone()).toInstant();
        return Duration.between(now, next).toMillis();
    }
}
