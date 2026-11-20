package me.Evan52098.genscore.store;

import me.Evan52098.genscore.economy.EconomyModule;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.module.ModuleManager;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Hands out a {@link StoreSettings.Reward} to everyone online (community goal, store party). Runs on the global
 * thread, where Folia lets console commands run; per-player messages hop to each player's own thread.
 */
final class StoreRewards {

    /** Names safe to put into a console command (Java names, plus Floodgate's "." prefix). Blocks command injection. */
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9_.\\-]{1,32}");

    private final ModuleManager modules;
    private final Logger log;

    StoreRewards(ModuleManager modules, Logger log) {
        this.modules = modules;
        this.log = log;
    }

    /**
     * Gives the reward. {@code onePerIp} pays one account per IP address, so a buyer can't multiply a party with alts
     * (checklist 10). Returns at once; the work runs on the global thread.
     */
    void give(StoreSettings.Reward reward, String reason, boolean onePerIp, @Nullable String notice) {
        Tasks.global.run(() -> {
            for (String cmd : reward.consoleCommands()) {
                dispatch(cmd, reason);
            }
            Set<String> ips = new HashSet<>();
            List<Player> paid = new ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (onePerIp) {
                    InetSocketAddress addr = p.getAddress();
                    String ip = addr == null || addr.getAddress() == null ? null : addr.getAddress().getHostAddress();
                    if (ip != null && !ips.add(ip)) {
                        continue;
                    }
                }
                String name = p.getName();
                if (!SAFE_NAME.matcher(name).matches()) {
                    log.warning("[store] Skipped " + reason + " commands for a player whose name can't be put in a command safely.");
                } else {
                    for (String cmd : reward.playerCommands()) {
                        dispatch(cmd.replace("{player}", name), reason);
                    }
                }
                if (reward.moneyCents() > 0) {
                    EconomyModule eco = modules.get(EconomyModule.class);
                    if (eco != null) {
                        // Money is created here, not moved: the ledger records it with the reason (checklist 11).
                        eco.service().deposit(p.getUniqueId(), reward.moneyCents(), reason);
                    }
                }
                paid.add(p);
            }
            if (notice != null && !notice.isEmpty()) {
                for (Player p : paid) {
                    Tasks.onEntity(p, () -> p.sendMessage(Text.legacy(notice)));
                }
            }
            log.info("[store] " + reason + ": rewarded " + paid.size() + " online player(s).");
        });
    }

    private void dispatch(String command, String reason) {
        try {
            if (!Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command)) {
                log.warning("[store] " + reason + ": unknown command '" + command + "'");
            }
        } catch (RuntimeException e) {
            log.warning("[store] " + reason + ": command '" + command + "' failed: " + e);
        }
    }
}
