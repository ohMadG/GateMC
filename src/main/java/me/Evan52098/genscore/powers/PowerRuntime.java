package me.Evan52098.genscore.powers;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ExperienceOrb;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PowerRuntime implements Listener {

    static final int REFRESH_TICKS = 300;
    private static final int PERIOD = 10;

    private final PowersModule m;
    private final NamespacedKey keyScale;
    private final NamespacedKey keyHealth;
    private final NamespacedKey keyGravity;
    private final NamespacedKey keyFall;
    private final NamespacedKey keySafeFall;
    private final NamespacedKey keyFlight;
    private final List<Map.Entry<Attribute, NamespacedKey>> ourModifiers;

    private final Map<UUID, Live> live = new ConcurrentHashMap<>();
    final Cooldowns cooldowns = new Cooldowns();
    final Map<UUID, Long> frozenUntil = new ConcurrentHashMap<>();
    final Map<UUID, Mark> marks = new ConcurrentHashMap<>();

    record Mark(UUID caster, long until, double bonus) {
    }

    static final class Live {
        long lastSneak;
        @Nullable String applied;
        int appliedGeneration;
        final ArrayDeque<Location> history = new ArrayDeque<>();
        @Nullable ScheduledTask ticker;
        @Nullable ScheduledTask channel;
        @Nullable ScheduledTask flightEnd;
        boolean flight;
        long expectTeleportUntil;
        volatile long noFallUntil;
        volatile long aegisUntil;
    }

    PowerRuntime(PowersModule m) {
        this.m = m;
        var plugin = m.plugin();
        keyScale = new NamespacedKey(plugin, "power_scale");
        keyHealth = new NamespacedKey(plugin, "power_health");
        keyGravity = new NamespacedKey(plugin, "power_gravity");
        keyFall = new NamespacedKey(plugin, "power_fall");
        keySafeFall = new NamespacedKey(plugin, "power_safe_fall");
        keyFlight = new NamespacedKey(plugin, "power_flight");
        ourModifiers = List.of(
                Map.entry(Attribute.SCALE, keyScale),
                Map.entry(Attribute.MAX_HEALTH, keyHealth),
                Map.entry(Attribute.GRAVITY, keyGravity),
                Map.entry(Attribute.FALL_DAMAGE_MULTIPLIER, keyFall),
                Map.entry(Attribute.SAFE_FALL_DISTANCE, keySafeFall));
    }

    @Nullable Live live(UUID uuid) {
        return live.get(uuid);
    }

    void start(Player p) {
        stripLeftovers(p);
        Live l = live.computeIfAbsent(p.getUniqueId(), u -> new Live());
        if (l.ticker != null) {
            l.ticker.cancel();
        }
        l.ticker = Tasks.entity.timer(p, t -> tick(p, l, t), PERIOD, PERIOD);
    }

    void stop(Player p) {
        Live l = live.remove(p.getUniqueId());
        if (l != null) {
            cleanup(p, l);
            if (l.ticker != null) {
                l.ticker.cancel();
            }
        } else {
            stripLeftovers(p);
        }
        frozenUntil.remove(p.getUniqueId());
        marks.remove(p.getUniqueId());
    }

    void cleanup(Player p, Live l) {
        removePassive(p, l.applied);
        l.applied = null;
        endFlight(p, l, false);
        if (l.channel != null) {
            l.channel.cancel();
            l.channel = null;
        }
        l.history.clear();
        l.aegisUntil = 0;
    }

    void refreshNow(Player p) {
        Live l = live.get(p.getUniqueId());
        if (l == null) {
            return;
        }
        cleanup(p, l);
        tick(p, l, null);
    }

    private void stripLeftovers(Player p) {
        for (Map.Entry<Attribute, NamespacedKey> e : ourModifiers) {
            AttributeInstance inst = p.getAttribute(e.getKey());
            if (inst != null && inst.getModifier(e.getValue()) != null) {
                inst.removeModifier(e.getValue());
            }
        }
        if (p.getPersistentDataContainer().has(keyFlight, PersistentDataType.BYTE)) {
            p.getPersistentDataContainer().remove(keyFlight);
            if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
                p.setFlying(false);
                p.setAllowFlight(false);
            }
        }
    }

    private void tick(Player p, Live l, @Nullable ScheduledTask task) {
        if (!p.isOnline()) {
            if (task != null) {
                task.cancel();
            }
            return;
        }
        if (p.isDead()) {
            return;
        }
        PowerDef want = desiredPassive(p);
        int gen = m.generation();
        String wantId = want == null ? null : want.id();
        if (!java.util.Objects.equals(wantId, l.applied) || (want != null && l.appliedGeneration != gen)) {
            removePassive(p, l.applied);
            l.applied = null;
            if (want != null) {
                applyAttributes(p, want);
                l.applied = want.id();
                l.appliedGeneration = gen;
            }
        }
        if (want != null) {
            refreshPassive(p, want);
        }
        if (l.flight) {
            PowerDef wings = m.settings().power("angel_wings");
            if (wings == null || blockedReason(p, wings) != null || !"angel_wings".equals(activeId(p))) {
                endFlight(p, l, true);
            }
        }
        if (l.aegisUntil > System.currentTimeMillis()) {
            p.getWorld().spawnParticle(Particle.END_ROD, p.getLocation().add(0, 1, 0), 6, 0.4, 0.6, 0.4, 0.01);
        }
        recordHistory(p, l);
    }

    private void recordHistory(Player p, Live l) {
        PowerDef rewind = m.settings().power("rewind");
        if (rewind == null || !"rewind".equals(activeId(p))) {
            l.history.clear();
            return;
        }
        int keep = (int) Math.max(1, Math.min(60, Math.round(rewind.num("seconds", 5) * 20 / PERIOD)));
        l.history.addLast(p.getLocation());
        while (l.history.size() > keep + 1) {
            l.history.removeFirst();
        }
    }

    @Nullable String activeId(Player p) {
        PowerData d = m.data(p.getUniqueId());
        return d == null ? null : d.active();
    }

    @Nullable PowerDef activeDef(Player p) {
        PowerData d = m.data(p.getUniqueId());
        if (d == null) {
            return null;
        }
        String id = d.active();
        PowerDef def = m.settings().power(id);
        if (def == null || !def.enabled() || !d.owns(def.id())) {
            return null;
        }
        return def;
    }

    @Nullable PowerDef usable(Player p, String id) {
        PowerDef def = activeDef(p);
        if (def == null || !def.id().equals(id) || blockedReason(p, def) != null) {
            return null;
        }
        return def;
    }

    @Nullable String blockedReason(Player p, PowerDef def) {
        if (!def.enabled()) {
            return "That power is turned off right now.";
        }
        if (p.getGameMode() == GameMode.SPECTATOR) {
            return "Powers don't work in spectator mode.";
        }
        PowersSettings s = m.settings();
        if (s.worldDisabled(p.getWorld().getName())) {
            return "Powers are turned off in this world.";
        }
        if (false) {
            return "You can't use " + Msg.escape(Text.plain(def.name())) + " while in combat.";
        }
        return null;
    }

    private @Nullable PowerDef desiredPassive(Player p) {
        PowerDef def = activeDef(p);
        if (def == null || def.kind() != PowerDef.Kind.PASSIVE || blockedReason(p, def) != null) {
            return null;
        }
        return def;
    }

    private void applyAttributes(Player p, PowerDef def) {
        switch (def.id()) {
            case "ant_size" -> modifier(p, Attribute.SCALE, keyScale,
                    clamp(def.num("scale", 0.5), 0.2, 1) - 1, AttributeModifier.Operation.ADD_NUMBER);
            case "titan" -> {
                modifier(p, Attribute.SCALE, keyScale, clamp(def.num("scale", 1.25), 1, 2) - 1, AttributeModifier.Operation.ADD_NUMBER);
                modifier(p, Attribute.MAX_HEALTH, keyHealth, clamp(def.num("extra-health", 4), 0, 40), AttributeModifier.Operation.ADD_NUMBER);
            }
            case "moonwalker" -> {
                modifier(p, Attribute.GRAVITY, keyGravity, clamp(def.num("gravity", 0.4), 0.05, 1) - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
                modifier(p, Attribute.FALL_DAMAGE_MULTIPLIER, keyFall, clamp(def.num("fall-damage", 0.5), 0, 1) - 1, AttributeModifier.Operation.MULTIPLY_SCALAR_1);
                modifier(p, Attribute.SAFE_FALL_DISTANCE, keySafeFall, clamp(def.num("safe-fall", 3), 0, 20), AttributeModifier.Operation.ADD_NUMBER);
            }
            default -> {
            }
        }
    }

    private void modifier(Player p, Attribute attribute, NamespacedKey key, double amount, AttributeModifier.Operation op) {
        AttributeInstance inst = p.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        if (inst.getModifier(key) != null) {
            inst.removeModifier(key);
        }
        if (amount != 0) {
            inst.addTransientModifier(new AttributeModifier(key, amount, op));
        }
    }

    private void refreshPassive(Player p, PowerDef def) {
        switch (def.id()) {
            case "aqua_lungs" -> {
                potion(p, PotionEffectType.WATER_BREATHING, 0);
                potion(p, PotionEffectType.DOLPHINS_GRACE, 0);
            }
            case "owl_eyes" -> potion(p, PotionEffectType.NIGHT_VISION, 0);
            case "speedster" -> potion(p, PotionEffectType.SPEED, level(def, "speed-level", 2));
            case "miners_rush" -> potion(p, PotionEffectType.HASTE, level(def, "haste-level", 2));
            case "ember_walker" -> {
                potion(p, PotionEffectType.FIRE_RESISTANCE, 0);
                if (!p.isSneaking()) {
                    p.getWorld().spawnParticle(Particle.FLAME, p.getLocation().add(0, 0.1, 0), 4, 0.25, 0.05, 0.25, 0.01);
                }
            }
            case "magnet" -> magnet(p, def);
            default -> {
            }
        }
    }

    private static int level(PowerDef def, String key, int fallback) {
        return (int) clamp(def.num(key, fallback), 1, 5) - 1;
    }

    private static void potion(Player p, PotionEffectType type, int amplifier) {
        PotionEffect cur = p.getPotionEffect(type);
        boolean refresh = cur == null
                || cur.getAmplifier() < amplifier
                || (isOurs(cur, amplifier) && cur.getDuration() < REFRESH_TICKS - 2 * PERIOD);
        if (refresh) {
            p.addPotionEffect(new PotionEffect(type, REFRESH_TICKS, amplifier, true, false, true));
        }
    }

    private static boolean isOurs(PotionEffect e, int amplifier) {
        return e.isAmbient() && !e.hasParticles() && !e.isInfinite() && e.getDuration() <= REFRESH_TICKS && e.getAmplifier() == amplifier;
    }

    private static void removeOurPotion(Player p, PotionEffectType type) {
        PotionEffect cur = p.getPotionEffect(type);
        if (cur != null && cur.isAmbient() && !cur.hasParticles() && !cur.isInfinite() && cur.getDuration() <= REFRESH_TICKS) {
            p.removePotionEffect(type);
        }
    }

    private void removePassive(Player p, @Nullable String id) {
        for (Map.Entry<Attribute, NamespacedKey> e : ourModifiers) {
            AttributeInstance inst = p.getAttribute(e.getKey());
            if (inst != null && inst.getModifier(e.getValue()) != null) {
                inst.removeModifier(e.getValue());
            }
        }
        if (p.getHealth() > maxHealth(p)) {
            p.setHealth(maxHealth(p));
        }
        if (id == null) {
            return;
        }
        switch (id) {
            case "aqua_lungs" -> {
                removeOurPotion(p, PotionEffectType.WATER_BREATHING);
                removeOurPotion(p, PotionEffectType.DOLPHINS_GRACE);
            }
            case "owl_eyes" -> removeOurPotion(p, PotionEffectType.NIGHT_VISION);
            case "speedster" -> removeOurPotion(p, PotionEffectType.SPEED);
            case "miners_rush" -> removeOurPotion(p, PotionEffectType.HASTE);
            case "ember_walker" -> removeOurPotion(p, PotionEffectType.FIRE_RESISTANCE);
            default -> {
            }
        }
    }

    static double maxHealth(Player p) {
        AttributeInstance inst = p.getAttribute(Attribute.MAX_HEALTH);
        return inst == null ? 20 : inst.getValue();
    }

    private void magnet(Player p, PowerDef def) {
        double r = clamp(def.num("radius", 8), 1, 16);
        Location at = p.getLocation().add(0, 0.5, 0);
        UUID me = p.getUniqueId();
        try {
            for (Entity e : p.getNearbyEntities(r, r, r)) {
                if (!Tasks.owns(e)) {
                    continue;
                }
                if (e instanceof Item item) {
                    UUID owner = item.getOwner();
                    if (!item.canPlayerPickup() || item.getPickupDelay() > 40 || (owner != null && !owner.equals(me))) {
                        continue;
                    }
                } else if (!(e instanceof ExperienceOrb)) {
                    continue;
                }
                Vector v = at.toVector().subtract(e.getLocation().toVector());
                if (v.lengthSquared() < 1.0) {
                    continue;
                }
                e.setVelocity(v.normalize().multiply(0.45));
            }
        } catch (RuntimeException ignored) {
        }
    }

    boolean startFlight(Player p, int ticks) {
        Live l = live.get(p.getUniqueId());
        if (l == null || p.getAllowFlight()) {
            return false;
        }
        p.getPersistentDataContainer().set(keyFlight, PersistentDataType.BYTE, (byte) 1);
        p.setAllowFlight(true);
        p.setFlying(true);
        l.flight = true;
        if (l.flightEnd != null) {
            l.flightEnd.cancel();
        }
        l.flightEnd = Tasks.entity.later(p, () -> endFlight(p, l, true), ticks);
        return true;
    }

    void endFlight(Player p, Live l, boolean soft) {
        if (l.flightEnd != null) {
            l.flightEnd.cancel();
            l.flightEnd = null;
        }
        if (!l.flight) {
            return;
        }
        l.flight = false;
        p.getPersistentDataContainer().remove(keyFlight);
        if (p.getGameMode() == GameMode.SURVIVAL || p.getGameMode() == GameMode.ADVENTURE) {
            p.setFlying(false);
            p.setAllowFlight(false);
        }
        if (soft && p.isOnline() && !p.isDead()) {
            l.noFallUntil = System.currentTimeMillis() + 10_000;
            p.addPotionEffect(new PotionEffect(PotionEffectType.SLOW_FALLING, 120, 0, false, false, true));
            p.sendActionBar(Msg.neutral("Your wings fade..."));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        start(p);
        PowersSettings s = m.settings();
        PowerData d = m.data(p.getUniqueId());
        if (d == null || s.updateId().isBlank() || s.updateMessage().isBlank() || s.updateId().equals(d.seenUpdate())) {
            return;
        }
        d.seenUpdate(s.updateId());
        m.players().saveSoon(p.getUniqueId());
        Tasks.entity.later(p, () -> p.sendMessage(Text.of(s.updateMessage())
                .clickEvent(ClickEvent.runCommand("/powers"))
                .hoverEvent(HoverEvent.showText(Text.of("&eClick to open /powers")))), 60);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        stop(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player p = event.getEntity();
        Live l = live.get(p.getUniqueId());
        if (l != null) {
            cleanup(p, l);
            l.noFallUntil = 0;
        }
        frozenUntil.remove(p.getUniqueId());
        marks.remove(p.getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player p = event.getPlayer();
        Live l = live.get(p.getUniqueId());
        if (l != null) {
            cleanup(p, l);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Live l = live.get(event.getPlayer().getUniqueId());
        if (l == null) {
            return;
        }
        if (System.currentTimeMillis() < l.expectTeleportUntil) {
            l.expectTeleportUntil = 0;
            return;
        }
        l.history.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (!event.isSneaking()) {
            return;
        }
        Player p = event.getPlayer();
        Live l = live.get(p.getUniqueId());
        if (l == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - l.lastSneak <= m.settings().doubleSneakMs()) {
            l.lastSneak = 0;
            m.abilities().trigger(p, l);
        } else {
            l.lastSneak = now;
        }
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onFrozenMove(PlayerMoveEvent event) {
        Long until = frozenUntil.get(event.getPlayer().getUniqueId());
        if (until == null) {
            return;
        }
        if (System.currentTimeMillis() > until) {
            frozenUntil.remove(event.getPlayer().getUniqueId(), until);
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to.getX() != from.getX() || to.getZ() != from.getZ() || to.getY() > from.getY()) {
            Location back = from.clone();
            back.setYaw(to.getYaw());
            back.setPitch(to.getPitch());
            event.setTo(back);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSkate(PlayerMoveEvent event) {
        if (!event.hasChangedBlock()) {
            return;
        }
        Player p = event.getPlayer();
        Live l = live.get(p.getUniqueId());
        if (l == null || !"ice_skater".equals(l.applied) || p.isFlying() || p.isGliding() || p.isInsideVehicle()) {
            return;
        }
        PowerDef def = m.settings().power("ice_skater");
        if (def == null) {
            return;
        }
        Block feet = event.getTo().getBlock();
        if (feet.getType() == Material.WATER) {
            return;
        }
        int r = (int) clamp(def.num("radius", 2), 1, 4);
        long melt = (long) (clamp(def.num("melt-seconds", 4), 1, 60) * 20);
        Block centre = feet.getRelative(BlockFace.DOWN);
        if (centre.getType() == Material.FROSTED_ICE) {
            PowersModule.grace(p, 30);
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (dx * dx + dz * dz > r * r + 1) {
                    continue;
                }
                Block b = centre.getRelative(dx, 0, dz);
                if (!Tasks.owns(b.getLocation()) || b.getType() != Material.WATER) {
                    continue;
                }
                if (!(b.getBlockData() instanceof Levelled lv) || lv.getLevel() != 0 || !b.getRelative(BlockFace.UP).getType().isAir()) {
                    continue;
                }
                BlockState state = b.getState();
                state.setType(Material.FROSTED_ICE);
                EntityBlockFormEvent form = new EntityBlockFormEvent(p, b, state);
                if (form.callEvent()) {
                    state.update(true, false);
                    PowersModule.grace(p, 30);
                    melt(b, melt + java.util.concurrent.ThreadLocalRandom.current().nextLong(40));
                }
            }
        }
    }

    private static void melt(Block b, long delay) {
        Tasks.region.later(b.getLocation(), () -> {
            if (b.getType() != Material.FROSTED_ICE) {
                return;
            }
            org.bukkit.util.BoundingBox above = new org.bukkit.util.BoundingBox(b.getX() - 0.3, b.getY() + 1, b.getZ() - 0.3, b.getX() + 1.3, b.getY() + 2.5, b.getZ() + 1.3);
            if (!b.getWorld().getNearbyEntities(above, e -> e instanceof Player).isEmpty()) {
                melt(b, 20);
                return;
            }
            b.setType(Material.WATER);
        }, delay);
    }

    static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
