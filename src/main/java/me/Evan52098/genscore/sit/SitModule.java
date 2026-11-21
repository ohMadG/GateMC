package me.Evan52098.genscore.sit;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.plots.FactoryView;
import me.Evan52098.genscore.plots.Plot;
import me.Evan52098.genscore.plots.PlotService;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import me.Evan52098.genscore.worldrules.Text;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.Block;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /sit, /stand, and sitting on stairs, slabs and carpets by right-clicking them. Written fresh from SEBSit's config and
 * messages. The seat is an invisible, non-saved item display (so a crash leaves no seats behind); it removes itself
 * when its rider gets off, and checks every second that the block under it is still there.
 */
public final class SitModule implements GensModule, Listener {

    public static final String USE = "genscore.sit";
    public static final String ADMIN = "genscore.sit.admin";

    private record Settings(boolean clickEnabled, Set<String> blockTypes, boolean requireEmptyHand, boolean sitEnabled,
                            boolean requireSolidGround, boolean noSneaking, double seatOffset, Map<String, String> messages) {
        String msg(String key) {
            return messages.getOrDefault(key, "");
        }
    }

    private GensCore plugin;
    private volatile Settings settings;
    /** Player -> seat. */
    private final Map<UUID, Entity> seats = new ConcurrentHashMap<>();
    /** Blocks someone sits on ("world:x:y:z"), so two players can't share one stair. */
    private final Set<String> taken = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "sit";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        reload();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
    }

    @Override
    public void reload() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("sit");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        Set<String> blockTypes = new HashSet<>();
        List<String> configured = c.contains("right-click.allowed-block-types")
                ? c.getStringList("right-click.allowed-block-types") : List.of("STAIRS", "SLAB", "CARPET");
        for (String s : configured) {
            blockTypes.add(s.toUpperCase(Locale.ROOT));
        }
        Map<String, String> messages = new ConcurrentHashMap<>(Map.of(
                "already-sitting", "&eYou are already sitting. Use &f/stand&e to get up.",
                "sat-down", "&aYou sat down.",
                "stood-up", "&eYou stood up.",
                "not-sitting", "&cYou are not sitting.",
                "disabled", "&cSitting is currently disabled.",
                "cant-sit-here", "&cYou can't sit here."));
        ConfigurationSection m = c.getConfigurationSection("messages");
        if (m != null) {
            for (String key : m.getKeys(false)) {
                messages.put(key, m.getString(key, ""));
            }
        }
        settings = new Settings(c.getBoolean("right-click.enabled", true), Set.copyOf(blockTypes),
                c.getBoolean("right-click.require-empty-hand", false), c.getBoolean("enabled", true),
                c.getBoolean("require-solid-ground", true), c.getBoolean("prevent-sitting-while-sneaking", true),
                c.getDouble("seat-y-offset", 0.0), Map.copyOf(messages));
    }

    @Override
    public void disable() {
        // Seats are not saved with the world; ejecting here keeps riders from logging in mid-air on a reload.
        for (Entity seat : seats.values()) {
            try {
                if (Tasks.owns(seat)) {
                    seat.eject();
                    seat.remove();
                }
            } catch (RuntimeException ignored) {
                // shutting down; the seat is not saved with the world anyway
            }
        }
        seatKeys.clear();
        seats.clear();
        taken.clear();
    }

    private void say(Player p, String key) {
        String text = settings.msg(key);
        if (!text.isEmpty()) {
            p.sendMessage(Text.legacy(text));
        }
    }

    // ----------------------------------------------------------------------------------------------- commands

    @Command({"sit", "seat"})
    @CommandPermission(USE)
    @Description("Sit down where you are")
    public void sit(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Settings s = settings;
        if (!s.sitEnabled()) {
            say(player, "disabled");
            return;
        }
        if (seats.containsKey(player.getUniqueId())) {
            say(player, "already-sitting");
            return;
        }
        if (!canSitNow(player, s)) {
            say(player, "cant-sit-here");
            return;
        }
        Location l = player.getLocation();
        Block below = l.getBlock().getRelative(0, -1, 0);
        if (s.requireSolidGround() && !below.getType().isSolid()) {
            say(player, "cant-sit-here");
            return;
        }
        // Sit on the surface under the feet (a slab, a stair top or a full block).
        double surface = l.getBlock().isPassable() ? below.getBoundingBox().getMaxY() : l.getY();
        if (surface < below.getY()) {
            surface = l.getY();
        }
        seat(player, new Location(l.getWorld(), l.getX(), surface, l.getZ(), l.getYaw(), 0), null);
    }

    @Command("sit reload")
    @CommandPermission(ADMIN)
    @Description("Reread the sit settings")
    public void reloadCommand(BukkitCommandActor actor) {
        Tasks.global.run(() -> {
            plugin.reloadConfig();
            reload();
            Tasks.forSender(actor.sender(), () -> actor.reply(Msg.good("Sit settings reloaded.")));
        });
    }

    @Command("stand")
    @CommandPermission(USE)
    @Description("Stand up from a seat")
    public void stand(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (!seats.containsKey(player.getUniqueId())) {
            say(player, "not-sitting");
            return;
        }
        standUp(player);
    }

    // ----------------------------------------------------------------------------------------------- clicking

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        Settings s = settings;
        if (!s.clickEnabled() || !s.sitEnabled() || event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getHand() != EquipmentSlot.HAND
                || event.useInteractedBlock() == org.bukkit.event.Event.Result.DENY) {
            return;
        }
        Block block = event.getClickedBlock();
        Player player = event.getPlayer();
        if (block == null || !player.hasPermission(USE) || seats.containsKey(player.getUniqueId())) {
            return;
        }
        if (s.requireEmptyHand() && !player.getInventory().getItemInMainHand().isEmpty()) {
            return;
        }
        double height = seatHeight(block, s);
        if (Double.isNaN(height) || !block.getRelative(0, 1, 0).isPassable() || !canSitNow(player, s)) {
            return;
        }
        if (player.getLocation().distanceSquared(block.getLocation().add(0.5, 0.5, 0.5)) > 9) {
            return;
        }
        if (!block.equals(player.getTargetBlockExact(5)) || intoOtherPlot(player, block)) {
            return;
        }
        Location at = block.getLocation().add(0.5, height, 0.5);
        at.setYaw(player.getLocation().getYaw());
        if (block.getBlockData() instanceof Stairs stairs) {
            // Face away from the back of the stair, like sitting on a chair.
            at.setDirection(stairs.getFacing().getOppositeFace().getDirection());
            at.setPitch(0);
        }
        event.setCancelled(true);
        seat(player, at, key(block));
    }

    /** Height of the seat above the block's base, or NaN if the block can't be sat on. */
    private static double seatHeight(Block block, Settings s) {
        Material type = block.getType();
        if (s.blockTypes().contains("STAIRS") && Tag.STAIRS.isTagged(type)
                && block.getBlockData() instanceof Stairs st && st.getHalf() == Bisected.Half.BOTTOM) {
            return 0.5;
        }
        if (s.blockTypes().contains("SLAB") && Tag.SLABS.isTagged(type)
                && block.getBlockData() instanceof Slab sl && sl.getType() == Slab.Type.BOTTOM) {
            return 0.5;
        }
        if (s.blockTypes().contains("CARPET") && (Tag.WOOL_CARPETS.isTagged(type) || type == Material.MOSS_CARPET)) {
            return 0.0625;
        }
        return Double.NaN;
    }

    private boolean intoOtherPlot(Player player, Block block) {
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        PlotService service = plots == null ? null : plots.service();
        if (service == null) {
            return false;
        }
        Plot plot = service.plotAt(block.getLocation());
        if (plot == null || plot.owner().equals(player.getUniqueId())) {
            return false;
        }
        FactoryView view = service.view(plot);
        Location at = player.getLocation();
        return view.inside(block.getX(), block.getZ()) && !view.inside(at.getBlockX(), at.getBlockZ());
    }

    private static boolean canSitNow(Player p, Settings s) {
        if (s.noSneaking() && p.isSneaking()) {
            return false;
        }
        return !p.isInsideVehicle() && !p.isGliding() && !p.isFlying() && !p.isSwimming() && !p.isDead()
                && p.getGameMode() != GameMode.SPECTATOR;
    }

    private static String key(Block b) {
        return b.getWorld().getName() + ":" + b.getX() + ":" + b.getY() + ":" + b.getZ();
    }

    // ----------------------------------------------------------------------------------------------- seats

    /** Spawns the seat and puts the player on it. Runs on the player's thread, which owns this nearby spot. */
    private void seat(Player player, Location at, @Nullable String blockKey) {
        if (!Tasks.owns(at)) {
            return;
        }
        if (blockKey != null && !taken.add(blockKey)) {
            return; // someone else sits there
        }
        Location spot = at.clone().add(0, settings.seatOffset(), 0);
        ItemDisplay seat = at.getWorld().spawn(spot, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setInvulnerable(true);
            d.setGravity(false);
            d.setSilent(true);
        });
        if (!seat.addPassenger(player)) {
            seat.remove();
            if (blockKey != null) {
                taken.remove(blockKey);
            }
            say(player, "cant-sit-here");
            return;
        }
        seats.put(player.getUniqueId(), seat);
        if (blockKey != null) {
            seatKeys.put(seat.getUniqueId(), blockKey);
        }
        Block support = blockKey != null ? at.getBlock() : at.clone().add(0, -0.01, 0).getBlock();
        Material supportType = support.getType();
        say(player, "sat-down");
        // Watchdog on the seat's own thread: gone rider or gone block -> remove the seat.
        Tasks.entity.timer(seat, t -> {
            boolean riderOk = seat.getPassengers().contains(player) && player.isOnline();
            boolean blockOk = support.getType() == supportType;
            if (!seat.isValid() || !riderOk || !blockOk) {
                t.cancel();
                seat.eject();
                seats.remove(player.getUniqueId(), seat);
                removeSeat(seat);
            }
        }, 20, 20);
    }

    /** Seat -> the block it holds, to free it on dismount. */
    private final Map<UUID, String> seatKeys = new ConcurrentHashMap<>();

    private void standUp(Player player) {
        Entity seat = seats.remove(player.getUniqueId());
        if (seat == null) {
            return;
        }
        Tasks.onEntity(seat, () -> {
            seat.eject();
            removeSeat(seat);
        });
        say(player, "stood-up");
    }

    private void removeSeat(Entity seat) {
        String k = seatKeys.remove(seat.getUniqueId());
        if (k != null) {
            taken.remove(k);
        }
        seat.remove();
    }

    @EventHandler
    public void onDismount(EntityDismountEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        Entity seat = seats.get(player.getUniqueId());
        if (seat == null || !seat.equals(event.getDismounted())) {
            return;
        }
        seats.remove(player.getUniqueId(), seat);
        // Not while the dismount is in progress; on the seat's thread next tick. Lift the player out of the slab.
        Tasks.entity.run(seat, () -> removeSeat(seat));
        Tasks.entity.run(player, () -> {
            Location l = player.getLocation();
            if (!l.getBlock().isPassable()) {
                player.teleportAsync(l.add(0, 0.6, 0), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        });
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (event.getCause() != PlayerTeleportEvent.TeleportCause.DISMOUNT && seats.containsKey(event.getPlayer().getUniqueId())) {
            Player p = event.getPlayer();
            Entity seat = seats.remove(p.getUniqueId());
            if (seat != null) {
                p.leaveVehicle();
                Tasks.onEntity(seat, () -> removeSeat(seat));
            }
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Entity seat = seats.remove(event.getPlayer().getUniqueId());
        if (seat != null) {
            event.getPlayer().leaveVehicle();
            Tasks.onEntity(seat, () -> removeSeat(seat));
        }
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        Entity seat = seats.remove(event.getPlayer().getUniqueId());
        if (seat != null) {
            Tasks.onEntity(seat, () -> {
                seat.eject();
                removeSeat(seat);
            });
        }
    }
}
