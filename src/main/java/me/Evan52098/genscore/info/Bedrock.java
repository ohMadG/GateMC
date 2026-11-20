package me.Evan52098.genscore.info;

import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Tells Bedrock players (joined through Geyser + Floodgate) apart from Java players. Floodgate is optional and is not a
 * compile dependency, so its API is called by reflection; without Floodgate everyone counts as Java. Safe from any
 * thread (FloodgateApi.isFloodgatePlayer reads a concurrent map).
 */
public final class Bedrock {

    private static volatile boolean resolved;
    private static volatile Object api;
    private static volatile Method isFloodgatePlayer;

    private Bedrock() {
    }

    public static boolean is(UUID player) {
        resolve();
        Object a = api;
        Method m = isFloodgatePlayer;
        if (a == null || m == null) {
            return false;
        }
        try {
            return (Boolean) m.invoke(a, player);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** Forgets the lookup so a later call finds Floodgate again (after a reload). */
    public static void reset() {
        resolved = false;
        api = null;
        isFloodgatePlayer = null;
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        synchronized (Bedrock.class) {
            if (resolved) {
                return;
            }
            try {
                if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
                    Class<?> type = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                    api = type.getMethod("getInstance").invoke(null);
                    isFloodgatePlayer = type.getMethod("isFloodgatePlayer", UUID.class);
                }
            } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
                api = null;
                isFloodgatePlayer = null;
            }
            resolved = true;
        }
    }
}
