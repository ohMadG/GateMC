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
        SuperVanish superVanish = new SuperVanish(plugin, vanish);
        HiddenAlts.load(plugin.getConfig().getStringList("staffmode.hidden-alts"), plugin.getLogger());

        Bukkit.getPluginManager().registerEvents(new StaffModeListener(service, staff.tools(),
                plugin.modules().get(SusModule.class)), plugin);
        Bukkit.getPluginManager().registerEvents(superVanish, plugin);
        plugin.commands().register(this, new AllHomes(plugin.database(), plugin.playerData(), plugin.getLogger()));
        Tasks.global.timer(t -> superVanish.reconcile(), 40, 20);
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
