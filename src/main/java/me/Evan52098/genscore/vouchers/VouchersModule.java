package me.Evan52098.genscore.vouchers;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.EquipmentSlot;

/**
 * Vouchers: /voucher and right-click to redeem. Ported from BlockvilleCore's voucher package and the voucher bridge in
 * blockville-common ({@link VoucherBridge}). No config needed.
 */
public final class VouchersModule implements GensModule, Listener {

    private VoucherService service;

    @Override
    public String id() {
        return "vouchers";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Migrations.apply(plugin.database(), id(), plugin.getLogger(), VoucherService.schema(plugin.database()));
        service = new VoucherService(plugin);
        service.start();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(new VoucherCommands(service, plugin.getLogger()));
    }

    @Override
    public void disable() {
        if (service != null) {
            service.shutdown();
        }
    }

    /** For other modules that issue their own kinds of voucher. */
    public VoucherBridge bridge() {
        return service;
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        if (service.voucherIdOf(event.getItem()) == null) {
            return;
        }
        // Read before cancelling: setCancelled(true) also sets useItemInHand to DENY.
        boolean deniedElsewhere = event.useItemInHand() == Event.Result.DENY;
        // A voucher is never used as an item (placed, eaten, thrown), whichever hand it is in.
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND || deniedElsewhere) {
            return; // only the main hand redeems; an off-hand voucher just does nothing
        }
        service.redeem(event.getPlayer());
    }

    /** Checklist 5 and 7: returns copies taken during a failed redeem and gives rewards left owed by a stop or crash. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        service.onJoin(event.getPlayer());
    }
}
