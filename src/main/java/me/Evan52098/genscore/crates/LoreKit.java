package me.Evan52098.genscore.crates;

import me.Evan52098.genscore.menu.Lore;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * The parts of blockville-common's StandardLore that {@link Lore} does not have (bullet, detail, status prompt, a
 * coloured stat, a text progress bar), in the same style. Used by the lockbox and daily menus. Lines use {@code &} codes.
 * The pixel-art progress bar needed the Blockville resource pack, so the Bedrock text bar is used for everyone.
 */
public final class LoreKit {

    private final Lore.Accent accent;
    private final List<String> lines = new ArrayList<>();

    private LoreKit(Lore.Accent accent) {
        this.accent = accent;
    }

    public static LoreKit builder(Lore.Accent accent) {
        return new LoreKit(accent);
    }

    public LoreKit subcategory(String text) {
        lines.add("&8" + Lore.smallCaps(text));
        lines.add("");
        return this;
    }

    public LoreKit divider() {
        lines.add("");
        return this;
    }

    public LoreKit description(String... wrapped) {
        for (String line : wrapped) {
            lines.add("&7" + line.replace("&f", "&7"));
        }
        return this;
    }

    public LoreKit bullet(String text) {
        String bare = text.replaceAll("^(?:&[0-9a-fk-orA-FK-OR]|&#[0-9a-fA-F]{6})+", "");
        String code = accent == Lore.Accent.WHITE ? "&7" : accent.code();
        lines.add(bare.startsWith("✔") || bare.startsWith("✘") ? text : code + "• &f" + text);
        return this;
    }

    public LoreKit detail(String text) {
        lines.add("&7" + text);
        return this;
    }

    public LoreKit stat(String label, String value) {
        String valueColor = accent == Lore.Accent.WHITE ? "&e" : accent.code();
        return stat(label, value, valueColor);
    }

    public LoreKit stat(String label, String value, String valueColor) {
        lines.add(accent.code() + "• &f" + label + ": " + valueColor + value);
        return this;
    }

    public LoreKit progressBar(double fraction) {
        double f = Math.max(0.0, Math.min(1.0, fraction));
        int filled = (int) Math.round(f * 10);
        lines.add("&a" + "█".repeat(filled) + "&8" + "█".repeat(10 - filled) + " &7" + (int) (f * 100) + "%");
        return this;
    }

    public LoreKit clickPrompt(String action) {
        lines.add("");
        lines.add("&e" + Lore.smallCaps(action));
        return this;
    }

    public LoreKit statusPrompt(String text) {
        lines.add("");
        lines.add(accent.code() + Lore.smallCaps(text));
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
        while (!out.isEmpty() && out.get(0).isEmpty()) {
            out.remove(0);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).isEmpty()) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    /** Makes an item shimmer (BlockvilleCore's ProgressionGui.glint). */
    public static ItemStack glint(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setEnchantmentGlintOverride(true);
            item.setItemMeta(meta);
        }
        return item;
    }

    public static ItemStack filler() {
        return Lore.item(Material.GRAY_STAINED_GLASS_PANE, " ", List.of());
    }

    /** "1d 2h 3m 4s" (BlockvilleCore's TimeUtil.formatDuration). */
    public static String duration(long ms) {
        if (ms <= 0L) {
            return "0s";
        }
        long seconds = ms / 1000L;
        long days = seconds / 86400L;
        seconds %= 86400L;
        long hours = seconds / 3600L;
        seconds %= 3600L;
        long minutes = seconds / 60L;
        seconds %= 60L;
        StringBuilder sb = new StringBuilder(16);
        if (days > 0L) {
            sb.append(days).append("d ");
        }
        if (hours > 0L) {
            sb.append(hours).append("h ");
        }
        if (minutes > 0L) {
            sb.append(minutes).append("m ");
        }
        if (seconds > 0L || sb.isEmpty()) {
            sb.append(seconds).append("s");
        }
        return sb.toString().trim();
    }
}
