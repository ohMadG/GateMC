package me.Evan52098.genscore.mail;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.player.PlayerDataService;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.trade.OnlinePlayerSuggestions;
import me.Evan52098.genscore.util.Cooldowns;
import me.Evan52098.genscore.util.Msg;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import revxrsal.commands.annotation.Command;
import revxrsal.commands.annotation.Description;
import revxrsal.commands.annotation.Named;
import revxrsal.commands.annotation.SuggestWith;
import revxrsal.commands.bukkit.actor.BukkitCommandActor;
import revxrsal.commands.bukkit.annotation.CommandPermission;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Offline mail: /mail send, /mail (read), /mail clear, and the "you have unread mail" note at join. Ported from
 * BlockvilleCore's MailCommand, MailService, MailData and MailNotifyListener with the same messages.
 *
 * <p>BlockvilleCore mail carries text only (no items or money), so there is nothing to escrow. Added for a public PvP
 * server: a send cooldown, a length limit and a cap on how much unread mail one player can be sent (checklist 10).
 * Left out: the mute check (BlockvilleCore asked Heimdall; mutes are left to the punishment plugin here).
 */
public final class MailModule implements GensModule, Listener {

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(ZoneOffset.UTC);

    private record Mail(long id, String sender, String message, long createdAt) {
    }

    private final Cooldowns cooldowns = new Cooldowns();
    private Database db;
    private PlayerDataService players;
    private Logger logger;
    private long sendCooldownMillis;
    private int maxLength;
    private int maxUnread;

    @Override
    public String id() {
        return "mail";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        this.db = plugin.database();
        this.players = plugin.playerData();
        this.logger = plugin.getLogger();
        Migrations.apply(db, id(), logger,
                "CREATE TABLE IF NOT EXISTS pvp_mail ("
                        + db.autoIdColumn("id") + ", recipient_uuid TEXT NOT NULL, recipient_name TEXT NOT NULL, "
                        + "sender_uuid TEXT, sender_name TEXT NOT NULL, message TEXT NOT NULL, is_read INTEGER NOT NULL DEFAULT 0, "
                        + "created_at BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_mail_recipient ON pvp_mail (recipient_uuid, is_read)");
        ConfigurationSection c = plugin.getConfig().getConfigurationSection("mail");
        if (c == null) {
            c = new MemoryConfiguration();
        }
        sendCooldownMillis = TimeUnit.SECONDS.toMillis(Math.max(0, c.getLong("send-cooldown-seconds", 10)));
        maxLength = Math.max(16, c.getInt("max-length", 256));
        maxUnread = Math.max(1, c.getInt("max-unread-per-player", 50));
        plugin.commands().register(this);
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @Command("mail send")
    @CommandPermission("genscore.mail")
    @Description("Leave a player a message they'll see next time they log in")
    public void send(BukkitCommandActor actor, @Named("player") @SuggestWith(OnlinePlayerSuggestions.class) String targetName,
                     @Named("message") String message) {
        Player player = actor.requirePlayer();
        String text = message.strip();
        if (text.isEmpty()) {
            return;
        }
        if (text.length() > maxLength) {
            player.sendMessage(Msg.bad("That message is too long (" + Msg.white(String.valueOf(maxLength)) + " characters at most)."));
            return;
        }
        long wait = cooldowns.remaining(player.getUniqueId(), "mail");
        if (wait > 0) {
            player.sendMessage(Msg.bad("Wait " + Cooldowns.format(wait) + " before sending more mail."));
            return;
        }
        cooldowns.start(player.getUniqueId(), "mail", sendCooldownMillis);
        UUID sender = player.getUniqueId();
        String senderName = player.getName();
        players.lookup(targetName).thenCompose(found -> {
            if (found.isEmpty()) {
                return java.util.concurrent.CompletableFuture.completedFuture(Result.NOT_FOUND);
            }
            PlayerDataService.PlayerRecord to = found.get();
            return db.async(c -> {
                try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_mail WHERE recipient_uuid = ? AND is_read = 0")) {
                    ps.setString(1, to.uuid().toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) >= maxUnread) {
                            return Result.FULL;
                        }
                    }
                }
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO pvp_mail (recipient_uuid, recipient_name, sender_uuid, sender_name, message, created_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    ps.setString(1, to.uuid().toString());
                    ps.setString(2, to.name());
                    ps.setString(3, sender.toString());
                    ps.setString(4, senderName);
                    ps.setString(5, text);
                    ps.setLong(6, System.currentTimeMillis());
                    ps.executeUpdate();
                }
                return Result.SENT;
            });
        }).whenComplete((result, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to send mail to " + targetName, error);
                player.sendMessage(Msg.bad("Your mail could not be sent right now."));
                return;
            }
            switch (result) {
                case NOT_FOUND -> player.sendMessage(Msg.bad("Player not found."));
                case FULL -> player.sendMessage(Msg.bad(Msg.white(targetName) + "'s mailbox is full."));
                case SENT -> player.sendMessage(Msg.good("Mail sent to " + Msg.white(targetName) + "."));
            }
        }));
    }

    private enum Result { SENT, NOT_FOUND, FULL }

    @Command({"mail read", "mail"})
    @CommandPermission("genscore.mail")
    @Description("Read your mail")
    public void read(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        if (!cooldowns.tryStart(player.getUniqueId(), "mail-read", 1000)) {
            return;
        }
        UUID uuid = player.getUniqueId();
        db.async(c -> {
            List<Mail> rows = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(
                    "SELECT id, sender_name, message, created_at FROM pvp_mail WHERE recipient_uuid = ? ORDER BY created_at ASC, id ASC")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        rows.add(new Mail(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)));
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("UPDATE pvp_mail SET is_read = 1 WHERE recipient_uuid = ? AND is_read = 0")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
            return rows;
        }).whenComplete((rows, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to read mail for " + player.getName(), error);
                player.sendMessage(Msg.bad("Your mail could not be loaded right now."));
                return;
            }
            if (rows.isEmpty()) {
                player.sendMessage(Msg.neutral("You have no mail."));
                return;
            }
            player.sendMessage(Msg.parse("<gold><bold>Your Mail</bold></gold> <gray>(<red>" + rows.size() + "</red>)</gray>"));
            for (Mail row : rows) {
                String date = DATE_FORMAT.format(Instant.ofEpochMilli(row.createdAt()));
                player.sendMessage(Msg.parse("<gray>[<red>" + date + "</red>] <yellow>" + Msg.escape(row.sender()) + "</yellow><gray>: <white>"
                        + Msg.escape(row.message()) + "</white>"));
            }
        }));
    }

    @Command("mail clear")
    @CommandPermission("genscore.mail")
    @Description("Clear all of your mail")
    public void clear(BukkitCommandActor actor) {
        Player player = actor.requirePlayer();
        UUID uuid = player.getUniqueId();
        db.asyncRun(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM pvp_mail WHERE recipient_uuid = ?")) {
                ps.setString(1, uuid.toString());
                ps.executeUpdate();
            }
        }).whenComplete((v, error) -> Tasks.entity.run(player, () -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to clear mail for " + player.getName(), error);
                player.sendMessage(Msg.bad("Your mail could not be cleared right now."));
                return;
            }
            player.sendMessage(Msg.good("Your mail has been cleared."));
        }));
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        db.async(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM pvp_mail WHERE recipient_uuid = ? AND is_read = 0")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        }).whenComplete((count, error) -> {
            if (error != null) {
                logger.log(Level.WARNING, "Failed to check unread mail for " + player.getName(), error);
                return;
            }
            if (count > 0) {
                Tasks.entity.run(player, () -> player.sendMessage(Msg.neutral("You have <white>" + count
                        + "</white> unread mail message(s). Use <white>/mail read</white> to view them.")));
            }
        });
    }
}
