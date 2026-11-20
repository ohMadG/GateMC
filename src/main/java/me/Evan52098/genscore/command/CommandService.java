package me.Evan52098.genscore.command;

import org.bukkit.plugin.java.JavaPlugin;
import revxrsal.commands.Lamp;
import revxrsal.commands.bukkit.BukkitLampConfig;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;

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
                .build();
    }

    /** Registers an object whose methods carry Lamp {@code @Command} annotations. */
    public void register(Object... handlers) {
        lamp.register(handlers);
    }

    public Lamp<BukkitCommandActor> lamp() {
        return lamp;
    }
}
