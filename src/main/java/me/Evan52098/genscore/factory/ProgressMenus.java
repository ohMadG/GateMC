package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/** The token shop, the cache menu, defences and raid gear. All run on the player's thread. */
final class ProgressMenus {

    private final FactoryModule module;
    private final NamespacedKey pickKey;

    ProgressMenus(FactoryModule module) {
        this.module = module;
        this.pickKey = new NamespacedKey(module.plugin(), "raid_pick");
    }

    // ------------------------------------------------------------------ token shop

    void tokens(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null) {
            return;
        }
        ProgressSettings ps = module.progress();
        menuOf.put(p.getUniqueId(), Kind.TOKENS);
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Token shop"));
        menu.set(4, Lore.item(Material.SUNFLOWER, "&eTokens: &f" + g.tokens(), Lore.builder(Lore.Accent.WHITE)
                .description("Tokens come off your belt now and then,", "from milestones, raids and caches.",
                        "Spend them here and on defences (/defence).").build()), null);
        tokenUpgrade(menu, 11, p, Material.GOLD_INGOT, "&6Sell value", ps.value(), g::valueLevel, g::setValueLevel,
                l -> "+" + Math.round(ps.value().perLevel() * 100 * l) + "% on everything sold");
        tokenUpgrade(menu, 13, p, Material.RABBIT_FOOT, "&aDrop luck", ps.luck(), g::luckLevel, g::setLuckLevel,
                l -> "+" + Math.round(ps.luck().perLevel() * 100 * l) + "% token and cache drops");
        menu.set(15, Lore.item(Material.CHEST, "&fBuy a Common cache", Lore.builder(Lore.Accent.WHITE)
                .stat("Price", ps.cacheTokenPrice() + " tokens").clickPrompt("buy").build()), e -> {
            if (!g.takeTokens(ps.cacheTokenPrice())) { // take first
                p.sendMessage(Msg.bad("You need " + Msg.white(ps.cacheTokenPrice() + " tokens") + "."));
                return;
            }
            g.addCache(0, 1);
            module.saveSoon(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1f);
            tokens(p);
        });
        menu.set(22, Lore.item(Material.ENDER_CHEST, "&dYour caches", Lore.builder(Lore.Accent.WHITE).clickPrompt("open /caches").build()),
                e -> caches(p));
        menu.open(p);
    }

    /** A token-priced upgrade button. Shift-click buys as many levels as the tokens cover. */
    private void tokenUpgrade(Menu menu, int slot, Player p, Material icon, String name, ProgressSettings.Upgrade up,
                              IntSupplier level, IntConsumer setLevel, java.util.function.IntFunction<String> effect) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        int l = level.getAsInt();
        long price = up.price(l);
        Lore lore = Lore.builder(Lore.Accent.WHITE).stat("Level", l + " / " + up.max()).stat("Now", effect.apply(l));
        if (price < 0) {
            lore.description("Fully upgraded.");
        } else {
            lore.stat("Next", effect.apply(l + 1)).stat("Price", price + " tokens").clickPrompt("buy")
                    .description("Shift-click: buy as many as you can afford.");
        }
        menu.set(slot, Lore.item(icon, name, lore.build()), e -> {
            int bought = 0;
            while (g != null) {
                long cost = up.price(level.getAsInt());
                if (cost < 0 || !g.takeTokens(cost)) { // take first
                    break;
                }
                setLevel.accept(level.getAsInt() + 1);
                bought++;
                if (!e.isShiftClick()) {
                    break;
                }
            }
            if (bought == 0) {
                p.sendMessage(Msg.bad(price < 0 ? "That is fully upgraded." : "You need " + Msg.white(price + " tokens") + "."));
                return;
            }
            module.saveSoon(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
            module.afterUpgrade(p.getUniqueId());
            reopen(p, slot);
        });
    }

    private void reopen(Player p, int slot) {
        // Upgrade buttons live on two menus; reopen the one that was used.
        if (menuOf.get(p.getUniqueId()) == Kind.DEFENCE) {
            defence(p);
        } else {
            tokens(p);
        }
    }

    private enum Kind { TOKENS, DEFENCE }

    private final java.util.Map<java.util.UUID, Kind> menuOf = new java.util.concurrent.ConcurrentHashMap<>();

    // ------------------------------------------------------------------ caches

    void caches(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null) {
            return;
        }
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Caches"));
        Material[] icons = {Material.BARREL, Material.CHEST, Material.ENDER_CHEST, Material.SHULKER_BOX};
        ProgressSettings ps = module.progress();
        for (int r = 0; r < 4; r++) {
            int rarity = r;
            int count = g.caches(r);
            ItemStack icon = Lore.item(icons[r], (r == 0 ? "&f" : r == 1 ? "&b" : r == 2 ? "&d" : "&6") + ProgressSettings.RARITY_NAMES[r] + " cache",
                    Lore.builder(Lore.Accent.WHITE).stat("You have", String.valueOf(count))
                            .stat("Money", "about " + Math.round(ps.cacheMinutes()[r]) + " min of income")
                            .stat("Tokens", ps.cacheTokens()[r][0] + "-" + ps.cacheTokens()[r][1])
                            .stat("Key chance", Math.round(ps.keyChance()[r] * 100) + "%")
                            .clickPrompt("break one").description("Shift-click: break up to 10.").build());
            icon.setAmount(Math.max(1, Math.min(64, count)));
            menu.set(10 + r * 2, icon, e -> {
                if (!e.isShiftClick()) {
                    // One cache: the spinning reel.
                    if (!module.rewards().openAnimated(p, rarity)) {
                        p.sendMessage(Msg.bad("You have no " + ProgressSettings.RARITY_NAMES[rarity] + " caches."));
                    }
                    return;
                }
                int opened = 0;
                for (int i = 0; i < 10; i++) {
                    if (!module.rewards().open(p, rarity)) {
                        break;
                    }
                    opened++;
                }
                if (opened == 0) {
                    p.sendMessage(Msg.bad("You have no " + ProgressSettings.RARITY_NAMES[rarity] + " caches."));
                    return;
                }
                p.closeInventory();
            });
        }
        menu.set(22, Lore.item(Material.SUNFLOWER, "&eTokens: &f" + g.tokens(), Lore.builder(Lore.Accent.WHITE)
                .clickPrompt("open the token shop").build()), e -> tokens(p));
        menu.open(p);
    }

    // ------------------------------------------------------------------ defences (tokens)

    void defence(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        PlotData d = module.plotData(p.getUniqueId());
        if (g == null || d == null) {
            return;
        }
        menuOf.put(p.getUniqueId(), Kind.DEFENCE);
        ProgressSettings ps = module.progress();
        int baseGate = module.settings().gateCloseSeconds();
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Defences"));
        menu.set(4, Lore.item(Material.SUNFLOWER, "&eTokens: &f" + g.tokens(), Lore.builder(Lore.Accent.WHITE)
                .description("Defences are paid in tokens.", "Raiders can only get in while your gate is open.")
                .stat("Shield", module.shieldText(p.getUniqueId())).build()), null);
        tokenUpgrade(menu, 10, p, Material.IRON_DOOR, "&bGate time", ps.gateTime(), d::lockLevel, d::setLockLevel,
                l -> ps.gateSeconds(baseGate, l) + "s closed per click");
        tokenUpgrade(menu, 12, p, Material.IRON_CHESTPLATE, "&7Belt armour", ps.armor(), d::armorLevel, d::setArmorLevel,
                l -> ps.segmentHealth(l) + " hits to break a segment (no gear)");
        tokenUpgrade(menu, 14, p, Material.DISPENSER, "&cTurrets", ps.turrets(), d::turretLevel, d::setTurretLevel,
                l -> l == 0 ? "none" : Math.min(l, 4) + " turret" + (Math.min(l, 4) == 1 ? "" : "s") + ", "
                        + String.format(java.util.Locale.ROOT, "%.1f", (ps.turretDamage() + ps.turretDamagePerLevel() * (l - 1)) / 2) + " hearts a shot");
        tokenUpgrade(menu, 16, p, Material.COBWEB, "&5Traps", ps.traps(), d::trapLevel, d::setTrapLevel,
                l -> l == 0 ? "none" : "Slowness " + Math.min(l, 4) + (l >= 3 ? ", throws raiders out" : ""));
        menu.set(22, Lore.item(Material.BELL, "&fAlarm", Lore.builder(Lore.Accent.WHITE)
                .description("Always on: you get a warning and a", "title when someone walks into your factory.").build()), null);
        menu.open(p);
    }

    // ------------------------------------------------------------------ raid gear (money)

    void gear(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null) {
            return;
        }
        ProgressSettings ps = module.progress();
        int level = g.gearLevel();
        long price = ps.gear().price(level);
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Raid gear"));
        menu.set(10, raidPick(level), e -> {
            giveRaidPick(p);
            p.closeInventory();
        });
        Lore lore = Lore.builder(Lore.Accent.RED).stat("Level", level + " / " + ps.gear().max())
                .stat("Damage", ps.hitDamage(level) + " per hit")
                .stat("Loot", Math.round(ps.lootShare(level) * 100) + "% of a broken segment");
        if (price < 0) {
            lore.description("Fully upgraded.");
        } else {
            lore.stat("Next", ps.hitDamage(level + 1) + " per hit, " + Math.round(ps.lootShare(level + 1) * 100) + "% loot")
                    .stat("Price", Money.compact(price * 100)).clickPrompt("buy");
        }
        menu.set(12, Lore.item(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, "&cUpgrade gear", lore.build()), e -> {
            long cost = ps.gear().price(g.gearLevel());
            if (cost < 0) {
                p.sendMessage(Msg.neutral("Your gear is fully upgraded."));
                return;
            }
            if (!module.withdraw(p, cost * 100, "raid gear level " + (g.gearLevel() + 1))) {
                return;
            }
            g.setGearLevel(g.gearLevel() + 1);
            module.saveSoon(p.getUniqueId());
            p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1f);
            refreshPicks(p);
            gear(p);
        });
        Lockpicks picks = module.lockpicks();
        if (picks.settings().enabled()) {
            int pickPrice = picks.settings().priceTokens();
            menu.set(14, Lore.item(Material.TRIPWIRE_HOOK, "&e&lLockpicks", Lore.builder(Lore.Accent.WHITE)
                    .description("Pick a closed enemy gate to", "jam it open for " + picks.settings().jamSeconds() + "s.").divider()
                    .stat("You have", String.valueOf(picks.count(p)))
                    .stat("Skill", picks.skillText(p))
                    .stat("Price", pickPrice + " tokens each")
                    .stat("Tokens", String.valueOf(g.tokens()))
                    .description("Shift-click: buy 5.").clickPrompt("buy one").build()), e -> {
                picks.buy(p, e.isShiftClick() ? 5 : 1);
                gear(p);
            });
            int tier = picks.tier(p);
            boolean max = tier >= picks.maxTier();
            Lore up = Lore.builder(Lore.Accent.WHITE)
                    .description("Better lockpicks make every", "lock game easier. Stacks with skill.").divider()
                    .stat("Now", Lockpicks.tierName(tier))
                    .stat("Bonus", tier == 0 ? "none" : Lockpicks.tierPerks(tier));
            if (max) {
                up.description("", "&aBest lockpick unlocked.");
            } else {
                up.stat("Next", Lockpicks.tierName(tier + 1))
                        .stat("Next bonus", Lockpicks.tierPerks(tier + 1))
                        .stat("Cost", picks.settings().upgradeCosts()[tier] + " tokens")
                        .clickPrompt("upgrade");
            }
            menu.set(16, MenuStyle.clean(Lore.item(Lockpicks.TIER_ICONS[Math.min(Lockpicks.TIER_ICONS.length - 1, tier + (max ? 0 : 1))],
                    "&e&lUpgrade Lockpick", up.build()), max), e -> {
                picks.upgrade(p);
                gear(p);
            });
        }
        menu.set(22, Lore.item(Material.PAPER, "&fHow raiding works", Lore.builder(Lore.Accent.WHITE)
                .description("Walk into a factory whose gate is open.", "Hit its belt with the Raid Pick to break",
                        "segments. Items on a broken segment spill:", "you take a share, the rest is lost.",
                        "After " + ps.segmentsPerRaid() + " segments the factory gets a " + ps.shieldMinutes() + " min shield.",
                        "Watch out for turrets and traps.").build()), null);
        menu.open(p);
    }

    ItemStack raidPick(int level) {
        ItemStack pick = new ItemStack(level >= 15 ? Material.NETHERITE_PICKAXE : level >= 5 ? Material.DIAMOND_PICKAXE : Material.IRON_PICKAXE);
        ItemMeta meta = pick.getItemMeta();
        meta.displayName(Msg.parse("<red><bold>Raid Pick</bold></red> <gray>Lv " + level).decoration(TextDecoration.ITALIC, false));
        ProgressSettings ps = module.progress();
        meta.lore(List.of(
                Msg.parse("<gray>Breaks belts in factories with an open gate.").decoration(TextDecoration.ITALIC, false),
                Msg.parse("<gray>Damage <white>" + ps.hitDamage(level) + "</white>, loot <white>"
                        + Math.round(ps.lootShare(level) * 100) + "%").decoration(TextDecoration.ITALIC, false)));
        meta.setUnbreakable(true);
        meta.setItemModel(pickModel(level));
        meta.getPersistentDataContainer().set(pickKey, PersistentDataType.INTEGER, level);
        pick.setItemMeta(meta);
        return pick;
    }

    private static org.bukkit.NamespacedKey pickModel(int level) {
        return new org.bukkit.NamespacedKey("genscore", "raid_pick_" + (level >= 15 ? 3 : level >= 5 ? 2 : 1));
    }

    void restylePicks(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (!isRaidPick(it)) {
                continue;
            }
            Integer level = it.getItemMeta().getPersistentDataContainer().get(pickKey, PersistentDataType.INTEGER);
            int lv = level == null ? 0 : level;
            if (!pickModel(lv).equals(it.getItemMeta().getItemModel())) {
                p.getInventory().setItem(i, raidPick(lv));
            }
        }
    }

    boolean isRaidPick(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(pickKey, PersistentDataType.INTEGER);
    }

    void giveRaidPick(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        for (ItemStack it : p.getInventory().getContents()) {
            if (isRaidPick(it)) {
                p.sendMessage(Msg.neutral("You already have a Raid Pick."));
                return;
            }
        }
        var left = p.getInventory().addItem(raidPick(g == null ? 0 : g.gearLevel()));
        if (!left.isEmpty()) {
            p.sendMessage(Msg.bad("Your inventory is full."));
            return;
        }
        p.sendMessage(Msg.good("You got a " + Msg.white("Raid Pick") + ". Hit belts in factories with open gates."));
    }

    /** After a gear upgrade, the picks they carry show the new level. */
    private void refreshPicks(Player p) {
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isRaidPick(contents[i])) {
                p.getInventory().setItem(i, raidPick(g == null ? 0 : g.gearLevel()));
            }
        }
    }

    void openedTokens(Player p) {
        menuOf.put(p.getUniqueId(), Kind.TOKENS);
    }
}
