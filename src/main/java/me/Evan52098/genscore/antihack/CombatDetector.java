package me.Evan52098.genscore.antihack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Vector;

final class CombatDetector implements Listener {
    private static final int CPS_WINDOW_TICKS = 20;
    private static final int CPS_LIMIT = 30;
    private static final int PATTERN_SAMPLE = 40;
    private static final int PATTERN_SAME = 38;
    private static final int PATTERN_MAX_GAP = 6;
    private static final int AURA_WINDOW = 3;
    private static final int AURA_TARGETS = 3;
    private static final int SWING_WINDOW = 60;
    private static final int SWING_MIN_HITS = 10;
    private static final double ANGLE_LIMIT = 80.0;
    private static final double ANGLE_MIN_DISTANCE = 2.0;
    private static final double HITBOX_MARGIN = 0.35;
    private static final int HITBOX_RUN = 6;
    private static final int HITBOX_MISSES = 5;
    private static final double SNAP_DEGREES = 100.0;
    private static final int SNAP_WINDOW = 200;
    private static final int SNAP_LIMIT = 3;
    private static final int USING_TICKS = 5;
    private static final int VARIANCE_SAMPLE = 60;
    private static final double VARIANCE_LIMIT = 0.3;
    private static final int ROBOTIC_SAMPLE = 30;
    private static final double ROBOTIC_JERK = 0.02;

    private final AntiHackService service;
    private final Map<UUID, State> states = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class State {
        final Deque<Integer> clicks = new ArrayDeque<>();
        final Deque<Integer> gaps = new ArrayDeque<>();
        int lastClick = -1000;
        final Deque<Integer> swings = new ArrayDeque<>();
        final Deque<Integer> attacks = new ArrayDeque<>();
        final Deque<int[]> targets = new ArrayDeque<>();
        final Deque<Boolean> aimed = new ArrayDeque<>();
        final Deque<Integer> longGaps = new ArrayDeque<>();
        final Deque<Double> yawDeltas = new ArrayDeque<>();
        int lastAttack = -1000;
        int snapTick = -1000;
        float snapEndYaw;
        final Deque<Integer> snaps = new ArrayDeque<>();
        float lastYaw = Float.NaN;
    }

    CombatDetector(AntiHackService service) {
        this.service = service;
    }

    private State state(Player player) {
        return this.states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.states.remove(event.getPlayer().getUniqueId());
    }


    @EventHandler(priority = EventPriority.MONITOR)
    public void onSwing(PlayerAnimationEvent event) {
        State state = this.state(event.getPlayer());
        int now = this.service.tick(event.getPlayer());
        state.swings.addLast(now);
        prune(state.swings, now, SWING_WINDOW);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onAirClick(PlayerInteractEvent event) {
        if (event.getAction() == Action.LEFT_CLICK_AIR) {
            this.click(event.getPlayer());
        }
    }

    private void click(Player player) {
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.shouldCheck(player, track)) {
            return;
        }
        State state = this.state(player);
        int now = this.service.tick(player);
        if (state.lastClick >= 0 && now - state.lastClick > PATTERN_MAX_GAP) {
            state.gaps.clear();
            state.longGaps.clear();
        }
        if (state.lastClick >= 0 && now - state.lastClick <= PATTERN_MAX_GAP) {
            state.longGaps.addLast(now - state.lastClick);
            while (state.longGaps.size() > VARIANCE_SAMPLE) {
                state.longGaps.pollFirst();
            }
            if (state.longGaps.size() >= VARIANCE_SAMPLE) {
                double mean = 0.0;
                for (int gap : state.longGaps) {
                    mean += gap;
                }
                mean /= state.longGaps.size();
                double sum = 0.0;
                for (int gap : state.longGaps) {
                    sum += (gap - mean) * (gap - mean);
                }
                double deviation = Math.sqrt(sum / state.longGaps.size());
                if (mean <= 5.0 && deviation < VARIANCE_LIMIT) {
                    this.service.flag(player, track, AntiHackCheck.AUTO_CLICKER_VARIANCE);
                    state.longGaps.clear();
                }
            }
        }
        if (state.lastClick >= 0 && now - state.lastClick <= PATTERN_MAX_GAP) {
            state.gaps.addLast(now - state.lastClick);
            while (state.gaps.size() > PATTERN_SAMPLE) {
                state.gaps.pollFirst();
            }
        }
        state.lastClick = now;
        state.clicks.addLast(now);
        prune(state.clicks, now, CPS_WINDOW_TICKS);
        if (state.clicks.size() >= CPS_LIMIT) {
            this.service.flag(player, track, AntiHackCheck.AUTO_CLICKER_CPS);
            state.clicks.clear();
            return;
        }
        if (state.gaps.size() >= PATTERN_SAMPLE) {
            Map<Integer, Integer> counts = new HashMap<>();
            int most = 0;
            for (int gap : state.gaps) {
                most = Math.max(most, counts.merge(gap, 1, Integer::sum));
            }
            if (most >= PATTERN_SAME) {
                this.service.flag(player, track, AntiHackCheck.AUTO_CLICKER_PATTERN);
                state.gaps.clear();
            }
        }
    }


    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        if (event.getCause() != EntityDamageEvent.DamageCause.ENTITY_ATTACK || !(event.getDamager() instanceof Player attacker) || !(event.getEntity() instanceof LivingEntity target)) {
            return;
        }
        if (!this.service.isEnabled()) {
            return;
        }
        PlayerTrack track = this.service.track(attacker);
        if (!this.service.shouldCheck(attacker, track) || attacker.getWorld() != target.getWorld()) {
            return;
        }
        State state = this.state(attacker);
        int now = this.service.tick(attacker);
        state.lastAttack = now;
        this.click(attacker);
        boolean cancel = false;

        if (attacker.isHandRaised() && attacker.getActiveItemUsedTime() >= USING_TICKS) {
            this.service.flag(attacker, track, AntiHackCheck.AUTO_BLOCK);
            cancel = true;
        }

        state.targets.addLast(new int[] {now, target.getEntityId()});
        while (!state.targets.isEmpty() && now - state.targets.peekFirst()[0] > AURA_WINDOW) {
            state.targets.pollFirst();
        }
        Set<Integer> distinct = new HashSet<>();
        for (int[] hit : state.targets) {
            distinct.add(hit[1]);
        }
        if (distinct.size() >= AURA_TARGETS) {
            this.service.flag(attacker, track, AntiHackCheck.KILL_AURA_MULTI);
            state.targets.clear();
            cancel = true;
        }

        state.attacks.addLast(now);
        prune(state.attacks, now, SWING_WINDOW);
        prune(state.swings, now, SWING_WINDOW);
        if (state.attacks.size() >= SWING_MIN_HITS && state.swings.size() * 2 <= state.attacks.size()) {
            this.service.flag(attacker, track, AntiHackCheck.KILL_AURA_NO_SWING);
            state.attacks.clear();
            state.swings.clear();
        }

        Location eye = attacker.getEyeLocation();
        BoundingBox box = target.getBoundingBox();
        Vector toTarget = box.getCenter().subtract(eye.toVector());
        double distance = toTarget.length();

        if (distance > ANGLE_MIN_DISTANCE) {
            double angle = Math.toDegrees(eye.getDirection().angle(toTarget));
            if (angle > ANGLE_LIMIT) {
                this.service.flag(attacker, track, AntiHackCheck.KILL_AURA_ANGLE);
                cancel = true;
            }
        }

        if (distance > 1.5) {
            boolean on = box.clone().expand(HITBOX_MARGIN).rayTrace(eye.toVector(), eye.getDirection(), distance + 2.0) != null;
            state.aimed.addLast(on);
            while (state.aimed.size() > HITBOX_RUN) {
                state.aimed.pollFirst();
            }
            if (state.aimed.size() >= HITBOX_RUN) {
                int misses = 0;
                for (boolean hit : state.aimed) {
                    if (!hit) {
                        misses++;
                    }
                }
                if (misses >= HITBOX_MISSES) {
                    this.service.flag(attacker, track, AntiHackCheck.HITBOX);
                    state.aimed.clear();
                }
            }
        }

        if (event.isCritical() && this.stoodStill(attacker, track)) {
            this.service.flag(attacker, track, AntiHackCheck.CRITICALS);
        }

        if (now - state.snapTick <= 2) {
            float yawToTarget = (float) Math.toDegrees(Math.atan2(-toTarget.getX(), toTarget.getZ()));
            if (Math.abs(wrap(state.snapEndYaw - yawToTarget)) <= 10.0f) {
                state.snaps.addLast(now);
                prune(state.snaps, now, SNAP_WINDOW);
                if (state.snaps.size() >= SNAP_LIMIT) {
                    this.service.flag(attacker, track, AntiHackCheck.AIM_SNAP);
                    state.snaps.clear();
                }
            }
        }

        if (cancel && this.service.isSetback()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTurn(PlayerMoveEvent event) {
        if (!event.hasChangedOrientation()) {
            return;
        }
        Player player = event.getPlayer();
        State state = this.state(player);
        float yaw = event.getTo().getYaw();
        int now = this.service.tick(player);
        if (!Float.isNaN(state.lastYaw) && Math.abs(wrap(yaw - state.lastYaw)) > SNAP_DEGREES) {
            state.snapTick = now;
            state.snapEndYaw = yaw;
        }
        if (!Float.isNaN(state.lastYaw) && now - state.lastAttack <= 40) {
            double delta = wrap(yaw - state.lastYaw);
            if (Math.abs(delta) > 1.0) {
                state.yawDeltas.addLast(delta);
                while (state.yawDeltas.size() > ROBOTIC_SAMPLE) {
                    state.yawDeltas.pollFirst();
                }
                if (state.yawDeltas.size() >= ROBOTIC_SAMPLE && this.service.shouldCheck(player, this.service.track(player))) {
                    double speed = 0.0;
                    double jerk = 0.0;
                    Double previous = null;
                    for (double d : state.yawDeltas) {
                        speed += Math.abs(d);
                        if (previous != null) {
                            jerk += Math.abs(d - previous);
                        }
                        previous = d;
                    }
                    speed /= state.yawDeltas.size();
                    jerk /= state.yawDeltas.size() - 1;
                    if (speed > 2.0 && jerk < ROBOTIC_JERK) {
                        this.service.flag(player, this.service.track(player), AntiHackCheck.AIM_ROBOTIC);
                        state.yawDeltas.clear();
                    }
                }
            }
        } else if (now - state.lastAttack > 40) {
            state.yawDeltas.clear();
        }
        state.lastYaw = yaw;
    }

    private boolean stoodStill(Player player, PlayerTrack track) {
        if (track.historyCount < 8) {
            return false;
        }
        double min = Double.MAX_VALUE;
        double max = -Double.MAX_VALUE;
        for (int i = 0; i < track.historyCount; i++) {
            min = Math.min(min, track.historyY[i]);
            max = Math.max(max, track.historyY[i]);
        }
        if (max - min > 0.12) {
            return false;
        }
        Block below = Terrain.blockAt(player.getLocation().subtract(0, 0.2, 0));
        if (below == null) {
            return false;
        }
        Material type = below.getType();
        return type.isSolid() && !below.isLiquid() && type != Material.LADDER && type != Material.VINE && !player.isClimbing() && !player.isInWater();
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        Player player = event.getPlayer();
        Vector push = event.getVelocity().clone();
        double horizontal = Math.hypot(push.getX(), push.getZ());
        if (horizontal < 0.25 && push.getY() < 0.25) {
            return;
        }
        PlayerTrack track = this.service.track(player);
        if (!this.service.isEnabled() || !this.service.shouldCheck(player, track)) {
            return;
        }
        Location start = player.getLocation().clone();
        int startTick = track.tick;
        me.Evan52098.genscore.scheduler.Tasks.entity.later(player, () -> {
            if (!player.isOnline() || !this.service.shouldCheck(player, track) || track.ignoreUntilTick > startTick + 6) {
                return;
            }
            Location end = player.getLocation();
            if (end.getWorld() != start.getWorld() || player.isInWater() || player.isClimbing() || player.isInsideVehicle()) {
                return;
            }
            if (inWeb(start) || inWeb(end) || boxedIn(start, push)) {
                return;
            }
            double moved = Math.hypot(end.getX() - start.getX(), end.getZ() - start.getZ());
            double rose = end.getY() - start.getY();
            boolean horizontalIgnored = moved < horizontal * 4 * 0.15;
            boolean verticalIgnored = push.getY() < 0.25 || rose < push.getY() * 4 * 0.15;
            if (horizontalIgnored && verticalIgnored) {
                this.service.flag(player, track, AntiHackCheck.VELOCITY);
            }
        }, 6L); // on the player's own thread, 6 of their region's ticks later
    }

    private static boolean inWeb(Location location) {
        Material type = Terrain.typeAt(location);
        return type == Material.COBWEB || type == Material.SWEET_BERRY_BUSH || type == Material.POWDER_SNOW;
    }

    private static boolean boxedIn(Location start, Vector push) {
        Vector flat = new Vector(push.getX(), 0, push.getZ());
        if (flat.lengthSquared() > 1e-6) {
            flat.normalize();
        }
        for (int step = 1; step <= 2; step++) {
            Location ahead = start.clone().add(flat.clone().multiply(step));
            if (Terrain.typeAt(ahead).isSolid() || Terrain.typeAt(ahead.clone().add(0, 1, 0)).isSolid()) {
                return true;
            }
        }
        return Terrain.typeAt(start.clone().add(0, 2, 0)).isSolid();
    }


    private static void prune(Deque<Integer> ticks, int now, int window) {
        while (!ticks.isEmpty() && now - ticks.peekFirst() > window) {
            ticks.pollFirst();
        }
    }

    private static float wrap(float degrees) {
        float d = degrees % 360.0f;
        if (d > 180.0f) {
            d -= 360.0f;
        } else if (d < -180.0f) {
            d += 360.0f;
        }
        return d;
    }
}
