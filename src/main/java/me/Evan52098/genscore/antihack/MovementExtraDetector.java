package me.Evan52098.genscore.antihack;

import com.destroystokyo.paper.event.player.PlayerElytraBoostEvent;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.entity.Boat;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Minecart;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class MovementExtraDetector implements Listener {
    private static final long TICK_NANOS = 50_000_000L;
    private static final long TIMER_CREDIT_FLOOR = -1_500_000_000L;
    private static final long TIMER_LIMIT = 300_000_000L;

    private final AntiHackService service;
    private final Map<UUID, State> states = new java.util.concurrent.ConcurrentHashMap<>();

    private static final class State {
        long lastNano;
        long balance;
        final Deque<Integer> timerStrikes = new ArrayDeque<>();
        boolean prevAir;
        boolean prevGround;
        double prevDy;
        double prevDx;
        double prevDz;
        double prevHorizontal;
        int jesus;
        final Deque<Integer> airJumps = new ArrayDeque<>();
        int using;
        double usingDistance;
        int badFood;
        int badBlind;
        int backwards;
        final Deque<Integer> strafes = new ArrayDeque<>();
        int wallRise;
        int climbSamples;
        double climbRise;
        int lastBoostTick = -1000;
        int glideHover;
        int boatAir;
        int vehicleSamples;
        double vehicleDistance;

        void reset() {
            this.lastNano = 0L;
            this.balance = 0L;
            this.prevAir = false;
            this.prevGround = false;
            this.jesus = 0;
            this.using = 0;
            this.usingDistance = 0.0;
            this.badFood = 0;
            this.badBlind = 0;
            this.backwards = 0;
            this.wallRise = 0;
            this.climbSamples = 0;
            this.climbRise = 0.0;
            this.glideHover = 0;
            this.boatAir = 0;
            this.vehicleSamples = 0;
            this.vehicleDistance = 0.0;
        }
    }

    MovementExtraDetector(AntiHackService service) {
        this.service = service;
    }

    private State state(Player player) {
        return this.states.computeIfAbsent(player.getUniqueId(), id -> new State());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        this.states.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent event) {
        this.state(event.getPlayer()).reset();
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        this.state(event.getPlayer()).reset();
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        this.state(event.getPlayer()).reset();
    }

    @EventHandler
    public void onBoost(PlayerElytraBoostEvent event) {
        this.state(event.getPlayer()).lastBoostTick = this.service.tick(event.getPlayer());
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!this.service.isEnabled()) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        Player player = event.getPlayer();
        PlayerTrack track = this.service.track(player);
        State s = this.state(player);
        if (from.getWorld() != to.getWorld()) {
            s.reset();
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            return;
        }
        int tick = track.tick;

        if (player.isGliding()) {
            if (this.service.shouldCheckGlider(player, track)) {
                this.elytra(player, track, s, horizontal, dy, tick);
            }
            s.prevAir = true;
            s.prevGround = false;
            return;
        }
        if (!this.service.shouldCheck(player, track)) {
            s.reset();
            return;
        }

        this.timer(player, track, s, tick);

        int terrain = Terrain.classify(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight());
        boolean unloaded = (terrain & Terrain.UNLOADED) != 0;
        boolean medium = (terrain & Terrain.MEDIUM) != 0;
        boolean bouncy = (terrain & Terrain.BOUNCY) != 0;
        boolean slippery = (terrain & Terrain.FAST_SURFACE) != 0;
        boolean air = (terrain & Terrain.SUPPORT) == 0;
        boolean ground = solidBelow(to);
        if (unloaded || bouncy) {
            s.prevAir = air;
            s.prevGround = false;
            s.prevDy = dy;
            return;
        }
        boolean levitating = player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.SLOW_FALLING);

        if (!medium) {
            this.jesus(player, track, s, to, horizontal, dy);
            if (!levitating) {
                this.step(player, track, s, dy, medium);
                this.airJump(player, track, s, air, dy, tick);
            }
            if (!slippery) {
                this.strafe(player, track, s, air, dx, dz, horizontal, tick);
                this.noSlow(player, track, s, ground, horizontal);
                this.sprint(player, track, s, to, ground, dx, dz, horizontal);
            }
        } else {
            s.jesus = 0;
            s.using = 0;
            s.usingDistance = 0.0;
        }
        this.climb(player, track, s, to, terrain, ground, dy, levitating);

        s.prevAir = air;
        s.prevGround = ground;
        s.prevDy = dy;
        s.prevDx = dx;
        s.prevDz = dz;
        s.prevHorizontal = horizontal;
    }

    private void timer(Player player, PlayerTrack track, State s, int tick) {
        long now = System.nanoTime();
        if (s.lastNano != 0L) {
            s.balance += TICK_NANOS - (now - s.lastNano);
            if (s.balance < TIMER_CREDIT_FLOOR) {
                s.balance = TIMER_CREDIT_FLOOR;
            }
        }
        s.lastNano = now;
        if (s.balance > TIMER_LIMIT) {
            s.balance = 0L;
            s.timerStrikes.addLast(tick);
            while (!s.timerStrikes.isEmpty() && tick - s.timerStrikes.peekFirst() > 200) {
                s.timerStrikes.pollFirst();
            }
            if (s.timerStrikes.size() >= 3) {
                this.service.flag(player, track, AntiHackCheck.TIMER);
                s.timerStrikes.clear();
            }
        }
    }

    private void jesus(Player player, PlayerTrack track, State s, Location to, double horizontal, double dy) {
        if (horizontal < 0.08 || Math.abs(dy) > 0.06 || player.isSwimming() || player.isInWater() || Terrain.liquidAt(to)
                || !Terrain.liquidAt(to.clone().subtract(0, 0.3, 0)) || supported(to)) {
            s.jesus = 0;
            return;
        }
        s.jesus++;
        if (s.jesus >= 8) {
            s.jesus = 4;
            if (!Terrain.standableEntityNear(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight())) {
                this.service.flag(player, track, AntiHackCheck.JESUS);
            }
        }
    }

    private void step(Player player, PlayerTrack track, State s, double dy, boolean medium) {
        if (!s.prevGround || medium) {
            return;
        }
        PotionEffect boost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        double allowed = 0.42 + 0.1 * (boost == null ? 0 : boost.getAmplifier() + 1) + 0.25;
        if (dy > allowed) {
            this.service.flag(player, track, AntiHackCheck.STEP);
        }
    }

    private void airJump(Player player, PlayerTrack track, State s, boolean air, double dy, int tick) {
        if (air && s.prevAir && s.prevDy < -0.02 && dy > 0.08) {
            s.airJumps.addLast(tick);
            while (!s.airJumps.isEmpty() && tick - s.airJumps.peekFirst() > 20) {
                s.airJumps.pollFirst();
            }
            if (s.airJumps.size() >= 2) {
                this.service.flag(player, track, AntiHackCheck.AIR_JUMP);
                s.airJumps.clear();
            }
        }
    }

    private void noSlow(Player player, PlayerTrack track, State s, boolean ground, double horizontal) {
        if (!(player.isHandRaised() && player.getActiveItemUsedTime() >= 4 && ground && player.getActiveItem().getType() != Material.SHIELD)) {
            s.using = 0;
            s.usingDistance = 0.0;
            return;
        }
        s.using++;
        s.usingDistance += horizontal;
        if (s.using >= 12) {
            double average = s.usingDistance / s.using;
            s.using = 0;
            s.usingDistance = 0.0;
            if (average > movementAttribute(player) * (4.317 / 20.0 / 0.1) * 0.6) {
                this.service.flag(player, track, AntiHackCheck.NO_SLOW);
            }
        }
    }

    private void sprint(Player player, PlayerTrack track, State s, Location to, boolean ground, double dx, double dz, double horizontal) {
        if (!player.isSprinting() || horizontal < 0.15) {
            s.badFood = 0;
            s.badBlind = 0;
            s.backwards = 0;
            return;
        }
        s.badFood = player.getFoodLevel() <= 5 ? s.badFood + 1 : 0;
        s.badBlind = player.hasPotionEffect(PotionEffectType.BLINDNESS) ? s.badBlind + 1 : 0;
        if (s.badFood >= 20 || s.badBlind >= 20) {
            s.badFood = 0;
            s.badBlind = 0;
            this.service.flag(player, track, AntiHackCheck.SPRINT);
            return;
        }
        if (!ground || horizontal < 0.2) {
            s.backwards = 0;
            return;
        }
        double moveYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double diff = Math.abs(wrap(moveYaw - to.getYaw()));
        s.backwards = diff > 100.0 ? s.backwards + 1 : 0;
        if (s.backwards >= 8) {
            s.backwards = 0;
            this.service.flag(player, track, AntiHackCheck.SPRINT);
        }
    }

    private void strafe(Player player, PlayerTrack track, State s, boolean air, double dx, double dz, double horizontal, int tick) {
        if (!(air && s.prevAir && horizontal > 0.2 && s.prevHorizontal > 0.2)) {
            return;
        }
        double dot = (dx * s.prevDx + dz * s.prevDz) / (horizontal * s.prevHorizontal);
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));
        if (angle <= 60.0) {
            return;
        }
        s.strafes.addLast(tick);
        while (!s.strafes.isEmpty() && tick - s.strafes.peekFirst() > 20) {
            s.strafes.pollFirst();
        }
        if (s.strafes.size() >= 3) {
            this.service.flag(player, track, AntiHackCheck.STRAFE);
            s.strafes.clear();
        }
    }

    private void climb(Player player, PlayerTrack track, State s, Location to, int terrain, boolean ground, double dy, boolean levitating) {
        if (player.isClimbing() && dy > 0.0) {
            s.climbRise += dy;
            s.climbSamples++;
            if (s.climbSamples >= 8) {
                double average = s.climbRise / s.climbSamples;
                s.climbRise = 0.0;
                s.climbSamples = 0;
                if (average > 0.2) {
                    this.service.flag(player, track, AntiHackCheck.FAST_CLIMB);
                }
            }
            s.wallRise = 0;
            return;
        }
        s.climbRise = 0.0;
        s.climbSamples = 0;
        boolean wallOnly = (terrain & Terrain.SUPPORT) != 0 && (terrain & Terrain.MEDIUM) == 0 && !ground;
        if (!(dy > 0.05 && wallOnly) || levitating || scaffoldingNear(to)) {
            s.wallRise = 0;
            return;
        }
        PotionEffect boost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        int limit = 10 + 4 * (boost == null ? 0 : boost.getAmplifier() + 1);
        if (++s.wallRise >= limit) {
            s.wallRise = limit / 2;
            this.service.flag(player, track, AntiHackCheck.WALL_CLIMB);
        }
    }

    private void elytra(Player player, PlayerTrack track, State s, double horizontal, double dy, int tick) {
        if (tick - s.lastBoostTick < 100) {
            s.glideHover = 0;
            return;
        }
        if (horizontal > 5.0) {
            this.service.flag(player, track, AntiHackCheck.ELYTRA);
            return;
        }
        if (horizontal < 0.08 && Math.abs(dy) < 0.04) {
            if (++s.glideHover >= 15) {
                s.glideHover = 0;
                this.service.flag(player, track, AntiHackCheck.ELYTRA);
            }
        } else {
            s.glideHover = 0;
        }
    }


    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVehicle(VehicleMoveEvent event) {
        if (!this.service.isEnabled() || !(event.getVehicle() instanceof Boat || event.getVehicle() instanceof Minecart)) {
            return;
        }
        Entity vehicle = event.getVehicle();
        if (vehicle.getPassengers().isEmpty() || !(vehicle.getPassengers().get(0) instanceof Player rider)) {
            return;
        }
        PlayerTrack track = this.service.track(rider);
        if (!this.service.shouldCheckRider(rider, track)) {
            return;
        }
        State s = this.state(rider);
        Location from = event.getFrom();
        Location to = event.getTo();
        double dy = to.getY() - from.getY();
        double horizontal = Math.hypot(to.getX() - from.getX(), to.getZ() - from.getZ());

        if (vehicle instanceof Boat && !vehicle.isOnGround() && !vehicle.isInWater() && !liquidNear(to) && dy > -0.01) {
            if (++s.boatAir >= 10) {
                s.boatAir = 5;
                this.service.flag(rider, track, AntiHackCheck.BOAT_FLY);
            }
        } else {
            s.boatAir = 0;
        }

        s.vehicleDistance += horizontal;
        if (++s.vehicleSamples >= 5) {
            double average = s.vehicleDistance / s.vehicleSamples;
            s.vehicleDistance = 0.0;
            s.vehicleSamples = 0;
            double limit = vehicle instanceof Boat ? 2.3 : 1.2;
            if (average > limit) {
                this.service.flag(rider, track, AntiHackCheck.ENTITY_SPEED);
            }
        }
    }


    private static boolean solidBelow(Location at) {
        for (double ox : new double[] {-0.3, 0.0, 0.3}) {
            for (double oz : new double[] {-0.3, 0.0, 0.3}) {
                Block block = Terrain.blockAt(at.getWorld(), (int) Math.floor(at.getX() + ox), (int) Math.floor(at.getY() - 0.08), (int) Math.floor(at.getZ() + oz));
                if (block != null && !block.isPassable()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean supported(Location at) {
        for (double ox : new double[] {-0.3, 0.3}) {
            for (double oz : new double[] {-0.3, 0.3}) {
                for (double oy : new double[] {-0.05, -0.5}) {
                    Block block = Terrain.blockAt(at.getWorld(), (int) Math.floor(at.getX() + ox), (int) Math.floor(at.getY() + oy), (int) Math.floor(at.getZ() + oz));
                    if (block != null && (!block.isPassable() || block.getType() == Material.LILY_PAD)) {
                        return true;
                    }
                }
            }
        }
        return Terrain.typeAt(at) == Material.LILY_PAD;
    }

    private static boolean scaffoldingNear(Location at) {
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 2; y++) {
                for (int z = -1; z <= 1; z++) {
                    Material type = Terrain.typeAt(at.getWorld(), at.getBlockX() + x, at.getBlockY() + y, at.getBlockZ() + z);
                    if (type == Material.SCAFFOLDING || type == Material.BUBBLE_COLUMN || type == Material.HONEY_BLOCK || type == Material.POWDER_SNOW) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean liquidNear(Location at) {
        for (int y = 0; y >= -2; y--) {
            Block block = Terrain.blockAt(at.getWorld(), at.getBlockX(), at.getBlockY() + y, at.getBlockZ());
            if (block != null && (block.isLiquid() || block.getType() == Material.ICE || block.getType() == Material.PACKED_ICE || block.getType() == Material.BLUE_ICE || block.getType() == Material.FROSTED_ICE)) {
                return true;
            }
        }
        return false;
    }

    private static double movementAttribute(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        return attribute == null ? 0.13 : attribute.getValue();
    }

    private static double wrap(double degrees) {
        double d = degrees % 360.0;
        if (d > 180.0) {
            d -= 360.0;
        } else if (d < -180.0) {
            d += 360.0;
        }
        return d;
    }
}
