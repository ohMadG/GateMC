package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

final class CrateShow {

    private static final float OPEN_ANGLE = 105f;
    static final float SCALE = 1.6f;
    private static final Vector3f HINGE = new Vector3f(0f, 0.1875f, -0.5f);
    private static final Vector3f LIFT = new Vector3f(0f, (SCALE - 1f) * 0.5f, 0f);
    private static final int OPEN = 16;
    private static final int RISE = 20;
    private static final int[] SPINS = {24, 27, 30, 33, 37, 41, 46, 52, 59, 67};
    private static final int REVEAL = 76;
    private static final int CLOSE = 120;
    private static final int END = 130;

    private CrateShow() {
    }

    static ItemStack modelItem(NamespacedKey model, String part) {
        ItemStack item = new ItemStack(Material.PAPER);
        item.editMeta(meta -> {
            meta.setItemModel(model);
            CustomModelDataComponent data = meta.getCustomModelDataComponent();
            data.setStrings(List.of(part));
            meta.setCustomModelDataComponent(data);
        });
        return item;
    }

    static Transformation base() {
        return new Transformation(new Vector3f(LIFT), new Quaternionf(), new Vector3f(SCALE, SCALE, SCALE), new Quaternionf());
    }

    static Transformation lid(float degrees) {
        Quaternionf rotation = new Quaternionf().rotateX((float) Math.toRadians(-degrees));
        Vector3f hinge = new Vector3f(HINGE).mul(SCALE);
        Vector3f moved = rotation.transform(new Vector3f(hinge));
        return new Transformation(hinge.add(LIFT).sub(moved), rotation, new Vector3f(SCALE, SCALE, SCALE), new Quaternionf());
    }

    private static Transformation floating(float up, float scale) {
        return new Transformation(new Vector3f(0f, up, 0f), new Quaternionf(), new Vector3f(scale, scale, scale), new Quaternionf());
    }

    private static void move(Display d, Transformation t, int ticks) {
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }

    static void play(Location center, ItemDisplay lid, @Nullable TextDisplay hologram, CrateConfig.Crate crate, CrateConfig.Reward won,
                     boolean rare, Player player, Runnable done, Runnable retired, Runnable finished, Logger logger) {
        World w = center.getWorld();
        double yaw = Math.toRadians(lid.getLocation().getYaw());
        Location stage = center.clone().add(-Math.sin(yaw) * 0.75, 0, Math.cos(yaw) * 0.75);
        Color color = crate.color();
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.2f);
        List<ItemStack> reel = new ArrayList<>();
        for (int i = 0; i < SPINS.length + 1; i++) {
            CrateConfig.Reward r = crate.roll(Set.of());
            reel.add((r == null ? won : r).icon());
        }
        ItemDisplay[] item = new ItemDisplay[1];
        TextDisplay[] label = new TextDisplay[1];
        AtomicBoolean gave = new AtomicBoolean();
        AtomicBoolean over = new AtomicBoolean();
        int[] tick = {0};
        Runnable give = () -> {
            if (gave.compareAndSet(false, true)) {
                CrateService.later(player, done, retired, 1);
            }
        };
        Runnable stop = () -> {
            if (!over.compareAndSet(false, true)) {
                return;
            }
            if (item[0] != null && item[0].isValid()) {
                item[0].remove();
            }
            if (label[0] != null && label[0].isValid()) {
                label[0].remove();
            }
            if (hologram != null && hologram.isValid()) {
                hologram.setVisibleByDefault(true);
            }
            give.run();
            finished.run();
        };
        Tasks.region.timer(center, task -> {
            if (over.get()) {
                task.cancel();
                return;
            }
            int t = tick[0]++;
            try {
                if (!lid.isValid()) {
                    task.cancel();
                    stop.run();
                    return;
                }
                if (t == 0 && hologram != null && hologram.isValid()) {
                    hologram.setVisibleByDefault(false);
                }
                if (t < OPEN) {
                    if (t % 4 == 0) {
                        move(lid, lid(t % 8 == 0 ? 6f : 0f), 2);
                        w.playSound(center, Sound.BLOCK_IRON_TRAPDOOR_CLOSE, 0.45f, 1.3f + t * 0.04f);
                    }
                    w.spawnParticle(Particle.DUST, center.clone().add(0, 0.1, 0), 3, 0.35, 0.3, 0.35, 0, dust);
                } else if (t == OPEN) {
                    move(lid, lid(OPEN_ANGLE), 8);
                    w.playSound(center, Sound.BLOCK_CHEST_OPEN, 0.9f, 0.8f);
                    w.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1.7f);
                    w.playSound(center, Sound.ENTITY_ILLUSIONER_CAST_SPELL, 0.6f, 1.4f);
                    w.spawnParticle(Particle.END_ROD, center.clone().add(0, 0.3, 0), 30, 0.15, 0.05, 0.15, 0.12);
                    w.spawnParticle(Particle.DUST, center.clone().add(0, 0.4, 0), 40, 0.4, 0.4, 0.4, 0, dust);
                } else if (t == RISE) {
                    item[0] = w.spawn(stage.clone().add(0, 0.05, 0), ItemDisplay.class, d -> {
                        d.setPersistent(false);
                        d.setItemStack(reel.get(0));
                        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GROUND);
                        d.setBillboard(Display.Billboard.VERTICAL);
                        d.setTransformation(floating(0f, 0.5f));
                    });
                    Tasks.region.later(center, () -> {
                        if (item[0] != null && item[0].isValid()) {
                            move(item[0], floating(1.45f, 1.6f), 18);
                        }
                    }, 1);
                }
                for (int k = 0; k < SPINS.length; k++) {
                    if (t == SPINS[k] && item[0] != null) {
                        item[0].setItemStack(reel.get(k + 1));
                        w.playSound(center, Sound.BLOCK_NOTE_BLOCK_PLING, 0.6f, 0.7f + k * 0.12f);
                        w.playSound(center, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.5f, 0.8f + k * 0.1f);
                        w.spawnParticle(Particle.DUST, stage.clone().add(0, 1.75, 0), 6, 0.2, 0.2, 0.2, 0, dust);
                    }
                }
                if (t == REVEAL && item[0] != null) {
                    item[0].setItemStack(won.icon());
                    item[0].setGlowColorOverride(color);
                    item[0].setGlowing(true);
                    move(item[0], floating(1.55f, 2.1f), 5);
                    Location top = stage.clone().add(0, 1.9, 0);
                    w.playSound(center, Sound.UI_TOAST_CHALLENGE_COMPLETE, 0.6f, 1.3f);
                    w.playSound(center, Sound.ENTITY_PLAYER_LEVELUP, 0.8f, 1.6f);
                    w.spawnParticle(Particle.FIREWORK, top, 40, 0.1, 0.1, 0.1, 0.18);
                    w.spawnParticle(Particle.DUST, top, 50, 0.6, 0.6, 0.6, 0, new Particle.DustOptions(color, 1.6f));
                    if (rare) {
                        w.playSound(center, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.6f);
                        w.playSound(center, Sound.ITEM_TOTEM_USE, 0.5f, 1.4f);
                        w.spawnParticle(Particle.TOTEM_OF_UNDYING, top, 80, 0.3, 0.3, 0.3, 0.5);
                    }
                    label[0] = w.spawn(stage.clone().add(0, 3.25, 0), TextDisplay.class, d -> {
                        d.setPersistent(false);
                        d.setBillboard(Display.Billboard.CENTER);
                        d.setShadowed(true);
                        d.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
                        d.text(CrateConfig.text(won.name()));
                    });
                    give.run();
                } else if (t > REVEAL && t < CLOSE && t % 3 == 0) {
                    w.spawnParticle(Particle.DUST, stage.clone().add(0, 1.9, 0), 2, 0.25, 0.25, 0.25, 0, dust);
                    if (rare && t % 6 == 0) {
                        beam(w, stage.clone().add(0, 2.3, 0), color, 12);
                    }
                } else if (t == CLOSE) {
                    if (item[0] != null && item[0].isValid()) {
                        item[0].remove();
                    }
                    if (label[0] != null && label[0].isValid()) {
                        label[0].remove();
                    }
                    move(lid, lid(0f), 8);
                    w.playSound(center, Sound.BLOCK_CHEST_CLOSE, 0.8f, 0.9f);
                } else if (t >= END) {
                    task.cancel();
                    stop.run();
                }
            } catch (RuntimeException e) {
                logger.log(Level.WARNING, "[keycrates] Crate animation failed; finishing the open without it", e);
                task.cancel();
                if (lid.isValid()) {
                    lid.setTransformation(lid(0f));
                }
                stop.run();
            }
        }, 1, 1);
    }

    private static void beam(World w, Location from, Color color, int height) {
        Particle.DustOptions dust = new Particle.DustOptions(color, 1.4f);
        for (int i = 0; i < height; i++) {
            w.spawnParticle(Particle.DUST, from.clone().add(0, i * 0.5, 0), 2, 0.05, 0.1, 0.05, 0, dust);
        }
    }
}
