package me.Evan52098.genscore.leaderboards;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Floating leaderboards: non-persistent TextDisplays, ported from BlockvilleCore's ArenaLeaderboards (same placement in
 * front of the player, same look). The list of boards is kept in holograms.yml and the displays are spawned again
 * whenever their chunk loads.
 *
 * <p>Folia: a display is spawned, changed and removed only on the region thread that owns its location
 * ({@code Tasks.region}); chunk-load events already arrive on that thread.
 */
final class Holograms implements Listener {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private static final class Holo {
        final String board;
        final String world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        volatile TextDisplay display;
        volatile String lastText = "";

        Holo(String board, String world, double x, double y, double z, float yaw) {
            this.board = board;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
        }

        Location location(World w) {
            return new Location(w, x, y, z, yaw, 0f);
        }
    }

    private final LeaderboardsModule module;
    private final File file;
    private final Logger logger;
    private final List<Holo> holos = new CopyOnWriteArrayList<>();

    Holograms(LeaderboardsModule module, File file, Logger logger) {
        this.module = module;
        this.file = file;
        this.logger = logger;
    }

    void start(GensCore plugin) {
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.timer(t -> refreshAll(), 5, 60, TimeUnit.SECONDS);
    }

    void stop() {
        HandlerList.unregisterAll(this);
        // The displays are not saved with the world, so they disappear on their own at shutdown.
    }

    void place(String board, Location at) {
        double yaw = Math.toRadians(at.getYaw());
        Location spot = at.clone().add(-Math.sin(yaw) * 2.5, 1.8, Math.cos(yaw) * 2.5);
        Holo holo = new Holo(board.toLowerCase(Locale.ROOT), at.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ(), at.getYaw() + 180f);
        holos.add(holo);
        Tasks.async.run(this::save);
        refresh(holo);
    }

    int clear() {
        int count = holos.size();
        for (Holo holo : holos) {
            removeDisplay(holo);
        }
        holos.clear();
        Tasks.async.run(this::save);
        return count;
    }

    private void removeDisplay(Holo holo) {
        TextDisplay display = holo.display;
        World w = Bukkit.getWorld(holo.world);
        if (display == null || w == null) {
            return;
        }
        Tasks.region.run(holo.location(w), () -> {
            if (display.isValid()) {
                display.remove();
            }
        });
        holo.display = null;
    }

    private void refreshAll() {
        for (Holo holo : holos) {
            refresh(holo);
        }
    }

    private void refresh(Holo holo) {
        LeaderboardsModule.Board board = module.board(holo.board);
        if (board == null) {
            return;
        }
        List<LeaderboardsModule.Entry> entries = module.top(board.id());
        StringBuilder text = new StringBuilder("&6&l" + board.title().toUpperCase(Locale.ROOT) + "\n&8&m                        \n");
        if (entries.isEmpty()) {
            text.append("&7Nobody yet - be the first!");
        }
        int rank = 1;
        for (LeaderboardsModule.Entry entry : entries) {
            String colour = rank == 1 ? "&6" : rank == 2 ? "&f" : rank == 3 ? "&c" : "&7";
            text.append(colour).append(rank).append(". &f").append(entry.name() == null ? "Unknown" : entry.name().replace('&', ' '))
                    .append(" &8- &a").append(board.format().apply(entry.value())).append('\n');
            rank++;
        }
        holo.lastText = text.toString().stripTrailing();
        World w = Bukkit.getWorld(holo.world);
        if (w == null) {
            return;
        }
        Location at = holo.location(w);
        Tasks.region.run(at, () -> {
            if (!w.isChunkLoaded(((int) Math.floor(holo.x)) >> 4, ((int) Math.floor(holo.z)) >> 4) || !holos.contains(holo)) {
                return;
            }
            ensure(holo, w);
            TextDisplay display = holo.display;
            if (display != null) {
                display.text(LEGACY.deserialize(holo.lastText));
            }
        });
    }

    /** On the region thread owning the hologram's location. */
    private void ensure(Holo holo, World world) {
        TextDisplay current = holo.display;
        if (current != null && current.isValid()) {
            return;
        }
        Component text = LEGACY.deserialize(holo.lastText.isEmpty() ? "&7Loading..." : holo.lastText);
        holo.display = world.spawn(holo.location(world), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.FIXED);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(140, 0, 0, 0));
            display.setPersistent(false);
            display.setViewRange(1.5f);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(1.4f, 1.4f, 1.4f), new Quaternionf()));
            display.text(text);
        });
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        for (Holo holo : holos) {
            if (holo.world.equals(event.getWorld().getName()) && ((int) Math.floor(holo.x)) >> 4 == event.getChunk().getX()
                    && ((int) Math.floor(holo.z)) >> 4 == event.getChunk().getZ()) {
                World w = event.getWorld();
                Tasks.region.run(holo.location(w), () -> ensure(holo, w)); // next tick, still on the owning region
            }
        }
    }

    private void load() {
        holos.clear();
        if (!file.isFile()) {
            return;
        }
        for (Map<?, ?> map : YamlConfiguration.loadConfiguration(file).getMapList("boards")) {
            try {
                holos.add(new Holo(String.valueOf(map.get("type")), String.valueOf(map.get("world")), ((Number) map.get("x")).doubleValue(),
                        ((Number) map.get("y")).doubleValue(), ((Number) map.get("z")).doubleValue(), ((Number) map.get("yaw")).floatValue()));
            } catch (RuntimeException e) {
                logger.warning("[leaderboards] Skipped a bad entry in holograms.yml: " + map);
            }
        }
    }

    private synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Holo holo : holos) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", holo.board);
            map.put("world", holo.world);
            map.put("x", holo.x);
            map.put("y", holo.y);
            map.put("z", holo.z);
            map.put("yaw", holo.yaw);
            list.add(map);
        }
        yaml.set("boards", list);
        try {
            File parent = file.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            yaml.save(file);
        } catch (IOException e) {
            logger.warning("[leaderboards] Could not save holograms.yml: " + e.getMessage());
        }
    }
}
