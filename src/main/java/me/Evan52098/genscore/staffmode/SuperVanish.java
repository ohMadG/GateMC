package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Owner super-vanish (HxCore {@code hxcore.supervanish}): a vanished player with {@code genscore.supervanish} is also
 * hidden from staff who can normally see vanished players, unless they have {@code genscore.supervanish.see}.
 *
 * <p>The staff module's vanish shows vanished players to {@code genscore.vanish.see} holders; this re-hides
 * super-vanished ones from them. It runs on join, right after a /vanish command, and once a second as a safety net.
 * Each hide runs on the viewer's thread (Folia). Unvanishing is left to the staff module, which shows the player to
 * everyone.
 */
final class SuperVanish implements Listener {

    static final String PERM = "genscore.supervanish";
    static final String SEE = "genscore.supervanish.see";

    private final Plugin plugin;
    private final VanishBridge vanish;

    SuperVanish(Plugin plugin, VanishBridge vanish) {
        this.plugin = plugin;
        this.vanish = vanish;
    }

    /** Hides every super-vanished player from every viewer who may not see them. Safe from the global thread. */
    void reconcile() {
        List<Player> hidden = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (vanish.isVanished(p) && p.hasPermission(PERM)) {
                hidden.add(p);
            }
        }
        if (hidden.isEmpty()) {
            return;
        }
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.hasPermission(SEE)) {
                hideFrom(viewer, hidden);
            }
        }
    }

    private void hideFrom(Player viewer, List<Player> hidden) {
        Tasks.onEntity(viewer, () -> {
            for (Player h : hidden) {
                if (!h.equals(viewer) && viewer.canSee(h) && vanish.isVanished(h)) {
                    viewer.hidePlayer(plugin, h);
                }
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        // The joining player may be a viewer, or the owner themself (the staff module re-vanishes them on join).
        Tasks.global.later(this::reconcile, 2);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.equals("vanish") || label.equals("v") || label.endsWith(":vanish") || label.equals("staffmode")) {
            Tasks.global.later(this::reconcile, 2);
        }
    }
}
