package me.Evan52098.genscore.settings;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.EssentialsModule;
import me.Evan52098.genscore.essentials.Social;
import me.Evan52098.genscore.essentials.SocialCommands;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Reads and changes the private-messages toggle that the essentials module owns (its {@link Social} data), so the
 * settings menu never keeps a second copy.
 */
final class EssentialsBridge {

    private final GensCore plugin;

    EssentialsBridge(GensCore plugin) {
        this.plugin = plugin;
    }

    /** The player's Social data, or null when essentials is off or the player is not loaded. */
    @Nullable Social social(UUID uuid) {
        EssentialsModule module = plugin.modules().get(EssentialsModule.class);
        SocialCommands commands = module == null ? null : module.social();
        return commands == null ? null : commands.social(uuid);
    }

    boolean essentialsEnabled() {
        return plugin.modules().get(EssentialsModule.class) != null;
    }
}
