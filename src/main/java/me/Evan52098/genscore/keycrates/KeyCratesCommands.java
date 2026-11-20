package me.Evan52098.genscore.keycrates;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.trade.OnlinePlayerSuggestions;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Locale;

/** /crates (/keys, /crate), /crate give|giveall|preview|set|remove|list|reload, /claim (/claimkeys). */
public final class KeyCratesCommands {

    private static final int MAX_GIVE = 10_000;

    private final GensCore plugin;
    private final KeyCratesModule module;
    private final CrateService service;
    private final CrateMenus menus;
    private final CrateBlocks blocks;

    KeyCratesCommands(GensCore plugin, KeyCratesModule module, CrateService service, CrateMenus menus, CrateBlocks blocks) {
        this.plugin = plugin;
        this.module = module;
        this.service = service;
        this.menus = menus;
        this.blocks = blocks;
    }

    @Command({"crates", "keys", "crate"})
    @CommandPermission("genscore.keycrates")
    @Description("Your crate keys - preview and open crates")
    public void menu(BukkitCommandActor actor) {
        menus.main(actor.requirePlayer());
    }

    @Command({"claim", "claimkeys"})
    @CommandPermission("genscore.keycrates")
    @Description("Collect crate keys and rewards that didn't fit in your inventory")
    public void claim(BukkitCommandActor actor) {
        service.claim(actor.requirePlayer());
    }

    @Command("crate preview")
    @CommandPermission("genscore.keycrates")
    @Description("See a crate's rewards and chances")
    public void preview(BukkitCommandActor actor, @Named("crate") @SuggestWith(CrateSuggestions.class) String crate) {
        Player player = actor.requirePlayer();
        CrateConfig.Crate c = service.config().crate(crate);
        if (c == null) {
            player.sendMessage(Msg.bad("There is no crate called " + Msg.white(crate) + "."));
            return;
        }
        menus.preview(player, c, 0, true);
    }

    @Command("crate give")
    @CommandPermission("genscore.keycrates.admin")
    @Description("Give crate keys (offline players get them in /claim)")
    public void give(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String name,
                     @Named("crate") @SuggestWith(CrateSuggestions.class) String crate, @Named("amount") int amount) {
        CommandSender sender = actor.sender();
        String id = crate.toLowerCase(Locale.ROOT);
        if (!validGive(sender, id, amount)) {
            return;
        }
        String by = sender.getName();
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            service.giveKeys(online.getUniqueId(), id, amount, "given by " + by);
            sender.sendMessage(Msg.good("Gave " + Msg.white(amount + "x " + id) + " key(s) to " + Msg.escape(online.getName()) + "."));
            return;
        }
        plugin.playerData().findUuid(name).thenAccept(found -> {
            if (found.isEmpty()) {
                Tasks.forSender(sender, () -> sender.sendMessage(Msg.bad("No player named " + Msg.white(name) + " has joined.")));
                return;
            }
            service.giveKeys(found.get(), id, amount, "given by " + by);
            Tasks.forSender(sender, () -> sender.sendMessage(Msg.good("Gave " + Msg.white(amount + "x " + id) + " key(s) to "
                    + Msg.escape(name) + " (offline: they are waiting in /claim).")));
        });
    }

    @Command("crate giveall")
    @CommandPermission("genscore.keycrates.admin")
    @Description("Give crate keys to everyone online")
    public void giveAll(BukkitCommandActor actor, @Named("crate") @SuggestWith(CrateSuggestions.class) String crate, @Named("amount") int amount) {
        CommandSender sender = actor.sender();
        String id = crate.toLowerCase(Locale.ROOT);
        if (!validGive(sender, id, Math.min(amount, 64)) || amount > 64) {
            if (amount > 64) {
                sender.sendMessage(Msg.bad("Give at most 64 keys each to everyone."));
            }
            return;
        }
        int count = 0;
        for (Player p : Bukkit.getOnlinePlayers()) {
            service.giveKeys(p.getUniqueId(), id, amount, "keyall by " + sender.getName());
            count++;
        }
        CrateConfig.Crate c = service.config().crate(id);
        String line = "&5&lCRATES &8» &7Everyone online got &f" + amount + "x " + (c != null ? c.keyName() : id) + "&7!";
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> p.sendMessage(CrateConfig.text(line)));
        }
        sender.sendMessage(Msg.good("Gave " + Msg.white(amount + "x " + id) + " key(s) to " + count + " players."));
    }

    private boolean validGive(CommandSender sender, String id, int amount) {
        if (amount < 1 || amount > MAX_GIVE) {
            sender.sendMessage(Msg.bad("Amount must be 1 to " + MAX_GIVE + "."));
            return false;
        }
        if (service.config().crate(id) == null) {
            sender.sendMessage(Msg.bad("There is no open crate called " + Msg.white(id) + "."));
            return false;
        }
        return true;
    }

    @Command("crate set")
    @CommandPermission("genscore.keycrates.admin")
    @Description("Make the block you are looking at a crate")
    public void set(BukkitCommandActor actor, @Named("crate") @SuggestWith(CrateSuggestions.class) String crate) {
        Player player = actor.requirePlayer();
        CrateConfig.Crate c = service.config().crate(crate);
        if (c == null) {
            player.sendMessage(Msg.bad("There is no crate called " + Msg.white(crate) + "."));
            return;
        }
        Block target = player.getTargetBlockExact(6);
        if (target == null || target.getType().isAir()) {
            player.sendMessage(Msg.bad("Look at a block within 6 blocks."));
            return;
        }
        blocks.set(target, c.id());
        plugin.getLogger().info("[keycrates] " + player.getName() + " placed crate " + c.id() + " at " + target.getWorld().getName()
                + " " + target.getX() + " " + target.getY() + " " + target.getZ());
        player.sendMessage(Msg.good("That " + Msg.white(target.getType().name().toLowerCase(Locale.ROOT)) + " is now the " + Msg.white(c.id()) + " crate."));
    }

    @Command("crate remove")
    @CommandPermission("genscore.keycrates.admin")
    @Description("Stop the block you are looking at being a crate")
    public void remove(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Block target = player.getTargetBlockExact(6);
        if (target == null || blocks.crateAt(target) == null) {
            player.sendMessage(Msg.bad("Look at a crate block within 6 blocks."));
            return;
        }
        if (!blocks.remove(target)) {
            player.sendMessage(Msg.bad("That crate comes from crates.yml (locations:). Remove it there and /crate reload."));
            return;
        }
        player.sendMessage(Msg.good("That block is no longer a crate."));
    }

    @Command("crate list")
    @CommandPermission("genscore.keycrates.admin")
    @Description("List crates and crate blocks")
    public void list(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        sender.sendMessage(Msg.neutral("Crates: " + Msg.white(String.join(", ", service.config().crates.keySet().stream().sorted().toList()))));
        for (String line : blocks.describe()) {
            sender.sendMessage(Msg.parse("<gray> - " + Msg.escape(line)));
        }
    }

    @Command("crate reload")
    @CommandPermission("genscore.keycrates.admin")
    @Description("Reload crates.yml and the keycrates settings")
    public void reload(BukkitCommandActor actor) {
        CommandSender sender = actor.sender();
        plugin.reloadConfig();
        module.reload();
        sender.sendMessage(Msg.good("Crates reloaded: " + Msg.white(service.config().crates.size() + "") + " open crate(s)."));
    }
}
