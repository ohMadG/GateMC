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

    private static final java.util.Set<java.util.UUID> PACKED = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private record Return(String label, Consumer<Player> action, long until) {
    }

    private static final Map<java.util.UUID, Return> RETURNS = new java.util.concurrent.ConcurrentHashMap<>();

    public static void setReturn(Player player, String label, Consumer<Player> action) {
        RETURNS.put(player.getUniqueId(), new Return(label, action, System.currentTimeMillis() + 10 * 60_000L));
    }

    public static void clearReturn(Player player) {
        RETURNS.remove(player.getUniqueId());
    }

    private void addReturn(Player player) {
        Return r = RETURNS.get(player.getUniqueId());
        if (r == null) {
            return;
        }
        if (r.until() < System.currentTimeMillis()) {
            RETURNS.remove(player.getUniqueId(), r);
            return;
        }
        int slot = (rows - 1) * 9;
        if (buttons.containsKey(slot)) {
            return;
        }
        ItemStack there = inventory.getItem(slot);
        if (there != null && !there.isEmpty() && !there.getType().name().endsWith("STAINED_GLASS_PANE")) {
            return;
        }
        set(slot, MenuStyle.back(r.label()), click -> {
            RETURNS.remove(player.getUniqueId(), r);
            r.action().accept(player);
        });
    }
    private static final net.kyori.adventure.key.Key GUI_FONT = net.kyori.adventure.key.Key.key("genscore", "gui");

    private Inventory inventory;
    private final int rows;
    private final Component title;
    private boolean styled;
    private boolean custom;
    private boolean plain;
    private final Map<Integer, Consumer<InventoryClickEvent>> buttons = new HashMap<>();

    public Menu(int rows, Component title) {
        this.rows = Math.max(1, Math.min(6, rows));
        this.title = title;
        this.inventory = Bukkit.createInventory(this, this.rows * 9, title);
    }

    public Menu customTitle() {
        this.custom = true;
        return this;
    }

    /** Leave empty slots empty (menus that mirror a real inventory). */
    public Menu plain() {
        this.plain = true;
        return this;
    }

    public static boolean styledFor(Player player) {
        return PACKED.contains(player.getUniqueId()) && !me.Evan52098.genscore.util.Bedrock.is(player);
    }

    private Component styledTitle() {
        String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title);
        Component background = Component.text("\ue000" + (char) (0xE100 + rows) + "\ue001")
                .font(GUI_FONT).color(net.kyori.adventure.text.format.NamedTextColor.WHITE);
        return Component.empty().append(background)
                .append(Component.text(plain, net.kyori.adventure.text.format.TextColor.color(0xFFB347)));
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
    public boolean isEmpty(int slot) {
        ItemStack i = slot >= 0 && slot < inventory.getSize() ? inventory.getItem(slot) : null;
        return i == null || i.isEmpty();
    }

    public void open(Player player) {
        if (!custom && !plain) {
            MenuStyle.frame(this, rows);
        }
        addReturn(player);
        if (!styled && !custom) {
            Component themed = styledFor(player) ? styledTitle()
                    : MenuStyle.title(net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(title));
            Inventory fancy = Bukkit.createInventory(this, rows * 9, themed);
            fancy.setContents(inventory.getContents());
            inventory = fancy;
            styled = true;
        }
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

        @EventHandler
        public void onPack(org.bukkit.event.player.PlayerResourcePackStatusEvent event) {
            switch (event.getStatus()) {
                case SUCCESSFULLY_LOADED -> PACKED.add(event.getPlayer().getUniqueId());
                case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> PACKED.remove(event.getPlayer().getUniqueId());
                default -> {
                }
            }
        }

        @EventHandler
        public void onQuit(org.bukkit.event.player.PlayerQuitEvent event) {
            PACKED.remove(event.getPlayer().getUniqueId());
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
