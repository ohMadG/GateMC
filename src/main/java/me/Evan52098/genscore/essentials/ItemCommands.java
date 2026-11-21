package me.Evan52098.genscore.essentials;

import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Default;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Item and inventory commands: /i, /fix, /itemrename, /itemlore, /craft, /anvil, /disposal, /ec, /invsee.
 * Ported from BlockvilleCore with the same messages.
 *
 * <p>Folia: another player's live inventory can only be edited while they are on the viewer's region thread. When
 * they are not (far away), the viewer gets a read-only copy, and an edit view closes itself if the two drift apart.
 * {@code /give} and {@code /kill} keep their vanilla meaning; BlockvilleCore's overrides of them are not ported.
 */
public final class ItemCommands implements Listener {

    /** Marks a read-only copy (or the disposal bin, which simply deletes what is put in it). */
    private static final class ViewHolder implements InventoryHolder {
        private final boolean readOnly;
        private Inventory inventory;

        ViewHolder(boolean readOnly) {
            this.readOnly = readOnly;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private final long fixCooldownMillis;
    private final Cooldowns cooldowns = new Cooldowns();

    ItemCommands(long fixCooldownSeconds) {
        this.fixCooldownMillis = Math.max(0, fixCooldownSeconds) * 1000L;
    }

    // ------------------------------------------------------------------ /i

    @Command({"item", "i"})
    @CommandPermission("genscore.admin.give")
    @Description("Give yourself or a player an item")
    public void item(BukkitCommandActor actor, @Named("item") String itemName, @Named("amount") @Default("1") int amount,
                     @Named("player") @Optional @Nullable Player target) {
        Material material = Material.matchMaterial(itemName);
        if (material == null || !material.isItem() || material.isAir()) {
            actor.sender().sendMessage(Msg.bad("Unknown item: " + Msg.white(itemName) + "."));
            return;
        }
        Player subject = target != null ? target : actor.requirePlayer();
        int count = Math.max(1, Math.min(amount, 64 * 36));
        Tasks.onEntity(subject, () -> {
            Map<Integer, ItemStack> left = subject.getInventory().addItem(new ItemStack(material, count));
            left.values().forEach(stack -> subject.getWorld().dropItemNaturally(subject.getLocation(), stack));
        });
        actor.sender().sendMessage(Msg.good("Gave " + Msg.white(count + " x " + material.getKey().getKey()) + " to " + Msg.white(subject.getName()) + "."));
    }

    // ------------------------------------------------------------------ /fix

    @Command({"fix", "repair"})
    @CommandPermission("genscore.fix")
    @Description("Repair the item in your hand")
    public void fix(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir() || item.getType().getMaxDurability() <= 0 || !(item.getItemMeta() instanceof Damageable damageable)) {
            player.sendMessage(Msg.bad("That item cannot be repaired."));
            return;
        }
        if (damageable.getDamage() <= 0) {
            player.sendMessage(Msg.bad("That item is not damaged."));
            return;
        }
        boolean bypass = player.hasPermission("genscore.fix.bypasscooldown");
        long left = cooldowns.remaining(player.getUniqueId(), "fix");
        if (!bypass && left > 0) {
            player.sendMessage(Msg.bad("You must wait " + Msg.white(Cooldowns.format(left)) + " before using /fix again."));
            return;
        }
        damageable.setDamage(0);
        item.setItemMeta(damageable);
        if (!bypass) {
            cooldowns.start(player.getUniqueId(), "fix", fixCooldownMillis);
        }
        player.sendMessage(Msg.good("Your item has been repaired."));
    }

    // ------------------------------------------------------------------ rename and lore

    private static @Nullable ItemStack held(Player player) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            player.sendMessage(Msg.bad("You must be holding an item."));
            return null;
        }
        return item;
    }

    /** Colours (MiniMessage tags) only with genscore.itemrename.color; otherwise the text is taken literally. */
    private static Component styled(Player player, String text) {
        Component c = player.hasPermission("genscore.itemrename.color") ? Msg.parse(text) : Component.text(text);
        return c.decoration(TextDecoration.ITALIC, false);
    }

    @Command("itemrename")
    @CommandPermission("genscore.itemrename")
    @Description("Rename the item in your hand")
    public void itemRename(BukkitCommandActor actor, @Named("name") String name) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(styled(player, name));
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Item renamed to " + Msg.white(name) + "."));
    }

    @Command("itemrename reset")
    @CommandPermission("genscore.itemrename")
    public void itemRenameReset(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.displayName(null);
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Item name reset."));
    }

    private static List<Component> lore(ItemMeta meta) {
        List<Component> lore = meta.lore();
        return lore == null ? new ArrayList<>() : new ArrayList<>(lore);
    }

    @Command("itemlore add")
    @CommandPermission("genscore.itemlore")
    public void loreAdd(BukkitCommandActor actor, @Named("text") String text) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = lore(meta);
        if (lore.size() >= 32) {
            player.sendMessage(Msg.bad("That item can't hold any more lore lines."));
            return;
        }
        lore.add(styled(player, text));
        meta.lore(lore);
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Lore line added."));
    }

    @Command("itemlore set")
    @CommandPermission("genscore.itemlore")
    public void loreSet(BukkitCommandActor actor, @Named("line") int line, @Named("text") String text) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = lore(meta);
        if (line < 1 || line > lore.size()) {
            player.sendMessage(Msg.bad("Invalid line number."));
            return;
        }
        lore.set(line - 1, styled(player, text));
        meta.lore(lore);
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Lore line " + Msg.white(String.valueOf(line)) + " updated."));
    }

    @Command("itemlore remove")
    @CommandPermission("genscore.itemlore")
    public void loreRemove(BukkitCommandActor actor, @Named("line") int line) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        List<Component> lore = lore(meta);
        if (line < 1 || line > lore.size()) {
            player.sendMessage(Msg.bad("Invalid line number."));
            return;
        }
        lore.remove(line - 1);
        meta.lore(lore.isEmpty() ? null : lore);
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Lore line " + Msg.white(String.valueOf(line)) + " removed."));
    }

    @Command("itemlore clear")
    @CommandPermission("genscore.itemlore")
    public void loreClear(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        ItemStack item = held(player);
        if (item == null) {
            return;
        }
        ItemMeta meta = item.getItemMeta();
        meta.lore(null);
        item.setItemMeta(meta);
        player.sendMessage(Msg.good("Lore cleared."));
    }

    // ------------------------------------------------------------------ portable blocks

    @Command({"craft", "workbench"})
    @CommandPermission("genscore.craft")
    @Description("Open a crafting table")
    public void craft(BukkitCommandActor actor) {
        actor.requirePlayer().openWorkbench(null, true);
    }

    @Command("anvil")
    @CommandPermission("genscore.anvil")
    @Description("Open an anvil")
    public void anvil(BukkitCommandActor actor) {
        actor.requirePlayer().openAnvil(null, true);
    }

    @Command({"disposal", "trash"})
    @CommandPermission("genscore.disposal")
    @Description("Open a bin; anything left in it is deleted")
    public void disposal(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        ViewHolder holder = new ViewHolder(false);
        holder.inventory = Bukkit.createInventory(holder, 36, Component.text("Disposal"));
        player.openInventory(holder.inventory);
    }

    // ------------------------------------------------------------------ ender chest and invsee

    @Command({"ec", "enderchest", "echest"})
    @CommandPermission("genscore.enderchest")
    @Description("Open your ender chest, or another player's")
    public void enderChest(BukkitCommandActor actor, @Named("player") @Optional @Nullable Player target) {
        Player viewer = actor.requirePlayer();
        if (target == null || target.equals(viewer)) {
            viewer.openInventory(viewer.getEnderChest());
            return;
        }
        boolean edit = viewer.hasPermission("genscore.echest.edit");
        if (!edit && !viewer.hasPermission("genscore.echest.view")) {
            viewer.sendMessage(Msg.bad("You don't have permission to do that."));
            return;
        }
        openOther(viewer, target, edit, true);
    }

    @Command("invsee")
    @Description("Look at a player's inventory")
    public void invsee(BukkitCommandActor actor, @Named("player") Player target) {
        Player viewer = actor.requirePlayer();
        boolean edit = viewer.hasPermission("genscore.invsee.edit");
        if (!edit && !viewer.hasPermission("genscore.invsee.view")) {
            viewer.sendMessage(Msg.bad("You don't have permission to do that."));
            return;
        }
        if (target.equals(viewer)) {
            viewer.sendMessage(Msg.bad("You can't inspect your own inventory."));
            return;
        }
        openOther(viewer, target, edit, false);
    }

    private void openOther(Player viewer, Player target, boolean edit, boolean enderChest) {
        if (edit && Tasks.owns(target)) {
            org.bukkit.inventory.Inventory live = enderChest ? target.getEnderChest() : target.getInventory();
            viewer.openInventory(live);
            // Watch every tick on the viewer's thread: the view syncs every tick, so it must close the moment the
            // target is no longer on this thread (moved away) or has logged out (their saved inventory must not be
            // edited afterwards, or items taken out would exist twice).
            Tasks.entity.timer(viewer, task -> {
                if (viewer.getOpenInventory().getTopInventory() != live) {
                    task.cancel();
                    return;
                }
                if (!target.isOnline() || !Tasks.owns(target)) {
                    task.cancel();
                    viewer.closeInventory();
                    viewer.sendMessage(Msg.neutral("Closed " + Msg.white(target.getName()) + "'s inventory: they moved away or left."));
                }
            }, 1, 1);
            return;
        }
        if (edit) {
            viewer.sendMessage(Msg.neutral(Msg.white(target.getName()) + " is too far away to edit; showing a read-only copy."));
        }
        // Copy the items on the target's thread, then show the copy on the viewer's thread.
        Tasks.entity.run(target, () -> {
            ItemStack[] contents = enderChest ? target.getEnderChest().getContents() : target.getInventory().getContents();
            ItemStack[] copy = new ItemStack[contents.length];
            for (int i = 0; i < contents.length; i++) {
                copy[i] = contents[i] == null ? null : contents[i].clone();
            }
            String title = target.getName() + (enderChest ? "'s ender chest" : "'s inventory") + " (read-only)";
            Tasks.entity.run(viewer, () -> {
                ViewHolder holder = new ViewHolder(true);
                int size = Math.min(54, ((copy.length + 8) / 9) * 9);
                holder.inventory = Bukkit.createInventory(holder, size, Component.text(title));
                for (int i = 0; i < copy.length && i < size; i++) {
                    holder.inventory.setItem(i, copy[i]);
                }
                viewer.openInventory(holder.inventory);
            });
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ViewHolder holder && holder.readOnly) {
            event.setCancelled(true);
            return;
        }
        // A live view of another player's inventory or ender chest: stop if they are no longer on our thread.
        if (top.getHolder() instanceof Player owner && !owner.equals(event.getWhoClicked())
                && (!owner.isOnline() || !Tasks.owns(owner))) {
            event.setCancelled(true);
            Tasks.entity.run(event.getWhoClicked(), () -> event.getWhoClicked().closeInventory());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (top.getHolder() instanceof ViewHolder holder && holder.readOnly) {
            event.setCancelled(true);
            return;
        }
        if (top.getHolder() instanceof Player owner && !owner.equals(event.getWhoClicked())
                && (!owner.isOnline() || !Tasks.owns(owner))) {
            event.setCancelled(true);
            Tasks.entity.run(event.getWhoClicked(), () -> event.getWhoClicked().closeInventory());
        }
    }
}
