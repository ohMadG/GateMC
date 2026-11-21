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
import io.papermc.paper.event.player.PrePlayerAttackEntityEvent;
import org.bukkit.Sound;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
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
        final float scale;
        volatile Interaction hitbox;

        Holo(String board, String world, double x, double y, double z, float yaw, float scale) {
            this.scale = scale;
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
    private final Map<UUID, Map<Holo, View>> views = new ConcurrentHashMap<>();
    private GensCore plugin;

    private static final double VIEW_RANGE = 40;

    private static final class View {
        volatile TextDisplay display;
        volatile String text = "";
        volatile String shown = "";
        volatile int page;
        final AtomicBoolean busy = new AtomicBoolean();
    }

    Holograms(LeaderboardsModule module, File file, Logger logger) {
        this.module = module;
        this.file = file;
        this.logger = logger;
    }

    void start(GensCore plugin) {
        this.plugin = plugin;
        load();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.timer(t -> refreshAll(), 5, 60, TimeUnit.SECONDS);
        Tasks.async.timer(t -> sweep(), 2, 1, TimeUnit.SECONDS);
    }

    void stop() {
        HandlerList.unregisterAll(this);
        // The displays are not saved with the world, so they disappear on their own at shutdown.
    }

    void place(String board, Location at) {
        double yaw = Math.toRadians(at.getYaw());
        Location spot = at.clone().add(-Math.sin(yaw) * 2.5, 1.8, Math.cos(yaw) * 2.5);
        Holo holo = new Holo(board.toLowerCase(Locale.ROOT), at.getWorld().getName(), spot.getX(), spot.getY(), spot.getZ(), at.getYaw() + 180f, 1.4f);
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
        for (UUID id : List.copyOf(views.keySet())) {
            dropViews(id);
        }
        Tasks.async.run(this::save);
        return count;
    }

    private void removeDisplay(Holo holo) {
        Interaction hitbox = holo.hitbox;
        World w = Bukkit.getWorld(holo.world);
        if (hitbox == null || w == null) {
            return;
        }
        Tasks.region.run(holo.location(w), () -> {
            if (hitbox.isValid()) {
                hitbox.remove();
            }
        });
        holo.hitbox = null;
    }

    private void refreshAll() {
        for (Holo holo : holos) {
            refresh(holo);
        }
    }

    private void refresh(Holo holo) {
        if (module.board(holo.board) == null) {
            return;
        }
        World w = Bukkit.getWorld(holo.world);
        if (w == null) {
            return;
        }
        Tasks.region.run(holo.location(w), () -> {
            if (w.isChunkLoaded(((int) Math.floor(holo.x)) >> 4, ((int) Math.floor(holo.z)) >> 4) && holos.contains(holo)) {
                ensure(holo, w);
            }
        });
    }

    private int lines(Holo holo) {
        return module.pageSize() + 6;
    }

    private void ensure(Holo holo, World world) {
        Interaction current = holo.hitbox;
        if (current != null && current.isValid()) {
            return;
        }
        float height = (float) (lines(holo) * 0.25 * holo.scale + 0.2);
        Location at = holo.location(world).subtract(0, 0.1, 0);
        holo.hitbox = world.spawn(at, Interaction.class, box -> {
            box.setPersistent(false);
            box.setInteractionWidth((float) Math.max(1.5, 3.2 * holo.scale));
            box.setInteractionHeight(height);
            box.setResponsive(true);
        });
    }

    private void sweep() {
        for (UUID id : List.copyOf(views.keySet())) {
            if (Bukkit.getPlayer(id) == null) {
                dropViews(id);
            }
        }
        if (holos.isEmpty()) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(player, () -> tick(player));
        }
    }

    private void tick(Player player) {
        Location loc = player.getLocation();
        for (Holo holo : holos) {
            boolean near = holo.world.equals(loc.getWorld().getName())
                    && square(loc.getX() - holo.x) + square(loc.getZ() - holo.z) <= VIEW_RANGE * VIEW_RANGE;
            Map<Holo, View> mine = views.get(player.getUniqueId());
            if (!near) {
                View view = mine == null ? null : mine.remove(holo);
                if (view != null) {
                    removeView(holo, view);
                }
                continue;
            }
            View view = views.computeIfAbsent(player.getUniqueId(), k -> new ConcurrentHashMap<>()).computeIfAbsent(holo, k -> new View());
            update(player, holo, view);
        }
    }

    private static double square(double v) {
        return v * v;
    }

    private void update(Player player, Holo holo, View view) {
        LeaderboardsModule.Board board = module.board(holo.board);
        World w = Bukkit.getWorld(holo.world);
        if (board == null || w == null) {
            return;
        }
        view.text = render(board, player, view);
        TextDisplay display = view.display;
        if (display != null && display.isValid() && view.text.equals(view.shown)) {
            return;
        }
        if (!view.busy.compareAndSet(false, true)) {
            return;
        }
        Tasks.region.run(holo.location(w), () -> {
            try {
                Map<Holo, View> mine = views.get(player.getUniqueId());
                if (mine == null || mine.get(holo) != view || !holos.contains(holo)
                        || !w.isChunkLoaded(((int) Math.floor(holo.x)) >> 4, ((int) Math.floor(holo.z)) >> 4)) {
                    return;
                }
                ensure(holo, w);
                String text = view.text;
                TextDisplay current = view.display;
                if (current == null || !current.isValid()) {
                    current = spawn(holo, w, text);
                    view.display = current;
                    TextDisplay shown = current;
                    Tasks.entity.run(player, () -> player.showEntity(plugin, shown));
                } else {
                    current.text(LEGACY.deserialize(text));
                }
                view.shown = text;
            } finally {
                view.busy.set(false);
            }
        });
    }

    private String render(LeaderboardsModule.Board board, Player player, View view) {
        List<LeaderboardsModule.Entry> entries = module.top(board.id());
        int size = module.pageSize();
        int pages = Math.max(1, (entries.size() + size - 1) / size);
        view.page = Math.floorMod(view.page, pages);
        int page = view.page;
        UUID me = player.getUniqueId();
        StringBuilder text = new StringBuilder("&6&l" + board.title().toUpperCase(Locale.ROOT) + "\n&8&m                        \n");
        if (entries.isEmpty()) {
            text.append("&7Nobody yet - be the first!\n");
        }
        int from = page * size;
        int to = Math.min(entries.size(), from + size);
        for (int i = from; i < to; i++) {
            LeaderboardsModule.Entry entry = entries.get(i);
            int rank = i + 1;
            boolean self = entry.uuid().equals(me);
            String colour = rank == 1 ? "&6" : rank == 2 ? "&f" : rank == 3 ? "&c" : "&7";
            text.append(colour).append(rank).append(". ").append(self ? "&e&l" : "&f").append(LeaderboardsModule.safeName(entry))
                    .append(self ? "&r" : "").append(" &8- &a").append(board.format().apply(entry.value())).append('\n');
        }
        if (pages > 1) {
            for (int i = to; i < from + size; i++) {
                text.append("&8").append(i + 1).append(". &8-\n");
            }
        }
        text.append("&8&m                        \n");
        int index = LeaderboardsModule.indexOf(entries, me);
        long value = module.liveValue(board, me);
        if (index >= 0) {
            text.append("&e&lYOU &6#").append(index + 1).append(" &8- &a").append(board.format().apply(value));
        } else {
            text.append("&e&lYOU &7").append(module.rows()).append("+ &8- &a").append(board.format().apply(value));
        }
        if (index > 0) {
            text.append("\n&7").append(board.format().apply(Math.max(0, entries.get(index - 1).value() - value))).append(" behind &f#").append(index);
        }
        if (pages > 1) {
            text.append("\n&e◀ &fPage ").append(page + 1).append("/").append(pages).append(" &e▶ &8(click)");
        }
        return text.toString();
    }

    private TextDisplay spawn(Holo holo, World world, String text) {
        return world.spawn(holo.location(world), TextDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setBillboard(Display.Billboard.FIXED);
            display.setShadowed(true);
            display.setBackgroundColor(Color.fromARGB(140, 0, 0, 0));
            display.setPersistent(false);
            display.setViewRange(1.5f);
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(holo.scale, holo.scale, holo.scale), new Quaternionf()));
            display.text(LEGACY.deserialize(text));
        });
    }

    private void removeView(Holo holo, View view) {
        TextDisplay display = view.display;
        view.display = null;
        World w = Bukkit.getWorld(holo.world);
        if (display == null || w == null) {
            return;
        }
        Tasks.region.run(holo.location(w), () -> {
            if (display.isValid()) {
                display.remove();
            }
        });
    }

    private void dropViews(UUID player) {
        Map<Holo, View> mine = views.remove(player);
        if (mine == null) {
            return;
        }
        mine.forEach(this::removeView);
    }

    private void flip(Player player, Entity clicked, int step) {
        for (Holo holo : holos) {
            Interaction box = holo.hitbox;
            if (box == null || !box.getUniqueId().equals(clicked.getUniqueId())) {
                continue;
            }
            Map<Holo, View> mine = views.get(player.getUniqueId());
            View view = mine == null ? null : mine.get(holo);
            if (view == null) {
                return;
            }
            view.page += step;
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, step > 0 ? 1.4f : 1.1f);
            update(player, holo, view);
            return;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEntityEvent event) {
        if (event.getHand() == EquipmentSlot.HAND && event.getRightClicked() instanceof Interaction) {
            flip(event.getPlayer(), event.getRightClicked(), 1);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onAttack(PrePlayerAttackEntityEvent event) {
        if (event.getAttacked() instanceof Interaction box && holos.stream().anyMatch(h -> h.hitbox == box)) {
            event.setCancelled(true);
            flip(event.getPlayer(), box, -1);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        dropViews(event.getPlayer().getUniqueId());
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
                        ((Number) map.get("y")).doubleValue(), ((Number) map.get("z")).doubleValue(), ((Number) map.get("yaw")).floatValue(),
                        map.get("scale") instanceof Number n ? n.floatValue() : 1.4f));
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
            map.put("scale", holo.scale);
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
