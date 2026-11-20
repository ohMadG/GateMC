package me.Evan52098.genscore.staffmode;

import org.bukkit.GameRules;
import org.bukkit.entity.Player;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.logging.Logger;

/**
 * Per-player "reduced debug info" (F3 without coordinates). Paper has no API for it, only the world-wide game rule,
 * so this sends the vanilla entity event (22 = reduced on, 23 = off) to the one client by reflection on the
 * Mojang-mapped server classes. If that ever fails (a server update renames something) it logs once and does nothing:
 * staff mode keeps working, only the F3 hiding is lost.
 *
 * <p>The client resets the flag on respawn and world change; the listener calls {@link #apply} again then.
 */
final class DebugInfo {

    private final Logger log;
    private volatile boolean broken;
    private Constructor<?> packetCtor;
    private Method getHandle;

    DebugInfo(Logger log) {
        this.log = log;
    }

    /** Call on the player's thread. */
    void apply(Player player, boolean reduced) {
        if (broken) {
            return;
        }
        if (!reduced && Boolean.TRUE.equals(player.getWorld().getGameRuleValue(GameRules.REDUCED_DEBUG_INFO))) {
            return; // the world hides it for everyone anyway
        }
        try {
            if (packetCtor == null) {
                Class<?> packet = Class.forName("net.minecraft.network.protocol.game.ClientboundEntityEventPacket");
                Class<?> entity = Class.forName("net.minecraft.world.entity.Entity");
                packetCtor = packet.getConstructor(entity, byte.class);
                getHandle = player.getClass().getMethod("getHandle");
            }
            Object handle = getHandle.invoke(player);
            Object connection = handle.getClass().getField("connection").get(handle);
            Object packet = packetCtor.newInstance(handle, (byte) (reduced ? 22 : 23));
            Method send = null;
            for (Class<?> c = connection.getClass(); c != null && send == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("send") && m.getParameterCount() == 1
                            && m.getParameterTypes()[0].isAssignableFrom(packet.getClass())) {
                        send = m;
                        break;
                    }
                }
            }
            if (send == null) {
                throw new NoSuchMethodException("send(Packet)");
            }
            send.setAccessible(true);
            send.invoke(connection, packet);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            broken = true;
            log.warning("[staffmode] Can't hide F3 coordinates on this server version (" + e + "); staff mode works without it.");
        }
    }
}
