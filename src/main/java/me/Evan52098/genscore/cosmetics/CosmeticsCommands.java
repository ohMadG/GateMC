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

/** /cosmetics (cosmetic, wardrobe, cos), its admin subcommands, and /homeicon. */
public final class CosmeticsCommands {

    private final CosmeticsModule module;
    private final Cooldowns cooldowns = new Cooldowns();

    CosmeticsCommands(CosmeticsModule module) {
        this.module = module;
    }

    @Command({"cosmetics", "cosmetic", "wardrobe", "cos"})
    @CommandPermission("genscore.cosmetics")
    @Description("Join and leave messages, chat tags, name paints, chat colours, auras and glows")
    public void open(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        module.menus().openMain(player);
    }

    @Command({"cosmetics give", "cosmetic give"})
    @CommandPermission(CosmeticsModule.ADMIN)
    @Description("Give a player a stored cosmetic unlock (crate rewards)")
    public void give(BukkitCommandActor actor, @Named("player") String name, @Named("category") String category,
                     @Named("id") String id) {
        CommandSender sender = actor.sender();
        CosmeticCategory cat = category(sender, category);
        if (cat == null) {
            return;
        }
        if (module.config().get(cat, id) == null) {
            sender.sendMessage(Msg.bad("There is no " + Msg.white(cat.key()) + " cosmetic called " + Msg.white(id) + "."));
            return;
        }
        module.resolve(name).thenCompose(uuid -> {
            if (uuid == null) {
                return java.util.concurrent.CompletableFuture.completedFuture((Boolean) null);
            }
            return module.grant(uuid, cat, id, "admin:" + sender.getName());
        }).whenComplete((isNew, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                sender.sendMessage(Msg.bad("Could not give it: " + Msg.white(String.valueOf(e.getMessage()))));
            } else if (isNew == null) {
                sender.sendMessage(Msg.bad("Player " + Msg.white(name) + " has never joined."));
            } else if (isNew) {
                sender.sendMessage(Msg.good("Gave " + Msg.white(name) + " the " + Msg.white(cat.key() + ":" + id) + " cosmetic."));
            } else {
                sender.sendMessage(Msg.neutral(Msg.white(name) + " already had " + Msg.white(cat.key() + ":" + id) + "."));
            }
        }));
    }

    @Command({"cosmetics revoke", "cosmetic revoke"})
    @CommandPermission(CosmeticsModule.ADMIN)
    @Description("Remove a stored cosmetic unlock (permission unlocks are managed in LuckPerms)")
    public void revoke(BukkitCommandActor actor, @Named("player") String name, @Named("category") String category,
                       @Named("id") String id) {
        CommandSender sender = actor.sender();
        CosmeticCategory cat = category(sender, category);
        if (cat == null) {
            return;
        }
        module.resolve(name).thenCompose(uuid -> uuid == null
                ? java.util.concurrent.CompletableFuture.completedFuture((Boolean) null)
                : module.revoke(uuid, cat, id)).whenComplete((removed, e) -> Tasks.forSender(sender, () -> {
            if (e != null) {
                sender.sendMessage(Msg.bad("Could not revoke it: " + Msg.white(String.valueOf(e.getMessage()))));
            } else if (removed == null) {
                sender.sendMessage(Msg.bad("Player " + Msg.white(name) + " has never joined."));
            } else if (removed) {
                sender.sendMessage(Msg.good("Removed " + Msg.white(cat.key() + ":" + id) + " from " + Msg.white(name) + "."));
            } else {
                sender.sendMessage(Msg.neutral(Msg.white(name) + " had no stored unlock for " + Msg.white(cat.key() + ":" + id) + "."));
            }
        }));
    }

    @Command({"cosmetics reload", "cosmetic reload"})
    @CommandPermission(CosmeticsModule.ADMIN)
    @Description("Reload modules/cosmetics/cosmetics.yml")
    public void reload(BukkitCommandActor actor) {
        module.reload();
        actor.sender().sendMessage(Msg.good("Cosmetics reloaded."));
    }

    private static @Nullable CosmeticCategory category(CommandSender sender, String key) {
        CosmeticCategory cat = CosmeticCategory.byKey(key);
        if (cat == null) {
            StringBuilder keys = new StringBuilder();
            for (CosmeticCategory c : CosmeticCategory.values()) {
                keys.append(keys.isEmpty() ? "" : ", ").append(c.key());
            }
            sender.sendMessage(Msg.bad("Unknown category. Use one of: " + Msg.white(keys.toString())));
        }
        return cat;
    }
}
