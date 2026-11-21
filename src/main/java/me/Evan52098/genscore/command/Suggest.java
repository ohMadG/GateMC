package me.Evan52098.genscore.command;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public final class Suggest {

    private Suggest() {
    }

    public static <T extends GensModule> @Nullable T module(Class<T> type) {
        try {
            return JavaPlugin.getPlugin(GensCore.class).modules().get(type);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static final class Players implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            List<String> out = new ArrayList<>();
            Player viewer = context.actor().sender() instanceof Player p ? p : null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (viewer == null || viewer.canSee(p)) {
                    out.add(p.getName());
                }
            }
            return out;
        }
    }
}
