package me.Evan52098.genscore.info;

import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Configurable chest menus in HxCore's {@code menus:} shape: a title, a size, and items with slot, material, name,
 * lore, glow, an optional item model ({@code model: 'namespace:path'}) and an optional {@code command} the player runs
 * when clicking. A menu can also have {@code chat:} lines, shown instead of the chest to Bedrock players (and to
 * everyone when the menu has no items).
 */
final class InfoMenus {

    record Item(int slot, Material material, String name, List<String> lore, boolean glow, @Nullable NamespacedKey model,
                @Nullable String command) {
    }

    record Page(String id, String title, int rows, List<Item> items, List<String> chat) {
    }

    /** Commands a menu button may run: letters, digits, spaces and a few symbols, no colour or control characters. */
    private static final Pattern SAFE_COMMAND = Pattern.compile("[A-Za-z0-9 _:.\\-]{1,100}");

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final Cooldowns clicks = new Cooldowns();

    void load(@Nullable ConfigurationSection menus, Logger log) {
        pages.clear();
        if (menus == null) {
            return;
        }
        for (String id : menus.getKeys(false)) {
            ConfigurationSection s = menus.getConfigurationSection(id);
            if (s == null) {
                continue;
            }
            int size = s.getInt("size", 54);
            int rows = Math.max(1, Math.min(6, (size + 8) / 9));
            List<Item> items = new ArrayList<>();
            ConfigurationSection is = s.getConfigurationSection("items");
            if (is != null) {
                for (String key : is.getKeys(false)) {
                    ConfigurationSection i = is.getConfigurationSection(key);
                    if (i == null) {
                        continue;
                    }
                    Material mat = Material.matchMaterial(i.getString("material", "PAPER"));
                    if (mat == null || !mat.isItem() || mat.isAir()) {
                        log.warning("[info] Menu " + id + " item " + key + ": unknown material " + i.getString("material"));
                        continue;
                    }
                    int slot = i.getInt("slot", -1);
                    if (slot < 0 || slot >= rows * 9) {
                        log.warning("[info] Menu " + id + " item " + key + ": slot " + slot + " is outside the menu");
                        continue;
                    }
                    String model = i.getString("model", "");
                    NamespacedKey modelKey = model == null || model.isBlank() ? null : NamespacedKey.fromString(model.toLowerCase(Locale.ROOT));
                    String command = i.getString("command", "");
                    if (command != null) {
                        command = command.trim();
                        if (command.startsWith("/")) {
                            command = command.substring(1);
                        }
                    }
                    if (command != null && !command.isEmpty() && !SAFE_COMMAND.matcher(command).matches()) {
                        log.warning("[info] Menu " + id + " item " + key + ": command ignored (unexpected characters)");
                        command = null;
                    }
                    items.add(new Item(slot, mat, i.getString("name", ""), i.getStringList("lore"), i.getBoolean("glow", false),
                            modelKey, command == null || command.isEmpty() ? null : command));
                }
            }
            pages.put(id.toLowerCase(Locale.ROOT), new Page(id, s.getString("title", id), rows, List.copyOf(items),
                    List.copyOf(s.getStringList("chat"))));
        }
    }

    @Nullable Page page(String id) {
        return pages.get(id.toLowerCase(Locale.ROOT));
    }

    /** Opens a menu (or its chat version). Call on the player's thread. */
    boolean show(Player player, String id) {
        Page page = page(id);
        if (page == null) {
            return false;
        }
        boolean chat = page.items().isEmpty() || (!page.chat().isEmpty() && Bedrock.is(player.getUniqueId()));
        if (chat) {
            for (String line : page.chat()) {
                player.sendMessage(Text.legacy(line));
            }
            return true;
        }
        Menu menu = new Menu(page.rows(), Text.legacy(page.title()));
        for (Item item : page.items()) {
            ItemStack stack = build(item);
            if (item.command() == null) {
                menu.set(item.slot(), stack);
                continue;
            }
            String command = item.command();
            menu.set(item.slot(), stack, click -> {
                // Clicks arrive on the player's thread. Rate limited so a held click can't spam commands (checklist 10).
                if (!clicks.tryStart(player.getUniqueId(), "menu", 300)) {
                    return;
                }
                // Next tick on the player's thread: closing an inventory inside its own click event is unsafe.
                Tasks.entity.run(player, () -> {
                    player.closeInventory();
                    player.performCommand(command);
                });
            });
        }
        menu.open(player);
        return true;
    }

    private static ItemStack build(Item item) {
        ItemStack stack = new ItemStack(item.material());
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(Text.item(item.name()));
        List<Component> lore = new ArrayList<>(item.lore().size());
        for (String line : item.lore()) {
            lore.add(Text.item(line));
        }
        meta.lore(lore);
        if (item.glow()) {
            meta.setEnchantmentGlintOverride(true);
        }
        if (item.model() != null) {
            meta.setItemModel(item.model());
        }
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    void forget(Player player) {
        clicks.clear(player.getUniqueId(), "menu");
    }
}
