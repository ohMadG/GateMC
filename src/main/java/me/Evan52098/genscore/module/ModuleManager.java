package me.Evan52098.genscore.module;

import me.Evan52098.genscore.GensCore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Loads modules in registration order and unloads them in reverse. A module that fails to enable is
 * logged and skipped so one broken feature does not take the server down.
 */
public final class ModuleManager {

    private final GensCore plugin;
    private final Map<String, GensModule> registered = new LinkedHashMap<>();
    private final List<GensModule> enabled = new ArrayList<>();

    public ModuleManager(GensCore plugin) {
        this.plugin = plugin;
    }

    public void register(GensModule module) {
        if (registered.putIfAbsent(module.id(), module) != null) {
            throw new IllegalStateException("Duplicate module id: " + module.id());
        }
    }

    public void enableAll() {
        for (GensModule module : registered.values()) {
            String path = "modules." + module.id();
            if (!plugin.getConfig().getBoolean(path, true)) {
                plugin.getLogger().info("Module " + module.id() + " is off in config.yml");
                continue;
            }
            try {
                module.enable(plugin);
                enabled.add(module);
                plugin.getLogger().info("Enabled module " + module.id());
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module " + module.id() + " failed to enable", e);
            }
        }
    }

    public void disableAll() {
        List<GensModule> reversed = new ArrayList<>(enabled);
        Collections.reverse(reversed);
        for (GensModule module : reversed) {
            try {
                module.disable();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module " + module.id() + " failed to disable", e);
            }
        }
        enabled.clear();
    }

    public void reloadAll() {
        for (GensModule module : enabled) {
            try {
                module.reload();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Module " + module.id() + " failed to reload", e);
            }
        }
    }

    /** An enabled module of the given type, or null if it is off or failed. For modules that use each other. */
    public <T extends GensModule> T get(Class<T> type) {
        for (GensModule module : enabled) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        return null;
    }

    public List<GensModule> enabled() {
        return Collections.unmodifiableList(enabled);
    }
}
