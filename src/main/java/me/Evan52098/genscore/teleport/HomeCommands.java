package me.Evan52098.genscore.teleport;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.permissions.PermissionAttachmentInfo;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.List;
import java.util.Locale;

final class HomeCommands {

    private final TeleportService teleports;
    private final PlayerDataService players;
    private final PlayerSection<Homes> homesSection;
    private final int defaultMaxHomes;

    HomeCommands(TeleportService teleports, PlayerDataService players, PlayerSection<Homes> homesSection, int defaultMaxHomes) {
        this.teleports = teleports;
        this.players = players;
        this.homesSection = homesSection;
        this.defaultMaxHomes = defaultMaxHomes;
    }

    private Homes homes(Player p) {
        return players.get(p).get(homesSection);
    }

    int maxHomes(Player p) {
        if (p.hasPermission("genscore.homes.unlimited")) {
            return Integer.MAX_VALUE;
        }
        int max = defaultMaxHomes;
        for (PermissionAttachmentInfo info : p.getEffectivePermissions()) {
            String node = info.getPermission().toLowerCase(Locale.ROOT);
            if (info.getValue() && node.startsWith("genscore.homes.")) {
                try {
                    max = Math.max(max, Integer.parseInt(node.substring("genscore.homes.".length())));
                } catch (NumberFormatException ignored) {
                    // not a number node
                }
            }
        }
        return max;
    }


    @Command("home")
    @CommandPermission("genscore.home")
    @Description("Teleport to one of your homes")
    public void home(BukkitCommandActor actor, @Named("name") @Default("home") String rawName) {
        Player player = actor.requirePlayer();
        String name = Homes.normalize(rawName);
        Homes homes = homes(player);
        SavedLocation saved = name == null ? null : homes.get(name);
        if (saved == null) {
            // With exactly one home, /home goes there whatever it is called.
            List<String> names = homes.names();
            if ("home".equals(name) && names.size() == 1) {
                name = names.get(0);
                saved = homes.get(name);
            }
        }
        if (saved == null) {
            player.sendMessage(Msg.bad("Home " + Msg.white(rawName) + " not found."));
            return;
        }
        Location target = saved.toLocation();
        if (target == null) {
            player.sendMessage(Msg.bad("The world home " + Msg.white(name) + " was set in no longer exists."));
            return;
        }
        teleports.teleport(player, target, TeleportService.Kind.HOME, Msg.good("Teleported to home " + Msg.white(name) + "."));
    }

    @Command({"homes", "home list", "listhomes"})
    @CommandPermission("genscore.home")
    @Description("List your homes")
    public void homes(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        List<String> names = homes(player).names();
        if (names.isEmpty()) {
            player.sendMessage(Msg.neutral("You have no homes set."));
            return;
        }
        int max = maxHomes(player);
        player.sendMessage(Msg.neutral("Your homes (" + Msg.white(String.valueOf(names.size())) + "/"
                + Msg.white(max == Integer.MAX_VALUE ? "unlimited" : String.valueOf(max)) + "): " + Msg.white(String.join(", ", names))));
    }

    @Command("sethome")
    @CommandPermission("genscore.sethome")
    @Description("Set a home where you stand")
    public void setHome(BukkitCommandActor actor, @Named("name") @Default("home") String rawName) {
        Player player = actor.requirePlayer();
        String name = Homes.normalize(rawName);
        if (name == null) {
            player.sendMessage(Msg.bad("Home names are 1 to 16 letters, numbers, _ or -."));
            return;
        }
        Homes homes = homes(player);
        int max = maxHomes(player);
        if (!homes.has(name) && homes.size() >= max) {
            player.sendMessage(Msg.bad("You've reached your home limit (" + Msg.white(String.valueOf(max)) + "). Buy more with " + Msg.white("/buyhome") + "."));
            return;
        }
        homes.set(name, SavedLocation.of(player.getLocation()));
        player.sendMessage(Msg.good("Home " + Msg.white(name) + " has been set."));
    }

    @Command("delhome")
    @CommandPermission("genscore.delhome")
    @Description("Delete one of your homes")
    public void delHome(BukkitCommandActor actor, @Named("name") String rawName) {
        Player player = actor.requirePlayer();
        String name = Homes.normalize(rawName);
        if (name == null || !homes(player).remove(name)) {
            player.sendMessage(Msg.bad("Home " + Msg.white(rawName) + " not found."));
            return;
        }
        player.sendMessage(Msg.good("Home " + Msg.white(name) + " has been deleted."));
    }

    @Command("renamehome")
    @CommandPermission("genscore.renamehome")
    @Description("Rename one of your homes")
    public void renameHome(BukkitCommandActor actor, @Named("from") String rawFrom, @Named("to") String rawTo) {
        Player player = actor.requirePlayer();
        String from = Homes.normalize(rawFrom);
        String to = Homes.normalize(rawTo);
        Homes homes = homes(player);
        if (from == null || !homes.has(from)) {
            player.sendMessage(Msg.bad("Home " + Msg.white(rawFrom) + " not found."));
            return;
        }
        if (to == null) {
            player.sendMessage(Msg.bad("Home names are 1 to 16 letters, numbers, _ or -."));
            return;
        }
        if (!homes.rename(from, to)) {
            player.sendMessage(Msg.bad("A home named " + Msg.white(to) + " already exists."));
            return;
        }
        player.sendMessage(Msg.good("Renamed home " + Msg.white(from) + " to " + Msg.white(to) + "."));
    }
}
