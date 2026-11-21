package me.Evan52098.genscore.basestyles;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.plots.PlotStyles;
import me.Evan52098.genscore.plots.PlotsModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BaseStylesModule implements GensModule {

    public static final String USE = "genscore.basestyle";
    public static final String ADMIN = "genscore.basestyle.admin";

    record Def(String id, String name, Material icon, List<String> lore, List<String> ranks, PlotStyles.Style style) {
    }

    private record Settings(String title, String defaultId, long cooldownMs, Map<String, Def> styles) {
    }

    static final class Choice {
        private volatile String style;
        private volatile boolean dirty;

        String style() {
            return style;
        }

        void set(String id) {
            style = id;
            dirty = true;
        }

        boolean takeDirty() {
            boolean d = dirty;
            dirty = false;
            return d;
        }
    }

    private final PlayerSection<Choice> section = new PlayerSection<>() {
        @Override
        public String id() {
            return "basestyle";
        }

        @Override
        public Choice load(Connection c, UUID player) throws SQLException {
            Choice choice = new Choice();
            try (PreparedStatement ps = c.prepareStatement("SELECT style FROM gens_base_styles WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        choice.style = rs.getString(1);
                    }
                }
            }
            return choice;
        }

        @Override
        public void save(Connection c, UUID player, Choice data) throws SQLException {
            if (!data.takeDirty()) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO gens_base_styles (uuid, style) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET style = excluded.style")) {
                ps.setString(1, player.toString());
                ps.setString(2, data.style());
                ps.executeUpdate();
            }
        }
    };

    private GensCore plugin;
    private PlayerDataService players;
    private File file;
    private volatile Settings settings;
    private final Cooldowns cooldowns = new Cooldowns();

    @Override
    public String id() {
        return "basestyles";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.players = plugin.playerData();
        this.file = new File(plugin.getDataFolder(), "modules/basestyles/styles.yml");
        if (!file.isFile()) {
            plugin.saveResource("modules/basestyles/styles.yml", false);
        }
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS gens_base_styles (uuid TEXT PRIMARY KEY, style TEXT NOT NULL)");
        settings = load();
        players.register(section);
        plugin.commands().register(this);
        PlotStyles.set(this::styleFor);
        plugin.getLogger().info("[basestyles] " + settings.styles().size() + " base styles loaded");
    }

    @Override
    public void disable() {
        PlotStyles.set(null);
    }

    @Override
    public void reload() {
        settings = load();
    }

    private Settings load() {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        Map<String, Def> styles = new LinkedHashMap<>();
        ConfigurationSection list = y.getConfigurationSection("styles");
        if (list != null) {
            for (String key : list.getKeys(false)) {
                ConfigurationSection s = list.getConfigurationSection(key);
                if (s == null) {
                    continue;
                }
                String id = key.toLowerCase(Locale.ROOT);
                Map<Material, Material> palette = new EnumMap<>(Material.class);
                ConfigurationSection p = s.getConfigurationSection("palette");
                if (p != null) {
                    for (String from : p.getKeys(false)) {
                        Material a = Material.matchMaterial(from);
                        Material b = Material.matchMaterial(String.valueOf(p.getString(from)));
                        if (a == null || b == null || !a.isBlock() || !b.isBlock()) {
                            plugin.getLogger().warning("[basestyles] " + id + ": bad palette entry " + from + " -> " + p.getString(from));
                            continue;
                        }
                        palette.put(a, b);
                    }
                }
                Material door = Material.matchMaterial(s.getString("door", ""));
                Material icon = Material.matchMaterial(s.getString("icon", "STONE_BRICKS"));
                List<String> ranks = new ArrayList<>();
                for (String r : s.getStringList("ranks")) {
                    ranks.add(r.toLowerCase(Locale.ROOT));
                }
                styles.put(id, new Def(id, s.getString("name", id), icon == null || !icon.isItem() ? Material.STONE_BRICKS : icon,
                        s.getStringList("lore"), List.copyOf(ranks),
                        new PlotStyles.Style(id, Map.copyOf(palette), door != null && door.isBlock() ? door : null)));
            }
        }
        String def = y.getString("default", "stonework").toLowerCase(Locale.ROOT);
        if (!styles.containsKey(def) && !styles.isEmpty()) {
            def = styles.keySet().iterator().next();
        }
        return new Settings(y.getString("menu-title", "&f&lBASE STYLES"), def,
                Math.max(0, y.getLong("change-cooldown", 30)) * 1000L, styles);
    }

    boolean unlocked(Player p, Def def) {
        if (def.ranks().isEmpty() || p.hasPermission("genscore.basestyle." + def.id())) {
            return true;
        }
        for (String rank : def.ranks()) {
            if (p.hasPermission("group." + rank)) {
                return true;
            }
        }
        return false;
    }

    private @Nullable Choice choice(UUID uuid) {
        PlayerData pd = players.get(uuid);
        return pd == null ? null : pd.get(section);
    }

    private Def current(Player p) {
        Settings s = settings;
        Choice c = choice(p.getUniqueId());
        Def def = c == null || c.style() == null ? null : s.styles().get(c.style());
        if (def == null || !unlocked(p, def)) {
            def = s.styles().get(s.defaultId());
        }
        return def;
    }

    private @Nullable PlotStyles.Style styleFor(UUID owner) {
        Player p = Bukkit.getPlayer(owner);
        if (p == null) {
            return null;
        }
        Def def = current(p);
        return def == null ? null : def.style();
    }

    @Command({"basestyle", "basestyles", "styles", "factorystyle"})
    @CommandPermission(USE)
    @Description("Choose your factory's base style")
    public void open(BukkitCommandActor actor) {
        Player p = actor.requirePlayer();
        Tasks.onEntity(p, () -> menu(p));
    }

    @Command("basestyle reload")
    @CommandPermission(ADMIN)
    @Description("Reload the base styles")
    public void reloadCommand(BukkitCommandActor actor) {
        Tasks.async.run(() -> {
            settings = load();
            actor.sender().sendMessage(Msg.good("Reloaded " + Msg.white(String.valueOf(settings.styles().size())) + " base styles."));
        });
    }

    private void menu(Player p) {
        Settings s = settings;
        List<Def> defs = new ArrayList<>(s.styles().values());
        int rows = Math.max(3, Math.min(6, 2 + (defs.size() + 6) / 7));
        Menu menu = new Menu(rows, MenuStyle.title("Base Styles"));
        Def active = current(p);
        int[] slots = MenuStyle.symmetric(defs.size(), 1);
        for (int i = 0; i < defs.size() && i < slots.length; i++) {
            Def def = defs.get(i);
            boolean open = unlocked(p, def);
            boolean on = active != null && active.id().equals(def.id());
            Lore lore = Lore.builder(open ? Lore.Accent.CYAN : Lore.Accent.RED).subcategory("Base style");
            for (String line : def.lore()) {
                lore.description(line);
            }
            lore.divider();
            if (on) {
                lore.stat("Status", "&aIn use");
            } else if (open) {
                lore.stat("Status", "&aUnlocked").clickPrompt("Click to rebuild in this style");
            } else {
                lore.stat("Status", "&cLocked").description("Unlocked with the " + rankName(def) + " &7rank.", "Get ranks at &e/store&7.");
            }
            ItemStack item = Lore.item(open ? def.icon() : Material.GRAY_DYE, def.name(), lore.build());
            menu.set(slots[i], MenuStyle.clean(item, on), c -> choose(p, def));
        }
        menu.set(MenuStyle.shelf(rows, 4), MenuStyle.close(), c -> p.closeInventory());
        MenuStyle.frame(menu, rows);
        menu.open(p);
    }

    private static String rankName(Def def) {
        if (def.ranks().isEmpty()) {
            return "&7any";
        }
        return switch (def.ranks().get(0)) {
            case "vip" -> "&bMechanic";
            case "vipplus" -> "&9Machinist";
            case "mvp" -> "&dOverseer";
            case "mvpplus" -> "&5Industrialist";
            case "legend" -> "&6Tycoon";
            default -> "&f" + def.ranks().get(0);
        };
    }

    private void choose(Player p, Def def) {
        if (!unlocked(p, def)) {
            p.sendMessage(Msg.bad("You need the " + Msg.white(Msg.escape(stripped(rankName(def)))) + " rank for that style. See " + Msg.white("/store") + "."));
            return;
        }
        Def active = current(p);
        if (active != null && active.id().equals(def.id())) {
            p.sendMessage(Msg.neutral("Your factory already uses that style."));
            return;
        }
        long left = cooldowns.remaining(p.getUniqueId(), "basestyle");
        if (left > 0) {
            p.sendMessage(Msg.bad("Wait " + Msg.white(Cooldowns.format(left)) + " before changing style again."));
            return;
        }
        me.Evan52098.genscore.factory.FactoryModule factory = plugin.modules().get(me.Evan52098.genscore.factory.FactoryModule.class);
        if (factory != null && factory.raidDamaged(p.getUniqueId())) {
            p.sendMessage(Msg.bad("Repair your broken belts before changing style."));
            return;
        }
        Choice c = choice(p.getUniqueId());
        if (c == null) {
            p.sendMessage(Msg.bad("Your data is still loading, try again in a moment."));
            return;
        }
        c.set(def.id());
        cooldowns.start(p.getUniqueId(), "basestyle", settings.cooldownMs());
        p.closeInventory();
        PlotsModule plots = plugin.modules().get(PlotsModule.class);
        if (plots == null || plots.service() == null) {
            p.sendMessage(Msg.good("Style saved. Your factory uses it next time it is built."));
            return;
        }
        p.sendMessage(Msg.good("Rebuilding your factory in " + Msg.white(Msg.escape(stripped(def.name()))) + "..."));
        plots.service().rebuildFor(p.getUniqueId()).whenComplete((ok, error) -> Tasks.onEntity(p, () -> {
            if (error == null && Boolean.TRUE.equals(ok)) {
                p.sendMessage(Msg.good("Your factory now uses " + Msg.white(Msg.escape(stripped(def.name()))) + "."));
            } else {
                p.sendMessage(Msg.neutral("Style saved. Your factory uses it next time it is built."));
            }
        }));
    }

    private static String stripped(String legacy) {
        return legacy.replaceAll("[&§][0-9a-fk-orA-FK-OR]", "");
    }
}
