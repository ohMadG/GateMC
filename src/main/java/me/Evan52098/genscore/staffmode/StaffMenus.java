package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.factory.FactoryModule;
import me.Evan52098.genscore.menu.Lore;
import me.Evan52098.genscore.menu.Menu;
import me.Evan52098.genscore.menu.MenuStyle;
import me.Evan52098.genscore.player.PlayerData;
import me.Evan52098.genscore.ranks.RanksModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.shards.ShardsModule;
import me.Evan52098.genscore.shards.ShardsService;
import me.Evan52098.genscore.staff.StaffTools;
import me.Evan52098.genscore.util.Bedrock;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

final class StaffMenus {

    private static final int PER_PAGE = 28;

    private record Snapshot(String world, int x, int y, int z, double health, double maxHealth, int food, int ping,
                            String mode, boolean flying) {
    }

    private final GensCore plugin;
    private final StaffModeService service;
    private final @Nullable StaffTools tools;

    StaffMenus(GensCore plugin, StaffModeService service, @Nullable StaffTools tools) {
        this.plugin = plugin;
        this.service = service;
        this.tools = tools;
    }

    void players(Player staff, int page) {
        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.remove(staff);
        online.sort(Comparator.comparing(p -> p.getName().toLowerCase(Locale.ROOT)));
        int pages = Math.max(1, (online.size() + PER_PAGE - 1) / PER_PAGE);
        int pg = Math.max(0, Math.min(page, pages - 1));
        Menu menu = new Menu(6, MenuStyle.title("Staff", "Players"));
        menu.set(4, MenuStyle.clean(Lore.item(Material.SPYGLASS, "&b&lONLINE PLAYERS", Lore.builder(Lore.Accent.CYAN)
                .stat("Online", String.valueOf(online.size() + 1))
                .description("Click a player to open their profile.").build()), false));
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = pg * PER_PAGE + i;
            if (idx >= online.size()) {
                break;
            }
            Player target = online.get(idx);
            int slot = 10 + (i / 7) * 9 + (i % 7);
            List<String> lore = new ArrayList<>();
            lore.add("&7Rank: " + RanksModule.badgeText(target));
            lore.add("&7Balance: &a" + value(target.getUniqueId(), "balance"));
            lore.add("&7Gen tier: &f" + value(target.getUniqueId(), "gen_tier") + " &8• &7Rebirths: &d" + value(target.getUniqueId(), "rebirths"));
            lore.add("&7Ping: &f" + target.getPing() + "ms" + (Bedrock.is(target) ? " &8• &bBedrock" : ""));
            if (service.vanish().isVanished(target)) {
                lore.add("&8Vanished");
            }
            if (service.inStaffMode(target.getUniqueId())) {
                lore.add("&8In staff mode");
            }
            if (tools != null && tools.isFrozen(target.getUniqueId())) {
                lore.add("&bFrozen");
            }
            lore.add("");
            lore.add(Lore.prompt("view profile"));
            menu.set(slot, head(target, "&f" + target.getName(), lore), c -> profile(staff, target.getUniqueId(), pg));
        }
        if (online.isEmpty()) {
            menu.set(22, MenuStyle.icon(Material.BARRIER, "&7Nobody else is online", List.of()));
        }
        if (pg > 0) {
            menu.set(48, MenuStyle.previous(pg + 1, pages), c -> players(staff, pg - 1));
        }
        if (pg < pages - 1) {
            menu.set(50, MenuStyle.next(pg + 1, pages), c -> players(staff, pg + 1));
        }
        menu.set(49, MenuStyle.close(), c -> staff.closeInventory());
        if (random != null) {
            menu.set(47, MenuStyle.icon(Material.COMPASS, "&d&lRandom teleport", List.of("&7Go to a random visible player.", "", Lore.prompt("teleport"))),
                    c -> {
                        staff.closeInventory();
                        random.accept(staff);
                    });
        }
        MenuStyle.frame(menu, 6);
        menu.open(staff);
    }

    private java.util.function.Consumer<Player> random;

    StaffMenus random(java.util.function.Consumer<Player> random) {
        this.random = random;
        return this;
    }

    void profile(Player staff, UUID id, int back) {
        Player target = Bukkit.getPlayer(id);
        if (target == null) {
            staff.sendMessage(Msg.bad("That player is offline."));
            players(staff, back);
            return;
        }
        Tasks.entity.run(target, () -> {
            Location l = target.getLocation();
            var max = target.getAttribute(Attribute.MAX_HEALTH);
            Snapshot snap = new Snapshot(l.getWorld().getName(), l.getBlockX(), l.getBlockY(), l.getBlockZ(), target.getHealth(),
                    max == null ? 20 : max.getValue(), target.getFoodLevel(), target.getPing(),
                    target.getGameMode().name().toLowerCase(Locale.ROOT), target.isFlying());
            Tasks.entity.run(staff, () -> showProfile(staff, target, snap, back));
        });
    }

    private void showProfile(Player staff, Player target, Snapshot snap, int back) {
        UUID id = target.getUniqueId();
        Menu menu = new Menu(6, MenuStyle.title("Staff", target.getName()));
        boolean frozen = tools != null && tools.isFrozen(id);
        boolean vanished = service.vanish().isVanished(target);
        PlayerData pd = plugin.playerData().get(id);
        List<String> info = new ArrayList<>();
        info.add("&7Rank: " + RanksModule.badgeText(target));
        info.add("&7Client: &f" + (Bedrock.is(target) ? "Bedrock" : "Java") + " &8• &7Ping: &f" + snap.ping() + "ms");
        info.add("&7Playtime: &f" + (pd == null ? "?" : duration(pd.playtimeMs())));
        info.add("&7UUID: &8" + id);
        if (vanished) {
            info.add("&8Vanished");
        }
        if (service.inStaffMode(id)) {
            info.add("&8In staff mode");
        }
        menu.set(4, head(target, "&b&l" + target.getName(), info));

        menu.set(19, MenuStyle.clean(Lore.item(Material.GOLD_INGOT, "&6&lECONOMY", Lore.builder(Lore.Accent.WHITE)
                .stat("Balance", value(id, "balance"))
                .stat("Per hour", value(id, "income_hour"))
                .stat("Tokens", value(id, "tokens"))
                .stat("Credits", credits(id)).build()), false));
        menu.set(20, MenuStyle.clean(Lore.item(Material.HOPPER, "&e&lFACTORY", Lore.builder(Lore.Accent.WHITE)
                .stat("Gen tier", value(id, "gen_tier") + "/" + value(id, "gen_max"))
                .stat("Rebirths", value(id, "rebirths"))
                .stat("Belt", value(id, "belt") + "/" + value(id, "belt_max"))
                .stat("Machines", value(id, "machines") + "/5")
                .stat("Gate", strip(value(id, "gate")))
                .stat("Shield", strip(value(id, "shield"))).build()), false));
        menu.set(21, MenuStyle.clean(Lore.item(Material.RED_DYE, "&c&lSTATUS", Lore.builder(Lore.Accent.RED)
                .stat("Health", Math.round(snap.health()) + "/" + Math.round(snap.maxHealth()))
                .stat("Food", String.valueOf(snap.food()))
                .stat("Mode", snap.mode() + (snap.flying() ? " (flying)" : ""))
                .stat("Frozen", frozen ? "yes" : "no").build()), false));
        menu.set(22, MenuStyle.clean(Lore.item(Material.COMPASS, "&a&lLOCATION", Lore.builder(Lore.Accent.GREEN)
                .stat("World", snap.world())
                .stat("At", snap.x() + " " + snap.y() + " " + snap.z()).build()), false));

        menu.set(24, MenuStyle.icon(Material.ENDER_PEARL, "&d&lTeleport", List.of("&7Go to " + target.getName() + ".", "", Lore.prompt("teleport"))),
                c -> teleport(staff, target));
        menu.set(25, MenuStyle.icon(frozen ? Material.BLUE_ICE : Material.PACKED_ICE, frozen ? "&b&lUnfreeze" : "&b&lFreeze",
                List.of(frozen ? "&7Let them move again." : "&7Stop them moving and talking.", "", Lore.prompt(frozen ? "unfreeze" : "freeze"))),
                c -> {
                    if (tools == null) {
                        staff.sendMessage(Msg.bad("Freeze is not available (staff module off)."));
                        return;
                    }
                    if (tools.isFrozen(id)) {
                        tools.unfreeze(id);
                    } else if (target.hasPermission("genscore.freeze.exempt")) {
                        staff.sendMessage(Msg.bad("You can't freeze that player."));
                        return;
                    } else {
                        tools.freeze(target, staff.getName(), "staff menu");
                    }
                    profile(staff, id, back);
                });
        menu.set(33, MenuStyle.icon(Material.CHEST, "&6&lInventory", List.of("&7See and edit their inventory.", "", Lore.prompt("open"))),
                c -> staff.performCommand("invsee " + target.getName()));
        menu.set(34, MenuStyle.icon(Material.ENDER_CHEST, "&5&lEnder chest", List.of("&7See their ender chest.", "", Lore.prompt("open"))),
                c -> staff.performCommand("echest " + target.getName()));
        menu.set(42, MenuStyle.icon(Material.WRITABLE_BOOK, "&e&lInventory history", List.of("&7Saved inventories from deaths,", "&7logouts and restarts.", "", Lore.prompt("open"))),
                c -> staff.performCommand("invhistory " + target.getName()));
        menu.set(43, MenuStyle.icon(Material.CLOCK, "&a&lRefresh", List.of(Lore.prompt("refresh"))), c -> profile(staff, id, back));
        menu.set(45, MenuStyle.back("players"), c -> players(staff, back));
        MenuStyle.frame(menu, 6);
        menu.open(staff);
    }

    private void teleport(Player staff, Player target) {
        Tasks.entity.run(target, () -> {
            Location at = target.getLocation();
            Tasks.entity.run(staff, () -> {
                staff.closeInventory();
                staff.teleportAsync(at).thenAccept(ok -> {
                    if (ok) {
                        staff.sendMessage(Msg.good("Teleported to " + Msg.white(target.getName()) + "."));
                    }
                });
            });
        });
    }

    private static ItemStack head(Player p, String name, List<String> lore) {
        ItemStack item = Lore.item(Material.PLAYER_HEAD, name, lore);
        item.editMeta(SkullMeta.class, meta -> meta.setPlayerProfile(p.getPlayerProfile()));
        return MenuStyle.clean(item, false);
    }

    private static String value(UUID id, String key) {
        String v = FactoryModule.placeholder(id, key);
        return v == null ? "?" : v;
    }

    private static String credits(UUID id) {
        ShardsService s = ShardsModule.service();
        return s == null ? "?" : String.format("%,d", s.balance(id));
    }

    private static String strip(String legacy) {
        return legacy.replaceAll("&[0-9a-fk-or]", "");
    }

    private static String duration(long ms) {
        long minutes = ms / 60000;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) {
            return days + "d " + (hours % 24) + "h";
        }
        if (hours > 0) {
            return hours + "h " + (minutes % 60) + "m";
        }
        return minutes + "m";
    }
}
