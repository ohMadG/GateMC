package me.Evan52098.genscore.menu;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * A chest menu where every click is cancelled and buttons run code. GensCore's replacement for the InventoryFramework
 * ChestGui that BlockvilleCore uses: plain Paper inventories, so it is Folia-safe (clicks arrive on the clicking
 * player's thread). Register {@link #listener()} once.
 *
 * <pre>
 * Menu menu = new Menu(4, Msg.parse("&lt;gray&gt;Title"));
 * menu.set(13, item, click -&gt; ...);
 * menu.open(player);
 * </pre>
 */
public final class Menu implements InventoryHolder {

    private final Inventory inventory;
    private final Map<Integer, Consumer<InventoryClickEvent>> buttons = new HashMap<>();

    public Menu(int rows, Component title) {
        this.inventory = Bukkit.createInventory(this, Math.max(1, Math.min(6, rows)) * 9, title);
    }

    /** An item that does nothing when clicked. */
    public Menu set(int slot, @Nullable ItemStack item) {
        return set(slot, item, null);
    }

    /** A button. Slot = x + y * 9. */
    public Menu set(int slot, @Nullable ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
        if (slot < 0 || slot >= inventory.getSize()) {
            return this;
        }
        inventory.setItem(slot, item);
        if (onClick != null) {
            buttons.put(slot, onClick);
        } else {
            buttons.remove(slot);
        }
        return this;
    }

    public Menu set(int x, int y, @Nullable ItemStack item, @Nullable Consumer<InventoryClickEvent> onClick) {
        return set(x + y * 9, item, onClick);
    }

    /** Opens the menu. Call on the player's thread. */
    public void open(Player player) {
        player.openInventory(inventory);
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    private static final Listener LISTENER = new Listener() {
        @EventHandler(ignoreCancelled = true)
        public void onClick(InventoryClickEvent event) {
            if (!(event.getView().getTopInventory().getHolder() instanceof Menu menu)) {
                return;
            }
            event.setCancelled(true);
            if (event.getClickedInventory() == menu.inventory) {
                Consumer<InventoryClickEvent> button = menu.buttons.get(event.getSlot());
                if (button != null) {
                    button.accept(event);
                }
            }
        }

        @EventHandler(ignoreCancelled = true)
        public void onDrag(InventoryDragEvent event) {
            if (event.getView().getTopInventory().getHolder() instanceof Menu) {
                event.setCancelled(true);
            }
        }
    };

    public static Listener listener() {
        return LISTENER;
    }
}
