package me.Evan52098.genscore.module;

import me.Evan52098.genscore.GensCore;

/**
 * One feature of GensCore (factions, combat tag, KOTH, shop, ...). Each module is switched on or off
 * under {@code modules.<id>} in config.yml and keeps its own files in {@code modules/<id>/}.
 */
public interface GensModule {

    /** Short lowercase id, used in config.yml and for the module's folder. */
    String id();

    /** Register listeners, commands and tasks. Must schedule work through {@code Tasks}, never the Bukkit scheduler. */
    void enable(GensCore plugin) throws Exception;

    /** Save data and release resources. Scheduled tasks are cancelled by the plugin afterwards. */
    default void disable() {
    }

    /** Reread config without a restart. */
    default void reload() {
    }
}
