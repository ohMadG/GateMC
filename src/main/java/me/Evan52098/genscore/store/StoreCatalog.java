package me.Evan52098.genscore.store;

import me.Evan52098.genscore.menu.Lore;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;

record StoreCatalog(String title, String headlessToken, String completeUrl, List<Category> categories,
                    Map<String, Product> products, List<Tier> tiers) {

    record Icon(Material material, @Nullable NamespacedKey model, String name, boolean glint) {
        ItemStack item(List<String> lore) {
            ItemStack item = Lore.item(material, name, lore);
            item.editMeta(meta -> {
                meta.addItemFlags(ItemFlag.values());
                if (model != null) {
                    meta.setItemModel(model);
                }
                if (glint) {
                    meta.setEnchantmentGlintOverride(true);
                }
            });
            return item;
        }
    }

    record Badge(String text, TextColor color) {
    }

    record Content(String art, String name, int amount) {
    }

    record Tier(long cents, String name, String art, TextColor color) {
    }

    record Category(String id, String name, List<String> label, TextColor color, String art, Icon icon,
                    List<Product> products) {
    }

    record Product(String id, String category, String name, String fullName, TextColor color, TextColor titleColor,
                   long cents, String art, Icon icon, @Nullable Badge badge, List<String> highlights,
                   List<Content> contents, List<String> lore, String view, long packageId, boolean subscription,
                   int max) {
    }

    static StoreCatalog load(File file, Logger log) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection checkout = y.getConfigurationSection("checkout");
        List<Category> categories = new ArrayList<>();
        Map<String, Product> products = new LinkedHashMap<>();
        ConfigurationSection cats = y.getConfigurationSection("categories");
        if (cats != null) {
            for (String id : cats.getKeys(false)) {
                ConfigurationSection c = cats.getConfigurationSection(id);
                if (c == null) {
                    continue;
                }
                if (categories.size() >= 5) {
                    log.warning("[store] Only 5 store categories fit in the menu; skipped " + id);
                    continue;
                }
                TextColor color = color(c.getString("color"), TextColor.color(0xE5484D));
                List<Product> list = new ArrayList<>();
                ConfigurationSection ps = c.getConfigurationSection("products");
                if (ps != null) {
                    for (String pid : ps.getKeys(false)) {
                        ConfigurationSection p = ps.getConfigurationSection(pid);
                        if (p == null) {
                            continue;
                        }
                        Product product = product(id + "." + pid, id, p, color, log);
                        list.add(product);
                        products.put(product.id(), product);
                    }
                }
                String name = c.getString("name", id);
                List<String> label = new ArrayList<>(c.isList("label") ? c.getStringList("label")
                        : List.of(c.getString("label", name.toUpperCase(Locale.ROOT))));
                while (label.size() > 2) {
                    label.remove(label.size() - 1);
                }
                categories.add(new Category(id, name, List.copyOf(label), color, c.getString("art", "item/bundle"),
                        icon(c, "icon", name, log), List.copyOf(list)));
            }
        }
        List<Tier> tiers = new ArrayList<>();
        for (Map<?, ?> m : y.getMapList("cart-rewards")) {
            Object at = m.get("at");
            if (!(at instanceof Number n) || tiers.size() >= 2) {
                continue;
            }
            Object c = m.get("color");
            tiers.add(new Tier(StoreSettings.cents(n.doubleValue()), String.valueOf(m.get("name")),
                    String.valueOf(m.get("art")), color(c == null ? null : c.toString(), TextColor.color(0xFFFFFF))));
        }
        tiers.sort(Comparator.comparingLong(Tier::cents));
        return new StoreCatalog(
                y.getString("title", "Store"),
                checkout == null ? "" : checkout.getString("headless-token", "").trim(),
                checkout == null ? "" : checkout.getString("complete-url", "").trim(),
                List.copyOf(categories), Map.copyOf(products), List.copyOf(tiers));
    }

    private static Product product(String key, String category, ConfigurationSection p, TextColor fallback, Logger log) {
        String name = p.getString("name", p.getName());
        String fullName = p.getString("full-name", name);
        TextColor color = color(p.getString("color"), fallback);
        TextColor title = color(p.getString("title-color"), color);
        Badge badge = null;
        ConfigurationSection b = p.getConfigurationSection("badge");
        if (b != null && !b.getString("text", "").isBlank()) {
            badge = new Badge(b.getString("text").toUpperCase(Locale.ROOT), color(b.getString("color"), color));
        }
        List<Content> contents = new ArrayList<>();
        for (Map<?, ?> m : p.getMapList("contents")) {
            if (contents.size() >= 5) {
                break;
            }
            Object amount = m.get("amount");
            Object n = m.get("name");
            contents.add(new Content(String.valueOf(m.get("art")), n == null ? fullName : n.toString(),
                    amount instanceof Number a ? a.intValue() : 1));
        }
        List<String> highlights = new ArrayList<>(p.getStringList("highlights"));
        while (highlights.size() > 9) {
            highlights.remove(highlights.size() - 1);
        }
        String hex = "&#" + String.format("%06X", title.value());
        return new Product(key, category, name, fullName, color, title,
                StoreSettings.cents(p.getDouble("price", 0)), p.getString("art", "item/bundle"),
                icon(p, "icon", hex + "&l" + fullName, log), badge, List.copyOf(highlights), List.copyOf(contents),
                List.copyOf(p.getStringList("lore")), p.getString("view-button", "View"),
                Math.max(0, p.getLong("package", 0)), p.getBoolean("subscription", false),
                Math.max(1, Math.min(64, p.getInt("max", 1))));
    }

    private static Icon icon(ConfigurationSection s, String path, String name, Logger log) {
        ConfigurationSection i = s.getConfigurationSection(path);
        if (i == null) {
            return new Icon(material(s.getString(path, "CHEST"), log), null, name, false);
        }
        return new Icon(material(i.getString("material", "CHEST"), log), key(i.getString("model")), name,
                i.getBoolean("glint", false));
    }

    private static Material material(String name, Logger log) {
        Material m = Material.matchMaterial(name == null ? "" : name);
        if (m == null || !m.isItem() || m.isAir()) {
            log.warning("[store] Unknown item " + name + " in store.yml; using CHEST.");
            return Material.CHEST;
        }
        return m;
    }

    private static @Nullable NamespacedKey key(@Nullable String s) {
        if (s == null || s.isBlank() || !Key.parseable(s)) {
            return null;
        }
        return NamespacedKey.fromString(s);
    }

    static TextColor color(@Nullable String hex, TextColor fallback) {
        if (hex == null) {
            return fallback;
        }
        TextColor c = TextColor.fromHexString(hex.startsWith("#") ? hex : "#" + hex);
        return c == null ? fallback : c;
    }
}
