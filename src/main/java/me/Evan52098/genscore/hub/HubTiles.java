package me.Evan52098.genscore.hub;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

final class HubTiles {

    record Tile(String id, int col, int row, Material icon, String name, List<String> lore, Consumer<Player> action) {
    }

    record Button(int col, ItemStack item, Consumer<Player> action) {
    }

    private static final NamespacedKey BLANK = new NamespacedKey("genscore", "menu_blank");
    private static final String[] BIG_PIECES = {"tl", null, "tr", "bl", "bm", "br"};
    private static final String[] SMALL_PIECES = {"l", null, "r"};

    private final @Nullable Component title;

    private HubTiles(@Nullable Component title) {
        this.title = title;
    }

    static HubTiles load(@Nullable InputStream in, Logger log) {
        if (in == null) {
            log.warning("[hub] modules/hub/menu_title.json is missing; the tile menu is off.");
            return new HubTiles(null);
        }
        try (in) {
            return new HubTiles(GsonComponentSerializer.gson().deserialize(new String(in.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException e) {
            log.warning("[hub] Could not read menu_title.json; the tile menu is off.");
            return new HubTiles(null);
        }
    }

    static boolean bedrock(Player p) {
        return me.Evan52098.genscore.util.Bedrock.is(p);
    }

    boolean open(Player p, List<Tile> tiles, List<Button> bar) {
        boolean bedrock = bedrock(p);
        boolean java = !bedrock && title != null && Menu.styledFor(p);
        if (!bedrock && !java) {
            return false;
        }
        Menu menu = java ? new Menu(6, title).customTitle() : new Menu(6, MenuStyle.title("GateMC"));
        for (Tile t : tiles) {
            boolean big = t.row() < 2;
            int base = big ? t.row() * 18 + t.col() * 3 : 36 + t.col() * 3;
            String[] pieces = big ? BIG_PIECES : SMALL_PIECES;
            for (int i = 0; i < pieces.length; i++) {
                int slot = base + (i % 3) + (i / 3) * 9;
                ItemStack item;
                if (java) {
                    item = model(Material.PAPER, BLANK, t);
                } else if (pieces[i] == null) {
                    item = model(t.icon(), null, t);
                } else {
                    item = model(Material.PAPER, new NamespacedKey("genscore", "menu/" + t.id() + "_" + pieces[i]), t);
                }
                menu.set(slot, item, c -> t.action().accept(p));
            }
        }
        for (Button b : bar) {
            menu.set(45 + b.col(), b.item(), c -> b.action().accept(p));
        }
        menu.open(p);
        return true;
    }

    private static ItemStack model(Material material, @Nullable NamespacedKey model, Tile t) {
        ItemStack item = Lore.item(material, t.name(), t.lore());
        item.editMeta(meta -> {
            meta.addItemFlags(ItemFlag.values());
            if (model != null) {
                meta.setItemModel(model);
            }
        });
        return item;
    }
}
