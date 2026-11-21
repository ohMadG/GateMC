package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.staff.StaffModule;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.sus.SusModule;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.UUID;

/**
 * SEBStaff's staff mode plus HxCore's staff extras: /staffmode, owner super-vanish, /allhomes and the hidden-alts list.
 * Needs the staff module (vanish, freeze); without it the module refuses to enable.
 */
public final class StaffModeModule implements GensModule {

    private GensCore plugin;
    private StaffModeService service;
    private static volatile StaffModeModule instance;
    private volatile int openReports;
    private @Nullable me.Evan52098.genscore.staff.StaffTools tools;

    @Override
    public String id() {
        return "staffmode";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        StaffModule staff = plugin.modules().get(StaffModule.class);
        if (staff == null || staff.vanish() == null) {
            throw new IllegalStateException("staffmode needs the staff module (vanish, freeze); turn modules.staff on");
        }
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_staffmode_saved (uuid TEXT PRIMARY KEY, contents TEXT NOT NULL, "
                        + "gamemode TEXT NOT NULL, allow_flight INTEGER NOT NULL, flying INTEGER NOT NULL, "
                        + "was_vanished INTEGER NOT NULL, at BIGINT NOT NULL)");

        VanishBridge vanish = new VanishBridge(staff.vanish(), plugin.getLogger());
        service = new StaffModeService(plugin, plugin.database(), plugin.getLogger(), vanish, new DebugInfo(plugin.getLogger()));
        service.hooks(this::gameBlock, this::leaveGame, this::rejoinGame);
        SuperVanish superVanish = new SuperVanish(plugin, vanish);
        HiddenAlts.load(plugin.getConfig().getStringList("staffmode.hidden-alts"), plugin.getLogger());

        Bukkit.getPluginManager().registerEvents(new StaffModeListener(service, staff.tools(),
                plugin.modules().get(SusModule.class)).menus(new StaffMenus(plugin, service, staff.tools())), plugin);
        Bukkit.getPluginManager().registerEvents(superVanish, plugin);
        plugin.commands().register(this);
        if (plugin.getConfig().getBoolean("teleport.homes.enabled", true)) {
            plugin.commands().register(new AllHomes(plugin.database(), plugin.playerData(), plugin.getLogger()));
        }
        Tasks.global.timer(t -> superVanish.reconcile(), 40, 20);
        this.tools = staff.tools();
        instance = this;
        Tasks.global.timer(t -> actionBars(), 40, 30);
        Tasks.async.timer(t -> countReports(), 5, 30, java.util.concurrent.TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        HiddenAlts.load(plugin.getConfig().getStringList("staffmode.hidden-alts"), plugin.getLogger());
    }

    @Override
    public void disable() {
        if (service != null) {
            service.leaveAll();
        }
    }

    private @Nullable String gameBlock(Player player) {
        me.Evan52098.genscore.factory.FactoryModule factory = plugin.modules().get(me.Evan52098.genscore.factory.FactoryModule.class);
        return factory == null ? null : factory.staffModeBlock(player);
    }

    private void leaveGame(Player player) {
        me.Evan52098.genscore.plots.PlotsModule plots = plugin.modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        if (plots == null || plots.service() == null) {
            return;
        }
        plots.service().removeNow(player.getUniqueId());
        player.sendMessage(Msg.neutral("Your factory is offline until you leave staff mode."));
    }

    private void rejoinGame(Player player) {
        me.Evan52098.genscore.plots.PlotsModule plots = plugin.modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        if (plots == null || plots.service() == null) {
            return;
        }
        var service = plots.service();
        service.create(player.getUniqueId()).whenComplete((plot, error) -> Tasks.entity.run(player, () -> {
            if (!player.isOnline()) {
                return;
            }
            if (error != null) {
                player.sendMessage(Msg.bad("Your factory could not be rebuilt. Try " + Msg.white("/plot") + " in a moment."));
                return;
            }
            player.teleportAsync(service.spawnOf(plot), org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.PLUGIN);
            player.sendMessage(Msg.good("Your factory is back online."));
        }));
    }

    private void actionBars() {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (!service.inStaffMode(p.getUniqueId())) {
                continue;
            }
            Tasks.entity.run(p, () -> {
                boolean hidden = service.vanish().isVanished(p);
                p.sendActionBar(Msg.parse("<aqua><bold>STAFF MODE</bold></aqua> <dark_gray>|</dark_gray> "
                        + (hidden ? "<green>Vanished</green>" : "<red>Visible</red>")));
            });
        }
    }

    private void countReports() {
        try {
            openReports = plugin.database().sync(c -> {
                try (var ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_reports WHERE open = 1");
                     var rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            });
        } catch (java.sql.SQLException | RuntimeException ignored) {
            openReports = -1;
        }
    }

    public static @Nullable String placeholder(@Nullable Player p, String key) {
        StaffModeModule m = instance;
        if (m == null || !key.startsWith("staff")) {
            return null;
        }
        return switch (key) {
            case "staffmode" -> p != null && m.service.inStaffMode(p.getUniqueId()) ? "yes" : "no";
            case "staff_vanished" -> p != null && m.service.vanish().isVanished(p) ? "&aVanished" : "&cVisible";
            case "staff_players" -> String.valueOf(Bukkit.getOnlinePlayers().size());
            case "staff_online" -> String.valueOf(Bukkit.getOnlinePlayers().stream().filter(x -> x.hasPermission(StaffModeService.PERM)).count());
            case "staff_inmode" -> String.valueOf(Bukkit.getOnlinePlayers().stream().filter(x -> m.service.inStaffMode(x.getUniqueId())).count());
            case "staff_bedrock" -> String.valueOf(Bukkit.getOnlinePlayers().stream().filter(me.Evan52098.genscore.util.Bedrock::is).count());
            case "staff_frozen" -> String.valueOf(m.tools == null ? 0 : m.tools.frozenCount());
            case "staff_reports" -> m.openReports < 0 ? "?" : String.valueOf(m.openReports);
            default -> null;
        };
    }

    /** True while the player is in staff mode. Safe from any thread. */
    public boolean inStaffMode(UUID player) {
        return service.inStaffMode(player);
    }

    @Command({"staffmode", "sm", "mod"})
    @CommandPermission(StaffModeService.PERM)
    @Description("Toggle staff mode, optionally teleporting to a player")
    public void staffMode(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        Player player = actor.requirePlayer();
        if (target == null) {
            if (!service.leave(player, false)) {
                service.enter(player, null);
            }
            return;
        }
        if (target.equals(player)) {
            player.sendMessage(Msg.bad("You can't teleport to yourself."));
            return;
        }
        service.enter(player, () -> Tasks.entity.run(target, () -> {
            Location at = target.getLocation();
            Tasks.entity.run(player, () -> player.teleportAsync(at).thenAccept(ok -> {
                if (ok) {
                    player.sendMessage(Msg.good("Teleported to " + Msg.white(target.getName()) + "."));
                }
            }));
        }));
    }
}
