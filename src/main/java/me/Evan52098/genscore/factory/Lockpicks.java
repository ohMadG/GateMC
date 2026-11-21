package me.Evan52098.genscore.factory;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.plots.GensPlayer;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

final class Lockpicks {

    record Settings(boolean enabled, int priceTokens, int pins, int pinsPerLock, int maxPins, int strikes,
                    int gearPerStrike, int speedTicks, int seconds, int secondsPerPin, int jamSeconds, int cooldownSeconds,
                    double range, int maxSkill, int pointsPerLevel, int successPoints, int failPoints, int[] upgradeCosts) {
        static Settings from(@Nullable ConfigurationSection s) {
            if (s == null) {
                s = new MemoryConfiguration();
            }
            return new Settings(s.getBoolean("enabled", true), Math.max(0, s.getInt("price-tokens", 10)),
                    Math.max(1, s.getInt("pins", 3)), Math.max(0, s.getInt("pins-per-lock-level", 1)),
                    Math.max(1, Math.min(9, s.getInt("max-pins", 8))), Math.max(1, s.getInt("strikes", 1)),
                    Math.max(0, s.getInt("gear-levels-per-extra-strike", 5)), Math.max(1, s.getInt("speed-ticks", 2)),
                    Math.max(5, s.getInt("seconds", 10)), Math.max(0, s.getInt("seconds-per-pin", 2)),
                    Math.max(1, s.getInt("jam-seconds", 15)), Math.max(0, s.getInt("cooldown-seconds", 30)),
                    Math.max(2, s.getDouble("range", 4)), Math.max(0, s.getInt("skill.max-level", 10)),
                    Math.max(1, s.getInt("skill.points-per-level", 6)), Math.max(0, s.getInt("skill.success-points", 3)),
                    Math.max(0, s.getInt("skill.fail-points", 1)), costs(s.getIntegerList("upgrade-costs")));
        }

        private static int[] costs(java.util.List<Integer> list) {
            if (list.isEmpty()) {
                return new int[]{60, 150, 350, 800, 1600};
            }
            int[] out = new int[Math.min(TIERS.length - 1, list.size())];
            for (int i = 0; i < out.length; i++) {
                out[i] = Math.max(0, list.get(i));
            }
            return out;
        }
    }

    static final String[] TIERS = {"Rusty", "Iron", "Steel", "Gold", "Diamond", "Netherite"};
    static final String[] TIER_COLORS = {"&6", "&f", "&7", "&e", "&b", "&5"};
    static final Material[] TIER_ICONS = {Material.TRIPWIRE_HOOK, Material.IRON_INGOT, Material.IRON_CHAIN, Material.GOLD_INGOT,
            Material.DIAMOND, Material.NETHERITE_INGOT};
    private static final int TRACK = 18;
    private static final int TRACK_LEN = 9;

    private static final class Game {
        final Factory factory;
        final Location gate;
        final long start;
        final long deadline;
        final int pins;
        final int maxStrikes;
        final int width;
        final int skill;
        final Menu menu;
        ScheduledTask task;
        int set;
        int strikes;
        int cursor;
        int dir = 1;
        int sweet;
        int step;
        int ticks;
        long lastClick;
        boolean over;

        Game(Factory factory, Location gate, long start, long deadline, int pins, int maxStrikes, int step, int width, int skill, Menu menu) {
            this.factory = factory;
            this.gate = gate;
            this.start = start;
            this.deadline = deadline;
            this.pins = pins;
            this.maxStrikes = maxStrikes;
            this.step = step;
            this.width = width;
            this.skill = skill;
            this.menu = menu;
        }
    }

    private final FactoryModule module;
    private final NamespacedKey key;
    private final NamespacedKey skillKey;
    private final NamespacedKey tierKey;
    private volatile Settings settings;
    private final Map<UUID, Game> active = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastTry = new ConcurrentHashMap<>();

    Lockpicks(FactoryModule module, org.bukkit.plugin.Plugin plugin, @Nullable ConfigurationSection config) {
        this.module = module;
        this.key = new NamespacedKey(plugin, "lockpick");
        this.skillKey = new NamespacedKey(plugin, "lockpick_skill");
        this.tierKey = new NamespacedKey(plugin, "lockpick_tier");
        this.settings = Settings.from(config);
    }

    void reload(@Nullable ConfigurationSection config) {
        settings = Settings.from(config);
    }

    Settings settings() {
        return settings;
    }

    ItemStack item(int amount) {
        ItemStack item = Lore.item(Material.TRIPWIRE_HOOK, "&e&lLockpick", Lore.builder(Lore.Accent.WHITE)
                .description("Right-click a closed enemy gate", "and set every pin to pick the lock.").divider()
                .stat("Success", "jams the gate open " + settings.jamSeconds() + "s")
                .description("&7Snaps if you fail.").build());
        item.setAmount(Math.max(1, Math.min(64, amount)));
        item.editMeta(meta -> meta.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1));
        return item;
    }

    boolean isLockpick(@Nullable ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(key, PersistentDataType.BYTE);
    }

    void buy(Player p, int amount) {
        Settings s = settings;
        if (!s.enabled()) {
            p.sendMessage(Msg.bad("Lockpicks are turned off."));
            return;
        }
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        long cost = (long) s.priceTokens() * amount;
        if (g == null || !g.takeTokens(cost)) {
            p.sendMessage(Msg.bad("You need " + Msg.white(cost + " tokens") + " for " + amount + " lockpick" + (amount == 1 ? "" : "s") + "."));
            return;
        }
        HashMap<Integer, ItemStack> left = p.getInventory().addItem(item(amount));
        int refused = left.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (refused > 0) {
            g.addTokens((long) s.priceTokens() * refused);
        }
        module.saveSoon(p.getUniqueId());
        int got = amount - refused;
        if (got <= 0) {
            p.sendMessage(Msg.bad("Your inventory is full."));
            return;
        }
        p.playSound(p.getLocation(), Sound.BLOCK_CHAIN_PLACE, 1f, 1.4f);
        p.sendMessage(Msg.good("Bought " + Msg.white(got + " lockpick" + (got == 1 ? "" : "s")) + " for "
                + Msg.white((long) s.priceTokens() * got + " tokens") + "."));
    }

    int count(Player p) {
        int n = 0;
        for (ItemStack it : p.getInventory().getContents()) {
            if (isLockpick(it)) {
                n += it.getAmount();
            }
        }
        return n;
    }

    void start(Player p, Factory f, Location gate) {
        Settings s = settings;
        UUID id = p.getUniqueId();
        if (!s.enabled() || active.containsKey(id)) {
            return;
        }
        if (!f.gateClosed()) {
            p.sendActionBar(Msg.bad("This gate is already open."));
            return;
        }
        if (module.bypasses(p)) {
            p.sendActionBar(Msg.bad("You can't lockpick in staff mode."));
            return;
        }
        PlotData data = module.plotData(f.view.owner());
        long now = System.currentTimeMillis();
        if (data != null && data.shieldUntil() > now) {
            p.sendActionBar(Msg.bad("This factory is shielded from raids."));
            return;
        }
        Player owner = Bukkit.getPlayer(f.view.owner());
        if (FactoryModule.sameNetwork(p, owner) && !p.hasPermission(FactoryModule.BYPASS)) {
            p.sendActionBar(Msg.bad("You can't pick a gate owned by someone on your connection."));
            return;
        }
        long wait = lastTry.getOrDefault(id, 0L) + s.cooldownSeconds() * 1000L - now;
        if (wait > 0) {
            p.sendActionBar(Msg.bad("Your hands are shaking. Try again in " + ((wait + 999) / 1000) + "s."));
            return;
        }
        int lock = data == null ? 0 : data.lockLevel();
        GensPlayer g = module.gensPlayer(id);
        int gear = g == null ? 0 : g.gearLevel();
        int pins = Math.min(s.maxPins(), s.pins() + s.pinsPerLock() * lock);
        int skill = level(p);
        int tier = tier(p);
        int strikes = s.strikes() + skill / 3 + tier / 2 + (s.gearPerStrike() > 0 ? gear / s.gearPerStrike() : 0);
        int width = Math.min(TRACK_LEN - 3, 1 + (skill >= 5 ? 1 : 0) + (skill >= 10 ? 1 : 0) + (tier >= 3 ? 1 : 0) + (tier >= 5 ? 1 : 0));
        int step = s.speedTicks() + skill / 3 + (tier + 1) / 2;
        long deadline = now + (s.seconds() + skill + tier * 2L + (long) s.secondsPerPin() * pins) * 1000L;
        lastTry.put(id, now);
        Menu menu = new Menu(5, MenuStyle.title("Pick the lock")).plain();
        Game game = new Game(f, gate.clone(), now, deadline, pins, strikes, step, width, skill, menu);
        game.sweet = 2 + ThreadLocalRandom.current().nextInt(TRACK_LEN - width - 1);
        for (int slot = 0; slot < 45; slot++) {
            menu.set(slot, MenuStyle.pane(MenuStyle.FRAME), e -> click(p));
        }
        draw(game, s);
        active.put(id, game);
        menu.open(p);
        game.task = Tasks.entity.timer(p, t -> tick(p), 1L, 1L);
        if (game.task == null) {
            active.remove(id, game);
            return;
        }
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_TRAPDOOR_OPEN, 0.7f, 1.8f);
        if (owner != null) {
            String name = p.getName();
            Tasks.entity.run(owner, () -> {
                owner.showTitle(Title.title(Msg.parse("<red><bold>LOCKPICK!"), Msg.parse("<white>" + Msg.escape(name)
                        + " <gray>is picking your gate. <yellow>Click it to jam the lock!"),
                        Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(400))));
                owner.playSound(owner.getLocation(), Sound.BLOCK_BELL_USE, 1f, 0.8f);
                owner.sendMessage(Msg.bad(Msg.white(name) + " is picking your gate lock! Click your gate to jam it."));
            });
        }
    }

    private void draw(Game g, Settings s) {
        Menu m = g.menu;
        int[] pinSlots = MenuStyle.symmetric(g.pins, 0);
        for (int i = 0; i < pinSlots.length; i++) {
            boolean done = i < g.set;
            m.set(pinSlots[i], Lore.item(done ? Material.LIME_DYE : Material.GRAY_DYE, done ? "&aPin " + (i + 1) + " set" : "&7Pin " + (i + 1),
                    List.of()), e -> click(lookup(e)));
        }
        for (int i = 0; i < TRACK_LEN; i++) {
            boolean cur = i == g.cursor;
            boolean sweet = i >= g.sweet && i < g.sweet + g.width;
            ItemStack it;
            if (cur && sweet) {
                it = MenuStyle.clean(Lore.item(Material.LIME_STAINED_GLASS_PANE, "&a&lNOW! &fClick!", List.of()), true);
            } else if (cur) {
                it = Lore.item(Material.WHITE_STAINED_GLASS_PANE, "&fTension", List.of());
            } else if (sweet) {
                it = Lore.item(Material.YELLOW_STAINED_GLASS_PANE, "&eSweet spot", List.of());
            } else {
                it = MenuStyle.pane(Material.GRAY_STAINED_GLASS_PANE);
            }
            m.set(TRACK + i, it, e -> click(lookup(e)));
        }
        long left = Math.max(0, (g.deadline - System.currentTimeMillis() + 999) / 1000);
        m.set(39, Lore.item(Material.CLOCK, "&e" + left + "s left", Lore.builder(Lore.Accent.WHITE)
                .description("Click when the tension marker", "is on the sweet spot.").build()), e -> click(lookup(e)));
        int lives = g.maxStrikes - g.strikes;
        m.set(41, Lore.item(lives > 1 ? Material.TRIPWIRE_HOOK : Material.REDSTONE, "&c" + lives + " &7misses left",
                List.of()), e -> click(lookup(e)));
        m.set(40, Lore.item(Material.IRON_DOOR, "&fPins &a" + g.set + "&7/&f" + g.pins, List.of()), e -> click(lookup(e)));
        m.set(37, Lore.item(Material.EXPERIENCE_BOTTLE, "&bSkill " + g.skill + "&7/&f" + s.maxSkill(), Lore.builder(Lore.Accent.WHITE)
                .description("Practice makes it easier:", "slower marker, more misses,", "more time, a wider sweet spot.").build()),
                e -> click(lookup(e)));
    }

    private static boolean onSweet(Game g) {
        return g.cursor >= g.sweet && g.cursor < g.sweet + g.width;
    }

    private int points(Player p) {
        return p.getPersistentDataContainer().getOrDefault(skillKey, PersistentDataType.INTEGER, 0);
    }

    int level(Player p) {
        Settings s = settings;
        return Math.min(s.maxSkill(), points(p) / s.pointsPerLevel());
    }

    int tier(Player p) {
        return Math.max(0, Math.min(TIERS.length - 1, p.getPersistentDataContainer().getOrDefault(tierKey, PersistentDataType.INTEGER, 0)));
    }

    int maxTier() {
        return Math.min(TIERS.length - 1, settings.upgradeCosts().length);
    }

    static String tierName(int tier) {
        return TIER_COLORS[tier] + TIERS[tier] + " Lockpick";
    }

    static String tierPerks(int tier) {
        return "+" + (tier * 2) + "s, +" + (tier / 2) + " miss" + (tier / 2 == 1 ? "" : "es") + ", slower marker"
                + (tier >= 5 ? ", +2 sweet spot" : tier >= 3 ? ", +1 sweet spot" : "");
    }

    void upgrade(Player p) {
        Settings s = settings;
        int tier = tier(p);
        if (tier >= maxTier()) {
            p.sendMessage(Msg.neutral("Your lockpick is already " + Msg.white(TIERS[tier]) + ", the best there is."));
            return;
        }
        long cost = s.upgradeCosts()[tier];
        GensPlayer g = module.gensPlayer(p.getUniqueId());
        if (g == null || !g.takeTokens(cost)) {
            p.sendMessage(Msg.bad("You need " + Msg.white(cost + " tokens") + " to upgrade your lockpick."));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        module.saveSoon(p.getUniqueId());
        p.getPersistentDataContainer().set(tierKey, PersistentDataType.INTEGER, tier + 1);
        p.playSound(p.getLocation(), Sound.BLOCK_SMITHING_TABLE_USE, 1f, 1.2f);
        p.sendMessage(Msg.good("Lockpick upgraded to " + Msg.white(TIERS[tier + 1]) + ": every pick is now easier."));
    }

    String skillText(Player p) {
        Settings s = settings;
        int lv = level(p);
        if (lv >= s.maxSkill()) {
            return lv + " (master)";
        }
        return lv + " (" + (points(p) % s.pointsPerLevel()) + "/" + s.pointsPerLevel() + " to next)";
    }

    private void learn(Player p, int gained) {
        if (gained <= 0) {
            return;
        }
        int before = level(p);
        p.getPersistentDataContainer().set(skillKey, PersistentDataType.INTEGER, points(p) + gained);
        int after = level(p);
        if (after > before) {
            p.sendMessage(Msg.good("Lockpicking skill up! You are now level " + Msg.white(String.valueOf(after))
                    + ": the lock game gets a little easier."));
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
        }
    }

    private Player lookup(org.bukkit.event.inventory.InventoryClickEvent e) {
        return (Player) e.getWhoClicked();
    }

    private void tick(Player p) {
        UUID id = p.getUniqueId();
        Game g = active.get(id);
        if (g == null || g.over) {
            return;
        }
        if (!p.isOnline() || p.isDead()) {
            end(p, g, null, false);
            return;
        }
        if (!g.factory.gateClosed()) {
            end(p, g, "The gate opened on its own.", false);
            return;
        }
        Location here = p.getLocation();
        Settings s = settings;
        if (here.getWorld() != g.gate.getWorld() || here.distanceSquared(g.gate) > s.range() * s.range()) {
            end(p, g, "You moved away from the gate.", false);
            return;
        }
        if (module.lastCombatAt(id) > g.start) {
            end(p, g, "You got hit and lost your grip.", false);
            return;
        }
        if (System.currentTimeMillis() > g.deadline) {
            fail(p, g, "You ran out of time.");
            return;
        }
        if (++g.ticks % g.step != 0) {
            return;
        }
        int next = g.cursor + g.dir;
        if (next < 0 || next >= TRACK_LEN) {
            g.dir = -g.dir;
            next = g.cursor + g.dir;
        }
        g.cursor = next;
        draw(g, s);
        if (onSweet(g)) {
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, 0.5f, 1.8f);
        }
    }

    private void click(Player p) {
        UUID id = p.getUniqueId();
        Game g = active.get(id);
        if (g == null || g.over) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - g.lastClick < 120) {
            return;
        }
        g.lastClick = now;
        Settings s = settings;
        if (onSweet(g)) {
            g.set++;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 0.8f, 0.8f + 0.15f * g.set);
            if (g.set >= g.pins) {
                succeed(p, g);
                return;
            }
            int sweet;
            do {
                sweet = ThreadLocalRandom.current().nextInt(TRACK_LEN - g.width + 1);
            } while (g.cursor >= sweet - 1 && g.cursor <= sweet + g.width);
            g.sweet = sweet;
            if (g.set % 2 == 0 && g.step > 1) {
                g.step--;
            }
        } else {
            g.strikes++;
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.9f, 0.6f);
            if (g.strikes >= g.maxStrikes) {
                fail(p, g, "You slipped too many times.");
                return;
            }
        }
        draw(g, s);
    }

    private void succeed(Player p, Game g) {
        if (!finish(p, g)) {
            return;
        }
        Settings s = settings;
        learn(p, s.successPoints());
        Factory f = g.factory;
        Tasks.region.run(f.view.center(), () -> f.jamOpen(s.jamSeconds()));
        p.playSound(p.getLocation(), Sound.BLOCK_IRON_DOOR_OPEN, 1f, 0.8f);
        p.showTitle(Title.title(Msg.parse("<green><bold>LOCK PICKED"), Msg.parse("<gray>The gate is jammed open for <white>"
                + s.jamSeconds() + "s"), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(1500), Duration.ofMillis(300))));
        Player owner = Bukkit.getPlayer(f.view.owner());
        String name = p.getName();
        if (owner != null) {
            Tasks.entity.run(owner, () -> {
                owner.sendMessage(Msg.bad(Msg.white(name) + " picked your lock! Your gate is jammed open for "
                        + Msg.white(s.jamSeconds() + "s") + "."));
                owner.playSound(owner.getLocation(), Sound.ENTITY_IRON_GOLEM_DAMAGE, 1f, 0.8f);
            });
        }
    }

    private void fail(Player p, Game g, String why) {
        if (!finish(p, g)) {
            return;
        }
        p.playSound(p.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        p.sendMessage(Msg.bad(why + " The lockpick snapped."));
        learn(p, settings.failPoints());
        Player owner = Bukkit.getPlayer(g.factory.view.owner());
        String name = p.getName();
        if (owner != null) {
            Tasks.entity.run(owner, () -> owner.sendMessage(Msg.good(Msg.white(name) + " failed to pick your lock.")));
        }
    }

    private boolean finish(Player p, Game g) {
        g.over = true;
        active.remove(p.getUniqueId(), g);
        if (g.task != null) {
            g.task.cancel();
        }
        if (p.getOpenInventory().getTopInventory().getHolder() == g.menu) {
            p.closeInventory();
        }
        if (!takeOne(p)) {
            p.sendMessage(Msg.bad("You have no lockpick left."));
            return false;
        }
        return true;
    }

    private void end(Player p, Game g, @Nullable String why, boolean snap) {
        g.over = true;
        active.remove(p.getUniqueId(), g);
        if (g.task != null) {
            g.task.cancel();
        }
        if (p.isOnline() && p.getOpenInventory().getTopInventory().getHolder() == g.menu) {
            p.closeInventory();
        }
        if (snap) {
            takeOne(p);
        }
        if (why != null && p.isOnline()) {
            p.sendActionBar(Msg.bad(why));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
        }
    }

    void closed(Player p, Inventory top) {
        Game g = active.get(p.getUniqueId());
        if (g == null || g.over || top.getHolder() != g.menu) {
            return;
        }
        g.over = true;
        active.remove(p.getUniqueId(), g);
        if (g.task != null) {
            g.task.cancel();
        }
        p.sendActionBar(Msg.bad("You stopped picking the lock."));
    }

    boolean jam(Player owner, Factory f) {
        for (Map.Entry<UUID, Game> e : List.copyOf(active.entrySet())) {
            Game g = e.getValue();
            if (g.factory != f || g.over) {
                continue;
            }
            Player r = Bukkit.getPlayer(e.getKey());
            if (r != null) {
                Tasks.entity.run(r, () -> {
                    end(r, g, null, true);
                    r.playSound(r.getLocation(), Sound.ENTITY_ITEM_BREAK, 1f, 0.8f);
                    r.sendMessage(Msg.bad("The owner jammed the lock. Your lockpick snapped."));
                });
            } else {
                g.over = true;
                active.remove(e.getKey(), g);
            }
            owner.sendMessage(Msg.good("You jammed the lock" + (r == null ? "" : " on " + Msg.white(r.getName())) + "."));
            owner.playSound(owner.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.6f, 1.4f);
            return true;
        }
        return false;
    }

    void forget(UUID player) {
        Game g = active.remove(player);
        if (g != null) {
            g.over = true;
            if (g.task != null) {
                g.task.cancel();
            }
        }
        lastTry.remove(player);
    }

    private boolean takeOne(Player p) {
        ItemStack[] contents = p.getInventory().getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack it = contents[i];
            if (isLockpick(it)) {
                it.setAmount(it.getAmount() - 1);
                p.getInventory().setItem(i, it.getAmount() <= 0 ? null : it);
                return true;
            }
        }
        return false;
    }
}
