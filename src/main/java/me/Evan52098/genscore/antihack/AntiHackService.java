package me.Evan52098.genscore.antihack;

import me.Evan52098.genscore.lagwatch.RegionTps;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityToggleGlideEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerRiptideEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.event.vehicle.VehicleExitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * BlockvilleCore's extra anti-hack checks next to GrimAC (which stays the main anticheat). Watches movement, reach,
 * combat, clicking, building and client behaviour; keeps a 30-second record per player; alerts staff
 * ({@code genscore.antihack.alert}) when it reaches the configured level; optionally sets back and kicks.
 *
 * <p>Folia, what changed from BlockvilleCore:
 * <ul>
 *   <li>Each player has a one-tick timer on their own entity scheduler (it follows them between regions). It counts
 *       their region's ticks (replacing {@code Bukkit.getCurrentTick()}), records the position history the reach check
 *       uses, and once a second judges and reports that player. So a player's record is only ever touched on their own
 *       thread; there is no global loop over everyone.</li>
 *   <li>"The server is lagging, don't judge" is per region: the player's region TPS from {@link RegionTps} (Paper:
 *       the normal server TPS).</li>
 *   <li>Staff alerts are sent on each staff member's thread; setbacks use {@code teleportAsync} on the player's thread
 *       one tick later; the log file is written on an async thread.</li>
 * </ul>
 * Left out: the cross-server alert relay (Redis), and the Heimdall kick record and staff note (GensCore has no
 * punishment system; the kick is a plain kick and the note goes to antihack.log).
 */
public final class AntiHackService implements Listener {

    public static final String ALERT_PERMISSION = "genscore.antihack.alert";
    public static final String BYPASS_PERMISSION = "genscore.antihack.bypass";
    public static final String ADMIN_PERMISSION = "genscore.antihack.admin";

    private static final long WINDOW_MS = 30_000L;
    private static final long REALERT_MS = 20_000L;
    private static final double LAG_TPS = 17.5;
    private static final int MAX_PING = 450;
    private static final int RECENT_LIMIT = 200;
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    public record Incident(long at, String player, AntiHackLevel level, String summary, int ping) {
    }

    private final Plugin plugin;
    private volatile ConfigurationSection config;
    private final File overridesFile;
    private final Map<UUID, PlayerTrack> tracks = new ConcurrentHashMap<>();
    private final List<Predicate<Player>> exemptions = new CopyOnWriteArrayList<>();
    private final List<BiConsumer<Player, PlayerTrack>> everySecond = new CopyOnWriteArrayList<>();
    private final Set<UUID> mutedStaff = ConcurrentHashMap.newKeySet();
    private final Deque<Incident> recent = new ConcurrentLinkedDeque<>();
    private final File logFile;
    private final Object logLock = new Object();
    private final RegionTps regionTps;

    private YamlConfiguration overrides = new YamlConfiguration();
    private volatile boolean enabled;
    private volatile boolean strict;
    private volatile boolean kick;
    private volatile java.util.Set<AntiHackCheck> enforced = java.util.EnumSet.noneOf(AntiHackCheck.class);
    private volatile boolean setback;
    private volatile AntiHackLevel alertLevel = AntiHackLevel.MEDIUM;

    AntiHackService(Plugin plugin, @Nullable ConfigurationSection config, File moduleFolder, RegionTps regionTps) {
        this.plugin = plugin;
        this.config = config;
        this.overridesFile = new File(moduleFolder, "settings.yml");
        this.logFile = new File(plugin.getDataFolder(), "antihack.log");
        this.regionTps = regionTps;
        reloadSettings();
    }

    /** A new config.yml section after a plugin reload. */
    void config(@Nullable ConfigurationSection section) {
        this.config = section;
        reloadSettings();
    }

    void start() {
        for (Player online : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(online, () -> begin(online));
        }
    }

    void stop() {
        tracks.clear();
    }

    // ------------------------------------------------------------------ settings

    /**
     * config.yml's {@code antihack:} section, then the switches changed in game with /antihack (kept in
     * modules/antihack/settings.yml so this module never rewrites config.yml).
     */
    public synchronized void reloadSettings() {
        overrides = overridesFile.isFile() ? YamlConfiguration.loadConfiguration(overridesFile) : new YamlConfiguration();
        apply();
    }

    private synchronized void apply() {
        boolean e = config == null || config.getBoolean("enabled", true);
        boolean s = config != null && config.getBoolean("strict", false);
        boolean k = config != null && config.getBoolean("kick", false);
        boolean b = config != null && config.getBoolean("setback", false);
        String level = config == null ? "medium" : config.getString("alert-level", "medium");
        enabled = overrides.getBoolean("enabled", e);
        strict = overrides.getBoolean("strict", s);
        kick = overrides.getBoolean("kick", k);
        setback = overrides.getBoolean("setback", b);
        java.util.Set<AntiHackCheck> en = java.util.EnumSet.noneOf(AntiHackCheck.class);
        List<String> names = config != null && config.isList("enforce") ? config.getStringList("enforce") : List.of("speed", "fly", "reach");
        for (String name : names) {
            switch (name.toLowerCase(java.util.Locale.ROOT)) {
                case "speed" -> en.add(AntiHackCheck.SPEED);
                case "fly" -> {
                    en.add(AntiHackCheck.FLY_HOVER);
                    en.add(AntiHackCheck.FLY_RISE);
                }
                case "reach" -> en.add(AntiHackCheck.REACH);
                default -> {
                    try {
                        en.add(AntiHackCheck.valueOf(name.toUpperCase(java.util.Locale.ROOT)));
                    } catch (IllegalArgumentException ignored) {
                        plugin.getLogger().warning("[AntiHack] Unknown check in antihack.enforce: " + name);
                    }
                }
            }
        }
        enforced = en;
        AntiHackLevel parsed = AntiHackLevel.parse(level, AntiHackLevel.MEDIUM);
        alertLevel = parsed == AntiHackLevel.NONE ? AntiHackLevel.LOW : parsed;
    }

    /** Changes a switch now; the file is written on an async thread (no disk work on a region thread). */
    public synchronized void setFlag(String key, boolean value) {
        overrides.set(key, value);
        apply();
        String text = overrides.saveToString();
        Tasks.async.run(() -> {
            synchronized (overridesFile) {
                try {
                    File parent = overridesFile.getParentFile();
                    if (parent != null) {
                        parent.mkdirs();
                    }
                    Files.writeString(overridesFile.toPath(), text, StandardCharsets.UTF_8);
                } catch (IOException ex) {
                    plugin.getLogger().warning("[AntiHack] Could not save " + overridesFile.getName() + ": " + ex.getMessage());
                }
            }
        });
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isStrict() {
        return strict;
    }

    public boolean isKick() {
        return kick;
    }

    public boolean isSetback() {
        return setback;
    }

    public boolean enforces(AntiHackCheck check) {
        return enabled && enforced.contains(check);
    }

    public AntiHackLevel alertLevel() {
        return alertLevel;
    }

    /** Lets other modules exempt players (events, minigames). Checked on the player's thread. */
    public void addExemption(Predicate<Player> exemption) {
        exemptions.add(exemption);
    }

    public boolean toggleAlerts(UUID staff) {
        if (mutedStaff.remove(staff)) {
            return true;
        }
        mutedStaff.add(staff);
        return false;
    }

    // ------------------------------------------------------------------ per-player timer

    /** Detectors that judge once a second (packet rates, ping) register here; runs on the player's thread. */
    void everySecond(BiConsumer<Player, PlayerTrack> check) {
        everySecond.add(check);
    }

    /** Player's thread. */
    private void begin(Player player) {
        PlayerTrack track = new PlayerTrack();
        PlayerTrack previous = tracks.put(player.getUniqueId(), track);
        if (previous != null && previous.timer != null) {
            previous.timer.cancel();
        }
        track.timer = Tasks.entity.timer(player, t -> tick(player, track), 1, 1);
    }

    private void tick(Player player, PlayerTrack track) {
        track.tick++;
        if (!enabled) {
            return;
        }
        track.recordPosition(player.getX(), player.getY(), player.getZ());
        if (track.tick % 20 == 0) {
            for (BiConsumer<Player, PlayerTrack> check : everySecond) {
                check.accept(player, track);
            }
            report(player, track);
        }
    }

    /** The player's record. Player's thread. Created on demand for the moment between join and the timer starting. */
    PlayerTrack track(Player player) {
        return tracks.computeIfAbsent(player.getUniqueId(), id -> new PlayerTrack());
    }

    /** Ticks of the player's region since they joined. Player's thread. */
    int tick(Player player) {
        return track(player).tick;
    }

    Plugin plugin() {
        return plugin;
    }

    // ------------------------------------------------------------------ judging

    boolean shouldCheck(Player player, PlayerTrack track) {
        return judgeable(player, track, false, false, false);
    }

    boolean shouldCheckRider(Player player, PlayerTrack track) {
        return judgeable(player, track, true, false, false);
    }

    boolean shouldCheckGlider(Player player, PlayerTrack track) {
        return judgeable(player, track, false, true, false);
    }

    boolean shouldCheckAnyPing(Player player, PlayerTrack track) {
        return judgeable(player, track, true, true, true);
    }

    private boolean judgeable(Player player, PlayerTrack track, boolean vehicleOk, boolean glidingOk, boolean anyPing) {
        if (!enabled || lagging(player) || track.tick < track.ignoreUntilTick) {
            return false;
        }
        GameMode mode = player.getGameMode();
        if (mode != GameMode.SURVIVAL && mode != GameMode.ADVENTURE) {
            return false;
        }
        if (player.getAllowFlight() || player.isFlying() || (!vehicleOk && player.isInsideVehicle()) || (!glidingOk && player.isGliding())
                || player.isRiptiding() || player.isDead() || player.isSleeping()) {
            return false;
        }
        if ((!anyPing && player.getPing() > MAX_PING) || isBedrock(player)) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now - track.bypassCheckedAt > 5000L) {
            track.bypassCheckedAt = now;
            track.bypass = player.hasPermission(BYPASS_PERMISSION);
        }
        if (track.bypass) {
            return false;
        }
        for (Predicate<Player> exemption : exemptions) {
            if (exemption.test(player)) {
                return false;
            }
        }
        return true;
    }

    /** The player's region is too slow to judge movement fairly. */
    private boolean lagging(Player player) {
        double server = RegionTps.serverTps();
        double tps = Double.isNaN(server) ? regionTps.tps(player.getUniqueId()) : server;
        return tps < LAG_TPS;
    }

    double tpsOf(Player player) {
        double server = RegionTps.serverTps();
        return Double.isNaN(server) ? regionTps.tps(player.getUniqueId()) : server;
    }

    private static boolean isBedrock(Player player) {
        return player.getUniqueId().getMostSignificantBits() == 0L;
    }

    /** Player's thread. */
    void grace(Player player, int ticks) {
        PlayerTrack track = track(player);
        track.ignoreUntilTick = Math.max(track.ignoreUntilTick, track.tick + ticks);
        track.resetMovement();
    }

    /** Player's thread. */
    void flag(Player player, PlayerTrack track, AntiHackCheck check) {
        flag(player, track, check, null);
    }

    void flag(Player player, PlayerTrack track, AntiHackCheck check, @Nullable Location backTo) {
        track.offenses.addLast(new PlayerTrack.Offense(System.currentTimeMillis(), check));
        if (check.isMovement() && (setback || enforced.contains(check))) {
            setBack(player, track, backTo != null && backTo.getWorld() == player.getWorld() ? backTo : track.lastSafe);
        }
    }

    private void setBack(Player player, PlayerTrack track, @Nullable Location safe) {
        if (safe == null || track.tick - track.lastSetbackTick < 20) {
            return;
        }
        track.lastSetbackTick = track.tick;
        Location target = safe.clone();
        // Next tick on the player's thread, never inside the move event that flagged it.
        Tasks.entity.run(player, () -> {
            target.setYaw(player.getLocation().getYaw());
            target.setPitch(player.getLocation().getPitch());
            track.settingBack = true;
            player.teleportAsync(target, PlayerTeleportEvent.TeleportCause.PLUGIN);
        });
    }

    private static int points(PlayerTrack track, long now) {
        track.offenses.removeIf(offense -> now - offense.at() > WINDOW_MS);
        int total = 0;
        for (PlayerTrack.Offense offense : track.offenses) {
            total += offense.check().weight();
        }
        return total;
    }

    private static String summary(PlayerTrack track) {
        Map<AntiHackCheck, Integer> counts = new EnumMap<>(AntiHackCheck.class);
        for (PlayerTrack.Offense offense : track.offenses) {
            counts.merge(offense.check(), 1, Integer::sum);
        }
        List<String> parts = new ArrayList<>();
        counts.forEach((check, count) -> parts.add(check.label() + " x" + count));
        return String.join(", ", parts);
    }

    /** The player's record in words. Player's thread (the command hops there first). */
    String describe(Player player) {
        PlayerTrack track = tracks.get(player.getUniqueId());
        if (track == null) {
            return "No record.";
        }
        int points = points(track, System.currentTimeMillis());
        if (points == 0) {
            return "Clean over the last 30 seconds.";
        }
        return AntiHackLevel.of(points, strict).label() + " (" + points + " points): " + summary(track);
    }

    public List<Incident> recentIncidents(@Nullable String playerName, int limit) {
        List<Incident> out = new ArrayList<>();
        for (Incident incident : recent) {
            if (playerName == null || incident.player().equalsIgnoreCase(playerName)) {
                out.add(incident);
            }
        }
        Collections.reverse(out);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** Once a second per player, on their thread (BlockvilleCore's report(), split per player). */
    private void report(Player player, PlayerTrack track) {
        if (track.offenses.isEmpty() && track.alertedLevel == AntiHackLevel.NONE) {
            return;
        }
        long now = System.currentTimeMillis();
        AntiHackLevel level = AntiHackLevel.of(points(track, now), strict);
        if (level.ordinal() < track.alertedLevel.ordinal()) {
            track.alertedLevel = level;
            return;
        }
        boolean escalated = level.ordinal() > track.alertedLevel.ordinal();
        boolean sustained = level == AntiHackLevel.EXTREME && now - track.lastAlertAt >= REALERT_MS;
        if (!escalated && !sustained) {
            return;
        }
        track.alertedLevel = level;
        if (level.ordinal() < alertLevel.ordinal()) {
            return;
        }
        track.lastAlertAt = now;
        String summary = summary(track);
        alertStaff(player, level, summary);
        record(player, level, summary);
        if (level == AntiHackLevel.EXTREME) {
            extreme(player, summary);
        }
    }

    private void alertStaff(Player suspect, AntiHackLevel level, String summary) {
        String line = suspect.getName() + " &8- " + level.colour() + "&l" + level.label().toUpperCase() + " &8(&7" + summary + "&8) &7ping &f"
                + suspect.getPing() + "ms &7tps &f" + String.format("%.1f", Math.min(20.0, tpsOf(suspect)));
        Component message = Msg.bad("").append(LEGACY.deserialize(line))
                .append(LEGACY.deserialize(" &8[&eTeleport&8]")
                        .clickEvent(ClickEvent.runCommand("/tp " + suspect.getName()))
                        .hoverEvent(HoverEvent.showText(LEGACY.deserialize("&7Teleport to " + suspect.getName()))));
        UUID suspectId = suspect.getUniqueId();
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (!staff.getUniqueId().equals(suspectId) && staff.hasPermission(ALERT_PERMISSION) && !mutedStaff.contains(staff.getUniqueId())) {
                Tasks.onEntity(staff, () -> staff.sendMessage(message));
            }
        }
    }

    private void record(Player player, AntiHackLevel level, String summary) {
        Incident incident = new Incident(System.currentTimeMillis(), player.getName(), level, summary, player.getPing());
        recent.addLast(incident);
        while (recent.size() > RECENT_LIMIT) {
            recent.pollFirst();
        }
        Location at = player.getLocation();
        log(Instant.ofEpochMilli(incident.at()) + " " + player.getName() + " " + player.getUniqueId() + " " + level.label().toUpperCase() + " [" + summary + "] ping="
                + incident.ping() + "ms tps=" + String.format("%.1f", tpsOf(player)) + " " + at.getWorld().getName() + " " + at.getBlockX() + " " + at.getBlockY() + " " + at.getBlockZ());
    }

    private void extreme(Player player, String summary) {
        plugin.getLogger().warning("[AntiHack] " + player.getName() + " is at EXTREME: " + summary);
        if (!kick) {
            return;
        }
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.getUniqueId().equals(player.getUniqueId()) && other.hasPermission(ALERT_PERMISSION) && !mutedStaff.contains(other.getUniqueId())) {
                return; // staff are online to look themselves
            }
        }
        log(Instant.now() + " " + player.getName() + " KICKED [" + summary + "]");
        player.kick(LEGACY.deserialize("&cDisconnected by the anti-hack\n\n&7Your movement did not look possible (" + summary + ").\n&7If this was a mistake, tell a staff member."));
    }

    private void log(String line) {
        if (!plugin.isEnabled()) {
            return;
        }
        Tasks.async.run(() -> {
            synchronized (logLock) {
                try {
                    Files.writeString(logFile.toPath(), line + System.lineSeparator(), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                } catch (IOException ex) {
                    plugin.getLogger().warning("Could not write antihack.log: " + ex.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ grace periods (all on the player's own thread)

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        begin(player);
        grace(player, 100);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        PlayerTrack track = tracks.remove(event.getPlayer().getUniqueId());
        if (track != null && track.timer != null) {
            track.timer.cancel();
        }
        mutedStaff.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerTrack track = track(event.getPlayer());
        if (track.settingBack) {
            track.settingBack = false;
            grace(event.getPlayer(), 5);
            return;
        }
        grace(event.getPlayer(), 60);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        grace(event.getPlayer(), 100);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        grace(event.getPlayer(), 100);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVelocity(PlayerVelocityEvent event) {
        grace(event.getPlayer(), 80);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 20);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGameMode(PlayerGameModeChangeEvent event) {
        grace(event.getPlayer(), 100);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        grace(event.getPlayer(), 100);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onGlide(EntityToggleGlideEvent event) {
        if (event.getEntity() instanceof Player player) {
            grace(player, 100);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRiptide(PlayerRiptideEvent event) {
        grace(event.getPlayer(), 100);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onVehicleExit(VehicleExitEvent event) {
        if (event.getExited() instanceof Player player) {
            grace(player, 60);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        gracePlayersNear(event.getBlock().getLocation());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        gracePlayersNear(event.getBlock().getLocation());
    }

    /**
     * Region thread of the piston. A player within 10 blocks is owned by the same thread; players this thread does
     * not own are skipped before their location is read (Folia rule 3).
     */
    private void gracePlayersNear(Location piston) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!Bukkit.isOwnedByCurrentRegion(player) || player.getWorld() != piston.getWorld()) {
                continue;
            }
            if (player.getLocation().distanceSquared(piston) <= 100.0) {
                grace(player, 40);
            }
        }
    }
}
