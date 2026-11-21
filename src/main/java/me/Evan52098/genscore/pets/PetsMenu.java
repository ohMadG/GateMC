package me.Evan52098.genscore.pets;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

final class PetsMenu {

    private static final int[] GRID = {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
    private static final int[] EQUIP = {11, 12, 13, 14, 15};
    private static final int[] EGGS = {47, 48, 50, 51};

    private final PetsModule module;

    PetsMenu(PetsModule module) {
        this.module = module;
    }

    void open(Player p, int page) {
        PetData d = module.data(p.getUniqueId());
        if (d == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        PetsSettings s = module.settings();
        List<PetData.Pet> pets = d.pets();
        pets.sort(Comparator.comparing((PetData.Pet x) -> !x.equipped)
                .thenComparing(x -> -rarity(s, x))
                .thenComparing(x -> !x.golden)
                .thenComparing(x -> -x.level));
        int slots = module.slots(p);
        Menu menu = new Menu(6, MenuStyle.title("Pets"));
        menu.set(4, summary(p, d, s, slots));
        List<PetData.Pet> equipped = pets.stream().filter(x -> x.equipped).toList();
        for (int i = 0; i < EQUIP.length; i++) {
            if (i < equipped.size()) {
                PetData.Pet pet = equipped.get(i);
                menu.set(EQUIP[i], petItem(s, pet, d, true), c -> {
                    if (c.isRightClick()) {
                        detail(p, pet.id, page);
                    } else {
                        unequip(p, d, pet.id, page);
                    }
                });
            } else if (i < slots) {
                menu.set(EQUIP[i], MenuStyle.icon(Material.LIME_STAINED_GLASS_PANE, "&aEmpty pet slot",
                        List.of("&7Click a pet below to equip it.")));
            } else {
                menu.set(EQUIP[i], MenuStyle.icon(Material.GRAY_STAINED_GLASS_PANE, "&8Locked pet slot",
                        List.of("&7More pet slots come with ranks.", "&e/store")));
            }
        }
        List<PetData.Pet> rest = pets.stream().filter(x -> !x.equipped).toList();
        int pages = Math.max(1, (rest.size() + GRID.length - 1) / GRID.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        for (int i = 0; i < GRID.length; i++) {
            int idx = pg * GRID.length + i;
            if (idx >= rest.size()) {
                break;
            }
            PetData.Pet pet = rest.get(idx);
            menu.set(GRID[i], petItem(s, pet, d, false), c -> {
                if (c.isRightClick()) {
                    detail(p, pet.id, pg);
                } else {
                    equip(p, d, pet.id, pg);
                }
            });
        }
        if (rest.isEmpty() && equipped.isEmpty()) {
            menu.set(31, MenuStyle.icon(Material.EGG, "&fNo pets yet", List.of(
                    "&7Pet eggs come from caches,", "&7crates and the Egg Shop.", "", "&7Hatch them with the eggs below.")));
        }
        for (int r = 0; r < 4; r++) {
            int rarity = r;
            int count = d.eggs(r);
            List<String> lore = new ArrayList<>();
            lore.add("&7You have &f" + count + "&7.");
            lore.add("&7Hatches a random " + PetsSettings.RARITY_CODES[r] + PetsSettings.RARITY_NAMES[r] + " &7pet:");
            for (PetsSettings.PetDef def : s.pets().values()) {
                if (def.rarity() == r) {
                    lore.add("&8• " + PetsSettings.RARITY_CODES[r] + def.name() + " &8(" + PetsSettings.perkName(def.perk()) + ")");
                }
            }
            lore.add("");
            lore.add(count > 0 ? me.Evan52098.genscore.menu.Lore.prompt("hatch") : "&8Buy eggs in the Egg Shop (top right)");
            ItemStack egg = MenuStyle.icon(PetsSettings.EGG_ICONS[r], PetsSettings.RARITY_CODES[r] + "&l" + PetsSettings.RARITY_NAMES[r] + " Egg", lore);
            egg.setAmount(Math.max(1, Math.min(64, count)));
            menu.set(EGGS[r], egg, c -> hatch(p, d, rarity, pg));
        }
        menu.set(45, MenuStyle.close(), c -> p.closeInventory());
        if (pg > 0) {
            menu.set(46, MenuStyle.previous(pg + 1, pages), c -> open(p, pg - 1));
        }
        if (pg < pages - 1) {
            menu.set(52, MenuStyle.next(pg + 1, pages), c -> open(p, pg + 1));
        }
        menu.set(49, MenuStyle.icon(d.hidden() ? Material.ENDER_EYE : Material.ENDER_PEARL, d.hidden() ? "&7Pets hidden" : "&aPets shown",
                List.of("&7Show or hide your floating pets.", "&7Boosts work either way.", "", me.Evan52098.genscore.menu.Lore.prompt("toggle"))), c -> {
            d.setHidden(!d.hidden());
            open(p, pg);
        });
        menu.set(53, MenuStyle.icon(Material.BOOK, "&eHow pets work", List.of(
                "&7Equipped pets boost your factory.",
                "&7They level up while equipped,",
                "&7or feed them treats for tokens.",
                "&7Fuse 3 of the same pet into a",
                "&6Golden &7one (x" + trim(s.goldenMultiplier()) + " boost).",
                "",
                "&eLeft-click &7a pet to equip it.",
                "&eRight-click &7a pet for more.")));
        menu.set(8, MenuStyle.icon(Material.EMERALD, "&a&lEgg Shop", List.of(
                "&7Buy pet eggs with credits.", "&7You have &6" + module.credits(p.getUniqueId()) + " credits&7.", "",
                me.Evan52098.genscore.menu.Lore.prompt("browse"))), c -> shop(p, pg));
        MenuStyle.frame(menu, 6);
        menu.open(p);
    }

    private static final int[] SHOP = {10, 12, 14, 16};

    void shop(Player p, int back) {
        PetsSettings s = module.settings();
        long credits = module.credits(p.getUniqueId());
        Menu menu = new Menu(3, MenuStyle.title("Pets", "Egg Shop"));
        for (int r = 0; r < 4; r++) {
            int rarity = r;
            long price = s.eggPrices()[r];
            String code = PetsSettings.RARITY_CODES[r];
            List<String> lore = new ArrayList<>();
            lore.add("&7Hatches a random " + code + PetsSettings.RARITY_NAMES[r] + " &7pet.");
            lore.add("");
            if (price <= 0) {
                lore.add("&8Not for sale.");
            } else {
                lore.add("&7Price: &6" + price + " credits");
                lore.add("&7You have &6" + credits + " credits&7.");
                lore.add("");
                lore.add(credits >= price ? me.Evan52098.genscore.menu.Lore.prompt("buy") : "&cNot enough credits. &e/store");
            }
            menu.set(SHOP[r], MenuStyle.icon(PetsSettings.EGG_ICONS[r], code + "&l" + PetsSettings.RARITY_NAMES[r] + " Egg", lore), c -> {
                if (price <= 0 || module.credits(p.getUniqueId()) < price) {
                    p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                    return;
                }
                confirm(p, rarity, price, back);
            });
        }
        menu.set(18, MenuStyle.back("your pets"), c -> open(p, back));
        MenuStyle.frame(menu, 3);
        menu.open(p);
    }

    private void confirm(Player p, int rarity, long price, int back) {
        String code = PetsSettings.RARITY_CODES[rarity];
        String egg = code + PetsSettings.RARITY_NAMES[rarity] + " Egg";
        Menu menu = new Menu(3, MenuStyle.title("Pets", "Confirm"));
        menu.set(13, MenuStyle.icon(PetsSettings.EGG_ICONS[rarity], code + "&l" + PetsSettings.RARITY_NAMES[rarity] + " Egg",
                List.of("&7Price: &6" + price + " credits")));
        menu.set(11, MenuStyle.icon(Material.LIME_CONCRETE, "&a&lBuy", List.of("&7Spend &6" + price + " credits &7on a " + egg + "&7.")), c -> {
            PetData d = module.data(p.getUniqueId());
            if (d == null || !module.spendCredits(p.getUniqueId(), price, "pet egg " + PetsSettings.RARITIES[rarity])) {
                p.sendMessage(Msg.bad("You need " + Msg.white(price + " credits") + "."));
                p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
                shop(p, back);
                return;
            }
            d.addEggs(rarity, 1);
            p.sendMessage(Msg.good("Bought a " + Msg.white(PetsSettings.RARITY_NAMES[rarity] + " Egg") + ". Hatch it in /pets."));
            p.playSound(p, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.2f);
            shop(p, back);
        });
        menu.set(15, MenuStyle.icon(Material.RED_CONCRETE, "&c&lCancel", List.of("&7Back to the egg shop.")), c -> shop(p, back));
        MenuStyle.frame(menu, 3);
        menu.open(p);
    }

    private ItemStack summary(Player p, PetData d, PetsSettings s, int slots) {
        List<String> lore = new ArrayList<>();
        lore.add("&7Equipped: &f" + d.equippedCount() + "/" + slots);
        lore.add("&7Owned: &f" + d.count() + "/" + s.storage());
        Map<String, Double> bonus = PetsModule.bonuses(d);
        if (!bonus.isEmpty()) {
            lore.add("");
            lore.add("&7Your pet boosts:");
            bonus.forEach((k, v) -> lore.add("&8• &a" + PetsSettings.perkValue(k, v) + " &7" + PetsSettings.perkName(k)));
        }
        return MenuStyle.clean(Lore.item(Material.NETHER_STAR, "&d&lPETS", lore), true);
    }

    private ItemStack petItem(PetsSettings s, PetData.Pet pet, PetData d, boolean equipped) {
        PetsSettings.PetDef def = s.pet(pet.type);
        if (def == null) {
            return MenuStyle.icon(Material.BARRIER, "&cUnknown pet (" + pet.type + ")", List.of("&7Removed from pets.yml."));
        }
        String code = PetsSettings.RARITY_CODES[def.rarity()];
        List<String> lore = new ArrayList<>();
        lore.add(code + PetsSettings.RARITY_NAMES[def.rarity()] + (pet.golden ? " &6&lGOLDEN" : ""));
        lore.add("&7Level &f" + pet.level + (pet.level >= s.maxLevel() ? " &a(max)" : "&7/" + s.maxLevel()));
        if (pet.level < s.maxLevel()) {
            lore.add(bar(pet.xp, s.xpToNext(pet.level)) + " &7" + pet.xp + "/" + s.xpToNext(pet.level) + " xp");
        }
        lore.add("&a" + PetsSettings.perkValue(def.perk(), s.value(def, pet.level, pet.golden)) + " &7" + PetsSettings.perkName(def.perk()));
        int same = d.sameType(pet.type, pet.id);
        if (!pet.golden && same > 0) {
            lore.add("&8" + Math.min(2, same) + "/2 more to fuse golden");
        }
        lore.add("");
        lore.add(equipped ? me.Evan52098.genscore.menu.Lore.prompt("unequip") : me.Evan52098.genscore.menu.Lore.prompt("equip"));
        lore.add("&e&lRIGHT-CLICK &7for feed, fuse and release");
        String name = (pet.golden ? "&6&l" : code + "&l") + def.name();
        return MenuStyle.clean(Lore.item(def.icon(), name, lore), pet.golden || equipped);
    }

    private static String bar(int xp, int need) {
        int filled = need <= 0 ? 10 : Math.min(10, xp * 10 / need);
        return "&a" + "|".repeat(filled) + "&8" + "|".repeat(10 - filled);
    }

    private static int rarity(PetsSettings s, PetData.Pet pet) {
        PetsSettings.PetDef def = s.pet(pet.type);
        return def == null ? -1 : def.rarity();
    }

    private void equip(Player p, PetData d, String id, int page) {
        if (d.equippedCount() >= module.slots(p)) {
            p.sendMessage(Msg.bad("All your pet slots are full. Unequip a pet first."));
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (d.setEquipped(id, true)) {
            d.recompute(module.settings());
            p.playSound(p, Sound.ENTITY_CHICKEN_EGG, 0.6f, 1.4f);
        }
        open(p, page);
    }

    private void unequip(Player p, PetData d, String id, int page) {
        if (d.setEquipped(id, false)) {
            d.recompute(module.settings());
            p.playSound(p, Sound.ITEM_ARMOR_EQUIP_LEATHER, 0.6f, 1.2f);
        }
        open(p, page);
    }

    private void hatch(Player p, PetData d, int rarity, int page) {
        PetsSettings s = module.settings();
        if (d.eggs(rarity) <= 0) {
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (d.count() >= s.storage()) {
            p.sendMessage(Msg.bad("Your pet storage is full (" + s.storage() + "). Release or fuse some pets first."));
            return;
        }
        PetsSettings.PetDef def = s.roll(rarity);
        if (def == null || !d.takeEgg(rarity)) {
            p.sendMessage(Msg.bad("There are no " + PetsSettings.RARITY_NAMES[rarity] + " pets to hatch right now."));
            return;
        }
        d.add(def.id());
        String code = PetsSettings.RARITY_CODES[rarity];
        p.showTitle(net.kyori.adventure.title.Title.title(
                me.Evan52098.genscore.info.Text.legacy(code + "&l" + def.name()),
                me.Evan52098.genscore.info.Text.legacy("&7hatched from a " + code + PetsSettings.RARITY_NAMES[rarity] + " &7egg!")));
        p.playSound(p, rarity >= 2 ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.2f);
        p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1.2, 0), 20, 0.5, 0.5, 0.5);
        if (rarity == 3 && s.announceLegendary()) {
            module.announce(me.Evan52098.genscore.info.Text.legacy("&6&lPETS &8» &f" + p.getName() + " &7hatched a &6&lLegendary "
                    + def.name() + "&7!"));
        }
        open(p, page);
    }

    void detail(Player p, String id, int back) {
        PetData d = module.data(p.getUniqueId());
        PetsSettings s = module.settings();
        PetData.Pet pet = d == null ? null : d.pet(id);
        PetsSettings.PetDef def = pet == null ? null : s.pet(pet.type);
        if (d == null || pet == null) {
            open(p, back);
            return;
        }
        Menu menu = new Menu(4, MenuStyle.title("Pets", def == null ? pet.type : def.name()));
        menu.set(13, petItem(s, pet, d, pet.equipped));
        boolean max = pet.level >= s.maxLevel();
        long tokens = module.tokens(p.getUniqueId());
        menu.set(19, MenuStyle.icon(Material.COOKIE, "&6Feed 1 treat", List.of(
                "&7+" + s.treatXp() + " xp for &e" + s.treatTokens() + " tokens&7.",
                "&7You have &e" + tokens + " tokens&7.", "",
                max ? "&aThis pet is max level." : me.Evan52098.genscore.menu.Lore.prompt("feed"))), c -> feed(p, id, 1, back));
        menu.set(20, MenuStyle.icon(Material.CAKE, "&6Feed 10 treats", List.of(
                "&7+" + (s.treatXp() * 10) + " xp for &e" + (s.treatTokens() * 10) + " tokens&7.", "",
                max ? "&aThis pet is max level." : me.Evan52098.genscore.menu.Lore.prompt("feed"))), c -> feed(p, id, 10, back));
        int same = d.sameType(pet.type, pet.id);
        menu.set(22, MenuStyle.icon(Material.GOLD_BLOCK, "&6&lFuse Golden", List.of(
                "&7Fuse this pet with 2 more " + (def == null ? pet.type : def.name()),
                "&7into one &6Golden &7pet (x" + trim(s.goldenMultiplier()) + " boost).",
                "&7It keeps the highest level.", "",
                pet.golden ? "&6Already golden." : same >= 2 ? me.Evan52098.genscore.menu.Lore.prompt("fuse") : "&cYou need " + (2 - same) + " more.")),
                c -> fuse(p, id, back));
        int refund = def == null ? 0 : s.releaseTokens()[def.rarity()] * (pet.golden ? 3 : 1);
        menu.set(25, MenuStyle.icon(Material.LAVA_BUCKET, "&cRelease", List.of(
                "&7Let this pet go for &e" + refund + " tokens&7.", "&cThis can't be undone.", "",
                "&e&lSHIFT-CLICK &7to release")), c -> {
            if (!c.isShiftClick()) {
                p.sendMessage(Msg.neutral("Shift-click to release this pet."));
                return;
            }
            if (d.remove(id)) {
                d.recompute(s);
                module.giveTokens(p.getUniqueId(), refund);
                p.sendMessage(Msg.neutral("Released. +" + Msg.white(refund + " tokens") + "."));
                p.playSound(p, Sound.ENTITY_ITEM_BREAK, 0.6f, 1.2f);
            }
            open(p, back);
        });
        menu.set(27, MenuStyle.back("your pets"), c -> open(p, back));
        MenuStyle.frame(menu, 4);
        menu.open(p);
    }

    private void feed(Player p, String id, int treats, int back) {
        PetData d = module.data(p.getUniqueId());
        PetsSettings s = module.settings();
        PetData.Pet pet = d == null ? null : d.pet(id);
        if (pet == null || pet.level >= s.maxLevel()) {
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (!module.spendTokens(p.getUniqueId(), (long) s.treatTokens() * treats)) {
            p.sendMessage(Msg.bad("You need " + Msg.white((s.treatTokens() * treats) + " tokens") + "."));
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        int levels = d.addXp(id, s.treatXp() * treats, s);
        d.recompute(s);
        p.playSound(p, Sound.ENTITY_GENERIC_EAT, 0.6f, 1.2f);
        if (levels > 0) {
            PetData.Pet now = d.pet(id);
            p.sendMessage(Msg.good("Level up! Now level " + Msg.white(String.valueOf(now == null ? 0 : now.level)) + "."));
            p.playSound(p, Sound.ENTITY_PLAYER_LEVELUP, 0.5f, 1.6f);
        }
        detail(p, id, back);
    }

    private void fuse(Player p, String id, int back) {
        PetData d = module.data(p.getUniqueId());
        PetsSettings s = module.settings();
        if (d == null) {
            return;
        }
        PetData.Pet result = d.fuse(id);
        if (result == null) {
            p.playSound(p, Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        d.recompute(s);
        PetsSettings.PetDef def = s.pet(result.type);
        p.showTitle(net.kyori.adventure.title.Title.title(
                me.Evan52098.genscore.info.Text.legacy("&6&lGOLDEN " + (def == null ? result.type : def.name()).toUpperCase()),
                me.Evan52098.genscore.info.Text.legacy("&7Three pets fused into one!")));
        p.playSound(p, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.7f, 1.0f);
        p.getWorld().spawnParticle(Particle.TOTEM_OF_UNDYING, p.getLocation().add(0, 1, 0), 40, 0.5, 0.7, 0.5);
        detail(p, id, back);
    }

    private static String trim(double v) {
        return v == Math.floor(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
