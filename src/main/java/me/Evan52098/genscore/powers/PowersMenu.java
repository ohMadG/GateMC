package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class PowersMenu {

    private static final int PER_PAGE = 21;
    private static final long CLICK_MS = 400;
    private static final int SHIMMER_STEPS = 4;
    private static final Material[] PANES = {Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE,
            Material.PURPLE_STAINED_GLASS_PANE, Material.MAGENTA_STAINED_GLASS_PANE, Material.WHITE_STAINED_GLASS_PANE};
    private static final Material[] OP_PANES = {Material.RED_STAINED_GLASS_PANE, Material.ORANGE_STAINED_GLASS_PANE,
            Material.BLACK_STAINED_GLASS_PANE, Material.YELLOW_STAINED_GLASS_PANE};

    private final PowersModule m;
    private final Cooldowns clicks = new Cooldowns();

    PowersMenu(PowersModule m) {
        this.m = m;
    }

    void open(Player p, boolean op) {
        open(p, op, 0, true);
    }

    void open(Player p, boolean op, int page) {
        open(p, op, page, false);
    }

    private void open(Player p, boolean op, int page, boolean animate) {
        PowersSettings s = m.settings();
        PowerData d = m.data(p.getUniqueId());
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        List<PowerDef> shown = new ArrayList<>();
        for (PowerDef def : s.powers().values()) {
            if (def.op() == op) {
                shown.add(def);
            }
        }
        int pages = Math.max(1, (shown.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        int onPage = Math.min(PER_PAGE, Math.max(0, shown.size() - pg * PER_PAGE));
        int powerRows = Math.max(1, (onPage + 6) / 7);
        int firstRow = powerRows >= 3 ? 1 : 2;
        int rows = firstRow + powerRows + 2;
        Menu menu = new Menu(rows, MenuStyle.title(op ? "Super Powers" : "Powers", pages > 1 ? "Page " + (pg + 1) + "/" + pages : null));

        long balance = m.service().balance(p.getUniqueId(), op);
        boolean free = !op && s.firstFree() && !d.freeUsed();
        menu.set(4, item(op ? Material.WITHER_SKELETON_SKULL : Material.AMETHYST_SHARD, op ? "&6Super Powers" : "&bPowers", op
                ? Lore.builder(Lore.Accent.RED).subcategory("Bought with credits")
                        .description("The strongest powers.", "Get credits at &6/store&7.")
                        .divider().stat("Your credits", PowersService.fmt(balance)).build()
                : Lore.builder(Lore.Accent.CYAN).subcategory("Bought with tokens")
                        .description("Buy a power once, keep it forever.", "One power is active at a time;", "switching is free.")
                        .divider().stat("Your tokens", PowersService.fmt(balance))
                        .description(free ? "&aYour first power is free!" : "").build(), false));

        int[] slots = MenuStyle.symmetric(onPage, firstRow);
        boolean[] ready = {!animate || onPage == 0};
        for (int k = 0; k < onPage; k++) {
            PowerDef def = shown.get(pg * PER_PAGE + k);
            menu.set(slots[k], icon(d, def, free), click -> {
                if (!ready[0] || !clicks.tryStart(p.getUniqueId(), "powers-menu", CLICK_MS)) {
                    return;
                }
                m.service().click(p, def);
                reopen(p, op, pg);
            });
        }
        if (shown.isEmpty()) {
            menu.set(22, item(Material.STRUCTURE_VOID, "&7No powers yet", List.of(), false));
        }

        UUID uuid = p.getUniqueId();
        ItemStack offItem;
        java.util.function.Consumer<org.bukkit.event.inventory.InventoryClickEvent> offClick = null;
        if (d.active() != null) {
            offItem = item(Material.LAVA_BUCKET, "&cTurn Off Power", Lore.builder(Lore.Accent.RED)
                    .description("Switch your active power off.").clickPrompt("Click to turn off").build(), false);
            offClick = c -> {
                if (clicks.tryStart(uuid, "powers-menu", CLICK_MS)) {
                    m.service().turnOff(p);
                    reopen(p, op, pg);
                }
            };
        } else {
            offItem = item(Material.GRAY_DYE, "&7No Active Power", Lore.builder(Lore.Accent.WHITE)
                    .description("Click an owned power", "to make it active.").build(), false);
        }
        if (pg > 0) {
            menu.set(MenuStyle.shelf(rows, 3), MenuStyle.previous(pg, pages), c -> reopen(p, op, pg - 1));
        }
        if (pg < pages - 1) {
            menu.set(MenuStyle.shelf(rows, 5), MenuStyle.next(pg + 2, pages), c -> reopen(p, op, pg + 1));
        }
        if (op) {
            menu.set(MenuStyle.shelf(rows, 0), MenuStyle.back("the normal powers"), c -> reopen(p, false, 0));
            menu.set(MenuStyle.shelf(rows, 4), offItem, offClick);
            menu.set(MenuStyle.shelf(rows, 8), MenuStyle.close(), c -> p.closeInventory());
        } else {
            menu.set(MenuStyle.shelf(rows, 1), item(Material.WITHER_SKELETON_SKULL, s.opMenuTitle(), Lore.builder(Lore.Accent.RED)
                    .description("Paid powers from the store.").clickPrompt("Click to view").build(), true), c -> reopen(p, true, 0, true));
            menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), c -> p.closeInventory());
            menu.set(MenuStyle.shelf(rows, 7), offItem, offClick);
        }
        MenuStyle.frame(menu, rows);
        menu.open(p);
        if (animate) {
            animate(p, menu.getInventory(), op, slots, onPage, ready);
        }
    }

    private void reopen(Player p, boolean op, int page) {
        reopen(p, op, page, false);
    }

    private void reopen(Player p, boolean op, int page, boolean animate) {
        Tasks.entity.run(p, () -> {
            if (p.isOnline()) {
                open(p, op, page, animate);
            }
        });
    }

    private void animate(Player p, Inventory inv, boolean op, int[] slots, int count, boolean[] ready) {
        Material[] panes = op ? OP_PANES : PANES;
        ItemStack[] icons = new ItemStack[count];
        for (int k = 0; k < count; k++) {
            icons[k] = inv.getItem(slots[k]);
            inv.setItem(slots[k], item(panes[k % panes.length], " ", List.of(), false));
        }
        if (op) {
            p.playSound(p, Sound.BLOCK_END_PORTAL_SPAWN, 0.35f, 1.4f);
            p.playSound(p, Sound.ENTITY_WITHER_SHOOT, 0.4f, 0.7f);
        } else {
            p.playSound(p, Sound.BLOCK_BEACON_ACTIVATE, 0.8f, 1.6f);
            p.playSound(p, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1f, 1.2f);
        }
        if (count == 0) {
            return;
        }
        int[] step = {0};
        Tasks.entity.timer(p, (ScheduledTask task) -> {
            if (!p.isOnline() || p.getOpenInventory().getTopInventory() != inv) {
                ready[0] = true;
                task.cancel();
                return;
            }
            int n = step[0]++;
            int revealed = Math.max(0, n - SHIMMER_STEPS + 1);
            for (int k = revealed; k < count; k++) {
                inv.setItem(slots[k], item(panes[(k + n) % panes.length], " ", List.of(), false));
            }
            if (n < SHIMMER_STEPS) {
                p.playSound(p, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.7f, 0.8f + n * 0.25f);
                return;
            }
            int k = n - SHIMMER_STEPS;
            inv.setItem(slots[k], icons[k]);
            float pitch = 0.6f + 1.4f * k / Math.max(1, count - 1);
            p.playSound(p, op ? Sound.BLOCK_NOTE_BLOCK_BASS : Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, pitch);
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_CHIME, 0.35f, pitch);
            if (k == count - 1) {
                ready[0] = true;
                task.cancel();
                if (op) {
                    p.playSound(p, Sound.ENTITY_ENDER_DRAGON_GROWL, 0.35f, 1.3f);
                    p.playSound(p, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.3f, 1.6f);
                } else {
                    p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.5f, 1.4f);
                    p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.8f);
                }
            }
        }, 1, 1);
    }

    private ItemStack icon(PowerData d, PowerDef def, boolean free) {
        boolean owned = d.owns(def.id());
        boolean active = def.id().equals(d.active());
        Lore lore = Lore.builder(def.op() ? Lore.Accent.RED : Lore.Accent.CYAN).subcategory(switch (def.kind()) {
            case ACTIVE -> "Active power";
            case TRIGGERED -> "Triggered power";
            case PASSIVE -> "Passive power";
        });
        for (String line : def.lore()) {
            lore.description(line);
        }
        lore.divider();
        switch (def.kind()) {
            case ACTIVE -> {
                lore.stat("Use", "double-tap sneak");
                if (def.cooldownMs() > 0) {
                    lore.stat("Cooldown", Cooldowns.format(def.cooldownMs()));
                }
            }
            case TRIGGERED -> lore.stat("Works", "by itself" + (def.cooldownMs() > 0 ? ", every " + Cooldowns.format(def.cooldownMs()) : ""));
            case PASSIVE -> lore.stat("Works", "always, while active");
        }
        if (!def.enabled()) {
            lore.divider().description("&cTurned off");
        } else if (active) {
            lore.stat("Status", "&aActive").clickPrompt("Click to turn off");
        } else if (owned) {
            lore.stat("Status", "&aOwned").clickPrompt("Click to make active");
        } else if (free) {
            lore.stat("Cost", "&aFree &7(your first power)").clickPrompt("Click to unlock");
        } else {
            lore.stat("Cost", PowersService.fmt(def.cost()) + (def.op() ? " &6credits" : " &etokens")).clickPrompt("Click to buy");
        }
        return model(item(def.material(), def.name(), lore.build(), active), def.id());
    }

    private ItemStack model(ItemStack item, String id) {
        String model = m.settings().iconModel();
        NamespacedKey key = model == null || model.isBlank() ? null : NamespacedKey.fromString(model);
        if (key == null) {
            return item;
        }
        item.editMeta(meta -> {
            meta.setItemModel(key);
            CustomModelDataComponent data = meta.getCustomModelDataComponent();
            data.setStrings(List.of(id));
            meta.setCustomModelDataComponent(data);
        });
        return item;
    }

    private static ItemStack item(Material material, String name, List<String> lore, boolean glow) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(Text.of(name));
        List<Component> lines = new ArrayList<>();
        for (String line : lore) {
            if (line != null && !(line.isEmpty() && lines.isEmpty())) {
                lines.add(Text.of(line));
            }
        }
        meta.lore(lines);
        meta.addItemFlags(ItemFlag.values());
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        item.setItemMeta(meta);
        return item;
    }
}
