package me.Evan52098.genscore.staff;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * /vanish. The GensCore version of blockville-common's bvvanish: hidden from everyone without genscore.vanish.see, no
 * join or quit message while vanished. Vanish lasts until the server restarts (also across relogs).
 *
 * <p>Folia: hiding is a change to the <i>viewer's</i> view, so each hide or show runs on the viewer's thread.
 */
public final class Vanish implements Listener {

    public static final String SEE = "genscore.vanish.see";

    private final Plugin plugin;
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    Vanish(Plugin plugin) {
        this.plugin = plugin;
    }

    public boolean isVanished(UUID player) {
        return vanished.contains(player);
    }

    @Command({"vanish", "v"})
    @CommandPermission("genscore.vanish")
    @Description("Hide from players")
    public void vanish(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        Player subject = target != null ? target : actor.requirePlayer();
        if (target != null && !actor.sender().hasPermission("genscore.vanish.others")) {
            actor.sender().sendMessage(Msg.bad("You don't have permission to do that."));
            return;
        }
        boolean now = !vanished.contains(subject.getUniqueId());
        set(subject, now);
        Tasks.onEntity(subject, () -> subject.sendMessage(Msg.good(now ? "You are now vanished." : "You are no longer vanished.")));
        if (target != null) {
            actor.sender().sendMessage(Msg.good((now ? "Vanished " : "Unvanished ") + Msg.white(subject.getName()) + "."));
        }
    }

    /** Vanishes or shows a player (staff mode uses this). Call on the player's thread. */
    public void setVanished(Player player, boolean hide) {
        set(player, hide);
    }

    private void set(Player subject, boolean hide) {
        if (hide) {
            vanished.add(subject.getUniqueId());
        } else {
            vanished.remove(subject.getUniqueId());
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(subject)) {
                continue;
            }
            Tasks.onEntity(viewer, () -> {
                if (hide && !viewer.hasPermission(SEE)) {
                    viewer.hidePlayer(plugin, subject);
                } else {
                    viewer.showPlayer(plugin, subject);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        Player joined = event.getPlayer();
        // The newcomer must not see anyone who is vanished (this runs on their thread).
        if (!joined.hasPermission(SEE)) {
            for (UUID id : vanished) {
                Player hidden = Bukkit.getPlayer(id);
                if (hidden != null) {
                    joined.hidePlayer(plugin, hidden);
                }
            }
        }
        if (vanished.contains(joined.getUniqueId())) {
            event.joinMessage(null);
            set(joined, true);
            joined.sendMessage(Msg.neutral("You are still vanished."));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onQuit(PlayerQuitEvent event) {
        if (vanished.contains(event.getPlayer().getUniqueId())) {
            event.quitMessage(null);
        }
    }
}
