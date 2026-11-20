package me.Evan52098.genscore.trade;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * One player's view of a trade: a 27-slot window drawn from the session's snapshot, in BlockvilleCore's TradeGui layout
 * (the first player's offer on the left, the second's on the right, their ready dyes at the top and bottom of the middle
 * column, the status in the centre). It is never edited by the player: every click is cancelled and interpreted
 * (checklist 4). Created, drawn and read only on the viewer's own thread.
 */
final class TradeView implements InventoryHolder {

    static final int SIZE = 27;
    static final int[] SLOTS_A = {0, 1, 2, 3, 9, 10, 11, 12, 18, 19, 20, 21};
    static final int[] SLOTS_B = {5, 6, 7, 8, 14, 15, 16, 17, 23, 24, 25, 26};
    static final int READY_SLOT_A = 4;
    static final int READY_SLOT_B = 22;
    static final int INFO_SLOT = 13;

    final TradeSession session;
    final UUID viewer;
    private final Inventory inventory;

    TradeView(TradeSession session, UUID viewer) {
        this.session = session;
        this.viewer = viewer;
        this.inventory = Bukkit.createInventory(this, SIZE, Msg.parse("<dark_gray>Trading"));
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    /** Index into the viewer's own offer for a raw top slot, or -1. */
    int ownIndex(int rawSlot) {
        int[] own = session.isA(viewer) ? SLOTS_A : SLOTS_B;
        for (int i = 0; i < own.length; i++) {
            if (own[i] == rawSlot) {
                return i;
            }
        }
        return -1;
    }

    int ownReadySlot() {
        return session.isA(viewer) ? READY_SLOT_A : READY_SLOT_B;
    }

    void render() {
        TradeSession.Snapshot s = session.snapshot();
        draw(SLOTS_A, s.offerA());
        draw(SLOTS_B, s.offerB());
        inventory.setItem(READY_SLOT_A, readyItem(s.readyA(), s.nameA()));
        inventory.setItem(READY_SLOT_B, readyItem(s.readyB(), s.nameB()));
        inventory.setItem(INFO_SLOT, infoItem(s));
    }

    private void draw(int[] slots, List<ItemStack> items) {
        for (int i = 0; i < slots.length; i++) {
            inventory.setItem(slots[i], i < items.size() ? items.get(i) : null);
        }
    }

    private static ItemStack readyItem(boolean ready, String ownerName) {
        Lore.Accent accent = ready ? Lore.Accent.RED : Lore.Accent.GREEN;
        String name = accent.code() + "&l" + ownerName + ": " + (ready ? "READY" : "NOT READY");
        List<String> lore = ready
                ? Lore.builder(accent).subcategory("Trade").divider().description("You're locked in.").divider().clickPrompt("Click to Edit Offer").build()
                : Lore.builder(accent).subcategory("Trade").divider().description("Confirm when you're happy", "with your offer.").divider()
                .clickPrompt("Click to Confirm").build();
        ItemStack item = Lore.item(ready ? Material.LIME_DYE : Material.YELLOW_DYE, name, lore);
        if (ready) {
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.setEnchantmentGlintOverride(true);
                item.setItemMeta(meta);
            }
        }
        return item;
    }

    private static ItemStack infoItem(TradeSession.Snapshot s) {
        if (s.countdown() > 0) {
            int left = s.countdown();
            List<String> lore = Lore.builder(Lore.Accent.GREEN).subcategory("Trade").divider()
                    .description("Both of you are ready.", "Change your offer or click your dye", "to cancel.").build();
            ItemStack clock = Lore.item(Material.CLOCK, Lore.Accent.GREEN.code() + "&lTrading in " + left + "...", lore);
            clock.setAmount(Math.max(1, Math.min(64, left)));
            return clock;
        }
        List<String> lore = Lore.builder(Lore.Accent.GREEN).subcategory("Trade").divider()
                .stat(s.nameA(), s.readyA() ? "Ready" : "Not Ready")
                .stat(s.nameB(), s.readyB() ? "Ready" : "Not Ready")
                .divider()
                .description("Both must be ready to", "complete the trade.")
                .divider()
                .description("Click an item in your inventory", "to offer it (right-click: one).", "Click your offered item to take it back.")
                .build();
        return Lore.item(Material.PAPER, Lore.Accent.GREEN.code() + "&lTrade Status", lore);
    }
}
