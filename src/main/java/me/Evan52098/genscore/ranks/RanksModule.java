package me.Evan52098.genscore.ranks;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.info.Bedrock;
import me.Evan52098.genscore.info.Text;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.player.PlayerSection;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Rank badges and chat tags from HxCore ({@code ranks:}, {@code tags:}, {@code tags-menu-title}).
 * <ul>
 *   <li>Badge: the first entry under {@code ranks.badges} whose permission the player has (default
 *       {@code group.<id>}, which LuckPerms gives every member of that group; {@code default} matches everyone).
 *       Java players see the resource-pack glyph ({@code logo}) when there is one and {@code use-logo} is on; Bedrock
 *       players (Floodgate) and ranks without a glyph get {@code text}.</li>
 *   <li>Tags: unlocked with {@code genscore.tag.<id>}; the player picks one in {@code /tags}. The choice is stored per
 *       player ({@code pvp_player_tags}). A tag whose permission was taken away stops showing at once.</li>
 * </ul>
 * Chat formatting belongs to the chat module and PlaceholderAPI is not a compile dependency, so this module exposes
 * static lookups ({@link #badge}, {@link #tag}, {@link #placeholder}) that the chat module and a PlaceholderAPI
 * expansion call. They are safe from any thread (chat renders off the main thread) and return "" when the module is off.
 */
public final class RanksModule implements GensModule, Listener {

    record Badge(String id, String permission, String logo, String text) {
    }

    record Tag(String id, String display) {
    }

    /** A player's chosen tag. Thread-safe: changed on the player's thread, saved on a database thread. */
    static final class Choice {
        private String tag;
        private boolean dirty;

        synchronized @Nullable String tag() {
            return tag;
        }

        synchronized void set(@Nullable String tag) {
            this.tag = tag;
            this.dirty = true;
        }

        synchronized boolean takeDirty() {
            boolean d = dirty;
            dirty = false;
            return d;
        }
    }

    private static final Pattern TAG_ID = Pattern.compile("[a-z0-9_\\-]{1,32}");
    private static final int[] TAG_SLOTS = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 31, 32, 33, 34};

    private static volatile RanksModule instance;

    private GensCore plugin;
    private PlayerDataService players;
    private final Cooldowns cooldowns = new Cooldowns();
    private volatile boolean useLogo = true;
    private volatile List<Badge> badges = List.of();
    private volatile Map<String, Tag> tags = Map.of();
    private volatile String menuTitle = "&c&l❄ &f&lTAGS &c&l❄";

    private final PlayerSection<Choice> section = new PlayerSection<>() {
        @Override
        public String id() {
            return "tags";
        }

        @Override
        public Choice load(Connection c, UUID player) throws SQLException {
            Choice choice = new Choice();
            try (PreparedStatement ps = c.prepareStatement("SELECT tag FROM pvp_player_tags WHERE uuid = ?")) {
                ps.setString(1, player.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        choice.tag = rs.getString(1);
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
            String tag = data.tag();
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO pvp_player_tags (uuid, tag) VALUES (?, ?) ON CONFLICT (uuid) DO UPDATE SET tag = excluded.tag")) {
                ps.setString(1, player.toString());
                ps.setString(2, tag);
                ps.executeUpdate();
            }
        }
    };

    @Override
    public String id() {
        return "ranks";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.plugin = plugin;
        this.players = plugin.playerData();
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_player_tags (uuid TEXT PRIMARY KEY, tag TEXT)");
        loadConfig();
        players.register(section);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        instance = this;
    }

    @Override
    public void disable() {
        instance = null;
    }

    @Override
    public void reload() {
        loadConfig();
    }

    private void loadConfig() {
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("ranks");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        useLogo = c.getBoolean("use-logo", true);
        menuTitle = c.getString("tags-menu-title", menuTitle);
        List<Badge> b = new ArrayList<>();
        ConfigurationSection bs = c.getConfigurationSection("badges");
        if (bs != null) {
            for (String id : bs.getKeys(false)) { // config order: highest rank first
                ConfigurationSection s = bs.getConfigurationSection(id);
                if (s == null) {
                    continue;
                }
                String perm = s.getString("permission", id.equalsIgnoreCase("default") ? "" : "group." + id.toLowerCase(Locale.ROOT));
                b.add(new Badge(id, perm == null ? "" : perm, s.getString("logo", ""), s.getString("text", "")));
            }
        }
        badges = List.copyOf(b);
        Map<String, Tag> t = new LinkedHashMap<>();
        ConfigurationSection ts = c.getConfigurationSection("tags");
        if (ts != null) {
            for (String id : ts.getKeys(false)) {
                String key = id.toLowerCase(Locale.ROOT);
                if (!TAG_ID.matcher(key).matches()) {
                    plugin.getLogger().warning("[ranks] Tag id '" + id + "' skipped: use a-z, 0-9, _ and - only.");
                    continue;
                }
                ConfigurationSection s = ts.getConfigurationSection(id);
                String display = s != null ? s.getString("display", id) : ts.getString(id, id);
                t.put(key, new Tag(key, display));
            }
        }
        tags = java.util.Collections.unmodifiableMap(t);
        Bedrock.reset();
    }

    // ------------------------------------------------------------------ lookups (any thread)

    private @Nullable Badge badgeOf(Player p) {
        for (Badge b : badges) {
            if (b.permission().isEmpty() || p.hasPermission(b.permission())) {
                return b;
            }
        }
        return null;
    }

    private String badgeString(Player p, boolean allowLogo) {
        Badge b = badgeOf(p);
        if (b == null) {
            return "";
        }
        if (allowLogo && useLogo && !b.logo().isEmpty() && !Bedrock.is(p.getUniqueId())) {
            return b.logo();
        }
        return b.text();
    }

    private @Nullable Tag tagOf(Player p) {
        PlayerData data = players.get(p.getUniqueId());
        if (data == null) {
            return null;
        }
        String id;
        try {
            id = data.get(section).tag();
        } catch (IllegalStateException e) {
            return null; // section not registered (module registered after player data started)
        }
        if (id == null) {
            return null;
        }
        Tag tag = tags.get(id);
        return tag != null && p.hasPermission("genscore.tag." + tag.id()) ? tag : null;
    }

    /** The player's rank badge in {@code &} codes: the glyph for Java players when set, else the text. "" if none. */
    public static String badge(Player p) {
        RanksModule m = instance;
        return m == null ? "" : m.badgeString(p, true);
    }

    /** The text badge, never the glyph (for places a resource pack can't reach, like the console or Discord). */
    public static String badgeText(Player p) {
        RanksModule m = instance;
        return m == null ? "" : m.badgeString(p, false);
    }

    /** The player's chosen, still-unlocked tag in {@code &} codes, or "". */
    public static String tag(Player p) {
        RanksModule m = instance;
        Tag t = m == null ? null : m.tagOf(p);
        return t == null ? "" : t.display();
    }

    public static Component badgeComponent(Player p) {
        return Text.legacy(badge(p));
    }

    /** The tag followed by a space, or empty, ready to put in front of a name. */
    public static Component tagComponent(Player p) {
        String t = tag(p);
        return t.isEmpty() ? Component.empty() : Text.legacy(t + "&r ");
    }

    /**
     * PlaceholderAPI values ({@code %genscore_<params>%}): {@code badge}, {@code badge_text}, {@code rank} (the badge
     * id), {@code tag}, {@code tag_id}. Null for anything else. Values are {@code &} codes.
     */
    public static @Nullable String placeholder(@Nullable Player p, String params) {
        RanksModule m = instance;
        if (m == null || p == null) {
            return null;
        }
        return switch (params.toLowerCase(Locale.ROOT)) {
            case "badge" -> m.badgeString(p, true);
            case "badge_text" -> m.badgeString(p, false);
            case "rank" -> {
                Badge b = m.badgeOf(p);
                yield b == null ? "" : b.id();
            }
            case "tag" -> tag(p);
            case "tag_id" -> {
                Tag t = m.tagOf(p);
                yield t == null ? "" : t.id();
            }
            default -> null;
        };
    }

    // ------------------------------------------------------------------ /tags

    @Command({"tags", "tag"})
    @CommandPermission("genscore.tags")
    @Description("Pick a chat tag")
    public void tagsCommand(BukkitCommandActor actor) {
        openTags(actor.requirePlayer(), 0);
    }

    private void openTags(Player player, int page) {
        List<Tag> all = new ArrayList<>(tags.values());
        int pages = Math.max(1, (all.size() + TAG_SLOTS.length - 1) / TAG_SLOTS.length);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Choice choice = players.get(player).get(section);
        String current = choice.tag();
        Menu menu = new Menu(6, Text.legacy(menuTitle));
        for (int i = 0; i < TAG_SLOTS.length; i++) {
            int index = pg * TAG_SLOTS.length + i;
            if (index >= all.size()) {
                break;
            }
            Tag tag = all.get(index);
            boolean unlocked = player.hasPermission("genscore.tag." + tag.id());
            boolean equipped = unlocked && tag.id().equals(current);
            List<String> lore = new ArrayList<>();
            lore.add("&8" + tag.id());
            lore.add("");
            lore.add("&7Preview: " + tag.display() + " &f" + player.getName());
            lore.add("");
            lore.add(equipped ? "&a✔ Equipped" : unlocked ? "&eClick to equip" : "&c✘ Locked");
            ItemStack item = item(unlocked ? Material.NAME_TAG : Material.GRAY_DYE, tag.display(), lore, equipped);
            if (!unlocked || equipped) {
                menu.set(TAG_SLOTS[i], item);
                continue;
            }
            menu.set(TAG_SLOTS[i], item, click -> choose(player, tag.id(), pg));
        }
        if (current != null) {
            menu.set(49, item(Material.BARRIER, "&cRemove tag", List.of("&7Stop showing a tag."), false), click -> choose(player, null, pg));
        }
        if (pg > 0) {
            menu.set(45, item(Material.ARROW, "&ePrevious page", List.of(), false), click -> openTags(player, pg - 1));
        }
        if (pg < pages - 1) {
            menu.set(53, item(Material.ARROW, "&eNext page", List.of(), false), click -> openTags(player, pg + 1));
        }
        if (all.isEmpty()) {
            menu.set(22, item(Material.PAPER, "&7No tags yet", List.of(), false));
        }
        menu.open(player);
    }

    /** Equips (or removes, with null) a tag. Player's thread. */
    private void choose(Player player, @Nullable String tagId, int page) {
        // Each change is saved to the database, so clicks are rate limited (checklist 3, 10).
        if (!cooldowns.tryStart(player.getUniqueId(), "tag", 750)) {
            return;
        }
        if (tagId != null && (!tags.containsKey(tagId) || !player.hasPermission("genscore.tag." + tagId))) {
            player.sendMessage(Msg.bad("You haven't unlocked that tag."));
            return;
        }
        players.get(player).get(section).set(tagId);
        players.saveSoon(player.getUniqueId());
        if (tagId == null) {
            player.sendMessage(Msg.good("Tag removed."));
        } else {
            player.sendMessage(Msg.good("Tag equipped: ").append(Text.legacy(tags.get(tagId).display())));
        }
        openTags(player, page);
    }

    private static ItemStack item(Material material, String name, List<String> lore, boolean glow) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(Text.item(name));
        List<Component> lines = new ArrayList<>();
        for (String l : lore) {
            lines.add(Text.item(l));
        }
        meta.lore(lines);
        if (glow) {
            meta.setEnchantmentGlintOverride(true);
        }
        meta.addItemFlags(ItemFlag.values());
        stack.setItemMeta(meta);
        return stack;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cooldowns.clear(event.getPlayer().getUniqueId(), "tag");
    }
}
