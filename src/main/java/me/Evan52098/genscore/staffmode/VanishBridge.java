package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.staff.Vanish;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Turns the staff module's vanish on or off for a player. {@link Vanish} only exposes {@code isVanished}; its
 * {@code set(Player, boolean)} is private. Until the accessor described in {@code integration/group-m.md} is added,
 * this calls it by reflection. When a public {@code setVanished(Player, boolean)} exists, that is used instead.
 */
final class VanishBridge {

    private final Vanish vanish;
    private final Method setter;

    VanishBridge(Vanish vanish, Logger log) {
        this.vanish = vanish;
        Method m = null;
        try {
            m = Vanish.class.getMethod("setVanished", Player.class, boolean.class);
        } catch (NoSuchMethodException e) {
            try {
                m = Vanish.class.getDeclaredMethod("set", Player.class, boolean.class);
                m.setAccessible(true);
            } catch (ReflectiveOperationException | RuntimeException e2) {
                log.warning("[staffmode] Can't reach the staff module's vanish; staff mode will not vanish players. " + e2);
            }
        }
        this.setter = m;
    }

    boolean isVanished(Player player) {
        return vanish.isVanished(player.getUniqueId());
    }

    /** Sets vanish. Call on the player's thread (the staff module hides them from each viewer on that viewer's thread). */
    boolean set(Player player, boolean on) {
        if (setter == null) {
            return false;
        }
        if (vanish.isVanished(player.getUniqueId()) == on) {
            return true;
        }
        try {
            setter.invoke(vanish, player, on);
            return true;
        } catch (ReflectiveOperationException e) {
            return false;
        }
    }
}
