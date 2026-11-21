package me.Evan52098.genscore.punish;

import me.Evan52098.genscore.GensCore;
import me.Evan52098.genscore.module.GensModule;
import me.Evan52098.genscore.scheduler.Tasks;
import me.Evan52098.genscore.storage.Database;
import me.Evan52098.genscore.storage.Migrations;
import me.Evan52098.genscore.util.Msg;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public final class PunishModule implements GensModule {
    private static volatile PunishModule instance;

    private Warden warden;
    private PunishmentService punishmentService;
    private String appealUrl = "";

    @Override
    public String id() {
        return "punish";
    }

    @Override
    public void enable(GensCore plugin) throws Exception {
        Database db = plugin.database();
        Migrations.apply(db, id(), plugin.getLogger(),
                "CREATE TABLE IF NOT EXISTS pvp_punishments ("
                        + db.autoIdColumn("id") + ", short_id TEXT, target_uuid TEXT NOT NULL, target_name TEXT NOT NULL, type TEXT NOT NULL, "
                        + "reason TEXT NOT NULL, created_at BIGINT NOT NULL, expires_at BIGINT, staff_uuid TEXT, staff_name TEXT NOT NULL, "
                        + "active INTEGER NOT NULL DEFAULT 1, ip_address TEXT NOT NULL DEFAULT '', category TEXT NOT NULL DEFAULT '', "
                        + "severity INTEGER NOT NULL DEFAULT 0, remove_admin TEXT NOT NULL DEFAULT '', remove_reason TEXT NOT NULL DEFAULT '', removed_at BIGINT);"
                        + "CREATE UNIQUE INDEX IF NOT EXISTS pvp_punishments_short_id ON pvp_punishments (short_id);"
                        + "CREATE INDEX IF NOT EXISTS pvp_punishments_target ON pvp_punishments (target_uuid, type, active);"
                        + "CREATE INDEX IF NOT EXISTS pvp_punishments_staff ON pvp_punishments (staff_uuid);"
                        + "CREATE INDEX IF NOT EXISTS pvp_punishments_ip ON pvp_punishments (ip_address);"
                        + "CREATE INDEX IF NOT EXISTS pvp_punishments_created ON pvp_punishments (id DESC);"
                        + "CREATE TABLE IF NOT EXISTS pvp_login_ips ("
                        + db.autoIdColumn("id") + ", uuid TEXT NOT NULL, ip TEXT NOT NULL, last_seen BIGINT NOT NULL);"
                        + "CREATE INDEX IF NOT EXISTS pvp_login_ips_uuid ON pvp_login_ips (uuid);"
                        + "CREATE INDEX IF NOT EXISTS pvp_login_ips_ip ON pvp_login_ips (ip);"
                        + "CREATE TABLE IF NOT EXISTS pvp_alt_exemptions ("
                        + "target_uuid TEXT PRIMARY KEY, target_name TEXT NOT NULL, added_by_uuid TEXT, added_by_name TEXT NOT NULL, "
                        + "reason TEXT NOT NULL, created_at BIGINT NOT NULL);"
                        + "CREATE TABLE IF NOT EXISTS pvp_staff_lockouts ("
                        + db.autoIdColumn("id") + ", staff_name TEXT NOT NULL, staff_uuid TEXT, action_count INTEGER NOT NULL, "
                        + "window_seconds INTEGER NOT NULL, tripped_at BIGINT NOT NULL, cleared INTEGER NOT NULL DEFAULT 0, "
                        + "cleared_by TEXT, cleared_at BIGINT);"
                        + "CREATE INDEX IF NOT EXISTS pvp_staff_lockouts_name ON pvp_staff_lockouts (staff_name, cleared);"
                        + NoteService.schema(db),
                "CREATE TABLE IF NOT EXISTS gens_mutes (uuid TEXT PRIMARY KEY, name TEXT NOT NULL, until BIGINT NOT NULL, "
                        + "reason TEXT NOT NULL, muted_by TEXT NOT NULL, at BIGINT NOT NULL);"
                        + "INSERT INTO pvp_punishments (target_uuid, target_name, type, reason, created_at, expires_at, staff_uuid, staff_name, "
                        + "active, ip_address, category, severity, remove_admin, remove_reason, removed_at) "
                        + "SELECT uuid, name, 'MUTE', reason, at, until, NULL, muted_by, 1, '', 'CHAT', "
                        + "CASE WHEN reason = 'Chat filter violations (report accepted)' THEN 6 ELSE 0 END, '', '', NULL FROM gens_mutes");

        ConfigurationSection c = plugin.getConfig().getConfigurationSection("punish");
        if (c == null) {
            c = new MemoryConfiguration();
        }

        punishmentService = new PunishmentService(db, plugin.getLogger());
        punishmentService.backfillShortIds();
        PunishWebhook webhook = new PunishWebhook(c.getString("discord-webhook-url", ""), plugin.getLogger());

        ConfigurationSection lockoutSection = c.getConfigurationSection("staff-lockout");
        boolean lockoutEnabled = lockoutSection == null || lockoutSection.getBoolean("enabled", true);
        int lockoutWindow = lockoutSection == null ? 30 : lockoutSection.getInt("window-seconds", 30);
        int lockoutMax = lockoutSection == null ? 5 : lockoutSection.getInt("max-actions", 5);
        StaffLockoutService lockoutService = new StaffLockoutService(db, lockoutEnabled, lockoutWindow, lockoutMax, webhook);
        lockoutService.onTripped((staffName, lockout) -> Tasks.global.run(() -> {
            String message = staffName + " has been locked out of punishments after " + lockout.actionCount() + " actions in "
                    + lockout.windowSeconds() + "s - possible compromised or rogue admin. An admin must run /punish clearlockout to restore access.";
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.hasPermission(PunishCommand.PERMISSION_ADMIN)) {
                    Tasks.onEntity(online, () -> online.sendMessage(Msg.bad(Msg.escape(message))));
                }
            }
            plugin.getLogger().warning("[Punish] " + message);
        }));

        ConfigurationSection altBan = c.getConfigurationSection("alt-ban-evasion");
        boolean altBanEnabled = altBan == null || altBan.getBoolean("enabled", true);
        boolean ipTrackingEnabled = altBan == null || altBan.getBoolean("ip-tracking", true);
        int loginIpRetentionDays = c.getInt("login-ip-retention-days", 90);

        appealUrl = c.getString("appeal-url", "");
        long warnCooldownMillis = (long) (c.getDouble("warn-cooldown-hours", 1.0) * 3_600_000L);

        warden = new Warden(punishmentService, lockoutService, webhook, appealUrl, ipTrackingEnabled, altBanEnabled, warnCooldownMillis);

        ChatInput chatInput = new ChatInput();
        NoteService noteService = new NoteService(db);
        plugin.commands().register(new PunishCommand(warden, plugin.playerData(), chatInput, noteService));
        Bukkit.getPluginManager().registerEvents(chatInput, plugin);
        Bukkit.getPluginManager().registerEvents(new PunishListener(warden, appealUrl), plugin);

        Tasks.async.timer(t -> punishmentService.purgeOldLogins(loginIpRetentionDays), 60, 60, TimeUnit.MINUTES);
        Tasks.async.timer(t -> punishmentService.pruneMuteStates(), 10, 10, TimeUnit.MINUTES);
        for (Player online : Bukkit.getOnlinePlayers()) {
            punishmentService.getActiveMuteState(online.getUniqueId());
        }
        instance = this;
    }

    @Override
    public void disable() {
        instance = null;
    }

    public Warden warden() {
        return warden;
    }

    public PunishmentService service() {
        return punishmentService;
    }

    public static @Nullable Component chatBlock(UUID player) {
        PunishModule m = instance;
        if (m == null) {
            return null;
        }
        if (m.punishmentService.isMutePending(player)) {
            return Msg.bad("You are muted.");
        }
        PunishmentData mute = m.punishmentService.cachedMuteState(player).mute();
        return mute == null ? null : PunishMessages.muteMessage(mute, m.appealUrl);
    }

    public static boolean shadowMuted(UUID player) {
        PunishModule m = instance;
        return m != null && m.punishmentService.cachedMuteState(player).shadowMute() != null;
    }

    public static @Nullable String privateMessageBlock(UUID player) {
        PunishModule m = instance;
        if (m == null) {
            return null;
        }
        if (m.punishmentService.isMutePending(player)) {
            return "you are muted";
        }
        PunishmentData mute = m.punishmentService.cachedMuteState(player).mute();
        if (mute == null) {
            return null;
        }
        return mute.expiresAt() == null ? "you are permanently muted"
                : "you are muted for " + PunishMessages.duration(mute.expiresAt() - System.currentTimeMillis());
    }

    public static CompletableFuture<String> mute(UUID target, String targetName, long millis, String reason, @Nullable UUID staffUuid, String staffName) {
        PunishModule m = instance;
        if (m == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("the punish module is off"));
        }
        PunishmentSentence sentence = new PunishmentSentence(Category.CHAT, 0, false, millis, 0);
        return m.warden.apply(sentence, target, targetName, staffUuid == null ? Warden.SYSTEM_STAFF_UUID : staffUuid, staffName, reason)
                .thenApply(result -> outcome(targetName, result));
    }

    public static CompletableFuture<Boolean> unmute(UUID target, String by) {
        PunishModule m = instance;
        if (m == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("the punish module is off"));
        }
        return m.punishmentService.getActiveMuteState(target).thenCompose(state -> {
            CompletableFuture<Boolean> mute = state.mute() == null ? CompletableFuture.completedFuture(false)
                    : m.warden.revoke(state.mute().id(), by, "Unmuted with /unmute");
            CompletableFuture<Boolean> shadow = state.shadowMute() == null ? CompletableFuture.completedFuture(false)
                    : m.warden.revoke(state.shadowMute().id(), by, "Unmuted with /unmute");
            return mute.thenCombine(shadow, (a, b) -> a || b);
        });
    }

    public static CompletableFuture<String> punishFilterReport(UUID target, String targetName, @Nullable UUID staffUuid, String staffName, long reportId) {
        PunishModule m = instance;
        if (m == null) {
            return CompletableFuture.failedFuture(new IllegalStateException("the punish module is off"));
        }
        return m.warden.applyFilterStrike(target, targetName, staffUuid, staffName, "Chat filter violations (report #" + reportId + ")")
                .thenApply(result -> outcome(targetName, result));
    }

    private static String outcome(String targetName, Warden.ApplyResult result) {
        if (result.lockedOut()) {
            throw new IllegalStateException("you are locked out of issuing punishments");
        }
        if (result.isProtected()) {
            throw new IllegalStateException(result.protectedMessage());
        }
        if (result.isConflict()) {
            PunishmentData conflict = result.conflict();
            throw new IllegalStateException(targetName + " already has an active " + conflict.type().toLowerCase(Locale.ROOT) + " (" + conflict.displayId() + ")");
        }
        PunishmentData created = result.created();
        if (created == null) {
            throw new IllegalStateException("the punishment could not be recorded");
        }
        String verb = PunishmentService.TYPE_BAN.equals(created.type()) ? "banned" : "muted";
        String length = created.expiresAt() == null ? "permanently" : "for " + PunishMessages.duration(created.expiresAt() - created.createdAt());
        return verb + " " + length + " (" + created.displayId() + ")";
    }
}
