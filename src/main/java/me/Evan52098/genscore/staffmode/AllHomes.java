package me.Evan52098.genscore.staffmode;

import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.teleport.SavedLocation;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.Optional;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * /allhomes [page|player]: every player's homes (online or offline), read from the teleport module's
 * {@code pvp_homes} table, with a click to teleport. Online players' homes are saved first when one player is asked
 * for; the full list can be up to one autosave behind for homes set in the last few minutes.
 */
public final class AllHomes {

    static final String PERM = "genscore.allhomes";
    private static final int PAGE_SIZE = 10;

    private record Row(UUID owner, String ownerName, String home, SavedLocation at) {
    }

    private final Database db;
    private final PlayerDataService players;
    private final Logger log;
    private final Cooldowns cooldowns = new Cooldowns();

    AllHomes(Database db, PlayerDataService players, Logger log) {
        this.db = db;
        this.players = players;
        this.log = log;
    }

    @Command({"allhomes", "homeslist"})
    @CommandPermission(PERM)
    @Description("Every player's homes, click to teleport")
    public void allHomes(BukkitCommandActor actor, @Named("page|player") @Optional @Nullable String arg) {
        CommandSender sender = actor.sender();
        UUID cooldownKey = sender instanceof Player p ? p.getUniqueId() : new UUID(0, 0);
        if (!cooldowns.tryStart(cooldownKey, "allhomes", 1500)) { // checklist 10
            sender.sendMessage(Msg.bad("Slow down."));
            return;
        }
        int page = 1;
        String name = null;
        if (arg != null && !arg.isBlank()) {
            try {
                page = Math.max(1, Integer.parseInt(arg.trim()));
            } catch (NumberFormatException e) {
                name = arg.trim();
            }
        }
        if (name != null) {
            showPlayer(sender, name);
        } else {
            showPage(sender, page);
        }
    }

    private void showPage(CommandSender sender, int page) {
        db.async(c -> {
            int total;
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_homes");
                 ResultSet rs = ps.executeQuery()) {
                total = rs.next() ? rs.getInt(1) : 0;
            }
            List<Row> rows = query(c, "SELECT h.uuid, COALESCE(p.name, h.uuid), h.name, h.world, h.x, h.y, h.z, h.yaw, h.pitch "
                    + "FROM pvp_homes h LEFT JOIN pvp_players p ON p.uuid = h.uuid "
                    + "ORDER BY LOWER(COALESCE(p.name, h.uuid)), h.name LIMIT ? OFFSET ?", null, PAGE_SIZE, (page - 1) * PAGE_SIZE);
            return new Object[]{total, rows};
        }).whenComplete((result, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                log.log(Level.WARNING, "[allhomes] lookup failed", error);
                sender.sendMessage(Msg.bad("Could not read the homes."));
                return;
            }
            int total = (Integer) result[0];
            @SuppressWarnings("unchecked") List<Row> rows = (List<Row>) result[1];
            int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
            sender.sendMessage(Msg.parse("<gold>All homes <gray>(" + total + " homes, page " + Math.min(page, pages) + "/" + pages + ")"));
            if (rows.isEmpty()) {
                sender.sendMessage(Msg.neutral("Nothing on this page."));
            }
            for (Row r : rows) {
                sender.sendMessage(line(r, true));
            }
            if (page < pages) {
                sender.sendMessage(Msg.parse("<gray>[Next page]").clickEvent(ClickEvent.runCommand("/allhomes " + (page + 1))));
            }
        }));
    }

    private void showPlayer(CommandSender sender, String name) {
        players.lookup(name).thenCompose(found -> {
            if (found.isEmpty()) {
                return CompletableFuture.completedFuture((List<Row>) null);
            }
            UUID uuid = found.get().uuid();
            // Online: write their latest homes first, so the list is exact.
            CompletableFuture<Void> saved = Bukkit.getPlayer(uuid) != null ? players.saveNow(uuid) : CompletableFuture.completedFuture(null);
            return saved.exceptionally(e -> null).thenCompose(v -> db.async(c -> query(c,
                    "SELECT h.uuid, COALESCE(p.name, h.uuid), h.name, h.world, h.x, h.y, h.z, h.yaw, h.pitch "
                            + "FROM pvp_homes h LEFT JOIN pvp_players p ON p.uuid = h.uuid WHERE h.uuid = ? ORDER BY h.name",
                    uuid.toString(), -1, -1)));
        }).whenComplete((rows, error) -> Tasks.forSender(sender, () -> {
            if (error != null) {
                log.log(Level.WARNING, "[allhomes] lookup failed", error);
                sender.sendMessage(Msg.bad("Could not read the homes."));
            } else if (rows == null) {
                sender.sendMessage(Msg.bad("No player called " + Msg.white(name) + " has joined."));
            } else if (rows.isEmpty()) {
                sender.sendMessage(Msg.neutral(Msg.white(name) + " has no homes."));
            } else {
                sender.sendMessage(Msg.parse("<gold>Homes of <white>" + Msg.escape(rows.get(0).ownerName()) + "</white> <gray>(" + rows.size() + ")"));
                for (Row r : rows) {
                    sender.sendMessage(line(r, false));
                }
            }
        }));
    }

    private static List<Row> query(Connection c, String sql, @Nullable String uuid, int limit, int offset) throws SQLException {
        List<Row> rows = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            if (uuid != null) {
                ps.setString(1, uuid);
            } else {
                ps.setInt(1, limit);
                ps.setInt(2, offset);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getString(3), SavedLocation.read(rs, 4)));
                }
            }
        }
        return rows;
    }

    private static Component line(Row r, boolean withOwner) {
        SavedLocation l = r.at();
        String where = Msg.escape(l.world()) + " " + Math.round(l.x()) + " " + Math.round(l.y()) + " " + Math.round(l.z());
        return Msg.parse("<gray> - " + (withOwner ? "<white>" + Msg.escape(r.ownerName()) + "</white>: " : "")
                        + "<aqua>" + Msg.escape(r.home()) + "</aqua> <dark_gray>(" + where + ")</dark_gray> <green>[TP]")
                .clickEvent(ClickEvent.runCommand("/allhomestp " + r.owner() + " " + r.home()))
                .hoverEvent(HoverEvent.showText(Msg.parse("<gray>Teleport to this home")));
    }

    @Command("allhomestp")
    @CommandPermission(PERM)
    @Description("Teleport to a player's home (used by /allhomes)")
    public void teleport(BukkitCommandActor actor, @Named("owner") String owner, @Named("home") String home) {
        Player staff = actor.requirePlayer();
        UUID uuid;
        try {
            uuid = UUID.fromString(owner);
        } catch (IllegalArgumentException e) {
            staff.sendMessage(Msg.bad("Use /allhomes and click a home."));
            return;
        }
        if (!cooldowns.tryStart(staff.getUniqueId(), "allhomestp", 1000)) {
            return;
        }
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT " + SavedLocation.COLUMNS + " FROM pvp_homes WHERE uuid = ? AND name = ?")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, home);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? SavedLocation.read(rs, 1) : null;
                }
            }
        }).whenComplete((saved, error) -> Tasks.entity.run(staff, () -> {
            if (error != null || saved == null) {
                staff.sendMessage(Msg.bad("That home no longer exists."));
                return;
            }
            Location to = saved.toLocation();
            if (to == null) {
                staff.sendMessage(Msg.bad("That home's world is not loaded."));
                return;
            }
            staff.teleportAsync(to).thenAccept(ok -> {
                if (ok) {
                    staff.sendMessage(Msg.good("Teleported to home " + Msg.white(home) + "."));
                }
            });
        }));
    }
}
