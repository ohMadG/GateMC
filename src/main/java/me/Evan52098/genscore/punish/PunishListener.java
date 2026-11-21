package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;

import java.util.Locale;
import java.util.Set;

final class PunishListener implements Listener {
    private static final Set<String> BLOCKED_PUNISH_COMMANDS = Set.of(
            "kick", "kickall", "ban", "ban-ip", "pardon", "pardon-ip", "banlist");

    private final PunishmentService punishmentService;
    private final String appealUrl;

    PunishListener(Warden warden, String appealUrl) {
        this.punishmentService = warden.punishments();
        this.appealUrl = appealUrl;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        String withoutSlash = event.getMessage().substring(1);
        String label = withoutSlash.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (BLOCKED_PUNISH_COMMANDS.contains(label)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(Msg.bad("Use /punish <player> <reason> instead."));
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String ip = event.getAddress() == null ? null : event.getAddress().getHostAddress();
        PunishmentData ipBan;
        PunishmentData ban;
        try {
            ipBan = ip == null ? null : punishmentService.findActiveIpBan(ip).join();
            ban = ipBan != null ? null : punishmentService.getActive(event.getUniqueId(), PunishmentService.TYPE_BAN).join();
        } catch (RuntimeException e) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, Msg.bad("Your login could not be checked right now. Please try again in a moment."));
            return;
        }
        if (ipBan != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, PunishMessages.banMessage(ipBan, appealUrl));
            return;
        }
        if (ban != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, PunishMessages.banMessage(ban, appealUrl));
            return;
        }
        try {
            punishmentService.getActiveMuteState(event.getUniqueId()).join();
        } catch (RuntimeException ignored) {
        }
        if (ip != null) {
            punishmentService.recordLogin(event.getUniqueId(), ip);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PunishmentData mute = punishmentService.cachedMuteState(player.getUniqueId()).mute();
        if (mute != null) {
            Tasks.onEntity(player, () -> player.sendMessage(PunishMessages.muteMessage(mute, appealUrl)));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        Player player = event.getPlayer();
        if (punishmentService.isMutePending(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(Msg.bad("You are muted."));
            return;
        }
        PunishmentService.MuteState state = punishmentService.cachedMuteState(player.getUniqueId());
        if (state.mute() != null) {
            event.setCancelled(true);
            player.sendMessage(PunishMessages.muteMessage(state.mute(), appealUrl));
        } else if (state.shadowMute() != null) {
            for (int i = 0; i < event.getLines().length; i++) {
                event.line(i, Component.empty());
            }
        }
    }
}
