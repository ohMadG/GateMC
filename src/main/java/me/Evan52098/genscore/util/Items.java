package me.Evan52098.genscore.util;

import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Stores item lists as text (Paper's own item format, base64), which fits a TEXT column on PostgreSQL and SQLite. */
public final class Items {

    private Items() {
    }

    public static String encode(ItemStack[] items) {
        List<ItemStack> list = new ArrayList<>(items.length);
        for (ItemStack s : items) {
            list.add(s == null ? ItemStack.empty() : s);
        }
        return Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(list));
    }

    public static ItemStack[] decode(String text) {
        return ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(text));
    }

    /** Copies every non-empty stack. */
    public static List<ItemStack> nonEmpty(ItemStack[] items) {
        List<ItemStack> list = new ArrayList<>();
        for (ItemStack s : items) {
            if (s != null && !s.isEmpty()) {
                list.add(s.clone());
            }
        }
        return list;
    }
}
