package me.Evan52098.genscore.sus;

import io.papermc.paper.event.player.PlayerArmSwingEvent;
import me.Evan52098.genscore.scheduler.Tasks;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.entity.EnderCrystal;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageAbortEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityResurrectEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The four /sus checks HXSMP had that the antihack module does not:
 * <ul>
 *   <li><b>auto-clicker</b>: left clicks (arm swings that are not block mining) above {@code max-cps}, held for
 *       {@code hold-millis}. Antihack's own CPS check has a fixed 30 CPS over one second; this one is configurable
 *       and needs the rate held.</li>
 *   <li><b>auto-crystal</b>: end crystals broken per second above {@code max-crystals-per-second}, held.</li>
 *   <li><b>auto-totem</b>: after an off-hand totem pops, a new totem lands in the off hand faster than
 *       {@code totem-min-ms} after subtracting ping, {@code totem-flags-needed} times within the window.</li>
 *   <li><b>nuker</b>: hard blocks broken per second above {@code max-blocks-broken-per-second}, held.</li>
 * </ul>
 * Each player's counters are touched from that player's thread or the region they stand in; they are synchronized
 * anyway, since a crystal or block can be owned by a neighbouring region on Folia.
 */
final class SusDetectors implements Listener {

    /** Events per second over a limit, held for a time. */
    static final class RateMeter {
        private final ArrayDeque<Long> times = new ArrayDeque<>();
        private long overSince;

        /** Records one event; returns the rate when it has been above max for hold ms (then starts over), else -1. */
        synchronized int hit(long now, int max, long hold) {
            times.addLast(now);
            while (!times.isEmpty() && now - times.peekFirst() > 1000) {
                times.pollFirst();
            }
            int rate = times.size();
            if (rate <= max) {
                overSince = 0;
                return -1;
            }
            if (overSince == 0) {
                overSince = now;
                return -1;
            }
            if (now - overSince >= hold) {
                overSince = 0;
                return rate;
            }
            return -1;
        }
    }

    private static final class State {
        final RateMeter clicks = new RateMeter();
        final RateMeter crystals = new RateMeter();
        final RateMeter blocks = new RateMeter();
        volatile boolean digging;
        volatile long totemPopAt;        // ms; 0 = none pending
        final ArrayDeque<Long> fastTotems = new ArrayDeque<>();
    }

    private final SusService sus;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    SusDetectors(SusService sus) {
        this.sus = sus;
    }

    private State state(Player p) {
        return states.computeIfAbsent(p.getUniqueId(), id -> new State());
    }

    private static boolean playing(Player p) {
        GameMode m = p.getGameMode();
        return m == GameMode.SURVIVAL || m == GameMode.ADVENTURE;
    }

    // ------------------------------------------------------------------ auto-clicker

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwing(PlayerArmSwingEvent event) {
        SusSettings s = sus.settings();
        Player p = event.getPlayer();
        if (!s.autoClicker() || event.getHand() != EquipmentSlot.HAND || !playing(p)) {
            return;
        }
        State st = state(p);
        if (st.digging) {
            return; // mining swings every tick, that is not clicking
        }
        int rate = st.clicks.hit(System.currentTimeMillis(), s.maxCps(), s.holdMillis());
        if (rate > 0) {
            sus.flag(p, SusService.Check.AUTO_CLICKER, rate + " CPS for " + s.holdMillis() / 1000.0 + "s");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigStart(BlockDamageEvent event) {
        state(event.getPlayer()).digging = !event.getInstaBreak();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDigAbort(BlockDamageAbortEvent event) {
        state(event.getPlayer()).digging = false;
    }

    // ------------------------------------------------------------------ nuker

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Player p = event.getPlayer();
        State st = state(p);
        st.digging = false;
        SusSettings s = sus.settings();
        if (!s.nuker() || !playing(p)) {
            return;
        }
        Material type = event.getBlock().getType();
        if (type.getHardness() < s.nukerMinHardness()) {
            return; // only "hard" blocks: grass, flowers and crops break instantly by hand
        }
        int rate = st.blocks.hit(System.currentTimeMillis(), s.maxBlocksPerSecond(), s.holdMillis());
        if (rate > 0) {
            sus.flag(p, SusService.Check.NUKER, rate + " blocks/s");
        }
    }

    // ------------------------------------------------------------------ auto-crystal

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onCrystal(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof EnderCrystal)) {
            return;
        }
        Entity causing = event.getDamageSource().getCausingEntity();
        Player p = causing instanceof Player cp ? cp : event.getDamager() instanceof Player dp ? dp : null;
        SusSettings s = sus.settings();
        if (p == null || !s.autoCrystal() || !playing(p)) {
            return;
        }
        int rate = state(p).crystals.hit(System.currentTimeMillis(), s.maxCrystalsPerSecond(), s.holdMillis());
        if (rate > 0) {
            sus.flag(p, SusService.Check.AUTO_CRYSTAL, rate + " crystals/s");
        }
    }

    // ------------------------------------------------------------------ auto-totem

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPop(EntityResurrectEvent event) {
        if (event.getEntity() instanceof Player p && event.getHand() == EquipmentSlot.OFF_HAND
                && sus.settings().autoTotem() && playing(p)) {
            state(p).totemPopAt = System.currentTimeMillis();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            refillAttempt(p);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        refillAttempt(event.getPlayer());
    }

    /** An inventory action right after a pop: one tick later, see if it put a totem in the off hand, and how fast. */
    private void refillAttempt(Player p) {
        State st = states.get(p.getUniqueId());
        if (st == null) {
            return;
        }
        long popAt = st.totemPopAt;
        long now = System.currentTimeMillis();
        if (popAt == 0) {
            return;
        }
        if (now - popAt > 3000) {
            st.totemPopAt = 0;
            return;
        }
        int ping = p.getPing();
        Tasks.entity.later(p, () -> {
            ItemStack off = p.getInventory().getItemInOffHand();
            if (off.getType() != Material.TOTEM_OF_UNDYING || st.totemPopAt != popAt) {
                return;
            }
            st.totemPopAt = 0;
            SusSettings s = sus.settings();
            long reaction = (now - popAt) - ping;
            if (reaction >= s.totemMinMs()) {
                return;
            }
            int count;
            synchronized (st.fastTotems) {
                long t = System.currentTimeMillis();
                st.fastTotems.addLast(t);
                while (!st.fastTotems.isEmpty() && t - st.fastTotems.peekFirst() > s.totemWindowMillis()) {
                    st.fastTotems.pollFirst();
                }
                count = st.fastTotems.size();
                if (count >= s.totemFlagsNeeded()) {
                    st.fastTotems.clear();
                }
            }
            if (count >= s.totemFlagsNeeded()) {
                sus.flag(p, SusService.Check.AUTO_TOTEM, "refill " + Math.max(0, reaction) + "ms after ping, " + count + " times");
            }
        }, 1);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        states.remove(event.getPlayer().getUniqueId());
    }
}
