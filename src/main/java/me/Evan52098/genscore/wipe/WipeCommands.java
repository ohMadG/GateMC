package me.Evan52098.genscore.wipe;

import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.annotation.Single;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;
import revxrsal.commands.node.ExecutionContext;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * BlockvilleCore's wipe commands with {@code /bvadmin} renamed {@code /gensadmin}:
 * <ul>
 *   <li>{@code /wipe <player>} - the wipe menu</li>
 *   <li>{@code /gensadmin wipeplayer <player> full [preview|confirm]}</li>
 *   <li>{@code /gensadmin wipeplayercustom <player> <module:section,...|*>} (console / panel)</li>
 *   <li>{@code /gensadmin wiperestore <player|season> [file]}</li>
 *   <li>{@code /gensadmin wipeseason [preview|confirm]} - new: the season reset of every player (console confirms)</li>
 *   <li>{@code /wipereason <id>}</li>
 * </ul>
 * The kick action (the cross-server relay's half of a wipe) is gone: this is one server.
 */
public final class WipeCommands {

    public static final String PERMISSION = "genscore.admin.wipe";

    private static volatile WipeStore boundStore;

    private final WipeService service;
    private final WipeMenu menu;

    WipeCommands(WipeService service, WipeMenu menu) {
        this.service = service;
        this.menu = menu;
        boundStore = service.store();
    }

    @Command("wipe")
    @CommandPermission(PERMISSION)
    @Description("Open the wipe menu for a player")
    public void wipe(BukkitCommandActor actor, @Named("player") @Single @SuggestWith(OnlinePlayers.class) String targetName) {
        Player staff = actor.requirePlayer();
        menu.open(staff, targetName);
    }

    @Command("gensadmin wipeplayer")
    @CommandPermission(PERMISSION)
    @Description("Preview or carry out a full wipe of one player")
    public void wipePlayer(BukkitCommandActor actor,
                           @Named("player") @Single @SuggestWith(OnlinePlayers.class) String targetName,
                           @Named("scope") @Single @Optional @SuggestWith(Scopes.class) @Nullable String scopeWord,
                           @Named("action") @Single @Optional @SuggestWith(Actions.class) @Nullable String actionWord) {
        if (scopeWord == null || scopeWord.isBlank()) {
            actor.sender().sendMessage(Msg.neutral("Usage: " + Msg.white("/gensadmin wipeplayer (player) full [preview|confirm]")));
            return;
        }
        WipeScope scope = WipeScope.parseWipe(scopeWord);
        if (scope == null) {
            actor.sender().sendMessage(Msg.bad(Msg.white(scopeWord) + " is not a wipe scope. Use " + Msg.white("full")
                    + " (or the /wipe menu for a custom wipe)."));
            return;
        }
        String action = actionWord == null ? "preview" : actionWord.trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "preview" -> service.preview(actor.sender(), targetName, scope);
            case "confirm" -> service.confirm(actor.sender(), targetName, scope);
            default -> actor.sender().sendMessage(Msg.bad(Msg.white(actionWord) + " is not a wipe action. Use " + Msg.white("preview")
                    + " to see what would go, then " + Msg.white("confirm") + "."));
        }
    }

    @Command("gensadmin wipeplayercustom")
    @CommandPermission(PERMISSION)
    @Description("Wipe only the listed sections (module:section,...) of one player")
    public void wipePlayerCustom(BukkitCommandActor actor, @Named("player") @Single String targetName, @Named("sections") @Single String sectionsWord) {
        Set<String> selected = Arrays.stream(sectionsWord.split(","))
                .map(s -> s.trim().toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (selected.isEmpty()) {
            actor.sender().sendMessage(Msg.bad("Name at least one section to wipe, as module:section,module:section (or *)."));
            return;
        }
        boolean everything = selected.contains("*");
        Set<String> known = new LinkedHashSet<>();
        Map<String, List<String>> kept = new LinkedHashMap<>();
        for (WipeSection section : service.registry().sections()) {
            String key = section.key().toLowerCase(Locale.ROOT);
            known.add(key);
            if (!everything && !selected.contains(key)) {
                kept.computeIfAbsent(section.module(), k -> new ArrayList<>()).add(section.id());
            }
        }
        Set<String> unknown = new LinkedHashSet<>(selected);
        unknown.remove("*");
        unknown.removeAll(known);
        if (!unknown.isEmpty()) {
            actor.sender().sendMessage(Msg.bad("Unknown section(s) on this server: " + Msg.white(String.join(", ", unknown)) + ". Nothing was wiped."));
            return;
        }
        service.confirmCustom(actor.sender(), targetName, WipeKeepList.of(kept), "web panel");
    }

    @Command("gensadmin wiperestore")
    @CommandPermission(PERMISSION)
    @Description("List a player's wipe backups, or put one back (\"season\" for the season backups)")
    public void restore(BukkitCommandActor actor,
                        @Named("player") @Single @SuggestWith(BackupPlayers.class) String targetName,
                        @Named("file") @Single @Optional @SuggestWith(Backups.class) @Nullable String fileName) {
        if (fileName == null || fileName.isBlank()) {
            service.list(actor.sender(), targetName);
            return;
        }
        service.restore(actor.sender(), targetName, fileName.trim());
    }

    @Command("gensadmin wipeseason")
    @CommandPermission(PERMISSION)
    @Description("Season reset of every player: preview what goes, then confirm from the console")
    public void wipeSeason(BukkitCommandActor actor, @Named("action") @Single @Optional @SuggestWith(Actions.class) @Nullable String actionWord) {
        String action = actionWord == null ? "preview" : actionWord.trim().toLowerCase(Locale.ROOT);
        switch (action) {
            case "preview" -> service.seasonPreview(actor.sender());
            case "confirm" -> service.seasonConfirm(actor.sender());
            default -> actor.sender().sendMessage(Msg.bad("Use " + Msg.white("preview") + " first, then " + Msg.white("confirm") + " from the console."));
        }
    }

    @Command("wipereason")
    @CommandPermission(PERMISSION)
    @Description("Look up the staff-only reason behind a wipe by its id")
    public void wipeReason(BukkitCommandActor actor, @Named("id") @Single String wipeId) {
        service.reason(actor.sender(), wipeId);
    }

    // ------------------------------------------------------------------ suggestions (may run off the main thread)

    public static final class OnlinePlayers implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            List<String> names = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                names.add(p.getName());
            }
            return names;
        }
    }

    public static final class Scopes implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            List<String> scopes = new ArrayList<>();
            for (WipeScope scope : WipeScope.values()) {
                if (scope.wipeable()) {
                    scopes.add(scope.id());
                }
            }
            return scopes;
        }
    }

    public static final class Actions implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            return List.of("preview", "confirm");
        }
    }

    public static final class BackupPlayers implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            WipeStore store = boundStore;
            List<String> names = new ArrayList<>();
            names.add("season");
            if (store == null) {
                return names;
            }
            for (UUID id : store.players()) {
                String name = Bukkit.getOfflinePlayer(id).getName();
                if (name != null && !name.isBlank()) {
                    names.add(name);
                }
            }
            return names;
        }
    }

    public static final class Backups implements SuggestionProvider<BukkitCommandActor> {
        @Override
        public Collection<String> getSuggestions(ExecutionContext<BukkitCommandActor> context) {
            WipeStore store = boundStore;
            if (store == null) {
                return List.of();
            }
            String typed = context.getResolvedArgumentOrNull("player");
            List<File> files;
            if (typed != null && typed.equalsIgnoreCase("season")) {
                files = store.listSeason();
            } else {
                OfflinePlayer known = typed == null || typed.isBlank() ? null : Bukkit.getOfflinePlayerIfCached(typed);
                if (known != null) {
                    files = store.list(known.getUniqueId());
                } else {
                    File[] all = store.folder().listFiles((dir, name) -> name.endsWith(".json"));
                    files = all == null ? List.of() : List.of(all);
                }
            }
            List<String> names = new ArrayList<>(files.size());
            for (File f : files) {
                names.add(f.getName());
            }
            return names;
        }
    }
}
