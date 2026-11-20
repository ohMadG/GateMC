package me.Evan52098.genscore.keycrates;

import com.destroystokyo.paper.event.inventory.PrepareResultEvent;
import org.bukkit.block.Crafter;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.CrafterCraftEvent;
import org.bukkit.event.inventory.FurnaceBurnEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.inventory.ItemStack;

/**
 * Keys stay keys (checklist 9): they cannot be placed (tripwire hooks are blocks), renamed or combined in an anvil,
 * used in any workstation (smithing, grindstone, stonecutter, loom, cartography), crafted with (a tripwire hook makes
 * crossbows and trapped chests), fed to a crafter, or burnt as fuel. Renaming would not make a fake key anyway (keys
 * are recognised by their hidden tag only), but it would let a real key look like something else.
 */
final class KeyGuard implements Listener {

    private final KeyItems keys;

    KeyGuard(KeyItems keys) {
        this.keys = keys;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (keys.isKey(event.getItemInHand())) {
            event.setCancelled(true);
        }
    }

    /** Anvil, grindstone, smithing table, stonecutter, loom and cartography table (Paper fires this for all of them). */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareResult(PrepareResultEvent event) {
        if (containsKey(event.getInventory().getContents())) {
            event.setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPrepareCraft(PrepareItemCraftEvent event) {
        if (containsKey(event.getInventory().getMatrix())) {
            event.getInventory().setResult(null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCrafter(CrafterCraftEvent event) {
        if (event.getBlock().getState(false) instanceof Crafter crafter && containsKey(crafter.getInventory().getContents())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFuel(FurnaceBurnEvent event) {
        if (keys.isKey(event.getFuel())) {
            event.setCancelled(true);
        }
    }

    private boolean containsKey(ItemStack[] items) {
        if (items == null) {
            return false;
        }
        for (ItemStack s : items) {
            if (keys.isKey(s)) {
                return true;
            }
        }
        return false;
    }
}
