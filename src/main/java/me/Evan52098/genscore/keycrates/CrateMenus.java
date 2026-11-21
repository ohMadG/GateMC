package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * /crates (your keys and every crate), the chance preview, and the opening animation. All {@link Menu}s, so every
 * click is cancelled (checklist 4); buttons only call {@link CrateService}, which re-reads the live inventory.
 * Everything here runs on the viewing player's thread.
 */
final class CrateMenus {

    private static final int PER_PAGE = 45;

    private final CrateService service;
    private final Set<UUID> buying = java.util.concurrent.ConcurrentHashMap.newKeySet();

    CrateMenus(CrateService service) {
        this.service = service;
    }

    // ------------------------------------------------------------------ /crates

    void main(Player player) {
        UUID uuid = player.getUniqueId();
        service.store().claimCounts(uuid).whenComplete((counts, err) ->
                CrateService.later(player, () -> drawMain(player, err == null && counts != null ? counts : new int[2]), null, 1));
    }

    private void drawMain(Player player, int[] claimCounts) {
        CrateConfig cfg = service.config();
        List<CrateConfig.Crate> crates = new ArrayList<>(cfg.all());
        int maxSlot = 0;
        for (CrateConfig.Crate c : crates) {
            maxSlot = Math.max(maxSlot, c.menuSlot());
        }
        int bodyRows = Math.max(2, Math.min(5, Math.max((maxSlot / 9) + 1, (crates.size() + 8) / 9)));
        int rows = bodyRows + 1;
        Menu menu = new Menu(rows, CrateConfig.text(cfg.menuTitle));
        int body = bodyRows * 9;
        for (int i = 0; i < rows * 9; i++) {
            menu.set(i, filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        Set<Integer> used = new HashSet<>();
        List<CrateConfig.Crate> unplaced = new ArrayList<>();
        for (CrateConfig.Crate c : crates) {
            if (c.menuSlot() >= 0 && c.menuSlot() < body && used.add(c.menuSlot())) {
                menu.set(c.menuSlot(), crateIcon(player, c), e -> crateClick(player, c, e));
            } else {
                unplaced.add(c);
            }
        }
        int next = 10;
        for (CrateConfig.Crate c : unplaced) {
            while (next < body && used.contains(next)) {
                next++;
            }
            if (next >= body) {
                break;
            }
            used.add(next);
            menu.set(next, crateIcon(player, c), e -> crateClick(player, c, e));
        }
        int waiting = claimCounts[0] + claimCounts[1];
        menu.set(body + 4, item(Material.ENDER_CHEST, "&d&lClaim Box", List.of(
                "&7Keys and items that did not fit",
                "&7in your inventory wait here.",
                "",
                "&7Keys waiting: &f" + claimCounts[0],
                "&7Items waiting: &f" + claimCounts[1],
                "",
                waiting > 0 ? me.Evan52098.genscore.menu.Lore.prompt("click to claim") : "&8Nothing waiting")), e -> {
            player.closeInventory();
            service.claim(player);
        });
        menu.set(body, item(Material.BOOK, "&e&lHow keys work", List.of(
                "&7Earn keys by playing, voting",
                "&7and from the store.",
                "",
                "&7Open them here or at a crate",
                "&7at spawn. Every win is announced.")));
        menu.open(player);
    }

    private ItemStack crateIcon(Player player, CrateConfig.Crate c) {
        int keys = service.keys().count(player.getInventory(), c.id());
        ItemStack icon = c.icon().clone();
        ItemMeta meta = icon.getItemMeta();
        List<String> lore = new ArrayList<>();
        lore.add("&7Your keys: " + (keys > 0 ? "&a" : "&c") + keys);
        lore.add("");
        if (c.creditPrice() > 0) {
            lore.add("&7Buy a key: &a" + String.format("%,d", c.creditPrice()) + " credits &8(in the preview)");
        }
        lore.add(me.Evan52098.genscore.menu.Lore.prompt("left-click to preview rewards"));
        if (keys > 0) {
            lore.add(me.Evan52098.genscore.menu.Lore.prompt("right-click to open one"));
            lore.add("&eShift + right-click &7to open all");
        }
        if (c.noDuplicates()) {
            lore.add("");
            lore.add("&8You never win the same reward twice.");
        }
        meta.lore(lore.stream().map(CrateConfig::text).toList());
        if (keys > 0) {
            icon.setAmount(Math.max(1, Math.min(64, keys)));
        }
        icon.setItemMeta(meta);
        return icon;
    }

    private void crateClick(Player player, CrateConfig.Crate c, InventoryClickEvent e) {
        ClickType click = e.getClick();
        if (click.isRightClick()) {
            service.open(player, c.id(), click.isShiftClick() ? Integer.MAX_VALUE : 1);
        } else {
            preview(player, c, 0, true);
        }
    }

    private void buyKeys(Player player, CrateConfig.Crate crate, int amount, int page, boolean backButton) {
        CrateConfig.Crate live = service.config().crate(crate.id());
        ShardsService credits = ShardsModule.service();
        if (live == null || live.creditPrice() <= 0 || credits == null) {
            player.sendMessage(Msg.bad("That key can't be bought right now."));
            return;
        }
        if (!buying.add(player.getUniqueId())) {
            return;
        }
        try {
            long cost = live.creditPrice() * amount;
            if (!credits.withdraw(player.getUniqueId(), cost, "bought " + amount + "x " + live.id() + " key")) {
                player.sendMessage(Msg.bad("You need " + Msg.white(String.format("%,d", cost)) + " credits for that."));
                player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_NO, 0.7f, 1f);
                return;
            }
            if (!service.giveKeys(player, live.id(), amount, "bought with credits")) {
                credits.deposit(player.getUniqueId(), cost, "refund: " + live.id() + " key purchase failed");
                player.sendMessage(Msg.bad("Something went wrong; your credits were refunded."));
                return;
            }
            player.sendMessage(Msg.good("Bought " + Msg.white(amount + "x") + " key" + (amount == 1 ? "" : "s") + " for "
                    + Msg.white(String.format("%,d", cost)) + " credits: ").append(CrateConfig.text(live.keyName())));
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.4f);
        } finally {
            buying.remove(player.getUniqueId());
        }
        preview(player, live, page, backButton);
    }

    // ------------------------------------------------------------------ preview

    void preview(Player player, CrateConfig.Crate crate, int page, boolean backButton) {
        CrateConfig cfg = service.config();
        CrateConfig.Crate live = cfg.crate(crate.id()); // the newest definition after a reload
        if (live == null) {
            player.sendMessage(Msg.bad("That crate is closed."));
            player.closeInventory();
            return;
        }
        List<CrateConfig.Reward> rewards = new ArrayList<>(live.rewards());
        rewards.sort(Comparator.comparingDouble(CrateConfig.Reward::chance).reversed());
        int pages = Math.max(1, (rewards.size() + PER_PAGE - 1) / PER_PAGE);
        int p = Math.max(0, Math.min(page, pages - 1));
        List<CrateConfig.Reward> shown = rewards.subList(p * PER_PAGE, Math.min(rewards.size(), (p + 1) * PER_PAGE));
        int bodyRows = Math.max(1, (shown.size() + 8) / 9);
        Menu menu = new Menu(bodyRows + 1, CrateConfig.text(live.name() + (pages > 1 ? " &8(" + (p + 1) + "/" + pages + ")" : "")));
        for (int i = 0; i < shown.size(); i++) {
            menu.set(i, rewardIcon(cfg, shown.get(i)));
        }
        int base = bodyRows * 9;
        for (int i = base; i < base + 9; i++) {
            menu.set(i, filler(Material.GRAY_STAINED_GLASS_PANE));
        }
        if (backButton) {
            menu.set(base, item(Material.ARROW, "&7Back", List.of()), e -> main(player));
        }
        int keys = service.keys().count(player.getInventory(), live.id());
        if (keys > 0) {
            menu.set(base + 3, item(live.keyMaterial(), "&a&lOpen one", List.of("&7Your keys: &f" + keys, "", me.Evan52098.genscore.menu.Lore.prompt("click to open"))),
                    e -> service.open(player, live.id(), 1));
            menu.set(base + 5, item(Material.CHEST_MINECART, "&6&lOpen all", List.of("&7Opens up to &f" + Math.min(keys, cfg.openAllMax) + " &7keys at once.", "", me.Evan52098.genscore.menu.Lore.prompt("click to open"))),
                    e -> service.open(player, live.id(), Integer.MAX_VALUE));
        } else {
            menu.set(base + 4, item(Material.BARRIER, "&cNo keys", List.of("&7You need a " + live.keyName() + "&7.")));
        }
        if (live.creditPrice() > 0) {
            ShardsService credits = ShardsModule.service();
            long balance = credits == null ? 0 : credits.balance(player.getUniqueId());
            menu.set(base + 1, item(Material.EMERALD, "&a&lBuy a key", List.of(
                    "&7Price: &a" + String.format("%,d", live.creditPrice()) + " credits &7each",
                    "&7Your credits: &f" + String.format("%,d", balance),
                    "",
                    me.Evan52098.genscore.menu.Lore.prompt("click to buy 1"),
                    "&eShift-click &7to buy 5")),
                    e -> buyKeys(player, live, e.getClick().isShiftClick() ? 5 : 1, page, backButton));
        }
        if (p > 0) {
            menu.set(base + 7, item(Material.PAPER, "&ePrevious page", List.of()), e -> preview(player, live, p - 1, backButton));
        }
        if (p < pages - 1) {
            menu.set(base + 8, item(Material.PAPER, "&eNext page", List.of()), e -> preview(player, live, p + 1, backButton));
        }
        menu.open(player);
    }

    private static ItemStack rewardIcon(CrateConfig cfg, CrateConfig.Reward r) {
        ItemStack icon = r.icon().clone();
        ItemMeta meta = icon.getItemMeta();
        List<net.kyori.adventure.text.Component> lore = new ArrayList<>(meta.hasLore() && meta.lore() != null ? meta.lore() : List.of());
        if (!lore.isEmpty()) {
            lore.add(CrateConfig.text(""));
        }
        lore.add(CrateConfig.text(cfg.rarity(r.chance())));
        lore.add(CrateConfig.text("&7Chance: &f" + CrateConfig.chanceText(r.chance()) + "%"));
        meta.lore(lore);
        icon.setItemMeta(meta);
        return icon;
    }

    // ------------------------------------------------------------------ opening animation

    /**
     * A short spinning reel that lands on the reward already won. Cosmetic only: closing it early just delivers at once
     * ({@code done} runs exactly once); if the player leaves, {@code retired} runs and the reward waits for their
     * next join.
     */
    void animate(Player player, CrateConfig.Crate crate, CrateConfig.Reward won, Runnable done, Runnable retired) {
        int steps = 28;
        List<ItemStack> reel = new ArrayList<>(steps + 9);
        for (int i = 0; i < steps + 9; i++) {
            CrateConfig.Reward r = crate.roll(Set.of());
            reel.add(r == null ? won.icon() : r.icon());
        }
        reel.set(steps + 4, won.icon());
        Menu menu = new Menu(3, CrateConfig.text("&8Opening " + crate.name()));
        for (int i = 0; i < 27; i++) {
            menu.set(i, filler(Material.BLACK_STAINED_GLASS_PANE));
        }
        menu.set(4, filler(Material.HOPPER));
        menu.set(22, filler(Material.HOPPER));
        AtomicBoolean finished = new AtomicBoolean();
        Runnable finish = () -> {
            if (finished.compareAndSet(false, true)) {
                done.run();
            }
        };
        menu.open(player);
        frame(player, menu, reel, 0, steps, finish, retired, won);
    }

    private void frame(Player player, Menu menu, List<ItemStack> reel, int k, int steps, Runnable finish, Runnable retired, CrateConfig.Reward won) {
        if (player.getOpenInventory().getTopInventory().getHolder() != menu) {
            finish.run(); // closed early: deliver now
            return;
        }
        for (int i = 0; i < 9; i++) {
            menu.set(9 + i, reel.get(k + i));
        }
        if (k >= steps) {
            menu.set(4, filler(Material.LIME_STAINED_GLASS_PANE));
            menu.set(22, filler(Material.LIME_STAINED_GLASS_PANE));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.2f);
            CrateService.later(player, finish, retired, 12);
            return;
        }
        player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.6f, 1.0f + k / 40f);
        long delay = k < 14 ? 1 : k < 20 ? 2 : k < 25 ? 3 : 5; // slows down before it stops
        CrateService.later(player, () -> frame(player, menu, reel, k + 1, steps, finish, retired, won), retired, delay);
    }

    // ------------------------------------------------------------------ items

    private static ItemStack filler(Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(CrateConfig.text(" "));
        meta.setHideTooltip(true);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(CrateConfig.text(name));
        meta.lore(lore.stream().map(CrateConfig::text).toList());
        item.setItemMeta(meta);
        return item;
    }
}
