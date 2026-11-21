package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.EntityEffect;
import org.bukkit.Particle;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

import static me.Evan52098.genscore.powers.PowerRuntime.clamp;

final class PowerCombat implements Listener {

    private final PowersModule m;

    PowerCombat(PowersModule m) {
        this.m = m;
    }

    private static boolean unstoppable(EntityDamageEvent.DamageCause cause) {
        return cause == EntityDamageEvent.DamageCause.VOID
                || cause == EntityDamageEvent.DamageCause.KILL
                || cause == EntityDamageEvent.DamageCause.WORLD_BORDER
                || cause == EntityDamageEvent.DamageCause.SUICIDE;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShielded(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || unstoppable(event.getCause())) {
            return;
        }
        PowerRuntime.Live l = m.runtime().live(p.getUniqueId());
        if (l == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (l.aegisUntil > now) {
            event.setCancelled(true);
            return;
        }
        if (event.getCause() == EntityDamageEvent.DamageCause.FALL && l.noFallUntil > now) {
            event.setCancelled(true);
            l.noFallUntil = 0;
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onHit(EntityDamageByEntityEvent event) {
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker == event.getEntity()) {
            return;
        }
        UUID a = attacker.getUniqueId();
        PowerRuntime rt = m.runtime();
        double multiplier = 1;

        Long frozen = rt.frozenUntil.get(a);
        if (frozen != null && frozen > System.currentTimeMillis()) {
            PowerDef ts = m.settings().power("time_stop");
            multiplier *= clamp(ts == null ? 20 : ts.num("frozen-damage-percent", 20), 0, 100) / 100.0;
        }

        PowerRuntime.Mark mark = rt.marks.get(event.getEntity().getUniqueId());
        if (mark != null) {
            if (mark.until() < System.currentTimeMillis()) {
                rt.marks.remove(event.getEntity().getUniqueId(), mark);
            } else if (mark.caster().equals(a)) {
                multiplier *= 1 + mark.bonus();
            }
        }

        if (event.getDamager() == attacker && Tasks.owns(attacker)) {
            PowerDef def = rt.usable(attacker, "berserker");
            if (def != null) {
                double max = PowerRuntime.maxHealth(attacker);
                double missing = max <= 0 ? 0 : clamp(1 - attacker.getHealth() / max, 0, 1);
                multiplier *= 1 + clamp(def.num("max-bonus-percent", 60), 0, 300) / 100.0 * missing;
            }
        }

        if (multiplier != 1) {
            event.setDamage(Math.max(0, event.getDamage() * multiplier));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLethal(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p) || unstoppable(event.getCause())) {
            return;
        }
        if (p.getHealth() - event.getFinalDamage() > 0) {
            return;
        }
        PowerRuntime rt = m.runtime();
        PowerDef def = rt.usable(p, "immortal");
        if (def == null || !rt.cooldowns.tryStart(p.getUniqueId(), Abilities.key(def), def.cooldownMs())) {
            return;
        }
        event.setDamage(0);
        double max = PowerRuntime.maxHealth(p);
        p.setHealth(Math.min(max, clamp(def.num("health", 8), 1, max)));
        int ticks = def.ticks("regen-seconds", 5);
        p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 1, false, true, true));
        p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION, ticks, 0, false, true, true));
        p.playEffect(EntityEffect.PROTECTED_FROM_DEATH);
        p.sendMessage(Msg.good("Immortal saved you!"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLowHealth(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player p)) {
            return;
        }
        PowerRuntime rt = m.runtime();
        PowerDef def = rt.usable(p, "second_wind");
        if (def == null) {
            return;
        }
        double threshold = clamp(def.num("health", 6), 1, 40);
        double after = p.getHealth() - event.getFinalDamage();
        if (after <= 0 || after > threshold || p.getHealth() <= threshold) {
            return;
        }
        if (!rt.cooldowns.tryStart(p.getUniqueId(), Abilities.key(def), def.cooldownMs())) {
            return;
        }
        int ticks = def.ticks("seconds", 5);
        Tasks.entity.later(p, () -> {
            if (p.isDead()) {
                return;
            }
            p.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, ticks, 2, false, true, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, ticks, 1, false, true, true));
            p.getWorld().spawnParticle(Particle.HAPPY_VILLAGER, p.getLocation().add(0, 1, 0), 15, 0.4, 0.6, 0.4, 0);
            p.sendActionBar(Msg.good("Second Wind!"));
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSiphon(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) {
            return;
        }
        Player attacker = attacker(event.getDamager());
        if (attacker == null || attacker == event.getEntity()) {
            return;
        }
        double dealt = event.getFinalDamage();
        if (dealt <= 0) {
            return;
        }
        Tasks.onEntity(attacker, () -> {
            PowerDef def = m.runtime().usable(attacker, "soul_siphon");
            if (def == null || attacker.isDead()) {
                return;
            }
            double heal = Math.min(clamp(def.num("max-heal", 6), 0, 40), dealt * clamp(def.num("percent", 25), 0, 100) / 100.0);
            attacker.setHealth(Math.min(PowerRuntime.maxHealth(attacker), attacker.getHealth() + heal));
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onKill(PlayerDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null || killer == event.getEntity()) {
            return;
        }
        Tasks.onEntity(killer, () -> {
            PowerDef def = m.runtime().usable(killer, "vampire");
            if (def == null || killer.isDead()) {
                return;
            }
            killer.setHealth(Math.min(PowerRuntime.maxHealth(killer), killer.getHealth() + clamp(def.num("heal", 6), 0, 40)));
            killer.getWorld().spawnParticle(Particle.DAMAGE_INDICATOR, killer.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0);
        });
    }

    private static @Nullable Player attacker(Entity damager) {
        if (damager instanceof Player p) {
            return p;
        }
        if (damager instanceof Projectile proj && proj.getShooter() instanceof Player p) {
            return p;
        }
        return null;
    }
}
