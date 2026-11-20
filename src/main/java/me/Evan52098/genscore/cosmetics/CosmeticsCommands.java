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
    @Description("Kill effects, join messages, name colours, trails and tool skins")
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

    @Command({"cosmetics kills", "cosmetic kills", "killrank"})
    @CommandPermission("genscore.cosmetics")
    @Description("Show a player's kill count and kill rank")
    public void kills(BukkitCommandActor actor, @Optional @Named("player") @Nullable String name) {
        CommandSender sender = actor.sender();
        if (sender instanceof Player p && !cooldowns.tryStart(p.getUniqueId(), "kills", 2_000)) {
            return; // checklist 10: this can hit the database for offline players
        }
        String target = name != null ? name : sender.getName();
        Player online = Bukkit.getPlayerExact(target);
        if (online != null && CosmeticsModule.kills(online.getUniqueId()) >= 0) {
            show(sender, online.getName(), CosmeticsModule.kills(online.getUniqueId()));
            return;
        }
        if (name == null && !(sender instanceof Player)) {
            sender.sendMessage(Msg.bad("Name a player."));
            return;
        }
        module.resolve(target).thenCompose(uuid -> uuid == null
                ? java.util.concurrent.CompletableFuture.completedFuture((Integer) null)
                : module.storedKills(uuid)).whenComplete((k, e) -> Tasks.forSender(sender, () -> {
            if (e != null || k == null) {
                sender.sendMessage(Msg.bad("Player " + Msg.white(target) + " has never joined."));
            } else {
                show(sender, target, k);
            }
        }));
    }

    private void show(CommandSender sender, String who, int kills) {
        CosmeticsConfig cfg = module.config();
        CosmeticsConfig.KillRank rank = cfg.rankFor(kills);
        CosmeticsConfig.KillRank next = cfg.nextRank(kills);
        String line = Msg.white(who) + ": " + Msg.white(String.valueOf(kills)) + " kills"
                + (rank == null ? "" : ", rank " + Msg.white(CosmeticsModule.plain(rank.display())))
                + (next == null ? "" : " (" + Msg.white(String.valueOf(next.kills() - kills)) + " more for "
                + Msg.white(CosmeticsModule.plain(next.display())) + ")");
        sender.sendMessage(Msg.neutral(line));
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
