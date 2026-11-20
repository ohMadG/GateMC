package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.staff.StaffTools;
import me.Evan52098.genscore.sus.SusModule;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Staff mode hotbar tools, the look-only rules, the /sethome block, F3, and restore on join, quit and death. */
final class StaffModeListener implements Listener {

    private static final Set<String> SETHOME = Set.of("sethome", "createhome", "homeset", "addhome");

    private final StaffModeService service;
    private final @Nullable StaffTools tools;
    private final @Nullable SusModule sus;
    private final Cooldowns clickCooldown = new Cooldowns();

    StaffModeListener(StaffModeService service, @Nullable StaffTools tools, @Nullable SusModule sus) {
        this.service = service;
        this.tools = tools;
        this.sus = sus;
    }

    private boolean in(Player p) {
        return service.inStaffMode(p.getUniqueId());
    }

    private void cancelIf(Player p, Cancellable event) {
        if (in(p)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ join, quit, death

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        service.leave(event.getPlayer(), true); // before the player file is written (checklist 5)
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (in(event.getEntity())) {
            event.getDrops().clear(); // only staff tools; the real items are in the saved row
            event.setKeepInventory(true);
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        if (in(p)) {
            Tasks.entity.later(p, () -> {
                if (in(p) && !p.isDead()) {
                    service.leave(p, false);
                }
            }, 1);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        reapplyDebug(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawnDebug(PlayerRespawnEvent event) {
        reapplyDebug(event.getPlayer());
    }

    private void reapplyDebug(Player p) {
        if (in(p) && !p.hasPermission(StaffModeService.BYPASS)) {
            Tasks.entity.later(p, () -> {
                if (in(p)) {
                    service.debug().apply(p, true);
                }
            }, 2);
        }
    }

    // ------------------------------------------------------------------ /sethome

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player p = event.getPlayer();
        if (!in(p) || p.hasPermission(StaffModeService.BYPASS)) {
            return;
        }
        String label = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = label.indexOf(':');
        if (colon >= 0) {
            label = label.substring(colon + 1);
        }
        if (SETHOME.contains(label)) {
            event.setCancelled(true);
            p.sendMessage(Msg.bad("You can't set homes in staff mode."));
        }
    }

    // ------------------------------------------------------------------ look-only rules (checklist 4)

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamaged(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDealDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (in(event.getPlayer()) || service.toolOf(event.getItemDrop().getItemStack()) != null) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            cancelIf(p, event); // look only: nothing can move into or out of the staff inventory
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        cancelIf(event.getPlayer(), event);
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (event.getTarget() instanceof Player p) {
            cancelIf(p, event);
        }
    }

    // ------------------------------------------------------------------ hotbar tools

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUse(PlayerInteractEvent event) {
        Player p = event.getPlayer();
        if (!in(p)) {
            return;
        }
        if (event.getAction() == Action.PHYSICAL) {
            event.setCancelled(true); // no pressure plates or tripwires
            return;
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String tool = service.toolOf(event.getItem());
        if (tool == null) {
            return;
        }
        event.setCancelled(true);
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (!clickCooldown.tryStart(p.getUniqueId(), "tool", 250)) {
            return;
        }
        switch (tool) {
            case "random" -> randomTeleport(p);
            case "vanish" -> {
                boolean now = !service.vanish().isVanished(p);
                p.sendMessage(service.vanish().set(p, now)
                        ? Msg.good(now ? "You are now vanished." : "You are now visible.")
                        : Msg.bad("Vanish is not available."));
            }
            default -> p.sendMessage(Msg.neutral("Right-click a player with this."));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onUseOnPlayer(PlayerInteractEntityEvent event) {
        Player p = event.getPlayer();
        if (!in(p)) {
            return;
        }
        event.setCancelled(true); // no trading, riding or naming in staff mode
        if (event.getHand() != EquipmentSlot.HAND || !(event.getRightClicked() instanceof Player target)) {
            return;
        }
        String tool = service.toolOf(p.getInventory().getItemInMainHand());
        if (tool == null || !clickCooldown.tryStart(p.getUniqueId(), "tool", 250)) {
            return;
        }
        switch (tool) {
            case "freeze" -> {
                if (tools == null) {
                    p.sendMessage(Msg.bad("Freeze is not available (staff module off)."));
                } else if (target.hasPermission("genscore.freeze.exempt")) {
                    p.sendMessage(Msg.bad("You can't freeze that player."));
                } else if (tools.isFrozen(target.getUniqueId())) {
                    tools.unfreeze(target.getUniqueId());
                } else {
                    tools.freeze(target, p.getName(), "staff mode");
                }
            }
            case "invsee" -> p.performCommand("invsee " + target.getName());
            case "inspect" -> inspect(p, target);
            default -> {
            }
        }
    }

    private void randomTeleport(Player staff) {
        List<Player> candidates = new ArrayList<>();
        for (Player other : Bukkit.getOnlinePlayers()) {
            if (!other.equals(staff) && !service.inStaffMode(other.getUniqueId()) && !service.vanish().isVanished(other)) {
                candidates.add(other);
            }
        }
        if (candidates.isEmpty()) {
            staff.sendMessage(Msg.neutral("Nobody else to teleport to."));
            return;
        }
        Player target = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
        // Folia: the target's location is read on their thread, the teleport runs on the staff member's.
        Tasks.entity.run(target, () -> {
            Location at = target.getLocation();
            Tasks.entity.run(staff, () -> staff.teleportAsync(at).thenAccept(ok -> {
                if (ok) {
                    staff.sendMessage(Msg.good("Teleported to " + Msg.white(target.getName()) + "."));
                }
            }));
        });
    }

    /** Called on the staff member's thread; the target was right-clicked, so it is in the same region. */
    private void inspect(Player staff, Player target) {
        Location l = target.getLocation();
        var max = target.getAttribute(Attribute.MAX_HEALTH);
        staff.sendMessage(Msg.parse("<gold>Inspect <white>" + target.getName()));
        staff.sendMessage(Msg.parse("<gray>Health: <white>" + Math.round(target.getHealth()) + "/" + Math.round(max != null ? max.getValue() : 20)
                + "</white>  Food: <white>" + target.getFoodLevel() + "</white>  Ping: <white>" + target.getPing() + "ms"));
        staff.sendMessage(Msg.parse("<gray>Mode: <white>" + target.getGameMode().name().toLowerCase(Locale.ROOT)
                + "</white>  World: <white>" + Msg.escape(l.getWorld().getName()) + "</white>  At: <white>"
                + l.getBlockX() + " " + l.getBlockY() + " " + l.getBlockZ()));
        staff.sendMessage(Msg.parse("<gray>Frozen: <white>" + (tools != null && tools.isFrozen(target.getUniqueId()) ? "yes" : "no")
                + "</white>  Sus flags: <white>" + (sus != null ? sus.flags(target) : 0)));
    }
}
