package me.Evan52098.genscore.command;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.autocomplete.SuggestionProvider;
import revxrsal.commands.bukkit.BukkitLampConfig;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.exception.InvalidPlayerException;
import revxrsal.commands.bukkit.parameters.PlayerParameterType;
import revxrsal.commands.node.ExecutionContext;
import revxrsal.commands.parameter.ParameterType;
import revxrsal.commands.stream.MutableStringStream;

/**
 * Lamp, the same command framework BlockvilleCore uses, so its command classes port with few changes.
 * Lamp itself never schedules tasks, so it is Folia-safe; command bodies must follow the threading rules in PLAN.md
 * (a player's command runs on that player's thread, the console's on the global thread).
 */
public final class CommandService {

    private final Lamp<BukkitCommandActor> lamp;

    public CommandService(JavaPlugin plugin) {
        BukkitLampConfig<BukkitCommandActor> config = BukkitLampConfig.<BukkitCommandActor>builder(plugin)
                .disableBrigadier()
                .build();
        this.lamp = Lamp.<BukkitCommandActor>builder()
                .accept(config)
                .suggestionProviders(p -> p.addProviderFactory(new AutoSuggest()))
                .parameterTypes(p -> p.addParameterType(Player.class, new VisiblePlayer()))
                .build();
    }

    private static final class VisiblePlayer implements ParameterType<BukkitCommandActor, Player> {

        private final PlayerParameterType selectors = new PlayerParameterType(false);

        @Override
        public Player parse(MutableStringStream input, ExecutionContext<BukkitCommandActor> context) {
            CommandSender sender = context.actor().sender();
            String name = input.peekString();
            if (name.startsWith("@") && sender.hasPermission("minecraft.command.selector")) {
                return selectors.parse(input, context);
            }
            name = input.readString();
            if (name.equals("self") || name.equals("me") || name.equals("@s")) {
                return context.actor().requirePlayer();
            }
            Player found = Bukkit.getPlayerExact(name);
            if (found == null || (sender instanceof Player viewer && !viewer.canSee(found))) {
                throw new InvalidPlayerException(name);
            }
            return found;
        }

        @Override
        public SuggestionProvider<BukkitCommandActor> defaultSuggestions() {
            return new Suggest.Players();
        }
    }

    /** Registers an object whose methods carry Lamp {@code @Command} annotations. */
    public void register(Object... handlers) {
        lamp.register(handlers);
    }

    public Lamp<BukkitCommandActor> lamp() {
        return lamp;
    }
}
