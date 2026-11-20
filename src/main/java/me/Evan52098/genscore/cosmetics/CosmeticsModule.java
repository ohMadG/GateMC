package me.Evan52098.genscore.cosmetics;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BiPredicate;
import java.util.function.Function;

/**
 * Cosmetics (the HXSMP {@code /cosmetics} wardrobe): kill effects, explosive kills, join and leave messages, name
 * colours, walking trails and tool skins, plus kill ranks and {@code /homeicon}. Definitions are in
 * {@code modules/cosmetics/cosmetics.yml}; the idea and the join/leave message, colour and effect shapes come from
 * BlockvilleCore's bvcosmetics (joinmessages.yml, leavemessages.yml, colors.yml, effects.yml).
 *
 * <p>Ownership: a cosmetic is owned when the player has its permission (store ranks through LuckPerms) or a stored
 * unlock row (crate rewards, {@link #grant}). One active per category.
 *
 * <p>Folia: every player's trail and tool-skin work runs in a timer on that player's own thread. Kill handling starts
 * on the victim's thread, hops to the killer's thread for the killer's data and permissions, then to the region of
 * the death location to draw the effect. Nothing here touches blocks; explosive kills are particles and sound only.
 */
public final class CosmeticsModule implements GensModule, Listener {

    public static final String ADMIN = "genscore.cosmetics.admin";
    public static final String HOMEICON = "genscore.homeicon";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_HEX = LegacyComponentSerializer.builder()
            .character('&').hexColors().useUnusualXRepeatedCharacterHexFormat().build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static volatile @Nullable CosmeticsModule active;

    private GensCore plugin;
    private PlayerDataService players;
    private CosmeticsStore store;
    private CosmeticsMenu menus;
    private File file;
    private volatile CosmeticsConfig config;

    /** Per-player ticker and its state. The state is only touched on that player's thread. */
    private final Map<UUID, Ticker> tickers = new ConcurrentHashMap<>();
    /** Resolved name colour (owned and active), refreshed on the player's thread; read from chat/placeholder threads. */
    private final Map<UUID, String> nameColors = new ConcurrentHashMap<>();
    /** killer|victim -> last time that pair counted as a kill (anti kill-farming). */
    private final Map<String, Long> lastPairKill = new ConcurrentHashMap<>();
    /** Optional: the staff module can say whether two players are the same person (alts). */
    private volatile @Nullable BiPredicate<UUID, UUID> altCheck;
    /** Optional: the teleport module's home names, so /homeicon only accepts real homes. */
    private volatile @Nullable Function<Player, List<String>> homeNames;

    private static final class Ticker {
        @Nullable ScheduledTask task;
        @Nullable Location lastTrail;
        int colorIndex;
        int skinCountdown;
        int ownershipCountdown;
        boolean skinShown;
    }

    @Override
    public String id() {
        return "cosmetics";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.players = plugin.playerData();
        File folder = new File(plugin.getDataFolder(), "modules/" + id());
        this.file = new File(folder, "cosmetics.yml");
        if (!file.isFile()) {
            plugin.saveResource("modules/cosmetics/cosmetics.yml", false);
        }
        this.config = CosmeticsConfig.load(file, plugin.getLogger());
        this.store = new CosmeticsStore(plugin.database(), plugin.getLogger());
        this.menus = new CosmeticsMenu(this);
        players.register(store);
        plugin.commands().register(new CosmeticsCommands(this));
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Forget old anti-farm entries now and then (plain map work, no game state).
        Tasks.async.timer(t -> pruneKillPairs(), 5, 5, TimeUnit.MINUTES);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> startTicker(p)); // plugin reload with players online
        }
        active = this;
        plugin.getLogger().info("[cosmetics] " + countLoaded() + " cosmetics loaded");
    }

    @Override
    public void reload() {
        config = CosmeticsConfig.load(file, plugin.getLogger());
        plugin.getLogger().info("[cosmetics] reloaded, " + countLoaded() + " cosmetics");
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> {
                stopTicker(p.getUniqueId());
                startTicker(p);
            });
        }
    }

    @Override
    public void disable() {
        active = null;
        HandlerList.unregisterAll(this);
        for (UUID id : tickers.keySet()) {
            stopTicker(id);
        }
        nameColors.clear();
    }

    private int countLoaded() {
        int n = 0;
        for (Map<String, Cosmetic> m : config.cosmetics.values()) {
            n += m.size();
        }
        return n;
    }

    // =========================================================================================== API for other modules

    /**
     * Name colour of an online player as a MiniMessage opening tag ({@code <red>}, {@code <gradient:#a:#b>}), or ""
     * when they have none. Safe from any thread (the chat module's async renderer). Used by the {@code chat} hook.
     */
    public static String nameColor(UUID player) {
        CosmeticsModule m = active;
        return m == null ? "" : m.nameColors.getOrDefault(player, "");
    }

    /** The player's name in their name colour (plain name when none). Safe from any thread. */
    public static Component coloredName(UUID player, String name) {
        return MM.deserialize(nameColor(player) + MM.escapeTags(name));
    }

    /** Same, as legacy text with {@code &#rrggbb} hex (TAB, scoreboards, PlaceholderAPI). */
    public static String coloredNameLegacy(UUID player, String name) {
        return LEGACY_HEX.serialize(coloredName(player, name));
    }

    /** Total counted player kills of an online player, or -1 when not loaded. */
    public static int kills(UUID player) {
        CosmeticsModule m = active;
        PlayerData data = m == null ? null : m.players.get(player);
        return data == null ? -1 : data.get(m.store).kills();
    }

    /** Kill rank (legacy {@code &} text, e.g. "&a⚔ Warrior") of an online player, or "" when not loaded / no tiers. */
    public static String killRank(UUID player) {
        CosmeticsModule m = active;
        if (m == null) {
            return "";
        }
        int k = kills(player);
        CosmeticsConfig.KillRank r = k < 0 ? null : m.config.rankFor(k);
        return r == null ? "" : r.display();
    }

    /**
     * The icon a home shows in a homes menu: the player's choice from /homeicon, or {@code fallback}. Safe from any
     * thread, but call it on the player's thread (it checks a permission). The one-line hook for the homes menu.
     */
    public static Material homeIcon(Player player, String home, Material fallback) {
        CosmeticsModule m = active;
        PlayerData data = m == null ? null : m.players.get(player.getUniqueId());
        if (data == null || !player.hasPermission(HOMEICON)) {
            return fallback;
        }
        String mat = data.get(m.store).homeIcon(home);
        Material found = mat == null ? null : Material.matchMaterial(mat);
        return found != null && found.isItem() ? found : fallback;
    }

    /**
     * Gives a stored unlock (crate rewards, Tebex commands). Completes with true if it was new, false if already
     * owned (the crate can then give something else). Works for offline players. Unknown cosmetics complete
     * exceptionally.
     */
    public CompletableFuture<Boolean> grant(UUID player, CosmeticCategory category, String id, String source) {
        if (config.get(category, id) == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No " + category.key() + " cosmetic '" + id + "'"));
        }
        return store.grant(player, category, id, source).thenApply(isNew -> {
            PlayerData data = players.get(player); // checklist 5: if they are online, show it at once
            if (data != null) {
                data.get(store).addUnlock(category, id);
            }
            plugin.getLogger().info("[cosmetics] unlock " + category.key() + ":" + id + " -> " + player
                    + " (" + source + ")" + (isNew ? "" : " [already owned]"));
            return isNew;
        });
    }

    /** Removes a stored unlock (permission unlocks are LuckPerms' business). */
    public CompletableFuture<Boolean> revoke(UUID player, CosmeticCategory category, String id) {
        PlayerData data = players.get(player);
        if (data != null) {
            data.get(store).removeUnlock(category, id); // memory first so nothing can use it during the delete
        }
        return store.revoke(player, category, id).thenApply(removed -> {
            plugin.getLogger().info("[cosmetics] revoked " + category.key() + ":" + id + " from " + player);
            return removed;
        });
    }

    /** The staff module (or anything that knows about alts) can plug in a "same person" check for kill ranks. */
    public void setAltCheck(@Nullable BiPredicate<UUID, UUID> check) {
        this.altCheck = check;
    }

    /** The teleport module can plug in the player's home names, so /homeicon only accepts existing homes. */
    public void setHomeNames(@Nullable Function<Player, List<String>> names) {
        this.homeNames = names;
    }

    // =========================================================================================== internal accessors

    CosmeticsConfig config() {
        return config;
    }

    GensCore plugin() {
        return plugin;
    }

    PlayerDataService players() {
        return players;
    }

    CosmeticsMenu menus() {
        return menus;
    }

    @Nullable PlayerCosmetics data(UUID player) {
        PlayerData d = players.get(player);
        return d == null ? null : d.get(store);
    }

    @Nullable List<String> homeNamesOf(Player player) {
        Function<Player, List<String>> f = homeNames;
        return f == null ? null : f.apply(player);
    }

    /** Owned = no permission needed, or has the permission, or has a stored unlock. Call on the player's thread. */
    boolean owns(Player player, PlayerCosmetics data, Cosmetic c) {
        return c.permission().isEmpty() || player.hasPermission(c.permission()) || player.hasPermission(ADMIN)
                || data.hasUnlock(c.category(), c.id());
    }

    /** The active cosmetic of a category if it still exists and is still owned. Player's thread. */
    @Nullable Cosmetic activeOwned(Player player, PlayerCosmetics data, CosmeticCategory cat) {
        Cosmetic c = config.get(cat, data.active(cat));
        return c != null && owns(player, data, c) ? c : null;
    }

    /** Equip or unequip (null). Called from the menu on the player's thread. */
    void setActive(Player player, PlayerCosmetics data, CosmeticCategory cat, @Nullable Cosmetic c) {
        data.setActive(cat, c == null ? null : c.id());
        if (cat == CosmeticCategory.NAME_COLOR) {
            refreshNameColor(player, data);
        } else if (cat == CosmeticCategory.TOOL_SKIN) {
            Ticker t = tickers.get(player.getUniqueId());
            if (t != null) {
                t.skinCountdown = 0; // apply or remove on the next tick
            }
        }
    }

    static String plain(String legacy) {
        return PlainTextComponentSerializer.plainText().serialize(LEGACY.deserialize(legacy));
    }

    // =========================================================================================== join / quit

    @EventHandler(priority = EventPriority.HIGH)
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerCosmetics data = data(p.getUniqueId());
        if (data == null) {
            return;
        }
        refreshNameColor(p, data);
        startTicker(p);
        // Only replace a message that is there: staff vanish (HIGHEST) still removes it afterwards.
        Cosmetic msg = activeOwned(p, data, CosmeticCategory.JOIN_MESSAGE);
        if (msg != null && msg.message() != null && event.joinMessage() != null) {
            event.joinMessage(LEGACY.deserialize(msg.message().replace("{player}", p.getName())));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        PlayerCosmetics data = data(p.getUniqueId());
        if (data != null && event.quitMessage() != null) {
            Cosmetic msg = activeOwned(p, data, CosmeticCategory.LEAVE_MESSAGE);
            if (msg != null && msg.message() != null) {
                event.quitMessage(LEGACY.deserialize(msg.message().replace("{player}", p.getName())));
            }
        }
        stopTicker(p.getUniqueId());
        nameColors.remove(p.getUniqueId());
    }

    private void refreshNameColor(Player p, PlayerCosmetics data) {
        Cosmetic c = activeOwned(p, data, CosmeticCategory.NAME_COLOR);
        if (c != null && c.color() != null) {
            nameColors.put(p.getUniqueId(), c.color());
        } else {
            nameColors.remove(p.getUniqueId());
        }
    }

    // =========================================================================================== per-player ticker

    /** Starts this player's trail/skin timer. Player's thread. */
    private void startTicker(Player p) {
        if (!p.isOnline() || tickers.containsKey(p.getUniqueId())) {
            return;
        }
        Ticker t = new Ticker();
        tickers.put(p.getUniqueId(), t);
        int every = config.trailIntervalTicks;
        t.task = Tasks.entity.timer(p, task -> tick(p, t, every), every, every);
        if (t.task == null) {
            tickers.remove(p.getUniqueId(), t); // player already gone
        }
    }

    private void stopTicker(UUID id) {
        Ticker t = tickers.remove(id);
        if (t != null && t.task != null) {
            t.task.cancel();
        }
    }

    /** Runs on the player's own thread every {@code trails.interval-ticks}. */
    private void tick(Player p, Ticker t, int every) {
        if (!p.isOnline()) {
            return;
        }
        PlayerCosmetics data = data(p.getUniqueId());
        if (data == null) {
            return;
        }
        CosmeticsConfig cfg = config;
        // Permissions can be lost (rank expired): recheck the name colour every ~5 seconds.
        t.ownershipCountdown -= every;
        if (t.ownershipCountdown <= 0) {
            t.ownershipCountdown = 100;
            refreshNameColor(p, data);
        }
        Cosmetic trail = data.active(CosmeticCategory.TRAIL) == null ? null : activeOwned(p, data, CosmeticCategory.TRAIL);
        if (trail != null && trail.effect() != null && visible(p, cfg)) {
            Location here = p.getLocation();
            Location last = t.lastTrail;
            // Rate limit: only while actually moving, at most once per interval.
            if (last == null || last.getWorld() != here.getWorld() || last.distanceSquared(here) >= cfg.trailMinMoveSquared) {
                if (last != null && last.getWorld() == here.getWorld()) {
                    // Drawn where the player stands now: that region is always owned by this thread (Folia rule 2);
                    // the player walks away from it, so it still trails behind.
                    trail.effect().play(here, p, t.colorIndex++);
                }
                t.lastTrail = here;
            }
        } else {
            t.lastTrail = null;
        }
        t.skinCountdown -= every;
        if (t.skinCountdown <= 0) {
            t.skinCountdown = cfg.toolSkinRefreshTicks;
            refreshSkin(p, t, data, cfg);
        }
    }

    private static boolean visible(Player p, CosmeticsConfig cfg) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        // Never give away an invisible player's position in PvP.
        return !cfg.trailsHideWhenInvisible || !(p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY));
    }

    /**
     * Tool skins, packet-level only: viewers get a fake equipment update with a copy of the held item that carries
     * the skin's model. The real item in the inventory is never changed, so a dropped, traded, sold or stored tool is
     * always the plain original and nothing can be duplicated. Limits: the player sees their own tool unchanged (no
     * Bukkit API can fake a player's own hand without a packet library), and the server's own equipment updates (for
     * example when the tool takes damage) show the plain tool until the next refresh. Player's thread.
     */
    private void refreshSkin(Player p, Ticker t, PlayerCosmetics data, CosmeticsConfig cfg) {
        Cosmetic skin = cfg.toolSkinsEnabled && data.active(CosmeticCategory.TOOL_SKIN) != null
                ? activeOwned(p, data, CosmeticCategory.TOOL_SKIN) : null;
        ItemStack held = p.getInventory().getItemInMainHand();
        ItemStack send;
        if (skin != null && skin.skin() != null && skin.skin().fits(held)) {
            send = skin.skin().displayCopy(held);
            t.skinShown = true;
        } else if (t.skinShown) {
            send = held.clone(); // put the plain look back once
            t.skinShown = false;
        } else {
            return;
        }
        Set<Player> viewers = p.getTrackedBy();
        UUID owner = p.getUniqueId();
        for (Player viewer : viewers) {
            // Folia rule 3: the packet goes out from the viewer's own thread.
            Tasks.entity.run(viewer, () -> {
                Player o = Bukkit.getPlayer(owner);
                if (o != null && viewer.isOnline()) {
                    viewer.sendEquipmentChange(o, EquipmentSlot.HAND, send);
                }
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onHeld(PlayerItemHeldEvent event) {
        skinSoon(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        skinSoon(event.getPlayer());
    }

    /** The held item changes after these events; refresh the skin one tick later. */
    private void skinSoon(Player p) {
        Ticker t = tickers.get(p.getUniqueId());
        if (t == null) {
            return;
        }
        Tasks.entity.later(p, () -> {
            PlayerCosmetics data = data(p.getUniqueId());
            if (data != null && tickers.get(p.getUniqueId()) == t) {
                refreshSkin(p, t, data, config);
            }
        }, 1);
    }

    // =========================================================================================== kills

    /** On the victim's thread. Copies what is needed, then continues on the killer's thread. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        Player victim = event.getEntity();
        Player killer = victim.getKiller();
        if (killer == null || killer.getUniqueId().equals(victim.getUniqueId())) {
            return;
        }
        UUID victimId = victim.getUniqueId();
        String victimName = victim.getName();
        Location where = victim.getLocation();
        String victimIp = ip(victim.getAddress());
        UUID killerId = killer.getUniqueId();
        Tasks.entity.run(killer, () -> onKill(killer, killerId, victimId, victimName, victimIp, where));
    }

    /** Killer's thread: count the kill (if allowed) and pick the effects, then draw them in the death region. */
    private void onKill(Player killer, UUID killerId, UUID victimId, String victimName, @Nullable String victimIp, Location where) {
        PlayerCosmetics data = data(killerId);
        if (data == null || !killer.isOnline()) {
            return;
        }
        CosmeticsConfig cfg = config;
        countKill(killer, data, cfg, victimId, victimName, victimIp);

        Cosmetic effect = activeOwned(killer, data, CosmeticCategory.KILL_EFFECT);
        Cosmetic explosive = activeOwned(killer, data, CosmeticCategory.EXPLOSIVE_KILL);
        if ((effect == null || effect.effect() == null) && (explosive == null || explosive.effect() == null)) {
            return;
        }
        World world = where.getWorld();
        if (world == null) {
            return;
        }
        // Folia rule 2: draw on the thread that owns the death location. Particles, sound and a lightning effect only.
        Tasks.region.run(where, () -> {
            if (effect != null && effect.effect() != null) {
                effect.effect().play(where, null, 0);
            }
            if (explosive != null && explosive.effect() != null) {
                explosive.effect().play(where, null, 0);
            }
        });
    }

    private void countKill(Player killer, PlayerCosmetics data, CosmeticsConfig cfg, UUID victimId, String victimName,
                           @Nullable String victimIp) {
        if (!cfg.sameIpKillsCount && victimIp != null && victimIp.equals(ip(killer.getAddress()))) {
            return; // same connection address: likely an alt, not counted
        }
        BiPredicate<UUID, UUID> alts = altCheck;
        if (alts != null) {
            try {
                if (alts.test(killer.getUniqueId(), victimId)) {
                    return;
                }
            } catch (RuntimeException e) {
                plugin.getLogger().warning("[cosmetics] alt check failed: " + e);
            }
        }
        String pair = killer.getUniqueId() + "|" + victimId;
        long now = System.currentTimeMillis();
        if (cfg.sameVictimCooldownMillis > 0) {
            Long last = lastPairKill.get(pair);
            if (last != null && now - last < cfg.sameVictimCooldownMillis) {
                return; // farming the same victim
            }
            lastPairKill.put(pair, now);
        }
        int before = data.kills();
        int after = data.addKill();
        CosmeticsConfig.KillRank oldRank = cfg.rankFor(before);
        CosmeticsConfig.KillRank newRank = cfg.rankFor(after);
        if (newRank != null && !Objects.equals(oldRank, newRank)) {
            killer.sendMessage(Msg.good("Kill rank up! You are now " + Msg.white(plain(newRank.display()))
                    + " (" + Msg.white(String.valueOf(after)) + " kills)."));
        }
    }

    private void pruneKillPairs() {
        long keep = Math.max(60_000L, config.sameVictimCooldownMillis);
        long now = System.currentTimeMillis();
        lastPairKill.values().removeIf(t -> now - t > keep);
    }

    private static @Nullable String ip(@Nullable InetSocketAddress address) {
        return address == null || address.getAddress() == null ? null : address.getAddress().getHostAddress();
    }

    // =========================================================================================== admin helpers

    /** Finds a player (online first, then stored), completing with null if unknown. */
    CompletableFuture<UUID> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return players.findUuid(name).thenApply(o -> o.orElse(null));
    }

    CompletableFuture<Integer> storedKills(UUID player) {
        return store.storedKills(player);
    }
}
