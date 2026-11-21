package me.Evan52098.genscore.cosmetics;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.teleport.Homes;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;

final class HomeIconCommand {

    private final CosmeticsModule module;

    HomeIconCommand(CosmeticsModule module) {
        this.module = module;
    }

    @Command({"homeicon", "homeicons", "sethomeicon"})
    @CommandPermission(CosmeticsModule.HOMEICON)
    @Description("Choose the icon each of your homes shows in the homes menu")
    public void homeIcon(BukkitCommandActor actor, @Optional @Named("home") @Nullable String home) {
        Player player = actor.requirePlayer();
        if (home == null) {
            module.menus().openHomes(player);
            return;
        }
        String normalized = Homes.normalize(home);
        if (normalized == null) {
            player.sendMessage(Msg.bad("Home " + Msg.white(home) + " not found."));
            return;
        }
        List<String> names = module.homeNamesOf(player);
        if (names != null && !names.contains(normalized)) {
            player.sendMessage(Msg.bad("Home " + Msg.white(home) + " not found."));
            return;
        }
        module.menus().openIconPicker(player, normalized);
    }
}
