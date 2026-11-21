package me.Evan52098.genscore.store;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.nbt.api.BinaryTagHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.ShadowColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class StoreDialog {

    static final String NS = "genscore";
    static final int PER_PAGE = 3;
    static final int WIDTH = 632;
    static final int LINES = 40;
    static final int CARD_W = 140;
    static final int CARD_PITCH = 142;
    static final int SIDE_X = 426;
    static final int SIDE_W = 206;
    static final int SIDE_RIGHT = SIDE_X + SIDE_W - 7;
    static final int CART_ROWS = 7;

    private static final Key FONT = Key.key(NS, "shop");
    private static final Key ART = Key.key(NS, "shop_art");
    private static final char TAB = '';
    private static final char TAB_SELECTED = '';
    private static final char BADGE = '';
    private static final char CARD_TOP = '';
    private static final char CARD_BODY = '';
    private static final char SLOT = '';
    private static final char REWARDS = '';
    private static final char CART = '';
    private static final char TRACK = '';
    private static final char FILL = '';
    private static final char MARKER = '';
    private static final char MARKER_LOCK = '';
    private static final char GIFT = '';
    private static final char CART_ICON = '';
    private static final char REWARD_BOX = '';
    private static final char REWARD_LOCK = '';
    private static final char BTN_VIEW = '';
    private static final char BTN_BUY = '';
    private static final char BTN_TOTAL = '';
    private static final char BTN_CHECKOUT = '';
    private static final char BULLET = '';
    private static final char SUBDOT = '';

    private static final TextColor GOLD = TextColor.color(0xFFC83D);
    private static final TextColor MONEY = TextColor.color(0xFFD24A);
    private static final TextColor GREEN = TextColor.color(0x55E36B);
    private static final TextColor MUTED = TextColor.color(0x9A9AA6);
    private static final TextColor DIM = TextColor.color(0x6C6C78);
    private static final TextColor SUB = TextColor.color(0xA8A8B2);
    private static final TextColor LABEL = TextColor.color(0xE6E6EE);
    private static final TextColor PURPLE = TextColor.color(0x8E3BF0);
    private static final TextColor IDLE = TextColor.color(0x4A4A52);
    private static final TextColor ORANGE = TextColor.color(0xFFB020);
    private static final ShadowColor TEXT_SHADOW = ShadowColor.shadowColor(0xFF1A1A1E);
    private static final String WIDTHS = "1355551333515155555555555114545655555555355555555555555555353552555554551542555555535555553136";

    record View(int category, int page) {
    }

    private StoreDialog() {
    }

    static Key key(String path) {
        return Key.key(NS, "store/" + path);
    }

    static Dialog build(StoreCatalog cat, StoreArt art, StoreSettings s, View view, Map<StoreCatalog.Product, Integer> cart) {
        Component text = body(cat, art, s, view, cart);
        List<ActionButton> actions = new ArrayList<>();
        List<Component> labels = labels(cat, s, view, cart);
        List<String> paths = paths(cat, view);
        List<Component> tips = tooltips(cat, view, cart);
        for (int i = 0; i < labels.size(); i++) {
            actions.add(button(labels.get(i), tips.get(i), i % 4 == 3 ? SIDE_W : CARD_W, paths.get(i)));
        }
        DialogBase base = DialogBase.builder(Component.text(cat.title()))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(List.of(DialogBody.plainMessage(text, 1024)))
                .build();
        DialogType type = DialogType.multiAction(actions)
                .columns(4)
                .exitAction(ActionButton.create(Component.text("Close"), null, 200, action("close")))
                .build();
        return Dialog.create(b -> b.empty().base(base).type(type));
    }

    private static List<StoreCatalog.Product> shown(StoreCatalog cat, View view) {
        StoreCatalog.Category c = cat.categories().get(view.category());
        int pages = Math.max(1, (c.products().size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(pages - 1, view.page()));
        return c.products().subList(page * PER_PAGE, Math.min(c.products().size(), page * PER_PAGE + PER_PAGE));
    }

    static List<Component> labels(StoreCatalog cat, StoreSettings s, View view, Map<StoreCatalog.Product, Integer> cart) {
        List<StoreCatalog.Product> shown = shown(cat, view);
        List<Component> out = new ArrayList<>();
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? viewLabel(shown.get(k).view()) : blank(CARD_W));
        }
        out.add(totalLabel(s.formatMoney(total(cart))));
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? buyLabel(shown.get(k), cart.getOrDefault(shown.get(k), 0), s) : blank(CARD_W));
        }
        out.add(checkoutLabel(!cart.isEmpty()));
        return out;
    }

    private static List<String> paths(StoreCatalog cat, View view) {
        List<StoreCatalog.Product> shown = shown(cat, view);
        List<String> out = new ArrayList<>();
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? "view/" + shown.get(k).id() : "none");
        }
        out.add("none");
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? "add/" + shown.get(k).id() : "none");
        }
        out.add("checkout");
        return out;
    }

    private static List<Component> tooltips(StoreCatalog cat, View view, Map<StoreCatalog.Product, Integer> cart) {
        List<StoreCatalog.Product> shown = shown(cat, view);
        List<Component> out = new ArrayList<>();
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? Component.text("See everything in " + shown.get(k).fullName()) : null);
        }
        out.add(null);
        for (int k = 0; k < PER_PAGE; k++) {
            out.add(k < shown.size() ? Component.text(cart.getOrDefault(shown.get(k), 0) >= shown.get(k).max()
                    ? "Already in your cart" : "Add to your cart") : null);
        }
        out.add(Component.text(cart.isEmpty() ? "Your cart is empty" : "Check out on the store"));
        return out;
    }

    static Component body(StoreCatalog cat, StoreArt art, StoreSettings s, View view, Map<StoreCatalog.Product, Integer> cart) {
        StoreCatalog.Category c = cat.categories().get(view.category());
        int pages = Math.max(1, (c.products().size() + PER_PAGE - 1) / PER_PAGE);
        int page = Math.max(0, Math.min(pages - 1, view.page()));
        List<StoreCatalog.Product> shown = c.products().subList(page * PER_PAGE, Math.min(c.products().size(), page * PER_PAGE + PER_PAGE));
        long total = total(cart);

        Canvas a = new Canvas();
        for (int i = 0; i < cat.categories().size(); i++) {
            StoreCatalog.Category tab = cat.categories().get(i);
            int x = i * 107;
            a.glyph(FONT, i == view.category() ? TAB_SELECTED : TAB, 99, x, tab.color());
            StoreArt.Art t = art.get(tab.art());
            if (t != null) {
                a.glyph(ART, t.glyph(StoreArt.TAB), t.advance(StoreArt.TAB) - 1, x + 64, NamedTextColor.WHITE);
            }
            List<String> label = tab.label();
            int y = label.size() > 1 ? 10 : 15;
            for (String l : label) {
                a.text(fit(l, 60, true), x + 6, y, NamedTextColor.WHITE, true, true);
                y += 10;
            }
        }
        if (pages > 1) {
            a.text("<", 556, 15, page > 0 ? NamedTextColor.WHITE : DIM, true, true);
            a.center((page + 1) + "/" + pages, 590, 15, LABEL, true, true);
            a.text(">", 618, 15, page < pages - 1 ? NamedTextColor.WHITE : DIM, true, true);
        }

        for (int k = 0; k < shown.size(); k++) {
            card(a, art, shown.get(k), k * CARD_PITCH);
        }

        a.glyph(FONT, REWARDS, SIDE_W, SIDE_X, NamedTextColor.WHITE);
        a.glyph(FONT, GIFT, 9, SIDE_X + 6, NamedTextColor.WHITE);
        a.text("CART REWARDS", SIDE_X + 18, 52, GOLD, true, true);
        List<StoreCatalog.Tier> tiers = cat.tiers();
        int reached = 0;
        for (StoreCatalog.Tier t : tiers) {
            if (total >= t.cents()) {
                reached++;
            }
        }
        a.right(reached + "/" + tiers.size(), SIDE_RIGHT, 52, MUTED, false, false);
        if (!tiers.isEmpty()) {
            long max = tiers.get(tiers.size() - 1).cents();
            int len = 184;
            a.glyph(FONT, TRACK, 186, SIDE_X + 10, NamedTextColor.WHITE);
            a.repeat(FONT, FILL, (int) Math.min(len, max <= 0 ? len : total * len / max), SIDE_X + 11, ORANGE);
            for (StoreCatalog.Tier t : tiers) {
                int pos = SIDE_X + 10 + (int) (186L * t.cents() / Math.max(1, max));
                int mx = Math.min(SIDE_X + SIDE_W - 22, Math.max(SIDE_X + 4, pos - 9));
                a.glyph(FONT, MARKER, 18, mx, NamedTextColor.WHITE);
                StoreArt.Art ta = art.get(t.art());
                if (ta != null) {
                    a.glyph(ART, ta.glyph(StoreArt.MARKER), ta.advance(StoreArt.MARKER) - 1, mx + 3, NamedTextColor.WHITE);
                }
                if (total < t.cents()) {
                    a.glyph(FONT, MARKER_LOCK, 7, mx + 12, NamedTextColor.WHITE);
                }
                a.center(s.formatMoney(t.cents()).replace(".00", ""), mx + 9, 89, MUTED, false, false);
            }
            for (int i = 0; i < tiers.size(); i++) {
                StoreCatalog.Tier t = tiers.get(i);
                int by = 108 + 50 * i;
                a.glyph(FONT, (char) (REWARD_BOX + i), 36, SIDE_X + 6, NamedTextColor.WHITE);
                StoreArt.Art ta = art.get(t.art());
                if (ta != null) {
                    a.glyph(ART, ta.glyph(StoreArt.REWARD + i), ta.advance(StoreArt.REWARD + i) - 1, SIDE_X + 12, NamedTextColor.WHITE);
                }
                boolean got = total >= t.cents();
                if (!got) {
                    a.glyph(FONT, (char) (REWARD_LOCK + i), 7, SIDE_X + 33, NamedTextColor.WHITE);
                }
                String price = s.formatMoney(t.cents()).replace(".00", "");
                int x = SIDE_X + 48;
                a.text(price, x, by + 10, LABEL, false, true);
                a.segments(segments(t.name(), t.color()), x + width(price, false) + 4, by + 10, SIDE_RIGHT - x - width(price, false) - 4);
                if (got) {
                    a.text("Unlocked!", x, by + 22, GREEN, false, true);
                } else {
                    String need = s.formatMoney(t.cents() - total);
                    a.text("Add", x, by + 22, MUTED, false, false);
                    a.text(need, x + width("Add ", false), by + 22, GREEN, false, true);
                    a.text("more", x + width("Add " + need + " ", false), by + 22, MUTED, false, false);
                }
            }
        } else {
            a.center("No cart rewards right now", SIDE_X + SIDE_W / 2, 110, DIM, false, false);
        }

        a.glyph(FONT, CART, SIDE_W, SIDE_X, NamedTextColor.WHITE);
        a.glyph(FONT, CART_ICON, 9, SIDE_X + 6, NamedTextColor.WHITE);
        a.text("YOUR CART", SIDE_X + 18, 218, NamedTextColor.WHITE, true, true);
        int items = 0;
        for (int q : cart.values()) {
            items += q;
        }
        a.right(items + (items == 1 ? " item" : " items"), SIDE_RIGHT, 218, MUTED, false, false);
        List<Map.Entry<StoreCatalog.Product, Integer>> lines = new ArrayList<>(cart.entrySet());
        if (lines.isEmpty()) {
            a.center("Your cart is empty", SIDE_X + SIDE_W / 2, 239, MUTED, false, false);
            a.center("Pick a category to start", SIDE_X + SIDE_W / 2, 251, DIM, false, false);
        } else {
            for (int i = 0; i < Math.min(CART_ROWS, lines.size()); i++) {
                int y = 234 + 18 * i;
                if (i == CART_ROWS - 1 && lines.size() > CART_ROWS) {
                    a.text("+" + (lines.size() - i) + " more", SIDE_X + 26, y + 4, MUTED, false, false);
                    break;
                }
                StoreCatalog.Product p = lines.get(i).getKey();
                int qty = lines.get(i).getValue();
                StoreArt.Art pa = art.get(p.art());
                if (pa != null) {
                    a.glyph(ART, pa.glyph(StoreArt.CART_ROW + i), pa.advance(StoreArt.CART_ROW + i) - 1, SIDE_X + 6, NamedTextColor.WHITE);
                }
                String price = s.formatMoney(p.cents() * qty);
                int room = SIDE_RIGHT - (SIDE_X + 26) - width(price, false) - 6;
                String name = (qty > 1 ? qty + "x " : "") + p.fullName();
                if (width(name, false) > room) {
                    name = (qty > 1 ? qty + "x " : "") + p.name();
                }
                a.text(fit(name, room, false), SIDE_X + 26, y + 4, NamedTextColor.WHITE, false, true);
                a.right(price, SIDE_RIGHT, y + 4, MONEY, false, true);
            }
        }

        List<Component> body = new ArrayList<>();
        body.add(a.build(WIDTH));
        for (int line = 1; line < LINES; line++) {
            body.add(clickLine(cat, c, shown, lines, view, page, pages, line));
        }
        return Component.join(net.kyori.adventure.text.JoinConfiguration.newlines(), body);
    }

    static Dialog details(StoreCatalog.Product p, StoreSettings s, int qty, View back) {
        List<DialogBody> body = new ArrayList<>();
        List<String> lines = new ArrayList<>(p.lore());
        if (!lines.isEmpty()) {
            lines.add("");
        }
        for (String h : p.highlights()) {
            String t = h.startsWith("* ") ? "&8◆ &f" + h.substring(2) : h.startsWith("- ") ? "   &8• &7" + h.substring(2) : h;
            lines.add(t);
        }
        if (!p.contents().isEmpty()) {
            lines.add("");
            lines.add("&7What's inside:");
            for (StoreCatalog.Content content : p.contents()) {
                lines.add("&8 • &f" + (content.amount() > 1 ? content.amount() + "x " : "") + content.name());
            }
        }
        lines.add("");
        lines.add("&7Price: &6&l" + s.formatMoney(p.cents()) + (p.subscription() ? " &7/ month" : ""));
        StringBuilder sb = new StringBuilder();
        for (String l : lines) {
            if (!sb.isEmpty()) {
                sb.append('\n');
            }
            sb.append(l);
        }
        body.add(DialogBody.plainMessage(me.Evan52098.genscore.info.Text.legacy(sb.toString()), 300));
        String back2 = "back/" + back.category() + "/" + back.page();
        ActionButton add = ActionButton.create(qty >= p.max()
                        ? Component.text("Already in cart", NamedTextColor.GRAY)
                        : Component.text("Add to cart - " + s.formatMoney(p.cents()), GREEN),
                null, 200, action(qty >= p.max() ? back2 : "addback/" + p.id() + "/" + back.category() + "/" + back.page()));
        DialogBase base = DialogBase.builder(me.Evan52098.genscore.info.Text.legacy(
                        "&#" + String.format("%06X", p.titleColor().value()) + "&l" + p.fullName()))
                .canCloseWithEscape(true)
                .pause(false)
                .afterAction(DialogBase.DialogAfterAction.NONE)
                .body(body)
                .build();
        DialogType type = DialogType.multiAction(List.of(add))
                .columns(1)
                .exitAction(ActionButton.create(Component.text("Back"), null, 200, action(back2)))
                .build();
        return Dialog.create(b -> b.empty().base(base).type(type));
    }

    private static void card(Canvas a, StoreArt art, StoreCatalog.Product p, int x) {
        a.glyph(FONT, CARD_TOP, CARD_W, x, p.color());
        StoreArt.Art pa = art.get(p.art());
        if (pa != null) {
            int adv = pa.advance(StoreArt.CARD) - 1;
            a.glyph(ART, pa.glyph(StoreArt.CARD), adv, x + (CARD_W - adv) / 2, NamedTextColor.WHITE);
        }
        a.glyph(FONT, CARD_BODY, CARD_W, x, NamedTextColor.WHITE);
        if (p.badge() != null) {
            String text = fit(p.badge().text(), CARD_W - 10, true);
            int w = width(text, true) + 8;
            a.repeat(FONT, BADGE, w, x + 1, p.badge().color());
            a.text(text, x + 5, 46, NamedTextColor.WHITE, true, true);
        }
        a.center(fit(p.name().toUpperCase(java.util.Locale.ROOT), CARD_W - 8, true), x + CARD_W / 2, 192, p.titleColor(), true, true);
        for (int r = 0; r < p.highlights().size(); r++) {
            String h = p.highlights().get(r);
            int y = 207 + 11 * r;
            if (h.startsWith("- ")) {
                a.glyph(FONT, (char) (SUBDOT + r), 2, x + 16, NamedTextColor.WHITE);
                a.segments(segments(h.substring(2), SUB), x + 21, y, CARD_W - 25);
            } else {
                String body = h.startsWith("* ") ? h.substring(2) : h;
                a.glyph(FONT, (char) (BULLET + r), 5, x + 6, p.color());
                a.segments(segments(body, NamedTextColor.WHITE), x + 14, y, CARD_W - 18);
            }
        }
        if (!p.contents().isEmpty()) {
            a.center("WHAT'S INSIDE", x + CARD_W / 2, 330, DIM, false, false);
            int n = p.contents().size();
            int start = x + CARD_W / 2 - (n * 22 - 2) / 2;
            for (int i = 0; i < n; i++) {
                int bx = start + 22 * i;
                a.glyph(FONT, SLOT, 20, bx, NamedTextColor.WHITE);
                StoreArt.Art ca = art.get(p.contents().get(i).art());
                if (ca != null) {
                    int adv = ca.advance(StoreArt.INSIDE) - 1;
                    a.glyph(ART, ca.glyph(StoreArt.INSIDE), adv, bx + 2, NamedTextColor.WHITE);
                }
            }
        }
    }

    private static Component clickLine(StoreCatalog cat, StoreCatalog.Category c, List<StoreCatalog.Product> shown,
                                       List<Map.Entry<StoreCatalog.Product, Integer>> cart, View view, int page, int pages,
                                       int line) {
        Canvas a = new Canvas();
        int y = line * 9;
        if (y < 36) {
            for (int i = 0; i < cat.categories().size(); i++) {
                a.zone(i * 107, 99, "tab/" + i, Component.text(cat.categories().get(i).name()));
            }
            if (pages > 1 && y >= 9 && y < 27) {
                if (page > 0) {
                    a.zone(550, 20, "page/" + view.category() + "/" + (page - 1), Component.text("Previous page"));
                }
                if (page < pages - 1) {
                    a.zone(610, 20, "page/" + view.category() + "/" + (page + 1), Component.text("Next page"));
                }
            }
        }
        if (y >= 54 && y < 189) {
            for (int k = 0; k < shown.size(); k++) {
                a.zone(k * CARD_PITCH, CARD_W, "view/" + shown.get(k).id(), Component.text("View " + shown.get(k).fullName()));
            }
        }
        if (y >= 234 && y < 234 + 18 * CART_ROWS) {
            int row = (y - 234) / 18;
            if (row < cart.size() && !(row == CART_ROWS - 1 && cart.size() > CART_ROWS)) {
                StoreCatalog.Product p = cart.get(row).getKey();
                a.zone(SIDE_X, SIDE_W, "remove/" + p.id(), Component.text("Click to remove one " + p.fullName(), NamedTextColor.RED));
            }
        }
        return a.build(WIDTH);
    }

    private static Component viewLabel(String text) {
        Canvas a = new Canvas();
        a.glyph(FONT, BTN_VIEW, CARD_W, -2, NamedTextColor.WHITE);
        a.centerDefault(fit(text, CARD_W - 8, false), CARD_W / 2 - 2, LABEL, false);
        return a.build(CARD_W - 4);
    }

    private static Component buyLabel(StoreCatalog.Product p, int qty, StoreSettings s) {
        Canvas a = new Canvas();
        boolean full = qty >= p.max();
        a.glyph(FONT, BTN_BUY, CARD_W, -2, full ? IDLE : qty > 0 ? TextColor.color(0x2FAE4E) : PURPLE);
        String text = full ? (p.max() > 1 ? "In cart (" + qty + ")" : "In cart") : qty > 0 ? "Add another (" + qty + ")" : "Buy " + s.formatMoney(p.cents());
        a.centerDefault(text, CARD_W / 2 - 2, NamedTextColor.WHITE, true);
        return a.build(CARD_W - 4);
    }

    private static Component totalLabel(String money) {
        Canvas a = new Canvas();
        a.glyph(FONT, BTN_TOTAL, SIDE_W, -2, NamedTextColor.WHITE);
        a.textDefault("Total", 4, MUTED, false);
        a.rightDefault(money, SIDE_W - 10, MONEY, true);
        return a.build(SIDE_W - 4);
    }

    private static Component checkoutLabel(boolean ready) {
        Canvas a = new Canvas();
        a.glyph(FONT, BTN_CHECKOUT, SIDE_W, -2, ready ? PURPLE : IDLE);
        a.centerDefault("Checkout", SIDE_W / 2 - 2, ready ? NamedTextColor.WHITE : DIM, true);
        return a.build(SIDE_W - 4);
    }

    private static Component blank(int w) {
        return new Canvas().build(w - 4);
    }

    private static ActionButton button(Component label, @Nullable Component tooltip, int width, String path) {
        return ActionButton.create(label, tooltip, width, action(path));
    }

    private static DialogAction action(String path) {
        return DialogAction.customClick(key(path), BinaryTagHolder.binaryTagHolder("{}"));
    }

    private static long total(Map<StoreCatalog.Product, Integer> cart) {
        long t = 0;
        for (Map.Entry<StoreCatalog.Product, Integer> e : cart.entrySet()) {
            t += e.getKey().cents() * e.getValue();
        }
        return t;
    }

    record Seg(String text, TextColor color) {
    }

    static List<Seg> segments(String legacy, TextColor base) {
        List<Seg> out = new ArrayList<>();
        TextColor color = base;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < legacy.length(); i++) {
            char ch = legacy.charAt(i);
            if ((ch == '&' || ch == '§') && i + 1 < legacy.length()) {
                char code = Character.toLowerCase(legacy.charAt(i + 1));
                TextColor next = null;
                int skip = 1;
                if (code == '#' && i + 7 < legacy.length()) {
                    next = TextColor.fromHexString(legacy.substring(i + 1, i + 8));
                    skip = 7;
                } else if ("0123456789abcdef".indexOf(code) >= 0) {
                    next = legacyColor(code);
                } else if (code == 'r') {
                    next = base;
                } else if ("klmno".indexOf(code) >= 0) {
                    i += 1;
                    continue;
                }
                if (next != null) {
                    if (!sb.isEmpty()) {
                        out.add(new Seg(sb.toString(), color));
                        sb.setLength(0);
                    }
                    color = next;
                    i += skip;
                    continue;
                }
            }
            sb.append(ch);
        }
        if (!sb.isEmpty()) {
            out.add(new Seg(sb.toString(), color));
        }
        return out;
    }

    private static TextColor legacyColor(char code) {
        return switch (code) {
            case '0' -> NamedTextColor.BLACK;
            case '1' -> NamedTextColor.DARK_BLUE;
            case '2' -> NamedTextColor.DARK_GREEN;
            case '3' -> NamedTextColor.DARK_AQUA;
            case '4' -> NamedTextColor.DARK_RED;
            case '5' -> NamedTextColor.DARK_PURPLE;
            case '6' -> NamedTextColor.GOLD;
            case '7' -> NamedTextColor.GRAY;
            case '8' -> NamedTextColor.DARK_GRAY;
            case '9' -> NamedTextColor.BLUE;
            case 'a' -> NamedTextColor.GREEN;
            case 'b' -> NamedTextColor.AQUA;
            case 'c' -> NamedTextColor.RED;
            case 'd' -> NamedTextColor.LIGHT_PURPLE;
            case 'e' -> NamedTextColor.YELLOW;
            default -> NamedTextColor.WHITE;
        };
    }

    static String clean(String text) {
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sb.append(c >= 0x20 && c < 0x7F ? c : '?');
        }
        return sb.toString();
    }

    static int width(String text, boolean bold) {
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == ' ') {
                w += 4;
            } else if (c > 0x20 && c < 0x7F) {
                w += WIDTHS.charAt(c - 0x21) - '0' + 1 + (bold ? 1 : 0);
            }
        }
        return w;
    }

    static String fit(String text, int max, boolean bold) {
        String s = clean(text);
        if (width(s, bold) <= max) {
            return s;
        }
        while (!s.isEmpty() && width(s + "..", bold) > max) {
            s = s.substring(0, s.length() - 1);
        }
        return s.stripTrailing() + "..";
    }

    static final class Canvas {

        private final TextComponent.Builder out = Component.text();
        private int x;

        void glyph(Key font, char glyph, int width, int at, TextColor tint) {
            move(at);
            out.append(Component.text(glyph).font(font).color(tint).shadowColor(ShadowColor.none()));
            x += width + 1;
        }

        void repeat(Key font, char glyph, int count, int at, TextColor tint) {
            if (count <= 0) {
                return;
            }
            move(at);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < count; i++) {
                sb.append(glyph).append(space(-1));
            }
            out.append(Component.text(sb.toString()).font(font).color(tint).shadowColor(ShadowColor.none()));
            x += count;
        }

        void text(String text, int at, int y, TextColor color, boolean bold, boolean shadow) {
            String s = clean(text);
            if (s.isEmpty() || y < 0 || y > 430) {
                return;
            }
            move(at);
            out.append(Component.text(s).font(Key.key(NS, "shop_t" + y)).color(color).decoration(TextDecoration.BOLD, bold)
                    .shadowColor(shadow ? TEXT_SHADOW : ShadowColor.none()));
            x += width(s, bold);
        }

        void center(String text, int middle, int y, TextColor color, boolean bold, boolean shadow) {
            String s = clean(text);
            text(s, middle - width(s, bold) / 2, y, color, bold, shadow);
        }

        void right(String text, int edge, int y, TextColor color, boolean bold, boolean shadow) {
            String s = clean(text);
            text(s, edge - width(s, bold), y, color, bold, shadow);
        }

        void segments(List<Seg> segs, int at, int y, int max) {
            int used = 0;
            int pos = at;
            for (Seg seg : segs) {
                String s = clean(seg.text());
                int w = width(s, false);
                if (used + w > max) {
                    s = fit(s, max - used, false);
                    w = width(s, false);
                    text(s, pos, y, seg.color(), false, true);
                    return;
                }
                text(s, pos, y, seg.color(), false, true);
                used += w;
                pos += w;
            }
        }

        void textDefault(String text, int at, TextColor color, boolean bold) {
            text(text, at, 0, color, bold, true);
        }

        void centerDefault(String text, int middle, TextColor color, boolean bold) {
            center(text, middle, 0, color, bold, true);
        }

        void rightDefault(String text, int edge, TextColor color, boolean bold) {
            right(text, edge, 0, color, bold, true);
        }

        void zone(int at, int width, String path, Component hover) {
            move(at);
            out.append(Component.text(space(width)).font(FONT)
                    .clickEvent(ClickEvent.custom(key(path)))
                    .hoverEvent(HoverEvent.showText(hover)));
            x += width;
        }

        Component build(int end) {
            move(end);
            return out.build();
        }

        private void move(int to) {
            int delta = to - x;
            if (delta != 0) {
                out.append(Component.text(space(delta)).font(FONT));
            }
            x = to;
        }

        static String space(int delta) {
            StringBuilder sb = new StringBuilder();
            int left = Math.abs(delta);
            char base = delta < 0 ? '' : '';
            for (int bit = 9; bit >= 0; bit--) {
                while (left >= (1 << bit)) {
                    sb.append((char) (base + bit));
                    left -= 1 << bit;
                }
            }
            return sb.toString();
        }
    }
}
