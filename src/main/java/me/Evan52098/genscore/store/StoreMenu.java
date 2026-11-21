package me.Evan52098.genscore.store;

import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

final class StoreMenu {

    private final Supplier<StoreCatalog> catalog;
    private final Supplier<StoreSettings> settings;
    private final Supplier<StoreArt> art;
    private final Set<UUID> packLoaded;
    private final Logger log;
    private final StoreCheckout checkout = new StoreCheckout();
    private final Cooldowns cooldowns = new Cooldowns();
    private final Map<UUID, Map<String, Integer>> carts = new ConcurrentHashMap<>();
    private final Map<UUID, StoreDialog.View> views = new ConcurrentHashMap<>();

    StoreMenu(Supplier<StoreCatalog> catalog, Supplier<StoreSettings> settings, Supplier<StoreArt> art,
              Set<UUID> packLoaded, Logger log) {
        this.catalog = catalog;
        this.settings = settings;
        this.art = art;
        this.packLoaded = packLoaded;
        this.log = log;
    }

    void forget(UUID uuid) {
        carts.remove(uuid);
        views.remove(uuid);
    }

    void open(Player player, int category, int page) {
        StoreCatalog cat = catalog.get();
        if (cat.categories().isEmpty()) {
            player.sendMessage(Msg.bad("The store menu has no categories yet."));
            return;
        }
        int ci = Math.floorMod(category, cat.categories().size());
        Map<StoreCatalog.Product, Integer> cart = cart(player.getUniqueId(), cat);
        if (packLoaded.contains(player.getUniqueId()) && !me.Evan52098.genscore.util.Bedrock.is(player)) {
            StoreDialog.View view = new StoreDialog.View(ci, Math.max(0, page));
            views.put(player.getUniqueId(), view);
            player.showDialog(StoreDialog.build(cat, art.get(), settings.get(), view, cart));
        } else {
            plain(player, cat, ci, page, cart).open(player);
        }
    }

    void click(Player player, String path) {
        StoreCatalog cat = catalog.get();
        StoreDialog.View view = views.getOrDefault(player.getUniqueId(), new StoreDialog.View(0, 0));
        String[] p = path.split("/");
        switch (p[0]) {
            case "close" -> player.closeDialog();
            case "tab" -> {
                int i = parse(p, 1);
                if (i >= 0 && i < cat.categories().size()) {
                    player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                    open(player, i, 0);
                }
            }
            case "page", "back" -> open(player, parse(p, 1), parse(p, 2));
            case "view" -> {
                StoreCatalog.Product product = product(cat, p);
                if (product != null) {
                    player.showDialog(StoreDialog.details(product, settings.get(),
                            cart(player.getUniqueId(), cat).getOrDefault(product, 0), view));
                }
            }
            case "add" -> {
                StoreCatalog.Product product = product(cat, p);
                if (product != null) {
                    change(player, product, true);
                    open(player, view.category(), view.page());
                }
            }
            case "addback" -> {
                StoreCatalog.Product product = cat.products().get(p.length > 1 ? p[1] : "");
                if (product != null) {
                    change(player, product, true);
                    open(player, parse(p, 2), parse(p, 3));
                }
            }
            case "remove" -> {
                StoreCatalog.Product product = product(cat, p);
                if (product != null) {
                    change(player, product, false);
                    open(player, view.category(), view.page());
                }
            }
            case "checkout" -> checkout(player);
            default -> {
            }
        }
    }

    private static StoreCatalog.Product product(StoreCatalog cat, String[] p) {
        return p.length > 1 ? cat.products().get(p[1]) : null;
    }

    private static int parse(String[] p, int i) {
        try {
            return p.length > i ? Integer.parseInt(p[i]) : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private Map<StoreCatalog.Product, Integer> cart(UUID uuid, StoreCatalog cat) {
        Map<StoreCatalog.Product, Integer> out = new LinkedHashMap<>();
        Map<String, Integer> raw = carts.get(uuid);
        if (raw == null) {
            return out;
        }
        synchronized (raw) {
            raw.entrySet().removeIf(e -> !cat.products().containsKey(e.getKey()));
            for (Map.Entry<String, Integer> e : raw.entrySet()) {
                StoreCatalog.Product p = cat.products().get(e.getKey());
                out.put(p, Math.min(p.max(), e.getValue()));
            }
        }
        return out;
    }

    private boolean change(Player player, StoreCatalog.Product p, boolean add) {
        Map<String, Integer> raw = carts.computeIfAbsent(player.getUniqueId(), k -> new LinkedHashMap<>());
        boolean changed;
        synchronized (raw) {
            int qty = raw.getOrDefault(p.id(), 0);
            if (add) {
                changed = qty < p.max();
                if (changed) {
                    raw.put(p.id(), qty + 1);
                }
            } else {
                changed = qty > 0;
                if (qty <= 1) {
                    raw.remove(p.id());
                } else {
                    raw.put(p.id(), qty - 1);
                }
            }
        }
        if (changed) {
            player.playSound(player, add ? Sound.ENTITY_EXPERIENCE_ORB_PICKUP : Sound.ENTITY_ITEM_PICKUP, 0.6f, add ? 1.3f : 0.8f);
        } else {
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        }
        return changed;
    }

    private static long total(Map<StoreCatalog.Product, Integer> cart) {
        long t = 0;
        for (Map.Entry<StoreCatalog.Product, Integer> e : cart.entrySet()) {
            t += e.getKey().cents() * e.getValue();
        }
        return t;
    }

    private Menu plain(Player player, StoreCatalog cat, int ci, int page, Map<StoreCatalog.Product, Integer> cart) {
        StoreSettings s = settings.get();
        StoreCatalog.Category c = cat.categories().get(ci);
        Menu menu = new Menu(6, MenuStyle.title("Store", c.name()));
        for (int i = 0; i < cat.categories().size(); i++) {
            int target = i;
            menu.set(i * 9, tabIcon(cat.categories().get(i), i == ci), e -> {
                if (target != ci) {
                    player.playSound(player, Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                    open(player, target, 0);
                }
            });
        }
        int per = 14;
        int pages = Math.max(1, (c.products().size() + per - 1) / per);
        int pg = Math.max(0, Math.min(page, pages - 1));
        List<StoreCatalog.Product> list = c.products();
        for (int i = pg * per, n = 0; i < list.size() && n < per; i++, n++) {
            StoreCatalog.Product p = list.get(i);
            int slot = 11 + (n / 7) * 18 + (n % 7);
            int qty = cart.getOrDefault(p, 0);
            ItemStack icon = p.icon().item(productLore(s, p, qty));
            if (qty > 0) {
                icon.editMeta(m -> m.setEnchantmentGlintOverride(true));
            }
            menu.set(slot, icon, e -> {
                change(player, p, !e.isRightClick());
                open(player, ci, pg);
            });
        }
        menu.set(45, MenuStyle.close(), e -> player.closeInventory());
        if (pg > 0) {
            menu.set(46, MenuStyle.previous(pg + 1, pages), e -> open(player, ci, pg - 1));
        }
        if (pg < pages - 1) {
            menu.set(47, MenuStyle.next(pg + 1, pages), e -> open(player, ci, pg + 1));
        }
        List<String> lore = new ArrayList<>();
        if (cart.isEmpty()) {
            lore.add("&7Your cart is empty.");
        } else {
            for (Map.Entry<StoreCatalog.Product, Integer> e : cart.entrySet()) {
                lore.add("&8• &f" + (e.getValue() > 1 ? e.getValue() + "x " : "") + e.getKey().fullName() + " &7- &e"
                        + s.formatMoney(e.getKey().cents() * e.getValue()));
            }
            lore.add("");
            lore.add("&7Total: &6&l" + s.formatMoney(total(cart)));
            lore.add("");
            lore.add(me.Evan52098.genscore.menu.Lore.prompt("left-click to check out"));
            lore.add(me.Evan52098.genscore.menu.Lore.prompt("right-click to empty the cart"));
        }
        menu.set(53, MenuStyle.icon(cart.isEmpty() ? Material.MINECART : Material.CHEST_MINECART, "&a&lCheckout", lore), e -> {
            if (e.isRightClick()) {
                carts.remove(player.getUniqueId());
                open(player, ci, pg);
            } else {
                checkout(player);
            }
        });
        MenuStyle.frame(menu, 6);
        return menu;
    }

    private List<String> productLore(StoreSettings s, StoreCatalog.Product p, int qty) {
        List<String> out = new ArrayList<>(p.lore());
        for (String h : p.highlights()) {
            out.add(h.startsWith("- ") ? "  &8• &7" + h.substring(2) : "&8◆ &f" + (h.startsWith("* ") ? h.substring(2) : h));
        }
        out.add("");
        out.add("&7Price: &6&l" + s.formatMoney(p.cents()) + (p.subscription() ? " &7/ month" : ""));
        if (qty > 0) {
            out.add("&aIn your cart" + (p.max() > 1 ? " &7(x" + qty + ")" : ""));
        }
        out.add("");
        if (qty < p.max()) {
            out.add(me.Evan52098.genscore.menu.Lore.prompt("add to cart"));
        }
        if (qty > 0) {
            out.add(me.Evan52098.genscore.menu.Lore.prompt("right-click to remove"));
        }
        return out;
    }

    private ItemStack tabIcon(StoreCatalog.Category c, boolean selected) {
        String color = "&#" + String.format("%06X", c.color().value());
        ItemStack item = c.icon().item(List.of(
                "&7" + c.products().size() + (c.products().size() == 1 ? " item" : " items"),
                "",
                selected ? "&aViewing" : me.Evan52098.genscore.menu.Lore.prompt("view")));
        item.editMeta(m -> {
            m.displayName(Text.item(color + "&l" + c.name()));
            if (selected) {
                m.setEnchantmentGlintOverride(true);
            }
        });
        return item;
    }

    private void checkout(Player player) {
        StoreCatalog cat = catalog.get();
        Map<StoreCatalog.Product, Integer> cart = cart(player.getUniqueId(), cat);
        if (cart.isEmpty()) {
            player.playSound(player, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        StoreSettings s = settings.get();
        boolean linked = !cat.headlessToken().isEmpty() && cart.keySet().stream().allMatch(p -> p.packageId() > 0);
        player.closeInventory();
        player.closeDialog();
        if (!linked) {
            player.sendMessage(Msg.neutral("Your cart:"));
            for (Map.Entry<StoreCatalog.Product, Integer> en : cart.entrySet()) {
                player.sendMessage(Text.legacy("&8 • &f" + (en.getValue() > 1 ? en.getValue() + "x " : "") + en.getKey().fullName()
                        + " &7- &e" + s.formatMoney(en.getKey().cents() * en.getValue())));
            }
            if (Text.isWebLink(s.url())) {
                player.sendMessage(Text.replace(Text.legacy("&7Finish your order on the store: {store}"),
                        Map.of("{store}", Text.link(s.linkLabel(), s.url(), "&7Click to open the store"))));
            } else {
                player.sendMessage(Msg.bad("The store isn't set up yet."));
            }
            return;
        }
        if (!cooldowns.tryStart(player.getUniqueId(), "checkout", 10_000)) {
            player.sendMessage(Msg.bad("Please wait a moment before checking out again."));
            return;
        }
        player.sendMessage(Msg.neutral("Creating your checkout link..."));
        player.playSound(player, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 1.6f);
        String name = player.getName();
        Tasks.async.run(() -> {
            Component reply;
            boolean ok = false;
            try {
                String link = checkout.create(cat.headlessToken(), name, cat.completeUrl(), cart);
                reply = Component.text()
                        .append(Text.legacy("&6&lCHECKOUT &8» &7Your basket is ready: "))
                        .append(Text.legacy("&e&l[CLICK TO PAY]")
                                .clickEvent(ClickEvent.openUrl(link))
                                .hoverEvent(HoverEvent.showText(Text.legacy("&7Opens the secure Tebex checkout"))))
                        .build();
                ok = true;
            } catch (StoreCheckout.CheckoutException ex) {
                log.warning("[store] Checkout for " + name + " failed: " + ex.getMessage());
                reply = Msg.bad("Checkout failed: " + Msg.escape(String.valueOf(ex.getMessage())) + ". Try again in a moment.");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException ex) {
                log.log(Level.WARNING, "[store] Checkout for " + name + " failed", ex);
                reply = Msg.bad("Checkout failed. Try again in a moment.");
            }
            Component msg = reply;
            boolean done = ok;
            Tasks.onEntity(player, () -> {
                player.sendMessage(msg);
                if (done) {
                    carts.remove(player.getUniqueId());
                }
            });
        });
    }
}
