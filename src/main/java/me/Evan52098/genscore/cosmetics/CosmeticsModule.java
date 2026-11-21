package me.Evan52098.genscore.cosmetics;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.potion.PotionEffectType;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Cosmetics ({@code /cosmetics}): join and leave messages, chat tags, name paints, chat colours, auras and glows, plus
 * {@code /homeicon}. Definitions are in {@code modules/cosmetics/cosmetics.yml}.
 *
 * <p>Ownership: a cosmetic is owned when the player has its permission (store ranks through LuckPerms), a stored unlock
 * row (crate rewards, {@link #grant}) or bought it with credits in the menu. One active per category.
 *
 * <p>Name paints like {@code <#FE04FD>} are animated by the resource pack's text shader. Glows set the player glowing;
 * the outline colour is their nametag team colour, which TAB takes from {@code %genscore_glow%} in the tag prefix.
 *
 * <p>Folia: every player's aura and glow work runs in a timer on that player's own thread. Chat and placeholder threads
 * only read the resolved caches.
 */
public final class CosmeticsModule implements GensModule, Listener {

    public static final String ADMIN = "genscore.cosmetics.admin";
    public static final String HOMEICON = "genscore.homeicon";

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();
    private static final LegacyComponentSerializer LEGACY_HEX = LegacyComponentSerializer.builder()
            .character('&').hexColors().hexCharacter('#').build();
    private static final MiniMessage MM = MiniMessage.miniMessage();

    private static volatile @Nullable CosmeticsModule active;

    private GensCore plugin;
    private PlayerDataService players;
    private CosmeticsStore store;
    private CosmeticsMenu menus;
    private File file;
    private volatile CosmeticsConfig config;
    private boolean homesEnabled = true;

    private final Map<UUID, Ticker> tickers = new ConcurrentHashMap<>();
    private final Map<UUID, String> nameColors = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatTags = new ConcurrentHashMap<>();
    private final Map<UUID, String> chatColors = new ConcurrentHashMap<>();
    private final Map<UUID, String> glows = new ConcurrentHashMap<>();
    private volatile @Nullable Function<Player, List<String>> homeNames;

    private static final class Ticker {
        @Nullable ScheduledTask task;
        int colorIndex;
        int auraStep;
        int ownershipCountdown;
        boolean glowing;
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
        this.homesEnabled = plugin.getConfig().getBoolean("teleport.homes.enabled", true);
        if (homesEnabled) {
            plugin.commands().register(new HomeIconCommand(this));
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.onEntity(p, () -> start(p));
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
                stopTicker(p);
                start(p);
            });
        }
    }

    @Override
    public void disable() {
        active = null;
        HandlerList.unregisterAll(this);
        for (UUID id : List.copyOf(tickers.keySet())) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && Tasks.owns(p)) {
                stopTicker(p);
            } else {
                Ticker t = tickers.remove(id);
                if (t != null && t.task != null) {
                    t.task.cancel();
                }
            }
        }
        nameColors.clear();
        chatTags.clear();
        chatColors.clear();
        glows.clear();
    }

    private int countLoaded() {
        int n = 0;
        for (Map<String, Cosmetic> m : config.cosmetics.values()) {
            n += m.size();
        }
        return n;
    }

    // =========================================================================================== API for other modules

    /** Name paint of an online player as a MiniMessage opening tag, or "" when they have none. Safe from any thread. */
    public static String nameColor(UUID player) {
        CosmeticsModule m = active;
        return m == null ? "" : m.nameColors.getOrDefault(player, "");
    }

    /** The player's name in their name paint (plain name when none). Safe from any thread. */
    public static Component coloredName(UUID player, String name) {
        return MM.deserialize(nameColor(player) + MM.escapeTags(name));
    }

    /** Same, as legacy text with {@code &#rrggbb} hex (TAB, scoreboards, PlaceholderAPI). */
    public static String coloredNameLegacy(UUID player, String name) {
        return LEGACY_HEX.serialize(coloredName(player, name));
    }

    /** The active chat tag in {@code &} codes, or "". Safe from any thread. */
    public static String chatTag(UUID player) {
        CosmeticsModule m = active;
        return m == null ? "" : m.chatTags.getOrDefault(player, "");
    }

    /** A chat message in the player's chat colour, or null when they have none. Safe from any thread. */
    public static @Nullable Component chatColored(UUID player, String raw) {
        CosmeticsModule m = active;
        String color = m == null ? null : m.chatColors.get(player);
        return color == null ? null : MM.deserialize(color + "<msg>", Placeholder.unparsed("msg", raw));
    }

    /**
     * The glow colour as a legacy code ({@code &b}), cycling for multi-colour glows, or {@code fallback} when the
     * player has no glow. TAB puts it last in the nametag prefix, which makes it the team (outline) colour.
     */
    public static String glowCode(UUID player, String fallback) {
        CosmeticsModule m = active;
        String codes = m == null ? null : m.glows.get(player);
        if (codes == null || codes.isEmpty()) {
            return fallback;
        }
        int i = (int) ((System.currentTimeMillis() / m.config.glowCycleMillis) % codes.length());
        return "&" + codes.charAt(i);
    }

    /** Opens one wardrobe category (used by /tags). Player's thread. */
    public static boolean openCategory(Player player, CosmeticCategory category) {
        CosmeticsModule m = active;
        if (m == null) {
            return false;
        }
        m.menus.openCategory(player, category, 0);
        return true;
    }

    /** The icon a home shows in a homes menu: the player's choice from /homeicon, or {@code fallback}. */
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
     * owned. Works for offline players. Unknown cosmetics complete exceptionally.
     */
    public CompletableFuture<Boolean> grant(UUID player, CosmeticCategory category, String id, String source) {
        if (config.get(category, id) == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("No " + category.key() + " cosmetic '" + id + "'"));
        }
        return store.grant(player, category, id, source).thenApply(isNew -> {
            PlayerData data = players.get(player);
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
            data.get(store).removeUnlock(category, id);
        }
        return store.revoke(player, category, id).thenApply(removed -> {
            plugin.getLogger().info("[cosmetics] revoked " + category.key() + ":" + id + " from " + player);
            return removed;
        });
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
        refreshCaches(player, data);
        Ticker t = tickers.get(player.getUniqueId());
        if (t != null && cat == CosmeticCategory.GLOW) {
            applyGlow(player, t, data);
        }
    }

    /**
     * Buys a cosmetic with credits. Player's thread. The unlock is put in memory before the database write so a second
     * click in the same tick sees it as owned; a failed write refunds and takes the unlock back.
     */
    void buy(Player player, PlayerCosmetics data, Cosmetic c, Runnable after) {
        if (owns(player, data, c)) {
            after.run();
            return;
        }
        if (c.price() <= 0) {
            player.sendMessage(Msg.bad("That cosmetic isn't for sale. " + Msg.white("Find it in crates") + "."));
            return;
        }
        ShardsService credits = ShardsModule.service();
        if (credits == null) {
            player.sendMessage(Msg.bad("Credits are not available right now."));
            return;
        }
        String reason = "cosmetic " + c.category().key() + ":" + c.id();
        if (!credits.withdraw(player.getUniqueId(), c.price(), reason)) {
            player.sendMessage(Msg.bad("You need " + Msg.white(c.price() + " credits") + " for that."));
            return;
        }
        UUID id = player.getUniqueId();
        data.addUnlock(c.category(), c.id());
        store.grant(id, c.category(), c.id(), "bought").whenComplete((isNew, error) -> {
            if (error == null) {
                plugin.getLogger().info("[cosmetics] " + player.getName() + " bought " + reason + " for " + c.price() + " credits");
                return;
            }
            plugin.getLogger().warning("[cosmetics] could not save " + player.getName() + "'s purchase of " + reason + ": " + error);
            data.removeUnlock(c.category(), c.id());
            credits.deposit(id, c.price(), "refund " + reason);
            Tasks.entity.run(player, () -> {
                player.sendMessage(Msg.bad("The purchase could not be saved. Your credits were refunded."));
                PlayerCosmetics d = data(id);
                if (d != null) {
                    refreshCaches(player, d);
                }
            });
        });
        setActive(player, data, c.category(), c);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        player.sendMessage(Msg.good("Bought and equipped " + Msg.white(plain(c.displayName())) + " for "
                + Msg.white(c.price() + " credits") + "."));
        after.run();
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
        start(p);
        Cosmetic msg = activeOwned(p, data, CosmeticCategory.JOIN_MESSAGE);
        if (msg != null && msg.message() != null && event.joinMessage() != null) {
            event.joinMessage(message(msg, p));
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onQuit(PlayerQuitEvent event) {
        Player p = event.getPlayer();
        PlayerCosmetics data = data(p.getUniqueId());
        if (data != null && event.quitMessage() != null) {
            Cosmetic msg = activeOwned(p, data, CosmeticCategory.LEAVE_MESSAGE);
            if (msg != null && msg.message() != null) {
                event.quitMessage(message(msg, p));
            }
        }
        stopTicker(p);
        UUID id = p.getUniqueId();
        nameColors.remove(id);
        chatTags.remove(id);
        chatColors.remove(id);
        glows.remove(id);
    }

    private static Component message(Cosmetic msg, Player p) {
        String[] parts = msg.message().split("\\{player}", -1);
        Component out = Component.empty();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                out = out.append(coloredName(p.getUniqueId(), p.getName()));
            }
            out = out.append(LEGACY_HEX.deserialize(parts[i]));
        }
        return out;
    }

    private void refreshCaches(Player p, PlayerCosmetics data) {
        UUID id = p.getUniqueId();
        Cosmetic paint = activeOwned(p, data, CosmeticCategory.NAME_COLOR);
        put(nameColors, id, paint == null ? null : paint.color());
        Cosmetic tag = activeOwned(p, data, CosmeticCategory.CHAT_TAG);
        put(chatTags, id, tag == null ? null : tag.message());
        Cosmetic chat = activeOwned(p, data, CosmeticCategory.CHAT_COLOR);
        put(chatColors, id, chat == null ? null : chat.color());
        Cosmetic glow = activeOwned(p, data, CosmeticCategory.GLOW);
        put(glows, id, glow == null ? null : glow.color());
    }

    private static void put(Map<UUID, String> map, UUID id, @Nullable String value) {
        if (value == null) {
            map.remove(id);
        } else {
            map.put(id, value);
        }
    }

    // =========================================================================================== per-player ticker

    private void start(Player p) {
        PlayerCosmetics data = data(p.getUniqueId());
        if (data != null) {
            refreshCaches(p, data);
        }
        startTicker(p);
    }

    private void startTicker(Player p) {
        if (!p.isOnline() || tickers.containsKey(p.getUniqueId())) {
            return;
        }
        Ticker t = new Ticker();
        tickers.put(p.getUniqueId(), t);
        int every = config.auraIntervalTicks;
        t.task = Tasks.entity.timer(p, task -> tick(p, t, every), every, every);
        if (t.task == null) {
            tickers.remove(p.getUniqueId(), t);
        }
    }

    private void stopTicker(Player p) {
        Ticker t = tickers.remove(p.getUniqueId());
        if (t == null) {
            return;
        }
        if (t.task != null) {
            t.task.cancel();
        }
        if (t.glowing && Tasks.owns(p)) {
            p.setGlowing(false);
        }
    }

    private void tick(Player p, Ticker t, int every) {
        if (!p.isOnline()) {
            return;
        }
        PlayerCosmetics data = data(p.getUniqueId());
        if (data == null) {
            return;
        }
        CosmeticsConfig cfg = config;
        t.ownershipCountdown -= every;
        if (t.ownershipCountdown <= 0) {
            t.ownershipCountdown = 100;
            refreshCaches(p, data);
            applyGlow(p, t, data);
        }
        Cosmetic aura = data.active(CosmeticCategory.AURA) == null ? null : activeOwned(p, data, CosmeticCategory.AURA);
        if (aura != null && aura.effect() != null && visible(p, cfg)) {
            t.auraStep++;
            if (t.auraStep % 2 == 0) {
                Location base = p.getLocation();
                double phase = t.auraStep * 0.35;
                double height = 0.15 + (Math.sin(t.auraStep * 0.12) + 1) * 0.85;
                for (int k = 0; k < 2; k++) {
                    double a = phase + k * Math.PI;
                    aura.effect().play(base.clone().add(Math.cos(a) * 0.75, height, Math.sin(a) * 0.75), p, t.colorIndex++);
                }
            }
        }
    }

    private void applyGlow(Player p, Ticker t, PlayerCosmetics data) {
        boolean want = activeOwned(p, data, CosmeticCategory.GLOW) != null && visible(p, config);
        if (want != t.glowing) {
            t.glowing = want;
            p.setGlowing(want);
        }
    }

    private static boolean visible(Player p, CosmeticsConfig cfg) {
        if (p.isDead() || p.getGameMode() == GameMode.SPECTATOR) {
            return false;
        }
        return !cfg.hideWhenInvisible || !(p.isInvisible() || p.hasPotionEffect(PotionEffectType.INVISIBILITY));
    }

    // =========================================================================================== admin helpers

    CompletableFuture<UUID> resolve(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) {
            return CompletableFuture.completedFuture(online.getUniqueId());
        }
        return players.findUuid(name).thenApply(o -> o.orElse(null));
    }

    public List<String> cosmeticIds() {
        CosmeticsConfig c = config;
        List<String> out = new ArrayList<>();
        if (c != null) {
            for (Map<String, Cosmetic> m : c.cosmetics.values()) {
                out.addAll(m.keySet());
            }
        }
        out.sort(null);
        return out;
    }

    public List<String> cosmeticCategories() {
        List<String> out = new ArrayList<>();
        for (CosmeticCategory c : CosmeticCategory.values()) {
            out.add(c.key());
        }
        return out;
    }

    boolean homesEnabled() {
        return homesEnabled;
    }
}
