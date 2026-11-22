package me.Evan52098.genscore.factory;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.plots.FactoryView;
import me.Evan52098.genscore.plots.Plot;
import me.Evan52098.genscore.plots.PlotData;
import me.Evan52098.genscore.plots.PlotListener;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Money;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.entity.Entity;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Roadmap phase 2 (belt and income) and the gate from phase 3.
 *
 * <ul>
 *   <li><b>Gate:</b> every factory has a garage door. The owner closes it (gate button, clicking the door, /gate or the
 *       belt menu) and it stays shut for {@code factory.gate.close-seconds} (45), then rolls up again. While it is shut
 *       nobody but the owner can get inside the walls; while it is open anyone can walk in.</li>
 *   <li><b>Belt:</b> the gen puts an item on the start of the belt, the belt carries it to the sell point, and the item
 *       is sold for more the further it rode. Upgrade speed and length in the belt menu: {@code /belt} or click a belt.</li>
 * </ul>
 */
public final class FactoryModule implements GensModule, Listener, PlotListener {

    public static final String BYPASS = "genscore.factory.bypass";

    private GensCore plugin;
    private PlotsModule plots;
    private volatile FactorySettings settings;
    private volatile ProgressSettings progress;
    private Rewards rewards;
    private ProgressMenus menus;
    private Managers managers;
    private Lockpicks lockpicks;
    private Trust trust;
    private final Map<UUID, Long> lastSwing = new ConcurrentHashMap<>();
    private final Map<Integer, Factory> bySlot = new ConcurrentHashMap<>();
    private final Map<UUID, Factory> byOwner = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "factory";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        powerHooks = this;
        this.plots = plugin.modules().get(PlotsModule.class);
        if (plots == null) {
            throw new IllegalStateException("The factory module needs the plots module.");
        }
        settings = FactorySettings.from(plugin.getConfig().getConfigurationSection("factory"));
        progress = ProgressSettings.from(plugin.getConfig().getConfigurationSection("factory"));
        rewards = new Rewards(this);
        menus = new ProgressMenus(this);
        managers = new Managers(this, plugin);
        lockpicks = new Lockpicks(this, plugin, plugin.getConfig().getConfigurationSection("factory.lockpick"));
        trust = new Trust(plugin);
        RankPerks.load(plugin.getConfig().getConfigurationSection("factory.rank-perks"));
        Tasks.global.timer(t -> {
            for (Player online : Bukkit.getOnlinePlayers()) {
                Tasks.onEntity(online, () -> RankPerks.refresh(online));
            }
        }, 1200, 1200);
        for (Player online : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(online, () -> managers.start(online));
        }
        plugin.commands().register(this);
        plugin.commands().register(trust);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plots.service().addListener(this);
        plugin.getLogger().info("Factory: gate closes for " + settings.gateCloseSeconds() + "s, belt starts at "
                + settings.startSpeed() + " segments/s, resource pack " + (settings.pack().enabled() ? settings.pack().url() : "off") + ".");
        logTiers();
    }

    @Override
    public void disable() {
        for (Factory f : bySlot.values()) {
            f.stop();
        }
        bySlot.clear();
        byOwner.clear();
    }

    @Override
    public void reload() {
        settings = FactorySettings.from(plugin.getConfig().getConfigurationSection("factory"));
        progress = ProgressSettings.from(plugin.getConfig().getConfigurationSection("factory"));
        RankPerks.load(plugin.getConfig().getConfigurationSection("factory.rank-perks"));
        if (lockpicks != null) {
            lockpicks.reload(plugin.getConfig().getConfigurationSection("factory.lockpick"));
        }
        logTiers();
    }

    ProgressSettings progress() {
        return progress;
    }

    Rewards rewards() {
        return rewards;
    }

    @Nullable me.Evan52098.genscore.plots.GensPlayer gensPlayer(UUID player) {
        return plots.gensPlayer(player);
    }

    void saveSoon(UUID player) {
        plots.saveSoon(player);
    }

    /** How long this owner's gate stays shut after a click, with their gate-time upgrade. */
    int gateSeconds(UUID owner) {
        PlotData data = plotData(owner);
        return progress.gateSeconds(settings.gateCloseSeconds(), data == null ? 0 : data.lockLevel())
                + (int) Math.round(me.Evan52098.genscore.powers.PowersModule.perk(owner, "gate-seconds"));
    }

    private static volatile FactoryModule powerHooks;

    public static @Nullable Location landmark(UUID owner, String which) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(owner);
        return f == null ? null : f.landmark(which);
    }

    public static boolean hasRaidPick(Player p) {
        FactoryModule m = powerHooks;
        if (m == null || m.menus == null) {
            return false;
        }
        for (org.bukkit.inventory.ItemStack it : p.getInventory().getContents()) {
            if (it != null && m.menus.isRaidPick(it)) {
                return true;
            }
        }
        return false;
    }

    public static @Nullable Location gateFront(UUID owner) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(owner);
        return f == null || !f.hasGate() ? null : f.outsideGate();
    }

    public static long incomePerMinute(UUID uuid) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(uuid);
        return f == null ? 0 : f.incomePerMinute;
    }

    public static long steadyIncomePerMinute(UUID uuid) {
        FactoryModule m = powerHooks;
        if (m == null) {
            return 0;
        }
        Factory f = m.byOwner.get(uuid);
        long live = f == null ? 0 : f.incomePerMinute;
        PlotData data = m.plotData(uuid);
        if (data == null) {
            return live;
        }
        var g = m.gensPlayer(uuid);
        FactorySettings s = m.settings;
        int length = Math.max(1, f == null ? data.beltLength() : f.activeLength());
        double perSecond = Math.min(s.speed(data.speedLevel()), 1.0 / Math.max(0.05, s.genIntervalSeconds()));
        double boost = Machines.multiplier(data.machines(), length) * (1 + REBIRTH_BONUS * (g == null ? 0 : g.rebirths()))
                * (1 + m.progress.value().perLevel() * (g == null ? 0 : g.valueLevel())) * RankPerks.sale(uuid);
        return Math.max(live, Math.round(s.itemValue(length, data.genLevel()) * perSecond * 60 * boost));
    }

    public static @Nullable String placeholder(UUID uuid, String key) {
        FactoryModule m = powerHooks;
        if (m == null) {
            return null;
        }
        Factory f = m.byOwner.get(uuid);
        PlotData data = m.plotData(uuid);
        var g = m.gensPlayer(uuid);
        return switch (key) {
            case "balance" -> {
                EconomyModule eco = m.plugin.modules().get(EconomyModule.class);
                yield eco == null || eco.service() == null ? "$0" : Money.compact(eco.service().balance(uuid));
            }
            case "income_hour" -> Money.compact(f == null ? 0 : f.incomePerMinute * 60);
            case "income_min" -> Money.compact(f == null ? 0 : f.incomePerMinute);
            case "gen_tier" -> String.valueOf((data == null ? 0 : data.genLevel()) + 1);
            case "gen_max" -> String.valueOf(m.settings.tierCount());
            case "rebirths" -> String.valueOf(g == null ? 0 : g.rebirths());
            case "machines" -> String.valueOf(data == null ? 0 : data.machines());
            case "belt" -> String.valueOf(f == null ? 0 : f.activeLength());
            case "belt_max" -> String.valueOf(f == null ? 0 : f.path.size());
            case "tokens" -> String.format("%,d", g == null ? 0 : g.tokens());
            case "gate" -> f == null || !f.hasGate() ? "&7None" : f.gateClosed() ? "&aClosed &f" + f.gateSecondsLeft() + "s" : "&cOpen";
            case "shield" -> {
                long s = f == null ? 0 : f.shieldLeftSeconds();
                yield s <= 0 ? "&7None" : "&b" + (s >= 60 ? s / 60 + "m " + s % 60 + "s" : s + "s");
            }
            default -> null;
        };
    }

    public static boolean powerCloseGate(Player p) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(p.getUniqueId());
        if (f == null || !f.hasGate()) {
            return false;
        }
        f.requestClose();
        return true;
    }

    public static boolean powerRepairAll(Player p) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(p.getUniqueId());
        if (f == null) {
            return false;
        }
        Tasks.region.run(f.view.center(), f::repairAll);
        return true;
    }

    public static boolean powerShield(Player p, long seconds) {
        FactoryModule m = powerHooks;
        PlotData data = m == null ? null : m.plotData(p.getUniqueId());
        if (data == null || m.byOwner.get(p.getUniqueId()) == null) {
            return false;
        }
        data.setShieldUntil(Math.max(data.shieldUntil(), System.currentTimeMillis() + seconds * 1000L));
        m.saveSoon(p.getUniqueId());
        return true;
    }

    String shieldText(UUID owner) {
        Factory f = byOwner.get(owner);
        long left = f == null ? 0 : f.shieldLeftSeconds();
        return left > 0 ? (left / 60) + "m " + (left % 60) + "s" : "none";
    }

    /** Something upgradeable changed (turrets): redraw what depends on it. */
    void afterUpgrade(UUID owner) {
        Factory f = byOwner.get(owner);
        if (f != null) {
            Tasks.region.run(f.view.center(), f::redrawTurrets);
        }
    }

    /** A segment was broken: log it, tell people. Region thread. */
    void raidLogged(Player raider, Factory f, long loot, int tokens, int caches, long shieldSeconds) {
        UUID raiderId = raider.getUniqueId();
        UUID owner = f.view.owner();
        long at = System.currentTimeMillis();
        plugin.database().asyncRun(c -> {
            try (var ps = c.prepareStatement("INSERT INTO gens_raid_log (raider, owner, at, segments, loot_cents, tokens, caches) "
                    + "VALUES (?, ?, ?, 1, ?, ?, ?)")) {
                ps.setString(1, raiderId.toString());
                ps.setString(2, owner.toString());
                ps.setLong(3, at);
                ps.setLong(4, loot);
                ps.setInt(5, tokens);
                ps.setInt(6, caches);
                ps.executeUpdate();
            }
        }).exceptionally(e -> {
            plugin.getLogger().warning("Could not write the raid log: " + e.getMessage());
            return null;
        });
        saveSoon(raiderId);
        String ownerName = Bukkit.getOfflinePlayer(owner).getName();
        String line = "<red>⚔</red> <white>" + Msg.escape(raider.getName()) + "</white> <gray>broke a belt in</gray> <white>"
                + Msg.escape(String.valueOf(ownerName)) + "</white><gray>'s factory" + (loot > 0 ? " and took " + Money.compact(loot) : "")
                + (shieldSeconds > 0 ? ". Their shield is up." : ".") + "</gray>";
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(p, () -> p.sendMessage(Msg.parse(line)));
        }
        Player o = Bukkit.getPlayer(owner);
        if (o != null) {
            Tasks.entity.run(o, () -> {
                o.playSound(o.getLocation(), Sound.ENTITY_WITHER_BREAK_BLOCK, 0.6f, 1.2f);
                o.showTitle(net.kyori.adventure.title.Title.title(Msg.parse("<red><bold>RAID!"),
                        Msg.parse("<white>" + Msg.escape(raider.getName()) + " <gray>broke your belt. Close your gate!")));
            });
        }
    }

    private void logTiers() {
        FactorySettings s = settings;
        var tiers = s.genTiers();
        var last = tiers.get(tiers.size() - 1);
        plugin.getLogger().info("Factory: " + tiers.size() + " gen tiers (" + tiers.get(0).name() + " " + Money.compact(tiers.get(0).valueCents())
                + " ... " + last.name() + " " + Money.compact(last.valueCents()) + ", last costs " + Money.compact(last.price()) + "), "
                + s.speedLevels().size() + " speed levels up to " + fmt(s.speed(s.speedLevels().size()) / s.startSpeed()) + "x ("
                + fmt(s.speed(s.speedLevels().size())) + " segments/s).");
    }

    GensCore plugin() {
        return plugin;
    }

    FactorySettings settings() {
        return settings;
    }

    @Nullable PlotData plotData(UUID owner) {
        return plots.data(owner);
    }

    long steal(UUID owner, Player raider, long ownerIncomePerMinute) {
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco == null || eco.service() == null || eco.service().wallet(owner) == null || eco.service().wallet(raider.getUniqueId()) == null) {
            return 0;
        }
        var cfg = plugin.getConfig();
        double pct = Math.max(0, Math.min(0.5, cfg.getDouble("factory.raid.steal-percent", 0.03)));
        double maxMinutes = Math.max(0, cfg.getDouble("factory.raid.steal-max-minutes", 10));
        long floor = Math.round(Math.max(0, cfg.getDouble("factory.raid.steal-min", 25)) * 100);
        long balance = eco.service().balance(owner);
        long cap = Math.max(floor, Math.round(ownerIncomePerMinute * maxMinutes));
        long amount = Math.min(Math.round(balance * pct), cap);
        amount = Math.round(amount * (1 + me.Evan52098.genscore.powers.PowersModule.perk(raider.getUniqueId(), "raid-loot")));
        amount = Math.min(amount, balance);
        if (amount <= 0 || !eco.service().withdraw(owner, amount, "raided by " + raider.getName())) {
            return 0;
        }
        eco.service().deposit(raider.getUniqueId(), amount, "raid loot");
        Player o = Bukkit.getPlayer(owner);
        long shown = amount;
        if (o != null) {
            Tasks.entity.run(o, () -> o.sendMessage(Msg.bad(Msg.white(Msg.escape(raider.getName())) + " stole "
                    + Msg.white(Money.compact(shown)) + " from you. Close your gate!")));
        }
        return amount;
    }

    /** Pays belt earnings into the owner's balance; one ledger entry per payment (roadmap rule 3). */
    void pay(UUID owner, long cents) {
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco != null && cents > 0) {
            eco.service().deposit(owner, cents, "belt sales");
        }
    }

    // ------------------------------------------------------------------ plot lifecycle

    @Override
    public void ready(FactoryView view) {
        Factory old = bySlot.remove(view.slot());
        if (old != null) {
            old.stop();
        }
        Factory f = new Factory(this, view);
        bySlot.put(view.slot(), f);
        byOwner.put(view.owner(), f);
        f.start();
        Player owner = Bukkit.getPlayer(view.owner());
        if (owner != null) {
            Tasks.entity.run(owner, () -> {
                var key = new org.bukkit.NamespacedKey(plugin, "first_gate");
                if (!owner.getPersistentDataContainer().has(key, org.bukkit.persistence.PersistentDataType.BYTE)) {
                    owner.getPersistentDataContainer().set(key, org.bukkit.persistence.PersistentDataType.BYTE, (byte) 1);
                    f.closeFor(120);
                }
            });
        }
    }

    @Override
    public void removing(FactoryView view) {
        Factory f = bySlot.remove(view.slot());
        if (f != null) {
            byOwner.remove(view.owner(), f);
            f.stop();
        }
    }

    private @Nullable Factory at(Location l) {
        Plot plot = plots.service().plotAt(l);
        return plot == null ? null : bySlot.get(plot.slot());
    }

    // ------------------------------------------------------------------ gate: keeping people out

    private boolean blockedFrom(Player p, Factory f) {
        return f.gateClosed() && !p.getUniqueId().equals(f.view.owner()) && !bypasses(p) && !trusted(f.view.owner(), p);
    }

    boolean trusted(@Nullable UUID owner, Player p) {
        return trust != null && trust.trusts(owner, p.getUniqueId());
    }

    boolean bypasses(Player p) {
        me.Evan52098.genscore.staffmode.StaffModeModule staffMode = plugin.modules().get(me.Evan52098.genscore.staffmode.StaffModeModule.class);
        return staffMode != null && staffMode.inStaffMode(p.getUniqueId());
    }

    public static void tutorialReleased(UUID owner) {
        FactoryModule m = powerHooks;
        Factory f = m == null ? null : m.byOwner.get(owner);
        if (f != null) {
            f.requestOpen();
        }
    }

    private boolean leavingDuringTutorial(Player p, Location from, Location to) {
        if (!me.Evan52098.genscore.tutorial.TutorialModule.holdsGate(p.getUniqueId()) || bypasses(p)) {
            return false;
        }
        Factory own = byOwner.get(p.getUniqueId());
        if (own == null || !from.getWorld().equals(own.view.world()) || own.view.inside(to.getBlockX(), to.getBlockZ())
                && to.getWorld().equals(own.view.world())) {
            return false;
        }
        return own.view.inside(from.getBlockX(), from.getBlockZ()) || own.isGateCell(from.getBlockX(), from.getBlockY(), from.getBlockZ());
    }

    private static final String TUTORIAL_HOLD = "Your gate stays shut until the tutorial's Gate step.";

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
            return;
        }
        if (leavingDuringTutorial(event.getPlayer(), from, to)) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Msg.bad(TUTORIAL_HOLD + " Keep following the Guide."));
            return;
        }
        Factory f = at(to);
        if (f == null || !blockedFrom(event.getPlayer(), f)) {
            return;
        }
        boolean intoDoor = f.isGateCell(to.getBlockX(), to.getBlockY(), to.getBlockZ())
                || f.isGateCell(to.getBlockX(), to.getBlockY() + 1, to.getBlockZ());
        boolean wasIn = f.view.inside(from.getBlockX(), from.getBlockZ())
                || f.isGateCell(from.getBlockX(), from.getBlockY(), from.getBlockZ());
        if ((intoDoor || f.view.inside(to.getBlockX(), to.getBlockZ())) && !wasIn) {
            // The closed door is a wall for everyone but the owner (the owner walks through it).
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Msg.bad("This gate is closed."));
        } else if (f.view.inside(to.getBlockX(), to.getBlockZ())) {
            // Already inside a closed fortress (got in some other way): out you go.
            event.getPlayer().teleportAsync(f.outsideGate(), PlayerTeleportEvent.TeleportCause.PLUGIN);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN) {
            return; // our own moves (plot spawn, pushing intruders out)
        }
        Location to = event.getTo();
        if (leavingDuringTutorial(event.getPlayer(), event.getFrom(), to)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Msg.bad("You can't leave your factory yet. " + TUTORIAL_HOLD
                    + " (" + Msg.white("/tutorial skip") + " to stop the tutorial)"));
            return;
        }
        Factory f = at(to);
        if (f != null && blockedFrom(event.getPlayer(), f) && f.view.inside(to.getBlockX(), to.getBlockZ())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Msg.bad("That factory's gate is closed."));
        }
    }

    // ------------------------------------------------------------------ clicks: belt, gate button, door

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block b = event.getClickedBlock();
        if (b == null) {
            return;
        }
        Factory f = at(b.getLocation());
        if (f == null) {
            return;
        }
        Player p = event.getPlayer();
        boolean owner = p.getUniqueId().equals(f.view.owner());
        if (f.isButton(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
            if (!owner) {
                p.sendMessage(Msg.bad("This is not your gate."));
                return;
            }
            closeGate(p, f);
        } else if (f.path.contains(b.getX(), b.getY(), b.getZ()) || f.isMachine(b.getX(), b.getY(), b.getZ())) {
            event.setCancelled(true);
            int index = f.segmentIndex(b.getX(), b.getY(), b.getZ());
            if (owner && index >= 0 && f.isBroken(index)) {
                repair(p, f, index);
                return;
            }
            openMenu(p, f);
        }
    }

    /** Right-click on the gate's click boxes: close (or close again); sneak + right-click: open. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onClickBox(PlayerInteractEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Factory f = gateOf(event.getRightClicked());
        if (f == null) {
            return;
        }
        event.setCancelled(true);
        gateClicked(event.getPlayer(), f, event.getPlayer().isSneaking(), event.getRightClicked().getLocation());
    }

    /** Left-click (hitting) the gate does the same as right-click, so it is easy to use either way. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onHitClickBox(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player p)) {
            return;
        }
        Factory f = gateOf(event.getEntity());
        if (f == null) {
            return;
        }
        event.setCancelled(true);
        gateClicked(p, f, p.isSneaking(), event.getEntity().getLocation());
    }

    private @Nullable Factory gateOf(Entity e) {
        if (!e.getScoreboardTags().contains(Factory.tag())) {
            return null;
        }
        Factory f = at(e.getLocation());
        return f != null && f.isClickBox(e) ? f : null;
    }

    private void gateClicked(Player p, Factory f, boolean sneaking, Location at) {
        if (!p.getUniqueId().equals(f.view.owner()) && trusted(f.view.owner(), p)) {
            p.sendActionBar(Msg.good("You're trusted here. Walk right through the gate."));
            return;
        }
        if (!p.getUniqueId().equals(f.view.owner()) && f.gateClosed()
                && lockpicks.isLockpick(p.getInventory().getItemInMainHand())) {
            lockpicks.start(p, f, at);
            return;
        }
        if (p.getUniqueId().equals(f.view.owner()) && f.gateClosed() && lockpicks.jam(p, f)) {
            return;
        }
        if (!p.getUniqueId().equals(f.view.owner())) {
            p.sendActionBar(Msg.bad(f.gateClosed() ? "This gate is closed." : "This is not your gate."));
            return;
        }
        if (f.tutorialHold()) {
            p.sendActionBar(Msg.bad(TUTORIAL_HOLD));
            return;
        }
        if (sneaking) {
            f.requestOpen();
            p.sendMessage(Msg.neutral("Opening your gate."));
            return;
        }
        closeGate(p, f);
    }

    private final Map<UUID, GateGuard> gateGuards = new ConcurrentHashMap<>();

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWalk(PlayerMoveEvent event) {
        GateGuard g = gateGuards.get(event.getPlayer().getUniqueId());
        if (g == null || g.walked >= GateGuard.MIN_WALK) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (from.getWorld() != to.getWorld()) {
            return;
        }
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        double step = Math.sqrt(dx * dx + dz * dz);
        if (step > 0.01 && step < 2.0) {
            g.walked += step;
        }
    }

    private static final class GateGuard {
        static final int EARLY_LIMIT = 8;
        static final long LOCK_MS = 60_000;
        static final double MIN_WALK = 5.0;
        static final int TIMED_CLOSES = 6;
        static final long TIMED_SPREAD_MS = 800;
        final long[] closes = new long[TIMED_CLOSES];
        int closeCount;
        volatile double walked = MIN_WALK;
        final long[] clicks = new long[12];
        int count;
        int early;
        long lockedUntil;
        long lastAction;
        String world = "";
        double x;
        double y;
        double z;
        float yaw;
        float pitch;

        boolean click(long now) {
            System.arraycopy(clicks, 1, clicks, 0, clicks.length - 1);
            clicks[clicks.length - 1] = now;
            count = Math.min(clicks.length, count + 1);
            if (count < 10 || now - clicks[clicks.length - 10] > 6000) {
                return false;
            }
            double sum = 0;
            double sq = 0;
            int n = 9;
            for (int i = clicks.length - 9; i < clicks.length; i++) {
                double d = clicks[i] - clicks[i - 1];
                sum += d;
                sq += d * d;
            }
            double mean = sum / n;
            double sd = Math.sqrt(Math.max(0, sq / n - mean * mean));
            boolean flagged = mean < 65 || (mean < 650 && sd < 8);
            if (flagged) {
                count = 0;
            }
            return flagged;
        }

        boolean idle(long now) {
            return lastAction != 0 && now - lastAction <= 600_000 && walked < MIN_WALK;
        }

        boolean timed(long now) {
            System.arraycopy(closes, 1, closes, 0, closes.length - 1);
            closes[closes.length - 1] = now;
            closeCount = Math.min(closes.length, closeCount + 1);
            if (closeCount < closes.length) {
                return false;
            }
            long min = Long.MAX_VALUE;
            long max = 0;
            for (int i = 1; i < closes.length; i++) {
                long d = closes[i] - closes[i - 1];
                if (d > 600_000) {
                    return false;
                }
                min = Math.min(min, d);
                max = Math.max(max, d);
            }
            if (min >= 5_000 && max - min <= TIMED_SPREAD_MS) {
                closeCount = 0;
                return true;
            }
            return false;
        }

        void remember(Location at, long now) {
            world = at.getWorld().getName();
            x = at.getX();
            y = at.getY();
            z = at.getZ();
            yaw = at.getYaw();
            pitch = at.getPitch();
            lastAction = now;
            early = 0;
            walked = 0;
        }
    }

    private void lockGate(Player p, GateGuard g, long now, String why) {
        g.lockedUntil = now + GateGuard.LOCK_MS;
        p.sendMessage(Msg.bad("Auto-clicking detected (" + why + "). You can't close your gate for "
                + (GateGuard.LOCK_MS / 1000) + "s."));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_LAND, 0.5f, 0.8f);
        net.kyori.adventure.text.Component alert = Msg.parse("<red><bold>GATE</bold></red> <white>" + Msg.escape(p.getName())
                + "</white> <gray>may be auto-clicking their gate:</gray> <yellow>" + Msg.escape(why) + "</yellow>");
        plugin.getLogger().info("[gate] " + p.getName() + " may be auto-clicking: " + why);
        for (Player staff : org.bukkit.Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("genscore.antihack.alert")) {
                Tasks.onEntity(staff, () -> staff.sendMessage(alert));
            }
        }
    }

    private void closeGate(Player p, Factory f) {
        if (!f.hasGate()) {
            p.sendMessage(Msg.bad("This factory has no gate."));
            return;
        }
        boolean wasClosed = f.gateClosed();
        long now = System.currentTimeMillis();
        GateGuard g = gateGuards.computeIfAbsent(p.getUniqueId(), k -> new GateGuard());
        if (g.click(now)) {
            lockGate(p, g, now, "clicking the gate with a perfectly even rhythm");
        }
        if (now < g.lockedUntil) {
            p.sendActionBar(Msg.parse("<red>Gate locked: auto-clicking detected.</red> <gray>Try again in</gray> <white>"
                    + ((g.lockedUntil - now + 999) / 1000) + "s</white>"));
            return;
        }
        if (wasClosed && !f.canReup() && ++g.early >= GateGuard.EARLY_LIMIT) {
            g.early = 0;
            lockGate(p, g, now, "spamming the gate before the re-up window");
            return;
        }
        if (wasClosed && !f.canReup()) {
            long wait = f.gateSecondsLeft() - Factory.REUP_WINDOW;
            p.sendActionBar(Msg.parse("<red>You can re-up your gate in the last " + Factory.REUP_WINDOW + "s</red> <gray>(" + wait + "s)</gray>"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        long cool = f.closeCooldownLeft();
        if (!wasClosed && cool > 0) {
            p.sendActionBar(Msg.parse("<red>Your gate just opened.</red> <gray>You can close it in</gray> <white>" + cool + "s</white>"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        Location at = p.getLocation();
        if (g.idle(now)) {
            p.sendActionBar(Msg.parse("<yellow>AFK check:</yellow> <gray>walk around (at least " + (int) GateGuard.MIN_WALK
                    + " blocks), then close your gate again.</gray> <white>" + String.format(java.util.Locale.ROOT, "%.1f", g.walked)
                    + "/" + (int) GateGuard.MIN_WALK + "</white>"));
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.6f, 0.8f);
            return;
        }
        if (g.timed(now)) {
            lockGate(p, g, now, "closing the gate on an exact timer");
            return;
        }
        g.remember(at, now);
        f.requestClose();
        me.Evan52098.genscore.tutorial.TutorialModule.done(p, "gate");
        if (wasClosed) {
            p.sendMessage(Msg.good("Gate timer reset to " + Msg.white(settings.gateCloseSeconds() + "s") + "."));
        }
        p.playSound(p.getLocation(), Sound.BLOCK_STONE_BUTTON_CLICK_ON, 1f, 1f);
    }

    // ------------------------------------------------------------------ commands

    @Command("belt")
    @CommandPermission("genscore.belt")
    @Description("Open your belt menu")
    public void belt(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Factory f = byOwner.get(p.getUniqueId());
        if (f == null) {
            p.sendMessage(Msg.bad("Your factory is not ready yet. Try " + Msg.white("/plot") + "."));
            return;
        }
        openMenu(p, f);
    }

    @Command("gate")
    @CommandPermission("genscore.belt")
    @Description("Close your factory gate (again) for 45 seconds, or /gate open")
    public void gate(BukkitCommandActor actor, @Optional @revxrsal.commands.annotation.Named("action") @Nullable String action) {
        Player p = actor.requirePlayer();
        Factory f = byOwner.get(p.getUniqueId());
        if (f == null) {
            p.sendMessage(Msg.bad("Your factory is not ready yet."));
            return;
        }
        if (f.tutorialHold()) {
            p.sendMessage(Msg.bad(TUTORIAL_HOLD));
            return;
        }
        if ("open".equalsIgnoreCase(action)) {
            f.requestOpen();
            p.sendMessage(Msg.neutral("Opening your gate."));
            return;
        }
        if (!p.hasPermission("genscore.gate.remote") && !nearOwnGate(p, f)) {
            p.sendMessage(Msg.bad("Walk to your gate to close it. Ranks with remote gates can close it from anywhere (" + Msg.white("/store") + ")."));
            return;
        }
        closeGate(p, f);
    }

    private boolean nearOwnGate(Player p, Factory f) {
        var l = p.getLocation();
        if (!l.getWorld().equals(f.view.world())) {
            return false;
        }
        if (f.view.contains(l.getBlockX(), l.getBlockZ())) {
            return true;
        }
        return l.distanceSquared(f.outsideGate()) <= 8 * 8;
    }

    // ------------------------------------------------------------------ raids

    /** Left-click a belt segment with the Raid Pick in someone else's factory: a raid hit. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onRaidHit(PlayerInteractEvent event) {
        if (event.getAction() != Action.LEFT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block b = event.getClickedBlock();
        Factory f = b == null ? null : at(b.getLocation());
        if (f == null) {
            return;
        }
        int index = f.segmentIndex(b.getX(), b.getY(), b.getZ());
        if (index < 0) {
            return;
        }
        Player p = event.getPlayer();
        event.setCancelled(true);
        if (p.getUniqueId().equals(f.view.owner())) {
            return;
        }
        if (trusted(f.view.owner(), p)) {
            p.sendActionBar(Msg.bad("This factory trusts you, so you can't raid it."));
            return;
        }
        if (!menus.isRaidPick(p.getInventory().getItemInMainHand())) {
            p.sendActionBar(Msg.bad("You need a Raid Pick to break belts (" + Msg.white("/gear") + ")."));
            return;
        }
        if (sameNetwork(p, Bukkit.getPlayer(f.view.owner())) && !p.hasPermission(BYPASS)) {
            p.sendActionBar(Msg.bad("You can't raid a factory owned by someone on your connection."));
            return;
        }
        long now = System.currentTimeMillis();
        Long last = lastSwing.get(p.getUniqueId());
        if (last != null && now - last < progress.hitCooldownMs()) {
            return; // rate limit (anti-cheat): one hit per cooldown
        }
        lastSwing.put(p.getUniqueId(), now);
        var g = gensPlayer(p.getUniqueId());
        int damage = progress.hitDamage(g == null ? 0 : g.gearLevel())
                + (int) Math.round(me.Evan52098.genscore.powers.PowersModule.perk(p.getUniqueId(), "raid-damage"));
        // Belt state belongs to the factory's region thread; the raider stands in the same region.
        Tasks.region.run(f.view.center(), () -> {
            Factory.HitResult r = f.hit(p, index, damage);
            Tasks.entity.run(p, () -> {
                switch (r.hit()) {
                    case DAMAGED -> p.sendActionBar(Msg.parse("<red>Belt " + "█".repeat(Math.max(0, r.healthLeft()))
                            + "<dark_gray>" + "█".repeat(Math.max(0, r.maxHealth() - r.healthLeft())) + "</dark_gray> <gray>"
                            + r.healthLeft() + "/" + r.maxHealth()));
                    case BROKE -> {
                        p.sendMessage(Msg.good("Belt broken! " + Msg.white("+" + Money.compact(r.loot())) + ", "
                                + Msg.white("+" + r.tokens() + " tokens") + (r.caches() > 0 ? ", " + Msg.white("+" + r.caches() + " cache") : "")
                                + (r.shieldSeconds() > 0 ? ". The factory's shield is up." : ".")));
                        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
                    }
                    case BROKEN_ALREADY -> p.sendActionBar(Msg.neutral("That segment is already broken."));
                    case IDLE_SEGMENT -> p.sendActionBar(Msg.neutral("That part of the belt is not running."));
                    case SHIELDED -> p.sendActionBar(Msg.bad("This factory is shielded for " + r.shieldSeconds() + "s."));
                    case GATE_CLOSED -> p.sendActionBar(Msg.bad("The gate is closed."));
                    case RAID_CAP -> p.sendActionBar(Msg.bad("You have broken all you can here for now."));
                }
            });
        });
    }

    static boolean sameNetwork(Player a, @Nullable Player b) {
        if (b == null) {
            return false;
        }
        var x = a.getAddress();
        var y = b.getAddress();
        if (x == null || y == null || x.getAddress() == null || y.getAddress() == null) {
            return false;
        }
        var ip = x.getAddress();
        return !ip.isLoopbackAddress() && !ip.isSiteLocalAddress() && !ip.isLinkLocalAddress() && ip.equals(y.getAddress());
    }

    private void repair(Player p, Factory f, int index) {
        long cost = Math.round(progress.repairCostMinutes() * rewards.incomePerMinute(p.getUniqueId()));
        Tasks.region.run(f.view.center(), () -> {
            long wait = f.repairWait(index);
            if (wait > 0) {
                Tasks.entity.run(p, () -> p.sendActionBar(Msg.bad("Under attack: you can repair in " + wait + "s.")));
                return;
            }
            if (!f.isBroken(index)) {
                return;
            }
            if (!withdraw(p, cost, "belt repair")) {
                return;
            }
            if (!f.repair(index)) {
                pay(p.getUniqueId(), cost); // could not repair after all: give the money back
                return;
            }
            Tasks.entity.run(p, () -> p.sendMessage(Msg.good("Belt repaired for " + Msg.white(Money.compact(cost)) + ".")));
        });
    }

    @Command({"tokens", "tokenshop"})
    @CommandPermission("genscore.belt")
    @Description("Your tokens and the token shop")
    public void tokens(BukkitCommandActor actor) {
        menus.tokens(actor.requirePlayer());
    }

    static final double REBIRTH_BONUS = 0.5;

    static int rebirthTier(int rebirths) {
        return 60 + 10 * rebirths;
    }

    @Command({"rebirth", "prestige"})
    @CommandPermission("genscore.belt")
    @Description("Reset your factory for a permanent income boost")
    public void rebirth(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> rebirthMenu(p));
    }

    void rebirthMenu(Player p) {
        PlotData data = plotData(p.getUniqueId());
        var g = gensPlayer(p.getUniqueId());
        if (data == null || g == null) {
            p.sendMessage(Msg.bad("Your factory is not ready yet."));
            return;
        }
        int r = g.rebirths();
        int need = rebirthTier(r);
        int tier = data.genLevel() + 1;
        boolean ready = tier >= need;
        Menu menu = new Menu(3, me.Evan52098.genscore.menu.MenuStyle.title("Rebirth"));
        menu.set(11, Lore.item(Material.NETHER_STAR, "&d&lRebirth " + (r + 1), Lore.builder(Lore.Accent.WHITE)
                .description("Start your factory again,", "stronger every time.").divider()
                .stat("Needs", "Gen tier " + need + (ready ? " &a✔" : " &c(you: " + tier + ")"))
                .stat("Reward", "&d+" + (int) (REBIRTH_BONUS * 100) + "% &7sale value forever")
                .stat("Bonus", "&e+25 tokens")
                .build()));
        menu.set(13, Lore.item(Material.BARRIER, "&c&lYou lose", Lore.builder(Lore.Accent.RED)
                .description("Gen tier, belt speed, belt length,", "machines and your balance.").build()));
        menu.set(15, Lore.item(Material.CHEST, "&a&lYou keep", Lore.builder(Lore.Accent.CYAN)
                .description("Tokens, powers, defences, caches,", "cosmetics, credits and your rebirths.").divider()
                .stat("Rebirths", String.valueOf(r))
                .stat("Current boost", "&d+" + (int) (REBIRTH_BONUS * 100 * r) + "%").build()));
        menu.set(22, Lore.item(ready ? Material.LIME_CONCRETE : Material.GRAY_CONCRETE, ready ? "&a&lREBIRTH NOW" : "&7&lNot yet",
                Lore.builder(Lore.Accent.WHITE).description(ready ? "Click to rebirth." : "Reach gen tier " + need + " first.").build()), click -> {
            if (ready) {
                doRebirth(p);
            }
        });
        me.Evan52098.genscore.menu.MenuStyle.frame(menu, 3);
        menu.open(p);
    }

    private final Map<UUID, Long> lastCombat = new ConcurrentHashMap<>();

    long lastCombatAt(UUID player) {
        return lastCombat.getOrDefault(player, 0L);
    }

    Lockpicks lockpicks() {
        return lockpicks;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLockpickClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (lockpicks != null && event.getPlayer() instanceof Player p) {
            lockpicks.closed(p, event.getInventory());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onLockpickQuit(org.bukkit.event.player.PlayerQuitEvent event) {
        if (lockpicks != null) {
            lockpicks.forget(event.getPlayer().getUniqueId());
        }
    }

    void markCombat(UUID player) {
        lastCombat.put(player, System.currentTimeMillis());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCombat(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        org.bukkit.entity.Entity damager = event.getDamager() instanceof org.bukkit.entity.Projectile proj
                && proj.getShooter() instanceof org.bukkit.entity.Entity shooter ? shooter : event.getDamager();
        if (event.getEntity() instanceof Player victim && damager instanceof Player attacker && !victim.equals(attacker)) {
            markCombat(victim.getUniqueId());
            markCombat(attacker.getUniqueId());
        }
    }

    public @org.jetbrains.annotations.Nullable String staffModeBlock(Player p) {
        long now = System.currentTimeMillis();
        UUID id = p.getUniqueId();
        if (now - lastCombat.getOrDefault(id, 0L) < 15_000) {
            return "You were in combat in the last 15 seconds.";
        }
        if (now - lastSwing.getOrDefault(id, 0L) < 30_000) {
            return "You broke a belt in the last 30 seconds.";
        }
        Factory inside = at(p.getLocation());
        if (inside != null && !inside.view.owner().equals(id) && inside.view.inside(p.getLocation().getBlockX(), p.getLocation().getBlockZ())) {
            return "You're inside someone else's factory.";
        }
        Factory own = byOwner.get(id);
        if (own != null && (own.brokenCount() > 0 || now - own.lastAttackedAt() < 60_000)) {
            return "Your factory is under attack or has broken belts. Repair it first.";
        }
        return null;
    }

    public boolean raidDamaged(UUID owner) {
        Factory f = byOwner.get(owner);
        return f != null && f.brokenCount() > 0;
    }

    private void doRebirth(Player p) {
        PlotData data = plotData(p.getUniqueId());
        var g = gensPlayer(p.getUniqueId());
        if (data == null || g == null || data.genLevel() + 1 < rebirthTier(g.rebirths())) {
            return;
        }
        p.closeInventory();
        Factory belt = byOwner.get(p.getUniqueId());
        if (belt != null) {
            belt.wipeBelt();
        }
        int r = g.rebirths() + 1;
        g.setRebirths(r);
        me.Evan52098.genscore.hub.HubModule.progress(p.getUniqueId(), "REBIRTH", 1);
        g.addTokens(25);
        data.setGenLevel(0);
        data.setSpeedLevel(0);
        data.setMachines(0);
        data.setBeltLength(plots.defaultBeltLength());
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco != null && eco.service() != null) {
            eco.service().set(p.getUniqueId(), 0, "rebirth " + r);
        }
        saveSoon(p.getUniqueId());
        plugin.playerData().saveSoon(p.getUniqueId());
        celebrate(p, "<light_purple><bold>REBIRTH " + r, "<white>+" + (int) (REBIRTH_BONUS * 100 * r) + "% sale value forever", true);
        net.kyori.adventure.text.Component line = Msg.parse(Msg.headerText("Rebirth") + "<white>" + Msg.escape(p.getName())
                + "</white> <gray>reached rebirth</gray> <light_purple>" + r + "</light_purple><gray>!</gray>");
        for (Player o : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(o, () -> o.sendMessage(line));
        }
        if (plots.service() != null) {
            plots.service().rebuildFor(p.getUniqueId());
        }
    }

    @Command({"machines", "upgraders"})
    @CommandPermission("genscore.belt")
    @Description("Buy machines that boost your belt's value")
    public void machines(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> machinesMenu(p));
    }

    void machinesMenu(Player p) {
        me.Evan52098.genscore.tutorial.TutorialModule.done(p, "machines");
        PlotData data = plotData(p.getUniqueId());
        Factory f = byOwner.get(p.getUniqueId());
        if (data == null || f == null) {
            p.sendMessage(Msg.bad("Your factory is not ready yet."));
            return;
        }
        int owned = data.machines();
        int length = f.activeLength();
        Menu menu = new Menu(4, me.Evan52098.genscore.menu.MenuStyle.title("Machines"));
        int[] slots = {11, 12, 13, 14, 15};
        for (int i = 0; i < Machines.ALL.size(); i++) {
            Machines.Machine m = Machines.ALL.get(i);
            boolean have = i < owned;
            boolean next = i == owned;
            boolean reaches = m.segment() < length;
            Lore lore = Lore.builder(have ? Lore.Accent.CYAN : Lore.Accent.WHITE).subcategory("Belt machine")
                    .description(m.description()).divider()
                    .stat("Value", "x" + m.multiplier())
                    .stat("Sits at", "segment " + (m.segment() + 1));
            if (have) {
                lore.stat("Status", reaches ? "&aRunning" : "&cBelt too short (" + length + "/" + (m.segment() + 1) + ")");
            } else if (next) {
                lore.stat("Price", "&a" + Money.compact(m.priceCents())).clickPrompt("Click to build");
            } else {
                lore.stat("Status", "&7Build the one before first");
            }
            org.bukkit.inventory.ItemStack icon = Lore.item(have || next ? m.icon() : Material.GRAY_DYE, (have ? "&a" : next ? "&e" : "&7") + "&l" + m.name(), lore.build());
            int index = i;
            menu.set(slots[i], me.Evan52098.genscore.menu.MenuStyle.clean(icon, have && reaches), click -> {
                if (index == data.machines()) {
                    buyMachine(p, data, index);
                }
            });
        }
        double total = Machines.multiplier(owned, length);
        menu.set(4, Lore.item(Material.HOPPER, "&6&lYour line", Lore.builder(Lore.Accent.WHITE)
                .description("Every item that reaches the end", "of your belt passes your machines.").divider()
                .stat("Machines", owned + "/" + Machines.ALL.size())
                .stat("Total boost", "x" + String.format(java.util.Locale.ROOT, "%.2f", total)).build()));
        menu.set(27, me.Evan52098.genscore.menu.MenuStyle.back("your belt"), click -> {
            Menu.clearReturn(p);
            openMenu(p, f);
        });
        menu.set(me.Evan52098.genscore.menu.MenuStyle.shelf(4, 4), me.Evan52098.genscore.menu.MenuStyle.close(), click -> p.closeInventory());
        me.Evan52098.genscore.menu.MenuStyle.frame(menu, 4);
        menu.open(p);
    }

    @Nullable Factory factoryOf(UUID owner) {
        return byOwner.get(owner);
    }

    int defaultBeltLength() {
        return plots.defaultBeltLength();
    }

    boolean withdrawQuiet(Player p, long price, String reason) {
        return withdraw(p, price, reason, false);
    }

    void propsAfterGen(Player p, int from, int to) {
        if (to <= from) {
            return;
        }
        var stages = me.Evan52098.genscore.plots.PlotProps.unlockedBetween(from, to);
        if (!stages.isEmpty() && plots.service() != null) {
            plots.service().propsUnlocked(p.getUniqueId(), from, to);
            for (var stage : stages) {
                p.sendMessage(Msg.parse(Msg.headerText("Factory Upgrade") + "<gray>Your base unlocked</gray> <white>"
                        + Msg.escape(stage.name()) + "</white><gray>.</gray>"));
            }
        }
    }

    @Command({"managers", "manager"})
    @CommandPermission("genscore.belt")
    @Description("Hire a manager that buys upgrades for you")
    public void managersCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> managers.open(p));
    }

    private void buyMachine(Player p, PlotData data, int index) {
        Machines.Machine m = Machines.ALL.get(index);
        if (data.machines() != index || !withdraw(p, m.priceCents(), "machine " + m.name())) {
            return;
        }
        data.setMachines(index + 1);
        saveSoon(p.getUniqueId());
        celebrate(p, m.color() + "<bold>" + m.name().toUpperCase(java.util.Locale.ROOT) + " BUILT", "<white>x" + m.multiplier() + " item value", true);
        p.sendMessage(Msg.good("Built the " + Msg.white(m.name()) + ": every item that passes it is worth " + Msg.white("x" + m.multiplier()) + "."));
        me.Evan52098.genscore.hub.HubModule.progress(p.getUniqueId(), "UPGRADE", 1);
        machinesMenu(p);
    }

    @Command("caches")
    @CommandPermission("genscore.belt")
    @Description("Break your caches")
    public void caches(BukkitCommandActor actor) {
        menus.caches(actor.requirePlayer());
    }

    @Command({"defence", "defences", "defense"})
    @CommandPermission("genscore.belt")
    @Description("Upgrade your factory's defences")
    public void defence(BukkitCommandActor actor) {
        menus.defence(actor.requirePlayer());
    }

    @Command("gear")
    @CommandPermission("genscore.belt")
    @Description("Raid gear and the Raid Pick")
    public void gear(BukkitCommandActor actor) {
        menus.gear(actor.requirePlayer());
    }

    @Command("gensadmin tokens")
    @CommandPermission("genscore.admin.plots")
    @Description("Give tokens or a cache to an online player (tests)")
    public void adminTokens(BukkitCommandActor actor, @revxrsal.commands.annotation.Named("player") String player, @revxrsal.commands.annotation.Named("what") String what, @revxrsal.commands.annotation.Named("amount") int amount) {
        Player t = Bukkit.getPlayerExact(player);
        var g = t == null ? null : gensPlayer(t.getUniqueId());
        if (g == null) {
            actor.sender().sendMessage(Msg.bad("That player is not online."));
            return;
        }
        int rarity = java.util.Arrays.asList(me.Evan52098.genscore.plots.GensPlayer.RARITIES).indexOf(what.toLowerCase(java.util.Locale.ROOT));
        if (rarity >= 0) {
            g.addCache(rarity, amount);
        } else {
            g.addTokens(amount);
        }
        saveSoon(t.getUniqueId());
        actor.sender().sendMessage(Msg.good("Gave " + amount + " " + (rarity >= 0 ? what + " cache(s)" : "tokens") + " to " + t.getName() + "."));
    }

    @Command("gensadmin tokensall")
    @CommandPermission("genscore.admin.plots")
    @Description("Give tokens or a cache to everyone online")
    public void adminTokensAll(BukkitCommandActor actor, @revxrsal.commands.annotation.Named("what") String what, @revxrsal.commands.annotation.Named("amount") int amount) {
        if (amount < 1 || amount > 10_000) {
            actor.sender().sendMessage(Msg.bad("Amount must be 1 to 10,000."));
            return;
        }
        int rarity = java.util.Arrays.asList(me.Evan52098.genscore.plots.GensPlayer.RARITIES).indexOf(what.toLowerCase(java.util.Locale.ROOT));
        if (rarity < 0 && !what.equalsIgnoreCase("tokens")) {
            actor.sender().sendMessage(Msg.bad("Give tokens, or a common, rare, epic or legendary cache."));
            return;
        }
        String label = rarity >= 0 ? amount + "x " + what.toLowerCase(java.util.Locale.ROOT) + " cache" : amount + " tokens";
        int count = 0;
        for (Player t : List.copyOf(Bukkit.getOnlinePlayers())) {
            var g = gensPlayer(t.getUniqueId());
            if (g == null) {
                continue;
            }
            if (rarity >= 0) {
                g.addCache(rarity, amount);
            } else {
                g.addTokens(amount);
            }
            saveSoon(t.getUniqueId());
            count++;
            Tasks.onEntity(t, () -> t.sendMessage(Msg.parse("<gold><bold>TOKENS <dark_gray>» <gray>Everyone online got <white>" + label + "<gray>!")));
        }
        plugin.getLogger().info("[tokens] " + actor.sender().getName() + " gave " + label + " to " + count + " online players");
        actor.sender().sendMessage(Msg.good("Gave " + label + " to " + count + " players."));
    }

    // ------------------------------------------------------------------ belt menu

    private void openMenu(Player p, Factory f) {
        openMenu(p, f, 0);
    }

    private void openMenu(Player p, Factory f, int tab) {
        boolean owner = p.getUniqueId().equals(f.view.owner());
        PlotData data = plotData(f.view.owner());
        if (data == null) {
            p.sendMessage(Msg.bad("That factory's owner is not online."));
            return;
        }
        FactorySettings s = settings;
        int length = f.activeLength();
        int level = data.speedLevel();
        int tier = data.genLevel();
        FactorySettings.GenTier gen = s.tier(tier);
        org.bukkit.inventory.ItemStack overview = Lore.item(Material.HOPPER, "&6&lBelt", Lore.builder(Lore.Accent.WHITE)
                .stat("Length", length + " / " + f.path.size() + " segments")
                .stat("Speed", mult(s, level) + " (" + fmt(s.speed(level)) + " segments/s)")
                .stat("Gen", "Tier " + (tier + 1) + ": " + gen.mix())
                .stat("Machines", data.machines() + "/" + Machines.ALL.size()
                        + " (x" + String.format(java.util.Locale.ROOT, "%.2f", Machines.multiplier(data.machines(), length)) + ")")
                .stat("Average item", Money.compact(s.itemValue(length, tier)))
                .stat("Income", Money.compact(f.incomePerMinute) + " / min")
                .stat("Items on belt", String.valueOf(f.itemsOnBelt))
                .divider()
                .description("Items ride the belt to the sell point.", "The longer the belt, the more each is worth.")
                .build());
        if (!owner) {
            String ownerName = Bukkit.getOfflinePlayer(f.view.owner()).getName();
            Menu menu = new Menu(3, me.Evan52098.genscore.menu.MenuStyle.title(ownerName + "'s belt"));
            menu.set(13, overview, null);
            menu.open(p);
            return;
        }
        openTab(p, f, data, overview, tab);
    }

    private static final String[] TAB_NAMES = {"UPGRADES", "DEFENCE & RAIDS", "REWARDS"};
    private static final String[] TAB_TITLES = {"Upgrades", "Defence", "Rewards"};
    private static final String[] TAB_COLORS = {"&6", "&c", "&e"};
    private static final Material[] TAB_ICONS = {Material.ANVIL, Material.IRON_SWORD, Material.CHEST};
    private static final Material[] TAB_PANES = {Material.ORANGE_STAINED_GLASS_PANE, Material.RED_STAINED_GLASS_PANE,
            Material.YELLOW_STAINED_GLASS_PANE};
    private static final String[] TAB_LINES = {"Make every item worth more.", "Keep raiders out, go raid others.",
            "Spend tokens, open caches, rebirth."};

    private void openTab(Player p, Factory f, PlotData data, org.bukkit.inventory.ItemStack overview, int tab) {
        FactorySettings s = settings;
        int length = f.activeLength();
        int level = data.speedLevel();
        int tier = data.genLevel();
        FactorySettings.GenTier gen = s.tier(tier);
        String c = TAB_COLORS[tab] + "&l";
        Menu menu = new Menu(4, me.Evan52098.genscore.menu.MenuStyle.title("Your belt", TAB_TITLES[tab])).plain();
        for (int slot = 0; slot < 9; slot++) {
            boolean corner = slot == 0 || slot == 8;
            Material edge = corner ? me.Evan52098.genscore.menu.MenuStyle.ACCENT : me.Evan52098.genscore.menu.MenuStyle.FRAME;
            menu.set(slot, me.Evan52098.genscore.menu.MenuStyle.pane(edge));
            menu.set(9 + slot, me.Evan52098.genscore.menu.MenuStyle.pane(TAB_PANES[tab]));
            menu.set(27 + slot, me.Evan52098.genscore.menu.MenuStyle.pane(edge));
        }
        for (int t = 0; t < 3; t++) {
            int to = t;
            boolean on = t == tab;
            Lore lore = Lore.builder(Lore.Accent.WHITE).description(TAB_LINES[t]);
            if (on) {
                lore.divider().description("&aShowing now.");
            } else {
                lore.clickPrompt("open " + TAB_TITLES[t].toLowerCase(java.util.Locale.ROOT));
            }
            menu.set(2 + t * 2, me.Evan52098.genscore.menu.MenuStyle.clean(
                    Lore.item(TAB_ICONS[t], TAB_COLORS[t] + "&l" + TAB_NAMES[t], lore.build()), on), e -> {
                if (!on) {
                    p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
                    openTab(p, f, data, overview, to);
                }
            });
        }
        var g = gensPlayer(p.getUniqueId());
        if (tab == 0) {
            int owned = data.machines();
            long speedPrice = s.nextSpeedPrice(level);
            long tierPrice = s.nextTierPrice(tier);
            int done = Math.max(0, (data.beltLength() - plots.defaultBeltLength()) / Math.max(1, s.lengthStep()));
            boolean maxed = data.beltLength() >= f.path.size();
            Lore machines = Lore.builder(Lore.Accent.WHITE)
                    .stat("Built", owned + " / " + Machines.ALL.size())
                    .stat("Total boost", "x" + String.format(java.util.Locale.ROOT, "%.2f", Machines.multiplier(owned, length)));
            if (owned < Machines.ALL.size()) {
                Machines.Machine next = Machines.ALL.get(owned);
                machines.stat("Next", next.name() + " (x" + next.multiplier() + ")").stat("Price", Money.compact(next.priceCents()));
            } else {
                machines.description("Every machine is built.");
            }
            menu.set(20, Lore.item(Material.SUGAR, c + "Speed", (speedPrice < 0
                    ? Lore.builder(Lore.Accent.WHITE).stat("Speed", mult(s, level)).description("Top speed.")
                    : Lore.builder(Lore.Accent.WHITE).stat("Level", level + " / " + s.speedLevels().size())
                    .stat("Now", mult(s, level)).stat("Next", mult(s, level + 1)).stat("Price", me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "speed") ? "FREE (tutorial)" : Money.compact(speedPrice))
                    .description("Shift-click: buy as many as you can afford.").clickPrompt("buy"))
                    .build()), e -> buySpeed(p, f, e.isShiftClick()));
            menu.set(21, Lore.item(tierPrice < 0 ? gen.item() : s.tier(tier + 1).item(), c + "Gen", (tierPrice < 0
                    ? Lore.builder(Lore.Accent.WHITE).stat("Gen", "Tier " + (tier + 1) + ": " + gen.mix()).description("Top tier.")
                    : Lore.builder(Lore.Accent.WHITE).stat("Now", "Tier " + (tier + 1) + ": " + gen.mix())
                    .stat("Next", "Tier " + (tier + 2) + ": " + s.tier(tier + 1).mix())
                    .stat("Average item then", Money.compact(s.itemValue(length, tier + 1)))
                    .stat("Price", me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "gen") ? "FREE (tutorial)" : Money.compact(tierPrice))
                    .description("Shift-click: buy as many as you can afford.").clickPrompt("buy"))
                    .build()), e -> buyTier(p, f, e.isShiftClick()));
            menu.set(22, Lore.item(Material.IRON_CHAIN, c + "Length", (maxed
                    ? Lore.builder(Lore.Accent.WHITE).stat("Length", length + " segments").description("The belt fills the factory.")
                    : Lore.builder(Lore.Accent.WHITE).stat("Now", length + " segments")
                    .stat("Next", Math.min(f.path.size(), data.beltLength() + s.lengthStep()) + " segments")
                    .stat("Item value then", Money.compact(s.itemValue(Math.min(f.path.size(), data.beltLength() + s.lengthStep()), tier)))
                    .stat("Price", me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "length") ? "FREE (tutorial)" : Money.compact(s.lengthPrice(done)))
                    .description("Shift-click: buy as many as you can afford.").clickPrompt("buy"))
                    .build()), e -> buyLength(p, f, e.isShiftClick()));
            menu.set(23, Lore.item(Material.BLAST_FURNACE, c + "Machines", machines.clickPrompt("see your machines").build()),
                    e -> sub(p, 0, this::machinesMenu));
            menu.set(24, Lore.item(Material.GOLDEN_HELMET, c + "Managers", Lore.builder(Lore.Accent.WHITE)
                    .description("Hire a manager to buy", "upgrades for you.").clickPrompt("open managers").build()),
                    e -> sub(p, 0, managers::open));
        } else if (tab == 1) {
            boolean shut = f.gateClosed();
            menu.set(21, Lore.item(shut ? Material.IRON_DOOR : Material.OAK_DOOR, c + "Gate", Lore.builder(Lore.Accent.WHITE)
                    .stat("Status", shut ? "&aClosed (" + f.gateSecondsLeft() + "s)" : "&cOpen")
                    .description("Closed: nobody else gets in.", "It opens again after " + s.gateCloseSeconds() + "s.")
                    .clickPrompt(shut ? "close it again (reset the timer)" : "close it").build()), e -> {
                closeGate(p, f);
                p.closeInventory();
            });
            menu.set(22, Lore.item(Material.SHIELD, c + "Defences", Lore.builder(Lore.Accent.WHITE)
                    .stat("Gate time", gateSeconds(p.getUniqueId()) + "s").stat("Shield", shieldText(p.getUniqueId()))
                    .clickPrompt("gate time, armour, turrets, traps").build()), e -> sub(p, 1, menus::defence));
            menu.set(23, Lore.item(Material.IRON_PICKAXE, c + "Raid Gear", Lore.builder(Lore.Accent.WHITE)
                    .stat("Gear level", String.valueOf(g == null ? 0 : g.gearLevel()))
                    .clickPrompt("the Raid Pick and gear upgrades").build()), e -> sub(p, 1, menus::gear));
        } else {
            int cacheCount = 0;
            for (int r = 0; g != null && r < 4; r++) {
                cacheCount += g.caches(r);
            }
            int rebirths = g == null ? 0 : g.rebirths();
            int need = rebirthTier(rebirths);
            menu.set(21, Lore.item(Material.SUNFLOWER, c + "Tokens", Lore.builder(Lore.Accent.WHITE)
                    .stat("Tokens", String.valueOf(g == null ? 0 : g.tokens()))
                    .clickPrompt("open the token shop").build()), e -> sub(p, 2, menus::tokens));
            menu.set(22, Lore.item(Material.ENDER_CHEST, c + "Caches", Lore.builder(Lore.Accent.WHITE)
                    .stat("Unopened", String.valueOf(cacheCount))
                    .clickPrompt("break your caches").build()), e -> sub(p, 2, menus::caches));
            menu.set(23, Lore.item(Material.NETHER_STAR, c + "Rebirth", Lore.builder(Lore.Accent.WHITE)
                    .stat("Rebirths", String.valueOf(rebirths))
                    .stat("Next needs", "Gen tier " + need + (tier + 1 >= need ? " &a✔" : " &7(you: " + (tier + 1) + ")"))
                    .clickPrompt("see rebirth").build()), e -> sub(p, 2, this::rebirthMenu));
        }
        menu.set(31, overview, null);
        menu.set(35, me.Evan52098.genscore.menu.MenuStyle.close(), e -> p.closeInventory());
        menu.open(p);
    }

    private void sub(Player p, int tab, java.util.function.Consumer<Player> open) {
        Menu.setReturn(p, "your belt", x -> {
            Factory back = byOwner.get(x.getUniqueId());
            if (back != null) {
                openMenu(x, back, tab);
            }
        });
        open.accept(p);
    }

    private void buySpeed(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        boolean freeUsed = false;
        while (true) {
            int level = data.speedLevel();
            long price = settings.nextSpeedPrice(level);
            if (price < 0) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your belt is already at top speed."));
                }
                break;
            }
            boolean free = bought == 0 && me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "speed");
            if (!free && !withdraw(p, price, "belt speed level " + (level + 1), bought == 0)) {
                break;
            }
            data.setSpeedLevel(level + 1); // checklist 1: the money is taken first
            bought++;
            if (free) {
                me.Evan52098.genscore.tutorial.TutorialModule.usedFree(p, "speed");
                freeUsed = true;
                break;
            }
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        p.sendMessage(Msg.good("Belt speed upgraded" + (bought > 1 ? " " + bought + " times" : "") + " to "
                + Msg.white(mult(settings, data.speedLevel())) + "." + (freeUsed ? " Free for the tutorial!" : "")));
        celebrate(p, "<aqua><bold>SPEED UP", "<white>" + mult(settings, data.speedLevel()) + " belt speed", false);
        openMenu(p, f);
    }

    /** Title, sound and a burst of particles (a firework for the big ones) when something good happens. */
    void celebrate(Player p, String title, String subtitle, boolean big) {
        p.showTitle(net.kyori.adventure.title.Title.title(Msg.parse(title), Msg.parse(subtitle),
                net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(100), java.time.Duration.ofMillis(1400),
                        java.time.Duration.ofMillis(400))));
        p.playSound(p.getLocation(), big ? Sound.UI_TOAST_CHALLENGE_COMPLETE : Sound.ENTITY_PLAYER_LEVELUP, 1f, big ? 1f : 1.4f);
        p.getWorld().spawnParticle(org.bukkit.Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 25, 0.5, 0.7, 0.5, 0.1);
        if (big) {
            p.getWorld().spawn(p.getLocation().add(0, 1.5, 0), org.bukkit.entity.Firework.class, fw -> {
                var meta = fw.getFireworkMeta();
                meta.addEffect(org.bukkit.FireworkEffect.builder().with(org.bukkit.FireworkEffect.Type.BALL_LARGE)
                        .withColor(org.bukkit.Color.YELLOW, org.bukkit.Color.ORANGE).withFade(org.bukkit.Color.WHITE)
                        .flicker(true).trail(true).build());
                fw.setFireworkMeta(meta);
                fw.setPersistent(false);
                fw.addScoreboardTag("genscore_harmless");
                fw.detonate();
            });
        }
    }

    private void buyTier(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        boolean freeUsed = false;
        int startLevel = data.genLevel();
        while (true) {
            int tier = data.genLevel();
            long price = settings.nextTierPrice(tier);
            if (price < 0) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your gen is already the top tier."));
                }
                break;
            }
            boolean free = bought == 0 && me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "gen");
            if (!free && !withdraw(p, price, "gen tier " + (tier + 2), bought == 0)) {
                break;
            }
            data.setGenLevel(tier + 1); // checklist 1: the money is taken first
            bought++;
            if (free) {
                me.Evan52098.genscore.tutorial.TutorialModule.usedFree(p, "gen");
                freeUsed = true;
                break;
            }
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        int now = data.genLevel();
        var stages = me.Evan52098.genscore.plots.PlotProps.unlockedBetween(startLevel, now);
        if (!stages.isEmpty() && plots.service() != null) {
            plots.service().propsUnlocked(p.getUniqueId(), startLevel, now);
            for (var stage : stages) {
                p.sendMessage(Msg.parse(Msg.headerText("Factory Upgrade") + "<gray>Your base unlocked</gray> <white>"
                        + Msg.escape(stage.name()) + "</white><gray>.</gray>"));
            }
            p.playSound(p.getLocation(), org.bukkit.Sound.BLOCK_ANVIL_USE, 0.6f, 1.2f);
        }
        p.sendMessage(Msg.good("Gen upgraded" + (bought > 1 ? " " + bought + " tiers" : "") + " to tier " + (now + 1) + ": it now makes "
                + Msg.white(settings.tier(now).mix()) + "." + (freeUsed ? " Free for the tutorial!" : "")));
        celebrate(p, "<gold><bold>GEN TIER " + (now + 1), "<yellow>" + Msg.escape(settings.tier(now).mix()), bought > 1 || now % 10 == 0);
        openMenu(p, f);
    }

    private void buyLength(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        boolean freeUsed = false;
        while (true) {
            if (data.beltLength() >= f.path.size()) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your belt already fills the factory."));
                }
                break;
            }
            int done = Math.max(0, (data.beltLength() - plots.defaultBeltLength()) / Math.max(1, settings.lengthStep()));
            long price = settings.lengthPrice(done);
            boolean free = bought == 0 && me.Evan52098.genscore.tutorial.TutorialModule.freeUpgrade(p, "length");
            if (!free && !withdraw(p, price, "belt length upgrade " + (done + 1), bought == 0)) {
                break;
            }
            data.setBeltLength(Math.min(f.path.size(), data.beltLength() + settings.lengthStep()));
            bought++;
            if (free) {
                me.Evan52098.genscore.tutorial.TutorialModule.usedFree(p, "length");
                freeUsed = true;
                break;
            }
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        p.sendMessage(Msg.good("Belt extended" + (bought > 1 ? " " + bought + " times" : "") + " to "
                + Msg.white(data.beltLength() + " segments") + "." + (freeUsed ? " Free for the tutorial!" : "")));
        celebrate(p, "<green><bold>BELT EXTENDED", "<white>" + data.beltLength() + " segments", data.beltLength() >= f.path.size());
        openMenu(p, f);
    }

    /** Takes the money (atomic). {@code complain}: say so when they cannot afford it (not after a multi-buy's last). */
    boolean withdraw(Player p, long price, String reason) {
        return withdraw(p, price, reason, true);
    }

    private boolean withdraw(Player p, long price, String reason, boolean complain) {
        EconomyModule eco = plugin.modules().get(EconomyModule.class);
        if (eco == null) {
            p.sendMessage(Msg.bad("The economy is off."));
            return false;
        }
        if (!eco.service().withdraw(p.getUniqueId(), price, reason)) {
            if (complain) {
                p.sendMessage(Msg.bad("You need " + Msg.white(Money.compact(price)) + " for that."));
            }
            return false;
        }
        if (reason.startsWith("gen tier") || reason.startsWith("belt speed") || reason.startsWith("belt length")) {
            me.Evan52098.genscore.hub.HubModule.progress(p.getUniqueId(), "UPGRADE", 1);
        }
        return true;
    }

    /** The belt speed at a level as a multiple of the starting speed, e.g. "3.25x". */
    private static String mult(FactorySettings s, int level) {
        return fmt(s.speed(level) / s.startSpeed()) + "x";
    }

    private static String fmt(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.format(java.util.Locale.ROOT, "%.2f", d);
    }

    // ------------------------------------------------------------------ joining: resource pack, boss bar

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        FactorySettings.Pack pack = settings.pack();
        if (pack.enabled()) {
            try {
                ResourcePackInfo info = ResourcePackInfo.resourcePackInfo(
                        UUID.nameUUIDFromBytes(("genscore-pack-" + pack.sha1()).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        URI.create(pack.url()), pack.sha1().toLowerCase(java.util.Locale.ROOT));
                p.sendResourcePacks(ResourcePackRequest.resourcePackRequest().packs(info).required(pack.required())
                        .prompt(Msg.neutral(pack.prompt())).build());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("factory.resource-pack.url is not a valid URL: " + e.getMessage());
            }
        }
        Factory f = byOwner.get(p.getUniqueId());
        if (f != null) {
            f.ownerJoined();
        }
        Tasks.entity.later(p, () -> menus.restylePicks(p), 20L);
        RankPerks.refresh(p);
        managers.start(p);
        var pd = plugin.playerData().get(p.getUniqueId());
        PlotData data = plotData(p.getUniqueId());
        if (pd != null && pd.isFirstTime() && data != null && progress.newPlayerShieldMinutes() > 0) {
            data.setShieldUntil(System.currentTimeMillis() + progress.newPlayerShieldMinutes() * 60_000L);
            p.sendMessage(Msg.good("Your factory is shielded from raids for your first " + Msg.white(progress.newPlayerShieldMinutes() + " minutes") + "."));
        }
    }

    List<Factory> factories() {
        return List.copyOf(bySlot.values());
    }

    // ------------------------------------------------------------------ admin / tests

    @Command("gensadmin factory status")
    @CommandPermission("genscore.admin.plots")
    @Description("List running factories: belt, gate and income")
    public void status(BukkitCommandActor actor) {
        var sender = actor.sender();
        List<Factory> all = factories();
        sender.sendMessage(Msg.neutral(all.size() + " factories running."));
        for (Factory f : all) {
            long corners = f.path.segments().stream().filter(s -> s.kind() != BeltPath.Kind.STRAIGHT).count();
            String name = Bukkit.getOfflinePlayer(f.view.owner()).getName();
            sender.sendMessage(Msg.parse("<gray>slot <white>" + f.view.slot() + "</white> " + Msg.escape(String.valueOf(name))
                    + " (" + f.view.rotation().name().toLowerCase() + "): path <white>" + f.path.size() + "</white> (" + corners
                    + " corners), belt <white>" + f.activeLength() + "</white> at " + fmt(f.speed()) + "/s, items " + f.itemsOnBelt
                    + ", " + Money.compact(f.incomePerMinute) + "/min, gate " + (f.gateClosed() ? "<green>closed " + f.gateSecondsLeft() + "s</green>"
                    : "<red>open</red>") + " (" + f.view.marker("gate").size() + " cells, " + f.view.marker("gate_button").size()
                    + " button), owner " + (f.ownerOnline ? "online" : "offline")));
        }
    }

    @Command("gensadmin factory gate")
    @CommandPermission("genscore.admin.plots")
    @Description("Close or open a factory's gate (tests)")
    public void adminGate(BukkitCommandActor actor, @revxrsal.commands.annotation.Named("slot") int slot, @revxrsal.commands.annotation.Named("action") String action) {
        Factory f = bySlot.get(slot);
        if (f == null) {
            actor.sender().sendMessage(Msg.bad("No factory on slot " + slot + "."));
            return;
        }
        if (action.equalsIgnoreCase("open")) {
            f.requestOpen();
        } else {
            f.requestClose();
        }
        actor.sender().sendMessage(Msg.good("Gate on slot " + slot + ": " + (action.equalsIgnoreCase("open") ? "opening." : "closing.")));
    }

    @Command("gensadmin factory check")
    @CommandPermission("genscore.admin.plots")
    @Description("Count gate barriers, belt slabs and the gate button in the world for a factory (tests)")
    public void check(BukkitCommandActor actor, int slot) {
        var sender = actor.sender();
        Factory f = bySlot.get(slot);
        if (f == null) {
            sender.sendMessage(Msg.bad("No factory on slot " + slot + "."));
            return;
        }
        Tasks.region.run(f.view.center(), () -> {
            int barriers = 0;
            int air = 0;
            for (var v : f.view.marker("gate")) {
                Material m = f.view.world().getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()).getType();
                if (m == Material.BARRIER) {
                    barriers++;
                } else if (m.isAir()) {
                    air++;
                }
            }
            int slabs = 0;
            for (var seg : f.path.segments()) {
                var c = seg.cell();
                if (f.view.world().getBlockAt(c.getBlockX(), c.getBlockY(), c.getBlockZ()).getType() == Material.SMOOTH_STONE_SLAB) {
                    slabs++;
                }
            }
            int buttons = 0;
            for (var v : f.view.marker("gate_button")) {
                if (f.view.world().getBlockAt(v.getBlockX(), v.getBlockY(), v.getBlockZ()).getType() == Material.POLISHED_BLACKSTONE_BUTTON) {
                    buttons++;
                }
            }
            long displays = f.view.world().getNearbyEntities(f.view.center(), f.view.size(), 16, f.view.size()).stream()
                    .filter(e -> e.getScoreboardTags().contains(Factory.tag())).count();
            String line = "slot " + slot + ": gate barriers " + barriers + ", gate air " + air + ", belt slabs " + slabs + "/"
                    + f.path.size() + ", gate buttons " + buttons + ", factory display entities " + displays
                    + ", gate " + (f.gateClosed() ? "closed" : "open");
            Tasks.forSender(sender, () -> sender.sendMessage(Msg.neutral(Msg.escape(line))));
        });
    }
}
