package me.Evan52098.genscore.menu;

import me.Evan52098.genscore.util.Center;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public final class MenuStyle {

    public static final Material FRAME = Material.BLACK_STAINED_GLASS_PANE;
    public static final Material ACCENT = Material.ORANGE_STAINED_GLASS_PANE;

    private MenuStyle() {
    }

    private static final int TITLE_CENTER = 80;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder().character('&').hexCharacter('#').hexColors().build();

    public static Component title(String section, String page) {
        String text = plain(section);
        if (page != null && !page.isBlank()) {
            text += " - " + plain(page);
        }
        String caps = Lore.smallCaps(text);
        int pad = Math.max(0, Math.round((TITLE_CENTER - Center.width(caps) / 2f) / 4f));
        return Component.text(" ".repeat(pad)).append(Component.text(caps, NamedTextColor.DARK_GRAY, TextDecoration.UNDERLINED));
    }

    private static String plain(String text) {
        String stripped = text.replace('§', '&');
        return PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(stripped)).trim();
    }

    public static Component title(String section) {
        return title(section, null);
    }

    public static ItemStack pane(Material material) {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> meta.setHideTooltip(true));
        return item;
    }

    public static void frame(Menu menu, int rows) {
        int last = rows * 9 - 1;
        for (int slot = 0; slot <= last; slot++) {
            if (!menu.isEmpty(slot)) {
                continue;
            }
            boolean corner = slot == 0 || slot == 8 || slot == last - 8 || slot == last;
            menu.set(slot, pane(corner ? ACCENT : FRAME));
        }
    }

    public static ItemStack clean(ItemStack item, boolean glint) {
        item.editMeta(meta -> {
            meta.addItemFlags(ItemFlag.values());
            if (glint) {
                meta.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }

    public static ItemStack icon(Material material, String name, List<String> lore) {
        return clean(Lore.item(material, name, lore), false);
    }

    public static ItemStack back(String to) {
        return icon(Material.OAK_DOOR, "&fBack", Lore.builder(Lore.Accent.WHITE).description("To " + to + ".").clickPrompt("Click to go back").build());
    }

    public static ItemStack close() {
        return icon(Material.BARRIER, "&cClose", List.of());
    }

    public static ItemStack previous(int page, int pages) {
        return icon(Material.ARROW, "&fPrevious Page", Lore.builder(Lore.Accent.WHITE)
                .stat("Page", page + "/" + pages).clickPrompt("Click to go back a page").build());
    }

    public static ItemStack next(int page, int pages) {
        return icon(Material.SPECTRAL_ARROW, "&fNext Page", Lore.builder(Lore.Accent.WHITE)
                .stat("Page", page + "/" + pages).clickPrompt("Click for the next page").build());
    }

    public static int shelf(int rows, int col) {
        return (rows - 1) * 9 + col;
    }

    private static final int[][] ROW = {
            {}, {4}, {3, 5}, {2, 4, 6}, {1, 3, 5, 7}, {2, 3, 4, 5, 6}, {1, 2, 3, 5, 6, 7}, {1, 2, 3, 4, 5, 6, 7}};

    public static int[] symmetric(int count, int firstRow) {
        int[] out = new int[count];
        int i = 0;
        int row = firstRow;
        while (i < count) {
            int inRow = Math.min(7, count - i);
            for (int col : ROW[inRow]) {
                out[i++] = row * 9 + col;
            }
            row++;
        }
        return out;
    }

    public static int grid(int i) {
        return 10 + (i / 7) * 9 + (i % 7);
    }
}
