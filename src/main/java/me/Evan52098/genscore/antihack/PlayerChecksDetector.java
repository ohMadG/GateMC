package me.Evan52098.genscore.antihack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;

final class PlayerChecksDetector implements Listener {
    private static final double INTERACT_RANGE = 7.0;
    private static final List<String> CHEAT_CLIENTS = List.of("wurst", "meteor-client", "meteorclient", "liquidbounce", "aristois", "rusherhack", "thunderhack", "bleachhack",
            "salhack", "huzuni", "futureclient", "vapeclient", "impactclient", "fdpclient", "nodus", "novoline");
    private static final int RIGHT_CLICK_CPS_WINDOW_TICKS = 20;
    private static final int RIGHT_CLICK_CPS_LIMIT = 30;
    private static final int RIGHT_CLICK_PATTERN_SAMPLE = 40;
    private static final int RIGHT_CLICK_PATTERN_SAME = 38;
    private static final int RIGHT_CLICK_PATTERN_MAX_GAP = 6;
    private static final int RIGHT_CLICK_VARIANCE_SAMPLE = 60;
    private static final double RIGHT_CLICK_VARIANCE_LIMIT = 0.3;

    private final AntiHackService service;
    private final Map<UUID, State> states = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class State {
        int digTick = -1000;
        long digBlock;
        final Deque<Integer> places = new ArrayDeque<>();
        final Deque<Integer> towers = new ArrayDeque<>();
        final Deque<Boolean> scaffold = new ArrayDeque<>();
        int lastConsume = -1000;
        int bowStart = -1000;
        int groundSpoof;
        int sneakSprint;
        final Deque<Integer> invClicks = new ArrayDeque<>();
        final Deque<Integer> interacts = new ArrayDeque<>();
        int lastPing = -1;
        final Deque<Integer> pingSwings = new ArrayDeque<>();
        int lastRightClick = -1000;
        final Deque<Integer> rightClicks = new ArrayDeque<>();
        final Deque<Integer> rightClickGaps = new ArrayDeque<>();
        final Deque<Integer> rightClickLongGaps = new ArrayDeque<>();
    }

    PlayerChecksDetector(AntiHackService service) {
        this.service = service;
        // Folia: the ping sample runs once a second on each player's own thread (AntiHackService's per-player timer).
        service.everySecond(this::samplePing);
    }

    private State state(Player player) {
        return this.states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDigStart(BlockDamageEvent event) {
        State s = this.state(event.getPlayer());
        s.digTick = this.service.tick(event.getPlayer());
        s.digBlock = event.getBlock().getBlockKey();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State s = this.state(player);
        Block block = event.getBlock();
        boolean cancel = false;
        if (player.getEyeLocation().distance(block.getLocation().add(0.5, 0.5, 0.5)) > INTERACT_RANGE) {
            this.service.flag(player, track, AntiHackCheck.INVALID);
            cancel = true;
        }
        if (s.digBlock == block.getBlockKey()) {
            float speed = block.getBreakSpeed(player);
            if (speed > 0.0f && speed < 1.0f) {
                double minTicks = Math.ceil(1.0 / speed);
                int elapsed = track.tick - s.digTick;
                if (elapsed < minTicks * 0.5 - 1) {
                    this.service.flag(player, track, AntiHackCheck.FAST_BREAK);
                    cancel = true;
                }
            }
        }
        if (cancel && this.service.isSetback()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State s = this.state(player);
        int now = track.tick;
        boolean cancel = false;

        s.places.addLast(now);
        prune(s.places, now, 20);
        if (s.places.size() > 25) {
            this.service.flag(player, track, AntiHackCheck.FAST_PLACE);
            s.places.clear();
            cancel = true;
        }

        Block placed = event.getBlockPlaced();
        boolean underFeet = Math.abs(placed.getX() + 0.5 - player.getX()) < 0.8 && Math.abs(placed.getZ() + 0.5 - player.getZ()) < 0.8
                && placed.getY() <= player.getY() && placed.getY() >= player.getY() - 1.5;
        if (underFeet && (!player.isOnGround() || player.getVelocity().getY() > 0.0)) {
            s.towers.addLast(now);
            prune(s.towers, now, 20);
            if (s.towers.size() > 3) {
                this.service.flag(player, track, AntiHackCheck.TOWER);
                s.towers.clear();
                cancel = true;
            }
        }

        RayTraceResult ray;
        try {
            ray = player.rayTraceBlocks(6.0);
        } catch (RuntimeException notOwned) {
            ray = null; // Folia: the ray reached blocks another region owns; skip this sample rather than guess
            if (cancel && this.service.isSetback()) {
                event.setCancelled(true);
            }
            return;
        }
        boolean on = ray != null && ray.getHitBlock() != null && ray.getHitBlock().equals(event.getBlockAgainst());
        s.scaffold.addLast(on);
        while (s.scaffold.size() > 8) {
            s.scaffold.pollFirst();
        }
        if (s.scaffold.size() >= 8) {
            int off = 0;
            for (boolean value : s.scaffold) {
                if (!value) {
                    off++;
                }
            }
            if (off >= 5) {
                this.service.flag(player, track, AntiHackCheck.SCAFFOLD);
                s.scaffold.clear();
                cancel = true;
            }
        }
        if (cancel && this.service.isSetback()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        State s = this.state(player);
        int now = this.service.tick(player);
        if (event.getItem() != null && event.getItem().getType() == Material.BOW && (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK)) {
            s.bowStart = now;
        }
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        s.interacts.addLast(now);
        prune(s.interacts, now, 20);
        if (s.interacts.size() >= 45) {
            this.service.flag(player, track, AntiHackCheck.BAD_PACKETS);
            s.interacts.clear();
        }
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && player.getEyeLocation().distance(event.getClickedBlock().getLocation().add(0.5, 0.5, 0.5)) > INTERACT_RANGE) {
            this.service.flag(player, track, AntiHackCheck.INVALID);
            if (this.service.isSetback()) {
                event.setCancelled(true);
            }
        }
        if (event.getAction() == Action.RIGHT_CLICK_AIR || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            this.rightClick(player, s, track, now);
        }
    }

    private void rightClick(Player player, State s, PlayerTrack track, int now) {
        if (s.lastRightClick >= 0 && now - s.lastRightClick > RIGHT_CLICK_PATTERN_MAX_GAP) {
            s.rightClickGaps.clear();
            s.rightClickLongGaps.clear();
        }
        if (s.lastRightClick >= 0 && now - s.lastRightClick <= RIGHT_CLICK_PATTERN_MAX_GAP) {
            s.rightClickLongGaps.addLast(now - s.lastRightClick);
            while (s.rightClickLongGaps.size() > RIGHT_CLICK_VARIANCE_SAMPLE) {
                s.rightClickLongGaps.pollFirst();
            }
            if (s.rightClickLongGaps.size() >= RIGHT_CLICK_VARIANCE_SAMPLE) {
                double mean = 0.0;
                for (int gap : s.rightClickLongGaps) {
                    mean += gap;
                }
                mean /= s.rightClickLongGaps.size();
                double sum = 0.0;
                for (int gap : s.rightClickLongGaps) {
                    sum += (gap - mean) * (gap - mean);
                }
                double deviation = Math.sqrt(sum / s.rightClickLongGaps.size());
                if (mean <= 5.0 && deviation < RIGHT_CLICK_VARIANCE_LIMIT) {
                    this.service.flag(player, track, AntiHackCheck.INTERACT_AUTO_CLICKER_VARIANCE);
                    s.rightClickLongGaps.clear();
                }
            }
        }
        if (s.lastRightClick >= 0 && now - s.lastRightClick <= RIGHT_CLICK_PATTERN_MAX_GAP) {
            s.rightClickGaps.addLast(now - s.lastRightClick);
            while (s.rightClickGaps.size() > RIGHT_CLICK_PATTERN_SAMPLE) {
                s.rightClickGaps.pollFirst();
            }
        }
        s.lastRightClick = now;
        s.rightClicks.addLast(now);
        prune(s.rightClicks, now, RIGHT_CLICK_CPS_WINDOW_TICKS);
        if (s.rightClicks.size() >= RIGHT_CLICK_CPS_LIMIT) {
            this.service.flag(player, track, AntiHackCheck.INTERACT_AUTO_CLICKER_CPS);
            s.rightClicks.clear();
            return;
        }
        if (s.rightClickGaps.size() >= RIGHT_CLICK_PATTERN_SAMPLE) {
            Map<Integer, Integer> counts = new HashMap<>();
            int most = 0;
            for (int gap : s.rightClickGaps) {
                most = Math.max(most, counts.merge(gap, 1, Integer::sum));
            }
            if (most >= RIGHT_CLICK_PATTERN_SAME) {
                this.service.flag(player, track, AntiHackCheck.INTERACT_AUTO_CLICKER_PATTERN);
                s.rightClickGaps.clear();
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        Player player = event.getPlayer();
        State s = this.state(player);
        int now = this.service.tick(player);
        int gap = now - s.lastConsume;
        s.lastConsume = now;
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        int least = event.getItem().getType() == Material.DRIED_KELP ? 12 : 26;
        if (gap < least) {
            this.service.flag(player, track, AntiHackCheck.FAST_USE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onShoot(EntityShootBowEvent event) {
        if (!(event.getEntity() instanceof Player player) || event.getBow() == null || event.getBow().getType() != Material.BOW || !this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State s = this.state(player);
        int held = track.tick - s.bowStart;
        if (held < 0 || held > 200) {
            return;
        }
        double f = (held + 3) / 20.0;
        double expected = Math.min(1.0, (f * f + f * 2.0) / 3.0);
        if (event.getForce() > expected + 0.25) {
            this.service.flag(player, track, AntiHackCheck.FAST_USE);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!this.service.isEnabled() || event.getTo() == null || event.getFrom().getWorld() != event.getTo().getWorld()) {
            return;
        }
        Player player = event.getPlayer();
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State s = this.state(player);
        Location to = event.getTo();
        double horizontal = Math.hypot(to.getX() - event.getFrom().getX(), to.getZ() - event.getFrom().getZ());

        if (player.isSneaking() && player.isSprinting() && horizontal > 0.1) {
            if (++s.sneakSprint >= 10) {
                s.sneakSprint = 0;
                this.service.flag(player, track, AntiHackCheck.INVALID);
            }
        } else {
            s.sneakSprint = 0;
        }

        if (player.isOnGround() && !player.hasPotionEffect(PotionEffectType.LEVITATION) && !player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            int terrain = Terrain.classify(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight());
            if ((terrain & Terrain.SUPPORT) == 0 && (terrain & Terrain.UNLOADED) == 0) {
                if (++s.groundSpoof >= 4) {
                    s.groundSpoof = 0;
                    if (!Terrain.standableEntityNear(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight())) {
                        this.service.flag(player, track, AntiHackCheck.GROUND_SPOOF);
                    }
                }
            } else {
                s.groundSpoof = 0;
            }
        } else {
            s.groundSpoof = 0;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player) || !this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State s = this.state(player);
        int now = track.tick;
        s.invClicks.addLast(now);
        prune(s.invClicks, now, 20);
        if (s.invClicks.size() >= 30) {
            this.service.flag(player, track, AntiHackCheck.INVENTORY);
            s.invClicks.clear();
            return;
        }
        if (track.historyCount >= PlayerTrack.HISTORY && !player.isInWater() && !player.isInsideVehicle()) {
            int newest = (track.historyHead - 1 + PlayerTrack.HISTORY) % PlayerTrack.HISTORY;
            int oldest = track.historyHead % PlayerTrack.HISTORY;
            double moved = Math.hypot(track.historyX[newest] - track.historyX[oldest], track.historyZ[newest] - track.historyZ[oldest]);
            Material below = Terrain.typeAt(player.getLocation().subtract(0, 0.2, 0));
            boolean slippery = below.name().contains("ICE") || below == Material.SOUL_SAND || below == Material.SOUL_SOIL || below == Material.SLIME_BLOCK;
            if (moved > 2.4 && !slippery) {
                this.service.flag(player, track, AntiHackCheck.INVENTORY);
            }
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBook(PlayerEditBookEvent event) {
        BookMeta meta = event.getNewBookMeta();
        boolean tooBig = meta.getPageCount() > 100;
        for (int i = 1; i <= Math.min(meta.getPageCount(), 100) && !tooBig; i++) {
            tooBig = meta.getPage(i).length() > 2048;
        }
        if (tooBig) {
            event.setCancelled(true);
            Player player = event.getPlayer();
            this.service.flag(player, this.service.track(player), AntiHackCheck.CRASH);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSign(SignChangeEvent event) {
        for (String line : event.getLines()) {
            if (line != null && line.length() > 100) {
                event.setCancelled(true);
                Player player = event.getPlayer();
                this.service.flag(player, this.service.track(player), AntiHackCheck.CRASH);
                return;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (long delay : new long[] {60L, 220L}) {
            me.Evan52098.genscore.scheduler.Tasks.entity.later(player, () -> this.checkClient(player), delay);
        }
    }

    private void checkClient(Player player) {
        if (!player.isOnline() || !this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheckAnyPing(player, track)) {
            return;
        }
        String brand = player.getClientBrandName();
        Set<String> channels = player.getListeningPluginChannels();
        String haystack = ((brand == null ? "" : brand) + " " + String.join(" ", channels)).toLowerCase();
        for (String name : CHEAT_CLIENTS) {
            if (haystack.contains(name)) {
                this.service.flag(player, track, AntiHackCheck.HACKED_CLIENT);
                return;
            }
        }
    }

    /** Once a second, on the player's own thread. */
    private void samplePing(Player player, PlayerTrack track) {
        int now = track.tick;
        State s = this.state(player);
        int ping = player.getPing();
        int last = s.lastPing;
        s.lastPing = ping;
        if (last < 0 || Math.abs(ping - last) <= 400) {
            return;
        }
        if (!this.service.isEnabled() || !this.service.shouldCheckAnyPing(player, track)) {
            return;
        }
        s.pingSwings.addLast(now);
        prune(s.pingSwings, now, 600);
        if (s.pingSwings.size() >= 3) {
            this.service.flag(player, track, AntiHackCheck.PING_SPOOF);
            s.pingSwings.clear();
        }
    }

    private static void prune(Deque<Integer> ticks, int now, int window) {
        while (!ticks.isEmpty() && now - ticks.peekFirst() > window) {
            ticks.pollFirst();
        }
    }
}
