package me.Evan52098.genscore.cosmetics;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Set;

/**
 * One cosmetic from cosmetics.yml. Immutable; a reload builds new ones. Which fields matter depends on the category:
 * {@code message} for join/leave messages, {@code color} for name colours, {@code effect} for kill effects, explosive
 * kills and trails, {@code skin} for tool skins.
 *
 * @param permission store-bought unlock (LuckPerms). Owning the permission OR having a stored unlock row is enough.
 */
public record Cosmetic(CosmeticCategory category, String id, String displayName, String permission, Material icon,
                       List<String> description, @Nullable String message, @Nullable String color,
                       @Nullable Effect effect, @Nullable Skin skin) {

    /**
     * Particles, a sound and optionally a lightning flash (visual only, never a real bolt). Everything here is drawn
     * client-side: nothing damages blocks or entities.
     */
    public record Effect(Particle particle, int count, double spread, double speed, double offsetY, List<Color> colors,
                         float size, @Nullable String sound, float volume, float pitch, boolean lightning) {

        /**
         * Draws the effect at a location. Must run on the thread that owns {@code at} (Folia rule 2): the victim's or
         * walker's own thread, which owns the region they stand in.
         *
         * @param source when set, only players who can see this player receive the particles (keeps vanished staff
         *               hidden)
         */
        void play(Location at, @Nullable Player source, int colorIndex) {
            World world = at.getWorld();
            if (world == null) {
                return;
            }
            Location where = at.clone().add(0, offsetY, 0);
            ParticleBuilder b = new ParticleBuilder(particle).location(where).count(count)
                    .offset(spread, spread, spread).extra(speed);
            if (source != null) {
                b.source(source);
            }
            Class<?> type = particle.getDataType();
            Color c = colors.isEmpty() ? Color.WHITE : colors.get(Math.floorMod(colorIndex, colors.size()));
            if (type == Particle.DustOptions.class) {
                b.color(c, size);
            } else if (type == Particle.DustTransition.class) {
                Color to = colors.size() > 1 ? colors.get(Math.floorMod(colorIndex + 1, colors.size())) : c;
                b.colorTransition(c, to, size);
            } else if (type == Color.class) {
                b.data(c);
            }
            b.spawn();
            if (sound != null) {
                world.playSound(where, sound, SoundCategory.PLAYERS, volume, pitch);
            }
            if (lightning) {
                world.strikeLightningEffect(where); // effect only: no fire, no damage
            }
        }

        /** Particles that need data other than a colour (block, item, vibration) are refused at load. */
        static boolean supported(Particle p) {
            Class<?> type = p.getDataType();
            return type == Void.class || type == Particle.DustOptions.class || type == Particle.DustTransition.class
                    || type == Color.class;
        }
    }

    /**
     * A tool skin: shown to other players on a held tool of one of {@code materials}. Only a copy of the item is sent
     * to viewers (packet-level equipment display); the real item is never changed.
     */
    public record Skin(Set<Material> materials, @Nullable NamespacedKey itemModel, @Nullable Integer customModelData) {

        boolean fits(@Nullable ItemStack item) {
            return item != null && !item.getType().isAir() && materials.contains(item.getType());
        }

        /** A display-only copy with the new model. The original is untouched. */
        @SuppressWarnings("deprecation")
        ItemStack displayCopy(ItemStack real) {
            ItemStack copy = real.clone();
            ItemMeta meta = copy.getItemMeta();
            if (meta == null) {
                return copy;
            }
            if (itemModel != null) {
                meta.setItemModel(itemModel);
            }
            if (customModelData != null) {
                meta.setCustomModelData(customModelData);
            }
            copy.setItemMeta(meta);
            return copy;
        }
    }
}
