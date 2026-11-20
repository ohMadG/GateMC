package me.Evan52098.genscore.plots;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.teleport.TeleportModule;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Roadmap phase 1: one plot per online player on a grid in a void world, built from one template on join and removed
 * after the owner leaves. Commands: {@code /plot}, {@code /plotadmin ...}.
 *
 * <p>The plot world must already exist when the server starts: Folia cannot create worlds while running. Use the
 * server's main world and make it void with a flat preset (see {@code plots:} in config.yml).
 */
public final class PlotsModule implements GensModule, Listener {

    public static final String ADMIN = "genscore.admin.plots";
    public static final String BUILD = "genscore.plots.build";

    private GensCore plugin;
    private PlotSettings.Grid grid;
    private volatile PlotSettings.Live live;
    private volatile PlotTemplate template;
    private PlotService service;
    private PlayerSection<PlotData> plotSection;
    private final Set<UUID> rescuing = ConcurrentHashMap.newKeySet();

    @Override
    public String id() {
        return "plots";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_players (uuid TEXT PRIMARY KEY, rebirths INTEGER NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS gens_plots (uuid TEXT PRIMARY KEY, belt_length INTEGER NOT NULL, "
                        + "lock_level INTEGER NOT NULL DEFAULT 0, shield_until BIGINT NOT NULL DEFAULT 0);"
                        + "CREATE TABLE IF NOT EXISTS gens_slots (slot INTEGER PRIMARY KEY, owner TEXT NOT NULL, since BIGINT NOT NULL)");

        ConfigurationSection root = config();
        grid = PlotSettings.grid(root);
        grid.warnings(plugin.getLogger());
        live = PlotSettings.live(root, plugin.getLogger());
        World world = Bukkit.getWorld(grid.worldName());
        if (world == null) {
            throw new IllegalStateException("plots.world '" + grid.worldName() + "' is not loaded. Folia cannot create worlds "
                    + "while running: use a world the server loads at start-up (normally level-name in server.properties).");
        }
        template = PlotTemplate.load(templateFile(), grid.size(), live, plugin.getLogger());

        PlotBuilder builder = new PlotBuilder(plugin, grid, () -> live, () -> template);
        service = new PlotService(plugin.database(), plugin.getLogger(), world, grid, () -> live, () -> template, builder);
        int recovered = service.recover();
        if (recovered > 0) {
            plugin.getLogger().info("Clearing " + recovered + " plot slot(s) left from the last run.");
        }

        plotSection = PlotData.section(() -> live.defaultBeltLength());
        plugin.playerData().register(plotSection);
        plugin.playerData().register(GensPlayer.SECTION);
        plugin.commands().register(new PlotCommands(this));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Plots: world " + world.getName() + ", " + grid.maxSlots() + " slots, " + grid.size() + "x"
                + grid.size() + ", spacing " + grid.spacing() + ", template " + template.source() + ".");
    }

    @Override
    public void disable() {
        if (service != null) {
            service.close();
        }
    }

    /** Rereads everything except the grid, which only changes on a restart (moving it would strand plot blocks). */
    @Override
    public void reload() {
        ConfigurationSection root = config();
        PlotSettings.Grid newGrid = PlotSettings.grid(root);
        if (!newGrid.equals(grid)) {
            plugin.getLogger().warning("plots: world, size and grid changes take effect after a restart.");
        }
        live = PlotSettings.live(root, plugin.getLogger());
        reloadTemplate();
    }

    void reloadTemplate() {
        template = PlotTemplate.load(templateFile(), grid.size(), live, plugin.getLogger());
    }

    // ------------------------------------------------------------------ accessors

    GensCore plugin() {
        return plugin;
    }

    public PlotService service() {
        return service;
    }

    PlotTemplate template() {
        return template;
    }

    PlotSettings.Live live() {
        return live;
    }

    File templateFile() {
        return new File(new File(plugin.getDataFolder(), "modules/plots"), live.templateFile());
    }

    /** An online player's lasting plot data, or null if they are not loaded. */
    public @Nullable PlotData data(UUID player) {
        var data = plugin.playerData().get(player);
        return data == null ? null : data.get(plotSection);
    }

    public @Nullable GensPlayer gensPlayer(UUID player) {
        var data = plugin.playerData().get(player);
        return data == null ? null : data.get(GensPlayer.SECTION);
    }

    /** Where to send someone who has no plot to go to: /setspawn if the teleport module has one, else world spawn. */
    Location fallbackSpawn() {
        TeleportModule teleport = plugin.modules().get(TeleportModule.class);
        Location spawn = teleport != null ? teleport.spawnLocation() : null;
        return spawn != null ? spawn : service.world().getSpawnLocation();
    }

    // ------------------------------------------------------------------ lifecycle events

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        service.create(player.getUniqueId()).whenComplete((plot, error) -> Tasks.entity.run(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                player.sendMessage(Msg.bad("Your plot could not be built. Try " + Msg.white("/plot") + " in a moment, or tell staff."));
                return;
            }
            if (live.teleportOnJoin()) {
                player.teleportAsync(service.spawnOf(plot), PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        rescuing.remove(event.getPlayer().getUniqueId());
        service.ownerLeft(event.getPlayer().getUniqueId());
    }

    /** Falling off a plot into the void: back to the player's own plot, or spawn if it is not ready. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Location to = event.getTo();
        if (to.getY() >= live.voidY() || to.getWorld() != service.world()) {
            return;
        }
        Player player = event.getPlayer();
        if (!rescuing.add(player.getUniqueId())) {
            return;
        }
        Plot plot = service.plotOf(player.getUniqueId());
        Location target = plot != null && plot.isReady() ? service.spawnOf(plot) : fallbackSpawn();
        player.setFallDistance(0);
        player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN)
                .whenComplete((ok, err) -> rescuing.remove(player.getUniqueId()));
    }

    // ------------------------------------------------------------------ protection (blocks are never saved)

    private boolean blocked(Player player, World world) {
        return live.protectWorld() && world == service.world() && !player.hasPermission(BUILD);
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (blocked(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    private ConfigurationSection config() {
        ConfigurationSection s = plugin.getConfig().getConfigurationSection("plots");
        return s != null ? s : new MemoryConfiguration();
    }
}
