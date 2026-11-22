package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;

final class Managers {

    record Tier(String name, String color, Material icon, long tokens, long credits, int everySeconds, int maxBuys,
                boolean gen, boolean machines, String perk) {
    }

    static final List<Tier> TIERS = List.of(
            new Tier("Foreman", "<yellow>", Material.LEATHER_HELMET, 250, 0, 10, 3, false, false,
                    "Buys belt speed and length."),
            new Tier("Supervisor", "<aqua>", Material.IRON_HELMET, 1000, 0, 5, 6, true, false,
                    "Also buys gen tiers, twice as fast."),
            new Tier("Director", "<gold>", Material.GOLDEN_HELMET, 0, 2500, 2, 12, true, true,
                    "Also builds machines, five times as fast."));

    private static final int[] RESERVES = {0, 25, 50, 75};

    private final FactoryModule module;
    private final NamespacedKey levelKey;
    private final NamespacedKey onKey;
    private final NamespacedKey reserveKey;

    Managers(FactoryModule module, org.bukkit.plugin.Plugin plugin) {
        this.module = module;
        this.levelKey = new NamespacedKey(plugin, "manager_level");
        this.onKey = new NamespacedKey(plugin, "manager_on");
        this.reserveKey = new NamespacedKey(plugin, "manager_reserve");
    }

    int level(Player p) {
        int bought = p.getPersistentDataContainer().getOrDefault(levelKey, PersistentDataType.INTEGER, 0);
        for (int i = TIERS.size(); i > bought; i--) {
            if (p.hasPermission("genscore.manager." + i)) {
                return i;
            }
        }
        return bought;
    }

    private boolean on(Player p) {
        return p.getPersistentDataContainer().getOrDefault(onKey, PersistentDataType.BYTE, (byte) 1) == 1;
    }

    private int reserve(Player p) {
        return p.getPersistentDataContainer().getOrDefault(reserveKey, PersistentDataType.INTEGER, 0);
    }

    void start(Player p) {
        int[] clock = {0};
        Tasks.entity.timer(p, t -> {
            if (!p.isOnline()) {
                t.cancel();
                return;
            }
            int lv = level(p);
            if (lv == 0 || !on(p)) {
                return;
            }
            Tier tier = TIERS.get(lv - 1);
            if (++clock[0] % tier.everySeconds() != 0) {
                return;
            }
            work(p, tier);
        }, 40L, 20L);
    }

    private void work(Player p, Tier tier) {
        EconomyModule eco = module.plugin().modules().get(EconomyModule.class);
        Factory f = module.factoryOf(p.getUniqueId());
        PlotData data = module.plotData(p.getUniqueId());
        if (eco == null || eco.service() == null || f == null || data == null) {
            return;
        }
        FactorySettings s = module.settings();
        long balance = eco.service().balance(p.getUniqueId());
        long keep = balance * reserve(p) / 100;
        int bought = 0;
        long spent = 0;
        int genBefore = data.genLevel();
        while (bought < tier.maxBuys()) {
            long best = -1;
            int kind = -1;
            long speed = s.nextSpeedPrice(data.speedLevel());
            if (speed >= 0) {
                best = speed;
                kind = 0;
            }
            if (data.beltLength() < f.path.size()) {
                int done = Math.max(0, (data.beltLength() - module.defaultBeltLength()) / Math.max(1, s.lengthStep()));
                long length = s.lengthPrice(done);
                if (best < 0 || length < best) {
                    best = length;
                    kind = 1;
                }
            }
            if (tier.gen()) {
                long gen = s.nextTierPrice(data.genLevel());
                if (gen >= 0 && (best < 0 || gen < best)) {
                    best = gen;
                    kind = 2;
                }
            }
            if (tier.machines() && data.machines() < Machines.ALL.size()) {
                long machine = Machines.ALL.get(data.machines()).priceCents();
                if (best < 0 || machine < best) {
                    best = machine;
                    kind = 3;
                }
            }
            if (kind < 0 || balance - spent - best < keep) {
                break;
            }
            String reason = switch (kind) {
                case 0 -> "belt speed level " + (data.speedLevel() + 1) + " (manager)";
                case 1 -> "belt length upgrade (manager)";
                case 2 -> "gen tier " + (data.genLevel() + 2) + " (manager)";
                default -> "machine " + Machines.ALL.get(data.machines()).name() + " (manager)";
            };
            if (!module.withdrawQuiet(p, best, reason)) {
                break;
            }
            switch (kind) {
                case 0 -> data.setSpeedLevel(data.speedLevel() + 1);
                case 1 -> data.setBeltLength(Math.min(f.path.size(), data.beltLength() + s.lengthStep()));
                case 2 -> data.setGenLevel(data.genLevel() + 1);
                default -> {
                    Machines.Machine m = Machines.ALL.get(data.machines());
                    data.setMachines(data.machines() + 1);
                    p.sendMessage(Msg.good("Your " + Msg.white(tier.name()) + " built the " + Msg.white(m.name())
                            + ": items are worth " + Msg.white("x" + m.multiplier()) + "."));
                }
            }
            spent += best;
            bought++;
        }
        if (bought == 0) {
            return;
        }
        module.saveSoon(p.getUniqueId());
        module.propsAfterGen(p, genBefore, data.genLevel());
        p.sendActionBar(Msg.parse(tier.color() + "<bold>" + tier.name() + "</bold></" + tier.color().substring(1)
                + " <gray>bought <white>" + bought + "</white> upgrade" + (bought == 1 ? "" : "s") + " for <green>"
                + Money.compact(spent)));
        p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_CHIME, 0.4f, 1.6f);
    }

    void open(Player p) {
        me.Evan52098.genscore.tutorial.TutorialModule.done(p, "managers");
        int lv = level(p);
        boolean on = on(p);
        int reserve = reserve(p);
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Managers"));
        for (int i = 0; i < TIERS.size(); i++) {
            Tier t = TIERS.get(i);
            int n = i + 1;
            Lore lore = Lore.builder(lv >= n ? Lore.Accent.GREEN : Lore.Accent.WHITE)
                    .subcategory("Manager " + n)
                    .description(t.perk(), "Spends your money on the", "cheapest upgrade, all by itself.")
                    .divider()
                    .stat("Works every", t.everySeconds() + "s")
                    .stat("Upgrades per round", String.valueOf(t.maxBuys()))
                    .stat("Price", t.credits() > 0 ? t.credits() + " credits" : t.tokens() + " tokens");
            if (lv >= n) {
                lore.description("&aHired.");
            } else if (n == lv + 1) {
                lore.clickPrompt("hire the " + t.name());
            } else {
                lore.description("&7Hire the " + TIERS.get(i - 1).name() + " first.");
            }
            menu.set(11 + i * 2, Lore.item(t.icon(), legacy(t.color()) + "&l" + t.name(), lore.build()), e -> {
                if (n == lv + 1) {
                    hire(p, n);
                }
            });
        }
        if (lv > 0) {
            menu.set(MenuStyle.shelf(rows, 3), Lore.item(on ? Material.LIME_DYE : Material.GRAY_DYE, on ? "&aManager: working" : "&7Manager: on break",
                    Lore.builder(Lore.Accent.WHITE).clickPrompt(on ? "send them on break" : "put them back to work").build()), e -> {
                p.getPersistentDataContainer().set(onKey, PersistentDataType.BYTE, (byte) (on ? 0 : 1));
                open(p);
            });
            menu.set(MenuStyle.shelf(rows, 5), Lore.item(Material.GOLD_INGOT, "&6Keep in the bank: &f" + reserve + "%",
                    Lore.builder(Lore.Accent.WHITE).description("The manager never spends", "this share of your balance.")
                            .clickPrompt("change it").build()), e -> {
                int next = RESERVES[0];
                for (int i = 0; i < RESERVES.length; i++) {
                    if (RESERVES[i] == reserve) {
                        next = RESERVES[(i + 1) % RESERVES.length];
                    }
                }
                p.getPersistentDataContainer().set(reserveKey, PersistentDataType.INTEGER, next);
                open(p);
            });
        }
        menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), e -> p.closeInventory());
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private void hire(Player p, int n) {
        if (level(p) != n - 1) {
            return;
        }
        Tier t = TIERS.get(n - 1);
        if (t.credits() > 0) {
            var credits = ShardsModule.service();
            if (credits == null || !credits.withdraw(p.getUniqueId(), t.credits(), "manager " + t.name())) {
                p.sendMessage(Msg.bad("You need " + Msg.white(t.credits() + " credits") + " to hire the " + t.name() + "."));
                return;
            }
        } else {
            var g = module.gensPlayer(p.getUniqueId());
            if (g == null || !g.takeTokens(t.tokens())) {
                p.sendMessage(Msg.bad("You need " + Msg.white(t.tokens() + " tokens") + " to hire the " + t.name() + "."));
                return;
            }
            module.saveSoon(p.getUniqueId());
        }
        PersistentDataContainer pdc = p.getPersistentDataContainer();
        pdc.set(levelKey, PersistentDataType.INTEGER, n);
        pdc.set(onKey, PersistentDataType.BYTE, (byte) 1);
        module.celebrate(p, t.color() + "<bold>" + t.name().toUpperCase(java.util.Locale.ROOT) + " HIRED", "<gray>" + t.perk(), true);
        open(p);
    }

    private static String legacy(String mini) {
        return switch (mini) {
            case "<yellow>" -> "&e";
            case "<aqua>" -> "&b";
            default -> "&6";
        };
    }
}
