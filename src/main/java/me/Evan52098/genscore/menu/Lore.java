package me.Evan52098.genscore.menu;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Menu item names and lore in the same style as blockville-common's StandardLore and GuiHelper.buildItem: a small-caps
 * grey subcategory, blank spacers, "• Label: value" stats, grey description lines and a yellow small-caps click
 * prompt. The resource-pack icons StandardLore used are left out (this server has no Blockville pack).
 * Lines use {@code &} colour codes, as in BlockvilleCore.
 */
public final class Lore {

    public enum Accent {
        CYAN("&b"), WHITE("&f"), GREEN("&a"), RED("&c");

        private final String code;

        Accent(String code) {
            this.code = code;
        }

        public String code() {
            return code;
        }
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final String SMALL_FROM = "abcdefghijklmnopqrstuvwxyz";
    private static final String SMALL_TO = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀѕᴛᴜᴠᴡxʏᴢ";

    private final Accent accent;
    private final List<String> lines = new ArrayList<>();

    private Lore(Accent accent) {
        this.accent = accent;
    }

    public static Lore builder(Accent accent) {
        return new Lore(accent);
    }

    public Lore subcategory(String text) {
        lines.add("&8" + smallCaps(text));
        lines.add("");
        return this;
    }

    public Lore divider() {
        lines.add("");
        return this;
    }

    public Lore description(String... wrapped) {
        for (String line : wrapped) {
            lines.add("&7" + line.replace("&f", "&7"));
        }
        return this;
    }

    public Lore stat(String label, String value) {
        String valueColor = accent == Accent.WHITE ? "&e" : accent.code();
        lines.add(accent.code() + "• &f" + label + ": " + valueColor + value);
        return this;
    }

    public Lore clickPrompt(String action) {
        lines.add("");
        lines.add("&e" + smallCaps(action));
        return this;
    }

    public List<String> build() {
        List<String> out = new ArrayList<>();
        for (String line : lines) {
            if (line.isEmpty() && !out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
                continue;
            }
            out.add(line);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    public static String smallCaps(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (char c : text.toLowerCase(Locale.ROOT).toCharArray()) {
            int i = SMALL_FROM.indexOf(c);
            sb.append(i >= 0 ? SMALL_TO.charAt(i) : c);
        }
        return sb.toString();
    }

    /** An item with a name and lore in {@code &} colour codes, not italic (like GuiHelper.buildItem). */
    public static ItemStack item(Material material, String name, List<String> lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(text(name));
        List<Component> lines = new ArrayList<>(lore.size());
        for (String line : lore) {
            lines.add(text(line));
        }
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }

    private static Component text(String legacy) {
        return LEGACY.deserialize(legacy).decoration(TextDecoration.ITALIC, false);
    }
}
