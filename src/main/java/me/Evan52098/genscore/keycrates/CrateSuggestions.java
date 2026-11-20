package me.Evan52098.genscore.keycrates;

import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.node.ExecutionContext;

import java.util.Collection;
import java.util.List;

/** Tab-completes crate ids from the current config. */
public final class CrateSuggestions implements SuggestionProvider<BukkitCommandActor> {

    static volatile CrateService service;

    @Override
    public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
        CrateService s = service;
        return s == null ? List.of() : s.config().crates.keySet().stream().sorted().toList();
    }
}
