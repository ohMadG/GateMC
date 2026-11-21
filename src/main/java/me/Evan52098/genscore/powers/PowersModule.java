package me.Evan52098.genscore.powers;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiPredicate;

public final class PowersModule implements GensModule {

    public static final String USE = "genscore.powers";
    public static final String ADMIN = "genscore.powers.admin";

    private static volatile @Nullable PowersModule instance;
    private static volatile @Nullable BiPredicate<UUID, UUID> targetFilter;

    private GensCore plugin;
    private File file;
    private volatile PowersSettings settings;
    private final AtomicInteger generation = new AtomicInteger();
    private final PowersSection section = new PowersSection();
    private PlayerDataService players;
    private PowersService service;
    private PowerRuntime runtime;
    private Abilities abilities;
    private PowersMenu menu;

    public static CompletableFuture<Boolean> grant(UUID player, String powerId) {
        PowersModule m = instance;
        if (m == null || player == null || powerId == null) {
            return CompletableFuture.completedFuture(false);
        }
        return m.service.grant(player, powerId, "api");
    }

    public static @Nullable String activePowerId(UUID player) {
        PowersModule m = instance;
        if (m == null) {
            return null;
        }
        PowerData d = m.data(player);
        return d == null ? null : d.active();
    }

    public static String activePowerName(UUID player) {
        PowersModule m = instance;
        String id = activePowerId(player);
        if (m == null || id == null) {
            return "";
        }
        PowerDef def = m.settings.power(id);
        return def == null ? "" : def.name();
    }

    public static void setTargetFilter(@Nullable BiPredicate<UUID, UUID> filter) {
        targetFilter = filter;
    }

    static @Nullable BiPredicate<UUID, UUID> targetFilter() {
        return targetFilter;
    }

    @Override
    public String id() {
        return "powers";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.players = plugin.playerData();
        File folder = new File(plugin.getDataFolder(), "modules/" + id());
        this.file = new File(folder, "powers.yml");
        if (!file.isFile()) {
            plugin.saveResource("modules/powers/powers.yml", false);
        }
        this.settings = PowersSettings.load(file, plugin.getLogger());

        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_powers ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "owned TEXT NOT NULL DEFAULT '', "
                        + "active TEXT, "
                        + "free_used INTEGER NOT NULL DEFAULT 0, "
                        + "seen_update TEXT NOT NULL DEFAULT '');"
                        + "CREATE TABLE IF NOT EXISTS pvp_powers_pending ("
                        + plugin.database().autoIdColumn("id") + ", "
                        + "uuid TEXT NOT NULL, "
                        + "power TEXT NOT NULL, "
                        + "source TEXT NOT NULL, "
                        + "created_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_powers_pending_uuid ON pvp_powers_pending (uuid)");
        players.register(section);

        this.runtime = new PowerRuntime(this);
        this.abilities = new Abilities(this);
        this.service = new PowersService(this);
        this.menu = new PowersMenu(this);
        Bukkit.getPluginManager().registerEvents(runtime, plugin);
        Bukkit.getPluginManager().registerEvents(new PowerCombat(this), plugin);
        plugin.commands().register(new PowersCommands(this));

        Tasks.async.timer(t -> service.sweepPending(), 20, 20, TimeUnit.SECONDS);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> runtime.start(p));
        }
        me.Evan52098.genscore.antihack.AntiHackModule antiHack = plugin.modules().get(me.Evan52098.genscore.antihack.AntiHackModule.class);
        if (antiHack != null && antiHack.service() != null) {
            antiHack.service().addExemption(this::antiHackExempt);
        }
        instance = this;
        plugin.getLogger().info("[powers] " + settings.powers().size() + " powers loaded");
    }

    @Override
    public void reload() {
        settings = PowersSettings.load(file, plugin.getLogger());
        generation.incrementAndGet();
        plugin.getLogger().info("[powers] Reloaded, " + settings.powers().size() + " powers");
    }

    @Override
    public void disable() {
        instance = null;
        if (runtime != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (Tasks.owns(p)) {
                    runtime.stop(p);
                } else {
                    Tasks.entity.run(p, () -> runtime.stop(p));
                }
            }
        }
    }

    GensCore plugin() {
        return plugin;
    }

    PowersSettings settings() {
        return settings;
    }

    int generation() {
        return generation.get();
    }

    PlayerDataService players() {
        return players;
    }

    PowersService service() {
        return service;
    }

    PowerRuntime runtime() {
        return runtime;
    }

    Abilities abilities() {
        return abilities;
    }

    PowersMenu menu() {
        return menu;
    }

    @Nullable PowerData data(UUID uuid) {
        PlayerData pd = players.get(uuid);
        return pd == null ? null : pd.get(section);
    }

    boolean pvpProtected(UUID uuid) {
        return false;
    }

    private static final java.util.Set<String> MOVING_PASSIVES = java.util.Set.of("speedster", "moonwalker", "ant_size",
            "titan", "aqua_lungs", "ice_skater", "ember_walker");
    private static final java.util.Map<UUID, Long> GRACE = new java.util.concurrent.ConcurrentHashMap<>();

    private static final java.util.Map<UUID, java.util.Map<String, double[]>> BOOSTS = new java.util.concurrent.ConcurrentHashMap<>();

    public static double perk(UUID player, String key) {
        PowersModule m = instance;
        if (m == null || player == null) {
            return 0;
        }
        double total = 0;
        String id = activePowerId(player);
        PowerDef def = id == null ? null : m.settings.power(id);
        if (def != null && def.enabled() && def.kind() == PowerDef.Kind.PASSIVE) {
            org.bukkit.entity.Player p = Bukkit.getPlayer(player);
            if (p == null || !m.settings.worldDisabled(p.getWorld().getName())) {
                total += def.num(key, 0);
            }
        }
        total += me.Evan52098.genscore.hub.HubModule.bonus(player, key);
        total += me.Evan52098.genscore.pets.PetsModule.bonus(player, key);
        java.util.Map<String, double[]> boosts = BOOSTS.get(player);
        if (boosts != null) {
            double[] b = boosts.get(key);
            if (b != null) {
                if (b[1] > System.currentTimeMillis()) {
                    total += b[0];
                } else {
                    boosts.remove(key, b);
                }
            }
        }
        return total;
    }

    static void boost(UUID player, String key, double amount, long millis) {
        BOOSTS.computeIfAbsent(player, k -> new java.util.concurrent.ConcurrentHashMap<>())
                .put(key, new double[]{amount, System.currentTimeMillis() + millis});
    }

    static void grace(org.bukkit.entity.Player player, int ticks) {
        GRACE.merge(player.getUniqueId(), System.currentTimeMillis() + Math.max(40, ticks) * 50L, Math::max);
    }

    private boolean antiHackExempt(org.bukkit.entity.Player player) {
        Long until = GRACE.get(player.getUniqueId());
        if (until != null) {
            if (until > System.currentTimeMillis()) {
                return true;
            }
            GRACE.remove(player.getUniqueId(), until);
        }
        String active = activePowerId(player.getUniqueId());
        return active != null && MOVING_PASSIVES.contains(active);
    }
}
