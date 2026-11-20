package me.Evan52098.genscore.antihack;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

final class MovementDetector implements Listener {
    private static final int HOVER_STEPS = 10;
    private static final int HOVER_STEPS_STRICT = 8;
    private static final int SPEED_WINDOW = 15;
    private static final double BASE_JUMP_HEIGHT = 1.25;
    private static final double WALK_PER_TICK_PER_ATTRIBUTE = 4.317 / 20.0 / 0.1;
    private static final double SPRINT_JUMP_FACTOR = 1.27;

    private final AntiHackService service;

    MovementDetector(AntiHackService service) {
        this.service = service;
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
        if (from.getWorld() != to.getWorld()) {
            track.resetMovement();
            return;
        }
        double dx = to.getX() - from.getX();
        double dy = to.getY() - from.getY();
        double dz = to.getZ() - from.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        if (horizontal < 1.0E-6 && Math.abs(dy) < 1.0E-6) {
            return;
        }
        if (!this.service.shouldCheck(player, track)) {
            track.resetMovement();
            return;
        }

        int terrain = Terrain.classify(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight());
        int tick = track.tick;
        if ((terrain & Terrain.BOUNCY) != 0) {
            this.service.grace(player, 80);
            return;
        }
        if ((terrain & Terrain.FAST_SURFACE) != 0) {
            track.fastSurfaceUntilTick = tick + 40;
        }

        if ((terrain & Terrain.SUPPORT) != 0) {
            track.airY = to.getY();
            track.hover = 0;
            if ((terrain & Terrain.UNLOADED) == 0) {
                track.lastSafe = to.clone();
            }
        } else {
            this.checkFly(player, track, to, dy);
        }

        if ((terrain & Terrain.MEDIUM) == 0 && tick >= track.fastSurfaceUntilTick) {
            this.checkSpeed(player, track, horizontal);
        } else {
            track.speedSamples = 0;
            track.speedDistance = 0.0;
        }
    }

    private void checkFly(Player player, PlayerTrack track, Location to, double dy) {
        if (player.hasPotionEffect(PotionEffectType.LEVITATION) || player.hasPotionEffect(PotionEffectType.SLOW_FALLING)) {
            track.hover = 0;
            track.airY = to.getY();
            return;
        }
        if (Double.isNaN(track.airY)) {
            track.airY = to.getY();
        }
        boolean strict = this.service.isStrict();

        if (Math.abs(dy) < 0.1) {
            track.hover++;
        } else {
            track.hover = 0;
        }
        int hoverLimit = strict ? HOVER_STEPS_STRICT : HOVER_STEPS;
        if (track.hover > hoverLimit) {
            track.hover = hoverLimit - 3;
            if (!Terrain.standableEntityNear(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight())) {
                this.service.flag(player, track, AntiHackCheck.FLY_HOVER);
            }
        }

        double allowance = maxJumpHeight(player) + (strict ? 0.8 : 1.1);
        if (dy > 0.0 && to.getY() - track.airY > allowance) {
            track.airY = to.getY() - allowance + 0.4;
            if (!Terrain.standableEntityNear(to.getWorld(), to.getX(), to.getY(), to.getZ(), player.getWidth(), player.getHeight())) {
                this.service.flag(player, track, AntiHackCheck.FLY_RISE);
            }
        }
    }

    private static double maxJumpHeight(Player player) {
        PotionEffect boost = player.getPotionEffect(PotionEffectType.JUMP_BOOST);
        if (boost == null) {
            return BASE_JUMP_HEIGHT;
        }
        double launch = 0.42 + 0.1 * (boost.getAmplifier() + 1);
        return BASE_JUMP_HEIGHT * (launch / 0.42) * (launch / 0.42);
    }

    private void checkSpeed(Player player, PlayerTrack track, double horizontal) {
        if (horizontal <= 0.0) {
            return;
        }
        track.speedDistance += horizontal;
        track.speedSamples++;
        if (track.speedSamples < SPEED_WINDOW) {
            return;
        }
        double average = track.speedDistance / track.speedSamples;
        track.speedDistance = 0.0;
        track.speedSamples = 0;
        double margin = this.service.isStrict() ? 1.06 : 1.18;
        double limit = movementAttribute(player) * WALK_PER_TICK_PER_ATTRIBUTE * SPRINT_JUMP_FACTOR * margin;
        if (average > limit) {
            this.service.flag(player, track, AntiHackCheck.SPEED);
        }
    }

    private static double movementAttribute(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (attribute != null) {
            return attribute.getValue();
        }
        PotionEffect speed = player.getPotionEffect(PotionEffectType.SPEED);
        return 0.13 * (1.0 + 0.2 * (speed == null ? 0 : speed.getAmplifier() + 1));
    }
}
