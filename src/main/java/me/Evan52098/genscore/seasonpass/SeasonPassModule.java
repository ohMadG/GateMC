package me.Evan52098.genscore.seasonpass;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.rewardbox.RewardBox;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
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

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The Season Pass (HXSMP's /pass, /battlepass, /seasonpass, /bp): free and premium tracks of N tiers, pass XP from
 * daily and weekly challenges, one claim per tier and track. Premium is the permission {@value #PREMIUM}, so Tebex or
 * LuckPerms can grant it. Settings are under {@code seasonpass} in config.yml.
 *
 * <p>Other modules feed challenges through {@link #progress(UUID, Objective, String, long)} (static, any thread); see
 * integration/group-h.md for the hook lines.
 */
public final class SeasonPassModule implements GensModule {

    public static final String PREMIUM = "genscore.pass.premium";
    static final String USE = "genscore.pass";
    static final String ADMIN = "genscore.pass.admin";

    private static volatile @Nullable SeasonPassModule instance;

    private GensCore plugin;
    private PassService service;
    private PassMenus menus;
    private RewardBox box;
    private PassListener listener;

    @Override
    public String id() {
        return "seasonpass";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(), PassService.schema());
        box = new RewardBox(plugin, "pass:", "/pass box", "seasonpass");
        service = new PassService(plugin, config(), box);
        menus = new PassMenus(service);
        listener = new PassListener(service);
        Bukkit.getPluginManager().registerEvents(listener, plugin);
        plugin.commands().register(this);
        // Players already online (a reload of the module) get their pass loaded now.
        for (Player p : Bukkit.getOnlinePlayers()) {
            service.join(p.getUniqueId(), p.getName());
        }
        Tasks.async.timer(t -> service.playtimeTick(), 60, 60, TimeUnit.SECONDS);
        Tasks.async.timer(t -> service.saveAll(), 60, 60, TimeUnit.SECONDS);
        Tasks.async.timer(t -> listener.purgePlaced(), 30, 30, TimeUnit.MINUTES);
        instance = this;
    }

    private PassConfig config() {
        return PassConfig.from(plugin.getConfig().getConfigurationSection("seasonpass"), plugin.getLogger());
    }

    @Override
    public void reload() {
        if (service != null) {
            service.config(config());
        }
    }

    @Override
    public void disable() {
        instance = null;
        if (service != null) {
            service.shutdown();
        }
        if (box != null) {
            box.shutdown();
        }
    }

    // ------------------------------------------------------------------ API for other modules

    /**
     * Counts toward the current daily and weekly challenges of an online player. Static and safe from any thread; does
     * nothing when the module is off, the season is not running or the player is not loaded.
     *
     * @param what the material / entity type / tag the challenge filter matches (null = only unfiltered challenges)
     */
    public static void progress(UUID player, Objective type, @Nullable String what, long amount) {
        SeasonPassModule m = instance;
        if (m != null && amount > 0 && amount <= 1_000_000) {
            m.service.progress(player, type, what, amount);
        }
    }

    // ------------------------------------------------------------------ commands

    @Command({"pass", "battlepass", "seasonpass", "bp"})
    @CommandPermission(USE)
    @Description("Open the Season Pass")
    public void pass(BukkitCommandActor actor) {
        menus.openPass(actor.requirePlayer(), -1);
    }

    @Command({"pass challenges", "battlepass challenges", "seasonpass challenges", "bp challenges"})
    @CommandPermission(USE)
    @Description("See today's and this week's pass challenges")
    public void challenges(BukkitCommandActor actor) {
        menus.openChallenges(actor.requirePlayer());
    }

    @Command({"pass box", "battlepass box", "seasonpass box", "bp box"})
    @CommandPermission(USE)
    @Description("Collect pass rewards that didn't fit in your inventory")
    public void collect(BukkitCommandActor actor) {
        menus.collect(actor.requirePlayer());
    }

    @Command("pass progress")
    @CommandPermission(ADMIN)
    @Description("Count progress toward a player's pass challenges (for hooks in other plugins' commands)")
    public void progressCommand(BukkitCommandActor actor, @Named("player") String name, @Named("type") String type,
                                @Named("amount") long amount, @Optional @Named("filter") @Nullable String filter) {
        CommandSender sender = actor.sender();
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Msg.bad("That player is not online."));
            return;
        }
        Objective objective;
        try {
            objective = Objective.valueOf(type.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Msg.bad("Unknown type. Use one of: " + java.util.Arrays.toString(Objective.values())));
            return;
        }
        if (amount <= 0 || amount > 1_000_000) {
            sender.sendMessage(Msg.bad("Amount must be 1 to 1,000,000."));
            return;
        }
        progress(target.getUniqueId(), objective, filter, amount);
        plugin.getLogger().info("[seasonpass] " + sender.getName() + " added " + amount + " " + objective + " progress to " + target.getName());
    }

    @Command("pass xp give")
    @CommandPermission(ADMIN)
    @Description("Give pass XP to an online player")
    public void xpGive(BukkitCommandActor actor, @Named("player") String name, @Named("amount") long amount) {
        xp(actor.sender(), name, amount, false);
    }

    @Command("pass xp set")
    @CommandPermission(ADMIN)
    @Description("Set an online player's pass XP")
    public void xpSet(BukkitCommandActor actor, @Named("player") String name, @Named("amount") long amount) {
        xp(actor.sender(), name, amount, true);
    }

    private void xp(CommandSender sender, String name, long amount, boolean set) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Msg.bad("That player is not online."));
            return;
        }
        if (amount < (set ? 0 : 1) || amount > 100_000_000L) {
            sender.sendMessage(Msg.bad("Amount must be " + (set ? 0 : 1) + " to 100,000,000."));
            return;
        }
        if (!service.addXp(target.getUniqueId(), amount, set)) {
            sender.sendMessage(Msg.bad("Their pass is not loaded yet."));
            return;
        }
        plugin.getLogger().info("[seasonpass] " + sender.getName() + (set ? " set " : " gave ") + amount + " pass XP " + (set ? "for " : "to ") + target.getName());
        sender.sendMessage(Msg.good((set ? "Set " : "Gave ") + Msg.white(amount + " XP") + (set ? " for " : " to ") + Msg.escape(target.getName()) + "."));
    }
}
