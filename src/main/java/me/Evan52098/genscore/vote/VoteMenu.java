package me.Evan52098.genscore.vote;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The /vote menu, laid out like BlockvilleCore's VoteGui: the player's record on their head at the top, up to ten voting
 * sites in the middle (glowing until voted, then a countdown to the next vote), and the reward, streak and vote party
 * tiles at the bottom. Opened on the player's thread.
 */
final class VoteMenu {

    private static final int ROWS = 5;
    private static final String GOLD = "&e";

    private final VoteModule module;

    VoteMenu(VoteModule module) {
        this.module = module;
    }

    void open(Player player) {
        VoteStats.Status status = module.status(player);
        if (status == null) {
            player.sendMessage(Msg.neutral("Your voting record is still loading - try again in a moment."));
            return;
        }
        VoteConfig config = module.config();
        Menu menu = new Menu(ROWS, Msg.parse("<dark_gray>Vote for the server"));

        List<String> head = Lore.builder(Lore.Accent.CYAN).subcategory("Voting").divider()
                .stat("Votes", String.format("%,d", status.total()))
                .stat("This month", String.format("%,d", status.month()))
                .stat("Streak", days(status.streak()))
                .stat("Best streak", days(status.bestStreak()))
                .divider()
                .description(status.votedToday() ? "&aYou've voted today - thank you!" : "&eYou haven't voted today yet.")
                .build();
        ItemStack skull = Lore.item(Material.PLAYER_HEAD, GOLD + "&l" + player.getName(), fixDetail(head));
        if (skull.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            skull.setItemMeta(meta);
        }
        menu.set(4, 0, skull, null);

        List<VoteConfig.Site> sites = new ArrayList<>(config.sites.values());
        long now = System.currentTimeMillis();
        for (int i = 0; i < Math.min(10, sites.size()); i++) {
            VoteConfig.Site site = sites.get(i);
            siteTile(menu, player, site, status.siteTimes(), now, 2 + i % 5, 1 + i / 5);
        }
        if (sites.isEmpty()) {
            menu.set(4, 1, Lore.item(Material.CLOCK, "&f&lComing soon", Lore.builder(Lore.Accent.CYAN).subcategory("Voting Sites")
                    .divider().description("The server's voting sites", "will be listed here soon.").build()), null);
        }

        Lore per = Lore.builder(Lore.Accent.CYAN).subcategory("Every Vote").divider().description("Each site you vote on pays:");
        List<String> perLines = new ArrayList<>(per.build());
        perLines.addAll(bullets(config.voteReward));
        menu.set(2, 3, hidden(Lore.item(Material.GOLD_INGOT, GOLD + "&lVote Rewards", perLines)), null);

        List<String> streak = new ArrayList<>(Lore.builder(Lore.Accent.CYAN).subcategory("Voting Streak").divider()
                .description("Vote at least once a day to", "keep your streak going.").divider()
                .stat("Current streak", days(status.streak())).build());
        for (Map.Entry<Integer, VoteConfig.Reward> milestone : config.streakMilestones.entrySet()) {
            boolean reached = status.streak() >= milestone.getKey();
            streak.add("&b• " + (reached ? "&a✔ " : "&7") + days(milestone.getKey()) + "&8: &f" + summary(milestone.getValue()));
        }
        menu.set(4, 3, hidden(Lore.item(Material.BLAZE_POWDER, GOLD + "&lVoting Streak", streak)), null);

        if (config.partyEnabled) {
            int progress = module.partyProgress();
            int goal = config.partyGoal;
            List<String> party = new ArrayList<>(Lore.builder(Lore.Accent.CYAN).subcategory("Vote Party").divider()
                    .description("Every " + goal + " votes from anyone,", "everyone online gets a reward.").divider()
                    .stat("Progress", progress >= 0 ? Math.min(progress, goal) + " / " + goal : "counting...").build());
            if (progress >= 0) {
                party.add(progressBar(Math.min(1.0, progress / (double) goal)));
            }
            party.add("");
            party.add("&7Party reward: &f" + summary(config.partyReward));
            menu.set(6, 3, hidden(Lore.item(Material.FIREWORK_ROCKET, GOLD + "&lVote Party", party)), null);
        }

        menu.set(8, 4, Lore.item(Material.BARRIER, " &c✘ Close ", List.of("", "&fClose the menu.")), click -> player.closeInventory());
        menu.open(player);
    }

    private void siteTile(Menu menu, Player player, VoteConfig.Site site, Map<String, Long> times, long now, int x, int y) {
        Long last = times.get(site.id());
        long ready = last == null ? 0L : last + site.cooldownHours() * 3_600_000L;
        boolean done = ready > now;
        Lore lore = Lore.builder(Lore.Accent.CYAN).subcategory("Voting Site").divider();
        if (done) {
            lore.description("&a✔ Voted", "&7Vote again in &f" + duration(ready - now) + "&7.").clickPrompt("Click for the link");
        } else {
            lore.description("Vote for the server here", "every " + site.cooldownHours() + " hours.").clickPrompt("Click to Vote");
        }
        Material icon = Material.matchMaterial(site.icon());
        if (icon == null || !icon.isItem() || icon.isAir()) {
            icon = Material.PAPER;
        }
        ItemStack item = hidden(Lore.item(icon, (done ? "&a&l✔ " : GOLD + "&l") + site.name(), fixDetail(lore.build())));
        if (!done) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        menu.set(x, y, item, click -> {
            player.closeInventory();
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.4f, 1.3f);
            if (site.url().isBlank()) {
                player.sendMessage(Msg.neutral("That site's link isn't set up yet."));
                return;
            }
            player.sendMessage(Msg.neutral("Vote on <white>" + Msg.escape(site.name()) + "</white>: ")
                    .append(Msg.parse("<#ffd84a><u>" + Msg.escape(site.url()) + "</u>"))
                    .clickEvent(ClickEvent.openUrl(site.url())));
        });
    }

    /** Lore.description greys every line; keep the explicit colour of status lines. */
    private static List<String> fixDetail(List<String> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(line.startsWith("&7&") ? line.substring(2) : line);
        }
        return out;
    }

    private static ItemStack hidden(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE, ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static String progressBar(double fraction) {
        int filled = (int) Math.round(fraction * 20);
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(20 - filled);
    }

    private static List<String> bullets(VoteConfig.Reward reward) {
        List<String> lines = new ArrayList<>();
        if (reward.moneyCents() > 0) {
            lines.add("&b• &f" + Money.format(reward.moneyCents()));
        }
        if (reward.xp() > 0) {
            lines.add("&b• &f" + reward.xp() + " XP");
        }
        for (String label : labels(reward)) {
            lines.add("&b• &f" + label);
        }
        return lines;
    }

    private static String summary(VoteConfig.Reward reward) {
        List<String> parts = new ArrayList<>();
        if (reward.moneyCents() > 0) {
            parts.add(Money.format(reward.moneyCents()));
        }
        if (reward.xp() > 0) {
            parts.add(reward.xp() + " XP");
        }
        parts.addAll(labels(reward));
        return parts.isEmpty() ? "a reward" : String.join(", ", parts);
    }

    /** The reward's own {@code display} lines, or a label guessed from known key-giving commands. */
    private static List<String> labels(VoteConfig.Reward reward) {
        if (!reward.display().isEmpty()) {
            return reward.display();
        }
        List<String> out = new ArrayList<>();
        for (String command : reward.commands()) {
            String label = describe(command);
            if (label != null) {
                out.add(label);
            }
        }
        return out;
    }

    private static String describe(String command) {
        String[] parts = command.split("\\s+");
        if (parts.length >= 3 && parts[0].equalsIgnoreCase("phoenixcrates") && parts[1].equalsIgnoreCase("giveKey")) {
            int amount = 1;
            if (parts.length >= 4) {
                try {
                    amount = Integer.parseInt(parts[parts.length > 4 ? 4 : 3].replaceAll("[^0-9]", ""));
                } catch (NumberFormatException ignored) {
                    // keep 1
                }
            }
            String key = parts[2].substring(0, 1).toUpperCase() + parts[2].substring(1).toLowerCase();
            return amount + " " + key + " Key" + (amount == 1 ? "" : "s");
        }
        return null;
    }

    private static String days(int days) {
        return days + (days == 1 ? " day" : " days");
    }

    private static String duration(long millis) {
        long minutes = Math.max(1, millis / 60_000L);
        long hours = minutes / 60;
        return hours > 0 ? hours + "h " + (minutes % 60) + "m" : minutes + "m";
    }
}
