package me.Evan52098.genscore.antihack;

import com.destroystokyo.paper.event.server.AsyncTabCompleteEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * BlockvilleCore's PacketDetector counted raw client packets with ProtocolLib. GensCore has no packet library on its
 * classpath (ProtocolLib is only partly Folia-ready; packetevents would be the choice), so this version counts the same
 * things through Paper events, which see one event per accepted packet:
 * <ul>
 *   <li>moves per second (PlayerMoveEvent) - TIMER after 3 seconds in a row above 25,</li>
 *   <li>arm swings (PlayerAnimationEvent), sneak/sprint toggles (entity actions) and tab completes - BAD_PACKETS.</li>
 * </ul>
 * Not possible without a packet library: custom-payload spam (CRASH) and a client claiming to fly in the abilities
 * packet. GrimAC, the main anticheat, covers both.
 *
 * <p>Folia: counters are atomics (the tab-complete event is async); judging runs once a second on each player's own
 * thread through {@link AntiHackService#everySecond}.
 */
final class PacketDetector implements Listener {

    private static final int MOVES_PER_SECOND = 25;
    private static final int ARM_PER_SECOND = 60;
    private static final int ACTION_PER_SECOND = 30;
    private static final int TAB_PER_SECOND = 12;

    private final AntiHackService service;
    private final Map<UUID, Counts> counts = new ConcurrentHashMap<>();

    private static final class Counts {
        final AtomicInteger moves = new AtomicInteger();
        final AtomicInteger arm = new AtomicInteger();
        final AtomicInteger action = new AtomicInteger();
        final AtomicInteger tab = new AtomicInteger();
        int fastSeconds;
    }

    PacketDetector(AntiHackService service) {
        this.service = service;
        service.everySecond(this::judge);
    }

    private Counts counts(UUID id) {
        return counts.computeIfAbsent(id, k -> new Counts());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        counts.put(event.getPlayer().getUniqueId(), new Counts());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        counts.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onMove(PlayerMoveEvent event) {
        counts(event.getPlayer().getUniqueId()).moves.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onArm(PlayerAnimationEvent event) {
        counts(event.getPlayer().getUniqueId()).arm.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSneak(PlayerToggleSneakEvent event) {
        counts(event.getPlayer().getUniqueId()).action.incrementAndGet();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onSprint(PlayerToggleSprintEvent event) {
        counts(event.getPlayer().getUniqueId()).action.incrementAndGet();
    }

    /** Async thread: only an atomic counter is touched. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onTab(AsyncTabCompleteEvent event) {
        if (event.getSender() instanceof Player player) {
            counts(player.getUniqueId()).tab.incrementAndGet();
        }
    }

    /** Once a second, on the player's thread. */
    private void judge(Player player, PlayerTrack track) {
        Counts c = counts.get(player.getUniqueId());
        if (c == null) {
            return;
        }
        int moves = c.moves.getAndSet(0);
        int arm = c.arm.getAndSet(0);
        int action = c.action.getAndSet(0);
        int tab = c.tab.getAndSet(0);
        if (!service.shouldCheckAnyPing(player, track)) {
            c.fastSeconds = 0;
            return;
        }
        if (moves > MOVES_PER_SECOND) {
            if (++c.fastSeconds >= 3) {
                c.fastSeconds = 0;
                service.flag(player, track, AntiHackCheck.TIMER);
            }
        } else {
            c.fastSeconds = 0;
        }
        if (arm > ARM_PER_SECOND || action > ACTION_PER_SECOND || tab > TAB_PER_SECOND) {
            service.flag(player, track, AntiHackCheck.BAD_PACKETS);
        }
    }
}
