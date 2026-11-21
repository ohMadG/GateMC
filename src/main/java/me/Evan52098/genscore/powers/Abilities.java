package me.Evan52098.genscore.powers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.damage.DamageType;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;

import static me.Evan52098.genscore.powers.PowerRuntime.clamp;

final class Abilities {

    private static final Logger LOG = Logger.getLogger("GensCore");
    private static final Set<String> MOVING = Set.of("jetpack", "blink", "grapple", "rewind", "shadow_step", "angel_wings");

    private final PowersModule m;
    private final Area area;

    Abilities(PowersModule m) {
        this.m = m;
        this.area = new Area(m);
    }

    static String key(PowerDef def) {
        return "power:" + def.id();
    }

    void trigger(Player p, PowerRuntime.Live l) {
        PowerRuntime rt = m.runtime();
        PowerDef def = rt.activeDef(p);
        if (def == null || !def.isActive()) {
            return;
        }
        String why = rt.blockedReason(p, def);
        if (why != null) {
            p.sendActionBar(Msg.bad(why));
            return;
        }
        UUID id = p.getUniqueId();
        long left = rt.cooldowns.remaining(id, key(def));
        if (left > 0) {
            p.sendActionBar(Msg.bad(Msg.escape(Text.plain(def.name())) + " is recharging: " + Msg.white(Cooldowns.format(left))));
            return;
        }
        if (def.cooldownMs() > 0 && !rt.cooldowns.tryStart(id, key(def), def.cooldownMs())) {
            return;
        }
        boolean fired;
        try {
            fired = fire(p, l, def);
        } catch (RuntimeException e) {
            LOG.warning("[powers] " + def.id() + " failed for " + p.getName() + ": " + e);
            fired = false;
        }
        if (!fired) {
            rt.cooldowns.clear(id, key(def));
        } else {
            PowersModule.grace(p, MOVING.contains(def.id()) ? def.ticks("duration", 3) + 20 : 0);
            p.sendActionBar(Text.of(def.name()));
        }
    }

    private boolean fire(Player p, PowerRuntime.Live l, PowerDef def) {
        return switch (def.id()) {
            case "jetpack" -> jetpack(p, l, def);
            case "blink" -> blink(p, l, def);
            case "phantom" -> {
                p.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, def.ticks("duration", 6), 0, false, false, true));
                p.getWorld().spawnParticle(Particle.LARGE_SMOKE, p.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
                yield true;
            }
            case "blizzard" -> blizzard(p, l, def);
            case "grapple" -> grapple(p, l, def);
            case "shockwave" -> shockwave(p, def);
            case "rewind" -> rewind(p, l);
            case "shadow_step" -> shadowStep(p, l, def);
            case "thors_wrath" -> thorsWrath(p, def);
            case "black_hole" -> blackHole(p, def);
            case "dragon_breath" -> dragonBreath(p, l, def);
            case "time_stop" -> timeStop(p, def);
            case "reapers_mark" -> reapersMark(p, def);
            case "divine_aegis" -> {
                l.aegisUntil = System.currentTimeMillis() + def.ticks("duration", 5) * 50L;
                p.getWorld().playSound(p.getLocation(), Sound.ITEM_TOTEM_USE, 0.6f, 1.6f);
                yield true;
            }
            case "angel_wings" -> {
                if (!m.runtime().startFlight(p, def.ticks("duration", 15))) {
                    p.sendActionBar(Msg.bad("You can already fly."));
                    yield false;
                }
                p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 30, 0.5, 0.2, 0.5, 0.05);
                yield true;
            }
            case "lockdown" -> factoryAction(p, me.Evan52098.genscore.factory.FactoryModule.powerCloseGate(p),
                    "Gate locked down.", "You have no factory gate to close.");
            case "repair_drone" -> factoryAction(p, me.Evan52098.genscore.factory.FactoryModule.powerRepairAll(p),
                    "Repair drone fixed your belt.", "You have no factory to repair.");
            case "fortress_mode" -> factoryAction(p, me.Evan52098.genscore.factory.FactoryModule.powerShield(p, def.ticks("duration", 60) / 20),
                    "Fortress mode: no raids for " + def.ticks("duration", 60) / 20 + "s.", "You have no factory to shield.");
            case "rush_hour", "overdrive" -> {
                PowersModule.boost(p.getUniqueId(), "belt-speed", def.num("belt-speed", 1.0), def.ticks("duration", 20) * 50L);
                p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BEACON_POWER_SELECT, 0.8f, 1.6f);
                p.getWorld().spawnParticle(Particle.ELECTRIC_SPARK, p.getLocation().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.1);
                yield true;
            }
            case "golden_hour" -> {
                PowersModule.boost(p.getUniqueId(), "sale-value", def.num("sale-value", 2.0), def.ticks("duration", 30) * 50L);
                p.getWorld().playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.4f);
                p.getWorld().spawnParticle(Particle.WAX_ON, p.getLocation().add(0, 1, 0), 40, 0.5, 0.7, 0.5, 0.1);
                yield true;
            }
            default -> false;
        };
    }

    private boolean factoryAction(Player p, boolean ok, String good, String bad) {
        p.sendMessage(ok ? Msg.good(good) : Msg.bad(bad));
        return ok;
    }

    private boolean jetpack(Player p, PowerRuntime.Live l, PowerDef def) {
        int ticks = def.ticks("duration", 3);
        double up = clamp(def.num("lift", 0.6), 0.1, 1.5);
        cancelChannel(l);
        l.noFallUntil = System.currentTimeMillis() + ticks * 50L + 6000;
        l.channel = channel(p, l, 2, ticks, () -> {
            if (m.runtime().usable(p, "jetpack") == null) {
                return false;
            }
            Vector dir = p.getLocation().getDirection();
            p.setVelocity(new Vector(dir.getX() * 0.6, up, dir.getZ() * 0.6));
            p.getWorld().spawnParticle(Particle.FLAME, p.getLocation(), 6, 0.15, 0.05, 0.15, 0.02);
            p.getWorld().spawnParticle(Particle.CLOUD, p.getLocation(), 3, 0.15, 0.05, 0.15, 0.01);
            return true;
        });
        return true;
    }

    private boolean blink(Player p, PowerRuntime.Live l, PowerDef def) {
        double dist = clamp(def.num("distance", 8), 1, 32);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        World w = p.getWorld();
        RayTraceResult hit = w.rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);
        Vector point = hit == null
                ? eye.toVector().add(dir.clone().multiply(dist))
                : hit.getHitPosition().subtract(dir.clone().multiply(0.6));
        double eyeHeight = eye.getY() - p.getLocation().getY();
        Location feet = point.toLocation(w, eye.getYaw(), eye.getPitch()).subtract(0, eyeHeight, 0);
        Location dest = standable(feet);
        if (dest == null) {
            p.sendActionBar(Msg.bad("There's no room to blink there."));
            return false;
        }
        Location from = p.getLocation();
        w.spawnParticle(Particle.PORTAL, from.add(0, 1, 0), 30, 0.3, 0.6, 0.3, 0.2);
        teleport(p, l, dest);
        return true;
    }

    private boolean grapple(Player p, PowerRuntime.Live l, PowerDef def) {
        double dist = clamp(def.num("distance", 28), 2, 64);
        Location eye = p.getEyeLocation();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, eye.getDirection(), dist, FluidCollisionMode.NEVER, true);
        if (hit == null || hit.getHitBlock() == null) {
            p.sendActionBar(Msg.bad("Nothing to hook onto in range."));
            return false;
        }
        Vector to = hit.getHitPosition().subtract(p.getLocation().toVector());
        double len = to.length();
        if (len < 1.5) {
            p.sendActionBar(Msg.bad("Too close to hook."));
            return false;
        }
        Vector v = to.normalize().multiply(Math.min(clamp(def.num("max-speed", 3), 0.5, 4), 0.6 + len * 0.09));
        v.setY(v.getY() + 0.35);
        p.setVelocity(v);
        l.noFallUntil = System.currentTimeMillis() + 6000;
        Vector step = hit.getHitPosition().subtract(eye.toVector()).multiply(1.0 / Math.max(1, (int) len));
        Location at = eye.clone();
        for (int i = 0; i < (int) len; i++) {
            at.add(step);
            if (Tasks.owns(at)) {
                p.getWorld().spawnParticle(Particle.CRIT, at, 1, 0, 0, 0, 0);
            }
        }
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_FISHING_BOBBER_RETRIEVE, 1f, 0.8f);
        return true;
    }

    private boolean rewind(Player p, PowerRuntime.Live l) {
        Location back = l.history.peekFirst();
        if (back == null || back.getWorld() != p.getWorld() || l.history.size() < 2) {
            p.sendActionBar(Msg.bad("Nothing to rewind to yet."));
            return false;
        }
        Location target = back.clone();
        l.history.clear();
        Tasks.region.run(target, () -> {
            Location safe = standable(target);
            Tasks.entity.run(p, () -> {
                if (safe == null) {
                    p.sendActionBar(Msg.bad("Something is in the way. Rewind failed."));
                    m.runtime().cooldowns.clear(p.getUniqueId(), "power:rewind");
                    return;
                }
                p.getWorld().spawnParticle(Particle.REVERSE_PORTAL, p.getLocation().add(0, 1, 0), 40, 0.3, 0.6, 0.3, 0.05);
                teleport(p, l, safe);
            });
        });
        return true;
    }

    private boolean shadowStep(Player p, PowerRuntime.Live l, PowerDef def) {
        Player target = lookedAt(p, clamp(def.num("distance", 12), 2, 32));
        if (target == null) {
            p.sendActionBar(Msg.bad("No player in sight."));
            return false;
        }
        UUID caster = p.getUniqueId();
        Tasks.entity.run(target, () -> {
            Location dest = null;
            if (target.isValid() && area.targetable(caster, target)) {
                Location tl = target.getLocation();
                Vector back = tl.getDirection().setY(0);
                if (back.lengthSquared() < 1.0E-4) {
                    back = new Vector(0, 0, 1);
                }
                back.normalize().multiply(-1);
                RayTraceResult wall = target.getWorld().rayTraceBlocks(tl.clone().add(0, 1, 0), back, 1.6, FluidCollisionMode.NEVER, true);
                if (wall == null) {
                    Location spot = tl.clone().add(back.multiply(1.3));
                    spot.setYaw(tl.getYaw());
                    spot.setPitch(0);
                    dest = standable(spot);
                }
            }
            Location go = dest;
            Tasks.entity.run(p, () -> {
                if (go == null || go.getWorld() != p.getWorld()) {
                    p.sendActionBar(Msg.bad("You can't get behind them."));
                    m.runtime().cooldowns.clear(caster, "power:shadow_step");
                    return;
                }
                p.getWorld().spawnParticle(Particle.SQUID_INK, p.getLocation().add(0, 1, 0), 20, 0.3, 0.6, 0.3, 0.02);
                teleport(p, l, go);
            });
        });
        return true;
    }

    private boolean blizzard(Player p, PowerRuntime.Live l, PowerDef def) {
        double r = clamp(def.num("radius", 6), 1, 24);
        int slow = (int) clamp(def.num("slowness-level", 3), 1, 6) - 1;
        cancelChannel(l);
        l.channel = channel(p, l, 10, def.ticks("duration", 5), () -> {
            Location at = p.getLocation();
            p.getWorld().spawnParticle(Particle.SNOWFLAKE, at.clone().add(0, 2, 0), 80, r / 2, 1.5, r / 2, 0.02);
            area.players(p, at, r, t -> {
                t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 30, slow, false, true, true));
                t.setFreezeTicks(Math.min(t.getMaxFreezeTicks() - 1, t.getFreezeTicks() + 30));
            });
            return true;
        });
        return true;
    }

    private boolean shockwave(Player p, PowerDef def) {
        double r = clamp(def.num("radius", 5), 1, 16);
        double strength = clamp(def.num("strength", 1.6), 0.2, 4);
        Location at = p.getLocation();
        Vector centre = at.toVector();
        p.getWorld().spawnParticle(Particle.EXPLOSION, at, 6, r / 3, 0.2, r / 3, 0);
        p.getWorld().playSound(at, Sound.ENTITY_GENERIC_EXPLODE, 0.8f, 1.2f);
        area.players(p, at, r, t -> {
            Vector v = t.getLocation().toVector().subtract(centre).setY(0);
            if (v.lengthSquared() < 1.0E-4) {
                v = new Vector(0.1, 0, 0);
            }
            t.setVelocity(v.normalize().multiply(strength).setY(0.6));
        });
        return true;
    }

    private boolean thorsWrath(Player p, PowerDef def) {
        double r = clamp(def.num("radius", 12), 1, 32);
        double damage = clamp(def.num("damage", 6), 0, 40);
        area.players(p, p.getLocation(), r, t -> {
            t.getWorld().strikeLightningEffect(t.getLocation());
            Area.hurt(p, t, damage, DamageType.LIGHTNING_BOLT);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60, 1, false, true, true));
        });
        return true;
    }

    private boolean blackHole(Player p, PowerDef def) {
        double dist = clamp(def.num("distance", 20), 2, 48);
        double r = clamp(def.num("radius", 8), 1, 16);
        double crush = clamp(def.num("crush-damage", 4), 0, 40);
        int ticks = def.ticks("duration", 4);
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        RayTraceResult hit = p.getWorld().rayTraceBlocks(eye, dir, dist, FluidCollisionMode.NEVER, true);
        Vector c = hit == null ? eye.toVector().add(dir.clone().multiply(dist)) : hit.getHitPosition().subtract(dir.clone().multiply(1.0));
        Location centre = c.toLocation(p.getWorld());
        int[] left = {Math.max(1, ticks / 5)};
        Tasks.region.timer(centre, task -> {
            if (left[0]-- <= 0) {
                task.cancel();
                return;
            }
            World w = centre.getWorld();
            w.spawnParticle(Particle.REVERSE_PORTAL, centre, 60, r / 3, r / 3, r / 3, 0.05);
            w.spawnParticle(Particle.SQUID_INK, centre, 10, 0.3, 0.3, 0.3, 0.01);
            try {
                for (Entity e : w.getNearbyEntities(centre, r, r, r, e -> e instanceof Item)) {
                    if (Tasks.owns(e)) {
                        pull(e, c, 0.35);
                    }
                }
            } catch (RuntimeException ignored) {
            }
            area.players(p, centre, r, t -> {
                if (t.getLocation().add(0, 1, 0).toVector().distanceSquared(c) < 2.25) {
                    Area.hurt(p, t, crush, DamageType.MAGIC);
                } else {
                    pull(t, c, 0.4);
                }
            });
        }, 1, 5);
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.5f, 0.6f);
        return true;
    }

    private static void pull(Entity e, Vector centre, double speed) {
        Vector v = centre.clone().subtract(e.getLocation().toVector());
        if (v.lengthSquared() < 0.25) {
            return;
        }
        e.setVelocity(v.normalize().multiply(speed));
    }

    private boolean dragonBreath(Player p, PowerRuntime.Live l, PowerDef def) {
        double dist = clamp(def.num("distance", 8), 1, 16);
        double damage = clamp(def.num("damage", 1), 0, 10);
        double cone = Math.toRadians(clamp(def.num("cone-degrees", 30), 5, 80));
        int fire = def.ticks("burn-seconds", 3);
        cancelChannel(l);
        l.channel = channel(p, l, 4, def.ticks("duration", 3), () -> {
            if (m.runtime().usable(p, "dragon_breath") == null) {
                return false;
            }
            Location eye = p.getEyeLocation();
            Vector dir = eye.getDirection();
            Vector origin = eye.toVector();
            for (double d = 1; d <= dist; d += 1) {
                Location at = eye.clone().add(dir.clone().multiply(d));
                if (Tasks.owns(at)) {
                    p.getWorld().spawnParticle(Particle.FLAME, at, 4, d * 0.12, d * 0.12, d * 0.12, 0.01);
                }
            }
            area.players(p, eye, dist, t -> {
                Vector to = t.getLocation().add(0, 1, 0).toVector().subtract(origin);
                if (to.lengthSquared() < 1.0E-4 || to.angle(dir) > cone || !clearLine(t, origin)) {
                    return;
                }
                t.setFireTicks(Math.max(t.getFireTicks(), fire));
                Area.hurt(p, t, damage, DamageType.ON_FIRE);
            });
            return true;
        });
        return true;
    }

    private boolean timeStop(Player p, PowerDef def) {
        double r = clamp(def.num("radius", 15), 1, 32);
        int ticks = def.ticks("duration", 3);
        PowerRuntime rt = m.runtime();
        p.getWorld().playSound(p.getLocation(), Sound.BLOCK_BELL_RESONATE, 1f, 0.5f);
        area.players(p, p.getLocation(), r, t -> {
            rt.frozenUntil.put(t.getUniqueId(), System.currentTimeMillis() + ticks * 50L);
            t.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, ticks, 6, false, false, true));
            t.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, ticks, 2, false, false, true));
            t.getWorld().spawnParticle(Particle.END_ROD, t.getLocation().add(0, 1, 0), 15, 0.3, 0.6, 0.3, 0);
            t.sendActionBar(Msg.bad("Time has stopped!"));
        });
        return true;
    }

    private boolean reapersMark(Player p, PowerDef def) {
        Player target = lookedAt(p, clamp(def.num("distance", 30), 2, 64));
        if (target == null) {
            p.sendActionBar(Msg.bad("No player in sight."));
            return false;
        }
        UUID caster = p.getUniqueId();
        int ticks = def.ticks("duration", 10);
        double bonus = clamp(def.num("bonus-percent", 40), 0, 200) / 100.0;
        PowerRuntime rt = m.runtime();
        String name = target.getName();
        Tasks.entity.run(target, () -> {
            boolean ok = target.isValid() && area.targetable(caster, target);
            if (ok) {
                rt.marks.put(target.getUniqueId(), new PowerRuntime.Mark(caster, System.currentTimeMillis() + ticks * 50L, bonus));
                target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, ticks, 0, false, false, true));
                target.sendActionBar(Msg.bad("You have been marked for death!"));
            }
            Tasks.entity.run(p, () -> {
                if (ok) {
                    p.sendMessage(Msg.good("You marked " + Msg.white(name) + "."));
                } else {
                    p.sendActionBar(Msg.bad("You can't mark that player."));
                    rt.cooldowns.clear(caster, "power:reapers_mark");
                }
            });
        });
        return true;
    }

    private @Nullable ScheduledTask channel(Player p, PowerRuntime.Live l, int period, int ticks, java.util.function.BooleanSupplier step) {
        int[] left = {Math.max(1, ticks / period)};
        return Tasks.entity.timer(p, task -> {
            if (!p.isOnline() || p.isDead() || left[0]-- <= 0 || !step.getAsBoolean()) {
                task.cancel();
                if (l.channel == task) {
                    l.channel = null;
                }
            }
        }, 1, period);
    }

    private static void cancelChannel(PowerRuntime.Live l) {
        if (l.channel != null) {
            l.channel.cancel();
            l.channel = null;
        }
    }

    private void teleport(Player p, PowerRuntime.Live l, Location dest) {
        l.expectTeleportUntil = System.currentTimeMillis() + 3000;
        Location d = dest.clone();
        p.teleportAsync(d, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok -> {
            if (Boolean.TRUE.equals(ok)) {
                Tasks.entity.run(p, () -> p.getWorld().playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.3f));
            }
        });
    }

    static @Nullable Location standable(Location feet) {
        World w = feet.getWorld();
        if (w == null) {
            return null;
        }
        for (int up = 0; up <= 1; up++) {
            Location at = feet.clone().add(0, up, 0);
            if (at.getY() < w.getMinHeight() + 1 || at.getY() > w.getMaxHeight() - 2 || !Tasks.owns(at)) {
                continue;
            }
            Block b = at.getBlock();
            Block head = b.getRelative(BlockFace.UP);
            if (b.isPassable() && head.isPassable() && b.getType() != Material.LAVA && head.getType() != Material.LAVA) {
                return at;
            }
        }
        return null;
    }

    private static @Nullable Player lookedAt(Player p, double range) {
        Location eye = p.getEyeLocation();
        try {
            RayTraceResult r = p.getWorld().rayTrace(eye, eye.getDirection(), range, FluidCollisionMode.NEVER, true, 0.4,
                    e -> e instanceof Player o && o != p && Tasks.owns(e));
            return r != null && r.getHitEntity() instanceof Player t ? t : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static boolean clearLine(Player t, Vector from) {
        try {
            Location eye = t.getEyeLocation();
            Vector v = from.clone().subtract(eye.toVector());
            double len = v.length();
            return len < 0.5 || t.getWorld().rayTraceBlocks(eye, v.normalize(), len, FluidCollisionMode.NEVER, true) == null;
        } catch (RuntimeException e) {
            return false;
        }
    }
}
