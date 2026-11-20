package me.Evan52098.genscore.daily;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

/**
 * Daily rewards: /daily (the Daily Rewards menu with the Daily Strike and, with the crates module on, the Daily
 * Lockbox), the optional dig-site block, and the join reminder. Ported from BlockvilleCore's dailyreward package,
 * DailyRewardCommand, DailyRewardAdminCommand, DailyRewardGui, DailyHubGui, DailyRewardJoinReminderListener and
 * DailyRewardClaimListener. Settings are under {@code daily} in config.yml.
 */
public final class DailyModule implements GensModule, Listener {

    private static final int MAX_REACH_DISTANCE = 6;

    private GensCore plugin;
    private DailyRewardService service;
    private DailyHubGui hub;
    private DailyClaimBlock claimBlock;

    @Override
    public String id() {
        return "daily";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        Migrations.apply(plugin.database(), id(), plugin.getLogger(), DailyRewardService.schema());
        plugin.playerData().register(DailyStats.SECTION);
        service = new DailyRewardService(plugin, config());
        DailyRewardGui strike = new DailyRewardGui(plugin, service);
        hub = new DailyHubGui(plugin, service, strike);
        strike.setHub(hub);
        claimBlock = new DailyClaimBlock(plugin, strike);
        claimBlock.load();
        Bukkit.getPluginManager().registerEvents(claimBlock, plugin);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
    }

    private DailyRewardConfig config() {
        return DailyRewardConfig.from(plugin.getConfig().getConfigurationSection("daily"), plugin.getLogger());
    }

    @Override
    public void reload() {
        if (service != null) {
            service.config(config());
            claimBlock.load();
        }
    }

    @Override
    public void disable() {
        if (service != null) {
            service.shutdown();
        }
        if (claimBlock != null) {
            claimBlock.despawnLabel();
        }
    }

    // ------------------------------------------------------------------ commands

    @Command({"daily", "dailyreward", "claim"})
    @CommandPermission("genscore.command.daily")
    @Description("Open the daily rewards menu")
    public void daily(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (!service.config().enabled()) {
            player.sendMessage(Msg.bad("Daily rewards are turned off right now."));
            return;
        }
        hub.open(player);
    }

    @Command("dailyreward setblock")
    @CommandPermission("genscore.admin.dailyreward")
    @Description("Set the physical Daily Strike dig site to the block you're looking at")
    public void setBlock(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        Block target = player.getTargetBlockExact(MAX_REACH_DISTANCE);
        if (target == null || target.getType().isAir()) {
            player.sendMessage(Msg.bad("Look at a solid block within " + MAX_REACH_DISTANCE + " blocks first."));
            return;
        }
        claimBlock.setLocation(target);
        player.sendMessage(Msg.good("Daily Strike dig site set at <white>" + target.getX() + ", " + target.getY() + ", " + target.getZ() + "</white>."));
    }

    @Command("dailyreward removeblock")
    @CommandPermission("genscore.admin.dailyreward")
    @Description("Remove the physical Daily Strike dig site, if one is set")
    public void removeBlock(BukkitCommandActor actor) {
        claimBlock.clearLocation();
        actor.sender().sendMessage(Msg.good("Daily Strike dig site removed."));
    }

    // ------------------------------------------------------------------ join

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        // Checklist 5 and 7: rewards recorded but not given before a crash or stop are given now.
        service.deliverPending(player.getUniqueId(), player.getName());
        // The reminder (BlockvilleCore's DailyRewardJoinReminderListener), a moment after the join messages.
        Tasks.entity.later(player, () -> {
            if (!service.config().enabled() || !player.hasPermission("genscore.command.daily")) {
                return;
            }
            DailyRewardService.EligibilityInfo info = service.peek(player.getUniqueId());
            if (info.ready() && info.eligible()) {
                player.sendMessage(Msg.neutral("Your daily reward is ready - <click:run_command:/daily><hover:show_text:'<gray>Click to open /daily'>"
                        + "<white><underlined>click here</underlined></white></hover></click> or use <white>/daily</white> to claim it."));
            }
        }, 40L);
    }
}
