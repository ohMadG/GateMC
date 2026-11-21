package me.Evan52098.genscore.cosmetics;

import com.destroystokyo.paper.ParticleBuilder;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * One cosmetic from cosmetics.yml. Immutable; a reload builds new ones. Which fields matter depends on the category:
 * {@code message} for join/leave messages and chat tags, {@code color} for name paints and chat colours (a MiniMessage
 * tag) and glows (legacy colour codes, several = cycling), {@code effect} for auras. {@code price} is in credits, 0 =
 * not sold.
 *
 * @param permission store-bought unlock (LuckPerms). Owning the permission OR having a stored unlock row is enough.
 */
public record Cosmetic(CosmeticCategory category, String id, String displayName, String permission, Material icon,
                       List<String> description, @Nullable String message, @Nullable String color,
                       @Nullable Effect effect, long price) {

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
}
