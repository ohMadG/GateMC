package me.Evan52098.genscore.npcs;

import io.papermc.paper.datacomponent.item.ResolvableProfile;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mannequin;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Vector;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class NpcsModule implements GensModule, Listener {

    private record NpcDef(String id, String name, String role, String command, double x, double z, float yaw,
                          Material hand, Material helmet) {
    }

    private record Shot(Location at, String title, String subtitle, int ticks) {
    }

    private static final double CX = 1152.5;
    private static final double Y = 65;
    private static final List<NpcDef> NPCS = List.of(
            new NpcDef("guide", "&a&lGuide", "&7New here? Click me!", "tutorial", 1149.5, 1137.5, -90f, Material.WRITABLE_BOOK, Material.LEATHER_HELMET),
            new NpcDef("upgrades", "&e&lEngineer", "&7Upgrade your belt & gen", "belt", 1149.5, 1134.5, -90f, Material.HOPPER, Material.IRON_HELMET),
            new NpcDef("powers", "&b&lMystic", "&7Powers & super powers", "powers", 1149.5, 1131.5, -90f, Material.BLAZE_ROD, Material.CHAINMAIL_HELMET),
            new NpcDef("tokens", "&6&lTrader", "&7The token shop", "tokens", 1149.5, 1128.5, -90f, Material.SUNFLOWER, Material.GOLDEN_HELMET),
            new NpcDef("daily", "&6&lBanker", "&7Daily cash or boost", "daily", 1155.5, 1137.5, 90f, Material.GOLD_INGOT, Material.GOLDEN_HELMET),
            new NpcDef("tasks", "&a&lForeman", "&7Your daily tasks", "tasks", 1155.5, 1134.5, 90f, Material.WRITABLE_BOOK, Material.IRON_HELMET),
            new NpcDef("cosmetics", "&d&lStylist", "&7Cosmetics & tags", "cosmetics", 1155.5, 1131.5, 90f, Material.LEATHER_CHESTPLATE, Material.LEATHER_HELMET),
            new NpcDef("store", "&a&lMerchant", "&7Ranks, credits & more", "store", 1155.5, 1128.5, 90f, Material.EMERALD, Material.DIAMOND_HELMET));

    private record CrateDecor(String id, double x, double y, double z, String model, float scale) {
    }

    private static final List<CrateDecor> CRATES = List.of();

    private final Map<String, org.bukkit.entity.ItemDisplay> crateDisplays = new ConcurrentHashMap<>();
    private float spin;

    private GensCore plugin;
    private NamespacedKey npcKey;
    private NamespacedKey introKey;
    private final Map<String, Entity> spawned = new ConcurrentHashMap<>();
    private final Set<UUID> inCutscene = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Entity> cameras = new ConcurrentHashMap<>();

    @Override
    public String id() {
        return "npcs";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "npc");
        this.introKey = new NamespacedKey(plugin, "intro_seen");
        Bukkit.getPluginManager().registerEvents(this, plugin);
        plugin.commands().register(this);
        Tasks.global.timer(t -> ensureNpcs(), 100L, 200L);
        Tasks.global.timer(t -> spinCrates(), 120L, 40L);
    }

    @Override
    public void disable() {
        for (Entity e : crateDisplays.values()) {
            try {
                e.remove();
            } catch (RuntimeException ignored) {
            }
        }
        crateDisplays.clear();
        for (Entity e : spawned.values()) {
            try {
                e.remove();
            } catch (RuntimeException ignored) {
            }
        }
        spawned.clear();
    }

    private World world() {
        return Bukkit.getWorld(plugin.getConfig().getString("plots.world", "world"));
    }

    private void ensureNpcs() {
        World w = world();
        if (w == null) {
            return;
        }
        Location anchor = new Location(w, CX, Y, 1133);
        Tasks.region.run(anchor, () -> {
            if (!w.isChunkLoaded(anchor.getBlockX() >> 4, anchor.getBlockZ() >> 4)) {
                return;
            }
            for (Entity e : w.getNearbyEntities(anchor, 12, 6, 12)) {
                String id = e.getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
                if (id == null) {
                    continue;
                }
                Entity mine = id.startsWith("crate_") ? crateDisplays.get(id.substring(6)) : spawned.get(id);
                if (mine != e) {
                    e.remove();
                }
            }
            for (CrateDecor c : CRATES) {
                var have = crateDisplays.get(c.id());
                if (have != null && have.isValid()) {
                    continue;
                }
                Location at = new Location(w, c.x(), c.y(), c.z());
                ItemStack model = new ItemStack(Material.PAPER);
                model.editMeta(meta -> meta.setItemModel(new NamespacedKey("genscore", c.model())));
                var d = w.spawn(at, org.bukkit.entity.ItemDisplay.class, n -> {
                    n.setPersistent(false);
                    n.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, "crate_" + c.id());
                    n.setItemStack(model);
                    n.setItemDisplayTransform(org.bukkit.entity.ItemDisplay.ItemDisplayTransform.NONE);
                    n.setBrightness(new Display.Brightness(15, 15));
                    n.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(), new org.joml.Quaternionf(),
                            new org.joml.Vector3f(c.scale(), c.scale(), c.scale()), new org.joml.Quaternionf()));
                });
                crateDisplays.put(c.id(), d);
            }
            for (NpcDef def : NPCS) {
                Entity have = spawned.get(def.id());
                if (have != null && have.isValid()) {
                    continue;
                }
                Location at = new Location(w, def.x(), Y, def.z(), def.yaw(), 0f);
                Mannequin m = w.spawn(at, Mannequin.class, n -> {
                    n.setPersistent(false);
                    n.getPersistentDataContainer().set(npcKey, PersistentDataType.STRING, def.id());
                    n.setImmovable(true);
                    n.setAI(false);
                    n.setGravity(false);
                    n.setInvulnerable(true);
                    n.setSilent(true);
                    n.setCollidable(false);
                    n.setRemoveWhenFarAway(false);
                    n.customName(Msg.parse(legacyToMini(def.name())));
                    n.setCustomNameVisible(true);
                    n.setDescription(Msg.parse(legacyToMini(def.role())));
                    n.setProfile(ResolvableProfile.resolvableProfile()
                            .skinPatch(patch -> patch.body(net.kyori.adventure.key.Key.key("genscore", "entity/npc/" + def.id()))
                                    .model(org.bukkit.profile.PlayerTextures.SkinModel.CLASSIC))
                            .build());
                    n.getEquipment().setItemInMainHand(new ItemStack(def.hand()));
                });
                spawned.put(def.id(), m);
            }
        });
    }

    private void spinCrates() {
        World w = world();
        if (w == null) {
            return;
        }
        spin += (float) (Math.PI / 2);
        float angle = spin;
        Tasks.region.run(new Location(w, 1152, 67, 1152), () -> {
            for (CrateDecor c : CRATES) {
                var d = crateDisplays.get(c.id());
                if (d == null || !d.isValid()) {
                    continue;
                }
                d.setInterpolationDelay(0);
                d.setInterpolationDuration(40);
                d.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(0, (float) Math.sin(angle) * 0.08f, 0),
                        new org.joml.Quaternionf().rotateY(angle), new org.joml.Vector3f(c.scale(), c.scale(), c.scale()), new org.joml.Quaternionf()));
            }
        });
    }

    private static String legacyToMini(String legacy) {
        return legacy.replace("&a", "<green>").replace("&b", "<aqua>").replace("&d", "<light_purple>").replace("&e", "<yellow>")
                .replace("&6", "<gold>").replace("&7", "<gray>").replace("&f", "<white>").replace("&l", "<bold>");
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onClick(PlayerInteractEntityEvent event) {
        String id = event.getRightClicked().getPersistentDataContainer().get(npcKey, PersistentDataType.STRING);
        if (id == null || event.getHand() != EquipmentSlot.HAND) {
            if (id != null) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        Player p = event.getPlayer();
        for (NpcDef def : NPCS) {
            if (def.id().equals(id)) {
                p.playSound(p.getLocation(), Sound.ENTITY_VILLAGER_TRADE, 0.7f, 1.2f);
                if ("tutorial".equals(def.command())) {
                    if (!me.Evan52098.genscore.tutorial.TutorialModule.openFor(p)) {
                        openTutorial(p);
                    }
                } else {
                    p.performCommand(def.command());
                }
                return;
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity().getPersistentDataContainer().has(npcKey, PersistentDataType.STRING)) {
            event.setCancelled(true);
        }
        if (event.getEntity() instanceof Player p && inCutscene.contains(p.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ tutorial

    @Command("guidebook")
    @Description("How to play GateMC")
    public void tutorialCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> openTutorial(p));
    }

    private void openTutorial(Player p) {
        List<Component> pages = new ArrayList<>();
        pages.add(Msg.parse("<gold><bold>Welcome to GateMC</bold></gold>\n\n<black>You own a <bold>factory</bold>. Its gen makes items that ride your <bold>conveyor belt</bold> and sell at the end.\n\nThe longer and faster your belt, the more you earn.\n\n<dark_gray>Turn the page →"));
        pages.add(Msg.parse("<gold><bold>Upgrading</bold></gold>\n\n<black>Open the menu with the <bold>Nether Star</bold> in slot 5 (or <bold>/menu</bold>).\n\n<bold>Belt & Gen</bold>: better items, a faster and longer belt.\n\nEarly upgrades are cheap. Spend fast!"));
        pages.add(Msg.parse("<gold><bold>Your Gate</bold></gold>\n\n<black>Raiders can only get in while your gate is <dark_red><bold>open</bold></dark_red>.\n\nWalk to your gate and press the button (or <bold>/gate</bold> there) to close it for a while.\n\nA raider who breaks your belt <bold>takes all your income</bold> until you close it!"));
        pages.add(Msg.parse("<gold><bold>Raiding</bold></gold>\n\n<black>Get a <bold>Raid Pick</bold> from <bold>Raid Gear</bold>, find a base with an open gate and break its belt.\n\nYou steal money and take their income.\n\nUse a <bold>Raid Cloak</bold> to hide from turrets."));
        pages.add(Msg.parse("<gold><bold>Tokens & Powers</bold></gold>\n\n<black>Tokens come off your belt now and then. Spend them in the <bold>Token Shop</bold> and on <bold>Powers</bold>.\n\nCaches are rare finds: break them in <bold>/caches</bold>.\n\nYour first power is free!"));
        pages.add(Msg.parse("<gold><bold>Every Day</bold></gold>\n\n<black><bold>/daily</bold>: cash or a 2x money boost.\n<bold>/tasks</bold>: three tasks for extra money.\n\nWatch for <bold>events</bold> like 2x money on the scoreboard.\n\n<dark_green>Good luck, and close your gate!"));
        p.openBook(Book.book(Component.text("GateMC Guide"), Component.text("GateMC"), pages));
    }

    // ------------------------------------------------------------------ first-join cutscene

    @Command("intro")
    @CommandPermission("genscore.intro")
    @Description("Watch the GateMC intro again")
    public void introCommand(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> {
            if (inOtherFactory(p)) {
                p.sendMessage(Msg.bad("You can't watch the intro inside someone else's factory."));
                return;
            }
            startCutscene(p);
        });
    }

    private boolean inOtherFactory(Player p) {
        me.Evan52098.genscore.plots.PlotsModule plots = plugin.modules().get(me.Evan52098.genscore.plots.PlotsModule.class);
        me.Evan52098.genscore.plots.PlotService service = plots == null ? null : plots.service();
        if (service == null) {
            return false;
        }
        Location at = p.getLocation();
        me.Evan52098.genscore.plots.Plot plot = service.plotAt(at);
        return plot != null && !plot.owner().equals(p.getUniqueId()) && service.view(plot).inside(at.getBlockX(), at.getBlockZ());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        if (p.getPersistentDataContainer().has(introKey, PersistentDataType.BYTE)) {
            return;
        }
        p.getPersistentDataContainer().set(introKey, PersistentDataType.BYTE, (byte) 1);
        Tasks.entity.later(p, () -> startCutscene(p), 60L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        if (inCutscene.remove(p.getUniqueId())) {
            p.setGameMode(GameMode.SURVIVAL);
        }
        Entity camera = cameras.remove(p.getUniqueId());
        if (camera != null && camera.isValid()) {
            Tasks.entity.run(camera, camera::remove);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onStopSpectating(com.destroystokyo.paper.event.player.PlayerStopSpectatingEntityEvent event) {
        if (inCutscene.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onSpectateTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (event.getCause() == org.bukkit.event.player.PlayerTeleportEvent.TeleportCause.SPECTATE
                && inCutscene.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (inCutscene.contains(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    private void startCutscene(Player p) {
        World w = world();
        if (w == null || !p.isOnline() || !inCutscene.add(p.getUniqueId())) {
            return;
        }
        GameMode before = p.getGameMode() == GameMode.SPECTATOR ? GameMode.SURVIVAL : p.getGameMode();
        List<Shot> shots = List.of(
                new Shot(look(w, 1236, 118, 1236, CX, 64, 1152), "<gold><bold>GATEMC", "<gray>Build your factory. Raid the rest.", 90),
                new Shot(look(w, 1190, 98, 1108, CX, 66, 1148), "<yellow><bold>Spawn", "<gray>Crates, shops and helpers live here", 80),
                new Shot(look(w, 1152.5, 76, 1128, CX, 67, 1150), "<light_purple><bold>Crates", "<gray>Open them with keys from the store and playtime", 80),
                new Shot(look(w, 1128, 84, 1188, 1152, 64, 1206), "<aqua><bold>Factories", "<gray>Every player gets one along the roads", 90),
                new Shot(look(w, 1192, 80, 1170, 1172, 64, 1180), "<red><bold>Raids", "<gray>Open gates can be raided. Close yours!", 80),
                new Shot(look(w, 1152.5, 128, 1160, CX, 64, 1152.5), "<green><bold>Your turn", "<gray>Let's go to your factory", 60));
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 25, 0, false, false, false));
        p.setGameMode(GameMode.SPECTATOR);
        Location first = shots.get(0).at();
        p.teleportAsync(first).thenRun(() -> Tasks.entity.run(p, () -> {
            if (!inCutscene.contains(p.getUniqueId())) {
                return;
            }
            org.bukkit.entity.ItemDisplay camera = w.spawn(first, org.bukkit.entity.ItemDisplay.class, d -> {
                d.setPersistent(false);
                d.setTeleportDuration(3);
                d.addScoreboardTag("genscore_camera");
            });
            cameras.put(p.getUniqueId(), camera);
            p.setSpectatorTarget(camera);
            p.playSound(p.getLocation(), "minecraft:music_disc.otherside", org.bukkit.SoundCategory.RECORDS, 0.35f, 1f);
            int total = 0;
            int[] starts = new int[shots.size()];
            for (int s = 0; s < shots.size(); s++) {
                starts[s] = total;
                total += shots.get(s).ticks();
            }
            for (int s = 0; s < shots.size(); s++) {
                Shot shot = shots.get(s);
                Tasks.entity.later(p, () -> {
                    if (inCutscene.contains(p.getUniqueId())) {
                        p.showTitle(Title.title(Msg.parse(shot.title()), Msg.parse(shot.subtitle()),
                                Title.Times.times(Duration.ofMillis(500), Duration.ofMillis(Math.max(500, shot.ticks() * 50L - 1300)), Duration.ofMillis(700))));
                    }
                }, Math.max(1, starts[s]));
            }
            int frames = total / 2;
            for (int f = 1; f <= frames; f++) {
                double time = f * 2.0;
                Location at = along(shots, starts, time);
                long delay = f * 2L;
                Tasks.entity.later(camera, () -> {
                    if (inCutscene.contains(p.getUniqueId()) && camera.isValid()) {
                        camera.teleportAsync(at);
                    }
                }, delay);
            }
            Tasks.entity.later(p, () -> endCutscene(p, camera, before), total + 4L);
        }));
    }

    private void endCutscene(Player p, Entity camera, GameMode before) {
        cameras.remove(p.getUniqueId(), camera);
        if (camera.isValid()) {
            Tasks.entity.run(camera, camera::remove);
        }
        if (!p.isOnline() || !inCutscene.remove(p.getUniqueId())) {
            return;
        }
        p.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.BLINDNESS, 30, 0, false, false, false));
        p.setSpectatorTarget(null);
        p.stopSound("minecraft:music_disc.otherside");
        p.setGameMode(before);
        p.performCommand("plot");
        Tasks.entity.later(p, () -> {
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
            p.showTitle(Title.title(Msg.parse("<gold><bold>Welcome to GateMC!"), Msg.parse("<gray>This is your factory"),
                    Title.Times.times(Duration.ofMillis(300), Duration.ofMillis(2500), Duration.ofMillis(700))));
            p.sendMessage(Msg.parse("<gold><bold>Welcome to GateMC!</bold></gold> <gray>Open the menu with the</gray> <white>Nether Star</white> <gray>in slot 5. Your gate is shut for your first 2 minutes.</gray>"));
        }, 20L);
        Tasks.entity.later(p, () -> me.Evan52098.genscore.tutorial.TutorialModule.introFinished(p), 70L);
    }

    private static Location along(List<Shot> shots, int[] starts, double time) {
        int n = shots.size();
        int s = 0;
        while (s + 1 < n && time >= starts[s + 1]) {
            s++;
        }
        if (s >= n - 1) {
            return shots.get(n - 1).at().clone();
        }
        double u = (time - starts[s]) / (double) shots.get(s).ticks();
        u = Math.max(0, Math.min(1, u));
        Location p0 = shots.get(Math.max(0, s - 1)).at();
        Location p1 = shots.get(s).at();
        Location p2 = shots.get(s + 1).at();
        Location p3 = shots.get(Math.min(n - 1, s + 2)).at();
        double x = catmull(p0.getX(), p1.getX(), p2.getX(), p3.getX(), u);
        double y = catmull(p0.getY(), p1.getY(), p2.getY(), p3.getY(), u);
        double z = catmull(p0.getZ(), p1.getZ(), p2.getZ(), p3.getZ(), u);
        double e = u * u * (3 - 2 * u);
        Location l = new Location(p1.getWorld(), x, y, z);
        float dy = ((p2.getYaw() - p1.getYaw()) % 360 + 540) % 360 - 180;
        l.setYaw((float) (p1.getYaw() + dy * e));
        l.setPitch((float) (p1.getPitch() + (p2.getPitch() - p1.getPitch()) * e));
        return l;
    }

    private static Location look(World w, double x, double y, double z, double tx, double ty, double tz) {
        Location l = new Location(w, x, y, z);
        l.setDirection(new Vector(tx - x, ty - y, tz - z));
        return l;
    }

    private static double catmull(double a, double b, double c, double d, double t) {
        double t2 = t * t;
        double t3 = t2 * t;
        return 0.5 * ((2 * b) + (-a + c) * t + (2 * a - 5 * b + 4 * c - d) * t2 + (-a + 3 * b - 3 * c + d) * t3);
    }

}
