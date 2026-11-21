package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.damage.DamageSource;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Player;

import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

final class Area {

    private final PowersModule m;

    Area(PowersModule m) {
        this.m = m;
    }

    void players(Player caster, Location origin, double radius, Consumer<Player> effect) {
        UUID casterId = caster.getUniqueId();
        World world = origin.getWorld();
        Location centre = origin.clone();
        double r2 = radius * radius;
        for (Player t : Bukkit.getOnlinePlayers()) {
            if (t.getUniqueId().equals(casterId) || t.getWorld() != world) {
                continue;
            }
            Tasks.entity.run(t, () -> {
                if (!t.isValid() || t.getWorld() != world || t.getLocation().distanceSquared(centre) > r2) {
                    return;
                }
                if (targetable(casterId, t)) {
                    effect.accept(t);
                }
            });
        }
    }

    boolean targetable(UUID caster, Player t) {
        if (t.isDead() || (t.getGameMode() != GameMode.SURVIVAL && t.getGameMode() != GameMode.ADVENTURE)) {
            return false;
        }
        if (Boolean.FALSE.equals(t.getWorld().getGameRuleValue(GameRules.PVP)) || m.settings().worldDisabled(t.getWorld().getName())) {
            return false;
        }
        if (m.pvpProtected(t.getUniqueId())) {
            return false;
        }
        BiPredicate<UUID, UUID> filter = PowersModule.targetFilter();
        return filter == null || filter.test(caster, t.getUniqueId());
    }

    static void hurt(Player caster, Player target, double amount, DamageType type) {
        if (amount <= 0 || !caster.isValid() || !Tasks.owns(caster)) {
            return;
        }
        target.damage(amount, DamageSource.builder(type).withCausingEntity(caster).withDirectEntity(caster).build());
    }
}
