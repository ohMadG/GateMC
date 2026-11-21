package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Crate blocks placed by admins ({@code /crate set}) or listed under a crate's {@code locations}: left-click previews,
 * right-click opens one, sneak + right-click opens all. The blocks cannot be broken, burnt, blown up or pushed.
 * Holograms are non-persistent TextDisplays, spawned again whenever their chunk loads (the {@code leaderboards}
 * pattern). Folia: displays are touched only on the region thread that owns them.
 */
final class CrateBlocks implements Listener {

    record Pos(String world, int x, int y, int z) {
        static Pos of(Block b) {
            return new Pos(b.getWorld().getName(), b.getX(), b.getY(), b.getZ());
        }

        Location center(World w, double up) {
            return new Location(w, x + 0.5, y + up, z + 0.5);
        }
    }

    private static final class Spot {
        final String crate;
        final boolean fromConfig;
        volatile TextDisplay display;
        volatile org.bukkit.entity.ItemDisplay base;
        volatile org.bukkit.entity.ItemDisplay lid;
        final java.util.concurrent.atomic.AtomicBoolean opening = new java.util.concurrent.atomic.AtomicBoolean();

        Spot(String crate, boolean fromConfig) {
            this.crate = crate;
            this.fromConfig = fromConfig;
        }
    }

    private final CrateService service;
    private final CrateMenus menus;
    private final Logger logger;
    private final Map<Pos, Spot> spots = new ConcurrentHashMap<>();

    CrateBlocks(CrateService service, CrateMenus menus, Logger logger) {
        this.service = service;
        this.menus = menus;
        this.logger = logger;
    }

    void start(GensCore plugin) throws Exception {
        for (CrateStore.BlockRow row : service.store().loadBlocks()) {
            spots.put(new Pos(row.world(), row.x(), row.y(), row.z()), new Spot(row.crate(), false));
        }
        loadConfigLocations();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.global.later(this::refreshAll, 40);
    }

    void stop() {
        HandlerList.unregisterAll(this);
        removeAllDisplays();
    }

    /** After /crate reload: locations from crates.yml again, then every hologram redrawn. */
    void reload() {
        spots.entrySet().removeIf(e -> {
            if (e.getValue().fromConfig) {
                removeDisplay(e.getKey(), e.getValue());
                return true;
            }
            return false;
        });
        loadConfigLocations();
        refreshAll();
    }

    private void loadConfigLocations() {
        for (CrateConfig.Crate crate : service.config().all()) {
            for (String loc : crate.locations()) {
                String[] p = loc.split(",");
                try {
                    Pos pos = new Pos(p[0].trim(), Integer.parseInt(p[1].trim()), Integer.parseInt(p[2].trim()), Integer.parseInt(p[3].trim()));
                    spots.putIfAbsent(pos, new Spot(crate.id(), true));
                } catch (RuntimeException e) {
                    logger.warning("[keycrates] Crate '" + crate.id() + "' location '" + loc + "' is not world,x,y,z; ignored.");
                }
            }
        }
    }

    @Nullable String crateAt(Block b) {
        Spot s = spots.get(Pos.of(b));
        return s == null ? null : s.crate;
    }

    void set(Block block, String crate) {
        Pos pos = Pos.of(block);
        Spot old = spots.put(pos, new Spot(crate, false));
        if (old != null) {
            removeDisplay(pos, old);
        }
        service.store().putBlock(new CrateStore.BlockRow(pos.world(), pos.x(), pos.y(), pos.z(), crate));
        refresh(pos);
    }

    /** Returns false when the block is not a crate, or is one from crates.yml (remove it there). */
    boolean remove(Block block) {
        Pos pos = Pos.of(block);
        Spot s = spots.get(pos);
        if (s == null || s.fromConfig) {
            return false;
        }
        spots.remove(pos, s);
        removeDisplay(pos, s);
        service.store().removeBlock(pos.world(), pos.x(), pos.y(), pos.z());
        return true;
    }

    List<String> describe() {
        return spots.entrySet().stream()
                .map(e -> e.getValue().crate + " @ " + e.getKey().world() + " " + e.getKey().x() + " " + e.getKey().y() + " " + e.getKey().z()
                        + (e.getValue().fromConfig ? " (crates.yml)" : ""))
                .sorted().toList();
    }

    // ------------------------------------------------------------------ holograms

    private void refreshAll() {
        for (Pos pos : spots.keySet()) {
            refresh(pos);
        }
    }

    private void refresh(Pos pos) {
        World w = Bukkit.getWorld(pos.world());
        if (w == null) {
            return;
        }
        Tasks.region.run(pos.center(w, 0), () -> {
            if (w.isChunkLoaded(pos.x() >> 4, pos.z() >> 4)) {
                ensure(pos, w, true);
            }
        });
    }

    /** On the region thread owning the position. */
    private void ensure(Pos pos, World w, boolean redraw) {
        Spot spot = spots.get(pos);
        if (spot == null) {
            return;
        }
        CrateConfig.Crate crate = service.config().crate(spot.crate);
        TextDisplay current = spot.display;
        if (crate == null || crate.hologram().isEmpty()) {
            if (current != null && current.isValid()) {
                current.remove();
            }
            spot.display = null;
            return;
        }
        ensureModel(pos, w, spot, crate, redraw);
        String text = String.join("\n", crate.hologram());
        if (current != null && current.isValid()) {
            if (redraw) {
                current.text(CrateConfig.text(text));
                current.teleportAsync(pos.center(w, crate.hologramHeight()));
            }
            return;
        }
        spot.display = w.spawn(pos.center(w, crate.hologramHeight()), TextDisplay.class, d -> {
            d.setBillboard(Display.Billboard.CENTER);
            d.setShadowed(true);
            d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            d.setPersistent(false); // never saved with the world; spawned again on chunk load
            d.text(CrateConfig.text(text));
        });
    }

    private void ensureModel(Pos pos, World w, Spot spot, CrateConfig.Crate crate, boolean redraw) {
        if (spot.opening.get()) {
            return;
        }
        org.bukkit.NamespacedKey model = service.config().crateModel;
        if (crate.model() == null || model == null) {
            removeParts(spot);
            return;
        }
        org.bukkit.block.Block block = w.getBlockAt(pos.x(), pos.y(), pos.z());
        if (block.getType() != org.bukkit.Material.BARRIER) {
            block.setType(org.bukkit.Material.BARRIER, false);
        }
        Location at = pos.center(w, 0.5);
        at.setYaw((float) Math.toDegrees(Math.atan2(-Math.signum(pos.x() + 0.5 - 1152.5), Math.signum(pos.z() + 0.5 - 1152.5))) + 180f);
        if (redraw || spot.base == null || !spot.base.isValid() || spot.lid == null || !spot.lid.isValid()) {
            removeParts(spot);
            spot.base = part(w, at, model, crate.model() + "_base", CrateShow.base());
            spot.lid = part(w, at, model, crate.model() + "_lid", CrateShow.lid(0f));
        }
    }

    private static org.bukkit.entity.ItemDisplay part(World w, Location at, org.bukkit.NamespacedKey model, String part,
                                                      org.bukkit.util.Transformation t) {
        return w.spawn(at, org.bukkit.entity.ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(CrateShow.modelItem(model, part));
            d.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
            d.setBrightness(new Display.Brightness(15, 15));
            d.setTransformation(t);
        });
    }

    private static void removeParts(Spot spot) {
        var base = spot.base;
        var lid = spot.lid;
        spot.base = null;
        spot.lid = null;
        if (base != null && base.isValid()) {
            base.remove();
        }
        if (lid != null && lid.isValid()) {
            lid.remove();
        }
    }

    boolean play(Pos pos, Player player, CrateConfig.Crate crate, CrateConfig.Reward won, boolean rare, Runnable done, Runnable retired) {
        Spot spot = spots.get(pos);
        World w = Bukkit.getWorld(pos.world());
        if (spot == null || w == null || !spot.crate.equals(crate.id()) || crate.model() == null) {
            return false;
        }
        var lid = spot.lid;
        if (lid == null || !lid.isValid() || !spot.opening.compareAndSet(false, true)) {
            return false;
        }
        CrateShow.play(pos.center(w, 0.5), lid, spot.display, crate, won, rare, player, done, retired, () -> spot.opening.set(false), logger);
        return true;
    }

    private void removeDisplay(Pos pos, Spot spot) {
        TextDisplay d = spot.display;
        World w = Bukkit.getWorld(pos.world());
        spot.display = null;
        if (w == null) {
            return;
        }
        Tasks.region.run(pos.center(w, 0), () -> {
            if (d != null && d.isValid()) {
                d.remove();
            }
            removeParts(spot);
        });
    }

    private void removeAllDisplays() {
        spots.forEach(this::removeDisplay);
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        String world = event.getWorld().getName();
        int cx = event.getChunk().getX();
        int cz = event.getChunk().getZ();
        for (Pos pos : spots.keySet()) {
            if (pos.world().equals(world) && pos.x() >> 4 == cx && pos.z() >> 4 == cz) {
                World w = event.getWorld();
                Tasks.region.run(pos.center(w, 0), () -> ensure(pos, w, false));
            }
        }
    }

    // ------------------------------------------------------------------ using crates

    @EventHandler(priority = EventPriority.LOW)
    public void onInteract(PlayerInteractEvent event) {
        Block b = event.getClickedBlock();
        if (b == null || (event.getAction() != Action.LEFT_CLICK_BLOCK && event.getAction() != Action.RIGHT_CLICK_BLOCK)) {
            return;
        }
        String crateId = crateAt(b);
        if (crateId == null) {
            return;
        }
        event.setCancelled(true); // no chest opening, no breaking, no placing against it
        if (event.getHand() != EquipmentSlot.HAND) {
            return; // the off-hand fires a second event
        }
        Player player = event.getPlayer();
        CrateConfig.Crate crate = service.config().crate(crateId);
        if (crate == null) {
            player.sendMessage(Msg.bad("This crate is closed."));
            return;
        }
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            if (player.isSneaking() && player.hasPermission("genscore.keycrates.admin")) {
                player.sendMessage(Msg.neutral("This is the " + Msg.white(crate.id()) + " crate. Remove it with " + Msg.white("/crate remove") + "."));
                return;
            }
            menus.preview(player, crate, 0, false);
            return;
        }
        if (service.keys().count(player.getInventory(), crate.id()) <= 0) {
            menus.preview(player, crate, 0, false);
            player.sendMessage(Msg.bad("You need a key to open this crate. Earn them by playing and voting."));
            return;
        }
        service.open(player, crate.id(), player.isSneaking() ? Integer.MAX_VALUE : 1, Pos.of(event.getClickedBlock()));
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (crateAt(event.getBlock()) != null) {
            event.setCancelled(true);
            if (event.getPlayer().hasPermission("genscore.keycrates.admin")) {
                event.getPlayer().sendMessage(Msg.neutral("That is a crate. Remove it with " + Msg.white("/crate remove") + " first."));
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().removeIf(b -> crateAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().removeIf(b -> crateAt(b) != null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (crateAt(event.getBlock()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChange(EntityChangeBlockEvent event) {
        if (crateAt(event.getBlock()) != null) {
            event.setCancelled(true); // endermen, withers, ravagers
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        for (Block b : event.getBlocks()) {
            if (crateAt(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        for (Block b : event.getBlocks()) {
            if (crateAt(b) != null) {
                event.setCancelled(true);
                return;
            }
        }
    }

    static String normalise(String crate) {
        return crate.toLowerCase(Locale.ROOT);
    }
}
