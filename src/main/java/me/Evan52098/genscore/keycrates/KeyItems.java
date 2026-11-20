package me.Evan52098.genscore.keycrates;

import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Physical crate keys. A key is recognised only by the crate id in its PersistentDataContainer
 * ({@code genscore:crate_key}), never by its name, lore or material: a renamed tripwire hook is worth nothing, and
 * players cannot write that tag in survival (checklist 9). Creative-mode copies are a staff matter (see group-f.md).
 *
 * <p>All methods that take an inventory must run on the owning player's thread.
 */
final class KeyItems {

    private final NamespacedKey tag;

    KeyItems(Plugin plugin) {
        this.tag = new NamespacedKey(plugin, "crate_key");
    }

    /** Key stacks for {@code amount} keys, split at the material's stack size. */
    List<ItemStack> create(CrateConfig.Crate crate, int amount) {
        ItemStack one = new ItemStack(crate.keyMaterial());
        ItemMeta meta = one.getItemMeta();
        meta.displayName(CrateConfig.text(crate.keyName()));
        meta.lore(List.of(
                CrateConfig.text("&7Opens the " + crate.name() + "&7."),
                CrateConfig.text("&7Use it at a crate or in &f/crates&7.")));
        meta.setEnchantmentGlintOverride(true);
        if (crate.keyModel() != null) {
            meta.setItemModel(crate.keyModel());
        }
        meta.getPersistentDataContainer().set(tag, PersistentDataType.STRING, crate.id());
        one.setItemMeta(meta);
        int max = Math.max(1, one.getMaxStackSize());
        List<ItemStack> stacks = new ArrayList<>();
        int left = amount;
        while (left > 0) {
            ItemStack s = one.clone();
            s.setAmount(Math.min(max, left));
            left -= s.getAmount();
            stacks.add(s);
        }
        return stacks;
    }

    /** The crate id of a key item, or null if it is not a key. */
    @Nullable String crateOf(@Nullable ItemStack item) {
        if (item == null || item.isEmpty() || !item.hasItemMeta()) {
            return null;
        }
        return item.getItemMeta().getPersistentDataContainer().get(tag, PersistentDataType.STRING);
    }

    boolean isKey(@Nullable ItemStack item) {
        return crateOf(item) != null;
    }

    /** Keys for this crate anywhere in the player's inventory (main, hotbar, off-hand). */
    int count(PlayerInventory inv, String crateId) {
        int n = 0;
        ItemStack[] contents = inv.getContents();
        for (ItemStack s : contents) {
            if (crateId.equals(crateOf(s))) {
                n += s.getAmount();
            }
        }
        return n;
    }

    /**
     * Removes up to {@code amount} keys of this crate from the live inventory and returns how many were removed. Reads
     * the real inventory at this moment (checklist 9), so a stale menu or a dropped key can never be "spent" twice.
     */
    int remove(PlayerInventory inv, String crateId, int amount) {
        int left = amount;
        ItemStack[] contents = inv.getContents();
        for (int slot = 0; slot < contents.length && left > 0; slot++) {
            ItemStack s = contents[slot];
            if (!crateId.equals(crateOf(s))) {
                continue;
            }
            int take = Math.min(left, s.getAmount());
            left -= take;
            if (take >= s.getAmount()) {
                inv.setItem(slot, null);
            } else {
                ItemStack rest = s.clone();
                rest.setAmount(s.getAmount() - take);
                inv.setItem(slot, rest);
            }
        }
        return amount - left;
    }

    /**
     * Simulates adding {@code stack} to a copy of a player's storage slots: fills similar stacks first, then empty
     * slots. Mutates {@code slots} and returns how many items fit.
     */
    static int fit(ItemStack[] slots, ItemStack stack) {
        int left = stack.getAmount();
        int max = Math.max(1, stack.getMaxStackSize());
        for (int i = 0; i < slots.length && left > 0; i++) {
            ItemStack s = slots[i];
            if (s != null && !s.isEmpty() && s.isSimilar(stack) && s.getAmount() < max) {
                int add = Math.min(left, max - s.getAmount());
                s.setAmount(s.getAmount() + add);
                left -= add;
            }
        }
        for (int i = 0; i < slots.length && left > 0; i++) {
            if (slots[i] == null || slots[i].isEmpty()) {
                ItemStack put = stack.clone();
                put.setAmount(Math.min(left, max));
                slots[i] = put;
                left -= put.getAmount();
            }
        }
        return stack.getAmount() - left;
    }

    /** A deep copy of the player's 36 storage slots, for {@link #fit}. */
    static ItemStack[] storageCopy(PlayerInventory inv) {
        ItemStack[] src = inv.getStorageContents();
        ItemStack[] copy = new ItemStack[src.length];
        for (int i = 0; i < src.length; i++) {
            copy[i] = src[i] == null ? null : src[i].clone();
        }
        return copy;
    }
}
