package me.Evan52098.genscore.merchant;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.factory.FactoryModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.rewardbox.RewardBox;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

public final class MerchantModule implements GensModule, Listener {

    enum Currency { TOKENS, CREDITS, INCOME }

    record Offer(String id, String name, Material icon, Currency currency, long price, String command) {
    }

    record Deal(Offer offer, int discount) {
        long price() {
            return Math.max(1, Math.round(offer.price() * (100 - discount) / 100.0));
        }
    }

    static final List<Offer> POOL = List.of(
            new Offer("keys_common", "&a3x Common Key", Material.TRIPWIRE_HOOK, Currency.TOKENS, 30, "crate give {player} vote 3"),
            new Offer("key_rare", "&b1x Rare Key", Material.TRIPWIRE_HOOK, Currency.TOKENS, 60, "crate give {player} cosmetic 1"),
            new Offer("key_epic", "&d1x Epic Key", Material.TRIPWIRE_HOOK, Currency.CREDITS, 40, "crate give {player} amethyst 1"),
            new Offer("key_legend", "&61x Legendary Key", Material.TRIPWIRE_HOOK, Currency.CREDITS, 150, "crate give {player} op 1"),
            new Offer("boost_30", "&b2x Money &7(30 min)", Material.CLOCK, Currency.INCOME, 20, "gensreward {player} boost 30"),
            new Offer("boost_60", "&b2x Money &7(1 hour)", Material.CLOCK, Currency.TOKENS, 45, "gensreward {player} boost 60"),
            new Offer("cache_epic", "&5Epic Cache", Material.ENDER_CHEST, Currency.INCOME, 40, "gensreward {player} cache epic 1"),
            new Offer("cache_legend", "&6Legendary Cache", Material.ENDER_CHEST, Currency.TOKENS, 120, "gensreward {player} cache legendary 1"),
            new Offer("tokens_20", "&e20 Tokens", Material.SUNFLOWER, Currency.INCOME, 60, "gensreward {player} tokens 20"),
            new Offer("tokens_50", "&e50 Tokens", Material.SUNFLOWER, Currency.CREDITS, 25, "gensreward {player} tokens 50"),
            new Offer("egg_rare", "&9Rare Pet Egg", Material.TURTLE_EGG, Currency.TOKENS, 80, "pets egg give {player} rare 1"),
            new Offer("egg_epic", "&5Epic Pet Egg", Material.SNIFFER_EGG, Currency.CREDITS, 60, "pets egg give {player} epic 1"),
            new Offer("egg_legend", "&6Legendary Pet Egg", Material.DRAGON_EGG, Currency.CREDITS, 200, "pets egg give {player} legendary 1"),
            new Offer("power_overclock", "&bPower: Overclock", Material.REPEATER, Currency.TOKENS, 150, "powers grant {player} overclock"),
            new Offer("aura_static", "&eStatic Aura", Material.LIGHTNING_ROD, Currency.CREDITS, 120, "cosmetics give {player} auras static"));

    private static final int[] DISCOUNTS = {10, 10, 15, 15, 20, 20, 25, 30, 40};
    private static volatile MerchantModule instance;

    private GensCore plugin;
    private NamespacedKey npcKey;
    private volatile int intervalMinutes;
    private volatile int stayMinutes;
    private volatile int offersPerVisit;
    private volatile double[] spot;
    private volatile long arriveAt;
    private volatile long leaveAt;
    private volatile boolean warned;
    private volatile List<Deal> deals = List.of();
    private final Map<UUID, Set<String>> bought = new ConcurrentHashMap<>();
    private volatile @Nullable Mannequin npc;

    @Override
    public String id() {
        return "merchant";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "merchant");
        reload();
        arriveAt = System.currentTimeMillis() + 10 * 60_000L;
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
        Tasks.async.timer(t -> tick(), 5, 5, TimeUnit.SECONDS);
        plugin.getLogger().info("[merchant] visits every " + intervalMinutes + " min for " + stayMinutes + " min, first in 10 min.");
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("merchant");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        intervalMinutes = Math.max(5, c.getInt("interval-minutes", 60));
        stayMinutes = Math.max(1, Math.min(intervalMinutes - 1, c.getInt("stay-minutes", 15)));
        offersPerVisit = Math.max(1, Math.min(7, c.getInt("offers", 5)));
        spot = new double[]{c.getDouble("x", 1152.5), c.getDouble("y", 65), c.getDouble("z", 1126.5), c.getDouble("yaw", 0)};
    }

    @Override
    public void disable() {
        instance = null;
        Mannequin m = npc;
        if (m != null) {
            Tasks.entity.run(m, m::remove);
        }
    }

    public static @Nullable String placeholder(String key) {
        MerchantModule m = instance;
        if (m == null || !"merchant".equals(key)) {
            return null;
        }
        long now = System.currentTimeMillis();
        return m.here() ? "&aAt spawn &f" + clock(m.leaveAt - now) : "&7in &f" + clock(Math.max(0, m.arriveAt - now));
    }

    private boolean here() {
        return leaveAt > System.currentTimeMillis();
    }

    private static String clock(long ms) {
        long s = Math.max(0, ms / 1000);
        return s >= 60 ? (s / 60) + "m" : s + "s";
    }

    private void tick() {
        long now = System.currentTimeMillis();
        if (!here() && now >= arriveAt) {
            arrive();
        } else if (here() && !warned && leaveAt - now <= 120_000L) {
            warned = true;
            Tasks.global.run(() -> Bukkit.broadcast(Msg.parse(Msg.headerText("Merchant") + "<gray>The Travelling Merchant leaves in "
                    + "<white>2 minutes</white>! <yellow><click:run_command:'/merchant'>[Shop now]</click></yellow>")));
        } else if (!here() && npc != null) {
            depart();
        }
        Mannequin m = npc;
        if (m != null && here()) {
            Tasks.entity.run(m, () -> {
                m.setDescription(Msg.parse("<gray>Leaves in <white>" + clock(leaveAt - System.currentTimeMillis())));
                m.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, m.getLocation().add(0, 1.2, 0), 6, 0.4, 0.6, 0.4, 0);
                m.getWorld().spawnParticle(Particle.END_ROD, m.getLocation().add(0, 2.3, 0), 2, 0.3, 0.1, 0.3, 0.01);
            });
        }
    }

    private void arrive() {
        List<Offer> pool = new ArrayList<>(POOL);
        Collections.shuffle(pool);
        List<Deal> picked = new ArrayList<>();
        for (int i = 0; i < Math.min(offersPerVisit, pool.size()); i++) {
            picked.add(new Deal(pool.get(i), DISCOUNTS[ThreadLocalRandom.current().nextInt(DISCOUNTS.length)]));
        }
        deals = List.copyOf(picked);
        bought.clear();
        warned = false;
        long now = System.currentTimeMillis();
        leaveAt = now + stayMinutes * 60_000L;
        arriveAt = now + intervalMinutes * 60_000L;
        spawnNpc();
        int best = picked.stream().mapToInt(Deal::discount).max().orElse(0);
        Tasks.global.run(() -> {
            Bukkit.broadcast(Msg.parse(Msg.headerText("Merchant") + "<gray>The <gold>Travelling Merchant</gold> is at spawn for <white>"
                    + stayMinutes + " minutes</white> with deals up to <green>" + best + "% off</green>! "
                    + "<yellow><click:run_command:'/merchant'>[Shop now]</click></yellow>"));
            for (Player p : Bukkit.getOnlinePlayers()) {
                Tasks.entity.run(p, () -> p.playSound(p.getLocation(), Sound.ENTITY_WANDERING_TRADER_YES, 1f, 1f));
            }
        });
    }

    private void spawnNpc() {
        World w = Bukkit.getWorld(plugin.getConfig().getString("plots.world", "world"));
        double[] s = spot;
        if (w == null) {
            return;
        }
        Location at = new Location(w, s[0], s[1], s[2], (float) s[3], 0f);
        Tasks.region.run(at, () -> {
            for (Entity e : at.getWorld().getNearbyEntities(at, 4, 4, 4)) {
                if (e.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
            npc = at.getWorld().spawn(at, Mannequin.class, n -> {
                n.setPersistent(false);
                n.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
                n.setImmovable(true);
                n.setAI(false);
                n.setGravity(false);
                n.setInvulnerable(true);
                n.setSilent(true);
                n.setCollidable(false);
                n.setRemoveWhenFarAway(false);
                n.setGlowing(true);
                n.customName(Msg.parse("<gold><bold>Travelling Merchant"));
                n.setCustomNameVisible(true);
                n.setDescription(Msg.parse("<gray>Click to trade"));
                n.setProfile(ResolvableProfile.resolvableProfile().name("GateMC_merchant").build());
                n.getEquipment().setItemInMainHand(new ItemStack(Material.EMERALD));
                n.getEquipment().setHelmet(new ItemStack(Material.CARVED_PUMPKIN));
            });
            at.getWorld().spawnParticle(Particle.CLOUD, at.clone().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.05);
            at.getWorld().playSound(at, Sound.ENTITY_WANDERING_TRADER_REAPPEARED, 1.5f, 1f);
        });
    }

    private void depart() {
        Mannequin m = npc;
        npc = null;
        deals = List.of();
        if (m != null) {
            Tasks.entity.run(m, () -> {
                m.getWorld().spawnParticle(Particle.CLOUD, m.getLocation().add(0, 1, 0), 30, 0.5, 1, 0.5, 0.05);
                m.getWorld().playSound(m.getLocation(), Sound.ENTITY_WANDERING_TRADER_DISAPPEARED, 1.5f, 1f);
                m.remove();
            });
        }
        Tasks.global.run(() -> Bukkit.broadcast(Msg.parse(Msg.headerText("Merchant") + "<gray>The Travelling Merchant has left. "
                + "Back in about <white>" + (intervalMinutes - stayMinutes) + " minutes</white>.")));
    }

    @EventHandler
    public void onClick(PlayerInteractEntityEvent event) {
        if (event.getRightClicked().getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE)) {
            event.setCancelled(true);
            Player p = event.getPlayer();
            Tasks.entity.run(p, () -> open(p));
        }
    }

    @Command({"merchant", "trader"})
    @CommandPermission("genscore.merchant")
    @Description("The Travelling Merchant's deals")
    public void merchantCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.entity.run(p, () -> open(p));
    }

    @Command("merchant summon")
    @CommandPermission("genscore.merchant.admin")
    @Description("Bring the Travelling Merchant now")
    public void summon(BukkitCommandActor actor) {
        if (here()) {
            actor.sender().sendMessage(Msg.bad("The merchant is already here."));
            return;
        }
        arrive();
        actor.sender().sendMessage(Msg.good("The merchant has arrived."));
    }

    private void open(Player p) {
        if (!here()) {
            p.sendMessage(Msg.neutral("The Travelling Merchant is away. Back at spawn in "
                    + Msg.white(clock(Math.max(0, arriveAt - System.currentTimeMillis()))) + "."));
            return;
        }
        List<Deal> now = deals;
        Set<String> mine = bought.getOrDefault(p.getUniqueId(), Set.of());
        int rows = 4;
        Menu menu = new Menu(rows, MenuStyle.title("Travelling Merchant"));
        int[] slots = MenuStyle.symmetric(now.size(), 1);
        for (int i = 0; i < now.size(); i++) {
            Deal d = now.get(i);
            boolean sold = mine.contains(d.offer().id());
            long price = cost(p.getUniqueId(), d);
            Lore lore = Lore.builder(sold ? Lore.Accent.GREEN : Lore.Accent.WHITE).subcategory(d.discount() + "% off")
                    .stat("Was", "&m" + text(d.offer().currency(), cost(p.getUniqueId(), new Deal(d.offer(), 0))))
                    .stat("Now", "&a" + text(d.offer().currency(), price))
                    .divider();
            if (sold) {
                lore.description("&aBought. One per visit.");
            } else {
                lore.clickPrompt("buy it");
            }
            ItemStack icon = Lore.item(d.offer().icon(), d.offer().name(), lore.build());
            menu.set(slots[i], icon, e -> {
                if (!sold) {
                    buy(p, d);
                }
            });
        }
        menu.set(MenuStyle.shelf(rows, 4), Lore.item(Material.CLOCK, "&6Leaves in &f" + clock(leaveAt - System.currentTimeMillis()),
                Lore.builder(Lore.Accent.WHITE).description("New deals every visit,", "every " + intervalMinutes + " minutes.").build()), null);
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private long cost(UUID uuid, Deal d) {
        if (d.offer().currency() == Currency.INCOME) {
            long perMinute = Math.max(20_00L, FactoryModule.steadyIncomePerMinute(uuid));
            return Math.max(1, Math.round(perMinute * d.offer().price() * (100 - d.discount()) / 100.0));
        }
        return d.price();
    }

    private static String text(Currency c, long amount) {
        return switch (c) {
            case TOKENS -> amount + " tokens";
            case CREDITS -> amount + " credits";
            case INCOME -> Money.compact(amount);
        };
    }

    private void buy(Player p, Deal d) {
        if (!here() || !deals.contains(d)) {
            p.closeInventory();
            return;
        }
        UUID uuid = p.getUniqueId();
        Set<String> mine = bought.computeIfAbsent(uuid, k -> ConcurrentHashMap.newKeySet());
        if (!mine.add(d.offer().id())) {
            return;
        }
        long price = cost(uuid, d);
        boolean paid = switch (d.offer().currency()) {
            case TOKENS -> {
                PlotsModule plots = plugin.modules().get(PlotsModule.class);
                var g = plots == null ? null : plots.gensPlayer(uuid);
                boolean ok = g != null && g.takeTokens(price);
                if (ok) {
                    plots.saveSoon(uuid);
                }
                yield ok;
            }
            case CREDITS -> {
                var s = ShardsModule.service();
                yield s != null && s.withdraw(uuid, price, "merchant " + d.offer().id());
            }
            case INCOME -> {
                EconomyModule eco = plugin.modules().get(EconomyModule.class);
                yield eco != null && eco.service() != null && eco.service().withdraw(uuid, price, "merchant " + d.offer().id());
            }
        };
        if (!paid) {
            mine.remove(d.offer().id());
            p.sendMessage(Msg.bad("You need " + Msg.white(text(d.offer().currency(), price)) + " for that."));
            p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_NO, 1f, 1f);
            return;
        }
        String name = p.getName();
        Tasks.global.run(() -> Bukkit.dispatchCommand(Bukkit.getConsoleSender(), d.offer().command().replace("{player}", name)));
        p.sendMessage(Msg.parse(Msg.headerText("Merchant") + "<gray>You bought</gray> "
                + RewardBox.legacyToMini(d.offer().name()) + " <gray>for <white>" + text(d.offer().currency(), price) + "</white>."));
        p.playSound(p.getLocation(), Sound.ENTITY_WANDERING_TRADER_TRADE, 1f, 1f);
        open(p);
    }
}
