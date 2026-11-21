package me.Evan52098.genscore.discord;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.rewardbox.Reward;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

final class NitroMenu {

    private static final String PINK = "&#f47fff";
    private static final int ROWS = 5;
    private static final int MAX_TIERS = 7;

    private final DiscordModule module;

    NitroMenu(DiscordModule module) {
        this.module = module;
    }

    void open(Player player) {
        if (!module.cooldowns().tryStart(player.getUniqueId(), "discord-menu", 500L)) {
            return;
        }
        module.link(player.getUniqueId()).thenCompose(link -> link == null
                ? java.util.concurrent.CompletableFuture.completedFuture(new Object[]{null, Set.<String>of()})
                : module.claimed(link.discordId()).thenApply(c -> new Object[]{link, c})
        ).whenComplete((r, e) -> Tasks.entity.run(player, () -> {
            if (e != null) {
                player.sendMessage(Msg.bad("Couldn't load your Discord link. Try again in a moment."));
                return;
            }
            @SuppressWarnings("unchecked")
            Set<String> claimed = (Set<String>) r[1];
            build(player, (DiscordModule.Link) r[0], claimed).open(player);
        }));
    }

    private Menu build(Player player, DiscordModule.@Nullable Link link, Set<String> claimed) {
        DiscordConfig c = module.config();
        long now = System.currentTimeMillis();
        int days = link == null ? 0 : link.boostedDays(now);
        boolean boosting = link != null && link.boosting();
        Menu menu = new Menu(ROWS, MenuStyle.title("Nitro Rewards"));

        Lore head = Lore.builder(Lore.Accent.CYAN).subcategory("Discord").divider();
        if (link == null) {
            head.stat("Linked", "&cNo").divider().description("Link your account with &f/link", "to unlock Discord rewards.");
        } else {
            head.stat("Linked", "&a" + link.discordName())
                    .stat("Boosting", boosting ? "&aYes" : "&cNo")
                    .stat("Days boosted", String.format("%,d", days))
                    .divider();
            if (boosting) {
                head.description("&aThank you for boosting!");
            } else {
                head.description("&7Boost the Discord to climb", "&7the Nitro reward path.");
            }
        }
        ItemStack skull = Lore.item(Material.PLAYER_HEAD, PINK + player.getName(), head.build());
        if (skull.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            skull.setItemMeta(meta);
        }
        menu.set(4, MenuStyle.clean(skull, false));

        List<DiscordConfig.Tier> tiers = c.path.subList(0, Math.min(MAX_TIERS, c.path.size()));
        int[] slots = MenuStyle.symmetric(tiers.size(), 1);
        boolean[] reached = new boolean[tiers.size()];
        for (int i = 0; i < tiers.size(); i++) {
            DiscordConfig.Tier tier = tiers.get(i);
            reached[i] = boosting && days >= tier.days();
            tierTile(menu, player, tier, slots[i], link, boosting, days, claimed.contains("nitro:" + tier.id()));
        }
        pathLine(menu, slots, reached);
        if (tiers.isEmpty()) {
            menu.set(22, MenuStyle.icon(Material.CLOCK, "&fComing soon", Lore.builder(Lore.Accent.CYAN)
                    .subcategory("Nitro Path").divider().description("Booster rewards are", "being set up.").build()));
        }

        List<String> linkLore = new ArrayList<>(Lore.builder(Lore.Accent.CYAN).subcategory("Linking").divider()
                .description(link == null ? "Link your Discord for:" : "&a✔ Linked").build());
        if (link == null) {
            for (Reward r : c.linkReward) {
                linkLore.add("&b• &f" + r.display());
            }
            linkLore.add("");
            linkLore.add("&e▸ Click to get a code");
        }
        menu.set(MenuStyle.shelf(ROWS, 2), MenuStyle.icon(Material.NAME_TAG, "&#5865f2Discord Link", linkLore), click -> {
            if (link == null) {
                player.closeInventory();
                player.performCommand("link");
            }
        });

        if (c.nitroKit.isBlank() && !c.monthlyRewards.isEmpty()) {
            List<String> lines = new ArrayList<>();
            lines.add("&7Every &f" + c.monthlyDays + " days &7of boosting:");
            for (Reward r : c.monthlyRewards) {
                lines.add("&f• " + r.display());
            }
            int next = c.monthlyDays - (days % c.monthlyDays);
            lines.add(boosting ? "&aNext one in &f" + next + (next == 1 ? " day" : " days") : "&7Boost the Discord to start.");
            menu.set(MenuStyle.shelf(ROWS, 3), MenuStyle.icon(Material.SNIFFER_EGG, PINK + "Monthly Reward", Lore.builder(Lore.Accent.CYAN)
                    .subcategory("Every Month").divider().description(lines.toArray(new String[0])).build()));
        }
        boolean kitReady = boosting && !c.nitroKit.isBlank();
        if (!c.nitroKit.isBlank()) menu.set(MenuStyle.shelf(ROWS, 3), MenuStyle.clean(Lore.item(Material.SHULKER_BOX, PINK + "Nitro Kit",
                Lore.builder(Lore.Accent.CYAN).subcategory("Booster Kit").divider()
                        .description(kitReady ? new String[]{"Claim your booster kit."} : new String[]{"&7Boost the Discord to unlock", "&7the booster kit."})
                        .clickPrompt(kitReady ? "Click to claim" : "Locked").build()), kitReady), click -> {
            if (kitReady) {
                player.closeInventory();
                player.performCommand("kit " + c.nitroKit);
            }
        });
        menu.set(MenuStyle.shelf(ROWS, 4), MenuStyle.close(), click -> player.closeInventory());
        menu.set(MenuStyle.shelf(ROWS, 5), MenuStyle.icon(Material.CHEST, "&fReward Box", Lore.builder(Lore.Accent.CYAN)
                .subcategory("Overflow").divider().description("Rewards that didn't fit", "in your inventory.")
                .clickPrompt("Click to collect").build()), click -> {
            player.closeInventory();
            player.performCommand("nitro box");
        });
        menu.set(MenuStyle.shelf(ROWS, 6), MenuStyle.icon(Material.AMETHYST_CLUSTER, PINK + "How It Works", Lore.builder(Lore.Accent.CYAN)
                .subcategory("Nitro Path").divider()
                .description("Link with &f/link&7, then boost", "our Discord server. Every day", "you boost moves you along",
                        "the path. Each step can be", "claimed once per Discord account.").build()));

        MenuStyle.frame(menu, ROWS);
        return menu;
    }

    private void tierTile(Menu menu, Player player, DiscordConfig.Tier tier, int slot, DiscordModule.@Nullable Link link,
                          boolean boosting, int days, boolean claimed) {
        boolean unlocked = boosting && days >= tier.days();
        Lore lore = Lore.builder(Lore.Accent.CYAN).subcategory(tier.days() == 0 ? "Any Boost" : tier.days() + " Days Boosted").divider();
        for (String line : tier.description()) {
            lore.description(line);
        }
        List<String> lines = new ArrayList<>(lore.build());
        if (!tier.description().isEmpty()) {
            lines.add("");
        }
        lines.add("&8" + Lore.smallCaps("Rewards"));
        for (Reward r : tier.rewards()) {
            lines.add("&b• &f" + r.display());
        }
        lines.add("");
        String status;
        if (claimed) {
            status = "&a✔ Claimed";
        } else if (unlocked) {
            status = "&e▸ Click to claim";
        } else if (link == null) {
            status = "&c✘ Link your Discord first";
        } else if (!boosting) {
            status = "&c✘ Boost the Discord to unlock";
        } else {
            status = "&7" + (tier.days() - days) + " more day" + (tier.days() - days == 1 ? "" : "s") + " to go";
        }
        lines.add(status);
        Material icon = claimed ? Material.LIME_DYE : unlocked ? tier.icon() : Material.GRAY_DYE;
        String name = (claimed ? "&a" : unlocked ? PINK : "&7") + tier.name();
        ItemStack item = MenuStyle.clean(Lore.item(icon, name, lines), unlocked && !claimed);
        menu.set(slot, item, click -> {
            if (claimed || !unlocked) {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.8f);
                return;
            }
            if (!module.cooldowns().tryStart(player.getUniqueId(), "discord-claim", 1500L)) {
                return;
            }
            player.closeInventory();
            module.claimTier(player, tier);
        });
    }

    private static void pathLine(Menu menu, int[] slots, boolean[] reached) {
        if (slots.length == 0) {
            return;
        }
        int row = 2 * 9;
        int first = slots[0] % 9;
        int last = slots[slots.length - 1] % 9;
        for (int col = first; col <= last; col++) {
            int idx = -1;
            int before = -1;
            for (int i = 0; i < slots.length; i++) {
                if (slots[i] % 9 == col) {
                    idx = i;
                }
                if (slots[i] % 9 <= col) {
                    before = i;
                }
            }
            boolean lit;
            if (idx >= 0) {
                lit = reached[idx];
            } else {
                lit = before >= 0 && before + 1 < reached.length && reached[before + 1];
            }
            Material pane = lit ? Material.MAGENTA_STAINED_GLASS_PANE
                    : (idx >= 0 && idx > 0 && reached[idx - 1]) || (idx < 0 && before >= 0 && reached[before])
                    ? Material.PINK_STAINED_GLASS_PANE : Material.GRAY_STAINED_GLASS_PANE;
            menu.set(row + col, MenuStyle.pane(pane));
        }
    }
}
