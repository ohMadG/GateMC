package me.Evan52098.genscore.settings;

import io.papermc.paper.event.entity.EntityKnockbackEvent;
import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.essentials.Social;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Player settings (/settings, /tptoggle) and admin preferences (/prefs). Ported from BlockvilleCore's
 * PlayerSettingsService, SettingsGui, SettingsCommand, AdminPrefsService, PrefsGui, PrefsCommand, AdminPrefsListener and
 * ForcefieldTickTask, with the menus' look and messages from its gui.yml.
 *
 * <p>Left out of the menus because GensCore has no such feature: friend requests and friend notifications, other
 * players' cosmetics, background music, and the whole Box settings page.
 *
 * <p>Other modules read the values through the public methods here ({@link #tradeRequestsEnabled}, {@link #tpaEnabled},
 * {@link #notificationSounds}, {@link #staffChatSound}, {@link #buildBypass}).
 */
public final class SettingsModule implements GensModule, Listener {

    private static final double FORCEFIELD_RADIUS = 3.0;
    private static final double HORIZONTAL_PUSH = 0.6;
    private static final double VERTICAL_PUSH = 0.35;
    static final String PREFS_PERMISSION = "genscore.prefs";

    private final PlayerSettings.Section section = new PlayerSettings.Section();
    private PlayerDataService players;
    private EssentialsBridge essentials;

    @Override
    public String id() {
        return "settings";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.players = plugin.playerData();
        this.essentials = new EssentialsBridge(plugin);
        Migrations.apply(plugin.database(), id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_settings ("
                        + "uuid TEXT PRIMARY KEY, "
                        + "notification_sounds INTEGER NOT NULL DEFAULT 1, "
                        + "tpa_enabled INTEGER NOT NULL DEFAULT 1, "
                        + "trade_requests INTEGER NOT NULL DEFAULT 1, "
                        + "staff_chat_sound INTEGER NOT NULL DEFAULT 1, "
                        + "forcefield INTEGER NOT NULL DEFAULT 0, "
                        + "ignore_velocity INTEGER NOT NULL DEFAULT 0, "
                        + "build_bypass INTEGER NOT NULL DEFAULT 0)");
        players.register(section);
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
        // Forcefield, every 4 ticks as in BlockvilleCore. Each source is handled on its own thread.
        Tasks.global.timer(t -> {
            for (Player source : Bukkit.getOnlinePlayers()) {
                PlayerSettings s = settings(source.getUniqueId());
                if (s != null && s.get(PlayerSettings.Key.FORCEFIELD)) {
                    Tasks.entity.run(source, () -> forcefield(source));
                }
            }
        }, 20, 4);
    }

    // ------------------------------------------------------------------ public API for other modules

    /** The player's settings while they are online, or null. */
    public @Nullable PlayerSettings settings(UUID uuid) {
        PlayerData data = players.get(uuid);
        return data == null ? null : data.get(section);
    }

    private boolean value(UUID uuid, PlayerSettings.Key key) {
        PlayerSettings s = settings(uuid);
        return s == null ? key.def : s.get(key);
    }

    /** False when the player turned trade requests off. */
    public boolean tradeRequestsEnabled(UUID uuid) {
        return value(uuid, PlayerSettings.Key.TRADE_REQUESTS);
    }

    /** False when the player turned teleport requests off (/tptoggle). Staff bypass with genscore.tp.toggle.bypass. */
    public boolean tpaEnabled(UUID uuid) {
        return value(uuid, PlayerSettings.Key.TPA);
    }

    /** Whether to play a sound for /msg and staff messages. */
    public boolean notificationSounds(UUID uuid) {
        return value(uuid, PlayerSettings.Key.NOTIFICATION_SOUNDS);
    }

    /** Whether staff chat plays a sound for this staff member. */
    public boolean staffChatSound(UUID uuid) {
        return value(uuid, PlayerSettings.Key.STAFF_CHAT_SOUND);
    }

    /** Build bypass for protected areas: only while the player still has the /prefs permission. */
    public boolean buildBypass(Player player) {
        return player.hasPermission(PREFS_PERMISSION) && value(player.getUniqueId(), PlayerSettings.Key.BUILD_BYPASS);
    }

    // ------------------------------------------------------------------ commands

    @Command("settings")
    @CommandPermission("genscore.command.settings")
    @Description("Manage your sound, message and TPA preferences")
    public void settingsCommand(BukkitCommandActor actor) {
        if (!(actor.sender() instanceof Player player)) {
            actor.sender().sendMessage("This command can only be used by a player.");
            return;
        }
        openMain(player);
    }

    @Command("tptoggle")
    @CommandPermission("genscore.tptoggle")
    @Description("Toggle whether others can teleport to you or send you teleport requests")
    public void tpToggle(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        PlayerSettings s = settings(player.getUniqueId());
        if (s == null) {
            return;
        }
        boolean enabled = s.toggle(PlayerSettings.Key.TPA);
        player.sendMessage(enabled ? Msg.good("Others can now teleport to you and send you teleport requests.")
                : Msg.good("Others can no longer teleport to you or send you teleport requests."));
    }

    @Command("prefs")
    @CommandPermission(PREFS_PERMISSION)
    @Description("Open the admin preferences menu")
    public void prefsCommand(BukkitCommandActor actor) {
        openPrefs(actor.requirePlayer());
    }

    // ------------------------------------------------------------------ menus (layout from BlockvilleCore's gui.yml)

    private void openMain(Player player) {
        Menu menu = new Menu(4, Msg.parse("<dark_gray>Settings"));
        nav(menu, 10, Material.NOTE_BLOCK, "&b&lSound Settings",
                List.of("Manage sound notifications", "from staff messages and", "private messages."), () -> openSounds(player));
        nav(menu, 12, Material.WRITABLE_BOOK, "&b&lMessage Settings",
                List.of("Manage private messages", "and trade requests."), () -> openMessages(player));
        nav(menu, 14, Material.ENDER_PEARL, "&b&lTPA Settings",
                List.of("Manage whether other", "players can send you", "teleport requests."), () -> openTpa(player));
        close(menu, 35);
        fill(menu);
        menu.open(player);
    }

    private void openSounds(Player player) {
        PlayerSettings s = settings(player.getUniqueId());
        if (s == null) {
            return;
        }
        Menu menu = new Menu(4, Msg.parse("<dark_gray>Sound Settings"));
        toggle(menu, 13, "Notification Sounds", "sound settings", s.get(PlayerSettings.Key.NOTIFICATION_SOUNDS),
                List.of("Plays a sound when a staff", "member messages you,", "or when you receive a /msg."), () -> {
                    boolean on = s.toggle(PlayerSettings.Key.NOTIFICATION_SOUNDS);
                    player.sendMessage(on ? Msg.good("Notification sounds enabled.") : Msg.bad("Notification sounds disabled."));
                    openSounds(player);
                });
        back(menu, 27, () -> openMain(player));
        close(menu, 35);
        fill(menu);
        menu.open(player);
    }

    private void openMessages(Player player) {
        PlayerSettings s = settings(player.getUniqueId());
        if (s == null) {
            return;
        }
        Menu menu = new Menu(4, Msg.parse("<dark_gray>Message Settings"));
        if (essentials.essentialsEnabled()) {
            Social social = essentials.social(player.getUniqueId());
            List<String> desc = List.of("When off, other players", "cannot /msg you.");
            if (social != null) {
                toggle(menu, 11, "Private Messages", "message settings", social.msgEnabled(), desc, () -> {
                    boolean on = !social.msgEnabled();
                    social.msgEnabled(on); // essentials owns this value; we only flip it
                    player.sendMessage(on ? Msg.good("Private messages enabled.") : Msg.bad("Private messages disabled."));
                    openMessages(player);
                });
            } else {
                // Fallback when the essentials accessor is not reachable: let essentials do the toggle and report it.
                nav(menu, 11, Material.PAPER, "&b&lPrivate Messages", desc, () -> {
                    player.performCommand("msgtoggle");
                    openMessages(player);
                });
            }
        }
        toggle(menu, 15, "Trade Requests", "message settings", s.get(PlayerSettings.Key.TRADE_REQUESTS),
                List.of("When off, other players", "cannot send you trade", "requests."), () -> {
                    boolean on = s.toggle(PlayerSettings.Key.TRADE_REQUESTS);
                    player.sendMessage(on ? Msg.good("Trade requests enabled.") : Msg.bad("Trade requests disabled."));
                    openMessages(player);
                });
        back(menu, 27, () -> openMain(player));
        close(menu, 35);
        fill(menu);
        menu.open(player);
    }

    private void openTpa(Player player) {
        PlayerSettings s = settings(player.getUniqueId());
        if (s == null) {
            return;
        }
        Menu menu = new Menu(4, Msg.parse("<dark_gray>TPA Settings"));
        toggle(menu, 13, "Accept TPA Requests", "tpa settings", s.get(PlayerSettings.Key.TPA),
                List.of("When off, other players", "cannot send you a /tpa", "or /tpahere request."), () -> {
                    boolean on = s.toggle(PlayerSettings.Key.TPA);
                    player.sendMessage(on ? Msg.good("TPA requests enabled.") : Msg.bad("TPA requests disabled."));
                    openTpa(player);
                });
        back(menu, 27, () -> openMain(player));
        close(menu, 35);
        fill(menu);
        menu.open(player);
    }

    private void openPrefs(Player player) {
        PlayerSettings s = settings(player.getUniqueId());
        if (s == null) {
            return;
        }
        Menu menu = new Menu(3, Msg.parse("<dark_gray>Admin Preferences"));
        prefToggle(menu, player, s, 11, PlayerSettings.Key.FORCEFIELD, "Forcefield",
                List.of("Bounces other players away", "when they get too close.", "Useful when being mobbed."));
        prefToggle(menu, player, s, 12, PlayerSettings.Key.BUILD_BYPASS, "Build Bypass",
                List.of("Lets you build and break", "in spawn/protected", "areas without needing the", "bypass permission node."));
        prefToggle(menu, player, s, 14, PlayerSettings.Key.STAFF_CHAT_SOUND, "Staff Chat Sound",
                List.of("Plays a sound when you", "receive a staff chat", "message."));
        prefToggle(menu, player, s, 15, PlayerSettings.Key.IGNORE_VELOCITY, "Ignore Velocity",
                List.of("Prevents you from being", "knocked back by attacks,", "explosions, or forcefields."));
        close(menu, 22);
        fill(menu);
        menu.open(player);
    }

    private void prefToggle(Menu menu, Player player, PlayerSettings s, int slot, PlayerSettings.Key key, String label, List<String> desc) {
        toggle(menu, slot, label, "admin preferences", s.get(key), desc, () -> {
            if (!player.hasPermission(PREFS_PERMISSION)) {
                player.closeInventory();
                return;
            }
            boolean on = s.toggle(key);
            player.sendMessage(on ? Msg.good(label + " enabled.") : Msg.bad(label + " disabled."));
            openPrefs(player);
        });
    }

    // ------------------------------------------------------------------ menu pieces

    private static void toggle(Menu menu, int slot, String label, String category, boolean on, List<String> desc, Runnable onClick) {
        Lore lore = Lore.builder(on ? Lore.Accent.GREEN : Lore.Accent.RED).subcategory(category).description(desc.toArray(String[]::new))
                .clickPrompt("Click to Toggle");
        menu.set(slot, Lore.item(on ? Material.LIME_DYE : Material.GRAY_DYE, (on ? "&a&l" : "&c&l") + label + ": " + (on ? "ON" : "OFF"),
                lore.build()), click -> onClick.run());
    }

    private static void nav(Menu menu, int slot, Material material, String name, List<String> desc, Runnable onClick) {
        Lore lore = Lore.builder(Lore.Accent.CYAN).subcategory("Settings").description(desc.toArray(String[]::new)).clickPrompt("Click to View");
        menu.set(slot, Lore.item(material, name, lore.build()), click -> onClick.run());
    }

    private static void back(Menu menu, int slot, Runnable onClick) {
        menu.set(slot, Lore.item(Material.ARROW, "&e&lBACK", List.of()), click -> onClick.run());
    }

    private static void close(Menu menu, int slot) {
        menu.set(slot, Lore.item(Material.BARRIER, "&c&lCLOSE", List.of()), click -> click.getWhoClicked().closeInventory());
    }

    /** Grey glass in every empty slot, like BlockvilleCore's global filler. */
    static void fill(Menu menu) {
        ItemStack pane = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = pane.getItemMeta();
        if (meta != null) {
            meta.setHideTooltip(true);
            pane.setItemMeta(meta);
        }
        for (int i = 0; i < menu.getInventory().getSize(); i++) {
            ItemStack existing = menu.getInventory().getItem(i);
            if (existing == null || existing.isEmpty()) {
                menu.set(i, pane);
            }
        }
    }

    // ------------------------------------------------------------------ forcefield and ignore velocity

    /** Runs on the source's thread; each push runs on the pushed player's thread. */
    private void forcefield(Player source) {
        if (!source.isOnline() || !source.hasPermission(PREFS_PERMISSION)) {
            return;
        }
        Location from = source.getLocation();
        List<Player> targets = new ArrayList<>();
        for (Entity nearby : source.getNearbyEntities(FORCEFIELD_RADIUS, FORCEFIELD_RADIUS, FORCEFIELD_RADIUS)) {
            if (nearby instanceof Player target && !target.equals(source)) {
                targets.add(target);
            }
        }
        for (Player target : targets) {
            if (ignoresVelocity(target)) {
                continue;
            }
            Tasks.onEntity(target, () -> {
                if (!target.isOnline() || !target.getWorld().equals(from.getWorld())) {
                    return;
                }
                Vector direction = target.getLocation().toVector().subtract(from.toVector());
                direction.setY(0.0);
                if (direction.lengthSquared() < 1.0E-4) {
                    direction = new Vector(1.0, 0.0, 0.0);
                } else {
                    direction.normalize();
                }
                direction.multiply(HORIZONTAL_PUSH);
                direction.setY(VERTICAL_PUSH);
                target.setVelocity(direction);
            });
        }
    }

    private boolean ignoresVelocity(Player player) {
        return player.hasPermission(PREFS_PERMISSION) && value(player.getUniqueId(), PlayerSettings.Key.IGNORE_VELOCITY);
    }

    @EventHandler(ignoreCancelled = true)
    public void onKnockback(EntityKnockbackEvent event) {
        if (event.getEntity() instanceof Player player && ignoresVelocity(player)) {
            event.setCancelled(true);
        }
    }
}
