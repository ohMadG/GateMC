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
    private final Map<Integer, Factory> bySlot = new ConcurrentHashMap<>();
    private final Map<UUID, Factory> byOwner = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "factory";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.plots = plugin.modules().get(PlotsModule.class);
        if (plots == null) {
            throw new IllegalStateException("The factory module needs the plots module.");
        }
        settings = FactorySettings.from(plugin.getConfig().getConfigurationSection("factory"));
        plugin.commands().register(this);
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
        logTiers();
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
        return f.gateClosed() && !p.getUniqueId().equals(f.view.owner()) && !p.hasPermission(BYPASS);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        Location from = event.getFrom();
        if (to.getBlockX() == from.getBlockX() && to.getBlockZ() == from.getBlockZ()) {
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
        gateClicked(event.getPlayer(), f, event.getPlayer().isSneaking());
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
        gateClicked(p, f, p.isSneaking());
    }

    private @Nullable Factory gateOf(Entity e) {
        if (!e.getScoreboardTags().contains(Factory.tag())) {
            return null;
        }
        Factory f = at(e.getLocation());
        return f != null && f.isClickBox(e) ? f : null;
    }

    private void gateClicked(Player p, Factory f, boolean sneaking) {
        if (!p.getUniqueId().equals(f.view.owner())) {
            p.sendActionBar(Msg.bad(f.gateClosed() ? "This gate is closed." : "This is not your gate."));
            return;
        }
        if (sneaking) {
            f.requestOpen();
            p.sendMessage(Msg.neutral("Opening your gate."));
            return;
        }
        closeGate(p, f);
    }

    private void closeGate(Player p, Factory f) {
        if (!f.hasGate()) {
            p.sendMessage(Msg.bad("This factory has no gate."));
            return;
        }
        boolean wasClosed = f.gateClosed();
        f.requestClose();
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
    public void gate(BukkitCommandActor actor, @Optional @Nullable String action) {
        Player p = actor.requirePlayer();
        Factory f = byOwner.get(p.getUniqueId());
        if (f == null) {
            p.sendMessage(Msg.bad("Your factory is not ready yet."));
            return;
        }
        if ("open".equalsIgnoreCase(action)) {
            f.requestOpen();
            p.sendMessage(Msg.neutral("Opening your gate."));
            return;
        }
        closeGate(p, f);
    }

    // ------------------------------------------------------------------ belt menu

    private void openMenu(Player p, Factory f) {
        boolean owner = p.getUniqueId().equals(f.view.owner());
        PlotData data = plotData(f.view.owner());
        if (data == null) {
            p.sendMessage(Msg.bad("That factory's owner is not online."));
            return;
        }
        FactorySettings s = settings;
        String ownerName = Bukkit.getOfflinePlayer(f.view.owner()).getName();
        Menu menu = new Menu(3, Msg.parse(owner ? "<dark_gray>Your belt" : "<dark_gray>" + Msg.escape(String.valueOf(ownerName)) + "'s belt"));
        int length = f.activeLength();
        int level = data.speedLevel();
        int tier = data.genLevel();
        FactorySettings.GenTier gen = s.tier(tier);
        menu.set(10, Lore.item(Material.HOPPER, "&6Belt", Lore.builder(Lore.Accent.WHITE)
                .stat("Length", length + " / " + f.path.size() + " segments")
                .stat("Speed", mult(s, level) + " (" + fmt(s.speed(level)) + " segments/s)")
                .stat("Gen", "Tier " + (tier + 1) + ": " + gen.mix())
                .stat("Average item", Money.compact(s.itemValue(length, tier)))
                .stat("Income", Money.compact(f.incomePerMinute) + " / min")
                .stat("Items on belt", String.valueOf(f.itemsOnBelt))
                .divider()
                .description("Items ride the belt to the sell point.", "The longer the belt, the more each is worth.")
                .build()), null);
        if (owner) {
            long speedPrice = s.nextSpeedPrice(level);
            menu.set(12, Lore.item(Material.SUGAR, "&bSpeed upgrade", (speedPrice < 0
                    ? Lore.builder(Lore.Accent.CYAN).stat("Speed", mult(s, level)).description("Top speed.")
                    : Lore.builder(Lore.Accent.CYAN).stat("Level", level + " / " + s.speedLevels().size())
                    .stat("Now", mult(s, level)).stat("Next", mult(s, level + 1)).stat("Price", Money.compact(speedPrice))
                    .clickPrompt("buy").description("Shift-click: buy as many as you can afford."))
                    .build()), e -> buySpeed(p, f, e.isShiftClick()));
            int done = Math.max(0, (data.beltLength() - plots.defaultBeltLength()) / Math.max(1, s.lengthStep()));
            boolean maxed = data.beltLength() >= f.path.size();
            menu.set(14, Lore.item(Material.IRON_CHAIN, "&aLength upgrade", (maxed
                    ? Lore.builder(Lore.Accent.GREEN).stat("Length", length + " segments").description("The belt fills the factory.")
                    : Lore.builder(Lore.Accent.GREEN).stat("Now", length + " segments")
                    .stat("Next", Math.min(f.path.size(), data.beltLength() + s.lengthStep()) + " segments")
                    .stat("Item value then", Money.compact(s.itemValue(Math.min(f.path.size(), data.beltLength() + s.lengthStep()), tier)))
                    .stat("Price", Money.compact(s.lengthPrice(done))).clickPrompt("buy")
                    .description("Shift-click: buy as many as you can afford."))
                    .build()), e -> buyLength(p, f, e.isShiftClick()));
            long tierPrice = s.nextTierPrice(tier);
            menu.set(13, Lore.item(tierPrice < 0 ? gen.item() : s.tier(tier + 1).item(), "&6Gen upgrade", (tierPrice < 0
                    ? Lore.builder(Lore.Accent.WHITE).stat("Gen", "Tier " + (tier + 1) + ": " + gen.mix()).description("Top tier.")
                    : Lore.builder(Lore.Accent.WHITE).stat("Now", "Tier " + (tier + 1) + ": " + gen.mix())
                    .stat("Next", "Tier " + (tier + 2) + ": " + s.tier(tier + 1).mix())
                    .stat("Average item then", Money.compact(s.itemValue(length, tier + 1)))
                    .stat("Price", Money.compact(tierPrice)).clickPrompt("buy")
                    .description("Shift-click: buy as many as you can afford.", "Tier " + (tier + 1) + " of " + s.genTiers().size() + "."))
                    .build()), e -> buyTier(p, f, e.isShiftClick()));
            boolean shut = f.gateClosed();
            menu.set(16, Lore.item(shut ? Material.IRON_DOOR : Material.OAK_DOOR, shut ? "&aGate closed" : "&cGate OPEN",
                    Lore.builder(shut ? Lore.Accent.GREEN : Lore.Accent.RED)
                            .stat("Closed for", shut ? f.gateSecondsLeft() + "s" : "-")
                            .description("Closed: nobody else gets in.", "It opens again after " + s.gateCloseSeconds() + "s.")
                            .clickPrompt(shut ? "close it again (reset the timer)" : "close it").build()), e -> {
                closeGate(p, f);
                p.closeInventory();
            });
        }
        menu.open(p);
    }

    private void buySpeed(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        while (true) {
            int level = data.speedLevel();
            long price = settings.nextSpeedPrice(level);
            if (price < 0) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your belt is already at top speed."));
                }
                break;
            }
            if (!withdraw(p, price, "belt speed level " + (level + 1), bought == 0)) {
                break;
            }
            data.setSpeedLevel(level + 1); // checklist 1: the money is taken first
            bought++;
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        p.sendMessage(Msg.good("Belt speed upgraded" + (bought > 1 ? " " + bought + " times" : "") + " to "
                + Msg.white(mult(settings, data.speedLevel())) + "."));
        p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1.4f);
        openMenu(p, f);
    }

    private void buyTier(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        while (true) {
            int tier = data.genLevel();
            long price = settings.nextTierPrice(tier);
            if (price < 0) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your gen is already the top tier."));
                }
                break;
            }
            if (!withdraw(p, price, "gen tier " + (tier + 2), bought == 0)) {
                break;
            }
            data.setGenLevel(tier + 1); // checklist 1: the money is taken first
            bought++;
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        int now = data.genLevel();
        p.sendMessage(Msg.good("Gen upgraded" + (bought > 1 ? " " + bought + " tiers" : "") + " to tier " + (now + 1) + ": it now makes "
                + Msg.white(settings.tier(now).mix()) + "."));
        p.playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 1f, 1.2f);
        openMenu(p, f);
    }

    private void buyLength(Player p, Factory f, boolean many) {
        PlotData data = plotData(p.getUniqueId());
        if (data == null) {
            return;
        }
        int bought = 0;
        while (true) {
            if (data.beltLength() >= f.path.size()) {
                if (bought == 0) {
                    p.sendMessage(Msg.neutral("Your belt already fills the factory."));
                }
                break;
            }
            int done = Math.max(0, (data.beltLength() - plots.defaultBeltLength()) / Math.max(1, settings.lengthStep()));
            long price = settings.lengthPrice(done);
            if (!withdraw(p, price, "belt length upgrade " + (done + 1), bought == 0)) {
                break;
            }
            data.setBeltLength(Math.min(f.path.size(), data.beltLength() + settings.lengthStep()));
            bought++;
            if (!many) {
                break;
            }
        }
        if (bought == 0) {
            return;
        }
        plugin.playerData().saveSoon(p.getUniqueId());
        p.sendMessage(Msg.good("Belt extended" + (bought > 1 ? " " + bought + " times" : "") + " to "
                + Msg.white(data.beltLength() + " segments") + "."));
        p.playSound(p.getLocation(), Sound.BLOCK_ANVIL_USE, 0.7f, 1.4f);
        openMenu(p, f);
    }

    /** Takes the money (atomic). {@code complain}: say so when they cannot afford it (not after a multi-buy's last). */
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
    public void adminGate(BukkitCommandActor actor, int slot, String action) {
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
