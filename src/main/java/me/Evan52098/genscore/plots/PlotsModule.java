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
    private volatile PlotTemplate hub;
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
                        + "CREATE TABLE IF NOT EXISTS gens_slots (slot INTEGER PRIMARY KEY, owner TEXT NOT NULL, since BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS gens_platforms (id INTEGER PRIMARY KEY, built_at BIGINT NOT NULL)",
                "ALTER TABLE gens_plots ADD COLUMN speed_level INTEGER NOT NULL DEFAULT 0");

        ConfigurationSection root = config();
        grid = PlotSettings.grid(root);
        grid.warnings(plugin.getLogger());
        live = PlotSettings.live(root, plugin.getLogger());
        World world = Bukkit.getWorld(grid.worldName());
        if (world == null) {
            throw new IllegalStateException("plots.world '" + grid.worldName() + "' is not loaded. Folia cannot create worlds "
                    + "while running: use a world the server loads at start-up (normally level-name in server.properties).");
        }
        reloadTemplates();

        PlotBuilder builder = new PlotBuilder(plugin, grid, () -> live, () -> template, () -> hub);
        service = new PlotService(plugin.database(), plugin.getLogger(), world, grid, () -> live, () -> template, builder);
        int recovered = service.recover(this::firstPlatformReady);
        if (recovered > 0) {
            plugin.getLogger().info("Clearing " + recovered + " plot slot(s) left from the last run.");
        }

        plotSection = PlotData.section(() -> live.defaultBeltLength());
        plugin.playerData().register(plotSection);
        plugin.playerData().register(GensPlayer.SECTION);
        plugin.commands().register(new PlotCommands(this));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.getLogger().info("Plots: world " + world.getName() + ", platforms of " + grid.perPlatform() + " factories ("
                + (2 * grid.reach() + 1) + " blocks across), up to " + grid.maxPlatforms() + " platforms, factory template "
                + template.source() + ", hub " + hub.source() + ".");
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
        reloadTemplates();
    }

    /** Rereads the factory template and the hub template (either falls back when its file is missing). */
    void reloadTemplates() {
        PlotTemplate factory = PlotTemplate.load(templateFile(), grid.size(), live.markers(), "factory template", plugin.getLogger());
        if (factory == null) {
            plugin.getLogger().info("No factory template at " + templateFile().getPath() + "; using a plain " + grid.size() + "x"
                    + grid.size() + " floor with a yellow front edge.");
            factory = PlotTemplate.fallbackFactory(grid.size(), live);
        }
        int plazaWidth = grid.plazaHalf() * 2 + 1;
        PlotTemplate hubTemplate = PlotTemplate.load(hubFile(), plazaWidth, live.markers(), "hub template", plugin.getLogger());
        template = factory;
        hub = hubTemplate != null ? hubTemplate : PlotTemplate.emptyHub(plazaWidth);
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

    File hubFile() {
        return new File(new File(plugin.getDataFolder(), "modules/plots"), live.platform().hubFile());
    }

    PlotTemplate hub() {
        return hub;
    }

    PlotSettings.Grid grid() {
        return grid;
    }

    /** A new player's belt length (plots.defaults.belt-length). */
    public int defaultBeltLength() {
        return live.defaultBeltLength();
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

    /**
     * Where to send someone who has no factory to go to: /setspawn if the teleport module has one, else the first
     * platform's hub, else the world spawn.
     */
    Location fallbackSpawn() {
        TeleportModule teleport = plugin.modules().get(TeleportModule.class);
        Location spawn = teleport != null ? teleport.spawnLocation() : null;
        if (spawn == null) {
            spawn = service.hubSpawn();
        }
        return spawn != null ? spawn : service.world().getSpawnLocation();
    }

    /** The first platform exists: point the world spawn at its hub, so new players appear there instead of the void. */
    private void firstPlatformReady() {
        if (!live.setWorldSpawn()) {
            return;
        }
        Location hubSpawn = service.hubSpawn();
        if (hubSpawn == null) {
            return;
        }
        Tasks.global.run(() -> {
            try {
                service.world().setSpawnLocation(hubSpawn);
                plugin.getLogger().info("World spawn set to the hub at " + hubSpawn.getBlockX() + ", " + hubSpawn.getBlockY() + ", "
                        + hubSpawn.getBlockZ() + " (plots.set-world-spawn).");
            } catch (RuntimeException e) {
                plugin.getLogger().warning("Could not set the world spawn to the hub: " + e.getMessage());
            }
        });
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
