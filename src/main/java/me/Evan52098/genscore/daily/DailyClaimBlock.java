package me.Evan52098.genscore.daily;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.logging.Level;

/**
 * The optional physical Daily Strike dig site (BlockvilleCore's DailyRewardClaimBlockService and
 * DailyRewardClaimListener): right-clicking that block opens the Daily Strike, and a floating "Daily Strike" label
 * sits above it. The location is kept in {@code modules/daily/claim-block.yml} (set with /dailyreward setblock).
 *
 * <p>Folia: the label is spawned on the region thread that owns the block (when its chunk loads, or right away if it is
 * loaded) and is not saved with the world, so restarts never pile up copies. It is removed through its own scheduler.
 */
final class DailyClaimBlock implements Listener {

    private record Spot(String world, int x, int y, int z) {
        Location labelAt(World w) {
            return new Location(w, x + 0.5, y + 1.4, z + 0.5);
        }
    }

    private final GensCore plugin;
    private final DailyRewardGui gui;
    private final File file;
    private final NamespacedKey labelKey;
    private volatile @Nullable Spot spot;
    private volatile @Nullable TextDisplay label;

    DailyClaimBlock(GensCore plugin, DailyRewardGui gui) {
        this.plugin = plugin;
        this.gui = gui;
        this.file = new File(plugin.getDataFolder(), "modules/daily/claim-block.yml");
        this.labelKey = new NamespacedKey(plugin, "daily_strike_label");
    }

    void load() {
        despawnLabel();
        spot = null;
        String raw = YamlConfiguration.loadConfiguration(file).getString("location", "");
        if (raw == null || raw.isBlank()) {
            return;
        }
        String[] parts = raw.split(",");
        if (parts.length < 4) {
            return;
        }
        try {
            spot = new Spot(parts[0], Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()), Integer.parseInt(parts[3].trim()));
        } catch (NumberFormatException ignored) {
            return;
        }
        spawnLabelIfLoaded();
    }

    boolean isSet() {
        return spot != null;
    }

    private boolean matches(Block block) {
        Spot s = spot;
        return s != null && block.getWorld().getName().equals(s.world())
                && block.getX() == s.x() && block.getY() == s.y() && block.getZ() == s.z();
    }

    void setLocation(Block block) {
        save(block.getWorld().getName() + "," + block.getX() + "," + block.getY() + "," + block.getZ());
        load();
    }

    void clearLocation() {
        save("");
        despawnLabel();
        spot = null;
    }

    private void save(String location) {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("location", location);
        try {
            file.getParentFile().mkdirs();
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "[daily] Could not save the Daily Strike dig site", e);
        }
    }

    // ------------------------------------------------------------------ label

    private void spawnLabelIfLoaded() {
        Spot s = spot;
        World world = s == null ? null : Bukkit.getWorld(s.world());
        if (world == null) {
            return;
        }
        Location at = s.labelAt(world);
        Tasks.region.run(at, () -> {
            if (world.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                spawnLabel(world, s);
            }
        });
    }

    /** Call on the region thread that owns the spot. */
    private void spawnLabel(World world, Spot s) {
        if (spot != s) {
            return;
        }
        TextDisplay current = label;
        if (current != null && current.isValid()) {
            return;
        }
        Location at = s.labelAt(world);
        // Remove any label left from before (for example after a reload).
        for (Entity e : world.getNearbyEntities(at, 0.5, 0.5, 0.5)) {
            if (e instanceof TextDisplay d && d.getPersistentDataContainer().has(labelKey, PersistentDataType.BYTE)) {
                d.remove();
            }
        }
        label = world.spawn(at, TextDisplay.class, display -> {
            display.text(LegacyComponentSerializer.legacyAmpersand().deserialize("&6&l⛏ Daily Strike\n&7Right-click to dig!"));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
            display.getPersistentDataContainer().set(labelKey, PersistentDataType.BYTE, (byte) 1);
        });
    }

    void despawnLabel() {
        TextDisplay current = label;
        label = null;
        if (current != null) {
            try {
                Tasks.entity.run(current, current::remove);
            } catch (RuntimeException ignored) {
                // plugin disabling: the label is not persistent, so it is gone after the restart anyway
            }
        }
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Spot s = spot;
        if (s != null && event.getWorld().getName().equals(s.world())
                && event.getChunk().getX() == (s.x() >> 4) && event.getChunk().getZ() == (s.z() >> 4)) {
            World world = event.getWorld();
            // Spawned a tick later, on the same region thread, rather than while the chunk is still loading.
            Tasks.region.run(s.labelAt(world), () -> {
                if (world.isChunkLoaded(s.x() >> 4, s.z() >> 4)) {
                    spawnLabel(world, s);
                }
            });
        }
    }

    // ------------------------------------------------------------------ clicking

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK || spot == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || !matches(block)) {
            return;
        }
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
        Player player = event.getPlayer();
        gui.open(player);
    }
}
