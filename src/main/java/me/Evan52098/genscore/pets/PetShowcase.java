package me.Evan52098.genscore.pets;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

final class PetShowcase {

    private record Spot(String world, double x, double y, double z, float yaw) {
    }

    private final PetsModule module;
    private final NamespacedKey key;
    private final File file;
    private final List<Entity> spawned = new CopyOnWriteArrayList<>();
    private final List<ItemDisplay> spinning = new CopyOnWriteArrayList<>();
    private volatile @Nullable Spot spot;
    private volatile String signature = "";
    private ScheduledTask ensureTask;
    private ScheduledTask spinTask;
    private float angle;

    PetShowcase(PetsModule module, Plugin plugin, File file) {
        this.module = module;
        this.key = new NamespacedKey(plugin, "pet_showcase");
        this.file = file;
    }

    void start() {
        load();
        ensureTask = Tasks.global.timer(t -> ensure(), 100L, 200L);
        spinTask = Tasks.global.timer(t -> spin(), 120L, 30L);
    }

    void stop() {
        if (ensureTask != null) {
            ensureTask.cancel();
        }
        if (spinTask != null) {
            spinTask.cancel();
        }
        clear();
    }

    void reload() {
        load();
        clear();
        ensure();
    }

    private void load() {
        ConfigurationSection c = YamlConfiguration.loadConfiguration(file).getConfigurationSection("showcase");
        if (c == null || !c.getBoolean("enabled", true)) {
            spot = null;
            return;
        }
        spot = new Spot(c.getString("world", "world"), c.getDouble("x"), c.getDouble("y"), c.getDouble("z"),
                (float) c.getDouble("yaw", 0));
    }

    boolean moveTo(Location at) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        y.set("showcase.enabled", true);
        y.set("showcase.world", at.getWorld().getName());
        y.set("showcase.x", at.getX());
        y.set("showcase.y", at.getY());
        y.set("showcase.z", at.getZ());
        y.set("showcase.yaw", (double) at.getYaw());
        try {
            y.save(file);
        } catch (java.io.IOException e) {
            return false;
        }
        reload();
        return true;
    }

    private void clear() {
        for (Entity e : spawned) {
            Tasks.onEntity(e, e::remove);
        }
        spawned.clear();
        spinning.clear();
        signature = "";
    }

    private List<PetsSettings.PetDef> pets() {
        List<PetsSettings.PetDef> list = new ArrayList<>(module.settings().pets().values());
        list.sort(Comparator.comparingInt(PetsSettings.PetDef::rarity).thenComparing(PetsSettings.PetDef::name));
        return list;
    }

    private void ensure() {
        Spot s = spot;
        if (s == null) {
            return;
        }
        World w = Bukkit.getWorld(s.world());
        if (w == null) {
            return;
        }
        Location anchor = new Location(w, s.x(), s.y(), s.z());
        Tasks.region.run(anchor, () -> {
            if (!w.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                return;
            }
            List<PetsSettings.PetDef> pets = pets();
            String sig = pets.toString() + s;
            boolean healthy = sig.equals(signature) && !spawned.isEmpty();
            for (Entity e : spawned) {
                if (!e.isValid()) {
                    healthy = false;
                    break;
                }
            }
            if (healthy) {
                return;
            }
            for (Entity e : spawned) {
                if (e.isValid()) {
                    e.remove();
                }
            }
            spawned.clear();
            spinning.clear();
            for (Entity e : w.getNearbyEntities(anchor, 16, 6, 16)) {
                if (e.getPersistentDataContainer().has(key, PersistentDataType.BYTE)) {
                    e.remove();
                }
            }
            build(w, s, pets);
            signature = sig;
        });
    }

    private void build(World w, Spot s, List<PetsSettings.PetDef> pets) {
        double yaw = Math.toRadians(s.yaw());
        double rx = Math.cos(yaw);
        double rz = Math.sin(yaw);
        int rows = 1;
        for (int r = 0; r < 4; r++) {
            int count = 0;
            for (PetsSettings.PetDef def : pets) {
                if (def.rarity() == r) {
                    count++;
                }
            }
            rows = Math.max(rows, count);
        }
        double colGap = 1.45;
        double rowGap = 1.1;
        double top = s.y() + 0.75 + (rows - 1) * rowGap;
        spawned.add(text(spot(w, s, rx, rz, 0, top + 1.05), "&d&lPETS &8- &7hatch eggs from caches, crates & &a/pets", 0.7f, true));
        for (int r = 0; r < 4; r++) {
            double off = (r - 1.5) * colGap;
            String code = PetsSettings.RARITY_CODES[r];
            spawned.add(text(spot(w, s, rx, rz, off, top + 0.55), code + "&l" + PetsSettings.RARITY_NAMES[r].toUpperCase(java.util.Locale.ROOT), 0.6f, false));
            int k = 0;
            for (PetsSettings.PetDef def : pets) {
                if (def.rarity() != r) {
                    continue;
                }
                double y = top - k * rowGap;
                Location at = spot(w, s, rx, rz, off, y);
                ItemDisplay item = w.spawn(at, ItemDisplay.class, d -> {
                    tag(d);
                    d.setItemStack(new ItemStack(def.icon()));
                    d.setBillboard(Display.Billboard.FIXED);
                    d.setInterpolationDuration(30);
                    d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(0.5f, 0.5f, 0.5f), new Quaternionf()));
                });
                spawned.add(item);
                spinning.add(item);
                spawned.add(text(spot(w, s, rx, rz, off, y - 0.62), code + def.name() + "\n&a"
                        + PetsSettings.perkValue(def.perk(), def.value()) + " &7" + PetsSettings.perkName(def.perk()), 0.4f, false));
                k++;
            }
        }
    }

    private static Location spot(World w, Spot s, double rx, double rz, double off, double y) {
        return new Location(w, s.x() + rx * off, y, s.z() + rz * off, s.yaw(), 0);
    }

    private TextDisplay text(Location at, String legacy, float scale, boolean background) {
        return at.getWorld().spawn(at, TextDisplay.class, d -> {
            tag(d);
            d.text(Text.legacy(legacy));
            d.setBillboard(Display.Billboard.FIXED);
            d.setShadowed(true);
            d.setAlignment(TextDisplay.TextAlignment.CENTER);
            d.setBackgroundColor(background ? Color.fromARGB(150, 10, 6, 20) : Color.fromARGB(0, 0, 0, 0));
            d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf()));
        });
    }

    private void tag(Entity e) {
        e.setPersistent(false);
        e.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1);
    }

    private void spin() {
        if (spinning.isEmpty()) {
            return;
        }
        angle = (angle + 90f) % 360f;
        Quaternionf rot = new Quaternionf().rotateY((float) Math.toRadians(angle));
        for (ItemDisplay d : spinning) {
            Tasks.onEntity(d, () -> {
                if (!d.isValid()) {
                    return;
                }
                Transformation t = d.getTransformation();
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(30);
                d.setTransformation(new Transformation(t.getTranslation(), rot, t.getScale(), t.getRightRotation()));
            });
        }
    }
}
