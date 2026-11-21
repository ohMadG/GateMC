package me.Evan52098.genscore.gameplay;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

public final class GameplayModule implements GensModule, Listener {

    private record Settings(boolean noHunger, boolean noMobs, boolean noDrops, boolean noPvpDamage, boolean keepInventory,
                            boolean stick, int slot, int level, String name, List<String> lore) {
    }

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private GensCore plugin;
    private NamespacedKey stickKey;
    private NamespacedKey hubKey;
    private static final int HUB_SLOT = 4;
    private static final NamespacedKey ROD_MODEL = new NamespacedKey("genscore", "kb_rod");
    private static final NamespacedKey RAID_PICK = new NamespacedKey("genscore", "raid_pick");
    private static final EnumSet<CreatureSpawnEvent.SpawnReason> ALLOWED_SPAWNS = EnumSet.of(
            CreatureSpawnEvent.SpawnReason.CUSTOM, CreatureSpawnEvent.SpawnReason.COMMAND,
            CreatureSpawnEvent.SpawnReason.SPAWNER_EGG, CreatureSpawnEvent.SpawnReason.DEFAULT);
    private volatile Settings settings;

    @Override
    public String id() {
        return "gameplay";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.stickKey = new NamespacedKey(plugin, "kb_stick");
        this.hubKey = new NamespacedKey(plugin, "hub_item");
        this.settings = read();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> start(p));
        }
        plugin.getLogger().info("[gameplay] hunger " + (settings.noHunger() ? "off" : "on") + ", knockback stick "
                + (settings.stick() ? "level " + settings.level() + " in slot " + (settings.slot() + 1) : "off") + ".");
    }

    @Override
    public void reload() {
        settings = read();
    }

    private Settings read() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("gameplay");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        ConfigurationSection s = c.getConfigurationSection("knockback-stick");
        if (s == null) {
            s = new MemoryConfiguration();
        }
        return new Settings(
                c.getBoolean("no-hunger", true),
                c.getBoolean("no-mob-spawning", true),
                c.getBoolean("no-item-drops", true),
                c.getBoolean("no-pvp-damage", true),
                c.getBoolean("keep-inventory", true),
                s.getBoolean("enabled", true),
                Math.max(0, Math.min(8, s.getInt("slot", 9) - 1)),
                Math.max(1, Math.min(255, s.getInt("level", 5))),
                s.getString("name", "&6&lKnockback Rod"),
                s.getStringList("lore").isEmpty() ? List.of("&7Knock players off the platform.", "&8Always yours: it can't be dropped or lost.") : s.getStringList("lore"));
    }

    private void start(Player p) {
        if (settings.noHunger()) {
            p.setFoodLevel(20);
            p.setSaturation(20f);
            p.setExhaustion(0f);
        }
        Tasks.entity.timer(p, t -> {
            if (!p.isOnline()) {
                t.cancel();
                return;
            }
            ensureStick(p);
            ensureHub(p);
        }, 1L, 40L);
    }

    boolean isStick(ItemStack item) {
        return item != null && item.getType() == Material.STICK && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(stickKey, PersistentDataType.BYTE);
    }

    boolean isHub(ItemStack item) {
        return item != null && item.getType() == Material.NETHER_STAR && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(hubKey, PersistentDataType.BYTE);
    }

    boolean isRaidPick(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(RAID_PICK, PersistentDataType.INTEGER);
    }

    boolean isLocked(ItemStack item) {
        return isStick(item) || isHub(item);
    }

    private ItemStack makeHub() {
        ItemStack item = new ItemStack(Material.NETHER_STAR);
        item.editMeta(meta -> {
            meta.displayName(LEGACY.deserialize("&6&lGateMC Menu &7(Right-click)").decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(LEGACY.deserialize("&7Your factory, upgrades, powers,").decoration(TextDecoration.ITALIC, false),
                    LEGACY.deserialize("&7tasks, boombox and more.").decoration(TextDecoration.ITALIC, false)));
            meta.getPersistentDataContainer().set(hubKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private boolean staffMode(Player p) {
        var staff = plugin.modules().get(me.Evan52098.genscore.staffmode.StaffModeModule.class);
        return staff != null && staff.inStaffMode(p.getUniqueId());
    }

    private void ensureHub(Player p) {
        PlayerInventory inv = p.getInventory();
        if (staffMode(p)) {
            for (int i = 0; i < inv.getSize(); i++) {
                if (isHub(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            return;
        }
        if (isHub(p.getItemOnCursor())) {
            return;
        }
        int found = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            if (isHub(inv.getItem(i))) {
                if (found >= 0) {
                    inv.setItem(i, null);
                } else {
                    found = i;
                }
            }
        }
        if (found >= 0) {
            return;
        }
        ItemStack displaced = inv.getItem(HUB_SLOT);
        inv.setItem(HUB_SLOT, makeHub());
        if (displaced != null && !displaced.getType().isAir() && !isStick(displaced)) {
            for (ItemStack left : inv.addItem(displaced).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        } else if (isStick(displaced)) {
            inv.addItem(displaced);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMobSpawn(CreatureSpawnEvent event) {
        if (settings.noMobs() && !ALLOWED_SPAWNS.contains(event.getSpawnReason())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHubUse(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND || !isHub(event.getItem())) {
            return;
        }
        org.bukkit.event.block.Action a = event.getAction();
        if (a == org.bukkit.event.block.Action.RIGHT_CLICK_AIR || a == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            me.Evan52098.genscore.hub.HubModule.open(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHubClick(InventoryClickEvent event) {
        if (isHub(event.getCurrentItem()) && event.getClickedInventory() instanceof PlayerInventory
                && event.getWhoClicked() instanceof Player p) {
            event.setCancelled(true);
            Tasks.entity.run(p, () -> me.Evan52098.genscore.hub.HubModule.open(p));
        }
    }

    private ItemStack makeStick() {
        Settings s = settings;
        ItemStack item = new ItemStack(Material.STICK);
        item.editMeta(meta -> {
            meta.displayName(LEGACY.deserialize(s.name()).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            for (String line : s.lore()) {
                lore.add(LEGACY.deserialize(line).decoration(TextDecoration.ITALIC, false));
            }
            meta.lore(lore);
            meta.addEnchant(Enchantment.KNOCKBACK, s.level(), true);
            meta.setItemModel(ROD_MODEL);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            meta.getPersistentDataContainer().set(stickKey, PersistentDataType.BYTE, (byte) 1);
        });
        return item;
    }

    private void ensureStick(Player p) {
        Settings s = settings;
        PlayerInventory inv = p.getInventory();
        if (!s.stick() || staffMode(p)) {
            for (int i = 0; i < inv.getSize(); i++) {
                if (isStick(inv.getItem(i))) {
                    inv.setItem(i, null);
                }
            }
            return;
        }
        if (isStick(p.getItemOnCursor())) {
            return;
        }
        int found = -1;
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (!isStick(it)) {
                continue;
            }
            if (found >= 0 || it.getAmount() != 1) {
                if (found >= 0) {
                    inv.setItem(i, null);
                    continue;
                }
                it.setAmount(1);
            }
            found = i;
            if (it.getEnchantmentLevel(Enchantment.KNOCKBACK) != s.level() || !ROD_MODEL.equals(it.getItemMeta().getItemModel())) {
                inv.setItem(i, makeStick());
            }
        }
        if (found >= 0) {
            return;
        }
        ItemStack displaced = inv.getItem(s.slot());
        inv.setItem(s.slot(), makeStick());
        if (displaced != null && !displaced.getType().isAir()) {
            for (ItemStack left : inv.addItem(displaced).values()) {
                p.getWorld().dropItemNaturally(p.getLocation(), left);
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        start(event.getPlayer());
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player p = event.getPlayer();
        Tasks.entity.later(p, () -> {
            ensureStick(p);
            if (settings.noHunger()) {
                p.setFoodLevel(20);
                p.setSaturation(20f);
            }
        }, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFireworkHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof org.bukkit.entity.Firework fw
                && (fw.getScoreboardTags().contains("genscore_harmless") || fw.getShooter() == null)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFood(FoodLevelChangeEvent event) {
        if (!settings.noHunger() || !(event.getEntity() instanceof Player p)) {
            return;
        }
        if (event.getFoodLevel() < p.getFoodLevel()) {
            event.setCancelled(true);
        }
        p.setSaturation(20f);
        p.setExhaustion(0f);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        if (settings.keepInventory() && !event.getKeepInventory()) {
            event.setKeepInventory(true);
            event.getDrops().clear();
            event.setKeepLevel(true);
            event.setDroppedExp(0);
        }
        event.getDrops().removeIf(item -> {
            if (isRaidPick(item)) {
                event.getItemsToKeep().add(item);
                return true;
            }
            return false;
        });
        event.getDrops().removeIf(this::isLocked);
        event.getItemsToKeep().removeIf(this::isLocked);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerHit(org.bukkit.event.entity.EntityDamageByEntityEvent event) {
        if (!settings.noPvpDamage() || !(event.getEntity() instanceof Player)) {
            return;
        }
        org.bukkit.entity.Entity damager = event.getDamager();
        if (damager instanceof org.bukkit.entity.Projectile proj && proj.getShooter() instanceof org.bukkit.entity.Entity shooter) {
            damager = shooter;
        }
        if (damager instanceof Player && !damager.equals(event.getEntity())) {
            event.setDamage(0);
        }
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onContainer(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) {
            return;
        }
        if (event.getPlayer().hasPermission(me.Evan52098.genscore.plots.PlotsModule.BUILD)) {
            return;
        }
        if (event.getClickedBlock().getState(false) instanceof org.bukkit.block.Container) {
            event.setUseInteractedBlock(org.bukkit.event.Event.Result.DENY);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isLocked(event.getItemDrop().getItemStack()) || isRaidPick(event.getItemDrop().getItemStack())) {
            event.setCancelled(true);
            event.getPlayer().sendActionBar(Msg.bad("You can't drop that."));
            return;
        }
        Player p = event.getPlayer();
        if (settings.noDrops() && p.getGameMode() != org.bukkit.GameMode.CREATIVE && !p.hasPermission("genscore.drop.bypass")) {
            event.setCancelled(true);
            p.sendActionBar(Msg.bad("You can't drop items here."));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (isLocked(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        ItemStack hand = event.getHand() == EquipmentSlot.OFF_HAND
                ? event.getPlayer().getInventory().getItemInOffHand()
                : event.getPlayer().getInventory().getItemInMainHand();
        if (isLocked(hand) && (event.getRightClicked() instanceof org.bukkit.entity.ItemFrame
                || event.getRightClicked() instanceof org.bukkit.entity.ArmorStand
                || event.getRightClicked() instanceof org.bukkit.entity.Allay)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        boolean top = event.getView().getTopInventory().getType() != InventoryType.CRAFTING
                && event.getView().getTopInventory().getType() != InventoryType.PLAYER;
        ItemStack current = event.getCurrentItem();
        ItemStack cursor = event.getCursor();
        if (event.getClick() == org.bukkit.event.inventory.ClickType.SWAP_OFFHAND && isLocked(current)) {
            event.setCancelled(true);
            return;
        }
        if (event.getSlotType() == InventoryType.SlotType.QUICKBAR || event.getSlotType() == InventoryType.SlotType.CONTAINER) {
            if (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 40 && isLocked(cursor)) {
                event.setCancelled(true);
                return;
            }
        }
        if (event.getClickedInventory() instanceof PlayerInventory && event.getSlot() == 40 && isLocked(cursor)) {
            event.setCancelled(true);
            return;
        }
        if (event.getHotbarButton() >= 0 && isLocked(event.getWhoClicked().getInventory().getItem(event.getHotbarButton()))
                && !(event.getClickedInventory() instanceof PlayerInventory)) {
            event.setCancelled(true);
            return;
        }
        if (!top) {
            if (event.getSlotType() == InventoryType.SlotType.ARMOR && isLocked(cursor)) {
                event.setCancelled(true);
            }
            if (event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT
                    || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT
                    || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR
                    || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR) {
                if (isLocked(current) || isLocked(cursor)) {
                    event.setCancelled(true);
                }
            }
            return;
        }
        boolean intoTop = event.getClickedInventory() != null && event.getClickedInventory().equals(event.getView().getTopInventory());
        if (intoTop && isLocked(cursor)) {
            event.setCancelled(true);
        } else if (event.isShiftClick() && !intoTop && isLocked(current)) {
            event.setCancelled(true);
        } else if (event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ALL_CURSOR
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_CURSOR
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ALL_SLOT
                || event.getAction() == org.bukkit.event.inventory.InventoryAction.DROP_ONE_SLOT) {
            if (isLocked(current) || isLocked(cursor)) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!isLocked(event.getOldCursor())) {
            return;
        }
        int topSize = event.getView().getTopInventory().getSize();
        boolean playerTop = event.getView().getTopInventory().getType() == InventoryType.CRAFTING;
        for (int raw : event.getRawSlots()) {
            if (!playerTop && raw < topSize) {
                event.setCancelled(true);
                return;
            }
            if (event.getView().convertSlot(raw) == 40) {
                event.setCancelled(true);
                return;
            }
        }
    }
}
