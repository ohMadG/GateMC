package me.Evan52098.genscore.pets;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

final class PetFollower {

    private static final double[][] OFFSETS = {
            {0.75, 0.20, -0.35}, {-0.75, 0.20, -0.35}, {0.45, 0.80, -0.60}, {-0.45, 0.80, -0.60}, {0.0, 1.00, -0.85}};

    private record Shown(String key, List<ItemDisplay> displays) {
    }

    private final Function<Player, List<Material>> icons;
    private final Map<UUID, Shown> shown = new ConcurrentHashMap<>();
    private final Map<UUID, ScheduledTask> tasks = new ConcurrentHashMap<>();

    PetFollower(Function<Player, List<Material>> icons) {
        this.icons = icons;
    }

    void start(Player p) {
        if (tasks.containsKey(p.getUniqueId())) {
            return;
        }
        ScheduledTask t = Tasks.entity.timer(p, task -> tick(p), 5, 2);
        if (t != null) {
            tasks.put(p.getUniqueId(), t);
        }
    }

    void stop(UUID uuid) {
        ScheduledTask t = tasks.remove(uuid);
        if (t != null) {
            t.cancel();
        }
        clear(uuid);
    }

    void stopAll() {
        for (UUID uuid : List.copyOf(tasks.keySet())) {
            stop(uuid);
        }
    }

    private void clear(UUID uuid) {
        Shown s = shown.remove(uuid);
        if (s != null) {
            for (ItemDisplay d : s.displays()) {
                Tasks.onEntity(d, d::remove);
            }
        }
    }

    private void tick(Player p) {
        if (!p.isOnline()) {
            stop(p.getUniqueId());
            return;
        }
        List<Material> want = p.getGameMode() == GameMode.SPECTATOR || p.isInvisible()
                || p.hasPotionEffect(org.bukkit.potion.PotionEffectType.INVISIBILITY)
                || me.Evan52098.genscore.hub.HubModule.cloaked(p) ? List.of() : icons.apply(p);
        if (want.size() > OFFSETS.length) {
            want = want.subList(0, OFFSETS.length);
        }
        String key = want.toString();
        Shown s = shown.get(p.getUniqueId());
        Location base = p.getLocation();
        boolean stale = s == null || !s.key().equals(key);
        if (!stale) {
            for (ItemDisplay d : s.displays()) {
                if (!d.isValid() || d.getWorld() != base.getWorld() || d.getLocation().distanceSquared(base) > 64) {
                    stale = true;
                    break;
                }
            }
        }
        if (stale) {
            clear(p.getUniqueId());
            if (want.isEmpty()) {
                return;
            }
            List<ItemDisplay> displays = new ArrayList<>();
            for (int i = 0; i < want.size(); i++) {
                displays.add(spawn(target(base, i, p.getTicksLived()), want.get(i)));
            }
            s = new Shown(key, displays);
            shown.put(p.getUniqueId(), s);
            return;
        }
        int t = p.getTicksLived();
        for (int i = 0; i < s.displays().size(); i++) {
            ItemDisplay d = s.displays().get(i);
            Location to = target(base, i, t);
            d.teleportAsync(to);
        }
    }

    private static Location target(Location base, int i, int ticks) {
        double yaw = Math.toRadians(base.getYaw());
        double[] o = OFFSETS[i];
        double rx = -Math.cos(yaw);
        double rz = -Math.sin(yaw);
        double fx = -Math.sin(yaw);
        double fz = Math.cos(yaw);
        double bob = Math.sin(ticks / 9.0 + i * 1.7) * 0.08;
        Location to = base.clone().add(rx * o[0] + fx * o[2], 1.25 + o[1] + bob, rz * o[0] + fz * o[2]);
        to.setYaw(base.getYaw() + 180f);
        to.setPitch(0);
        return to;
    }

    private static ItemDisplay spawn(Location at, Material icon) {
        return at.getWorld().spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setItemStack(new ItemStack(icon));
            d.setBillboard(Display.Billboard.FIXED);
            d.setTeleportDuration(3);
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.45f, 0.45f, 0.45f), new Quaternionf()));
            d.setShadowRadius(0.15f);
            d.setShadowStrength(0.4f);
        });
    }
}
