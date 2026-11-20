package me.Evan52098.genscore.kitalerts;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.kits.KitsModule;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HxCore's {@code kit-timers}: a title and a chat line when one of the listed rank kits can be claimed again.
 *
 * <p>Every {@code check-seconds}, each online player's listed kits are checked on that player's thread. A kit that was
 * on cooldown at the last check and is ready now triggers the alert (several at once are joined into one). Kits that
 * are already ready when a player joins don't alert; kits the player can't use, or has no cooldown for (bypass), never
 * do.
 *
 * <p>The cooldown comes from the kits module. It has no public accessor yet, so this module looks for
 * {@code public long cooldownLeft(Player, String)} on {@link KitsModule} at runtime (see the integration notes for the
 * exact method). Without it the module logs one warning and stays idle.
 */
public final class KitAlertsModule implements GensModule, Listener {

    private GensCore plugin;
    private volatile Settings settings;
    private volatile Method cooldownLeft;
    /** Per player: kit id -> was on cooldown at the last check. Each inner map is only touched on its player's thread. */
    private final Map<UUID, Map<String, Boolean>> waiting = new ConcurrentHashMap<>();

    record Settings(boolean enabled, int checkSeconds, List<String> kits, Map<String, String> names, String title,
                    String subtitle, String chat) {
    }

    @Override
    public String id() {
        return "kitalerts";
    }

    @Override
    public void enable(GensCore plugin) {
        this.plugin = plugin;
        loadConfig();
        try {
            cooldownLeft = KitsModule.class.getMethod("cooldownLeft", Player.class, String.class);
            if (cooldownLeft.getReturnType() != long.class) {
                cooldownLeft = null;
            }
        } catch (NoSuchMethodException e) {
            cooldownLeft = null;
        }
        if (cooldownLeft == null) {
            plugin.getLogger().warning("[kitalerts] KitsModule has no 'public long cooldownLeft(Player, String)' yet; "
                    + "kit-ready alerts are idle until it is added.");
        }
        Bukkit.getPluginManager().registerEvents(this, plugin);
        Tasks.async.timer(t -> tick(), settings.checkSeconds(), settings.checkSeconds(), TimeUnit.SECONDS);
    }

    @Override
    public void reload() {
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("kitalerts");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        List<String> kits = new ArrayList<>();
        for (String k : c.getStringList("kits")) {
            kits.add(k.toLowerCase(Locale.ROOT));
        }
        Map<String, String> names = new ConcurrentHashMap<>();
        ConfigurationSection n = c.getConfigurationSection("names");
        if (n != null) {
            for (String k : n.getKeys(false)) {
                names.put(k.toLowerCase(Locale.ROOT), n.getString(k, k));
            }
        }
        settings = new Settings(c.getBoolean("enabled", true),
                Math.max(2, Math.min(300, c.getInt("check-seconds", 10))),
                List.copyOf(kits), Map.copyOf(names),
                c.getString("title", "&a&lKIT READY"),
                c.getString("subtitle", "{kits} &fcan be claimed again!"),
                c.getString("chat", "&a&lKITS &8» {kits} &fis ready again! &7Claim it with &e/kit"));
    }

    private void tick() {
        Settings s = settings;
        if (!s.enabled() || s.kits().isEmpty() || cooldownLeft == null) {
            return;
        }
        KitsModule kits = plugin.modules().get(KitsModule.class);
        if (kits == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            Tasks.entity.run(p, () -> check(p, kits, s)); // the kits data is read on the player's own thread
        }
    }

    private void check(Player p, KitsModule kits, Settings s) {
        if (!p.isOnline()) {
            return;
        }
        Map<String, Boolean> state = waiting.computeIfAbsent(p.getUniqueId(), k -> new ConcurrentHashMap<>());
        List<String> ready = new ArrayList<>();
        for (String kit : s.kits()) {
            long left;
            try {
                left = (long) cooldownLeft.invoke(kits, p, kit);
            } catch (ReflectiveOperationException | RuntimeException e) {
                continue; // player data not loaded yet, kit removed, ...
            }
            if (left < 0) {
                state.remove(kit); // unknown kit or no permission
                continue;
            }
            Boolean wasWaiting = state.put(kit, left > 0);
            if (left == 0 && Boolean.TRUE.equals(wasWaiting)) {
                ready.add(s.names().getOrDefault(kit, "&f" + kit));
            }
        }
        if (ready.isEmpty()) {
            return;
        }
        String list = String.join("&f, ", ready);
        p.showTitle(Title.title(Text.legacy(s.title()), Text.legacy(s.subtitle().replace("{kits}", list)),
                Title.Times.times(Duration.ofMillis(300), Duration.ofSeconds(3), Duration.ofMillis(700))));
        p.sendMessage(Text.legacy(s.chat().replace("{kits}", list)));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        waiting.remove(event.getPlayer().getUniqueId());
    }
}
