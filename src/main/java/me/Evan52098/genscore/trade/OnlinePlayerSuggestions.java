package me.Evan52098.genscore.trade;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** Tab-completes online player names (BlockvilleCore's OnlinePlayerSuggestionProvider). Reading names is thread-safe. */
public final class OnlinePlayerSuggestions implements SuggestionProvider<BukkitCommandActor> {

    @Override
    public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            names.add(p.getName());
        }
        return names;
    }
}
