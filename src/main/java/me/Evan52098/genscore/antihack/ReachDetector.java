package me.Evan52098.genscore.antihack;

import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.util.BoundingBox;

final class ReachDetector implements Listener {
    private static final double TOLERANCE = 0.8;
    private static final double TOLERANCE_STRICT = 0.65;
    private static final double MOB_EXTRA = 0.5;

    private final AntiHackService service;

    ReachDetector(AntiHackService service) {
        this.service = service;
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
        double allowed = interactionRange(attacker) + (this.service.isStrict() ? TOLERANCE_STRICT : TOLERANCE) + (target instanceof Player ? 0.0 : MOB_EXTRA);
        if (closest(attacker, track, target) <= allowed) {
            return;
        }
        this.service.flag(attacker, track, AntiHackCheck.REACH);
        if (this.service.isSetback() || this.service.enforces(AntiHackCheck.REACH)) {
            event.setCancelled(true);
        }
    }

    private double closest(Player attacker, PlayerTrack attackerTrack, LivingEntity target) {
        double eyeHeight = attacker.getEyeHeight();
        BoundingBox body = target.getBoundingBox();
        PlayerTrack targetTrack = target instanceof Player targetPlayer ? this.service.track(targetPlayer) : null;

        int attackerPoints = Math.max(1, attackerTrack.historyCount);
        int targetPoints = targetTrack == null ? 1 : Math.max(1, targetTrack.historyCount);
        double best = Double.MAX_VALUE;
        for (int i = 0; i < attackerPoints; i++) {
            double ex;
            double ey;
            double ez;
            if (attackerTrack.historyCount == 0) {
                ex = attacker.getX();
                ey = attacker.getY() + eyeHeight;
                ez = attacker.getZ();
            } else {
                ex = attackerTrack.historyX[i];
                ey = attackerTrack.historyY[i] + eyeHeight;
                ez = attackerTrack.historyZ[i];
            }
            for (int j = 0; j < targetPoints; j++) {
                BoundingBox box = body;
                if (targetTrack != null && targetTrack.historyCount > 0) {
                    box = body.clone().shift(targetTrack.historyX[j] - target.getX(), targetTrack.historyY[j] - target.getY(), targetTrack.historyZ[j] - target.getZ());
                }
                best = Math.min(best, distance(ex, ey, ez, box));
            }
        }
        return best;
    }

    private static double distance(double x, double y, double z, BoundingBox box) {
        double dx = Math.max(Math.max(box.getMinX() - x, 0.0), x - box.getMaxX());
        double dy = Math.max(Math.max(box.getMinY() - y, 0.0), y - box.getMaxY());
        double dz = Math.max(Math.max(box.getMinZ() - z, 0.0), z - box.getMaxZ());
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static double interactionRange(Player player) {
        AttributeInstance attribute = player.getAttribute(Attribute.ENTITY_INTERACTION_RANGE);
        return attribute == null ? 3.0 : attribute.getValue();
    }
}
