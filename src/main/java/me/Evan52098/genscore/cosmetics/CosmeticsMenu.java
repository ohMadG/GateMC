package me.Evan52098.genscore.cosmetics;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * The wardrobe menus. All are {@link Menu}s (every click cancelled, nothing can be taken out), opened and clicked on
 * the player's own thread. Equipping only changes which cosmetic id is selected; no items are ever given.
 */
final class CosmeticsMenu {

    private static final int PER_PAGE = 45;
    private static final int[] CATEGORY_SLOTS = {10, 11, 12, 13, 14, 15, 16};

    private final CosmeticsModule module;

    CosmeticsMenu(CosmeticsModule module) {
        this.module = module;
    }

    // ------------------------------------------------------------------ main

    void openMain(Player player) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            player.sendMessage(Msg.bad("Your cosmetics are still loading, try again in a moment."));
            return;
        }
        CosmeticsConfig cfg = module.config();
        Menu menu = new Menu(4, Msg.parse("<dark_gray>Cosmetics"));
        CosmeticCategory[] cats = CosmeticCategory.values();
        for (int i = 0; i < cats.length && i < CATEGORY_SLOTS.length; i++) {
            CosmeticCategory cat = cats[i];
            int owned = 0;
            for (Cosmetic c : cfg.cosmetics.get(cat).values()) {
                if (module.owns(player, data, c)) {
                    owned++;
                }
            }
            Cosmetic current = module.activeOwned(player, data, cat);
            List<String> lore = Lore.builder(Lore.Accent.CYAN)
                    .subcategory("Category")
                    .description(cat.blurb())
                    .divider()
                    .stat("Owned", owned + "/" + cfg.cosmetics.get(cat).size())
                    .stat("Selected", current == null ? "None" : current.displayName())
                    .clickPrompt("Click to browse")
                    .build();
            menu.set(CATEGORY_SLOTS[i], Lore.item(cat.icon(), "&b" + cat.title(), lore), e -> openCategory(player, cat, 0));
        }

        int kills = data.kills();
        CosmeticsConfig.KillRank rank = cfg.rankFor(kills);
        CosmeticsConfig.KillRank next = cfg.nextRank(kills);
        Lore rankLore = Lore.builder(Lore.Accent.WHITE)
                .subcategory("Kill rank")
                .description("Shown above your head. Earned by", "killing other players.")
                .divider()
                .stat("Kills", String.valueOf(kills))
                .stat("Rank", rank == null ? "None" : rank.display());
        if (next != null) {
            rankLore.stat("Next", next.display() + " &7(" + (next.kills() - kills) + " more)");
        }
        menu.set(29, Lore.item(Material.IRON_SWORD, "&fKill Rank", rankLore.build()));

        if (player.hasPermission(CosmeticsModule.HOMEICON)) {
            menu.set(33, Lore.item(Material.RED_BED, "&fHome Icons", Lore.builder(Lore.Accent.WHITE)
                    .subcategory("Homes")
                    .description("Pick the icon each of your", "homes shows in the homes menu.")
                    .clickPrompt("Click to choose")
                    .build()), e -> openHomes(player));
        }
        menu.set(31, Lore.item(Material.BARRIER, "&cClose", List.of()), e -> player.closeInventory());
        menu.open(player);
    }

    // ------------------------------------------------------------------ one category

    void openCategory(Player player, CosmeticCategory cat, int page) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            return;
        }
        CosmeticsConfig cfg = module.config();
        List<Cosmetic> all = new ArrayList<>(cfg.cosmetics.get(cat).values());
        int pages = Math.max(1, (all.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, Msg.parse("<dark_gray>" + Msg.escape(cat.title()) + (pages > 1 ? " (" + (p + 1) + "/" + pages + ")" : "")));
        String activeId = data.active(cat);
        for (int i = 0; i < PER_PAGE; i++) {
            int index = p * PER_PAGE + i;
            if (index >= all.size()) {
                break;
            }
            Cosmetic c = all.get(index);
            boolean owned = module.owns(player, data, c);
            boolean selected = owned && c.id().equals(activeId);
            Lore lore = Lore.builder(selected ? Lore.Accent.GREEN : owned ? Lore.Accent.CYAN : Lore.Accent.RED)
                    .subcategory(cat.title());
            for (String line : c.description()) {
                lore.description(line);
            }
            if (cat == CosmeticCategory.TOOL_SKIN && c.skin() != null) {
                lore.divider().description("&7Others see it on your tools;", "&7your own view stays the same.");
            }
            lore.divider().stat("Status", selected ? "Selected" : owned ? "Owned" : "Locked");
            if (!owned) {
                lore.divider().description(cfg.lockedHint);
            } else {
                lore.clickPrompt(selected ? "Click to unequip" : "Click to equip");
            }
            ItemStack icon = Lore.item(c.icon(), c.displayName(), lore.build());
            menu.set(i, icon, e -> click(player, cat, c, p));
        }
        menu.set(45, Lore.item(Material.ARROW, "&fBack", List.of()), e -> openMain(player));
        if (p > 0) {
            menu.set(48, Lore.item(Material.PAPER, "&fPrevious page", List.of()), e -> openCategory(player, cat, p - 1));
        }
        menu.set(49, Lore.item(Material.BUCKET, "&cUnequip", Lore.builder(Lore.Accent.RED)
                .description("Turn off your " + cat.title().toLowerCase() + ".")
                .build()), e -> {
            module.setActive(player, data, cat, null);
            player.sendMessage(Msg.neutral(cat.title() + " turned off."));
            openCategory(player, cat, p);
        });
        if (p < pages - 1) {
            menu.set(50, Lore.item(Material.PAPER, "&fNext page", List.of()), e -> openCategory(player, cat, p + 1));
        }
        menu.set(53, Lore.item(Material.BARRIER, "&cClose", List.of()), e -> player.closeInventory());
        menu.open(player);
    }

    private void click(Player player, CosmeticCategory cat, Cosmetic c, int page) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            return;
        }
        // Re-read the definition: a reload may have removed it while the menu was open.
        Cosmetic current = module.config().get(cat, c.id());
        if (current == null) {
            openCategory(player, cat, page);
            return;
        }
        if (!module.owns(player, data, current)) {
            player.sendMessage(Msg.bad("You don't own " + Msg.white(CosmeticsModule.plain(current.displayName())) + " yet."));
            return;
        }
        if (current.id().equals(data.active(cat))) {
            module.setActive(player, data, cat, null);
            player.sendMessage(Msg.neutral("Unequipped " + Msg.white(CosmeticsModule.plain(current.displayName())) + "."));
        } else {
            module.setActive(player, data, cat, current);
            player.sendMessage(Msg.good("Equipped " + Msg.white(CosmeticsModule.plain(current.displayName())) + "."));
        }
        openCategory(player, cat, page);
    }

    // ------------------------------------------------------------------ home icons

    void openHomes(Player player) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            return;
        }
        List<String> homes = module.homeNamesOf(player);
        List<String> shown;
        if (homes == null) {
            // The teleport hook is not connected: show homes that already have an icon and explain the command.
            shown = data.homeIconNames();
            player.sendMessage(Msg.neutral("Use " + Msg.white("/homeicon <home>") + " to choose a home's icon."));
        } else {
            shown = new ArrayList<>(homes);
            if (shown.isEmpty()) {
                player.sendMessage(Msg.neutral("You have no homes set."));
                return;
            }
        }
        int rows = Math.max(2, Math.min(6, (shown.size() + 8) / 9 + 1));
        Menu menu = new Menu(rows, Msg.parse("<dark_gray>Home Icons"));
        int max = (rows - 1) * 9;
        for (int i = 0; i < shown.size() && i < max; i++) {
            String home = shown.get(i);
            String mat = data.homeIcon(home);
            Material icon = mat == null ? null : Material.matchMaterial(mat);
            menu.set(i, Lore.item(icon != null && icon.isItem() ? icon : Material.RED_BED, "&f" + home,
                    Lore.builder(Lore.Accent.WHITE).subcategory("Home").clickPrompt("Click to change icon").build()),
                    e -> openIconPicker(player, home));
        }
        int bottom = (rows - 1) * 9;
        menu.set(bottom, Lore.item(Material.ARROW, "&fBack", List.of()), e -> openMain(player));
        menu.set(bottom + 8, Lore.item(Material.BARRIER, "&cClose", List.of()), e -> player.closeInventory());
        menu.open(player);
    }

    void openIconPicker(Player player, String home) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            return;
        }
        CosmeticsConfig cfg = module.config();
        Menu menu = new Menu(6, Msg.parse("<dark_gray>Icon for " + Msg.escape(home)));
        List<Material> choices = cfg.homeIconChoices;
        for (int i = 0; i < choices.size() && i < PER_PAGE; i++) {
            Material m = choices.get(i);
            menu.set(i, Lore.item(m, "&f" + pretty(m), Lore.builder(Lore.Accent.WHITE).clickPrompt("Click to use").build()),
                    e -> choose(player, home, m));
        }
        if (cfg.homeIconAllowHeld) {
            menu.set(47, Lore.item(Material.ITEM_FRAME, "&fUse held item", Lore.builder(Lore.Accent.WHITE)
                    .description("Uses the type of the item in", "your main hand (only its look).")
                    .clickPrompt("Click to use").build()), e -> {
                Material held = player.getInventory().getItemInMainHand().getType();
                if (held.isAir() || !held.isItem()) {
                    player.sendMessage(Msg.bad("Hold the item you want as the icon."));
                    return;
                }
                choose(player, home, held);
            });
        }
        menu.set(49, Lore.item(Material.BUCKET, "&cReset", Lore.builder(Lore.Accent.RED)
                .description("Back to the default icon.").build()), e -> {
            data.setHomeIcon(home, null);
            player.sendMessage(Msg.neutral("Home " + Msg.white(home) + " uses the default icon again."));
            openHomes(player);
        });
        menu.set(45, Lore.item(Material.ARROW, "&fBack", List.of()), e -> openHomes(player));
        menu.set(53, Lore.item(Material.BARRIER, "&cClose", List.of()), e -> player.closeInventory());
        menu.open(player);
    }

    private void choose(Player player, String home, Material m) {
        PlayerCosmetics data = module.data(player.getUniqueId());
        if (data == null) {
            return;
        }
        if (!player.hasPermission(CosmeticsModule.HOMEICON)) {
            player.sendMessage(Msg.bad("Home icons are for ranked players."));
            return;
        }
        // Checklist 10: a cap on stored rows per player (names are free text when the teleport hook is not connected).
        if (!data.hasHomeIcon(home) && data.homeIconCount() >= module.config().homeIconMax) {
            player.sendMessage(Msg.bad("You have too many home icons set. Reset some first."));
            return;
        }
        data.setHomeIcon(home, m.name());
        player.sendMessage(Msg.good("Home " + Msg.white(home) + " now shows " + Msg.white(pretty(m)) + "."));
        openHomes(player);
    }

    private static String pretty(Material m) {
        String s = m.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
